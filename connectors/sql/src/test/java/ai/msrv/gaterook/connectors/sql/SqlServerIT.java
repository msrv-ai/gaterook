package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.TemplateKind;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Real SQL Server fixture: start the sqlserver Compose profile, then use -Pservices,sqlserver-it. */
@Tag("sqlserver-it")
class SqlServerIT {
  static final String DATABASE = "gaterook_sqlserver_it";
  static final String OTHER_DATABASE = "gaterook_sqlserver_other_it";
  static final String READER = "gaterook_sqlserver_reader_it";
  static final String READER_PASSWORD = "SqlServer-reader-pw-0001";
  static final Path WORK = Path.of("target", "sqlserver-work").toAbsolutePath();
  final SqlServerDialect dialect = new SqlServerDialect();
  final JdbcSqlConnector connector = new JdbcSqlConnector(dialect);

  @BeforeAll
  static void seed() throws Exception {
    assumeTrue(TestServices.enabled(), "SQL Server integration tests need the Compose test services");
    try (Connection c = admin("master"); Statement st = c.createStatement()) {
      for (String database : List.of(DATABASE, OTHER_DATABASE)) {
        st.execute("IF DB_ID('" + database + "') IS NOT NULL BEGIN ALTER DATABASE [" + database
            + "] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE [" + database + "]; END");
        st.execute("CREATE DATABASE [" + database + "]");
      }
      st.execute("IF SUSER_ID('" + READER + "') IS NOT NULL DROP LOGIN [" + READER + "]");
      st.execute("CREATE LOGIN [" + READER + "] WITH PASSWORD = '" + READER_PASSWORD + "', CHECK_POLICY = OFF");
    }
    try (Connection c = admin(DATABASE); Statement st = c.createStatement()) {
      st.execute("CREATE TABLE dbo.orders (id int NOT NULL PRIMARY KEY, name nvarchar(100), amount decimal(20,2), "
          + "active bit, placed datetimeoffset, payload varbinary(10))");
      st.execute("INSERT INTO dbo.orders VALUES (1, N'first', 12.30, 1, '2026-01-02T03:04:05+02:00', 0x0102), "
          + "(2, N'second', 45.60, 0, NULL, NULL)");
      st.execute("CREATE INDEX orders_name ON dbo.orders(name)");
      st.execute("CREATE TABLE dbo.items (id int PRIMARY KEY, order_id int REFERENCES dbo.orders(id))");
      st.execute("CREATE VIEW dbo.order_names AS SELECT id, name FROM dbo.orders");
      st.execute("CREATE USER [" + READER + "] FOR LOGIN [" + READER + "] WITH DEFAULT_SCHEMA = dbo");
      st.execute("GRANT SELECT ON SCHEMA::dbo TO [" + READER + "]");
      st.execute("GRANT VIEW DEFINITION TO [" + READER + "]");
    }
    try (Connection c = admin(OTHER_DATABASE); Statement st = c.createStatement()) {
      st.execute("CREATE TABLE dbo.orders (id int PRIMARY KEY)");
      st.execute("INSERT INTO dbo.orders VALUES (99)");
    }
  }

  static Connection admin(String database) throws Exception {
    Properties p = new Properties();
    p.setProperty("user", "sa");
    p.setProperty("password", TestServices.value("GW_SQLSERVER_PASSWORD"));
    p.setProperty("databaseName", database);
    p.setProperty("encrypt", "true");
    p.setProperty("trustServerCertificate", "true");
    p.setProperty("loginTimeout", "5");
    p.setProperty("socketTimeout", "30000");
    p.setProperty("queryTimeout", "20");
    p.setProperty("cancelQueryTimeout", "2");
    return new com.microsoft.sqlserver.jdbc.SQLServerDriver().connect(
        "jdbc:sqlserver://127.0.0.1:" + TestServices.port("GW_SQLSERVER_PORT"), p);
  }

  ConnectionConfig config(String user) {
    ConnectionConfig cfg = Fixtures.config("sqlserver", Env.DEV, "127.0.0.1",
        TestServices.port("GW_SQLSERVER_PORT"), DATABASE, user, null, null);
    ((ObjectNode) cfg.config().get("connection")).put("sslMode", "require");
    return cfg;
  }

