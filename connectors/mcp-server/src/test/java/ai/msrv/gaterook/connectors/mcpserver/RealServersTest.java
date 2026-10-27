package ai.msrv.gaterook.connectors.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.support.egress.AddressPolicy;
import ai.msrv.gaterook.connectors.support.egress.EgressClients;
import ai.msrv.gaterook.connectors.support.egress.EgressSpec;
import ai.msrv.gaterook.connectors.support.egress.GuardedEgress;
import ai.msrv.gaterook.connectors.support.egress.HostResolver;
import ai.msrv.gaterook.connectors.support.mcp.McpServerRuntime;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Real servers from npm (profile {@code mcp-servers}, Node 24 from {@code MCP_NODE} or the PATH): each package is
 * installed the way a catalog entry installs it — {@code npm ci --ignore-scripts} from a pinned lockfile in
 * {@code src/test/resources/npm}, its integrity checked before every start — into
 * {@code ~/.cache/mcp-gateway-test/c10-mcp-servers} (npm's cache stays there between runs).
 *
 * <ul>
 *   <li>{@code @modelcontextprotocol/server-github} 2025.4.8: 26 tools, none declares annotations, all published as
 *       {@code github_*}; a package changed after the install doesn't start.</li>
 *   <li>{@code @modelcontextprotocol/server-everything} 2.0.0: over stdio, and over Streamable HTTP on this computer
 *       through the egress guard (allowed as the environment's private range), with a call.</li>
 * </ul>
 */
@Tag("mcp-servers")
class RealServersTest {

  private static final Path CACHE = Path.of(System.getProperty("user.home"), ".cache", "mcp-gateway-test",
      "c10-mcp-servers");
  private static Fixtures.Host host;
  private static McpServers runtime;
  private static EgressClients clients;

  @BeforeAll
  static void attach() throws IOException {
    Files.createDirectories(CACHE.resolve("home"));
    String node = System.getenv("MCP_NODE");
    String path = (node == null || node.isBlank() ? "" : Path.of(node).getParent() + ":")
        + System.getenv().getOrDefault("PATH", "/usr/bin:/bin");
    host = new Fixtures.Host(CACHE.resolve("home"));
    Map<String, String> coreEnv = new java.util.HashMap<>();
    coreEnv.put("PATH", path);
    coreEnv.put("HOME", CACHE.resolve("home").toString());
    coreEnv.put("CORE_CANARY", "core-only-7d1f");
    // npm reaches the registry the way the core would let it: through this machine's proxy and CA bundle.
    for (String name : Launcher.INHERITABLE) {
      if (System.getenv(name) != null) {
        coreEnv.put(name, System.getenv(name));
      }
    }
    runtime = new McpServers(coreEnv,
        System.getProperty("os.name"), false, Clock.systemUTC(), Duration.ofSeconds(1),
        McpServerConnector.typeManifest());
    runtime.attach(host);
    clients = new EgressClients(new EgressClients.Settings("mcp-gateway/test", Set.of(), HostResolver.SYSTEM));
  }

  @AfterAll
  static void close() {
    runtime.close();
    clients.close();
  }

  private static McpServerRuntime.NpmPackage pkg(String name, String version, String resource, String bin)
      throws IOException {
    byte[] lock;
    try (InputStream in = RealServersTest.class.getResourceAsStream("/npm/" + resource + "/package-lock.json")) {
      lock = in.readAllBytes();
    }
    JsonNode entry = Texts.JSON.readTree(lock).path("packages").path("node_modules/" + name);
    return new McpServerRuntime.NpmPackage(name, version, entry.path("integrity").asString(), lock, bin,
        Texts.str(entry.path("engines").path("node"), null), true);
  }

  private static ConnectionConfig stdio(String id, String name, List<String> command, String variable) {
    ObjectNode c = Texts.JSON.createObjectNode().put("transport", "stdio");
    ArrayNode argv = c.putArray("command");
    command.forEach(argv::add);
    if (variable != null) {
      c.putArray("env").addObject().put("name", variable);
    }
    return Fixtures.cfg(id, name, Env.DEV, 1, c);
  }

  @Test
  void serverGithubListsItsTwentySixToolsAndRefusesAChangedPackage() throws Exception {
    McpServerRuntime.NpmPackage pkg = pkg("@modelcontextprotocol/server-github", "2025.4.8",
        "server-github-2025.4.8", "mcp-server-github");
    McpServerRuntime.NpmInstall installed = runtime.install("github-mcp", pkg, Duration.ofMinutes(5));
    assertThat(installed.command()).containsExactly("node",
        "node_modules/@modelcontextprotocol/server-github/dist/index.js");
    assertThat(installed.integrity()).isEqualTo(pkg.integrity());

    ConnectionConfig cfg = stdio("cn_github", "github-mcp", installed.command(), "GITHUB_PERSONAL_ACCESS_TOKEN");
    Fixtures.Secrets secrets = new Fixtures.Secrets().with("/env/0/secretRef", "ghp_" + "x".repeat(36));
    McpServerRuntime.Handshake h = runtime.probe(cfg, secrets, Fixtures.Context.of(Duration.ofSeconds(60)));

    assertThat(h.tools()).hasSize(26);
    assertThat(h.rejected()).isEmpty();
    assertThat(h.tools()).allSatisfy(t -> assertThat(t.path("exposed").asString()).startsWith("github_"));
    assertThat(Snapshots.annotated(h.tools())).isZero();
    assertThat(h.tools()).extracting(t -> t.path("original").asString()).contains("create_issue",
        "search_repositories", "create_or_update_file", "merge_pull_request");
    assertThat(h.notes()).contains("26 tools, 0 declare annotations");

    // Started as a stored catalog server: the integrity is checked first.
    McpServerRuntime.Snapshot snapshot = new McpServerRuntime.Snapshot("github_", h.serverVersion(),
        h.protocolVersion(), h.tools(), pkg.integrity());
    Connector c = runtime.connector(cfg, snapshot);
    assertThat(c.manifest().tools()).allSatisfy(t -> assertThat(t.mutating()).as(t.name()).isTrue());
    runtime.start(cfg, snapshot, secrets, Fixtures.Context.of(Duration.ofSeconds(60)), false);
    runtime.stop(cfg.id());

    Path record = CACHE.resolve("home/mcp/github-mcp/node_modules/.package-lock.json");
    String original = Files.readString(record);
    try {
      Files.writeString(record, original.replace(pkg.integrity(), "sha512-changed"));
      assertThatThrownBy(() -> runtime.start(cfg, snapshot, secrets, Fixtures.Context.of(Duration.ofSeconds(60)),
          true)).hasMessageContaining("doesn't match its catalog entry");
    } finally {
      Files.writeString(record, original);
    }
  }

  @Test
  void serverEverythingAnswersOverStdioAndStreamableHttp() throws Exception {
    McpServerRuntime.NpmPackage pkg = pkg("@modelcontextprotocol/server-everything", "2.0.0",
        "server-everything-2.0.0", "mcp-server-everything");
    McpServerRuntime.NpmInstall installed = runtime.install("everything", pkg, Duration.ofMinutes(5));

    List<String> command = new ArrayList<>(installed.command());
    command.add("stdio");
    McpServerRuntime.Handshake overStdio = runtime.probe(stdio("cn_every", "everything", command, null),
        new Fixtures.Secrets(), Fixtures.Context.of(Duration.ofSeconds(60)));
    assertThat(overStdio.protocolVersion()).isEqualTo("2025-11-25");
    assertThat(overStdio.tools()).extracting(t -> t.path("exposed").asString()).contains("everything_echo",
        "everything_get_env");
    // get-env answers the server's whole environment: the core's own variables never reach it.
    ConnectionConfig every = stdio("cn_every", "everything", command, null);
    McpServerRuntime.Snapshot stdioSnapshot = new McpServerRuntime.Snapshot("everything_", overStdio.serverVersion(),
        overStdio.protocolVersion(), overStdio.tools(), pkg.integrity());
    Connector stdioConnector = runtime.connector(every, stdioSnapshot);
    ToolResult env = stdioConnector.execute(Fixtures.call("everything_get_env", "{}"), every, new Fixtures.Secrets(),
        Fixtures.Context.approved(stdioConnector.describeAction(Fixtures.call("everything_get_env", "{}"), every)
            .payload()));
    assertThat(env.content().getFirst().text()).contains("PATH").doesNotContain("core-only-7d1f");
    runtime.stop(every.id());

    int port;
    try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      port = s.getLocalPort();
    }
    Path workDir = CACHE.resolve("home/mcp/everything");
    ProcessBuilder pb = new ProcessBuilder(nodeCommand(), installed.command().get(1), "streamableHttp")
        .directory(workDir.toFile()).redirectErrorStream(true);
    pb.environment().put("PORT", Integer.toString(port));
    Process http = pb.start();
    try {
      awaitLine(http, "listening on port " + port, Duration.ofSeconds(30));
      ObjectNode c = Texts.JSON.createObjectNode().put("transport", "http")
          .put("url", "http://127.0.0.1:" + port + "/mcp");
      ConnectionConfig cfg = Fixtures.cfg("cn_every_http", "everything-http", Env.DEV, 1, c);
      GuardedEgress guard = clients.egress(EgressSpec.of("cn_every_http",
          AddressPolicy.builder().allowPrivate("127.0.0.1/32").build()));
      McpServerRuntime.Handshake h = runtime.probe(cfg, new Fixtures.Secrets(),
          new Fixtures.Context(Duration.ofSeconds(60), false, null, guard));
      assertThat(h.protocolVersion()).isEqualTo("2025-11-25");
      assertThat(h.tools()).extracting(t -> t.path("original").asString()).contains("echo");

      McpServerRuntime.Snapshot snapshot = new McpServerRuntime.Snapshot("everything_", h.serverVersion(),
          h.protocolVersion(), h.tools(), null);
      Connector connector = runtime.connector(cfg, snapshot);
      Fixtures.Context approved = new Fixtures.Context(Duration.ofSeconds(60), true,
          connector.describeAction(Fixtures.call("everything_echo", "{\"message\":\"hello\"}"), cfg).payload(), guard);
      ToolResult r = connector.execute(Fixtures.call("everything_echo", "{\"message\":\"hello\"}"), cfg,
          new Fixtures.Secrets(), approved);
      assertThat(r.isError()).isFalse();
      assertThat(r.content().getFirst().text()).contains("hello");
      runtime.stop(cfg.id());
    } finally {
      http.destroy();
      http.waitFor(5, TimeUnit.SECONDS);
      http.destroyForcibly();
    }
  }

  private static String nodeCommand() {
    String node = System.getenv("MCP_NODE");
    return node == null || node.isBlank() ? "node" : node;
  }

  private static void awaitLine(Process p, String needle, Duration within) throws InterruptedException {
    CountDownLatch seen = new CountDownLatch(1);
    Thread.ofVirtual().start(() -> {
      try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(),
          StandardCharsets.UTF_8))) {
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
}
