package com.gaterook.connectors.cosmosdb;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * A partition key value as Cosmos DB's {@code x-ms-documentdb-partitionkey} header carries it: a JSON array of one
 * value, or of up to three for a hierarchical key, each a string, a number, {@code true}/{@code false} or
 * {@code null}. A number must be exact as the 64-bit floating point Cosmos DB stores; a string is at most 2,048 bytes,
 * without control characters. The header escapes every character outside ASCII ({@code é} as {@code \}{@code u00E9}),
 * as HTTP headers take ASCII only.
 */
record PartitionKey(ArrayNode values) {

  /** Cosmos DB's longest partition key value, in UTF-8 bytes. */
  static final int MAX_VALUE_BYTES = 2_048;
  /** Levels of a hierarchical partition key. */
  static final int MAX_LEVELS = 3;
  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  private static final JsonMapper ASCII = JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The value an agent gave, or why it isn't one. */
  static PartitionKey of(JsonNode arg) throws Refusal {
    if (arg == null || arg.isMissingNode()) {
      throw Refusal.bad("Give partitionKey: the item's partition key value, such as \"c-42\" (an array of values for "
          + "a hierarchical key).", "Call cosmosdb_list_containers to see each container's partition key paths.");
    }
    ArrayNode values = F.arrayNode();
    if (arg.isArray()) {
      if (arg.isEmpty() || arg.size() > MAX_LEVELS) {
        throw Refusal.bad("partitionKey as an array holds one to three values, one for each level of a hierarchical "
            + "key.", null);
      }
      for (JsonNode v : arg) {
        values.add(value(v));
      }
    } else {
      values.add(value(arg));
    }
    return new PartitionKey(values);
  }

  private static JsonNode value(JsonNode v) throws Refusal {
    if (v.isNull() || v.isBoolean()) {
      return v;
    }
    if (v.isString()) {
      String s = v.asString();
      if (s.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
        throw Refusal.bad("A partition key value is at most " + MAX_VALUE_BYTES + " bytes.", null);
      }
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        if (c < 0x20 || c == 0x7f) {
          throw Refusal.bad("The partition key value holds a control character.", null);
        }
        if (Character.isHighSurrogate(c) ? i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(++i))
            : Character.isLowSurrogate(c)) {
          throw Refusal.bad("The partition key value holds a broken character.", null);
        }
      }
      return v;
    }
    if (v.isNumber()) {
      double d = v.asDouble();
      if (!Double.isFinite(d) || CosmosValues.inexactInteger(v)) {
        throw Refusal.bad("The partition key value " + shorten(v.toString()) + " isn't exact as a Cosmos DB number "
            + "(a 64-bit float): store such keys as strings.", null);
      }
      return v;
    }
    throw Refusal.bad("A partition key value is a string, a number, true, false or null, not "
        + (v.isObject() ? "an object" : "an array inside the array") + ".", null);
  }

  /** {@code ["c-42"]}, ASCII only. */
  String header() {
    return ASCII.writeValueAsString(values);
  }

  /** {@code ["c-42"]} as people read it. */
  String shown() {
    return JSON.writeValueAsString(values);
  }

  /**
   * The value of this key in {@code item} at the container's partition key {@code paths} ({@code /customerId},
   * {@code /address/zip}), or {@code null} when the item lacks one of them or a path isn't one GateRook reads.
   */
  static PartitionKey in(JsonNode item, List<String> paths) {
    ArrayNode values = F.arrayNode();
    for (String path : paths) {
      List<String> segments = segments(path);
      if (segments == null) {
        return null;
      }
      JsonNode v = item;
      for (String segment : segments) {
        v = v == null || !v.isObject() ? null : v.get(segment);
      }
      if (v == null || v.isMissingNode()) {
        return null;
      }
      values.add(v);
    }
    return new PartitionKey(values);
  }

  /** The property names of a partition key path, or {@code null} for one GateRook doesn't read. */
  static List<String> segments(String path) {
    if (path == null || !path.startsWith("/") || path.length() < 2) {
      return null;
    }
    List<String> out = new ArrayList<>();
    for (String raw : path.substring(1).split("/", -1)) {
      String s = raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"") ? raw.substring(1, raw.length() - 1)
          : raw;
      if (s.isEmpty() || s.indexOf('"') >= 0) {
        return null;
      }
      out.add(s);
    }
    return out;
  }

  /** Whether this key names the same values as {@code other}: numbers by their value. */
  boolean sameAs(PartitionKey other) {
    if (other == null || other.values.size() != values.size()) {
      return false;
    }
    for (int i = 0; i < values.size(); i++) {
      JsonNode a = values.get(i);
      JsonNode b = other.values.get(i);
      boolean same = a.isNumber() && b.isNumber() ? a.asDouble() == b.asDouble() : a.equals(b);
      if (!same) {
        return false;
      }
    }
    return true;
  }

  private static String shorten(String s) {
    return s.length() <= 40 ? s : s.substring(0, 39) + "…";
  }
}
