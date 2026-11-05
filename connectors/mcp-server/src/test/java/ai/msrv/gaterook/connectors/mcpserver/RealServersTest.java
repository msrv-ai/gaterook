package ai.msrv.gaterook.connectors.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
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
 *       through the egress guard (allowed as the environment's private range), with a call; it serves both eras and is
 *       spoken to in 2026-07-28.</li>
 *   <li>A package with an install script, served from this computer: {@code npm ci} runs it in the sandbox servers
 *       start in, which lets it write its work folder and nothing else.</li>
 * </ul>
 *
 * <p>When this checkout's bridge is built ({@code bridge-cli/target/gw-bridge.jar}), the home gets the
 * {@code bin/gw-bridge} launcher the core writes, so installs and servers run in the OS sandbox ({@code sandbox-exec}
 * on macOS, Landlock on Linux) as they do in the app; without it they run unconfined, and the sandbox test is skipped.
 */
@Tag("mcp-servers")
class RealServersTest {

  private static final Path CACHE = Path.of(System.getProperty("user.home"), ".cache", "mcp-gateway-test",
      "c10-mcp-servers");
  private static Fixtures.Host host;
  private static McpServers runtime;
  private static EgressClients clients;
  /** Whether {@code home/bin/gw-bridge} runs this checkout's bridge. */
  private static boolean bridge;

  @BeforeAll
  static void attach() throws IOException {
    Files.createDirectories(CACHE.resolve("home"));
    bridge = installBridge(CACHE.resolve("home"));
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
    clients = new EgressClients(new EgressClients.Settings("GateRook/test", Set.of(), HostResolver.SYSTEM));
  }

  @AfterAll
  static void close() throws IOException {
    runtime.close();
    clients.close();
    // The cache outlives this checkout: no launcher is left pointing into it.
    Files.deleteIfExists(CACHE.resolve("home/bin/gw-bridge"));
  }

  /**
   * {@code <home>/bin/gw-bridge} as the core writes it ({@code BridgeShimInstaller}), running this checkout's bridge
   * JAR: {@code false}, and no launcher, when it isn't built.
   */
  private static boolean installBridge(Path home) throws IOException {
    Path shim = home.resolve("bin/gw-bridge");
    Files.deleteIfExists(shim);
    Path jar = Path.of("..", "..", "bridge-cli", "target", "gw-bridge.jar").toAbsolutePath().normalize();
    if (!Files.isRegularFile(jar) || System.getProperty("os.name").startsWith("Windows")) {
      return false;
    }
    Files.createDirectories(shim.getParent());
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");
    Files.writeString(shim, "#!/bin/sh\nGW_HOME='" + home + "'; export GW_HOME\nexec '" + java + "' -XX:+UseSerialGC "
        + "-XX:TieredStopAtLevel=1 -Xshare:auto -Xmx64m --enable-native-access=ALL-UNNAMED -jar '" + jar
        + "' \"$@\"\n");
    Files.setPosixFilePermissions(shim, PosixFilePermissions.fromString("rwx------"));
    return true;
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
    // Its stdio entry serves both eras: a new server is spoken to in the newest.
    assertThat(overStdio.protocolVersion()).isEqualTo("2026-07-28");
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
      // Over Streamable HTTP too.
      assertThat(h.protocolVersion()).isEqualTo("2026-07-28");
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

  /**
   * {@code npm ci} runs in the sandbox servers start in, install scripts included (a catalog entry may allow them):
   * the package's script writes its own folder in the server's work folder, where npm keeps its cache too, and can
   * write neither GateRook's home nor the person's; nor can it connect to the core's ports, while npm reaches the
   * registry on another port of this computer. The package comes from that registry, by the lockfile's
   * {@code resolved} URL, so the test needs no network.
   */
  @Test
  void anInstallScriptWritesOnlyTheServersWorkFolder() throws Exception {
    assumeTrue(bridge, "bridge-cli/target/gw-bridge.jar isn't built");
    assertThat(runtime.launcher().sandbox()).as("gw-bridge sandbox-exec works here").isNotNull();
    Path work = CACHE.resolve("home/mcp/sandbox-probe");
    // In GateRook's home beside the work folders, and in the person's home outside GateRook's.
    List<Path> outside = List.of(CACHE.resolve("home/planted.txt"), CACHE.resolve("planted.txt"));
    for (Path p : outside) {
      Files.deleteIfExists(p);
    }
    // A listener standing in for the core's local API: the host names its port at the next start.
    ServerSocket core = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    Thread.ofVirtual().start(() -> {
      while (!core.isClosed()) {
        try {
          core.accept().close();
        } catch (IOException e) {
          // Closed at the end of the test.
        }
      }
    });
    host.corePorts = Set.of(core.getLocalPort());
    StringBuilder script = new StringBuilder("const fs = require('fs');\nconst seen = [];\n");
    for (Path p : outside) {
      String target = Texts.JSON.writeValueAsString(p.toString());
      script.append("try { fs.writeFileSync(").append(target).append(", 'planted'); seen.push('WROTE ' + ")
          .append(target).append("); } catch (e) { seen.push(e.code); }\n");
    }
    script.append("const socket = require('net').connect(").append(core.getLocalPort()).append(", '127.0.0.1');\n")
        .append("const done = what => { seen.push(what); socket.destroy(); ")
        .append("fs.writeFileSync('ran.txt', seen.join('\\n')); };\n")
        .append("socket.on('connect', () => done('REACHED the core'));\n")
        .append("socket.on('error', e => done('core ' + e.code));\n");
    Map<String, String> files = new LinkedHashMap<>();
    files.put("package/package.json", "{\"name\":\"gw-sandbox-probe\",\"version\":\"1.0.0\","
        + "\"bin\":{\"gw-sandbox-probe\":\"index.js\"},\"scripts\":{\"install\":\"node install.js\"}}");
    files.put("package/index.js", "#!/usr/bin/env node\n");
    files.put("package/install.js", script.toString());
    byte[] tarball = tgz(files);
    String integrity = "sha512-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512")
        .digest(tarball));

    com.sun.net.httpserver.HttpServer registry = com.sun.net.httpserver.HttpServer.create(
        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    registry.createContext("/gw-sandbox-probe/-/gw-sandbox-probe-1.0.0.tgz", exchange -> {
      exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
      exchange.sendResponseHeaders(200, tarball.length);
      exchange.getResponseBody().write(tarball);
      exchange.close();
    });
    registry.start();
    try {
      String resolved = "http://127.0.0.1:" + registry.getAddress().getPort()
          + "/gw-sandbox-probe/-/gw-sandbox-probe-1.0.0.tgz";
      byte[] lock = ("{\"name\":\"mcp-gateway-server\",\"lockfileVersion\":3,\"requires\":true,\"packages\":{"
          + "\"\":{\"name\":\"mcp-gateway-server\",\"dependencies\":{\"gw-sandbox-probe\":\"1.0.0\"}},"
          + "\"node_modules/gw-sandbox-probe\":{\"version\":\"1.0.0\",\"resolved\":\"" + resolved + "\","
          + "\"integrity\":\"" + integrity + "\",\"hasInstallScript\":true,"
          + "\"bin\":{\"gw-sandbox-probe\":\"index.js\"}}}}").getBytes(StandardCharsets.UTF_8);
      McpServerRuntime.NpmPackage pkg = new McpServerRuntime.NpmPackage("gw-sandbox-probe", "1.0.0", integrity, lock,
          "gw-sandbox-probe", null, false);

      McpServerRuntime.NpmInstall installed = runtime.install("sandbox-probe", pkg, Duration.ofMinutes(2));

      assertThat(installed.command()).containsExactly("node", "node_modules/gw-sandbox-probe/index.js");
      // The script ran, in its own folder, and was refused every write outside the work folder.
      Path ran = work.resolve("node_modules/gw-sandbox-probe/ran.txt");
      assertThat(ran).exists();
      List<String> seen = Files.readAllLines(ran);
      assertThat(seen).hasSize(outside.size() + 1).noneMatch(line -> line.startsWith("WROTE"));
      for (Path p : outside) {
        assertThat(p).doesNotExist();
      }
      // The core's port was refused by the sandbox (EPERM from Seatbelt, EACCES from Landlock), not unanswered: npm
      // reached the registry's port on the same address. Linux before 6.7 (Landlock ABI 4) can't close a port.
      if (portRules()) {
        assertThat(seen.getLast()).isIn("core EPERM", "core EACCES");
      }
      // npm's cache is the work folder's own.
      assertThat(work.resolve(".npm/_cacache")).isDirectory();
    } finally {
      registry.stop(0);
      host.corePorts = Set.of();
      core.close();
      for (Path p : outside) {
        Files.deleteIfExists(p);
      }
    }
  }

  /** Whether this system's sandbox closes TCP ports: macOS, or Linux 6.7 and later (Landlock ABI 4). */
  private static boolean portRules() {
    if (!System.getProperty("os.name").startsWith("Linux")) {
      return true;
    }
    java.util.regex.Matcher v = java.util.regex.Pattern.compile("^(\\d+)\\.(\\d+)")
        .matcher(System.getProperty("os.version"));
    return v.find() && (Integer.parseInt(v.group(1)) > 6
        || Integer.parseInt(v.group(1)) == 6 && Integer.parseInt(v.group(2)) >= 7);
  }

  /** A gzipped ustar archive of {@code files} (name to text), as npm packs a package. */
  private static byte[] tgz(Map<String, String> files) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
      for (Map.Entry<String, String> f : files.entrySet()) {
        byte[] data = f.getValue().getBytes(StandardCharsets.UTF_8);
        byte[] header = new byte[512];
        field(header, 0, 100, f.getKey());
        field(header, 100, 8, "0000644");
        field(header, 108, 8, "0000000");
        field(header, 116, 8, "0000000");
        field(header, 124, 12, "%011o".formatted(data.length));
        field(header, 136, 12, "%011o".formatted(1_700_000_000L));
        Arrays.fill(header, 148, 156, (byte) ' ');
        header[156] = '0';
        field(header, 257, 6, "ustar");
        field(header, 263, 2, "00");
        int sum = 0;
        for (byte b : header) {
          sum += b & 0xff;
        }
        field(header, 148, 7, "%06o".formatted(sum));
        gz.write(header);
        gz.write(data);
        gz.write(new byte[(512 - data.length % 512) % 512]);
      }
      gz.write(new byte[1024]);
    }
    return bytes.toByteArray();
  }

  /** {@code text} at {@code offset}, NUL-padded to {@code length}. */
  private static void field(byte[] header, int offset, int length, String text) {
    byte[] b = text.getBytes(StandardCharsets.US_ASCII);
    int n = Math.min(b.length, length);
    System.arraycopy(b, 0, header, offset, n);
    Arrays.fill(header, offset + n, offset + length, (byte) 0);
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
