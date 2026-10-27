package ai.msrv.gaterook.core.connections.internal.persistence;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.core.platform.EnvConverter;
import ai.msrv.gaterook.core.platform.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * A configured resource ({@code dev-db}). {@code config} is non-secret: secret fields appear only as
 * {@code {"$secret": "<id>"}}. {@code version} is also the {@code ConnectionConfig.version()} connectors key their
 * pools on, so every edit, secret rotation and policy change must bump it (ARCHITECTURE.md §3).
 *
 * <p>{@code type} names a {@code connector_type} row of the {@code connectors} module; it is a plain column, not an
 * association, because modules never share entities.
 */
@Entity
@Table(name = "connection")
public class ConnectionEntity {

  @Id
  @Column(name = "id", length = 36, nullable = false, updatable = false)
  private String id;

  @Column(name = "name", length = 64, nullable = false, unique = true)
  private String name;

  @Column(name = "type", length = 48, nullable = false, updatable = false)
  private String type;

  @Convert(converter = EnvConverter.class)
  @Column(name = "env", length = 8, nullable = false)
  private Env env;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "config", nullable = false)
  private Map<String, Object> config;

  /** {@code TestResult.metadataUpdate}: a non-secret snapshot {@code classify()} may use. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "metadata")
  private Map<String, Object> metadata;

  @Enumerated(EnumType.STRING)
  @Column(name = "health_state", length = 12)
  private HealthResult.State healthState;

  @Column(name = "health_latency_ms")
  private Long healthLatencyMs;

  @Column(name = "health_message", length = 512)
  private String healthMessage;

  @Column(name = "health_checked_at")
  private Instant healthCheckedAt;

  /** When the current {@link #healthState} began: kept while checks say the same, set when the state changes. */
  @Column(name = "health_since")
  private Instant healthSince;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

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

  protected ConnectionEntity() {}

  public ConnectionEntity(String name, String type, Env env, Map<String, Object> config, Instant now) {
    this.id = Ids.uuid();
    this.name = Objects.requireNonNull(name, "name");
    this.type = Objects.requireNonNull(type, "type");
    this.env = Objects.requireNonNull(env, "env");
    this.config = Objects.requireNonNull(config, "config");
    this.createdAt = Objects.requireNonNull(now, "now");
    this.updatedAt = now;
  }

  public String getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getType() {
    return type;
  }

  public Env getEnv() {
    return env;
  }

  public Map<String, Object> getConfig() {
    return config;
  }

  /** A new configuration; the optimistic lock bumps {@code version}, which invalidates connector pools. */
  public void reconfigure(Env env, Map<String, Object> config, Instant now) {
    this.env = Objects.requireNonNull(env, "env");
    this.config = Objects.requireNonNull(config, "config");
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  /** Records a change that doesn't touch the row's own fields (secret rotated, policy changed) as a new version. */
  public void touch(Instant now) {
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public Map<String, Object> getMetadata() {
    return metadata;
  }

  public void setMetadata(Map<String, Object> metadata) {
    this.metadata = metadata;
  }

  public HealthResult.State getHealthState() {
    return healthState;
  }

  public Long getHealthLatencyMs() {
    return healthLatencyMs;
  }

  public String getHealthMessage() {
    return healthMessage;
  }

  public Instant getHealthCheckedAt() {
    return healthCheckedAt;
  }

  public Instant getHealthSince() {
    return healthSince;
  }

  public void recordHealth(HealthResult.State state, Long latencyMs, String message, Instant at) {
    if (state != healthState || healthSince == null) {
      this.healthSince = at;
    }
    this.healthState = state;
    this.healthLatencyMs = latencyMs;
    this.healthMessage = message;
    this.healthCheckedAt = at;
  }

  public Instant getCreatedAt() {
    return createdAt;
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
