package ai.msrv.gaterook.connectors.influxdb;

import static ai.msrv.gaterook.connectors.influxdb.FakeInflux.body;
import static ai.msrv.gaterook.connectors.influxdb.FakeInflux.form;
import static ai.msrv.gaterook.connectors.influxdb.FakeInflux.json;
import static ai.msrv.gaterook.connectors.influxdb.FakeInflux.jsonBody;
import static ai.msrv.gaterook.connectors.influxdb.FakeInflux.param;
import static ai.msrv.gaterook.connectors.influxdb.FakeInflux.ping;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mockwebserver3.MockResponse;
import mockwebserver3.RecordedRequest;
import mockwebserver3.SocketEffect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** The InfluxDB connector against a fake of InfluxDB 3's, 2.x's and 1.x's HTTP APIs. */
class InfluxDbConnectorTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  private static final String TOKEN = "apiv3_test-only-token-cnry";
  private static final String PASSWORD = "test-only-pw-cnry";

  private FakeInflux influx;
  private InfluxDbConnector connector;

  @BeforeEach
  void start() throws Exception {
    influx = new FakeInflux();
    connector = new InfluxDbConnector();
  }

  @AfterEach
  void stop() {
    connector.close();
    influx.close();
  }

  private ObjectNode settings(String version, String... databases) {
    ObjectNode c = F.objectNode().put("url", influx.url()).put("version", version);
    if (version.equals("1")) {
      c.putObject("auth").put("method", "password").put("username", "reader");
    } else {
      c.putObject("auth").put("method", "token");
    }
    if (version.equals("2")) {
      c.put("organization", "acme");
    }
    if (databases.length > 0) {
      ArrayNode list = c.putArray("databases");
      for (String d : databases) {
        list.add(d);
      }
    }
    return c;
  }

  private static ConnectionConfig config(ObjectNode c, Env env) {
    return new ConnectionConfig("conn-influx-" + c.path("version").asString() + "-" + env.id(), env.id() + "-influx",
        "influxdb", env, 1, c, Set.of("/auth/token", "/auth/password"), null);
  }

  private ConnectionConfig config(String version, String... databases) {
    return config(settings(version, databases), Env.DEV);
  }

  private static SecretResolver secrets(String version) {
    return version.equals("1") ? new TestSecrets().with("/auth/password", PASSWORD)
        : new TestSecrets().with("/auth/token", TOKEN);
  }

  private static PolicyView policy(Env env) {
    return new PolicyView(env, env.id() + ".influxdb", F.objectNode(), env == Env.PROD ? TestCtx.prod()
        : TestCtx.dev());
  }

  private static ToolCall call(String tool, String args) {
    return new ToolCall("tc_" + Long.toHexString(System.nanoTime()), tool, "dev-influx",
        (ObjectNode) JSON.readTree(args), null);
  }

  private static String args(Object... kv) {
    ObjectNode n = F.objectNode();
    for (int i = 0; i + 1 < kv.length; i += 2) {
      n.set((String) kv[i], JSON.valueToTree(kv[i + 1]));
    }
    return n.toString();
  }

  private Classification classify(String version, String tool, String args, String... databases) {
    return connector.classify(call(tool, args), config(version, databases), policy(Env.DEV));
  }

  private ToolResult run(String version, String tool, String args, String... databases) throws ConnectorException {
    return connector.execute(call(tool, args), config(version, databases), secrets(version), TestCtx.of());
  }

  // ── Tools, settings and routes ─────────────────────────────────────────────────────────────────────────────

  @Test
  void publishesFourReadsAndOneApprovedWrite() {
    assertThat(connector.manifest().id()).isEqualTo("influxdb");
    assertThat(connector.manifest().name()).isEqualTo("InfluxDB");
    assertThat(connector.manifest().group()).isEqualTo("Databases");
    assertThat(connector.manifest().icon()).isEqualTo("activity");
    assertThat(connector.manifest().tools()).extracting(ToolDefinition::name).containsExactly(
        "influxdb_list_databases", "influxdb_list_measurements", "influxdb_describe", "influxdb_query",
        "influxdb_write");
    ToolDefinition write = connector.manifest().tools().getLast();
    assertThat(write.mutating()).isTrue();
    assertThat(write.readOnlyModeAllowed()).isFalse();
    assertThat(write.defaultMode()).isEqualTo(ToolDefinition.Mode.APPROVAL);
    assertThat(write.prodDefaultMode()).isEqualTo(ToolDefinition.Mode.APPROVAL);
    assertThat(write.inputSchema().at("/properties/note/maxLength").asInt()).isEqualTo(500);
    for (ToolDefinition read : connector.manifest().tools().subList(0, 4)) {
      assertThat(read.defaultMode()).as(read.name()).isEqualTo(ToolDefinition.Mode.READ_ONLY);
      assertThat(read.annotations().readOnlyHint()).isTrue();
    }
    assertThat(connector.manifest().configSchema().at("/x-card/description").asString())
        .startsWith("Databases, measurements and SQL or InfluxQL reads");
  }

  @Test
  void theServerUrlIsHttpsOrThisComputer() throws Exception {
    for (String ok : List.of("https://influxdb.example.com:8181", "https://influxdb.example.com/influx/",
        "https://10.0.4.12:8086", "http://localhost:8086", "http://127.0.0.1:8181", "http://[::1]:8086")) {
      assertThat(InfluxSettings.baseUrl(ok).toString()).as(ok).doesNotEndWith("/");
    }
    for (String refused : List.of("http://influxdb.example.com:8086", "http://10.0.4.12:8086",
        "https://169.254.169.254", "https://metadata.google.internal", "https://user:pw@influxdb.example.com",
        "https://influxdb.example.com/?org=acme", "https://influxdb.example.com/#x", "ftp://influxdb.example.com",
        "https://0177.0.0.1", "https://influxdb.example.com/a%2Fb", "")) {
      assertThatThrownBy(() -> InfluxSettings.baseUrl(refused)).as(refused).isInstanceOf(ConnectorException.class);
    }
    assertThatThrownBy(() -> InfluxSettings.baseUrl("http://influxdb.example.com:8086"))
        .hasMessageContaining("Plain http:// goes only to this computer");
  }

  @Test
  void credentialsFollowTheServer() {
    ObjectNode v2password = settings("2");
    v2password.putObject("auth").put("method", "password").put("username", "reader");
    ObjectNode v1token = settings("1");
    v1token.putObject("auth").put("method", "token");
    ObjectNode prodNone = settings("3");
    prodNone.putObject("auth").put("method", "none");
    ObjectNode colon = settings("1");
    colon.putObject("auth").put("method", "password").put("username", "a:b");
    for (ObjectNode c : List.of(v2password, v1token, colon)) {
      Classification denied = connector.classify(call("influxdb_list_databases", "{}"), config(c, Env.DEV),
          policy(Env.DEV));
      assertThat(denied.ruleId()).as(c.toString()).isEqualTo("dev.influxdb.bad-config");
    }
    assertThat(connector.classify(call("influxdb_list_databases", "{}"), config(prodNone, Env.PROD),
        policy(Env.PROD)).ruleId()).isEqualTo("prod.influxdb.bad-config");
    assertThat(connector.classify(call("influxdb_list_databases", "{}"), config(prodNone, Env.DEV),
        policy(Env.DEV)).kind()).isEqualTo(Classification.Kind.READ);
  }

  @Test
  void queriesAreReadInTheirLanguage() {
    String sql = "influxdb_query";
    assertThat(classify("3", sql, args("database", "metrics", "query", "SELECT * FROM cpu WHERE host = 'a;b'"))
        .kind()).isEqualTo(Classification.Kind.READ);
    assertThat(classify("3", sql, args("database", "metrics", "query", "DELETE FROM cpu")).ruleId())
        .isEqualTo("dev.influxdb.query-writes");
    assertThat(classify("3", sql, args("database", "metrics", "query", "SELECT * FROM system.queries")).ruleId())
        .isEqualTo("dev.influxdb.forbidden");
    assertThat(classify("3", sql, args("database", "metrics", "query", "SELECT 'open")).ruleId())
        .isEqualTo("dev.influxdb.unparsed");
    assertThat(classify("3", sql, args("database", "metrics", "language", "influxql", "query",
        "SELECT * INTO cpu2 FROM cpu")).ruleId()).isEqualTo("dev.influxdb.query-writes");
    assertThat(classify("3", sql, args("database", "metrics", "language", "influxql", "query", "SHOW USERS"))
        .ruleId()).isEqualTo("dev.influxdb.forbidden");
    assertThat(classify("2", sql, args("database", "metrics", "language", "sql", "query", "SELECT 1")).ruleId())
        .isEqualTo("dev.influxdb.sql-not-supported");
    assertThat(classify("2", sql, args("database", "metrics", "query", "SELECT mean(usage) FROM cpu")).kind())
        .isEqualTo(Classification.Kind.READ);
    for (String version : List.of("3", "2", "1")) {
      assertThat(classify(version, sql, args("database", "metrics", "query",
          "from(bucket: \"metrics\") |> range(start: -1h) |> to(bucket: \"copy\")")).ruleId()).as(version)
          .isEqualTo("dev.influxdb.flux-not-supported");
    }
    // A database is named, except for SHOW DATABASES.
    assertThat(classify("2", sql, args("query", "SELECT * FROM cpu")).ruleId()).isEqualTo("dev.influxdb.bad-argument");
    assertThat(classify("3", sql, args("query", "SELECT 1")).ruleId()).isEqualTo("dev.influxdb.bad-argument");
    assertThat(classify("2", sql, args("query", "SHOW DATABASES")).kind()).isEqualTo(Classification.Kind.READ);
    assertThat(classify("1", sql, args("query", "SELECT * FROM metrics.autogen.cpu")).kind())
        .isEqualTo(Classification.Kind.READ);
    // Parameters are named scalar values.
    assertThat(classify("3", sql, args("database", "metrics", "query", "SELECT * FROM cpu WHERE host = $h",
        "parameters", Map.of("h", "web-1"))).kind()).isEqualTo(Classification.Kind.READ);
    assertThat(classify("3", sql, args("database", "metrics", "query", "SELECT $h", "parameters",
        Map.of("h", Map.of("identifier", "cpu")))).ruleId()).isEqualTo("dev.influxdb.bad-argument");
    assertThat(classify("3", sql, args("database", "metrics", "query", "SELECT $h", "parameters",
        Map.of("1h", "x"))).ruleId()).isEqualTo("dev.influxdb.bad-argument");
  }

  @Test
  void onlyListedDatabasesPassAlsoWhenTheStatementNamesThem() {
    String[] list = {"metrics", "iot_*"};
    assertThat(classify("2", "influxdb_query", args("database", "iot_eu", "query", "SELECT * FROM cpu"), list)
        .kind()).isEqualTo(Classification.Kind.READ);
    assertThat(classify("2", "influxdb_query", args("database", "hr", "query", "SELECT * FROM cpu"), list).ruleId())
        .isEqualTo("dev.influxdb.database-not-allowed");
    assertThat(classify("1", "influxdb_query", args("database", "metrics", "query",
        "SELECT * FROM \"hr\".\"autogen\".\"salaries\""), list).ruleId())
        .isEqualTo("dev.influxdb.database-not-allowed");
    assertThat(classify("3", "influxdb_query", args("database", "metrics", "language", "influxql", "query",
        "SHOW MEASUREMENTS ON hr"), list).ruleId()).isEqualTo("dev.influxdb.database-not-allowed");
    assertThat(classify("2", "influxdb_list_measurements", args("database", "hr"), list).ruleId())
        .isEqualTo("dev.influxdb.database-not-allowed");
    assertThat(classify("2", "influxdb_describe", args("database", "hr", "measurement", "cpu"), list).ruleId())
        .isEqualTo("dev.influxdb.database-not-allowed");
    assertThat(classify("3", "influxdb_write", args("database", "hr", "lines", "m v=1"), list).ruleId())
        .isEqualTo("dev.influxdb.database-not-allowed");
    assertThat(classify("3", "influxdb_describe", args("database", "metrics", "measurement", "a\"b"), list)
        .ruleId()).isEqualTo("dev.influxdb.bad-argument");
    assertThat(classify("3", "influxdb_list_measurements", args("database", " metrics"), list).ruleId())
        .isEqualTo("dev.influxdb.bad-argument");
  }

  @Test
  void writesAreReadPointByPoint() {
    String two = "cpu,host=web-1 usage=12.5 1730000000\ncpu,host=web-2 usage=7.25 1730000060";
    Classification ok = classify("3", "influxdb_write", args("database", "metrics", "lines", two, "precision", "s"));
    assertThat(ok.kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(ok.facts().path("points").asInt()).isEqualTo(2);
    assertThat(ok.facts().path("measurements").toString()).isEqualTo("[\"cpu\"]");
    Classification malformed = classify("3", "influxdb_write", args("database", "metrics", "lines",
        "cpu usage=1\ncpu,host=a\\\\b usage=2"));
    assertThat(malformed.ruleId()).isEqualTo("dev.influxdb.bad-line-protocol");
    assertThat(malformed.reason()).startsWith("Line 2: ");
    ObjectNode small = settings("3");
    small.putObject("advanced").put("maxWritePoints", 1);
    assertThat(connector.classify(call("influxdb_write", args("database", "metrics", "lines", two)),
        config(small, Env.DEV), policy(Env.DEV)).ruleId()).isEqualTo("dev.influxdb.too-many-points");
    assertThat(classify("3", "influxdb_write", args("database", "_internal", "lines", "m v=1")).ruleId())
        .isEqualTo("dev.influxdb.system-database");
    assertThat(classify("3", "influxdb_write", args("database", "metrics", "lines", "m v=1", "retentionPolicy",
        "autogen")).ruleId()).isEqualTo("dev.influxdb.bad-argument");
    assertThat(classify("1", "influxdb_write", args("database", "metrics", "lines", "m v=1", "retentionPolicy",
        "autogen")).kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(classify("3", "influxdb_write", args("database", "metrics", "lines", "m v=1", "precision", "m"))
        .ruleId()).isEqualTo("dev.influxdb.bad-argument");
    assertThat(classify("1", "influxdb_write", args("database", "metrics", "lines", "m v=1u")).ruleId())
        .isEqualTo("dev.influxdb.bad-line-protocol");
    assertThat(classify("3", "influxdb_write", args("database", "metrics", "lines", "m s=\""
        + "x".repeat(LineProtocol.MAX_BYTES) + "\"")).ruleId()).isEqualTo("dev.influxdb.write-too-large");
  }

  // ── The exact action ────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void theApprovalShowsTheExactRequestAndThePoints() {
    String lines = "cpu,host=web-1 usage=12.5 1730000000\ncpu,host=web-2 usage=7.25\n";
    ActionPreview p = connector.describeAction(call("influxdb_write", args("database", "metrics", "lines", lines,
        "precision", "s", "note", "backfill")), config("3"));
    assertThat(p.kind()).isEqualTo(ActionPreview.Kind.HTTP);
    assertThat(p.text()).isEqualTo("POST " + influx.url() + "/api/v3/write_lp?db=metrics&precision=second"
        + "&accept_partial=false\nContent-Type: text/plain; charset=utf-8\nAuthorization: ‹masked›\n\n"
        + "cpu,host=web-1 usage=12.5 1730000000\ncpu,host=web-2 usage=7.25");
    assertThat(p.text()).doesNotContain(TOKEN);
    assertThat(p.payload().toString()).isEqualTo("{\"database\":\"metrics\",\"precision\":\"s\",\"lines\":"
        + "\"cpu,host=web-1 usage=12.5 1730000000\\ncpu,host=web-2 usage=7.25\"}");
    Map<String, String> fields = new java.util.LinkedHashMap<>();
    p.fields().forEach(f -> fields.put(f.label(), f.value()));
    assertThat(fields).containsEntry("Database", "metrics").containsEntry("Points", "2")
        .containsEntry("Measurements", "cpu (2)").containsEntry("Precision", "s (seconds)");
    assertThat(fields.get("Time range")).isEqualTo("2024-10-27T03:33:20Z (and 1 point at the server's time)");
    assertThat(fields.get("Also sent by the gateway")).startsWith("Host, Content-Length: 62, User-Agent: GateRook/");
    assertThat(fields.get("Each point")).isEqualTo("cpu host=web-1 · usage=12.5 (float) · 2024-10-27T03:33:20Z\n"
        + "cpu host=web-2 · usage=7.25 (float) · the server's time");
    assertThat(p.warnings()).anyMatch(w -> w.startsWith("1 point without a timestamp"));
    assertThat(p.impact()).extracting(ActionPreview.Field::label).contains("Existing points", "All or nothing",
        "Retried");
    assertThat(p.target()).endsWith(" · metrics");

    // The other APIs: their own path and parameters.
    ActionPreview v2 = connector.describeAction(call("influxdb_write", args("database", "metrics", "lines",
        "m v=1 1", "precision", "us")), config("2"));
    assertThat(v2.text()).startsWith("POST " + influx.url() + "/api/v2/write?org=acme&bucket=metrics&precision=us\n");
    assertThat(v2.fields()).extracting(ActionPreview.Field::label).contains("Bucket", "Organization");
    ActionPreview v1 = connector.describeAction(call("influxdb_write", args("database", "metrics", "lines",
        "m v=1 1", "precision", "us", "retentionPolicy", "one week")), config("1"));
    assertThat(v1.text()).startsWith("POST " + influx.url() + "/write?db=metrics&rp=one%20week&precision=u\n");
    // A timestamp of seconds sent as nanoseconds lands in 1970: the approver is told.
    assertThat(connector.describeAction(call("influxdb_write", args("database", "metrics", "lines",
        "m v=1 1730000000")), config("3")).warnings()).anyMatch(w -> w.contains("check that ns is the unit"));
  }

  @Test
  void prepareSaysWhenInfluxDb3WouldCreateTheDatabase() {
    influx.on("GET /api/v3/configure/database", 200, "[{\"iox::database\":\"metrics\"}]");
    ToolCall typo = call("influxdb_write", args("database", "metrcs", "lines", "m v=1"));
    PreparedAction prepared = connector.prepare(typo, config("3"), secrets("3"), TestCtx.of());
    assertThat(prepared.outcome()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    assertThat(prepared.preview().warnings()).anyMatch(w -> w.startsWith("Database metrcs doesn't exist yet"));
    ToolCall known = call("influxdb_write", args("database", "metrics", "lines", "m v=1"));
    assertThat(connector.prepare(known, config("3"), secrets("3"), TestCtx.of()).preview().warnings())
        .noneMatch(w -> w.contains("doesn't exist yet"));
  }

  // ── Writes ──────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aWriteRunsOnlyApprovedAndSendsTheApprovedPoints() throws Exception {
    influx.on("POST /api/v3/write_lp", r -> new MockResponse.Builder().code(204).build());
    String lines = "cpu,host=web-1 usage=12.5 1730000000\ncpu,host=web-2 usage=7.25 1730000060";
    ToolCall write = call("influxdb_write", args("database", "metrics", "lines", lines, "precision", "s"));
    assertThatThrownBy(() -> connector.execute(write, config("3"), secrets("3"), TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    assertThat(influx.requests).isEmpty();

    TestCtx approved = TestCtx.of().approved(connector.describeAction(write, config("3")).payload());
    ToolResult done = connector.execute(write, config("3"), secrets("3"), approved);
    assertThat(done.structuredContent().path("written").asBoolean()).isTrue();
    assertThat(done.structuredContent().path("points").asInt()).isEqualTo(2);
    assertThat(done.content().getFirst().text()).isEqualTo("Wrote 2 points to metrics.");
    RecordedRequest sent = influx.to("POST /api/v3/write_lp").getFirst();
    assertThat(param(sent, "db")).isEqualTo("metrics");
    assertThat(param(sent, "precision")).isEqualTo("second");
    assertThat(param(sent, "accept_partial")).isEqualTo("false");
    assertThat(body(sent)).isEqualTo(lines);
    assertThat(sent.getHeaders().get("Authorization")).isEqualTo("Bearer " + TOKEN);
    assertThat(sent.getHeaders().get("Content-Type")).isEqualTo("text/plain; charset=utf-8");
    assertThat(approved.taints).contains(TOKEN);

    // What runs is the approved payload, whatever the call says now.
    influx.requests.clear();
    connector.execute(call("influxdb_write", args("database", "metrics", "lines", "evil v=1")), config("3"),
        secrets("3"), approved);
    assertThat(body(influx.to("POST /api/v3/write_lp").getFirst())).isEqualTo(lines);
    // An approved payload is read again: a database off the list doesn't run.
    assertThatThrownBy(() -> connector.execute(write, config("3", "telemetry"), secrets("3"), approved))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
  }

  @Test
  void eachApiGetsItsOwnWrite() throws Exception {
    influx.on("POST /api/v2/write", r -> new MockResponse.Builder().code(204).build())
        .on("POST /write", r -> new MockResponse.Builder().code(204).build());
    ToolCall v2 = call("influxdb_write", args("database", "metrics", "lines", "m v=1 1", "precision", "ms"));
    connector.execute(v2, config("2"), secrets("2"), TestCtx.of().approved(connector.describeAction(v2,
        config("2")).payload()));
    RecordedRequest sent2 = influx.to("POST /api/v2/write").getFirst();
    assertThat(param(sent2, "org")).isEqualTo("acme");
    assertThat(param(sent2, "bucket")).isEqualTo("metrics");
    assertThat(param(sent2, "precision")).isEqualTo("ms");
    assertThat(sent2.getHeaders().get("Authorization")).isEqualTo("Token " + TOKEN);

    ToolCall v1 = call("influxdb_write", args("database", "metrics", "lines", "m v=1i 1", "retentionPolicy",
        "autogen"));
    TestCtx ctx = TestCtx.of().approved(connector.describeAction(v1, config("1")).payload());
    connector.execute(v1, config("1"), secrets("1"), ctx);
    RecordedRequest sent1 = influx.to("POST /write").getFirst();
    assertThat(param(sent1, "db")).isEqualTo("metrics");
    assertThat(param(sent1, "rp")).isEqualTo("autogen");
    assertThat(param(sent1, "precision")).isEqualTo("ns");
    String basic = Base64.getEncoder().encodeToString(("reader:" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    assertThat(sent1.getHeaders().get("Authorization")).isEqualTo("Basic " + basic);
    assertThat(ctx.taints).contains(PASSWORD, basic);
  }

  @Test
  void aRefusedWriteSaysWhetherAnythingWasWritten() throws Exception {
    record Case(String version, String path, MockResponse answer, ConnectorException.Kind kind, String says) {}
    List<Case> cases = List.of(
        new Case("3", "POST /api/v3/write_lp", json(400, "{\"error\":\"line protocol parsing error\",\"data\":"
            + "{\"error_message\":\"invalid column type for column 'usage'\",\"line_number\":1,\"original_line\":"
            + "\"m\"}}"), ConnectorException.Kind.RESOURCE_ERROR, "nothing was written"),
        new Case("2", "POST /api/v2/write", json(422, "{\"code\":\"unprocessable entity\",\"message\":\"partial "
            + "write: field type conflict dropped=1\"}"), ConnectorException.Kind.OUTCOME_UNKNOWN,
            "part of the points"),
        new Case("1", "POST /write", json(400, "{\"error\":\"partial write: field type conflict dropped=1\"}"),
            ConnectorException.Kind.OUTCOME_UNKNOWN, "part of the points"),
        new Case("1", "POST /write", json(500, "{\"error\":\"timeout\"}"), ConnectorException.Kind.OUTCOME_UNKNOWN,
            "not known"),
        new Case("2", "POST /api/v2/write", json(403, "{\"code\":\"forbidden\",\"message\":\"insufficient "
            + "permissions for write\"}"), ConnectorException.Kind.RESOURCE_ERROR, "may not write"),
        new Case("1", "POST /write", json(404, "{\"error\":\"database not found: \\\"metrics\\\"\"}"),
            ConnectorException.Kind.BAD_INPUT, "doesn't exist"),
        new Case("3", "POST /api/v3/write_lp", new MockResponse.Builder()
            .onResponseStart(new SocketEffect.CloseSocket(true, false, false)).build(),
            ConnectorException.Kind.OUTCOME_UNKNOWN, "not known"));
    for (Case c : cases) {
      influx.on(c.path(), r -> c.answer());
      ToolCall write = call("influxdb_write", args("database", "metrics", "lines", "m v=1 1"));
      TestCtx approved = TestCtx.of().approved(connector.describeAction(write, config(c.version())).payload());
      assertThatThrownBy(() -> connector.execute(write, config(c.version()), secrets(c.version()), approved))
          .as(c.toString())
          .isInstanceOfSatisfying(ConnectorException.class, e -> {
            assertThat(e.kind()).as(e.getMessage()).isEqualTo(c.kind());
            assertThat(e.getMessage()).contains(c.says()).doesNotContain(TOKEN).doesNotContain(PASSWORD);
          });
    }
  }

  // ── Reads ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void sqlReadsArriveAsTypedRows() throws Exception {
    influx.on("POST /api/v3/query_sql", 200, String.join("\n",
        "{\"cores\":4,\"host\":\"web-1\",\"ok\":true,\"time\":\"2024-10-27T03:33:20\",\"usage\":12.5,"
            + "\"used\":9007199254740993}",
        "{\"host\":\"web-3\",\"time\":\"2024-10-27T03:35:20.123456789\",\"usage\":1.0}", ""));
    TestCtx ctx = TestCtx.of();
    ToolResult r = connector.execute(call("influxdb_query", args("database", "metrics", "query",
        "SELECT * FROM cpu WHERE host = $h", "parameters", Map.of("h", "web-1"))), config("3"), secrets("3"), ctx);
    JsonNode sent = jsonBody(influx.to("POST /api/v3/query_sql").getFirst());
    assertThat(sent.path("db").asString()).isEqualTo("metrics");
    assertThat(sent.path("q").asString()).isEqualTo("SELECT * FROM cpu WHERE host = $h");
    assertThat(sent.path("format").asString()).isEqualTo("jsonl");
    assertThat(sent.path("params").toString()).isEqualTo("{\"h\":\"web-1\"}");
    assertThat(influx.to("POST /api/v3/query_sql").getFirst().getHeaders().get("Authorization"))
        .isEqualTo("Bearer " + TOKEN);
    assertThat(ctx.taints).contains(TOKEN);

    assertThat(r.table().columns()).extracting(ToolResult.Column::name).containsExactly("cores", "host", "ok", "time",
        "usage", "used");
    assertThat(r.table().columns()).extracting(ToolResult.Column::type).containsExactly("integer", "string",
        "boolean", "timestamp", "float", "integer");
    JsonNode first = r.table().rows().get(0);
    assertThat(first.get(0).isIntegralNumber()).isTrue();
    assertThat(first.get(3).asString()).isEqualTo("2024-10-27T03:33:20Z");
    assertThat(first.get(4).asDouble()).isEqualTo(12.5);
    assertThat(first.get(5).isString()).isTrue();
    assertThat(first.get(5).asString()).isEqualTo("9007199254740993");
    JsonNode second = r.table().rows().get(1);
    assertThat(second.get(0).isNull()).isTrue();
    assertThat(second.get(3).asString()).isEqualTo("2024-10-27T03:35:20.123456789Z");
    assertThat(r.truncated()).isFalse();
    assertThat(r.structuredContent().path("rows").asInt()).isEqualTo(2);
  }

  @Test
  void rowsStopAtTheLimitTheResponseSizeAndTheCellSize() throws Exception {
    StringBuilder rows = new StringBuilder();
    for (int i = 0; i < 50; i++) {
      rows.append("{\"n\":").append(i).append(",\"note\":\"").append("x".repeat(400)).append("\"}\n");
    }
    influx.on("POST /api/v3/query_sql", 200, rows.toString());
    ToolResult limited = run("3", "influxdb_query", args("database", "metrics", "query", "SELECT * FROM t",
        "limit", 3));
    assertThat(limited.table().rows()).hasSize(3);
    assertThat(limited.truncated()).isTrue();
    assertThat(limited.truncatedReason()).isEqualTo("row_limit");
    assertThat(limited.structuredContent().path("hint").asString()).contains("narrow the time range");

    Limits small = new Limits(1_000, 1_000, 8 * 1024, 64 * 1024, 64 * 1024, 64 * 1024, 100, Duration.ofSeconds(5),
        Duration.ofSeconds(30), Duration.ofSeconds(10), null);
    ToolResult cut = connector.execute(call("influxdb_query", args("database", "metrics", "query", "SELECT * FROM t",
        "limit", 50)), config("3"), secrets("3"), TestCtx.of().with(small));
    assertThat(cut.truncatedReason()).isEqualTo("size_limit");
    assertThat(cut.table().rows().size()).isBetween(1, 49);
    assertThat(cut.table().rows().get(0).get(1).asString()).endsWith("…‹400 characters›").hasSizeLessThan(130);

    // The connection's own row limit caps the call's.
    ObjectNode capped = settings("3");
    capped.putObject("advanced").put("maxRows", 2);
    assertThat(connector.execute(call("influxdb_query", args("database", "metrics", "query", "SELECT * FROM t",
        "limit", 40)), config(capped, Env.DEV), secrets("3"), TestCtx.of()).table().rows()).hasSize(2);
  }

  @Test
  void influxqlOn1xAnd2xReadsChunkedSeries() throws Exception {
    influx.on("POST /query", 200, String.join("\n",
        "{\"results\":[{\"statement_id\":0,\"series\":[{\"name\":\"cpu\",\"tags\":{\"host\":\"web-1\"},"
            + "\"columns\":[\"time\",\"mean\"],\"values\":[[\"1970-01-01T00:00:00Z\",12.5]]}],\"partial\":true}]}",
        "{\"results\":[{\"statement_id\":0,\"series\":[{\"name\":\"cpu\",\"tags\":{\"host\":\"web-3\"},"
            + "\"columns\":[\"time\",\"mean\"],\"values\":[[\"1970-01-01T00:00:00Z\",1.5]]}]}]}"));
    ToolResult r = run("2", "influxdb_query", args("database", "metrics", "query",
        "SELECT mean(usage) FROM cpu GROUP BY host", "parameters", Map.of("n", 5)));
    RecordedRequest sent = influx.to("POST /query").getFirst();
    Map<String, String> f = form(sent);
    assertThat(f).containsEntry("db", "metrics").containsEntry("q", "SELECT mean(usage) FROM cpu GROUP BY host")
        .containsEntry("chunked", "true").containsEntry("chunk_size", "101").containsEntry("params", "{\"n\":5}");
    assertThat(sent.getHeaders().get("Content-Type")).startsWith("application/x-www-form-urlencoded");
    assertThat(sent.getHeaders().get("Authorization")).isEqualTo("Token " + TOKEN);
    assertThat(sent.getUrl().query()).isNull();
    assertThat(r.table().columns()).extracting(ToolResult.Column::name).containsExactly("measurement", "host",
        "time", "mean");
    assertThat(r.table().rows().toString()).isEqualTo("[[\"cpu\",\"web-1\",\"1970-01-01T00:00:00Z\",12.5],"
        + "[\"cpu\",\"web-3\",\"1970-01-01T00:00:00Z\",1.5]]");

    // One series without tags is the series' own columns.
    influx.on("POST /query", 200, "{\"results\":[{\"statement_id\":0,\"series\":[{\"name\":\"databases\","
        + "\"columns\":[\"name\"],\"values\":[[\"metrics\"],[\"hr\"],[\"_internal\"]]}]}]}");
    ToolResult dbs = run("1", "influxdb_query", args("query", "SHOW DATABASES"), "metrics");
    assertThat(dbs.table().columns()).extracting(ToolResult.Column::name).containsExactly("name");
    assertThat(dbs.table().rows().toString()).isEqualTo("[[\"metrics\"]]");
    String basic = Base64.getEncoder().encodeToString(("reader:" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    assertThat(influx.to("POST /query").getLast().getHeaders().get("Authorization")).isEqualTo("Basic " + basic);
    assertThat(form(influx.to("POST /query").getLast())).doesNotContainKey("db");
  }

  @Test
  void statementErrorsSayWhatToDo() {
    influx.on("POST /query", 200, "{\"results\":[{\"statement_id\":0,\"error\":\"database not found: metrcs\"}]}");
    assertThatThrownBy(() -> run("1", "influxdb_query", args("database", "metrcs", "query", "SELECT * FROM cpu")))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
          assertThat(e.getMessage()).contains("database not found: metrcs", "influxdb_list_databases");
        });
    influx.on("POST /query", 400, "{\"code\":\"invalid\",\"message\":\"failed to parse query: found EOF\"}");
    assertThatThrownBy(() -> run("2", "influxdb_query", args("database", "metrics", "query", "SELECT * FROM cpu")))
        .hasMessageContaining("failed to parse query: found EOF");
    influx.on("POST /api/v3/query_sql", r -> FakeInflux.text(500,
        "Schema error: No field named nope.\nValid fields are cpu.host, \u001b[31mcpu.usage"));
    assertThatThrownBy(() -> run("3", "influxdb_query", args("database", "metrics", "query", "SELECT nope FROM cpu")))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
          assertThat(e.getMessage()).contains("Schema error: No field named nope").doesNotContain("\u001b");
        });
    influx.on("POST /api/v3/query_sql", 401, "{\"error\": \"the request was not authenticated\"}");
    assertThatThrownBy(() -> run("3", "influxdb_query", args("database", "metrics", "query", "SELECT 1")))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE));
  }

  @Test
  void executeReadsTheStatementAgain() {
    assertThatThrownBy(() -> run("3", "influxdb_query", args("database", "metrics", "query", "DROP TABLE cpu")))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    assertThatThrownBy(() -> run("1", "influxdb_query", args("database", "metrics", "query",
        "SELECT * FROM \"hr\".\"autogen\".\"pay\""), "metrics"))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    assertThat(influx.requests).isEmpty();
  }

  @Test
  void aReadToolInApprovalRunsTheApprovedArguments() throws Exception {
    influx.on("POST /api/v3/query_sql", 200, "{\"x\":1}\n");
    ToolCall approvedCall = call("influxdb_query", args("database", "metrics", "query", "SELECT 1 AS x"));
    ActionPreview p = connector.describeAction(approvedCall, config("3"));
    assertThat(p.kind()).isEqualTo(ActionPreview.Kind.SQL);
    assertThat(p.text()).isEqualTo("SELECT 1 AS x");
    connector.execute(call("influxdb_query", args("database", "metrics", "query", "SELECT 2 AS x")), config("3"),
        secrets("3"), TestCtx.of().approved(p.payload()));
    assertThat(jsonBody(influx.to("POST /api/v3/query_sql").getFirst()).path("q").asString())
        .isEqualTo("SELECT 1 AS x");
  }

  // ── Lists and descriptions ─────────────────────────────────────────────────────────────────────────────────

  @Test
  void databasesAreListedWithinTheList() throws Exception {
    influx.on("GET /api/v3/configure/database", 200, "[{\"iox::database\":\"_internal\"},{\"iox::database\":"
        + "\"metrics\"},{\"iox::database\":\"hr\"},{\"iox::database\":\"iot_eu\",\"deleted\":true}]");
    ToolResult r = run("3", "influxdb_list_databases", "{}", "metrics", "iot_*");
    assertThat(r.structuredContent().path("databases").toString()).isEqualTo("[\"metrics\"]");
    assertThat(param(influx.to("GET /api/v3/configure/database").getFirst(), "format")).isEqualTo("json");

    // A resource token of InfluxDB 3 Enterprise may not use the configuration API: SHOW DATABASES answers.
    influx.on("GET /api/v3/configure/database", 403, "{\"error\":\"forbidden\"}")
        .on("POST /api/v3/query_influxql", 200, "{\"iox::database\":\"metrics\",\"deleted\":false}\n"
            + "{\"iox::database\":\"hr\",\"deleted\":false}\n");
    assertThat(run("3", "influxdb_list_databases", "{}").structuredContent().path("databases").toString())
        .isEqualTo("[\"metrics\",\"hr\"]");
    JsonNode show = jsonBody(influx.to("POST /api/v3/query_influxql").getFirst());
    assertThat(show.path("q").asString()).isEqualTo("SHOW DATABASES");
    assertThat(show.has("db")).isFalse();
  }

  @Test
  void measurementsPageWithSealedCursors() throws Exception {
    influx.on("POST /query", r -> {
      String q = form(r).get("q");
      return q.endsWith("OFFSET 0")
          ? json(200, "{\"results\":[{\"statement_id\":0,\"series\":[{\"name\":\"measurements\",\"columns\":"
              + "[\"name\"],\"values\":[[\"cpu\"],[\"disk\"],[\"mem\"]]}]}]}")
          : json(200, "{\"results\":[{\"statement_id\":0,\"series\":[{\"name\":\"measurements\",\"columns\":"
              + "[\"name\"],\"values\":[[\"mem\"]]}]}]}");
    });
    ToolResult first = run("2", "influxdb_list_measurements", args("database", "metrics", "limit", 2));
    assertThat(first.structuredContent().path("measurements").toString()).isEqualTo("[\"cpu\",\"disk\"]");
    String cursor = first.structuredContent().path("nextCursor").asString();
    assertThat(cursor).startsWith("ifx1.");
    assertThat(form(influx.to("POST /query").getFirst()).get("q")).isEqualTo("SHOW MEASUREMENTS LIMIT 3 OFFSET 0");
    ToolResult second = run("2", "influxdb_list_measurements", args("database", "metrics", "limit", 2, "cursor",
        cursor));
    assertThat(second.structuredContent().path("measurements").toString()).isEqualTo("[\"mem\"]");
    assertThat(second.structuredContent().has("nextCursor")).isFalse();
    assertThat(form(influx.to("POST /query").getLast()).get("q")).isEqualTo("SHOW MEASUREMENTS LIMIT 3 OFFSET 2");
    // A cursor belongs to its database and connection, and can't be made up.
    assertThatThrownBy(() -> run("2", "influxdb_list_measurements", args("database", "telemetry", "cursor", cursor)))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("another connection or request");
    assertThatThrownBy(() -> run("2", "influxdb_list_measurements", args("database", "metrics", "cursor",
        "ifx1.forged"))).isInstanceOf(ConnectorException.class);
    assertThat(Cursors.purpose(InfluxTools.LIST_MEASUREMENTS)).matches("[a-z][a-z0-9.-]{0,47}");

    influx.on("POST /api/v3/query_sql", 200, "{\"table_name\":\"cpu\"}\n");
    assertThat(run("3", "influxdb_list_measurements", args("database", "metrics")).structuredContent()
        .path("measurements").toString()).isEqualTo("[\"cpu\"]");
    assertThat(jsonBody(influx.to("POST /api/v3/query_sql").getFirst()).path("q").asString())
        .isEqualTo("SELECT table_name FROM information_schema.tables WHERE table_schema = 'iox' ORDER BY table_name "
            + "LIMIT 201 OFFSET 0");
  }

  @Test
  void aMeasurementIsDescribedWithItsTagsAndTypedFields() throws Exception {
    influx.on("POST /api/v3/query_influxql", r -> jsonBody(r).path("q").asString().startsWith("SHOW FIELD KEYS")
        ? json(200, "{\"iox::measurement\":\"my cpu\",\"fieldKey\":\"usage\",\"fieldType\":\"float\"}\n"
            + "{\"iox::measurement\":\"my cpu\",\"fieldKey\":\"cores\",\"fieldType\":\"integer\"}\n")
        : json(200, "{\"iox::measurement\":\"my cpu\",\"tagKey\":\"host\"}\n"));
    ToolResult d = run("3", "influxdb_describe", args("database", "metrics", "measurement", "my cpu"));
    assertThat(d.structuredContent().path("tags").toString()).isEqualTo("[\"host\"]");
    assertThat(d.structuredContent().path("fields").toString()).isEqualTo("[{\"name\":\"usage\",\"type\":\"float\"},"
        + "{\"name\":\"cores\",\"type\":\"integer\"}]");
    assertThat(d.content().getFirst().text()).isEqualTo("my cpu in metrics: tags host · fields usage float, cores "
        + "integer · time");
    List<String> sent = new ArrayList<>();
    influx.to("POST /api/v3/query_influxql").forEach(r -> sent.add(jsonBody(r).path("q").asString()));
    assertThat(sent).containsExactly("SHOW FIELD KEYS FROM \"my cpu\"", "SHOW TAG KEYS FROM \"my cpu\"");
  }

  // ── Test connection and health ──────────────────────────────────────────────────────────────────────────────

  @Test
  void testConnectionChecksTheServerAndTellsAnAdminToken() {
    influx.on("GET /ping", r -> ping("3.12.1", "Core"))
        .on("GET /api/v3/configure/database", 200, "[{\"iox::database\":\"_internal\"},{\"iox::database\":"
            + "\"metrics\"}]");
    TestResult t = connector.test(config(settings("3"), Env.STAGE), secrets("3"), TestCtx.of().env(Env.STAGE));
    assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
    assertThat(t.serverVersion()).isEqualTo("InfluxDB 3.12.1 Core");
    assertThat(t.checks()).extracting(TestResult.Check::name).containsExactly("server", "databases", "permissions");
    assertThat(t.checks().get(1).detail()).isEqualTo("2 databases agents may use: _internal, metrics");
    assertThat(t.permissions().level()).isEqualTo(TestResult.Level.ADMIN);
    assertThat(t.hints()).extracting(TestResult.Hint::code).contains("influxdb.read-boundary",
        "influxdb.admin-credentials", "influxdb.plain-http");
    assertThat(t.hints()).filteredOn(h -> h.code().equals("influxdb.admin-credentials"))
        .extracting(TestResult.Hint::severity).containsExactly(TestResult.Severity.WARNING);
    assertThat(t.metadataUpdate().path("family").asString()).isEqualTo("3");
    assertThat(influx.to("GET /ping").getFirst().getHeaders().get("Authorization")).isEqualTo("Bearer " + TOKEN);
  }

  @Test
  void testConnectionRefusesTheWrongServer() {
    influx.on("GET /ping", r -> ping("v2.9.1", "OSS"));
    TestResult t = connector.test(config("3"), secrets("3"), TestCtx.of());
    assertThat(t.ok()).isFalse();
    assertThat(t.error().code()).isEqualTo("influxdb.version");
    assertThat(t.error().message()).contains("InfluxDB 2.9.1 OSS", "choose InfluxDB 2.x or InfluxDB Cloud");
    influx.on("GET /ping", r -> ping("1.12.4", "OSS"));
    assertThat(connector.test(config("2"), secrets("2"), TestCtx.of()).error().message())
        .contains("choose InfluxDB 1.x");
    influx.on("GET /ping", 401, "{\"error\": \"the request was not authenticated\"}");
    assertThat(connector.test(config("3"), secrets("3"), TestCtx.of()).error().code())
        .isEqualTo("influxdb.credentials");
  }

  @Test
  void testConnectionTellsTokensAndUsersApart() {
    influx.on("GET /ping", r -> ping("v2.9.1", "OSS"))
        .on("POST /query", 200, "{\"results\":[{\"statement_id\":0,\"series\":[{\"name\":\"databases\",\"columns\":"
            + "[\"name\"],\"values\":[[\"metrics\"]]}]}]}")
        .on("GET /api/v2/users", 200, "{\"users\":[{\"id\":\"1\",\"name\":\"admin\"}]}");
    TestResult admin = connector.test(config("2"), secrets("2"), TestCtx.of());
    assertThat(admin.ok()).as(String.valueOf(admin.error())).isTrue();
    assertThat(admin.permissions().level()).isEqualTo(TestResult.Level.ADMIN);
    influx.on("GET /api/v2/users", 200, "{\"users\":[]}");
    TestResult scoped = connector.test(config("2"), secrets("2"), TestCtx.of());
    assertThat(scoped.permissions().level()).isEqualTo(TestResult.Level.UNKNOWN);
    assertThat(scoped.hints()).extracting(TestResult.Hint::code).doesNotContain("influxdb.admin-credentials");
    ObjectNode noOrg = settings("2");
    noOrg.remove("organization");
    assertThat(connector.test(config(noOrg, Env.DEV), secrets("2"), TestCtx.of()).hints())
        .extracting(TestResult.Hint::code).contains("influxdb.organization");

    influx.on("GET /ping", r -> ping("1.12.4", "OSS"))
        .on("POST /query", r -> form(r).get("q").equals("SHOW USERS")
            ? json(200, "{\"error\":\"error authorizing query: reader not authorized to execute statement 'SHOW "
                + "USERS', requires admin privilege\"}")
            : json(200, "{\"results\":[{\"statement_id\":0,\"series\":[{\"name\":\"databases\",\"columns\":[\"name\"],"
                + "\"values\":[[\"metrics\"]]}]}]}"));
    TestResult reader = connector.test(config("1"), secrets("1"), TestCtx.of());
    assertThat(reader.ok()).as(String.valueOf(reader.error())).isTrue();
    assertThat(reader.identity()).isEqualTo("reader");
    assertThat(reader.permissions().level()).isEqualTo(TestResult.Level.UNKNOWN);
    assertThat(reader.permissions().read()).contains("isn't an admin user");
  }

  @Test
  void testConnectionSaysWhenTheServerCantBeReached() throws Exception {
    String url = influx.url();
    influx.close();
    ObjectNode gone = settings("3");
    gone.put("url", url);
    TestResult t = connector.test(config(gone, Env.DEV), secrets("3"), TestCtx.of());
    assertThat(t.ok()).isFalse();
    assertThat(t.error().code()).isEqualTo("influxdb.unreachable");
    assertThat(t.error().message()).contains("Can't connect to 127.0.0.1");
  }

  @Test
  void redirectsAreNeverFollowed() throws Exception {
    try (FakeInflux elsewhere = new FakeInflux()) {
      elsewhere.on("GET /", r -> ping("3.12.1", "Core"));
      influx.on("GET /ping", r -> new MockResponse.Builder().code(302).addHeader("Location", elsewhere.url()
          + "/ping").build());
      TestResult t = connector.test(config("3"), secrets("3"), TestCtx.of());
      assertThat(t.ok()).isFalse();
      assertThat(t.error().message()).contains("302");
      assertThat(elsewhere.requests).isEmpty();
    }
  }

  @Test
  void healthUsesPingOrHealth() {
    influx.on("GET /health", 200, "OK").on("GET /ping", r -> ping("1.12.4", "OSS"));
    assertThat(connector.health(config("3"), secrets("3"), TestCtx.of()).state()).isEqualTo(HealthResult.State.HEALTHY);
    assertThat(connector.health(config("1"), secrets("1"), TestCtx.of()).state()).isEqualTo(HealthResult.State.HEALTHY);
    assertThat(influx.to("GET /health")).hasSize(1);
    influx.on("GET /health", 503, "{\"status\":\"fail\"}");
    assertThat(connector.health(config("2"), secrets("2"), TestCtx.of()).state())
        .isEqualTo(HealthResult.State.UNREACHABLE);
  }

  @Test
  void aMissingTokenIsSaidWithoutAnyRequest() {
    assertThatThrownBy(() -> connector.execute(call("influxdb_list_databases", "{}"), config("3"), new TestSecrets(),
        TestCtx.of())).hasMessageContaining("no stored API token");
    assertThat(influx.requests).isEmpty();
  }

  @Test
  void describeConnectionNeedsNoServer() throws Exception {
    JsonNode d = connector.describe(config("2", "metrics"), secrets("2"), TestCtx.of(), "cpu").orElseThrow();
    assertThat(d.path("server").asString()).isEqualTo("InfluxDB 2.x");
    assertThat(d.path("databases").toString()).isEqualTo("[\"metrics\"]");
    assertThat(d.path("measurement").asString()).contains("influxdb_describe");
    assertThat(influx.requests).isEmpty();
  }
}
