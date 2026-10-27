package ai.msrv.gaterook.core.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.tunnel.TunnelWorld;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Reference OAuth clients against the core (ARCHITECTURE.md §10 K3, report 02 §8), profile {@code mcp-clients}:
 * the TypeScript SDK {@code @modelcontextprotocol/sdk} 1.32.1 signs in through its own discovery, dynamic
 * registration and PKCE and lists the tools; conformance 0.2.0-alpha.12 runs its {@code authorization} scenarios
 * ({@code authorization-server-metadata-endpoint}, {@code authorization-code-grant}). Both talk to the local
 * authorization server: a test client can't resolve the tunnel's hostname, and both servers are one implementation
 * apart from the ingress. The test plays the person's browser and their Allow in the app.
 *
 * <p>Packages: {@code npm ci} from {@code src/test/resources/ai/msrv/gaterook/core/oauth/node/package-lock.json} into
 * {@code ~/.cache/mcp-gateway-test/k3-oauth} (override {@code MCP_K3_OAUTH}); Node is {@code MCP_NODE}, else
 * {@code /opt/node24/…}, else {@code node} on the PATH.
 */
@Tag("mcp-clients")
class OAuthNodeClientsIT {

  static final Path SOURCES = Path.of("src/test/resources/ai/msrv/gaterook/core/oauth/node").toAbsolutePath();
  static final Path CLIENTS = Path.of(System.getenv().getOrDefault("MCP_K3_OAUTH",
      System.getProperty("user.home") + "/.cache/mcp-gateway-test/k3-oauth"));
  private static final Pattern AUTHORIZE = Pattern.compile("(http://127\\.0\\.0\\.1:\\d+/oauth/authorize\\?\\S+)");

  private static TunnelWorld world;
  private static OAuthFlow browser;
  private static String node;
  private static String resource;

  @BeforeAll
  static void start() throws Exception {
    node = nodeBinary();
    install();
    world = TunnelWorld.start("oauth-node");
    world.endpoint("dev", Env.DEV);
    EndpointService endpoints = world.bean(EndpointService.class);
    endpoints.updateSettings("dev", new EndpointService.SettingsUpdate(null, null, null, null, null, null, true),
        endpoints.get("dev").version(), null);
    browser = new OAuthFlow(world, false);
    resource = "http://127.0.0.1:" + world.localPort() + "/mcp/dev";
  }

  @AfterAll
  static void stop() {
    if (world != null) {
      world.close();
    }
  }

