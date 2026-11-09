package ai.msrv.gaterook.connectors.support;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The schema keyword {@value #KEYWORD}: a config property that only the person sets. Two kinds of settings carry it:
 * those that record the person's own decision ("Trust" after Test connection: a host key, a pinned certificate or
 * cluster, an allowed credential command) and the opt-ins that let GateRook run a command on this computer. An AI
 * agent's import proposal must not hold such a property, so the person is asked instead of finding the decision made;
 * the person sets it through Test connection, then Trust, or in the connection form.
 *
 * <p>A connector marks the property in its {@code configSchema} ({@code "x-person-only": true}); a property whose
 * Test connection patch writes it, or whose description says Test connection fills it, must carry the mark. The mark
 * on an object covers everything inside it. It changes nothing in the connection form or in the validation of a
 * saved config.
 */
public final class PersonOnly {

  /** The schema keyword. */
  public static final String KEYWORD = "x-person-only";

  private static final int MAX_DEPTH = 32;

  private PersonOnly() {}

  /** Whether this property schema carries the mark. */
  public static boolean isMarked(JsonNode definition) {
    return definition != null && definition.isObject() && definition.path(KEYWORD).asBoolean(false);
  }

  /**
   * Whether the property at {@code pointer} ({@code /hostKeys/pinned}, {@code /jump/hostKeys/pinned}; an array index
   * may be {@code *} or a number) is marked, or sits inside one that is. The walk goes through {@code properties},
   * {@code items}, the branches of {@code allOf}/{@code anyOf}/{@code oneOf}, {@code then}/{@code else} and local
   * {@code $ref}s, as the connection form does.
   */
  public static boolean covers(JsonNode configSchema, String pointer) {
    if (configSchema == null || pointer == null || !pointer.startsWith("/")) {
      return false;
    }
    String[] parts = pointer.substring(1).split("/", -1);
    List<String> path = new ArrayList<>();
    for (String part : parts) {
      path.add(part.replace("~1", "/").replace("~0", "~"));
    }
    return covers(configSchema, configSchema, path, 0, 0);
  }

  private static boolean covers(JsonNode node, JsonNode root, List<String> path, int index, int depth) {
    if (node == null || !node.isObject() || depth > MAX_DEPTH) {
      return false;
    }
    if (isMarked(node)) {
      return true;
    }
    if (index < path.size()) {
      String part = path.get(index);
      if (covers(node.path("properties").get(part), root, path, index + 1, depth + 1)) {
        return true;
      }
      if ((part.equals("*") || part.matches("\\d{1,4}")) && covers(node.get("items"), root, path, index + 1, depth + 1)) {
        return true;
      }
    }
    for (String combinator : new String[] {"allOf", "anyOf", "oneOf"}) {
      for (JsonNode branch : node.path(combinator)) {
        if (covers(branch, root, path, index, depth + 1)) {
          return true;
        }
      }
    }
    for (String conditional : new String[] {"then", "else"}) {
      if (covers(node.get(conditional), root, path, index, depth + 1)) {
        return true;
      }
    }
    String ref = node.path("$ref").asString("");
    return ref.startsWith("#/") && covers(root.at(ref.substring(1)), root, path, index, depth + 1);
  }
}
