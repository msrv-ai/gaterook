package ai.msrv.gaterook.connectors.snowflake;

import ai.msrv.gaterook.connector.api.ToolResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Cells of the SQL API's {@code jsonv2} answers, typed by the statement's {@code resultSetMetaData.rowType}. Snowflake
 * sends every value as a string; the agent gets a JSON number where it stays exact (FIXED integers within ±2^53,
 * finite REAL), a boolean for BOOLEAN, ISO-8601 text for DATE, TIME and the TIMESTAMP types, the exact text for
 * decimals and larger numbers, JSON text without its white space for VARIANT, OBJECT, ARRAY and the geospatial types,
 * and {@code \x…} hex of the first 256 bytes for BINARY. A text cell is cut to the call's cell size, saying how much
 * was left out.
 */
final class Values {

  static final long MAX_SAFE_INTEGER = 9_007_199_254_740_992L;
  static final int MAX_BYTES_SHOWN = 256;
  private static final BigDecimal SAFE = BigDecimal.valueOf(MAX_SAFE_INTEGER);
  private static final BigDecimal NANOS = BigDecimal.valueOf(1_000_000_000L);
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private Values() {}

  /**
   * One column of an answer.
   *
   * @param type      Snowflake's type in upper case: FIXED, REAL, TEXT, BOOLEAN, DATE, TIME, TIMESTAMP_NTZ, VARIANT…
   * @param database  where the column comes from, when Snowflake says; {@code null} for an expression
   */
  record Column(String name, String type, int precision, int scale, long length, String database, String schema,
      String table) {

    /** The type as SQL writes it: {@code NUMBER(38,0)}, {@code VARCHAR(16777216)}, {@code TIMESTAMP_TZ}. */
    String sqlType() {
      return switch (type) {
        case "FIXED" -> "NUMBER(" + precision + "," + scale + ")";
        case "TEXT" -> length > 0 ? "VARCHAR(" + length + ")" : "VARCHAR";
        case "REAL" -> "FLOAT";
        default -> type;
      };
    }

    ToolResult.Column tool() {
      return new ToolResult.Column(name, sqlType(), table == null ? null : schema, table, null);
    }
  }

  static List<Column> columns(JsonNode rowType) {
    List<Column> out = new ArrayList<>();
    for (JsonNode c : rowType) {
      out.add(new Column(c.path("name").asString(""), c.path("type").asString("TEXT").toUpperCase(Locale.ROOT),
          c.path("precision").asInt(0), c.path("scale").asInt(0), c.path("length").asLong(0),
          blankToNull(c.path("database").asString(null)), blankToNull(c.path("schema").asString(null)),
          blankToNull(c.path("table").asString(null))));
    }
    return out;
  }

  /** A cell as the agent gets it; {@code raw} is what the SQL API sent, a string or null. */
  static JsonNode cell(JsonNode raw, Column c, int maxCellChars) {
    if (raw == null || raw.isNull() || raw.isMissingNode()) {
      return F.nullNode();
    }
    String v = raw.isString() ? raw.asString() : raw.toString();
    try {
      return switch (c.type()) {
        case "FIXED" -> c.scale() == 0 ? integer(v) : F.stringNode(v);
        case "DECFLOAT" -> integer(v);
        case "REAL" -> real(v);
        case "BOOLEAN" -> bool(v);
        case "DATE" -> F.stringNode(LocalDate.ofEpochDay(Long.parseLong(v.strip())).toString());
        case "TIME" -> F.stringNode(DateTimeFormatter.ISO_LOCAL_TIME.format(LocalTime.ofNanoOfDay(nanos(v.strip()))));
        case "TIMESTAMP_NTZ" -> F.stringNode(DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(
            LocalDateTime.ofEpochSecond(seconds(v.strip()), nanoOfSecond(v.strip()), ZoneOffset.UTC)));
        case "TIMESTAMP_LTZ" -> F.stringNode(DateTimeFormatter.ISO_INSTANT.format(instant(v.strip())));
        case "TIMESTAMP_TZ" -> timestampTz(v.strip(), maxCellChars);
        case "VARIANT", "OBJECT", "ARRAY", "MAP", "GEOGRAPHY", "GEOMETRY", "VECTOR" -> text(compact(v), maxCellChars);
        case "BINARY" -> F.stringNode(hex(v));
        default -> text(v, maxCellChars);
      };
    } catch (NumberFormatException | DateTimeException | ArithmeticException e) {
      // A value the type's format doesn't read: Snowflake's own text.
      return text(v, maxCellChars);
    }
  }

