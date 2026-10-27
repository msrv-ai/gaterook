package ai.msrv.gaterook.connector.api;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Result of Test connection: latency, version, identity, what the account may really do, hints in the kit's tone.
 *
 * <p>{@code needsConfirmation} stops the test before credentials leave; the core applies its {@code configPatch}
 * (Setup changes) after "Trust" and runs the test again.
 *
 * @param metadataUpdate        non-secret snapshot for classify(): k8s discovery, kubeconfig contexts
 * @param suggestedPolicyChange e.g. {@code {"kind":"allow-private","cidr":"10.30.4.12/32"}}
 */
public record TestResult(boolean ok, long latencyMs, String serverVersion, String identity, Permissions permissions,
    List<Hint> hints, List<Check> checks, TestError error, Confirmation needsConfirmation, JsonNode metadataUpdate,
    JsonNode suggestedPolicyChange, JsonNode details) {

  public enum Severity { INFO, WARNING, DANGER, ERROR }

  public enum Level { READ, WRITE, ADMIN, UNKNOWN }

  public record Permissions(Level level, String read, String write, List<String> admin, JsonNode details) {}

  public record Hint(Severity severity, String code, String field, String text) {}

  public record Check(String name, boolean ok, long ms, String detail) {}

  /** {@code message} says what happened and what to do. */
  public record TestError(String code, String message, String technical) {}

  /** {@code kind}: host-key | tls-cert | exec-plugin. */
  public record Confirmation(String kind, String text, JsonNode data, JsonNode configPatch) {}

  public static TestResult success(long latencyMs, String serverVersion, String identity, Permissions p) {
    return new TestResult(true, latencyMs, serverVersion, identity, p, List.of(), List.of(), null, null, null, null,
        null);
  }

  public static TestResult failure(String code, String message, String technical) {
    return new TestResult(false, 0, null, null, null, List.of(), List.of(), new TestError(code, message, technical),
        null, null, null, null);
  }

  public static TestResult confirm(Confirmation c, List<Check> checksSoFar) {
    return new TestResult(false, 0, null, null, null, List.of(), List.copyOf(checksSoFar), null, c, null, null,
        null);
  }

  public TestResult withHint(Hint h) {
    List<Hint> l = new ArrayList<>(hints);
    l.add(h);
    return new TestResult(ok, latencyMs, serverVersion, identity, permissions, List.copyOf(l), checks, error,
        needsConfirmation, metadataUpdate, suggestedPolicyChange, details);
  }

  public TestResult withChecks(List<Check> v) {
    return new TestResult(ok, latencyMs, serverVersion, identity, permissions, hints, List.copyOf(v), error,
        needsConfirmation, metadataUpdate, suggestedPolicyChange, details);
  }

  public TestResult withMetadata(JsonNode v) {
    return new TestResult(ok, latencyMs, serverVersion, identity, permissions, hints, checks, error,
        needsConfirmation, v, suggestedPolicyChange, details);
  }

  public TestResult withSuggestedPolicyChange(JsonNode v) {
    return new TestResult(ok, latencyMs, serverVersion, identity, permissions, hints, checks, error,
        needsConfirmation, metadataUpdate, v, details);
  }

  public TestResult withDetails(JsonNode v) {
    return new TestResult(ok, latencyMs, serverVersion, identity, permissions, hints, checks, error,
        needsConfirmation, metadataUpdate, suggestedPolicyChange, v);
  }
}
