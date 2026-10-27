package ai.msrv.gaterook.core.admin.connections;

import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.core.connections.ConnectionTester;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Test connection as the form shows it (ARCHITECTURE.md §6 connection-new, kit {@code connection-form}): the
 * connector's {@link TestResult}, already masked by the tester, in the admin API's own records. A test that waits for
 * "Trust" carries {@code confirmationId} and what to trust (fingerprints), never the patch the core applies.
 */
public final class TestDtos {

  /** {@code POST /api/connections/test}, {@code …/{name}/test}: the result of one test. */
  public record Outcome(boolean ok, long latencyMs, String serverVersion, String identity, Permissions permissions,
      List<Hint> hints, List<Check> checks, Error error, Confirmation needsConfirmation, String confirmationId,
      JsonNode suggestedPolicyChange, JsonNode details) {}

  /** What the account may really do: {@code level} {@code read}, {@code write}, {@code admin} or {@code unknown}. */
  public record Permissions(String level, String read, String write, List<String> admin, JsonNode details) {}

  /**
   * A hint in the kit's tone; {@code snippet} is a copyable fix (SQL "Create a read-only user"), when the connector
   * put one in {@code details.snippets.<code>}.
   */
  public record Hint(String severity, String code, String field, String text, Snippet snippet) {}

  public record Snippet(String language, String text) {}

  public record Check(String name, boolean ok, long ms, String detail) {}

  /** {@code message}: what happened and what to do; {@code technical}: the driver's words, masked. */
  public record Error(String code, String message, String technical) {}

  /** {@code kind}: {@code host-key}, {@code tls-cert} or {@code exec-plugin}. */
  public record Confirmation(String kind, String text, JsonNode data) {}

  /** {@code POST …/test/confirm}: the draft's patched config, or the test that ran again for a stored connection. */
  public record Confirmed(String kind, JsonNode config, Outcome retest) {}

  private TestDtos() {}

  public static Outcome outcome(ConnectionTester.Outcome o) {
    TestResult r = o.result();
    JsonNode snippets = r.details() == null ? null : r.details().path("snippets");
    List<Hint> hints = r.hints() == null ? List.of() : r.hints().stream().map(h -> {
      JsonNode s = snippets == null || h.code() == null ? null : snippets.path(h.code());
      Snippet snippet = s != null && s.path("text").isString() ? new Snippet(s.path("language").asString("text"),
          s.get("text").asString()) : null;
      return new Hint(lower(h.severity()), h.code(), h.field(), h.text(), snippet);
    }).toList();
    List<Check> checks = r.checks() == null ? List.of() : r.checks().stream()
        .map(c -> new Check(c.name(), c.ok(), c.ms(), c.detail())).toList();
    TestResult.Permissions p = r.permissions();
    TestResult.Confirmation c = r.needsConfirmation();
    return new Outcome(r.ok(), r.latencyMs(), r.serverVersion(), r.identity(),
        p == null ? null : new Permissions(lower(p.level()), p.read(), p.write(),
            p.admin() == null ? List.of() : List.copyOf(p.admin()), p.details()),
        hints, checks, r.error() == null ? null : new Error(r.error().code(), r.error().message(),
            r.error().technical()),
        c == null ? null : new Confirmation(c.kind(), c.text(), c.data()), o.confirmationId(),
        r.suggestedPolicyChange(), r.details());
  }

  public static Confirmed confirmed(ConnectionTester.ConfirmOutcome o) {
    return new Confirmed(o.kind(), o.config(), o.retest() == null ? null : outcome(o.retest()));
  }

  private static String lower(Enum<?> e) {
    return e == null ? null : e.name().toLowerCase(java.util.Locale.ROOT);
  }
}
