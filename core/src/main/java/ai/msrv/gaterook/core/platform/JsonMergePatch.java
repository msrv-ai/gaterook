package ai.msrv.gaterook.core.platform;

import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * RFC 7396 JSON Merge Patch: what a connector's {@code TestResult.Confirmation.configPatch} means (the trusted host key
 * or certificate added to the config after "Trust").
 */
public final class JsonMergePatch {

  private JsonMergePatch() {}

  /** {@code target} patched by {@code patch}; neither argument is modified. */
  public static JsonNode apply(JsonNode target, JsonNode patch) {
    if (patch == null || !patch.isObject()) {
      return patch == null ? target : patch.deepCopy();
    }
    ObjectNode out = target != null && target.isObject() ? (ObjectNode) target.deepCopy()
        : JsonNodeFactory.instance.objectNode();
    for (Map.Entry<String, JsonNode> e : patch.properties()) {
      if (e.getValue().isNull()) {
        out.remove(e.getKey());
      } else {
        out.set(e.getKey(), apply(out.get(e.getKey()), e.getValue()));
      }
    }
    return out;
  }
}
