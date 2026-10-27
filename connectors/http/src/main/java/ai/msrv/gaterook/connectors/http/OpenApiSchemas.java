package ai.msrv.gaterook.connectors.http;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * OpenAPI schemas to JSON Schema 2020-12 for an {@code inputSchema} (report 10 §1, §7.4): local {@code $ref}s are
 * inlined (a reference already on the path, or nesting deeper than 24, becomes "Recursive Node"); OpenAPI 3.0's
 * {@code nullable} becomes a {@code null} type, boolean {@code exclusiveMinimum}/{@code exclusiveMaximum} become
 * numbers; {@code readOnly} properties are no input and leave {@code required}; vendor extensions, examples, XML,
 * discriminators and external docs are dropped; descriptions are cleaned and cut.
 *
 * <p>Inlining is work a file decides: a large {@code enum} that a thousand properties refer to is gigabytes once
 * copied and written. So every node written and every character copied counts ({@link #overBudget}): past
 * {@link #MAX_WORK} for one operation its input would be over 32 KB anyway, so nothing more is copied and the operation
 * is skipped; the import as a whole stops at {@link #MAX_TOTAL_WORK}.
 */
final class OpenApiSchemas {

  static final int MAX_DEPTH = 24;
  /**
   * Work for one operation's input: nodes written plus characters of names and strings. A schema serializes to at
   * least that many bytes, so past twice the 32 KB limit the operation can't be published either way.
   */
  static final long MAX_WORK = 2L * OpenApiImport.MAX_INPUT_SCHEMA_BYTES;
  /**
   * Work for a whole file: twenty times what Stripe's description takes (0.9 million; GitHub's 0.45 million, report 10
   * §8.5), and what it keeps in memory meanwhile stays in the hundreds of megabytes.
   */
  static final long MAX_TOTAL_WORK = 20_000_000L;

  private static final Set<String> DROPPED = Set.of("xml", "externalDocs", "discriminator", "example", "examples",
      "$schema", "$id", "$anchor", "$dynamicAnchor", "$comment", "nullable", "$defs", "definitions");
  private static final Set<String> SCHEMA_ARRAYS = Set.of("allOf", "anyOf", "oneOf", "prefixItems");
  private static final Set<String> SCHEMA_VALUES = Set.of("items", "not", "additionalProperties", "contains",
      "propertyNames", "if", "then", "else", "unevaluatedProperties", "unevaluatedItems", "contentSchema");
  private static final Set<String> SCHEMA_MAPS = Set.of("patternProperties", "dependentSchemas");

  private final JsonNode root;
  private final boolean v30;
  /** Object nodes written, so a schema that would blow up the validator's budget is skipped, not published. */
  private int nodes;
  /** Work of the current operation (nodes and characters, see the type). */
  private long work;
  /** Work of the operations before it. */
  private long done;

  OpenApiSchemas(JsonNode root) {
    this.root = root;
    this.v30 = root.path("openapi").asString("").startsWith("3.0");
  }

  int nodes() {
    return nodes;
  }

  /** Whether the current operation's input went past {@link #MAX_WORK}: it is cut short and can't be published. */
  boolean overBudget() {
    return work > MAX_WORK;
  }

  /** Work of the whole import so far. */
  long totalWork() {
    return done + work;
  }

  /** Counts work an operation causes besides its schemas (its path, its parameters). */
  void spend(long amount) {
    work += amount;
  }

  /** The next operation starts. */
  void resetCount() {
    done += work;
    nodes = 0;
    work = 0;
  }

  /** The converted schema of {@code schema}; an absent schema accepts anything. */
  JsonNode convert(JsonNode schema) {
    return convert(schema, new ArrayDeque<>(), 0);
  }

  private JsonNode convert(JsonNode s, Deque<String> stack, int depth) {
    if (s == null || s.isMissingNode() || s.isNull()) {
      return JsonNodeFactory.instance.objectNode();
    }
    if (nodes > OpenApiImport.MAX_SCHEMA_NODES || work > MAX_WORK) {
      // Over budget: the operation is skipped anyway; stop inlining before shared references multiply the work.
      return JsonNodeFactory.instance.objectNode();
    }
    if (s.isBoolean()) {
      return s;
    }
    if (!s.isObject()) {
      return JsonNodeFactory.instance.objectNode();
    }
    nodes++;
    work++;
    if (s.path("$ref").isString()) {
      String ref = s.get("$ref").asString();
      if (stack.contains(ref) || depth > MAX_DEPTH) {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("type", "object");
        o.put("description", "Recursive " + ref.substring(ref.lastIndexOf('/') + 1));
        return o;
      }
      JsonNode target = ref.startsWith("#/") ? root.at(ref.substring(1)) : null;
      if (target == null || target.isMissingNode()) {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("description", "Unresolved " + ToolText.clean(ref, 120));
        return o;
      }
      stack.push(ref);
      JsonNode inlined = convert(target, stack, depth + 1);
      stack.pop();
      if (s.size() == 1 || !inlined.isObject()) {
        return inlined;
      }
      // 3.1 allows keywords next to $ref: they narrow the referenced schema.
      ObjectNode merged = ((ObjectNode) inlined).deepCopy();
      for (Map.Entry<String, JsonNode> e : s.properties()) {
        if (!e.getKey().equals("$ref") && (e.getKey().equals("description") || e.getKey().equals("title"))) {
          merged.put(e.getKey(), text(e.getValue(), e.getKey().equals("title") ? ToolText.TITLE
              : ToolText.PROPERTY_DESCRIPTION));
        }
      }
      return merged;
    }
    ObjectNode o = JsonNodeFactory.instance.objectNode();
    for (Map.Entry<String, JsonNode> e : s.properties()) {
      String k = e.getKey();
      JsonNode v = e.getValue();
      if (k.startsWith("x-") || DROPPED.contains(k)) {
        continue;
      }
      switch (k) {
        case "properties" -> {
          if (!v.isObject()) {
            continue;
          }
          ObjectNode props = o.putObject("properties");
          for (Map.Entry<String, JsonNode> p : v.properties()) {
            if (work > MAX_WORK) {
              break;
            }
            work += p.getKey().length();
            JsonNode target = OpenApiDocument.deref(root, p.getValue());
            if (target != null && target.path("readOnly").asBoolean(false)) {
              continue; // the server writes it: not an input
            }
            props.set(p.getKey(), convert(p.getValue(), stack, depth + 1));
          }
        }
        case "required" -> {
          // Written after the properties, so read-only properties can leave it.
        }
        case "readOnly" -> {
          // Read-only properties are left out above; a read-only schema as a whole says nothing about input.
        }
        case "exclusiveMinimum", "exclusiveMaximum" -> {
          work++;
          if (v.isBoolean()) {
            String bound = k.equals("exclusiveMinimum") ? "minimum" : "maximum";
            if (v.asBoolean() && s.path(bound).isNumber()) {
              o.set(k, s.get(bound));
            }
          } else if (v.isNumber()) {
            o.set(k, v);
          }
        }
        case "minimum", "maximum" -> {
          work++;
          String exclusive = k.equals("minimum") ? "exclusiveMinimum" : "exclusiveMaximum";
          if (!(s.path(exclusive).isBoolean() && s.path(exclusive).asBoolean())) {
            o.set(k, v);
          }
        }
        case "description" -> o.put(k, text(v, ToolText.PROPERTY_DESCRIPTION));
        case "title" -> o.put(k, text(v, ToolText.TITLE));
        default -> {
          if (SCHEMA_ARRAYS.contains(k) && v.isArray()) {
            ArrayNode a = o.putArray(k);
            for (JsonNode c : v) {
              a.add(convert(c, stack, depth + 1));
            }
          } else if (SCHEMA_VALUES.contains(k) && (v.isObject() || v.isBoolean())) {
            o.set(k, convert(v, stack, depth + 1));
          } else if (SCHEMA_MAPS.contains(k) && v.isObject()) {
            ObjectNode m = o.putObject(k);
            for (Map.Entry<String, JsonNode> p : v.properties()) {
              if (work > MAX_WORK) {
                break;
              }
              work += p.getKey().length();
              m.set(p.getKey(), convert(p.getValue(), stack, depth + 1));
            }
          } else {
            o.set(k, copy(v));
          }
        }
      }
    }
    if (s.path("required").isArray()) {
      ArrayNode required = JsonNodeFactory.instance.arrayNode();
      JsonNode props = o.path("properties");
      for (JsonNode r : s.get("required")) {
        if (work > MAX_WORK) {
          break;
        }
        String name = r.asString("");
        work += name.length() + 1;
        // A required property that is read-only (left out above) can't be demanded from the agent.
        boolean declared = s.path("properties").has(name);
        if (!declared || props.has(name)) {
          required.add(name);
        }
      }
      if (!required.isEmpty()) {
        o.set("required", required);
      }
    }
    if (v30 && s.path("nullable").asBoolean(false)) {
      JsonNode type = o.get("type");
      if (type != null && type.isString()) {
        ArrayNode t = JsonNodeFactory.instance.arrayNode();
        t.add(type.asString());
        t.add("null");
        o.set("type", t);
      }
      if (o.path("enum").isArray()) {
        boolean hasNull = false;
        for (JsonNode n : o.get("enum")) {
          hasNull |= n.isNull();
        }
        if (!hasNull) {
          ((ArrayNode) o.get("enum")).addNull();
        }
      }
    }
    return o;
  }

  /** A description or title, cleaned and cut, counted as work. */
  private String text(JsonNode value, int max) {
    String text = ToolText.clean(value.asString(""), max);
    work += text.length();
    return text;
  }

  /**
   * A value that isn't a schema ({@code enum}, {@code const}, {@code default}…), counted node by node and character by
   * character; past the budget the rest is left out (the operation is skipped). Value nodes are immutable and shared.
   */
  private JsonNode copy(JsonNode v) {
    work++;
    if (v.isString()) {
      work += v.asString().length();
      return v;
    }
    if (v.isArray()) {
      ArrayNode out = JsonNodeFactory.instance.arrayNode();
      for (JsonNode item : v) {
        if (work > MAX_WORK) {
          break;
        }
        out.add(copy(item));
      }
      return out;
    }
    if (v.isObject()) {
      ObjectNode out = JsonNodeFactory.instance.objectNode();
      for (Map.Entry<String, JsonNode> e : v.properties()) {
        if (work > MAX_WORK) {
          break;
        }
        work += e.getKey().length();
        out.set(e.getKey(), copy(e.getValue()));
      }
      return out;
    }
    return v;
  }
}
