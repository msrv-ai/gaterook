package ai.msrv.gaterook.connectors.trino;

import static ai.msrv.gaterook.connectors.trino.FakeTrino.column;
import static ai.msrv.gaterook.connectors.trino.FakeTrino.error;
import static ai.msrv.gaterook.connectors.trino.FakeTrino.plan;
import static ai.msrv.gaterook.connectors.trino.FakeTrino.rows;
import static ai.msrv.gaterook.connectors.trino.FakeTrino.text;
import static ai.msrv.gaterook.connectors.trino.FakeTrino.update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
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

/** The Trino connector against a fake coordinator: what is sent, what is refused before it runs, what comes back. */
class TrinoConnectorTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String PASSWORD = "test-only-trino-password";

  private FakeTrino trino;
  private TrinoConnector connector;
  private final TestSecrets secrets = new TestSecrets().with("/auth/password", PASSWORD);

  @BeforeEach
  void start() throws Exception {
    trino = new FakeTrino();
    connector = new TrinoConnector();
  }

  @AfterEach
  void stop() {
    connector.close();
    trino.close();
  }

  private ConnectionConfig config(Env env, String... catalogs) {
    return signingIn(env, "basic", catalogs);
  }

  private ConnectionConfig signingIn(Env env, String mode, String... catalogs) {
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("url", trino.url()).put("catalog", "tpch")
        .put("schema", "tiny");
    c.putObject("auth").put("mode", mode).put("user", "gaterook_reader");
    if (catalogs.length > 0) {
      var list = c.putArray("catalogs");
      for (String s : catalogs) {
        list.add(s);
      }
    }
    return new ConnectionConfig("conn-trino-" + env.id(), env.id() + "-trino", "trino", env, 1, c,
        Set.of("/auth/password"), null);
  }

  private static PolicyView policy(Env env) {
    return new PolicyView(env, env.id() + ".trino", JsonNodeFactory.instance.objectNode(),
        env == Env.PROD ? TestCtx.prod() : TestCtx.dev());
  }

  private static ToolCall call(String tool, String args) {
    return new ToolCall("tc_" + Long.toHexString(System.nanoTime()), tool, "dev-trino",
        (ObjectNode) JSON.readTree(args), null);
  }

  private static String sql(String sql) {
    return JSON.writeValueAsString(Map.of("sql", sql));
  }

  private Classification classify(String tool, String args, String... catalogs) {
    return connector.classify(call(tool, args), config(Env.DEV, catalogs), policy(Env.DEV));
  }

  private static String nationColumns() {
    return "[" + column("nationkey", "bigint") + "," + column("name", "varchar") + "]";
  }

  // ── Tools and routes ────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void publishesReadsAndOneApprovedChange() {
    assertThat(connector.manifest().id()).isEqualTo("trino");
    assertThat(connector.manifest().name()).isEqualTo("Trino / Presto");
    assertThat(connector.manifest().group()).isEqualTo("Databases");
    assertThat(connector.manifest().icon()).isEqualTo("database");
    assertThat(connector.manifest().tools()).extracting(ToolDefinition::name).containsExactly("trino_list_catalogs",
        "trino_list_schemas", "trino_list_tables", "trino_describe_table", "trino_query", "trino_execute");
    ToolDefinition execute = connector.manifest().tools().getLast();
    assertThat(execute.mutating()).isTrue();
    assertThat(execute.readOnlyModeAllowed()).isFalse();
    assertThat(execute.defaultMode()).isEqualTo(ToolDefinition.Mode.APPROVAL);
    assertThat(execute.prodDefaultMode()).isEqualTo(ToolDefinition.Mode.APPROVAL);
    assertThat(execute.inputSchema().at("/properties/note/maxLength").asInt()).isEqualTo(500);
    assertThat(execute.inputSchema().at("/properties/reason").isMissingNode()).isTrue();
    assertThat(connector.manifest().tools().get(4).defaultMode()).isEqualTo(ToolDefinition.Mode.READ_ONLY);
  }

  @Test
  void statementsAreRoutedByWhatTheyDo() {
    assertThat(classify("trino_query", sql("SELECT * FROM tpch.tiny.nation WHERE name = 'DELETE; x'")).kind())
        .isEqualTo(Classification.Kind.READ);
    Classification writes = classify("trino_query", sql("DELETE FROM memory.default.t WHERE true"));
    assertThat(writes.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(writes.ruleId()).isEqualTo("dev.trino.query-writes");
    Classification change = classify("trino_execute", sql("INSERT INTO memory.default.t VALUES (1)"));
    assertThat(change.kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(change.facts().path("target").asString()).isEqualTo("memory.default.t");
    assertThat(change.facts().path("statement").asString()).isEqualTo("INSERT");
    assertThat(classify("trino_execute", sql("SELECT 1")).ruleId()).isEqualTo("dev.trino.execute-read");
    assertThat(classify("trino_query", sql("SET SESSION query_max_run_time = '1h'")).ruleId())
        .isEqualTo("dev.trino.forbidden");
    assertThat(classify("trino_query", sql("SELECT 'open")).ruleId()).isEqualTo("dev.trino.unparsed");
    assertThat(classify("trino_query", sql("SELECT * FROM TABLE(pg.system.query(query => 'DELETE FROM x'))"))
        .ruleId()).isEqualTo("dev.trino.forbidden");
  }

  @Test
  void onlyListedCatalogsPass() {
    String[] allowed = {"tpch", "memory"};
    assertThat(classify("trino_query", sql("SHOW TABLES FROM tpch.tiny"), allowed).kind())
        .isEqualTo(Classification.Kind.READ);
    // Names the plan can't show: the gateway reads them itself.
    for (String text : List.of("SHOW CREATE TABLE hive.s.t", "SHOW STATS FOR hive.s.t", "DESCRIBE hive.s.t",
        "SHOW SCHEMAS FROM hive", "SELECT hive.lib.f(1)", "SHOW CATALOGS")) {
      Classification c = classify("trino_query", sql(text), allowed);
      assertThat(c.kind()).as(text).isEqualTo(Classification.Kind.DENY);
      assertThat(c.ruleId()).as(text).isEqualTo("dev.trino.catalog-not-allowed");
    }
    // Without a list, every catalog the user may read.
    assertThat(classify("trino_query", sql("SHOW CREATE TABLE hive.s.t")).kind()).isEqualTo(Classification.Kind.READ);
    assertThat(classify("trino_execute", sql("DROP TABLE hive.s.t"), allowed).kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThat(classify("trino_execute", sql("DROP TABLE memory.default.t"), allowed).kind())
        .isEqualTo(Classification.Kind.MUTATION);
    // Unqualified names are in the default catalog, which must be on the list.
    assertThat(classify("trino_execute", sql("DROP TABLE t"), allowed).kind())
        .isEqualTo(Classification.Kind.MUTATION);
    assertThat(classify("trino_list_schemas", "{\"catalog\": \"hive\"}", allowed).kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThat(classify("trino_describe_table", "{\"table\": \"tpch.tiny.nation\"}", allowed).kind())
        .isEqualTo(Classification.Kind.READ);
    assertThat(classify("trino_describe_table", "{\"table\": \"hive.s.t\"}", allowed).kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThat(classify("trino_describe_table", "{\"table\": \"a; DROP TABLE b\"}", allowed).ruleId())
        .isEqualTo("dev.trino.bad-argument");
  }

  @Test
  void settingsKeepCredentialsOffPlainHttpAndUnauthenticatedCoordinatorsOnDev() {
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("url", "http://trino.example.com:8080");
    c.putObject("auth").put("mode", "basic").put("user", "gaterook_reader");
    ConnectionConfig plain = new ConnectionConfig("x", "x", "trino", Env.DEV, 1, c, Set.of(), null);
    assertThatThrownBy(() -> TrinoSettings.of(plain)).hasMessageContaining("https://");
    ((ObjectNode) c.get("auth")).put("mode", "none");
    assertThat(connector.classify(call("trino_list_catalogs", "{}"), plain, policy(Env.DEV)).kind())
        .isEqualTo(Classification.Kind.READ);
    ConnectionConfig prodPlain = new ConnectionConfig("x", "x", "trino", Env.PROD, 1, c, Set.of(), null);
    assertThat(connector.classify(call("trino_list_catalogs", "{}"), prodPlain, policy(Env.PROD)).ruleId())
        .isEqualTo("prod.trino.bad-config");
    // A coordinator on this computer may go without TLS and without authentication.
    assertThat(connector.classify(call("trino_list_catalogs", "{}"), signingIn(Env.PROD, "none"), policy(Env.PROD))
        .kind()).isEqualTo(Classification.Kind.READ);
    c.put("url", "https://trino.example.com:8443/ui");
    assertThatThrownBy(() -> TrinoSettings.of(plain)).hasMessageContaining("address only");
    c.put("url", "http://169.254.169.254:8080");
    assertThatThrownBy(() -> TrinoSettings.of(plain)).hasMessageContaining("metadata");
    c.put("url", "https://trino.example.com:8443").put("catalog", "hive").putArray("catalogs").add("tpch");
    assertThatThrownBy(() -> TrinoSettings.of(plain)).hasMessageContaining("default catalog hive");
    // The user travels as a header value: printable ASCII only.
    c.remove("catalogs");
    ((ObjectNode) c.get("auth")).put("user", "lecteuré\r\nX-Trino-Role: admin");
    assertThatThrownBy(() -> TrinoSettings.of(plain)).hasMessageContaining("printable ASCII");
  }

  // ── What is sent and what comes back ───────────────────────────────────────────────────────────────────────

  @Test
  void aReadIsPlannedByTrinoThenRunsWithTheSessionAndCredentials() throws Exception {
    trino.on("EXPLAIN (TYPE IO, FORMAT JSON) SELECT", plan(List.of("tpch.tiny.nation"), null))
        .on("SELECT", rows(nationColumns()), rows(nationColumns(), "[0, \"ALGERIA\"]", "[1, \"ARGENTINA\"]"));
    TestCtx ctx = TestCtx.of();
    ToolResult r = connector.execute(call("trino_query", sql("SELECT nationkey, name FROM nation;")),
        config(Env.DEV, "tpch"), secrets, ctx);

    assertThat(trino.statements).containsExactly("EXPLAIN (TYPE IO, FORMAT JSON) SELECT nationkey, name FROM nation",
        "SELECT nationkey, name FROM nation");
    RecordedRequest sent = trino.to("POST /v1/statement").getLast();
    assertThat(sent.getHeaders().get("X-Trino-User")).isEqualTo("gaterook_reader");
    assertThat(sent.getHeaders().get("X-Trino-Source")).isEqualTo("gaterook");
    assertThat(sent.getHeaders().get("X-Trino-Catalog")).isEqualTo("tpch");
    assertThat(sent.getHeaders().get("X-Trino-Schema")).isEqualTo("tiny");
    assertThat(sent.getHeaders().get("X-Trino-Session")).contains("query_max_run_time=", "query_max_execution_time=");
    assertThat(sent.getHeaders().get("X-Trino-Client-Capabilities")).isEqualTo("PARAMETRIC_DATETIME");
    assertThat(sent.getHeaders().get("X-Trino-Client-Tags")).isEqualTo("gaterook,read");
    String basic = "Basic " + Base64.getEncoder().encodeToString(("gaterook_reader:" + PASSWORD)
        .getBytes(StandardCharsets.UTF_8));
    assertThat(sent.getHeaders().get("Authorization")).isEqualTo(basic);
    assertThat(ctx.taints).contains(PASSWORD, basic.substring(6));
    // The polls carry the same headers to the same coordinator.
    assertThat(trino.to("GET /v1/statement/executing/")).isNotEmpty()
        .allSatisfy(g -> assertThat(g.getHeaders().get("Authorization")).isEqualTo(basic));

    assertThat(r.table().columns()).extracting(ToolResult.Column::name).containsExactly("nationkey", "name");
    assertThat(r.table().rows()).hasSize(2);
    assertThat(r.table().rows().get(1).get(1).asString()).isEqualTo("ARGENTINA");
    assertThat(r.truncated()).isFalse();
    assertThat(r.structuredContent().path("tablesRead").get(0).asString()).isEqualTo("tpch.tiny.nation");
    assertThat(trino.cancels).isEmpty();
  }

  @Test
  void trinosPlanDecidesWhatRuns() {
    // A statement the gateway reads as a query that Trino plans as a write never runs.
    trino.on("EXPLAIN (TYPE IO, FORMAT JSON) SELECT", plan(List.of("tpch.tiny.nation"), "memory.default.t"));
    assertThatThrownBy(() -> connector.execute(call("trino_query", sql("SELECT * FROM nation")), config(Env.DEV),
        secrets, TestCtx.of())).isInstanceOf(ConnectorException.class).hasMessageContaining("writes to "
        + "memory.default.t");
    // A table of another catalog, read through a view.
    trino.on("EXPLAIN (TYPE IO, FORMAT JSON) SELECT", plan(List.of("tpch.tiny.v", "hive.hr.salaries"), null));
    assertThatThrownBy(() -> connector.execute(call("trino_query", sql("SELECT * FROM v")), config(Env.DEV, "tpch"),
        secrets, TestCtx.of())).hasMessageContaining("hive.hr.salaries");
    // A plan GateRook can't read, and a data definition statement's echo.
    trino.on("EXPLAIN (TYPE IO, FORMAT JSON) SELECT", text("{\"unexpected\": true}"));
    assertThatThrownBy(() -> connector.execute(call("trino_query", sql("SELECT 1")), config(Env.DEV), secrets,
        TestCtx.of())).hasMessageContaining("can't be read");
    trino.on("EXPLAIN (TYPE IO, FORMAT JSON) SELECT", text("CREATE TABLE x"));
    assertThatThrownBy(() -> connector.execute(call("trino_query", sql("SELECT 1")), config(Env.DEV), secrets,
        TestCtx.of())).hasMessageContaining("data definition");
    assertThat(trino.statements).allMatch(s -> s.startsWith("EXPLAIN (TYPE IO, FORMAT JSON) "));
  }

  @Test
  void valuesKeepTheirTypes() throws Exception {
    String columns = "[" + column("big", "bigint") + "," + column("small", "integer") + ",{\"name\":\"amount\","
        + "\"type\":\"decimal(20, 2)\",\"typeSignature\":{\"rawType\":\"decimal\",\"arguments\":[{\"kind\":\"LONG\","
        + "\"value\":20},{\"kind\":\"LONG\",\"value\":2}]}}," + column("ratio", "double") + ","
        + column("raw", "varbinary") + "," + column("at", "timestamp") + ","
        + "{\"name\":\"zoned\",\"type\":\"timestamp(6) with time zone\",\"typeSignature\":{\"rawType\":\"timestamp with "
        + "time zone\",\"arguments\":[{\"kind\":\"LONG\",\"value\":6}]}},"
        + "{\"name\":\"tags\",\"type\":\"array(bigint)\",\"typeSignature\":{\"rawType\":\"array\",\"arguments\":[{"
        + "\"kind\":\"TYPE\",\"value\":{\"rawType\":\"bigint\",\"arguments\":[]}}]}},"
        + "{\"name\":\"attrs\",\"type\":\"map(varchar, bigint)\",\"typeSignature\":{\"rawType\":\"map\",\"arguments\":["
        + "{\"kind\":\"TYPE\",\"value\":{\"rawType\":\"varchar\",\"arguments\":[]}},{\"kind\":\"TYPE\",\"value\":{"
        + "\"rawType\":\"bigint\",\"arguments\":[]}}]}},"
        + "{\"name\":\"buyer\",\"type\":\"row(name varchar, score double)\",\"typeSignature\":{\"rawType\":\"row\","
        + "\"arguments\":[{\"kind\":\"NAMED_TYPE\",\"value\":{\"fieldName\":{\"name\":\"name\"},\"typeSignature\":{"
        + "\"rawType\":\"varchar\",\"arguments\":[]}}},{\"kind\":\"NAMED_TYPE\",\"value\":{\"fieldName\":{\"name\":"
        + "\"score\"},\"typeSignature\":{\"rawType\":\"double\",\"arguments\":[]}}}]}},"
        + column("note", "varchar") + "," + column("flag", "boolean") + "," + column("missing", "bigint") + "]";
    trino.on("EXPLAIN (TYPE IO, FORMAT JSON) SELECT", plan(List.of(), null))
        .on("SELECT", rows(columns, "[123456789012345678901, 7, \"12.50\", \"NaN\", \"AP8Q\", \"2026-01-02 "
            + "03:04:05.123456789012\", \"2026-01-02 03:04:05.123456 Europe/Berlin\", [1, 2], {\"a\": "
            + "9007199254740993}, [\"Ada\", 0.5], \"" + "x".repeat(10_000) + "\", true, null]"));
    ToolResult r = connector.execute(call("trino_query", sql("SELECT 1")), config(Env.DEV), secrets, TestCtx.of());
    JsonNode row = r.table().rows().get(0);
    assertThat(row.get(0).asString()).isEqualTo("123456789012345678901");
    assertThat(row.get(1).isIntegralNumber()).isTrue();
    assertThat(row.get(2).asString()).isEqualTo("12.50");
    assertThat(row.get(3).asString()).isEqualTo("NaN");
    assertThat(row.get(4).asString()).isEqualTo("\\x00ff10");
    assertThat(row.get(5).asString()).isEqualTo("2026-01-02T03:04:05.123456789012");
    assertThat(row.get(6).asString()).isEqualTo("2026-01-02T03:04:05.123456+01:00");
    assertThat(row.get(7).toString()).isEqualTo("[1,2]");
    assertThat(row.get(8).path("a").asString()).isEqualTo("9007199254740993");
    assertThat(row.get(9).path("name").asString()).isEqualTo("Ada");
    assertThat(row.get(9).path("score").asDouble()).isEqualTo(0.5);
    assertThat(row.get(10).asString()).startsWith("x".repeat(100)).contains("…‹10000 characters›");
    assertThat(row.get(11).asBoolean()).isTrue();
    assertThat(row.get(12).isNull()).isTrue();
    assertThat(r.table().columns().get(6).type()).isEqualTo("timestamp(6) with time zone");
    assertThat(Cells.zoned("2026-01-02 03:04:05 UTC")).isEqualTo("2026-01-02T03:04:05Z");
    assertThat(Cells.zoned("2026-07-02 03:04:05.1 +02:00")).isEqualTo("2026-07-02T03:04:05.1+02:00");
  }

  @Test
  void aQueryBeyondTheRowLimitIsCancelledAndSaysHowToSeeMore() throws Exception {
    trino.on("EXPLAIN (TYPE IO, FORMAT JSON) SELECT", plan(List.of("tpch.tiny.nation"), null))
        .endless("SELECT", rows(nationColumns(), "[0, \"A\"]", "[1, \"B\"]", "[2, \"C\"]"));
    ToolResult r = connector.execute(call("trino_query", "{\"sql\": \"SELECT * FROM nation\", \"limit\": 2}"),
        config(Env.DEV), secrets, TestCtx.of());
    assertThat(r.table().rows()).hasSize(2);
    assertThat(r.truncated()).isTrue();
    assertThat(r.truncatedReason()).isEqualTo("row_limit");
    assertThat(r.table().nextCursor()).isNull();
    assertThat(r.content().getFirst().text()).contains("can't resume a query", "narrow it");
    assertThat(trino.cancels).hasSize(1);
  }

  @Test
  void aQueryThatOutlivesTheCallIsCancelled() {
    trino.on("EXPLAIN (TYPE IO, FORMAT JSON) SELECT", plan(List.of(), null))
        .endless("SELECT", rows(nationColumns()));
    assertThatThrownBy(() -> connector.execute(call("trino_query", sql("SELECT * FROM nation")), config(Env.DEV),
        secrets, TestCtx.of().timeout(Duration.ofMillis(2_500))))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT);
          assertThat(e.getMessage()).contains("cancelled");
        });
    assertThat(trino.cancels).hasSize(1);
    // With rows already read, the call answers with them and says it was cut.
    trino.endless("SELECT", rows(nationColumns(), "[0, \"A\"]"));
    trino.cancels.clear();
    ToolResult partial = assertDoesNotThrow(() -> connector.execute(call("trino_query", sql("SELECT * FROM nation")),
        config(Env.DEV), secrets, TestCtx.of().timeout(Duration.ofMillis(2_500))));
    assertThat(partial.truncatedReason()).isEqualTo("timeout");
    assertThat(partial.table().rows()).hasSize(1);
    assertThat(trino.cancels).hasSize(1);
  }

  private static ToolResult assertDoesNotThrow(ThrowingSupplier s) {
    try {
      return s.get();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  @FunctionalInterface
  private interface ThrowingSupplier {
    ToolResult get() throws Exception;
  }

  @Test
  void theRestOfAQueryStaysOnTheCoordinator() {
    // A nextUri on another host gets nothing: no poll, no cancel, no credentials.
    trino.nextBase = "http://localhost:" + trino.port();
    trino.on("EXPLAIN (TYPE IO, FORMAT JSON) SELECT", plan(List.of(), null), rows("[]"));
    assertThatThrownBy(() -> connector.execute(call("trino_query", sql("SELECT 1")), config(Env.DEV), secrets,
        TestCtx.of())).isInstanceOf(ConnectorException.class).hasMessageContaining("own URL");
    assertThat(trino.to("GET")).isEmpty();
    assertThat(trino.to("DELETE")).isEmpty();
  }

  @Test
  void trinosErrorsSayWhatToDoWithoutItsStack() {
    trino.on("EXPLAIN (TYPE IO, FORMAT JSON) SELECT", error("TABLE_NOT_FOUND", "USER_ERROR", "line 1:15: Table "
        + "'tpch.tiny.nope' does not exist"));
    assertThatThrownBy(() -> connector.execute(call("trino_query", sql("SELECT * FROM nope")), config(Env.DEV), secrets,
        TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
          assertThat(e.getMessage()).contains("does not exist").doesNotContain("io.trino.secret");
        });
    trino.on("EXPLAIN (TYPE IO, FORMAT JSON) SELECT", error("PERMISSION_DENIED", "USER_ERROR", "Access Denied: "
        + "Cannot select from table hr.salaries"));
    assertThatThrownBy(() -> connector.execute(call("trino_query", sql("SELECT * FROM hr")), config(Env.DEV), secrets,
        TestCtx.of())).hasMessageContaining("access control denied gaterook_reader");
  }

  @Test
  void aChangeRunsOnlyApprovedAndWhatRunsIsTheApprovedText() throws Exception {
    trino.on("EXPLAIN (TYPE IO, FORMAT JSON) SELECT", plan(List.of("tpch.tiny.nation"), null))
        .on("INSERT", update("INSERT", 25L));
    String text = "INSERT INTO memory.default.t SELECT * FROM tpch.tiny.nation";
    ToolCall c = call("trino_execute", sql(text + ";"));
    assertThatThrownBy(() -> connector.execute(c, config(Env.DEV), secrets, TestCtx.of()))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("Not approved");
    assertThat(trino.statements).isEmpty();

    ActionPreview preview = connector.describeAction(c, config(Env.DEV));
    assertThat(preview.kind()).isEqualTo(ActionPreview.Kind.SQL);
    assertThat(preview.text()).isEqualTo(text);
    assertThat(preview.payload().path("sql").asString()).isEqualTo(text);
    TestCtx approved = TestCtx.of().approved(preview.payload());
    ToolResult r = connector.execute(c, config(Env.DEV), secrets, approved);
    assertThat(r.structuredContent().path("rowsAffected").asInt()).isEqualTo(25);
    assertThat(r.content().getFirst().text()).isEqualTo("25 rows written by INSERT on memory.default.t.");
    // Right before it runs, Trino plans the query it inserts again; the INSERT itself is never planned.
    assertThat(trino.statements).containsExactly("EXPLAIN (TYPE IO, FORMAT JSON) SELECT * FROM tpch.tiny.nation",
        text);
    assertThat(trino.to("POST /v1/statement").getLast().getHeaders().get("X-Trino-Client-Tags"))
        .isEqualTo("gaterook,change");

    // The call's own arguments say something else: what runs is what the person approved.
    trino.statements.clear();
    connector.execute(call("trino_execute", sql("DROP TABLE memory.default.t")), config(Env.DEV), secrets, approved);
    assertThat(trino.statements.getLast()).isEqualTo(text);
  }

  @Test
  void aLostOrUnfinishedChangeIsAnUnknownOutcome() {
    String text = "DELETE FROM memory.default.t WHERE id = 1";
    trino.on("EXPLAIN (TYPE IO, FORMAT JSON) SELECT 1 FROM memory.default.t WHERE id = 1",
            plan(List.of("memory.default.t"), null))
        .on("DELETE", r -> new FakeTrino.Script(List.of(update(null, null)), true));
    TestCtx approved = TestCtx.of().timeout(Duration.ofMillis(2_500)).approved(JsonNodeFactory.instance.objectNode()
        .put("sql", text));
    assertThatThrownBy(() -> connector.execute(call("trino_execute", "{}"), config(Env.DEV), secrets, approved))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN));
    assertThat(trino.cancels).hasSize(1);
    // Trino's own failure of a change is not a lost answer: it says what Trino said and what to check.
    trino.on("DELETE", error("NOT_SUPPORTED", "USER_ERROR", "This connector does not support modifying table rows"));
    assertThatThrownBy(() -> connector.execute(call("trino_execute", "{}"), config(Env.DEV), secrets,
        TestCtx.of().approved(JsonNodeFactory.instance.objectNode().put("sql", text))))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
          assertThat(e.getMessage()).contains("does not support modifying");
        });
  }

  @Test
  void theApprovalShowsTrinosPlanAndNeverAsksForWhatCantRun() throws Exception {
    trino.on("EXPLAIN (TYPE IO, FORMAT JSON) SELECT", plan(List.of("tpch.tiny.nation"), null));
    PreparedAction prepared = connector.prepare(call("trino_execute", sql("INSERT INTO memory.default.t SELECT * "
        + "FROM tpch.tiny.nation")), config(Env.DEV, "tpch", "memory"), secrets, TestCtx.of());
    assertThat(prepared.outcome()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    assertThat(prepared.preview().impact()).extracting(ActionPreview.Field::label).startsWith("Statement", "Writes",
        "Reads");
    assertThat(prepared.preview().impact().get(1).value()).isEqualTo("memory.default.t");
    assertThat(prepared.preview().impact().get(2).value()).isEqualTo("tpch.tiny.nation");
    // The table written is the statement's own, in the connection's default catalog and schema.
    PreparedAction unqualified = connector.prepare(call("trino_execute", sql("INSERT INTO \"Nation_Copy\" SELECT * "
        + "FROM nation")), config(Env.DEV), secrets, TestCtx.of());
    assertThat(unqualified.preview().impact().get(1).value()).isEqualTo("tpch.tiny.nation_copy");
    // A query that reads no table says so.
    trino.on("EXPLAIN (TYPE IO, FORMAT JSON) VALUES", plan(List.of(), null));
    PreparedAction values = connector.prepare(call("trino_execute", sql("INSERT INTO memory.default.t VALUES (1)")),
        config(Env.DEV), secrets, TestCtx.of());
    assertThat(values.preview().impact().get(2).value()).isEqualTo("No table, by Trino's plan");

    // A data definition statement isn't planned: the approval says Trino checks it when it runs it.
    trino.on("EXPLAIN (TYPE IO, FORMAT JSON) DROP", text("DROP TABLE memory.default.t"));
    PreparedAction drop = connector.prepare(call("trino_execute", sql("DROP TABLE memory.default.t")),
        config(Env.DEV), secrets, TestCtx.of());
    assertThat(drop.preview().impact()).extracting(ActionPreview.Field::label).contains("Trino's plan");
    // One that Trino plans after all, as a write, isn't put to a person.
    trino.on("EXPLAIN (TYPE IO, FORMAT JSON) DROP", plan(List.of(), "memory.default.t"));
    PreparedAction planned = connector.prepare(call("trino_execute", sql("DROP TABLE memory.default.t")),
        config(Env.DEV), secrets, TestCtx.of());
    assertThat(planned.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(planned.message()).contains("as a write");

    // What Trino refuses of the read, and what reads another catalog, never reaches a person.
    trino.on("EXPLAIN (TYPE IO, FORMAT JSON) SELECT 1 FROM memory.default.t", error("TABLE_NOT_FOUND", "USER_ERROR",
        "line 1:15: Table 'memory.default.t' does not exist"));
    PreparedAction refused = connector.prepare(call("trino_execute", sql("DELETE FROM memory.default.t")),
        config(Env.DEV), secrets, TestCtx.of());
    assertThat(refused.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(refused.message()).contains("does not exist");
    trino.on("EXPLAIN (TYPE IO, FORMAT JSON) SELECT", plan(List.of("hive.hr.salaries"), null));
    PreparedAction outside = connector.prepare(call("trino_execute", sql("INSERT INTO memory.default.t SELECT * "
        + "FROM hr.salaries")), config(Env.DEV, "tpch", "memory"), secrets, TestCtx.of());
    assertThat(outside.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(outside.message()).contains("hive.hr.salaries");
    // A plan of the read that writes a table is no plan of a read.
    trino.on("EXPLAIN (TYPE IO, FORMAT JSON) SELECT", plan(List.of("tpch.tiny.nation"), "memory.default.t"));
    PreparedAction writing = connector.prepare(call("trino_execute", sql("INSERT INTO memory.default.t SELECT * "
        + "FROM tpch.tiny.nation")), config(Env.DEV), secrets, TestCtx.of());
    assertThat(writing.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    // A target outside the connection's catalogs is refused before Trino is asked anything.
    trino.statements.clear();
    PreparedAction elsewhere = connector.prepare(call("trino_execute", sql("INSERT INTO hive.s.t SELECT 1")),
        config(Env.DEV, "tpch", "memory"), secrets, TestCtx.of());
    assertThat(elsewhere.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(elsewhere.message()).contains("hive");
    assertThat(trino.statements).isEmpty();
  }

  @Test
  void aChangeIsPlannedByAReadOfWhatItReadsNeverByItsWrite() throws Exception {
    trino.on("EXPLAIN (TYPE IO, FORMAT JSON) ", plan(List.of("memory.default.t", "tpch.tiny.nation"), null));
    for (String prefix : List.of("CREATE", "INSERT", "DELETE", "UPDATE", "MERGE", "ANALYZE", "REFRESH", "ALTER")) {
      trino.on(prefix, update(prefix, 1L));
    }
    Map<String, String> reads = new LinkedHashMap<>();
    reads.put("CREATE TABLE memory.default.t2 AS SELECT * FROM tpch.tiny.nation WITH DATA",
        "SELECT * FROM tpch.tiny.nation");
    reads.put("CREATE TABLE memory.default.t3 AS (SELECT nationkey FROM tpch.tiny.nation) WITH NO DATA",
        "SELECT nationkey FROM tpch.tiny.nation");
    reads.put("INSERT INTO memory.default.t (id) VALUES (1)", "VALUES (1)");
    reads.put("INSERT INTO memory.default.t SELECT nationkey FROM tpch.tiny.nation",
        "SELECT nationkey FROM tpch.tiny.nation");
    reads.put("DELETE FROM memory.default.t WHERE id = 1", "SELECT 1 FROM memory.default.t WHERE id = 1");
    reads.put("UPDATE memory.default.t SET name = 'x' WHERE id = 1", "SELECT 'x' FROM memory.default.t WHERE id = 1");
    reads.put("MERGE INTO memory.default.t t USING tpch.tiny.nation n ON t.id = n.nationkey WHEN NOT MATCHED THEN "
        + "INSERT (id) VALUES (n.nationkey)", "SELECT n.nationkey FROM memory.default.t t FULL JOIN tpch.tiny.nation n "
        + "ON t.id = n.nationkey");
    reads.put("ANALYZE memory.default.t", "SELECT 1 FROM memory.default.t");
    reads.put("REFRESH MATERIALIZED VIEW memory.default.mv", "SELECT 1 FROM memory.default.mv");
    reads.put("ALTER TABLE memory.default.t EXECUTE optimize", "SELECT 1 FROM memory.default.t");
    reads.put("ALTER MATERIALIZED VIEW memory.default.mv EXECUTE optimize", "SELECT 1 FROM memory.default.mv");
    for (Map.Entry<String, String> e : reads.entrySet()) {
      trino.statements.clear();
      ToolCall c = call("trino_execute", sql(e.getKey()));
      assertThat(connector.classify(c, config(Env.DEV, "tpch", "memory"), policy(Env.DEV)).kind()).as(e.getKey())
          .isEqualTo(Classification.Kind.MUTATION);
      PreparedAction prepared = connector.prepare(c, config(Env.DEV, "tpch", "memory"), secrets, TestCtx.of());
      assertThat(prepared.outcome()).as(e.getKey()).isEqualTo(PreparedAction.Outcome.APPROVAL);
      assertThat(prepared.preview().impact()).extracting(ActionPreview.Field::value).as(e.getKey())
          .contains("memory.default.t, tpch.tiny.nation");
      connector.execute(c, config(Env.DEV, "tpch", "memory"), secrets,
          TestCtx.of().approved(prepared.preview().payload()));
      // Before the approval and right before the run, Trino plans the read; then the approved statement runs.
      assertThat(trino.statements).as(e.getKey()).containsExactly("EXPLAIN (TYPE IO, FORMAT JSON) " + e.getValue(),
          "EXPLAIN (TYPE IO, FORMAT JSON) " + e.getValue(), e.getKey());
    }
    assertThat(trino.to("POST /v1/statement")).extracting(r -> r.getBody().utf8())
        .noneMatch(s -> s.matches("(?s)EXPLAIN \\(TYPE IO, FORMAT JSON\\) \\(*\\s*(CREATE TABLE \\S+ AS|INSERT|DELETE"
            + "|UPDATE|MERGE|ANALYZE|REFRESH|ALTER \\S+( VIEW)? \\S+ EXECUTE).*"));
  }

  @Test
  void aReadOutsideTheCatalogsStopsTheChangeAlsoRightBeforeItRuns() throws Exception {
    trino.on("EXPLAIN (TYPE IO, FORMAT JSON) SELECT", plan(List.of("tpch.tiny.nation"), null))
        .on("INSERT", update("INSERT", 25L));
    String text = "INSERT INTO memory.default.t SELECT * FROM tpch.tiny.nation";
    PreparedAction prepared = connector.prepare(call("trino_execute", sql(text)), config(Env.DEV, "tpch", "memory"),
        secrets, TestCtx.of());
    assertThat(prepared.outcome()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    // By the time it runs, what the query reads is outside the connection's catalogs: nothing runs.
    trino.on("EXPLAIN (TYPE IO, FORMAT JSON) SELECT", plan(List.of("tpch.tiny.nation", "hive.hr.salaries"), null));
    trino.statements.clear();
    assertThatThrownBy(() -> connector.execute(call("trino_execute", "{}"), config(Env.DEV, "tpch", "memory"), secrets,
        TestCtx.of().approved(prepared.preview().payload())))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).contains("hive.hr.salaries");
        });
    assertThat(trino.statements).containsExactly("EXPLAIN (TYPE IO, FORMAT JSON) SELECT * FROM tpch.tiny.nation");
  }

  @Test
  void onlyReadsAndDataDefinitionStatementsAreEverExplained() throws Exception {
    TrinoHttp.Call c = new TrinoHttp.Call(config(Env.DEV), TrinoSettings.of(config(Env.DEV)), secrets, TestCtx.of());
    for (String write : List.of("INSERT INTO memory.default.t SELECT 1", "CREATE TABLE memory.default.t AS SELECT 1",
        "DELETE FROM memory.default.t", "MERGE INTO t USING u ON t.id = u.id WHEN MATCHED THEN DELETE")) {
      assertThatThrownBy(() -> connector.plan(c, write)).as(write).isInstanceOfSatisfying(ConnectorException.class,
          e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
      assertThatThrownBy(() -> connector.definition(c, TrinoSql.read(write))).as(write)
          .isInstanceOf(ConnectorException.class);
    }
    assertThat(trino.statements).isEmpty();
  }

  @Test
  void listsAndDescribesWithinTheList() throws Exception {
    trino.on("SHOW CATALOGS", rows("[" + column("Catalog", "varchar") + "]", "[\"hive\"]", "[\"memory\"]",
            "[\"system\"]", "[\"tpch\"]"))
        .on("SHOW SCHEMAS FROM \"tpch\"", rows("[" + column("Schema", "varchar") + "]", "[\"sf1\"]", "[\"tiny\"]"))
        .on("SELECT table_name, table_type FROM \"tpch\".information_schema.tables WHERE table_schema = 'tiny'",
            rows("[" + column("table_name", "varchar") + "," + column("table_type", "varchar") + "]",
                "[\"nation\", \"BASE TABLE\"]", "[\"v_nation\", \"VIEW\"]"))
        .on("SELECT table_name", rows("[" + column("table_name", "varchar") + "," + column("table_type", "varchar")
            + "]"))
        .on("DESCRIBE \"tpch\".\"tiny\".\"nation\"", rows("[" + column("Column", "varchar") + ","
            + column("Type", "varchar") + "," + column("Extra", "varchar") + "," + column("Comment", "varchar") + "]",
            "[\"nationkey\", \"bigint\", \"\", \"the key\"]", "[\"name\", \"varchar(25)\", \"\", \"\"]"))
        .on("SELECT comment FROM system.metadata.table_comments", rows("[" + column("comment", "varchar") + "]",
            "[\"Nations of the world\"]"));
    ToolResult catalogs = connector.execute(call("trino_list_catalogs", "{}"), config(Env.DEV, "tpch", "memory"),
        secrets, TestCtx.of());
    assertThat(catalogs.structuredContent().path("catalogs").toString()).isEqualTo("[\"memory\",\"tpch\"]");
    ToolResult schemas = connector.execute(call("trino_list_schemas", "{}"), config(Env.DEV), secrets, TestCtx.of());
    assertThat(schemas.structuredContent().path("schemas").toString()).isEqualTo("[\"sf1\",\"tiny\"]");
    ToolResult tables = connector.execute(call("trino_list_tables", "{\"like\": \"n%\"}"), config(Env.DEV), secrets,
        TestCtx.of());
    assertThat(tables.structuredContent().path("tables").get(1).path("kind").asString()).isEqualTo("view");
    assertThat(trino.statements).anyMatch(s -> s.contains("table_name LIKE 'n%'"));
    ToolResult d = connector.execute(call("trino_describe_table", "{\"table\": \"nation\"}"), config(Env.DEV),
        secrets, TestCtx.of());
    assertThat(d.structuredContent().path("table").asString()).isEqualTo("tpch.tiny.nation");
    assertThat(d.structuredContent().at("/columns/0/comment").asString()).isEqualTo("the key");
    assertThat(d.structuredContent().path("comment").asString()).isEqualTo("Nations of the world");
    // A quote in a name stays inside its literal.
    connector.execute(call("trino_list_tables", "{\"schema\": \"ti'ny\"}"), config(Env.DEV), secrets, TestCtx.of());
    assertThat(trino.statements.getLast()).contains("table_schema = 'ti''ny'");
    assertThatThrownBy(() -> connector.execute(call("trino_describe_table", "{\"table\": \"hive.s.t\"}"),
        config(Env.DEV, "tpch"), secrets, TestCtx.of())).isInstanceOf(ConnectorException.class);
  }

  @Test
  void testConnectionNamesTheVersionTheUserAndTheCatalogs() {
    trino.on("SELECT current_user", rows("[" + column("_col0", "varchar") + "]", "[\"gaterook_reader\"]"))
        .on("SHOW CATALOGS", rows("[" + column("Catalog", "varchar") + "]", "[\"memory\"]", "[\"tpch\"]"));
    TestResult t = connector.test(config(Env.PROD, "tpch"), secrets, TestCtx.of().env(Env.PROD));
    assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
    assertThat(t.serverVersion()).isEqualTo("Trino 484");
    assertThat(t.identity()).isEqualTo("gaterook_reader");
    assertThat(t.checks()).extracting(TestResult.Check::name).containsExactly("coordinator", "sign-in", "catalogs");
    assertThat(t.checks().get(2).detail()).contains("1 catalog", "tpch");
    assertThat(t.hints()).extracting(TestResult.Hint::code).contains("trino.read-only-boundary", "trino.plain-http");
    // /v1/info carries no credentials.
    assertThat(trino.to("GET /v1/info").getFirst().getHeaders().get("Authorization")).isNull();
  }

  @Test
  void aMissingPasswordAndRefusedCredentialsSayWhatToDo() {
    TestResult t = connector.test(config(Env.DEV), new TestSecrets(), TestCtx.of());
    assertThat(t.ok()).isFalse();
    assertThat(t.error().message()).contains("no stored password");
  }

  @Test
  void prestoModeSendsPrestoHeaders() throws Exception {
    trino.on("EXPLAIN (TYPE IO, FORMAT JSON) SELECT", plan(List.of(), null)).on("SELECT", rows("[]"));
    ConnectionConfig base = config(Env.DEV);
    ObjectNode c = (ObjectNode) base.config().deepCopy();
    c.put("engine", "presto");
    connector.execute(call("trino_query", sql("SELECT 1")), new ConnectionConfig(base.id(), base.name(), base.type(),
        base.env(), 2, c, base.secretFields(), null), secrets, TestCtx.of());
    RecordedRequest sent = trino.to("POST /v1/statement").getLast();
    assertThat(sent.getHeaders().get("X-Presto-User")).isEqualTo("gaterook_reader");
    assertThat(sent.getHeaders().get("X-Presto-Catalog")).isEqualTo("tpch");
    assertThat(sent.getHeaders().get("X-Trino-User")).isNull();
    assertThat(sent.getHeaders().get("X-Presto-Client-Capabilities")).isNull();
  }

  @Test
  void aJwtIsABearerTokenAndJoinsTheTaint() throws Exception {
    trino.on("EXPLAIN (TYPE IO, FORMAT JSON) SELECT", plan(List.of(), null)).on("SELECT", rows("[]"));
    ConnectionConfig base = config(Env.DEV);
    ObjectNode c = (ObjectNode) base.config().deepCopy();
    ((ObjectNode) c.get("auth")).put("mode", "jwt").remove("user");
    TestCtx ctx = TestCtx.of();
    connector.execute(call("trino_query", sql("SELECT 1")), new ConnectionConfig(base.id(), base.name(), base.type(),
        base.env(), 3, c, Set.of("/auth/token"), null), new TestSecrets().with("/auth/token", "eyJ.test-only.jwt"),
        ctx);
    RecordedRequest sent = trino.to("POST /v1/statement").getLast();
    assertThat(sent.getHeaders().get("Authorization")).isEqualTo("Bearer eyJ.test-only.jwt");
    assertThat(sent.getHeaders().get("X-Trino-User")).isNull();
    assertThat(ctx.taints).contains("eyJ.test-only.jwt");
  }
}
