package ai.msrv.gaterook.core.endpoints.internal.persistence;

import ai.msrv.gaterook.core.platform.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/** A connection wired into an endpoint. Created and deleted, never changed: the row has no optimistic lock. */
@Entity
@Table(name = "endpoint_connection")
public class EndpointConnectionEntity {

  @Id
  @Column(name = "id", length = 36, nullable = false, updatable = false)
  private String id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "endpoint_id", nullable = false, updatable = false)
  private EndpointEntity endpoint;

  /** A connection of the {@code connections} module. */
  @Column(name = "connection_id", length = 36, nullable = false, updatable = false)
  private String connectionId;

  @Column(name = "added_at", nullable = false, updatable = false)
  private Instant addedAt;

  @Column(name = "mac")
  private byte[] mac;

  protected EndpointConnectionEntity() {}

  public EndpointConnectionEntity(EndpointEntity endpoint, String connectionId, Instant now) {
    this.id = Ids.uuid();
    this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
    this.connectionId = Objects.requireNonNull(connectionId, "connectionId");
    this.addedAt = Objects.requireNonNull(now, "now");
  }

  public String getId() {
    return id;
  }

  public EndpointEntity getEndpoint() {
    return endpoint;
  }

  public String getConnectionId() {
    return connectionId;
  }

  public Instant getAddedAt() {
    return addedAt;
  }

  public byte[] getMac() {
    return mac;
  }

  public void setMac(byte[] mac) {
    this.mac = mac;
  }
}
