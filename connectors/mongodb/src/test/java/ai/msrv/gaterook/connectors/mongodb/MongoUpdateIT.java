package ai.msrv.gaterook.connectors.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import com.mongodb.client.MongoClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** MongoDB mutations against a disposable server, including the approved payload and server permissions. */
class MongoUpdateIT {

  private static final String COLLECTION = "update_cases";
  private static final MongoConnector CONNECTOR = new MongoConnector();
  private static LocalMongod mongod;

  @BeforeAll
  static void start() throws Exception {
    mongod = LocalMongod.start(LocalMongod.defaultBinary(), true, false);
  }

  @AfterAll
  static void stop() {
    CONNECTOR.close();
    if (mongod != null) {
      mongod.close();
    }
  }

  @BeforeEach
  void seed() {
    try (MongoClient root = mongod.root()) {
      var collection = root.getDatabase("orders").getCollection(COLLECTION);
      collection.drop();
      collection.insertMany(List.of(
          new Document("_id", 1).append("status", "pending").append("count", 1),
          new Document("_id", 2).append("status", "pending").append("count", 2),
          new Document("_id", 3).append("status", "shipped").append("count", 3)));
    }
  }

  private static ConnectionConfig config(String user) {
    return Fixtures.config("update-mongo-" + user, Env.DEV,
        "{\"connection\":{\"uri\":\"" + mongod.uri("orders")
            + "\",\"readPreference\":\"secondary\"},\"credentials\":{\"user\":\"" + user
            + "\",\"authSource\":\"admin\"}}", 1);
  }

  private static Fixtures.Secrets secrets(String user) {
    return Fixtures.Secrets.password(LocalMongod.password(user));
  }

  private static ToolCall call(String tool, String arguments) {
    var args = Fixtures.json(arguments).put("collection", COLLECTION);
    return Fixtures.call(tool, args.toString());
  }

  private static ToolResult run(String tool, String arguments) throws ConnectorException {
    ToolCall call = call(tool, arguments);
    ConnectionConfig config = config("gw_rw");
    Classification classification = CONNECTOR.classify(call, config, Fixtures.policy(Env.DEV));
    assertThat(classification.kind()).as(classification.reason()).isEqualTo(Classification.Kind.MUTATION);
    PreparedAction prepared = CONNECTOR.prepare(call, config, secrets("gw_rw"), Fixtures.Context.of(Env.DEV));
    assertThat(prepared.outcome()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    return CONNECTOR.execute(call, config, secrets("gw_rw"),
        Fixtures.Context.approved(Env.DEV, prepared.preview().payload()));
  }

  private static List<Document> documents() {
    try (MongoClient root = mongod.root()) {
      return root.getDatabase("orders").getCollection(COLLECTION).find().sort(new Document("_id", 1))
          .into(new ArrayList<>());
    }
  }

  private static void counts(ToolResult result, long matched, long modified) {
    JsonNode body = result.structuredContent();
    assertThat(result.isError()).isFalse();
    assertThat(body.path("acknowledged").asBoolean()).isTrue();
    assertThat(body.path("matchedCount").asLong()).isEqualTo(matched);
    assertThat(body.path("modifiedCount").asLong()).isEqualTo(modified);
  }

  @Test
  void updateChangesOnlyOneMatchingDocumentAndManyChangesAllMatches() throws Exception {
    counts(run("mongo_update", "{\"filter\":{\"status\":\"pending\"},\"update\":{\"$inc\":{\"count\":10}}}"), 1, 1);
    List<Document> afterOne = documents();
    assertThat(afterOne.stream().filter(d -> d.getInteger("count") > 10).count()).isEqualTo(1);
    assertThat(afterOne.get(2).getInteger("count")).isEqualTo(3);

    counts(run("mongo_update_many", "{\"filter\":{\"status\":\"pending\"},"
        + "\"update\":{\"$set\":{\"status\":\"processed\"}}}"), 2, 2);
    assertThat(documents()).extracting(d -> d.getString("status")).containsExactly("processed", "processed", "shipped");
  }

  @ParameterizedTest
  @ValueSource(strings = {"mongo_update", "mongo_update_many"})
  void noMatchDoesNotInsertUnlessUpsertIsRequested(String tool) throws Exception {
    counts(run(tool, "{\"filter\":{\"_id\":999},\"update\":{\"$set\":{\"status\":\"processed\"}}}"), 0, 0);
    assertThat(documents()).hasSize(3);
  }

  @Test
  void unchangedValuesAreMatchedButNotModified() throws Exception {
    counts(run("mongo_update", "{\"filter\":{\"_id\":1},\"update\":{\"$set\":{\"status\":\"pending\"}}}"), 1, 0);
    assertThat(documents().getFirst().getInteger("count")).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"mongo_update", "mongo_update_many"})
  void upsertReturnsTheTypedIdentifierAndCreatesOneDocument(String tool) throws Exception {
    String id = "507f1f77bcf86cd799439011";
    ToolResult result = run(tool, "{\"filter\":\"{_id: ObjectId('" + id + "')}\","
        + "\"update\":{\"$set\":{\"status\":\"created\"}},\"upsert\":true}");

    counts(result, 0, 0);
    assertThat(result.structuredContent().at("/upsertedId/$oid").asString()).isEqualTo(id);
    assertThat(documents()).hasSize(4);
    try (MongoClient root = mongod.root()) {
      Document inserted = root.getDatabase("orders").getCollection(COLLECTION)
          .find(new Document("_id", new ObjectId(id))).first();
      assertThat(inserted).isNotNull();
      assertThat(inserted.getString("status")).isEqualTo("created");
    }
  }

