package ai.msrv.gaterook.e2e.tunnel;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import ai.msrv.gaterook.core.platform.LaunchSecret;
import ai.msrv.gaterook.e2e.platform.CoreProcess;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;

/**
 * The tunnel and claude.ai's sign-in on the core as Electron runs it (ARCHITECTURE.md §10 K3): {@code app.jar}, or the
 * native executable of {@code -Dgateway.core.binary}, with the bootstrap's public port and a fake {@code cloudflared}
 * pinned by path. Publish brings the agent up as the core's child with Electron's bare environment; claude.ai signs in
 * through the agent's edge with its metadata document over HTTPS and calls the endpoint; a core killed with the tunnel
 * on leaves an orphan that the next start kills; a core that stops takes its agent with it.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TunnelProcessIT {

  private static final String HOST = "gw-e2e-5m3q.trycloudflare.com";
  private static final String REDIRECT = "https://claude.ai/api/mcp/auth_callback";
  private static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
      .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final HeldCertificate CA = new HeldCertificate.Builder().certificateAuthority(0)
      .commonName("claude.ai stand-in CA").build();
  private static final HeldCertificate LEAF = new HeldCertificate.Builder().commonName("localhost")
      .addSubjectAlternativeName("localhost").signedBy(CA).build();

  private static Path home;
  private static Path report;
  private static List<String> flags;
  private static int port;
  private static CoreProcess core;
  private static HttpsServer claude;
  private static String clientId;

  @BeforeAll
  static void start() throws Exception {
    home = CoreProcess.newHome("tunnel-e2e");
    Path bin = Files.createDirectories(home.resolveSibling(home.getFileName() + "-agent"));
    report = bin.resolve("cloudflared.report");
    Path agent = bin.resolve("cloudflared");
    String classes = Path.of(FakeCloudflaredAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI())
        .toString();
    Files.writeString(agent, "#!/bin/sh\nexec '" + Path.of(System.getProperty("java.home"), "bin", "java")
        + "' -XX:TieredStopAtLevel=1 -cp '" + classes + "' " + FakeCloudflaredAgent.class.getName() + " '" + HOST
        + "' '" + report + "' \"$@\"\n");
    Files.setPosixFilePermissions(agent, PosixFilePermissions.fromString("rwx------"));
    String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(agent)));
    String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
    String platform = (os.startsWith("mac") ? "darwin" : os.startsWith("windows") ? "windows" : "linux") + "-"
        + (arch.equals("aarch64") || arch.equals("arm64") ? "arm64" : "x64");
    Path pins = bin.resolve("pins.json");
    Files.writeString(pins, "{\"cloudflared\":{\"version\":\"2026.10.0\",\"platforms\":{\"" + platform
        + "\":{\"path\":\"" + agent + "\",\"binary\":\"cloudflared\",\"binarySha256\":\"" + sha + "\"}}}}");

    claude = HttpsServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
    claude.setHttpsConfigurator(new HttpsConfigurator(new HandshakeCertificates.Builder().heldCertificate(LEAF)
        .build().sslContext()));
    clientId = "https://localhost:" + claude.getAddress().getPort() + "/oauth/mcp-oauth-client-metadata";
    byte[] document = ("{\"client_id\":\"" + clientId + "\",\"client_name\":\"Claude\",\"redirect_uris\":[\""
        + REDIRECT + "\"],\"token_endpoint_auth_method\":\"none\"}").getBytes(StandardCharsets.UTF_8);
    claude.createContext("/oauth/mcp-oauth-client-metadata", ex -> {
      try (ex) {
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, document.length);
        ex.getResponseBody().write(document);
      }
    });
    claude.start();
    Path ca = bin.resolve("ca.pem");
    Files.writeString(ca, CA.certificatePem());

    flags = List.of("-Dgateway.test.tunnel-pins=" + pins, "-Dgateway.test.oauth.client-hosts=localhost",
        "-Dgateway.test.oauth.cimd-ca=" + ca, "-Dgateway.connections.health-interval=0s");
    port = CoreProcess.freePort();
    core = CoreProcess.start(home, port, flags).bootstrap();
    core.untilReady(Duration.ofSeconds(90));
    admin(201, "POST", "/api/endpoints", "{\"slug\":\"dev\",\"startFrom\":\"empty\"}");
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
    if (claude != null) {
      claude.stop(0);
    }
  }

  @Test
  @Order(1)
  void publishBringsTheAgentUpAsTheCoresChildWithItsBareEnvironment() throws Exception {
    JsonNode off = admin(200, "GET", "/api/tunnel", null);
    assertThat(off.path("state").asString()).isEqualTo("off");
    assertThat(off.path("cloudflared").path("sha256Ok").asBoolean()).isTrue();
    assertThat(off.path("publicPort").asInt()).isEqualTo(core.publicPort);

    Files.deleteIfExists(report);
    JsonNode starting = admin(202, "POST", "/api/tunnel/publish", "{\"endpoint\":\"dev\"}");
    assertThat(starting.path("state").asString()).isEqualTo("starting");
    await(() -> state().path("state").asString().equals("on"), "the tunnel");
    JsonNode on = state();
    assertThat(on.path("host").asString()).isEqualTo(HOST);
    assertThat(on.path("publicUrl").asString()).isEqualTo("https://" + HOST + "/mcp/dev");

    Properties agent = agent();
    assertThat(agent.getProperty("argv")).contains("--url\u0001http://127.0.0.1:" + core.publicPort)
        .contains("--protocol\u0001auto").contains(home.resolve("run/cloudflared.yml").toString());
    assertThat(agent.getProperty("tunnelVars")).isEqualTo("false");
    long pid = Long.parseLong(agent.getProperty("pid"));
    assertThat(ProcessHandle.of(pid).flatMap(ProcessHandle::parent).map(ProcessHandle::pid))
        .as("the core's child").contains(core.process.pid());
    assertThat(Files.readString(home.resolve("run/tunnel.pid"))).startsWith(pid + "\n");

    // The self-test runs from this computer: the fake hostname has no DNS here, which it says in words.
    await(() -> !state().path("check").isNull(), "the self-test");
    assertThat(state().path("check").path("ok").asBoolean()).isFalse();
    assertThat(state().path("check").path("text").asString()).contains("doesn't resolve");
    assertThat(admin(200, "GET", "/api/health", null).path("tunnel").path("state").asString()).isEqualTo("on");
  }

  @Test
  @Order(2)
  void claudeSignsInThroughTheEdgeAndCallsTheEndpoint() throws Exception {
    HttpResponse<String> first = edge("160.79.104.10", "POST", "/mcp/dev", Map.of("Content-Type",
        "application/json", "Accept", "application/json, text/event-stream"), initialize(), null);
    assertThat(first.statusCode()).isEqualTo(401);
    assertThat(first.headers().firstValue("WWW-Authenticate").orElse("")).isEqualTo("Bearer resource_metadata=\""
        + "https://" + HOST + "/.well-known/oauth-protected-resource/mcp/dev\", scope=\"mcp\"");
    assertThat(edge("203.0.113.24", "POST", "/mcp/dev", Map.of("Content-Type", "application/json"), "{}", null)
        .statusCode()).as("outside Anthropic's range").isEqualTo(403);
    JsonNode prm = CoreProcess.JSON.readTree(edge("160.79.104.10", "GET",
        "/.well-known/oauth-protected-resource/mcp/dev", Map.of(), null, null).body());
    assertThat(prm.path("resource").asString()).isEqualTo("https://" + HOST + "/mcp/dev");
    JsonNode as = CoreProcess.JSON.readTree(edge("160.79.104.10", "GET", "/.well-known/oauth-authorization-server",
        Map.of(), null, null).body());
    assertThat(as.path("client_id_metadata_document_supported").asBoolean()).isTrue();

    byte[] v = new byte[32];
    RANDOM.nextBytes(v);
    String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(v);
    String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
        .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    String resource = "https://" + HOST + "/mcp/dev";
    Map<String, String> q = new LinkedHashMap<>();
    q.put("response_type", "code");
    q.put("client_id", clientId);
    q.put("redirect_uri", REDIRECT);
    q.put("code_challenge", challenge);
    q.put("code_challenge_method", "S256");
    q.put("state", "e2e");
    q.put("scope", "mcp offline_access");
    q.put("resource", resource);
    HttpResponse<String> page = edge("203.0.113.24", "GET", "/oauth/authorize?" + form(q), Map.of(), null, null);
    assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
    String rid = page.body().replaceAll("(?s).*data-rid=\"(ar_[A-Za-z0-9]+)\".*", "$1");
    String code = page.body().replaceAll("(?s).*aria-label=\"Code\">([2-9A-Z]{3}-[2-9A-Z]{3})<.*", "$1");
    String cookie = page.headers().firstValue("Set-Cookie").orElseThrow();
    cookie = cookie.substring(0, cookie.indexOf(';'));

    JsonNode consents = admin(200, "GET", "/api/oauth/consents", null);
    assertThat(consents.get(0).path("code").asString()).isEqualTo(code);
    assertThat(consents.get(0).path("ip").asString()).isEqualTo("203.0.113.24");
    admin(204, "POST", "/api/oauth/consents/" + rid, "{\"decision\":\"allow\"}");
    HttpResponse<String> status = edge("203.0.113.24", "GET", "/oauth/authorize/status?rid=" + rid, Map.of(),
        null, cookie);
    String location = CoreProcess.JSON.readTree(status.body()).path("location").asString();
    Map<String, String> back = query(location);
    assertThat(back).containsEntry("state", "e2e").containsEntry("iss", "https://" + HOST);

    Map<String, String> token = new LinkedHashMap<>();
    token.put("grant_type", "authorization_code");
    token.put("code", back.get("code"));
    token.put("client_id", clientId);
    token.put("redirect_uri", REDIRECT);
    token.put("code_verifier", verifier);
    token.put("resource", resource);
    HttpResponse<String> t = edge("160.79.104.10", "POST", "/oauth/token", Map.of("Content-Type",
        "application/x-www-form-urlencoded"), form(token), null);
    assertThat(t.statusCode()).as(t.body()).isEqualTo(200);
    String access = CoreProcess.JSON.readTree(t.body()).path("access_token").asString();
    HttpResponse<String> list = edge("160.79.104.10", "POST", "/mcp/dev", Map.of("Content-Type",
        "application/json", "Accept", "application/json, text/event-stream", "MCP-Protocol-Version", "2025-11-25",
        "Authorization", "Bearer " + access), "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", null);
    assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
    assertThat(list.body()).contains("approval_status");
    assertThat(admin(200, "GET", "/api/oauth/grants", null).get(0).path("state").asString()).isEqualTo("active");
  }

  @Test
  @Order(3)
  void anOrphanOfAKilledCoreIsKilledByTheNextStart() throws Exception {
    long agent = Long.parseLong(agent().getProperty("pid"));
    core.process.destroyForcibly();
    assertThat(core.exited(Duration.ofSeconds(10))).isTrue();
    assertThat(ProcessHandle.of(agent).map(ProcessHandle::isAlive)).as("left behind").contains(true);

    core = CoreProcess.start(home, port, flags).bootstrap();
    core.untilReady(Duration.ofSeconds(90));
    await(() -> ProcessHandle.of(agent).map(h -> !h.isAlive()).orElse(true), "the orphan to be killed");
    await(() -> !state().path("history").get(0).path("to").isNull(), "the session to be closed");
    JsonNode after = state();
    assertThat(after.path("state").asString()).as("off after every start").isEqualTo("off");
    assertThat(after.path("endpoint").asString()).isEqualTo("dev");
    assertThat(after.path("history").get(0).path("to").isNull()).as("the session was closed").isFalse();
    assertThat(core.coreLog()).contains("left from the previous run");
  }

  @Test
  @Order(4)
  void aCoreThatStopsTakesItsAgentWithIt() throws Exception {
    Files.deleteIfExists(report);
    admin(202, "POST", "/api/tunnel/publish", "{\"endpoint\":\"dev\"}");
    await(() -> state().path("state").asString().equals("on"), "the tunnel");
    long agent = Long.parseLong(agent().getProperty("pid"));
    core.closeStdin();
    assertThat(core.exited(Duration.ofSeconds(30))).as(core.stderr()).isTrue();
    await(() -> ProcessHandle.of(agent).map(h -> !h.isAlive()).orElse(true), "the agent to stop with the core");
    assertThat(home.resolve("run/tunnel.pid")).doesNotExist();
    String log = core.coreLog() + core.stderr();
    assertThat(log).doesNotContain("MissingReflectionRegistration").doesNotContain("MissingResourceRegistration")
        .doesNotContain("UnsupportedFeatureError");
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static Properties agent() throws IOException {
    await(() -> Files.isRegularFile(report), "the agent's report");
    Properties p = new Properties();
    try (InputStream in = Files.newInputStream(report)) {
      p.load(in);
    }
    return p;
  }

  private static JsonNode state() {
    try {
      return admin(200, "GET", "/api/tunnel", null);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String initialize() {
    return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\","
        + "\"capabilities\":{},\"clientInfo\":{\"name\":\"claude-ai\",\"version\":\"1\"}}}";
  }

  private static HttpResponse<String> edge(String caller, String method, String target, Map<String, String> headers,
      String body, String cookie) throws Exception {
    int edgePort = Integer.parseInt(agent().getProperty("edgePort"));
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + edgePort + target))
        .timeout(Duration.ofSeconds(30)).header("X-Fake-Client-IP", caller)
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    headers.forEach(b::header);
    if (cookie != null) {
      b.header("Cookie", cookie);
    }
    return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
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

  private static String form(Map<String, String> values) {
    return values.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
  }

  private static Map<String, String> query(String location) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String pair : URI.create(location).getRawQuery().split("&")) {
      int eq = pair.indexOf('=');
      out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
          URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
    }
    return out;
  }

  private static void await(BooleanSupplier condition, String what) {
    long deadline = System.nanoTime() + 60_000_000_000L;
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("Timed out waiting for " + what);
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
  }
}
