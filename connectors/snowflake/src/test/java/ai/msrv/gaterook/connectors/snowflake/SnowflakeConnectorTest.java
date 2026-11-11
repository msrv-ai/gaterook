package ai.msrv.gaterook.connectors.snowflake;

import static ai.msrv.gaterook.connectors.snowflake.FakeSnowflake.Col;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import mockwebserver3.MockResponse;
import mockwebserver3.RecordedRequest;
import mockwebserver3.SocketEffect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** The Snowflake connector against a fake of the SQL API: what is signed, sent, refused and shown. */
class SnowflakeConnectorTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private FakeSnowflake fake;
  private SnowflakeConnector snowflake;
  private final TestSecrets secrets = new TestSecrets().with(SnowflakeSettings.PRIVATE_KEY,
      FakeSnowflake.privateKeyPem());

  @BeforeEach
  void start() {
    fake = new FakeSnowflake();
    snowflake = new SnowflakeConnector(fake.base());
  }

  @AfterEach
  void stop() {
    snowflake.close();
    fake.close();
  }

  private static ObjectNode settings(String... tables) {
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("account", "myorg-myaccount").put("user", "gw_reader")
        .put("warehouse", "compute_wh").put("role", "analyst_ro").put("database", "analytics").put("schema", "public");
    c.putObject("authentication").put("method", "keyPair");
    if (tables.length > 0) {
      var list = c.putArray("tables");
      Arrays.stream(tables).forEach(list::add);
    }
    return c;
  }

  private static ConnectionConfig config(Env env, ObjectNode c) {
    return new ConnectionConfig("conn-sf-" + env.id(), env.id() + "-sf", "snowflake", env, 1, c,
        Set.of(SnowflakeSettings.PRIVATE_KEY, SnowflakeSettings.TOKEN), null);
  }

  private static ConnectionConfig config(String... tables) {
    return config(Env.DEV, settings(tables));
  }

  private static PolicyView policy(Env env) {
    return new PolicyView(env, env.id() + ".snowflake", JsonNodeFactory.instance.objectNode(),
        env == Env.PROD ? TestCtx.prod() : TestCtx.dev());
  }

  private static ToolCall call(String tool, String args) {
    return new ToolCall("tc_" + Long.toHexString(System.nanoTime()), tool, "dev-sf", (ObjectNode) JSON.readTree(args),
        null);
  }

  private static ToolCall sql(String tool, String sql) {
    return new ToolCall("tc_" + Long.toHexString(System.nanoTime()), tool, "dev-sf",
        JsonNodeFactory.instance.objectNode().put("sql", sql), null);
  }

  private static ConnectorException thrown(ThrowingCall c) {
    try {
      c.run();
    } catch (ConnectorException e) {
      return e;
    }
    throw new AssertionError("expected a ConnectorException");
  }

  @FunctionalInterface
  private interface ThrowingCall {
    void run() throws ConnectorException;
  }

  /** EXPLAIN of anything is a scan of ORDERS; the query itself answers {@code answer}. */
  private void planAndAnswer(String prefix, MockResponse answer) {
    fake.on("EXPLAIN USING JSON", b -> FakeSnowflake.plan(FakeSnowflake.scanPlan("ANALYTICS.PUBLIC.ORDERS")))
        .on(prefix, b -> answer);
  }

  // ── Manifest ────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void publishesFourToolsWithChangesWaitingForApproval() {
    assertThat(snowflake.manifest().id()).isEqualTo("snowflake");
    assertThat(snowflake.manifest().name()).isEqualTo("Snowflake");
    assertThat(snowflake.manifest().icon()).isEqualTo("database");
    assertThat(snowflake.manifest().group()).isEqualTo("Databases");
    assertThat(snowflake.manifest().description()).endsWith(".");
    assertThat(snowflake.manifest().configSchema().at("/x-card/description").asString())
        .isEqualTo("Read queries, schemas and tables. Changes wait for approval.");
    assertThat(snowflake.manifest().tools()).extracting(ToolDefinition::name).containsExactly(
        "snowflake_list_schemas", "snowflake_describe_table", "snowflake_query", "snowflake_execute");
    for (ToolDefinition t : snowflake.manifest().tools()) {
      assertThat(t.inputSchema().path("additionalProperties").asBoolean(true)).as(t.name()).isFalse();
      assertThat(t.inputSchema().at("/properties/connection")).as(t.name())
          .isEqualTo(JsonNodeFactory.instance.objectNode().put("type", "string"));
      assertThat(t.title()).isNotEqualTo(t.name());
    }
    List<ToolDefinition> reads = snowflake.manifest().tools().subList(0, 3);
    assertThat(reads).allSatisfy(t -> {
      assertThat(t.nature()).isEqualTo(ToolDefinition.Nature.READ);
      assertThat(t.defaultMode()).isEqualTo(ToolDefinition.Mode.READ_ONLY);
      assertThat(t.prodDefaultMode()).isEqualTo(ToolDefinition.Mode.READ_ONLY);
      assertThat(t.outputSchema()).isNotNull();
    });
    ToolDefinition execute = snowflake.manifest().tools().getLast();
    assertThat(execute.mutating()).isTrue();
    assertThat(execute.outputSchema()).isNull();
    assertThat(execute.annotations()).isEqualTo(ToolDefinition.Annotations.write(true));
    assertThat(execute.readOnlyModeAllowed()).isFalse();
    assertThat(List.of(execute.defaultMode(), execute.prodDefaultMode())).containsOnly(ToolDefinition.Mode.APPROVAL);
    // The core shows `note` to the approver, as for sql_execute; there is no other field for the agent's reason.
    assertThat(execute.inputSchema().at("/properties/note/maxLength").asInt()).isEqualTo(500);
    assertThat(execute.inputSchema().at("/properties/note/description").asString())
        .isEqualTo("Why you need this change. Shown to the approver as unverified.");
    assertThat(execute.inputSchema().path("properties").has("reason")).isFalse();
    assertThat(snowflake.manifest().access().hosts()).containsExactly("*.snowflakecomputing.com");
  }

  // ── Sign-in and what is sent ────────────────────────────────────────────────────────────────────────────────

  @Test
  void everyRequestCarriesAKeyPairJwtTheTestKeyVerifies() throws Exception {
    planAndAnswer("SELECT ID", FakeSnowflake.result(List.of(Col.of("ID", "FIXED")), List.of(List.of("1"))));
    TestCtx ctx = TestCtx.of();
    ToolResult r = snowflake.execute(sql("snowflake_query", "SELECT ID FROM orders"), config(), secrets, ctx);

    assertThat(fake.problems).isEmpty();
    assertThat(fake.requests).hasSize(2);
    RecordedRequest sent = fake.requests.getLast();
    assertThat(sent.getHeaders().get("X-Snowflake-Authorization-Token-Type")).isEqualTo("KEYPAIR_JWT");
    assertThat(sent.getHeaders().get("Content-Type")).isEqualTo("application/json");
    assertThat(sent.getHeaders().get("User-Agent")).startsWith("GateRook/");
    assertThat(UUID.fromString(sent.getUrl().queryParameter("requestId"))).isNotNull();
    String jwt = sent.getHeaders().get("Authorization").substring(7);
    JsonNode claims = FakeSnowflake.claims(jwt);
    assertThat(claims.path("iss").asString()).isEqualTo("MYORG-MYACCOUNT.GW_READER." + FakeSnowflake.fingerprint());
    assertThat(claims.path("sub").asString()).isEqualTo("MYORG-MYACCOUNT.GW_READER");
    assertThat(claims.path("exp").asLong() - claims.path("iat").asLong()).isBetween(300L, 59L * 60);
    // The derived JWT joins the call's taint, so an echo of it is masked.
    assertThat(ctx.taints).contains(jwt);
    assertThat(r.table().rows().get(0).get(0).asLong()).isEqualTo(1);
  }

  @Test
  void eachStatementCarriesTheConnectionsContextOneStatementAndATag() throws Exception {
    planAndAnswer("SELECT ID", FakeSnowflake.result(List.of(Col.of("ID", "FIXED")), List.of(List.of("1"))));
    TestCtx ctx = TestCtx.of();
    snowflake.execute(call("snowflake_query", "{\"sql\": \"SELECT ID FROM orders\", \"limit\": 20}"), config(),
        secrets, ctx);

    List<JsonNode> sent = fake.statementsSent();
    assertThat(sent.get(0).path("statement").asString()).isEqualTo("EXPLAIN USING JSON SELECT ID FROM orders");
    JsonNode query = sent.get(1);
    assertThat(query.path("statement").asString()).isEqualTo("SELECT ID FROM orders");
    assertThat(query.path("warehouse").asString()).isEqualTo("COMPUTE_WH");
    assertThat(query.path("role").asString()).isEqualTo("ANALYST_RO");
    assertThat(query.path("database").asString()).isEqualTo("ANALYTICS");
    assertThat(query.path("schema").asString()).isEqualTo("PUBLIC");
    // The policy's statement timeout (5 s on dev) is the stricter one.
    assertThat(query.path("timeout").asInt()).isEqualTo(5);
    JsonNode parameters = query.path("parameters");
    assertThat(parameters.path("MULTI_STATEMENT_COUNT").asString()).isEqualTo("1");
    assertThat(parameters.path("ROWS_PER_RESULTSET").asString()).isEqualTo("21");
    JsonNode tag = JSON.readTree(parameters.path("QUERY_TAG").asString());
    assertThat(tag.path("app").asString()).isEqualTo("GateRook");
    assertThat(tag.path("call").asString()).isEqualTo(ctx.callId());
    assertThat(tag.path("tool").asString()).isEqualTo("snowflake_query");
    assertThat(sent.get(0).path("parameters").path("MULTI_STATEMENT_COUNT").asString()).isEqualTo("1");

    ObjectNode own = settings();
    own.putObject("advanced").put("statementTimeoutSeconds", 2);
    planAndAnswer("SELECT ID", FakeSnowflake.result(List.of(Col.of("ID", "FIXED")), List.of(List.of("1"))));
    snowflake.execute(sql("snowflake_query", "SELECT ID FROM orders"), config(Env.DEV, own), secrets, TestCtx.of());
    assertThat(fake.statementsSent().getLast().path("timeout").asInt()).isEqualTo(2);
  }

  @Test
  void aProgrammaticAccessTokenGoesAsTheBearerWithItsType() throws Exception {
    fake.token("pat-secret-0123456789");
    ObjectNode c = settings();
    c.putObject("authentication").put("method", "token");
    fake.on("SELECT 1", b -> FakeSnowflake.result(List.of(Col.of("1", "FIXED")), List.of(List.of("1"))));
    TestCtx ctx = TestCtx.of();
    snowflake.health(config(Env.DEV, c), new TestSecrets().with(SnowflakeSettings.TOKEN, "  pat-secret-0123456789\n"),
        ctx);
    assertThat(fake.problems).isEmpty();
    RecordedRequest sent = fake.requests.getFirst();
    assertThat(sent.getHeaders().get("X-Snowflake-Authorization-Token-Type")).isEqualTo("PROGRAMMATIC_ACCESS_TOKEN");
    assertThat(sent.getHeaders().get("Authorization")).isEqualTo("Bearer pat-secret-0123456789");
    assertThat(ctx.taints).contains("pat-secret-0123456789");
  }

  @Test
  void unusableKeysAreRefusedBeforeAnyRequest() {
    TestResult notPem = snowflake.test(config(), new TestSecrets().with(SnowflakeSettings.PRIVATE_KEY, "not a key"),
        TestCtx.of());
    assertThat(notPem.error().code()).isEqualTo("snowflake.credentials");
    assertThat(notPem.error().message()).doesNotContain("not a key");
    TestResult missing = snowflake.test(config(), new TestSecrets(), TestCtx.of());
    assertThat(missing.error().code()).isEqualTo("snowflake.credentials");
    assertThat(fake.requests).isEmpty();
  }

  // ── Reads ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void valuesAreTypedByTheRowType() throws Exception {
    List<Col> columns = List.of(Col.of("N", "FIXED"), Col.of("BIG", "FIXED"), new Col("PRICE", "FIXED", 2),
        Col.of("R", "REAL"), Col.of("NAN", "REAL"), Col.of("T", "TEXT"), Col.of("B", "BOOLEAN"), Col.of("D", "DATE"),
        Col.of("TM", "TIME"), Col.of("NTZ", "TIMESTAMP_NTZ"), Col.of("LTZ", "TIMESTAMP_LTZ"),
        Col.of("TZ", "TIMESTAMP_TZ"), Col.of("V", "VARIANT"), Col.of("A", "ARRAY"), Col.of("BIN", "BINARY"),
        Col.of("NUL", "TEXT"));
    List<String> row = Arrays.asList("42", "123456789012345678901", "12.50", "1.5", "NaN", "x".repeat(100), "true",
        "18262", "82919.000000000", "1616173619.123456789", "1616173619.000000000", "1616173619.000000000 960",
        "{\n  \"a\": [\n    1,\n    2\n  ],\n  \"s\": \"x y\"\n}", "[\n  1,\n  \"two\"\n]", "48656C6C6F", null);
    planAndAnswer("SELECT", FakeSnowflake.result(columns, List.of(row)));
    TestCtx ctx = TestCtx.of().with(new ai.msrv.gaterook.connector.api.Limits(1_000, 1_000, 512 * 1024, 256 * 1024,
        256 * 1024, 64 * 1024, 40, Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(10), null));
    ToolResult r = snowflake.execute(sql("snowflake_query", "SELECT * FROM orders"), config(), secrets, ctx);

    JsonNode cells = r.table().rows().get(0);
    assertThat(cells.get(0).isIntegralNumber()).isTrue();
    assertThat(cells.get(0).asLong()).isEqualTo(42);
    assertThat(cells.get(1).asString()).isEqualTo("123456789012345678901");
    assertThat(cells.get(2).asString()).isEqualTo("12.50");
    assertThat(cells.get(3).asDouble()).isEqualTo(1.5);
    assertThat(cells.get(4).asString()).isEqualTo("NaN");
    assertThat(cells.get(5).asString()).startsWith("x".repeat(40) + "…‹truncated 60 chars›");
    assertThat(cells.get(6).asBoolean()).isTrue();
    assertThat(cells.get(7).asString()).isEqualTo("2020-01-01");
    assertThat(cells.get(8).asString()).isEqualTo("23:01:59");
    assertThat(cells.get(9).asString()).isEqualTo("2021-03-19T17:06:59.123456789");
    assertThat(cells.get(10).asString()).isEqualTo("2021-03-19T17:06:59Z");
    assertThat(cells.get(11).asString()).isEqualTo("2021-03-19T09:06:59-08:00");
    assertThat(cells.get(12).asString()).isEqualTo("{\"a\":[1,2],\"s\":\"x y\"}");
    assertThat(cells.get(13).asString()).isEqualTo("[1,\"two\"]");
    assertThat(cells.get(14).asString()).isEqualTo("\\x48656c6c6f");
    assertThat(cells.get(15).isNull()).isTrue();
    assertThat(r.table().columns()).extracting(ToolResult.Column::type).startsWith("NUMBER(38,0)", "NUMBER(38,0)",
        "NUMBER(38,2)", "FLOAT", "FLOAT", "VARCHAR(16777216)");
    assertThat(r.table().columns().getFirst().sourceTable()).isEqualTo("ORDERS");
    assertThat(r.truncated()).isFalse();
    assertThat(r.structuredContent().path("statement").asString()).isEqualTo("SELECT");
  }

  @Test
  void aPlanThatChangesDataIsRefusedAndTheQueryNeverRuns() {
    fake.on("EXPLAIN USING JSON", b -> FakeSnowflake.plan("{\"GlobalStats\": {}, \"Operations\": [[{\"id\": 0, "
        + "\"operation\": \"Result\"}, {\"id\": 1, \"parentOperators\": [0], \"operation\": \"Insert\", \"objects\": "
        + "[\"ANALYTICS.PUBLIC.ORDERS\"]}]]}"));
    ConnectorException e = thrown(() -> snowflake.execute(sql("snowflake_query", "SELECT * FROM orders"), config(),
        secrets, TestCtx.of()));
    assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
    assertThat(e.getMessage()).contains("Insert", "only reads");
    assertThat(fake.sqlSent()).containsExactly("EXPLAIN USING JSON SELECT * FROM orders");
  }

  @Test
  void aPlanGateRookCantReadIsRefused() {
    for (String plan : List.of("not json", "{\"Operations\": []}", "{\"Operations\": [[{\"id\": 0}]]}",
        "{\"Operations\": [[{\"operation\": \"TableScan\", \"objects\": \"ORDERS\"}]]}")) {
      fake.on("EXPLAIN USING JSON", b -> FakeSnowflake.plan(plan));
      ConnectorException e = thrown(() -> snowflake.execute(sql("snowflake_query", "SELECT 1"), config(), secrets,
          TestCtx.of()));
      assertThat(e.kind()).as(plan).isEqualTo(ConnectorException.Kind.DENIED);
      assertThat(e.getMessage()).as(plan).contains("Snowflake's plan");
    }
    assertThat(fake.sqlSent()).allMatch(s -> s.startsWith("EXPLAIN"));

    fake.on("EXPLAIN USING JSON", b -> FakeSnowflake.failure(422, "002003", "02000", "SQL compilation error:\nObject "
        + "'NOPE' does not exist or not authorized."));
    ConnectorException failed = thrown(() -> snowflake.execute(sql("snowflake_query", "SELECT * FROM nope"),
        config(), secrets, TestCtx.of()));
    assertThat(failed.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
    assertThat(failed.getMessage()).contains("the plan failed", "Object 'NOPE' does not exist");
    assertThat(fake.sqlSent()).noneMatch(s -> s.equals("SELECT * FROM nope"));
  }

  @Test
  void showAndDescribeRunWithoutAPlan() throws Exception {
    fake.on("SHOW TABLES", b -> FakeSnowflake.result(List.of(Col.of("name", "TEXT")), List.of(List.of("ORDERS"))));
    ToolResult r = snowflake.execute(sql("snowflake_query", "SHOW TABLES IN SCHEMA analytics.public"), config(),
        secrets, TestCtx.of());
    assertThat(r.table().rows().get(0).get(0).asString()).isEqualTo("ORDERS");
    assertThat(fake.sqlSent()).containsExactly("SHOW TABLES IN SCHEMA analytics.public");
  }

  @Test
  void laterPartitionsAreReadCompressedUpToTheLimit() throws Exception {
    String handle = "01b2c3d4-0000-0000-0000-0000000000aa";
    List<Col> columns = List.of(Col.of("ID", "FIXED"));
    fake.on("EXPLAIN USING JSON", b -> FakeSnowflake.plan(FakeSnowflake.scanPlan("ANALYTICS.PUBLIC.ORDERS")))
        .on("SELECT ID", b -> FakeSnowflake.json(200, FakeSnowflake.resultSet(columns, List.of(List.of("1"),
            List.of("2")), List.of(2, 2, 2), handle).toString()))
        .onHandle(handle, r -> {
          String partition = r.getUrl().queryParameter("partition");
          int p = Integer.parseInt(partition);
          return FakeSnowflake.gzip(JsonNodeFactory.instance.objectNode().set("data", FakeSnowflake.data(List.of(
              List.of(String.valueOf(2 * p + 1)), List.of(String.valueOf(2 * p + 2))))).toString());
        });

    ToolResult all = snowflake.execute(call("snowflake_query", "{\"sql\": \"SELECT ID FROM orders\", \"limit\": 10}"),
        config(), secrets, TestCtx.of());
    assertThat(all.table().rows()).hasSize(6);
    assertThat(all.table().rows().get(5).get(0).asLong()).isEqualTo(6);
    assertThat(all.truncated()).isFalse();
    List<RecordedRequest> gets = fake.requests.stream().filter(r -> r.getMethod().equals("GET")).toList();
    assertThat(gets).extracting(r -> r.getUrl().queryParameter("partition")).containsExactly("1", "2");
    assertThat(gets.getFirst().getHeaders().get("Accept-Encoding")).isEqualTo("gzip");

    ToolResult cut = snowflake.execute(call("snowflake_query", "{\"sql\": \"SELECT ID FROM orders\", \"limit\": 3}"),
        config(), secrets, TestCtx.of());
    assertThat(cut.table().rows()).hasSize(3);
    assertThat(cut.truncated()).isTrue();
    assertThat(cut.truncatedReason()).isEqualTo("row_limit");
    assertThat(cut.content().getFirst().text()).contains("narrow the query");
    // The third partition isn't asked for: the limit was reached in the second.
    assertThat(fake.requests.stream().filter(r -> r.getMethod().equals("GET"))).hasSize(3);
  }

  @Test
  void theRowLimitIsTheEndpointsAtMost() throws Exception {
    planAndAnswer("SELECT ID", FakeSnowflake.result(List.of(Col.of("ID", "FIXED")), List.of(List.of("1"),
        List.of("2"), List.of("3"))));
    TestCtx ctx = TestCtx.of().env(Env.PROD);
    ToolResult r = snowflake.execute(call("snowflake_query", "{\"sql\": \"SELECT ID FROM orders\", \"limit\": 1000}"),
        config(), secrets, ctx);
    assertThat(fake.statementsSent().getLast().path("parameters").path("ROWS_PER_RESULTSET").asString())
        .isEqualTo("101");
    assertThat(r.table().rows()).hasSize(3);
  }

  // ── Table list ──────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aTableListKeepsEveryReadToListedTables() {
    ConnectionConfig cfg = config("ANALYTICS.PUBLIC.ORD*");
    PolicyView dev = policy(Env.DEV);
    assertThat(snowflake.classify(call("snowflake_describe_table", "{\"table\": \"orders\"}"), cfg, dev).kind())
        .isEqualTo(Classification.Kind.READ);
    Classification secret = snowflake.classify(call("snowflake_describe_table", "{\"table\": \"hr.people\"}"), cfg,
        dev);
    assertThat(secret.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(secret.ruleId()).isEqualTo("dev.snowflake.table-not-allowed");
    for (String q : List.of("SHOW TABLES", "SELECT * FROM information_schema.tables",
        "SELECT * FROM TABLE(RESULT_SCAN(LAST_QUERY_ID()))", "SELECT GET_DDL('table', 'hr.people.salaries')",
        "SELECT $1 FROM @hr_stage", "DESCRIBE USER admin", "DESCRIBE TABLE hr.people")) {
      Classification c = snowflake.classify(sql("snowflake_query", q), cfg, dev);
      assertThat(c.kind()).as(q).isEqualTo(Classification.Kind.DENY);
      assertThat(c.ruleId()).as(q).isEqualTo("dev.snowflake.table-not-allowed");
    }
    assertThat(snowflake.classify(sql("snowflake_query", "DESCRIBE TABLE orders"), cfg, dev).kind())
        .isEqualTo(Classification.Kind.READ);
    assertThat(snowflake.classify(call("snowflake_list_schemas", "{\"database\": \"hr\"}"), cfg, dev).kind())
        .isEqualTo(Classification.Kind.DENY);
  }

  @Test
  void aQueryWhosePlanReadsAnUnlistedTableIsRefused() {
    fake.on("EXPLAIN USING JSON", b -> FakeSnowflake.plan(FakeSnowflake.scanPlan("ANALYTICS.PUBLIC.ORDERS",
        "HR.PEOPLE.SALARIES")));
    ConnectorException e = thrown(() -> snowflake.execute(sql("snowflake_query", "SELECT * FROM orders_v"),
        config("ANALYTICS.PUBLIC.ORD*"), secrets, TestCtx.of()));
    assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
    assertThat(e.getMessage()).contains("HR.PEOPLE.SALARIES");
    assertThat(fake.sqlSent()).hasSize(1);
  }

  @Test
  void listingFiltersByTheTableListAtSnowflakeAndAgainHere() throws Exception {
    List<Col> tables = List.of(Col.of("TABLE_CATALOG", "TEXT"), Col.of("TABLE_SCHEMA", "TEXT"),
        Col.of("TABLE_NAME", "TEXT"), Col.of("TABLE_TYPE", "TEXT"), Col.of("ROW_COUNT", "FIXED"),
        Col.of("BYTES", "FIXED"), Col.of("COMMENT", "TEXT"));
    fake.on("SELECT TABLE_CATALOG", b -> FakeSnowflake.result(tables, List.of(
            Arrays.asList("ANALYTICS", "PUBLIC", "ORDERS", "BASE TABLE", "1204", "52011", null),
            Arrays.asList("ANALYTICS", "PUBLIC", "ORDERS_V", "VIEW", null, null, "Open orders"),
            Arrays.asList("ANALYTICS", "PUBLIC", "SECRETS", "BASE TABLE", "3", "100", null))))
        .on("SELECT CATALOG_NAME", b -> FakeSnowflake.result(List.of(Col.of("CATALOG_NAME", "TEXT"),
            Col.of("SCHEMA_NAME", "TEXT"), Col.of("COMMENT", "TEXT")), List.of(
            Arrays.asList("ANALYTICS", "PUBLIC", null), Arrays.asList("ANALYTICS", "STAGING", null))));
    ToolResult r = snowflake.execute(call("snowflake_list_schemas", "{\"pattern\": \"ord%\"}"),
        config("analytics.public.ord*"), secrets, TestCtx.of());

    JsonNode request = fake.statementsSent().getFirst();
    assertThat(request.path("statement").asString()).contains("TABLE_NAME ILIKE ?",
        "(TABLE_SCHEMA ILIKE ? ESCAPE '!' AND TABLE_NAME ILIKE ? ESCAPE '!')", "LIMIT 501");
    assertThat(request.path("bindings").path("1").path("value").asString()).isEqualTo("ord%");
    assertThat(request.path("bindings").path("2").path("value").asString()).isEqualTo("PUBLIC");
    assertThat(request.path("bindings").path("3").path("value").asString()).isEqualTo("ORD%");
    assertThat(request.path("bindings").path("3").path("type").asString()).isEqualTo("TEXT");
    assertThat(request.has("schema")).as("a catalog read sends no schema").isFalse();
    assertThat(request.path("database").asString()).isEqualTo("ANALYTICS");

    JsonNode out = r.structuredContent();
    assertThat(out.path("tables")).extracting(t -> t.path("name").asString()).containsExactly("ORDERS", "ORDERS_V");
    assertThat(out.path("tables").get(0).path("rows").asLong()).isEqualTo(1204);
    assertThat(out.path("tables").get(1).path("kind").asString()).isEqualTo("view");
    assertThat(out.path("schemas")).extracting(s -> s.path("name").asString()).containsExactly("PUBLIC");
    assertThat(r.content().getFirst().text()).startsWith("ANALYTICS.PUBLIC: ORDERS, ORDERS_V (view)")
        .doesNotContain("SECRETS");
    OutputSchemas.listSchemas(r);
  }

  @Test
  void describingATableReadsInformationSchemaWithBoundNames() throws Exception {
    fake.on("SELECT TABLE_TYPE", b -> FakeSnowflake.result(List.of(Col.of("TABLE_TYPE", "TEXT"),
            Col.of("ROW_COUNT", "FIXED"), Col.of("BYTES", "FIXED"), Col.of("COMMENT", "TEXT"),
            Col.of("CLUSTERING_KEY", "TEXT")), List.of(Arrays.asList("BASE TABLE", "1204", "52011", "Orders", null))))
        .on("SELECT COLUMN_NAME", b -> FakeSnowflake.result(List.of(Col.of("COLUMN_NAME", "TEXT"),
            Col.of("DATA_TYPE", "TEXT"), Col.of("CHARACTER_MAXIMUM_LENGTH", "FIXED"),
            Col.of("NUMERIC_PRECISION", "FIXED"), Col.of("NUMERIC_SCALE", "FIXED"), Col.of("IS_NULLABLE", "TEXT"),
            Col.of("COLUMN_DEFAULT", "TEXT"), Col.of("COMMENT", "TEXT")), List.of(
            Arrays.asList("ID", "NUMBER", null, "38", "0", "NO", null, null),
            Arrays.asList("STATUS", "TEXT", "16777216", null, null, "YES", "'new'", "Lifecycle"))));
    ToolResult r = snowflake.execute(call("snowflake_describe_table", "{\"table\": \"\\\"Sales\\\".orders\"}"),
        config(), secrets, TestCtx.of());

    List<JsonNode> sent = fake.statementsSent();
    assertThat(sent).hasSize(2);
    for (JsonNode s : sent) {
      assertThat(s.path("database").asString()).isEqualTo("ANALYTICS");
      assertThat(s.path("bindings").path("1").path("value").asString()).isEqualTo("Sales");
      assertThat(s.path("bindings").path("2").path("value").asString()).isEqualTo("ORDERS");
    }
    JsonNode d = r.structuredContent();
    assertThat(d.path("schema").asString()).isEqualTo("Sales");
    assertThat(d.path("columns").get(0).path("type").asString()).isEqualTo("NUMBER(38,0)");
    assertThat(d.path("columns").get(0).path("nullable").asBoolean()).isFalse();
    assertThat(d.path("columns").get(1).path("type").asString()).isEqualTo("VARCHAR(16777216)");
    assertThat(r.content().getFirst().text()).isEqualTo("ANALYTICS.\"Sales\".ORDERS (table, ~1,204 rows): ID "
        + "NUMBER(38,0) not null, STATUS VARCHAR(16777216)");
    OutputSchemas.describeTable(r);

    fake.on("SELECT TABLE_TYPE", b -> FakeSnowflake.result(List.of(Col.of("TABLE_TYPE", "TEXT")), List.of()));
    ConnectorException missing = thrown(() -> snowflake.execute(call("snowflake_describe_table",
        "{\"table\": \"nothing\"}"), config(), secrets, TestCtx.of()));
    assertThat(missing.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
    assertThat(missing.getMessage()).contains("snowflake_list_schemas");
  }

  // ── Changes ─────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void theApprovalShowsTheExactStatementAndOnlyTheApprovedPayloadRuns() throws Exception {
    String statement = "UPDATE orders SET status = 'paid' WHERE id = 7";
    ToolCall asked = call("snowflake_execute", JSON.writeValueAsString(Map.of("sql", statement)));
    Classification c = snowflake.classify(asked, config(), policy(Env.DEV));
    assertThat(c.kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(c.facts().path("target").asString()).isEqualTo("ANALYTICS.PUBLIC.ORDERS");
    ActionPreview preview = snowflake.describeAction(asked, config());
    assertThat(preview.kind()).isEqualTo(ActionPreview.Kind.SQL);
    assertThat(preview.language()).isEqualTo("sql");
    assertThat(preview.text()).isEqualTo(statement);
    assertThat(preview.payload().path("sql").asString()).isEqualTo(statement);
    assertThat(preview.fields()).extracting(ActionPreview.Field::value).contains("ANALYST_RO", "COMPUTE_WH",
        "ANALYTICS.PUBLIC");
    assertThat(preview.impact()).extracting(ActionPreview.Field::value).contains("ANALYTICS.PUBLIC.ORDERS");

    ConnectorException unapproved = thrown(() -> snowflake.execute(asked, config(), secrets, TestCtx.of()));
    assertThat(unapproved.kind()).isEqualTo(ConnectorException.Kind.DENIED);
    assertThat(fake.requests).isEmpty();

    fake.on("UPDATE", b -> FakeSnowflake.json(200, "{\"resultSetMetaData\": {\"numRows\": 1, \"rowType\": [{\"name\": "
        + "\"number of rows updated\", \"type\": \"FIXED\"}, {\"name\": \"number of multi-joined rows updated\", "
        + "\"type\": \"FIXED\"}], \"partitionInfo\": [{\"rowCount\": 1}]}, \"data\": [[\"1\", \"0\"]], \"code\": "
        + "\"090001\", \"statementHandle\": \"01b2c3d4-0000-0000-0000-000000000003\", \"stats\": "
        + "{\"numRowsInserted\": 0, \"numRowsUpdated\": 1, \"numRowsDeleted\": 0, \"numDuplicateRowsUpdated\": 0}}"));
    // The call's own arguments say something else: what runs is what the person approved.
    ToolCall other = call("snowflake_execute", "{\"sql\": \"DELETE FROM orders\"}");
    ToolResult done = snowflake.execute(other, config(), secrets, TestCtx.of().approved(preview.payload()));
    assertThat(fake.sqlSent()).containsExactly(statement);
    assertThat(fake.statementsSent().getFirst().path("parameters").path("MULTI_STATEMENT_COUNT").asString())
        .isEqualTo("1");
    assertThat(done.structuredContent().path("committed").asBoolean()).isTrue();
    assertThat(done.structuredContent().path("rowsAffected").asLong()).isEqualTo(1);
    assertThat(done.structuredContent().path("rowsUpdated").asLong()).isEqualTo(1);
    assertThat(done.content().getFirst().text()).isEqualTo("1 row updated · ANALYTICS.PUBLIC.ORDERS.");
  }

  @Test
  void ddlReportsSnowflakesStatus() throws Exception {
    String statement = "CREATE TABLE analytics.public.notes (id NUMBER, body VARCHAR)";
    ActionPreview preview = snowflake.describeAction(sql("snowflake_execute", statement), config());
    assertThat(preview.impact()).extracting(ActionPreview.Field::value).contains("None: Snowflake commits DDL at once");
    fake.on("CREATE TABLE", b -> FakeSnowflake.result(List.of(Col.of("status", "TEXT")), List.of(List.of(
        "Table NOTES successfully created."))));
    ToolResult done = snowflake.execute(sql("snowflake_execute", statement), config(), secrets,
        TestCtx.of().approved(preview.payload()));
    assertThat(done.content().getFirst().text()).isEqualTo("Table NOTES successfully created.");
    assertThat(done.structuredContent().path("statement").asString()).isEqualTo("CREATE TABLE");
  }

  @Test
  void aChangeIsNeverRetriedAndALostAnswerIsUnknown() {
    String statement = "DELETE FROM orders WHERE id = 7";
    JsonNode payload = snowflake.describeAction(sql("snowflake_execute", statement), config()).payload();
    fake.on("DELETE", b -> new MockResponse.Builder().onResponseStart(new SocketEffect.CloseSocket()).build());
    ConnectorException dropped = thrown(() -> snowflake.execute(sql("snowflake_execute", statement), config(),
        secrets, TestCtx.of().approved(payload)));
    assertThat(dropped.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN);
    assertThat(fake.sqlSent()).hasSize(1);

    FakeSnowflake failing = new FakeSnowflake();
    try (SnowflakeConnector other = new SnowflakeConnector(failing.base())) {
      failing.on("DELETE", b -> FakeSnowflake.json(500, "{\"code\": \"000603\", \"message\": \"Internal error\"}"));
      ConnectorException failed = thrown(() -> other.execute(sql("snowflake_execute", statement), config(), secrets,
          TestCtx.of().approved(payload)));
      assertThat(failed.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN);
      assertThat(failed.getMessage()).contains("whether it ran is not known");
      assertThat(failing.sqlSent()).hasSize(1);
    } finally {
      failing.close();
    }
  }

  @Test
  void aRefusedChangeChangedNothing() {
    String statement = "DELETE FROM orders WHERE id = 7";
    JsonNode payload = snowflake.describeAction(sql("snowflake_execute", statement), config()).payload();
    fake.on("DELETE", b -> FakeSnowflake.failure(422, "003001", "42501", "SQL access control error:\nInsufficient "
        + "privileges to operate on table 'ORDERS'"));
    ConnectorException e = thrown(() -> snowflake.execute(sql("snowflake_execute", statement), config(), secrets,
        TestCtx.of().approved(payload)));
    assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
    assertThat(e.getMessage()).startsWith("Snowflake refused the change, so nothing changed").contains("ANALYST_RO",
        "Insufficient privileges");
  }

  @Test
  void anApprovalForAnotherContextDoesntRun() {
    String statement = "DELETE FROM orders WHERE id = 7";
    JsonNode payload = snowflake.describeAction(sql("snowflake_execute", statement), config()).payload();
    ObjectNode moved = settings();
    moved.put("role", "sysadmin");
    ConnectorException e = thrown(() -> snowflake.execute(sql("snowflake_execute", statement), config(Env.DEV, moved),
        secrets, TestCtx.of().approved(payload)));
    assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
    assertThat(e.getMessage()).contains("changed after the approval");
    assertThat(fake.requests).isEmpty();
  }

  @Test
  void aChangeStillRunningAtTheDeadlineIsCanceledAndUnknown() {
    String handle = "01b2c3d4-0000-0000-0000-0000000000bb";
    List<String> cancels = new ArrayList<>();
    fake.on("UPDATE", b -> FakeSnowflake.running(handle)).onHandle(handle, r -> {
      if (r.getUrl().encodedPath().endsWith("/cancel")) {
        cancels.add(r.getMethod());
        return FakeSnowflake.json(200, "{\"code\": \"000604\", \"message\": \"Statement canceled.\"}");
      }
      return FakeSnowflake.running(handle);
    });
    String statement = "UPDATE orders SET status = 'paid' WHERE id = 7";
    JsonNode payload = snowflake.describeAction(sql("snowflake_execute", statement), config()).payload();
    ConnectorException e = thrown(() -> snowflake.execute(sql("snowflake_execute", statement), config(), secrets,
        TestCtx.of().timeout(Duration.ofMillis(1_500)).approved(payload)));
    assertThat(e.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN);
    assertThat(cancels).containsExactly("POST");
    assertThat(fake.sqlSent()).hasSize(1);
  }

  @Test
  void aLongReadIsPolledUntilItsAnswer() throws Exception {
    String handle = "01b2c3d4-0000-0000-0000-0000000000cc";
    int[] polls = {0};
    fake.on("SHOW WAREHOUSES", b -> FakeSnowflake.running(handle)).onHandle(handle, r -> ++polls[0] < 2
        ? FakeSnowflake.json(429, "{\"code\": \"000000\", \"message\": \"busy\"}")
        : FakeSnowflake.result(List.of(Col.of("name", "TEXT")), List.of(List.of("COMPUTE_WH"))));
    ToolResult r = snowflake.execute(sql("snowflake_query", "SHOW WAREHOUSES"), config(), secrets, TestCtx.of());
    assertThat(r.table().rows().get(0).get(0).asString()).isEqualTo("COMPUTE_WH");
    assertThat(polls[0]).isEqualTo(2);
  }

  // ── Errors ──────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void snowflakesErrorsSayWhatToDo() {
    Consumer<String[]> check = c -> {
      // c: status, code, sqlState, message, expected kind, expected text
      fake.on("SHOW", b -> FakeSnowflake.failure(Integer.parseInt(c[0]), c[1], c[2], c[3]));
      ConnectorException e = thrown(() -> snowflake.execute(sql("snowflake_query", "SHOW TABLES"), config(), secrets,
          TestCtx.of()));
      assertThat(e.kind()).as(c[3]).isEqualTo(ConnectorException.Kind.valueOf(c[4]));
      assertThat(e.getMessage()).as(c[3]).contains(c[5]);
    };
    check.accept(new String[] {"401", "390144", "", "JWT token is invalid.", "RESOURCE_UNAVAILABLE",
        FakeSnowflake.fingerprint()});
    check.accept(new String[] {"422", "002003", "02000", "SQL compilation error:\nObject 'X' does not exist or not "
        + "authorized.", "BAD_INPUT", "Object 'X' does not exist or not authorized. Call snowflake_list_schemas"});
    check.accept(new String[] {"422", "001003", "42000", "SQL compilation error:\nsyntax error line 1", "BAD_INPUT",
        "Snowflake refused the statement: SQL compilation error: syntax error line 1"});
    check.accept(new String[] {"422", "003001", "42501", "Insufficient privileges to operate on schema 'PUBLIC'",
        "RESOURCE_ERROR", "The role ANALYST_RO may not do this"});
    check.accept(new String[] {"422", "000606", "57P03", "No active warehouse selected in the current session.",
        "RESOURCE_ERROR", "Set the connection's warehouse"});
    check.accept(new String[] {"429", "", "", "Too many requests", "RESOURCE_ERROR", "Try again in a moment"});
    check.accept(new String[] {"503", "", "", "Service unavailable", "RESOURCE_UNAVAILABLE", "failed with 503"});
    check.accept(new String[] {"408", "000630", "57014", "Statement reached its statement or warehouse timeout of 5 "
        + "second(s) and was canceled.", "TIMEOUT", "Narrow the query"});
    check.accept(new String[] {"403", "", "", "Forbidden", "RESOURCE_UNAVAILABLE", "network policy"});
  }

  @Test
  void anUnreachableAccountIsUnavailable() {
    SnowflakeConnector nowhere = new SnowflakeConnector(java.net.URI.create("http://127.0.0.1:1"));
    ConnectorException e = thrown(() -> nowhere.execute(sql("snowflake_query", "SHOW TABLES"), config(), secrets,
        TestCtx.of()));
    assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE);
    assertThat(nowhere.test(config(), secrets, TestCtx.of()).error().code()).isEqualTo("snowflake.unreachable");
    nowhere.close();
  }

  // ── Test connection ─────────────────────────────────────────────────────────────────────────────────────────

  private void identity(String role, String secondary, String warehouse) {
    fake.on("SELECT CURRENT_VERSION()", b -> FakeSnowflake.result(List.of(Col.of("V", "TEXT"), Col.of("U", "TEXT"),
        Col.of("R", "TEXT"), Col.of("S", "TEXT"), Col.of("W", "TEXT"), Col.of("D", "TEXT"), Col.of("SC", "TEXT")),
        List.of(Arrays.asList("9.38.1", "GW_READER", role, secondary, warehouse, "ANALYTICS", "PUBLIC"))));
  }

  private void grants(String... privilegesOn) {
    List<List<String>> rows = new ArrayList<>();
    for (String p : privilegesOn) {
      String[] parts = p.split(" ON ");
      rows.add(List.of("2026-01-01 00:00:00", parts[0], parts[1], "ANALYTICS.PUBLIC.ORDERS", "ROLE", "ANALYST_RO",
          "false", "SYSADMIN"));
    }
    fake.on("SHOW GRANTS TO ROLE", b -> FakeSnowflake.result(List.of(Col.of("created_on", "TIMESTAMP_LTZ"),
        Col.of("privilege", "TEXT"), Col.of("granted_on", "TEXT"), Col.of("name", "TEXT"), Col.of("granted_to", "TEXT"),
        Col.of("grantee_name", "TEXT"), Col.of("grant_option", "TEXT"), Col.of("granted_by", "TEXT")), rows));
  }

  @Test
  void testConnectionNamesTheIdentityAndTheRolesReach() {
    identity("ANALYST_RO", "{\"roles\":\"\",\"value\":\"\"}", "COMPUTE_WH");
    grants("USAGE ON DATABASE", "SELECT ON TABLE");
    TestResult read = snowflake.test(config(Env.PROD, settings()), secrets, TestCtx.of().env(Env.PROD));
    assertThat(read.ok()).as(String.valueOf(read.error())).isTrue();
    assertThat(read.serverVersion()).isEqualTo("Snowflake 9.38.1");
    assertThat(read.identity()).isEqualTo("GW_READER · ANALYST_RO");
    assertThat(read.permissions().level()).isEqualTo(TestResult.Level.READ);
    assertThat(read.checks()).extracting(TestResult.Check::name).containsExactly("sign-in", "session", "grants");
    assertThat(read.checks().getFirst().detail()).contains(FakeSnowflake.fingerprint());
    assertThat(read.hints()).isEmpty();
    assertThat(fake.sqlSent()).contains("SHOW GRANTS TO ROLE \"ANALYST_RO\"");
    assertThat(read.metadataUpdate().path("role").asString()).isEqualTo("ANALYST_RO");

    grants("INSERT ON TABLE", "SELECT ON TABLE");
    identity("ANALYST_RO", "{\"roles\":\"DEV_ADMIN\",\"value\":\"ALL\"}", null);
    TestResult writes = snowflake.test(config(Env.PROD, settings()), secrets, TestCtx.of().env(Env.PROD));
    assertThat(writes.permissions().level()).isEqualTo(TestResult.Level.WRITE);
    assertThat(writes.hints()).extracting(TestResult.Hint::code).containsExactly("snowflake.role-writes",
        "snowflake.secondary-roles", "snowflake.no-warehouse");
    assertThat(writes.hints().getFirst().severity()).isEqualTo(TestResult.Severity.WARNING);
    assertThat(writes.hints().getFirst().text()).contains("no read-only transaction", "role is the boundary");

    identity("ACCOUNTADMIN", "{\"roles\":\"\",\"value\":\"\"}", "COMPUTE_WH");
    TestResult admin = snowflake.test(config(Env.PROD, settings()), secrets, TestCtx.of().env(Env.PROD));
    assertThat(admin.permissions().level()).isEqualTo(TestResult.Level.ADMIN);
    assertThat(admin.hints().getFirst().severity()).isEqualTo(TestResult.Severity.DANGER);

    // A role that inherits others: not shown as read-only, and prod is advised to use one.
    fake.on("SHOW GRANTS TO ROLE", b -> FakeSnowflake.result(List.of(Col.of("privilege", "TEXT"),
        Col.of("granted_on", "TEXT"), Col.of("name", "TEXT")), List.of(List.of("USAGE", "ROLE", "REPORTER"))));
    identity("ANALYST_RO", "{\"roles\":\"\",\"value\":\"\"}", "COMPUTE_WH");
    TestResult inherits = snowflake.test(config(Env.PROD, settings()), secrets, TestCtx.of().env(Env.PROD));
    assertThat(inherits.permissions().level()).isEqualTo(TestResult.Level.UNKNOWN);
    assertThat(inherits.hints()).extracting(TestResult.Hint::code).containsExactly("snowflake.read-only-role");
  }

  @Test
  void aRefusedSignInSaysWhichKeySnowflakeShouldKnow() {
    fake.subject("OTHER-ACCOUNT.GW_READER");
    TestResult r = snowflake.test(config(), secrets, TestCtx.of());
    assertThat(r.ok()).isFalse();
    assertThat(r.error().code()).isEqualTo("snowflake.sign-in");
    assertThat(r.error().message()).contains("RSA_PUBLIC_KEY", FakeSnowflake.fingerprint());
    assertThat(r.error().technical()).contains("390144");
  }

  @Test
  void anIncompleteConnectionIsRefusedWithoutAnyRequest() {
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("account", "https://myorg-myaccount.snowflakecomputing"
        + ".com").put("user", "gw_reader");
    c.putObject("authentication").put("method", "keyPair");
    ConnectionConfig broken = config(Env.DEV, c);
    Classification denied = snowflake.classify(sql("snowflake_query", "SELECT 1"), broken, policy(Env.DEV));
    assertThat(denied.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(denied.ruleId()).isEqualTo("dev.snowflake.bad-config");
    assertThat(snowflake.test(broken, secrets, TestCtx.of()).error().code()).isEqualTo("snowflake.settings");
    assertThat(fake.requests).isEmpty();
  }

  @Test
  void describeConnectionSaysHowReadsAndChangesRun() throws Exception {
    JsonNode d = snowflake.describe(config("ANALYTICS.PUBLIC.*"), secrets, TestCtx.of(), "orders").orElseThrow();
    assertThat(d.path("account").asString()).isEqualTo("myorg-myaccount");
    assertThat(d.path("namespace").asString()).isEqualTo("ANALYTICS.PUBLIC");
    assertThat(d.path("tables").get(0).asString()).isEqualTo("ANALYTICS.PUBLIC.*");
    assertThat(d.path("table").asString()).contains("snowflake_describe_table");
    assertThat(fake.requests).isEmpty();
  }
}
