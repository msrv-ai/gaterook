package ai.msrv.gaterook.core.templates.internal.persistence;

import ai.msrv.gaterook.core.platform.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.OptimisticLock;

/**
 * A no-code template (SQL query, SSH runbook, OpenAPI import) bound to the connection it runs on. The definition lives
 * in append-only {@link TemplateVersionEntity} rows; {@code currentVersion} picks one, and the version is part of the
 * approval digest, so editing a template after a request was approved stops its execution.
 */
@Entity
@Table(name = "template")
public class TemplateEntity {

  @Id
  @Column(name = "id", length = 40, nullable = false, updatable = false)
  private String id;

  @Convert(converter = TemplateType.Converter.class)
  @Column(name = "kind", length = 16, nullable = false, updatable = false)
  private TemplateType kind;

  @Column(name = "name", length = 64, nullable = false)
  private String name;

  /** "Runs on": a connection of the {@code connections} module, or {@code null} once it was deleted. */
  @Column(name = "connection_id", length = 36)
  private String connectionId;

  @Column(name = "current_version", nullable = false)
  private int currentVersion;

  @Convert(converter = TemplateStatus.Converter.class)
  @Column(name = "status", length = 12, nullable = false)
  private TemplateStatus status;

  @Column(name = "catalog_ref", length = 160)
  private String catalogRef;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /**
   * HMAC of the row ({@code platform.RowMac}: kind, name, Runs on, current version, status); excluded from optimistic
   * locking, so that writing it after the flush that settled {@code version} doesn't bump the version it signs.
   */
  @OptimisticLock(excluded = true)
  @Column(name = "mac")
  private byte[] mac;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected TemplateEntity() {}

  public TemplateEntity(TemplateType kind, String name, String connectionId, Instant now) {
    this.id = Ids.next("tpl_");
    this.kind = Objects.requireNonNull(kind, "kind");
    this.name = Objects.requireNonNull(name, "name");
    this.connectionId = connectionId;
    this.currentVersion = 0;
    this.status = TemplateStatus.ACTIVE;
    this.createdAt = Objects.requireNonNull(now, "now");
    this.updatedAt = now;
  }

  public String getId() {
    return id;
  }

  public TemplateType getKind() {
    return kind;
  }

  public String getName() {
    return name;
  }

  public void setName(String name, Instant now) {
    this.name = Objects.requireNonNull(name, "name");
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public String getConnectionId() {
    return connectionId;
  }

  /** The Runs on connection went away: the template can't compile any more. */
  public void detach(Instant now) {
    this.connectionId = null;
    this.status = TemplateStatus.INVALID;
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public int getCurrentVersion() {
    return currentVersion;
  }

  /** Points the template at a version that was just appended. */
  public void advanceTo(int templateVersion, Instant now) {
    if (templateVersion <= currentVersion) {
      throw new IllegalArgumentException("Template versions only grow: " + currentVersion + " → " + templateVersion);
    }
    this.currentVersion = templateVersion;
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public TemplateStatus getStatus() {
    return status;
  }

  public void setStatus(TemplateStatus status, Instant now) {
    this.status = Objects.requireNonNull(status, "status");
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public String getCatalogRef() {
    return catalogRef;
  }

  public void setCatalogRef(String catalogRef) {
    this.catalogRef = catalogRef;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public long getVersion() {
    return version;
  }

  public byte[] getMac() {
    return mac;
  }

  public void setMac(byte[] mac) {
    this.mac = mac;
  }
}