  @Test
  void theTypeScriptSdkSignsInWithDiscoveryRegistrationAndPkce() throws Exception {
    int port = TunnelWorld.freePort();
    List<JsonNode> events = new CopyOnWriteArrayList<>();
    Process p = start(List.of(node, script("sdk-signin.mjs").toString()), Map.of("MCP_URL", resource,
        "MCP_REDIRECT", "http://127.0.0.1:" + port + "/callback"));
    try (OutputStream stdin = p.getOutputStream()) {
      read(p.getInputStream(), line -> {
        JsonNode e;
        try {
          e = TunnelWorld.JSON.readTree(line);
        } catch (RuntimeException notJson) {
          return;
        }
        events.add(e);
        if ("authorize".equals(e.path("event").asString())) {
          String location = signInAsThePerson(e.path("url").asString());
          try {
            stdin.write((location + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
          } catch (IOException io) {
            throw new IllegalStateException(io);
          }
        }
      });
      assertThat(p.waitFor(120, TimeUnit.SECONDS)).as("sdk-signin.mjs finished: %s", events).isTrue();
    }
    assertThat(p.exitValue()).as("sdk-signin.mjs: %s", events).isZero();
    assertThat(event(events, "registered").path("client_id").asString()).startsWith("gwc_");
    assertThat(event(events, "registered").path("auth").asString()).isEqualTo("none");
    JsonNode authorize = event(events, "authorize");
    assertThat(authorize.path("url").asString()).contains("code_challenge_method=S256")
        .contains("resource=" + java.net.URLEncoder.encode(resource, StandardCharsets.UTF_8));
    assertThat(event(events, "callback").path("iss").asString()).isEqualTo("http://127.0.0.1:" + world.localPort());
    assertThat(event(events, "tokens").path("access").asString()).isEqualTo("gwo_");
    assertThat(event(events, "tokens").path("refresh").asString()).isEqualTo("gwr_");
    assertThat(event(events, "connected").path("server").asString()).isEqualTo("mcp-gateway");
    assertThat(event(events, "tools").path("names").toString()).contains("sql_query");
  }

  @Test
  void conformanceAuthorizationScenariosPass() throws Exception {
    int port = TunnelWorld.freePort();
    String redirect = "http://127.0.0.1:" + port + "/callback";
    TunnelWorld.Answer registered = browser.post(null, "/oauth/register", Map.of("Content-Type", "application/json"),
        "{\"client_name\":\"conformance\",\"redirect_uris\":[\"" + redirect + "\"]}");
    assertThat(registered.status()).as(registered.body()).isEqualTo(201);
    String clientId = registered.json().path("client_id").asString();
    Path out = world.home().resolve("conformance");
    Files.createDirectories(out);
    List<String> lines = new CopyOnWriteArrayList<>();
    Process p = start(List.of(node, CLIENTS.resolve("node_modules/@modelcontextprotocol/conformance/dist/index.js")
        .toString(), "authorization", "--url", "http://127.0.0.1:" + world.localPort(), "--client-id", clientId,
        "--resource", resource, "-p", String.valueOf(port), "-o", out.toString(), "--verbose"), Map.of());
    p.getOutputStream().close();
    read(p.getInputStream(), line -> {
      lines.add(line);
      Matcher m = AUTHORIZE.matcher(line);
      if (m.find()) {
        String location = signInAsThePerson(m.group(1));
        // The browser follows the redirect to the conformance tool's callback server.
        TunnelWorld.Answer callback = get(location);
        assertThat(callback.status()).as(location).isEqualTo(200);
      }
    });
    assertThat(p.waitFor(180, TimeUnit.SECONDS)).as("conformance finished:%n%s", String.join("\n", lines)).isTrue();
    String log = String.join("\n", lines);
    List<JsonNode> checks = checks(out);
    assertThat(checks).as("checks of %s:%n%s", out, log).isNotEmpty();
    List<String> failed = new ArrayList<>();
    for (JsonNode c : checks) {
      String status = c.path("status").asString();
      if (status.equals("FAILURE")) {
        failed.add(c.path("id").asString() + ": " + c.path("errorMessage").asString());
      }
    }
    assertThat(failed).as("failed checks:%n%s", log).isEmpty();
    assertThat(checks).extracting(c -> c.path("id").asString()).contains("authorization-code-grant");
    assertThat(checks.stream().filter(c -> c.path("id").asString().equals("authorization-code-grant"))
        .map(c -> c.path("status").asString())).containsOnly("SUCCESS");
    // The local server signs in without a metadata document: the CIMD check is a warning, not a failure.
    assertThat(p.exitValue()).as(log).isZero();
  }

  /** The browser on the sign-in page, the person's Allow in the app, the page's poll: the redirect it follows. */
  private static String signInAsThePerson(String authorizeUrl) {
    java.net.URI u = java.net.URI.create(authorizeUrl);
    TunnelWorld.Answer page = world.local("GET", u.getRawPath() + "?" + u.getRawQuery(), Map.of(), null);
    assertThat(page.status()).as(page.body()).isEqualTo(200);
    String rid = page.body().replaceAll("(?s).*data-rid=\"(ar_[A-Za-z0-9]+)\".*", "$1");
    String cookie = page.header("set-cookie");
    cookie = cookie.substring(0, cookie.indexOf(';'));
    OAuthFlow.Waiting w = new OAuthFlow.Waiting(page, rid, null, cookie);
    assertThat(browser.consent(w).path("ingress").asString()).isEqualTo("LOCAL");
    return browser.allowLocation(w);
  }

  private static TunnelWorld.Answer get(String url) {
    try {
      var rs = java.net.http.HttpClient.newBuilder().proxy(java.net.http.HttpClient.Builder.NO_PROXY).build()
          .send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).GET().build(),
              java.net.http.HttpResponse.BodyHandlers.ofString());
      return new TunnelWorld.Answer(rs.statusCode(), Map.of(), rs.body());
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The checks the conformance tool saved (one JSON file per scenario, or one with all). */
  private static List<JsonNode> checks(Path out) throws IOException {
    List<JsonNode> all = new ArrayList<>();
    try (Stream<Path> files = Files.walk(out)) {
      for (Path f : files.filter(f -> f.toString().endsWith(".json")).toList()) {
        JsonNode n = TunnelWorld.JSON.readTree(Files.readString(f));
        collect(n, all);
      }
    }
    return all;
  }

  private static void collect(JsonNode n, List<JsonNode> into) {
    if (n.isArray()) {
      n.forEach(c -> collect(c, into));
    } else if (n.isObject()) {
      if (n.has("id") && n.has("status")) {
        into.add(n);
      } else {
        n.properties().forEach(e -> collect(e.getValue(), into));
      }
    }
  }

  // ── Node ────────────────────────────────────────────────────────────────────────────────────────────────────

  private static JsonNode event(List<JsonNode> events, String name) {
    return events.stream().filter(e -> name.equals(e.path("event").asString())).findFirst()
        .orElseThrow(() -> new AssertionError("no " + name + " event in " + events));
  }

  private static Process start(List<String> command, Map<String, String> env) throws IOException {
    ProcessBuilder pb = new ProcessBuilder(command).directory(CLIENTS.toFile()).redirectErrorStream(true);
    pb.environment().putAll(env);
    pb.environment().put("NO_COLOR", "1");
    return pb.start();
  }

  /** Reads {@code in} line by line on a thread of its own until it ends. */
  private static void read(InputStream in, Consumer<String> lines) {
    Thread.ofVirtual().start(() -> {
      try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        for (String line; (line = r.readLine()) != null; ) {
          System.out.println("[node] " + line);
          lines.accept(line);
        }
      } catch (IOException ignored) {
        // The process ended.
      }
    });
  }

  private static Path script(String name) throws IOException {
    Path target = CLIENTS.resolve(name);
    Files.copy(SOURCES.resolve(name), target, StandardCopyOption.REPLACE_EXISTING);
    return target;
  }

  /** {@code npm ci} from the committed lock when the cache doesn't hold exactly these packages yet. */
  private static void install() throws Exception {
    Files.createDirectories(CLIENTS);
    String want = Files.readString(SOURCES.resolve("package-lock.json"));
    Path stamp = CLIENTS.resolve(".installed-from");
    if (Files.exists(CLIENTS.resolve("node_modules/.package-lock.json")) && Files.exists(stamp)
        && Files.readString(stamp).equals(want)) {
      return;
    }
    Files.copy(SOURCES.resolve("package.json"), CLIENTS.resolve("package.json"), StandardCopyOption.REPLACE_EXISTING);
    Files.copy(SOURCES.resolve("package-lock.json"), CLIENTS.resolve("package-lock.json"),
        StandardCopyOption.REPLACE_EXISTING);
    Path npm = Path.of(node).resolveSibling("npm");
    Process p = new ProcessBuilder(Files.exists(npm) ? npm.toString() : "npm", "ci", "--ignore-scripts", "--no-audit",
        "--no-fund").directory(CLIENTS.toFile()).redirectErrorStream(true).start();
    String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(p.waitFor(300, TimeUnit.SECONDS) && p.exitValue() == 0).as("npm ci:%n%s", log).isTrue();
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
}
