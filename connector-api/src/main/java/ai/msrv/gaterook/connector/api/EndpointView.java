package ai.msrv.gaterook.connector.api;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * What {@link Connector#tools(EndpointView)} may know about an endpoint: its connections of this type, their limits
 * and template contributions. No tokens.
 */
public record EndpointView(String slug, Env env, List<ConnectionConfig> connections,
    Map<String, Limits> limitsByConnection, List<Contribution> contributions) {

  public record Contribution(String connectionId, String templateId, int version, JsonNode value) {}
}
