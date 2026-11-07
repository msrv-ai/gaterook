package ai.msrv.gaterook.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What {@link WindowsSandbox} prepares and hands to kernel32, pinned without Windows. */
class WindowsSandboxTest {

  @Test
  void onlyTheEnvironmentsFoldersInsideAReadWritePathAreCreated() {
    Path work = Path.of("/gw/mcp-servers/fs").toAbsolutePath();
    Map<String, String> env = Map.of(
        "TEMP", work.resolve("tmp").toString(),
        "APPDATA", work.resolve("AppData").resolve("Roaming").toString(),
        "LOCALAPPDATA", Path.of("/elsewhere/Local").toAbsolutePath().toString(),
        "USERPROFILE", work.toString(),
        "PATH", work.resolve("bin").toString());
    assertThat(WindowsSandbox.foldersToCreate(env, List.of(work))).containsExactlyInAnyOrder(
        work.resolve("tmp"), work.resolve("AppData").resolve("Roaming"), work);
    assertThat(WindowsSandbox.foldersToCreate(env, List.of())).isEmpty();
  }

  @Test
  void theServersJobEndsWithTheBridgeAndLetsABrowserLeaveExplicitly() {
    assertThat(WindowsSandbox.SERVER_LIMITS).isEqualTo(0x2000 | 0x0400 | 0x0800);
    assertThat(WindowsSandbox.SERVER_LIMITS & 0x1000).as("no silent breakaway").isZero();
  }

  @Test
  void onWindowsACommandIsFoundOnPathWithPathextInAnyCase(@org.junit.jupiter.api.io.TempDir Path dir)
      throws Exception {
    Path one = java.nio.file.Files.createDirectories(dir.resolve("one"));
    Path two = java.nio.file.Files.createDirectories(dir.resolve("two"));
    java.nio.file.Files.writeString(two.resolve("npx.cmd"), "@echo off");
    java.nio.file.Files.writeString(one.resolve("node.exe"), "MZ");
    Map<String, String> env = Map.of("Path", one + ";" + two, "PATHEXT", ".COM;.EXE;.BAT;.CMD");
    assertThat(Sandbox.resolve("npx", env, true)).isEqualTo(two.resolve("npx.cmd").toAbsolutePath());
    assertThat(Sandbox.resolve("node", env, true)).isEqualTo(one.resolve("node.exe").toAbsolutePath());
    assertThat(Sandbox.resolve("node.exe", env, true)).isEqualTo(one.resolve("node.exe").toAbsolutePath());
    assertThat(Sandbox.resolve("missing", env, true)).isNull();
    // An explicit Windows path must not fall back to the matching command on PATH.
    assertThat(Sandbox.resolve(one.resolve("npx.cmd").toString().replace('/', '\\'), env, true)).isNull();
    assertThat(Sandbox.resolve(two.resolve("npx.cmd").toString(), env, true))
        .isEqualTo(two.resolve("npx.cmd").toAbsolutePath());
  }

  @Test
  void theLabelAndTheSidAreLow() {
    assertThat(WindowsSandbox.WRITABLE_FOLDER).isEqualTo("S:(ML;OICI;NW;;;LW)");
    byte[] sid = WindowsSandbox.lowSid();
    assertThat(sid).containsExactly(1, 1, 0, 0, 0, 0, 0, 16, 0, 0x10, 0, 0);
  }

  @Test
  void aWorkFolderLabelledLowAlreadyIsLeftAsItIs() {
    assertThat(WindowsSandbox.lowLabelAcl(acl(0x11, 0x3, 0x1000))).isTrue();
    assertThat(WindowsSandbox.lowLabelAcl(acl(0x11, 0x13, 0x1000))).as("inherited").isTrue();
    assertThat(WindowsSandbox.lowLabelAcl(acl(0x11, 0x3, 0x2000))).as("medium").isFalse();
    assertThat(WindowsSandbox.lowLabelAcl(acl(0x11, 0x0, 0x1000))).as("not inherited by its files").isFalse();
    assertThat(WindowsSandbox.lowLabelAcl(acl(0x02, 0x3, 0x1000))).as("not a label").isFalse();
  }

  private static java.lang.foreign.MemorySegment acl(int type, int flags, int rid) {
    java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(28).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    b.put((byte) 2).put((byte) 0).putShort((short) 28).putShort((short) 1).putShort((short) 0);
    b.put((byte) type).put((byte) flags).putShort((short) 20).putInt(1);
    b.put((byte) 1).put((byte) 1).put(new byte[] {0, 0, 0, 0, 0, 16}).putInt(rid);
    return java.lang.foreign.MemorySegment.ofArray(b.array());
  }
}
