package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connectors.sql.IdentifierRules.TableRef;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OracleCatalogTest {
  @Test
  void listingUsesTheRemainingCallBudgetAndCancelsTheRunningQuery() throws Exception {
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet rows = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    List<Runnable> cancellations = new ArrayList<>();
    ExecContext context = context(Duration.ofMillis(1_500), cancellations);
    when(statement.executeQuery()).thenAnswer(invocation -> {
      assertThat(cancellations).hasSize(1);
      cancellations.getFirst().run();
      return rows;
    });

    new OracleCatalog().forCall(context, 5_000).list(connection, "APP", null, table -> true, 10);

    verify(statement).setQueryTimeout(2);
    verify(statement).cancel();
    verify(statement).close();
  }

  @Test
  void everyDescribeQueryIsBoundedAndKeysPreserveColumnOrder() throws Exception {
    Connection connection = mock(Connection.class);
    List<PreparedStatement> statements = new ArrayList<>();
    List<String> sql = new ArrayList<>();
    List<ResultSet> results = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      PreparedStatement statement = mock(PreparedStatement.class);
      ResultSet rows = mock(ResultSet.class);
      when(statement.executeQuery()).thenReturn(rows);
      statements.add(statement);
      results.add(rows);
    }
    AtomicInteger index = new AtomicInteger();
    when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
      sql.add(invocation.getArgument(0));
      return statements.get(index.getAndIncrement());
    });
    when(results.get(0).next()).thenReturn(true, false);
    when(results.get(0).getString(1)).thenReturn("APP");
    when(results.get(0).getString(2)).thenReturn("ORDERS");
    when(results.get(0).getString(3)).thenReturn("TABLE");
    when(results.get(2).next()).thenReturn(true, true, false);
    when(results.get(2).getString(1)).thenReturn("TENANT_ID", "ID");
    when(results.get(4).next()).thenReturn(true, true, false);
    when(results.get(4).getString(1)).thenReturn("ORDERS_CUSTOMER_FK");
    when(results.get(4).getString(2)).thenReturn("TENANT_ID", "CUSTOMER_ID");
    when(results.get(4).getString(3)).thenReturn("APP");
    when(results.get(4).getString(4)).thenReturn("CUSTOMERS");
    when(results.get(4).getString(5)).thenReturn("TENANT_ID", "ID");
    List<Runnable> cancellations = new ArrayList<>();

    var described = new OracleCatalog().forCall(context(Duration.ofSeconds(10), cancellations), 500)
        .describe(connection, new TableRef("APP", "ORDERS")).orElseThrow();

    assertThat(described.path("primaryKey").toString()).isEqualTo("[\"TENANT_ID\",\"ID\"]");
    var key = described.path("foreignKeys").get(0);
    assertThat(key.path("columns").toString()).isEqualTo("[\"TENANT_ID\",\"CUSTOMER_ID\"]");
    assertThat(key.path("refColumns").toString()).isEqualTo("[\"TENANT_ID\",\"ID\"]");
    assertThat(sql.get(2)).contains("ORDER BY c.POSITION");
    assertThat(sql.get(4)).contains("pc.POSITION = fc.POSITION", "ORDER BY f.CONSTRAINT_NAME, fc.POSITION");
    assertThat(cancellations).hasSize(statements.size());
    cancellations.forEach(Runnable::run);
    for (PreparedStatement statement : statements) {
      verify(statement).setQueryTimeout(1);
      verify(statement).setString(1, "APP");
      verify(statement).setString(2, "ORDERS");
      verify(statement).cancel();
      verify(statement).close();
    }
    verify(connection, never()).getMetaData();
  }

  @Test
  void anExpiredOrCancelledCallNeverStartsAnotherDictionaryQuery() throws Exception {
    for (boolean cancelled : List.of(false, true)) {
      Connection connection = mock(Connection.class);
      ExecContext context = context(cancelled ? Duration.ofSeconds(10) : Duration.ZERO, new ArrayList<>());
      when(context.cancelled()).thenReturn(cancelled);
      assertThatThrownBy(() -> new OracleCatalog().forCall(context, 5_000)
          .describe(connection, new TableRef("APP", "ORDERS")))
          .isInstanceOf(SQLTimeoutException.class);
      verifyNoInteractions(connection);
    }
  }

  private static ExecContext context(Duration remaining, List<Runnable> cancellations) {
    ExecContext context = mock(ExecContext.class);
    when(context.remaining()).thenReturn(remaining);
    doAnswer(invocation -> {
      cancellations.add(invocation.getArgument(0));
      return null;
    }).when(context).onCancel(any(Runnable.class));
    return context;
  }
}
