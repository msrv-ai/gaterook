package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import tools.jackson.databind.node.ObjectNode;

class SqlServerDialectTest {

  private final SqlServerDialect dialect = new SqlServerDialect();

  @Test
  void defaultsAreSqlServerSpecificAndPropertiesCannotInjectOptions() throws Exception {
    ConnectionConfig cfg = config("verify-full");
    ((ObjectNode) cfg.config().get("connection")).remove("port");
    ((ObjectNode) cfg.config().get("connection")).put("database", "app;encrypt=false;}");
    ConnectionSettings settings = ConnectionSettings.of(cfg, dialect);

    assertThat(settings.port()).isEqualTo(1433);
    assertThat(settings.defaultSchema()).isEqualTo("dbo");
    assertThat(settings.allowPublicKeyRetrieval()).isFalse();
    assertThat(dialect.url(settings)).isEqualTo("jdbc:sqlserver://");
    assertThat(dialect.driver().acceptsURL(dialect.url(settings))).isTrue();
    assertThat(dialect.properties(settings, null, 7_000))
        .containsEntry("databaseName", "app;encrypt=false;}")
        .containsEntry("serverName", "127.0.0.1")
        .containsEntry("encrypt", "true")
        .containsEntry("trustServerCertificate", "false")
        .containsEntry("authentication", "SqlPassword")
        .containsEntry("integratedSecurity", "false")
        .containsEntry("connectRetryCount", "0")
        .containsEntry("socketTimeout", "7000")
        .containsEntry("loginTimeout", "5");
  }

  @ParameterizedTest
  @CsvSource({"verify-full,true,false", "require,true,true", "disable,false,true"})
  void tlsModesHaveExplicitSemantics(String mode, String encrypt, String trust) throws Exception {
    assertThat(dialect.properties(ConnectionSettings.of(config(mode), dialect), null, 5_000))
        .containsEntry("encrypt", encrypt).containsEntry("trustServerCertificate", trust);
  }

  @Test
  void databaseRoutingKeepsExpectedSchemaAndCredentials() {
    ConnectionSettings original = ConnectionSettings.of(config("verify-full"), dialect);
    ConnectionSettings routed = original.withDatabase("second", dialect);
    assertThat(routed.database()).isEqualTo("second");
    assertThat(routed.defaultSchema()).isEqualTo("dbo");
    assertThat(routed.user()).isEqualTo(original.user());
  }

  @Test
  void preludeRejectsUnexpectedDefaultSchema() throws Exception {
    Connection c = mock(Connection.class);
    Statement st = mock(Statement.class);
    ResultSet rs = mock(ResultSet.class);
    when(c.createStatement()).thenReturn(st);
    when(st.executeQuery("SELECT SCHEMA_NAME()")).thenReturn(rs);
    when(rs.next()).thenReturn(true);
    when(rs.getString(1)).thenReturn("different");
    assertThatThrownBy(() -> dialect.prelude(c, SqlDialect.ServerInfo.parse("SQL Server", "16.0"),
        ConnectionSettings.of(config("verify-full"), dialect), 5_000, true))
        .isInstanceOf(SQLException.class).hasMessageContaining("default schema");
  }

  @Test
  void showplanIsResetEvenWhenPlanFails() throws Exception {
    Connection c = mock(Connection.class);
    Statement on = mock(Statement.class);
    Statement off = mock(Statement.class);
    when(c.createStatement()).thenReturn(on, off);
    assertThatThrownBy(() -> SqlServerDialect.withShowPlan(c, () -> {
      throw new SQLException("plan denied");
    })).isInstanceOf(SQLException.class).hasMessage("plan denied");
    InOrder order = inOrder(on, off);
    order.verify(on).execute("SET SHOWPLAN_XML ON");
    order.verify(on).close();
    order.verify(off).execute("SET SHOWPLAN_XML OFF");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void showplanIsResetWhenEnablingItOrClosingSetupFails(boolean failOnClose) throws Exception {
    Connection c = mock(Connection.class);
    Statement on = mock(Statement.class);
    Statement off = mock(Statement.class);
    when(c.createStatement()).thenReturn(on, off);
    SQLException failure = new SQLException("setup failed after changing session state");
    if (failOnClose) {
      doThrow(failure).when(on).close();
    } else {
      doThrow(failure).when(on).execute("SET SHOWPLAN_XML ON");
    }

    assertThatThrownBy(() -> SqlServerDialect.withShowPlan(c, () -> {
      throw new AssertionError("A failed setup must not run the plan");
    })).isSameAs(failure);
    verify(off).execute("SET SHOWPLAN_XML OFF");
  }

  @Test
  void failedShowplanResetAbortsConnection() throws Exception {
    Connection c = mock(Connection.class);
    Statement on = mock(Statement.class);
    Statement off = mock(Statement.class);
    when(c.createStatement()).thenReturn(on, off);
    doThrow(new SQLException("reset failed")).when(off).execute("SET SHOWPLAN_XML OFF");
    assertThatThrownBy(() -> SqlServerDialect.withShowPlan(c, () -> 1))
        .isInstanceOf(SQLException.class).hasMessage("reset failed");
    verify(c).abort(any());
  }

  @Test
  void plansUseStatementEstimateRatherThanSummingPlanOperators() {
    assertThat(SqlServerDialect.estimatedRows("""
        <ShowPlanXML xmlns="http://schemas.microsoft.com/sqlserver/2004/07/showplan">
          <StmtSimple StatementEstRows="1.4"><QueryPlan><RelOp EstimateRows="1000"/></QueryPlan></StmtSimple>
        </ShowPlanXML>
        """)).isEqualTo(OptionalLong.of(2));
    assertThat(SqlServerDialect.estimatedRows("<unrelated StatementEstRows='500'/>")).isEmpty();
    assertThat(SqlServerDialect.estimatedRows("""
        <ShowPlanXML xmlns="http://schemas.microsoft.com/sqlserver/2004/07/showplan">
          <StmtSimple StatementEstRows="NaN"/>
        </ShowPlanXML>
        """)).isEmpty();
  }

  @Test
  void columnTypeIncludesUnicodeLengthsPrecisionAndTemporalScale() {
    assertThat(SqlServerCatalog.columnType("nvarchar", 80, 0, 0)).isEqualTo("nvarchar(40)");
    assertThat(SqlServerCatalog.columnType("nvarchar", -1, 0, 0)).isEqualTo("nvarchar(max)");
    assertThat(SqlServerCatalog.columnType("decimal", 9, 18, 2)).isEqualTo("decimal(18,2)");
    assertThat(SqlServerCatalog.columnType("datetimeoffset", 10, 34, 7)).isEqualTo("datetimeoffset(7)");
    assertThat(SqlServerCatalog.quote("odd]name")).isEqualTo("[odd]]name]");
  }

  @Test
  void tlsProbeDoesNotDemandAdministrativePermissions() throws Exception {
    Connection c = mock(Connection.class);
    Statement st = mock(Statement.class);
    when(c.createStatement()).thenReturn(st);
    when(st.executeQuery(any())).thenThrow(new SQLException("permission denied", "42000", 300));
    assertThat(dialect.probe().tls(c, SqlDialect.ServerInfo.parse("SQL Server", "16.0"))).isNull();
  }

  private static ConnectionConfig config(String mode) {
    ConnectionConfig cfg = Fixtures.config("sqlserver", Env.DEV, "127.0.0.1", 1433, "app", "app_ro", null, null);
    ((ObjectNode) cfg.config().get("connection")).put("sslMode", mode);
    return cfg;
  }
}
