package ai.msrv.gaterook.connectors.h2;

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
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
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
import java.util.Properties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * H2 for agents (connector module {@code h2}, docs/h2.md): one database of an H2 server, over H2's TCP protocol.
 * Reads list and describe its tables and run one checked query with row and time limits; a change runs one approved
 * INSERT, UPDATE, DELETE, MERGE, TRUNCATE, COMMENT ON or DDL statement, committed once and never retried.
 *
 * <p>H2 has no read-only transaction, so three things keep a read from changing anything: {@link H2Sql} admits one
 * query that calls only H2's built-in functions that stay inside the database; the query runs in a transaction that
 * is always rolled back; and the database user's rights, which GateRook checks and reports. A user with SELECT rights
 * only is the boundary the server enforces: an administrator could read the server's files or run Java code through
 * the database's own views and functions.
 *
 * <p>The H2 client's sockets go through the module host's TCP gate ({@code access.tcp}): they reach the connection's
 * host and port only, under the environment's policy. H2's TCP protocol isn't encrypted, so the connector connects only
 * to a server on this computer or a private network. Each call opens its own connection and closes it.
 */
public final class H2Connector implements Connector {

  static final String QUERY = "h2_query";
  static final String LIST_TABLES = "h2_list_tables";
  static final String DESCRIBE_TABLE = "h2_describe_table";
  static final String EXECUTE = "h2_execute";

  static final int DEFAULT_QUERY_LIMIT = 100;
  static final int MAX_QUERY_LIMIT = 1_000;
  static final int DEFAULT_LIST_LIMIT = 200;
  /** Test connection gives up after 8 s. */
  static final long TEST_BUDGET_MS = 8_000;
  /** What the module host sets once its TCP gate is the JVM's proxy selector. */
  static final String GATE_PROPERTY = "gaterook.tcpGate";

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private final ConnectorManifest manifest;
  private final boolean needsGate;

  /** The module host's way in: the connector's definition in this JAR, and its sockets only through the host's TCP gate. */
  public H2Connector() {
    this(Manifests.own(), true);
  }

