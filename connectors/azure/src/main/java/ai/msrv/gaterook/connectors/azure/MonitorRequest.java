package ai.msrv.gaterook.connectors.azure;

import ai.msrv.gaterook.connectors.support.TimeArg;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One {@code azure_monitor} call, checked without I/O: metrics of one resource ({@code query}), its metric
 * definitions ({@code list_metrics}), fired alerts ({@code alerts}), the Activity Log ({@code activity_log}) or
 * Resource Health ({@code health}). Times become absolute here, so an approval stores what runs; ranges are capped by
 * the Cloud policy's longest range and by what each API keeps (alerts 30 days, the Activity Log 90).
 *
 * @param resource the resource ID the action is about, or {@code null}
 * @param interval the metrics' time grain, ISO 8601 ({@code PT5M})
 * @param notes    what the gateway changed about the call, for the answer
 */
record MonitorRequest(Action action, ResourceIds.Parsed resource, String rawResource, String subscription,
    List<String> metrics, List<String> aggregations, String interval, String filter, String namespace, int top,
    Instant from, Instant to, String timeRange, String state, String condition, String severity,
    String resourceGroup, String status, String level, String operation, int limit, String cursor,
    List<String> notes) {

  enum Action {
    QUERY("query"), LIST_METRICS("list_metrics"), ALERTS("alerts"), ACTIVITY_LOG("activity_log"), HEALTH("health");

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

  static final List<String> AGGREGATIONS = List.of("Average", "Minimum", "Maximum", "Total", "Count");
  /** Time grains Azure Monitor keeps for platform metrics, finest first. */
  static final List<String> INTERVALS = List.of("PT1M", "PT5M", "PT15M", "PT30M", "PT1H", "PT6H", "PT12H", "P1D");
  static final Map<String, Duration> GRAINS = Map.of("PT1M", Duration.ofMinutes(1), "PT5M", Duration.ofMinutes(5),
      "PT15M", Duration.ofMinutes(15), "PT30M", Duration.ofMinutes(30), "PT1H", Duration.ofHours(1), "PT6H",
      Duration.ofHours(6), "PT12H", Duration.ofHours(12), "P1D", Duration.ofDays(1));
  static final Duration ALERTS_KEEP = Duration.ofDays(30);
  static final Duration ACTIVITY_KEEP = Duration.ofDays(90);
  static final int MAX_METRICS = 20;
  private static final Pattern METRIC = Pattern.compile("^[\\p{L}\\p{N} ._/()%:-]{1,128}$");
  private static final Pattern NAMESPACE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9./ _-]{0,127}$");
  private static final Pattern GROUP = Pattern.compile("^[\\p{L}\\p{N}._()-]{1,90}$");
  private static final Set<String> STATES = Set.of("New", "Acknowledged", "Closed");
  private static final Set<String> CONDITIONS = Set.of("Fired", "Resolved");
  private static final Set<String> SEVERITIES = Set.of("Sev0", "Sev1", "Sev2", "Sev3", "Sev4");
  private static final Set<String> TIME_RANGES = Set.of("1h", "1d", "7d", "30d");
  private static final Set<String> STATUSES = Set.of("Succeeded", "Failed", "Started", "Accepted", "Canceled");
  private static final Set<String> LEVELS = Set.of("Critical", "Error", "Warning", "Informational", "Verbose");

  static MonitorRequest of(JsonNode args, Settings s, AzurePolicy p, Instant now) throws Refusal {
    String actionText = text(args, "action", "query");
    Action action = Action.of(actionText);
    if (action == null) {
      throw new Refusal("bad_input", "action is query, list_metrics, alerts, activity_log or health.", null);
    }
    List<String> notes = new ArrayList<>();
    String rawResource = text(args, "resource", null);
    ResourceIds.Parsed resource = null;
    if (rawResource != null) {
      resource = ResourceIds.parse(rawResource);
      if (resource == null || !resource.isResource()) {
        throw new Refusal("azure_path", "\"" + Settings.abbreviate(rawResource) + "\" isn't a resource ID "
            + "(/subscriptions/<id>/resourceGroups/<group>/providers/<namespace>/<type>/<name>).", null);
      }
      if (!s.hasSubscription(resource.subscription())) {
        throw new Refusal("subscription", "Subscription " + resource.subscription() + " isn't one of this "
            + "connection's subscriptions.", null);
      }
      CallRequest.checkPolicy(resource, p);
    }
    if ((action == Action.QUERY || action == Action.LIST_METRICS) && resource == null) {
      throw new Refusal("bad_input", "Name the resource whose metrics to read (its ID from azure_describe).", null);
    }
    String subscription = text(args, "subscription", null);
    if (subscription != null && !s.hasSubscription(subscription)) {
      throw new Refusal("subscription", "Subscription " + Settings.abbreviate(subscription) + " isn't one of this "
          + "connection's subscriptions.", "Use one of: " + String.join(", ", s.subscriptions()) + ".");
    }
    if (subscription == null) {
      subscription = resource != null ? resource.subscription() : s.subscriptions().getFirst();
    }
    subscription = subscription.toLowerCase(Locale.ROOT);
    int limit = args.path("limit").isNumber() ? args.path("limit").asInt() : Math.min(50, p.maxItems());
    if (limit > p.maxItems()) {
      notes.add("limit lowered to " + p.maxItems() + " by the " + p.env().id() + " policy");
    }
    limit = Math.max(1, Math.min(limit, p.maxItems()));

    Duration policyRange = Duration.ofDays(p.maxRangeDays());
    Duration keep = switch (action) {
      case ALERTS -> ALERTS_KEEP;
      case ACTIVITY_LOG -> ACTIVITY_KEEP;
      default -> Duration.ofDays(93);
    };
    Duration maxRange = policyRange.compareTo(keep) < 0 ? policyRange : keep;
    String defaultFrom = action == Action.ACTIVITY_LOG ? "-24h" : action == Action.ALERTS ? "-1d" : "-1h";
    Instant from = null;
    Instant to = null;
    String timeRange = null;
    if (action == Action.ALERTS && text(args, "from", null) == null && text(args, "to", null) == null) {
      timeRange = text(args, "timeRange", "1d");
      if (!TIME_RANGES.contains(timeRange)) {
        throw new Refusal("bad_input", "timeRange is 1h, 1d, 7d or 30d.", null);
      }
      Duration range = switch (timeRange) {
        case "1h" -> Duration.ofHours(1);
        case "1d" -> Duration.ofDays(1);
        case "7d" -> Duration.ofDays(7);
        default -> Duration.ofDays(30);
      };
      if (range.compareTo(maxRange) > 0) {
        throw new Refusal("range", "timeRange " + timeRange + " is longer than " + p.env().id() + " allows ("
            + p.maxRangeDays() + " days).", "Use a shorter timeRange.");
      }
    } else if (action == Action.QUERY || action == Action.ALERTS || action == Action.ACTIVITY_LOG) {
      try {
        TimeArg.Range range = TimeArg.range(text(args, "from", defaultFrom), text(args, "to", "now"), now, maxRange);
        from = range.from();
        to = range.to();
      } catch (TimeArg.InvalidTimeException e) {
        throw new Refusal("range", e.getMessage(), "Write times as -1h, now-30m or 2026-10-22T14:00:00Z.");
      }
      if (action != Action.QUERY && from.isBefore(now.minus(keep))) {
        throw new Refusal("range", "Azure keeps " + (action == Action.ALERTS ? "alerts for 30" : "the Activity Log "
            + "for 90") + " days; from is earlier.", "Use a later from.");
      }
    }

    List<String> metrics = new ArrayList<>();
    List<String> aggregations = new ArrayList<>();
    String interval = null;
    String filter = null;
    String namespace = text(args, "namespace", null);
    int top = 0;
    if (namespace != null && !NAMESPACE.matcher(namespace).matches()) {
      throw new Refusal("bad_input", "namespace is a metric namespace, like Microsoft.Compute/virtualMachines.",
          null);
    }
    if (action == Action.QUERY) {
      Set<String> names = new LinkedHashSet<>();
      JsonNode m = args.path("metrics");
      if (m.isString()) {
        names.add(m.asString().strip());
      } else {
        for (JsonNode n : m) {
          names.add(n.asString("").strip());
        }
      }
      names.remove("");
      if (names.isEmpty()) {
        throw new Refusal("bad_input", "Name at least one metric, like Percentage CPU (action list_metrics lists "
            + "them).", null);
      }
      if (names.size() > MAX_METRICS) {
        throw new Refusal("bad_input", "At most " + MAX_METRICS + " metrics per call.", null);
      }
      for (String name : names) {
        if (!METRIC.matcher(name).matches()) {
          throw new Refusal("bad_input", "\"" + Settings.abbreviate(name) + "\" isn't a metric name.", null);
        }
      }
      metrics.addAll(names);
      JsonNode a = args.path("aggregation");
      if (a.isString()) {
        aggregations.add(aggregation(a.asString()));
      } else {
        for (JsonNode n : a) {
          aggregations.add(aggregation(n.asString("")));
        }
      }
      if (aggregations.isEmpty()) {
        aggregations.add("Average");
      }
      aggregations = List.copyOf(new LinkedHashSet<>(aggregations));
      filter = text(args, "filter", null);
      if (filter != null && (filter.length() > 1_000 || filter.contains("\n") || filter.contains("\r"))) {
        throw new Refusal("bad_input", "filter is one line of at most 1,000 characters, like "
            + "\"Instance eq '*'\".", null);
      }
      top = args.path("top").isNumber() ? Math.max(1, Math.min(50, args.path("top").asInt())) : filter == null ? 0
          : 10;
      int series = Math.max(1, top);
      Duration length = Duration.between(from, to);
      String asked = text(args, "interval", null);
      if (asked != null && !GRAINS.containsKey(asked.toUpperCase(Locale.ROOT))) {
        throw new Refusal("bad_input", "interval is one of " + String.join(", ", INTERVALS) + ".", null);
      }
      interval = asked == null ? null : asked.toUpperCase(Locale.ROOT);
      for (String grain : INTERVALS) {
        if (interval != null && GRAINS.get(grain).compareTo(GRAINS.get(interval)) < 0) {
          continue;
        }
        long points = (length.toSeconds() / Math.max(1, GRAINS.get(grain).toSeconds()) + 1) * metrics.size()
            * aggregations.size() * series;
        if (points <= p.maxPoints()) {
          if (interval != null && !grain.equals(interval)) {
            notes.add("interval raised from " + interval + " to " + grain + " to stay within " + p.maxPoints()
                + " points");
          }
          interval = grain;
          break;
        }
        if (grain.equals("P1D")) {
          throw new Refusal("points", "Even daily points would exceed " + p.maxPoints() + " data points on "
              + p.env().id() + ".", "Use a shorter range, fewer metrics, fewer aggregations or a lower top.");
        }
      }
    }

    String state = choice(args, "state", STATES);
    String condition = choice(args, "condition", CONDITIONS);
    if (action == Action.ALERTS && condition == null) {
      condition = "Fired";
    }
    String severity = choice(args, "severity", SEVERITIES);
    String status = choice(args, "status", STATUSES);
    String level = choice(args, "level", LEVELS);
    String resourceGroup = text(args, "resourceGroup", null);
    if (resourceGroup != null && !GROUP.matcher(resourceGroup).matches()) {
      throw new Refusal("bad_input", "\"" + Settings.abbreviate(resourceGroup) + "\" isn't a resource group name.",
          null);
    }
    if (action == Action.ACTIVITY_LOG && resourceGroup != null && resource != null) {
      throw new Refusal("bad_input", "The Activity Log filters by resource or by resource group, not both.", null);
    }
    String operation = text(args, "operation", null);
    if (operation != null && operation.length() > 256) {
      throw new Refusal("bad_input", "operation is at most 256 characters.", null);
    }
    String cursor = text(args, "cursor", null);
    return new MonitorRequest(action, resource, rawResource, subscription, List.copyOf(metrics),
        List.copyOf(aggregations), interval, filter, namespace, top, from, to, timeRange, state, condition, severity,
        resourceGroup, status, level, operation, limit, cursor, List.copyOf(notes));
  }

  /** The approved form: what {@link #payload} stored, absolute times included. */
  static MonitorRequest ofPayload(JsonNode payload, Settings s, AzurePolicy p, Instant now) throws Refusal {
    return of(payload.path("arguments"), s, p, now);
  }

  /** The hash of what makes two calls the same query, for cursors. */
  String hash() {
    return Cursors.hash(action.id, rawResource, subscription, String.valueOf(from), String.valueOf(to), timeRange,
        state, condition, severity, resourceGroup, status, level, operation, Integer.toString(limit));
  }

  ObjectNode payload() {
    ObjectNode p = JsonNodeFactory.instance.objectNode();
    p.put("tool", "azure_monitor");
    ObjectNode a = p.putObject("arguments");
    a.put("action", action.id);
    if (rawResource != null) {
      a.put("resource", rawResource);
    }
    a.put("subscription", subscription);
    if (!metrics.isEmpty()) {
      ArrayNode m = a.putArray("metrics");
      metrics.forEach(m::add);
      ArrayNode ag = a.putArray("aggregation");
      aggregations.forEach(ag::add);
      a.put("interval", interval);
    }
    put(a, "filter", filter);
    put(a, "namespace", namespace);
    if (top > 0) {
      a.put("top", top);
    }
    if (from != null) {
      a.put("from", from.toString());
      a.put("to", to.toString());
    }
    put(a, "timeRange", timeRange);
    put(a, "state", state);
    put(a, "condition", condition);
    put(a, "severity", severity);
    put(a, "resourceGroup", resourceGroup);
    put(a, "status", status);
    put(a, "level", level);
    put(a, "operation", operation);
    a.put("limit", limit);
    put(a, "cursor", cursor);
    return p;
  }

  private static void put(ObjectNode o, String field, String value) {
    if (value != null) {
      o.put(field, value);
    }
  }

  private static String aggregation(String text) throws Refusal {
    for (String a : AGGREGATIONS) {
      if (a.equalsIgnoreCase(text.strip())) {
        return a;
      }
    }
    throw new Refusal("bad_input", "aggregation is " + String.join(", ", AGGREGATIONS) + ".", null);
  }

  private static String choice(JsonNode args, String field, Set<String> allowed) throws Refusal {
    String v = text(args, field, null);
    if (v == null) {
      return null;
    }
    for (String a : allowed) {
      if (a.equalsIgnoreCase(v)) {
        return a;
      }
    }
    throw new Refusal("bad_input", field + " is one of " + String.join(", ", new java.util.TreeSet<>(allowed)) + ".",
        null);
  }

  private static String text(JsonNode node, String field, String dflt) {
    JsonNode n = node.get(field);
    return n == null || n.isNull() || !n.isValueNode() || n.asString().isBlank() ? dflt : n.asString().strip();
  }
}
