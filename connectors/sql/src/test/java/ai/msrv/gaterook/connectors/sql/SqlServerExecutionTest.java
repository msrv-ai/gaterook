package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.SecretResolver;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Admission checks must run again when execution is called directly, including with an approval payload. */
class SqlServerExecutionTest {
  private static final SqlDialect DIALECT = new SqlServerDialect();

  @Test
  void approvedQueriesAreCheckedBeforeAnyCredentialsAreRequested() {
    try (JdbcSqlConnector connector = new JdbcSqlConnector(DIALECT)) {
      SecretResolver secrets = mock(SecretResolver.class);
      for (String sql : List.of(
          "SELECT * FROM other.dbo.orders",
          "SELECT dbo.custom_function(1)",
          "SELECT dbo.CustomType::Run()",
          "SELECT CAST('value' AS dbo.CustomType)",
          "UPDATE dbo.orders SET status = 1 OUTPUT inserted.id INTO other.dbo.stolen")) {
        Fixtures.Context ctx = Fixtures.Context.of(Env.DEV, null).approved(Fixtures.object().put("sql", sql));
        assertThatThrownBy(() -> connector.execute(Fixtures.sql("sql_query", "SELECT 1"), config(), secrets, ctx))
            .as(sql).isInstanceOfSatisfying(ConnectorException.class,
                error -> assertThat(error.kind()).isEqualTo(ConnectorException.Kind.DENIED));
      }
      verifyNoInteractions(secrets);
    }
  }

  @Test
  void approvedWritesCannotBypassScopeOrSessionChecksWithoutClassification() {
    try (JdbcSqlConnector connector = new JdbcSqlConnector(DIALECT)) {
      SecretResolver secrets = mock(SecretResolver.class);
      for (String sql : List.of(
          "UPDATE other.dbo.orders SET status = 1",
          "UPDATE dbo.orders SET status = 1 OUTPUT inserted.id INTO other.dbo.stolen",
          "INSERT INTO dbo.orders(id) OUTPUT inserted.id INTO other.dbo.stolen VALUES (1)",
          "EXEC dbo.custom_procedure")) {
        Fixtures.Context ctx = Fixtures.Context.of(Env.DEV, null).approved(Fixtures.object().put("sql", sql));
        assertThatThrownBy(() -> connector.execute(Fixtures.sql("sql_execute", "DELETE FROM dbo.orders WHERE id = 0"),
            config(), secrets, ctx)).as(sql).isInstanceOfSatisfying(ConnectorException.class,
                error -> assertThat(error.kind()).isEqualTo(ConnectorException.Kind.DENIED));
      }
      verifyNoInteractions(secrets);
    }
  }

  @Test
  void nullableTimestampsUseTheMicrosoftTypeAndPreserveTheOffset() throws SQLException {
    PreparedStatement statement = mock(PreparedStatement.class);
    OffsetDateTime value = OffsetDateTime.parse("2026-04-15T10:30:00+05:30");

    TemplateParams.bind(statement, List.of(
        new TemplateParams.Bound("optional_time", TemplateParams.Type.TIMESTAMP, null),
        new TemplateParams.Bound("event_time", TemplateParams.Type.TIMESTAMP, value)), DIALECT);

    verify(statement).setNull(1, microsoft.sql.Types.DATETIMEOFFSET);
    verify(statement).setObject(2, value);
    verifyNoMoreInteractions(statement);
  }

  @Test
  void statementAndLockTimeoutsAreResourceErrorsWithSqlServerGuidance() {
    for (SQLException error : List.of(new SQLTimeoutException("Query timed out", "HYT00"),
        new SQLException("Lock request time out period exceeded", "S00051", 1222))) {
      ConnectorException mapped = SqlErrors.of(error, DIALECT, "dev-sqlserver", "dev", Duration.ofSeconds(3));

      assertThat(mapped.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
      assertThat(mapped.getMessage()).contains("Statement timeout", "3 s", "TOP").doesNotContain("LIMIT");
      assertThat(mapped.getCause()).isSameAs(error);
    }
  }

  @Test
  void deniedDatabasePermissionsAreReportedWithoutMarkingTheServerUnavailable() {
    SQLException error = new SQLException("The SELECT permission was denied", "S0005", 229);

    ConnectorException mapped = SqlErrors.of(error, DIALECT, "dev-sqlserver", "dev", Duration.ofSeconds(3));

    assertThat(mapped.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
    assertThat(mapped.getMessage()).contains("database user of dev-sqlserver", "may not do this", "229");
    assertThat(mapped.getCause()).isSameAs(error);
  }

  private static ConnectionConfig config() {
    return Fixtures.config("sqlserver", Env.DEV, "sql.example.test", 1433, "app", "app_user", null, null);
  }
}
