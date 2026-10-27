package ai.msrv.gaterook.e2e.nativeimage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.e2e.platform.CoreProcess;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;

/**
 * The native smoke (ARCHITECTURE.md §2.4 and §10 M1.7, report 15 §6): steps 1–4, 6 and 7 against one core process,
 * plus the public listener and the warm start.
 * <ol>
 *   <li>The core on an empty home: ready line, {@code /api/health}.</li>
 *   <li>Admin API as the window uses it: a connection with a stored secret, an endpoint, a template, a token.</li>
 *   <li>MCP: {@code initialize} → {@code tools/list} → a read through the SQL template → a mutation → its approval
 *       request → the person's decision → {@code approval_status} → the next read sees the change.</li>
 *   <li>The event stream carries the request, its decision and the calls.</li>
 *   <li>(The per-connector touch calls against fakes come with the connectors, step N1.)</li>
 *   <li>The gateway database: the audit is one hash chain and refuses an {@code UPDATE}; the request ran once.</li>
 *   <li>No {@code Missing*Registration} or {@code UnsupportedFeatureError} in the log.</li>
 * </ol>
 * The public listener (§7.3): outside its allowlist everything is 404, the published endpoint without a token is 401,
 * and with the tunnel off it is 404 as well. Last, warm starts on the filled home: exec → ready line, in the native
 * image a median of {@code gateway.smoke.warmStartRuns} (21) starts of at most {@code gateway.smoke.warmStartBudgetMs}
 * (500 ms).
 *
 * <p>The connector is the core's {@code fake-sql} (switched on with {@code gateway.test.fake-connector}) and the
 * tunnel is pretended with {@code gateway.test.tunnel-host}: the built-in connectors are still skeletons, there is no
 * tunnel before step K3, and a release build would ignore both switches. On the JVM ({@code app.jar}) this is the
 * end-to-end run of the assembled core; with {@code -Dgateway.core.binary=<path>} it is the native smoke.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NativeSmokeIT {

  private static final Duration READY = Duration.ofSeconds(90);
  private static final String TUNNEL_HOST = "gw-smoke-7f3k.trycloudflare.com";
  private static final String PASSWORD = "smoke-fake-sql-Xq7-" + Long.toHexString(System.nanoTime());
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private static Path home;
  private static CoreProcess core;
  private static String token;
  private static String approvalId;
  private static final List<String> EVENTS = new CopyOnWriteArrayList<>();
  private static Thread eventReader;

  @BeforeAll
  static void start() {
    home = CoreProcess.newHome("smoke");
    core = CoreProcess.start(home, CoreProcess.freePort(), List.of("-Dgateway.test.fake-connector=true",
        "-Dgateway.test.tunnel-host=" + TUNNEL_HOST, "-Dgateway.test.tunnel-endpoint=smoke")).bootstrap();
  }

  @AfterAll
  static void stop() {
    if (eventReader != null) {
      eventReader.interrupt();
    }
    if (core != null) {
      core.close();
    }
  }

  // ── 1. Start ────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(1)
  void step1TheCoreStartsOnAnEmptyHomeAndReportsItsHealth() throws Exception {
    List<JsonNode> lines = core.untilReady(READY);
    long readyMs = Duration.ofNanos(System.nanoTime() - core.startedNanos).toMillis();
    System.out.println("Native smoke: " + runtime() + " core ready after " + readyMs + " ms (cold, empty home)");
    JsonNode ready = lines.getLast();
    assertThat(ready.path("ready").asBoolean()).as(core.stderr()).isTrue();
    assertThat(ready.path("firstRun").asBoolean()).isTrue();
    assertThat(ready.path("runtime").asString()).isEqualTo(runtime());

    JsonNode health = json(admin("GET", "/api/health", null), 200);
    assertThat(health.path("status").asString()).isEqualTo("ok");
    assertThat(health.path("runtime").asString()).isEqualTo(runtime());
    assertThat(health.path("ports").path("public").asInt()).isEqualTo(core.publicPort);
  }

  // ── 4. Events (opened before anything happens) ──────────────────────────────────────────────────────────────

  @Test
  @Order(2)
  void step4TheEventStreamOpens() throws Exception {
    String auth = core.secret().sign("GET", "/api/events", System.currentTimeMillis(), nonce());
    HttpResponse<java.io.InputStream> stream = HTTP.send(HttpRequest.newBuilder(uri(core.port, "/api/events"))
        .header("X-Gateway-Auth", auth).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
    assertThat(stream.statusCode()).isEqualTo(200);
    BufferedReader reader = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8));
    assertThat(reader.readLine()).isEqualTo(": stream open");
    eventReader = Thread.ofPlatform().daemon(true).name("smoke-events").start(() -> {
      try {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.startsWith("event:")) {
            EVENTS.add(line.substring("event:".length()).strip());
          }
        }
      } catch (java.io.IOException ignored) {
        // The core stopped.
      }
    });
  }

  // ── 2. Admin API ────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(3)
  void step2TheAdminApiSetsUpAConnectionAnEndpointATemplateAndAToken() throws Exception {
    JsonNode types = json(admin("GET", "/api/connector-types", null), 200);
    assertThat(types.toString()).contains("fake-sql");

    JsonNode tested = json(admin("POST", "/api/connections/test", """
        {"type":"fake-sql","env":"dev","config":%s,"secrets":{"/credentials/password":{"source":"local",
         "value":"%s"}}}""".formatted(config(), PASSWORD)), 200);
    assertThat(tested.path("ok").asBoolean()).as(tested.toString()).isTrue();
    json(admin("POST", "/api/connections", """
        {"name":"orders-db","type":"fake-sql","env":"dev","config":%s,
         "secrets":{"/credentials/password":{"source":"local","value":"%s"}}}""".formatted(config(), PASSWORD)), 201);
    JsonNode connection = json(admin("GET", "/api/connections/orders-db", null), 200);
    assertThat(connection.path("secrets").get(0).path("state").asString()).isEqualTo("stored");

    json(admin("POST", "/api/endpoints", "{\"slug\":\"smoke\",\"startFrom\":\"empty\"}"), 201);
    json(admin("POST", "/api/endpoints/smoke/connections/orders-db", "{}"), 200);
    JsonNode settings = json(admin("GET", "/api/endpoints/smoke/settings", null), 200);
    // The first answer of a request comes at once: the person decides after the agent got its handle.
    json(admin("PUT", "/api/endpoints/smoke/settings", "{\"approvalWait\":{\"firstWaitSeconds\":0},\"version\":"
        + settings.path("version").asLong() + "}"), 200);

    JsonNode template = json(admin("POST", "/api/templates", """
        {"kind":"sql-query","connection":"orders-db","name":"orders_by_status","definition":{
         "name":"orders_by_status","description":"Orders in one status.",
         "sql":"SELECT id, status FROM orders WHERE status = :status",
         "params":{"status":{"type":"string","enum":["pending","shipped","cancelled"]}}}}"""), 201);
    assertThat(template.path("compile").path("ok").asBoolean()).as(template.toString()).isTrue();

    JsonNode issued = json(admin("POST", "/api/endpoints/smoke/tokens", "{\"name\":\"smoke\",\"ttlDays\":1}"), 201);
    token = issued.path("token").asString();
    assertThat(token).startsWith("gwt_");
    assertThat(json(admin("GET", "/api/endpoints/smoke/tokens", null), 200).toString()).doesNotContain(token);
  }

  // ── 3. MCP ──────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(4)
  void step3AReadGoesThroughTheTemplateAndAMutationWaitsForAPerson() throws Exception {
    JsonNode init = mcp("""
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},
         "clientInfo":{"name":"native-smoke","version":"1"}}}""");
    assertThat(init.at("/result/protocolVersion").asString()).as(init.toString()).isEqualTo("2025-11-25");

    // The template's tool appears once the endpoint's pairs are reconciled (asynchronously, after the save).
    List<String> names = new ArrayList<>();
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (!names.contains("orders_by_status") && System.nanoTime() < deadline) {
      names.clear();
      mcp("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}").at("/result/tools")
          .forEach(t -> names.add(t.path("name").asString()));
      if (!names.contains("orders_by_status")) {
        Thread.sleep(200);
      }
    }
    assertThat(names).contains("approval_status", "sql_query", "sql_execute", "orders_by_status");

    JsonNode pending = call(3, "orders_by_status", "{\"status\":\"pending\"}");
    assertThat(pending.at("/result/isError").asBoolean(true)).as(pending.toString()).isFalse();
    assertThat(pending.at("/result/structuredContent/rows").toString()).isEqualTo(
        "[[48213,\"pending\"],[48240,\"pending\"]]");

    JsonNode request = call(4, "sql_execute", "{\"connection\":\"orders-db\","
        + "\"sql\":\"UPDATE orders SET status = 'cancelled' WHERE id = 48213;\"}");
    JsonNode handle = request.at("/result/structuredContent");
    assertThat(handle.path("status").asString()).as(request.toString()).isEqualTo("pending");
    String handleId = handle.path("handle").asString();
    assertThat(handle.at("/action/text").asString()).isEqualTo("UPDATE orders SET status = 'cancelled' WHERE id = 48213;");

    JsonNode waiting = json(admin("GET", "/api/approvals?status=pending", null), 200);
    assertThat(waiting.path("items").size()).isEqualTo(1);
    approvalId = waiting.path("items").get(0).path("id").asString();
    assertThat(approvalId).isEqualTo(handleId);
    JsonNode detail = json(admin("GET", "/api/approvals/" + approvalId, null), 200);
    String digest = detail.path("digest").asString();
    JsonNode decided = json(admin("POST", "/api/approvals/" + approvalId + "/decision",
        "{\"decision\":\"approve\",\"digest\":\"" + digest + "\",\"via\":\"app\"}"), 200);
    assertThat(decided.path("status").asString()).isIn("running", "completed");

    JsonNode status = call(5, "approval_status", "{\"handle\":\"" + handleId + "\",\"wait_seconds\":20}");
    assertThat(status.at("/result/structuredContent/status").asString()).as(status.toString()).isEqualTo("completed");
    assertThat(status.at("/result/structuredContent/result/rowsAffected").asInt()).isEqualTo(1);
    assertThat(status.at("/result/content/0/text").asString()).startsWith("Approved by you and executed on orders-db");

    JsonNode after = call(6, "sql_query", "{\"connection\":\"orders-db\","
        + "\"sql\":\"SELECT id, status FROM orders WHERE id = 48213\"}");
    assertThat(after.at("/result/structuredContent/rows").toString()).as(after.toString())
        .isEqualTo("[[48213,\"cancelled\"]]");

    // The policy still decides: DDL isn't a read, so it is a request too, never a run.
    JsonNode ddl = call(7, "sql_query", "{\"connection\":\"orders-db\",\"sql\":\"DROP TABLE orders\"}");
    assertThat(ddl.at("/result/structuredContent/status").asString()).as(ddl.toString()).isEqualTo("pending");
    assertThat(mcpStatus(null, "/mcp/smoke")).as("no token").isEqualTo(401);
  }

  // ── 4. Events (what arrived) ────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(5)
  void step4TheStreamCarriedTheRequestItsDecisionAndTheCalls() throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!(EVENTS.contains("approval.resolved") && EVENTS.contains("audit.call"))
        && System.nanoTime() < deadline) {
      Thread.sleep(100);
    }
    assertThat(EVENTS).contains("approval.requested", "approval.resolved", "audit.call");
    assertThat(String.join(",", EVENTS)).doesNotContain(PASSWORD);
  }

  // ── The public listener ─────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(6)
  void thePublicListenerServesOnlyThePublishedEndpointAndNeverWithoutAToken() throws Exception {
    int pub = core.publicPort;
    String mcp = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}";
    // The published endpoint through the tunnel's host: 401 naming where to sign in (OAuth, K3).
    String answer = raw(pub, "POST", "/mcp/smoke", TUNNEL_HOST, null, mcp);
    assertThat(statusOf(answer)).as(answer).isEqualTo(401);
    assertThat(answer).contains("WWW-Authenticate: Bearer resource_metadata=\"https://" + TUNNEL_HOST
        + "/.well-known/oauth-protected-resource/mcp/smoke\"");
    // A local token is no good there either.
    assertThat(statusOf(raw(pub, "POST", "/mcp/smoke", TUNNEL_HOST, token, mcp))).isEqualTo(401);
    // Everything outside the allowlist, a foreign Host and the tricks of a raw path: 404.
    for (String path : List.of("/api/health", "/api/events", "/internal/files/grant", "/mcp/other", "/",
        "/error", "/actuator/health", "/approvals/ap_000000000000", "/mcp/smoke/", "/mcp/smoke;x", "/MCP/smoke",
        "/mcp/%73moke", "/mcp//smoke")) {
      assertThat(statusOf(raw(pub, "POST", path, TUNNEL_HOST, token, mcp))).as(path).isEqualTo(404);
    }
    // Tomcat itself may refuse a dot segment (400) before the filter would (404); either way nothing runs.
    assertThat(statusOf(raw(pub, "POST", "/mcp/../mcp/smoke", TUNNEL_HOST, token, mcp))).isIn(400, 404);
    assertThat(statusOf(raw(pub, "POST", "/mcp/smoke", "127.0.0.1:" + pub, token, mcp))).isEqualTo(404);
    assertThat(statusOf(raw(pub, "GET", "/api/health", TUNNEL_HOST, null, null))).isEqualTo(404);
    // The tunnel's host on the local listener is DNS rebinding.
    assertThat(statusOf(raw(core.port, "POST", "/mcp/smoke", TUNNEL_HOST, token, mcp))).isEqualTo(421);
    // Bodies over 64 KiB: refused by their length (an empty 413). A chunked one has no length up front, and through
    // the tunnel nothing is read before the token (K3): without one it is a 401 and the body is never read (the 413
    // of a chunked body cut off while read: PublicIngressFilterTest).
    String large = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"x\",\"params\":{\"pad\":\"" + "x".repeat(100 * 1024)
        + "\"}}";
    assertThat(statusOf(raw(pub, "POST", "/mcp/smoke", TUNNEL_HOST, null, large))).isEqualTo(413);
    String chunked = rawChunked(pub, "/mcp/smoke", TUNNEL_HOST, large);
    assertThat(statusOf(chunked)).as(chunked).isEqualTo(401);
    assertThat(chunked).doesNotContain("timestamp").doesNotContain("\"status\"");
  }

  // ── 6. The database ─────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(7)
  void step6TheAuditIsOneChainThatRefusesAnUpdateAndTheRequestRanOnce() throws Exception {
    core.closeStdin();
    assertThat(core.exited(Duration.ofSeconds(20))).as("exits after stdin EOF").isTrue();
    try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + home.resolve("gateway.db"))) {
      List<String> rows = rows(db, "select tool_name || ':' || phase || ':' || decision from tool_call order by id");
      assertThat(rows).containsSubsequence("orders_by_status:received:allowed", "orders_by_status:completed:allowed",
          "sql_execute:received:pending", "sql_execute:resolved:approved", "sql_execute:completed:approved",
          "sql_query:received:allowed", "sql_query:completed:allowed", "sql_query:received:pending");
      String prev = "0".repeat(64);
      for (String link : rows(db, "select prev_sha256 || ' ' || row_sha256 from tool_call order by id")) {
        assertThat(link).startsWith(prev + " ");
        prev = link.substring(65);
      }
      assertThat(rows(db, "select status || ':' || (executed_sha256 = action_sha256) from approval where id = '"
          + approvalId + "'")).containsExactly("completed:1");
      assertThat(rows(db, "select count(*) from tool_call where approval_id = '" + approvalId
          + "' and phase = 'completed'")).containsExactly("1");
      try (Statement s = db.createStatement()) {
        assertThatThrownBy(() -> s.executeUpdate("update tool_call set decision = 'allowed'"))
            .isInstanceOf(SQLException.class).hasMessageContaining("audit is append-only");
      }
      assertThat(rows(db, "select key from setting where key = 'audit.head-mac'")).containsExactly("audit.head-mac");
    }
  }

  // ── 7. The log ──────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(8)
  void step7TheLogHasNoMissingRegistrationAndNoSecret() {
    String log = core.coreLog();
    assertThat(log).contains("Test connector fake-sql is on");
    assertThat(log).doesNotContainPattern("Missing[A-Za-z]*Registration").doesNotContain("UnsupportedFeatureError");
    assertThat(log).doesNotContain(PASSWORD).doesNotContain(token);
  }

  // ── Warm start ──────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(9)
  void theWarmStartIsWithinBudgetAndTheTunnelIsOffWithoutItsOverride() throws Exception {
    // The median of 21 starts after one that isn't counted (the first after the smoke's own start also pays for the
    // page cache): a median of five moved by tens of milliseconds between runs of the same binary.
    int runs = CoreProcess.nativeBinary() ? Integer.getInteger("gateway.smoke.warmStartRuns", 21) : 1;
    List<Long> times = new ArrayList<>();
    for (int i = CoreProcess.nativeBinary() ? -1 : 0; i < runs; i++) {
      try (CoreProcess again = CoreProcess.start(home, CoreProcess.freePort(), List.of()).bootstrap()) {
        JsonNode ready = again.untilReady(READY).getLast();
        if (i >= 0) {
          times.add(Duration.ofNanos(System.nanoTime() - again.startedNanos).toMillis());
        }
        assertThat(ready.path("ready").asBoolean()).as(again.stderr()).isTrue();
        assertThat(ready.path("firstRun").asBoolean()).isFalse();
        if (i == 0) {
          assertThat(statusOf(raw(again.publicPort, "POST", "/mcp/smoke", TUNNEL_HOST, null, "{}")))
              .as("no tunnel, nothing published").isEqualTo(404);
        }
        again.closeStdin();
        assertThat(again.exited(Duration.ofSeconds(20))).isTrue();
      }
    }
    List<Long> sorted = times.stream().sorted().toList();
    long median = sorted.get(sorted.size() / 2);
    System.out.println("Native smoke: " + runtime() + " warm start (exec -> ready line) " + times + " ms, median "
        + median + " ms");
    if (CoreProcess.nativeBinary()) {
      long budget = Long.getLong("gateway.smoke.warmStartBudgetMs", 500);
      assertThat(median).as("warm start median, budget %d ms", budget).isLessThanOrEqualTo(budget);
    }
  }

  // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static String runtime() {
    return CoreProcess.nativeBinary() ? "native" : "jvm";
  }

  private static String config() {
    return "{\"connection\":{\"host\":\"db.smoke.internal\",\"port\":5432,\"database\":\"app\"},"
        + "\"credentials\":{\"user\":\"gw_reader\"}}";
  }

  private record Answer(int status, String body) {}

  private static URI uri(int port, String target) {
    return URI.create("http://127.0.0.1:" + port + target);
  }

  /** A request of the window: signed as Electron's main process signs it. */
  private static Answer admin(String method, String target, String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(uri(core.port, target)).timeout(Duration.ofSeconds(30))
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
    HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(uri(core.port, "/mcp/smoke"))
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

  private static int mcpStatus(String bearer, String path) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(uri(core.port, path)).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("{}"));
    if (bearer != null) {
      b.header("Authorization", "Bearer " + bearer);
    }
    return HTTP.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  /** A request as written on the wire: the client libraries won't send a foreign {@code Host} or a raw path. */
  private static String raw(int port, String method, String path, String host, String bearer, String body)
      throws Exception {
    byte[] content = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
    StringBuilder request = new StringBuilder(method + " " + path + " HTTP/1.1\r\nHost: " + host + "\r\n");
    if (bearer != null) {
      request.append("Authorization: Bearer ").append(bearer).append("\r\n");
    }
    if (body != null) {
      request.append("Content-Type: application/json\r\nAccept: application/json, text/event-stream\r\n");
    }
    request.append("Content-Length: ").append(content.length).append("\r\nConnection: close\r\n\r\n");
    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
      socket.setSoTimeout(15_000);
      socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
      socket.getOutputStream().write(content);
      socket.getOutputStream().flush();
      return new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
    }
  }

  /** As {@link #raw}, with {@code Transfer-Encoding: chunked}: a body without a length to refuse up front. */
  private static String rawChunked(int port, String path, String host, String body) throws Exception {
    byte[] content = body.getBytes(StandardCharsets.UTF_8);
    String head = "POST " + path + " HTTP/1.1\r\nHost: " + host + "\r\nContent-Type: application/json\r\n"
        + "Accept: application/json, text/event-stream\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n";
    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
      socket.setSoTimeout(15_000);
      OutputStream out = socket.getOutputStream();
      out.write(head.getBytes(StandardCharsets.ISO_8859_1));
      for (int at = 0; at < content.length; at += 8192) {
        int n = Math.min(8192, content.length - at);
        out.write((Integer.toHexString(n) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(content, at, n);
        out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
      }
      out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      return new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
    }
  }

  private static int statusOf(String rawAnswer) {
    return Integer.parseInt(rawAnswer.substring(0, rawAnswer.indexOf("\r\n")).split(" ")[1]);
  }

  private static List<String> rows(Connection db, String sql) throws SQLException {
    List<String> out = new ArrayList<>();
    try (Statement s = db.createStatement(); ResultSet r = s.executeQuery(sql)) {
      while (r.next()) {
        out.add(r.getString(1));
      }
    }
    return out;
  }

  private static String nonce() {
    byte[] bytes = new byte[16];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
