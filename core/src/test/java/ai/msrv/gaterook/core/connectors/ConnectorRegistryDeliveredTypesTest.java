package ai.msrv.gaterook.core.connectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.MasterKeyLock;
import ai.msrv.gaterook.core.platform.RowMac;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import java.time.Clock;
import java.time.Duration;
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
 * installed already. A type that isn't here is asked for where something reaches for it.
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

  @SuppressWarnings("unchecked")
  private static ConnectorRegistry registry(List<Connector> compiledIn, ConnectorSource source) {
    ConnectorTypeRepository rows = mock(ConnectorTypeRepository.class);
    when(rows.findAllById(any())).thenReturn(List.of());
    when(rows.findById(anyString())).thenReturn(Optional.empty());
    when(rows.saveAndFlush(any(ConnectorTypeEntity.class))).thenAnswer(call -> call.getArgument(0));
    RowMac mac = mock(RowMac.class);
    when(mac.fields()).thenAnswer(call -> JSON.createObjectNode());
    when(mac.sign(anyString(), anyString(), anyLong(), any())).thenReturn(new byte[] {1});
    PluginRepository plugins = mock(PluginRepository.class);
    when(plugins.findById(anyString())).thenReturn(Optional.empty());
    ConnectorRegistry registry = new ConnectorRegistry(compiledIn, List.of(source), rows, plugins, mac,
        mock(MasterKeyLock.class), mock(IntegrityAlerts.class), mock(ObjectProvider.class),
        mock(ApplicationEventPublisher.class), JSON, Clock.systemUTC(), mock(PlatformTransactionManager.class),
        false);
    registry.synchronize();
    return registry;
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

    Connector other = connector("oracle", ConnectorManifest.Kind.BUILT_IN, tool("sql_query", "Runs PL/SQL too."));
    assertThat(registry(List.of(postgres), new Source().module("sql-oracle", other)).type("oracle"))
        .as("a tool that differs from the compiled-in one of its name can't share an endpoint with it").isEmpty();

    Connector core = connector("oracle", ConnectorManifest.Kind.BUILT_IN, tool("approval_status", "Pretends."));
    assertThat(registry(List.of(postgres), new Source().module("sql-oracle", core)).type("oracle")).isEmpty();
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
}