  @Test
  void anExplicitEmptyFilterUpdatesEveryDocument() throws Exception {
    counts(run("mongo_update_many", "{\"filter\":{},\"update\":{\"$inc\":{\"count\":1}}}"), 3, 3);
    assertThat(documents()).extracting(d -> d.getInteger("count")).containsExactly(2, 3, 4);
  }

  @Test
  void arrayFiltersUpdateOnlySelectedElements() throws Exception {
    try (MongoClient root = mongod.root()) {
      root.getDatabase("orders").getCollection(COLLECTION).updateOne(new Document("_id", 1),
          new Document("$set", new Document("items", List.of(
              new Document("sku", "a").append("quantity", 1),
              new Document("sku", "b").append("quantity", 2)))));
    }

    counts(run("mongo_update", "{\"filter\":{\"_id\":1},\"update\":{\"$inc\":{\"items.$[item].quantity\":5}},"
        + "\"arrayFilters\":[{\"item.sku\":\"b\"}]}"), 1, 1);

    assertThat(documents().getFirst().getList("items", Document.class))
        .extracting(d -> d.getInteger("quantity")).containsExactly(1, 7);
  }

  @ParameterizedTest
  @ValueSource(strings = {"mongo_update", "mongo_update_many"})
  void updatePipelinesUseExistingFieldValues(String tool) throws Exception {
    counts(run(tool, "{\"filter\":{\"_id\":1},\"update\":[{\"$set\":{\"count\":{\"$add\":[\"$count\",5]}}},"
        + "{\"$unset\":\"status\"}]}"), 1, 1);
    Document changed = documents().getFirst();
    assertThat(changed.getInteger("count")).isEqualTo(6);
    assertThat(changed).doesNotContainKey("status");
  }

  @Test
  void preparationDoesNotWriteAndExecutionUsesTheApprovedFilterAndUpdate() throws Exception {
    ConnectionConfig config = config("gw_rw");
    ToolCall original = call("mongo_update", "{\"filter\":{\"_id\":1},\"update\":{\"$set\":{\"status\":\"approved\"}}}");
    PreparedAction prepared = CONNECTOR.prepare(original, config, secrets("gw_rw"), Fixtures.Context.of(Env.DEV));
    assertThat(prepared.outcome()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    assertThat(documents()).extracting(d -> d.getString("status")).containsExactly("pending", "pending", "shipped");

    ToolCall changed = call("mongo_update", "{\"filter\":{\"_id\":2},\"update\":{\"$set\":{\"status\":\"unapproved\"}}}");
    counts(CONNECTOR.execute(changed, config, secrets("gw_rw"),
        Fixtures.Context.approved(Env.DEV, prepared.preview().payload())), 1, 1);

    assertThat(documents()).extracting(d -> d.getString("status")).containsExactly("approved", "pending", "shipped");
  }

  @ParameterizedTest
  @ValueSource(strings = {"mongo_update", "mongo_update_many"})
  void serverPermissionsStillRefuseWritesByAReadOnlyUser(String tool) {
    ToolCall call = call(tool, "{\"filter\":{},\"update\":{\"$set\":{\"status\":\"forbidden\"}}}");
    ConnectionConfig config = config("gw_ro");
    PreparedAction prepared = CONNECTOR.prepare(call, config, secrets("gw_ro"), Fixtures.Context.of(Env.DEV));

    assertThatThrownBy(() -> CONNECTOR.execute(call, config, secrets("gw_ro"),
        Fixtures.Context.approved(Env.DEV, prepared.preview().payload())))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
          assertThat(e.getMessage()).contains("not authorized");
        });
    assertThat(documents()).extracting(d -> d.getString("status")).containsExactly("pending", "pending", "shipped");
  }

  @Test
  void anExpiredDeadlineDoesNotWrite() {
    ToolCall call = call("mongo_update_many", "{\"filter\":{},\"update\":{\"$inc\":{\"count\":1}}}");
    ConnectionConfig config = config("gw_rw");
    PreparedAction prepared = CONNECTOR.prepare(call, config, secrets("gw_rw"), Fixtures.Context.of(Env.DEV));
    Fixtures.Context expired = new Fixtures.Context(Env.DEV, Fixtures.Context.limits(Env.DEV),
        Duration.ofSeconds(-1), prepared.preview().payload());

    assertThatThrownBy(() -> CONNECTOR.execute(call, config, secrets("gw_rw"), expired))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT));
    assertThat(documents()).extracting(d -> d.getInteger("count")).containsExactly(1, 2, 3);
  }
}
