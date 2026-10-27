package ai.msrv.gaterook.connectors.gcp;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connectors.support.TimeArg;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One {@code gcp_metrics} call, checked without I/O (report 09 §3.4, §3.5): a time series query of one metric, the
 * metrics a project has, or its alerts. The same checks run in {@code classify} and again in {@code execute}; the
 * payload of an approval carries absolute times and the resolved period, so an approved call reads what the person
 * saw.
 */
record MetricsRequest(String project, Action action, String metric, String filter, Instant from, Instant to,
    int period, String aligner, String reducer, List<String> groupBy, String prefix, String state, int limit,
    String cursor, List<String> notes) {

  enum Action {
    QUERY("query"), LIST_METRICS("list_metrics"), ALERTS("alerts");

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

  /**
   * A metric type: its service, then path segments that never are {@code .}, {@code ..} or empty — the type goes into
   * the path of {@code metricDescriptors.get}, and a dot segment would walk out of the project (ARCHITECTURE.md §3
   * "GCP": the path is built from checked parts).
   */
  static final Pattern METRIC = Pattern.compile(
      "^[a-z0-9.-]+\\.(googleapis\\.com|io)(/[A-Za-z0-9_-][A-Za-z0-9_.-]*)+$");
  static final Pattern GROUP_BY = Pattern.compile("^(metric|resource)\\.labels\\.[a-z0-9_]+$");
  static final Pattern PREFIX = Pattern.compile("^[A-Za-z0-9._/-]{1,256}$");
  /** A filter may narrow, never choose the metric or the project (report 09 §3.5 {@code gcp.filter_scope}). */
  static final Pattern FILTER_SCOPE = Pattern.compile(
      "(?i)\\b(metric\\.type|project|project_id|project_number)\\s*(=|:|!=|<|>)");
  static final List<Integer> PERIODS = List.of(60, 300, 900, 3_600, 21_600, 86_400);
  static final Set<String> ALIGNERS = Set.of("ALIGN_MEAN", "ALIGN_MAX", "ALIGN_MIN", "ALIGN_SUM", "ALIGN_RATE",
      "ALIGN_DELTA", "ALIGN_COUNT", "ALIGN_PERCENTILE_99", "ALIGN_PERCENTILE_95", "ALIGN_PERCENTILE_50");
  static final Set<String> REDUCERS = Set.of("REDUCE_NONE", "REDUCE_SUM", "REDUCE_MEAN", "REDUCE_MAX", "REDUCE_MIN",
      "REDUCE_COUNT", "REDUCE_PERCENTILE_99", "REDUCE_PERCENTILE_95", "REDUCE_PERCENTILE_50");
  /** Points one series should have when the period is left to the gateway. */
  static final int TARGET_POINTS = 360;
  static final int MAX_FILTER = 2_000;

  MetricsRequest {
    groupBy = List.copyOf(groupBy);
    notes = List.copyOf(notes);
  }

  static MetricsRequest of(JsonNode args, String connection, Settings s, GcpPolicy p, Instant now) throws Refusal {
    List<String> notes = new ArrayList<>();
    String project = DescribeRequest.text(args, "project");
    if (project != null && !s.hasMetricsProject(project)) {
      throw new Refusal("scope_not_allowed", project + " isn't a metrics project of " + connection + ".",
          s.metricsProjects().isEmpty() ? null : "Its projects: " + String.join(", ", s.metricsProjects()) + ".");
    }
    if (project == null) {
      project = s.firstMetricsProject();
    }
    if (project == null) {
      throw new Refusal("scope_not_allowed", connection + " names no project to read metrics from.",
          "Ask the person to add one under Metrics projects.");
    }
    String actionText = DescribeRequest.text(args, "action");
    Action action = actionText == null ? Action.QUERY : Action.of(actionText);
    if (action == null) {
      throw new Refusal("bad_input", "action is query, list_metrics or alerts.", null);
    }
    String metric = DescribeRequest.text(args, "metric");
    String filter = DescribeRequest.text(args, "filter");
    String prefix = DescribeRequest.text(args, "prefix");
    String aligner = DescribeRequest.text(args, "aligner");
    String reducer = DescribeRequest.text(args, "reducer");
    List<String> groupBy = new ArrayList<>();
    for (JsonNode g : args.path("groupBy")) {
      groupBy.add(g.asString(""));
    }
    Instant from = null;
    Instant to = null;
    int period = 0;
    if (action == Action.QUERY) {
      if (metric == null) {
        throw new Refusal("bad_input", "A query needs metric, like run.googleapis.com/request_count.",
            "List the metrics with action list_metrics.");
      }
      if (metric.length() > 512 || !METRIC.matcher(metric).matches()) {
        throw new Refusal("bad_input", "\"" + DescribeRequest.abbreviate(metric) + "\" isn't a metric type like "
            + "run.googleapis.com/request_count.", null);
      }
      DescribeRequest.checkService(Services.ofType(metric), metric, p);
      if (filter != null) {
        checkFilter(filter);
      }
      try {
        TimeArg.Range range = TimeArg.range(DescribeRequest.text(args, "from") == null ? "-1h"
            : DescribeRequest.text(args, "from"), DescribeRequest.text(args, "to"), now,
            Duration.ofDays(p.maxRangeDays()));
        from = range.from();
        to = range.to();
      } catch (TimeArg.InvalidTimeException e) {
        throw new Refusal("range", e.getMessage(), null);
      }
      if (args.path("period").isNumber()) {
        period = args.path("period").asInt();
        if (!PERIODS.contains(period)) {
          throw new Refusal("bad_input", "period is one of 60, 300, 900, 3600, 21600, 86400 seconds.", null);
        }
      } else {
        period = autoPeriod(Duration.between(from, to));
      }
      long perSeries = Math.max(1, Duration.between(from, to).toSeconds() / period);
      if (perSeries > p.maxPoints()) {
        notes.add("One series has up to " + perSeries + " points; the " + p.env().id() + " policy allows "
            + p.maxPoints() + " per call, so raise period or narrow from/to to see them all.");
      }
      if (aligner != null && !ALIGNERS.contains(aligner)) {
        throw new Refusal("bad_input", "Unknown aligner " + DescribeRequest.abbreviate(aligner) + ".", null);
      }
      if (reducer != null && !REDUCERS.contains(reducer)) {
        throw new Refusal("bad_input", "Unknown reducer " + DescribeRequest.abbreviate(reducer) + ".", null);
      }
      if (groupBy.size() > 5) {
        throw new Refusal("bad_input", "At most 5 groupBy labels.", null);
      }
      for (String g : groupBy) {
        if (!GROUP_BY.matcher(g).matches()) {
          throw new Refusal("bad_input", "groupBy takes labels like resource.labels.service_name, not \""
              + DescribeRequest.abbreviate(g) + "\".", null);
        }
      }
      if (!groupBy.isEmpty() && (reducer == null || reducer.equals("REDUCE_NONE"))) {
        throw new Refusal("bad_input", "groupBy needs a reducer, like REDUCE_SUM.", null);
      }
      if (reducer == null) {
        reducer = "REDUCE_NONE";
      }
    } else {
      metric = null;
      filter = null;
      aligner = null;
      reducer = null;
      groupBy = List.of();
    }
    if (action == Action.LIST_METRICS && prefix != null) {
      if (!PREFIX.matcher(prefix).matches()) {
        throw new Refusal("bad_input", "prefix is the start of a metric type, like run.googleapis.com/.", null);
      }
      if (prefix.contains("/") || prefix.contains(".")) {
        DescribeRequest.checkService(Services.ofType(prefix), prefix, p);
      }
    } else if (action != Action.LIST_METRICS) {
      prefix = null;
    }
    String state = null;
    if (action == Action.ALERTS) {
      state = DescribeRequest.text(args, "state") == null ? "OPEN" : DescribeRequest.text(args, "state");
      if (!state.equals("OPEN") && !state.equals("CLOSED")) {
        throw new Refusal("bad_input", "state is OPEN or CLOSED.", null);
      }
    }
    String cursor = DescribeRequest.text(args, "cursor");
    if (cursor != null && cursor.length() > 4096) {
      throw new Refusal("bad_input", "cursor is longer than 4096 characters.", null);
    }
    int dflt = p.env() == Env.PROD ? 10 : 20;
    int asked = args.path("limit").isNumber() ? args.path("limit").asInt(dflt) : dflt;
    if (asked < 1 || asked > 100) {
      throw new Refusal("bad_input", "limit is 1 to 100.", null);
    }
    int limit = Math.min(asked, p.maxItems());
    if (limit < asked) {
      notes.add("limit lowered to " + limit + " by the " + p.env().id() + " policy.");
    }
    return new MetricsRequest(project, action, metric, filter, from, to, period, aligner, reducer, groupBy, prefix,
        state, limit, cursor, notes);
  }

  /**
   * The filter stays inside its parentheses and never names the metric or the project: {@code metric.type="m" AND
   * (<filter>)} is what goes to Monitoring, so an unbalanced {@code )} or quote could turn the AND into an OR over
   * other metrics.
   */
  static void checkFilter(String filter) throws Refusal {
    if (filter.length() > MAX_FILTER) {
      throw new Refusal("bad_input", "filter is longer than " + MAX_FILTER + " characters.", null);
    }
    int depth = 0;
    boolean quoted = false;
    for (int i = 0; i < filter.length(); i++) {
      char c = filter.charAt(i);
      if (quoted) {
        if (c == '\\') {
          i++;
        } else if (c == '"') {
          quoted = false;
        }
        continue;
      }
      if (c == '"') {
        quoted = true;
      } else if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
        if (depth < 0) {
          break;
        }
      } else if (c < 0x20 && c != '\t') {
        depth = -1;
        break;
      }
    }
    if (quoted || depth != 0) {
      throw new Refusal("filter_syntax", "The filter has unbalanced parentheses or quotes.",
          "Write it as one expression, like resource.labels.service_name = \"api\".");
    }
    if (FILTER_SCOPE.matcher(unquoted(filter)).find()) {
      throw new Refusal("filter_scope", "filter can't choose the metric or the project.",
          "Pass metric and project instead; filter narrows by labels, like resource.labels.service_name = \"api\".");
    }
  }

  /** The filter with string literals blanked, so a label value that happens to say "project=" doesn't count. */
  private static String unquoted(String filter) {
    StringBuilder out = new StringBuilder(filter.length());
    boolean quoted = false;
    for (int i = 0; i < filter.length(); i++) {
      char c = filter.charAt(i);
      if (quoted) {
        if (c == '\\') {
          i++;
        } else if (c == '"') {
          quoted = false;
          out.append('"');
        }
        continue;
      }
      if (c == '"') {
        quoted = true;
      }
      out.append(c);
    }
    return out.toString();
  }

  /** The smallest period that gives one series at most {@value #TARGET_POINTS} points. */
  static int autoPeriod(Duration range) {
    long seconds = Math.max(1, range.toSeconds());
    for (int p : PERIODS) {
      if (seconds / p <= TARGET_POINTS) {
        return p;
      }
    }
    return PERIODS.getLast();
  }

  /** {@code metric.type = "<metric>" AND (<filter>)}: what goes to {@code timeSeries.list}. */
  String monitoringFilter() {
    String base = "metric.type = \"" + metric + "\"";
    return filter == null ? base : base + " AND (" + filter + ")";
  }

  ObjectNode payload() {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("tool", "gcp_metrics");
    n.put("project", project);
    n.put("action", action.id);
    if (metric != null) {
      n.put("metric", metric);
    }
    if (filter != null) {
      n.put("filter", filter);
    }
    if (from != null) {
      n.put("from", from.toString());
      n.put("to", to.toString());
      n.put("period", period);
    }
    if (aligner != null) {
      n.put("aligner", aligner);
    }
    if (reducer != null) {
      n.put("reducer", reducer);
    }
    if (!groupBy.isEmpty()) {
      ArrayNode g = n.putArray("groupBy");
      groupBy.forEach(g::add);
    }
    if (prefix != null) {
      n.put("prefix", prefix);
    }
    if (state != null) {
      n.put("state", state);
    }
    n.put("limit", limit);
    if (cursor != null) {
      n.put("cursor", cursor);
    }
    return n;
  }

  /**
   * What a cursor is bound to: the query without its page, size and times. A relative {@code -1h} resolves anew on
   * every call, so the cursor carries the interval of the first page and the next pages read that one.
   */
  String hash() {
    ObjectNode n = payload();
    n.remove("cursor");
    n.remove("limit");
    n.remove("from");
    n.remove("to");
    n.remove("period");
    return DescribeRequest.sha256(n.toString());
  }

  /** The same request over the interval a cursor carries. */
  MetricsRequest over(Instant from, Instant to, int period) {
    return new MetricsRequest(project, action, metric, filter, from, to, period, aligner, reducer, groupBy, prefix,
        state, limit, cursor, notes);
  }
}
