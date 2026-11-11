package ai.msrv.gaterook.connectors.neo4j;

import ai.msrv.gaterook.connectors.support.JsonNumbers;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The parameters of a statement ({@code $name}) from JSON: strings, integers of 64 bits, floats, booleans, nulls,
 * lists and maps. Temporal and spatial values are built in Cypher from these ({@code date($day)},
 * {@code point({x: $x, y: $y})}). Values are what the agent sent; GateRook never puts them into the statement's text.
 */
final class Parameters {

  static final int MAX_PARAMETERS = 100;
  static final int MAX_DEPTH = 8;
  static final int MAX_VALUES = 10_000;
  static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

  private Parameters() {}

  /** Why {@code parameters} can't be passed, or {@code null}. */
  static String problem(JsonNode parameters) {
    if (parameters == null || parameters.isMissingNode() || parameters.isNull()) {
      return null;
    }
    if (!parameters.isObject()) {
      return "parameters must be an object such as {\"name\": \"Ada\"}.";
    }
    if (parameters.size() > MAX_PARAMETERS) {
      return "At most " + MAX_PARAMETERS + " parameters per statement.";
    }
    int[] count = {0};
    for (Map.Entry<String, JsonNode> e : parameters.properties()) {
      if (!NAME.matcher(e.getKey()).matches()) {
        return "A parameter needs a name of letters, digits and underscores, such as since (written $since).";
      }
      String problem = value(e.getValue(), 0, count);
      if (problem != null) {
        return "Parameter " + e.getKey() + " " + problem + ".";
      }
    }
    return null;
  }

  private static String value(JsonNode v, int depth, int[] count) {
    if (++count[0] > MAX_VALUES) {
      return "holds more than " + MAX_VALUES + " values";
    }
    if (depth > MAX_DEPTH) {
      return "is nested deeper than " + MAX_DEPTH + " levels";
    }
    if (v.isNumber()) {
      if (v.isIntegralNumber()) {
        return v.canConvertToLong() ? null : "is an integer beyond 64 bits; pass it as a string";
      }
      if (JsonNumbers.notFinite(v)) {
        return "isn't a finite number";
      }
      if (v.isBigDecimal()) {
        BigDecimal d = v.decimalValue();
        return JsonNumbers.expandable(d) && Double.isFinite(d.doubleValue()) ? null : "is a number beyond a double";
      }
      return null;
    }
    if (v.isArray()) {
      for (JsonNode e : v) {
        String problem = value(e, depth + 1, count);
        if (problem != null) {
          return problem;
        }
      }
      return null;
    }
    if (v.isObject()) {
      for (Map.Entry<String, JsonNode> e : v.properties()) {
        if (e.getKey().length() > 256) {
          return "has a map key longer than 256 characters";
        }
        String problem = value(e.getValue(), depth + 1, count);
        if (problem != null) {
          return problem;
        }
      }
      return null;
    }
    return v.isString() || v.isBoolean() || v.isNull() ? null : "isn't a JSON value Cypher takes";
  }

  /** The parameters for the driver; {@link #problem} found nothing. */
  static Map<String, Object> of(JsonNode parameters) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (parameters != null && parameters.isObject()) {
      for (Map.Entry<String, JsonNode> e : parameters.properties()) {
        out.put(e.getKey(), java(e.getValue()));
      }
    }
    return out;
  }

  private static Object java(JsonNode v) {
    if (v == null || v.isNull() || v.isMissingNode()) {
      return null;
    }
    if (v.isBoolean()) {
      return v.asBoolean();
    }
    if (v.isNumber()) {
      return v.isIntegralNumber() ? (Object) v.longValue() : (Object) v.doubleValue();
    }
    if (v.isString()) {
      return v.asString();
    }
    if (v.isArray()) {
      List<Object> list = new ArrayList<>();
      v.forEach(e -> list.add(java(e)));
      return list;
    }
    Map<String, Object> map = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> e : v.properties()) {
      map.put(e.getKey(), java(e.getValue()));
    }
    return map;
  }
}
