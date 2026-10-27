package ai.msrv.gaterook.e2e.nativeimage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.msrv.gaterook.e2e.TestServices;
import ai.msrv.gaterook.e2e.platform.CoreProcess;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Step 5 of the native smoke (report 15 §6, ARCHITECTURE.md §10 N1): every connector module describes "touches" in
 * {@code META-INF/mcp-gateway/smoke/<area>.json} — calls and tests that reach the libraries the image must hold
 * (parsers, drivers, HTTP clients, google-auth, the AWS SDK, Fabric8). Each one runs here through the core as a
 * person and an agent use it: a connection made through the admin API, then Test connection, or a tool called over
 * MCP on an endpoint with all the touches' connections, and the answer checked against the descriptor's
 * {@code expect}.
 *
 * <p>Touches without a fixture need no server. Those with one get it from the Docker test services
 * ({@code -Pservices}: {@code postgres}, {@code redis}, {@code loki}); the rest ({@code mongod-srv},
 * {@code kube-apiserver}, {@code google}, {@code aws}) are aborted with the name of the area's {@code *CoreProcessIT},
 * which runs the same touch against its own fakes and which {@code scripts/native-smoke.sh} runs against the binary
 * too. With {@code -Dgateway.core.binary} the core is the native image; otherwise {@code app.jar}.
 */
class ConnectorTouchesIT {

  private static final Duration READY = Duration.ofSeconds(90);
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final String ENDPOINT = "touches";

  /** Fixtures another IT provides against the binary, by name: that IT. */
  private static final Map<String, String> ELSEWHERE = Map.of("mongod-srv", "MongoCoreProcessIT",
      "kube-apiserver", "KubernetesCoreProcessIT", "google", "GcpCoreProcessIT", "aws", "AwsCoreProcessIT");

  private static CoreProcess core;
  private static String token;
  private static final AtomicInteger IDS = new AtomicInteger(1);
  private static final Map<String, Map<String, Object>> FIXTURES = new HashMap<>();

  @BeforeAll
  static void start() {
    core = CoreProcess.start(CoreProcess.newHome("touches"), CoreProcess.freePort(), List.of()).bootstrap();
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
  }

  @TestFactory
  Stream<DynamicTest> everyTouchOfEveryConnectorHolds() throws Exception {
    assertThat(core.untilReady(READY).getLast().path("ready").asBoolean()).as(core.stderr()).isTrue();
    json(admin("POST", "/api/endpoints", "{\"slug\":\"" + ENDPOINT + "\",\"startFrom\":\"empty\"}"), 201);
    token = json(admin("POST", "/api/endpoints/" + ENDPOINT + "/tokens", "{\"name\":\"touches\",\"ttlDays\":1}"),
        201).path("token").asString();

    List<DynamicTest> tests = new ArrayList<>();
    Resource[] descriptors = new PathMatchingResourcePatternResolver()
        .getResources("classpath*:META-INF/mcp-gateway/smoke/*.json");
    assertThat(descriptors).as("descriptors of the connector modules").hasSizeGreaterThanOrEqualTo(7);
    for (Resource r : descriptors) {
      JsonNode d;
      try (InputStream in = r.getInputStream()) {
        d = CoreProcess.JSON.readTree(in);
      }
      String area = d.path("area").asString();
      int n = 0;
      for (JsonNode touch : d.path("touches")) {
        String name = area + "-" + (++n);
        tests.add(DynamicTest.dynamicTest(area + " · " + touch.path("name").asString(), () -> run(name, touch)));
      }
    }
    return tests.stream();
  }

