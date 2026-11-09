package ai.msrv.gaterook.connectors.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.support.mcp.McpServerRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A renamed connection takes its server's work folder and log along ({@link McpServers#relocate}): the server stops
 * first and can't start while the move is unsettled, a saved rename deletes what the new name held before, and one that
 * isn't saved moves everything back.
 */
class RelocationTest {

  private static final String TOKEN = "fake-token-3e9b51c0d7a24f68";
  private static final String TOKEN_FIELD = "/env/0/secretRef";

  @TempDir
  Path home;

  private McpServers runtime;
  private final Fixtures.Secrets secrets = new Fixtures.Secrets().with(TOKEN_FIELD, TOKEN);

  @BeforeEach
  void attach() {
    Map<String, String> coreEnv = Map.of("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"),
        "HOME", home.toString(), "USER", "tester");
    runtime = new McpServers(coreEnv, System.getProperty("os.name"), false, Clock.systemUTC(), Duration.ofMillis(100),
        McpServerConnector.typeManifest());
    runtime.attach(new Fixtures.Host(home));
  }

  @AfterEach
  void close() {
    runtime.close();
  }

  private static ConnectionConfig cfg(String name, long version) {
    ObjectNode c = Fixtures.object("{\"transport\":\"stdio\",\"env\":[{\"name\":\"FAKE_TOKEN\"}]}");
    ArrayNode command = c.putArray("command");
    command.add(Fixtures.javaExecutable());
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(FakeMcpServer.class.getName());
    command.add("normal");
    return Fixtures.cfg("cn_fake", name, Env.DEV, version, c);
  }

  private McpServerRuntime.Snapshot review(ConnectionConfig cfg) throws ConnectorException {
    McpServerRuntime.Handshake h = runtime.probe(cfg, secrets, Fixtures.Context.of(Duration.ofSeconds(60)));
    return new McpServerRuntime.Snapshot("fake_", h.serverVersion(), h.protocolVersion(), h.tools(), null);
  }

  private String call(Connector c, ConnectionConfig cfg, String tool) throws ConnectorException {
    ToolResult r = c.execute(Fixtures.call(tool, "{\"text\":\"hi\"}"), cfg, secrets,
        Fixtures.Context.of(Duration.ofSeconds(30)));
    return r.content().getFirst().text();
  }

  private Stream<String> entries(String folder) throws java.io.IOException {
    try (Stream<Path> list = Files.list(home.resolve(folder))) {
      return list.map(p -> p.getFileName().toString()).toList().stream();
    }
  }

  @Test
  void aSavedRenameStopsTheServerMovesItsFolderAndLogAndTheNextCallStartsItThere() throws Exception {
    ConnectionConfig before = cfg("fake-mcp", 1);
    McpServerRuntime.Snapshot snapshot = review(before);
    Connector c = runtime.connector(before, snapshot);
    assertThat(call(c, before, "fake_echo")).isEqualTo("hi");
    long pid = runtime.process("cn_fake").orElseThrow().pid();
    Files.writeString(home.resolve("mcp/fake-mcp/state.txt"), "kept by the server");

    McpServerRuntime.Relocation move = runtime.relocate("cn_fake", "fake-mcp", "notes-mcp");

    assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    assertThat(home.resolve("mcp/fake-mcp")).doesNotExist();
    assertThat(home.resolve("mcp/notes-mcp/state.txt")).hasContent("kept by the server");
    assertThat(home.resolve("logs/mcp/fake-mcp.log")).doesNotExist();
    String moved = Files.readString(home.resolve("logs/mcp/notes-mcp.log"));
    assertThat(moved).contains("fake server starting");
    ConnectionConfig after = cfg("notes-mcp", 2);
    Connector renamed = runtime.connector(after, snapshot);
    assertThatThrownBy(() -> call(renamed, after, "fake_echo")).isInstanceOf(ConnectorException.class)
        .hasMessageContaining("notes-mcp is being renamed");
    assertThatThrownBy(() -> runtime.relocate("cn_fake", "notes-mcp", "other-mcp"))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("being renamed already");

    move.commit();

    assertThat(call(renamed, after, "fake_env")).contains("cwd=" + home.resolve("mcp/notes-mcp").toRealPath());
    String log = Files.readString(home.resolve("logs/mcp/notes-mcp.log"));
    assertThat(log).startsWith(moved);
    assertThat(log.substring(moved.length())).contains("fake server starting");
    assertThat(entries("mcp")).containsExactly("notes-mcp");
    assertThat(entries("logs/mcp")).containsExactly("notes-mcp.log");
  }

  @Test
  void aRenameThatIsNotSavedMovesEverythingBackWithWhatTheNewNameHeld() throws Exception {
    ConnectionConfig before = cfg("fake-mcp", 1);
    Connector c = runtime.connector(before, review(before));
    assertThat(call(c, before, "fake_echo")).isEqualTo("hi");
    Files.createDirectories(home.resolve("mcp/notes-mcp"));
    Files.writeString(home.resolve("mcp/notes-mcp/left.txt"), "left by a deleted server");
    Files.writeString(home.resolve("logs/mcp/notes-mcp.log"), "its old lines\n");
    Files.writeString(home.resolve("logs/mcp/notes-mcp.log.1"), "its older lines\n");

    McpServerRuntime.Relocation move = runtime.relocate("cn_fake", "fake-mcp", "notes-mcp");

    assertThat(home.resolve("mcp/notes-mcp/left.txt")).doesNotExist();
    assertThat(home.resolve("mcp/notes-mcp/tmp")).isDirectory();
    assertThat(home.resolve("logs/mcp/notes-mcp.log.1")).doesNotExist();

    move.rollback();
    move.commit();

    assertThat(home.resolve("mcp/notes-mcp/left.txt")).hasContent("left by a deleted server");
    assertThat(home.resolve("logs/mcp/notes-mcp.log")).hasContent("its old lines");
    assertThat(home.resolve("logs/mcp/notes-mcp.log.1")).hasContent("its older lines");
    assertThat(home.resolve("mcp/fake-mcp/tmp")).isDirectory();
    assertThat(home.resolve("logs/mcp/fake-mcp.log")).content().contains("fake server starting");
    assertThat(entries("mcp")).containsExactlyInAnyOrder("fake-mcp", "notes-mcp");
    assertThat(call(c, before, "fake_env")).contains("cwd=" + home.resolve("mcp/fake-mcp").toRealPath());
  }

  @Test
  void whatTheNewNameHeldIsDeletedAsLinksNeverFollowed() throws Exception {
    Assumptions.assumeFalse(System.getProperty("os.name").startsWith("Windows"), "symbolic links need privileges");
    Path outside = Files.createDirectories(home.resolve("outside"));
    Files.writeString(outside.resolve("keep.txt"), "not GateRook's");
    Files.createDirectories(home.resolve("mcp/notes-mcp"));
    Files.createSymbolicLink(home.resolve("mcp/notes-mcp/link"), outside);
    Files.createDirectories(home.resolve("mcp/fake-mcp/tmp"));

    runtime.relocate("cn_fake", "fake-mcp", "notes-mcp").commit();

    assertThat(outside.resolve("keep.txt")).hasContent("not GateRook's");
    assertThat(entries("mcp")).containsExactly("notes-mcp");
    assertThat(home.resolve("mcp/notes-mcp/tmp")).isDirectory();
    assertThat(home.resolve("mcp/notes-mcp/link")).doesNotExist();
  }

  @Test
  void onlyConnectionNamesNameFolders() throws Exception {
    for (String[] names : new String[][] {{"fake-mcp", "../escape"}, {"fake-mcp", "Notes"}, {"..", "notes-mcp"},
        {"fake-mcp", "a".repeat(65)}}) {
      assertThatThrownBy(() -> runtime.relocate("cn_fake", names[0], names[1])).isInstanceOf(ConnectorException.class)
          .hasMessageContaining("can't become");
    }
    runtime.relocate("cn_fake", "fake-mcp", "fake-mcp").commit();
    assertThat(home.resolve("mcp")).doesNotExist();
  }
}