  Fixtures.Secrets secrets(String user) {
    return Fixtures.Secrets.password(user.equals(READER) ? READER_PASSWORD : TestServices.value("GW_SQLSERVER_PASSWORD"));
  }

  Fixtures.Context context() {
    return Fixtures.Context.of(Env.DEV, WORK);
  }

  ToolResult query(ConnectionConfig cfg, String sql) throws Exception {
    ToolCall call = Fixtures.sql(SqlTools.QUERY, sql);
    assertThat(connector.classify(call, cfg, Fixtures.policy(Env.DEV, null)).kind()).isEqualTo(Classification.Kind.READ);
    return connector.execute(call, cfg, secrets(cfg.string("/credentials/user")), context());
  }

  @AfterEach
  void close() {
    connector.close();
  }

  @Test
  void connectsDiscoversAndInspectsReaderPermissions() {
    ConnectionConfig cfg = config(READER);
    var test = connector.test(cfg, secrets(READER), context());
    assertThat(test.ok()).as(test.toString()).isTrue();
    assertThat(test.serverVersion()).contains("SQL Server");
    assertThat(test.details().at("/tls/enabled").asBoolean()).isTrue();
    assertThat(connector.health(cfg, secrets(READER), context()).state())
        .isEqualTo(ai.msrv.gaterook.connector.api.HealthResult.State.HEALTHY);
    var discovery = connector.discoverDatabases(config("sa"), secrets("sa"), context());
    assertThat(discovery.ok()).as(discovery.toString()).isTrue();
    assertThat(discovery.details().path("databases").toString()).contains(DATABASE, OTHER_DATABASE);
  }

  @Test
  void queriesTopBracketIdentifiersAndSqlServerTypesWithLimits() throws Exception {
    ToolResult result = query(config(READER), "SELECT TOP (1) [id], [name], [amount], [active], [placed], [payload] "
        + "FROM [dbo].[orders] ORDER BY [id]");
    JsonNode row = result.table().rows().get(0);
    assertThat(row.get(0).asInt()).isEqualTo(1);
    assertThat(row.get(2).asString()).isEqualTo("12.30");
    assertThat(row.get(3).asBoolean()).isTrue();
    assertThat(row.get(4).asString()).isEqualTo("2026-01-02T03:04:05+02:00");
    assertThat(row.get(5).asString()).isEqualTo("\\x0102");
    ToolCall limited = Fixtures.call(SqlTools.QUERY,
        Fixtures.object().put("sql", "SELECT id FROM dbo.orders ORDER BY id").put("limit", 1));
    assertThat(connector.execute(limited, config(READER), secrets(READER), context()).table().rows()).hasSize(1);
  }

  @Test
  void listsAndDescribesTablesViewsKeysAndIndexes() throws Exception {
    ConnectionConfig cfg = config(READER);
    ToolCall list = Fixtures.call(SqlTools.LIST_SCHEMAS, Fixtures.object().put("schema", "dbo"));
    connector.classify(list, cfg, Fixtures.policy(Env.DEV, null));
    ToolResult result = connector.execute(list, cfg, secrets(READER), context());
    assertThat(result.structuredContent().toString()).contains("orders", "order_names");
    ToolCall describe = Fixtures.call(SqlTools.DESCRIBE_TABLE, Fixtures.object().put("table", "[dbo].[orders]"));
    ToolResult table = connector.execute(describe, cfg, secrets(READER), context());
    assertThat(table.structuredContent().path("columns")).hasSize(6);
    assertThat(table.structuredContent().path("primaryKey").toString()).contains("id");
    assertThat(table.structuredContent().path("indexes").toString()).contains("orders_name");
    ToolResult items = connector.execute(
        Fixtures.call(SqlTools.DESCRIBE_TABLE, Fixtures.object().put("table", "dbo.items")), cfg, secrets(READER), context());
    assertThat(items.structuredContent().path("foreignKeys").toString()).contains("order_id", "orders");
  }

