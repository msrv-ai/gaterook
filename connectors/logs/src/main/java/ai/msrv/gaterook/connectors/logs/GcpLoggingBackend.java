package ai.msrv.gaterook.connectors.logs;

import com.google.auth.oauth2.GoogleCredentials;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.GcpSpec;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * GCP Cloud Logging over REST ({@code POST v2/entries:list}, report 07 §3.4): google-auth only for the token
 * (impersonation 900 s by default), the request itself on {@code java.net.http}. The projects or log views are the
 * connection's; the filter always adds the connection's scope filter with {@code AND}. Every request spends one of
 * the project's 60 {@code entries.list} calls per minute, which people share: the gateway takes its configured share
 * through {@code ExecContext.acquire} and never retries a {@code 429}.
 */
final class GcpLoggingBackend implements Backend {

  static final String LOGGING_READ = "https://www.googleapis.com/auth/logging.read";
  static final String PLATFORM_READ_ONLY = "https://www.googleapis.com/auth/cloud-platform.read-only";
  static final URI LOGGING = URI.create("https://logging.googleapis.com");
  static final URI RESOURCE_MANAGER = URI.create("https://cloudresourcemanager.googleapis.com");
  /** Pages per call: an empty page with a token means "keep looking", but each one costs quota. */
  static final int MAX_PAGES = 3;
  static final int MAX_PAGE_SIZE = 1_000;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  /** Permissions Test connection asks about; the last four mean the account can change logs. */
  private static final List<String> PERMISSIONS = List.of("logging.logEntries.list",
      "logging.privateLogEntries.list", "logging.logEntries.create", "logging.logs.delete", "logging.buckets.update",
      "logging.sinks.update");

  private final Settings.Gcp s;
  private final Call call;

  GcpLoggingBackend(Settings.Gcp s, Call call) {
    this.s = s;
    this.call = call;
  }

  @Override
  public Page newest(String query, String source, long fromNs, long toNs, int want, String continuation)
      throws ConnectorException {
    return list(query, source, fromNs, toNs, want, continuation, "timestamp desc");
  }

  @Override
  public Page oldest(String query, String source, long fromNs, long toNs, int want) throws ConnectorException {
    return list(query, source, fromNs, toNs, want, null, "timestamp asc");
  }

  @Override
  public Duration pollEvery() {
    return Duration.ofSeconds(5);
  }

  @Override
  public Duration delay() {
    return Duration.ofSeconds(10);
  }

  /**
   * {@code (scope) AND (query) AND timestamp>="…" AND timestamp<="…"}: what goes to Cloud Logging, one part per line.
   * A comment ({@code --} to the end of the line) can't reach past its own part: not the time range the gateway
   * adds, nor the query after the scope filter. The agent's query can't hold one at all ({@link LogsRequest}); a scope
   * filter with a comment closes its parenthesis on the next line. {@code AND} binds weakest in this language (NOT,
   * then OR, then AND), so no {@code OR} inside a part reaches past it.
   */
  static String filter(Settings.Gcp s, String query, long fromNs, long toNs) {
    List<String> parts = new ArrayList<>();
    if (s.scopeFilter() != null) {
      parts.add(group(s.scopeFilter()));
    }
    if (!query.isBlank()) {
      parts.add(group(query));
    }
    parts.add("timestamp>=\"" + instant(fromNs) + "\"");
    parts.add("timestamp<=\"" + instant(toNs) + "\"");
    return String.join("\nAND ", parts);
  }

  private static String group(String part) {
    return part.contains("--") ? "(" + part + "\n)" : "(" + part + ")";
  }

