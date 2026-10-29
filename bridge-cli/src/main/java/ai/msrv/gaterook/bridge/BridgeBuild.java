package ai.msrv.gaterook.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/** The application version shared by the broker handshake and the bridge's {@code --version} output. */
final class BridgeBuild {

  private static final String PROPERTIES = "ai/msrv/gaterook/bridge/build.properties";

  static final String VERSION = readVersion();

  private BridgeBuild() {}

  private static String readVersion() {
    try (InputStream in = BridgeBuild.class.getClassLoader().getResourceAsStream(PROPERTIES)) {
      if (in == null) {
        throw new IllegalStateException(PROPERTIES + " is missing from the classpath");
      }
      Properties properties = new Properties();
      properties.load(in);
      String version = properties.getProperty("version");
      if (version == null || version.isBlank() || version.startsWith("@")) {
        throw new IllegalStateException(PROPERTIES + " was not filtered by the build: " + version);
      }
      return version;
    } catch (IOException e) {
      throw new UncheckedIOException("Can't read " + PROPERTIES, e);
    }
  }
}
