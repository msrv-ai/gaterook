package ai.msrv.gaterook.core.mcp.internal;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.core.approvals.ApprovalAnswers;
import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.core.approvals.ApprovalStatusTool;
import ai.msrv.gaterook.core.audit.AuditUnavailableException;
import ai.msrv.gaterook.core.audit.AuditWriter;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.endpoints.CoreTools;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.endpoints.ToolCatalog;
import ai.msrv.gaterook.core.invocation.ConnectorInvoker;
import ai.msrv.gaterook.core.invocation.ToolGuard;
import ai.msrv.gaterook.core.platform.Ids;
import ai.msrv.gaterook.core.platform.SchemaValidator;
import ai.msrv.gaterook.core.policy.Decision;
import ai.msrv.gaterook.core.policy.PolicyEngine;
import ai.msrv.gaterook.core.policy.PolicyService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code tools/call} (ARCHITECTURE.md §5 steps 5–11): find the tool, validate the arguments, resolve the connection,
 * rate limits, classify, decide, audit {@code received} (fail-closed), then deny, run, or ask a person. A READ result
 * reaches the agent only after its {@code completed} row; if that row can't be written the agent gets {@code -32603}.
 * A retry of an approval round with a valid {@code requestState} skips straight to the request it names. A tool that
 * publishes an {@code outputSchema} gets every answer about a request in a shape that keeps to it
 * ({@link ApprovalAnswers#typed}): the executed result once it ran, {@code isError} until then.
 */
@Component
public class ToolCallHandler {

  private static final Logger log = LoggerFactory.getLogger(ToolCallHandler.class);

  /** {@code -32603} when a call's {@code received} row can't be written (§5 step 8). */
  static final String AUDIT_UNAVAILABLE = "The audit is unavailable, so nothing ran. Try again later.";

  private final ToolCatalog catalogs;
  private final SchemaValidator validator;
  private final ConnectorRegistry registry;
  private final PolicyEngine engine;
  private final PolicyService policies;
  private final ToolGuard guard;
  private final AuditWriter audit;
  private final ConnectorInvoker invoker;
  private final ApprovalService approvals;
  private final ApprovalAnswers answers;
  private final ApprovalStatusTool statusTool;
  private final ApprovalDelivery delivery;
  private final RequestStateCodec states;
  private final CoreToolsHandler coreTools;
  private final Calls calls;
  private final ApprovalCards approvalCards;

  public ToolCallHandler(ToolCatalog catalogs, SchemaValidator validator, ConnectorRegistry registry,
      PolicyEngine engine, PolicyService policies, ToolGuard guard, AuditWriter audit, ConnectorInvoker invoker,
      ApprovalService approvals, ApprovalAnswers answers, ApprovalStatusTool statusTool, ApprovalDelivery delivery,
      RequestStateCodec states, CoreToolsHandler coreTools, Calls calls, ApprovalCards approvalCards) {
    this.catalogs = catalogs;
    this.validator = validator;
    this.registry = registry;
    this.engine = engine;
    this.policies = policies;
    this.guard = guard;
    this.audit = audit;
    this.invoker = invoker;
    this.approvals = approvals;
    this.answers = answers;
    this.statusTool = statusTool;
    this.delivery = delivery;
    this.states = states;
    this.coreTools = coreTools;
    this.calls = calls;
    this.approvalCards = approvalCards;
  }

  public ObjectNode call(McpRequest r) {
    Envelope env = r.env();
    JsonNode nameNode = env.params().get("name");
    if (nameNode == null || !nameNode.isString() || nameNode.asString().isEmpty()) {
      throw McpException.invalidParams("params.name is required");
    }
    String name = nameNode.asString();
    JsonNode argsNode = env.params().get("arguments");
    if (argsNode != null && !argsNode.isObject() && !argsNode.isNull()) {
      throw McpException.invalidParams("params.arguments must be an object");
    }
    ObjectNode args = argsNode != null && argsNode.isObject() ? (ObjectNode) argsNode
        : JsonNodeFactory.instance.objectNode();
    r.span().tag("gen_ai.tool.name", name);

    if (CoreTools.APPROVAL_STATUS.equals(name)) {
      return approvalStatus(r, args);
    }
    ToolCatalog.PublishedTool published = r.catalog().tool(name)
        .orElseThrow(() -> McpException.invalidParams("Unknown tool: " + name));
    List<SchemaValidator.Violation> violations = validator.validateBounded(published.inputSchema(), args,
        SchemaValidator.BUDGET);
    if (!violations.isEmpty()) {
      return ResultWriter.toolError(env, invalid(violations), null);
    }
    if (CoreTools.APPROVAL_CARD.equals(name) || CoreTools.APPROVAL_CARD_ACTION.equals(name)) {
      PolicyEngine.Rates rates = engine.rates(r.snapshot().env());
      ToolGuard.Admission admission = guard.admit(r.caller().principalId(), name, rates.perToken(), rates.perTool());
      if (!admission.allowed()) {
        return rateLimited(r, name, admission, "tool".equals(admission.scope()) ? rates.perTool() : rates.perToken());
      }
      return ResultWriter.callToolResult(env,
          approvalCards.call(r, args, CoreTools.APPROVAL_CARD_ACTION.equals(name)), null, r.span().traceId(), null);
    }
    if (published.core()) {
      return coreTools.call(r, published, args);
    }
    String connection = args.path("connection").isString() ? args.get("connection").asString() : null;
    Optional<EndpointSnapshot.Pair> found = catalogs.resolve(r.catalog(), name, connection);
    if (found.isEmpty()) {
      TreeSet<String> available = new TreeSet<>();
      published.pairs().forEach(p -> available.add(p.connectionName()));
      return ResultWriter.toolError(env, "Connection " + connection + " is not available on " + r.snapshot().slug()
          + ". Available: " + String.join(", ", available) + ".", null);
    }
    EndpointSnapshot.Pair pair = found.get();
    ConnectionConfig cfg = pair.config();
    String argumentsDigest = RequestStateCodec.argumentsDigest(name, args);

    // A retry of an approval round: the state proves who, where, what and with which arguments.
    JsonNode requestState = env.params().get("requestState");
    if (env.era() == Era.MODERN && requestState != null && requestState.isString()) {
      Optional<ApprovalDelivery.Answer> resumed = states.open(requestState.asString(), r.caller().principalId(),
          r.snapshot().id(), "tools/call", name, argumentsDigest)
          .flatMap(state -> delivery.resume(r, state, env.params().get("inputResponses"), argumentsDigest));
      if (resumed.isPresent()) {
        return answer(r, resumed.get(), null, published.outputSchema());
      }
    }

    PolicyEngine.Rates rates = engine.rates(pair.view(), cfg);
    ToolGuard.Admission admission = guard.admit(r.caller().principalId(), name, rates.perToken(), rates.perTool());
    if (!admission.allowed()) {
      return rateLimited(r, name, admission, "tool".equals(admission.scope()) ? rates.perTool() : rates.perToken());
    }
    Optional<Connector> connector = registry.connectorFor(cfg);
    if (connector.isEmpty()) {
      return ResultWriter.toolError(env, cfg.name() + " isn't available right now.", null);
    }
    String callId = Ids.next("tc_");
    ObjectNode arguments = args.deepCopy();
    arguments.remove("connection");
    ToolCall call = new ToolCall(callId, name, cfg.name(), arguments, pair.template());
    Classification classification;
    try {
      classification = connector.get().classify(call, cfg, engine.view(pair.view(), cfg));
    } catch (RuntimeException e) {
      log.warn("classify of {} on {} failed: {}", name, cfg.name(), e.toString());
      classification = null;
    }
    Decision decision = engine.decide(pair.view(), cfg, classification);
    Classification.Kind kind = classification == null ? null : classification.kind();
    ObjectNode facets = facets(pair, args);
    facets.set("policy", calls.policyFacet(cfg.env(), pair.mode(), classification, decision, rates, admission));
    AuditWriter.Call auditCall = calls.call(r, callId, name, pair.toolKey(), cfg.name(), cfg.env(), pair.tool(), args,
        facets);
    switch (decision.outcome()) {
      case UNKNOWN_TOOL -> throw McpException.invalidParams("Unknown tool: " + name);
      case DENY -> {
        received(auditCall, AuditWriter.Received.of(AuditWriter.Verdict.DENIED, kind, decision.ruleId()));
        r.span().error("denied");
        String text = "Denied by policy " + decision.ruleId() + ": " + decision.reason()
            + (decision.hint() == null ? "" : " " + decision.hint());
        return ResultWriter.callToolResult(env, ToolResult.error(text), callId, r.span().traceId(), null);
      }
      case ALLOW -> {
        if (pair.mode() == Mode.BYPASS && (kind == Classification.Kind.MUTATION
            || pair.tool().nature() != ToolDefinition.Nature.READ)) {
          return approval(r, pair, call, decision, kind, auditCall, args, argumentsDigest,
              published.outputSchema());
        }
        received(auditCall, AuditWriter.Received.of(AuditWriter.Verdict.ALLOWED, kind, decision.ruleId()));
        return run(r, auditCall, new ConnectorInvoker.Request(callId, cfg, call, decision.limits(),
            policies.maskPolicy(cfg.env()), r.span().traceId(), r.span().traceparent()), null);
      }
      default -> {
        return approval(r, pair, call, decision, kind, auditCall, args, argumentsDigest, published.outputSchema());
      }
    }
  }

  /** Runs an allowed call and writes its {@code completed} row before the result leaves. */
  ObjectNode run(McpRequest r, AuditWriter.Call auditCall, ConnectorInvoker.Request request, ObjectNode extraMeta) {
    return run(r, auditCall, request, extraMeta, null);
  }

  private ObjectNode run(McpRequest r, AuditWriter.Call auditCall, ConnectorInvoker.Request request,
      ObjectNode extraMeta, PreparedAction bypass) {
    long started = System.nanoTime();
    ConnectorInvoker.Outcome outcome = bypass == null ? invoker.execute(request)
        : invoker.executeBypass(request, bypass);
    long durationMs = (System.nanoTime() - started) / 1_000_000;
    completed(auditCall, outcome, durationMs, outcome.ok() ? AuditWriter.Verdict.ALLOWED : AuditWriter.Verdict.ERROR,
        null);
    if (!outcome.ok()) {
      r.span().error(outcome.errorKind().name());
    }
    return ResultWriter.callToolResult(r.env(), outcome.result(), request.callId(), r.span().traceId(), extraMeta);
  }

  /**
   * Prepare an exact action, then create an approval request or execute a policy-allowed Bypass call.
   *
   * @param outputSchema what the published tool promises its results look like, or {@code null}
   */
  private ObjectNode approval(McpRequest r, EndpointSnapshot.Pair pair, ToolCall call, Decision decision,
      Classification.Kind kind, AuditWriter.Call auditCall, ObjectNode args, String argumentsDigest,
      JsonNode outputSchema) {
    Envelope env = r.env();
    ConnectionConfig cfg = pair.config();
    ConnectorInvoker.Request request = new ConnectorInvoker.Request(call.callId(), cfg, call, decision.limits(),
        policies.maskPolicy(cfg.env()), r.span().traceId(), r.span().traceparent());
    ConnectorInvoker.Prepared prepared = invoker.prepare(request);
    if (!prepared.ok()) {
      received(auditCall, new AuditWriter.Received(AuditWriter.Verdict.ERROR, kind, decision.ruleId(), null, null,
          prepared.errorKind().name(), prepared.errorMessage()));
      return ResultWriter.callToolResult(env, ToolResult.error(prepared.errorMessage()), call.callId(),
          r.span().traceId(), null);
    }
    PreparedAction action = prepared.action();
    if (action.outcome() == PreparedAction.Outcome.REJECT) {
      String why = action.message() == null ? "The change can't be made as asked." : action.message();
      received(auditCall, new AuditWriter.Received(AuditWriter.Verdict.DENIED, kind, decision.ruleId(), null, null,
          "REJECTED", why));
      return ResultWriter.callToolResult(env, ToolResult.error(why), call.callId(), r.span().traceId(), null);
    }
    if (action.outcome() == PreparedAction.Outcome.NO_CHANGE) {
      String why = action.message() == null ? "Unchanged: nothing to do." : action.message();
      received(auditCall, AuditWriter.Received.of(AuditWriter.Verdict.ALLOWED, kind, null));
      ToolResult unchanged = ToolResult.text(why);
      completed(auditCall, new ConnectorInvoker.Outcome(unchanged, null, null, 0, 0, why.length(), null, why, false), 0,
          AuditWriter.Verdict.ALLOWED, null);
      // "Unchanged" is no output of the tool: a tool with an outputSchema can only say it as an error.
      return ResultWriter.callToolResult(env, outputSchema == null ? unchanged : ApprovalAnswers.asError(unchanged),
          call.callId(), r.span().traceId(), null);
    }
    if (pair.mode() == Mode.BYPASS && decision.outcome() == Decision.Outcome.ALLOW) {
      received(auditCall, AuditWriter.Received.of(AuditWriter.Verdict.ALLOWED, kind, decision.ruleId()));
      return run(r, auditCall, request, null, action);
    }
    ActionPreview preview = action.preview();
    String note = args.path("note").isString() ? args.get("note").asString() : null;
    boolean mrtr = ApprovalDelivery.mrtr(r);
    ApprovalService.Ticket ticket;
    try {
      ticket = approvals.requestOrReuse(new ApprovalService.NewRequest(r.caller(), r.snapshot().id(),
          r.snapshot().slug(), call.callId(), call, pair.toolKey(), cfg, preview, action.preparedState(), note,
          decision.ruleId(), kind, r.span().traceparent(), auditCall,
          mrtr ? AuditWriter.Delivery.MRTR : AuditWriter.Delivery.HANDLE));
    } catch (ApprovalService.TooManyWaitingException e) {
      return ResultWriter.callToolResult(env, ToolResult.error(e.getMessage()), call.callId(), r.span().traceId(),
          null);
    } catch (AuditUnavailableException e) {
      // The request and its received row commit together: neither exists, nothing ran.
      log.warn("The audit refused the received row of {}: {}", call.callId(), e.toString());
      throw new McpException(JsonRpc.INTERNAL_ERROR, AUDIT_UNAVAILABLE);
    }
    return answer(r, delivery.first(r, ticket.approval(), argumentsDigest), call.callId(), outputSchema);
  }

  private ObjectNode answer(McpRequest r, ApprovalDelivery.Answer answer, String callId, JsonNode outputSchema) {
    if (answer.inputRequired() != null) {
      return answer.inputRequired();
    }
    ToolResult result = outputSchema == null ? answer.result() : answers.typed(answer.view(), answer.result());
    if (r.env().supportsUi() && answer.view().pending()) {
      List<ToolResult.Content> content = new ArrayList<>(result.content());
      content.add(ToolResult.Content.text("Show approval_card with handle " + answer.view().id()
          + " once for interactive review. Final approval happens in GateRook. Keep polling approval_status; "
          + "do not repeat the operation."));
      result = new ToolResult(content, result.structuredContent(), result.isError(), result.table(), result.truncated(),
          result.truncatedReason(), result.maskPaths(), result.maskedCount(), result.resultBytes(), result.meta());
    }
    return ResultWriter.callToolResult(r.env(), result, callId, r.span().traceId(),
        result.meta() instanceof ObjectNode meta ? meta : null);
  }

  private ObjectNode approvalStatus(McpRequest r, ObjectNode args) {
    List<SchemaValidator.Violation> violations = validator.validateBounded(CoreTools.approvalStatus().inputSchema(),
        args, SchemaValidator.BUDGET);
    if (!violations.isEmpty()) {
      return ResultWriter.toolError(r.env(), invalid(violations), null);
    }
    PolicyEngine.Rates rates = engine.rates(r.snapshot().env());
    // It counts against the principal's limit, not against a tool's: polling is what the agent was told to do.
    ToolGuard.Admission admission = guard.admit(r.caller().principalId(), "\0" + CoreTools.APPROVAL_STATUS,
        rates.perToken(), Integer.MAX_VALUE);
    if (!admission.allowed()) {
      return rateLimited(r, CoreTools.APPROVAL_STATUS, admission, rates.perToken());
    }
    ToolResult result = statusTool.call(r.caller(), r.snapshot().id(), args);
    return ResultWriter.callToolResult(r.env(), result, null, r.span().traceId(), null);
  }

  private ObjectNode rateLimited(McpRequest r, String tool, ToolGuard.Admission admission, int perMinute) {
    long seconds = Math.max(1, (admission.retryAfterMs() + 999) / 1000);
    String text = "tool".equals(admission.scope())
        ? perMinute + " calls per min for " + tool + " on this token. Retry after " + seconds + " s."
        : perMinute + " calls per min on this token. Retry after " + seconds + " s.";
    ObjectNode meta = JsonNodeFactory.instance.objectNode();
    meta.put("ai.msrv.gaterook/retryAfterMs", admission.retryAfterMs());
    return ResultWriter.toolError(r.env(), text, meta);
  }

  private void received(AuditWriter.Call call, AuditWriter.Received received) {
    try {
      audit.received(call, received);
    } catch (AuditUnavailableException e) {
      throw new McpException(JsonRpc.INTERNAL_ERROR, AUDIT_UNAVAILABLE);
    }
  }

  private void completed(AuditWriter.Call call, ConnectorInvoker.Outcome o, long durationMs,
      AuditWriter.Verdict verdict, String ruleId) {
    try {
      audit.completed(call, new AuditWriter.Completion(verdict, ruleId, null, null, null, durationMs,
          Math.max(0, durationMs - o.resourceMs()), o.resourceMs(), null, o.resultBytes(), o.resultSha256(),
          o.preview(), o.maskedCount(), o.truncated(), o.errorKind() == null ? null : o.errorKind().name(),
          o.errorMessage(), o.outcomeUnknown() ? Boolean.TRUE : null, null, null));
    } catch (AuditUnavailableException e) {
      throw new McpException(JsonRpc.INTERNAL_ERROR, "The audit couldn't record the result, so it is not shown. "
          + "Try again later.");
    }
  }

  /** Template and runbook facts for Activity ({@code runbook:compose-ps}, the template's name and version). */
  private static ObjectNode facets(EndpointSnapshot.Pair pair, ObjectNode args) {
    ObjectNode f = JsonNodeFactory.instance.objectNode();
    if (args.path("runbook").isString()) {
      f.put("runbook", args.get("runbook").asString());
    }
    if (pair.template() != null) {
      f.put("template", pair.template().templateId());
      f.put("templateVersion", pair.template().version());
    }
    return f;
  }

  private static String invalid(List<SchemaValidator.Violation> violations) {
    StringBuilder out = new StringBuilder("Invalid arguments: ");
    for (int i = 0; i < Math.min(5, violations.size()); i++) {
      SchemaValidator.Violation v = violations.get(i);
      out.append(i > 0 ? "; " : "").append(v.pointer() == null || v.pointer().isEmpty() ? "" : v.pointer() + " ")
          .append(v.message());
    }
    return out.toString();
  }
}
