package ai.msrv.gaterook.connectors.prometheus;

import ai.msrv.gaterook.connectors.support.TimeArg;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One tool call as the request it becomes: a fixed read path of {@code /api/v1}, its parameters in order, and the
 * call's arguments with every time made absolute. {@link #of} runs without I/O, so {@code classify()} refuses what the
 * request couldn't be, the approval shows the exact request and the execution sends that request: an approved call
 * carries the absolute times it was approved with.
 */
final class Plan {

  /** The only paths the client builds: reads of {@code /api/v1}, relative to the server's URL. */
  enum Endpoint {
    QUERY("api/v1/query"),
    QUERY_RANGE("api/v1/query_range"),
    SERIES("api/v1/series"),
    LABELS("api/v1/labels"),
    LABEL_VALUES("api/v1/label/"),
    METADATA("api/v1/metadata"),
    TARGETS("api/v1/targets"),
    RULES("api/v1/rules"),
    ALERTS("api/v1/alerts"),
    BUILD_INFO("api/v1/status/buildinfo");

    final String path;

    Endpoint(String path) {
      this.path = path;
    }
  }

  /** A call that can't become a request; {@code rule} names the denial ({@code <env>.prometheus.<rule>}). */
  static final class Refusal extends Exception {
    private static final long serialVersionUID = 1L;
    final String rule;
    final String hint;

    Refusal(String rule, String message, String hint) {
      super(message, null, false, false);
      this.rule = rule;
      this.hint = hint;
    }
  }

  static final int MAX_QUERY = 8_192;
  static final int MAX_SELECTOR = 2_048;
  static final int MAX_SELECTORS = 20;
  static final int MAX_NAME = 256;
  static final int MAX_NAMES = 50;
  /** Most items of a list answer (label names and values, metrics, targets, rules, alerts) when none is asked. */
  static final int DEFAULT_LIST = 1_000;
  static final int MAX_LIST = 10_000;
  static final Duration DEFAULT_WINDOW = Duration.ofHours(1);
  private static final Pattern LEGACY_LABEL = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  final String tool;
  final Endpoint endpoint;
  /** The label of {@link Endpoint#LABEL_VALUES} as asked, else {@code null}. */
  final String label;
  /** The parameters in order, without {@code timeout}: that one is the call's remaining time when it is sent. */
  final List<Map.Entry<String, String>> params;
  /** The most items of the answer before the endpoint's row limit: the server is asked for one more. */
  final int rows;
  /** Whether the request carries {@code timeout} (PromQL evaluation). */
  final boolean evaluates;
  /** The arguments with absolute times: what an approval stores and what runs after it. */
  final ObjectNode arguments;
  final String query;
  final Instant time;
  final Instant start;
  final Instant end;
  final Duration step;
  /** The filter of targets or alerts: {@code active}, {@code dropped}, {@code firing}, … or {@code any}. */
  final String state;

  private Plan(Builder b) {
    this.tool = b.tool;
    this.endpoint = b.endpoint;
    this.label = b.label;
    this.params = List.copyOf(b.params);
    this.rows = b.rows;
    this.evaluates = b.endpoint == Endpoint.QUERY || b.endpoint == Endpoint.QUERY_RANGE;
    this.arguments = b.arguments;
    this.query = b.query;
    this.time = b.time;
    this.start = b.start;
    this.end = b.end;
    this.step = b.step;
    this.state = b.state;
  }

  /** The path under the server's URL: {@code api/v1/query}, {@code api/v1/label/job/values}. */
  String path() {
    return endpoint == Endpoint.LABEL_VALUES
        ? endpoint.path + URLEncoder.encode(labelSegment(label), StandardCharsets.UTF_8) + "/values"
        : endpoint.path;
  }

  /** {@code ?query=…&time=…}, each name and value encoded; {@code timeout} is added when the request is sent. */
  String queryString(String timeout) {
    StringBuilder out = new StringBuilder();
    for (Map.Entry<String, String> p : params) {
      append(out, p.getKey(), p.getValue());
    }
    if (timeout != null) {
      append(out, "timeout", timeout);
    }
    return out.toString();
  }