  private Page list(String query, String source, long fromNs, long toNs, int want, String continuation,
      String orderBy) throws ConnectorException {
    List<String> resources = source != null ? List.of(source) : s.resourceNames();
    String filter = filter(s, query, fromNs, toNs);
    List<LogEntry> entries = new ArrayList<>();
    String token = continuation;
    int requests = 0;
    String partial = null;
    while (requests < MAX_PAGES) {
      if (!call.ctx().acquire(s.quotaKey(), s.quotaPerMinute())) {
        if (requests == 0) {
          throw ConnectorException.resourceError("GateRook's share of Cloud Logging reads for "
              + s.resourceNames().get(0) + " (" + s.quotaPerMinute() + " per minute) is used up. Try again in a "
              + "minute.", null);
        }
        partial = "partial";
        break;
      }
      ObjectNode body = JsonNodeFactory.instance.objectNode();
      ArrayNode names = body.putArray("resourceNames");
      resources.forEach(names::add);
      body.put("filter", filter);
      body.put("orderBy", orderBy);
      body.put("pageSize", Math.min(MAX_PAGE_SIZE, Math.max(1, want - entries.size())));
      if (token != null) {
        body.put("pageToken", token);
      }
      JsonNode page = post(logging().resolve("/v2/entries:list"), body, "Cloud Logging");
      requests++;
      for (JsonNode e : page.path("entries")) {
        entries.add(entry(e));
      }
      token = page.path("nextPageToken").asString(null);
      if (token != null && token.isBlank()) {
        token = null;
      }
      if (entries.size() >= want || token == null) {
        break;
      }
      if (call.ctx().remaining().compareTo(Duration.ofSeconds(1)) < 0) {
        partial = "timeout";
        break;
      }
    }
    Page p = Page.of(entries, token != null || entries.size() >= want, token, Page.Stats.requests(requests));
    return partial == null ? p : p.withPartial(partial);
  }

  private LogEntry entry(JsonNode e) {
    long ns = nanos(e.path("timestamp").asString(null), e.path("receiveTimestamp").asString(null));
    Map<String, String> labels = new LinkedHashMap<>();
    e.at("/resource/labels").properties().forEach(l -> labels.put(l.getKey(), l.getValue().asString()));
    e.path("labels").properties().forEach(l -> labels.putIfAbsent(l.getKey(), l.getValue().asString()));
    String type = e.at("/resource/type").asString(null);
    if (type != null) {
      labels.put("resource_type", type);
    }
    String logName = e.path("logName").asString("");
    if (!logName.isEmpty()) {
      int slash = logName.lastIndexOf('/');
      labels.put("log", URLDecoder.decode(logName.substring(slash + 1), StandardCharsets.UTF_8));
    }
    String trace = e.path("trace").asString(null);
    if (trace != null) {
      labels.put("trace", trace);
    }
    String message;
    ObjectNode fields = null;
    if (e.path("textPayload").isString()) {
      message = e.path("textPayload").asString();
    } else if (e.path("jsonPayload").isObject()) {
      fields = (ObjectNode) e.path("jsonPayload");
      JsonNode m = fields.get("message");
      message = m != null && m.isString() ? m.asString() : fields.toString();
    } else if (e.path("protoPayload").isObject()) {
      fields = (ObjectNode) e.path("protoPayload");
      message = (fields.path("methodName").asString("") + " " + fields.path("resourceName").asString("") + " by "
          + fields.at("/authenticationInfo/principalEmail").asString("unknown")).strip();
    } else {
      message = "";
    }
    String insertId = e.path("insertId").asString(null);
    String id = insertId != null ? insertId : LogEntry.hashId(Long.toString(ns), logName, message);
    return LogEntry.of(ns, Severity.of(e.path("severity").asString(null)), message, labels, fields, id,
        call.maxChars());
  }

  static long nanos(String timestamp, String fallback) {
    String t = timestamp != null ? timestamp : fallback;
    if (t == null) {
      return 0;
    }
    Instant i;
    try {
      i = Instant.parse(t);
    } catch (DateTimeParseException e) {
      i = OffsetDateTime.parse(t).toInstant();
    }
    return Math.addExact(Math.multiplyExact(i.getEpochSecond(), 1_000_000_000L), i.getNano());
  }

  static String instant(long ns) {
    return Instant.ofEpochSecond(Math.floorDiv(ns, 1_000_000_000L), Math.floorMod(ns, 1_000_000_000L)).toString();
  }

  // ── Test, health, describe ──────────────────────────────────────────────────────────────────────────────────

