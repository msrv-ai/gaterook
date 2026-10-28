package ai.msrv.gaterook.e2e.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.sqlite.SQLiteConfig;
import tools.jackson.databind.JsonNode;

/**
 * The start-up protocol of the core as a process (ARCHITECTURE.md §7.1, report 14 §8 "CoreProcessIT"): progress and
 * ready lines on stdout and nothing else, the failure reasons Electron shows, the local guard and the event stream of
 * the real binary, the process hardening and the scheduler rule, exit on stdin EOF. On the JVM by default; with
 * {@code -Dgateway.core.binary} against the native executable, which makes it the native smoke of M1.2 (the A10 gate:
 * the core refuses to start when the read-only routing of its SQLite pools doesn't work).
 */
class CoreProcessIT {

  private static final Duration READY = Duration.ofSeconds(90);

  @Test
  void startsReportsReadyServesAndExitsOnEof() throws Exception {
    Path home = CoreProcess.newHome("ready");
    int port = CoreProcess.freePort();
    try (CoreProcess core = CoreProcess.start(home, port,
        List.of("-Djdk.virtualThreadScheduler.parallelism=2")).bootstrap()) {
      List<JsonNode> lines = core.untilReady(READY);
      long readyMs = Duration.ofNanos(System.nanoTime() - core.startedNanos).toMillis();
      System.out.println("Core (" + (CoreProcess.nativeBinary() ? "native" : "jvm") + ") ready line after "
          + readyMs + " ms (cold home)");

      assertThat(lines.stream().filter(l -> l.has("step")).map(l -> l.get("step").asString() + ":"
          + l.get("state").asString())).containsExactly("master-key:running", "master-key:done", "db-migrations:running",
          "db-migrations:done", "local-api:running", "local-api:done");
      JsonNode ready = lines.getLast();
      assertThat(ready.get("v").asInt()).isEqualTo(1);
      assertThat(ready.get("ready").asBoolean()).as(core.stderr()).isTrue();
      assertThat(ready.get("firstRun").asBoolean()).isTrue();
      assertThat(ready.get("locked").isNull()).isTrue();
      assertThat(ready.get("version").asString()).isNotBlank();
      assertThat(ready.get("ports").get("local").asInt()).isEqualTo(port);
      assertThat(ready.get("ports").get("public").asInt()).isEqualTo(core.publicPort);
      assertThat(ready.get("runtime").asString()).isEqualTo(CoreProcess.nativeBinary() ? "native" : "jvm");
      assertThat(ready.get("pid").asLong()).isEqualTo(core.process.pid());

      // The guard of the real listener, and the event stream behind it.
      assertThat(status(port, "/api/events", null)).isEqualTo(401);
      assertThat(status(port, "/error", null)).isEqualTo(404);
      assertThat(status(port, "/actuator/health", null)).isEqualTo(404);
      // A browser origin: the guard reads Settings › Local API through JPA (reader pool) once, then refuses it.
      // A raw request: HttpURLConnection silently drops a restricted header like Origin.
      assertThat(rawStatus(port, "POST /mcp/dev HTTP/1.1\r\nHost: 127.0.0.1:" + port
          + "\r\nOrigin: https://evil.example\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")).isEqualTo(403);
      assertThat(rawStatus(port, "GET /api/events HTTP/1.1\r\nHost: evil.example:" + port
          + "\r\nConnection: close\r\n\r\n")).as("DNS rebinding").isEqualTo(421);
      HttpURLConnection events = open(port, "/api/events", core.secret().sign("GET", "/api/events",
          System.currentTimeMillis(), nonce()));
      assertThat(events.getResponseCode()).isEqualTo(200);
      assertThat(events.getHeaderField("X-Gateway-Instance")).isEqualTo("it-instance-0123456789");
      try (BufferedReader stream = new BufferedReader(new InputStreamReader(events.getInputStream(),
          StandardCharsets.UTF_8))) {
        assertThat(stream.readLine()).isEqualTo(": stream open");
      }
      events.disconnect();

      // Hardening and the database gate, as the core logged them.
      String log = core.coreLog();
      assertThat(log).contains("read-only transactions verified on the reader pool");
      // The scheduler rule (pools + 2 carriers) applied and honoured: on the JVM and in the native image alike.
      assertThat(log).contains("virtual thread scheduler parallelism 6 (minimum 6, raised from 2), 6 carriers verified");
      if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux")) {
        assertThat(log).contains("Process hardening: core dumps disabled");
      }
      assertThat(log).doesNotContain("MissingReflectionRegistration").doesNotContain("UnsupportedFeatureError")
          .doesNotContain("MissingResourceRegistration");
      assertThat(posix(home)).isEqualTo("rwx------");
      assertThat(posix(home.resolve("gateway.db"))).isEqualTo("rw-------");
      assertThat(posix(home.resolve("logs"))).isEqualTo("rwx------");
      // Tink in this runtime: the first start generated the master key and sealed the purpose-key root with it.
      assertThat(posix(home.resolve("master.keyset"))).isEqualTo("r--------");
      assertThat(log).contains("Master key created at");

      // Electron goes away: the core follows.
      core.closeStdin();
      assertThat(core.exited(Duration.ofSeconds(15))).as("exits after stdin EOF").isTrue();
      assertThat(core.process.exitValue()).isZero();
      assertThat(core.stdoutSoFar()).as("stdout carries protocol lines only").allSatisfy(line ->
          assertThat(CoreProcess.JSON.readTree(line).get("v").asInt()).isEqualTo(1));
    }

