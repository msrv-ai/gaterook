package com.gaterook.connectors.firebase;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Firestore's typed values ({@code {"stringValue": "a"}}, {@code {"integerValue": "12"}}, {@code {"mapValue":
 * {"fields": {…}}}}) both ways.
 *
 * <p>What an agent passes is plain JSON or typed: an object with exactly one key that names a Firestore value type is
 * that typed value, checked strictly (its own maps and arrays typed throughout); any other JSON is converted — a
 * string to {@code stringValue}, an integer to {@code integerValue} (64 bits at most), any other number to
 * {@code doubleValue}, {@code true}/{@code false}, {@code null}, an array to {@code arrayValue} and an object to
 * {@code mapValue}, its members by the same rule. A reference names a document of the connection's own database, as
 * its full name or its path. Firestore's own limits are kept: no array directly in an array, maps 20 deep.
 *
 * <p>What comes back is plain JSON: an integer is a JSON number only where JavaScript reads it exactly (up to 2^53),
 * else its text; a timestamp is its RFC 3339 text; bytes are their first 256 bytes in hex with their length; a
 * reference is its document's path; a geo point is {@code {latitude, longitude}}; strings are cut to the call's cell
 * size; maps and arrays recursively, to a bounded depth.
 */
final class FirestoreValues {

  static final long MAX_SAFE_INTEGER = 9_007_199_254_740_992L;
  static final int MAX_BYTES_SHOWN = 256;
  /** Firestore nests maps and arrays at most 20 deep. */
  static final int MAX_DEPTH = 20;
  /** Depth of a value read back: deeper than Firestore allows, never reached by its own documents. */
  static final int MAX_DEPTH_SHOWN = 32;
  static final Set<String> TYPES = Set.of("nullValue", "booleanValue", "integerValue", "doubleValue",
      "timestampValue", "stringValue", "bytesValue", "referenceValue", "geoPointValue", "arrayValue", "mapValue");
  /** A document's full name in any database: {@code projects/p/databases/d/documents/…}. */
  private static final Pattern FULL_NAME =
      Pattern.compile("^projects/[^/]+/databases/[^/]+/documents(/.*)?$", Pattern.DOTALL);
  private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
  private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private FirestoreValues() {}

  // ── What an agent passes ────────────────────────────────────────────────────────────────────────────────────

  /**
   * One value an agent wrote, plain or typed, as Firestore's typed value.
   *
   * @param documents the connection's {@code projects/p/databases/d/documents}: where a reference may point
   * @param where     where the value is, for messages: {@code fields.status}
   */
  static ObjectNode typed(JsonNode value, String documents, String where) throws Refusal {
    return typed(value, documents, where, 0, false);
  }

  /** A document's fields an agent wrote: field names to values, plain or typed. */
  static ObjectNode fields(JsonNode fields, String documents, String where) throws Refusal {
    if (fields == null || fields.isMissingNode() || fields.isNull()) {
      return F.objectNode();
    }
    if (!fields.isObject()) {
      throw Refusal.bad(where + " must be an object of field names and values.", null);
    }
    ObjectNode out = F.objectNode();
    for (Map.Entry<String, JsonNode> e : fields.properties()) {
      out.set(fieldName(e.getKey(), where), typed(e.getValue(), documents, where + "." + e.getKey(), 1, false));
    }
    return out;
  }

  /**
   * Fields as an approved write carries them: each value one of Firestore's typed values, checked and normalized as
   * when the agent's values were converted. A plain value here never came from {@link #fields}.
   */
  static ObjectNode typedFields(JsonNode fields, String documents, String where) throws Refusal {
    if (fields == null || !fields.isObject()) {
      throw Refusal.bad(where + " must be an object of typed values.", null);
    }
    ObjectNode out = F.objectNode();
    for (Map.Entry<String, JsonNode> e : fields.properties()) {
      out.set(fieldName(e.getKey(), where), strict(e.getValue(), documents, where + "." + e.getKey(), 1, false));
    }
    return out;
  }

  private static String fieldName(String name, String where) throws Refusal {
    String problem = FieldPaths.problem(name);
    if (problem == null && name.equals(FieldPaths.NAME)) {
      problem = "is reserved by Firestore (__…__)";
    }
    if (problem == null && Names.utf8Length(name) > FieldPaths.MAX_BYTES) {
      problem = "is longer than Firestore allows";
    }
    if (problem != null) {
      throw Refusal.bad("The field name " + Names.shown(name) + " in " + where + " " + problem + ".", null);
    }
    return name;
  }

