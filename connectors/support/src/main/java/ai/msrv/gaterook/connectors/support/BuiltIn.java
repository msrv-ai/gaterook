package ai.msrv.gaterook.connectors.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/** Facts every built-in connector manifest shares. */
public final class BuiltIn {

  private static final String BUILD_PROPERTIES = "ai/msrv/gaterook/connectors/support/build.properties";

  /**
   * Version of the built-in connectors: they ship with the gateway, so it is the gateway build's version. The value
   * comes from a resource filtered by Maven, which keeps the version in one place (the root pom).
   */
  public static final String VERSION = readVersion();

  private BuiltIn() {}

  private static String readVersion() {
    // Read at run time, never baked into the native image heap; the resource is registered in
    // META-INF/native-image/ai.msrv.gaterook/connectors-support-manual.
    try (InputStream in = BuiltIn.class.getClassLoader().getResourceAsStream(BUILD_PROPERTIES)) {
      if (in == null) {
        throw new IllegalStateException(BUILD_PROPERTIES + " is missing from the classpath");
      }
      Properties p = new Properties();
      p.load(in);
      String version = p.getProperty("version");
      if (version == null || version.isBlank() || version.startsWith("@")) {
        throw new IllegalStateException(BUILD_PROPERTIES + " was not filtered by the build: " + version);
      }
      return version;
    } catch (IOException e) {
      throw new UncheckedIOException("Can't read " + BUILD_PROPERTIES, e);
    }
  }
}
