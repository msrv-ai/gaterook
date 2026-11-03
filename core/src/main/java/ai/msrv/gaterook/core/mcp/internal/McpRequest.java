package ai.msrv.gaterook.core.mcp.internal;

import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.mcp.McpGateway;
import ai.msrv.gaterook.core.endpoints.ToolCatalog;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.CallTracing;

/**
 * Everything one request knows: its envelope, who sent it, the endpoint's snapshot and published tools (one view for
 * the whole request), and its span.
 *
 * @param stream   where its notifications go before its answer ({@link McpGateway.Stream#NONE}: nowhere)
 * @param logLevel the least severe log level its client asked for, or {@code null}
 * @param client   which client of the principal sent it (a bridge's {@code X-Gateway-Bridge-Client}), or {@code null}
 */
public record McpRequest(Envelope env, Caller caller, EndpointSnapshot snapshot, ToolCatalog.Catalog catalog,
    CallTracing.Started span, McpGateway.Stream stream, String logLevel, String client) {

  public McpRequest(Envelope env, Caller caller, EndpointSnapshot snapshot, ToolCatalog.Catalog catalog,
      CallTracing.Started span) {
    this(env, caller, snapshot, catalog, span, McpGateway.Stream.NONE, null, null);
  }

  /** The request's {@code _meta.progressToken} (a string or an integer), or {@code null}. */
  public tools.jackson.databind.JsonNode progressToken() {
    tools.jackson.databind.JsonNode t = env.meta().get("progressToken");
    return t != null && (t.isString() || t.isIntegralNumber()) ? t : null;
  }

  /** {@code settings.protocol.approvalDelivery}: {@code auto} (MRTR when the client can) or {@code handle-only}. */
  public boolean mrtrAllowed() {
    return !"handle-only".equals(snapshot.settings().path("protocol").path("approvalDelivery").asString("auto"));
  }

  /** {@code settings.approvalWait.firstWaitSeconds}: how long the first answer waits for a quick decision. */
  public int firstWaitSeconds() {
    int s = snapshot.settings().path("approvalWait").path("firstWaitSeconds").asInt(15);
    return Math.max(0, Math.min(25, s));
  }
}