  private static ObjectNode typed(JsonNode v, String documents, String where, int depth, boolean inArray)
      throws Refusal {
    if (depth > MAX_DEPTH) {
      throw Refusal.bad(where + " nests deeper than Firestore's " + MAX_DEPTH + " levels.", null);
    }
    if (v == null || v.isMissingNode() || v.isNull()) {
      return F.objectNode().putNull("nullValue");
    }
    if (v.isBoolean()) {
      return F.objectNode().put("booleanValue", v.booleanValue());
    }
    if (v.isIntegralNumber()) {
      BigInteger n = v.bigIntegerValue();
      if (n.compareTo(LONG_MIN) < 0 || n.compareTo(LONG_MAX) > 0) {
        throw Refusal.bad(where + " is larger than a Firestore integer (64 bits).",
            "Write it as {\"doubleValue\": …} or as a string.");
      }
      return F.objectNode().put("integerValue", n.toString());
    }
    if (v.isNumber()) {
      double d = v.doubleValue();
      if (!Double.isFinite(d)) {
        throw Refusal.bad(where + " isn't a finite number.", null);
      }
      return F.objectNode().put("doubleValue", d);
    }
    if (v.isString()) {
      return F.objectNode().put("stringValue", v.asString());
    }
    if (v.isArray()) {
      if (inArray) {
        throw Refusal.bad(where + " is an array inside an array, which Firestore doesn't store.",
            "Put the inner array into a map: [{\"items\": […]}].");
      }
      ArrayNode values = F.arrayNode();
      int i = 0;
      for (JsonNode e : v) {
        values.add(typed(e, documents, where + "[" + i++ + "]", depth + 1, true));
      }
      ObjectNode out = F.objectNode();
      out.putObject("arrayValue").set("values", values);
      return out;
    }
    if (v.isObject()) {
      if (v.size() == 1 && TYPES.contains(v.propertyNames().iterator().next())) {
        return checked(v, documents, where, depth, inArray);
      }
      ObjectNode fields = F.objectNode();
      for (Map.Entry<String, JsonNode> e : v.properties()) {
        fields.set(fieldName(e.getKey(), where), typed(e.getValue(), documents, where + "." + e.getKey(), depth + 1,
            false));
      }
      ObjectNode out = F.objectNode();
      out.putObject("mapValue").set("fields", fields);
      return out;
    }
    throw Refusal.bad(where + " isn't a value Firestore stores.", null);
  }

  /** A typed value, checked and normalized: integers as text, references as full names. */
  private static ObjectNode checked(JsonNode typed, String documents, String where, int depth, boolean inArray)
      throws Refusal {
    if (depth > MAX_DEPTH) {
      throw Refusal.bad(where + " nests deeper than Firestore's " + MAX_DEPTH + " levels.", null);
    }
    Map.Entry<String, JsonNode> t = typed.properties().iterator().next();
    String type = t.getKey();
    JsonNode v = t.getValue();
    ObjectNode out = F.objectNode();
    switch (type) {
      case "nullValue" -> {
        if (!v.isNull() && !(v.isString() && v.asString().equals("NULL_VALUE"))) {
          throw typedBad(where, "nullValue takes null");
        }
        out.putNull("nullValue");
      }
      case "booleanValue" -> {
        if (!v.isBoolean()) {
          throw typedBad(where, "booleanValue takes true or false");
        }
        out.put("booleanValue", v.booleanValue());
      }
      case "integerValue" -> out.put("integerValue", integer(v, where).toString());
      case "doubleValue" -> {
        if (v.isNumber() && Double.isFinite(v.doubleValue())) {
          out.put("doubleValue", v.doubleValue());
        } else if (v.isString() && Set.of("NaN", "Infinity", "-Infinity").contains(v.asString())) {
          out.put("doubleValue", v.asString());
        } else {
          throw typedBad(where, "doubleValue takes a number, \"NaN\", \"Infinity\" or \"-Infinity\"");
        }
      }
      case "timestampValue" -> {
        if (!v.isString() || !timestamp(v.asString())) {
          throw typedBad(where, "timestampValue takes an RFC 3339 time such as 2026-01-02T03:04:05Z");
        }
        out.put("timestampValue", v.asString());
      }
      case "stringValue" -> {
        if (!v.isString()) {
          throw typedBad(where, "stringValue takes a string");
        }
        out.put("stringValue", v.asString());
      }
      case "bytesValue" -> {
        if (!v.isString() || !base64(v.asString())) {
          throw typedBad(where, "bytesValue takes base64");
        }
        out.put("bytesValue", v.asString());
      }
      case "referenceValue" -> {
        if (!v.isString()) {
          throw typedBad(where, "referenceValue takes a document's path, such as users/alice");
        }
        out.put("referenceValue", reference(v.asString(), documents, where));
      }
      case "geoPointValue" -> {
        if (!v.isObject() || v.size() != 2 || !v.path("latitude").isNumber() || !v.path("longitude").isNumber()
            || Math.abs(v.get("latitude").doubleValue()) > 90 || Math.abs(v.get("longitude").doubleValue()) > 180) {
          throw typedBad(where, "geoPointValue takes {\"latitude\": -90…90, \"longitude\": -180…180}");
        }
        out.putObject("geoPointValue").put("latitude", v.get("latitude").doubleValue())
            .put("longitude", v.get("longitude").doubleValue());
      }
      case "arrayValue" -> {
        if (inArray) {
          throw Refusal.bad(where + " is an array inside an array, which Firestore doesn't store.", null);
        }
        JsonNode values = v.path("values");
        if (!v.isObject() || v.size() > 1 || (v.size() == 1 && !values.isArray())) {
          throw typedBad(where, "arrayValue takes {\"values\": [typed values]}");
        }
        ArrayNode list = F.arrayNode();
        int i = 0;
        for (JsonNode e : values) {
          list.add(strict(e, documents, where + "[" + i++ + "]", depth + 1, true));
        }
        out.putObject("arrayValue").set("values", list);
      }
      case "mapValue" -> {
        JsonNode fields = v.path("fields");
        if (!v.isObject() || v.size() > 1 || (v.size() == 1 && !fields.isObject())) {
          throw typedBad(where, "mapValue takes {\"fields\": {name: typed value}}");
        }
        ObjectNode map = F.objectNode();
        for (Map.Entry<String, JsonNode> e : fields.properties()) {
          map.set(fieldName(e.getKey(), where), strict(e.getValue(), documents, where + "." + e.getKey(), depth + 1,
              false));
        }
        out.putObject("mapValue").set("fields", map);
      }
      default -> throw typedBad(where, "has an unknown type");
    }
    return out;
  }

