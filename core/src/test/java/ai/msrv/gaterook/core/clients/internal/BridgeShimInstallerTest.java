package ai.msrv.gaterook.core.clients.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.msrv.gaterook.core.clients.internal.ClientEnvironment.Os;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The launcher client configs run ({@code ~/.mcp-gateway/bin/gw-bridge}, report 03 §7.4): every path quoted for its
 * shell, written atomically, 0755 in a 0700 folder.
 */
class BridgeShimInstallerTest {

  @TempDir
  Path dir;

  @Test
  void thePosixShimQuotesItsPathsAndExportsTheHome() throws IOException {
    Path home = dir.resolve("it's home/.mcp-gateway");
    ClientEnvironment env = new ClientEnvironment(Os.LINUX, dir, home, null, Map.of(), true, "laptop");
    BridgeShimInstaller.Target bridge = new BridgeShimInstaller.Target(List.of("/opt/app/bridge/gw-bridge"));
    String script = BridgeShimInstaller.script(env, bridge);
    assertThat(script).startsWith("#!/bin/sh\n")
        .contains("GW_HOME='" + dir + "/it'\\''s home/.mcp-gateway'; export GW_HOME\n")
        .endsWith("exec '/opt/app/bridge/gw-bridge' \"$@\"\n");
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    Path shim = BridgeShimInstaller.install(env, bridge);
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(shim))).isEqualTo("rwxr-xr-x");
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(shim.getParent()))).isEqualTo("rwx------");
    assertThat(BridgeShimInstaller.ready(env)).isTrue();
  }

  @Test
  void theWindowsShimWritesAPercentSignTwice() {
    // A batch file expands %…% inside quotes too: C:\Users\a%b would lose its %b.
    ClientEnvironment env = new ClientEnvironment(Os.WINDOWS, Path.of("C:\\Users\\a%b"),
        Path.of("C:\\Users\\a%b\\.mcp-gateway"), null, Map.of(), false, "laptop");
    String script = BridgeShimInstaller.script(env, new BridgeShimInstaller.Target(List.of(
        "C:\\Program Files\\MCP Gateway\\100%\\gw-bridge.exe")));
    // (Path.of makes the Windows path absolute against this system's folder: only its end is checked.)
    assertThat(script).contains("C:\\Users\\a%%b\\.mcp-gateway\"\r\n").doesNotContain("a%b")
        .contains("\"C:\\Program Files\\MCP Gateway\\100%%\\gw-bridge.exe\" %*\r\n");
  }
}
