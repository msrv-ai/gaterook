package ai.msrv.gaterook.connectors.duckdb;

import ai.msrv.gaterook.connector.api.ToolResult;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Struct;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Rows of a DuckDB result as the agent gets them, with the limits of connectors/sql's ResultReader: JSON numbers where
 * they stay exact (integers within ±2^53, finite floats), strings for decimals and bigger integers, ISO-8601 for
 * timestamps (with the offset for {@code TIMESTAMPTZ}), DuckDB's own text for other scalar types, {@code \x…} hex for
 * blobs, JSON arrays and objects for lists, structs and maps, or DuckDB's text of one that holds a value JSON can't say
 * exactly here. A cell longer than {@code maxCellChars} is cut and says how much was left out.
 * Reading stops after {@code maxRows} rows (one more is read to know there were more) or when the rows outgrow
 * {@code maxBytes}. GateRook bounds a query's rows in DuckDB itself with an outer {@code LIMIT}, so the reader never
 * sees more than one row past the limit.
 */
final class DuckDbResults {

  static final long MAX_SAFE_INTEGER = 9_007_199_254_740_992L;
  static final int MAX_BYTES_SHOWN = 256;
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  /**
   * What was read: the table and why it stopped early, if it did.
   *
   * @param total the rows of the result: those read, plus the rest when they were counted
   */
  record Read(ToolResult.Table table, String truncatedReason, long total) {}

  private DuckDbResults() {}

  /** A statement that returned no rows at all. */
  static Read empty() {
    return new Read(new ToolResult.Table(List.of(), NODES.arrayNode(), null), null, 0);
  }

  /** @param countRest go on counting the rows after the limit without keeping them (RETURNING of a change) */
  static Read read(ResultSet rs, int maxRows, long maxBytes, int maxCellChars, boolean countRest)
      throws SQLException {
    ResultSetMetaData md = rs.getMetaData();
    int n = md.getColumnCount();
    List<ToolResult.Column> columns = new ArrayList<>(n);
    String[] types = new String[n];
    for (int i = 1; i <= n; i++) {
      types[i - 1] = md.getColumnTypeName(i) == null ? "" : md.getColumnTypeName(i);
      columns.add(new ToolResult.Column(md.getColumnLabel(i), types[i - 1], null, null, null));
    }
    ArrayNode rows = NODES.arrayNode();
    String truncated = null;
    long bytes = 0;
    long total = 0;
    while (rs.next()) {
      if (rows.size() >= maxRows) {
        truncated = "row_limit";
        total++;
        break;
      }
      ArrayNode row = rows.addArray();
      total++;
      for (int i = 1; i <= n; i++) {
        JsonNode cell = cell(rs, i, types[i - 1], maxCellChars);
        row.add(cell);
        bytes += cell.isString() ? cell.asString().length() + 2L : cell.isContainer() ? cell.toString().length() : 8L;
      }
      if (bytes > maxBytes) {
        truncated = "size_limit";
        break;
      }
      if (Thread.currentThread().isInterrupted()) {
        throw new SQLException("The call was stopped while its rows were read.");
      }
    }
    if (truncated != null && countRest) {
      while (rs.next()) {
        total++;
      }
    }
    return new Read(new ToolResult.Table(List.copyOf(columns), rows, null), truncated, total);
  }

