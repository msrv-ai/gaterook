package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.TemplateKind;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.TestResult.Hint;
import ai.msrv.gaterook.connector.api.TestResult.Severity;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.sql.SqlExecutor.BoundSql;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The MySQL connector against a real server, MySQL 8.4 or MariaDB 11.4 (report 04 §8): the same scenarios as on
 * PostgreSQL where the server has them. Reads run as {@code rw}, who may write.
 */
abstract class MysqlFamilyIT {

  /** One connector per test: each subclass runs against its own server. */
  final JdbcSqlConnector CONNECTOR = new JdbcSqlConnector(new MySqlDialect());
  static final Path WORK = Path.of("target", "mysql-work").toAbsolutePath();

  abstract LocalMysql server();

  /** A read that runs longer than half a second unless the server stops it. */
  abstract String slowQuery();

  /** MySQL 8.4 users authenticate with caching_sha2_password: without TLS that needs the server's RSA key. */
  abstract String sslMode();

  @AfterEach
  void close() {
    CONNECTOR.close();
  }

  ConnectionConfig cfg(String user, Env env) {
    ConnectionConfig base = Fixtures.config("mysql", env, "127.0.0.1", server().port(), "app", user, null, null);
    ObjectNode c = (ObjectNode) base.config().deepCopy();
    ((ObjectNode) c.get("connection")).put("sslMode", sslMode());
    return new ConnectionConfig(base.id() + "-" + server().flavor() + "-" + user, base.name(), base.type(), env, 1,
        c, base.secretFields(), null);
  }

  static Fixtures.Secrets secrets(String user) {
    return Fixtures.Secrets.password(LocalMysql.password(user));
  }

  static Fixtures.Context ctx() {
    return Fixtures.Context.of(Env.DEV, WORK);
  }

