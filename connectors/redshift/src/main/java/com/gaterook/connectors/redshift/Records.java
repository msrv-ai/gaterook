package com.gaterook.connectors.redshift;

import com.gaterook.connector.api.ToolResult;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * The Data API's records ({@code [[{"longValue": 1}, {"stringValue": "a"}]]}) as a table with typed cells, by the
 * result's column metadata: a {@code longValue} as a number only where JavaScript keeps it exact, a
 * {@code doubleValue} as a number when it is finite, {@code numeric} as text, {@code timestamp} and
 * {@code timestamptz} as ISO 8601 ({@code timestamptz} in UTC), {@code SUPER} as JSON when it fits a cell, binary
 * values as a hex preview, booleans and nulls as they are, strings cut to the cell size.
 */
final class Records {

  /** {@code Number.MAX_SAFE_INTEGER}: the largest whole number JavaScript keeps exact, with all below it. */
  static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
  static final int MAX_BYTES_SHOWN = 256;
  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Pattern INTEGER = Pattern.compile("-?\\d{1,19}");
  /** {@code 2026-11-11 09:30:00[.123456]}: Redshift's text of a timestamp. */
  private static final DateTimeFormatter LOCAL = new DateTimeFormatterBuilder()
      .appendPattern("uuuu-MM-dd HH:mm:ss")
      .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
      .toFormatter(Locale.ROOT);
  /** {@code 2026-11-11 09:30:00[.123456]+00[:30]}: a timestamptz. */
  private static final DateTimeFormatter OFFSET = new DateTimeFormatterBuilder()
      .appendPattern("uuuu-MM-dd HH:mm:ss")
      .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
      .appendOffset("+HH:mm", "+00")
      .toFormatter(Locale.ROOT);
  private static final DateTimeFormatter OFFSET_SHORT = new DateTimeFormatterBuilder()
      .appendPattern("uuuu-MM-dd HH:mm:ss")
      .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
      .appendOffset("+HH", "+00")
      .toFormatter(Locale.ROOT);

  private Records() {}

  /** The columns of a result: their labels and Redshift's type names ({@code int4}, {@code varchar}). */
  static List<ToolResult.Column> columns(JsonNode metadata) {
    List<ToolResult.Column> out = new ArrayList<>();
    for (JsonNode c : metadata) {
      String label = c.path("label").asString("");
      String name = label.isBlank() ? c.path("name").asString("") : label;
      out.add(new ToolResult.Column(name, c.path("typeName").asString(""), blankToNull(c.path("schemaName")),
          blankToNull(c.path("tableName")), null));
    }
    return out;
  }

  private static String blankToNull(JsonNode n) {
    String s = n.asString("");
    return s.isBlank() ? null : s;
  }

  /** One record as an array of cells. */
  static ArrayNode row(JsonNode record, JsonNode metadata, int maxCellChars) {
    ArrayNode out = F.arrayNode();
    for (int i = 0; i < record.size(); i++) {
      out.add(cell(record.get(i), metadata.path(i).path("typeName").asString(""), maxCellChars));
    }
    return out;
  }

  /**
   * One field, a union: exactly one of {@code isNull}, {@code booleanValue}, {@code longValue}, {@code doubleValue},
   * {@code stringValue} or {@code blobValue}.
   */
  static JsonNode cell(JsonNode field, String typeName, int maxCellChars) {
    if (field == null || !field.isObject() || field.path("isNull").asBoolean(false)) {
      return F.nullNode();
    }
    JsonNode v;
    if ((v = field.get("booleanValue")) != null && v.isBoolean()) {
      return F.booleanNode(v.asBoolean());
    }
    if ((v = field.get("longValue")) != null && v.isNumber()) {
      return v.isIntegralNumber() && v.canConvertToLong() && safe(v.asLong()) ? F.numberNode(v.asLong())
          : F.stringNode(v.asString());
    }
    if ((v = field.get("doubleValue")) != null) {
      if (v.isNumber() && Double.isFinite(v.asDouble())) {
        return F.numberNode(v.asDouble());
      }
      return F.stringNode(v.asString());
    }
    if ((v = field.get("blobValue")) != null && v.isString()) {
      return bytes(v.asString());
    }
    if ((v = field.get("stringValue")) != null && v.isString()) {
      return string(v.asString(), typeName.toLowerCase(Locale.ROOT), maxCellChars);
    }
    return F.nullNode();
  }

  private static JsonNode string(String s, String type, int maxCellChars) {
    switch (type) {
      case "timestamp" -> {
        try {
          return F.stringNode(LocalDateTime.parse(s, LOCAL).toString());
        } catch (DateTimeParseException e) {
          return text(s, maxCellChars);
        }
      }
      case "timestamptz" -> {
        for (DateTimeFormatter f : List.of(OFFSET, OFFSET_SHORT)) {
          try {
            return F.stringNode(OffsetDateTime.parse(s, f).withOffsetSameInstant(ZoneOffset.UTC).toInstant()
                .toString());
          } catch (DateTimeParseException e) {
            // The other spelling of the offset.
          }
        }
        return text(s, maxCellChars);
      }
      case "int2", "int4", "int8" -> {
        return integer(s);
      }
      case "super" -> {
        if (s.length() <= maxCellChars) {
          try {
            return bounded(JSON.readTree(s), 0);
          } catch (JacksonException e) {
            // Not JSON: shown as text.
          }
        }
        return text(s, maxCellChars);
      }
      default -> {
        return text(s, maxCellChars);
      }
    }
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

  /** A SUPER value parsed as JSON: numbers JavaScript can't keep exact as text, at most a few levels deep. */
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

  private static JsonNode bytes(String base64) {
    byte[] b;
    try {
      b = Base64.getDecoder().decode(base64);
    } catch (IllegalArgumentException e) {
      return F.stringNode("‹binary›");
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
