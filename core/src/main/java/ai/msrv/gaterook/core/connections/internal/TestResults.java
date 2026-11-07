package ai.msrv.gaterook.core.connections.internal;

import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.core.platform.MaskPolicy;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.Taint;
import java.util.List;
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
