package ai.msrv.gaterook.connector.api;

/** An MCP resource of a connection, e.g. {@code mcpgw://connections/dev-db/schema}. */
public record ResourceDescriptor(String uri, String name, String mimeType, String description) {}
