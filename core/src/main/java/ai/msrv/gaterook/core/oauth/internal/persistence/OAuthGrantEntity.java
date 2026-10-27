package ai.msrv.gaterook.core.oauth.internal.persistence;

import ai.msrv.gaterook.core.platform.Ids;
import ai.msrv.gaterook.core.platform.Ingress;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
 * A person's consent ({@code og_…}): one client may call one endpoint through one ingress until {@code expiresAt}
 * (7 days). It is the principal of approvals and rate limits for OAuth calls, never a rotating token.
 */
@Entity
@Table(name = "oauth_grant")
public class OAuthGrantEntity {

  @Id
  @Column(name = "id", length = 40, nullable = false, updatable = false)
  private String id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "oauth_client_id", nullable = false, updatable = false)
  private OAuthClientEntity client;

  /** An endpoint of the {@code endpoints} module. */
  @Column(name = "endpoint_id", length = 36, nullable = false, updatable = false)
  private String endpointId;

  @Enumerated(EnumType.STRING)
  @Column(name = "ingress", length = 6, nullable = false, updatable = false)
  private Ingress ingress;

  /** RFC 8707 resource the tokens are bound to ({@code https://<host>/mcp/<slug>}). */
  @Column(name = "resource", length = 512, nullable = false, updatable = false)
  private String resource;

  @Column(name = "scope", length = 128, nullable = false, updatable = false)
  private String scope;

  @Convert(converter = OAuthGrantState.Converter.class)
  @Column(name = "state", length = 16, nullable = false)
  private OAuthGrantState state;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "revoke_reason", length = 64)
  private String revokeReason;

  @Column(name = "last_used_at")
  private Instant lastUsedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected OAuthGrantEntity() {}

  public OAuthGrantEntity(OAuthClientEntity client, String endpointId, String resource, String scope, Instant now,
      Instant expiresAt) {
    this.id = Ids.next("og_");
    this.client = Objects.requireNonNull(client, "client");
    this.endpointId = Objects.requireNonNull(endpointId, "endpointId");
    this.ingress = client.getIngress();
    this.resource = Objects.requireNonNull(resource, "resource");
    this.scope = Objects.requireNonNull(scope, "scope");
    this.state = OAuthGrantState.ACTIVE;
    this.createdAt = Objects.requireNonNull(now, "now");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
  }

  public String getId() {
    return id;
  }

  public OAuthClientEntity getClient() {
    return client;
  }

  public String getEndpointId() {
    return endpointId;
  }

  public Ingress getIngress() {
    return ingress;
  }

  public String getResource() {
    return resource;
  }

  public String getScope() {
    return scope;
  }

  public OAuthGrantState getState() {
    return state;
  }

  public void setState(OAuthGrantState state) {
    this.state = Objects.requireNonNull(state, "state");
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
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
      this.state = OAuthGrantState.REVOKED;
    }
  }

  public Instant getLastUsedAt() {
    return lastUsedAt;
  }

  public void setLastUsedAt(Instant lastUsedAt) {
    this.lastUsedAt = lastUsedAt;
  }

  public long getVersion() {
    return version;
  }
}
