package ai.msrv.gaterook.core.mcp;

import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshotService;
import ai.msrv.gaterook.core.endpoints.ToolCatalog;
import ai.msrv.gaterook.core.mcp.internal.Envelope;
import ai.msrv.gaterook.core.mcp.internal.EraResolver;
import ai.msrv.gaterook.core.mcp.internal.Instructions;
import ai.msrv.gaterook.core.mcp.internal.JsonRpc;
import ai.msrv.gaterook.core.mcp.internal.McpDispatcher;
import ai.msrv.gaterook.core.platform.Caller;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The MCP runtime's entry (ARCHITECTURE.md §5 steps 3–12) for the HTTP transport and for the Playground, which calls
 * it with its own principal in the era it picked: one parsed JSON-RPC object of an authenticated caller on one
 * endpoint, answered for its era.
 */
@Component
public class McpGateway {

  /** The answer: HTTP status and the JSON-RPC body, or {@code null} for 202 (a notification). */
  public record Response(int status, ObjectNode body) {}

  private final EndpointSnapshotService snapshots;
  private final McpDispatcher dispatcher;
  private final ToolCatalog catalogs;

  public McpGateway(EndpointSnapshotService snapshots, McpDispatcher dispatcher, ToolCatalog catalogs) {
    this.snapshots = snapshots;
    this.dispatcher = dispatcher;
    this.catalogs = catalogs;
  }

  /**
   * The {@code instructions} the gateway writes for an endpoint whose person wrote none (kit endpoint-detail ›
   * Settings › Server instructions shows it in the field): the tools that wait for approval, the approval rules, the
   * {@code ‹masked›} line. Never a secret: every agent on the endpoint reads it.
   */
  public String defaultInstructions(EndpointSnapshot snapshot) {
    return Instructions.defaults(snapshot, catalogs.forEndpoint(snapshot));
  }

  /**
   * @param header the request's headers by name (case-insensitive)
   */
  public Response handle(String slug, Caller caller, ObjectNode request, Function<String, String> header) {
    Envelope env;
    try {
      env = EraResolver.resolve(request, header);
    } catch (EraResolver.Rejected e) {
      return new Response(400, JsonRpc.error(e.id(), e.code(), e.getMessage(), e.data()));
    }
    Optional<EndpointSnapshot> snapshot = snapshots.bySlug(slug);
    if (snapshot.isEmpty()) {
      // The token named this endpoint a moment ago: it was deleted since.
      return new Response(404, JsonRpc.error(env.id(), JsonRpc.METHOD_NOT_FOUND, "Endpoint " + slug + " doesn't "
          + "exist", null));
    }
    if (!snapshot.get().enabled()) {
      return new Response(404, JsonRpc.error(env.id(), JsonRpc.METHOD_NOT_FOUND, "Endpoint " + slug + " is disabled",
          null));
    }
    McpDispatcher.Reply reply = dispatcher.dispatch(env, caller, snapshot.get());
    return new Response(reply.status(), reply.body());
  }

  /** A JSON-RPC error without id (malformed bodies, before any envelope exists). */
  public static ObjectNode error(int code, String message) {
    return JsonRpc.error(null, code, message, (JsonNode) null);
  }
}
