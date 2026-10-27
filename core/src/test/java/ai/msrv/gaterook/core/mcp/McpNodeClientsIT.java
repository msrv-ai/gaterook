package ai.msrv.gaterook.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.core.endpoints.CoreTools;
import ai.msrv.gaterook.core.endpoints.EndpointConnections;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.EndpointTools;
import ai.msrv.gaterook.core.endpoints.TokenService;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.testfixtures.FakeConnectors;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The reference Node clients against a real core (report 01 §8 steps 5–7, ARCHITECTURE.md M1.5), profile
 * {@code mcp-clients}: {@code @modelcontextprotocol/client} 2.3.1 in the modern era with MRTR, MCP Inspector 2.12.0
 * in both eras, and conformance 0.1.16 through a loopback proxy that adds the Bearer token. The packages come from
 * {@code core/src/test/node/package-lock.json} into {@code ~/.cache/mcp-gateway-test/node-clients} (override:
 * {@code MCP_NODE_CLIENTS}); Node is {@code MCP_NODE}, else {@code /opt/node24/…}, else {@code node} on the PATH.
 */
@Tag("mcp-clients")
class McpNodeClientsIT {

  static final Path SOURCES = Path.of("src/test/node").toAbsolutePath();
  static final Path CLIENTS = Path.of(System.getenv().getOrDefault("MCP_NODE_CLIENTS",
      System.getProperty("user.home") + "/.cache/mcp-gateway-test/node-clients"));

  static TestCore core;
  static String node;
  static String origin;
  static String devToken;
  static ApprovalService approvals;
  static Path work;

  @BeforeAll
  static void start() throws Exception {
    node = nodeBinary();
    installClients();
    core = TestCore.start("mcp-node");
    core.connection("dev-db", "pgfake", Env.DEV, "db.dev.internal");
    EndpointService endpoints = core.bean(EndpointService.class);
    EndpointConnections wiring = core.bean(EndpointConnections.class);
    EndpointTools modes = core.bean(EndpointTools.class);
    for (String slug : List.of("dev", "conformance")) {
      endpoints.create(slug, null, EndpointService.StartFrom.EMPTY, null);
      wiring.add(slug, "dev-db", null);
      for (String tool : CoreTools.WITH_MODE) {
        var pair = endpoints.snapshot(slug).coreTool(tool).orElseThrow();
        modes.setMode(slug, tool, null, ai.msrv.gaterook.connector.api.ToolDefinition.Mode.READ_ONLY, null,
            pair.rowVersion(), null);
      }
      endpoints.updateSettings(slug, new EndpointService.SettingsUpdate(null, null, null, null, null,
          JsonNodeFactory.instance.objectNode().put("firstWaitSeconds", 0), null), endpoints.get(slug).version(),
          null);
    }
    TokenService tokens = core.bean(TokenService.class);
    devToken = tokens.issue("dev", "ts-sdk", 7, null).token();
    origin = core.bean(LocalListener.class).origin();
    approvals = core.bean(ApprovalService.class);
    work = core.home().resolve("node");
    Files.createDirectories(work);
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
  }

  // ── TypeScript SDK 2.3.1, modern era with MRTR ──────────────────────────────────────────────────────────────

