package ai.msrv.gaterook.core.connectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.core.connectors.internal.persistence.ConnectorTypeEntity;
import ai.msrv.gaterook.core.connectors.internal.persistence.ConnectorTypeRepository;
import ai.msrv.gaterook.core.connectors.internal.persistence.PluginRepository;
import ai.msrv.gaterook.core.platform.CanonicalJson;
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.MasterKeyLock;
import ai.msrv.gaterook.core.platform.RowMac;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * GateRook's own connector types that a source delivers (connector modules, docs/connector-delivery.md): they
 * register as built-in types before any foreign type, a compiled-in connector of the same id comes first, and their
 * tool names are closed to plugins. A module that is only listed closes its names to new plugins, not to one that is
 * installed already. A type that isn't here is asked for where something reaches for it, and a type an earlier build
 * compiled in stays registered from its row until its module arrives.
 */
class ConnectorRegistryDeliveredTypesTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** A source with plugin types and, marked first-party, module types. */
  private static final class Source implements ConnectorSource {
    final Map<String, Connector> connectors = new LinkedHashMap<>();
    final Map<String, String> modules = new LinkedHashMap<>();
    Set<String> listed = Set.of();

    Source module(String moduleId, Connector connector) {
      connectors.put(connector.manifest().id(), connector);
      modules.put(connector.manifest().id(), moduleId);
      return this;
    }

    Source plugin(Connector connector) {
      connectors.put(connector.manifest().id(), connector);
      return this;
    }

    @Override
    public List<ConnectorManifest> types() {
      return connectors.values().stream().map(Connector::manifest).toList();
    }

    @Override
    public Optional<Connector> connectorFor(ConnectionConfig connection) {
      return Optional.ofNullable(connectors.get(connection.type()));
    }

    @Override
    public boolean firstParty(String typeId) {
      return modules.containsKey(typeId);
    }

    @Override
    public Optional<String> packageOf(String typeId) {
      return connectors.containsKey(typeId) ? Optional.of(modules.getOrDefault(typeId, typeId)) : Optional.empty();
    }

    @Override
    public List<Connector> firstPartyConnectors() {
      return connectors.values().stream().filter(c -> modules.containsKey(c.manifest().id())).toList();
    }

    @Override
    public Set<String> reservedToolNames() {
      return listed;
    }
  }

  private static ToolDefinition tool(String name, String description) {
    ObjectNode input = JSON.createObjectNode().put("type", "object");
    input.putObject("properties").putObject("connection").put("type", "string");
    return ToolDefinition.builder(name).title(name).description(description).inputSchema(input).build();
  }

  /** A tool that takes one more argument than {@link #tool}: another contract under the same name. */
  private static ToolDefinition toolWithArgument(String name, String description, String argument) {
    ObjectNode input = JSON.createObjectNode().put("type", "object");
    ObjectNode properties = input.putObject("properties");
    properties.putObject("connection").put("type", "string");
    properties.putObject(argument).put("type", "string").put("description", "One more argument.");
    return ToolDefinition.builder(name).title(name).description(description).inputSchema(input).build();
  }

  private static Connector connector(String id, ConnectorManifest.Kind kind, ToolDefinition... tools) {
    ConnectorManifest m = ConnectorManifest.builder(id, "1.0.0", kind).name(id).tools(List.of(tools)).build();
    return new Connector() {
      @Override
      public ConnectorManifest manifest() {
        return m;
      }

      @Override
      public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
        return Classification.read();
      }

      @Override
      public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
        return ActionPreview.of(ActionPreview.Kind.TEXT, "text", "", cfg.name(), "");
      }

      @Override
      public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
        return TestResult.success(1, null, null, null);
      }

      @Override
      public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
        return ToolResult.text(id);
      }
    };
  }

  private static ConnectorRegistry registry(List<Connector> compiledIn, ConnectorSource source) {
    return registry(compiledIn, source, List.of(), null, mock(ApplicationEventPublisher.class));
  }

  /**
   * A registry over the rows an earlier build left, told what makes a type arrive before it first looks at them.
   * Rows verify unless their id is {@code tampered}.
   */
  @SuppressWarnings("unchecked")
  private static ConnectorRegistry registry(List<Connector> compiledIn, ConnectorSource source,
      List<ConnectorTypeEntity> earlier, ConnectorRegistry.MissingTypes handler,
      ApplicationEventPublisher publisher) {
    ConnectorTypeRepository rows = mock(ConnectorTypeRepository.class);
    when(rows.findAll()).thenReturn(earlier);
    when(rows.findById(anyString())).thenAnswer(call -> earlier.stream()
        .filter(row -> row.getId().equals(call.getArgument(0))).findFirst());
    when(rows.saveAndFlush(any(ConnectorTypeEntity.class))).thenAnswer(call -> call.getArgument(0));
    RowMac mac = mock(RowMac.class);
    when(mac.fields()).thenAnswer(call -> JSON.createObjectNode());
    when(mac.sign(anyString(), anyString(), anyLong(), any())).thenReturn(new byte[] {1});
    when(mac.verify(anyString(), anyString(), anyLong(), any(), any())).thenReturn(true);
    when(mac.verify(anyString(), eq("tampered"), anyLong(), any(), any())).thenReturn(false);
    PluginRepository plugins = mock(PluginRepository.class);
    when(plugins.findById(anyString())).thenReturn(Optional.empty());
    when(plugins.existsById(anyString())).thenReturn(true);
    ConnectorRegistry registry = new ConnectorRegistry(compiledIn, List.of(source), rows, plugins, mac,
        mock(MasterKeyLock.class), mock(IntegrityAlerts.class), mock(ObjectProvider.class), publisher, JSON,
        Clock.systemUTC(), mock(PlatformTransactionManager.class), false);
    if (handler != null) {
      registry.missingTypes(handler);
    }
    registry.synchronize();
    return registry;
  }

  /** The row a build leaves of a type it compiles in: its manifest, no package. */
  @SuppressWarnings("unchecked")
  private static ConnectorTypeEntity row(Connector connector) {
    ConnectorManifest m = connector.manifest();
    ObjectNode tree = ManifestCodec.write(m);
    return new ConnectorTypeEntity(m.id(), m.kind(), m.version(), JSON.treeToValue(tree, Map.class),
        CanonicalJson.sha256Hex(tree), null, Instant.EPOCH);
  }

  /** What makes types arrive: it records what it is asked and stands in for the types in {@link #keeps}. */
  private static final class Arrivals implements ConnectorRegistry.MissingTypes {
    final List<String> asked = new ArrayList<>();
    final Map<String, Connector> standIns = new LinkedHashMap<>();
    Set<String> keeps = Set.of();

    @Override
    public void wanted(String typeId) {
      asked.add("wanted " + typeId);
    }

    @Override
    public boolean await(String typeId, Duration budget) {
      asked.add("await " + typeId + " " + budget.toSeconds());
      return true;
    }

    @Override
    public Optional<Connector> standIn(ConnectorManifest known) {
      if (!keeps.contains(known.id())) {
        return Optional.empty();
      }
      return Optional.of(standIns.computeIfAbsent(known.id(), id -> connector(id, known.kind(),
          known.tools().toArray(ToolDefinition[]::new))));
    }
  }

  private static ConnectionConfig of(String type) {
    return new ConnectionConfig("c-" + type, type + "-dev", type, Env.DEV, 1, JSON.createObjectNode(), Set.of(), null);
  }

  @Test
  void aModulesTypeIsABuiltInTypeAndItsToolNamesAreClosedToPlugins() {
    Connector cassandra = connector("cassandra", ConnectorManifest.Kind.BUILT_IN, tool("cql_query", "Runs CQL."));
    Connector squatter = connector("cql-tools", ConnectorManifest.Kind.PLUGIN, tool("cql_export", "Exports."));
    Connector jira = connector("jira", ConnectorManifest.Kind.PLUGIN, tool("jira_search", "Searches."));
    // The plugins come first in the source: the module's type still registers before them.
    Source source = new Source().plugin(squatter).plugin(jira).module("cassandra", cassandra);
    ConnectorRegistry registry = registry(List.of(connector("postgres", ConnectorManifest.Kind.BUILT_IN,
        tool("sql_query", "Runs SQL."))), source);

    assertThat(registry.type("cassandra")).hasValueSatisfying(t -> {
      assertThat(t.kind()).isEqualTo(ConnectorManifest.Kind.BUILT_IN);
      assertThat(t.usable()).isTrue();
    });
    assertThat(registry.compiledIn("cassandra")).isFalse();
    assertThat(registry.connectorFor(of("cassandra"))).containsSame(cassandra);
    assertThat(registry.builtInConnectors()).contains(cassandra);
    assertThat(registry.reservedToolNames()).contains("cql_query", "sql_query");
    assertThat(registry.reservedPrefixes()).contains("cql_", "sql_");
    assertThat(registry.reservedProblem("cql_drop")).hasValue("cql_drop starts with cql_, which only built-in tools "
        + "use");
    assertThat(registry.type("cql-tools")).as("a plugin can't pass its tool off as the module's").isEmpty();
    assertThat(registry.type("jira")).isPresent();
  }

  @Test
  void aCompiledInConnectorOfTheSameTypeComesBeforeAModule() {
    Connector compiledIn = connector("neo4j", ConnectorManifest.Kind.BUILT_IN, tool("neo4j_query", "Runs Cypher."));
    Connector module = connector("neo4j", ConnectorManifest.Kind.BUILT_IN, tool("neo4j_query", "Runs Cypher."));
    ConnectorRegistry registry = registry(List.of(compiledIn), new Source().module("neo4j", module));

    assertThat(registry.compiledIn("neo4j")).isTrue();
    assertThat(registry.connectorFor(of("neo4j"))).containsSame(compiledIn);
    assertThat(registry.builtInConnectors()).containsExactly(compiledIn);
    assertThat(registry.types()).extracting(ConnectorType::id).containsExactly("neo4j");
  }

  @Test
  void onlyAFirstPartySourceAddsABuiltInTypeAndItsSharedToolsMustEqualTheCompiledInOnes() {
    Connector postgres = connector("postgres", ConnectorManifest.Kind.BUILT_IN, tool("sql_query", "Runs SQL."));
    Connector pretender = connector("oracle", ConnectorManifest.Kind.BUILT_IN, tool("ora_query", "Runs SQL."));
    assertThat(registry(List.of(postgres), new Source().plugin(pretender)).type("oracle"))
        .as("foreign code declares no built-in type").isEmpty();

    Connector same = connector("oracle", ConnectorManifest.Kind.BUILT_IN, tool("sql_query", "Runs SQL."));
    assertThat(registry(List.of(postgres), new Source().module("sql-oracle", same)).type("oracle"))
        .as("an equal tool merges with the compiled-in one on an endpoint").isPresent();

    Connector other = connector("oracle", ConnectorManifest.Kind.BUILT_IN,
        toolWithArgument("sql_query", "Runs SQL.", "hint"));
    assertThat(registry(List.of(postgres), new Source().module("sql-oracle", other)).type("oracle"))
        .as("a tool that differs from the compiled-in one of its name can't share an endpoint with it").isEmpty();

    Connector core = connector("oracle", ConnectorManifest.Kind.BUILT_IN, tool("approval_status", "Pretends."));
    assertThat(registry(List.of(postgres), new Source().module("sql-oracle", core)).type("oracle")).isEmpty();
  }

  @Test
  void aToolAModuleSharesWithACompiledInTypeIsTheFamilysWhateverBuildWordedIt() {
    ToolDefinition family = tool("sql_query", "Runs SQL.");
    Connector postgres = connector("postgres", ConnectorManifest.Kind.BUILT_IN, family);
    // The module was built on another day: the same tool, worded as that build worded it, and one tool of its own.
    ToolDefinition worded = tool("sql_query", "Ran SQL, as an earlier build said it.");
    ToolDefinition own = tool("ora_explain", "Explains a plan.");
    Connector oracle = connector("oracle", ConnectorManifest.Kind.BUILT_IN, worded, own);
    Connector sqlServer = connector("sqlserver", ConnectorManifest.Kind.BUILT_IN,
        tool("sql_query", "Runs T-SQL, as yet another build said it."));
    ConnectorRegistry registry = registry(List.of(postgres), new Source().module("sql-oracle", oracle)
        .module("sql-sqlserver", sqlServer));

    assertThat(registry.tool("oracle", "sql_query")).as("registered with the family's definition").contains(family);
    assertThat(registry.tool("sqlserver", "sql_query")).as("two modules of one family agree through it")
        .contains(family);
    assertThat(registry.tool("oracle", "ora_explain")).contains(own);
    assertThat(registry.published("oracle", List.of(worded, own))).as("and so are the tools of its endpoints")
        .containsExactly(family, own);
    assertThat(registry.published("postgres", List.of(worded))).as("a compiled-in type's tools are its own")
        .containsExactly(worded);

    assertThat(ConnectorRegistry.sameContract(family, worded)).isTrue();
    assertThat(ConnectorRegistry.sameTool(family, worded)).isFalse();
    assertThat(ConnectorRegistry.sameContract(family, toolWithArgument("sql_query", "Runs SQL.", "hint")))
        .as("another argument is another contract").isFalse();
    ToolDefinition change = ToolDefinition.builder("sql_query").title("sql_query").description("Runs SQL.")
        .inputSchema(family.inputSchema()).nature(ToolDefinition.Nature.MUTATION)
        .defaultMode(ToolDefinition.Mode.APPROVAL, ToolDefinition.Mode.APPROVAL).build();
    assertThat(ConnectorRegistry.sameContract(family, change)).as("and so is another nature").isFalse();
  }

  @Test
  void aModuleThatIsOnlyListedClosesItsNamesToNewPluginsNotToAnInstalledOne() {
    Connector clickhouse = connector("clickhouse", ConnectorManifest.Kind.PLUGIN,
        tool("clickhouse_query", "Runs a query."));
    Source source = new Source().plugin(clickhouse);
    source.listed = Set.of("clickhouse_query", "duck_query");
    ConnectorRegistry registry = registry(List.of(), source);

    assertThat(registry.type("clickhouse")).as("the installed plugin keeps its tools").isPresent();
    assertThat(registry.reservedProblem("clickhouse_query")).as("a new one may not take the name").isPresent();
    assertThat(registry.isReserved("duck_export")).isTrue();
    assertThat(registry.reservedToolNames()).contains("duck_query");
    assertThat(registry.isReserved("jira_search")).isFalse();
  }

  @Test
  void aTypeThatIsNotHereIsAskedForInPassingAndWaitedForWhereARequestNeedsIt() {
    ConnectorRegistry registry = registry(List.of(connector("postgres", ConnectorManifest.Kind.BUILT_IN,
        tool("sql_query", "Runs SQL."))), new Source());
    List<String> asked = new ArrayList<>();
    registry.missingTypes(new ConnectorRegistry.MissingTypes() {
      @Override
      public void wanted(String typeId) {
        asked.add("wanted " + typeId);
      }

      @Override
      public boolean await(String typeId, Duration budget) {
        asked.add("await " + typeId + " " + budget.toSeconds());
        return true;
      }
    });

    assertThat(registry.type("postgres")).isPresent();
    registry.awaitInstalled("postgres");
    assertThat(asked).as("a registered type asks for nothing").isEmpty();

    assertThat(registry.type("cassandra")).isEmpty();
    assertThat(registry.manifest("cassandra")).isEmpty();
    assertThat(registry.connectorFor(of("cassandra"))).isEmpty();
    assertThat(asked).containsExactly("wanted cassandra", "wanted cassandra", "wanted cassandra");

    asked.clear();
    registry.awaitInstalled("cassandra");
    registry.awaitInstalled("Not A Type");
    registry.awaitInstalled(null);
    assertThat(registry.type("Not A Type")).isEmpty();
    assertThat(asked).as("only an id that could be a type's").containsExactly("await cassandra 90");
  }

  @Test
  void aTypeAnEarlierBuildCompiledInStaysRegisteredFromItsRowUntilItsModuleArrives() {
    Connector postgres = connector("postgres", ConnectorManifest.Kind.BUILT_IN, tool("sql_query", "Runs SQL."));
    Connector graph = connector("graph", ConnectorManifest.Kind.BUILT_IN, tool("graph_query", "Reads a graph."));
    // A dialect of the SQL family that the build before compiled in, with that build's wording of the shared tool.
    Connector oracle = connector("oracle", ConnectorManifest.Kind.BUILT_IN,
        tool("sql_query", "Ran SQL in the build before."));
    Arrivals arrivals = new Arrivals();
    arrivals.keeps = Set.of("graph", "oracle");
    Source source = new Source().plugin(connector("graph-tools", ConnectorManifest.Kind.PLUGIN,
        tool("graph_query", "Takes the name.")));
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    ConnectorRegistry registry = registry(List.of(postgres), source, List.of(row(postgres), row(graph), row(oracle)),
        arrivals, publisher);

    assertThat(registry.awaitedTypes()).containsExactlyInAnyOrder("graph", "oracle");
    assertThat(registry.tool("oracle", "sql_query")).map(ToolDefinition::description)
        .as("a tool it shares with a compiled-in type is worded as this build words it").contains("Runs SQL.");
    assertThat(registry.type("graph")).map(t -> t.manifest().kind()).contains(ConnectorManifest.Kind.BUILT_IN);
    assertThat(registry.usable("graph")).as("its switch is the row's").isTrue();
    assertThat(registry.types()).extracting(t -> t.manifest().id()).contains("graph", "postgres");
    assertThat(registry.tool("graph", "graph_query")).as("its tools as the row describes them").isPresent();
    assertThat(registry.connectorFor(of("graph"))).containsSame(arrivals.standIns.get("graph"));
    assertThat(registry.compiledIn("graph")).isFalse();
    assertThat(registry.type("graph-tools")).as("its tool names stay GateRook's own meanwhile").isEmpty();
    assertThat(registry.isReserved("graph_query")).isTrue();
    assertThat(arrivals.asked).as("every look asks for it again").contains("wanted graph")
        .doesNotContain("wanted postgres");

    arrivals.asked.clear();
    registry.awaitInstalled("graph");
    assertThat(arrivals.asked).as("a request that needs the type waits for the real one")
        .containsExactly("await graph 90");
    verify(publisher, never()).publishEvent(new ConnectorTypeChanged("graph"));

    // Its module arrives.
    source.module("graph-module", graph);
    registry.synchronize();
    assertThat(registry.awaitedTypes()).as("the other one is still on its way").containsExactly("oracle");
    assertThat(registry.connectorFor(of("graph"))).containsSame(graph);
    assertThat(registry.type("graph")).isPresent();
    verify(publisher).publishEvent(new ConnectorTypeChanged("graph"));
  }

  @Test
  void aRowIsNotKeptWhenNothingMakesItsTypeArriveOrItCannotBeHeld() {
    Connector postgres = connector("postgres", ConnectorManifest.Kind.BUILT_IN, tool("sql_query", "Runs SQL."));
    Arrivals arrivals = new Arrivals();
    arrivals.keeps = Set.of("tampered", "jira", "oracle", "wanted-by-nobody-else");
    List<ConnectorTypeEntity> earlier = List.of(
        // Nothing stands in for it: no module can bring it, or no connection uses it.
        row(connector("graph", ConnectorManifest.Kind.BUILT_IN, tool("graph_query", "Reads a graph."))),
        // Changed outside GateRook.
        row(connector("tampered", ConnectorManifest.Kind.BUILT_IN, tool("tampered_read", "Reads."))),
        // A plugin's type that is gone: never GateRook's own.
        row(connector("jira", ConnectorManifest.Kind.PLUGIN, tool("jira_search", "Searches."))),
        // Its shared tool takes other arguments than the one this build compiles in: the two couldn't share an
        // endpoint.
        row(connector("oracle", ConnectorManifest.Kind.BUILT_IN, toolWithArgument("sql_query", "Runs SQL.", "hint"))));
    ConnectorRegistry registry = registry(List.of(postgres), new Source(), earlier, arrivals,
        mock(ApplicationEventPublisher.class));

    assertThat(registry.awaitedTypes()).isEmpty();
    for (String id : List.of("graph", "tampered", "jira", "oracle")) {
      assertThat(registry.type(id)).as(id).isEmpty();
      assertThat(registry.connectorFor(of(id))).as(id).isEmpty();
    }
    assertThat(registry.isReserved("graph_query")).isFalse();
    assertThat(arrivals.asked).as("a type that isn't here is asked for as before").contains("wanted graph");
  }
}
