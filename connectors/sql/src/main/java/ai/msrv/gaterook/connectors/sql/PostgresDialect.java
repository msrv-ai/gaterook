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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.postgresql.PGConnection;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PostgreSQL 12 and later through pgjdbc (report 04), and CockroachDB, which speaks PostgreSQL's protocol
 * (docs/cockroachdb.md): it names itself in the {@code crdb_version} parameter, has no {@code EXPLAIN (FORMAT JSON)},
 * and refuses privilege checks of its {@code crdb_internal} tables, which the catalog and the probe leave out.
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

  /** The most bytes of rows one call's transaction receives from PostgreSQL (SqlErrors explains the refusal). */
  static final long RESULT_BUFFER = 32L * 1024 * 1024;

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
    p.setProperty("ApplicationName", "gaterook");
    int connectSeconds = Math.max(1, (s.connectTimeoutMs() + 999) / 1000);
    p.setProperty("connectTimeout", Integer.toString(connectSeconds));
    p.setProperty("loginTimeout", Integer.toString(connectSeconds));
    p.setProperty("socketTimeout", Integer.toString(Math.max(1, socketTimeoutMs / 1000)));
    p.setProperty("tcpKeepAlive", "true");
    // pgjdbc buffers every row of a fetch whole, so a wide row or a huge value takes the gateway's memory before
    // the reader can cut it: the rows of one transaction (one call) stop at this many bytes with a clear error. The
    // count restarts with each BEGIN, which pgjdbc sends as a simple query.
    p.setProperty("maxResultBuffer", Long.toString(RESULT_BUFFER));
    // setReadOnly(true) becomes BEGIN READ ONLY (not a session setting that a pooled connection could keep).
    p.setProperty("readOnlyMode", "transaction");
    // No Kerberos or GSS encryption probing: the form has user and password only.
    p.setProperty("gssEncMode", "disable");
    // Auto is chosen before a connection's properties exist (resolveTransport); if it ever wasn't, TLS is verified.
    String mode = AUTO.equals(s.sslMode()) ? PostgresTls.TLS : s.sslMode();
    p.setProperty("sslmode", mode);
    if (!"disable".equals(mode)) {
      // The connection's CA, else the JVM's trust store (pgjdbc's own factory would look for ~/.postgresql/root.crt),
      // and never a client certificate (it would offer ~/.postgresql/postgresql.crt to a server that asks).
      p.setProperty("sslfactory", PostgresSslFactory.class.getName());
      if (s.caCertificate() != null) {
        p.setProperty("sslrootcert", CertificateFiles.pem(certificates, s.caCertificate()).toString());
      }
    }
    return p;
  }

  /** The SSL mode that leaves TLS to the server's answer ({@link PostgresTls}). */
  static final String AUTO = "auto";

  /** Auto: an SSLRequest asks the server whether it speaks TLS before any credential is sent. */
  @Override
  public ConnectionSettings resolveTransport(ConnectionSettings s, int timeoutMs) throws SQLException {
    if (!AUTO.equals(s.sslMode())) {
      return s;
    }
    boolean tls = PostgresTls.offered(s.host(), s.port(), Math.max(1, Math.min(timeoutMs, s.connectTimeoutMs())));
    return s.withTransport(tls ? PostgresTls.TLS : PostgresTls.PLAIN);
  }

  /**
   * Auto in a pool: the properties of both answers are made now (a CA that can't be used fails at once, as for
   * verify-full), the server is asked at the pool's first connection, and its answer holds for the pool.
   */
  @Override
  public CredentialAwareDataSource.Transport poolTransport(ConnectionSettings s, Path certificates,
      int socketTimeoutMs, boolean write) throws IOException {
    if (!AUTO.equals(s.sslMode())) {
      return SqlDialect.super.poolTransport(s, certificates, socketTimeoutMs, write);
    }
    Properties tls = properties(s.withTransport(PostgresTls.TLS), certificates, socketTimeoutMs);
    Properties plain = properties(s.withTransport(PostgresTls.PLAIN), certificates, socketTimeoutMs);
    return CredentialAwareDataSource.Transport.once(() ->
        PostgresTls.TLS.equals(resolveTransport(s, s.connectTimeoutMs()).sslMode()) ? tls : plain);
  }

  /** {@code CockroachDB CCL v26.3.5 (aarch64-unknown-linux-gnu, …)}: the version after the {@code v}. */
  private static final Pattern CRDB_VERSION = Pattern.compile("\\bv(\\d+(?:\\.\\d+){1,2})");
  /** A node of CockroachDB's text plan: {@code estimated row count: 1,204 (…)}. */
  private static final Pattern CRDB_ESTIMATE = Pattern.compile("estimated row count: ([\\d,]+)");

  @Override
  public ServerInfo identify(Connection c) throws SQLException {
    // CockroachDB reports PostgreSQL 13 as server_version; its own version is a parameter of the session.
    String crdb = c.unwrap(PGConnection.class).getParameterStatus("crdb_version");
    if (crdb != null) {
      Matcher m = CRDB_VERSION.matcher(crdb);
      return ServerInfo.parse("CockroachDB", m.find() ? m.group(1) : "");
    }
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
    if (server != null && server.cockroach()) {
      return cockroachEstimate(c, statement);
    }
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

  /**
   * CockroachDB's text plan, top down: the first node with an estimate. A mutation's own node often has none; the
   * scan below it is what the change touches.
   */
  private static OptionalLong cockroachEstimate(Connection c, SqlExecutor.BoundSql statement) throws SQLException {
    String prefix = "EXPLAIN ";
    try (Statement st = SqlExecutor.create(c, prefix, statement, false)) {
      SqlExecutor.execute(st, prefix, statement);
      try (ResultSet rs = st.getResultSet()) {
        while (rs != null && rs.next()) {
          Matcher m = CRDB_ESTIMATE.matcher(String.valueOf(rs.getString(1)));
          if (m.find()) {
            return OptionalLong.of(Long.parseLong(m.group(1).replace(",", "")));
          }
        }
      }
      return OptionalLong.empty();
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
