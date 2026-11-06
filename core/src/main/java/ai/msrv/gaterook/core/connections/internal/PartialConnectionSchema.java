package ai.msrv.gaterook.core.connections.internal;

import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Only disabled import drafts may omit required values; values that are present keep their schema constraints. */
public final class PartialConnectionSchema {
  private PartialConnectionSchema() {}

  public static JsonNode relax(JsonNode schema) {
    if (!(schema instanceof ObjectNode)) return schema.deepCopy();
    ObjectNode out = (ObjectNode) schema.deepCopy();
    out.remove("required");
    out.remove("dependentRequired");
    for (String map : List.of("properties", "patternProperties", "$defs", "definitions", "dependentSchemas")) {
      if (out.path(map) instanceof ObjectNode entries) {
        for (String key : List.copyOf(entries.propertyNames())) entries.set(key, relax(entries.get(key)));
      }
    }
    for (String key : List.of("items", "additionalProperties", "unevaluatedProperties", "then", "else")) {
      if (out.path(key).isObject()) out.set(key, relax(out.get(key)));
    }
    for (String key : List.of("oneOf", "anyOf", "allOf", "prefixItems")) {
      if (out.path(key) instanceof ArrayNode branches) {
        ArrayNode relaxed = JsonNodeFactory.instance.arrayNode();
        branches.forEach(branch -> relaxed.add(relax(branch)));
        out.set(key, relaxed);
      }
    }
    // Missing discriminators can make several partial branches valid; full validation restores oneOf on completion.
    if (out.has("oneOf")) {
      JsonNode choices = out.remove("oneOf");
      ObjectNode union = JsonNodeFactory.instance.objectNode();
      union.set("anyOf", choices);
      ArrayNode all = out.path("allOf") instanceof ArrayNode existing ? existing : out.putArray("allOf");
      all.add(union);
    }
    if (out.path("minItems").asInt(0) > 0) {
      ObjectNode allowed = JsonNodeFactory.instance.objectNode();
      allowed.putArray("anyOf").add(out).addObject().putArray("const");
      return allowed;
    }
    return out;
  }

  /** Blank scalar inputs represent unavailable settings; empty arrays retain their explicit scope semantics. */
  public static ObjectNode available(ObjectNode config) {
    ObjectNode out = config.deepCopy();
    clean(out);
    return out;
  }

  private static void clean(JsonNode node) {
    if (node instanceof ObjectNode object) {
      for (String key : List.copyOf(object.propertyNames())) {
        JsonNode value = object.get(key);
        if (value.isNull() || value.isString() && value.asString().isBlank()) object.remove(key);
        else clean(value);
      }
    } else if (node.isArray()) node.forEach(PartialConnectionSchema::clean);
  }
}
