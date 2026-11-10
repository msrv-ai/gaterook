package ai.msrv.gaterook.connectors.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolDefinition;
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
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a server shares besides tools ({@link RawMcpServer}, 2026-07-28): its resources, templates and prompts under the
 * connection's namespace and prefix, only when the person lets it share them; the app a reviewed tool names, whether
 * or not; images and links of a tool's result as blocks; the updates of the resources clients subscribe to, and its
 * changed lists, in both eras.
 */
class SharingTest {

  private static final String EXPOSED_WELCOME = "mcpgw://connections/raw-mcp/mcp/notes://welcome";
  private static final String APP = "ui://gaterook/connections/raw-mcp/charts/view.html";

  @TempDir
  Path home;

  private McpServers runtime;
  private Fixtures.Host host;
  private Path wire;
  private Fixtures.Secrets secrets;

  @BeforeEach
  void attach() {
    runtime = new McpServers(Map.of("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"),
        "HOME", home.toString()), System.getProperty("os.name"), false, Clock.systemUTC(), Duration.ofMillis(100),
        McpServerConnector.typeManifest());
    host = new Fixtures.Host(home);
    runtime.attach(host);
    wire = home.resolve("wire.jsonl");
    secrets = new Fixtures.Secrets().with("/env/0/secretRef", wire.toString());
  }

  @AfterEach
  void close() {
    runtime.close();
  }

  private static ConnectionConfig cfg(String share) {
    return cfg("modern", share);
  }

  private static ConnectionConfig cfg(String mode, String share) {
    ObjectNode c = Fixtures.object("{\"transport\":\"stdio\",\"env\":[{\"name\":\"RAW_LOG\"}]"
        + (share.isEmpty() ? "" : ",\"share\":" + share) + "}");
    ArrayNode command = c.putArray("command");
    command.add(Fixtures.javaExecutable());
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(RawMcpServer.class.getName());
    command.add(mode);
    return Fixtures.cfg("cn_raw", "raw-mcp", Env.DEV, 1, c);
  }

  private Connector connector(ConnectionConfig cfg) throws ConnectorException {
    McpServerRuntime.Handshake h = runtime.probe(cfg, secrets, Fixtures.Context.of(Duration.ofSeconds(60)));
    return runtime.connector(cfg, new McpServerRuntime.Snapshot("raw_", h.serverVersion(), h.protocolVersion(),
        h.tools(), null));
  }

  private Fixtures.Context ctx() {
    return Fixtures.Context.of(Duration.ofSeconds(30));
  }

  /** What the client wrote to the server's stdin, one message a line. */
  private List<JsonNode> sent() {
    List<JsonNode> out = new ArrayList<>();
    try {
      if (Files.exists(wire)) {
        for (String line : Files.readAllLines(wire)) {
          out.add(Texts.JSON.readTree(line));
        }
      }
    } catch (IOException e) {
      // Being written: the next look reads it.
    }
    return out;
  }

  private List<JsonNode> sent(String method) {
    return sent().stream().filter(m -> m.path("method").asString("").equals(method)).toList();
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > end) {
        throw new AssertionError("Condition not met in 10 s");
      }
      Thread.sleep(50);
    }
  }

  @Test
  void theReviewSaysWhatTheServerOffersBesidesTools() throws Exception {
    McpServerRuntime.Handshake h = runtime.probe(cfg(""), secrets, ctx());

    assertThat(h.notes()).anyMatch(n -> n.startsWith("Offers resources and prompts besides tools"))
        .anyMatch(n -> n.startsWith("1 tool shows an MCP App"));
    assertThat(Snapshots.find(h.tools(), "raw_chart").orElseThrow().path("ui").path("resourceUri").asString())
        .isEqualTo("ui://charts/view.html");
  }

  @Test
  void sharedResourcesAndTemplatesAreUnderTheConnectionsNamespace() throws Exception {
    ConnectionConfig cfg = cfg("{\"resources\":true}");
    Connector c = connector(cfg);

    assertThat(c.sharesResources(cfg)).isTrue();
    assertThat(c.sharesPrompts(cfg)).isFalse();
    JsonNode list = c.listResources(cfg, secrets, ctx(), false);
    assertThat(list.path("resources")).extracting(r -> r.path("uri").asString())
        .containsExactly(EXPOSED_WELCOME, APP, "mcpgw://connections/raw-mcp/mcp/bin://logo");
    // What the server says about a resource is cleaned like a tool's description.
    assertThat(list.path("resources").get(0).path("description").asString()).isEqualTo("The first note.");
    JsonNode templates = c.listResources(cfg, secrets, ctx(), true);
    assertThat(templates.path("resourceTemplates").get(0).path("uriTemplate").asString())
        .isEqualTo("mcpgw://connections/raw-mcp/mcp/notes://{name}");

    ToolResult welcome = c.readResource(EXPOSED_WELCOME, cfg, secrets, ctx());
    assertThat(welcome.content()).singleElement().satisfies(b -> {
      assertThat(b.type()).isEqualTo("resource");
      assertThat(b.uri()).isEqualTo(EXPOSED_WELCOME);
      assertThat(b.text()).isEqualTo("Hello");
    });
    ToolResult fromTemplate = c.readResource("mcpgw://connections/raw-mcp/mcp/notes://todo", cfg, secrets, ctx());
    assertThat(fromTemplate.content().getFirst().text()).isEqualTo("Note todo");
    ToolResult logo = c.readResource("mcpgw://connections/raw-mcp/mcp/bin://logo", cfg, secrets, ctx());
    assertThat(logo.content().getFirst().data()).isEqualTo(RawMcpServer.PNG);
    assertThatThrownBy(() -> c.readResource("mcpgw://connections/other/mcp/notes://welcome", cfg, secrets, ctx()))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT));
  }

  @Test
  void aServerThatDoesntShareListsNothingButItsReviewedAppsRead() throws Exception {
    ConnectionConfig cfg = cfg("");
    Connector c = connector(cfg);

    assertThat(c.listResources(cfg, secrets, ctx(), false)).isNull();
    assertThatThrownBy(() -> c.readResource(EXPOSED_WELCOME, cfg, secrets, ctx()))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    ToolResult app = c.readResource(APP, cfg, secrets, ctx());
    assertThat(app.content().getFirst().mimeType()).isEqualTo("text/html;profile=mcp-app");
    assertThat(app.content().getFirst().meta().path("ui").path("csp").path("connectDomains").get(0).asString())
        .isEqualTo("https://charts.example");
    // Only its policy's origins and its border reach the AI app: no permission, no origin of its own, no wildcard.
    assertThat(app.content().getFirst().meta().toString())
        .isEqualTo("{\"ui\":{\"csp\":{\"connectDomains\":[\"https://charts.example\"]},\"prefersBorder\":true}}");
    ToolDefinition chart = c.manifest().tools().stream().filter(t -> t.name().equals("raw_chart")).findFirst()
        .orElseThrow();
    assertThat(chart.meta().path("ui").path("resourceUri").asString()).isEqualTo(APP);
    assertThatThrownBy(() -> c.readResource("ui://gaterook/connections/raw-mcp/other.html", cfg, secrets, ctx()))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
  }

  @Test
  void sharedPromptsAreUnderThePrefixAndCompleteThere() throws Exception {
    ConnectionConfig cfg = cfg("{\"prompts\":true,\"resources\":true}");
    Connector c = connector(cfg);

    JsonNode prompts = c.listPrompts(cfg, secrets, ctx());
    assertThat(prompts.path("prompts")).extracting(p -> p.path("name").asString()).containsExactly("raw_summarize");
    JsonNode got = c.getPrompt("raw_summarize", Fixtures.object("{\"note\":\"welcome\"}"), cfg, secrets, ctx());
    assertThat(got.path("description").asString()).isEqualTo("Summary of welcome");
    assertThat(got.path("messages").get(0).path("content").path("text").asString())
        .isEqualTo("Summarize the note welcome.");
    assertThat(got.path("messages").get(1).path("content").path("resource").path("uri").asString())
        .isEqualTo(EXPOSED_WELCOME);

    JsonNode completed = c.complete(Fixtures.object("""
        {"ref":{"type":"ref/prompt","name":"raw_summarize"},"argument":{"name":"note","value":"we"}}"""), cfg,
        secrets, ctx());
    assertThat(completed.path("completion").path("values")).extracting(JsonNode::asString)
        .containsExactly("welcome", "weekly");
    JsonNode byTemplate = c.complete(Fixtures.object("""
        {"ref":{"type":"ref/resource","uri":"mcpgw://connections/raw-mcp/mcp/notes://{name}"},
         "argument":{"name":"name","value":"w"}}"""), cfg, secrets, ctx());
    assertThat(byTemplate.path("completion").path("values")).extracting(JsonNode::asString)
        .containsExactly("welcome");
  }

  /**
   * The hash of what a server declares for a tool doesn't cover the app it shows, so the app is pinned beside it: a
   * server that moves the app of a reviewed tool stops the tool until the core took the list in, and an approval
   * prepared for the old app doesn't run against the new one.
   */
  @Test
  void anAppMovedAfterTheReviewStopsTheToolAndAnApprovalOfTheOldOneDoesntRun() throws Exception {
    ConnectionConfig cfg = cfg("dual", "");
    McpServerRuntime.Handshake h = runtime.probe(cfg, secrets, ctx());
    // Reviewed in 2025-11-25: its session speaks that era, which tells a changed list as a notification.
    Connector reviewed = runtime.connector(cfg, new McpServerRuntime.Snapshot("raw_", h.serverVersion(),
        "2025-11-25", h.tools(), null));
    JsonNode before = Snapshots.find(h.tools(), "raw_chart").orElseThrow();
    ActionPreview pinned = reviewed.describeAction(Fixtures.call("raw_chart", "{}"), cfg);
    assertThat(pinned.payload().path("ui").path("resourceUri").asString()).isEqualTo("ui://charts/view.html");
    assertThat(reviewed.execute(Fixtures.call("raw_chart", "{}"), cfg, secrets, ctx()).content()).isNotEmpty();

    int announced = host.handshakes.size();
    ActionPreview change = reviewed.describeAction(Fixtures.call("raw_change", "{}"), cfg);
    reviewed.execute(Fixtures.call("raw_change", "{}"), cfg, secrets, Fixtures.Context.approved(change.payload()));
    await(() -> host.handshakes.size() > announced);

    JsonNode after = Snapshots.find(host.handshakes.getLast().tools(), "raw_chart").orElseThrow();
    assertThat(after.path("ui").path("resourceUri").asString()).isEqualTo("ui://charts/moved.html");
    assertThat(after.path("definitionHash").asString()).as("what it declares for the chart is the same")
        .isEqualTo(before.path("definitionHash").asString());
    assertThatThrownBy(() -> reviewed.execute(Fixtures.call("raw_chart", "{}"), cfg, secrets, ctx()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).contains("changed chart since it was reviewed");
        });

    // The core took the new list in: a call prepared before it is another call than the one that would run now.
    Connector taken = runtime.connector(cfg, new McpServerRuntime.Snapshot("raw_", h.serverVersion(), "2025-11-25",
        host.handshakes.getLast().tools(), null));
    assertThatThrownBy(() -> taken.execute(Fixtures.call("raw_chart", "{}"), cfg, secrets,
        Fixtures.Context.approved(pinned.payload())))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).contains("changed chart after the call was approved");
        });
    ActionPreview now = taken.describeAction(Fixtures.call("raw_chart", "{}"), cfg);
    assertThat(now.payload().path("ui").path("resourceUri").asString()).isEqualTo("ui://charts/moved.html");
    assertThat(taken.execute(Fixtures.call("raw_chart", "{}"), cfg, secrets,
        Fixtures.Context.approved(now.payload())).content()).isNotEmpty();
    // A payload prepared before approvals named the app has no "ui" at all, and still runs against an unchanged one.
    ObjectNode older = (ObjectNode) now.payload().deepCopy();
    older.remove("ui");
    assertThat(taken.execute(Fixtures.call("raw_chart", "{}"), cfg, secrets, Fixtures.Context.approved(older))
        .content()).isNotEmpty();
  }

  @Test
  void imagesAndLinksOfAResultAreBlocks() throws Exception {
    ConnectionConfig cfg = cfg("{\"resources\":true}");
    Connector c = connector(cfg);

    ToolResult r = c.execute(Fixtures.call("raw_picture", "{}"), cfg, secrets, ctx());

    assertThat(r.content().get(0).type()).isEqualTo("image");
    assertThat(r.content().get(0).data()).isEqualTo(RawMcpServer.PNG);
    assertThat(r.content().get(0).mimeType()).isEqualTo("image/png");
    assertThat(r.content().get(1).type()).isEqualTo("resource_link");
    assertThat(r.content().get(1).uri()).isEqualTo(EXPOSED_WELCOME);
  }

  @Test
  void subscribedResourcesTellTheirUpdatesInBothEras() throws Exception {
    for (String mode : new String[] {"modern", "dual"}) {
      host.notices.clear();
      Files.deleteIfExists(wire);
      ConnectionConfig cfg = cfg(mode, "{\"resources\":true}");
      McpServerRuntime.Handshake h = runtime.probe(cfg, secrets, ctx());
      // The dual server reviewed in 2025-11-25: its session speaks that era.
      String version = mode.equals("dual") ? "2025-11-25" : h.protocolVersion();
      Connector c = runtime.connector(cfg, new McpServerRuntime.Snapshot("raw_", h.serverVersion(), version,
          h.tools(), null));
      // Clients subscribe before the session runs: it hears them when it opens. Another server's URI isn't its own.
      c.subscriptions(cfg, Set.of(EXPOSED_WELCOME, "mcpgw://connections/other/mcp/notes://welcome"));
      c.execute(Fixtures.call("raw_echo", "{\"text\":\"x\"}"), cfg, secrets, ctx());
      if (mode.equals("modern")) {
        await(() -> sent("subscriptions/listen").stream().anyMatch(m -> m.path("params").path("notifications")
            .path("resourceSubscriptions").toString().equals("[\"notes://welcome\"]")));
        assertThat(sent("resources/subscribe")).as(mode).isEmpty();
      } else {
        await(() -> !sent("resources/subscribe").isEmpty());
        assertThat(sent("resources/subscribe")).as(mode).singleElement()
            .satisfies(m -> assertThat(m.path("params").path("uri").asString()).isEqualTo("notes://welcome"));
      }

      // The server may take its stream a moment after the request arrives: ask until it tells.
      for (int i = 0; i < 20 && !host.notices.contains("updated " + EXPOSED_WELCOME); i++) {
        assertThat(c.execute(Fixtures.call("raw_touch", "{}"), cfg, secrets, ctx()).content().getFirst().text())
            .isEqualTo("touched");
        Thread.sleep(250);
      }
      assertThat(host.notices).as(mode).contains("updated " + EXPOSED_WELCOME, "resources");

      c.subscriptions(cfg, Set.of());
      if (mode.equals("modern")) {
        await(() -> sent("subscriptions/listen").getLast().path("params").path("notifications")
            .path("resourceSubscriptions").isMissingNode());
      } else {
        await(() -> sent("resources/unsubscribe").size() == 1);
      }
      runtime.stop("cn_raw");
    }
  }
}
