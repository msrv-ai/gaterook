package ai.msrv.gaterook.connectors.azure;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code azure_monitor} on Resource Manager's Azure Monitor APIs, all GETs:
 * <ul>
 *   <li>{@code query}: {@code <resource>/providers/Microsoft.Insights/metrics} (2018-01-01): metric names,
 *       aggregations, the time grain, a dimension {@code $filter} and {@code top};</li>
 *   <li>{@code list_metrics}: {@code <resource>/providers/Microsoft.Insights/metricDefinitions} (2018-01-01);</li>
 *   <li>{@code alerts}: {@code /subscriptions/<id>/providers/Microsoft.AlertsManagement/alerts} (2019-03-01): fired or
 *       resolved alerts by state, severity, resource group or target, newest change first;</li>
 *   <li>{@code activity_log}: {@code /subscriptions/<id>/providers/Microsoft.Insights/eventtypes/management/values}
 *       (2015-04-01) between two times, by resource group or resource; without the callers' token {@code claims};</li>
 *   <li>{@code health}: Resource Health ({@code availabilityStatuses}, 2022-10-01) of one resource or of the
 *       subscription.</li>
 * </ul>
 * Items whose resource the Cloud policy hides are left out (services and resource masks: alerts, events and health
 * statuses carry no location), answers are masked, and paging continues through a sealed cursor.
 */
final class Monitor {

  static final String METRICS_API = "2018-01-01";
  static final String ALERTS_API = "2019-03-01";
  static final String ACTIVITY_API = "2015-04-01";
  static final String HEALTH_API = "2022-10-01";
  static final String PURPOSE = "azure.monitor";
  /**
   * Properties of an Activity Log event left out: the request and response bodies of an operation can hold what was
   * written (settings, keys). The event's {@code claims}, which repeat the caller's token claims, are never mapped.
   */
  static final java.util.Set<String> ACTIVITY_BODIES = java.util.Set.of("requestbody", "responsebody");
  static final int MAX_PAGES = 3;

  private final Call call;

  Monitor(Call call) {
    this.call = call;
  }

  ToolResult run(MonitorRequest r) throws ConnectorException {
    call.quota(r.subscription(), "Azure Monitor", Call.MONITOR_PER_MINUTE);
    if (r.cursor() != null) {
      // The page token continues the query of the first page: its range comes with it.
      JsonNode c = continuation(r);
      if (c.hasNonNull("f") && c.hasNonNull("e")) {
        try {
          r = r.withRange(java.time.Instant.parse(c.path("f").asString()), java.time.Instant.parse(
              c.path("e").asString()));
        } catch (java.time.format.DateTimeParseException e) {
          throw ConnectorException.badInput("The cursor is invalid. Start again without cursor.");
        }
      }
    }
    return switch (r.action()) {
      case QUERY -> metrics(r);
      case LIST_METRICS -> definitions(r);
      case ALERTS -> alerts(r);
      case ACTIVITY_LOG -> activity(r);
      case HEALTH -> health(r);
    };
  }

  // ── Metrics ─────────────────────────────────────────────────────────────────────────────────────────────────

