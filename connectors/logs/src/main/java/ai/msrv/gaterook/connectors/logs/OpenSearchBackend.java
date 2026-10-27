package ai.msrv.gaterook.connectors.logs;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connectors.cloudauth.AwsSessionPolicy;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * OpenSearch over {@code _search} (report 07 §3.4; checked against OpenSearch 3.9.0): the index patterns of the
 * connection go into the URL path and nowhere else, the agent's query is a Lucene {@code query_string} with leading
 * wildcards off, the connection's scope query sits in {@code bool.filter}. JSON Query DSL is refused by
 * {@link LogsRequest}: a {@code terms} lookup inside it reads documents of any index. Amazon OpenSearch Service is
 * signed with SigV4 ({@code es}, {@code aoss}).
 */
final class OpenSearchBackend implements Backend {

  /** What an assumed role may do with a domain in this session: read over HTTP. */
  static final String SESSION_POLICY = AwsSessionPolicy.builder()
      .allow(List.of("es:ESHttpGet", "es:ESHttpHead", "es:ESHttpPost", "aoss:APIAccessAll"), List.of())
      .toJson();
  /** {@code index.max_result_window}'s default. */
  static final int MAX_SIZE = 10_000;
  private static final String FILTER_PATH = "hits.hits._index,hits.hits._id,hits.hits._source,hits.hits.sort,"
      + "timed_out,_shards.failed,took";
  /** Headers {@code java.net.http} sets itself; the signer covers {@code host} with the same value. */
  private static final Set<String> RESTRICTED = Set.of("host", "content-length", "connection", "expect", "upgrade");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Settings.OpenSearch s;
  private final Call call;

  OpenSearchBackend(Settings.OpenSearch s, Call call) {
    this.s = s;
    this.call = call;
  }

  @Override
  public Page newest(String query, String source, long fromNs, long toNs, int want, String continuation)
      throws ConnectorException {
    return search(query, source, fromNs, toNs, want, "desc");
  }

  @Override
  public Page oldest(String query, String source, long fromNs, long toNs, int want) throws ConnectorException {
    return search(query, source, fromNs, toNs, want, "asc");
  }

  @Override
  public Duration pollEvery() {
    return Duration.ofSeconds(1);
  }

  @Override
  public Duration delay() {
    // Documents become searchable after the index refresh (1 s by default).
    return Duration.ofSeconds(2);
  }

  /** The {@code _search} body: range and scope as filters, the agent's Lucene query as the only scored clause. */
  ObjectNode body(String query, long fromNs, long toNs, int size, String order) {
    ObjectNode body = JsonNodeFactory.instance.objectNode();
    body.put("size", size);
    body.put("track_total_hits", false);
    long seconds = Math.max(1, Math.min(10, call.ctx().remaining().toSeconds() - 1));
    body.put("timeout", seconds + "s");
    ObjectNode sort = body.putArray("sort").addObject().putObject(s.timestampField());
    sort.put("order", order);
    sort.put("numeric_type", "date_nanos");
    ObjectNode bool = body.putObject("query").putObject("bool");
    ArrayNode filter = bool.putArray("filter");
    ObjectNode range = filter.addObject().putObject("range").putObject(s.timestampField());
    range.put("gte", GcpLoggingBackend.instant(fromNs));
    range.put("lte", GcpLoggingBackend.instant(toNs));
    range.put("format", "strict_date_optional_time_nanos");
    if (s.scopeQuery() != null) {
      filter.addObject().set("query_string", queryString(s.scopeQuery()));
    }
    if (!query.isBlank()) {
      bool.putArray("must").addObject().set("query_string", queryString(query));
    }
    return body;
  }

