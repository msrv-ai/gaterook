package ai.msrv.gaterook.connectors.prometheus;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connectors.support.JsonNumbers;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Prometheus' answers read as a stream ({@code {"status", "data", "errorType", "error", "warnings", "infos"}}): a tree
 * with bounds, so a server's answer costs memory in proportion to what can be shown, never to what it sent. Lists keep
 * their first items and count the rest, strings are cut, and a body cut at the connection's size limit ends the tree
 * where it stops. Numbers stay exact ({@link BigDecimal}); times become ISO 8601 in UTC.
 */
final class PromJson {

  static final JsonMapper JSON = JsonMapper.builder().build();
  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  /** The longest string kept of a read; what is shown is cut shorter still. */
  static final int MAX_STRING = 32_768;
  /** The most values one answer's tree holds. */
  static final long MAX_NODES = 250_000;
  /** Items of a container other than the answer's lists (labels of a series, rules of a group). */
  static final int INNER_ITEMS = 1_000;
  /** Seconds since 1970 beyond which a time isn't one: shown as written. */
  private static final BigDecimal MAX_SECONDS = BigDecimal.valueOf(253_402_300_799L);

  private PromJson() {}

  /**
   * What a read keeps.
   *
   * @param listDepth the depth of the answer's lists ({@code data} is 1, {@code data.activeTargets} 2): they keep
   *                  {@code listItems} items, every other container {@link #INNER_ITEMS}
   */
  record Bounds(int listDepth, int listItems) {}

  /**
   * A bounded tree of one answer.
   *
   * @param cut     whether the body ended before {@code data} did: the server's answer was longer than the
   *                connection's size limit, and what it held is cut (a body cut later lost warnings at most)
   * @param dropped whether the bounds left items out of a container
   * @param totals  the number of items of each container that left some out, by identity
   */
  record Tree(ObjectNode root, boolean cut, boolean dropped, Map<JsonNode, Long> totals) {

    String status() {
      return root.path("status").asString("");
    }

    JsonNode data() {
      return root.path("data");
    }

    /** The items a container had: what it kept, or more when the bounds left some out. */
    long total(JsonNode container) {
      Long t = totals.get(container);
      return t != null ? t : container == null ? 0 : container.size();
    }

    List<String> warnings() {
      return strings(root.path("warnings"));
    }

    List<String> infos() {
      return strings(root.path("infos"));
    }

    private static List<String> strings(JsonNode n) {
      List<String> out = new ArrayList<>();
      for (JsonNode v : n) {
        if (v.isString() && out.size() < 20) {
          out.add(PrometheusApi.excerpt(v.asString()));
        }
      }
      return out;
    }
  }

  /**
   * Reads {@code body}.
   *
   * @param cut whether the body is the start of a longer answer: a tree that stops early is then kept
   * @throws ConnectorException {@code RESOURCE_ERROR} when the answer isn't Prometheus' JSON
   */
  static Tree read(byte[] body, boolean cut, Bounds bounds) throws ConnectorException {
    Map<JsonNode, Long> totals = new IdentityHashMap<>();
    ObjectNode root = F.objectNode();
    Walk walk = new Walk();
    try (JsonParser p = JSON.createParser(body)) {
      if (p.nextToken() != JsonToken.START_OBJECT) {
        throw notJson(body);
      }
      fill(p, root, bounds, totals, walk);
    } catch (JacksonException e) {
      if (!cut) {
        throw notJson(body);
      }
    }
    return new Tree(root, cut && !walk.dataClosed, walk.dropped, totals);
  }

  /** What a walk learned beside the tree. */
  private static final class Walk {
    boolean dropped;
    boolean dataClosed;
  }

  private static ConnectorException notJson(byte[] body) {
    String start = new String(body, 0, Math.min(body.length, 200), java.nio.charset.StandardCharsets.UTF_8);
    return ConnectorException.resourceError("The server answered with something that isn't Prometheus' JSON"
        + (start.stripLeading().startsWith("<") ? " (an HTML page: a proxy or a sign-in page?)" : "")
        + ". Check the server URL.", null);
  }

  /** One open container of the walk: what it is, how deep, how many items it had, whether it kept them all. */
  private static final class Frame {
    final JsonNode node;
    final int depth;
    final int max;
    long items;
    boolean dropped;
    String name;

    Frame(JsonNode node, int depth, int max) {
      this.node = node;
      this.depth = depth;
      this.max = max;
    }
  }

