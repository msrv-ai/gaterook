package ai.msrv.gaterook.connectors.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.networknt.schema.Error;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * mongo_find, mongo_aggregate and describe_connection against a real mongod (8.0.23 from flapdoodle, or
 * {@code GW_MONGOD_BIN}) with access control: Extended JSON in and out, every limit of a call, the validator as the
 * only thing between a {@code readWrite} user and {@code $out}, system namespaces, an approved call that runs its
 * payload, answers against the output schemas.
 */
class MongoIT {

  static LocalMongod mongod;
  static final MongoConnector CONNECTOR = new MongoConnector();
  static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

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

  static ConnectionConfig cfg(String user, Env env, String extra) {
    return Fixtures.config("orders-mongo-" + user + "-" + env.id(), env, "{\"connection\":{\"uri\":\""
        + mongod.uri("orders") + "\"},\"credentials\":{\"user\":\"" + user + "\",\"authSource\":\""
        + (user.equals("gw_reader") ? "orders" : "admin") + "\"}" + (extra == null ? "" : "," + extra) + "}", 1);
  }

  static ConnectionConfig cfgNoTls(String user) {
    return Fixtures.config("orders-mongo-" + user + "-prod", Env.PROD, "{\"connection\":{\"uri\":\""
        + mongod.uri("orders") + "\",\"tls\":false},\"credentials\":{\"user\":\"" + user + "\"}}", 1);
  }

  static Fixtures.Secrets secrets(String user) {
    return Fixtures.Secrets.password(LocalMongod.password(user));
  }

  /** classify, then execute as the core does for a READ. */
  static ToolResult run(ConnectionConfig cfg, String user, String tool, String args, Fixtures.Context ctx)
      throws ConnectorException {
    ToolCall call = Fixtures.call(tool, args);
    Classification c = CONNECTOR.classify(call, cfg, Fixtures.policy(cfg.env()));
    assertThat(c.kind()).as(c.reason()).isEqualTo(Classification.Kind.READ);
    return CONNECTOR.execute(call, cfg, secrets(user), ctx);
  }

  static ToolResult run(String tool, String args) throws ConnectorException {
    return run(cfg("gw_ro", Env.DEV, null), "gw_ro", tool, args, Fixtures.Context.of(Env.DEV));
  }