  @Test
  void theTypeScriptClientApprovesThroughMrtrAndDeclinesInTheClient() throws Exception {
    FakeConnectors.EXECUTIONS.clear();
    String approveSql = "UPDATE orders SET status = 'ts' WHERE id = 48231;";
    String declineSql = "DELETE FROM orders WHERE id = 48232;";
    List<JsonNode> events = new ArrayList<>();
    int exit = run(List.of(node, script("mrtr.mjs").toString()), Map.of("MCP_URL", origin + "/mcp/dev", "MCP_TOKEN",
        devToken, "MCP_SQL_APPROVE", approveSql, "MCP_SQL_DECLINE", declineSql), line -> {
          JsonNode e = McpClient.JSON.readTree(line);
          events.add(e);
          if ("elicitation".equals(e.path("event").asString()) && e.path("message").asString().contains(approveSql)) {
            String url = e.path("url").asString();
            String id = url.substring(url.lastIndexOf('/') + 1);
            approvals.decide(id, ApprovalService.Decision.APPROVE, "app", null, approvals.find(id).orElseThrow()
                .digest());
          }
        }, 180);
    assertThat(exit).as("mrtr.mjs: %s", events).isZero();
    assertThat(event(events, "connected").path("version").asString()).isEqualTo("2026-07-28");
    assertThat(event(events, "connected").at("/server/name").asString()).isEqualTo("mcp-gateway");
    assertThat(event(events, "tools").path("names").toString()).contains("sql_query", "sql_execute",
        "approval_status");
    assertThat(event(events, "read").at("/result/structuredContent/rowCount").asInt()).isEqualTo(3);

    List<JsonNode> elicitations = events.stream().filter(e -> "elicitation".equals(e.path("event").asString()))
        .toList();
    assertThat(elicitations).hasSize(2).allSatisfy(e -> {
      assertThat(e.path("mode").asString()).isEqualTo("url");
      assertThat(e.path("url").asString()).startsWith(origin + "/approvals/ap_");
    });
    JsonNode approved = event(events, "approved").path("result");
    assertThat(approved.path("isError").asBoolean()).isFalse();
    assertThat(approved.at("/structuredContent/status").asString()).isEqualTo("completed");
    assertThat(approved.at("/content/0/text").asString()).isEqualTo("Approved by you and executed on dev-db (dev): "
        + "1 row updated");
    JsonNode declined = event(events, "declined").path("result");
    assertThat(declined.path("isError").asBoolean()).isTrue();
    assertThat(declined.at("/structuredContent/status").asString()).isEqualTo("rejected");
    assertThat(FakeConnectors.EXECUTIONS.stream().filter(FakeConnectors.Execution::approved).toList()).singleElement()
        .satisfies(e -> assertThat(e.sql()).isEqualTo(approveSql));
    assertThat(core.jdbc().queryForList("select distinct approval_delivery from tool_call where token_label = "
        + "'ts-sdk' and approval_delivery is not null", String.class)).containsExactly("mrtr");
    assertThat(core.jdbc().queryForList("select distinct protocol_version from tool_call where token_label = 'ts-sdk'",
        String.class)).containsExactly("2026-07-28");
  }

