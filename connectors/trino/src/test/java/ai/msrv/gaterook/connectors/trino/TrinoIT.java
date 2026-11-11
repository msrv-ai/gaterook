package ai.msrv.gaterook.connectors.trino;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * {@code -Pservices,trino-it}. Every tool as an agent calls it, a write that Trino's own IO plan names refused through
 * the read tool, an approved CREATE TABLE and INSERT into memory, the row and size limits, and a query cancelled at the
 * call's deadline: the coordinator's own record of each query says how it ended.
 */
@Tag("trino-it")
class TrinoIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String TABLE = "memory.default.gaterook_it_orders";
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

  /** A statement sent straight to the coordinator (setup), followed to its end. */
  private static void direct(String statement) throws Exception {
    HttpClient http = HttpClient.newHttpClient();
    HttpResponse<String> r = http.send(HttpRequest.newBuilder(base.resolve("/v1/statement"))
        .header("X-Trino-User", "gaterook_it").POST(HttpRequest.BodyPublishers.ofString(statement)).build(),
        HttpResponse.BodyHandlers.ofString());
    JsonNode page = JSON.readTree(r.body());
    while (page.path("nextUri").isString()) {
      page = JSON.readTree(http.send(HttpRequest.newBuilder(URI.create(page.get("nextUri").asString())).GET().build(),
          HttpResponse.BodyHandlers.ofString()).body());
    }
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
  void aWriteIsRefusedThroughTheReadToolAndTrinosPlanSaysSo() throws Exception {
    Classification route = classify(call("trino_query", sql("INSERT INTO " + TABLE + " VALUES (1, 'x')")));
    assertThat(route.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(route.ruleId()).isEqualTo("dev.trino.query-writes");
    // Trino's own IO plan of the write names its target, which the read check refuses.
    direct("CREATE TABLE IF NOT EXISTS memory.default.gaterook_it_plan (id bigint, name varchar)");
    try {
      TrinoHttp.Call c = new TrinoHttp.Call(cfg, TrinoSettings.of(cfg), NONE, TestCtx.of());
      IoPlan plan = connector.plan(c, "INSERT INTO memory.default.gaterook_it_plan SELECT nationkey, name FROM "
          + "tpch.tiny.nation");
      assertThat(plan.planned()).isTrue();
      assertThat(plan.output().text()).isEqualTo("memory.default.gaterook_it_plan");
      assertThat(plan.inputNames()).containsExactly("tpch.tiny.nation");
      assertThatThrownBy(() -> TrinoConnector.checkRead(plan, c.settings())).isInstanceOfSatisfying(
          ConnectorException.class, e -> assertThat(e.getMessage()).contains("writes to "
              + "memory.default.gaterook_it_plan"));
      // A data definition statement is no query either.
      assertThat(connector.plan(c, "DROP TABLE memory.default.gaterook_it_plan").planned()).isFalse();
    } finally {
      direct("DROP TABLE IF EXISTS memory.default.gaterook_it_plan");
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
    assertThat(prepared.preview().impact()).extracting(ActionPreview.Field::value).contains(TABLE,
        "tpch.tiny.nation");
    ToolResult inserted = connector.execute(insert, cfg, NONE, TestCtx.of().approved(prepared.preview().payload()));
    assertThat(inserted.structuredContent().path("rowsAffected").asInt()).isEqualTo(2);
    assertThat(ended(inserted.structuredContent().path("queryId").asString())).isEqualTo("FINISHED");
    ToolResult back = read("trino_query", sql("SELECT id, name FROM " + TABLE + " ORDER BY id"), TestCtx.of());
    assertThat(back.table().rows().toString()).isEqualTo("[[0,\"ALGERIA\"],[1,\"ARGENTINA\"]]");
    // Without approval nothing runs; what Trino refuses is never put to a person.
    assertThatThrownBy(() -> connector.execute(insert, cfg, NONE, TestCtx.of())).hasMessageContaining("Not approved");
    PreparedAction delete = connector.prepare(call("trino_execute", sql("DELETE FROM " + TABLE + " WHERE id = 0")),
        cfg, NONE, TestCtx.of());
    assertThat(delete.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(delete.message()).contains("does not support");
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
