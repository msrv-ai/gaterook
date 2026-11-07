package ai.msrv.gaterook.connectors.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connectors.support.egress.AddressPolicy;
import ai.msrv.gaterook.connectors.support.egress.EgressClients;
import ai.msrv.gaterook.connectors.support.egress.EgressSpec;
import ai.msrv.gaterook.connectors.support.egress.GuardedEgress;
import ai.msrv.gaterook.connectors.support.egress.HostResolver;
import ai.msrv.gaterook.connectors.support.mcp.McpServerRuntime;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The client against the TypeScript MCP SDK 2.3.1's own server (profile {@code mcp-servers}; Node from
 * {@code MCP_NODE} or the PATH, the package from the repository's {@code node_modules}): {@code serveStdio} and
 * {@code createMcpHandler} serve both eras, so a new server is spoken to in 2026-07-28 — with the {@code Mcp-Param-*}
 * header its tool declares, which that server checks — and a server reviewed in 2025-11-25 keeps being spoken to in
 * it, through the stateless fallback.
 */
@Tag("mcp-servers")
class SdkServersTest {

  private static final Path FIXTURE = Path.of("src/test/node/sdk-server.mjs").toAbsolutePath();

  @TempDir
  Path home;

  private McpServers runtime;
  private EgressClients clients;
  private Process http;
  private String node;

  @BeforeEach
  void attach() {
    String configured = System.getenv("MCP_NODE");
    node = configured != null && !configured.isBlank() ? configured : "node";
    Path sdk = Path.of("").toAbsolutePath().getParent().getParent().resolve(
        "node_modules/@modelcontextprotocol/server/package.json");
    assumeTrue(Files.exists(sdk), "npm ci installs @modelcontextprotocol/server into the repository");
    clients = new EgressClients(new EgressClients.Settings("GateRook/test", Set.of(), HostResolver.SYSTEM));
    runtime = new McpServers(Map.of("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"),
        "HOME", home.toString()), System.getProperty("os.name"), false, Clock.systemUTC(), Duration.ofMillis(200),
        McpServerConnector.typeManifest());
    runtime.attach(new Fixtures.Host(home));
  }

  @AfterEach
  void close() {
    if (runtime != null) {
      runtime.close();
    }
    if (clients != null) {
      clients.close();
    }
    if (http != null) {
      http.destroy();
    }
  }

  private Fixtures.Context ctx() {
    GuardedEgress guard = clients.egress(EgressSpec.of("cn_sdk", AddressPolicy.builder()
        .allowPrivate("127.0.0.1/32").build()));
    return new Fixtures.Context(Duration.ofSeconds(60), false, null, guard);
  }

  @Test
  void overStdioANewServerSpeaks2026() throws Exception {
    ObjectNode c = Fixtures.object("{\"transport\":\"stdio\"}");
    ArrayNode command = c.putArray("command");
    command.add(node);
    command.add(FIXTURE.toString());
    command.add("stdio");
    ConnectionConfig cfg = Fixtures.cfg("cn_sdk", "sdk-mcp", Env.DEV, 1, c);

    McpServerRuntime.Handshake h = runtime.probe(cfg, new Fixtures.Secrets(), ctx());

    assertThat(h.protocolVersion()).isEqualTo("2026-07-28");
    assertThat(h.serverVersion()).isEqualTo("2.3.1-fixture");
    assertThat(h.instructions()).isEqualTo("SDK fixture instructions.");
    Connector connector = runtime.connector(cfg, new McpServerRuntime.Snapshot("sdk_", h.serverVersion(),
        h.protocolVersion(), h.tools(), null));
    assertThat(connector.execute(Fixtures.call("sdk_echo", "{\"text\":\"over stdio\"}"), cfg,
        new Fixtures.Secrets(), ctx()).content().getFirst().text()).isEqualTo("over stdio");
  }

  @Test
  void overHttpItGetsTheParamHeaderAndAReviewIn2025StaysThere() throws Exception {
    http = new ProcessBuilder(node, FIXTURE.toString(), "http").redirectErrorStream(true).start();
    String line = new BufferedReader(new InputStreamReader(http.getInputStream(), StandardCharsets.UTF_8))
        .readLine();
    assertThat(line).startsWith("listening ");
    ConnectionConfig cfg = Fixtures.cfg("cn_sdk", "sdk-mcp", Env.DEV, 1, Fixtures.object("""
        {"transport":"http","url":"http://127.0.0.1:%s/mcp"}""".formatted(line.substring(10).strip())));

    McpServerRuntime.Handshake h = runtime.probe(cfg, new Fixtures.Secrets(), ctx());
    assertThat(h.protocolVersion()).isEqualTo("2026-07-28");
    Connector modern = runtime.connector(cfg, new McpServerRuntime.Snapshot("sdk_", h.serverVersion(),
        h.protocolVersion(), h.tools(), null));
    // The server refuses a call whose Mcp-Param-Region doesn't match the argument (-32020).
    assertThat(modern.execute(Fixtures.call("sdk_region", "{\"region\":\"eu-west-1\"}"), cfg, new Fixtures.Secrets(),
        ctx()).content().getFirst().text()).isEqualTo("region eu-west-1");

    runtime.stop("cn_sdk");
    ConnectionConfig again = Fixtures.cfg("cn_sdk", "sdk-mcp", Env.DEV, 2, cfg.config());
    Connector legacy = runtime.connector(again, new McpServerRuntime.Snapshot("sdk_", h.serverVersion(),
        "2025-11-25", h.tools(), null));
    assertThat(legacy.execute(Fixtures.call("sdk_echo", "{\"text\":\"in 2025\"}"), again, new Fixtures.Secrets(),
        ctx()).content().getFirst().text()).isEqualTo("in 2025");
    Session s = runtime.running("cn_sdk");
    assertThat(s.protocolVersion()).isEqualTo("2025-11-25");
  }
}