  @Override
  public TestResult test() {
    Probe probe = new Probe(call.ctx());
    List<TestResult.Hint> hints = new ArrayList<>();
    String identity = s.credentials().identity();
    long dataMs;
    TestResult.Level level = TestResult.Level.UNKNOWN;
    List<String> admin = new ArrayList<>();
    try {
      probe.step("token", () -> {
        headers(logging(), List.of(LOGGING_READ));
        return s.credentials().impersonate() != null ? "impersonating " + s.credentials().impersonate()
            + " for " + s.credentials().lifetimeSeconds() / 60 + " min" : identity;
      });
      long started = System.nanoTime();
      JsonNode newest = probe.step("read", () -> {
        if (!call.ctx().acquire(s.quotaKey(), s.quotaPerMinute())) {
          throw ConnectorException.resourceError("GateRook's share of Cloud Logging reads is used up. Test again "
              + "in a minute.", null);
        }
        long now = call.ctx().clock().instant().toEpochMilli() * 1_000_000L;
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        ArrayNode names = body.putArray("resourceNames");
        s.resourceNames().forEach(names::add);
        body.put("filter", filter(s, "", now - Duration.ofHours(1).toNanos(), now));
        body.put("orderBy", "timestamp desc");
        body.put("pageSize", 1);
        return post(logging().resolve("/v2/entries:list"), body, "Cloud Logging");
      }, r -> r.path("entries").isEmpty() ? "no entries in the last hour"
          : "newest entry " + age(r.path("entries").get(0)));
      dataMs = (System.nanoTime() - started) / 1_000_000;
      if (newest.path("entries").isEmpty()) {
        hints.add(new TestResult.Hint(TestResult.Severity.INFO, "logs.empty", "/scopeFilter",
            "No logs in the last hour. Check the projects and the scope filter."));
      }
      for (String resource : s.resourceNames()) {
        if (!resource.startsWith("projects/") || resource.contains("/locations/")) {
          continue;
        }
        String project = resource.substring("projects/".length());
        JsonNode granted = probe.optional("permissions on " + project, () -> {
          ObjectNode body = JsonNodeFactory.instance.objectNode();
          ArrayNode p = body.putArray("permissions");
          PERMISSIONS.forEach(p::add);
          return post(resourceManager().resolve("/v3/projects/" + project + ":testIamPermissions"), body,
              PLATFORM_READ_ONLY, "Cloud Resource Manager");
        }, r -> r.path("permissions").size() + " of " + PERMISSIONS.size() + " permissions");
        if (granted == null) {
          continue;
        }
        List<String> have = new ArrayList<>();
        granted.path("permissions").forEach(p -> have.add(p.asString()));
        level = level == TestResult.Level.WRITE ? level : TestResult.Level.READ;
        List<String> writes = have.stream().filter(p -> !p.endsWith(".list")).toList();
        if (!writes.isEmpty()) {
          level = TestResult.Level.WRITE;
          admin.addAll(writes);
          hints.add(new TestResult.Hint(call.cfg().env() == Env.PROD ? TestResult.Severity.WARNING
              : TestResult.Severity.INFO, "logs.can-write", "/credentials",
              "This account can write or delete logs in " + project + " (" + String.join(", ", writes)
                  + "). Use roles/logging.viewer for prod."));
        }
        if (have.contains("logging.privateLogEntries.list")) {
          hints.add(new TestResult.Hint(TestResult.Severity.INFO, "logs.private-logs", null,
              "Can read Data Access audit logs in " + project + "."));
        }
      }
    } catch (ConnectorException e) {
      return probe.failure(e);
    }
    if (s.credentials().source() == GcpSpec.Source.SERVICE_ACCOUNT_KEY && s.credentials().impersonate() == null
        && call.cfg().env() == Env.PROD) {
      hints.add(new TestResult.Hint(TestResult.Severity.WARNING, "logs.long-lived-key", "/credentials/impersonate",
          "Use impersonation on prod: a stored key doesn't expire, a borrowed token lasts 15 min."));
    }
    TestResult result = TestResult.success(dataMs, "Cloud Logging API v2", identity,
        new TestResult.Permissions(level, "Reads " + String.join(", ", s.resourceNames()),
            level == TestResult.Level.WRITE ? "Can write or delete logs" : level == TestResult.Level.READ ? "None"
                : "Unknown", List.copyOf(admin), null))
        .withChecks(probe.checks())
        .withMetadata(JsonNodeFactory.instance.objectNode().put("serverVersion", "Cloud Logging"));
    for (TestResult.Hint h : hints) {
      result = result.withHint(h);
    }
    return result;
  }

