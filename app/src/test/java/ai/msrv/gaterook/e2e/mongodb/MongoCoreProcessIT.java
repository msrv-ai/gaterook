package ai.msrv.gaterook.e2e.mongodb;

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
 * The MongoDB connector in the core as a process, through the admin API the window uses and MCP over HTTP
 * (ARCHITECTURE.md §10 C2), against a mongod with access control and TLS only, reached as
 * {@code mongodb+srv://rs.0.0.1}: SRV and TXT come from a local DNS server over UDP, named by
 * {@code -Dgateway.test.dns-server} (a development build honours it, a release ignores it). On the JVM it runs
 * {@code app.jar}; with {@code -Dgateway.core.binary=<path>} it is the native touch of the connector: the driver, its
 * SCRAM and TLS, our DNS client, Extended JSON and the validator as the image has them.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MongoCoreProcessIT {

  private static final Duration READY = Duration.ofSeconds(90);
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private static LocalMongod mongod;
  private static UdpDnsStub dns;
  private static CoreProcess core;
  private static String token;

  @BeforeAll
  static void start() throws Exception {
    mongod = LocalMongod.start();
    // rs.0.0.1 → 127.0.0.1: an IP target keeps the system resolver out, the certificate names 127.0.0.1, and the
    // driver's rule that a target shares the SRV name's parent domain holds (0.0.1).
    dns = new UdpDnsStub().srv("_mongodb._tcp.rs.0.0.1", mongod.port, "127.0.0.1").txt("rs.0.0.1",
        "authSource=admin");
    core = CoreProcess.start(CoreProcess.newHome("mongodb"), CoreProcess.freePort(),
        List.of("-Dgateway.test.dns-server=" + dns.address())).bootstrap();
    List<JsonNode> lines = core.untilReady(READY);
    assertThat(lines.getLast().path("ready").asBoolean()).as(core.stderr()).isTrue();
  }

  @AfterAll
  static void stop() throws Exception {
    if (core != null) {
      core.close();
    }
    if (dns != null) {
      dns.close();
    }
    if (mongod != null) {
      mongod.close();
    }
  }

  private static String config(String user) throws Exception {
    return "{\"connection\":{\"uri\":\"mongodb+srv://rs.0.0.1/orders\",\"caCertificate\":"
        + CoreProcess.JSON.writeValueAsString(mongod.caCertificate()) + "},\"credentials\":{\"user\":\"" + user
        + "\"}}";
  }

  private static String secrets(String password) {
    return "{\"/credentials/password\":{\"source\":\"local\",\"value\":\"" + password + "\"}}";
  }

  // ── Test connection ─────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(1)
  void testConnectionThroughSrvSaysWhatTheUserMayDo() throws Exception {
    JsonNode tested = json(admin("POST", "/api/connections/test", "{\"type\":\"mongodb\",\"env\":\"dev\",\"config\":"
        + config("gw_rw") + ",\"secrets\":" + secrets(LocalMongod.password("gw_rw")) + "}"), 200);

    assertThat(tested.path("ok").asBoolean()).as(tested.toString()).isTrue();
    assertThat(tested.path("serverVersion").asString()).isEqualTo("MongoDB " + mongod.version());
    assertThat(tested.path("identity").asString()).isEqualTo("gw_rw@admin");
    assertThat(tested.at("/permissions/level").asString()).isEqualToIgnoringCase("write");
    assertThat(tested.path("hints").findValuesAsString("code")).contains("mongo.user-can-write");
    assertThat(tested.toString()).doesNotContain(LocalMongod.password("gw_rw"));
    // The lookups went through the gateway's own DNS client to the stub: SRV for the hosts, TXT for the options.
    assertThat(dns.asked).contains("SRV _mongodb._tcp.rs.0.0.1", "TXT rs.0.0.1");
  }

  @Test
  @Order(2)
  void aRefusedConnectionAndAPasswordInTheUriAreSaidSo() throws Exception {
    JsonNode refused = json(admin("POST", "/api/connections/test", "{\"type\":\"mongodb\",\"env\":\"dev\",\"config\":"
        + "{\"connection\":{\"uri\":\"mongodb://127.0.0.1:" + CoreProcess.freePort() + "/orders\"}}}"), 200);
    // The core's secret scan of the config stops the password first; the connector's own check (mongo.config,
    // "Remove the user name and password…") is the second line for what the scan doesn't recognize.
    JsonNode userInfo = json(admin("POST", "/api/connections/test", "{\"type\":\"mongodb\",\"env\":\"dev\","
        + "\"config\":{\"connection\":{\"uri\":\"mongodb://gw_rw:" + LocalMongod.password("gw_rw")
        + "@127.0.0.1/orders\"}}}"), 422);

    assertThat(refused.path("ok").asBoolean()).isFalse();
    assertThat(refused.at("/error/code").asString()).as(refused.toString()).isEqualTo("mongo.refused");
    assertThat(userInfo.path("field").asString()).isEqualTo("/config/connection/uri");
    assertThat(userInfo.toString()).doesNotContain(LocalMongod.password("gw_rw"));
  }

  // ── Setup ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(3)
  void aConnectionAnEndpointAndAToken() throws Exception {
    // A user that can write on purpose: the validator, not the server, has to stop $out.
    json(admin("POST", "/api/connections", "{\"name\":\"orders-mongo\",\"type\":\"mongodb\",\"env\":\"dev\","
        + "\"config\":" + config("gw_rw") + ",\"secrets\":" + secrets(LocalMongod.password("gw_rw")) + "}"), 201);
    JsonNode stored = json(admin("POST", "/api/connections/orders-mongo/test", null), 200);
    assertThat(stored.path("ok").asBoolean()).as(stored.toString()).isTrue();

    json(admin("POST", "/api/endpoints", "{\"slug\":\"mongo\",\"startFrom\":\"empty\"}"), 201);
    json(admin("POST", "/api/endpoints/mongo/connections/orders-mongo", "{}"), 200);
    // describe_connection is off on an empty endpoint: Read only, as the window's toggle does it.
    long version = -1;
    for (JsonNode group : json(admin("GET", "/api/endpoints/mongo/tools", null), 200)) {
      for (JsonNode t : group.path("tools")) {
        if (t.path("toolKey").asString().equals("describe_connection")) {
          version = t.path("version").asLong();
        }
      }
    }
    assertThat(version).isNotNegative();
    json(admin("PUT", "/api/endpoints/mongo/tools", "{\"toolKey\":\"describe_connection\",\"mode\":\"READ_ONLY\","
        + "\"version\":" + version + "}"), 200);
    token = json(admin("POST", "/api/endpoints/mongo/tokens", "{\"name\":\"mongo-e2e\",\"ttlDays\":1}"), 201)
        .path("token").asString();
    assertThat(token).startsWith("gwt_");
  }

  // ── MCP ─────────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(4)
  void findAndAggregateAnswerExtendedJson() throws Exception {
    JsonNode tools = mcp("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}").at("/result/tools");
    assertThat(names(tools)).contains("mongo_find", "mongo_aggregate", "describe_connection");
    for (JsonNode t : tools) {
      if (t.path("name").asString().equals("mongo_find")) {
        assertThat(t.path("description").asString())
            .contains("orders-mongo (MongoDB " + mongod.majorMinor() + ", dev)").contains("Max 1,000 documents, 5 s.");
      }
    }

    JsonNode found = call(2, "mongo_find", "{\"connection\":\"orders-mongo\",\"collection\":\"orders\",\"sort\":"
        + "{\"_id\":1}}");
    assertThat(found.at("/result/isError").asBoolean(true)).as(found.toString()).isFalse();
    assertThat(found.at("/result/structuredContent/returned").asInt()).isEqualTo(3);
    assertThat(found.at("/result/structuredContent/documents/0").toString()).isEqualTo("{\"_id\":1,\"id\":48213,"
        + "\"status\":\"pending\",\"total\":{\"$numberDecimal\":\"120.50\"},\"customer\":{\"id\":{\"$numberLong\":"
        + "\"9007199254740993\"}}}");

    JsonNode grouped = call(3, "mongo_aggregate", "{\"connection\":\"orders-mongo\",\"collection\":\"orders\","
        + "\"pipeline\":[{\"$match\":{\"total\":{\"$gt\":{\"$numberDecimal\":\"1\"}}}},{\"$group\":{\"_id\":"
        + "\"$status\",\"n\":{\"$sum\":1}}},{\"$sort\":{\"_id\":1}}]}");
    assertThat(grouped.at("/result/structuredContent/documents").toString()).as(grouped.toString())
        .isEqualTo("[{\"_id\":\"pending\",\"n\":2},{\"_id\":\"shipped\",\"n\":1}]");
  }

  @Test
  @Order(5)
  void writesJavascriptAndSystemNamespacesAreDeniedBeforeTheServer() throws Exception {
    JsonNode out = call(4, "mongo_aggregate", "{\"connection\":\"orders-mongo\",\"collection\":\"orders\","
        + "\"pipeline\":[{\"$facet\":{\"a\":[{\"$match\":{}}]}},{\"$out\":\"stolen\"}]}");
    JsonNode where = call(5, "mongo_find", "{\"connection\":\"orders-mongo\",\"collection\":\"orders\",\"filter\":"
        + "{\"$or\":[{\"$where\":\"sleep(5000) || true\"}]}}");
    JsonNode system = call(6, "mongo_find", "{\"connection\":\"orders-mongo\",\"database\":\"admin\","
        + "\"collection\":\"system.users\"}");

    assertThat(out.at("/result/content/0/text").asString()).as(out.toString())
        .startsWith("Denied by policy dev.mongodb.write-stage");
    assertThat(where.at("/result/content/0/text").asString()).as(where.toString())
        .startsWith("Denied by policy dev.mongodb.server-js");
    assertThat(system.at("/result/content/0/text").asString()).as(system.toString())
        .startsWith("Denied by policy dev.mongodb.namespace-not-allowed");
    assertThat(mongod.collections()).doesNotContain("stolen");
  }

  @Test
  @Order(6)
  void theConnectionsPasswordInADocumentIsMasked() throws Exception {
    JsonNode notes = call(7, "mongo_find", "{\"connection\":\"orders-mongo\",\"collection\":\"notes\"}");

    assertThat(notes.at("/result/isError").asBoolean(true)).as(notes.toString()).isFalse();
    assertThat(notes.toString()).doesNotContain(LocalMongod.password("gw_rw")).contains("‹masked›");
  }

  @Test
  @Order(7)
  void describeConnectionListsWhatTheAgentMayRead() throws Exception {
    JsonNode d = call(8, "describe_connection", "{\"connection\":\"orders-mongo\"}");

    JsonNode details = d.at("/result/structuredContent/details");
    assertThat(details.isObject()).as(d.toString()).isTrue();
    List<String> databases = new ArrayList<>();
    details.path("databases").forEach(db -> databases.add(db.path("name").asString()));
    assertThat(databases).contains("orders").doesNotContain("admin", "local", "config");
    assertThat(details.path("readsOnly").asString()).isNotBlank();
  }

  @Test
  @Order(8)
  void aReleaseIgnoresTheTestDnsServer() throws Exception {
    // A native test binary is built as no release (BuildFlags is fixed at build time), so only the JVM can be one
    // here: -Dgateway.release=true is what the JVM fallback artifact's launcher sets.
    Assumptions.assumeFalse(CoreProcess.nativeBinary(), "the native test binary isn't a release build");
    int before = dns.asked.size();
    try (CoreProcess release = CoreProcess.start(CoreProcess.newHome("mongodb-release"), CoreProcess.freePort(),
        List.of("-Dgateway.release=true", "-Dgateway.test.dns-server=" + dns.address())).bootstrap()) {
      List<JsonNode> lines = release.untilReady(READY);
      assertThat(lines.getLast().path("ready").asBoolean()).as(release.stderr()).isTrue();
      String target = "/api/connections/test";
      HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + release.port
          + target)).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
          .header("X-Gateway-Auth", release.secret().sign("POST", target, System.currentTimeMillis(), nonce()))
          .POST(HttpRequest.BodyPublishers.ofString("{\"type\":\"mongodb\",\"env\":\"dev\",\"config\":{"
              + "\"connection\":{\"uri\":\"mongodb+srv://rs.0.0.1/orders\"},\"advanced\":"
              + "{\"serverSelectionTimeoutMs\":2000}}}")).build(), HttpResponse.BodyHandlers.ofString());

      assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
      assertThat(CoreProcess.JSON.readTree(r.body()).path("ok").asBoolean()).as(r.body()).isFalse();
      // The system's resolvers were asked (and don't know rs.0.0.1); the stub never was.
      assertThat(dns.asked).hasSize(before);
    }
  }

  // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static List<String> names(JsonNode tools) {
    List<String> out = new ArrayList<>();
    tools.forEach(t -> out.add(t.path("name").asString()));
    return out;
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
    HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(uri("/mcp/mongo"))
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
