package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connector.api.ToolResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Clob;
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
 * <p>A long value is never built whole to be cut afterwards: a cell takes at most {@link #fieldSize} bytes of the
 * gateway's memory beyond what the driver holds of the row. PostgreSQL's text is decoded from the first bytes of the
 * row's buffer; LOBs are read through their locators; long character and binary columns through bounded streams;
 * SQL Server and Oracle cut the rest at the server ({@code setMaxFieldSize}). A cut that may have hidden the value's
 * length says so: {@code …‹truncated 24,580+ chars›}.
 *
 * <p>Each column carries its origin ({@code schema.table.column}) where the driver knows it, so the core can mask
 * known-sensitive columns by name; an expression ({@code lower(email)}) has none, and the core's detectors apply.
 */
final class ResultReader {

  static final long MAX_SAFE_INTEGER = 9_007_199_254_740_992L;
  static final int MAX_BYTES_SHOWN = 256;

  /**
   * The most bytes of one character or binary value a read takes from the server where the driver can cut there
   * ({@code Statement.setMaxFieldSize}), and the most it decodes: a cell of {@code maxCellChars} characters of four
   * UTF-8 bytes each, and one more character to know the cell was longer.
   */
  static int fieldSize(int maxCellChars) {
    return 4 * maxCellChars + 4;
  }

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
    return read(rs, maxRows, maxBytes, maxCellChars, SqlSyntax.of(mysql), countRest);
  }

  static Read read(ResultSet rs, int maxRows, long maxBytes, int maxCellChars, SqlDialect dialect, boolean countRest)
      throws SQLException {
    return read(rs, maxRows, maxBytes, maxCellChars, dialect.syntax(), countRest);
  }

  static Read read(ResultSet rs, int maxRows, long maxBytes, int maxCellChars, SqlSyntax syntax, boolean countRest)
      throws SQLException {
    ResultSetMetaData md = rs.getMetaData();
    int n = md.getColumnCount();
    List<ToolResult.Column> columns = new ArrayList<>(n);
    int[] types = new int[n];
    String[] typeNames = new String[n];
    for (int i = 1; i <= n; i++) {
      types[i - 1] = md.getColumnType(i);
      typeNames[i - 1] = md.getColumnTypeName(i) == null ? "" : md.getColumnTypeName(i);
      columns.add(new ToolResult.Column(md.getColumnLabel(i), typeNames[i - 1], origin(md, i, Part.SCHEMA, syntax),
          origin(md, i, Part.TABLE, syntax), origin(md, i, Part.COLUMN, syntax)));
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
        JsonNode cell = cell(rs, i, types[i - 1], typeNames[i - 1], maxCellChars, syntax);
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
  private static String origin(ResultSetMetaData md, int i, Part part, SqlSyntax syntax) {
    try {
      String v;
      if (syntax == SqlSyntax.SQLITE) {
        // sqlite-jdbc names the table of a column that comes from one; every table of a connection is in main.
        String table = md.getTableName(i);
        v = table == null || table.isEmpty() ? null : switch (part) {
          case SCHEMA -> "main";
          case TABLE -> table;
          case COLUMN -> md.getColumnName(i);
        };
      } else if (syntax != SqlSyntax.POSTGRES) {
        v = switch (part) {
          case SCHEMA -> syntax == SqlSyntax.MYSQL ? md.getCatalogName(i) : md.getSchemaName(i);
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
    return cell(rs, i, type, typeName, maxCellChars, SqlSyntax.of(mysql));
  }

  static JsonNode cell(ResultSet rs, int i, int type, String typeName, int maxCellChars, SqlSyntax syntax)
      throws SQLException {
    try {
      if (syntax == SqlSyntax.POSTGRES && textFormat(rs, i, type)) {
        return pgText(rs, i, maxCellChars);
      }
      if (syntax == SqlSyntax.SQLITE) {
        return sqlite(rs.getObject(i), maxCellChars);
      }
      if (syntax == SqlSyntax.ORACLE) {
        if (type == oracle.jdbc.OracleTypes.TIMESTAMPTZ || type == Types.TIMESTAMP_WITH_TIMEZONE) {
          OffsetDateTime value = rs.getObject(i, OffsetDateTime.class);
          return value == null ? JsonNodeFactory.instance.nullNode() : text(value.toString(), maxCellChars);
        }
        if (type == oracle.jdbc.OracleTypes.TIMESTAMPLTZ || type == Types.TIMESTAMP || type == Types.DATE) {
          LocalDateTime value = rs.getObject(i, LocalDateTime.class);
          return value == null ? JsonNodeFactory.instance.nullNode() : text(value.toString(), maxCellChars);
        }
      }
      return typed(rs, i, type, typeName.toLowerCase(Locale.ROOT), maxCellChars, syntax == SqlSyntax.MYSQL);
    } catch (SQLException | ArithmeticException | IllegalArgumentException e) {
      String v = rs.getString(i);
      return v == null ? JsonNodeFactory.instance.nullNode() : text(v, maxCellChars);
    }
  }

  /**
   * A SQLite value by its storage class, whatever the column declares: an integer (as text beyond JavaScript's exact
   * range), a real (non-finite as text), text, or a blob's first bytes with its length.
   */
  static JsonNode sqlite(Object value, int maxCellChars) {
    JsonNodeFactory f = JsonNodeFactory.instance;
    return switch (value) {
      case null -> f.nullNode();
      case Integer n -> f.numberNode(n.longValue());
      case Long n -> n >= -MAX_SAFE_INTEGER && n <= MAX_SAFE_INTEGER ? f.numberNode(n) : f.stringNode(n.toString());
      case Double d -> Double.isFinite(d) ? f.numberNode(d) : f.stringNode(d.toString());
      case byte[] b -> f.stringNode(hex(b));
      case String t -> text(t, maxCellChars);
      default -> text(String.valueOf(value), maxCellChars);
    };
  }

  /**
   * Whether pgjdbc holds this column as the server's text: character types and the types it has no Java class for
   * ({@code json}, {@code uuid}, {@code interval}, enums), unless it asked for the binary format of a prepared
   * statement's column.
   */
  private static boolean textFormat(ResultSet rs, int i, int type) {
    switch (type) {
      case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
          Types.CLOB, Types.NCLOB, Types.SQLXML, Types.OTHER -> {
        try {
          return rs.getMetaData().unwrap(org.postgresql.PGResultSetMetaData.class).getFormat(i) == 0;
        } catch (SQLException | RuntimeException e) {
          return false;
        }
      }
      default -> {
        return false;
      }
    }
  }

  /**
   * A PostgreSQL text cell from the row's own bytes ({@code getBytes} returns them as they are for a text column):
   * only the first {@link #fieldSize} bytes become characters, however long the value is.
   */
  private static JsonNode pgText(ResultSet rs, int i, int maxCellChars) throws SQLException {
    byte[] raw = rs.getBytes(i);
    if (raw == null) {
      return JsonNodeFactory.instance.nullNode();
    }
    int cap = fieldSize(maxCellChars);
    if (raw.length <= cap) {
      return text(new String(raw, StandardCharsets.UTF_8), maxCellChars);
    }
    int end = cap;
    // Not inside a UTF-8 sequence: back up to the last character's first byte, then leave that character out.
    while (end > 0 && (raw[end] & 0xC0) == 0x80) {
      end--;
    }
    String head = new String(raw, 0, end, StandardCharsets.UTF_8);
    return text(head, maxCellChars, head.length() + 1L, true);
  }

  /** The first {@code maxCellChars + 1} characters of a stream; the rest is read up to {@link #fieldSize} and counted. */
  private static JsonNode streamed(Reader reader, int maxCellChars) throws SQLException {
    if (reader == null) {
      return JsonNodeFactory.instance.nullNode();
    }
    int cap = fieldSize(maxCellChars);
    char[] buf = new char[maxCellChars + 1];
    try (reader) {
      int n = 0;
      while (n < buf.length) {
        int r = reader.read(buf, n, buf.length - n);
        if (r < 0) {
          break;
        }
        n += r;
      }
      if (n <= maxCellChars) {
        return JsonNodeFactory.instance.stringNode(new String(buf, 0, n));
      }
      long rest = skip(reader, cap - n);
      String v = new String(buf, 0, n);
      return rest < 0 ? text(v, maxCellChars, cap + 1L, true) : text(v, maxCellChars, n + rest, false);
    } catch (IOException e) {
      throw new SQLException(e);
    }
  }

  /** The first {@link #MAX_BYTES_SHOWN} bytes of a stream as hex; the rest is read up to {@link #fieldSize} and counted. */
  private static JsonNode streamed(InputStream in, int maxCellChars) throws SQLException {
    if (in == null) {
      return JsonNodeFactory.instance.nullNode();
    }
    try (in) {
      byte[] head = in.readNBytes(MAX_BYTES_SHOWN);
      int cap = fieldSize(maxCellChars);
      long rest = skip(in, cap - head.length);
      return JsonNodeFactory.instance.stringNode(rest < 0 ? hex(head, cap + 1L, true)
          : hex(head, head.length + rest, false));
    } catch (IOException e) {
      throw new SQLException(e);
    }
  }

  /** How much more a reader has, up to {@code limit}; -1 when it has more than that. */
  private static long skip(Reader reader, long limit) throws IOException {
    char[] scratch = new char[Math.min(8_192, Math.toIntExact(Math.max(1, limit)))];
    long skipped = 0;
    while (true) {
      int r = reader.read(scratch, 0, (int) Math.min(scratch.length, limit - skipped + 1));
      if (r < 0) {
        return skipped;
      }
      skipped += r;
      if (skipped > limit) {
        return -1;
      }
    }
  }

  private static long skip(InputStream in, long limit) throws IOException {
    byte[] scratch = new byte[Math.min(8_192, Math.toIntExact(Math.max(1, limit)))];
    long skipped = 0;
    while (true) {
      int r = in.read(scratch, 0, (int) Math.min(scratch.length, limit - skipped + 1));
      if (r < 0) {
        return skipped;
      }
      skipped += r;
      if (skipped > limit) {
        return -1;
      }
    }
  }

  /** A CLOB by its locator: its length, and only the characters that are shown. */
  private static JsonNode clob(ResultSet rs, int i, int maxCellChars) throws SQLException {
    Clob clob = rs.getClob(i);
    if (clob == null) {
      return JsonNodeFactory.instance.nullNode();
    }
    try {
      long length = clob.length();
      String head = length == 0 ? "" : clob.getSubString(1, (int) Math.min(length, maxCellChars + 1L));
      return text(head, maxCellChars, length, false);
    } finally {
      free(clob);
    }
  }

  /** A BLOB by its locator: its length, and only the bytes that are shown. */
  private static JsonNode blob(ResultSet rs, int i) throws SQLException {
    Blob blob = rs.getBlob(i);
    if (blob == null) {
      return JsonNodeFactory.instance.nullNode();
    }
    try {
      long length = blob.length();
      byte[] head = length == 0 ? new byte[0] : blob.getBytes(1, (int) Math.min(length, MAX_BYTES_SHOWN));
      return JsonNodeFactory.instance.stringNode(hex(head, length, false));
    } finally {
      free(blob);
    }
  }

  private static void free(Object lob) {
    try {
      if (lob instanceof Clob c) {
        c.free();
      } else if (lob instanceof Blob b) {
        b.free();
      }
    } catch (SQLException | RuntimeException ignored) {
      // The locator goes with the result set at the latest.
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
      case Types.BLOB -> {
        return blob(rs, i);
      }
      case Types.LONGVARBINARY -> {
        return streamed(rs.getBinaryStream(i), maxCellChars);
      }
      case Types.BINARY, Types.VARBINARY -> {
        byte[] v = rs.getBytes(i);
        return v == null ? f.nullNode() : f.stringNode(hex(v));
      }
      case Types.CLOB, Types.NCLOB -> {
        return clob(rs, i, maxCellChars);
      }
      case Types.LONGVARCHAR, Types.LONGNVARCHAR -> {
        return streamed(rs.getCharacterStream(i), maxCellChars);
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
    return hex(bytes, bytes.length, false);
  }

  /**
   * {@code \x0a1b…}: the first 256 bytes of a value {@code length} bytes long, of which {@code bytes} holds at least
   * those; {@code open} when the value wasn't read to its end, so {@code length} is a lower bound
   * ({@code …‹32,773+ bytes›}).
   */
  static String hex(byte[] bytes, long length, boolean open) {
    int shown = (int) Math.min(Math.min(bytes.length, MAX_BYTES_SHOWN), length);
    String hex = "\\x" + HexFormat.of().formatHex(bytes, 0, shown);
    return length > shown || open ? hex + "…‹" + length + (open ? "+" : "") + " bytes›" : hex;
  }

  /** A cell cut to {@code max} characters, never inside a surrogate pair, with how much was left out. */
  static JsonNode text(String v, int max) {
    return text(v, max, v.length(), false);
  }

  /**
   * A cell cut to {@code max} characters, of a value {@code length} characters long of which {@code v} is the start;
   * {@code open} when the value wasn't read to its end, so {@code length} is a lower bound and the count left out says
   * so ({@code …‹truncated 24,580+ chars›}).
   */
  static JsonNode text(String v, int max, long length, boolean open) {
    long total = Math.max(length, v.length());
    if (total <= max && !open) {
      return JsonNodeFactory.instance.stringNode(v);
    }
    int cut = Math.min(max, v.length());
    if (cut > 0 && cut < v.length() && Character.isHighSurrogate(v.charAt(cut - 1))) {
      cut--;
    }
    return JsonNodeFactory.instance.stringNode(v.substring(0, cut) + "…‹truncated "
        + String.format(Locale.US, "%,d", total - cut) + (open ? "+" : "") + " chars›");
  }
}
