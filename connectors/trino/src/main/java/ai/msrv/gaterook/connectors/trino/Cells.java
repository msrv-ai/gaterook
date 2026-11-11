package ai.msrv.gaterook.connectors.trino;

import ai.msrv.gaterook.connector.api.ToolResult;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Trino's values ({@code data} of the client protocol) as typed table cells, by each column's {@code typeSignature}:
 * an integer is a JSON number only where JavaScript keeps it exact, a decimal stays text, a timestamp is ISO-8601
 * ({@code 2026-01-02T03:04:05.123}, with {@code Z} or its offset when it has a zone), binary values show their first
 * bytes in hex, arrays and maps are JSON arrays and objects, a row with named fields an object, text is cut to the
 * cell size. Presto's signatures ({@code TYPE_SIGNATURE}, {@code NAMED_TYPE_SIGNATURE}) read the same.
 */
final class Cells {

  static final long MAX_SAFE_INTEGER = 9_007_199_254_740_992L;
  static final int MAX_BYTES_SHOWN = 256;
  static final int MAX_DEPTH = 15;
  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  /** {@code 2026-01-02 03:04:05.123456 Europe/Berlin}: date, time, zone. */
  private static final Pattern ZONED = Pattern.compile("(-?\\d{4,}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}:\\d{2})(\\.\\d+)? (.+)");
  private static final Pattern LOCAL = Pattern.compile("-?\\d{4,}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(\\.\\d+)?");
  private static final Pattern OFFSET = Pattern.compile("[+-]\\d{2}:\\d{2}");

  private Cells() {}

  /** The columns of an answer: name and Trino's type as it writes it. */
  static List<ToolResult.Column> columns(JsonNode columns) {
    List<ToolResult.Column> out = new ArrayList<>();
    if (columns != null) {
      for (JsonNode c : columns) {
        out.add(new ToolResult.Column(c.path("name").asString(""), c.path("type").asString(""), null, null, null));
      }
    }
    return out;
  }

  /** One row as an array of cells. */
  static ArrayNode row(JsonNode row, JsonNode columns, int maxCellChars) {
    ArrayNode out = F.arrayNode();
    for (int i = 0; i < row.size(); i++) {
      JsonNode signature = columns == null ? null : columns.path(i).path("typeSignature");
      out.add(value(row.get(i), signature, maxCellChars, 0));
    }
    return out;
  }

  static JsonNode value(JsonNode v, JsonNode signature, int maxCellChars, int depth) {
    if (v == null || v.isNull() || v.isMissingNode()) {
      return F.nullNode();
    }
    if (depth > MAX_DEPTH) {
      return F.stringNode("…");
    }
    String raw = rawType(signature);
    switch (raw) {
      case "bigint", "integer", "smallint", "tinyint" -> {
        return integer(v);
      }
      case "double", "real" -> {
        return v.isNumber() ? v : F.stringNode(v.asString(""));
      }
      case "boolean" -> {
        return v.isBoolean() ? v : F.booleanNode("true".equalsIgnoreCase(v.asString("")));
      }
      case "timestamp" -> {
        return F.stringNode(local(v.asString("")));
      }
      case "timestamp with time zone" -> {
        return F.stringNode(zoned(v.asString("")));
      }
      case "varbinary", "hyperloglog", "p4hyperloglog", "qdigest", "tdigest", "setdigest" -> {
        return bytes(v.asString(""));
      }
      case "array" -> {
        JsonNode element = argument(signature, 0);
        ArrayNode out = F.arrayNode();
        for (JsonNode e : v) {
          out.add(value(e, element, maxCellChars, depth + 1));
        }
        return out;
      }
      case "map" -> {
        if (!v.isObject()) {
          return text(v.toString(), maxCellChars);
        }
        JsonNode valueType = argument(signature, 1);
        ObjectNode out = F.objectNode();
        for (var e : v.properties()) {
          out.set(e.getKey(), value(e.getValue(), valueType, maxCellChars, depth + 1));
        }
        return out;
      }
      case "row" -> {
        return row(v, signature, maxCellChars, depth);
      }
      default -> {
        if (v.isString()) {
          return text(v.asString(), maxCellChars);
        }
        if (v.isIntegralNumber()) {
          return integer(v);
        }
        if (v.isContainer()) {
          return text(v.toString(), maxCellChars);
        }
        return v;
      }
    }
  }