  /** Inside a typed map or array: typed values only, as Firestore writes them. */
  private static ObjectNode strict(JsonNode v, String documents, String where, int depth, boolean inArray)
      throws Refusal {
    if (v == null || !v.isObject() || v.size() != 1 || !TYPES.contains(v.propertyNames().iterator().next())) {
      throw typedBad(where, "isn't one typed value such as {\"stringValue\": \"a\"}: inside mapValue and arrayValue "
          + "every value is typed");
    }
    return checked(v, documents, where, depth, inArray);
  }

  private static BigInteger integer(JsonNode v, String where) throws Refusal {
    BigInteger n = null;
    if (v.isIntegralNumber()) {
      n = v.bigIntegerValue();
    } else if (v.isString() && v.asString().matches("-?\\d{1,19}")) {
      n = new BigInteger(v.asString());
    }
    if (n == null || n.compareTo(LONG_MIN) < 0 || n.compareTo(LONG_MAX) > 0) {
      throw typedBad(where, "integerValue takes a 64-bit integer, as a number or its digits");
    }
    return n;
  }

  /** A document's full name from a reference an agent wrote: its path, or a full name in the same database. */
  static String reference(String text, String documents, String where) throws Refusal {
    boolean own = text.startsWith(documents + "/");
    if (!own && FULL_NAME.matcher(text).matches()) {
      throw Refusal.bad(where + " refers to a document of another database.",
          "Name a document of this database by its path, such as users/alice.");
    }
    String path = own ? text.substring(documents.length() + 1) : text;
    FirestorePath p = FirestorePath.parse(path);
    if (!p.document()) {
      throw Refusal.bad(where + " doesn't refer to a document: a document's path has an even number of IDs.", null);
    }
    return p.name(documents);
  }

  private static boolean timestamp(String text) {
    try {
      OffsetDateTime t = OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
      return t.getYear() >= 1 && t.getYear() <= 9999;
    } catch (DateTimeParseException e) {
      return false;
    }
  }

  private static boolean base64(String text) {
    try {
      Base64.getDecoder().decode(text);
      return true;
    } catch (IllegalArgumentException e) {
      try {
        Base64.getUrlDecoder().decode(text);
        return true;
      } catch (IllegalArgumentException e2) {
        return false;
      }
    }
  }

  private static Refusal typedBad(String where, String problem) {
    return Refusal.bad(where + ": " + problem + ".", "Pass plain JSON, or one typed value such as "
        + "{\"timestampValue\": \"2026-01-02T03:04:05Z\"}.");
  }

