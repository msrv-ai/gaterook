package ai.msrv.gaterook.core.audit.internal.persistence;

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
import org.hibernate.annotations.OptimisticLock;
import org.hibernate.type.SqlTypes;

/**
 * An audit export destination with its own schedule and cursor. Retention never deletes a row past the smallest
 * {@code lastExportedId} of the destinations: nothing unexported is ever lost.
 *
 * <p>What the person set — kind, name, switch, config, schedule, secret — is versioned and signed ({@code mac},
 * V80), and so is the export position — the cursor and the batch in flight —, which decides what leaves the core and
 * what retention may delete (review K5: a position moved in {@code gateway.db} would silently skip rows). The
 * position moves through the repository's conditional updates, which carry its new MAC; the last attempt, the state
 * and the error only report and stay outside the MAC. Neither is under optimistic locking: a form that read the
 * destination keeps its version.
 */
@Entity
@Table(name = "audit_export")
public class AuditExportEntity {

  @Id
  @Column(name = "id", length = 36, nullable = false, updatable = false)
  private String id;

  @Convert(converter = ExportKind.Converter.class)
  @Column(name = "kind", length = 8, nullable = false, updatable = false)
  private ExportKind kind;

  @Column(name = "name", length = 64, nullable = false, unique = true)
  private String name;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "config", nullable = false)
  private Map<String, Object> config;

  /** {@code stream}, {@code daily@02:00} or {@code hourly}. */
  @Column(name = "schedule", length = 32, nullable = false)
  private String schedule;

  @Column(name = "secret_id", length = 36)
  private String secretId;

  @OptimisticLock(excluded = true)
  @Column(name = "last_exported_id")
  private Long lastExportedId;

  @OptimisticLock(excluded = true)
  @Column(name = "last_exported_at")
  private Instant lastExportedAt;

  /** The last row of the batch being sent, written before it leaves (V80); {@code null} between batches. */
  @OptimisticLock(excluded = true)
  @Column(name = "pending_to_id")
  private Long pendingToId;

  @OptimisticLock(excluded = true)
  @Column(name = "state", length = 16)
  private String state;

  @OptimisticLock(excluded = true)
  @Column(name = "failing_since")
  private Instant failingSince;

  @OptimisticLock(excluded = true)
  @Column(name = "last_attempt_at")
  private Instant lastAttemptAt;

  @OptimisticLock(excluded = true)
  @Column(name = "last_error", length = 1000)
  private String lastError;

  /** {@code platform.RowMac} over what the person set (V80). Not part of optimistic locking. */
  @OptimisticLock(excluded = true)
  @Column(name = "mac")
  private byte[] mac;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected AuditExportEntity() {}

  public AuditExportEntity(ExportKind kind, String name, Map<String, Object> config, String schedule) {
    this.id = Ids.uuid();
    this.kind = Objects.requireNonNull(kind, "kind");
    this.name = Objects.requireNonNull(name, "name");
    this.config = Objects.requireNonNull(config, "config");
    this.schedule = Objects.requireNonNull(schedule, "schedule");
    this.enabled = true;
  }

  public String getId() {
    return id;
  }

  public ExportKind getKind() {
    return kind;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = Objects.requireNonNull(name, "name");
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Map<String, Object> getConfig() {
    return config;
  }

  public void setConfig(Map<String, Object> config) {
    this.config = Objects.requireNonNull(config, "config");
  }

  public String getSchedule() {
    return schedule;
  }

  public void setSchedule(String schedule) {
    this.schedule = Objects.requireNonNull(schedule, "schedule");
  }

  public String getSecretId() {
    return secretId;
  }

  public void setSecretId(String secretId) {
    this.secretId = secretId;
  }

  public Long getLastExportedId() {
    return lastExportedId;
  }

  public Instant getLastExportedAt() {
    return lastExportedAt;
  }

  public Long getPendingToId() {
    return pendingToId;
  }

  /**
   * A batch up to {@code lastId} reached the destination: the cursor of a new entity (tests, the demo world). A
   * stored destination moves its cursor with {@link AuditExportRepository#advance}.
   */
  public void exported(long lastId, Instant at) {
    if (lastExportedId != null && lastId < lastExportedId) {
      throw new IllegalArgumentException("The export cursor only moves forward");
    }
    this.lastExportedId = lastId;
    this.lastExportedAt = at;
    this.lastAttemptAt = at;
    this.pendingToId = null;
    this.state = "ok";
    this.failingSince = null;
    this.lastError = null;
  }

  /**
   * The destination gets the audit again from its first row: what it received can't be vouched for (its row was
   * changed outside the core, the position too perhaps). Copies are told apart by the rows' ids and hashes.
   */
  public void restart() {
    this.lastExportedId = null;
    this.lastExportedAt = null;
    this.pendingToId = null;
  }

  /** An attempt failed; the cursor stays where it was (a new entity: the demo world). */
  public void failed(String error, Instant since, Instant at) {
    this.failingSince = failingSince == null ? since : failingSince;
    this.state = "failing";
    this.lastError = error;
    this.lastAttemptAt = at;
  }

  public String getState() {
    return state;
  }

  public Instant getFailingSince() {
    return failingSince;
  }

  public Instant getLastAttemptAt() {
    return lastAttemptAt;
  }

  public String getLastError() {
    return lastError;
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
