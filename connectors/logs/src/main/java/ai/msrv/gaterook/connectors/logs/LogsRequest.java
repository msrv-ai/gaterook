package ai.msrv.gaterook.connectors.logs;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connectors.support.TimeArg;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One {@code logs_search} or {@code logs_tail} call, checked against the connection and the environment without any
 * I/O (report 07 §3.5): the same checks run in {@code classify} (where a failure is a DENY with its rule) and again
 * in {@code execute}. Limits above the policy are lowered, not refused; the answer says so.
 *
 * @param from   search: the oldest time asked for; tail: the start of the first call ({@code since})
 * @param to     search: the newest time; tail: unused ({@code null})
 * @param waitFor tail: how long the call may wait for new entries
 * @param notes  what the gateway changed about the call, for the answer's text
 */
record LogsRequest(Tool tool, String query, String source, Instant from, Instant to, int limit, String cursor,
    Duration waitFor, List<String> notes) {

  /** The two tools. */
  enum Tool {
    SEARCH("logs_search"), TAIL("logs_tail");

    final String id;

    Tool(String id) {
      this.id = id;
    }

    static Tool of(String name) {
      for (Tool t : values()) {
        if (t.id.equals(name)) {
          return t;
        }
      }
      return null;
    }
  }

  /** A call the gateway refuses before it reaches the backend: becomes {@code DENY <env>.logs.<rule>}. */
  static final class Refusal extends Exception {
    private static final long serialVersionUID = 1L;
    final String rule;
    final String hint;

    Refusal(String rule, String reason, String hint) {
      super(reason, null, false, false);
      this.rule = rule;
      this.hint = hint;
    }
  }

  /** What the policy decided for one call, carried from {@code classify} to {@code execute} of the same call. */
  record PolicyLimits(int defaultLimit, int maxWaitSeconds) {
    static PolicyLimits defaults(Env env) {
      return env == Env.PROD ? new PolicyLimits(50, 3) : new PolicyLimits(100, 8);
    }
  }

  /** Entries per call, whatever the policy says: the tools' schemas promise no more. */
  static final int MAX_LIMIT = 1_000;
  /** {@code wait} of {@code logs_tail}, whatever the policy says. */
  static final int MAX_WAIT_SECONDS = 8;
  /** Filter patterns of {@code FilterLogEvents} (CloudWatch tail and filter search). */
  static final int MAX_FILTER_PATTERN = 1_024;
  /** {@code StartQuery.queryString}, including what the gateway adds. */
  static final int MAX_INSIGHTS_QUERY = 10_000;
  /** {@code entries.list} filters, including the scope filter and the time range. */
  static final int MAX_GCP_FILTER = 20_000;
  /** Queries of the schema, every backend. */
  static final int MAX_QUERY = 20_000;

  /**
   * Checks a call.
   *
   * @param limits the call's limits, or {@code null} where none are known yet (the approval preview): then neither
   *               the range nor the line limit is checked
   * @param policy the environment's own defaults and caps of the Logs section
   */
  static LogsRequest of(ToolCall call, Settings s, Env env, Limits limits, PolicyLimits policy, Instant now)
      throws Refusal {
    Tool tool = Tool.of(call.tool());
    if (tool == null) {
      throw new Refusal("unknown_tool", "The Logs connector has no tool " + call.tool() + ".", null);
    }
    return check(tool, call.arguments(), s, env, limits, policy, now);
  }

  /** The approved form of a call: what {@link #payload} stored, absolute times included. */
  static LogsRequest ofPayload(JsonNode payload, Settings s, Env env, Limits limits, PolicyLimits policy, Instant now)
      throws Refusal {
    Tool tool = Tool.of(payload.path("tool").asString(""));
    if (tool == null) {
      throw new Refusal("unknown_tool", "The approved call names no Logs tool.", null);
    }
    return check(tool, payload.path("arguments"), s, env, limits, policy, now);
  }

  private static LogsRequest check(Tool tool, JsonNode args, Settings s, Env env, Limits limits, PolicyLimits policy,
      Instant now) throws Refusal {
    String query = string(args, "query");
    if (query == null) {
      query = "";
    }
    String source = string(args, "source");
    if (source != null && source.isBlank()) {
      source = null;
    }
    String cursor = string(args, "cursor");
    if (cursor != null && cursor.isBlank()) {
      cursor = null;
    }
    List<String> notes = new ArrayList<>();
    checkSource(s, source);
    checkQuery(tool, s, query);

    Instant from;
    Instant to = null;
    Duration wait = null;
    Duration maxRange = limits == null ? null : limits.maxRange();
    try {
      if (tool == Tool.SEARCH) {
        TimeArg.Range range = TimeArg.range(stringOr(args, "from", "-1h"), stringOr(args, "to", "now"), now,
            maxRange);
        from = range.from();
        to = range.to();
      } else {
        from = TimeArg.parse(stringOr(args, "since", "now"), now);
        if (from.isAfter(now)) {
          throw new TimeArg.InvalidTimeException("since is in the future (" + from + "): use now or a time before.");
        }
        if (maxRange != null && Duration.between(from, now).compareTo(maxRange) > 0) {
          throw new TimeArg.InvalidTimeException("since goes back further than this environment allows ("
              + maxRange.toHours() + " h). Use a later time, or a search call for older entries.");
        }
      }
    } catch (TimeArg.InvalidTimeException e) {
      throw new Refusal("range", e.getMessage(), "Write times as -1h, now-30m or 2026-10-22T14:00:00+02:00.");
    }
    if (s instanceof Settings.Loki && maxRange != null) {
      // A metric query reads its range vector behind every point of the window: [30d] reads 30 days before from.
      Optional<LogQl.Range> longest = LogQl.longestRange(query);
      if (longest.isPresent() && longest.get().length().compareTo(maxRange) > 0) {
        throw new Refusal("range", "The range [" + longest.get().text() + "] reads further back than this "
            + "environment allows (" + maxRange.toHours() + " h).", "Use a shorter range, like [5m] or [1h].");
      }
    }

    String envName = env.id();
    int limit = args.has("limit") && args.get("limit").isNumber() ? args.get("limit").asInt() : policy.defaultLimit();
    if (limit < 1) {
      limit = 1;
    }
    int cap = Math.min(MAX_LIMIT, limits == null ? MAX_LIMIT : Math.max(1, limits.maxLines()));
    if (limit > cap) {
      notes.add("limit lowered to " + cap + " by the " + envName + " policy");
      limit = cap;
    }
    if (tool == Tool.TAIL) {
      int asked = args.has("wait") && args.get("wait").isNumber() ? args.get("wait").asInt() : 5;
      int maxWait = Math.min(MAX_WAIT_SECONDS, policy.maxWaitSeconds());
      if (limits != null) {
        // The answer must leave the gateway before the tool timeout: two seconds for the last poll and the answer.
        maxWait = (int) Math.min(maxWait, Math.max(0, limits.toolTimeout().toSeconds() - 2));
      }
      int seconds = Math.max(0, asked);
      if (seconds > maxWait) {
        notes.add("wait lowered to " + maxWait + " s by the " + envName + " policy");
        seconds = maxWait;
      }
      wait = Duration.ofSeconds(seconds);
    }
    return new LogsRequest(tool, query, source, from, to, limit, cursor, wait, List.copyOf(notes));
  }

  private static void checkSource(Settings s, String source) throws Refusal {
    if (source == null) {
      return;
    }
    if (s.sources().isEmpty()) {
      throw new Refusal("source_not_allowed", "Loki connections have no sources: select streams in the query.",
          "Pick streams with labels, like {app=\"api\"}.");
    }
    if (!s.sources().contains(source)) {
      String what = switch (s) {
        case Settings.CloudWatch c -> "log groups";
        case Settings.OpenSearch o -> "index patterns";
        default -> "resources";
      };
      throw new Refusal("source_not_allowed", source + " isn't one of the " + what + " this connection may read.",
          "Use one of: " + String.join(", ", s.sources()) + " (describe_connection lists them).");
    }
  }

  private static void checkQuery(Tool tool, Settings s, String query) throws Refusal {
    if (query.length() > MAX_QUERY) {
      throw new Refusal("query_too_long", "The query has " + query.length() + " characters; at most " + MAX_QUERY
          + " are accepted.", null);
    }
    switch (s) {
      case Settings.CloudWatch c -> {
        boolean pattern = tool == Tool.TAIL || !c.insights();
        if (pattern && query.length() > MAX_FILTER_PATTERN) {
          throw new Refusal("query_too_long", "CloudWatch filter patterns are at most " + MAX_FILTER_PATTERN
              + " characters; this one has " + query.length() + ".", null);
        }
        if (!pattern) {
          Optional<InsightsQuery.Reach> reach = InsightsQuery.outside(query);
          if (reach.isPresent()) {
            throw switch (reach.get()) {
              case SOURCE -> new Refusal(reach.get().rule, "The SOURCE command (also in join, appendcols and "
                  + "subqueries) picks log groups inside the query; this connection reads only its own log groups.",
                  "Pick a log group with source instead. A field called source: write it as `source`.");
              case LOOKUP -> new Refusal(reach.get().rule, "The lookup command reads a lookup table of the account, "
                  + "which this connection doesn't give agents.", "Search the connection's log groups without lookup.");
              case UNMASK -> new Refusal(reach.get().rule, "unmask shows what the log group's data protection policy "
                  + "masks; GateRook reads log events masked.", null);
            };
          }
          String effective = CloudWatchBackend.insightsQuery(query);
          if (effective.length() > MAX_INSIGHTS_QUERY) {
            throw new Refusal("query_too_long", "Logs Insights queries are at most " + MAX_INSIGHTS_QUERY
                + " characters, with what GateRook adds (" + effective.length() + ").", null);
          }
        }
      }
      case Settings.OpenSearch o -> {
        if (query.stripLeading().startsWith("{")) {
          throw new Refusal("os_dsl", "JSON Query DSL isn't accepted: a terms lookup can read indices outside this "
              + "connection.", "Use Lucene syntax: log.level:error AND message:timeout");
        }
      }
      case Settings.Gcp g -> {
        int length = query.length() + (g.scopeFilter() == null ? 0 : g.scopeFilter().length()) + 120;
        if (length > MAX_GCP_FILTER) {
          throw new Refusal("query_too_long", "Cloud Logging filters are at most " + MAX_GCP_FILTER
              + " characters, with the connection's scope filter and the time range.", null);
        }
        try {
          GcpLoggingFilter.expression(query);
        } catch (IllegalArgumentException e) {
          throw new Refusal("gcp_filter", e.getMessage(), "Close every quoted string and match the parentheses.");
        }
      }
      case Settings.Loki l -> {
        // LogQL is checked by Loki itself; its parse errors reach the agent as they are.
      }
    }
  }

  /** The canonical form of the call that an approval stores and executes: absolute times, nothing relative. */
  ObjectNode payload() {
    ObjectNode p = JsonNodeFactory.instance.objectNode();
    p.put("tool", tool.id);
    ObjectNode a = p.putObject("arguments");
    a.put("query", query);
    if (source != null) {
      a.put("source", source);
    }
    if (tool == Tool.SEARCH) {
      a.put("from", from.toString());
      a.put("to", to.toString());
    } else {
      a.put("since", from.toString());
      a.put("wait", waitFor.toSeconds());
    }
    a.put("limit", limit);
    if (cursor != null) {
      a.put("cursor", cursor);
    }
    return p;
  }

  private static String string(JsonNode args, String field) {
    JsonNode n = args.get(field);
    return n == null || n.isNull() ? null : n.asString();
  }

  private static String stringOr(JsonNode args, String field, String dflt) {
    String v = string(args, field);
    return v == null || v.isBlank() ? dflt : v.strip().toLowerCase(Locale.ROOT).equals("now") ? "now" : v.strip();
  }
}
