package ai.msrv.gaterook.core.connectors;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import java.util.List;
import java.util.Optional;

/**
 * SPI: connectors that are not Spring beans of a connector module (ARCHITECTURE.md §2.3).
 *
 * <p>{@code ConnectorRegistry} collects the built-in {@link Connector} beans and asks every {@code ConnectorSource}
 * bean: {@code connectors.plugins} contributes a {@code RemoteConnector} per installed plugin (its manifest comes from
 * the database, no plugin host is started), {@code mcpservers} an {@code McpServerConnector} per connection of type
 * {@code mcp-server}. Steps K4 and C10 add beans of this type and never edit the registry. For the policy, approvals
 * and audit all of them are one interface, {@link Connector}.
 */
public interface ConnectorSource {

  /**
   * Connector types this source adds to {@code connector_type} and to the catalog, e.g. one per installed plugin.
   * Must be cheap and must not start a process; may be empty when the source only serves connections of a type that a
   * connector bean declares (the {@code mcp-server} type).
   */
  List<ConnectorManifest> types();

  /**
   * The connector that serves {@code connection}, or empty when this source doesn't own the connection's type. May
   * return the same instance for every connection of a type (plugins) or one per connection (MCP servers). Must not
   * start a process: hosts and servers start on the first call that needs them.
   */
  Optional<Connector> connectorFor(ConnectionConfig connection);
}
