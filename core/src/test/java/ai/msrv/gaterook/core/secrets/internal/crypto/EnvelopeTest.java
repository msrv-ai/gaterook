package ai.msrv.gaterook.core.secrets.internal.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.aead.PredefinedAeadParameters;
import com.google.crypto.tink.subtle.AesGcmJce;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

/** Report 12 §8.1–8.2: the envelope, rotation, backups and the Tink gotchas, without a database. */
class EnvelopeTest {

  private static final byte[] VALUE = "s3cr3t-dev-db-password".getBytes(StandardCharsets.UTF_8);

  @Test
  void sealsAndOpensWithIdAndVersionBound() throws Exception {
    KeysetHandle master = Keysets.generate();
    Envelope.Sealed sealed = Envelope.seal(master, "id-1", 3, VALUE);

    assertThat(Envelope.open(master, "id-1", 3, sealed.ciphertext(), sealed.wrappedDek())).isEqualTo(VALUE);
    assertThat(sealed.keyId()).isEqualTo(Envelope.primaryKeyId(master));
    assertThat(sealed.ciphertext()).hasSize(VALUE.length + 33);
    assertThat(sealed.wrappedDek().length).isBetween(130, 160);
    // Swapped rows or versions: the associated data no longer matches.
    assertThatThrownBy(() -> Envelope.open(master, "id-2", 3, sealed.ciphertext(), sealed.wrappedDek()))
        .isInstanceOf(GeneralSecurityException.class);
    assertThatThrownBy(() -> Envelope.open(master, "id-1", 4, sealed.ciphertext(), sealed.wrappedDek()))
        .isInstanceOf(GeneralSecurityException.class);
    Envelope.Sealed other = Envelope.seal(master, "id-2", 1, "other".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> Envelope.open(master, "id-1", 3, sealed.ciphertext(), other.wrappedDek()))
        .isInstanceOf(GeneralSecurityException.class);
    assertThatThrownBy(() -> Envelope.open(Keysets.generate(), "id-1", 3, sealed.ciphertext(), sealed.wrappedDek()))
        .isInstanceOf(GeneralSecurityException.class);
  }

  @Test
  void rotationKeepsOldDataKeysOpenUntilRewrapThenDisablesThem() throws Exception {
    KeysetHandle before = Keysets.generate();
    Envelope.Sealed old = Envelope.seal(before, "id-1", 1, VALUE);

    KeysetHandle rotated = Keysets.withNewPrimary(before);
    assertThat(rotated.size()).isEqualTo(2);
    assertThat(Envelope.open(rotated, "id-1", 1, old.ciphertext(), old.wrappedDek())).as("before re-wrap")
        .isEqualTo(VALUE);

    Envelope.Sealed rewrapped = Envelope.rewrap(rotated, "id-1", 1, old.ciphertext(), old.wrappedDek());
    assertThat(rewrapped.ciphertext()).as("the ciphertext never changes").isEqualTo(old.ciphertext());
    assertThat(rewrapped.keyId()).isEqualTo(Envelope.primaryKeyId(rotated)).isNotEqualTo(old.keyId());

    KeysetHandle disabled = Keysets.disableAllButPrimary(rotated);
    assertThat(Envelope.open(disabled, "id-1", 1, rewrapped.ciphertext(), rewrapped.wrappedDek())).isEqualTo(VALUE);
    assertThatThrownBy(() -> Envelope.open(disabled, "id-1", 1, old.ciphertext(), old.wrappedDek()))
        .as("the old wrap no longer opens").isInstanceOf(GeneralSecurityException.class);
    assertThat(Keysets.hasDisabled(disabled)).isTrue();
    assertThat(Keysets.covers(disabled, java.util.List.of(old.keyId()))).isFalse();

    KeysetHandle trimmed = Keysets.deleteDisabled(disabled);
    assertThat(trimmed.size()).isEqualTo(1);
    assertThat(Envelope.open(trimmed, "id-1", 1, rewrapped.ciphertext(), rewrapped.wrappedDek())).isEqualTo(VALUE);
  }

