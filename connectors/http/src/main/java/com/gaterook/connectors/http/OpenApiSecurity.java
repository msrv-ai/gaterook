package com.gaterook.connectors.http;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Maps supported OpenAPI credentials without copying examples, defaults or secret values. */
final class OpenApiSecurity {
  private OpenApiSecurity() {}

  static ObjectNode config(JsonNode scheme) {
    ObjectNode auth = JsonNodeFactory.instance.objectNode();
    String type = scheme.path("type").asString("");
    if (type.equals("http")) {
      String http = scheme.path("scheme").asString("");
      if (!http.equalsIgnoreCase("bearer") && !http.equalsIgnoreCase("basic")) return null;
      auth.put("type", http.toLowerCase(java.util.Locale.ROOT));
    } else if (type.equals("apiKey")) {
      String where = scheme.path("in").asString("");
      String name = scheme.path("name").asString("");
      if (name.isBlank()) return null;
      if (where.equals("header") && Headers.validName(name) && !Headers.isForbidden(name)) {
        auth.put("type", "apiKeyHeader");
        auth.put("headerName", name);
      } else if (where.equals("query")) {
        auth.put("type", "apiKeyQuery");
        auth.put("queryName", name);
      } else return null;
    } else return null;
    return auth;
  }

  /** OR between requirements, AND within one requirement; explicit [] overrides global security. */
  static String refusal(JsonNode root, JsonNode op, HttpSettings settings) {
    JsonNode requirements = op.has("security") ? op.get("security") : root.get("security");
    if (requirements == null) return null;
    if (!requirements.isArray()) return "security must be an array";
    if (requirements.isEmpty()) return null;
    List<String> reasons = new ArrayList<>();
    for (JsonNode alternative : requirements) {
      if (!alternative.isObject()) { reasons.add("invalid security requirement"); continue; }
      if (alternative.size() > 1) { reasons.add("combined authentication schemes are not supported"); continue; }
      boolean matches = true;
      for (String name : alternative.propertyNames()) {
        JsonNode scheme = OpenApiDocument.deref(root, root.path("components").path("securitySchemes").path(name));
        ObjectNode expected = scheme == null ? null : config(scheme);
        if (expected == null) {
          reasons.add("unsupported authentication " + ToolText.clean(name, 64));
          matches = false;
        } else if (!matches(expected, settings)) {
          reasons.add("configure credentials for " + ToolText.clean(name, 64));
          matches = false;
        }
      }
      if (matches) return null;
    }
    return String.join("; ", reasons.stream().distinct().toList());
  }

  private static boolean matches(JsonNode expected, HttpSettings settings) {
    String type = expected.path("type").asString("");
    if (!type.equals(settings.auth().type().id)) return false;
    if (type.equals("apiKeyHeader")) return expected.path("headerName").asString("").equalsIgnoreCase(settings.auth().name());
    if (type.equals("apiKeyQuery")) return expected.path("queryName").asString("").equals(settings.auth().name());
    return true;
  }
}
