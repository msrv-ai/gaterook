package com.gaterook.connectors.spanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolResult;
import com.gaterook.connectors.cloudauth.CloudCredentials;
import com.gaterook.connectors.cloudauth.CloudEndpoints;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Spanner connector against Google's Spanner emulator of the Compose services, profile {@code spanner} (start it
 * with {@code scripts/test-services.sh up spanner}, then use {@code -Pservices,spanner-it}). The setup makes an
 * instance and two databases of its own, GoogleSQL and PostgreSQL, over the emulator's REST API. The emulator takes
 * no credentials: a token server on this computer stands in for Google's, as the connector sends tokens only where a
 * test token server goes with the test endpoint.
 */
@Tag("spanner-it")
class SpannerEmulatorIT {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String PROJECT = "gw-test-project";
  static final String INSTANCE = "gw-it";
  static final String RUN = HexFormat.of().formatHex(new byte[] {(byte) ThreadLocalRandom.current().nextInt(),
      (byte) ThreadLocalRandom.current().nextInt(), (byte) ThreadLocalRandom.current().nextInt()});
  static final String DB = "orders_" + RUN;
  static final String PG = "pgorders_" + RUN;
  static String rest;
  static MockWebServer tokens;
  static CloudCredentials cloud;
  static SpannerConnector spanner;
  static final HttpClient HTTP = HttpClient.newHttpClient();
  final TestSecrets secrets = new TestSecrets();

