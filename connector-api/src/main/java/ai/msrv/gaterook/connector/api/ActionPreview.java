package ai.msrv.gaterook.connector.api;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * What the approver sees.
 *
 * <p>{@code text} is exactly what runs (SQL as sent, wire command of a runbook, HTTP request with ‹masked› auth
 * headers, unified diff): never a paraphrase, never normalized; the UI escapes invisible and confusable characters.
 * {@code payload} is the canonical request stored with the approval and executed after it
 * ({@link ExecContext#approvedPayload()}).
 *
 * @param language {@code sql | sh | json | yaml | http | diff | text}
 * @param target   e.g. {@code "dev-db · db.dev.internal:5432/app"}
 * @param summary  ≤ 120 chars, for channels
 */
public record ActionPreview(Kind kind, String language, String text, String target, String summary,
    List<Field> fields, List<Field> impact, List<String> warnings, Map<String, String> auditFacets,
    JsonNode payload, JsonNode extra) {

  public enum Kind { SQL, COMMAND, HTTP, DIFF, RUNBOOK, JSON, TEXT }

  public record Field(String label, String value) {}

  public static ActionPreview of(Kind kind, String language, String text, String target, String summary) {
    return new ActionPreview(kind, language, text, target, summary, List.of(), List.of(), List.of(), Map.of(),
        null, null);
  }

  public ActionPreview withFields(List<Field> v) {
    return new ActionPreview(kind, language, text, target, summary, List.copyOf(v), impact, warnings,
        auditFacets, payload, extra);
  }

  public ActionPreview withImpact(List<Field> v) {
    return new ActionPreview(kind, language, text, target, summary, fields, List.copyOf(v), warnings,
        auditFacets, payload, extra);
  }

  public ActionPreview withWarnings(List<String> v) {
    return new ActionPreview(kind, language, text, target, summary, fields, impact, List.copyOf(v), auditFacets,
        payload, extra);
  }

  public ActionPreview withFacets(Map<String, String> v) {
    return new ActionPreview(kind, language, text, target, summary, fields, impact, warnings, Map.copyOf(v),
        payload, extra);
  }

  public ActionPreview withPayload(JsonNode v) {
    return new ActionPreview(kind, language, text, target, summary, fields, impact, warnings, auditFacets, v,
        extra);
  }
}
