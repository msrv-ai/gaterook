package ai.msrv.gaterook.connectors.logs;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.TestResult;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Loki over its HTTP API (report 07 §1, §3.4; checked against Loki 3.7.9): {@code query_range} newest first for a
 * search and oldest first for a tail, the connection's tenant in {@code X-Scope-OrgID} (agents can't name another),
 * structured metadata as the third element of an entry ({@code categorize-labels}). Loki answers stream by stream;
 * the streams are merged into one timeline here. Errors are plain text, and the agent gets Loki's own words.
 */
final class LokiBackend implements Backend {

  /** Loki's {@code max_entries_limit_per_query} default. */
  static final int MAX_ENTRIES = 5_000;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  /** Newest first, ties by id: the order every page of a search keeps. */
  static final Comparator<LogEntry> NEWEST_FIRST = Comparator.comparingLong(LogEntry::nanos).reversed()
      .thenComparing(LogEntry::id);
  static final Comparator<LogEntry> OLDEST_FIRST = Comparator.comparingLong(LogEntry::nanos)
      .thenComparing(LogEntry::id);

  private final Settings.Loki s;
  private final Call call;

  LokiBackend(Settings.Loki s, Call call) {
    this.s = s;
    this.call = call;
  }

  @Override
  public Page newest(String query, String source, long fromNs, long toNs, int want, String continuation)
      throws ConnectorException {
    return range(query, fromNs, toNs, want, "backward");
  }

  @Override
  public Page oldest(String query, String source, long fromNs, long toNs, int want) throws ConnectorException {
    Page p = range(query, fromNs, toNs, want, "forward");
    if (p.aggregate() != null) {
      throw ConnectorException.badInput("logs_tail needs a log query (a stream selector with filters), not a metric "
          + "query. Use logs_search for metrics.");
    }
    return p;
  }

  @Override
  public Duration pollEvery() {
    return Duration.ofSeconds(1);
  }

  @Override
  public Duration delay() {
    return Duration.ofSeconds(2);
  }

  private Page range(String query, long fromNs, long toNs, int want, String direction) throws ConnectorException {
    if (query.isBlank()) {
      throw ConnectorException.badInput("Loki needs a stream selector, like {app=\"api\"} or {service_name=~\".+\"}.");
    }
    int limit = Math.min(want, MAX_ENTRIES);
    // Loki's start is inclusive and its end exclusive; the window's end is inclusive.
    String url = s.base() + "/loki/api/v1/query_range?query=" + enc(query) + "&start=" + fromNs + "&end="
        + Math.addExact(toNs, 1) + "&limit=" + limit + "&direction=" + direction;
    Http.Response r = get(url);
    if (!r.ok()) {
      throw error(r, "the query");
    }
    JsonNode data = parse(r).path("data");
    Page.Stats stats = new Page.Stats(1, data.at("/stats/summary/totalBytesProcessed").asLong(-1),
        data.at("/stats/summary/totalLinesProcessed").asLong(-1));
    String type = data.path("resultType").asString("");
    if (type.equals("matrix") || type.equals("vector")) {
      return new Page(List.of(), false, null, null, aggregate(data.path("result"), type), stats);
    }
    if (!type.equals("streams")) {
      throw ConnectorException.resourceError("Loki answered with an unknown result type \"" + type + "\".", null);
    }
    List<LogEntry> merged = merge(data.path("result"), direction.equals("backward") ? NEWEST_FIRST : OLDEST_FIRST);
    return Page.of(merged, merged.size() >= limit, null, stats);
  }

  /**
   * The k-way merge of Loki's streams into one timeline (each stream is already in the asked direction; it is sorted
   * again in case a proxy reordered it).
   */
  List<LogEntry> merge(JsonNode result, Comparator<LogEntry> order) {
    List<List<LogEntry>> streams = new ArrayList<>();
    for (JsonNode stream : result) {
      TreeMap<String, String> labels = new TreeMap<>();
      stream.path("stream").properties().forEach(e -> labels.put(e.getKey(), e.getValue().asString()));
      String streamKey = labels.toString();
      List<LogEntry> entries = new ArrayList<>();
      for (JsonNode v : stream.path("values")) {
        entries.add(entry(v, labels, streamKey));
      }
      entries.sort(order);
      streams.add(entries);
    }
    record Head(List<LogEntry> stream, int at) {
      LogEntry entry() {
        return stream.get(at);
      }
    }
    PriorityQueue<Head> heads = new PriorityQueue<>((a, b) -> order.compare(a.entry(), b.entry()));
    for (List<LogEntry> st : streams) {
      if (!st.isEmpty()) {
        heads.add(new Head(st, 0));
      }
    }
    List<LogEntry> out = new ArrayList<>();
    while (!heads.isEmpty()) {
      Head h = heads.poll();
      out.add(h.entry());
      if (h.at() + 1 < h.stream().size()) {
        heads.add(new Head(h.stream(), h.at() + 1));
      }
    }
    return out;
  }

