package ai.msrv.gaterook.core.connectors;

/**
 * A connection's resource said something outside any call: an external MCP server's resources or prompts changed, or
 * a resource someone subscribed to was updated. The MCP module tells the clients of the endpoints the connection is
 * on ({@code notifications/resources/list_changed}, {@code notifications/prompts/list_changed},
 * {@code notifications/resources/updated}).
 *
 * @param uri the updated resource as endpoints publish it, for {@link Kind#RESOURCE_UPDATED}; else {@code null}
 */
public record ConnectionNotified(String connectionId, Kind kind, String uri) {

  public enum Kind { RESOURCES_CHANGED, PROMPTS_CHANGED, RESOURCE_UPDATED }
}
