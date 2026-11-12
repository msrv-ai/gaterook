package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
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
    if (dialect.syntax() == SqlSyntax.SQLITE) {
      return TestResult.failure("sql.database", "A SQLite connection is one database file: choose it.", null);
    }
    ConnectionSettings s = ConnectionSettings.of(cfg, dialect).withDatabase(
        dialect.mysql() ? null : discoveryDatabase(cfg), dialect.mysql());
    int budget = (int) Math.max(1, Math.min(BUDGET_MS, ctx.remaining().toMillis()));
    if (ctx.cancelled() || ctx.remaining().isZero()) {
      return TestResult.failure("sql.timeout", "Loading databases timed out.", null);
    }
    long started = System.nanoTime();
    Opened opened = open(cfg, secrets, ctx, s, budget);
    if (opened.failure() != null) {
      return opened.failure();
    }
    try (Connection c = opened.connection()) {
      if (!dialect.sqlServer()) c.setReadOnly(true);
      ServerInfo server = dialect.identify(c);
      ObjectNode details = JsonNodeFactory.instance.objectNode();
      ArrayNode databases = details.putArray("databases");
      boolean complete = true;
      try (Statement st = c.createStatement()) {
        st.setQueryTimeout(Math.max(1, (budget + 999) / 1000));
        ctx.onCancel(() -> { try { st.cancel(); } catch (SQLException ignored) { } });
        try (ResultSet rows = st.executeQuery(databasesQuery())) {
          while (rows.next()) {
            if (databases.size() == 1000) { complete = false; break; }
            databases.add(rows.getString(1));
          }
        }
      }
      details.put("discoveryComplete", complete);
      return TestResult.success(ms(started), server.full(), opened.user(), null).withDetails(details);
    } catch (SQLException e) {
      return failure(e, opened.settings(), opened.user());
    }
  }

  /** The databases the login may connect to, at most 1,001 of them by name: discovery's list. */
  String databasesQuery() {
    return dialect.mysql()
        ? "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME NOT IN "
            + "('information_schema', 'mysql', 'performance_schema', 'sys') ORDER BY SCHEMA_NAME LIMIT 1001"
        : dialect.sqlServer() ? "SELECT TOP (1001) name FROM sys.databases WHERE state = 0 "
            + "AND database_id > 4 AND HAS_DBACCESS(name) = 1 ORDER BY name"
        : "SELECT datname FROM pg_catalog.pg_database WHERE datallowconn AND NOT datistemplate "
            + "AND has_database_privilege(oid, 'CONNECT') ORDER BY datname LIMIT 1001";
  }

  /**
   * A direct connection of a test, a discovery or a suggestion, with {@code budget}: the CA checked, Auto's transport
   * chosen by the server ({@link SqlDialect#resolveTransport}), and only then the login sent. The caller closes it; a
   * cancelled call closes it too.
   *
   * @param connection the open connection, or {@code null} with {@code failure} saying why there is none
   * @param settings   what it connected with, Auto's choice made
   * @param user       the login's user
   * @param error      the driver's refusal behind {@code failure}, or {@code null}
   */
  record Opened(Connection connection, ConnectionSettings settings, String user, long connectMs, TestResult failure,
      SQLException error) {

    static Opened failed(TestResult failure) {
      return new Opened(null, null, null, 0, failure, null);
    }

    static Opened failed(TestResult failure, SQLException error) {
      return new Opened(null, null, null, 0, failure, error);
    }
  }

  Opened open(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, ConnectionSettings s, int budget) {
    ConnectionSettings bounded = s.withConnectTimeoutAtMost(budget);
    Properties props;
    try {
      // The CA first: a certificate that can't be used is said before anything goes to the server.
      props = dialect.properties(bounded, certificates(ctx), budget);
      ConnectionSettings resolved = dialect.resolveTransport(bounded, budget);
      if (resolved != bounded) {
        bounded = resolved;
        props = dialect.properties(bounded, certificates(ctx), budget);
      }
    } catch (IOException e) {
      return Opened.failed(TestResult.failure("sql.certificate", e.getMessage(), null));
    } catch (SQLException e) {
      return Opened.failed(failure(e, bounded, cfg.string(ConnectionSettings.USER)), e);
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
        c = dialect.driver().connect(dialect.url(bounded), props);
        if (c == null) {
          throw new SQLException("The driver doesn't take this URL.", "08001");
        }
      } catch (SQLException e) {
        return Opened.failed(failure(e, bounded, login.user()), e);
      } finally {
        props.remove("password");
      }
      ctx.onCancel(() -> closeOnCancel(c));
      return new Opened(c, bounded, login.user(), ms(connectStart), null, null);
    } catch (SecretUnavailableException e) {
      return Opened.failed(TestResult.failure("sql.secret", e.getMessage(), null));
    }
  }

  private static void closeOnCancel(Connection connection) {
    try {
      connection.close();
    } catch (SQLException ignored) {
      // The operation already ended or the server has closed its socket.
    }
  }

  private String discoveryDatabase(ConnectionConfig cfg) {
    String database = cfg.string("/advanced/discoveryDatabase");
    return database == null || database.isBlank() ? (dialect.sqlServer() ? "master" : "postgres") : database;
  }

  private TestResult testOne(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, ConnectionSettings s) {
    long started = System.nanoTime();
    List<TestResult.Check> checks = new ArrayList<>();
    try {
      dialect.checkTarget(s, ctx);
    } catch (ConnectorException e) {
      return TestResult.failure("sql.file", e.getMessage(), null);
    }
    int budget = (int) Math.max(1, Math.min(BUDGET_MS, ctx.remaining().toMillis()));
    Opened opened = open(cfg, secrets, ctx, s, budget);
    if (opened.failure() != null) {
      return opened.failure();
    }
    ConnectionSettings used = opened.settings();
    checks.add(new TestResult.Check("connect", true, opened.connectMs(), "Connected to " + used.target()));
    try (Connection c = opened.connection()) {
      try {
        return probe(c, cfg, used, opened.user(), checks, opened.connectMs(), started);
      } finally {
        if (dialect.syntax() == SqlSyntax.ORACLE) c.rollback();
      }
    } catch (SQLException e) {
      return failure(e, used, opened.user()).withChecks(checks);
    }
  }

  private TestResult probe(Connection c, ConnectionConfig cfg, ConnectionSettings s, String user,
      List<TestResult.Check> checks, long connectMs, long started) throws SQLException {
    // Every probe only reads; the socket timeout of the budget bounds each one.
    if (dialect.syntax() != SqlSyntax.ORACLE && !dialect.sqlServer()) c.setReadOnly(true);
    else if (dialect.syntax() == SqlSyntax.ORACLE) {
      c.setAutoCommit(false);
      dialect.prelude(c, dialect.identify(c), s, BUDGET_MS, true);
    }
    ServerInfo server = dialect.identify(c);
    SqlProbe probe = dialect.probe();
    long latency = latency(c);
    checks.add(new TestResult.Check("latency", true, latency, "Median of three " + dialect.pingSql()));
    boolean file = dialect.syntax() == SqlSyntax.SQLITE;
    SqlProbe.Tls tls = probe.tls(c, server);
    boolean tlsKnown = tls != null || (dialect.sqlServer() && !"disable".equals(s.sslMode()));
    if (tls == null) {
      tls = new SqlProbe.Tls(!"disable".equals(s.sslMode()),
          tlsKnown ? "TLS required by connection settings" : "Unknown: the login cannot inspect transport metadata");
    }
    if (file) {
      // A file on this computer: nothing travels over a network.
      tls = new SqlProbe.Tls(false, "Local file");
      checks.add(new TestResult.Check("file", true, 0, fileDetail(c, s)));
    } else {
      checks.add(new TestResult.Check("tls", true, 0, !tlsKnown ? tls.version() : tlsDetail(s, tls)));
    }
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
    if (file) {
      details.putObject("file").put("path", s.target());
    } else {
      ObjectNode tlsDetails = details.putObject("tls").put("version", tls.version());
      if (tlsKnown) tlsDetails.put("enabled", tls.enabled());
      else tlsDetails.putNull("enabled");
      tlsDetails.put("mode", s.sslMode()).put("auto", s.autoTls() || AUTO.equals(s.sslMode()));
    }
    ObjectNode snippets = details.putObject("snippets");
    for (Hint h : file ? sqliteHints(cfg.env(), access) : hints(cfg.env(), s, access, tls, missing, server)) {
      if (!tlsKnown && "sql.tls-off".equals(h.code())) continue;
      result = result.withHint(h);
      if (!file && ("sql.user-can-write".equals(h.code()) || "sql.superuser".equals(h.code()))) {
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
      out.add(new Hint(Severity.WARNING, "sql.tls-off", "/connection/sslMode", (s.autoTls()
          ? "The server doesn't offer TLS, so Auto connected without encryption." : "The connection isn't encrypted.")
          + " On prod use TLS (verify-full)."));
    }
    if (tls.enabled() && env == Env.PROD && AUTO.equals(s.sslMode())) {
      // MySQL's Auto is the driver's PREFERRED: TLS when the server offers it, its certificate taken as it comes.
      out.add(new Hint(Severity.WARNING, "sql.tls-unverified", "/connection/sslMode", "Auto encrypts without "
          + "verifying the server's certificate. On prod use verify-full, with the CA certificate under Advanced."));
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

  /** SQLite: no users or roles, only whether GateRook's account may write the file. */
  static List<Hint> sqliteHints(Env env, SqlProbe.Access a) {
    List<Hint> out = new ArrayList<>();
    if (a.write() != null) {
      boolean dev = env == Env.DEV;
      out.add(new Hint(dev ? Severity.INFO : Severity.WARNING, "sql.user-can-write", "/connection/file",
          "GateRook may write this file. " + (dev ? "Fine for dev: every write still waits for approval."
              : "Every write still waits for approval, but on " + env.id() + " use a read-only copy of the file, or "
              + "turn sql_execute off.")));
    }
    if (a.readableTables() == 0) {
      out.add(new Hint(Severity.INFO, "sql.empty-database", "/connection/file",
          "The database has no tables or views yet."));
    }
    return out;
  }

  /** {@code Local file · 1.2 MiB · journal wal}. */
  private static String fileDetail(Connection c, ConnectionSettings s) throws SQLException {
    long pages = 0;
    long pageSize = 0;
    String journal = null;
    try (Statement st = c.createStatement()) {
      try (ResultSet rs = st.executeQuery("PRAGMA page_count")) {
        pages = rs.next() ? rs.getLong(1) : 0;
      }
      try (ResultSet rs = st.executeQuery("PRAGMA page_size")) {
        pageSize = rs.next() ? rs.getLong(1) : 0;
      }
      try (ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
        journal = rs.next() ? rs.getString(1) : null;
      }
    }
    double mib = pages * pageSize / 1048576.0;
    return "Local file · " + (mib < 0.1 ? (pages * pageSize / 1024) + " KiB" : String.format(Locale.US, "%.1f MiB", mib))
        + (journal == null ? "" : " · journal " + journal.toLowerCase(Locale.ROOT));
  }

  /** The SSL mode whose transport the server decides: PostgreSQL asks it ({@link PostgresTls}), MySQL's driver tries. */
  static final String AUTO = "auto";

  /** {@code Encrypted · TLSv1.3}, and with Auto what it chose and why. */
  static String tlsDetail(ConnectionSettings s, SqlProbe.Tls tls) {
    String base = tls.enabled() ? "Encrypted" + (tls.version() == null ? "" : " · " + tls.version()) : "Not encrypted";
    if (s.autoTls()) {
      return base + (tls.enabled() ? " · Auto: the server offers TLS, so its certificate and host name were verified"
          : " · Auto: the server doesn't offer TLS");
    }
    if (AUTO.equals(s.sslMode())) {
      return base + (tls.enabled() ? " · Auto: the certificate isn't verified" : " · Auto: the server doesn't offer TLS");
    }
    return base;
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
    if (dialect.syntax() == SqlSyntax.SQLITE) {
      String path = s.target();
      return switch (e.getErrorCode()) {
        case SqlErrors.SQLITE_CANTOPEN -> TestResult.failure("sql.file", "Can't open " + path + ". Check that the file "
            + "exists and that GateRook may read it and its folder.", technical);
        case SqlErrors.SQLITE_NOTADB -> TestResult.failure("sql.file", path + " isn't a SQLite database.", technical);
        case SqlErrors.SQLITE_CORRUPT -> TestResult.failure("sql.file", path + " is damaged: "
            + SqlErrors.describe(e) + ".", technical);
        case SqlErrors.SQLITE_BUSY, SqlErrors.SQLITE_LOCKED -> TestResult.failure("sql.locked", "Another program "
            + "holds a lock on " + path + ". Test again in a moment.", technical);
        default -> TestResult.failure(e instanceof org.sqlite.SQLiteException ? "sql.error" : "sql.file",
            e instanceof org.sqlite.SQLiteException ? SqlErrors.describe(e) + "." : SqlErrors.firstLine(e.getMessage())
                + ".", technical);
      };
    }
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
            + mode(s) + "). Use the name in the certificate, or verify-ca.", technical);
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
      return "the certificate doesn't match the host (" + mode(s) + ")";
    }
    if (msg.contains("PKIX") || msg.contains("unable to find valid certification path")) {
      return "the certificate isn't signed by a trusted CA (" + mode(s) + "). Add the CA certificate under Advanced";
    }
    return SqlErrors.firstLine(msg);
  }

  /** The SSL mode as a TLS failure names it: the form's, or what Auto did. */
  private static String mode(ConnectionSettings s) {
    return s.autoTls() ? "Auto verifies it, as the server offers TLS" : s.sslMode();
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
