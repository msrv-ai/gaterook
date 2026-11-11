package ai.msrv.gaterook.connectors.spanner;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Reads the answer of {@code executeStreamingSql} as it arrives: Google's REST API sends a JSON array of
 * {@code PartialResultSet} messages, a gRPC gateway (Spanner's emulator) one object per message, each in
 * {@code {"result": …}} or {@code {"error": …}}. The values of all messages form the rows in order, {@code width} per
 * row; a message with {@code chunkedValue} split its last value, which the next message's first value completes
 * (strings join, lists join with their boundary elements merged the same way). Each complete row goes to {@link Rows};
 * when it answers {@code false} the reading stops, and the caller closes the answer.
 */
final class ResultStream {

  /** Gets each complete row; answers whether to read on. */
  interface Rows {
    boolean row(List<JsonNode> values);
  }

  /** An error inside the stream: its HTTP status and Google's error object. */
  static final class StreamError extends Exception {

    private final transient JsonNode error;
    private final int status;

    StreamError(int status, JsonNode error) {
      super(error.path("message").asString(""), null, false, false);
      this.status = status;
      this.error = error;
    }

    int status() {
      return status;
    }

    JsonNode error() {
      return error;
    }
  }

  /** Reads one message at a time from the answer: the messages after it aren't trailing tokens. */
  private static final JsonMapper JSON = JsonMapper.builder()
      .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .build();
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private final Rows rows;
  private final List<JsonNode> pending = new ArrayList<>();
  private JsonNode metadata;
  private JsonNode stats;
  private int width = -1;
  private boolean chunked;
  private boolean stopped;
  private long messages;

  ResultStream(Rows rows) {
    this.rows = rows;
  }

  /** The result's metadata ({@code rowType}, {@code transaction}), or {@code null} before the first message. */
  JsonNode metadata() {
    return metadata;
  }

  /** The last statistics a message carried ({@code rowCountExact} of DML, a query's plan in PLAN mode), or null. */
  JsonNode stats() {
    return stats;
  }

  /** Whether the reading stopped before the end of the answer, because {@link Rows} said so. */
  boolean stopped() {
    return stopped;
  }

  void read(InputStream in) throws IOException, StreamError {
    try (JsonParser p = JSON.createParser(in)) {
      JsonToken t = p.nextToken();
      if (t == null) {
        throw new IOException("Spanner's answer is empty");
      }
      boolean array = t == JsonToken.START_ARRAY;
      if (array) {
        t = p.nextToken();
      }
      while (t != null && !(array && t == JsonToken.END_ARRAY)) {
        if (t != JsonToken.START_OBJECT) {
          throw new IOException("Spanner's answer holds something other than messages");
        }
        JsonNode message = JSON.readTree(p);
        if (!accept(message)) {
          stopped = true;
          return;
        }
        t = p.nextToken();
      }
    }
    if (!pending.isEmpty() || chunked) {
      throw new IOException("Spanner's answer ended inside a row");
    }
    if (messages == 0) {
      throw new IOException("Spanner's answer has no messages");
    }
  }

  private boolean accept(JsonNode envelope) throws StreamError, IOException {
    if (envelope.has("error")) {
      JsonNode error = envelope.get("error");
      int code = error.path("code").asInt(500);
      // Google's REST APIs put the HTTP status in code; a gRPC gateway puts gRPC's number (under 100).
      throw new StreamError(code >= 100 ? code : SpannerApi.httpStatus(SpannerApi.statusName(error)), error);
    }
    JsonNode m = envelope.has("result") ? envelope.get("result") : envelope;
    messages++;
    if (metadata == null && m.has("metadata")) {
      metadata = m.get("metadata");
      width = metadata.path("rowType").path("fields").size();
    }
    if (m.has("stats")) {
      stats = m.get("stats");
    }
    JsonNode values = m.path("values");
    if (!values.isArray() || values.isEmpty()) {
      return true;
    }
    if (width <= 0) {
      throw new IOException("Spanner sent values before the result's columns");
    }
    int from = 0;
    if (chunked) {
      pending.add(merge(pending.removeLast(), values.get(0)));
      from = 1;
    }
    for (int i = from; i < values.size(); i++) {
      pending.add(values.get(i));
    }
    chunked = m.path("chunkedValue").asBoolean(false);
    // The last value of a chunked message isn't complete yet: a row that ends with it waits for the next message.
    int complete = pending.size() - (chunked ? 1 : 0);
    int used = 0;
    while (complete - used >= width) {
      boolean more = rows.row(List.copyOf(pending.subList(used, used + width)));
      used += width;
      if (!more) {
        return false;
      }
    }
    pending.subList(0, used).clear();
    return true;
  }

  /** Joins a value split across two messages: strings concatenate; lists concatenate, their boundary merged. */
  static JsonNode merge(JsonNode a, JsonNode b) throws IOException {
    if (a.isString() && b.isString()) {
      return F.stringNode(a.asString() + b.asString());
    }
    if (a.isArray() && b.isArray()) {
      ArrayNode out = ((ArrayNode) a).deepCopy();
      int from = 0;
      if (!out.isEmpty() && !b.isEmpty()) {
        JsonNode last = out.get(out.size() - 1);
        JsonNode first = b.get(0);
        if ((last.isString() && first.isString()) || (last.isArray() && first.isArray())) {
          out.set(out.size() - 1, merge(last, first));
          from = 1;
        }
      }
      for (int i = from; i < b.size(); i++) {
        out.add(b.get(i));
      }
      return out;
    }
    throw new IOException("Spanner split a value that can't be joined");
  }
}
