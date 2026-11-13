package com.gaterook.connectors.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import com.gaterook.connectors.support.mcp.McpServerRuntime;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
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
 * would then open wherever the link leads: a link in place of the work folder or its {@code tmp} is refused and never
 * followed. The folder of all work folders is the person's, a link there included.
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

  /**
   * The folder of all work folders is the person's: no server can write there, so a link at it (the folder kept on
   * another disk) is theirs and followed, and the work folders are made where it leads, below it as everywhere.
   */
  @Test
  void aLinkAtTheFolderOfAllWorkFoldersIsThePersonsOwnAndFollowed() throws Exception {
    Path disk = Files.createDirectories(home.resolve("disk/mcp"));
    Files.createSymbolicLink(home.resolve("mcp"), disk);

    Session.prepare(home.resolve("mcp/server"));

    for (Path dir : List.of(disk.resolve("server"), disk.resolve("server/tmp"))) {
      assertThat(dir).isDirectory();
      assertThat(Files.isSymbolicLink(dir)).isFalse();
      assertThat(Files.getPosixFilePermissions(dir)).isEqualTo(PosixFilePermissions.fromString(OWNER_ONLY));
    }
    // Below it a link is still refused.
    Files.delete(disk.resolve("server/tmp"));
    Path tmp = Files.createSymbolicLink(disk.resolve("server/tmp"), home.resolve("disk"));
    assertThatThrownBy(() -> Session.prepare(home.resolve("mcp/server"))).isInstanceOf(ConnectorException.class)
        .hasMessageContaining("temp folder " + home.resolve("mcp/server/tmp")).hasMessageContaining("symbolic link");
    assertThat(Files.isSymbolicLink(tmp)).isTrue();

    // A folder of all work folders that isn't there yet is made owner-only.
    Path fresh = home.resolve("fresh/mcp/server");
    Session.prepare(fresh);
    assertThat(Files.getPosixFilePermissions(fresh.getParent()))
        .isEqualTo(PosixFilePermissions.fromString(OWNER_ONLY));
  }

  @Test
  void aLinkInPlaceOfTheWorkFolderIsRefusedToo() throws Exception {
    Path outside = Files.createDirectories(home.resolve("outside"));
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

  /**
   * Where a link leads is the server's to write, and any text will do. It goes into the error the person and the agent
   * read and into the server's log, so a line break, a terminal escape and an invisible character are removed (as from
   * every other text of a server, {@link Texts}): the reason stays one plain line.
   */
  @Test
  void whereALinkLeadsIsShownAndLoggedAsOnePlainLine() throws Exception {
    Path work = Files.createDirectories(home.resolve("mcp/server"));
    Path file = home.resolve("logs/server.log");
    Path target = Path.of("first\nsecond\u001b[31m");
    try {
      target = Path.of(target + Character.toString(0xE0049)); // a tag character, invisible
    } catch (InvalidPathException e) {
      // A file system that can't name it has no such link to show.
    }
    Path tmp = Files.createSymbolicLink(work.resolve("tmp"), target);
    Throwable refused;
    try (ServerLog log = new ServerLog(file, Clock.systemUTC())) {
      refused = catchThrowable(() -> Session.prepare(work, log));
    }

    assertThat(refused).isInstanceOf(ConnectorException.class);
    assertThat(hasHiddenCharacter(refused.getMessage())).as(refused.getMessage()).isFalse();
    assertThat(refused).hasMessageContaining("temp folder " + tmp)
        .hasMessageContaining("symbolic link to first second");
    List<String> lines = Files.readAllLines(file);
    assertThat(lines).hasSize(1);
    assertThat(hasHiddenCharacter(lines.getFirst())).as(lines.getFirst()).isFalse();
    assertThat(lines.getFirst()).contains("[gaterook]").contains("symbolic link to first second");
  }

  @Test
  void aTargetOfNothingButInvisibleCharactersIsLeftOut() throws Exception {
    Path work = Files.createDirectories(home.resolve("mcp/server"));
    Files.createSymbolicLink(work.resolve("tmp"), Path.of("\n\u001b"));

    assertThatThrownBy(() -> Session.prepare(work)).isInstanceOf(ConnectorException.class)
        .hasMessageContaining("is a symbolic link. GateRook");
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

  /** Whether {@code text} has a character that breaks its line or hides in it: a control or a format character. */
  private static boolean hasHiddenCharacter(String text) {
    return text.codePoints().anyMatch(Texts::hidden);
  }
}
