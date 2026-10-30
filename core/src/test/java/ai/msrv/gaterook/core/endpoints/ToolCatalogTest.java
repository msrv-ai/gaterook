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
}