  long count(String sql) throws SQLException {
    try (Connection c = server().root(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  ToolResult read(String user, String sql) throws ConnectorException {
    ConnectionConfig cfg = cfg(user, Env.DEV);
    return CONNECTOR.runRead(cfg, ConnectionSettings.of(cfg, true), secrets(user), ctx(), BoundSql.plain(sql), 100);
  }

  // ── The guarantee ───────────────────────────────────────────────────────────────────────────────────────

  @Test
  void readOnlyStopsAWritingFunctionThatTheClassifierWouldLetThrough() throws Exception {
    long before = count("SELECT COUNT(*) FROM app.audit_log");

    assertThatThrownBy(() -> read("rw", "SELECT app.f_write()"))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
          assertThat(e.getMessage()).contains("read-only transaction refused it").contains("READ ONLY");
        });
    assertThat(count("SELECT COUNT(*) FROM app.audit_log")).isEqualTo(before);
  }

  @Test
  void readOnlyStopsAWriteOutright() throws Exception {
    long before = count("SELECT COUNT(*) FROM app.order_items");

    // Connector/J refuses it before the server can (S1009); either way the read-only transaction is the reason.
    assertThatThrownBy(() -> read("rw", "DELETE FROM app.order_items"))
        .hasMessageContaining("the read-only transaction refused it");
    assertThat(count("SELECT COUNT(*) FROM app.order_items")).isEqualTo(before);
  }

  @Test
  void theSessionReadsQuotesTheWayTheGatewayDoes() throws Exception {
    // A session that had ANSI_QUOTES or NO_BACKSLASH_ESCAPES would read these differently from the lexer.
    ToolResult r = read("rw", "SELECT 'it\\'s', \"double\", @@SESSION.sql_mode");

    JsonNode row = r.table().rows().get(0);
    assertThat(row.get(0).asString()).isEqualTo("it's");
    assertThat(row.get(1).asString()).isEqualTo("double");
    assertThat(row.get(2).asString()).doesNotContain("ANSI_QUOTES").doesNotContain("NO_BACKSLASH_ESCAPES");
  }

  @Test
  void aSlowQueryStopsAtTheStatementTimeout() {
    ConnectionConfig cfg = cfg("rw", Env.DEV);
    Fixtures.Context ctx = ctx().with(Fixtures.limits(100, Duration.ofMillis(500)));
    long started = System.nanoTime();

    assertThatThrownBy(() -> CONNECTOR.runRead(cfg, ConnectionSettings.of(cfg, true), secrets("rw"), ctx,
        BoundSql.plain(slowQuery()), 10))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.getMessage())
            .isEqualTo("Statement timeout (0.5 s on dev) exceeded. Narrow the WHERE clause or add LIMIT."));
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(8));
  }

  // ── Tools ───────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void queryReturnsTypedRowsWithTheirOrigin() throws Exception {
    ToolCall call = Fixtures.sql("sql_query", "SELECT o.id, o.status, o.total, o.created_at, c.email AS contact "
        + "FROM orders o JOIN customers c ON c.id = o.customer_id WHERE o.id = 48213");
    assertThat(CONNECTOR.classify(call, cfg("ro", Env.DEV), Fixtures.policy(Env.DEV, null)).kind())
        .isEqualTo(Classification.Kind.READ);

    ToolResult r = CONNECTOR.execute(call, cfg("ro", Env.DEV), secrets("ro"), ctx());

    JsonNode row = r.table().rows().get(0);
    assertThat(row.get(0).asLong()).isEqualTo(48213);
    assertThat(row.get(2).asString()).isEqualTo("120.50");
    assertThat(row.get(3).asString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
    ToolResult.Column contact = r.table().columns().get(4);
    assertThat(contact.name()).isEqualTo("contact");
    assertThat(contact.sourceSchema()).isEqualTo("app");
    assertThat(contact.sourceTable()).isEqualTo("customers");
    assertThat(contact.sourceColumn()).isEqualTo("email");
  }

  @Test
  void queryStopsAtTheRowLimit() throws Exception {
    ToolCall call = Fixtures.call("sql_query", Fixtures.object().put("sql",
        "WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r WHERE n < 500) SELECT n FROM r")
        .put("limit", 1000));

    ToolResult r = CONNECTOR.execute(call, cfg("ro", Env.DEV), secrets("ro"), ctx().with(Fixtures.limits(25,
        Duration.ofSeconds(5))));

    assertThat(r.table().rows()).hasSize(25);
    assertThat(r.truncatedReason()).isEqualTo("row_limit");
  }

  @Test
  void aChangeNeedsApprovalAndRunsAsApproved() throws Exception {
    server().exec("INSERT IGNORE INTO app.orders (id, customer_id, status) VALUES (90001, 1, 'pending')");
    ToolCall call = Fixtures.sql("sql_execute", "UPDATE orders SET status = 'cancelled' WHERE id = 90001");
    assertThatThrownBy(() -> CONNECTOR.execute(call, cfg("rw", Env.DEV), secrets("rw"), ctx()))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));

    ActionPreview preview = CONNECTOR.describeAction(call, cfg("rw", Env.DEV));
    ToolResult r = CONNECTOR.execute(call, cfg("rw", Env.DEV), secrets("rw"), ctx().approved(preview.payload()));

    assertThat(r.content().getFirst().text()).isEqualTo("1 row updated");
    assertThat(count("SELECT COUNT(*) FROM app.orders WHERE id = 90001 AND status = 'cancelled'")).isEqualTo(1);
  }

  @Test
  void prepareEstimatesRowsWithoutChangingThem() throws Exception {
    ToolCall call = Fixtures.sql("sql_execute", "UPDATE orders SET status = 'cancelled' WHERE id = 48213");

    PreparedAction p = CONNECTOR.prepare(call, cfg("rw", Env.DEV), secrets("rw"), ctx());

    assertThat(p.outcome()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    assertThat(p.preview().impact()).contains(new ActionPreview.Field("Estimated rows", "1 (from EXPLAIN)"),
        new ActionPreview.Field("Target table", "orders"));
    assertThat(count("SELECT COUNT(*) FROM app.orders WHERE id = 48213 AND status = 'pending'")).isEqualTo(1);

    PreparedAction typo = CONNECTOR.prepare(Fixtures.sql("sql_execute", "UPDATE ordrs SET status = 'x'"),
        cfg("rw", Env.DEV), secrets("rw"), ctx());
    assertThat(typo.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(typo.message()).contains("ERROR 1146 (42S02): Table 'app.ordrs' doesn't exist");
  }

  @Test
  void templateValuesAreBoundByTheServer() throws Exception {
    TemplateKind.CompileResult compiled = CONNECTOR.templateKinds().getFirst().compile(Fixtures.json("""
        {"name": "orders_by_status",
         "sql": "SELECT id, status FROM orders WHERE status = :status AND (note IS NULL OR note <> :note) ORDER BY id",
         "params": [{"name": "status", "type": "enum", "values": ["pending", "paid", "shipped", "cancelled"]},
                    {"name": "note", "type": "string", "required": false, "default": "x"}]}
        """), cfg("ro", Env.DEV));
    assertThat(compiled.errors()).isEmpty();
    TemplateKind.CompiledTool tool = compiled.tools().getFirst();

    ToolCall ok = new ToolCall("tc_t1", "orders_by_status", "dev-db", Fixtures.object().put("status", "shipped"),
        new ToolCall.TemplateBinding("tpl", 1, "q", tool.binding()));
    assertThat(CONNECTOR.execute(ok, cfg("ro", Env.DEV), secrets("ro"), ctx()).table().rows())
        .extracting(r -> r.get(0).asLong()).containsExactly(48241L);

    ToolCall injection = new ToolCall("tc_t2", "orders_by_status", "dev-db", Fixtures.object()
        .put("status", "shipped").put("note", "x' OR '1'='1"), new ToolCall.TemplateBinding("tpl", 1, "q",
        tool.binding()));
    assertThat(CONNECTOR.execute(injection, cfg("ro", Env.DEV), secrets("ro"), ctx()).table().rows())
        .extracting(r -> r.get(0).asLong()).containsExactly(48241L);
  }

  @Test
  void listSchemasAndDescribeTableShowWhatTheUserMaySee() throws Exception {
    ToolCall list = Fixtures.call("sql_list_schemas", Fixtures.object());
    CONNECTOR.classify(list, cfg("ro", Env.DEV), Fixtures.policy(Env.DEV, null));
    ToolResult all = CONNECTOR.execute(list, cfg("ro", Env.DEV), secrets("ro"), ctx());

    assertThat(all.structuredContent().path("schemas").findValuesAsString("name")).containsExactly("app");
    assertThat(all.structuredContent().path("tables").findValuesAsString("name"))
        .contains("orders", "customers").doesNotContain("keys");
    assertThat(PostgresIT.validate(CONNECTOR.manifest().tools().getFirst().outputSchema(), all.structuredContent()))
        .isEmpty();

    ToolResult d = CONNECTOR.execute(Fixtures.call("sql_describe_table", Fixtures.object().put("table", "orders")),
        cfg("ro", Env.DEV), secrets("ro"), ctx());
    JsonNode t = d.structuredContent();
    assertThat(t.path("schema").asString()).isEqualTo("app");
    assertThat(t.path("primaryKey").toString()).isEqualTo("[\"id\"]");
    assertThat(t.path("foreignKeys").get(0).path("refTable").asString()).isEqualTo("customers");
    assertThat(t.path("indexes").findValuesAsString("name")).contains("orders_status_idx");
    assertThat(PostgresIT.validate(CONNECTOR.manifest().tools().get(1).outputSchema(), t)).isEmpty();

    assertThatThrownBy(() -> CONNECTOR.execute(Fixtures.call("sql_describe_table", Fixtures.object()
        .put("table", "secret_stuff.keys")), cfg("ro", Env.DEV), secrets("ro"), ctx()))
        .hasMessage("Table secret_stuff.keys not found on dev-db. Call sql_list_schemas to see the tables.");
  }

  // ── Test connection, health, rotation ───────────────────────────────────────────────────────────────────

  private TestResult test(String user, Env env) {
    return CONNECTOR.test(cfg(user, env), secrets(user), Fixtures.Context.of(env, WORK));
  }

  private static Hint hint(TestResult r, String code) {
    return r.hints().stream().filter(h -> h.code().equals(code)).findFirst().orElse(null);
  }

  @Test
  void testConnectionSaysWhatEachUserMayDo() {
    TestResult ro = test("ro", Env.DEV);
    assertThat(ro.ok()).as(String.valueOf(ro.error())).isTrue();
    assertThat(ro.identity()).isEqualTo("ro@%");
    assertThat(ro.permissions().level()).isEqualTo(TestResult.Level.READ);
    assertThat(ro.hints()).noneMatch(h -> h.severity() == Severity.DANGER);
    assertThat(hint(ro, "sql.user-can-write")).isNull();

    TestResult rw = test("rw", Env.PROD);
    assertThat(hint(rw, "sql.user-can-write").severity()).isEqualTo(Severity.WARNING);
    assertThat(rw.permissions().write()).contains("on app.*");
    assertThat(rw.details().at("/snippets/sql.user-can-write/text").asString()).contains("GRANT SELECT, SHOW VIEW ON")
        .doesNotContain(LocalMysql.password("rw"));

    TestResult filer = test("filer", Env.DEV);
    assertThat(hint(filer, "sql.dangerous-privilege").text()).isEqualTo("filer@% has FILE: it can read and write "
        + "files on the database server. Use a user without it.");

    TestResult admin = test("admin", Env.DEV);
    assertThat(hint(admin, "sql.superuser").severity()).isEqualTo(Severity.DANGER);
    assertThat(admin.permissions().level()).isEqualTo(TestResult.Level.ADMIN);

    TestResult wrong = CONNECTOR.test(cfg("ro", Env.DEV), Fixtures.Secrets.password("nope-nope"),
        Fixtures.Context.of(Env.DEV, WORK));
    assertThat(wrong.error().code()).isEqualTo("sql.auth");
    assertThat(wrong.error().message()).isEqualTo("Password authentication failed for user ro.");
  }

  @Test
  void healthIsADirectSelectOne() {
    HealthResult h = CONNECTOR.health(cfg("ro", Env.DEV), secrets("ro"), ctx());
    assertThat(h.state()).isEqualTo(HealthResult.State.HEALTHY);
  }

  @Test
  void rotateSetsTheNewPasswordOfTheConnectionsOwnUser() throws Exception {
    ConnectionConfig cfg = cfg("rotator", Env.DEV);

    CONNECTOR.rotate(cfg, secrets("rotator"), ConnectionSettings.PASSWORD, "rotated-pw-000000002".toCharArray(),
        ctx());

    try (Connection c = server().connect("rotator", "rotated-pw-000000002")) {
      assertThat(c.isValid(2)).isTrue();
    }
    assertThatThrownBy(() -> server().connect("rotator", LocalMysql.password("rotator")))
        .isInstanceOf(SQLException.class);
  }
}
