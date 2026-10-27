package ai.msrv.gaterook.core.policy.internal.persistence;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.platform.EnvConverter;
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
 * The policy of one environment (table {@code policy}). {@code sections} holds the connector sections and the core's
 * own ({@code sql, ssh, k8s, cloud, http, masking, limits, approvals}); merging with {@code x-stricter} is the
 * {@code policy} module's job, this row only stores what the person set.
 */
@Entity
@Table(name = "policy")
public class PolicyEntity {

  /** The env id ({@code dev}); a String key because JPA applies no converter to identifiers. */
  @Id
  @Column(name = "env", length = 8, nullable = false, updatable = false)
  private String env;

  @Convert(converter = EnvConverter.class)
  @Column(name = "inherits", length = 8)
  private Env inherits;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "sections", nullable = false)
  private Map<String, Object> sections;

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

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected PolicyEntity() {}

  public PolicyEntity(Env env, Env inherits, Map<String, Object> sections, Instant now) {
    this.env = Objects.requireNonNull(env, "env").id();
    this.inherits = inherits;
    this.sections = Objects.requireNonNull(sections, "sections");
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public Env getEnv() {
    return Env.of(env);
  }

  public Env getInherits() {
    return inherits;
  }

  public Map<String, Object> getSections() {
    return sections;
  }

  public void update(Env inherits, Map<String, Object> sections, Instant now) {
    this.inherits = inherits;
    this.sections = Objects.requireNonNull(sections, "sections");
    this.updatedAt = Objects.requireNonNull(now, "now");
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

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