  @Test
  void executesOnlyTheApprovedWriteAndLeavesPoolUsableAfterShowplan() throws Exception {
    ConnectionConfig cfg = config("sa");
    ToolCall update = Fixtures.sql(SqlTools.EXECUTE, "UPDATE dbo.orders SET name = N'approved' WHERE id = 1");
    assertThatThrownBy(() -> connector.execute(update, cfg, secrets("sa"), context())).isInstanceOf(ConnectorException.class);
    var prepared = connector.prepare(update, cfg, secrets("sa"), context());
    assertThat(prepared.preview()).isNotNull();
    assertThat(query(cfg, "SELECT name FROM dbo.orders WHERE id = 1").table().rows().get(0).get(0).asString()).isEqualTo("first");
    var payload = connector.describeAction(update, cfg).payload();
    ToolCall tampered = Fixtures.sql(SqlTools.EXECUTE, "DELETE FROM dbo.orders");
    ToolResult changed = connector.execute(tampered, cfg, secrets("sa"), context().approved(payload));
    assertThat(changed.structuredContent().path("committed").asBoolean()).isTrue();
    assertThat(changed.structuredContent().path("rowsAffected").asLong()).isEqualTo(1);
    assertThat(query(cfg, "SELECT name FROM dbo.orders WHERE id = 1").table().rows().get(0).get(0).asString()).isEqualTo("approved");
    try (Connection c = admin(DATABASE); Statement st = c.createStatement()) {
      st.execute("UPDATE dbo.orders SET name = N'first' WHERE id = 1");
    }
  }

  @Test
  void errorsRollbackChangesAndReaderCannotWrite() throws Exception {
    ConnectionConfig cfg = config("sa");
    ToolCall bad = Fixtures.sql(SqlTools.EXECUTE, "UPDATE dbo.orders SET id = 1 WHERE id = 2");
    assertThatThrownBy(() -> connector.execute(bad, cfg, secrets("sa"), context().approved(connector.describeAction(bad, cfg).payload())))
        .isInstanceOf(ConnectorException.class);
    assertThat(query(cfg, "SELECT COUNT(*) FROM dbo.orders").table().rows().get(0).get(0).asInt()).isEqualTo(2);
    ToolCall write = Fixtures.sql(SqlTools.EXECUTE, "UPDATE dbo.orders SET name = N'denied' WHERE id = 1");
    assertThatThrownBy(() -> connector.execute(write, config(READER), secrets(READER),
        context().approved(connector.describeAction(write, config(READER)).payload())))
        .hasMessageContaining("may not do this");
  }

  @Test
  void rejectsUnsafeTsqlEvenWhenApprovedWithoutClassification() {
    ConnectionConfig cfg = config("sa");
    for (String sql : List.of("SELECT * INTO dbo.unapproved FROM dbo.orders", "SELECT NEXT VALUE FOR dbo.seq",
        "SELECT 1; DELETE FROM dbo.orders", "EXEC('DELETE FROM dbo.orders')", "SELECT * FROM " + OTHER_DATABASE + ".dbo.orders")) {
      ToolCall call = Fixtures.sql(SqlTools.QUERY, sql);
      assertThatThrownBy(() -> connector.execute(call, cfg, secrets("sa"), context().approved(Fixtures.object().put("sql", sql))))
          .isInstanceOf(ConnectorException.class);
    }
  }

  @Test
  void selectedDatabaseRoutesCallsAndRequiresSelection() throws Exception {
    ConnectionConfig cfg = config("sa");
    ObjectNode connection = (ObjectNode) cfg.config().get("connection");
    connection.remove("database");
    connection.putArray("databases").add(DATABASE).add(OTHER_DATABASE);
    ToolCall call = Fixtures.call(SqlTools.QUERY,
        Fixtures.object().put("sql", "SELECT id FROM dbo.orders").put("database", OTHER_DATABASE));
    ToolResult result = connector.execute(call, cfg, secrets("sa"), context());
    assertThat(result.table().rows().get(0).get(0).asInt()).isEqualTo(99);
    assertThatThrownBy(() -> connector.execute(Fixtures.sql(SqlTools.QUERY, "SELECT 1"), cfg, secrets("sa"), context()))
        .isInstanceOf(ConnectorException.class);
  }

