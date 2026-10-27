package ai.msrv.gaterook.core.notifications.internal.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Table {@code notify_delivery}. */
public interface NotifyDeliveryRepository extends JpaRepository<NotifyDeliveryEntity, String> {

  List<NotifyDeliveryEntity> findByApprovalId(String approvalId);

  Optional<NotifyDeliveryEntity> findByChannelAndExternalRef(NotifyChannel channel, String externalRef);

  List<NotifyDeliveryEntity> findByApprovalIdAndChannel(String approvalId, NotifyChannel channel);

  /** Deliveries still showing buttons: the start-up reconciliation updates those whose request closed meanwhile. */
  List<NotifyDeliveryEntity> findByState(DeliveryState state);
}
