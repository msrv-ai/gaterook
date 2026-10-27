package ai.msrv.gaterook.e2e.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.msrv.gaterook.e2e.platform.CoreProcess;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import redis.clients.jedis.Jedis;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Redis connector through the assembled core (step C3, ARCHITECTURE.md §10): the window's admin API tests and
 * saves a connection to the machine's redis-server over TLS (a private CA, full verification for localhost) as the
 * read-only ACL user, with a key denylist; an endpoint and a token; an agent then reads, scans with cursors and is
 * refused a denied key. A value holds the connection's own password: the agent sees {@code ‹masked›} (§9 "the agent
 * never sees a secret"), and the server's command statistics show that no write command ever ran.
 *
 * <p>On the JVM this runs {@code app.jar}; with {@code -Dgateway.core.binary=<path>} it is the native touch of the
 * connector: Jedis' connection and its {@code pom.properties}, JSSE with the private CA, the sealed cursors.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisCoreProcessIT {

  private static final Duration READY = Duration.ofSeconds(90);
  private static final String RO_PASSWORD = "cnry-redis-" + Long.toHexString(System.nanoTime());
  private static final String ADMIN_PASSWORD = "admin-" + Long.toHexString(System.nanoTime());
  private static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
      .connectTimeout(Duration.ofSeconds(5)).build();
  /** Commands that change data or the server; none may have run for the connector. */
  private static final Set<String> WRITES = Set.of("set", "setex", "psetex", "setnx", "getset", "getdel", "getex",
      "del", "unlink", "expire", "pexpire", "persist", "rename", "renamenx", "copy", "move", "restore", "migrate",
      "flushall", "flushdb", "hset", "hdel", "lpush", "rpush", "lpop", "rpop", "lset", "ltrim", "sadd", "srem", "spop",
      "zadd", "zrem", "xadd", "xdel", "xtrim", "eval", "evalsha", "fcall", "function", "script", "keys", "debug",
      "shutdown", "save", "bgsave", "bgrewriteaof", "replicaof", "slaveof", "module", "acl|setuser");

  private static LocalRedisServer redis;
  private static CoreProcess core;
  private static String token;

  @BeforeAll
  static void start() throws Exception {
    Path binary = LocalRedisServer.binary();
    assumeTrue(binary != null, "redis-server isn't on PATH; set GW_REDIS_BIN to run this test");
    redis = LocalRedisServer.start(binary, ADMIN_PASSWORD, RO_PASSWORD);
    try (Jedis j = redis.admin()) {
      j.set("cache:greeting", "hello from redis");
      // A value that quotes the connection's own password: the core masks it in the answer.
      j.set("cache:echo", "reconnect with AUTH ro " + RO_PASSWORD);
      j.set("session:1", "a session the denylist hides");
      for (int i = 0; i < 30; i++) {
        j.set("bulk:" + i, "v" + i);
      }
      j.configResetStat();
    }
    core = CoreProcess.start(CoreProcess.newHome("redis"), CoreProcess.freePort(), List.of()).bootstrap();
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
    if (redis != null) {
      redis.close();
    }
  }

  private static String config() {
    ObjectNode c = JsonNodeFactory.instance.objectNode();
    c.putObject("connection").put("host", "localhost").put("port", redis.tlsPort).put("tls", true)
        .put("tlsVerify", "full").put("caCertificate", redis.caPem);
    c.putObject("credentials").put("user", "ro");
    c.putObject("advanced").putArray("keyDenylist").add("session:*");
    return c.toString();
  }

  private static String secrets() {
    return "{\"/credentials/password\":{\"source\":\"local\",\"value\":\"" + RO_PASSWORD + "\"}}";
  }

  @Test
  @Order(1)
  void theConnectionIsTestedOverTlsAndSaved() throws Exception {
    assertThat(core.untilReady(READY).getLast().path("ready").asBoolean()).as(core.stderr()).isTrue();
    JsonNode tested = json(admin("POST", "/api/connections/test", "{\"type\":\"redis\",\"env\":\"dev\",\"config\":"
        + config() + ",\"secrets\":" + secrets() + "}"), 200);
    assertThat(tested.path("ok").asBoolean()).as(tested.toString()).isTrue();
    assertThat(tested.path("serverVersion").asString()).startsWith("Redis 7.");
    assertThat(tested.path("identity").asString()).isEqualTo("ro");
    assertThat(tested.path("permissions").path("level").asString()).isEqualToIgnoringCase("read");
    assertThat(tested.toString()).doesNotContain(RO_PASSWORD);

    json(admin("POST", "/api/connections", "{\"name\":\"cache-redis\",\"type\":\"redis\",\"env\":\"dev\",\"config\":"
        + config() + ",\"secrets\":" + secrets() + "}"), 201);
    // The stored test records the server's version for the tool descriptions.
    JsonNode stored = json(admin("POST", "/api/connections/cache-redis/test", null), 200);
    assertThat(stored.path("ok").asBoolean()).as(stored.toString()).isTrue();

    json(admin("POST", "/api/endpoints", "{\"slug\":\"cache\",\"startFrom\":\"empty\"}"), 201);
    json(admin("POST", "/api/endpoints/cache/connections/cache-redis", "{}"), 200);
    // describe_connection starts Off on a new endpoint; a person switches it to Read only.
    JsonNode describe = null;
    for (JsonNode g : json(admin("GET", "/api/endpoints/cache/tools", null), 200)) {
      for (JsonNode t : g.path("tools")) {
        if (t.path("name").asString().equals("describe_connection")) {
          describe = t;
        }
      }
    }
    assertThat(describe).isNotNull();
    ObjectNode on = JsonNodeFactory.instance.objectNode().put("toolKey", describe.path("toolKey").asString())
        .put("mode", "READ_ONLY").put("version", describe.path("version").asLong());
    json(admin("PUT", "/api/endpoints/cache/tools", on.toString()), 200);
    token =json(admin("POST", "/api/endpoints/cache/tokens", "{\"name\":\"redis-e2e\",\"ttlDays\":1}"), 201)
        .path("token").asString();
    assertThat(token).startsWith("gwt_");
  }

  @Test
  @Order(2)
  void theToolsNameTheServer() throws Exception {
    mcp("""
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},
         "clientInfo":{"name":"redis-e2e","version":"1"}}}""");
    Map<String, JsonNode> tools = new HashMap<>();
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while ((!tools.containsKey("redis_get") || !tools.containsKey("redis_scan")) && System.nanoTime() < deadline) {
      tools.clear();
      for (JsonNode t : mcp("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}")
          .at("/result/tools")) {
        tools.put(t.path("name").asString(), t);
      }
      if (!tools.containsKey("redis_get")) {
        Thread.sleep(200);
      }
    }
    assertThat(tools).containsKeys("redis_get", "redis_scan");
    assertThat(tools.get("redis_get").path("description").asString()).contains("cache-redis (Redis 7, dev)");
    assertThat(tools.get("redis_get").path("annotations").path("readOnlyHint").asBoolean()).isTrue();
  }

  @Test
  @Order(3)
  void anAgentReadsScansAndIsRefusedTheDeniedKey() throws Exception {
    JsonNode hello = call(3, "redis_get", "{\"connection\":\"cache-redis\",\"key\":\"cache:greeting\"}");
    assertThat(hello.at("/result/isError").asBoolean(true)).as(hello.toString()).isFalse();
    assertThat(hello.at("/result/structuredContent/value").asString()).isEqualTo("hello from redis");

    JsonNode echo = call(4, "redis_get", "{\"connection\":\"cache-redis\",\"key\":\"cache:echo\"}");
    assertThat(echo.at("/result/isError").asBoolean(true)).as(echo.toString()).isFalse();
    assertThat(echo.toString()).doesNotContain(RO_PASSWORD).contains("‹masked›");

    JsonNode denied = call(5, "redis_get", "{\"connection\":\"cache-redis\",\"key\":\"session:1\"}");
    assertThat(denied.at("/result/isError").asBoolean()).isTrue();
    assertThat(denied.at("/result/content/0/text").asString()).startsWith("Denied by policy dev.redis.key-not-allowed");

    Set<String> keys = new HashSet<>();
    String cursor = null;
    int pages = 0;
    do {
      JsonNode page = call(10 + pages, "redis_scan", "{\"connection\":\"cache-redis\",\"pattern\":\"*\",\"count\":10"
          + (cursor == null ? "" : ",\"cursor\":\"" + cursor + "\"") + "}");
      assertThat(page.at("/result/isError").asBoolean(true)).as(page.toString()).isFalse();
      page.at("/result/structuredContent/keys").forEach(k -> keys.add(k.path("key").asString()));
      JsonNode next = page.at("/result/structuredContent/nextCursor");
      cursor = next.isString() ? next.asString() : null;
      if (cursor != null) {
        assertThat(cursor).startsWith("rsc.");
      }
      pages++;
    } while (cursor != null && pages < 40);
    assertThat(keys).contains("cache:greeting", "cache:echo", "bulk:0", "bulk:29").doesNotContain("session:1");
    assertThat(keys.stream().filter(k -> k.startsWith("bulk:"))).hasSize(30);
    assertThat(pages).isGreaterThan(1);

    JsonNode described = call(60, "describe_connection", "{\"connection\":\"cache-redis\"}");
    assertThat(described.at("/result/isError").asBoolean(true)).as(described.toString()).isFalse();
    assertThat(described.toString()).contains("bulk").contains("hiddenByPolicy");
  }

  @Test
  @Order(4)
  void noWriteCommandEverRanOnTheServer() {
    List<String> ran = new ArrayList<>();
    try (Jedis j = redis.admin()) {
      for (String line : j.info("commandstats").split("\r?\n")) {
        // cmdstat_get:calls=3,usec=…,rejected_calls=0,failed_calls=0
        if (line.startsWith("cmdstat_")) {
          String name = line.substring("cmdstat_".length(), line.indexOf(':'));
          long calls = Long.parseLong(line.replaceAll(".*[:,]calls=(\\d+).*", "$1"));
          if (calls > 0) {
            ran.add(name);
          }
        }
      }
    }
    assertThat(ran).contains("get").noneMatch(WRITES::contains);
  }

  @Test
  @Order(5)
  void noCredentialReachesAnAnswerOrTheLog() throws Exception {
    List<String> seen = new ArrayList<>(core.stdoutSoFar());
    seen.add(core.coreLog());
    seen.add(core.stderr());
    seen.add(json(admin("GET", "/api/connections", null), 200).toString());
    seen.add(json(admin("GET", "/api/connections/cache-redis", null), 200).toString());
    seen.add(json(admin("GET", "/api/activity/events", null), 200).toString());
    String everything = String.join("\n", seen);
    for (String canary : List.of(RO_PASSWORD, ADMIN_PASSWORD, token)) {
      assertThat(everything).doesNotContain(canary);
    }
    assertThat(core.coreLog()).doesNotContainPattern("Missing[A-Za-z]*Registration")
        .doesNotContain("UnsupportedFeatureError");
  }

  // ── The window's admin API and the agent's MCP ──────────────────────────────────────────────────────────────

  private record Answer(int status, String body) {}

  private static URI uri(String target) {
    return URI.create("http://127.0.0.1:" + core.port + target);
  }

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

  private static JsonNode mcp(String message) throws Exception {
    HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(uri("/mcp/cache")).timeout(Duration.ofSeconds(40))
        .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
        .header("MCP-Protocol-Version", "2025-11-25").header("Authorization", "Bearer " + token)
        .POST(HttpRequest.BodyPublishers.ofString(message)).build(), HttpResponse.BodyHandlers.ofString());
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
