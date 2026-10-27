package ai.msrv.gaterook.core.connectors.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.TemplateKind;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The test connector of M1.7: classification by the first keyword, the approved payload, its one table, templates. */
class FakeSqlConnectorTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final ConnectionConfig CFG = new ConnectionConfig("c-1", "orders-db", FakeSqlConnector.TYPE, Env.DEV,
      1, JSON.readTree("{\"connection\":{\"host\":\"db.test\",\"database\":\"app\"},\"credentials\":{\"user\":\"u\"}}"),
      Set.of("/credentials/password"), null);

  private final FakeSqlConnector fake = FakeSqlConnector.like(Optional.empty());

  @Test
  void itCopiesTheToolsFormAndPolicyOfTheBuiltInSqlType() {
    ToolDefinition query = ToolDefinition.builder("sql_query").title("Q").description("d")
        .inputSchema(JSON.readTree("{\"type\":\"object\"}")).build();
    ToolDefinition other = ToolDefinition.builder("sql_describe_table").title("T").build();
    ConnectorManifest postgres = ConnectorManifest.builder("postgres", "1", ConnectorManifest.Kind.BUILT_IN)
        .configSchema(JSON.readTree("{\"type\":\"object\",\"title\":\"pg\"}"))
        .policySchema(JSON.readTree("{\"type\":\"object\",\"x-policy-section\":\"sql\"}"))
        .tools(List.of(query, other)).build();
    ConnectorManifest m = FakeSqlConnector.like(Optional.of(postgres)).manifest();
    assertThat(m.id()).isEqualTo("fake-sql");
    assertThat(m.tools()).containsExactly(query);
    assertThat(m.configSchema()).isEqualTo(postgres.configSchema());
    assertThat(m.policySchema()).isEqualTo(postgres.policySchema());
  }

  @Test
  void theFirstKeywordDecidesAndDdlIsDeniedWhereThePolicyDeniesIt() {
    assertThat(classify("sql_query", "  select 1", Env.DEV).kind()).isEqualTo(Classification.Kind.READ);
    assertThat(classify("sql_query", "WITH x AS (SELECT 1) SELECT * FROM x", Env.DEV).kind())
        .isEqualTo(Classification.Kind.READ);
    assertThat(classify("sql_query", "UPDATE orders SET status = 'x'", Env.DEV).kind())
        .isEqualTo(Classification.Kind.MUTATION);
    assertThat(classify("sql_execute", "SELECT 1", Env.DEV).kind()).isEqualTo(Classification.Kind.MUTATION);
    Classification ddl = classify("sql_execute", "DROP TABLE orders", Env.PROD);
    assertThat(ddl.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(ddl.ruleId()).isEqualTo("prod.sql.block_ddl");
    assertThat(classify("sql_execute", "DROP TABLE orders", Env.DEV).kind()).isEqualTo(Classification.Kind.MUTATION);
  }

  @Test
  void aChangeRunsOnlyApprovedAndExactlyAsApproved() throws ConnectorException {
    ToolCall update = call("sql_execute", "UPDATE orders SET status = 'cancelled' WHERE id = 48213;");
    assertThatThrownBy(() -> fake.execute(update, CFG, null, context(false, null)))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("Not approved");

    // The stored payload runs, whatever the arguments say now.
    ObjectNode approved = JSON.createObjectNode().put("sql", "UPDATE orders SET status = 'shipped' WHERE id = 48240;");
    ToolResult result = fake.execute(update, CFG, null, context(true, approved));
    assertThat(result.structuredContent().path("rowsAffected").asInt()).isEqualTo(1);

    ToolResult rows = fake.execute(call("sql_query", "SELECT id, status FROM orders WHERE status = 'shipped'"), CFG,
        null, context(false, null));
    assertThat(rows.table().rows().toString()).isEqualTo("[[48240,\"shipped\"],[48241,\"shipped\"]]");
    assertThat(fake.describeAction(update, CFG).text()).isEqualTo(update.string("sql"));
  }

  @Test
  void aTemplateCompilesToOneToolAndBindsItsParametersAsLiterals() throws ConnectorException {
    TemplateKind kind = fake.templateKinds().getFirst();
    TemplateKind.CompileResult compiled = kind.compile(JSON.readTree("""
        {"name":"orders_by_status","sql":"SELECT id, status FROM orders WHERE status = :status",
         "params":{"status":{"type":"string","enum":["pending","shipped"]}}}"""), CFG);
    assertThat(compiled.ok()).as(compiled.errors().toString()).isTrue();
    TemplateKind.CompiledTool tool = compiled.tools().getFirst();
    assertThat(tool.definition().nature()).isEqualTo(ToolDefinition.Nature.READ);
    assertThat(tool.definition().inputSchema().path("required").toString()).isEqualTo("[\"status\"]");

    ToolCall call = new ToolCall("tc_1", "orders_by_status", "orders-db",
        JSON.createObjectNode().put("status", "it's"), new ToolCall.TemplateBinding("tpl_1", 1, "q", tool.binding()));
    assertThat(FakeSqlConnector.statement(call)).isEqualTo("SELECT id, status FROM orders WHERE status = 'it''s'");
    assertThat(fake.classify(call, CFG, null).kind()).isEqualTo(Classification.Kind.READ);

    TemplateKind.CompileResult change = kind.compile(JSON.readTree("""
        {"name":"cancel_order","sql":"UPDATE orders SET status = 'cancelled' WHERE id = :id",
         "params":{"id":{"type":"integer"}}}"""), CFG);
    assertThat(change.tools().getFirst().definition().nature()).isEqualTo(ToolDefinition.Nature.MUTATION);
    assertThat(change.tools().getFirst().definition().outputSchema()).isNull();
    assertThat(kind.compile(JSON.readTree("{\"name\":\"x\",\"sql\":\"SELECT :nope\"}"), CFG).errors())
        .containsExactly(":nope isn't a parameter");
  }

  private Classification classify(String tool, String sql, Env env) {
    JsonNode section = JSON.readTree(env == Env.PROD ? "{\"denyDdl\":true}" : "{\"denyDdl\":false}");
    return fake.classify(call(tool, sql), CFG, new PolicyView(env, env.id() + ".sql", section, null));
  }

  private static ToolCall call(String tool, String sql) {
    return new ToolCall("tc_1", tool, "orders-db", JSON.createObjectNode().put("sql", sql), null);
  }

  private static ExecContext context(boolean approved, JsonNode payload) {
    Limits limits = new Limits(1000, 1000, 512 * 1024, 1 << 20, 256 * 1024, 64 * 1024, 8192, Duration.ofSeconds(5),
        Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofHours(168));
    return new ExecContext() {
      @Override public String callId() { return "tc_1"; }
      @Override public String traceId() { return "0".repeat(32); }
      @Override public String traceparent() { return null; }
      @Override public Env env() { return Env.DEV; }
      @Override public Limits limits() { return limits; }
      @Override public Duration timeout() { return Duration.ofSeconds(10); }
      @Override public Instant deadline() { return Instant.now().plusSeconds(10); }
      @Override public boolean cancelled() { return false; }
      @Override public void onCancel(Runnable action) {}
      @Override public boolean approved() { return approved; }
      @Override public Optional<String> approvalId() { return approved ? Optional.of("ap_1") : Optional.empty(); }
      @Override public Optional<JsonNode> preparedState() { return Optional.empty(); }
      @Override public Optional<JsonNode> approvedPayload() { return Optional.ofNullable(payload); }
      @Override public System.Logger log() { return System.getLogger("fake"); }
      @Override public Clock clock() { return Clock.systemUTC(); }
      @Override public HandleCodec handles() { return null; }
      @Override public boolean acquire(String remoteQuotaKey, int permitsPerMinute) { return true; }
      @Override public Path workDir() { return Path.of("target"); }
      @Override public Egress egress() { return (request, maxBytes) -> { throw new UnsupportedOperationException(); }; }
      @Override public void taint(CharSequence value) {}
    };
  }
}
