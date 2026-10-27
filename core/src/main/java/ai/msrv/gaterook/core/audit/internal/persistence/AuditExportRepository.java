package ai.msrv.gaterook.core.audit.internal.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * Table {@code audit_export}. What the person set is saved through the entity (versioned, then signed); what the
 * exports do moves through the conditional updates below, each one a compare-and-set on the batch in flight, so a run
 * that lost its destination (deleted, or another run finished the batch) changes nothing. The two that move the
 * export position write its MAC too ({@code Destinations.position}, over the version it was made for).
 */
public interface AuditExportRepository extends JpaRepository<AuditExportEntity, String> {

  List<AuditExportEntity> findByEnabledTrueOrderByNameAsc();

  List<AuditExportEntity> findAllByOrderByNameAsc();

  Optional<AuditExportEntity> findByName(String name);

  /**
   * The smallest export cursor among the destinations as stored, where a destination that never exported counts as 0.
   * Retention doesn't take it as it is: {@code Destinations.retentionBound} trusts only positions whose MAC holds.
   * Empty without destinations.
   */
  @Query("select min(coalesce(e.lastExportedId, 0)) from AuditExportEntity e")
  Optional<Long> findRetentionBound();

  /** Writes down the batch about to be sent, with the position's new MAC: only when none is in flight. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update AuditExportEntity e set e.pendingToId = :toId, e.lastAttemptAt = :at, e.mac = :mac where e.id = :id "
      + "and e.pendingToId is null and e.version = :version")
  int claim(String id, long toId, Instant at, byte[] mac, long version);

  /**
   * The batch in flight reached the destination: the cursor moves to its end with the position's new MAC, and the
   * destination is fine again.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update AuditExportEntity e set e.lastExportedId = :toId, e.lastExportedAt = :at, e.lastAttemptAt = :at, "
      + "e.pendingToId = null, e.state = 'ok', e.failingSince = null, e.lastError = null, e.mac = :mac "
      + "where e.id = :id and e.pendingToId = :toId and e.version = :version")
  int advance(String id, long toId, Instant at, byte[] mac, long version);

  /** An attempt that found nothing to send: the destination is up to date. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update AuditExportEntity e set e.lastAttemptAt = :at, e.state = 'ok', e.failingSince = null, "
      + "e.lastError = null where e.id = :id and e.pendingToId is null")
  int upToDate(String id, Instant at);

  /** An attempt failed: the cursor and the batch in flight stay, so the retry sends the same rows. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update AuditExportEntity e set e.state = 'failing', e.failingSince = coalesce(e.failingSince, :at), "
      + "e.lastAttemptAt = :at, e.lastError = :error where e.id = :id")
  int failed(String id, String error, Instant at);
}
