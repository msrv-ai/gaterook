package ai.msrv.gaterook.connectors.mongodb;

import com.mongodb.MongoExecutionTimeoutException;
import com.mongodb.client.MongoCursor;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bson.BsonDocument;
import org.bson.RawBsonDocument;
import org.bson.codecs.BsonDocumentCodec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads a cursor within the call's limits (report 05 §3.1): at most {@code n} documents (the query asked for
 * {@code n + 1}: one more means {@code doc_limit}), at most the response budget — counted on what the agent gets, the
 * document in the structured content and its line of text — then {@code size_limit} and the cursor is closed (the
 * driver kills it on the server). Long strings and binaries are cut before conversion; the server's
 * {@code maxTimeMS} after some documents ends the page with {@code timeout} instead of an error.
 */
final class DocReader {

  /** Room for the result's own fields around the documents. */
  static final int OVERHEAD = 1_024;

  /** One page of documents. */
  record Page(List<JsonNode> documents, List<String> lines, String truncatedReason, long bsonBytes, int cutValues,
      long elapsedMs) {

    int returned() {
      return documents.size();
    }
  }

  private DocReader() {}

  static Page read(MongoCursor<RawBsonDocument> cursor, int n, long maxBytes, int maxChars, long startNanos) {
    List<JsonNode> docs = new ArrayList<>();
    List<String> lines = new ArrayList<>();
    String reason = null;
    long bson = 0;
    long used = OVERHEAD;
    int cut = 0;
    BsonDocumentCodec codec = new BsonDocumentCodec();
    try {
      while (cursor.hasNext()) {
        RawBsonDocument raw = cursor.next();
        if (docs.size() == n) {
          reason = "doc_limit";
          break;
        }
        int size = raw.getByteBuffer().remaining();
        BsonDocument doc = raw.decode(codec);
        cut += Ejson.shrink(doc, maxChars);
        String json = Ejson.relaxed(doc);
        long cost = 2L * json.getBytes(StandardCharsets.UTF_8).length + 2;
        if (used + cost > maxBytes) {
          reason = "size_limit";
          break;
        }
        used += cost;
        bson += size;
        docs.add(Ejson.JSON.readTree(json));
        lines.add(json);
      }
    } catch (MongoExecutionTimeoutException e) {
      if (docs.isEmpty()) {
        throw e;
      }
      reason = "timeout";
    } finally {
      cursor.close();
    }
    return new Page(List.copyOf(docs), List.copyOf(lines), reason, bson, cut, (System.nanoTime() - startNanos)
        / 1_000_000);
  }

  /**
   * The answer of the output schema and its text: a line saying what came back, then one document per line.
   *
   * @param connection the connection's name, for the text
   */
  static ToolResult result(String tool, String connection, String database, String collection, Page page,
      String readPreference, List<String> warnings, int limit) {
    ObjectNode s = JsonNodeFactory.instance.objectNode();
    s.put("database", database);
    s.put("collection", collection);
    ArrayNode docs = s.putArray("documents");
    page.documents().forEach(docs::add);
    s.put("returned", page.returned());
    s.put("truncated", page.truncatedReason() != null);
    if (page.truncatedReason() == null) {
      s.putNull("truncatedReason");
    } else {
      s.put("truncatedReason", page.truncatedReason());
    }
    s.put("bytes", page.bsonBytes());
    s.put("elapsedMs", page.elapsedMs());
    s.put("readPreference", readPreference);
    List<String> all = new ArrayList<>(warnings);
    if (page.cutValues() > 0) {
      all.add(page.cutValues() + (page.cutValues() == 1 ? " value was" : " values were") + " cut: long strings to "
          + "their first characters, binary data to its size. Project the fields you need.");
    }
    ArrayNode w = s.putArray("warnings");
    all.forEach(w::add);

    NumberFormat numbers = NumberFormat.getIntegerInstance(Locale.US);
    StringBuilder text = new StringBuilder();
    int n = page.returned();
    text.append(numbers.format(n)).append(n == 1 ? " document" : " documents").append(" from ").append(database)
        .append('.').append(collection).append(" on ").append(connection).append(" · ").append(page.elapsedMs())
        .append(" ms");
    if (page.truncatedReason() != null) {
      text.append(switch (page.truncatedReason()) {
        case "doc_limit" -> " · more match: cut at " + numbers.format(limit) + (MongoTools.FIND.equals(tool)
            ? ". Narrow the filter, or call again with skip " + numbers.format(limit) + "." : ". Narrow the pipeline.");
        case "size_limit" -> " · cut by the size limit. Project fewer fields or ask for fewer documents.";
        default -> " · the time limit stopped the query: these are the documents read so far.";
      });
    }
    for (String line : page.lines()) {
      text.append('\n').append(line);
    }
    for (String warning : all) {
      text.append("\nNote: ").append(warning);
    }
    ToolResult r = ToolResult.json(s, text.toString());
    return page.truncatedReason() == null ? r : r.truncated(page.truncatedReason());
  }
}
