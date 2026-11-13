package com.gaterook.connectors.databricks;

import com.gaterook.connector.api.ActionPreview;
import com.gaterook.connector.api.ActionPreview.Field;
import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Connector;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ConnectorManifest;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.HealthResult;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.TestResult.Hint;
import com.gaterook.connector.api.TestResult.Severity;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolResult;
import com.gaterook.connectors.support.BuiltIn;
import com.gaterook.connectors.support.OutputSanitizer;
import com.gaterook.connectors.support.SchemaResources;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Databricks SQL (docs/databricks.md): Unity Catalog catalogs, schemas and tables, and Databricks SQL reads on a SQL
 * warehouse through the SQL Statement Execution API 2.0; {@code databricks_execute} runs one change a person approved.
 *
 * <p>Databricks SQL has no read-only session: Unity Catalog's grants are the boundary, so the identity should hold
 * {@code USE CATALOG}, {@code USE SCHEMA} and {@code SELECT} only. {@link #classify} reads each statement by Spark's
 * tokens ({@link SparkSql}) without I/O, sends {@code databricks_query} only reads, refuses access control, files,
 * data movement, session settings and functions that reach outside the warehouse's tables, and checks the catalogs
 * and schemas the statement names against the connection's lists. A read waits for the warehouse until the call's
 * deadline, with Databricks cancelling it there; a change runs once, is never retried and is never cancelled.
 */
public final class DatabricksConnector implements Connector {

  static final String TYPE = "databricks";
  static final int DEFAULT_LIMIT = 100;
  static final int MAX_PARAMETERS = 50;
  /** The most bytes a read's result may hold ({@code byte_limit}); INLINE results end at 25 MiB. */
  static final long BYTE_LIMIT = 8L * 1024 * 1024;
  /** A read's result holds at most this many pages of the endpoint's row limit ({@code row_limit}). */
  static final int PAGES_PER_STATEMENT = 10;
  /** Result chunks one call reads at most to fill its limit. */
  static final int MAX_CHUNKS = 5;
  /** Listing pages one call reads at most while Unity Catalog answers empty pages. */
  static final int MAX_LIST_PAGES = 5;
  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  private static final Pattern STATEMENT_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
  private static final Pattern PARAMETER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
  private static final Pattern PARAMETER_TYPE = Pattern.compile("STRING|BOOLEAN|TINYINT|BYTE|SMALLINT|SHORT|INT|"
      + "INTEGER|BIGINT|LONG|FLOAT|REAL|DOUBLE|DATE|TIMESTAMP|TIMESTAMP_NTZ|BINARY|"
      + "DECIMAL(\\([0-9]{1,2}(, ?[0-9]{1,2})?\\))?");
  private static final Pattern PART = Pattern.compile("[A-Za-z0-9_-]{1,255}");
  private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELED", "CLOSED");

  private final DatabricksApi api;
  private final ConnectorManifest manifest;
  private volatile DatabricksSuggestions.Machine machine = DatabricksSuggestions.Machine.system();

  public DatabricksConnector() {
    this(Clock.systemUTC(), defaultTls());
  }

  /** A connector whose TLS trusts {@code ssl}'s roots: the JDK's in production, a test CA in tests. */
  DatabricksConnector(Clock clock, SSLContext ssl) {
    this.api = new DatabricksApi(ssl, clock);
    SchemaResources schemas = SchemaResources.of(DatabricksConnector.class, TYPE);
    this.manifest = ConnectorManifest.builder(TYPE, BuiltIn.VERSION, ConnectorManifest.Kind.BUILT_IN)
        .name("Databricks SQL")
        .icon("database")
        .group("Databases")
        .description("Unity Catalog tables and Databricks SQL queries on a SQL warehouse. Changes wait for "
            + "approval.")
        .configSchema(schemas.load("config"))
        .tools(DatabricksTools.definitions(schemas))
        .access(new ConnectorManifest.RequiredAccess(List.of("*.cloud.databricks.com", "*.azuredatabricks.net",
            "*.gcp.databricks.com"), List.of(), List.of()))
        .build();
  }

  private static SSLContext defaultTls() {
    try {
      return SSLContext.getDefault();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("TLS is not available", e);
    }
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  // ── Classification ──────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    DatabricksSettings s;
    try {
      s = DatabricksSettings.of(cfg);
    } catch (ConnectorException e) {
      return Classification.deny(policy.rule("bad-config"), e.getMessage(), "Complete the connection's settings.");
    }
    String env = policy.env() == null ? "this environment" : policy.env().id();
    switch (call.tool()) {
      case DatabricksTools.LIST_CATALOGS -> {
        return Classification.read();
      }
      case DatabricksTools.LIST_SCHEMAS -> {
        String catalog = lowerOr(call.string("catalog"), s.catalog());
        if (catalog == null || !PART.matcher(catalog).matches()) {
          return Classification.deny(policy.rule("bad-argument"), "Name a catalog.", "Call databricks_list_catalogs "
              + "to see the catalogs.");
        }
        return s.allowsCatalog(catalog) ? Classification.read() : notAllowed(catalog, env, policy);
      }
      case DatabricksTools.LIST_TABLES -> {
        String catalog = lowerOr(call.string("catalog"), s.catalog());
        String schema = lowerOr(call.string("schema"), s.defaultSchema());
        if (catalog == null || !PART.matcher(catalog).matches() || !PART.matcher(schema).matches()) {
          return Classification.deny(policy.rule("bad-argument"), "Name a catalog and a schema.",
              "Call databricks_list_schemas to see the schemas.");
        }
        return s.allows(catalog, schema) ? Classification.read() : notAllowed(catalog + "." + schema, env, policy);
      }
      case DatabricksTools.DESCRIBE_TABLE -> {
        String[] t = table(call.string("table"), s);
        if (t == null) {
          return Classification.deny(policy.rule("bad-argument"), "Name a table as catalog.schema.table, or "
              + "schema.table in the default catalog.", "Call databricks_list_tables to see the tables.");
        }
        return s.allows(t[0], t[1]) ? Classification.read() : notAllowed(t[0] + "." + t[1], env, policy);
      }
      case DatabricksTools.QUERY, DatabricksTools.EXECUTE -> {
        SparkSql.Statement st = SparkSql.read(call.string("sql"));
        boolean read = DatabricksTools.QUERY.equals(call.tool());
        switch (st.kind()) {
          case UNREADABLE -> {
            return Classification.deny(policy.rule("unparsed"), st.problem() + " The gateway doesn't run what it "
                + "can't read.", "Rewrite it in plain Databricks SQL.");
          }
          case FORBIDDEN -> {
            return Classification.deny(policy.rule("forbidden"), st.problem(), null);
          }
          case READ -> {
            if (!read) {
              return Classification.deny(policy.rule("execute-read"), "This is a read.", "Use databricks_query; it "
                  + "doesn't need approval.");
            }
          }
          case CHANGE -> {
            if (read) {
              return Classification.deny(policy.rule("query-writes"), "databricks_query only reads; this is "
                  + st.type() + ".", "Use databricks_execute: a person approves the exact statement.");
            }
          }
          default -> throw new IllegalStateException();
        }
        String problem = parameters(call.arguments().path("parameters"), st.parameters());
        if (problem != null) {
          return Classification.deny(policy.rule("bad-argument"), problem, null);
        }
        String outside = outsideLists(st, s, env);
        if (outside != null) {
          return Classification.deny(policy.rule("not-allowed"), outside, read ? "Call databricks_list_catalogs to "
              + "see what you can use." : null);
        }
        if (read) {
          return Classification.read();
        }
        ObjectNode facts = F.objectNode().put("statement", st.type());
        if (st.target() != null) {
          facts.put("target", st.target());
        }
        return Classification.mutation(st.type() + (st.target() == null ? "" : " on " + st.target())
            + " changes data", facts);
      }
      default -> {
        return Classification.deny(policy.rule("unknown-tool"), manifest.name() + " has no tool " + call.tool() + ".",
            null);
      }
    }
  }

  private static Classification notAllowed(String what, String env, PolicyView policy) {
    return Classification.deny(policy.rule("not-allowed"), what + " isn't one of the catalogs and schemas agents may "
        + "use on " + env + ".", "Call databricks_list_catalogs to see what you can use.");
  }

  private static String lowerOr(String value, String dflt) {
    return value == null || value.isBlank() ? dflt : value.strip().toLowerCase(Locale.ROOT);
  }

  /**
   * Why the statement names a catalog or schema off the connection's lists, or {@code null}: every table, schema,
   * catalog and qualified function it names, a table without a catalog or schema in the connection's defaults. A
   * listing of every catalog or schema (SHOW CATALOGS, SHOW SCHEMAS) would show names off the lists.
   */
  static String outsideLists(SparkSql.Statement st, DatabricksSettings s, String env) {
    if (!s.limited()) {
      return null;
    }
    if (st.type().equals("SHOW CATALOGS") && !s.catalogs().isEmpty()) {
      return "SHOW CATALOGS lists every catalog, also those off the connection's list: databricks_list_catalogs "
          + "shows the ones agents may use.";
    }
    if ((st.type().equals("SHOW SCHEMAS") || st.type().equals("SHOW DATABASES")) && !s.schemas().isEmpty()) {
      return st.type() + " lists every schema, also those off the connection's list: databricks_list_schemas shows "
          + "the ones agents may use.";
    }
    for (SparkSql.Name n : st.names()) {
      List<String> p = n.parts();
      String catalog;
      String schema;
      switch (n.ref()) {
        case CATALOG -> {
          if (p.size() != 1) {
            return "GateRook can't tell which catalog " + n.text() + " names.";
          }
          if (!s.allowsCatalog(p.getFirst())) {
            return "The catalog " + p.getFirst() + " isn't one agents may use on " + env + ".";
          }
          continue;
        }
        case SCHEMA -> {
          if (p.size() > 2) {
            return "GateRook can't tell which schema " + n.text() + " names.";
          }
          catalog = p.size() == 2 ? p.get(0) : s.catalog();
          schema = p.getLast();
        }
        case FUNCTION -> {
          if (p.size() > 3) {
            return "GateRook can't tell which function " + n.text() + " names.";
          }
          catalog = p.size() == 3 ? p.get(0) : s.catalog();
          schema = p.get(p.size() - 2);
        }
        default -> {
          if (p.size() > 3) {
            return "GateRook can't tell which table " + n.text() + " names.";
          }
          catalog = p.size() == 3 ? p.get(0) : s.catalog();
          schema = p.size() >= 2 ? p.get(p.size() - 2) : s.defaultSchema();
        }
      }
      if (!s.allows(catalog, schema)) {
        return (catalog == null ? schema : catalog + "." + schema) + " (in " + n.text() + ") isn't one of the "
            + "schemas agents may use on " + env + ".";
      }
    }
    return null;
  }

  /**
   * Why {@code parameters} aren't Databricks' named parameters of this statement, or {@code null}: each {@code {name,
   * value, type}} once, a scalar value or null, a scalar type, every one used as {@code :name} and every {@code :name}
   * given.
   */
  static String parameters(JsonNode parameters, Set<String> used) {
    Set<String> given = new HashSet<>();
    if (parameters != null && !parameters.isMissingNode() && !parameters.isNull()) {
      if (!parameters.isArray()) {
        return "parameters must be an array of {name, value, type}.";
      }
      if (parameters.size() > MAX_PARAMETERS) {
        return "At most " + MAX_PARAMETERS + " parameters per statement.";
      }
      for (JsonNode p : parameters) {
        String name = p.path("name").asString("");
        if (!PARAMETER.matcher(name).matches()) {
          return "A parameter needs a name such as since (written :since in the SQL).";
        }
        if (!given.add(name)) {
          return "Parameter " + name + " is given twice.";
        }
        JsonNode v = p.path("value");
        if (!(v.isString() || v.isNumber() || v.isBoolean() || v.isNull())) {
          return "Parameter " + name + " needs a single value: text, a number, a boolean or null.";
        }
        if (p.has("type") && !PARAMETER_TYPE.matcher(p.path("type").asString("")).matches()) {
          return "Parameter " + name + " needs a scalar type in upper case, such as INT, DATE or DECIMAL(10,2).";
        }
        if (!used.contains(name)) {
          return "The statement doesn't use :" + name + ".";
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

  /**
   * The parameters as the API takes them: {@code [{name, value, type}]}, the value as text or null; without a type, a
   * whole number is a {@code BIGINT}, another number a {@code DOUBLE}, a boolean a {@code BOOLEAN}, text a
   * {@code STRING}.
   */
  static ArrayNode parameterList(JsonNode parameters) {
    ArrayNode out = F.arrayNode();
    if (parameters == null || !parameters.isArray()) {
      return out;
    }
    for (JsonNode p : parameters) {
      JsonNode v = p.path("value");
      ObjectNode q = out.addObject().put("name", p.path("name").asString());
      if (v.isNull() || v.isMissingNode()) {
        q.putNull("value");
      } else {
        q.put("value", v.isString() ? v.asString() : v.toString());
      }
      String type = p.path("type").asString(null);
      if (type == null) {
        type = v.isIntegralNumber() ? "BIGINT" : v.isNumber() ? "DOUBLE" : v.isBoolean() ? "BOOLEAN" : null;
      }
      if (type != null) {
        q.put("type", type);
      }
    }
    return out;
  }

  /**
   * {@code catalog.schema.table}, {@code schema.table} or {@code table}, with backticks, as {@code [catalog, schema,
   * table]} in lower case, the connection's defaults filling what is left out; {@code null} if not a table name or no
   * catalog is known.
   */
  static String[] table(String written, DatabricksSettings s) {
    if (written == null || written.isBlank()) {
      return null;
    }
    List<String> parts = new ArrayList<>();
    StringBuilder part = new StringBuilder();
    boolean quoted = false;
    boolean wasQuoted = false;
    String w = written.strip();
    for (int i = 0; i < w.length(); i++) {
      char c = w.charAt(i);
      if (quoted) {
        if (c == '`' && i + 1 < w.length() && w.charAt(i + 1) == '`') {
          part.append('`');
          i++;
        } else if (c == '`') {
          quoted = false;
        } else {
          part.append(c);
        }
      } else if (c == '`' && part.isEmpty()) {
        quoted = true;
        wasQuoted = true;
      } else if (c == '.') {
        parts.add(part.toString());
        part.setLength(0);
        wasQuoted = false;
      } else {
        if (wasQuoted) {
          return null;
        }
        part.append(c);
      }
    }
    if (quoted) {
      return null;
    }
    parts.add(part.toString());
    if (parts.size() > 3) {
      return null;
    }
    for (String p : parts) {
      if (!PART.matcher(p).matches()) {
        return null;
      }
    }
    String table = parts.getLast().toLowerCase(Locale.ROOT);
    String schema = parts.size() >= 2 ? parts.get(parts.size() - 2).toLowerCase(Locale.ROOT) : s.defaultSchema();
    String catalog = parts.size() == 3 ? parts.getFirst().toLowerCase(Locale.ROOT) : s.catalog();
    return catalog == null ? null : new String[] {catalog, schema, table};
  }

  // ── The exact action ────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    String where = cfg.name() + " · " + hostOf(cfg) + " · warehouse "
        + Objects.requireNonNullElse(cfg.string("/warehouseId"), "?");
    JsonNode parameters = call.arguments().path("parameters");
    List<Field> fields = new ArrayList<>();
    if (parameters.isArray() && !parameters.isEmpty()) {
      fields.add(new Field("Parameters", shorten(parameterList(parameters).toString(), 2_000)));
    }
    if (cfg.string("/catalog") != null || cfg.string("/schema") != null) {
      fields.add(new Field("Default catalog and schema", Objects.requireNonNullElse(cfg.string("/catalog"),
          "the workspace's") + " · " + Objects.requireNonNullElse(cfg.string("/schema"), "default")));
    }
    if (DatabricksTools.EXECUTE.equals(call.tool())) {
      String sql = Objects.requireNonNullElse(call.string("sql"), "");
      SparkSql.Statement st = SparkSql.read(sql);
      ObjectNode payload = F.objectNode().put("sql", sql);
      if (parameters.isArray() && !parameters.isEmpty()) {
        payload.set("parameters", parameterList(parameters));
      }
      return ActionPreview.of(ActionPreview.Kind.SQL, "sql", sql, where, shorten(DatabricksTools.EXECUTE + " on "
              + cfg.name() + ": " + st.type() + (st.target() == null ? "" : " " + st.target()), 120))
          .withFields(fields)
          .withImpact(List.of(new Field("Target", st.target() == null ? "Unknown: not analyzed" : st.target()),
              new Field("Transaction", st.dml() ? "One statement; Delta applies it whole or not at all"
                  : "One statement, as Databricks runs it"),
              new Field("Retried", "No: a request whose answer is lost is reported as unknown")))
          .withFacets(Map.of("statement", st.type(), "target", Objects.requireNonNullElse(st.target(), "")))
          .withPayload(payload);
    }
    // A read tool an endpoint put in Approval: its arguments are the whole action.
    ObjectNode args = call.arguments().deepCopy();
    if (DatabricksTools.QUERY.equals(call.tool())) {
      return ActionPreview.of(ActionPreview.Kind.SQL, "sql", Objects.requireNonNullElse(call.string("sql"), ""),
              where, shorten(call.tool() + " on " + cfg.name(), 120))
          .withFields(fields)
          .withPayload(F.objectNode().set("arguments", args))
          .withFacets(Map.of("tool", call.tool()))
          .withImpact(List.of(new Field("Reads", "Rows, no change")));
    }
    return ActionPreview.of(ActionPreview.Kind.JSON, "json", args.toString(), where,
            shorten(call.tool() + " on " + cfg.name(), 120))
        .withPayload(F.objectNode().set("arguments", args))
        .withFacets(Map.of("tool", call.tool()))
        .withImpact(List.of(new Field("Reads", "Metadata, no change")));
  }

  private static String hostOf(ConnectionConfig cfg) {
    try {
      return DatabricksSettings.base(cfg.string("/host")).getHost();
    } catch (ConnectorException e) {
      return "?";
    }
  }

  private static String shorten(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }

  // ── Execution ───────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    DatabricksSettings s = DatabricksSettings.of(cfg);
    DatabricksApi.Call c = new DatabricksApi.Call(cfg, s, secrets, ctx);
    return switch (call.tool()) {
      case DatabricksTools.LIST_CATALOGS -> listCatalogs(c, arguments(call, ctx));
      case DatabricksTools.LIST_SCHEMAS -> listSchemas(c, arguments(call, ctx));
      case DatabricksTools.LIST_TABLES -> listTables(c, arguments(call, ctx));
      case DatabricksTools.DESCRIBE_TABLE -> describeTable(c, arguments(call, ctx));
      case DatabricksTools.QUERY -> query(c, arguments(call, ctx));
      case DatabricksTools.EXECUTE -> change(c);
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

  /** One kind of listing: its path, the array in its answer, how an entry reads. */
  private interface Lister {
    void add(JsonNode entry, ArrayNode out, List<String> names);
  }

  /**
   * Pages of a Unity Catalog listing until one holds an entry agents may use, as a page may hold none but still name
   * the next; the cursor is Unity Catalog's page token.
   */
  private ToolResult listing(DatabricksApi.Call c, String tool, String path, String array, ObjectNode out,
      String field, JsonNode args, ObjectNode request, Lister lister, String empty) throws ConnectorException {
    int limit = limit(args, 50, 50);
    String hash = Cursors.hash(request);
    String token = args.path("cursor").isString() ? Cursors.open(c.ctx(), tool, args.get("cursor").asString(),
        c.cfg().id(), hash).asString(null) : null;
    ArrayNode entries = out.putArray(field);
    List<String> names = new ArrayList<>();
    String next = null;
    for (int page = 0; page < MAX_LIST_PAGES; page++) {
      JsonNode r = api.get(c, path + (path.contains("?") ? "&" : "?") + DatabricksApi.query("max_results",
          Integer.toString(limit), "page_token", token).substring(1), "Unity Catalog");
      for (JsonNode e : r.path(array)) {
        lister.add(e, entries, names);
      }
      next = r.path("next_page_token").asString(null);
      if (next != null && next.isBlank()) {
        next = null;
      }
      if (!entries.isEmpty() || next == null || c.ctx().remaining().compareTo(Duration.ofSeconds(1)) < 0) {
        break;
      }
      token = next;
    }
    if (next != null) {
      out.put("nextCursor", Cursors.seal(c.ctx(), tool, c.cfg().id(), hash, F.stringNode(next)));
    }
    return ToolResult.json(out, names.isEmpty() ? empty : names.size() + " " + field + ": " + String.join(", ", names)
        + (next == null ? "" : "\nMore " + field + ": pass nextCursor."));
  }

  private ToolResult listCatalogs(DatabricksApi.Call c, JsonNode args) throws ConnectorException {
    DatabricksSettings s = c.settings();
    return listing(c, DatabricksTools.LIST_CATALOGS, "api/2.1/unity-catalog/catalogs", "catalogs", F.objectNode(),
        "catalogs", args, F.objectNode().put("list", "catalogs"), (e, out, names) -> {
          String name = e.path("name").asString("");
          if (s.allowsCatalog(name)) {
            ObjectNode o = out.addObject().put("catalog", name);
            if (e.path("catalog_type").isString()) {
              o.put("kind", e.get("catalog_type").asString().toLowerCase(Locale.ROOT).replace('_', ' '));
            }
            comment(e, o);
            names.add(name);
          }
        }, "No catalogs you may use.");
  }

  private ToolResult listSchemas(DatabricksApi.Call c, JsonNode args) throws ConnectorException {
    DatabricksSettings s = c.settings();
    String catalog = lowerOr(args.path("catalog").asString(null), s.catalog());
    if (catalog == null || !PART.matcher(catalog).matches()) {
      throw ConnectorException.badInput("Name a catalog. Call databricks_list_catalogs to see the catalogs.");
    }
    if (!s.allowsCatalog(catalog)) {
      throw ConnectorException.denied(catalog + " isn't one of the catalogs of this connection.");
    }
    return listing(c, DatabricksTools.LIST_SCHEMAS, "api/2.1/unity-catalog/schemas" + DatabricksApi.query(
        "catalog_name", catalog), "schemas", F.objectNode().put("catalog", catalog), "schemas", args,
        F.objectNode().put("catalog", catalog), (e, out, names) -> {
          String name = e.path("name").asString("");
          if (s.allows(catalog, name)) {
            ObjectNode o = out.addObject().put("schema", name);
            comment(e, o);
            names.add(name);
          }
        }, "No schemas you may use in " + catalog + ".");
  }

  private ToolResult listTables(DatabricksApi.Call c, JsonNode args) throws ConnectorException {
    DatabricksSettings s = c.settings();
    String catalog = lowerOr(args.path("catalog").asString(null), s.catalog());
    String schema = lowerOr(args.path("schema").asString(null), s.defaultSchema());
    if (catalog == null || !PART.matcher(catalog).matches() || !PART.matcher(schema).matches()) {
      throw ConnectorException.badInput("Name a catalog and a schema. Call databricks_list_schemas to see the "
          + "schemas.");
    }
    if (!s.allows(catalog, schema)) {
      throw ConnectorException.denied(catalog + "." + schema + " isn't one of the schemas of this connection.");
    }
    return listing(c, DatabricksTools.LIST_TABLES, "api/2.1/unity-catalog/tables" + DatabricksApi.query(
        "catalog_name", catalog, "schema_name", schema, "omit_columns", "true", "omit_properties", "true"), "tables",
        F.objectNode().put("schema", catalog + "." + schema), "tables", args,
        F.objectNode().put("catalog", catalog).put("schema", schema), (e, out, names) -> {
          String name = e.path("name").asString("");
          String kind = e.path("table_type").asString("TABLE").toLowerCase(Locale.ROOT).replace('_', ' ');
          ObjectNode o = out.addObject().put("table", name).put("kind", kind);
          if (e.path("data_source_format").isString()) {
            o.put("format", e.get("data_source_format").asString().toLowerCase(Locale.ROOT));
          }
          comment(e, o);
          names.add(name + (kind.equals("managed") ? "" : " (" + kind + ")"));
        }, "No tables in " + catalog + "." + schema + ".");
  }

  private static void comment(JsonNode e, ObjectNode o) {
    if (e.path("comment").isString() && !e.get("comment").asString().isBlank()) {
      o.put("comment", Cells.text(e.get("comment").asString(), 500).asString());
    }
  }

  private ToolResult describeTable(DatabricksApi.Call c, JsonNode args) throws ConnectorException {
    DatabricksSettings s = c.settings();
    String[] t = table(args.path("table").asString(null), s);
    if (t == null) {
      throw ConnectorException.badInput("Name a table as catalog.schema.table, or schema.table in the default "
          + "catalog.");
    }
    if (!s.allows(t[0], t[1])) {
      throw ConnectorException.denied(t[0] + "." + t[1] + " isn't one of the schemas of this connection.");
    }
    String full = t[0] + "." + t[1] + "." + t[2];
    JsonNode r = api.get(c, DatabricksApi.path("api", "2.1", "unity-catalog", "tables", full), "Unity Catalog");
    String kind = r.path("table_type").asString("TABLE").toLowerCase(Locale.ROOT).replace('_', ' ');
    ObjectNode out = F.objectNode().put("table", full).put("kind", kind);
    if (r.path("data_source_format").isString()) {
      out.put("format", r.get("data_source_format").asString().toLowerCase(Locale.ROOT));
    }
    comment(r, out);
    ArrayNode columns = out.putArray("columns");
    List<String> text = new ArrayList<>();
    List<String> partitions = new ArrayList<>();
    for (JsonNode col : r.path("columns")) {
      String name = col.path("name").asString("");
      String type = col.path("type_text").asString(col.path("type_name").asString(""));
      ObjectNode e = columns.addObject().put("name", name).put("type", type)
          .put("nullable", col.path("nullable").asBoolean(true));
      comment(col, e);
      if (col.path("partition_index").isIntegralNumber()) {
        partitions.add(name);
      }
      text.add(name + " " + type);
    }
    if (!partitions.isEmpty()) {
      ArrayNode p = out.putArray("partitionedBy");
      partitions.forEach(p::add);
    }
    if (r.path("view_definition").isString()) {
      out.put("viewQuery", Cells.text(r.get("view_definition").asString(), 8_000).asString());
    }
    return ToolResult.json(out, full + " (" + kind + (out.has("format") ? ", " + out.get("format").asString() : "")
        + "): " + String.join(", ", text) + (partitions.isEmpty() ? "" : " · partitioned by "
        + String.join(", ", partitions)));
  }

  /** How long a request may wait for the warehouse: 5 to 50 s, or 0 to poll when less is left. */
  private static long waitSeconds(ExecContext ctx) {
    long left = (ctx.remaining().toMillis() - 2_000) / 1_000;
    return left >= 5 ? Math.min(50, left) : 0;
  }

  /** {@code POST /api/2.0/sql/statements}: INLINE, JSON_ARRAY, the defaults of the connection. */
  private JsonNode submit(DatabricksApi.Call c, String sql, ArrayNode parameters, boolean read)
      throws ConnectorException {
    DatabricksSettings s = c.settings();
    ObjectNode body = F.objectNode().put("warehouse_id", s.warehouse()).put("statement", sql)
        .put("disposition", "INLINE").put("format", "JSON_ARRAY");
    if (s.catalog() != null) {
      body.put("catalog", s.catalog());
    }
    if (s.schema() != null) {
      body.put("schema", s.schema());
    }
    if (!parameters.isEmpty()) {
      body.set("parameters", parameters);
    }
    if (read) {
      body.put("row_limit", (long) c.ctx().limits().maxRows() * PAGES_PER_STATEMENT).put("byte_limit", BYTE_LIMIT);
    }
    long wait = waitSeconds(c.ctx());
    body.put("wait_timeout", wait + "s");
    // A read waits on the server for what the call has left, and Databricks cancels it there; a change goes on.
    body.put("on_wait_timeout", read && wait > 0 ? "CANCEL" : "CONTINUE");
    return api.post(c, "api/2.0/sql/statements", body, read ? "Databricks SQL" : "The change", !read);
  }

  /**
   * Polls a statement until it ends or the call's deadline. At the deadline a read is cancelled; a change is left
   * running and {@code null} returned.
   */
  private JsonNode await(DatabricksApi.Call c, JsonNode r, boolean read) throws ConnectorException {
    ExecContext ctx = c.ctx();
    String id = statementId(r);
    long pause = 250;
    while (!TERMINAL.contains(r.path("status").path("state").asString(""))) {
      if (ctx.remaining().compareTo(Duration.ofMillis(1_500)) <= 0) {
        if (read) {
          cancel(c, id);
          throw ConnectorException.timeout("The warehouse didn't finish within the call's time, so GateRook "
              + "cancelled statement " + id + ". Narrow the query, or filter on a partition column.");
        }
        return null;
      }
      try {
        Thread.sleep(Math.min(pause, Math.max(1, ctx.remaining().toMillis() - 1_500)));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        if (read) {
          cancel(c, id);
        }
        throw ConnectorException.timeout("The statement was cancelled with the call.");
      }
      pause = Math.min(2_000, pause * 2);
      r = api.get(c, DatabricksApi.path("api", "2.0", "sql", "statements", id), "Databricks SQL");
    }
    return r;
  }

  private static String statementId(JsonNode r) throws ConnectorException {
    String id = r.path("statement_id").asString("");
    if (!STATEMENT_ID.matcher(id).matches()) {
      throw ConnectorException.resourceError("Databricks answered without a statement id.", null);
    }
    return id;
  }

  private void cancel(DatabricksApi.Call c, String id) {
    try {
      api.post(c, DatabricksApi.path("api", "2.0", "sql", "statements", id, "cancel"), F.objectNode(),
          "Databricks SQL", false);
    } catch (ConnectorException e) {
      // The statement ends at the warehouse's own timeout; the call ends either way.
    }
  }

  /** A statement that ended without success, as what the agent can do about it. */
  private static ConnectorException failed(JsonNode r, String id) {
    String state = r.path("status").path("state").asString("");
    JsonNode error = r.path("status").path("error");
    String message = OutputSanitizer.sanitize(DatabricksApi.firstLine(error.path("message").asString(state)));
    return switch (state) {
      case "CANCELED" -> ConnectorException.timeout("Databricks cancelled statement " + id + ": the call's time ran "
          + "out. Narrow the query, or filter on a partition column.");
      case "CLOSED" -> ConnectorException.badInput("The result of statement " + id + " is no longer available: "
          + "Databricks keeps it for an hour. Run the query again.");
      default -> switch (error.path("error_code").asString("")) {
        case "PERMISSION_DENIED", "UNAUTHENTICATED" -> ConnectorException.resourceError("Databricks refused the "
            + "statement: " + message + ". The identity needs USE CATALOG, USE SCHEMA and SELECT on what it reads.",
            null);
        case "TEMPORARILY_UNAVAILABLE", "SERVICE_UNDER_MAINTENANCE", "WORKSPACE_TEMPORARILY_UNAVAILABLE",
            "IO_ERROR", "INTERNAL_ERROR" -> ConnectorException.unavailable("The warehouse failed: " + message + ".",
            null);
        case "DEADLINE_EXCEEDED" -> ConnectorException.timeout("The warehouse stopped the statement at its "
            + "timeout: " + message + ".");
        case "RESOURCE_EXHAUSTED" -> ConnectorException.resourceError("The warehouse is out of capacity: " + message
            + ". Try again in a moment.", null);
        default -> ConnectorException.badInput("Databricks refused the statement: " + message + ".");
      };
    };
  }

  private ToolResult query(DatabricksApi.Call c, JsonNode args) throws ConnectorException {
    ExecContext ctx = c.ctx();
    String sql = args.path("sql").asString("");
    SparkSql.Statement st = SparkSql.read(sql);
    if (st.kind() != SparkSql.Kind.READ) {
      throw ConnectorException.denied("databricks_query only runs one statement the gateway reads as a read.");
    }
    String problem = parameters(args.path("parameters"), st.parameters());
    if (problem != null) {
      throw ConnectorException.badInput(problem);
    }
    String outside = outsideLists(st, c.settings(), ctx.env() == null ? "this environment" : ctx.env().id());
    if (outside != null) {
      throw ConnectorException.denied(outside);
    }
    int limit = limit(args, ctx.limits().maxRows(), DEFAULT_LIMIT);
    ObjectNode request = F.objectNode().put("sql", sql);
    request.set("parameters", parameterList(args.path("parameters")));
    String hash = Cursors.hash(request);
    JsonNode r;
    int chunk = 0;
    int offset = 0;
    if (args.path("cursor").isString()) {
      JsonNode at = Cursors.open(ctx, DatabricksTools.QUERY, args.get("cursor").asString(), c.cfg().id(), hash);
      String id = at.path("s").asString("");
      if (!STATEMENT_ID.matcher(id).matches()) {
        throw ConnectorException.badInput("The cursor names no statement. Start again without cursor.");
      }
      chunk = Math.max(0, at.path("k").asInt(0));
      offset = Math.max(0, at.path("o").asInt(0));
      r = api.get(c, DatabricksApi.path("api", "2.0", "sql", "statements", id), "Databricks SQL");
    } else {
      r = await(c, submit(c, sql, parameterList(args.path("parameters")), true), true);
    }
    String id = statementId(r);
    if (!"SUCCEEDED".equals(r.path("status").path("state").asString(""))) {
      throw failed(r, id);
    }
    JsonNode manifest = r.path("manifest");
    ObjectNode facts = F.objectNode().put("statement", id);
    if (manifest.path("total_row_count").isIntegralNumber()) {
      facts.put("totalRows", manifest.get("total_row_count").asLong());
    }
    if (manifest.path("total_byte_count").isIntegralNumber()) {
      facts.put("resultBytes", manifest.get("total_byte_count").asLong());
    }
    if (manifest.path("truncated").asBoolean(false)) {
      facts.put("cut", "Databricks kept the first " + (long) ctx.limits().maxRows() * PAGES_PER_STATEMENT + " rows or "
          + (BYTE_LIMIT >> 20) + " MiB of the result; narrow the query to see the rest.");
    }
    return page(c, id, manifest, r.path("result"), chunk, offset, limit, hash, facts);
  }

  /**
   * One page of rows within the row limit and the response budget, from row {@code offset} of chunk {@code chunk} on.
   * The cursor names the chunk and the row the next page starts at, so a page cut at the response size goes on
   * exactly where it stopped.
   */
  private ToolResult page(DatabricksApi.Call c, String id, JsonNode manifest, JsonNode first, int chunk, int offset,
      int limit, String hash, ObjectNode facts) throws ConnectorException {
    ExecContext ctx = c.ctx();
    List<String> types = Cells.types(manifest);
    long budget = Math.max(4_096, ctx.limits().maxResponseBytes() * 3 / 4 - 2_048);
    long used = 0;
    ArrayNode rows = F.arrayNode();
    String truncated = null;
    ObjectNode position = null;
    JsonNode current;
    if (first.has("data_array") && first.path("chunk_index").asInt(0) == chunk) {
      current = first;
    } else if (manifest.path("total_chunk_count").asInt(-1) == 0 || manifest.path("total_row_count").asLong(-1) == 0) {
      current = F.objectNode();
    } else {
      current = chunk(c, id, chunk);
    }
    int skip = offset;
    for (int chunks = 1; ; chunks++) {
      JsonNode data = current.path("data_array");
      int i = skip;
      for (; i < data.size(); i++) {
        if (rows.size() >= limit) {
          break;
        }
        ArrayNode cells = Cells.row(data.get(i), types, ctx.limits().maxCellChars());
        used += cells.toString().length();
        if (used > budget && !rows.isEmpty()) {
          truncated = "size_limit";
          break;
        }
        rows.add(cells);
      }
      if (i < data.size()) {
        position = F.objectNode().put("s", id).put("k", chunk).put("o", i);
        break;
      }
      if (!current.path("next_chunk_index").isIntegralNumber()) {
        break;
      }
      int next = current.get("next_chunk_index").asInt();
      if (rows.size() >= limit || chunks >= MAX_CHUNKS || ctx.remaining().compareTo(Duration.ofSeconds(1)) < 0) {
        position = F.objectNode().put("s", id).put("k", next).put("o", 0);
        break;
      }
      chunk = next;
      skip = 0;
      current = chunk(c, id, chunk);
    }
    String nextCursor = position == null ? null : Cursors.seal(ctx, DatabricksTools.QUERY, c.cfg().id(), hash,
        position);
    ToolResult result = ToolResult.table(new ToolResult.Table(Cells.columns(manifest), rows, nextCursor), facts);
    return truncated == null ? result : result.truncated(truncated);
  }

  private JsonNode chunk(DatabricksApi.Call c, String id, int index) throws ConnectorException {
    return api.get(c, DatabricksApi.path("api", "2.0", "sql", "statements", id, "result", "chunks",
        Integer.toString(index)), "Databricks SQL");
  }

  private ToolResult change(DatabricksApi.Call c) throws ConnectorException {
    ExecContext ctx = c.ctx();
    if (!ctx.approved()) {
      throw ConnectorException.denied("Not approved: databricks_execute changes data, so a person approves it "
          + "first.");
    }
    JsonNode payload = ctx.approvedPayload().orElseThrow(() -> ConnectorException.denied("The approved action "
        + "carries no statement."));
    String sql = payload.path("sql").asString("");
    SparkSql.Statement st = SparkSql.read(sql);
    if (st.kind() != SparkSql.Kind.CHANGE) {
      throw ConnectorException.denied("databricks_execute runs one analyzed change.");
    }
    String problem = parameters(payload.path("parameters"), st.parameters());
    if (problem != null) {
      throw ConnectorException.badInput(problem);
    }
    String outside = outsideLists(st, c.settings(), ctx.env() == null ? "this environment" : ctx.env().id());
    if (outside != null) {
      throw ConnectorException.denied(outside);
    }
    JsonNode submitted = submit(c, sql, payload.path("parameters").isArray() ? (ArrayNode) payload.get("parameters")
        .deepCopy() : F.arrayNode(), false);
    String id = statementId(submitted);
    JsonNode r;
    try {
      r = await(c, submitted, false);
    } catch (ConnectorException e) {
      throw ConnectorException.outcomeUnknown("GateRook lost track of the change, statement " + id + ", so whether it "
          + "ran is not known. Check the warehouse's query history before retrying.", e);
    }
    if (r == null) {
      throw ConnectorException.outcomeUnknown("Databricks is still running the change as statement " + id + ". Check "
          + "the warehouse's query history before retrying.", null);
    }
    String state = r.path("status").path("state").asString("");
    if ("FAILED".equals(state) || "CANCELED".equals(state)) {
      String message = OutputSanitizer.sanitize(DatabricksApi.firstLine(r.path("status").path("error")
          .path("message").asString(state)));
      throw ConnectorException.resourceError("Databricks " + ("CANCELED".equals(state) ? "cancelled" : "stopped")
          + " the change (statement " + id + "): " + message + "." + (st.dml() ? " A DML statement that fails "
          + "changes nothing." : " Check the table before retrying."), null);
    }
    ObjectNode out = F.objectNode().put("committed", true).put("statement", st.type()).put("statementId", id);
    if (st.target() != null) {
      out.put("target", st.target());
    }
    List<String> said = new ArrayList<>();
    JsonNode columns = r.path("manifest").path("schema").path("columns");
    JsonNode row = r.path("result").path("data_array").path(0);
    for (int i = 0; i < columns.size(); i++) {
      String name = columns.get(i).path("name").asString("");
      if (name.startsWith("num_") && name.endsWith("_rows") && row.path(i).isString()) {
        JsonNode n = Cells.integer(row.get(i).asString());
        String key = switch (name) {
          case "num_affected_rows" -> "rowsAffected";
          case "num_inserted_rows" -> "rowsInserted";
          case "num_updated_rows" -> "rowsUpdated";
          case "num_deleted_rows" -> "rowsDeleted";
          default -> name;
        };
        out.set(key, n);
        if (name.equals("num_affected_rows")) {
          said.addFirst(n.asString() + (n.asString().equals("1") ? " row" : " rows") + " affected");
        }
      }
    }
    return ToolResult.json(out, said.isEmpty() ? st.type() + " done." : said.getFirst() + " by " + st.type() + ".");
  }

  // ── Test connection, health, describe ───────────────────────────────────────────────────────────────────────

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    DatabricksSettings s;
    try {
      s = DatabricksSettings.of(cfg);
    } catch (ConnectorException e) {
      return TestResult.failure("databricks.settings", e.getMessage(), null);
    }
    DatabricksApi.Call c = new DatabricksApi.Call(cfg, s, secrets, ctx);
    List<TestResult.Check> checks = new ArrayList<>();
    long started = System.nanoTime();
    String identity;
    try {
      JsonNode me = api.get(c, "api/2.0/preview/scim/v2/Me", "The workspace's sign-in");
      identity = OutputSanitizer.sanitize(me.path("userName").asString(me.path("displayName").asString(
          me.path("applicationId").asString(s.auth() == DatabricksSettings.Auth.OAUTH ? s.clientId() : "?"))));
      checks.add(new TestResult.Check("identity", true, ms(started), identity));
    } catch (ConnectorException e) {
      return TestResult.failure(e.kind() == ConnectorException.Kind.RESOURCE_UNAVAILABLE ? "databricks.sign-in"
          : "databricks.identity", e.getMessage(), null);
    }
    long warehoused = System.nanoTime();
    String warehouseName;
    String state;
    try {
      JsonNode w = api.get(c, DatabricksApi.path("api", "2.0", "sql", "warehouses", s.warehouse()),
          "The SQL warehouse");
      warehouseName = OutputSanitizer.sanitize(w.path("name").asString(s.warehouse()));
      state = w.path("state").asString("UNKNOWN");
      checks.add(new TestResult.Check("warehouse", true, ms(warehoused), warehouseName + " · " + state));
    } catch (ConnectorException e) {
      return TestResult.failure("databricks.warehouse", e.getMessage() + " The identity needs CAN USE on the SQL "
          + "warehouse.", null).withChecks(checks);
    }
    long listed = System.nanoTime();
    int visible = 0;
    List<String> first = new ArrayList<>();
    boolean more;
    try {
      JsonNode r = api.get(c, "api/2.1/unity-catalog/catalogs" + DatabricksApi.query("max_results", "50"),
          "Unity Catalog");
      for (JsonNode cat : r.path("catalogs")) {
        String name = cat.path("name").asString("");
        if (s.allowsCatalog(name)) {
          visible++;
          if (first.size() < 5) {
            first.add(name);
          }
        }
      }
      more = r.path("next_page_token").isString() && !r.get("next_page_token").asString().isBlank();
      checks.add(new TestResult.Check("catalogs", true, ms(listed), visible + (more ? "+" : "")
          + (visible == 1 ? " catalog" : " catalogs") + " agents may use" + (first.isEmpty() ? ""
          : ": " + String.join(", ", first) + (visible > first.size() || more ? ", …" : ""))));
    } catch (ConnectorException e) {
      return TestResult.failure("databricks.catalogs", e.getMessage(), null).withChecks(checks);
    }
    long latency = ms(listed);
    boolean running = "RUNNING".equals(state);
    if (running) {
      long ran = System.nanoTime();
      try {
        JsonNode r = await(c, submit(c, "SELECT current_user() AS user, current_catalog() AS catalog, "
            + "current_schema() AS schema", F.arrayNode(), true), true);
        if (!"SUCCEEDED".equals(r.path("status").path("state").asString(""))) {
          throw failed(r, statementId(r));
        }
        JsonNode row = r.path("result").path("data_array").path(0);
        checks.add(new TestResult.Check("statement", true, ms(ran), "Ran a query as "
            + OutputSanitizer.sanitize(row.path(0).asString(identity)) + " in " + OutputSanitizer.sanitize(
            row.path(1).asString("?")) + "." + OutputSanitizer.sanitize(row.path(2).asString("?"))));
      } catch (ConnectorException e) {
        return TestResult.failure("databricks.statement", "The identity can see the warehouse but not run queries "
            + "on it: " + e.getMessage(), null).withChecks(checks);
      }
    }
    TestResult.Permissions permissions = new TestResult.Permissions(TestResult.Level.UNKNOWN,
        "Databricks SQL reads as " + identity + ", within its Unity Catalog grants", null, List.of(),
        F.objectNode().put("signIn", s.auth() == DatabricksSettings.Auth.OAUTH ? "service principal"
            : "personal access token"));
    TestResult result = TestResult.success(latency, "Databricks SQL · " + warehouseName, identity, permissions)
        .withChecks(checks)
        .withMetadata(F.objectNode().put("serverVersion", "Databricks SQL").put("host", s.host())
            .put("warehouse", warehouseName));
    for (Hint h : hints(cfg.env(), s, visible, running ? null : state)) {
      result = result.withHint(h);
    }
    return result;
  }

  static List<Hint> hints(Env env, DatabricksSettings s, int visible, String stoppedState) {
    List<Hint> out = new ArrayList<>();
    if (!s.databricksDomain()) {
      out.add(new Hint(Severity.WARNING, "databricks.host", "/host", s.host() + " isn't one of Databricks' own "
          + "domains. Check that it is your workspace (a private link or a custom domain) before you give it a "
          + "token."));
    }
    if (env != Env.DEV && s.auth() == DatabricksSettings.Auth.TOKEN) {
      out.add(new Hint(Severity.WARNING, "databricks.personal-token", "/auth/method", "Calls use a personal access "
          + "token with every grant of its owner. On " + env.id() + ", use a service principal with read grants "
          + "only."));
    }
    out.add(new Hint(Severity.INFO, "databricks.read-only-boundary", null, "Databricks SQL has no read-only session: "
        + "GateRook sends databricks_query only reads, and Unity Catalog's grants are the boundary. Give the identity "
        + "USE CATALOG, USE SCHEMA and SELECT only."));
    if (stoppedState != null) {
      out.add(new Hint(Severity.INFO, "databricks.warehouse-not-running", "/warehouseId", "The warehouse is "
          + stoppedState + ", so no query ran: the first query starts it, and Databricks bills while it runs."));
    }
    if (visible == 0) {
      out.add(new Hint(Severity.WARNING, "databricks.no-catalogs", "/catalogs", "The identity sees no catalog "
          + (s.catalogs().isEmpty() ? "" : "matching the connection's list ") + "in this workspace."));
    }
    return out;
  }

  /**
   * Setup helpers ({@link DatabricksSuggestions}): the workspace profiles of {@code ~/.databrickscfg}; signed in, the
   * SQL warehouses, catalogs and schemas the identity may use.
   */
  @Override
  public Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope) {
    return DatabricksSuggestions.suggest(api, cfg, secrets, ctx, scope, machine);
  }

  /** Tests: this computer's files and environment elsewhere. */
  DatabricksConnector machine(DatabricksSuggestions.Machine machine) {
    this.machine = Objects.requireNonNull(machine, "machine");
    return this;
  }

  /** The SQL warehouse's state: the workspace and the sign-in, without starting the warehouse. */
  @Override
  public HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    try {
      DatabricksSettings s = DatabricksSettings.of(cfg);
      long started = System.nanoTime();
      api.get(new DatabricksApi.Call(cfg, s, secrets, ctx), DatabricksApi.path("api", "2.0", "sql", "warehouses",
          s.warehouse()), "The SQL warehouse");
      return HealthResult.healthy(ms(started));
    } catch (ConnectorException e) {
      return HealthResult.unreachable(e.getMessage());
    }
  }

  @Override
  public Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object) {
    ObjectNode out = F.objectNode();
    try {
      DatabricksSettings s = DatabricksSettings.of(cfg);
      out.put("workspace", s.host()).put("warehouse", s.warehouse());
      out.put("defaultCatalog", Objects.requireNonNullElse(s.catalog(), "the workspace's default"));
      out.put("defaultSchema", s.defaultSchema());
      if (s.catalogs().isEmpty()) {
        out.put("catalogs", "Every catalog the identity may use");
      } else {
        ArrayNode a = out.putArray("catalogs");
        s.catalogs().forEach(a::add);
      }
      if (!s.schemas().isEmpty()) {
        ArrayNode a = out.putArray("schemas");
        s.schemas().forEach(a::add);
      }
    } catch (ConnectorException e) {
      out.put("settings", e.getMessage());
    }
    out.put("reads", "One SELECT, WITH, VALUES, SHOW, DESCRIBE or EXPLAIN per databricks_query; name tables as "
        + "catalog.schema.table and pass values as :name parameters");
    out.put("changes", "One INSERT, UPDATE, DELETE, MERGE, DDL or maintenance statement per databricks_execute, "
        + "after approval");
    if (object != null && !object.isBlank()) {
      out.put("table", "Call databricks_describe_table with table " + object + ".");
    }
    return Optional.of(out);
  }

  @Override
  public void onConnectionChanged(String connectionId) {
    api.auth.evict(connectionId);
  }

  @Override
  public void onConnectionRemoved(String connectionId) {
    api.auth.evict(connectionId);
  }

  private static long ms(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
