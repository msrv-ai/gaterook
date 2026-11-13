package com.gaterook.connectors.logs;

import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.HealthResult;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connectors.cloudauth.AzureCloud.Audience;
import com.gaterook.connectors.cloudauth.AzureCredentials;
import com.gaterook.connectors.cloudauth.AzureSpec;
import com.gaterook.connectors.cloudauth.AzureTrust;
import com.gaterook.connectors.cloudauth.CloudCredentials;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
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
 * Azure Monitor Logs over the Log Analytics query API ({@code POST /v1/workspaces/<id>/query}, and for an Application
 * Insights component the resource-centric {@code POST /v1<resourceId>/query}), with an Entra ID token for Log
 * Analytics. The workspaces and components are the connection's: several workspaces go into one request through the
 * API's {@code workspaces} list, which only the gateway fills; a query that names other resources itself
 * ({@code workspace()}, {@code app()}, …) is refused before it is sent ({@link KqlQuery}).
 *
 * <p>The window is the request's {@code timespan} and an inclusive {@code where} on the time column
 * ({@code TimeGenerated} in a workspace, {@code timestamp} in a component's tables). A query of log entries gets
 * {@code | top <n> by <time> desc} (a tail: {@code asc}); a query that aggregates ({@code summarize}, {@code count},
 * …) is answered as a table. Every token's tenant must be the connection's pinned tenant, and goes to the call's taint.
 */
final class AzureMonitorBackend implements Backend {

  /** Requests per minute GateRook takes from the workspace's query limits (200 per 30 s per user). */
  static final int PER_MINUTE = 60;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final List<String> MESSAGE_COLUMNS = List.of("Message", "message", "RenderedDescription",
      "ResultDescription", "OuterMessage", "outerMessage", "LogMessage", "Log", "Description", "Name", "name",
      "OperationName");
  private static final List<String> LEVEL_COLUMNS = List.of("SeverityLevel", "severityLevel", "Level", "level",
      "LogLevel", "Severity", "severity");
  private static final List<String> LABEL_COLUMNS = List.of("Type", "itemType", "_ResourceId", "Computer",
      "AppRoleName", "cloud_RoleName", "OperationName", "Category", "ContainerName", "PodName");

  private final Settings.AzureMonitor s;
  private final Call call;

  AzureMonitorBackend(Settings.AzureMonitor s, Call call) {
    this.s = s;
    this.call = call;
  }

  @Override
  public Page newest(String query, String source, long fromNs, long toNs, int want, String continuation)
      throws ConnectorException {
    return run(query, source, fromNs, toNs, want, false);
  }

  @Override
  public Page oldest(String query, String source, long fromNs, long toNs, int want) throws ConnectorException {
    if (KqlQuery.aggregates(query)) {
      throw ConnectorException.badInput("A tail reads log entries: leave summarize, count and the like out of the "
          + "query, or use a search.");
    }
    return run(query, source, fromNs, toNs, want, true);
  }

  @Override
  public Duration pollEvery() {
    return Duration.ofSeconds(5);
  }

  /** Log Analytics makes most records queryable within a minute; later ones only a search finds. */
  @Override
  public Duration delay() {
    return Duration.ofSeconds(60);
  }

  /** The query as it goes to Log Analytics for one kind of source. */
  static String effective(String query, String timeColumn, long fromNs, long toNs, int want, boolean ascending) {
    String q = query == null || query.isBlank() ? "search *" : query.strip();
    while (q.endsWith(";")) {
      q = q.substring(0, q.length() - 1).strip();
    }
    if (KqlQuery.aggregates(q)) {
      return q + "\n| take " + want;
    }
    return q + "\n| where " + timeColumn + " between (datetime(" + kusto(fromNs, false) + ") .. datetime("
        + kusto(toNs, true) + "))\n| top " + want + " by " + timeColumn + (ascending ? " asc" : " desc");
  }

  private Page run(String query, String source, long fromNs, long toNs, int want, boolean ascending)
      throws ConnectorException {
    List<String> workspaces = new ArrayList<>();
    List<String> components = new ArrayList<>();
    for (String src : source != null ? List.of(source) : s.sources()) {
      (Settings.AzureMonitor.component(src) ? components : workspaces).add(src);
    }
    boolean aggregate = KqlQuery.aggregates(query == null ? "" : query);
    List<LogEntry> entries = new ArrayList<>();
    Page.Aggregate table = null;
    int requests = 0;
    String partial = null;
    if (!workspaces.isEmpty()) {
      Answer a = query(URI.create(base() + "/v1/workspaces/" + workspaces.getFirst() + "/query"),
          workspaces.subList(1, workspaces.size()), effective(query, "TimeGenerated", fromNs, toNs, want, ascending),
          fromNs, toNs);
      requests++;
      partial = a.partial ? "partial" : null;
      if (aggregate) {
        table = a.table();
      } else {
        entries.addAll(a.entries("TimeGenerated", workspaces.size() == 1 ? workspaces.getFirst() : null));
      }
    }
    for (String component : components) {
      if (aggregate && table != null) {
        break;
      }
      if (requests > 0 && call.ctx().remaining().compareTo(Duration.ofSeconds(2)) < 0) {
        partial = "timeout";
        break;
      }
      Answer a = query(URI.create(base() + "/v1" + encodePath(component) + "/query"), List.of(),
          effective(query, "timestamp", fromNs, toNs, want, ascending), fromNs, toNs);
      requests++;
      if (a.partial) {
        partial = "partial";
      }
      if (aggregate) {
        table = a.table();
      } else {
        entries.addAll(a.entries("timestamp", component));
      }
    }
    Page.Stats stats = Page.Stats.requests(requests);
    if (aggregate) {
      return new Page(List.of(), false, null, partial, table == null ? new Page.Aggregate(List.of(),
          JsonNodeFactory.instance.arrayNode()) : table, stats);
    }
    Comparator<LogEntry> order = Comparator.comparingLong(LogEntry::nanos);
    entries.sort(ascending ? order : order.reversed());
    boolean more = entries.size() >= want;
    List<LogEntry> kept = entries.size() > want ? entries.subList(0, want) : entries;
    Page p = Page.of(kept, more, null, stats);
    return partial == null ? p : p.withPartial(partial);
  }

  // ── HTTP ────────────────────────────────────────────────────────────────────────────────────────────────────

  /** One answer of the query API: its first table, and whether Log Analytics said the result is partial. */
  private record Answer(List<String> columns, List<String> types, ArrayNode rows, boolean partial, int maxChars,
      String sourceLabel) {

    Page.Aggregate table() {
      ArrayNode out = JsonNodeFactory.instance.arrayNode();
      for (JsonNode row : rows) {
        ObjectNode o = out.addObject();
        for (int i = 0; i < columns.size() && i < row.size(); i++) {
          o.set(columns.get(i), sanitized(row.get(i)));
        }
      }
      return new Page.Aggregate(columns, out);
    }

    List<LogEntry> entries(String timeColumn, String source) throws ConnectorException {
      int time = columns.indexOf(timeColumn);
      if (time < 0) {
        throw ConnectorException.badInput("The query's result has no " + timeColumn + " column, so its rows aren't "
            + "log entries: keep " + timeColumn + " (project it), or summarize for a table.");
      }
      List<LogEntry> out = new ArrayList<>();
      for (JsonNode row : rows) {
        long ns = nanos(row.path(time).asString(""));
        ObjectNode fields = JsonNodeFactory.instance.objectNode();
        for (int i = 0; i < columns.size() && i < row.size(); i++) {
          if (i != time && !row.get(i).isNull() && !(row.get(i).isString() && row.get(i).asString().isEmpty())) {
            fields.set(columns.get(i), row.get(i));
          }
        }
        String message = null;
        for (String c : MESSAGE_COLUMNS) {
          if (fields.path(c).isString() && !fields.path(c).asString().isBlank()) {
            message = fields.path(c).asString();
            break;
          }
        }
        if (message == null) {
          message = fields.toString();
        }
        String level = null;
        for (String c : LEVEL_COLUMNS) {
          JsonNode v = fields.get(c);
          if (v != null && !v.isNull()) {
            level = v.asString();
            break;
          }
        }
        Severity severity = level == null ? Severity.ofLine(message) : appInsightsLevel(level);
        Map<String, String> labels = new LinkedHashMap<>();
        for (String c : LABEL_COLUMNS) {
          JsonNode v = fields.get(c);
          if (v != null && v.isValueNode() && !v.asString().isBlank()) {
            labels.put(c, v.asString());
          }
        }
        if (source != null) {
          labels.put("source", source);
        }
        String itemId = fields.path("_ItemId").asString(fields.path("itemId").asString(""));
        String id = itemId.isBlank() ? LogEntry.hashId(Long.toString(ns), sourceLabel, fields.toString()) : itemId;
        out.add(LogEntry.of(ns, severity, message, labels, fields, id, maxChars));
      }
      return out;
    }

    private static JsonNode sanitized(JsonNode n) {
      return n.isString() ? JsonNodeFactory.instance.stringNode(com.gaterook.connectors.support.OutputSanitizer
          .sanitize(n.asString())) : n;
    }
  }

  /** Application Insights' {@code severityLevel} is 0 (verbose) to 4 (critical); other columns are names. */
  static Severity appInsightsLevel(String level) {
    return switch (level.strip()) {
      case "0" -> Severity.DEBUG;
      case "1" -> Severity.INFO;
      case "2" -> Severity.WARNING;
      case "3" -> Severity.ERROR;
      case "4" -> Severity.CRITICAL;
      default -> Severity.of(level);
    };
  }

  private Answer query(URI uri, List<String> moreWorkspaces, String query, long fromNs, long toNs)
      throws ConnectorException {
    if (!call.ctx().acquire(s.quotaKey(), PER_MINUTE)) {
      throw ConnectorException.resourceError("GateRook's share of Log Analytics queries (" + PER_MINUTE + " per "
          + "minute) is used up. Try again in a minute.", null);
    }
    ObjectNode body = JsonNodeFactory.instance.objectNode();
    body.put("query", query);
    // The window, a second wider on both sides: the inclusive where in the query is the exact one.
    body.put("timespan", kusto(fromNs - 1_000_000_000L, false) + "/" + kusto(toNs + 1_000_000_000L, true));
    if (!moreWorkspaces.isEmpty()) {
      ArrayNode w = body.putArray("workspaces");
      moreWorkspaces.forEach(w::add);
    }
    JsonNode tree = post(uri, body, "Log Analytics");
    JsonNode first = tree.path("tables").path(0);
    List<String> columns = new ArrayList<>();
    List<String> types = new ArrayList<>();
    for (JsonNode c : first.path("columns")) {
      columns.add(c.path("name").asString(""));
      types.add(c.path("type").asString(""));
    }
    JsonNode rows = first.path("rows");
    boolean partial = tree.has("error") && !tree.path("error").isNull();
    String label = uri.getPath();
    return new Answer(columns, types, rows.isArray() ? (ArrayNode) rows : JsonNodeFactory.instance.arrayNode(),
        partial, call.maxChars(), label);
  }

  private JsonNode post(URI uri, ObjectNode body, String what) throws ConnectorException {
    AzureSpec spec = s.credentials();
    if (!call.cloud().azure().sendsTokensTo(spec.cloud(), Audience.LOG_ANALYTICS, uri)) {
      throw new IllegalArgumentException("Not a Log Analytics URL of " + spec.cloud().id());
    }
    AzureCredentials.AccessToken token = token(true);
    HttpRequest.Builder b = HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
        .header("Accept", "application/json").header("Authorization", "Bearer " + token.value())
        .header("Prefer", "wait=" + Math.max(5, Math.min(120, call.ctx().remaining().toSeconds() - 1)))
        .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    Http.Response r = Http.send(call.clients().google(), b, call.ctx(), what);
    JsonNode tree;
    try {
      tree = JSON.readTree(r.body().isBlank() ? "{}" : r.body());
    } catch (JacksonException e) {
      throw ConnectorException.resourceError(what + " answered " + r.status() + " with something that isn't JSON: "
          + Http.excerpt(r.body()), null);
    }
    if (!r.ok()) {
      if (r.status() == 401) {
        call.cloud().azure().invalidate(call.cfg().id(), Audience.LOG_ANALYTICS);
      }
      throw error(r.status(), tree, what);
    }
    return tree;
  }

  /** The call's token for Log Analytics, of the pinned tenant when {@code pinned}, tainted. */
  private AzureCredentials.AccessToken token(boolean pinned) throws ConnectorException {
    AzureCredentials.AccessToken t = call.cloud().azure().token(owner(), s.credentials(), Audience.LOG_ANALYTICS,
        call.secrets(), call.ctx().remaining().minus(Http.MARGIN));
    call.ctx().taint(t.value());
    if (pinned) {
      String pin = s.pinnedTenant();
      if (pin == null) {
        throw ConnectorException.denied("Test the connection once: GateRook pins the Azure tenant its sign-in "
            + "belongs to before agents read through it.");
      }
      if (t.tenantId() != null && !pin.equals(t.tenantId())) {
        call.cloud().azure().invalidate(call.cfg().id(), Audience.LOG_ANALYTICS);
        throw ConnectorException.denied("The Azure sign-in now belongs to tenant " + t.tenantId() + ", not " + pin
            + ". Sign in to the connection's tenant, or test the connection again.");
      }
    }
    return t;
  }

  private ConnectorException error(int status, JsonNode tree, String what) {
    JsonNode error = tree.path("error");
    String code = error.path("code").asString("");
    StringBuilder message = new StringBuilder(error.path("message").asString(""));
    JsonNode inner = error.path("innererror");
    for (int depth = 0; depth < 4 && inner.isObject(); depth++) {
      String m = inner.path("message").asString("");
      if (!m.isBlank()) {
        message.append(' ').append(m);
      }
      inner = inner.path("innererror");
    }
    for (JsonNode d : error.path("details")) {
      String m = d.path("message").asString("");
      if (!m.isBlank()) {
        message.append(' ').append(m);
      }
    }
    String text = Http.excerpt(message.toString());
    return switch (status) {
      case 400 -> ConnectorException.badInput(what + " refused the query (" + code + "): " + text);
      case 401 -> ConnectorException.unavailable(what + " refused the token: " + text + ". Sign in again, then test "
          + "again.", null);
      case 403 -> ConnectorException.resourceError(what + " denied " + s.credentials().identity() + ": " + text
          + " Assign the Log Analytics Reader role on the workspace (or Reader on the component).", null);
      case 404 -> ConnectorException.resourceError(what + ": not found (" + code + "). " + text + " Check the "
          + "workspace ID or the component's resource ID.", null);
      case 429 -> ConnectorException.resourceError(what + " throttled the query: workspaces allow about 200 "
          + "queries per 30 seconds per user, shared with people and other tools. Try again in a minute.", null);
      default -> status >= 500 ? ConnectorException.unavailable(what + " failed (" + status + "): " + text, null)
          : ConnectorException.resourceError(what + " answered " + status + ": " + text, null);
    };
  }

  private String base() {
    URI b = call.cloud().azure().base(s.credentials().cloud(), Audience.LOG_ANALYTICS);
    String t = b.toString();
    return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
  }

  /** A component's resource ID with each segment percent-encoded. */
  static String encodePath(String resourceId) {
    StringBuilder out = new StringBuilder();
    for (String segment : resourceId.substring(1).split("/")) {
      out.append('/').append(java.net.URLEncoder.encode(segment, java.nio.charset.StandardCharsets.UTF_8)
          .replace("+", "%20"));
    }
    return out.toString();
  }

  private CloudCredentials.Owner owner() {
    return new CloudCredentials.Owner(call.cfg().id(), call.cfg().version(), call.cfg().name());
  }

  static long nanos(String timestamp) {
    if (timestamp == null || timestamp.isBlank()) {
      return 0;
    }
    try {
      Instant i = Instant.parse(timestamp);
      return Math.addExact(Math.multiplyExact(i.getEpochSecond(), 1_000_000_000L), i.getNano());
    } catch (DateTimeParseException e) {
      return 0;
    }
  }

  static String instant(long ns) {
    return Instant.ofEpochSecond(Math.floorDiv(ns, 1_000_000_000L), Math.floorMod(ns, 1_000_000_000L)).toString();
  }

  /**
   * A time as Kusto and the query API read it: at most seven fractional digits (100 ns ticks), rounded down for the
   * start of a window and up for its end, so the window never shrinks.
   */
  static String kusto(long ns, boolean ceil) {
    long ticks = ceil ? Math.floorDiv(ns + 99, 100) : Math.floorDiv(ns, 100);
    long rounded = Math.multiplyExact(ticks, 100);
    return KUSTO_TIME.format(Instant.ofEpochSecond(Math.floorDiv(rounded, 1_000_000_000L),
        Math.floorMod(rounded, 1_000_000_000L)));
  }

  private static final java.time.format.DateTimeFormatter KUSTO_TIME = java.time.format.DateTimeFormatter
      .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSS'Z'").withZone(java.time.ZoneOffset.UTC);

  // ── Test, health, describe ──────────────────────────────────────────────────────────────────────────────────

  @Override
  public TestResult test() {
    Probe probe = new Probe(call.ctx());
    AzureSpec spec = s.credentials();
    List<TestResult.Hint> hints = new ArrayList<>();
    long dataMs = 0;
    AzureCredentials.AccessToken token;
    try {
      TestResult.Confirmation decision = probe.step(spec.source() == AzureSpec.Source.CLI ? "Azure CLI"
          : "sign-in", () -> AzureTrust.decision(call.cloud().azure(), spec, call.cfg().name(),
              call.ctx().remaining()), d -> d == null ? "ready" : "waiting for you");
      if (decision != null) {
        return TestResult.confirm(decision, probe.checks());
      }
      token = probe.step("token", () -> token(false), t -> t.account() + (t.tenantId() == null ? ""
          : " in tenant " + t.tenantId()));
      if (token.tenantId() == null || (spec.tenantIsGuid() && !spec.tenantId().equals(token.tenantId()))) {
        String text = token.tenantId() == null ? "The token doesn't say its tenant, so GateRook can't pin it."
            : "The sign-in belongs to tenant " + token.tenantId() + ", not " + spec.tenantId() + ". Sign in to the "
                + "connection's tenant or fix the tenant ID.";
        return TestResult.failure("logs.azure-tenant", text, null).withChecks(probe.checks());
      }
      for (String source : s.sources()) {
        long started = System.nanoTime();
        boolean component = Settings.AzureMonitor.component(source);
        URI uri = URI.create(base() + (component ? "/v1" + encodePath(source) : "/v1/workspaces/" + source)
            + "/query");
        probe.step(component ? "component " + source.substring(source.lastIndexOf('/') + 1) : "workspace " + source,
            () -> {
              if (!call.ctx().acquire(s.quotaKey(), PER_MINUTE)) {
                throw ConnectorException.resourceError("GateRook's share of Log Analytics queries is used up. Test "
                    + "again in a minute.", null);
              }
              ObjectNode body = JsonNodeFactory.instance.objectNode().put("query", "print gaterook = 1")
                  .put("timespan", "PT5M");
              return postUnpinned(uri, body);
            }, r -> "query allowed");
        if (dataMs == 0) {
          dataMs = Math.max(1, (System.nanoTime() - started) / 1_000_000);
        }
      }
    } catch (ConnectorException e) {
      return probe.failure(e);
    }
    if (spec.source() == AzureSpec.Source.CLIENT_SECRET && call.cfg().env() == Env.PROD) {
      hints.add(new TestResult.Hint(TestResult.Severity.WARNING, "logs.azure-client-secret", "/credentials/source",
          "Use a certificate on prod: a client secret is a password that lasts up to two years."));
    }
    if (spec.source() == AzureSpec.Source.DEVICE_CODE) {
      hints.add(new TestResult.Hint(TestResult.Severity.INFO, "logs.azure-device-sign-in", "/credentials/source",
          "Device sign-ins last until GateRook restarts; then test the connection again to sign in."));
    }
    TestResult result = TestResult.success(dataMs, "Log Analytics query API · " + spec.cloud().id(), token.account(),
        new TestResult.Permissions(TestResult.Level.READ, "Queries " + String.join(", ", s.sources()), "None: "
            + "the query API only reads", List.of(), null))
        .withChecks(probe.checks())
        .withMetadata(JsonNodeFactory.instance.objectNode().put("serverVersion", "Azure Monitor Logs · "
            + spec.cloud().id()).put("tenant", token.tenantId()));
    for (TestResult.Hint h : hints) {
      result = result.withHint(h);
    }
    return result;
  }

  /** Test connection's probe: the token without the pin (the test records the tenant it found). */
  private JsonNode postUnpinned(URI uri, ObjectNode body) throws ConnectorException {
    AzureCredentials.AccessToken token = token(false);
    if (!call.cloud().azure().sendsTokensTo(s.credentials().cloud(), Audience.LOG_ANALYTICS, uri)) {
      throw new IllegalArgumentException("Not a Log Analytics URL");
    }
    HttpRequest.Builder b = HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
        .header("Accept", "application/json").header("Authorization", "Bearer " + token.value())
        .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    Http.Response r = Http.send(call.clients().google(), b, call.ctx(), "Log Analytics");
    JsonNode tree;
    try {
      tree = JSON.readTree(r.body().isBlank() ? "{}" : r.body());
    } catch (JacksonException e) {
      tree = JsonNodeFactory.instance.objectNode();
    }
    if (!r.ok()) {
      throw error(r.status(), tree, "Log Analytics");
    }
    return tree;
  }

  @Override
  public HealthResult health() {
    // A token is enough: a query would spend the workspace's shared query limits every few minutes.
    long started = System.nanoTime();
    try {
      token(true);
      return HealthResult.healthy((System.nanoTime() - started) / 1_000_000);
    } catch (ConnectorException e) {
      return HealthResult.unreachable(e.getMessage());
    }
  }

  @Override
  public ObjectNode describe(String object) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    ArrayNode workspaces = out.putArray("workspaces");
    s.workspaces().forEach(workspaces::add);
    ArrayNode components = out.putArray("applicationInsights");
    s.applicationInsights().forEach(components::add);
    out.put("timeColumn", "TimeGenerated in workspaces; timestamp in Application Insights components");
    ArrayNode examples = out.putArray("examples");
    examples.add("AppExceptions | where SeverityLevel >= 3 | project TimeGenerated, ProblemId, OuterMessage");
    examples.add("ContainerLogV2 | where LogLevel == 'error' | project TimeGenerated, PodName, LogMessage");
    examples.add("AzureDiagnostics | where Category == 'FunctionAppLogs'");
    examples.add("requests | where success == false (in an Application Insights component)");
    examples.add("AppRequests | summarize failures = countif(Success == false) by bin(TimeGenerated, 5m)");
    out.put("refused", "workspace(), app(), resource(), adx(), arg(), cluster(), database(), externaldata, plugins "
        + "that call other services, and control commands");
    return out;
  }
}
