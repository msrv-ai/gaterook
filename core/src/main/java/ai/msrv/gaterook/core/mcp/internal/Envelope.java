package ai.msrv.gaterook.core.mcp.internal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One parsed JSON-RPC request with its era (ARCHITECTURE.md §5 step 3).
 *
 * @param id                 the request id; {@code null} for a notification
 * @param version            the revision the answer speaks ({@code 2026-07-28}, {@code 2025-11-25}, …)
 * @param clientCapabilities modern: from {@code _meta}; legacy: from {@code initialize} (else empty)
 * @param clientInfo         what the client says it is ({@code _meta} or {@code initialize}); shown, never trusted
 * @param traceparent        {@code params._meta.traceparent} as sent (validated where it is used)
 */
public record Envelope(Era era, String version, JsonNode id, String method, ObjectNode params,
    JsonNode clientCapabilities, JsonNode clientInfo, String traceparent) {

  public boolean notification() {
    return id == null;
  }

  /** The client accepts URL elicitation in this request (MRTR approvals, A3). */
  public boolean elicitationUrl() {
    return era == Era.MODERN && clientCapabilities != null
        && clientCapabilities.path("elicitation").path("url").isObject();
  }

  public JsonNode meta() {
    JsonNode meta = params.path("_meta");
    return meta.isObject() ? meta : JsonNodeFactory.instance.objectNode();
  }
}
