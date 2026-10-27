package ai.msrv.gaterook.connectors.sql;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.Properties;

/**
 * MySQL 8.0, 8.4, 9.x and MariaDB 10.6+ through one driver, Connector/J (report 04). MariaDB is recognized by its
 * version string ({@code …-MariaDB}): the driver reports "MySQL" as the product name for both.
 *
 * <p>Reads: {@code setReadOnly(true)} makes Connector/J send {@code SET SESSION TRANSACTION READ ONLY}; the prelude
 * sets the statement timeout ({@code max_execution_time} on MySQL, {@code max_statement_time} on MariaDB) and takes
 * {@code NO_BACKSLASH_ESCAPES} and {@code ANSI_QUOTES} out of the session's {@code sql_mode}, so the server reads
 * quotes and backslashes the way the gateway's lexer and parser do; then it checks the transaction is read only. One
 * statement per call: {@code allowMultiQueries} stays off.
 */
public final class MySqlDialect implements SqlDialect {

  /** Modes that change how quotes and backslashes are read, removed from the session's {@code sql_mode}. */
  static final String SQL_MODE = "TRIM(BOTH ',' FROM REPLACE(REPLACE(REPLACE(REPLACE(REPLACE("
      + "CONCAT(',', @@SESSION.sql_mode, ','), ',NO_BACKSLASH_ESCAPES,', ','), ',ANSI_QUOTES,', ','), ',ANSI,', ','), "
      + "',ORACLE,', ','), ',MSSQL,', ','))";

  private final MySqlCatalog catalog = new MySqlCatalog();
  private final MySqlProbe probe = new MySqlProbe();

  @Override
  public String type() {
    return "mysql";
  }

  @Override
  public String displayName() {
    return "MySQL";
  }

  @Override
  public String description() {
    return "MySQL 8 and MariaDB: schemas, tables and queries. Writes wait for approval.";
  }

  @Override
  public String configSchemaName() {
    return "mysql.config";
  }

  @Override
  public boolean mysql() {
    return true;
  }

  @Override
  public Driver driver() {
    try {
      return new com.mysql.cj.jdbc.Driver();
    } catch (SQLException e) {
      throw new IllegalStateException("Connector/J can't start", e);
    }
  }

  @Override
  public String url(ConnectionSettings s) {
    return "jdbc:mysql://" + s.host() + ":" + s.port() + "/" + (s.database() == null ? ""
        : URLEncoder.encode(s.database(), StandardCharsets.UTF_8).replace("+", "%20"));
  }

  @Override
  public Properties properties(ConnectionSettings s, Path certificates, int socketTimeoutMs) throws IOException {
    Properties p = new Properties();
    // The fixed list of report 04 §6.5: what could load files, run several statements or deserialize stays off.
    p.setProperty("allowMultiQueries", "false");
    p.setProperty("allowLoadLocalInfile", "false");
    p.setProperty("allowUrlInLocalInfile", "false");
    p.setProperty("allowPublicKeyRetrieval", Boolean.toString(s.allowPublicKeyRetrieval()));
    p.setProperty("connectTimeout", Integer.toString(s.connectTimeoutMs()));
    p.setProperty("socketTimeout", Integer.toString(socketTimeoutMs));
    p.setProperty("connectionAttributes", "program_name:gaterook");
    p.setProperty("tcpKeepAlive", "true");
    // Template values are bound by the server, never pasted into the text by the driver.
    p.setProperty("useServerPrepStmts", "true");
    p.setProperty("emulateUnsupportedPstmts", "false");
    p.setProperty("cachePrepStmts", "false");
    p.setProperty("yearIsDateType", "false");
    p.setProperty("autoReconnect", "false");
    p.setProperty("characterEncoding", "UTF-8");
    p.setProperty("sslMode", switch (s.sslMode()) {
      case "auto" -> "PREFERRED";
      case "disable" -> "DISABLED";
      case "require" -> "REQUIRED";
      case "verify-ca" -> "VERIFY_CA";
      default -> "VERIFY_IDENTITY";
    });
    if (("verify-ca".equals(s.sslMode()) || "verify-full".equals(s.sslMode())) && s.caCertificate() != null) {
      Path store = CertificateFiles.pkcs12(certificates, s.caCertificate());
      p.setProperty("trustCertificateKeyStoreUrl", store.toUri().toString());
      p.setProperty("trustCertificateKeyStoreType", "PKCS12");
      p.setProperty("trustCertificateKeyStorePassword", CertificateFiles.TRUST_STORE_PASSWORD);
      p.setProperty("fallbackToSystemTrustStore", "false");
    }
    return p;
  }

  @Override
  public ServerInfo identify(Connection c) throws SQLException {
    String version = c.getMetaData().getDatabaseProductVersion();
    if (version != null && version.toLowerCase(Locale.ROOT).contains("mariadb")) {
      // Old MariaDB servers prefix their version with 5.5.5- for clients that expect MySQL.
      String v = version.startsWith("5.5.5-") ? version.substring(6) : version;
      return ServerInfo.parse("MariaDB", v);
    }
    return ServerInfo.parse("MySQL", version);
  }

