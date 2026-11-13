package com.gaterook.connectors.cassandra;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.data.CqlDuration;
import com.datastax.oss.driver.api.core.data.CqlVector;
import com.datastax.oss.driver.api.core.data.GettableByIndex;
import com.datastax.oss.driver.api.core.data.TupleValue;
import com.datastax.oss.driver.api.core.data.UdtValue;
import com.datastax.oss.driver.api.core.type.CustomType;
import com.datastax.oss.driver.api.core.type.DataType;
import com.datastax.oss.driver.api.core.type.DataTypes;
import com.datastax.oss.driver.api.core.type.ListType;
import com.datastax.oss.driver.api.core.type.MapType;
import com.datastax.oss.driver.api.core.type.SetType;
import com.datastax.oss.driver.api.core.type.TupleType;
import com.datastax.oss.driver.api.core.type.UserDefinedType;
import com.datastax.oss.driver.api.core.type.VectorType;
import com.datastax.oss.driver.api.core.type.codec.TypeCodec;
import com.datastax.oss.driver.api.core.type.codec.registry.CodecRegistry;
import com.gaterook.connectors.support.JsonNumbers;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * CQL values and JSON, both ways, by the CQL type the server declared.
 *
 * <p>Out: {@code int}, {@code smallint}, {@code tinyint} and {@code bigint} or {@code varint} within ±2^53 are
 * JSON numbers, larger ones their exact text; {@code decimal} is its exact text; finite {@code float} and
 * {@code double} values are numbers, NaN and the infinities text; {@code timestamp} is ISO-8601 UTC, {@code date}
 * and {@code time} ISO-8601 too; {@code uuid} and {@code timeuuid} text; {@code blob} {@code 0x…} hex of its first 256
 * bytes with its length; text is cut to the cell size; lists, sets, tuples and vectors are arrays; maps are objects
 * when their keys read as text, else arrays of {@code {key, value}}; UDTs are objects of their fields. A collection
 * that outgrows the cell size ends with a marker of what was left out.
 *
 * <p>In: the same forms, checked against the type of each bind marker: integers as JSON integers or digit strings
 * (exact for {@code bigint} and {@code varint}), decimals as numbers or strings, {@code timestamp} as ISO-8601 or
 * epoch milliseconds, {@code inet} as an IP literal (never a name: nothing is looked up), {@code blob} as
 * {@code 0x…}, collections as arrays, maps as objects (or arrays of {@code [key, value]}), UDTs as objects.
 */
final class Values {

