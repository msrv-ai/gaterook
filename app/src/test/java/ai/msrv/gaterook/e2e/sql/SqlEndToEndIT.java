package ai.msrv.gaterook.e2e.sql;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.GatewayApplication;
import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.core.approvals.ApprovalView;
import ai.msrv.gaterook.core.connections.ConnectionService;
import ai.msrv.gaterook.core.connections.ConnectionTester;
import ai.msrv.gaterook.core.connections.SecretInput;
import ai.msrv.gaterook.core.endpoints.EndpointConnections;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshotService;
import ai.msrv.gaterook.core.endpoints.TokenService;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.platform.boot.GatewayLauncher;
import ai.msrv.gaterook.core.templates.TemplateService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The SQL connector through the assembled core and MCP over HTTP, against a local PostgreSQL 16 (ARCHITECTURE.md §10
 * C1, §9 "SQL reads cannot write"): tool descriptions name the server Test connection found, rows come back (masked on
 * prod), a read that calls a function that isn't built in waits for a person on dev and still can't write once
 * approved, the same call is denied on prod, a change runs as approved with its EXPLAIN estimate, and the kit's
 * template {@code orders_by_status} is a Read only tool.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SqlEndToEndIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String LAUNCH_SECRET = Base64.getUrlEncoder().withoutPadding()
      .encodeToString("sql-end-to-end-it-launch-secret0".getBytes(StandardCharsets.US_ASCII));
  private static final AtomicInteger IDS = new AtomicInteger(1);

  private static LocalPg pg;
  private static ConfigurableApplicationContext core;
  private static ApprovalService approvals;
  private static int port;
  private static String devToken;
  private static String prodToken;

  @BeforeAll
  static void start() throws Exception {
    pg = LocalPg.startIfAvailable();
    Assumptions.assumeTrue(pg != null, "No PostgreSQL binaries: set GW_PG_BIN or install postgresql-16");
    Path home = Files.createTempDirectory(Files.createDirectories(Path.of("target", "core-homes")
        .toAbsolutePath()), "sql-");
    core = new SpringApplicationBuilder(GatewayApplication.class)
        .contextFactory(GatewayLauncher.contexts(true))
        .run("--gateway.home=" + home, "--gateway.dev=true", "--gateway.dev.launch-secret=" + LAUNCH_SECRET,
            "--server.port=0", "--gateway.connections.health-interval=0s");
    approvals = core.getBean(ApprovalService.class);
    port = core.getBean(LocalListener.class).port();

    ConnectionService connections = core.getBean(ConnectionService.class);
    connections.create("dev-db", "postgres", Env.DEV, config("gw_rw"), Map.of("/credentials/password",
        SecretInput.local(LocalPg.password("gw_rw").toCharArray())), null);
    connections.create("prod-db", "postgres", Env.PROD, config("gw_ro"), Map.of("/credentials/password",
        SecretInput.local(LocalPg.password("gw_ro").toCharArray())), "e2e");
    ConnectionTester tester = core.getBean(ConnectionTester.class);
    for (String name : new String[] {"dev-db", "prod-db"}) {
      ConnectionTester.Outcome tested = tester.testStored(name);
      assertThat(tested.result().ok()).as(String.valueOf(tested.result().error())).isTrue();
    }

    EndpointService endpoints = core.getBean(EndpointService.class);
    EndpointConnections wiring = core.getBean(EndpointConnections.class);
    TokenService tokens = core.getBean(TokenService.class);
    for (String[] e : new String[][] {{"dev", "dev-db"}, {"prod", "prod-db"}}) {
      String reason = e[0].equals("prod") ? "e2e" : null;
      endpoints.create(e[0], null, EndpointService.StartFrom.EMPTY, reason);
      wiring.add(e[0], e[1], reason);
      endpoints.updateSettings(e[0], new EndpointService.SettingsUpdate(null, null, null, null, null,
          JSON.createObjectNode().put("firstWaitSeconds", 0), null), endpoints.get(e[0]).version(), reason);
    }
    devToken = tokens.issue("dev", "e2e-agent", 7, null).token();
    prodToken = tokens.issue("prod", "e2e-agent", 7, "e2e").token();
  }

  @AfterAll
  static void stop() throws Exception {
    if (core != null) {
      core.close();
    }
    if (pg != null) {
      pg.close();
    }
  }

  private static JsonNode config(String user) {
    return JSON.readTree("{\"connection\":{\"host\":\"127.0.0.1\",\"port\":" + pg.port + ",\"database\":\"app\","
        + "\"sslMode\":\"disable\"},\"credentials\":{\"user\":\"" + user + "\"}}");
  }

  // ── MCP ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static JsonNode mcp(String slug, String token, String method, ObjectNode params) throws Exception {
    ObjectNode body = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", IDS.getAndIncrement())
        .put("method", method);
    body.set("params", params);
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp/" + slug))
        .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream").header("Authorization", "Bearer " + token)
        .header("MCP-Protocol-Version", "2025-11-25").POST(HttpRequest.BodyPublishers.ofString(body.toString()))
        .build();
    HttpResponse<String> r = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    return JSON.readTree(r.body());
  }

  private static JsonNode call(String slug, String tool, ObjectNode arguments) throws Exception {
    ObjectNode params = JSON.createObjectNode().put("name", tool);
    params.set("arguments", arguments);
    return mcp(slug, slug.equals("prod") ? prodToken : devToken, "tools/call", params).path("result");
  }

  private static ObjectNode sql(String connection, String sql) {
    return JSON.createObjectNode().put("connection", connection).put("sql", sql);
  }

  private static ApprovalView approve(JsonNode pending) throws InterruptedException {
    String handle = pending.at("/structuredContent/handle").asString();
    assertThat(handle).as(pending.toString()).startsWith("ap_");
    ApprovalView a = approvals.find(handle).orElseThrow();
    approvals.decide(handle, ApprovalService.Decision.APPROVE, "app", null, a.digest());
    await(() -> approvals.find(handle).orElseThrow().terminal());
    return approvals.find(handle).orElseThrow();
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (!condition.getAsBoolean()) {
      assertThat(System.nanoTime()).as("condition within 20 s").isLessThan(until);
      Thread.sleep(50);
    }
  }

  // ── The pipeline ────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(1)
  void toolDescriptionsNameTheServerThatTestConnectionFound() throws Exception {
    JsonNode tools = mcp("dev", devToken, "tools/list", JSON.createObjectNode()).at("/result/tools");
    JsonNode query = null;
    for (JsonNode t : tools) {
      if (t.path("name").asString().equals("sql_query")) {
        query = t;
      }
    }
    assertThat(query).as(tools.toString()).isNotNull();
    assertThat(query.path("description").asString()).contains("dev-db (PostgreSQL 16, dev)");
  }

  @Test
  @Order(2)
  void aReadRunsAtOnce() throws Exception {
    JsonNode r = call("dev", "sql_query", sql("dev-db", "SELECT id, email, name FROM customers ORDER BY id"));

    assertThat(r.path("isError").asBoolean()).as(r.toString()).isFalse();
    assertThat(r.at("/structuredContent/rowCount").asInt()).isEqualTo(2);
    assertThat(r.at("/structuredContent/types").toString()).isEqualTo("[\"int8\",\"text\",\"text\"]");
    assertThat(r.at("/structuredContent/rows/0/0").asLong()).isEqualTo(1);
    assertThat(r.at("/structuredContent/rows/0/2").asString()).isEqualTo("Ada");
    assertThat(r.at("/content/0/text").asString()).startsWith("2 rows · ");
  }

  @Test
  @Order(3)
  void onProdTheRowsComeBackMasked() throws Exception {
    // prod masks e-mail addresses by default (the masking policy), here in a column the connector named.
    JsonNode r = call("prod", "sql_query", sql("prod-db", "SELECT id, email, name FROM customers ORDER BY id"));

    assertThat(r.path("isError").asBoolean()).as(r.toString()).isFalse();
    assertThat(r.at("/structuredContent/rows/0/2").asString()).isEqualTo("Ada");
    assertThat(r.toString()).doesNotContain("ada@example.com").doesNotContain("bob@example.com");
  }

  @Test
  @Order(4)
  void aWriteInSqlQueryIsDeniedBeforeAnythingRuns() throws Exception {
    JsonNode r = call("dev", "sql_query", sql("dev-db", "WITH d AS (DELETE FROM orders RETURNING *) SELECT * FROM d"));

    assertThat(r.path("isError").asBoolean()).isTrue();
    assertThat(r.at("/content/0/text").asString()).startsWith("Denied by policy dev.sql.query-writes");
    assertThat(pg.count("SELECT count(*) FROM orders")).isEqualTo(3);
  }

  @Test
  @Order(5)
  void aFunctionThatIsntBuiltInWaitsForAPersonOnDevAndStillCantWrite() throws Exception {
    long before = pg.count("SELECT count(*) FROM audit_log");

    JsonNode pending = call("dev", "sql_query", sql("dev-db", "SELECT f_write()"));
    assertThat(pending.at("/structuredContent/status").asString()).as(pending.toString()).isEqualTo("pending");
    ApprovalView request = approvals.find(pending.at("/structuredContent/handle").asString()).orElseThrow();
    assertThat(request.action().text()).isEqualTo("SELECT f_write()");
    assertThat(request.action().warnings()).anySatisfy(w -> assertThat(w).contains("f_write"));
    ApprovalView done = approve(pending);

    assertThat(done.status()).isEqualTo("failed");
    assertThat(done.error()).contains("read-only transaction refused it");
    assertThat(pg.count("SELECT count(*) FROM audit_log")).isEqualTo(before);
  }

  @Test
  @Order(6)
  void theSameCallIsDeniedOnProd() throws Exception {
    JsonNode r = call("prod", "sql_query", sql("prod-db", "SELECT f_write()"));

    assertThat(r.path("isError").asBoolean()).isTrue();
    assertThat(r.at("/content/0/text").asString()).startsWith("Denied by policy prod.sql.function-not-built-in");
  }

  @Test
  @Order(7)
  void aChangeRunsAsApprovedWithItsEstimate() throws Exception {
    JsonNode pending = call("dev", "sql_execute", sql("dev-db",
        "UPDATE orders SET status = 'cancelled' WHERE id = 48240"));
    ApprovalView request = approvals.find(pending.at("/structuredContent/handle").asString()).orElseThrow();
    assertThat(request.action().text()).isEqualTo("UPDATE orders SET status = 'cancelled' WHERE id = 48240");
    assertThat(request.action().impact()).contains(new ActionPreview.Field("Target table", "orders"),
        new ActionPreview.Field("Estimated rows", "1 (from EXPLAIN)"));
    assertThat(pg.count("SELECT count(*) FROM orders WHERE id = 48240 AND status = 'pending'")).isEqualTo(1);

    ApprovalView done = approve(pending);

    assertThat(done.status()).as(String.valueOf(done.error())).isEqualTo("completed");
    assertThat(pg.count("SELECT count(*) FROM orders WHERE id = 48240 AND status = 'cancelled'")).isEqualTo(1);
    JsonNode status = call("dev", "approval_status", JSON.createObjectNode().put("handle", done.id()));
    assertThat(status.at("/content/0/text").asString()).contains("1 row updated");
  }

  @Test
  @Order(8)
  void ordersByStatusIsAReadOnlyToolOfItsConnection() throws Exception {
    TemplateService templates = core.getBean(TemplateService.class);
    TemplateService.SaveResult saved = templates.create("sql-query", "dev-db", null, JSON.readTree("""
        {"name": "orders_by_status",
         "description": "Orders with one status created after a point in time, newest first. Returns at most 200 rows.",
         "sql": "SELECT id, status, total, created_at\\nFROM orders\\nWHERE status = :status\\n  AND created_at >= :since\\nORDER BY created_at DESC\\nLIMIT 200",
         "params": [
           {"name": "status", "type": "enum", "values": ["pending", "paid", "shipped", "cancelled"],
            "description": "Order status to match"},
           {"name": "since", "type": "timestamp", "required": false, "default": "-PT24H",
            "description": "Earliest created_at, ISO 8601"}]}
        """), null, null);
    assertThat(saved.compile().errors()).isEmpty();
    EndpointSnapshotService snapshots = core.getBean(EndpointSnapshotService.class);
    await(() -> snapshots.bySlug("dev").orElseThrow().pairs().stream()
        .anyMatch(p -> p.tool().name().equals("orders_by_status") && p.on()));

    JsonNode tools = mcp("dev", devToken, "tools/list", JSON.createObjectNode()).at("/result/tools");
    JsonNode tool = null;
    for (JsonNode t : tools) {
      if (t.path("name").asString().equals("orders_by_status")) {
        tool = t;
      }
    }
    assertThat(tool).as(tools.toString()).isNotNull();
    assertThat(tool.at("/annotations/readOnlyHint").asBoolean()).isTrue();
    assertThat(tool.at("/inputSchema/properties/since/description").asString())
        .isEqualTo("Earliest created_at, ISO 8601. Default: 24 hours ago");
    assertThat(tool.at("/inputSchema/properties/connection").isMissingNode()).isTrue();

    JsonNode r = call("dev", "orders_by_status", JSON.createObjectNode().put("status", "shipped"));

    assertThat(r.path("isError").asBoolean()).as(r.toString()).isFalse();
    assertThat(r.at("/structuredContent/rowCount").asInt()).isEqualTo(1);
    assertThat(r.at("/structuredContent/rows/0/0").asLong()).isEqualTo(48241);
  }
}
