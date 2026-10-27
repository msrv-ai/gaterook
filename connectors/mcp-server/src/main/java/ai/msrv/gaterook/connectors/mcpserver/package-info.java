/**
 * External MCP servers ({@code mcp-server}), report 11.
 *
 * <p>One {@code McpServerConnector} per connection (the core's {@code McpServerSource} creates them); tools come from
 * the server, renamed with the connection's prefix ({@code github_…}), declared annotations shown as "not verified".
 * Every call is a mutation unless a person confirmed Read only for that tool and version. Step C10 implements the
 * client, the supervisor and the snapshots; this package has the final type manifest.
 */
package ai.msrv.gaterook.connectors.mcpserver;
