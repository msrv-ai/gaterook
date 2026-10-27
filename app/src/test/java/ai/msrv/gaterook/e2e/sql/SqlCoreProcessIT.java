package ai.msrv.gaterook.e2e.sql;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.e2e.platform.CoreProcess;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;

/**
 * The SQL connectors in the core as a process, through the admin API the window uses and MCP over HTTP, against a
 * local PostgreSQL 16 (ARCHITECTURE.md §10 C1). On the JVM it runs {@code app.jar}; with
 * {@code -Dgateway.core.binary=<path>} it is the native touch of the SQL connectors (decision 2: every library works
 * in the native image): pgjdbc, Connector/J, HikariCP and JSqlParser as the image has them — Test connection with the
 * probes and the {@code pg_catalog} snapshot, a read in its READ ONLY transaction, a function that isn't built in
 * stopped by the database after approval, a change with its EXPLAIN estimate, a template with bound values, and the
 * MySQL driver refused a connection.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SqlCoreProcessIT {

  private static final Duration READY = Duration.ofSeconds(90);
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private static LocalPg pg;
  private static CoreProcess core;
  private static String token;

  @BeforeAll
  static void start() throws Exception {
    pg = LocalPg.startIfAvailable();
    Assumptions.assumeTrue(pg != null, "No PostgreSQL binaries: set GW_PG_BIN or install postgresql-16");
    core = CoreProcess.start(CoreProcess.newHome("sql"), CoreProcess.freePort(), List.of()).bootstrap();
    List<JsonNode> lines = core.untilReady(READY);
    assertThat(lines.getLast().path("ready").asBoolean()).as(core.stderr()).isTrue();
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

  private static String config(String user) {
    return "{\"connection\":{\"host\":\"127.0.0.1\",\"port\":" + pg.port + ",\"database\":\"app\","
        + "\"sslMode\":\"disable\"},\"credentials\":{\"user\":\"" + user + "\"}}";
  }

  private static String secrets(String password) {
    return "{\"/credentials/password\":{\"source\":\"local\",\"value\":\"" + password + "\"}}";
  }

  // ── Test connection ─────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(1)
  void testConnectionSaysWhatTheUserMayDo() throws Exception {
    JsonNode tested = json(admin("POST", "/api/connections/test", "{\"type\":\"postgres\",\"env\":\"dev\",\"config\":"
        + config("gw_rw") + ",\"secrets\":" + secrets(LocalPg.password("gw_rw")) + "}"), 200);

    assertThat(tested.path("ok").asBoolean()).as(tested.toString()).isTrue();
    assertThat(tested.path("serverVersion").asString()).startsWith("PostgreSQL 16.");
    assertThat(tested.path("identity").asString()).isEqualTo("gw_rw");
    assertThat(tested.at("/permissions/level").asString()).isEqualTo("write");
    assertThat(tested.path("hints").findValuesAsString("code")).contains("sql.user-can-write");
    assertThat(tested.toString()).doesNotContain(LocalPg.password("gw_rw"));
  }

  @Test
  @Order(2)
  void theMysqlDriverReportsARefusedConnection() throws Exception {
    JsonNode tested = json(admin("POST", "/api/connections/test", "{\"type\":\"mysql\",\"env\":\"dev\",\"config\":"
        + "{\"connection\":{\"host\":\"127.0.0.1\",\"port\":" + CoreProcess.freePort() + ",\"sslMode\":\"disable\"},"
        + "\"credentials\":{\"user\":\"gw_reader\"}},\"secrets\":" + secrets("mysql-e2e-pw-0001") + "}"), 200);

    assertThat(tested.path("ok").asBoolean()).isFalse();
    assertThat(tested.at("/error/code").asString()).as(tested.toString()).isEqualTo("sql.refused");
  }

  // ── Setup ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(3)
  void aConnectionAnEndpointAndAToken() throws Exception {
    json(admin("POST", "/api/connections", "{\"name\":\"dev-db\",\"type\":\"postgres\",\"env\":\"dev\",\"config\":"
        + config("gw_rw") + ",\"secrets\":" + secrets(LocalPg.password("gw_rw")) + "}"), 201);
    // The stored test fills the metadata classify needs: the server's label and its built-in functions.
    JsonNode stored = json(admin("POST", "/api/connections/dev-db/test", null), 200);
    assertThat(stored.path("ok").asBoolean()).as(stored.toString()).isTrue();

    json(admin("POST", "/api/endpoints", "{\"slug\":\"sql\",\"startFrom\":\"empty\"}"), 201);
    json(admin("POST", "/api/endpoints/sql/connections/dev-db", "{}"), 200);
    JsonNode settings = json(admin("GET", "/api/endpoints/sql/settings", null), 200);
    json(admin("PUT", "/api/endpoints/sql/settings", "{\"approvalWait\":{\"firstWaitSeconds\":0},\"version\":"
        + settings.path("version").asLong() + "}"), 200);
    token = json(admin("POST", "/api/endpoints/sql/tokens", "{\"name\":\"sql-e2e\",\"ttlDays\":1}"), 201)
        .path("token").asString();
    assertThat(token).startsWith("gwt_");
  }

  // ── MCP ─────────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(4)
  void aReadRunsInItsReadOnlyTransaction() throws Exception {
    JsonNode tools = mcp("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}").at("/result/tools");
    assertThat(names(tools)).contains("sql_list_schemas", "sql_describe_table", "sql_query",
        "sql_execute", "approval_status");
    for (JsonNode t : tools) {
      if (t.path("name").asString().equals("sql_query")) {
        assertThat(t.path("description").asString()).contains("dev-db (PostgreSQL 16, dev)");
      }
    }

    JsonNode r = call(2, "sql_query", "{\"connection\":\"dev-db\",\"sql\":\"SELECT id, status, total FROM orders "
        + "ORDER BY id\"}");
    assertThat(r.at("/result/isError").asBoolean(true)).as(r.toString()).isFalse();
    assertThat(r.at("/result/structuredContent/rows").toString())
        .isEqualTo("[[48213,\"pending\",\"120.50\"],[48240,\"pending\",\"9.99\"],[48241,\"shipped\",\"42.00\"]]");

    JsonNode schemas = call(3, "sql_list_schemas", "{\"connection\":\"dev-db\"}");
    assertThat(schemas.at("/result/structuredContent/tables").findValuesAsString("name"))
        .as(schemas.toString()).contains("orders", "customers", "audit_log");
    JsonNode table = call(4, "sql_describe_table", "{\"connection\":\"dev-db\",\"table\":\"orders\"}");
    assertThat(table.at("/result/structuredContent/primaryKey").toString()).as(table.toString())
        .isEqualTo("[\"id\"]");

    JsonNode write = call(5, "sql_query", "{\"connection\":\"dev-db\",\"sql\":\"DELETE FROM orders\"}");
    assertThat(write.at("/result/content/0/text").asString()).startsWith("Denied by policy dev.sql.query-writes");
    assertThat(pg.count("SELECT count(*) FROM orders")).isEqualTo(3);
  }

  @Test
  @Order(5)
  void aFunctionThatIsntBuiltInIsApprovedAndStillCantWrite() throws Exception {
    long before = pg.count("SELECT count(*) FROM audit_log");
    JsonNode pending = call(6, "sql_query", "{\"connection\":\"dev-db\",\"sql\":\"SELECT f_write()\"}");
    String handle = pending.at("/result/structuredContent/handle").asString();
    assertThat(handle).as(pending.toString()).startsWith("ap_");

    approve(handle);
    JsonNode status = call(7, "approval_status", "{\"handle\":\"" + handle + "\",\"wait_seconds\":20}");

    assertThat(status.at("/result/structuredContent/status").asString()).as(status.toString()).isEqualTo("failed");
    assertThat(status.toString()).contains("read-only transaction refused it");
    assertThat(pg.count("SELECT count(*) FROM audit_log")).isEqualTo(before);
  }

  @Test
  @Order(6)
  void aChangeRunsAsApprovedWithItsEstimate() throws Exception {
    JsonNode pending = call(8, "sql_execute", "{\"connection\":\"dev-db\",\"sql\":\"UPDATE orders SET status = "
        + "'cancelled' WHERE id = 48213\"}");
    String handle = pending.at("/result/structuredContent/handle").asString();
    assertThat(handle).as(pending.toString()).startsWith("ap_");
    JsonNode detail = json(admin("GET", "/api/approvals/" + handle, null), 200);
    assertThat(detail.toString()).contains("Estimated rows").contains("1 (from EXPLAIN)");

    approve(handle);
    JsonNode status = call(9, "approval_status", "{\"handle\":\"" + handle + "\",\"wait_seconds\":20}");

    assertThat(status.at("/result/structuredContent/status").asString()).as(status.toString())
        .isEqualTo("completed");
    assertThat(status.at("/result/content/0/text").asString()).contains("1 row updated");
    assertThat(pg.count("SELECT count(*) FROM orders WHERE id = 48213 AND status = 'cancelled'")).isEqualTo(1);
  }

  @Test
  @Order(7)
  void theKitsTemplateIsAReadOnlyToolWithBoundValues() throws Exception {
    JsonNode saved = json(admin("POST", "/api/templates", """
        {"kind":"sql-query","connection":"dev-db","definition":{"name":"orders_by_status",
         "description":"Orders with one status created after a point in time, newest first.",
         "sql":"SELECT id, status FROM orders WHERE status = :status AND created_at >= :since ORDER BY id",
         "params":[{"name":"status","type":"enum","values":["pending","paid","shipped","cancelled"]},
                   {"name":"since","type":"timestamp","required":false,"default":"-P7D"}]}}"""), 201);
    assertThat(saved.at("/compile/ok").asBoolean()).as(saved.toString()).isTrue();

    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    List<String> names = List.of();
    while (!names.contains("orders_by_status") && System.nanoTime() < deadline) {
      names = names(mcp("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/list\",\"params\":{}}")
          .at("/result/tools"));
      if (!names.contains("orders_by_status")) {
        Thread.sleep(200);
      }
    }
    assertThat(names).contains("orders_by_status");

    JsonNode r = call(11, "orders_by_status", "{\"status\":\"pending\"}");
    assertThat(r.at("/result/isError").asBoolean(true)).as(r.toString()).isFalse();
    assertThat(r.at("/result/structuredContent/rows").toString()).isEqualTo("[[48240,\"pending\"]]");
    JsonNode injected = call(12, "orders_by_status", "{\"status\":\"pending' OR '1'='1\"}");
    assertThat(injected.at("/result/isError").asBoolean()).as(injected.toString()).isTrue();
  }

  // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  /** The names of a {@code tools/list} answer. */
  private static List<String> names(JsonNode tools) {
    List<String> out = new ArrayList<>();
    tools.forEach(t -> out.add(t.path("name").asString()));
    return out;
  }

  private static void approve(String handle) throws Exception {
    JsonNode detail = json(admin("GET", "/api/approvals/" + handle, null), 200);
    JsonNode decided = json(admin("POST", "/api/approvals/" + handle + "/decision", "{\"decision\":\"approve\","
        + "\"digest\":\"" + detail.path("digest").asString() + "\",\"via\":\"app\"}"), 200);
    assertThat(decided.path("status").asString()).isIn("running", "completed", "failed");
  }

  private record Answer(int status, String body) {}

  private static URI uri(String target) {
    return URI.create("http://127.0.0.1:" + core.port + target);
  }

  /** A request of the window: signed as Electron's main process signs it. */
  private static Answer admin(String method, String target, String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(uri(target)).timeout(Duration.ofSeconds(30))
        .header("X-Gateway-Auth", core.secret().sign(method, target, System.currentTimeMillis(), nonce()))
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    if (body != null) {
      b.header("Content-Type", "application/json");
    }
    HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    return new Answer(r.statusCode(), r.body());
  }

  private static JsonNode json(Answer answer, int status) {
    assertThat(answer.status()).as(answer.body()).isEqualTo(status);
    return CoreProcess.JSON.readTree(answer.body());
  }

  /** One JSON-RPC message of a legacy (2025-11-25) client with the endpoint's token. */
  private static JsonNode mcp(String message) throws Exception {
    HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(uri("/mcp/sql"))
        .timeout(Duration.ofSeconds(40)).header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", "2025-11-25")
        .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.ofString(message)).build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    return CoreProcess.JSON.readTree(r.body());
  }

  private static JsonNode call(int id, String tool, String arguments) throws Exception {
    return mcp("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
        + "\",\"arguments\":" + arguments + "}}");
  }

  private static String nonce() {
    byte[] bytes = new byte[16];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
