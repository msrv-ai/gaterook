package gaterook.plugins.access;

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
import ai.msrv.gaterook.plugin.protocol.WireCodec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * The release JAR in a real plugin host, spawned as the core spawns one (ARCHITECTURE.md §7.5): a child JVM with the
 * host's flags and a cleared environment, GPP/1 over its stdin and stdout, the plugin's data folder as its temporary
 * folder. On macOS the host runs under {@code sandbox-exec} with the core's rules for files: GateRook's home and the
 * credential folders unreadable, nothing writable but the data folder and the devices. There UCanAccess reads the
 * person's .accdb file, and an approved change can't open the file for writing; without the sandbox the same change
 * applies and the next read sees it.
 */
class AccessPluginHostIT {

  private static final String GENERATION = "msaccess@1.0.0";

  @TempDir
  Path temp;

  private Path home;
  private Path dataDir;
  private Path database;
  private Process process;
  private JsonRpcPeer peer;
  private final List<String> stderr = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    Path jar = jar();
    Assumptions.assumeTrue(Files.isRegularFile(jar), "the release JAR is built by package: " + jar);
    home = Files.createDirectories(temp.resolve("gaterook-home")).toRealPath();
    dataDir = Files.createDirectories(home.resolve("plugin-data").resolve("msaccess").resolve("tmp")).getParent();
    database = TestCalls.database(temp.resolve("project").resolve("shop.accdb")).toRealPath();
  }

  @AfterEach
  void stop() {
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
  }

  @Test
  void theReleaseReadsThePersonsFileInTheMacSandboxAndCantWriteIt() throws Exception {
    Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac")
        && Files.isExecutable(Path.of("/usr/bin/sandbox-exec")), "sandbox-exec is macOS's");
    Messages.Loaded loaded = start(true);
    assertThat(loaded.sandbox()).isEqualTo("sandbox-exec");
    assertThat(hashes(loaded.tools())).isEqualTo(PluginManifest.parse(TestCalls.manifest()).definitionHashes());

    TestResult t = test();
    assertThat(t.ok()).as(String.valueOf(t.error()) + " " + stderr).isTrue();
    assertThat(t.serverVersion()).isEqualTo("Access 2010");
    assertThat(t.details().path("tables").asInt()).isEqualTo(2);
    // The sandbox lets the host write its data folder only: the file reads as read-only from there.
    assertThat(t.details().path("writable").asBoolean()).isFalse();
    assertThat(t.hints()).extracting(TestResult.Hint::code).contains("access.file-read-only");

    ToolResult rows = WireCodec.result(execute(TestCalls.sql("access_query", "SELECT ID, Note, Total FROM Orders "
        + "ORDER BY ID"), false));
    assertThat(rows.isError()).as(rows.toString()).isFalse();
    assertThat(rows.table().rows().toString()).isEqualTo("[[1,\"first\",\"12.5000\"],[2,\"second\",\"99.0000\"]]");

    String before = sha256(database);
    assertThatThrownBy(() -> execute(TestCalls.sql("access_execute", "INSERT INTO Orders (Note) VALUES ('third')"),
        true)).hasMessageContaining("can't open shop.accdb for writing here").hasMessageContaining("Nothing changed");
    assertThat(sha256(database)).isEqualTo(before);
    assertThatThrownBy(() -> execute(TestCalls.sql("access_query", "DROP TABLE Orders"), false))
        .hasMessageContaining("only reads");
  }

  @Test
  void anApprovedChangeAppliesWhereTheHostMayWriteTheFile() throws Exception {
    Messages.Loaded loaded = start(false);
    assertThat(loaded.tools()).extracting(ToolDefinition::name).containsExactly("access_list_tables",
        "access_describe_table", "access_query", "access_execute");
    assertThatThrownBy(() -> execute(TestCalls.sql("access_execute", "INSERT INTO Orders (Note) VALUES ('third')"),
        false)).hasMessageContaining("Not approved");
    ToolResult count = WireCodec.result(execute(TestCalls.sql("access_query", "SELECT COUNT(*) FROM Orders"), false));
    assertThat(count.table().rows().get(0).get(0).asInt()).isEqualTo(2);
    ToolResult done = WireCodec.result(execute(TestCalls.sql("access_execute", "INSERT INTO Orders (Note) VALUES "
        + "('third')"), true));
    assertThat(done.isError()).as(done.toString()).isFalse();
    assertThat(done.structuredContent().path("rowsChanged").asLong()).isEqualTo(1);
    ToolResult after = WireCodec.result(execute(TestCalls.sql("access_query", "SELECT COUNT(*) FROM Orders"), false));
    assertThat(after.table().rows().get(0).get(0).asInt()).isEqualTo(3);
    ToolResult refused = WireCodec.result(execute(TestCalls.sql("access_query", "SELECT * FROM Nope"), false));
    assertThat(refused.isError()).isTrue();
  }

  // ── The host ────────────────────────────────────────────────────────────────────────────────────────────────

  private static Path jar() {
    return Path.of(System.getProperty("gateway.accessJar", "target/gw-msaccess-1.0.0.jar"));
  }

  private Messages.Loaded start(boolean sandbox) throws Exception {
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");
    String hostJar = System.getProperty("gateway.pluginHostJar");
    Assumptions.assumeTrue(hostJar != null && Files.isRegularFile(Path.of(hostJar)), "plugin-host.jar is copied by "
        + "pre-integration-test");
    List<String> argv = new ArrayList<>();
    if (sandbox) {
      argv.addAll(List.of("/usr/bin/sandbox-exec", "-p", macProfile()));
    }
    // The core's flags for a host (HostLauncher.command), its heap from the manifest.
    argv.addAll(List.of(java.toString(), "-Xlog:disable", "-Xlog:all=warning:stderr", "-XX:+UseSerialGC",
        "-XX:TieredStopAtLevel=1", "-Xms8m", "-Xmx512m", "-Xss512k", "-XX:ReservedCodeCacheSize=32m",
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
        throw new RpcException(Gpp.METHOD_NOT_FOUND, "No core method " + method + " in this test");
      }
    }, JsonRpcPeer.Options.defaults().name("core").strayLine(line -> stderr.add("stdout: " + line))).start();
    await(peer.request(Gpp.HOST_HELLO, new Messages.Hello(Gpp.PROTOCOL, "0.9.2", ApiVersion.CURRENT, false,
        Gpp.MAX_FRAME_BYTES, 8, "info").write()), Duration.ofSeconds(30));
    Messages.Sandbox s = new Messages.Sandbox(sandbox, List.of(), List.of(dataDir.toString()));
    Path jar = jar().toAbsolutePath();
    return Messages.Loaded.read(await(peer.request(Gpp.PLUGIN_LOAD, new Messages.Load(GENERATION, 1, jar.toString(),
        sha256(jar), dataDir.toString(), s).write()), Duration.ofSeconds(30)));
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
    return b.toString();
  }

  private ConnectionConfig connection() {
    return TestCalls.config(Env.DEV, database.toString());
  }

  private TestResult test() throws Exception {
    Messages.Test t = new Messages.Test(GENERATION, "tt_1", "ct_test", connection(), 10_000, TestCalls.DEV,
        Messages.EgressGrant.NONE);
    return WireCodec.test(await(peer.request(Gpp.CONNECTOR_TEST, t.write()), Duration.ofSeconds(15)));
  }

  private JsonNode execute(ToolCall call, boolean approved) throws Exception {
    Messages.Execute e = new Messages.Execute(GENERATION, "ct_" + System.nanoTime(), call, connection(), 10_000,
        approved, approved ? "ap_1" : null, null, approved ? call.arguments() : null, TestCalls.DEV, null, null,
        Messages.EgressGrant.NONE);
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
