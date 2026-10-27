package ai.msrv.gaterook.core.connectors.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OptimisticLock;
import org.hibernate.type.SqlTypes;

/**
 * An installed Java plugin ({@code jira-service-desk}). The manifest is stored so that {@code tools/list} never has to
 * start the plugin host; the previous version is kept for 7 days for Roll back (ARCHITECTURE.md §7.5).
 */
@Entity
@Table(name = "plugin")
public class PluginEntity {

  @Id
  @Column(name = "id", length = 48, nullable = false, updatable = false)
  private String id;

  @Column(name = "current_version", length = 32, nullable = false)
  private String currentVersion;

  @Column(name = "previous_version", length = 32)
  private String previousVersion;

  @Column(name = "previous_kept_until")
  private Instant previousKeptUntil;

  /** The SHA-256 of the previous version's JAR, which a Roll back must find unchanged (V50). */
  @Column(name = "previous_sha256", length = 64)
  private String previousSha256;

  @Convert(converter = PluginState.Converter.class)
  @Column(name = "state", length = 16, nullable = false)
  private PluginState state;

  @Column(name = "signed", nullable = false)
  private boolean signed;

  @Convert(converter = PluginSource.Converter.class)
  @Column(name = "source", length = 8, nullable = false)
  private PluginSource source;

  @Column(name = "sha256", length = 64, nullable = false)
  private String sha256;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "manifest", nullable = false)
  private Map<String, Object> manifest;

  @Column(name = "catalog_entry", length = 128)
  private String catalogEntry;

  @Column(name = "installed_at", nullable = false, updatable = false)
  private Instant installedAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

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

  protected PluginEntity() {}

  public PluginEntity(String id, String currentVersion, boolean signed, PluginSource source, String sha256,
      long sizeBytes, Map<String, Object> manifest, Instant now) {
    this.id = Objects.requireNonNull(id, "id");
    this.currentVersion = Objects.requireNonNull(currentVersion, "currentVersion");
    this.state = PluginState.INSTALLED;
    this.signed = signed;
    this.source = Objects.requireNonNull(source, "source");
    this.sha256 = Objects.requireNonNull(sha256, "sha256");
    this.sizeBytes = sizeBytes;
    this.manifest = Objects.requireNonNull(manifest, "manifest");
    this.installedAt = Objects.requireNonNull(now, "now");
    this.updatedAt = now;
  }

  public String getId() {
    return id;
  }

  public String getCurrentVersion() {
    return currentVersion;
  }

  public String getPreviousVersion() {
    return previousVersion;
  }

  public Instant getPreviousKeptUntil() {
    return previousKeptUntil;
  }

  public String getPreviousSha256() {
    return previousSha256;
  }

  /** Switches to a new generation and keeps the old one for Roll back until {@code keepPreviousUntil}. */
  public void upgrade(String newVersion, boolean signed, PluginSource source, String sha256, long sizeBytes,
      Map<String, Object> manifest, Instant keepPreviousUntil, Instant now) {
    this.previousVersion = this.currentVersion;
    this.previousSha256 = this.sha256;
    this.previousKeptUntil = keepPreviousUntil;
    this.currentVersion = Objects.requireNonNull(newVersion, "newVersion");
    this.signed = signed;
    this.source = Objects.requireNonNull(source, "source");
    this.sha256 = Objects.requireNonNull(sha256, "sha256");
    this.sizeBytes = sizeBytes;
    this.manifest = Objects.requireNonNull(manifest, "manifest");
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public PluginState getState() {
    return state;
  }

  public void setState(PluginState state, Instant now) {
    this.state = Objects.requireNonNull(state, "state");
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public boolean isSigned() {
    return signed;
  }

  public PluginSource getSource() {
    return source;
  }

  public String getSha256() {
    return sha256;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public Map<String, Object> getManifest() {
    return manifest;
  }

  public String getCatalogEntry() {
    return catalogEntry;
  }

  public void setCatalogEntry(String catalogEntry) {
    this.catalogEntry = catalogEntry;
  }

  public Instant getInstalledAt() {
    return installedAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
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
