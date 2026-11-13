package com.gaterook.connectors.influxdb;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Line protocol, read point by point before a person approves it: {@code measurement,tag=value field=value timestamp}.
 * Only the form every InfluxDB version reads the same way passes, so the approval shows exactly the points written:
 * one space between the parts, the escapes {@code \,} and {@code \ } in a measurement, {@code \,}, {@code \=} and
 * {@code \ } in tag keys, tag values and field keys, {@code \"} and {@code \\} in a string field; floats such as
 * {@code 1.5} or {@code 1e3}, integers {@code 1i}, unsigned integers {@code 1u} (not on 1.x), {@code "strings"},
 * booleans; a timestamp of digits within InfluxDB's time range. Anything else is refused with its line: another
 * backslash (InfluxDB 3 reads {@code \\} in a tag as one backslash, 1.x and 2.x as two), comments, blank-only lines,
 * tabs, carriage returns, names that begin with {@code _} (reserved by InfluxDB 2.x), {@code time} as a key, a key
 * twice in a point.
 */
final class LineProtocol {

  static final int MAX_BYTES = 1 << 20;
  /** InfluxDB's time range in nanoseconds: 1677-09-21T00:12:43.145224194Z to 2262-04-11T23:47:16.854775806Z. */
  static final long MIN_TIME = -9_223_372_036_854_775_806L;
  static final long MAX_TIME = 9_223_372_036_854_775_806L;
  private static final BigInteger MAX_UNSIGNED = BigInteger.TWO.pow(64).subtract(BigInteger.ONE);
  private static final Pattern INTEGER = Pattern.compile("-?\\d{1,20}i");
  private static final Pattern UNSIGNED = Pattern.compile("\\d{1,20}u");
  private static final Pattern FLOAT = Pattern.compile("-?\\d{1,400}(\\.\\d{1,400})?([eE][-+]?\\d{1,4})?");
  private static final Pattern TIMESTAMP = Pattern.compile("-?\\d{1,19}");
  private static final Set<String> TRUE = Set.of("t", "T", "true", "True", "TRUE");
  private static final Set<String> FALSE = Set.of("f", "F", "false", "False", "FALSE");

  /** The type of a field value. */
  enum Type {
    FLOAT("float"), INTEGER("integer"), UNSIGNED("unsigned"), STRING("string"), BOOLEAN("boolean");

    final String label;

    Type(String label) {
      this.label = label;
    }
  }

  /** {@code value}: the number as written without its suffix, the string's content, {@code true} or {@code false}. */
  record Field(String key, Type type, String value) {}

  /** One point; {@code timestamp} in the precision of the write, or {@code null}: the server stamps it. */
  record Point(int line, String measurement, Map<String, String> tags, List<Field> fields, Long timestamp) {}

  /**
   * The points of a write.
   *
   * @param body    the lines as they are sent: the given lines without empty ones, joined by {@code \n}
   * @param problem why a line can't be written, with its number, or {@code null}
   */
  record Batch(String body, List<Point> points, String problem) {

    boolean ok() {
      return problem == null;
    }
  }

  private LineProtocol() {}

  /** Nanoseconds per unit of {@code precision} ({@code ns}, {@code us}, {@code ms}, {@code s}). */
  static long nanos(String precision) {
    return switch (precision) {
      case "us" -> 1_000L;
      case "ms" -> 1_000_000L;
      case "s" -> 1_000_000_000L;
      default -> 1L;
    };
  }

  /** A timestamp of {@code precision} as an instant, or {@code null} outside InfluxDB's time range. */
  static Instant instant(long timestamp, String precision) {
    long unit = nanos(precision);
    long ns;
    try {
      ns = Math.multiplyExact(timestamp, unit);
    } catch (ArithmeticException e) {
      return null;
    }
    if (ns < MIN_TIME || ns > MAX_TIME) {
      return null;
    }
    return Instant.ofEpochSecond(Math.floorDiv(ns, 1_000_000_000L), Math.floorMod(ns, 1_000_000_000L));
  }

  static Batch read(String text, String precision, boolean unsignedAllowed) {
    if (text == null || text.isEmpty()) {
      return new Batch("", List.of(), "There are no lines to write.");
    }
    if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      return new Batch("", List.of(), "One write is at most 1 MiB of line protocol: split it.");
    }
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isHighSurrogate(c) && (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1)))
          || Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(text.charAt(i - 1)))) {
        return new Batch("", List.of(), "The text holds a character that isn't valid Unicode.");
      }
    }
    List<Point> points = new ArrayList<>();
    List<String> lines = new ArrayList<>();
    String[] raw = text.split("\n", -1);
    for (int n = 0; n < raw.length; n++) {
      String line = raw[n];
      if (line.isEmpty()) {
        continue;
      }
      Point p;
      try {
        p = new Reader(line, n + 1, precision, unsignedAllowed).point();
      } catch (IllegalArgumentException e) {
        return new Batch("", List.of(), "Line " + (n + 1) + ": " + e.getMessage());
      }
      points.add(p);
      lines.add(line);
    }
    if (points.isEmpty()) {
      return new Batch("", List.of(), "There are no lines to write.");
    }
    return new Batch(String.join("\n", lines), List.copyOf(points), null);
  }

  /** One line, read from left to right; a problem is an {@link IllegalArgumentException} with the reason. */
  private static final class Reader {
    private final String s;
    private final int line;
    private final String precision;
    private final boolean unsignedAllowed;
    private int i;

    Reader(String s, int line, String precision, boolean unsignedAllowed) {
      this.s = s;
      this.line = line;
      this.precision = precision;
      this.unsignedAllowed = unsignedAllowed;
    }

    Point point() {
      for (int k = 0; k < s.length(); k++) {
        char c = s.charAt(k);
        if (c == '\r') {
          throw new IllegalArgumentException("end lines with \\n alone, without \\r.");
        }
        if (c == '\t') {
          throw new IllegalArgumentException("a tab isn't read the same way by every InfluxDB: use one space.");
        }
        if (c < 0x20 || c == 0x7F) {
          throw new IllegalArgumentException("the line holds a control character.");
        }
      }
      char first = s.charAt(0);
      if (first == '#') {
        throw new IllegalArgumentException("comments aren't written: leave out the lines that start with #.");
      }
      if (first == ' ') {
        throw new IllegalArgumentException("the line starts with a space: start it with the measurement.");
      }
      String measurement = name("measurement", ", ", "=\"", ", ", true);
      Map<String, String> tags = new LinkedHashMap<>();
      while (i < s.length() && s.charAt(i) == ',') {
        i++;
        String key = name("tag key", "=", ", \"", ",= ", true);
        expect('=', "a tag needs key=value");
        String value = name("tag value", ", ", "=\"", ",= ", false);
        if (key.equals("time")) {
          throw new IllegalArgumentException("time can't be a tag key.");
        }
        if (tags.put(key, value) != null) {
          throw new IllegalArgumentException("the tag " + key + " is there twice.");
        }
      }
      expect(' ', "a space and the fields follow the measurement and its tags");
      if (i < s.length() && s.charAt(i) == ' ') {
        throw new IllegalArgumentException("one space, not more, goes between the parts of a line.");
      }
      List<Field> fields = new ArrayList<>();
      Set<String> keys = new HashSet<>();
      while (true) {
        String key = name("field key", "=", ", \"", ",= ", true);
        expect('=', "a field needs key=value");
        if (key.equals("time")) {
          throw new IllegalArgumentException("time can't be a field key.");
        }
        if (!keys.add(key)) {
          throw new IllegalArgumentException("the field " + key + " is there twice.");
        }
        fields.add(value(key));
        if (i < s.length() && s.charAt(i) == ',') {
          i++;
          continue;
        }
        break;
      }
      Long timestamp = null;
      if (i < s.length()) {
        expect(' ', "a space and the timestamp follow the fields");
        String ts = s.substring(i);
        if (ts.isEmpty()) {
          throw new IllegalArgumentException("the line ends with a space: leave it out, or put the timestamp after "
              + "it.");
        }
        if (!TIMESTAMP.matcher(ts).matches()) {
          throw new IllegalArgumentException("the timestamp " + shorten(ts) + " isn't an integer of digits; one "
              + "space goes before it and nothing after it.");
        }
        long value;
        try {
          value = Long.parseLong(ts);
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("the timestamp " + ts + " is out of range.");
        }
        if (instant(value, precision) == null) {
          throw new IllegalArgumentException("the timestamp " + ts + " (" + precision + ") is outside InfluxDB's time "
              + "range, 1677-09-21 to 2262-04-11.");
        }
        timestamp = value;
        i = s.length();
      }
      return new Point(line, measurement, tags, List.copyOf(fields), timestamp);
    }

    /**
     * A measurement, tag key, tag value or field key up to an unescaped character of {@code ends}; an unescaped
     * character of {@code refused} is an error. {@code escapable} are the characters a backslash may escape here; any
     * other backslash is refused, as InfluxDB versions read it differently.
     *
     * @param reserved whether a leading {@code _} is refused: measurements and keys, not tag values
     */
    private String name(String what, String ends, String refused, String escapable, boolean reserved) {
      StringBuilder out = new StringBuilder();
      while (i < s.length()) {
        char c = s.charAt(i);
        if (c == '\\') {
          char e = i + 1 < s.length() ? s.charAt(i + 1) : 0;
          if (e == 0 || escapable.indexOf(e) < 0) {
            throw new IllegalArgumentException("a backslash in a " + what + " is read differently by InfluxDB "
                + "versions; GateRook takes it only before " + describe(escapable) + ".");
          }
          out.append(e);
          i += 2;
          continue;
        }
        if (ends.indexOf(c) >= 0) {
          break;
        }
        if (refused.indexOf(c) >= 0) {
          throw new IllegalArgumentException("a " + what + " can't hold " + (c == ' ' ? "a space" : String.valueOf(c))
              + (escapable.indexOf(c) >= 0 ? " unless a backslash escapes it" : "") + ".");
        }
        out.append(c);
        i++;
      }
      if (out.isEmpty()) {
        throw new IllegalArgumentException("a " + what + " is missing.");
      }
      if (reserved && out.charAt(0) == '_') {
        throw new IllegalArgumentException("the " + what + " " + out + " begins with _: InfluxDB 2.x reserves such "
            + "names.");
      }
      return out.toString();
    }

    private static String describe(String escapable) {
      List<String> parts = new ArrayList<>();
      for (char c : escapable.toCharArray()) {
        parts.add(c == ' ' ? "a space" : String.valueOf(c));
      }
      return String.join(", ", parts);
    }

    private void expect(char c, String what) {
      if (i >= s.length() || s.charAt(i) != c) {
        throw new IllegalArgumentException(what + ".");
      }
      i++;
    }

    private Field value(String key) {
      if (i >= s.length()) {
        throw new IllegalArgumentException("the field " + key + " has no value.");
      }
      if (s.charAt(i) == '"') {
        StringBuilder out = new StringBuilder();
        i++;
        while (i < s.length()) {
          char c = s.charAt(i);
          if (c == '\\') {
            char e = i + 1 < s.length() ? s.charAt(i + 1) : 0;
            if (e != '"' && e != '\\') {
              throw new IllegalArgumentException("in the string of " + key + ", a backslash is taken only before \" "
                  + "or another backslash: write \\\\ for one backslash.");
            }
            out.append(e);
            i += 2;
            continue;
          }
          if (c == '"') {
            i++;
            if (i < s.length() && s.charAt(i) != ',' && s.charAt(i) != ' ') {
              throw new IllegalArgumentException("a comma or a space follows the string of " + key + ".");
            }
            return new Field(key, Type.STRING, out.toString());
          }
          out.append(c);
          i++;
        }
        throw new IllegalArgumentException("the string of " + key + " isn't closed.");
      }
      int start = i;
      while (i < s.length() && s.charAt(i) != ',' && s.charAt(i) != ' ') {
        i++;
      }
      String v = s.substring(start, i);
      if (TRUE.contains(v)) {
        return new Field(key, Type.BOOLEAN, "true");
      }
      if (FALSE.contains(v)) {
        return new Field(key, Type.BOOLEAN, "false");
      }
      if (INTEGER.matcher(v).matches()) {
        BigInteger n = new BigInteger(v.substring(0, v.length() - 1));
        if (n.bitLength() > 63) {
          throw new IllegalArgumentException("the integer " + key + "=" + v + " is beyond 64 bits.");
        }
        return new Field(key, Type.INTEGER, n.toString());
      }
      if (UNSIGNED.matcher(v).matches()) {
        if (!unsignedAllowed) {
          throw new IllegalArgumentException("InfluxDB 1.x has no unsigned integers: write " + key + "="
              + v.substring(0, v.length() - 1) + "i.");
        }
        BigInteger n = new BigInteger(v.substring(0, v.length() - 1));
        if (n.compareTo(MAX_UNSIGNED) > 0) {
          throw new IllegalArgumentException("the unsigned integer " + key + "=" + v + " is beyond 64 bits.");
        }
        return new Field(key, Type.UNSIGNED, n.toString());
      }
      if (FLOAT.matcher(v).matches()) {
        double d = Double.parseDouble(v);
        if (!Double.isFinite(d)) {
          throw new IllegalArgumentException("the float " + key + "=" + shorten(v) + " is beyond a double.");
        }
        return new Field(key, Type.FLOAT, v);
      }
      throw new IllegalArgumentException(shorten(v) + " isn't a value for " + key + ": write a float (1.5), an "
          + "integer (1i), an unsigned integer (1u), a \"string\" or a boolean (true, false).");
    }

    private static String shorten(String v) {
      return v.length() <= 40 ? v : v.substring(0, 39) + "…";
    }

    @Override
    public String toString() {
      return "line " + line;
    }
  }

  /** {@code cpu host=web-1 · usage=12.5 (float), cores=4 (integer) · 2026-11-11T08:00:00Z} for the approver. */
  static String describe(Point p, String precision) {
    StringBuilder out = new StringBuilder(p.measurement());
    for (Map.Entry<String, String> t : p.tags().entrySet()) {
      out.append(' ').append(t.getKey()).append('=').append(t.getValue());
    }
    out.append(" · ");
    List<String> fields = new ArrayList<>();
    for (Field f : p.fields()) {
      String v = f.type() == Type.STRING ? "\"" + f.value() + "\"" : f.value();
      fields.add(f.key() + "=" + v + " (" + f.type().label + ")");
    }
    out.append(String.join(", ", fields)).append(" · ");
    out.append(p.timestamp() == null ? "the server's time" : instant(p.timestamp(), precision));
    return out.toString();
  }
}