  /** A typed value by DuckDB's type name, or DuckDB's text where no JSON form says it exactly. */
  static JsonNode cell(ResultSet rs, int i, String typeName, int maxCellChars) throws SQLException {
    String type = typeName.toUpperCase(Locale.ROOT);
    try {
      if (type.endsWith("]") || type.startsWith("STRUCT") || type.startsWith("MAP") || type.startsWith("UNION")) {
        return nestedCell(rs, i, maxCellChars);
      }
      switch (type) {
        case "BOOLEAN" -> {
          boolean v = rs.getBoolean(i);
          return rs.wasNull() ? NODES.nullNode() : NODES.booleanNode(v);
        }
        case "TINYINT", "SMALLINT", "INTEGER", "BIGINT", "UTINYINT", "USMALLINT", "UINTEGER", "UBIGINT", "HUGEINT",
            "UHUGEINT" -> {
          Object v = rs.getObject(i);
          return v == null ? NODES.nullNode() : integer(v, maxCellChars);
        }
        case "FLOAT", "DOUBLE" -> {
          double v = rs.getDouble(i);
          if (rs.wasNull()) {
            return NODES.nullNode();
          }
          return Double.isFinite(v) ? NODES.numberNode(v) : NODES.stringNode(Double.toString(v));
        }
        case "BLOB" -> {
          byte[] v = rs.getBytes(i);
          return v == null ? NODES.nullNode() : NODES.stringNode(hex(v));
        }
        case "TIMESTAMP WITH TIME ZONE" -> {
          OffsetDateTime v = rs.getObject(i, OffsetDateTime.class);
          return v == null ? NODES.nullNode() : text(v.toString(), maxCellChars);
        }
        case "TIMESTAMP", "TIMESTAMP_S", "TIMESTAMP_MS", "TIMESTAMP_NS", "DATETIME" -> {
          // The date and time as stored: java.sql.Timestamp would render it through this JVM's time zone.
          LocalDateTime v = rs.getObject(i, LocalDateTime.class);
          return v == null ? NODES.nullNode() : text(DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(v), maxCellChars);
        }
        default -> {
          if (type.startsWith("DECIMAL")) {
            BigDecimal v = rs.getBigDecimal(i);
            return v == null ? NODES.nullNode() : NODES.stringNode(v.toPlainString());
          }
          String v = rs.getString(i);
          return v == null ? NODES.nullNode() : text(v, maxCellChars);
        }
      }
    } catch (SQLException | RuntimeException e) {
      String v = rs.getString(i);
      return v == null ? NODES.nullNode() : text(v, maxCellChars);
    }
  }

  /** A list, struct or map as JSON; DuckDB's own text when it holds something JSON can't say exactly. */
  private static JsonNode nestedCell(ResultSet rs, int i, int maxCellChars) throws SQLException {
    Object v = rs.getObject(i);
    if (v == null) {
      return NODES.nullNode();
    }
    JsonNode json;
    try {
      json = nested(v, 0);
    } catch (SQLException | RuntimeException e) {
      json = null;
    }
    if (json == null) {
      String text = rs.getString(i);
      return text == null ? NODES.nullNode() : text(text, maxCellChars);
    }
    String rendered = json.toString();
    return rendered.length() <= maxCellChars ? json : text(rendered, maxCellChars);
  }

  /** JSON of a nested value, or {@code null} when a part of it has no exact JSON form here. */
  private static JsonNode nested(Object v, int depth) throws SQLException {
    if (depth > 32) {
      return null;
    }
    switch (v) {
      case null -> {
        return NODES.nullNode();
      }
      case Boolean b -> {
        return NODES.booleanNode(b);
      }
      case Byte x -> {
        return NODES.numberNode(x.longValue());
      }
      case Short x -> {
        return NODES.numberNode(x.longValue());
      }
      case Integer x -> {
        return NODES.numberNode(x.longValue());
      }
      case Long x -> {
        return Math.abs(x) <= MAX_SAFE_INTEGER ? NODES.numberNode(x) : NODES.stringNode(x.toString());
      }
      case BigInteger x -> {
        return x.abs().compareTo(BigInteger.valueOf(MAX_SAFE_INTEGER)) <= 0 ? NODES.numberNode(x.longValueExact())
            : NODES.stringNode(x.toString());
      }
      case BigDecimal x -> {
        return NODES.stringNode(x.toPlainString());
      }
      case Float x -> {
        return Float.isFinite(x) ? NODES.numberNode(x.doubleValue()) : NODES.stringNode(x.toString());
      }
      case Double x -> {
        return Double.isFinite(x) ? NODES.numberNode(x) : NODES.stringNode(x.toString());
      }
      case String s -> {
        return NODES.stringNode(s);
      }
      case UUID u -> {
        return NODES.stringNode(u.toString());
      }
      case byte[] b -> {
        return NODES.stringNode(hex(b));
      }
      case OffsetDateTime t -> {
        return NODES.stringNode(t.toString());
      }
      case Array a -> {
        Object items = a.getArray();
        return items instanceof Object[] array ? list(array, depth) : null;
      }
      case Object[] array -> {
        return list(array, depth);
      }
      case org.duckdb.DuckDBStruct s -> {
        return map(s.getMap(), depth);
      }
      case Struct s -> {
        return list(s.getAttributes(), depth);
      }
      case Map<?, ?> m -> {
        return map(m, depth);
      }
      default -> {
        // java.sql.Timestamp and friends render in this JVM's time zone: DuckDB's own text instead.
        return v instanceof TemporalAccessor && !(v instanceof java.util.Date) ? NODES.stringNode(v.toString())
            : null;
      }
    }
  }

