package ai.msrv.gaterook.core.endpoints.internal.persistence;

import ai.msrv.gaterook.core.platform.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.OptimisticLock;

/**
 * A grant ({@code gr_…}) lets one client on this computer obtain 15-minute bridge tokens for one endpoint
 * (ARCHITECTURE.md §7.2). Only its id goes into the client's config; revoking it stops new tokens and kills the live
 * ones on the next call.
 */
@Entity
@Table(name = "client_grant")
public class ClientGrantEntity {

  @Id
  @Column(name = "id", length = 40, nullable = false, updatable = false)
  private String id;

  /** {@code claude-desktop}, {@code codex}, {@code cursor} or {@code antigravity}. */
  @Column(name = "client", length = 16, nullable = false, updatable = false)
  private String client;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "endpoint_id", nullable = false, updatable = false)
  private EndpointEntity endpoint;

  /** Trusted label shown in Activity: {@code codex-laptop}. */
  @Column(name = "label", length = 40, nullable = false)
  private String label;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  /** The person's production access approval, retained across token renewal and core restarts. */
  @Column(name = "confirmed_at")
  private Instant confirmedAt;

  @Column(name = "last_token_at")
  private Instant lastTokenAt;

  @Column(name = "last_pid")
  private Long lastPid;

  /**
   * HMAC of the row ({@code platform.RowMac}); excluded from optimistic locking, so that writing it after the
   * flush that settled {@code version} doesn't bump the version it signs.
   */
  @OptimisticLock(excluded = true)
  @Column(name = "mac")
  private byte[] mac;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected ClientGrantEntity() {}

  public ClientGrantEntity(String client, EndpointEntity endpoint, String label, Instant now) {
    this(Ids.next("gr_"), client, endpoint, label, now);
  }

  /**
   * A grant whose id was chosen before it existed: Connect shows the exact config entry, grant id included, in its
   * preview, and creates the grant only once that entry is written.
   */
  public ClientGrantEntity(String id, String client, EndpointEntity endpoint, String label, Instant now) {
    if (!Ids.matches("gr_", id)) {
      throw new IllegalArgumentException("Not a grant id: " + id);
    }
    this.id = id;
    this.client = Objects.requireNonNull(client, "client");
    this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
    this.label = Objects.requireNonNull(label, "label");
    this.createdAt = Objects.requireNonNull(now, "now");
  }

  public String getId() {
    return id;
  }

  public String getClient() {
    return client;
  }

  public EndpointEntity getEndpoint() {
    return endpoint;
  }

  public String getLabel() {
    return label;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public boolean isRevoked() {
    return revokedAt != null;
  }

  public void revoke(Instant now) {
    if (revokedAt == null) {
      this.revokedAt = Objects.requireNonNull(now, "now");
    }
  }

  public Instant getLastTokenAt() {
    return lastTokenAt;
  }

  public Instant getConfirmedAt() {
    return confirmedAt;
  }

  public void confirm(Instant now) {
    if (confirmedAt == null) {
      confirmedAt = Objects.requireNonNull(now, "now");
    }
  }

  public void clearConfirmation() {
    confirmedAt = null;
  }

  public Long getLastPid() {
    return lastPid;
  }

  public void tokenIssued(Instant at, Long pid) {
    this.lastTokenAt = at;
    this.lastPid = pid;
  }

  public byte[] getMac() {
    return mac;
  }

  public void setMac(byte[] mac) {
    this.mac = mac;
  }

  public long getVersion() {
    return version;
  }
}
