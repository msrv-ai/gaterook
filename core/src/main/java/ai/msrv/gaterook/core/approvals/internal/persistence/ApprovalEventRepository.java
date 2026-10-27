package ai.msrv.gaterook.core.approvals.internal.persistence;

import java.util.List;
import org.springframework.data.repository.Repository;

/** Table {@code approval_event}: inserts and reads only. */
public interface ApprovalEventRepository extends Repository<ApprovalEventEntity, Long> {

  ApprovalEventEntity save(ApprovalEventEntity event);

  List<ApprovalEventEntity> findByApprovalIdOrderByIdAsc(String approvalId);
}