  private static JsonNode list(Object[] values, int depth) throws SQLException {
    ArrayNode out = NODES.arrayNode();
    for (Object o : values) {
      JsonNode item = nested(o, depth + 1);
      if (item == null) {
        return null;
      }
      out.add(item);
    }
    return out;
  }

  /** A struct or a map with text keys as an object; a map with other keys as {@code [{"key", "value"}]}. */
  private static JsonNode map(Map<?, ?> m, int depth) throws SQLException {
    boolean textKeys = m.keySet().stream().allMatch(k -> k instanceof String);
    if (textKeys) {
      ObjectNode out = NODES.objectNode();
      for (Map.Entry<?, ?> e : m.entrySet()) {
        JsonNode value = nested(e.getValue(), depth + 1);
        if (value == null) {
          return null;
        }
        out.set((String) e.getKey(), value);
      }
      return out;
    }
    ArrayNode out = NODES.arrayNode();
    for (Map.Entry<?, ?> e : m.entrySet()) {
      JsonNode key = nested(e.getKey(), depth + 1);
      JsonNode value = nested(e.getValue(), depth + 1);
      if (key == null || value == null) {
        return null;
      }
      out.addObject().set("key", key).set("value", value);
    }
    return out;
  }

  /** An integer of any width: a JSON number within ±2^53, else its digits as text. */
  private static JsonNode integer(Object v, int maxCellChars) {
    return switch (v) {
      case Byte x -> NODES.numberNode(x.longValue());
      case Short x -> NODES.numberNode(x.longValue());
      case Integer x -> NODES.numberNode(x.longValue());
      case Long x -> Math.abs(x) <= MAX_SAFE_INTEGER ? NODES.numberNode(x) : NODES.stringNode(x.toString());
      case BigInteger x -> x.abs().compareTo(BigInteger.valueOf(MAX_SAFE_INTEGER)) <= 0
          ? NODES.numberNode(x.longValueExact()) : NODES.stringNode(x.toString());
      case BigDecimal x -> NODES.stringNode(x.toPlainString());
      default -> text(String.valueOf(v), maxCellChars);
    };
  }

  /** {@code \x0a1b…}, the first 256 bytes, then how many there were. */
  static String hex(byte[] bytes) {
    int shown = Math.min(bytes.length, MAX_BYTES_SHOWN);
    String hex = "\\x" + HexFormat.of().formatHex(bytes, 0, shown);
    return bytes.length > shown ? hex + "…‹" + bytes.length + " bytes›" : hex;
  }

  /** A cell cut to {@code max} characters, never inside a surrogate pair, with how much was left out. */
  static JsonNode text(String v, int max) {
    if (v.length() <= max) {
      return NODES.stringNode(v);
    }
    int cut = Math.max(0, max);
    if (cut > 0 && Character.isHighSurrogate(v.charAt(cut - 1))) {
      cut--;
    }
    return NODES.stringNode(v.substring(0, cut) + "…‹truncated " + String.format(Locale.US, "%,d", v.length() - cut)
        + " chars›");
  }
}
