package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ActionPreview.Field;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TemplateKind;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.sql.IdentifierRules.Part;
import ai.msrv.gaterook.connectors.sql.IdentifierRules.TableRef;
import ai.msrv.gaterook.connectors.sql.SqlClassifier.Analysis;
import ai.msrv.gaterook.connectors.sql.SqlClassifier.Kind;
import ai.msrv.gaterook.connectors.sql.SqlClassifier.StatementFacts;
import ai.msrv.gaterook.connectors.sql.SqlDialect.ServerInfo;
import ai.msrv.gaterook.connectors.sql.SqlExecutor.BoundSql;
import ai.msrv.gaterook.connectors.sql.SqlRules.SqlPolicy;
import ai.msrv.gaterook.connectors.support.BuiltIn;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The SQL connector of one dialect: PostgreSQL, MySQL/MariaDB or SQL Server (report 04, ARCHITECTURE.md §10 C1).
 *
 * <p>The route of a call comes from {@link SqlClassifier} and {@link SqlRules} in {@link #classify}, without I/O. The
 * PostgreSQL and MySQL enforce reads in the database: every read — {@code sql_query}, introspection, a read
 * template, even one a person approved because it calls a function that isn't built in — runs in a READ ONLY
 * transaction that ends with ROLLBACK ({@link SqlExecutor}). SQL Server instead requires strictly analyzed SELECTs
 * and known built-in functions, with rollback; a SELECT-only login supplies server-enforced protection.
 * A change runs only after approval
 * ({@code ctx.approved()}), exactly as the person saw it ({@code approvedPayload}), in one transaction.
 *
 * <p>{@link #execute} gets no policy, so it checks again what it can without one (defence in depth): a read is one
 * analyzed read without denied functions, and without approval only built-in ones; a change needs approval. What only
 * the policy knows — the tables agents may use — {@link #classify} remembers for its call:
 * {@code sql_list_schemas} filters its listing with it.
 */
public final class JdbcSqlConnector implements Connector, SecretResolver.CredentialRotator {

  static final String SCHEMA_DIRECTORY = "sql";
  /** Rows of {@code sql_query} when the agent doesn't say, as its input schema's default. */
  static final int DEFAULT_QUERY_LIMIT = 50;
  /** Tables of {@code sql_list_schemas} when the agent doesn't say. */
  static final int DEFAULT_LIST_LIMIT = 500;

  private final SqlDialect dialect;
  private final ConnectorManifest manifest;
  private final SqlQueryTemplates templates;
  private final SqlPools pools;
  private final SqlExecutor executor;
  private final TestProbe probe;
  private final CallPolicies policies = new CallPolicies();

  public JdbcSqlConnector(SqlDialect dialect) {
    this.dialect = Objects.requireNonNull(dialect, "dialect");
    SchemaResources schemas = SchemaResources.of(JdbcSqlConnector.class, SCHEMA_DIRECTORY);
    this.manifest = ConnectorManifest.builder(dialect.type(), BuiltIn.VERSION, ConnectorManifest.Kind.BUILT_IN)
        .name(dialect.displayName())
        .icon("database")
        .group("Databases")
        .description(dialect.description())
        .configSchema(schemas.load(dialect.configSchemaName()))
        .policySchema(schemas.load("policy"))
        .tools(SqlTools.definitions(schemas))
        .build();
    this.templates = new SqlQueryTemplates(dialect, schemas.load(SqlQueryTemplates.KIND + ".definition"),
        schemas.load(SqlTools.QUERY + ".output"));
    this.pools = new SqlPools(dialect);
    this.executor = new SqlExecutor(dialect);
    this.probe = new TestProbe(dialect);
  }

  public SqlDialect dialect() {
    return dialect;
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  @Override
  public List<TemplateKind> templateKinds() {
    return List.of(templates);
  }

  private boolean mysql() {
    return dialect.mysql();
  }

  // ── Classification ──────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    ConnectionSettings settings;
    try {
      settings = callSettings(call, cfg, null);
    } catch (ConnectorException e) {
      return Classification.deny(policy.rule("database-not-selected"), e.getMessage(), null);
    }
    SqlPolicy p = SqlPolicy.of(policy, cfg, dialect, settings);
    if (call.template() != null) {
      TemplateCall t = TemplateCall.of(call.template().binding(), dialect);
      if (t.problem() != null) {
        return Classification.deny(p.rule("template-invalid"), t.problem(), "Save the template again.");
      }
      return SqlRules.template(t.analysis(), p, call.tool());
    }
    return switch (call.tool()) {
      case SqlTools.LIST_SCHEMAS -> {
        policies.remember(call.callId(), p);
        yield Classification.read();
      }
      case SqlTools.DESCRIBE_TABLE -> SqlRules.describe(IdentifierRules.table(Objects.requireNonNullElse(
          call.string("table"), ""), dialect, p.defaultSchema()), p);
      case SqlTools.QUERY -> SqlRules.query(SqlClassifier.analyze(call.string("sql"), dialect), p);
      case SqlTools.EXECUTE -> SqlRules.execute(SqlClassifier.analyze(call.string("sql"), dialect), p);
      default -> Classification.deny(p.rule("unknown-tool"), manifest.name() + " has no tool " + call.tool() + ".",
          null);
    };
  }

  // ── The exact action ────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    ConnectionSettings s;
    try {
      s = callSettings(call, cfg, null);
    } catch (ConnectorException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
    String target = cfg.name() + " · " + s.target();
    if (call.template() != null) {
      return describeTemplate(call, cfg, s, target);
    }
    return switch (call.tool()) {
      case SqlTools.QUERY, SqlTools.EXECUTE -> describeSql(call, cfg, s, target);
      default -> {
        // A read tool an endpoint put in Approval: its arguments are the whole action.
        ObjectNode args = call.arguments().deepCopy();
        yield ActionPreview.of(ActionPreview.Kind.JSON, "json", args.toString(), target,
                shorten(call.tool() + " on " + cfg.name()))
            .withPayload(databasePayload(JsonNodeFactory.instance.objectNode().set("arguments", args), cfg, s))
            .withFacets(Map.of("tool", call.tool()))
            .withImpact(List.of(new Field("Transaction", readTransaction())));
      }
    };
  }

  private ActionPreview describeSql(ToolCall call, ConnectionConfig cfg, ConnectionSettings s, String target) {
    String sql = Objects.requireNonNullElse(call.string("sql"), "");
    Analysis a = SqlClassifier.analyze(sql, dialect);
    List<Field> impact = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    Map<String, String> facets = new LinkedHashMap<>();
    facets.put("statement", statementTypes(a));
    if (SqlTools.QUERY.equals(call.tool())) {
      impact.add(new Field("Transaction", readTransaction()));
      warnings.addAll(functionWarnings(a, cfg, s));
    } else {
      List<String> targets = SqlRules.targets(a, dialect, s.defaultSchema());
      impact.add(new Field("Target table", targets.isEmpty() ? (a.parsed() ? "None" : "Unknown: not analyzed")
          : String.join(", ", targets)));
      if (!targets.isEmpty()) {
        facets.put("tables", String.join(",", targets));
      }
      impact.add(new Field("Transaction", transaction(a)));
      if (!a.parsed()) {
        warnings.add("Not analyzed by the gateway: " + (a.problem() == null ? "it couldn't read this statement."
            : a.problem()) + " Check the exact text above.");
      }
      if (mysql() && a.statements().stream().anyMatch(st -> st.kind() == Kind.DDL)) {
        warnings.add("MySQL commits a schema change at once: it can't be rolled back.");
      }
    }
    String summary = shorten(call.tool() + " on " + cfg.name() + ": " + statementTypes(a) + firstTargets(a, s)
        + (a.count() > 1 ? " (" + a.count() + " statements)" : ""));
    return ActionPreview.of(ActionPreview.Kind.SQL, "sql", sql, target, summary)
        .withImpact(impact)
        .withWarnings(warnings)
        .withFacets(facets)
        .withPayload(databasePayload(JsonNodeFactory.instance.objectNode().put("sql", sql), cfg, s));
  }

  private ActionPreview describeTemplate(ToolCall call, ConnectionConfig cfg, ConnectionSettings s, String target) {
    ToolCall.TemplateBinding b = call.template();
    TemplateCall t = TemplateCall.of(b.binding(), dialect);
    List<Field> fields = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    fields.add(new Field("Template", t.name() + " · version " + b.version()));
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    try {
      // Durations ("-PT24H") become times now: the request stores them, and they run as approved.
      List<TemplateParams.Bound> values = TemplateParams.resolve(t.params(), t.order(), call.arguments(),
          Clock.systemUTC());
      fields.add(new Field("Parameters", TemplateParams.describe(values)));
      payload.set("values", TemplateParams.valuesJson(values));
    } catch (ConnectorException e) {
      warnings.add(e.getMessage());
      payload.putObject("values");
    }
    fields.add(new Field("Binding", "Values are bound by the driver, never pasted into the SQL"));
    List<Field> impact = new ArrayList<>();
    if (t.read()) {
      impact.add(new Field("Transaction", readTransaction()));
      warnings.addAll(functionWarnings(t.analysis(), cfg, s));
    } else {
      List<String> targets = SqlRules.targets(t.analysis(), dialect, s.defaultSchema());
      impact.add(new Field("Target table", targets.isEmpty() ? "None" : String.join(", ", targets)));
      impact.add(new Field("Transaction", transaction(t.analysis())));
    }
    if (t.problem() != null) {
      warnings.add(t.problem());
    }
    return ActionPreview.of(ActionPreview.Kind.SQL, "sql", t.sql(), target,
            shorten(t.name() + " on " + cfg.name() + " (" + cfg.env().id() + ")"))
        .withFields(fields)
        .withImpact(impact)
        .withWarnings(warnings)
        .withFacets(Map.of("template", t.name() + "@v" + b.version(), "statement", t.statement()))
        .withPayload(databasePayload(payload, cfg, s));
  }

  private ObjectNode databasePayload(ObjectNode payload, ConnectionConfig cfg, ConnectionSettings s) {
    if (DatabaseSelection.configured(cfg)) payload.put("database", s.database());
    return payload;
  }

  /** Resolve the approved database independently of mutable call arguments. */
  private ConnectionSettings callSettings(ToolCall call, ConnectionConfig cfg, ExecContext ctx)
      throws ConnectorException {
    String requested = call.template() == null ? call.string("database")
        : call.template().binding().path("database").asString(null);
    if (ctx != null && ctx.approved() && DatabaseSelection.configured(cfg)) {
      requested = ctx.approvedPayload().map(payload -> payload.path("database").asString(null)).orElse(null);
      if (requested == null) throw ConnectorException.denied("The approved action does not specify a database.");
    }
    String database = DatabaseSelection.resolve(cfg, requested);
    return ConnectionSettings.of(cfg, dialect).withDatabase(database, mysql());
  }

  /** Enforce database boundaries even when execute is called without the policy classifier. */
  private void checkSelectedTables(Analysis analysis, ConnectionConfig cfg, ConnectionSettings settings)
      throws ConnectorException {
    if (dialect.sqlServer()) {
      for (StatementFacts statement : analysis.statements()) {
        if (statement.tables() == null) {
          throw ConnectorException.denied("The gateway cannot verify the SQL Server database scope.");
        }
        for (String name : statement.tables()) {
          if (IdentifierRules.table(name, dialect, settings.defaultSchema()) == null) {
            throw ConnectorException.denied("SQL Server statements must stay in the selected database. Use schema.table.");
          }
        }
      }
      if (analysis.functions().stream().anyMatch(function -> function.parts().size() > 2)) {
        throw ConnectorException.denied("SQL Server functions must stay in the selected database.");
      }
      return;
    }
    if (!mysql() || !DatabaseSelection.configured(cfg)) return;
    Allowlist scope = Allowlist.of(List.of(), settings.schemaAllowlist(), true, DatabaseSelection.selected(cfg));
    for (StatementFacts statement : analysis.statements()) {
      if (statement.tables() == null) {
        throw ConnectorException.denied("The gateway cannot verify this statement against the selected databases.");
      }
      for (String name : statement.tables()) {
        if (!scope.allows(IdentifierRules.table(name, true, settings.defaultSchema()))) {
          throw ConnectorException.denied("Table " + name + " is outside the selected database scope.");
        }
      }
    }
  }

  /** What the read of a request calls that isn't built in: the reason a person decides. */
  private List<String> functionWarnings(Analysis a, ConnectionConfig cfg, ConnectionSettings s) {
    FunctionRules.Verdict v = FunctionRules.check(a.functions(), dialect, FunctionRules.fromMetadata(cfg.metadata()),
        List.of(), s.allowedFunctions());
    if (v.notBuiltIn().isEmpty()) {
      return List.of();
    }
    if (dialect.sqlServer()) {
      return List.of("SQL Server reads refuse functions outside the known built-in set: "
          + String.join(", ", v.notBuiltIn()) + ".");
    }
    return List.of("Calls " + String.join(", ", v.notBuiltIn()) + (v.notBuiltIn().size() == 1 ? ", which isn't a "
        + "built-in function" : ", which aren't built-in functions") + ": it may change data or act outside the "
        + "database. It still runs in a read-only transaction that is rolled back.");
  }

  private String readTransaction() {
    return dialect.sqlServer() ? "Validated read query, always rolled back" : "Read only, always rolled back";
  }

  private String transaction(Analysis a) {
    if (mysql() && a.statements().stream().anyMatch(st -> st.kind() == Kind.DDL)) {
      return "No: MySQL commits a schema change at once";
    }
    return a.count() > 1 ? "Yes, all " + a.count() + " statements in one, rolled back on any error"
        : "Yes, rolled back on error";
  }

  private static String statementTypes(Analysis a) {
    Set<String> types = new LinkedHashSet<>();
    a.statements().forEach(st -> types.add(st.type()));
    return types.isEmpty() ? "SQL" : String.join(", ", types);
  }

  private String firstTargets(Analysis a, ConnectionSettings s) {
    List<String> targets = SqlRules.targets(a, dialect, s.defaultSchema());
    if (targets.isEmpty()) {
      return "";
    }
    return " " + (targets.size() <= 2 ? String.join(", ", targets) : targets.getFirst() + " and "
        + (targets.size() - 1) + " more");
  }

  /** At most 120 characters, for channels. */
  static String shorten(String s) {
    return s.length() <= 120 ? s : s.substring(0, 119) + "…";
  }

  // ── Impact, before the request exists ───────────────────────────────────────────────────────────────────────

  /**
   * A change gets its estimated rows from EXPLAIN, without running it, in a transaction that is always rolled back.
   * A statement the database can't even plan — a syntax error, a missing table in the first statement — is refused
   * here: no person is asked to approve what can't run. An unreachable database fails the call.
   */
  @Override
  public PreparedAction prepare(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    if (call.template() != null) {
      // A value the template can't take (a time that isn't one, a bound the schema doesn't express) would leave
      // the request without values: nobody approves a statement whose values are unknown.
      TemplateCall t = TemplateCall.of(call.template().binding(), dialect);
      if (t.problem() != null) {
        return PreparedAction.reject(t.problem());
      }
      try {
        TemplateParams.resolve(t.params(), t.order(), call.arguments(), ctx.clock());
      } catch (ConnectorException e) {
        return PreparedAction.reject(e.getMessage());
      }
    }
    ActionPreview preview = describeAction(call, cfg);
    List<BoundSql> changes = changes(call, preview);
    if (changes.isEmpty()) {
      return PreparedAction.approval(preview, null);
    }
    ConnectionSettings s = callSettings(call, cfg, null);
    SqlPools.Pool pool = pool(cfg, s, secrets, ctx);
    List<String> types = statementTypesOf(call);
    Estimates estimates = executor.explain(pool, s, ctx, cfg.name(),
        (c, server, timeoutMs) -> estimate(c, server, changes));
    if (estimates.refusal() != null) {
      return PreparedAction.reject(estimates.refusal());
    }
    List<Field> impact = new ArrayList<>(preview.impact());
    impact.add(Math.min(1, impact.size()), new Field("Estimated rows", estimates.text(types)));
    impact.add(new Field("Statement timeout",
        SqlErrors.seconds(s.statementTimeout(ctx.limits().statementTimeout()))));
    List<String> warnings = new ArrayList<>(preview.warnings());
    warnings.addAll(estimates.warnings());
    return PreparedAction.approval(preview.withImpact(impact).withWarnings(warnings), null);
  }

  /**
   * The statements of a change, one per piece, as they would run, with {@code null} for those without a plan (DDL, a
   * read); empty for a call that changes nothing or that the gateway can't split.
   */
  private List<BoundSql> changes(ToolCall call, ActionPreview preview) {
    if (call.template() != null) {
      TemplateCall t = TemplateCall.of(call.template().binding(), dialect);
      if (t.read() || t.problem() != null || t.analysis().kind() != Kind.DML || !EXPLAINABLE.contains(t.statement())) {
        return List.of();
      }
      try {
        List<TemplateParams.Bound> values = TemplateParams.fromJson(t.params(), t.order(),
            preview.payload().path("values"));
        return List.of(BoundSql.template(t.jdbc(), values));
      } catch (ConnectorException | RuntimeException e) {
        return List.of();
      }
    }
    if (!SqlTools.EXECUTE.equals(call.tool())) {
      return List.of();
    }
    String sql = Objects.requireNonNullElse(call.string("sql"), "");
    Analysis a = SqlClassifier.analyze(sql, dialect);
    if (!a.parsed()) {
      return List.of();
    }
    List<String> pieces = SqlLexer.scan(sql, dialect).statements();
    if (pieces.size() != a.count()) {
      return List.of();
    }
    List<BoundSql> out = new ArrayList<>();
    for (int i = 0; i < pieces.size(); i++) {
      StatementFacts st = a.statements().get(i);
      out.add(st.kind() == Kind.DML && EXPLAINABLE.contains(st.type()) ? BoundSql.plain(pieces.get(i)) : null);
    }
    return out.stream().allMatch(Objects::isNull) ? List.of() : out;
  }

  /**
   * Changes EXPLAIN can plan: not {@code CALL} (a syntax error after EXPLAIN) nor {@code EXPLAIN ANALYZE} itself; a
   * {@code SELECT} here is one that locks or carries a DELETE in a CTE.
   */
  private static final Set<String> EXPLAINABLE = Set.of("UPDATE", "DELETE", "INSERT", "MERGE", "REPLACE", "SELECT");

  private List<String> statementTypesOf(ToolCall call) {
    if (call.template() != null) {
      return List.of(TemplateCall.of(call.template().binding(), dialect).statement());
    }
    return SqlClassifier.analyze(Objects.requireNonNullElse(call.string("sql"), ""), dialect).statements().stream()
        .map(StatementFacts::type).toList();
  }

  /**
   * What EXPLAIN said.
   *
   * @param rows per statement: the estimate, -1 when unknown, {@code null} for a statement that isn't DML
   */
  private record Estimates(List<Long> rows, List<String> warnings, String refusal) {

    String text(List<String> types) {
      List<String> parts = new ArrayList<>();
      for (int i = 0; i < rows.size(); i++) {
        Long n = rows.get(i);
        if (n == null) {
          continue;
        }
        String count = n < 0 ? "unknown" : String.format(Locale.US, "%,d", n);
        parts.add(rows.size() == 1 ? count : (i < types.size() ? types.get(i) : "Statement " + (i + 1)) + ": "
            + count);
      }
      return parts.isEmpty() ? "Unknown" : String.join(" · ", parts) + " (from EXPLAIN)";
    }
  }

  private Estimates estimate(Connection c, ServerInfo server, List<BoundSql> statements) throws SQLException {
    List<Long> rows = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    for (int i = 0; i < statements.size(); i++) {
      BoundSql st = statements.get(i);
      if (st == null) {
        rows.add(null);
        continue;
      }
      try {
        OptionalLong n = dialect.estimateRows(c, server, st);
        rows.add(n.isPresent() ? n.getAsLong() : -1L);
      } catch (SQLException e) {
        String state = SqlErrors.state(e);
        if (SqlErrors.unreachable(e, state)) {
          throw e;
        }
        int code = e.getErrorCode();
        boolean syntax = "42601".equals(state) || (mysql() && code == 1064)
            || (dialect.sqlServer() && (code == 102 || code == 156));
        // A missing table or column, or a user that may not do it: no statement before this one changes that.
        boolean missing = "42P01".equals(state) || "42703".equals(state) || "42883".equals(state)
            || "42501".equals(state) || (mysql() && (code == 1146 || code == 1054 || code == 1142 || code == 1143))
            || (dialect.sqlServer() && (code == 207 || code == 208 || code == 229));
        if (syntax || (missing && i == 0)) {
          return new Estimates(rows, warnings, "The database can't run it, so nobody was asked to approve it: "
              + SqlErrors.describe(e) + ".");
        }
        rows.add(-1L);
        warnings.add("The impact of statement " + (i + 1) + " couldn't be estimated: " + SqlErrors.describe(e)
            + ".");
        if (!mysql()) {
          // PostgreSQL aborts the transaction at its first error: the plans after it can't be asked for.
          for (int j = i + 1; j < statements.size(); j++) {
            rows.add(statements.get(j) == null ? null : -1L);
          }
          break;
        }
      }
    }
    return new Estimates(rows, warnings, null);
  }

  // ── Execution ───────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    ConnectionSettings s = callSettings(call, cfg, ctx);
    if (call.template() != null) {
      return runTemplate(call, cfg, s, secrets, ctx);
    }
    return switch (call.tool()) {
      case SqlTools.LIST_SCHEMAS -> listSchemas(call, cfg, s, secrets, ctx);
      case SqlTools.DESCRIBE_TABLE -> describeTable(call, cfg, s, secrets, ctx);
      case SqlTools.QUERY -> query(call, cfg, s, secrets, ctx);
      case SqlTools.EXECUTE -> change(call, cfg, s, secrets, ctx);
      default -> throw ConnectorException.badInput(manifest.name() + " has no tool " + call.tool() + ".");
    };
  }

  /** The arguments that run: the approved request's, or the call's. */
  private static JsonNode arguments(ToolCall call, ExecContext ctx) {
    if (ctx.approved() && ctx.approvedPayload().isPresent() && ctx.approvedPayload().get().has("arguments")) {
      return ctx.approvedPayload().get().get("arguments");
    }
    return call.arguments();
  }

  private static String sql(ToolCall call, ExecContext ctx) {
    if (ctx.approved() && ctx.approvedPayload().isPresent() && ctx.approvedPayload().get().path("sql").isString()) {
      return ctx.approvedPayload().get().get("sql").asString();
    }
    return Objects.requireNonNullElse(call.string("sql"), "");
  }

  private ToolResult query(ToolCall call, ConnectionConfig cfg, ConnectionSettings s, SecretResolver secrets,
      ExecContext ctx) throws ConnectorException {
    String sql = sql(call, ctx);
    Analysis a = SqlClassifier.analyze(sql, dialect);
    if (!a.parsed() || a.count() != 1 || a.kind() != Kind.READ) {
      throw ConnectorException.denied("sql_query only runs one statement the gateway reads as a read.");
    }
    checkSelectedTables(a, cfg, s);
    FunctionRules.Verdict v = FunctionRules.check(a.functions(), dialect, FunctionRules.fromMetadata(cfg.metadata()),
        List.of(), s.allowedFunctions());
    if (!v.denied().isEmpty()) {
      throw ConnectorException.denied(String.join(", ", v.denied()) + " isn't allowed through GateRook.");
    }
    if (dialect.sqlServer() && !v.notBuiltIn().isEmpty()) {
      throw ConnectorException.denied("SQL Server reads only allow known built-in functions.");
    }
    if (!v.notBuiltIn().isEmpty() && !ctx.approved()) {
      throw ConnectorException.denied("Not approved: " + String.join(", ", v.notBuiltIn()) + " isn't a built-in "
          + "function, so a person approves the call first.");
    }
    int limit = Math.max(1, Math.min(arguments(call, ctx).path("limit").asInt(DEFAULT_QUERY_LIMIT),
        ctx.limits().maxRows()));
    return runRead(cfg, s, secrets, ctx, BoundSql.plain(sql), limit);
  }

  /**
   * Runs an admitted read and rolls back. PostgreSQL/MySQL additionally enforce READ ONLY; their tests call this
   * directly with writes to verify server enforcement. SQL Server must pass the admission checks before this call.
   */
  ToolResult runRead(ConnectionConfig cfg, ConnectionSettings s, SecretResolver secrets, ExecContext ctx,
      BoundSql statement, int limit) throws ConnectorException {
    SqlPools.Pool pool = pool(cfg, s, secrets, ctx);
    ResultReader.Read read = executor.read(pool, s, ctx, cfg.name(),
        (c, server, timeoutMs) -> executor.query(c, ctx, timeoutMs, statement, limit));
    if (read == null) {
      return ToolResult.table(new ToolResult.Table(List.of(), JsonNodeFactory.instance.arrayNode(), null), null);
    }
    ToolResult result = ToolResult.table(read.table(), null);
    return read.truncatedReason() == null ? result : result.truncated(read.truncatedReason());
  }

  private ToolResult change(ToolCall call, ConnectionConfig cfg, ConnectionSettings s, SecretResolver secrets,
      ExecContext ctx) throws ConnectorException {
    if (!ctx.approved()) {
      throw ConnectorException.denied("Not approved: sql_execute changes data, so a person approves it first.");
    }
    String sql = sql(call, ctx);
    Analysis a = SqlClassifier.analyze(sql, dialect);
    checkSelectedTables(a, cfg, s);
    if (a.tooComplex() || a.kind() == Kind.FORBIDDEN) {
      throw ConnectorException.denied("This statement can't run through GateRook.");
    }
    if (a.count() > (mysql() || dialect.sqlServer() ? 1 : SqlRules.MAX_POSTGRES_STATEMENTS)
        || (dialect.sqlServer() && !a.parsed())) {
      throw ConnectorException.denied("Too many statements for one sql_execute.");
    }
    FunctionRules.Verdict v = FunctionRules.check(a.functions(), dialect, FunctionRules.fromMetadata(cfg.metadata()),
        List.of(), s.allowedFunctions());
    if (!v.denied().isEmpty()) {
      throw ConnectorException.denied(String.join(", ", v.denied()) + " isn't allowed through GateRook.");
    }
    List<String> types = a.statements().stream().map(StatementFacts::type).toList();
    return runChange(cfg, s, secrets, ctx, BoundSql.plain(sql), types);
  }

  private ToolResult runChange(ConnectionConfig cfg, ConnectionSettings s, SecretResolver secrets, ExecContext ctx,
      BoundSql statement, List<String> types) throws ConnectorException {
    SqlPools.Pool pool = pool(cfg, s, secrets, ctx);
    List<SqlExecutor.Applied> applied = executor.write(pool, s, ctx, cfg.name(),
        (c, server, timeoutMs) -> executor.change(c, ctx, timeoutMs, statement, types));
    return changeResult(applied);
  }

  /** {@code {committed, rowsAffected, statements:[{type, rowsAffected, returning?}]}} and "1 row updated". */
  static ToolResult changeResult(List<SqlExecutor.Applied> applied) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    out.put("committed", true);
    long total = 0;
    ArrayNode statements = out.putArray("statements");
    List<String> parts = new ArrayList<>();
    for (SqlExecutor.Applied a : applied) {
      ObjectNode o = statements.addObject().put("type", a.type()).put("rowsAffected", a.rowsAffected());
      total += Math.max(0, a.rowsAffected());
      if (a.returning() != null) {
        o.set("returning", rowsAsObjects(a.returning()));
        if (a.returningTruncated()) {
          o.put("returningTruncated", true);
        }
      }
      parts.add(done(a));
    }
    out.put("rowsAffected", total);
    String text = applied.size() == 1 ? parts.getFirst() : "Committed " + applied.size() + " statements: "
        + String.join(", ", parts);
    return ToolResult.json(out, text);
  }

  /** Rows keyed by column name: the core masks a known-sensitive key ({@code email}) as it would a column. */
  private static ArrayNode rowsAsObjects(ToolResult.Table t) {
    ArrayNode rows = JsonNodeFactory.instance.arrayNode();
    for (JsonNode row : t.rows()) {
      ObjectNode o = rows.addObject();
      for (int i = 0; i < t.columns().size() && i < row.size(); i++) {
        o.set(t.columns().get(i).name(), row.get(i));
      }
    }
    return rows;
  }

  private static String done(SqlExecutor.Applied a) {
    String verb = switch (a.type()) {
      case "UPDATE" -> "updated";
      case "INSERT" -> "inserted";
      case "DELETE" -> "deleted";
      case "MERGE" -> "merged";
      case "REPLACE" -> "replaced";
      default -> null;
    };
    if (verb == null) {
      return a.type() + (a.rowsAffected() > 0 ? " (" + rows(a.rowsAffected()) + ")" : " done");
    }
    return rows(a.rowsAffected()) + " " + verb;
  }

  private static String rows(long n) {
    return String.format(Locale.US, "%,d", n) + (n == 1 ? " row" : " rows");
  }

  // ── Templates ───────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * A compiled template as the core keeps it ({@link SqlQueryTemplates#compile}). What runs is derived again from the
   * saved SQL — its JDBC form, the placeholder order, its class — and must match the binding, so a binding can't run
   * anything but its own statement.
   */
  record TemplateCall(String name, String sql, String jdbc, List<String> order, List<TemplateParams.Param> params,
      Analysis analysis, String statement, boolean read, Integer maxRows, String problem) {

    static TemplateCall of(JsonNode binding, boolean mysql) {
      return of(binding, mysql ? new MySqlDialect() : new PostgresDialect());
    }

    static TemplateCall of(JsonNode binding, SqlDialect dialect) {
      String name = binding.path("name").asString("template");
      String sql = binding.path("sql").asString("");
      List<String> errors = new ArrayList<>();
      List<TemplateParams.Param> params = TemplateParams.parse(binding.path("params"), errors);
      Set<String> declared = new LinkedHashSet<>();
      params.forEach(p -> declared.add(p.name()));
      NamedSql named = NamedSql.compile(sql, dialect, declared);
      Analysis a = SqlClassifier.analyze(sql, dialect);
      Integer maxRows = binding.path("maxRows").isIntegralNumber() ? binding.get("maxRows").asInt() : null;
      String statement = a.statements().isEmpty() ? "SQL" : a.statements().getFirst().type();
      List<String> storedOrder = new ArrayList<>();
      binding.path("order").forEach(n -> storedOrder.add(n.asString()));
      String problem = null;
      if (!errors.isEmpty() || !named.errors().isEmpty()) {
        problem = "The template " + name + " no longer compiles: " + String.join(" ", errors.isEmpty()
            ? named.errors() : errors);
      } else if (!named.jdbc().equals(binding.path("jdbc").asString("")) || !named.order().equals(storedOrder)) {
        problem = "The binding of " + name + " doesn't match its SQL.";
      } else if (!a.parsed() || a.count() != 1) {
        problem = "The statement of " + name + " can't be analyzed.";
      }
      boolean read = a.parsed() && a.count() == 1 && a.kind() == Kind.READ;
      return new TemplateCall(name, sql, named.jdbc(), named.order(), params, a, statement, read, maxRows, problem);
    }
  }

  private ToolResult runTemplate(ToolCall call, ConnectionConfig cfg, ConnectionSettings s, SecretResolver secrets,
      ExecContext ctx) throws ConnectorException {
    TemplateCall t = TemplateCall.of(call.template().binding(), dialect);
    if (t.problem() != null) {
      throw ConnectorException.denied(t.problem());
    }
    checkSelectedTables(t.analysis(), cfg, s);
    if (t.analysis().kind() == Kind.FORBIDDEN) {
      throw ConnectorException.denied(t.name() + " can't run through GateRook.");
    }
    FunctionRules.Verdict v = FunctionRules.check(t.analysis().functions(), dialect,
        FunctionRules.fromMetadata(cfg.metadata()), List.of(), s.allowedFunctions());
    if (!v.denied().isEmpty()) {
      throw ConnectorException.denied(String.join(", ", v.denied()) + " isn't allowed through GateRook.");
    }
    if (dialect.sqlServer() && t.read() && !v.notBuiltIn().isEmpty()) {
      throw ConnectorException.denied("SQL Server reads only allow known built-in functions.");
    }
    boolean needsApproval = !t.read() || !v.notBuiltIn().isEmpty();
    if (needsApproval && !ctx.approved()) {
      throw ConnectorException.denied("Not approved: " + t.name() + " " + (t.read() ? "calls a function that isn't "
          + "built in" : "changes data") + ", so a person approves it first.");
    }
    List<TemplateParams.Bound> values = ctx.approved() && ctx.approvedPayload().isPresent()
        ? TemplateParams.fromJson(t.params(), t.order(), ctx.approvedPayload().get().path("values"))
        : TemplateParams.resolve(t.params(), t.order(), call.arguments(), ctx.clock());
    BoundSql statement = BoundSql.template(t.jdbc(), values);
    if (t.read()) {
      int limit = ctx.limits().maxRows();
      if (t.maxRows() != null) {
        limit = Math.min(limit, t.maxRows());
      }
      return runRead(cfg, s, secrets, ctx, statement, Math.max(1, limit));
    }
    return runChange(cfg, s, secrets, ctx, statement, List.of(t.statement()));
  }

  // ── Introspection ───────────────────────────────────────────────────────────────────────────────────────────

  private ToolResult listSchemas(ToolCall call, ConnectionConfig cfg, ConnectionSettings s, SecretResolver secrets,
      ExecContext ctx) throws ConnectorException {
    SqlPolicy p = policies.take(call.callId());
    if (p == null) {
      // Only classify knows which tables the policy opens; without it the listing could show what it hides.
      throw ConnectorException.denied("sql_list_schemas wasn't classified for this call.");
    }
    JsonNode args = arguments(call, ctx);
    String schema = blankToNull(args.path("schema").asString(null));
    String pattern = blankToNull(args.path("pattern").asString(null));
    int limit = Math.max(1, Math.min(args.path("limit").asInt(DEFAULT_LIST_LIMIT), 1_000));
    String onlySchema = null;
    if (schema != null) {
      List<Part> parts = IdentifierRules.parts(schema, dialect);
      if (parts.size() != 1) {
        throw ConnectorException.badInput(schema + " isn't a schema name.");
      }
      onlySchema = IdentifierRules.normalize(parts.getFirst(), dialect);
    }
    Allowlist allowlist = p.allowlist();
    String only = onlySchema;
    SqlPools.Pool pool = pool(cfg, s, secrets, ctx);
    SqlCatalog.Listing listing = executor.read(pool, s, ctx, cfg.name(), (c, server, timeoutMs) ->
        dialect.catalog().list(c, only, pattern, allowlist::allows, limit));
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    ArrayNode schemas = out.putArray("schemas");
    for (String name : listing.schemas()) {
      int[] count = listing.counts().getOrDefault(name, new int[2]);
      if (allowlist.restricted() && count[0] + count[1] == 0 && !allowlist.mayAllowSchema(name)) {
        continue;
      }
      schemas.addObject().put("name", name).put("tables", count[0]).put("views", count[1]);
    }
    ArrayNode tables = out.putArray("tables");
    StringBuilder text = new StringBuilder();
    String current = null;
    List<String> names = new ArrayList<>();
    for (SqlCatalog.Table t : listing.tables()) {
      ObjectNode o = tables.addObject().put("schema", t.schema()).put("name", t.name()).put("kind", t.kind());
      if (t.estimatedRows() == null) {
        o.putNull("estimatedRows");
      } else {
        o.put("estimatedRows", t.estimatedRows());
      }
      if (t.comment() == null) {
        o.putNull("comment");
      } else {
        o.put("comment", t.comment());
      }
      if (!t.schema().equals(current)) {
        flush(text, current, names, listing.counts());
        current = t.schema();
      }
      names.add(IdentifierRules.quoteIfNeeded(t.name(), dialect) + ("table".equals(t.kind()) ? "" : " (" + t.kind()
          + ")"));
    }
    flush(text, current, names, listing.counts());
    out.put("truncated", listing.truncated());
    if (text.isEmpty()) {
      text.append(schemas.isEmpty() ? "No schemas you may use." : "No tables match.");
    }
    if (listing.truncated()) {
      text.append("\nMore tables match: narrow it with schema or pattern.");
    }
    return ToolResult.json(out, text.toString().strip());
  }

  private static void flush(StringBuilder text, String schema, List<String> names, Map<String, int[]> counts) {
    if (schema == null || names.isEmpty()) {
      names.clear();
      return;
    }
    int[] count = counts.getOrDefault(schema, new int[2]);
    text.append(schema).append(" (").append(count[0]).append(count[0] == 1 ? " table" : " tables");
    if (count[1] > 0) {
      text.append(", ").append(count[1]).append(count[1] == 1 ? " view" : " views");
    }
    text.append("): ").append(String.join(", ", names)).append('\n');
    names.clear();
  }

  private ToolResult describeTable(ToolCall call, ConnectionConfig cfg, ConnectionSettings s, SecretResolver secrets,
      ExecContext ctx) throws ConnectorException {
    String written = arguments(call, ctx).path("table").asString("");
    TableRef table = IdentifierRules.table(written, dialect, s.defaultSchema());
    if (table == null || table.schema() == null) {
      throw ConnectorException.badInput(table == null ? written + " isn't a table name: write table, schema.table or "
          + (mysql() ? "`Mixed`.`Case`." : "\"Mixed\".\"Case\".") : "Qualify " + written + " with its database: "
          + cfg.name() + " has no default database.");
    }
    if (mysql() && DatabaseSelection.configured(cfg) && !DatabaseSelection.selected(cfg).contains(table.schema())) {
      throw ConnectorException.denied("Database " + table.schema() + " is not selected for " + cfg.name() + ".");
    }
    SqlPools.Pool pool = pool(cfg, s, secrets, ctx);
    Optional<ObjectNode> found = executor.read(pool, s, ctx, cfg.name(),
        (c, server, timeoutMs) -> dialect.catalog().describe(c, table));
    if (found.isEmpty()) {
      throw ConnectorException.badInput("Table " + IdentifierRules.display(table, dialect) + " not found on "
          + cfg.name() + ". Call sql_list_schemas to see the tables.");
    }
    ObjectNode d = found.get();
    return ToolResult.json(d, describeText(d));
  }

  /** {@code public.orders (table, ~1,204 rows): id bigint not null, status text · primary key (id) · 2 indexes}. */
  static String describeText(ObjectNode d) {
    StringBuilder out = new StringBuilder();
    out.append(d.path("schema").asString()).append('.').append(d.path("name").asString()).append(" (")
        .append(d.path("kind").asString());
    if (d.path("estimatedRows").isNumber()) {
      out.append(", ~").append(String.format(Locale.US, "%,d", d.get("estimatedRows").asLong())).append(" rows");
    }
    out.append("): ");
    List<String> columns = new ArrayList<>();
    for (JsonNode c : d.path("columns")) {
      columns.add(c.path("name").asString() + " " + c.path("type").asString()
          + (c.path("nullable").asBoolean(true) ? "" : " not null"));
    }
    out.append(String.join(", ", columns));
    List<String> pk = new ArrayList<>();
    d.path("primaryKey").forEach(n -> pk.add(n.asString()));
    if (!pk.isEmpty()) {
      out.append(" · primary key (").append(String.join(", ", pk)).append(')');
    }
    int indexes = d.path("indexes").size();
    if (indexes > 0) {
      out.append(" · ").append(indexes).append(indexes == 1 ? " index" : " indexes");
    }
    int fks = d.path("foreignKeys").size();
    if (fks > 0) {
      out.append(" · ").append(fks).append(fks == 1 ? " foreign key" : " foreign keys");
    }
    return out.toString();
  }

  /**
   * describe_connection: what the connector knows without I/O — the server, where names resolve, how reads and
   * changes run. A table goes through {@code sql_describe_table}, which checks the tables agents may use.
   */
  @Override
  public Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object) {
    ConnectionSettings s = ConnectionSettings.of(cfg, dialect);
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    JsonNode meta = cfg.metadata();
    out.put("server", meta != null && meta.path("serverVersion").isString() ? meta.get("serverVersion").asString()
        : dialect.displayName());
    if (meta != null && meta.path("replica").asBoolean(false)) {
      out.put("replica", true);
    }
    if (!mysql() && s.database() != null) {
      out.put("database", s.database());
    }
    if (s.defaultSchema() != null) {
      out.put(mysql() ? "defaultDatabase" : "defaultSchema", s.defaultSchema());
    }
    if (!s.schemaAllowlist().isEmpty()) {
      ArrayNode a = out.putArray(mysql() ? "databases" : "schemas");
      s.schemaAllowlist().forEach(a::add);
    }
    if (DatabaseSelection.configured(cfg)) {
      ArrayNode databases = out.putArray("databases");
      DatabaseSelection.selected(cfg).forEach(databases::add);
      out.put("databaseSelection", "Pass database on SQL tool calls when more than one database is selected.");
    }
    out.put("reads", dialect.sqlServer()
        ? "One validated SELECT per sql_query, always rolled back; use a SELECT-only database user for server-enforced protection"
        : "One statement per sql_query, in a read-only transaction that is rolled back");
    out.put("changes", mysql() || dialect.sqlServer() ? "One statement per sql_execute, after approval"
        : "Up to " + SqlRules.MAX_POSTGRES_STATEMENTS + " statements per sql_execute in one transaction, after "
        + "approval");
    if (object != null && !object.isBlank()) {
      out.put("table", "Call sql_describe_table with table " + object + ".");
    }
    return Optional.of(out);
  }

  // ── Test connection, health, rotation ───────────────────────────────────────────────────────────────────────

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    return probe.test(cfg, secrets, ctx);
  }

  @Override
  public TestResult discoverDatabases(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    return probe.discoverDatabases(cfg, secrets, ctx);
  }

  /**
   * A direct connection and one {@code SELECT 1} (the latency), never the pool: health says whether a new connection
   * gets through, and it doesn't take one of the pool's connections from a call.
   */
  @Override
  public HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    ConnectionSettings s = ConnectionSettings.of(cfg, dialect);
    try (Credentials login = Credentials.resolve(cfg, secrets);
         Connection c = connect(s, login, ctx)) {
      try (Statement st = c.createStatement()) {
        st.setQueryTimeout(3);
        long started = System.nanoTime();
        st.execute("SELECT 1");
        return HealthResult.healthy((System.nanoTime() - started) / 1_000_000);
      }
    } catch (ConnectorException e) {
      return HealthResult.unreachable(e.getMessage());
    } catch (SQLException e) {
      TestResult failure = probe.failure(e, s, cfg.string("/credentials/user"));
      return HealthResult.unreachable(failure.error().message());
    } catch (IOException e) {
      return HealthResult.unreachable("The CA certificate can't be used: " + e.getMessage());
    }
  }

  /** A connection outside the pool, with the budget of Test connection. */
  private Connection connect(ConnectionSettings s, Credentials login, ExecContext ctx)
      throws SQLException, IOException {
    ConnectionSettings bounded = s.withConnectTimeoutAtMost(TestProbe.BUDGET_MS);
    Properties props = dialect.properties(bounded, TestProbe.certificates(ctx), TestProbe.BUDGET_MS);
    if (login.user() != null) {
      props.setProperty("user", login.user());
    }
    if (login.password().length > 0) {
      props.setProperty("password", new String(login.password()));
    }
    try {
      Connection c = dialect.driver().connect(dialect.url(bounded), props);
      if (c == null) {
        throw new SQLException("The driver doesn't take this URL.", "08001");
      }
      return c;
    } finally {
      props.remove("password");
    }
  }

  @Override
  public boolean canRotate(ConnectionConfig cfg, String field) {
    return !dialect.sqlServer() && ConnectionSettings.PASSWORD.equals(field) && cfg.string("/credentials/user") != null;
  }

  /**
   * Rotate at the source: logs in with the current password and sets {@code next} for the connection's own user —
   * PostgreSQL through pgjdbc's {@code alterUserPassword} (hashed on this side, so no server log sees it), MySQL with
   * {@code ALTER USER CURRENT_USER() IDENTIFIED BY ?}. The core stores {@code next} as the new version only after this
   * returns; the pool of the old version closes after its calls.
   */
  @Override
  public void rotate(ConnectionConfig cfg, SecretResolver current, String field, char[] next, ExecContext ctx)
      throws ConnectorException {
    if (!canRotate(cfg, field)) {
      throw ConnectorException.badInput("Only the password of " + cfg.name() + " rotates at the source.");
    }
    if (current.isSet(field) && "dynamic".equals(current.meta(field).source())) {
      throw ConnectorException.badInput(cfg.name() + " uses short-lived logins: they rotate in their store.");
    }
    if (next == null || next.length < 16) {
      throw ConnectorException.badInput("A new password has at least 16 characters.");
    }
    ConnectionSettings s = ConnectionSettings.of(cfg, dialect);
    try (Credentials login = Credentials.resolve(cfg, current);
         Connection c = connect(s, login, ctx)) {
      c.setAutoCommit(true);
      dialect.changePassword(c, dialect.identify(c), login.user(), next);
    } catch (SQLException e) {
      throw SqlErrors.of(e, dialect, cfg.name(), ctx.env() == null ? "this environment" : ctx.env().id(),
          Duration.ofMillis(TestProbe.BUDGET_MS));
    } catch (IOException e) {
      throw ConnectorException.badInput("The CA certificate can't be used: " + e.getMessage());
    }
    pools.drop(cfg.id());
  }

  // ── Pools ───────────────────────────────────────────────────────────────────────────────────────────────────

  private SqlPools.Pool pool(ConnectionConfig cfg, ConnectionSettings s, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    Credentials login = Credentials.resolve(cfg, secrets);
    try {
      return pools.pool(cfg, s, login, TestProbe.certificates(ctx));
    } catch (IOException e) {
      throw ConnectorException.badInput("The CA certificate of " + cfg.name() + " can't be used: " + e.getMessage());
    } catch (IllegalStateException e) {
      throw ConnectorException.unavailable(manifest.name() + " is shutting down.", e);
    }
  }

  /** Tests: the pools of this connector. */
  SqlPools pools() {
    return pools;
  }

  @Override
  public void onConnectionChanged(String connectionId) {
    pools.drop(connectionId);
  }

  @Override
  public void onConnectionRemoved(String connectionId) {
    pools.drop(connectionId);
  }

  @Override
  public void close() {
    pools.close();
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  // ── Policy of a call ────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The merged policy {@link #classify} saw for a {@code sql_list_schemas} call, for {@link #execute} of the same
   * call ({@code ExecContext} carries no policy). The core classifies every call — an approved one again, right before
   * it runs — so an entry lives seconds; a time limit and a bounded size keep forgotten ones from piling up.
   */
  static final class CallPolicies {
    static final int MAX = 4_096;
    static final long TTL_NANOS = Duration.ofMinutes(5).toNanos();

    private record Entry(SqlPolicy policy, long at) {}

    private final ConcurrentHashMap<String, Entry> byCall = new ConcurrentHashMap<>();

    void remember(String callId, SqlPolicy policy) {
      if (callId == null) {
        return;
      }
      long now = System.nanoTime();
      if (byCall.size() >= MAX) {
        byCall.values().removeIf(e -> now - e.at() > TTL_NANOS);
        if (byCall.size() >= MAX) {
          byCall.clear();
        }
      }
      byCall.put(callId, new Entry(policy, now));
    }

    SqlPolicy take(String callId) {
      Entry e = callId == null ? null : byCall.remove(callId);
      return e == null || System.nanoTime() - e.at() > TTL_NANOS ? null : e.policy();
    }
  }
}
