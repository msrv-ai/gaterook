package ai.msrv.gaterook.core.connectors.plugins;

import ai.msrv.gaterook.core.platform.CoreBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the JRE and {@code plugin-host.jar} the core launches plugin hosts with (ARCHITECTURE.md §2.3): the native
 * desktop build is told its bundle with {@code -Dgateway.resources} and uses {@code <resources>/jre} and
 * {@code <resources>/plugin-host/plugin-host.jar}; a JVM build (dev, tests) lends its own {@code java.home} and takes
 * the jar from {@code -Dgateway.pluginHostJar}. When neither is present the plugin host can't run, and the subsystem
 * says so instead of failing the whole core.
 */
public final class HostRuntimeLocator {

  private static final Logger log = LoggerFactory.getLogger(HostRuntimeLocator.class);

  private final String resources;
  private final String pluginHostJar;

  public HostRuntimeLocator(String resources, String pluginHostJar) {
    this.resources = resources == null || resources.isBlank() ? null : resources;
    this.pluginHostJar = pluginHostJar == null || pluginHostJar.isBlank() ? null : pluginHostJar;
  }

  /** Whether a JRE and the host jar are both present: only then can a plugin host start. */
  public boolean available() {
    return javaExecutable().isPresent() && hostJar().isPresent();
  }

  /** Why the host can't run, for the UI, or empty when it can. */
  public Optional<String> unavailableReason() {
    if (javaExecutable().isEmpty()) {
      return Optional.of("No Java runtime for plugins: " + (resources != null ? "none in the bundle"
          : "this core is a native build with no java.home and no -Dgateway.resources"));
    }
    if (hostJar().isEmpty()) {
      return Optional.of("plugin-host.jar is missing (set -Dgateway.pluginHostJar in a development run)");
    }
    return Optional.empty();
  }

  /** {@code <jre>/bin/java}. */
  public Optional<Path> javaExecutable() {
    return jreHome().map(home -> {
      Path unix = home.resolve("bin").resolve("java");
      Path win = home.resolve("bin").resolve("java.exe");
      return Files.isRegularFile(win) && !Files.isRegularFile(unix) ? win : unix;
    }).filter(Files::isRegularFile);
  }

  /** The JRE home: the bundle's {@code jre}, else this process's {@code java.home} (JVM build only). */
  public Optional<Path> jreHome() {
    if (resources != null) {
      Path jre = Path.of(resources, "jre");
      if (Files.isDirectory(jre)) {
        return Optional.of(jre);
      }
    }
    if ("native".equals(CoreBuild.runtime())) {
      return Optional.empty();
    }
    String home = System.getProperty("java.home");
    return home == null || home.isBlank() ? Optional.empty() : Optional.of(Path.of(home));
  }

  /** {@code plugin-host.jar}. */
  public Optional<Path> hostJar() {
    if (pluginHostJar != null) {
      Path jar = Path.of(pluginHostJar);
      if (Files.isRegularFile(jar)) {
        return Optional.of(jar);
      }
    }
    if (resources != null) {
      Path jar = Path.of(resources, "plugin-host", "plugin-host.jar");
      if (Files.isRegularFile(jar)) {
        return Optional.of(jar);
      }
    }
    return Optional.empty();
  }

  /** {@code JAVA_RUNTIME_VERSION} of {@code <jre>/release}, or {@code JAVA_VERSION}, or this JVM's version. */
  public String jreRelease() {
    return jreHome().map(home -> {
      Path release = home.resolve("release");
      if (Files.isRegularFile(release)) {
        try {
          for (String line : Files.readAllLines(release)) {
            if (line.startsWith("JAVA_RUNTIME_VERSION=")) {
              return unquote(line.substring("JAVA_RUNTIME_VERSION=".length()));
            }
          }
          for (String line : Files.readAllLines(release)) {
            if (line.startsWith("JAVA_VERSION=")) {
              return unquote(line.substring("JAVA_VERSION=".length()));
            }
          }
        } catch (IOException e) {
          log.debug("Can't read {}: {}", release, e.toString());
        }
      }
      return Runtime.version().toString();
    }).orElse(Runtime.version().toString());
  }

  /** The modules of the JRE ({@code MODULES=} in {@code release}), for a plugin's {@code requires.javaModules}. */
  public Set<String> jreModules() {
    Set<String> modules = new LinkedHashSet<>();
    jreHome().ifPresent(home -> {
      Path release = home.resolve("release");
      if (Files.isRegularFile(release)) {
        try {
          for (String line : Files.readAllLines(release)) {
            if (line.startsWith("MODULES=")) {
              for (String m : unquote(line.substring("MODULES=".length())).split("\\s+")) {
                if (!m.isBlank()) {
                  modules.add(m.strip());
                }
              }
            }
          }
        } catch (IOException e) {
          log.debug("Can't read modules from {}: {}", release, e.toString());
        }
      }
      if (modules.isEmpty()) {
        // A JVM build's own JDK has no MODULES= line: take what this runtime offers.
        ModuleLayer.boot().modules().forEach(m -> modules.add(m.getName()));
      }
    });
    return Set.copyOf(modules);
  }

  boolean osIsWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
  }

  private static String unquote(String value) {
    String v = value.strip();
    if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
      return v.substring(1, v.length() - 1);
    }
    return v;
  }
}
