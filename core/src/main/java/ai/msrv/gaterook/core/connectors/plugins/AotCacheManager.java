package ai.msrv.gaterook.core.connectors.plugins;

import ai.msrv.gaterook.core.platform.CoreBuild;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The host's AOT cache on the person's machine (report 16 §3.3): after the first install the manager runs
 * {@code java -XX:AOTCacheOutput=… Main --train} in the background, which loads a built-in dummy plugin and makes a few
 * hundred calls, then renames the result into {@code ~/.mcp-gateway/cache/}. The cache's key is the host version, the
 * JRE's runtime version and the SHA-256 of {@code plugin-host.jar}; a host started with a cache whose key still
 * matches is ready about twice as fast, and a cache that doesn't fit is simply not used.
 */
public final class AotCacheManager {

  private static final Logger log = LoggerFactory.getLogger(AotCacheManager.class);

  private final HostRuntimeLocator runtime;
  private final PluginPaths paths;
  private final AtomicBoolean training = new AtomicBoolean();
  private final boolean enabled;

  public AotCacheManager(HostRuntimeLocator runtime, PluginPaths paths, boolean enabled) {
    this.runtime = runtime;
    this.paths = paths;
    this.enabled = enabled;
  }

  /** The cache file for this host and JRE, if it exists and its key matches. */
  public Optional<Path> cacheFor(String jreRelease, Path hostJar) {
    if (!enabled) {
      return Optional.empty();
    }
    Path cache = cachePath(jreRelease, hostJar);
    return cache != null && Files.isRegularFile(cache) ? Optional.of(cache) : Optional.empty();
  }

  /** Builds the cache in the background once, unless one already fits. Never fails a caller: a miss just means slower. */
  public void trainInBackground() {
    if (!enabled || !runtime.available()) {
      return;
    }
    Path hostJar = runtime.hostJar().orElse(null);
    if (hostJar == null) {
      return;
    }
    String jre = runtime.jreRelease();
    Path cache = cachePath(jre, hostJar);
    if (cache == null || Files.isRegularFile(cache)) {
      return;
    }
    if (!training.compareAndSet(false, true)) {
      return;
    }
    Thread.ofVirtual().name("plugin-host-aot-train").start(() -> {
      try {
        train(hostJar, cache);
      } catch (RuntimeException e) {
        log.info("The plugin host AOT cache couldn't be built: {}", e.toString());
      } finally {
        training.set(false);
      }
    });
  }

  private void train(Path hostJar, Path cache) {
    Path javaExe = runtime.javaExecutable().orElse(null);
    if (javaExe == null) {
      return;
    }
    Path tmp = cache.resolveSibling(cache.getFileName() + ".tmp-" + ProcessHandle.current().pid());
    long started = System.nanoTime();
    ProcessBuilder pb = new ProcessBuilder(javaExe.toString(), "-XX:AOTCacheOutput=" + tmp,
        "-Xlog:disable", "-Xlog:all=warning:stderr", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1",
        "-cp", hostJar.toString(), "ai.msrv.gaterook.pluginhost.Main", "--train");
    pb.environment().clear();
    pb.environment().put("LANG", "C.UTF-8");
    pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
    pb.redirectError(ProcessBuilder.Redirect.DISCARD);
    try {
      Process p = pb.start();
      if (!p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
        p.destroyForcibly();
        log.info("The plugin host AOT training run didn't finish in time");
        return;
      }
      if (p.exitValue() != 0 || !Files.isRegularFile(tmp)) {
        Files.deleteIfExists(tmp);
        return;
      }
      Files.move(tmp, cache, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      log.info("Built the plugin host AOT cache in {} ms: {}", (System.nanoTime() - started) / 1_000_000,
          cache.getFileName());
    } catch (IOException e) {
      log.info("The plugin host AOT training run failed: {}", e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private Path cachePath(String jreRelease, Path hostJar) {
    String sha = sha8(hostJar);
    if (sha == null) {
      return null;
    }
    String jre = jreRelease == null ? "unknown" : jreRelease.replaceAll("[^A-Za-z0-9._+-]", "_");
    return paths.cache().resolve("plugin-host-" + CoreBuild.VERSION + "-jre" + jre + "-" + sha + ".aot");
  }

  private static String sha8(Path file) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
        in.transferTo(java.io.OutputStream.nullOutputStream());
      }
      return HexFormat.of().formatHex(digest.digest()).substring(0, 8);
    } catch (IOException | NoSuchAlgorithmException e) {
      return null;
    }
  }
}
