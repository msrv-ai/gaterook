package com.gaterook.connectors.spanner;

import static com.gaterook.connectors.spanner.FakeSpanner.SESSION;
import static com.gaterook.connectors.spanner.FakeSpanner.body;
import static com.gaterook.connectors.spanner.FakeSpanner.field;
import static com.gaterook.connectors.spanner.FakeSpanner.first;
import static com.gaterook.connectors.spanner.FakeSpanner.gatewayStream;
import static com.gaterook.connectors.spanner.FakeSpanner.json;
import static com.gaterook.connectors.spanner.FakeSpanner.more;
import static com.gaterook.connectors.spanner.FakeSpanner.resultSet;
import static com.gaterook.connectors.spanner.FakeSpanner.stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connector.api.ActionPreview;
import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** The Spanner connector against a fake of Google's token server and Spanner's REST API. */
class SpannerConnectorTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final List<String> ORDER_FIELDS = List.of(field("id", "INT64"), field("note", "STRING"));

  private FakeSpanner google;
  private SpannerConnector spanner;
  private final TestSecrets secrets = new TestSecrets();

  @BeforeEach
  void start() throws Exception {
    google = new FakeSpanner();
    spanner = new SpannerConnector(google.cloud);
  }

  @AfterEach
  void stop() {
    spanner.close();
    google.close();
  }

  private static ConnectionConfig config(Env env, String role) {
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("project", "acme-prod").put("instance", "orders-prod")
        .put("database", "orders");
    c.putObject("credentials").put("source", "adc");
    if (role != null) {
      c.putObject("advanced").put("databaseRole", role);
    }
    return new ConnectionConfig("conn-sp-" + env.id(), env.id() + "-spanner", "spanner", env, 1, c, Set.of(), null);
  }

  private static ConnectionConfig config() {
    return config(Env.DEV, null);
  }

  private static PolicyView policy(Env env) {
    return new PolicyView(env, env.id() + ".spanner", JsonNodeFactory.instance.objectNode(),
        env == Env.PROD ? TestCtx.prod() : TestCtx.dev());
  }

  private static ToolCall call(String tool, String args) {
    return new ToolCall("tc_" + Long.toHexString(System.nanoTime()), tool, "dev-spanner",
        (ObjectNode) JSON.readTree(args), null);
  }

  private static String sql(String sql) {
    return JSON.writeValueAsString(Map.of("sql", sql));
  }

  private Classification classify(String tool, String args) {
    return spanner.classify(call(tool, args), config(), policy(Env.DEV));
  }

  // ── Routes ──────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void readsAreQueriesAndChangesNeedApprovalOfTheExactStatement() {
    assertThat(classify("spanner_query", sql("SELECT * FROM Orders")).kind()).isEqualTo(Classification.Kind.READ);
    assertThat(classify("spanner_query", sql("FROM Orders |> WHERE id > 3")).kind())
        .isEqualTo(Classification.Kind.READ);
    Classification write = classify("spanner_query", sql("DELETE FROM Orders WHERE true"));
    assertThat(write.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(write.ruleId()).isEqualTo("dev.spanner.query-writes");
    Classification dml = classify("spanner_execute", sql("UPDATE Orders SET note = 'x' WHERE id = 1"));
    assertThat(dml.kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(dml.reason()).isEqualTo("UPDATE on Orders changes data");
    Classification ddl = classify("spanner_execute", sql("CREATE INDEX OrdersByNote ON Orders(note)"));
    assertThat(ddl.reason()).isEqualTo("CREATE on Orders changes the schema");
    assertThat(classify("spanner_execute", sql("SELECT 1")).ruleId()).isEqualTo("dev.spanner.execute-read");
    assertThat(classify("spanner_execute", sql("GRANT SELECT ON TABLE Orders TO ROLE r")).ruleId())
        .isEqualTo("dev.spanner.forbidden");
    assertThat(classify("spanner_query", sql("SELECT 1; DELETE FROM Orders WHERE true")).ruleId())
        .isEqualTo("dev.spanner.forbidden");
    assertThat(classify("spanner_describe_table", "{\"table\": \"bad name\"}").ruleId())
        .isEqualTo("dev.spanner.bad-argument");
    assertThat(classify("spanner_query", "{\"sql\": \"SELECT @n\", \"parameters\": [{\"name\": \"n\", "
        + "\"type\": \"INT64\", \"value\": \"x\"}]}").reason()).isEqualTo("Parameter n (INT64) needs a whole number.");
    ConnectionConfig bad = new ConnectionConfig("c", "dev-spanner", "spanner", Env.DEV, 1,
        JsonNodeFactory.instance.objectNode().put("project", "acme-prod"), Set.of(), null);
    assertThat(spanner.classify(call("spanner_query", sql("SELECT 1")), bad, policy(Env.DEV)).ruleId())
        .isEqualTo("dev.spanner.bad-config");
  }

  @Test
  void anApprovalShowsTheExactStatementWithWhatItTouches() {
    ActionPreview p = spanner.describeAction(call("spanner_execute", "{\"sql\": \"DELETE FROM Orders WHERE id = @id\","
        + " \"parameters\": [{\"name\": \"id\", \"type\": \"INT64\", \"value\": 7}]}"), config(Env.PROD, "agent"));
    assertThat(p.kind()).isEqualTo(ActionPreview.Kind.SQL);
    assertThat(p.payload().path("sql").asString()).isEqualTo("DELETE FROM Orders WHERE id = @id");
    assertThat(p.payload().path("parameters").get(0).path("value").asInt()).isEqualTo(7);
    assertThat(p.impact()).extracting(ActionPreview.Field::label).contains("Target", "Transaction", "Retried");
    assertThat(p.target()).contains("orders-prod/orders").contains("role agent");
  }

  // ── Reads ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void listsTablesInAReadOnlyTransactionOfItsOwnSessionInTheDatabaseRole() throws Exception {
    google.onSql("INFORMATION_SCHEMA.TABLES", r -> json(200, resultSet(List.of(field("TABLE_SCHEMA", "STRING"),
        field("TABLE_NAME", "STRING"), field("TABLE_TYPE", "STRING"), field("PARENT_TABLE_NAME", "STRING")),
        List.of(List.of("\"\"", "\"Customers\"", "\"BASE TABLE\"", "null"),
            List.of("\"\"", "\"Orders\"", "\"BASE TABLE\"", "\"Customers\""),
            List.of("\"sales\"", "\"Totals\"", "\"VIEW\"", "null")))));
    ToolResult r = spanner.execute(call("spanner_list_tables", "{}"), config(Env.DEV, "agent_reader"), secrets,
        TestCtx.of());
    assertThat(r.content().getFirst().text()).isEqualTo("3 tables: Customers, Orders (in Customers), "
        + "sales.Totals (view)");
    JsonNode session = body(google.to("POST /sessions").getFirst());
    assertThat(session.at("/session/creatorRole").asString()).isEqualTo("agent_reader");
    JsonNode query = google.statements().getFirst();
    assertThat(query.at("/transaction/singleUse/readOnly/strong").asBoolean()).isTrue();
    assertThat(google.to("DELETE /sessions/s-1")).hasSize(1);
  }

  @Test
  void describesColumnsKeysIndexesAndTheTablesOwnDdl() throws Exception {
    google.onSql("ON_DELETE_ACTION", r -> json(200, resultSet(List.of(field("TABLE_TYPE", "STRING"),
        field("PARENT_TABLE_NAME", "STRING"), field("ON_DELETE_ACTION", "STRING")),
        List.of(List.of("\"BASE TABLE\"", "\"Customers\"", "\"CASCADE\"")))));
    google.onSql("INFORMATION_SCHEMA.COLUMNS", r -> json(200, resultSet(List.of(field("COLUMN_NAME", "STRING"),
        field("SPANNER_TYPE", "STRING"), field("IS_NULLABLE", "STRING"), field("D", "STRING"), field("G", "STRING")),
        List.of(List.of("\"CustomerId\"", "\"INT64\"", "\"NO\"", "null", "null"),
            List.of("\"OrderId\"", "\"INT64\"", "\"NO\"", "null", "null"),
            List.of("\"Note\"", "\"STRING(MAX)\"", "\"YES\"", "\"'none'\"", "null")))));
    google.onSql("INFORMATION_SCHEMA.INDEXES", r -> json(200, resultSet(List.of(field("INDEX_NAME", "STRING"),
        field("INDEX_TYPE", "STRING"), field("IS_UNIQUE", "BOOL"), field("IS_NULL_FILTERED", "BOOL"),
        field("COLUMN_NAME", "STRING"), field("ORDINAL_POSITION", "INT64"), field("COLUMN_ORDERING", "STRING")),
        List.of(List.of("\"OrdersByNote\"", "\"INDEX\"", "true", "false", "\"Note\"", "\"1\"", "\"DESC\""),
            List.of("\"OrdersByNote\"", "\"INDEX\"", "true", "false", "\"OrderId\"", "null", "null"),
            List.of("\"PRIMARY_KEY\"", "\"PRIMARY_KEY\"", "true", "false", "\"CustomerId\"", "\"1\"", "\"ASC\""),
            List.of("\"PRIMARY_KEY\"", "\"PRIMARY_KEY\"", "true", "false", "\"OrderId\"", "\"2\"", "\"ASC\"")))));
    google.on("GET /ddl", "{\"statements\":[\"CREATE TABLE Customers (\\n  CustomerId INT64 NOT NULL,\\n) PRIMARY "
        + "KEY(CustomerId)\",\"CREATE TABLE Orders (\\n  CustomerId INT64 NOT NULL,\\n  OrderId INT64 NOT NULL,\\n  "
        + "Note STRING(MAX) DEFAULT ('none'),\\n) PRIMARY KEY(CustomerId, OrderId),\\n  INTERLEAVE IN PARENT "
        + "Customers ON DELETE CASCADE\",\"CREATE UNIQUE INDEX OrdersByNote ON Orders(Note DESC) STORING (OrderId)\","
        + "\"CREATE TABLE OrdersArchive (\\n  Id INT64,\\n) PRIMARY KEY(Id)\"]}");
    ToolResult r = spanner.execute(call("spanner_describe_table", "{\"table\": \"Orders\"}"), config(), secrets,
        TestCtx.of());
    JsonNode out = r.structuredContent();
    assertThat(out.path("parent").asString()).isEqualTo("Customers");
    assertThat(out.path("onDelete").asString()).isEqualTo("CASCADE");
    assertThat(out.path("columns").get(2).path("default").asString()).isEqualTo("'none'");
    assertThat(out.path("primaryKey").toString()).isEqualTo("[\"CustomerId\",\"OrderId\"]");
    assertThat(out.at("/indexes/0/columns").toString()).isEqualTo("[\"Note DESC\"]");
    assertThat(out.at("/indexes/0/storing").toString()).isEqualTo("[\"OrderId\"]");
    assertThat(out.path("ddl")).hasSize(2);
    assertThat(r.content().getFirst().text()).startsWith("Orders (table, interleaved in Customers): CustomerId INT64 "
        + "NOT NULL").contains("primary key (CustomerId, OrderId)").endsWith("1 index");
  }

  @Test
  void aQueryRunsReadOnlyAndPagesAtTheFirstPagesSnapshot() throws Exception {
    google.onSql("FROM Orders", r -> {
      boolean later = body(r).at("/transaction/singleUse/readOnly/readTimestamp").isString();
      return later ? stream(first(ORDER_FIELDS, "\"1\",\"a\""), more("\"2\",\"b\",\"3\",\"c\"", false))
          : stream(first(ORDER_FIELDS, "\"1\",\"a\""), more("\"2\",\"b\"", false), more("\"3\",\"c\"", false));
    });
    ToolResult page = spanner.execute(call("spanner_query", "{\"sql\": \"SELECT id, note FROM Orders ORDER BY id\", "
        + "\"limit\": 2}"), config(), secrets, TestCtx.of());
    assertThat(page.table().rows().toString()).isEqualTo("[[1,\"a\"],[2,\"b\"]]");
    assertThat(page.table().columns()).extracting(ToolResult.Column::type).containsExactly("INT64", "STRING");
    String cursor = page.table().nextCursor();
    assertThat(cursor).startsWith("sp1.");
    JsonNode firstRequest = google.statements().getFirst();
    assertThat(firstRequest.at("/transaction/singleUse/readOnly/strong").asBoolean()).isTrue();
    assertThat(firstRequest.at("/transaction/singleUse/readOnly/returnReadTimestamp").asBoolean()).isTrue();

    ToolResult next = spanner.execute(call("spanner_query", "{\"sql\": \"SELECT id, note FROM Orders ORDER BY id\", "
        + "\"limit\": 2, \"cursor\": \"" + cursor + "\"}"), config(), secrets, TestCtx.of());
    assertThat(next.table().rows().toString()).isEqualTo("[[3,\"c\"]]");
    assertThat(next.table().nextCursor()).isNull();
    assertThat(google.statements().get(1).at("/transaction/singleUse/readOnly/readTimestamp").asString())
        .isEqualTo("2026-11-11T10:00:00.000001Z");
    // The cursor belongs to its statement.
    assertThatThrownBy(() -> spanner.execute(call("spanner_query", "{\"sql\": \"SELECT 1 FROM Orders\", "
        + "\"cursor\": \"" + cursor + "\"}"), config(), secrets, TestCtx.of()))
        .hasMessageContaining("another connection or request");
  }

  @Test
  void joinsValuesThatAGatewayStreamSplitAcrossMessages() throws Exception {
    google.onSql("FROM Notes", r -> gatewayStream(
        first(List.of(field("note", "STRING"), "{\"name\":\"tags\",\"type\":{\"code\":\"ARRAY\",\"arrayElementType\":"
            + "{\"code\":\"STRING\"}}}"), "\"hel\"", true),
        more("\"lo\",[\"a\",\"b\"]", true),
        more("[\"c\",\"d\"],\"x\",[]", false)));
    ToolResult r = spanner.execute(call("spanner_query", sql("SELECT note, tags FROM Notes")), config(), secrets,
        TestCtx.of());
    assertThat(r.table().rows().toString()).isEqualTo("[[\"hello\",[\"a\",\"bc\",\"d\"]],[\"x\",[]]]");
  }

  @Test
  void theQueryToolRefusesAChangeBeforeSpannerSeesIt() {
    assertThatThrownBy(() -> spanner.execute(call("spanner_query", sql("INSERT INTO Orders (id) VALUES (1)")),
        config(), secrets, TestCtx.of())).isInstanceOf(ConnectorException.class)
        .hasMessageContaining("only runs one statement the gateway reads as a read");
    assertThat(google.statements()).isEmpty();
  }

  // ── Changes ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static ObjectNode approved(String sql) {
    return JsonNodeFactory.instance.objectNode().put("sql", sql);
  }

  @Test
  void anApprovedDmlStatementCommitsOnceInItsOwnReadWriteTransaction() throws Exception {
    google.on("POST /sessions/s-1:beginTransaction", "{\"id\":\"dHgx\"}");
    google.onSql("UPDATE Orders", r -> json(200, "{\"metadata\":{\"rowType\":{\"fields\":[]}},\"stats\":"
        + "{\"rowCountExact\":\"3\"}}"));
    google.on("POST /sessions/s-1:commit", "{\"commitTimestamp\":\"2026-11-11T10:01:00Z\",\"commitStats\":"
        + "{\"mutationCount\":\"6\"}}");
    String text = "UPDATE Orders SET note = 'shipped' WHERE id < 4";
    ToolResult r = spanner.execute(call("spanner_execute", sql(text)), config(), secrets,
        TestCtx.of().approved(approved(text)));
    assertThat(r.content().getFirst().text()).isEqualTo("3 rows affected by UPDATE, committed at "
        + "2026-11-11T10:01:00Z.");
    assertThat(r.structuredContent().path("mutations").asLong()).isEqualTo(6);
    assertThat(body(google.to("POST /sessions/s-1:beginTransaction").getFirst()).at("/options/readWrite").isObject())
        .isTrue();
    JsonNode dml = google.statements().getFirst();
    assertThat(dml.at("/transaction/id").asString()).isEqualTo("dHgx");
    assertThat(dml.path("seqno").asString()).isEqualTo("1");
    assertThat(body(google.to("POST /sessions/s-1:commit").getFirst()).path("transactionId").asString())
        .isEqualTo("dHgx");
  }

  @Test
  void aChangeWithoutApprovalNeverReachesSpanner() {
    assertThatThrownBy(() -> spanner.execute(call("spanner_execute", sql("DELETE FROM Orders WHERE true")), config(),
        secrets, TestCtx.of())).hasMessageStartingWith("Not approved");
    assertThat(google.requests).isEmpty();
  }

  @Test
  void anAbortedCommitChangedNothingAndAFailedStatementRollsBack() {
    google.on("POST /sessions/s-1:beginTransaction", "{\"id\":\"dHgx\"}");
    google.onSql("UPDATE Orders", r -> json(200, "{\"stats\":{\"rowCountExact\":\"1\"}}"));
    google.on("POST /sessions/s-1:commit", r -> json(409, "{\"error\":{\"code\":409,\"message\":\"Transaction was "
        + "aborted.\",\"status\":\"ABORTED\"}}"));
    String text = "UPDATE Orders SET note = 'x' WHERE id = 1";
    assertThatThrownBy(() -> spanner.execute(call("spanner_execute", sql(text)), config(), secrets,
        TestCtx.of().approved(approved(text)))).hasMessageContaining("nothing changed. Run it again.");

    google.onSql("DELETE FROM Orders", r -> json(400, "{\"error\":{\"code\":400,\"message\":\"Table not found: "
        + "Orders\",\"status\":\"INVALID_ARGUMENT\"}}"));
    String delete = "DELETE FROM Orders WHERE id = 2";
    assertThatThrownBy(() -> spanner.execute(call("spanner_execute", sql(delete)), config(), secrets,
        TestCtx.of().approved(approved(delete)))).hasMessageContaining("Table not found");
    assertThat(google.to("POST /sessions/s-1:rollback")).hasSize(1);
  }

  @Test
  void aSchemaChangeHasTheApprovalsOperationIdAndIsFollowedUntilDone() throws Exception {
    String id = SpannerConnector.operationId(TestCtx.of().approved(approved("x")));
    assertThat(id).matches("gaterook_[0-9a-f]{32}");
    google.on("PATCH /ddl", r -> json(200, "{\"name\":\"" + FakeSpanner.DATABASE + "/operations/" + id
        + "\",\"done\":false}"));
    google.on("GET /operations/" + id, "{\"name\":\"" + FakeSpanner.DATABASE + "/operations/" + id
        + "\",\"done\":true}");
    String text = "CREATE INDEX OrdersByNote ON Orders(Note)";
    ToolResult r = spanner.execute(call("spanner_execute", sql(text)), config(), secrets,
        TestCtx.of().approved(approved(text)));
    assertThat(r.content().getFirst().text()).isEqualTo("CREATE on Orders applied.");
    JsonNode patch = body(google.to("PATCH /ddl").getFirst());
    assertThat(patch.path("operationId").asString()).isEqualTo(id);
    assertThat(patch.path("statements").get(0).asString()).isEqualTo(text);

    // The same approval sent again finds its first operation instead of applying the statement twice.
    google.on("PATCH /ddl", r2 -> json(409, "{\"error\":{\"code\":409,\"message\":\"Operation already exists\","
        + "\"status\":\"ALREADY_EXISTS\"}}"));
    ToolResult again = spanner.execute(call("spanner_execute", sql(text)), config(), secrets,
        TestCtx.of().approved(approved(text)));
    assertThat(again.structuredContent().path("applied").asBoolean()).isTrue();
  }

  @Test
  void aRefusedSchemaChangeSaysNothingChanged() {
    google.on("PATCH /ddl", r -> json(200, "{\"name\":\"" + FakeSpanner.DATABASE + "/operations/op_1\",\"done\":true,"
        + "\"error\":{\"code\":9,\"message\":\"Duplicate name in schema: OrdersByNote.\"}}"));
    String text = "CREATE INDEX OrdersByNote ON Orders(Note)";
    assertThatThrownBy(() -> spanner.execute(call("spanner_execute", sql(text)), config(), secrets,
        TestCtx.of().approved(approved(text)))).hasMessage("Spanner refused the schema change: Duplicate name in "
        + "schema: OrdersByNote. Nothing changed.");
  }

  // ── PostgreSQL dialect ──────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aPostgresDatabaseGetsItsCatalogItsParametersAndItsTypeNames() throws Exception {
    google.postgres = true;
    google.onSql("information_schema.tables", r -> json(200, resultSet(List.of(field("table_schema", "STRING"),
        field("table_name", "STRING"), field("table_type", "STRING"), field("parent_table_name", "STRING")),
        List.of(List.of("\"public\"", "\"orders\"", "\"BASE TABLE\"", "null")))));
    ToolResult tables = spanner.execute(call("spanner_list_tables", "{\"schema\": \"public\"}"), config(), secrets,
        TestCtx.of());
    assertThat(tables.content().getFirst().text()).isEqualTo("1 table: orders");
    JsonNode list = google.statements().getFirst();
    assertThat(list.path("sql").asString()).contains("table_schema = $1");
    assertThat(list.at("/params/p1").asString()).isEqualTo("public");

    google.onSql("FROM orders WHERE", r -> stream(first(List.of(field("id", "INT64"),
        "{\"name\":\"amount\",\"type\":{\"code\":\"NUMERIC\",\"typeAnnotation\":\"PG_NUMERIC\"}}"),
        "\"1\",\"12.50\"")));
    ToolResult q = spanner.execute(call("spanner_query", "{\"sql\": \"SELECT id, amount FROM orders WHERE amount > "
        + "$1\", \"parameters\": [{\"name\": \"p1\", \"type\": \"NUMERIC\", \"value\": \"10\"}]}"), config(), secrets,
        TestCtx.of());
    assertThat(q.table().columns()).extracting(ToolResult.Column::type).containsExactly("bigint", "numeric");
    JsonNode request = google.statements().get(1);
    assertThat(request.at("/paramTypes/p1/typeAnnotation").asString()).isEqualTo("PG_NUMERIC");
    assertThat(request.at("/params/p1").asString()).isEqualTo("10");
  }

  // ── Test connection ─────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void testConnectionOpensASessionReadsAndCountsTheTables() {
    google.onSql("INFORMATION_SCHEMA.TABLES", r -> json(200, resultSet(List.of(field("TABLE_SCHEMA", "STRING"),
        field("TABLE_NAME", "STRING")), List.of(List.of("\"\"", "\"Orders\"")))));
    TestResult t = spanner.test(config(Env.PROD, null), secrets, TestCtx.of().env(Env.PROD));
    assertThat(t.ok()).isTrue();
    assertThat(t.checks()).extracting(TestResult.Check::name).containsExactly("session", "read", "tables");
    assertThat(t.hints()).extracting(TestResult.Hint::code).contains("spanner.personal-credentials",
        "spanner.read-only-boundary");
  }

  @Test
  void aTurnedOffApiSaysHowToTurnItOn() {
    google.on("POST /sessions", r -> json(403, "{\"error\":{\"code\":403,\"message\":\"Cloud Spanner API has not "
        + "been used in project acme-prod before or it is disabled.\",\"status\":\"PERMISSION_DENIED\",\"details\":"
        + "[{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\",\"reason\":\"SERVICE_DISABLED\"}]}}"));
    TestResult t = spanner.test(config(), secrets, TestCtx.of());
    assertThat(t.ok()).isFalse();
    assertThat(t.error().message()).contains("gcloud services enable spanner.googleapis.com --project=acme-prod");
  }

  @Test
  void sessionNamesOutsideTheDatabaseAreRefused() {
    google.on("POST /sessions", "{\"name\":\"projects/other/instances/x/databases/y/sessions/s-9\"}");
    assertThatThrownBy(() -> spanner.execute(call("spanner_list_tables", "{}"), config(), secrets, TestCtx.of()))
        .hasMessageContaining("outside orders-prod/orders");
    assertThat(google.statements()).isEmpty();
    assertThat(SESSION).startsWith(FakeSpanner.DATABASE);
  }
}
