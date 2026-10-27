package ai.msrv.gaterook.core.endpoints;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.policy.PairView;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * An immutable view of one endpoint as a call needs it (ARCHITECTURE.md §5 step 4): its settings, its connections
 * with their configs, every tool × connection pair with its effective mode, the core tools. Built by
 * {@link EndpointSnapshotService}, cached, rebuilt when the endpoint, a connection, a template, a policy or a
 * connector type changes.
 *
 * @param blockedReason why the endpoint serves nothing right now (a row changed outside GateRook), or {@code null}
 */
public record EndpointSnapshot(String id, String slug, String description, Env env, boolean readOnly,
    boolean enabled, String instructions, JsonNode settings, boolean published, boolean oauthEnabled,
    JsonNode riskDismissed, long version, List<Wired> connections, List<Pair> pairs, List<CorePair> coreTools,
    String blockedReason) {

  /**
   * A connection of the endpoint.
   *
   * @param problem why its tools don't run ("changed outside GateRook", "PostgreSQL is switched off"), or
   *                {@code null}
   */
  public record Wired(ConnectionConfig config, ConnectorManifest manifest, String problem) {

    public boolean usable() {
      return problem == null;
    }
  }

  /**
   * One tool × connection pair.
   *
   * @param mode        the effective mode: the stored one, except that an unconfirmed Read only of a plugin or MCP
   *                    server tool counts as Approval and a pair of an unusable connection as Off
   * @param storedMode  what {@code endpoint_tool} says
   * @param declaredBy  who declared the tool's annotations (plugin, MCP server), or {@code null}
   * @param provenanceVersion the version a Read only confirmation binds to (the plugin's, the upstream MCP server's)
   * @param template    the template binding of a compiled tool, or {@code null}
   * @param contributions what the templates of the pair's connection contribute to its tools (runbooks of
   *                      {@code ssh_run}); a runbook with {@code "freeText": true} makes {@code ssh_run} a full channel
   */
  public record Pair(String rowId, long rowVersion, String toolKey, ToolDefinition tool, ConnectorManifest manifest,
      ConnectionConfig config, Mode mode, Mode storedMode, JsonNode overrides, String declaredBy,
      boolean readOnlyConfirmed, String provenanceVersion, ToolCall.TemplateBinding template, PairView view,
      List<JsonNode> contributions) {

    public String connectionName() {
      return config.name();
    }

    public boolean on() {
      return mode != Mode.OFF;
    }
  }

  /**
   * An endpoint-wide core tool with a mode ({@code list_connections}, {@code describe_connection}).
   *
   * @param mode       the effective mode: a read-only endpoint holds Approval as Off, like every pair
   * @param storedMode what {@code endpoint_tool} says
   */
  public record CorePair(String rowId, long rowVersion, String tool, Mode mode, Mode storedMode) {}

  public boolean blocked() {
    return blockedReason != null;
  }

  /** The pairs that are not Off. */
  public List<Pair> onPairs() {
    return pairs.stream().filter(Pair::on).toList();
  }

  public Optional<Wired> connection(String name) {
    return connections.stream().filter(w -> w.config().name().equals(name)).findFirst();
  }

  public Optional<CorePair> coreTool(String name) {
    return coreTools.stream().filter(c -> c.tool().equals(name)).findFirst();
  }
}
