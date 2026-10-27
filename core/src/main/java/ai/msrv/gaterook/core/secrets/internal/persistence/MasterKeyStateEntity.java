package ai.msrv.gaterook.core.secrets.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The single row ({@code id = 1}) of facts about the master key that live in the database: the last backup and
 * which key ids it covered, and the target of a rotation in progress (a crash resumes it). The key itself is never
 * here (ARCHITECTURE.md A14).
 */
@Entity
@Table(name = "master_key_state")
public class MasterKeyStateEntity {

  /** The only id the CHECK constraint allows. */
  public static final int ID = 1;

  @Id
  @Column(name = "id", columnDefinition = "integer", nullable = false, updatable = false)
  private Integer id;

  @Column(name = "last_backup_at")
  private Instant lastBackupAt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "last_backup_key_ids")
  private List<Long> lastBackupKeyIds;

  @Column(name = "rotation_target_key_id")
  private Long rotationTargetKeyId;

  /** When the gateway made the key in use (Secrets: "Created Jul 14"); {@code null} for a key made before V4. */
  @Column(name = "key_created_at")
  private Instant keyCreatedAt;

  /** When it last rotated the key ("never rotated" while {@code null}). */
  @Column(name = "key_rotated_at")
  private Instant keyRotatedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  public MasterKeyStateEntity() {
    this.id = ID;
  }

  public Integer getId() {
    return id;
  }

  public Instant getLastBackupAt() {
    return lastBackupAt;
  }

  public List<Long> getLastBackupKeyIds() {
    return lastBackupKeyIds;
  }

  public void backedUp(Instant at, List<Long> keyIds) {
    this.lastBackupAt = at;
    this.lastBackupKeyIds = List.copyOf(keyIds);
  }

  public Long getRotationTargetKeyId() {
    return rotationTargetKeyId;
  }

  public void setRotationTargetKeyId(Long rotationTargetKeyId) {
    this.rotationTargetKeyId = rotationTargetKeyId;
  }

  public Instant getKeyCreatedAt() {
    return keyCreatedAt;
  }

  public Instant getKeyRotatedAt() {
    return keyRotatedAt;
  }

  /** A new key: made now, never rotated. */
  public void keyCreated(Instant at) {
    this.keyCreatedAt = at;
    this.keyRotatedAt = null;
  }

  public void keyRotated(Instant at) {
    this.keyRotatedAt = at;
  }

  public long getVersion() {
    return version;
  }
}
