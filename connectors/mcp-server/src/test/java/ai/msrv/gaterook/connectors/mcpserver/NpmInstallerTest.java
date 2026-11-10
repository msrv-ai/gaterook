package ai.msrv.gaterook.connectors.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import ai.msrv.gaterook.connector.api.ConnectorException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class NpmInstallerTest {

  private static final String INTEGRITY = "sha512-abc==";

  private static void installed(Path dir, String lockIntegrity, String recordIntegrity, String version)
      throws Exception {
    Files.createDirectories(dir.resolve("node_modules/@scope/server"));
    Files.writeString(dir.resolve("package-lock.json"), """
        {"lockfileVersion":3,"packages":{"":{"dependencies":{"@scope/server":"1.0.0"}},
         "node_modules/@scope/server":{"version":"1.0.0","integrity":"%s"},
         "node_modules/dep":{"version":"2.0.0","integrity":"sha512-dep"}}}""".formatted(lockIntegrity));
    Files.writeString(dir.resolve("node_modules/.package-lock.json"), """
        {"lockfileVersion":3,"packages":{"node_modules/@scope/server":{"version":"1.0.0","integrity":"%s"}}}"""
        .formatted(recordIntegrity));
    Files.writeString(dir.resolve("node_modules/@scope/server/package.json"), """
        {"name":"@scope/server","version":"%s","bin":{"server":"dist/index.js"}}""".formatted(version));
  }

  @Test
  void anInstallThatMatchesItsCatalogEntryStarts(@TempDir Path dir) throws Exception {
    installed(dir, INTEGRITY, INTEGRITY, "1.0.0");

    assertThatCode(() -> NpmInstaller.verify(dir, INTEGRITY)).doesNotThrowAnyException();
  }

  @Test
  void aPackageSwappedOutsideTheGatewayDoesntStart(@TempDir Path dir) throws Exception {
    installed(dir, INTEGRITY, "sha512-other", "1.0.0");
    assertThatThrownBy(() -> NpmInstaller.verify(dir, INTEGRITY)).isInstanceOf(ConnectorException.class)
        .hasMessageContaining("doesn't match its catalog entry");

    installed(dir, INTEGRITY, INTEGRITY, "1.0.1");
    assertThatThrownBy(() -> NpmInstaller.verify(dir, INTEGRITY)).isInstanceOf(ConnectorException.class);

    installed(dir, "sha512-lock-edited", INTEGRITY, "1.0.0");
    assertThatThrownBy(() -> NpmInstaller.verify(dir, INTEGRITY)).isInstanceOf(ConnectorException.class);
  }

  /**
   * The server writes in its folder, so what is read there before each start is read within bounds (audit M-A5): a
   * regular file, not a link, of at most {@link NpmInstaller#MAX_FILE} bytes; nothing of a larger one is read.
   */
  @Test
  void aFileOfTheFolderThatIsTooLargeOrALinkIsNotRead(@TempDir Path dir, @TempDir Path elsewhere) throws Exception {
    installed(dir, INTEGRITY, INTEGRITY, "1.0.0");
    Path record = dir.resolve("node_modules/.package-lock.json");
    String valid = Files.readString(record);
    // The same record, padded past the bound with a member npm doesn't write: still valid JSON.
    Files.writeString(record, valid.replaceFirst("\\{", "{\"padding\":\"" + "p".repeat(NpmInstaller.MAX_FILE)
        + "\","));
    assertThatThrownBy(() -> NpmInstaller.verify(dir, INTEGRITY)).isInstanceOf(ConnectorException.class)
        .hasMessageContaining("doesn't match its catalog entry");

    // The record put elsewhere, with a link in its place.
    Files.writeString(elsewhere.resolve("record.json"), valid);
    Files.delete(record);
    Files.createSymbolicLink(record, elsewhere.resolve("record.json"));
    assertThatThrownBy(() -> NpmInstaller.verify(dir, INTEGRITY)).isInstanceOf(ConnectorException.class)
        .hasMessageContaining("doesn't match its catalog entry");

    Files.delete(record);
    Files.writeString(record, valid);
    assertThatCode(() -> NpmInstaller.verify(dir, INTEGRITY)).doesNotThrowAnyException();
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void aFifoInPlaceOfTheLockfileIsRefusedWithoutWaitingForAWriter(@TempDir Path dir) throws Exception {
    installed(dir, INTEGRITY, INTEGRITY, "1.0.0");
    Path lock = dir.resolve("package-lock.json");
    Files.delete(lock);
    assertThat(new ProcessBuilder("mkfifo", lock.toString()).start().waitFor()).isZero();

    assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThatThrownBy(() -> NpmInstaller.verify(dir,
        INTEGRITY)).isInstanceOf(ConnectorException.class).hasMessageContaining("doesn't match its catalog entry"));
  }

  @Test
  void onlyTheEndOfWhatNpmPrintsIsKept() throws Exception {
    byte[] start = "the start\n".getBytes(StandardCharsets.UTF_8);
    byte[] middle = new byte[3 * NpmInstaller.MAX_OUTPUT + 123];
    java.util.Arrays.fill(middle, (byte) 'm');
    byte[] end = "\nthe last line".getBytes(StandardCharsets.UTF_8);
    InputStream printed = new SequenceInputStream(new ByteArrayInputStream(start), new SequenceInputStream(
        new ByteArrayInputStream(middle), new ByteArrayInputStream(end)));

    String kept = NpmInstaller.tail(printed);

    assertThat(kept).hasSize(NpmInstaller.MAX_OUTPUT).endsWith("mmm\nthe last line").doesNotContain("the start");
    assertThat(NpmInstaller.tail(new ByteArrayInputStream(start))).isEqualTo("the start\n");
  }
}
