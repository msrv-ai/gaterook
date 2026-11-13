package com.gaterook.core.mcp.internal;

import com.gaterook.core.endpoints.EndpointSnapshot;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A {@code subscriptions/listen} request of 2026-07-28 (ARCHITECTURE.md §5.5), checked before its stream opens: the
 * envelope and headers like any request of the revision, an endpoint that speaks it, a filter of what the client opts
 * in to. What the gateway honors is what it can tell about: the three lists, and updates of the resources that a
 * server shares on the endpoint ({@link ResourcesHandler#subscribable}), at most
 * {@value ResourceSubscriptions#MAX_PER_OWNER}.
 */
public final class Listening {

  public static final String META_SUBSCRIPTION = "io.modelcontextprotocol/subscriptionId";

  /**
   * The check's outcome: the error to answer with its HTTP status, or the request's id, the filter honored and the
   * resources whose updates the stream carries.
   */
  public record Outcome(int status, ObjectNode error, JsonNode id, ObjectNode honored, Set<String> resources) {

    public boolean accepted() {
      return error == null;
    }

    public boolean wants(String notification) {
      return honored != null && honored.path(notification).asBoolean(false);
    }
  }

  private Listening() {}

  public static Outcome check(ObjectNode request, Function<String, String> header, Optional<EndpointSnapshot> found,
      ResourcesHandler resources) {
    Envelope env;
    try {
      env = EraResolver.resolve(request, header);
    } catch (EraResolver.Rejected e) {
      return refused(400, JsonRpc.error(e.id(), e.code(), e.getMessage(), e.data()));
    }
    if (env.era() != Era.MODERN) {
      // The 2025 revisions have no such method: their GET stream is what tells.
      return refused(200, JsonRpc.error(env.id(), JsonRpc.METHOD_NOT_FOUND, "Method not found: "
          + env.method(), null));
    }
    if (found.isEmpty()) {
      return refused(404, JsonRpc.error(env.id(), JsonRpc.METHOD_NOT_FOUND, "The endpoint doesn't exist", null));
    }
    EndpointSnapshot snapshot = found.get();
    List<String> revisions = McpDispatcher.revisions(snapshot);
    if (!revisions.contains(Era.MODERN_VERSION)) {
      ObjectNode data = JsonNodeFactory.instance.objectNode();
      revisions.forEach(data.putArray("supported")::add);
      data.put("requested", env.version());
      return refused(400, JsonRpc.error(env.id(), JsonRpc.UNSUPPORTED_VERSION, snapshot.slug() + " doesn't speak "
          + env.version() + ".", data));
    }
    JsonNode filter = env.params().get("notifications");
    if (filter == null || !filter.isObject()) {
      return refused(400, JsonRpc.error(env.id(), JsonRpc.INVALID_PARAMS, "params.notifications is required", null));
    }
    ObjectNode honored = JsonNodeFactory.instance.objectNode();
    for (String list : List.of("toolsListChanged", "resourcesListChanged", "promptsListChanged")) {
      if (filter.path(list).asBoolean(false)) {
        honored.put(list, true);
      }
    }
    Set<String> uris = new LinkedHashSet<>();
    for (JsonNode uri : filter.path("resourceSubscriptions")) {
      if (uri.isString() && uris.size() < ResourceSubscriptions.MAX_PER_OWNER
          && resources.subscribable(snapshot, uri.asString())) {
        uris.add(uri.asString());
      }
    }
    if (filter.has("resourceSubscriptions")) {
      ArrayNode kept = honored.putArray("resourceSubscriptions");
      uris.forEach(kept::add);
    }
    return new Outcome(200, null, env.id(), honored, Set.copyOf(uris));
  }

  private static Outcome refused(int status, ObjectNode error) {
    return new Outcome(status, error, null, null, Set.of());
  }

  /** A notification of the stream, carrying its subscription's id. */
  public static ObjectNode notification(String method, ObjectNode params, JsonNode subscriptionId) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("jsonrpc", "2.0");
    n.put("method", method);
    ObjectNode p = params == null ? JsonNodeFactory.instance.objectNode() : params;
    ObjectNode meta = p.get("_meta") instanceof ObjectNode m ? m : p.putObject("_meta");
    meta.set(META_SUBSCRIPTION, subscriptionId.deepCopy());
    n.set("params", p);
    return n;
  }

  /** The answer that ends a stream the gateway closes ({@code SubscriptionsListenResult}). */
  public static ObjectNode ended(JsonNode subscriptionId) {
    ObjectNode result = JsonNodeFactory.instance.objectNode();
    result.put("resultType", "complete");
    ObjectNode meta = result.putObject("_meta");
    meta.set(META_SUBSCRIPTION, subscriptionId.deepCopy());
    meta.putObject(ResultWriter.META_SERVER).put("name", ResultWriter.SERVER_NAME)
        .put("version", com.gaterook.core.platform.CoreBuild.VERSION);
    return JsonRpc.result(subscriptionId, result);
  }
}
