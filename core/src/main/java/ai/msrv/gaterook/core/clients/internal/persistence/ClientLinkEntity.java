package ai.msrv.gaterook.core.clients.internal.persistence;

import ai.msrv.gaterook.core.platform.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;

/**
 * What one Connect wrote into one client's configuration: the file, the entry, the SHA-256 of the bytes we wrote
 * (drift detection) and the backup. Endpoint, grant and token belong to the {@code endpoints} module and are referenced
 * by id.
 */
@Entity
@Table(name = "client_link")
public class ClientLinkEntity {

  @Id
  @Column(name = "id", length = 36, nullable = false, updatable = false)
  private String id;

  @Convert(converter = ClientKind.Converter.class)
  @Column(name = "client", length = 16, nullable = false, updatable = false)
  private ClientKind client;

  @Column(name = "endpoint_id", length = 36, nullable = false, updatable = false)
  private String endpointId;

  @Convert(converter = LinkMethod.Converter.class)
  @Column(name = "method", length = 16, nullable = false, updatable = false)
  private LinkMethod method;

  @Column(name = "config_path", length = 1024)
  private String configPath;

  @Column(name = "entry_name", length = 48)
  private String entryName;

  @Column(name = "grant_id", length = 40)
  private String grantId;

  @Column(name = "token_id", length = 40)
  private String tokenId;

  @Column(name = "written_sha256", length = 64)
  private String writtenSha256;

  @Column(name = "backup_path", length = 1024)
  private String backupPath;

  @Column(name = "status", length = 16, nullable = false)
  private String status;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "removed_at")
  private Instant removedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected ClientLinkEntity() {}

  public ClientLinkEntity(ClientKind client, String endpointId, LinkMethod method, String status, Instant now) {
    this.id = Ids.uuid();
    this.client = Objects.requireNonNull(client, "client");
    this.endpointId = Objects.requireNonNull(endpointId, "endpointId");
    this.method = Objects.requireNonNull(method, "method");
    this.status = Objects.requireNonNull(status, "status");
    this.createdAt = Objects.requireNonNull(now, "now");
  }

  public String getId() {
    return id;
  }

  public ClientKind getClient() {
    return client;
  }

  public String getEndpointId() {
    return endpointId;
  }

  public LinkMethod getMethod() {
    return method;
  }

  public String getConfigPath() {
    return configPath;
  }

  public String getEntryName() {
    return entryName;
  }

  /** Where and what was written; {@code writtenSha256} is the hash of the whole file after our edit. */
  public void written(String configPath, String entryName, String writtenSha256, String backupPath) {
    this.configPath = configPath;
    this.entryName = entryName;
    this.writtenSha256 = writtenSha256;
    this.backupPath = backupPath;
  }

  public String getGrantId() {
    return grantId;
  }

  public void setGrantId(String grantId) {
    this.grantId = grantId;
  }

  public String getTokenId() {
    return tokenId;
  }

  public void setTokenId(String tokenId) {
    this.tokenId = tokenId;
  }

  public String getWrittenSha256() {
    return writtenSha256;
  }

  public String getBackupPath() {
    return backupPath;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = Objects.requireNonNull(status, "status");
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getRemovedAt() {
    return removedAt;
  }

  public void removed(Instant now) {
    this.removedAt = Objects.requireNonNull(now, "now");
  }

  public long getVersion() {
    return version;
  }
}
