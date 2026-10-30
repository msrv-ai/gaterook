package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.sql.SqlDialect.ServerInfo;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * How a statement runs (report 04 §6.1, §6.4, §7.3).
 *
 * <p>A <b>read</b> takes a pooled connection, makes its next transaction READ ONLY before the first statement, runs
 * the prelude (statement and lock timeouts on the server, name resolution, the read-only check) and the statement,
 * and always ends with ROLLBACK: whatever a function did inside the transaction is undone, and the session goes back
 * to the pool clean. Oracle autonomous routines reached through trusted views or stored expressions are outside
 * this transaction; database permissions remain essential in addition to the SQL classifier. SQL Server has no
 * read-only transaction mode and relies on strict SELECT admission, known built-in functions and rollback;
 * a SELECT-only login supplies server-enforced protection.
 *
 * <p>A <b>change</b> runs in a read-write transaction with the same prelude and ends with COMMIT; any error before it
 * rolls back. A connection lost during COMMIT leaves the outcome unknown, and the call says so; it is never retried.
 *
 * <p>The agent's SQL runs as written: a plain {@link Statement} with escape processing off, so {@code {fn …}} isn't
 * rewritten and what ran is what was classified and approved. Template values are bound parameters.
 */
final class SqlExecutor {

  /** Server-side timeouts get at least this; the client's query timeout adds two seconds on top. */
  static final long MIN_TIMEOUT_MS = 100;
  /** RETURNING rows kept per statement of a change. */
  static final int MAX_RETURNING = 100;

  private final SqlDialect dialect;

  SqlExecutor(SqlDialect dialect) {
    this.dialect = dialect;
  }

  /** Work on a connection inside the call's transaction. */
  interface Work<T> {
    T run(Connection c, ServerInfo server, long timeoutMs) throws SQLException;
  }

  /**
   * A statement as it runs: the agent's SQL as written (a plain statement), or a template's JDBC form with the values
   * bound to its {@code ?}s (always prepared, even without parameters: its {@code ??} stands for PostgreSQL's
   * {@code ?} operator only in a prepared statement).
   */
  record BoundSql(String sql, List<TemplateParams.Bound> values, boolean prepared) {

    static BoundSql plain(String sql) {
      return new BoundSql(sql, List.of(), false);
    }

    static BoundSql template(String jdbc, List<TemplateParams.Bound> values) {
      return new BoundSql(jdbc, List.copyOf(values), true);
    }
  }

  // ── Transactions ────────────────────────────────────────────────────────────────────────────────────────────

  <T> T read(SqlPools.Pool pool, ConnectionSettings s, ExecContext ctx, String connection, Work<T> work)
      throws ConnectorException {
    return inTransaction(pool, s, ctx, connection, true, false, work);
  }

  <T> T write(SqlPools.Pool pool, ConnectionSettings s, ExecContext ctx, String connection, Work<T> work)
      throws ConnectorException {
    return inTransaction(pool, s, ctx, connection, false, true, work);
  }

  /**
   * Plans of a change before its request exists ({@code prepare}): READ ONLY where the server allows EXPLAIN of a
   * change in it (PostgreSQL), else a read-write transaction (MySQL refuses EXPLAIN UPDATE in READ ONLY, and SQL
   * Server has no read-only transactions); either way it ends with ROLLBACK. EXPLAIN without ANALYZE and SQL Server's
   * SHOWPLAN_XML do not run the statement.
   */
  <T> T explain(SqlPools.Pool pool, ConnectionSettings s, ExecContext ctx, String connection, Work<T> work)
      throws ConnectorException {
    return inTransaction(pool, s, ctx, connection, !dialect.explainNeedsWrite(), false, work);
  }

