package ai.msrv.gaterook.core.secrets;

import ai.msrv.gaterook.connector.api.Env;
import java.util.List;
import java.util.Optional;

/**
 * SPI: what the modules that own secret fields tell the Secrets screen. {@code connections} (M1.4) implements it for
 * connections, {@code mcpservers} for MCP servers, and so on; without an implementation a secret shows no "Used by",
 * no environment and can't be tested before Replace or rotated at the source.
 */
public interface SecretOwners {

  SecretOwner.Kind kind();

  /** Label, environment and users of an owner, or empty when it doesn't exist. */
  Optional<Owner> describe(String ownerId);

  /**
   * Tests the owner with {@code version} of {@code field} instead of the current one ({@code PUT …/value} with
   * {@code testBefore}); empty when the owner can't be tested.
   */
  default Optional<TestOutcome> testWithVersion(String ownerId, String field, int version) {
    return Optional.empty();
  }

  /** Why Rotate is unavailable, or empty when the connector can rotate this field at the source. */
  default Optional<String> rotateUnavailable(String ownerId, String field) {
    return Optional.of("This connector can't issue a new value for itself. Replace it with a new value.");
  }

  /**
   * Rotates {@code field} at the source: from now on the source accepts {@code next}, not the current value
   * ({@code ALTER ROLE … PASSWORD}). Called while {@code next} waits as a pending version; when it throws, the source
   * kept the current value.
   *
   * @throws ai.msrv.gaterook.core.platform.ProblemException 409 when the source refused, with its words
   */
  default void rotate(String ownerId, String field, char[] next) {
    throw new UnsupportedOperationException("Rotation at the source isn't available for " + kind());
  }

  /**
   * @param label  {@code dev-db}
   * @param env    the owner's environment, for prod's mandatory comment; {@code null} if it has none
   * @param usedBy what uses it: {@code ["dev", "incident-prod"]} endpoints, {@code ["github-mcp"]}
   */
  record Owner(String label, Env env, List<String> usedBy) {}

  /** The result of a test with a pending version. */
  record TestOutcome(boolean ok, String message) {}
}
