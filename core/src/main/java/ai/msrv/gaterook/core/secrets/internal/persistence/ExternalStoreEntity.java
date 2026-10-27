package ai.msrv.gaterook.core.secrets.internal.persistence;

import ai.msrv.gaterook.core.platform.Ids;
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
import org.hibernate.type.SqlTypes;

/**
 * An external secret store (Vault/OpenBao, GCP Secret Manager, AWS Secrets Manager; Secrets › External stores).
 * {@code config} is non-secret; the store's own sign-in secret is a {@code secret} row with owner kind {@code store},
 * referenced by {@code authSecretId}.
 */
@Entity
@Table(name = "external_store")
public class ExternalStoreEntity {

  @Id
  @Column(name = "id", length = 36, nullable = false, updatable = false)
  private String id;

  @Convert(converter = StoreKind.Converter.class)
  @Column(name = "kind", length = 8, nullable = false, updatable = false)
  private StoreKind kind;

  @Column(name = "name", length = 64, nullable = false, unique = true)
  private String name;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "config", nullable = false)
  private Map<String, Object> config;

  @Column(name = "auth_secret_id", length = 36)
  private String authSecretId;

  @Column(name = "state", length = 16, nullable = false)
  private String state;

  @Column(name = "last_checked_at")
  private Instant lastCheckedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected ExternalStoreEntity() {}

  public ExternalStoreEntity(StoreKind kind, String name, Map<String, Object> config, String state, Instant now) {
    this.id = Ids.uuid();
    this.kind = Objects.requireNonNull(kind, "kind");
    this.name = Objects.requireNonNull(name, "name");
    this.config = Objects.requireNonNull(config, "config");
    this.state = Objects.requireNonNull(state, "state");
    this.createdAt = Objects.requireNonNull(now, "now");
  }

  public String getId() {
    return id;
  }

  public StoreKind getKind() {
    return kind;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = Objects.requireNonNull(name, "name");
  }

  public Map<String, Object> getConfig() {
    return config;
  }

  public void setConfig(Map<String, Object> config) {
    this.config = Objects.requireNonNull(config, "config");
  }

  public String getAuthSecretId() {
    return authSecretId;
  }

  public void setAuthSecretId(String authSecretId) {
    this.authSecretId = authSecretId;
  }

  public String getState() {
    return state;
  }

  public void setState(String state, Instant checkedAt) {
    this.state = Objects.requireNonNull(state, "state");
    this.lastCheckedAt = checkedAt;
  }

  public Instant getLastCheckedAt() {
    return lastCheckedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public long getVersion() {
    return version;
  }
}
