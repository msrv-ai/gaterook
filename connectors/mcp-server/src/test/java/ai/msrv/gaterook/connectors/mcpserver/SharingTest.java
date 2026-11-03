package ai.msrv.gaterook.connectors.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.support.mcp.McpServerRuntime;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
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
 * or not; images and links of a tool's result as blocks.
 */
class SharingTest {

  private static final String EXPOSED_WELCOME = "mcpgw://connections/raw-mcp/mcp/notes://welcome";
  private static final String APP = "ui://gaterook/connections/raw-mcp/charts/view.html";

  @TempDir
  Path home;

  private McpServers runtime;
  private Fixtures.Secrets secrets;

  @BeforeEach
  void attach() {
    runtime = new McpServers(Map.of("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"),
        "HOME", home.toString()), System.getProperty("os.name"), false, Clock.systemUTC(), Duration.ofMillis(100),
        McpServerConnector.typeManifest());
    runtime.attach(new Fixtures.Host(home));
    secrets = new Fixtures.Secrets().with("/env/0/secretRef", home.resolve("wire.jsonl").toString());
  }

  @AfterEach
  void close() {
    runtime.close();
  }

  private static ConnectionConfig cfg(String share) {
    ObjectNode c = Fixtures.object("{\"transport\":\"stdio\",\"env\":[{\"name\":\"RAW_LOG\"}]"
        + (share.isEmpty() ? "" : ",\"share\":" + share) + "}");
    ArrayNode command = c.putArray("command");
    command.add(Fixtures.javaExecutable());
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(RawMcpServer.class.getName());
    command.add("modern");
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
}