  // ── What comes back ─────────────────────────────────────────────────────────────────────────────────────────

  /** A document Firestore returned as the agent sees it: its path, ID, fields as plain JSON and its times. */
  static ObjectNode document(JsonNode doc, String documents, int maxCellChars) {
    ObjectNode out = F.objectNode();
    String name = doc.path("name").asString("");
    String path = name.startsWith(documents + "/") ? name.substring(documents.length() + 1) : name;
    out.put("path", path);
    out.put("id", path.substring(path.lastIndexOf('/') + 1));
    out.set("fields", plainFields(doc.path("fields"), documents, maxCellChars, 0));
    if (doc.path("createTime").isString()) {
      out.put("createTime", doc.get("createTime").asString());
    }
    if (doc.path("updateTime").isString()) {
      out.put("updateTime", doc.get("updateTime").asString());
    }
    return out;
  }

  static ObjectNode plainFields(JsonNode fields, String documents, int maxCellChars, int depth) {
    ObjectNode out = F.objectNode();
    for (Map.Entry<String, JsonNode> e : fields.properties()) {
      out.set(e.getKey(), plain(e.getValue(), documents, maxCellChars, depth));
    }
    return out;
  }

  /** One typed value as plain JSON. */
  static JsonNode plain(JsonNode value, String documents, int maxCellChars, int depth) {
    if (value == null || !value.isObject() || value.isEmpty() || depth > MAX_DEPTH_SHOWN) {
      return depth > MAX_DEPTH_SHOWN ? F.stringNode("‹nested deeper than " + MAX_DEPTH_SHOWN + " levels›")
          : F.nullNode();
    }
    Map.Entry<String, JsonNode> typed = value.properties().iterator().next();
    JsonNode v = typed.getValue();
    return switch (typed.getKey()) {
      case "nullValue" -> F.nullNode();
      case "booleanValue" -> F.booleanNode(v.asBoolean(false));
      case "integerValue" -> integerShown(v.asString(""));
      case "doubleValue" -> v.isNumber() ? F.numberNode(v.doubleValue()) : F.stringNode(v.asString(""));
      case "timestampValue" -> F.stringNode(v.asString(""));
      case "stringValue" -> text(v.asString(""), maxCellChars);
      case "bytesValue" -> bytes(v.asString(""));
      case "referenceValue" -> {
        String name = v.asString("");
        yield F.stringNode(name.startsWith(documents + "/") ? name.substring(documents.length() + 1) : name);
      }
      case "geoPointValue" -> F.objectNode().put("latitude", v.path("latitude").asDouble(0))
          .put("longitude", v.path("longitude").asDouble(0));
      case "arrayValue" -> {
        ArrayNode a = F.arrayNode();
        for (JsonNode e : v.path("values")) {
          a.add(plain(e, documents, maxCellChars, depth + 1));
        }
        yield a;
      }
      case "mapValue" -> plainFields(v.path("fields"), documents, maxCellChars, depth + 1);
      default -> F.nullNode();
    };
  }

  /** An int64 as JSON: a number where JavaScript keeps it exact, else its text. */
  static JsonNode integerShown(String text) {
    try {
      BigInteger n = new BigDecimal(text).toBigIntegerExact();
      return n.abs().compareTo(BigInteger.valueOf(MAX_SAFE_INTEGER)) <= 0 ? F.numberNode(n.longValue())
          : F.stringNode(n.toString());
    } catch (NumberFormatException | ArithmeticException e) {
      return F.stringNode(text);
    }
  }

  private static JsonNode bytes(String base64) {
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(base64);
    } catch (IllegalArgumentException e) {
      try {
        bytes = Base64.getUrlDecoder().decode(base64);
      } catch (IllegalArgumentException e2) {
        return F.stringNode("‹not base64›");
      }
    }
    int shown = Math.min(bytes.length, MAX_BYTES_SHOWN);
    String hex = "\\x" + HexFormat.of().formatHex(bytes, 0, shown);
    return F.stringNode(bytes.length > shown ? hex + "…‹" + bytes.length + " bytes›" : hex);
  }

  /** A string cut to {@code max} characters, never inside a surrogate pair. */
  static JsonNode text(String v, int max) {
    if (v.length() <= max) {
      return F.stringNode(v);
    }
    int end = Character.isHighSurrogate(v.charAt(max - 1)) ? max - 1 : max;
    return F.stringNode(v.substring(0, end) + "…‹" + v.length() + " characters›");
  }
}
