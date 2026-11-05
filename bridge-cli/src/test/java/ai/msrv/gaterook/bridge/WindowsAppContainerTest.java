package ai.msrv.gaterook.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What {@link WindowsAppContainer} builds before it calls Windows, pinned without it. */
class WindowsAppContainerTest {

  @Test
  void aCommandLineReadsBackAsTheSameArguments() {
    assertThat(WindowsAppContainer.commandLine(List.of("C:\\Program Files\\nodejs\\node.exe", "server.js", "")))
        .isEqualTo("\"C:\\Program Files\\nodejs\\node.exe\" server.js \"\"");
    // A quote is escaped, and the backslashes before it doubled; trailing backslashes before the closing quote too.
    assertThat(WindowsAppContainer.quote("say \"hi\"")).isEqualTo("\"say \\\"hi\\\"\"");
    assertThat(WindowsAppContainer.quote("a\\\"b")).isEqualTo("\"a\\\\\\\"b\"");
    assertThat(WindowsAppContainer.quote("C:\\dir with space\\")).isEqualTo("\"C:\\dir with space\\\\\"");
    // Backslashes elsewhere are literal, and nothing else needs quotes: & and | mean nothing to CreateProcess.
    assertThat(WindowsAppContainer.quote("C:\\a\\b")).isEqualTo("C:\\a\\b");
    assertThat(WindowsAppContainer.quote("pkg&calc|x")).isEqualTo("pkg&calc|x");
  }

  @Test
  void npxAndNpmBesideNodeRunAsNodeWithNpmsScript(@TempDir Path dir) throws Exception {
    Path node = Files.writeString(dir.resolve("node.exe"), "MZ");
    Path bin = Files.createDirectories(dir.resolve("node_modules/npm/bin"));
    Path npxCli = Files.writeString(bin.resolve("npx-cli.js"), "");
    Path npmCli = Files.writeString(bin.resolve("npm-cli.js"), "");
    Path npx = Files.writeString(dir.resolve("npx.cmd"), "@echo off");
    Path npm = Files.writeString(dir.resolve("npm.cmd"), "@echo off");
    assertThat(WindowsAppContainer.executable(List.of(npx.toString(), "-y", "pkg@1.0.0")))
        .containsExactly(node.toString(), npxCli.toString(), "-y", "pkg@1.0.0");
    assertThat(WindowsAppContainer.executable(List.of(npm.toString(), "ci")))
        .containsExactly(node.toString(), npmCli.toString(), "ci");
    assertThat(WindowsAppContainer.executable(List.of(node.toString(), "server.js")))
        .containsExactly(node.toString(), "server.js");
    // Any other batch file would hand the arguments to cmd.exe: refused.
    Path other = Files.writeString(dir.resolve("server.cmd"), "@echo off");
    assertThat(WindowsAppContainer.executable(List.of(other.toString()))).isNull();
  }

  @Test
  void eachWorkFolderHasItsOwnContainer() {
    String one = WindowsAppContainer.containerName(Path.of("/gw/mcp-servers/github"));
    assertThat(one).matches("GateRook\\.McpServer\\.[0-9a-f]{16}").hasSizeLessThanOrEqualTo(64);
    assertThat(WindowsAppContainer.containerName(Path.of("/gw/mcp-servers/github"))).isEqualTo(one);
    assertThat(WindowsAppContainer.containerName(Path.of("/gw/mcp-servers/slack"))).isNotEqualTo(one);
  }

  @Test
  void theCapabilitiesAreTheInternetAndThePrivateNetwork() {
    assertThat(WindowsAppContainer.capabilitySid(1)).containsExactly(1, 2, 0, 0, 0, 0, 0, 15, 3, 0, 0, 0, 1, 0, 0, 0);
    assertThat(WindowsAppContainer.capabilitySid(3)).containsExactly(1, 2, 0, 0, 0, 0, 0, 15, 3, 0, 0, 0, 3, 0, 0, 0);
  }

  @Test
  void aGrantIsSkippedWhenTheAccessListNamesTheContainerAlready() {
    byte[] sid = WindowsAppContainer.capabilitySid(9);
    MemorySegment acl = allowed(sid, 0x1F01FF);
    assertThat(WindowsAppContainer.names(acl, sid, 0x1F01FF)).isTrue();
    assertThat(WindowsAppContainer.names(acl, sid, 0x1200A9)).as("a subset of the rights").isTrue();
    assertThat(WindowsAppContainer.names(allowed(sid, 0x1200A9), sid, 0x1F01FF)).as("fewer rights").isFalse();
    assertThat(WindowsAppContainer.names(acl, WindowsAppContainer.capabilitySid(8), 0x1F01FF)).isFalse();
  }

  @Test
  void theFlagIsParsed() {
    Sandbox.Request r = Sandbox.parse(List.of("--rw", "C:\\work", "--appcontainer", "--", "node.exe"));
    assertThat(r.appContainer()).isTrue();
    assertThat(r.readWrite()).containsExactly("C:\\work");
    assertThat(Sandbox.parse(List.of("--", "node.exe")).appContainer()).isFalse();
  }

  @Test
  void nodeInAContainerSkipsRealpathAndTheBlockIsSortedAndDoubleTerminated() {
    java.util.Map<String, String> env = WindowsAppContainer.environment(List.of("C:\\n\\node.exe", "x.js"),
        java.util.Map.of("Path", "C:\\n", "TEMP", "C:\\w\\tmp"));
    assertThat(env).containsEntry("NODE_OPTIONS", "--preserve-symlinks --preserve-symlinks-main");
    assertThat(WindowsAppContainer.environment(List.of("C:\\n\\node.exe"), java.util.Map.of("node_options",
        "--max-old-space-size=512")).get("NODE_OPTIONS"))
        .isEqualTo("--max-old-space-size=512 --preserve-symlinks --preserve-symlinks-main");
    assertThat(WindowsAppContainer.environment(List.of("C:\\py\\python.exe"), java.util.Map.of("A", "1")))
        .doesNotContainKey("NODE_OPTIONS");
    assertThat(WindowsAppContainer.environmentBlock(java.util.Map.of("b", "2", "A", "1", "=C:", "C:\\")))
        .isEqualTo("A=1\0b=2\0\0");
  }

  /** An ACL with one access-allowed ACE for {@code sid}. */
  private static MemorySegment allowed(byte[] sid, int mask) {
    int aceSize = 8 + sid.length;
    ByteBuffer b = ByteBuffer.allocate(8 + aceSize).order(ByteOrder.LITTLE_ENDIAN);
    b.put((byte) 2).put((byte) 0).putShort((short) (8 + aceSize)).putShort((short) 1).putShort((short) 0);
    b.put((byte) 0).put((byte) 0x3).putShort((short) aceSize).putInt(mask).put(sid);
    return MemorySegment.ofArray(b.array());
  }
}
