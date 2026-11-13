package com.gaterook.connectors.influxdb;

import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.ToolResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The rows of a read as they arrive: InfluxDB 3's JSON Lines (one object per row) or the chunks of InfluxDB 1.x and
 * 2.x ({@code {"results": [{"series": [{"name", "tags", "columns", "values"}]}]}} per line), read until the row limit,
 * the response budget or the deadline, then the request ends. Cells keep their types: an integer is a number only
 * where JavaScript keeps it exact, otherwise its digits as text; the {@code time} column is ISO 8601 in UTC; strings
 * are cut to the cell size.
 */
final class Results {

  static final BigInteger MAX_SAFE_INTEGER = BigInteger.valueOf(9_007_199_254_740_991L);
  static final int MAX_LINE = 8 * 1024 * 1024;
  /** How Arrow writes a timestamp without a time zone: InfluxDB 3's {@code time} column. */
  private static final Pattern ZONELESS = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?");
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private Results() {}

  /** One row: the series it belongs to (1.x and 2.x), its tags, its cells by column. */
  private record Row(String series, Map<String, String> tags, Map<String, JsonNode> cells) {}

  /** The rows of one read, within its limits. */
  static final class Rows {
    private final int limit;
    private final long budget;
    private final int maxCellChars;
    private final ExecContext ctx;
    private final Set<String> columns = new LinkedHashSet<>();
    private final Set<String> tagKeys = new LinkedHashSet<>();
    private final Set<String> series = new LinkedHashSet<>();
    private final Map<String, String> types = new HashMap<>();
    private final List<Row> rows = new ArrayList<>();
    private long bytes;
    private String truncated;

    Rows(int limit, long budget, int maxCellChars, ExecContext ctx) {
      this.limit = limit;
      this.budget = budget;
      this.maxCellChars = Math.max(16, maxCellChars);
      this.ctx = ctx;
    }

    /** Adds one row of the server's cells; {@code false}: the read is full and stops. */
    boolean add(String seriesName, JsonNode tags, Map<String, JsonNode> raw) {
      if (rows.size() >= limit) {
        truncated = "row_limit";
        return false;
      }
      Map<String, String> tagValues = new LinkedHashMap<>();
      long size = 2;
      if (tags != null && tags.isObject()) {
        for (Map.Entry<String, JsonNode> t : tags.properties()) {
          String value = t.getValue().isNull() ? "" : t.getValue().asString("");
          tagValues.put(t.getKey(), value);
          size += t.getKey().length() + value.length() + 6;
        }
      }
      Map<String, JsonNode> cells = new LinkedHashMap<>();
      for (Map.Entry<String, JsonNode> e : raw.entrySet()) {
        String column = e.getKey();
        JsonNode value = e.getValue();
        JsonNode cell = cell(value, maxCellChars, column.equals("time"));
        String kind = kind(value, column);
        if (kind != null) {
          types.putIfAbsent(column, kind);
        }
        cells.put(column, cell);
        size += column.length() + cell.toString().length() + 4;
      }
      if (!rows.isEmpty() && bytes + size > budget) {
        truncated = "size_limit";
        return false;
      }
      bytes += size;
      columns.addAll(cells.keySet());
      tagKeys.addAll(tagValues.keySet());
      if (seriesName != null) {
        series.add(seriesName);
      }
      rows.add(new Row(seriesName, tagValues, cells));
      if (ctx.remaining().compareTo(Duration.ofMillis(500)) < 0) {
        truncated = "timeout";
        return false;
      }
      return true;
    }

    int size() {
      return rows.size();
    }

    String truncated() {
      return truncated;
    }

    /** The cells of {@code column} in row order. */
    List<JsonNode> column(String column) {
      List<JsonNode> out = new ArrayList<>();
      for (Row r : rows) {
        out.add(r.cells().getOrDefault(column, F.nullNode()));
      }
      return out;
    }

    /** Keeps only the rows whose {@code column} passes {@code keep}. */
    void retain(String column, java.util.function.Predicate<String> keep) {
      rows.removeIf(r -> !keep.test(r.cells().getOrDefault(column, F.nullNode()).asString("")));
    }

