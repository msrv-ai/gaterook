package ai.msrv.gaterook.core.policy.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * "Only stricter wins" (ARCHITECTURE.md §5 step 7, report 14 §4.3): how a connection, an endpoint or a tool override
 * may narrow a value of the environment's policy, by the key's {@code x-stricter}:
 * <ul>
 *   <li>{@code min} / {@code max}: the smaller / larger number (timeouts and row limits shrink);</li>
 *   <li>{@code intersect}: what both lists allow; an empty list means "no restriction" (allowlists);</li>
 *   <li>{@code union}: everything either list names (denylists, masked fields);</li>
 *   <li>{@code and}: true only if both are (a permission such as {@code allowExec});</li>
 *   <li>{@code or}: true if either is (a protection such as {@code denyDdl}).</li>
 * </ul>
 * A key without {@code x-stricter} can't be narrowed: the override is ignored and the policy's value stays.
 */
public final class StricterMerge {

  private StricterMerge() {}

  /** The value that results from narrowing {@code current} with {@code candidate}. */
  public static JsonNode merge(String op, JsonNode current, JsonNode candidate) {
    if (candidate == null || candidate.isNull() || candidate.isMissingNode()) {
      return current;
    }
    if (current == null || current.isNull() || current.isMissingNode()) {
      // Nothing to narrow: only an op that can only add restrictions may introduce a value.
      return switch (op == null ? "" : op) {
        case "min", "max", "intersect", "union", "or" -> candidate;
        default -> current;
      };
    }
    return switch (op == null ? "" : op) {
      case "min" -> numbers(current, candidate) ? (candidate.decimalValue().compareTo(current.decimalValue()) < 0
          ? candidate : current) : current;
      case "max" -> numbers(current, candidate) ? (candidate.decimalValue().compareTo(current.decimalValue()) > 0
          ? candidate : current) : current;
      case "and" -> booleans(current, candidate)
          ? JsonNodeFactory.instance.booleanNode(current.asBoolean() && candidate.asBoolean()) : current;
      case "or" -> booleans(current, candidate)
          ? JsonNodeFactory.instance.booleanNode(current.asBoolean() || candidate.asBoolean()) : current;
      case "intersect" -> arrays(current, candidate) ? intersect(current, candidate) : current;
      case "union" -> arrays(current, candidate) ? union(current, candidate) : current;
      default -> current;
    };
  }

  /** Whether {@code candidate} is at least as strict as {@code current} under {@code op}. */
  public static boolean atLeastAsStrict(String op, JsonNode current, JsonNode candidate) {
    JsonNode merged = merge(op, current, candidate);
    return merged != null && merged.equals(candidate);
  }

  private static JsonNode intersect(JsonNode current, JsonNode candidate) {
    if (current.isEmpty()) {
      return candidate;
    }
    if (candidate.isEmpty()) {
      return current;
    }
    Set<JsonNode> allowed = new LinkedHashSet<>();
    candidate.forEach(allowed::add);
    ArrayNode out = JsonNodeFactory.instance.arrayNode();
    for (JsonNode v : current) {
      if (allowed.contains(v)) {
        out.add(v);
      }
    }
    // Both restricted and nothing in common: nothing is allowed. An empty list would read as "everything", so the
    // result keeps a value no name can match.
    if (out.isEmpty()) {
      out.add("\u0000none");
    }
    return out;
  }

  private static JsonNode union(JsonNode current, JsonNode candidate) {
    List<JsonNode> all = new ArrayList<>();
    Set<JsonNode> seen = new LinkedHashSet<>();
    for (JsonNode v : current) {
      if (seen.add(v)) {
        all.add(v);
      }
    }
    for (JsonNode v : candidate) {
      if (seen.add(v)) {
        all.add(v);
      }
    }
    ArrayNode out = JsonNodeFactory.instance.arrayNode();
    all.forEach(out::add);
    return out;
  }

  private static boolean numbers(JsonNode a, JsonNode b) {
    return a.isNumber() && b.isNumber();
  }

  private static boolean booleans(JsonNode a, JsonNode b) {
    return a.isBoolean() && b.isBoolean();
  }

  private static boolean arrays(JsonNode a, JsonNode b) {
    return a.isArray() && b.isArray();
  }
}
