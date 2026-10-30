package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.TemplateKind;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.sql.SqlExecutor.BoundSql;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import tools.jackson.databind.JsonNode;

/** Live Oracle checks against Compose or an explicitly configured disposable schema; see docs/oracle.md. */
@EnabledIf("configured")
class OracleIT {
  private static final OracleDialect DIALECT = new OracleDialect();
  private static final Path WORK = Path.of("target", "oracle-work").toAbsolutePath();
  private static final String CUSTOMERS = "GW_ORACLE_IT_CUSTOMERS";
  private static final String ORDERS = "GW_ORACLE_IT_ORDERS";
  private static final String QUOTED = "\"GateRook mixed table\"";
  private static boolean fixtureStarted;
  private static OracleTestSettings settings;
  private final JdbcSqlConnector connector = new JdbcSqlConnector(DIALECT);

  static boolean configured() {
    return OracleTestSettings.configured();
  }

  private static ConnectionConfig cfg() {
    return Fixtures.config("oracle", Env.DEV, settings.host(), settings.port(), settings.service(), settings.user(),
        null, null);
  }

  private static Fixtures.Secrets secrets() {
    return Fixtures.Secrets.password(settings.password());
  }

  private static Fixtures.Context ctx() {
    return Fixtures.Context.of(Env.DEV, WORK);
  }

  private static Connection direct() throws Exception {
    ConnectionSettings settings = ConnectionSettings.of(cfg(), DIALECT);
    Properties properties = DIALECT.properties(settings, WORK, 15_000);
    properties.setProperty("user", settings.user());
    properties.setProperty("password", OracleIT.settings.password());
    return DIALECT.driver().connect(DIALECT.url(settings), properties);
  }

  @BeforeAll
  static void createFixture() throws Exception {
    settings = OracleTestSettings.current();
    try (Connection c = direct(); Statement st = c.createStatement()) {
      dropFixture(st);
      fixtureStarted = true;
      try {
        st.execute("CREATE TABLE " + CUSTOMERS + " (ID NUMBER(10) PRIMARY KEY, EMAIL VARCHAR2(100))");
        st.execute("CREATE TABLE " + ORDERS + " (ID NUMBER(10) PRIMARY KEY, CUSTOMER_ID NUMBER(10) "
            + "REFERENCES " + CUSTOMERS + "(ID), STATUS VARCHAR2(20), TOTAL NUMBER(12,2), NOTE CLOB, CREATED_AT TIMESTAMP)");
        st.execute("CREATE INDEX GW_ORACLE_IT_STATUS_IDX ON " + ORDERS + "(STATUS)");
        st.execute("CREATE TABLE " + QUOTED + " (\"Key\" NUMBER(10) PRIMARY KEY, \"Value\" VARCHAR2(20))");
        st.executeUpdate("INSERT INTO " + CUSTOMERS + " VALUES (1, 'reader@example.test')");
        st.executeUpdate("INSERT INTO " + ORDERS + " VALUES (1, 1, 'pending', 120.50, 'fixture note', "
            + "TIMESTAMP '2026-01-02 03:04:05')");
        st.executeUpdate("INSERT INTO " + ORDERS + " VALUES (2, 1, 'paid', 42.25, NULL, "
            + "TIMESTAMP '2026-01-03 04:05:06')");
        st.executeUpdate("INSERT INTO " + QUOTED + " VALUES (1, 'quoted')");
      } catch (Exception failure) {
        try { dropFixture(st); }
        catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
        fixtureStarted = false;
        throw failure;
      }
    }
  }

  @AfterAll
  static void removeFixture() throws Exception {
    if (!fixtureStarted) return;
    try (Connection c = direct(); Statement st = c.createStatement()) { dropFixture(st); }
  }

  private static void dropFixture(Statement st) throws SQLException {
    for (String table : List.of(ORDERS, CUSTOMERS, QUOTED)) {
      try { st.execute("DROP TABLE " + table + " CASCADE CONSTRAINTS PURGE"); }
      catch (SQLException e) { if (e.getErrorCode() != 942) throw e; }
    }
  }

  @AfterEach
  void close() { connector.close(); }

  private static long count(String sql) throws Exception {
    try (Connection c = direct(); Statement st = c.createStatement(); ResultSet rows = st.executeQuery(sql)) {
      assertThat(rows.next()).isTrue();
      return rows.getLong(1);
    }
  }

  @Test
  void connectionTestAndHealthUseTheOracleServiceAndReportItsIdentity() {
    TestResult tested = connector.test(cfg(), secrets(), ctx());
    assertThat(tested.ok()).as(String.valueOf(tested.error())).isTrue();
    assertThat(tested.serverVersion()).contains("Oracle");
    assertThat(tested.identity()).isEqualTo(settings.user().toUpperCase(java.util.Locale.ROOT));
    assertThat(tested.permissions().level()).isNotEqualTo(TestResult.Level.ADMIN);
    assertThat(tested.permissions().write()).isNotEmpty();
    assertThat(connector.health(cfg(), secrets(), ctx()).state()).isEqualTo(HealthResult.State.HEALTHY);
  }

