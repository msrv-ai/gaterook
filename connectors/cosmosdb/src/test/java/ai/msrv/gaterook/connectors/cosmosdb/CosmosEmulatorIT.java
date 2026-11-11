package ai.msrv.gaterook.connectors.cosmosdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Cosmos DB connector against the Azure Cosmos DB Linux emulator vNext of the Compose services (start it with
 * {@code scripts/test-services.sh up cosmosdb}, then use {@code -Pservices,cosmosdb-it}): a database and containers made
 * through the same REST API, then every tool as an agent calls it and approved changes of each kind.
 *
 * <p>The emulator checks no key or token (Microsoft documents that it doesn't enforce authentication), so the
 * signature itself is verified against {@link FakeCosmos}; it also runs TOP, ORDER BY and aggregates across partitions
 * that Azure's gateway refuses, which the fake covers too.
 */
@Tag("cosmosdb-it")
class CosmosEmulatorIT {

  static final String DATABASE = "gaterook_cosmos_it";
  private static final AtomicInteger CALLS = new AtomicInteger();
  private static URI base;
  private static String key;
  private static CosmosDbConnector cosmos;
  private static ConnectionConfig cfg;
  private static TestSecrets secrets;

  @BeforeAll
  static void seed() throws Exception {
    assumeTrue(TestServices.enabled(), "The Cosmos DB emulator runs in the Compose services: scripts/test-services.sh "
        + "up cosmosdb, then -Pservices,cosmosdb-it");
    base = URI.create("http://127.0.0.1:" + TestServices.port("GW_COSMOSDB_PORT"));
    key = TestServices.value("GW_COSMOSDB_KEY");
    secrets = new TestSecrets().with(CosmosSettings.ACCOUNT_KEY, key);
    admin("DELETE", "/dbs/" + DATABASE, "dbs", "dbs/" + DATABASE, null, 204, 404);
    admin("POST", "/dbs", "dbs", "", "{\"id\": \"" + DATABASE + "\"}", 201);
    String colls = "/dbs/" + DATABASE + "/colls";
    admin("POST", colls, "colls", "dbs/" + DATABASE, "{\"id\": \"orders\", \"partitionKey\": {\"paths\": "
        + "[\"/customerId\"], \"kind\": \"Hash\", \"version\": 2}, \"uniqueKeyPolicy\": {\"uniqueKeys\": [{\"paths\": "
        + "[\"/orderNo\"]}]}}", 201);
    admin("POST", colls, "colls", "dbs/" + DATABASE, "{\"id\": \"secrets\", \"partitionKey\": {\"paths\": "
        + "[\"/id\"], \"kind\": \"Hash\", \"version\": 2}}", 201);
    for (int i = 1; i <= 12; i++) {
      String customer = "c-" + (i % 3);
      admin("POST", colls + "/orders/docs", "docs", "dbs/" + DATABASE + "/colls/orders", "{\"id\": \"o-" + i
          + "\", \"customerId\": \"" + customer + "\", \"orderNo\": " + i + ", \"total\": " + (i * 10) + ", \"status\": \""
          + (i % 2 == 0 ? "paid" : "open") + "\"}", 201, "x-ms-documentdb-partitionkey", "[\"" + customer + "\"]");
    }
    admin("POST", colls + "/secrets/docs", "docs", "dbs/" + DATABASE + "/colls/secrets", "{\"id\": \"api-key\"}", 201,
        "x-ms-documentdb-partitionkey", "[\"api-key\"]");
    cosmos = new CosmosDbConnector(true);
    cfg = new ConnectionConfig("conn-emulator", "dev-cosmos-emulator", "cosmosdb", Env.DEV, 1, TestCtx.JSON.readTree(
        "{\"endpoint\": \"" + base + "\", \"auth\": {\"method\": \"key\", \"keyKind\": \"readWrite\"}, \"databases\": "
            + "[\"" + DATABASE + "\"], \"containers\": [\"orders\"]}"), Set.of(CosmosSettings.ACCOUNT_KEY), null);
  }

  @AfterAll
  static void drop() throws Exception {
    if (cosmos != null) {
      cosmos.close();
      admin("DELETE", "/dbs/" + DATABASE, "dbs", "dbs/" + DATABASE, null, 204, 404);
    }
  }

  /** A request of the test's own setup, signed with the emulator's key. */
  static JsonNode admin(String verb, String path, String type, String link, String body, int... ok) throws Exception {
    return admin(verb, path, type, link, body, ok[0], ok.length > 1 ? ok[1] : ok[0], null, null);
  }

  static JsonNode admin(String verb, String path, String type, String link, String body, int ok, String header,
      String value) throws Exception {
    return admin(verb, path, type, link, body, ok, ok, header, value);
  }

  private static JsonNode admin(String verb, String path, String type, String link, String body, int ok, int ok2,
      String header, String value) throws Exception {
    String date = CosmosAuth.HTTP_DATE.format(Clock.systemUTC().instant());
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20))
        .header("x-ms-version", CosmosHttp.API_VERSION).header("x-ms-date", date)
        .header("authorization", CosmosAuth.header(CosmosAuth.MASTER, CosmosAuth.signature(key.toCharArray(), verb,
            type, link, date)));
    if (header != null) {
      b.header(header, value);
    }
    if (body != null) {
      b.header("Content-Type", "application/json").method(verb, HttpRequest.BodyPublishers.ofString(body));
    } else {
      b.method(verb, HttpRequest.BodyPublishers.noBody());
    }
    try (HttpClient http = HttpClient.newHttpClient()) {
      HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
      assertThat(r.statusCode()).as(verb + " " + path + ": " + r.body()).isIn(ok, ok2);
      return r.body().isBlank() ? JsonNodeFactory.instance.objectNode() : TestCtx.JSON.readTree(r.body());
    }
  }

  private static ToolCall call(String tool, String args) {
    return new ToolCall("tc_emu_" + CALLS.incrementAndGet(), tool, "dev-cosmos-emulator",
        (ObjectNode) TestCtx.JSON.readTree(args), null);
  }

  private static String in(String more) {
    return "{\"database\": \"" + DATABASE + "\", \"container\": \"orders\"" + (more.isEmpty() ? "" : ", " + more) + "}";
  }

  private static PolicyView policy() {
    return new PolicyView(Env.DEV, "dev.cosmosdb", JsonNodeFactory.instance.objectNode(), TestCtx.dev());
  }

  @Test
  void testConnectionReadsTheEmulatorsAccount() {
    TestResult r = cosmos.test(cfg, secrets, TestCtx.of());
    assertThat(r.ok()).as(String.valueOf(r.error())).isTrue();
    assertThat(r.serverVersion()).startsWith("Azure Cosmos DB for NoSQL · ");
    assertThat(r.checks()).extracting(TestResult.Check::name).containsExactly("account", "databases");
    assertThat(r.checks().get(1).detail()).isEqualTo("1 database agents may use: " + DATABASE);
    assertThat(r.hints()).extracting(TestResult.Hint::code).contains("cosmosdb.emulator")
        .doesNotContain("cosmosdb.emulator-key", "cosmosdb.no-databases");
    assertThat(r.metadataUpdate().path("defaultConsistency").asString()).isNotBlank();
    assertThat(cosmos.health(cfg, secrets, TestCtx.of()).state())
        .isNotEqualTo(ai.msrv.gaterook.connector.api.HealthResult.State.UNREACHABLE);
  }

  @Test
  void listingsAndTheDescriptionKeepToTheConnectionsLists() throws Exception {
    ToolResult dbs = cosmos.execute(call("cosmosdb_list_databases", "{}"), cfg, secrets, TestCtx.of());
    assertThat(dbs.structuredContent().path("databases").toString()).isEqualTo("[\"" + DATABASE + "\"]");
    ToolResult containers = cosmos.execute(call("cosmosdb_list_containers", "{\"database\": \"" + DATABASE + "\"}"),
        cfg, secrets, TestCtx.of());
    assertThat(containers.structuredContent().path("containers").toString()).isEqualTo("[{\"id\":\"orders\","
        + "\"partitionKeyPaths\":[\"/customerId\"],\"partitionKeyKind\":\"Hash\"}]");
    ToolResult d = cosmos.execute(call("cosmosdb_describe_container", in("")), cfg, secrets, TestCtx.of());
    assertThat(d.structuredContent().at("/partitionKey/paths").toString()).isEqualTo("[\"/customerId\"]");
    assertThat(d.structuredContent().path("uniqueKeys").toString()).isEqualTo("[[\"/orderNo\"]]");
    assertThat(d.content().getFirst().text()).startsWith(DATABASE + "/orders: partition key /customerId (Hash v2)");
    Classification secret = cosmos.classify(call("cosmosdb_read_item", "{\"database\": \"" + DATABASE + "\", "
        + "\"container\": \"secrets\", \"id\": \"api-key\", \"partitionKey\": \"api-key\"}"), cfg, policy());
    assertThat(secret.ruleId()).isEqualTo("dev.cosmosdb.container-not-allowed");
  }

  @Test
  void aQueryAcrossPartitionsPagesThroughEveryItemOnce() throws Exception {
    String args = in("\"query\": \"SELECT c.id, c.total FROM c WHERE c.total >= @min\", \"parameters\": {\"@min\": "
        + "20}, \"limit\": 4");
    Set<String> seen = new HashSet<>();
    String cursor = null;
    int calls = 0;
    do {
      ObjectNode a = (ObjectNode) TestCtx.JSON.readTree(args);
      if (cursor != null) {
        a.put("cursor", cursor);
      }
      JsonNode out = cosmos.execute(call("cosmosdb_query", a.toString()), cfg, secrets, TestCtx.of())
          .structuredContent();
      assertThat(out.path("count").asInt()).isBetween(0, 4);
      out.path("items").forEach(i -> assertThat(seen.add(i.path("id").asString())).as("no repeats").isTrue());
      cursor = out.path("nextCursor").asString(null);
      calls++;
    } while (cursor != null && calls < 10);
    assertThat(seen).hasSize(11).doesNotContain("o-1");
    assertThat(calls).isGreaterThan(1);

    JsonNode one = cosmos.execute(call("cosmosdb_query", in("\"query\": \"SELECT VALUE c.id FROM c\", "
        + "\"partitionKey\": \"c-1\"")), cfg, secrets, TestCtx.of()).structuredContent();
    List<String> ids = new java.util.ArrayList<>();
    one.path("items").forEach(i -> ids.add(i.asString()));
    assertThat(ids).containsExactlyInAnyOrder("o-1", "o-4", "o-7", "o-10");
    assertThat(one.path("crossPartition").asBoolean()).isFalse();
  }

  @Test
  void aQueryThatNeedsStateRunsWhenItsAnswerIsOnePage() throws Exception {
    JsonNode sorted = cosmos.execute(call("cosmosdb_query", in("\"query\": \"SELECT VALUE c.total FROM c ORDER BY "
        + "c.total DESC\", \"partitionKey\": \"c-2\"")), cfg, secrets, TestCtx.of()).structuredContent();
    assertThat(sorted.path("items").toString()).isEqualTo("[110,80,50,20]");
    assertThat(sorted.has("nextCursor")).isFalse();
    JsonNode count = cosmos.execute(call("cosmosdb_query", in("\"query\": \"SELECT VALUE COUNT(1) FROM c WHERE "
        + "c.status = @s\", \"parameters\": {\"@s\": \"paid\"}, \"partitionKey\": \"c-0\"")), cfg, secrets,
        TestCtx.of()).structuredContent();
    assertThat(count.path("items").toString()).isEqualTo("[2]");
    assertThatThrownBy(() -> cosmos.execute(call("cosmosdb_query", in("\"query\": \"SELECT * FRM c\"")), cfg, secrets,
        TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
          assertThat(e.getMessage()).contains("Syntax error");
        });
  }

  @Test
  void approvedChangesOfEachKindRunAsShown() throws Exception {
    String id = "it-a b?c#%+&é";
    ObjectNode create = (ObjectNode) TestCtx.JSON.readTree(in("\"operation\": \"create\", \"partitionKey\": \"c-9\", "
        + "\"item\": {\"customerId\": \"c-9\", \"orderNo\": 900, \"total\": 5, \"status\": \"open\"}"));
    ((ObjectNode) create.get("item")).put("id", id);
    ToolResult created = run(create);
    assertThat(created.structuredContent().path("status").asInt()).isEqualTo(201);

    ObjectNode read = (ObjectNode) TestCtx.JSON.readTree(in("\"partitionKey\": \"c-9\""));
    read.put("id", id);
    JsonNode item = cosmos.execute(call("cosmosdb_read_item", read.toString()), cfg, secrets, TestCtx.of())
        .structuredContent();
    assertThat(item.path("found").asBoolean()).isTrue();
    assertThat(item.at("/item/id").asString()).isEqualTo(id);
    String etag = item.path("etag").asString();

    assertThatThrownBy(() -> run(create)).isInstanceOfSatisfying(ConnectorException.class, e -> {
      assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
      assertThat(e.getMessage()).contains("already exists").contains("Nothing changed");
    });

    ObjectNode replace = (ObjectNode) TestCtx.JSON.readTree(in("\"operation\": \"replace\", \"partitionKey\": "
        + "\"c-9\", \"item\": {\"customerId\": \"c-9\", \"orderNo\": 900, \"total\": 6, \"status\": \"open\"}"));
    replace.put("id", id).put("ifMatch", etag);
    ((ObjectNode) replace.get("item")).put("id", id);
    run(replace);
    assertThatThrownBy(() -> run(replace)).as("the ETag is stale now")
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.getMessage()).contains("changed since"));

    ObjectNode patch = (ObjectNode) TestCtx.JSON.readTree(in("\"operation\": \"patch\", \"partitionKey\": \"c-9\", "
        + "\"condition\": \"FROM c WHERE c.status = 'open'\", \"operations\": [{\"op\": \"incr\", \"path\": \"/total\", "
        + "\"value\": 4}, {\"op\": \"set\", \"path\": \"/status\", \"value\": \"paid\"}]"));
    patch.put("id", id);
    JsonNode patched = run(patch).structuredContent();
    assertThat(patched.at("/item/total").asInt()).isEqualTo(10);
    assertThat(patched.at("/item/status").asString()).isEqualTo("paid");
    assertThatThrownBy(() -> run(patch)).as("the condition no longer holds")
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.getMessage()).contains("condition"));

    ObjectNode upsert = (ObjectNode) TestCtx.JSON.readTree(in("\"operation\": \"upsert\", \"partitionKey\": \"c-9\", "
        + "\"item\": {\"id\": \"it-upsert\", \"customerId\": \"c-9\", \"orderNo\": 901}"));
    assertThat(run(upsert).content().getFirst().text()).startsWith("Created (upsert) it-upsert");
    assertThat(run(upsert).content().getFirst().text()).startsWith("Replaced (upsert) it-upsert");

    for (String doomed : List.of(id, "it-upsert")) {
      ObjectNode delete = (ObjectNode) TestCtx.JSON.readTree(in("\"operation\": \"delete\", \"partitionKey\": "
          + "\"c-9\""));
      delete.put("id", doomed);
      assertThat(run(delete).content().getFirst().text()).startsWith("Deleted ");
    }
    assertThat(cosmos.execute(call("cosmosdb_read_item", read.toString()), cfg, secrets, TestCtx.of())
        .structuredContent().path("found").asBoolean()).isFalse();
  }

  @Test
  void prepareReadsThePartitionKeyOfTheContainer() throws Exception {
    PreparedAction wrong = cosmos.prepare(call("cosmosdb_execute", in("\"operation\": \"create\", \"partitionKey\": "
        + "\"c-1\", \"item\": {\"id\": \"it-x\", \"customerId\": \"c-2\"}")), cfg, secrets, TestCtx.of());
    assertThat(wrong.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(wrong.message()).contains("/customerId");
    PreparedAction fine = cosmos.prepare(call("cosmosdb_execute", in("\"operation\": \"delete\", \"id\": \"o-1\", "
        + "\"partitionKey\": \"c-1\"")), cfg, secrets, TestCtx.of());
    assertThat(fine.outcome()).isEqualTo(PreparedAction.Outcome.APPROVAL);
  }

  /** Classifies, prepares and describes the change as the core does, then runs the approved payload. */
  private static ToolResult run(ObjectNode args) throws Exception {
    ToolCall c = call("cosmosdb_execute", args.toString());
    assertThat(cosmos.classify(c, cfg, policy()).kind()).isEqualTo(Classification.Kind.MUTATION);
    PreparedAction prepared = cosmos.prepare(c, cfg, secrets, TestCtx.of());
    assertThat(prepared.outcome()).as(String.valueOf(prepared.message())).isEqualTo(PreparedAction.Outcome.APPROVAL);
    ActionPreview preview = prepared.preview();
    return cosmos.execute(c, cfg, secrets, TestCtx.of().approved(preview.payload()));
  }
}
