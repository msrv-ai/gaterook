package ai.msrv.gaterook.connectors.firebase;

import ai.msrv.gaterook.connector.api.ConnectorException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One JSON answer read token by token within two caps, so that a large one is never held in memory: the bytes read
 * from the wire, and the bytes of what the agent would get — strings already cut to the call's cell size. Past either
 * cap the reading stops ({@link TooLarge}); the caller refuses the answer with advice to narrow it.
 */
final class BoundedJson {

  /** Nesting kept: deeper than the Realtime Database nests (32); deeper values become a marker. */
  static final int MAX_DEPTH = 64;
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  /** The answer is larger than a cap. */
  static final class TooLarge extends Exception {
    private static final long serialVersionUID = 1L;
    final long limit;

    TooLarge(long limit) {
      super(null, null, false, false);
      this.limit = limit;
    }
  }

  private final long maxOutput;
  private final int maxChars;
  private long used;

  private BoundedJson(long maxOutput, int maxChars) {
    this.maxOutput = maxOutput;
    this.maxChars = maxChars;
  }

  /**
   * The JSON value of {@code in}; an empty body is {@code null}.
   *
   * @param maxRaw    bytes read from the wire at most
   * @param maxOutput bytes of the value as the agent would get it at most
   * @param what      who answers, for messages
   */
  static JsonNode read(GoogleHttp.Capped in, long maxRaw, long maxOutput, int maxChars, String what)
      throws TooLarge, ConnectorException {
    in.cap(maxRaw);
    BoundedJson reader = new BoundedJson(maxOutput, maxChars);
    try (JsonParser p = GoogleHttp.JSON.createParser(in)) {
      JsonToken t = p.nextToken();
      if (t == null) {
        return F.nullNode();
      }
      JsonNode v = reader.value(p, t, 0);
      if (p.nextToken() != null) {
        throw ConnectorException.resourceError(what + " answered with more than one JSON value.", null);
      }
      return v;
    } catch (JacksonException e) {
      if (in.exceeded) {
        throw new TooLarge(maxRaw);
      }
      throw ConnectorException.resourceError(what + " answered with something that isn't JSON.", null);
    }
  }

  private JsonNode value(JsonParser p, JsonToken t, int depth) throws TooLarge {
    switch (t) {
      case START_OBJECT -> {
        if (depth >= MAX_DEPTH) {
          p.skipChildren();
          return deep();
        }
        count(2);
        ObjectNode o = F.objectNode();
        JsonToken next;
        while ((next = p.nextToken()) == JsonToken.PROPERTY_NAME) {
          String key = p.currentName();
          count(Names.utf8Length(key) + 4L);
          o.set(key, value(p, p.nextToken(), depth + 1));
        }
        return o;
      }
      case START_ARRAY -> {
        if (depth >= MAX_DEPTH) {
          p.skipChildren();
          return deep();
        }
        count(2);
        ArrayNode a = F.arrayNode();
        JsonToken next;
        while ((next = p.nextToken()) != JsonToken.END_ARRAY && next != null) {
          count(1);
          a.add(value(p, next, depth + 1));
        }
        return a;
      }
      case VALUE_STRING -> {
        JsonNode s = FirestoreValues.text(p.getString(), maxChars);
        count(Names.utf8Length(s.asString()) + 2L);
        return s;
      }
      case VALUE_NUMBER_INT -> {
        JsonNode n = p.getNumberType() == JsonParser.NumberType.BIG_INTEGER ? F.numberNode(p.getBigIntegerValue())
            : F.numberNode(p.getLongValue());
        count(n.toString().length());
        return n;
      }
      case VALUE_NUMBER_FLOAT -> {
        JsonNode n = F.numberNode(p.getDoubleValue());
        count(n.toString().length());
        return n;
      }
      case VALUE_TRUE, VALUE_FALSE -> {
        count(5);
        return F.booleanNode(t == JsonToken.VALUE_TRUE);
      }
      default -> {
        count(4);
        return F.nullNode();
      }
    }
  }

  private JsonNode deep() throws TooLarge {
    JsonNode marker = F.stringNode("‹nested deeper than " + MAX_DEPTH + " levels›");
    count(40);
    return marker;
  }

  private void count(long n) throws TooLarge {
    used += n;
    if (used > maxOutput) {
      throw new TooLarge(maxOutput);
    }
  }
}