  private ObjectNode queryString(String q) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("query", q);
    n.put("default_field", s.messageField());
    n.put("allow_leading_wildcard", false);
    n.put("analyze_wildcard", false);
    n.put("lenient", true);
    return n;
  }

  private Page search(String query, String source, long fromNs, long toNs, int want, String order)
      throws ConnectorException {
    List<String> indices = source != null ? List.of(source) : s.indices();
    int size = Math.min(want, MAX_SIZE);
    String path = "/" + String.join(",", indices) + "/_search?ignore_unavailable=true&allow_no_indices=true"
        + "&filter_path=" + FILTER_PATH;
    JsonNode r = json(send("POST", path, body(query, fromNs, toNs, size, order).toString(), "the search"),
        "the search");
    List<LogEntry> entries = new ArrayList<>();
    for (JsonNode hit : r.at("/hits/hits")) {
      entries.add(entry(hit));
    }
    entries.removeIf(e -> e.nanos() < fromNs || e.nanos() > toNs);
    entries.sort(order.equals("desc") ? LokiBackend.NEWEST_FIRST : LokiBackend.OLDEST_FIRST);
    Page p = Page.of(entries, r.at("/hits/hits").size() >= size, null, Page.Stats.requests(1));
    boolean partial = r.path("timed_out").asBoolean(false) || r.at("/_shards/failed").asInt(0) > 0;
    return partial ? p.withPartial("partial") : p;
  }

  private LogEntry entry(JsonNode hit) {
    JsonNode src = hit.path("_source");
    long ns;
    JsonNode sortValue = hit.path("sort").path(0);
    if (sortValue.isIntegralNumber()) {
      ns = sortValue.asLong();
    } else {
      String t = text(lookup(src, s.timestampField()));
      ns = t == null ? 0 : GcpLoggingBackend.nanos(t, null);
    }
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put("index", hit.path("_index").asString(""));
    for (String field : s.labelFields()) {
      String v = text(lookup(src, field));
      if (v != null) {
        labels.put(field, v);
      }
    }
    String message = text(lookup(src, s.messageField()));
    if (message == null) {
      message = src.toString();
    }
    String level = text(lookup(src, s.severityField()));
    Severity severity = level == null ? Severity.ofLine(message) : Severity.of(level);
    String id = hit.path("_index").asString("") + "/" + hit.path("_id").asString("");
    return LogEntry.of(ns, severity, message, labels, src.isObject() ? (ObjectNode) src : null, id,
        call.maxChars());
  }

  /** A field of {@code _source}: a flat key ({@code "log.level"}) or the nested path ({@code log → level}). */
  static JsonNode lookup(JsonNode source, String field) {
    JsonNode flat = source.get(field);
    if (flat != null) {
      return flat;
    }
    JsonNode n = source;
    String rest = field;
    while (n != null && n.isObject()) {
      int dot = rest.indexOf('.');
      if (dot < 0) {
        return n.get(rest);
      }
      // A dotted key may also sit at any level: {"log": {"level.name": …}}.
      JsonNode whole = n.get(rest);
      if (whole != null) {
        return whole;
      }
      n = n.get(rest.substring(0, dot));
      rest = rest.substring(dot + 1);
    }
    return null;
  }

  private static String text(JsonNode n) {
    if (n == null || n.isNull() || n.isMissingNode()) {
      return null;
    }
    if (n.isArray()) {
      return n.isEmpty() ? null : text(n.get(0));
    }
    return n.isValueNode() ? n.asString() : n.toString();
  }

  // ── Test, health, describe ──────────────────────────────────────────────────────────────────────────────────

  @Override
  public TestResult test() {
    Probe probe = new Probe(call.ctx());
    List<TestResult.Hint> hints = new ArrayList<>();
    String version;
    long dataMs;
    TestResult.Level level = TestResult.Level.UNKNOWN;
    String identity = s.auth().username() != null ? s.auth().username() : s.auth().mode().name().toLowerCase(
        Locale.ROOT);
    try {
      JsonNode root = probe.step("version", () -> json(send("GET", "/", null, "the cluster info"), "the cluster"),
          r -> r.at("/version/distribution").asString("elasticsearch") + " " + r.at("/version/number").asString("?"));
      if (!"opensearch".equals(root.at("/version/distribution").asString(""))) {
        hints.add(new TestResult.Hint(TestResult.Severity.WARNING, "logs.not-opensearch", "/url",
            "This looks like Elasticsearch, which MCP Gateway doesn't support. Searches may still work."));
      }
      version = root.at("/version/number").asString("unknown");
      probe.step("indices", () -> {
        JsonNode resolved = json(send("GET", "/_resolve/index/" + String.join(",", s.indices())
            + "?expand_wildcards=open", null, "resolving the indices"), "the indices");
        int n = resolved.path("indices").size() + resolved.path("aliases").size()
            + resolved.path("data_streams").size();
        if (n == 0) {
          throw ConnectorException.resourceError("No index matches " + String.join(", ", s.indices()) + ".", null);
        }
        return n;
      }, n -> n + (n == 1 ? " index" : " indices, aliases or data streams"));
      long now = call.ctx().clock().instant().toEpochMilli() * 1_000_000L;
      long started = System.nanoTime();
      probe.step("read", () -> search("", null, 0, now, 1, "desc"),
          p -> p.entries().isEmpty() ? "no documents" : "newest document " + p.entries().get(0).timestamp());
      dataMs = (System.nanoTime() - started) / 1_000_000;
      JsonNode account = probe.optional("permissions", () -> {
        Http.Response r = send("GET", "/_plugins/_security/api/account", null, "the security plugin");
        if (!r.ok()) {
          throw ConnectorException.resourceError(r.status() == 404 ? "the security plugin isn't installed"
              : "the security plugin answered " + r.status(), null);
        }
        return json(r, "the security plugin");
      }, a -> "roles " + a.path("roles"));
      if (account != null) {
        level = TestResult.Level.READ;
        String roles = account.path("roles").toString() + account.path("backend_roles").toString();
        if (roles.contains("all_access") || roles.contains("admin") || roles.contains("write")) {
          level = TestResult.Level.WRITE;
          hints.add(new TestResult.Hint(call.cfg().env() == Env.PROD ? TestResult.Severity.WARNING
              : TestResult.Severity.INFO, "logs.can-write", "/auth/username",
              "This user can write. Use a read-only role for prod."));
        }
        if (account.has("user_name")) {
          identity = account.path("user_name").asString(identity);
        }
      } else if (s.auth().mode() == Settings.AuthMode.API_KEY) {
        hints.add(new TestResult.Hint(TestResult.Severity.INFO, "logs.permissions-unknown", null,
            "Permissions unknown: an API key can't ask the security plugin about itself."));
      }
    } catch (ConnectorException e) {
      return probe.failure(e);
    }
    hints.addAll(Probe.transport(s.base(), s.auth(), call.cfg().env()));
    TestResult result = TestResult.success(dataMs, "OpenSearch " + version, identity,
        new TestResult.Permissions(level, "Reads " + String.join(", ", s.indices()),
            level == TestResult.Level.WRITE ? "Can write" : level == TestResult.Level.READ ? "None" : "Unknown",
            List.of(), null))
        .withChecks(probe.checks())
        .withMetadata(JsonNodeFactory.instance.objectNode().put("serverVersion", "OpenSearch " + version));
    for (TestResult.Hint h : hints) {
      result = result.withHint(h);
    }
    return result;
  }

  @Override
  public HealthResult health() {
    long started = System.nanoTime();
    try {
      Http.Response r = send("GET", "/", null, "the cluster info");
      if (!r.ok()) {
        return HealthResult.unreachable("OpenSearch answered " + r.status());
      }
      return HealthResult.healthy((System.nanoTime() - started) / 1_000_000);
    } catch (ConnectorException e) {
      return HealthResult.unreachable(e.getMessage());
    }
  }

  @Override
  public ObjectNode describe(String object) throws ConnectorException {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    ArrayNode indices = out.putArray("indices");
    s.indices().forEach(indices::add);
    out.put("timestampField", s.timestampField());
    out.put("messageField", s.messageField());
    out.put("severityField", s.severityField());
    ArrayNode labels = out.putArray("labelFields");
    s.labelFields().forEach(labels::add);
    ArrayNode examples = out.putArray("examples");
    examples.add(s.severityField() + ":error AND " + s.messageField() + ":timeout");
    examples.add(s.messageField() + ":\"connection refused\"");
    if (object != null && !object.isBlank()) {
      if (!s.indices().contains(object)) {
        throw ConnectorException.badInput(object + " isn't one of this connection's index patterns.");
      }
      JsonNode resolved = json(send("GET", "/_resolve/index/" + object + "?expand_wildcards=open", null,
          "resolving the indices"), "the indices");
      ArrayNode names = out.putArray("matching");
      for (JsonNode i : resolved.path("indices")) {
        if (names.size() < 100) {
          names.add(i.path("name").asString());
        }
      }
      for (JsonNode a : resolved.path("data_streams")) {
        if (names.size() < 100) {
          names.add(a.path("name").asString());
        }
      }
    }
    return out;
  }

  // ── HTTP ────────────────────────────────────────────────────────────────────────────────────────────────────

  private Http.Response send(String method, String pathAndQuery, String body, String what)
      throws ConnectorException {
    URI uri = URI.create(s.base() + pathAndQuery);
    HttpRequest.Builder b = HttpRequest.newBuilder(uri).header("Accept", "application/json");
    if (body != null) {
      b.header("Content-Type", "application/json");
    }
    b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    if (s.auth().mode() == Settings.AuthMode.AWS_SIGV4) {
      sign(b, method, uri, body);
    } else {
      String auth = Http.authorization(s.auth(), call.secrets());
      if (auth != null) {
        b.header("Authorization", auth);
      }
    }
    return Http.send(client(), b, call.ctx(), "OpenSearch " + what);
  }

  /** SigV4 for Amazon OpenSearch Service: the signer's headers onto the request {@code java.net.http} sends. */
  private void sign(HttpRequest.Builder b, String method, URI uri, String body) throws ConnectorException {
    AwsCredentials credentials;
    try {
      credentials = CloudCredentials.tainting(call.cloud().aws(new CloudCredentials.Owner(call.cfg().id(),
          call.cfg().version(), call.cfg().name()), s.awsCredentials(), SESSION_POLICY, call.secrets()),
          call.ctx()::taint).resolveCredentials();
    } catch (SdkException e) {
      throw ConnectorException.unavailable("AWS credentials for the domain couldn't be loaded: " + e.getMessage(), e);
    }
    SdkHttpRequest.Builder request = SdkHttpRequest.builder().method(SdkHttpMethod.fromValue(method)).uri(uri)
        .putHeader("Accept", "application/json");
    if (body != null) {
      request.putHeader("Content-Type", "application/json");
    }
    SdkHttpRequest unsigned = request.build();
    SignedRequest signed = AwsV4HttpSigner.create().sign(r -> r.identity(credentials).request(unsigned)
        .payload(body == null ? null : ContentStreamProvider.fromUtf8String(body))
        .putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, s.awsService())
        .putProperty(AwsV4HttpSigner.REGION_NAME, s.awsRegion()));
    for (Map.Entry<String, List<String>> h : signed.request().headers().entrySet()) {
      if (RESTRICTED.contains(h.getKey().toLowerCase(Locale.ROOT)) || h.getKey().equalsIgnoreCase("Accept")
          || h.getKey().equalsIgnoreCase("Content-Type")) {
        continue;
      }
      for (String v : h.getValue()) {
        b.header(h.getKey(), v);
      }
    }
  }

  private HttpClient client() throws ConnectorException {
    return call.clients().http(call.cfg(), s.caCertificate());
  }

  private JsonNode json(Http.Response r, String what) throws ConnectorException {
    JsonNode tree;
    try {
      tree = JSON.readTree(r.body().isBlank() ? "{}" : r.body());
    } catch (JacksonException e) {
      throw ConnectorException.resourceError("OpenSearch answered " + r.status() + " to " + what + " with something "
          + "that isn't JSON: " + Http.excerpt(r.body()), null);
    }
    if (!r.ok()) {
      String reason = tree.at("/error/root_cause/0/reason").asString(tree.at("/error/reason").asString(
          Http.excerpt(r.body())));
      throw switch (r.status()) {
        case 400 -> ConnectorException.badInput("OpenSearch refused the query: " + Http.excerpt(reason));
        case 401 -> ConnectorException.resourceError("OpenSearch refused the credentials: " + Http.excerpt(reason)
            + ". Check them under Credentials.", null);
        case 403 -> ConnectorException.resourceError("OpenSearch denied " + what + ": " + Http.excerpt(reason)
            + ". Give the user the read role on " + String.join(", ", s.indices()) + ".", null);
        case 429 -> ConnectorException.resourceError("OpenSearch is overloaded (429): " + Http.excerpt(reason)
            + ". Try again in a few seconds.", null);
        default -> r.status() >= 500
            ? ConnectorException.unavailable("OpenSearch failed (" + r.status() + "): " + Http.excerpt(reason), null)
            : ConnectorException.resourceError("OpenSearch answered " + r.status() + ": " + Http.excerpt(reason),
                null);
      };
    }
    return tree;
  }
}
