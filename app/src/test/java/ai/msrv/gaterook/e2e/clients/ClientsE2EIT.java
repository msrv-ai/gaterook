package ai.msrv.gaterook.e2e.clients;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.GatewayApplication;
import ai.msrv.gaterook.core.endpoints.CoreTools;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.EndpointTools;
import ai.msrv.gaterook.core.platform.LaunchSecret;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.platform.boot.GatewayLauncher;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The real clients against the assembled core (ARCHITECTURE.md §10 K1), profile {@code mcp-clients}: Codex
 * {@code 0.162.1} ({@code codex app-server}) connected through the window's Connect, its {@code http_headers_helper}
 * running the shim → {@code gw-bridge.jar} → the broker ({@code bridge/dev-broker.mjs}, as Electron's main runs it) →
 * {@code /internal/bridge/tokens}; tokens live seconds here, so Codex meets a 401 and takes a new one. Claude Desktop's
 * extension is checked by {@code @anthropic-ai/mcpb 2.1.2} ({@code unpack}, {@code validate}, {@code info}) and its
 * bridge, as unpacked, talks to the core. Disconnect gives Codex's config back byte for byte.
 *
 * <p>The packages come from {@code bridge/e2e-clients/package-lock.json} into
 * {@code ~/.cache/mcp-gateway-test/k1-clients} ({@code npm ci} checks every package's integrity hash; override:
 * {@code MCP_K1_CLIENTS}); Node is {@code MCP_NODE}, else {@code /opt/node24/…}, else {@code node} on the PATH.
 */
@Tag("mcp-clients")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClientsE2EIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String LAUNCH_SECRET = Base64.getUrlEncoder().withoutPadding()
      .encodeToString("clients-e2e-it-launch-secret-012".getBytes(StandardCharsets.US_ASCII));
  /** Short, so Codex's first token expires during the test; a release always issues 15 minutes. */
  private static final Duration TTL = Duration.ofSeconds(6);
  private static final Path SOURCES = Path.of("../bridge/e2e-clients").toAbsolutePath().normalize();
  private static final Path BROKER = Path.of("../bridge/dev-broker.mjs").toAbsolutePath().normalize();
  private static final Path BRIDGE_JAR = Path.of("../bridge-cli/target/gw-bridge.jar").toAbsolutePath().normalize();
  private static final Path CLIENTS = Path.of(System.getenv().getOrDefault("MCP_K1_CLIENTS",
      System.getProperty("user.home") + "/.cache/mcp-gateway-test/k1-clients"));
  private static final Pattern GRANT = Pattern.compile("gr_[a-z0-9]{12}");
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private static String node;
  private static Path root;
  private static Path home;
  private static Path userHome;
  private static ConfigurableApplicationContext core;
  private static JdbcTemplate jdbc;
  private static int port;
  private static Process broker;
  private static final List<Process> processes = new ArrayList<>();
  private static String codexBefore;
  private static String codexLink;
  private static String codexGrant;

  @BeforeAll
  static void start() throws Exception {
    node = nodeBinary();
    install();
    assertThat(BRIDGE_JAR).as("bridge-cli is built before app (-pl app -am)").exists();
    root = TestRoots.create("client-e2e");
    home = root.resolve("gateway");
    userHome = Files.createDirectories(root.resolve("user"));
    codexBefore = "model = \"gpt-5.5-codex\"\n\n[mcp_servers.github]\nurl = \"https://api.githubcopilot.com/mcp/\"\n"
        + "bearer_token_env_var = \"GITHUB_PAT\"\n";
    Files.writeString(Files.createDirectories(userHome.resolve(".codex")).resolve("config.toml"), codexBefore);
    Files.createDirectories(userHome.resolve(".config/Claude"));
    core = new SpringApplicationBuilder(GatewayApplication.class)
        .contextFactory(GatewayLauncher.contexts(true))
        .run("--gateway.home=" + home, "--gateway.dev=true", "--gateway.dev.launch-secret=" + LAUNCH_SECRET,
            "--server.port=0", "--gateway.connections.health-interval=0s",
            "--gateway.clients.user-home=" + userHome, "--gateway.clients.os=linux",
            "--gateway.clients.bridge-token-ttl=" + TTL, "--gateway.clients.bridge-jar=" + BRIDGE_JAR);
    jdbc = new JdbcTemplate(core.getBean(javax.sql.DataSource.class));
    port = core.getBean(LocalListener.class).port();
    EndpointService endpoints = core.getBean(EndpointService.class);
    EndpointTools modes = core.getBean(EndpointTools.class);
    endpoints.create("dev", null, EndpointService.StartFrom.EMPTY, null);
    for (String tool : CoreTools.WITH_MODE) {
      var pair = endpoints.snapshot("dev").coreTool(tool).orElseThrow();
      modes.setMode("dev", tool, null, Mode.READ_ONLY, null, pair.rowVersion(), null);
    }
    // The broker as Electron's main process runs it, signing with the launch secret.
    ProcessBuilder pb = new ProcessBuilder(node, BROKER.toString(), "--home", home.toString(), "--origin",
        "http://127.0.0.1:" + port).redirectError(ProcessBuilder.Redirect.INHERIT);
    pb.environment().put("GW_DEV_INTERNAL_SECRET", LAUNCH_SECRET);
    broker = pb.start();
    processes.add(broker);
    String ready = new BufferedReader(new InputStreamReader(broker.getInputStream(), StandardCharsets.UTF_8))
        .readLine();
    assertThat(JSON.readTree(ready).path("ready").asBoolean()).as(ready).isTrue();
    // The core writes the shim a moment after it is ready (ClientDriftWatcher).
    Path shim = home.resolve("bin/gw-bridge");
    await(() -> Files.exists(shim) && read(shim).contains(BRIDGE_JAR.toString()), "the bridge shim");
  }

  @AfterAll
  static void stop() {
    for (Process p : processes) {
      p.descendants().forEach(ProcessHandle::destroyForcibly);
      p.destroyForcibly();
    }
    if (core != null) {
      core.close();
    }
    TestRoots.remove(root);
  }

  // ── Codex ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(1)
  void codexCallsThroughTheHelperAndRotatesItsTokenAfterA401() throws Exception {
    JsonNode preview = admin("POST", "/api/clients/codex/connect/preview", """
        {"endpoint":"dev","method":"helper","grantLabel":"codex-laptop"}""");
    JsonNode connected = admin("POST", "/api/clients/codex/connect",
        "{\"previewId\":\"" + preview.path("previewId").asString() + "\"}");
    codexLink = connected.path("link").path("id").asString();
    String config = read(userHome.resolve(".codex/config.toml"));
    Matcher g = GRANT.matcher(config);
    assertThat(g.find()).isTrue();
    codexGrant = g.group();
    assertThat(config).contains(home.resolve("bin/gw-bridge").toString()).doesNotContain("gwb_");

    try (AppServer codex = AppServer.start()) {
      JsonNode init = codex.call("initialize", JSON.readTree("{\"clientInfo\":{\"name\":\"k1-e2e\",\"version\":\"1\"}}"));
      assertThat(init.has("error")).as(init.toString()).isFalse();
      codex.notify("initialized");
      JsonNode thread = codex.call("thread/start", JSON.createObjectNode());
      String threadId = thread.path("result").path("thread").path("id").asString();
      assertThat(threadId).as(thread.toString()).isNotBlank();

      JsonNode first = callTool(codex, threadId);
      assertThat(first.has("error")).as(first.toString()).isFalse();
      assertThat(first.toString()).contains("connections");
      Instant firstExpires = Instant.ofEpochMilli(jdbc.queryForObject("select max(expires_at) from api_token where "
          + "grant_id = ?", Long.class, codexGrant));
      int before = jdbc.queryForObject("select count(*) from api_token where grant_id = ?", Integer.class, codexGrant);

      // The token Codex holds expires; the core answers 401 and Codex runs the helper again.
      Thread.sleep(Duration.between(Instant.now(), firstExpires).toMillis() + 1_500);
      JsonNode again = callTool(codex, threadId);
      if (again.has("error") || again.toString().contains("\"isError\":true")) {
        // Codex may surface the 401 once before the retry with the new header.
        again = callTool(codex, threadId);
      }
      assertThat(again.has("error")).as(again.toString()).isFalse();
      assertThat(again.toString()).contains("connections");
      // Codex ran the helper again once its token was refused: a new token, issued after the old one expired.
      int after = jdbc.queryForObject("select count(*) from api_token where grant_id = ?", Integer.class, codexGrant);
      assertThat(after).as("a new token after the 401").isGreaterThan(before);
      assertThat(jdbc.queryForObject("select max(created_at) from api_token where grant_id = ?", Long.class,
          codexGrant)).isGreaterThanOrEqualTo(firstExpires.toEpochMilli());
    }
    JsonNode link = link("codex", codexLink);
    assertThat(link.path("status").asString()).isEqualTo("connected");
  }

  private static JsonNode callTool(AppServer codex, String threadId) throws Exception {
    ObjectNode params = JSON.createObjectNode();
    params.put("server", "gaterook-dev");
    params.put("threadId", threadId);
    params.put("tool", CoreTools.LIST_CONNECTIONS);
    params.putObject("arguments");
    return codex.call("mcpServer/tool/call", params);
  }

  // ── Claude Desktop ──────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(2)
  void theExtensionValidatesAndItsBridgeTalksToTheCore() throws Exception {
    JsonNode preview = admin("POST", "/api/clients/claude-desktop/connect/preview", """
        {"endpoint":"dev","method":"mcpb","grantLabel":"claude-desktop"}""");
    JsonNode connected = admin("POST", "/api/clients/claude-desktop/connect",
        "{\"previewId\":\"" + preview.path("previewId").asString() + "\"}");
    Path mcpb = Path.of(connected.path("mcpbPath").asString());
    Path mcpbCli = CLIENTS.resolve("node_modules/@anthropic-ai/mcpb/dist/cli/cli.js");
    Path unpacked = mcpb.resolveSibling("unpacked");
    Result unpack = run(List.of(node, mcpbCli.toString(), "unpack", mcpb.toString(), unpacked.toString()), Map.of(),
        60);
    assertThat(unpack.code()).as(unpack.out()).isZero();
    Result validate = run(List.of(node, mcpbCli.toString(), "validate", unpacked.resolve("manifest.json").toString()),
        Map.of(), 60);
    assertThat(validate.code()).as(validate.out()).isZero();
    assertThat(validate.out()).contains("validation passes");
    Result info = run(List.of(node, mcpbCli.toString(), "info", mcpb.toString()), Map.of(), 60);
    assertThat(info.code()).as(info.out()).isZero();
    assertThat(info.out()).contains("gaterook-dev");

    JsonNode manifest = JSON.readTree(read(unpacked.resolve("manifest.json")));
    List<String> args = new ArrayList<>();
    manifest.path("server").path("mcp_config").path("args").forEach(a -> args.add(a.asString()
        .replace("${__dirname}", unpacked.toString())));
    List<String> argv = new ArrayList<>(List.of(node));
    argv.addAll(args);
    ProcessBuilder pb = new ProcessBuilder(argv).redirectError(ProcessBuilder.Redirect.INHERIT);
    pb.environment().clear();
    pb.environment().put("PATH", System.getenv("PATH"));
    manifest.path("server").path("mcp_config").path("env").properties()
        .forEach(e -> pb.environment().put(e.getKey(), e.getValue().asString()));
    Process bridge = pb.start();
    processes.add(bridge);
    try (StdioPeer peer = new StdioPeer(bridge)) {
      JsonNode init = peer.call("initialize", JSON.readTree("""
          {"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"claude-desktop-e2e","version":"1"}}"""));
      assertThat(init.path("result").path("protocolVersion").asString()).as(init.toString()).isEqualTo("2025-11-25");
      peer.notify("notifications/initialized");
      JsonNode tools = peer.call("tools/list", JSON.createObjectNode());
      assertThat(tools.path("result").path("tools").findValuesAsString("name")).contains(CoreTools.LIST_CONNECTIONS);
      JsonNode call = peer.call("tools/call", JSON.readTree("{\"name\":\"list_connections\",\"arguments\":{}}"));
      assertThat(call.path("result").path("isError").asBoolean(false)).as(call.toString()).isFalse();
      // Its token expires too: the bridge takes a new one after the 401 and the next call just works.
      Thread.sleep(TTL.toMillis() + 1_500);
      JsonNode later = peer.call("tools/call", JSON.readTree("{\"name\":\"list_connections\",\"arguments\":{}}"));
      assertThat(later.path("result").path("isError").asBoolean(false)).as(later.toString()).isFalse();
    }
  }

  // ── Disconnect ──────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(3)
  void disconnectGivesCodexItsConfigBackAndTheHelperGetsNothing() throws Exception {
    JsonNode preview = admin("POST", "/api/clients/links/" + codexLink + "/disconnect/preview", null);
    admin("POST", "/api/clients/links/" + codexLink + "/disconnect", "{\"previewId\":\""
        + preview.path("previewId").asString() + "\"}");
    assertThat(read(userHome.resolve(".codex/config.toml"))).isEqualTo(codexBefore);
    Result helper = run(List.of("/bin/sh", "-c", "'" + home.resolve("bin/gw-bridge") + "' headers --endpoint dev "
        + "--grant " + codexGrant), Map.of("HOME", userHome.toString()), 30);
    assertThat(helper.code()).isNotZero();
    assertThat(helper.out()).doesNotContain("gwb_");
    // Codex throws a helper's stderr away: why it failed is in the bridge's own log, owner-only and without tokens.
    Path log = home.resolve("logs/bridge.log");
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(log))).isEqualTo("rw-------");
    assertThat(read(log)).contains("grant_revoked").doesNotContainPattern("gwb_[A-Za-z0-9_-]{43}");
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  /** {@code codex app-server}: JSON-RPC lines on stdin and stdout, in a home of its own. */
  private static final class AppServer implements AutoCloseable {

    private final Process process;
    private final OutputStream in;
    private final Map<Integer, BlockingQueue<JsonNode>> waiting = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();

    private AppServer(Process process) {
      this.process = process;
      this.in = process.getOutputStream();
      Thread.ofVirtual().start(this::read);
    }

    static AppServer start() throws IOException {
      ProcessBuilder pb = new ProcessBuilder(node, CLIENTS.resolve("node_modules/@openai/codex/bin/codex.js")
          .toString(), "app-server").redirectError(ProcessBuilder.Redirect.INHERIT);
      Map<String, String> env = pb.environment();
      env.clear();
      env.put("HOME", userHome.toString());
      env.put("CODEX_HOME", userHome.resolve(".codex").toString());
      env.put("PATH", Path.of(node).getParent() + ":/usr/bin:/bin");
      Process p = pb.start();
      processes.add(p);
      return new AppServer(p);
    }

    private void read() {
      try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(),
          StandardCharsets.UTF_8))) {
        String line;
        while ((line = r.readLine()) != null) {
          JsonNode m;
          try {
            m = JSON.readTree(line);
          } catch (RuntimeException e) {
            continue;
          }
          if (m.has("id") && (m.has("result") || m.has("error"))) {
            BlockingQueue<JsonNode> q = waiting.get(m.path("id").asInt());
            if (q != null) {
              q.add(m);
            }
          }
        }
      } catch (IOException ignored) {
        // The process ended.
      }
    }

    JsonNode call(String method, JsonNode params) throws Exception {
      int id = ids.incrementAndGet();
      BlockingQueue<JsonNode> q = new LinkedBlockingQueue<>();
      waiting.put(id, q);
      ObjectNode m = JSON.createObjectNode();
      m.put("id", id);
      m.put("method", method);
      m.set("params", params);
      send(m);
      JsonNode answer = q.poll(90, TimeUnit.SECONDS);
      assertThat(answer).as("Codex answers " + method).isNotNull();
      return answer;
    }

    void notify(String method) throws IOException {
      ObjectNode m = JSON.createObjectNode();
      m.put("method", method);
      m.putObject("params");
      send(m);
    }

    private synchronized void send(JsonNode m) throws IOException {
      in.write((JSON.writeValueAsString(m) + "\n").getBytes(StandardCharsets.UTF_8));
      in.flush();
    }

    @Override
    public void close() {
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      process.destroyForcibly();
    }
  }

  /** An MCP client on a stdio server's stdin and stdout: JSON-RPC 2.0, one message per line, answers by id. */
  private static final class StdioPeer implements AutoCloseable {

    private final Process process;
    private final Map<Integer, BlockingQueue<JsonNode>> waiting = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();

    StdioPeer(Process process) {
      this.process = process;
      Thread.ofVirtual().start(() -> {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(),
            StandardCharsets.UTF_8))) {
          String line;
          while ((line = r.readLine()) != null) {
            JsonNode m = JSON.readTree(line);
            BlockingQueue<JsonNode> q = waiting.get(m.path("id").asInt(-1));
            if (q != null) {
              q.add(m);
            }
          }
        } catch (IOException | RuntimeException ignored) {
          // The process ended.
        }
      });
    }

    JsonNode call(String method, JsonNode params) throws Exception {
      int id = ids.incrementAndGet();
      BlockingQueue<JsonNode> q = new LinkedBlockingQueue<>();
      waiting.put(id, q);
      ObjectNode m = JSON.createObjectNode();
      m.put("jsonrpc", "2.0");
      m.put("id", id);
      m.put("method", method);
      m.set("params", params);
      write(m);
      JsonNode answer = q.poll(60, TimeUnit.SECONDS);
      assertThat(answer).as("the bridge answers " + method).isNotNull();
      return answer;
    }

    void notify(String method) throws IOException {
      ObjectNode m = JSON.createObjectNode();
      m.put("jsonrpc", "2.0");
      m.put("method", method);
      write(m);
    }

    private synchronized void write(JsonNode m) throws IOException {
      OutputStream out = process.getOutputStream();
      out.write((JSON.writeValueAsString(m) + "\n").getBytes(StandardCharsets.UTF_8));
      out.flush();
    }

    @Override
    public void close() {
      process.destroyForcibly();
    }
  }

  private static JsonNode link(String client, String id) throws Exception {
    for (JsonNode c : admin("GET", "/api/clients", null).path("clients")) {
      if (c.path("client").asString().equals(client)) {
        for (JsonNode l : c.path("links")) {
          if (l.path("id").asString().equals(id)) {
            return l;
          }
        }
      }
    }
    throw new AssertionError("no link " + id);
  }

  /** A signed admin request, as Electron's main process sends it; must answer 2xx. */
  private static JsonNode admin(String method, String target, String json) throws Exception {
    byte[] nonce = new byte[16];
    RANDOM.nextBytes(nonce);
    String auth = LaunchSecret.fromBase64Url(LAUNCH_SECRET).sign(method, target, System.currentTimeMillis(),
        Base64.getUrlEncoder().withoutPadding().encodeToString(nonce));
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + target))
        .timeout(Duration.ofSeconds(30)).header(LaunchSecret.HEADER, auth);
    if (json == null) {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
    }
    HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).as(method + " " + target + ": " + r.body()).isBetween(200, 299);
    return r.body().isEmpty() ? JSON.createObjectNode() : JSON.readTree(r.body());
  }

  private record Result(int code, String out) {}

  private static Result run(List<String> argv, Map<String, String> env, int seconds) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
    pb.environment().putAll(env);
    Process p = pb.start();
    p.getOutputStream().close();
    StringBuilder out = new StringBuilder();
    Thread reader = Thread.ofVirtual().start(() -> {
      try {
        out.append(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
      } catch (IOException ignored) {
        // Gone.
      }
    });
    if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new AssertionError(argv + " didn't finish in " + seconds + " s");
    }
    reader.join(5_000);
    return new Result(p.exitValue(), out.toString());
  }

  private static void install() throws Exception {
    Files.createDirectories(CLIENTS);
    Path lock = SOURCES.resolve("package-lock.json");
    Path stamp = CLIENTS.resolve(".installed-from");
    String want = Long.toHexString(java.util.Arrays.hashCode(Files.readAllBytes(lock)));
    if (Files.exists(CLIENTS.resolve("node_modules/.package-lock.json")) && Files.exists(stamp)
        && Files.readString(stamp).equals(want)) {
      return;
    }
    Files.copy(SOURCES.resolve("package.json"), CLIENTS.resolve("package.json"), StandardCopyOption.REPLACE_EXISTING);
    Files.copy(lock, CLIENTS.resolve("package-lock.json"), StandardCopyOption.REPLACE_EXISTING);
    Path npm = Path.of(node).resolveSibling("npm");
    ProcessBuilder pb = new ProcessBuilder(Files.exists(npm) ? npm.toString() : "npm", "ci", "--ignore-scripts",
        "--no-audit", "--no-fund").directory(CLIENTS.toFile()).redirectErrorStream(true);
    pb.environment().put("PATH", Path.of(node).getParent() + ":" + System.getenv("PATH"));
    Process p = pb.start();
    String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(p.waitFor(900, TimeUnit.SECONDS) && p.exitValue() == 0).as("npm ci:%n%s", log).isTrue();
    Files.writeString(stamp, want);
  }

  private static String nodeBinary() {
    String env = System.getenv("MCP_NODE");
    if (env != null && !env.isBlank()) {
      return env;
    }
    Path bundled = Path.of("/opt/node24/node-v24.22.1-linux-x64/bin/node");
    return Files.isExecutable(bundled) ? bundled.toString() : "node";
  }

  private static String read(Path p) {
    try {
      return Files.readString(p);
    } catch (IOException e) {
      return "";
    }
  }

  private static void await(java.util.function.BooleanSupplier condition, String what) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("Timed out waiting for " + what);
      }
      Thread.sleep(100);
    }
  }
}
