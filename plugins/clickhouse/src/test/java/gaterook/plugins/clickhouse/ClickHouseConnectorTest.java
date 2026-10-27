package gaterook.plugins.clickhouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.net.InetAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The plugin against ClickHouse's HTTP interface in MockWebServer (step K6): the statement in the POST body, the
 * settings every query carries ({@code readonly=1}, {@code allow_ddl=0}, the time and row limits), the credentials
 * in ClickHouse's headers, values as query parameters, how {@code JSONCompact} becomes a table, and what ClickHouse's
 * refusals mean for the agent (an unknown table, the account, the time limit, a read-only profile) and what is the
 * service's trouble (HTTP 503).
 */
class ClickHouseConnectorTest {

  private MockWebServer server;
  private final Deque<MockResponse> answers = new ArrayDeque<>();
  private final List<RecordedRequest> seen = new CopyOnWriteArrayList<>();
  private final ClickHouseConnector connector = new ClickHouseConnector(TestCalls.manifest());

  @BeforeEach
  void start() throws Exception {
    server = new MockWebServer();
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        seen.add(request);
        synchronized (answers) {
          MockResponse next = answers.poll();
          return next != null ? next : new MockResponse.Builder().code(500)
              .body("Code: 999. DB::Exception: no answer queued").build();
        }
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
  }

  @AfterEach
  void stop() throws Exception {
    server.close();
  }

  private void answer(String json) {
    synchronized (answers) {
      answers.add(new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json; charset=UTF-8")
          .body(json).build());
    }
  }

  private void refuse(int status, int code, String message) {
    synchronized (answers) {
      answers.add(new MockResponse.Builder().code(status).addHeader("X-ClickHouse-Exception-Code", code)
          .body("Code: " + code + ". DB::Exception: " + message + " (version 24.8.4.13 (official build))\n")
          .build());
    }
  }