    boolean hasColumn(String column) {
      return columns.contains(column);
    }

    /**
     * The table: for the rows of several series or of tagged series (1.x and 2.x), the series' name first as
     * {@code measurement}, then the tags, then the columns in the order they came.
     */
    ToolResult.Table table() {
      boolean named = series.size() > 1 || !tagKeys.isEmpty();
      String nameColumn = columns.contains("measurement") ? "_measurement" : "measurement";
      List<ToolResult.Column> out = new ArrayList<>();
      if (named) {
        out.add(new ToolResult.Column(nameColumn, "string", null, null, null));
      }
      for (String t : tagKeys) {
        if (!columns.contains(t)) {
          out.add(new ToolResult.Column(t, "tag", null, null, null));
        }
      }
      for (String c : columns) {
        out.add(new ToolResult.Column(c, types.getOrDefault(c, "unknown"), null, null, null));
      }
      ArrayNode values = F.arrayNode();
      for (Row r : rows) {
        ArrayNode cells = values.addArray();
        if (named) {
          cells.add(r.series() == null ? F.nullNode() : F.stringNode(r.series()));
        }
        for (String t : tagKeys) {
          if (!columns.contains(t)) {
            String v = r.tags().get(t);
            cells.add(v == null ? F.nullNode() : text(v, maxCellChars));
          }
        }
        for (String c : columns) {
          cells.add(r.cells().getOrDefault(c, F.nullNode()));
        }
      }
      return new ToolResult.Table(List.copyOf(out), values, null);
    }
  }

  /** InfluxDB 3's JSON Lines: one object per row, its keys the columns; a cell without a value is left out. */
  static Rows jsonLines(InputStream in, Rows rows, String what) throws IOException, ConnectorException {
    LineReader lines = new LineReader(in, what);
    String line;
    while ((line = lines.next()) != null) {
      if (line.isBlank()) {
        continue;
      }
      JsonNode n = parse(line, what);
      if (!n.isObject()) {
        throw ConnectorException.resourceError(what + " answered a row that isn't an object.", null);
      }
      Map<String, JsonNode> raw = new LinkedHashMap<>();
      for (Map.Entry<String, JsonNode> e : n.properties()) {
        raw.put(e.getKey(), e.getValue());
      }
      if (!rows.add(null, null, raw)) {
        break;
      }
    }
    return rows;
  }

  /** The chunks of InfluxDB 1.x and 2.x's {@code /query?chunked=true}: one JSON object per line. */
  static Rows chunks(InputStream in, Rows rows, String what) throws IOException, ConnectorException {
    LineReader lines = new LineReader(in, what);
    String line;
    while ((line = lines.next()) != null) {
      if (line.isBlank()) {
        continue;
      }
      JsonNode n = parse(line, what);
      if (n.path("error").isString()) {
        throw statementError(n.get("error").asString(), what);
      }
      for (JsonNode result : n.path("results")) {
        if (result.path("error").isString()) {
          throw statementError(result.get("error").asString(), what);
        }
        for (JsonNode s : result.path("series")) {
          String name = s.path("name").isString() ? s.get("name").asString() : null;
          List<String> columns = new ArrayList<>();
          s.path("columns").forEach(c -> columns.add(c.asString("")));
          for (JsonNode values : s.path("values")) {
            Map<String, JsonNode> raw = new LinkedHashMap<>();
            for (int k = 0; k < columns.size(); k++) {
              raw.put(columns.get(k), values.path(k));
            }
            if (!rows.add(name, s.path("tags"), raw)) {
              return rows;
            }
          }
        }
      }
    }
    return rows;
  }

  /** A statement InfluxDB 1.x or 2.x refused inside a 200 answer. */
  static ConnectorException statementError(String error, String what) {
    String said = Errors.message(error.getBytes(StandardCharsets.UTF_8));
    String lower = said.toLowerCase(java.util.Locale.ROOT);
    if (lower.contains("not authorized") || lower.contains("authoriz")) {
      return ConnectorException.resourceError("The credentials may not run this: " + said + ".", null);
    }
    if (lower.contains("database not found")) {
      return ConnectorException.badInput(said + ". Call influxdb_list_databases to see the databases.");
    }
    return ConnectorException.badInput(what + " refused the query: " + said + ".");
  }

