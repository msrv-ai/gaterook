package ai.msrv.gaterook.core.notifications.internal.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Table {@code notification_channel}, keyed by channel id ({@code desktop}, {@code slack}, {@code telegram}). */
public interface NotificationChannelRepository extends JpaRepository<NotificationChannelEntity, String> {}