  private static void append(StringBuilder out, String name, String value) {
    out.append(out.isEmpty() ? '?' : '&').append(URLEncoder.encode(name, StandardCharsets.UTF_8)).append('=')
        .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
  }

  /** A plan of the server's build information, for Test connection and health. */
  static Plan buildInfo() {
    Builder b = new Builder("buildinfo", Endpoint.BUILD_INFO, F.objectNode());
    return new Plan(b);
  }

  /** A plan of one instant query that isn't a tool call (Test connection's checks). */
  static Plan probe(String query, Instant now) {
    Builder b = new Builder("probe", Endpoint.QUERY, F.objectNode());
    b.query = query;
    b.time = now;
    b.rows = 10;
    b.param("query", query).param("time", iso(now)).param("limit", "11");
    return new Plan(b);
  }

  /**
   * The request of {@code tool} with {@code args}.
   *
   * @param now the clock's instant: relative times ({@code -1h}, {@code now}) become absolute against it
   * @throws Refusal what the call can't ask, and what to do instead
   */
  static Plan of(String tool, JsonNode args, PrometheusSettings s, Instant now) throws Refusal {
    // Whole seconds: relative times read as people write them, and a server's query cache sees the same times again.
    Instant at = now.truncatedTo(ChronoUnit.SECONDS);
    return switch (tool) {
      case PrometheusTools.QUERY -> query(args, s, at);
      case PrometheusTools.QUERY_RANGE -> queryRange(args, s, at);
      case PrometheusTools.SERIES -> series(args, s, at);
      case PrometheusTools.LABELS -> labels(args, at);
      case PrometheusTools.LABEL_VALUES -> labelValues(args, at);
      case PrometheusTools.METADATA -> metadata(args);
      case PrometheusTools.TARGETS -> targets(args);
      case PrometheusTools.RULES -> rules(args);
      case PrometheusTools.ALERTS -> alerts(args);
      default -> throw new Refusal("unknown-tool", "Prometheus has no tool " + tool + ".", null);
    };
  }

  private static Plan query(JsonNode args, PrometheusSettings s, Instant now) throws Refusal {
    Builder b = new Builder(PrometheusTools.QUERY, Endpoint.QUERY, args);
    b.query = promql(args);
    b.time = time(args, "time", now, now);
    b.rows = rows(args, s.maxSeries(), s.maxSeries());
    b.param("query", b.query).param("time", iso(b.time)).param("limit", Integer.toString(b.rows + 1));
    b.arguments.put("time", iso(b.time));
    return new Plan(b);
  }

  private static Plan queryRange(JsonNode args, PrometheusSettings s, Instant now) throws Refusal {
    Builder b = new Builder(PrometheusTools.QUERY_RANGE, Endpoint.QUERY_RANGE, args);
    b.query = promql(args);
    window(b, args, now);
    Duration range = Duration.between(b.start, b.end);
    String asked = text(args, "step");
    if (asked == null) {
      b.step = Durations.defaultStep(range, s.maxPoints());
    } else {
      b.step = Durations.parse(asked);
      if (b.step == null) {
        throw new Refusal("bad-argument", "Can't read the step \"" + abbreviate(asked) + "\".", "Give a duration "
            + "such as 30s, 5m or 1h, or a number of seconds, or leave step out.");
      }
    }
    long points = Durations.points(range, b.step);
    if (points > s.maxPoints()) {
      throw new Refusal("too-many-points", "A step of " + Durations.human(b.step) + " over "
          + Durations.human(range) + " asks for " + points + " points per series; this connection allows at most "
          + s.maxPoints() + ".", "Use a step of at least " + Durations.human(Durations.smallestStep(range,
          s.maxPoints())) + ", or shorten the range.");
    }
    b.rows = rows(args, s.maxSeries(), s.maxSeries());
    b.param("query", b.query).param("start", iso(b.start)).param("end", iso(b.end))
        .param("step", Durations.seconds(b.step)).param("limit", Integer.toString(b.rows + 1));
    b.arguments.put("start", iso(b.start)).put("end", iso(b.end)).put("step", Durations.seconds(b.step));
    return new Plan(b);
  }

