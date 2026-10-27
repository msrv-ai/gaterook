package ai.msrv.gaterook.core.secrets.internal.crypto;

import com.google.crypto.tink.Aead;
import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.TinkJsonProtoKeysetFormat;
import com.google.crypto.tink.subtle.AesGcmJce;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Backups of the master keyset (report 12 §7.3, kit Secrets › Master key, onboarding step 1). With a passphrase: the
 * keyset as Tink {@code encryptedKeyset} under AES-GCM with a key from PBKDF2-HMAC-SHA256, 600,000 iterations (OWASP),
 * in a small wrapper {@code {"format":"mcp-gateway/keyset-backup/1","kdf":{…},"keyset":{…}}}. Without one (the kit
 * makes the passphrase optional, with its risk text): the cleartext Tink JSON keyset.
 */
public final class BackupCodec {

  public static final String FORMAT = "mcp-gateway/keyset-backup/1";
  public static final int ITERATIONS = 600_000;
  /**
   * Fewer iterations than this mean a file GateRook didn't write (or a downgrade); more than {@link #MAX_ITERATIONS}
   * would hang the restore dialog for minutes on a crafted file.
   */
  static final int MIN_ITERATIONS = 100_000;
  static final int MAX_ITERATIONS = 10_000_000;
  static final byte[] BACKUP_AD = "mcp-gateway/keyset-backup/v1".getBytes(StandardCharsets.UTF_8);

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private BackupCodec() {}

  /** Why a backup can't be read; the message is for the person. */
  public static final class BackupException extends Exception {
    private static final long serialVersionUID = 1L;

    public BackupException(String message) {
      super(message);
    }
  }

  public static byte[] write(KeysetHandle keyset, char[] passphrase) throws GeneralSecurityException {
    return write(keyset, passphrase, ITERATIONS);
  }

  static byte[] write(KeysetHandle keyset, char[] passphrase, int iterations) throws GeneralSecurityException {
    if (passphrase == null || passphrase.length == 0) {
      return TinkJsonProtoKeysetFormat.serializeKeyset(keyset, Envelope.CFG, InsecureSecretKeyAccess.get())
          .getBytes(StandardCharsets.UTF_8);
    }
    byte[] salt = new byte[16];
    new SecureRandom().nextBytes(salt);
    String encrypted = TinkJsonProtoKeysetFormat.serializeEncryptedKeyset(keyset,
        passphraseAead(passphrase, salt, iterations), BACKUP_AD, Envelope.CFG);
    ObjectNode backup = JSON.createObjectNode();
    backup.put("format", FORMAT);
    ObjectNode kdf = backup.putObject("kdf");
    kdf.put("alg", "PBKDF2-HMAC-SHA256");
    kdf.put("iterations", iterations);
    kdf.put("salt", Base64.getEncoder().encodeToString(salt));
    backup.set("keyset", JSON.readTree(encrypted));
    return JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(backup);
  }

  /** Whether a backup needs a passphrase to be read. */
  public static boolean needsPassphrase(byte[] file) {
    try {
      return FORMAT.equals(JSON.readTree(file).path("format").asString(""));
    } catch (RuntimeException e) {
      return false;
    }
  }

  public static KeysetHandle read(byte[] file, char[] passphrase) throws BackupException {
    return read(file, passphrase, MIN_ITERATIONS);
  }

  /** Tests write cheap backups; {@code minIterations} lets them read those back. */
  static KeysetHandle read(byte[] file, char[] passphrase, int minIterations) throws BackupException {
    JsonNode tree;
    try {
      tree = JSON.readTree(file);
    } catch (RuntimeException e) {
      throw new BackupException("This file isn't a master key backup");
    }
    if (tree == null || !tree.isObject()) {
      throw new BackupException("This file isn't a master key backup");
    }
    if (FORMAT.equals(tree.path("format").asString(""))) {
      if (passphrase == null || passphrase.length == 0) {
        throw new BackupException("This backup is protected by a passphrase. Enter it to restore.");
      }
      JsonNode kdf = tree.path("kdf");
      if (!"PBKDF2-HMAC-SHA256".equals(kdf.path("alg").asString("")) || kdf.path("iterations").asInt(0) < minIterations
          || kdf.path("iterations").asInt(0) > MAX_ITERATIONS) {
        throw new BackupException("This backup uses a key derivation GateRook doesn't accept");
      }
      try {
        byte[] salt = Base64.getDecoder().decode(kdf.path("salt").asString(""));
        return TinkJsonProtoKeysetFormat.parseEncryptedKeyset(JSON.writeValueAsString(tree.path("keyset")),
            passphraseAead(passphrase, salt, kdf.path("iterations").asInt()), BACKUP_AD, Envelope.CFG);
      } catch (GeneralSecurityException | IllegalArgumentException e) {
        throw new BackupException("The passphrase is wrong, or the backup is damaged");
      }
    }
    if (tree.has("encryptedKeyset")) {
      throw new BackupException("This is the OS-protected key file of a computer, not a backup. Restore from the "
          + "file you saved with Back up key.");
    }
    try {
      return TinkJsonProtoKeysetFormat.parseKeyset(JSON.writeValueAsString(tree), Envelope.CFG,
          InsecureSecretKeyAccess.get());
    } catch (GeneralSecurityException | RuntimeException e) {
      throw new BackupException("This file isn't a master key backup");
    }
  }

  private static Aead passphraseAead(char[] passphrase, byte[] salt, int iterations) throws GeneralSecurityException {
    KeySpec spec = new PBEKeySpec(passphrase, salt, iterations, 256);
    byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    try {
      return new AesGcmJce(key);
    } finally {
      Arrays.fill(key, (byte) 0);
      ((PBEKeySpec) spec).clearPassword();
    }
  }
}
