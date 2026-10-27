package ai.msrv.gaterook.core.clients.internal;

import ai.msrv.gaterook.core.clients.internal.ClientEnvironment.Os;
import ai.msrv.gaterook.core.platform.GatewayPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;

/**
 * The stable launcher client configs point at (report 03 §1, ARCHITECTURE.md §7.2): {@code ~/.mcp-gateway/bin/gw-bridge}
 * ({@code gw-bridge.cmd} on Windows), rewritten atomically at every start of the core, so that a moved or updated app,
 * or an AppImage mounted elsewhere, never breaks Codex's helper or a stdio entry. It runs, in this order: the native
 * {@code gw-bridge} of the app's bundle; the bundle's JRE with {@code gw-bridge.jar}; in a development run the jar
 * named by {@code gateway.clients.bridge-jar} on this JVM. It exports {@code GW_HOME}, so the bridge finds the broker
 * of this gateway home. Without any of them it says so and fails (exit 1), which the client shows.
 */
public final class BridgeShimInstaller {

  /** The program the shim runs. */
  public record Target(List<String> argv) {

    public boolean present() {
      return !argv.isEmpty();
    }
  }

  private BridgeShimInstaller() {}

  /**
   * What the shim should run.
   *
   * @param javaHome  this JVM's home, or {@code null} in a native image
   * @param devJar    {@code gateway.clients.bridge-jar} (development and tests), or {@code null}
   */
  public static Target target(ClientEnvironment env, Path javaHome, Path devJar) {
    Optional<Path> resources = env.resources();
    if (resources.isPresent()) {
      Path bridge = resources.get().resolve("bridge");
      Path nativeBridge = bridge.resolve(env.os() == Os.WINDOWS ? "gw-bridge.exe" : "gw-bridge");
      if (Files.isRegularFile(nativeBridge)) {
        return new Target(List.of(nativeBridge.toString()));
      }
      Path jre = resources.get().resolve("jre").resolve("bin").resolve(env.os() == Os.WINDOWS ? "java.exe" : "java");
      Path jar = bridge.resolve("gw-bridge.jar");
      if (Files.isRegularFile(jre) && Files.isRegularFile(jar)) {
        return new Target(javaArgv(jre, jar));
      }
    }
    if (devJar != null && javaHome != null && Files.isRegularFile(devJar)) {
      Path java = javaHome.resolve("bin").resolve(env.os() == Os.WINDOWS ? "java.exe" : "java");
      return new Target(javaArgv(java, devJar.toAbsolutePath().normalize()));
    }
    return new Target(List.of());
  }

  /**
   * A small, quick JVM: the helper runs once per request Codex makes. Native access is granted on the command line
   * as well as in the jar's manifest, because {@code sandbox-exec} calls Landlock through FFM and the manifest
   * attribute is only read for {@code -jar}.
   */
  private static List<String> javaArgv(Path java, Path jar) {
    return List.of(java.toString(), "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xshare:auto", "-Xmx64m",
        "--enable-native-access=ALL-UNNAMED", "-jar", jar.toString());
  }

  /** The shim's text for {@code target}. */
  public static String script(ClientEnvironment env, Target target) {
    String home = env.gatewayHome().toString();
    if (env.os() == Os.WINDOWS) {
      // A batch file expands %…% even inside quotes: a literal % is written %%.
      StringBuilder b = new StringBuilder("@echo off\r\n")
          .append("rem GateRook bridge launcher, rewritten by GateRook at every start\r\n")
          .append("set \"GW_HOME=").append(home.replace("%", "%%")).append("\"\r\n");
      if (!target.present()) {
        b.append("echo GateRook's bridge isn't installed with this build of GateRook. 1>&2\r\nexit /b 1\r\n");
      } else {
        for (String a : target.argv()) {
          b.append('"').append(a.replace("%", "%%")).append("\" ");
        }
        b.append("%*\r\n");
      }
      return b.toString();
    }
    StringBuilder b = new StringBuilder("#!/bin/sh\n")
        .append("# GateRook bridge launcher, rewritten by GateRook at every start\n")
        .append("GW_HOME=").append(ClientEnvironment.shQuote(home)).append("; export GW_HOME\n");
    if (!target.present()) {
      b.append("echo \"GateRook's bridge isn't installed with this build of GateRook.\" >&2\nexit 1\n");
    } else {
      b.append("exec");
      for (String a : target.argv()) {
        b.append(' ').append(ClientEnvironment.shQuote(a));
      }
      b.append(" \"$@\"\n");
    }
    return b.toString();
  }

  /**
   * Writes the shim (and, on Windows, a copy of the native bridge for clients that can't run {@code .cmd}) atomically,
   * 0755 in a 0700 {@code bin}; returns the shim.
   */
  public static Path install(ClientEnvironment env, Target target) throws IOException {
    Path shim = env.shim();
    GatewayPaths.ownerOnlyDirectory(shim.getParent());
    writeAtomically(shim, script(env, target).getBytes(StandardCharsets.UTF_8), true);
    if (env.os() == Os.WINDOWS && target.present() && target.argv().size() == 1
        && target.argv().getFirst().endsWith(".exe")) {
      Path copy = env.bridgeExecutable();
      Path tmp = Files.createTempFile(copy.getParent(), ".gw-bridge", ".tmp");
      try {
        Files.copy(Path.of(target.argv().getFirst()), tmp, StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmp, copy, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(tmp);
      }
    }
    return shim;
  }

  /** Whether the shim exists and is executable (check step 1). */
  public static boolean ready(ClientEnvironment env) {
    Path shim = env.shim();
    return Files.isRegularFile(shim) && (env.os() == Os.WINDOWS || Files.isExecutable(shim));
  }

  private static void writeAtomically(Path file, byte[] data, boolean executable) throws IOException {
    Path tmp = Files.createTempFile(file.getParent(), "." + file.getFileName(), ".tmp");
    try {
      Files.write(tmp, data);
      if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
        Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString(executable ? "rwxr-xr-x" : "rw-------"));
      }
      Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }
}
