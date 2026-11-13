package com.gaterook.core.mcp.internal;

import com.gaterook.connector.api.ToolDefinition.Mode;
import com.gaterook.connector.api.ToolResult;
import com.gaterook.core.audit.AuditUnavailableException;
import com.gaterook.core.audit.AuditWriter;
import com.gaterook.core.endpoints.CoreTools;
import com.gaterook.core.endpoints.EndpointSnapshot;
import com.gaterook.core.endpoints.ToolCatalog;
import com.gaterook.core.endpoints.ToolSearch;
import com.gaterook.core.invocation.ResultLimiter;
import com.gaterook.core.invocation.ToolGuard;
import com.gaterook.core.platform.Ids;
import com.gaterook.core.policy.PolicyEngine;
import com.gaterook.core.policy.PolicyService;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code find_tools} (ARCHITECTURE.md §5.7): the published tools of the endpoint that match a query
 * ({@link ToolSearch}), or those named, with what the model needs to run them: whether they only read, whether they
 * wait for a person, their connections, whether tools/list shows them, and for the best ones their input schema as
 * clients get it. It reads definitions only, what tools/list would show of them, so like tools/list it touches no
 * resource and masks nothing; rate limits and the audit treat it as any call of the gateway's own tools.
 */
@Component
public class ToolFinder {

  /** How many of a query's matches come with their input schema; the rest the model asks for by name. */
  static final int WITH_SCHEMA = 3;

  /** How much of a match's description a query's answer keeps; names get the whole of it. */
  static final int DESCRIPTION_CHARS = 600;

  private final PolicyEngine engine;
  private final PolicyService policies;
  private final ToolGuard guard;
  private final AuditWriter audit;
  private final Calls calls;

  public ToolFinder(PolicyEngine engine, PolicyService policies, ToolGuard guard, AuditWriter audit, Calls calls) {
    this.engine = engine;
    this.policies = policies;
    this.guard = guard;
    this.audit = audit;
    this.calls = calls;
  }

  public ObjectNode call(McpRequest r, ObjectNode args) {
    EndpointSnapshot s = r.snapshot();
    PolicyEngine.Rates rates = engine.rates(s.env());
    ToolGuard.Admission admission = guard.admit(r.caller().principalId(), CoreTools.FIND_TOOLS, rates.perToken(),
        rates.perTool());
    if (!admission.allowed()) {
      ObjectNode meta = JsonNodeFactory.instance.objectNode().put("ai.msrv.gaterook/retryAfterMs",
          admission.retryAfterMs());
      return ResultWriter.toolError(r.env(), "Too many calls on this token. Retry after "
          + Math.max(1, admission.retryAfterMs() / 1000) + " s.", meta);
    }
    String callId = Ids.next("tc_");
    AuditWriter.Call call = calls.call(r, callId, CoreTools.FIND_TOOLS, CoreTools.FIND_TOOLS, null, s.env(),
        CoreTools.findTools(), args, null);
    received(call);
    long started = System.nanoTime();
    ToolResult result = ResultLimiter.limit(find(r.catalog(), s, args), policies.limits(s.env()), 0);
    long bytes = ResultLimiter.size(result);
    result = result.accounted(result.content(), result.structuredContent(), result.table(), result.maskedCount(),
        bytes);
    long durationMs = (System.nanoTime() - started) / 1_000_000;
    try {
      audit.completed(call, new AuditWriter.Completion(result.isError() ? AuditWriter.Verdict.ERROR
          : AuditWriter.Verdict.ALLOWED, null, null, null, null, durationMs, durationMs, 0L, null, bytes,
          ResultLimiter.sha256(result), ResultLimiter.preview(result, 4_096), result.maskedCount(), result.truncated(),
          result.isError() ? "INVALID_ARGUMENTS" : null, result.isError() ? ResultLimiter.preview(result, 1_000) : null,
          null, null, null));
    } catch (AuditUnavailableException e) {
      throw new McpException(JsonRpc.INTERNAL_ERROR, "The audit couldn't record the result, so it is not shown.");
    }
    return ResultWriter.callToolResult(r.env(), result, callId, r.span().traceId(), null);
  }

