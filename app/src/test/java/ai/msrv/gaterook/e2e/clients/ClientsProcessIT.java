package ai.msrv.gaterook.e2e.clients;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.platform.LaunchSecret;
import ai.msrv.gaterook.e2e.platform.CoreProcess;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.toml.TomlMapper;

/**
 * The clients module in the core's own process, started as Electron starts it (profile {@code mcp-clients}): the jar,
 * or the native executable of {@code -Dgateway.core.binary}, with a bundle folder ({@code -Dgateway.resources}) whose
 * {@code bridge/gw-bridge} is the native bridge of {@code -Dgateway.bridge.binary} or, without it, the bundle's JRE
 * running {@code gw-bridge.jar}; the broker is {@code bridge/dev-broker.mjs}. Every way a client reaches an endpoint
 * runs end to end: Codex's helper through the shim, Cursor's token in its file, Antigravity's stdio entry, Claude
 * Desktop's extension with the Node bridge the core packs, the self-test of Check, and Disconnect. In the native image
 * this is the clients module's touch: TOML and JSON editing, the extension's resource, the processes it starts.
 */
@Tag("mcp-clients")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClientsProcessIT {

  private static final Duration READY = Duration.ofSeconds(90);
  private static final Pattern NAMED = Pattern.compile("gwt_[A-Za-z0-9_-]{43}");
  private static final Pattern BRIDGE = Pattern.compile("gwb_[A-Za-z0-9_-]{43}");
  private static final String INSTANCE_NONCE = "it-instance-0123456789";
  private static final Path BROKER = Path.of("../bridge/dev-broker.mjs").toAbsolutePath().normalize();
  private static final Path BRIDGE_JAR = Path.of("../bridge-cli/target/gw-bridge.jar").toAbsolutePath().normalize();
  private static final String CODEX_BEFORE = "model = \"gpt-5.5-codex\"\n\n[mcp_servers.github]\n"
      + "url = \"https://api.githubcopilot.com/mcp/\"\nbearer_token_env_var = \"GITHUB_PAT\"\n";
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private static String node;
  private static Path root;
  private static Path home;
  private static Path userHome;
  private static CoreProcess core;
  private static Process broker;
  private static final List<Process> processes = new ArrayList<>();
  private static String codexLink;
  private static String codexHelper;

  @BeforeAll
  static void start() throws Exception {
    node = nodeBinary();
    root = TestRoots.create("client-process");
    home = Files.createDirectories(root.resolve("gateway"));
    userHome = Files.createDirectories(root.resolve("user"));
    Files.writeString(Files.createDirectories(userHome.resolve(".codex")).resolve("config.toml"), CODEX_BEFORE);
    Files.createDirectories(userHome.resolve(".cursor"));
    Files.createDirectories(userHome.resolve(".gemini/config"));
    Files.createDirectories(userHome.resolve(".config/Claude"));

    core = CoreProcess.start(home, CoreProcess.freePort(), List.of("-Dgateway.clients.user-home=" + userHome,
        "-Dgateway.clients.os=linux", "-Dgateway.resources=" + bundle(root.resolve("resources")),
        "-Dgateway.connections.health-interval=0s")).bootstrap();
    List<JsonNode> lines = core.untilReady(READY);
    assertThat(lines.getLast().path("ready").asBoolean()).as(core.stderr()).isTrue();

    // The broker as Electron's main process runs it: signs with the launch secret, checks the instance nonce.
    ProcessBuilder pb = new ProcessBuilder(node, BROKER.toString(), "--home", home.toString(), "--origin",
        "http://127.0.0.1:" + core.port, "--instance", INSTANCE_NONCE).redirectError(ProcessBuilder.Redirect.INHERIT);
    pb.environment().put("GW_DEV_INTERNAL_SECRET", CoreProcess.SECRET);
    broker = pb.start();
    processes.add(broker);
    String ready = new BufferedReader(new InputStreamReader(broker.getInputStream(), StandardCharsets.UTF_8))
        .readLine();
    assertThat(CoreProcess.JSON.readTree(ready).path("ready").asBoolean()).as(ready).isTrue();

    Path shim = home.resolve("bin/gw-bridge");
    await(() -> read(shim).contains(root.resolve("resources").toString()), "the shim on the bundle's bridge");
    admin(201, "POST", "/api/endpoints", "{\"slug\":\"dev\",\"startFrom\":\"empty\"}");
  }

  /**
   * The bundle folder of a desktop install: {@code bridge/gw-bridge} when a native bridge is given, else
   * {@code jre/bin/java} (this JDK) and {@code bridge/gw-bridge.jar} — the shim's two choices.
   */
  private static Path bundle(Path resources) throws IOException {
    Path bridge = Files.createDirectories(resources.resolve("bridge"));
    String binary = System.getProperty("gateway.bridge.binary");
    if (binary != null && !binary.isBlank()) {
      Path target = bridge.resolve("gw-bridge");
      Files.copy(Path.of(binary), target, StandardCopyOption.REPLACE_EXISTING);
      Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"));
    } else {
      assertThat(BRIDGE_JAR).as("bridge-cli is built before app (-pl app -am)").exists();
      Files.copy(BRIDGE_JAR, bridge.resolve("gw-bridge.jar"), StandardCopyOption.REPLACE_EXISTING);
      Path bin = Files.createDirectories(resources.resolve("jre/bin"));
      Files.createSymbolicLink(bin.resolve("java"), Path.of(System.getProperty("java.home"), "bin", "java"));
    }
    return resources;
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
  void codexsHelperGetsATokenThroughTheShimTheBridgeAndTheBroker() throws Exception {
    JsonNode scan = admin(200, "POST", "/api/clients/scan", null);
    assertThat(scan.path("clients").findValuesAsString("status")).containsExactly("detected", "detected", "detected",
        "detected");
    JsonNode preview = admin(200, "POST", "/api/clients/codex/connect/preview",
        "{\"endpoint\":\"dev\",\"method\":\"helper\",\"grantLabel\":\"codex-laptop\"}");
    assertThat(preview.path("files").get(0).path("diff").path("added").asInt()).isPositive();
    JsonNode connected = admin(200, "POST", "/api/clients/codex/connect",
        "{\"previewId\":\"" + preview.path("previewId").asString() + "\"}");
    codexLink = connected.path("link").path("id").asString();
    String config = read(userHome.resolve(".codex/config.toml"));
    assertThat(config).startsWith(CODEX_BEFORE).doesNotContain("gwb_");
    JsonNode entry = new TomlMapper().readTree(config).path("mcp_servers").path("gaterook-dev");
    assertThat(entry.path("url").asString()).isEqualTo("http://127.0.0.1:" + core.port + "/mcp/dev");
    assertThat(entry.path("default_tools_approval_mode").asString()).isEqualTo("writes");
    codexHelper = entry.path("http_headers_helper").asString();

    // Exactly as Codex runs it: sh -c with a cleared environment.
    Result helper = run(List.of("/bin/sh", "-c", codexHelper), Map.of("HOME", userHome.toString()), "");
    assertThat(helper.code()).as(helper.err()).isZero();
    JsonNode headers = CoreProcess.JSON.readTree(helper.out());
    String authorization = headers.path("Authorization").asString();
    assertThat(authorization).startsWith("Bearer gwb_");
    HttpResponse<String> init = mcp(authorization, initialize("codex"));
    assertThat(init.statusCode()).as(init.body()).isEqualTo(200);
    assertThat(init.headers().firstValue("X-Gateway-Bridge-Instance")).isPresent();

    // Check runs the same command and finds the whole chain working.
    JsonNode check = admin(200, "POST", "/api/clients/links/" + codexLink + "/check", null);
    assertThat(step(check, "broker").path("state").asString()).as(check.toString()).isEqualTo("ok");
    assertThat(step(check, "bridge").path("state").asString()).as(check.toString()).isEqualTo("ok");
  }

  // ── Cursor and Antigravity ──────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(2)
  void cursorGetsANamedTokenInAnOwnerOnlyFile() throws Exception {
    JsonNode preview = admin(200, "POST", "/api/clients/cursor/connect/preview",
        "{\"endpoint\":\"dev\",\"method\":\"token\",\"token\":{\"name\":\"cursor-laptop\",\"ttlDays\":7}}");
    assertThat(preview.toString()).doesNotContainPattern(NAMED.pattern());
    admin(200, "POST", "/api/clients/cursor/connect", "{\"previewId\":\"" + preview.path("previewId").asString()
        + "\"}");
    Path file = userHome.resolve(".cursor/mcp.json");
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
    JsonNode entry = CoreProcess.JSON.readTree(read(file)).path("mcpServers").path("gaterook-dev");
    String authorization = entry.path("headers").path("Authorization").asString();
    assertThat(authorization).matches("Bearer " + NAMED.pattern());
    assertThat(mcp(authorization, initialize("cursor")).statusCode()).isEqualTo(200);
  }

  @Test
  @Order(3)
  void antigravitysStdioEntryRunsTheBridge() throws Exception {
    JsonNode preview = admin(200, "POST", "/api/clients/antigravity/connect/preview",
        "{\"endpoint\":\"dev\",\"method\":\"config-bridge\"}");
    admin(200, "POST", "/api/clients/antigravity/connect", "{\"previewId\":\"" + preview.path("previewId").asString()
        + "\"}");
    JsonNode entry = CoreProcess.JSON.readTree(read(userHome.resolve(".gemini/config/mcp_config.json")))
        .path("mcpServers").path("gaterook-dev");
    List<String> argv = new ArrayList<>(List.of(entry.path("command").asString()));
    entry.path("args").forEach(a -> argv.add(a.asString()));
    assertThat(argv.getFirst()).isEqualTo(home.resolve("bin/gw-bridge").toString());
    try (StdioPeer peer = StdioPeer.start(argv, Map.of("HOME", userHome.toString()))) {
      JsonNode init = peer.call("initialize", CoreProcess.JSON.readTree("""
          {"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"antigravity","version":"1"}}"""));
      assertThat(init.at("/result/protocolVersion").asString()).as(init.toString()).isEqualTo("2025-11-25");
      peer.notify("notifications/initialized");
      JsonNode tools = peer.call("tools/list", CoreProcess.JSON.createObjectNode());
      assertThat(tools.path("result").has("tools")).as(tools.toString()).isTrue();
    }
  }

  // ── Claude Desktop ──────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(4)
  void claudeDesktopsExtensionCarriesTheNodeBridgeAndItWorks() throws Exception {
    JsonNode preview = admin(200, "POST", "/api/clients/claude-desktop/connect/preview",
        "{\"endpoint\":\"dev\",\"method\":\"mcpb\",\"grantLabel\":\"claude-desktop\"}");
    JsonNode connected = admin(200, "POST", "/api/clients/claude-desktop/connect",
        "{\"previewId\":\"" + preview.path("previewId").asString() + "\"}");
    Path mcpb = Path.of(connected.path("mcpbPath").asString());
    assertThat(mcpb).startsWith(home.resolve("clients/claude-desktop"));
    Path unpacked = Files.createDirectories(mcpb.resolveSibling("unpacked-" + mcpb.getFileName()));
    try (ZipFile zip = new ZipFile(mcpb.toFile())) {
      assertThat(zip.stream().map(ZipEntry::getName).toList()).contains("manifest.json", "server/gw-bridge.mjs");
      for (String name : List.of("manifest.json", "server/gw-bridge.mjs")) {
        Path out = Files.createDirectories(unpacked.resolve(name).getParent()).resolve(Path.of(name).getFileName());
        try (InputStream in = zip.getInputStream(zip.getEntry(name))) {
          Files.copy(in, out);
        }
      }
    }
    assertThat(read(unpacked.resolve("server/gw-bridge.mjs"))).as("the core's copy of bridge/gw-bridge.mjs")
        .isEqualTo(read(Path.of("../bridge/gw-bridge.mjs")));
    JsonNode manifest = CoreProcess.JSON.readTree(read(unpacked.resolve("manifest.json")));
    List<String> argv = new ArrayList<>(List.of(node));
    manifest.at("/server/mcp_config/args").forEach(a -> argv.add(a.asString().replace("${__dirname}",
        unpacked.toString())));
    Map<String, String> env = new java.util.HashMap<>();
    manifest.at("/server/mcp_config/env").properties().forEach(e -> env.put(e.getKey(), e.getValue().asString()));
    try (StdioPeer peer = StdioPeer.start(argv, env)) {
      JsonNode init = peer.call("initialize", CoreProcess.JSON.readTree("""
          {"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"claude-desktop","version":"1"}}"""));
      assertThat(init.at("/result/protocolVersion").asString()).as(init.toString()).isEqualTo("2025-11-25");
    }
  }

  // ── Disconnect, and what the core logged ────────────────────────────────────────────────────────────────────

  @Test
  @Order(5)
  void disconnectGivesCodexItsFileBackAndTheHelperGetsNothingMore() throws Exception {
    JsonNode preview = admin(200, "POST", "/api/clients/links/" + codexLink + "/disconnect/preview", null);
    admin(200, "POST", "/api/clients/links/" + codexLink + "/disconnect", "{\"previewId\":\""
        + preview.path("previewId").asString() + "\"}");
    assertThat(read(userHome.resolve(".codex/config.toml"))).isEqualTo(CODEX_BEFORE);
    Result helper = run(List.of("/bin/sh", "-c", codexHelper), Map.of("HOME", userHome.toString()), "");
    assertThat(helper.code()).as("grant revoked").isEqualTo(2);
    assertThat(helper.out()).isEmpty();
  }

  @Test
  @Order(6)
  void theLogsHoldNoMissingRegistrationAndNoToken() {
    String log = core.coreLog() + core.stderr() + read(home.resolve("logs/bridge.log"));
    assertThat(log).isNotBlank().doesNotContain("MissingReflectionRegistration")
        .doesNotContain("MissingResourceRegistration").doesNotContain("MissingJNIRegistration")
        .doesNotContain("UnsupportedFeatureError").doesNotContainPattern(NAMED.pattern())
        .doesNotContainPattern(BRIDGE.pattern());
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static String initialize(String client) {
    return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\","
        + "\"capabilities\":{},\"clientInfo\":{\"name\":\"" + client + "\",\"version\":\"1\"}}}";
  }

  private static HttpResponse<String> mcp(String authorization, String message) throws Exception {
    return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + core.port + "/mcp/dev"))
        .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream").header("Authorization", authorization)
        .POST(HttpRequest.BodyPublishers.ofString(message)).build(), HttpResponse.BodyHandlers.ofString());
  }

  /** A request of the window, signed as Electron's main process signs it. */
  private static JsonNode admin(int status, String method, String target, String json) throws Exception {
    byte[] nonce = new byte[16];
    RANDOM.nextBytes(nonce);
    String auth = core.secret().sign(method, target, System.currentTimeMillis(),
        Base64.getUrlEncoder().withoutPadding().encodeToString(nonce));
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + core.port + target))
        .timeout(Duration.ofSeconds(30)).header(LaunchSecret.HEADER, auth);
    if (json == null) {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
    }
    HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).as(method + " " + target + ": " + r.body()).isEqualTo(status);
    return r.body().isEmpty() ? CoreProcess.JSON.createObjectNode() : CoreProcess.JSON.readTree(r.body());
  }

  private static JsonNode step(JsonNode check, String id) {
    for (JsonNode s : check.path("steps")) {
      if (s.path("id").asString().equals(id)) {
        return s;
      }
    }
    throw new AssertionError("no step " + id + " in " + check);
  }

  private record Result(int code, String out, String err) {}

  /** {@code argv} with only {@code env} and {@code PATH}, the way the clients start their helpers and servers. */
  private static Result run(List<String> argv, Map<String, String> env, String stdin) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(argv);
    pb.environment().clear();
    pb.environment().put("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
    pb.environment().putAll(env);
    Process p = pb.start();
    CompletableFuture<String> out = drain(p.getInputStream());
    CompletableFuture<String> err = drain(p.getErrorStream());
    try (OutputStream in = p.getOutputStream()) {
      in.write(stdin.getBytes(StandardCharsets.UTF_8));
    }
    if (!p.waitFor(30, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new AssertionError(argv + " didn't finish in 30 s");
    }
    return new Result(p.exitValue(), out.join(), err.join());
  }

  private static CompletableFuture<String> drain(InputStream stream) {
    CompletableFuture<String> text = new CompletableFuture<>();
    Thread.ofVirtual().start(() -> {
      try (stream) {
        text.complete(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
      } catch (IOException e) {
        text.complete("");
      }
    });
    return text;
  }

  /** An MCP client on a stdio server: JSON-RPC 2.0, one message per line, answers by id. */
  private static final class StdioPeer implements AutoCloseable {

    private final Process process;
    private final Map<Integer, BlockingQueue<JsonNode>> waiting = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();

    private StdioPeer(Process process) {
      this.process = process;
      Thread.ofVirtual().start(() -> {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(),
            StandardCharsets.UTF_8))) {
          String line;
          while ((line = r.readLine()) != null) {
            JsonNode m = CoreProcess.JSON.readTree(line);
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

    static StdioPeer start(List<String> argv, Map<String, String> env) throws IOException {
      ProcessBuilder pb = new ProcessBuilder(argv).redirectError(ProcessBuilder.Redirect.INHERIT);
      pb.environment().clear();
      pb.environment().put("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
      pb.environment().putAll(env);
      Process p = pb.start();
      processes.add(p);
      return new StdioPeer(p);
    }

    JsonNode call(String method, JsonNode params) throws Exception {
      int id = ids.incrementAndGet();
      BlockingQueue<JsonNode> q = new LinkedBlockingQueue<>();
      waiting.put(id, q);
      ObjectNode m = CoreProcess.JSON.createObjectNode();
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
      ObjectNode m = CoreProcess.JSON.createObjectNode();
      m.put("jsonrpc", "2.0");
      m.put("method", method);
      write(m);
    }

    private synchronized void write(JsonNode m) throws IOException {
      OutputStream out = process.getOutputStream();
      out.write((CoreProcess.JSON.writeValueAsString(m) + "\n").getBytes(StandardCharsets.UTF_8));
      out.flush();
    }

    @Override
    public void close() {
      process.destroyForcibly();
    }
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
