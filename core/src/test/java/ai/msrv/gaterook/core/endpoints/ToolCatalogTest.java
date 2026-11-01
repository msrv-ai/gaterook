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
}
