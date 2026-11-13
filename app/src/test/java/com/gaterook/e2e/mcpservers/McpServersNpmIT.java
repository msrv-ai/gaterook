package com.gaterook.e2e.mcpservers;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.e2e.platform.CoreProcess;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Real servers from npm on the assembled core (profile {@code mcp-servers}; with {@code -Dgateway.core.binary} the
 * native executable: the MCP SDK's records in the image, both transports):
 * {@code @modelcontextprotocol/server-everything} 2.0.0 over stdio and Streamable HTTP. The tools retain their input
 * schemas and declared read-only modes, mutations require approval, and a bulk change turns every tool Off.
 * HTTP is reached only once the environment's policy allows its address, with an explicitly approved {@code echo}.
 * The package is installed with
 * {@code npm ci --ignore-scripts} from the lockfiles in {@code src/test/resources/mcp-servers} into
 * {@code ~/.cache/mcp-gateway-test/c10-mcp-servers/app}; Node 24 from {@code MCP_NODE} or the PATH.
 */
@Tag("mcp-servers")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class McpServersNpmIT {

  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final Path CACHE = Path.of(System.getProperty("user.home"), ".cache", "mcp-gateway-test",
      "c10-mcp-servers", "app");
  private static final String SERVER_TOKEN = "fixture-token-" + "e2e".repeat(12);
  private static CoreProcess core;
  private static Path everything;
  private static Process everythingHttp;
  private static int everythingPort;
  private static String token;

  @BeforeAll
  static void start() throws Exception {
    everything = install("@modelcontextprotocol/server-everything", "2.0.0", "server-everything-2.0.0");
    try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      everythingPort = s.getLocalPort();
    }
    ProcessBuilder pb = new ProcessBuilder(node(), everything.resolve(
        "node_modules/@modelcontextprotocol/server-everything/dist/index.js").toString(), "streamableHttp")
        .redirectErrorStream(true);
    pb.environment().put("PORT", Integer.toString(everythingPort));
    everythingHttp = pb.start();
    awaitLine(everythingHttp.getInputStream(), "listening on port " + everythingPort, Duration.ofSeconds(30));
    core = CoreProcess.start(CoreProcess.newHome("mcp-servers-npm"), CoreProcess.freePort(), List.of()).bootstrap();
    core.untilReady(Duration.ofSeconds(90));
  }

  @AfterAll
  static void stop() throws InterruptedException {
    if (core != null) {
      core.close();
    }
    if (everythingHttp != null) {
      everythingHttp.destroy();
      everythingHttp.waitFor(5, TimeUnit.SECONDS);
      everythingHttp.destroyForcibly();
    }
  }

  @Test
  @Order(1)
  void serverEverythingPreservesSchemasAndModesBeforeAllToolsAreTurnedOff() throws Exception {
    ObjectNode request = CoreProcess.JSON.createObjectNode().put("acknowledge", true);
    ObjectNode config = request.putObject("config").put("name", "stdio-everything").put("transport", "stdio");
    ArrayNode command = config.putArray("command");
    command.add(node());
    command.add(everything.resolve("node_modules/@modelcontextprotocol/server-everything/dist/index.js").toString());
    command.add("stdio");
    config.putArray("env").addObject().put("name", "MCP_FIXTURE_TOKEN");
    request.putObject("secrets").putObject("/env/0/secretRef").put("source", "local").put("value", SERVER_TOKEN);
    JsonNode added = json(admin("POST", "/api/mcp-servers", request.toString()), 201);
    int toolCount = added.path("tools").asInt();
    assertThat(toolCount).isGreaterThanOrEqualTo(16);

    JsonNode tools = json(admin("GET", "/api/mcp-servers/stdio-everything/tools", null), 200);
    Map<String, String> expectedModes = new LinkedHashMap<>();
    assertThat(tools).hasSize(toolCount).allSatisfy(t -> {
      assertThat(t.path("name").asString()).startsWith("stdio_");
      assertThat(t.at("/inputSchema/type").asString()).isEqualTo("object");
      expectedModes.put(t.path("name").asString(), "read".equals(t.path("nature").asString())
          ? "READ_ONLY" : "APPROVAL");
      if (t.path("original").asString().equals("echo")) {
        assertThat(t.at("/inputSchema/properties/message/type").asString()).isEqualTo("string");
        assertThat(t.at("/inputSchema/required")).extracting(JsonNode::asString).contains("message");
      }
    });
    assertThat(expectedModes).containsEntry("stdio_echo", "READ_ONLY")
        .containsEntry("stdio_gzip_file_as_resource", "APPROVAL");

    json(admin("POST", "/api/endpoints", "{\"slug\":\"stdio\",\"startFrom\":\"empty\"}"), 201);
    json(admin("POST", "/api/endpoints/stdio/connections/stdio-everything", "{}"), 200);
    List<JsonNode> rows = rows("stdio", "stdio-everything");
    assertThat(rows).hasSize(toolCount).allSatisfy(t -> assertThat(t.path("mode").asString())
        .isEqualTo(expectedModes.get(t.path("name").asString())));
    JsonNode bulk = json(admin("POST", "/api/endpoints/stdio/tools/bulk",
        "{\"group\":\"stdio-everything\",\"mode\":\"OFF\"}"), 200);
    assertThat(bulk.path("changed")).hasSize(toolCount);
    assertThat(rows("stdio", "stdio-everything"))
        .allSatisfy(t -> assertThat(t.path("mode").asString()).isEqualTo("OFF"));
    assertThat(core.coreLog()).doesNotContain(SERVER_TOKEN);
  }

  @Test
  @Order(2)
  void serverEverythingAnswersOverStreamableHttpOnceItsAddressIsAllowed() throws Exception {
    String url = "http://127.0.0.1:" + everythingPort + "/mcp";
    String add = "{\"acknowledge\":true,\"config\":{\"name\":\"everything-http\",\"transport\":\"http\",\"url\":\""
        + url + "\"}}";
    Answer refused = admin("POST", "/api/mcp-servers", add);
    assertThat(refused.status()).isEqualTo(422);
    assertThat(refused.body()).contains("Allow 127.0.0.1/32 for everything-http");

    JsonNode dev = json(admin("GET", "/api/policies/dev", null), 200);
    json(admin("PUT", "/api/policies/dev", "{\"version\":" + dev.path("version").asLong() + ",\"sections\":{\"http\":"
        + "{\"allowedPrivateRanges\":[{\"cidr\":\"127.0.0.1/32\",\"connections\":[\"everything-http\"]}]}}}"), 200);
    JsonNode added = json(admin("POST", "/api/mcp-servers", add), 201);
    assertThat(added.path("tools").asInt()).isPositive();

    json(admin("POST", "/api/endpoints", "{\"slug\":\"every\",\"startFrom\":\"empty\"}"), 201);
    json(admin("POST", "/api/endpoints/every/connections/everything-http", "{}"), 200);
    JsonNode echo = rows("every", "everything-http").stream()
        .filter(t -> t.path("name").asString().equals("everything_echo")).findFirst().orElseThrow();
    json(admin("PUT", "/api/endpoints/every/tools", "{\"toolKey\":\"" + echo.path("toolKey").asString()
        + "\",\"connection\":\"everything-http\",\"mode\":\"APPROVAL\",\"version\":"
        + echo.path("version").asLong() + "}"), 200);
    JsonNode settings = json(admin("GET", "/api/endpoints/every/settings", null), 200);
    json(admin("PUT", "/api/endpoints/every/settings", "{\"approvalWait\":{\"firstWaitSeconds\":0},\"version\":"
        + settings.path("version").asLong() + "}"), 200);
    token = json(admin("POST", "/api/endpoints/every/tokens", "{\"name\":\"e2e\",\"ttlDays\":1}"), 201)
        .path("token").asString();
    mcp("""
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},
         "clientInfo":{"name":"mcp-servers-npm","version":"1"}}}""");

    JsonNode pending = mcp("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":"
        + "\"everything_echo\",\"arguments\":{\"message\":\"hello from the gateway\"}}}");
    String id = pending.at("/result/structuredContent/handle").asString();
    assertThat(id).as(pending.toString()).startsWith("ap_");
    JsonNode detail = json(admin("GET", "/api/approvals/" + id, null), 200);
    json(admin("POST", "/api/approvals/" + id + "/decision", "{\"decision\":\"approve\",\"digest\":\""
        + detail.path("digest").asString() + "\",\"via\":\"app\"}"), 200);
    JsonNode status = mcp("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":"
        + "\"approval_status\",\"arguments\":{\"handle\":\"" + id + "\",\"wait_seconds\":20}}}");
    assertThat(status.at("/result/structuredContent/status").asString()).as(status.toString()).isEqualTo("completed");
    assertThat(status.toString()).contains("hello from the gateway");
  }

  // ── Fixtures ────────────────────────────────────────────────────────────────────────────────────────────────

  private static String node() {
    String node = System.getenv("MCP_NODE");
    return node == null || node.isBlank() ? "node" : node;
  }

  /** {@code npm ci --ignore-scripts} of one pinned package into its own folder of the test cache. */
  private static Path install(String name, String version, String resource) throws Exception {
    Path dir = CACHE.resolve(resource);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("package.json"), "{\"name\":\"mcp-gateway-server\",\"private\":true,"
        + "\"dependencies\":{\"" + name + "\":\"" + version + "\"}}\n");
    byte[] lock;
    try (InputStream in = McpServersNpmIT.class.getResourceAsStream("/mcp-servers/" + resource
        + "/package-lock.json")) {
      lock = in.readAllBytes();
    }
    Files.write(dir.resolve("package-lock.json"), lock);
    String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(lock));
    Path installedLock = dir.resolve(".installed-lock-sha256");
    if (Files.isRegularFile(dir.resolve("node_modules/" + name + "/package.json"))
        && Files.isRegularFile(installedLock) && fingerprint.equals(Files.readString(installedLock))) {
      return dir;
    }
    String npm = Path.of(node()).getParent() == null ? "npm" : Path.of(node()).resolveSibling("npm").toString();
    Process p = new ProcessBuilder(npm, "ci", "--ignore-scripts", "--no-audit", "--no-fund", "--loglevel=error")
        .directory(dir.toFile()).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(p.waitFor(5, TimeUnit.MINUTES)).isTrue();
    assertThat(p.exitValue()).as(out).isZero();
    Files.writeString(installedLock, fingerprint);
    return dir;
  }

  private static void awaitLine(InputStream stream, String needle, Duration within) throws InterruptedException {
    CountDownLatch seen = new CountDownLatch(1);
    Thread.ofVirtual().start(() -> {
      try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          if (line.contains(needle)) {
            seen.countDown();
          }
        }
      } catch (IOException e) {
        // The process ended.
      }
    });
    assertThat(seen.await(within.toMillis(), TimeUnit.MILLISECONDS)).as("the server printed: " + needle).isTrue();
  }

  private record Answer(int status, String body) {}

  private static List<JsonNode> rows(String slug, String connection) throws Exception {
    List<JsonNode> out = new ArrayList<>();
    json(admin("GET", "/api/endpoints/" + slug + "/tools", null), 200).forEach(g -> {
      if (g.path("connection").asString().equals(connection)) {
        g.path("tools").forEach(out::add);
      }
    });
    return out;
  }

  private static URI uri(String target) {
    return URI.create("http://127.0.0.1:" + core.port + target);
  }

  private static Answer admin(String method, String target, String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(uri(target)).timeout(Duration.ofSeconds(120))
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
    HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(uri("/mcp/every"))
        .timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", "2025-11-25")
        .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.ofString(message)).build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    return CoreProcess.JSON.readTree(r.body());
  }

  private static String nonce() {
    byte[] bytes = new byte[16];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
