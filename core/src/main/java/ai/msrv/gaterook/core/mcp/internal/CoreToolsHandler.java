package ai.msrv.gaterook.core.mcp.internal;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.core.audit.AuditUnavailableException;
import ai.msrv.gaterook.core.audit.AuditWriter;
import ai.msrv.gaterook.core.endpoints.CoreTools;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.endpoints.ToolCatalog;
import ai.msrv.gaterook.core.invocation.ConnectorInvoker;
import ai.msrv.gaterook.core.invocation.ResultLimiter;
import ai.msrv.gaterook.core.invocation.ToolGuard;
import ai.msrv.gaterook.core.platform.Ids;
import ai.msrv.gaterook.core.platform.MaskPolicy;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.Taint;
import ai.msrv.gaterook.core.policy.PolicyEngine;
import ai.msrv.gaterook.core.policy.PolicyService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The core's own tools {@code list_connections} and {@code describe_connection} (ARCHITECTURE.md §3, report 01 §3.5):
 * READ, no connector {@code execute}; audited like any call. What they show is what an agent may know — names,
 * types, environments, modes, limits, the config fields the connector marks {@code x-agent-visible}, whether a secret
 * is stored — never a host's credentials or a secret reference. {@code describe_connection} adds the connector's own
 * {@code describe} (schemas, collections, log groups; ≤ 2 s) through the invoker, masked like every result. The
 * whole answer then goes through the {@link Masker} with the environment's policy (§9: before anything is serialized
 * for the agent), so the masking policy's own fields and personal data rules reach the visible settings and the
 * manifest's texts too; only the core's constant {@code credentials} block ("stored") is added after that pass. In
 * Approval mode they are refused: nothing about a core tool waits for a person. Like every connector call they are
 * refused ({@code core.integrity}) while the policy of the endpoint's environment, or of the described connection's,
 * didn't verify.
 */
@Component
public class CoreToolsHandler {

  private final PolicyEngine engine;
  private final PolicyService policies;
  private final ToolGuard guard;
  private final AuditWriter audit;
  private final ConnectorInvoker invoker;
  private final Masker masker;
  private final Calls calls;

  public CoreToolsHandler(PolicyEngine engine, PolicyService policies, ToolGuard guard, AuditWriter audit,
      ConnectorInvoker invoker, Masker masker, Calls calls) {
    this.engine = engine;
    this.policies = policies;
    this.guard = guard;
    this.audit = audit;
    this.invoker = invoker;
    this.masker = masker;
    this.calls = calls;
  }

