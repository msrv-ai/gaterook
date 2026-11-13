package com.gaterook.core.connectors.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gaterook.connector.api.ActionPreview;
import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Connector;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ConnectorManifest;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.connector.api.ToolResult;
import com.gaterook.plugin.protocol.GppJson;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * The stand-in of a connector type that an earlier build compiled in and that arrives as a connector module: the
 * type's tools stay as its row describes them, nothing runs without the connector, a call waits for it within its
 * own time, and everything goes to the connector once it is here.
 */
class AwaitedConnectorTest {

  private static final ConnectionConfig GRAPH = new ConnectionConfig("c-1", "graph-dev", "graph", Env.DEV, 1,
      GppJson.object(), Set.of(), null);
  private static final PolicyView POLICY = new PolicyView(Env.DEV, "dev.graph", GppJson.object(), null);
  private static final ToolCall CALL = new ToolCall("tc_1", "graph_query", "graph-dev", GppJson.object(), null);

  private static ConnectorManifest manifest() {
    ObjectNode input = GppJson.object().put("type", "object");
    return ConnectorManifest.builder("graph", "1.0.0", ConnectorManifest.Kind.BUILT_IN).name("Graph")
        .tools(List.of(ToolDefinition.builder("graph_query").title("Query").description("Reads a graph.")
            .inputSchema(input).build())).build();
  }

  /** What the stand-in is told: the connector, once {@link #connector} is set, and every wait it asked for. */
  private static final class Arrival implements AwaitedConnector.Arrival {
    Connector connector;
    final List<Duration> waits = new ArrayList<>();

    @Override
    public Optional<Connector> await(ConnectionConfig cfg, Duration budget) {
      waits.add(budget);
      return Optional.ofNullable(connector);
    }

    @Override
    public String problem() {
      return "The Graph connector is being downloaded. Try again in a moment.";
    }
  }

  private static Connector real() {
    ConnectorManifest m = manifest();
    return new Connector() {
      @Override
      public ConnectorManifest manifest() {
        return m;
      }

      @Override
      public boolean supportsTool(String tool, ConnectionConfig cfg) {
        return false;
      }

      @Override
      public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
        return Classification.read();
      }

      @Override
      public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
        return ActionPreview.of(ActionPreview.Kind.TEXT, "text", "MATCH (n) RETURN n", cfg.name(), "Reads");
      }

      @Override
      public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
        return TestResult.success(1, "graph 5", null, null);
      }

      @Override
      public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
        return ToolResult.text("rows");
      }
    };
  }

  private static ExecContext context(Duration remaining) {
    ExecContext ctx = mock(ExecContext.class);
    when(ctx.remaining()).thenReturn(remaining);
    return ctx;
  }

  @Test
  void untilTheConnectorIsHereItsToolsStayAndNothingRuns() throws Exception {
    Arrival arrival = new Arrival();
    AwaitedConnector standIn = new AwaitedConnector(manifest(), arrival);

    assertThat(standIn.manifest().tools()).extracting(ToolDefinition::name).containsExactly("graph_query");
    assertThat(standIn.supportsTool("graph_query", GRAPH)).isTrue();
    assertThat(standIn.previewTools(GRAPH)).extracting(ToolDefinition::name).containsExactly("graph_query");
    assertThat(arrival.waits).as("building an endpoint's tools only looks").containsOnly(Duration.ZERO);

    Classification denied = standIn.classify(CALL, GRAPH, POLICY);
    assertThat(denied.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(denied.ruleId()).isEqualTo("dev.graph.connector-unavailable");
    assertThat(denied.reason()).isEqualTo("The Graph connector is being downloaded. Try again in a moment.");

    assertThatThrownBy(() -> standIn.execute(CALL, GRAPH, null, context(Duration.ofSeconds(60))))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE);
          assertThat(e).hasMessageContaining("is being downloaded");
        });
    assertThatThrownBy(() -> standIn.prepare(CALL, GRAPH, null, context(Duration.ofSeconds(60))))
        .isInstanceOf(ConnectorException.class);
    TestResult tested = standIn.test(GRAPH, null, context(Duration.ofSeconds(60)));
    assertThat(tested.ok()).isFalse();
    assertThat(tested.error().message()).contains("is being downloaded");
    assertThat(standIn.health(GRAPH, null, context(Duration.ofSeconds(60))).message()).contains("is being downloaded");
    assertThat(standIn.describeAction(CALL, GRAPH).warnings()).as("never the connector's words without it")
        .anyMatch(w -> w.contains("is being downloaded"));
  }

  @Test
  void aCallWaitsForTheConnectorWithinItsOwnTime() throws Exception {
    Arrival arrival = new Arrival();
    AwaitedConnector standIn = new AwaitedConnector(manifest(), arrival);

    standIn.classify(CALL, GRAPH, POLICY);
    assertThat(arrival.waits).containsExactly(ModuleService.CALL_WAIT);

    arrival.waits.clear();
    assertThatThrownBy(() -> standIn.execute(CALL, GRAPH, null, context(Duration.ofSeconds(3))))
        .isInstanceOf(ConnectorException.class);
    assertThat(arrival.waits).as("not past the call's deadline").containsExactly(Duration.ofSeconds(3));

    arrival.waits.clear();
    standIn.health(GRAPH, null, context(Duration.ofSeconds(60)));
    standIn.suggest(GRAPH, null, context(Duration.ofSeconds(60)), null);
    assertThat(arrival.waits).as("a health check and the form's help never wait").containsOnly(Duration.ZERO);
  }

  @Test
  void onceTheConnectorIsHereEverythingGoesToIt() throws Exception {
    Arrival arrival = new Arrival();
    AwaitedConnector standIn = new AwaitedConnector(manifest(), arrival);
    arrival.connector = real();

    assertThat(standIn.classify(CALL, GRAPH, POLICY).kind()).isEqualTo(Classification.Kind.READ);
    assertThat(standIn.describeAction(CALL, GRAPH).text()).isEqualTo("MATCH (n) RETURN n");
    assertThat(standIn.execute(CALL, GRAPH, null, context(Duration.ofSeconds(60))).content().getFirst().text())
        .isEqualTo("rows");
    assertThat(standIn.test(GRAPH, null, context(Duration.ofSeconds(60))).ok()).isTrue();
    assertThat(standIn.supportsTool("graph_query", GRAPH)).as("the connector's own answer").isFalse();
  }
}
