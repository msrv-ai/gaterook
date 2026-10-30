package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

class OracleDialectTest {
  private final OracleDialect dialect = new OracleDialect();
  private final SqlDialect.ServerInfo server = new SqlDialect.ServerInfo("Oracle Database", "19.0", 19, 0);

  @Test
  void defaultsUseOracleServiceAndVerifiedTls() throws Exception {
    ConnectionConfig cfg = config();
    ((ObjectNode) cfg.config().path("connection")).remove("port");
    ((ObjectNode) cfg.config().path("connection")).remove("sslMode");
    ConnectionSettings s = ConnectionSettings.of(cfg, dialect);
    assertThat(s.port()).isEqualTo(1521);
    assertThat(s.database()).isEqualTo("FREEPDB1");
    assertThat(s.defaultSchema()).isEqualTo("APP_READER");
    assertThat(dialect.url(s)).isEqualTo("jdbc:oracle:thin:@tcps://db.example.test:1521/FREEPDB1");
    assertThat(dialect.properties(s, null, 4200))
        .containsEntry("oracle.net.ssl_server_dn_match", "true")
        .containsEntry("oracle.jdbc.ReadTimeout", "4200")
        .containsEntry("oracle.net.CONNECT_TIMEOUT", "5000")
        .doesNotContainKeys("user", "password", "internal_logon");
  }

  @Test
  void quotedSchemaKeepsCaseAndUnquotedSchemaFoldsToUppercase() {
    ConnectionConfig cfg = config();
    ((ObjectNode) cfg.config()).putObject("advanced").put("defaultSchema", "\"MixedCase\"");
    assertThat(ConnectionSettings.of(cfg, dialect).defaultSchema()).isEqualTo("MixedCase");
    ((ObjectNode) cfg.config().path("advanced")).put("defaultSchema", "app");
    assertThat(ConnectionSettings.of(cfg, dialect).defaultSchema()).isEqualTo("APP");
  }

  @Test
  void enforcesReadOnlyOnServerBeforeAnyQuery() throws Exception {
    Connection c = mock(Connection.class);
    Statement st = mock(Statement.class);
    ResultSet rows = mock(ResultSet.class);
    when(c.createStatement()).thenReturn(st);
    when(st.executeQuery("SELECT SYS_CONTEXT('USERENV', 'SESSION_USER') FROM DUAL")).thenReturn(rows);
    when(rows.next()).thenReturn(true);
    when(rows.getString(1)).thenReturn("APP_READER");

    dialect.prelude(c, server, ConnectionSettings.of(config(), dialect), 3500, true);

    var order = inOrder(st);
    order.verify(st).execute("SET TRANSACTION READ ONLY");
    order.verify(st).executeQuery("SELECT SYS_CONTEXT('USERENV', 'SESSION_USER') FROM DUAL");
    order.verify(st).execute("ALTER SESSION SET CURRENT_SCHEMA = \"APP_READER\"");
    verify(st).setQueryTimeout(4);
  }

  @Test
  void refusesSysBecauseOracleDoesNotEnforceItsReadOnlyTransaction() throws Exception {
    Connection c = mock(Connection.class);
    Statement st = mock(Statement.class);
    ResultSet rows = mock(ResultSet.class);
    when(c.createStatement()).thenReturn(st);
    when(st.executeQuery("SELECT SYS_CONTEXT('USERENV', 'SESSION_USER') FROM DUAL")).thenReturn(rows);
    when(rows.next()).thenReturn(true);
    when(rows.getString(1)).thenReturn("SYS");
    assertThatThrownBy(() -> dialect.prelude(c, server, ConnectionSettings.of(config(), dialect), 3000, true))
        .isInstanceOf(SQLException.class).hasMessageContaining("SYS");
  }

  @Test
  void schemaRequiresAServiceNameAndRejectsJdbcUrlInjection() {
    var schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        .getSchema(SchemaResources.of(JdbcSqlConnector.class, "sql").load("oracle.config"));
    ObjectNode cfg = (ObjectNode) config().config();
    assertThat(schema.validate(cfg)).isEmpty();
    ((ObjectNode) cfg.path("connection")).remove("database");
    assertThat(schema.validate(cfg)).isNotEmpty();
    ((ObjectNode) cfg.path("connection")).put("database", "FREEPDB1?oracle.net.wallet_location=/tmp/wallet");
    assertThat(schema.validate(cfg)).isNotEmpty();
    ((ObjectNode) cfg.path("connection")).put("database", "FREEPDB1").put("sslMode", "require");
    assertThat(schema.validate(cfg)).isNotEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "bad\"password", "bad\npassword"})
  void rejectsPasswordsThatCannotBeQuotedInOracleBeforeOpeningAStatement(String password) {
    Connection c = mock(Connection.class);
    assertThatThrownBy(() -> dialect.changePassword(c, server, "APP_READER", password.toCharArray()))
        .isInstanceOf(SQLException.class).hasMessageContaining("password");
    org.mockito.Mockito.verifyNoInteractions(c);
  }

  private static ConnectionConfig config() {
    return Fixtures.config("oracle", Env.DEV, "db.example.test", 1521, "FREEPDB1", "app_reader", null, null);
  }
}
