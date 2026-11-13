package com.gaterook.connectors.influxdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.gaterook.connector.api.ActionPreview;
import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.HealthResult;
import com.gaterook.connector.api.Limits;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connector.api.PreparedAction;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolResult;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The InfluxDB connector against the real servers of the influxdb Compose profile: InfluxDB 3 Core (its SQL and
 * InfluxQL APIs, admin token) and InfluxDB 2.9 (InfluxQL through its v1-compatible API, an operator token and a token
 * that may only read). Start them with {@code scripts/test-services.sh up influxdb}, then run with
 * {@code -Pservices,influxdb-it}. Every run writes to databases and buckets of its own and removes them.
 */
@Tag("influxdb-it")
class InfluxDbIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final String RUN = Long.toString(System.currentTimeMillis(), 36);

  private static InfluxDbConnector connector;

  @BeforeAll
  static void connector() {
    assumeTrue(TestServices.enabled(), "InfluxDB runs in the Compose services: scripts/test-services.sh up influxdb, "
        + "then -Pservices,influxdb-it");
    connector = new InfluxDbConnector();
  }

  @AfterAll
  static void close() {
    if (connector != null) {
      connector.close();
    }
  }

  private static ToolCall call(String tool, Object... kv) {
    ObjectNode args = F.objectNode();
    for (int i = 0; i + 1 < kv.length; i += 2) {
      args.set((String) kv[i], JSON.valueToTree(kv[i + 1]));
    }
    return new ToolCall("tc_it_" + Long.toHexString(System.nanoTime()), tool, "it-influx", args, null);
  }

  private static Classification classify(ToolCall call, ConnectionConfig cfg) {
    return connector.classify(call, cfg, new PolicyView(Env.DEV, "dev.influxdb", F.objectNode(), TestCtx.dev()));
  }

  /** A read as the core runs it: classified first, refused unless it is a read. */
  private static ToolResult read(ToolCall call, ConnectionConfig cfg, TestSecrets secrets) throws ConnectorException {
    Classification c = classify(call, cfg);
    assertThat(c.kind()).as(c.reason()).isEqualTo(Classification.Kind.READ);
    return connector.execute(call, cfg, secrets, TestCtx.of());
  }

  /** A write as the core runs it: classified, described, prepared, approved, then run from the approved payload. */
  private static ToolResult approvedWrite(ToolCall call, ConnectionConfig cfg, TestSecrets secrets)
      throws ConnectorException {
    Classification c = classify(call, cfg);
    assertThat(c.kind()).as(c.reason()).isEqualTo(Classification.Kind.MUTATION);
    PreparedAction prepared = connector.prepare(call, cfg, secrets, TestCtx.of());
    assertThat(prepared.outcome()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    return connector.execute(call, cfg, secrets, TestCtx.of().approved(prepared.preview().payload()));
  }

  private static List<List<String>> cells(ToolResult r) {
    List<List<String>> out = new ArrayList<>();
    for (JsonNode row : r.table().rows()) {
      List<String> cells = new ArrayList<>();
      row.forEach(cell -> cells.add(cell.isNull() ? null : cell.isString() ? cell.asString() : cell.toString()));
      out.add(cells);
    }
    return out;
  }

  private static HttpResponse<String> api(String method, String url, String authorization, String type, String body)
      throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
        .header("Authorization", authorization);
    if (type != null) {
      b.header("Content-Type", type);
    }
    b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String q(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  // ── InfluxDB 3 Core ─────────────────────────────────────────────────────────────────────────────────────────

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class InfluxDb3Core {

    private final String base = "http://127.0.0.1:" + TestServices.port("GW_INFLUXDB3_PORT");
    private final String token = TestServices.value("GW_INFLUXDB3_TOKEN");
    private final TestSecrets secrets = new TestSecrets().with("/auth/token", token);
    private final String db = "gw_it_" + RUN;

    @BeforeAll
    void reachable() throws Exception {
      HttpResponse<String> ping = api("GET", base + "/ping", "Bearer " + token, null, null);
      assumeTrue(ping.statusCode() == 200, "Start the influxdb profile: scripts/test-services.sh up influxdb");
    }

    @AfterAll
    void dropDatabases() throws Exception {
      for (String name : List.of(db, db + "_limits")) {
        api("DELETE", base + "/api/v3/configure/database?db=" + q(name), "Bearer " + token, null, null);
      }
    }

    ConnectionConfig config(String... databases) {
      ObjectNode c = F.objectNode().put("url", base).put("version", "3");
      c.putObject("auth").put("method", "token");
      if (databases.length > 0) {
        ArrayNode list = c.putArray("databases");
        for (String d : databases) {
          list.add(d);
        }
      }
      return new ConnectionConfig("it-influx3-" + String.join("-", databases), "it-influx", "influxdb", Env.DEV, 1,
          c, Set.of("/auth/token"), null);
    }

    @Test
    void testConnectionReportsCoreAndItsAdminToken() {
      TestResult t = connector.test(config(), secrets, TestCtx.of());
      assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
      assertThat(t.serverVersion()).startsWith("InfluxDB 3.").endsWith(" Core");
      assertThat(t.permissions().level()).isEqualTo(TestResult.Level.ADMIN);
      assertThat(t.hints()).extracting(TestResult.Hint::code).contains("influxdb.read-boundary",
          "influxdb.admin-credentials");
      assertThat(connector.health(config(), secrets, TestCtx.of()).state()).isEqualTo(HealthResult.State.HEALTHY);

      ObjectNode wrong = (ObjectNode) config().config().deepCopy();
      wrong.put("version", "2");
      TestResult mismatch = connector.test(new ConnectionConfig("it-influx3-v2", "it-influx", "influxdb", Env.DEV, 1,
          wrong, Set.of("/auth/token"), null), secrets, TestCtx.of());
      assertThat(mismatch.error().code()).isEqualTo("influxdb.version");
      assertThat(connector.test(config(), new TestSecrets().with("/auth/token", "apiv3_wrong-token-0001"),
          TestCtx.of()).error().code()).isEqualTo("influxdb.credentials");
    }

    @Test
    void anApprovedWriteIsReadBackBySqlAndInfluxQl() throws Exception {
      String lines = String.join("\n",
          "cpu,host=web-1,region=eu usage=12.5,cores=4i,ok=true,note=\"a \\\"quoted\\\" \\\\ note\" 1730000000",
          "cpu,host=web-2,region=eu usage=7.25,cores=8i,ok=false,note=\"b\" 1730000060",
          "mem,host=web-1 used=9007199254740993i,free=1u 1730000000");
      ToolCall write = call("influxdb_write", "database", db, "lines", lines, "precision", "s", "note", "it");
      ConnectionConfig cfg = config("gw_it_*");

      // Before approval nothing is written, and the approval says the database would be new.
      assertThatThrownBy(() -> connector.execute(write, cfg, secrets, TestCtx.of()))
          .isInstanceOfSatisfying(ConnectorException.class,
              e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
      PreparedAction prepared = connector.prepare(write, cfg, secrets, TestCtx.of());
      assertThat(prepared.preview().kind()).isEqualTo(ActionPreview.Kind.HTTP);
      assertThat(prepared.preview().text()).startsWith("POST " + base + "/api/v3/write_lp?db=" + db
          + "&precision=second&accept_partial=false\n").endsWith("\n\n" + lines).doesNotContain(token);
      assertThat(prepared.preview().warnings()).anyMatch(w -> w.contains("doesn't exist yet"));

      ToolResult written = approvedWrite(write, cfg, secrets);
      assertThat(written.structuredContent().path("points").asInt()).isEqualTo(3);

      ToolResult sql = read(call("influxdb_query", "database", db, "query",
          "SELECT host, usage, cores, ok, note, time FROM cpu ORDER BY time"), cfg, secrets);
      assertThat(sql.table().columns()).extracting(ToolResult.Column::name).containsExactly("host", "usage", "cores",
          "ok", "note", "time");
      assertThat(cells(sql)).containsExactly(
          List.of("web-1", "12.5", "4", "true", "a \"quoted\" \\ note", "2024-10-27T03:33:20Z"),
          List.of("web-2", "7.25", "8", "false", "b", "2024-10-27T03:34:20Z"));
      // Beyond JavaScript's exact integers, the digits stay exact as text.
      ToolResult mem = read(call("influxdb_query", "database", db, "query", "SELECT used, free FROM mem"), cfg,
          secrets);
      assertThat(cells(mem)).containsExactly(List.of("9007199254740993", "1"));

      ToolResult param = read(call("influxdb_query", "database", db, "query",
          "SELECT usage FROM cpu WHERE host = $h", "parameters", Map.of("h", "web-2")), cfg, secrets);
      assertThat(cells(param)).containsExactly(List.of("7.25"));

      ToolResult influxql = read(call("influxdb_query", "database", db, "language", "influxql", "query",
          "SELECT mean(usage) FROM cpu GROUP BY host"), cfg, secrets);
      assertThat(influxql.table().columns()).extracting(ToolResult.Column::name).contains("host", "mean");
      assertThat(influxql.table().rows()).hasSize(2);

      List<String> listed = new ArrayList<>();
      read(call("influxdb_list_databases"), cfg, secrets).structuredContent().path("databases")
          .forEach(n -> listed.add(n.asString()));
      assertThat(listed).contains(db).allMatch(name -> name.startsWith("gw_it_"));
      assertThat(read(call("influxdb_list_measurements", "database", db), cfg, secrets).structuredContent()
          .path("measurements").toString()).isEqualTo("[\"cpu\",\"mem\"]");
      JsonNode described = read(call("influxdb_describe", "database", db, "measurement", "cpu"), cfg, secrets)
          .structuredContent();
      assertThat(described.path("tags").toString()).isEqualTo("[\"host\",\"region\"]");
      assertThat(described.path("fields").toString()).contains("{\"name\":\"usage\",\"type\":\"float\"}",
          "{\"name\":\"cores\",\"type\":\"integer\"}", "{\"name\":\"ok\",\"type\":\"boolean\"}");

      // A database off the connection's list is refused, by the statement's own name too.
      assertThat(classify(call("influxdb_query", "database", "_internal", "query", "SELECT 1"), cfg).ruleId())
          .isEqualTo("dev.influxdb.database-not-allowed");
      assertThat(classify(call("influxdb_query", "database", db, "language", "influxql", "query",
          "SELECT * FROM \"_internal\".\"autogen\".\"cpu\""), cfg).ruleId())
          .isEqualTo("dev.influxdb.database-not-allowed");
    }

    @Test
    void theReadToolNeverWrites() throws Exception {
      ConnectionConfig cfg = config();
      approvedWrite(call("influxdb_write", "database", db, "lines", "guard,host=a v=1 1730000000", "precision", "s"),
          cfg, secrets);
      for (String[] statement : new String[][] {{"sql", "DELETE FROM guard"}, {"sql", "DROP TABLE guard"},
          {"sql", "SELECT 1; DELETE FROM guard"}, {"sql", "COPY (SELECT * FROM guard) TO 'out.parquet'"},
          {"influxql", "SELECT * INTO guard_copy FROM guard"}, {"influxql", "DROP MEASUREMENT guard"},
          {"influxql", "DELETE FROM guard"}, {"influxql", "SELECT * FROM guard; DROP DATABASE " + db}}) {
        ToolCall c = call("influxdb_query", "database", db, "language", statement[0], "query", statement[1]);
        assertThat(classify(c, cfg).kind()).as(statement[1]).isEqualTo(Classification.Kind.DENY);
        assertThatThrownBy(() -> connector.execute(c, cfg, secrets, TestCtx.of())).as(statement[1])
            .isInstanceOfSatisfying(ConnectorException.class,
                e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
      }
      ToolResult still = read(call("influxdb_query", "database", db, "query", "SELECT count(*) AS n FROM guard"), cfg,
          secrets);
      assertThat(cells(still)).containsExactly(List.of("1"));
      HttpResponse<String> copy = api("POST", base + "/api/v3/query_sql", "Bearer " + token, "application/json",
          "{\"db\":\"" + db + "\",\"q\":\"SELECT count(*) FROM guard_copy\",\"format\":\"json\"}");
      assertThat(copy.statusCode()).isNotEqualTo(200);
    }

    @Test
    void readsKeepToTheirLimitsAndErrorsSayWhatToDo() throws Exception {
      String limitsDb = db + "_limits";
      ConnectionConfig cfg = config();
      StringBuilder lines = new StringBuilder();
      for (int i = 0; i < 30; i++) {
        lines.append("load,host=h").append(i).append(" v=").append(i).append(".5,text=\"").append("x".repeat(300))
            .append("\" ").append(1730000000 + i).append('\n');
      }
      approvedWrite(call("influxdb_write", "database", limitsDb, "lines", lines.toString(), "precision", "s"), cfg,
          secrets);
      ToolResult five = read(call("influxdb_query", "database", limitsDb, "query", "SELECT * FROM load ORDER BY time",
          "limit", 5), cfg, secrets);
      assertThat(five.table().rows()).hasSize(5);
      assertThat(five.truncatedReason()).isEqualTo("row_limit");

      Limits small = new Limits(1_000, 1_000, 8 * 1024, 64 * 1024, 64 * 1024, 64 * 1024, 100, Duration.ofSeconds(5),
          Duration.ofSeconds(30), Duration.ofSeconds(10), null);
      ToolResult cut = connector.execute(call("influxdb_query", "database", limitsDb, "query",
          "SELECT * FROM load ORDER BY time", "limit", 30), cfg, secrets, TestCtx.of().with(small));
      assertThat(cut.truncatedReason()).isEqualTo("size_limit");
      assertThat(cut.table().rows().size()).isBetween(1, 29);

      // A field of another type is refused whole: InfluxDB 3 writes all points or none.
      ToolCall conflict = call("influxdb_write", "database", limitsDb, "lines",
          "load,host=new v=1.5 1730001000\nload,host=bad v=2i 1730001001", "precision", "s");
      assertThatThrownBy(() -> approvedWrite(conflict, cfg, secrets))
          .isInstanceOfSatisfying(ConnectorException.class, e -> {
            assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
            assertThat(e.getMessage()).contains("nothing was written");
          });
      assertThat(cells(read(call("influxdb_query", "database", limitsDb, "query",
          "SELECT count(*) AS n FROM load"), cfg, secrets))).containsExactly(List.of("30"));

      assertThatThrownBy(() -> read(call("influxdb_query", "database", limitsDb, "query", "SELECT nope FROM load"),
          cfg, secrets)).isInstanceOf(ConnectorException.class).hasMessageContaining("nope");
      assertThatThrownBy(() -> read(call("influxdb_query", "database", "gw_it_missing_" + RUN, "query", "SELECT 1"),
          cfg, secrets)).isInstanceOfSatisfying(ConnectorException.class, e -> {
            assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
            assertThat(e.getMessage()).contains("database not found");
          });
      assertThatThrownBy(() -> read(call("influxdb_query", "database", limitsDb, "query", "SELECT 1"), cfg,
          new TestSecrets().with("/auth/token", "apiv3_wrong-token-0001")))
          .isInstanceOfSatisfying(ConnectorException.class,
              e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE));
    }
  }

  // ── InfluxDB 2.9 ────────────────────────────────────────────────────────────────────────────────────────────

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class InfluxDb2 {

    private final String base = "http://127.0.0.1:" + TestServices.port("GW_INFLUXDB2_PORT");
    private final String operator = TestServices.value("GW_INFLUXDB2_TOKEN");
    private final String bucket = "gw_it_" + RUN;
    private String orgId;
    private String bucketId;
    private String readerId;
    private TestSecrets operatorSecrets;
    private TestSecrets readerSecrets;

    @BeforeAll
    void bucketAndReader() throws Exception {
      HttpResponse<String> health = api("GET", base + "/health", "Token " + operator, null, null);
      assumeTrue(health.statusCode() == 200, "Start the influxdb profile: scripts/test-services.sh up influxdb");
      orgId = JSON.readTree(api("GET", base + "/api/v2/orgs?org=acme", "Token " + operator, null, null).body())
          .path("orgs").path(0).path("id").asString();
      HttpResponse<String> created = api("POST", base + "/api/v2/buckets", "Token " + operator, "application/json",
          "{\"orgID\":\"" + orgId + "\",\"name\":\"" + bucket + "\",\"retentionRules\":[]}");
      assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
      bucketId = JSON.readTree(created.body()).path("id").asString();
      HttpResponse<String> reader = api("POST", base + "/api/v2/authorizations", "Token " + operator,
          "application/json", "{\"orgID\":\"" + orgId + "\",\"description\":\"gw-it-reader\",\"permissions\":"
              + "[{\"action\":\"read\",\"resource\":{\"type\":\"buckets\",\"id\":\"" + bucketId + "\",\"orgID\":\""
              + orgId + "\"}}]}");
      assertThat(reader.statusCode()).as(reader.body()).isEqualTo(201);
      JsonNode authorization = JSON.readTree(reader.body());
      readerId = authorization.path("id").asString();
      operatorSecrets = new TestSecrets().with("/auth/token", operator);
      readerSecrets = new TestSecrets().with("/auth/token", authorization.path("token").asString());
    }

    @AfterAll
    void removeBucketAndReader() throws Exception {
      if (readerId != null) {
        api("DELETE", base + "/api/v2/authorizations/" + readerId, "Token " + operator, null, null);
      }
      if (bucketId != null) {
        api("DELETE", base + "/api/v2/buckets/" + bucketId, "Token " + operator, null, null);
      }
    }

    ConnectionConfig config(String... databases) {
      ObjectNode c = F.objectNode().put("url", base).put("version", "2").put("organization", "acme");
      c.putObject("auth").put("method", "token");
      if (databases.length > 0) {
        ArrayNode list = c.putArray("databases");
        for (String d : databases) {
          list.add(d);
        }
      }
      return new ConnectionConfig("it-influx2-" + String.join("-", databases), "it-influx", "influxdb", Env.DEV, 1,
          c, Set.of("/auth/token"), null);
    }

    @Test
    void testConnectionTellsTheOperatorTokenFromTheReader() {
      TestResult admin = connector.test(config(), operatorSecrets, TestCtx.of());
      assertThat(admin.ok()).as(String.valueOf(admin.error())).isTrue();
      assertThat(admin.serverVersion()).isEqualTo("InfluxDB 2.9.1 OSS");
      assertThat(admin.permissions().level()).isEqualTo(TestResult.Level.ADMIN);
      TestResult reader = connector.test(config(), readerSecrets, TestCtx.of());
      assertThat(reader.ok()).as(String.valueOf(reader.error())).isTrue();
      assertThat(reader.permissions().level()).isEqualTo(TestResult.Level.UNKNOWN);
      assertThat(reader.checks().get(1).detail()).isEqualTo("1 bucket agents may use: " + bucket);
      assertThat(connector.health(config(), readerSecrets, TestCtx.of()).state())
          .isEqualTo(HealthResult.State.HEALTHY);
    }

    @Test
    void anApprovedWriteIsReadBackWithAReadToken() throws Exception {
      ConnectionConfig cfg = config(bucket);
      String lines = "cpu,host=web-1 usage=12.5,cores=4i 1730000000000\ncpu,host=web-2 usage=7.25,cores=8i "
          + "1730000060000\nmem,host=web-1 used=42i 1730000000000";
      ToolResult written = approvedWrite(call("influxdb_write", "database", bucket, "lines", lines, "precision", "ms"),
          cfg, operatorSecrets);
      assertThat(written.structuredContent().path("points").asInt()).isEqualTo(3);

      ToolResult rows = read(call("influxdb_query", "database", bucket, "query",
          "SELECT host, usage, cores FROM cpu ORDER BY time"), cfg, readerSecrets);
      assertThat(rows.table().columns()).extracting(ToolResult.Column::name).containsExactly("time", "host", "usage",
          "cores");
      assertThat(cells(rows)).containsExactly(List.of("2024-10-27T03:33:20Z", "web-1", "12.5", "4"),
          List.of("2024-10-27T03:34:20Z", "web-2", "7.25", "8"));
      ToolResult grouped = read(call("influxdb_query", "database", bucket, "query",
          "SELECT max(usage) FROM cpu GROUP BY host"), cfg, readerSecrets);
      assertThat(grouped.table().columns()).extracting(ToolResult.Column::name).containsExactly("measurement", "host",
          "time", "max");
      assertThat(cells(grouped)).extracting(r -> r.get(1)).containsExactly("web-1", "web-2");
      ToolResult param = read(call("influxdb_query", "database", bucket, "query",
          "SELECT usage FROM cpu WHERE host = $h", "parameters", Map.of("h", "web-2")), cfg, readerSecrets);
      assertThat(cells(param)).extracting(r -> r.get(1)).containsExactly("7.25");

      ToolResult databases = read(call("influxdb_query", "query", "SHOW DATABASES"), cfg, operatorSecrets);
      assertThat(cells(databases)).containsExactly(List.of(bucket));
      assertThat(read(call("influxdb_list_databases"), cfg, operatorSecrets).structuredContent().path("databases")
          .toString()).isEqualTo("[\"" + bucket + "\"]");
      assertThat(read(call("influxdb_list_measurements", "database", bucket), cfg, readerSecrets).structuredContent()
          .path("measurements").toString()).isEqualTo("[\"cpu\",\"mem\"]");
      JsonNode described = read(call("influxdb_describe", "database", bucket, "measurement", "cpu"), cfg,
          readerSecrets).structuredContent();
      assertThat(described.path("tags").toString()).isEqualTo("[\"host\"]");
      assertThat(described.path("fields").toString()).isEqualTo("[{\"name\":\"cores\",\"type\":\"integer\"},"
          + "{\"name\":\"usage\",\"type\":\"float\"}]");
    }

    @Test
    void theServerKeepsAReadTokenFromWritingAndPartialWritesAreUnknown() throws Exception {
      ConnectionConfig cfg = config(bucket);
      ToolCall write = call("influxdb_write", "database", bucket, "lines", "denied,host=a v=1 1730000000",
          "precision", "s");
      assertThatThrownBy(() -> approvedWrite(write, cfg, readerSecrets))
          .isInstanceOfSatisfying(ConnectorException.class, e -> {
            assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
            assertThat(e.getMessage()).contains("may not write", "nothing was written");
          });
      assertThat(read(call("influxdb_query", "database", bucket, "query", "SELECT * FROM denied"), cfg,
          readerSecrets).table().rows()).isEmpty();

      approvedWrite(call("influxdb_write", "database", bucket, "lines", "typed,host=a v=1.5 1730000000",
          "precision", "s"), cfg, operatorSecrets);
      // InfluxDB 2.x keeps the points it accepts when it refuses others: the outcome is reported as unknown.
      ToolCall partial = call("influxdb_write", "database", bucket, "lines",
          "typed,host=b v=2.5 1730000001\ntyped,host=c v=3i 1730000002", "precision", "s");
      assertThatThrownBy(() -> approvedWrite(partial, cfg, operatorSecrets))
          .isInstanceOfSatisfying(ConnectorException.class, e -> {
            assertThat(e.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN);
            assertThat(e.getMessage()).contains("part of the points");
          });
      assertThat(cells(read(call("influxdb_query", "database", bucket, "query", "SELECT v FROM typed ORDER BY time"),
          cfg, readerSecrets))).extracting(r -> r.get(1)).containsExactly("1.5", "2.5");

      for (String statement : List.of("SELECT * INTO copy FROM typed", "DROP MEASUREMENT typed",
          "DELETE FROM typed", "CREATE DATABASE x", "SHOW USERS")) {
        ToolCall c = call("influxdb_query", "database", bucket, "query", statement);
        assertThat(classify(c, cfg).kind()).as(statement).isEqualTo(Classification.Kind.DENY);
        assertThatThrownBy(() -> connector.execute(c, cfg, operatorSecrets, TestCtx.of())).as(statement)
            .isInstanceOf(ConnectorException.class);
      }
      assertThat(classify(call("influxdb_query", "database", "metrics", "query", "SELECT * FROM cpu"), cfg).ruleId())
          .isEqualTo("dev.influxdb.database-not-allowed");
      assertThat(classify(call("influxdb_query", "database", bucket, "language", "sql", "query", "SELECT 1"), cfg)
          .ruleId()).isEqualTo("dev.influxdb.sql-not-supported");
    }
  }
}
