package ai.msrv.gaterook.connectors.cloudflare;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.support.TimeArg;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code cloudflare_workers_logs}: a query of Workers Logs ({@code POST …/workers/observability/telemetry/query}) that
 * GateRook writes from structured arguments: a time range, the Worker, a text search and field filters, events or a
 * count. The query is sent with {@code dry: true}, so Cloudflare doesn't keep it among the account's saved queries;
 * it reads and changes nothing else, which makes the POST a read.
 */
final class WorkersLogs {

  static final Set<String> ARGS = Set.of("connection", "script", "from", "to", "search", "regex", "filters", "view",
      "groupBy", "limit", "cursor");
  static final Set<String> OPERATIONS = Set.of("eq", "neq", "includes", "not_includes", "starts_with", "ends_with",
      "regex", "exists", "is_null", "in", "not_in", "gt", "gte", "lt", "lte");
  /** Workers Logs keeps events for at most seven days (Workers Paid; three on Free). */
  static final Duration MAX_RANGE = Duration.ofDays(7);
  /** The query's own name: Cloudflare asks for one even for an ad-hoc query. */
  static final String QUERY_ID = "gaterook-adhoc";

  private record Filter(String key, String operation, JsonNode value) {}

  private final String script;
  private final TimeArg.Range range;
  private final String search;
  private final boolean regex;
  private final List<Filter> filters;
  private final String view;
  private final List<String> groupBy;
  private final int limit;
  private final String cursor;

  private WorkersLogs(String script, TimeArg.Range range, String search, boolean regex, List<Filter> filters,
      String view, List<String> groupBy, int limit, String cursor) {
    this.script = script;
    this.range = range;
    this.search = search;
    this.regex = regex;
    this.filters = filters;
    this.view = view;
    this.groupBy = groupBy;
    this.limit = limit;
    this.cursor = cursor;
  }

  static WorkersLogs parse(ObjectNode args, Instant now, Duration policyRange, int maxLines) throws Refusal {
    Args.only(args, CloudflareTools.WORKERS_LOGS, ARGS);
    String script = Args.string(args, "script", Args.SCRIPT, "a Worker name");
    TimeArg.Range range;
    Duration max = policyRange != null && policyRange.compareTo(MAX_RANGE) < 0 ? policyRange : MAX_RANGE;
    try {
      range = TimeArg.range(args.hasNonNull("from") ? Args.string(args, "from", 40) : "-1h",
          Args.string(args, "to", 40), now, max);
    } catch (TimeArg.InvalidTimeException e) {
      throw new Refusal("range", e.getMessage(), null);
    }
    String search = Args.string(args, "search", 500);
    Boolean regex = Args.bool(args, "regex");
    if (regex != null && regex && search == null) {
      throw Refusal.badInput("regex applies to search, and the call has none.", null);
    }
    List<Filter> filters = new ArrayList<>();
    JsonNode list = args.get("filters");
    if (list != null && !list.isNull()) {
      if (!list.isArray() || list.size() > 10) {
        throw Refusal.badInput("filters must be a list of at most 10 filters.", null);
      }
      for (JsonNode f : list) {
        if (!(f instanceof ObjectNode o)) {
          throw Refusal.badInput("Each filter must be an object: {key, operation, value}.", null);
        }
        Args.only(o, "a filter", Set.of("key", "operation", "value"));
        String key = Args.required(o, "key", Args.FIELD, "a field name such as $metadata.level", null);
        String op = Args.choice(o, "operation", OPERATIONS, null);
        if (op == null) {
          throw Refusal.badInput("The filter on " + key + " has no operation.", "Use eq, includes, gte, exists…");
        }
        JsonNode value = o.get("value");
        boolean takesValue = !op.equals("exists") && !op.equals("is_null");
        if (takesValue && (value == null || !(value.isString() || value.isNumber() || value.isBoolean()))) {
          throw Refusal.badInput("The filter on " + key + " needs a value: text, a number or true/false.", null);
        }
        if (!takesValue && value != null && !value.isNull()) {
          throw Refusal.badInput("Operation " + op + " takes no value.", null);
        }
        if (value != null && value.isString() && value.asString().length() > 1_000) {
          throw Refusal.badInput("The value of the filter on " + key + " is longer than 1,000 characters.", null);
        }
        filters.add(new Filter(key, op, takesValue ? value.deepCopy() : null));
      }
    }
    String view = Args.choice(args, "view", Set.of("events", "count"), "events");
    List<String> groupBy = new ArrayList<>();
    JsonNode g = args.get("groupBy");
    if (g != null && !g.isNull()) {
      if (view.equals("events")) {
        throw Refusal.badInput("groupBy applies to view count.", null);
      }
      for (String key : Args.strings(args, "groupBy", 3, 128)) {
        if (!Args.FIELD.matcher(key).matches()) {
          throw Refusal.badInput("\"" + Args.abbreviate(key) + "\" isn't a field name.", null);
        }
        groupBy.add(key);
      }
    }
    Integer limit = Args.integer(args, "limit", 1, 500);
    String cursor = Args.string(args, "cursor", 200);
    if (cursor != null && (view.equals("count") || !cursor.matches("[A-Za-z0-9_.:-]{1,200}"))) {
      throw Refusal.badInput(view.equals("count") ? "cursor applies to view events."
          : "cursor isn't one this tool gave.", null);
    }
    return new WorkersLogs(script, range, search, regex != null && regex, List.copyOf(filters), view,
        List.copyOf(groupBy), Math.min(limit == null ? 50 : limit, Math.max(1, maxLines)), cursor);
  }

