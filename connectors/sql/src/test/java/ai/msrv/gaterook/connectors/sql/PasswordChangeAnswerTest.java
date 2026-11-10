package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;

/**
 * A failed password change at the source is either the server's answer (it refused, the password stays) or no answer
 * at all (the connection broke or the driver stopped waiting after the change was sent): then the server may have
 * applied it, and the gateway must keep the new password instead of destroying it.
 */
class PasswordChangeAnswerTest {

  @Test
  void aBrokenConnectionOrADriverTimeoutLosesTheAnswer() {
    assertThat(JdbcSqlConnector.answerLost(new SQLException("An I/O error occurred while sending to the backend.",
        "08006"))).isTrue();
    assertThat(JdbcSqlConnector.answerLost(new SQLException("Communications link failure", "08S01"))).isTrue();
    assertThat(JdbcSqlConnector.answerLost(new SQLRecoverableException("connection reset"))).isTrue();
    assertThat(JdbcSqlConnector.answerLost(new SQLNonTransientConnectionException("connection closed"))).isTrue();
    assertThat(JdbcSqlConnector.answerLost(new SQLTransientConnectionException("socket timeout"))).isTrue();
    assertThat(JdbcSqlConnector.answerLost(new SQLTimeoutException("Statement cancelled due to timeout"))).isTrue();
    SQLException chained = new SQLException("batch failed", "HY000");
    chained.setNextException(new SQLException("connection lost", "08003"));
    assertThat(JdbcSqlConnector.answerLost(chained)).as("a broken connection further down the chain").isTrue();
  }

  @Test
  void aRefusalOfTheServerIsAnAnswer() {
    assertThat(JdbcSqlConnector.answerLost(new SQLException("permission denied to alter role", "42501"))).isFalse();
    assertThat(JdbcSqlConnector.answerLost(new SQLException("Your password does not satisfy the current policy",
        "HY000", 1819))).isFalse();
    assertThat(JdbcSqlConnector.answerLost(new SQLException("syntax error", "42601"))).isFalse();
    assertThat(JdbcSqlConnector.answerLost(new SQLException("no state"))).isFalse();
  }
}
