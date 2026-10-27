package ai.msrv.gaterook.connectors.sql;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.OptionalLong;
import java.util.Properties;
import org.postgresql.PGConnection;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PostgreSQL 12 and later through pgjdbc (report 04).
 *
 * <p>Reads: {@code setReadOnly(true)} before the first statement makes pgjdbc open the transaction with
 * {@code BEGIN READ ONLY}; the prelude sets the transaction's statement and lock timeouts, the default schema as
 * {@code search_path} and {@code standard_conforming_strings}, and checks {@code transaction_read_only}. All of it is
 * {@code SET LOCAL} ({@code set_config(…, true)}), so the ROLLBACK that ends every read leaves the pooled session
 * clean.
 */
public final class PostgresDialect implements SqlDialect {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final PostgresCatalog catalog = new PostgresCatalog();
  private final PostgresProbe probe = new PostgresProbe();

  @Override
  public String type() {
    return "postgres";
  }

  @Override
  public String displayName() {
    return "PostgreSQL";
  }

  @Override
  public String description() {
    return "Schemas, tables and queries on PostgreSQL 12 and later. Writes wait for approval.";
  }

  @Override
  public String configSchemaName() {
    return "postgres.config";
  }

  @Override
  public boolean mysql() {
    return false;
  }

  @Override
  public Driver driver() {
    return new org.postgresql.Driver();
  }

  @Override
  public String url(ConnectionSettings s) {
    return "jdbc:postgresql://" + s.host() + ":" + s.port() + "/" + URLEncoder.encode(s.database(), StandardCharsets.UTF_8).replace("+", "%20");
  }

  @Override
  public Properties properties(ConnectionSettings s, Path certificates, int socketTimeoutMs) throws IOException {
    Properties p = new Properties();
    p.setProperty("ApplicationName", "mcp-gateway");
    int connectSeconds = Math.max(1, (s.connectTimeoutMs() + 999) / 1000);
    p.setProperty("connectTimeout", Integer.toString(connectSeconds));
    p.setProperty("loginTimeout", Integer.toString(connectSeconds));
    p.setProperty("socketTimeout", Integer.toString(Math.max(1, socketTimeoutMs / 1000)));
    p.setProperty("tcpKeepAlive", "true");
    // setReadOnly(true) becomes BEGIN READ ONLY (not a session setting that a pooled connection could keep).
    p.setProperty("readOnlyMode", "transaction");
    // No Kerberos or GSS encryption probing: the form has user and password only.
    p.setProperty("gssEncMode", "disable");
    String mode = s.sslMode();
    p.setProperty("sslmode", mode);
    if (!"disable".equals(mode)) {
      if (s.caCertificate() != null) {
        p.setProperty("sslrootcert", CertificateFiles.pem(certificates, s.caCertificate()).toString());
      } else if (!"require".equals(mode)) {
        // The JVM's trust store: pgjdbc's default factory would look for ~/.postgresql/root.crt instead.
        p.setProperty("sslfactory", "org.postgresql.ssl.DefaultJavaSSLFactory");
      }
      if (certificates != null) {
        // The default factory would also send a client certificate from ~/.postgresql: never one of the user's files.
        p.setProperty("sslcert", CertificateFiles.none(certificates).toString() + ".crt");
        p.setProperty("sslkey", CertificateFiles.none(certificates).toString() + ".pk8");
      }
    }
    return p;
  }

  @Override
  public ServerInfo identify(Connection c) throws SQLException {
    return ServerInfo.parse("PostgreSQL", c.getMetaData().getDatabaseProductVersion());
  }

  @Override
  public void prelude(Connection c, ServerInfo server, ConnectionSettings s, long timeoutMs, boolean readOnly)
      throws SQLException {
    String timeout = Long.toString(Math.max(1, timeoutMs));
    try (PreparedStatement st = c.prepareStatement("SELECT set_config('statement_timeout', ?, true), "
        + "set_config('lock_timeout', ?, true), set_config('idle_in_transaction_session_timeout', '30s', true), "
        + "set_config('search_path', quote_ident(?), true), set_config('standard_conforming_strings', 'on', true), "
        + "current_setting('transaction_read_only')")) {
      st.setString(1, timeout);
      st.setString(2, timeout);
      st.setString(3, s.defaultSchema());
      try (ResultSet rs = st.executeQuery()) {
        rs.next();
        if (readOnly && !"on".equals(rs.getString(6))) {
          throw new SQLException("The transaction is not read only, so the read was not run.", "25000");
        }
      }
    }
  }

  @Override
  public OptionalLong estimateRows(Connection c, ServerInfo server, SqlExecutor.BoundSql statement)
      throws SQLException {
    String prefix = "EXPLAIN (FORMAT JSON) ";
    try (Statement st = SqlExecutor.create(c, prefix, statement, false)) {
      SqlExecutor.execute(st, prefix, statement);
      try (ResultSet rs = st.getResultSet()) {
        if (rs == null || !rs.next()) {
          return OptionalLong.empty();
        }
        JsonNode plan = JSON.readTree(rs.getString(1)).path(0).path("Plan");
        // A ModifyTable node reports 0 rows itself; the scan below it is what the change touches.
        if ("ModifyTable".equals(plan.path("Node Type").asString()) && plan.path("Plans").isArray()) {
          plan = plan.path("Plans").path(0);
        }
        JsonNode rows = plan.path("Plan Rows");
        return rows.isNumber() ? OptionalLong.of(rows.asLong()) : OptionalLong.empty();
      }
    } catch (RuntimeException e) {
      return OptionalLong.empty();
    }
  }

  @Override
  public boolean explainNeedsWrite() {
    return false;
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
   * pgjdbc hashes the new password on this side (SCRAM-SHA-256, or what {@code password_encryption} says) and sends
   * only the verifier in {@code ALTER USER}: the server's log never sees the password.
   */
  @Override
  public void changePassword(Connection c, ServerInfo server, String user, char[] next) throws SQLException {
    c.unwrap(PGConnection.class).alterUserPassword(user, next, null);
  }
}
