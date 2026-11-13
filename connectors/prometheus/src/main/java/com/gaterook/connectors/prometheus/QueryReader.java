package com.gaterook.connectors.prometheus;

import com.gaterook.connectors.support.OutputSanitizer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A walk over the answer of {@code /api/v1/query} or {@code /api/v1/query_range} as a stream: the envelope's facts,
 * then every series of {@code data.result} with its labels and its points ({@code value}, {@code values},
 * {@code histogram}, {@code histograms}), in whatever order the server wrote them. A {@link Visitor} decides which
 * series it reads; the others are counted and skipped without being kept. A body cut at the size limit ends the walk
 * where it stops ({@link Facts#partial}).
 */
final class QueryReader {

  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  /** Labels kept of one series; a server sends a few dozen at most. */
  static final int MAX_LABELS = 200;

  private QueryReader() {}

  /** What the walk is told about each series. */
  interface Visitor {
    /** Whether series {@code index} (from 0) is read: its labels and points, else only counted. */
    boolean wants(int index);

    void labels(int index, ObjectNode labels);

    /**
     * One point of a wanted series.
     *
     * @param histogram the native histogram ({@code count}, {@code sum}, {@code buckets}), or {@code null} for a float
     */
    void point(int index, String time, String value, JsonNode histogram);

    /** The number of points of series {@code index}, wanted or not, when its object ends. */
    void points(int index, long count);
  }

  /** What the envelope said, beside the series. */
  static final class Facts {
    String status = "";
    String errorType = "";
    String error = "";
    String resultType = "";
    final List<String> warnings = new ArrayList<>();
    final List<String> infos = new ArrayList<>();
    /** The {@code [time, value]} of a scalar or string result. */
    String scalarTime;
    String scalarValue;
    int series;
    /** The body ended before the answer did. */
    boolean partial;
    /** {@code data.result} was read to its end: a body cut later lost warnings at most. */
    boolean resultComplete;
  }

  /**
   * Walks {@code body}.
   *
   * @param cut whether the body is the start of a longer answer
   * @return the facts, or {@code null} when the body isn't JSON (and not merely cut)
   */
  static Facts walk(byte[] body, boolean cut, Visitor v, int maxCellChars) {
    Facts f = new Facts();
    try (JsonParser p = PromJson.JSON.createParser(body)) {
      if (p.nextToken() != JsonToken.START_OBJECT) {
        return null;
      }
      while (p.nextToken() == JsonToken.PROPERTY_NAME) {
        String name = p.currentName();
        JsonToken t = p.nextToken();
        switch (name) {
          case "status" -> f.status = text(p, t);
          case "errorType" -> f.errorType = text(p, t);
          case "error" -> f.error = text(p, t);
          case "warnings" -> strings(p, t, f.warnings);
          case "infos" -> strings(p, t, f.infos);
          case "data" -> data(p, t, f, v, maxCellChars);
          default -> p.skipChildren();
        }
      }
    } catch (JacksonException e) {
      if (!cut) {
        return null;
      }
      f.partial = true;
    }
    if (cut) {
      f.partial = true;
    }
    return f;
  }

  private static void data(JsonParser p, JsonToken t, Facts f, Visitor v, int maxCellChars) {
    if (t != JsonToken.START_OBJECT) {
      p.skipChildren();
      return;
    }
    while (p.nextToken() == JsonToken.PROPERTY_NAME) {
      String name = p.currentName();
      JsonToken vt = p.nextToken();
      if (name.equals("resultType")) {
        f.resultType = text(p, vt);
      } else if (name.equals("result") && vt == JsonToken.START_ARRAY) {
        result(p, f, v, maxCellChars);
      } else {
        p.skipChildren();
      }
    }
  }

  /** {@code [series…]} of a vector or matrix, or {@code [time, value]} of a scalar or string. */
  private static void result(JsonParser p, Facts f, Visitor v, int maxCellChars) {
    int position = 0;
    JsonToken t;
    while ((t = p.nextToken()) != JsonToken.END_ARRAY) {
      if (t == JsonToken.START_OBJECT) {
        series(p, f.series, v, maxCellChars);
        f.series++;
      } else if (t.isScalarValue()) {
        if (position == 0) {
          f.scalarTime = PromJson.time(scalarNode(p, t));
        } else if (position == 1) {
          f.scalarValue = PromJson.value(scalarNode(p, t));
        }
        position++;
      } else {
        p.skipChildren();
      }
    }
    f.resultComplete = true;
  }

  private static void series(JsonParser p, int index, Visitor v, int maxCellChars) {
    boolean wanted = v.wants(index);
    long count = 0;
    while (p.nextToken() == JsonToken.PROPERTY_NAME) {
      String name = p.currentName();
      JsonToken t = p.nextToken();
      switch (name) {
        case "metric" -> {
          if (wanted && t == JsonToken.START_OBJECT) {
            v.labels(index, labels(p, maxCellChars));
          } else {
            p.skipChildren();
          }
        }
        case "value", "histogram" -> {
          if (t == JsonToken.START_ARRAY) {
            count++;
            point(p, index, wanted, name.equals("histogram"), v);
          } else {
            p.skipChildren();
          }
        }
        case "values", "histograms" -> {
          if (t != JsonToken.START_ARRAY) {
            p.skipChildren();
            continue;
          }
          boolean histograms = name.equals("histograms");
          JsonToken pt;
          while ((pt = p.nextToken()) != JsonToken.END_ARRAY) {
            if (pt == JsonToken.START_ARRAY) {
              count++;
              point(p, index, wanted, histograms, v);
            } else {
              p.skipChildren();
            }
          }
        }
        default -> p.skipChildren();
      }
    }
    v.points(index, count);
  }

  /** One {@code [time, value]} or {@code [time, histogram]}, the parser just inside its array. */
  private static void point(JsonParser p, int index, boolean wanted, boolean histogram, Visitor v) {
    if (!wanted) {
      p.skipChildren();
      return;
    }
    String time = null;
    String value = null;
    JsonNode h = null;
    int position = 0;
    JsonToken t;
    while ((t = p.nextToken()) != JsonToken.END_ARRAY) {
      if (position == 0 && t.isScalarValue()) {
        time = PromJson.time(scalarNode(p, t));
      } else if (position == 1 && histogram && t == JsonToken.START_OBJECT) {
        h = histogram(p);
      } else if (position == 1 && t.isScalarValue()) {
        value = PromJson.value(scalarNode(p, t));
      } else {
        p.skipChildren();
      }
      position++;
    }
    v.point(index, time == null ? "" : time, value == null ? "" : value, h);
  }

  /** A native histogram: {@code count} and {@code sum} as written, {@code buckets} as {@code [rule, left, right, n]}. */
  private static JsonNode histogram(JsonParser p) {
    ObjectNode h = F.objectNode();
    while (p.nextToken() == JsonToken.PROPERTY_NAME) {
      String name = p.currentName();
      JsonToken t = p.nextToken();
      if ((name.equals("count") || name.equals("sum")) && t.isScalarValue()) {
        h.put(name, PromJson.value(scalarNode(p, t)));
      } else if (name.equals("buckets") && t == JsonToken.START_ARRAY) {
        var buckets = h.putArray("buckets");
        JsonToken bt;
        while ((bt = p.nextToken()) != JsonToken.END_ARRAY) {
          if (bt == JsonToken.START_ARRAY && buckets.size() < 1_000) {
            var bucket = buckets.addArray();
            JsonToken et;
            while ((et = p.nextToken()) != JsonToken.END_ARRAY) {
              if (et == JsonToken.VALUE_NUMBER_INT && bucket.isEmpty()) {
                bucket.add(p.getIntValue());
              } else if (et.isScalarValue()) {
                bucket.add(PromJson.value(scalarNode(p, et)));
              } else {
                p.skipChildren();
              }
            }
          } else {
            p.skipChildren();
          }
        }
      } else {
        p.skipChildren();
      }
    }
    return h;
  }

  /** A series' labels: names and values sanitized, values cut to the endpoint's cell size, at most 200 of them. */
  private static ObjectNode labels(JsonParser p, int maxCellChars) {
    ObjectNode labels = F.objectNode();
    while (p.nextToken() == JsonToken.PROPERTY_NAME) {
      String name = OutputSanitizer.sanitize(p.currentName());
      JsonToken t = p.nextToken();
      if (t.isScalarValue() && labels.size() < MAX_LABELS) {
        labels.put(name, cell(p.getValueAsString(""), maxCellChars));
      } else {
        p.skipChildren();
      }
    }
    return labels;
  }

  static String cell(String s, int maxCellChars) {
    String clean = OutputSanitizer.sanitize(s);
    int max = Math.max(16, maxCellChars);
    return clean.length() <= max ? clean : clean.substring(0, max - 1) + "…";
  }

  private static JsonNode scalarNode(JsonParser p, JsonToken t) {
    return switch (t) {
      case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> F.numberNode(new BigDecimal(p.getString()));
      case VALUE_STRING -> F.stringNode(p.getString());
      case VALUE_TRUE, VALUE_FALSE -> F.stringNode(p.getString());
      default -> F.nullNode();
    };
  }

  private static String text(JsonParser p, JsonToken t) {
    if (t.isScalarValue()) {
      return p.getValueAsString("");
    }
    p.skipChildren();
    return "";
  }

  private static void strings(JsonParser p, JsonToken t, List<String> out) {
    if (t != JsonToken.START_ARRAY) {
      p.skipChildren();
      return;
    }
    JsonToken e;
    while ((e = p.nextToken()) != JsonToken.END_ARRAY) {
      if (e == JsonToken.VALUE_STRING && out.size() < 20) {
        out.add(PrometheusApi.excerpt(p.getString()));
      } else {
        p.skipChildren();
      }
    }
  }
}
