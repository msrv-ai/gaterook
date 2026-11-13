package com.gaterook.connectors.support;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
 *
 * <p>{@link #templates} is the only walk here that finds the marks; {@link #covers} answers from its result. It walks
 * a schema the way the core's schema walk does when it checks an import proposal, and a test over every built-in
 * connector checks that both list the same settings.
 */
public final class PersonOnly {

  /** The schema keyword. */
  public static final String KEYWORD = "x-person-only";

  private static final int MAX_DEPTH = 32;

  /** Plugin manifests carry schemas too: a schema that unfolds into more than this is not walked further. */
  private static final int MAX_NODES = 20_000;

  private PersonOnly() {}

  /** Whether this property schema carries the mark. */
  public static boolean isMarked(JsonNode definition) {
    return definition != null && definition.isObject() && definition.path(KEYWORD).asBoolean(false);
  }

  /**
   * Where every marked property sits in a config, sorted: JSON Pointer templates in which {@code *} stands for an
   * array index ({@code /hostKeys/pinned}, {@code /jump/hostKeys/pinned}, {@code /replicas/*}{@code /pin}). The walk
   * goes through {@code properties}, {@code items}, the branches of {@code oneOf}/{@code anyOf}/{@code allOf},
   * {@code then}/{@code else} and local {@code $ref}s, {@code "#"} included; a {@code $ref} already being unfolded on
   * the current path is not followed again.
   */
  public static List<String> templates(JsonNode configSchema) {
    if (configSchema == null) {
      return List.of();
    }
    Set<String> out = new TreeSet<>();
    new Walk(configSchema, out).visit(configSchema, "", 0);
    return List.copyOf(out);
  }

  /**
   * Whether the property at {@code pointer} ({@code /hostKeys/pinned}, {@code /jump/hostKeys/pinned/0}; an array
   * index may be {@code *} or a number) is marked, or sits inside one that is: one of {@link #templates} is the pointer
   * or a parent of it, a {@code *} of the template standing for any index.
   */
  public static boolean covers(JsonNode configSchema, String pointer) {
    if (configSchema == null || pointer == null || !pointer.startsWith("/")) {
      return false;
    }
    String[] parts = pointer.substring(1).split("/", -1);
    for (String template : templates(configSchema)) {
      if (within(parts, template)) {
        return true;
      }
    }
    return false;
  }

  private static boolean within(String[] parts, String template) {
    String[] marked = template.isEmpty() ? new String[0] : template.substring(1).split("/", -1);
    if (marked.length > parts.length) {
      return false;
    }
    for (int i = 0; i < marked.length; i++) {
      String part = parts[i];
      boolean index = part.equals("*") || (!part.isEmpty() && part.chars().allMatch(c -> c >= '0' && c <= '9'));
      if (!marked[i].equals(part) && !(marked[i].equals("*") && index)) {
        return false;
      }
    }
    return true;
  }

  private static String escape(String name) {
    return name.replace("~", "~0").replace("/", "~1");
  }

  private static final class Walk {
    private final JsonNode root;
    private final Set<String> out;
    /** The {@code $ref}s being unfolded on the current path: a schema that refers to itself stops there. */
    private final Deque<String> refs = new ArrayDeque<>();
    private int nodes;

    Walk(JsonNode root, Set<String> out) {
      this.root = root;
      this.out = out;
    }

    void visit(JsonNode schema, String pointer, int depth) {
      if (schema == null || !schema.isObject() || depth > MAX_DEPTH || ++nodes > MAX_NODES) {
        return;
      }
      if (isMarked(schema)) {
        out.add(pointer);
      }
      for (Map.Entry<String, JsonNode> property : schema.path("properties").properties()) {
        visit(property.getValue(), pointer + "/" + escape(property.getKey()), depth + 1);
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