  private ToolResult metrics(MonitorRequest r) throws ConnectorException {
    Map<String, String> q = Arm.query(METRICS_API);
    q.put("metricnames", String.join(",", r.metrics()));
    q.put("timespan", r.from() + "/" + r.to());
    q.put("interval", r.interval());
    q.put("aggregation", String.join(",", r.aggregations()));
    if (r.namespace() != null) {
      q.put("metricnamespace", r.namespace());
    }
    if (r.filter() != null) {
      q.put("$filter", r.filter());
    }
    if (r.top() > 0) {
      q.put("top", Integer.toString(r.top()));
    }
    URI uri = call.arm().url(ResourceIds.encodedPath(r.resource()) + "/providers/Microsoft.Insights/metrics", q);
    JsonNode answer = call.arm().get(uri, "Azure Monitor metrics");
    ArrayNode metrics = JsonNodeFactory.instance.arrayNode();
    long points = 0;
    boolean cut = false;
    int maxPoints = call.policy().maxPoints();
    for (JsonNode m : answer.path("value")) {
      ObjectNode metric = metrics.addObject();
      metric.put("name", m.path("name").path("value").asString(""));
      metric.put("unit", m.path("unit").asString(""));
      if (m.has("displayDescription")) {
        metric.put("description", m.path("displayDescription").asString(""));
      }
      ArrayNode series = metric.putArray("timeseries");
      for (JsonNode ts : m.path("timeseries")) {
        ObjectNode one = JsonNodeFactory.instance.objectNode();
        ObjectNode dims = one.putObject("dimensions");
        for (JsonNode d : ts.path("metadatavalues")) {
          dims.put(d.path("name").path("value").asString(""), d.path("value").asString(""));
        }
        ArrayNode data = one.putArray("data");
        for (JsonNode point : ts.path("data")) {
          if (points >= maxPoints) {
            cut = true;
            break;
          }
          ObjectNode p = data.addObject();
          p.put("time", point.path("timeStamp").asString(""));
          for (String a : r.aggregations()) {
            JsonNode v = point.get(a.toLowerCase(Locale.ROOT));
            if (v != null && v.isNumber()) {
              p.set(a.toLowerCase(Locale.ROOT), v);
            }
          }
          points++;
        }
        series.add(one);
        if (cut) {
          break;
        }
      }
      if (cut) {
        break;
      }
    }
    List<String> notes = new ArrayList<>(r.notes());
    if (cut) {
      notes.add("Cut at " + maxPoints + " data points (the " + call.cfg().env().id() + " policy): use a coarser "
          + "interval or a shorter range.");
    }
    ObjectNode s = base(r, cut ? "points_limit" : null, null, 0, 0, notes);
    s.put("resource", r.rawResource());
    s.put("from", r.from().toString());
    s.put("to", r.to().toString());
    s.put("interval", r.interval());
    s.set("metrics", metrics);
    s.set("items", JsonNodeFactory.instance.arrayNode());
    s.put("count", metrics.size());
    s.put("points", points);
    StringBuilder t = new StringBuilder();
    t.append("Metrics of ").append(r.rawResource()).append(", ").append(r.from()).append(" → ").append(r.to())
        .append(" every ").append(r.interval()).append(':');
    for (JsonNode m : metrics) {
      t.append('\n').append(m.path("name").asString()).append(" (").append(m.path("unit").asString()).append(')');
      for (JsonNode ts : m.path("timeseries")) {
        t.append("\n  ").append(ts.path("dimensions").isEmpty() ? "" : ts.path("dimensions") + " ");
        List<String> values = new ArrayList<>();
        for (JsonNode p : ts.path("data")) {
          for (String a : r.aggregations()) {
            JsonNode v = p.get(a.toLowerCase(Locale.ROOT));
            if (v != null) {
              values.add(p.path("time").asString().replaceFirst(":00Z$", "Z") + " " + a.toLowerCase(Locale.ROOT)
                  + "=" + v.asString());
            }
          }
        }
        t.append(values.isEmpty() ? "no data" : String.join(", ", values));
      }
    }
    return answer(s, t, notes);
  }

  private ToolResult definitions(MonitorRequest r) throws ConnectorException {
    Map<String, String> q = Arm.query(METRICS_API);
    if (r.namespace() != null) {
      q.put("metricnamespace", r.namespace());
    }
    URI uri = call.arm().url(ResourceIds.encodedPath(r.resource()) + "/providers/Microsoft.Insights/metricDefinitions",
        q);
    JsonNode answer = call.arm().get(uri, "Azure Monitor metric definitions");
    ArrayNode items = JsonNodeFactory.instance.arrayNode();
    int total = 0;
    for (JsonNode d : answer.path("value")) {
      total++;
      if (items.size() >= r.limit()) {
        continue;
      }
      ObjectNode item = items.addObject();
      item.put("name", d.path("name").path("value").asString(""));
      item.put("displayName", d.path("name").path("localizedValue").asString(""));
      item.put("unit", d.path("unit").asString(""));
      item.put("namespace", d.path("namespace").asString(""));
      item.put("primaryAggregation", d.path("primaryAggregationType").asString(""));
      ArrayNode aggs = item.putArray("aggregations");
      d.path("supportedAggregationTypes").forEach(a -> aggs.add(a.asString("")));
      ArrayNode dims = item.putArray("dimensions");
      d.path("dimensions").forEach(x -> dims.add(x.path("value").asString("")));
      ArrayNode grains = item.putArray("timeGrains");
      d.path("metricAvailabilities").forEach(x -> grains.add(x.path("timeGrain").asString("")));
      if (d.path("isDimensionRequired").asBoolean(false)) {
        item.put("dimensionRequired", true);
      }
    }
    List<String> notes = new ArrayList<>(r.notes());
    if (total > items.size()) {
      notes.add((total - items.size()) + " more definitions: raise limit or pass namespace.");
    }
    ObjectNode s = base(r, total > items.size() ? "limit" : null, null, 0, 0, notes);
    s.put("resource", r.rawResource());
    s.set("items", items);
    s.put("count", items.size());
    StringBuilder t = new StringBuilder("Metrics of " + r.rawResource() + ":");
    for (JsonNode i : items) {
      t.append('\n').append(i.path("name").asString()).append(" (").append(i.path("unit").asString()).append(", ")
          .append(i.path("primaryAggregation").asString()).append(i.path("dimensions").isEmpty() ? ""
              : "; dimensions " + join(i.path("dimensions"))).append(')');
    }
    return answer(s, t, notes);
  }

