package ai.msrv.gaterook.connectors.athena;

import ai.msrv.gaterook.connector.api.ToolResult;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Athena's values ({@code VarCharValue} of {@code GetQueryResults}, always text) as typed table cells by each column's
 * {@code Type}: an integer is a JSON number only where JavaScript keeps it exact, a decimal stays text, a double is a
 * number while it is finite, a timestamp is ISO-8601 ({@code Z} or its offset when it has a zone), binary values show
 * their first bytes in hex. Arrays, maps and rows arrive as Athena's own text ({@code [1, 2]}, {@code {a=1}}), which
 * no JSON reader can take back with certainty: they stay that text. Text is cut to the cell size.
 */
final class Cells {

  static final long MAX_SAFE_INTEGER = 9_007_199_254_740_992L;
  static final int MAX_BYTES_SHOWN = 256;
  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  private static final Pattern ZONED = Pattern.compile("(-?\\d{4,}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}:\\d{2})(\\.\\d+)? (.+)");
  private static final Pattern LOCAL = Pattern.compile("-?\\d{4,}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(\\.\\d+)?");
  private static final Pattern OFFSET = Pattern.compile("[+-]\\d{2}:\\d{2}");
  private static final Pattern HEX = Pattern.compile("([0-9a-fA-F]{2})( [0-9a-fA-F]{2})*");

  private Cells() {}

  /** The columns of a result: name and Athena's type. */
  static List<ToolResult.Column> columns(JsonNode columnInfo) {
    List<ToolResult.Column> out = new ArrayList<>();
    for (JsonNode c : columnInfo) {
      out.add(new ToolResult.Column(c.path("Name").asString(c.path("Label").asString("")), c.path("Type")
          .asString(""), null, null, null));
    }
    return out;
  }

  /** One row ({@code {"Data": [{"VarCharValue": …}, {}]}}) as an array of cells; a missing value is NULL. */
  static ArrayNode row(JsonNode row, JsonNode columnInfo, int maxCellChars) {
    ArrayNode out = F.arrayNode();
    JsonNode data = row.path("Data");
    int n = Math.max(data.size(), columnInfo.size());
    for (int i = 0; i < n; i++) {
      JsonNode cell = data.path(i).path("VarCharValue");
      String type = columnInfo.path(i).path("Type").asString("");
      out.add(cell.isString() ? value(cell.asString(), type, maxCellChars) : F.nullNode());
    }
    return out;
  }

  static JsonNode value(String v, String type, int maxCellChars) {
    String t = type.toLowerCase(Locale.ROOT);
    int paren = t.indexOf('(');
    String base = (paren < 0 ? t : t.substring(0, paren)).strip();
    switch (base) {
      case "bigint", "integer", "int", "smallint", "tinyint" -> {
        try {
          BigInteger n = new BigInteger(v.strip());
          return n.abs().compareTo(BigInteger.valueOf(MAX_SAFE_INTEGER)) <= 0 ? F.numberNode(n.longValue())
              : F.stringNode(v);
        } catch (NumberFormatException e) {
          return text(v, maxCellChars);
        }
      }
      case "double", "float", "real" -> {
        try {
          double d = Double.parseDouble(v.strip());
          return Double.isFinite(d) ? F.numberNode(d) : F.stringNode(v);
        } catch (NumberFormatException e) {
          return F.stringNode(v);
        }
      }
      case "boolean" -> {
        return v.equalsIgnoreCase("true") ? F.booleanNode(true) : v.equalsIgnoreCase("false") ? F.booleanNode(false)
            : text(v, maxCellChars);
      }
      case "timestamp" -> {
        return F.stringNode(t.contains("with time zone") ? zoned(v) : local(v));
      }
      case "varbinary" -> {
        return bytes(v, maxCellChars);
      }
      default -> {
        return text(v, maxCellChars);
      }
    }
  }

  static String local(String text) {
    return LOCAL.matcher(text).matches() ? text.replaceFirst(" ", "T") : text;
  }

  /** {@code 2026-01-02 03:04:05.123 UTC} as {@code 2026-01-02T03:04:05.123Z}; a region's offset at that time. */
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
      return head + offset;
    } catch (DateTimeException e) {
      return text;
    }
  }

  /** Athena's hex bytes ({@code 00 ff 10}) as a preview: {@code \x00ff10}, longer values with their length. */
  static JsonNode bytes(String v, int maxCellChars) {
    String s = v.strip();
    if (!HEX.matcher(s).matches()) {
      return text(v, maxCellChars);
    }
    byte[] b = HexFormat.of().parseHex(s.replace(" ", ""));
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