  /** An integer within ±2^53 as a JSON number; anything else (larger, fractional) as its exact text. */
  static JsonNode integer(String v) {
    BigDecimal n = new BigDecimal(v.strip());
    if (n.signum() == 0) {
      return F.numberNode(0L);
    }
    BigDecimal whole = n.stripTrailingZeros();
    if (whole.scale() <= 0 && whole.abs().compareTo(SAFE) <= 0) {
      return F.numberNode(whole.longValueExact());
    }
    return F.stringNode(v);
  }

  private static JsonNode real(String v) {
    double d = Double.parseDouble(v.strip());
    return Double.isFinite(d) ? F.numberNode(d) : F.stringNode(v);
  }

  private static JsonNode bool(String v) {
    return switch (v.strip().toLowerCase(Locale.ROOT)) {
      case "true", "1" -> F.booleanNode(true);
      case "false", "0" -> F.booleanNode(false);
      default -> F.stringNode(v);
    };
  }

  /** {@code 1616173619.000000000 960}: the instant, then the offset in minutes plus 1440. */
  private static JsonNode timestampTz(String v, int maxCellChars) {
    int space = v.indexOf(' ');
    if (space < 0) {
      return text(v, maxCellChars);
    }
    Instant at = instant(v.substring(0, space));
    int offset = Integer.parseInt(v.substring(space + 1).strip()) - 1440;
    return F.stringNode(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
        OffsetDateTime.ofInstant(at, ZoneOffset.ofTotalSeconds(offset * 60))));
  }

  private static Instant instant(String v) {
    return Instant.ofEpochSecond(seconds(v), nanoOfSecond(v));
  }

  /** Whole seconds of {@code 82919.000000000}, rounded down. */
  private static long seconds(String v) {
    return new BigDecimal(v).setScale(0, RoundingMode.FLOOR).longValueExact();
  }

  private static int nanoOfSecond(String v) {
    BigDecimal n = new BigDecimal(v);
    return n.subtract(n.setScale(0, RoundingMode.FLOOR)).multiply(NANOS).intValue();
  }

  private static long nanos(String v) {
    return new BigDecimal(v).multiply(NANOS).setScale(0, RoundingMode.FLOOR).longValueExact();
  }

  /** JSON text without white space outside its strings: the same value in fewer bytes; other text as it is. */
  static String compact(String json) {
    String t = json.strip();
    if (t.isEmpty() || (t.charAt(0) != '{' && t.charAt(0) != '[')) {
      return t;
    }
    StringBuilder out = new StringBuilder(t.length());
    boolean string = false;
    boolean escaped = false;
    for (int i = 0; i < t.length(); i++) {
      char c = t.charAt(i);
      if (string) {
        out.append(c);
        if (escaped) {
          escaped = false;
        } else if (c == '\\') {
          escaped = true;
        } else if (c == '"') {
          string = false;
        }
        continue;
      }
      if (c == '"') {
        string = true;
        out.append(c);
      } else if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
        out.append(c);
      }
    }
    return out.toString();
  }

  /** {@code \x0a1b…}: the first 256 bytes of a hex value, then how many there were. */
  static String hex(String v) {
    String h = v.strip().toLowerCase(Locale.ROOT);
    if (!h.matches("[0-9a-f]*") || h.length() % 2 != 0) {
      throw new NumberFormatException("not hex");
    }
    long bytes = h.length() / 2;
    if (bytes <= MAX_BYTES_SHOWN) {
      return "\\x" + h;
    }
    return "\\x" + h.substring(0, MAX_BYTES_SHOWN * 2) + "…‹" + bytes + " bytes›";
  }

  /** A cell cut to {@code max} characters, never inside a surrogate pair, with how much was left out. */
  static JsonNode text(String v, int max) {
    if (v.length() <= max) {
      return F.stringNode(v);
    }
    int cut = Math.max(0, max);
    if (cut > 0 && Character.isHighSurrogate(v.charAt(cut - 1))) {
      cut--;
    }
    return F.stringNode(v.substring(0, cut) + "…‹truncated " + String.format(Locale.US, "%,d", v.length() - cut)
        + " chars›");
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
