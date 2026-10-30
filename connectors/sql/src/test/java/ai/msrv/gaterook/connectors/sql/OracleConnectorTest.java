package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import oracle.jdbc.OracleTypes;

class OracleConnectorTest {
  private final JdbcSqlConnector oracle = new JdbcSqlConnector(new OracleDialect());

  private ConnectionConfig config() {
    return Fixtures.config("oracle", Env.DEV, "db.example.test", 1521, "FREEPDB1", "app", null, null);
  }

  @Test
  void sharesSqlToolsAndExposesOracleConnectionAndTemplates() {
    assertThat(oracle.manifest().id()).isEqualTo("oracle");
    assertThat(oracle.manifest().tools()).isEqualTo(new JdbcSqlConnector(new MySqlDialect()).manifest().tools());
    assertThat(oracle.templateKinds().getFirst().connectionTypes()).containsExactly("oracle");
    var description = oracle.describe(config(), null, null, null).orElseThrow();
    assertThat(description.path("serviceName").asString()).isEqualTo("FREEPDB1");
    assertThat(description.path("defaultSchema").asString()).isEqualTo("APP");
    assertThat(description.path("changes").asString()).contains("One statement");
  }

  @Test
  void ddlApprovalExplainsImplicitCommitAndPreservesExactSql() {
    String sql = "ALTER TABLE orders ADD note VARCHAR2(50);";
    var preview = oracle.describeAction(Fixtures.sql("sql_execute", sql), config());
    assertThat(preview.text()).isEqualTo(sql);
    assertThat(preview.payload().path("sql").asString()).isEqualTo(sql);
    assertThat(preview.impact()).contains(new ActionPreview.Field("Transaction",
        "No: Oracle Database commits a schema change at once"));
    assertThat(preview.warnings()).anySatisfy(w -> assertThat(w).contains("can't be rolled back"));
  }

  @Test
  void unsafeReadsAndUnapprovedChangesNeverReachTheDatabase() {
    for (String sql : List.of("DELETE FROM orders", "SELECT app.write_data() FROM DUAL",
        "SELECT DBMS_RANDOM.VALUE() FROM DUAL", "SELECT * FROM orders@remote_db",
        "SELECT orders_seq.NEXTVAL FROM DUAL", "SELECT * FROM orders FOR UPDATE")) {
      assertThatThrownBy(() -> oracle.execute(Fixtures.sql("sql_query", sql), config(), Fixtures.Secrets.none(),
          Fixtures.Context.of(Env.DEV, null)))
          .as(sql).isInstanceOfSatisfying(ConnectorException.class,
              e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    }
    assertThatThrownBy(() -> oracle.execute(Fixtures.sql("sql_execute", "DELETE FROM orders"), config(),
        Fixtures.Secrets.none(), Fixtures.Context.of(Env.DEV, null)))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
  }

  @Test
  void approvalCannotRunPlsqlOrMultipleStatements() {
    for (String sql : List.of("BEGIN NULL; END;", "UPDATE orders SET id = 2; DELETE FROM orders", "COMMIT")) {
      assertThatThrownBy(() -> oracle.execute(Fixtures.sql("sql_execute", sql), config(), Fixtures.Secrets.none(),
          Fixtures.Context.of(Env.DEV, null).approved(Fixtures.object().put("sql", sql))))
          .as(sql).isInstanceOfSatisfying(ConnectorException.class,
              e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    }
  }

  @Test
  void jdbcRemovesOnlyTheClientTerminatorAfterLexingLiterals() throws Exception {
    var statement = oracle.executable(SqlExecutor.BoundSql.plain("SELECT q'[text;still text]' FROM DUAL; -- end"));
    assertThat(statement.sql()).isEqualTo("SELECT q'[text;still text]' FROM DUAL");
    assertThatThrownBy(() -> oracle.executable(SqlExecutor.BoundSql.plain("SELECT 1 FROM DUAL; SELECT 2 FROM DUAL")))
        .isInstanceOf(ConnectorException.class);
  }

  @Test
  void oracleBooleanParametersUseNumbersForPre23cDatabases() throws Exception {
    PreparedStatement statement = mock(PreparedStatement.class);
    TemplateParams.bind(statement, List.of(new TemplateParams.Bound("enabled", TemplateParams.Type.BOOLEAN, true),
        new TemplateParams.Bound("missing", TemplateParams.Type.BOOLEAN, null)), SqlSyntax.ORACLE);
    verify(statement).setInt(1, 1);
    verify(statement).setNull(2, Types.NUMERIC);
  }

  @Test
  void oracleStatementTimeoutHasNoGraceForAMissingServerTimeout() throws Exception {
    Connection connection = mock(Connection.class);
    Statement statement = mock(Statement.class);
    when(connection.createStatement()).thenReturn(statement);
    new SqlExecutor(new OracleDialect()).statement(connection, Fixtures.Context.of(Env.DEV, null), 1000,
        SqlExecutor.BoundSql.plain("SELECT 1 FROM DUAL"));
    verify(statement).setQueryTimeout(1);
  }

  @Test
  void oracleRowsKeepColumnOriginsAndTimestampOffsets() throws Exception {
    ResultSet rows = mock(ResultSet.class);
    ResultSetMetaData metadata = mock(ResultSetMetaData.class);
    when(rows.getMetaData()).thenReturn(metadata);
    when(rows.next()).thenReturn(true, false);
    when(metadata.getColumnCount()).thenReturn(1);
    when(metadata.getColumnLabel(1)).thenReturn("created");
    when(metadata.getColumnName(1)).thenReturn("CREATED_AT");
    when(metadata.getSchemaName(1)).thenReturn("APP");
    when(metadata.getTableName(1)).thenReturn("ORDERS");
    when(metadata.getColumnType(1)).thenReturn(OracleTypes.TIMESTAMPTZ);
    when(metadata.getColumnTypeName(1)).thenReturn("TIMESTAMP WITH TIME ZONE");
    when(rows.getObject(1, OffsetDateTime.class)).thenReturn(OffsetDateTime.parse("2026-10-29T12:30:00+02:00"));
    var result = ResultReader.read(rows, 10, 10000, 1000, SqlSyntax.ORACLE, false);
    assertThat(result.table().columns().getFirst().sourceSchema()).isEqualTo("APP");
    assertThat(result.table().columns().getFirst().sourceTable()).isEqualTo("ORDERS");
    assertThat(result.table().rows().get(0).get(0).asString()).isEqualTo("2026-10-29T12:30+02:00");
  }

  @Test
  void oracleErrorsExplainReadOnlyFailuresAndTimeouts() {
    var readOnly = SqlErrors.of(new SQLException("ORA-01456", "72000", 1456), SqlSyntax.ORACLE,
        "test-db", "dev", Duration.ofSeconds(3));
    assertThat(readOnly.getMessage()).contains("read-only transaction refused it");
    var timeout = SqlErrors.of(new SQLException("ORA-01013", "72000", 1013), SqlSyntax.ORACLE,
        "test-db", "dev", Duration.ofSeconds(3));
    assertThat(timeout.getMessage()).contains("FETCH FIRST").doesNotContain("LIMIT");
    assertThat(SqlErrors.unreachable(new SQLException("ORA-12514", "66000", 12514), "66000", SqlSyntax.ORACLE)).isTrue();
    for (int code : List.of(1017, 1033, 1034)) {
      assertThat(SqlErrors.of(new SQLException("File error", "HY000", code), SqlSyntax.MYSQL,
          "test-db", "dev", Duration.ofSeconds(3)).kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
    }
  }
}