  /** The answer: the matches of {@code query} or the tools {@code names} names. */
  static ToolResult find(ToolCatalog.Catalog catalog, EndpointSnapshot s, ObjectNode args) {
    String query = args.path("query").asString("").strip();
    JsonNode names = args.get("names");
    String connection = args.path("connection").isString() ? args.get("connection").asString() : null;
    int limit = args.path("limit").asInt(ToolSearch.DEFAULT_LIMIT);
    boolean byName = names != null && names.isArray() && !names.isEmpty();
    if (byName == !query.isEmpty()) {
      return ToolResult.error(byName ? "Give a query or names, not both."
          : "Give a query, a few words about what you want to do, or names of tools.");
    }
    List<ToolCatalog.PublishedTool> found = new ArrayList<>();
    List<String> unknown = new ArrayList<>();
    int total;
    if (byName) {
      for (JsonNode n : names) {
        String name = n.asString("");
        catalog.tool(name).filter(ToolSearch::searchable)
            .filter(t -> connection == null || t.pairs().stream().anyMatch(p -> p.connectionName().equals(connection)))
            .ifPresentOrElse(found::add, () -> unknown.add(name));
      }
      total = found.size();
    } else {
      ToolSearch.Result matches = catalog.search().search(query, connection, limit);
      matches.hits().forEach(h -> found.add(h.tool()));
      total = matches.total();
    }
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    if (!byName) {
      out.put("query", query);
    }
    if (connection != null) {
      out.put("connection", connection);
    }
    out.put("total", total);
    ArrayNode tools = out.putArray("tools");
    StringBuilder text = new StringBuilder();
    if (found.isEmpty()) {
      text.append(byName ? "No tool of " + s.slug() + " has these names." : "No tool of " + s.slug()
          + " matches \"" + query + "\"" + (connection == null ? "" : " on " + connection) + ".")
          .append(" Try other words, or list_connections for every connection and its tools.");
    } else {
      text.append(byName ? found.size() + (found.size() == 1 ? " tool" : " tools")
              : (total > found.size() ? "The best " + found.size() + " of " + total : String.valueOf(total))
                  + (total == 1 ? " tool matches" : " tools match") + " \"" + query + "\"")
          .append(". Run one that only reads with call_read_tool, any other with call_tool: "
              + "{\"name\": \"<tool>\", \"arguments\": {…}}.");
    }
    for (int i = 0; i < found.size(); i++) {
      ToolCatalog.PublishedTool t = found.get(i);
      boolean schema = byName || i < WITH_SCHEMA;
      String description = byName ? t.description() : cut(t.description(), DESCRIPTION_CHARS);
      boolean readOnly = ToolSearch.readOnly(t);
      String approval = approval(t);
      TreeSet<String> on = new TreeSet<>();
      t.pairs().forEach(p -> on.add(p.connectionName()));
      boolean listed = catalog.listed(t.name());
      ObjectNode entry = tools.addObject();
      entry.put("name", t.name());
      if (t.title() != null) {
        entry.put("title", t.title());
      }
      entry.put("description", description);
      entry.put("readOnly", readOnly);
      entry.put("approval", approval);
      on.forEach(entry.putArray("connections")::add);
      entry.put("listed", listed);
      if (schema) {
        entry.set("inputSchema", t.clientInputSchema());
      }
      text.append("\n\n").append(i + 1).append(". ").append(t.name());
      if (t.title() != null && !t.title().isBlank()) {
        text.append(" (").append(t.title()).append(')');
      }
      text.append(" · ").append(readOnly ? "reads" : "can change data").append(" · ").append(waits(t, approval))
          .append(" · ").append(String.join(", ", on)).append(listed ? " · listed: call it directly" : "")
          .append("\n   ").append(description.replace("\n", "\n   "));
      if (schema) {
        text.append("\n   inputSchema: ").append(t.clientInputSchema());
      }
    }
    if (!unknown.isEmpty()) {
      unknown.forEach(out.putArray("unknown")::add);
      text.append("\n\nNot on ").append(s.slug()).append(connection == null ? "" : " for " + connection).append(": ")
          .append(String.join(", ", unknown)).append('.');
    }
    if (!byName && total > found.size()) {
      text.append("\n\n").append(total - found.size()).append(" more match: raise limit, or narrow the query.");
    }
    return ToolResult.json(out, text.toString());
  }

  /** {@code required}: every connection waits for a person; {@code partial}: some do; {@code none}. */
  private static String approval(ToolCatalog.PublishedTool t) {
    long waiting = t.pairs().stream().filter(p -> p.mode() == Mode.APPROVAL).count();
    return waiting == 0 ? "none" : waiting == t.pairs().size() ? "required" : "partial";
  }

  private static String waits(ToolCatalog.PublishedTool t, String approval) {
    List<String> bypass = t.pairs().stream().filter(p -> p.mode() == Mode.BYPASS)
        .map(EndpointSnapshot.Pair::connectionName).sorted().toList();
    String out = switch (approval) {
      case "required" -> "waits for a person's approval";
      case "partial" -> "waits for approval on " + String.join(", ", t.pairs().stream()
          .filter(p -> p.mode() == Mode.APPROVAL).map(EndpointSnapshot.Pair::connectionName).sorted().toList());
      default -> "runs at once";
    };
    return bypass.isEmpty() ? out : out + "; bypass on " + String.join(", ", bypass)
        + ": runs without approval, including changes";
  }

  private static String cut(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max - 1).strip() + "…";
  }

  private void received(AuditWriter.Call call) {
    try {
      audit.received(call, AuditWriter.Received.of(AuditWriter.Verdict.ALLOWED, null, null));
    } catch (AuditUnavailableException e) {
      throw new McpException(JsonRpc.INTERNAL_ERROR, "The audit is unavailable, so nothing ran. Try again later.");
    }
  }
}
