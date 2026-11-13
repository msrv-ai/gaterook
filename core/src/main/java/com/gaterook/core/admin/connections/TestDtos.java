package com.gaterook.core.admin.connections;

import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.TestResult;
import com.gaterook.core.connections.ConnectionTester;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

  /**
   * {@code POST /api/connections/suggest}: what the setup form offers, by the JSON Pointer of each config field, and
   * notes on what was found or comes next. Already masked by the tester.
   */
  public record Suggested(Map<String, SuggestedField> fields, List<String> notes) {}

  /**
   * {@code fill}: what to fill in while the field is empty or holds its default; {@code complete}: nothing cut. No
   * property is named like a secret's value (AdminResponseTypesTest): a suggestion is never one, as the answer is
   * masked and an option a mask would change is left out.
   */
  public record SuggestedField(List<SuggestedOption> options, String fill, String source, boolean complete) {}

  /** {@code option}: what picking it puts in the field. */
  public record SuggestedOption(String option, String label, String detail) {}

  private TestDtos() {}

  public static Suggested suggested(Suggestions s) {
    Map<String, SuggestedField> fields = new LinkedHashMap<>();
    s.fields().forEach((pointer, f) -> fields.put(pointer, new SuggestedField(f.options().stream()
        .map(o -> new SuggestedOption(o.value(), o.label(), o.detail())).toList(), f.value(), f.source(),
        f.complete())));
    return new Suggested(fields, s.notes());
  }

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
