package ai.msrv.gaterook.connectors.mcpserver;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectorException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
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
}
