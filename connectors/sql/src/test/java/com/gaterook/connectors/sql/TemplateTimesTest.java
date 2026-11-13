package com.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

/**
 * A template's timestamp is an instant, whatever the time zone of the gateway's host. MySQL and MariaDB get it as
 * their session's local time of that instant, which the server computes; the JVM's zone takes no part.
 */
class TemplateTimesTest {

  private static final OffsetDateTime NOON_IN_KOLKATA = OffsetDateTime.parse("2026-10-22T17:30:00.123456789+05:30");

  @Test
  void mysqlGetsTheSessionsLocalTimeOfTheInstantWhateverTheGatewaysZone() throws SQLException {
    TimeZone before = TimeZone.getDefault();
    try {
      for (String zone : List.of("UTC", "Pacific/Kiritimati", "America/Adak")) {
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        PreparedStatement statement = mock(PreparedStatement.class);
        PreparedStatement conversion = mock(PreparedStatement.class);
        Connection connection = mock(Connection.class);
        ResultSet rows = mock(ResultSet.class);
        LocalDateTime sessionLocal = LocalDateTime.parse("2026-10-22T14:00:00.123456");
        when(statement.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT FROM_UNIXTIME(?)")).thenReturn(conversion);
        when(conversion.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getObject(1, LocalDateTime.class)).thenReturn(sessionLocal);

        TemplateParams.bind(statement, List.of(new TemplateParams.Bound("note", TemplateParams.Type.STRING, "x"),
            new TemplateParams.Bound("since", TemplateParams.Type.TIMESTAMP, NOON_IN_KOLKATA)), SqlSyntax.MYSQL);

        // 12:00:00.123456 UTC, to the microsecond MySQL keeps: the instant, not a local time of any zone.
        verify(conversion).setBigDecimal(1, new BigDecimal("1792670400.123456"));
        verify(statement).setString(1, "x");
        verify(statement).setObject(2, sessionLocal);
        verify(statement, never()).setObject(2, NOON_IN_KOLKATA);
      }
    } finally {
      TimeZone.setDefault(before);
    }
  }

  @Test
  void aTimeTheServerCantPlaceInItsSessionsZoneIsRefusedNotGuessed() throws SQLException {
    PreparedStatement statement = mock(PreparedStatement.class);
    PreparedStatement conversion = mock(PreparedStatement.class);
    Connection connection = mock(Connection.class);
    ResultSet rows = mock(ResultSet.class);
    when(statement.getConnection()).thenReturn(connection);
    when(connection.prepareStatement("SELECT FROM_UNIXTIME(?), FROM_UNIXTIME(?)")).thenReturn(conversion);
    when(conversion.executeQuery()).thenReturn(rows);
    when(rows.next()).thenReturn(true);
    when(rows.getObject(1, LocalDateTime.class)).thenReturn(LocalDateTime.parse("2026-10-22T12:00:00"));
    when(rows.getObject(2, LocalDateTime.class)).thenReturn(null);

    assertThatThrownBy(() -> TemplateParams.bind(statement, List.of(
        new TemplateParams.Bound("from", TemplateParams.Type.TIMESTAMP, NOON_IN_KOLKATA),
        new TemplateParams.Bound("to", TemplateParams.Type.TIMESTAMP, OffsetDateTime.parse("1960-01-01T00:00:00Z"))),
        SqlSyntax.MYSQL))
        .isInstanceOfSatisfying(SQLException.class, e -> {
          assertThat(e.getSQLState()).isEqualTo("22008");
          assertThat(e.getMessage()).contains("1960-01-01T00:00Z", "outside the times the server converts");
        });
    verify(statement, never()).setObject(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void otherServersAndCallsWithoutTimesNeedNoConversion() throws SQLException {
    for (SqlSyntax syntax : List.of(SqlSyntax.POSTGRES, SqlSyntax.ORACLE, SqlSyntax.SQLSERVER)) {
      PreparedStatement statement = mock(PreparedStatement.class);
      TemplateParams.bind(statement, List.of(
          new TemplateParams.Bound("since", TemplateParams.Type.TIMESTAMP, NOON_IN_KOLKATA)), syntax);
      verify(statement).setObject(1, NOON_IN_KOLKATA);
      verify(statement, never()).getConnection();
    }
    PreparedStatement statement = mock(PreparedStatement.class);
    Connection connection = mock(Connection.class);
    when(statement.getConnection()).thenReturn(connection);
    TemplateParams.bind(statement, List.of(new TemplateParams.Bound("since", TemplateParams.Type.TIMESTAMP, null),
        new TemplateParams.Bound("id", TemplateParams.Type.INTEGER, 7L)), SqlSyntax.MYSQL);
    verify(connection, never()).prepareStatement(anyString());
    verify(statement).setLong(2, 7L);
  }
}