  // ── Alerts, Activity Log, Resource Health ───────────────────────────────────────────────────────────────────

  private ToolResult alerts(MonitorRequest r) throws ConnectorException {
    Map<String, String> q = Arm.query(ALERTS_API);
    q.put("monitorCondition", r.condition());
    if (r.timeRange() != null) {
      q.put("timeRange", r.timeRange());
    } else {
      q.put("customTimeRange", r.from() + "/" + r.to());
    }
    if (r.state() != null) {
      q.put("alertState", r.state());
    }
    if (r.severity() != null) {
      q.put("severity", r.severity());
    }
    if (r.resourceGroup() != null) {
      q.put("targetResourceGroup", r.resourceGroup());
    }
    if (r.resource() != null) {
      q.put("targetResource", r.rawResource());
    }
    q.put("pageCount", Integer.toString(Math.min(250, r.limit())));
    q.put("sortBy", "lastModifiedDateTime");
    q.put("sortOrder", "desc");
    String path = "/subscriptions/" + r.subscription() + "/providers/Microsoft.AlertsManagement/alerts";
    Page page = pages(path, q, r, "Azure Monitor alerts", item -> {
      JsonNode e = item.path("properties").path("essentials");
      ObjectNode a = JsonNodeFactory.instance.objectNode();
      a.put("id", item.path("id").asString(""));
      a.put("name", item.path("name").asString(""));
      a.put("severity", e.path("severity").asString(""));
      a.put("state", e.path("alertState").asString(""));
      a.put("condition", e.path("monitorCondition").asString(""));
      a.put("signalType", e.path("signalType").asString(""));
      a.put("monitorService", e.path("monitorService").asString(""));
      a.put("targetResource", e.path("targetResource").asString(""));
      a.put("targetResourceType", e.path("targetResourceType").asString(""));
      a.put("targetResourceGroup", e.path("targetResourceGroup").asString(""));
      a.put("alertRule", e.path("alertRule").asString(""));
      a.put("started", e.path("startDateTime").asString(""));
      a.put("lastModified", e.path("lastModifiedDateTime").asString(""));
      if (e.hasNonNull("monitorConditionResolvedDateTime")) {
        a.put("resolved", e.path("monitorConditionResolvedDateTime").asString(""));
      }
      if (e.hasNonNull("description")) {
        a.put("description", e.path("description").asString(""));
      }
      return a;
    }, a -> a.path("targetResource").asString(null));
    StringBuilder t = new StringBuilder(page.items.size() + " " + (r.condition() == null ? "" : r.condition()
        .toLowerCase(Locale.ROOT) + " ") + "alerts in subscription " + r.subscription() + ":");
    for (JsonNode a : page.items) {
      t.append('\n').append(a.path("severity").asString()).append(' ').append(a.path("name").asString()).append(" · ")
          .append(a.path("state").asString()).append(" · ").append(a.path("targetResource").asString()).append(" · ")
          .append(a.path("started").asString());
    }
    return answer(page.content(r, this), t, page.notes);
  }