  private static Plan series(JsonNode args, PrometheusSettings s, Instant now) throws Refusal {
    Builder b = new Builder(PrometheusTools.SERIES, Endpoint.SERIES, args);
    List<String> match = selectors(args, true);
    window(b, args, now);
    b.rows = rows(args, s.maxSeries(), s.maxSeries());
    for (String m : match) {
      b.param("match[]", m);
    }
    b.param("start", iso(b.start)).param("end", iso(b.end)).param("limit", Integer.toString(b.rows + 1));
    b.arguments.put("start", iso(b.start)).put("end", iso(b.end));
    return new Plan(b);
  }

  private static Plan labels(JsonNode args, Instant now) throws Refusal {
    Builder b = new Builder(PrometheusTools.LABELS, Endpoint.LABELS, args);
    List<String> match = selectors(args, false);
    window(b, args, now);
    b.rows = rows(args, DEFAULT_LIST, MAX_LIST);
    for (String m : match) {
      b.param("match[]", m);
    }
    b.param("start", iso(b.start)).param("end", iso(b.end)).param("limit", Integer.toString(b.rows + 1));
    b.arguments.put("start", iso(b.start)).put("end", iso(b.end));
    return new Plan(b);
  }

  private static Plan labelValues(JsonNode args, Instant now) throws Refusal {
    Builder b = new Builder(PrometheusTools.LABEL_VALUES, Endpoint.LABEL_VALUES, args);
    b.label = name(args, "label", true);
    List<String> match = selectors(args, false);
    window(b, args, now);
    b.rows = rows(args, DEFAULT_LIST, MAX_LIST);
    for (String m : match) {
      b.param("match[]", m);
    }
    b.param("start", iso(b.start)).param("end", iso(b.end)).param("limit", Integer.toString(b.rows + 1));
    b.arguments.put("start", iso(b.start)).put("end", iso(b.end));
    return new Plan(b);
  }

  private static Plan metadata(JsonNode args) throws Refusal {
    Builder b = new Builder(PrometheusTools.METADATA, Endpoint.METADATA, args);
    String metric = name(args, "metric", false);
    b.rows = rows(args, DEFAULT_LIST, MAX_LIST);
    if (metric != null) {
      b.param("metric", metric);
    }
    b.param("limit", Integer.toString(b.rows + 1)).param("limit_per_metric", "5");
    return new Plan(b);
  }

  private static Plan targets(JsonNode args) throws Refusal {
    Builder b = new Builder(PrometheusTools.TARGETS, Endpoint.TARGETS, args);
    b.state = choice(args, "state", "active", List.of("active", "dropped", "any"));
    String pool = name(args, "scrapePool", false);
    b.rows = rows(args, DEFAULT_LIST, MAX_LIST);
    b.param("state", b.state);
    if (pool != null) {
      b.param("scrapePool", pool);
    }
    return new Plan(b);
  }

  private static Plan rules(JsonNode args) throws Refusal {
    Builder b = new Builder(PrometheusTools.RULES, Endpoint.RULES, args);
    String type = choice(args, "type", null, List.of("alert", "record"));
    b.rows = rows(args, DEFAULT_LIST, MAX_LIST);
    if (type != null) {
      b.param("type", type);
    }
    for (String n : names(args, "names")) {
      b.param("rule_name[]", n);
    }
    for (String g : names(args, "groups")) {
      b.param("rule_group[]", g);
    }
    // Active alerts are prometheus_alerts' answer; servers that don't know the parameter send them and they're dropped.
    b.param("exclude_alerts", "true");
    return new Plan(b);
  }

  private static Plan alerts(JsonNode args) throws Refusal {
    Builder b = new Builder(PrometheusTools.ALERTS, Endpoint.ALERTS, args);
    b.state = choice(args, "state", "any", List.of("firing", "pending", "any"));
    b.rows = rows(args, DEFAULT_LIST, MAX_LIST);
    return new Plan(b);
  }

