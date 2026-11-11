package ai.msrv.gaterook.connectors.trino;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Trino connector against Trino 484 of the test services (profile {@code trino}: the built-in memory and tpch
 * catalogs, no authentication on loopback). Start it with {@code scripts/test-services.sh up trino}, then run with
 * {@code -Pservices,trino-it}. Every tool as an agent calls it, a write refused through the read tool and never
 * planned, an approved CREATE TABLE, CREATE TABLE AS and INSERT into memory with nothing written before the approval,
 * the reads Trino plans for DELETE, UPDATE and MERGE, the row and size limits, and a query cancelled at the call's
 * deadline: the coordinator's own record of each query says how it ended.
 */
@Tag("trino-it")
class TrinoIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String TABLE = "memory.default.gaterook_it_orders";
  /** A suffix of this run's own tables: the coordinator's record of queries keeps those of earlier runs a while. */
  private static final String RUN = Long.toHexString(System.nanoTime() & 0xffffffL);
  private static final TestSecrets NONE = new TestSecrets();
  private static TrinoConnector connector;
  private static ConnectionConfig cfg;
  private static URI base;

  @BeforeAll
  static void start() throws Exception {
    assumeTrue(TestServices.enabled(), "Trino runs in the Compose services: scripts/test-services.sh up trino, then "
        + "-Pservices,trino-it");
    base = URI.create("http://127.0.0.1:" + TestServices.port("GW_TRINO_PORT"));
    connector = new TrinoConnector();
    cfg = config();
    direct("DROP TABLE IF EXISTS " + TABLE);
  }

  @AfterAll
  static void stop() throws Exception {
    if (connector != null) {
      direct("DROP TABLE IF EXISTS " + TABLE);
      connector.close();
    }
  }

  private static ConnectionConfig config() {
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("url", base.toString()).put("catalog", "tpch")
        .put("schema", "tiny");
    c.putObject("auth").put("mode", "none").put("user", "gaterook_it");
    c.putArray("catalogs").add("tpch").add("memory");
    return new ConnectionConfig("conn-trino-it", "it-trino", "trino", Env.DEV, 1, c, Set.of(), null);
  }

  private static ToolCall call(String tool, String args) {
    return new ToolCall("tc_it", tool, "it-trino", (ObjectNode) JSON.readTree(args), null);
  }

  private static String sql(String sql) {
    return JSON.writeValueAsString(Map.of("sql", sql));
  }

  private static Classification classify(ToolCall call) {
    return connector.classify(call, cfg, new PolicyView(Env.DEV, "dev.trino", JsonNodeFactory.instance.objectNode(),
        TestCtx.dev()));
  }

  /** A read as an agent calls it: classified, then run. */
  private static ToolResult read(String tool, String args, TestCtx ctx) throws ConnectorException {
    ToolCall call = call(tool, args);
    Classification c = classify(call);
    assertThat(c.kind()).as(c.reason()).isEqualTo(Classification.Kind.READ);
    return connector.execute(call, cfg, NONE, ctx);
  }

  /** A change as the core runs it: classified, prepared, then executed with the approved payload. */
  private static ToolResult approved(String statement) throws ConnectorException {
    ToolCall call = call("trino_execute", sql(statement));
    assertThat(classify(call).kind()).isEqualTo(Classification.Kind.MUTATION);
    PreparedAction prepared = connector.prepare(call, cfg, NONE, TestCtx.of());
    assertThat(prepared.outcome()).as(prepared.message()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    return connector.execute(call, cfg, NONE, TestCtx.of().approved(prepared.preview().payload()));
  }

  /** A statement sent straight to the coordinator (setup and checks), followed to its end: its rows. */
  private static List<JsonNode> direct(String statement) throws Exception {
    HttpClient http = HttpClient.newHttpClient();
    HttpResponse<String> r = http.send(HttpRequest.newBuilder(base.resolve("/v1/statement"))
        .header("X-Trino-User", "gaterook_it").POST(HttpRequest.BodyPublishers.ofString(statement)).build(),
        HttpResponse.BodyHandlers.ofString());
    JsonNode page = JSON.readTree(r.body());
    List<JsonNode> rows = new ArrayList<>();
    page.path("data").forEach(rows::add);
    while (page.path("nextUri").isString()) {
      page = JSON.readTree(http.send(HttpRequest.newBuilder(URI.create(page.get("nextUri").asString())).GET().build(),
          HttpResponse.BodyHandlers.ofString()).body());
      page.path("data").forEach(rows::add);
    }
    if (page.path("error").isObject()) {
      throw new IllegalStateException(statement + ": " + page.path("error").path("message").asString());
    }
    return rows;
  }

  /** Whether {@code memory.default} has the table, as the coordinator's own metadata says. */
  private static boolean exists(String table) throws Exception {
    return !direct("SELECT table_name FROM memory.information_schema.tables WHERE table_schema = 'default' AND "
        + "table_name = '" + table + "'").isEmpty();
  }

  /** How many rows a table has, read straight from the coordinator. */
  private static long count(String table) throws Exception {
    return direct("SELECT count(*) FROM " + table).getFirst().get(0).asLong();
  }

  /** The statements the coordinator received lately that contain {@code text}, by its own record of queries. */
  private static List<String> received(String text) throws Exception {
    List<String> out = new ArrayList<>();
    for (JsonNode row : direct("SELECT query FROM system.runtime.queries WHERE strpos(query, '" + text + "') > 0 "
        + "AND strpos(query, 'system.runtime.queries') = 0")) {
      out.add(row.get(0).asString());
    }
    return out;
  }

  /** How the coordinator says query {@code id} ended: {@code FINISHED}, or {@code FAILED:USER_CANCELED}. */
  private static String ended(String id) throws Exception {
    for (int i = 0; i < 50; i++) {
      HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(base.resolve("/v1/query/" + id))
          .header("X-Trino-User", "gaterook_it").GET().build(), HttpResponse.BodyHandlers.ofString());
      JsonNode q = JSON.readTree(r.body());
      String state = q.path("state").asString("");
      if (state.equals("FINISHED") || state.equals("FAILED")) {
        return state + (q.path("errorCode").isObject() ? ":" + q.path("errorCode").path("name").asString() : "");
      }
      Thread.sleep(100);
    }
    return "RUNNING";
  }

  @Test
  void testConnectionSeesTheCoordinatorTheUserAndTheListedCatalogs() {
    TestResult t = connector.test(cfg, NONE, TestCtx.of());
    assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
    assertThat(t.serverVersion()).isEqualTo("Trino 484");
    assertThat(t.identity()).isEqualTo("gaterook_it");
    assertThat(t.checks().get(2).detail()).contains("2 catalogs", "memory", "tpch");
    assertThat(connector.health(cfg, NONE, TestCtx.of()).state()).isEqualTo(HealthResult.State.HEALTHY);
  }

  @Test
  void readsTpchThroughEveryReadTool() throws Exception {
    ToolResult catalogs = read("trino_list_catalogs", "{}", TestCtx.of());
    assertThat(catalogs.structuredContent().path("catalogs").toString()).isEqualTo("[\"memory\",\"tpch\"]");
    ToolResult schemas = read("trino_list_schemas", "{\"catalog\": \"tpch\"}", TestCtx.of());
    assertThat(schemas.structuredContent().path("schemas").toString()).contains("\"tiny\"", "\"sf1\"");
    ToolResult tables = read("trino_list_tables", "{\"like\": \"n%\"}", TestCtx.of());
    assertThat(tables.structuredContent().path("tables").toString()).contains("nation");
    ToolResult d = read("trino_describe_table", "{\"table\": \"tpch.tiny.nation\"}", TestCtx.of());
    assertThat(d.structuredContent().path("columns").toString()).contains("nationkey", "bigint", "varchar(25)");

    ToolResult q = read("trino_query", sql("SELECT n.nationkey, n.name, r.name AS region, "
        + "CAST(n.nationkey AS decimal(10, 2)) AS amount, TIMESTAMP '2026-01-02 03:04:05.123456 Europe/Berlin' AS at, "
        + "ARRAY[n.regionkey] AS regions, CAST(ROW(n.nationkey, n.name) AS ROW(k bigint, v varchar)) AS pair, "
        + "9223372036854775807 AS big FROM tpch.tiny.nation n JOIN tpch.tiny.region r ON n.regionkey = r.regionkey "
        + "WHERE n.nationkey < 3 ORDER BY n.nationkey;"), TestCtx.of());
    assertThat(q.table().rows()).hasSize(3);
    JsonNode row = q.table().rows().get(1);
    assertThat(row.get(0).asInt()).isEqualTo(1);
    assertThat(row.get(1).asString()).isEqualTo("ARGENTINA");
    assertThat(row.get(2).asString()).isEqualTo("AMERICA");
    assertThat(row.get(3).asString()).isEqualTo("1.00");
    assertThat(row.get(4).asString()).isEqualTo("2026-01-02T03:04:05.123456+01:00");
    assertThat(row.get(5).toString()).isEqualTo("[1]");
    assertThat(row.get(6).path("v").asString()).isEqualTo("ARGENTINA");
    assertThat(row.get(7).asString()).isEqualTo("9223372036854775807");
    assertThat(q.structuredContent().path("tablesRead").toString()).contains("tpch.tiny.nation", "tpch.tiny.region");
    assertThat(read("trino_query", sql("SHOW TABLES FROM tpch.tiny"), TestCtx.of()).table().rows().toString())
        .contains("lineitem");
    assertThat(read("trino_query", sql("DESCRIBE tpch.tiny.region"), TestCtx.of()).table().rows()).hasSize(3);
    assertThat(read("trino_query", sql("EXPLAIN SELECT * FROM tpch.tiny.region"), TestCtx.of()).table().rows())
        .isNotEmpty();
  }

  @Test
  void aWriteIsRefusedThroughTheReadToolAndNeverPlanned() throws Exception {
    Classification route = classify(call("trino_query", sql("INSERT INTO " + TABLE + " VALUES (1, 'x')")));
    assertThat(route.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(route.ruleId()).isEqualTo("dev.trino.query-writes");
    TrinoHttp.Call c = new TrinoHttp.Call(cfg, TrinoSettings.of(cfg), NONE, TestCtx.of());
    String plan = "gaterook_it_plan_" + RUN;
    direct("DROP TABLE IF EXISTS memory.default." + plan);
    try {
      // Planning a CREATE TABLE AS would create its table in memory: GateRook doesn't ask Trino for it.
      assertThatThrownBy(() -> connector.plan(c, "CREATE TABLE memory.default." + plan + " AS SELECT nationkey, name "
          + "FROM tpch.tiny.nation")).isInstanceOfSatisfying(ConnectorException.class,
              e -> assertThat(e.getMessage()).contains("plan reads only"));
      assertThat(exists(plan)).isFalse();
      // A data definition statement is echoed by Trino, not run: the table it drops is still there.
      direct("CREATE TABLE memory.default." + plan + " (id bigint, name varchar)");
      connector.definition(c, TrinoSql.read("DROP TABLE memory.default." + plan));
      assertThat(exists(plan)).isTrue();
      // The plan of a write's read writes nothing and names what the write reads.
      IoPlan io = connector.plan(c, TrinoSql.read("INSERT INTO memory.default." + plan + " SELECT nationkey, name "
          + "FROM tpch.tiny.nation").planned());
      assertThat(io.planned()).isTrue();
      assertThat(io.output()).isNull();
      assertThat(io.inputNames()).containsExactly("tpch.tiny.nation");
      assertThat(count("memory.default." + plan)).isZero();
      // By the coordinator's own record, it was never asked to plan either write.
      assertThat(received(plan)).isNotEmpty().noneMatch(q -> q.startsWith("EXPLAIN (TYPE IO, FORMAT JSON) CREATE "
          + "TABLE memory.default." + plan + " AS") || q.startsWith("EXPLAIN (TYPE IO, FORMAT JSON) INSERT"));
    } finally {
      direct("DROP TABLE IF EXISTS memory.default." + plan);
    }
    // A catalog off the list is refused by the plan, also when the statement doesn't name it.
    ConnectionConfig onlyMemory = new ConnectionConfig("conn-trino-it-memory", "it-trino", "trino", Env.DEV, 1,
        ((ObjectNode) cfg.config().deepCopy()).put("catalog", "memory").put("schema", "default")
            .set("catalogs", JsonNodeFactory.instance.arrayNode().add("memory")), Set.of(), null);
    assertThatThrownBy(() -> connector.execute(call("trino_query", sql("SELECT * FROM tpch.tiny.nation")), onlyMemory,
        NONE, TestCtx.of())).hasMessageContaining("tpch.tiny.nation");
  }

  @Test
  void anApprovedCreateAndInsertRunOnceAndReadBack() throws Exception {
    ToolResult created = approved("CREATE TABLE " + TABLE + " (id bigint, name varchar)");
    assertThat(created.structuredContent().path("updateType").asString()).isEqualTo("CREATE TABLE");
    ToolCall insert = call("trino_execute", sql("INSERT INTO " + TABLE + " SELECT nationkey, name FROM "
        + "tpch.tiny.nation WHERE nationkey < 2"));
    PreparedAction prepared = connector.prepare(insert, cfg, NONE, TestCtx.of());
    assertThat(prepared.outcome()).as(prepared.message()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    assertThat(prepared.preview().impact()).extracting(ActionPreview.Field::label, ActionPreview.Field::value)
        .contains(tuple("Writes", TABLE), tuple("Reads", "tpch.tiny.nation"));
    // Before a person approves, the table has no row.
    assertThat(count(TABLE)).isZero();
    ToolResult inserted = connector.execute(insert, cfg, NONE, TestCtx.of().approved(prepared.preview().payload()));
    assertThat(inserted.structuredContent().path("rowsAffected").asInt()).isEqualTo(2);
    assertThat(ended(inserted.structuredContent().path("queryId").asString())).isEqualTo("FINISHED");
    ToolResult back = read("trino_query", sql("SELECT id, name FROM " + TABLE + " ORDER BY id"), TestCtx.of());
    assertThat(back.table().rows().toString()).isEqualTo("[[0,\"ALGERIA\"],[1,\"ARGENTINA\"]]");
    // Without approval nothing runs.
    assertThatThrownBy(() -> connector.execute(insert, cfg, NONE, TestCtx.of())).hasMessageContaining("Not approved");
    // What only the write itself shows (memory deletes no rows) is Trino's answer to the approved statement: nothing
    // ran, and the rows are as they were.
    ToolCall delete = call("trino_execute", sql("DELETE FROM " + TABLE + " WHERE id = 0"));
    PreparedAction approval = connector.prepare(delete, cfg, NONE, TestCtx.of());
    assertThat(approval.outcome()).as(approval.message()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    assertThatThrownBy(() -> connector.execute(delete, cfg, NONE, TestCtx.of().approved(approval.preview().payload())))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
          assertThat(e.getMessage()).contains("does not support", "Nothing ran");
        });
    assertThat(count(TABLE)).isEqualTo(2);
  }

  @Test
  void anApprovedCreateTableAsCreatesNothingBeforeTheApproval() throws Exception {
    String name = "gaterook_it_ctas_" + RUN;
    String table = "memory.default." + name;
    direct("DROP TABLE IF EXISTS " + table);
    try {
      String statement = "CREATE TABLE " + table + " AS (SELECT nationkey AS id, name FROM tpch.tiny.nation WHERE "
          + "nationkey < 3) WITH DATA";
      ToolCall create = call("trino_execute", sql(statement));
      assertThat(classify(create).kind()).isEqualTo(Classification.Kind.MUTATION);
      PreparedAction prepared = connector.prepare(create, cfg, NONE, TestCtx.of());
      assertThat(prepared.outcome()).as(prepared.message()).isEqualTo(PreparedAction.Outcome.APPROVAL);
      assertThat(prepared.preview().impact()).extracting(ActionPreview.Field::label, ActionPreview.Field::value)
          .contains(tuple("Statement", "CREATE TABLE AS"), tuple("Writes", table), tuple("Reads", "tpch.tiny.nation"));
      // Trino planned the query only: before a person approves, the table doesn't exist.
      assertThat(exists(name)).isFalse();

      ToolResult created = connector.execute(create, cfg, NONE, TestCtx.of().approved(prepared.preview().payload()));
      assertThat(created.structuredContent().path("rowsAffected").asInt()).isEqualTo(3);
      assertThat(ended(created.structuredContent().path("queryId").asString())).isEqualTo("FINISHED");
      // By the coordinator's own record, it ran the approved statement and was never asked to plan it, neither before
      // the approval nor right before the run.
      assertThat(received(name)).contains(statement).noneMatch(q -> q.startsWith("EXPLAIN"));
      ToolResult back = read("trino_query", sql("SELECT id, name FROM " + table + " ORDER BY id"), TestCtx.of());
      assertThat(back.table().rows().toString()).isEqualTo("[[0,\"ALGERIA\"],[1,\"ARGENTINA\"],[2,\"BRAZIL\"]]");
    } finally {
      direct("DROP TABLE IF EXISTS " + table);
    }
  }

  @Test
  void deleteUpdateAndMergeArePlannedByWhatTheyRead() throws Exception {
    String name = "gaterook_it_merge_" + RUN;
    String table = "memory.default." + name;
    direct("DROP TABLE IF EXISTS " + table);
    direct("CREATE TABLE " + table + " AS SELECT nationkey AS id, name FROM tpch.tiny.nation WHERE nationkey < 5");
    try {
      for (String statement : List.of(
          "DELETE FROM " + table + " WHERE id IN (SELECT nationkey FROM tpch.tiny.nation WHERE regionkey = 1)",
          "UPDATE " + table + " SET name = (SELECT max(name) FROM tpch.tiny.nation) WHERE id = 1",
          "MERGE INTO " + table + " t USING (SELECT nationkey, name FROM tpch.tiny.nation) n ON t.id = n.nationkey "
              + "WHEN MATCHED AND n.name <> t.name THEN UPDATE SET name = n.name "
              + "WHEN NOT MATCHED THEN INSERT (id, name) VALUES (n.nationkey, n.name)")) {
        PreparedAction prepared = connector.prepare(call("trino_execute", sql(statement)), cfg, NONE, TestCtx.of());
        assertThat(prepared.outcome()).as(statement + ": " + prepared.message())
            .isEqualTo(PreparedAction.Outcome.APPROVAL);
        assertThat(prepared.preview().impact()).extracting(ActionPreview.Field::label, ActionPreview.Field::value)
            .as(statement).contains(tuple("Writes", table));
        String reads = prepared.preview().impact().stream().filter(f -> f.label().equals("Reads")).findFirst()
            .orElseThrow().value();
        assertThat(reads).as(statement).contains(table, "tpch.tiny.nation");
      }
      // A source table off the connection's list stops the change before a person is asked.
      ConnectionConfig onlyMemory = new ConnectionConfig("conn-trino-it-memory", "it-trino", "trino", Env.DEV, 1,
          ((ObjectNode) cfg.config().deepCopy()).put("catalog", "memory").put("schema", "default")
              .set("catalogs", JsonNodeFactory.instance.arrayNode().add("memory")), Set.of(), null);
      PreparedAction outside = connector.prepare(call("trino_execute", sql("MERGE INTO " + table + " t USING "
          + "tpch.tiny.nation n ON t.id = n.nationkey WHEN MATCHED THEN DELETE")), onlyMemory, NONE, TestCtx.of());
      assertThat(outside.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
      assertThat(outside.message()).contains("tpch.tiny.nation");
      assertThat(count(table)).isEqualTo(5);
      assertThat(received(name)).isNotEmpty().noneMatch(q -> q.startsWith("EXPLAIN (TYPE IO, FORMAT JSON) DELETE")
          || q.startsWith("EXPLAIN (TYPE IO, FORMAT JSON) UPDATE") || q.startsWith("EXPLAIN (TYPE IO, FORMAT JSON) "
          + "MERGE"));
    } finally {
      direct("DROP TABLE IF EXISTS " + table);
    }
  }

  @Test
  void rowAndSizeLimitsStopTheQueryAndCancelIt() throws Exception {
    ToolResult rows = read("trino_query", "{\"sql\": \"SELECT * FROM tpch.sf1.lineitem\", \"limit\": 5}",
        TestCtx.of());
    assertThat(rows.table().rows()).hasSize(5);
    assertThat(rows.truncatedReason()).isEqualTo("row_limit");
    assertThat(rows.content().getFirst().text()).contains("can't resume a query");
    assertThat(ended(rows.structuredContent().path("queryId").asString())).isEqualTo("FAILED:USER_CANCELED");

    Limits small = TestCtx.dev();
    small = new Limits(small.maxRows(), small.maxLines(), 8 * 1024, small.maxRequestBytes(), small.maxOutputBytes(),
        small.maxStderrBytes(), small.maxCellChars(), small.statementTimeout(), small.commandTimeout(),
        Duration.ofSeconds(10), small.maxRange());
    ToolResult sized = read("trino_query", sql("SELECT * FROM tpch.sf1.orders"), TestCtx.of().with(small));
    assertThat(sized.truncatedReason()).isEqualTo("size_limit");
    assertThat(sized.table().rows().size()).isBetween(1, 99);
    assertThat(ended(sized.structuredContent().path("queryId").asString())).isEqualTo("FAILED:USER_CANCELED");
  }

  @Test
  void aQueryThatOutlivesTheCallIsCancelledAtTheDeadline() throws Exception {
    long started = System.nanoTime();
    ConnectorException e = null;
    try {
      read("trino_query", sql("SELECT count(*) FROM tpch.sf100.lineitem WHERE comment LIKE '%gaterook%'"),
          TestCtx.of().timeout(Duration.ofSeconds(4)));
    } catch (ConnectorException thrown) {
      e = thrown;
    }
    assertThat(e).isNotNull();
    assertThat(e.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(6));
    Matcher id = Pattern.compile("query (\\S+?)\\)").matcher(e.getMessage());
    assertThat(id.find()).as(e.getMessage()).isTrue();
    assertThat(ended(id.group(1))).isIn("FAILED:USER_CANCELED", "FAILED:EXCEEDED_TIME_LIMIT");
  }
}
