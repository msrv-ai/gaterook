package ai.msrv.gaterook.connectors.access;

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
import io.github.spannm.jackcess.Database;
import io.github.spannm.jackcess.DatabaseBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
 * Microsoft Access for agents (connector module {@code msaccess}, docs/access.md): one {@code .accdb} or
 * {@code .mdb} file on this computer, through UCanAccess. Reads list and describe its tables and saved queries and run
 * one checked query with row and time limits; a change runs one approved INSERT, UPDATE, DELETE or table DDL
 * statement, written to the file once and never retried.
 *
 * <p>Four things keep a read from changing anything: {@link AccessSql} admits one SELECT that neither reads files nor
 * runs SQL from text; Jackcess opens the file read-only ({@link ReadOnlyOpener}); the HSQLDB session that runs the
 * query is read-only and rolled back; and on macOS the connector's sandbox lets it write its own folder only. A saved
 * query of the file that calls {@code LOAD_FILE} (no query Access writes does) keeps every read of the file from
 * running.
 */
public final class AccessConnector implements Connector {

  static final String QUERY = "access_query";
  static final String LIST_TABLES = "access_list_tables";
  static final String DESCRIBE_TABLE = "access_describe_table";
  static final String EXECUTE = "access_execute";

  static final int DEFAULT_QUERY_LIMIT = 100;
  static final int MAX_QUERY_LIMIT = 1_000;
  static final int DEFAULT_LIST_LIMIT = 200;
  static final int DEFAULT_MAX_ROWS = 1_000;

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private final ConnectorManifest manifest;

  /** The module host's way in: the connector's definition in this JAR. */
  public AccessConnector() {
    this(Manifests.own());
  }