  private LogEntry entry(JsonNode v, TreeMap<String, String> streamLabels, String streamKey) {
    long ns = Long.parseLong(v.path(0).asString("0"));
    String line = v.path(1).asString("");
    JsonNode extra = v.path(2);
    TreeMap<String, String> labels = new TreeMap<>(streamLabels);
    JsonNode metadata = extra.path("structuredMetadata");
    metadata.properties().forEach(e -> labels.put(e.getKey(), e.getValue().asString()));
    extra.path("parsed").properties().forEach(e -> labels.putIfAbsent(e.getKey(), e.getValue().asString()));
    String level = first(metadata.path("detected_level"), streamLabels.get("detected_level"),
        streamLabels.get("level"), extra.path("parsed").path("level"));
    Severity severity = level == null || level.equals("unknown") ? Severity.ofLine(line) : Severity.of(level);
    return LogEntry.of(ns, severity, line, labels, null, LogEntry.hashId(Long.toString(ns), streamKey, line),
        call.maxChars());
  }

  private static String first(Object... candidates) {
    for (Object c : candidates) {
      if (c instanceof JsonNode n && n.isString() && !n.asString().isBlank()) {
        return n.asString();
      }
      if (c instanceof String t && !t.isBlank()) {
        return t;
      }
    }
    return null;
  }

  private static Page.Aggregate aggregate(JsonNode result, String type) {
    TreeSet<String> names = new TreeSet<>();
    for (JsonNode series : result) {
      series.path("metric").properties().forEach(e -> names.add(e.getKey()));
    }
    List<String> columns = new ArrayList<>();
    columns.add("time");
    columns.addAll(names);
    columns.add("value");
    ArrayNode rows = JsonNodeFactory.instance.arrayNode();
    for (JsonNode series : result) {
      List<JsonNode> points = new ArrayList<>();
      if (type.equals("vector")) {
        points.add(series.path("value"));
      } else {
        series.path("values").forEach(points::add);
      }
      for (JsonNode p : points) {
        ArrayNode row = rows.addArray();
        double seconds = p.path(0).asDouble();
        row.add(java.time.Instant.ofEpochMilli(Math.round(seconds * 1000)).toString());
        for (String n : names) {
          JsonNode l = series.path("metric").get(n);
          if (l == null) {
            row.addNull();
          } else {
            row.add(l.asString());
          }
        }
        row.add(p.path(1).asString());
      }
    }
    return new Page.Aggregate(List.copyOf(columns), rows);
  }

  // ── Test, health, describe ──────────────────────────────────────────────────────────────────────────────────

  @Override
  public TestResult test() {
    Probe probe = new Probe(call.ctx());
    String version = "unknown";
    TestResult.Level level = TestResult.Level.UNKNOWN;
    List<TestResult.Hint> hints = new ArrayList<>();
    long dataMs;
    try {
      probe.step("ready", () -> {
        Http.Response r = get(s.base() + "/ready");
        if (!r.ok()) {
          throw error(r, "/ready");
        }
        return "ready";
      });
      Http.Response info = probe.step("version", () -> get(s.base() + "/loki/api/v1/status/buildinfo"),
          r -> r.ok() ? "Loki " + parse(r).path("version").asString("?") : "not reported (" + r.status() + ")");
      if (info.ok()) {
        version = parse(info).path("version").asString("unknown");
      }
      long started = System.nanoTime();
      Http.Response labels = probe.step("read", () -> {
        Http.Response r = get(s.base() + "/loki/api/v1/labels?since=1h");
        if (!r.ok()) {
          throw error(r, "reading labels");
        }
        return r;
      }, r -> parse(r).path("data").size() + " labels in the last hour");
      dataMs = (System.nanoTime() - started) / 1_000_000;
      if (parse(labels).path("data").isEmpty()) {
        hints.add(new TestResult.Hint(TestResult.Severity.INFO, "logs.empty", null,
            "No logs in the last hour. Check the tenant and that something ships logs to this Loki."));
      }
      // An empty push writes nothing; Loki only says whether this token could push.
      Http.Response push = probe.step("write probe", () -> post(s.base() + "/loki/api/v1/push",
          "{\"streams\":[]}"), r -> switch (r.status()) {
            case 401, 403 -> "refused: read only";
            case 204, 400, 422 -> "this token can push logs";
            default -> "unknown (" + r.status() + ")";
          });
      if (push.status() == 401 || push.status() == 403) {
        level = TestResult.Level.READ;
      } else if (push.status() == 204 || push.status() == 400 || push.status() == 422) {
        level = TestResult.Level.WRITE;
        hints.add(new TestResult.Hint(call.cfg().env() == ai.msrv.gaterook.connector.api.Env.PROD
            ? TestResult.Severity.WARNING : TestResult.Severity.INFO, "logs.can-write", "/auth/secret",
            "This token can push logs. Use a read-only token (Grafana Cloud scope logs:read) for prod."));
      }
    } catch (ConnectorException e) {
      return probe.failure(e);
    }
    hints.addAll(Probe.transport(s.base(), s.auth(), call.cfg().env()));
    TestResult result = TestResult.success(dataMs, "Loki " + version, s.tenant() == null ? "no tenant"
        : "tenant " + s.tenant(), new TestResult.Permissions(level, "Reads its tenant's logs",
        level == TestResult.Level.WRITE ? "Can push logs" : level == TestResult.Level.READ ? "None" : "Unknown",
        List.of(), null)).withChecks(probe.checks());
    for (TestResult.Hint h : hints) {
      result = result.withHint(h);
    }
    return result.withMetadata(JsonNodeFactory.instance.objectNode().put("serverVersion", "Loki " + version));
  }