  static final long MAX_SAFE_INTEGER = 9_007_199_254_740_992L;
  static final int MAX_BYTES_SHOWN = 256;
  static final int MAX_DEPTH = 12;
  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  private static final Pattern INTEGER = Pattern.compile("-?\\d{1,4000}");
  private static final Pattern UUID_TEXT = Pattern.compile(
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private static final Pattern HEX = Pattern.compile("0[xX](?:[0-9a-fA-F]{2})*");
  private static final Pattern IP = Pattern.compile("[0-9A-Fa-f:.%]{2,64}");

  private Values() {}

  /** A value that doesn't fit its marker's type, with where it is ({@code values[2]}, {@code values.id.city}). */
  static final class BadValue extends Exception {
    private static final long serialVersionUID = 1L;

    BadValue(String message) {
      super(message, null, false, false);
    }
  }

  /** The type as CQL writes it: {@code map<text, frozen<list<int>>>}. */
  static String cql(DataType type) {
    return type.asCql(true, true);
  }

  // ── Out ─────────────────────────────────────────────────────────────────────────────────────────────────────

  /** How many characters a cell may still take. */
  static final class Budget {
    private long left;

    Budget(long chars) {
      this.left = chars;
    }

    boolean spent() {
      return left <= 0;
    }

    void take(long chars) {
      left -= chars;
    }
  }

  /** Cell {@code i} of a row as JSON. */
  static JsonNode cell(GettableByIndex row, int i, DataType type, int maxCellChars) {
    if (row.isNull(i)) {
      return F.nullNode();
    }
    if (isText(type)) {
      return text(row.getBytesUnsafe(i), maxCellChars);
    }
    if (type.equals(DataTypes.BLOB)) {
      return blob(row.getBytesUnsafe(i));
    }
    return json(type, row.getObject(i), new Budget(maxCellChars), maxCellChars, 0);
  }

  static JsonNode json(DataType type, Object value, Budget budget, int maxCellChars, int depth) {
    if (value == null) {
      return F.nullNode();
    }
    if (depth > MAX_DEPTH) {
      budget.take(3);
      return F.stringNode("…");
    }
    JsonNode out;
    if (type instanceof ListType || type instanceof SetType) {
      DataType element = type instanceof ListType l ? l.getElementType() : ((SetType) type).getElementType();
      out = array((Iterable<?>) value, element, budget, maxCellChars, depth);
    } else if (type instanceof VectorType v && value instanceof CqlVector<?> vector) {
      out = array(vector, v.getElementType(), budget, maxCellChars, depth);
    } else if (type instanceof MapType m) {
      out = map((Map<?, ?>) value, m, budget, maxCellChars, depth);
    } else if (type instanceof UserDefinedType udt && value instanceof UdtValue u) {
      ObjectNode o = F.objectNode();
      List<CqlIdentifier> names = udt.getFieldNames();
      for (int f = 0; f < names.size(); f++) {
        if (budget.spent()) {
          o.put("…", "‹" + (names.size() - f) + " more fields›");
          break;
        }
        o.set(names.get(f).asInternal(), u.isNull(f) ? F.nullNode()
            : json(udt.getFieldTypes().get(f), u.getObject(f), budget, maxCellChars, depth + 1));
      }
      out = o;
    } else if (type instanceof TupleType tuple && value instanceof TupleValue t) {
      ArrayNode a = F.arrayNode();
      List<DataType> types = tuple.getComponentTypes();
      for (int c = 0; c < types.size(); c++) {
        a.add(t.isNull(c) ? F.nullNode() : json(types.get(c), t.getObject(c), budget, maxCellChars, depth + 1));
      }
      out = a;
    } else {
      out = scalar(type, value, maxCellChars);
    }
    if (out.isValueNode()) {
      budget.take(out.isString() ? out.asString().length() + 2 : out.toString().length());
    }
    return out;
  }

  private static ArrayNode array(Iterable<?> values, DataType element, Budget budget, int maxCellChars, int depth) {
    ArrayNode a = F.arrayNode();
    int shown = 0;
    int total = values instanceof java.util.Collection<?> c ? c.size() : -1;
    for (Object v : values) {
      if (budget.spent()) {
        a.add("…‹" + (total < 0 ? "more" : (total - shown) + " more") + " items›");
        break;
      }
      a.add(json(element, v, budget, maxCellChars, depth + 1));
      shown++;
    }
    return a;
  }

  private static JsonNode map(Map<?, ?> values, MapType type, Budget budget, int maxCellChars, int depth) {
    boolean textKeys = textLikeKey(type.getKeyType());
    ObjectNode o = textKeys ? F.objectNode() : null;
    ArrayNode a = textKeys ? null : F.arrayNode();
    int shown = 0;
    for (Map.Entry<?, ?> e : values.entrySet()) {
      if (budget.spent()) {
        String more = "…‹" + (values.size() - shown) + " more entries›";
        if (o != null) {
          o.put("…", more);
        } else {
          a.add(more);
        }
        break;
      }
      JsonNode key = json(type.getKeyType(), e.getKey(), budget, maxCellChars, depth + 1);
      JsonNode value = json(type.getValueType(), e.getValue(), budget, maxCellChars, depth + 1);
      if (o != null) {
        o.set(key.isString() ? key.asString() : key.toString(), value);
      } else {
        a.addObject().<ObjectNode>set("key", key).set("value", value);
      }
      shown++;
    }
    return o != null ? o : a;
  }

  /** Keys that a JSON object can name exactly: text, numbers, uuids, dates, booleans. */
  private static boolean textLikeKey(DataType key) {
    return !(key instanceof ListType || key instanceof SetType || key instanceof MapType || key instanceof TupleType
        || key instanceof UserDefinedType || key instanceof CustomType || key.equals(DataTypes.BLOB));
  }

  private static JsonNode scalar(DataType type, Object value, int maxCellChars) {
    if (value instanceof String s) {
      return cut(s, maxCellChars);
    }
    if (value instanceof Long l) {
      return Math.abs(l) <= MAX_SAFE_INTEGER ? F.numberNode(l) : F.stringNode(Long.toString(l));
    }
    if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
      return F.numberNode(((Number) value).intValue());
    }
    if (value instanceof BigInteger b) {
      return b.abs().compareTo(BigInteger.valueOf(MAX_SAFE_INTEGER)) <= 0 ? F.numberNode(b.longValue())
          : cut(b.toString(), maxCellChars);
    }
    if (value instanceof BigDecimal d) {
      return cut(JsonNumbers.expandable(d) ? d.toPlainString() : JsonNumbers.plain(d), maxCellChars);
    }
    if (value instanceof Double d) {
      return Double.isFinite(d) ? F.numberNode(d) : F.stringNode(d.toString());
    }
    if (value instanceof Float f) {
      return Float.isFinite(f) ? F.numberNode(f.doubleValue()) : F.stringNode(f.toString());
    }
    if (value instanceof Boolean b) {
      return F.booleanNode(b);
    }
    if (value instanceof Instant t) {
      return F.stringNode(t.toString());
    }
    if (value instanceof LocalDate || value instanceof LocalTime || value instanceof UUID
        || value instanceof CqlDuration) {
      return F.stringNode(value.toString());
    }
    if (value instanceof InetAddress a) {
      return F.stringNode(a.getHostAddress());
    }
    if (value instanceof ByteBuffer b) {
      return blob(b);
    }
    return cut(String.valueOf(value), maxCellChars);
  }