  /** The release described by {@code manifest} (tests). */
  AccessConnector(JsonNode manifest) {
    this.manifest = Manifests.read(manifest);
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  /** UCanAccess shares one copy of a file between calls: two at once per connection. */
  @Override
  public int maxConcurrency() {
    return 2;
  }

  /** The connection's limits: rows and the statement timeout. */
  record Limits(int maxRows, Integer statementTimeoutMs) {

    static Limits of(ConnectionConfig cfg) {
      int rows = Math.clamp(cfg.integer("/options/maxRows", DEFAULT_MAX_ROWS), 1, 10_000);
      int timeout = cfg.integer("/options/statementTimeoutMs", -1);
      return new Limits(rows, timeout > 0 ? timeout : null);
    }
  }

  // ── Classification and the exact action ─────────────────────────────────────────────────────────────────────

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    if (tool(call.tool()).isEmpty()) {
      return Classification.deny(policy.rule("unknown-tool"), call.tool() + " isn't a tool of Microsoft Access "
          + manifest.version() + ".", null);
    }
    return switch (call.tool()) {
      case QUERY -> {
        AccessSql.Verdict v = AccessSql.read(call.string("sql"));
        yield v.ok() ? Classification.read() : Classification.deny(policy.rule("read-only"), v.problem(),
            "access_query only reads. A change goes through access_execute, which waits for a person's approval.");
      }
      case EXECUTE -> {
        AccessSql.Verdict v = AccessSql.change(call.string("sql"));
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

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    String file = fileName(cfg.string("/file"));
    String target = cfg.name() + " · " + file;
    Limits limits = Limits.of(cfg);
    String sql = call.string("sql");
    switch (call.tool()) {
      case QUERY -> {
        AccessSql.Verdict v = AccessSql.read(sql);
        int rows = Math.min(Math.clamp(call.integer("limit", DEFAULT_QUERY_LIMIT), 1, MAX_QUERY_LIMIT),
            limits.maxRows());
        List<String> warnings = v.ok() ? List.of() : List.of("GateRook will refuse this statement: " + v.problem());
        return ActionPreview.of(ActionPreview.Kind.SQL, "sql", sql == null ? "" : sql, target,
                shorten("Query on " + cfg.name()))
            .withFields(List.of(new ActionPreview.Field("Access", "The file opened read-only, a read-only "
                + "transaction, at most " + rows + " rows")))
            .withWarnings(warnings)
            .withFacets(Map.of("statement", v.ok() ? v.verb() : "refused"))
            .withPayload(NODES.objectNode().put("sql", sql == null ? "" : sql).put("limit", rows));
      }
      case EXECUTE -> {
        AccessSql.Verdict v = AccessSql.change(sql);
        List<ActionPreview.Field> impact = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, String> facets = new LinkedHashMap<>();
        if (v.ok()) {
          impact.add(new ActionPreview.Field("Statement", v.verb()));
          impact.add(new ActionPreview.Field("Target", v.target() == null ? "Unknown: not named" : v.target()));
          impact.add(new ActionPreview.Field("Writing", "UCanAccess writes the change to the file when it commits; "
              + "GateRook never retries it"));
          facets.put("statement", v.verb());
          if (v.target() != null) {
            facets.put("tables", v.target());
          }
          if (v.verb().equals("DROP TABLE") || v.verb().equals("DELETE")) {
            warnings.add(v.verb() + " can't be undone once it is written to the file: keep a copy of " + file + ".");
          }
        } else {
          warnings.add("GateRook will refuse this statement: " + v.problem());
          facets.put("statement", "refused");
        }
        return ActionPreview.of(ActionPreview.Kind.SQL, "sql", sql == null ? "" : sql, target,
                shorten((v.ok() ? v.verb() + (v.target() == null ? "" : " " + v.target()) : "Change") + " on "
                    + cfg.name()))
            .withFields(List.of(new ActionPreview.Field("Access", "The file opened for writing")))
            .withImpact(impact)
            .withWarnings(warnings)
            .withFacets(facets)
            .withPayload(NODES.objectNode().put("sql", sql == null ? "" : sql));
      }
      default -> {
        String what = LIST_TABLES.equals(call.tool()) ? "List the tables" : "Describe " + call.string("table");
        return ActionPreview.of(ActionPreview.Kind.TEXT, "text", call.tool() + " " + call.arguments(), target,
            shorten(what + " of " + cfg.name())).withPayload(call.arguments());
      }
    }
  }

  // ── Setup help ──────────────────────────────────────────────────────────────────────────────────────────────

  /** The Access databases next to the typed path ({@link AccessSuggestions}), in either scope. */
  @Override
  public Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope) {
    return AccessSuggestions.suggest(cfg, ctx);
  }

  // ── Test connection ─────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    long started = System.nanoTime();
    List<TestResult.Check> checks = new ArrayList<>();
    Path file;
    try {
      file = file(cfg, ctx);
    } catch (ConnectorException e) {
      return TestResult.failure("access.file", e.getMessage(), null);
    }
    long size = AccessFiles.size(file);
    checks.add(new TestResult.Check("file", true, ms(started), "Local file · " + bytes(size) + " · "
        + AccessFiles.format(file)));
    String version;
    try (Database db = new DatabaseBuilder().withFile(file.toFile()).withReadOnly(true).withAutoSync(false).open()) {
      version = "Access " + db.getFileFormat().name().replace("V", "");
    } catch (IOException | RuntimeException e) {
      if (AccessErrors.encrypted(e)) {
        return TestResult.failure("access.encrypted", fileName(file.toString()) + " is password-protected: GateRook "
            + "can't open encrypted Access files. Remove the password in Access (File, Info, Decrypt Database).",
            AccessErrors.message(e)).withChecks(checks);
      }
      return TestResult.failure("access.open", "Jackcess can't open " + fileName(file.toString()) + ": "
          + AccessErrors.message(e), null).withChecks(checks);
    }
    long opened = System.nanoTime();
    long tables = 0;
    long queries = 0;
    try (Connection c = AccessEngine.read(file, ctx.workDir())) {
      try {
        String unsafe = unsafeQuery(c);
        if (unsafe != null) {
          return TestResult.failure("access.unsafe-query", unsafeText(unsafe), null).withChecks(checks);
        }
        try (ResultSet rs = c.getMetaData().getTables(null, null, "%", new String[] {"TABLE", "VIEW"})) {
          while (rs.next()) {
            if ("VIEW".equalsIgnoreCase(rs.getString("TABLE_TYPE"))) {
              queries++;
            } else {
              tables++;
            }
          }
        }
      } finally {
        rollback(c);
      }
    } catch (SQLException | RuntimeException e) {
      if (AccessErrors.encrypted(e)) {
        return TestResult.failure("access.encrypted", fileName(file.toString()) + " is password-protected: GateRook "
            + "can't open encrypted Access files.", AccessErrors.message(e)).withChecks(checks);
      }
      return TestResult.failure("access.open", "UCanAccess can't open " + fileName(file.toString()) + ": "
          + AccessErrors.message(e), null).withChecks(checks);
    }
    long latency = ms(opened);
    checks.add(new TestResult.Check("open", true, latency, "Opened read-only · " + version + " · "
        + count(tables, "table") + ", " + count(queries, "saved query", "saved queries")));
    boolean writable = AccessFiles.writable(file) && AccessFiles.opensForWriting(file);
    checks.add(new TestResult.Check("permissions", true, 0, writable ? "Can write" : "Read only"));
    ObjectNode permissionDetails = NODES.objectNode();
    permissionDetails.put("changes", writable ? "Through access_execute, after approval" : "None: the file can't be "
        + "written here");
    TestResult.Permissions permissions = new TestResult.Permissions(writable ? TestResult.Level.WRITE
        : TestResult.Level.READ, "SELECT on " + count(tables, "table") + " and " + count(queries, "saved query",
        "saved queries"), writable ? "INSERT, UPDATE, DELETE and table DDL through access_execute, after approval"
        : null, List.of(), permissionDetails);
    TestResult result = TestResult.success(latency, version, null, permissions);
    for (TestResult.Hint h : hints(cfg.env(), writable, tables + queries, size)) {
      result = result.withHint(h);
    }
    ObjectNode details = NODES.objectNode();
    details.putObject("file").put("name", fileName(file.toString())).put("sizeBytes", size);
    details.put("version", version);
    details.put("tables", tables);
    details.put("queries", queries);
    details.put("writable", writable);
    details.put("inMemory", size <= AccessEngine.IN_MEMORY_BYTES);
    details.put("totalMs", ms(started));
    return result.withChecks(checks).withDetails(details)
        .withMetadata(NODES.objectNode().put("serverVersion", version));
  }

