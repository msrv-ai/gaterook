package ai.msrv.gaterook.core.policy;

import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * One tool × connection pair of an endpoint as the policy sees it (ARCHITECTURE.md §2.2: this module's own record, so
 * that {@code policy} doesn't depend on {@code endpoints}, which builds it from its snapshot).
 *
 * @param endpointReadOnly {@code endpoint.access = read-only}: no mutation, not even with approval
 * @param manifest         the connector type of the connection
 * @param toolKey          {@code sql_query}, {@code tpl:<id>:<opKey>}, {@code mcp:<connection>:<original>}, …
 * @param endpointLimits   the endpoint's limit overrides ({@code settings.limits}), or {@code null}
 * @param overrides        the pair's own stricter limits ({@code endpoint_tool.overrides}), or {@code null}
 */
public record PairView(String endpointId, String endpointSlug, Env endpointEnv, boolean endpointReadOnly,
    String connectionId, String connectionName, Env connectionEnv, ConnectorManifest manifest, ToolDefinition tool,
    String toolKey, ToolDefinition.Mode mode, JsonNode endpointLimits, JsonNode overrides) {

  public PairView {
    Objects.requireNonNull(endpointSlug, "endpointSlug");
    Objects.requireNonNull(endpointEnv, "endpointEnv");
    Objects.requireNonNull(connectionEnv, "connectionEnv");
    Objects.requireNonNull(manifest, "manifest");
    Objects.requireNonNull(tool, "tool");
    Objects.requireNonNull(toolKey, "toolKey");
    Objects.requireNonNull(mode, "mode");
  }

  /** The same pair in another mode: the truth table and tests. */
  public PairView withMode(ToolDefinition.Mode value) {
    return new PairView(endpointId, endpointSlug, endpointEnv, endpointReadOnly, connectionId, connectionName,
        connectionEnv, manifest, tool, toolKey, value, endpointLimits, overrides);
  }
}
