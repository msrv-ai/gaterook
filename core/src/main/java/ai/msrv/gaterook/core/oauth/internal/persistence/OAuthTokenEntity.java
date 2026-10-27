package ai.msrv.gaterook.core.oauth.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * An issued access or refresh token, by {@code b64url(sha256)} only. A refresh token is consumed on use and replaced;
 * presenting a consumed one revokes the whole grant (refresh token reuse detection).
 */
@Entity
@Table(name = "oauth_token")
public class OAuthTokenEntity {

  @Id
  @Column(name = "hash", length = 44, nullable = false, updatable = false)
  private String hash;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "grant_id", nullable = false, updatable = false)
  private OAuthGrantEntity grant;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", length = 8, nullable = false, updatable = false)
  private OAuthTokenKind kind;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @Column(name = "consumed_at")
  private Instant consumedAt;

  @Column(name = "replaced_by", length = 44)
  private String replacedBy;

  protected OAuthTokenEntity() {}

  public OAuthTokenEntity(String hash, OAuthGrantEntity grant, OAuthTokenKind kind, Instant now, Instant expiresAt) {
    this.hash = Objects.requireNonNull(hash, "hash");
    this.grant = Objects.requireNonNull(grant, "grant");
    this.kind = Objects.requireNonNull(kind, "kind");
    this.createdAt = Objects.requireNonNull(now, "now");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
  }

  public String getHash() {
    return hash;
  }

  public OAuthGrantEntity getGrant() {
    return grant;
  }

  public OAuthTokenKind getKind() {
    return kind;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getConsumedAt() {
    return consumedAt;
  }

  public String getReplacedBy() {
    return replacedBy;
  }

  /** A refresh token was exchanged for {@code replacementHash}. */
  public void consume(Instant now, String replacementHash) {
    this.consumedAt = Objects.requireNonNull(now, "now");
    this.replacedBy = replacementHash;
  }
}