  static List<TestResult.Hint> hints(Env env, boolean writable, long objects, long size) {
    List<TestResult.Hint> out = new ArrayList<>();
    if (writable) {
      boolean dev = env == Env.DEV;
      out.add(new TestResult.Hint(dev ? TestResult.Severity.INFO : TestResult.Severity.WARNING,
          "access.file-writable", "/file", "GateRook may write this file. " + (dev ? "Fine for dev: every change "
          + "still waits for approval." : "Every change still waits for approval, but on " + env.id() + " use a "
          + "copy of the file, or keep access_execute off.")));
    } else {
      out.add(new TestResult.Hint(TestResult.Severity.INFO, "access.file-read-only", "/file", "GateRook can't write "
          + "this file here, so access_execute fails: the file is read-only, or the connector's sandbox lets it write "
          + "only its own folder."));
    }
    if (objects == 0) {
      out.add(new TestResult.Hint(TestResult.Severity.INFO, "access.empty", "/file", "The file has no tables or saved "
          + "queries yet."));
    }
    if (size > AccessEngine.IN_MEMORY_BYTES) {
      out.add(new TestResult.Hint(TestResult.Severity.INFO, "access.large", "/file", "The file is larger than 128 MB: "
          + "GateRook copies it into its own folder to read it, so the first call after a change takes longer."));
    }
    return out;
  }

