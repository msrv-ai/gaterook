package com.gaterook.core.endpoints;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorManifest;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.connector.api.ToolDefinition.Mode;
import com.gaterook.core.policy.PairView;
import java.time.Instant;
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
  public record Wired(ConnectionConfig config, ConnectorManifest manifest, String problem, boolean enabled) {

    public Wired(ConnectionConfig config, ConnectorManifest manifest, String problem) {
      this(config, manifest, problem, true);
    }

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
   * @param held        why an external MCP server's tool doesn't run in its stored Read only or Bypass (its row records
   *                    another definition, or none: ARCHITECTURE.md §7.5), or {@code null}
   */
  public record Pair(String rowId, long rowVersion, String toolKey, ToolDefinition tool, ConnectorManifest manifest,
      ConnectionConfig config, Mode mode, Mode storedMode, JsonNode overrides, String declaredBy,
      boolean readOnlyConfirmed, String provenanceVersion, ToolCall.TemplateBinding template, PairView view,
      List<JsonNode> contributions, Instant bypassUntil, String held) {

    /** A pair that nothing holds. */
    public Pair(String rowId, long rowVersion, String toolKey, ToolDefinition tool, ConnectorManifest manifest,
        ConnectionConfig config, Mode mode, Mode storedMode, JsonNode overrides, String declaredBy,
        boolean readOnlyConfirmed, String provenanceVersion, ToolCall.TemplateBinding template, PairView view,
        List<JsonNode> contributions, Instant bypassUntil) {
      this(rowId, rowVersion, toolKey, tool, manifest, config, mode, storedMode, overrides, declaredBy,
          readOnlyConfirmed, provenanceVersion, template, view, contributions, bypassUntil, null);
    }

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

  /** User pauses keep discovery intact and refuse resource access with an actionable explanation. */
  public String disabledReason(String connection) {
    if (!enabled) return "Endpoint " + slug + " is disabled by the user. Ask the user to enable it in GateRook "
        + "before retrying. Nothing ran.";
    Optional<Wired> configured = connection(connection);
    if (configured.filter(w -> com.gaterook.core.connections.ConnectionService.needsSetup(w.config())).isPresent()) {
      return "Connection " + connection + " needs setup. Complete its settings in GateRook before enabling access. Nothing ran.";
    }
    return configured.filter(w -> !w.enabled()).map(w -> "Connection " + connection
        + " is disabled by the user across all endpoints. Ask the user to enable it in GateRook before retrying. "
        + "Nothing ran.").orElse(null);
  }

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
