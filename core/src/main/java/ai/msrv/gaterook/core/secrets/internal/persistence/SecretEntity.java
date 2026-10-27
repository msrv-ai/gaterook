package ai.msrv.gaterook.core.secrets.internal.persistence;

import ai.msrv.gaterook.core.platform.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;

/**
 * One secret field of one owner ({@code dev-db/password}): its kind and, for references, where it lives. Values never
 * sit here: local values are {@link SecretVersionEntity} ciphertexts, references are resolved from the store on use.
 * The owner is named by kind and id rather than a foreign key, because owners live in several tables.
 */
@Entity
@Table(name = "secret")
public class SecretEntity {

  @Id
  @Column(name = "id", length = 36, nullable = false, updatable = false)
  private String id;

  @Column(name = "name", length = 128, nullable = false, unique = true)
  private String name;

  @Convert(converter = SecretKind.Converter.class)
  @Column(name = "kind", length = 12, nullable = false)
  private SecretKind kind;

  @Convert(converter = SecretOwnerKind.Converter.class)
  @Column(name = "owner_kind", length = 16, nullable = false, updatable = false)
  private SecretOwnerKind ownerKind;

  @Column(name = "owner_id", length = 36, nullable = false, updatable = false)
  private String ownerId;

  /** JSON pointer of the field in the owner's config, e.g. {@code /credentials/password}. */
  @Column(name = "field", length = 256, nullable = false, updatable = false)
  private String field;

  @Column(name = "ref_uri", length = 1024)
  private String refUri;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "store_id")
  private ExternalStoreEntity store;

  @Column(name = "current_version")
  private Integer currentVersion;

  @Column(name = "remote_version", length = 64)
  private String remoteVersion;

  @Column(name = "remote_checked_at")
  private Instant remoteCheckedAt;

  @Column(name = "rotated_at")
  private Instant rotatedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected SecretEntity() {}

  public SecretEntity(String name, SecretKind kind, SecretOwnerKind ownerKind, String ownerId, String field,
      Instant now) {
    this.id = Ids.uuid();
    this.name = Objects.requireNonNull(name, "name");
    this.kind = Objects.requireNonNull(kind, "kind");
    this.ownerKind = Objects.requireNonNull(ownerKind, "ownerKind");
    this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
    this.field = Objects.requireNonNull(field, "field");
    this.createdAt = Objects.requireNonNull(now, "now");
  }

  public String getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = Objects.requireNonNull(name, "name");
  }

  public SecretKind getKind() {
    return kind;
  }

  public SecretOwnerKind getOwnerKind() {
    return ownerKind;
  }

  public String getOwnerId() {
    return ownerId;
  }

  public String getField() {
    return field;
  }

  public String getRefUri() {
    return refUri;
  }

  public ExternalStoreEntity getStore() {
    return store;
  }

  /** Makes this secret a reference (or a dynamic secret) into {@code store}. */
  public void pointTo(SecretKind kind, ExternalStoreEntity store, String refUri) {
    this.kind = Objects.requireNonNull(kind, "kind");
    this.store = store;
    this.refUri = refUri;
  }

  public Integer getCurrentVersion() {
    return currentVersion;
  }

  public void setCurrentVersion(Integer currentVersion) {
    this.currentVersion = currentVersion;
  }

  public String getRemoteVersion() {
    return remoteVersion;
  }

  public Instant getRemoteCheckedAt() {
    return remoteCheckedAt;
  }

  public void remoteChecked(String remoteVersion, Instant at) {
    this.remoteVersion = remoteVersion;
    this.remoteCheckedAt = at;
  }

  public Instant getRotatedAt() {
    return rotatedAt;
  }

  public void setRotatedAt(Instant rotatedAt) {
    this.rotatedAt = rotatedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public long getVersion() {
    return version;
  }
}