  public ObjectNode call(McpRequest r, ToolCatalog.PublishedTool tool, ObjectNode args) {
    EndpointSnapshot s = r.snapshot();
    PolicyEngine.Rates rates = engine.rates(s.env());
    ToolGuard.Admission admission = guard.admit(r.caller().principalId(), tool.name(), rates.perToken(),
        rates.perTool());
    if (!admission.allowed()) {
      ObjectNode meta = JsonNodeFactory.instance.objectNode().put("ai.msrv.gaterook/retryAfterMs",
          admission.retryAfterMs());
      return ResultWriter.toolError(r.env(), "Too many calls on this token. Retry after "
          + Math.max(1, admission.retryAfterMs() / 1000) + " s.", meta);
    }
    String callId = Ids.next("tc_");
    String connection = args.path("connection").asString(null);
    Mode mode = s.coreTool(tool.name()).map(EndpointSnapshot.CorePair::mode).orElse(Mode.OFF);
    EndpointSnapshot.Wired wired = connection == null ? null : s.connection(connection).filter(
        EndpointSnapshot.Wired::usable).orElse(null);
    AuditWriter.Call call = calls.call(r, callId, tool.name(), tool.name(), connection,
        wired == null ? s.env() : wired.config().env(), CoreTools.withMode(tool.name()), args, null);
    if (mode == Mode.APPROVAL) {
      received(call, AuditWriter.Received.of(AuditWriter.Verdict.DENIED, null, s.env().id() + ".core.approval-mode"));
      return ResultWriter.callToolResult(r.env(), ToolResult.error(tool.name() + " is set to Approval on " + s.slug()
          + ", and the gateway's own tools don't wait for a person. Ask the user to set it to Read only."), callId,
          r.span().traceId(), null);
    }
    // The same first gate as every connector call (§5 step 7): without a verified policy there are no trusted masking
    // rules or limits, and describe_connection would hand the connection's secrets to its connector on defaults.
    Env unverified = engine.unverified(s.env(), wired == null ? null : wired.config().env());
    if (unverified != null) {
      received(call, AuditWriter.Received.of(AuditWriter.Verdict.DENIED, null, PolicyEngine.INTEGRITY_RULE));
      r.span().error("denied");
      return ResultWriter.callToolResult(r.env(), ToolResult.error("Denied by policy " + PolicyEngine.INTEGRITY_RULE
          + ": " + PolicyEngine.integrityReason(unverified)), callId, r.span().traceId(), null);
    }
    received(call, AuditWriter.Received.of(AuditWriter.Verdict.ALLOWED, null, null));
    long started = System.nanoTime();
    ToolResult result;
    long resourceMs = 0;
    if (CoreTools.LIST_CONNECTIONS.equals(tool.name())) {
      result = mask(list(s), policies.maskPolicy(s.env()), 0);
    } else {
      if (wired == null) {
        result = ToolResult.error("Connection " + connection + " is not available on " + s.slug() + ".");
      } else {
        ConnectorInvoker.Outcome described = describe(r, s, wired, args.path("object").asString(null), callId);
        resourceMs = described.resourceMs();
        result = describeResult(s, wired, described, policies.maskPolicy(wired.config().env()));
      }
    }
    Limits limits = policies.limits(s.env());
    result = ResultLimiter.limit(result, limits, resourceMs);
    long bytes = ResultLimiter.size(result);
    result = result.accounted(result.content(), result.structuredContent(), result.table(), result.maskedCount(),
        bytes);
    long durationMs = (System.nanoTime() - started) / 1_000_000;
    try {
      audit.completed(call, new AuditWriter.Completion(result.isError() ? AuditWriter.Verdict.ERROR
          : AuditWriter.Verdict.ALLOWED, null, null, null, null, durationMs, Math.max(0, durationMs - resourceMs),
          resourceMs, null, bytes, ResultLimiter.sha256(result), ResultLimiter.preview(result, 4_096),
          result.maskedCount(), result.truncated(), result.isError() ? "RESOURCE_ERROR" : null,
          result.isError() ? ResultLimiter.preview(result, 1_000) : null, null, null, null));
    } catch (AuditUnavailableException e) {
      throw new McpException(JsonRpc.INTERNAL_ERROR, "The audit couldn't record the result, so it is not shown.");
    }
    return ResultWriter.callToolResult(r.env(), result, callId, r.span().traceId(), null);
  }

