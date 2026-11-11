package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.logging.Logger;
import org.sqlite.ProgressHandler;
import org.sqlite.SQLiteConnection;

/**
 * A SQLite database file on this computer, through sqlite-jdbc (docs/sqlite.md).
 *
 * <p>Reads open the file read-only ({@code SQLITE_OPEN_READONLY}) and run with {@code query_only}, which also refuses
 * temporary objects; approved changes have connections of their own that open it read-write. No connection may
 * create a file, attach another database ({@code SQLITE_LIMIT_ATTACHED} is 0) or load an extension; a value is at
 * most {@link #MAX_VALUE_BYTES}. Every call turns {@code trusted_schema} off, so views, triggers and defaults in the
 * file can't call functions with side effects. The driver's query timeout doesn't bound a running statement, so the
 * prelude starts a deadline that a progress handler enforces with {@code sqlite3_interrupt}.
 */
public final class SqliteDialect implements SqlDialect {

  /** The largest string or blob a statement may read or build: {@code SQLITE_LIMIT_LENGTH}. */
  static final int MAX_VALUE_BYTES = 64 << 20;
  /** VM steps between deadline checks: a few microseconds of work. */
  static final int PROGRESS_STEPS = 1_000;
  private static final int OPEN_READONLY = 0x1;
  private static final int OPEN_READWRITE = 0x2;

  private final SqliteCatalog catalog = new SqliteCatalog();
  private final SqliteProbe probe = new SqliteProbe();
  private final GuardedDriver driver = new GuardedDriver();

  @Override public String type() { return "sqlite"; }
  @Override public String displayName() { return "SQLite"; }

  @Override
  public String description() {
    return "Tables and queries of a SQLite database file on this computer. Writes wait for approval.";
  }

  @Override public String configSchemaName() { return "sqlite.config"; }
  @Override public boolean mysql() { return false; }
  @Override public SqlSyntax syntax() { return SqlSyntax.SQLITE; }
  @Override public Driver driver() { return driver; }

  @Override
  public String url(ConnectionSettings s) {
    return SqliteFiles.url(s.file() == null ? null : s.file().path());
  }

  @Override
  public Properties properties(ConnectionSettings s, Path certificates, int socketTimeoutMs) {
    return properties(s, certificates, socketTimeoutMs, false);
  }

  @Override
  public Properties properties(ConnectionSettings s, Path certificates, int socketTimeoutMs, boolean write) {
    Properties p = new Properties();
    // Never SQLITE_OPEN_CREATE nor SQLITE_OPEN_URI: a missing file stays missing, and a name is only a name.
    p.setProperty("open_mode", Integer.toString(write ? OPEN_READWRITE : OPEN_READONLY));
    p.setProperty("limit_attached", "0");
    p.setProperty("limit_length", Integer.toString(MAX_VALUE_BYTES));
    p.setProperty("enable_load_extension", "false");
    ConnectionSettings.FileDatabase file = s.file();
    p.setProperty("busy_timeout", Integer.toString(file == null ? 2_000 : file.busyTimeoutMs()));
    p.setProperty("foreign_keys", Boolean.toString(file == null || file.foreignKeys()));
    return p;
  }

  @Override public boolean separateWriteConnections() { return true; }

  @Override
  public void checkTarget(ConnectionSettings s, ExecContext ctx) throws ConnectorException {
    Path home = ctx == null ? null : SqliteFiles.home(ctx.workDir());
    if (home != null) {
      driver.protect(home);
    }
    SqliteFiles.check(s.file() == null ? null : s.file().path(), driver.home());
  }

  @Override
  public ServerInfo identify(Connection c) throws SQLException {
    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT sqlite_version()")) {
      return ServerInfo.parse("SQLite", rs.next() ? rs.getString(1) : "");
    }
  }

  @Override
  public void prelude(Connection c, ServerInfo server, ConnectionSettings s, long timeoutMs, boolean readOnly)
      throws SQLException {
    ProgressHandler.setHandler(c.unwrap(SQLiteConnection.class), PROGRESS_STEPS,
        new Deadline(System.nanoTime() + Math.max(1, timeoutMs) * 1_000_000L));
    try (Statement st = c.createStatement()) {
      st.execute("PRAGMA trusted_schema = OFF");
      st.execute(readOnly ? "PRAGMA query_only = ON" : "PRAGMA query_only = OFF");
    }
  }

  @Override
  public void afterWork(Connection c) {
    try {
      ProgressHandler.clearHandler(c.unwrap(SQLiteConnection.class));
    } catch (SQLException | RuntimeException ignored) {
      // A closed connection keeps no handler.
    }
  }

  @Override
  public OptionalLong estimateRows(Connection c, ServerInfo server, SqlExecutor.BoundSql statement) {
    // EXPLAIN QUERY PLAN describes the plan, not how many rows it would change.
    return OptionalLong.empty();
  }

  @Override public boolean explainNeedsWrite() { return false; }
  @Override public SqlCatalog catalog() { return catalog; }
  @Override public SqlProbe probe() { return probe; }

  @Override
  public void changePassword(Connection c, ServerInfo server, String user, char[] next) throws SQLException {
    throw new SQLFeatureNotSupportedException("A SQLite file has no users or passwords.");
  }

  /** Interrupts the statement of a call at its deadline: SQLite then answers SQLITE_INTERRUPT. */
  static final class Deadline extends ProgressHandler {
    private final long deadlineNanos;

    Deadline(long deadlineNanos) {
      this.deadlineNanos = deadlineNanos;
    }

    @Override
    protected int progress() {
      return System.nanoTime() - deadlineNanos > 0 ? 1 : 0;
    }
  }

  /**
   * sqlite-jdbc behind the file checks: every connection it opens is to a checked file, outside GateRook's home,
   * whatever happened to the path since the connection's pool was made.
   */
  static final class GuardedDriver implements Driver {
    private final org.sqlite.JDBC delegate = new org.sqlite.JDBC();
    private volatile Path home;

    void protect(Path gaterookHome) {
      this.home = gaterookHome;
    }

    Path home() {
      return home;
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
      if (url == null || !url.startsWith(SqliteFiles.URL_PREFIX)) {
        return null;
      }
      String mode = info == null ? null : info.getProperty("open_mode");
      if (!Integer.toString(OPEN_READONLY).equals(mode) && !Integer.toString(OPEN_READWRITE).equals(mode)) {
        throw new SQLException("A SQLite connection opens its file read-only or read-write, nothing else.", "08004");
      }
      try {
        SqliteFiles.check(url.substring(SqliteFiles.URL_PREFIX.length()), home);
      } catch (ConnectorException e) {
        throw new SQLException(e.getMessage(), "08004");
      }
      return delegate.connect(url, info);
    }

    @Override public boolean acceptsURL(String url) { return url != null && url.startsWith(SqliteFiles.URL_PREFIX); }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
      return delegate.getPropertyInfo(url, info);
    }

    @Override public int getMajorVersion() { return delegate.getMajorVersion(); }
    @Override public int getMinorVersion() { return delegate.getMinorVersion(); }
    @Override public boolean jdbcCompliant() { return false; }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      throw new SQLFeatureNotSupportedException();
    }
  }
}
