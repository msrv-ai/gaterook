package ai.msrv.gaterook.connectors.cloudflare;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.support.TimeArg;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code cloudflare_analytics}: one query of the GraphQL Analytics API that GateRook writes itself from a fixed
 * template per dataset — {@code firewallEventsAdaptive} (security events), {@code firewallEventsAdaptiveGroups} and
 * {@code httpRequestsAdaptiveGroups} (counts by dimension) of one zone. Agents choose the dataset, the time range,
 * dimensions from a fixed list and filter values; values are checked and written as GraphQL literals (JSON string
 * escaping), so no text of theirs becomes part of the query's structure.
 */
final class Analytics {

  static final Set<String> ARGS = Set.of("connection", "zone", "dataset", "from", "to", "groupBy", "filter", "limit");
  static final Set<String> DATASETS = Set.of("security_events", "security_summary", "http_requests");
  /** The longest range one query reads: the adaptive datasets keep at most about a month. */
  static final Duration MAX_RANGE = Duration.ofDays(31);

  /** Filter arguments → the GraphQL filter field, per kind of dataset. */
  private static final Map<String, String> SECURITY_FILTERS = map("action", "action", "source", "source", "ruleId",
      "ruleId", "clientIP", "clientIP", "clientCountryName", "clientCountryName", "host", "clientRequestHTTPHost",
      "path", "clientRequestPath", "method", "clientRequestHTTPMethodName", "status", "edgeResponseStatus");
  private static final Map<String, String> HTTP_FILTERS = map("clientIP", "clientIP", "clientCountryName",
      "clientCountryName", "host", "clientRequestHTTPHost", "path", "clientRequestPath", "method",
      "clientRequestHTTPMethodName", "status", "edgeResponseStatus");
  private static final Set<String> SECURITY_DIMENSIONS = Set.of("action", "source", "ruleId", "clientCountryName",
      "clientIP", "clientRequestHTTPHost", "clientRequestPath", "clientRequestHTTPMethodName", "edgeResponseStatus",
      "userAgent", "clientAsn");
  private static final Set<String> HTTP_DIMENSIONS = Set.of("clientCountryName", "clientIP", "clientRequestHTTPHost",
      "clientRequestPath", "clientRequestHTTPMethodName", "edgeResponseStatus", "cacheStatus", "originResponseStatus",
      "userAgent", "clientAsn");
  /** The fields of one security event. */
  static final List<String> EVENT_FIELDS = List.of("datetime", "action", "source", "ruleId", "description",
      "rayName", "clientIP", "clientAsn", "clientCountryName", "clientRequestHTTPHost", "clientRequestHTTPMethodName",
      "clientRequestPath", "clientRequestQuery", "userAgent", "edgeResponseStatus");

