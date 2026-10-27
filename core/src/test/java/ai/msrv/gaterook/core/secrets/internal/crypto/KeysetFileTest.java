package ai.msrv.gaterook.core.secrets.internal.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.crypto.tink.KeysetHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The key file: 0400, atomic replacement of a read-only file, problems the card shows (report 12 §6, §8.1). */
class KeysetFileTest {

  private Path dir;
  private KeysetFile file;

  @BeforeEach
  void setUp() throws Exception {
    dir = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "keyset-file-");
    file = new KeysetFile(dir);
  }

  @Test
  void writesOwnerReadOnlyAndReplacesAReadOnlyFile() throws Exception {
    KeysetHandle first = Keysets.generate();
    file.write(first, null);
    assertThat(file.mode()).isEqualTo("0400");
    assertThat(file.problems()).isEmpty();

    KeysetHandle second = Keysets.withNewPrimary(first);
    file.write(second, null);
    assertThat(file.mode()).isEqualTo("0400");
    KeysetFile.Loaded loaded = file.load(null);
    assertThat(loaded.state()).isEqualTo(KeysetFile.State.LOADED);
    assertThat(loaded.handle().size()).isEqualTo(2);
    try (var listing = Files.list(dir)) {
      assertThat(listing).as("no temporary file left").hasSize(1);
    }
  }

  @Test
  void reportsWidePermissionsAndFixesThem() throws Exception {
    file.write(Keysets.generate(), null);
    Files.setPosixFilePermissions(file.path(), PosixFilePermissions.fromString("rw-r--r--"));
    assertThat(file.mode()).isEqualTo("0644");
    assertThat(file.problems()).containsExactly("perms");

    file.fixPermissions();
    assertThat(file.mode()).isEqualTo("0400");
    assertThat(Files.getPosixFilePermissions(dir)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
    assertThat(file.problems()).isEmpty();
  }

  @Test
  void missingAndGarbageFilesAreStatesNotExceptions() throws Exception {
    assertThat(file.load(null).state()).isEqualTo(KeysetFile.State.MISSING);
    Files.writeString(file.path(), "not a keyset");
    KeysetFile.Loaded garbage = file.load(null);
    assertThat(garbage.state()).isEqualTo(KeysetFile.State.INVALID);
    assertThat(garbage.problem()).contains("not a valid key file");

    Path aside = file.moveAside(Instant.ofEpochMilli(1_700_000_000_000L));
    assertThat(aside.getFileName().toString()).isEqualTo("master.keyset.replaced-1700000000000");
    assertThat(file.exists()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"/Users/ann/Library/Mobile Documents/com~apple~CloudDocs/keys/master.keyset",
      "/Users/ann/Library/CloudStorage/Dropbox/master.keyset", "/home/ann/Dropbox/backup.json",
      "/home/ann/Google Drive/backup.json", "C:/Users/ann/OneDrive - Acme/backup.json"})
  void detectsSyncedFolders(String path) {
    assertThat(SyncedFolders.isSynced(Path.of(path))).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"/home/ann/.gaterook/master.keyset", "/Users/ann/Documents/backup.json",
      "/media/usb/backup.json"})
  void leavesLocalFoldersAlone(String path) {
    assertThat(SyncedFolders.isSynced(Path.of(path))).isFalse();
  }
}