  private ToolResult activity(MonitorRequest r) throws ConnectorException {
    Map<String, String> q = Arm.query(ACTIVITY_API);
    StringBuilder filter = new StringBuilder("eventTimestamp ge '" + r.from() + "' and eventTimestamp le '"
        + r.to() + "'");
    if (r.resourceGroup() != null) {
      filter.append(" and resourceGroupName eq '").append(r.resourceGroup().replace("'", "''")).append('\'');
    } else if (r.resource() != null) {
      filter.append(" and resourceUri eq '").append(r.rawResource().replace("'", "''")).append('\'');
    }
    q.put("$filter", filter.toString());
    String path = "/subscriptions/" + r.subscription() + "/providers/Microsoft.Insights/eventtypes/management/values";
    Page page = pages(path, q, r, "the Azure Activity Log", e -> {
      if (r.status() != null && !r.status().equalsIgnoreCase(e.path("status").path("value").asString(""))) {
        return null;
      }
      if (r.level() != null && !r.level().equalsIgnoreCase(e.path("level").asString(""))) {
        return null;
      }
      String operation = e.path("operationName").path("value").asString("");
      if (r.operation() != null && !operation.toLowerCase(Locale.ROOT).contains(r.operation()
          .toLowerCase(Locale.ROOT))) {
        return null;
      }
      ObjectNode a = JsonNodeFactory.instance.objectNode();
      a.put("time", e.path("eventTimestamp").asString(""));
      a.put("operation", operation);
      a.put("status", e.path("status").path("value").asString(""));
      if (!e.path("subStatus").path("value").asString("").isEmpty()) {
        a.put("subStatus", e.path("subStatus").path("value").asString(""));
      }
      a.put("caller", e.path("caller").asString(""));
      a.put("level", e.path("level").asString(""));
      a.put("category", e.path("category").path("value").asString(""));
      a.put("resourceId", e.path("resourceId").asString(""));
      a.put("resourceGroup", e.path("resourceGroupName").asString(""));
      a.put("correlationId", e.path("correlationId").asString(""));
      if (!e.path("description").asString("").isEmpty()) {
        a.put("description", e.path("description").asString(""));
      }
      JsonNode props = e.path("properties");
      if (props.isObject() && !props.isEmpty()) {
        ObjectNode kept = JsonNodeFactory.instance.objectNode();
        props.properties().forEach(p -> {
          if (!ACTIVITY_BODIES.contains(p.getKey().toLowerCase(Locale.ROOT))) {
            kept.set(p.getKey(), p.getValue().deepCopy());
          }
        });
        a.set("properties", kept);
      }
      return a;
    }, a -> a.path("resourceId").asString(null));
    StringBuilder t = new StringBuilder(page.items.size() + " Activity Log events in subscription "
        + r.subscription() + ", " + r.from() + " → " + r.to() + ":");
    for (JsonNode a : page.items) {
      t.append('\n').append(a.path("time").asString()).append(' ').append(a.path("operation").asString()).append(' ')
          .append(a.path("status").asString()).append(" by ").append(a.path("caller").asString()).append(" on ")
          .append(a.path("resourceId").asString());
    }
    return answer(page.content(r, this), t, page.notes);
  }

  private ToolResult health(MonitorRequest r) throws ConnectorException {
    Map<String, String> q = Arm.query(HEALTH_API);
    if (r.resource() != null) {
      URI uri = call.arm().url(ResourceIds.encodedPath(r.resource())
          + "/providers/Microsoft.ResourceHealth/availabilityStatuses/current", q);
      JsonNode status = call.arm().get(uri, "Azure Resource Health");
      ObjectNode item = healthItem(status, r.rawResource());
      int masked = Redactor.redact(item);
      ArrayNode items = JsonNodeFactory.instance.arrayNode().add(item);
      ObjectNode s = base(r, null, null, 0, masked, r.notes());
      s.set("items", items);
      s.put("count", 1);
      StringBuilder t = new StringBuilder(r.rawResource() + ": " + item.path("availabilityState").asString() + " · "
          + item.path("summary").asString());
      return answer(s, t, r.notes());
    }
    q.put("$top", Integer.toString(r.limit()));
    String path = "/subscriptions/" + r.subscription() + "/providers/Microsoft.ResourceHealth/availabilityStatuses";
    Page page = pages(path, q, r, "Azure Resource Health", st -> {
      String id = st.path("id").asString("");
      int at = id.toLowerCase(Locale.ROOT).indexOf("/providers/microsoft.resourcehealth/");
      return healthItem(st, at > 0 ? id.substring(0, at) : id);
    }, a -> a.path("resourceId").asString(null));
    StringBuilder t = new StringBuilder(page.items.size() + " health statuses in subscription " + r.subscription()
        + ":");
    for (JsonNode a : page.items) {
      t.append('\n').append(a.path("availabilityState").asString()).append(' ').append(a.path("resourceId")
          .asString()).append(" · ").append(a.path("summary").asString());
    }
    return answer(page.content(r, this), t, page.notes);
  }

