package ai.msrv.gaterook.core.clients.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.core.clients.ConfigEditException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How a client's config is written (ARCHITECTURE.md §7.2 "Writing configuration files"): only over the bytes the preview read, with a
 * dated copy next to it and an owner-only copy in the app's backups, atomically, through a symlink to its target, and
 * owner-only once it holds a token.
 */
class ConfigFileWriterTest {

  private static final Instant NOW = Instant.parse("2026-10-25T08:30:00Z");

  @TempDir
  Path dir;

  private ConfigFileWriter writer(Instant at) {
    return new ConfigFileWriter(dir.resolve("gateway/backups"), Clock.fixed(at, ZoneOffset.UTC));
  }

  @Test
  void writesOverTheBytesThePreviewReadAndBacksThemUp() throws IOException {
    Path file = dir.resolve("mcp.json");
    Files.writeString(file, "{}\n");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
    ConfigFileWriter.Snapshot before = ConfigFileWriter.read(file);
    assertThat(before.sha256()).isEqualTo(ConfigFileWriter.sha256("{}\n"));

    ConfigFileWriter.Written w = writer(NOW).write(before, "{\"a\":1}\n", "cursor", false);
    assertThat(Files.readString(file)).isEqualTo("{\"a\":1}\n");
    assertThat(mode(file)).as("no token: the original's permissions").isEqualTo("rw-r--r--");
    assertThat(w.backup().getFileName().toString()).isEqualTo("mcp.json.bak-" + LocalDate.ofInstant(NOW,
        ZoneId.systemDefault()));
    assertThat(Files.readString(w.backup())).isEqualTo("{}\n");
    assertThat(mode(w.backup())).isEqualTo("rw-r--r--");
    assertThat(mode(dir.resolve("gateway/backups"))).isEqualTo("rwx------");
    assertThat(mode(dir.resolve("gateway/backups/cursor"))).isEqualTo("rwx------");
    assertThat(mode(w.appBackup())).isEqualTo("rw-------");
    assertThat(Files.readString(w.appBackup())).isEqualTo("{}\n");
    assertThat(w.sha256()).isEqualTo(ConfigFileWriter.sha256("{\"a\":1}\n"));
    try (Stream<Path> s = Files.list(dir)) {
      assertThat(s.map(p -> p.getFileName().toString())).as("no temporary file stays")
          .noneMatch(n -> n.endsWith(".tmp"));
    }

    // The same snapshot again: the file isn't what it read any more.
    assertThatThrownBy(() -> writer(NOW).write(before, "{}", "cursor", false))
        .isInstanceOf(ConfigFileWriter.StaleFileException.class);
    assertThat(Files.readString(file)).isEqualTo("{\"a\":1}\n");
  }

  @Test
  void aTokenMakesTheFileAndItsDatedCopyOwnerOnly() throws IOException {
    Path file = dir.resolve("mcp.json");
    Files.writeString(file, "{}\n");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-r--"));
    String withToken = "{\"h\":\"Bearer gwt_" + "A".repeat(43) + "\"}\n";
    writer(NOW).write(ConfigFileWriter.read(file), withToken, "cursor", true);
    assertThat(mode(file)).isEqualTo("rw-------");
    // The next copy holds the token: owner-only, whatever the original's mode was.
    ConfigFileWriter.Written next = writer(NOW.plusSeconds(1)).write(ConfigFileWriter.read(file), "{}\n", "cursor",
        false);
    assertThat(next.backup().getFileName().toString()).endsWith("-2");
    assertThat(mode(next.backup())).isEqualTo("rw-------");
    assertThat(mode(next.appBackup())).isEqualTo("rw-------");
  }

  @Test
  void aNewFileIsOwnerOnlyAndHasNoBackup() throws IOException {
    Path file = dir.resolve("new/config.toml");
    ConfigFileWriter.Snapshot missing = ConfigFileWriter.read(file);
    assertThat(missing.exists()).isFalse();
    assertThat(missing.sha256()).isEqualTo("absent");
    ConfigFileWriter.Written w = writer(NOW).write(missing, "x = 1\n", "codex", false);
    assertThat(w.backup()).isNull();
    assertThat(w.appBackup()).isNull();
    assertThat(mode(file)).isEqualTo("rw-------");
  }

  @Test
  void aSymlinkIsFollowedToTheFileItNames() throws IOException {
    Path real = Files.createDirectories(dir.resolve("dotfiles")).resolve("mcp.json");
    Files.writeString(real, "{}\n");
    Path link = Files.createSymbolicLink(dir.resolve("mcp.json"), real);
    ConfigFileWriter.Snapshot s = ConfigFileWriter.read(link);
    assertThat(s.realPath()).isEqualTo(real.toRealPath());
    ConfigFileWriter.Written w = writer(NOW).write(s, "{\"b\":2}\n", "cursor", false);
    assertThat(Files.isSymbolicLink(link)).as("the link stays a link").isTrue();
    assertThat(Files.readString(real)).isEqualTo("{\"b\":2}\n");
    assertThat(w.backup().getParent()).isEqualTo(real.toRealPath().getParent());
  }

  @Test
  void keepsTheNewestTwentyCopies() throws IOException {
    Path file = dir.resolve("mcp.json");
    Files.writeString(file, "0\n");
    for (int i = 1; i <= 25; i++) {
      writer(NOW.plusSeconds(i)).write(ConfigFileWriter.read(file), i + "\n", "cursor", false);
    }
    List<Path> copies = writer(NOW).backups("cursor");
    assertThat(copies).hasSize(ConfigFileWriter.KEEP_BACKUPS);
    assertThat(Files.readString(copies.getFirst())).as("newest first").isEqualTo("24\n");
  }

  @Test
  void refusesWhatItShouldNotEdit() throws IOException {
    Path big = dir.resolve("big.json");
    Files.writeString(big, "x".repeat((int) ConfigFileWriter.MAX_BYTES + 1));
    assertThatThrownBy(() -> ConfigFileWriter.read(big)).isInstanceOfSatisfying(ConfigEditException.class,
        e -> assertThat(e.reason()).isEqualTo(ConfigEditException.Reason.UNSUPPORTED_FILE));
    Path folder = Files.createDirectories(dir.resolve("folder.json"));
    assertThatThrownBy(() -> ConfigFileWriter.read(folder)).isInstanceOf(ConfigEditException.class);
    Path latin = dir.resolve("latin1.json");
    Files.write(latin, new byte[] {'{', (byte) 0xE9, '}'});
    assertThatThrownBy(() -> ConfigFileWriter.read(latin)).isInstanceOfSatisfying(ConfigEditException.class,
        e -> assertThat(e.reason()).isEqualTo(ConfigEditException.Reason.INVALID));
  }

  private static String mode(Path p) throws IOException {
    return PosixFilePermissions.toString(Files.getPosixFilePermissions(p));
  }
}
