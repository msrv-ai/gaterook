package ai.msrv.gaterook.core.platform;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import org.springframework.core.NativeDetector;

/** The version and runtime of this core build, as the ready line and {@code /api/health} report them. */
public final class CoreBuild {

  private static final String PROPERTIES = "ai/msrv/gaterook/core/platform/core.properties";

  /** The gateway version from a resource the build filters: the root pom is the only source. */
  public static final String VERSION = readVersion();

  private CoreBuild() {}

  /** {@code native} inside a native image, else {@code jvm}. */
  public static String runtime() {
    return NativeDetector.inNativeImage() ? "native" : "jvm";
  }

  private static String readVersion() {
    // Read at run time from a resource registered by CoreHints, never baked into the image heap.
    try (InputStream in = CoreBuild.class.getClassLoader().getResourceAsStream(PROPERTIES)) {
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
