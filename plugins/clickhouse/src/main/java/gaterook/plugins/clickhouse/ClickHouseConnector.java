package gaterook.plugins.clickhouse;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * ClickHouse for agents (catalog plugin {@code clickhouse} 0.6.1, step K6): read-only analytics over the HTTP
 * interface — one query with row and time limits, databases, tables, a table's columns, a query's plan and the queries
 * running now. Nothing here writes: a statement that isn't a read is refused before it leaves the plugin
 * ({@link ReadOnlySql}), and ClickHouse runs every query with {@code readonly=1} and {@code allow_ddl=0}.
 *
 * <p>ClickHouse's refusals of a query (a syntax error, an unknown table, no privilege, the time limit) are results the
 * agent reads ({@code isError}); trouble reaching ClickHouse is an exception the gateway counts against the connection.
 */
public final class ClickHouseConnector implements Connector {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final Pattern WRITE_GRANT = Pattern.compile(
      "\\b(ALL|INSERT|ALTER|CREATE|DROP|TRUNCATE|OPTIMIZE|KILL|SYSTEM|UNDROP|BACKUP)\\b");
  private static final int QUERY_TEXT = 300;

  private final ConnectorManifest manifest;

  /** The plugin host's way in: the manifest of this JAR. */
  public ClickHouseConnector() {
    this(Manifests.own());
  }

  /** The release described by {@code manifest} (tests). */
  ClickHouseConnector(JsonNode manifest) {
    this.manifest = Manifests.read(manifest);
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  @Override
  public int maxConcurrency() {
    return 4;
  }

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    if (tool(call.tool()).isEmpty()) {
      return Classification.deny(policy.rule("unknown-tool"), call.tool() + " isn't a tool of ClickHouse "
          + manifest.version() + ".", null);
    }
    try {
      ClickHouseConfig config = callConfig(call, cfg);
      Optional<String> scope = scopeProblem(call, config);
      if (scope.isPresent()) return Classification.deny(policy.rule("clickhouse.database"), scope.get(), null);
    } catch (ConnectorException e) {
      return Classification.deny(policy.rule("clickhouse.database"), e.getMessage(), null);
    }
    Optional<String> problem = switch (call.tool()) {
      case "clickhouse_query" -> ReadOnlySql.check(call.string("sql")).problem();
      case "clickhouse_explain" -> ReadOnlySql.checkQuery(call.string("sql")).problem();
      default -> Optional.empty();
    };
    return problem.map(p -> Classification.deny(policy.rule("clickhouse.read-only"), p,
        "This plugin only reads. Write the change in ClickHouse itself.")).orElseGet(Classification::read);
  }

