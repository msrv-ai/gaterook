package ai.msrv.gaterook.core.platform.boot;

import ai.msrv.gaterook.core.platform.Bootstrap;
import ai.msrv.gaterook.core.platform.BuildFlags;
import ai.msrv.gaterook.core.platform.LaunchSecret;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.core.env.Environment;

/**
 * The bootstrap of a core started without Electron: {@code mvn spring-boot:run}, the IDE, {@code ng serve} in front of
 * it, and the tests (ARCHITECTURE.md §2.4). Only with {@code gateway.dev=true} and never in a release build; the launch
 * secret comes from {@code GW_DEV_INTERNAL_SECRET} (or the property {@code gateway.dev.launch-secret}, for tests).
 */
final class DevBootstrap {

  static final String SECRET_ENV = "GW_DEV_INTERNAL_SECRET";
  static final String SECRET_PROPERTY = "gateway.dev.launch-secret";

  private DevBootstrap() {}

  /** Whether the launcher may skip stdin: a dev run with its secret in the environment. */
  static boolean requestedByLauncher() {
    return !BuildFlags.RELEASE && Boolean.getBoolean("gateway.dev") && System.getenv(SECRET_ENV) != null;
  }

  static Bootstrap from(Environment environment) {
    boolean dev = environment.getProperty("gateway.dev", Boolean.class, false);
    if (BuildFlags.RELEASE || !dev) {
      throw new GatewayStartupException(StartupReason.BOOTSTRAP_TIMEOUT, "No bootstrap: the core must be started by "
          + "MCP Gateway (development builds: -Dgateway.dev=true and " + SECRET_ENV + ")");
    }
    String secret = environment.getProperty(SECRET_PROPERTY, System.getenv(SECRET_ENV) == null ? ""
        : System.getenv(SECRET_ENV));
    LaunchSecret launchSecret;
    try {
      launchSecret = LaunchSecret.fromBase64Url(secret);
    } catch (IllegalArgumentException e) {
      throw new GatewayStartupException(StartupReason.BOOTSTRAP_INVALID, SECRET_ENV
          + " must be at least " + LaunchSecret.MIN_KEY_BYTES + " bytes of base64url (openssl rand -base64 32 | tr '+/' '-_')");
    }
    int port = environment.getProperty("server.port", Integer.class, 7465);
    int publicPort = environment.getProperty("gateway.public-port", Integer.class, 7466);
    return new Bootstrap(launchSecret, randomNonce(), null, "off", port, publicPort, true);
  }

  private static String randomNonce() {
    byte[] bytes = new byte[16];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
