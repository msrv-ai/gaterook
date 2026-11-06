package ai.msrv.gaterook.core.endpoints;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.core.platform.GatewayPaths;
import ai.msrv.gaterook.core.platform.ProblemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class ConnectionImportFilesTest {

  @TempDir Path temporary;
  private static final String ID = "ci_0123456789ab";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void literalCredentialsStayOutOfReviewAndAreClearedWhenTheSessionCloses() throws Exception {
    try (GatewayPaths paths = GatewayPaths.open(temporary.resolve("home"))) {
      ConnectionImportFiles files = new ConnectionImportFiles(paths, JSON);
      Path file = Path.of(files.create(ID));
      if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(file.getParent())).isEqualTo(PosixFilePermissions.fromString("rwx------"));
      }
      Files.writeString(file, manifest("""
          {"source":"local","value":"literal-import-test-password"}
          """).toString());
      var prepared = files.read(ID);
      assertThat(Files.exists(file)).isFalse();
      assertThat(prepared.manifest().toString()).doesNotContain("literal-import-test-password", "\"secrets\"")
          .contains("secretSources", "/credentials/password", "local");
      var secret = prepared.resolve("database").get("/credentials/password");
      assertThat(new String(secret.value())).isEqualTo("literal-import-test-password");
      secret.clear();
      assertThat(prepared.toString()).doesNotContain("literal-import-test-password");
      prepared.close();
      var after = prepared.resolve("database").get("/credentials/password");
      assertThat(after.value()).containsOnly('\0');
      after.clear();
    }
  }

  @Test
  void fileReferencesAreInertUntilThePersonConfirmsAndCanSelectAJsonKey() throws Exception {
    try (GatewayPaths paths = GatewayPaths.open(temporary.resolve("home"))) {
      ConnectionImportFiles files = new ConnectionImportFiles(paths, JSON);
      Path credentials = temporary.resolve("credentials.json");
      ObjectNode source = JsonNodeFactory.instance.objectNode().put("source", "file")
          .put("path", credentials.toString()).put("jsonPointer", "/database/password");
      Path file = Path.of(files.create(ID));
      Files.writeString(file, manifest(source.toString()).toString());
      try (var prepared = files.read(ID)) {
        assertThat(Files.exists(credentials)).isFalse();
        assertThat(prepared.manifest().toString()).contains(credentials.toString());
        assertThatThrownBy(() -> prepared.resolve("database")).isInstanceOf(ProblemException.class);
        Files.writeString(credentials, "{\"database\":{\"password\":\"file-import-test-password\"}}");
        var secret = prepared.resolve("database").get("/credentials/password");
        assertThat(new String(secret.value())).isEqualTo("file-import-test-password");
        secret.clear();
        assertThat(prepared.manifest().toString()).doesNotContain("file-import-test-password");
      }
    }
  }

  @Test
  void vaultKeysArePreservedAsReferencesWithoutResolvingThem() throws Exception {
    try (GatewayPaths paths = GatewayPaths.open(temporary.resolve("home"))) {
      ConnectionImportFiles files = new ConnectionImportFiles(paths, JSON);
      Path file = Path.of(files.create(ID));
      Files.writeString(file, manifest("""
          {"source":"reference","ref":"vault://kv/example/dev#password","store":"store-example"}
          """).toString());
      try (var prepared = files.read(ID)) {
        var secret = prepared.resolve("database").get("/credentials/password");
        assertThat(secret.isLocal()).isFalse();
        assertThat(secret.reference()).isEqualTo("vault://kv/example/dev#password");
        assertThat(secret.store()).isEqualTo("store-example");
      }
    }
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void rejectsFileReplacementBySymlinkAndNeverEchoesInvalidJson() throws Exception {
    try (GatewayPaths paths = GatewayPaths.open(temporary.resolve("home"))) {
      ConnectionImportFiles files = new ConnectionImportFiles(paths, JSON);
      Path file = Path.of(files.create(ID));
      Path other = temporary.resolve("other.json");
      Files.writeString(other, manifest("{\"source\":\"local\",\"value\":\"never-echo-this-secret\"}").toString());
      Files.delete(file);
      Files.createSymbolicLink(file, other);
      assertThatThrownBy(() -> files.read(ID)).isInstanceOf(ProblemException.class)
          .hasMessageNotContaining("never-echo-this-secret");
      Files.delete(file);
      Files.writeString(file, "{\"value\":\"never-echo-this-secret\" garbage");
      assertThatThrownBy(() -> files.read(ID)).isInstanceOf(ProblemException.class)
          .hasMessageNotContaining("never-echo-this-secret");
      Files.writeString(file, " ".repeat(ConnectionImportService.MAX_BYTES + 1));
      assertThatThrownBy(() -> files.read(ID)).isInstanceOf(ProblemException.class);
      assertThatThrownBy(() -> files.create("../../outside")).isInstanceOf(ProblemException.class);
      assertThat(Files.exists(other)).isTrue();
    }
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void restartRemovesUnconsumedOwnedFilesWithoutTouchingUnrelatedFilesOrSymlinkTargets() throws Exception {
    try (GatewayPaths paths = GatewayPaths.open(temporary.resolve("home"))) {
      ConnectionImportFiles files = new ConnectionImportFiles(paths, JSON);
      Path oldFile = Path.of(files.create(ID));
      Files.writeString(oldFile, "unsubmitted private import");
      Path unrelated = oldFile.resolveSibling("notes.txt");
      Files.writeString(unrelated, "keep this");
      Path target = temporary.resolve("keep.json");
      Files.writeString(target, "keep target");
      Path link = oldFile.resolveSibling("ci_abcdefghijkl.json");
      Files.createSymbolicLink(link, target);
      new ConnectionImportFiles(paths, JSON);
      assertThat(Files.exists(oldFile)).isFalse();
      assertThat(Files.isSymbolicLink(link)).isFalse();
      assertThat(Files.readString(target)).isEqualTo("keep target");
      assertThat(Files.readString(unrelated)).isEqualTo("keep this");
    }
  }

  private static ObjectNode manifest(String secret) {
    ObjectNode manifest = JsonNodeFactory.instance.objectNode().put("version", 1);
    ObjectNode candidate = manifest.putArray("connections").addObject().put("id", "database")
        .put("name", "example-dev-db").put("type", "pgfake").put("env", "dev");
    candidate.putObject("config").putObject("connection").put("host", "database.test");
    candidate.putArray("evidence").add("config/application-dev.yml");
    candidate.putArray("credentialHints");
    candidate.putArray("warnings");
    candidate.putObject("secrets").set("/credentials/password", JSON.readTree(secret));
    manifest.putArray("warnings");
    return manifest;
  }
}
