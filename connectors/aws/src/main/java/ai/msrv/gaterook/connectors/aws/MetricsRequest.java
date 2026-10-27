package ai.msrv.gaterook.connectors.aws;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connectors.support.TimeArg;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One {@code aws_metrics} call, checked without I/O (report 09 §3.2, §3.5): {@code query} → {@code GetMetricData},
 * {@code list_metrics} → {@code ListMetrics}, {@code alarms} → {@code DescribeAlarms}. The same checks run in
 * {@code classify}, {@code describeAction} and {@code execute}; the payload of an approval carries absolute times and
 * the resolved period, so an approved call reads what the person saw.
 *
 * <p>Neither {@code AccountId} nor Metrics Insights is in the input: the first reads another account, the second any
 * metric of this one. Each metric's namespace must belong to an allowed service; expressions pass the
 * {@link ExpressionGuard}.
 */
record MetricsRequest(String region, Action action, List<Query> metrics, List<Expression> expressions, Instant from,
    Instant to, int period, String namespace, String metricName, Map<String, String> dimensions, String state,
    String alarmNamePrefix, int limit, int maxPoints, String cursor, List<String> notes) {

  enum Action {
    QUERY("query"), LIST_METRICS("list_metrics"), ALARMS("alarms");

    final String id;

    Action(String id) {
      this.id = id;
    }

    static Action of(String id) {
      for (Action a : values()) {
        if (a.id.equals(id)) {
          return a;
        }
      }
      return null;
    }
  }

  /** One {@code MetricStat} query. */
  record Query(String id, String namespace, String metric, Map<String, String> dimensions, String stat,
      String label) {}

  /** One metric math query. */
  record Expression(String id, String expression, String label) {}

  static final List<Integer> PERIODS = List.of(60, 300, 900, 3_600, 21_600, 86_400);
  /** Points one series should have when the period is left to the gateway. */
  static final int TARGET_POINTS = 360;
  /** CloudWatch keeps 1-minute data 15 days and 5-minute data 63 days (report 09 §9 "retention of 15/63/455 days"). */
  static final Duration ONE_MINUTE_KEPT = Duration.ofDays(15);
  static final Duration FIVE_MINUTES_KEPT = Duration.ofDays(63);

  private static final Pattern ID = Pattern.compile("^[a-z][a-zA-Z0-9_]{0,31}$");
  private static final Pattern NAMESPACE = Pattern.compile("^[A-Za-z0-9.\\-_/#: ]{1,255}$");
  private static final Pattern METRIC = Pattern.compile("^[^\\p{Cntrl}]{1,255}$");
  private static final Pattern STAT = Pattern.compile("^[A-Za-z][A-Za-z0-9.:%()]{0,31}$");
  private static final Pattern DIMENSION = Pattern.compile("^[^\\p{Cntrl}:]{1,255}$");

  MetricsRequest {
    metrics = List.copyOf(metrics);
    expressions = List.copyOf(expressions);
    dimensions = dimensions == null ? Map.of() : Map.copyOf(dimensions);
    notes = List.copyOf(notes);
  }

  static MetricsRequest of(JsonNode args, String connection, Settings s, AwsPolicy p, Instant now) throws Refusal {
    String region = Requests.region(args, connection, s, p);
    Action action = Action.of(args.path("action").asString("query"));
    if (action == null) {
      throw new Refusal("bad_input", "action is query, list_metrics or alarms.", null);
    }
    List<String> notes = new ArrayList<>();
    String cursor = null;
    if (args.hasNonNull("cursor")) {
      cursor = args.get("cursor").asString("");
      if (!cursor.startsWith(Cursors.PREFIX) || cursor.length() > 4096) {
        throw new Refusal("bad_input", "This cursor doesn't come from aws_metrics. Pass nextCursor of the previous "
            + "answer, or leave cursor out.", null);
      }
    }
    int dflt = p.env() == Env.PROD ? 10 : 50;
    int asked = args.path("limit").isNumber() ? args.path("limit").asInt(dflt) : dflt;
    if (asked < 1 || asked > 100) {
      throw new Refusal("bad_input", "limit is 1 to 100.", null);
    }
    int limit = Math.min(asked, p.maxItems());
    if (limit < asked && action != Action.QUERY) {
      notes.add("limit lowered to " + limit + " by the " + p.env().id() + " policy.");
    }
    return switch (action) {
      case QUERY -> query(args, region, p, now, limit, cursor, notes);
      case LIST_METRICS -> listMetrics(args, region, p, limit, cursor, notes);
      case ALARMS -> alarms(args, region, p, limit, cursor, notes);
    };
  }

  private static MetricsRequest query(JsonNode args, String region, AwsPolicy p, Instant now, int limit,
      String cursor, List<String> notes) throws Refusal {
    JsonNode list = args.path("metrics");
    if (!list.isArray() || list.isEmpty()) {
      throw new Refusal("bad_input", "query needs metrics: [{namespace, metric, dimensions, stat}], e.g. "
          + "{\"namespace\":\"AWS/RDS\",\"metric\":\"CPUUtilization\",\"dimensions\":{\"DBInstanceIdentifier\":"
          + "\"stage-orders\"}}.", null);
    }
    if (list.size() > 10) {
      throw new Refusal("bad_input", "At most 10 metrics per call.", null);
    }
    Set<String> ids = new LinkedHashSet<>();
    for (JsonNode m : list) {
      if (m.hasNonNull("id")) {
        ids.add(id(m.get("id").asString(""), ids));
      }
    }
    for (JsonNode e : args.path("expressions")) {
      ids.add(id(e.path("id").asString(""), ids));
    }
    List<Query> metrics = new ArrayList<>();
    int n = 0;
    for (JsonNode m : list) {
      String id = m.hasNonNull("id") ? m.get("id").asString() : null;
      if (id == null) {
        do {
          id = "m" + (++n);
        } while (ids.contains(id));
        ids.add(id);
      }
      String ns = m.path("namespace").asString("");
      String metric = m.path("metric").asString("");
      if (!NAMESPACE.matcher(ns).matches() || !METRIC.matcher(metric).matches()) {
        throw new Refusal("bad_input", "Each metric names its namespace (AWS/RDS) and metric (CPUUtilization).", null);
      }
      Requests.printable(metric, "metric");
      Requests.service(Services.ofNamespace(ns), ns, p);
      String stat = m.path("stat").asString("Average");
      if (!STAT.matcher(stat).matches()) {
        throw new Refusal("bad_input", "stat is a CloudWatch statistic: Average, Sum, Minimum, Maximum, SampleCount, "
            + "p99 …", null);
      }
      String label = text(m, "label");
      if (label != null) {
        Requests.printable(label, "label");
      }
      metrics.add(new Query(id, ns, metric, dimensions(m.path("dimensions")), stat, label));
    }
    List<Expression> expressions = new ArrayList<>();
    JsonNode exprs = args.path("expressions");
    if (exprs.size() > 5) {
      throw new Refusal("bad_input", "At most 5 expressions per call.", null);
    }
    for (JsonNode e : exprs) {
      String expression = e.path("expression").asString("");
      if (expression.length() > 512) {
        throw new Refusal("metric_expression", "An expression has at most 512 characters.", null);
      }
      try {
        ExpressionGuard.check(expression, ids);
      } catch (ExpressionGuard.Rejected r) {
        throw new Refusal("metric_expression", "The expression \"" + Requests.abbreviate(expression) + "\" can't be "
            + "sent: " + r.getMessage(), "Expressions may combine this call's metrics with arithmetic and the math "
            + "functions (SUM, AVG, RATE, FILL, …); LAMBDA, SEARCH and SELECT aren't sent.");
      }
      String label = text(e, "label");
      if (label != null) {
        Requests.printable(label, "label");
      }
      expressions.add(new Expression(e.path("id").asString(), expression, label));
    }
    TimeArg.Range range;
    try {
      range = TimeArg.range(args.path("from").asString("-1h"), args.path("to").asString("now"), now,
          Duration.ofDays(p.maxRangeDays()));
    } catch (TimeArg.InvalidTimeException e) {
      throw new Refusal("range", e.getMessage(), null);
    }
    Duration age = Duration.between(range.from(), now);
    int floor = age.compareTo(FIVE_MINUTES_KEPT) > 0 ? 3_600 : age.compareTo(ONE_MINUTE_KEPT) > 0 ? 300 : 60;
    int period;
    if (args.path("period").isNumber()) {
      period = args.path("period").asInt();
      if (!PERIODS.contains(period)) {
        throw new Refusal("bad_input", "period is one of 60, 300, 900, 3600, 21600, 86400 seconds.", null);
      }
      if (period < floor) {
        notes.add("period raised to " + floor + " s by retention: CloudWatch keeps " + (floor == 300
            ? "1-minute data for 15 days" : "5-minute data for 63 days") + ".");
        period = floor;
      }
    } else {
      period = Math.max(autoPeriod(range.length()), floor);
    }
    // CloudWatch aligns to the period anyway; aligned times make the answer and the approval say the same thing.
    Instant from = Instant.ofEpochSecond(Math.floorDiv(range.from().getEpochSecond(), period) * period);
    Instant to = Instant.ofEpochSecond(-Math.floorDiv(-range.to().getEpochSecond(), period) * period);
    long perSeries = Math.max(1, Duration.between(from, to).toSeconds() / period);
    long total = perSeries * (metrics.size() + expressions.size());
    if (total > p.maxPoints()) {
      notes.add("The " + p.env().id() + " policy allows " + p.maxPoints() + " data points per call and this query "
          + "has up to " + total + ": the answer stops there with a cursor. Raise period or narrow from/to.");
    }
    return new MetricsRequest(region, Action.QUERY, metrics, expressions, from, to, period, null, null, null, null,
        null, limit, p.maxPoints(), cursor, notes);
  }

  private static MetricsRequest listMetrics(JsonNode args, String region, AwsPolicy p, int limit, String cursor,
      List<String> notes) throws Refusal {
    String ns = text(args, "namespace");
    if (ns != null) {
      if (!NAMESPACE.matcher(ns).matches()) {
        throw new Refusal("bad_input", "namespace is a CloudWatch namespace such as AWS/RDS.", null);
      }
      Requests.service(Services.ofNamespace(ns), ns, p);
    }
    String metric = text(args, "metric");
    if (metric != null && !METRIC.matcher(metric).matches()) {
      throw new Refusal("bad_input", "metric is a metric name such as CPUUtilization.", null);
    }
    Map<String, String> dims = dimensions(args.path("dimensions"));
    return new MetricsRequest(region, Action.LIST_METRICS, List.of(), List.of(), null, null, 0, ns, metric, dims,
        null, null, limit, p.maxPoints(), cursor, notes);
  }

  private static MetricsRequest alarms(JsonNode args, String region, AwsPolicy p, int limit, String cursor,
      List<String> notes) throws Refusal {
    Requests.service("cloudwatch", "Alarms", p);
    String state = args.path("state").asString("ALARM");
    if (!List.of("ALARM", "OK", "INSUFFICIENT_DATA").contains(state)) {
      throw new Refusal("bad_input", "state is ALARM, OK or INSUFFICIENT_DATA.", null);
    }
    String prefix = text(args, "alarmNamePrefix");
    if (prefix != null) {
      if (prefix.length() > 255) {
        throw new Refusal("bad_input", "alarmNamePrefix has at most 255 characters.", null);
      }
      Requests.printable(prefix, "alarmNamePrefix");
    }
    return new MetricsRequest(region, Action.ALARMS, List.of(), List.of(), null, null, 0, null, null, null, state,
        prefix, limit, p.maxPoints(), cursor, notes);
  }

  private static String id(String id, Set<String> seen) throws Refusal {
    if (!ID.matcher(id).matches()) {
      throw new Refusal("bad_input", "An id starts with a lower-case letter and has letters, digits and _ (m1, "
          + "errors).", null);
    }
    if (seen.contains(id)) {
      throw new Refusal("bad_input", "The id " + id + " is used twice.", null);
    }
    return id;
  }

  private static Map<String, String> dimensions(JsonNode node) throws Refusal {
    Map<String, String> out = new TreeMap<>();
    if (node.isMissingNode() || node.isNull()) {
      return out;
    }
    if (!node.isObject() || node.size() > 30) {
      throw new Refusal("bad_input", "dimensions maps at most 30 names to values, e.g. {\"DBInstanceIdentifier\":"
          + "\"stage-orders\"}.", null);
    }
    for (Map.Entry<String, JsonNode> d : node.properties()) {
      String v = d.getValue().asString("");
      if (!DIMENSION.matcher(d.getKey()).matches() || !d.getValue().isString() || v.isEmpty() || v.length() > 1024) {
        throw new Refusal("bad_input", "Each dimension is a name and a text value.", null);
      }
      Requests.printable(v, "dimensions");
      out.put(d.getKey(), v);
    }
    return out;
  }

  /** The smallest period that gives one series at most {@value #TARGET_POINTS} points. */
  static int autoPeriod(Duration range) {
    for (int p : PERIODS) {
      if (range.toSeconds() / p <= TARGET_POINTS) {
        return p;
      }
    }
    return PERIODS.getLast();
  }

  private static String text(JsonNode node, String field) {
    JsonNode n = node.get(field);
    return n == null || n.isNull() || n.asString("").isBlank() ? null : n.asString();
  }

  /** The canonical request of an approval: absolute times and the resolved period. */
  ObjectNode payload() {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("tool", "aws_metrics");
    n.put("region", region);
    n.put("action", action.id);
    switch (action) {
      case QUERY -> {
        ArrayNode ms = n.putArray("metrics");
        for (Query q : metrics) {
          ObjectNode m = ms.addObject();
          m.put("id", q.id());
          m.put("namespace", q.namespace());
          m.put("metric", q.metric());
          if (!q.dimensions().isEmpty()) {
            ObjectNode d = m.putObject("dimensions");
            q.dimensions().forEach(d::put);
          }
          m.put("stat", q.stat());
          if (q.label() != null) {
            m.put("label", q.label());
          }
        }
        if (!expressions.isEmpty()) {
          ArrayNode es = n.putArray("expressions");
          for (Expression e : expressions) {
            ObjectNode o = es.addObject();
            o.put("id", e.id());
            o.put("expression", e.expression());
            if (e.label() != null) {
              o.put("label", e.label());
            }
          }
        }
        n.put("from", from.toString());
        n.put("to", to.toString());
        n.put("period", period);
      }
      case LIST_METRICS -> {
        if (namespace != null) {
          n.put("namespace", namespace);
        }
        if (metricName != null) {
          n.put("metric", metricName);
        }
        if (!dimensions.isEmpty()) {
          ObjectNode d = n.putObject("dimensions");
          new TreeMap<>(dimensions).forEach(d::put);
        }
      }
      case ALARMS -> {
        n.put("state", state);
        if (alarmNamePrefix != null) {
          n.put("alarmNamePrefix", alarmNamePrefix);
        }
      }
    }
    n.put("limit", limit);
    if (cursor != null) {
      n.put("cursor", cursor);
    }
    return n;
  }

  /** The query without its page and size; for {@code query} also without the interval (the cursor keeps it). */
  String hash() {
    ObjectNode n = payload();
    n.remove("cursor");
    n.remove("limit");
    n.remove("from");
    n.remove("to");
    n.remove("period");
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(n.toString().getBytes(StandardCharsets.UTF_8))).substring(0, 32);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The same query over another interval: a cursor's page reads the interval of its first page. */
  MetricsRequest over(Instant from, Instant to, int period) {
    return new MetricsRequest(region, action, metrics, expressions, from, to, period, namespace, metricName,
        dimensions, state, alarmNamePrefix, limit, maxPoints, cursor, notes);
  }

  /** The ids of every query of the call, in order. */
  List<String> ids() {
    List<String> out = new ArrayList<>();
    metrics.forEach(m -> out.add(m.id()));
    expressions.forEach(e -> out.add(e.id()));
    return out;
  }

  /** {@code AWS/RDS CPUUtilization {DBInstanceIdentifier=stage-orders} Average}. */
  static String describe(Query q) {
    StringBuilder b = new StringBuilder(q.namespace()).append(' ').append(q.metric());
    if (!q.dimensions().isEmpty()) {
      Map<String, String> d = new LinkedHashMap<>(new TreeMap<>(q.dimensions()));
      b.append(" {");
      int i = 0;
      for (Map.Entry<String, String> e : d.entrySet()) {
        b.append(i++ == 0 ? "" : ", ").append(e.getKey()).append('=').append(e.getValue());
      }
      b.append('}');
    }
    return b.append(' ').append(q.stat()).toString();
  }
}
