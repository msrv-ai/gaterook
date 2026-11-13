package com.gaterook.connectors.aws;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The JSON of awsJson1_0, awsJson1_1 and restJson1 bodies, both ways, guided by the shapes: canonical parameters
 * ({@link ApiParams}) to the wire — timestamps as epoch seconds unless the model says otherwise, restJson1 member
 * names from {@code jsonName} — and an answer back to the model's names with timestamps as ISO-8601 instants. A
 * member the model doesn't know (a newer service) passes through unchanged.
 */
final class ApiJson {

  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)
      .withLocale(Locale.ROOT);

  private ApiJson() {}

  /** {@code value} of {@code shape} as the wire carries it; {@code rest}: restJson1 ({@code jsonName} applies). */
  static JsonNode write(ApiModel m, JsonNode shape, JsonNode value, boolean rest, String format) {
    switch (ApiModel.type(shape)) {
      case "structure", "union" -> {
        ObjectNode out = F.objectNode();
        for (Map.Entry<String, JsonNode> e : ApiModel.members(shape)) {
          JsonNode v = value.get(e.getKey());
          if (v == null || v.isNull()) {
            continue;
          }
          JsonNode member = e.getValue();
          String name = rest && member.hasNonNull("json") ? member.get("json").asString() : e.getKey();
          out.set(name, write(m, m.shape(member.path("s").asString()), v, rest, member.path("ts").asString(null)));
        }
        return out;
      }
      case "list" -> {
        ArrayNode out = F.arrayNode();
        JsonNode member = shape.path("member");
        JsonNode target = m.shape(member.path("s").asString());
        for (JsonNode item : value) {
          out.add(write(m, target, item, rest, member.path("ts").asString(null)));
        }
        return out;
      }
      case "map" -> {
        ObjectNode out = F.objectNode();
        JsonNode member = shape.path("value");
        JsonNode target = m.shape(member.path("s").asString());
        for (Map.Entry<String, JsonNode> e : value.properties()) {
          out.set(e.getKey(), write(m, target, e.getValue(), rest, member.path("ts").asString(null)));
        }
        return out;
      }
      case "timestamp" -> {
        Instant t = Instant.parse(value.asString());
        return switch (format != null ? format : shape.path("ts").asString("epoch-seconds")) {
          case "date-time" -> F.stringNode(t.toString());
          case "http-date" -> F.stringNode(HTTP_DATE.format(t));
          default -> F.numberNode(epochSeconds(t));
        };
      }
      default -> {
        return value;
      }
    }
  }

  /** Epoch seconds with the fraction the instant has (milliseconds at most, as AWS reads them). */
  static BigDecimal epochSeconds(Instant t) {
    return BigDecimal.valueOf(t.toEpochMilli()).movePointLeft(3).stripTrailingZeros();
  }

  /** An answer's {@code value} of {@code shape} under the model's member names, timestamps as ISO-8601. */
  static JsonNode read(ApiModel m, JsonNode shape, JsonNode value, boolean rest) {
    if (value == null || value.isNull()) {
      return value;
    }
    switch (ApiModel.type(shape)) {
      case "structure", "union" -> {
        if (!value.isObject()) {
          return value;
        }
        ObjectNode out = F.objectNode();
        Map<String, String> byWire = new java.util.HashMap<>();
        for (Map.Entry<String, JsonNode> e : ApiModel.members(shape)) {
          JsonNode member = e.getValue();
          byWire.put(rest && member.hasNonNull("json") ? member.get("json").asString() : e.getKey(), e.getKey());
        }
        for (Map.Entry<String, JsonNode> e : value.properties()) {
          String name = byWire.get(e.getKey());
          if (name == null) {
            if (!e.getKey().equals("__type")) {
              out.set(e.getKey(), e.getValue());
            }
            continue;
          }
          JsonNode member = shape.path("m").get(name);
          out.set(name, read(m, m.shape(member.path("s").asString()), e.getValue(), rest));
        }
        return out;
      }
      case "list" -> {
        if (!value.isArray()) {
          return value;
        }
        ArrayNode out = F.arrayNode();
        JsonNode target = m.shape(shape.path("member").path("s").asString());
        value.forEach(item -> out.add(read(m, target, item, rest)));
        return out;
      }
      case "map" -> {
        if (!value.isObject()) {
          return value;
        }
        ObjectNode out = F.objectNode();
        JsonNode target = m.shape(shape.path("value").path("s").asString());
        value.properties().forEach(e -> out.set(e.getKey(), read(m, target, e.getValue(), rest)));
        return out;
      }
      case "timestamp" -> {
        return timestamp(value);
      }
      default -> {
        return value;
      }
    }
  }

  /** A timestamp of an answer (epoch seconds, ISO-8601 or an HTTP date) as an ISO-8601 instant. */
  static JsonNode timestamp(JsonNode value) {
    try {
      if (value.isNumber()) {
        BigDecimal s = value.decimalValue();
        return F.stringNode(Instant.ofEpochSecond(s.longValue(), s.remainder(BigDecimal.ONE).movePointRight(9)
            .intValue()).toString());
      }
      if (value.isString()) {
        String s = value.asString().strip();
        if (!s.isEmpty() && Character.isDigit(s.charAt(0)) && s.indexOf('-') < 0) {
          return timestamp(F.numberNode(new BigDecimal(s)));
        }
        if (!s.isEmpty() && Character.isLetter(s.charAt(0))) {
          return F.stringNode(Instant.from(HTTP_DATE.parse(s)).toString());
        }
        return F.stringNode(java.time.OffsetDateTime.parse(s).toInstant().toString());
      }
    } catch (RuntimeException e) {
      // A value that isn't a time AWS writes stays as it came.
    }
    return value;
  }
}
