package ai.msrv.gaterook.core.secrets.internal.crypto;

import com.google.crypto.tink.Aead;
import com.google.crypto.tink.Configuration;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.TinkProtoKeysetFormat;
import com.google.crypto.tink.aead.AeadConfig2026;
import com.google.crypto.tink.aead.PredefinedAeadParameters;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Objects;

/**
 * Envelope encryption of local secrets (report 12 §7.2, ARCHITECTURE.md A14): every version of every secret gets its
 * own data key (a Tink {@code AES256_GCM} keyset), the value is encrypted with it, and the data key is stored wrapped by
 * the master keyset. The associated data binds both to the secret's id and version, so swapping rows or versions in the
 * database makes decryption fail instead of returning another secret's value.
 *
 * <p>Rotation of the master key only re-wraps data keys ({@link #rewrap}); ciphertexts never change. Tink's
 * {@link AeadConfig2026} is used throughout: no global registry, nothing initialized at image build time.
 */
public final class Envelope {

  /** The Tink configuration of every primitive the gateway makes. */
  public static final Configuration CFG = AeadConfig2026.get();

  /** {@code secret_version.enc_format} of this layout. */
  public static final int FORMAT = 1;

  private Envelope() {}

  /**
   * A sealed value.
   *
   * @param keyId id of the master keyset's primary key that wrapped the data key ({@code secret_version.key_id})
   */
  public record Sealed(byte[] ciphertext, byte[] wrappedDek, long keyId) {}

  /** The AEAD of a master keyset: encrypts with the primary key, decrypts with any enabled key (by key prefix). */
  public static Aead aead(KeysetHandle master) throws GeneralSecurityException {
    return master.getPrimitive(CFG, Aead.class);
  }

  /** The id of the master keyset's primary key, as stored in {@code key_id}. */
  public static long primaryKeyId(KeysetHandle master) {
    return Integer.toUnsignedLong(master.getPrimary().getId());
  }

  public static Sealed seal(KeysetHandle master, String id, int version, byte[] value) throws GeneralSecurityException {
    Objects.requireNonNull(value, "value");
    KeysetHandle dek = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM, CFG);
    byte[] ciphertext = dek.getPrimitive(CFG, Aead.class).encrypt(value, valueAd(id, version));
    byte[] wrapped = TinkProtoKeysetFormat.serializeEncryptedKeyset(dek, aead(master), dekAd(id, version), CFG);
    return new Sealed(ciphertext, wrapped, primaryKeyId(master));
  }

  public static byte[] open(KeysetHandle master, String id, int version, byte[] ciphertext, byte[] wrappedDek)
      throws GeneralSecurityException {
    KeysetHandle dek = TinkProtoKeysetFormat.parseEncryptedKeyset(wrappedDek, aead(master), dekAd(id, version), CFG);
    return dek.getPrimitive(CFG, Aead.class).decrypt(ciphertext, valueAd(id, version));
  }

  /** The same data key, wrapped by the master keyset's current primary key; the ciphertext stays valid. */
  public static Sealed rewrap(KeysetHandle master, String id, int version, byte[] ciphertext, byte[] wrappedDek)
      throws GeneralSecurityException {
    Aead masterAead = aead(master);
    KeysetHandle dek = TinkProtoKeysetFormat.parseEncryptedKeyset(wrappedDek, masterAead, dekAd(id, version), CFG);
    byte[] rewrapped = TinkProtoKeysetFormat.serializeEncryptedKeyset(dek, masterAead, dekAd(id, version), CFG);
    return new Sealed(ciphertext, rewrapped, primaryKeyId(master));
  }

  static byte[] valueAd(String id, int version) {
    return ("mcp-gateway/secret/v1\0" + id + "\0" + version).getBytes(StandardCharsets.UTF_8);
  }

  static byte[] dekAd(String id, int version) {
    return ("mcp-gateway/dek/v1\0" + id + "\0" + version).getBytes(StandardCharsets.UTF_8);
  }
}