  // ── Execution ───────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    ToolDefinition tool = tool(call.tool()).orElseThrow(() -> ConnectorException.badInput(call.tool()
        + " isn't a tool of Microsoft Access " + manifest.version() + "."));
    if (tool.nature() != ToolDefinition.Nature.READ && !ctx.approved()) {
      throw ConnectorException.denied("Not approved: " + call.tool() + " changes the database file, so a person "
          + "approves it first.");
    }
    Path file = file(cfg, ctx);
    Limits limits = Limits.of(cfg);
    return switch (call.tool()) {
      case QUERY -> query(call, file, limits, ctx);
      case LIST_TABLES -> listTables(call, file, limits, ctx);
      case DESCRIBE_TABLE -> describeTable(call, file, limits, ctx);
      case EXECUTE -> change(call, file, limits, ctx);
      default -> throw ConnectorException.badInput(call.tool() + " isn't implemented here.");
    };
  }

  @FunctionalInterface
  private interface Read {
    ToolResult run(Connection c, int timeoutSeconds) throws SQLException, ConnectorException;
  }

  /**
   * A read on a read-only connection, rolled back whatever happens, refused when a saved query of the file calls
   * {@code LOAD_FILE}.
   */
  private static ToolResult read(Path file, Limits limits, ExecContext ctx, Read body) throws ConnectorException {
    long timeout = timeoutMs(ctx, limits);
    try (Connection c = AccessEngine.read(file, ctx.workDir())) {
      try {
        String unsafe = unsafeQuery(c);
        if (unsafe != null) {
          throw ConnectorException.denied(unsafeText(unsafe));
        }
        return body.run(c, seconds(timeout));
      } finally {
        rollback(c);
      }
    } catch (SQLException | RuntimeException e) {
      return readFailure(e, file, ctx, timeout);
    }
  }

  /** A saved query (an HSQLDB view of UCanAccess's copy) that reads files, or {@code null}. */
  static String unsafeQuery(Connection c) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.VIEWS WHERE TABLE_SCHEMA = "
            + "'PUBLIC' AND UPPER(VIEW_DEFINITION) LIKE '%LOAD_FILE%'")) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  private static String unsafeText(String query) {
    return "The file's saved query " + query + " calls LOAD_FILE, which reads files outside the database. A query "
        + "Access writes never does, so GateRook doesn't read this file: remove the query, or use another copy.";
  }

  private ToolResult query(ToolCall call, Path file, Limits limits, ExecContext ctx) throws ConnectorException {
    String sql = statement(call, ctx);
    AccessSql.Verdict v = AccessSql.read(sql);
    if (!v.ok()) {
      throw ConnectorException.denied(v.problem());
    }
    int limit = rows(arguments(call, ctx).path("limit").asInt(DEFAULT_QUERY_LIMIT), limits, ctx);
    return read(file, limits, ctx, (c, timeout) -> {
      try (Statement st = c.createStatement()) {
        st.setQueryTimeout(timeout);
        st.setMaxRows(limit + 1);
        ctx.onCancel(() -> cancel(st));
        if (!st.execute(v.text())) {
          return ToolResult.table(new ToolResult.Table(List.of(), NODES.arrayNode(), null), null);
        }
        try (ResultSet rs = st.getResultSet()) {
          AccessResults.Read read = AccessResults.read(rs, limit, maxBytes(ctx), maxCells(ctx), false);
          ToolResult result = ToolResult.table(read.table(), null);
          return read.truncatedReason() == null ? result : result.truncated(read.truncatedReason());
        }
      }
    });
  }

  private ToolResult listTables(ToolCall call, Path file, Limits limits, ExecContext ctx) throws ConnectorException {
    int limit = rows(Math.clamp(call.integer("limit", DEFAULT_LIST_LIMIT), 1, 1_000), limits, ctx);
    Pattern like = like(call.string("like"));
    return read(file, limits, ctx, (c, timeout) -> {
      ArrayNode rows = NODES.arrayNode();
      boolean truncated = false;
      try (ResultSet rs = c.getMetaData().getTables(null, null, "%", new String[] {"TABLE", "VIEW"})) {
        while (rs.next()) {
          String name = rs.getString("TABLE_NAME");
          if (name == null || like != null && !like.matcher(name).matches()) {
            continue;
          }
          if (rows.size() >= limit) {
            truncated = true;
            break;
          }
          rows.addArray().add(name).add("VIEW".equalsIgnoreCase(rs.getString("TABLE_TYPE")) ? "query" : "table");
        }
      }
      List<ToolResult.Column> columns = List.of(new ToolResult.Column("name", "VARCHAR", null, null, null),
          new ToolResult.Column("kind", "VARCHAR", null, null, null));
      ObjectNode structured = NODES.objectNode().put("rowCount", rows.size()).put("truncated", truncated);
      ToolResult result = ToolResult.table(new ToolResult.Table(columns, rows, null), structured);
      return truncated ? result.truncated("row_limit") : result;
    });
  }

  /** A LIKE pattern ({@code %}, {@code _}) as a case-insensitive regular expression, or {@code null}. */
  static Pattern like(String pattern) {
    if (pattern == null || pattern.isBlank()) {
      return null;
    }
    StringBuilder b = new StringBuilder();
    for (char ch : pattern.strip().toCharArray()) {
      b.append(switch (ch) {
        case '%' -> ".*";
        case '_' -> ".";
        default -> Pattern.quote(String.valueOf(ch));
      });
    }
    return Pattern.compile(b.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
  }

  private ToolResult describeTable(ToolCall call, Path file, Limits limits, ExecContext ctx)
      throws ConnectorException {
    String table = blankToNull(call.string("table"));
    if (table == null) {
      throw ConnectorException.badInput("table is required.");
    }
    int cells = maxCells(ctx);
    return read(file, limits, ctx, (c, timeout) -> describe(c, table, cells));
  }

  private static ToolResult describe(Connection c, String table, int cells) throws SQLException {
    DatabaseMetaData md = c.getMetaData();
    String name = null;
    String kind = null;
    List<String> close = new ArrayList<>();
    try (ResultSet rs = md.getTables(null, null, "%", new String[] {"TABLE", "VIEW"})) {
      while (rs.next()) {
        String n = rs.getString("TABLE_NAME");
        if (n == null) {
          continue;
        }
        if (n.equals(table) || name == null && n.equalsIgnoreCase(table)) {
          name = n;
          kind = "VIEW".equalsIgnoreCase(rs.getString("TABLE_TYPE")) ? "query" : "table";
        } else if (n.toLowerCase(Locale.ROOT).contains(table.toLowerCase(Locale.ROOT)) && close.size() < 5) {
          close.add(n);
        }
      }
    }
    if (name == null) {
      return ToolResult.error(table + " isn't a table or saved query of this file." + (close.isEmpty() ? ""
          : " Close: " + String.join(", ", close) + ".") + " access_list_tables lists them.");
    }
    ObjectNode out = NODES.objectNode();
    out.put("name", name);
    out.put("kind", kind);
    ArrayNode columns = out.putArray("columns");
    List<String> lines = new ArrayList<>();
    try (ResultSet rs = md.getColumns(null, null, name, "%")) {
      while (rs.next() && columns.size() < 2_000) {
        ObjectNode col = columns.addObject();
        String type = rs.getString("TYPE_NAME");
        int size = rs.getInt("COLUMN_SIZE");
        boolean nullable = !"NO".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
        col.put("name", rs.getString("COLUMN_NAME"));
        col.put("type", type == null ? "UNKNOWN" : type);
        if (rs.wasNull() || size <= 0) {
          col.putNull("size");
        } else {
          col.put("size", size);
        }
        col.put("nullable", nullable);
        String dflt = rs.getString("COLUMN_DEF");
        if (dflt == null) {
          col.putNull("default");
        } else {
          col.set("default", AccessResults.text(dflt, cells));
        }
        col.put("autoNumber", "YES".equalsIgnoreCase(rs.getString("IS_AUTOINCREMENT")));
        lines.add(rs.getString("COLUMN_NAME") + " " + type + (nullable ? "" : " NOT NULL"));
      }
    }
    ArrayNode primaryKey = out.putArray("primaryKey");
    ArrayNode indexes = out.putArray("indexes");
    if ("table".equals(kind)) {
      List<String[]> keys = new ArrayList<>();
      try (ResultSet rs = md.getPrimaryKeys(null, null, name)) {
        while (rs.next()) {
          keys.add(new String[] {String.valueOf(rs.getInt("KEY_SEQ")), rs.getString("COLUMN_NAME")});
        }
      }
      keys.stream().sorted((a, b) -> Integer.compare(Integer.parseInt(a[0]), Integer.parseInt(b[0])))
          .forEach(k -> primaryKey.add(k[1]));
      Map<String, ObjectNode> byName = new LinkedHashMap<>();
      try (ResultSet rs = md.getIndexInfo(null, null, name, false, true)) {
        while (rs.next() && byName.size() < 500) {
          String index = rs.getString("INDEX_NAME");
          if (index == null) {
            continue;
          }
          boolean unique = !rs.getBoolean("NON_UNIQUE");
          ObjectNode node = byName.computeIfAbsent(index, n -> {
            ObjectNode o = NODES.objectNode();
            o.put("name", n);
            o.put("unique", unique);
            o.putArray("columns");
            return o;
          });
          String column = rs.getString("COLUMN_NAME");
          if (column != null) {
            ((ArrayNode) node.get("columns")).add(column);
          }
        }
      }
      byName.values().forEach(indexes::add);
    }
    List<String> keyNames = new ArrayList<>();
    primaryKey.forEach(k -> keyNames.add(k.asString()));
    String head = name + " · " + kind + (keyNames.isEmpty() ? "" : " · primary key (" + String.join(", ", keyNames)
        + ")");
    return ToolResult.json(out, head + "\n" + String.join("\n", lines));
  }

  private ToolResult change(ToolCall call, Path file, Limits limits, ExecContext ctx) throws ConnectorException {
    String sql = statement(call, ctx);
    AccessSql.Verdict v = AccessSql.change(sql);
    if (!v.ok()) {
      throw ConnectorException.denied(v.problem());
    }
    String name = fileName(file.toString());
    long timeout = timeoutMs(ctx, limits);
    long started = System.nanoTime();
    if (!AccessFiles.opensForWriting(file)) {
      throw ConnectorException.resourceError("GateRook can't open " + name + " for writing here: the file is "
          + "read-only, or the connector's sandbox lets it write only its own folder. Nothing changed.", null);
    }
    Connection c;
    try {
      c = AccessEngine.write(file, ctx.workDir());
    } catch (SQLException | RuntimeException e) {
      if (AccessErrors.notPermitted(e)) {
        throw ConnectorException.resourceError("GateRook can't open " + name + " for writing here: the file is "
            + "read-only, or the connector's sandbox lets it write only its own folder. Nothing changed.", e);
      }
      throw ConnectorException.unavailable("UCanAccess can't open " + name + " for writing: "
          + AccessErrors.message(e) + " Nothing changed.", e);
    }
    boolean ran = false;
    try (c) {
      // Whatever comes of it, the next read loads the file again.
      AccessEngine.changed(file);
      ObjectNode out = NODES.objectNode();
      out.put("statement", v.verb());
      if (v.target() == null) {
        out.putNull("target");
      } else {
        out.put("target", v.target());
      }
      try (Statement st = c.createStatement()) {
        st.setQueryTimeout(seconds(timeout));
        ctx.onCancel(() -> cancel(st));
        // Exactly the approved text.
        st.execute(v.text());
        long count = st.getUpdateCount();
        if (count < 0) {
          out.putNull("rowsChanged");
        } else {
          out.put("rowsChanged", count);
        }
      } catch (SQLException | RuntimeException e) {
        rollback(c);
        if (AccessErrors.notPermitted(e)) {
          throw ConnectorException.resourceError("GateRook can't write " + name + " here: the file is read-only, or "
              + "the connector's sandbox lets it write only its own folder. Nothing changed.", e);
        }
        return ToolResult.error("UCanAccess refused the change, so nothing changed: " + AccessErrors.message(e));
      }
      ran = true;
      c.commit();
      out.put("elapsedMs", ms(started));
      JsonNode changed = out.path("rowsChanged");
      String text = v.verb() + (v.target() == null ? "" : " " + v.target()) + ": " + (changed.isNumber()
          ? count(changed.asLong(), "row") + " changed." : "done.");
      return ToolResult.json(out, text);
    } catch (SQLException | RuntimeException e) {
      if (ran) {
        // Jackcess writes rows one by one, without a transaction of its own.
        throw ConnectorException.outcomeUnknown("Writing the change to " + name + " failed: " + AccessErrors.message(e)
            + " Part of it may be in the file: check the data before you try again.", e);
      }
      throw ConnectorException.unavailable("The change couldn't run: " + AccessErrors.message(e) + " Nothing changed.",
          e);
    }
  }

  private static ToolResult readFailure(Exception e, Path file, ExecContext ctx, long timeoutMs)
      throws ConnectorException {
    String name = fileName(file.toString());
    if (e instanceof SQLException s && AccessErrors.cancelled(s)) {
      if (ctx.cancelled()) {
        throw ConnectorException.resourceError("The call was cancelled: the query stopped.", e);
      }
      throw ConnectorException.timeout("The query ran past its time limit (" + seconds(timeoutMs) + " s) and stopped. "
          + "Narrow it down and try again.");
    }
    if (AccessErrors.encrypted(e)) {
      throw ConnectorException.unavailable(name + " is password-protected: GateRook can't open encrypted Access "
          + "files.", e);
    }
    if (AccessErrors.readOnly(e)) {
      return ToolResult.error("The query tried to change the database, which a read never does: "
          + AccessErrors.message(e));
    }
    if (AccessErrors.noSuchObject(e)) {
      return ToolResult.error("UCanAccess refused the query: " + AccessErrors.message(e) + ". access_list_tables "
          + "lists the tables and saved queries.");
    }
    return ToolResult.error("UCanAccess refused the query: " + AccessErrors.message(e));
  }

  // ── Arguments, files and limits ─────────────────────────────────────────────────────────────────────────────

  /** The connection's file, checked now (links included). */
  static Path file(ConnectionConfig cfg, ExecContext ctx) throws ConnectorException {
    return AccessFiles.database(cfg.string("/file"), AccessFiles.home(ctx.workDir()));
  }

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

  static int rows(int requested, Limits limits, ExecContext ctx) {
    int env = ctx.limits() == null || ctx.limits().maxRows() <= 0 ? Integer.MAX_VALUE : ctx.limits().maxRows();
    return Math.max(1, Math.min(Math.min(Math.clamp(requested, 1, MAX_QUERY_LIMIT * 10), limits.maxRows()), env));
  }

  static long timeoutMs(ExecContext ctx, Limits limits) {
    long limit = ctx.limits() != null && ctx.limits().statementTimeout() != null
        && !ctx.limits().statementTimeout().isZero() ? ctx.limits().statementTimeout().toMillis() : 30_000;
    if (limits.statementTimeoutMs() != null) {
      limit = Math.min(limit, limits.statementTimeoutMs());
    }
    long left = ctx.remaining().toMillis() - 250;
    return Math.max(1_000, Math.min(limit, left));
  }

  private static int seconds(long ms) {
    return (int) Math.max(1, (ms + 999) / 1000);
  }

  private static long maxBytes(ExecContext ctx) {
    return ctx.limits() == null || ctx.limits().maxResponseBytes() <= 0 ? 524_288 : ctx.limits().maxResponseBytes();
  }

  private static int maxCells(ExecContext ctx) {
    return ctx.limits() == null || ctx.limits().maxCellChars() <= 0 ? 8_192 : ctx.limits().maxCellChars();
  }

  private static void rollback(Connection c) {
    try {
      if (!c.isClosed()) {
        c.rollback();
      }
    } catch (SQLException | RuntimeException ignored) {
      // The connection closes next.
    }
  }

  private static void cancel(Statement st) {
    try {
      st.cancel();
    } catch (SQLException | RuntimeException ignored) {
      // It ended already.
    }
  }

  // ── Small things ────────────────────────────────────────────────────────────────────────────────────────────

  private Optional<ToolDefinition> tool(String name) {
    return manifest.tools().stream().filter(t -> t.name().equals(name)).findFirst();
  }

  static String fileName(String path) {
    if (path == null || path.isBlank()) {
      return "the file";
    }
    String p = path.strip().replace('\\', '/');
    return p.substring(p.lastIndexOf('/') + 1);
  }

  private static String blankToNull(String v) {
    return v == null || v.isBlank() ? null : v.strip();
  }

  private static String bytes(long n) {
    double mib = n / 1048576.0;
    return mib < 0.1 ? (n / 1024) + " KiB" : String.format(Locale.US, "%.1f MiB", mib);
  }

  private static String count(long n, String noun) {
    return n + " " + noun + (n == 1 ? "" : "s");
  }

  private static String count(long n, String one, String many) {
    return n + " " + (n == 1 ? one : many);
  }

  private static String shorten(String s) {
    return s.length() <= 120 ? s : s.substring(0, 119) + "…";
  }

  private static long ms(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
