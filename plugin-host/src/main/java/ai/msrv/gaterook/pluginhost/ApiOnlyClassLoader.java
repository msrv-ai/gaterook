package ai.msrv.gaterook.pluginhost;

import ai.msrv.gaterook.plugin.protocol.PluginManifest;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * The parent of every plugin's class loader (report 11 §7): the JDK and the shared API only — connector-api, Jackson,
 * SLF4J and PF4J — so that {@code Connector} is the same class for the host and the plugin while the host's own
 * classes ({@code ai.msrv.gaterook.pluginhost.*}, {@code ai.msrv.gaterook.plugin.protocol.*}) stay invisible. Hygiene, not a
 * sandbox: JEP 486 left no in-JVM sandbox; the process boundary and the OS sandbox are the real ones.
 */
final class ApiOnlyClassLoader extends ClassLoader {

  /** Packages served from the host: what a plugin compiles against. */
  static final List<String> SHARED = List.of("ai.msrv.gaterook.connector.api.", "tools.jackson.",
      "com.fasterxml.jackson.annotation.", "org.slf4j.", "org.pf4j.");

  static {
    registerAsParallelCapable();
  }

  private final ClassLoader host;

  ApiOnlyClassLoader(ClassLoader host) {
    super("plugin-api", ClassLoader.getPlatformClassLoader());
    this.host = host;
    // The manifest parser and this list must agree on what a plugin can't ship.
    assert PluginManifest.SHARED_PACKAGES.containsAll(SHARED.subList(1, SHARED.size()));
  }

  static boolean shared(String className) {
    for (String p : SHARED) {
      if (className.startsWith(p)) {
        return true;
      }
    }
    return false;
  }

  @Override
  protected Class<?> findClass(String name) throws ClassNotFoundException {
    if (shared(name)) {
      return host.loadClass(name);
    }
    throw new ClassNotFoundException(name);
  }

  @Override
  protected URL findResource(String name) {
    return sharedResource(name) ? host.getResource(name) : null;
  }

  @Override
  protected Enumeration<URL> findResources(String name) throws java.io.IOException {
    return sharedResource(name) ? host.getResources(name) : Collections.emptyEnumeration();
  }

  private static boolean sharedResource(String name) {
    return shared(name.replace('/', '.'));
  }
}
