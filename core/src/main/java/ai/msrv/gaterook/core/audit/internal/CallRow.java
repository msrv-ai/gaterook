package ai.msrv.gaterook.core.audit.internal;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.audit.internal.persistence.ApprovalDeliveryMode;
import ai.msrv.gaterook.core.audit.internal.persistence.CallDecision;
import ai.msrv.gaterook.core.audit.internal.persistence.CallPhase;
import ai.msrv.gaterook.core.audit.internal.persistence.ToolCallEntity;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.Ingress;
import java.time.Instant;
import java.util.Map;

/**
 * The values of one {@code tool_call} row before it has a place in the chain: the writer builds the entity from it
 * once to hash it and once to insert it, and a row that couldn't be written waits in the backlog in this form (its
 * place in the chain is decided when it is finally written).
 */
public record CallRow(String callId, CallPhase phase, Instant createdAt, String endpointSlug, String toolName,
    CallDecision decision, String toolKey, String connectionName, Env env, Caller.Kind principalKind, Ingress ingress,
    String tokenLabel, String callerIp, String client, Map<String, Object> clientInfo, String protocolVersion,
    String traceId, String spanId, Map<String, Object> argsMasked, String argsSha256,
    Classification.Kind classification, String ruleId, String approvalId, ApprovalDeliveryMode delivery,
    String decidedVia, String commentSha256, String actionSha256, String executedSha256, Long durationMs,
    Long gatewayMs, Long resourceMs, Long approvalWaitMs, Long resultBytes, String resultSha256, String resultPreview,
    Integer maskedCount, Boolean truncated, String errorKind, String errorMessage, Boolean outcomeUnknown,
    Map<String, Object> facets) {

  public ToolCallEntity build(String prev, String row) {
    return ToolCallEntity.builder(callId, phase, createdAt, endpointSlug, toolName, decision)
        .tool(toolKey, connectionName, env)
        .caller(principalKind, ingress, tokenLabel, callerIp)
        .client(client, clientInfo, protocolVersion)
        .trace(traceId, spanId)
        .arguments(argsMasked, argsSha256)
        .policy(classification, ruleId)
        .approval(approvalId, delivery, decidedVia, commentSha256, actionSha256, executedSha256)
        .timing(durationMs, gatewayMs, resourceMs, approvalWaitMs)
        .result(resultBytes, resultSha256, resultPreview, maskedCount, truncated)
        .error(errorKind, errorMessage, outcomeUnknown)
        .facets(facets)
        .chain(prev, row)
        .build();
  }

  /**
   * This row of an approval request (resolved, completed) for another call that reused the request: that call's own
   * identity from its {@code received} row — who, which client, which trace — and this row's decision and outcome.
   * Timings and the wait stay with the call that made the request, so that one execution and one wait are counted
   * once.
   */
  public CallRow repeatedBy(ToolCallEntity received) {
    return new CallRow(received.getCallId(), phase, createdAt, received.getEndpointSlug(), received.getToolName(),
        decision, received.getToolKey(), received.getConnectionName(), received.getEnv(), received.getPrincipalKind(),
        received.getIngress(), received.getTokenLabel(), received.getCallerIp(), received.getClient(),
        received.getClientInfo(), received.getProtocolVersion(), received.getTraceId(), received.getSpanId(), null,
        null, null, ruleId, approvalId, delivery, decidedVia, commentSha256, actionSha256, executedSha256, null, null,
        null, null, resultBytes, resultSha256, resultPreview, maskedCount, truncated, errorKind, errorMessage,
        outcomeUnknown, null);
  }

  /** The row as it joins the chain after {@code prev}. */
  public ToolCallEntity chained(String prev) {
    return build(prev, ChainHash.of(prev, build(prev, prev)));
  }
}
