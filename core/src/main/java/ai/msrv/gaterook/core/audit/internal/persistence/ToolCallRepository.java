package ai.msrv.gaterook.core.audit.internal.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/**
 * Table {@code tool_call}. Extends the bare {@link Repository}: inserts and reads only, no update or delete in the
 * API (report 15 §9 rule 21). Retention deletes through its own job, bounded by the V1 trigger.
 */
public interface ToolCallRepository extends Repository<ToolCallEntity, Long> {

  ToolCallEntity save(ToolCallEntity call);

  Optional<ToolCallEntity> findById(Long id);

  /** The head of the hash chain. */
  Optional<ToolCallEntity> findTopByOrderByIdDesc();

  List<ToolCallEntity> findByCallIdOrderByIdAsc(String callId);

  List<ToolCallEntity> findByTraceIdOrderByIdAsc(String traceId);

  /** The rows of one approval request's calls in one phase (index {@code tool_call_approval}, V3). */
  List<ToolCallEntity> findByApprovalIdAndPhaseOrderByIdAsc(String approvalId, CallPhase phase);

  List<ToolCallEntity> findByCreatedAtBetweenOrderByIdDesc(Instant from, Instant to, Pageable page);

  List<ToolCallEntity> findByIdGreaterThanOrderByIdAsc(long afterId, Pageable page);

  /** Rows by id (Activity › Send to SIEM sends the latest row of each selected call). */
  List<ToolCallEntity> findByIdInOrderByIdAsc(java.util.Collection<Long> ids);

  long count();
}