  @Test
  void templatesBindValuesIncludingUnicodeAndNullableTimestamps() throws Exception {
    ConnectionConfig cfg = config(READER);
    ObjectNode definition = Fixtures.json("""
        {"name":"order_lookup",
         "sql":"SELECT [id], :text AS label, CAST(:moment AS datetimeoffset) AS moment FROM [dbo].[orders] WHERE id = :id",
         "params":[{"name":"id","type":"integer"},{"name":"text","type":"string"},
                   {"name":"moment","type":"timestamp","required":false}]}
        """);
    TemplateKind kind = connector.templateKinds().getFirst();
    var compiled = kind.compile(definition, cfg);
    assertThat(compiled.errors()).isEmpty();
    var tool = compiled.tools().getFirst();
    ObjectNode args = Fixtures.object().put("id", 1).put("text", "O'Brien; 日本語");
    ToolCall call = new ToolCall("template-test", tool.definition().name(), cfg.name(), args,
        new ToolCall.TemplateBinding("lookup", 1, "q", tool.binding()));
    ToolResult result = connector.execute(call, cfg, secrets(READER), context());
    assertThat(result.table().rows().get(0).get(1).asString()).isEqualTo("O'Brien; 日本語");
    assertThat(result.table().rows().get(0).get(2).isNull()).isTrue();
  }

  @Test
  void preparedWriteEstimatesAndBindsTimestampValues() throws Exception {
    ConnectionConfig cfg = config("sa");
    ObjectNode definition = Fixtures.json("""
        {"name":"update_order","sql":"UPDATE dbo.orders SET placed = :moment WHERE id = :id",
         "params":[{"name":"id","type":"integer"},{"name":"moment","type":"timestamp"}]}
        """);
    var compiled = connector.templateKinds().getFirst().compile(definition, cfg);
    assertThat(compiled.errors()).isEmpty();
    var tool = compiled.tools().getFirst();
    ToolCall call = new ToolCall("update-template", tool.definition().name(), cfg.name(),
        Fixtures.object().put("id", 2).put("moment", "2026-02-03T04:05:06Z"),
        new ToolCall.TemplateBinding("update", 1, "q", tool.binding()));
    var prepared = connector.prepare(call, cfg, secrets("sa"), context());
    assertThat(prepared.preview()).as(prepared.toString()).isNotNull();
    assertThat(query(cfg, "SELECT placed FROM dbo.orders WHERE id = 2").table().rows().get(0).get(0).isNull()).isTrue();
    connector.execute(call, cfg, secrets("sa"), context().approved(prepared.preview().payload()));
    assertThat(query(cfg, "SELECT placed FROM dbo.orders WHERE id = 2").table().rows().get(0).get(0).asString())
        .isEqualTo("2026-02-03T04:05:06Z");
    try (Connection c = admin(DATABASE); Statement st = c.createStatement()) {
      st.execute("UPDATE dbo.orders SET placed = NULL WHERE id = 2");
    }
  }

  @Test
  void lockTimeoutStopsBlockedReadAndPoolRecovers() throws Exception {
    try (Connection blocker = admin(DATABASE); Statement lock = blocker.createStatement()) {
      blocker.setAutoCommit(false);
      lock.execute("UPDATE dbo.orders SET name = N'locked' WHERE id = 1");
      ConnectionConfig cfg = config(READER);
      ToolCall call = Fixtures.sql(SqlTools.QUERY, "SELECT name FROM dbo.orders WHERE id = 1");
      var context = context().with(Fixtures.limits(100, java.time.Duration.ofMillis(200)));
      assertThatThrownBy(() -> connector.execute(call, cfg, secrets(READER), context))
          .isInstanceOf(ConnectorException.class).hasMessageContaining("Statement timeout");
      blocker.rollback();
      assertThat(query(cfg, "SELECT name FROM dbo.orders WHERE id = 1").table().rows().get(0).get(0).asString())
          .isEqualTo("first");
    }
  }

  @Test
  void verifiesCertificatesByDefaultAndReportsBadLogins() {
    ConnectionConfig cfg = config(READER);
    ((ObjectNode) cfg.config().get("connection")).put("sslMode", "verify-full");
    var untrusted = connector.test(cfg, secrets(READER), context());
    assertThat(untrusted.ok()).isFalse();
    assertThat(untrusted.error().message()).containsIgnoringCase("certificate");
    var badPassword = connector.test(config(READER), Fixtures.Secrets.password("wrong-fixture-password"), context());
    assertThat(badPassword.ok()).isFalse();
    assertThat(badPassword.error().message()).containsIgnoringCase("login");
  }

}
