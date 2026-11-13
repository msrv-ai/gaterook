package com.gaterook.connectors.cosmosdb;

import java.math.BigInteger;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * JSON values going to and coming from Cosmos DB. Cosmos DB stores numbers as 64-bit floats and nests at most 128
 * levels; what agents send is checked against both. What comes back is bounded for the agent: strings longer than the
 * call's cell size are cut and say how long they were.
 */
final class CosmosValues {

  /** Cosmos DB's deepest nesting of objects and arrays. */
  static final int MAX_DEPTH = 128;
  /** Nesting kept in an answer; deeper values become a marker. */
  static final int MAX_SHOWN_DEPTH = 64;
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private CosmosValues() {}

  /** Why {@code v} can't be sent to Cosmos DB as a value, or {@code null}. */
  static String problem(JsonNode v) {
    return problem(v, 0);
  }

  private static String problem(JsonNode v, int depth) {
    if (v == null || v.isMissingNode()) {
      return "is missing";
    }
    if (depth > MAX_DEPTH) {
      return "nests deeper than " + MAX_DEPTH + " levels";
    }
    if (v.isNumber() && !Double.isFinite(v.asDouble())) {
      return "is a number Cosmos DB can't store (NaN, infinite or beyond a 64-bit float)";
    }
    if (v.isContainer()) {
      for (JsonNode child : v) {
        String p = problem(child, depth + 1);
        if (p != null) {
          return p;
        }
      }
    }
    return null;
  }

  /** 2^53: the largest integer from which every smaller one is exact as a 64-bit float. */
  static final BigInteger MAX_EXACT = BigInteger.ONE.shiftLeft(53);

  /** Whether {@code v} is an integer that a 64-bit float may not keep exactly: beyond ±2^53. */
  static boolean inexactInteger(JsonNode v) {
    return v.isIntegralNumber() && v.bigIntegerValue().abs().compareTo(MAX_EXACT) > 0;
  }

  /** Whether {@code v} holds an integer that a 64-bit float doesn't keep exactly. */
  static boolean inexact(JsonNode v) {
    if (v == null) {
      return false;
    }
    if (v.isIntegralNumber()) {
      return inexactInteger(v);
    }
    if (v.isContainer()) {
      for (JsonNode child : v) {
        if (inexact(child)) {
          return true;
        }
      }
    }
    return false;
  }

  /** A copy of {@code v} for the agent: strings cut to {@code maxChars}, nesting to {@link #MAX_SHOWN_DEPTH}. */
  static JsonNode shown(JsonNode v, int maxChars) {
    return shown(v, maxChars, 0);
  }

  private static JsonNode shown(JsonNode v, int maxChars, int depth) {
    if (v == null) {
      return F.nullNode();
    }
    if (v.isString()) {
      return text(v.asString(), maxChars);
    }
    if (!v.isContainer()) {
      return v;
    }
    if (depth >= MAX_SHOWN_DEPTH) {
      return F.stringNode("‹nested deeper than " + MAX_SHOWN_DEPTH + " levels›");
    }
    if (v.isArray()) {
      ArrayNode out = F.arrayNode();
      for (JsonNode child : v) {
        out.add(shown(child, maxChars, depth + 1));
      }
      return out;
    }
    ObjectNode out = F.objectNode();
    for (Map.Entry<String, JsonNode> e : v.properties()) {
      out.set(e.getKey(), shown(e.getValue(), maxChars, depth + 1));
    }
    return out;
  }

  static JsonNode text(String v, int max) {
    if (max <= 0 || v.length() <= max) {
      return F.stringNode(v);
    }
    int end = Character.isHighSurrogate(v.charAt(max - 1)) ? max - 1 : max;
    return F.stringNode(v.substring(0, end) + "…‹" + v.length() + " characters›");
  }
}