  private static ObjectNode healthItem(JsonNode status, String resourceId) {
    JsonNode p = status.path("properties");
    ObjectNode item = JsonNodeFactory.instance.objectNode();
    item.put("resourceId", resourceId);
    item.put("availabilityState", p.path("availabilityState").asString(""));
    item.put("summary", p.path("summary").asString(""));
    if (p.hasNonNull("detailedStatus")) {
      item.put("detail", p.path("detailedStatus").asString(""));
    }
    item.put("reasonType", p.path("reasonType").asString(""));
    String since = p.path("occuredTime").asString(p.path("occurredTime").asString(""));
    item.put("since", since);
    item.put("reported", p.path("reportedTime").asString(""));
    return item;
  }

  // ── Paging and answers ──────────────────────────────────────────────────────────────────────────────────────

  /** Items of a list API, policy-checked and masked, up to the limit, with a cursor for the rest. */
  private static final class Page {
    final ArrayNode items = JsonNodeFactory.instance.arrayNode();
    int hidden;
    int masked;
    String cursor;
    List<String> notes = new ArrayList<>();

    ObjectNode content(MonitorRequest r, Monitor m) {
      ObjectNode s = m.base(r, cursor != null ? "limit" : null, cursor, hidden, masked, notes);
      s.set("items", items);
      s.put("count", items.size());
      if (r.from() != null) {
        s.put("from", r.from().toString());
        s.put("to", r.to().toString());
      }
      return s;
    }
  }

  private Page pages(String path, Map<String, String> query, MonitorRequest r, String what,
      java.util.function.Function<JsonNode, ObjectNode> map,
      java.util.function.Function<ObjectNode, String> resourceOf) throws ConnectorException {
    Page page = new Page();
    page.notes.addAll(r.notes());
    String token = null;
    int skip = 0;
    if (r.cursor() != null) {
      JsonNode c = continuation(r);
      token = c.path("s").asString("");
      token = token.isBlank() ? null : token;
      skip = Math.max(0, c.path("o").asInt(0));
    }
    String encoded = ResourceIds.encodedPath(ResourceIds.parse(path));
    for (int n = 0; n < MAX_PAGES; n++) {
      Map<String, String> q = new java.util.LinkedHashMap<>(query);
      if (token != null) {
        q.put("$skipToken", token);
      }
      JsonNode answer = call.arm().get(call.arm().url(encoded, q), what);
      int index = 0;
      for (JsonNode raw : answer.path("value")) {
        int at = index++;
        if (at < skip) {
          continue;
        }
        ObjectNode item = map.apply(raw);
        if (item == null) {
          continue;
        }
        if (!allowed(resourceOf.apply(item))) {
          page.hidden++;
          continue;
        }
        if (page.items.size() >= r.limit()) {
          page.cursor = seal(r, token, at);
          page.notes.add("More items: call azure_monitor again with the same arguments and cursor.");
          return finish(page);
        }
        page.masked += Redactor.redact(item);
        page.items.add(item);
      }
      skip = 0;
      String next = skipTokenOf(answer.path("nextLink").asString(null), path);
      if (next == null) {
        return finish(page);
      }
      token = next;
      if (page.items.size() >= r.limit() || !call.timeForAnotherPage()) {
        break;
      }
    }
    page.cursor = seal(r, token, 0);
    page.notes.add("More items: call azure_monitor again with the same arguments and cursor.");
    return finish(page);
  }

  /**
   * A cursor of the next page: Azure's {@code $skipToken}, how many items of that page were handed out, and the range
   * of the first page.
   */
  private String seal(MonitorRequest r, String token, int offset) {
    ObjectNode c = JsonNodeFactory.instance.objectNode();
    c.put("s", token == null ? "" : token);
    c.put("o", offset);
    if (r.from() != null) {
      c.put("f", r.from().toString());
      c.put("e", r.to().toString());
    }
    return Cursors.seal(call.ctx(), PURPOSE, call.cfg().id(), r.hash(), c.toString());
  }

