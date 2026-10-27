package ai.msrv.gaterook.core.notifications.internal.persistence;

import ai.msrv.gaterook.core.platform.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;

/**
 * One message about one approval request in one channel. A click in Slack or Telegram is accepted only for the
 * {@code externalRef} (channel + message ts, chat + message id) of a delivery recorded here.
 */
@Entity
@Table(name = "notify_delivery")
public class NotifyDeliveryEntity {

  @Id
  @Column(name = "id", length = 36, nullable = false, updatable = false)
  private String id;

  /** An approval of the {@code approvals} module. */
  @Column(name = "approval_id", length = 40, nullable = false, updatable = false)
  private String approvalId;

  @Convert(converter = NotifyChannel.Converter.class)
  @Column(name = "channel", length = 8, nullable = false, updatable = false)
  private NotifyChannel channel;

  @Column(name = "target", length = 64, nullable = false, updatable = false)
  private String target;

  @Column(name = "external_ref", length = 64)
  private String externalRef;

  /** Whether the message carried Approve (ARCHITECTURE.md §7.4 conditions held when it was sent). */
  @Column(name = "offered_approve", nullable = false, updatable = false)
  private boolean offeredApprove;

  @Convert(converter = DeliveryState.Converter.class)
  @Column(name = "state", length = 10, nullable = false)
  private DeliveryState state;

  @Column(name = "error", length = 512)
  private String error;

  @Column(name = "sent_at")
  private Instant sentAt;

  @Column(name = "resolved_at")
  private Instant resolvedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected NotifyDeliveryEntity() {}

  public NotifyDeliveryEntity(String approvalId, NotifyChannel channel, String target, boolean offeredApprove) {
    this.id = Ids.uuid();
    this.approvalId = Objects.requireNonNull(approvalId, "approvalId");
    this.channel = Objects.requireNonNull(channel, "channel");
    this.target = Objects.requireNonNull(target, "target");
    this.offeredApprove = offeredApprove;
    this.state = DeliveryState.SENDING;
  }

  public String getId() {
    return id;
  }

  public String getApprovalId() {
    return approvalId;
  }

  public NotifyChannel getChannel() {
    return channel;
  }

  public String getTarget() {
    return target;
  }

  public String getExternalRef() {
    return externalRef;
  }

  public boolean isOfferedApprove() {
    return offeredApprove;
  }

  public DeliveryState getState() {
    return state;
  }

  public void sent(String externalRef, Instant at) {
    this.externalRef = externalRef;
    this.state = DeliveryState.SENT;
    this.sentAt = Objects.requireNonNull(at, "at");
    this.error = null;
  }

  public void resolved(Instant at) {
    this.state = DeliveryState.RESOLVED;
    this.resolvedAt = Objects.requireNonNull(at, "at");
  }

  public void failed(String error) {
    this.state = DeliveryState.FAILED;
    this.error = error;
  }

  public String getError() {
    return error;
  }

  public Instant getSentAt() {
    return sentAt;
  }

  public Instant getResolvedAt() {
    return resolvedAt;
  }

  public long getVersion() {
    return version;
  }
}