  @Test
  void queryReturnsNumbersClobsTimestampsAndOracleQuotedLiterals() throws Exception {
    ToolCall call = Fixtures.sql("sql_query", "SELECT o.ID, o.TOTAL, o.NOTE, o.CREATED_AT, c.EMAIL "
        + "FROM " + ORDERS + " o JOIN " + CUSTOMERS + " c ON c.ID = o.CUSTOMER_ID WHERE o.ID = 1");
    assertThat(connector.classify(call, cfg(), Fixtures.policy(Env.DEV, null)).kind())
        .isEqualTo(Classification.Kind.READ);
    JsonNode row = connector.execute(call, cfg(), secrets(), ctx()).table().rows().get(0);
    assertThat(row.get(0).asLong()).isEqualTo(1);
    assertThat(row.get(1).isString()).isTrue();
    assertThat(new BigDecimal(row.get(1).asString())).isEqualByComparingTo("120.50");
    assertThat(row.get(2).asString()).isEqualTo("fixture note");
    assertThat(row.get(3).asString()).startsWith("2026-01-02T03:04:05");
    assertThat(row.get(4).asString()).isEqualTo("reader@example.test");
    ToolResult literal = connector.execute(Fixtures.sql("sql_query", "SELECT q'[it's :literal]' FROM DUAL"),
        cfg(), secrets(), ctx());
    assertThat(literal.table().rows().get(0).get(0).asString()).isEqualTo("it's :literal");
  }

  @Test
  void approvalRunsTheStoredSqlAndCommitsOnlyThatChange() throws Exception {
    ToolCall approved = Fixtures.sql("sql_execute", "UPDATE " + ORDERS + " SET STATUS = 'approved' WHERE ID = 2");
    assertThatThrownBy(() -> connector.execute(approved, cfg(), secrets(), ctx()))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    ActionPreview preview = connector.describeAction(approved, cfg());
    ToolCall changed = Fixtures.sql("sql_execute", "DELETE FROM " + ORDERS);
    ToolResult result = connector.execute(changed, cfg(), secrets(), ctx().approved(preview.payload()));
    assertThat(result.content().getFirst().text()).isEqualTo("1 row updated");
    assertThat(count("SELECT COUNT(*) FROM " + ORDERS)).isEqualTo(2);
    assertThat(count("SELECT COUNT(*) FROM " + ORDERS + " WHERE ID = 2 AND STATUS = 'approved'")).isEqualTo(1);
  }

