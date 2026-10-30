package ai.msrv.gaterook.connectors.sql;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.OptionalLong;
import java.util.Properties;

/** Oracle Database 19c and later through the JDBC Thin driver and service-name connections. */
public final class OracleDialect implements SqlDialect {
  private final OracleCatalog catalog = new OracleCatalog();
  private final OracleProbe probe = new OracleProbe();

  @Override public String type() { return "oracle"; }
  @Override public String displayName() { return "Oracle Database"; }
  @Override public String description() {
    return "Schemas, tables and queries on Oracle Database 19c and later. Writes wait for approval.";
  }
  @Override public String configSchemaName() { return "oracle.config"; }
  @Override public boolean mysql() { return false; }
  @Override public SqlSyntax syntax() { return SqlSyntax.ORACLE; }
  @Override public String pingSql() { return "SELECT 1 FROM DUAL"; }
  @Override public Driver driver() { return new oracle.jdbc.OracleDriver(); }

  @Override
  public String url(ConnectionSettings s) {
    String protocol = "disable".equals(s.sslMode()) ? "tcp" : "tcps";
    return "jdbc:oracle:thin:@" + protocol + "://" + s.host() + ":" + s.port() + "/" + s.database();
  }

  @Override
  public Properties properties(ConnectionSettings s, Path certificates, int socketTimeoutMs) throws IOException {
    Properties p = new Properties();
    p.setProperty("oracle.net.CONNECT_TIMEOUT", Integer.toString(s.connectTimeoutMs()));
    p.setProperty("oracle.net.OUTBOUND_CONNECT_TIMEOUT", Integer.toString(s.connectTimeoutMs()));
    p.setProperty("oracle.jdbc.ReadTimeout", Integer.toString(Math.max(1, socketTimeoutMs)));
    p.setProperty("oracle.net.keepAlive", "true");
    p.setProperty("oracle.jdbc.fanEnabled", "false");
    p.setProperty("oracle.jdbc.timezoneAsRegion", "false");
    p.setProperty("oracle.net.ssl_server_dn_match", Boolean.toString("verify-full".equals(s.sslMode())));
    if (!"disable".equals(s.sslMode()) && s.caCertificate() != null) {
      if (certificates == null) throw new IOException("The connector has no work folder for the CA certificate.");
      p.setProperty("javax.net.ssl.trustStore", CertificateFiles.pkcs12(certificates, s.caCertificate()).toString());
      p.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
      p.setProperty("javax.net.ssl.trustStorePassword", CertificateFiles.TRUST_STORE_PASSWORD);
    }
    return p;
  }

  @Override
  public ServerInfo identify(Connection c) throws SQLException {
    var metadata = c.getMetaData();
    return new ServerInfo("Oracle Database", metadata.getDatabaseMajorVersion() + "."
        + metadata.getDatabaseMinorVersion(), metadata.getDatabaseMajorVersion(), metadata.getDatabaseMinorVersion());
  }

  @Override
  public void prelude(Connection c, ServerInfo server, ConnectionSettings s, long timeoutMs, boolean readOnly)
      throws SQLException {
    try (Statement st = c.createStatement()) {
      st.setEscapeProcessing(false);
      st.setQueryTimeout((int) Math.max(1, (timeoutMs + 999) / 1000));
      // JDBC setReadOnly is only a hint on Oracle. This statement enforces the transaction on the server.
      st.execute(readOnly ? "SET TRANSACTION READ ONLY" : "SET TRANSACTION READ WRITE");
      String user;
      try (ResultSet rows = st.executeQuery("SELECT SYS_CONTEXT('USERENV', 'SESSION_USER') FROM DUAL")) {
        if (!rows.next()) throw new SQLException("The session identity could not be checked.", "25000");
        user = rows.getString(1);
      }
      if (readOnly && "SYS".equals(user)) {
        throw new SQLException("Oracle does not enforce read-only transactions for SYS. Use a dedicated user.",
            "25000");
      }
      String schema = s.defaultSchema() == null ? user : s.defaultSchema();
      st.execute("ALTER SESSION SET CURRENT_SCHEMA = " + IdentifierRules.quote(schema, false));
      st.execute("ALTER SESSION SET TIME_ZONE = '+00:00'");
      st.execute("ALTER SESSION SET DDL_LOCK_TIMEOUT = " + Math.max(1, (timeoutMs + 999) / 1000));
    }
  }

  @Override
  public OptionalLong estimateRows(Connection c, ServerInfo server, SqlExecutor.BoundSql statement) {
    // EXPLAIN PLAN writes PLAN_TABLE. Approval preparation must not mutate the connected database.
    return OptionalLong.empty();
  }

  @Override public boolean explainNeedsWrite() { return false; }
  @Override public SqlCatalog catalog() { return catalog; }
  @Override public SqlProbe probe() { return probe; }

  @Override
  public void changePassword(Connection c, ServerInfo server, String user, char[] next) throws SQLException {
    if (next.length == 0) throw new SQLException("The Oracle password cannot be empty.", "22023");
    for (char ch : next) {
      if (ch == '"' || Character.isISOControl(ch)) {
        throw new SQLException("The Oracle password cannot contain double quotes or control characters.", "22023");
      }
    }
    String identity;
    try (Statement st = c.createStatement();
         ResultSet rows = st.executeQuery("SELECT SYS_CONTEXT('USERENV', 'SESSION_USER') FROM DUAL")) {
      if (!rows.next()) throw new SQLException("The session identity could not be checked.", "25000");
      identity = rows.getString(1);
    }
    StringBuilder sql = new StringBuilder("ALTER USER ").append(IdentifierRules.quote(identity, false))
        .append(" IDENTIFIED BY \"").append(next).append('"');
    try (Statement st = c.createStatement()) {
      st.setEscapeProcessing(false);
      st.execute(sql.toString());
    } finally {
      for (int i = 0; i < sql.length(); i++) sql.setCharAt(i, '\0');
    }
  }
}
