package ai.msrv.gaterook.core.notifications.internal.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Table {@code notify_pairing}, keyed by code hash. */
public interface NotifyPairingRepository extends JpaRepository<NotifyPairingEntity, String> {

  @Modifying(clearAutomatically = true)
  @Query("delete from NotifyPairingEntity p where p.expiresAt < :now")
  int deleteExpired(Instant now);

  /** Codes still waiting: for a person's confirmation in the app or for the account to present them. */
  List<NotifyPairingEntity> findByConsumedAtIsNullAndExpiresAtAfter(Instant now);
}
