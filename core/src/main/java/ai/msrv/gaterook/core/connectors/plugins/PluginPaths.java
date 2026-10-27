package ai.msrv.gaterook.core.connectors.plugins;

import ai.msrv.gaterook.core.platform.GatewayPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/**
 * Where the plugin subsystem keeps its files under {@code ~/.gaterook} (ARCHITECTURE.md §7.5):
 * <ul>
 *   <li>{@code plugins/<id>/<version>/plugin.jar} — an installed generation (0600, folders 0700); the previous version
 *       stays 7 days for Roll back;</li>
 *   <li>{@code plugins/.staging/} — a checked-but-not-installed JAR;</li>
 *   <li>{@code plugin-data/<id>/} — a plugin's work folder and {@code tmp} (the only place the OS sandbox lets it
 *       write);</li>
 *   <li>{@code cache/} — the host's AOT cache;</li>
 *   <li>{@code logs/plugins/<id>.log} — the masked log of a plugin and its host.</li>
 * </ul>
 */
@Component
public final class PluginPaths {

  private final GatewayPaths paths;

  PluginPaths(GatewayPaths paths) {
    this.paths = paths;
  }

  /** {@code plugins/}. */
  public Path plugins() {
    return dir(paths.home().resolve("plugins"));
  }

  /** {@code plugins/.staging/}. */
  public Path staging() {
    return dir(plugins().resolve(".staging"));
  }

  /** {@code plugins/<id>/}. */
  public Path pluginDir(String id) {
    return plugins().resolve(id);
  }

  /** {@code plugins/<id>/<version>/}. */
  public Path versionDir(String id, String version) {
    return pluginDir(id).resolve(version);
  }

  /** {@code plugins/<id>/<version>/plugin.jar}. */
  public Path jar(String id, String version) {
    return versionDir(id, version).resolve("plugin.jar");
  }

  /** {@code plugin-data/<id>/} (0700), the plugin's writable work folder. */
  public Path dataDir(String id) {
    Path dir = dir(paths.home().resolve("plugin-data").resolve(id));
    dir(dir.resolve("tmp"));
    return dir;
  }

  /** {@code cache/} for the host's AOT cache. */
  public Path cache() {
    return dir(paths.home().resolve("cache"));
  }

  /** {@code logs/plugins/<id>.log}. */
  public Path log(String id) {
    return dir(paths.logs().resolve("plugins")).resolve(id + ".log");
  }

  /** {@code run/plugin-hosts.json}: the pids of running hosts, for orphan cleanup on the next start. */
  public Path pidFile() {
    return paths.run().resolve("plugin-hosts.json");
  }

  GatewayPaths gatewayPaths() {
    return paths;
  }

  private static Path dir(Path dir) {
    try {
      if (!Files.isDirectory(dir)) {
        GatewayPaths.ownerOnlyDirectory(dir.getParent());
        GatewayPaths.ownerOnlyDirectory(dir);
      }
      return dir;
    } catch (IOException e) {
      throw new UncheckedIOException("Can't make " + dir, e);
    }
  }
}
