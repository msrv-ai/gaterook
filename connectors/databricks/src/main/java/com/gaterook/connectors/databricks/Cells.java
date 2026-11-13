package com.gaterook.connectors.databricks;

import com.gaterook.connector.api.ToolResult;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The rows of a {@code JSON_ARRAY} result ({@code [["1", "Ada", null]]}: text or null) as a table with typed cells, by
 * the manifest's column types: whole numbers as JSON numbers only where JavaScript keeps them exact, {@code DECIMAL}
 * as text, {@code TIMESTAMP} as ISO 8601 (in UTC when Databricks gives an offset), {@code TIMESTAMP_NTZ} as ISO 8601
 * without one, booleans, binary values as a hex preview, arrays, maps and structs as JSON when they fit a cell, other
 * text cut to the cell size.
 */
final class Cells {

  /** {@code Number.MAX_SAFE_INTEGER}: the largest whole number JavaScript keeps exact, with all below it. */
  static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
  static final int MAX_BYTES_SHOWN = 256;
  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Pattern INTEGER = Pattern.compile("-?\\d{1,19}");
  /** {@code 2026-11-11 09:30:00[.123456]} or with a T. */
  private static final DateTimeFormatter LOCAL = new DateTimeFormatterBuilder()
      .appendPattern("uuuu-MM-dd")
      .optionalStart().appendLiteral('T').optionalEnd()
      .optionalStart().appendLiteral(' ').optionalEnd()
      .appendPattern("HH:mm:ss")
      .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
      .toFormatter(Locale.ROOT);
  private static final DateTimeFormatter OFFSET = new DateTimeFormatterBuilder()
      .append(LOCAL)
      .appendOffset("+HH:MM", "Z")
      .toFormatter(Locale.ROOT);

  private Cells() {}

  /** The columns of a manifest: name and the full SQL type ({@code DECIMAL(10,2)}, {@code ARRAY<STRING>}). */
  static List<ToolResult.Column> columns(JsonNode manifest) {
    List<ToolResult.Column> out = new ArrayList<>();
    for (JsonNode c : manifest.path("schema").path("columns")) {
      String type = c.path("type_text").asString(c.path("type_name").asString(""));
      out.add(new ToolResult.Column(c.path("name").asString(""), type, null, null, null));
    }
    return out;
  }

  /** The base types of a manifest's columns ({@code LONG}, {@code STRUCT}), in order. */
  static List<String> types(JsonNode manifest) {
    List<String> out = new ArrayList<>();
    for (JsonNode c : manifest.path("schema").path("columns")) {
      out.add(c.path("type_name").asString("STRING").toUpperCase(Locale.ROOT));
    }
    return out;
  }

  /** One row as an array of cells. */
  static ArrayNode row(JsonNode row, List<String> types, int maxCellChars) {
    ArrayNode out = F.arrayNode();
    for (int i = 0; i < row.size(); i++) {
      out.add(cell(row.get(i), i < types.size() ? types.get(i) : "STRING", maxCellChars));
    }
    return out;
  }

  static JsonNode cell(JsonNode v, String type, int maxCellChars) {
    if (v == null || v.isNull() || v.isMissingNode()) {
      return F.nullNode();
    }
    String s = v.isString() ? v.asString() : v.toString();
    return switch (type) {
      case "BOOLEAN" -> s.equalsIgnoreCase("true") ? F.booleanNode(true) : s.equalsIgnoreCase("false")
          ? F.booleanNode(false) : text(s, maxCellChars);
      case "BYTE", "SHORT", "INT", "LONG" -> integer(s);
      case "FLOAT", "DOUBLE" -> {
        try {
          double d = Double.parseDouble(s);
          yield Double.isFinite(d) && s.length() <= 40 ? F.numberNode(d) : F.stringNode(s);
        } catch (NumberFormatException e) {
          yield F.stringNode(s);
        }
      }
      case "TIMESTAMP" -> timestamp(s, maxCellChars);
      case "TIMESTAMP_NTZ" -> local(s, maxCellChars);
      case "BINARY" -> bytes(s, maxCellChars);
      case "ARRAY", "MAP", "STRUCT", "VARIANT" -> structured(s, maxCellChars);
      case "NULL" -> F.nullNode();
      default -> text(s, maxCellChars);
    };
  }