  // ── Arguments ─────────────────────────────────────────────────────────────────────────────────────────────

  /** The PromQL text: present, within its length, without control characters other than tabs and line breaks. */
  private static String promql(JsonNode args) throws Refusal {
    JsonNode q = args.path("query");
    if (!q.isString() || q.asString().isBlank()) {
      throw new Refusal("bad-argument", "Give a PromQL expression in query, such as up.", null);
    }
    String text = q.asString();
    if (text.length() > MAX_QUERY) {
      throw new Refusal("bad-argument", "The query has " + text.length() + " characters; at most " + MAX_QUERY
          + " are sent.", "Shorten the expression.");
    }
    if (hasControl(text, true)) {
      throw new Refusal("bad-argument", "The query contains control characters.", "Send plain PromQL text.");
    }
    return text;
  }

  /** {@code match[]}: series selectors, at least one when {@code required}. */
  private static List<String> selectors(JsonNode args, boolean required) throws Refusal {
    JsonNode m = args.path("match");
    List<String> out = new ArrayList<>();
    if (m.isMissingNode() || m.isNull()) {
      if (required) {
        throw new Refusal("bad-argument", "Give at least one series selector in match, such as up or "
            + "{job=\"api\"}.", null);
      }
      return out;
    }
    if (!m.isArray() || (required && m.isEmpty()) || m.size() > MAX_SELECTORS) {
      throw new Refusal("bad-argument", "match is a list of 1 to " + MAX_SELECTORS + " series selectors.", null);
    }
    for (JsonNode selector : m) {
      if (!selector.isString() || selector.asString().isBlank() || selector.asString().length() > MAX_SELECTOR
          || hasControl(selector.asString(), true)) {
        throw new Refusal("bad-argument", "Each selector in match is a series selector of at most " + MAX_SELECTOR
            + " characters, such as http_requests_total{job=\"api\"}.", null);
      }
      out.add(selector.asString());
    }
    return out;
  }

  /** A label, metric, scrape pool or rule name: present when {@code required}, short, without control characters. */
  private static String name(JsonNode args, String field, boolean required) throws Refusal {
    JsonNode n = args.path(field);
    if (n.isMissingNode() || n.isNull()) {
      if (required) {
        throw new Refusal("bad-argument", "Give " + field + ".", null);
      }
      return null;
    }
    if (!n.isString() || n.asString().isEmpty() || n.asString().length() > MAX_NAME || hasControl(n.asString(),
        false)) {
      throw new Refusal("bad-argument", field + " is a name of 1 to " + MAX_NAME + " characters without control "
          + "characters.", null);
    }
    return n.asString();
  }

  private static List<String> names(JsonNode args, String field) throws Refusal {
    JsonNode n = args.path(field);
    List<String> out = new ArrayList<>();
    if (n.isMissingNode() || n.isNull()) {
      return out;
    }
    if (!n.isArray() || n.size() > MAX_NAMES) {
      throw new Refusal("bad-argument", field + " is a list of at most " + MAX_NAMES + " names.", null);
    }
    for (JsonNode v : n) {
      if (!v.isString() || v.asString().isEmpty() || v.asString().length() > MAX_NAME || hasControl(v.asString(),
          false)) {
        throw new Refusal("bad-argument", "Each of " + field + " is a name of 1 to " + MAX_NAME + " characters.",
            null);
      }
      out.add(v.asString());
    }
    return out;
  }

  private static String choice(JsonNode args, String field, String dflt, List<String> allowed) throws Refusal {
    String v = text(args, field);
    if (v == null) {
      return dflt;
    }
    if (!allowed.contains(v)) {
      throw new Refusal("bad-argument", field + " is one of " + String.join(", ", allowed) + ".", null);
    }
    return v;
  }