  /** The query exactly as it goes to ClickHouse, with the limits it carries; no I/O, no secret. */
  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    ClickHouseConfig config;
    try {
      config = callConfig(call, cfg);
    } catch (ConnectorException e) {
      return ActionPreview.of(ActionPreview.Kind.TEXT, "text", call.tool() + " " + call.arguments(), cfg.name(),
          call.tool() + " on " + cfg.name());
    }
    String target = cfg.name() + " · " + config.host() + ":" + config.port() + "/" + config.database();
    Optional<Statement> statement = statement(call, config, Integer.MAX_VALUE);
    if (statement.isEmpty()) {
      return ActionPreview.of(ActionPreview.Kind.TEXT, "text", call.tool() + " " + call.arguments(), target,
          call.tool() + " on " + cfg.name());
    }
    Statement s = statement.get();
    StringBuilder text = new StringBuilder(s.sql());
    if (!s.params().isEmpty()) {
      text.append("\n-- parameters: ").append(s.params());
    }
    return ActionPreview.of(ActionPreview.Kind.SQL, "sql", text.toString(), target, s.summary())
        .withFields(List.of(new ActionPreview.Field("Limits", "readonly=1, at most " + config.maxRows() + " rows, "
            + config.maxExecutionSeconds() + " s")));
  }

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    long started = System.nanoTime();
    List<TestResult.Check> checks = new ArrayList<>();
    ClickHouseClient ch;
    try {
      ch = ClickHouseClient.of(cfg, secrets, ctx);
    } catch (ConnectorException e) {
      return TestResult.failure("config", e.getMessage(), null);
    }
    String where = ch.config().host() + ":" + ch.config().port();
    try {
      long t0 = System.nanoTime();
      ClickHouseClient.Answer about = ch.query("SELECT version(), currentUser(), currentDatabase()", Map.of(), 1);
      checks.add(new TestResult.Check("Query", about.ok(), ms(t0), about.ok() ? null : about.failure().message()));
      if (!about.ok()) {
        return testFailure(about.failure(), checks);
      }
      JsonNode row = about.table().rows().path(0);
      String version = row.path(0).asString(null);
      String user = row.path(1).asString(null);
      for (String database : ch.config().databases()) {
        if (database.equals(ch.config().database())) continue;
        t0 = System.nanoTime();
        ClickHouseClient selected = new ClickHouseClient(ch.config().forDatabase(database, true), secrets, ctx);
        ClickHouseClient.Answer access = selected.query("SELECT currentDatabase()", Map.of(), 1);
        checks.add(new TestResult.Check("Database " + database, access.ok(), ms(t0),
            access.ok() ? null : access.failure().message()));
        if (!access.ok()) return testFailure(access.failure(), checks);
      }
      t0 = System.nanoTime();
      ClickHouseClient.Answer grants = ch.query("SHOW GRANTS", Map.of(), 200);
      checks.add(new TestResult.Check("Grants", grants.ok(), ms(t0), grants.ok() ? null
          : grants.failure().message()));
      List<String> writes = new ArrayList<>();
      if (grants.ok()) {
        for (JsonNode g : grants.table().rows()) {
          String line = g.path(0).asString("").toUpperCase(Locale.ROOT);
          var m = WRITE_GRANT.matcher(line.replaceFirst("\\bON\\b.*$", ""));
          while (m.find()) {
            if (!writes.contains(m.group(1))) {
              writes.add(m.group(1));
            }
          }
        }
      }
      ObjectNode details = NODES.objectNode();
      details.put("readonlySent", true);
      details.put("maxRows", ch.config().maxRows());
      details.put("maxExecutionSeconds", ch.config().maxExecutionSeconds());
      TestResult.Permissions permissions = new TestResult.Permissions(!grants.ok() ? TestResult.Level.UNKNOWN
          : writes.isEmpty() ? TestResult.Level.READ : TestResult.Level.WRITE, "SELECT",
          writes.isEmpty() ? null : String.join(", ", writes), List.of(), details);
      TestResult result = TestResult.success(ms(started), version, user, permissions).withChecks(checks);
      if (!writes.isEmpty()) {
        result = result.withHint(new TestResult.Hint(TestResult.Severity.WARNING, "can-write", null, "This user "
            + "may also " + String.join(", ", writes) + ". MCP Gateway sends readonly=1 with every query, but use a "
            + "user that can only read for prod."));
      }
      return result;
    } catch (ConnectorException e) {
      checks.add(new TestResult.Check("Reach " + where, false, ms(started), e.getMessage()));
      return TestResult.failure(e.kind() == ConnectorException.Kind.DENIED ? "denied" : "unreachable",
          "ClickHouse can't be reached at " + where + ": " + e.getMessage(), e.getMessage()).withChecks(checks);
    }
  }

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    tool(call.tool()).orElseThrow(() -> ConnectorException.badInput(call.tool() + " isn't a tool of ClickHouse "
        + manifest.version() + "."));
    ClickHouseConfig config = callConfig(call, cfg);
    Optional<String> scope = scopeProblem(call, config);
    if (scope.isPresent()) throw ConnectorException.denied(scope.get());
    ClickHouseClient ch = new ClickHouseClient(config, secrets, ctx);
    int envRows = ctx.limits() == null ? Integer.MAX_VALUE : ctx.limits().maxRows();
    int rows = Math.min(ch.config().maxRows(), envRows > 0 ? envRows : Integer.MAX_VALUE);
    return switch (call.tool()) {
      case "clickhouse_query" -> query(ch, call, rows);
      case "clickhouse_describe_table" -> describe(ch, call);
      case "clickhouse_list_databases", "clickhouse_list_tables", "clickhouse_explain",
          "clickhouse_running_queries" -> table(ch, call, rows);
      default -> throw ConnectorException.badInput(call.tool() + " isn't implemented here.");
    };
  }

  @Override
  public TestResult discoverDatabases(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    long started = System.nanoTime();
    try {
      ObjectNode settings = (ObjectNode) cfg.config().deepCopy();
      settings.remove("databases");
      settings.remove("database");
      ConnectionConfig server = new ConnectionConfig(cfg.id(), cfg.name(), cfg.type(), cfg.env(), cfg.version(),
          settings, cfg.secretFields(), cfg.metadata());
      ClickHouseClient client = ClickHouseClient.of(server, secrets, ctx);
      ClickHouseClient.Answer answer = client.discoverDatabases();
      if (!answer.ok()) return testFailure(answer.failure(), List.of());
      ObjectNode details = NODES.objectNode();
      ArrayNode names = details.putArray("databases");
      answer.table().rows().forEach(row -> {
        String name = row.path(0).asString("");
        if (!name.isBlank() && names.size() < 1000) names.add(name);
      });
      details.put("discoveryComplete", answer.table().rows().size() <= 1000);
      return TestResult.success(ms(started), null, client.config().user(), null).withDetails(details);
    } catch (ConnectorException e) {
      return TestResult.failure("discovery", e.getMessage(), null);
    }
  }

  private static ClickHouseConfig callConfig(ToolCall call, ConnectionConfig cfg) throws ConnectorException {
    boolean needsDatabase = !"clickhouse_list_databases".equals(call.tool())
        && !"clickhouse_running_queries".equals(call.tool());
    return ClickHouseConfig.of(cfg).forDatabase(call.string("database"), needsDatabase);
  }

  private static Optional<String> scopeProblem(ToolCall call, ClickHouseConfig config) {
    if (config.databases().isEmpty()) return Optional.empty();
    if ("clickhouse_running_queries".equals(call.tool())) {
      return Optional.of("Running queries are server-wide and unavailable when only selected databases are exposed.");
    }
    return "clickhouse_query".equals(call.tool()) || "clickhouse_explain".equals(call.tool())
        ? ReadOnlySql.databaseProblem(call.string("sql"), config.databases()) : Optional.empty();
  }

  // ── Tools ───────────────────────────────────────────────────────────────────────────────────────────────────

  private ToolResult query(ClickHouseClient ch, ToolCall call, int maxRows) throws ConnectorException {
    ReadOnlySql.Result checked = ReadOnlySql.check(call.string("sql"));
    if (!checked.ok()) {
      // Defence in depth: the gateway's classification refuses it first.
      throw ConnectorException.denied(checked.refusal());
    }
    int limit = Math.min(Math.clamp(call.integer("limit", 100), 1, 1000), maxRows);
    ClickHouseClient.Answer a = ch.query(checked.checked().sql(), Map.of(), limit);
    if (!a.ok()) {
      return refused(a.failure(), ch.config());
    }
    ClickHouseClient.Table t = a.table();
    ArrayNode rows = cut(t.rows(), limit);
    boolean truncated = t.rows().size() > limit;
    ObjectNode structured = NODES.objectNode();
    ArrayNode columns = structured.putArray("columns");
    t.names().forEach(columns::add);
    ArrayNode types = structured.putArray("types");
    t.types().forEach(types::add);
    structured.set("rows", rows);
    structured.put("rowCount", rows.size());
    structured.put("truncated", truncated);
    if (truncated) {
      structured.put("truncatedReason", "row_limit");
    } else {
      structured.putNull("truncatedReason");
    }
    structured.put("elapsedMs", t.elapsedMs());
    if (t.rowsRead() == null) {
      structured.putNull("rowsRead");
    } else {
      structured.put("rowsRead", t.rowsRead());
    }
    ToolResult result = ToolResult.table(new ToolResult.Table(columns(t), rows, null), structured);
    return truncated ? result.truncated("row_limit") : result;
  }

  /** The list tools, explain and the running queries: a statement of the plugin's own, its answer as a table. */
  private ToolResult table(ClickHouseClient ch, ToolCall call, int maxRows) throws ConnectorException {
    if ("clickhouse_explain".equals(call.tool())) {
      ReadOnlySql.Result checked = ReadOnlySql.checkQuery(call.string("sql"));
      if (!checked.ok()) {
        throw ConnectorException.denied(checked.refusal());
      }
    }
    Statement s = statement(call, ch.config(), maxRows).orElseThrow(() -> ConnectorException.badInput(
        "The arguments of " + call.tool() + " aren't valid."));
    ClickHouseClient.Answer a = ch.query(s.sql(), s.params(), s.limit());
    if (!a.ok()) {
      return refused(a.failure(), ch.config());
    }
    ClickHouseClient.Table t = a.table();
    ArrayNode rows = cut(t.rows(), s.limit());
    boolean truncated = t.rows().size() > s.limit();
    ObjectNode structured = NODES.objectNode();
    structured.put("rowCount", rows.size());
    structured.put("truncated", truncated);
    if (s.database() != null) {
      structured.put("database", s.database());
    }
    ToolResult result = ToolResult.table(new ToolResult.Table(columns(t), rows, null), structured);
    return truncated ? result.truncated("row_limit") : result;
  }

  private ToolResult describe(ClickHouseClient ch, ToolCall call) throws ConnectorException {
    String database = database(call, ch.config());
    String table = call.string("table");
    if (table == null || table.isBlank()) {
      throw ConnectorException.badInput("table is required.");
    }
    Map<String, String> params = new LinkedHashMap<>();
    params.put("db", database);
    params.put("tbl", table);
    ClickHouseClient.Answer about = ch.query("SELECT engine, sorting_key, partition_key, primary_key, total_rows, "
        + "total_bytes, comment FROM system.tables WHERE database = {db:String} AND name = {tbl:String}", params, 1);
    if (!about.ok()) {
      return refused(about.failure(), ch.config());
    }
    if (about.table().rows().isEmpty()) {
      return ToolResult.error(database + "." + table + " not found or not visible to this user.");
    }
    ClickHouseClient.Answer cols = ch.query("SELECT name, type, default_kind, default_expression, comment, "
        + "is_in_sorting_key, is_in_primary_key FROM system.columns WHERE database = {db:String} AND table = "
        + "{tbl:String} ORDER BY position", params, 2000);
    if (!cols.ok()) {
      return refused(cols.failure(), ch.config());
    }
    JsonNode t = about.table().rows().get(0);
    ObjectNode out = NODES.objectNode();
    out.put("database", database);
    out.put("table", table);
    out.put("engine", text(t.path(0)));
    out.put("sortingKey", text(t.path(1)));
    out.put("partitionKey", text(t.path(2)));
    out.put("primaryKey", text(t.path(3)));
    number(out, "totalRows", t.path(4));
    number(out, "totalBytes", t.path(5));
    out.put("comment", text(t.path(6)));
    ArrayNode columns = out.putArray("columns");
    List<String> lines = new ArrayList<>();
    for (JsonNode c : cols.table().rows()) {
      ObjectNode col = columns.addObject();
      col.put("name", c.path(0).asString(""));
      col.put("type", c.path(1).asString(""));
      col.put("defaultKind", text(c.path(2)));
      col.put("defaultExpression", text(c.path(3)));
      col.put("comment", text(c.path(4)));
      col.put("inSortingKey", flag(c.path(5)));
      col.put("inPrimaryKey", flag(c.path(6)));
      lines.add(col.path("name").asString() + " " + col.path("type").asString()
          + (col.path("inSortingKey").asBoolean() ? " (sorting key)" : ""));
    }
    String head = database + "." + table + " · " + out.path("engine").asString("") + (out.path("sortingKey")
        .asString("").isEmpty() ? "" : " · ORDER BY " + out.path("sortingKey").asString());
    return ToolResult.json(out, head + "\n" + String.join("\n", lines));
  }

  // ── Statements ──────────────────────────────────────────────────────────────────────────────────────────────

  /** A statement of the plugin's own: its SQL, the values of its placeholders and the rows it may return. */
  private record Statement(String sql, Map<String, String> params, int limit, String database, String summary) {}

  private Optional<Statement> statement(ToolCall call, ClickHouseConfig config, int maxRows) {
    String database = database(call, config);
    return switch (call.tool()) {
      case "clickhouse_query" -> {
        ReadOnlySql.Result r = ReadOnlySql.check(call.string("sql"));
        yield r.ok() ? Optional.of(new Statement(r.checked().sql(), Map.of(), Math.min(call.integer("limit", 100),
            maxRows), null, "Query on " + config.database())) : Optional.empty();
      }
      case "clickhouse_list_databases" -> Optional.of(new Statement("SELECT d.name AS name, any(d.engine) AS engine, "
          + "countIf(t.name != '') AS tables FROM system.databases AS d LEFT JOIN system.tables AS t ON t.database = "
          + "d.name" + selectedFilter(config) + " GROUP BY d.name ORDER BY d.name", selectedParams(config),
          Math.min(1000, maxRows), null, "List databases"));
      case "clickhouse_list_tables" -> {
        int limit = Math.min(Math.clamp(call.integer("limit", 100), 1, 500), maxRows);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("db", database);
        String like = call.string("like");
        StringBuilder sql = new StringBuilder("SELECT name, engine, total_rows, total_bytes, comment FROM "
            + "system.tables WHERE database = {db:String}");
        if (like != null && !like.isBlank()) {
          sql.append(" AND name LIKE {like:String}");
          params.put("like", like);
        }
        sql.append(" ORDER BY total_bytes DESC NULLS LAST, name LIMIT ").append(limit + 1);
        yield Optional.of(new Statement(sql.toString(), params, limit, database, "Tables of " + database));
      }
      case "clickhouse_describe_table" -> Optional.of(new Statement("SELECT name, type, default_kind, "
          + "default_expression, comment, is_in_sorting_key, is_in_primary_key FROM system.columns WHERE database = "
          + "{db:String} AND table = {tbl:String} ORDER BY position", Map.of("db", database, "tbl",
          String.valueOf(call.string("table"))), 2000, database, "Describe " + database + "." + call.string("table")));
      case "clickhouse_explain" -> {
        ReadOnlySql.Result r = ReadOnlySql.checkQuery(call.string("sql"));
        String kind = call.arguments().path("kind").asString("PLAN").toUpperCase(Locale.ROOT);
        if (!r.ok() || !List.of("PLAN", "PIPELINE", "ESTIMATE", "SYNTAX").contains(kind)) {
          yield Optional.empty();
        }
        yield Optional.of(new Statement("EXPLAIN " + kind + " " + r.checked().sql(), Map.of(), Math.min(1000,
            maxRows), null, "Explain (" + kind + ")"));
      }
      case "clickhouse_running_queries" -> {
        int limit = Math.min(Math.clamp(call.integer("limit", 50), 1, 200), maxRows);
        yield Optional.of(new Statement("SELECT query_id, user, round(elapsed, 1) AS elapsed_s, read_rows, "
            + "memory_usage, substring(query, 1, " + QUERY_TEXT + ") AS query FROM system.processes WHERE query_id "
            + "!= queryID() ORDER BY elapsed DESC LIMIT " + (limit + 1), Map.of(), limit, null, "Running queries"));
      }
      default -> Optional.empty();
    };
  }

  private static String database(ToolCall call, ClickHouseConfig config) {
    String db = call.string("database");
    return db == null || db.isBlank() ? config.database() : db.strip();
  }

  private static String selectedFilter(ClickHouseConfig config) {
    if (config.databases().isEmpty()) return "";
    List<String> parameters = new ArrayList<>();
    for (int i = 0; i < config.databases().size(); i++) parameters.add("{selected" + i + ":String}");
    return " WHERE d.name IN (" + String.join(", ", parameters) + ")";
  }

  private static Map<String, String> selectedParams(ClickHouseConfig config) {
    Map<String, String> params = new LinkedHashMap<>();
    for (int i = 0; i < config.databases().size(); i++) params.put("selected" + i, config.databases().get(i));
    return params;
  }

  // ── Answers ─────────────────────────────────────────────────────────────────────────────────────────────────

  /** What ClickHouse's refusal means for the agent. */
  private static ToolResult refused(ClickHouseClient.Failure f, ClickHouseConfig config) {
    if (f.account()) {
      return ToolResult.error("ClickHouse rejected the user or the password. Test the connection.");
    }
    return switch (f.code()) {
      case ClickHouseClient.READONLY -> ToolResult.error("ClickHouse refused it in read-only mode: " + f.message()
          + " (A user whose profile sets readonly=1 can't take the limits MCP Gateway sends: give it readonly=2.)");
      case ClickHouseClient.TIMEOUT_EXCEEDED -> ToolResult.error("The query ran longer than "
          + config.maxExecutionSeconds() + " s and ClickHouse stopped it. Narrow it down (a WHERE on the sorting "
          + "key, fewer columns) and try again.");
      case ClickHouseClient.ACCESS_DENIED -> ToolResult.error("This user may not do that: " + f.message());
      default -> ToolResult.error("ClickHouse refused the query (code " + f.code() + "): " + f.message());
    };
  }

  private static TestResult testFailure(ClickHouseClient.Failure f, List<TestResult.Check> checks) {
    if (f.account()) {
      return TestResult.failure("auth", "ClickHouse rejected the user or the password.", "code " + f.code() + ": "
          + f.message()).withChecks(checks);
    }
    if (f.code() == ClickHouseClient.READONLY) {
      return TestResult.failure("readonly-profile", "This user's profile sets readonly=1, which also forbids the "
          + "limits MCP Gateway sends with every query. Give the user readonly=2: it may read and set limits, never "
          + "write.", "code " + f.code() + ": " + f.message()).withChecks(checks);
    }
    return TestResult.failure("clickhouse-" + f.code(), "ClickHouse refused the test query: " + f.message(),
        "code " + f.code()).withChecks(checks);
  }

  private static List<ToolResult.Column> columns(ClickHouseClient.Table t) {
    List<ToolResult.Column> out = new ArrayList<>();
    for (int i = 0; i < t.names().size(); i++) {
      out.add(new ToolResult.Column(t.names().get(i), i < t.types().size() ? t.types().get(i) : "String", null, null,
          null));
    }
    return out;
  }

  private static ArrayNode cut(ArrayNode rows, int limit) {
    if (rows.size() <= limit) {
      return rows;
    }
    ArrayNode out = NODES.arrayNode();
    for (int i = 0; i < limit; i++) {
      out.add(rows.get(i));
    }
    return out;
  }

  private static String text(JsonNode v) {
    return v == null || v.isNull() || v.isMissingNode() || v.asString("").isEmpty() ? null : v.asString();
  }

  /** UInt64 comes quoted in ClickHouse's JSON: a number either way, or null. */
  private static void number(ObjectNode out, String name, JsonNode v) {
    if (v.isNumber()) {
      out.put(name, v.asLong());
    } else if (v.isString() && v.asString().matches("\\d{1,19}")) {
      out.put(name, Long.parseLong(v.asString()));
    } else {
      out.putNull(name);
    }
  }

  private static boolean flag(JsonNode v) {
    return v.isBoolean() ? v.asBoolean() : v.asInt(0) != 0;
  }

  private Optional<ToolDefinition> tool(String name) {
    return manifest.tools().stream().filter(t -> t.name().equals(name)).findFirst();
  }

  private static long ms(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
