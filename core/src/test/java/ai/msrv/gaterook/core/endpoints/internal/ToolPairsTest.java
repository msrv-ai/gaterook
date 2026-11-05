package ai.msrv.gaterook.core.endpoints.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.EndpointView;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.EnvironmentPolicies;
import ai.msrv.gaterook.core.policy.internal.LimitsOf;
import ai.msrv.gaterook.core.templates.TemplateService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.node.JsonNodeFactory;

/** Backend-specific tools keep a shared definition but only appear on connections that support them. */
class ToolPairsTest {

  private final ConnectorRegistry registry = mock(ConnectorRegistry.class);
  private final TemplateService templates = mock(TemplateService.class);
  private final EnvironmentPolicies policies = mock(EnvironmentPolicies.class);
  private final ToolPairs pairs = new ToolPairs(registry, templates, policies);
  private final ToolDefinition shared = ToolDefinition.builder("logs_search").build();
  private final ToolDefinition specific = ToolDefinition.builder("opensearch_request").build();
  private final ConnectorManifest manifest = ConnectorManifest.builder("logs", "1.0.0",
      ConnectorManifest.Kind.BUILT_IN).tools(List.of(shared, specific)).build();

  @BeforeEach
  void environmentPolicy() {
    when(policies.view(Env.DEV, manifest)).thenReturn(new PolicyView(Env.DEV, "dev.logs",
        JsonNodeFactory.instance.objectNode(), LimitsOf.from(JsonNodeFactory.instance.objectNode(), Env.DEV)));
  }

  @Test
  void backendSpecificToolsOnlyPairWithSupportedConnections() {
    ConnectionConfig search = connection("search", "opensearch");
    ConnectionConfig loki = connection("loki", "loki");
    Connector connector = connector();
    when(connector.supportsTool(specific.name(), loki)).thenReturn(false);
    List<ToolPairs.Member> members = List.of(member(search), member(loki));

    var offered = pairs.offered(members, "dev", Env.DEV);

    assertThat(offered.get(search.id())).extracting(ToolPairs.Offered::toolKey)
        .containsExactly("logs_search", "opensearch_request");
    assertThat(offered.get(loki.id())).extracting(ToolPairs.Offered::toolKey).containsExactly("logs_search");
    assertThat(offered.get(search.id()).getFirst().definition())
        .isSameAs(offered.get(loki.id()).getFirst().definition());
    ArgumentCaptor<EndpointView> view = ArgumentCaptor.forClass(EndpointView.class);
    verify(connector, times(1)).tools(view.capture());
    assertThat(view.getValue().connections()).containsExactly(search, loki);
    assertThat(view.getValue().limitsByConnection().get(search.id())).isSameAs(policies.view(Env.DEV, manifest).limits());

    assertThat(pairs.offeredFor(loki, members, "dev", Env.DEV))
        .extracting(ToolPairs.Offered::toolKey).containsExactly("logs_search");
  }

  @Test
  void connectorsWithoutAnApplicabilityOverrideKeepAllTheirTools() {
    connector();
    ConnectionConfig first = connection("first", "one");
    ConnectionConfig second = connection("second", "two");

    var offered = pairs.offered(List.of(member(first), member(second)), "dev", Env.DEV);

    assertThat(offered.get(first.id())).extracting(ToolPairs.Offered::toolKey)
        .containsExactly("logs_search", "opensearch_request");
    assertThat(offered.get(second.id())).extracting(ToolPairs.Offered::toolKey)
        .containsExactly("logs_search", "opensearch_request");
  }

  @Test
  void unavailableConnectorStillOffersItsManifestTools() {
    when(registry.connectorFor(any())).thenReturn(Optional.empty());
    ConnectionConfig cfg = connection("offline", "one");

    assertThat(pairs.offered(List.of(member(cfg)), "dev", Env.DEV).get(cfg.id()))
        .extracting(ToolPairs.Offered::toolKey).containsExactly("logs_search", "opensearch_request");
  }

  @Test
  void mcpDefaultsUseExplicitReadDeclarationsInEveryEnvironmentWhilePluginsStillRequireConfirmation() {
    ToolDefinition read = ToolDefinition.builder("search").defaultMode(Mode.APPROVAL, Mode.APPROVAL).build();
    ToolDefinition undeclared = ToolDefinition.builder("get_status")
        .annotations(new ToolDefinition.Annotations(null, null, null, null)).build();
    ToolDefinition conflicting = ToolDefinition.builder("get_and_delete")
        .annotations(new ToolDefinition.Annotations(true, true, null, null)).build();
    ToolPairs.Offered mcp = external(read, ConnectorManifest.Kind.MCP_SERVER);
    ToolPairs.Offered plugin = external(read, ConnectorManifest.Kind.PLUGIN);

    for (Env env : Env.values()) {
      assertThat(ToolPairs.initial(mcp, env, false)).isEqualTo(Mode.READ_ONLY);
      assertThat(ToolPairs.initial(mcp, env, true)).isEqualTo(Mode.READ_ONLY);
      assertThat(ToolPairs.initial(plugin, env, false)).isEqualTo(Mode.APPROVAL);
      assertThat(ToolPairs.initial(plugin, env, true)).isEqualTo(Mode.OFF);
      for (ToolDefinition other : List.of(undeclared, conflicting)) {
        assertThat(ToolPairs.initial(external(other, ConnectorManifest.Kind.MCP_SERVER), env, false))
            .isEqualTo(Mode.APPROVAL);
        assertThat(ToolPairs.initial(external(other, ConnectorManifest.Kind.MCP_SERVER), env, true))
            .isEqualTo(Mode.OFF);
      }
    }
    assertThat(ToolPairs.refusal(mcp, Mode.READ_ONLY, false, false, "dev")).isEmpty();
    assertThat(ToolPairs.refusal(plugin, Mode.READ_ONLY, false, false, "dev")).hasValueSatisfying(
        reason -> assertThat(reason).contains("Confirm"));
  }

  private static ToolPairs.Offered external(ToolDefinition tool, ConnectorManifest.Kind kind) {
    return new ToolPairs.Offered("external:" + tool.name(), tool, kind, "test-server", "1.0.0", null, null);
  }

  private Connector connector() {
    Connector connector = mock(Connector.class, CALLS_REAL_METHODS);
    when(connector.manifest()).thenReturn(manifest);
    when(registry.connectorFor(any())).thenReturn(Optional.of(connector));
    return connector;
  }

  private ToolPairs.Member member(ConnectionConfig cfg) {
    return new ToolPairs.Member(cfg, manifest, true);
  }

  private static ConnectionConfig connection(String id, String backend) {
    return new ConnectionConfig(id, id, "logs", Env.DEV, 1,
        JsonNodeFactory.instance.objectNode().put("backend", backend), Set.of(), null);
  }
}
