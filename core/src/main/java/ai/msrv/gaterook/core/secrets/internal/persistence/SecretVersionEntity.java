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
import java.time.Instant;
import java.util.Objects;

/**
 * One version of a secret: for a local secret the Tink ciphertext and its data key wrapped by the master keyset
 * (report 12 §7.2); for a reference the URI it pointed to. Destroying a version nulls the wrapped key (crypto-shredding),
 * which is why the row has no optimistic lock: only the secrets service writes it, under the secret's own version.
 */
@Entity
@Table(name = "secret_version")
public class SecretVersionEntity {

  @Id
  @Column(name = "id", length = 36, nullable = false, updatable = false)
  private String id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "secret_id", nullable = false, updatable = false)
  private SecretEntity secret;

  @Column(name = "secret_version", nullable = false, updatable = false)
  private int secretVersion;

  @Convert(converter = SecretVersionState.Converter.class)
  @Column(name = "state", length = 12, nullable = false)
  private SecretVersionState state;

  @Column(name = "ciphertext")
  private byte[] ciphertext;

  @Column(name = "wrapped_dek")
  private byte[] wrappedDek;

  /** Primary key id of the master keyset when the data key was wrapped; drives re-wrapping on rotation. */
  @Column(name = "key_id")
  private Long keyId;

  @Column(name = "enc_format", nullable = false)
  private int encFormat;

  @Column(name = "ref_uri", length = 1024)
  private String refUri;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "destroyed_at")
  private Instant destroyedAt;

  protected SecretVersionEntity() {}

  public SecretVersionEntity(SecretEntity secret, int secretVersion, SecretVersionState state, int encFormat,
      Instant now) {
    this.id = Ids.uuid();
    this.secret = Objects.requireNonNull(secret, "secret");
    this.secretVersion = secretVersion;
    this.state = Objects.requireNonNull(state, "state");
    this.encFormat = encFormat;
    this.createdAt = Objects.requireNonNull(now, "now");
  }

  public String getId() {
    return id;
  }

  public SecretEntity getSecret() {
    return secret;
  }

  public int getSecretVersion() {
    return secretVersion;
  }

  public SecretVersionState getState() {
    return state;
  }

  public void setState(SecretVersionState state) {
    this.state = Objects.requireNonNull(state, "state");
  }

  public byte[] getCiphertext() {
    return ciphertext;
  }

  public byte[] getWrappedDek() {
    return wrappedDek;
  }

  public Long getKeyId() {
    return keyId;
  }

  /** Stores the envelope of a local value. */
  public void seal(byte[] ciphertext, byte[] wrappedDek, long keyId) {
    this.ciphertext = Objects.requireNonNull(ciphertext, "ciphertext");
    this.wrappedDek = Objects.requireNonNull(wrappedDek, "wrappedDek");
    this.keyId = keyId;
  }

  /** Re-wraps the data key under another master key (rotation); the ciphertext stays as it is. */
  public void rewrap(byte[] wrappedDek, long keyId) {
    this.wrappedDek = Objects.requireNonNull(wrappedDek, "wrappedDek");
    this.keyId = keyId;
  }

  /** Crypto-shredding: without the wrapped key the ciphertext can never be decrypted again. */
  public void destroy(Instant now) {
    this.state = SecretVersionState.DESTROYED;
    this.ciphertext = null;
    this.wrappedDek = null;
    this.keyId = null;
    this.destroyedAt = Objects.requireNonNull(now, "now");
  }

  public int getEncFormat() {
    return encFormat;
  }

  public String getRefUri() {
    return refUri;
  }

  public void setRefUri(String refUri) {
    this.refUri = refUri;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getDestroyedAt() {
    return destroyedAt;
  }
}