  private static boolean isText(DataType type) {
    return type.equals(DataTypes.TEXT) || type.equals(DataTypes.ASCII);
  }

  /** UTF-8 text without decoding more of it than a cell shows. */
  static JsonNode text(ByteBuffer bytes, int maxCellChars) {
    ByteBuffer b = bytes.duplicate();
    int total = b.remaining();
    int read = (int) Math.min(total, (long) maxCellChars * 4 + 4);
    b.limit(b.position() + read);
    CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE);
    CharBuffer chars;
    try {
      chars = decoder.decode(b);
    } catch (java.nio.charset.CharacterCodingException e) {
      return F.stringNode("‹text that isn't UTF-8›");
    }
    String s = chars.toString();
    if (read < total || s.length() > maxCellChars) {
      int end = Math.min(s.length(), maxCellChars);
      if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) {
        end--;
      }
      return F.stringNode(s.substring(0, end) + "…‹" + total + " bytes›");
    }
    return F.stringNode(s);
  }

  static JsonNode cut(String v, int max) {
    if (v.length() <= max) {
      return F.stringNode(v);
    }
    int end = Character.isHighSurrogate(v.charAt(max - 1)) ? max - 1 : max;
    return F.stringNode(v.substring(0, end) + "…‹" + v.length() + " characters›");
  }

  /** {@code 0x0102…}: the first {@link #MAX_BYTES_SHOWN} bytes as CQL writes a blob, with the length when cut. */
  static JsonNode blob(ByteBuffer bytes) {
    ByteBuffer b = bytes.duplicate();
    int total = b.remaining();
    byte[] head = new byte[Math.min(total, MAX_BYTES_SHOWN)];
    b.get(head);
    String hex = "0x" + HexFormat.of().formatHex(head);
    return F.stringNode(total > head.length ? hex + "…‹" + total + " bytes›" : hex);
  }

  // ── In ──────────────────────────────────────────────────────────────────────────────────────────────────────

  /** {@code value} as the Java value the driver's codec of {@code type} writes; {@code null} for JSON null. */
  static Object java(DataType type, JsonNode value, String at, CodecRegistry codecs, int depth) throws BadValue {
    if (value == null || value.isNull() || value.isMissingNode()) {
      return null;
    }
    if (depth > MAX_DEPTH) {
      throw new BadValue(at + " nests deeper than " + MAX_DEPTH + " levels.");
    }
    if (type instanceof ListType l) {
      List<Object> out = new ArrayList<>();
      int i = 0;
      for (JsonNode e : array(value, at, type)) {
        out.add(element(l.getElementType(), e, at + "[" + i++ + "]", codecs, depth));
      }
      return out;
    }
    if (type instanceof SetType s) {
      Set<Object> out = new LinkedHashSet<>();
      int i = 0;
      for (JsonNode e : array(value, at, type)) {
        out.add(element(s.getElementType(), e, at + "[" + i++ + "]", codecs, depth));
      }
      return out;
    }
    if (type instanceof MapType m) {
      return map(m, value, at, codecs, depth);
    }
    if (type instanceof VectorType v) {
      JsonNode a = array(value, at, type);
      if (a.size() != v.getDimensions()) {
        throw new BadValue(at + " needs " + v.getDimensions() + " numbers for " + cql(type) + ".");
      }
      List<Object> out = new ArrayList<>();
      for (int i = 0; i < a.size(); i++) {
        out.add(element(v.getElementType(), a.get(i), at + "[" + i + "]", codecs, depth));
      }
      return CqlVector.newInstance(out);
    }
    if (type instanceof TupleType t) {
      JsonNode a = array(value, at, type);
      List<DataType> types = t.getComponentTypes();
      if (a.size() != types.size()) {
        throw new BadValue(at + " needs " + types.size() + " components for " + cql(type) + ".");
      }
      TupleValue out = t.newValue();
      for (int i = 0; i < types.size(); i++) {
        out = set(out, i, types.get(i), java(types.get(i), a.get(i), at + "[" + i + "]", codecs, depth + 1),
            codecs);
      }
      return out;
    }
    if (type instanceof UserDefinedType udt) {
      if (!value.isObject()) {
        throw new BadValue(at + " needs an object of the fields of " + cql(type) + ".");
      }
      UdtValue out = udt.newValue();
      for (Map.Entry<String, JsonNode> field : value.properties()) {
        int i = udt.firstIndexOf(CqlIdentifier.fromInternal(field.getKey()));
        if (i < 0) {
          throw new BadValue(at + "." + field.getKey() + " isn't a field of " + cql(type) + ".");
        }
        DataType fieldType = udt.getFieldTypes().get(i);
        out = set(out, i, fieldType, java(fieldType, field.getValue(), at + "." + field.getKey(), codecs, depth + 1),
            codecs);
      }
      return out;
    }
    if (type instanceof CustomType) {
      throw new BadValue(at + " is of the custom type " + cql(type) + ", which GateRook doesn't bind.");
    }
    return scalar(type, value, at);
  }

  private static Object element(DataType type, JsonNode value, String at, CodecRegistry codecs, int depth)
      throws BadValue {
    if (value == null || value.isNull()) {
      throw new BadValue(at + " is null: a collection holds no nulls.");
    }
    return java(type, value, at, codecs, depth + 1);
  }

  private static TupleValue set(TupleValue t, int i, DataType type, Object v, CodecRegistry codecs) {
    TypeCodec<Object> codec = codecs.codecFor(type);
    return v == null ? t.setToNull(i) : t.set(i, v, codec);
  }

  private static UdtValue set(UdtValue u, int i, DataType type, Object v, CodecRegistry codecs) {
    TypeCodec<Object> codec = codecs.codecFor(type);
    return v == null ? u.setToNull(i) : u.set(i, v, codec);
  }

  private static JsonNode array(JsonNode value, String at, DataType type) throws BadValue {
    if (!value.isArray()) {
      throw new BadValue(at + " needs an array for " + cql(type) + ".");
    }
    return value;
  }

  private static Map<Object, Object> map(MapType type, JsonNode value, String at, CodecRegistry codecs, int depth)
      throws BadValue {
    Map<Object, Object> out = new LinkedHashMap<>();
    if (value.isObject()) {
      for (Map.Entry<String, JsonNode> e : value.properties()) {
        String key = at + "[" + quoteKey(e.getKey()) + "]";
        Object k = java(type.getKeyType(), keyNode(type.getKeyType(), e.getKey()), key, codecs, depth + 1);
        out.put(k, element(type.getValueType(), e.getValue(), key, codecs, depth));
      }
      return out;
    }
    if (value.isArray()) {
      int i = 0;
      for (JsonNode pair : value) {
        String key = at + "[" + i++ + "]";
        if (!pair.isArray() || pair.size() != 2) {
          throw new BadValue(key + " needs [key, value] for " + cql(type) + ".");
        }
        out.put(element(type.getKeyType(), pair.get(0), key, codecs, depth),
            element(type.getValueType(), pair.get(1), key, codecs, depth));
      }
      return out;
    }
    throw new BadValue(at + " needs an object (or an array of [key, value]) for " + cql(type) + ".");
  }

  private static String quoteKey(String key) {
    return "\"" + (key.length() > 40 ? key.substring(0, 39) + "…" : key) + "\"";
  }

  /** A JSON object's key as the JSON value the key type reads: a number for numeric keys, else the text. */
  private static JsonNode keyNode(DataType keyType, String key) {
    if (keyType.equals(DataTypes.BOOLEAN)) {
      return "true".equals(key) ? F.booleanNode(true) : "false".equals(key) ? F.booleanNode(false)
          : F.stringNode(key);
    }
    return F.stringNode(key);
  }

  private static Object scalar(DataType type, JsonNode v, String at) throws BadValue {
    if (type.equals(DataTypes.TEXT)) {
      return string(v, at, type);
    }
    if (type.equals(DataTypes.ASCII)) {
      String s = string(v, at, type);
      if (!s.chars().allMatch(c -> c < 0x80)) {
        throw new BadValue(at + " holds characters outside ASCII, which an ascii column can't store.");
      }
      return s;
    }
    if (type.equals(DataTypes.INT)) {
      return (int) integer(v, at, type, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }
    if (type.equals(DataTypes.SMALLINT)) {
      return (short) integer(v, at, type, Short.MIN_VALUE, Short.MAX_VALUE);
    }
    if (type.equals(DataTypes.TINYINT)) {
      return (byte) integer(v, at, type, Byte.MIN_VALUE, Byte.MAX_VALUE);
    }
    if (type.equals(DataTypes.BIGINT) || type.equals(DataTypes.COUNTER)) {
      return integer(v, at, type, Long.MIN_VALUE, Long.MAX_VALUE);
    }
    if (type.equals(DataTypes.VARINT)) {
      return bigInteger(v, at, type);
    }
    if (type.equals(DataTypes.DECIMAL)) {
      return decimal(v, at, type);
    }
    if (type.equals(DataTypes.DOUBLE)) {
      return floating(v, at, type);
    }
    if (type.equals(DataTypes.FLOAT)) {
      double d = floating(v, at, type);
      if (Double.isFinite(d) && Math.abs(d) > Float.MAX_VALUE) {
        throw new BadValue(at + " is too large for a float.");
      }
      return (float) d;
    }
    if (type.equals(DataTypes.BOOLEAN)) {
      if (!v.isBoolean()) {
        throw new BadValue(at + " needs true or false for boolean.");
      }
      return v.booleanValue();
    }
    if (type.equals(DataTypes.TIMESTAMP)) {
      return timestamp(v, at);
    }
    if (type.equals(DataTypes.DATE)) {
      try {
        return LocalDate.parse(string(v, at, type));
      } catch (DateTimeException e) {
        throw new BadValue(at + " needs a date such as 2026-11-11.");
      }
    }
    if (type.equals(DataTypes.TIME)) {
      try {
        return LocalTime.parse(string(v, at, type));
      } catch (DateTimeException e) {
        throw new BadValue(at + " needs a time such as 13:45:30.123.");
      }
    }
    if (type.equals(DataTypes.UUID) || type.equals(DataTypes.TIMEUUID)) {
      String s = string(v, at, type);
      if (!UUID_TEXT.matcher(s).matches()) {
        throw new BadValue(at + " needs a UUID such as 123e4567-e89b-12d3-a456-426614174000.");
      }
      UUID u = UUID.fromString(s);
      if (type.equals(DataTypes.TIMEUUID) && u.version() != 1) {
        throw new BadValue(at + " needs a time-based (version 1) UUID for timeuuid.");
      }
      return u;
    }
    if (type.equals(DataTypes.BLOB)) {
      String s = string(v, at, type);
      if (!HEX.matcher(s).matches()) {
        throw new BadValue(at + " needs bytes as 0x and an even number of hex digits.");
      }
      return ByteBuffer.wrap(HexFormat.of().parseHex(s, 2, s.length()));
    }
    if (type.equals(DataTypes.INET)) {
      String s = string(v, at, type);
      try {
        if (!IP.matcher(s).matches()) {
          throw new IllegalArgumentException(s);
        }
        return InetAddress.ofLiteral(s);
      } catch (IllegalArgumentException e) {
        throw new BadValue(at + " needs an IP address such as 10.0.4.12; names aren't looked up.");
      }
    }
    if (type.equals(DataTypes.DURATION)) {
      try {
        return CqlDuration.from(string(v, at, type));
      } catch (RuntimeException e) {
        throw new BadValue(at + " needs a duration such as 1h30m or PT1H30M.");
      }
    }
    throw new BadValue(at + " is of the type " + cql(type) + ", which GateRook doesn't bind.");
  }

  private static String string(JsonNode v, String at, DataType type) throws BadValue {
    if (!v.isString()) {
      throw new BadValue(at + " needs a string for " + cql(type) + ".");
    }
    return v.asString();
  }

  private static long integer(JsonNode v, String at, DataType type, long min, long max) throws BadValue {
    long n;
    if (v.isIntegralNumber() && v.canConvertToLong()) {
      n = v.longValue();
    } else if (v.isString() && INTEGER.matcher(v.asString()).matches() && v.asString().length() <= 20) {
      try {
        n = Long.parseLong(v.asString());
      } catch (NumberFormatException e) {
        throw new BadValue(at + " is outside the range of " + cql(type) + ".");
      }
    } else {
      throw new BadValue(at + " needs an integer for " + cql(type) + ".");
    }
    if (n < min || n > max) {
      throw new BadValue(at + " is outside the range of " + cql(type) + ".");
    }
    return n;
  }

  private static BigInteger bigInteger(JsonNode v, String at, DataType type) throws BadValue {
    if (v.isIntegralNumber()) {
      return v.bigIntegerValue();
    }
    if (v.isString() && INTEGER.matcher(v.asString()).matches()) {
      return new BigInteger(v.asString());
    }
    throw new BadValue(at + " needs an integer, or its digits as a string, for " + cql(type) + ".");
  }

  private static BigDecimal decimal(JsonNode v, String at, DataType type) throws BadValue {
    if (v.isNumber()) {
      if (JsonNumbers.notFinite(v)) {
        throw new BadValue(at + " isn't a finite number.");
      }
      return v.decimalValue();
    }
    if (v.isString() && v.asString().length() <= 1_000) {
      try {
        return new BigDecimal(v.asString());
      } catch (NumberFormatException e) {
        throw new BadValue(at + " isn't a decimal number.");
      }
    }
    throw new BadValue(at + " needs a number, or its text such as \"12.30\", for " + cql(type) + ".");
  }

  private static double floating(JsonNode v, String at, DataType type) throws BadValue {
    if (v.isNumber()) {
      return v.doubleValue();
    }
    if (v.isString()) {
      switch (v.asString()) {
        case "NaN" -> {
          return Double.NaN;
        }
        case "Infinity" -> {
          return Double.POSITIVE_INFINITY;
        }
        case "-Infinity" -> {
          return Double.NEGATIVE_INFINITY;
        }
        default -> {
          // Not one of the three: refused below.
        }
      }
    }
    throw new BadValue(at + " needs a number for " + cql(type) + ".");
  }

  private static Instant timestamp(JsonNode v, String at) throws BadValue {
    if (v.isIntegralNumber() && v.canConvertToLong()) {
      return Instant.ofEpochMilli(v.longValue());
    }
    if (v.isString()) {
      String s = v.asString();
      try {
        return Instant.parse(s);
      } catch (DateTimeException e) {
        try {
          return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeException e2) {
          // Neither form: refused below.
        }
      }
    }
    throw new BadValue(at + " needs a timestamp such as 2026-11-11T09:30:00Z, or milliseconds since 1970.");
  }
}
