package ai.msrv.gaterook.connectors.databricks;

import static ai.msrv.gaterook.connectors.databricks.FakeDatabricks.body;
import static ai.msrv.gaterook.connectors.databricks.FakeDatabricks.json;
import static ai.msrv.gaterook.connectors.databricks.FakeDatabricks.param;
import static ai.msrv.gaterook.connectors.databricks.FakeDatabricks.succeeded;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import mockwebserver3.MockResponse;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** The Databricks connector against a fake workspace over HTTPS: what is sent, how it signs in, what is shown. */
class DatabricksConnectorTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ID = "01eda0e7-e315-1846-84e2-79a963ffad44";
  private static final String WAREHOUSE = "1a2b3c4d5e6f7a8b";
  private static final String STATEMENTS = "POST /api/2.0/sql/statements";

  private FakeDatabricks dbx;
  private TestCtx.MovingClock clock;
  private DatabricksConnector databricks;
  private final TestSecrets secrets = new TestSecrets().with("/auth/token", FakeDatabricks.PAT)
      .with("/auth/clientSecret", FakeDatabricks.CLIENT_SECRET);

  @BeforeEach
  void start() throws Exception {
    dbx = new FakeDatabricks();
    clock = new TestCtx.MovingClock(Instant.parse("2026-11-11T09:00:00Z"));
    databricks = new DatabricksConnector(clock, dbx.clientTls);
  }

  @AfterEach
  void stop() {
    databricks.close();
    dbx.close();
  }

  private ConnectionConfig config(Env env) {
    return config(env, c -> {});
  }

  /** A token connection to the fake workspace, default catalog main and schema sales. */
  private ConnectionConfig config(Env env, Consumer<ObjectNode> change) {
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("host", dbx.host()).put("warehouseId", WAREHOUSE)
        .put("catalog", "main").put("schema", "sales");
    c.putObject("auth").put("method", "token");
    change.accept(c);
    return new ConnectionConfig("conn-dbx-" + env.id(), env.id() + "-dbx", "databricks", env, 1, c,
        Set.of("/auth/token", "/auth/clientSecret"), null);
  }

  private static Consumer<ObjectNode> oauth() {
    return c -> c.putObject("auth").put("method", "oauth").put("clientId", FakeDatabricks.CLIENT_ID);
  }

  private static Consumer<ObjectNode> lists() {
    return c -> {
      c.putArray("catalogs").add("main").add("audit*");
      c.putArray("schemas").add("sales").add("main.crm").add("audit*.*");
    };
  }

  private static PolicyView policy(Env env) {
    return new PolicyView(env, env.id() + ".databricks", JsonNodeFactory.instance.objectNode(),
        env == Env.PROD ? TestCtx.prod() : TestCtx.dev());
  }

  private static ToolCall call(String tool, String args) {
    return new ToolCall("tc_" + Long.toHexString(System.nanoTime()), tool, "dev-dbx",
        (ObjectNode) JSON.readTree(args), null);
  }

  private static String sql(String sql) {
    return JSON.writeValueAsString(Map.of("sql", sql));
  }

  private Classification classify(String tool, String args, Consumer<ObjectNode> change) {
    return databricks.classify(call(tool, args), config(Env.DEV, change), policy(Env.DEV));
  }

  private Classification classify(String tool, String args) {
    return classify(tool, args, c -> {});
  }

  // ── Tools and routes ────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void publishesReadsAndOneApprovedChange() {
    assertThat(databricks.manifest().id()).isEqualTo("databricks");
    assertThat(databricks.manifest().name()).isEqualTo("Databricks SQL");
    assertThat(databricks.manifest().group()).isEqualTo("Databases");
    assertThat(databricks.manifest().icon()).isEqualTo("database");
    assertThat(databricks.manifest().tools()).extracting(ToolDefinition::name).containsExactly(
        "databricks_list_catalogs", "databricks_list_schemas", "databricks_list_tables", "databricks_describe_table",
        "databricks_query", "databricks_execute");
    ToolDefinition execute = databricks.manifest().tools().getLast();
    assertThat(execute.mutating()).isTrue();
    assertThat(execute.readOnlyModeAllowed()).isFalse();
    assertThat(execute.defaultMode()).isEqualTo(ToolDefinition.Mode.APPROVAL);
    assertThat(execute.prodDefaultMode()).isEqualTo(ToolDefinition.Mode.APPROVAL);
    assertThat(execute.inputSchema().at("/properties/note/maxLength").asInt()).isEqualTo(500);
    assertThat(databricks.manifest().tools().subList(0, 5)).allSatisfy(t -> {
      assertThat(t.nature()).isEqualTo(ToolDefinition.Nature.READ);
      assertThat(t.defaultMode()).isEqualTo(ToolDefinition.Mode.READ_ONLY);
    });
  }

  @Test
  void statementsAreRoutedByWhatTheyDo() {
    assertThat(classify("databricks_query", sql("SELECT id FROM main.sales.orders WHERE note = 'DELETE; DROP'"))
        .kind()).isEqualTo(Classification.Kind.READ);
    Classification writes = classify("databricks_query", sql("DELETE FROM main.sales.orders WHERE true"));
    assertThat(writes.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(writes.ruleId()).isEqualTo("dev.databricks.query-writes");
    Classification change = classify("databricks_execute", sql("UPDATE main.sales.orders SET paid = true"));
    assertThat(change.kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(change.facts().path("target").asString()).isEqualTo("main.sales.orders");
    assertThat(classify("databricks_execute", sql("SELECT 1")).ruleId()).isEqualTo("dev.databricks.execute-read");
    assertThat(classify("databricks_query", sql("SELECT * FROM read_files('s3://acme/x')")).ruleId())
        .isEqualTo("dev.databricks.forbidden");
    assertThat(classify("databricks_execute", sql("COPY INTO main.sales.orders FROM 's3://acme/x'")).ruleId())
        .isEqualTo("dev.databricks.forbidden");
    assertThat(classify("databricks_query", sql("SELECT 'open")).ruleId()).isEqualTo("dev.databricks.unparsed");
  }

  @Test
  void catalogsAndSchemasOffTheListsAreRefusedWhereAStatementNamesThem() {
    for (String read : List.of("SELECT * FROM orders", "SELECT * FROM main.sales.orders",
        "SELECT * FROM crm.customers", "SELECT * FROM audit_eu.trail.events", "WITH x AS (SELECT 1) SELECT * FROM x",
        "SELECT o.id FROM orders o JOIN main.crm.customers c ON o.c = c.id", "SHOW TABLES",
        "DESCRIBE TABLE main.sales.orders", "SELECT main.sales.discount(price) FROM orders")) {
      assertThat(classify("databricks_query", sql(read), lists()).kind()).as(read)
          .isEqualTo(Classification.Kind.READ);
    }
    for (String read : List.of("SELECT * FROM hr.salaries", "SELECT * FROM other.sales.orders",
        "SELECT * FROM orders o JOIN hr.salaries s ON o.id = s.id",
        "SELECT * FROM orders o JOIN main.crm.c x ON o.id = x.id, hr.salaries s",
        "SELECT * FROM orders, hr.salaries", "SELECT * FROM orders WHERE id IN (SELECT id FROM other.x.y)",
        "SELECT main.hr.raise(1)", "SHOW CATALOGS", "SHOW SCHEMAS", "SHOW TABLES IN hr", "SHOW SCHEMAS IN other",
        "DESCRIBE TABLE main.hr.salaries", "DESCRIBE SCHEMA hr", "SELECT * FROM a.b.c.d")) {
      Classification c = classify("databricks_query", sql(read), lists());
      assertThat(c.kind()).as(read).isEqualTo(Classification.Kind.DENY);
      assertThat(c.ruleId()).as(read).isEqualTo("dev.databricks.not-allowed");
    }
    for (String change : List.of("INSERT INTO hr.t SELECT * FROM orders", "INSERT INTO orders SELECT * FROM hr.t",
        "ALTER TABLE sales.t RENAME TO hr.t", "CREATE TABLE sales.copy SHALLOW CLONE hr.salaries",
        "MERGE INTO sales.t USING hr.s ON t.id = s.id WHEN MATCHED THEN DELETE", "DROP SCHEMA hr",
        "CREATE TABLE sales.t2 LIKE hr.salaries", "OPTIMIZE other.sales.orders")) {
      assertThat(classify("databricks_execute", sql(change), lists()).ruleId()).as(change)
          .isEqualTo("dev.databricks.not-allowed");
    }
    assertThat(classify("databricks_list_schemas", "{\"catalog\": \"other\"}", lists()).ruleId())
        .isEqualTo("dev.databricks.not-allowed");
    assertThat(classify("databricks_list_tables", "{\"schema\": \"hr\"}", lists()).ruleId())
        .isEqualTo("dev.databricks.not-allowed");
    assertThat(classify("databricks_list_tables", "{\"catalog\": \"audit_eu\", \"schema\": \"trail\"}", lists())
        .kind()).isEqualTo(Classification.Kind.READ);
    assertThat(classify("databricks_describe_table", "{\"table\": \"hr.salaries\"}", lists()).ruleId())
        .isEqualTo("dev.databricks.not-allowed");
    assertThat(classify("databricks_describe_table", "{\"table\": \"`main`.crm.customers\"}", lists()).kind())
        .isEqualTo(Classification.Kind.READ);
    // A list needs the defaults of statements that name no catalog or schema to be on it.
    assertThat(classify("databricks_query", sql("SELECT 1"), c -> {
      lists().accept(c);
      c.remove("catalog");
    }).ruleId()).isEqualTo("dev.databricks.bad-config");
    assertThat(classify("databricks_query", sql("SELECT 1"), c -> {
      lists().accept(c);
      c.put("schema", "hr");
    }).ruleId()).isEqualTo("dev.databricks.bad-config");
  }

  @Test
  void parametersAreNamedScalarValuesTheStatementUses() {
    String query = "SELECT * FROM orders WHERE id = :id AND day >= :since";
    assertThat(classify("databricks_query", "{\"sql\": \"" + query + "\", \"parameters\": [{\"name\": \"id\", "
        + "\"value\": 42}, {\"name\": \"since\", \"value\": \"2026-01-01\", \"type\": \"DATE\"}]}").kind())
        .isEqualTo(Classification.Kind.READ);
    for (String p : List.of("[{\"name\": \"id\", \"value\": 42}]",
        "[{\"name\": \"id\", \"value\": 42}, {\"name\": \"since\", \"value\": {\"a\": 1}}]",
        "[{\"name\": \"id\", \"value\": 42}, {\"name\": \"since\", \"value\": 1, \"type\": \"ARRAY<INT>\"}]",
        "[{\"name\": \"id\", \"value\": 42}, {\"name\": \"since\", \"value\": 1, \"type\": \"date\"}]",
        "[{\"name\": \"id\", \"value\": 1}, {\"name\": \"id\", \"value\": 2}, {\"name\": \"since\", \"value\": 1}]",
        "[{\"name\": \"id\", \"value\": 1}, {\"name\": \"since\", \"value\": 1}, {\"name\": \"x\", \"value\": 1}]")) {
      Classification c = classify("databricks_query", "{\"sql\": \"" + query + "\", \"parameters\": " + p + "}");
      assertThat(c.kind()).as(p).isEqualTo(Classification.Kind.DENY);
      assertThat(c.ruleId()).as(p).isEqualTo("dev.databricks.bad-argument");
    }
    // Casts and JSON paths are no parameters.
    assertThat(classify("databricks_query", sql("SELECT amount::INT, raw:owner FROM orders")).kind())
        .isEqualTo(Classification.Kind.READ);
    assertThat(DatabricksConnector.parameterList(JSON.readTree("[{\"name\": \"a\", \"value\": 42}, {\"name\": \"b\", "
        + "\"value\": 1.5}, {\"name\": \"c\", \"value\": true}, {\"name\": \"d\", \"value\": null}, {\"name\": \"e\", "
        + "\"value\": \"x\"}, {\"name\": \"f\", \"value\": \"9\", \"type\": \"DECIMAL(10,2)\"}]")).toString())
        .isEqualTo("[{\"name\":\"a\",\"value\":\"42\",\"type\":\"BIGINT\"},{\"name\":\"b\",\"value\":\"1.5\","
            + "\"type\":\"DOUBLE\"},{\"name\":\"c\",\"value\":\"true\",\"type\":\"BOOLEAN\"},{\"name\":\"d\","
            + "\"value\":null},{\"name\":\"e\",\"value\":\"x\"},{\"name\":\"f\",\"value\":\"9\",\"type\":"
            + "\"DECIMAL(10,2)\"}]");
  }

  // ── What is sent and what comes back ───────────────────────────────────────────────────────────────────────

  @Test
  void aReadIsOneStatementOnTheWarehouseWithTheConnectionsDefaults() throws Exception {
    String columns = "id:LONG:BIGINT;amount:DECIMAL:DECIMAL(12,2);paid:BOOLEAN;at:TIMESTAMP;day:TIMESTAMP_NTZ;"
        + "raw:BINARY;tags:ARRAY:ARRAY<STRING>;buyer:STRUCT:STRUCT<name: STRING>;attrs:MAP:MAP<STRING, INT>;"
        + "score:DOUBLE;note:STRING";
    dbx.on(STATEMENTS, succeeded(ID, columns, "[[\"9007199254740993\",\"12.50\",\"true\","
        + "\"2016-02-14T16:52:13.000Z\",\"2016-02-14 16:52:13\",\"AP8Q\",\"[\\\"a\\\",\\\"b\\\"]\","
        + "\"{\\\"name\\\":\\\"Ada\\\"}\",\"{\\\"k\\\":9007199254740993}\",\"0.5\",null]]", 1, 1, null));
    TestCtx ctx = TestCtx.of();
    ToolResult r = databricks.execute(call("databricks_query", "{\"sql\": \"SELECT * FROM orders WHERE id = :id\", "
        + "\"parameters\": [{\"name\": \"id\", \"value\": 7}]}"), config(Env.DEV), secrets, ctx);

    RecordedRequest sent = dbx.to(STATEMENTS).getFirst();
    assertThat(sent.getHeaders().get("Authorization")).isEqualTo("Bearer " + FakeDatabricks.PAT);
    JsonNode body = body(sent);
    assertThat(body.path("warehouse_id").asString()).isEqualTo(WAREHOUSE);
    assertThat(body.path("statement").asString()).isEqualTo("SELECT * FROM orders WHERE id = :id");
    assertThat(body.path("catalog").asString()).isEqualTo("main");
    assertThat(body.path("schema").asString()).isEqualTo("sales");
    assertThat(body.path("parameters").toString()).isEqualTo("[{\"name\":\"id\",\"value\":\"7\",\"type\":"
        + "\"BIGINT\"}]");
    assertThat(body.path("disposition").asString()).isEqualTo("INLINE");
    assertThat(body.path("format").asString()).isEqualTo("JSON_ARRAY");
    // What the call has left (10 s), less two to answer: 7 or 8 s, as the clock runs.
    assertThat(body.path("wait_timeout").asString()).isIn("7s", "8s");
    assertThat(body.path("on_wait_timeout").asString()).isEqualTo("CANCEL");
    assertThat(body.path("row_limit").asLong()).isEqualTo(10_000);
    assertThat(body.path("byte_limit").asLong()).isEqualTo(8L * 1024 * 1024);
    assertThat(ctx.taints).contains(FakeDatabricks.PAT);

    assertThat(r.table().columns()).extracting(ToolResult.Column::name).containsExactly("id", "amount", "paid", "at",
        "day", "raw", "tags", "buyer", "attrs", "score", "note");
    assertThat(r.table().columns().get(1).type()).isEqualTo("DECIMAL(12,2)");
    JsonNode row = r.table().rows().get(0);
    assertThat(row.get(0).isString()).as("beyond 2^53: text").isTrue();
    assertThat(row.get(1).asString()).isEqualTo("12.50");
    assertThat(row.get(2).asBoolean()).isTrue();
    assertThat(row.get(3).asString()).isEqualTo("2016-02-14T16:52:13Z");
    assertThat(row.get(4).asString()).isEqualTo("2016-02-14T16:52:13");
    assertThat(row.get(5).asString()).isEqualTo("\\x00ff10");
    assertThat(row.get(6).toString()).isEqualTo("[\"a\",\"b\"]");
    assertThat(row.get(7).path("name").asString()).isEqualTo("Ada");
    assertThat(row.get(8).path("k").asString()).isEqualTo("9007199254740993");
    assertThat(row.get(9).asDouble()).isEqualTo(0.5);
    assertThat(row.get(10).isNull()).isTrue();
    assertThat(r.table().nextCursor()).isNull();
    assertThat(r.structuredContent().path("statement").asString()).isEqualTo(ID);
    assertThat(r.structuredContent().path("totalRows").asLong()).isEqualTo(1);
  }

  @Test
  void aServicePrincipalSignsInOnceAndItsTokenIsKeptUntilShortlyBeforeItExpires() throws Exception {
    dbx.on(STATEMENTS, succeeded(ID, "x:INT", "[[\"1\"]]", 1, 1, null));
    ConnectionConfig cfg = config(Env.DEV, oauth());
    TestCtx ctx = TestCtx.of();
    databricks.execute(call("databricks_query", sql("SELECT 1 AS x")), cfg, secrets, ctx);

    RecordedRequest signIn = dbx.to("POST /oidc/v1/token").getFirst();
    String basic = Base64.getEncoder().encodeToString((FakeDatabricks.CLIENT_ID + ":" + FakeDatabricks.CLIENT_SECRET)
        .getBytes(StandardCharsets.UTF_8));
    assertThat(signIn.getHeaders().get("Authorization")).isEqualTo("Basic " + basic);
    assertThat(signIn.getHeaders().get("Content-Type")).startsWith("application/x-www-form-urlencoded");
    assertThat(signIn.getBody().utf8()).isEqualTo("grant_type=client_credentials&scope=all-apis");
    assertThat(dbx.to(STATEMENTS).getFirst().getHeaders().get("Authorization"))
        .isEqualTo("Bearer " + FakeDatabricks.OAUTH_TOKEN);
    assertThat(ctx.taints).contains(FakeDatabricks.CLIENT_SECRET, basic, FakeDatabricks.OAUTH_TOKEN);

    // The next call uses the token it has, and taints it again.
    TestCtx next = TestCtx.of();
    databricks.execute(call("databricks_query", sql("SELECT 1 AS x")), cfg, secrets, next);
    assertThat(dbx.to("POST /oidc/v1/token")).hasSize(1);
    assertThat(next.taints).contains(FakeDatabricks.OAUTH_TOKEN);
    // Shortly before it expires, a new one.
    clock.advance(Duration.ofMinutes(59).plusSeconds(1));
    databricks.execute(call("databricks_query", sql("SELECT 1 AS x")), cfg, secrets, TestCtx.of());
    assertThat(dbx.to("POST /oidc/v1/token")).hasSize(2);
    // A rotated secret signs in again at once.
    secrets.version("2");
    databricks.execute(call("databricks_query", sql("SELECT 1 AS x")), cfg, secrets, TestCtx.of());
    assertThat(dbx.to("POST /oidc/v1/token")).hasSize(3);
    // A token Databricks refuses is dropped: the next call signs in again.
    dbx.on(STATEMENTS, r -> json(401, "{\"error_code\":\"UNAUTHENTICATED\",\"message\":\"Invalid token\"}"));
    assertThatThrownBy(() -> databricks.execute(call("databricks_query", sql("SELECT 1 AS x")), cfg, secrets,
        TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE));
    dbx.on(STATEMENTS, succeeded(ID, "x:INT", "[[\"1\"]]", 1, 1, null));
    databricks.execute(call("databricks_query", sql("SELECT 1 AS x")), cfg, secrets, TestCtx.of());
    assertThat(dbx.to("POST /oidc/v1/token")).hasSize(4);
    // And a changed connection forgets its token.
    databricks.onConnectionChanged(cfg.id());
    databricks.execute(call("databricks_query", sql("SELECT 1 AS x")), cfg, secrets, TestCtx.of());
    assertThat(dbx.to("POST /oidc/v1/token")).hasSize(5);
  }

  @Test
  void aRefusedSignInSaysWhatToDoWithoutTheSecret() {
    dbx.on("POST /oidc/v1/token", r -> json(401, "{\"error\":\"invalid_client\",\"error_description\":\"Client "
        + "authentication failed\"}"));
    assertThatThrownBy(() -> databricks.execute(call("databricks_list_catalogs", "{}"), config(Env.DEV, oauth()),
        secrets, TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE);
          assertThat(e.getMessage()).contains("client ID or secret", "Client authentication failed")
              .doesNotContain(FakeDatabricks.CLIENT_SECRET);
        });
    assertThat(dbx.to("GET /api")).isEmpty();
  }

  @Test
  void pagesGoOnFromTheChunkAndRowWhereTheyStopped() throws Exception {
    String first = succeeded(ID, "id:INT", "[[\"1\"],[\"2\"],[\"3\"]]", 2, 5, 1);
    dbx.on(STATEMENTS, first)
        .on("GET /api/2.0/sql/statements/" + ID, first)
        .on("GET /api/2.0/sql/statements/" + ID + "/result/chunks/1", "{\"chunk_index\":1,\"row_offset\":3,"
            + "\"row_count\":2,\"data_array\":[[\"4\"],[\"5\"]]}");
    String args = "{\"sql\": \"SELECT id FROM orders\", \"limit\": 2}";
    ToolResult one = databricks.execute(call("databricks_query", args), config(Env.DEV), secrets, TestCtx.of());
    assertThat(one.table().rows().toString()).isEqualTo("[[1],[2]]");
    assertThat(one.table().nextCursor()).startsWith("dbx1.");
    ToolResult two = databricks.execute(call("databricks_query", "{\"sql\": \"SELECT id FROM orders\", \"limit\": 2, "
        + "\"cursor\": \"" + one.table().nextCursor() + "\"}"), config(Env.DEV), secrets, TestCtx.of());
    // Row 3 is the rest of the first chunk, row 4 the start of the next one.
    assertThat(two.table().rows().toString()).isEqualTo("[[3],[4]]");
    ToolResult three = databricks.execute(call("databricks_query", "{\"sql\": \"SELECT id FROM orders\", "
        + "\"limit\": 2, \"cursor\": \"" + two.table().nextCursor() + "\"}"), config(Env.DEV), secrets,
        TestCtx.of());
    assertThat(three.table().rows().toString()).isEqualTo("[[5]]");
    assertThat(three.table().nextCursor()).isNull();
    // One statement: the next pages read the same statement's result.
    assertThat(dbx.to(STATEMENTS)).hasSize(1);

    assertThatThrownBy(() -> databricks.execute(call("databricks_query", "{\"sql\": \"SELECT 2\", \"cursor\": \""
        + one.table().nextCursor() + "\"}"), config(Env.DEV), secrets, TestCtx.of()))
        .isInstanceOf(ConnectorException.class);
    assertThatThrownBy(() -> databricks.execute(call("databricks_query", "{\"sql\": \"SELECT id FROM orders\", "
        + "\"limit\": 2, \"cursor\": \"dbx1.forged\"}"), config(Env.DEV), secrets, TestCtx.of()))
        .isInstanceOf(ConnectorException.class);
    // An expired result says so.
    dbx.on("GET /api/2.0/sql/statements/" + ID, "{\"statement_id\":\"" + ID + "\",\"status\":{\"state\":"
        + "\"CLOSED\"}}");
    assertThatThrownBy(() -> databricks.execute(call("databricks_query", "{\"sql\": \"SELECT id FROM orders\", "
        + "\"limit\": 2, \"cursor\": \"" + one.table().nextCursor() + "\"}"), config(Env.DEV), secrets,
        TestCtx.of())).hasMessageContaining("no longer available");
  }

  @Test
  void aQueryThatOutlivesTheCallIsCancelled() {
    AtomicInteger cancels = new AtomicInteger();
    dbx.on(STATEMENTS, "{\"statement_id\":\"" + ID + "\",\"status\":{\"state\":\"PENDING\"}}")
        .on("GET /api/2.0/sql/statements/" + ID, "{\"statement_id\":\"" + ID + "\",\"status\":{\"state\":"
            + "\"RUNNING\"}}")
        .on("POST /api/2.0/sql/statements/" + ID + "/cancel", r -> {
          cancels.incrementAndGet();
          return json(200, "{}");
        });
    assertThatThrownBy(() -> databricks.execute(call("databricks_query", sql("SELECT 1")), config(Env.DEV), secrets,
        TestCtx.of().timeout(Duration.ofMillis(2_500))))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT);
          assertThat(e.getMessage()).contains("cancelled statement " + ID);
        });
    assertThat(body(dbx.to(STATEMENTS).getFirst()).path("wait_timeout").asString()).isEqualTo("0s");
    assertThat(cancels.get()).isEqualTo(1);
    // With time to wait on the server, Databricks cancels it there.
    dbx.on(STATEMENTS, "{\"statement_id\":\"" + ID + "\",\"status\":{\"state\":\"CANCELED\"}}");
    assertThatThrownBy(() -> databricks.execute(call("databricks_query", sql("SELECT 1")), config(Env.DEV), secrets,
        TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT));
  }

  @Test
  void aChangeRunsOnlyApprovedOnceAndIsNeverCancelled() throws Exception {
    dbx.on(STATEMENTS, succeeded(ID, "num_affected_rows:LONG:BIGINT;num_updated_rows:LONG:BIGINT", "[[\"3\",\"3\"]]",
        1, 1, null));
    String text = "UPDATE main.sales.orders SET paid = true WHERE region = :region";
    ToolCall c = call("databricks_execute", "{\"sql\": \"" + text + "\", \"parameters\": [{\"name\": \"region\", "
        + "\"value\": \"eu\"}], \"note\": \"Close the paid orders\"}");
    assertThatThrownBy(() -> databricks.execute(c, config(Env.DEV), secrets, TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).contains("Not approved");
        });
    assertThat(dbx.requests).isEmpty();

    ActionPreview preview = databricks.describeAction(c, config(Env.DEV));
    assertThat(preview.kind()).isEqualTo(ActionPreview.Kind.SQL);
    assertThat(preview.text()).isEqualTo(text);
    assertThat(preview.payload().toString()).isEqualTo("{\"sql\":\"" + text + "\",\"parameters\":[{\"name\":"
        + "\"region\",\"value\":\"eu\"}]}");
    ToolResult r = databricks.execute(c, config(Env.DEV), secrets, TestCtx.of().approved(preview.payload()));
    assertThat(r.structuredContent().path("committed").asBoolean()).isTrue();
    assertThat(r.structuredContent().path("rowsAffected").asInt()).isEqualTo(3);
    assertThat(r.structuredContent().path("rowsUpdated").asInt()).isEqualTo(3);
    assertThat(r.content().getFirst().text()).isEqualTo("3 rows affected by UPDATE.");
    JsonNode sent = body(dbx.to(STATEMENTS).getFirst());
    assertThat(sent.path("statement").asString()).isEqualTo(text);
    assertThat(sent.path("on_wait_timeout").asString()).isEqualTo("CONTINUE");
    assertThat(sent.has("row_limit")).isFalse();
    assertThat(sent.path("parameters").toString()).isEqualTo("[{\"name\":\"region\",\"value\":\"eu\"}]");

    // What runs is the approved text, whatever the call says now.
    dbx.requests.clear();
    databricks.execute(call("databricks_execute", sql("DELETE FROM main.sales.orders WHERE true")), config(Env.DEV),
        secrets, TestCtx.of().approved(preview.payload()));
    assertThat(body(dbx.to(STATEMENTS).getFirst()).path("statement").asString()).isEqualTo(text);
  }

  @Test
  void aLostOrUnfinishedChangeIsAnUnknownOutcomeAndAFailedOneSaysWhatHappened() {
    ToolCall c = call("databricks_execute", sql("DELETE FROM main.sales.orders WHERE id = 1"));
    JsonNode approved = databricks.describeAction(c, config(Env.DEV)).payload();
    dbx.on(STATEMENTS, r -> json(503, "{\"error_code\":\"TEMPORARILY_UNAVAILABLE\",\"message\":\"later\"}"));
    assertThatThrownBy(() -> databricks.execute(c, config(Env.DEV), secrets, TestCtx.of().approved(approved)))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN));

    dbx.on(STATEMENTS, "{\"statement_id\":\"" + ID + "\",\"status\":{\"state\":\"PENDING\"}}")
        .on("GET /api/2.0/sql/statements/" + ID, "{\"statement_id\":\"" + ID + "\",\"status\":{\"state\":"
            + "\"RUNNING\"}}");
    assertThatThrownBy(() -> databricks.execute(c, config(Env.DEV), secrets, TestCtx.of().timeout(
        Duration.ofMillis(2_500)).approved(approved)))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN);
          assertThat(e.getMessage()).contains("still running the change as statement " + ID);
        });
    assertThat(dbx.to("POST /api/2.0/sql/statements/" + ID + "/cancel")).as("a change is never cancelled").isEmpty();

    dbx.on(STATEMENTS, "{\"statement_id\":\"" + ID + "\",\"status\":{\"state\":\"FAILED\",\"error\":{\"error_code\":"
        + "\"BAD_REQUEST\",\"message\":\"[DELTA_CONCURRENT_APPEND] Files were added by a concurrent update\"}}}");
    assertThatThrownBy(() -> databricks.execute(c, config(Env.DEV), secrets, TestCtx.of().approved(approved)))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
          assertThat(e.getMessage()).contains("DELTA_CONCURRENT_APPEND", "changes nothing");
        });
  }

  @Test
  void listsAndDescribesWithinTheLists() throws Exception {
    dbx.on("GET /api/2.1/unity-catalog/catalogs", "{\"catalogs\":[{\"name\":\"main\",\"catalog_type\":"
            + "\"MANAGED_CATALOG\"},{\"name\":\"hr\"},{\"name\":\"audit_eu\",\"comment\":\"Audit trail\"}],"
            + "\"next_page_token\":\"c2\"}")
        .on("GET /api/2.1/unity-catalog/schemas", "{\"schemas\":[{\"name\":\"sales\"},{\"name\":\"crm\"},{\"name\":"
            + "\"hr\"}]}")
        .on("GET /api/2.1/unity-catalog/tables", "{\"tables\":[{\"name\":\"orders\",\"table_type\":\"MANAGED\","
            + "\"data_source_format\":\"DELTA\"},{\"name\":\"v_paid\",\"table_type\":\"VIEW\"}]}")
        .on("GET /api/2.1/unity-catalog/tables/main.sales.orders", "{\"name\":\"orders\",\"table_type\":\"MANAGED\","
            + "\"data_source_format\":\"DELTA\",\"comment\":\"Orders\",\"columns\":[{\"name\":\"id\",\"type_text\":"
            + "\"bigint\",\"type_name\":\"LONG\",\"nullable\":false},{\"name\":\"day\",\"type_text\":\"date\","
            + "\"type_name\":\"DATE\",\"partition_index\":0}]}");
    ConnectionConfig cfg = config(Env.DEV, lists());
    ToolResult catalogs = databricks.execute(call("databricks_list_catalogs", "{}"), cfg, secrets, TestCtx.of());
    assertThat(catalogs.structuredContent().path("catalogs").toString()).contains("main", "audit_eu")
        .doesNotContain("\"hr\"");
    assertThat(catalogs.structuredContent().path("nextCursor").asString()).startsWith("dbx1.");
    assertThat(param(dbx.to("GET /api/2.1/unity-catalog/catalogs").getFirst(), "max_results")).isEqualTo("50");

    ToolResult schemas = databricks.execute(call("databricks_list_schemas", "{}"), cfg, secrets, TestCtx.of());
    assertThat(param(dbx.to("GET /api/2.1/unity-catalog/schemas").getFirst(), "catalog_name")).isEqualTo("main");
    assertThat(schemas.structuredContent().path("schemas").toString()).contains("sales", "crm")
        .doesNotContain("\"hr\"");

    ToolResult tables = databricks.execute(call("databricks_list_tables", "{}"), cfg, secrets, TestCtx.of());
    RecordedRequest listed = dbx.to("GET /api/2.1/unity-catalog/tables").getFirst();
    assertThat(param(listed, "catalog_name")).isEqualTo("main");
    assertThat(param(listed, "schema_name")).isEqualTo("sales");
    assertThat(param(listed, "omit_columns")).isEqualTo("true");
    assertThat(tables.content().getFirst().text()).contains("orders", "v_paid (view)");

    ToolResult d = databricks.execute(call("databricks_describe_table", "{\"table\": \"orders\"}"), cfg, secrets,
        TestCtx.of());
    assertThat(d.structuredContent().path("table").asString()).isEqualTo("main.sales.orders");
    assertThat(d.structuredContent().path("partitionedBy").toString()).isEqualTo("[\"day\"]");
    assertThat(d.content().getFirst().text()).isEqualTo("main.sales.orders (managed, delta): id bigint, day date · "
        + "partitioned by day");
    assertThatThrownBy(() -> databricks.execute(call("databricks_describe_table", "{\"table\": \"hr.salaries\"}"),
        cfg, secrets, TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
  }

  @Test
  void theWorkspaceIsAnHttpsNameOrALoopbackAddress() throws Exception {
    assertThat(DatabricksSettings.base("https://DBC-a1b2c3d4-e5f6.cloud.databricks.com/").toString())
        .isEqualTo("https://dbc-a1b2c3d4-e5f6.cloud.databricks.com/");
    assertThat(DatabricksSettings.base("https://127.0.0.1:8443").toString()).isEqualTo("https://127.0.0.1:8443/");
    assertThat(DatabricksSettings.base("https://[::1]:8443").toString()).isEqualTo("https://[::1]:8443/");
    for (String bad : List.of("http://dbc-a1b2c3d4-e5f6.cloud.databricks.com", "https://10.0.0.7",
        "https://169.254.169.254", "https://2130706433", "https://0x7f.0.0.1", "https://[fd00::1]",
        "https://user@dbc.cloud.databricks.com", "https://dbc.cloud.databricks.com/sql/1.0/warehouses/x",
        "https://dbc.cloud.databricks.com/?o=1", "ftp://dbc.cloud.databricks.com", "dbc.cloud.databricks.com", "")) {
      assertThatThrownBy(() -> DatabricksSettings.base(bad)).as(bad).isInstanceOf(ConnectorException.class);
    }
  }

  @Test
  void aWorkspaceNameOfCloudMetadataIsNeverCalled() {
    ConnectionConfig cfg = config(Env.DEV, c -> c.put("host", "https://metadata.google.internal"));
    assertThatThrownBy(() -> databricks.execute(call("databricks_list_catalogs", "{}"), cfg, secrets, TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    assertThat(dbx.requests).isEmpty();
  }

  @Test
  void redirectsAreNeverFollowed() {
    dbx.on("GET /api/2.1/unity-catalog/catalogs", r -> new MockResponse.Builder().code(302)
        .addHeader("Location", "https://login.example.com/sso?next=x").build());
    assertThatThrownBy(() -> databricks.execute(call("databricks_list_catalogs", "{}"), config(Env.DEV), secrets,
        TestCtx.of())).hasMessageContaining("redirected to login.example.com");
    assertThat(dbx.requests).hasSize(1);
  }

  @Test
  void databricksErrorsSayWhatToDo() {
    dbx.on("GET /api/2.1/unity-catalog/catalogs", r -> json(403, "{\"error_code\":\"PERMISSION_DENIED\","
        + "\"message\":\"User does not have USE CATALOG on main\"}"));
    assertThatThrownBy(() -> databricks.execute(call("databricks_list_catalogs", "{}"), config(Env.DEV), secrets,
        TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
          assertThat(e.getMessage()).contains("USE CATALOG on main", "CAN USE on the warehouse");
        });
    dbx.on(STATEMENTS, "{\"statement_id\":\"" + ID + "\",\"status\":{\"state\":\"FAILED\",\"error\":{\"error_code\":"
        + "\"BAD_REQUEST\",\"message\":\"[TABLE_OR_VIEW_NOT_FOUND] The table or view `nope` cannot be found."
        + "\\nmore\"}}}");
    assertThatThrownBy(() -> databricks.execute(call("databricks_query", sql("SELECT * FROM nope")), config(Env.DEV),
        secrets, TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
          assertThat(e.getMessage()).contains("TABLE_OR_VIEW_NOT_FOUND").doesNotContain("more");
        });
  }

  @Test
  void testConnectionNamesTheIdentityAndRunsAQueryOnlyOnARunningWarehouse() {
    dbx.on("GET /api/2.0/preview/scim/v2/Me", "{\"userName\":\"ada@example.com\"}")
        .on("GET /api/2.0/sql/warehouses/" + WAREHOUSE, "{\"id\":\"" + WAREHOUSE + "\",\"name\":\"Starter "
            + "Warehouse\",\"state\":\"RUNNING\"}")
        .on("GET /api/2.1/unity-catalog/catalogs", "{\"catalogs\":[{\"name\":\"main\"},{\"name\":\"samples\"}]}")
        .on(STATEMENTS, succeeded(ID, "user:STRING;catalog:STRING;schema:STRING", "[[\"ada@example.com\",\"main\","
            + "\"sales\"]]", 1, 1, null));
    TestResult r = databricks.test(config(Env.PROD), secrets, TestCtx.of().env(Env.PROD));
    assertThat(r.ok()).as(String.valueOf(r.error())).isTrue();
    assertThat(r.identity()).isEqualTo("ada@example.com");
    assertThat(r.serverVersion()).isEqualTo("Databricks SQL · Starter Warehouse");
    assertThat(r.checks()).extracting(TestResult.Check::name).containsExactly("identity", "warehouse", "catalogs",
        "statement");
    assertThat(r.hints()).extracting(TestResult.Hint::code).contains("databricks.personal-token",
        "databricks.read-only-boundary", "databricks.host");

    dbx.requests.clear();
    dbx.on("GET /api/2.0/sql/warehouses/" + WAREHOUSE, "{\"id\":\"" + WAREHOUSE + "\",\"name\":\"Starter "
        + "Warehouse\",\"state\":\"STOPPED\"}");
    TestResult stopped = databricks.test(config(Env.DEV), secrets, TestCtx.of());
    assertThat(stopped.ok()).isTrue();
    assertThat(stopped.hints()).extracting(TestResult.Hint::code).contains("databricks.warehouse-not-running")
        .doesNotContain("databricks.personal-token");
    assertThat(dbx.to(STATEMENTS)).as("a stopped warehouse isn't started").isEmpty();
  }

  @Test
  void theHealthCheckDoesntStartTheWarehouse() {
    dbx.on("GET /api/2.0/sql/warehouses/" + WAREHOUSE, "{\"id\":\"" + WAREHOUSE + "\",\"state\":\"STOPPED\"}");
    assertThat(databricks.health(config(Env.DEV), secrets, TestCtx.of()).state())
        .isEqualTo(HealthResult.State.HEALTHY);
    assertThat(dbx.requests).extracting(r -> r.getMethod() + " " + r.getUrl().encodedPath())
        .containsExactly("GET /api/2.0/sql/warehouses/" + WAREHOUSE);
  }

  @Test
  void anIncompleteConnectionIsRefusedWithoutAnyRequest() {
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("host", "http://dbc.cloud.databricks.com");
    ConnectionConfig broken = new ConnectionConfig("conn-x", "x", "databricks", Env.DEV, 1, c, Set.of(), null);
    assertThat(databricks.classify(call("databricks_list_catalogs", "{}"), broken, policy(Env.DEV)).ruleId())
        .isEqualTo("dev.databricks.bad-config");
    assertThat(databricks.test(broken, secrets, TestCtx.of()).error().code()).isEqualTo("databricks.settings");
    assertThat(dbx.requests).isEmpty();
  }

  @Test
  void cellsKeepTheirTypes() {
    assertThat(Cells.cell(JSON.readTree("\"42\""), "INT", 100).isIntegralNumber()).isTrue();
    assertThat(Cells.cell(JSON.readTree("\"-9007199254740993\""), "LONG", 100).isString()).isTrue();
    assertThat(Cells.cell(JSON.readTree("\"NaN\""), "DOUBLE", 100).asString()).isEqualTo("NaN");
    assertThat(Cells.cell(JSON.readTree("\"1e400\""), "DOUBLE", 100).asString()).isEqualTo("1e400");
    assertThat(Cells.cell(JSON.readTree("\"2016-02-14T16:52:13.5+01:00\""), "TIMESTAMP", 100).asString())
        .isEqualTo("2016-02-14T15:52:13.500Z");
    assertThat(Cells.cell(JSON.readTree("\"not a time\""), "TIMESTAMP", 100).asString()).isEqualTo("not a time");
    assertThat(Cells.cell(JSON.readTree("\"" + "x".repeat(20) + "\""), "STRING", 10).asString())
        .startsWith("xxxxxxxxxx…‹20");
    assertThat(Cells.cell(JSON.readTree("\"[1, {\\\"a\\\": 2}]\""), "ARRAY", 100).toString())
        .isEqualTo("[1,{\"a\":2}]");
    assertThat(Cells.cell(JSON.readTree("\"" + "[1]".repeat(50) + "\""), "ARRAY", 10).asString()).contains("…‹");
    assertThat(Cells.cell(JSON.readTree("\"" + "A".repeat(400) + "\""), "BINARY", 1000).asString())
        .endsWith("…‹300 bytes›");
    assertThat(Cells.cell(JSON.readTree("null"), "STRING", 100).isNull()).isTrue();
  }
}