  /** A row: an object when every field has a name, else an array in the fields' order. */
  private static JsonNode row(JsonNode v, JsonNode signature, int maxCellChars, int depth) {
    if (!v.isArray()) {
      return v.isObject() ? v : text(v.asString(v.toString()), maxCellChars);
    }
    List<String> names = new ArrayList<>();
    List<JsonNode> types = new ArrayList<>();
    for (JsonNode a : arguments(signature)) {
      JsonNode value = a.path("value");
      JsonNode field = value.path("fieldName");
      String name = field.isObject() ? field.path("name").asString(null) : field.asString(null);
      names.add(name);
      types.add(value.has("typeSignature") ? value.get("typeSignature") : value);
    }
    boolean named = !names.isEmpty() && names.size() == v.size() && names.stream().allMatch(n -> n != null
        && !n.isEmpty()) && names.stream().distinct().count() == names.size();
    if (named) {
      ObjectNode out = F.objectNode();
      for (int i = 0; i < v.size(); i++) {
        out.set(names.get(i), value(v.get(i), types.get(i), maxCellChars, depth + 1));
      }
      return out;
    }
    ArrayNode out = F.arrayNode();
    for (int i = 0; i < v.size(); i++) {
      out.add(value(v.get(i), i < types.size() ? types.get(i) : null, maxCellChars, depth + 1));
    }
    return out;
  }

  /** The base type of a signature, lower case: {@code bigint}, {@code array}, {@code row}. */
  static String rawType(JsonNode signature) {
    if (signature == null || signature.isMissingNode() || signature.isNull()) {
      return "";
    }
    String raw = signature.isString() ? signature.asString() : signature.path("rawType").asString("");
    int paren = raw.indexOf('(');
    return (paren < 0 ? raw : raw.substring(0, paren)).strip().toLowerCase(Locale.ROOT);
  }

  private static List<JsonNode> arguments(JsonNode signature) {
    List<JsonNode> out = new ArrayList<>();
    if (signature != null && signature.isObject()) {
      signature.path("arguments").forEach(out::add);
    }
    return out;
  }

  /** The {@code index}-th type argument (an array's element, a map's key or value). */
  private static JsonNode argument(JsonNode signature, int index) {
    List<JsonNode> arguments = arguments(signature);
    return index < arguments.size() ? arguments.get(index).path("value") : null;
  }

  /** An integer: a JSON number up to 2^53, else its digits as text. */
  static JsonNode integer(JsonNode v) {
    if (v.isIntegralNumber()) {
      BigInteger n = v.bigIntegerValue();
      return n.abs().compareTo(BigInteger.valueOf(MAX_SAFE_INTEGER)) <= 0 ? F.numberNode(n.longValue())
          : F.stringNode(n.toString());
    }
    return F.stringNode(v.asString(v.toString()));
  }

  /** {@code 2026-01-02 03:04:05.123} as {@code 2026-01-02T03:04:05.123}. */
  static String local(String text) {
    return LOCAL.matcher(text).matches() ? text.replaceFirst(" ", "T") : text;
  }

  /**
   * {@code 2026-01-02 03:04:05.123 UTC} as {@code 2026-01-02T03:04:05.123Z}; an offset stays as it is; a region's
   * offset at that time is computed ({@code +01:00} for Europe/Berlin in January), the fraction kept to the digit.
   */
  static String zoned(String text) {
    Matcher m = ZONED.matcher(text);
    if (!m.matches()) {
      return text;
    }
    String fraction = m.group(3) == null ? "" : m.group(3);
    String head = m.group(1) + "T" + m.group(2) + fraction;
    String zone = m.group(4);
    if (zone.equals("UTC") || zone.equals("Z")) {
      return head + "Z";
    }
    if (OFFSET.matcher(zone).matches()) {
      return head + zone;
    }
    try {
      String nanos = fraction.length() > 10 ? fraction.substring(0, 10) : fraction;
      LocalDateTime local = LocalDateTime.parse(m.group(1) + "T" + m.group(2) + nanos);
      String offset = ZonedDateTime.of(local, ZoneId.of(zone)).getOffset().getId();
      return head + (offset.equals("Z") ? "Z" : offset);
    } catch (DateTimeException e) {
      return text;
    }
  }

  /** Base64 of the protocol as a hex preview: {@code \x00ff10}, longer values with their length. */
  static JsonNode bytes(String base64) {
    byte[] b;
    try {
      b = Base64.getDecoder().decode(base64);
    } catch (IllegalArgumentException e) {
      return F.stringNode(base64);
    }
    int shown = Math.min(b.length, MAX_BYTES_SHOWN);
    String hex = "\\x" + HexFormat.of().formatHex(b, 0, shown);
    return F.stringNode(b.length > shown ? hex + "…‹" + b.length + " bytes›" : hex);
  }

  static JsonNode text(String v, int max) {
    if (v.length() <= max) {
      return F.stringNode(v);
    }
    int end = Character.isHighSurrogate(v.charAt(max - 1)) ? max - 1 : max;
    return F.stringNode(v.substring(0, end) + "…‹" + v.length() + " characters›");
  }
}
