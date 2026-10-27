package ai.msrv.gaterook.core.settings;

import ai.msrv.gaterook.core.platform.CoreBuild;
import ai.msrv.gaterook.core.platform.GatewayPaths;
import ai.msrv.gaterook.core.platform.LocalListener;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * What Settings › General › Core and core-starting say about this core (ARCHITECTURE.md §6 {@code GET /api/core/info},
 * §8.6 item 1): the version, that it is the native core (or a JVM build in development), its port and log file, and
 * the Java runtime the plugin host would use — the bundled JRE of the installer, or in a JVM development run the JDK
 * this core runs on.
 */
@Component
public class CoreInfo {

  /**
   * The JRE plugins run on.
   *
   * @param bundledJre whether it is the installer's JRE ({@code resources/jre}) rather than this process's JDK
   * @param version    {@code JAVA_RUNTIME_VERSION} of its {@code release} file, or {@code null} when unknown
   * @param present    whether its {@code bin/java} exists
   */
  public record PluginRuntime(boolean bundledJre, String home, String version, boolean present) {}

  /** {@code GET /api/core/info}. */
  public record Info(String version, String runtime, String vm, int port, String logFile,
      PluginRuntime pluginRuntime) {}

  private final LocalListener listener;
  private final GatewayPaths paths;
  private final String resources;

  public CoreInfo(LocalListener listener, GatewayPaths paths,
      @Value("${gateway.resources:}") String resources) {
    this.listener = listener;
    this.paths = paths;
    this.resources = resources;
  }

  public Info info() {
    return new Info(CoreBuild.VERSION, CoreBuild.runtime(), vm(), listener.port(),
        paths.logs().resolve("core.log").toString(), pluginRuntime());
  }

  private static String vm() {
    String name = System.getProperty("java.vm.name", "");
    String version = System.getProperty("java.vm.version", "");
    return (name + " " + version).strip();
  }

  /** {@code -Dgateway.resources} (Electron) names the bundle; a development run falls back to {@code java.home}. */
  PluginRuntime pluginRuntime() {
    boolean bundled = resources != null && !resources.isBlank();
    Path home = bundled ? Path.of(resources, "jre") : javaHome();
    if (home == null) {
      return new PluginRuntime(false, null, null, false);
    }
    boolean present = Files.isRegularFile(home.resolve("bin").resolve("java"))
        || Files.isRegularFile(home.resolve("bin").resolve("java.exe"));
    return new PluginRuntime(bundled, home.toString(), releaseVersion(home), present);
  }

  private static Path javaHome() {
    // A native image has no java.home of its own; only a JVM build can lend its JDK to the plugin host.
    String home = System.getProperty("java.home");
    return home == null || home.isBlank() || "native".equals(CoreBuild.runtime()) ? null : Path.of(home);
  }

  private static String releaseVersion(Path home) {
    Path release = home.resolve("release");
    if (!Files.isRegularFile(release)) {
      return null;
    }
    Properties p = new Properties();
    try (InputStream in = Files.newInputStream(release)) {
      p.load(in);
    } catch (IOException e) {
      return null;
    }
    String version = p.getProperty("JAVA_RUNTIME_VERSION", p.getProperty("JAVA_VERSION"));
    return version == null ? null : version.replace("\"", "");
  }
}
