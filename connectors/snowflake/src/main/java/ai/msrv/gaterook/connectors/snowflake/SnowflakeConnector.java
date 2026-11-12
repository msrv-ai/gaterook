package ai.msrv.gaterook.connectors.snowflake;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ActionPreview.Field;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.TestResult.Hint;
import ai.msrv.gaterook.connector.api.TestResult.Severity;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.snowflake.SnowflakeApi.Answer;
import ai.msrv.gaterook.connectors.snowflake.SnowflakeClassifier.Refusal;
import ai.msrv.gaterook.connectors.snowflake.SnowflakeClassifier.Statement;
import ai.msrv.gaterook.connectors.support.BuiltIn;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Snowflake (docs/snowflake.md): {@code snowflake_list_schemas}, {@code snowflake_describe_table} and
 * {@code snowflake_query} read; {@code snowflake_execute} runs one INSERT, UPDATE, DELETE, MERGE, TRUNCATE or DDL
 * statement that a person approved. Everything goes through Snowflake's SQL API ({@link SnowflakeApi}).
 *
 * <p>The route of a call comes from {@link #classify} without I/O ({@link SnowflakeClassifier}). Snowflake has no
 * read-only transaction, so a read is checked again where only Snowflake can tell: before a SELECT runs, Snowflake
 * plans it ({@code EXPLAIN USING JSON}), and a plan that changes data, can't be read or, with a table list, reads an
 * unlisted table, is refused. Every statement is sent with {@code MULTI_STATEMENT_COUNT=1}. These checks are defence in
 * depth: the role and the user's other roles are the boundary, and Test connection says what they may do.
 */
public final class SnowflakeConnector implements Connector {

  static final String TYPE = "snowflake";
  static final int DEFAULT_QUERY_LIMIT = 50;
  static final int DEFAULT_LIST_LIMIT = 500;
  static final int MAX_COLUMNS = 2_000;
  static final int MAX_GRANTS = 5_000;
  /** The system roles that can change anything in an account. */
  static final Set<String> ADMIN_ROLES = Set.of("ACCOUNTADMIN", "ORGADMIN", "GLOBALORGADMIN", "SECURITYADMIN",
      "SYSADMIN", "USERADMIN");
  static final String IDENTITY_SQL = "SELECT CURRENT_VERSION(), CURRENT_USER(), CURRENT_ROLE(), "
      + "CURRENT_SECONDARY_ROLES(), CURRENT_WAREHOUSE(), CURRENT_DATABASE(), CURRENT_SCHEMA()";
  static final String TABLES_SQL = "SELECT TABLE_CATALOG, TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE, ROW_COUNT, BYTES, "
      + "COMMENT FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA <> 'INFORMATION_SCHEMA'";
  static final String SCHEMATA_SQL = "SELECT CATALOG_NAME, SCHEMA_NAME, COMMENT FROM INFORMATION_SCHEMA.SCHEMATA "
      + "WHERE SCHEMA_NAME <> 'INFORMATION_SCHEMA'";
  static final String TABLE_SQL = "SELECT TABLE_TYPE, ROW_COUNT, BYTES, COMMENT, CLUSTERING_KEY FROM "
      + "INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
  static final String COLUMNS_SQL = "SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, "
      + "NUMERIC_SCALE, IS_NULLABLE, COLUMN_DEFAULT, COMMENT FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ? "
      + "AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION LIMIT " + (MAX_COLUMNS + 1);
  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final SnowflakeApi api;
  private final ConnectorManifest manifest;
  private volatile SnowflakeSuggestions.Machine machine = SnowflakeSuggestions.Machine.system();

  public SnowflakeConnector() {
    this(null);
  }

  /** Tests: {@code testBase} replaces the account's address. Never a setting: a person can't send a key elsewhere. */
  SnowflakeConnector(URI testBase) {
    this.api = new SnowflakeApi(testBase);
    SchemaResources schemas = SchemaResources.of(SnowflakeConnector.class, TYPE);
    this.manifest = ConnectorManifest.builder(TYPE, BuiltIn.VERSION, ConnectorManifest.Kind.BUILT_IN)
        .name("Snowflake")
        .icon("database")
        .group("Databases")
        .description("Read queries, schemas and tables in Snowflake through its SQL API. Changes wait for approval.")
        .configSchema(schemas.load("config"))
        .tools(SnowflakeTools.definitions(schemas))
        .access(new ConnectorManifest.RequiredAccess(List.of("*.snowflakecomputing.com"), List.of(), List.of()))
        .build();
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  // ── Classification ──────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    SnowflakeSettings s;
    try {
      s = SnowflakeSettings.of(cfg);
    } catch (ConnectorException e) {
      return Classification.deny(policy.rule("bad-config"), e.getMessage(), "Complete the connection's settings.");
    }
    String env = policy.env() == null ? "this environment" : policy.env().id();
    return switch (call.tool()) {
      case SnowflakeTools.LIST_SCHEMAS -> refusal(policy, listing(call.arguments(), s, env));
      case SnowflakeTools.DESCRIBE_TABLE -> refusal(policy, described(call.string("table"), s, env));
      case SnowflakeTools.QUERY -> refusal(policy, SnowflakeClassifier.query(SnowflakeClassifier.analyze(
          call.string("sql")), s, env));
      case SnowflakeTools.EXECUTE -> {
        Statement st = SnowflakeClassifier.analyze(call.string("sql"));
        Refusal r = SnowflakeClassifier.execute(st, s, env);
        if (r != null) {
          yield refusal(policy, r);
        }
        String target = SnowflakeClassifier.targetDisplay(st, s);
        ObjectNode facts = F.objectNode().put("statement", st.type());
        if (target != null) {
          facts.put("target", target);
        }
        yield Classification.mutation(st.type() + (target == null ? "" : " on " + target) + " changes "
            + (st.ddl() ? "objects" : "data"), facts);
      }
      default -> Classification.deny(policy.rule("unknown-tool"), manifest.name() + " has no tool " + call.tool()
          + ".", null);
    };
  }

  private static Classification refusal(PolicyView policy, Refusal r) {
    return r == null ? Classification.read() : Classification.deny(policy.rule(r.rule()), r.reason(), r.hint());
  }

  /** {@code snowflake_list_schemas}: a database and schema that are names, and that the table list may hold. */
  static Refusal listing(JsonNode args, SnowflakeSettings s, String env) {
    String database = s.database();
    if (args.path("database").isString()) {
      database = ObjectName.identifier(args.get("database").asString());
      if (database == null) {
        return new Refusal("bad-argument", args.get("database").asString() + " isn't a database name.",
            "Write it as it was created, such as ANALYTICS, or in double quotes.");
      }
    }
    String schema = null;
    if (args.path("schema").isString()) {
      schema = ObjectName.identifier(args.get("schema").asString());
      if (schema == null) {
        return new Refusal("bad-argument", args.get("schema").asString() + " isn't a schema name.",
            "Write it as it was created, such as PUBLIC, or in double quotes.");
      }
    }
    if (!s.tables().mayAllowDatabase(database)) {
      return new Refusal("table-not-allowed", "No table of " + ObjectName.quoteIfNeeded(database) + " is open to "
          + "agents on " + env + ".", "Leave database out to list the connection's.");
    }
    if (schema != null && !s.tables().mayAllowSchema(database, schema)) {
      return new Refusal("table-not-allowed", "No table of schema " + ObjectName.quoteIfNeeded(schema) + " is open "
          + "to agents on " + env + ".", "Leave schema out to see the schemas you can use.");
    }
    return null;
  }

  /** {@code snowflake_describe_table}: a table name that resolves, on the table list. */
  static Refusal described(String table, SnowflakeSettings s, String env) {
    ObjectName name = ObjectName.parse(table);
    if (name == null || name.parts().size() > 3) {
      return new Refusal("bad-argument", (table == null ? "The call names no table" : table + " isn't a table "
          + "name") + ".", "Write TABLE, SCHEMA.TABLE or DATABASE.SCHEMA.TABLE; quote names with lower case.");
    }
    ObjectName full = name.resolve(s.database(), s.schema());
    if (full == null) {
      return new Refusal("bad-argument", name.display() + " needs its " + (name.parts().size() == 2 || s.database()
          == null ? "database" : "schema") + ": the connection has no default.", "Write DATABASE.SCHEMA.TABLE.");
    }
    if (!s.tables().allows(full)) {
      return new Refusal("table-not-allowed", full.display() + " isn't one of the tables agents may use on " + env
          + ".", "Call snowflake_list_schemas to see the tables you can use.");
    }
    return null;
  }

  // ── The exact action ────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    SnowflakeSettings s;
    try {
      s = SnowflakeSettings.of(cfg);
    } catch (ConnectorException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
    String target = cfg.name() + " · " + s.account() + (s.namespace() == null ? "" : " · " + s.namespace());
    if (SnowflakeTools.EXECUTE.equals(call.tool()) || SnowflakeTools.QUERY.equals(call.tool())) {
      return describeSql(call, s, target, cfg.name());
    }
    // A read tool an endpoint put in Approval: its arguments are the whole action.
    ObjectNode args = call.arguments().deepCopy();
    return ActionPreview.of(ActionPreview.Kind.JSON, "json", args.toString(), target,
            shorten(call.tool() + " on " + cfg.name()))
        .withFields(context(s))
        .withImpact(List.of(new Field("Reads", "INFORMATION_SCHEMA of the database, no change")))
        .withFacets(Map.of("tool", call.tool()))
        .withPayload(F.objectNode().set("arguments", args).set("context", contextNode(s)));
  }

  private ActionPreview describeSql(ToolCall call, SnowflakeSettings s, String target, String connection) {
    String sql = Objects.requireNonNullElse(call.string("sql"), "");
    Statement st = SnowflakeClassifier.analyze(sql);
    boolean change = SnowflakeTools.EXECUTE.equals(call.tool());
    String changed = SnowflakeClassifier.targetDisplay(st, s);
    List<Field> impact = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    if (change) {
      impact.add(new Field("Changes", changed != null ? changed : st.readable() ? "Not named by GateRook: read the "
          + "statement" : "Unknown: not read"));
      impact.add(new Field("Transaction", st.ddl() ? "None: Snowflake commits DDL at once"
          : "Autocommit: Snowflake commits the statement when it ends, or rolls it back"));
      impact.add(new Field("Retried", "No: a request whose answer is lost is reported as unknown"));
      if ((st.type().equals("UPDATE") || st.type().equals("DELETE")) && !st.scan().hasKeyword("WHERE")) {
        warnings.add("No WHERE: it changes every row of " + (changed == null ? "the table" : changed) + ".");
      }
      if (st.type().equals("TRUNCATE") || st.type().startsWith("DROP ")) {
        warnings.add("It removes data; getting it back depends on Time Travel's retention (UNDROP).");
      }
    } else {
      impact.add(new Field("Transaction", "None: Snowflake has no read-only transaction. GateRook checks the "
          + "statement, and Snowflake's plan of a SELECT, before it runs"));
    }
    if (!st.readable()) {
      warnings.add("Not read by GateRook: " + st.problem() + " Check the exact text above.");
    }
    ObjectNode payload = F.objectNode().put("sql", sql);
    if (!change && call.arguments().path("limit").isIntegralNumber()) {
      payload.put("limit", call.arguments().get("limit").asInt());
    }
    payload.set("context", contextNode(s));
    Map<String, String> facets = new LinkedHashMap<>();
    facets.put("statement", st.type());
    if (changed != null) {
      facets.put("target", changed);
    }
    return ActionPreview.of(ActionPreview.Kind.SQL, "sql", sql, target, shorten(call.tool() + " on " + connection
            + ": " + st.type() + (changed == null ? "" : " " + changed)))
        .withFields(context(s))
        .withImpact(impact)
        .withWarnings(warnings)
        .withFacets(facets)
        .withPayload(payload);
  }

  /** Where a statement runs, as the approver sees it: role, warehouse and namespace. */
  private static List<Field> context(SnowflakeSettings s) {
    return List.of(new Field("Role", s.role() == null ? "The user's default role" : s.role()),
        new Field("Warehouse", s.warehouse() == null ? "The user's default warehouse" : s.warehouse()),
        new Field("Database and schema", s.namespace() == null ? "The user's default namespace" : s.namespace()));
  }

  /** The same, as the approved payload holds it: what runs must run where the person saw it. */
  static ObjectNode contextNode(SnowflakeSettings s) {
    ObjectNode n = F.objectNode();
    n.put("account", s.account());
    putOrNull(n, "role", s.role());
    putOrNull(n, "warehouse", s.warehouse());
    putOrNull(n, "database", s.database());
    putOrNull(n, "schema", s.schema());
    return n;
  }

  private static void putOrNull(ObjectNode n, String key, String value) {
    if (value == null) {
      n.putNull(key);
    } else {
      n.put(key, value);
    }
  }

  static String shorten(String s) {
    return s.length() <= 120 ? s : s.substring(0, 119) + "…";
  }

  // ── Execution ───────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    SnowflakeSettings s = SnowflakeSettings.of(cfg);
    return switch (call.tool()) {
      case SnowflakeTools.LIST_SCHEMAS -> listSchemas(cfg, s, secrets, ctx, arguments(call, ctx, s));
      case SnowflakeTools.DESCRIBE_TABLE -> describeTable(cfg, s, secrets, ctx, arguments(call, ctx, s));
      case SnowflakeTools.QUERY -> query(cfg, s, secrets, ctx, call);
      case SnowflakeTools.EXECUTE -> change(cfg, s, secrets, ctx);
      default -> throw ConnectorException.badInput(manifest.name() + " has no tool " + call.tool() + ".");
    };
  }

  private SnowflakeApi.Call call(ConnectionConfig cfg, SnowflakeSettings s, SecretResolver secrets, ExecContext ctx,
      String tool) throws ConnectorException {
    return new SnowflakeApi.Call(cfg, s, ctx, SnowflakeAuth.of(s, secrets, ctx), tool);
  }

  private static String env(ExecContext ctx) {
    return ctx.env() == null ? "this environment" : ctx.env().id();
  }

  /** The arguments that run: the approved request's, or the call's. */
  private static JsonNode arguments(ToolCall call, ExecContext ctx, SnowflakeSettings s) throws ConnectorException {
    if (ctx.approved() && ctx.approvedPayload().isPresent() && ctx.approvedPayload().get().has("arguments")) {
      checkContext(ctx.approvedPayload().get(), s);
      return ctx.approvedPayload().get().get("arguments");
    }
    return call.arguments();
  }

  /** An approved request runs where the person saw it would: the same account, role, warehouse and namespace. */
  private static void checkContext(JsonNode payload, SnowflakeSettings s) throws ConnectorException {
    if (!contextNode(s).equals(payload.path("context"))) {
      throw ConnectorException.denied("The connection's account, role, warehouse, database or schema changed after "
          + "the approval. Ask for a new one.");
    }
  }

  private static int limit(JsonNode args, ExecContext ctx, int dflt) {
    return Math.max(1, Math.min(args.path("limit").asInt(dflt), ctx.limits().maxRows()));
  }

  /** Bytes of a table's cells before the answer is cut: the core renders the rows as text too. */
  private static long budget(ExecContext ctx) {
    return Math.max(4_096, ctx.limits().maxResponseBytes() / 2);
  }

  private ToolResult query(ConnectionConfig cfg, SnowflakeSettings s, SecretResolver secrets, ExecContext ctx,
      ToolCall call) throws ConnectorException {
    JsonNode args = call.arguments();
    String sql = Objects.requireNonNullElse(call.string("sql"), "");
    if (ctx.approved() && ctx.approvedPayload().isPresent() && ctx.approvedPayload().get().path("sql").isString()) {
      JsonNode payload = ctx.approvedPayload().get();
      checkContext(payload, s);
      sql = payload.get("sql").asString();
      args = payload;
    }
    Statement st = SnowflakeClassifier.analyze(sql);
    Refusal refusal = SnowflakeClassifier.query(st, s, env(ctx));
    if (refusal != null) {
      throw ConnectorException.denied(refusal.reason());
    }
    int limit = limit(args, ctx, DEFAULT_QUERY_LIMIT);
    SnowflakeApi.Call c = call(cfg, s, secrets, ctx, call.tool());
    if (st.type().equals("SELECT")) {
      checkPlan(c, sql, s, env(ctx));
    }
    Answer a = api.run(c, SnowflakeApi.Statement.read(sql, limit + 1));
    return table(c, a, limit, st.type());
  }

  /**
   * Snowflake's plan of a SELECT, before it runs: it must be readable, change no data, and with a table list read
   * only listed tables.
   */
  private void checkPlan(SnowflakeApi.Call c, String sql, SnowflakeSettings s, String env) throws ConnectorException {
    Answer explained;
    try {
      explained = api.run(c, SnowflakeApi.Statement.read("EXPLAIN USING JSON " + sql, 2));
    } catch (SnowflakeApi.Failure f) {
      if (f.kind() != ConnectorException.Kind.BAD_INPUT && f.kind() != ConnectorException.Kind.RESOURCE_ERROR) {
        throw f;
      }
      // The agent sent a SELECT; say that it was the plan that failed, not the query itself.
      throw new ConnectorException(f.kind(), "GateRook asks Snowflake to plan a SELECT before it runs, and the plan "
          + "failed, so the query didn't run. " + f.getMessage(), null);
    }
    JsonNode cell = explained.data().path(0).path(0);
    Plans.Plan plan = Plans.read(cell.isString() ? cell.asString() : null);
    if (!plan.readable()) {
      throw ConnectorException.denied(plan.problem() + " GateRook runs a SELECT only after it read Snowflake's plan.");
    }
    List<String> changing = plan.changing();
    if (!changing.isEmpty()) {
      throw ConnectorException.denied("Snowflake's plan of this query changes data (" + String.join(", ", changing)
          + "), and snowflake_query only reads. Use snowflake_execute: a person approves the exact statement.");
    }
    if (s.tables().restricted()) {
      for (String object : plan.objects()) {
        ObjectName name = ObjectName.parse(object);
        ObjectName full = name == null ? null : name.resolve(s.database(), s.schema());
        if (full == null || !s.tables().allows(full)) {
          throw ConnectorException.denied(OutputSanitizer.sanitize(object) + " isn't one of the tables agents may "
              + "use on " + env + ", and Snowflake's plan of this query reads it.");
        }
      }
    }
  }

  /** The rows of a result as a table, partition after partition, until the limit, the budget or the deadline. */
  private ToolResult table(SnowflakeApi.Call c, Answer a, int limit, String type) throws ConnectorException {
    ExecContext ctx = c.ctx();
    List<Values.Column> columns = Values.columns(a.rowType());
    int cell = ctx.limits().maxCellChars();
    long budget = budget(ctx);
    ArrayNode rows = F.arrayNode();
    long bytes = 0;
    String truncated = null;
    JsonNode data = a.data();
    int partition = 0;
    read:
    while (true) {
      for (JsonNode raw : data) {
        if (rows.size() >= limit) {
          truncated = "row_limit";
          break read;
        }
        ArrayNode row = rows.addArray();
        for (int i = 0; i < columns.size(); i++) {
          JsonNode v = Values.cell(raw.get(i), columns.get(i), cell);
          row.add(v);
          bytes += v.isString() ? v.asString().length() + 2L : 8L;
        }
        if (bytes > budget) {
          truncated = "size_limit";
          break read;
        }
      }
      partition++;
      if (partition >= a.partitions()) {
        break;
      }
      if (rows.size() >= limit) {
        truncated = "row_limit";
        break;
      }
      if (ctx.remaining().compareTo(Duration.ofSeconds(1)) < 0) {
        truncated = "timeout";
        break;
      }
      data = api.partition(c, a.handle(), partition);
    }
    if (truncated == null && a.numRows() > rows.size()) {
      truncated = "row_limit";
    }
    List<ToolResult.Column> tableColumns = columns.stream().map(Values.Column::tool).toList();
    ObjectNode facts = F.objectNode().put("statement", type);
    List<ToolResult.Content> notes = new ArrayList<>();
    if (truncated != null) {
      notes.add(ToolResult.Content.text(switch (truncated) {
        case "row_limit" -> "More rows match: narrow the query with WHERE or LIMIT, or pass a higher limit (at most "
            + ctx.limits().maxRows() + " here).";
        case "size_limit" -> "Cut at the response size: select fewer or narrower columns, or fewer rows.";
        default -> "Stopped reading at the call's time limit: narrow the query.";
      }));
    }
    return new ToolResult(notes, facts, false, new ToolResult.Table(tableColumns, rows, null), truncated != null,
        truncated, List.of(), 0, 0, null);
  }

  /** Raw rows of a statement GateRook wrote itself, all partitions, at most {@code max}. */
  private List<JsonNode> rows(SnowflakeApi.Call c, Answer a, int max) throws ConnectorException {
    List<JsonNode> out = new ArrayList<>();
    JsonNode data = a.data();
    int partition = 0;
    while (true) {
      for (JsonNode row : data) {
        if (out.size() >= max) {
          return out;
        }
        out.add(row);
      }
      partition++;
      if (partition >= a.partitions() || out.size() >= max) {
        return out;
      }
      data = api.partition(c, a.handle(), partition);
    }
  }

  private ToolResult listSchemas(ConnectionConfig cfg, SnowflakeSettings s, SecretResolver secrets, ExecContext ctx,
      JsonNode args) throws ConnectorException {
    Refusal refusal = listing(args, s, env(ctx));
    if (refusal != null) {
      throw ConnectorException.denied(refusal.reason());
    }
    String database = args.path("database").isString() ? ObjectName.identifier(args.get("database").asString())
        : null;
    String schema = args.path("schema").isString() ? ObjectName.identifier(args.get("schema").asString()) : null;
    String pattern = args.path("pattern").asString(null);
    int limit = Math.max(1, Math.min(args.path("limit").asInt(DEFAULT_LIST_LIMIT), 1_000));
    String listed = database != null ? database : s.database();

    StringBuilder sql = new StringBuilder(TABLES_SQL);
    List<String> values = new ArrayList<>();
    if (schema != null) {
      sql.append(" AND TABLE_SCHEMA = ?");
      values.add(schema);
    }
    if (pattern != null && !pattern.isBlank()) {
      sql.append(" AND TABLE_NAME ILIKE ?");
      values.add(pattern);
    }
    if (s.tables().restricted()) {
      List<String> filters = new ArrayList<>();
      for (String[] like : s.tables().likePatterns(listed)) {
        filters.add("(TABLE_SCHEMA ILIKE ? ESCAPE '!' AND TABLE_NAME ILIKE ? ESCAPE '!')");
        values.add(like[0]);
        values.add(like[1]);
      }
      if (filters.isEmpty()) {
        throw ConnectorException.denied("No table of " + (listed == null ? "the database"
            : ObjectName.quoteIfNeeded(listed)) + " is open to agents on " + env(ctx) + ".");
      }
      sql.append(" AND (").append(String.join(" OR ", filters)).append(')');
    }
    sql.append(" ORDER BY TABLE_SCHEMA, TABLE_NAME LIMIT ").append(limit + 1);

    SnowflakeApi.Call c = call(cfg, s, secrets, ctx, SnowflakeTools.LIST_SCHEMAS);
    List<JsonNode> found = rows(c, api.run(c, SnowflakeApi.Statement.catalog(sql.toString(), values, database, 0)),
        limit + 1);
    boolean truncated = found.size() > limit;
    ArrayNode tables = F.arrayNode();
    Map<String, List<String>> bySchema = new LinkedHashMap<>();
    for (JsonNode r : found.subList(0, Math.min(found.size(), limit))) {
      String catalog = r.path(0).asString("");
      String sch = r.path(1).asString("");
      String name = r.path(2).asString("");
      if (!s.tables().allows(catalog, sch, name)) {
        continue;
      }
      String kind = kind(r.path(3).asString(""));
      ObjectNode t = tables.addObject().put("database", catalog).put("schema", sch).put("name", name)
          .put("kind", kind);
      putLong(t, "rows", r.path(4));
      putLong(t, "bytes", r.path(5));
      putText(t, "comment", r.path(6), ctx.limits().maxCellChars());
      bySchema.computeIfAbsent(ObjectName.quoteIfNeeded(catalog) + "." + ObjectName.quoteIfNeeded(sch),
          k -> new ArrayList<>()).add(ObjectName.quoteIfNeeded(name) + (kind.equals("table") ? "" : " (" + kind + ")"));
    }

    StringBuilder schemataSql = new StringBuilder(SCHEMATA_SQL);
    List<String> schemataValues = new ArrayList<>();
    if (schema != null) {
      schemataSql.append(" AND SCHEMA_NAME = ?");
      schemataValues.add(schema);
    }
    schemataSql.append(" ORDER BY SCHEMA_NAME LIMIT 1001");
    ArrayNode schemas = F.arrayNode();
    List<String> empty = new ArrayList<>();
    for (JsonNode r : rows(c, api.run(c, SnowflakeApi.Statement.catalog(schemataSql.toString(), schemataValues,
        database, 0)), 1_000)) {
      String catalog = r.path(0).asString("");
      String sch = r.path(1).asString("");
      if (!s.tables().mayAllowSchema(catalog, sch)) {
        continue;
      }
      ObjectNode o = schemas.addObject().put("database", catalog).put("name", sch);
      putText(o, "comment", r.path(2), ctx.limits().maxCellChars());
      String key = ObjectName.quoteIfNeeded(catalog) + "." + ObjectName.quoteIfNeeded(sch);
      if (!bySchema.containsKey(key)) {
        empty.add(key);
      }
    }

    ObjectNode out = F.objectNode();
    if (listed == null) {
      out.putNull("database");
    } else {
      out.put("database", listed);
    }
    out.set("schemas", schemas);
    out.set("tables", tables);
    out.put("truncated", truncated);
    StringBuilder text = new StringBuilder();
    bySchema.forEach((key, names) -> text.append(key).append(": ").append(String.join(", ", names)).append('\n'));
    if (!empty.isEmpty()) {
      text.append("Schemas without tables listed here: ").append(String.join(", ", empty)).append('\n');
    }
    if (text.isEmpty()) {
      text.append(schemas.isEmpty() ? "No schemas you may use." : "No tables match.");
    }
    if (truncated) {
      text.append("\nMore tables match: narrow it with schema or pattern.");
    }
    return ToolResult.json(out, text.toString().strip());
  }

  /** {@code BASE TABLE} is a table; other kinds as Snowflake names them, in lower case. */
  private static String kind(String tableType) {
    return tableType.equals("BASE TABLE") || tableType.isBlank() ? "table" : tableType.toLowerCase(Locale.ROOT);
  }

  private static void putLong(ObjectNode o, String key, JsonNode raw) {
    try {
      if (raw.isString()) {
        o.put(key, new java.math.BigDecimal(raw.asString().strip()).longValueExact());
        return;
      }
    } catch (NumberFormatException | ArithmeticException e) {
      // Not a count GateRook can show exactly.
    }
    o.putNull(key);
  }

  private static void putText(ObjectNode o, String key, JsonNode raw, int maxCellChars) {
    if (raw.isString()) {
      o.set(key, Values.text(raw.asString(), maxCellChars));
    } else {
      o.putNull(key);
    }
  }

  private ToolResult describeTable(ConnectionConfig cfg, SnowflakeSettings s, SecretResolver secrets, ExecContext ctx,
      JsonNode args) throws ConnectorException {
    String written = args.path("table").asString(null);
    Refusal refusal = described(written, s, env(ctx));
    if (refusal != null) {
      throw refusal.rule().equals("bad-argument") ? ConnectorException.badInput(refusal.reason() + " "
          + refusal.hint()) : ConnectorException.denied(refusal.reason());
    }
    ObjectName full = Objects.requireNonNull(ObjectName.parse(written)).resolve(s.database(), s.schema());
    SnowflakeApi.Call c = call(cfg, s, secrets, ctx, SnowflakeTools.DESCRIBE_TABLE);
    List<String> key = List.of(full.schema(), full.table());
    List<JsonNode> found = rows(c, api.run(c, SnowflakeApi.Statement.catalog(TABLE_SQL, key, full.database(), 0)), 1);
    if (found.isEmpty()) {
      throw ConnectorException.badInput("Table " + full.display() + " doesn't exist, or the role may not see it. Call "
          + "snowflake_list_schemas to see the tables.");
    }
    JsonNode t = found.getFirst();
    List<JsonNode> columnRows = rows(c, api.run(c, SnowflakeApi.Statement.catalog(COLUMNS_SQL, key, full.database(),
        0)), MAX_COLUMNS + 1);
    int cell = ctx.limits().maxCellChars();
    ObjectNode out = F.objectNode().put("database", full.database()).put("schema", full.schema())
        .put("name", full.table()).put("kind", kind(t.path(0).asString("")));
    putLong(out, "rows", t.path(1));
    putLong(out, "bytes", t.path(2));
    putText(out, "comment", t.path(3), cell);
    putText(out, "clusteringKey", t.path(4), cell);
    ArrayNode columns = out.putArray("columns");
    for (JsonNode r : columnRows.subList(0, Math.min(columnRows.size(), MAX_COLUMNS))) {
      ObjectNode col = columns.addObject().put("name", r.path(0).asString(""))
          .put("type", columnType(r.path(1).asString(""), r.path(2), r.path(3), r.path(4)))
          .put("nullable", !"NO".equalsIgnoreCase(r.path(5).asString("YES")));
      putText(col, "default", r.path(6), cell);
      putText(col, "comment", r.path(7), cell);
    }
    out.put("truncated", columnRows.size() > MAX_COLUMNS);
    return ToolResult.json(out, describeText(out));
  }

  /** INFORMATION_SCHEMA's type as SQL writes it: {@code NUMBER(38,0)}, {@code VARCHAR(16777216)}, {@code DATE}. */
  static String columnType(String dataType, JsonNode length, JsonNode precision, JsonNode scale) {
    String type = dataType.toUpperCase(Locale.ROOT);
    if (type.equals("NUMBER") && precision.isString()) {
      return "NUMBER(" + precision.asString() + "," + scale.asString("0") + ")";
    }
    if (type.equals("TEXT") && length.isString()) {
      return "VARCHAR(" + length.asString() + ")";
    }
    if ((type.equals("BINARY")) && length.isString()) {
      return "BINARY(" + length.asString() + ")";
    }
    return type;
  }

  /** {@code ANALYTICS.PUBLIC.ORDERS (table, ~1,204 rows): ID NUMBER(38,0) not null, STATUS VARCHAR(16777216)}. */
  static String describeText(ObjectNode d) {
    StringBuilder out = new StringBuilder(ObjectName.quoteIfNeeded(d.path("database").asString())).append('.')
        .append(ObjectName.quoteIfNeeded(d.path("schema").asString())).append('.')
        .append(ObjectName.quoteIfNeeded(d.path("name").asString())).append(" (").append(d.path("kind").asString());
    if (d.path("rows").isNumber()) {
      out.append(", ~").append(String.format(Locale.US, "%,d", d.get("rows").asLong())).append(" rows");
    }
    out.append("): ");
    List<String> columns = new ArrayList<>();
    for (JsonNode c : d.path("columns")) {
      columns.add(ObjectName.quoteIfNeeded(c.path("name").asString()) + " " + c.path("type").asString()
          + (c.path("nullable").asBoolean(true) ? "" : " not null"));
    }
    out.append(String.join(", ", columns));
    if (d.path("clusteringKey").isString()) {
      out.append(" · clustered by ").append(d.get("clusteringKey").asString());
    }
    return out.toString();
  }

  private ToolResult change(ConnectionConfig cfg, SnowflakeSettings s, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    if (!ctx.approved()) {
      throw ConnectorException.denied("Not approved: snowflake_execute changes data, so a person approves it first.");
    }
    JsonNode payload = ctx.approvedPayload().orElseThrow(() -> ConnectorException.denied("The approved action "
        + "carries no statement."));
    if (!payload.path("sql").isString()) {
      throw ConnectorException.denied("The approved action carries no statement.");
    }
    checkContext(payload, s);
    String sql = payload.get("sql").asString();
    Statement st = SnowflakeClassifier.analyze(sql);
    Refusal refusal = SnowflakeClassifier.execute(st, s, env(ctx));
    if (refusal != null) {
      throw ConnectorException.denied(refusal.reason());
    }
    SnowflakeApi.Call c = call(cfg, s, secrets, ctx, SnowflakeTools.EXECUTE);
    Answer a = api.run(c, SnowflakeApi.Statement.change(sql));
    return changeResult(st, SnowflakeClassifier.targetDisplay(st, s), a);
  }

  /** {@code {committed, statement, rowsAffected, rowsInserted, …}} from the SQL API's stats, or DDL's status. */
  static ToolResult changeResult(Statement st, String target, Answer a) {
    ObjectNode out = F.objectNode().put("committed", true).put("statement", st.type());
    if (target != null) {
      out.put("target", target);
    }
    Map<String, Long> counts = counts(a);
    String status = status(a);
    if (!counts.isEmpty()) {
      long affected = counts.getOrDefault("inserted", 0L) + counts.getOrDefault("updated", 0L)
          + counts.getOrDefault("deleted", 0L);
      out.put("rowsAffected", affected);
      counts.forEach((k, v) -> out.put(switch (k) {
        case "inserted" -> "rowsInserted";
        case "updated" -> "rowsUpdated";
        case "deleted" -> "rowsDeleted";
        default -> "duplicateRowsUpdated";
      }, v));
    }
    if (status != null) {
      out.put("message", status);
    }
    String text;
    if (!counts.isEmpty() && !st.ddl()) {
      List<String> parts = new ArrayList<>();
      counts.forEach((k, v) -> {
        if (!k.equals("duplicatesUpdated") && (v > 0 || counts.size() == 1)) {
          parts.add(rows(v) + " " + k);
        }
      });
      if (parts.isEmpty()) {
        parts.add("No rows changed");
      }
      text = String.join(", ", parts) + (target == null ? "" : " · " + target) + ".";
    } else {
      text = status != null ? status : st.type() + " done.";
    }
    return ToolResult.json(out, text);
  }

  /** Rows inserted, updated and deleted: the SQL API's stats, else the counts DML returns as its result. */
  private static Map<String, Long> counts(Answer a) {
    Map<String, Long> out = new LinkedHashMap<>();
    JsonNode stats = a.stats();
    if (stats.isObject() && !stats.isEmpty()) {
      for (String[] k : new String[][] {{"numRowsInserted", "inserted"}, {"numRowsUpdated", "updated"},
          {"numRowsDeleted", "deleted"}, {"numDuplicateRowsUpdated", "duplicatesUpdated"}}) {
        if (stats.path(k[0]).isNumber() || stats.path(k[0]).isString()) {
          out.put(k[1], stats.get(k[0]).asLong(0));
        }
      }
      return out;
    }
    JsonNode rowType = a.rowType();
    JsonNode first = a.data().path(0);
    for (int i = 0; i < rowType.size(); i++) {
      String name = rowType.get(i).path("name").asString("").toLowerCase(Locale.ROOT);
      String what = name.startsWith("number of rows inserted") ? "inserted"
          : name.startsWith("number of rows updated") ? "updated"
          : name.startsWith("number of rows deleted") ? "deleted" : null;
      if (what != null && first.path(i).isString()) {
        try {
          out.put(what, Long.parseLong(first.get(i).asString().strip()));
        } catch (NumberFormatException e) {
          // Not a count.
        }
      }
    }
    return out;
  }

  /** The status DDL answers with ({@code Table ORDERS successfully created.}), one bounded line. */
  private static String status(Answer a) {
    JsonNode rowType = a.rowType();
    if (rowType.size() == 1 && "status".equalsIgnoreCase(rowType.get(0).path("name").asString(""))
        && a.data().path(0).path(0).isString()) {
      String line = OutputSanitizer.sanitize(a.data().get(0).get(0).asString().replaceAll("\\s+", " ").strip());
      return line.length() > 300 ? line.substring(0, 299) + "…" : line;
    }
    return null;
  }

  private static String rows(long n) {
    return String.format(Locale.US, "%,d", n) + (n == 1 ? " row" : " rows");
  }

  // ── Test connection, health, describe ───────────────────────────────────────────────────────────────────────

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    SnowflakeSettings s;
    try {
      s = SnowflakeSettings.of(cfg);
    } catch (ConnectorException e) {
      return TestResult.failure("snowflake.settings", e.getMessage(), null);
    }
    SnowflakeApi.Call c;
    try {
      c = call(cfg, s, secrets, ctx, "test");
    } catch (ConnectorException e) {
      return TestResult.failure("snowflake.credentials", e.getMessage(), null);
    }
    List<TestResult.Check> checks = new ArrayList<>();
    long started = System.nanoTime();
    Answer identity;
    try {
      identity = api.run(c, SnowflakeApi.Statement.read(IDENTITY_SQL, 2));
    } catch (SnowflakeApi.Failure f) {
      return TestResult.failure(f.signIn ? "snowflake.sign-in" : f.kind() == ConnectorException.Kind
          .RESOURCE_UNAVAILABLE ? "snowflake.unreachable" : "snowflake.query", f.getMessage(), f.code.isEmpty() ? null
          : "Snowflake code " + f.code + ", HTTP " + f.status);
    } catch (ConnectorException e) {
      return TestResult.failure(e.kind() == ConnectorException.Kind.RESOURCE_UNAVAILABLE
          || e.kind() == ConnectorException.Kind.TIMEOUT ? "snowflake.unreachable" : "snowflake.query",
          e.getMessage(), null);
    }
    long latency = ms(started);
    JsonNode row = identity.data().path(0);
    String version = text(row.path(0));
    String user = text(row.path(1));
    String role = text(row.path(2));
    List<String> secondary = secondaryRoles(text(row.path(3)));
    String warehouse = text(row.path(4));
    checks.add(new TestResult.Check("sign-in", true, latency, c.credential().fingerprint() == null
        ? "Programmatic access token" : "Key pair, public key " + c.credential().fingerprint()));
    checks.add(new TestResult.Check("session", true, latency, "User " + display(user) + " · role " + display(role)
        + " · warehouse " + (warehouse == null ? "none" : display(warehouse))
        + (s.namespace() == null ? "" : " · " + s.namespace())));

    long granted = System.nanoTime();
    Grants grants = grants(c, role);
    checks.add(new TestResult.Check("grants", grants.read(), ms(granted), grants.detail()));
    TestResult.Level level = grants.level();
    if (level == TestResult.Level.READ && !secondary.isEmpty()) {
      level = TestResult.Level.UNKNOWN;
    }
    ObjectNode details = F.objectNode();
    details.put("role", role);
    ArrayNode secondaryNode = details.putArray("secondaryRoles");
    secondary.forEach(secondaryNode::add);
    TestResult.Permissions permissions = new TestResult.Permissions(level,
        "SELECT, SHOW and DESCRIBE, checked by GateRook", grants.writes().isEmpty() ? null
        : String.join(", ", grants.writes()), level == TestResult.Level.ADMIN ? List.of(role) : List.of(), details);
    ObjectNode metadata = F.objectNode().put("serverVersion", "Snowflake " + Objects.requireNonNullElse(version, ""))
        .put("account", s.account());
    if (user != null) {
      metadata.put("user", user);
    }
    if (role != null) {
      metadata.put("role", role);
    }
    if (warehouse != null) {
      metadata.put("warehouse", warehouse);
    }
    TestResult result = TestResult.success(latency, "Snowflake " + Objects.requireNonNullElse(version, "?"),
            display(user) + " · " + display(role), permissions)
        .withChecks(checks)
        .withMetadata(metadata);
    for (Hint h : hints(cfg.env(), s, user, role, level, grants, secondary, warehouse)) {
      result = result.withHint(h);
    }
    return result;
  }

  /** What the role may do, from its own grants. */
  record Grants(TestResult.Level level, List<String> writes, List<String> inherited, String detail, boolean read) {}

  /** {@code SHOW GRANTS TO ROLE}: its write privileges and the roles it inherits (not followed further). */
  private Grants grants(SnowflakeApi.Call c, String role) {
    if (role == null) {
      return new Grants(TestResult.Level.UNKNOWN, List.of(), List.of(), "The session has no role.", false);
    }
    if (ADMIN_ROLES.contains(role)) {
      return new Grants(TestResult.Level.ADMIN, List.of("everything"), List.of(), role + " is a system role that can "
          + "change anything in the account.", true);
    }
    List<JsonNode> found;
    Answer a;
    try {
      a = api.run(c, SnowflakeApi.Statement.read("SHOW GRANTS TO ROLE " + ObjectName.quote(role), MAX_GRANTS + 1));
      found = rows(c, a, MAX_GRANTS + 1);
    } catch (ConnectorException e) {
      return new Grants(TestResult.Level.UNKNOWN, List.of(), List.of(), "Its grants couldn't be read: "
          + e.getMessage(), false);
    }
    Map<String, Integer> at = new LinkedHashMap<>();
    JsonNode rowType = a.rowType();
    for (int i = 0; i < rowType.size(); i++) {
      at.put(rowType.get(i).path("name").asString("").toLowerCase(Locale.ROOT), i);
    }
    Integer privilegeAt = at.get("privilege");
    Integer onAt = at.get("granted_on");
    Integer nameAt = at.get("name");
    if (privilegeAt == null || onAt == null || nameAt == null) {
      return new Grants(TestResult.Level.UNKNOWN, List.of(), List.of(), "Its grants came back in a form GateRook "
          + "doesn't read.", false);
    }
    Set<String> writes = new LinkedHashSet<>();
    Set<String> inherited = new LinkedHashSet<>();
    boolean admin = false;
    for (JsonNode r : found.subList(0, Math.min(found.size(), MAX_GRANTS))) {
      String privilege = r.path(privilegeAt).asString("").toUpperCase(Locale.ROOT);
      String on = r.path(onAt).asString("").toUpperCase(Locale.ROOT);
      String name = r.path(nameAt).asString("");
      if (on.equals("ROLE") && privilege.equals("USAGE")) {
        inherited.add(name);
        admin |= ADMIN_ROLES.contains(name.toUpperCase(Locale.ROOT));
      } else if (writes(privilege, on)) {
        writes.add(privilege);
      }
    }
    List<String> w = List.copyOf(writes);
    List<String> inh = List.copyOf(inherited);
    if (admin) {
      return new Grants(TestResult.Level.ADMIN, w, inh, role + " inherits a system role: " + String.join(", ", inh)
          + ".", true);
    }
    if (!w.isEmpty()) {
      return new Grants(TestResult.Level.WRITE, w, inh, role + " may change data or objects: " + String.join(", ", w)
          + ".", true);
    }
    if (!inh.isEmpty() || found.size() > MAX_GRANTS) {
      return new Grants(TestResult.Level.UNKNOWN, w, inh, role + " has no write privilege of its own, but inherits "
          + String.join(", ", inh) + ", whose grants weren't read.", true);
    }
    return new Grants(TestResult.Level.READ, w, inh, role + " has no write privilege of its own and inherits no "
        + "role.", true);
  }

  /** Whether a privilege lets a role change data or objects. */
  static boolean writes(String privilege, String on) {
    return privilege.equals("OWNERSHIP") || privilege.equals("INSERT") || privilege.equals("UPDATE")
        || privilege.equals("DELETE") || privilege.equals("TRUNCATE") || privilege.equals("MODIFY")
        || privilege.equals("WRITE") || privilege.equals("REBUILD") || privilege.equals("EVOLVE SCHEMA")
        || privilege.equals("MANAGE GRANTS") || privilege.startsWith("CREATE ") || privilege.startsWith("APPLY")
        || privilege.startsWith("ALL") || privilege.startsWith("EXECUTE ") || privilege.startsWith("IMPORT")
        || (privilege.equals("OPERATE") && !on.equals("WAREHOUSE"));
  }

  static List<Hint> hints(Env env, SnowflakeSettings s, String user, String role, TestResult.Level level,
      Grants grants, List<String> secondary, String warehouse) {
    List<Hint> out = new ArrayList<>();
    boolean dev = env == Env.DEV;
    String boundary = "Snowflake has no read-only transaction, so the role is the boundary";
    if (level == TestResult.Level.ADMIN) {
      out.add(new Hint(dev ? Severity.WARNING : Severity.DANGER, "snowflake.admin-role", "/role", display(role)
          + " can change anything in the account. " + boundary + ": use a role that can only read."));
    } else if (level == TestResult.Level.WRITE) {
      out.add(new Hint(dev ? Severity.INFO : Severity.WARNING, "snowflake.role-writes", "/role", display(role)
          + " may change data (" + String.join(", ", grants.writes()) + "). GateRook checks every read, but " + boundary
          + ": use a role that can only read outside dev."));
    } else if (!dev && level != TestResult.Level.READ) {
      out.add(new Hint(Severity.INFO, "snowflake.read-only-role", "/role", boundary + ": give the user a role that "
          + "can only read, and only that role."));
    }
    if (!secondary.isEmpty()) {
      out.add(new Hint(dev ? Severity.INFO : Severity.WARNING, "snowflake.secondary-roles", "/user",
          "Secondary roles are active (" + String.join(", ", secondary) + "): Snowflake authorizes statements with "
              + "them too. Set DEFAULT_SECONDARY_ROLES = () for the user, or use a token restricted to one role."));
    }
    if (warehouse == null) {
      out.add(new Hint(Severity.WARNING, "snowflake.no-warehouse", "/warehouse", "No warehouse: queries over tables "
          + "need one. Set Warehouse, or give the user a default warehouse its role may use."));
    }
    if (user != null && s.auth() == SnowflakeSettings.Auth.TOKEN && !user.equalsIgnoreCase(s.user())) {
      out.add(new Hint(Severity.WARNING, "snowflake.user", "/user", "The token signs in as " + display(user)
          + ", not " + s.user() + "."));
    }
    return out;
  }

  /** {@code CURRENT_SECONDARY_ROLES()}: {@code {"roles":"A,B","value":"ALL"}}, the roles it names. */
  static List<String> secondaryRoles(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      JsonNode n = JSON.readTree(json);
      String roles = n.path("roles").asString("");
      List<String> out = new ArrayList<>();
      for (String r : roles.split(",")) {
        if (!r.isBlank()) {
          out.add(r.strip());
        }
      }
      return out;
    } catch (JacksonException e) {
      return List.of();
    }
  }

  private static String text(JsonNode n) {
    return n.isString() ? n.asString() : null;
  }

  /** A name a server sent, one short line. */
  private static String display(String name) {
    if (name == null) {
      return "none";
    }
    String line = OutputSanitizer.sanitize(name.replaceAll("\\s+", " "));
    return line.length() > 128 ? line.substring(0, 127) + "…" : line;
  }

  /**
   * Setup helpers ({@link SnowflakeSuggestions}): the Snowflake CLI's connections on this computer; signed in, the
   * user's defaults, roles, warehouses, databases and schemas.
   */
  @Override
  public Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope) {
    return SnowflakeSuggestions.suggest(api, cfg, secrets, ctx, scope, machine);
  }

  /** Tests: this computer's files and environment elsewhere. */
  SnowflakeConnector machine(SnowflakeSuggestions.Machine machine) {
    this.machine = Objects.requireNonNull(machine, "machine");
    return this;
  }

  @Override
  public HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    try {
      SnowflakeSettings s = SnowflakeSettings.of(cfg);
      SnowflakeApi.Call c = call(cfg, s, secrets, ctx, "health");
      long started = System.nanoTime();
      api.run(c, SnowflakeApi.Statement.read("SELECT 1", 2));
      return HealthResult.healthy(ms(started));
    } catch (ConnectorException e) {
      return HealthResult.unreachable(e.getMessage());
    }
  }

  @Override
  public Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object) {
    ObjectNode out = F.objectNode();
    out.put("account", cfg.string("/account"));
    SnowflakeSettings s;
    try {
      s = SnowflakeSettings.of(cfg);
    } catch (ConnectorException e) {
      out.put("settings", e.getMessage());
      return Optional.of(out);
    }
    if (s.namespace() != null) {
      out.put("namespace", s.namespace());
    }
    if (s.role() != null) {
      out.put("role", s.role());
    }
    if (s.warehouse() != null) {
      out.put("warehouse", s.warehouse());
    }
    if (s.tables().restricted()) {
      ArrayNode a = out.putArray("tables");
      s.tables().patterns().forEach(a::add);
    } else {
      out.put("tables", "Every table the role may read");
    }
    out.put("names", "Unquoted names are upper case in Snowflake; write \"Mixed\" in double quotes to keep case");
    out.put("reads", "One SELECT, WITH … SELECT, SHOW or DESCRIBE per snowflake_query; Snowflake plans a SELECT "
        + "first, and one whose plan changes data is refused");
    out.put("changes", "One INSERT, UPDATE, DELETE, MERGE, TRUNCATE or DDL statement per snowflake_execute, after "
        + "approval; Snowflake commits it when it ends");
    if (object != null && !object.isBlank()) {
      out.put("table", "Call snowflake_describe_table with table " + object + ".");
    }
    return Optional.of(out);
  }

  @Override
  public void close() {
    api.close();
  }

  private static long ms(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
