package ai.msrv.gaterook.core.connectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TemplateKind;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connector.api.ToolDefinition.Annotations;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import ai.msrv.gaterook.testfixtures.FakeConnectors;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The checks that fail the start (ARCHITECTURE.md §5 step 4): same-named tools of two types are equal up to the
 * {@code connection} enum or the core doesn't start; no connector takes a core tool's name; names are valid. And the
 * built-in families no plugin, MCP server or template may use.
 */
class ConnectorRegistryTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static ConnectorManifest manifest(String id, ConnectorManifest.Kind kind, ToolDefinition... tools) {
    return ConnectorManifest.builder(id, "1.0.0", kind).name(id).tools(List.of(tools)).build();
  }

  private static ObjectNode input(String... connectionEnum) {
    ObjectNode n = JSON.createObjectNode().put("type", "object");
    ObjectNode connection = n.putObject("properties").putObject("connection").put("type", "string");
    if (connectionEnum.length > 0) {
      var e = connection.putArray("enum");
      for (String c : connectionEnum) {
        e.add(c);
      }
    }
    ((ObjectNode) n.get("properties")).putObject("sql").put("type", "string");
    return n;
  }

  private static ToolDefinition query(String description, ObjectNode input) {
    return ToolDefinition.builder("sql_query").title("Run a read-only SQL query").description(description)
        .inputSchema(input).annotations(Annotations.read()).nature(Nature.READ)
        .defaultMode(Mode.READ_ONLY, Mode.READ_ONLY).build();
  }

  @Test
  void sameNamedToolsMergeOnlyWhenEqualUpToTheConnectionEnum() {
    ConnectorManifest pg = manifest("pg", ConnectorManifest.Kind.BUILT_IN, query("Runs one SELECT.", input("a")));
    ConnectorManifest my = manifest("my", ConnectorManifest.Kind.BUILT_IN, query("Runs one SELECT.", input("b")));
    assertThatCode(() -> ConnectorRegistry.checkTools(List.of(pg, my))).doesNotThrowAnyException();
    assertThatCode(() -> ConnectorRegistry.checkTools(List.of(FakeConnectors.sql("pgfake", "FakePG"),
        FakeConnectors.sql("myfake", "FakeMy")))).doesNotThrowAnyException();

    ConnectorManifest otherText = manifest("my", ConnectorManifest.Kind.BUILT_IN,
        query("Runs one SELECT on MySQL.", input()));
    assertThatThrownBy(() -> ConnectorRegistry.checkTools(List.of(pg, otherText)))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("sql_query of my differs from the one of pg");

    ObjectNode wider = input();
    ((ObjectNode) wider.get("properties")).putObject("limit").put("type", "integer");
    ConnectorManifest otherSchema = manifest("my", ConnectorManifest.Kind.BUILT_IN, query("Runs one SELECT.", wider));
    assertThatThrownBy(() -> ConnectorRegistry.checkTools(List.of(pg, otherSchema)))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("differs");

    ConnectorManifest otherNature = manifest("my", ConnectorManifest.Kind.BUILT_IN,
        ToolDefinition.builder("sql_query").title("Run a read-only SQL query").description("Runs one SELECT.")
            .inputSchema(input()).annotations(Annotations.write(false)).nature(Nature.MIXED)
            .defaultMode(Mode.APPROVAL, Mode.APPROVAL).build());
    assertThatThrownBy(() -> ConnectorRegistry.checkTools(List.of(pg, otherNature)))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("differs");
  }

  @Test
  void noConnectorTakesACoreToolOrAnInvalidOrRepeatedName() {
    ConnectorManifest core = manifest("x", ConnectorManifest.Kind.BUILT_IN,
        ToolDefinition.builder("approval_status").inputSchema(input()).build());
    assertThatThrownBy(() -> ConnectorRegistry.checkTools(List.of(core)))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("approval_status is a tool of the core");

    ConnectorManifest invalid = manifest("x", ConnectorManifest.Kind.BUILT_IN,
        ToolDefinition.builder("Bad-Name").inputSchema(input()).build());
    assertThatThrownBy(() -> ConnectorRegistry.checkTools(List.of(invalid)))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("is not ^[a-z][a-z0-9_]{0,63}$");

    ConnectorManifest twice = manifest("x", ConnectorManifest.Kind.BUILT_IN, query("a", input()), query("a", input()));
    assertThatThrownBy(() -> ConnectorRegistry.checkTools(List.of(twice)))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("declared twice");
  }

  @Test
  void theBuiltInFamiliesComeFromTheBuiltInToolsOnly() {
    ConnectorManifest sql = FakeConnectors.sql("pgfake", "FakePG");
    ConnectorManifest plain = manifest("ping", ConnectorManifest.Kind.BUILT_IN,
        ToolDefinition.builder("ping").inputSchema(input()).build());
    ConnectorManifest plugin = manifest("jira", ConnectorManifest.Kind.PLUGIN,
        ToolDefinition.builder("jira_search").inputSchema(input()).build());
    assertThat(ConnectorRegistry.reservedPrefixes(List.of(sql, plain, plugin))).containsExactly("sql_");
  }

  @Test
  void aTemplateKindOfSeveralConnectorsCompilesWithTheConnectorOfItsConnection() {
    Connector pg = withKind("pg", "sql-query");
    Connector my = withKind("my", "sql-query");
    Connector ssh = withKind("ssh", "ssh-runbook");

    Map<String, TemplateKind> kinds = ConnectorRegistry.templateKinds(List.of(pg, my, ssh));

    assertThat(kinds.keySet()).containsExactly("sql-query", "ssh-runbook");
    TemplateKind sql = kinds.get("sql-query");
    assertThat(sql.connectionTypes()).containsExactly("pg", "my");
    assertThat(sql.definitionSchema().path("owner").asString()).isEqualTo("pg");
    assertThat(sql.compile(JSON.createObjectNode(), on("my")).warnings()).containsExactly("compiled by my");
    assertThat(sql.compile(JSON.createObjectNode(), on("pg")).warnings()).containsExactly("compiled by pg");
    assertThat(sql.preview(JSON.createObjectNode(), JSON.createObjectNode(), on("my")).summary())
        .isEqualTo("previewed by my");
    assertThat(sql.compile(JSON.createObjectNode(), on("ssh")).errors())
        .containsExactly("sql-query templates don't run on ssh connections.");
    // A kind only one connector offers is that connector's own.
    assertThat(kinds.get("ssh-runbook")).isSameAs(ssh.templateKinds().getFirst());
  }

  private static ConnectionConfig on(String type) {
    return new ConnectionConfig("c-" + type, type + "-db", type, Env.DEV, 1, JSON.createObjectNode(), Set.of(), null);
  }

  /** A connector of {@code type} that offers {@code kind} for its own type and says who compiled. */
  private static Connector withKind(String type, String kind) {
    ConnectorManifest m = manifest(type, ConnectorManifest.Kind.BUILT_IN);
    TemplateKind k = new TemplateKind() {
      @Override
      public String kind() {
        return kind;
      }

      @Override
      public List<String> connectionTypes() {
        return List.of(type);
      }

      @Override
      public JsonNode definitionSchema() {
        return JSON.createObjectNode().put("owner", type);
      }

      @Override
      public CompileResult compile(JsonNode definition, ConnectionConfig runsOn) {
        return new CompileResult(List.of(), List.of(), List.of(), List.of("compiled by " + type), null);
      }

      @Override
      public ActionPreview preview(JsonNode definition, ObjectNode arguments, ConnectionConfig runsOn) {
        return ActionPreview.of(ActionPreview.Kind.TEXT, "text", "", runsOn.name(), "previewed by " + type);
      }
    };
    return new Connector() {
      @Override
      public ConnectorManifest manifest() {
        return m;
      }

      @Override
      public List<TemplateKind> templateKinds() {
        return List.of(k);
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
        return TestResult.success(0, null, null, null);
      }

      @Override
      public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
        return ToolResult.text("");
      }
    };
  }
}
