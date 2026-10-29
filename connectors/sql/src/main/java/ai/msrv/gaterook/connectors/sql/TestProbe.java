package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.TestResult.Hint;
import ai.msrv.gaterook.connector.api.TestResult.Severity;
import ai.msrv.gaterook.connectors.sql.SqlDialect.ServerInfo;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.LinkedHashSet;
import javax.net.ssl.SSLException;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Test connection (report 04 §5): a direct {@code Driver.connect}, not the pool (Hikari's "Connection is not
 * available, request timed out" hides the real cause), within 5 s, and nothing is kept. Then the server, the session's
 * TLS, the latency (median of three {@code SELECT 1}), what the user may really do, and the allowlist's schemas.
 *
 * <p>Hints in the kit's tone: a user that can write is fine on dev (every write waits for approval) and a warning
 * elsewhere, with SQL for a read-only user; a superuser, roles and privileges that reach server files or other
 * sessions, and extensions that run code outside the transaction are dangers; a read replica and TLS off on prod are
 * said too. The metadata it returns is what {@code classify} needs without I/O: the server's label for tool
 * descriptions, and on PostgreSQL every built-in function name.
 */
final class TestProbe {

  /** "Gives up after 5 s." */
  static final int BUDGET_MS = 5_000;

  private final SqlDialect dialect;

  TestProbe(SqlDialect dialect) {
    this.dialect = dialect;
  }

  TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    ConnectionSettings settings = ConnectionSettings.of(cfg, dialect);
    if (!DatabaseSelection.configured(cfg)) return testOne(cfg, secrets, ctx, settings);
    List<String> databases = DatabaseSelection.selected(cfg);
    if (databases.isEmpty()) return TestResult.failure("sql.database", "Select at least one database.", null);
    TestResult summary = null;
    List<TestResult.Check> checks = new ArrayList<>();
    List<Hint> hints = new ArrayList<>();
    ObjectNode databaseDetails = JsonNodeFactory.instance.objectNode();
    LinkedHashSet<String> builtIns = new LinkedHashSet<>();
    TestResult.Permissions strongest = null;
    for (String database : databases) {
      if (ctx.cancelled() || ctx.remaining().isZero()) {
        return TestResult.failure("sql.timeout", "The connection test timed out.", null).withChecks(checks);
      }
      TestResult result = testOne(cfg, secrets, ctx, settings.withDatabase(database, dialect.mysql()));
      for (TestResult.Check check : result.checks()) {
        checks.add(new TestResult.Check(check.name(), check.ok(), check.ms(), database + ": " + check.detail()));
      }
      if (!result.ok()) return result.withChecks(checks);
      if (summary == null) summary = result;
      hints.addAll(result.hints());
      databaseDetails.set(database, result.details());
      if (result.metadataUpdate() != null) {
        result.metadataUpdate().path("builtinFunctions").forEach(n -> builtIns.add(n.asString()));
      }
      if (strongest == null || result.permissions().level().ordinal() > strongest.level().ordinal()) {
        strongest = result.permissions();
      }
    }
    ObjectNode details = ((ObjectNode) summary.details()).deepCopy();
    details.set("databases", databaseDetails);
    ObjectNode metadata = ((ObjectNode) summary.metadataUpdate()).deepCopy();
    if (!builtIns.isEmpty()) builtIns.forEach(metadata.putArray("builtinFunctions")::add);
    return new TestResult(true, summary.latencyMs(), summary.serverVersion(), summary.identity(), strongest,
        hints.stream().distinct().toList(), List.copyOf(checks), null, null, metadata, null, details);
  }

  /** Discover catalogs using the login alone, before a database has been selected. */
  TestResult discoverDatabases(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    if (dialect.syntax() == SqlSyntax.ORACLE) {
      return TestResult.failure("sql.database", "Enter the Oracle service name in the connection settings.", null);
    }
    ConnectionSettings s = ConnectionSettings.of(cfg, dialect).withDatabase(
        dialect.mysql() ? null : discoveryDatabase(cfg), dialect.mysql());
    int budget = (int) Math.max(1, Math.min(BUDGET_MS, ctx.remaining().toMillis()));
    if (ctx.cancelled() || ctx.remaining().isZero()) {
      return TestResult.failure("sql.timeout", "Loading databases timed out.", null);
    }
    Properties props;
    try {
      props = dialect.properties(s.withConnectTimeoutAtMost(budget), certificates(ctx), budget);
    } catch (IOException e) {
      return TestResult.failure("sql.certificate", e.getMessage(), null);
    }
    long started = System.nanoTime();
    try (Credentials login = Credentials.resolve(cfg, secrets)) {
      if (login.user() != null) props.setProperty("user", login.user());
      if (login.password().length > 0) props.setProperty("password", new String(login.password()));
      try (Connection c = dialect.driver().connect(dialect.url(s), props)) {
        props.remove("password");
        ctx.onCancel(() -> closeOnCancel(c));
        c.setReadOnly(true);
        ServerInfo server = dialect.identify(c);
        String query = dialect.mysql()
            ? "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME NOT IN "
                + "('information_schema', 'mysql', 'performance_schema', 'sys') ORDER BY SCHEMA_NAME LIMIT 1001"
            : "SELECT datname FROM pg_catalog.pg_database WHERE datallowconn AND NOT datistemplate "
                + "AND has_database_privilege(oid, 'CONNECT') ORDER BY datname LIMIT 1001";
        ObjectNode details = JsonNodeFactory.instance.objectNode();
        ArrayNode databases = details.putArray("databases");
        boolean complete = true;
        try (Statement st = c.createStatement()) {
          st.setQueryTimeout(Math.max(1, (budget + 999) / 1000));
          ctx.onCancel(() -> { try { st.cancel(); } catch (SQLException ignored) { } });
          try (ResultSet rows = st.executeQuery(query)) {
            while (rows.next()) {
              if (databases.size() == 1000) { complete = false; break; }
              databases.add(rows.getString(1));
            }
          }
        }
        details.put("discoveryComplete", complete);
        return TestResult.success(ms(started), server.full(), login.user(), null).withDetails(details);
      } catch (SQLException e) {
        return failure(e, s, login.user());
      } finally {
        props.remove("password");
      }
    } catch (SecretUnavailableException e) {
      return TestResult.failure("sql.secret", e.getMessage(), null);
    }
  }

  private static void closeOnCancel(Connection connection) {
    try {
      connection.close();
    } catch (SQLException ignored) {
      // The operation already ended or the server has closed its socket.
    }
  }

  private static String discoveryDatabase(ConnectionConfig cfg) {
    String database = cfg.string("/advanced/discoveryDatabase");
    return database == null || database.isBlank() ? "postgres" : database;
  }

  private TestResult testOne(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, ConnectionSettings s) {
    long started = System.nanoTime();
    List<TestResult.Check> checks = new ArrayList<>();
    int budget = (int) Math.max(1, Math.min(BUDGET_MS, ctx.remaining().toMillis()));
    Properties props;
    try {
      props = dialect.properties(s.withConnectTimeoutAtMost(budget), certificates(ctx), budget);
    } catch (IOException e) {
      return TestResult.failure("sql.certificate", e.getMessage(), null);
    }
    try (Credentials login = Credentials.resolve(cfg, secrets)) {
      if (login.user() != null) {
        props.setProperty("user", login.user());
      }
      if (login.password().length > 0) {
        props.setProperty("password", new String(login.password()));
      }
      long connectStart = System.nanoTime();
      Connection c;
      try {
        c = dialect.driver().connect(dialect.url(s), props);
      } catch (SQLException e) {
        return failure(e, s, login.user());
      } finally {
        props.remove("password");
      }
      long connectMs = ms(connectStart);
      checks.add(new TestResult.Check("connect", true, connectMs, "Connected to " + s.target()));
      try (c) {
        ctx.onCancel(() -> closeOnCancel(c));
        try {
          return probe(c, cfg, s, login.user(), checks, connectMs, started);
        } finally {
          if (dialect.syntax() == SqlSyntax.ORACLE) c.rollback();
        }
      } catch (SQLException e) {
        return failure(e, s, login.user()).withChecks(checks);
      }
    } catch (SecretUnavailableException e) {
      return TestResult.failure("sql.secret", e.getMessage(), null);
    }
  }

  private TestResult probe(Connection c, ConnectionConfig cfg, ConnectionSettings s, String user,
      List<TestResult.Check> checks, long connectMs, long started) throws SQLException {
    // Every probe only reads; the socket timeout of the budget bounds each one.
    if (dialect.syntax() != SqlSyntax.ORACLE) c.setReadOnly(true);
    else {
      c.setAutoCommit(false);
      dialect.prelude(c, dialect.identify(c), s, BUDGET_MS, true);
    }
    ServerInfo server = dialect.identify(c);
    SqlProbe probe = dialect.probe();
    long latency = latency(c);
    checks.add(new TestResult.Check("latency", true, latency, "Median of three " + dialect.pingSql()));
    SqlProbe.Tls tls = probe.tls(c, server);
    checks.add(new TestResult.Check("tls", true, 0, tls.enabled() ? "Encrypted · " + tls.version() : "Not encrypted"));
    SqlProbe.Access access = probe.access(c, s, server);
    checks.add(new TestResult.Check("permissions", true, 0, access.write() == null ? "Read only" : "Can write"));
    List<String> missing = probe.missingSchemas(c, s.schemaAllowlist());
    checks.add(new TestResult.Check("allowlist", missing.isEmpty(), 0, missing.isEmpty() ? (s.schemaAllowlist()
        .isEmpty() ? "No schema allowlist" : "Every schema found") : "Not found: " + String.join(", ", missing)));
    List<String> builtIns = probe.builtInFunctions(c);

    TestResult.Level level = access.superuser() || !access.dangerous().isEmpty() ? TestResult.Level.ADMIN
        : access.write() != null || access.schemaChanges() ? TestResult.Level.WRITE : TestResult.Level.READ;
    String read = access.readableTables() < 0 ? null : "SELECT on " + access.readableTables()
        + (access.readableTables() == 1 ? " table" : " tables");
    ObjectNode permissionDetails = JsonNodeFactory.instance.objectNode();
    permissionDetails.put("schemaChanges", access.schemaChanges() ? "Can create or change objects"
        : "None: no CREATE or ownership");
    TestResult.Permissions permissions = new TestResult.Permissions(level, read, access.write(),
        List.copyOf(access.admin()), permissionDetails);
    TestResult result = TestResult.success(latency, server.full(), access.user() == null ? user : access.user(),
        permissions);

    ObjectNode details = JsonNodeFactory.instance.objectNode();
    details.put("connectMs", connectMs);
    details.put("totalMs", ms(started));
    details.putObject("server").put("product", server.product()).put("version", server.version())
        .put("replica", access.replica());
    details.putObject("tls").put("enabled", tls.enabled()).put("version", tls.version());
    ObjectNode snippets = details.putObject("snippets");
    for (Hint h : hints(cfg.env(), s, access, tls, missing, server)) {
      result = result.withHint(h);
      if ("sql.user-can-write".equals(h.code()) || "sql.superuser".equals(h.code())) {
        snippets.putObject(h.code()).put("language", "sql").put("text", probe.readOnlyUserSql(s));
      }
    }
    ObjectNode metadata = JsonNodeFactory.instance.objectNode();
    metadata.put("serverVersion", server.label());
    metadata.put("product", server.product());
    metadata.put("version", server.version());
    metadata.put("replica", access.replica());
    if (!builtIns.isEmpty()) {
      ArrayNode names = metadata.putArray("builtinFunctions");
      builtIns.forEach(names::add);
    }
    return result.withChecks(checks).withDetails(details).withMetadata(metadata);
  }

  static List<Hint> hints(Env env, ConnectionSettings s, SqlProbe.Access a, SqlProbe.Tls tls, List<String> missing,
      ServerInfo server) {
    List<Hint> out = new ArrayList<>();
    String user = a.user() == null ? s.user() : a.user();
    if (a.superuser()) {
      out.add(new Hint(Severity.DANGER, "sql.superuser", "/credentials/user", "This user is a superuser: it can "
          + "read server files and stop other sessions. Use a dedicated read-only user."));
    }
    for (String d : a.superuser() ? List.<String>of() : a.dangerous()) {
      // A superuser's one hint says it all; its single privileges would repeat it.
      out.add(new Hint(Severity.DANGER, "sql.dangerous-privilege", "/credentials/user", user + " has " + d + ": "
          + danger(d) + " Use a user without it."));
    }
    for (String e : a.extensions()) {
      out.add(new Hint(Severity.DANGER, "sql.dangerous-extension", null, e + " is installed in "
          + (s.database() == null ? "this database" : s.database()) + ": its functions run code outside the "
          + "read-only transaction. GateRook refuses the known ones, but only the user's GRANTs are a real "
          + "boundary."));
    }
    if (!a.superuser() && (a.write() != null || a.schemaChanges())) {
      boolean dev = env == Env.DEV;
      out.add(new Hint(dev ? Severity.INFO : Severity.WARNING, "sql.user-can-write", "/credentials/user",
          "This user can write. " + (dev ? "Fine for dev: every write still waits for approval. For stage and prod "
              + "use a read-only user or a replica." : "Every write still waits for approval, but on " + env.id()
              + " use a read-only user or a replica.")));
    }
    if (a.replica()) {
      out.add(new Hint(Severity.INFO, "sql.replica", null, "Read replica: sql_execute will fail here."));
    }
    if (!tls.enabled() && env == Env.PROD) {
      out.add(new Hint(Severity.WARNING, "sql.tls-off", "/connection/sslMode", "The connection isn't encrypted. "
          + "On prod use TLS (verify-full)."));
    }
    if (!missing.isEmpty()) {
      out.add(new Hint(Severity.WARNING, "sql.schema-not-found", "/advanced/schemaAllowlist", (missing.size() == 1
          ? "Schema " + missing.getFirst() + " not found" : "Schemas " + String.join(", ", missing) + " not found")
          + " for " + user + "."));
    }
    if ("PostgreSQL".equals(server.product()) && server.major() > 0 && server.major() < 12) {
      out.add(new Hint(Severity.WARNING, "sql.old-server", null, "PostgreSQL " + server.version() + " is older "
          + "than 12, which GateRook supports."));
    }
    return out;
  }

  private static String danger(String privilege) {
    return switch (privilege.toLowerCase(Locale.ROOT)) {
      case "pg_execute_server_program" -> "it can run programs on the database server.";
      case "pg_write_server_files" -> "it can write files on the database server.";
      case "pg_read_server_files" -> "it can read files on the database server.";
      case "pg_signal_backend" -> "it can cancel and stop other sessions.";
      case "file" -> "it can read and write files on the database server.";
      case "super" -> "it can stop other sessions and change server settings.";
      case "system_user" -> "it can change other users' sessions and accounts.";
      default -> "it reaches outside the database.";
    };
  }

  private long latency(Connection c) throws SQLException {
    long[] runs = new long[3];
    try (Statement st = c.createStatement()) {
      for (int i = 0; i < runs.length; i++) {
        long t = System.nanoTime();
        st.execute(dialect.pingSql());
        runs[i] = ms(t);
      }
    }
    Arrays.sort(runs);
    return runs[1];
  }

  /** The kit's texts for what keeps the test from connecting; the driver's chain under "technical". */
  TestResult failure(SQLException e, ConnectionSettings s, String user) {
    String technical = technical(e);
    String state = SqlErrors.state(e);
    int code = e.getErrorCode();
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof UnknownHostException) {
        return TestResult.failure("sql.unknown-host", "Can't resolve " + s.host() + ". Check the VPN or the host "
            + "name.", technical);
      }
      if (t instanceof SocketTimeoutException) {
        return TestResult.failure("sql.timeout", "Can't reach " + s.host() + ":" + s.port() + ": timed out after "
            + (s.connectTimeoutMs() / 1000) + " s. Check the VPN or the host, then test again.", technical);
      }
      if (t instanceof ConnectException) {
        return TestResult.failure("sql.refused", "Connection refused on port " + s.port() + ". Is the database "
            + "running?", technical);
      }
      if (t instanceof SSLException) {
        return TestResult.failure("sql.tls", "TLS: " + tlsText(t, s) + ".", technical);
      }
    }
    if ("28P01".equals(state) || "28000".equals(state) || code == 1045
        || (dialect.syntax() == SqlSyntax.ORACLE && (code == 1017 || code == 28000 || code == 28001))) {
      return TestResult.failure("sql.auth", "Password authentication failed for user " + (user == null ? "?" : user)
          + ".", technical);
    }
    if ("3D000".equals(state) || code == 1049) {
      return TestResult.failure("sql.database", "Database " + s.database() + " doesn't exist.", technical);
    }
    if (dialect.syntax() == SqlSyntax.ORACLE && (code == 12514 || code == 12505)) {
      return TestResult.failure("sql.database", "Oracle service " + s.database()
          + " is not registered with the listener. Check the service name and port.", technical);
    }
    if (state != null && state.startsWith("08")) {
      String msg = SqlErrors.firstLine(e.getMessage());
      if (SqlErrors.hostNameMismatch(e)) {
        return TestResult.failure("sql.tls", "TLS: the certificate doesn't match the host " + s.host() + " ("
            + s.sslMode() + "). Use the name in the certificate, or verify-ca.", technical);
      }
      if (msg != null && msg.toLowerCase(Locale.ROOT).contains("ssl")) {
        return TestResult.failure("sql.tls", "TLS: " + msg + ".", technical);
      }
      return TestResult.failure("sql.connect", "Can't connect to " + s.host() + ":" + s.port() + ": " + msg + ".",
          technical);
    }
    return TestResult.failure("sql.error", SqlErrors.describe(e) + ".", technical);
  }

  private static String tlsText(Throwable t, ConnectionSettings s) {
    String msg = t.getMessage() == null ? "" : t.getMessage();
    if (msg.toLowerCase(Locale.ROOT).contains("hostname") || msg.contains("No subject alternative")) {
      return "the certificate doesn't match the host (" + s.sslMode() + ")";
    }
    if (msg.contains("PKIX") || msg.contains("unable to find valid certification path")) {
      return "the certificate isn't signed by a trusted CA (" + s.sslMode() + "). Add the CA certificate";
    }
    return SqlErrors.firstLine(msg);
  }

  private static String technical(Throwable e) {
    StringBuilder out = new StringBuilder();
    for (Throwable t = e; t != null && out.length() < 2_000; t = t.getCause()) {
      if (!out.isEmpty()) {
        out.append("\ncaused by ");
      }
      out.append(t.getClass().getSimpleName()).append(": ").append(SqlErrors.firstLine(t.getMessage()));
    }
    return out.toString();
  }

  static Path certificates(ExecContext ctx) {
    return ctx == null || ctx.workDir() == null ? null : ctx.workDir().resolve("certs");
  }

  private static long ms(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