  private static final Pattern ACTION = Pattern.compile("[a-z_]{1,40}");
  private static final Pattern SOURCE = Pattern.compile("[a-zA-Z_]{1,40}");
  private static final Pattern RULE = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final Pattern IP = Pattern.compile("[0-9A-Fa-f:.]{2,45}");
  private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");
  private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.-]{1,253}");
  private static final Pattern METHOD = Pattern.compile("[A-Z]{3,10}");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Zones.Ref zone;
  private final String dataset;
  private final TimeArg.Range range;
  private final List<String> groupBy;
  private final Map<String, Object> filter;
  private final int limit;

  private Analytics(Zones.Ref zone, String dataset, TimeArg.Range range, List<String> groupBy,
      Map<String, Object> filter, int limit) {
    this.zone = zone;
    this.dataset = dataset;
    this.range = range;
    this.groupBy = groupBy;
    this.filter = filter;
    this.limit = limit;
  }

  static Analytics parse(ObjectNode args, CloudflareSettings s, String connection, JsonNode metadata, Instant now,
      Duration policyRange) throws Refusal {
    Args.only(args, CloudflareTools.ANALYTICS, ARGS);
    Zones.Ref ref = Zones.Ref.parse(Args.string(args, "zone", 253));
    Zones.allowed(ref, s, connection, metadata);
    String dataset = Args.choice(args, "dataset", DATASETS, null);
    if (dataset == null) {
      throw Refusal.badInput("The call names no dataset.", "Pass security_events, security_summary or "
          + "http_requests.");
    }
    Duration max = policyRange != null && policyRange.compareTo(MAX_RANGE) < 0 ? policyRange : MAX_RANGE;
    TimeArg.Range range;
    try {
      range = TimeArg.range(args.hasNonNull("from") ? Args.string(args, "from", 40) : "-1h",
          Args.string(args, "to", 40), now.truncatedTo(ChronoUnit.SECONDS), max);
    } catch (TimeArg.InvalidTimeException e) {
      throw new Refusal("range", e.getMessage(), null);
    }
    range = new TimeArg.Range(range.from().truncatedTo(ChronoUnit.SECONDS), range.to().truncatedTo(ChronoUnit.SECONDS));
    boolean http = dataset.equals("http_requests");
    List<String> groupBy = new ArrayList<>();
    if (args.hasNonNull("groupBy")) {
      if (dataset.equals("security_events")) {
        throw Refusal.badInput("groupBy applies to security_summary and http_requests.", null);
      }
      for (String d : Args.strings(args, "groupBy", 2, 64)) {
        if (!(http ? HTTP_DIMENSIONS : SECURITY_DIMENSIONS).contains(d)) {
          throw Refusal.badInput(d + " isn't a dimension of " + dataset + ".", "Use one of " + String.join(", ",
              (http ? HTTP_DIMENSIONS : SECURITY_DIMENSIONS).stream().sorted().toList()) + ".");
        }
        groupBy.add(d);
      }
    } else if (!dataset.equals("security_events")) {
      groupBy.addAll(http ? List.of("edgeResponseStatus") : List.of("action", "source"));
    }
    Map<String, Object> filter = new LinkedHashMap<>();
    JsonNode f = args.get("filter");
    if (f != null && !f.isNull()) {
      if (!(f instanceof ObjectNode fo)) {
        throw Refusal.badInput("filter must be an object.", null);
      }
      Map<String, String> fields = http ? HTTP_FILTERS : SECURITY_FILTERS;
      for (String name : fo.propertyNames()) {
        String field = fields.get(name);
        if (field == null) {
          throw Refusal.badInput("filter." + Args.abbreviate(name) + " doesn't apply to " + dataset + ".",
              "It takes " + String.join(", ", fields.keySet()) + ".");
        }
        if (name.equals("status")) {
          filter.put(field, Args.integer(fo, "status", 100, 599));
          continue;
        }
        Pattern p = switch (name) {
          case "action" -> ACTION;
          case "source" -> SOURCE;
          case "ruleId" -> RULE;
          case "clientIP" -> IP;
          case "clientCountryName" -> COUNTRY;
          case "host" -> HOST;
          case "method" -> METHOD;
          default -> null;
        };
        String v = p == null ? Args.string(fo, name, 1_024) : Args.string(fo, name, p, "a valid " + name);
        if (name.equals("path") && (v == null || !v.startsWith("/"))) {
          throw Refusal.badInput("filter.path must start with /.", null);
        }
        filter.put(field, v);
      }
    }
    Integer limit = Args.integer(args, "limit", 1, 100);
    return new Analytics(ref, dataset, range, List.copyOf(groupBy), filter, limit == null ? 25 : limit);
  }

  /** The query for a zone, with every value a literal GateRook checked. */
  String query(String zoneId) {
    StringBuilder f = new StringBuilder();
    f.append("{datetime_geq: ").append(literal(range.from().toString())).append(", datetime_leq: ")
        .append(literal(range.to().toString()));
    for (Map.Entry<String, Object> e : filter.entrySet()) {
      f.append(", ").append(e.getKey()).append(": ").append(e.getValue() instanceof Integer i ? i.toString()
          : literal((String) e.getValue()));
    }
    f.append('}');
    String body = switch (dataset) {
      case "security_events" -> "events: firewallEventsAdaptive(filter: " + f + ", limit: " + limit
          + ", orderBy: [datetime_DESC]) { " + String.join(" ", EVENT_FIELDS) + " }";
      case "security_summary" -> "groups: firewallEventsAdaptiveGroups(filter: " + f + ", limit: " + limit
          + ", orderBy: [count_DESC]) { count dimensions { " + String.join(" ", groupBy) + " } }";
      default -> "totals: httpRequestsAdaptiveGroups(filter: " + f + ", limit: 1) { count sum { edgeResponseBytes "
          + "visits } } groups: httpRequestsAdaptiveGroups(filter: " + f + ", limit: " + limit
          + ", orderBy: [count_DESC]) { count sum { edgeResponseBytes visits } dimensions { "
          + String.join(" ", groupBy) + " } }";
    };
    return "query GateRookAnalytics { viewer { zones(filter: {zoneTag: " + literal(zoneId) + "}) { " + body
        + " } } }";
  }

  /** A GraphQL string literal: JSON's escaping is GraphQL's. */
  static String literal(String value) {
    return JSON.writeValueAsString(value);
  }

  ToolResult run(Call c) throws ConnectorException {
    Zones.Zone z = c.zones().resolve(c, zone);
    JsonNode data = c.api().graphql(c, query(z.id()), "analytics");
    JsonNode zones = data.path("viewer").path("zones");
    if (!zones.isArray() || zones.isEmpty()) {
      throw ConnectorException.resourceError("Cloudflare's GraphQL Analytics API returned no data for " + z.name()
          + ". " + CloudflareApi.permissionHint("analytics"), null);
    }
    JsonNode node = zones.get(0);
    ObjectNode s = Answers.envelope(c, dataset);
    s.set("zone", z.node());
    s.put("from", range.from().toString());
    s.put("to", range.to().toString());
    List<String> notes = new ArrayList<>();
    ArrayNode items = Answers.F.arrayNode();
    if (dataset.equals("security_events")) {
      for (JsonNode e : node.path("events")) {
        items.add(e.deepCopy());
      }
      if (items.size() >= limit) {
        notes.add("More events may match: narrow from/to or the filter, or raise limit.");
      }
    } else {
      for (JsonNode g : node.path("groups")) {
        ObjectNode row = items.addObject();
        row.set("dimensions", g.path("dimensions").deepCopy());
        row.set("count", g.path("count").deepCopy());
        if (g.path("sum").isObject()) {
          row.set("sum", g.path("sum").deepCopy());
        }
      }
      JsonNode totals = node.path("totals");
      if (totals.isArray() && !totals.isEmpty()) {
        ObjectNode t = Answers.F.objectNode();
        t.set("count", totals.get(0).path("count").deepCopy());
        if (totals.get(0).path("sum").isObject()) {
          t.set("sum", totals.get(0).path("sum").deepCopy());
        }
        s.set("item", t);
      }
      notes.add("Counts of adaptive datasets are sampled estimates for busy zones.");
    }
    s.set("items", items);
    return Answers.answer(c, "Analytics · " + dataset + " · " + z.name() + " · " + c.cfg().name(), s, notes);
  }

  private static Map<String, String> map(String... kv) {
    Map<String, String> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put(kv[i], kv[i + 1]);
    }
    return java.util.Collections.unmodifiableMap(m);
  }
}
