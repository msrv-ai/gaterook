package ai.msrv.gaterook.core.mcp;

import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshotService;
import ai.msrv.gaterook.core.endpoints.ToolCatalog;
import ai.msrv.gaterook.core.mcp.internal.BridgeClient;
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

  /**
   * Where the notifications of a request go before its answer: the event stream the POST is answered with
   * ({@code notifications/progress}, {@code notifications/message}). Called from the resource's reading thread: it
   * never blocks.
   */
  @FunctionalInterface
  public interface Stream {

    /** Nobody listens: the request is answered as JSON, and what it would have said goes nowhere. */
    Stream NONE = notification -> false;

    /** @return whether the notification will reach the client */
    boolean send(ObjectNode notification);

    /**
     * What to do when the client closes the stream: over 2026-07-28 that is how it cancels the request, so the call
     * stops; the 2025 revisions cancel with {@code notifications/cancelled} instead, and ignore it here.
     */
    default void onClose(Runnable action) {}
  }

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
    return handle(slug, caller, request, header, Stream.NONE);
  }

  /** {@link #handle} whose notifications go to {@code stream} before the answer. */
  public Response handle(String slug, Caller caller, ObjectNode request, Function<String, String> header,
      Stream stream) {
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
    McpDispatcher.Reply reply = dispatcher.dispatch(env, caller, snapshot.get(), header.apply(BridgeClient.HEADER),
        stream);
    return new Response(reply.status(), reply.body());
  }

  /** A JSON-RPC error without id (malformed bodies, before any envelope exists). */
  public static ObjectNode error(int code, String message) {
    return JsonRpc.error(null, code, message, (JsonNode) null);
  }
}
