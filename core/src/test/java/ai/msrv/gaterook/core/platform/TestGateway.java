package ai.msrv.gaterook.core.platform;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * A development bootstrap for {@code @SpringBootTest}: its own gateway home under {@code target/} (one per test
 * class, so the home lock never collides), a fixed launch secret, a free port.
 */
public final class TestGateway {

  /** 32 bytes, base64url: what the tests sign their admin requests with. */
  public static final String LAUNCH_SECRET = Base64.getUrlEncoder().withoutPadding()
      .encodeToString("test-launch-secret-0123456789abc".getBytes(StandardCharsets.US_ASCII));

  private TestGateway() {}

  /** Registers the properties of a test core whose home is {@code target/gateway-homes/<name>-<random>}. */
  public static Path register(DynamicPropertyRegistry registry, String name) {
    Path home = newHome(name);
    registry.add("gateway.home", home::toString);
    registry.add("gateway.dev", () -> "true");
    registry.add("gateway.dev.launch-secret", () -> LAUNCH_SECRET);
    registry.add("server.port", () -> "0");
    return home;
  }

  /** A fresh, empty directory for a gateway home. */
  public static Path newHome(String name) {
    try {
      Path parent = Path.of("target", "gateway-homes").toAbsolutePath();
      Files.createDirectories(parent);
      return Files.createTempDirectory(parent, name + "-");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static LaunchSecret launchSecret() {
    return LaunchSecret.fromBase64Url(LAUNCH_SECRET);
  }
}
