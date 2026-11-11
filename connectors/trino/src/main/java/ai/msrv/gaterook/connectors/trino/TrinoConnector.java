package ai.msrv.gaterook.connectors.trino;

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
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.TestResult.Hint;
import ai.msrv.gaterook.connector.api.TestResult.Severity;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.support.BuiltIn;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import ai.msrv.gaterook.connectors.trino.TrinoHttp.Call;
import ai.msrv.gaterook.connectors.trino.TrinoHttp.Purpose;
import ai.msrv.gaterook.connectors.trino.TrinoSql.Kind;
import ai.msrv.gaterook.connectors.trino.TrinoSql.Ref;
import ai.msrv.gaterook.connectors.trino.TrinoSql.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Trino and Presto (docs/trino.md): catalogs, schemas, tables and SQL reads over Trino's client protocol;
 * {@code trino_execute} runs one statement a person approved.
 *
 * <p>Trino has no read-only session. {@link #classify} reads the text by Trino's tokens ({@link TrinoSql}) and picks
 * the route without I/O: only {@code SELECT}, {@code WITH}, {@code VALUES}, {@code SHOW}, {@code DESCRIBE} and
 * {@code EXPLAIN} without {@code ANALYZE} are reads; sessions, roles, grants, transactions, prepared statements,
 * procedures, catalogs, outside table functions and AI functions are refused. Before a read runs, Trino itself plans
 * it: {@code EXPLAIN (TYPE IO, FORMAT JSON)} must name no table it writes, and every table it reads must be in a
 * catalog of the connection ({@link IoPlan}). GateRook never asks Trino to plan a write: Trino plans one through its
 * catalog's write hooks, and a memory catalog creates the table of a {@code CREATE TABLE AS} while it is only being
 * planned. A change is planned by a read of what it reads instead, and writes the table its own name says; its
 * approval shows both, it runs once and is never retried; a lost answer leaves its outcome unknown. The user's own
 * access control in Trino stays part of the boundary.
 */
public final class TrinoConnector implements Connector {

  static final String TYPE = "trino";
  static final int DEFAULT_LIMIT = 100;
  static final int LIST_LIMIT = 200;
  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  private static final Pattern NAME = Pattern.compile("[^\\p{Cntrl}]{1,255}");

  private final TrinoHttp http = new TrinoHttp();
  private final ConnectorManifest manifest;

  public TrinoConnector() {
    SchemaResources schemas = SchemaResources.of(TrinoConnector.class, TYPE);
    this.manifest = ConnectorManifest.builder(TYPE, BuiltIn.VERSION, ConnectorManifest.Kind.BUILT_IN)
        .name("Trino / Presto")
        .icon("database")
        .group("Databases")
        .description("Catalogs, schemas and SQL reads checked by Trino's own plan. Changes wait for approval.")
        .configSchema(schemas.load("config"))
        .tools(TrinoTools.definitions(schemas))
        .access(ConnectorManifest.RequiredAccess.none())
        .build();
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  // ── Classification ──────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    TrinoSettings s;
    try {
      s = TrinoSettings.of(cfg);
    } catch (ConnectorException e) {
      return Classification.deny(policy.rule("bad-config"), e.getMessage(), "Complete the connection's settings.");
    }
    String env = policy.env() == null ? "this environment" : policy.env().id();
    switch (call.tool()) {
      case TrinoTools.LIST_CATALOGS -> {
        return Classification.read();
      }
      case TrinoTools.LIST_SCHEMAS, TrinoTools.LIST_TABLES -> {
        String catalog = Objects.requireNonNullElse(blank(call.string("catalog")), s.catalog());
        if (catalog == null || !NAME.matcher(catalog).matches()) {
          return Classification.deny(policy.rule("bad-argument"), "Name a catalog: the connection has no default "
              + "catalog.", "Call trino_list_catalogs to see the catalogs.");
        }
        if (TrinoTools.LIST_TABLES.equals(call.tool())) {
          String schema = blank(call.string("schema"));
          if (schema == null && (s.schema() == null || blank(call.string("catalog")) != null)) {
            return Classification.deny(policy.rule("bad-argument"), "Name a schema.", "Call trino_list_schemas to "
                + "see the schemas.");
          }
          if (schema != null && !NAME.matcher(schema).matches()) {
            return Classification.deny(policy.rule("bad-argument"), "The schema name has a control character.", null);
          }
        }
        return s.allows(catalog) ? Classification.read() : notAllowed(catalog, env, policy);
      }
      case TrinoTools.DESCRIBE_TABLE -> {
        String[] t = table(call.string("table"), s);
        if (t == null) {
          return Classification.deny(policy.rule("bad-argument"), "Name a table: catalog.schema.table, or "
              + "schema.table in the default catalog.", "Call trino_list_tables to see the tables.");
        }
        return s.allows(t[0]) ? Classification.read() : notAllowed(t[0], env, policy);
      }
      case TrinoTools.QUERY, TrinoTools.EXECUTE -> {
        TrinoSql.Statement st = TrinoSql.read(call.string("sql"));
        boolean read = TrinoTools.QUERY.equals(call.tool());
        return switch (st.kind()) {
          case UNREADABLE -> Classification.deny(policy.rule("unparsed"), st.problem() + " The gateway doesn't run "
              + "what it can't read.", "Rewrite it in plain Trino SQL.");
          case FORBIDDEN -> Classification.deny(policy.rule("forbidden"), st.problem(), null);
          case READ -> {
            if (!read) {
              yield Classification.deny(policy.rule("execute-read"), "This is a read.", "Use trino_query; it doesn't "
                  + "need approval.");
            }
            String problem = refs(st, s, env);
            yield problem == null ? Classification.read()
                : Classification.deny(policy.rule("catalog-not-allowed"), problem, "Call trino_list_catalogs to see "
                    + "the catalogs you can use.");
          }
          case CHANGE -> {
            if (read) {
              yield Classification.deny(policy.rule("query-writes"), "trino_query only reads; this is " + st.type()
                  + ".", "Use trino_execute: a person approves the exact statement.");
            }
            String problem = refs(st, s, env);
            if (problem != null) {
              yield Classification.deny(policy.rule("catalog-not-allowed"), problem, "Name the table as "
                  + "catalog.schema.table in a catalog of the connection.");
            }
            ObjectNode facts = F.objectNode().put("statement", st.type()).put("target", st.target().name().text());
            yield Classification.mutation(st.type() + " on " + st.target().name().text() + " changes data", facts);
          }
        };
      }
      default -> {
        return Classification.deny(policy.rule("unknown-tool"), manifest.name() + " has no tool " + call.tool() + ".",
            null);
      }
    }
  }

  private static Classification notAllowed(String catalog, String env, PolicyView policy) {
    return Classification.deny(policy.rule("catalog-not-allowed"), catalog + " isn't one of the catalogs agents may "
        + "use on " + env + ".", "Call trino_list_catalogs to see the catalogs you can use.");
  }

  /**
   * Why the statement's names leave the connection's catalogs, or {@code null}: its target, the objects a
   * {@code SHOW} or {@code DESCRIBE} names, the catalogs of qualified functions, and the forms whose plan can't tell.
   */
  static String refs(TrinoSql.Statement st, TrinoSettings s, String env) {
    if (s.catalogs().isEmpty()) {
      return null;
    }
    if (st.catalogs()) {
      return "SHOW CATALOGS lists every catalog, and on " + env + " only some are open to agents.";
    }
    List<Ref> all = new ArrayList<>(st.refs());
    if (st.target() != null) {
      all.add(st.target());
    }
    for (Ref ref : all) {
      String catalog = s.catalogOf(ref);
      if (catalog == null) {
        return "The gateway can't tell the catalog of " + ref.name().text() + ", and on " + env + " only some "
            + "catalogs are open to agents. Name it as catalog.schema.table.";
      }
      if (!s.allows(catalog)) {
        return (ref.role() == Role.FUNCTION ? "The function " + ref.name().text() + " is in catalog " : "") + catalog
            + ", which isn't one of the catalogs agents may use on " + env + ".";
      }
    }
    return null;
  }

  /** {@code [catalog, schema, table]} of a table name an agent gave, with the connection's defaults; or {@code null}. */
  static String[] table(String written, TrinoSettings s) {
    TrinoSql.Name name = TrinoSql.qualified(written);
    if (name == null) {
      return null;
    }
    List<String> p = name.parts();
    String catalog;
    String schema;
    switch (p.size()) {
      case 3 -> {
        catalog = p.get(0);
        schema = p.get(1);
      }
      case 2 -> {
        catalog = s.catalog();
        schema = p.get(0);
      }
      case 1 -> {
        catalog = s.catalog();
        schema = s.schema();
      }
      default -> {
        return null;
      }
    }
    if (catalog == null || schema == null) {
      return null;
    }
    return new String[] {catalog.toLowerCase(Locale.ROOT), schema, p.getLast()};
  }

  private static String blank(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  // ── The exact action ────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    String where = cfg.name() + " · " + where(cfg);
    if (TrinoTools.EXECUTE.equals(call.tool())) {
      TrinoSql.Statement st = TrinoSql.read(call.string("sql"));
      // What runs: the statement without a final semicolon, which Trino refuses.
      String sql = st.sql() != null ? st.sql() : Objects.requireNonNullElse(call.string("sql"), "");
      String target = st.target() == null ? null : st.target().name().text();
      List<String> warnings = new ArrayList<>();
      if ("none".equals(cfg.string("/auth/mode"))) {
        warnings.add("The coordinator doesn't authenticate this connection: Trino believes the user name it is "
            + "sent.");
      }
      return ActionPreview.of(ActionPreview.Kind.SQL, "sql", sql, where, shorten(TrinoTools.EXECUTE + " on "
              + cfg.name() + ": " + st.type() + (target == null ? "" : " " + target), 120))
          .withImpact(List.of(new Field("Target", target == null ? "Unknown: not analyzed" : target),
              new Field("Transaction", "One statement; Trino's connectors write it when its query finishes"),
              new Field("Retried", "No: a request whose answer is lost is reported as unknown")))
          .withWarnings(warnings)
          .withFacets(Map.of("statement", st.type(), "target", Objects.requireNonNullElse(target, "")))
          .withPayload(F.objectNode().put("sql", sql));
    }
    // A read tool an endpoint put in Approval: its arguments are the whole action.
    ObjectNode args = call.arguments().deepCopy();
    if (TrinoTools.QUERY.equals(call.tool())) {
      return ActionPreview.of(ActionPreview.Kind.SQL, "sql", Objects.requireNonNullElse(call.string("sql"), ""),
              where, shorten(call.tool() + " on " + cfg.name(), 120))
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

  /** {@code trino.example.com:8443 · hive.sales}, from the config as it is (describeAction has no settings). */
  private static String where(ConnectionConfig cfg) {
    String url = Objects.requireNonNullElse(cfg.string("/url"), "?").replaceFirst("^https?://", "")
        .replaceFirst("/$", "");
    String catalog = cfg.string("/catalog");
    String schema = cfg.string("/schema");
    return url + (catalog == null ? "" : " · " + catalog + (schema == null ? "" : "." + schema));
  }

  /**
   * Trino's plan before a person is asked. A change Trino plans as a write is never planned itself: Trino would begin
   * the write in its catalog while planning it. GateRook plans the read of what the change reads ({@link TrinoSql})
   * and shows the tables it reads and the table the statement's own name says it writes. A data definition statement
   * is echoed by Trino without running. A statement whose read Trino refuses, a read sent as a change, or one that
   * reaches past the connection's catalogs is never put to a person.
   */
  @Override
  public PreparedAction prepare(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    ActionPreview preview = describeAction(call, cfg);
    boolean change = TrinoTools.EXECUTE.equals(call.tool());
    if (!change && !TrinoTools.QUERY.equals(call.tool())) {
      return PreparedAction.approval(preview, null);
    }
    TrinoSettings s = TrinoSettings.of(cfg);
    Call c = new Call(cfg, s, secrets, ctx);
    TrinoSql.Statement st = TrinoSql.read(call.string("sql"));
    List<Field> impact = new ArrayList<>();
    try {
      if (change) {
        if (!st.change()) {
          return PreparedAction.reject(st.read() ? "This is a read. Use trino_query; it doesn't need approval."
              : Objects.requireNonNullElse(st.problem(), "trino_execute runs one analyzed change."));
        }
        String problem = refs(st, s, ctx.env() == null ? "this environment" : ctx.env().id());
        if (problem != null) {
          return PreparedAction.reject(problem);
        }
        impact.add(new Field("Statement", st.type()));
        if (st.planned() != null) {
          IoPlan plan = plan(c, st.planned());
          checkChange(plan, s);
          impact.add(new Field("Writes", written(st.target(), s)));
          impact.add(new Field("Reads", plan.inputs().isEmpty() ? "No table, by Trino's plan"
              : shorten(String.join(", ", plan.inputNames()), 2_000)));
        } else {
          definition(c, st);
          impact.add(new Field("Trino's plan", "None: Trino checks a data definition statement when it runs it"));
        }
      } else {
        if (!st.read()) {
          return PreparedAction.reject(Objects.requireNonNullElse(st.problem(), "trino_query only reads."));
        }
        IoPlan plan = plan(c, st.planned());
        checkRead(plan, s);
        impact.add(new Field("Reads", plan.inputs().isEmpty() ? "No table, by Trino's plan"
            : shorten(String.join(", ", plan.inputNames()), 2_000)));
      }
    } catch (ConnectorException e) {
      if (e.kind() == ConnectorException.Kind.BAD_INPUT || e.kind() == ConnectorException.Kind.DENIED
          || e.kind() == ConnectorException.Kind.RESOURCE_ERROR) {
        return PreparedAction.reject(e.getMessage());
      }
      throw e;
    }
    impact.addAll(preview.impact());
    return PreparedAction.approval(preview.withImpact(impact), null);
  }

  private static String shorten(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }

  // ── Trino's plan ────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * {@code EXPLAIN (TYPE IO, FORMAT JSON) <read>}: what Trino says a read reads. Only a statement GateRook reads as a
   * read is sent, a change's read of what it reads included; never a write, which Trino plans through its catalog's
   * write hooks.
   */
  IoPlan plan(Call c, String read) throws ConnectorException {
    if (!TrinoSql.read(read).read()) {
      throw ConnectorException.denied("GateRook asks Trino to plan reads only.");
    }
    return explain(c, read);
  }

  /**
   * {@code EXPLAIN (TYPE IO, FORMAT JSON) <statement>} of a data definition statement, which Trino parses and answers
   * with the statement's own text without running it. Trino planning it would mean it runs it as a write after all,
   * which GateRook doesn't put to a person.
   */
  void definition(Call c, TrinoSql.Statement st) throws ConnectorException {
    if (!st.change() || st.planned() != null) {
      throw ConnectorException.denied("GateRook asks Trino to plan reads only.");
    }
    if (explain(c, st.sql()).planned()) {
      throw ConnectorException.denied("Trino plans " + st.type() + " as a write, not as a data definition "
          + "statement: GateRook doesn't run it.");
    }
  }

  private IoPlan explain(Call c, String statement) throws ConnectorException {
    String[] text = new String[1];
    http.run(c, "EXPLAIN (TYPE IO, FORMAT JSON) " + statement, Purpose.READ, (columns, row) -> {
      if (text[0] == null && row.isArray() && !row.isEmpty()) {
        text[0] = row.get(0).asString(null);
      }
      return true;
    });
    IoPlan plan = IoPlan.parse(text[0]);
    if (plan == null) {
      throw ConnectorException.denied("Trino's IO plan of the statement can't be read, and GateRook doesn't run "
          + "what it can't check.");
    }
    return plan;
  }

  /** A read: Trino plans it as a query that writes no table and reads only catalogs of the connection. */
  static void checkRead(IoPlan plan, TrinoSettings s) throws ConnectorException {
    if (!plan.planned()) {
      throw ConnectorException.denied("Trino plans this as a data definition statement, not a query: trino_query "
          + "doesn't run it.");
    }
    if (plan.output() != null) {
      throw ConnectorException.denied("Trino's plan writes to " + plan.output().text() + ": trino_query only reads. "
          + "Use trino_execute, which a person approves.");
    }
    checkInputs(plan, s);
  }

  /**
   * A change, by Trino's plan of the read of what it reads: a plan of a query that writes no table, whose tables are
   * all in catalogs of the connection. The table the change writes is its own target, which {@link #refs} checks.
   */
  static void checkChange(IoPlan plan, TrinoSettings s) throws ConnectorException {
    if (!plan.planned() || plan.output() != null) {
      throw ConnectorException.denied("Trino's plan of what the change reads isn't the plan of a read, and GateRook "
          + "doesn't run what it can't check.");
    }
    checkInputs(plan, s);
  }

  /** The table a change writes, as its own name says, with the connection's default catalog and schema. */
  static String written(Ref target, TrinoSettings s) {
    List<String> parts = new ArrayList<>(target.name().parts().stream().map(p -> p.toLowerCase(Locale.ROOT)).toList());
    if (parts.size() == 1 && s.schema() != null) {
      parts.addFirst(s.schema().toLowerCase(Locale.ROOT));
    }
    if (parts.size() == 2 && s.catalog() != null) {
      parts.addFirst(s.catalog().toLowerCase(Locale.ROOT));
    }
    return String.join(".", parts);
  }

  private static void checkInputs(IoPlan plan, TrinoSettings s) throws ConnectorException {
    for (IoPlan.Table t : plan.inputs()) {
      if (!s.allows(t.catalog())) {
        throw ConnectorException.denied("The statement reads " + t.text() + ", which isn't in a catalog of this "
            + "connection.");
      }
    }
  }

  // ── Execution ───────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    TrinoSettings s = TrinoSettings.of(cfg);
    Call c = new Call(cfg, s, secrets, ctx);
    return switch (call.tool()) {
      case TrinoTools.LIST_CATALOGS -> listCatalogs(c, arguments(call, ctx));
      case TrinoTools.LIST_SCHEMAS -> listSchemas(c, arguments(call, ctx));
      case TrinoTools.LIST_TABLES -> listTables(c, arguments(call, ctx));
      case TrinoTools.DESCRIBE_TABLE -> describeTable(c, arguments(call, ctx));
      case TrinoTools.QUERY -> query(c, arguments(call, ctx));
      case TrinoTools.EXECUTE -> change(c);
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

  private static int limit(JsonNode args, int dflt, int max) {
    return Math.max(1, Math.min(args.path("limit").asInt(dflt), max));
  }

  /** A name in double quotes, as Trino reads it. */
  static String quoted(String name) {
    return "\"" + name.replace("\"", "\"\"") + "\"";
  }

  /** A string literal, as Trino reads it. */
  static String literal(String text) {
    return "'" + text.replace("'", "''") + "'";
  }

  /** The first column of every row of a fixed metadata statement, up to {@code max}. */
  private List<String> names(Call c, String sql, int max, boolean[] more) throws ConnectorException {
    List<String> out = new ArrayList<>();
    http.run(c, sql, Purpose.READ, (columns, row) -> {
      if (out.size() >= max) {
        more[0] = true;
        return false;
      }
      out.add(row.path(0).asString(""));
      return true;
    });
    return out;
  }

  private ToolResult listCatalogs(Call c, JsonNode args) throws ConnectorException {
    int limit = limit(args, LIST_LIMIT, 1_000);
    List<String> all = names(c, "SHOW CATALOGS", 10_000, new boolean[1]);
    ObjectNode out = F.objectNode();
    ArrayNode catalogs = out.putArray("catalogs");
    List<String> shown = new ArrayList<>();
    for (String name : all) {
      if (c.settings().allows(name) && shown.size() < limit) {
        catalogs.add(name);
        shown.add(name);
      }
    }
    boolean more = all.stream().filter(c.settings()::allows).count() > shown.size();
    if (c.settings().catalog() != null) {
      out.put("defaultCatalog", c.settings().catalog());
    }
    ToolResult r = ToolResult.json(out, shown.isEmpty() ? "No catalogs you may use." : shown.size()
        + (shown.size() == 1 ? " catalog: " : " catalogs: ") + String.join(", ", shown)
        + (more ? "\nMore catalogs: raise limit." : ""));
    return more ? r.truncated("row_limit") : r;
  }

  private ToolResult listSchemas(Call c, JsonNode args) throws ConnectorException {
    String catalog = Objects.requireNonNullElse(blank(args.path("catalog").asString(null)), c.settings().catalog());
    allowed(c, catalog);
    int limit = limit(args, LIST_LIMIT, 1_000);
    boolean[] more = new boolean[1];
    List<String> schemas = names(c, "SHOW SCHEMAS FROM " + quoted(catalog), limit, more);
    ObjectNode out = F.objectNode().put("catalog", catalog);
    ArrayNode a = out.putArray("schemas");
    schemas.forEach(a::add);
    ToolResult r = ToolResult.json(out, schemas.isEmpty() ? "No schemas in " + catalog + "." : catalog + ": "
        + String.join(", ", schemas) + (more[0] ? "\nMore schemas: raise limit." : ""));
    return more[0] ? r.truncated("row_limit") : r;
  }

  private ToolResult listTables(Call c, JsonNode args) throws ConnectorException {
    String givenCatalog = blank(args.path("catalog").asString(null));
    String catalog = Objects.requireNonNullElse(givenCatalog, c.settings().catalog());
    String schema = blank(args.path("schema").asString(null));
    if (schema == null && givenCatalog == null) {
      schema = c.settings().schema();
    }
    allowed(c, catalog);
    if (schema == null || !NAME.matcher(schema).matches()) {
      throw ConnectorException.badInput("Name a schema. Call trino_list_schemas to see the schemas.");
    }
    String like = blank(args.path("like").asString(null));
    int limit = limit(args, LIST_LIMIT, 1_000);
    String sql = "SELECT table_name, table_type FROM " + quoted(catalog) + ".information_schema.tables WHERE "
        + "table_schema = " + literal(schema) + (like == null ? "" : " AND table_name LIKE " + literal(like))
        + " ORDER BY table_name LIMIT " + (limit + 1);
    ObjectNode out = F.objectNode().put("catalog", catalog).put("schema", schema);
    ArrayNode tables = out.putArray("tables");
    List<String> names = new ArrayList<>();
    boolean[] more = new boolean[1];
    http.run(c, sql, Purpose.READ, (columns, row) -> {
      if (names.size() >= limit) {
        more[0] = true;
        return false;
      }
      String name = row.path(0).asString("");
      String kind = row.path(1).asString("BASE TABLE").equals("VIEW") ? "view" : "table";
      tables.addObject().put("table", name).put("kind", kind);
      names.add(name + ("view".equals(kind) ? " (view)" : ""));
      return true;
    });
    ToolResult r = ToolResult.json(out, names.isEmpty() ? "No tables in " + catalog + "." + schema
        + (like == null ? "" : " like " + like) + "." : catalog + "." + schema + ": " + String.join(", ", names)
        + (more[0] ? "\nMore tables: narrow with like or raise limit." : ""));
    return more[0] ? r.truncated("row_limit") : r;
  }

  private ToolResult describeTable(Call c, JsonNode args) throws ConnectorException {
    String[] t = table(args.path("table").asString(null), c.settings());
    if (t == null) {
      throw ConnectorException.badInput("Name a table: catalog.schema.table, or schema.table in the default "
          + "catalog.");
    }
    allowed(c, t[0]);
    ObjectNode out = F.objectNode().put("table", t[0] + "." + t[1] + "." + t[2]);
    ArrayNode columns = out.putArray("columns");
    List<String> text = new ArrayList<>();
    int cell = c.ctx().limits().maxCellChars();
    http.run(c, "DESCRIBE " + quoted(t[0]) + "." + quoted(t[1]) + "." + quoted(t[2]), Purpose.READ,
        (cols, row) -> {
          ObjectNode col = columns.addObject().put("name", row.path(0).asString(""))
              .put("type", row.path(1).asString(""));
          String extra = row.path(2).asString("");
          if (!extra.isBlank()) {
            col.put("extra", extra);
          }
          String comment = row.path(3).asString("");
          if (!comment.isBlank()) {
            col.set("comment", Cells.text(comment, Math.min(cell, 2_000)));
          }
          text.add(col.path("name").asString() + " " + col.path("type").asString());
          return columns.size() < 2_000;
        });
    try {
      String[] comment = new String[1];
      http.run(c, "SELECT comment FROM system.metadata.table_comments WHERE catalog_name = " + literal(t[0])
          + " AND schema_name = " + literal(t[1]) + " AND table_name = " + literal(t[2]), Purpose.READ,
          (cols, row) -> {
            comment[0] = row.path(0).asString(null);
            return false;
          });
      if (comment[0] != null && !comment[0].isBlank()) {
        out.set("comment", Cells.text(comment[0], Math.min(cell, 2_000)));
      }
    } catch (ConnectorException e) {
      // The table's comment is extra: a user without access to system.metadata still describes the table.
    }
    return ToolResult.json(out, out.path("table").asString() + ": " + String.join(", ", text)
        + (out.has("comment") ? " · " + out.path("comment").asString() : ""));
  }

  /** Defence in depth: {@link #execute} gets no policy, so it checks the catalog against the connection again. */
  private static void allowed(Call c, String catalog) throws ConnectorException {
    if (catalog == null || !NAME.matcher(catalog).matches()) {
      throw ConnectorException.badInput("Name a catalog. Call trino_list_catalogs to see the catalogs.");
    }
    if (!c.settings().allows(catalog)) {
      throw ConnectorException.denied(catalog + " isn't one of the catalogs of this connection.");
    }
  }

  /** Rows of a read, up to its limit and the response budget; one more row says there are more. */
  private static final class Collected implements TrinoHttp.Rows {
    final int limit;
    final long budget;
    final int cell;
    final ArrayNode rows = F.arrayNode();
    long used;
    String cut;

    Collected(int limit, long budget, int cell) {
      this.limit = limit;
      this.budget = budget;
      this.cell = cell;
    }

    @Override
    public boolean take(JsonNode columns, JsonNode row) {
      if (rows.size() >= limit) {
        cut = "row_limit";
        return false;
      }
      ArrayNode cells = Cells.row(row, columns, cell);
      long size = cells.toString().length();
      if (used + size > budget && !rows.isEmpty()) {
        cut = "size_limit";
        return false;
      }
      used += size;
      rows.add(cells);
      return true;
    }
  }

  private ToolResult query(Call c, JsonNode args) throws ConnectorException {
    ExecContext ctx = c.ctx();
    TrinoSettings s = c.settings();
    TrinoSql.Statement st = TrinoSql.read(args.path("sql").asString(""));
    if (st.kind() != Kind.READ) {
      throw ConnectorException.denied("trino_query only runs one statement the gateway reads as a read.");
    }
    String problem = refs(st, s, ctx.env() == null ? "this environment" : ctx.env().id());
    if (problem != null) {
      throw ConnectorException.denied(problem);
    }
    IoPlan plan = plan(c, st.planned());
    checkRead(plan, s);
    int limit = limit(args, DEFAULT_LIMIT, ctx.limits().maxRows());
    long budget = Math.max(4_096, ctx.limits().maxResponseBytes() * 3 / 4 - 2_048);
    Collected rows = new Collected(limit, budget, ctx.limits().maxCellChars());
    TrinoHttp.Result r = http.run(c, st.sql(), Purpose.READ, rows);
    ObjectNode facts = F.objectNode();
    if (r.queryId() != null) {
      facts.put("queryId", r.queryId());
    }
    JsonNode stats = r.stats();
    if (stats != null && stats.path("processedRows").canConvertToLong()) {
      facts.put("processedRows", stats.get("processedRows").asLong());
    }
    if (stats != null && stats.path("processedBytes").canConvertToLong()) {
      facts.put("processedBytes", stats.get("processedBytes").asLong());
    }
    if (!plan.inputs().isEmpty()) {
      ArrayNode read = facts.putArray("tablesRead");
      plan.inputNames().stream().limit(50).forEach(read::add);
    }
    String cut = rows.cut != null ? rows.cut : "time".equals(r.cut()) ? "timeout" : null;
    ToolResult.Table table = new ToolResult.Table(Cells.columns(r.columns()), rows.rows, null);
    if (cut == null) {
      return ToolResult.table(table, facts);
    }
    String note = "timeout".equals(cut)
        ? s.engine().title + " didn't finish within the call's time, so GateRook cancelled the query after "
            + rows.rows.size() + (rows.rows.size() == 1 ? " row" : " rows") + "."
        : "GateRook stopped the query at " + rows.rows.size() + (rows.rows.size() == 1 ? " row" : " rows")
            + " and cancelled it.";
    facts.put("more", note + " " + s.engine().title + " can't resume a query: to see further rows, narrow it (WHERE, "
        + "fewer columns, ORDER BY with LIMIT and OFFSET) and run it again.");
    return new ToolResult(List.of(ToolResult.Content.text(facts.get("more").asString())), facts, false, table, true,
        cut, List.of(), 0, 0, null);
  }

  private ToolResult change(Call c) throws ConnectorException {
    ExecContext ctx = c.ctx();
    if (!ctx.approved()) {
      throw ConnectorException.denied("Not approved: trino_execute changes data, so a person approves it first.");
    }
    JsonNode payload = ctx.approvedPayload().orElseThrow(() -> ConnectorException.denied("The approved action "
        + "carries no statement."));
    TrinoSql.Statement st = TrinoSql.read(payload.path("sql").asString(""));
    if (st.kind() != Kind.CHANGE) {
      throw ConnectorException.denied("trino_execute runs one analyzed change.");
    }
    String problem = refs(st, c.settings(), ctx.env() == null ? "this environment" : ctx.env().id());
    if (problem != null) {
      throw ConnectorException.denied(problem);
    }
    // Checked again right before it runs: the read of what it reads, never the write itself.
    if (st.planned() != null) {
      checkChange(plan(c, st.planned()), c.settings());
    } else {
      definition(c, st);
    }
    TrinoHttp.Result r = http.run(c, st.sql(), Purpose.CHANGE, (columns, row) -> true);
    ObjectNode out = F.objectNode().put("committed", true).put("statement", st.type())
        .put("target", st.target().name().text());
    if (r.queryId() != null) {
      out.put("queryId", r.queryId());
    }
    if (r.updateType() != null) {
      out.put("updateType", r.updateType());
    }
    String text;
    if (r.updateCount() != null) {
      out.put("rowsAffected", r.updateCount());
      text = r.updateCount() + (r.updateCount() == 1 ? " row" : " rows") + " written by " + st.type() + " on "
          + st.target().name().text() + ".";
    } else {
      text = st.type() + " on " + st.target().name().text() + " done.";
    }
    return ToolResult.json(out, text);
  }

  // ── Test connection, health, describe ───────────────────────────────────────────────────────────────────────

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    TrinoSettings s;
    try {
      s = TrinoSettings.of(cfg);
    } catch (ConnectorException e) {
      return TestResult.failure("trino.settings", e.getMessage(), null);
    }
    Call c = new Call(cfg, s, secrets, ctx);
    List<TestResult.Check> checks = new ArrayList<>();
    long started = System.nanoTime();
    String version;
    try {
      JsonNode info = http.info(c);
      version = info.path("nodeVersion").path("version").asString("");
      if (info.path("starting").asBoolean(false)) {
        return TestResult.failure("trino.starting", s.engine().title + " at " + s.address() + " is still starting. "
            + "Test again in a moment.", null);
      }
    } catch (ConnectorException e) {
      return TestResult.failure("trino.unreachable", e.getMessage(), null);
    }
    long latency = ms(started);
    checks.add(new TestResult.Check("coordinator", true, latency, s.engine().title + " " + version + " at "
        + s.address()));
    String identity;
    long signedIn = System.nanoTime();
    try {
      String[] user = new String[1];
      http.run(c, "SELECT current_user", Purpose.READ, (columns, row) -> {
        user[0] = row.path(0).asString(null);
        return true;
      });
      identity = Objects.requireNonNullElse(user[0], Objects.requireNonNullElse(s.user(), "?"));
    } catch (ConnectorException e) {
      return TestResult.failure(e.kind() == ConnectorException.Kind.RESOURCE_UNAVAILABLE ? "trino.credentials"
          : "trino.query", e.getMessage(), null).withChecks(checks);
    }
    checks.add(new TestResult.Check("sign-in", true, ms(signedIn), "Runs statements as " + identity));
    long listed = System.nanoTime();
    List<String> visible = new ArrayList<>();
    try {
      for (String name : names(c, "SHOW CATALOGS", 10_000, new boolean[1])) {
        if (s.allows(name)) {
          visible.add(name);
        }
      }
    } catch (ConnectorException e) {
      return TestResult.failure("trino.catalogs", "Signed in, but SHOW CATALOGS failed: " + e.getMessage(), null)
          .withChecks(checks);
    }
    checks.add(new TestResult.Check("catalogs", true, ms(listed), visible.size() + (visible.size() == 1 ? " catalog"
        : " catalogs") + " agents may use" + (visible.isEmpty() ? "" : ": " + String.join(", ", visible.subList(0,
        Math.min(5, visible.size()))) + (visible.size() > 5 ? ", …" : ""))));
    ObjectNode details = F.objectNode();
    if (s.catalogs().isEmpty()) {
      details.put("catalogs", "Every catalog the user may read");
    } else {
      ArrayNode list = details.putArray("catalogs");
      s.catalogs().forEach(list::add);
    }
    TestResult.Permissions permissions = new TestResult.Permissions(TestResult.Level.UNKNOWN,
        "SELECT, SHOW, DESCRIBE and EXPLAIN that Trino's IO plan calls reads of " + (s.catalogs().isEmpty()
            ? "every catalog the user may read" : "the listed catalogs"), null, List.of(), details);
    TestResult result = TestResult.success(latency, s.engine().title + " " + version, identity, permissions)
        .withChecks(checks)
        .withMetadata(F.objectNode().put("serverVersion", s.engine().title + " " + version)
            .put("engine", s.engine().title));
    for (Hint h : hints(cfg.env(), s, visible)) {
      result = result.withHint(h);
    }
    return result;
  }

  static List<Hint> hints(Env env, TrinoSettings s, List<String> visible) {
    List<Hint> out = new ArrayList<>();
    out.add(new Hint(Severity.INFO, "trino.read-only-boundary", null, s.engine().title + " has no read-only session: "
        + "GateRook runs only what Trino's IO plan calls a read of the connection's catalogs, and the user's access "
        + "control in " + s.engine().title + " is the boundary. Give this user read access only."));
    if (s.auth() == TrinoSettings.Auth.NONE) {
      out.add(new Hint(env == Env.DEV ? Severity.INFO : Severity.WARNING, "trino.no-authentication", "/auth/mode",
          "Without authentication " + s.engine().title + " believes any user name it is sent. Sign in with a "
              + "password or a JWT outside dev."));
    }
    if (!"https".equals(s.base().getScheme())) {
      out.add(new Hint(Severity.INFO, "trino.plain-http", "/url", "The coordinator is reached over plain HTTP on "
          + "this computer."));
    }
    if (visible.isEmpty()) {
      out.add(new Hint(Severity.WARNING, "trino.no-catalogs", "/catalogs", "The user sees no catalog"
          + (s.catalogs().isEmpty() ? "" : " matching the connection's list") + "."));
    } else if (s.catalog() != null && visible.stream().noneMatch(v -> v.equalsIgnoreCase(s.catalog()))) {
      out.add(new Hint(Severity.WARNING, "trino.default-catalog", "/catalog", "The user doesn't see the default "
          + "catalog " + s.catalog() + "."));
    }
    if (s.engine() == TrinoSettings.Engine.PRESTO) {
      out.add(new Hint(Severity.INFO, "trino.presto", "/engine", "GateRook's checks were verified against Trino; "
          + "Presto's IO plan names its tables the same way."));
    }
    return out;
  }

  @Override
  public HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    try {
      TrinoSettings s = TrinoSettings.of(cfg);
      long started = System.nanoTime();
      JsonNode info = http.info(new Call(cfg, s, secrets, ctx));
      if (info.path("starting").asBoolean(false)) {
        return HealthResult.unreachable(s.engine().title + " is starting.");
      }
      return HealthResult.healthy(ms(started));
    } catch (ConnectorException e) {
      return HealthResult.unreachable(e.getMessage());
    }
  }

  @Override
  public Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object) {
    ObjectNode out = F.objectNode().put("engine", "presto".equals(cfg.string("/engine")) ? "Presto" : "Trino");
    if (cfg.string("/catalog") != null) {
      out.put("defaultCatalog", cfg.string("/catalog"));
    }
    if (cfg.string("/schema") != null) {
      out.put("defaultSchema", cfg.string("/schema"));
    }
    List<String> catalogs = cfg.strings("/catalogs");
    if (catalogs.isEmpty()) {
      out.put("catalogs", "Every catalog the user may read");
    } else {
      ArrayNode a = out.putArray("catalogs");
      catalogs.forEach(a::add);
    }
    out.put("reads", "One SELECT, WITH, VALUES, SHOW, DESCRIBE or EXPLAIN per trino_query, checked by Trino's IO "
        + "plan; name tables as catalog.schema.table and quote names with double quotes. Results end at the row "
        + "limit: narrow a query to see further rows");
    out.put("changes", "One INSERT, UPDATE, DELETE, MERGE, CREATE, ALTER, DROP, COMMENT, TRUNCATE, ANALYZE or "
        + "REFRESH per trino_execute, after approval");
    if (object != null && !object.isBlank()) {
      out.put("table", "Call trino_describe_table with table " + object + ".");
    }
    return Optional.of(out);
  }

  @Override
  public void onConnectionChanged(String connectionId) {
    http.evict(connectionId);
  }

  @Override
  public void onConnectionRemoved(String connectionId) {
    http.evict(connectionId);
  }

  @Override
  public void close() {
    http.close();
  }

  private static long ms(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
