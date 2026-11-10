package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connectors.sql.SqlErrors.Phase;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Which failures count for the connection's circuit breaker ({@code RESOURCE_UNAVAILABLE}): a server that can't be
 * used, never the error of one statement. A refused login and a database that doesn't exist stop every call only while
 * a session is being opened; a vendor code counts only in its own dialect.
 */
class SqlErrorsTest {

  @ParameterizedTest(name = "{0} {1} state={2} code={3} → {4}")
  @CsvSource(delimiterString = " | ", textBlock = """
      # Statement errors that reuse a login or database code: the server is there, the statement failed.
      MYSQL     | STATEMENT | 3D000 | 1046  | RESOURCE_ERROR
      MYSQL     | STATEMENT | 42000 | 1049  | RESOURCE_ERROR
      MYSQL     | STATEMENT | 28000 | 1045  | RESOURCE_ERROR
      POSTGRES  | STATEMENT | 3D000 | 0     | RESOURCE_ERROR
      POSTGRES  | STATEMENT | 28000 | 0     | RESOURCE_ERROR
      # The same codes while a session is opened: the server can't be used.
      MYSQL     | CONNECT   | 28000 | 1045  | RESOURCE_UNAVAILABLE
      MYSQL     | CONNECT   | 42000 | 1049  | RESOURCE_UNAVAILABLE
      POSTGRES  | CONNECT   | 28P01 | 0     | RESOURCE_UNAVAILABLE
      POSTGRES  | CONNECT   | 3D000 | 0     | RESOURCE_UNAVAILABLE
      SQLSERVER | CONNECT   | S0001 | 18456 | RESOURCE_UNAVAILABLE
      SQLSERVER | CONNECT   | S0001 | 4060  | RESOURCE_UNAVAILABLE
      ORACLE    | CONNECT   | 72000 | 1017  | RESOURCE_UNAVAILABLE
      ORACLE    | CONNECT   | 99999 | 28000 | RESOURCE_UNAVAILABLE
      # A vendor code of another dialect is an ordinary error (ORA-02003: invalid USERENV parameter).
      ORACLE    | STATEMENT | 72000 | 2003  | RESOURCE_ERROR
      ORACLE    | STATEMENT | 72000 | 4060  | RESOURCE_ERROR
      ORACLE    | CONNECT   | 72000 | 2013  | RESOURCE_ERROR
      SQLSERVER | STATEMENT | S0001 | 2003  | RESOURCE_ERROR
      SQLSERVER | STATEMENT | S0001 | 1049  | RESOURCE_ERROR
      POSTGRES  | STATEMENT | XX000 | 2006  | RESOURCE_ERROR
      SQLSERVER | STATEMENT | S0001 | 18456 | RESOURCE_ERROR
      ORACLE    | STATEMENT | 72000 | 1017  | RESOURCE_ERROR
      # A session the server dropped, or a server that goes away: in any phase.
      POSTGRES  | STATEMENT | 08006 | 0     | RESOURCE_UNAVAILABLE
      POSTGRES  | STATEMENT | 57P01 | 0     | RESOURCE_UNAVAILABLE
      POSTGRES  | STATEMENT | 57P03 | 0     | RESOURCE_UNAVAILABLE
      MYSQL     | STATEMENT | 08S01 | 0     | RESOURCE_UNAVAILABLE
      MYSQL     | STATEMENT | HY000 | 2013  | RESOURCE_UNAVAILABLE
      MYSQL     | STATEMENT | HY000 | 2006  | RESOURCE_UNAVAILABLE
      ORACLE    | STATEMENT | 08000 | 3113  | RESOURCE_UNAVAILABLE
      ORACLE    | STATEMENT | 66000 | 17008 | RESOURCE_UNAVAILABLE
      SQLSERVER | STATEMENT | 08S01 | 0     | RESOURCE_UNAVAILABLE
      """)
  void onlyAServerThatCantBeUsedCountsForTheBreaker(SqlSyntax syntax, Phase phase, String state, int code,
      ConnectorException.Kind expected) {
    SQLException e = new SQLException("the server's words", state, code);

    ConnectorException mapped = SqlErrors.of(e, syntax, phase, "dev-db", "dev", Duration.ofSeconds(3));

    assertThat(mapped.kind()).isEqualTo(expected);
    assertThat(SqlErrors.unreachable(e, state, syntax, phase))
        .isEqualTo(expected == ConnectorException.Kind.RESOURCE_UNAVAILABLE);
    assertThat(mapped.getCause()).isSameAs(e);
  }

  @ParameterizedTest
  @CsvSource({"POSTGRES", "MYSQL", "ORACLE", "SQLSERVER"})
  void aNetworkFailureBelowCountsInEveryDialectAndPhase(SqlSyntax syntax) {
    SQLException e = new SQLException("I/O error", "HY000", 0, new SocketTimeoutException("Read timed out"));

    for (Phase phase : Phase.values()) {
      assertThat(SqlErrors.of(e, syntax, phase, "dev-db", "dev", Duration.ofSeconds(3)).kind()).as(phase.name())
          .isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE);
    }
  }
}
