package ai.msrv.gaterook.core.platform.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row of {@code setting}: a key and any JSON document (an object for a Settings section, a string for
 * {@code core.instance-id}, an array for {@code activity.saved-views}). The value is kept as JSON text because a
 * {@code Map} column can't hold a top-level scalar or array; {@code SettingStore} parses it into a tree.
 */
@Entity
@Table(name = "setting")
public class SettingEntity {

  @Id
  @Column(name = "key", length = 128, nullable = false, updatable = false)
  private String key;

  @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
  @Column(name = "value", nullable = false)
  private String value;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected SettingEntity() {}

  public SettingEntity(String key, String json, Instant now) {
    this.key = Objects.requireNonNull(key, "key");
    this.value = Objects.requireNonNull(json, "json");
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public String getKey() {
    return key;
  }

  /** The value as JSON text. */
  public String getValue() {
    return value;
  }

  public void setValue(String json, Instant now) {
    this.value = Objects.requireNonNull(json, "json");
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public long getVersion() {
    return version;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