  // ── MCP Inspector 2.12.0, both eras ─────────────────────────────────────────────────────────────────────────

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"modern", "legacy"})
  void theInspectorListsAndCallsInBothEras(String era) throws Exception {
    String token = core.bean(TokenService.class).issue("dev", "inspector-" + era, 7, null).token();
    JsonNode list = inspector(era, token, "--method", "tools/list");
    List<String> names = new ArrayList<>();
    list.path("tools").forEach(t -> names.add(t.path("name").asString()));
    assertThat(names).as(list.toString()).contains("sql_query", "sql_execute", "approval_status");

    JsonNode call = inspector(era, token, "--method", "tools/call", "--tool-name", "sql_query", "--tool-arg",
        "connection=dev-db", "--tool-arg", "sql=SELECT 1");
    assertThat(call.path("isError").asBoolean()).as(call.toString()).isFalse();
    assertThat(call.at("/structuredContent/rowCount").asInt()).isEqualTo(3);
    assertThat(core.jdbc().queryForList("select distinct protocol_version from tool_call where token_label = ?",
        String.class, "inspector-" + era)).containsExactly(era.equals("modern") ? "2026-07-28" : "2025-11-25");
  }

  // ── Conformance 0.1.16 through the Bearer proxy ─────────────────────────────────────────────────────────────

  /**
   * The whole active server suite of 2025-11-25 against the endpoint {@code conformance}; the scenarios that can't
   * pass here, and why, are in {@code core/src/test/node/conformance-baseline.yml} (a baseline entry that starts
   * passing fails the run too, so the list stays honest).
   */
  @Test
  void theConformanceSuitePassesBesidesItsBaseline() throws Exception {
    String token = core.bean(TokenService.class).issue("conformance", "conformance", 7, null).token();
    ProcessBuilder pb = new ProcessBuilder(node, script("bearer-proxy.mjs").toString()).redirectErrorStream(true)
        .directory(CLIENTS.toFile());
    pb.environment().put("MCP_TARGET", origin);
    pb.environment().put("MCP_TOKEN", token);
    Process proxy = pb.start();
    try {
      BufferedReader r = new BufferedReader(new InputStreamReader(proxy.getInputStream(), StandardCharsets.UTF_8));
      String first = r.readLine();
      assertThat(first).as("proxy").isNotNull().contains("port");
      int port = McpClient.JSON.readTree(first).path("port").asInt();
      StringBuilder log = new StringBuilder();
      int exit = run(List.of(node, CLIENTS.resolve("node_modules/@modelcontextprotocol/conformance/dist/index.js")
          .toString(), "server", "--url", "http://127.0.0.1:" + port + "/mcp/conformance", "--spec-version",
          "2025-11-25", "--expected-failures", SOURCES.resolve("conformance-baseline.yml").toString(), "-o",
          work.resolve("conformance").toString()), Map.of(), line -> log.append(line).append('\n'), 600);
      System.out.println(log);
      assertThat(exit).as("conformance:%n%s", log).isZero();
      assertThat(log).contains("server-initialize: ").contains("tools-list: ")
          .contains("Baseline check passed: all failures are expected.");
    } finally {
      proxy.destroy();
      proxy.waitFor(5, TimeUnit.SECONDS);
    }
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static JsonNode inspector(String era, String token, String... args) throws Exception {
    List<String> cmd = new ArrayList<>(List.of(node, CLIENTS.resolve(
        "node_modules/@modelcontextprotocol/inspector/clients/launcher/build/index.js").toString(), "--cli",
        origin + "/mcp/dev", "--transport", "http", "--protocol-era", era, "--header", "Authorization: Bearer " + token,
        "--format", "json", "-q"));
    cmd.addAll(List.of(args));
    StringBuilder out = new StringBuilder();
    int exit = run(cmd, Map.of("MCP_CATALOG_PATH", work.resolve("inspector-catalog.json").toString(),
        "MCP_CLIENT_CONFIG_PATH", work.resolve("inspector-client.json").toString()), line -> out.append(line)
        .append('\n'), 120);
    assertThat(exit).as("inspector %s %s:%n%s", era, String.join(" ", args), out).isZero();
    String text = out.toString();
    JsonNode json = McpClient.JSON.readTree(text.substring(text.indexOf('{')));
    // --format json wraps what the method returned in {"result": …}.
    return json.has("result") ? json.get("result") : json;
  }

  private static JsonNode event(List<JsonNode> events, String name) {
    return events.stream().filter(e -> name.equals(e.path("event").asString())).findFirst()
        .orElseThrow(() -> new AssertionError("No " + name + " in " + events));
  }

  /** Runs a command in the clients' directory, feeding each line of stdout and stderr to {@code lines}. */
  private static int run(List<String> command, Map<String, String> env, Consumer<String> lines, int timeoutSeconds)
      throws Exception {
    ProcessBuilder pb = new ProcessBuilder(command).directory(CLIENTS.toFile()).redirectErrorStream(true);
    pb.environment().putAll(env);
    pb.environment().put("NO_COLOR", "1");
    Path bin = Path.of(node).getParent();
    if (bin != null) {
      pb.environment().merge("PATH", bin.toString(), (old, dir) -> dir + java.io.File.pathSeparator + old);
    }
    Process p = pb.start();
    Thread reader = Thread.ofVirtual().start(() -> {
      try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = r.readLine()) != null) {
          if (line.startsWith("{") || !line.isBlank()) {
            try {
              lines.accept(line);
            } catch (RuntimeException e) {
              // A line that isn't an event (a warning) is kept by the caller's log only.
            }
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
    if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new AssertionError("Timed out: " + String.join(" ", command));
    }
    reader.join(5_000);
    return p.exitValue();
  }

  /** The scripts run from the clients' directory, where their imports resolve. */
  private static Path script(String name) throws IOException {
    Path target = CLIENTS.resolve(name);
    Files.copy(SOURCES.resolve(name), target, StandardCopyOption.REPLACE_EXISTING);
    return target;
  }

  /** {@code npm ci} from the committed lock when the cache doesn't hold these exact versions yet. */
  private static void installClients() throws Exception {
    Files.createDirectories(CLIENTS);
    Path lock = SOURCES.resolve("package-lock.json");
    Path installed = CLIENTS.resolve("node_modules/.package-lock.json");
    Path stamp = CLIENTS.resolve(".installed-from");
    String want = Long.toHexString(java.util.Arrays.hashCode(Files.readAllBytes(lock)));
    if (Files.exists(installed) && Files.exists(stamp) && Files.readString(stamp).equals(want)) {
      return;
    }
    Files.copy(SOURCES.resolve("package.json"), CLIENTS.resolve("package.json"), StandardCopyOption.REPLACE_EXISTING);
    Files.copy(lock, CLIENTS.resolve("package-lock.json"), StandardCopyOption.REPLACE_EXISTING);
    Path npm = Path.of(node).resolveSibling("npm");
    StringBuilder log = new StringBuilder();
    int exit = run(List.of(Files.exists(npm) ? npm.toString() : "npm", "ci", "--ignore-scripts", "--no-audit",
        "--no-fund"), Map.of(), line -> log.append(line).append('\n'), 900);
    assertThat(exit).as("npm ci:%n%s", log).isZero();
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
