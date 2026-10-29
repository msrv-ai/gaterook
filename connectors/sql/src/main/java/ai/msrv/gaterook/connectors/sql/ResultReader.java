package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connector.api.ToolResult;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Rows of a result set as the agent gets them (report 04 §3.3): JSON numbers where they stay exact (integers within
 * ±2^53, floats), strings for decimals and big integers, ISO-8601 for times, JSON arrays for arrays, {@code \x…} hex
 * for bytes. Cells longer than {@code maxCellChars} are cut. Reading stops after {@code maxRows} rows (one more is read
 * to know there were more) or when the rows outgrow {@code maxBytes}. The statement is not cancelled: the row limit
 * already bounds what the server sends ({@code setMaxRows}: a portal limit on PostgreSQL, {@code sql_select_limit} on
 * MySQL), closing the result set ends the rest, and a cancel could hit the transaction's next statement instead.
 *
 * <p>Each column carries its origin ({@code schema.table.column}) where the driver knows it, so the core can mask
 * known-sensitive columns by name; an expression ({@code lower(email)}) has none, and the core's detectors apply.
 */
final class ResultReader {

  static final long MAX_SAFE_INTEGER = 9_007_199_254_740_992L;
  static final int MAX_BYTES_SHOWN = 256;

  /**
   * What was read: the table and why it stopped early, if it did.
   *
   * @param total the rows of the result: those read, plus the rest when they were counted
   */
  record Read(ToolResult.Table table, String truncatedReason, long total) {}

  private ResultReader() {}

  /**
   * @param countRest go on counting the rows after the limit without keeping them (RETURNING of a change, whose
   *                  statements must all run to the end)
   */
  static Read read(ResultSet rs, int maxRows, long maxBytes, int maxCellChars, boolean mysql, boolean countRest)
      throws SQLException {
    return read(rs, maxRows, maxBytes, maxCellChars, mysql ? new MySqlDialect() : new PostgresDialect(), countRest);
  }

