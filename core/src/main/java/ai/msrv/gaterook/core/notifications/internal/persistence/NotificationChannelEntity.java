package ai.msrv.gaterook.core.notifications.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OptimisticLock;
import org.hibernate.type.SqlTypes;

/**
 * Settings › Notifications for one channel. {@code config} holds {@code deliverTo}, {@code channelId},
 * {@code protectContent}…; bot tokens only as {@code {"$secret": "<id>"}}.
 */
@Entity
@Table(name = "notification_channel")
public class NotificationChannelEntity {

  /** The channel id ({@code slack}); a String key because JPA applies no converter to identifiers. */
  @Id
  @Column(name = "kind", length = 8, nullable = false, updatable = false)
  private String kind;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "config", nullable = false)
  private Map<String, Object> config;

  /**
   * What the channel last reported: outside optimistic locking and the MAC, because it decides nothing and a form that
   * read the channel keeps its version.
   */
  @OptimisticLock(excluded = true)
  @Column(name = "state", length = 16)
  private String state;

  @OptimisticLock(excluded = true)
  @Column(name = "state_detail", length = 512)
  private String stateDetail;

  @OptimisticLock(excluded = true)
  @Column(name = "last_delivery_at")
  private Instant lastDeliveryAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  /**
   * {@code platform.RowMac} over the kind, the switch and the config (V30, K2): where exact actions go and which
   * workspace a click must come from. Not part of optimistic locking, so signing doesn't bump the version it signs.
   */
  @OptimisticLock(excluded = true)
  @Column(name = "mac")
  private byte[] mac;

  protected NotificationChannelEntity() {}

  public NotificationChannelEntity(NotifyChannel channel, boolean enabled, Map<String, Object> config, Instant now) {
    this.kind = Objects.requireNonNull(channel, "channel").id();
    this.enabled = enabled;
    this.config = Objects.requireNonNull(config, "config");
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public NotifyChannel getChannel() {
    return new NotifyChannel.Converter().convertToEntityAttribute(kind);
  }

  public boolean isEnabled() {
    return enabled;
  }

  public Map<String, Object> getConfig() {
    return config;
  }

  public void configure(boolean enabled, Map<String, Object> config, Instant now) {
    this.enabled = enabled;
    this.config = Objects.requireNonNull(config, "config");
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public String getState() {
    return state;
  }

  public String getStateDetail() {
    return stateDetail;
  }

  public void setState(String state, String detail) {
    this.state = state;
    this.stateDetail = detail;
  }

  public Instant getLastDeliveryAt() {
    return lastDeliveryAt;
  }

  public void setLastDeliveryAt(Instant lastDeliveryAt) {
    this.lastDeliveryAt = lastDeliveryAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public long getVersion() {
    return version;
  }

  public byte[] getMac() {
    return mac;
  }

  public void setMac(byte[] mac) {
    this.mac = mac;
  }
}
