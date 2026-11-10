package ai.msrv.gaterook.core.connections.internal;

import ai.msrv.gaterook.connectors.support.PersonOnly;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The settings of a connection type that only the person makes: every property of its {@code configSchema} marked
 * {@value PersonOnly#KEYWORD}, wherever it sits (a conditional branch, an object, an array item, a local
 * {@code $ref}; {@link SchemaPointers} walks the schema). As JSON Pointer templates where {@code *} stands for an
 * array index: {@code /trustedExec}, {@code /hostKeys/pinned}, {@code /jump/hostKeys/pinned}.
 */
public final class PersonOnlyFields {

  private static final int MAX_NODES = 20_000;

  private PersonOnlyFields() {}

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
}
