package ai.msrv.gaterook.core.approvals.internal;

import ai.msrv.gaterook.core.approvals.internal.persistence.ApprovalEntity;
import ai.msrv.gaterook.core.approvals.internal.persistence.ApprovalEventEntity;
import ai.msrv.gaterook.core.approvals.internal.persistence.ApprovalEventKind;
import ai.msrv.gaterook.core.approvals.internal.persistence.ApprovalEventRepository;
import ai.msrv.gaterook.core.approvals.internal.persistence.ApprovalRepository;
import ai.msrv.gaterook.core.approvals.internal.persistence.ApprovalStatus;
import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.connections.ConnectionRemovalParticipant;
import ai.msrv.gaterook.core.connectors.PluginGenerationSwitched;
import ai.msrv.gaterook.core.endpoints.EndpointRemovalParticipant;
import ai.msrv.gaterook.core.approvals.ApprovalResolved;
import ai.msrv.gaterook.core.audit.AuditWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Pending approval requests whose connection, endpoint or principal goes away can never run: they are rejected in the
 * same transaction (ARCHITECTURE.md §4 "Deletion", §5 step 11 "Revoking a token/grant, disconnecting, or deleting a
 * connection automatically denies its pending requests"), and so are those of a plugin's connections when the plugin is updated (report 11 §4.3:
 * the action was approved against the old code), each by
 * the versioned compare-and-set that every status change goes through, with a {@code decided} entry in its timeline.
 * The reason is the row's {@code error} and the entry's {@code reason}; {@code comment} stays empty, since no person
 * decided ({@code decided_via = 'core'}). The rows keep the endpoint's slug and the connection's name for the
 * history; the foreign keys go to {@code NULL}.
 */
@Component
@Order(30)
class ApprovalCleanup implements ConnectionRemovalParticipant, EndpointRemovalParticipant {

  /** {@code approval.decided_via} of a request the core rejected itself. */
  static final String VIA = "core";

  private final ApprovalRepository approvals;
  private final ApprovalEventRepository events;
  private final ApprovalRows rows;
  private final ApprovalWaiters waiters;
  private final ConnectionConfigs connections;
  private final AuditWriter audit;
  private final ApplicationEventPublisher publisher;
  private final Clock clock;

  ApprovalCleanup(ApprovalRepository approvals, ApprovalEventRepository events, ApprovalRows rows,
      ApprovalWaiters waiters, ConnectionConfigs connections, AuditWriter audit, ApplicationEventPublisher publisher,
      Clock clock) {
    this.approvals = approvals;
    this.events = events;
    this.rows = rows;
    this.waiters = waiters;
    this.connections = connections;
    this.audit = audit;
    this.publisher = publisher;
    this.clock = clock;
  }

  @Override
  public ConnectionRemovalParticipant.Removal onRemoving(String connectionId, String connectionName, String reason) {
    int rejected = reject(approvals.findByConnectionIdAndStatus(connectionId, ApprovalStatus.PENDING),
        "Connection " + connectionName + " was deleted. Not executed.");
    return new ConnectionRemovalParticipant.Removal(List.of(), rejected, List.of(), List.of());
  }

  @Override
  public EndpointRemovalParticipant.Removal onEndpointRemoving(String endpointId, String slug, String reason) {
    int rejected = reject(approvals.findByEndpointIdAndStatus(endpointId, ApprovalStatus.PENDING),
        "Endpoint " + slug + " was deleted. Not executed.");
    return new EndpointRemovalParticipant.Removal(List.of(), rejected);
  }

  @Override
  public EndpointRemovalParticipant.Removal onConnectionUnwiring(String endpointId, String slug, String connectionId,
      String connectionName, String reason) {
    int rejected = reject(approvals.findByEndpointIdAndConnectionIdAndStatus(endpointId, connectionId,
        ApprovalStatus.PENDING), connectionName + " was removed from " + slug + ". Not executed.");
    return new EndpointRemovalParticipant.Removal(List.of(), rejected);
  }

  /** Revoke of a token or grant auto-rejects what its principal still waits for (§5 step 11). */
  @Override
  public EndpointRemovalParticipant.Removal onPrincipalRevoked(String endpointId, String slug, String principalId,
      String label, String reason) {
    int rejected = reject(approvals.findByEndpointIdAndPrincipalIdAndStatus(endpointId, principalId,
        ApprovalStatus.PENDING), "Access of " + label + " to " + slug + " was revoked. Not executed.");
    return new EndpointRemovalParticipant.Removal(List.of(), rejected);
  }

  /**
   * An update of a plugin rejects what still waits for the tools of its connections (report 16 §8: «Plugin updated to
   * 1.3.0, ask again»), in the transaction of the switch: a call approved later would run on the new code. The
   * plugin's id is the type of its connections.
   */
  @EventListener
  void onPluginUpdated(PluginGenerationSwitched switched) {
    for (ConnectionConfigs.Stored c : connections.all()) {
      if (c.config().type().equals(switched.pluginId())) {
        reject(approvals.findByConnectionIdAndStatus(c.id(), ApprovalStatus.PENDING),
            "Plugin updated to " + switched.toVersion() + ", ask again");
      }
    }
  }

  private int reject(List<ApprovalEntity> pending, String why) {
    int rejected = 0;
    Instant now = clock.instant();
    for (ApprovalEntity a : pending) {
      if (approvals.rejectByCore(a.getId(), ApprovalStatus.PENDING, ApprovalStatus.REJECTED, VIA, why, now) != 1) {
        // Decided or expired meanwhile: nothing to do.
        continue;
      }
      Map<String, Object> detail = new LinkedHashMap<>();
      detail.put("decision", "rejected");
      detail.put("by", VIA);
      detail.put("reason", why);
      events.save(new ApprovalEventEntity(a, ApprovalEventKind.DECIDED, detail, now));
      // The call's history ends here too; a refusal of the audit rolls the removal back (fail closed).
      audit.resolved(rows.auditCall(a), new AuditWriter.Resolution(AuditWriter.Verdict.REJECTED, a.getId(),
          a.getRuleId(), VIA, null, a.getActionSha256(), Duration.between(a.getCreatedAt(), now).toMillis(), why));
      publisher.publishEvent(new ApprovalResolved(a.getId(), "rejected", VIA));
      String id = a.getId();
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          waiters.signal(id);
        }
      });
      rejected++;
    }
    return rejected;
  }
}
