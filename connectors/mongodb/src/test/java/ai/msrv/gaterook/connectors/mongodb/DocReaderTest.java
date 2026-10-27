package ai.msrv.gaterook.connectors.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.MongoExecutionTimeoutException;
import com.mongodb.ServerAddress;
import com.mongodb.ServerCursor;
import com.mongodb.client.MongoCursor;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.bson.BsonBinary;
import org.bson.BsonDateTime;
import org.bson.BsonDecimal128;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonObjectId;
import org.bson.BsonString;
import org.bson.RawBsonDocument;
import org.bson.UuidRepresentation;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Golden relaxed Extended JSON and the page limits (report 05 §3.1, §8 {@code DocReaderTest}): Int64 beyond ±2^53,
 * Decimal128, dates before 1970, NaN, binary and UUID, values cut before conversion, {@code doc_limit},
 * {@code size_limit}, a time limit after some documents.
 */
class DocReaderTest {

  @Test
  void relaxedExtendedJsonKeepsEveryDigitAndType() {
    BsonDocument doc = new BsonDocument("_id", new BsonObjectId(new ObjectId("6717a1f2c3b4d5e6f7a8b9c0")))
        .append("total", new BsonDecimal128(Decimal128.parse("84.90")))
        .append("created_at", new BsonDateTime(1_761_136_181_000L))
        .append("before1970", new BsonDateTime(-315_619_200_000L))
        .append("big", new BsonInt64(9_007_199_254_740_993L))
        .append("negativeBig", new BsonInt64(-9_007_199_254_740_993L))
        .append("safe", new BsonInt64(9_007_199_254_740_992L))
        .append("small", new BsonInt32(42))
        .append("nan", new BsonDouble(Double.NaN))
        .append("ratio", new BsonDouble(0.5))
        .append("uuid", new BsonBinary(UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e"),
            UuidRepresentation.STANDARD));

    JsonNode out = page(List.of(doc), 10, 512 * 1024).documents().getFirst();

    assertThat(out.toString()).isEqualTo("{\"_id\":{\"$oid\":\"6717a1f2c3b4d5e6f7a8b9c0\"},"
        + "\"total\":{\"$numberDecimal\":\"84.90\"},\"created_at\":{\"$date\":\"2025-10-22T12:29:41Z\"},"
        + "\"before1970\":{\"$date\":{\"$numberLong\":\"-315619200000\"}},"
        + "\"big\":{\"$numberLong\":\"9007199254740993\"},\"negativeBig\":{\"$numberLong\":\"-9007199254740993\"},"
        + "\"safe\":9007199254740992,\"small\":42,\"nan\":{\"$numberDouble\":\"NaN\"},\"ratio\":0.5,"
        + "\"uuid\":{\"$binary\":{\"base64\":\"D4+tW9nLRp+hZXCGdyiVDg==\",\"subType\":\"04\"}}}");
  }

  @Test
  void longStringsAndBinariesAreCutBeforeConversion() {
    BsonDocument doc = new BsonDocument("note", new BsonString("x".repeat(20_000)))
        .append("blob", new BsonBinary(new byte[20_480]))
        .append("tiny", new BsonBinary(new byte[] {1, 2, 3}));

    DocReader.Page page = page(List.of(doc), 10, 512 * 1024);

    JsonNode out = page.documents().getFirst();
    assertThat(out.path("note").asString()).startsWith("x".repeat(8_192) + "…‹truncated 11,808 chars›")
        .hasSize(8_192 + "…‹truncated 11,808 chars›".length());
    assertThat(out.path("blob").asString()).isEqualTo("‹binary 20,480 bytes, subtype 00›");
    assertThat(out.at("/tiny/$binary/base64").asString()).isEqualTo("AQID");
    assertThat(page.cutValues()).isEqualTo(2);
  }

  @Test
  void oneMoreDocumentThanAskedMeansMoreMatch() {
    List<BsonDocument> docs = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      docs.add(new BsonDocument("i", new BsonInt32(i)));
    }

    DocReader.Page five = page(docs, 5, 512 * 1024);
    DocReader.Page all = page(docs, 6, 512 * 1024);

