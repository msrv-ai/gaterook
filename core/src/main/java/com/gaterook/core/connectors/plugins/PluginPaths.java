package com.gaterook.core.connectors.plugins;

import com.gaterook.core.platform.GatewayPaths;
import com.gaterook.core.platform.ProblemException;
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
    return dir(checked(paths.home().resolve("plugins")));
  }

  /** {@code plugins/.staging/}. */
  public Path staging() {
    return dir(checked(plugins().resolve(".staging")));
  }

  /** {@code plugins/<id>/}. */
  public Path pluginDir(String id) {
    return child(plugins(), id);
  }

  /** {@code plugins/<id>/<version>/}. */
  public Path versionDir(String id, String version) {
    return child(pluginDir(id), version);
  }

  /** {@code plugins/<id>/<version>/plugin.jar}. */
  public Path jar(String id, String version) {
    return checked(versionDir(id, version).resolve("plugin.jar"));
  }

  /** {@code plugin-data/<id>/} (0700), the plugin's writable work folder. */
  public Path dataDir(String id) {
    Path dir = dir(child(paths.home().resolve("plugin-data"), id));
    dir(checked(dir.resolve("tmp")));
    return dir;
  }

  /** {@code cache/} for the host's AOT cache. */
  public Path cache() {
    return dir(checked(paths.home().resolve("cache")));
  }

  /** {@code logs/plugins/<id>.log}. */
  public Path log(String id) {
    if (id == null) {
      throw ProblemException.unprocessable("Invalid plugin path", "A plugin ID is required.", "/plugin");
    }
    return child(dir(checked(paths.logs().resolve("plugins"))), id + ".log");
  }

  /** {@code run/plugin-hosts.json}: the pids of running hosts, for orphan cleanup on the next start. */
  public Path pidFile() {
    return paths.run().resolve("plugin-hosts.json");
  }

  GatewayPaths gatewayPaths() {
    return paths;
  }

  /** Resolves exactly one portable path component, then checks the managed filesystem boundary. */
  private Path child(Path parent, String name) {
    if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,199}")) {
      throw ProblemException.unprocessable("Invalid plugin path",
          "A plugin ID or version must be a single safe path component.", "/plugin");
    }
    Path root = parent.toAbsolutePath().normalize();
    Path child = root.resolve(name).normalize();
    if (!child.startsWith(root) || child.equals(root)) {
      throw ProblemException.unprocessable("Invalid plugin path",
          "Plugin files must remain in their managed directory.", "/plugin");
    }
    return checked(child);
  }

  /** Managed paths may not follow a plugin-created link into another plugin or outside the gateway home. */
  private Path checked(Path path) {
    Path root = paths.home().toAbsolutePath().normalize();
    Path target = path.toAbsolutePath().normalize();
    if (!target.startsWith(root) || target.equals(root)) {
      throw ProblemException.unprocessable("Invalid plugin path",
          "Plugin files must remain in the gateway home.", "/plugin");
    }
    Path current = root;
    for (Path name : root.relativize(target)) {
      current = current.resolve(name);
      if (Files.isSymbolicLink(current)) {
        throw ProblemException.conflict("Unsafe plugin path", "Managed plugin paths cannot be symbolic links.");
      }
    }
    return target;
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