  @Test
  void explicitReadOnlyTransactionRejectsDmlAndThePoolRemainsUsable() throws Exception {
    assertThatThrownBy(() -> connector.runRead(cfg(), ConnectionSettings.of(cfg(), DIALECT), secrets(), ctx(),
        BoundSql.plain("DELETE FROM " + ORDERS), 10))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.getMessage()).contains("read-only"));
    assertThat(count("SELECT COUNT(*) FROM " + ORDERS)).isEqualTo(2);
    ToolResult next = connector.execute(Fixtures.sql("sql_query", "SELECT 1 FROM DUAL"), cfg(), secrets(), ctx());
    assertThat(next.table().rows().get(0).get(0).asInt()).isEqualTo(1);
  }

  @Test
  void introspectionIncludesKeysIndexesAndCaseSensitiveNames() throws Exception {
    ToolCall list = Fixtures.call("sql_list_schemas", Fixtures.object());
    connector.classify(list, cfg(), Fixtures.policy(Env.DEV, null));
    ToolResult listing = connector.execute(list, cfg(), secrets(), ctx());
    assertThat(listing.structuredContent().path("tables").findValuesAsString("name"))
        .contains(CUSTOMERS, ORDERS, "GateRook mixed table");
    assertThat(PostgresIT.validate(connector.manifest().tools().getFirst().outputSchema(), listing.structuredContent()))
        .isEmpty();
    ToolResult result = connector.execute(Fixtures.call("sql_describe_table", Fixtures.object().put("table", ORDERS)),
        cfg(), secrets(), ctx());
    JsonNode table = result.structuredContent();
    assertThat(table.path("primaryKey").toString()).isEqualTo("[\"ID\"]");
    assertThat(table.path("foreignKeys").get(0).path("refTable").asString()).isEqualTo(CUSTOMERS);
    assertThat(table.path("indexes").findValuesAsString("name")).contains("GW_ORACLE_IT_STATUS_IDX");
    assertThat(PostgresIT.validate(connector.manifest().tools().get(1).outputSchema(), table)).isEmpty();
    JsonNode quoted = connector.execute(Fixtures.call("sql_describe_table", Fixtures.object().put("table", QUOTED)),
        cfg(), secrets(), ctx()).structuredContent();
    assertThat(quoted.path("name").asString()).isEqualTo("GateRook mixed table");
    assertThat(quoted.path("primaryKey").toString()).isEqualTo("[\"Key\"]");
    JsonNode dual = connector.execute(Fixtures.call("sql_describe_table", Fixtures.object().put("table", "SYS.DUAL")),
        cfg(), secrets(), ctx()).structuredContent();
    assertThat(dual.path("schema").asString()).isEqualTo("SYS");
    assertThat(dual.path("name").asString()).isEqualTo("DUAL");
    assertThat(dual.path("columns").findValuesAsString("name")).contains("DUMMY");
    assertThat(connector.execute(Fixtures.sql("sql_query", "SELECT \"Value\" FROM " + QUOTED),
        cfg(), secrets(), ctx()).table().rows().get(0).get(0).asString()).isEqualTo("quoted");
  }

  @Test
  void templatesBindEverySupportedTypeAndTypedNulls() throws Exception {
    TemplateKind.CompileResult compiled = connector.templateKinds().getFirst().compile(Fixtures.json("""
        {"name":"oracle_types", "sql":"SELECT :text AS TXT, :id AS ID, :amount AS AMOUNT, :flag AS FLAG, CAST(:day AS DATE) AS DAY_VALUE, CAST(:time AS TIMESTAMP WITH TIME ZONE) AS TIME_VALUE, CAST(:missing AS VARCHAR2(20)) AS MISSING, CAST(:missing_time AS TIMESTAMP WITH TIME ZONE) AS MISSING_TIME, CAST(:missing_flag AS NUMBER) AS MISSING_FLAG FROM DUAL",
         "params":[{"name":"text","type":"string"}, {"name":"id","type":"integer"},
                   {"name":"amount","type":"number"}, {"name":"flag","type":"boolean"},
                   {"name":"day","type":"date"}, {"name":"time","type":"timestamp"},
                   {"name":"missing","type":"string","required":false},
                   {"name":"missing_time","type":"timestamp","required":false},
                   {"name":"missing_flag","type":"boolean","required":false}]}
        """), cfg());
    assertThat(compiled.errors()).isEmpty();
    ToolCall call = new ToolCall("tc_oracle_types", "oracle_types", cfg().name(), Fixtures.json("""
        {"text":"x' OR '1'='1", "id":17, "amount":12.5, "flag":true,
         "day":"2026-01-02", "time":"2026-01-02T03:04:05+02:00"}
        """), new ToolCall.TemplateBinding("tpl-oracle", 1, "query", compiled.tools().getFirst().binding()));
    JsonNode row = connector.execute(call, cfg(), secrets(), ctx()).table().rows().get(0);
    assertThat(row.get(0).asString()).isEqualTo("x' OR '1'='1");
    assertThat(row.get(1).asLong()).isEqualTo(17);
    assertThat(row.get(2).asString()).isEqualTo("12.5");
    assertThat(row.get(3).asInt()).isEqualTo(1);
    assertThat(row.get(4).asString()).startsWith("2026-01-02");
    assertThat(row.get(5).asString()).contains("2026-01-02T03:04:05").endsWith("+02:00");
    assertThat(row.get(6).isNull()).isTrue();
    assertThat(row.get(7).isNull()).isTrue();
    assertThat(row.get(8).isNull()).isTrue();
  }

  @Test
  void rowLimitTruncatesARealOracleResult() throws Exception {
    ToolResult result = connector.execute(Fixtures.call("sql_query", Fixtures.object()
        .put("sql", "SELECT ID FROM " + ORDERS + " ORDER BY ID").put("limit", 100)), cfg(), secrets(),
        ctx().with(Fixtures.limits(1, Duration.ofSeconds(5))));
    assertThat(result.table().rows()).hasSize(1);
    assertThat(result.truncatedReason()).isEqualTo("row_limit");
  }

  @Test
  void timeoutCancelsTheStatementAndAllowsTheNextRead() throws Exception {
    long started = System.nanoTime();
    assertThatThrownBy(() -> connector.runRead(cfg(), ConnectionSettings.of(cfg(), DIALECT), secrets(),
        ctx().with(Fixtures.limits(10, Duration.ofMillis(500))),
        BoundSql.plain("WITH numbers AS (SELECT LEVEL AS n FROM DUAL CONNECT BY LEVEL <= 1000) "
            + "SELECT COUNT(*) FROM numbers a CROSS JOIN numbers b CROSS JOIN numbers c "
            + "WHERE MOD(a.n * b.n + c.n, 97) = 0"), 10))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.getMessage().toLowerCase(java.util.Locale.ROOT)).contains("timeout"));
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(8));
    assertThat(connector.execute(Fixtures.sql("sql_query", "SELECT 1 FROM DUAL"), cfg(), secrets(), ctx())
        .table().rows().get(0).get(0).asInt()).isEqualTo(1);
  }
}
