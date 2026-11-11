package ai.msrv.gaterook.connectors.bigquery;

import ai.msrv.gaterook.connector.api.ToolResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * BigQuery's rows ({@code {"f": [{"v": …}]}}) as a table with typed cells, by the result's schema: INT64 as a number
 * only where JavaScript keeps it exact, NUMERIC and BIGNUMERIC as text, TIMESTAMP (asked as microseconds) as ISO-8601
 * UTC, BYTES as a hex preview, STRUCT as an object, REPEATED as an array, strings cut to the cell size.
 */
final class Rows {

  static final long MAX_SAFE_INTEGER = 9_007_199_254_740_992L;
  static final int MAX_BYTES_SHOWN = 256;
  static final int MAX_DEPTH = 15;
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private Rows() {}

  /** The columns of a result schema. */
  static List<ToolResult.Column> columns(JsonNode schema) {
    List<ToolResult.Column> out = new ArrayList<>();
    for (JsonNode field : schema.path("fields")) {
      String type = field.path("type").asString("");
      if ("REPEATED".equals(field.path("mode").asString(""))) {
        type = "ARRAY<" + type + ">";
      }
      out.add(new ToolResult.Column(field.path("name").asString(""), type, null, null, null));
    }
    return out;
  }

  /** One row as an array of cells. */
  static ArrayNode row(JsonNode row, JsonNode schema, int maxCellChars) {
    ArrayNode out = F.arrayNode();
    JsonNode cells = row.path("f");
    JsonNode fields = schema.path("fields");
    for (int i = 0; i < fields.size(); i++) {
      out.add(value(cells.path(i).path("v"), fields.get(i), maxCellChars, 0));
    }
    return out;
  }

  private static JsonNode value(JsonNode v, JsonNode field, int maxCellChars, int depth) {
    if (v == null || v.isMissingNode() || v.isNull() || depth > MAX_DEPTH) {
      return F.nullNode();
    }
    if ("REPEATED".equals(field.path("mode").asString(""))) {
      ArrayNode out = F.arrayNode();
      JsonNode element = elementOf(field);
      for (JsonNode e : v) {
        out.add(value(e.path("v"), element, maxCellChars, depth + 1));
      }
      return out;
    }
    String type = field.path("type").asString("").toUpperCase(Locale.ROOT);
    return switch (type) {
      case "RECORD", "STRUCT" -> {
        ObjectNode out = F.objectNode();
        JsonNode sub = field.path("fields");
        for (int i = 0; i < sub.size(); i++) {
          out.set(sub.get(i).path("name").asString(""), value(v.path("f").path(i).path("v"), sub.get(i),
              maxCellChars, depth + 1));
        }
        yield out;
      }
      case "INTEGER", "INT64" -> integer(v.asString(""));
      case "FLOAT", "FLOAT64" -> {
        String text = v.asString("");
        try {
          double d = Double.parseDouble(text);
          yield Double.isFinite(d) ? F.numberNode(d) : F.stringNode(text);
        } catch (NumberFormatException e) {
          yield F.stringNode(text);
        }
      }
      case "BOOLEAN", "BOOL" -> F.booleanNode("true".equalsIgnoreCase(v.asString("")));
      case "TIMESTAMP" -> timestamp(v.asString(""));
      case "BYTES" -> bytes(v.asString(""));
      default -> text(v.isString() ? v.asString() : v.toString(), maxCellChars);
    };
  }

  /** A REPEATED field's element: the same field, not repeated. */
  private static JsonNode elementOf(JsonNode field) {
    ObjectNode element = ((ObjectNode) field).deepCopy();
    element.put("mode", "NULLABLE");
    return element;
  }

  /** An int64 that Google's JSON writes as a string (or a number); 0 when it is neither. */
  static long count(JsonNode n) {
    if (n == null || n.isMissingNode() || n.isNull()) {
      return 0;
    }
    if (n.isIntegralNumber()) {
      return n.asLong();
    }
    try {
      return Long.parseLong(n.asString("0").strip());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  static JsonNode integer(String text) {
    try {
      BigDecimal n = new BigDecimal(text);
      if (n.abs().compareTo(BigDecimal.valueOf(MAX_SAFE_INTEGER)) <= 0) {
        return F.numberNode(n.longValueExact());
      }
    } catch (NumberFormatException | ArithmeticException e) {
      // Not an integer: shown as BigQuery wrote it.
    }
    return F.stringNode(text);
  }

  /** Microseconds since the epoch (formatOptions.useInt64Timestamp) or seconds as a float, as ISO-8601 UTC. */
  static JsonNode timestamp(String text) {
    try {
      if (text.matches("-?\\d+")) {
        long micros = Long.parseLong(text);
        return F.stringNode(Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
            Math.floorMod(micros, 1_000_000L) * 1_000L).toString());
      }
      BigDecimal seconds = new BigDecimal(text);
      long whole = seconds.setScale(0, java.math.RoundingMode.FLOOR).longValueExact();
      long nanos = seconds.subtract(BigDecimal.valueOf(whole)).movePointRight(9).longValue();
      return F.stringNode(Instant.ofEpochSecond(whole, nanos).toString());
    } catch (NumberFormatException | ArithmeticException | java.time.DateTimeException e) {
      return F.stringNode(text);
    }
  }

  private static JsonNode bytes(String base64) {
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
