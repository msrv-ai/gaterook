package ai.msrv.gaterook.core.notifications.internal.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Table {@code notify_link}. */
public interface NotifyLinkRepository extends JpaRepository<NotifyLinkEntity, String> {

  List<NotifyLinkEntity> findByChannelAndRevokedAtIsNull(NotifyChannel channel);

  List<NotifyLinkEntity> findByRevokedAtIsNullOrderByLinkedAtAsc();
}
