package com.gaterook.connectors.athena;

import static com.gaterook.connectors.athena.FakeAthena.body;
import static com.gaterook.connectors.athena.FakeAthena.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connector.api.ActionPreview;
import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.Limits;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connector.api.PreparedAction;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.connector.api.ToolResult;
import com.gaterook.connectors.athena.FakeAthena.Script;
import com.gaterook.connectors.cloudauth.AwsSessionPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** The Athena connector against a fake of Athena's JSON API and STS: what is sent, signed, refused and shown. */
class AthenaConnectorTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ROLE = "arn:aws:iam::412398765012:role/gw-athena";
  private static final String EXPLAIN = "EXPLAIN (TYPE IO, FORMAT JSON) ";

  private FakeAthena athena;
  private AthenaConnector connector;
  private final TestSecrets secrets = new TestSecrets().with("/credentials/secretAccessKey", "test-only-secret-key");

  @BeforeEach
  void start() throws Exception {
    athena = new FakeAthena();
    connector = new AthenaConnector(athena.cloud);
  }

  @AfterEach
  void stop() {
    connector.close();
    athena.close();
  }

  private static ConnectionConfig config(Env env, String... databases) {
    return config(env, false, databases);
  }

  private static ConnectionConfig config(Env env, boolean role, String... databases) {
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("region", "eu-central-1").put("workgroup", "primary")
        .put("database", "sales");
    ObjectNode credentials = c.putObject("credentials").put("source", "accessKey")
        .put("accessKeyId", "AKIAIOSFODNN7EXAMPLE");
    if (role) {
      credentials.put("roleArn", ROLE).put("externalId", "gw-ext-0123456789ab");
    }
    if (databases.length > 0) {
      var list = c.putArray("databases");
      for (String d : databases) {
        list.add(d);
      }
    }
    return new ConnectionConfig("conn-athena-" + env.id() + (role ? "-role" : ""), env.id() + "-athena", "athena",
        env, 1, c, Set.of("/credentials/secretAccessKey"), null);
  }

  private static PolicyView policy(Env env) {
    return new PolicyView(env, env.id() + ".athena", JsonNodeFactory.instance.objectNode(),
        env == Env.PROD ? TestCtx.prod() : TestCtx.dev());
  }

  private static ToolCall call(String tool, String args) {
    return new ToolCall("tc_" + Long.toHexString(System.nanoTime()), tool, "dev-athena",
        (ObjectNode) JSON.readTree(args), null);
  }

  private static String sql(String sql) {
    return JSON.writeValueAsString(Map.of("sql", sql));
  }

  private Classification classify(String tool, String args, String... databases) {
    return connector.classify(call(tool, args), config(Env.DEV, databases), policy(Env.DEV));
  }

  private static Script orders(String[]... rows) {
    return Script.select(List.of("id:bigint", "total:decimal(10,2)", "paid:boolean", "placed:timestamp",
        "raw:varbinary", "tags:array(varchar)", "ratio:double"), rows);
  }

  // ── Tools and routes ────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void publishesReadsAndOneApprovedChange() {
    assertThat(connector.manifest().id()).isEqualTo("athena");
    assertThat(connector.manifest().name()).isEqualTo("Amazon Athena");
    assertThat(connector.manifest().group()).isEqualTo("Databases");
    assertThat(connector.manifest().tools()).extracting(ToolDefinition::name).containsExactly("athena_list_catalogs",
        "athena_list_databases", "athena_list_tables", "athena_describe_table", "athena_query", "athena_execute");
    ToolDefinition execute = connector.manifest().tools().getLast();
    assertThat(execute.mutating()).isTrue();
    assertThat(execute.readOnlyModeAllowed()).isFalse();
    assertThat(execute.defaultMode()).isEqualTo(ToolDefinition.Mode.APPROVAL);
    assertThat(execute.prodDefaultMode()).isEqualTo(ToolDefinition.Mode.APPROVAL);
    assertThat(execute.inputSchema().at("/properties/note/maxLength").asInt()).isEqualTo(500);
    assertThat(execute.inputSchema().at("/properties/reason").isMissingNode()).isTrue();
  }

  @Test
  void statementsAreRoutedByWhatTheyDo() {
    assertThat(classify("athena_query", sql("SELECT * FROM sales.orders WHERE note = 'DELETE; x'")).kind())
        .isEqualTo(Classification.Kind.READ);
    Classification writes = classify("athena_query", sql("INSERT INTO sales.orders VALUES (1)"));
    assertThat(writes.ruleId()).isEqualTo("dev.athena.query-writes");
    Classification change = classify("athena_execute", sql("INSERT INTO sales.orders VALUES (1)"));
    assertThat(change.kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(change.facts().path("target").asString()).isEqualTo("sales.orders");
    assertThat(classify("athena_execute", sql("SELECT 1")).ruleId()).isEqualTo("dev.athena.execute-read");
    assertThat(classify("athena_query", sql("UNLOAD (SELECT * FROM sales.orders) TO 's3://acme-exports/'"))
        .ruleId()).isEqualTo("dev.athena.forbidden");
    assertThat(classify("athena_query", sql("DESCRIBE sales.orders 'x\\'")).ruleId()).isEqualTo("dev.athena.unparsed");
  }

  @Test
  void onlyListedDatabasesPass() {
    String[] allowed = {"sales", "audit_*"};
    assertThat(classify("athena_query", sql("SHOW TABLES IN audit_2026"), allowed).kind())
        .isEqualTo(Classification.Kind.READ);
    for (String text : List.of("SHOW TABLES IN hr", "DESCRIBE hr.salaries", "SHOW CREATE TABLE hr.salaries",
        "SHOW DATABASES", "SHOW PARTITIONS other.t")) {
      Classification c = classify("athena_query", sql(text), allowed);
      assertThat(c.kind()).as(text).isEqualTo(Classification.Kind.DENY);
      assertThat(c.ruleId()).as(text).isEqualTo("dev.athena.database-not-allowed");
    }
    assertThat(classify("athena_execute", sql("DROP TABLE hr.salaries"), allowed).kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThat(classify("athena_execute", sql("DROP TABLE daily"), allowed).kind())
        .isEqualTo(Classification.Kind.MUTATION);
    assertThat(classify("athena_execute", sql("CREATE DATABASE hr2"), allowed).kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThat(classify("athena_list_tables", "{\"database\": \"hr\"}", allowed).kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThat(classify("athena_describe_table", "{\"table\": \"awsdatacatalog.sales.orders\"}", allowed).kind())
        .isEqualTo(Classification.Kind.READ);
    assertThat(classify("athena_list_databases", "{\"catalog\": \"other_catalog\"}", allowed).kind())
        .isEqualTo(Classification.Kind.DENY);
  }

  // ── What is sent, signed and shown ──────────────────────────────────────────────────────────────────────────

  @Test
  void aQueryIsPlannedThenStartedSignedPolledAndRead() throws Exception {
    athena.on(EXPLAIN + "SELECT", Script.plan(List.of("awsdatacatalog.sales.orders"), null))
        .on("SELECT", orders(new String[] {"1", "12.50", "true", "2026-01-02 03:04:05.123", "00 ff 10", "[a, b]",
            "0.5"}, new String[] {"9007199254740993", null, "false", null, null, null, "NaN"}));
    TestCtx ctx = TestCtx.of();
    ToolResult r = connector.execute(call("athena_query", sql("SELECT * FROM sales.orders;")), config(Env.DEV,
        "sales"), secrets, ctx);

    assertThat(athena.started).extracting(FakeAthena.Query::sql).containsExactly(EXPLAIN + "SELECT * FROM sales.orders",
        "SELECT * FROM sales.orders");
    RecordedRequest sent = athena.to("StartQueryExecution").getLast();
    assertThat(sent.getHeaders().get("Content-Type")).isEqualTo("application/x-amz-json-1.1");
    assertThat(sent.getHeaders().get("X-Amz-Target")).isEqualTo("AmazonAthena.StartQueryExecution");
    assertThat(sent.getHeaders().get("Authorization")).contains("AWS4-HMAC-SHA256", "/eu-central-1/athena/aws4_request",
        "x-amz-target");
    JsonNode start = body(sent);
    assertThat(start.path("WorkGroup").asString()).isEqualTo("primary");
    assertThat(start.at("/QueryExecutionContext/Catalog").asString()).isEqualTo("AwsDataCatalog");
    assertThat(start.at("/QueryExecutionContext/Database").asString()).isEqualTo("sales");
    assertThat(start.path("ClientRequestToken").asString()).hasSizeBetween(32, 128);
    assertThat(ctx.taints).contains("test-only-secret-key");
    assertThat(athena.to("GetQueryExecution").size()).isGreaterThanOrEqualTo(4);

    assertThat(r.table().columns()).extracting(ToolResult.Column::name).containsExactly("id", "total", "paid",
        "placed", "raw", "tags", "ratio");
    assertThat(r.table().rows()).hasSize(2);
    JsonNode row = r.table().rows().get(0);
    assertThat(row.get(0).isIntegralNumber()).isTrue();
    assertThat(row.get(1).asString()).isEqualTo("12.50");
    assertThat(row.get(2).asBoolean()).isTrue();
    assertThat(row.get(3).asString()).isEqualTo("2026-01-02T03:04:05.123");
    assertThat(row.get(4).asString()).isEqualTo("\\x00ff10");
    assertThat(row.get(5).asString()).isEqualTo("[a, b]");
    assertThat(row.get(6).asDouble()).isEqualTo(0.5);
    JsonNode second = r.table().rows().get(1);
    assertThat(second.get(0).asString()).isEqualTo("9007199254740993");
    assertThat(second.get(1).isNull()).isTrue();
    assertThat(second.get(6).asString()).isEqualTo("NaN");
    assertThat(r.structuredContent().path("dataScannedBytes").asLong()).isEqualTo(10_485_760L);
    assertThat(r.structuredContent().path("tablesRead").get(0).asString()).isEqualTo("awsdatacatalog.sales.orders");
    assertThat(r.content().getFirst().text()).isEqualTo("10.0 MiB scanned");
    assertThat(r.table().nextCursor()).isNull();
  }

  @Test
  void athenasPlanDecidesWhatRuns() {
    athena.on(EXPLAIN + "SELECT", Script.plan(List.of("awsdatacatalog.sales.orders"), "awsdatacatalog.sales.copy"));
    assertThatThrownBy(() -> connector.execute(call("athena_query", sql("SELECT * FROM sales.orders")),
        config(Env.DEV), secrets, TestCtx.of())).hasMessageContaining("writes to awsdatacatalog.sales.copy");
    athena.on(EXPLAIN + "SELECT", Script.plan(List.of("awsdatacatalog.sales.v", "awsdatacatalog.hr.salaries"), null));
    assertThatThrownBy(() -> connector.execute(call("athena_query", sql("SELECT * FROM sales.v")),
        config(Env.DEV, "sales"), secrets, TestCtx.of())).hasMessageContaining("awsdatacatalog.hr.salaries");
    athena.on(EXPLAIN + "SELECT", Script.select(List.of("Query Plan:varchar"), new String[] {"- Output[x]"}));
    assertThatThrownBy(() -> connector.execute(call("athena_query", sql("SELECT 1")), config(Env.DEV), secrets,
        TestCtx.of())).hasMessageContaining("data definition");
    // Only the plans ran.
    assertThat(athena.started).allMatch(q -> q.sql().startsWith(EXPLAIN));
  }

  @Test
  void aReadAthenaReportsAsAChangeReturnsNothing() {
    athena.on(EXPLAIN + "SELECT", Script.plan(List.of(), null))
        .on("SELECT", orders().statement("DML", "INSERT"));
    assertThatThrownBy(() -> connector.execute(call("athena_query", sql("SELECT 1")), config(Env.DEV), secrets,
        TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).contains("ran as DML INSERT");
        });
  }

  @Test
  void pagesGoOnFromTheSameQueryWithASealedCursor() throws Exception {
    String[][] rows = new String[5][];
    for (int i = 0; i < 5; i++) {
      rows[i] = new String[] {Integer.toString(i), "1.00", "true", null, null, null, "1.5"};
    }
    athena.on(EXPLAIN + "SELECT", Script.plan(List.of("awsdatacatalog.sales.orders"), null))
        .on("SELECT", orders(rows));
    String args = "{\"sql\": \"SELECT * FROM sales.orders\", \"limit\": 2}";
    ToolResult first = connector.execute(call("athena_query", args), config(Env.DEV), secrets, TestCtx.of());
    assertThat(first.table().rows().toString()).startsWith("[[0,").contains("[1,");
    String cursor = first.table().nextCursor();
    assertThat(cursor).startsWith("at1.");
    // The header plus two rows: MaxResults 3 on the first page.
    assertThat(body(athena.to("GetQueryResults").getLast()).path("MaxResults").asInt()).isEqualTo(3);

    ObjectNode more = (ObjectNode) JSON.readTree(args);
    more.put("cursor", cursor);
    ToolResult second = connector.execute(call("athena_query", more.toString()), config(Env.DEV), secrets,
        TestCtx.of());
    assertThat(second.table().rows().get(0).get(0).asInt()).isEqualTo(2);
    assertThat(body(athena.to("GetQueryResults").getLast()).path("NextToken").asString()).isEqualTo("tok-3");
    more.put("cursor", second.table().nextCursor());
    ToolResult third = connector.execute(call("athena_query", more.toString()), config(Env.DEV), secrets,
        TestCtx.of());
    assertThat(third.table().rows()).hasSize(1);
    assertThat(third.table().nextCursor()).isNull();
    // One plan and one query: the next pages read the same execution's results.
    assertThat(athena.started).hasSize(2);

    // A cursor belongs to its statement and connection, and can't be forged.
    ObjectNode other = (ObjectNode) JSON.readTree("{\"sql\": \"SELECT 2\"}");
    other.put("cursor", cursor);
    assertThatThrownBy(() -> connector.execute(call("athena_query", other.toString()), config(Env.DEV), secrets,
        TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT));
    other.put("sql", "SELECT * FROM sales.orders").put("cursor", "at1.forged");
    assertThatThrownBy(() -> connector.execute(call("athena_query", other.toString()), config(Env.DEV), secrets,
        TestCtx.of())).isInstanceOf(ConnectorException.class);
  }

  @Test
  void aPageCutAtTheResponseSizeGoesOnAtTheNextRow() throws Exception {
    String[][] rows = new String[6][];
    for (int i = 0; i < 6; i++) {
      rows[i] = new String[] {Integer.toString(i), "1.00", "true", null, null, "x".repeat(1_500), "1.5"};
    }
    athena.on(EXPLAIN + "SELECT", Script.plan(List.of(), null)).on("SELECT", orders(rows));
    Limits dev = TestCtx.dev();
    Limits small = new Limits(dev.maxRows(), dev.maxLines(), 8 * 1024, dev.maxRequestBytes(), dev.maxOutputBytes(),
        dev.maxStderrBytes(), dev.maxCellChars(), dev.statementTimeout(), dev.commandTimeout(), dev.toolTimeout(),
        dev.maxRange());
    String args = "{\"sql\": \"SELECT * FROM sales.orders\"}";
    List<Integer> seen = new ArrayList<>();
    ToolResult page = connector.execute(call("athena_query", args), config(Env.DEV), secrets, TestCtx.of().with(small));
    assertThat(page.truncatedReason()).isEqualTo("size_limit");
    for (int calls = 0; calls < 10; calls++) {
      page.table().rows().forEach(r -> seen.add(r.get(0).asInt()));
      if (page.table().nextCursor() == null) {
        break;
      }
      ObjectNode more = (ObjectNode) JSON.readTree(args);
      more.put("cursor", page.table().nextCursor());
      page = connector.execute(call("athena_query", more.toString()), config(Env.DEV), secrets,
          TestCtx.of().with(small));
    }
    assertThat(seen).containsExactly(0, 1, 2, 3, 4, 5);
  }

  @Test
  void aQueryThatOutlivesTheCallIsStopped() {
    athena.on(EXPLAIN + "SELECT", Script.plan(List.of(), null)).on("SELECT", Script.running());
    assertThatThrownBy(() -> connector.execute(call("athena_query", sql("SELECT * FROM sales.orders")),
        config(Env.DEV), secrets, TestCtx.of().timeout(Duration.ofMillis(2_500))))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT);
          assertThat(e.getMessage()).contains("stopped it");
        });
    assertThat(athena.stops).hasSize(1);
    assertThat(athena.stops.getFirst()).isEqualTo(athena.started.getLast().id());
  }

  @Test
  void withARoleEachCallAssumesItUnderTheSessionPolicyOfWhatItDoes() throws Exception {
    athena.on(EXPLAIN + "SELECT", Script.plan(List.of("awsdatacatalog.sales.orders"), null))
        .on("SELECT", orders())
        .on(EXPLAIN + "SELECT * FROM staging.orders", Script.plan(List.of("awsdatacatalog.staging.orders"), null))
        .on("INSERT", orders().statement("DML", "INSERT").updated(4));
    ConnectionConfig cfg = config(Env.DEV, true, "sales", "staging");
    connector.execute(call("athena_query", sql("SELECT * FROM sales.orders")), cfg, secrets, TestCtx.of());
    List<String> policies = athena.policies();
    assertThat(policies.getFirst()).contains("athena:GetWorkGroup").doesNotContain("athena:StartQueryExecution");
    String read = policies.getLast();
    assertThat(read).contains("\"athena:StartQueryExecution\"", "workgroup/primary", "glue:GetTable*",
        "database/sales", "table/staging/*", "\"s3:PutObject\"", "arn:aws:s3:::acme-athena-results/gaterook/*")
        .doesNotContain("glue:*Table", "s3:DeleteObject", "database/hr", "athena:Get*");
    assertThat(read.length()).isLessThanOrEqualTo(AwsSessionPolicy.MAX_CHARACTERS);
    // Query results only of the connection's workgroup.
    for (JsonNode statement : JSON.readTree(read).path("Statement")) {
      if (statement.path("Action").toString().contains("athena:GetQueryResults")) {
        assertThat(statement.path("Resource").toString())
            .isEqualTo("[\"arn:aws:athena:eu-central-1:*:workgroup/primary\"]");
      }
    }

    ObjectNode approved = JsonNodeFactory.instance.objectNode().put("sql", "INSERT INTO sales.orders SELECT * FROM "
        + "staging.orders");
    ToolResult done = connector.execute(call("athena_execute", "{\"sql\": \"DROP TABLE sales.orders\"}"), cfg,
        secrets, TestCtx.of().approved(approved));
    assertThat(athena.started.getLast().sql()).isEqualTo("INSERT INTO sales.orders SELECT * FROM staging.orders");
    String write = athena.policies().getLast();
    assertThat(write).contains("s3:DeleteObject");
    assertThat(write.length()).isLessThanOrEqualTo(AwsSessionPolicy.MAX_CHARACTERS);
    // Glue changes only in the target's own database, though the statement reads another.
    JsonNode changes = null;
    for (JsonNode statement : JSON.readTree(write).path("Statement")) {
      if (statement.path("Action").toString().contains("glue:*Table")) {
        changes = statement;
      }
    }
    assertThat(changes).isNotNull();
    assertThat(changes.path("Resource").toString()).contains("database/sales", "table/sales/*")
        .doesNotContain("staging", "database/*");
    assertThat(done.structuredContent().path("rowsAffected").asInt()).isEqualTo(4);
    assertThat(done.content().getFirst().text()).startsWith("4 rows written by INSERT on sales.orders.");
  }

  @Test
  void sessionPoliciesStayWithinWhatStsAccepts() throws Exception {
    List<String> many = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      many.add("analytics_department_database_" + i);
    }
    ObjectNode c = (ObjectNode) config(Env.DEV, true).config().deepCopy();
    var list = c.putArray("databases");
    many.forEach(list::add);
    c.remove("database");
    AthenaSettings s = AthenaSettings.of(new ConnectionConfig("x", "x", "athena", Env.DEV, 1, c, Set.of(), null));
    String read = s.readPolicy("s3://acme-athena-results/gaterook/");
    assertThat(read.length()).isLessThanOrEqualTo(AwsSessionPolicy.MAX_CHARACTERS);
    assertThat(read).contains("table/*/*").doesNotContain("glue:*Table", "s3:DeleteObject");
    String write = s.writePolicy("analytics_department_database_7", "s3://acme-athena-results/gaterook/");
    assertThat(write.length()).isLessThanOrEqualTo(AwsSessionPolicy.MAX_CHARACTERS);
    assertThat(write).contains("database/analytics_department_database_7");
    // Results Athena keeps itself: no S3 write at all.
    assertThat(s.readPolicy(null)).doesNotContain("s3:PutObject");
    assertThat(s.inspectPolicy()).contains("athena:GetWorkGroup").doesNotContain("s3:", "glue:");
  }

  @Test
  void aChangeRunsOnlyApprovedOnceAndALostAnswerIsUnknown() throws Exception {
    String text = "DELETE FROM sales.orders WHERE id = 1";
    athena.on(EXPLAIN + "SELECT 1 FROM sales.orders WHERE id = 1", Script.plan(List.of("awsdatacatalog.sales.orders"),
            null))
        .on("DELETE", orders().statement("DML", "DELETE").updated(1));
    ToolCall c = call("athena_execute", sql(text));
    assertThatThrownBy(() -> connector.execute(c, config(Env.DEV), secrets, TestCtx.of()))
        .hasMessageContaining("Not approved");
    ActionPreview preview = connector.describeAction(c, config(Env.DEV));
    assertThat(preview.text()).isEqualTo(text);
    TestCtx approved = TestCtx.of().approved(preview.payload());
    connector.execute(c, config(Env.DEV), secrets, approved);
    String token = body(athena.to("StartQueryExecution").getLast()).path("ClientRequestToken").asString();
    assertThat(token).isEqualTo(AthenaConnector.requestToken(approved)).hasSize(36);
    // The same approval again: Athena returns the same query, nothing runs twice.
    connector.execute(c, config(Env.DEV), secrets, approved);
    assertThat(athena.started.stream().filter(q -> q.sql().equals(text)).count()).isEqualTo(1);

    athena.operation("StartQueryExecution", b -> json(500, "{\"__type\": \"InternalServerException\", "
        + "\"Message\": \"internal\"}"));
    assertThatThrownBy(() -> connector.execute(call("athena_execute", "{}"), config(Env.DEV), secrets,
        TestCtx.of().approved(JsonNodeFactory.instance.objectNode().put("sql", "DROP TABLE sales.daily"))))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN));
  }

  @Test
  void aChangeStillRunningAtTheDeadlineIsStoppedAndUnknown() {
    athena.on("MSCK", Script.running());
    TestCtx approved = TestCtx.of().timeout(Duration.ofMillis(2_500)).approved(JsonNodeFactory.instance.objectNode()
        .put("sql", "MSCK REPAIR TABLE sales.orders"));
    assertThatThrownBy(() -> connector.execute(call("athena_execute", "{}"), config(Env.DEV), secrets, approved))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN);
          assertThat(e.getMessage()).contains("stopped query");
        });
    assertThat(athena.stops).hasSize(1);
    // No plan for a statement Hive runs.
    assertThat(athena.started).extracting(FakeAthena.Query::sql).containsExactly("MSCK REPAIR TABLE sales.orders");
  }

  @Test
  void aFailedQuerySaysWhatAthenaSaid() {
    athena.on(EXPLAIN + "SELECT", Script.failed("TABLE_NOT_FOUND: line 1:15: Table 'awsdatacatalog.sales.nope' does "
        + "not exist", 2));
    assertThatThrownBy(() -> connector.execute(call("athena_query", sql("SELECT * FROM sales.nope")), config(Env.DEV),
        secrets, TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
          assertThat(e.getMessage()).contains("does not exist");
        });
    athena.on(EXPLAIN + "SELECT", Script.plan(List.of(), null))
        .on("SELECT", Script.failed("Query cancelled! : Bytes scanned limit was exceeded", 2));
    assertThatThrownBy(() -> connector.execute(call("athena_query", sql("SELECT * FROM sales.orders")),
        config(Env.DEV), secrets, TestCtx.of())).hasMessageContaining("workgroup's data limit");
    athena.on("INSERT", Script.failed("HIVE_PATH_ALREADY_EXISTS", 2))
        .on(EXPLAIN + "VALUES", Script.plan(List.of(), null));
    assertThatThrownBy(() -> connector.execute(call("athena_execute", "{}"), config(Env.DEV), secrets,
        TestCtx.of().approved(JsonNodeFactory.instance.objectNode().put("sql", "INSERT INTO sales.orders VALUES 1"))))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
          assertThat(e.getMessage()).contains("can leave data files");
        });
    athena.operation("ListDatabases", b -> json(400, "{\"__type\": \"AccessDeniedException\", \"Message\": \"User is "
        + "not authorized to perform: athena:ListDatabases\"}"));
    assertThatThrownBy(() -> connector.execute(call("athena_list_databases", "{}"), config(Env.DEV), secrets,
        TestCtx.of())).hasMessageContaining("may not do this", "athena:ListDatabases");
  }

  @Test
  void theApprovalShowsAthenasPlanAndTheWorkgroupsLimit() throws Exception {
    athena.on(EXPLAIN + "SELECT", Script.plan(List.of("awsdatacatalog.staging.orders"), null));
    PreparedAction prepared = connector.prepare(call("athena_execute", sql("INSERT INTO sales.orders SELECT * FROM "
        + "staging.orders")), config(Env.DEV, true, "sales", "staging"), secrets, TestCtx.of());
    assertThat(prepared.outcome()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    assertThat(prepared.preview().impact()).extracting(ActionPreview.Field::label).startsWith("Statement", "Writes",
        "Reads", "Data limit");
    // The table written is the statement's own, in the connection's catalog; what it reads is Athena's plan.
    assertThat(prepared.preview().impact().get(1).value()).isEqualTo("awsdatacatalog.sales.orders");
    assertThat(prepared.preview().impact().get(2).value()).isEqualTo("awsdatacatalog.staging.orders");
    assertThat(prepared.preview().impact().get(3).value()).isEqualTo("10.0 GiB per query (workgroup primary)");
    PreparedAction unqualified = connector.prepare(call("athena_execute", sql("INSERT INTO Daily SELECT 1")),
        config(Env.DEV), secrets, TestCtx.of());
    assertThat(unqualified.preview().impact().get(1).value()).isEqualTo("awsdatacatalog.sales.daily");
    PreparedAction ddl = connector.prepare(call("athena_execute", sql("ALTER TABLE sales.orders ADD PARTITION "
        + "(dt = '2026-11-11')")), config(Env.DEV), secrets, TestCtx.of());
    assertThat(ddl.preview().impact()).extracting(ActionPreview.Field::label).contains("Athena's plan");
    athena.on(EXPLAIN + "SELECT", Script.plan(List.of("awsdatacatalog.hr.salaries"), null));
    PreparedAction outside = connector.prepare(call("athena_execute", sql("INSERT INTO sales.orders SELECT * FROM "
        + "hr.salaries")), config(Env.DEV, "sales"), secrets, TestCtx.of());
    assertThat(outside.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(outside.message()).contains("awsdatacatalog.hr.salaries");
    // A plan of the read that writes a table is no plan of a read; a target outside the list is refused unasked.
    athena.on(EXPLAIN + "SELECT", Script.plan(List.of("awsdatacatalog.staging.orders"), "awsdatacatalog.sales.x"));
    assertThat(connector.prepare(call("athena_execute", sql("INSERT INTO sales.orders SELECT * FROM staging.orders")),
        config(Env.DEV), secrets, TestCtx.of()).outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    int started = athena.started.size();
    PreparedAction elsewhere = connector.prepare(call("athena_execute", sql("INSERT INTO hr.salaries SELECT 1")),
        config(Env.DEV, "sales"), secrets, TestCtx.of());
    assertThat(elsewhere.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(athena.started).hasSize(started);
  }

  @Test
  void aChangeIsPlannedByAReadOfWhatItReadsNeverByItsWrite() throws Exception {
    Map<String, String> reads = new LinkedHashMap<>();
    reads.put("CREATE TABLE sales.daily WITH (format = 'PARQUET') AS SELECT * FROM staging.orders WITH DATA",
        "SELECT * FROM staging.orders");
    reads.put("INSERT INTO sales.orders (id) VALUES (1)", "VALUES (1)");
    reads.put("DELETE FROM sales.orders WHERE id IN (SELECT id FROM staging.orders)",
        "SELECT 1 FROM sales.orders WHERE id IN (SELECT id FROM staging.orders)");
    reads.put("UPDATE sales.orders SET paid = true WHERE id = 1", "SELECT true FROM sales.orders WHERE id = 1");
    reads.put("MERGE INTO sales.orders o USING staging.orders s ON o.id = s.id WHEN NOT MATCHED THEN INSERT (id) "
        + "VALUES (s.id)", "SELECT s.id FROM sales.orders o FULL JOIN staging.orders s ON o.id = s.id");
    for (Map.Entry<String, String> e : reads.entrySet()) {
      // Each change in its own Athena: one approval's request token starts one query.
      try (FakeAthena fake = new FakeAthena()) {
        AthenaConnector own = new AthenaConnector(fake.cloud);
        fake.on(EXPLAIN, Script.plan(List.of("awsdatacatalog.sales.orders", "awsdatacatalog.staging.orders"), null));
        for (String prefix : List.of("CREATE", "INSERT", "DELETE", "UPDATE", "MERGE")) {
          fake.on(prefix, orders().statement("DML", prefix).updated(1));
        }
        ConnectionConfig cfg = config(Env.DEV, true, "sales", "staging");
        ToolCall c = call("athena_execute", sql(e.getKey()));
        assertThat(own.classify(c, cfg, policy(Env.DEV)).kind()).as(e.getKey())
            .isEqualTo(Classification.Kind.MUTATION);
        PreparedAction prepared = own.prepare(c, cfg, secrets, TestCtx.of());
        assertThat(prepared.outcome()).as(e.getKey()).isEqualTo(PreparedAction.Outcome.APPROVAL);
        assertThat(prepared.preview().impact()).extracting(ActionPreview.Field::value).as(e.getKey())
            .contains("awsdatacatalog.sales.orders, awsdatacatalog.staging.orders");
        own.execute(c, cfg, secrets, TestCtx.of().approved(prepared.preview().payload()));
        // Before the approval and right before the run, Athena plans the read; then the approved statement runs.
        assertThat(fake.started).extracting(FakeAthena.Query::sql).as(e.getKey()).containsExactly(EXPLAIN
            + e.getValue(), EXPLAIN + e.getValue(), e.getKey());
        assertThat(fake.started).extracting(FakeAthena.Query::sql).noneMatch(s -> s.matches("(?s)EXPLAIN \\(TYPE IO, "
            + "FORMAT JSON\\) (CREATE|INSERT|DELETE|UPDATE|MERGE).*"));
        own.close();
      }
    }
  }

  @Test
  void aReadOutsideTheDatabasesStopsTheChangeAlsoRightBeforeItRuns() throws Exception {
    athena.on(EXPLAIN + "SELECT", Script.plan(List.of("awsdatacatalog.staging.orders"), null))
        .on("INSERT", orders().statement("DML", "INSERT").updated(4));
    String text = "INSERT INTO sales.orders SELECT * FROM staging.orders";
    PreparedAction prepared = connector.prepare(call("athena_execute", sql(text)), config(Env.DEV, "sales", "staging"),
        secrets, TestCtx.of());
    assertThat(prepared.outcome()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    athena.on(EXPLAIN + "SELECT", Script.plan(List.of("awsdatacatalog.staging.orders", "awsdatacatalog.hr.salaries"),
        null));
    assertThatThrownBy(() -> connector.execute(call("athena_execute", "{}"), config(Env.DEV, "sales", "staging"),
        secrets, TestCtx.of().approved(prepared.preview().payload())))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).contains("awsdatacatalog.hr.salaries");
        });
    assertThat(athena.started).extracting(FakeAthena.Query::sql).containsExactly(EXPLAIN + "SELECT * FROM "
        + "staging.orders", EXPLAIN + "SELECT * FROM staging.orders");
    // Only queries are ever planned: a write is refused before anything is sent.
    AthenaApi.Call c = new AthenaApi.Call(config(Env.DEV), AthenaSettings.of(config(Env.DEV)), secrets, TestCtx.of());
    for (String write : List.of(text, "CREATE TABLE sales.t AS SELECT 1", "DELETE FROM sales.orders",
        "DESCRIBE sales.orders")) {
      assertThatThrownBy(() -> connector.plan(c, write, null)).as(write)
          .isInstanceOfSatisfying(ConnectorException.class,
              e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    }
    assertThat(athena.started).hasSize(2);
  }

  @Test
  void listsAndDescribesWithinTheList() throws Exception {
    athena.operation("ListDataCatalogs", "{\"DataCatalogsSummary\": [{\"CatalogName\": \"AwsDataCatalog\", "
            + "\"Type\": \"GLUE\"}, {\"CatalogName\": \"dynamo\", \"Type\": \"LAMBDA\"}]}")
        .operation("ListDatabases", b -> b.has("NextToken")
            ? json(200, "{\"DatabaseList\": [{\"Name\": \"sales_eu\"}]}")
            : json(200, "{\"DatabaseList\": [{\"Name\": \"hr\"}, {\"Name\": \"sales\", \"Description\": \"Orders\"}], "
                + "\"NextToken\": \"db-2\"}"))
        .operation("ListTableMetadata", "{\"TableMetadataList\": [{\"Name\": \"orders\", \"TableType\": "
            + "\"EXTERNAL_TABLE\"}, {\"Name\": \"v_orders\", \"TableType\": \"VIRTUAL_VIEW\"}]}")
        .operation("GetTableMetadata", "{\"TableMetadata\": {\"Name\": \"orders\", \"TableType\": \"EXTERNAL_TABLE\", "
            + "\"Columns\": [{\"Name\": \"id\", \"Type\": \"bigint\", \"Comment\": \"the key\"}, {\"Name\": \"total\", "
            + "\"Type\": \"decimal(10,2)\"}], \"PartitionKeys\": [{\"Name\": \"dt\", \"Type\": \"string\"}], "
            + "\"Parameters\": {\"location\": \"s3://acme-data/orders/\", \"classification\": \"parquet\", "
            + "\"secret_param\": \"hidden\"}}}");
    ConnectionConfig cfg = config(Env.DEV, "sales*");
    ToolResult catalogs = connector.execute(call("athena_list_catalogs", "{}"), cfg, secrets, TestCtx.of());
    assertThat(catalogs.structuredContent().path("catalogs").toString()).contains("AwsDataCatalog")
        .doesNotContain("dynamo");
    ToolResult databases = connector.execute(call("athena_list_databases", "{\"limit\": 1}"), cfg, secrets,
        TestCtx.of());
    assertThat(databases.structuredContent().path("databases").toString()).contains("sales").doesNotContain("hr");
    ToolResult rest = connector.execute(call("athena_list_databases", "{\"cursor\": \""
        + databases.structuredContent().path("nextCursor").asString() + "\"}"), cfg, secrets, TestCtx.of());
    assertThat(rest.structuredContent().path("databases").toString()).contains("sales_eu");
    ToolResult tables = connector.execute(call("athena_list_tables", "{}"), cfg, secrets, TestCtx.of());
    assertThat(tables.structuredContent().path("tables").get(1).path("kind").asString()).isEqualTo("view");
    ToolResult d = connector.execute(call("athena_describe_table", "{\"table\": \"orders\"}"), cfg, secrets,
        TestCtx.of());
    assertThat(d.structuredContent().path("table").asString()).isEqualTo("AwsDataCatalog.sales.orders");
    assertThat(d.structuredContent().at("/columns/0/comment").asString()).isEqualTo("the key");
    assertThat(d.structuredContent().path("location").asString()).isEqualTo("s3://acme-data/orders/");
    assertThat(d.structuredContent().has("secret_param")).isFalse();
    assertThat(d.content().getFirst().text()).contains("partitioned by dt");
    assertThatThrownBy(() -> connector.execute(call("athena_describe_table", "{\"table\": \"hr.salaries\"}"), cfg,
        secrets, TestCtx.of())).isInstanceOf(ConnectorException.class);
  }

  @Test
  void testConnectionReadsTheWorkgroupAndWarnsWithoutItsLimits() {
    athena.on("EXPLAIN SELECT 1", Script.select(List.of("Query Plan:varchar"), new String[] {"- Output[_col0]"}))
        .operation("ListDatabases", "{\"DatabaseList\": [{\"Name\": \"sales\"}, {\"Name\": \"hr\"}]}");
    TestResult t = connector.test(config(Env.PROD, true, "sales"), secrets, TestCtx.of().env(Env.PROD));
    assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
    assertThat(t.identity()).contains("assumed-role/gw-athena");
    assertThat(t.checks()).extracting(TestResult.Check::name).containsExactly("identity", "workgroup", "databases",
        "query");
    assertThat(t.checks().get(1).detail()).contains("Athena engine version 3", "10.0 GiB per query");
    assertThat(t.checks().get(2).detail()).contains("1 database agents may use", "sales");
    assertThat(t.hints()).extracting(TestResult.Hint::code).doesNotContain("athena.no-scan-cutoff",
        "athena.not-enforced", "athena.no-role");

    athena.operation("GetWorkGroup", b -> json(200, FakeAthena.workgroup(null, false, null, true)));
    TestResult open = connector.test(config(Env.PROD), secrets, TestCtx.of().env(Env.PROD));
    assertThat(open.ok()).as(String.valueOf(open.error())).isTrue();
    assertThat(open.hints()).extracting(TestResult.Hint::code).contains("athena.no-scan-cutoff", "athena.not-enforced",
        "athena.no-role");
    assertThat(open.hints()).filteredOn(h -> h.code().equals("athena.no-scan-cutoff"))
        .allSatisfy(h -> assertThat(h.severity()).isEqualTo(TestResult.Severity.WARNING));
  }

  @Test
  void anIncompleteConnectionIsRefusedWithoutAnyRequest() {
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("region", "nowhere");
    ConnectionConfig broken = new ConnectionConfig("conn-x", "x", "athena", Env.DEV, 1, c, Set.of(), null);
    assertThat(connector.classify(call("athena_list_catalogs", "{}"), broken, policy(Env.DEV)).kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThat(connector.test(broken, secrets, TestCtx.of()).error().code()).isEqualTo("athena.settings");
    ObjectNode bad = (ObjectNode) config(Env.DEV).config().deepCopy();
    bad.put("outputLocation", "https://acme.example.com/results");
    assertThatThrownBy(() -> AthenaSettings.of(new ConnectionConfig("y", "y", "athena", Env.DEV, 1, bad, Set.of(),
        null))).hasMessageContaining("S3 URI");
    assertThat(athena.requests).isEmpty();
  }
}