  /** The {@code name} of each element (findValuesAsString would also find the names nested in them). */
  static List<String> names(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.path("name").asString()));
    return out;
  }

  static List<Error> validate(String tool, JsonNode instance) {
    JsonNode schema = CONNECTOR.manifest().tools().stream().filter(t -> t.name().equals(tool)).findFirst()
        .orElseThrow().outputSchema();
    return SCHEMAS.getSchema(schema).validate(instance);
  }

  @Test
  void findReadsExtendedJsonAndAnswersRelaxedWithExactInt64() throws Exception {
    ToolResult r = run("mongo_find", "{\"collection\":\"orders\",\"filter\":\"{created_at: {$gte: "
        + "ISODate('2026-10-22T00:00:00Z')}}\"}");

    JsonNode s = r.structuredContent();
    assertThat(validate("mongo_find", s)).isEmpty();
    assertThat(s.path("returned").asInt()).isEqualTo(1);
    assertThat(s.path("documents").get(0).toString()).isEqualTo("{\"_id\":1,\"id\":48213,\"status\":\"pending\","
        + "\"total\":{\"$numberDecimal\":\"120.50\"},\"created_at\":{\"$date\":\"2026-10-22T12:29:41Z\"},"
        + "\"customer\":{\"id\":{\"$numberLong\":\"9007199254740993\"},\"email\":\"ann@example.com\"}}");
    assertThat(s.path("truncated").asBoolean()).isFalse();
    assertThat(r.content().getFirst().text()).startsWith("1 document from orders.orders on orders-mongo-gw_ro-dev");

    JsonNode all = run("mongo_find", "{\"collection\":\"orders\",\"sort\":{\"_id\":1},\"projection\":"
        + "{\"created_at\":1,\"ratio\":1,\"tag\":1}}").structuredContent().path("documents");
    assertThat(all.get(1).toString()).isEqualTo("{\"_id\":2,\"created_at\":{\"$date\":{\"$numberLong\":"
        + "\"-315619200000\"}}}");
    assertThat(all.get(2).toString()).isEqualTo("{\"_id\":3,\"created_at\":{\"$date\":\"2026-10-21T08:00:00Z\"},"
        + "\"ratio\":{\"$numberDouble\":\"NaN\"},\"tag\":{\"$binary\":{\"base64\":\"D4+tW9nLRp+hZXCGdyiVDg==\","
        + "\"subType\":\"04\"}}}");
  }

  @Test
  void aggregateRunsThePipelineWithTheGatewaysLimit() throws Exception {
    ToolResult r = run("mongo_aggregate", "{\"collection\":\"orders\",\"pipeline\":[{\"$group\":{\"_id\":"
        + "\"$status\",\"n\":{\"$sum\":1}}},{\"$sort\":{\"_id\":1}}]}");

    assertThat(validate("mongo_aggregate", r.structuredContent())).isEmpty();
    assertThat(r.structuredContent().path("documents").toString())
        .isEqualTo("[{\"_id\":\"pending\",\"n\":2},{\"_id\":\"shipped\",\"n\":1}]");

    ToolResult cut = run("mongo_aggregate", "{\"collection\":\"events\",\"pipeline\":[{\"$sort\":{\"_id\":1}}],"
        + "\"limit\":7}");
    assertThat(cut.structuredContent().path("returned").asInt()).isEqualTo(7);
    assertThat(cut.structuredContent().path("truncatedReason").asString()).isEqualTo("doc_limit");
    assertThat(cut.truncated()).isTrue();
  }

  @Test
  void theEnvironmentsLimitsCapEveryCall() throws Exception {
    // prod: at most 100 documents, whatever the agent asks.
    // TLS is on by default on prod: this test server has none.
    ToolResult prod = run(cfgNoTls("gw_ro"), "gw_ro", "mongo_find",
        "{\"collection\":\"events\",\"limit\":1000}", Fixtures.Context.of(Env.PROD));
    assertThat(prod.structuredContent().path("returned").asInt()).isEqualTo(100);
    assertThat(prod.structuredContent().path("truncatedReason").asString()).isEqualTo("doc_limit");
    assertThat(prod.structuredContent().path("readPreference").asString()).isEqualTo("secondaryPreferred");

    // 16 KB for the answer, less 1 KB of overhead: documents of ~240 bytes, each counted twice (structured
    // content and text), so about 31.
    Limits small = Fixtures.Context.withRows(Fixtures.Context.limits(Env.DEV), 1_000, 16 * 1024,
        Duration.ofSeconds(5));
    ToolResult sized = run(cfg("gw_ro", Env.DEV, null), "gw_ro", "mongo_find",
        "{\"collection\":\"events\",\"limit\":1000}", Fixtures.Context.with(Env.DEV, small));
    assertThat(sized.structuredContent().path("truncatedReason").asString()).isEqualTo("size_limit");
    assertThat(sized.structuredContent().path("returned").asInt()).isBetween(25, 35);
  }

  @Test
  void theServersTimeLimitStopsTheQuery() throws Exception {
    mongod.failNextWithTimeLimit(1);
    try {
      assertThatThrownBy(() -> run("mongo_find", "{\"collection\":\"events\"}"))
          .isInstanceOfSatisfying(ConnectorException.class, e -> {
            assertThat(e.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT);
            assertThat(e.getMessage()).contains("ran longer than its time limit (maxTimeMS)");
          });
    } finally {
      mongod.clearFailPoint();
    }
  }

  @Test
  void outFromAUserThatCanWriteIsStoppedByTheValidatorOnly() throws Exception {
    ConnectionConfig rw = cfg("gw_rw", Env.DEV, null);
    String out = "{\"collection\":\"orders\",\"pipeline\":[{\"$match\":{}},{\"$out\":\"stolen\"}]}";
    String merge = "{\"collection\":\"orders\",\"pipeline\":[{\"$facet\":{\"a\":[{\"$match\":{}}]}},"
        + "{\"$merge\":{\"into\":\"merged\"}}]}";

    for (String args : List.of(out, merge)) {
      assertThat(CONNECTOR.classify(Fixtures.call("mongo_aggregate", args), rw, Fixtures.policy(Env.DEV)).kind())
          .isEqualTo(Classification.Kind.DENY);
      // The executor directly, as if classify had been skipped.
      assertThatThrownBy(() -> CONNECTOR.execute(Fixtures.call("mongo_aggregate", args), rw, secrets("gw_rw"),
          Fixtures.Context.of(Env.DEV))).isInstanceOfSatisfying(ConnectorException.class,
          e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    }
    try (MongoClient root = mongod.root()) {
      List<String> names = root.getDatabase("orders").listCollectionNames().into(new ArrayList<>());
      assertThat(names).doesNotContain("stolen", "merged");
    }
    // Without the gateway the same user really writes: the validator is the barrier (report 05 §9 item 1).
    try (MongoClient direct = MongoClients.create(MongoClientSettings.builder()
        .applyConnectionString(new com.mongodb.ConnectionString(mongod.uri("orders")))
        .credential(MongoCredential.createCredential("gw_rw", "admin", LocalMongod.password("gw_rw").toCharArray()))
        .build())) {
      direct.getDatabase("orders").getCollection("orders").aggregate(List.of(new Document("$match", new Document()),
          new Document("$out", "proof"))).first();
      assertThat(direct.getDatabase("orders").listCollectionNames().into(new ArrayList<>())).contains("proof");
      direct.getDatabase("orders").getCollection("proof").drop();
    }
  }

  @Test
  void serverSideJavaScriptAndSystemNamespacesNeverReachTheServer() {
    ConnectionConfig root = cfg(LocalMongod.ROOT, Env.DEV, null);
    for (String[] c : List.of(
        new String[] {"mongo_find", "{\"collection\":\"orders\",\"filter\":{\"$where\":\"sleep(3000) || true\"}}"},
        new String[] {"mongo_find", "{\"database\":\"admin\",\"collection\":\"system.users\"}"},
        new String[] {"mongo_aggregate", "{\"collection\":\"orders\",\"pipeline\":[{\"$lookup\":{\"from\":"
            + "{\"db\":\"admin\",\"coll\":\"system.users\"},\"localField\":\"a\",\"foreignField\":\"b\",\"as\":\"u\"}}]}"},
        new String[] {"mongo_aggregate", "{\"collection\":\"orders\",\"pipeline\":[{\"$currentOp\":{}}]}"})) {
      long t = System.nanoTime();
      assertThatThrownBy(() -> CONNECTOR.execute(Fixtures.call(c[0], c[1]), root, secrets(LocalMongod.ROOT),
          Fixtures.Context.of(Env.DEV))).as(c[1]).isInstanceOfSatisfying(ConnectorException.class,
          e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
      assertThat(Duration.ofNanos(System.nanoTime() - t)).isLessThan(Duration.ofSeconds(1));
    }
  }

  @Test
  void anApprovedCallRunsWhatThePersonSawNotTheArgumentsAgain() throws Exception {
    ConnectionConfig cfg = cfg("gw_ro", Env.DEV, null);
    ToolCall shipped = Fixtures.call("mongo_find", "{\"collection\":\"orders\",\"filter\":{\"status\":\"shipped\"}}");
    PreparedAction prepared = CONNECTOR.prepare(shipped, cfg, secrets("gw_ro"), Fixtures.Context.of(Env.DEV));
    ToolCall pending = Fixtures.call("mongo_find", "{\"collection\":\"orders\",\"filter\":{\"status\":\"pending\"}}");

    ToolResult r = CONNECTOR.execute(pending, cfg, secrets("gw_ro"), Fixtures.Context.approved(Env.DEV,
        prepared.preview().payload()));

    assertThat(r.structuredContent().path("documents").findValuesAsString("status")).containsExactly("shipped");
  }

  @Test
  void aUserOfAnotherAuthDatabaseAndTheTaintOfItsPassword() throws Exception {
    Fixtures.Secrets secrets = secrets("gw_reader");
    ToolResult r = CONNECTOR.execute(Fixtures.call("mongo_find", "{\"collection\":\"customers\"}"),
        cfg("gw_reader", Env.DEV, null), secrets, Fixtures.Context.of(Env.DEV));

    assertThat(r.structuredContent().path("documents").toString()).isEqualTo("[{\"_id\":42,\"name\":\"Ann\"}]");
    // The password was resolved through the call's resolver, so the core's masker knows its every form.
    assertThat(secrets.handedOut).contains(MongoSettings.PASSWORD);
  }

  @Test
  void aCollectionThatIsntThereIsSaidSo() throws Exception {
    ToolResult r = run("mongo_find", "{\"collection\":\"ordrs\"}");

    assertThat(r.structuredContent().path("warnings").get(0).asString())
        .isEqualTo("Collection orders.ordrs doesn't exist. Call describe_connection to list collections.");
    assertThat(run("mongo_find", "{\"collection\":\"orders\",\"filter\":{\"status\":\"lost\"}}").structuredContent()
        .path("warnings").isEmpty()).isTrue();
  }

  @Test
  void aQueryTheServerRefusesIsTheServersOwnWords() {
    assertThatThrownBy(() -> run("mongo_find", "{\"collection\":\"orders\",\"filter\":{\"$bogus\":1}}"))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
          assertThat(e.getMessage()).contains("unknown top level operator: $bogus");
        });
    assertThatThrownBy(() -> run(cfg("gw_ro", Env.DEV, null), "gw_ro", "mongo_find",
        "{\"database\":\"inventory\",\"collection\":\"items\"}", Fixtures.Context.of(Env.DEV)))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
          assertThat(e.getMessage()).contains("not authorized");
        });
  }

  @Test
  void describeListsWhatTheAgentMayReadAndDescribesOneCollection() throws Exception {
    ConnectionConfig root = cfg(LocalMongod.ROOT, Env.DEV, null);
    JsonNode d = CONNECTOR.describe(root, secrets(LocalMongod.ROOT), Fixtures.Context.of(Env.DEV), null)
        .orElseThrow();

    assertThat(d.at("/server/version").asString()).isEqualTo(mongod.version);
    assertThat(names(d.path("databases"))).contains("orders", "inventory").doesNotContain("admin", "local", "config");
    JsonNode orders = d.path("databases").get(names(d.path("databases")).indexOf("orders"));
    assertThat(names(orders.path("collections"))).doesNotContain("system.views", "system.buckets.metrics");
    assertThat(orders.path("collections").toString()).contains("{\"name\":\"paid_orders\",\"type\":\"view\"}")
        .contains("\"name\":\"metrics\",\"type\":\"timeseries\"")
        .contains("{\"name\":\"orders\",\"type\":\"collection\",\"estimatedDocuments\":3}");
    assertThat(d.path("truncated").asBoolean()).isFalse();

    JsonNode one = CONNECTOR.describe(root, secrets(LocalMongod.ROOT), Fixtures.Context.of(Env.DEV),
        "orders.orders").orElseThrow().path("object");
    assertThat(names(one.path("indexes"))).containsExactly("_id_");
    assertThat(one.at("/fields/total").toString()).isEqualTo("[\"decimal\"]");
    assertThat(one.at("/fields/customer").toString()).isEqualTo("[\"object\"]");
    assertThat(one.path("sampled").asInt()).isEqualTo(3);

    JsonNode limited = CONNECTOR.describe(cfg(LocalMongod.ROOT, Env.DEV, "\"advanced\":{\"namespaceAllowlist\":"
        + "[\"orders.orders\"]}"), secrets(LocalMongod.ROOT), Fixtures.Context.of(Env.DEV), null).orElseThrow();
    assertThat(names(limited.path("databases"))).containsExactly("orders");
    assertThat(names(limited.at("/databases/0/collections"))).containsExactly("orders");

    assertThatThrownBy(() -> CONNECTOR.describe(root, secrets(LocalMongod.ROOT), Fixtures.Context.of(Env.DEV),
        "admin.system.users")).isInstanceOfSatisfying(ConnectorException.class,
        e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
  }
}
