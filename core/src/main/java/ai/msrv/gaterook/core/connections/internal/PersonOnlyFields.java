package ai.msrv.gaterook.core.connections.internal;

import ai.msrv.gaterook.connectors.support.PersonOnly;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The settings of a connection type that only the person makes: every property of its {@code configSchema} marked
 * {@value PersonOnly#KEYWORD}, wherever it sits (a conditional branch, an object, an array item, a local
 * {@code $ref}; {@link SchemaPointers} walks the schema). As JSON Pointer templates where {@code *} stands for an
 * array index: {@code /trustedExec}, {@code /hostKeys/pinned}, {@code /jump/hostKeys/pinned}. {@link #held} finds
 * them in a concrete config and {@link #covering} answers for one pointer, both as deep as the config nests: what an
 * endpoint share leaves out and an import drops.
 */
public final class PersonOnlyFields {

  private static final int MAX_NODES = 20_000;

  /** How far one value's definitions may unfold through branches and references. */
  private static final int MAX_UNFOLD_DEPTH = 32;

  /** How many definitions a walk of {@link #held} or {@link #covering} may look at in all, more for a larger config. */
  private static final int MAX_WORK = 1_000_000;

  private PersonOnlyFields() {}

  /**
   * One setting only the person makes that a concrete config holds.
   *
   * @param pointer  where it sits ({@code /replicas/0/pin}; empty when the whole config is marked)
   * @param template the same with {@code *} for each array index ({@code /replicas/*}{@code /pin}): the kind of
   *                 decision, however many array items hold one
   */
  public record Held(String pointer, String template) {}

  /**
   * The marked settings a concrete config holds, in the order it holds them, whatever their value ({@code null},
   * {@code false} and empty ones too); a marked value is not looked into, so a mark inside it adds nothing. The walk
   * follows the config and looks each value's definitions up where {@link #templates} finds them (properties, array
   * items, the branches, local {@code $ref}s, {@code "#"} included), so a schema that refers to a part containing the
   * reference is followed as deep as the config nests, where a template stops after one round. An object where the
   * definitions give array items is also looked into as a list, each member as an item ({@code *} in its template):
   * whoever iterates the value reads its members so, and a schema without {@code "type": "array"} lets it through.
   *
   * @throws IllegalArgumentException when the schema unfolds into more than the walk looks at, or the config is too
   *     large to walk along it
   */
  public static List<Held> held(JsonNode config, JsonNode configSchema) {
    if (config == null || configSchema == null) {
      return List.of();
    }
    List<Held> out = new ArrayList<>();
    new Find(configSchema).held(config, List.of(configSchema), "", "", out);
    return List.copyOf(out);
  }

  /**
   * Whether the value at a concrete pointer is a marked setting or lies inside one: where a credential of such a
   * setting would go ({@code /trust/clientKey} inside a marked {@code /trust}). A part of digits may name an array
   * item or a property; either counts.
   *
   * @return the marked setting's template ({@code *} for a part taken as an array index), or empty
   * @throws IllegalArgumentException when the schema unfolds into more than the walk looks at
   */
  public static Optional<String> covering(JsonNode configSchema, String pointer) {
    if (configSchema == null || pointer == null || !(pointer.isEmpty() || pointer.startsWith("/"))) {
      return Optional.empty();
    }
    return new Find(configSchema).covering(pointer);
  }

  /** Pointer templates of every marked property, sorted. */
  public static List<String> templates(JsonNode configSchema) {
    if (configSchema == null) {
      return List.of();
    }
    Set<String> out = new TreeSet<>();
    SchemaPointers.collect(configSchema, PersonOnly::isMarked).forEach(located -> out.add(located.template()));
    return List.copyOf(out);
  }

  /**
   * A copy of the schema without the marked properties (also from the {@code required} lists and from the
   * placeholders that name them), for whoever may write a config but not these: an AI agent's import proposal. The
   * connector's own schema, which the form and the validation read, is not changed.
   */
  public static JsonNode without(JsonNode configSchema) {
    if (configSchema == null) {
      return null;
    }
    JsonNode copy = configSchema.deepCopy();
    Set<String> hidden = new HashSet<>(templates(copy));
    if (!hidden.isEmpty()) {
      new Prune(copy, hidden).visit(copy, "", 0);
    }
    return copy;
  }

  private static final class Prune {
    private final JsonNode root;
    private final Set<String> hidden;
    private final Deque<String> refs = new ArrayDeque<>();
    private int nodes;

    Prune(JsonNode root, Set<String> hidden) {
      this.root = root;
      this.hidden = hidden;
    }

    void visit(JsonNode schema, String pointer, int depth) {
      if (!(schema instanceof ObjectNode object) || depth > 32 || ++nodes > MAX_NODES) {
        return;
      }
      if (object.get("properties") instanceof ObjectNode properties) {
        for (String key : List.copyOf(properties.propertyNames())) {
          String child = pointer + "/" + SecretFields.escape(key);
          if (hidden.contains(child)) {
            properties.remove(key);
            dropRequired(object, key);
          } else {
            visit(properties.get(key), child, depth + 1);
          }
        }
      }
      visit(object.get("items"), pointer + "/*", depth + 1);
      for (String combinator : new String[] {"oneOf", "anyOf", "allOf"}) {
        for (JsonNode branch : object.path(combinator)) {
          visit(branch, pointer, depth + 1);
        }
      }
      for (String conditional : new String[] {"then", "else"}) {
        visit(object.get(conditional), pointer, depth + 1);
      }
      JsonNode ref = object.get("$ref");
      if (ref != null && ref.isString() && ref.asString().startsWith("#/") && !refs.contains(ref.asString())) {
        refs.push(ref.asString());
        visit(root.at(ref.asString().substring(1)), pointer, depth + 1);
        refs.pop();
      }
    }

    private static void dropRequired(ObjectNode object, String key) {
      if (object.get("required") instanceof ArrayNode required) {
        for (int i = required.size() - 1; i >= 0; i--) {
          if (key.equals(required.get(i).asString(null))) {
            required.remove(i);
          }
        }
      }
    }
  }

  /**
   * The walk of {@link #held} and {@link #covering}: at each place of a config, the definitions that apply there are
   * the ones found for it and everything they unfold into, each unfolded once per walk.
   */
  private static final class Find {
    private final JsonNode root;
    private final Map<JsonNode, List<JsonNode>> unfolded = new IdentityHashMap<>();
    private int work;

    Find(JsonNode root) {
      this.root = root;
    }

    void held(JsonNode value, List<JsonNode> definitions, String pointer, String template, List<Held> out) {
      List<JsonNode> here = unfold(definitions);
      if (marked(here)) {
        out.add(new Held(pointer, template));
        return;
      }
      if (value.isObject()) {
        List<JsonNode> items = items(here);
        for (Map.Entry<String, JsonNode> property : value.properties()) {
          List<JsonNode> next = new ArrayList<>(property(here, property.getKey()));
          next.addAll(items);
          if (!next.isEmpty()) {
            String part = "/" + SecretFields.escape(property.getKey());
            held(property.getValue(), next, pointer + part, template + (items.isEmpty() ? part : "/*"), out);
          }
        }
      } else if (value.isArray()) {
        List<JsonNode> next = items(here);
        if (!next.isEmpty()) {
          for (int i = 0; i < value.size(); i++) {
            held(value.get(i), next, pointer + "/" + i, template + "/*", out);
          }
        }
      }
    }

    Optional<String> covering(String pointer) {
      String[] parts = pointer.isEmpty() ? new String[0] : pointer.substring(1).split("/", -1);
      List<JsonNode> definitions = List.of(root);
      StringBuilder template = new StringBuilder();
      for (int i = 0; ; i++) {
        List<JsonNode> here = unfold(definitions);
        if (marked(here)) {
          return Optional.of(template.toString());
        }
        if (i == parts.length) {
          return Optional.empty();
        }
        List<JsonNode> next = new ArrayList<>(property(here, SecretFields.unescape(parts[i])));
        List<JsonNode> items = parts[i].matches("\\d+") ? items(here) : List.of();
        next.addAll(items);
        if (next.isEmpty()) {
          return Optional.empty();
        }
        definitions = next;
        template.append('/').append(items.isEmpty() ? parts[i] : "*");
      }
    }

    private static boolean marked(List<JsonNode> definitions) {
      return definitions.stream().anyMatch(PersonOnly::isMarked);
    }

    private static List<JsonNode> property(List<JsonNode> definitions, String name) {
      List<JsonNode> out = new ArrayList<>();
      for (JsonNode definition : definitions) {
        JsonNode property = definition.path("properties").get(name);
        if (property != null && property.isObject()) {
          out.add(property);
        }
      }
      return out;
    }

    private static List<JsonNode> items(List<JsonNode> definitions) {
      List<JsonNode> out = new ArrayList<>();
      for (JsonNode definition : definitions) {
        if (definition.path("items").isObject()) {
          out.add(definition.get("items"));
        }
      }
      return out;
    }

    /** These definitions with their branches and the local references they name, each once. */
    private List<JsonNode> unfold(List<JsonNode> definitions) {
      List<JsonNode> out = new ArrayList<>();
      Set<JsonNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
      for (JsonNode definition : definitions) {
        for (JsonNode applies : unfolded.computeIfAbsent(definition, this::unfoldOne)) {
          if (seen.add(applies)) {
            out.add(applies);
          }
        }
      }
      work += out.size();
      if (work > MAX_WORK) {
        throw new IllegalArgumentException("Checking for settings only the person makes would look at more definitions "
            + "of the connector schema than one walk may.");
      }
      return out;
    }

    private List<JsonNode> unfoldOne(JsonNode definition) {
      List<JsonNode> out = new ArrayList<>();
      add(definition, out, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
      return out;
    }

    private void add(JsonNode schema, List<JsonNode> out, Set<JsonNode> seen, int depth) {
      if (schema == null || !schema.isObject() || !seen.add(schema)) {
        return;
      }
      if (depth > MAX_UNFOLD_DEPTH || out.size() >= MAX_NODES) {
        throw new IllegalArgumentException("The connector schema is too complex to check for settings only the person "
            + "makes.");
      }
      out.add(schema);
      for (String combinator : new String[] {"oneOf", "anyOf", "allOf"}) {
        for (JsonNode branch : schema.path(combinator)) {
          add(branch, out, seen, depth + 1);
        }
      }
      for (String conditional : new String[] {"then", "else"}) {
        add(schema.get(conditional), out, seen, depth + 1);
      }
      JsonNode ref = schema.get("$ref");
      if (ref != null && ref.isString() && (ref.asString().equals("#") || ref.asString().startsWith("#/"))) {
        add(root.at(ref.asString().substring(1)), out, seen, depth + 1);
      }
    }
  }
}