  @Override
  public void prelude(Connection c, ServerInfo server, ConnectionSettings s, long timeoutMs, boolean readOnly)
      throws SQLException {
    long ms = Math.max(1, timeoutMs);
    long seconds = Math.max(1, (ms + 999) / 1000);
    StringBuilder set = new StringBuilder("SET SESSION sql_mode = ").append(SQL_MODE);
    if (server.mariaDb()) {
      // MariaDB: seconds, every statement.
      set.append(", SESSION max_statement_time = ").append(String.format(Locale.ROOT, "%.3f", ms / 1000.0));
    } else {
      // MySQL: milliseconds, SELECT only; changes stop at the lock wait and the client's query timeout.
      set.append(", SESSION max_execution_time = ").append(ms);
    }
    if (!readOnly) {
      set.append(", SESSION innodb_lock_wait_timeout = ").append(seconds);
      set.append(", SESSION lock_wait_timeout = ").append(seconds);
    }
    try (Statement st = c.createStatement()) {
      st.setEscapeProcessing(false);
      st.execute(set.toString());
      if (readOnly) {
        String variable = server.mariaDb() ? "@@SESSION.tx_read_only" : "@@SESSION.transaction_read_only";
        try (ResultSet rs = st.executeQuery("SELECT " + variable)) {
          if (!rs.next() || rs.getInt(1) != 1) {
            throw new SQLException("The transaction is not read only, so the read was not run.", "25000");
          }
        }
      }
    }
  }

  /** Rows × filtered of the first line of {@code EXPLAIN}: what the optimizer expects the change to touch. */
  @Override
  public OptionalLong estimateRows(Connection c, ServerInfo server, SqlExecutor.BoundSql statement)
      throws SQLException {
    String prefix = "EXPLAIN ";
    try (Statement st = SqlExecutor.create(c, prefix, statement, true)) {
      SqlExecutor.execute(st, prefix, statement);
      try (ResultSet rs = st.getResultSet()) {
        if (rs == null) {
          return OptionalLong.empty();
        }
        ResultSetMetaData md = rs.getMetaData();
        int rows = -1;
        int filtered = -1;
        for (int i = 1; i <= md.getColumnCount(); i++) {
          String label = md.getColumnLabel(i).toLowerCase(Locale.ROOT);
          if (label.equals("rows")) {
            rows = i;
          } else if (label.equals("filtered")) {
            filtered = i;
          }
        }
        if (rows < 0 || !rs.next()) {
          return OptionalLong.empty();
        }
        long r = rs.getLong(rows);
        if (rs.wasNull()) {
          return OptionalLong.empty();
        }
        double f = filtered < 0 ? 100.0 : rs.getDouble(filtered);
        if (filtered >= 0 && rs.wasNull()) {
          f = 100.0;
        }
        return OptionalLong.of(Math.round(r * f / 100.0));
      }
    }
  }

  @Override
  public boolean explainNeedsWrite() {
    return true;
  }

  @Override
  public SqlCatalog catalog() {
    return catalog;
  }

  @Override
  public SqlProbe probe() {
    return probe;
  }

  /**
   * MySQL: {@code ALTER USER CURRENT_USER() IDENTIFIED BY '…'}; MariaDB, where ALTER USER needs CREATE USER even for
   * one's own account: {@code SET PASSWORD = PASSWORD('…')}. The password must be a literal in both (a placeholder is
   * a syntax error, checked on 8.4.10), so it is quoted here, after the session's {@code sql_mode} is made to read
   * backslash escapes the way the quoting writes them; the servers write {@code <secret>} into their logs in place of
   * it. The text is wiped once sent.
   */
  @Override
  public void changePassword(Connection c, ServerInfo server, String user, char[] next) throws SQLException {
    StringBuilder sql = new StringBuilder(next.length * 2 + 48).append(server.mariaDb()
        ? "SET PASSWORD = PASSWORD('" : "ALTER USER CURRENT_USER() IDENTIFIED BY '");
    for (char ch : next) {
      switch (ch) {
        case '\\' -> sql.append("\\\\");
        case '\'' -> sql.append("\\'");
        case '\0' -> sql.append("\\0");
        case '\n' -> sql.append("\\n");
        case '\r' -> sql.append("\\r");
        case '\u001a' -> sql.append("\\Z");
        default -> sql.append(ch);
      }
    }
    sql.append(server.mariaDb() ? "')" : "'");
    try (Statement st = c.createStatement()) {
      st.setEscapeProcessing(false);
      st.execute("SET SESSION sql_mode = " + SQL_MODE);
      st.execute(sql.toString());
    } finally {
      for (int i = 0; i < sql.length(); i++) {
        sql.setCharAt(i, '\0');
      }
    }
  }
}
