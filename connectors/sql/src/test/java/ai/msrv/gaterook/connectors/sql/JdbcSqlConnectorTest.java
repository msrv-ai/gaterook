package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

class JdbcSqlConnectorTest {

  private final JdbcSqlConnector postgres = new JdbcSqlConnector(new PostgresDialect());
  private final JdbcSqlConnector mysql = new JdbcSqlConnector(new MySqlDialect());

  @Test
  void postgresManifestIsTheCatalogCard() {
    ConnectorManifest m = postgres.manifest();

    assertThat(m.id()).isEqualTo("postgres");
    assertThat(m.kind()).isEqualTo(ConnectorManifest.Kind.BUILT_IN);
    assertThat(m.name()).isEqualTo("PostgreSQL");
    assertThat(m.group()).isEqualTo("Databases");
    assertThat(m.icon()).isEqualTo("database");
    assertThat(m.dynamicTools()).isFalse();
    assertThat(m.configSchema().at("/properties/connection/properties/port/default").asInt()).isEqualTo(5432);
    assertThat(m.configSchema().at("/properties/credentials/properties/password/writeOnly").asBoolean()).isTrue();
    assertThat(m.policySchema().path("x-policy-section").asString()).isEqualTo("sql");
  }

  @Test
  void bothDialectsPublishTheSameFourTools() {
    assertThat(postgres.manifest().tools()).extracting(ToolDefinition::name)
        .containsExactly("sql_list_schemas", "sql_describe_table", "sql_query", "sql_execute");
    // Same schemas, so one endpoint with PostgreSQL and MySQL merges them (ARCHITECTURE.md §5 step 4).
    assertThat(mysql.manifest().tools()).isEqualTo(postgres.manifest().tools());
    assertThat(mysql.manifest().id()).isEqualTo("mysql");
    assertThat(mysql.manifest().configSchema().at("/properties/connection/properties/port/default").asInt())
        .isEqualTo(3306);
    assertThat(mysql.manifest().configSchema().at("/properties/connection/required").toString())
        .doesNotContain("database");
  }

  @Test
  void onlySqlExecuteMutatesAndItWaitsForApproval() {
    for (ToolDefinition t : postgres.manifest().tools()) {
      assertThat(t.inputSchema().at("/required").toString()).as(t.name()).contains("\"connection\"");
      if (t.name().equals("sql_execute")) {
        assertThat(t.nature()).isEqualTo(Nature.MUTATION);
        assertThat(t.defaultMode()).isEqualTo(Mode.APPROVAL);
        assertThat(t.readOnlyModeAllowed()).isFalse();
        assertThat(t.outputSchema()).isNull();
      } else {
        assertThat(t.nature()).as(t.name()).isEqualTo(Nature.READ);
        assertThat(t.annotations().readOnlyHint()).as(t.name()).isTrue();
        assertThat(t.outputSchema()).as(t.name()).isNotNull();
      }
    }
  }

  @Test
  void theExactActionIsTheSqlByteForByte() {
    // A right-to-left override and a zero-width space stay as they are: the UI shows them escaped.
    String sql = "UPDATE orders SET note = 'ok\u202E' WHERE id = 48213\u200B";
    ToolCall call = new ToolCall("tc_2", "sql_execute", "dev-db", JsonNodeFactory.instance.objectNode().put("sql", sql),
        null);

    ActionPreview p = postgres.describeAction(call, devDb());

    assertThat(p.text()).isEqualTo(sql);
    assertThat(p.payload().path("sql").asString()).isEqualTo(sql);
    assertThat(p.kind()).isEqualTo(ActionPreview.Kind.SQL);
    assertThat(p.language()).isEqualTo("sql");
    assertThat(p.target()).isEqualTo("dev-db · db.dev.internal:5432/app");
    assertThat(p.summary()).isEqualTo("sql_execute on dev-db: UPDATE orders");
    assertThat(p.impact()).containsExactly(new ActionPreview.Field("Target table", "orders"),
        new ActionPreview.Field("Transaction", "Yes, rolled back on error"));
    assertThat(p.auditFacets()).containsEntry("statement", "UPDATE").containsEntry("tables", "orders");
  }

  @Test
  void aChangeTheGatewayCantAnalyzeSaysSo() {
    ToolCall call = new ToolCall("tc_3", "sql_execute", "dev-db",
        JsonNodeFactory.instance.objectNode().put("sql", "DO $$ BEGIN DELETE FROM orders; END $$"), null);

    ActionPreview p = postgres.describeAction(call, devDb());

    assertThat(p.warnings()).singleElement().satisfies(w -> assertThat(w).startsWith("Not analyzed by the gateway"));
    assertThat(p.impact()).contains(new ActionPreview.Field("Target table", "Unknown: not analyzed"));
  }

  @Test
  void aMysqlSchemaChangeCantBeRolledBackAndTheApproverIsTold() {
    ToolCall call = new ToolCall("tc_4", "sql_execute", "dev-db",
        JsonNodeFactory.instance.objectNode().put("sql", "ALTER TABLE orders ADD COLUMN x int"), null);
    ConnectionConfig cfg = new ConnectionConfig("c2", "dev-mysql", "mysql", Env.DEV, 1, devDb().config(),
        Set.of("/credentials/password"), null);

    ActionPreview p = mysql.describeAction(call, cfg);

    assertThat(p.impact()).contains(new ActionPreview.Field("Transaction", "No: MySQL commits a schema change at "
        + "once"));
    assertThat(p.warnings()).contains("MySQL commits a schema change at once: it can't be rolled back.");
  }

