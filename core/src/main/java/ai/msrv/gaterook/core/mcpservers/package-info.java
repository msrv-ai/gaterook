/**
 * MCP servers module (ARCHITECTURE.md §2.2, §7.5): {@code McpServerSource}, a ConnectorSource with one connector of
 * the {@code connectors/mcp-server} runtime per connection over its reviewed {@code mcp_server} snapshot;
 * {@code McpServerService} (review, add, start, stop, snapshots) and the runtime's host. The runtime itself (processes,
 * transports, supervision) is reached only through the {@code McpServerRuntime} SPI of {@code connectors.support}.
 *
 * <p>Allowed dependencies are those of the architecture's module table; the public API is this package, every
 * subpackage is internal.
 */
@ApplicationModule(displayName = "MCP servers", allowedDependencies = {
        "platform", "settings", "connectors", "connections", "secrets"})
package ai.msrv.gaterook.core.mcpservers;

import org.springframework.modulith.ApplicationModule;
