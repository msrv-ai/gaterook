package ai.msrv.gaterook.connectors.redshift;

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
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.TestResult.Hint;
import ai.msrv.gaterook.connector.api.TestResult.Severity;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudLocal;
import ai.msrv.gaterook.connectors.support.BuiltIn;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Amazon Redshift (docs/redshift.md) through the Redshift Data API: schemas, tables and SQL reads; {@code
 * redshift_execute} runs one change a person approved.
 *
 * <p>{@link #classify} reads the text by Redshift's tokens ({@link RedshiftSql}) and picks the route without I/O. A
 * read runs as one {@code BatchExecuteStatement} in {@code AUTO_COMMIT} mode, so the Data API wraps nothing around
 * it: {@code SET statement_timeout}, {@code BEGIN READ ONLY}, the statement, {@code ROLLBACK}. Redshift enforces the
 * READ ONLY transaction; a result counts only when Redshift reports that {@code BEGIN READ ONLY} finished. With an IAM
 * role, each call assumes it with a session policy for this warehouse, database and sign-in only
 * ({@link RedshiftSettings#policy}); IAM can't tell a read from a write, so the database user's grants stay the
 * boundary of what SQL may do. A change runs once with {@code ExecuteStatement} and a client token derived from the
 * approval, after {@link #prepare} showed Redshift's plan; it is never retried.
 */
public final class RedshiftConnector implements Connector {

  static final String TYPE = "redshift";
  static final int DEFAULT_LIMIT = 100;
  static final int MAX_PARAMETERS = 50;
  /** Result pages one call reads at most to fill its limit. */
  static final int MAX_PAGES = 5;
  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  private static final Pattern PARAMETER = Pattern.compile("[0-9A-Za-z_]{1,128}");
  private static final Pattern STATEMENT_ID = Pattern.compile("[a-z0-9]{8}(-[a-z0-9]{4}){3}-[a-z0-9]{12}(:\\d{1,2})?");
  private static final Pattern ROWS = Pattern.compile("rows=(\\d{1,19})");
  private static final Pattern VERSION = Pattern.compile("Redshift (\\d+(\\.\\d+)+)");
  private static final Set<String> TERMINAL = Set.of("FINISHED", "FAILED", "ABORTED");
  /** The statements of a read batch around the agent's: the timeout, the read-only transaction, the rollback. */
  private static final int READ_STATEMENT = 2;

  private final CloudCredentials cloud;
  private final RedshiftApi api;
  private final ConnectorManifest manifest;
  /** What the setup helpers read of this computer: AWS profiles and regions. */
  private final CloudLocal local;

  public RedshiftConnector(CloudCredentials cloud) {
    this(cloud, cloud == null ? null : new CloudLocal(cloud.endpoints()));
  }

  /** {@code local}: the profiles and regions the setup helpers read (tests: their own files and environment). */
  RedshiftConnector(CloudCredentials cloud, CloudLocal local) {
    this.cloud = Objects.requireNonNull(cloud, "cloud");
    this.local = Objects.requireNonNull(local, "local");
    this.api = new RedshiftApi(cloud);
    SchemaResources schemas = SchemaResources.of(RedshiftConnector.class, TYPE);
    this.manifest = ConnectorManifest.builder(TYPE, BuiltIn.VERSION, ConnectorManifest.Kind.BUILT_IN)
        .name("Amazon Redshift")
        .icon("database")
        .group("Databases")
        .description("Schemas, tables and SQL queries in read-only transactions on Amazon Redshift. Changes wait "
            + "for approval.")
        .configSchema(schemas.load("config"))
        .tools(RedshiftTools.definitions(schemas))
        // Redshift, Redshift Serverless and Secrets Manager: the setup helpers' listings of the form's names.
        .access(new ConnectorManifest.RequiredAccess(List.of("redshift-data.*.amazonaws.com", "sts.*.amazonaws.com",
            "redshift.*.amazonaws.com", "redshift-serverless.*.amazonaws.com", "secretsmanager.*.amazonaws.com"),
            List.of(), List.of("~/.aws/config", "~/.aws/credentials")))
        .build();
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  // ── Classification ──────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    try {
      RedshiftSettings.of(cfg);
    } catch (ConnectorException e) {
      return Classification.deny(policy.rule("bad-config"), e.getMessage(), "Complete the connection's settings.");
    }
    switch (call.tool()) {
      case RedshiftTools.LIST_SCHEMAS -> {
        return Classification.read();
      }
      case RedshiftTools.LIST_TABLES -> {
        return name(call.string("schema")) == null ? Classification.deny(policy.rule("bad-argument"), "Name a "
            + "schema, in double quotes when its name has capitals.", "Call redshift_list_schemas to see the schemas.")
            : Classification.read();
      }
      case RedshiftTools.DESCRIBE_TABLE -> {
        return table(call.string("table")) == null ? Classification.deny(policy.rule("bad-argument"), "Name a table "
            + "as schema.table, or a table of the public schema.", "Call redshift_list_tables to see the tables.")
            : Classification.read();
      }
      case RedshiftTools.QUERY, RedshiftTools.EXECUTE -> {
        RedshiftSql.Statement st = RedshiftSql.read(call.string("sql"));
        boolean read = RedshiftTools.QUERY.equals(call.tool());
        return switch (st.kind()) {
          case UNREADABLE -> Classification.deny(policy.rule("unparsed"), st.problem() + " The gateway doesn't run "
              + "what it can't read.", "Rewrite it in plain Redshift SQL.");
          case FORBIDDEN -> Classification.deny(policy.rule("forbidden"), st.problem(), null);
          case READ -> {
            if (!read) {
              yield Classification.deny(policy.rule("execute-read"), "This is a read.", "Use redshift_query; it "
                  + "doesn't need approval.");
            }
            String problem = parameters(call.arguments().path("parameters"), st.parameters());
            yield problem == null ? Classification.read() : Classification.deny(policy.rule("bad-argument"), problem,
                null);
          }
          case CHANGE -> {
            if (read) {
              yield Classification.deny(policy.rule("query-writes"), "redshift_query only reads; this is "
                  + st.type() + ".", "Use redshift_execute: a person approves the exact statement.");
            }
            String problem = parameters(call.arguments().path("parameters"), st.parameters());
            if (problem != null) {
              yield Classification.deny(policy.rule("bad-argument"), problem, null);
            }
            ObjectNode facts = F.objectNode().put("statement", st.type());
            if (st.target() != null) {
              facts.put("target", st.target());
            }
            yield Classification.mutation(st.type() + (st.target() == null ? "" : " on " + st.target())
                + " changes data", facts);
          }
        };
      }
      default -> {
        return Classification.deny(policy.rule("unknown-tool"), manifest.name() + " has no tool " + call.tool() + ".",
            null);
      }
    }
  }

  /**
   * Why {@code parameters} aren't the Data API's named text values of this statement, or {@code null}: each
   * {@code {name, value}} once, a value that isn't empty or {@code null} (the Data API can pass neither), and every
   * parameter used as {@code :name} in the statement and every {@code :name} given.
   */
  static String parameters(JsonNode parameters, Set<String> used) {
    Set<String> given = new HashSet<>();
    if (parameters != null && !parameters.isMissingNode() && !parameters.isNull()) {
      if (!parameters.isArray()) {
        return "parameters must be an array of {name, value}.";
      }
      if (parameters.size() > MAX_PARAMETERS) {
        return "At most " + MAX_PARAMETERS + " parameters per statement.";
      }
      for (JsonNode p : parameters) {
        String name = p.path("name").asString("");
        if (!PARAMETER.matcher(name).matches()) {
          return "A parameter needs a name of letters, digits and underscores, such as since (written :since in "
              + "the SQL).";
        }
        if (!given.add(name)) {
          return "Parameter " + name + " is given twice.";
        }
        JsonNode v = p.path("value");
        if (!(v.isString() || v.isNumber() || v.isBoolean())) {
          return "Parameter " + name + " needs a text, number or boolean value; write NULL in the SQL itself.";
        }
        if (v.isString() && v.asString().isEmpty()) {
          return "Parameter " + name + " is empty, which the Data API can't pass; write '' in the SQL itself.";
        }
        if (!used.contains(name)) {
          return "The statement doesn't use :" + name + ", and the Data API refuses a parameter no statement uses.";
        }
      }
    }
    for (String name : used) {
      if (!given.contains(name)) {
        return "The statement uses :" + name + " but parameters has no " + name + ".";
      }
    }
    return null;
  }

  /** The parameters as the Data API takes them: {@code [{name, value}]} with every value as text. */
  static ArrayNode parameterList(JsonNode parameters) {
    ArrayNode out = F.arrayNode();
    if (parameters != null && parameters.isArray()) {
      for (JsonNode p : parameters) {
        JsonNode v = p.path("value");
        out.addObject().put("name", p.path("name").asString()).put("value", v.isString() ? v.asString()
            : v.toString());
      }
    }
    return out;
  }

  /**
   * A schema or table name as Redshift resolves it: in double quotes exactly as written, otherwise folded to lower
   * case, as Redshift folds a name without quotes.
   */
  static String name(String written) {
    if (written == null || written.isBlank() || written.length() > 127 + 2) {
      return null;
    }
    String s = written.strip();
    if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
      s = s.substring(1, s.length() - 1).replace("\"\"", "\"");
    } else if (s.indexOf('"') >= 0) {
      return null;
    } else {
      s = s.toLowerCase(Locale.ROOT);
    }
    return s.isEmpty() || s.length() > 127 || !OutputSanitizer.isClean(s) ? null : s;
  }

  /** {@code schema.table}, {@code "S"."T"} or {@code table} (in {@code public}) as {@code [schema, table]}. */
  static String[] table(String written) {
    if (written == null) {
      return null;
    }
    String s = written.strip();
    int dot = -1;
    boolean quoted = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '"') {
        quoted = !quoted;
      } else if (c == '.' && !quoted) {
        if (dot >= 0) {
          return null;
        }
        dot = i;
      }
    }
    if (quoted) {
      return null;
    }
    String schema = dot < 0 ? "public" : name(s.substring(0, dot));
    String table = name(dot < 0 ? s : s.substring(dot + 1));
    return schema == null || table == null ? null : new String[] {schema, table};
  }

  // ── The exact action ────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    String where = cfg.name() + " · " + Objects.requireNonNullElse(cfg.string("/target/cluster"),
        Objects.requireNonNullElse(cfg.string("/target/workgroup"), "?")) + " · "
        + Objects.requireNonNullElse(cfg.string("/database"), "?");
    JsonNode parameters = call.arguments().path("parameters");
    List<Field> fields = new ArrayList<>();
    if (parameters.isArray() && !parameters.isEmpty()) {
      fields.add(new Field("Parameters", shorten(parameterList(parameters).toString(), 2_000)));
    }
    if (RedshiftTools.EXECUTE.equals(call.tool())) {
      String sql = Objects.requireNonNullElse(call.string("sql"), "");
      RedshiftSql.Statement st = RedshiftSql.read(sql);
      ObjectNode payload = F.objectNode().put("sql", sql);
      if (parameters.isArray() && !parameters.isEmpty()) {
        payload.set("parameters", parameterList(parameters));
      }
      List<String> warnings = new ArrayList<>();
      if (cfg.string("/credentials/roleArn") == null || "none".equals(cfg.string("/credentials/sessionPolicy"))) {
        warnings.add("AWS doesn't limit this change to the connection's warehouse and database: the credentials' "
            + "own permissions apply.");
      }
      boolean truncate = st.type().equals("TRUNCATE");
      return ActionPreview.of(ActionPreview.Kind.SQL, "sql", sql, where, shorten(RedshiftTools.EXECUTE + " on "
              + cfg.name() + ": " + st.type() + (st.target() == null ? "" : " " + st.target()), 120))
          .withFields(fields)
          .withImpact(List.of(new Field("Target", st.target() == null ? "Unknown: not analyzed" : st.target()),
              new Field("Transaction", truncate ? "TRUNCATE commits at once and can't be rolled back"
                  : "One statement; Redshift applies it whole or not at all"),
              new Field("Retried", "No: a request whose answer is lost is reported as unknown")))
          .withWarnings(warnings)
          .withFacets(Map.of("statement", st.type(), "target", Objects.requireNonNullElse(st.target(), "")))
          .withPayload(payload);
    }
    // A read tool an endpoint put in Approval: its arguments are the whole action.
    ObjectNode args = call.arguments().deepCopy();
    if (RedshiftTools.QUERY.equals(call.tool())) {
      return ActionPreview.of(ActionPreview.Kind.SQL, "sql", Objects.requireNonNullElse(call.string("sql"), ""),
              where, shorten(call.tool() + " on " + cfg.name(), 120))
          .withFields(fields)
          .withPayload(F.objectNode().set("arguments", args))
          .withFacets(Map.of("tool", call.tool()))
          .withImpact(List.of(new Field("Reads", "Rows in a READ ONLY transaction, no change")));
    }
    return ActionPreview.of(ActionPreview.Kind.JSON, "json", args.toString(), where,
            shorten(call.tool() + " on " + cfg.name(), 120))
        .withPayload(F.objectNode().set("arguments", args))
        .withFacets(Map.of("tool", call.tool()))
        .withImpact(List.of(new Field("Reads", "Metadata, no change")));
  }

  /**
   * Redshift's plan before a person is asked: {@code EXPLAIN} of the change (Redshift plans {@code INSERT},
   * {@code UPDATE}, {@code DELETE} and {@code CREATE TABLE … AS}) in a transaction that is rolled back, or of a read an
   * endpoint holds for approval in a READ ONLY one. A statement Redshift refuses is never put to a person.
   */
  @Override
  public PreparedAction prepare(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    ActionPreview preview = describeAction(call, cfg);
    boolean change = RedshiftTools.EXECUTE.equals(call.tool());
    boolean query = RedshiftTools.QUERY.equals(call.tool()) && !call.arguments().path("cursor").isString();
    if (!change && !query) {
      return PreparedAction.approval(preview, null);
    }
    String sql = Objects.requireNonNullElse(call.string("sql"), "");
    RedshiftSql.Statement st = RedshiftSql.read(sql);
    boolean explainable = change ? st.kind() == RedshiftSql.Kind.CHANGE && st.explainable()
        : st.kind() == RedshiftSql.Kind.READ && (st.type().equals("SELECT") || st.type().equals("WITH"));
    List<Field> impact = new ArrayList<>();
    if (explainable) {
      RedshiftSettings s = RedshiftSettings.of(cfg);
      List<String> plan;
      try {
        plan = explain(new RedshiftApi.Call(cfg, s, secrets, ctx), sql, call.arguments().path("parameters"), !change);
      } catch (ConnectorException e) {
        if (e.kind() == ConnectorException.Kind.BAD_INPUT || e.kind() == ConnectorException.Kind.DENIED) {
          return PreparedAction.reject(e.getMessage());
        }
        throw e;
      }
      Matcher rows = plan.isEmpty() ? null : ROWS.matcher(plan.getFirst());
      if (rows != null && rows.find()) {
        impact.add(new Field("Rows (estimate)", String.format(Locale.US, "%,d", Long.parseLong(rows.group(1)))
            + " · Redshift's plan, not a count"));
      }
      impact.add(new Field("Plan", shorten(String.join("\n", plan.subList(0, Math.min(plan.size(), 30))), 2_000)));
    } else if (change) {
      impact.add(new Field("Plan", "Redshift plans only SELECT, INSERT, UPDATE, DELETE and CREATE TABLE AS: this "
          + "statement is shown as written"));
    }
    impact.addAll(preview.impact());
    return PreparedAction.approval(preview.withImpact(impact), null);
  }

  /** The lines of Redshift's {@code EXPLAIN}, in a transaction that is rolled back (READ ONLY for a read). */
  private List<String> explain(RedshiftApi.Call c, String sql, JsonNode parameters, boolean readOnly)
      throws ConnectorException {
    JsonNode sub = batch(c, List.of(timeout(c.ctx()), readOnly ? "BEGIN READ ONLY" : "BEGIN", "EXPLAIN " + sql,
        "ROLLBACK"), parameterList(parameters), readOnly);
    List<String> lines = new ArrayList<>();
    String token = null;
    for (int page = 0; page < 2; page++) {
      ObjectNode req = F.objectNode().put("Id", sub.path("Id").asString());
      if (token != null) {
        req.put("NextToken", token);
      }
      JsonNode r = api.call(c, "GetStatementResult", req, c.settings().readPolicy(), false);
      for (JsonNode record : r.path("Records")) {
        lines.add(OutputSanitizer.sanitize(record.path(0).path("stringValue").asString("")));
      }
      token = r.path("NextToken").asString(null);
      if (token == null) {
        break;
      }
    }
    return lines;
  }

  private static String shorten(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }

  // ── Execution ───────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    RedshiftSettings s = RedshiftSettings.of(cfg);
    RedshiftApi.Call c = new RedshiftApi.Call(cfg, s, secrets, ctx);
    return switch (call.tool()) {
      case RedshiftTools.LIST_SCHEMAS -> listSchemas(c, arguments(call, ctx));
      case RedshiftTools.LIST_TABLES -> listTables(c, arguments(call, ctx));
      case RedshiftTools.DESCRIBE_TABLE -> describeTable(c, arguments(call, ctx));
      case RedshiftTools.QUERY -> query(c, arguments(call, ctx));
      case RedshiftTools.EXECUTE -> change(c);
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

  private static int limit(JsonNode args, int max, int dflt) {
    return Math.max(1, Math.min(args.path("limit").asInt(dflt), max));
  }

  private ToolResult listSchemas(RedshiftApi.Call c, JsonNode args) throws ConnectorException {
    String pattern = args.path("pattern").asString(null);
    int limit = limit(args, 1_000, 100);
    String hash = Cursors.hash(F.objectNode().put("pattern", Objects.requireNonNullElse(pattern, "")));
    ObjectNode req = c.settings().target().put("MaxResults", limit);
    if (pattern != null) {
      req.put("SchemaPattern", pattern);
    }
    if (args.path("cursor").isString()) {
      req.put("NextToken", Cursors.open(c.ctx(), RedshiftTools.LIST_SCHEMAS, args.get("cursor").asString(),
          c.cfg().id(), hash).asString(""));
    }
    JsonNode r = api.call(c, "ListSchemas", req, c.settings().readPolicy(), false);
    ObjectNode out = F.objectNode().put("database", c.settings().database());
    ArrayNode schemas = out.putArray("schemas");
    List<String> names = new ArrayList<>();
    for (JsonNode n : r.path("Schemas")) {
      String name = OutputSanitizer.sanitize(n.asString(""));
      schemas.add(name);
      names.add(name);
    }
    String next = r.path("NextToken").asString(null);
    if (next != null) {
      out.put("nextCursor", Cursors.seal(c.ctx(), RedshiftTools.LIST_SCHEMAS, c.cfg().id(), hash, F.stringNode(next)));
    }
    return ToolResult.json(out, names.isEmpty() ? "No schemas in " + c.settings().database() + "."
        : names.size() + (names.size() == 1 ? " schema: " : " schemas: ") + String.join(", ", names)
            + (next == null ? "" : "\nMore schemas: pass nextCursor."));
  }

  private ToolResult listTables(RedshiftApi.Call c, JsonNode args) throws ConnectorException {
    String schema = name(args.path("schema").asString(null));
    if (schema == null) {
      throw ConnectorException.badInput("Name a schema, in double quotes when its name has capitals.");
    }
    String pattern = args.path("pattern").asString(null);
    int limit = limit(args, 1_000, 100);
    ObjectNode request = F.objectNode().put("schema", schema).put("pattern", Objects.requireNonNullElse(pattern, ""));
    String hash = Cursors.hash(request);
    // A LIKE pattern: _ in a schema's name matches any character, so the answer is narrowed to the schema itself.
    ObjectNode req = c.settings().target().put("SchemaPattern", schema).put("MaxResults", limit);
    if (pattern != null) {
      req.put("TablePattern", pattern);
    }
    if (args.path("cursor").isString()) {
      req.put("NextToken", Cursors.open(c.ctx(), RedshiftTools.LIST_TABLES, args.get("cursor").asString(),
          c.cfg().id(), hash).asString(""));
    }
    JsonNode r = api.call(c, "ListTables", req, c.settings().readPolicy(), false);
    ObjectNode out = F.objectNode().put("schema", schema);
    ArrayNode tables = out.putArray("tables");
    List<String> names = new ArrayList<>();
    for (JsonNode t : r.path("Tables")) {
      if (!schema.equals(t.path("schema").asString(""))) {
        continue;
      }
      String name = OutputSanitizer.sanitize(t.path("name").asString(""));
      String kind = t.path("type").asString("TABLE").toLowerCase(Locale.ROOT);
      tables.addObject().put("table", name).put("kind", kind);
      names.add(name + ("table".equals(kind) ? "" : " (" + kind + ")"));
    }
    String next = r.path("NextToken").asString(null);
    if (next != null) {
      out.put("nextCursor", Cursors.seal(c.ctx(), RedshiftTools.LIST_TABLES, c.cfg().id(), hash, F.stringNode(next)));
    }
    return ToolResult.json(out, names.isEmpty() ? "No tables in " + schema + "." : schema + ": "
        + String.join(", ", names) + (next == null ? "" : "\nMore tables: pass nextCursor."));
  }

  private ToolResult describeTable(RedshiftApi.Call c, JsonNode args) throws ConnectorException {
    String[] t = table(args.path("table").asString(null));
    if (t == null) {
      throw ConnectorException.badInput("Name a table as schema.table, or a table of the public schema.");
    }
    ObjectNode out = F.objectNode().put("table", t[0] + "." + t[1]);
    ArrayNode columns = out.putArray("columns");
    List<String> text = new ArrayList<>();
    String token = null;
    for (int page = 0; page < MAX_PAGES; page++) {
      ObjectNode req = c.settings().target().put("Schema", t[0]).put("Table", t[1]).put("MaxResults", 1_000);
      if (token != null) {
        req.put("NextToken", token);
      }
      JsonNode r = api.call(c, "DescribeTable", req, c.settings().readPolicy(), false);
      for (JsonNode col : r.path("ColumnList")) {
        // Schema and Table are matched as patterns: _ matches any character.
        if (!t[0].equals(col.path("schemaName").asString(t[0])) || !t[1].equals(col.path("tableName")
            .asString(t[1]))) {
          continue;
        }
        String type = type(col);
        ObjectNode e = columns.addObject().put("name", OutputSanitizer.sanitize(col.path("name").asString("")))
            .put("type", type).put("nullable", col.path("nullable").asInt(1) != 0);
        if (col.path("columnDefault").isString() && !col.get("columnDefault").asString().isBlank()) {
          e.put("default", Records.text(OutputSanitizer.sanitize(col.get("columnDefault").asString()), 500)
              .asString());
        }
        text.add(e.path("name").asString() + " " + type);
      }
      token = r.path("NextToken").asString(null);
      if (token == null || c.ctx().remaining().compareTo(Duration.ofSeconds(1)) < 0) {
        break;
      }
    }
    if (columns.isEmpty()) {
      throw ConnectorException.badInput("No table " + t[0] + "." + t[1] + " the database user can see. Call "
          + "redshift_list_tables to see the tables.");
    }
    return ToolResult.json(out, t[0] + "." + t[1] + ": " + String.join(", ", text));
  }

  /** {@code varchar(256)}, {@code numeric(12,2)}, {@code int4}: Redshift's type with its size. */
  private static String type(JsonNode col) {
    String name = col.path("typeName").asString("");
    int length = col.path("length").asInt(0);
    int precision = col.path("precision").asInt(0);
    return switch (name) {
      case "varchar", "bpchar", "char", "character varying", "character", "varbyte" -> length > 0
          ? name + "(" + length + ")" : name;
      case "numeric", "decimal" -> precision > 0 ? name + "(" + precision + "," + col.path("scale").asInt(0) + ")"
          : name;
      default -> name;
    };
  }

  /** {@code SET statement_timeout}: the policy's statement timeout, but never past the call's deadline. */
  private static String timeout(ExecContext ctx) {
    long ms = Math.min(ctx.limits().statementTimeout().toMillis(), ctx.remaining().toMillis() - 1_000);
    return "SET statement_timeout TO " + Math.max(1_000, ms);
  }

  private ToolResult query(RedshiftApi.Call c, JsonNode args) throws ConnectorException {
    ExecContext ctx = c.ctx();
    String sql = args.path("sql").asString("");
    RedshiftSql.Statement st = RedshiftSql.read(sql);
    if (st.kind() != RedshiftSql.Kind.READ) {
      throw ConnectorException.denied("redshift_query only runs one statement the gateway reads as a read.");
    }
    String problem = parameters(args.path("parameters"), st.parameters());
    if (problem != null) {
      throw ConnectorException.badInput(problem);
    }
    int limit = limit(args, ctx.limits().maxRows(), DEFAULT_LIMIT);
    ObjectNode request = F.objectNode().put("sql", sql);
    request.set("parameters", parameterList(args.path("parameters")));
    String hash = Cursors.hash(request);
    if (args.path("cursor").isString()) {
      JsonNode at = Cursors.open(ctx, RedshiftTools.QUERY, args.get("cursor").asString(), c.cfg().id(), hash);
      String id = at.path("s").asString("");
      if (!STATEMENT_ID.matcher(id).matches()) {
        throw ConnectorException.badInput("The cursor names no statement. Start again without cursor.");
      }
      return page(c, id, at.path("t").asString(null), Math.max(0, at.path("o").asInt(0)), limit, hash,
          F.objectNode().put("statement", id));
    }
    JsonNode sub = batch(c, List.of(timeout(ctx), "BEGIN READ ONLY", sql, "ROLLBACK"),
        parameterList(args.path("parameters")), true);
    String id = sub.path("Id").asString("");
    ObjectNode facts = F.objectNode().put("statement", id);
    if (sub.path("ResultRows").asLong(-1) >= 0) {
      facts.put("resultRows", sub.get("ResultRows").asLong());
    }
    if (sub.path("ResultSize").asLong(-1) >= 0) {
      facts.put("resultBytes", sub.get("ResultSize").asLong());
    }
    if (sub.path("RedshiftQueryId").isIntegralNumber()) {
      facts.put("redshiftQueryId", sub.get("RedshiftQueryId").asLong());
    }
    if (!sub.path("HasResultSet").asBoolean(true)) {
      return ToolResult.json(facts, "The statement returned no rows.");
    }
    return page(c, id, null, 0, limit, hash, facts);
  }

  /**
   * Runs {@code sqls} as one batch in {@code AUTO_COMMIT} mode, the Data API wrapping no transaction around them,
   * waits for it, and returns the description of the agent's statement ({@link #READ_STATEMENT}) once Redshift
   * reports that the statement before it ({@code BEGIN READ ONLY} or {@code BEGIN}) finished and the agent's
   * statement finished too. A batch still running at the call's deadline is cancelled.
   */
  private JsonNode batch(RedshiftApi.Call c, List<String> sqls, ArrayNode parameters, boolean read)
      throws ConnectorException {
    ExecContext ctx = c.ctx();
    ObjectNode body = c.settings().target();
    ArrayNode list = body.putArray("Sqls");
    sqls.forEach(list::add);
    body.put("ExecutionMode", "AUTO_COMMIT");
    body.put("StatementName", "gaterook " + ctx.callId());
    if (!parameters.isEmpty()) {
      body.set("Parameters", parameters);
    }
    int wait = waitSeconds(ctx);
    if (wait > 0) {
      body.put("WaitTimeSeconds", wait);
    }
    JsonNode r = api.call(c, "BatchExecuteStatement", body, c.settings().readPolicy(), false);
    String id = r.path("Id").asString("");
    if (!STATEMENT_ID.matcher(id).matches()) {
      throw ConnectorException.resourceError("Redshift answered the batch without a statement id.", null);
    }
    JsonNode d = await(c, id, true);
    JsonNode subs = d.path("SubStatements");
    if (subs.size() != sqls.size()) {
      throw ConnectorException.resourceError("Redshift's answer about statement " + id + " doesn't name its "
          + sqls.size() + " parts" + (d.path("Error").isString() ? ": " + said(d.get("Error").asString()) : "")
          + ".", null);
    }
    JsonNode begin = subs.get(READ_STATEMENT - 1);
    if (!"FINISHED".equals(begin.path("Status").asString(""))) {
      throw ConnectorException.resourceError("Redshift didn't open the " + (read ? "read-only " : "")
          + "transaction (" + firstError(subs, d, begin.path("Status").asString("not run")) + "), so GateRook used "
          + "no result of statement " + id + ". Try again.", null);
    }
    JsonNode sub = subs.get(READ_STATEMENT);
    String status = sub.path("Status").asString("");
    if ("FINISHED".equals(status)) {
      return sub;
    }
    String error = said(sub.path("Error").asString(status));
    if (error.toLowerCase(Locale.ROOT).contains("statement timeout")) {
      throw ConnectorException.timeout("Redshift stopped the statement at the statement timeout of "
          + ctx.limits().statementTimeout().toSeconds() + " s: " + error + ". Narrow it, or ask a person to raise the "
          + "timeout.");
    }
    if (error.toLowerCase(Locale.ROOT).contains("read-only transaction")) {
      throw ConnectorException.denied("Redshift refused to write in the read-only transaction: " + error + ".");
    }
    if ("ABORTED".equals(status)) {
      throw ConnectorException.timeout("Redshift stopped statement " + id + ": " + error + ".");
    }
    throw ConnectorException.badInput("Redshift refused the statement: " + error + ".");
  }

  /** The first error Redshift reports for a batch's statements, else the batch's own, else {@code otherwise}. */
  private static String firstError(JsonNode subs, JsonNode batch, String otherwise) {
    for (JsonNode sub : subs) {
      if (sub.path("Error").isString() && !sub.get("Error").asString().isBlank()) {
        return said(sub.get("Error").asString());
      }
    }
    return batch.path("Error").isString() ? said(batch.get("Error").asString()) : otherwise;
  }

  /** How long one request may wait for a statement: what the call has left, at most 20 s, leaving time to answer. */
  private static int waitSeconds(ExecContext ctx) {
    long left = (ctx.remaining().toMillis() - 2_000) / 1_000;
    return (int) Math.max(0, Math.min(20, left));
  }

  /**
   * {@code DescribeStatement} until the statement ends or the call's deadline; long polls with
   * {@code WaitTimeSeconds} while Redshift takes them. At the deadline a read is cancelled; a change is left running,
   * and its outcome is reported as unknown by the caller.
   */
  private JsonNode await(RedshiftApi.Call c, String id, boolean cancelAtDeadline) throws ConnectorException {
    ExecContext ctx = c.ctx();
    boolean longPoll = true;
    long pause = 200;
    String policy = cancelAtDeadline ? c.settings().readPolicy() : c.settings().changePolicy();
    while (ctx.remaining().compareTo(Duration.ofMillis(1_500)) > 0) {
      ObjectNode req = F.objectNode().put("Id", id);
      int wait = longPoll ? waitSeconds(ctx) : 0;
      if (wait > 0) {
        req.put("WaitTimeSeconds", wait);
      }
      JsonNode d;
      try {
        d = api.call(c, "DescribeStatement", req, policy, false);
      } catch (RedshiftApi.ApiError e) {
        if (e.type().equals("ActiveWaitingRequestsExceededException") && longPoll) {
          longPoll = false;
          continue;
        }
        throw e;
      }
      if (TERMINAL.contains(d.path("Status").asString(""))) {
        return d;
      }
      if (wait == 0) {
        try {
          Thread.sleep(Math.min(pause, Math.max(1, ctx.remaining().toMillis() - 1_500)));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          cancel(c, id, policy);
          throw ConnectorException.timeout("The statement was cancelled with the call.");
        }
        pause = Math.min(1_000, pause * 2);
      }
    }
    if (cancelAtDeadline) {
      cancel(c, id, policy);
      throw ConnectorException.timeout("Redshift didn't finish within the call's time, so GateRook cancelled "
          + "statement " + id + ". Narrow the query, or filter on the sort key.");
    }
    return null;
  }

  private void cancel(RedshiftApi.Call c, String id, String policy) {
    try {
      api.call(c, "CancelStatement", F.objectNode().put("Id", id), policy, false);
    } catch (ConnectorException e) {
      // The statement stops at its own timeout; the call ends either way.
    }
  }

  /**
   * One page of rows within the row limit and the response budget, from the result page {@code token} (the first one
   * when {@code null}) on, skipping {@code offset} rows of it. The cursor names the result page and the row the next
   * page starts at, so a page cut at the response size goes on exactly where it stopped.
   */
  private ToolResult page(RedshiftApi.Call c, String id, String token, int offset, int limit, String hash,
      ObjectNode facts) throws ConnectorException {
    ExecContext ctx = c.ctx();
    long budget = Math.max(4_096, ctx.limits().maxResponseBytes() * 3 / 4 - 2_048);
    long used = 0;
    ArrayNode rows = F.arrayNode();
    List<ToolResult.Column> columns = null;
    JsonNode metadata = null;
    String truncated = null;
    String pageToken = token;
    int skip = offset;
    ObjectNode position = null;
    for (int pages = 1; ; pages++) {
      ObjectNode req = F.objectNode().put("Id", id);
      if (pageToken != null) {
        req.put("NextToken", pageToken);
      }
      JsonNode r = api.call(c, "GetStatementResult", req, c.settings().readPolicy(), false);
      if (metadata == null) {
        metadata = r.path("ColumnMetadata");
        columns = Records.columns(metadata);
        if (r.has("TotalNumRows")) {
          facts.put("totalRows", r.get("TotalNumRows").asLong());
        }
      }
      JsonNode records = r.path("Records");
      int i = skip;
      for (; i < records.size(); i++) {
        if (rows.size() >= limit) {
          break;
        }
        ArrayNode cells = Records.row(records.get(i), metadata, ctx.limits().maxCellChars());
        used += cells.toString().length();
        if (used > budget && !rows.isEmpty()) {
          truncated = "size_limit";
          break;
        }
        rows.add(cells);
      }
      String next = r.path("NextToken").asString(null);
      if (i < records.size()) {
        position = F.objectNode().put("s", id).put("o", i);
        if (pageToken != null) {
          position.put("t", pageToken);
        }
        break;
      }
      if (next == null) {
        break;
      }
      if (rows.size() >= limit || pages >= MAX_PAGES || ctx.remaining().compareTo(Duration.ofSeconds(1)) < 0) {
        position = F.objectNode().put("s", id).put("o", 0).put("t", next);
        break;
      }
      pageToken = next;
      skip = 0;
    }
    String nextCursor = position == null ? null : Cursors.seal(ctx, RedshiftTools.QUERY, c.cfg().id(), hash,
        position);
    ToolResult result = ToolResult.table(new ToolResult.Table(List.copyOf(columns), rows, nextCursor), facts);
    return truncated == null ? result : result.truncated(truncated);
  }

  private ToolResult change(RedshiftApi.Call c) throws ConnectorException {
    ExecContext ctx = c.ctx();
    if (!ctx.approved()) {
      throw ConnectorException.denied("Not approved: redshift_execute changes data, so a person approves it first.");
    }
    JsonNode payload = ctx.approvedPayload().orElseThrow(() -> ConnectorException.denied("The approved action "
        + "carries no statement."));
    String sql = payload.path("sql").asString("");
    RedshiftSql.Statement st = RedshiftSql.read(sql);
    if (st.kind() != RedshiftSql.Kind.CHANGE) {
      throw ConnectorException.denied("redshift_execute runs one analyzed change.");
    }
    String problem = parameters(payload.path("parameters"), st.parameters());
    if (problem != null) {
      throw ConnectorException.badInput(problem);
    }
    ObjectNode body = c.settings().target().put("Sql", sql).put("ClientToken", clientToken(ctx))
        .put("StatementName", "gaterook " + ctx.callId());
    ArrayNode parameters = parameterList(payload.path("parameters"));
    if (!parameters.isEmpty()) {
      body.set("Parameters", parameters);
    }
    int wait = waitSeconds(ctx);
    if (wait > 0) {
      body.put("WaitTimeSeconds", wait);
    }
    JsonNode r = api.call(c, "ExecuteStatement", body, c.settings().changePolicy(), true);
    String id = r.path("Id").asString("");
    JsonNode d;
    try {
      d = STATEMENT_ID.matcher(id).matches() ? await(c, id, false) : null;
    } catch (ConnectorException e) {
      throw ConnectorException.outcomeUnknown("GateRook lost track of the change, statement " + id + ", so whether "
          + "it ran is not known. Check the table before retrying.", e);
    }
    if (d == null) {
      throw ConnectorException.outcomeUnknown("Redshift is still running the change as statement " + id + ". Check "
          + "the table before retrying.", null);
    }
    String status = d.path("Status").asString("");
    if (!"FINISHED".equals(status)) {
      String error = said(d.path("Error").asString(status));
      throw ConnectorException.resourceError("Redshift " + ("ABORTED".equals(status) ? "stopped" : "refused")
          + " the change (statement " + id + "): " + error + ". A statement that fails changes nothing.", null);
    }
    long affected = d.path("ResultRows").asLong(-1);
    ObjectNode out = F.objectNode().put("committed", true).put("statement", st.type()).put("statementId", id);
    if (st.target() != null) {
      out.put("target", st.target());
    }
    if (affected >= 0) {
      out.put("rowsAffected", affected);
    }
    String text = affected < 0 ? st.type() + " done." : affected + (affected == 1 ? " row" : " rows")
        + " affected by " + st.type() + ".";
    return ToolResult.json(out, text);
  }

  /** The Data API's idempotency token for an approved change: a UUID of the approval's id (kept 8 hours). */
  static String clientToken(ExecContext ctx) {
    String id = ctx.approvalId().orElse(ctx.callId());
    return UUID.nameUUIDFromBytes(("gaterook-redshift|" + id).getBytes(StandardCharsets.UTF_8)).toString();
  }

  /** Redshift's text, sanitized and to its first line. */
  private static String said(String s) {
    return OutputSanitizer.sanitize(RedshiftApi.firstLine(s));
  }

  // ── Test connection, health, describe ───────────────────────────────────────────────────────────────────────

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    RedshiftSettings s;
    try {
      s = RedshiftSettings.of(cfg);
    } catch (ConnectorException e) {
      return TestResult.failure("redshift.settings", e.getMessage(), null);
    }
    RedshiftApi.Call c = new RedshiftApi.Call(cfg, s, secrets, ctx);
    List<TestResult.Check> checks = new ArrayList<>();
    long started = System.nanoTime();
    CloudCredentials.CallerIdentity identity;
    try {
      AwsCredentialsProvider provider = CloudCredentials.tainting(cloud.aws(new CloudCredentials.Owner(cfg.id(),
          cfg.version(), cfg.name()), s.credentials(), s.readPolicy(), secrets), ctx::taint);
      identity = cloud.callerIdentity(provider, s.region());
    } catch (ConnectorException e) {
      return TestResult.failure("redshift.credentials", e.getMessage(), null);
    } catch (SdkException e) {
      return TestResult.failure("redshift.credentials", "The AWS credentials couldn't be loaded: "
          + RedshiftApi.firstLine(e.getMessage()) + ".", null);
    }
    checks.add(new TestResult.Check("identity", true, ms(started), identity.arn()));
    long listed = System.nanoTime();
    int schemas = 0;
    try {
      JsonNode r = api.call(c, "ListSchemas", s.target().put("MaxResults", 100), s.readPolicy(), false);
      schemas = r.path("Schemas").size();
      checks.add(new TestResult.Check("schemas", true, ms(listed), schemas + (r.has("NextToken") ? "+" : "")
          + (schemas == 1 ? " schema" : " schemas") + " in " + s.database()));
    } catch (ConnectorException e) {
      return TestResult.failure(e.kind() == ConnectorException.Kind.RESOURCE_UNAVAILABLE ? "redshift.unreachable"
          : "redshift.schemas", e.getMessage(), null).withChecks(checks);
    }
    long latency = ms(listed);
    long read = System.nanoTime();
    String dbUser;
    boolean superuser;
    String version;
    try {
      JsonNode sub = batch(c, List.of(timeout(ctx), "BEGIN READ ONLY", "SELECT current_user AS db_user, (SELECT "
          + "usesuper FROM pg_user WHERE usename = current_user) AS superuser, version() AS version", "ROLLBACK"),
          F.arrayNode(), true);
      JsonNode row = api.call(c, "GetStatementResult", F.objectNode().put("Id", sub.path("Id").asString()),
          s.readPolicy(), false).path("Records").path(0);
      dbUser = OutputSanitizer.sanitize(row.path(0).path("stringValue").asString("?"));
      superuser = row.path(1).path("booleanValue").asBoolean(false);
      Matcher m = VERSION.matcher(row.path(2).path("stringValue").asString(""));
      version = m.find() ? m.group(1) : null;
      checks.add(new TestResult.Check("read-only", true, ms(read), "Ran a read in a READ ONLY transaction as "
          + dbUser));
    } catch (ConnectorException e) {
      return TestResult.failure("redshift.read", "The credentials can list schemas but not run reads: "
          + e.getMessage(), null).withChecks(checks);
    }
    TestResult.Permissions permissions = new TestResult.Permissions(TestResult.Level.UNKNOWN,
        "SQL reads in READ ONLY transactions as " + dbUser, null, List.of(), F.objectNode()
        .put("sessionPolicy", s.readPolicy() == null ? "none" : "generated per call").put("databaseUser", dbUser)
        .put("superuser", superuser));
    TestResult result = TestResult.success(latency, "Amazon Redshift" + (version == null ? "" : " " + version)
            + " · " + s.warehouse(), identity.arn() + " · database user " + dbUser, permissions)
        .withChecks(checks)
        .withMetadata(F.objectNode().put("serverVersion", "Amazon Redshift" + (version == null ? "" : " " + version))
            .put("account", identity.account()).put("region", s.region()).put("databaseUser", dbUser));
    for (Hint h : hints(cfg.env(), s, dbUser, superuser, schemas, cloud.profileWarnings(s.credentials()))) {
      result = result.withHint(h);
    }
    return result;
  }

  /**
   * Profiles and regions of this computer; signed in, the clusters, Serverless workgroups, databases and secrets
   * ({@link RedshiftSuggestions}).
   */
  @Override
  public Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope) {
    return RedshiftSuggestions.suggest(cfg, secrets, ctx, scope, cloud, local, api);
  }

  /** {@code profileWarnings}: what cloud-auth says about the AWS profile, such as that it runs a command. */
  static List<Hint> hints(Env env, RedshiftSettings s, String dbUser, boolean superuser, int schemas,
      List<String> profileWarnings) {
    List<Hint> out = new ArrayList<>();
    if (s.credentials().roleArn() == null) {
      out.add(new Hint(env == Env.DEV ? Severity.INFO : Severity.WARNING, "redshift.no-role", "/credentials/roleArn",
          "Without a role, each call has every permission of the credentials. With a role, AWS limits each call to "
              + "this warehouse, database and sign-in."));
    } else if (!s.generatedPolicy()) {
      out.add(new Hint(Severity.WARNING, "redshift.no-session-policy", "/credentials/sessionPolicy",
          "Calls use the role's permissions as they are: AWS doesn't keep them to this warehouse and database."));
    } else if (s.kind() == RedshiftSettings.Kind.SERVERLESS && !s.workgroupArn()) {
      out.add(new Hint(Severity.INFO, "redshift.workgroup-arn", "/target/workgroup", "Enter the workgroup's ARN: "
          + "the session policy then names this workgroup only, not every workgroup in " + s.region() + "."));
    }
    for (String w : profileWarnings) {
      out.add(new Hint(Severity.WARNING, "redshift.profile", "/credentials/profile", w));
    }
    if (superuser) {
      out.add(new Hint(env == Env.DEV ? Severity.WARNING : Severity.DANGER, "redshift.superuser",
          "/databaseAuth/method", dbUser + " is a superuser: reads stay read only, but they may read every table, "
              + "and an approved change may do anything. Use a user with SELECT on the schemas agents read."));
    }
    out.add(new Hint(Severity.INFO, "redshift.read-only-boundary", null, "Redshift runs each read in a READ ONLY "
        + "transaction and GateRook sends it only reads. The database user's grants decide what a read may see."));
    if (schemas == 0) {
      out.add(new Hint(Severity.WARNING, "redshift.no-schemas", "/database", "The database user sees no schema in "
          + s.database() + "."));
    }
    return out;
  }

  /**
   * The Data API's {@code ListStatements}: the credentials and the service, without running anything on the
   * warehouse, so the periodic check never wakes (and bills) a Serverless workgroup or a paused cluster.
   */
  @Override
  public HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    try {
      RedshiftSettings s = RedshiftSettings.of(cfg);
      long started = System.nanoTime();
      api.call(new RedshiftApi.Call(cfg, s, secrets, ctx), "ListStatements", F.objectNode().put("MaxResults", 1)
          .put("RoleLevel", false), s.readPolicy(), false);
      return HealthResult.healthy(ms(started));
    } catch (ConnectorException e) {
      return HealthResult.unreachable(e.getMessage());
    }
  }

  @Override
  public Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object) {
    ObjectNode out = F.objectNode().put("region", cfg.string("/region"));
    try {
      RedshiftSettings s = RedshiftSettings.of(cfg);
      out.put("warehouse", s.warehouse()).put("database", s.database()).put("signIn", s.signIn());
    } catch (ConnectorException e) {
      out.put("settings", e.getMessage());
    }
    out.put("reads", "One SELECT, WITH, SHOW or EXPLAIN per redshift_query, in a READ ONLY transaction; pass values "
        + "as :name parameters");
    out.put("changes", "One INSERT, UPDATE, DELETE, MERGE or DDL statement per redshift_execute, after approval");
    if (object != null && !object.isBlank()) {
      out.put("table", "Call redshift_describe_table with table " + object + ".");
    }
    return Optional.of(out);
  }

  @Override
  public void onConnectionChanged(String connectionId) {
    cloud.evict(connectionId);
  }

  @Override
  public void onConnectionRemoved(String connectionId) {
    cloud.evict(connectionId);
  }

  private static long ms(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
