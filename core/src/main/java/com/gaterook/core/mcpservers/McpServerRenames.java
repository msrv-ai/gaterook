package com.gaterook.core.mcpservers;

import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connectors.support.mcp.McpServerRuntime;
import com.gaterook.core.connections.ConnectionRenameParticipant;
import com.gaterook.core.mcpservers.internal.persistence.McpServerRepository;
import com.gaterook.core.platform.ProblemException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * An External MCP server's work folder and log are named after its connection ({@code mcp/<name>},
 * {@code logs/mcp/<name>.log}; ARCHITECTURE.md §7.5): a rename moves them along, so the server keeps its installed
 * package and its home, and moves them back when the rename isn't saved. Ordered after the other participants, so a
 * veto of theirs moves nothing; the server can't start again until the transaction ends either way.
 */
@Component
@Order(100)
class McpServerRenames implements ConnectionRenameParticipant {

  private final McpServerRepository repository;
  private final ObjectProvider<McpServerRuntime> runtime;

  McpServerRenames(McpServerRepository repository, ObjectProvider<McpServerRuntime> runtime) {
    this.repository = repository;
    this.runtime = runtime;
  }

  @Override
  public void onRenaming(String connectionId, String from, String to, String reason) {
    McpServerRuntime rt = runtime.getIfAvailable();
    if (rt == null || repository.findByConnectionId(connectionId).isEmpty()) {
      return;
    }
    McpServerRuntime.Relocation relocation;
    try {
      relocation = rt.relocate(connectionId, from, to);
    } catch (ConnectorException e) {
      throw ProblemException.conflict("Couldn't move the server's files", e.getMessage());
    }
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      // Only reached outside a transaction, where nothing can roll back.
      relocation.commit();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCompletion(int status) {
        if (status == STATUS_COMMITTED) {
          relocation.commit();
        } else {
          relocation.rollback();
        }
      }
    });
  }
}
