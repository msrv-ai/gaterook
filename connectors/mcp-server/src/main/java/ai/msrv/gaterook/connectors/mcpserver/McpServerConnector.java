package ai.msrv.gaterook.connectors.mcpserver;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.support.BuiltIn;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import ai.msrv.gaterook.connectors.support.mcp.McpServerRuntime;

/**
 * The External MCP server connector type: the catalog card, the "Add MCP server" form and Test connection of a server
 * that isn't saved yet. A stored server is served by its own connector ({@link ServerConnector}, from the runtime via
 * the core's {@code McpServerSource}), whose tools are that server's reviewed snapshot; this bean has no tools.
 */
public final class McpServerConnector implements Connector {

  public static final String TYPE = "mcp-server";

  private final ConnectorManifest manifest;
  private final McpServers runtime;

  public McpServerConnector(McpServers runtime) {
    this.runtime = runtime;
    this.manifest = typeManifest();
  }

  /** The type's manifest: built-in version, dynamic tools, the config form of {@code config.schema.json}. */
  public static ConnectorManifest typeManifest() {
    SchemaResources schemas = SchemaResources.of(McpServerConnector.class, "mcp-server");
    return ConnectorManifest.builder(TYPE, BuiltIn.VERSION, ConnectorManifest.Kind.MCP_SERVER)
        .name("External MCP server")
        .icon("plug")
        .group("HTTP and MCP")
        .description("Put any MCP server behind modes, approvals and audit.")
        .configSchema(schemas.load("config"))
        .dynamicTools(true)
        .build();
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    // Only reached for a connection the core has no snapshot for (an unverified row): nothing runs.
    return Classification.deny(policy.rule("no-snapshot"), cfg.name() + " has no reviewed tools, so " + call.tool()
        + " can't run.", "Review the server in Connectors › MCP servers.");
  }

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    // classify() denies every call, so no approval request can ask for a preview.
    throw new IllegalStateException(cfg.name() + " has no reviewed tools");
  }

  /** Test connection of a draft: a throw-away process lists its tools and stops. */
  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    try {
      McpServerRuntime.Handshake h = runtime.probe(cfg, secrets, ctx);
      return Tests.success(cfg.name(), h, null);
    } catch (ConnectorException e) {
      return Tests.failure(e, cfg);
    }
  }

  @Override
  public HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    // Never starts a process to look: a stored server's own connector reports its state.
    return HealthResult.unreachable(cfg.name() + " has no reviewed tools.");
  }

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    throw ConnectorException.denied(cfg.name() + " has no reviewed tools, so " + call.tool() + " can't run.");
  }

  @Override
  public void onConnectionRemoved(String connectionId) {
    runtime.forget(connectionId);
  }
}