  @Test
  void withoutApprovalNothingThatMayWriteReachesTheDatabase() {
    // No server listens: each refusal comes before a connection.
    Fixtures.Context ctx = Fixtures.Context.of(Env.DEV, null);
    for (String[] c : new String[][] {{"sql_execute", "UPDATE orders SET status = 'x'"},
        {"sql_query", "SELECT my_wrapper()"}, {"sql_query", "DELETE FROM orders"},
        {"sql_query", "SELECT pg_terminate_backend(1)"}, {"sql_execute", "BEGIN"}}) {
      ToolCall call = new ToolCall("tc_5", c[0], "dev-db", JsonNodeFactory.instance.objectNode().put("sql", c[1]),
          null);
      assertThatThrownBy(() -> postgres.execute(call, devDb(), Fixtures.Secrets.none(), ctx)).as(c[1])
          .isInstanceOfSatisfying(ConnectorException.class,
              e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    }
  }

  @Test
  void anUnknownToolIsDenied() {
    ToolCall call = new ToolCall("tc_6", "sql_drop_everything", "dev-db", JsonNodeFactory.instance.objectNode(), null);

    Classification c = postgres.classify(call, devDb(), Fixtures.policy(Env.DEV, null));

    assertThat(c.ruleId()).isEqualTo("dev.sql.unknown-tool");
  }

  @Test
  void describeConnectionSaysHowCallsRunWithoutIo() throws Exception {
    JsonNode d = postgres.describe(Fixtures.withMetadata(devDb(), Fixtures.json(
        "{\"serverVersion\": \"PostgreSQL 16\"}")), null, null, "orders").orElseThrow();

    assertThat(d.path("server").asString()).isEqualTo("PostgreSQL 16");
    assertThat(d.path("database").asString()).isEqualTo("app");
    assertThat(d.path("defaultSchema").asString()).isEqualTo("public");
    assertThat(d.path("table").asString()).isEqualTo("Call sql_describe_table with table orders.");
  }

  @Test
  void onlyThePasswordOfANamedUserRotates() {
    assertThat(postgres.canRotate(devDb(), "/credentials/password")).isTrue();
    assertThat(postgres.canRotate(devDb(), "/connection/caCertificate")).isFalse();
  }

  @Test
  void theListingPolicyOfACallIsTakenOnce() {
    JdbcSqlConnector.CallPolicies policies = new JdbcSqlConnector.CallPolicies();
    SqlRules.SqlPolicy p = SqlRules.SqlPolicy.of(Fixtures.policy(Env.DEV, null), devDb(), false);

    policies.remember("tc_a", p);

    assertThat(policies.take("tc_a")).isSameAs(p);
    assertThat(policies.take("tc_a")).isNull();
    assertThat(policies.take(null)).isNull();
    for (int i = 0; i <= JdbcSqlConnector.CallPolicies.MAX; i++) {
      policies.remember("tc_" + i, p);
    }
    assertThat(policies.take("tc_" + JdbcSqlConnector.CallPolicies.MAX)).isSameAs(p);
  }

  @Test
  void shortLivedLoginsGetPoolsThatEndBeforeTheLease() {
    assertThat(SqlPools.lifetime(null)).isEqualTo(Duration.ofMinutes(10));
    assertThat(SqlPools.lifetime(Instant.now().plus(Duration.ofHours(1)))).isEqualTo(Duration.ofMinutes(10));
    assertThat(SqlPools.lifetime(Instant.now().plus(Duration.ofMinutes(5))))
        .isBetween(Duration.ofSeconds(235), Duration.ofSeconds(240));
    // Hikari's floor: a lease about to end still gets 30 s, the pool is replaced with the next lease anyway.
    assertThat(SqlPools.lifetime(Instant.now().plus(Duration.ofSeconds(20)))).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void aChangeResultSaysWhatEachStatementDid() {
    ToolResult r = JdbcSqlConnector.changeResult(List.of(new SqlExecutor.Applied("UPDATE", 1, null, false),
        new SqlExecutor.Applied("DELETE", 3, null, false), new SqlExecutor.Applied("CREATE TABLE", 0, null, false)));

    assertThat(r.content().getFirst().text()).isEqualTo("Committed 3 statements: 1 row updated, 3 rows deleted, "
        + "CREATE TABLE done");
    assertThat(r.structuredContent().path("rowsAffected").asLong()).isEqualTo(4);
    assertThat(JdbcSqlConnector.changeResult(List.of(new SqlExecutor.Applied("INSERT", 2, null, false))).content()
        .getFirst().text()).isEqualTo("2 rows inserted");
  }

  private static ConnectionConfig devDb() {
    return Fixtures.config("postgres", Env.DEV, "db.dev.internal", 5432, "app", "app_ro", null, null);
  }
}