  /** Fills {@code root} from the parser at its first token. */
  private static void fill(JsonParser p, ObjectNode root, Bounds bounds, Map<JsonNode, Long> totals, Walk walk) {
    Deque<Frame> stack = new ArrayDeque<>();
    stack.push(new Frame(root, 0, Integer.MAX_VALUE));
    long nodes = 0;
    JsonToken t;
    while (!stack.isEmpty() && (t = p.nextToken()) != null) {
      Frame top = stack.peek();
      if (t == JsonToken.END_OBJECT || t == JsonToken.END_ARRAY) {
        if (top.dropped) {
          totals.put(top.node, top.items);
        }
        if (top.depth == 1 && top.node == root.get("data")) {
          walk.dataClosed = true;
        }
        stack.pop();
        continue;
      }
      if (t == JsonToken.PROPERTY_NAME) {
        top.name = p.currentName();
        continue;
      }
      top.items++;
      if (top.items > top.max || nodes >= MAX_NODES) {
        walk.dropped = true;
        top.dropped = true;
        if (t.isStructStart()) {
          p.skipChildren();
        }
        continue;
      }
      nodes++;
      JsonNode value;
      if (t == JsonToken.START_OBJECT || t == JsonToken.START_ARRAY) {
        value = t == JsonToken.START_OBJECT ? F.objectNode() : F.arrayNode();
        int depth = top.depth + 1;
        stack.push(new Frame(value, depth, depth == bounds.listDepth() ? bounds.listItems() : INNER_ITEMS));
      } else {
        value = scalar(p, t);
      }
      // Attached before it is filled: a body that stops in the middle leaves what was read in place.
      if (top.node instanceof ObjectNode o) {
        o.set(top.name, value);
      } else {
        ((ArrayNode) top.node).add(value);
      }
    }
  }

  private static JsonNode scalar(JsonParser p, JsonToken t) {
    return switch (t) {
      case VALUE_STRING -> {
        String s = p.getString();
        yield F.stringNode(s.length() <= MAX_STRING ? s : s.substring(0, MAX_STRING) + "…");
      }
      case VALUE_NUMBER_INT -> F.numberNode(p.getBigIntegerValue());
      case VALUE_NUMBER_FLOAT -> F.numberNode(p.getDecimalValue());
      case VALUE_TRUE -> F.booleanNode(true);
      case VALUE_FALSE -> F.booleanNode(false);
      default -> F.nullNode();
    };
  }

  /**
   * A time of Prometheus (seconds since 1970, a JSON number with up to nanoseconds) as ISO 8601 in UTC, exactly:
   * {@code 1700000000.123} is {@code 2023-11-14T22:13:20.123Z}. Text that isn't such a number is kept as written.
   */
  static String time(JsonNode n) {
    if (n == null || n.isMissingNode() || n.isNull()) {
      return "";
    }
    BigDecimal seconds;
    try {
      seconds = n.isNumber() ? n.decimalValue() : new BigDecimal(n.asString(""));
    } catch (NumberFormatException | ArithmeticException e) {
      return PrometheusApi.excerpt(n.asString(n.toString()));
    }
    return time(seconds, n);
  }

  static String time(BigDecimal seconds, Object asWritten) {
    if (seconds.abs().compareTo(MAX_SECONDS) > 0) {
      return String.valueOf(asWritten);
    }
    BigDecimal whole = seconds.setScale(0, RoundingMode.FLOOR);
    long nanos = seconds.subtract(whole).movePointRight(9).setScale(0, RoundingMode.FLOOR).longValueExact();
    return Instant.ofEpochSecond(whole.longValueExact(), nanos).toString();
  }

  /**
   * A sample's value as the server wrote it ({@code "0.25"}, {@code "NaN"}, {@code "+Inf"}): Prometheus writes values as
   * strings, so nothing is rounded. A server that sends a JSON number gets its exact plain notation.
   */
  static String value(JsonNode n) {
    if (n == null || n.isMissingNode() || n.isNull()) {
      return "";
    }
    if (n.isNumber()) {
      return JsonNumbers.plain(n.decimalValue());
    }
    return OutputSanitizer.sanitize(n.asString(""));
  }

  /** Whether the tree is an answer of Prometheus' API at all: a status, and data or an error. */
  static boolean envelope(Tree t) {
    return !t.status().isEmpty() && (t.root().has("data") || t.root().has("error"));
  }
}
