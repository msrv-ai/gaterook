package ai.msrv.gaterook.core.connections;

import java.util.List;

/**
 * SPI: a module that keeps something of a connection takes part in its deletion, inside the deletion's transaction
 * (ARCHITECTURE.md §4 "Deletion"): {@code endpoints} unwires it and reports the tools that stop, {@code templates}
 * marks the templates that ran on it invalid, {@code approvals} rejects its pending requests. Throwing vetoes the
 * deletion; everything rolls back.
 */
public interface ConnectionRemovalParticipant {

  /** What one participant did, for the summary of {@code DELETE /api/connections/{name}}. */
  record Removal(List<String> stoppedTools, int rejectedApprovals, List<String> templates, List<String> clients) {

    public static Removal none() {
      return new Removal(List.of(), 0, List.of(), List.of());
    }
  }

  Removal onRemoving(String connectionId, String connectionName, String reason);
}
