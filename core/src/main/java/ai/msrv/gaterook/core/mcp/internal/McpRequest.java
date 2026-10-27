package ai.msrv.gaterook.core.mcp.internal;

import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.endpoints.ToolCatalog;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.CallTracing;

/**
 * Everything one request knows: its envelope, who sent it, the endpoint's snapshot and published tools (one view for
 * the whole request), and its span.
 */
public record McpRequest(Envelope env, Caller caller, EndpointSnapshot snapshot, ToolCatalog.Catalog catalog,
    CallTracing.Started span) {

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