  /** The query as Cloudflare takes it. */
  ObjectNode body() {
    ObjectNode b = Answers.F.objectNode();
    b.put("queryId", QUERY_ID);
    b.put("dry", true);
    b.put("view", view.equals("events") ? "events" : "calculations");
    b.put("limit", limit);
    ObjectNode tf = b.putObject("timeframe");
    tf.put("from", range.from().toEpochMilli());
    tf.put("to", range.to().toEpochMilli());
    if (cursor != null) {
      b.put("offset", cursor);
      b.put("offsetDirection", "next");
    }
    ObjectNode p = b.putObject("parameters");
    ArrayNode fs = p.putArray("filters");
    if (script != null) {
      fs.addObject().put("key", "$metadata.service").put("operation", "eq").put("type", "string")
          .put("value", script);
    }
    for (Filter f : filters) {
      ObjectNode o = fs.addObject().put("key", f.key()).put("operation", f.operation());
      if (f.value() == null) {
        o.put("type", "string");
      } else {
        o.put("type", f.value().isNumber() ? "number" : f.value().isBoolean() ? "boolean" : "string");
        o.set("value", f.value());
      }
    }
    p.put("filterCombination", "and");
    if (search != null) {
      p.putObject("needle").put("value", search).put("isRegex", regex).put("matchCase", false);
    }
    if (view.equals("count")) {
      p.putArray("calculations").addObject().put("operator", "count").put("alias", "count");
      ArrayNode gb = p.putArray("groupBys");
      for (String key : groupBy) {
        gb.addObject().put("type", key.endsWith("statusCode") || key.endsWith("Ms") ? "number" : "string")
            .put("value", key);
      }
      p.put("limit", limit);
      p.putObject("orderBy").put("value", "count").put("order", "desc");
      b.put("ignoreSeries", true);
    }
    return b;
  }

  ToolResult run(Call c) throws ConnectorException {
    CloudflareApi api = c.api();
    JsonNode result = api.send(c, "POST", api.uri("accounts", c.settings().accountId(), "workers", "observability",
        "telemetry", "query"), body(), "Workers Logs", false).result();
    ObjectNode s = Answers.envelope(c, view);
    s.put("from", range.from().toString());
    s.put("to", range.to().toString());
    List<String> notes = new ArrayList<>();
    ArrayNode items = Answers.F.arrayNode();
    if (view.equals("events")) {
      JsonNode events = result.path("events").path("events");
      String last = null;
      for (JsonNode e : events) {
        items.add(event(e));
        last = e.path("$metadata").path("id").asString(null);
      }
      if (last != null && events.size() >= limit) {
        s.put("nextCursor", last);
        notes.add("More events: call again with cursor set to nextCursor.");
      }
      if (items.isEmpty()) {
        notes.add("No events: check that Workers Logs is turned on for the Worker (observability.logs.enabled in "
            + "cloudflare_workers view settings), and widen from.");
      }
    } else {
      for (JsonNode calc : result.path("calculations")) {
        for (JsonNode agg : calc.path("aggregates")) {
          ObjectNode row = items.addObject();
          ObjectNode groups = row.putObject("groups");
          for (JsonNode gr : agg.path("groups")) {
            groups.set(gr.path("key").asString(""), gr.path("value").deepCopy());
          }
          row.set("count", agg.has("count") ? agg.get("count").deepCopy() : agg.path("value").deepCopy());
        }
      }
    }
    s.set("items", items);
    return Answers.answer(c, "Workers Logs · " + (script == null ? "all Workers" : script) + " · " + c.cfg().name(),
        s, notes);
  }

  /** One event, without Cloudflare's internal fields: when, level, message, the Worker, the request. */
  private static ObjectNode event(JsonNode e) {
    JsonNode m = e.path("$metadata");
    ObjectNode o = Answers.F.objectNode();
    if (e.path("timestamp").canConvertToLong()) {
      o.put("time", Instant.ofEpochMilli(e.path("timestamp").asLong()).toString());
    }
    for (String f : List.of("level", "message", "error", "service", "statusCode", "trigger", "url", "requestId",
        "rayId", "type", "origin", "id")) {
      if (m.hasNonNull(f)) {
        o.set(f, m.get(f).deepCopy());
      }
    }
    JsonNode w = e.path("$workers");
    for (String f : List.of("outcome", "eventType", "scriptName", "cpuTimeMs", "wallTimeMs", "truncated")) {
      if (w.hasNonNull(f)) {
        o.set(f, w.get(f).deepCopy());
      }
    }
    if (w.path("scriptVersion").path("id").isString()) {
      o.put("version", w.path("scriptVersion").path("id").asString());
    }
    JsonNode request = w.path("event").path("request");
    if (request.isObject()) {
      ObjectNode r = o.putObject("request");
      for (String f : List.of("method", "url")) {
        if (request.hasNonNull(f)) {
          r.set(f, request.get(f).deepCopy());
        }
      }
    }
    if (w.path("event").path("response").path("status").isNumber()) {
      o.set("responseStatus", w.path("event").path("response").path("status").deepCopy());
    }
    if (e.has("source") && !e.get("source").isNull()) {
      o.set("source", e.get("source").deepCopy());
    }
    return o;
  }
}
