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
 * The launcher client configs run ({@code ~/.gaterook/bin/gw-bridge}, report 03 §7.4): every path quoted for its
 * shell, written atomically, 0755 in a 0700 folder.
 */
class BridgeShimInstallerTest {

  @TempDir
  Path dir;

  @Test
  void thePosixShimQuotesItsPathsAndExportsTheHome() throws IOException {
    Path home = dir.resolve("it's home/.gaterook");
    ClientEnvironment env = new ClientEnvironment(Os.LINUX, dir, home, null, Map.of(), true, "laptop");
    BridgeShimInstaller.Target bridge = new BridgeShimInstaller.Target(List.of("/opt/app/bridge/gw-bridge"));
    String script = BridgeShimInstaller.script(env, bridge);
    assertThat(script).startsWith("#!/bin/sh\n")
        .contains("GW_HOME='" + home.toString().replace("'", "'\\''") + "'; export GW_HOME\n")
        .endsWith("exec '/opt/app/bridge/gw-bridge' \"$@\"\n");
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    Path shim = BridgeShimInstaller.install(env, bridge);
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(shim))).isEqualTo("rwxr-xr-x");
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(shim.getParent()))).isEqualTo("rwx------");
    assertThat(BridgeShimInstaller.ready(env)).isTrue();
  }

  @Test
  void aBundleUnderASharedDirectoryRunsACopyInThePersonsOwnBin() throws IOException {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    // An AppImage mounts its bundle in /tmp: once it's unmounted, another account could create the same path.
    Path shared = Files.createDirectories(dir.resolve("tmp"));
    Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxrwxrwx"));
    Path resources = Files.createDirectories(shared.resolve(".mount_GateRk1/resources"));
    Path nativeBridge = Files.createDirectories(resources.resolve("bridge")).resolve("gw-bridge");
    Files.writeString(nativeBridge, "#!/bin/sh\necho bridge\n");
    Path home = dir.resolve("home/.gaterook");
    ClientEnvironment env = new ClientEnvironment(Os.LINUX, dir.resolve("home"), home, resources, Map.of(), true,
        "laptop");

    BridgeShimInstaller.Target target = BridgeShimInstaller.target(env, null, null);
    Path shim = BridgeShimInstaller.install(env, target);

    Path copy = home.resolve("bin/gw-bridge-native");
    assertThat(target.argv()).containsExactly(copy.toString());
    assertThat(Files.readString(shim)).endsWith("exec '" + copy + "' \"$@\"\n").doesNotContain(shared.toString());
    assertThat(Files.readString(copy)).isEqualTo("#!/bin/sh\necho bridge\n");
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(copy))).isEqualTo("rwx------");
    // The bundle's JRE stays in the shared directory: it is never run from there.
    Files.delete(nativeBridge);
    Files.writeString(resources.resolve("bridge/gw-bridge.jar"), "jar");
    Files.writeString(Files.createDirectories(resources.resolve("jre/bin")).resolve("java"), "java");
    assertThat(BridgeShimInstaller.target(env, null, null).present()).isFalse();
  }

  @Test
  void aBundleInAPrivateDirectoryRunsInPlace() throws IOException {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    assumeTrue(!BridgeShimInstaller.underSharedDirectory(dir), "the temporary folder is itself shared");
    Path resources = Files.createDirectories(dir.resolve("opt/GateRook/resources"));
    Path nativeBridge = Files.createDirectories(resources.resolve("bridge")).resolve("gw-bridge");
    Files.writeString(nativeBridge, "bridge");
    ClientEnvironment env = new ClientEnvironment(Os.LINUX, dir, dir.resolve(".gaterook"), resources, Map.of(), true,
        "laptop");

    BridgeShimInstaller.Target target = BridgeShimInstaller.target(env, null, null);

    assertThat(target.argv()).containsExactly(nativeBridge.toString());
    assertThat(target.copyFrom()).isNull();
  }

  @Test
  void theWindowsShimWritesAPercentSignTwice() {
    // A batch file expands %…% inside quotes too: C:\Users\a%b would lose its %b.
    ClientEnvironment env = new ClientEnvironment(Os.WINDOWS, Path.of("C:\\Users\\a%b"),
        Path.of("C:\\Users\\a%b\\.gaterook"), null, Map.of(), false, "laptop");
    String script = BridgeShimInstaller.script(env, new BridgeShimInstaller.Target(List.of(
        "C:\\Program Files\\GateRook\\100%\\gw-bridge.exe")));
    // (Path.of makes the Windows path absolute against this system's folder: only its end is checked.)
    assertThat(script).contains("C:\\Users\\a%%b\\.gaterook\"\r\n").doesNotContain("a%b")
        .contains("\"C:\\Program Files\\GateRook\\100%%\\gw-bridge.exe\" %*\r\n");
  }

  @Test
  void windowsClientsRunTheExportedBridgeInsteadOfThePackagePrivateExecutable() throws IOException {
    Path resources = Files.createDirectories(dir.resolve("WindowsApps/package/resources/bridge"));
    Path source = Files.writeString(resources.resolve("gw-bridge.exe"), "native bridge bytes");
    ClientEnvironment env = new ClientEnvironment(Os.WINDOWS, dir, dir.resolve("user home/.gaterook"),
        resources.getParent(), Map.of(), true, "laptop");
    BridgeShimInstaller.Target target = new BridgeShimInstaller.Target(List.of(source.toString()));
    Path shim = BridgeShimInstaller.install(env, target);
    assertThat(Files.readString(env.bridgeExecutable())).isEqualTo("native bridge bytes");
    assertThat(Files.readString(shim)).contains("\"" + env.bridgeExecutable() + "\" %*\r\n")
        .doesNotContain("WindowsApps");
    var originalTime = Files.getLastModifiedTime(env.bridgeExecutable());
    BridgeShimInstaller.install(env, target);
    assertThat(Files.getLastModifiedTime(env.bridgeExecutable())).isEqualTo(originalTime);
    Files.writeString(source, "updated bridge bytes");
    BridgeShimInstaller.install(env, target);
    assertThat(Files.readString(env.bridgeExecutable())).isEqualTo("updated bridge bytes");
    // The core starts MCP servers through the bridge's own argv, not the .cmd.
    assertThat(Files.readString(BridgeShimInstaller.argvFile(shim))).isEqualTo(env.bridgeExecutable() + "\n");
  }

  @Test
  void windowsWritesTheBridgesArgvBesideTheShimAndRemovesItWithoutABridge() throws IOException {
    ClientEnvironment env = new ClientEnvironment(Os.WINDOWS, dir, dir.resolve("user home/.gaterook"),
        null, Map.of(), true, "laptop");
    Path shim = BridgeShimInstaller.install(env, new BridgeShimInstaller.Target(List.of("C:\\jre\\bin\\java.exe",
        "-Xmx64m", "-jar", "C:\\app\\gw-bridge.jar")));
    assertThat(Files.readAllLines(BridgeShimInstaller.argvFile(shim)))
        .containsExactly("C:\\jre\\bin\\java.exe", "-Xmx64m", "-jar", "C:\\app\\gw-bridge.jar");
    BridgeShimInstaller.install(env, new BridgeShimInstaller.Target(List.of()));
    assertThat(BridgeShimInstaller.argvFile(shim)).doesNotExist();
  }
}
