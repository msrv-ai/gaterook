package ai.msrv.gaterook.core.connectors.internal.persistence;

import ai.msrv.gaterook.connector.api.ConnectorManifest;
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
import java.util.Map;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OptimisticLock;
import org.hibernate.type.SqlTypes;

/**
 * A connector type as the catalog shows it ({@code postgres}, {@code jira-service-desk}, {@code mcp-server}). Built-in
 * rows are upserted from the {@code Connector} beans at start; {@code enabled} is how a type is switched off at run
 * time (no {@code @Conditional} beans in native, A13).
 */
@Entity
@Table(name = "connector_type")
public class ConnectorTypeEntity {

  @Id
  @Column(name = "id", length = 48, nullable = false, updatable = false)
  private String id;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", length = 12, nullable = false)
  private ConnectorManifest.Kind kind;

  @Column(name = "impl_version", length = 32, nullable = false)
  private String implVersion;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "manifest", nullable = false)
  private Map<String, Object> manifest;

  @Column(name = "manifest_sha256", length = 64, nullable = false)
  private String manifestSha256;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "plugin_id")
  private PluginEntity plugin;

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

  protected ConnectorTypeEntity() {}

  public ConnectorTypeEntity(String id, ConnectorManifest.Kind kind, String implVersion, Map<String, Object> manifest,
      String manifestSha256, PluginEntity plugin, Instant now) {
    this.id = Objects.requireNonNull(id, "id");
    this.kind = Objects.requireNonNull(kind, "kind");
    this.implVersion = Objects.requireNonNull(implVersion, "implVersion");
    this.manifest = Objects.requireNonNull(manifest, "manifest");
    this.manifestSha256 = Objects.requireNonNull(manifestSha256, "manifestSha256");
    this.plugin = plugin;
    this.enabled = true;
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public String getId() {
    return id;
  }

  public ConnectorManifest.Kind getKind() {
    return kind;
  }

  public String getImplVersion() {
    return implVersion;
  }

  public Map<String, Object> getManifest() {
    return manifest;
  }

  public String getManifestSha256() {
    return manifestSha256;
  }

  /** Replaces the stored manifest, e.g. after an upgrade of the gateway or of a plugin. */
  public void updateManifest(String implVersion, Map<String, Object> manifest, String manifestSha256, Instant now) {
    this.implVersion = Objects.requireNonNull(implVersion, "implVersion");
    this.manifest = Objects.requireNonNull(manifest, "manifest");
    this.manifestSha256 = Objects.requireNonNull(manifestSha256, "manifestSha256");
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled, Instant now) {
    this.enabled = enabled;
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public PluginEntity getPlugin() {
    return plugin;
  }

  /** The plugin a {@code PLUGIN} type comes from ({@code plugin_id}); part of the row's MAC. */
  public void setPlugin(PluginEntity plugin) {
    this.plugin = plugin;
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
