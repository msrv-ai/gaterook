package ai.msrv.gaterook.core.connections;

/**
 * SPI: a module that keeps something under a connection's name takes part in its rename, inside the rename's
 * transaction, after the row has its new name (ARCHITECTURE.md §8.6 contract 13): {@code endpoints} moves the keys of
 * an MCP server's tools ({@code mcp:<name>:<tool>}) with their modes, {@code mcpservers} moves the server's work
 * folder and log. Throwing vetoes the rename; everything rolls back. What only records the past (the audit, approval
 * requests, Setup changes) keeps the old name.
 */
public interface ConnectionRenameParticipant {

  void onRenaming(String connectionId, String from, String to, String reason);
}