  /** The most items asked: {@code limit}, else {@code dflt}, never more than {@code cap}. */
  private static int rows(JsonNode args, int dflt, int cap) throws Refusal {
    JsonNode l = args.path("limit");
    if (l.isMissingNode() || l.isNull()) {
      return Math.min(dflt, cap);
    }
    if (!l.isIntegralNumber() || l.asLong() < 1) {
      throw new Refusal("bad-argument", "limit is a whole number of at least 1.", null);
    }
    return (int) Math.min(l.asLong(), cap);
  }

  /** {@code start} and {@code end}: end defaults to now, start to an hour before end; start must be before end. */
  private static void window(Builder b, JsonNode args, Instant now) throws Refusal {
    b.end = time(args, "end", now, now);
    b.start = time(args, "start", b.end.minus(DEFAULT_WINDOW), now);
    if (!b.start.isBefore(b.end)) {
      throw new Refusal("range", "start must be before end (start " + iso(b.start) + ", end " + iso(b.end) + ").",
          "Give a start before the end, such as start -1h and end now.");
    }
  }

  private static Instant time(JsonNode args, String field, Instant dflt, Instant now) throws Refusal {
    String v = text(args, field);
    if (v == null) {
      return dflt;
    }
    try {
      return TimeArg.parse(v, now).truncatedTo(ChronoUnit.MILLIS);
    } catch (TimeArg.InvalidTimeException e) {
      throw new Refusal("time", field + ": " + e.getMessage() + ".", null);
    }
  }

  private static String text(JsonNode args, String field) {
    JsonNode n = args.path(field);
    return n.isString() && !n.asString().isBlank() ? n.asString().strip() : null;
  }

  static String iso(Instant t) {
    return t.toString();
  }

  /** Control characters other than tabs and line breaks ({@code lines}) or any at all. */
  private static boolean hasControl(String s, boolean lines) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if ((c < 0x20 && !(lines && (c == '\t' || c == '\n' || c == '\r'))) || c == 0x7F || (c >= 0x80 && c <= 0x9F)) {
        return true;
      }
    }
    return false;
  }

  private static String abbreviate(String s) {
    return s.length() <= 40 ? s : s.substring(0, 40) + "…";
  }

  /**
   * A label name as the path of {@code /api/v1/label/<name>/values} carries it: as written when it is a classic name
   * ({@code [a-zA-Z_][a-zA-Z0-9_]*}), else in Prometheus 3's value encoding ({@code U__}, letters, digits and colons
   * as they are, {@code _} doubled, every other character its code point in hex between underscores). A classic name
   * that starts with {@code U__} is encoded too: the server would decode it.
   */
  static String labelSegment(String name) {
    if (LEGACY_LABEL.matcher(name).matches() && !name.startsWith("U__")) {
      return name;
    }
    StringBuilder out = new StringBuilder("U__");
    int i = 0;
    while (i < name.length()) {
      int cp = name.codePointAt(i);
      if (cp == '_') {
        out.append("__");
      } else if ((cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z') || cp == ':' || (cp >= '0' && cp <= '9'
          && i > 0)) {
        out.appendCodePoint(cp);
      } else if (cp >= 0xD800 && cp <= 0xDFFF) {
        // A lone surrogate is no character: the server's encoder writes U+FFFD for it.
        out.append("_fffd_");
      } else {
        out.append('_').append(Integer.toHexString(cp)).append('_');
      }
      i += Character.charCount(cp);
    }
    return out.toString();
  }

  /** What a plan is made of, before it is fixed. */
  private static final class Builder {
    final String tool;
    final Endpoint endpoint;
    final ObjectNode arguments;
    final List<Map.Entry<String, String>> params = new ArrayList<>();
    String label;
    int rows;
    String query;
    Instant time;
    Instant start;
    Instant end;
    Duration step;
    String state;

    Builder(String tool, Endpoint endpoint, JsonNode args) {
      this.tool = tool;
      this.endpoint = endpoint;
      this.arguments = args != null && args.isObject() ? ((ObjectNode) args).deepCopy() : F.objectNode();
    }

    Builder param(String name, String value) {
      params.add(Map.entry(name, value));
      return this;
    }
  }
}