  @Override
  public HealthResult health() {
    // A token is enough: reading entries would spend the project's shared quota every few minutes.
    long started = System.nanoTime();
    try {
      headers(logging(), List.of(LOGGING_READ));
      return HealthResult.healthy((System.nanoTime() - started) / 1_000_000);
    } catch (ConnectorException e) {
      return HealthResult.unreachable(e.getMessage());
    }
  }

  @Override
  public ObjectNode describe(String object) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    ArrayNode names = out.putArray("resourceNames");
    s.resourceNames().forEach(names::add);
    if (s.scopeFilter() != null) {
      out.put("scopeFilter", s.scopeFilter());
    }
    out.put("quotaPerMinute", s.quotaPerMinute());
    ArrayNode examples = out.putArray("examples");
    examples.add("severity>=ERROR");
    examples.add("resource.type=\"k8s_container\" AND resource.labels.namespace_name=\"app\"");
    examples.add("jsonPayload.message:\"timeout\"");
    return out;
  }

  // ── HTTP ────────────────────────────────────────────────────────────────────────────────────────────────────

  private URI logging() {
    URI test = call.cloud().endpoints().service("gcp.logging");
    return test != null ? test : LOGGING;
  }

  private URI resourceManager() {
    URI test = call.cloud().endpoints().service("gcp.cloudresourcemanager");
    return test != null ? test : RESOURCE_MANAGER;
  }

  private Map<String, List<String>> headers(URI uri, List<String> scopes) throws ConnectorException {
    GoogleCredentials credentials = call.cloud().gcp(owner(), s.credentials(), scopes, call.secrets());
    return CloudCredentials.requestMetadata(credentials, uri, call.ctx()::taint);
  }

  private CloudCredentials.Owner owner() {
    return new CloudCredentials.Owner(call.cfg().id(), call.cfg().version(), call.cfg().name());
  }

  private JsonNode post(URI uri, ObjectNode body, String what) throws ConnectorException {
    return post(uri, body, LOGGING_READ, what);
  }

  private JsonNode post(URI uri, ObjectNode body, String scope, String what) throws ConnectorException {
    HttpRequest.Builder b = HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
        .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    for (Map.Entry<String, List<String>> h : headers(uri, List.of(scope)).entrySet()) {
      for (String v : h.getValue()) {
        b.header(h.getKey(), v);
      }
    }
    Http.Response r = Http.send(call.clients().google(), b, call.ctx(), what);
    JsonNode tree;
    try {
      tree = JSON.readTree(r.body().isBlank() ? "{}" : r.body());
    } catch (JacksonException e) {
      throw ConnectorException.resourceError(what + " answered " + r.status() + " with something that isn't JSON: "
          + Http.excerpt(r.body()), null);
    }
    if (!r.ok()) {
      throw error(r.status(), tree, what);
    }
    return tree;
  }

  private ConnectorException error(int status, JsonNode tree, String what) {
    String message = Http.excerpt(tree.at("/error/message").asString(""));
    String code = tree.at("/error/status").asString("");
    return switch (status) {
      case 400 -> ConnectorException.badInput(what + " refused the filter: " + message);
      case 401 -> ConnectorException.unavailable(what + " refused the token: " + message
          + ". Sign in again with gcloud auth application-default login, then test again.", null);
      case 403 -> ConnectorException.resourceError(what + " denied access: " + message + ". Grant roles/logging.viewer"
          + " on the project (or roles/logging.viewAccessor on the log view) to " + s.credentials().identity()
          + ".", null);
      case 404 -> ConnectorException.resourceError(what + ": " + message + ". Check the resource names.", null);
      case 429 -> ConnectorException.resourceError("Cloud Logging's read quota for this project is used up ("
          + code + "): it allows 60 reads per minute, shared with people and other tools. Try again in a minute.",
          null);
      default -> status >= 500 ? ConnectorException.unavailable(what + " failed (" + status + "): " + message, null)
          : ConnectorException.resourceError(what + " answered " + status + ": " + message, null);
    };
  }

  private String age(JsonNode entry) {
    long ns = nanos(entry.path("timestamp").asString(null), entry.path("receiveTimestamp").asString(null));
    long seconds = Math.max(0, call.ctx().clock().instant().getEpochSecond() - ns / 1_000_000_000L);
    return seconds < 120 ? seconds + " s ago" : seconds / 60 + " min ago";
  }
}
