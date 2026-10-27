package ai.msrv.gaterook.core.secrets.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * A lease of dynamic credentials issued by an external store (Vault). Kept so that leases orphaned by a crash are
 * revoked at the next start; the lease id is not a secret.
 */
@Entity
@Table(name = "secret_lease")
public class SecretLeaseEntity {

  @Id
  @Column(name = "lease_id", length = 256, nullable = false, updatable = false)
  private String leaseId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "store_id", nullable = false, updatable = false)
  private ExternalStoreEntity store;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "secret_id", nullable = false, updatable = false)
  private SecretEntity secret;

  @Column(name = "issued_at", nullable = false, updatable = false)
  private Instant issuedAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  protected SecretLeaseEntity() {}

  public SecretLeaseEntity(String leaseId, ExternalStoreEntity store, SecretEntity secret, Instant issuedAt,
      Instant expiresAt) {
    this.leaseId = Objects.requireNonNull(leaseId, "leaseId");
    this.store = Objects.requireNonNull(store, "store");
    this.secret = Objects.requireNonNull(secret, "secret");
    this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
  }

  public String getLeaseId() {
    return leaseId;
  }

  public ExternalStoreEntity getStore() {
    return store;
  }

  public SecretEntity getSecret() {
    return secret;
  }

  public Instant getIssuedAt() {
    return issuedAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void renewedUntil(Instant expiresAt) {
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public void revoked(Instant at) {
    this.revokedAt = Objects.requireNonNull(at, "at");
  }
}