  /** The release described by {@code manifest} (tests); {@code needsGate} false runs without a module host. */
  H2Connector(JsonNode manifest, boolean needsGate) {
    this.manifest = Manifests.read(manifest);
    this.needsGate = needsGate;
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  @Override
  public int maxConcurrency() {
    return 4;
  }

  // ── Classification and the exact action ─────────────────────────────────────────────────────────────────────

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    if (tool(call.tool()).isEmpty()) {
      return Classification.deny(policy.rule("unknown-tool"), call.tool() + " isn't a tool of H2 "
          + manifest.version() + ".", null);
    }
    return switch (call.tool()) {
      case QUERY -> {
        H2Sql.Verdict v = H2Sql.read(call.string("sql"));
        yield v.ok() ? Classification.read() : Classification.deny(policy.rule("read-only"), v.problem(),
            "h2_query only reads. A change goes through h2_execute, which waits for a person's approval.");
      }
      case EXECUTE -> {
        H2Sql.Verdict v = H2Sql.change(call.string("sql"));
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
    H2Config config;
    try {
      config = H2Config.of(cfg);
    } catch (ConnectorException e) {
      return ActionPreview.of(ActionPreview.Kind.TEXT, "text", call.tool() + " " + call.arguments(), cfg.name(),
          shorten(call.tool() + " on " + cfg.name()));
    }
    String target = cfg.name() + " · " + config.database() + " on " + config.address();
    String sql = call.string("sql");
    switch (call.tool()) {
      case QUERY -> {
        H2Sql.Verdict v = H2Sql.read(sql);
        int rows = Math.min(Math.clamp(call.integer("limit", DEFAULT_QUERY_LIMIT), 1, MAX_QUERY_LIMIT),
            config.maxRows());
        List<String> warnings = v.ok() ? List.of() : List.of("GateRook will refuse this statement: " + v.problem());
        return ActionPreview.of(ActionPreview.Kind.SQL, "sql", sql == null ? "" : sql, target,
                shorten("Query on " + cfg.name()))
            .withFields(List.of(new ActionPreview.Field("Access", "One query as " + config.user() + ", rolled back "
                + "when it ends, at most " + rows + " rows")))
            .withWarnings(warnings)
            .withFacets(Map.of("statement", v.ok() ? v.verb() : "refused"))
            .withPayload(NODES.objectNode().put("sql", sql == null ? "" : sql).put("limit", rows));
      }
      case EXECUTE -> {
        H2Sql.Verdict v = H2Sql.change(sql);
        List<ActionPreview.Field> impact = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, String> facets = new LinkedHashMap<>();
        if (v.ok()) {
          impact.add(new ActionPreview.Field("Statement", v.verb()));
          impact.add(new ActionPreview.Field("Target", v.target() == null ? "Unknown: not named" : v.target()));
          impact.add(new ActionPreview.Field("Transaction", v.verb().startsWith("INSERT")
              || v.verb().startsWith("UPDATE") || v.verb().startsWith("DELETE") || v.verb().startsWith("MERGE")
              ? "One statement committed once: H2 rolls it back if it fails, and GateRook never retries it"
              : "H2 commits DDL as it runs it: it can't be rolled back, and GateRook never retries it"));
          facets.put("statement", v.verb());
          if (v.target() != null) {
            facets.put("tables", v.target());
          }
          if (v.verb().startsWith("DROP") || v.verb().startsWith("TRUNCATE")) {
            warnings.add(v.verb() + " can't be undone once it commits.");
          }
        } else {
          warnings.add("GateRook will refuse this statement: " + v.problem());
          facets.put("statement", "refused");
        }
        return ActionPreview.of(ActionPreview.Kind.SQL, "sql", sql == null ? "" : sql, target,
                shorten((v.ok() ? v.verb() + (v.target() == null ? "" : " " + v.target()) : "Change") + " on "
                    + cfg.name()))
            .withFields(List.of(new ActionPreview.Field("Access", "As " + config.user() + " on "
                + config.address())))
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

  // ── Test connection ─────────────────────────────────────────────────────────────────────────────────────────

  /** What the test learns from the server. */
  record Probe(String version, String user, boolean admin, List<String> rights, long tables, long views,
      String mode) {}

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    long started = System.nanoTime();
    List<TestResult.Check> checks = new ArrayList<>();
    H2Config config;
    try {
      config = H2Config.of(cfg);
      reachable(config);
    } catch (ConnectorException e) {
      return TestResult.failure("h2.config", e.getMessage(), null);
    }
    long budget = Math.max(1_000, Math.min(TEST_BUDGET_MS, ctx.remaining().toMillis() - 250));
    Probe probe;
    long latency;
    try (Connection c = open(config, secrets, ctx)) {
      latency = ms(started);
      checks.add(new TestResult.Check("connect", true, latency, "H2 server at " + config.address()));
      c.setAutoCommit(false);
      try {
        probe = probe(c, (int) Math.max(1, budget / 1000));
      } finally {
        rollback(c);
      }
    } catch (ConnectorException e) {
      return TestResult.failure("h2.unreachable", e.getMessage(), null).withChecks(checks);
    } catch (SQLException e) {
      return testFailure(e, config).withChecks(checks);
    }
    checks.add(new TestResult.Check("database", true, 0, config.database() + " · H2 " + probe.version()));
    checks.add(new TestResult.Check("user", true, 0, probe.user() + (probe.admin() ? " · administrator"
        : probe.rights().isEmpty() ? " · no rights on tables" : " · " + String.join(", ", probe.rights()))));
    boolean writes = probe.admin() || probe.rights().stream().anyMatch(r -> !r.equals("SELECT"));
    TestResult.Level level = probe.admin() ? TestResult.Level.ADMIN : writes ? TestResult.Level.WRITE
        : TestResult.Level.READ;
    ObjectNode permissionDetails = NODES.objectNode();
    permissionDetails.put("administrator", probe.admin());
    ArrayNode rights = permissionDetails.putArray("rights");
    probe.rights().forEach(rights::add);
    TestResult.Permissions permissions = new TestResult.Permissions(level, probe.admin() ? "Everything, as an "
        + "administrator" : "SELECT where granted",
        writes ? "Through h2_execute, after approval" : null, probe.admin() ? List.of("Administrator of the database")
        : List.of(), permissionDetails);
    TestResult result = TestResult.success(latency, "H2 " + probe.version(), probe.user(), permissions);
    for (TestResult.Hint h : hints(cfg.env(), probe)) {
      result = result.withHint(h);
    }
    ObjectNode details = NODES.objectNode();
    details.put("h2Version", probe.version());
    details.put("user", probe.user());
    details.put("administrator", probe.admin());
    details.put("tables", probe.tables());
    details.put("views", probe.views());
    details.put("mode", probe.mode());
    details.put("totalMs", ms(started));
    return result.withChecks(checks).withDetails(details)
        .withMetadata(NODES.objectNode().put("serverVersion", "H2 " + probe.version()));
  }

  private static Probe probe(Connection c, int timeoutSeconds) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.setQueryTimeout(timeoutSeconds);
      String version;
      String user;
      try (ResultSet rs = st.executeQuery("SELECT H2VERSION(), CURRENT_USER")) {
        rs.next();
        version = rs.getString(1);
        user = rs.getString(2);
      }
      boolean admin = false;
      try (PreparedStatement ps = c.prepareStatement("SELECT IS_ADMIN FROM INFORMATION_SCHEMA.USERS WHERE "
          + "USER_NAME = CURRENT_USER")) {
        ps.setQueryTimeout(timeoutSeconds);
        try (ResultSet rs = ps.executeQuery()) {
          admin = rs.next() && rs.getBoolean(1);
        }
      }
      // TABLE_PRIVILEGES shows every grant on the tables a user can see (RIGHTS shows an administrator only): the
      // user's own and PUBLIC's count. Which other roles it has, H2 shows only an administrator.
      List<String> rights = new ArrayList<>();
      try (ResultSet rs = st.executeQuery("SELECT DISTINCT PRIVILEGE_TYPE FROM INFORMATION_SCHEMA.TABLE_PRIVILEGES "
          + "WHERE (GRANTEE = CURRENT_USER OR GRANTEE = 'PUBLIC') AND PRIVILEGE_TYPE IS NOT NULL ORDER BY "
          + "PRIVILEGE_TYPE")) {
        while (rs.next()) {
          String right = rs.getString(1).strip().toUpperCase(Locale.ROOT);
          if (!right.isEmpty() && !rights.contains(right)) {
            rights.add(right);
          }
        }
      }
      long tables = 0;
      long views = 0;
      try (ResultSet rs = st.executeQuery("SELECT TABLE_TYPE, COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE "
          + "TABLE_SCHEMA <> 'INFORMATION_SCHEMA' GROUP BY TABLE_TYPE")) {
        while (rs.next()) {
          if ("VIEW".equals(rs.getString(1))) {
            views += rs.getLong(2);
          } else {
            tables += rs.getLong(2);
          }
        }
      }
      String mode = "REGULAR";
      try (ResultSet rs = st.executeQuery("SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME "
          + "= 'MODE'")) {
        if (rs.next() && rs.getString(1) != null) {
          mode = rs.getString(1);
        }
      }
      return new Probe(version, user, admin, List.copyOf(rights), tables, views, mode);
    }
  }

  /** The kit's tone: who the user is to the database, and what that means for the agent's reads. */
  static List<TestResult.Hint> hints(Env env, Probe probe) {
    List<TestResult.Hint> out = new ArrayList<>();
    if (probe.admin()) {
      out.add(new TestResult.Hint(env == Env.DEV ? TestResult.Severity.WARNING : TestResult.Severity.DANGER,
          "h2.admin", "/user", probe.user() + " is an administrator of this database. H2 has no read-only "
          + "transactions, so GateRook's checks and rollbacks are all that keep its reads from changing data, and "
          + "the database's own views and functions run with its rights. Give agents a user with SELECT rights only "
          + "(CREATE USER, then GRANT SELECT ON SCHEMA PUBLIC TO it)."));
    } else if (probe.rights().stream().anyMatch(r -> !r.equals("SELECT"))) {
      out.add(new TestResult.Hint(TestResult.Severity.WARNING, "h2.writer", "/user", probe.user() + " may also "
          + String.join(", ", probe.rights().stream().filter(r -> !r.equals("SELECT")).toList()) + ". Every change "
          + "still waits for approval, but a user with SELECT rights only is what H2 itself enforces."));
    } else if (probe.rights().isEmpty()) {
      out.add(new TestResult.Hint(TestResult.Severity.WARNING, "h2.no-rights", "/user", probe.user() + " has no "
          + "rights on tables yet, so queries find nothing to read."));
    }
    if (!"REGULAR".equalsIgnoreCase(probe.mode())) {
      out.add(new TestResult.Hint(TestResult.Severity.INFO, "h2.mode", "/database", "The database runs in "
          + probe.mode() + " mode. GateRook reads queries as H2's regular SQL, so functions only that mode adds "
          + "are refused."));
    }
    if (probe.tables() + probe.views() == 0) {
      out.add(new TestResult.Hint(TestResult.Severity.INFO, "h2.empty", "/database", "The database has no tables or "
          + "views yet."));
    }
    return out;
  }

  private static TestResult testFailure(SQLException e, H2Config config) {
    String technical = H2Errors.message(e);
    if (H2Errors.login(e)) {
      return TestResult.failure("h2.login", "The server refused the user or the password.", technical);
    }
    if (H2Errors.noDatabase(e)) {
      return TestResult.failure("h2.no-database", "The server has no database " + config.database() + ". GateRook "
          + "never creates one: check the name and the server's base directory.", technical);
    }
    if (H2Errors.versionMismatch(e)) {
      return TestResult.failure("h2.version", "The server's H2 version doesn't speak this connector's protocol (H2 "
          + org.h2.engine.Constants.VERSION + "). Use a server of H2 2.x.", technical);
    }
    if (H2Errors.connection(e)) {
      return TestResult.failure("h2.unreachable", "Can't reach the H2 server at " + config.address() + ". Check the "
          + "host, the port and that the server runs with -tcp.", technical);
    }
    return TestResult.failure("h2.error", technical, technical);
  }

  // ── Execution ───────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    ToolDefinition tool = tool(call.tool()).orElseThrow(() -> ConnectorException.badInput(call.tool()
        + " isn't a tool of H2 " + manifest.version() + "."));
    if (tool.nature() != ToolDefinition.Nature.READ && !ctx.approved()) {
      // Defence in depth: the gateway sends a change only after a person approved it (or the tool is in Bypass).
      throw ConnectorException.denied("Not approved: " + call.tool() + " changes the database, so a person "
          + "approves it first.");
    }
    H2Config config = H2Config.of(cfg);
    return switch (call.tool()) {
      case QUERY -> query(call, config, secrets, ctx);
      case LIST_TABLES -> listTables(call, config, secrets, ctx);
      case DESCRIBE_TABLE -> describeTable(call, config, secrets, ctx);
      case EXECUTE -> change(call, config, secrets, ctx);
      default -> throw ConnectorException.badInput(call.tool() + " isn't implemented here.");
    };
  }

  /** A read on its own connection, in a transaction that is rolled back whatever happens. */
  @FunctionalInterface
  private interface Read {
    ToolResult run(Connection c, int timeoutSeconds) throws SQLException, ConnectorException;
  }

  private ToolResult read(H2Config config, SecretResolver secrets, ExecContext ctx, Read body)
      throws ConnectorException {
    long timeout = timeoutMs(ctx, config);
    try (Connection c = open(config, secrets, ctx)) {
      c.setAutoCommit(false);
      try {
        return body.run(c, seconds(timeout));
      } finally {
        rollback(c);
      }
    } catch (SQLException e) {
      return readFailure(e, config, ctx, timeout);
    }
  }

  private ToolResult query(ToolCall call, H2Config config, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    String sql = statement(call, ctx);
    H2Sql.Verdict v = H2Sql.read(sql);
    if (!v.ok()) {
      throw ConnectorException.denied(v.problem());
    }
    int limit = rows(arguments(call, ctx).path("limit").asInt(DEFAULT_QUERY_LIMIT), config, ctx);
    return read(config, secrets, ctx, (c, timeout) -> {
      try (Statement st = c.createStatement()) {
        st.setQueryTimeout(timeout);
        st.setMaxRows(limit + 1);
        st.setFetchSize(Math.min(limit + 1, 500));
        st.setEscapeProcessing(false);
        ctx.onCancel(() -> cancel(st));
        if (!st.execute(v.text())) {
          return ToolResult.table(new ToolResult.Table(List.of(), NODES.arrayNode(), null), null);
        }
        try (ResultSet rs = st.getResultSet()) {
          H2Results.Read read = H2Results.read(rs, limit, maxBytes(ctx), maxCells(ctx), false);
          ToolResult result = ToolResult.table(read.table(), null);
          return read.truncatedReason() == null ? result : result.truncated(read.truncatedReason());
        }
      }
    });
  }

  private ToolResult listTables(ToolCall call, H2Config config, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    int limit = rows(Math.clamp(call.integer("limit", DEFAULT_LIST_LIMIT), 1, 1_000), config, ctx);
    String schema = blankToNull(call.string("schema"));
    String like = blankToNull(call.string("like"));
    StringBuilder sql = new StringBuilder("SELECT TABLE_SCHEMA AS \"schema\", TABLE_NAME AS \"name\", "
        + "CASE WHEN TABLE_TYPE = 'VIEW' THEN 'view' ELSE 'table' END AS \"kind\", ROW_COUNT_ESTIMATE AS "
        + "\"estimatedRows\", REMARKS AS \"comment\" FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA <> "
        + "'INFORMATION_SCHEMA'");
    List<String> params = new ArrayList<>();
    if (schema != null) {
      sql.append(" AND UPPER(TABLE_SCHEMA) = UPPER(?)");
      params.add(schema);
    }
    if (like != null) {
      sql.append(" AND UPPER(TABLE_NAME) LIKE UPPER(?)");
      params.add(like);
    }
    sql.append(" ORDER BY TABLE_SCHEMA, TABLE_NAME FETCH FIRST ").append(limit + 1).append(" ROWS ONLY");
    return read(config, secrets, ctx, (c, timeout) -> {
      try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
        ps.setQueryTimeout(timeout);
        for (int i = 0; i < params.size(); i++) {
          ps.setString(i + 1, params.get(i));
        }
        ctx.onCancel(() -> cancel(ps));
        try (ResultSet rs = ps.executeQuery()) {
          H2Results.Read read = H2Results.read(rs, limit, maxBytes(ctx), maxCells(ctx), false);
          ObjectNode structured = NODES.objectNode().put("rowCount", read.table().rows().size())
              .put("truncated", read.truncatedReason() != null);
          ToolResult result = ToolResult.table(read.table(), structured);
          return read.truncatedReason() == null ? result : result.truncated(read.truncatedReason());
        }
      }
    });
  }

  private ToolResult describeTable(ToolCall call, H2Config config, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    String table = blankToNull(call.string("table"));
    if (table == null) {
      throw ConnectorException.badInput("table is required.");
    }
    String schema = blankToNull(call.string("schema"));
    int cells = maxCells(ctx);
    return read(config, secrets, ctx, (c, timeout) -> describe(c, timeout, table, schema, cells));
  }

  private static ToolResult describe(Connection c, int timeout, String table, String schema, int cells)
      throws SQLException {
    // An unquoted name is upper case in H2: Orders and ORDERS are the same table, "Orders" another one.
    String sql = "SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE, ROW_COUNT_ESTIMATE, REMARKS FROM "
        + "INFORMATION_SCHEMA.TABLES WHERE (TABLE_NAME = ? OR TABLE_NAME = UPPER(?))"
        + (schema == null ? "" : " AND (TABLE_SCHEMA = ? OR TABLE_SCHEMA = UPPER(?))")
        + " ORDER BY TABLE_SCHEMA, TABLE_NAME";
    List<String[]> found = new ArrayList<>();
    Long estimated = null;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setQueryTimeout(timeout);
      ps.setString(1, table);
      ps.setString(2, table);
      if (schema != null) {
        ps.setString(3, schema);
        ps.setString(4, schema);
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          found.add(new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(5)});
          if (found.size() == 1) {
            long n = rs.getLong(4);
            estimated = rs.wasNull() ? null : n;
          }
        }
      }
    }
    if (found.size() > 1) {
      // The exact name wins over its upper-case form.
      List<String[]> exact = found.stream().filter(f -> f[1].equals(table)).toList();
      if (exact.size() == 1) {
        found = exact;
      }
    }
    if (found.isEmpty()) {
      return ToolResult.error((schema == null ? "" : schema + ".") + table + " isn't a table or view this user can "
          + "see. h2_list_tables lists them.");
    }
    if (found.size() > 1) {
      List<String> schemas = found.stream().map(f -> f[0]).distinct().toList();
      return ToolResult.error(table + " is in several schemas (" + String.join(", ", schemas) + "): name the "
          + "schema.");
    }
    String[] t = found.getFirst();
    boolean view = "VIEW".equals(t[2]);
    ObjectNode out = NODES.objectNode();
    out.put("schema", t[0]);
    out.put("name", t[1]);
    out.put("kind", view ? "view" : "table");
    if (estimated == null) {
      out.putNull("estimatedRows");
    } else {
      out.put("estimatedRows", estimated);
    }
    putText(out, "comment", t[3], cells);
    ArrayNode columns = out.putArray("columns");
    List<String> lines = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement("SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, "
        + "NUMERIC_PRECISION, NUMERIC_SCALE, IS_NULLABLE, COLUMN_DEFAULT, IS_IDENTITY, GENERATION_EXPRESSION, "
        + "REMARKS FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY "
        + "ORDINAL_POSITION")) {
      ps.setQueryTimeout(timeout);
      ps.setString(1, t[0]);
      ps.setString(2, t[1]);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next() && columns.size() < 2_000) {
          ObjectNode col = columns.addObject();
          String type = type(rs.getString(2), rs.getObject(3), rs.getObject(4), rs.getObject(5));
          boolean nullable = "YES".equalsIgnoreCase(rs.getString(6));
          col.put("name", rs.getString(1));
          col.put("type", type);
          col.put("nullable", nullable);
          putText(col, "default", rs.getString(7), cells);
          col.put("identity", "YES".equalsIgnoreCase(rs.getString(8)));
          putText(col, "generated", rs.getString(9), cells);
          putText(col, "comment", rs.getString(10), cells);
          lines.add(rs.getString(1) + " " + type + (nullable ? "" : " NOT NULL"));
        }
      }
    }
    DatabaseMetaData md = c.getMetaData();
    ArrayNode primaryKey = out.putArray("primaryKey");
    if (!view) {
      List<String[]> keys = new ArrayList<>();
      try (ResultSet rs = md.getPrimaryKeys(null, t[0], t[1])) {
        while (rs.next()) {
          keys.add(new String[] {String.valueOf(rs.getInt("KEY_SEQ")), rs.getString("COLUMN_NAME")});
        }
      }
      keys.stream().sorted((a, b) -> Integer.compare(Integer.parseInt(a[0]), Integer.parseInt(b[0])))
          .forEach(k -> primaryKey.add(k[1]));
    }
    ArrayNode foreignKeys = out.putArray("foreignKeys");
    if (!view) {
      Map<String, ObjectNode> byName = new LinkedHashMap<>();
      try (ResultSet rs = md.getImportedKeys(null, t[0], t[1])) {
        while (rs.next() && byName.size() < 200) {
          String name = rs.getString("FK_NAME");
          ObjectNode fk = byName.computeIfAbsent(name == null ? "fk" + byName.size() : name, n -> {
            ObjectNode o = NODES.objectNode();
            o.put("name", n);
            o.putArray("columns");
            o.put("references", "");
            o.putArray("referencedColumns");
            return o;
          });
          ((ArrayNode) fk.get("columns")).add(rs.getString("FKCOLUMN_NAME"));
          fk.put("references", rs.getString("PKTABLE_SCHEM") + "." + rs.getString("PKTABLE_NAME"));
          ((ArrayNode) fk.get("referencedColumns")).add(rs.getString("PKCOLUMN_NAME"));
        }
      }
      byName.values().forEach(foreignKeys::add);
    }
    ArrayNode indexes = out.putArray("indexes");
    if (!view) {
      Map<String, ObjectNode> byName = new LinkedHashMap<>();
      try (ResultSet rs = md.getIndexInfo(null, t[0], t[1], false, true)) {
        while (rs.next() && byName.size() < 500) {
          String name = rs.getString("INDEX_NAME");
          if (name == null) {
            continue;
          }
          boolean unique = !rs.getBoolean("NON_UNIQUE");
          ObjectNode index = byName.computeIfAbsent(name, n -> {
            ObjectNode o = NODES.objectNode();
            o.put("name", n);
            o.put("unique", unique);
            o.putArray("columns");
            return o;
          });
          String column = rs.getString("COLUMN_NAME");
          if (column != null) {
            ((ArrayNode) index.get("columns")).add(column);
          }
        }
      }
      byName.values().forEach(indexes::add);
    }
    if (view) {
      try (PreparedStatement ps = c.prepareStatement("SELECT VIEW_DEFINITION FROM INFORMATION_SCHEMA.VIEWS WHERE "
          + "TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
        ps.setQueryTimeout(timeout);
        ps.setString(1, t[0]);
        ps.setString(2, t[1]);
        try (ResultSet rs = ps.executeQuery()) {
          putText(out, "viewDefinition", rs.next() ? rs.getString(1) : null, cells);
        }
      }
    } else {
      out.putNull("viewDefinition");
    }
    List<String> keyNames = new ArrayList<>();
    primaryKey.forEach(k -> keyNames.add(k.asString()));
    String head = t[0] + "." + t[1] + " · " + (view ? "view" : "table") + (estimated == null ? "" : " · ~"
        + estimated + " rows") + (keyNames.isEmpty() ? "" : " · primary key (" + String.join(", ", keyNames) + ")");
    return ToolResult.json(out, head + "\n" + String.join("\n", lines));
  }

  /** {@code CHARACTER VARYING(100)}, {@code NUMERIC(10, 2)}: the type with the length or precision it was given. */
  static String type(String dataType, Object length, Object precision, Object scale) {
    if (dataType == null) {
      return "UNKNOWN";
    }
    String t = dataType.toUpperCase(Locale.ROOT);
    if ((t.startsWith("CHARACTER") || t.startsWith("BINARY") || t.equals("VARCHAR")) && length instanceof Number l
        && l.longValue() > 0 && l.longValue() < 1_000_000_000L) {
      return dataType + "(" + l + ")";
    }
    if ((t.equals("NUMERIC") || t.equals("DECIMAL")) && precision instanceof Number p) {
      return dataType + "(" + p + (scale instanceof Number s && s.intValue() > 0 ? ", " + s : "") + ")";
    }
    return dataType;
  }

  private ToolResult change(ToolCall call, H2Config config, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    String sql = statement(call, ctx);
    H2Sql.Verdict v = H2Sql.change(sql);
    if (!v.ok()) {
      throw ConnectorException.denied(v.problem());
    }
    int limit = rows(DEFAULT_QUERY_LIMIT, config, ctx);
    long timeout = timeoutMs(ctx, config);
    long started = System.nanoTime();
    Connection c;
    try {
      c = open(config, secrets, ctx);
    } catch (SQLException e) {
      throw openFailure(e, config, " Nothing changed.");
    }
    boolean sent = false;
    try (c) {
      c.setAutoCommit(false);
      ObjectNode out = NODES.objectNode();
      out.put("statement", v.verb());
      if (v.target() == null) {
        out.putNull("target");
      } else {
        out.put("target", v.target());
      }
      try (Statement st = c.createStatement()) {
        st.setQueryTimeout(seconds(timeout));
        st.setEscapeProcessing(false);
        ctx.onCancel(() -> cancel(st));
        // Exactly the approved text.
        if (st.execute(v.text())) {
          try (ResultSet rs = st.getResultSet()) {
            H2Results.Read read = H2Results.read(rs, limit, maxBytes(ctx), maxCells(ctx), true);
            out.put("rowsChanged", read.total());
            ArrayNode columns = out.putArray("columns");
            read.table().columns().forEach(col -> columns.add(col.name()));
            out.set("returned", read.table().rows());
            out.put("truncated", read.truncatedReason() != null);
          }
        } else {
          long count = st.getUpdateCount();
          if (count < 0) {
            out.putNull("rowsChanged");
          } else {
            out.put("rowsChanged", count);
          }
        }
      } catch (SQLException e) {
        rollback(c);
        return changeFailure(e, ctx);
      }
      sent = true;
      c.commit();
      out.put("elapsedMs", ms(started));
      JsonNode changed = out.path("rowsChanged");
      String text = v.verb() + (v.target() == null ? "" : " " + v.target()) + ": " + (changed.isNumber()
          ? count(changed.asLong(), "row") + " changed." : "done.");
      return ToolResult.json(out, text);
    } catch (SQLException e) {
      if (sent) {
        throw ConnectorException.outcomeUnknown("The commit's answer didn't arrive, so whether the change applied is "
            + "unknown. Check the data before you try again.", e);
      }
      throw ConnectorException.unavailable("The change couldn't run: " + H2Errors.message(e) + " Nothing changed.",
          e);
    }
  }

  /** A read's failure for the agent: H2's refusal of the statement is data; the connection or the deadline aren't. */
  private static ToolResult readFailure(SQLException e, H2Config config, ExecContext ctx, long timeoutMs)
      throws ConnectorException {
    if (H2Errors.cancelled(e)) {
      if (ctx.remaining().toMillis() <= 250 || !ctx.cancelled()) {
        throw ConnectorException.timeout("The query ran past its time limit (" + seconds(timeoutMs) + " s) and H2 "
            + "stopped it. Narrow it down and try again.");
      }
      throw ConnectorException.resourceError("The call was cancelled: H2 stopped the query.", e);
    }
    if (H2Errors.login(e) || H2Errors.noDatabase(e) || H2Errors.connection(e) || H2Errors.versionMismatch(e)) {
      throw openFailure(e, config, "");
    }
    if (H2Errors.rights(e)) {
      return ToolResult.error("The user may not do that: " + H2Errors.message(e));
    }
    return ToolResult.error("H2 refused the query: " + H2Errors.message(e));
  }

  /** A change H2 refused: rolled back, never retried. */
  private static ToolResult changeFailure(SQLException e, ExecContext ctx) throws ConnectorException {
    if (H2Errors.cancelled(e)) {
      throw ConnectorException.timeout("The change ran past its time limit, and H2 stopped it and rolled it back: "
          + "nothing changed.");
    }
    if (H2Errors.connection(e)) {
      throw ConnectorException.outcomeUnknown("The connection broke while the change ran: whether it applied is "
          + "unknown. Check the data before you try again.", e);
    }
    if (H2Errors.rights(e)) {
      return ToolResult.error("The user may not make this change, so nothing changed: " + H2Errors.message(e));
    }
    return ToolResult.error("H2 refused the change, so nothing changed: " + H2Errors.message(e));
  }

  private static ConnectorException openFailure(SQLException e, H2Config config, String after) {
    if (H2Errors.refusedByGate(e)) {
      return ConnectorException.denied("GateRook's network policy refused the connection to " + config.address()
          + "." + after);
    }
    if (H2Errors.login(e)) {
      return ConnectorException.denied("The H2 server refused the user or the password." + after);
    }
    if (H2Errors.noDatabase(e)) {
      return ConnectorException.unavailable("The server has no database " + config.database() + ": GateRook never "
          + "creates one." + after, e);
    }
    if (H2Errors.versionMismatch(e)) {
      return ConnectorException.unavailable("The server's H2 version doesn't speak this connector's protocol: "
          + H2Errors.message(e) + after, e);
    }
    return ConnectorException.unavailable("Can't reach the H2 server at " + config.address() + ": "
        + H2Errors.message(e) + after, e);
  }

  // ── Connections ─────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * A new connection of this call, through the host's TCP gate. The password comes from the core for this call and
   * is never kept.
   */
  private Connection open(H2Config config, SecretResolver secrets, ExecContext ctx)
      throws SQLException, ConnectorException {
    if (needsGate && System.getProperty(GATE_PROPERTY) == null) {
      throw ConnectorException.unavailable("This GateRook doesn't check this connector's TCP connections: update "
          + "GateRook to use the H2 plugin.", null);
    }
    reachable(config);
    Properties props = new Properties();
    props.setProperty("user", config.user());
    String password = secret(secrets, "/password");
    props.setProperty("password", password == null ? "" : password);
    return new org.h2.Driver().connect(config.url(), props);
  }

  /**
   * H2's TCP protocol isn't encrypted: the server must be on this computer or a private network. The TCP gate then
   * also needs the environment's policy to allow the address.
   */
  static void reachable(H2Config config) throws ConnectorException {
    InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(config.host().startsWith("[") ? config.host().substring(1,
          config.host().length() - 1) : config.host());
    } catch (UnknownHostException e) {
      throw ConnectorException.unavailable("Can't resolve " + config.host() + ". Check the host name and the VPN.",
          e);
    }
    for (InetAddress a : addresses) {
      if (!privateOrLocal(a)) {
        throw ConnectorException.denied(config.host() + " resolves to " + a.getHostAddress() + ", an address on "
            + "the internet. H2's TCP protocol isn't encrypted, so GateRook connects to an H2 server only on this "
            + "computer or a private network: reach a remote one through an SSH tunnel or a VPN.");
      }
    }
  }

  /** Loopback, RFC 1918, CGNAT or unique local addresses (also inside an IPv4-mapped IPv6 address). */
  static boolean privateOrLocal(InetAddress a) {
    byte[] b = a.getAddress();
    if (b.length == 16) {
      boolean mapped = true;
      for (int i = 0; i < 10; i++) {
        mapped &= b[i] == 0;
      }
      if (mapped && b[10] == (byte) 0xff && b[11] == (byte) 0xff) {
        b = new byte[] {b[12], b[13], b[14], b[15]};
      }
    }
    if (b.length == 4) {
      int first = b[0] & 0xff;
      int second = b[1] & 0xff;
      return first == 127 || first == 10 || first == 172 && second >= 16 && second <= 31
          || first == 192 && second == 168 || first == 100 && second >= 64 && second <= 127;
    }
    return a.isLoopbackAddress() || (b[0] & 0xfe) == 0xfc;
  }

  /** The password of this call: H2's driver takes it as a property and hashes it before it leaves. */
  private static String secret(SecretResolver secrets, String pointer) throws ConnectorException {
    if (!secrets.isSet(pointer)) {
      return null;
    }
    try (SecretResolver.Secret secret = secrets.secret(pointer)) {
      return secret.unsafeString();
    } catch (ai.msrv.gaterook.connector.api.SecretUnavailableException e) {
      throw ConnectorException.unavailable("The password isn't available: " + e.getMessage(), null);
    }
  }

  private static void rollback(Connection c) {
    try {
      if (!c.isClosed() && !c.getAutoCommit()) {
        c.rollback();
      }
    } catch (SQLException ignored) {
      // The connection closes next: H2 rolls an open transaction back then.
    }
  }

  private static void cancel(Statement st) {
    try {
      st.cancel();
    } catch (SQLException ignored) {
      // It ended already.
    }
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
  static int rows(int requested, H2Config config, ExecContext ctx) {
    int env = ctx.limits() == null || ctx.limits().maxRows() <= 0 ? Integer.MAX_VALUE : ctx.limits().maxRows();
    return Math.max(1, Math.min(Math.min(Math.clamp(requested, 1, MAX_QUERY_LIMIT * 10), config.maxRows()), env));
  }

  /** The policy's statement timeout, or the connection's own where stricter, never past the call's deadline. */
  static long timeoutMs(ExecContext ctx, H2Config config) {
    long limit = ctx.limits() != null && ctx.limits().statementTimeout() != null
        && !ctx.limits().statementTimeout().isZero() ? ctx.limits().statementTimeout().toMillis() : 30_000;
    if (config.statementTimeoutMs() != null) {
      limit = Math.min(limit, config.statementTimeoutMs());
    }
    long left = ctx.remaining().toMillis() - 250;
    return Math.max(1_000, Math.min(limit, left));
  }

  /** JDBC's query timeout is whole seconds: at least one, never longer than the timeout rounded up. */
  private static int seconds(long ms) {
    return (int) Math.max(1, (ms + 999) / 1000);
  }

  private static long maxBytes(ExecContext ctx) {
    return ctx.limits() == null || ctx.limits().maxResponseBytes() <= 0 ? 524_288 : ctx.limits().maxResponseBytes();
  }

  private static int maxCells(ExecContext ctx) {
    return ctx.limits() == null || ctx.limits().maxCellChars() <= 0 ? 8_192 : ctx.limits().maxCellChars();
  }

  // ── Small things ────────────────────────────────────────────────────────────────────────────────────────────

  private Optional<ToolDefinition> tool(String name) {
    return manifest.tools().stream().filter(t -> t.name().equals(name)).findFirst();
  }

  private static void putText(ObjectNode out, String name, String value, int cells) {
    if (value == null) {
      out.putNull(name);
    } else {
      out.set(name, H2Results.text(value, cells));
    }
  }

  private static String blankToNull(String v) {
    return v == null || v.isBlank() ? null : v.strip();
  }

  private static String count(long n, String noun) {
    return n + " " + noun + (n == 1 ? "" : "s");
  }

  private static String shorten(String s) {
    return s.length() <= 120 ? s : s.substring(0, 119) + "…";
  }

  private static long ms(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
