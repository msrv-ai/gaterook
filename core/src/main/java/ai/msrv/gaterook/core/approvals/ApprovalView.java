package ai.msrv.gaterook.core.approvals;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.core.platform.Caller;
import java.time.Instant;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * One approval request as the rest of the core reads it.
 *
 * @param status      {@code pending}, {@code running}, {@code completed}, {@code rejected}, {@code expired},
 *                    {@code failed} or {@code cancelled}
 * @param decidedVia  {@code app}, {@code desktop-notification}, {@code slack}, {@code telegram}, {@code client} (declined
 *                    in the MCP client), {@code core} (the gateway itself), {@code timeout}; {@code null} while pending
 * @param result      the masked result of the execution while it is kept (60 min), or {@code null}
 * @param error       why the gateway rejected or failed it, or {@code null}
 * @param call        the canonical call: tool, connection, version of its config, arguments, template binding
 */
public record ApprovalView(String id, String callId, String endpointId, String endpointSlug, String connectionId,
    String connectionName, String tool, String toolKey, Env env, Caller.Kind principalKind, String principalId,
    String clientLabel, String status, String digest, ActionPreview action, String actionSha256, String agentNote,
    String ruleId, String commentPolicy, String comment, String decidedVia, Instant decidedAt, Instant createdAt,
    Instant expiresAt, Instant executedAt, ToolResult result, Instant resultExpiresAt, String error,
    String traceContext, JsonNode call) {

  private static final Set<String> TERMINAL = Set.of("completed", "rejected", "expired", "failed", "cancelled");

  /** Nothing more will happen to it: no execution is pending or running. */
  public boolean terminal() {
    return TERMINAL.contains(status);
  }

  public boolean pending() {
    return "pending".equals(status);
  }
}