  private static void run(String name, JsonNode touch) throws Exception {
    Map<String, Object> fixture = Map.of();
    if (touch.hasNonNull("fixture")) {
      String f = touch.path("fixture").asString();
      assumeTrue(!ELSEWHERE.containsKey(f), "fixture " + f + ": " + ELSEWHERE.get(f) + " runs this touch");
      assumeTrue(TestServices.enabled(), "fixture " + f + " needs the Docker test services (-Pservices)");
      fixture = fixture(f);
    }
    JsonNode connection = resolve(touch.path("connection"), fixture);
    JsonNode expect = touch.path("expect");
    if (touch.path("test").asBoolean(false)) {
      JsonNode tested = json(admin("POST", "/api/connections/test", draft(connection).toString()), 200);
      assertThat(tested.path("ok").asBoolean()).as(tested.toString()).isEqualTo(expect.path("ok").asBoolean());
      if (expect.has("errorCode")) {
        assertThat(tested.path("error").path("code").asString()).as(tested.toString())
            .isEqualTo(expect.path("errorCode").asString());
      }
      return;
    }
    ObjectNode create = draft(connection);
    create.put("name", name);
    json(admin("POST", "/api/connections", create.toString()), 201);
    json(admin("POST", "/api/endpoints/" + ENDPOINT + "/connections/" + name, "{}"), 200);
    String tool = touch.at("/call/tool").asString();
    switchOn(name, tool);

    ObjectNode arguments = ((ObjectNode) touch.at("/call/arguments").deepCopy()).put("connection", name);
    JsonNode answer = mcp("{\"jsonrpc\":\"2.0\",\"id\":" + IDS.getAndIncrement() + ",\"method\":\"tools/call\","
        + "\"params\":{\"name\":\"" + tool + "\",\"arguments\":" + arguments + "}}");
    JsonNode result = answer.path("result");
    assertThat(result.isMissingNode()).as(answer.toString()).isFalse();
    assertThat(result.path("isError").asBoolean(false)).as(result.toString())
        .isEqualTo(expect.path("isError").asBoolean());
    if (expect.has("textStartsWith")) {
      assertThat(result.at("/content/0/text").asString()).as(result.toString())
          .startsWith(expect.path("textStartsWith").asString());
    }
    if (expect.has("structuredContent")) {
      expect.path("structuredContent").properties().forEach(e -> assertThat(result.path("structuredContent")
          .get(e.getKey())).as(e.getKey() + " in " + result).isEqualTo(e.getValue()));
    }
  }

  /**
   * A tool that starts Off on an endpoint (k8s_apply, like in the kit) is switched to Approval for the connection, as
   * a person does it, and waited for in tools/list. The touches of such tools end at the policy, before any approval.
   */
  private static void switchOn(String connection, String tool) throws Exception {
    JsonNode row = null;
    for (JsonNode g : json(admin("GET", "/api/endpoints/" + ENDPOINT + "/tools", null), 200)) {
      for (JsonNode t : g.path("tools")) {
        if (g.path("connection").asString().equals(connection) && t.path("name").asString().equals(tool)) {
          row = t;
        }
      }
    }
    assertThat(row).as(tool + " of " + connection + " on the endpoint").isNotNull();
    if (!row.path("mode").asString().equals("OFF")) {
      return;
    }
    ObjectNode on = NODES.objectNode().put("toolKey", row.path("toolKey").asString()).put("connection", connection)
        .put("mode", "APPROVAL").put("version", row.path("version").asLong()).put("reason", "Native smoke touch");
    json(admin("PUT", "/api/endpoints/" + ENDPOINT + "/tools", on.toString()), 200);
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (true) {
      List<String> names = new ArrayList<>();
      mcp("{\"jsonrpc\":\"2.0\",\"id\":" + IDS.getAndIncrement() + ",\"method\":\"tools/list\",\"params\":{}}")
          .at("/result/tools").forEach(t -> names.add(t.path("name").asString()));
      if (names.contains(tool) || System.nanoTime() > deadline) {
        assertThat(names).as("tools/list after switching " + tool + " on").contains(tool);
        return;
      }
      Thread.sleep(200);
    }
  }

  /** The admin API's body for a connection: type, env, config and the secrets as typed values. */
  private static ObjectNode draft(JsonNode connection) {
    ObjectNode body = NODES.objectNode();
    body.put("type", connection.path("type").asString());
    body.put("env", connection.path("env").asString());
    body.set("config", connection.path("config"));
    ObjectNode secrets = body.putObject("secrets");
    connection.path("secrets").properties().forEach(e -> secrets.putObject(e.getKey()).put("source", "local")
        .put("value", e.getValue().asString()));
    return body;
  }

  // ── fixtures (the Docker test services) ─────────────────────────────────────────────────────────────────────

  private static synchronized Map<String, Object> fixture(String name) throws Exception {
    Map<String, Object> made = FIXTURES.get(name);
    if (made == null) {
      made = switch (name) {
        case "postgres" -> postgres();
        case "redis" -> redis();
        case "loki" -> loki();
        default -> throw new IllegalArgumentException("No fixture " + name);
      };
      FIXTURES.put(name, made);
    }
    return made;
  }

