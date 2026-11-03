package ai.msrv.gaterook.connectors.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.support.mcp.McpServerRuntime;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The client against servers of the 2026-07-28 revision and of both eras ({@link RawMcpServer} in a child JVM): a new
 * server is asked {@code server/discover} first, every request carries the envelope, {@code input_required} rounds
 * that only carry state go again, a request that asks the person something fails, a call the gateway gives up is
 * cancelled at the server, tool changes arrive on a {@code subscriptions/listen} stream, a stored server is spoken to
 * in the era it was reviewed in, and a tool of 2025-11-25 that requires a task runs as one.
 */
class ModernServersTest {

  @TempDir
  Path home;

  private Fixtures.Host host;
  private McpServers runtime;
  private Path wire;
  private Fixtures.Secrets secrets;

  @BeforeEach
  void attach() {
    host = new Fixtures.Host(home);
    runtime = new McpServers(Map.of("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"),
        "HOME", home.toString()), System.getProperty("os.name"), false, Clock.systemUTC(), Duration.ofMillis(100),
        McpServerConnector.typeManifest());
    runtime.attach(host);
    wire = home.resolve("wire.jsonl");
    secrets = new Fixtures.Secrets().with("/env/0/secretRef", wire.toString());
  }

  @AfterEach
  void close() {
    runtime.close();
  }

  private static ConnectionConfig cfg(String mode, String extra) {
    ObjectNode c = Fixtures.object("{\"transport\":\"stdio\",\"env\":[{\"name\":\"RAW_LOG\"}]"
        + (extra.isEmpty() ? "" : "," + extra) + "}");
    ArrayNode command = c.putArray("command");
    command.add(Fixtures.javaExecutable());
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(RawMcpServer.class.getName());
    command.add(mode);
    return Fixtures.cfg("cn_raw", "raw-mcp", Env.DEV, 1, c);
  }

  /** What the client wrote to the server's stdin, one message a line. */
  private List<JsonNode> sent() throws IOException {
    List<JsonNode> out = new ArrayList<>();
    if (Files.exists(wire)) {
      for (String line : Files.readAllLines(wire)) {
        out.add(Texts.JSON.readTree(line));
      }
    }
    return out;
  }

  private List<String> methods() throws IOException {
    return sent().stream().map(m -> m.path("method").asString("")).toList();
  }

  private static ToolResult approved(Connector c, ConnectionConfig cfg, String tool, String args,
      Fixtures.Secrets secrets, Duration timeout) throws ConnectorException {
    ActionPreview preview = c.describeAction(Fixtures.call(tool, args), cfg);
    return c.execute(Fixtures.call(tool, args), cfg, secrets, Fixtures.Context.approved(preview.payload(), timeout));
  }

  private static void await(BooleanSupplier condition, Duration within) throws InterruptedException {
    long end = System.nanoTime() + within.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > end) {
        throw new AssertionError("Condition not met in " + within);
      }
      Thread.sleep(50);
    }
  }

  @Test
  void aServerOf2026IsDiscoveredAndEveryRequestCarriesTheEnvelope() throws Exception {
    ConnectionConfig cfg = cfg("modern", "");
    McpServerRuntime.Handshake h = runtime.probe(cfg, secrets, Fixtures.Context.of(Duration.ofSeconds(60)));

    assertThat(h.protocolVersion()).isEqualTo("2026-07-28");
    assertThat(h.serverVersion()).isEqualTo("9.9.9");
    assertThat(h.instructions()).isEqualTo("Modern instructions.");
    assertThat(h.tools()).extracting(t -> t.path("exposed").asString()).contains("raw_echo", "raw_meta");
    assertThat(methods()).startsWith("server/discover", "tools/list").doesNotContain("initialize",
        "notifications/initialized");
    for (JsonNode m : sent()) {
      JsonNode meta = m.path("params").path("_meta");
      assertThat(meta.path("io.modelcontextprotocol/protocolVersion").asString()).isEqualTo("2026-07-28");
      assertThat(meta.path("io.modelcontextprotocol/clientCapabilities").isObject()).isTrue();
      assertThat(meta.path("io.modelcontextprotocol/clientCapabilities").isEmpty()).isTrue();
      assertThat(meta.path("io.modelcontextprotocol/clientInfo").path("name").asString()).isEqualTo("gaterook");
    }

    McpServerRuntime.Snapshot snapshot = new McpServerRuntime.Snapshot("raw_", h.serverVersion(),
        h.protocolVersion(), h.tools(), null);
    Connector c = runtime.connector(cfg, snapshot);
    String meta = approved(c, cfg, "raw_meta", "{}", secrets, Duration.ofSeconds(30)).content().getFirst().text();
    assertThat(Texts.JSON.readTree(meta).path("io.modelcontextprotocol/protocolVersion").asString())
        .isEqualTo("2026-07-28");
    assertThat(c.execute(Fixtures.call("raw_echo", "{\"text\":\"hi\"}"), cfg, secrets,
        Fixtures.Context.of(Duration.ofSeconds(30))).content().getFirst().text()).isEqualTo("hi");
  }

  @Test
  void roundsThatOnlyCarryStateGoAgainAndAQuestionForThePersonFails() throws Exception {
    ConnectionConfig cfg = cfg("modern", "");
    McpServerRuntime.Handshake h = runtime.probe(cfg, secrets, Fixtures.Context.of(Duration.ofSeconds(60)));
    Connector c = runtime.connector(cfg, new McpServerRuntime.Snapshot("raw_", h.serverVersion(), h.protocolVersion(),
        h.tools(), null));

    ToolResult r = approved(c, cfg, "raw_rounds", "{}", secrets, Duration.ofSeconds(30));

    assertThat(r.content().getFirst().text()).isEqualTo("rounds done after 3");
    List<String> states = sent().stream().filter(m -> m.path("params").path("name").asString("").equals("rounds"))
        .map(m -> m.path("params").path("requestState").asString("")).toList();
    assertThat(states).containsExactly("", "r1", "r2");
    List<Long> ids = sent().stream().filter(m -> m.path("params").path("name").asString("").equals("rounds"))
        .map(m -> m.path("id").asLong()).toList();
    assertThat(ids).doesNotHaveDuplicates();

    assertThatThrownBy(() -> approved(c, cfg, "raw_asks", "{}", secrets, Duration.ofSeconds(30)))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
          assertThat(e.getMessage()).contains("asked for input GateRook doesn't give (elicitation/create)");
        });
  }

  @Test
  void aCallTheGatewayGivesUpIsCancelledAtTheServer() throws Exception {
    ConnectionConfig cfg = cfg("modern", "\"callTimeoutSeconds\":1");
    McpServerRuntime.Handshake h = runtime.probe(cfg, secrets, Fixtures.Context.of(Duration.ofSeconds(60)));
    Connector c = runtime.connector(cfg, new McpServerRuntime.Snapshot("raw_", h.serverVersion(), h.protocolVersion(),
        h.tools(), null));

    assertThatThrownBy(() -> approved(c, cfg, "raw_slow", "{\"ms\":4000}", secrets, Duration.ofSeconds(30)))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN));

    long call = sent().stream().filter(m -> m.path("params").path("name").asString("").equals("slow"))
        .findFirst().orElseThrow().path("id").asLong();
    await(() -> {
      try {
        return sent().stream().anyMatch(m -> m.path("method").asString("").equals("notifications/cancelled")
            && m.path("params").path("requestId").asLong(-1) == call);
      } catch (IOException e) {
        return false;
      }
    }, Duration.ofSeconds(10));
  }

  @Test
  void toolChangesArriveOnAListenStream() throws Exception {
    ConnectionConfig cfg = cfg("modern", "");
    McpServerRuntime.Handshake h = runtime.probe(cfg, secrets, Fixtures.Context.of(Duration.ofSeconds(60)));
    Connector c = runtime.connector(cfg, new McpServerRuntime.Snapshot("raw_", h.serverVersion(), h.protocolVersion(),
        h.tools(), null));
    c.execute(Fixtures.call("raw_echo", "{\"text\":\"x\"}"), cfg, secrets, Fixtures.Context.of(Duration.ofSeconds(30)));
    await(() -> {
      try {
        return methods().contains("subscriptions/listen");
      } catch (IOException e) {
        return false;
      }
    }, Duration.ofSeconds(10));
    JsonNode listen = sent().stream().filter(m -> m.path("method").asString("").equals("subscriptions/listen"))
        .findFirst().orElseThrow();
    assertThat(listen.path("params").path("notifications").path("toolsListChanged").asBoolean()).isTrue();
    int before = host.handshakes.size();

    approved(c, cfg, "raw_change", "{}", secrets, Duration.ofSeconds(30));

    await(() -> host.handshakes.size() > before, Duration.ofSeconds(10));
    JsonNode echo = Snapshots.find(host.handshakes.getLast().tools(), "raw_echo").orElseThrow();
    assertThat(echo.path("description").asString()).contains("sends it elsewhere");
  }

  @Test
  void aServerOfBothErasSpeaks2026AndAStoredServerSpeaksTheEraOfItsReview() throws Exception {
    ConnectionConfig cfg = cfg("dual", "");
    McpServerRuntime.Handshake h = runtime.probe(cfg, secrets, Fixtures.Context.of(Duration.ofSeconds(60)));
    assertThat(h.protocolVersion()).isEqualTo("2026-07-28");

    Files.deleteIfExists(wire);
    McpServerRuntime.Snapshot legacy = new McpServerRuntime.Snapshot("raw_", "9.9.9-legacy", "2025-11-25",
        h.tools(), null);
    Connector c = runtime.connector(cfg, legacy);
    String meta = approved(c, cfg, "raw_meta", "{}", secrets, Duration.ofSeconds(30)).content().getFirst().text();

    assertThat(methods()).startsWith("initialize", "notifications/initialized").doesNotContain("server/discover");
    assertThat(Texts.JSON.readTree(meta).has("io.modelcontextprotocol/protocolVersion")).isFalse();
  }

  @Test
  void aToolThatRequiresATaskRunsAsOne() throws Exception {
    ConnectionConfig cfg = cfg("tasks", "");
    McpServerRuntime.Handshake h = runtime.probe(cfg, secrets, Fixtures.Context.of(Duration.ofSeconds(60)));
    assertThat(h.protocolVersion()).isEqualTo("2025-11-25");
    JsonNode job = Snapshots.find(h.tools(), "raw_job").orElseThrow();
    assertThat(job.path("taskSupport").asString()).isEqualTo("required");
    Connector c = runtime.connector(cfg, new McpServerRuntime.Snapshot("raw_", h.serverVersion(), h.protocolVersion(),
        h.tools(), null));

    ToolResult r = approved(c, cfg, "raw_job", "{}", secrets, Duration.ofSeconds(30));

    assertThat(r.content().getFirst().text()).isEqualTo("task done after polls");
    JsonNode call = sent().stream().filter(m -> m.path("params").path("name").asString("").equals("job"))
        .findFirst().orElseThrow();
    assertThat(call.path("params").path("task").path("ttl").asLong()).isPositive();
    assertThat(methods()).contains("tasks/get", "tasks/result");
  }

  @Test
  void progressAndLogMessagesOfACallReachItsClientInBothEras() throws Exception {
    for (String mode : new String[] {"modern", "dual"}) {
      ConnectionConfig cfg = cfg(mode, "");
      McpServerRuntime.Handshake h = runtime.probe(cfg, secrets, Fixtures.Context.of(Duration.ofSeconds(60)));
      // The dual server reviewed in 2025-11-25: its calls speak that era.
      String version = mode.equals("dual") ? "2025-11-25" : h.protocolVersion();
      Connector c = runtime.connector(cfg, new McpServerRuntime.Snapshot("raw_", h.serverVersion(), version,
          h.tools(), null));
      Fixtures.Context ctx = Fixtures.Context.of(Duration.ofSeconds(30)).listening("info");

      ToolResult r = c.execute(Fixtures.call("raw_steps", "{}"), cfg, secrets, ctx);

      assertThat(r.content().getFirst().text()).isEqualTo("three steps");
      assertThat(ctx.heard).as(mode).contains("1.0/3.0 step 1", "2.0/3.0 step 2", "3.0/3.0 step 3",
          "warning steps: almost done");
      JsonNode call = sent().stream().filter(m -> m.path("params").path("name").asString("").equals("steps"))
          .reduce((a, b) -> b).orElseThrow();
      assertThat(call.path("params").path("_meta").path("progressToken").isMissingNode()).isFalse();
      if (mode.equals("modern")) {
        assertThat(call.path("params").path("_meta").path("io.modelcontextprotocol/logLevel").asString())
            .isEqualTo("info");
      }
      runtime.stop("cn_raw");
      Files.deleteIfExists(wire);
    }
  }
}