    // Second start on the same home: not a first run any more.
    try (CoreProcess again = CoreProcess.start(home, CoreProcess.freePort(), List.of()).bootstrap()) {
      JsonNode ready = again.untilReady(READY).getLast();
      assertThat(ready.get("ready").asBoolean()).as(again.stderr()).isTrue();
      assertThat(ready.get("firstRun").asBoolean()).isFalse();
    }
  }

  /**
   * M1.4 in this runtime: on start the registry writes every connector type it was built with, the policy service
   * seeds the three environments, both sign their rows with the row MAC; the second start verifies them again
   * (canonical JSON of what a JSON column gives back), so nothing reads as changed outside GateRook.
   */
  @Test
  void theConnectorCatalogAndThePoliciesAreStoredSignedAndVerifyOnTheNextStart() throws Exception {
    Path home = CoreProcess.newHome("catalog");
    try (CoreProcess core = CoreProcess.start(home, CoreProcess.freePort(), List.of()).bootstrap()) {
      assertThat(core.untilReady(READY).getLast().get("ready").asBoolean()).as(core.stderr()).isTrue();
      try (Connection db = readOnly(home)) {
        assertThat(rows(db, "select id from connector_type where kind = 'BUILT_IN' and mac is not null order by id"))
            .containsExactly("aws", "cloudwatch", "gcp", "gcp-logging", "http", "kubernetes", "logs", "loki",
                "mongodb", "mysql", "opensearch", "postgres", "redis", "ssh", "ssh-logs");
        assertThat(rows(db, "select id from connector_type where mac is null")).isEmpty();
        assertThat(rows(db, "select env || ':' || coalesce(inherits, '-') from policy where mac is not null "
            + "order by env")).containsExactly("dev:-", "prod:-", "stage:dev");
      }
    }
    try (CoreProcess again = CoreProcess.start(home, CoreProcess.freePort(), List.of()).bootstrap()) {
      assertThat(again.untilReady(READY).getLast().get("ready").asBoolean()).as(again.stderr()).isTrue();
      assertThat(again.coreLog()).doesNotContain("changed outside GateRook")
          .doesNotContain("MissingReflectionRegistration").doesNotContain("MissingResourceRegistration");
    }
  }

  /**
   * M1.5 in this runtime (with {@code -Dgateway.core.binary}: the native smoke of the MCP path): an endpoint and a
   * token seeded by a core in this JVM, then the process answers both eras on {@code /mcp/dev} — discovery, the tool
   * list, a core tool, policy refusals of the built-in connector (its {@code classify} is the step's stub), an unknown
   * handle — writes every call to the audit chain before answering, and anchors the chain when it exits.
   */
  @Test
  void theMcpRuntimeAnswersBothErasAndAuditsInThisRuntime() throws Exception {
    Path home = CoreProcess.newHome("mcp");
    String token;
    try (org.springframework.context.ConfigurableApplicationContext seed =
        new org.springframework.boot.builder.SpringApplicationBuilder(ai.msrv.gaterook.core.GatewayApplication.class)
            .run("--gateway.home=" + home, "--gateway.dev=true", "--gateway.dev.launch-secret=" + CoreProcess.SECRET,
                "--server.port=0", "--gateway.connections.health-interval=0s")) {
      seed.getBean(ai.msrv.gaterook.core.connections.ConnectionService.class).create("dev-db", "postgres",
          ai.msrv.gaterook.connector.api.Env.DEV, CoreProcess.JSON.readTree("{\"connection\":{\"host\":"
              + "\"db.dev.internal\",\"port\":5432,\"database\":\"app\"},\"credentials\":{\"user\":\"gw_reader\"}}"),
          java.util.Map.of("/credentials/password", ai.msrv.gaterook.core.connections.SecretInput.local(
              "smoke-dev-db-Xq7".toCharArray())), null);
      var endpoints = seed.getBean(ai.msrv.gaterook.core.endpoints.EndpointService.class);
      endpoints.create("dev", null, ai.msrv.gaterook.core.endpoints.EndpointService.StartFrom.EMPTY, null);
      seed.getBean(ai.msrv.gaterook.core.endpoints.EndpointConnections.class).add("dev", "dev-db", null);
      var snapshots = seed.getBean(ai.msrv.gaterook.core.endpoints.EndpointSnapshotService.class);
      seed.getBean(ai.msrv.gaterook.core.endpoints.EndpointTools.class).setMode("dev", "list_connections", null,
          ai.msrv.gaterook.connector.api.ToolDefinition.Mode.READ_ONLY, null, snapshots.bySlug("dev").orElseThrow()
              .coreTool("list_connections").orElseThrow().rowVersion(), null);
      token = seed.getBean(ai.msrv.gaterook.core.endpoints.TokenService.class).issue("dev", "smoke", 7, null).token();
    }

    int port = CoreProcess.freePort();
    try (CoreProcess core = CoreProcess.start(home, port, List.of()).bootstrap()) {
      assertThat(core.untilReady(READY).getLast().get("ready").asBoolean()).as(core.stderr()).isTrue();
      Mcp mcp = new Mcp(port, token);

      JsonNode init = mcp.post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
          + "\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"smoke\","
          + "\"version\":\"1\"}}}", "2025-11-25", null, null);
      assertThat(init.at("/result/serverInfo/name").asString()).as(init.toString()).isEqualTo("gaterook");
      assertThat(init.at("/result/instructions").asString()).contains("endpoint \"dev\"");
      String meta = "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
          + "\"io.modelcontextprotocol/clientCapabilities\":{}}";
      JsonNode discover = mcp.post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"server/discover\",\"params\":{"
          + meta + "}}", "2026-07-28", "server/discover", null);
      assertThat(discover.at("/result/resultType").asString()).as(discover.toString()).isEqualTo("complete");
      assertThat(discover.at("/result/supportedVersions").toString()).contains("2026-07-28", "2025-11-25");

      JsonNode list = mcp.post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\",\"params\":{}}",
          "2025-11-25", null, null);
      List<String> names = new java.util.ArrayList<>();
      list.at("/result/tools").forEach(t -> names.add(t.path("name").asString()));
      assertThat(names).as(list.toString()).contains("approval_status", "list_connections", "sql_query", "sql_execute");

      JsonNode connections = mcp.post("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{"
          + "\"name\":\"list_connections\",\"arguments\":{}," + meta + "}}", "2026-07-28", "tools/call",
          "list_connections");
      assertThat(connections.at("/result/isError").asBoolean(true)).as(connections.toString()).isFalse();
      assertThat(connections.at("/result/content/0/text").asString()).contains("dev-db");

      // The PostgreSQL connector refuses a write in sql_query while classifying, before any I/O (db.dev.internal
      // doesn't exist): the policy denies, the audit records it.
      JsonNode query = mcp.post("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{"
          + "\"name\":\"sql_query\",\"arguments\":{\"connection\":\"dev-db\",\"sql\":\"DROP TABLE orders\"}}}",
          "2025-11-25", null, null);
      assertThat(query.at("/result/isError").asBoolean()).as(query.toString()).isTrue();
      assertThat(query.at("/result/content/0/text").asString()).startsWith("Denied by policy dev.sql.query-writes");
      assertThat(query.at("/result/_meta/ai.msrv.gaterook~1callId").asString()).startsWith("tc_");
      JsonNode unknown = mcp.post("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{"
          + "\"name\":\"approval_status\",\"arguments\":{\"handle\":\"ap_000000000000\"}," + meta + "}}",
          "2026-07-28", "tools/call", "approval_status");
      assertThat(unknown.at("/result/content/0/text").asString()).as(unknown.toString())
          .startsWith("Unknown handle ap_000000000000");
      JsonNode bad = mcp.post("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{"
          + "\"name\":\"sql_query\",\"arguments\":{\"connection\":\"dev-db\",\"nope\":1}}}", "2025-11-25", null,
          null);
      assertThat(bad.at("/result/content/0/text").asString()).as(bad.toString()).startsWith("Invalid arguments: ");
      assertThat(mcp.status("{}", null)).isEqualTo(401);

      String log = core.coreLog();
      assertThat(log).doesNotContain("MissingReflectionRegistration").doesNotContain("UnsupportedFeatureError")
          .doesNotContain("MissingResourceRegistration");
      core.closeStdin();
      assertThat(core.exited(Duration.ofSeconds(15))).isTrue();
    }
    try (Connection db = readOnly(home)) {
      assertThat(rows(db, "select tool_name || ':' || phase || ':' || decision from tool_call order by id"))
          .containsExactly("list_connections:received:allowed", "list_connections:completed:allowed",
              "sql_query:received:denied");
      List<String> chain = rows(db, "select prev_sha256 || ' ' || row_sha256 from tool_call order by id");
      String prev = "0".repeat(64);
      for (String link : chain) {
        assertThat(link).startsWith(prev + " ");
        prev = link.substring(65);
      }
      assertThat(rows(db, "select key from setting where key = 'audit.head-mac'")).containsExactly("audit.head-mac");
    }
  }

  /** Raw MCP over HTTP with a Bearer token. */
  private record Mcp(int port, String token) {

    JsonNode post(String body, String version, String method, String name) throws Exception {
      java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
          + "/mcp/dev")).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
          .header("Accept", "application/json, text/event-stream").header("Authorization", "Bearer " + token)
          .header("MCP-Protocol-Version", version).POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));
      if (method != null) {
        b.header("Mcp-Method", method);
      }
      if (name != null) {
        b.header("Mcp-Name", name);
      }
      var r = java.net.http.HttpClient.newHttpClient().send(b.build(),
          java.net.http.HttpResponse.BodyHandlers.ofString());
      return CoreProcess.JSON.readTree(r.body());
    }

    int status(String body, String bearer) throws Exception {
      java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
          + "/mcp/dev")).header("Content-Type", "application/json")
          .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));
      if (bearer != null) {
        b.header("Authorization", "Bearer " + bearer);
      }
      return java.net.http.HttpClient.newHttpClient().send(b.build(),
          java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
    }
  }

  private static Connection readOnly(Path home) throws SQLException {
    SQLiteConfig config = new SQLiteConfig();
    config.setReadOnly(true);
    return config.createConnection("jdbc:sqlite:" + home.resolve("gateway.db"));
  }

  private static List<String> rows(Connection db, String sql) throws SQLException {
    List<String> out = new java.util.ArrayList<>();
    try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
      while (rs.next()) {
        out.add(rs.getString(1));
      }
    }
    return out;
  }

  /**
   * The admin API of M1.6 in this runtime, as the window uses it to set the gateway up: every screen's first request,
   * the writes that bind records (connection with its secret, endpoint, tool mode, token, settings, saved views,
   * policy), the Playground through the MCP pipeline and a database copy. In a native image this is what proves the
   * reflection metadata of the answers and requests and the AOT repositories behind them.
   */
  @Test
  void theAdminApiAnswersInThisRuntime() throws Exception {
    Path home = CoreProcess.newHome("admin");
    int port = CoreProcess.freePort();
    try (CoreProcess core = CoreProcess.start(home, port, List.of()).bootstrap()) {
      assertThat(core.untilReady(READY).getLast().get("ready").asBoolean()).as(core.stderr()).isTrue();

      JsonNode health = json(send(core, port, "GET", "/api/health", null), 200);
      assertThat(health.path("status").asString()).isEqualTo("ok");
      assertThat(health.path("runtime").asString()).isEqualTo(CoreProcess.nativeBinary() ? "native" : "jvm");
      assertThat(health.path("db").path("sqlite").asString()).startsWith("3.");
      assertThat(json(send(core, port, "GET", "/api/core/info", null), 200).path("port").asInt()).isEqualTo(port);
      JsonNode catalog = json(send(core, port, "GET", "/api/connector-types", null), 200);
      assertThat(catalog.path("groups").size()).isEqualTo(6);
      assertThat(json(send(core, port, "GET", "/api/connector-types/postgres/form?env=prod", null), 200)
          .path("secretFields").get(0).path("pointer").asString()).isEqualTo("/credentials/password");

      String password = "smoke-admin-Xq7-" + System.nanoTime();
      json(send(core, port, "POST", "/api/connections", "{\"name\":\"dev-db\",\"type\":\"postgres\","
          + "\"env\":\"dev\",\"config\":{\"connection\":{\"host\":\"db.dev.internal\",\"port\":5432,"
          + "\"database\":\"app\"},\"credentials\":{\"user\":\"gw_reader\"}},\"secrets\":{"
          + "\"/credentials/password\":{\"source\":\"local\",\"value\":\"" + password + "\"}}}"), 201);
      JsonNode connection = json(send(core, port, "GET", "/api/connections/dev-db", null), 200);
      assertThat(connection.path("secrets").get(0).path("state").asString()).isEqualTo("stored");
      json(send(core, port, "GET", "/api/connections?env=dev", null), 200);
      json(send(core, port, "GET", "/api/connections/dev-db/describe", null), 200);

      json(send(core, port, "POST", "/api/endpoints", "{\"slug\":\"dev\",\"startFrom\":\"empty\"}"), 201);
      json(send(core, port, "POST", "/api/endpoints/dev/connections/dev-db", "{}"), 200);
      JsonNode tools = json(send(core, port, "GET", "/api/endpoints/dev/tools", null), 200);
      JsonNode coreGroup = tools.get(tools.size() - 1);
      long version = -1;
      for (JsonNode t : coreGroup.path("tools")) {
        if (t.path("name").asString().equals("list_connections")) {
          version = t.path("version").asLong();
        }
      }
      json(send(core, port, "PUT", "/api/endpoints/dev/tools", "{\"toolKey\":\"list_connections\","
          + "\"mode\":\"READ_ONLY\",\"version\":" + version + "}"), 200);
      JsonNode settings = json(send(core, port, "GET", "/api/endpoints/dev/settings", null), 200);
      json(send(core, port, "GET", "/api/endpoints/dev/risk", null), 200);
      json(send(core, port, "GET", "/api/endpoints", null), 200);
      JsonNode issued = json(send(core, port, "POST", "/api/endpoints/dev/tokens", "{\"name\":\"smoke\"}"), 201);
      assertThat(issued.path("token").asString()).startsWith("gwt_");
      json(send(core, port, "GET", "/api/endpoints/dev/tokens", null), 200);
      assertThat(settings.path("version").asLong()).isNotNegative();

      json(send(core, port, "GET", "/api/playground/tools?endpoint=dev", null), 200);
      JsonNode call = json(send(core, port, "POST", "/api/playground/call", "{\"endpoint\":\"dev\","
          + "\"tool\":\"list_connections\",\"arguments\":{}}"), 200);
      assertThat(call.path("isError").asBoolean(true)).as(call.toString()).isFalse();
      assertThat(call.path("text").asString()).contains("dev-db");

      JsonNode events = json(send(core, port, "GET", "/api/activity/events?q=tool:list_connections", null), 200);
      assertThat(events.path("events").size()).isEqualTo(1);
      json(send(core, port, "GET", "/api/activity/events/" + events.path("events").get(0).path("id").asLong(),
          null), 200);
      json(send(core, port, "GET", "/api/activity/histogram", null), 200);
      json(send(core, port, "GET", "/api/activity/facets", null), 200);
      json(send(core, port, "GET", "/api/activity/traces", null), 200);
      json(send(core, port, "GET", "/api/activity/changes", null), 200);
      json(send(core, port, "PUT", "/api/activity/views", "{\"views\":[{\"name\":\"Mine\","
          + "\"q\":\"client:playground\"}],\"version\":0}"), 200);
      json(send(core, port, "GET", "/api/approvals?status=history", null), 200);
      json(send(core, port, "GET", "/api/approvals", null), 200);

      JsonNode policies = json(send(core, port, "GET", "/api/policies", null), 200);
      json(send(core, port, "GET", "/api/policies/schema", null), 200);
      json(send(core, port, "PUT", "/api/policies/dev", "{\"sections\":{\"sql\":{\"rowLimit\":500}},"
          + "\"version\":" + policies.path("dev").path("version").asLong() + "}"), 200);
      json(send(core, port, "GET", "/api/templates", null), 200);

      json(send(core, port, "GET", "/api/settings", null), 200);
      json(send(core, port, "PUT", "/api/settings/general", "{\"autoUpdate\":false,\"version\":0}"), 200);
      json(send(core, port, "GET", "/api/backup", null), 200);
      JsonNode copy = json(send(core, port, "POST", "/api/backup/db", null), 200);
      assertThat(Files.size(Path.of(copy.path("path").asString()))).isPositive();

      json(send(core, port, "GET", "/api/overview", null), 200);
      json(send(core, port, "GET", "/api/onboarding", null), 200);
      json(send(core, port, "GET", "/api/nav/counters", null), 200);
      json(send(core, port, "GET", "/api/search?q=dev", null), 200);
      json(send(core, port, "GET", "/api/secrets", null), 200);

      String log = core.coreLog();
      assertThat(log).doesNotContain("MissingReflectionRegistration").doesNotContain("UnsupportedFeatureError")
          .doesNotContain("MissingResourceRegistration").doesNotContain(password);
      core.closeStdin();
      assertThat(core.exited(Duration.ofSeconds(15))).isTrue();
    }
  }

  @Test
  void aLostKeyIsReplacedWhenNoSecretNeedsItAndAnUnreadableOneLocks() throws Exception {
    Path home = CoreProcess.newHome("no-key");
    try (CoreProcess first = CoreProcess.start(home, CoreProcess.freePort(), List.of()).bootstrap()) {
      assertThat(first.untilReady(READY).getLast().get("ready").asBoolean()).as(first.stderr()).isTrue();
      first.closeStdin();
      assertThat(first.exited(Duration.ofSeconds(15))).isTrue();
    }
    // No local secret to lose (§7.1, case b): a new key, with a warning on the master-key card.
    Files.delete(home.resolve("master.keyset"));
    int port = CoreProcess.freePort();
    try (CoreProcess again = CoreProcess.start(home, port, List.of()).bootstrap()) {
      JsonNode ready = again.untilReady(READY).getLast();
      assertThat(ready.get("ready").asBoolean()).as(again.stderr()).isTrue();
      assertThat(ready.get("locked").isNull()).isTrue();
      assertThat(posix(home.resolve("master.keyset"))).isEqualTo("r--------");
      JsonNode card = masterKeyCard(again, port);
      assertThat(card.get("warnings").toString()).contains("new-key-created");
      again.closeStdin();
      assertThat(again.exited(Duration.ofSeconds(15))).isTrue();
    }
    // A key file that exists but can't be read is never replaced behind the person's back: the locked mode.
    Files.setPosixFilePermissions(home.resolve("master.keyset"), PosixFilePermissions.fromString("rw-------"));
    Files.writeString(home.resolve("master.keyset"), "{\"not\":\"a keyset\"}");
    port = CoreProcess.freePort();
    try (CoreProcess locked = CoreProcess.start(home, port, List.of()).bootstrap()) {
      JsonNode ready = locked.untilReady(READY).getLast();
      assertThat(ready.get("ready").asBoolean()).as(locked.stderr()).isTrue();
      assertThat(ready.get("locked").asString()).isEqualTo("MASTER_KEY_INVALID");
      assertThat(signedStatus(locked, port, "/api/secrets")).isEqualTo(503);
      JsonNode card = masterKeyCard(locked, port);
      assertThat(card.get("locked").asString()).isEqualTo("MASTER_KEY_INVALID");
      assertThat(card.get("problems").toString()).contains("key-invalid");
      assertThat(Files.readString(home.resolve("master.keyset"))).isEqualTo("{\"not\":\"a keyset\"}");
    }
  }

  @Test
  void rotationBackupAndRestoreWorkInThisRuntime() throws Exception {
    Path home = CoreProcess.newHome("key-cycle");
    Path backup = home.resolveSibling(home.getFileName() + "-backup.json");
    int port = CoreProcess.freePort();
    try (CoreProcess core = CoreProcess.start(home, port, List.of()).bootstrap()) {
      assertThat(core.untilReady(READY).getLast().get("ready").asBoolean()).as(core.stderr()).isTrue();
      assertThat(post(core, port, "/api/master-key/rotate", "{}").status()).isEqualTo(202);
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      String state = "";
      while (!state.equals("done") && System.nanoTime() < deadline) {
        state = signedJson(core, port, "/api/master-key/rotation").get("state").asString();
        Thread.sleep(100);
      }
      assertThat(state).as(core.stderr()).isEqualTo("done");
      assertThat(masterKeyCard(core, port).get("keys")).hasSize(2);

      // PBKDF2 (600,000 iterations) and Tink's encrypted keyset in this runtime; the backup lets the old key go.
      Answer saved = post(core, port, "/api/master-key/backup", "{\"saveToken\":\"" + grant(core, port, backup,
          "master-key-backup") + "\",\"passphrase\":\"correct horse battery\"}");
      assertThat(saved.status()).as(saved.body()).isEqualTo(200);
      assertThat(posix(backup)).isEqualTo("rw-------");
      JsonNode card = masterKeyCard(core, port);
      assertThat(card.get("keys")).hasSize(1);
      assertThat(card.get("problems").toString()).doesNotContain("backup-needed");
      core.closeStdin();
      assertThat(core.exited(Duration.ofSeconds(15))).isTrue();
    }
    Files.setPosixFilePermissions(home.resolve("master.keyset"), PosixFilePermissions.fromString("rw-------"));
    Files.writeString(home.resolve("master.keyset"), "garbage");
    port = CoreProcess.freePort();
    try (CoreProcess locked = CoreProcess.start(home, port, List.of()).bootstrap()) {
      assertThat(locked.untilReady(READY).getLast().get("locked").asString()).isEqualTo("MASTER_KEY_INVALID");
      Answer wrong = post(locked, port, "/api/master-key/restore", "{\"openToken\":\"" + grant(locked, port, backup,
          "master-key-restore") + "\",\"passphrase\":\"wrong\"}");
      assertThat(wrong.status()).isEqualTo(422);
      Answer restored = post(locked, port, "/api/master-key/restore", "{\"openToken\":\"" + grant(locked, port,
          backup, "master-key-restore") + "\",\"passphrase\":\"correct horse battery\"}");
      assertThat(restored.status()).as(restored.body()).isEqualTo(200);
      assertThat(signedStatus(locked, port, "/api/secrets")).as("unlocked without a restart").isEqualTo(200);
      assertThat(locked.coreLog()).doesNotContain("MissingReflectionRegistration")
          .doesNotContain("correct horse battery");
    } finally {
      Files.deleteIfExists(backup);
    }
  }

  /**
   * Electron sends one {@code safeStorage} key to the bootstrap line and to {@code /internal/keyset/protect}; both read
   * it alike in either Base64 alphabet. Bytes {@code FB FF BF} encode to {@code -_-_} in base64url and {@code +/+/} in
   * standard Base64: the characters where the alphabets differ, which about three random keys in four contain.
   */
  @Test
  void theOsProtectedKeyFileOpensWithTheSameKeyInEitherAlphabet() throws Exception {
    byte[] first = fileKey((byte) 0xfb, (byte) 0xff, (byte) 0xbf);
    byte[] second = fileKey((byte) 0xfb, (byte) 0xef, (byte) 0xbe, (byte) 0xff, (byte) 0xff, (byte) 0xff);
    String firstUrl = Base64.getUrlEncoder().withoutPadding().encodeToString(first);
    assertThat(firstUrl).contains("-").contains("_");
    Path home = CoreProcess.newHome("os-protected");
    Path keyFile = home.resolve("master.keyset");

    try (CoreProcess core = CoreProcess.start(home, CoreProcess.freePort(), List.of())
        .bootstrap(firstUrl, "gnome_libsecret")) {
      JsonNode ready = core.untilReady(READY).getLast();
      assertThat(ready.get("ready").asBoolean()).as(core.stderr()).isTrue();
      assertThat(ready.get("locked").isNull()).isTrue();
      assertThat(Files.readString(keyFile)).contains("encryptedKeyset");
      assertThat(posix(keyFile)).isEqualTo("r--------");
      core.closeStdin();
      assertThat(core.exited(Duration.ofSeconds(15))).isTrue();
    }
    byte[] encrypted = Files.readAllBytes(keyFile);

    // The same key in the other alphabet opens the file, and protect accepts that alphabet too.
    int port = CoreProcess.freePort();
    try (CoreProcess core = CoreProcess.start(home, port, List.of())
        .bootstrap(Base64.getEncoder().encodeToString(first), "gnome_libsecret")) {
      JsonNode ready = core.untilReady(READY).getLast();
      assertThat(ready.get("ready").asBoolean()).as(core.stderr()).isTrue();
      assertThat(ready.get("locked").isNull()).as(core.coreLog()).isTrue();
      String secondStandard = Base64.getEncoder().encodeToString(second);
      assertThat(secondStandard).contains("+").contains("/");
      Answer protect = post(core, port, "/internal/keyset/protect", "{\"fileKey\":\"" + secondStandard + "\"}");
      assertThat(protect.status()).as(protect.body()).isEqualTo(204);
      assertThat(Files.readAllBytes(keyFile)).isNotEqualTo(encrypted);
      core.closeStdin();
      assertThat(core.exited(Duration.ofSeconds(15))).isTrue();
    }
    encrypted = Files.readAllBytes(keyFile);

    // The key switched on at runtime opens the file at the next start, in the bootstrap's own alphabet.
    try (CoreProcess core = CoreProcess.start(home, CoreProcess.freePort(), List.of())
        .bootstrap(Base64.getUrlEncoder().withoutPadding().encodeToString(second), "gnome_libsecret")) {
      JsonNode ready = core.untilReady(READY).getLast();
      assertThat(ready.get("ready").asBoolean()).as(core.stderr()).isTrue();
      assertThat(ready.get("locked").isNull()).as(core.coreLog()).isTrue();
      core.closeStdin();
      assertThat(core.exited(Duration.ofSeconds(15))).isTrue();
    }

    // Without the key the file is unreadable: locked, and the file stays as it is.
    try (CoreProcess core = CoreProcess.start(home, CoreProcess.freePort(), List.of()).bootstrap()) {
      JsonNode ready = core.untilReady(READY).getLast();
      assertThat(ready.get("ready").asBoolean()).as(core.stderr()).isTrue();
      assertThat(ready.get("locked").asString()).isEqualTo("MASTER_KEY_INVALID");
    }
    assertThat(Files.readAllBytes(keyFile)).isEqualTo(encrypted);
  }

  @Test
  void aSecondCoreOnTheSameHomeIsDbLocked() throws Exception {
    Path home = CoreProcess.newHome("locked");
    try (CoreProcess first = CoreProcess.start(home, CoreProcess.freePort(), List.of()).bootstrap()) {
      assertThat(first.untilReady(READY).getLast().get("ready").asBoolean()).as(first.stderr()).isTrue();

      try (CoreProcess second = CoreProcess.start(home, CoreProcess.freePort(), List.of()).bootstrap()) {
        JsonNode failed = second.untilReady(READY).getLast();
        assertThat(failed.get("ready").asBoolean()).isFalse();
        assertThat(failed.get("reason").asString()).isEqualTo("DB_LOCKED");
        assertThat(failed.get("log").asString()).endsWith("core.log");
        assertThat(second.exited(Duration.ofSeconds(15))).isTrue();
        assertThat(second.process.exitValue()).isEqualTo(2);
      }
    }
  }

  /**
   * Decision 3 ({@code ddl-auto=validate}): Hibernate checks its mapping against the migrated schema at every start,
   * in this runtime too, so a schema that no longer matches the entities stops the start instead of failing a query.
   */
  @Test
  void aSchemaThatNoLongerMatchesTheEntitiesStopsTheStart() throws Exception {
    Path home = CoreProcess.newHome("schema");
    try (CoreProcess first = CoreProcess.start(home, CoreProcess.freePort(), List.of()).bootstrap()) {
      assertThat(first.untilReady(READY).getLast().get("ready").asBoolean()).as(first.stderr()).isTrue();
    }
    try (Connection db = new SQLiteConfig().createConnection("jdbc:sqlite:" + home.resolve("gateway.db"));
        Statement change = db.createStatement()) {
      change.execute("ALTER TABLE trace DROP COLUMN title_source");
    }
    try (CoreProcess second = CoreProcess.start(home, CoreProcess.freePort(), List.of()).bootstrap()) {
      JsonNode failed = second.untilReady(READY).getLast();
      assertThat(failed.get("ready").asBoolean()).isFalse();
      assertThat(failed.get("reason").asString()).as(second.stderr()).isEqualTo("STARTUP_FAILED");
      assertThat(failed.get("detail").asString()).contains("SchemaManagementException");
      assertThat(second.exited(Duration.ofSeconds(15))).isTrue();
      assertThat(second.process.exitValue()).isEqualTo(2);
    }
    assertThat(Files.readString(home.resolve("logs/core.log"))).contains("title_source");
  }

  @Test
  void aTakenPortIsPortInUseWithItsOwner() throws Exception {
    try (ServerSocket squatter = new ServerSocket(0, 50, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
      Path home = CoreProcess.newHome("port");
      try (CoreProcess core = CoreProcess.start(home, squatter.getLocalPort(), List.of()).bootstrap()) {
        List<JsonNode> lines = core.untilReady(READY);
        JsonNode failed = lines.getLast();
        assertThat(failed.get("ready").asBoolean()).isFalse();
        assertThat(failed.get("reason").asString()).as(core.stderr()).isEqualTo("PORT_IN_USE");
        assertThat(failed.get("detail").asString()).contains("127.0.0.1:" + squatter.getLocalPort());
        assertThat(lines).anySatisfy(l -> {
          assertThat(l.path("step").asString()).isEqualTo("local-api");
          assertThat(l.path("state").asString()).isEqualTo("failed");
        });
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux")) {
          assertThat(failed.get("portOwner").get("pid").asLong()).isEqualTo(ProcessHandle.current().pid());
          assertThat(failed.get("portOwner").get("user").asString()).isEqualTo(System.getProperty("user.name"));
        }
        assertThat(core.exited(Duration.ofSeconds(15))).isTrue();
        assertThat(core.process.exitValue()).isEqualTo(2);
      }
    }
  }

  @Test
  void withoutABootstrapLineTheCoreGivesUp() throws Exception {
    try (CoreProcess silent = CoreProcess.start(CoreProcess.newHome("timeout"), CoreProcess.freePort(), List.of())) {
      JsonNode failed = silent.nextLine(Duration.ofSeconds(30));
      assertThat(failed.get("ready").asBoolean()).isFalse();
      assertThat(failed.get("reason").asString()).isEqualTo("BOOTSTRAP_TIMEOUT");
      assertThat(silent.exited(Duration.ofSeconds(10))).isTrue();
      assertThat(silent.process.exitValue()).isEqualTo(2);
    }
    try (CoreProcess garbled = CoreProcess.start(CoreProcess.newHome("invalid"), CoreProcess.freePort(), List.of())
        .writeLine("{\"v\":1,\"launchSecret\":\"too-short\"}")) {
      JsonNode failed = garbled.nextLine(Duration.ofSeconds(30));
      assertThat(failed.get("reason").asString()).isEqualTo("BOOTSTRAP_INVALID");
      assertThat(failed.toString()).doesNotContain("too-short");
      assertThat(garbled.exited(Duration.ofSeconds(10))).isTrue();
      assertThat(garbled.process.exitValue()).isEqualTo(2);
    }
  }

  // ── helpers ────────────────────────────────────────────────────────────────────────────────────────────────

  /** A 32-byte file key made of {@code pattern} repeated. */
  private static byte[] fileKey(byte... pattern) {
    byte[] key = new byte[32];
    for (int i = 0; i < key.length; i++) {
      key[i] = pattern[i % pattern.length];
    }
    return key;
  }

  private static String nonce() {
    byte[] bytes = new byte[16];
    new java.security.SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static HttpURLConnection open(int port, String path, String auth) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path).toURL()
        .openConnection();
    connection.setConnectTimeout(5_000);
    connection.setReadTimeout(20_000);
    if (auth != null) {
      connection.setRequestProperty("X-Gateway-Auth", auth);
    }
    return connection;
  }

  private record Answer(int status, String body) {}

  /** A signed POST with a JSON body. */
  private static Answer post(CoreProcess core, int port, String path, String json) throws Exception {
    HttpURLConnection connection = open(port, path, core.secret().sign("POST", path, System.currentTimeMillis(),
        nonce()));
    try {
      connection.setRequestMethod("POST");
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "application/json");
      connection.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
      int status = connection.getResponseCode();
      var stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
      return new Answer(status, stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    } finally {
      connection.disconnect();
    }
  }

  /** A signed request with an optional JSON body, any method. */
  private static Answer send(CoreProcess core, int port, String method, String target, String json)
      throws Exception {
    java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
        + target)).timeout(Duration.ofSeconds(30)).header("X-Gateway-Auth", core.secret().sign(method, target,
        System.currentTimeMillis(), nonce())).method(method, json == null
        ? java.net.http.HttpRequest.BodyPublishers.noBody() : java.net.http.HttpRequest.BodyPublishers.ofString(json));
    if (json != null) {
      b.header("Content-Type", "application/json");
    }
    var r = java.net.http.HttpClient.newHttpClient().send(b.build(),
        java.net.http.HttpResponse.BodyHandlers.ofString());
    return new Answer(r.statusCode(), r.body());
  }

  /** The answer's JSON, after checking its status. */
  private static JsonNode json(Answer answer, int status) {
    assertThat(answer.status()).as(answer.body()).isEqualTo(status);
    return CoreProcess.JSON.readTree(answer.body());
  }

  /** The one-time token Electron's file dialog would get for {@code path}. */
  private static String grant(CoreProcess core, int port, Path path, String purpose) throws Exception {
    Answer answer = post(core, port, "/internal/files/grant", "{\"path\":\"" + path + "\",\"purpose\":\"" + purpose
        + "\"}");
    assertThat(answer.status()).as(answer.body()).isEqualTo(200);
    return CoreProcess.JSON.readTree(answer.body()).get("token").asString();
  }

  private static JsonNode signedJson(CoreProcess core, int port, String path) throws Exception {
    HttpURLConnection connection = open(port, path, core.secret().sign("GET", path, System.currentTimeMillis(),
        nonce()));
    try {
      assertThat(connection.getResponseCode()).isEqualTo(200);
      return CoreProcess.JSON.readTree(connection.getInputStream());
    } finally {
      connection.disconnect();
    }
  }

  private static JsonNode masterKeyCard(CoreProcess core, int port) throws Exception {
    HttpURLConnection card = open(port, "/api/master-key", core.secret().sign("GET", "/api/master-key",
        System.currentTimeMillis(), nonce()));
    try {
      assertThat(card.getResponseCode()).isEqualTo(200);
      return CoreProcess.JSON.readTree(card.getInputStream());
    } finally {
      card.disconnect();
    }
  }

  private static int signedStatus(CoreProcess core, int port, String path) throws Exception {
    return status(port, path, core.secret().sign("GET", path, System.currentTimeMillis(), nonce()));
  }

  private static int status(int port, String path, String auth) throws Exception {
    HttpURLConnection connection = open(port, path, auth);
    try {
      return connection.getResponseCode();
    } finally {
      connection.disconnect();
    }
  }

  /** Sends {@code request} as is and returns the status code of the answer. */
  private static int rawStatus(int port, String request) throws Exception {
    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
      socket.setSoTimeout(10_000);
      socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
      socket.getOutputStream().flush();
      String status = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
          .readLine();
      return Integer.parseInt(status.split(" ")[1]);
    }
  }

  private static String posix(Path path) throws Exception {
    return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
  }
}
