package com.gaterook.core.policy.internal;

import com.gaterook.connector.api.PolicyView;
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
 *   <li>{@code min-positive}: the smaller positive number, with zero meaning unrestricted;</li>
 *   <li>{@code intersect}: what both lists allow, read by what an empty list allows ({@link Empty});</li>
 *   <li>{@code union}: everything either list names (denylists, masked fields);</li>
 *   <li>{@code and}: true only if both are (a permission such as {@code allowExec});</li>
 *   <li>{@code or}: true if either is (a protection such as {@code denyDdl}).</li>
 * </ul>
 * A key without {@code x-stricter} can't be narrowed: the override is ignored and the policy's value stays.
 *
 * <p>An {@code intersect} never allows more than either list does. Its result is read the way the connector reads the
 * key, so the merge follows the key's {@code x-empty}: an empty list may mean every name, no name, or the connector's
 * own default set. Two lists with nothing in common keep {@link PolicyView#NOTHING}, which no name matches.
 */
public final class StricterMerge {

  /**
   * What an empty list of an {@code intersect} key allows, from the key's {@code x-empty} (connector-spi.md).
   * The merge needs it: narrowing "no name" by a list must not give that list, and narrowing a connector's default
   * set by a list must keep the default set's bounds.
   */
  public enum Empty {
    /** {@code "all"}, the default: an empty list restricts nothing (a table allowlist). */
    ALL,
    /** {@code "none"}: an empty list allows nothing (allowed base URLs of HTTP egress). */
    NONE,
    /**
     * {@code "connector-default"}: an empty list allows what the connector allows by default, which is less than
     * everything (every Kubernetes resource but Secrets; every cloud service but sensitive ones). A list narrowed from
     * an empty one carries {@link PolicyView#WITHIN_DEFAULT}: the connector keeps the listed names its empty reading
     * allows too.
     */
    CONNECTOR_DEFAULT;

    /** The meaning a schema names; {@link #ALL} when it names none or one this version doesn't know. */
    public static Empty of(JsonNode xEmpty) {
      if (xEmpty == null || !xEmpty.isString()) {
        return ALL;
      }
      return switch (xEmpty.asString()) {
        case "none" -> NONE;
        case "connector-default" -> CONNECTOR_DEFAULT;
        default -> ALL;
      };
    }
  }

  private StricterMerge() {}

  /** The value that results from narrowing {@code current} with {@code candidate}, empty lists meaning "all". */
  public static JsonNode merge(String op, JsonNode current, JsonNode candidate) {
    return merge(op, Empty.ALL, current, candidate);
  }

  /**
   * The value that results from narrowing {@code current} with {@code candidate}.
   *
   * @param empty what an empty list of the key allows; only {@code intersect} reads it
   */
  public static JsonNode merge(String op, Empty empty, JsonNode current, JsonNode candidate) {
    if (candidate == null || candidate.isNull() || candidate.isMissingNode()) {
      return current;
    }
    if ("intersect".equals(op) && empty != Empty.ALL && absent(current) && candidate.isArray()) {
      // A key the section doesn't hold reads as an empty list, which allows no name or only the connector's own
      // default set: the candidate can narrow that, never replace it.
      return intersect(empty, JsonNodeFactory.instance.arrayNode(), candidate);
    }
    if (absent(current)) {
      // Nothing to narrow: only an op that can only add restrictions may introduce a value.
      return switch (op == null ? "" : op) {
        case "min", "max", "intersect", "union", "or" -> candidate;
        case "min-positive" -> candidate.isNumber() && candidate.decimalValue().signum() >= 0 ? candidate : current;
        default -> current;
      };
    }
    return switch (op == null ? "" : op) {
      case "min" -> numbers(current, candidate) ? (candidate.decimalValue().compareTo(current.decimalValue()) < 0
          ? candidate : current) : current;
      case "max" -> numbers(current, candidate) ? (candidate.decimalValue().compareTo(current.decimalValue()) > 0
          ? candidate : current) : current;
      case "min-positive" -> minPositive(current, candidate);
      case "and" -> booleans(current, candidate)
          ? JsonNodeFactory.instance.booleanNode(current.asBoolean() && candidate.asBoolean()) : current;
      case "or" -> booleans(current, candidate)
          ? JsonNodeFactory.instance.booleanNode(current.asBoolean() || candidate.asBoolean()) : current;
      case "intersect" -> arrays(current, candidate) ? intersect(empty, current, candidate) : current;
      case "union" -> arrays(current, candidate) ? union(current, candidate) : current;
      default -> current;
    };
  }

  /** Whether {@code candidate} is at least as strict as {@code current} under {@code op}. */
  public static boolean atLeastAsStrict(String op, JsonNode current, JsonNode candidate) {
    JsonNode merged = merge(op, current, candidate);
    return merged != null && merged.equals(candidate);
  }

  private static boolean absent(JsonNode n) {
    return n == null || n.isNull() || n.isMissingNode();
  }

  private static JsonNode minPositive(JsonNode current, JsonNode candidate) {
    if (!numbers(current, candidate) || candidate.decimalValue().signum() <= 0) {
      return current;
    }
    return current.decimalValue().signum() == 0 || candidate.decimalValue().compareTo(current.decimalValue()) < 0
        ? candidate : current;
  }

  /**
   * What both lists allow. A list without names stands for what an empty list allows: every name ({@link Empty#ALL},
   * so the other list decides), no name ({@link Empty#NONE}, so nothing is allowed) or the connector's default set
   * ({@link Empty#CONNECTOR_DEFAULT}, so the other list's names stay, marked as bounded by that set).
   */
  private static JsonNode intersect(Empty empty, JsonNode current, JsonNode candidate) {
    boolean bounded = empty == Empty.CONNECTOR_DEFAULT;
    List<JsonNode> currentNames = names(current, bounded);
    List<JsonNode> candidateNames = names(candidate, bounded);
    if (empty == Empty.NONE && (currentNames.isEmpty() || candidateNames.isEmpty())) {
      // An empty list allows nothing here, and nothing narrowed by anything is still nothing.
      return currentNames.isEmpty() ? current : JsonNodeFactory.instance.arrayNode();
    }
    if (empty == Empty.ALL) {
      if (currentNames.isEmpty()) {
        return candidate;
      }
      if (candidateNames.isEmpty()) {
        return current;
      }
    }
    boolean withinDefault = bounded && (currentNames.isEmpty() || candidateNames.isEmpty()
        || withinDefault(current) || withinDefault(candidate));
    List<JsonNode> kept;
    if (currentNames.isEmpty()) {
      kept = candidateNames;
    } else if (candidateNames.isEmpty()) {
      kept = currentNames;
    } else {
      Set<JsonNode> allowed = new LinkedHashSet<>(candidateNames);
      kept = currentNames.stream().filter(allowed::contains).toList();
      if (kept.isEmpty()) {
        // Both restricted and nothing in common: nothing is allowed. An empty list would read as "everything" or as
        // the connector's default set, so the result keeps a value no name can match.
        ArrayNode nothing = JsonNodeFactory.instance.arrayNode();
        nothing.add(PolicyView.NOTHING);
        return nothing;
      }
    }
    ArrayNode out = JsonNodeFactory.instance.arrayNode();
    kept.forEach(out::add);
    if (withinDefault && !kept.isEmpty()) {
      out.add(PolicyView.WITHIN_DEFAULT);
    }
    return out;
  }

  /** The values of a list; for a key with a connector default, without the marker that bounds them by it. */
  private static List<JsonNode> names(JsonNode list, boolean bounded) {
    List<JsonNode> out = new ArrayList<>();
    for (JsonNode v : list) {
      if (!(bounded && isWithinDefault(v)) && !out.contains(v)) {
        out.add(v);
      }
    }
    return out;
  }

  private static boolean withinDefault(JsonNode list) {
    for (JsonNode v : list) {
      if (isWithinDefault(v)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isWithinDefault(JsonNode v) {
    return v.isString() && PolicyView.WITHIN_DEFAULT.equals(v.asString());
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
