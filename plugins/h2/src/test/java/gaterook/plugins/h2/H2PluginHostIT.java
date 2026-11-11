package gaterook.plugins.h2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ApiVersion;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.plugin.protocol.Gpp;
import ai.msrv.gaterook.plugin.protocol.GppJson;
import ai.msrv.gaterook.plugin.protocol.JsonRpcPeer;
import ai.msrv.gaterook.plugin.protocol.Messages;
import ai.msrv.gaterook.plugin.protocol.PluginManifest;
import ai.msrv.gaterook.plugin.protocol.RpcException;
import ai.msrv.gaterook.plugin.protocol.SecretWire;
import ai.msrv.gaterook.plugin.protocol.WireCodec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.h2.tools.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The release JAR in a real plugin host, spawned as the core spawns one (ARCHITECTURE.md §7.5), against an H2 server
 * in this JVM. The host's TCP gate carries the H2 client's sockets: the server's address passes under a grant that
 * allows this computer, the same query is refused when the environment's policy doesn't, and a test then names the
 * range to allow. On macOS the host runs under {@code sandbox-exec} with the core's rules for files.
 */
class H2PluginHostIT {

  private static final String GENERATION = "h2@1.0.0";
  private static final List<Integer> CORE_PORTS = List.of(7465, 7466);

  @TempDir
  Path temp;

