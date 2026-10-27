package ai.msrv.gaterook.core.oauth.internal.persistence;

import ai.msrv.gaterook.core.platform.Ids;
import ai.msrv.gaterook.core.platform.Ingress;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An OAuth client known to one of the two authorization servers: {@code ingress} says which (TUNNEL for claude.ai,
 * LOCAL for Cursor and Antigravity), and a client of one is never accepted by the other (ARCHITECTURE.md §7.3).
 */
@Entity
@Table(name = "oauth_client")
public class OAuthClientEntity {

  @Id
  @Column(name = "id", length = 36, nullable = false, updatable = false)
  private String id;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", length = 4, nullable = false, updatable = false)
  private OAuthClientKind kind;

  @Enumerated(EnumType.STRING)
  @Column(name = "ingress", length = 6, nullable = false, updatable = false)
  private Ingress ingress;

  @Column(name = "client_id", length = 512, nullable = false, unique = true, updatable = false)
  private String clientId;

  @Column(name = "client_name", length = 128)
  private String clientName;

  @Column(name = "client_host", length = 253, nullable = false)
  private String clientHost;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "redirect_uris", nullable = false)
  private List<String> redirectUris;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "last_used_at")
  private Instant lastUsedAt;

  @Column(name = "mac")
  private byte[] mac;

  protected OAuthClientEntity() {}

  public OAuthClientEntity(OAuthClientKind kind, Ingress ingress, String clientId, String clientName,
      String clientHost, List<String> redirectUris, Instant now) {
    if (ingress == Ingress.PLAYGROUND) {
      throw new IllegalArgumentException("OAuth clients belong to the LOCAL or the TUNNEL authorization server");
    }
    this.id = Ids.uuid();
    this.kind = Objects.requireNonNull(kind, "kind");
    this.ingress = Objects.requireNonNull(ingress, "ingress");
    this.clientId = Objects.requireNonNull(clientId, "clientId");
    this.clientName = clientName;
    this.clientHost = Objects.requireNonNull(clientHost, "clientHost");
    this.redirectUris = List.copyOf(redirectUris);
    this.createdAt = Objects.requireNonNull(now, "now");
  }

  public String getId() {
    return id;
  }

  public OAuthClientKind getKind() {
    return kind;
  }

  public Ingress getIngress() {
    return ingress;
  }

  public String getClientId() {
    return clientId;
  }

  public String getClientName() {
    return clientName;
  }

  public String getClientHost() {
    return clientHost;
  }

  public List<String> getRedirectUris() {
    return redirectUris;
  }

  /** A refreshed metadata document (CIMD clients are re-fetched, cached 60 s…24 h). */
  public void refresh(String clientName, String clientHost, List<String> redirectUris) {
    this.clientName = clientName;
    this.clientHost = Objects.requireNonNull(clientHost, "clientHost");
    this.redirectUris = List.copyOf(redirectUris);
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getLastUsedAt() {
    return lastUsedAt;
  }

  public void setLastUsedAt(Instant lastUsedAt) {
    this.lastUsedAt = lastUsedAt;
  }

  public byte[] getMac() {
    return mac;
  }

  public void setMac(byte[] mac) {
    this.mac = mac;
  }
}
