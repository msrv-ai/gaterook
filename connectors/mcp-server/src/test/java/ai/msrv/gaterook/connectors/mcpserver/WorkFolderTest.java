package ai.msrv.gaterook.connectors.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connectors.support.mcp.McpServerRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The folders a server is started in ({@link Session#prepare}, ARCHITECTURE.md §7.5): its work folder (its
 * {@code HOME}), the {@code tmp} inside it ({@code TMPDIR}) and the folder of all work folders. A server writes in its
 * work folder, so it can replace {@code tmp} with a link before its next start, and a sandbox that names paths (macOS)
 * would then open wherever the link leads: a link where a folder belongs is refused and never followed.
 */
@DisabledOnOs(OS.WINDOWS) // symbolic links need privileges there
class WorkFolderTest {

  private static final String OWNER_ONLY = "rwx------";

  @TempDir
  Path home;

  @Test
  void newFoldersAreOwnerOnlyAndPreparingAgainChangesNothing() throws Exception {
    Path work = home.resolve("mcp/server");

    Session.prepare(work);

    for (Path dir : List.of(work.getParent(), work, work.resolve("tmp"))) {
      assertThat(dir).isDirectory();
      assertThat(Files.getPosixFilePermissions(dir)).isEqualTo(PosixFilePermissions.fromString(OWNER_ONLY));
    }
    Files.writeString(work.resolve("tmp/state"), "kept");
    Files.setPosixFilePermissions(work, PosixFilePermissions.fromString("rwxr-x---"));

    Session.prepare(work);

    assertThat(work.resolve("tmp/state")).hasContent("kept");
    assertThat(Files.getPosixFilePermissions(work)).isEqualTo(PosixFilePermissions.fromString("rwxr-x---"));
  }

  @Test
  void aLinkInPlaceOfTmpIsRefusedAndNeitherFollowedNorChanged() throws Exception {
    Path work = Files.createDirectories(home.resolve("mcp/server"));
    Path outside = Files.createDirectories(home.resolve("outside"));
    Files.setPosixFilePermissions(outside, PosixFilePermissions.fromString("rwxr-xr-x"));
    Path tmp = Files.createSymbolicLink(work.resolve("tmp"), outside);
    ServerLog log = new ServerLog(home.resolve("logs/server.log"), Clock.systemUTC());

    assertThatThrownBy(() -> Session.prepare(work, log)).isInstanceOf(ConnectorException.class)
        .hasMessageContaining("temp folder " + tmp).hasMessageContaining("symbolic link")
        .hasMessageContaining("to " + outside).hasMessageContaining("Delete the link");

    // Left as it was for the person to see, with what it points to untouched, and said in the server's log.
    assertThat(Files.isSymbolicLink(tmp)).isTrue();
    assertThat(Files.getPosixFilePermissions(outside)).isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
    try (Stream<Path> inside = Files.list(outside)) {
      assertThat(inside).isEmpty();
    }
    assertThat(home.resolve("logs/server.log")).content().contains("[gaterook]").contains("symbolic link")
        .contains(tmp.toString());
    log.close();
  }

  @Test
  void aLinkInPlaceOfTheWorkFolderOrTheFolderOfAllWorkFoldersIsRefusedToo() throws Exception {
    Path outside = Files.createDirectories(home.resolve("outside"));
    Path folders = Files.createSymbolicLink(home.resolve("mcp"), outside);

    assertThatThrownBy(() -> Session.prepare(home.resolve("mcp/server"))).isInstanceOf(ConnectorException.class)
        .hasMessageContaining("folder of all server folders " + folders).hasMessageContaining("symbolic link");
    try (Stream<Path> inside = Files.list(outside)) {
      assertThat(inside).isEmpty();
    }

    Files.delete(folders);
    Files.createDirectories(home.resolve("mcp"));
    Path work = Files.createSymbolicLink(home.resolve("mcp/server"), outside);

    assertThatThrownBy(() -> Session.prepare(work)).isInstanceOf(ConnectorException.class)
        .hasMessageContaining("server's folder " + work).hasMessageContaining("symbolic link");
    try (Stream<Path> inside = Files.list(outside)) {
      assertThat(inside).isEmpty();
    }
  }

  @Test
  void aLinkThatLeadsNowhereIsRefusedWithTheSameReason() throws Exception {
    Path work = Files.createDirectories(home.resolve("mcp/server"));
    Path tmp = Files.createSymbolicLink(work.resolve("tmp"), home.resolve("gone"));

    assertThatThrownBy(() -> Session.prepare(work)).isInstanceOf(ConnectorException.class)
        .hasMessageContaining("temp folder " + tmp).hasMessageContaining("symbolic link");
    assertThat(home.resolve("gone")).doesNotExist();
  }

  @Test
  void aStartIsRefusedBeforeAnythingRunsAndTheServersLogSaysWhy() throws Exception {
    Map<String, String> coreEnv = Map.of("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"),
        "HOME", home.toString(), "USER", "tester");
    McpServers runtime = new McpServers(coreEnv, System.getProperty("os.name"), false, Clock.systemUTC(),
        Duration.ofMillis(100), McpServerConnector.typeManifest());
    runtime.attach(new Fixtures.Host(home));
    try {
      ObjectNode c = Fixtures.object("{\"transport\":\"stdio\"}");
      ArrayNode command = c.putArray("command");
      command.add(Fixtures.javaExecutable());
      command.add("-cp");
      command.add(System.getProperty("java.class.path"));
      command.add(FakeMcpServer.class.getName());
      command.add("normal");
      ConnectionConfig cfg = Fixtures.cfg("cn_fake", "fake-mcp", Env.DEV, 1, c);
      Path outside = Files.createDirectories(home.resolve("outside"));
      Files.createDirectories(home.resolve("mcp/fake-mcp"));
      Files.createSymbolicLink(home.resolve("mcp/fake-mcp/tmp"), outside);

      assertThatThrownBy(() -> runtime.probe(cfg, new Fixtures.Secrets(), Fixtures.Context.of(Duration.ofSeconds(60))))
          .isInstanceOf(ConnectorException.class).hasMessageContaining("symbolic link");

      assertThat(runtime.process("cn_fake")).isEmpty();
      try (Stream<Path> inside = Files.list(outside)) {
        assertThat(inside).isEmpty();
      }
      assertThat(home.resolve("logs/mcp/fake-mcp.log")).content().contains("symbolic link");
    } finally {
      runtime.close();
    }
  }
}