  private static JsonNode parse(String line, String what) throws ConnectorException {
    try {
      return InfluxHttp.JSON.readTree(line);
    } catch (JacksonException e) {
      throw ConnectorException.resourceError(what + " answered something that isn't JSON: "
          + Errors.excerpt(line.getBytes(StandardCharsets.UTF_8)), null);
    }
  }

  /** One cell as the agent sees it. */
  static JsonNode cell(JsonNode v, int maxChars, boolean time) {
    if (v == null || v.isNull() || v.isMissingNode()) {
      return F.nullNode();
    }
    if (v.isIntegralNumber()) {
      BigInteger b = v.bigIntegerValue();
      return b.abs().compareTo(MAX_SAFE_INTEGER) <= 0 ? F.numberNode(b.longValue()) : F.stringNode(b.toString());
    }
    if (v.isNumber()) {
      double d = v.doubleValue();
      return Double.isFinite(d) ? F.numberNode(d) : F.stringNode(String.valueOf(d));
    }
    if (v.isBoolean()) {
      return F.booleanNode(v.asBoolean());
    }
    if (v.isString()) {
      String s = v.asString();
      return text(time ? utc(s) : s, maxChars);
    }
    return text(v.toString(), maxChars);
  }

  /** The type of a column from its first value: timestamp, integer, float, boolean or string. */
  private static String kind(JsonNode v, String column) {
    if (column.equals("time")) {
      return "timestamp";
    }
    if (v == null || v.isNull() || v.isMissingNode()) {
      return null;
    }
    if (v.isIntegralNumber()) {
      return "integer";
    }
    if (v.isNumber()) {
      return "float";
    }
    if (v.isBoolean()) {
      return "boolean";
    }
    return "string";
  }

  /** InfluxDB 3 writes its timestamps without a zone; they are UTC. */
  static String utc(String s) {
    return ZONELESS.matcher(s).matches() ? s + "Z" : s;
  }

  static JsonNode text(String v, int max) {
    if (v.length() <= max) {
      return F.stringNode(v);
    }
    int end = Character.isHighSurrogate(v.charAt(max - 1)) ? max - 1 : max;
    return F.stringNode(v.substring(0, end) + "…‹" + v.length() + " characters›");
  }

  /** Lines of UTF-8 from a stream, each at most {@link #MAX_LINE} bytes, all of them at most the body cap. */
  static final class LineReader {
    private final InputStream in;
    private final String what;
    private final byte[] buf = new byte[16384];
    private int pos;
    private int len;
    private long total;
    private boolean eof;

    LineReader(InputStream in, String what) {
      this.in = in;
      this.what = what;
    }

    /** The next line without its {@code \n}, or {@code null} at the end. */
    String next() throws IOException, ConnectorException {
      ByteArrayOutputStream line = new ByteArrayOutputStream(256);
      while (true) {
        if (pos == len) {
          if (eof) {
            return line.size() == 0 ? null : line.toString(StandardCharsets.UTF_8);
          }
          len = in.read(buf);
          pos = 0;
          if (len <= 0) {
            len = 0;
            eof = true;
            continue;
          }
          total += len;
          if (total > InfluxHttp.MAX_BODY) {
            throw ConnectorException.resourceError(what + " answered with more than " + (InfluxHttp.MAX_BODY >> 20)
                + " MiB.", null);
          }
        }
        int start = pos;
        while (pos < len && buf[pos] != '\n') {
          pos++;
        }
        line.write(buf, start, pos - start);
        if (line.size() > MAX_LINE) {
          throw ConnectorException.resourceError(what + " answered a row larger than " + (MAX_LINE >> 20) + " MiB.",
              null);
        }
        if (pos < len) {
          pos++;
          return line.toString(StandardCharsets.UTF_8);
        }
      }
    }
  }
}
