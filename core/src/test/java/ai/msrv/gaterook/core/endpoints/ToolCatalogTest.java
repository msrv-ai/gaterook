package ai.msrv.gaterook.core.endpoints;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.policy.PolicyEngine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class ToolCatalogTest {

  @Test
  void sharedToolsPublishWithUnlimitedAndExplicitLogRangesInEitherOrder() {
    Duration day = Duration.ofDays(1);
    for (Duration[] ranges : List.of(new Duration[] {null, null}, new Duration[] {null, day},
        new Duration[] {day, null})) {
      PolicyEngine engine = mock(PolicyEngine.class);
      var input = JsonNodeFactory.instance.objectNode().put("type", "object");
      input.putObject("properties").putObject("connection").put("type", "string");
      ToolDefinition tool = ToolDefinition.builder("logs_search").inputSchema(input)
          .description("Search logs").resultShape(ToolDefinition.ResultShape.LOGS).build();
      ConnectorManifest manifest = ConnectorManifest.builder("logs", "1.0.0", ConnectorManifest.Kind.BUILT_IN)
          .name("Logs").build();
      var connections = new ArrayList<EndpointSnapshot.Wired>();
      var pairs = new ArrayList<EndpointSnapshot.Pair>();
      for (int i = 0; i < ranges.length; i++) {
        String id = "logs-" + i;
        var config = new ConnectionConfig(id, id, "logs", Env.DEV, 1, JsonNodeFactory.instance.objectNode(),
            Set.of(), null);
        Limits limits = new Limits(1000, 1000, 524_288, 1_048_576, 262_144, 65_536, 8192,
            Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(10), ranges[i]);
        when(engine.view(null, config)).thenReturn(new PolicyView(Env.DEV, "dev.logs",
            JsonNodeFactory.instance.objectNode(), limits));
        connections.add(new EndpointSnapshot.Wired(config, manifest, null));
        pairs.add(new EndpointSnapshot.Pair(id, 1, tool.name(), tool, manifest, config, Mode.READ_ONLY,
            Mode.READ_ONLY, null, null, true, null, null, null, List.of(), null));
      }
      var snapshot = new EndpointSnapshot("ep", "dev", "", Env.DEV, false, true, "", null, false,
          false, null, 1, connections, pairs, List.of(), null);

      var published = new ToolCatalog(engine).forEndpoint(snapshot).tool("logs_search").orElseThrow();

      assertThat(published.pairs()).hasSize(2);
      assertThat(published.inputSchema().at("/properties/connection/enum"))
          .extracting(value -> value.asString()).containsExactly("logs-0", "logs-1");
      assertThat(published.description()).contains("Max 1,000 lines.");
    }
  }

  @Test
  void clientsGetNoEnumThatFollowsTheConnectionsWhileTheCoreKeepsEveryValue() {
    PolicyEngine engine = mock(PolicyEngine.class);
    ObjectNode declaredInput = JsonNodeFactory.instance.objectNode().put("type", "object");
    ObjectNode properties = declaredInput.putObject("properties");
    properties.putObject("connection").put("type", "string");
    properties.putObject("scope").put("type", "string");
    properties.putObject("level").put("type", "string").putArray("enum").add("info").add("error");
    ToolDefinition declared = ToolDefinition.builder("gcp_describe").inputSchema(declaredInput)
        .description("Describe").build();
    // What the connector publishes for this endpoint: the scopes of its connections, as an enum.
    ObjectNode endpointInput = declaredInput.deepCopy();
    ((ObjectNode) endpointInput.at("/properties/scope")).putArray("enum").add("projects/a");
    ToolDefinition forEndpoint = ToolDefinition.builder("gcp_describe").inputSchema(endpointInput)
        .description("Describe").build();
    ConnectorManifest manifest = ConnectorManifest.builder("gcp", "1.0.0", ConnectorManifest.Kind.BUILT_IN)
        .name("GCP").tools(List.of(declared)).build();
    var config = new ConnectionConfig("gcp-a", "gcp-a", "gcp", Env.DEV, 1, JsonNodeFactory.instance.objectNode(),
        Set.of(), null);
    Limits limits = new Limits(1000, 1000, 524_288, 1_048_576, 262_144, 65_536, 8192, Duration.ofSeconds(5),
        Duration.ofSeconds(30), Duration.ofSeconds(10), null);
    when(engine.view(null, config)).thenReturn(new PolicyView(Env.DEV, "dev.gcp",
        JsonNodeFactory.instance.objectNode(), limits));
    var pair = new EndpointSnapshot.Pair("gcp-a", 1, "gcp_describe", forEndpoint, manifest, config, Mode.READ_ONLY,
        Mode.READ_ONLY, null, null, true, null, null, null, List.of(), null);
    var snapshot = new EndpointSnapshot("ep", "dev", "", Env.DEV, false, true, "", null, false, false, null, 1,
        List.of(new EndpointSnapshot.Wired(config, manifest, null)), List.of(pair), List.of(), null);

    var published = new ToolCatalog(engine).forEndpoint(snapshot).tool("gcp_describe").orElseThrow();

    assertThat(published.inputSchema().at("/properties/connection/enum").toString()).isEqualTo("[\"gcp-a\"]");
    assertThat(published.inputSchema().at("/properties/scope/enum").toString()).isEqualTo("[\"projects/a\"]");
    ObjectNode client = published.clientInputSchema();
    assertThat(client.at("/properties/connection").toString())
        .isEqualTo("{\"type\":\"string\",\"description\":\"A connection named in this tool's description.\"}");
    assertThat(client.at("/properties/scope").has("enum")).as("the description names the scopes").isFalse();
    assertThat(client.at("/properties/level/enum").toString()).as("the manifest's own")
        .isEqualTo("[\"info\",\"error\"]");
    assertThat(published.description()).contains("Connections: gcp-a (GCP, dev).");
  }

  /**
   * MCP Apps lets an app call the tools of the server that shows it, and to an AI app the endpoint is that server
   * (ARCHITECTURE.md §5.4): only the tools of a connection whose app the endpoint shows may be called by an app.
   */
  @Test
  void onlyTheToolsOfAConnectionWhoseAppIsShownAreForApps() {
    Endpoint e = new Endpoint();
    e.mcp("charts", "chart_view", "{\"ui\":{\"resourceUri\":\"ui://gaterook/connections/charts/view.html\"}}");
    e.mcp("charts", "chart_data", null);
    e.mcp("charts", "chart_helper", "{\"ui\":{\"visibility\":[\"app\"]}}");
    e.mcp("plain", "plain_read", null);
    e.mcp("plain", "plain_both", "{\"ui\":{\"visibility\":[\"model\",\"app\"]}}");
    e.mcp("plain", "plain_helper", "{\"ui\":{\"visibility\":[\"app\"]}}");
    // An app under another connection's namespace is no app of this one: no read reaches it.
    e.mcp("plain", "plain_elsewhere", "{\"ui\":{\"resourceUri\":\"ui://gaterook/connections/charts/view.html\"}}");
    e.builtIn("logs", "logs_search");

    ToolCatalog.Catalog catalog = new ToolCatalog(e.engine).forEndpoint(e.snapshot());

    assertThat(ToolCatalog.appConnections(e.snapshot())).containsExactly("charts");
    assertThat(ui(catalog, "chart_view")).isEqualTo("{\"resourceUri\":\"ui://gaterook/connections/charts/view.html\"}");
    assertThat(ui(catalog, "chart_data")).as("what the server declared: every app of its server may call it")
        .isEqualTo("null");
    assertThat(ui(catalog, "chart_helper")).isEqualTo("{\"visibility\":[\"app\"]}");
    for (String other : List.of("plain_read", "plain_both", "plain_elsewhere", "logs_search")) {
      assertThat(ui(catalog, other)).as(other).isEqualTo("{\"visibility\":[\"model\"]}");
    }
    assertThat(catalog.tool("plain_helper")).as("only an app may call it, and no app of its own is shown").isEmpty();
    assertThat(catalog.problems()).anyMatch(p -> p.startsWith("plain_helper on plain is only for an MCP App"));
    // What a client gets changes; what the connector gave, which the definition hashes cover, doesn't.
    assertThat(catalog.tool("plain_both").orElseThrow().meta().toString())
        .isEqualTo("{\"ui\":{\"visibility\":[\"model\",\"app\"]}}");
    assertThat(catalog.tool("plain_elsewhere").orElseThrow().meta().path("ui").has("resourceUri")).isTrue();
  }

  @Test
  void aConnectionWhoseAppIsOffHasNoToolForApps() {
    Endpoint e = new Endpoint();
    e.mcp("charts", "chart_view", "{\"ui\":{\"resourceUri\":\"ui://gaterook/connections/charts/view.html\"}}");
    e.mcp("charts", "chart_data", null);
    e.mcp("charts", "chart_helper", "{\"ui\":{\"visibility\":[\"app\"]}}");
    e.off("chart_view");

    ToolCatalog.Catalog catalog = new ToolCatalog(e.engine).forEndpoint(e.snapshot());

    assertThat(ToolCatalog.appConnections(e.snapshot())).isEmpty();
    assertThat(catalog.tool("chart_view")).isEmpty();
    assertThat(catalog.tool("chart_helper")).isEmpty();
    assertThat(ui(catalog, "chart_data")).isEqualTo("{\"visibility\":[\"model\"]}");
  }

  private static String ui(ToolCatalog.Catalog catalog, String tool) {
    return String.valueOf(catalog.tool(tool).orElseThrow(() -> new AssertionError(tool + " isn't published"))
        .clientUi());
  }

  /** An endpoint of external MCP servers' and built-in connections, every tool in Read only unless turned off. */
  private static final class Endpoint {
    final PolicyEngine engine = mock(PolicyEngine.class);
    final List<EndpointSnapshot.Wired> connections = new ArrayList<>();
    final List<EndpointSnapshot.Pair> pairs = new ArrayList<>();

    void mcp(String connection, String tool, String meta) {
      pair(connection, ConnectorManifest.Kind.MCP_SERVER, ToolDefinition.builder(tool).description(tool)
          .inputSchema(JsonNodeFactory.instance.objectNode().put("type", "object"))
          .meta(meta == null ? null : tools.jackson.databind.json.JsonMapper.builder().build().readTree(meta))
          .build());
    }

    void builtIn(String connection, String tool) {
      ObjectNode input = JsonNodeFactory.instance.objectNode().put("type", "object");
      input.putObject("properties").putObject("connection").put("type", "string");
      pair(connection, ConnectorManifest.Kind.BUILT_IN, ToolDefinition.builder(tool).description(tool)
          .inputSchema(input).build());
    }

    void off(String tool) {
      pairs.replaceAll(p -> !p.tool().name().equals(tool) ? p : new EndpointSnapshot.Pair(p.rowId(), p.rowVersion(),
          p.toolKey(), p.tool(), p.manifest(), p.config(), Mode.OFF, Mode.OFF, null, null, true, null, null, null,
          List.of(), null));
    }

    private void pair(String connection, ConnectorManifest.Kind kind, ToolDefinition tool) {
      ConnectorManifest manifest = ConnectorManifest.builder(connection + "-type", "1.0.0", kind).name(connection)
          .build();
      ConnectionConfig config = connections.stream().map(EndpointSnapshot.Wired::config)
          .filter(c -> c.name().equals(connection)).findFirst().orElseGet(() -> {
            ConnectionConfig c = new ConnectionConfig(connection, connection, connection + "-type", Env.DEV, 1,
                JsonNodeFactory.instance.objectNode(), Set.of(), null);
            connections.add(new EndpointSnapshot.Wired(c, manifest, null));
            when(engine.view(null, c)).thenReturn(new PolicyView(Env.DEV, "dev." + connection,
                JsonNodeFactory.instance.objectNode(), new Limits(1000, 1000, 524_288, 1_048_576, 262_144, 65_536,
                    8192, Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(10), null)));
            return c;
          });
      pairs.add(new EndpointSnapshot.Pair(connection + "/" + tool.name(), 1, tool.name(), tool, manifest, config,
          Mode.READ_ONLY, Mode.READ_ONLY, null, null, true, null, null, null, List.of(), null));
    }

    EndpointSnapshot snapshot() {
      return new EndpointSnapshot("ep", "dev", "", Env.DEV, false, true, "", null, false, false, null, 1,
          List.copyOf(connections), List.copyOf(pairs), List.of(), null);
    }
  }
}
