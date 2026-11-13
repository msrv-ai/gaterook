package com.gaterook.core.templates;

import com.gaterook.core.connectors.ConnectorRegistry;
import com.gaterook.core.platform.MaskPolicy;
import com.gaterook.core.platform.Masker;
import com.gaterook.core.platform.ProblemException;
import com.gaterook.core.platform.SchemaValidator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Portable template definitions, compiled locally without copying another gateway's trust confirmations. */
@Service
public class TemplateShares {
  private static final int MAX_TEMPLATES = 100;
  private static final Set<String> KINDS = Set.of("sql-query", "ssh-runbook", "openapi");
  private static final Set<String> FIELDS = Set.of("id", "name", "kind", "definition");
  private final TemplateService templates;
  private final ConnectorRegistry registry;
  private final SchemaValidator validator;
  private final Masker masker;

  public record Exported(JsonNode templates, List<String> warnings) {}
  public record Imported(Map<String, String> ids, List<String> warnings) {}

  public TemplateShares(TemplateService templates, ConnectorRegistry registry, SchemaValidator validator,
      Masker masker) {
    this.templates = templates;
    this.registry = registry;
    this.validator = validator;
    this.masker = masker;
  }

  public Exported exportFor(String connectionId, boolean includeSecrets) {
    var result = JsonNodeFactory.instance.arrayNode();
    List<String> warnings = new ArrayList<>();
    for (var template : templates.list(null)) {
      if (!connectionId.equals(template.connectionId()) || !"active".equals(template.status())) continue;
      if (!KINDS.contains(template.kind())) {
        warnings.add("A template kind that is not portable was left out. Set it up on the destination computer.");
        continue;
      }
      JsonNode definition = template.verified() ? templates.definition(template.id()).orElse(null) : null;
      if (definition == null) {
        warnings.add("An unverified template was left out. Review its integrity before sharing it.");
        continue;
      }
      if (!includeSecrets && containsSecrets(definition)) {
        warnings.add("A template containing credential-like text was left out because secrets were excluded.");
        continue;
      }
      if (result.size() >= MAX_TEMPLATES) throw invalid("Share at most 100 templates per connection.");
      result.addObject().put("id", template.id()).put("name", template.name()).put("kind", template.kind())
          .set("definition", portableDefinition(template.kind(), definition));
    }
    return new Exported(result, List.copyOf(warnings));
  }

  /** Validate only; this never compiles a template, resolves a secret, or contacts a service. */
  public List<String> validate(JsonNode values, String connectionType) {
    if (values == null || values.isMissingNode()) return List.of();
    if (!values.isArray() || values.size() > MAX_TEMPLATES) throw invalid("Invalid shared template list.");
    Set<String> ids = new HashSet<>();
    Set<String> names = new HashSet<>();
    for (JsonNode value : values) {
      if (!value.isObject() || !FIELDS.containsAll(value.propertyNames())) throw invalid("Invalid shared template fields.");
      String id = text(value, "id", 128);
      String name = text(value, "name", 64);
      String kind = text(value, "kind", 32);
      if (!ids.add(id) || !names.add(kind + ":" + name)) throw invalid("A shared template is listed more than once.");
      var implementation = registry.templateKinds().get(kind);
      if (!KINDS.contains(kind) || implementation == null || !implementation.connectionTypes().contains(connectionType)) {
        throw invalid("A shared template is unavailable for this connection type.");
      }
      JsonNode definition = value.path("definition");
      bounded(definition, 0, new int[] {0});
      if (!definition.isObject() || !validator.validate(implementation.definitionSchema(), definition).isEmpty()) {
        throw invalid("A shared template does not match its definition schema.");
      }
    }
    return values.isEmpty() ? List.of() : List.of(
        "Template definitions are included. Read-only confirmations are cleared; SSH commands and non-GET HTTP operations require review.");
  }

  /** Called inside the endpoint import transaction, before wiring its new connections. */
  public Imported importFor(JsonNode values, String connectionName, boolean includeSecrets, String reason) {
    Map<String, String> ids = new LinkedHashMap<>();
    List<String> warnings = new ArrayList<>();
    if (values == null || values.isMissingNode()) return new Imported(Map.of(), List.of());
    for (JsonNode value : values) {
      JsonNode definition = value.path("definition");
      if (!includeSecrets && containsSecrets(definition)) {
        warnings.add("A template containing credential-like text was skipped because secrets were excluded.");
        continue;
      }
      var result = templates.create(value.path("kind").asString(), connectionName, value.path("name").asString(),
          portableDefinition(value.path("kind").asString(), definition), "Imported from an endpoint share", reason);
      if (result.template() == null || !result.compile().ok()) {
        // A failed compilation must roll back the enclosing import rather than leave a partly working endpoint.
        throw invalid("A shared template could not compile for the imported connection. Review the source template and connection settings.");
      }
      ids.put(value.path("id").asString(), result.template().id());
    }
    return new Imported(Map.copyOf(ids), List.copyOf(warnings));
  }

  public boolean containsSecrets(JsonNode definition) {
    return masker.tree(definition, null, MaskPolicy.SECRETS_ONLY).outcome().masked();
  }

  static JsonNode portableDefinition(String kind, JsonNode definition) {
    ObjectNode copy = (ObjectNode) TemplateService.withoutConfirmations(definition);
    if ("ssh-runbook".equals(kind)) copy.put("readOnly", false);
    if ("openapi".equals(kind)) {
      for (JsonNode operation : copy.path("operations")) {
        if (operation instanceof ObjectNode op && !Set.of("GET", "HEAD").contains(op.path("method").asString())) {
          op.put("nature", "mutation");
        }
      }
    }
    return copy;
  }

  private static String text(JsonNode value, String field, int max) {
    JsonNode node = value.path(field);
    if (!node.isString() || node.asString().isBlank() || node.asString().length() > max
        || node.asString().codePoints().anyMatch(c -> c < 32 || c == 127)) throw invalid("Invalid shared template metadata.");
    return node.asString();
  }

  private static void bounded(JsonNode value, int depth, int[] count) {
    if (depth > 24 || ++count[0] > 30_000) throw invalid("A shared template is too complex.");
    if (value.isObject()) {
      if (value.has("$secret")) throw invalid("Shared templates cannot refer to stored secret identifiers.");
      for (JsonNode child : value) bounded(child, depth + 1, count);
    } else if (value.isArray()) for (JsonNode child : value) bounded(child, depth + 1, count);
    else if (value.isString() && value.asString().length() > 262_144) throw invalid("A shared template value is too large.");
  }

  private static ProblemException invalid(String message) {
    return ProblemException.unprocessable("Check endpoint share", message, "/bundle/connections/templates");
  }
}
