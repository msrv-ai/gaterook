package ai.msrv.gaterook.connectors.mcpserver;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * Brings the schemas an MCP server declares to what the gateway publishes (report 11 §6.8, ARCHITECTURE.md §7.5):
 * JSON Schema 2020-12, an object at the root. TypeScript servers often send draft-07 with a {@code $schema} key, no
 * {@code type} or no {@code properties}; clients refuse such schemas and networknt, which validates the agent's
 * arguments, reads draft-07 tuple {@code items} as an error. Descriptions and titles inside are cleaned like the
 * tool's own ({@link #sanitize}). A schema that still isn't valid 2020-12, is over {@value #MAX_BYTES} bytes, has more
 * than {@value #MAX_SUBSCHEMAS} subschemas, names a parameter {@code connection} (the gateway's own) or hides
 * characters in a name or value a call carries is refused: its tool isn't published, with the reason shown in the
 * review.
 */
final class ToolSchemas {

  static final int MAX_BYTES = 64 * 1024;
  /** As the core counts them when it validates a call (platform.SchemaValidator). */
  static final int MAX_SUBSCHEMAS = 2_000;

  private static final Set<String> ONE = Set.of("not", "if", "then", "else", "items", "additionalItems", "contains",
      "additionalProperties", "propertyNames", "unevaluatedItems", "unevaluatedProperties", "contentSchema");
  private static final Set<String> MANY = Set.of("allOf", "anyOf", "oneOf", "prefixItems", "items");
  private static final Set<String> NAMED = Set.of("properties", "patternProperties", "$defs", "definitions",
      "dependentSchemas", "dependencies");
  /** Keywords an agent reads as prose: cleaned of what hides words ({@link Texts#clean}) and cut. */
  private static final Set<String> PROSE = Set.of("description", "title", "$comment");
  /** Keywords whose strings are values or names a call carries: invisible characters there refuse the tool. */
  private static final Set<String> VALUES = Set.of("enum", "const", "default", "required", "dependentRequired");
  static final int MAX_TEXT = 1_000;

  private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
  private static volatile Schema metaSchema;

  /** A normalized schema, or why the tool can't be published. */
  record Result(ObjectNode schema, String problem) {
    boolean ok() {
      return problem == null;
    }
  }

  private ToolSchemas() {}

  /** The input schema of a tool as published, or why not. */
  static Result input(Map<String, Object> declared) {
    if (declared == null) {
      return normalize(Texts.JSON.createObjectNode(), true);
    }
    return normalize(Texts.JSON.valueToTree(declared), true);
  }

  /** The output schema of a tool, or why it is dropped ({@code null} declared: no schema, no problem). */
  static Result output(Map<String, Object> declared) {
    if (declared == null) {
      return new Result(null, null);
    }
    return normalize(Texts.JSON.valueToTree(declared), false);
  }

  static Result normalize(JsonNode declared, boolean input) {
    String what = input ? "inputSchema" : "outputSchema";
    if (declared == null || !declared.isObject()) {
      return new Result(null, what + " is not a JSON object");
    }
    if (Texts.JSON.writeValueAsString(declared).getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      return new Result(null, what + " is over 64 KB");
    }
    ObjectNode schema = (ObjectNode) declared.deepCopy();
    upgrade(schema, true);
    JsonNode type = schema.get("type");
    if (type == null) {
      schema.put("type", "object");
    } else if (!(type.isString() && type.asString().equals("object"))) {
      return new Result(null, what + " doesn't describe an object (type " + type + ")");
    }
    if (!schema.path("properties").isObject()) {
      schema.putObject("properties");
    }
    if (input && schema.path("properties").has("connection")) {
      return new Result(null, "it has a parameter named connection, which MCP Gateway uses for itself");
    }
    String invisible = sanitize(schema, 0);
    if (invisible != null) {
      return new Result(null, what + " has invisible characters in " + invisible);
    }
    if (subschemas(schema, MAX_SUBSCHEMAS + 1) > MAX_SUBSCHEMAS) {
      return new Result(null, what + " has more than " + MAX_SUBSCHEMAS + " subschemas");
    }
    try {
      List<Error> errors = meta().validate(schema);
      if (!errors.isEmpty()) {
        return new Result(null, what + " isn't valid JSON Schema 2020-12: " + errors.getFirst().getMessage());
      }
      Schema compiled = REGISTRY.getSchema(SchemaLocation.of("urn:mcp-gateway:mcp-tool:" + Texts.sha256(schema)),
          schema);
      compiled.initializeValidators();
    } catch (RuntimeException e) {
      return new Result(null, what + " can't be compiled: " + Texts.clean(e.getMessage(), 200, false));
    }
    return new Result(schema, null);
  }

  /**
   * Draft-07 and draft-04 constructs in their 2020-12 form, in place: no {@code $schema} or draft-04 {@code id},
   * {@code definitions} as {@code $defs} (and references into them), tuple {@code items} as {@code prefixItems} with
   * {@code additionalItems} as {@code items}, {@code dependencies} split into {@code dependentRequired} and
   * {@code dependentSchemas}.
   */
  static void upgrade(JsonNode node, boolean root) {
    if (!(node instanceof ObjectNode o)) {
      return;
    }
    o.remove("$schema");
    if (o.path("id").isString() && !o.has("$id") && looksLikeSchema(o)) {
      o.remove("id");
    }
    if (o.path("definitions").isObject() && !o.has("$defs")) {
      o.set("$defs", o.remove("definitions"));
    }
    JsonNode ref = o.get("$ref");
    if (ref != null && ref.isString() && ref.asString().startsWith("#/definitions/")) {
      o.set("$ref", StringNode.valueOf("#/$defs/" + ref.asString().substring("#/definitions/".length())));
    }
    if (o.path("items").isArray()) {
      JsonNode tuple = o.remove("items");
      o.set("prefixItems", tuple);
      JsonNode additional = o.remove("additionalItems");
      if (additional != null) {
        o.set("items", additional);
      }
    } else if (o.has("additionalItems") && !o.has("prefixItems")) {
      // Without tuple items draft-07 ignores additionalItems; 2020-12 doesn't know it.
      o.remove("additionalItems");
    }
    if (o.path("dependencies").isObject()) {
      JsonNode dependencies = o.remove("dependencies");
      ObjectNode required = Texts.JSON.createObjectNode();
      ObjectNode schemas = Texts.JSON.createObjectNode();
      for (Map.Entry<String, JsonNode> e : dependencies.properties()) {
        (e.getValue().isArray() ? required : schemas).set(e.getKey(), e.getValue());
      }
      if (!required.isEmpty()) {
        o.set("dependentRequired", required);
      }
      if (!schemas.isEmpty()) {
        o.set("dependentSchemas", schemas);
      }
    }
    // Only into the keywords whose values are schemas: a property named "$schema" or "definitions", an enum value
    // or a default stays as the server wrote it.
    for (Map.Entry<String, JsonNode> e : o.properties()) {
      JsonNode v = e.getValue();
      if (ONE.contains(e.getKey())) {
        upgrade(v, false);
      } else if (MANY.contains(e.getKey()) && v.isArray()) {
        v.forEach(n -> upgrade(n, false));
      } else if (NAMED.contains(e.getKey()) && v.isObject()) {
        v.forEach(n -> upgrade(n, false));
      }
    }
  }

  /**
   * Cleans in place what an agent reads as prose in a schema — descriptions, titles, comments and the strings of
   * {@code examples} — the way the tool's own description is cleaned (report 11 §3.1: a server's text is untrusted,
   * and hidden words in a parameter's description reach the agent just the same). Names and values a call carries
   * (property names, {@code enum}, {@code const}, {@code default}, {@code required}) can't be cleaned without changing
   * the call: one with invisible characters is named, and the tool isn't published.
   *
   * @return where the invisible characters are ({@code enum}, {@code a property name}), or {@code null}
   */
  static String sanitize(JsonNode node, int depth) {
    if (!(node instanceof ObjectNode o) || depth > 200) {
      return null;
    }
    for (String key : List.copyOf(o.propertyNames())) {
      JsonNode v = o.get(key);
      String problem = null;
      if (PROSE.contains(key) && v.isString()) {
        boolean title = key.equals("title");
        o.put(key, Texts.clean(v.asString(), title ? Snapshots.MAX_TITLE : MAX_TEXT, !title));
      } else if (key.equals("examples")) {
        o.set(key, cleanStrings(v, depth + 1));
      } else if (VALUES.contains(key)) {
        problem = invisible(v, depth + 1) ? key : null;
      } else if (ONE.contains(key)) {
        problem = sanitize(v, depth + 1);
      } else if (MANY.contains(key) && v.isArray()) {
        for (JsonNode n : v) {
          problem = problem != null ? problem : sanitize(n, depth + 1);
        }
      } else if (NAMED.contains(key) && v.isObject()) {
        for (Map.Entry<String, JsonNode> e : v.properties()) {
          if (problem == null && Texts.invisible(e.getKey())) {
            problem = key.equals("properties") ? "a property name" : "a name under " + key;
          }
          problem = problem != null ? problem : sanitize(e.getValue(), depth + 1);
        }
      }
      if (problem != null) {
        return problem;
      }
    }
    return null;
  }

  private static JsonNode cleanStrings(JsonNode v, int depth) {
    if (v.isString()) {
      return StringNode.valueOf(Texts.clean(v.asString(), MAX_TEXT, true));
    }
    if (depth > 200) {
      return v;
    }
    if (v.isArray()) {
      ArrayNode out = Texts.JSON.createArrayNode();
      v.forEach(n -> out.add(cleanStrings(n, depth + 1)));
      return out;
    }
    if (v.isObject()) {
      ObjectNode out = Texts.JSON.createObjectNode();
      for (Map.Entry<String, JsonNode> e : v.properties()) {
        out.set(Texts.clean(e.getKey(), MAX_TEXT, false), cleanStrings(e.getValue(), depth + 1));
      }
      return out;
    }
    return v;
  }

  private static boolean invisible(JsonNode v, int depth) {
    if (v.isString()) {
      return Texts.invisible(v.asString());
    }
    if (depth > 200) {
      return false;
    }
    for (Map.Entry<String, JsonNode> e : v.properties()) {
      if (Texts.invisible(e.getKey()) || invisible(e.getValue(), depth + 1)) {
        return true;
      }
    }
    for (JsonNode n : v.isArray() ? v : Texts.JSON.createArrayNode()) {
      if (invisible(n, depth + 1)) {
        return true;
      }
    }
    return false;
  }

  private static boolean looksLikeSchema(ObjectNode o) {
    return o.has("type") || o.has("properties") || o.has("$ref");
  }

  /** The schema and every schema under a schema keyword, counted up to {@code cap}. */
  static int subschemas(JsonNode schema, int cap) {
    int count = 0;
    Deque<JsonNode> todo = new ArrayDeque<>();
    todo.push(schema);
    while (!todo.isEmpty() && count < cap) {
      JsonNode s = todo.pop();
      count++;
      if (!s.isObject()) {
        continue;
      }
      for (Map.Entry<String, JsonNode> e : s.properties()) {
        JsonNode v = e.getValue();
        if (ONE.contains(e.getKey()) && (v.isObject() || v.isBoolean())) {
          todo.push(v);
        } else if (MANY.contains(e.getKey()) && v.isArray()) {
          v.forEach(todo::push);
        } else if (NAMED.contains(e.getKey()) && v.isObject()) {
          for (JsonNode sub : v) {
            if (sub.isObject() || sub.isBoolean()) {
              todo.push(sub);
            }
          }
        }
      }
    }
    return count;
  }

  private static Schema meta() {
    Schema m = metaSchema;
    if (m == null) {
      m = REGISTRY.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));
      m.initializeValidators();
      metaSchema = m;
    }
    return m;
  }
}