    assertThat(five.returned()).isEqualTo(5);
    assertThat(five.truncatedReason()).isEqualTo("doc_limit");
    assertThat(all.returned()).isEqualTo(6);
    assertThat(all.truncatedReason()).isNull();
  }

  @Test
  void theResponseBudgetCountsTheDocumentAndItsLine() {
    List<BsonDocument> docs = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      docs.add(new BsonDocument("pad", new BsonString("y".repeat(1_000))));
    }

    DocReader.Page page = page(docs, 1_000, 32 * 1024);

    // (32 KB − 1 KB overhead) / (2 × ~1 KB per document)
    assertThat(page.returned()).isBetween(14, 16);
    assertThat(page.truncatedReason()).isEqualTo("size_limit");
    ToolResult r = DocReader.result("mongo_find", "orders-mongo", "orders", "orders", page, "primary", List.of(),
        1_000);
    assertThat(r.truncated()).isTrue();
    assertThat(r.truncatedReason()).isEqualTo("size_limit");
    assertThat(r.structuredContent().path("truncatedReason").asString()).isEqualTo("size_limit");
    assertThat(r.content().getFirst().text()).startsWith(page.returned() + " documents from orders.orders on "
        + "orders-mongo").contains("cut by the size limit");
  }

  @Test
  void theTimeLimitAfterSomeDocumentsEndsThePageAndBeforeAnyIsAnError() {
    List<BsonDocument> docs = List.of(new BsonDocument("i", new BsonInt32(1)), new BsonDocument("i",
        new BsonInt32(2)));

    DocReader.Page partial = DocReader.read(new ListCursor(docs, 1), 10, 512 * 1024, 8_192, System.nanoTime());

    assertThat(partial.returned()).isEqualTo(1);
    assertThat(partial.truncatedReason()).isEqualTo("timeout");
    assertThatThrownBy(() -> DocReader.read(new ListCursor(docs, 0), 10, 512 * 1024, 8_192, System.nanoTime()))
        .isInstanceOf(MongoExecutionTimeoutException.class);
  }

  @Test
  void theTextHasTheSummaryAndOneDocumentPerLine() {
    DocReader.Page page = page(List.of(new BsonDocument("a", new BsonInt32(1)), new BsonDocument("a",
        new BsonInt32(2))), 2, 512 * 1024);

    ToolResult r = DocReader.result("mongo_find", "orders-mongo", "orders", "orders", page, "secondaryPreferred",
        List.of("Collection orders.ordrs doesn't exist."), 2);

    String[] lines = r.content().getFirst().text().split("\n");
    assertThat(lines[0]).matches("2 documents from orders\\.orders on orders-mongo · \\d+ ms");
    assertThat(lines[1]).isEqualTo("{\"a\": 1}");
    assertThat(lines[2]).isEqualTo("{\"a\": 2}");
    assertThat(lines[3]).isEqualTo("Note: Collection orders.ordrs doesn't exist.");
    assertThat(r.structuredContent().path("readPreference").asString()).isEqualTo("secondaryPreferred");
    assertThat(r.structuredContent().path("returned").asInt()).isEqualTo(2);
    assertThat(r.structuredContent().path("truncated").asBoolean()).isFalse();
    assertThat(r.structuredContent().path("truncatedReason").isNull()).isTrue();
  }

  private static DocReader.Page page(List<BsonDocument> docs, int n, long maxBytes) {
    return DocReader.read(new ListCursor(docs, -1), n, maxBytes, 8_192, System.nanoTime());
  }

  /** A cursor over documents; {@code timeoutAt} ≥ 0 makes that read time out like maxTimeMS on a getMore. */
  static final class ListCursor implements MongoCursor<RawBsonDocument> {
    private final Iterator<BsonDocument> it;
    private final int timeoutAt;
    private int read;
    boolean closed;

    ListCursor(List<BsonDocument> docs, int timeoutAt) {
      this.it = docs.iterator();
      this.timeoutAt = timeoutAt;
    }

    @Override
    public boolean hasNext() {
      if (read == timeoutAt) {
        throw new MongoExecutionTimeoutException(50, "operation exceeded time limit");
      }
      return it.hasNext();
    }

    @Override
    public RawBsonDocument next() {
      read++;
      return new RawBsonDocument(it.next(), new BsonDocumentCodec());
    }

    @Override
    public int available() {
      return 0;
    }

    @Override
    public RawBsonDocument tryNext() {
      return hasNext() ? next() : null;
    }

    @Override
    public ServerCursor getServerCursor() {
      return null;
    }

    @Override
    public ServerAddress getServerAddress() {
      return new ServerAddress();
    }

    @Override
    public void close() {
      closed = true;
    }

    @Override
    public void forEachRemaining(Consumer<? super RawBsonDocument> action) {
      while (hasNext()) {
        action.accept(next());
      }
    }
  }
}
