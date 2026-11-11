package ai.msrv.gaterook.connectors.dynamodb;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * DynamoDB's typed values ({@code {"S": "a"}}, {@code {"N": "12.5"}}, {@code {"M": {…}}}) both ways: what an agent
 * passes as key values and parameters is checked as one; what comes back is shown as plain JSON. A number is a JSON
 * number only where JavaScript reads it exactly (an integer up to 2^53, a decimal of at most 15 digits), else its
 * text; a binary value is its first 256 bytes in hex with its length, as the SQL connectors show blobs; a string is
 * cut to the call's cell size.
 */
final class Items {

  static final long MAX_SAFE_INTEGER = 9_007_199_254_740_992L;
  static final int MAX_BYTES_SHOWN = 256;
  /** Nesting of maps and lists in a value an agent passes, and in one DynamoDB returns (it allows 32). */
  static final int MAX_DEPTH = 32;
  private static final Set<String> TYPES = Set.of("S", "N", "B", "BOOL", "NULL", "M", "L", "SS", "NS", "BS");
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private Items() {}

  /** An item's attributes as plain JSON. */
  static ObjectNode plainItem(JsonNode item, int maxCellChars) {
    ObjectNode out = F.objectNode();
    for (Map.Entry<String, JsonNode> attribute : item.properties()) {
      out.set(attribute.getKey(), plain(attribute.getValue(), maxCellChars, 0));
    }
    return out;
  }

  /** One typed value as plain JSON. */
  static JsonNode plain(JsonNode value, int maxCellChars, int depth) {
    if (value == null || !value.isObject() || value.size() != 1 || depth > MAX_DEPTH) {
      return F.nullNode();
    }
    Map.Entry<String, JsonNode> typed = value.properties().iterator().next();
    JsonNode v = typed.getValue();
    return switch (typed.getKey()) {
      case "S" -> text(v.asString(""), maxCellChars);
      case "N" -> number(v.asString(""));
      case "B" -> binary(v.asString(""));
      case "BOOL" -> F.booleanNode(v.asBoolean(false));
      case "NULL" -> F.nullNode();
      case "M" -> {
        ObjectNode m = F.objectNode();
        for (Map.Entry<String, JsonNode> e : v.properties()) {
          m.set(e.getKey(), plain(e.getValue(), maxCellChars, depth + 1));
        }
        yield m;
      }
      case "L" -> {
        ArrayNode l = F.arrayNode();
        v.forEach(e -> l.add(plain(e, maxCellChars, depth + 1)));
        yield l;
      }
      case "SS" -> {
        ArrayNode l = F.arrayNode();
        v.forEach(e -> l.add(text(e.asString(""), maxCellChars)));
        yield l;
      }
      case "NS" -> {
        ArrayNode l = F.arrayNode();
        v.forEach(e -> l.add(number(e.asString(""))));
        yield l;
      }
      case "BS" -> {
        ArrayNode l = F.arrayNode();
        v.forEach(e -> l.add(binary(e.asString(""))));
        yield l;
      }
      default -> F.nullNode();
    };
  }

  /** A DynamoDB number: exact as JSON where JavaScript keeps it, else its text. */
  static JsonNode number(String text) {
    BigDecimal n;
    try {
      n = new BigDecimal(text);
    } catch (NumberFormatException e) {
      return F.stringNode(text);
    }
    if (n.signum() == 0 || (n.scale() <= 0 || n.stripTrailingZeros().scale() <= 0)) {
      BigDecimal whole = n.stripTrailingZeros();
      if (whole.abs().compareTo(BigDecimal.valueOf(MAX_SAFE_INTEGER)) <= 0) {
        return F.numberNode(whole.longValueExact());
      }
      return F.stringNode(text);
    }
    return n.precision() <= 15 && n.abs().compareTo(BigDecimal.valueOf(MAX_SAFE_INTEGER)) <= 0
        ? F.numberNode(n.doubleValue()) : F.stringNode(text);
  }

  private static JsonNode binary(String base64) {
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(base64);
    } catch (IllegalArgumentException e) {
      return F.stringNode("‹not base64›");
    }
    int shown = Math.min(bytes.length, MAX_BYTES_SHOWN);
    String hex = "\\x" + HexFormat.of().formatHex(bytes, 0, shown);
    return F.stringNode(bytes.length > shown ? hex + "…‹" + bytes.length + " bytes›" : hex);
  }

  /** A string cut to {@code max} characters, never inside a surrogate pair. */
  static JsonNode text(String v, int max) {
    if (v.length() <= max) {
      return F.stringNode(v);
    }
    int end = Character.isHighSurrogate(v.charAt(max - 1)) ? max - 1 : max;
    return F.stringNode(v.substring(0, end) + "…‹" + v.length() + " characters›");
  }

  /**
   * Why {@code value} isn't one DynamoDB typed value an agent may pass ({@code {"S": "a"}}), or {@code null}: one
   * known type, the right JSON for it, a number that is one, valid base64, bounded nesting.
   */
  static String invalid(JsonNode value) {
    return invalid(value, 0);
  }

  private static String invalid(JsonNode value, int depth) {
    if (depth > MAX_DEPTH) {
      return "nests deeper than " + MAX_DEPTH + " levels";
    }
    if (value == null || !value.isObject() || value.size() != 1) {
      return "isn't one typed value such as {\"S\": \"text\"} or {\"N\": \"42\"}";
    }
    Map.Entry<String, JsonNode> typed = value.properties().iterator().next();
    String type = typed.getKey();
    JsonNode v = typed.getValue();
    if (!TYPES.contains(type)) {
      return "has the unknown type " + type + " (S, N, B, BOOL, NULL, M, L, SS, NS or BS)";
    }
    return switch (type) {
      case "S" -> v.isString() ? null : "S takes a string";
      case "N" -> v.isString() && isNumber(v.asString()) ? null : "N takes a number written as a string";
      case "B" -> v.isString() && isBase64(v.asString()) ? null : "B takes base64";
      case "BOOL" -> v.isBoolean() ? null : "BOOL takes true or false";
      case "NULL" -> v.isBoolean() && v.asBoolean() ? null : "NULL takes true";
      case "SS" -> setOf(v, n -> n.isString());
      case "NS" -> setOf(v, n -> n.isString() && isNumber(n.asString()));
      case "BS" -> setOf(v, n -> n.isString() && isBase64(n.asString()));
      case "M" -> {
        if (!v.isObject()) {
          yield "M takes an object of typed values";
        }
        for (Map.Entry<String, JsonNode> e : v.properties()) {
          String problem = invalid(e.getValue(), depth + 1);
          if (problem != null) {
            yield e.getKey() + " " + problem;
          }
        }
        yield null;
      }
      case "L" -> {
        if (!v.isArray()) {
          yield "L takes an array of typed values";
        }
        for (JsonNode e : v) {
          String problem = invalid(e, depth + 1);
          if (problem != null) {
            yield "an element " + problem;
          }
        }
        yield null;
      }
      default -> "has an unknown type";
    };
  }

  private static String setOf(JsonNode v, java.util.function.Predicate<JsonNode> element) {
    if (!v.isArray() || v.isEmpty()) {
      return "a set takes a non-empty array";
    }
    for (JsonNode e : v) {
      if (!element.test(e)) {
        return "a set holds a value of the wrong type";
      }
    }
    return null;
  }

  private static boolean isNumber(String text) {
    try {
      new BigDecimal(text);
      return text.length() <= 64;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private static boolean isBase64(String text) {
    try {
      Base64.getDecoder().decode(text);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
