package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.networknt.schema.Error;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.TemplateKind;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.sql.SqlExecutor.BoundSql;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The PostgreSQL connector against a real PostgreSQL 16 (report 04 §8, ARCHITECTURE.md §9 "SQL reads cannot write").
 * Every read runs as {@code rw}, a user that may write: what keeps a read from writing is the READ ONLY transaction,
 * not the user's grants.
 */
class PostgresIT {

  @RegisterExtension
  static final LocalPostgres PG = new LocalPostgres();

  private static final JdbcSqlConnector CONNECTOR = new JdbcSqlConnector(new PostgresDialect());
  private static final Path WORK = Path.of("target", "pg-work").toAbsolutePath();

  @AfterAll
  static void close() {
    CONNECTOR.close();
  }

  private static ConnectionConfig cfg(String user, Env env, ObjectNode advanced) {
    return Fixtures.config("postgres", env, "127.0.0.1", PG.port(), "app", user, advanced, null);
  }

  private static ConnectionConfig rw() {
    return cfg("rw", Env.DEV, null);
  }

  private static Fixtures.Secrets secrets(String user) {
    return Fixtures.Secrets.password(LocalPostgres.password(user));
  }

  private static Fixtures.Context ctx() {
    return Fixtures.Context.of(Env.DEV, WORK);
  }

  private static long count(String sql) throws SQLException {
    try (Connection c = PG.superuser(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static ToolResult read(ConnectionConfig cfg, String sql) throws ConnectorException {
    return CONNECTOR.runRead(cfg, ConnectionSettings.of(cfg, false), secrets(cfg.string("/credentials/user")), ctx(),
        BoundSql.plain(sql), 100);
  }

  // ── The guarantee: a read doesn't write, with the classifier off ──────────────────────────────────────────

  @Test
  void readOnlyStopsAWritingFunctionThatTheClassifierWouldLetThrough() throws Exception {
    long before = count("SELECT count(*) FROM audit_log");

    assertThatThrownBy(() -> read(rw(), "SELECT f_write()"))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
          assertThat(e.getMessage()).contains("read-only transaction refused it").contains("25006");
        });
    assertThat(count("SELECT count(*) FROM audit_log")).isEqualTo(before);
  }

  @Test
  void readOnlyStopsNextval() throws Exception {
    long before = count("SELECT last_value FROM ticket_seq");

    assertThatThrownBy(() -> read(rw(), "SELECT nextval('ticket_seq')"))
        .hasMessageContaining("cannot execute nextval() in a read-only transaction");
    assertThat(count("SELECT last_value FROM ticket_seq")).isEqualTo(before);
  }

  @Test
  void readOnlyStopsADeleteInACte() throws Exception {
    long before = count("SELECT count(*) FROM order_items");

    assertThatThrownBy(() -> read(rw(), "WITH d AS (DELETE FROM order_items RETURNING *) SELECT count(*) FROM d"))
        .hasMessageContaining("read-only transaction");
    assertThat(count("SELECT count(*) FROM order_items")).isEqualTo(before);
  }

  @Test
  void readOnlyStopsForUpdate() {
    assertThatThrownBy(() -> read(rw(), "SELECT * FROM orders FOR UPDATE"))
        .hasMessageContaining("read-only transaction");
  }

  @Test
  void aReadLeavesItsPooledSessionClean() throws Exception {
    ObjectNode one = Fixtures.object().put("poolSize", 1);
    ConnectionConfig cfg = Fixtures.withVersion(cfg("rw", Env.DEV, one), 7);
    ConnectionSettings s = ConnectionSettings.of(cfg, false);

    CONNECTOR.runRead(cfg, s, secrets("rw"), ctx(), BoundSql.plain(
        "SELECT set_config('application_name', 'dirty', false), set_config('search_path', 'secret_stuff', false)"),
        10);
    ToolResult after = CONNECTOR.runRead(cfg, s, secrets("rw"), ctx(), BoundSql.plain(
        "SELECT current_setting('application_name'), current_setting('search_path'), "
            + "current_setting('transaction_read_only')"), 10);

    JsonNode row = after.table().rows().get(0);
    assertThat(row.get(0).asString()).isEqualTo("gaterook");
    assertThat(row.get(1).asString()).isEqualTo("public");
    assertThat(row.get(2).asString()).isEqualTo("on");
    assertThat(CONNECTOR.pools().current(cfg.id()).total()).isEqualTo(1);
  }

  @Test
  void aFunctionThatIsntBuiltInWaitsForAPersonAndStillRunsReadOnly() throws Exception {
    ConnectionConfig cfg = rw();
    ToolCall call = Fixtures.sql("sql_query", "SELECT f_write()");

    assertThat(CONNECTOR.classify(call, cfg, Fixtures.policy(Env.DEV, null)).kind())
        .isEqualTo(Classification.Kind.MUTATION);
    assertThat(CONNECTOR.classify(call, cfg, Fixtures.policy(Env.PROD, null)).ruleId())
        .isEqualTo("prod.sql.function-not-built-in");
    assertThatThrownBy(() -> CONNECTOR.execute(call, cfg, secrets("rw"), ctx()))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));