  private JsonNode continuation(MonitorRequest r) throws ConnectorException {
    String text = Cursors.open(call.ctx(), PURPOSE, "azure_monitor", r.cursor(), call.cfg().id(), r.hash());
    try {
      JsonNode n = tools.jackson.databind.json.JsonMapper.builder().build().readTree(text);
      if (n.isObject()) {
        return n;
      }
    } catch (tools.jackson.core.JacksonException e) {
      // Not a cursor of this version: refused below.
    }
    throw ConnectorException.badInput("The cursor is invalid. Start again without cursor.");
  }

  private Page finish(Page page) {
    if (page.hidden > 0) {
      page.notes.add(page.hidden + (page.hidden == 1 ? " item is" : " items are") + " hidden by the Cloud policy of "
          + call.cfg().env().id() + ".");
    }
    return page;
  }

  /** Whether the resource an item is about may be seen: its service and the resource masks. */
  private boolean allowed(String resourceId) {
    AzurePolicy p = call.policy();
    if (resourceId == null || resourceId.isBlank()) {
      return !p.servicesRestricted() && p.resources().isEmpty();
    }
    ResourceIds.Parsed parsed = ResourceIds.parse(resourceId);
    if (parsed == null) {
      return !p.servicesRestricted() && p.resources().isEmpty();
    }
    try {
      CallRequest.checkPolicy(parsed, p);
      return true;
    } catch (Refusal e) {
      return false;
    }
  }

  /** The {@code $skipToken} of a {@code nextLink} of the same path, or {@code null}. */
  static String skipTokenOf(String nextLink, String path) {
    if (nextLink == null || nextLink.isBlank()) {
      return null;
    }
    try {
      URI uri = URI.create(nextLink);
      if (uri.getPath() == null || !uri.getPath().equalsIgnoreCase(path) || uri.getRawQuery() == null) {
        return null;
      }
      for (String pair : uri.getRawQuery().split("&")) {
        int eq = pair.indexOf('=');
        if (eq > 0 && URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8).equalsIgnoreCase("$skiptoken")) {
          return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
        }
      }
    } catch (IllegalArgumentException e) {
      return null;
    }
    return null;
  }

  ObjectNode base(MonitorRequest r, String reason, String cursor, int hidden, int masked, List<String> notes) {
    ObjectNode s = JsonNodeFactory.instance.objectNode();
    s.put("provider", "azure");
    s.put("action", r.action().id);
    s.put("subscription", r.subscription());
    s.put("hiddenByPolicy", hidden);
    s.put("masked", masked);
    s.put("truncated", reason != null);
    if (reason != null) {
      s.put("truncatedReason", reason);
    }
    if (cursor == null) {
      s.putNull("nextCursor");
    } else {
      s.put("nextCursor", cursor);
    }
    ArrayNode n = s.putArray("notes");
    notes.forEach(n::add);
    return s;
  }

  private ToolResult answer(ObjectNode s, StringBuilder t, List<String> notes) {
    long budget = call.budget();
    if (Call.utf8(s.toString()) > budget) {
      JsonNode items = s.path("items");
      while (items.size() > 1 && Call.utf8(s.toString()) > budget) {
        ((ArrayNode) items).remove(items.size() - 1);
      }
      JsonNode metrics = s.path("metrics");
      if (Call.utf8(s.toString()) > budget && metrics.isArray()) {
        s.set("metrics", AzureCall.shrink(metrics, 16));
      }
      s.put("count", Math.max(items.size(), s.path("metrics").size()));
      s.put("truncated", true);
      s.put("truncatedReason", "size_limit");
      s.putNull("nextCursor");
      ((ArrayNode) s.path("notes")).add("The answer was cut to the size limit: narrow the range, lower limit or "
          + "filter by resource group.");
    }
    for (String n : notes) {
      t.append('\n').append(n);
    }
    if (!s.path("nextCursor").isNull()) {
      t.append("\nNext: call azure_monitor again with the same arguments and cursor ")
          .append(s.path("nextCursor").asString());
    }
    t.append('\n').append(ResourceGraph.DATA_NOTE);
    String text = OutputSanitizer.sanitize(t.toString());
    if (Call.utf8(text) + Call.utf8(s.toString()) > call.budget()) {
      text = OutputSanitizer.sanitize(text.substring(0, (int) Math.min(text.length(), call.budget() / 4)) + "…");
    }
    ToolResult result = ToolResult.json(s, text);
    return s.path("truncated").asBoolean(false) ? result.truncated(s.path("truncatedReason").asString("limit"))
        : result;
  }

  private static String join(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.asString()));
    return String.join(", ", out);
  }
}