  static Read read(ResultSet rs, int maxRows, long maxBytes, int maxCellChars, SqlDialect dialect, boolean countRest)
      throws SQLException {
    boolean mysql = dialect.mysql();
    ResultSetMetaData md = rs.getMetaData();
    int n = md.getColumnCount();
    List<ToolResult.Column> columns = new ArrayList<>(n);
    int[] types = new int[n];
    String[] typeNames = new String[n];
    for (int i = 1; i <= n; i++) {
      types[i - 1] = md.getColumnType(i);
      typeNames[i - 1] = md.getColumnTypeName(i) == null ? "" : md.getColumnTypeName(i);
      columns.add(new ToolResult.Column(md.getColumnLabel(i), typeNames[i - 1], origin(md, i, Part.SCHEMA, dialect),
          origin(md, i, Part.TABLE, dialect), origin(md, i, Part.COLUMN, dialect)));
    }
    ArrayNode rows = JsonNodeFactory.instance.arrayNode();
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
        JsonNode cell = cell(rs, i, types[i - 1], typeNames[i - 1], maxCellChars, mysql);
        row.add(cell);
        bytes += cell.isString() ? cell.asString().length() + 2L : 8L;
      }
      if (bytes > maxBytes) {
        truncated = "size_limit";
        break;
      }
    }
    if (truncated != null && countRest) {
      while (rs.next()) {
        total++;
      }
    }
    return new Read(new ToolResult.Table(List.copyOf(columns), rows, null), truncated, total);
  }

  private enum Part { SCHEMA, TABLE, COLUMN }

  /** pgjdbc: base schema, table and column (an alias is followed); Connector/J: database, original table and column. */
  private static String origin(ResultSetMetaData md, int i, Part part, SqlDialect dialect) {
    try {
      String v;
      if (dialect.mysql() || dialect.sqlServer()) {
        v = switch (part) {
          case SCHEMA -> dialect.mysql() ? md.getCatalogName(i) : md.getSchemaName(i);
          case TABLE -> md.getTableName(i);
          case COLUMN -> md.getColumnName(i);
        };
      } else {
        org.postgresql.PGResultSetMetaData pg = md.unwrap(org.postgresql.PGResultSetMetaData.class);
        v = switch (part) {
          case SCHEMA -> pg.getBaseSchemaName(i);
          case TABLE -> pg.getBaseTableName(i);
          case COLUMN -> pg.getBaseColumnName(i);
        };
      }
      return v == null || v.isEmpty() ? null : v;
    } catch (SQLException | RuntimeException e) {
      return null;
    }
  }

  /** A typed value, or the server's text where the type's own getter can't say it (NaN numerics, money). */
  static JsonNode cell(ResultSet rs, int i, int type, String typeName, int maxCellChars, boolean mysql)
      throws SQLException {
    try {
      return typed(rs, i, type, typeName.toLowerCase(Locale.ROOT), maxCellChars, mysql);
    } catch (SQLException | ArithmeticException | IllegalArgumentException e) {
      String v = rs.getString(i);
      return v == null ? JsonNodeFactory.instance.nullNode() : text(v, maxCellChars);
    }
  }

  private static JsonNode typed(ResultSet rs, int i, int type, String name, int maxCellChars, boolean mysql)
      throws SQLException {
    JsonNodeFactory f = JsonNodeFactory.instance;
    if (name.equals("money")) {
      // pgjdbc says DOUBLE for money, whose text has a currency sign.
      String v = rs.getString(i);
      return v == null ? f.nullNode() : f.stringNode(v);
    }
    if (name.equals("datetimeoffset")) {
      OffsetDateTime value = rs.getObject(i, OffsetDateTime.class);
      return value == null ? f.nullNode() : text(value.toString(), maxCellChars);
    }
    switch (type) {
      case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> {
        long v = rs.getLong(i);
        return rs.wasNull() ? f.nullNode() : f.numberNode(v);
      }
      case Types.BIGINT -> {
        BigDecimal v = rs.getBigDecimal(i);
        if (v == null) {
          return f.nullNode();
        }
        return v.abs().compareTo(BigDecimal.valueOf(MAX_SAFE_INTEGER)) <= 0 ? f.numberNode(v.longValueExact())
            : f.stringNode(v.toPlainString());
      }
      case Types.NUMERIC, Types.DECIMAL -> {
        BigDecimal v = rs.getBigDecimal(i);
        return v == null ? f.nullNode() : f.stringNode(v.toPlainString());
      }
      case Types.REAL, Types.FLOAT, Types.DOUBLE -> {
        double v = rs.getDouble(i);
        if (rs.wasNull()) {
          return f.nullNode();
        }
        return Double.isFinite(v) ? f.numberNode(v) : f.stringNode(Double.toString(v));
      }
      case Types.BOOLEAN -> {
        boolean v = rs.getBoolean(i);
        return rs.wasNull() ? f.nullNode() : f.booleanNode(v);
      }
      case Types.BIT -> {
        Object v = rs.getObject(i);
        return switch (v) {
          case null -> f.nullNode();
          case Boolean b -> f.booleanNode(b);
          case byte[] b -> f.stringNode(hex(b));
          default -> text(String.valueOf(v), maxCellChars);
        };
      }
      case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> {
        byte[] v = rs.getBytes(i);
        return v == null ? f.nullNode() : f.stringNode(hex(v));
      }
      case Types.ARRAY -> {
        java.sql.Array a = rs.getArray(i);
        if (a == null) {
          return f.nullNode();
        }
        JsonNode node = array(a.getArray(), maxCellChars);
        return node.toString().length() <= maxCellChars ? node : text(node.toString(), maxCellChars);
      }
      case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> {
        return timestamp(rs, i, name, maxCellChars, mysql);
      }
      default -> {
        String v = rs.getString(i);
        return v == null ? f.nullNode() : text(v, maxCellChars);
      }
    }
  }

  /** PostgreSQL: ISO-8601 from java.time (an offset for timestamptz); MySQL: the server's text with a {@code T}. */
  private static JsonNode timestamp(ResultSet rs, int i, String typeName, int maxCellChars, boolean mysql)
      throws SQLException {
    JsonNodeFactory f = JsonNodeFactory.instance;
    if (!mysql) {
      // MySQL's text is already the server's time: converting it would apply the JVM's zone.
      try {
        if (typeName.equals("timestamptz")) {
          OffsetDateTime v = rs.getObject(i, OffsetDateTime.class);
          if (v != null && !v.equals(OffsetDateTime.MAX) && !v.equals(OffsetDateTime.MIN)) {
            return f.stringNode(v.toString());
          }
        } else if (typeName.equals("timestamp")) {
          LocalDateTime v = rs.getObject(i, LocalDateTime.class);
          if (v != null && !v.equals(LocalDateTime.MAX) && !v.equals(LocalDateTime.MIN)) {
            return f.stringNode(v.toString());
          }
        }
      } catch (SQLException | RuntimeException e) {
        // infinity, or a value java.time can't hold: the server's own text below.
      }
    }
    String text = rs.getString(i);
    if (text == null) {
      return f.nullNode();
    }
    if (text.length() >= 19 && text.charAt(10) == ' ' && Character.isDigit(text.charAt(0))) {
      text = text.substring(0, 10) + 'T' + text.substring(11);
    }
    return text(text, maxCellChars);
  }

  private static JsonNode array(Object values, int maxCellChars) {
    JsonNodeFactory f = JsonNodeFactory.instance;
    ArrayNode out = f.arrayNode();
    if (values == null || !values.getClass().isArray()) {
      return values == null ? f.nullNode() : f.stringNode(String.valueOf(values));
    }
    int length = Array.getLength(values);
    for (int i = 0; i < length; i++) {
      Object v = Array.get(values, i);
      switch (v) {
        case null -> out.addNull();
        case Integer x -> out.add(x);
        case Long x -> out.add(x);
        case Short x -> out.add(x.intValue());
        case Double x -> out.add(Double.isFinite(x) ? f.numberNode(x) : f.stringNode(x.toString()));
        case Float x -> out.add(Float.isFinite(x) ? f.numberNode(x.doubleValue()) : f.stringNode(x.toString()));
        case BigDecimal x -> out.add(x.toPlainString());
        case Boolean x -> out.add(x);
        case byte[] x -> out.add(hex(x));
        default -> {
          if (v.getClass().isArray()) {
            out.add(array(v, maxCellChars));
          } else {
            out.add(String.valueOf(v));
          }
        }
      }
    }
    return out;
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
      return JsonNodeFactory.instance.stringNode(v);
    }
    int cut = Character.isHighSurrogate(v.charAt(max - 1)) ? max - 1 : max;
    return JsonNodeFactory.instance.stringNode(v.substring(0, cut) + "…‹truncated "
        + String.format(Locale.US, "%,d", v.length() - cut) + " chars›");
  }
}