  private <T> T inTransaction(SqlPools.Pool pool, ConnectionSettings s, ExecContext ctx, String connection,
      boolean readOnly, boolean commit, Work<T> work) throws ConnectorException {
    long timeoutMs = timeoutMs(ctx, s);
    Connection c;
    try {
      c = pool.borrow();
    } catch (SQLException e) {
      throw SqlErrors.of(e, dialect.syntax(), connection, env(ctx), Duration.ofMillis(timeoutMs));
    }
    boolean broken = false;
    try {
      // Before the first statement: pgjdbc opens the transaction with BEGIN READ ONLY, Connector/J sets the session.
      // Oracle uses SET TRANSACTION READ ONLY; SQL Server has no read-only transaction mode.
      if (dialect.syntax() != SqlSyntax.ORACLE && !dialect.sqlServer()) c.setReadOnly(readOnly);
      dialect.prelude(c, pool.server(), s, timeoutMs, readOnly);
      T result = work.run(c, pool.server(), timeoutMs);
      if (!commit) {
        c.rollback();
        return result;
      }
      try {
        c.commit();
      } catch (SQLException e) {
        broken = true;
        if (SqlErrors.unreachable(e, SqlErrors.state(e), dialect.syntax())) {
          throw ConnectorException.outcomeUnknown("Outcome unknown: the connection dropped during COMMIT. Check "
              + "the data before retrying.", e);
        }
        String outcome = dialect.syntax() == SqlSyntax.ORACLE
            ? "The database refused COMMIT. Schema changes may already be committed; check the data before retrying: "
            : "The database refused the COMMIT, so nothing changed: ";
        throw ConnectorException.resourceError(outcome + SqlErrors.describe(e) + ".", e);
      }
      return result;
    } catch (SQLException e) {
      broken = !rollbackQuietly(c);
      if (Thread.currentThread().isInterrupted() || ctx.cancelled()) {
        // At the deadline it is the call's timeout (it counts for the connection's breaker); before it, the client
        // cancelled: the statement was stopped and rolled back, and the database did nothing wrong.
        if (ctx.remaining().isZero()) {
          throw ConnectorException.timeout("The call was stopped at its deadline (" + seconds(ctx.timeout())
              + ").");
        }
        throw ConnectorException.resourceError(dialect.syntax() == SqlSyntax.ORACLE
            ? "The call was cancelled. Schema changes may already be committed; check the data before retrying."
            : "The call was cancelled: the statement was stopped and rolled back.", e);
      }
      throw SqlErrors.of(e, dialect.syntax(), connection, env(ctx), Duration.ofMillis(timeoutMs));
    } catch (ConnectorException | RuntimeException e) {
      if (!broken) {
        broken = !rollbackQuietly(c);
      }
      throw e;
    } finally {
      if (broken) {
        pool.hikari.evictConnection(c);
      } else {
        closeQuietly(c);
      }
    }
  }

  /**
   * The statement timeout of the call: the policy's, or the connection's own where it is stricter, but never past the
   * call's deadline.
   */
  static long timeoutMs(ExecContext ctx, ConnectionSettings s) {
    long limit = s.statementTimeout(ctx.limits().statementTimeout()).toMillis();
    long left = ctx.remaining().toMillis() - 250;
    return Math.max(MIN_TIMEOUT_MS, Math.min(limit, left));
  }

  // ── Statements ──────────────────────────────────────────────────────────────────────────────────────────────

  /** A statement of the call: escape processing off, a client-side timeout, cancelled with the call. */
  Statement statement(Connection c, ExecContext ctx, long timeoutMs, BoundSql bound) throws SQLException {
    Statement st = create(c, "", bound, dialect.syntax());
    long graceSeconds = dialect.syntax() == SqlSyntax.ORACLE || dialect.sqlServer() ? 0 : 2;
    st.setQueryTimeout((int) Math.max(1, (timeoutMs + 999) / 1000 + graceSeconds));
    Statement registered = st;
    ctx.onCancel(() -> {
      try {
        registered.cancel();
      } catch (SQLException ignored) {
        // Already done or closed.
      }
    });
    return st;
  }

  /**
   * A statement for {@code prefix + bound.sql()}: a {@link PreparedStatement} with the template's values bound, or a
   * plain statement that runs the text as written ({@link #execute}).
   */
  static Statement create(Connection c, String prefix, BoundSql bound, boolean mysql) throws SQLException {
    return create(c, prefix, bound, mysql ? SqlSyntax.MYSQL : SqlSyntax.POSTGRES);
  }