  @Test
  void aBackupFromBeforeARotationDoesntOpenNewDataKeys() throws Exception {
    KeysetHandle before = Keysets.generate();
    byte[] backup = BackupCodec.write(before, null);
    KeysetHandle rotated = Keysets.withNewPrimary(before);
    Envelope.Sealed fresh = Envelope.seal(rotated, "id-9", 1, VALUE);

    KeysetHandle restored = BackupCodec.read(backup, null);
    assertThat(Keysets.covers(restored, java.util.List.of(fresh.keyId()))).isFalse();
    assertThatThrownBy(() -> Envelope.open(restored, "id-9", 1, fresh.ciphertext(), fresh.wrappedDek()))
        .isInstanceOf(GeneralSecurityException.class);
  }

  @Test
  void passphraseBackupsRoundTripAndRefuseAWrongPassphrase() throws Exception {
    KeysetHandle master = Keysets.generate();
    Envelope.Sealed sealed = Envelope.seal(master, "id-1", 1, VALUE);
    byte[] backup = BackupCodec.write(master, "correct horse battery".toCharArray(), 1_000);

    assertThat(new String(backup, StandardCharsets.UTF_8)).contains(BackupCodec.FORMAT).contains("PBKDF2-HMAC-SHA256")
        .contains("encryptedKeyset");
    assertThat(BackupCodec.needsPassphrase(backup)).isTrue();
    assertThatThrownBy(() -> BackupCodec.read(backup, "correct horse battery".toCharArray()))
        .as("a weak derivation is refused").hasMessageContaining("key derivation");
    KeysetHandle restored = BackupCodec.read(backup, "correct horse battery".toCharArray(), 1_000);
    assertThat(Envelope.open(restored, "id-1", 1, sealed.ciphertext(), sealed.wrappedDek())).isEqualTo(VALUE);
    assertThatThrownBy(() -> BackupCodec.read(backup, "wrong".toCharArray(), 1_000))
        .isInstanceOf(BackupCodec.BackupException.class).hasMessageContaining("passphrase is wrong");
    assertThatThrownBy(() -> BackupCodec.read(backup, null)).hasMessageContaining("protected by a passphrase");
    assertThatThrownBy(() -> BackupCodec.read("{\"x\":1}".getBytes(StandardCharsets.UTF_8), null))
        .hasMessageContaining("isn't a master key backup");
    assertThat(BackupCodec.ITERATIONS).isGreaterThanOrEqualTo(600_000);
  }

  @Test
  void theOsProtectedFileOpensOnlyWithItsFileKey() throws Exception {
    java.nio.file.Path dir = java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("target").toAbsolutePath(),
        "keyset-");
    KeysetFile file = new KeysetFile(dir);
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    KeysetHandle master = Keysets.generate();
    file.write(master, new AesGcmJce(key));

    assertThat(java.nio.file.Files.readString(file.path())).contains("encryptedKeyset");
    KeysetFile.Loaded loaded = file.load(new AesGcmJce(key));
    assertThat(loaded.state()).isEqualTo(KeysetFile.State.LOADED);
    assertThat(loaded.encrypted()).isTrue();
    assertThat(Envelope.primaryKeyId(loaded.handle())).isEqualTo(Envelope.primaryKeyId(master));
    byte[] other = new byte[32];
    new SecureRandom().nextBytes(other);
    assertThat(file.load(new AesGcmJce(other)).state()).isEqualTo(KeysetFile.State.INVALID);
    assertThat(file.load(null).problem()).contains("protected by the OS");
    assertThatThrownBy(() -> BackupCodec.read(java.nio.file.Files.readAllBytes(file.path()), null))
        .hasMessageContaining("not a backup");
  }

  @Test
  void aBuilderWithoutConfigurationFailsAsTheReportWarns() throws Exception {
    // Regression guard for report 12 §9.1: without setConfiguration(CFG) the global registry is used and has no
    // key creator, so every keyset change must go through Keysets.
    KeysetHandle master = Keysets.generate();
    assertThatThrownBy(() -> KeysetHandle.newBuilder(master)
        .addEntry(KeysetHandle.generateEntryFromParameters(PredefinedAeadParameters.AES256_GCM).withRandomId()
            .makePrimary()).build())
        .isInstanceOf(GeneralSecurityException.class);
    assertThat(Keysets.withNewPrimary(master).size()).isEqualTo(2);
  }
}
