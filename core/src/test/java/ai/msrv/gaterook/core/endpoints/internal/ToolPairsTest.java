package ai.msrv.gaterook.core.endpoints.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import ai.msrv.gaterook.core.connectors.ManifestCodec;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointAccess;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointToolEntity;
import ai.msrv.gaterook.core.platform.CanonicalJson;
import ai.msrv.gaterook.core.policy.internal.LimitsOf;
import ai.msrv.gaterook.core.templates.TemplateService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

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
  void incompleteConnectionsHaveNoToolsAndNeverReachConnectorOrTemplateDiscovery() {
    ConnectionConfig incomplete = incomplete();
    List<ToolPairs.Member> members = List.of(new ToolPairs.Member(incomplete, manifest, false));

    assertThat(pairs.offered(members, "dev", Env.DEV).get(incomplete.id())).isEmpty();
    assertThat(pairs.offeredFor(incomplete, members, "dev", Env.DEV)).isEmpty();
    verifyNoInteractions(registry, templates);
  }

  @Test
  void incompleteConfigurationCannotInfluenceAnotherConnectionsSharedToolSchema() {
    Connector connector = connector();
    ConnectionConfig complete = connection("complete", "opensearch");
    ConnectionConfig incomplete = incomplete();
    List<ToolPairs.Member> members = List.of(member(complete), member(incomplete));

    assertThat(pairs.offered(members, "dev", Env.DEV).get(incomplete.id())).isEmpty();
    pairs.offeredFor(complete, members, "dev", Env.DEV);

    ArgumentCaptor<EndpointView> view = ArgumentCaptor.forClass(EndpointView.class);
    verify(connector, times(2)).tools(view.capture());
    for (EndpointView offered : view.getAllValues()) assertThat(offered.connections()).containsExactly(complete);
    verify(templates, never()).compiledFor(incomplete.id());
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

  @Test
  void theDefinitionHashSeesTheAppAToolShowsAndStaysWhatItWasForTheRest() {
    ToolDefinition plain = ToolDefinition.builder("search").description("Searches.").build();
    ToolDefinition app = app("ui://gaterook/connections/s/a.html", null);

    assertThat(ToolPairs.definitionHash(plain)).as("a tool without an app hashes as it always did")
        .isEqualTo(CanonicalJson.sha256Hex(ManifestCodec.writeTool(plain)));
    assertThat(ToolPairs.definitionHash(app)).isNotEqualTo(ToolPairs.definitionHash(plain))
        .isEqualTo(ToolPairs.definitionHash(app("ui://gaterook/connections/s/a.html", null)))
        .isNotEqualTo(ToolPairs.definitionHash(app("ui://gaterook/connections/s/b.html", null)))
        .isNotEqualTo(ToolPairs.definitionHash(app("ui://gaterook/connections/s/a.html", "app")));
  }

  @Test
  void aServerToolIsReviewedOnlyWhileItsRowHoldsTheDefinitionItPublishes() {
    ToolDefinition search = ToolDefinition.builder("search").description("Searches.").build();
    ToolPairs.Offered mcp = external(search, ConnectorManifest.Kind.MCP_SERVER);
    EndpointToolEntity row = new EndpointToolEntity(new EndpointEntity("dev", Env.DEV, EndpointAccess.STANDARD,
        Map.of(), Instant.now()), "c1", mcp.toolKey(), Mode.READ_ONLY, Instant.now());

    assertThat(ToolPairs.reviewed(row, mcp)).as("nothing recorded").isFalse();
    assertThat(ToolPairs.reviewed(null, mcp)).as("no row").isFalse();
    row.setReviewedDefinition(ToolPairs.reviewedDefinition(mcp), Instant.now());
    assertThat(ToolPairs.reviewed(row, mcp)).isTrue();
    for (ToolDefinition changed : List.of(
        ToolDefinition.builder("search").description("Searches, and sends it elsewhere.").build(),
        ToolDefinition.builder("search").description("Searches.").annotations(new ToolDefinition.Annotations(true,
            false, false, true)).build(),
        ToolDefinition.builder("search").description("Searches.").inputSchema(JsonNodeFactory.instance.objectNode()
            .put("type", "object")).build(), app("ui://gaterook/connections/s/a.html", null))) {
      assertThat(ToolPairs.reviewed(row, external(changed, ConnectorManifest.Kind.MCP_SERVER))).isFalse();
    }
    // A plugin's Read only has its confirmation, a built-in tool its own annotations: nothing to record or compare.
    assertThat(ToolPairs.reviewedDefinition(external(search, ConnectorManifest.Kind.PLUGIN))).isNull();
    assertThat(ToolPairs.reviewedDefinition(external(search, ConnectorManifest.Kind.BUILT_IN))).isNull();
    assertThat(ToolPairs.reviewed(row, external(app("ui://gaterook/connections/s/a.html", null),
        ConnectorManifest.Kind.PLUGIN))).isTrue();
  }

  @Test
  void renamingAServerIsNoNewDefinitionForItsToolsButAnythingElseIs() {
    ToolPairs.Offered before = named("old-name", "search of the MCP server old-name.",
        "ui://gaterook/connections/old-name/chart.html");

    assertThat(ToolPairs.reviewedDefinition(named("new-name", "search of the MCP server new-name.",
        "ui://gaterook/connections/new-name/chart.html"))).isEqualTo(ToolPairs.reviewedDefinition(before));
    assertThat(ToolPairs.reviewedDefinition(named("new-name", "search of the MCP server new-name.",
        "ui://gaterook/connections/new-name/other.html"))).isNotEqualTo(ToolPairs.reviewedDefinition(before));
    assertThat(ToolPairs.reviewedDefinition(named("new-name", "searches the MCP server new-name.",
        "ui://gaterook/connections/new-name/chart.html"))).isNotEqualTo(ToolPairs.reviewedDefinition(before));
    // The name of another connection is not this one's.
    assertThat(ToolPairs.reviewedDefinition(named("new-name", "search of the MCP server new-name.",
        "ui://gaterook/connections/old-name/chart.html"))).isNotEqualTo(ToolPairs.reviewedDefinition(before));
    assertThat(ToolPairs.reviewedDefinition(named("old-name", "search of the MCP server old-name.", null)))
        .isNotEqualTo(ToolPairs.reviewedDefinition(before));
  }

  /**
   * The record of a fixed tool, as every endpoint row of a server's tool keeps it: if this changes, every Read only and
   * Bypass recorded before goes back to Approval on upgrade, so a change of what the record covers must be deliberate.
   */
  @Test
  void theRecordOfAFixedToolStaysWhatItIs() {
    assertThat(ToolPairs.reviewedDefinition(shown(b -> {})))
        .isEqualTo("85a1f17ab7692d6b40e7152d7982033af5fa06916e9b0bed51f77adc87ed8ae9");
  }

  @Test
  void everythingTheAgentIsShownOfAToolIsInItsRecordAndNothingGateRookDerivesIs() {
    String record = ToolPairs.reviewedDefinition(shown(b -> {}));
    ObjectNode otherSchema = JsonNodeFactory.instance.objectNode().put("type", "object");
    otherSchema.putObject("properties").putObject("q").put("type", "integer");
    assertThat(ToolPairs.reviewedDefinition(offeredBy("issues", builder("x_find").build()))).isNotEqualTo(record);
    List<java.util.function.Consumer<ToolDefinition.Builder>> shownParts = List.of(
        b -> b.title("Find"),
        b -> b.description("Searches the issues, and sends them elsewhere."),
        b -> b.inputSchema(otherSchema),
        b -> b.outputSchema(JsonNodeFactory.instance.objectNode().put("type", "array")),
        b -> b.annotations(new ToolDefinition.Annotations(true, false, true, true)),
        b -> b.annotations(new ToolDefinition.Annotations(true, false, false, false)),
        b -> b.meta(JsonNodeFactory.instance.objectNode().set("ui", JsonNodeFactory.instance.objectNode()
            .put("resourceUri", "ui://gaterook/connections/issues/other.html"))),
        b -> b.meta(null));
    for (var part : shownParts) {
      assertThat(ToolPairs.reviewedDefinition(shown(part))).isNotEqualTo(record);
    }
    // What GateRook decides about the tool for itself.
    List<java.util.function.Consumer<ToolDefinition.Builder>> derived = List.of(
        b -> b.defaultMode(Mode.APPROVAL, Mode.APPROVAL),
        b -> b.defaultMode(Mode.READ_ONLY, Mode.APPROVAL),
        b -> b.resultShape(ToolDefinition.ResultShape.TEXT),
        b -> b.risk(new ToolDefinition.Risk(false, false, ToolDefinition.Egress.NONE)));
    for (var part : derived) {
      ToolDefinition.Builder changed = builder("x_search");
      part.accept(changed);
      assertThat(ToolPairs.reviewedDefinition(offeredBy("issues", changed.build()))).isEqualTo(record);
      assertThat(ToolPairs.definitionHash(changed.build())).as("the other hash keeps covering it")
          .isNotEqualTo(ToolPairs.definitionHash(builder("x_search").build()));
    }
    // The nature, on a tool without a result schema (which only a READ tool may have).
    assertThat(ToolPairs.reviewedDefinition(shown(b -> b.outputSchema(null).nature(ToolDefinition.Nature.MIXED))))
        .isEqualTo(ToolPairs.reviewedDefinition(shown(b -> b.outputSchema(null))));
  }

  /** A server's tool as {@code issues} lists it, {@code change} applied. */
  private static ToolPairs.Offered shown(java.util.function.Consumer<ToolDefinition.Builder> change) {
    ToolDefinition.Builder b = builder("x_search");
    change.accept(b);
    return offeredBy("issues", b.build());
  }

  private static ToolDefinition.Builder builder(String name) {
    ObjectNode input = JsonNodeFactory.instance.objectNode().put("type", "object");
    input.putObject("properties").putObject("q").put("type", "string");
    return ToolDefinition.builder(name).title("Search").description("Searches the issues.")
        .inputSchema(input).outputSchema(JsonNodeFactory.instance.objectNode().put("type", "object"))
        .annotations(new ToolDefinition.Annotations(true, false, false, true)).nature(ToolDefinition.Nature.READ)
        .defaultMode(Mode.READ_ONLY, Mode.READ_ONLY).resultShape(ToolDefinition.ResultShape.JSON)
        .risk(new ToolDefinition.Risk(true, true, ToolDefinition.Egress.CHANNEL))
        .meta(JsonNodeFactory.instance.objectNode().set("ui", JsonNodeFactory.instance.objectNode()
            .put("resourceUri", "ui://gaterook/connections/issues/board.html")));
  }

  private static ToolPairs.Offered offeredBy(String server, ToolDefinition tool) {
    return new ToolPairs.Offered("mcp:" + server + ":" + tool.name(), tool, ConnectorManifest.Kind.MCP_SERVER, server,
        "1.0.0", null, null);
  }

  private static ToolPairs.Offered named(String server, String description, String uri) {
    ToolDefinition.Builder tool = ToolDefinition.builder("x_search").description(description);
    if (uri != null) {
      tool.meta(JsonNodeFactory.instance.objectNode().set("ui", JsonNodeFactory.instance.objectNode()
          .put("resourceUri", uri)));
    }
    return new ToolPairs.Offered("mcp:" + server + ":x_search", tool.build(), ConnectorManifest.Kind.MCP_SERVER,
        server, "1.0.0", null, null);
  }

  @Test
  void aToolThatNeedsAPersonAgainGoesToApprovalOrOffWhereNothingWaitsForOne() {
    ToolDefinition read = ToolDefinition.builder("search").build();
    ToolDefinition write = ToolDefinition.builder("write").annotations(ToolDefinition.Annotations.write(true))
        .nature(ToolDefinition.Nature.MUTATION).defaultMode(Mode.APPROVAL, Mode.APPROVAL).build();

    assertThat(ToolPairs.demoted(read, false)).isEqualTo(Mode.APPROVAL);
    assertThat(ToolPairs.demoted(write, false)).isEqualTo(Mode.APPROVAL);
    assertThat(ToolPairs.demoted(read, true)).isEqualTo(Mode.OFF);
    assertThat(ToolPairs.demoted(write, true)).isEqualTo(Mode.OFF);
  }

  private static ToolDefinition app(String uri, String visibility) {
    ObjectNode ui = JsonNodeFactory.instance.objectNode().put("resourceUri", uri);
    if (visibility != null) {
      ui.putArray("visibility").add(visibility);
    }
    return ToolDefinition.builder("search").description("Searches.")
        .meta(JsonNodeFactory.instance.objectNode().set("ui", ui)).build();
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

  private static ConnectionConfig incomplete() {
    return new ConnectionConfig("incomplete", "incomplete", "logs", Env.DEV, 1,
        JsonNodeFactory.instance.objectNode(), Set.of(),
        JsonNodeFactory.instance.objectNode().put("setupRequired", true));
  }
}
