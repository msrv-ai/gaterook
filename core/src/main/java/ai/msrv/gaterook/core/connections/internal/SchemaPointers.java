package ai.msrv.gaterook.core.connections.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;

/**
 * Where the properties of a {@code configSchema} sit in a config, as JSON Pointer templates in which {@code *} stands
 * for an array index ({@code /secretHeaders/*}{@code /value}), and the values a concrete config holds at a template.
 * The walk goes through {@code properties}, {@code items}, the branches of {@code oneOf}/{@code anyOf}/{@code allOf},
 * {@code then}/{@code else}, and local {@code $ref}s ({@code "#/properties/credentials"}: the SSH bastion and the
 * OpenSearch AWS credentials reuse another part of their schema).
 */
public final class SchemaPointers {

  /** One property schema and where its values sit. */
  public record Located(String template, JsonNode schema) {}

  /** Plugin manifests carry schemas too: a schema that unfolds into more than this is not walked further. */
  private static final int MAX_NODES = 20_000;

  private SchemaPointers() {}

  /** Every property schema for which {@code wanted} holds, in schema order; one per template and schema. */
  public static List<Located> collect(JsonNode schema, Predicate<JsonNode> wanted) {
    List<Located> out = new ArrayList<>();
    new Walk(schema, wanted, out).visit(schema, "", 0);
    return out;
  }

  /** The values {@code config} holds at a template, by concrete pointer; missing ones are left out. */
  public static Map<String, JsonNode> values(JsonNode config, String template) {
    Map<String, JsonNode> out = new LinkedHashMap<>();
    String[] parts = template.isEmpty() ? new String[0] : template.substring(1).split("/", -1);
    values(config, parts, 0, "", out);
    return out;
  }

  private static void values(JsonNode node, String[] parts, int i, String pointer, Map<String, JsonNode> out) {
    if (node == null || node.isMissingNode()) {
      return;
    }
    if (i == parts.length) {
      out.put(pointer, node);
      return;
    }
    if ("*".equals(parts[i])) {
      if (node.isArray()) {
        for (int index = 0; index < node.size(); index++) {
          values(node.get(index), parts, i + 1, pointer + "/" + index, out);
        }
      }
    } else if (node.isObject()) {
      values(node.get(SecretFields.unescape(parts[i])), parts, i + 1, pointer + "/" + parts[i], out);
    }
  }

  private static final class Walk {
    private final JsonNode root;
    private final Predicate<JsonNode> wanted;
    private final List<Located> out;
    /** The {@code $ref}s being unfolded on the current path: a schema that refers to itself stops there. */
    private final Deque<String> refs = new ArrayDeque<>();
    private int nodes;

    Walk(JsonNode root, Predicate<JsonNode> wanted, List<Located> out) {
      this.root = root;
      this.wanted = wanted;
      this.out = out;
    }

    void visit(JsonNode schema, String pointer, int depth) {
      if (schema == null || !schema.isObject() || depth > 32 || ++nodes > MAX_NODES) {
        return;
      }
      if (wanted.test(schema) && out.stream().noneMatch(l -> l.template().equals(pointer) && l.schema() == schema)) {
        out.add(new Located(pointer, schema));
      }
      for (Map.Entry<String, JsonNode> p : schema.path("properties").properties()) {
        visit(p.getValue(), pointer + "/" + SecretFields.escape(p.getKey()), depth + 1);
      }
      if (schema.path("items").isObject()) {
        visit(schema.get("items"), pointer + "/*", depth + 1);
      }
      for (String combinator : new String[] {"oneOf", "anyOf", "allOf"}) {
        for (JsonNode branch : schema.path(combinator)) {
          visit(branch, pointer, depth + 1);
        }
      }
      for (String conditional : new String[] {"then", "else"}) {
        visit(schema.get(conditional), pointer, depth + 1);
      }
      JsonNode ref = schema.get("$ref");
      if (ref != null && ref.isString() && (ref.asString().equals("#") || ref.asString().startsWith("#/"))
          && !refs.contains(ref.asString())) {
        refs.push(ref.asString());
        visit(root.at(ref.asString().substring(1)), pointer, depth + 1);
        refs.pop();
      }
    }
  }
}
