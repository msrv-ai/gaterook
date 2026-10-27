package ai.msrv.gaterook.core.notifications.internal.persistence;

import ai.msrv.gaterook.core.platform.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * A Slack or Telegram account linked to this computer's user. A link becomes active only after the two-step pairing
 * (a "Link" in the DM by that account and the code in the app); clicks are accepted only from linked accounts.
 */
@Entity
@Table(name = "notify_link")
public class NotifyLinkEntity {

  @Id
  @Column(name = "id", length = 36, nullable = false, updatable = false)
  private String id;

  @Convert(converter = NotifyChannel.Converter.class)
  @Column(name = "channel", length = 8, nullable = false, updatable = false)
  private NotifyChannel channel;

  @Column(name = "team_id", length = 32, updatable = false)
  private String teamId;

  @Column(name = "user_id", length = 64, nullable = false, updatable = false)
  private String userId;

  @Column(name = "chat_id", length = 32, updatable = false)
  private String chatId;

  @Column(name = "display", length = 128)
  private String display;

  @Column(name = "linked_at", nullable = false, updatable = false)
  private Instant linkedAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  /**
   * {@code platform.RowMac} over the channel, the account, its chat and the link's dates (V30, K2): a linked account
   * may decide from its messages, so a link written around the core must not count.
   */
  @Column(name = "mac")
  private byte[] mac;

  protected NotifyLinkEntity() {}

  public NotifyLinkEntity(NotifyChannel channel, String teamId, String userId, String chatId, String display,
      Instant now) {
    if (channel == NotifyChannel.DESKTOP) {
      throw new IllegalArgumentException("The desktop channel has no linked accounts");
    }
    this.id = Ids.uuid();
    this.channel = channel;
    this.teamId = teamId;
    this.userId = Objects.requireNonNull(userId, "userId");
    this.chatId = chatId;
    this.display = display;
    this.linkedAt = Objects.requireNonNull(now, "now");
  }

  public String getId() {
    return id;
  }

  public NotifyChannel getChannel() {
    return channel;
  }

  public String getTeamId() {
    return teamId;
  }

  public String getUserId() {
    return userId;
  }

  public String getChatId() {
    return chatId;
  }

  public String getDisplay() {
    return display;
  }

  public void setDisplay(String display) {
    this.display = display;
  }

  public Instant getLinkedAt() {
    return linkedAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public void revoke(Instant now) {
    if (revokedAt == null) {
      this.revokedAt = Objects.requireNonNull(now, "now");
    }
  }

  public byte[] getMac() {
    return mac;
  }

  public void setMac(byte[] mac) {
    this.mac = mac;
  }
}
