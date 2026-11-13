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

  /**
   * Whether {@code typeId} is one of GateRook's own connectors with another delivery: a connector module
   * (docs/connector-delivery.md). Such a type is registered as a built-in one, with its tools' own names and modes;
   * a compiled-in connector of the same id comes first and serves its connections. Every other type of a source is
   * foreign code: it takes no name of a built-in tool and declares no built-in kind.
   */
  default boolean firstParty(String typeId) {
    return false;
  }

  /**
   * Whether a {@link #firstParty first-party} type comes from a package that GateRook's signed list doesn't pin: a
   * connector module a person built or got elsewhere and installed from a file. It is registered as a module's types
   * are, but nothing it says is vouched for: it shares no tool with another connector, its tools start in Approval,
   * and the action a person approves is the call's own arguments.
   */
  default boolean unverified(String typeId) {
    return false;
  }

  /** The installed package (the {@code plugin} row) that delivers {@code typeId}, which its type row points at. */
  default Optional<String> packageOf(String typeId) {
    return Optional.empty();
  }

  /** The connectors of this source's {@link #firstParty first-party} types, one per type. */
  default List<Connector> firstPartyConnectors() {
    return List.of();
  }

  /**
   * Tool names of GateRook's own connectors that this source knows without having them installed (the modules its
   * channel lists): no plugin, MCP server or template may publish one.
   */
  default java.util.Set<String> reservedToolNames() {
    return java.util.Set.of();
  }
}
