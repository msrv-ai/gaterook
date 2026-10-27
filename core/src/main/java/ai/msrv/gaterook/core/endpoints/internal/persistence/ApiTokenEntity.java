package ai.msrv.gaterook.core.endpoints.internal.persistence;

import ai.msrv.gaterook.core.platform.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;

/**
 * An endpoint token. Only {@code b64url(sha256(token))} is stored and it is looked up on every request, so revocation
 * is immediate (A4); the value itself exists only in the one response that issued it.
 */
@Entity
@Table(name = "api_token")
public class ApiTokenEntity {

  @Id
  @Column(name = "id", length = 40, nullable = false, updatable = false)
  private String id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "endpoint_id", nullable = false, updatable = false)
  private EndpointEntity endpoint;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", length = 8, nullable = false, updatable = false)
  private TokenKind kind;

  @Column(name = "name", length = 40, updatable = false)
  private String name;

  /** {@code gwt_K9xP}, what people see of a named token; {@code null} for bridge tokens. */
  @Column(name = "prefix", length = 12, updatable = false)
  private String prefix;

  @Column(name = "hash", length = 44, nullable = false, unique = true, updatable = false)
  private String hash;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "grant_id", updatable = false)
  private ClientGrantEntity grant;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @Column(name = "last_used_at")
  private Instant lastUsedAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "revoke_reason", length = 64)
  private String revokeReason;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected ApiTokenEntity() {}

  private ApiTokenEntity(EndpointEntity endpoint, TokenKind kind, String name, String prefix, String hash,
      ClientGrantEntity grant, Instant now, Instant expiresAt) {
    this.id = Ids.next("tok_");
    this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
    this.kind = kind;
    this.name = name;
    this.prefix = prefix;
    this.hash = Objects.requireNonNull(hash, "hash");
    this.grant = grant;
    this.createdAt = Objects.requireNonNull(now, "now");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
  }

  /** A {@code gwt_} token a person names and stores. */
  public static ApiTokenEntity named(EndpointEntity endpoint, String name, String prefix, String hash, Instant now,
      Instant expiresAt) {
    return new ApiTokenEntity(endpoint, TokenKind.NAMED, Objects.requireNonNull(name, "name"),
        Objects.requireNonNull(prefix, "prefix"), hash, null, now, expiresAt);
  }

  /** A {@code gwb_} token issued on the fly for a grant. */
  public static ApiTokenEntity bridge(ClientGrantEntity grant, String hash, Instant now, Instant expiresAt) {
    Objects.requireNonNull(grant, "grant");
    return new ApiTokenEntity(grant.getEndpoint(), TokenKind.BRIDGE, null, null, hash, grant, now, expiresAt);
  }

  public String getId() {
    return id;
  }

  public EndpointEntity getEndpoint() {
    return endpoint;
  }

  public TokenKind getKind() {
    return kind;
  }

  public String getName() {
    return name;
  }

  public String getPrefix() {
    return prefix;
  }

  public String getHash() {
    return hash;
  }

  public ClientGrantEntity getGrant() {
    return grant;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getLastUsedAt() {
    return lastUsedAt;
  }

  public void setLastUsedAt(Instant lastUsedAt) {
    this.lastUsedAt = lastUsedAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public String getRevokeReason() {
    return revokeReason;
  }

  public void revoke(Instant now, String reason) {
    if (revokedAt == null) {
      this.revokedAt = Objects.requireNonNull(now, "now");
      this.revokeReason = reason;
    }
  }

  /** Whether the token may authenticate a call at {@code now}. */
  public boolean isLive(Instant now) {
    return revokedAt == null && now.isBefore(expiresAt);
  }

  public long getVersion() {
    return version;
  }
}