  static boolean safe(long n) {
    return n >= -MAX_SAFE_INTEGER && n <= MAX_SAFE_INTEGER;
  }

  /** A whole number as a JSON number where JavaScript keeps it exact; its text otherwise. */
  static JsonNode integer(String text) {
    if (INTEGER.matcher(text).matches()) {
      try {
        long n = Long.parseLong(text);
        if (safe(n)) {
          return F.numberNode(n);
        }
      } catch (NumberFormatException e) {
        // Beyond a long: shown as text.
      }
    }
    return F.stringNode(text);
  }

  /** A TIMESTAMP: with an offset as an instant in UTC; without one as Databricks wrote it, in ISO 8601. */
  private static JsonNode timestamp(String s, int maxCellChars) {
    try {
      return F.stringNode(OffsetDateTime.parse(s, OFFSET).toInstant().toString());
    } catch (DateTimeParseException e) {
      return local(s, maxCellChars);
    }
  }

  private static JsonNode local(String s, int maxCellChars) {
    try {
      return F.stringNode(LocalDateTime.parse(s, LOCAL).toString());
    } catch (DateTimeParseException e) {
      return text(s, maxCellChars);
    }
  }

  /** BINARY, which JSON_ARRAY writes in Base64: the first bytes in hex. */
  private static JsonNode bytes(String base64, int maxCellChars) {
    byte[] b;
    try {
      b = Base64.getDecoder().decode(base64);
    } catch (IllegalArgumentException e) {
      return text(base64, maxCellChars);
    }
    int shown = Math.min(b.length, MAX_BYTES_SHOWN);
    String hex = "\\x" + HexFormat.of().formatHex(b, 0, shown);
    return F.stringNode(b.length > shown ? hex + "…‹" + b.length + " bytes›" : hex);
  }

  /** An ARRAY, MAP, STRUCT or VARIANT, which JSON_ARRAY writes as JSON text: parsed when it fits a cell. */
  private static JsonNode structured(String s, int maxCellChars) {
    if (s.length() <= maxCellChars) {
      try {
        return bounded(JSON.readTree(s), 0);
      } catch (JacksonException e) {
        // Not JSON: shown as text.
      }
    }
    return text(s, maxCellChars);
  }

  /** Parsed JSON with the numbers JavaScript can't keep exact as text, at most a few levels deep. */
  private static JsonNode bounded(JsonNode n, int depth) {
    if (depth > 32) {
      return F.stringNode(n.toString());
    }
    if (n.isObject()) {
      ObjectNode out = F.objectNode();
      for (var e : n.properties()) {
        out.set(e.getKey(), bounded(e.getValue(), depth + 1));
      }
      return out;
    }
    if (n.isArray()) {
      ArrayNode out = F.arrayNode();
      n.forEach(e -> out.add(bounded(e, depth + 1)));
      return out;
    }
    if (n.isIntegralNumber()) {
      return n.canConvertToLong() && safe(n.asLong()) ? F.numberNode(n.asLong()) : F.stringNode(n.asString());
    }
    if (n.isNumber()) {
      return Double.isFinite(n.asDouble()) ? F.numberNode(n.asDouble()) : F.stringNode(n.asString());
    }
    return n;
  }

  static JsonNode text(String v, int max) {
    if (v.length() <= max) {
      return F.stringNode(v);
    }
    int end = Character.isHighSurrogate(v.charAt(max - 1)) ? max - 1 : max;
    return F.stringNode(v.substring(0, end) + "…‹" + v.length() + " characters›");
  }
}
