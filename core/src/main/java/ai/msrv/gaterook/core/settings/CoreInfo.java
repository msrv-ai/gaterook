package ai.msrv.gaterook.core.settings;

import ai.msrv.gaterook.core.platform.CoreBuild;
import ai.msrv.gaterook.core.platform.GatewayPaths;
import ai.msrv.gaterook.core.platform.LocalListener;
import org.springframework.stereotype.Component;

/**
 * What Settings › General › Core and core-starting say about this core (ARCHITECTURE.md §6 {@code GET /api/core/info},
 * §8.6 item 1): the version, that it is the native core (or a JVM build in development), its port and log file, and
 * the Java runtime the plugin subsystem reports: cached support, an offline bundle or a development JDK.
 */
@Component
public class CoreInfo {

  /** Runtime details supplied by the admin facade from the plugin subsystem's authoritative locator. */
  public record PluginRuntime(boolean bundledJre, String home, String version, boolean present,
      String source, String state, String message) {}

  /** {@code GET /api/core/info}. */
  public record Info(String version, String runtime, String vm, int port, String logFile,
      PluginRuntime pluginRuntime) {}

  private final LocalListener listener;
  private final GatewayPaths paths;

  public CoreInfo(LocalListener listener, GatewayPaths paths) {
    this.listener = listener;
    this.paths = paths;
  }

  public Info info(PluginRuntime pluginRuntime) {
    return new Info(CoreBuild.VERSION, CoreBuild.runtime(), vm(), listener.port(),
        paths.logs().resolve("core.log").toString(), pluginRuntime);
  }

  private static String vm() {
    String name = System.getProperty("java.vm.name", "");
    String version = System.getProperty("java.vm.version", "");
    return (name + " " + version).strip();
  }

}