    ActionPreview preview = CONNECTOR.describeAction(call, cfg);
    assertThat(preview.warnings()).anySatisfy(w -> assertThat(w).contains("f_write").contains("read-only"));
    long before = count("SELECT count(*) FROM audit_log");
    // Approved: it runs, as a read, and the database refuses the write inside it.
    assertThatThrownBy(() -> CONNECTOR.execute(call, cfg, secrets("rw"), ctx().approved(preview.payload())))
        .hasMessageContaining("read-only transaction");
    assertThat(count("SELECT count(*) FROM audit_log")).isEqualTo(before);
  }

  // ── sql_query ───────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void queryReturnsTypedRowsWithTheirOrigin() throws Exception {
    ToolCall call = Fixtures.sql("sql_query", "SELECT o.id, o.status, o.total, o.meta, o.tags, o.created_at, "
        + "c.email AS contact FROM orders o JOIN customers c ON c.id = o.customer_id WHERE o.id = 48213");
    assertThat(CONNECTOR.classify(call, rw(), Fixtures.policy(Env.DEV, null)).kind())
        .isEqualTo(Classification.Kind.READ);

    ToolResult r = CONNECTOR.execute(call, rw(), secrets("rw"), ctx());

    assertThat(r.table().columns()).extracting(ToolResult.Column::name)
        .containsExactly("id", "status", "total", "meta", "tags", "created_at", "contact");
    ToolResult.Column contact = r.table().columns().get(6);
    assertThat(contact.sourceSchema()).isEqualTo("public");
    assertThat(contact.sourceTable()).isEqualTo("customers");
    assertThat(contact.sourceColumn()).isEqualTo("email");
    JsonNode row = r.table().rows().get(0);
    assertThat(row.get(0).isNumber()).isTrue();
    assertThat(row.get(0).asLong()).isEqualTo(48213);
    assertThat(row.get(2).asString()).isEqualTo("120.50");
    assertThat(row.get(4).toString()).isEqualTo("[\"a\",\"b\"]");
    assertThat(row.get(5).asString()).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
    assertThat(r.truncated()).isFalse();
  }

  @Test
  void queryStopsAtTheRowLimit() throws Exception {
    ToolCall call = Fixtures.call("sql_query", Fixtures.object().put("sql",
        "SELECT g FROM generate_series(1, 5000) g").put("limit", 1000));

    ToolResult r = CONNECTOR.execute(call, rw(), secrets("rw"), ctx().with(Fixtures.limits(25,
        Duration.ofSeconds(5))));

    assertThat(r.table().rows()).hasSize(25);
    assertThat(r.truncated()).isTrue();
    assertThat(r.truncatedReason()).isEqualTo("row_limit");
  }

  @Test
  void aSlowQueryStopsAtTheStatementTimeout() {
    ToolCall call = Fixtures.sql("sql_query", "SELECT pg_sleep(10)");
    Fixtures.Context ctx = ctx().with(Fixtures.limits(100, Duration.ofMillis(500)));
    long started = System.nanoTime();

    assertThatThrownBy(() -> CONNECTOR.execute(call, rw(), secrets("rw"), ctx))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
          assertThat(e.getMessage()).isEqualTo("Statement timeout (0.5 s on dev) exceeded. Narrow the WHERE clause "
              + "or add LIMIT.");
        });
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
  }

  @Test
  void theConnectionsOwnStatementTimeoutAppliesWhereItIsStricter() throws Exception {
    ToolCall call = Fixtures.sql("sql_query", "SELECT pg_sleep(10)");
    ConnectionConfig strict = cfg("rw", Env.DEV, Fixtures.object().put("statementTimeoutMs", 400));

    assertThatThrownBy(() -> CONNECTOR.execute(call, strict, secrets("rw"), ctx()))
        .hasMessage("Statement timeout (0.4 s on dev) exceeded. Narrow the WHERE clause or add LIMIT.");

    // A looser one leaves the policy's: 0.5 s here, not 10 s.
    ConnectionConfig loose = cfg("rw", Env.DEV, Fixtures.object().put("statementTimeoutMs", 10_000));
    assertThatThrownBy(() -> CONNECTOR.execute(call, loose, secrets("rw"),
        ctx().with(Fixtures.limits(100, Duration.ofMillis(500)))))
        .hasMessageStartingWith("Statement timeout (0.5 s on dev)");

    PreparedAction p = CONNECTOR.prepare(Fixtures.sql("sql_execute", "UPDATE orders SET note = 'x' WHERE id = -1"),
        cfg("rw", Env.DEV, Fixtures.object().put("statementTimeoutMs", 1_500)), secrets("rw"), ctx());
    assertThat(p.preview().impact()).contains(new ActionPreview.Field("Statement timeout", "1.5 s"));
  }

  @Test
  void cancellingTheCallCancelsTheStatement() throws Exception {
    ToolCall call = Fixtures.sql("sql_query", "SELECT pg_sleep(20)");
    Fixtures.Context ctx = ctx().with(Fixtures.limits(100, Duration.ofSeconds(30))).timeout(Duration.ofSeconds(60));
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<ToolResult> running = pool.submit(() -> CONNECTOR.execute(call, rw(), secrets("rw"), ctx));
      Thread.sleep(700);
      ctx.cancel();
      assertThatThrownBy(() -> running.get(10, TimeUnit.SECONDS)).cause()
          .isInstanceOfSatisfying(ConnectorException.class, e -> {
            assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
            assertThat(e.getMessage()).isEqualTo("The call was cancelled: the statement was stopped and rolled back.");
          });
    }
  }

  @Test
  void anUnknownTableIsTheDatabasesError() {
    assertThatThrownBy(() -> CONNECTOR.execute(Fixtures.sql("sql_query", "SELECT * FROM ordrs"), rw(),
        secrets("rw"), ctx()))
        .hasMessage("ERROR 42P01: relation \"ordrs\" does not exist (position 15)");
  }

  // ── sql_execute ─────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aChangeNeedsApprovalAndRunsAsApproved() throws Exception {
    PG.exec("INSERT INTO orders (id, customer_id, status) VALUES (90001, 1, 'pending') ON CONFLICT DO NOTHING");
    ToolCall call = Fixtures.sql("sql_execute", "UPDATE orders SET status = 'cancelled' WHERE id = 90001 "
        + "RETURNING id, status");
    assertThatThrownBy(() -> CONNECTOR.execute(call, rw(), secrets("rw"), ctx()))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    assertThat(count("SELECT count(*) FROM orders WHERE id = 90001 AND status = 'pending'")).isEqualTo(1);

    ActionPreview preview = CONNECTOR.describeAction(call, rw());
    ToolResult r = CONNECTOR.execute(call, rw(), secrets("rw"), ctx().approved(preview.payload()));

    assertThat(r.content().getFirst().text()).isEqualTo("1 row updated");
    assertThat(r.structuredContent().path("committed").asBoolean()).isTrue();
    assertThat(r.structuredContent().at("/statements/0/returning/0/status").asString()).isEqualTo("cancelled");
    assertThat(count("SELECT count(*) FROM orders WHERE id = 90001 AND status = 'cancelled'")).isEqualTo(1);
  }

  @Test
  void theApprovedPayloadRunsNotTheArguments() throws Exception {
    PG.exec("INSERT INTO orders (id, customer_id, status) VALUES (90002, 1, 'pending') ON CONFLICT DO NOTHING");
    ToolCall call = Fixtures.sql("sql_execute", "DELETE FROM orders WHERE id = 90002");
    ObjectNode payload = Fixtures.object().put("sql", "UPDATE orders SET note = 'approved' WHERE id = 90002");

    CONNECTOR.execute(call, rw(), secrets("rw"), ctx().approved(payload));

    assertThat(count("SELECT count(*) FROM orders WHERE id = 90002 AND note = 'approved'")).isEqualTo(1);
  }

  @Test
  void statementsOfOneCallCommitTogetherOrNotAtAll() throws Exception {
    PG.exec("INSERT INTO orders (id, customer_id, status) VALUES (90003, 1, 'pending') ON CONFLICT DO NOTHING");
    String sql = "UPDATE orders SET status = 'paid' WHERE id = 90003; INSERT INTO order_items (order_id, sku, qty) "
        + "VALUES (90003, 'Z-1', NULL)";

    assertThatThrownBy(() -> CONNECTOR.execute(Fixtures.sql("sql_execute", sql), rw(), secrets("rw"),
        ctx().approved(Fixtures.object().put("sql", sql))))
        .hasMessageContaining("23502");
    assertThat(count("SELECT count(*) FROM orders WHERE id = 90003 AND status = 'pending'")).isEqualTo(1);

    String ok = "UPDATE orders SET status = 'paid' WHERE id = 90003; INSERT INTO order_items (order_id, sku, qty) "
        + "VALUES (90003, 'Z-1', 1), (90003, 'Z-2', 2)";
    ToolResult r = CONNECTOR.execute(Fixtures.sql("sql_execute", ok), rw(), secrets("rw"),
        ctx().approved(Fixtures.object().put("sql", ok)));

    assertThat(r.content().getFirst().text()).isEqualTo("Committed 2 statements: 1 row updated, 2 rows inserted");
    assertThat(r.structuredContent().path("rowsAffected").asLong()).isEqualTo(3);
    assertThat(count("SELECT count(*) FROM order_items WHERE order_id = 90003")).isEqualTo(2);
  }

  @Test
  void prepareEstimatesRowsWithoutChangingThem() throws Exception {
    ToolCall call = Fixtures.sql("sql_execute", "UPDATE orders SET status = 'cancelled' WHERE id = 48213");
    long pending = count("SELECT count(*) FROM orders WHERE id = 48213 AND status = 'pending'");

    PreparedAction p = CONNECTOR.prepare(call, rw(), secrets("rw"), ctx());

    assertThat(p.outcome()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    assertThat(p.preview().text()).isEqualTo("UPDATE orders SET status = 'cancelled' WHERE id = 48213");
    assertThat(p.preview().impact()).extracting(ActionPreview.Field::label)
        .containsExactly("Target table", "Estimated rows", "Transaction", "Statement timeout");
    assertThat(p.preview().impact()).contains(new ActionPreview.Field("Target table", "orders"),
        new ActionPreview.Field("Estimated rows", "1 (from EXPLAIN)"),
        new ActionPreview.Field("Transaction", "Yes, rolled back on error"),
        new ActionPreview.Field("Statement timeout", "5 s"));
    assertThat(count("SELECT count(*) FROM orders WHERE id = 48213 AND status = 'pending'")).isEqualTo(pending);
  }

  @Test
  void prepareRefusesWhatTheDatabaseCantRun() throws Exception {
    PreparedAction typo = CONNECTOR.prepare(Fixtures.sql("sql_execute", "UPDATE ordrs SET status = 'x'"), rw(),
        secrets("rw"), ctx());
    assertThat(typo.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(typo.message()).contains("nobody was asked to approve it").contains("42P01");

    // A table the first statement creates: the second can't be planned yet, which is no reason to refuse.
    PreparedAction later = CONNECTOR.prepare(Fixtures.sql("sql_execute",
        "CREATE TABLE tmp_later (id int); INSERT INTO tmp_later VALUES (1)"), rw(), secrets("rw"), ctx());
    assertThat(later.outcome()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    assertThat(later.preview().warnings()).anySatisfy(w -> assertThat(w).contains("couldn't be estimated"));
  }

  @Test
  void whatEXPLAINCantPlanStillGoesToAPerson() throws Exception {
    for (String sql : List.of("CALL p_write()", "EXPLAIN ANALYZE DELETE FROM audit_log WHERE id < 0")) {
      PreparedAction p = CONNECTOR.prepare(Fixtures.sql("sql_execute", sql), rw(), secrets("rw"), ctx());
      assertThat(p.outcome()).as(sql).isEqualTo(PreparedAction.Outcome.APPROVAL);
      assertThat(p.preview().impact()).extracting(ActionPreview.Field::label).as(sql)
          .doesNotContain("Estimated rows");
    }
    PreparedAction denied = CONNECTOR.prepare(Fixtures.sql("sql_execute", "DELETE FROM secret_stuff.keys"),
        cfg("ro", Env.DEV, null), secrets("ro"), ctx());
    assertThat(denied.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(denied.message()).contains("42501");
  }

  // ── Templates ───────────────────────────────────────────────────────────────────────────────────────────

  /** The kit's template (Connectors › Templates). */
  static final String ORDERS_BY_STATUS = """
      {"name": "orders_by_status",
       "description": "Orders with one status created after a point in time, newest first. Returns at most 200 rows.",
       "sql": "SELECT id, status, total, created_at\\nFROM orders\\nWHERE status = :status\\n  AND created_at >= :since\\nORDER BY created_at DESC\\nLIMIT 200",
       "params": [
         {"name": "status", "type": "enum", "values": ["pending", "paid", "shipped", "cancelled"],
          "description": "Order status to match"},
         {"name": "since", "type": "timestamp", "required": false, "default": "-PT24H",
          "description": "Earliest created_at, ISO 8601"}]}
      """;

  @Test
  void ordersByStatusIsTheKitsReadOnlyTool() throws Exception {
    TemplateKind kind = CONNECTOR.templateKinds().getFirst();
    TemplateKind.CompileResult compiled = kind.compile(Fixtures.json(ORDERS_BY_STATUS), rw());

    assertThat(compiled.errors()).isEmpty();
    TemplateKind.CompiledTool tool = compiled.tools().getFirst();
    ToolDefinition d = tool.definition();
    assertThat(d.name()).isEqualTo("orders_by_status");
    assertThat(d.defaultMode()).isEqualTo(ToolDefinition.Mode.READ_ONLY);
    assertThat(d.annotations().readOnlyHint()).isTrue();
    assertThat(d.inputSchema()).isEqualTo(Fixtures.json("""
        {"type": "object",
         "properties": {
           "status": {"type": "string", "enum": ["pending", "paid", "shipped", "cancelled"],
                      "description": "Order status to match"},
           "since": {"type": "string", "format": "date-time",
                     "description": "Earliest created_at, ISO 8601. Default: 24 hours ago"}},
         "required": ["status"]}
        """));

    ToolCall call = new ToolCall("tc_tpl", "orders_by_status", "dev-db", Fixtures.object().put("status", "pending"),
        new ToolCall.TemplateBinding("tpl_1", 3, tool.opKey(), tool.binding()));
    assertThat(CONNECTOR.classify(call, rw(), Fixtures.policy(Env.DEV, null)).kind())
        .isEqualTo(Classification.Kind.READ);

    ToolResult r = CONNECTOR.execute(call, rw(), secrets("rw"), ctx());

    // 48213 is two hours old, 48240 three days: the default "since" is 24 hours ago.
    assertThat(r.table().rows()).extracting(row -> row.get(0).asLong()).contains(48213L).doesNotContain(48240L);
    assertThat(validate(compiledOutput(d), tableAsOutput(r))).isEmpty();
  }

  @Test
  void aTemplateValueIsBoundNeverPastedIntoTheSql() throws Exception {
    TemplateKind.CompiledTool tool = CONNECTOR.templateKinds().getFirst().compile(Fixtures.json("""
        {"name": "orders_with_note", "sql": "SELECT id FROM orders WHERE note = :note OR meta ? 'vip'",
         "params": [{"name": "note", "type": "string"}]}
        """), rw()).tools().getFirst();
    ToolCall call = new ToolCall("tc_bind", "orders_with_note", "dev-db",
        Fixtures.object().put("note", "x' OR '1'='1"), new ToolCall.TemplateBinding("tpl_2", 1, "q", tool.binding()));

    ToolResult r = CONNECTOR.execute(call, rw(), secrets("rw"), ctx());

    // Only the row with meta ? 'vip' (PostgreSQL's ? operator survives as ?? in the prepared statement).
    assertThat(r.table().rows()).extracting(row -> row.get(0).asLong()).containsExactly(48213L);
  }

  @Test
  void aChangingTemplateWaitsForApprovalAndRunsTheApprovedValues() throws Exception {
    PG.exec("INSERT INTO orders (id, customer_id, status) VALUES (90004, 1, 'pending') ON CONFLICT DO NOTHING");
    TemplateKind.CompiledTool tool = CONNECTOR.templateKinds().getFirst().compile(Fixtures.json("""
        {"name": "cancel_order", "sql": "UPDATE orders SET status = 'cancelled' WHERE id = :id",
         "params": [{"name": "id", "type": "integer", "minimum": 1}]}
        """), rw()).tools().getFirst();
    assertThat(tool.definition().defaultMode()).isEqualTo(ToolDefinition.Mode.APPROVAL);
    ToolCall call = new ToolCall("tc_cancel", "cancel_order", "dev-db", Fixtures.object().put("id", 90004),
        new ToolCall.TemplateBinding("tpl_3", 1, "q", tool.binding()));
    assertThat(CONNECTOR.classify(call, rw(), Fixtures.policy(Env.DEV, null)).kind())
        .isEqualTo(Classification.Kind.MUTATION);

    PreparedAction p = CONNECTOR.prepare(call, rw(), secrets("rw"), ctx());
    assertThat(p.preview().fields()).contains(new ActionPreview.Field("Parameters", ":id = 90004 (integer)"));
    assertThat(p.preview().impact()).contains(new ActionPreview.Field("Estimated rows", "1 (from EXPLAIN)"));
    assertThatThrownBy(() -> CONNECTOR.execute(call, rw(), secrets("rw"), ctx()))
        .hasMessageContaining("Not approved");

    ToolResult r = CONNECTOR.execute(call, rw(), secrets("rw"), ctx().approved(p.preview().payload()));
    assertThat(r.content().getFirst().text()).isEqualTo("1 row updated");
    assertThat(count("SELECT count(*) FROM orders WHERE id = 90004 AND status = 'cancelled'")).isEqualTo(1);
  }

  @Test
  void aChangingTemplateRunsOnlyWithTheValuesAPersonSaw() throws Exception {
    PG.exec("INSERT INTO orders (id, customer_id, status) VALUES (90005, 1, 'pending') ON CONFLICT DO NOTHING");
    TemplateKind.CompiledTool tool = CONNECTOR.templateKinds().getFirst().compile(Fixtures.json("""
        {"name": "cancel_before", "sql": "UPDATE orders SET status = :status WHERE id = :id AND created_at < :before",
         "params": [{"name": "status", "type": "enum", "values": ["cancelled"]},
                    {"name": "id", "type": "integer"},
                    {"name": "before", "type": "timestamp", "required": false}]}
        """), rw()).tools().getFirst();
    ToolCall.TemplateBinding binding = new ToolCall.TemplateBinding("tpl_4", 1, "q", tool.binding());

    // A time the input schema lets through (its format isn't asserted) but that isn't one: no request at all.
    ToolCall bad = new ToolCall("tc_bad", "cancel_before", "dev-db", Fixtures.object().put("status", "cancelled")
        .put("id", 90005).put("before", "yesterday"), binding);
    PreparedAction refused = CONNECTOR.prepare(bad, rw(), secrets("rw"), ctx());
    assertThat(refused.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(refused.message()).contains("before isn't a valid timestamp");

    // An approved payload without one of the values doesn't run with a NULL in its place.
    ToolCall good = new ToolCall("tc_good", "cancel_before", "dev-db", Fixtures.object().put("status", "cancelled")
        .put("id", 90005), binding);
    PreparedAction p = CONNECTOR.prepare(good, rw(), secrets("rw"), ctx());
    assertThat(p.preview().payload().path("values").has("before")).isTrue();
    ObjectNode tampered = (ObjectNode) p.preview().payload().deepCopy();
    ((ObjectNode) tampered.get("values")).remove("status");
    assertThatThrownBy(() -> CONNECTOR.execute(good, rw(), secrets("rw"), ctx().approved(tampered)))
        .hasMessageContaining("no value for :status");
    ((ObjectNode) tampered.get("values")).putNull("status");
    assertThatThrownBy(() -> CONNECTOR.execute(good, rw(), secrets("rw"), ctx().approved(tampered)))
        .hasMessageContaining("no value for :status");
    assertThat(count("SELECT count(*) FROM orders WHERE id = 90005 AND status = 'pending'")).isEqualTo(1);

    // The optional time is NULL as approved, so the comparison matches nothing.
    ToolResult r = CONNECTOR.execute(good, rw(), secrets("rw"), ctx().approved(p.preview().payload()));
    assertThat(r.content().getFirst().text()).isEqualTo("0 rows updated");
  }

  // ── Introspection ───────────────────────────────────────────────────────────────────────────────────────

  @Test
  void listSchemasShowsWhatTheUserAndThePolicyAllow() throws Exception {
    ToolCall call = Fixtures.call("sql_list_schemas", Fixtures.object());
    ConnectionConfig ro = cfg("ro", Env.DEV, null);
    CONNECTOR.classify(call, ro, Fixtures.policy(Env.DEV, null));

    ToolResult all = CONNECTOR.execute(call, ro, secrets("ro"), ctx());

    assertThat(all.structuredContent().path("schemas").findValuesAsString("name"))
        .containsExactly("public", "reporting");
    assertThat(all.structuredContent().path("tables").findValuesAsString("name"))
        .contains("orders", "customers", "daily_totals").doesNotContain("keys");
    assertThat(all.content().getFirst().text()).contains("public (").contains("orders");
    assertThat(validate(CONNECTOR.manifest().tools().getFirst().outputSchema(), all.structuredContent())).isEmpty();

    ObjectNode policy = Fixtures.object();
    policy.putArray("allowlist").add("public.orders");
    ToolCall narrowed = Fixtures.call("sql_list_schemas", Fixtures.object());
    CONNECTOR.classify(narrowed, ro, Fixtures.policy(Env.DEV, policy));
    ToolResult one = CONNECTOR.execute(narrowed, ro, secrets("ro"), ctx());
    assertThat(one.structuredContent().path("tables").findValuesAsString("name")).containsExactly("orders");
    assertThat(one.structuredContent().path("schemas").findValuesAsString("name")).containsExactly("public");

    ToolCall unclassified = Fixtures.call("sql_list_schemas", Fixtures.object());
    assertThatThrownBy(() -> CONNECTOR.execute(unclassified, ro, secrets("ro"), ctx()))
        .hasMessageContaining("wasn't classified");
  }

  @Test
  void describeTableShowsColumnsKeysAndIndexes() throws Exception {
    ToolCall call = Fixtures.call("sql_describe_table", Fixtures.object().put("table", "orders"));
    ToolResult r = CONNECTOR.execute(call, cfg("ro", Env.DEV, null), secrets("ro"), ctx());
    JsonNode d = r.structuredContent();

    assertThat(d.path("schema").asString()).isEqualTo("public");
    assertThat(d.path("kind").asString()).isEqualTo("table");
    assertThat(d.path("columns").findValuesAsString("name")).startsWith("id", "customer_id", "status");
    assertThat(d.path("primaryKey").toString()).isEqualTo("[\"id\"]");
    assertThat(d.path("foreignKeys").get(0).path("refTable").asString()).isEqualTo("customers");
    assertThat(d.path("referencedBy").findValuesAsString("table")).contains("order_items");
    assertThat(d.path("indexes").findValuesAsString("name")).contains("orders_status_idx");
    assertThat(r.content().getFirst().text()).startsWith("public.orders (table").contains("primary key (id)");
    assertThat(validate(CONNECTOR.manifest().tools().get(1).outputSchema(), d)).isEmpty();

    assertThatThrownBy(() -> CONNECTOR.execute(Fixtures.call("sql_describe_table",
        Fixtures.object().put("table", "secret_stuff.keys")), cfg("ro", Env.DEV, null), secrets("ro"), ctx()))
        .hasMessage("Table secret_stuff.keys not found on dev-db. Call sql_list_schemas to see the tables.");
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────

  static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

  static List<Error> validate(JsonNode schema, JsonNode instance) {
    return SCHEMAS.getSchema(schema).validate(instance);
  }

  private static JsonNode compiledOutput(ToolDefinition d) {
    return d.outputSchema();
  }

  /** What the core makes of a table result for the agent (ResultLimiter): the sql_query output. */
  static JsonNode tableAsOutput(ToolResult r) {
    ObjectNode s = Fixtures.object();
    var columns = s.putArray("columns");
    var types = s.putArray("types");
    r.table().columns().forEach(c -> {
      columns.add(c.name());
      types.add(c.type());
    });
    s.set("rows", r.table().rows());
    s.put("rowCount", r.table().rows().size());
    s.put("truncated", r.truncated());
    s.putArray("masked");
    return s;
  }
}