  /** The superuser of the services' PostgreSQL over TLS (its self-signed certificate for localhost), database app. */
  private static Map<String, Object> postgres() throws SQLException, IOException {
    int port = TestServices.port("GW_PG_PORT");
    String password = TestServices.value("GW_PG_PASSWORD");
    try (Connection c = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + port + "/postgres", "postgres",
        password); Statement st = c.createStatement()) {
      if (!st.executeQuery("SELECT 1 FROM pg_database WHERE datname = 'app'").next()) {
        st.execute("CREATE DATABASE app");
      }
    }
    return Map.of("port", port, "caCertificate", Files.readString(TestServices.file("run/certs/postgres.crt")),
        "user", "postgres", "password", password);
  }

  /** The services' Redis primary over TLS (its CA), user ro of the ACL file, the list smoke:list = a, b, c. */
  private static Map<String, Object> redis() throws IOException {
    try (Jedis j = new Jedis(new HostAndPort("127.0.0.1", TestServices.port("GW_REDIS_PORT")),
        DefaultJedisClientConfig.builder().password("default-pass").build())) {
      j.aclLoad();
      j.del("smoke:list");
      j.rpush("smoke:list", "a", "b", "c");
    }
    return Map.of("tlsPort", TestServices.port("GW_REDIS_TLS_PORT"),
        "caCertificate", Files.readString(TestServices.file("run/certs/ca.crt")), "user", "ro", "password", "ro-pass");
  }

  /** The services' Loki with an entry of {app="smoke"} for a tenant of this run. */
  private static Map<String, Object> loki() throws Exception {
    String url = "http://127.0.0.1:" + TestServices.port("GW_LOKI_PORT");
    String tenant = "touches-" + Long.toString(System.currentTimeMillis(), 36);
    Instant at = Instant.now().minusSeconds(30);
    ObjectNode body = NODES.objectNode();
    ObjectNode stream = body.putArray("streams").addObject();
    stream.putObject("stream").put("app", "smoke");
    ArrayNode values = stream.putArray("values");
    values.addArray().add(Long.toString(at.getEpochSecond() * 1_000_000_000L + at.getNano())).add("smoke one");
    values.addArray().add(Long.toString(at.plusSeconds(1).getEpochSecond() * 1_000_000_000L)).add("smoke two");
    HttpResponse<String> pushed = HTTP.send(HttpRequest.newBuilder(URI.create(url + "/loki/api/v1/push"))
        .header("Content-Type", "application/json").header("X-Scope-OrgID", tenant)
        .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
    assertThat(pushed.statusCode()).as(pushed.body()).isEqualTo(204);
    return Map.of("url", url, "tenant", tenant);
  }

  /**
   * {@code ${fixture.x}} in every string of the tree; a string that is only the placeholder takes the value's own JSON
   * type ({@code "${fixture.port}"} becomes a number).
   */
  private static JsonNode resolve(JsonNode node, Map<String, Object> fixture) {
    if (node.isString()) {
      String text = node.asString();
      if (text.startsWith("${fixture.") && text.endsWith("}") && text.indexOf('}') == text.length() - 1) {
        Object value = fixture.get(text.substring("${fixture.".length(), text.length() - 1));
        assertThat(value).as("fixture value of " + text).isNotNull();
        return value instanceof Number number ? NODES.numberNode(number.longValue()) : NODES.stringNode(
            value.toString());
      }
      for (Map.Entry<String, Object> e : fixture.entrySet()) {
        text = text.replace("${fixture." + e.getKey() + "}", e.getValue().toString());
      }
      return NODES.stringNode(text);
    }
    if (node.isObject()) {
      ObjectNode out = NODES.objectNode();
      node.properties().forEach(e -> out.set(e.getKey(), resolve(e.getValue(), fixture)));
      return out;
    }
    if (node.isArray()) {
      ArrayNode out = NODES.arrayNode();
      node.forEach(v -> out.add(resolve(v, fixture)));
      return out;
    }
    return node;
  }

  // ── the core ────────────────────────────────────────────────────────────────────────────────────────────────

  private record Answer(int status, String body) {}

  private static Answer admin(String method, String target, String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + core.port + target))
        .timeout(Duration.ofSeconds(60))
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

  private static JsonNode mcp(String message) throws Exception {
    HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + core.port + "/mcp/"
            + ENDPOINT)).timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", "2025-11-25")
        .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.ofString(message,
            StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    return CoreProcess.JSON.readTree(r.body());
  }

  private static String nonce() {
    byte[] b = new byte[16];
    new SecureRandom().nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }
}
