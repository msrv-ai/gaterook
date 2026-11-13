package ai.msrv.gaterook.connectors.duckdb;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * DuckDB for agents (connector module {@code duckdb}, docs/duckdb.md): one DuckDB database file on this computer.
 * Reads list and describe its tables and run one read-only statement with row, memory and time limits; a change runs
 * one INSERT, UPDATE, DELETE or DDL statement after a person approved exactly that statement, never retried.
 *
 * <p>Two layers keep a read from changing anything. {@link DuckSql} refuses what isn't a read before DuckDB sees it,
 * and DuckDB itself runs every call on a fresh instance ({@link DuckDbEngine}) that opens the file read-only, with
 * external access off except for the connection's data folders, no extension installed or loaded, and its
 * configuration locked. A change gets its own read-write instance with the same locks. The file and the data folders
 * are checked whenever a call opens them ({@link DuckDbFiles}).
 *
 * <p>DuckDB's refusal of a statement (a syntax error, an unknown table, the memory limit) is a result the agent reads
 * ({@code isError}); trouble opening the file, a deadline or a statement GateRook refuses is an exception.
 */
public final class DuckDbConnector implements Connector {

  static final String QUERY = "duckdb_query";
  static final String LIST_TABLES = "duckdb_list_tables";
  static final String DESCRIBE_TABLE = "duckdb_describe_table";
  static final String EXECUTE = "duckdb_execute";

  static final int DEFAULT_QUERY_LIMIT = 100;
  static final int MAX_QUERY_LIMIT = 1_000;
  static final int DEFAULT_LIST_LIMIT = 200;
  /** Test connection gives up after 5 s. */
  static final long TEST_BUDGET_MS = 5_000;

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final Pattern STORAGE = Pattern.compile("storage_version=([^,}\\s]+)");

  private final ConnectorManifest manifest;

  /** The module host's way in: the connector's definition in this JAR. */
  public DuckDbConnector() {
    this(Manifests.own());
  }

  /** The release described by {@code manifest} (tests). */
  DuckDbConnector(JsonNode manifest) {
    this.manifest = Manifests.read(manifest);
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  /** Each call has a DuckDB instance of its own, with the connection's memory limit: two at once per connection. */
  @Override
  public int maxConcurrency() {
    return 2;
  }

  // ── Classification and the exact action ─────────────────────────────────────────────────────────────────────

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    if (tool(call.tool()).isEmpty()) {
      return Classification.deny(policy.rule("unknown-tool"), call.tool() + " isn't a tool of DuckDB "
          + manifest.version() + ".", null);
    }
    DuckDbConfig config;
    try {
      config = DuckDbConfig.of(cfg);
    } catch (ConnectorException e) {
      return Classification.deny(policy.rule("config"), e.getMessage(), null);
    }
    List<String> folders = configuredPrefixes(config);
    return switch (call.tool()) {
      case QUERY -> {
        DuckSql.Verdict v = DuckSql.read(call.string("sql"), folders);
        yield v.ok() ? Classification.read() : Classification.deny(policy.rule("read-only"), v.problem(),
            "duckdb_query only reads. A change goes through duckdb_execute, which waits for a person's approval.");
      }
      case EXECUTE -> {
        DuckSql.Verdict v = DuckSql.change(call.string("sql"), folders);
        if (!v.ok()) {
          yield Classification.deny(policy.rule("change"), v.problem(), null);
        }
        ObjectNode facts = NODES.objectNode().put("statement", v.verb());
        if (v.target() != null) {
          facts.put("target", v.target());
        }
        yield Classification.mutation(v.verb() + (v.target() == null ? "" : " " + v.target()) + " changes "
            + cfg.name() + ".", facts);
      }
      default -> Classification.read();
    };
  }