  private static ToolResult list(EndpointSnapshot s) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    ArrayNode list = out.putArray("connections");
    StringBuilder text = new StringBuilder();
    for (EndpointSnapshot.Wired w : s.connections()) {
      if (!w.usable()) {
        continue;
      }
      ObjectNode c = list.addObject();
      c.put("name", w.config().name());
      c.put("type", w.config().type());
      c.put("typeName", w.manifest().name());
      c.put("env", w.config().env().id());
      c.put("description", w.manifest().description());
      ArrayNode tools = c.putArray("tools");
      List<String> names = new ArrayList<>();
      for (EndpointSnapshot.Pair p : s.pairs()) {
        if (p.config().id().equals(w.config().id()) && p.on()) {
          tools.addObject().put("name", p.tool().name()).put("mode", mode(p.mode()));
          names.add(p.tool().name() + (p.mode() == Mode.APPROVAL ? " (approval)" : ""));
        }
      }
      text.append(w.config().name()).append(" · ").append(w.manifest().name()).append(" · ")
          .append(w.config().env().id()).append(": ").append(names.isEmpty() ? "no tools" : String.join(", ", names))
          .append('\n');
    }
    return ToolResult.json(out, text.isEmpty() ? "No connections on " + s.slug() + "." : text.toString().strip());
  }

  private ConnectorInvoker.Outcome describe(McpRequest r, EndpointSnapshot s, EndpointSnapshot.Wired w,
      String object, String callId) {
    EndpointSnapshot.Pair any = s.pairs().stream().filter(p -> p.config().id().equals(w.config().id())).findFirst()
        .orElse(null);
    Limits limits = any == null ? policies.limits(w.config().env(), w.config().type())
        : engine.view(any.view(), w.config()).limits();
    ToolCall call = new ToolCall(callId, CoreTools.DESCRIBE_CONNECTION, w.config().name(),
        JsonNodeFactory.instance.objectNode(), null);
    return invoker.describe(new ConnectorInvoker.Request(callId, w.config(), call, limits,
        policies.maskPolicy(w.config().env()), r.span().traceId(), r.span().traceparent()), object);
  }

  /**
   * The answer through the masker; {@code alreadyMasked} counts what an earlier pass (the connector's describe, masked
   * by the invoker with the call's taint) masked.
   */
  private ToolResult mask(ToolResult result, MaskPolicy policy, int alreadyMasked) {
    Masker.MaskedResult masked = masker.result(result, new Taint(), policy);
    ToolResult r = masked.result();
    return r.accounted(r.content(), r.structuredContent(), r.table(), alreadyMasked + masked.outcome().fields(),
        r.resultBytes());
  }

  private ToolResult describeResult(EndpointSnapshot s, EndpointSnapshot.Wired w, ConnectorInvoker.Outcome d,
      MaskPolicy policy) {
    ConnectionConfig cfg = w.config();
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    out.put("name", cfg.name());
    out.put("type", cfg.type());
    out.put("typeName", w.manifest().name());
    out.put("env", cfg.env().id());
    out.put("description", w.manifest().description());
    ObjectNode visible = out.putObject("settings");
    if (w.manifest().configSchema() != null) {
      collectVisible(w.manifest().configSchema(), "", cfg, visible);
    }
    EndpointSnapshot.Pair any = s.pairs().stream().filter(p -> p.config().id().equals(cfg.id())).findFirst()
        .orElse(null);
    Limits l = any == null ? policies.limits(cfg.env(), cfg.type()) : engine.view(any.view(), cfg).limits();
    ObjectNode limits = out.putObject("limits");
    limits.put("maxRows", l.maxRows());
    limits.put("maxLines", l.maxLines());
    limits.put("maxResponseKb", l.maxResponseBytes() / 1024);
    limits.put("statementTimeoutMs", l.statementTimeout().toMillis());
    limits.put("toolTimeoutSeconds", l.toolTimeout().toSeconds());
    ArrayNode tools = out.putArray("tools");
    for (EndpointSnapshot.Pair p : s.pairs()) {
      if (p.config().id().equals(cfg.id()) && p.on()) {
        tools.addObject().put("name", p.tool().name()).put("mode", mode(p.mode()));
      }
    }
    if (d.ok() && d.result().structuredContent() != null) {
      out.set("details", d.result().structuredContent());
    } else if (!d.ok()) {
      out.put("detailsError", d.errorMessage());
    }
    String text = cfg.name() + " · " + w.manifest().name() + " · " + cfg.env().id() + ". Max " + l.maxRows()
        + " rows, " + l.toolTimeout().toSeconds() + " s per call. Credentials are stored in GateRook and never "
        + "shown.";
    ToolResult masked = mask(ToolResult.json(out, text), policy, d.maskedCount());
    // The core's own constant: every key names a secret field, so a masking pass would hide that it is stored.
    ObjectNode structured = (ObjectNode) masked.structuredContent();
    ObjectNode secrets = structured.putObject("credentials");
    for (String field : cfg.secretFields()) {
      secrets.put(field, "stored");
    }
    return masked;
  }

  /** Config values whose schema says {@code x-agent-visible: true} (a database name, a region; never a host secret). */
  private static void collectVisible(JsonNode schema, String pointer, ConnectionConfig cfg, ObjectNode out) {
    for (Map.Entry<String, JsonNode> p : schema.path("properties").properties()) {
      String child = pointer + "/" + p.getKey();
      JsonNode def = p.getValue();
      if (def.path("x-agent-visible").asBoolean(false) && !def.path("writeOnly").asBoolean(false)) {
        JsonNode value = cfg.config().at(child);
        if (!value.isMissingNode()) {
          out.set(child.substring(1).replace('/', '.'), value);
        }
      }
      if (def.path("properties").isObject()) {
        collectVisible(def, child, cfg, out);
      }
    }
  }

  private static String mode(Mode m) {
    return switch (m) {
      case READ_ONLY -> "read-only";
      case APPROVAL -> "approval";
      case BYPASS -> "bypass";
      case OFF -> "off";
    };
  }

  private void received(AuditWriter.Call call, AuditWriter.Received r) {
    try {
      audit.received(call, r);
    } catch (AuditUnavailableException e) {
      throw new McpException(JsonRpc.INTERNAL_ERROR, "The audit is unavailable, so nothing ran. Try again later.");
    }
  }
}
