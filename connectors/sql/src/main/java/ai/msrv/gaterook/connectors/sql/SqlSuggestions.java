package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import ai.msrv.gaterook.connectors.sql.SqlDialect.ServerInfo;
import ai.msrv.gaterook.connectors.support.LocalPorts;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Help for the SQL forms ({@link ai.msrv.gaterook.connector.api.Connector#suggest}).
 *
 * <p>On this computer ({@link Suggestions.Scope#LOCAL}): {@code localhost} for Host when a server answers on the
 * engine's usual port (never filled in), that port when the host is this computer and the typed port doesn't answer,
 * and for SQLite the database files in the folder of the typed path.
 *
 * <p>Signed in ({@link Suggestions.Scope#SERVICE}) with Test connection's budget and direct connection, so Auto's
 * transport is chosen before the login is sent: what the user may use for the fields that name server objects. The
 * database selection has its own discovery and isn't repeated here. PostgreSQL: the schemas of the first selected
 * database for Default schema and the schema allowlist, the user's own stable and immutable functions, and without a
 * selected database a discovery database the account may connect to. MySQL: the databases for the allowlist and the
 * functions that don't declare data changes. SQL Server: the database user's default schema, filled in because the
 * connection must match it, and the schemas. Oracle: the schemas with tables the user may read. SQLite: what the file
 * holds. Nothing is saved or changed; a failure becomes a note.
 */
final class SqlSuggestions {

  static final String THIS_COMPUTER = "this computer";
  static final String HOST = "/connection/host";
  static final String PORT = "/connection/port";
  static final String FILE = "/connection/file";
  static final String DEFAULT_SCHEMA = "/advanced/defaultSchema";
  static final String ALLOWLIST = "/advanced/schemaAllowlist";
  static final String FUNCTIONS = "/advanced/allowedFunctions";
  static final String DISCOVERY = "/advanced/discoveryDatabase";

  /** A database every PostgreSQL cluster has, open to every user unless revoked: a second way to list databases. */
  static final String TEMPLATE1 = "template1";

  private static final int MAX_FILES = 50;
  /** Entries of a folder looked at for SQLite files: a home folder can hold thousands. */
  private static final int MAX_ENTRIES = 5_000;
  private static final Set<String> SQLITE_EXTENSIONS = Set.of("db", "sqlite", "sqlite3", "db3", "s3db", "sl3");
  private static final byte[] SQLITE_HEADER = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
  /** A PostgreSQL name an allowed function entry can match: entries fold to lower case as unquoted names do. */
  private static final Pattern POSTGRES_NAME = Pattern.compile("[a-z_][a-z0-9_$]*");
  /** A MySQL name the form's pattern for allowed functions takes. */
  private static final Pattern MYSQL_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");
  /** An Oracle name that needs no quotes: unquoted names are upper case. */
  private static final Pattern ORACLE_NAME = Pattern.compile("[A-Z][A-Z0-9_$#]{0,127}");

  /** The user's own functions that declare they only read: no extension's, none that's volatile. */
  static final String POSTGRES_FUNCTIONS = "SELECT n.nspname, p.proname, p.provolatile FROM pg_catalog.pg_proc p "
      + "JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace WHERE p.prokind = 'f' "
      + "AND p.provolatile IN ('i', 's') AND " + PostgresCatalog.NOT_DATA + " AND n.nspname NOT LIKE 'pg\\_%' "
      + "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d WHERE d.classid = 'pg_catalog.pg_proc'::regclass "
      + "AND d.objid = p.oid AND d.deptype = 'e') "
      + "AND has_schema_privilege(n.oid, 'USAGE') AND has_function_privilege(p.oid, 'EXECUTE') "
      + "ORDER BY 1, 2 LIMIT " + (Suggestions.MAX_OPTIONS + 1);

  /** Stored functions the user may see that don't declare they modify data (MySQL doesn't enforce it). */
  static final String MYSQL_FUNCTIONS = "SELECT ROUTINE_SCHEMA, ROUTINE_NAME, SQL_DATA_ACCESS FROM "
      + "information_schema.ROUTINES WHERE ROUTINE_TYPE = 'FUNCTION' AND ROUTINE_SCHEMA NOT IN ("
      + MySqlCatalog.SYSTEM_SCHEMAS + ") AND SQL_DATA_ACCESS <> 'MODIFIES SQL DATA' ORDER BY 1, 2 LIMIT "
      + (Suggestions.MAX_OPTIONS + 1);

  private SqlSuggestions() {}

  static Suggestions suggest(SqlDialect dialect, TestProbe probe, ConnectionConfig cfg, SecretResolver secrets,
      ExecContext ctx, Suggestions.Scope scope) {
    Suggestions.Builder out = Suggestions.builder();
    boolean service = scope == Suggestions.Scope.SERVICE;
    switch (dialect.syntax()) {
      case SQLITE -> {
        if (service) {
          sqlite(dialect, probe, cfg, secrets, ctx, out);
        } else {
          sqliteFiles(cfg, ctx, out);
        }
      }
      case POSTGRES -> {
        if (service) {
          postgres(dialect, probe, cfg, secrets, ctx, out);
        } else {
          server(cfg, "A PostgreSQL server", 5432, out);
        }
      }
      case MYSQL -> {
        if (service) {
          mysql(dialect, probe, cfg, secrets, ctx, out);
        } else {
          server(cfg, "A MySQL or MariaDB server", 3306, out);
        }
      }
      case SQLSERVER -> {
        if (service) {
          sqlServer(dialect, probe, cfg, secrets, ctx, out);
        } else {
          server(cfg, "A SQL Server", 1433, out);
        }
      }
      case ORACLE -> {
        if (service) {
          oracle(dialect, probe, cfg, secrets, ctx, out);
        } else {
          server(cfg, "An Oracle listener", 1521, out);
        }
      }
    }
    return out.build();
  }

  // ── This computer ─────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * {@code localhost} for Host when something answers on {@code port} of this computer, and that port under Port when
   * the host is this computer and the typed port doesn't answer. Nothing is filled in.
   */
  static void server(ConnectionConfig cfg, String what, int port, Suggestions.Builder out) {
    List<Integer> found = LocalPorts.offer(out, HOST, what, port);
    String host = blankToNull(cfg.string(HOST));
    int typed = cfg.integer(PORT, port);
    if (!found.isEmpty() && host != null && loopback(host) && typed != port && !LocalPorts.listening(typed)) {
      out.options(PORT, THIS_COMPUTER, List.of(new Option(Integer.toString(port), null,
          what + " answers on port " + port)));
    }
  }

  static boolean loopback(String host) {
    String h = host.strip().toLowerCase(Locale.ROOT);
    return h.equals("localhost") || h.equals("::1") || h.equals("[::1]") || h.startsWith("127.");
  }

  /**
   * SQLite: the database files in the folder of the typed path (or in the typed folder), as {@code ~/…} where they are
   * in the home folder; a file GateRook keeps for itself isn't offered. Why the typed file can't be used is a note.
   */
  static void sqliteFiles(ConnectionConfig cfg, ExecContext ctx, Suggestions.Builder out) {
    String typed = blankToNull(cfg.string(FILE));
    if (typed == null) {
      return;
    }
    Path path;
    try {
      path = SqliteFiles.path(typed);
    } catch (ConnectorException e) {
      // Not a path on this computer yet (a relative one, one still being typed): the form's own check says so.
      return;
    }
    boolean folderTyped = Files.isDirectory(path);
    Path folder = folderTyped ? path : path.getParent();
    if (folder == null || !Files.isDirectory(folder)) {
      return;
    }
    Path home = SqliteFiles.home(ctx == null ? null : ctx.workDir());
    List<Path> candidates = new ArrayList<>();
    boolean complete = true;
    try (DirectoryStream<Path> dir = Files.newDirectoryStream(folder)) {
      int seen = 0;
      for (Path p : dir) {
        if (++seen > MAX_ENTRIES) {
          complete = false;
          break;
        }
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0 && SQLITE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))) {
          candidates.add(p);
        }
      }
    } catch (IOException | DirectoryIteratorException | SecurityException e) {
      return;
    }
    candidates.sort(null);
    List<Option> options = new ArrayList<>();
    for (Path p : candidates) {
      if (options.size() == MAX_FILES) {
        complete = false;
        break;
      }
      if (sqliteFile(p, home)) {
        options.add(new Option(shown(p), null, p.equals(path) ? "chosen" : null));
      }
    }
    if (!options.isEmpty()) {
      out.field(FILE, new Suggestions.Field(options, null, THIS_COMPUTER, complete));
    }
    if (Files.isRegularFile(path)) {
      try {
        SqliteFiles.check(typed, home);
      } catch (ConnectorException e) {
        out.note(e.getMessage());
      }
    } else if (!folderTyped && !Files.exists(path)) {
      out.note(shown(path) + " doesn't exist on this computer" + (options.isEmpty() ? "." : ": choose a database "
          + "file of its folder."));
    }
  }

  /** A regular file that starts as a SQLite database does, and isn't GateRook's own. */
  private static boolean sqliteFile(Path p, Path home) {
    try {
      Path real = p.toRealPath();
      if (!Files.isRegularFile(real) || (home != null && SqliteFiles.gaterooks(real, home))) {
        return false;
      }
      byte[] head = new byte[SQLITE_HEADER.length];
      try (InputStream in = Files.newInputStream(real)) {
        return in.readNBytes(head, 0, head.length) == head.length && Arrays.equals(head, SQLITE_HEADER);
      }
    } catch (IOException | SecurityException e) {
      return false;
    }
  }

  /** {@code ~/projects/shop.db} rather than the home folder's full path, as people type it. */
  static String shown(Path p) {
    Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
    return p.startsWith(home) && !p.equals(home) ? "~/" + home.relativize(p).toString().replace('\\', '/')
        : p.toString();
  }

  // ── Signed in ─────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * PostgreSQL. With a selected database: its schemas (the session's current one filled in where tables are, or the
   * only schema that has tables) and the user's own read-only functions. Without one: whether the account may connect
   * to the discovery database, and when it may not, {@code template1} filled in if that one lists the databases.
   */
  private static void postgres(SqlDialect dialect, TestProbe probe, ConnectionConfig cfg, SecretResolver secrets,
      ExecContext ctx, Suggestions.Builder out) {
    ConnectionSettings base = ConnectionSettings.of(cfg, dialect);
    String selected = blankToNull(DatabaseSelection.first(cfg));
    if (selected == null) {
      postgresDiscovery(dialect, probe, cfg, secrets, ctx, base, out);
      return;
    }
    TestProbe.Opened o = open(probe, cfg, secrets, ctx, base.withDatabase(selected, false), out);
    if (o == null) {
      return;
    }
    try (Connection c = o.connection()) {
      c.setReadOnly(true);
      ServerInfo server = dialect.identify(c);
      String user = o.user();
      String current = null;
      try (Statement st = c.createStatement();
           ResultSet rs = st.executeQuery("SELECT current_user, current_schema()")) {
        if (rs.next()) {
          user = rs.getString(1);
          current = rs.getString(2);
        }
      }
      SqlCatalog.Listing listing = dialect.catalog().list(c, null, null, t -> true, 0);
      String source = "database " + selected;
      String preferred = preferredSchema(current, listing);
      out.value(DEFAULT_SCHEMA, source, preferred, schemaOptions(listing, UnaryOperator.identity()));
      out.options(ALLOWLIST, source, schemaOptions(listing, name -> IdentifierRules.quoteIfNeeded(name,
          SqlSyntax.POSTGRES)));
      postgresFunctions(c, preferred == null ? base.defaultSchema() : preferred, out);
      out.note("Signed in to " + selected + " as " + user + " (" + server.full() + "): "
          + count(listing.schemas().size(), "schema") + " you may use. Choose the default schema, and an allowlist "
          + "if agents should see only some.");
      if (preferred == null && !listing.schemas().contains(base.defaultSchema())) {
        out.note("Schema " + base.defaultSchema() + " isn't one " + user + " may use in " + selected
            + ": choose the default schema.");
      }
    } catch (SQLException e) {
      out.note(probe.failure(e, o.settings(), o.user()).error().message());
    }
  }

  /** Before a database is selected: the database list's own way in, or {@code template1} when it's closed. */
  private static void postgresDiscovery(SqlDialect dialect, TestProbe probe, ConnectionConfig cfg,
      SecretResolver secrets, ExecContext ctx, ConnectionSettings base, Suggestions.Builder out) {
    String discovery = blankToNull(cfg.string(DISCOVERY));
    discovery = discovery == null ? "postgres" : discovery;
    if (budget(ctx) <= 0) {
      out.note(NO_TIME);
      return;
    }
    TestProbe.Opened o = probe.open(cfg, secrets, ctx, base.withDatabase(discovery, false), budget(ctx));
    if (o.failure() != null && !TEMPLATE1.equals(discovery) && databaseRefused(o.error(), discovery)
        && budget(ctx) > 0) {
      TestProbe.Opened fallback = probe.open(cfg, secrets, ctx, base.withDatabase(TEMPLATE1, false), budget(ctx));
      if (fallback.failure() == null) {
        try (Connection c = fallback.connection()) {
          List<String> names = databases(probe, c);
          List<Option> options = new ArrayList<>();
          options.add(new Option(TEMPLATE1, null, "Lists the databases"));
          names.stream().filter(n -> !n.equals(TEMPLATE1)).forEach(n -> options.add(Option.of(n)));
          out.value(DISCOVERY, "the server", TEMPLATE1, options);
          out.note(fallback.user() + " may not connect to " + discovery + ": Discovery database is set to "
              + TEMPLATE1 + ", which lists " + databaseCount(names) + " it may connect to.");
          return;
        } catch (SQLException e) {
          // Listing from template1 failed too: what the discovery database said is the useful answer.
        }
      }
    }
    if (o.failure() != null) {
      out.note(o.failure().error().message());
      return;
    }
    try (Connection c = o.connection()) {
      List<String> names = databases(probe, c);
      out.note("Signed in as " + o.user() + ": " + databaseCount(names) + " it may connect to. Load them and "
          + "choose the ones agents may use.");
    } catch (SQLException e) {
      out.note(probe.failure(e, o.settings(), o.user()).error().message());
    }
  }

  /**
   * Whether PostgreSQL refused the database rather than the login: it doesn't exist (3D000), the user lacks CONNECT on
   * it (42501), or {@code pg_hba.conf} has no entry for it.
   */
  static boolean databaseRefused(SQLException e, String database) {
    if (e == null) {
      return false;
    }
    String state = SqlErrors.state(e);
    String message = e.getMessage() == null ? "" : e.getMessage();
    return "3D000".equals(state) || "42501".equals(state)
        || ("28000".equals(state) && message.contains("database \"" + database + "\""));
  }

  /** The databases the login may connect to, as discovery lists them: at most 1,001 names. */
  private static List<String> databases(TestProbe probe, Connection c) throws SQLException {
    List<String> names = new ArrayList<>();
    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(probe.databasesQuery())) {
      while (rs.next()) {
        names.add(rs.getString(1));
      }
    }
    return names;
  }

  /** {@code 12 databases}, or {@code more than 1,000 databases} when discovery's list was cut. */
  private static String databaseCount(List<String> names) {
    return names.size() > 1_000 ? "more than 1,000 databases" : count(names.size(), "database");
  }

  /** The user's own stable and immutable functions, named as an entry of Functions reads may call matches them. */
  private static void postgresFunctions(Connection c, String defaultSchema, Suggestions.Builder out) {
    List<Option> options = new ArrayList<>();
    boolean complete = true;
    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(POSTGRES_FUNCTIONS)) {
      while (rs.next()) {
        String schema = rs.getString(1);
        String name = rs.getString(2);
        if (!POSTGRES_NAME.matcher(schema).matches() || !POSTGRES_NAME.matcher(name).matches()) {
          continue;
        }
        if (options.size() == Suggestions.MAX_OPTIONS) {
          complete = false;
          break;
        }
        String value = schema.equals(defaultSchema) ? name : schema + "." + name;
        if (options.stream().noneMatch(o -> o.value().equals(value))) {
          options.add(new Option(value, null, ("i".equals(rs.getString(3)) ? "Immutable" : "Stable")
              + " function in " + schema));
        }
      }
    } catch (SQLException e) {
      // CockroachDB and other PostgreSQL-compatible catalogs lack part of this: no functions to offer.
      return;
    }
    if (!options.isEmpty()) {
      out.field(FUNCTIONS, new Suggestions.Field(options, null, "the database", complete));
    }
  }

  /** MySQL: the databases for the allowlist and the functions that don't declare data changes. */
  private static void mysql(SqlDialect dialect, TestProbe probe, ConnectionConfig cfg, SecretResolver secrets,
      ExecContext ctx, Suggestions.Builder out) {
    ConnectionSettings base = ConnectionSettings.of(cfg, dialect);
    // Without a database: the listing needs none, and a selected one the user lost wouldn't stop it.
    TestProbe.Opened o = open(probe, cfg, secrets, ctx, base.withDatabase(null, true), out);
    if (o == null) {
      return;
    }
    try (Connection c = o.connection()) {
      c.setReadOnly(true);
      ServerInfo server = dialect.identify(c);
      String user = o.user();
      try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT CURRENT_USER()")) {
        if (rs.next()) {
          user = rs.getString(1);
        }
      }
      SqlCatalog.Listing listing = dialect.catalog().list(c, null, null, t -> true, 0);
      out.options(ALLOWLIST, "the server", schemaOptions(listing, UnaryOperator.identity()));
      String home = blankToNull(DatabaseSelection.first(cfg));
      List<Option> functions = new ArrayList<>();
      boolean complete = true;
      try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(MYSQL_FUNCTIONS)) {
        while (rs.next()) {
          String database = rs.getString(1);
          String name = rs.getString(2);
          if (!MYSQL_NAME.matcher(database).matches() || !MYSQL_NAME.matcher(name).matches()) {
            continue;
          }
          if (functions.size() == Suggestions.MAX_OPTIONS) {
            complete = false;
            break;
          }
          String access = rs.getString(3);
          functions.add(new Option(database.equals(home) ? name : database + "." + name, null, "Function in "
              + database + (access == null ? "" : ", " + access.toLowerCase(Locale.ROOT))));
        }
      } catch (SQLException e) {
        // No functions to offer: information_schema answers for every user, so this is a server's own limit.
      }
      if (!functions.isEmpty()) {
        out.field(FUNCTIONS, new Suggestions.Field(functions, null, "the server", complete));
      }
      out.note("Signed in as " + user + " (" + server.full() + "): " + count(listing.schemas().size(), "database")
          + " it may use. The database allowlist offers them.");
    } catch (SQLException e) {
      out.note(probe.failure(e, o.settings(), o.user()).error().message());
    }
  }

  /**
   * SQL Server: the database user's default schema, filled in because calls stop when Default schema doesn't match
   * it, and the schemas of the first selected database (or the login's default one).
   */
  private static void sqlServer(SqlDialect dialect, TestProbe probe, ConnectionConfig cfg, SecretResolver secrets,
      ExecContext ctx, Suggestions.Builder out) {
    ConnectionSettings base = ConnectionSettings.of(cfg, dialect);
    TestProbe.Opened o = open(probe, cfg, secrets, ctx, base, out);
    if (o == null) {
      return;
    }
    try (Connection c = o.connection()) {
      ServerInfo server = dialect.identify(c);
      String schema = null;
      String database = base.database();
      String user = o.user();
      try (Statement st = c.createStatement();
           ResultSet rs = st.executeQuery("SELECT SCHEMA_NAME(), DB_NAME(), USER_NAME()")) {
        if (rs.next()) {
          schema = rs.getString(1);
          database = rs.getString(2);
          user = rs.getString(3);
        }
      }
      String source = "database " + database;
      if (schema != null) {
        out.value(DEFAULT_SCHEMA, source, schema, List.of(new Option(schema, null, "The default schema of " + user
            + " in " + database)));
      }
      SqlCatalog.Listing listing = dialect.catalog().list(c, null, null, t -> true, 0);
      out.options(ALLOWLIST, source, schemaOptions(listing, UnaryOperator.identity()));
      out.note("Signed in to " + database + " as " + user + " (" + server.full() + "): "
          + count(listing.schemas().size(), "schema") + " it may read." + (schema == null ? ""
          : " Unqualified names resolve to " + schema + ", its default schema."));
      if (schema != null && !schema.equals(base.defaultSchema())) {
        out.note("Default schema must be " + schema + ", the default schema of " + user + " in " + database
            + ": calls stop while it differs.");
      }
    } catch (SQLException e) {
      out.note(probe.failure(e, o.settings(), o.user()).error().message());
    }
  }

  /**
   * Oracle: the schemas with tables or views the user may read. One is filled in as Default schema only when the
   * user's own schema has none and it is the only one that has: empty already means the user's own.
   */
  private static void oracle(SqlDialect dialect, TestProbe probe, ConnectionConfig cfg, SecretResolver secrets,
      ExecContext ctx, Suggestions.Builder out) {
    ConnectionSettings base = ConnectionSettings.of(cfg, dialect);
    TestProbe.Opened o = open(probe, cfg, secrets, ctx, base, out);
    if (o == null) {
      return;
    }
    try (Connection c = o.connection()) {
      String user = o.user();
      String current = null;
      try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
          "SELECT SYS_CONTEXT('USERENV', 'SESSION_USER'), SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM DUAL")) {
        if (rs.next()) {
          user = rs.getString(1);
          current = rs.getString(2);
        }
      }
      SqlCatalog.Listing listing = dialect.catalog().forCall(ctx, budget(ctx)).list(c, null, null, t -> true, 0);
      String preferred = preferredSchema(current, listing);
      String fill = preferred == null || preferred.equals(current) ? null : oracleName(preferred);
      out.value(DEFAULT_SCHEMA, "the database", fill, schemaOptions(listing, SqlSuggestions::oracleName));
      out.options(ALLOWLIST, "the database", schemaOptions(listing, SqlSuggestions::oracleName));
      out.note("Signed in to " + base.database() + " as " + user + ": " + count(listing.schemas().size(), "schema")
          + " with tables it may read." + (fill == null ? "" : " " + user + " owns none, so Default schema is "
          + preferred + "."));
    } catch (SQLException e) {
      out.note(probe.failure(e, o.settings(), o.user()).error().message());
    }
  }

  /** SQLite: what the file holds, read through a read-only handle as Test connection opens it. */
  private static void sqlite(SqlDialect dialect, TestProbe probe, ConnectionConfig cfg, SecretResolver secrets,
      ExecContext ctx, Suggestions.Builder out) {
    ConnectionSettings s = ConnectionSettings.of(cfg, dialect);
    try {
      dialect.checkTarget(s, ctx);
    } catch (ConnectorException e) {
      out.note(e.getMessage());
      return;
    }
    TestProbe.Opened o = open(probe, cfg, secrets, ctx, s, out);
    if (o == null) {
      return;
    }
    int tables = 0;
    int views = 0;
    try (Connection c = o.connection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
        "SELECT type, count(*) FROM sqlite_master WHERE type IN ('table', 'view') "
            + "AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\' GROUP BY type")) {
      while (rs.next()) {
        if ("table".equals(rs.getString(1))) {
          tables = rs.getInt(2);
        } else {
          views = rs.getInt(2);
        }
      }
    } catch (SQLException e) {
      out.note(probe.failure(e, s, null).error().message());
      return;
    }
    String path = s.file().path().replace('\\', '/');
    out.note(path.substring(path.lastIndexOf('/') + 1) + (tables + views == 0 ? " has no tables or views yet."
        : " holds " + contents(new int[] {tables, views}).toLowerCase(Locale.ROOT).replace(" · ", " and ") + "."));
  }

  // ── Shared ────────────────────────────────────────────────────────────────────────────────────────────────────

  private static final String NO_TIME = "No time was left to sign in: nothing was looked up on the server.";

  /** A direct connection as Test connection opens it, or a note saying why there is none. */
  private static TestProbe.Opened open(TestProbe probe, ConnectionConfig cfg, SecretResolver secrets,
      ExecContext ctx, ConnectionSettings s, Suggestions.Builder out) {
    int budget = budget(ctx);
    if (budget <= 0 || ctx.cancelled()) {
      out.note(NO_TIME);
      return null;
    }
    TestProbe.Opened o = probe.open(cfg, secrets, ctx, s, budget);
    if (o.failure() != null) {
      out.note(o.failure().error().message());
      return null;
    }
    return o;
  }

  private static int budget(ExecContext ctx) {
    return (int) Math.max(0, Math.min(TestProbe.BUDGET_MS, ctx.remaining().toMillis()));
  }

  /**
   * The schema worth filling in as Default schema: the session's own when it holds tables the user may read (or no
   * schema does), else the only schema that holds any; {@code null} when that isn't clear.
   */
  static String preferredSchema(String current, SqlCatalog.Listing listing) {
    List<String> withTables = listing.schemas().stream().filter(s -> {
      int[] n = listing.counts().get(s);
      return n != null && n[0] + n[1] > 0;
    }).toList();
    if (current != null && (withTables.isEmpty() || withTables.contains(current))) {
      return current;
    }
    return withTables.size() == 1 ? withTables.getFirst() : null;
  }

  /** The listing's schemas as options, each with what the user may read in it. */
  static List<Option> schemaOptions(SqlCatalog.Listing listing, UnaryOperator<String> value) {
    List<Option> options = new ArrayList<>();
    for (String schema : listing.schemas()) {
      String v = value.apply(schema);
      options.add(new Option(v, null, contents(listing.counts().getOrDefault(schema, new int[2]))));
    }
    return options;
  }

  /** {@code 4 tables · 1 view}, or that there is nothing the user may read. */
  static String contents(int[] counts) {
    if (counts[0] + counts[1] == 0) {
      return "No tables you may read";
    }
    List<String> parts = new ArrayList<>();
    if (counts[0] > 0) {
      parts.add(count(counts[0], "table"));
    }
    if (counts[1] > 0) {
      parts.add(count(counts[1], "view"));
    }
    return String.join(" · ", parts);
  }

  /** An Oracle schema as Default schema and the allowlist take it: upper-case names bare, others quoted. */
  static String oracleName(String name) {
    return ORACLE_NAME.matcher(name).matches() ? name : IdentifierRules.quote(name, SqlSyntax.ORACLE);
  }

  static String count(int n, String noun) {
    return n + " " + noun + (n == 1 ? "" : "s");
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