  /** The statement exactly as it runs, with the limits it runs under; no I/O. */
  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    DuckDbConfig config;
    try {
      config = DuckDbConfig.of(cfg);
    } catch (ConnectorException e) {
      return ActionPreview.of(ActionPreview.Kind.TEXT, "text", call.tool() + " " + call.arguments(), cfg.name(),
          shorten(call.tool() + " on " + cfg.name()));
    }
    String target = cfg.name() + " · " + config.fileName();
    List<String> folders = configuredPrefixes(config);
    ActionPreview.Field limits = new ActionPreview.Field("Limits", "memory " + config.memoryLimitMb() + " MB, "
        + config.threads() + (config.threads() == 1 ? " thread" : " threads") + ", external access off except "
        + (config.dataFolders().isEmpty() ? "nothing" : config.dataFolders().size() + " data "
        + (config.dataFolders().size() == 1 ? "folder" : "folders")) + ", settings locked");
    String sql = call.string("sql");
    switch (call.tool()) {
      case QUERY -> {
        DuckSql.Verdict v = DuckSql.read(sql, folders);
        int rows = Math.min(Math.clamp(call.integer("limit", DEFAULT_QUERY_LIMIT), 1, MAX_QUERY_LIMIT),
            config.maxRows());
        List<String> warnings = v.ok() ? List.of() : List.of("GateRook will refuse this statement: " + v.problem());
        ObjectNode payload = NODES.objectNode().put("sql", sql == null ? "" : sql).put("limit", rows);
        return ActionPreview.of(ActionPreview.Kind.SQL, "sql", sql == null ? "" : sql, target,
                shorten("Query on " + cfg.name()))
            .withFields(List.of(new ActionPreview.Field("Access", "read-only DuckDB instance, at most " + rows
                + " rows" + (v.ok() && v.rows() ? " (an outer LIMIT)" : "")), limits))
            .withWarnings(warnings)
            .withFacets(Map.of("statement", v.ok() ? v.verb() : "refused"))
            .withPayload(payload);
      }
      case EXECUTE -> {
        DuckSql.Verdict v = DuckSql.change(sql, folders);
        List<ActionPreview.Field> impact = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, String> facets = new LinkedHashMap<>();
        if (v.ok()) {
          impact.add(new ActionPreview.Field("Statement", v.verb()));
          impact.add(new ActionPreview.Field("Target", v.target() == null ? "Unknown: not named" : v.target()));
          impact.add(new ActionPreview.Field("Transaction", "One statement in its own transaction: DuckDB rolls it "
              + "back if it fails, and GateRook never retries it"));
          facets.put("statement", v.verb());
          if (v.target() != null) {
            facets.put("tables", v.target());
          }
          if (v.verb().startsWith("DROP") || v.verb().equals("TRUNCATE")) {
            warnings.add(v.verb() + " can't be undone once it commits.");
          }
        } else {
          warnings.add("GateRook will refuse this statement: " + v.problem());
          facets.put("statement", "refused");
        }
        return ActionPreview.of(ActionPreview.Kind.SQL, "sql", sql == null ? "" : sql, target,
                shorten((v.ok() ? v.verb() + (v.target() == null ? "" : " " + v.target()) : "Change") + " on "
                    + cfg.name()))
            .withFields(List.of(new ActionPreview.Field("Access", "read-write DuckDB instance of the file"), limits))
            .withImpact(impact)
            .withWarnings(warnings)
            .withFacets(facets)
            .withPayload(NODES.objectNode().put("sql", sql == null ? "" : sql));
      }
      default -> {
        String what = LIST_TABLES.equals(call.tool()) ? "List the tables" : "Describe " + call.string("table");
        return ActionPreview.of(ActionPreview.Kind.TEXT, "text", call.tool() + " " + call.arguments(), target,
            shorten(what + " of " + cfg.name())).withFields(List.of(limits)).withPayload(call.arguments());
      }
    }
  }

  // ── Setup help ──────────────────────────────────────────────────────────────────────────────────────────────

  /** The databases and data folders next to the typed path ({@link DuckDbSuggestions}), in either scope. */
  @Override
  public Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope) {
    return DuckDbSuggestions.suggest(cfg, ctx);
  }

  // ── Test connection ─────────────────────────────────────────────────────────────────────────────────────────

  /** What the test learns from the open file. */
  private record Probe(String version, long tables, long views, String storage) {}

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    long started = System.nanoTime();
    List<TestResult.Check> checks = new ArrayList<>();
    Target target;
    try {
      target = target(cfg, ctx);
    } catch (ConnectorException e) {
      String code = e.getMessage() != null && e.getMessage().startsWith("This build of the DuckDB connector")
          ? "duckdb.platform" : e.getMessage() != null && e.getMessage().contains("data folder") ? "duckdb.folder"
          : "duckdb.file";
      return TestResult.failure(code, e.getMessage(), null);
    }
    long size = size(target.database());
    checks.add(new TestResult.Check("file", true, ms(started), "Local file · " + bytes(size)));
    long budget = Math.max(100, Math.min(TEST_BUDGET_MS, ctx.remaining().toMillis() - 250));
    long opened = System.nanoTime();
    Probe probe;
    try {
      probe = DuckDbEngine.run(spec(target, false), ctx, budget, (c, call) -> probe(c, call));
    } catch (DuckDbEngine.Failure f) {
      return testFailure(f, target).withChecks(checks);
    } catch (ConnectorException e) {
      return TestResult.failure("duckdb.error", e.getMessage(), null).withChecks(checks);
    }
    long latency = ms(opened);
    checks.add(new TestResult.Check("open", true, latency, "Opened read-only · DuckDB " + probe.version()
        + (probe.storage() == null ? "" : " · storage " + probe.storage())));
    boolean writable = DuckDbFiles.writable(target.database());
    checks.add(new TestResult.Check("permissions", true, 0, writable ? "Can write" : "Read only"));
    checks.add(new TestResult.Check("data folders", true, 0, target.folders().isEmpty() ? "None: queries read the "
        + "database file only" : target.folders().size() + (target.folders().size() == 1 ? " folder" : " folders")));

    ObjectNode permissionDetails = NODES.objectNode();
    permissionDetails.put("schemaChanges", writable ? "Through duckdb_execute, after approval" : "None: the file "
        + "can't be written here");
    TestResult.Permissions permissions = new TestResult.Permissions(writable ? TestResult.Level.WRITE
        : TestResult.Level.READ, "SELECT on " + count(probe.tables(), "table") + " and " + count(probe.views(), "view"),
        writable ? "INSERT, UPDATE, DELETE and DDL through duckdb_execute, after approval" : null, List.of(),
        permissionDetails);
    TestResult result = TestResult.success(latency, "DuckDB " + probe.version(), null, permissions);
    for (TestResult.Hint h : hints(cfg.env(), writable, probe, target)) {
      result = result.withHint(h);
    }
    ObjectNode details = NODES.objectNode();
    details.putObject("file").put("name", target.config().fileName()).put("sizeBytes", size);
    details.put("duckdbVersion", probe.version());
    if (probe.storage() != null) {
      details.put("storageVersion", probe.storage());
    }
    details.put("tables", probe.tables());
    details.put("views", probe.views());
    details.put("writable", writable);
    details.put("dataFolders", target.folders().size());
    details.put("memoryLimitMb", target.config().memoryLimitMb());
    details.put("threads", target.config().threads());
    details.put("totalMs", ms(started));
    ObjectNode metadata = NODES.objectNode().put("serverVersion", "DuckDB " + probe.version());
    return result.withChecks(checks).withDetails(details).withMetadata(metadata);
  }

  private static Probe probe(Connection c, DuckDbEngine.Call call) throws SQLException {
    try (Statement st = c.createStatement()) {
      call.running(st);
      String version;
      try (ResultSet rs = st.executeQuery("SELECT library_version FROM pragma_version()")) {
        version = rs.next() ? rs.getString(1) : "unknown";
      }
      long tables;
      try (ResultSet rs = st.executeQuery("SELECT count(*) FROM duckdb_tables() WHERE database_name = "
          + "current_database() AND NOT internal AND NOT temporary")) {
        tables = rs.next() ? rs.getLong(1) : 0;
      }
      long views;
      try (ResultSet rs = st.executeQuery("SELECT count(*) FROM duckdb_views() WHERE database_name = "
          + "current_database() AND NOT internal AND NOT temporary")) {
        views = rs.next() ? rs.getLong(1) : 0;
      }
      String storage = null;
      try (ResultSet rs = st.executeQuery("SELECT CAST(tags AS VARCHAR) FROM duckdb_databases() WHERE database_name "
          + "= current_database()")) {
        if (rs.next() && rs.getString(1) != null) {
          Matcher m = STORAGE.matcher(rs.getString(1));
          storage = m.find() ? m.group(1) : null;
        }
      }
      return new Probe(version, tables, views, storage);
    }
  }

  /** The kit's tone, as for SQLite files: what GateRook may do with the file, and what the person chose. */
  static List<TestResult.Hint> hints(Env env, boolean writable, Probe probe, Target target) {
    List<TestResult.Hint> out = new ArrayList<>();
    if (writable) {
      boolean dev = env == Env.DEV;
      out.add(new TestResult.Hint(dev ? TestResult.Severity.INFO : TestResult.Severity.WARNING,
          "duckdb.file-writable", "/file", "GateRook may write this file. " + (dev ? "Fine for dev: every change "
          + "still waits for approval." : "Every change still waits for approval, but on " + env.id() + " use a "
          + "read-only copy of the file, or keep duckdb_execute off.")));
    } else {
      out.add(new TestResult.Hint(TestResult.Severity.INFO, "duckdb.file-read-only", "/file", "GateRook can't write "
          + "this file here, so duckdb_execute fails: the file or its folder is read-only, or the connector's sandbox "
          + "lets it write only its own folder."));
    }
    if (probe.tables() + probe.views() == 0) {
      out.add(new TestResult.Hint(TestResult.Severity.INFO, "duckdb.empty-database", "/file",
          "The database has no tables or views yet."));
    }
    if (!target.folders().isEmpty()) {
      out.add(new TestResult.Hint(TestResult.Severity.INFO, "duckdb.data-folders", "/dataFolders", "Queries may read "
          + "the files of " + count(target.folders().size(), "data folder") + ". DuckDB would let a statement write "
          + "files there too, so GateRook refuses COPY and EXPORT, and a data folder can't hold the database file."));
    }
    return out;
  }

  private static TestResult testFailure(DuckDbEngine.Failure f, Target target) {
    String file = target.config().fileName();
    String technical = f.sql == null ? null : DuckDbErrors.message(f.sql);
    if (f.stopped) {
      return TestResult.failure("duckdb.timeout", "The connection test timed out.", technical);
    }
    if (f.sql != null && DuckDbErrors.lockConflict(f.sql)) {
      return TestResult.failure("duckdb.locked", "Another program has " + file + " open for writing. DuckDB lets "
          + "other programs read a database file only while none writes it: close that program, or point the "
          + "connection at a copy of the file.", technical);
    }
    if (f.sql != null && DuckDbErrors.handleConflict(f.sql)) {
      return TestResult.failure("duckdb.busy", "A change of this connection has " + file + " open. Test again in a "
          + "moment.", technical);
    }
    if (f.sql != null && DuckDbErrors.notPermitted(f.sql)) {
      return TestResult.failure("duckdb.file", "GateRook may not read " + file + " here.", technical);
    }
    if (f.opening) {
      return TestResult.failure("duckdb.open", "DuckDB can't open " + file + ": " + (technical == null
          ? f.getMessage() : technical), technical);
    }
    return TestResult.failure("duckdb.error", technical == null ? f.getMessage() : technical, technical);
  }

  // ── Execution ───────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    ToolDefinition tool = tool(call.tool()).orElseThrow(() -> ConnectorException.badInput(call.tool()
        + " isn't a tool of DuckDB " + manifest.version() + "."));
    if (tool.nature() != ToolDefinition.Nature.READ && !ctx.approved()) {
      // Defence in depth: the gateway sends a change only after a person approved it (or the tool is in Bypass).
      throw ConnectorException.denied("Not approved: " + call.tool() + " changes the database file, so a person "
          + "approves it first.");
    }
    Target target = target(cfg, ctx);
    return switch (call.tool()) {
      case QUERY -> query(call, target, ctx);
      case LIST_TABLES -> listTables(call, target, ctx);
      case DESCRIBE_TABLE -> describeTable(call, target, ctx);
      case EXECUTE -> change(call, target, ctx);
      default -> throw ConnectorException.badInput(call.tool() + " isn't implemented here.");
    };
  }

  /** The checked files of one call and its limits: the database, the data folders, their prefixes. */
  record Target(DuckDbConfig config, Path database, List<DuckDbFiles.Folder> folders, List<String> prefixes) {}

  /** The connection's files, checked now (links included), or why the call can't open them. */
  static Target target(ConnectionConfig cfg, ExecContext ctx) throws ConnectorException {
    DuckDbConfig config = DuckDbConfig.of(cfg);
    String missing = DuckDbEngine.missingLibrary();
    if (missing != null) {
      throw ConnectorException.unavailable(missing, null);
    }
    Path home = DuckDbFiles.home(ctx.workDir());
    Path database = DuckDbFiles.database(config.file(), home);
    List<DuckDbFiles.Folder> folders = DuckDbFiles.folders(config.dataFolders(), home, database);
    List<Path> forms = new ArrayList<>();
    for (DuckDbFiles.Folder f : folders) {
      forms.add(f.configured());
      forms.add(f.real());
    }
    DuckDbEngine.cleanStaleLibraries(ctx.workDir());
    return new Target(config, database, folders, DuckDbFiles.prefixes(forms));
  }

  private static DuckDbEngine.Spec spec(Target target, boolean writable) {
    return new DuckDbEngine.Spec(target.database(), writable, target.prefixes(), target.config().memoryLimitMb(),
        target.config().threads());
  }

  private ToolResult query(ToolCall call, Target target, ExecContext ctx) throws ConnectorException {
    String sql = statement(call, ctx);
    DuckSql.Verdict v = DuckSql.read(sql, target.prefixes());
    if (!v.ok()) {
      // Defence in depth: DuckDB would refuse a change itself, but the agent learns why here.
      throw ConnectorException.denied(v.problem());
    }
    int limit = rows(arguments(call, ctx).path("limit").asInt(DEFAULT_QUERY_LIMIT), target.config(), ctx);
    // DuckDB bounds the rows itself: the statement as written, inside an outer LIMIT of one row more.
    String run = v.rows() ? "SELECT * FROM (\n" + v.text() + "\n) LIMIT " + (limit + 1) : v.text();
    long timeout = timeoutMs(ctx, target.config());
    try {
      DuckDbResults.Read read = DuckDbEngine.run(spec(target, false), ctx, timeout, (c, state) -> {
        try (Statement st = c.createStatement()) {
          state.running(st);
          if (!st.execute(run)) {
            return DuckDbResults.empty();
          }
          try (ResultSet rs = st.getResultSet()) {
            return DuckDbResults.read(rs, limit, maxBytes(ctx), maxCells(ctx), false);
          }
        }
      });
      ToolResult result = ToolResult.table(read.table(), null);
      return read.truncatedReason() == null ? result : result.truncated(read.truncatedReason());
    } catch (DuckDbEngine.Failure f) {
      return readFailure(f, target, ctx, timeout);
    }
  }

  private ToolResult listTables(ToolCall call, Target target, ExecContext ctx) throws ConnectorException {
    int limit = rows(Math.clamp(call.integer("limit", DEFAULT_LIST_LIMIT), 1, 1_000), target.config(), ctx);
    String schema = blankToNull(call.string("schema"));
    String like = blankToNull(call.string("like"));
    StringBuilder sql = new StringBuilder("SELECT * FROM (SELECT schema_name AS \"schema\", table_name AS \"name\", "
        + "'table' AS kind, estimated_size AS estimated_rows, column_count AS \"columns\", comment FROM "
        + "duckdb_tables() WHERE database_name = current_database() AND NOT internal AND NOT temporary UNION ALL "
        + "SELECT schema_name, view_name, 'view', NULL, column_count, comment FROM duckdb_views() WHERE "
        + "database_name = current_database() AND NOT internal AND NOT temporary) AS listed WHERE true");
    List<String> params = new ArrayList<>();
    if (schema != null) {
      sql.append(" AND lower(\"schema\") = lower(?)");
      params.add(schema);
    }
    if (like != null) {
      sql.append(" AND \"name\" ILIKE ?");
      params.add(like);
    }
    sql.append(" ORDER BY \"schema\", \"name\" LIMIT ").append(limit + 1);
    long timeout = timeoutMs(ctx, target.config());
    try {
      DuckDbResults.Read read = DuckDbEngine.run(spec(target, false), ctx, timeout,
          (c, state) -> {
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
              for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
              }
              state.running(ps);
              try (ResultSet rs = ps.executeQuery()) {
                return DuckDbResults.read(rs, limit, maxBytes(ctx), maxCells(ctx), false);
              }
            }
          });
      ObjectNode structured = NODES.objectNode().put("rowCount", read.table().rows().size())
          .put("truncated", read.truncatedReason() != null);
      ToolResult result = ToolResult.table(read.table(), structured);
      return read.truncatedReason() == null ? result : result.truncated(read.truncatedReason());
    } catch (DuckDbEngine.Failure f) {
      return readFailure(f, target, ctx, timeout);
    }
  }

  private ToolResult describeTable(ToolCall call, Target target, ExecContext ctx) throws ConnectorException {
    String table = blankToNull(call.string("table"));
    if (table == null) {
      throw ConnectorException.badInput("table is required.");
    }
    String schema = blankToNull(call.string("schema"));
    int cells = maxCells(ctx);
    long timeout = timeoutMs(ctx, target.config());
    try {
      return DuckDbEngine.run(spec(target, false), ctx, timeout, (c, state) -> describe(c, state, table, schema,
          cells));
    } catch (DuckDbEngine.Failure f) {
      return readFailure(f, target, ctx, timeout);
    }
  }

  private static ToolResult describe(Connection c, DuckDbEngine.Call state, String table, String schema, int cells)
      throws SQLException {
    String where = " WHERE database_name = current_database() AND NOT internal AND NOT temporary AND lower(%s) = "
        + "lower(?)" + (schema == null ? "" : " AND lower(schema_name) = lower(?)");
    String sql = "SELECT schema_name, table_name, 'table', estimated_size, comment, sql FROM duckdb_tables()"
        + where.formatted("table_name") + " UNION ALL SELECT schema_name, view_name, 'view', NULL, comment, sql FROM "
        + "duckdb_views()" + where.formatted("view_name") + " ORDER BY 1, 2";
    List<String[]> found = new ArrayList<>();
    Long estimated = null;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      int p = 1;
      for (int part = 0; part < 2; part++) {
        ps.setString(p++, table);
        if (schema != null) {
          ps.setString(p++, schema);
        }
      }
      state.running(ps);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          found.add(new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(5),
              rs.getString(6)});
          if (found.size() == 1) {
            long n = rs.getLong(4);
            estimated = rs.wasNull() ? null : n;
          }
        }
      }
    }
    if (found.isEmpty()) {
      return ToolResult.error((schema == null ? "" : schema + ".") + table + " isn't a table or view of this "
          + "database. duckdb_list_tables lists them.");
    }
    if (found.size() > 1) {
      List<String> schemas = found.stream().map(f -> f[0]).distinct().toList();
      return ToolResult.error(table + " is in several schemas (" + String.join(", ", schemas) + "): name the "
          + "schema.");
    }
    String[] t = found.getFirst();
    ObjectNode out = NODES.objectNode();
    out.put("schema", t[0]);
    out.put("name", t[1]);
    out.put("kind", t[2]);
    if (estimated == null) {
      out.putNull("estimatedRows");
    } else {
      out.put("estimatedRows", estimated);
    }
    putText(out, "comment", t[3], cells);
    ArrayNode columns = out.putArray("columns");
    List<String> lines = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement("SELECT column_name, data_type, is_nullable, column_default, "
        + "comment FROM duckdb_columns() WHERE database_name = current_database() AND schema_name = ? AND "
        + "table_name = ? ORDER BY column_index")) {
      ps.setString(1, t[0]);
      ps.setString(2, t[1]);
      state.running(ps);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next() && columns.size() < 2_000) {
          ObjectNode col = columns.addObject();
          col.put("name", rs.getString(1));
          col.put("type", rs.getString(2));
          col.put("nullable", rs.getBoolean(3));
          putText(col, "default", rs.getString(4), cells);
          putText(col, "comment", rs.getString(5), cells);
          lines.add(rs.getString(1) + " " + rs.getString(2) + (rs.getBoolean(3) ? "" : " NOT NULL"));
        }
      }
    }
    ArrayNode primaryKey = out.putArray("primaryKey");
    ArrayNode constraints = out.putArray("constraints");
    try (PreparedStatement ps = c.prepareStatement("SELECT constraint_type, constraint_text, "
        + "constraint_column_names FROM duckdb_constraints() WHERE database_name = current_database() AND "
        + "schema_name = ? AND table_name = ? AND constraint_type <> 'NOT NULL' ORDER BY constraint_index")) {
      ps.setString(1, t[0]);
      ps.setString(2, t[1]);
      state.running(ps);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next() && constraints.size() < 500) {
          ObjectNode con = constraints.addObject();
          con.put("type", rs.getString(1));
          putText(con, "text", rs.getString(2), cells);
          if ("PRIMARY KEY".equals(rs.getString(1)) && primaryKey.isEmpty()) {
            java.sql.Array names = rs.getArray(3);
            if (names != null) {
              for (Object name : (Object[]) names.getArray()) {
                primaryKey.add(String.valueOf(name));
              }
            }
          }
        }
      }
    }
    ArrayNode indexes = out.putArray("indexes");
    try (PreparedStatement ps = c.prepareStatement("SELECT index_name, is_unique, sql FROM duckdb_indexes() WHERE "
        + "database_name = current_database() AND schema_name = ? AND table_name = ? ORDER BY index_name")) {
      ps.setString(1, t[0]);
      ps.setString(2, t[1]);
      state.running(ps);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next() && indexes.size() < 500) {
          ObjectNode index = indexes.addObject();
          index.put("name", rs.getString(1));
          index.put("unique", rs.getBoolean(2));
          putText(index, "sql", rs.getString(3), cells);
        }
      }
    }
    putText(out, "sql", t[4], cells);
    String head = t[0] + "." + t[1] + " · " + t[2] + (estimated == null ? "" : " · ~" + estimated + " rows")
        + (primaryKey.isEmpty() ? "" : " · primary key (" + String.join(", ", strings(primaryKey)) + ")");
    return ToolResult.json(out, head + "\n" + String.join("\n", lines));
  }

  private ToolResult change(ToolCall call, Target target, ExecContext ctx) throws ConnectorException {
    String sql = statement(call, ctx);
    DuckSql.Verdict v = DuckSql.change(sql, target.prefixes());
    if (!v.ok()) {
      throw ConnectorException.denied(v.problem());
    }
    String file = target.config().fileName();
    int limit = rows(DEFAULT_QUERY_LIMIT, target.config(), ctx);
    long started = System.nanoTime();
    ObjectNode out;
    try {
      // Exactly the approved text: DuckDB parses it as written.
      out = DuckDbEngine.run(spec(target, true), ctx, timeoutMs(ctx, target.config()), (c, state) -> {
        try (Statement st = c.createStatement()) {
          state.running(st);
          ObjectNode o = NODES.objectNode();
          o.put("statement", v.verb());
          if (v.target() == null) {
            o.putNull("target");
          } else {
            o.put("target", v.target());
          }
          if (st.execute(sql)) {
            try (ResultSet rs = st.getResultSet()) {
              DuckDbResults.Read read = DuckDbResults.read(rs, limit, maxBytes(ctx), maxCells(ctx), true);
              o.put("rowsChanged", read.total());
              ArrayNode columns = o.putArray("columns");
              read.table().columns().forEach(col -> columns.add(col.name()));
              o.set("returned", read.table().rows());
              o.put("truncated", read.truncatedReason() != null);
            }
          } else {
            long count = st.getUpdateCount();
            if (count < 0) {
              o.putNull("rowsChanged");
            } else {
              o.put("rowsChanged", count);
            }
          }
          return o;
        }
      });
    } catch (DuckDbEngine.Failure f) {
      return changeFailure(f, file, ctx);
    }
    out.put("elapsedMs", ms(started));
    JsonNode changed = out.path("rowsChanged");
    String text = v.verb() + (v.target() == null ? "" : " " + v.target()) + ": " + (changed.isNumber()
        ? count(changed.asLong(), "row") + " changed." : "done.");
    return ToolResult.json(out, text);
  }

  /** What a read's failure means for the agent: DuckDB's refusal is data; the file or the deadline aren't. */
  private static ToolResult readFailure(DuckDbEngine.Failure f, Target target, ExecContext ctx, long timeoutMs)
      throws ConnectorException {
    String file = target.config().fileName();
    if (f.opening) {
      throw openFailure(f, file, "");
    }
    if (f.stopped) {
      if (f.deadline || ctx.remaining().isZero()) {
        throw ConnectorException.timeout("The query ran past its time limit (" + seconds(timeoutMs) + ") and "
            + "GateRook stopped it. Narrow it down and try again.");
      }
      throw ConnectorException.resourceError("The call was cancelled: DuckDB stopped the query.", f);
    }
    if (f.sql != null && DuckDbErrors.outOfMemory(f.sql)) {
      return ToolResult.error("The query needs more memory than this connection's limit of "
          + target.config().memoryLimitMb() + " MB, so DuckDB stopped it (nothing spills to disk). Narrow it down "
          + "(fewer columns, a WHERE, an aggregate), or raise Memory limit in the connection's settings.");
    }
    if (f.sql != null && DuckDbErrors.outsideFolders(f.sql)) {
      return ToolResult.error("DuckDB refused it: " + DuckDbErrors.message(f.sql) + " Queries read files only in the "
          + "connection's data folders.");
    }
    return ToolResult.error("DuckDB refused the query: " + DuckDbErrors.message(f.sql));
  }

  /** A change that didn't run, ran into DuckDB's refusal, or stopped: never retried, and said so. */
  private static ToolResult changeFailure(DuckDbEngine.Failure f, String file, ExecContext ctx)
      throws ConnectorException {
    if (f.opening) {
      if (f.sql != null && DuckDbErrors.notPermitted(f.sql)) {
        throw ConnectorException.resourceError("GateRook can't open " + file + " for writing here: the file or its "
            + "folder is read-only, or the connector's sandbox lets it write only its own folder. Nothing changed.", f);
      }
      throw openFailure(f, file, " Nothing changed.");
    }
    if (f.stillRunning) {
      throw ConnectorException.outcomeUnknown("The change didn't stop at its deadline: whether it applied is unknown. "
          + "Check the data before you try again.", f);
    }
    if (f.stopped) {
      if (f.deadline || ctx.remaining().isZero()) {
        throw ConnectorException.timeout("The change ran past its time limit, and DuckDB stopped it and rolled it "
            + "back: nothing changed.");
      }
      throw ConnectorException.resourceError("The change was cancelled: DuckDB rolled it back, nothing changed.", f);
    }
    if (f.sql != null && DuckDbErrors.outOfMemory(f.sql)) {
      return ToolResult.error("The change needs more memory than this connection's limit, so DuckDB stopped it and "
          + "rolled it back: nothing changed.");
    }
    return ToolResult.error("DuckDB refused the change, so nothing changed: " + DuckDbErrors.message(f.sql));
  }

  private static ConnectorException openFailure(DuckDbEngine.Failure f, String file, String after) {
    if (f.sql == null) {
      // Another call kept the file busy past the deadline, or the call ended before DuckDB opened the file.
      return f.stopped && !f.deadline ? ConnectorException.resourceError("The call was cancelled before DuckDB "
          + "opened " + file + "." + after, f) : ConnectorException.timeout(f.getMessage() + after);
    }
    if (DuckDbErrors.lockConflict(f.sql)) {
      return ConnectorException.unavailable("Another program has " + file + " open for writing. DuckDB lets other "
          + "programs read a database file only while none writes it: close that program, or point the connection "
          + "at a copy of the file." + after, f);
    }
    if (DuckDbErrors.handleConflict(f.sql)) {
      return ConnectorException.unavailable("Another call of this connection has " + file + " open: try again in a "
          + "moment." + after, f);
    }
    if (DuckDbErrors.notPermitted(f.sql)) {
      return ConnectorException.unavailable("GateRook may not read " + file + " here." + after, f);
    }
    return ConnectorException.unavailable("DuckDB can't open " + file + ": " + DuckDbErrors.message(f.sql) + after,
        f);
  }

  // ── Arguments and limits ────────────────────────────────────────────────────────────────────────────────────

  /**
   * The statement that runs: the call's own. When the core passes on the payload it had approved, that payload
   * must say the same, or nothing runs.
   */
  static String statement(ToolCall call, ExecContext ctx) throws ConnectorException {
    String args = call.string("sql");
    if (ctx.approved() && ctx.approvedPayload().isPresent() && ctx.approvedPayload().get().path("sql").isString()) {
      String approved = ctx.approvedPayload().get().get("sql").asString();
      if (args != null && !args.equals(approved)) {
        throw ConnectorException.denied("The approved statement isn't this call's: nothing ran.");
      }
      return approved;
    }
    if (args == null || args.isBlank()) {
      throw ConnectorException.badInput("sql is required.");
    }
    return args;
  }

  private static JsonNode arguments(ToolCall call, ExecContext ctx) {
    if (ctx.approved() && ctx.approvedPayload().isPresent() && ctx.approvedPayload().get().has("limit")) {
      return ctx.approvedPayload().get();
    }
    return call.arguments();
  }

  /** The rows a call returns at most: what it asks for, within the connection's and the environment's limits. */
  static int rows(int requested, DuckDbConfig config, ExecContext ctx) {
    int env = ctx.limits() == null || ctx.limits().maxRows() <= 0 ? Integer.MAX_VALUE : ctx.limits().maxRows();
    return Math.max(1, Math.min(Math.min(Math.clamp(requested, 1, MAX_QUERY_LIMIT * 10), config.maxRows()), env));
  }

  /**
   * The statement timeout of the call: the policy's, or the connection's own where it is stricter, but never past the
   * call's deadline.
   */
  static long timeoutMs(ExecContext ctx, DuckDbConfig config) {
    long limit = ctx.limits() != null && ctx.limits().statementTimeout() != null
        && !ctx.limits().statementTimeout().isZero() ? ctx.limits().statementTimeout().toMillis() : 30_000;
    if (config.statementTimeoutMs() != null) {
      limit = Math.min(limit, config.statementTimeoutMs());
    }
    long left = ctx.remaining().toMillis() - 250;
    return Math.max(100, Math.min(limit, left));
  }

  private static long maxBytes(ExecContext ctx) {
    return ctx.limits() == null || ctx.limits().maxResponseBytes() <= 0 ? 524_288 : ctx.limits().maxResponseBytes();
  }

  private static int maxCells(ExecContext ctx) {
    return ctx.limits() == null || ctx.limits().maxCellChars() <= 0 ? 8_192 : ctx.limits().maxCellChars();
  }

  /** The data folders as written in the connection, as {@code /…/} prefixes: what classification knows, no I/O. */
  private static List<String> configuredPrefixes(DuckDbConfig config) {
    return DuckDbFiles.prefixes(DuckDbFiles.configuredFolders(config.dataFolders()));
  }

  // ── Small things ────────────────────────────────────────────────────────────────────────────────────────────

  private Optional<ToolDefinition> tool(String name) {
    return manifest.tools().stream().filter(t -> t.name().equals(name)).findFirst();
  }

  private static void putText(ObjectNode out, String name, String value, int cells) {
    if (value == null) {
      out.putNull(name);
    } else {
      out.set(name, DuckDbResults.text(value, cells));
    }
  }

  private static List<String> strings(ArrayNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.asString()));
    return out;
  }

  private static String blankToNull(String v) {
    return v == null || v.isBlank() ? null : v.strip();
  }

  private static long size(Path file) {
    try {
      return Files.size(file);
    } catch (IOException | SecurityException e) {
      return 0;
    }
  }

  private static String bytes(long n) {
    double mib = n / 1048576.0;
    return mib < 0.1 ? (n / 1024) + " KiB" : String.format(Locale.US, "%.1f MiB", mib);
  }

  private static String count(long n, String noun) {
    return n + " " + noun + (n == 1 ? "" : "s");
  }

  private static String seconds(long ms) {
    return ms % 1000 == 0 ? (ms / 1000) + " s" : String.format(Locale.US, "%.1f s", ms / 1000.0);
  }

  private static String shorten(String s) {
    return s.length() <= 120 ? s : s.substring(0, 119) + "…";
  }

  private static long ms(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