  private static String param(RecordedRequest r, String name) {
    String target = r.getTarget();
    String query = target.substring(target.indexOf('?') + 1);
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8).equals(name)) {
        return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  private ConnectionConfig selected(String... names) {
    ConnectionConfig cfg = TestCalls.local(server.getPort());
    ObjectNode settings = (ObjectNode) cfg.config().deepCopy();
    var databases = settings.putArray("databases");
    for (String name : names) databases.add(name);
    return new ConnectionConfig(cfg.id(), cfg.name(), cfg.type(), cfg.env(), cfg.version(), settings,
        cfg.secretFields(), cfg.metadata());
  }

  @Test
  void discoveryUsesCredentialsBeforeChoosingADatabase() {
    answer("{\"meta\":[],\"data\":[[\"analytics\"],[\"archive\"]],\"rows\":2}");
    TestResult result = connector.discoverDatabases(selected("unavailable"), new TestCalls.Secrets(),
        new TestCalls.Context(false));
    assertThat(result.ok()).isTrue();
    assertThat(result.details().path("databases")).extracting(JsonNode::asString)
        .containsExactly("analytics", "archive");
    assertThat(seen.getFirst().getBody().utf8()).isEqualTo("SHOW DATABASES");
    assertThat(param(seen.getFirst(), "database")).isNull();
    assertThat(seen.getFirst().getHeaders().get("X-ClickHouse-Key")).isEqualTo(TestCalls.PASSWORD);
  }

  @Test
  void callsChooseASelectedDatabaseAndRejectOtherSourcesBeforeSending() throws Exception {
    ConnectionConfig cfg = selected("analytics", "archive");
    answer("{\"meta\":[],\"data\":[[1]],\"rows\":1}");
    connector.execute(TestCalls.call("clickhouse_query", "{\"sql\":\"SELECT 1\",\"database\":\"archive\"}"),
        cfg, new TestCalls.Secrets(), new TestCalls.Context(false));
    assertThat(param(seen.getFirst(), "database")).isEqualTo("archive");
    for (String args : List.of("{\"sql\":\"SELECT 1\"}",
        "{\"sql\":\"SELECT 1\",\"database\":\"private\"}",
        "{\"sql\":\"SELECT * FROM private.users\",\"database\":\"analytics\"}")) {
      assertThatThrownBy(() -> connector.execute(TestCalls.call("clickhouse_query", args), cfg,
          new TestCalls.Secrets(), new TestCalls.Context(false))).isInstanceOf(ConnectorException.class);
    }
    assertThat(seen).hasSize(1);
    assertThatThrownBy(() -> ClickHouseConfig.of(selected())).isInstanceOf(ConnectorException.class);
  }

  @Test
  void databaseListingFiltersInTheServerQueryAndTestingChecksEverySelection() throws Exception {
    ConnectionConfig cfg = selected("analytics", "archive");
    answer("{\"meta\":[],\"data\":[],\"rows\":0}");
    connector.execute(TestCalls.call("clickhouse_list_databases", "{}"), cfg, new TestCalls.Secrets(),
        new TestCalls.Context(false));
    assertThat(seen.getFirst().getBody().utf8()).contains("d.name IN ({selected0:String}, {selected1:String})");
    assertThat(param(seen.getFirst(), "param_selected0")).isEqualTo("analytics");
    assertThat(param(seen.getFirst(), "param_selected1")).isEqualTo("archive");
    answer("{\"meta\":[],\"data\":[[\"24.8\",\"gw_reader\",\"analytics\"]],\"rows\":1}");
    refuse(403, 497, "Access denied to archive");
    assertThat(connector.test(cfg, new TestCalls.Secrets(), new TestCalls.Context(false)).ok()).isFalse();
    assertThat(param(seen.getLast(), "database")).isEqualTo("archive");
  }

  @Test
  void aQueryGoesInThePostBodyWithTheLimitsAndComesBackAsATable() throws Exception {
    answer("""
        {"meta":[{"name":"event","type":"String"},{"name":"n","type":"UInt64"}],
         "data":[["click","120"],["view","98"],["buy","7"]],
         "rows":3,"rows_before_limit_at_least":3,
         "statistics":{"elapsed":0.012,"rows_read":225,"bytes_read":4096}}""");
    TestCalls.Context ctx = new TestCalls.Context(false);

    ToolResult r = connector.execute(TestCalls.call("clickhouse_query",
        "{\"sql\":\"SELECT event, count() AS n FROM events GROUP BY event ORDER BY n DESC;\",\"limit\":2}"),
        TestCalls.local(server.getPort()), new TestCalls.Secrets(), ctx);

    RecordedRequest req = seen.getFirst();
    assertThat(req.getMethod()).isEqualTo("POST");
    assertThat(req.getBody().utf8()).isEqualTo("SELECT event, count() AS n FROM events GROUP BY event ORDER BY n DESC");
    assertThat(param(req, "readonly")).isEqualTo("1");
    assertThat(param(req, "allow_ddl")).isEqualTo("0");
    assertThat(param(req, "database")).isEqualTo("analytics");
    assertThat(param(req, "default_format")).isEqualTo("JSONCompact");
    assertThat(param(req, "max_execution_time")).isEqualTo("20");
    assertThat(param(req, "max_result_rows")).isEqualTo("3");
    assertThat(param(req, "result_overflow_mode")).isEqualTo("break");
    assertThat(param(req, "query_id")).isEqualTo("mcp-gateway-tc_test");
    assertThat(req.getHeaders().get("X-ClickHouse-User")).isEqualTo("gw_reader");
    assertThat(req.getHeaders().get("X-ClickHouse-Key")).isEqualTo(TestCalls.PASSWORD);

    assertThat(r.isError()).isFalse();
    assertThat(r.truncated()).isTrue();
    assertThat(r.truncatedReason()).isEqualTo("row_limit");
    assertThat(r.table().columns()).extracting(ToolResult.Column::name).containsExactly("event", "n");
    assertThat(r.table().columns()).extracting(ToolResult.Column::type).containsExactly("String", "UInt64");
    assertThat(r.table().rows()).hasSize(2);
    JsonNode s = r.structuredContent();
    assertThat(s.path("rowCount").asInt()).isEqualTo(2);
    assertThat(s.path("truncated").asBoolean()).isTrue();
    assertThat(s.path("rowsRead").asLong()).isEqualTo(225);
    assertThat(s.path("elapsedMs").asLong()).isEqualTo(12);
    assertThat(r.toString()).doesNotContain(TestCalls.PASSWORD);
  }

  @Test
  void theEnvironmentsRowLimitCapsEveryQuery() throws Exception {
    answer("{\"meta\":[{\"name\":\"x\",\"type\":\"UInt8\"}],\"data\":[[1]],\"rows\":1}");
    TestCalls.Context ctx = new TestCalls.Context(false) {
      @Override
      public ai.msrv.gaterook.connector.api.Limits limits() {
        return new ai.msrv.gaterook.connector.api.Limits(10, 1000, 524_288, 1_048_576, 262_144, 65_536, 8192,
            java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(30), java.time.Duration.ofSeconds(10),
            java.time.Duration.ofHours(168));
      }
    };
    connector.execute(TestCalls.call("clickhouse_query", "{\"sql\":\"SELECT 1\",\"limit\":1000}"),
        TestCalls.local(server.getPort()), new TestCalls.Secrets(), ctx);
    assertThat(param(seen.getFirst(), "max_result_rows")).isEqualTo("11");
  }

  @Test
  void aWriteNeverLeavesThePlugin() {
    PolicyView policy = new PolicyView(Env.DEV, "dev.clickhouse", null, null);
    Classification c = connector.classify(TestCalls.call("clickhouse_query",
        "{\"sql\":\"ALTER TABLE events DELETE WHERE 1\"}"), TestCalls.local(server.getPort()), policy);
    assertThat(c.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(c.reason()).contains("never changes data");
    assertThat(connector.classify(TestCalls.call("clickhouse_query", "{\"sql\":\"SELECT 1\"}"),
        TestCalls.local(server.getPort()), policy).kind()).isEqualTo(Classification.Kind.READ);

    assertThatThrownBy(() -> connector.execute(TestCalls.call("clickhouse_query",
        "{\"sql\":\"SELECT * FROM url('http://169.254.169.254/latest/meta-data', CSV)\"}"),
        TestCalls.local(server.getPort()), new TestCalls.Secrets(), new TestCalls.Context(false)))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("url()");
    assertThat(seen).isEmpty();
  }

  @Test
  void listTablesPassesTheDatabaseAndPatternAsParameters() throws Exception {
    answer("""
        {"meta":[{"name":"name","type":"String"},{"name":"engine","type":"String"},
                 {"name":"total_rows","type":"Nullable(UInt64)"},{"name":"total_bytes","type":"Nullable(UInt64)"},
                 {"name":"comment","type":"String"}],
         "data":[["events_2026","MergeTree","1200000","88000000",""],["events_buffer","Buffer",null,null,""]],
         "rows":2}""");
    ToolResult r = connector.execute(TestCalls.call("clickhouse_list_tables",
        "{\"database\":\"web'; DROP\",\"like\":\"events_%\",\"limit\":10}"), TestCalls.local(server.getPort()),
        new TestCalls.Secrets(), new TestCalls.Context(false));

    RecordedRequest req = seen.getFirst();
    String sql = req.getBody().utf8();
    assertThat(sql).contains("database = {db:String}").contains("name LIKE {like:String}").contains("LIMIT 11")
        .doesNotContain("DROP");
    assertThat(param(req, "param_db")).isEqualTo("web'; DROP");
    assertThat(param(req, "param_like")).isEqualTo("events_%");
    assertThat(r.table().rows()).hasSize(2);
    assertThat(r.structuredContent().path("database").asString()).isEqualTo("web'; DROP");
  }

  @Test
  void describeTableReadsTheTableAndItsColumns() throws Exception {
    answer("""
        {"meta":[],"data":[["MergeTree","site_id, ts","toYYYYMM(ts)","site_id, ts","1200000","88000000","Page views"]],
         "rows":1}""");
    answer("""
        {"meta":[],"data":[["site_id","UInt32","","","",1,1],["ts","DateTime","","","",1,1],
                           ["path","String","DEFAULT","'/'","The path",0,0]],"rows":3}""");
    ToolResult r = connector.execute(TestCalls.call("clickhouse_describe_table", "{\"table\":\"page_views\"}"),
        TestCalls.local(server.getPort()), new TestCalls.Secrets(), new TestCalls.Context(false));

    assertThat(seen).hasSize(2);
    assertThat(param(seen.get(0), "param_db")).isEqualTo("analytics");
    assertThat(param(seen.get(0), "param_tbl")).isEqualTo("page_views");
    JsonNode s = r.structuredContent();
    assertThat(s.path("engine").asString()).isEqualTo("MergeTree");
    assertThat(s.path("sortingKey").asString()).isEqualTo("site_id, ts");
    assertThat(s.path("totalRows").asLong()).isEqualTo(1_200_000);
    assertThat(s.path("columns")).hasSize(3);
    assertThat(s.path("columns").get(2).path("defaultExpression").asString()).isEqualTo("'/'");
    assertThat(s.path("columns").get(0).path("inSortingKey").asBoolean()).isTrue();
    assertThat(r.content().getFirst().text()).startsWith("analytics.page_views · MergeTree · ORDER BY site_id, ts");
  }

  @Test
  void anUnknownTableIsTheAgentsErrorNotTheConnections() throws Exception {
    answer("{\"meta\":[],\"data\":[],\"rows\":0}");
    ToolResult missing = connector.execute(TestCalls.call("clickhouse_describe_table", "{\"table\":\"nope\"}"),
        TestCalls.local(server.getPort()), new TestCalls.Secrets(), new TestCalls.Context(false));
    assertThat(missing.isError()).isTrue();
    assertThat(missing.content().getFirst().text()).isEqualTo("analytics.nope not found or not visible to this user.");

    refuse(404, 60, "Table analytics.nope does not exist. (UNKNOWN_TABLE)");
    ToolResult r = connector.execute(TestCalls.call("clickhouse_query", "{\"sql\":\"SELECT * FROM nope\"}"),
        TestCalls.local(server.getPort()), new TestCalls.Secrets(), new TestCalls.Context(false));
    assertThat(r.isError()).isTrue();
    assertThat(r.content().getFirst().text()).isEqualTo("ClickHouse refused the query (code 60): Table "
        + "analytics.nope does not exist. (UNKNOWN_TABLE)");
  }

  @Test
  void theAccountTheTimeLimitAndAReadOnlyProfileAreSaidPlainly() throws Exception {
    refuse(403, 516, "gw_reader: Authentication failed: password is incorrect, or there is no user with such name.");
    refuse(500, 159, "Timeout exceeded: elapsed 20.1 seconds, maximum: 20. (TIMEOUT_EXCEEDED)");
    refuse(500, 164, "Cannot modify 'max_execution_time' setting in readonly mode. (READONLY)");
    String sql = "{\"sql\":\"SELECT count() FROM events\"}";
    ToolResult auth = connector.execute(TestCalls.call("clickhouse_query", sql), TestCalls.local(server.getPort()),
        new TestCalls.Secrets(), new TestCalls.Context(false));
    ToolResult timeout = connector.execute(TestCalls.call("clickhouse_query", sql), TestCalls.local(server.getPort()),
        new TestCalls.Secrets(), new TestCalls.Context(false));
    ToolResult readonly = connector.execute(TestCalls.call("clickhouse_query", sql),
        TestCalls.local(server.getPort()), new TestCalls.Secrets(), new TestCalls.Context(false));
    assertThat(auth.content().getFirst().text()).isEqualTo("ClickHouse rejected the user or the password. Test the "
        + "connection.");
    assertThat(timeout.content().getFirst().text()).startsWith("The query ran longer than 20 s");
    assertThat(readonly.content().getFirst().text()).contains("readonly=2");
  }

  @Test
  void aValueThatLooksLikeAnErrorStaysDataAndAnErrorAfterTheAnswerBeganIsSaid() throws Exception {
    answer("{\"meta\":[{\"name\":\"note\",\"type\":\"String\"}],\"data\":[[\"Code: 60. DB::Exception: from a log\"]],"
        + "\"rows\":1}");
    ToolResult data = connector.execute(TestCalls.call("clickhouse_query", "{\"sql\":\"SELECT note FROM logs\"}"),
        TestCalls.local(server.getPort()), new TestCalls.Secrets(), new TestCalls.Context(false));
    assertThat(data.isError()).isFalse();
    assertThat(data.table().rows().get(0).get(0).asString()).isEqualTo("Code: 60. DB::Exception: from a log");

    synchronized (answers) {
      answers.add(new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
          .body("{\"meta\":[{\"name\":\"x\",\"type\":\"UInt64\"}],\"data\":[[\"1\"],\n"
              + "Code: 241. DB::Exception: Memory limit (total) exceeded. (MEMORY_LIMIT_EXCEEDED)").build());
    }
    ToolResult late = connector.execute(TestCalls.call("clickhouse_query", "{\"sql\":\"SELECT x FROM big\"}"),
        TestCalls.local(server.getPort()), new TestCalls.Secrets(), new TestCalls.Context(false));
    assertThat(late.isError()).isTrue();
    assertThat(late.content().getFirst().text()).contains("code 241").contains("Memory limit");
  }

  @Test
  void anOutageIsTheConnectionsTrouble() {
    synchronized (answers) {
      answers.add(new MockResponse.Builder().code(503).body("<html>Service Unavailable</html>").build());
    }
    assertThatThrownBy(() -> connector.execute(TestCalls.call("clickhouse_list_databases", "{}"),
        TestCalls.local(server.getPort()), new TestCalls.Secrets(), new TestCalls.Context(false)))
        .isInstanceOf(ConnectorException.class)
        .satisfies(e -> assertThat(((ConnectorException) e).kind())
            .isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE));
  }

  @Test
  void explainAndRunningQueriesAreStatementsOfThePluginsOwn() throws Exception {
    answer("{\"meta\":[{\"name\":\"explain\",\"type\":\"String\"}],\"data\":[[\"Expression\"],[\"  ReadFromMergeTree\"]],"
        + "\"rows\":2}");
    ToolResult plan = connector.execute(TestCalls.call("clickhouse_explain",
        "{\"sql\":\"SELECT * FROM events WHERE site_id = 1\",\"kind\":\"pipeline\"}"),
        TestCalls.local(server.getPort()), new TestCalls.Secrets(), new TestCalls.Context(false));
    assertThat(seen.getLast().getBody().utf8()).isEqualTo("EXPLAIN PIPELINE SELECT * FROM events WHERE site_id = 1");
    assertThat(plan.table().rows()).hasSize(2);

    answer("{\"meta\":[],\"data\":[[\"q1\",\"etl\",12.5,\"9000000\",\"104857600\",\"INSERT INTO x SELECT …\"]],"
        + "\"rows\":1}");
    ToolResult running = connector.execute(TestCalls.call("clickhouse_running_queries", "{\"limit\":5}"),
        TestCalls.local(server.getPort()), new TestCalls.Secrets(), new TestCalls.Context(false));
    assertThat(seen.getLast().getBody().utf8()).contains("FROM system.processes").contains("queryID()")
        .contains("LIMIT 6");
    assertThat(running.table().rows()).hasSize(1);

    assertThatThrownBy(() -> connector.execute(TestCalls.call("clickhouse_explain",
        "{\"sql\":\"SHOW TABLES\"}"), TestCalls.local(server.getPort()), new TestCalls.Secrets(),
        new TestCalls.Context(false))).isInstanceOf(ConnectorException.class).hasMessageContaining("SELECT or WITH");
  }

  @Test
  void describeActionShowsTheQueryAndItsLimitsWithoutTheSecret() {
    ActionPreview p = connector.describeAction(TestCalls.call("clickhouse_list_tables",
        "{\"like\":\"events_%\"}"), TestCalls.local(server.getPort()));
    assertThat(p.kind()).isEqualTo(ActionPreview.Kind.SQL);
    assertThat(p.text()).contains("FROM system.tables").contains("parameters: {db=analytics, like=events_%}");
    assertThat(p.target()).isEqualTo("events-ch · 127.0.0.1:" + server.getPort() + "/analytics");
    assertThat(p.fields()).extracting(ActionPreview.Field::value)
        .containsExactly("readonly=1, at most 500 rows, 20 s");
    assertThat(p.toString()).doesNotContain(TestCalls.PASSWORD);
  }

  @Test
  void testConnectionReportsTheVersionTheUserAndWhetherItCanWrite() {
    answer("{\"meta\":[],\"data\":[[\"24.8.4.13\",\"gw_reader\",\"analytics\"]],\"rows\":1}");
    answer("{\"meta\":[],\"data\":[[\"GRANT SELECT ON analytics.* TO gw_reader\"],"
        + "[\"GRANT INSERT, ALTER UPDATE ON analytics.events TO gw_reader\"]],\"rows\":2}");
    TestResult t = connector.test(TestCalls.local(server.getPort()), new TestCalls.Secrets(),
        new TestCalls.Context(false));
    assertThat(t.ok()).isTrue();
    assertThat(t.serverVersion()).isEqualTo("24.8.4.13");
    assertThat(t.identity()).isEqualTo("gw_reader");
    assertThat(t.permissions().level()).isEqualTo(TestResult.Level.WRITE);
    assertThat(t.permissions().write()).isEqualTo("INSERT, ALTER");
    assertThat(t.hints()).extracting(TestResult.Hint::code).containsExactly("can-write");

    refuse(500, 164, "Cannot modify 'readonly' setting in readonly mode. (READONLY)");
    TestResult ro = connector.test(TestCalls.local(server.getPort()), new TestCalls.Secrets(),
        new TestCalls.Context(false));
    assertThat(ro.ok()).isFalse();
    assertThat(ro.error().code()).isEqualTo("readonly-profile");
  }
}
