package ai.msrv.gaterook.core.approvals.internal.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Table {@code approval}. */
public interface ApprovalRepository extends JpaRepository<ApprovalEntity, String> {

  List<ApprovalEntity> findByStatusOrderByExpiresAtAsc(ApprovalStatus status);

  /** Dedup: the same call by the same principal while it is pending gets the same handle. */
  Optional<ApprovalEntity> findFirstByEndpointIdAndPrincipalIdAndDigestAndStatus(String endpointId,
      String principalId, String digest, ApprovalStatus status);

  long countByPrincipalIdAndStatus(String principalId, ApprovalStatus status);

  List<ApprovalEntity> findByStatusAndExpiresAtBefore(ApprovalStatus status, Instant now);

  List<ApprovalEntity> findByConnectionIdAndStatus(String connectionId, ApprovalStatus status);

  List<ApprovalEntity> findByEndpointIdAndStatus(String endpointId, ApprovalStatus status);

  List<ApprovalEntity> findByEndpointIdAndConnectionIdAndStatus(String endpointId, String connectionId,
      ApprovalStatus status);

  List<ApprovalEntity> findByEndpointIdAndPrincipalIdAndStatus(String endpointId, String principalId,
      ApprovalStatus status);

  /**
   * The only way the status changes: compare-and-set from {@code expected} that also bumps the version
   * ({@code update versioned}), so a decision racing a timeout or a second channel affects exactly one row or none.
   * Returns the number of rows changed: 1 means this caller won.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update versioned ApprovalEntity a set a.status = :to where a.id = :id and a.status = :expected")
  int transition(String id, ApprovalStatus expected, ApprovalStatus to);

  /**
   * The core rejects a pending request whose connection or endpoint goes away, or whose principal was revoked: the
   * same versioned compare-and-set as {@link #transition}, with who decided and why in the same statement. The reason
   * goes to {@code error} (and to the {@code decided} event), never to {@code comment}: that column is a person's
   * decision comment ("Rejected by you: …", {@code tool_call.comment_sha256}), and {@code decided_via = 'core'} has
   * none. Leaves the persistence context alone, so that the deletion around it keeps its managed entities.
   */
  @Modifying(flushAutomatically = true)
  @Query("update versioned ApprovalEntity a set a.status = :to, a.decidedVia = :via, "
      + "a.error = :why, a.decidedAt = :at where a.id = :id and a.status = :expected")
  int rejectByCore(String id, ApprovalStatus expected, ApprovalStatus to, String via, String why, Instant at);

  /**
   * A person's decision: the status CAS from {@code pending} before the request expired, with who decided, how and
   * their comment in the same statement.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update versioned ApprovalEntity a set a.status = :to, a.decidedVia = :via, a.decidedExternalId = :external, "
      + "a.comment = :comment, a.decidedAt = :at where a.id = :id and a.status = :expected and a.expiresAt > :at")
  int decide(String id, ApprovalStatus expected, ApprovalStatus to, String via, String external, String comment,
      Instant at);

  /** A status CAS that records why ({@code error}): failures, refusals of the executor, expiry. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update versioned ApprovalEntity a set a.status = :to, a.error = :error where a.id = :id "
      + "and a.status = :expected")
  int finish(String id, ApprovalStatus expected, ApprovalStatus to, String error);

  /**
   * The executor stopped an approved request before it ran: {@code rejected} by the gateway itself
   * ({@code decided_via = 'core'}, as {@code approval.resolved} says), with why. The person's decision stays in the
   * request's feed and the audit, and so does when they made it ({@code decided_at}).
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update versioned ApprovalEntity a set a.status = :to, a.decidedVia = :via, a.error = :error "
      + "where a.id = :id and a.status = :expected")
  int refuse(String id, ApprovalStatus expected, ApprovalStatus to, String via, String error);

  /** The timeout: {@code pending} requests whose window closed become {@code expired}. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update versioned ApprovalEntity a set a.status = :to, a.decidedVia = :via, a.decidedAt = :at "
      + "where a.id = :id and a.status = :expected and a.expiresAt <= :at")
  int expire(String id, ApprovalStatus expected, ApprovalStatus to, String via, Instant at);

  /** History of Approvals: every request that no longer waits, newest first; the first page. */
  List<ApprovalEntity> findByStatusNotOrderByCreatedAtDescIdDesc(ApprovalStatus status, Pageable page);

  /** The next page of the history, after the request {@code (at, id)} the previous page ended with. */
  @Query("select a from ApprovalEntity a where a.status <> :status and (a.createdAt < :at "
      + "or (a.createdAt = :at and a.id < :id)) order by a.createdAt desc, a.id desc")
  List<ApprovalEntity> historyAfter(ApprovalStatus status, Instant at, String id, Pageable page);

  /** The statuses of the requests made since {@code since}: what the history counts per outcome. */
  @Query("select a.status from ApprovalEntity a where a.createdAt >= :since")
  List<ApprovalStatus> statusesSince(Instant since);

  long countByStatus(ApprovalStatus status);

  /** Executed results past their 60 minutes. */
  List<ApprovalEntity> findByResultExpiresAtBefore(Instant now);

  /** {@link #transition} that also requires the request not to have expired at {@code now} (decisions). */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update versioned ApprovalEntity a set a.status = :to "
      + "where a.id = :id and a.status = :expected and a.expiresAt > :now")
  int transitionBeforeExpiry(String id, ApprovalStatus expected, ApprovalStatus to, Instant now);
}
