package com.gaterook.core.clients.internal.persistence;

import com.gaterook.core.platform.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/** A user-named app configured outside GateRook; its endpoint token supplies connection evidence. */
@Entity
@Table(name = "manual_client")
public class ManualClientEntity {

  @Id
  @Column(name = "id", length = 36, nullable = false, updatable = false)
  private String id;

  @Column(name = "name", length = 80, nullable = false, updatable = false)
  private String name;

  @Column(name = "endpoint_id", length = 36, nullable = false, updatable = false)
  private String endpointId;

  @Column(name = "token_id", length = 40, nullable = false)
  private String tokenId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected ManualClientEntity() {}

  public ManualClientEntity(String name, String endpointId, Instant createdAt) {
    this.id = Ids.uuid();
    this.name = name;
    this.endpointId = endpointId;
    this.createdAt = createdAt;
  }

  public String getId() { return id; }
  public String getName() { return name; }
  public String getEndpointId() { return endpointId; }
  public String getTokenId() { return tokenId; }
  public Instant getCreatedAt() { return createdAt; }
  public void setTokenId(String tokenId) { this.tokenId = tokenId; }
}
