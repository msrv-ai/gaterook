package gaterook.plugins.h2;

import ai.msrv.gaterook.connector.api.ToolResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Rows of an H2 result as the agent gets them, with the limits of GateRook's other SQL connectors: JSON numbers where
 * they stay exact (integers within ±2^53, finite floats), strings for decimals and bigger integers, ISO-8601 for dates
 * and times (with the offset where the type has one), {@code \x…} hex for binary values (their first 256 bytes),
 * arrays as JSON arrays and a {@code JSON} value as JSON, H2's own text for every other type. A {@code JAVA_OBJECT}
 * value is never deserialized: it shows as its bytes. A cell longer than {@code maxCellChars} is cut and says how much
 * was left out. Reading stops after {@code maxRows} rows (one more is read to know there were more) or when the rows
 * outgrow {@code maxBytes}.
 */
final class H2Results {

  static final long MAX_SAFE_INTEGER = 9_007_199_254_740_992L;
  static final int MAX_BYTES_SHOWN = 256;
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * What was read: the table and why it stopped early, if it did.
   *
   * @param total the rows of the result: those read, plus the rest when they were counted
   */
  record Read(ToolResult.Table table, String truncatedReason, long total) {}

  private H2Results() {}

  static Read read(ResultSet rs, int maxRows, long maxBytes, int maxCellChars, boolean countRest)
      throws SQLException {
    ResultSetMetaData md = rs.getMetaData();
    int n = md.getColumnCount();
    List<ToolResult.Column> columns = new ArrayList<>(n);
    int[] types = new int[n];
    String[] names = new String[n];
    for (int i = 1; i <= n; i++) {
      types[i - 1] = md.getColumnType(i);
      names[i - 1] = md.getColumnTypeName(i) == null ? "" : md.getColumnTypeName(i);
      columns.add(new ToolResult.Column(md.getColumnLabel(i), names[i - 1], null, null, null));
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
        JsonNode cell = cell(rs, i, types[i - 1], names[i - 1], maxCellChars);
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

  /** One value by its JDBC type, and by H2's type name where JDBC says only OTHER. */
  static JsonNode cell(ResultSet rs, int i, int type, String typeName, int maxCellChars) throws SQLException {
    String name = typeName.toUpperCase(Locale.ROOT);
    if (name.equals("UUID")) {
      // H2 says BINARY for a UUID: its text is what a person reads.
      String v = rs.getString(i);
      return v == null ? NODES.nullNode() : NODES.stringNode(v);
    }
    switch (type) {
      case Types.BOOLEAN, Types.BIT -> {
        boolean v = rs.getBoolean(i);
        return rs.wasNull() ? NODES.nullNode() : NODES.booleanNode(v);
      }
      case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> {
        long v = rs.getLong(i);
        if (rs.wasNull()) {
          return NODES.nullNode();
        }
        return Math.abs(v) <= MAX_SAFE_INTEGER ? NODES.numberNode(v) : NODES.stringNode(Long.toString(v));
      }
      case Types.DECIMAL, Types.NUMERIC -> {
        String text = rs.getString(i);
        if (text == null) {
          return NODES.nullNode();
        }
        try {
          return NODES.stringNode(new BigDecimal(text).toPlainString());
        } catch (NumberFormatException e) {
          // DECFLOAT's Infinity and NaN.
          return NODES.stringNode(text);
        }
      }
      case Types.REAL, Types.FLOAT, Types.DOUBLE -> {
        double v = rs.getDouble(i);
        if (rs.wasNull()) {
          return NODES.nullNode();
        }
        return Double.isFinite(v) ? NODES.numberNode(v) : NODES.stringNode(Double.toString(v));
      }
      case Types.DATE -> {
        LocalDate v = rs.getObject(i, LocalDate.class);
        return v == null ? NODES.nullNode() : NODES.stringNode(v.toString());
      }
      case Types.TIME -> {
        LocalTime v = rs.getObject(i, LocalTime.class);
        return v == null ? NODES.nullNode() : NODES.stringNode(v.toString());
      }
      case Types.TIME_WITH_TIMEZONE -> {
        OffsetTime v = rs.getObject(i, OffsetTime.class);
        return v == null ? NODES.nullNode() : NODES.stringNode(v.toString());
      }
      case Types.TIMESTAMP -> {
        LocalDateTime v = rs.getObject(i, LocalDateTime.class);
        return v == null ? NODES.nullNode() : NODES.stringNode(v.toString());
      }
      case Types.TIMESTAMP_WITH_TIMEZONE -> {
        OffsetDateTime v = rs.getObject(i, OffsetDateTime.class);
        return v == null ? NODES.nullNode() : NODES.stringNode(v.toString());
      }
      case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> {
        byte[] v = rs.getBytes(i);
        return v == null ? NODES.nullNode() : hex(v, v.length);
      }
      case Types.BLOB -> {
        Blob b = rs.getBlob(i);
        return b == null ? NODES.nullNode() : blob(b);
      }
      case Types.CLOB, Types.NCLOB -> {
        Clob c = rs.getClob(i);
        return c == null ? NODES.nullNode() : clob(c, maxCellChars);
      }
      case Types.JAVA_OBJECT -> {
        // Never deserialized: getObject would run the class's readObject inside the plugin.
        byte[] v = rs.getBytes(i);
        return v == null ? NODES.nullNode() : hex(v, v.length);
      }
      case Types.ARRAY -> {
        return array(rs, i, name, maxCellChars);
      }
      default -> {
        String text = rs.getString(i);
        if (text == null) {
          return NODES.nullNode();
        }
        if (name.equals("JSON") && text.length() <= maxCellChars) {
          try {
            return JSON.readTree(text);
          } catch (RuntimeException e) {
            return text(text, maxCellChars);
          }
        }
        return text(text, maxCellChars);
      }
    }
  }

  /**
   * An array as a JSON array of its elements' text or numbers. Elements of a type that could hold Java objects are
   * shown as H2's text of the whole array instead, so nothing is deserialized.
   */
  private static JsonNode array(ResultSet rs, int i, String typeName, int maxCellChars) throws SQLException {
    if (typeName.contains("JAVA_OBJECT") || typeName.contains("OTHER") || typeName.contains("ROW")) {
      String text = rs.getString(i);
      return text == null ? NODES.nullNode() : text(text, maxCellChars);
    }
    Array a = rs.getArray(i);
    if (a == null) {
      return NODES.nullNode();
    }
    try {
      Object v = a.getArray();
      return v instanceof Object[] elements ? elements(elements, maxCellChars, 0) : text(String.valueOf(v),
          maxCellChars);
    } finally {
      a.free();
    }
  }

  private static JsonNode elements(Object[] elements, int maxCellChars, int depth) {
    ArrayNode out = NODES.arrayNode();
    for (Object e : elements) {
      if (e == null) {
        out.addNull();
      } else if (e instanceof Boolean b) {
        out.add(b);
      } else if (e instanceof Integer || e instanceof Short || e instanceof Byte || e instanceof Long) {
        long v = ((Number) e).longValue();
        if (Math.abs(v) <= MAX_SAFE_INTEGER) {
          out.add(v);
        } else {
          out.add(Long.toString(v));
        }
      } else if (e instanceof Double || e instanceof Float) {
        double v = ((Number) e).doubleValue();
        if (Double.isFinite(v)) {
          out.add(v);
        } else {
          out.add(Double.toString(v));
        }
      } else if (e instanceof BigDecimal d) {
        out.add(d.toPlainString());
      } else if (e instanceof byte[] b) {
        out.add(hex(b, b.length));
      } else if (e instanceof Object[] nested && depth < 8) {
        out.add(elements(nested, maxCellChars, depth + 1));
      } else {
        out.add(text(String.valueOf(e), maxCellChars));
      }
    }
    return out;
  }

  private static JsonNode blob(Blob b) throws SQLException {
    long length = b.length();
    try (InputStream in = b.getBinaryStream()) {
      byte[] head = in.readNBytes(MAX_BYTES_SHOWN);
      return hex(head, length);
    } catch (IOException e) {
      throw new SQLException("The binary value couldn't be read: " + e.getMessage(), e);
    } finally {
      b.free();
    }
  }

  private static JsonNode clob(Clob c, int maxCellChars) throws SQLException {
    long length = c.length();
    try (Reader in = c.getCharacterStream()) {
      char[] head = new char[(int) Math.min(length, maxCellChars + 1L)];
      int read = 0;
      while (read < head.length) {
        int n = in.read(head, read, head.length - read);
        if (n < 0) {
          break;
        }
        read += n;
      }
      String text = new String(head, 0, read);
      return length > maxCellChars ? NODES.stringNode(cut(text, maxCellChars) + "…‹" + length + " characters›")
          : NODES.stringNode(text);
    } catch (IOException e) {
      throw new SQLException("The text value couldn't be read: " + e.getMessage(), e);
    } finally {
      c.free();
    }
  }

  /** {@code \x…} of the first 256 bytes, and the length when there are more. */
  static JsonNode hex(byte[] bytes, long length) {
    int shown = (int) Math.min(Math.min(bytes.length, length), MAX_BYTES_SHOWN);
    String hex = "\\x" + HexFormat.of().formatHex(bytes, 0, shown);
    return NODES.stringNode(length > shown ? hex + "…‹" + length + " bytes›" : hex);
  }

  static JsonNode text(String v, int max) {
    if (v.length() <= max) {
      return NODES.stringNode(v);
    }
    return NODES.stringNode(cut(v, max) + "…‹" + v.length() + " characters›");
  }

  private static String cut(String v, int max) {
    int end = Math.min(v.length(), max);
    if (end > 0 && end < v.length() && Character.isHighSurrogate(v.charAt(end - 1))) {
      end--;
    }
    return v.substring(0, end);
  }
}
