package ai.msrv.gaterook.core.notifications.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A pending pairing code ({@code /start <code>}, 16 bytes, 10 minutes), by hash only. {@code candidate} is the
 * account that presented the code, waiting for the person's confirmation in the app.
 */
@Entity
@Table(name = "notify_pairing")
public class NotifyPairingEntity {

  @Id
  @Column(name = "code_hash", length = 44, nullable = false, updatable = false)
  private String codeHash;

  @Convert(converter = NotifyChannel.Converter.class)
  @Column(name = "channel", length = 8, nullable = false, updatable = false)
  private NotifyChannel channel;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @Column(name = "consumed_at")
  private Instant consumedAt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "candidate")
  private Map<String, Object> candidate;

  protected NotifyPairingEntity() {}

  public NotifyPairingEntity(String codeHash, NotifyChannel channel, Instant expiresAt) {
    this.codeHash = Objects.requireNonNull(codeHash, "codeHash");
    this.channel = Objects.requireNonNull(channel, "channel");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
  }

  public String getCodeHash() {
    return codeHash;
  }

  public NotifyChannel getChannel() {
    return channel;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getConsumedAt() {
    return consumedAt;
  }

  public Map<String, Object> getCandidate() {
    return candidate;
  }

  /** The account that sent the code; the pairing completes when the person confirms it in the app. */
  public void presentedBy(Map<String, Object> candidate) {
    this.candidate = candidate;
  }

  public void consume(Instant now) {
    this.consumedAt = Objects.requireNonNull(now, "now");
  }
}
