package com.gaterook.connectors.neo4j;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.Float32Vector;
import org.neo4j.driver.types.Float64Vector;
import org.neo4j.driver.types.Int16Vector;
import org.neo4j.driver.types.Int32Vector;
import org.neo4j.driver.types.Int64Vector;
import org.neo4j.driver.types.Int8Vector;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Path;
import org.neo4j.driver.types.Point;
import org.neo4j.driver.types.Relationship;
import org.neo4j.driver.types.Vector;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Neo4j's values as JSON. An integer is a JSON number only where JavaScript keeps it exact (up to 2^53), otherwise
 * its text; a float is a number, NaN and the infinities their names. Strings are cut to the call's cell size, bytes
 * show their first 256 in hex with their length.
 *
 * <ul>
 *   <li>a node: {@code {"elementId", "labels", "properties"}};</li>
 *   <li>a relationship: {@code {"elementId", "type", "startElementId", "endElementId", "properties"}};</li>
 *   <li>a path: {@code {"length", "nodes", "relationships"}}, in the path's order;</li>
 *   <li>temporal values as ISO 8601: {@code 2026-01-02}, {@code 03:04:05+02:00}, {@code 2026-01-02T03:04:05},
 *       {@code 2026-01-02T03:04:05+01:00[Europe/Berlin]}, durations as {@code P14M16DT45005.5S};</li>
 *   <li>a point: {@code {"crs", "srid", "x", "y"}} and {@code "z"} in 3D, x the longitude in WGS-84;</li>
 *   <li>a vector: its numbers;</li>
 *   <li>lists and maps as arrays and objects, at most {@value #MAX_ITEMS} entries and {@value #MAX_DEPTH} levels.</li>
 * </ul>
 *
 * <p>One value costs at most its {@link Budget}: once that is spent, the rest of a list or map is a note of what was
 * left out. A result never holds more than the call may answer, whatever one cell holds.
 */
final class Neo4jJson {

  static final long MAX_SAFE_INTEGER = 9_007_199_254_740_992L;
  static final int MAX_BYTES_SHOWN = 256;
  static final int MAX_ITEMS = 1_000;
  static final int MAX_DEPTH = 16;
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private Neo4jJson() {}

  /** What converting a value may still cost, in characters of JSON (roughly). */
  static final class Budget {
    private long left;
    private boolean cut;

    Budget(long chars) {
      this.left = chars;
    }

    boolean spent() {
      return left <= 0;
    }

    void take(long chars) {
      left -= chars;
    }

    /** Whether something was left out for the budget, a nesting or entry limit. */
    boolean cut() {
      return cut;
    }

    void markCut() {
      cut = true;
    }
  }

  /** One value as JSON. */
  static JsonNode value(Value v, int maxCellChars, Budget budget) {
    return value(v, maxCellChars, budget, 0);
  }

  /** The name of a value's type, for a column: {@code NODE}, {@code INTEGER}, {@code DATE_TIME}. */
  static String type(Value v) {
    if (v == null) {
      return "NULL";
    }
    String name = v.type().name().toUpperCase(Locale.ROOT);
    // The driver names a list "LIST OF ANY?".
    return name.startsWith("LIST") ? "LIST" : name.replace(' ', '_');
  }

  private static JsonNode value(Value v, int max, Budget budget, int depth) {
    if (v == null || v.isNull()) {
      budget.take(4);
      return F.nullNode();
    }
    if (depth > MAX_DEPTH) {
      budget.markCut();
      return F.stringNode("‹nested deeper than " + MAX_DEPTH + " levels›");
    }
    JsonNode out;
    switch (type(v)) {
      case "BOOLEAN" -> out = F.booleanNode(v.asBoolean());
      case "INTEGER" -> out = integer(v.asLong());
      case "FLOAT" -> out = real(v.asDouble());
      case "STRING" -> out = text(v.asString(), max);
      case "BYTES" -> out = bytes(v.asByteArray());
      case "LIST" -> out = list(v, max, budget, depth);
      case "MAP" -> out = map(v, max, budget, depth);
      case "NODE" -> out = node(v.asNode(), max, budget, depth);
      case "RELATIONSHIP" -> out = relationship(v.asRelationship(), max, budget, depth);
      case "PATH" -> out = path(v.asPath(), max, budget, depth);
      case "POINT" -> out = point(v.asPoint());
      case "DATE" -> out = F.stringNode(v.asLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
      case "TIME" -> out = F.stringNode(v.asOffsetTime().format(DateTimeFormatter.ISO_OFFSET_TIME));
      case "LOCAL_TIME" -> out = F.stringNode(v.asLocalTime().format(DateTimeFormatter.ISO_LOCAL_TIME));
      case "LOCAL_DATE_TIME" -> out = F.stringNode(v.asLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
      case "DATE_TIME" -> out = dateTime(v, max);
      case "DURATION" -> out = F.stringNode(v.asIsoDuration().toString());
      case "VECTOR" -> out = vector(v.asVector(), budget);
      default -> out = text(String.valueOf(v), max);
    }
    if (out.isValueNode()) {
      budget.take(out.isString() ? out.asString().length() + 2 : 8);
    }
    return out;
  }

  private static JsonNode dateTime(Value v, int max) {
    try {
      ZonedDateTime z = v.asZonedDateTime();
      return F.stringNode(z.getZone() instanceof ZoneOffset
          ? OffsetDateTime.of(z.toLocalDateTime(), (ZoneOffset) z.getZone()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
          : z.format(DateTimeFormatter.ISO_ZONED_DATE_TIME));
    } catch (RuntimeException e) {
      // A time zone this computer doesn't know: the driver's own text.
      return text(String.valueOf(v), max);
    }
  }

  /** An integer: a JSON number where JavaScript keeps it exact, else its text. */
  static JsonNode integer(long n) {
    return n >= -MAX_SAFE_INTEGER && n <= MAX_SAFE_INTEGER ? F.numberNode(n) : F.stringNode(Long.toString(n));
  }

  static JsonNode real(double d) {
    if (Double.isNaN(d)) {
      return F.stringNode("NaN");
    }
    if (Double.isInfinite(d)) {
      return F.stringNode(d > 0 ? "Infinity" : "-Infinity");
    }
    return F.numberNode(d);
  }

  /** A string cut to {@code max} characters, never inside a surrogate pair. */
  static JsonNode text(String v, int max) {
    if (v.length() <= max) {
      return F.stringNode(v);
    }
    int end = Character.isHighSurrogate(v.charAt(max - 1)) ? max - 1 : max;
    return F.stringNode(v.substring(0, end) + "…‹" + v.length() + " characters›");
  }

  static JsonNode bytes(byte[] bytes) {
    int shown = Math.min(bytes.length, MAX_BYTES_SHOWN);
    String hex = "\\x" + HexFormat.of().formatHex(bytes, 0, shown);
    return F.stringNode(bytes.length > shown ? hex + "…‹" + bytes.length + " bytes›" : hex);
  }

  private static ArrayNode list(Value v, int max, Budget budget, int depth) {
    ArrayNode out = F.arrayNode();
    int size = v.size();
    for (int i = 0; i < size; i++) {
      if (i >= MAX_ITEMS || budget.spent()) {
        budget.markCut();
        out.add("…‹" + (size - i) + " more›");
        break;
      }
      out.add(value(v.get(i), max, budget, depth + 1));
    }
    budget.take(2 + size);
    return out;
  }

  private static ObjectNode map(Value v, int max, Budget budget, int depth) {
    ObjectNode out = F.objectNode();
    int i = 0;
    int size = v.size();
    for (String key : v.keys()) {
      if (i >= MAX_ITEMS || budget.spent()) {
        budget.markCut();
        out.put("…", "‹" + (size - i) + " more entries›");
        break;
      }
      budget.take(key.length() + 4);
      out.set(key, value(v.get(key), max, budget, depth + 1));
      i++;
    }
    return out;
  }

  private static ObjectNode node(Node n, int max, Budget budget, int depth) {
    ObjectNode out = F.objectNode().put("elementId", n.elementId());
    ArrayNode labels = out.putArray("labels");
    int i = 0;
    for (String label : n.labels()) {
      if (i++ >= MAX_ITEMS) {
        budget.markCut();
        break;
      }
      labels.add(label);
      budget.take(label.length() + 3);
    }
    out.set("properties", properties(n, max, budget, depth));
    budget.take(n.elementId().length() + 40);
    return out;
  }

  private static ObjectNode relationship(Relationship r, int max, Budget budget, int depth) {
    ObjectNode out = F.objectNode().put("elementId", r.elementId()).put("type", r.type())
        .put("startElementId", r.startNodeElementId()).put("endElementId", r.endNodeElementId());
    out.set("properties", properties(r, max, budget, depth));
    budget.take(r.elementId().length() + r.type().length() + r.startNodeElementId().length()
        + r.endNodeElementId().length() + 70);
    return out;
  }

  private static ObjectNode properties(org.neo4j.driver.types.Entity e, int max, Budget budget, int depth) {
    ObjectNode out = F.objectNode();
    int i = 0;
    int size = e.size();
    for (String key : e.keys()) {
      if (i >= MAX_ITEMS || budget.spent()) {
        budget.markCut();
        out.put("…", "‹" + (size - i) + " more properties›");
        break;
      }
      budget.take(key.length() + 4);
      out.set(key, value(e.get(key), max, budget, depth + 1));
      i++;
    }
    return out;
  }

  private static ObjectNode path(Path p, int max, Budget budget, int depth) {
    ObjectNode out = F.objectNode().put("length", p.length());
    ArrayNode nodes = out.putArray("nodes");
    int i = 0;
    for (Node n : p.nodes()) {
      if (i++ >= MAX_ITEMS || budget.spent()) {
        budget.markCut();
        nodes.add("…‹more nodes›");
        break;
      }
      nodes.add(node(n, max, budget, depth + 1));
    }
    ArrayNode relationships = out.putArray("relationships");
    i = 0;
    for (Relationship r : p.relationships()) {
      if (i++ >= MAX_ITEMS || budget.spent()) {
        budget.markCut();
        relationships.add("…‹more relationships›");
        break;
      }
      relationships.add(relationship(r, max, budget, depth + 1));
    }
    return out;
  }

  /** The coordinate reference system of a point, by its SRID, as Cypher names it. */
  static String crs(int srid) {
    return switch (srid) {
      case 4326 -> "wgs-84";
      case 4979 -> "wgs-84-3d";
      case 7203 -> "cartesian";
      case 9157 -> "cartesian-3d";
      default -> "srid-" + srid;
    };
  }

  private static ObjectNode point(Point p) {
    ObjectNode out = F.objectNode().put("crs", crs(p.srid())).put("srid", p.srid());
    out.set("x", real(p.x()));
    out.set("y", real(p.y()));
    if (!Double.isNaN(p.z())) {
      out.set("z", real(p.z()));
    }
    return out;
  }

  private static ArrayNode vector(Vector v, Budget budget) {
    ArrayNode out = F.arrayNode();
    int n = Math.min(v.length(), MAX_ITEMS);
    switch (v) {
      case Float64Vector f -> {
        double[] a = f.toArray();
        for (int i = 0; i < n; i++) {
          out.add(real(a[i]));
        }
      }
      case Float32Vector f -> {
        float[] a = f.toArray();
        for (int i = 0; i < n; i++) {
          out.add(real(a[i]));
        }
      }
      case Int64Vector l -> {
        long[] a = l.toArray();
        for (int i = 0; i < n; i++) {
          out.add(integer(a[i]));
        }
      }
      case Int32Vector l -> {
        int[] a = l.toArray();
        for (int i = 0; i < n; i++) {
          out.add(a[i]);
        }
      }
      case Int16Vector l -> {
        short[] a = l.toArray();
        for (int i = 0; i < n; i++) {
          out.add(a[i]);
        }
      }
      case Int8Vector l -> {
        byte[] a = l.toArray();
        for (int i = 0; i < n; i++) {
          out.add(a[i]);
        }
      }
    }
    if (v.length() > n) {
      budget.markCut();
      out.add("…‹" + (v.length() - n) + " more›");
    }
    budget.take(8L * n + 2);
    return out;
  }
}
