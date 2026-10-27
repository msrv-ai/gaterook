package ai.msrv.gaterook.connectors.logs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The servers of {@code test-services/docker-compose.yml} (test-services/README.md): the ITs connect to them instead
 * of starting their own when the system property {@code gateway.it.services} ({@code -Pservices}) or the variable
 * {@code GW_TEST_SERVICES} names that folder. Ports and test passwords come from its {@code .env}, the file Compose
 * reads as well.
 */
final class TestServices {

  private static final Path DIR = locate();
  private static final Map<String, String> ENV = DIR == null ? Map.of() : read(DIR.resolve(".env"));

  private TestServices() {}

  /** Whether the ITs use the Compose servers. */
  static boolean enabled() {
    return DIR != null;
  }

  /** A value of {@code .env}, e.g. {@code GW_PG_PASSWORD}. */
  static String value(String key) {
    String v = ENV.get(key);
    if (v == null) {
      throw new IllegalStateException(key + " isn't in " + DIR.resolve(".env"));
    }
    return v;
  }

  /** A port of {@code .env}, published on 127.0.0.1. */
  static int port(String key) {
    return Integer.parseInt(value(key));
  }

  /** A file the services made in the folder, e.g. {@code run/certs/ca.crt}. */
  static Path file(String relative) {
    return DIR.resolve(relative);
  }

  private static Path locate() {
    String dir = System.getProperty("gateway.it.services");
    if (dir == null || dir.isBlank()) {
      dir = System.getenv("GW_TEST_SERVICES");
    }
    if (dir == null || dir.isBlank()) {
      return null;
    }
    Path p = Path.of(dir).toAbsolutePath();
    if (!Files.isRegularFile(p.resolve(".env"))) {
      throw new IllegalStateException(p + " has no .env: gateway.it.services and GW_TEST_SERVICES name the "
          + "test-services folder");
    }
    return p;
  }

  private static Map<String, String> read(Path env) {
    Map<String, String> values = new HashMap<>();
    try {
      for (String line : Files.readAllLines(env)) {
        String l = line.strip();
        int eq = l.indexOf('=');
        if (!l.startsWith("#") && eq > 0) {
          values.put(l.substring(0, eq).strip(), l.substring(eq + 1).strip());
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return Map.copyOf(values);
  }
}