  private Path home;
  private Path dataDir;
  private Server server;
  private Connection keeper;
  private Process process;
  private JsonRpcPeer peer;
  private final List<String> stderr = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    Path jar = jar();
    Assumptions.assumeTrue(Files.isRegularFile(jar), "the release JAR is built by package: " + jar);
    home = Files.createDirectories(temp.resolve("gaterook-home")).toRealPath();
    dataDir = Files.createDirectories(home.resolve("plugin-data").resolve("h2").resolve("tmp")).getParent();
    server = Server.createTcpServer("-tcpPort", "0", "-ifNotExists").start();
    String url = "jdbc:h2:tcp://127.0.0.1:" + server.getPort() + "/mem:shop";
    keeper = DriverManager.getConnection(url, "sa", "sa-secret");
    TestCalls.run(url, "sa", "sa-secret", "CREATE TABLE ORDERS (ID INT PRIMARY KEY, TOTAL DECIMAL(10, 2))",
        "INSERT INTO ORDERS VALUES (1, 10.50), (2, 99.00)", "CREATE USER READER PASSWORD 'reader-secret'",
        "GRANT SELECT ON ORDERS TO READER");
  }

  @AfterEach
  void stop() throws Exception {
    if (peer != null) {
      try {
        peer.request(Gpp.HOST_SHUTDOWN, GppJson.object().put("graceMs", 500)).get(Duration.ofSeconds(3));
      } catch (Exception e) {
        // It may already be gone.
      }
      peer.close();
    }
    if (process != null) {
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      process.destroyForcibly();
    }
    keeper.close();
    server.stop();
  }

  @Test
  void theDriverReachesTheServerThroughTheGateUnderAGrantThatAllowsThisComputer() throws Exception {
    Messages.Loaded loaded = start();
    assertThat(hashes(loaded.tools())).isEqualTo(PluginManifest.parse(TestCalls.manifest()).definitionHashes());
    Messages.EgressGrant grant = grant(List.of("127.0.0.0/8"));

    TestResult t = test(grant);
    assertThat(t.ok()).as(String.valueOf(t.error()) + " " + stderr).isTrue();
    assertThat(t.serverVersion()).isEqualTo("H2 2.5.252");
    assertThat(t.permissions().level()).isEqualTo(TestResult.Level.READ);

    ToolResult rows = WireCodec.result(execute(TestCalls.sql("h2_query", "SELECT ID, TOTAL FROM ORDERS ORDER BY ID"),
        grant, false));
    assertThat(rows.isError()).isFalse();
    assertThat(rows.table().rows().toString()).isEqualTo("[[1,\"10.50\"],[2,\"99.00\"]]");
    // The reader may not write: H2 itself refuses the approved change, and nothing changed.
    ToolResult refused = WireCodec.result(execute(TestCalls.sql("h2_execute", "INSERT INTO ORDERS VALUES (3, 1)"),
        grant, true));
    assertThat(refused.isError()).isTrue();
    assertThat(refused.content().getFirst().text()).startsWith("The user may not make this change");
  }

  @Test
  void withoutThePolicysPrivateRangeTheGateRefusesTheServerAndTheTestSaysWhatToAllow() throws Exception {
    start();
    Messages.EgressGrant grant = grant(List.of());

    TestResult t = test(grant);
    assertThat(t.ok()).isFalse();
    assertThat(t.error().code()).as(t.error().message()).isEqualTo("tcp.private-address");
    assertThat(t.error().message()).isEqualTo("127.0.0.1 is a private address. Allow 127.0.0.1/32 for warehouse on "
        + "dev in the environment's policy (http.allowedPrivateRanges).");
    assertThat(t.suggestedPolicyChange().path("cidr").asString()).isEqualTo("127.0.0.1/32");

    assertThatThrownBy(() -> execute(TestCalls.sql("h2_query", "SELECT 1"), grant, false))
        .hasMessageContaining("127.0.0.1 is a private address");
  }

  @Test
  void aGrantForAnotherPortLetsNothingThrough() throws Exception {
    start();
    int other = server.getPort() == 65_535 ? 1 : server.getPort() + 1;
    Messages.EgressGrant grant = new Messages.EgressGrant(List.of(), List.of("127.0.0.0/8"), CORE_PORTS,
        List.of("127.0.0.1:" + other));
    TestResult t = test(grant);
    assertThat(t.ok()).isFalse();
    assertThat(t.error().code()).isEqualTo("tcp.not-granted");
    assertThat(t.error().message()).isEqualTo("This plugin may only connect to 127.0.0.1:" + other + " for this "
        + "connection, not 127.0.0.1:" + server.getPort() + ".");
  }

  // ── The host ────────────────────────────────────────────────────────────────────────────────────────────────

  private Messages.EgressGrant grant(List<String> allowPrivate) {
    return new Messages.EgressGrant(List.of(), allowPrivate, CORE_PORTS, List.of("127.0.0.1:" + server.getPort()));
  }

  private static Path jar() {
    return Path.of(System.getProperty("gateway.h2Jar", "target/gw-h2-1.0.0.jar"));
  }

  private static boolean mac() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac")
        && Files.isExecutable(Path.of("/usr/bin/sandbox-exec"));
  }

  private Messages.Loaded start() throws Exception {
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");
    String hostJar = System.getProperty("gateway.pluginHostJar");
    Assumptions.assumeTrue(hostJar != null && Files.isRegularFile(Path.of(hostJar)), "plugin-host.jar is copied by "
        + "pre-integration-test");
    boolean sandbox = mac();
    List<String> argv = new ArrayList<>();
    if (sandbox) {
      argv.addAll(List.of("/usr/bin/sandbox-exec", "-p", macProfile()));
    }
    // The core's flags for a host (HostLauncher.command), its heap from the manifest.
    argv.addAll(List.of(java.toString(), "-Xlog:disable", "-Xlog:all=warning:stderr", "-XX:+UseSerialGC",
        "-XX:TieredStopAtLevel=1", "-Xms8m", "-Xmx192m", "-Xss512k", "-XX:ReservedCodeCacheSize=32m",
        "-XX:MaxMetaspaceSize=128m", "-XX:-UsePerfData", "-XX:+DisableAttachMechanism", "-XX:+ExitOnOutOfMemoryError",
        "--enable-native-access=ALL-UNNAMED", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
        "-Djava.io.tmpdir=" + dataDir.resolve("tmp"), "-cp", hostJar, "ai.msrv.gaterook.pluginhost.Main",
        "--protocol", String.valueOf(Gpp.PROTOCOL)));
    ProcessBuilder pb = new ProcessBuilder(argv).directory(dataDir.toFile());
    pb.environment().clear();
    pb.environment().put("LANG", "C.UTF-8");
    pb.environment().put("TMPDIR", dataDir.resolve("tmp").toString());
    pb.environment().put("PATH", "/usr/bin:/bin");
    if (sandbox) {
      pb.environment().put("MCP_GATEWAY_SANDBOX", "sandbox-exec");
    }
    process = pb.start();
    Thread.ofVirtual().start(this::pumpStderr);
    peer = new JsonRpcPeer(process.getInputStream(), process.getOutputStream(), new JsonRpcPeer.Handler() {
      @Override
      public Object handle(String method, JsonNode params, JsonRpcPeer.Incoming call) throws RpcException {
        return switch (method) {
          case Gpp.SECRETS_GET -> {
            if (!"/password".equals(params.path("field").asString())) {
              throw new RpcException(Gpp.SECRET_DENIED, "no secret " + params.path("field").asString());
            }
            yield SecretWire.text("reader-secret".toCharArray(), "v1", null);
          }
          case Gpp.SECRETS_META -> {
            ObjectNode o = GppJson.object();
            o.put("set", "/password".equals(params.path("field").asString()));
            o.put("source", "local");
            o.put("version", "v1");
            yield o;
          }
          default -> throw new RpcException(Gpp.METHOD_NOT_FOUND, "No core method " + method + " in this test");
        };
      }
    }, JsonRpcPeer.Options.defaults().name("core").strayLine(line -> stderr.add("stdout: " + line))).start();
    await(peer.request(Gpp.HOST_HELLO, new Messages.Hello(Gpp.PROTOCOL, "0.9.2", ApiVersion.CURRENT, false,
        Gpp.MAX_FRAME_BYTES, 8, "info").write()), Duration.ofSeconds(30));
    Messages.Sandbox s = new Messages.Sandbox(sandbox, List.of(), List.of(dataDir.toString()));
    Path jar = jar().toAbsolutePath();
    Messages.Loaded loaded = Messages.Loaded.read(await(peer.request(Gpp.PLUGIN_LOAD, new Messages.Load(GENERATION,
        1, jar.toString(), sha256(jar), dataDir.toString(), s).write()), Duration.ofSeconds(30)));
    if (sandbox) {
      assertThat(loaded.sandbox()).isEqualTo("sandbox-exec");
    }
    return loaded;
  }

  /** The core's file rules for a host on macOS (HostLauncher.macProfile), with this test's home and data folder. */
  private String macProfile() throws IOException {
    String user = System.getProperty("user.home");
    StringBuilder b = new StringBuilder("(version 1)\n(allow default)\n(deny file-write* (subpath \"/\"))\n"
        + "(allow file-write* (subpath \"/dev\"))\n(deny file-read* file-write*\n");
    for (String denied : List.of(home.toString(), user + "/.ssh", user + "/.aws")) {
      b.append("  (subpath \"").append(denied).append("\")\n");
    }
    b.append(")\n(allow file-read* file-write* (subpath \"").append(dataDir.toRealPath()).append("\"))\n");
    b.append("(allow file-read-metadata (literal \"").append(home).append("\") (literal \"")
        .append(home.resolve("plugin-data")).append("\"))\n");
    b.append("(deny network-outbound (remote ip \"*:7465\") (remote ip \"*:7466\"))\n");
    return b.toString();
  }

  private ConnectionConfig connection() {
    return TestCalls.config(Env.DEV, server.getPort(), "mem:shop", "READER");
  }

  private TestResult test(Messages.EgressGrant grant) throws Exception {
    Messages.Test t = new Messages.Test(GENERATION, "tt_1", "ct_test", connection(), 10_000, TestCalls.DEV, grant);
    return WireCodec.test(await(peer.request(Gpp.CONNECTOR_TEST, t.write()), Duration.ofSeconds(15)));
  }

  private JsonNode execute(ToolCall call, Messages.EgressGrant grant, boolean approved) throws Exception {
    Messages.Execute e = new Messages.Execute(GENERATION, "ct_" + System.nanoTime(), call, connection(), 10_000,
        approved, approved ? "ap_1" : null, null, approved ? call.arguments() : null, TestCalls.DEV, null, null,
        grant);
    return await(peer.request(Gpp.CONNECTOR_EXECUTE, e.write()), Duration.ofSeconds(15));
  }

  private JsonNode await(JsonRpcPeer.Call<JsonNode> call, Duration timeout) throws Exception {
    try {
      return call.get(timeout);
    } catch (RpcException e) {
      throw new IllegalStateException(e.getMessage() + " (host: " + stderr + ")", e);
    }
  }

  private void pumpStderr() {
    try (InputStream err = process.getErrorStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(err, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        stderr.add(line);
      }
    } catch (IOException e) {
      // Ended.
    }
  }

  private static Map<String, String> hashes(List<ToolDefinition> tools) {
    Map<String, String> out = new LinkedHashMap<>();
    tools.forEach(t -> out.put(t.name(), WireCodec.definitionHash(t)));
    return out;
  }

  private static String sha256(Path file) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
  }
}
