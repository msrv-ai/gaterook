package com.gaterook.connectors.spanner;

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
import com.gaterook.connectors.cloudauth.CloudCredentials;
import com.gaterook.connectors.cloudauth.CloudLocal;
import com.gaterook.connectors.cloudauth.GcpSpec;
import com.gaterook.connectors.support.BuiltIn;
import com.gaterook.connectors.support.OutputSanitizer;
import com.gaterook.connectors.support.SchemaResources;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Google Cloud Spanner (docs/spanner.md): one database per connection, its tables, and GoogleSQL or PostgreSQL-dialect
 * reads; {@code spanner_execute} runs one DML or DDL statement a person approved.
 *
 * <p>{@link #classify} reads the text by both dialects' tokens ({@link SpannerSql}) and picks the route without I/O.
 * The guarantee of a read comes from Spanner itself: it runs in a single-use read-only transaction, in which Spanner
 * refuses DML, and DDL never goes through a query. A DML change runs in its own read-write transaction, committed once
 * and never retried; a DDL change is one schema change whose operation id derives from the approval, so the same
 * approval can't apply it twice. A connection may name a fine-grained access control role, which every session takes.
 */
public final class SpannerConnector implements Connector {

  static final String TYPE = "spanner";
  static final int DEFAULT_LIMIT = 100;
  /** The most rows a cursor may skip to reach its page: farther pages are a query of their own. */
  static final long MAX_SKIP = 100_000;
  static final int MAX_TABLES = 1_000;
  static final int MAX_DDL_CHARS = 20_000;
  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  /** What Google's own client asks to learn a database's dialect: valid in both dialects. */
  static final String DIALECT_SQL = "SELECT 'POSTGRESQL' AS DIALECT FROM INFORMATION_SCHEMA.SCHEMATA WHERE "
      + "SCHEMA_NAME = 'information_schema' UNION ALL SELECT 'GOOGLE_STANDARD_SQL' AS DIALECT FROM "
      + "INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = 'INFORMATION_SCHEMA' AND CATALOG_NAME = ''";
  private static final Pattern STALE_SNAPSHOT = Pattern.compile("(?i)(read timestamp|version_retention|too old|"
      + "snapshot)");

  private final CloudCredentials cloud;
  private final CloudLocal local;
  private final SpannerApi api;
  private final ConnectorManifest manifest;
  /** Each connection's dialect, by connection id and version: whether its database is PostgreSQL-dialect. */
  private final Map<String, Boolean> dialects = new ConcurrentHashMap<>();

  public SpannerConnector(CloudCredentials cloud) {
    this(cloud, new CloudLocal(Objects.requireNonNull(cloud, "cloud").endpoints()));
  }

  /** @param local what this computer's gcloud knows, for the form's suggestions (tests: their own files) */
  SpannerConnector(CloudCredentials cloud, CloudLocal local) {
    this.cloud = Objects.requireNonNull(cloud, "cloud");
    this.local = Objects.requireNonNull(local, "local");
    this.api = new SpannerApi(cloud);
    SchemaResources schemas = SchemaResources.of(SpannerConnector.class, TYPE);
    this.manifest = ConnectorManifest.builder(TYPE, BuiltIn.VERSION, ConnectorManifest.Kind.BUILT_IN)
        .name("Google Cloud Spanner")
        .icon("database")
        .group("Databases")
        .description("Tables and GoogleSQL or PostgreSQL queries in read-only transactions. Changes wait for approval.")
        .configSchema(schemas.load("config"))
        .tools(SpannerTools.definitions(schemas))
        .access(new ConnectorManifest.RequiredAccess(List.of("spanner.googleapis.com", "oauth2.googleapis.com",
            "iamcredentials.googleapis.com"), List.of(),
            List.of("~/.config/gcloud/application_default_credentials.json")))
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
      SpannerSettings.of(cfg);
    } catch (ConnectorException e) {
      return Classification.deny(policy.rule("bad-config"), e.getMessage(), "Complete the connection's settings.");
    }
    switch (call.tool()) {
      case SpannerTools.LIST_TABLES -> {
        String schema = call.string("schema");
        if (schema != null && !schema.isEmpty() && !SpannerSettings.NAME.matcher(schema).matches()) {
          return Classification.deny(policy.rule("bad-argument"), schema + " isn't a schema name.",
              "Leave schema out to list every table.");
        }
        return Classification.read();
      }
      case SpannerTools.DESCRIBE_TABLE -> {
        if (SpannerSettings.table(call.string("table")) == null) {
          return Classification.deny(policy.rule("bad-argument"), "Name a table: table, or schema.table.",
              "Call spanner_list_tables to see the tables.");
        }
        return Classification.read();
      }
      case SpannerTools.QUERY, SpannerTools.EXECUTE -> {
        String problem = Values.problem(call.arguments().path("parameters"));
        if (problem != null) {
          return Classification.deny(policy.rule("bad-argument"), problem, null);
        }
        SpannerSql.Statement st = SpannerSql.read(call.string("sql"));
        boolean read = SpannerTools.QUERY.equals(call.tool());
        return switch (st.kind()) {
          case UNREADABLE -> Classification.deny(policy.rule("unparsed"), st.problem() + " The gateway doesn't run "
              + "what it can't read.", "Rewrite it as one plain statement.");
          case FORBIDDEN -> Classification.deny(policy.rule("forbidden"), st.problem(), null);
          case READ -> read ? Classification.read() : Classification.deny(policy.rule("execute-read"), "This is a "
              + "read.", "Use spanner_query; it doesn't need approval.");
          case DML, DDL -> {
            if (read) {
              yield Classification.deny(policy.rule("query-writes"), "spanner_query only reads; this is "
                  + st.type() + ".", "Use spanner_execute: a person approves the exact statement.");
            }
            ObjectNode facts = F.objectNode().put("statement", st.type())
                .put("kind", st.kind() == SpannerSql.Kind.DDL ? "schema" : "data");
            if (st.target() != null) {
              facts.put("target", st.target());
            }
            yield Classification.mutation(st.type() + (st.target() == null ? "" : " on " + st.target())
                + (st.kind() == SpannerSql.Kind.DDL ? " changes the schema" : " changes data"), facts);
          }
        };
      }
      default -> {
        return Classification.deny(policy.rule("unknown-tool"), manifest.name() + " has no tool " + call.tool() + ".",
            null);
      }
    }
  }

  // ── The exact action ────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    String where = cfg.name() + " · " + cfg.string("/project") + " · " + cfg.string("/instance") + "/"
        + cfg.string("/database") + (cfg.string("/advanced/databaseRole") == null ? ""
        : " · role " + cfg.string("/advanced/databaseRole"));
    JsonNode parameters = call.arguments().path("parameters");
    List<Field> fields = new ArrayList<>();
    if (parameters.isArray() && !parameters.isEmpty()) {
      fields.add(new Field("Parameters", shorten(parameters.toString(), 2_000)));
    }
    if (SpannerTools.EXECUTE.equals(call.tool())) {
      String sql = Objects.requireNonNullElse(call.string("sql"), "");
      SpannerSql.Statement st = SpannerSql.read(sql);
      boolean ddl = st.kind() == SpannerSql.Kind.DDL;
      ObjectNode payload = F.objectNode().put("sql", sql);
      if (parameters.isArray()) {
        payload.set("parameters", parameters.deepCopy());
      }
      return ActionPreview.of(ActionPreview.Kind.SQL, "sql", sql, where, shorten(SpannerTools.EXECUTE + " on "
              + cfg.name() + ": " + st.type() + (st.target() == null ? "" : " " + st.target()), 120))
          .withFields(fields)
          .withImpact(List.of(new Field("Target", st.target() == null ? "Unknown: not analyzed" : st.target()),
              new Field("Transaction", ddl ? "A schema change: Spanner applies it as one operation, which GateRook "
                  + "can't undo" : "One statement in its own read-write transaction; Spanner commits it whole or not "
                  + "at all"),
              new Field("Retried", "No: a change whose answer is lost is reported as unknown")))
          .withFacets(Map.of("statement", st.type(), "target", Objects.requireNonNullElse(st.target(), "")))
          .withPayload(payload);
    }
    // A read tool an endpoint put in Approval: its arguments are the whole action.
    ObjectNode args = call.arguments().deepCopy();
    if (SpannerTools.QUERY.equals(call.tool())) {
      return ActionPreview.of(ActionPreview.Kind.SQL, "sql", Objects.requireNonNullElse(call.string("sql"), ""),
              where, shorten(call.tool() + " on " + cfg.name(), 120))
          .withFields(fields)
          .withPayload(F.objectNode().set("arguments", args))
          .withFacets(Map.of("tool", call.tool()))
          .withImpact(List.of(new Field("Reads", "Rows in a read-only transaction, no change")));
    }
    return ActionPreview.of(ActionPreview.Kind.JSON, "json", args.toString(), where,
            shorten(call.tool() + " on " + cfg.name(), 120))
        .withPayload(F.objectNode().set("arguments", args))
        .withFacets(Map.of("tool", call.tool()))
        .withImpact(List.of(new Field("Reads", "Schema, no change")));
  }

  private static String shorten(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }

  // ── Execution ───────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    SpannerSettings s = SpannerSettings.of(cfg);
    SpannerApi.Call c = new SpannerApi.Call(cfg, s, secrets, ctx);
    return switch (call.tool()) {
      case SpannerTools.LIST_TABLES -> listTables(c, arguments(call, ctx));
      case SpannerTools.DESCRIBE_TABLE -> describeTable(c, arguments(call, ctx));
      case SpannerTools.QUERY -> query(c, arguments(call, ctx));
      case SpannerTools.EXECUTE -> change(c);
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

  /** A session of the connection's database, in the connection's database role. */
  private String openSession(SpannerApi.Call c) throws ConnectorException {
    ObjectNode body = F.objectNode();
    ObjectNode session = body.putObject("session");
    session.putObject("labels").put("client", "gaterook");
    if (c.settings().databaseRole() != null) {
      session.put("creatorRole", c.settings().databaseRole());
    }
    JsonNode r = api.post(c, c.database() + "/sessions", body, "Spanner sessions.create", false);
    return c.child(r.path("name").asString(""));
  }

  /** Deletes a session; Spanner removes an idle one after an hour anyway. */
  private void closeSession(SpannerApi.Call c, String session) {
    if (session == null) {
      return;
    }
    try {
      api.delete(c, session, "Spanner sessions.delete");
    } catch (ConnectorException e) {
      // The session ends on Spanner's side when it has been idle for an hour.
    }
  }

  /** A whole result of a short metadata query in a single-use read-only transaction. */
  private JsonNode select(SpannerApi.Call c, String session, String sql, ObjectNode params, ObjectNode types,
      String what) throws ConnectorException {
    ObjectNode body = F.objectNode().put("sql", sql);
    body.putObject("transaction").putObject("singleUse").putObject("readOnly").put("strong", true);
    if (params != null && !params.isEmpty()) {
      body.set("params", params);
      body.set("paramTypes", types);
    }
    body.putObject("requestOptions").put("requestTag", "gaterook-schema");
    return api.post(c, session + ":executeSql", body, what, false);
  }

  /** Whether the connection's database is PostgreSQL-dialect: asked once per connection version. */
  private boolean postgres(SpannerApi.Call c, String session) throws ConnectorException {
    String key = c.cfg().id() + ":" + c.cfg().version();
    Boolean known = dialects.get(key);
    if (known != null) {
      return known;
    }
    JsonNode r = select(c, session, DIALECT_SQL, null, null, "Spanner dialect query");
    boolean pg = "POSTGRESQL".equals(r.path("rows").path(0).path(0).asString(""));
    dialects.put(key, pg);
    return pg;
  }

  /**
   * Schema queries of both dialects: GoogleSQL's upper-case catalog and {@code @name} parameters, or PostgreSQL's
   * lower-case one and {@code $1}; {@code params} as {@code [name, value]} pairs in order.
   */
  private record Query(String sql, ObjectNode params, ObjectNode types) {

    static Query of(boolean postgres, String googleSql, String postgresSql, String... params) {
      ObjectNode values = F.objectNode();
      ObjectNode types = F.objectNode();
      for (int i = 0; i + 1 < params.length; i += 2) {
        String name = postgres ? "p" + (i / 2 + 1) : params[i];
        values.put(name, params[i + 1]);
        types.putObject(name).put("code", "STRING");
      }
      return new Query(postgres ? postgresSql : googleSql, values, types);
    }
  }

  private static String defaultSchema(boolean postgres) {
    return postgres ? "public" : "";
  }

  /** {@code name}, or {@code schema.name} outside the default schema. */
  private static String qualified(String schema, String name, boolean postgres) {
    return schema == null || schema.isEmpty() || schema.equals(defaultSchema(postgres)) ? name : schema + "." + name;
  }

  private static String text(JsonNode cell) {
    return cell == null || cell.isNull() || cell.isMissingNode() ? null : cell.asString("");
  }

  /** Spanner's BOOL, or PostgreSQL's {@code YES}/{@code NO} text of the same column. */
  private static boolean yes(JsonNode cell) {
    return cell != null && (cell.isBoolean() ? cell.asBoolean() : "YES".equalsIgnoreCase(cell.asString(""))
        || "true".equalsIgnoreCase(cell.asString("")));
  }

  private ToolResult listTables(SpannerApi.Call c, JsonNode args) throws ConnectorException {
    String schema = args.path("schema").asString(null);
    if (schema != null && (schema.isEmpty() || !SpannerSettings.NAME.matcher(schema).matches())) {
      schema = null;
    }
    int limit = Math.max(1, Math.min(args.path("limit").asInt(200), MAX_TABLES));
    String session = openSession(c);
    try {
      boolean pg = postgres(c, session);
      String where = schema == null ? "" : pg ? " AND table_schema = $1" : " AND TABLE_SCHEMA = @schema";
      Query q = Query.of(pg,
          "SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE, PARENT_TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE "
              + "TABLE_SCHEMA NOT IN ('INFORMATION_SCHEMA', 'SPANNER_SYS')" + where
              + " ORDER BY TABLE_SCHEMA, TABLE_NAME LIMIT " + (limit + 1),
          "SELECT table_schema, table_name, table_type, parent_table_name FROM information_schema.tables WHERE "
              + "table_schema NOT IN ('information_schema', 'spanner_sys', 'pg_catalog')" + where
              + " ORDER BY table_schema, table_name LIMIT " + (limit + 1),
          schema == null ? new String[0] : new String[] {"schema", schema});
      JsonNode r = select(c, session, q.sql(), q.params(), q.types(), "Spanner tables query");
      ObjectNode out = F.objectNode().put("database", c.settings().label()).put("dialect", pg ? "PostgreSQL"
          : "GoogleSQL");
      ArrayNode tables = out.putArray("tables");
      List<String> names = new ArrayList<>();
      int n = 0;
      for (JsonNode row : r.path("rows")) {
        if (n++ >= limit) {
          out.put("more", true);
          break;
        }
        String name = qualified(text(row.path(0)), text(row.path(1)), pg);
        String kind = "VIEW".equalsIgnoreCase(text(row.path(2))) ? "view" : "table";
        ObjectNode t = tables.addObject().put("table", name).put("kind", kind);
        String parent = text(row.path(3));
        if (parent != null && !parent.isEmpty()) {
          t.put("parent", parent);
        }
        names.add(name + ("view".equals(kind) ? " (view)" : parent == null || parent.isEmpty() ? ""
            : " (in " + parent + ")"));
      }
      return ToolResult.json(out, names.isEmpty() ? "No tables the credentials may read in " + c.settings().label()
          + "." : names.size() + (names.size() == 1 ? " table: " : " tables: ") + String.join(", ", names)
          + (out.has("more") ? "\nMore tables: pass schema or a larger limit." : ""));
    } finally {
      closeSession(c, session);
    }
  }

  private ToolResult describeTable(SpannerApi.Call c, JsonNode args) throws ConnectorException {
    String[] t = SpannerSettings.table(args.path("table").asString(null));
    if (t == null) {
      throw ConnectorException.badInput("Name a table: table, or schema.table.");
    }
    String session = openSession(c);
    try {
      boolean pg = postgres(c, session);
      String schema = t[0] == null ? defaultSchema(pg) : t[0];
      String name = t[1];
      Query table = Query.of(pg,
          "SELECT TABLE_TYPE, PARENT_TABLE_NAME, ON_DELETE_ACTION FROM INFORMATION_SCHEMA.TABLES WHERE "
              + "TABLE_SCHEMA = @schema AND TABLE_NAME = @name",
          "SELECT table_type, parent_table_name, on_delete_action FROM information_schema.tables WHERE "
              + "table_schema = $1 AND table_name = $2",
          "schema", schema, "name", name);
      JsonNode info = select(c, session, table.sql(), table.params(), table.types(), "Spanner table query")
          .path("rows").path(0);
      if (info.isMissingNode()) {
        throw ConnectorException.badInput("No table " + qualified(schema, name, pg) + " that the credentials may "
            + "read. Call spanner_list_tables to see the tables.");
      }
      Query columns = Query.of(pg,
          "SELECT COLUMN_NAME, SPANNER_TYPE, IS_NULLABLE, CAST(COLUMN_DEFAULT AS STRING), GENERATION_EXPRESSION FROM "
              + "INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @schema AND TABLE_NAME = @name "
              + "ORDER BY ORDINAL_POSITION",
          "SELECT column_name, spanner_type, is_nullable, column_default, generation_expression FROM "
              + "information_schema.columns WHERE table_schema = $1 AND table_name = $2 ORDER BY ordinal_position",
          "schema", schema, "name", name);
      Query indexes = Query.of(pg,
          "SELECT i.INDEX_NAME, i.INDEX_TYPE, i.IS_UNIQUE, i.IS_NULL_FILTERED, c.COLUMN_NAME, c.ORDINAL_POSITION, "
              + "c.COLUMN_ORDERING FROM INFORMATION_SCHEMA.INDEXES AS i JOIN INFORMATION_SCHEMA.INDEX_COLUMNS AS c "
              + "ON c.TABLE_SCHEMA = i.TABLE_SCHEMA AND c.TABLE_NAME = i.TABLE_NAME AND c.INDEX_NAME = i.INDEX_NAME "
              + "WHERE i.TABLE_SCHEMA = @schema AND i.TABLE_NAME = @name "
              + "ORDER BY i.INDEX_NAME, c.ORDINAL_POSITION",
          "SELECT i.index_name, i.index_type, i.is_unique, i.is_null_filtered, c.column_name, c.ordinal_position, "
              + "c.column_ordering FROM information_schema.indexes AS i JOIN information_schema.index_columns AS c "
              + "ON c.table_schema = i.table_schema AND c.table_name = i.table_name AND c.index_name = i.index_name "
              + "WHERE i.table_schema = $1 AND i.table_name = $2 ORDER BY i.index_name, c.ordinal_position",
          "schema", schema, "name", name);
      ObjectNode out = F.objectNode().put("table", qualified(schema, name, pg))
          .put("kind", "VIEW".equalsIgnoreCase(text(info.path(0))) ? "view" : "table");
      String parent = text(info.path(1));
      if (parent != null && !parent.isEmpty()) {
        out.put("parent", parent);
        String onDelete = text(info.path(2));
        if (onDelete != null && !onDelete.isEmpty()) {
          out.put("onDelete", onDelete);
        }
      }
      ArrayNode cols = out.putArray("columns");
      List<String> colText = new ArrayList<>();
      for (JsonNode row : select(c, session, columns.sql(), columns.params(), columns.types(),
          "Spanner columns query").path("rows")) {
        ObjectNode col = cols.addObject().put("name", text(row.path(0))).put("type", text(row.path(1)))
            .put("nullable", yes(row.path(2)));
        String def = text(row.path(3));
        if (def != null && !def.isEmpty()) {
          col.put("default", Values.text(def, 500).asString());
        }
        String gen = text(row.path(4));
        if (gen != null && !gen.isEmpty()) {
          col.put("generated", Values.text(gen, 500).asString());
        }
        colText.add(col.path("name").asString() + " " + col.path("type").asString()
            + (col.path("nullable").asBoolean() ? "" : " NOT NULL"));
      }
      Map<String, ObjectNode> byIndex = new LinkedHashMap<>();
      ArrayNode primaryKey = F.arrayNode();
      for (JsonNode row : select(c, session, indexes.sql(), indexes.params(), indexes.types(),
          "Spanner indexes query").path("rows")) {
        String index = text(row.path(0));
        String column = text(row.path(4));
        boolean key = !row.path(5).isNull();
        String ordering = text(row.path(6));
        String entry = column + ("DESC".equalsIgnoreCase(ordering) ? " DESC" : "");
        if ("PRIMARY_KEY".equalsIgnoreCase(text(row.path(1)))) {
          if (key) {
            primaryKey.add(entry);
          }
          continue;
        }
        ObjectNode idx = byIndex.computeIfAbsent(index, k -> {
          ObjectNode o = F.objectNode().put("name", k).put("unique", yes(row.path(2)))
              .put("nullFiltered", yes(row.path(3)));
          o.putArray("columns");
          return o;
        });
        if (key) {
          ((ArrayNode) idx.get("columns")).add(entry);
        } else {
          (idx.has("storing") ? (ArrayNode) idx.get("storing") : idx.putArray("storing")).add(column);
        }
      }
      out.set("primaryKey", primaryKey);
      ArrayNode idx = out.putArray("indexes");
      byIndex.values().forEach(idx::add);
      ddl(c, out, schema, name, pg);
      StringBuilder summary = new StringBuilder(out.path("table").asString()).append(" (")
          .append(out.path("kind").asString());
      if (out.has("parent")) {
        summary.append(", interleaved in ").append(out.get("parent").asString());
      }
      summary.append("): ").append(String.join(", ", colText));
      if (!primaryKey.isEmpty()) {
        List<String> pk = new ArrayList<>();
        primaryKey.forEach(k -> pk.add(k.asString()));
        summary.append(" · primary key (").append(String.join(", ", pk)).append(")");
      }
      if (!byIndex.isEmpty()) {
        summary.append(" · ").append(byIndex.size()).append(byIndex.size() == 1 ? " index" : " indexes");
      }
      return ToolResult.json(out, summary.toString());
    } finally {
      closeSession(c, session);
    }
  }

  /**
   * The table's DDL statements, where the credentials may read the database's DDL: its CREATE TABLE or VIEW, its
   * indexes and its ALTER TABLE statements. Left out when they may not; the columns above don't need it.
   */
  private void ddl(SpannerApi.Call c, ObjectNode out, String schema, String name, boolean pg) {
    JsonNode r;
    try {
      r = api.get(c, c.database() + "/ddl", "Spanner getDatabaseDdl");
    } catch (ConnectorException e) {
      out.put("ddlNote", "The credentials can't read the database's DDL (spanner.databases.getDdl).");
      return;
    }
    String full = qualified(schema, name, pg);
    ArrayNode statements = F.arrayNode();
    int chars = 0;
    for (JsonNode st : r.path("statements")) {
      String text = st.asString("");
      if (statementFor(text, full) && chars + text.length() <= MAX_DDL_CHARS) {
        statements.add(text);
        chars += text.length();
      }
    }
    if (!statements.isEmpty()) {
      out.set("ddl", statements);
    }
  }

  /** Whether a DDL statement creates, alters or indexes the table {@code name}. */
  static boolean statementFor(String ddl, String name) {
    String flat = ddl.replace("`", "").replace("\"", "").replaceAll("\\s+", " ").strip();
    String upper = flat.toUpperCase(Locale.ROOT);
    String target = Pattern.quote(name.toUpperCase(Locale.ROOT));
    return upper.matches("CREATE (OR REPLACE )?(TABLE|VIEW) (IF NOT EXISTS )?" + target + "\\b.*")
        || upper.matches("ALTER TABLE " + target + "\\b.*")
        || upper.matches("CREATE (UNIQUE )?(NULL_FILTERED )?(SEARCH |VECTOR )?INDEX (IF NOT EXISTS )?\\S+ ON "
            + target + "\\b.*");
  }

  private ToolResult query(SpannerApi.Call c, JsonNode args) throws ConnectorException {
    ExecContext ctx = c.ctx();
    String sql = args.path("sql").asString("");
    if (SpannerSql.read(sql).kind() != SpannerSql.Kind.READ) {
      throw ConnectorException.denied("spanner_query only runs one statement the gateway reads as a read.");
    }
    String problem = Values.problem(args.path("parameters"));
    if (problem != null) {
      throw ConnectorException.badInput(problem);
    }
    int limit = Math.max(1, Math.min(args.path("limit").asInt(DEFAULT_LIMIT), ctx.limits().maxRows()));
    ObjectNode request = F.objectNode().put("sql", sql);
    request.set("parameters", args.path("parameters").isArray() ? args.get("parameters") : F.arrayNode());
    String hash = Cursors.hash(request);
    String snapshot = null;
    long skip = 0;
    if (args.path("cursor").isString()) {
      JsonNode at = Cursors.open(ctx, SpannerTools.QUERY, args.get("cursor").asString(), c.cfg().id(), hash);
      snapshot = at.path("ts").asString(null);
      skip = Math.max(0, at.path("index").asLong(0));
      if (snapshot == null || skip > MAX_SKIP) {
        throw ConnectorException.badInput("This cursor goes past " + MAX_SKIP + " rows. Narrow the query instead, "
            + "for example WHERE key > the last key you saw, with ORDER BY key.");
      }
    }
    String session = openSession(c);
    try {
      boolean pg = postgres(c, session);
      ObjectNode body = F.objectNode().put("sql", sql);
      ObjectNode readOnly = body.putObject("transaction").putObject("singleUse").putObject("readOnly");
      if (snapshot == null) {
        readOnly.put("strong", true);
      } else {
        readOnly.put("readTimestamp", snapshot);
      }
      readOnly.put("returnReadTimestamp", true);
      Values.encode(args.path("parameters"), body, pg);
      body.putObject("requestOptions").put("requestTag", "gaterook-read");
      Page page = new Page(skip, limit, budget(ctx), ctx.limits().maxCellChars());
      ResultStream stream = new ResultStream(page);
      page.stream = stream;
      try {
        api.stream(c, session + ":executeStreamingSql", body, "Spanner query", stream);
      } catch (ConnectorException e) {
        if (snapshot != null && STALE_SNAPSHOT.matcher(Objects.requireNonNullElse(e.getMessage(), "")).find()) {
          throw ConnectorException.badInput("The cursor's snapshot is older than the database keeps old versions. "
              + "Start again without cursor.");
        }
        throw e;
      }
      JsonNode metadata = stream.metadata();
      if (metadata == null) {
        throw ConnectorException.resourceError("Spanner answered the query without its columns.", null);
      }
      String readTimestamp = metadata.path("transaction").path("readTimestamp").asString(snapshot);
      long shown = skip + page.rows.size();
      String next = null;
      if ((page.more || page.cut) && readTimestamp != null) {
        next = Cursors.seal(ctx, SpannerTools.QUERY, c.cfg().id(), hash, F.objectNode().put("ts", readTimestamp)
            .put("index", shown));
      }
      ObjectNode facts = F.objectNode();
      if (readTimestamp != null) {
        facts.put("readTimestamp", readTimestamp);
      }
      ToolResult result = ToolResult.table(new ToolResult.Table(List.copyOf(Values.columns(metadata.path("rowType"),
          pg)), page.rows, next), facts);
      return page.cut ? result.truncated("size_limit") : result;
    } finally {
      closeSession(c, session);
    }
  }

  /** The part of the answer rows may fill: three quarters of the response limit, less a margin. */
  private static long budget(ExecContext ctx) {
    return Math.max(4_096, ctx.limits().maxResponseBytes() * 3 / 4 - 2_048);
  }

  /** Collects one page of a streamed result: skips the rows of earlier pages, stops after the page and one more. */
  private static final class Page implements ResultStream.Rows {

    final ArrayNode rows = F.arrayNode();
    private final long skip;
    private final int limit;
    private final long budget;
    private final int maxCellChars;
    ResultStream stream;
    long seen;
    long used;
    boolean more;
    boolean cut;

    Page(long skip, int limit, long budget, int maxCellChars) {
      this.skip = skip;
      this.limit = limit;
      this.budget = budget;
      this.maxCellChars = maxCellChars;
    }

    @Override
    public boolean row(List<JsonNode> values) {
      seen++;
      if (seen <= skip) {
        return true;
      }
      if (rows.size() >= limit) {
        more = true;
        return false;
      }
      ArrayNode cells = Values.row(values, stream.metadata().path("rowType"), maxCellChars);
      used += cells.toString().length();
      if (used > budget && !rows.isEmpty()) {
        cut = true;
        return false;
      }
      rows.add(cells);
      return true;
    }
  }

  private ToolResult change(SpannerApi.Call c) throws ConnectorException {
    ExecContext ctx = c.ctx();
    if (!ctx.approved()) {
      throw ConnectorException.denied("Not approved: spanner_execute changes data, so a person approves it first.");
    }
    JsonNode payload = ctx.approvedPayload().orElseThrow(() -> ConnectorException.denied("The approved action "
        + "carries no statement."));
    String sql = payload.path("sql").asString("");
    SpannerSql.Statement st = SpannerSql.read(sql);
    if (st.kind() != SpannerSql.Kind.DML && st.kind() != SpannerSql.Kind.DDL) {
      throw ConnectorException.denied("spanner_execute runs one analyzed DML or DDL statement.");
    }
    String problem = Values.problem(payload.path("parameters"));
    if (problem != null) {
      throw ConnectorException.badInput(problem);
    }
    return st.kind() == SpannerSql.Kind.DDL ? schemaChange(c, sql, st) : dataChange(c, sql, st,
        payload.path("parameters"));
  }

  /** One DML statement in its own read-write transaction: begun, run, committed once. */
  private ToolResult dataChange(SpannerApi.Call c, String sql, SpannerSql.Statement st, JsonNode parameters)
      throws ConnectorException {
    String session = openSession(c);
    try {
      boolean pg = postgres(c, session);
      ObjectNode begin = F.objectNode();
      begin.putObject("options").putObject("readWrite");
      begin.putObject("requestOptions").put("transactionTag", "gaterook-change");
      String transaction = api.post(c, session + ":beginTransaction", begin, "Spanner beginTransaction", false)
          .path("id").asString("");
      if (transaction.isEmpty()) {
        throw ConnectorException.resourceError("Spanner began no transaction for the change.", null);
      }
      ObjectNode body = F.objectNode().put("sql", sql).put("seqno", "1");
      body.putObject("transaction").put("id", transaction);
      Values.encode(parameters, body, pg);
      body.putObject("requestOptions").put("requestTag", "gaterook-change");
      JsonNode result;
      try {
        // Nothing is committed before the commit below: a failure here changed nothing.
        result = api.post(c, session + ":executeSql", body, "Spanner change", false);
      } catch (ConnectorException e) {
        rollback(c, session, transaction);
        throw e;
      }
      ObjectNode commit = F.objectNode().put("transactionId", transaction).put("returnCommitStats", true);
      JsonNode committed = api.post(c, session + ":commit", commit, "Spanner commit", true);
      long affected = count(result.path("stats").path("rowCountExact"));
      ObjectNode out = F.objectNode().put("committed", true).put("statement", st.type()).put("rowsAffected", affected);
      String at = committed.path("commitTimestamp").asString(null);
      if (at != null) {
        out.put("commitTimestamp", at);
      }
      if (committed.path("commitStats").has("mutationCount")) {
        out.put("mutations", count(committed.path("commitStats").path("mutationCount")));
      }
      JsonNode rowType = result.path("metadata").path("rowType");
      if (rowType.path("fields").size() > 0 && result.path("rows").isArray() && !result.path("rows").isEmpty()) {
        ArrayNode returned = out.putArray("returned");
        int max = Math.min(result.path("rows").size(), c.ctx().limits().maxRows());
        for (int i = 0; i < max; i++) {
          List<JsonNode> values = new ArrayList<>();
          result.path("rows").get(i).forEach(values::add);
          returned.add(Values.row(values, rowType, c.ctx().limits().maxCellChars()));
        }
      }
      return ToolResult.json(out, affected + (affected == 1 ? " row" : " rows") + " affected by " + st.type()
          + (at == null ? "" : ", committed at " + at) + ".");
    } finally {
      closeSession(c, session);
    }
  }

  private void rollback(SpannerApi.Call c, String session, String transaction) {
    try {
      api.post(c, session + ":rollback", F.objectNode().put("transactionId", transaction), "Spanner rollback", false);
    } catch (ConnectorException e) {
      // An uncommitted transaction ends with its session; nothing of it was applied.
    }
  }

  /**
   * One DDL statement as a schema change. Its operation id derives from the approval, so sending the same approval
   * again finds the first operation (ALREADY_EXISTS) instead of applying it twice. It is followed until the call's
   * deadline; a longer change (an index backfill) goes on in Spanner and is reported as running.
   */
  private ToolResult schemaChange(SpannerApi.Call c, String sql, SpannerSql.Statement st) throws ConnectorException {
    String operationId = operationId(c.ctx());
    ObjectNode body = F.objectNode();
    body.putArray("statements").add(sql);
    body.put("operationId", operationId);
    JsonNode op = api.updateDdl(c, body, "Spanner schema change");
    String name = c.database() + "/operations/" + operationId;
    if (op == null || !op.path("name").isString()) {
      op = operation(c, name);
    } else {
      name = c.child(op.path("name").asString());
    }
    Duration pause = Duration.ofMillis(300);
    while (!op.path("done").asBoolean(false) && c.ctx().remaining().compareTo(Duration.ofSeconds(2)) > 0) {
      try {
        Thread.sleep(Math.min(pause.toMillis(), Math.max(1, c.ctx().remaining().minusSeconds(2).toMillis())));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      pause = pause.multipliedBy(2).compareTo(Duration.ofSeconds(2)) > 0 ? Duration.ofSeconds(2)
          : pause.multipliedBy(2);
      op = operation(c, name);
    }
    if (op.path("error").isObject()) {
      throw ConnectorException.resourceError("Spanner refused the schema change: " + OutputSanitizer.sanitize(
          SpannerApi.firstLine(op.path("error").path("message").asString(""))) + ". Nothing changed.", null);
    }
    boolean done = op.path("done").asBoolean(false);
    ObjectNode out = F.objectNode().put("statement", st.type()).put("applied", done).put("operation", name);
    return ToolResult.json(out, done ? st.type() + (st.target() == null ? "" : " on " + st.target()) + " applied."
        : "Spanner accepted the schema change and is still applying it (operation " + name + "). It goes on by "
            + "itself; spanner_describe_table shows the result when it is done.");
  }

  private JsonNode operation(SpannerApi.Call c, String name) throws ConnectorException {
    try {
      return api.get(c, c.child(name), "Spanner operations.get");
    } catch (ConnectorException e) {
      throw ConnectorException.outcomeUnknown("GateRook lost track of schema change " + name + ", so whether it was "
          + "applied is not known. Check the database's schema before retrying.", e);
    }
  }

  /** Spanner's operation id of an approved schema change: {@code gaterook_} and 32 hex digits of the approval. */
  static String operationId(ExecContext ctx) {
    String id = ctx.approvalId().orElse(ctx.callId());
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(("gaterook-spanner|" + id)
          .getBytes(StandardCharsets.UTF_8));
      return "gaterook_" + HexFormat.of().formatHex(digest, 0, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static long count(JsonNode n) {
    if (n == null || n.isMissingNode() || n.isNull()) {
      return 0;
    }
    if (n.isIntegralNumber()) {
      return n.asLong();
    }
    try {
      return Long.parseLong(n.asString("0").strip());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  // ── Test connection, health, describe ───────────────────────────────────────────────────────────────────────

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    SpannerSettings s;
    try {
      s = SpannerSettings.of(cfg);
    } catch (ConnectorException e) {
      return TestResult.failure("spanner.settings", e.getMessage(), null);
    }
    SpannerApi.Call c = new SpannerApi.Call(cfg, s, secrets, ctx);
    List<TestResult.Check> checks = new ArrayList<>();
    long started = System.nanoTime();
    String session;
    try {
      session = openSession(c);
    } catch (ConnectorException e) {
      return TestResult.failure(e.kind() == ConnectorException.Kind.RESOURCE_UNAVAILABLE ? "spanner.credentials"
          : "spanner.session", e.getMessage(), null);
    }
    long latency = ms(started);
    checks.add(new TestResult.Check("session", true, latency, "Opened a session on " + s.label()
        + (s.databaseRole() == null ? "" : " as database role " + s.databaseRole())));
    boolean pg;
    int visible = 0;
    boolean more;
    List<String> first = new ArrayList<>();
    try {
      long readStarted = System.nanoTime();
      dialects.remove(cfg.id() + ":" + cfg.version());
      pg = postgres(c, session);
      checks.add(new TestResult.Check("read", true, ms(readStarted), "Reads in a read-only transaction ("
          + (pg ? "PostgreSQL" : "GoogleSQL") + " dialect)"));
      Query q = Query.of(pg,
          "SELECT TABLE_SCHEMA, TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA NOT IN "
              + "('INFORMATION_SCHEMA', 'SPANNER_SYS') ORDER BY TABLE_SCHEMA, TABLE_NAME LIMIT 201",
          "SELECT table_schema, table_name FROM information_schema.tables WHERE table_schema NOT IN "
              + "('information_schema', 'spanner_sys', 'pg_catalog') ORDER BY table_schema, table_name LIMIT 201");
      JsonNode rows = select(c, session, q.sql(), q.params(), q.types(), "Spanner tables query").path("rows");
      for (JsonNode row : rows) {
        if (visible >= 200) {
          break;
        }
        visible++;
        if (first.size() < 5) {
          first.add(qualified(text(row.path(0)), text(row.path(1)), pg));
        }
      }
      more = rows.size() > 200;
    } catch (ConnectorException e) {
      return TestResult.failure("spanner.read", "Opened a session but can't read " + s.label() + ": "
          + e.getMessage(), null).withChecks(checks);
    } finally {
      closeSession(c, session);
    }
    checks.add(new TestResult.Check("tables", true, 0, visible + (more ? "+" : "") + (visible == 1 ? " table"
        : " tables") + " agents may read" + (first.isEmpty() ? "" : ": " + String.join(", ", first)
        + (visible > first.size() || more ? ", …" : ""))));
    TestResult.Permissions permissions = new TestResult.Permissions(TestResult.Level.UNKNOWN,
        "Queries in read-only transactions, as " + (s.databaseRole() == null ? "the credentials' IAM access"
            : "database role " + s.databaseRole()), null, List.of(), F.objectNode().put("dialect", pg ? "PostgreSQL"
            : "GoogleSQL"));
    TestResult result = TestResult.success(latency, "Google Cloud Spanner · " + s.label(), s.credentials().identity(),
        permissions).withChecks(checks).withMetadata(F.objectNode().put("serverVersion", "Cloud Spanner")
        .put("dialect", pg ? "PostgreSQL" : "GoogleSQL").put("database", s.databaseName()));
    for (Hint h : hints(cfg.env(), s, visible)) {
      result = result.withHint(h);
    }
    return result;
  }

  /** The project from this computer or the key; signed in, the instances, databases and database roles. */
  @Override
  public Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope) {
    return SpannerSuggestions.suggest(cfg, secrets, ctx, scope, api, cloud, local);
  }

  static List<Hint> hints(Env env, SpannerSettings s, int visible) {
    List<Hint> out = new ArrayList<>();
    if (env != Env.DEV && s.credentials().source() == GcpSpec.Source.ADC && s.credentials().impersonate() == null) {
      out.add(new Hint(Severity.WARNING, "spanner.personal-credentials", "/credentials/impersonate", "Calls use your "
          + "own Google sign-in. On " + env.id() + ", impersonate a service account with roles/spanner.databaseReader "
          + "on the database."));
    }
    out.add(new Hint(Severity.INFO, "spanner.read-only-boundary", null, "Reads run in read-only transactions, in "
        + "which Spanner refuses every change. Approved changes need roles/spanner.databaseUser (DML) or "
        + "roles/spanner.databaseAdmin (schema); a database role narrows what any of them may touch."));
    if (visible == 0) {
      out.add(new Hint(Severity.WARNING, "spanner.no-tables", "/advanced/databaseRole", "The credentials see no table "
          + "in " + s.label() + (s.databaseRole() == null ? "." : ": check the privileges of database role "
          + s.databaseRole() + ".")));
    }
    return out;
  }

  @Override
  public HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    try {
      SpannerSettings s = SpannerSettings.of(cfg);
      SpannerApi.Call c = new SpannerApi.Call(cfg, s, secrets, ctx);
      long started = System.nanoTime();
      closeSession(c, openSession(c));
      return HealthResult.healthy(ms(started));
    } catch (ConnectorException e) {
      return HealthResult.unreachable(e.getMessage());
    }
  }

  @Override
  public Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object) {
    ObjectNode out = F.objectNode().put("project", cfg.string("/project")).put("instance", cfg.string("/instance"))
        .put("database", cfg.string("/database"));
    if (cfg.string("/advanced/databaseRole") != null) {
      out.put("databaseRole", cfg.string("/advanced/databaseRole"));
    }
    out.put("reads", "One GoogleSQL or PostgreSQL query per spanner_query, in a read-only transaction; pass values as "
        + "parameters (@name in GoogleSQL, $1 named p1 in PostgreSQL)");
    out.put("changes", "One DML statement or one schema change per spanner_execute, after approval");
    if (object != null && !object.isBlank()) {
      out.put("table", "Call spanner_describe_table with table " + object + ".");
    }
    return Optional.of(out);
  }

  @Override
  public void onConnectionChanged(String connectionId) {
    cloud.evict(connectionId);
    dialects.keySet().removeIf(k -> k.startsWith(connectionId + ":"));
  }

  @Override
  public void onConnectionRemoved(String connectionId) {
    onConnectionChanged(connectionId);
  }

  private static long ms(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
