package ai.msrv.gaterook.core.connections.internal;

import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.core.platform.MaskPolicy;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.Taint;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Masks what a connector's {@code test} says before a person sees it or the core stores it: an error message may echo
 * the password the server refused. Masked by the call's taint and the secret rules, without the entropy check: host
 * key fingerprints and certificate digests look random by design and must stay readable to be trusted.
 */
public final class TestResults {

  private static final MaskPolicy POLICY = new MaskPolicy(false, Set.of(), List.of());

  private TestResults() {}

  public static TestResult mask(TestResult r, Masker masker, Taint taint) {
    return mask(r, masker, taint, tree(masker, taint, r.details()));
  }

  /**
   * Successful administrative database discovery supplies identifiers the form saves verbatim. A name may happen
   * to match a password; masking it would select a different database. Only the typed choice list is exempt, not
   * diagnostics, failed tests, or agent-facing results.
   */
  public static TestResult maskDatabaseDiscovery(TestResult r, Masker masker, Taint taint) {
    if (!r.ok() || r.needsConfirmation() != null || !(r.details() instanceof ObjectNode details)
        || !details.path("databases").isArray()) {
      return mask(r, masker, taint);
    }
    for (JsonNode name : details.path("databases")) {
      if (!name.isString() && !(name.isIntegralNumber() && name.canConvertToInt() && name.asInt() >= 0)) {
        return mask(r, masker, taint);
      }
    }
    ObjectNode diagnostics = details.deepCopy();
    JsonNode names = diagnostics.remove("databases");
    ObjectNode masked = (ObjectNode) tree(masker, taint, diagnostics);
    masked.set("databases", names);
    return mask(r, masker, taint, masked);
  }

  /** The most fields, notes and characters a form is offered; anything beyond is left out. */
  private static final int MAX_FIELDS = 64;
  private static final int MAX_NOTES = 8;
  private static final int MAX_VALUE = 1024;
  private static final int MAX_TEXT = 300;
  private static final int MAX_NOTE = 400;

  /**
   * Suggestions for a setup form ({@code POST /api/connections/suggest}), masked by the request's taint and the secret
   * rules. A value goes into a field verbatim, so an option or a value to fill in that masking would change is left
   * out rather than offered changed (a namespace that happens to equal the typed password); labels, details, sources
   * and notes are masked like any text. Pointers that aren't JSON Pointers, control characters and oversized texts
   * are dropped, and the lists are bounded.
   */
  public static Suggestions maskSuggestions(Suggestions s, Masker masker, Taint taint) {
    Suggestions.Builder out = Suggestions.builder();
    int fields = 0;
    for (Map.Entry<String, Suggestions.Field> e : s.fields().entrySet()) {
      String pointer = e.getKey();
      if (fields >= MAX_FIELDS || pointer == null || !pointer.startsWith("/") || pointer.length() > 256
          || !plain(pointer)) {
        continue;
      }
      Suggestions.Field f = e.getValue();
      List<Suggestions.Option> options = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      for (Suggestions.Option o : f.options()) {
        String value = verbatim(masker, taint, o.value());
        if (value == null || !seen.add(value)) {
          continue;
        }
        options.add(new Suggestions.Option(value, short_(masker, taint, o.label()), short_(masker, taint,
            o.detail())));
      }
      String value = verbatim(masker, taint, f.value());
      if (options.isEmpty() && value == null) {
        continue;
      }
      out.field(pointer, new Suggestions.Field(options, value, short_(masker, taint, f.source()),
          f.complete() && options.size() == f.options().size()));
      fields++;
    }
    s.notes().stream().limit(MAX_NOTES).map(n -> text(masker, taint, n))
        .filter(n -> n != null && !n.isBlank())
        .map(n -> n.length() > MAX_NOTE ? n.substring(0, MAX_NOTE - 1) + "…" : n)
        .forEach(out::note);
    return out.build();
  }

  /** {@code value} when masking leaves it as it is and it is a plain, bounded line; otherwise {@code null}. */
  private static String verbatim(Masker masker, Taint taint, String value) {
    if (value == null || value.isEmpty() || value.length() > MAX_VALUE || !plain(value)) {
      return null;
    }
    return value.equals(text(masker, taint, value)) ? value : null;
  }

  private static String short_(Masker masker, Taint taint, String value) {
    if (value == null || value.isBlank() || !plain(value)) {
      return null;
    }
    String masked = text(masker, taint, value);
    return masked.length() > MAX_TEXT ? masked.substring(0, MAX_TEXT - 1) + "…" : masked;
  }

  /** No control characters: a suggestion is one line the person reads and may put into a field. */
  private static boolean plain(String value) {
    return value.chars().noneMatch(c -> c < 0x20 || c == 0x7f || (c >= 0x80 && c < 0xa0) || c == 0x2028
        || c == 0x2029);
  }

  private static TestResult mask(TestResult r, Masker masker, Taint taint, JsonNode details) {
    TestResult.Permissions p = r.permissions() == null ? null : new TestResult.Permissions(r.permissions().level(),
        text(masker, taint, r.permissions().read()), text(masker, taint, r.permissions().write()),
        r.permissions().admin() == null ? List.of()
            : r.permissions().admin().stream().map(a -> text(masker, taint, a)).toList(),
        tree(masker, taint, r.permissions().details()));
    List<TestResult.Hint> hints = r.hints().stream().map(h -> new TestResult.Hint(h.severity(), h.code(), h.field(),
        text(masker, taint, h.text()))).toList();
    List<TestResult.Check> checks = r.checks().stream().map(c -> new TestResult.Check(c.name(), c.ok(), c.ms(),
        text(masker, taint, c.detail()))).toList();
    TestResult.TestError error = r.error() == null ? null : new TestResult.TestError(r.error().code(),
        text(masker, taint, r.error().message()), text(masker, taint, r.error().technical()));
    TestResult.Confirmation confirmation = r.needsConfirmation() == null ? null : new TestResult.Confirmation(
        r.needsConfirmation().kind(), text(masker, taint, r.needsConfirmation().text()),
        tree(masker, taint, r.needsConfirmation().data()), tree(masker, taint, r.needsConfirmation().configPatch()));
    return new TestResult(r.ok(), r.latencyMs(), text(masker, taint, r.serverVersion()),
        text(masker, taint, r.identity()), p, hints, checks, error, confirmation,
        tree(masker, taint, r.metadataUpdate()), tree(masker, taint, r.suggestedPolicyChange()),
        details);
  }

  private static String text(Masker masker, Taint taint, String value) {
    return value == null ? null : masker.text(value, taint, POLICY).text();
  }

  private static JsonNode tree(Masker masker, Taint taint, JsonNode value) {
    return value == null ? null : masker.tree(value, taint, POLICY).tree();
  }
}