  @Override
  public HealthResult health() {
    long started = System.nanoTime();
    try {
      Http.Response r = get(s.base() + "/ready");
      if (!r.ok()) {
        return HealthResult.unreachable("Loki isn't ready (" + r.status() + ")");
      }
      return HealthResult.healthy((System.nanoTime() - started) / 1_000_000);
    } catch (ConnectorException e) {
      return HealthResult.unreachable(e.getMessage());
    }
  }

  @Override
  public ObjectNode describe(String object) throws ConnectorException {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    if (object == null || object.isBlank()) {
      Http.Response r = get(s.base() + "/loki/api/v1/labels?since=1h");
      if (!r.ok()) {
        throw error(r, "reading labels");
      }
      out.set("labels", parse(r).path("data"));
      out.put("labelsSince", "1h");
    } else {
      if (!object.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
        throw ConnectorException.badInput(object + " isn't a Loki label name.");
      }
      Http.Response r = get(s.base() + "/loki/api/v1/label/" + object + "/values?since=1h");
      if (!r.ok()) {
        throw error(r, "reading label values");
      }
      ArrayNode values = JsonNodeFactory.instance.arrayNode();
      JsonNode data = parse(r).path("data");
      for (int i = 0; i < Math.min(100, data.size()); i++) {
        values.add(data.get(i));
      }
      out.put("label", object);
      out.set("values", values);
      out.put("valuesTruncated", data.size() > 100);
    }
    return out;
  }

  // ── HTTP ────────────────────────────────────────────────────────────────────────────────────────────────────

  private Http.Response get(String url) throws ConnectorException {
    return Http.send(client(), request(url).GET(), call.ctx(), "Loki");
  }

  private Http.Response post(String url, String json) throws ConnectorException {
    return Http.send(client(), request(url).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json)), call.ctx(), "Loki");
  }

  private HttpRequest.Builder request(String url) throws ConnectorException {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json")
        .header("X-Loki-Response-Encoding-Flags", "categorize-labels");
    if (s.tenant() != null) {
      b.header("X-Scope-OrgID", s.tenant());
    }
    String auth = Http.authorization(s.auth(), call.secrets());
    if (auth != null) {
      b.header("Authorization", auth);
    }
    return b;
  }

  private HttpClient client() throws ConnectorException {
    return call.clients().http(call.cfg(), s.caCertificate());
  }

  private static JsonNode parse(Http.Response r) throws ConnectorException {
    try {
      return JSON.readTree(r.body());
    } catch (JacksonException e) {
      throw ConnectorException.resourceError("Loki answered with something that isn't JSON: "
          + Http.excerpt(r.body()), null);
    }
  }

  /** Loki's own words: its errors are plain text ({@code parse error at line 1, col 11: …}). */
  private ConnectorException error(Http.Response r, String what) {
    String text = Http.excerpt(r.body());
    return switch (r.status()) {
      case 400 -> ConnectorException.badInput("Loki refused " + what + ": " + text);
      case 401, 403 -> text.contains("no org id")
          ? ConnectorException.resourceError("Loki needs a tenant: set Tenant (X-Scope-OrgID) in the connection.",
          null)
          : ConnectorException.resourceError("Loki refused the credentials (" + r.status() + "): " + text
              + ". Check the token under Credentials.", null);
      case 404 -> ConnectorException.resourceError("Loki has no " + what + " at this URL (404). Check the URL: "
          + "Grafana Cloud's ends in .grafana.net.", null);
      case 429 -> ConnectorException.resourceError("Loki is limiting queries (429): " + text
          + ". Try again in a few seconds or narrow the range.", null);
      default -> r.status() >= 500
          ? ConnectorException.unavailable("Loki failed (" + r.status() + "): " + text, null)
          : ConnectorException.resourceError("Loki answered " + r.status() + ": " + text, null);
    };
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