  @BeforeAll
  static void seed() throws Exception {
    assumeTrue(TestServices.enabled(), "Spanner integration tests need the Compose test services");
    rest = "http://127.0.0.1:" + TestServices.port("GW_SPANNER_PORT") + "/v1/";
    tokens = new MockWebServer();
    tokens.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        return new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
            .body("{\"access_token\":\"ya29.emulator\",\"expires_in\":3600,\"token_type\":\"Bearer\"}").build();
      }
    });
    tokens.start(InetAddress.getByName("127.0.0.1"), 0);
    Path dir = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "spanner-it-");
    Path adc = Files.writeString(dir.resolve("adc.json"), "{\"type\":\"authorized_user\",\"client_id\":\"c.apps"
        + ".googleusercontent.com\",\"client_secret\":\"d-it\",\"refresh_token\":\"1//it-refresh\"}");
    cloud = new CloudCredentials(new CloudEndpoints(tokens.url("/token").uri(), null, adc, null, null, null,
        Map.of("gcp.spanner", URI.create(rest.substring(0, rest.length() - 1)))), Clock.systemUTC());
    spanner = new SpannerConnector(cloud);

    String instances = "projects/" + PROJECT + "/instances";
    awaitEmulator(instances);
    JsonNode made = admin("POST", instances, "{\"instanceId\":\"" + INSTANCE + "\",\"instance\":{\"config\":"
        + "\"emulator-config\",\"displayName\":\"GateRook IT\",\"nodeCount\":1}}", true);
    assertThat(made.path("code").asInt(0)).as(made.toString()).isIn(0, 6);
    String databases = instances + "/" + INSTANCE + "/databases";
    admin("POST", databases, JSON.writeValueAsString(Map.of("createStatement", "CREATE DATABASE `" + DB + "`",
        "extraStatements", List.of(
            "CREATE TABLE Customers (CustomerId INT64 NOT NULL, Name STRING(MAX)) PRIMARY KEY (CustomerId)",
            "CREATE TABLE Orders (CustomerId INT64 NOT NULL, OrderId INT64 NOT NULL, Note STRING(MAX) DEFAULT "
                + "(\"none\"), Total NUMERIC) PRIMARY KEY (CustomerId, OrderId), INTERLEAVE IN PARENT Customers ON "
                + "DELETE CASCADE",
            "CREATE INDEX OrdersByNote ON Orders(Note DESC) STORING (Total)"))), false);
    admin("POST", databases, "{\"createStatement\":\"CREATE DATABASE \\\"" + PG + "\\\"\",\"databaseDialect\":"
        + "\"POSTGRESQL\"}", false);
    awaitReady(databases + "/" + DB);
    awaitReady(databases + "/" + PG);
    admin("PATCH", databases + "/" + PG + "/ddl", "{\"statements\":[\"CREATE TABLE orders (id bigint PRIMARY KEY, "
        + "amount numeric, note varchar)\"]}", false);
    write(DB, "Customers", List.of("CustomerId", "Name"), List.of(List.of("1", "Ada"), List.of("2", "Lin")));
    write(DB, "Orders", List.of("CustomerId", "OrderId", "Note", "Total"), List.of(
        List.of("1", "1", "new", "10.50"), List.of("1", "2", "new", "20"), List.of("1", "3", "paid", "30"),
        List.of("1", "4", "paid", "40"), List.of("2", "5", "new", "50")));
    write(PG, "orders", List.of("id", "amount", "note"), List.of(List.of("1", "5.25", "small"),
        List.of("2", "125.00", "large")));
  }

  @AfterAll
  static void drop() {
    if (spanner != null) {
      spanner.close();
      cloud.close();
      tokens.close();
    }
  }

  /** One request to the emulator's REST API; a 4xx/5xx answer is returned only when {@code tolerate}. */
  static JsonNode admin(String method, String path, String body, boolean tolerate) throws Exception {
    HttpRequest.Builder r = HttpRequest.newBuilder(URI.create(rest + path)).header("Content-Type", "application/json");
    r.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    HttpResponse<String> a = HTTP.send(r.build(), HttpResponse.BodyHandlers.ofString());
    JsonNode tree = a.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(a.body());
    if (a.statusCode() / 100 != 2 && !tolerate) {
      throw new AssertionError(method + " " + path + " answered " + a.statusCode() + ": " + a.body());
    }
    return tree;
  }

  /** The emulator answers its REST API a moment after its container starts. */
  static void awaitEmulator(String instances) throws Exception {
    for (int i = 0; i < 100; i++) {
      try {
        admin("GET", instances, null, true);
        return;
      } catch (java.io.IOException e) {
        Thread.sleep(100);
      }
    }
    throw new AssertionError("The Spanner emulator doesn't answer on " + rest);
  }

  static void awaitReady(String database) throws Exception {
    for (int i = 0; i < 50; i++) {
      if ("READY".equals(admin("GET", database, null, true).path("state").asString(""))) {
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError(database + " isn't READY");
  }

  /** Inserts rows with one committed mutation, as values in Spanner's JSON (all as text here). */
  static void write(String database, String table, List<String> columns, List<List<String>> rows) throws Exception {
    String db = "projects/" + PROJECT + "/instances/" + INSTANCE + "/databases/" + database;
    String session = admin("POST", db + "/sessions", "{}", false).path("name").asString();
    ObjectNode commit = JSON.createObjectNode();
    commit.putObject("singleUseTransaction").putObject("readWrite");
    ObjectNode insert = commit.putArray("mutations").addObject().putObject("insertOrUpdate").put("table", table);
    columns.forEach(insert.putArray("columns")::add);
    var values = insert.putArray("values");
    for (List<String> row : rows) {
      var v = values.addArray();
      row.forEach(v::add);
    }
    admin("POST", session + ":commit", JSON.writeValueAsString(commit), false);
    admin("DELETE", session, null, true);
  }

  static ConnectionConfig config(String database) {
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("project", PROJECT).put("instance", INSTANCE)
        .put("database", database);
    c.putObject("credentials").put("source", "adc");
    return new ConnectionConfig("conn-it-" + database, "dev-" + database, "spanner", Env.DEV, 1, c, Set.of(), null);
  }

  static ToolCall call(String tool, String args) {
    return new ToolCall("tc_" + Long.toHexString(System.nanoTime()), tool, "dev-spanner",
        (ObjectNode) JSON.readTree(args), null);
  }

  static String sql(String sql) {
    return JSON.writeValueAsString(Map.of("sql", sql));
  }

  @Test
  void testConnectionReadsTheDatabaseAndItsDialect() {
    TestResult t = spanner.test(config(DB), secrets, TestCtx.of());
    assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
    assertThat(t.metadataUpdate().path("dialect").asString()).isEqualTo("GoogleSQL");
    assertThat(t.checks().getLast().detail()).startsWith("2 tables agents may read: Customers, Orders");
    TestResult pg = spanner.test(config(PG), secrets, TestCtx.of());
    assertThat(pg.metadataUpdate().path("dialect").asString()).isEqualTo("PostgreSQL");
  }

  @Test
  void listsAndDescribesTheTablesWithTheirKeysIndexesAndDdl() throws Exception {
    ToolResult tables = spanner.execute(call("spanner_list_tables", "{}"), config(DB), secrets, TestCtx.of());
    assertThat(tables.content().getFirst().text()).isEqualTo("2 tables: Customers, Orders (in Customers)");
    JsonNode orders = spanner.execute(call("spanner_describe_table", "{\"table\": \"Orders\"}"), config(DB), secrets,
        TestCtx.of()).structuredContent();
    assertThat(orders.path("parent").asString()).isEqualTo("Customers");
    assertThat(orders.path("onDelete").asString()).isEqualTo("CASCADE");
    assertThat(orders.path("primaryKey").toString()).isEqualTo("[\"CustomerId\",\"OrderId\"]");
    assertThat(orders.at("/indexes/0/name").asString()).isEqualTo("OrdersByNote");
    assertThat(orders.at("/indexes/0/columns").toString()).isEqualTo("[\"Note DESC\"]");
    assertThat(orders.at("/indexes/0/storing").toString()).isEqualTo("[\"Total\"]");
    assertThat(orders.path("columns")).extracting(c -> c.path("name").asString()).containsExactly("CustomerId",
        "OrderId", "Note", "Total");
    assertThat(orders.path("ddl").get(0).asString()).startsWith("CREATE TABLE Orders");
  }

  @Test
  void pagesReadOneSnapshotEvenWhenRowsChangeBetweenThem() throws Exception {
    String query = "{\"sql\": \"SELECT OrderId, Note, Total FROM Orders WHERE CustomerId = 1 ORDER BY OrderId\", "
        + "\"limit\": 2";
    ToolResult first = spanner.execute(call("spanner_query", query + "}"), config(DB), secrets, TestCtx.of());
    assertThat(first.table().rows().toString()).isEqualTo("[[1,\"new\",\"10.5\"],[2,\"new\",\"20\"]]");
    assertThat(first.table().columns()).extracting(ToolResult.Column::type).containsExactly("INT64", "STRING",
        "NUMERIC");
    // A row that sorts first, written after the first page: the next page still reads the first page's snapshot.
    write(DB, "Orders", List.of("CustomerId", "OrderId", "Note", "Total"), List.of(List.of("1", "0", "late", "1")));
    ToolResult second = spanner.execute(call("spanner_query", query + ", \"cursor\": \"" + first.table().nextCursor()
        + "\"}"), config(DB), secrets, TestCtx.of());
    assertThat(second.table().rows().toString()).isEqualTo("[[3,\"paid\",\"30\"],[4,\"paid\",\"40\"]]");
    ToolResult fresh = spanner.execute(call("spanner_query", query + "}"), config(DB), secrets, TestCtx.of());
    assertThat(fresh.table().rows().get(0).get(0).asLong()).isZero();
  }

  @Test
  void spannerItselfRefusesAChangeInTheReadOnlyTransactionOfAQuery() throws Exception {
    String db = "projects/" + PROJECT + "/instances/" + INSTANCE + "/databases/" + DB;
    String session = admin("POST", db + "/sessions", "{}", false).path("name").asString();
    JsonNode refused = admin("POST", session + ":executeStreamingSql", "{\"sql\":\"DELETE FROM Orders WHERE true\","
        + "\"transaction\":{\"singleUse\":{\"readOnly\":{\"strong\":true}}}}", true);
    assertThat(refused.toString()).containsIgnoringCase("DML statements may not be performed");
    assertThat(spanner.execute(call("spanner_query", sql("SELECT COUNT(*) FROM Orders")), config(DB), secrets,
        TestCtx.of()).table().rows().get(0).get(0).asLong()).isGreaterThanOrEqualTo(5);
    admin("DELETE", session, null, true);
  }

  @Test
  void anApprovedChangeCommitsOnceAndReturnsItsRows() throws Exception {
    String text = "UPDATE Orders SET Note = 'shipped' WHERE CustomerId = 2 AND OrderId = 5 THEN RETURN OrderId, Note";
    ToolResult r = spanner.execute(call("spanner_execute", sql(text)), config(DB), secrets,
        TestCtx.of().approved(JsonNodeFactory.instance.objectNode().put("sql", text), "ap_dml_1"));
    assertThat(r.structuredContent().path("rowsAffected").asLong()).isEqualTo(1);
    assertThat(r.structuredContent().path("returned").toString()).isEqualTo("[[5,\"shipped\"]]");
    assertThat(r.structuredContent().path("commitTimestamp").asString()).isNotBlank();
    assertThat(spanner.execute(call("spanner_query", sql("SELECT Note FROM Orders WHERE OrderId = 5")), config(DB),
        secrets, TestCtx.of()).table().rows().toString()).isEqualTo("[[\"shipped\"]]");
  }

  @Test
  void anApprovedSchemaChangeAppliesOnceForItsApproval() throws Exception {
    String text = "CREATE INDEX CustomersByName ON Customers(Name)";
    TestCtx approved = TestCtx.of().approved(JsonNodeFactory.instance.objectNode().put("sql", text), "ap_ddl_1");
    ToolResult r = spanner.execute(call("spanner_execute", sql(text)), config(DB), secrets, approved);
    assertThat(r.content().getFirst().text()).isEqualTo("CREATE on Customers applied.");
    // The same approval again finds its operation: Spanner doesn't apply the statement a second time.
    ToolResult again = spanner.execute(call("spanner_execute", sql(text)), config(DB), secrets,
        TestCtx.of().approved(JsonNodeFactory.instance.objectNode().put("sql", text), "ap_ddl_1"));
    assertThat(again.structuredContent().path("applied").asBoolean()).isTrue();
    String bad = "CREATE INDEX CustomersByName ON Customers(Name)";
    assertThatThrownBy(() -> spanner.execute(call("spanner_execute", sql(bad)), config(DB), secrets,
        TestCtx.of().approved(JsonNodeFactory.instance.objectNode().put("sql", bad), "ap_ddl_2")))
        .hasMessageContaining("CustomersByName");
  }

  @Test
  void aPostgresDialectDatabaseTakesItsParametersAndNamesItsTypes() throws Exception {
    ToolResult tables = spanner.execute(call("spanner_list_tables", "{}"), config(PG), secrets, TestCtx.of());
    assertThat(tables.content().getFirst().text()).isEqualTo("1 table: orders");
    ToolResult r = spanner.execute(call("spanner_query", "{\"sql\": \"SELECT id, amount, note FROM orders WHERE "
        + "amount > $1 ORDER BY id\", \"parameters\": [{\"name\": \"p1\", \"type\": \"NUMERIC\", \"value\": \"10\"}]}"),
        config(PG), secrets, TestCtx.of());
    assertThat(r.table().rows().toString()).isEqualTo("[[2,\"125.00\",\"large\"]]");
    assertThat(r.table().columns()).extracting(ToolResult.Column::type).containsExactly("bigint", "numeric",
        "character varying");
    JsonNode described = spanner.execute(call("spanner_describe_table", "{\"table\": \"orders\"}"), config(PG),
        secrets, TestCtx.of()).structuredContent();
    assertThat(described.path("primaryKey").toString()).isEqualTo("[\"id\"]");
  }
}