  static Statement create(Connection c, String prefix, BoundSql bound, SqlDialect dialect) throws SQLException {
    return create(c, prefix, bound, dialect.syntax());
  }

  static Statement create(Connection c, String prefix, BoundSql bound, SqlSyntax syntax) throws SQLException {
    if (bound.prepared()) {
      // No escape processing to turn off: a template can't contain {…} (NamedSql), and a prepared statement's text
      // is parsed when it is prepared.
      PreparedStatement ps = c.prepareStatement(prefix + bound.sql());
      TemplateParams.bind(ps, bound.values(), syntax);
      return ps;
    }
    Statement st = c.createStatement();
    st.setEscapeProcessing(false);
    return st;
  }

  /** Executes what {@link #create} made; true when the first result is a result set. */
  static boolean execute(Statement st, String prefix, BoundSql bound) throws SQLException {
    return bound.prepared() ? ((PreparedStatement) st).execute() : st.execute(prefix + bound.sql());
  }

  /** Runs a read and reads its rows within the call's limits. */
  ResultReader.Read query(Connection c, ExecContext ctx, long timeoutMs, BoundSql bound, int limit)
      throws SQLException {
    try (Statement st = statement(c, ctx, timeoutMs, bound)) {
      st.setMaxRows(limit + 1);
      // PostgreSQL: a cursor (autoCommit is off); MySQL: rows streamed one by one, not buffered whole.
      st.setFetchSize(dialect.mysql() ? Integer.MIN_VALUE : Math.min(limit + 1, 200));
      boolean rows = execute(st, "", bound);
      if (!rows) {
        return null;
      }
      ResultSet rs = st.getResultSet();
      try {
        return ResultReader.read(rs, limit, ctx.limits().maxResponseBytes(), ctx.limits().maxCellChars(),
            dialect.syntax(), false);
      } finally {
        try {
          rs.close();
        } catch (SQLException ignored) {
          // MySQL reads the rest of a streamed result on close; the transaction ends with ROLLBACK anyway.
        }
      }
    }
  }

  /** What a change did, statement by statement. */
  record Applied(String type, long rowsAffected, ToolResult.Table returning, boolean returningTruncated) {}

  /**
   * Runs a change: one {@code execute} with the text as approved (pgjdbc sends PostgreSQL's statements in one go and
   * reports each), then every result in order.
   */
  List<Applied> change(Connection c, ExecContext ctx, long timeoutMs, BoundSql bound, List<String> types)
      throws SQLException {
    List<Applied> out = new ArrayList<>();
    try (Statement st = statement(c, ctx, timeoutMs, bound)) {
      // RETURNING of a big change is counted, not kept: fetch it in batches where the driver can (pgjdbc: one
      // statement in a transaction).
      st.setFetchSize(dialect.mysql() ? 0 : 200);
      boolean isResult = execute(st, "", bound);
      while (true) {
        String type = out.size() < types.size() ? types.get(out.size()) : "statement " + (out.size() + 1);
        if (isResult) {
          try (ResultSet rs = st.getResultSet()) {
            ResultReader.Read read = ResultReader.read(rs, MAX_RETURNING, ctx.limits().maxResponseBytes(),
                ctx.limits().maxCellChars(), dialect.syntax(), true);
            out.add(new Applied(type, read.total(), read.table(), read.truncatedReason() != null));
          }
        } else {
          long count = st.getLargeUpdateCount();
          if (count == -1) {
            break;
          }
          out.add(new Applied(type, count, null, false));
        }
        isResult = st.getMoreResults();
      }
    }
    return out;
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static boolean rollbackQuietly(Connection c) {
    try {
      c.rollback();
      return true;
    } catch (SQLException | RuntimeException e) {
      return false;
    }
  }

  private static void closeQuietly(Connection c) {
    try {
      c.close();
    } catch (SQLException ignored) {
      // Hikari takes it back or drops it.
    }
  }

  private static String env(ExecContext ctx) {
    return ctx.env() == null ? "this environment" : ctx.env().id();
  }

  private static String seconds(Duration d) {
    return SqlErrors.seconds(d);
  }
}
