package gaterook.plugins.duckdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** The DuckDB plugin's connector against real database files (docs/duckdb.md): no server, so it runs as a unit test. */
class DuckDbConnectorTest {

  @TempDir
  Path temp;

  private Path workDir;
  private Path database;
  private Path data;
  private final DuckDbConnector duckdb = new DuckDbConnector(TestCalls.manifest());

  @BeforeEach
  void setUp() throws Exception {
    workDir = TestCalls.workDir(temp.resolve("gaterook-home"));
    database = TestCalls.database(temp.resolve("project").resolve("shop.duckdb"),
        "CREATE TABLE customers (id INTEGER PRIMARY KEY, email VARCHAR NOT NULL UNIQUE, name VARCHAR DEFAULT "
            + "'anonymous')",
        "CREATE TABLE orders (id INTEGER PRIMARY KEY, customer_id INTEGER REFERENCES customers (id), "
            + "total DECIMAL(10, 2), placed TIMESTAMP, tags VARCHAR[], meta STRUCT(channel VARCHAR, n INTEGER), "
            + "raw BLOB, big HUGEINT, ratio DOUBLE, CHECK (total >= 0))",
        "CREATE INDEX orders_by_customer ON orders (customer_id)",
        "CREATE VIEW big_orders AS SELECT * FROM orders WHERE total > 100",
        "COMMENT ON TABLE orders IS 'Orders of the shop'",
        "CREATE SCHEMA staging",
        "CREATE TABLE staging.orders (id INTEGER)",
        "INSERT INTO customers VALUES (1, 'ada@example.test', 'Ada'), (2, 'lin@example.test', NULL)",
        "INSERT INTO orders VALUES (10, 1, 250.50, TIMESTAMP '2026-11-01 10:00:00', ['new', 'gift'], "
            + "{'channel': 'web', 'n': 2}, '\\xAA\\xBB'::BLOB, 170141183460469231731687303715884105727, 0.25), "
            + "(11, 2, 12.00, NULL, [], NULL, NULL, 1, 'NaN'::DOUBLE)");
    data = Files.createDirectories(temp.resolve("data"));
    Files.writeString(data.resolve("sales.csv"), "id,amount\n1,5\n2,7\n");
  }

  private ConnectionConfig config(Env env) {
    return TestCalls.config(env, database.toString(), List.of());
  }

  private ConnectionConfig withData() {
    return TestCalls.config(Env.DEV, database.toString(), List.of(data.toString()));
  }

  private TestCalls.Context read() {
    return new TestCalls.Context(workDir, false);
  }

  private TestCalls.Context approved(String sql) {
    return new TestCalls.Context(workDir, true, TestCalls.sql("duckdb_execute", sql).arguments(), Env.DEV,
        TestCalls.DEV, Duration.ofSeconds(10));
  }

  private ToolResult query(String sql) throws ConnectorException {
    return duckdb.execute(TestCalls.sql("duckdb_query", sql), config(Env.DEV), new TestCalls.NoSecrets(), read());
  }

  private ToolResult change(String sql) throws ConnectorException {
    return duckdb.execute(TestCalls.sql("duckdb_execute", sql), config(Env.DEV), new TestCalls.NoSecrets(),
        approved(sql));
  }

  private static JsonNode rows(ToolResult r) {
    assertThat(r.isError()).as(String.valueOf(r.content())).isFalse();
    return r.table().rows();
  }

  // ── Reads ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void listsTheTablesAndViewsBySchema() throws Exception {
    ToolResult all = duckdb.execute(TestCalls.call("duckdb_list_tables", "{}"), config(Env.DEV),
        new TestCalls.NoSecrets(), read());
    assertThat(all.table().columns()).extracting(ToolResult.Column::name).containsExactly("schema", "name", "kind",
        "estimated_rows", "columns", "comment");
    assertThat(rows(all).toString()).contains("\"main\",\"big_orders\",\"view\"", "\"main\",\"customers\",\"table\"",
        "\"staging\",\"orders\",\"table\"", "Orders of the shop");
    assertThat(rows(all)).hasSize(4);
    ToolResult staging = duckdb.execute(TestCalls.call("duckdb_list_tables", "{\"schema\":\"STAGING\"}"),
        config(Env.DEV), new TestCalls.NoSecrets(), read());
    assertThat(rows(staging)).hasSize(1);
    ToolResult like = duckdb.execute(TestCalls.call("duckdb_list_tables", "{\"like\":\"%ORDER%\",\"limit\":2}"),
        config(Env.DEV), new TestCalls.NoSecrets(), read());
    assertThat(rows(like)).hasSize(2);
    assertThat(like.truncatedReason()).isEqualTo("row_limit");
  }

  @Test
  void describesATableWithItsKeysConstraintsAndIndexes() throws Exception {
    ToolResult r = duckdb.execute(TestCalls.call("duckdb_describe_table", "{\"table\":\"ORDERS\",\"schema\":\"main\"}"),
        config(Env.DEV), new TestCalls.NoSecrets(), read());
    JsonNode t = r.structuredContent();
    assertThat(t.path("schema").asString()).isEqualTo("main");
    assertThat(t.path("name").asString()).isEqualTo("orders");
    assertThat(t.path("kind").asString()).isEqualTo("table");
    assertThat(t.path("comment").asString()).isEqualTo("Orders of the shop");
    assertThat(t.path("primaryKey").toString()).isEqualTo("[\"id\"]");
    assertThat(t.path("columns").get(0).path("nullable").asBoolean()).isFalse();
    assertThat(t.path("columns").get(2).path("type").asString()).isEqualTo("DECIMAL(10,2)");
    assertThat(t.path("constraints").toString()).contains("FOREIGN KEY", "CHECK");
    assertThat(t.path("indexes").get(0).path("name").asString()).isEqualTo("orders_by_customer");
    assertThat(t.path("sql").asString()).startsWith("CREATE TABLE orders");
    assertThat(r.content().getFirst().text()).startsWith("main.orders · table").contains("primary key (id)");

    ToolResult ambiguous = duckdb.execute(TestCalls.call("duckdb_describe_table", "{\"table\":\"orders\"}"),
        config(Env.DEV), new TestCalls.NoSecrets(), read());
    assertThat(ambiguous.isError()).isTrue();
    assertThat(ambiguous.content().getFirst().text()).contains("several schemas").contains("main, staging");
    ToolResult view = duckdb.execute(TestCalls.call("duckdb_describe_table", "{\"table\":\"big_orders\"}"),
        config(Env.DEV), new TestCalls.NoSecrets(), read());
    assertThat(view.structuredContent().path("kind").asString()).isEqualTo("view");
    ToolResult missing = duckdb.execute(TestCalls.call("duckdb_describe_table", "{\"table\":\"nope\"}"),
        config(Env.DEV), new TestCalls.NoSecrets(), read());
    assertThat(missing.isError()).isTrue();
  }

  @Test
  void aQueryReturnsTypedRows() throws Exception {
    ToolResult r = query("SELECT id, total, placed, tags, meta, raw, big, ratio, NULL AS nothing FROM orders "
        + "ORDER BY id");
    JsonNode first = rows(r).get(0);
    assertThat(first.get(0).asInt()).isEqualTo(10);
    assertThat(first.get(1).asString()).isEqualTo("250.50");
    assertThat(first.get(2).asString()).isEqualTo("2026-11-01T10:00:00");
    assertThat(first.get(3).toString()).isEqualTo("[\"new\",\"gift\"]");
    assertThat(first.get(4).path("channel").asString()).isEqualTo("web");
    assertThat(first.get(4).path("n").asInt()).isEqualTo(2);
    assertThat(first.get(5).asString()).isEqualTo("\\xaabb");
    assertThat(first.get(6).asString()).isEqualTo("170141183460469231731687303715884105727");
    assertThat(first.get(7).asDouble()).isEqualTo(0.25);
    assertThat(first.get(8).isNull()).isTrue();
    JsonNode second = rows(r).get(1);
    assertThat(second.get(3).toString()).isEqualTo("[]");
    assertThat(second.get(6).asInt()).isEqualTo(1);
    assertThat(second.get(7).asString()).isEqualTo("NaN");
    assertThat(r.table().columns()).extracting(ToolResult.Column::type).contains("DECIMAL(10,2)", "VARCHAR[]");

    JsonNode times = rows(query("SELECT DATE '2026-11-01', TIMESTAMPTZ '2026-11-01 10:00:00+00', "
        + "TIMESTAMP '2026-11-01 10:00:00.123456'")).get(0);
    assertThat(times.get(0).asString()).isEqualTo("2026-11-01");
    assertThat(java.time.OffsetDateTime.parse(times.get(1).asString()).toInstant())
        .isEqualTo(java.time.Instant.parse("2026-11-01T10:00:00Z"));
    assertThat(times.get(2).asString()).isEqualTo("2026-11-01T10:00:00.123456");
  }

  @Test
  void rowsBytesAndCellsAreBounded() throws Exception {
    ToolResult limited = duckdb.execute(TestCalls.call("duckdb_query",
        "{\"sql\":\"SELECT * FROM range(100000)\",\"limit\":3}"), config(Env.DEV), new TestCalls.NoSecrets(), read());
    assertThat(rows(limited)).hasSize(3);
    assertThat(limited.truncated()).isTrue();
    assertThat(limited.truncatedReason()).isEqualTo("row_limit");

    TestCalls.Context prod = new TestCalls.Context(workDir, false, null, Env.PROD,
        TestCalls.limits(2, Duration.ofSeconds(3)), Duration.ofSeconds(10));
    ToolResult env = duckdb.execute(TestCalls.sql("duckdb_query", "SELECT * FROM range(10)"), config(Env.PROD),
        new TestCalls.NoSecrets(), prod);
    assertThat(rows(env)).hasSize(2);

    TestCalls.Context small = new TestCalls.Context(workDir, false, null, Env.DEV, TestCalls.small(2_000, 50),
        Duration.ofSeconds(10));
    ToolResult sized = duckdb.execute(TestCalls.sql("duckdb_query", "SELECT repeat('x', 40) FROM range(1000)"),
        config(Env.DEV), new TestCalls.NoSecrets(), small);
    assertThat(sized.truncatedReason()).isEqualTo("size_limit");
    assertThat(rows(sized).size()).isLessThan(100);
    ToolResult cut = duckdb.execute(TestCalls.sql("duckdb_query", "SELECT repeat('y', 500) AS long"),
        config(Env.DEV), new TestCalls.NoSecrets(), small);
    assertThat(rows(cut).get(0).get(0).asString()).startsWith("y".repeat(50) + "…‹truncated 450 chars›");
  }

  @Test
  void aStatementThatIsntAReadIsRefusedBeforeDuckDbSeesIt() throws Exception {
    for (String sql : List.of("DROP TABLE orders", "DELETE FROM orders", "COPY orders TO '/tmp/x.csv'",
        "ATTACH '/tmp/x.duckdb'", "INSTALL httpfs", "SET threads = 64", "SELECT 1; DROP TABLE orders",
        "SELECT * FROM read_csv('" + data.resolve("sales.csv") + "')")) {
      assertThatThrownBy(() -> query(sql)).as(sql).isInstanceOfSatisfying(ConnectorException.class,
          e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    }
    assertThat(rows(query("SELECT count(*) FROM orders")).get(0).get(0).asInt()).isEqualTo(2);
  }

  @Test
  void duckDbsOwnRefusalIsAResultTheAgentReads() throws Exception {
    ToolResult unknown = query("SELECT * FROM nope");
    assertThat(unknown.isError()).isTrue();
    assertThat(unknown.content().getFirst().text()).startsWith("DuckDB refused the query: Catalog Error");
    ToolResult syntax = query("SELECT FROM WHERE");
    assertThat(syntax.isError()).isTrue();
  }

  @Test
  void dataFoldersCanBeRead() throws Exception {
    ToolResult r = duckdb.execute(TestCalls.sql("duckdb_query", "SELECT sum(amount) AS total FROM read_csv('"
        + data.resolve("sales.csv") + "')"), withData(), new TestCalls.NoSecrets(), read());
    assertThat(rows(r).get(0).get(0).asInt()).isEqualTo(12);
    // The folder as DuckDB resolves it works too (/var is /private/var on macOS).
    ToolResult real = duckdb.execute(TestCalls.sql("duckdb_query", "SELECT count(*) FROM '"
        + data.toRealPath().resolve("sales.csv") + "'"), withData(), new TestCalls.NoSecrets(), read());
    assertThat(rows(real).get(0).get(0).asInt()).isEqualTo(2);
    assertThatThrownBy(() -> duckdb.execute(TestCalls.sql("duckdb_query", "SELECT * FROM read_text('"
        + database + "')"), withData(), new TestCalls.NoSecrets(), read())).isInstanceOf(ConnectorException.class)
        .hasMessageContaining("isn't in a data folder");
  }

  @Test
  void aRunawayQueryStopsAtItsDeadline() throws Exception {
    TestCalls.Context ctx = new TestCalls.Context(workDir, false, null, Env.DEV,
        TestCalls.limits(1000, Duration.ofSeconds(1)), Duration.ofSeconds(10));
    long started = System.nanoTime();
    assertThatThrownBy(() -> duckdb.execute(TestCalls.sql("duckdb_query", "WITH RECURSIVE r(n) AS (SELECT 1 UNION "
        + "ALL SELECT n + 1 FROM r) SELECT count(*) FROM r"), config(Env.DEV), new TestCalls.NoSecrets(), ctx))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT);
          assertThat(e.getMessage()).contains("1 s");
        });
    assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(4_000L);
  }

  // ── Changes ─────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aChangeRunsOnlyWhenApprovedAndExactlyAsApproved() throws Exception {
    String insert = "INSERT INTO customers VALUES (3, 'kim@example.test', 'Kim')";
    assertThatThrownBy(() -> duckdb.execute(TestCalls.sql("duckdb_execute", insert), config(Env.DEV),
        new TestCalls.NoSecrets(), read())).isInstanceOfSatisfying(ConnectorException.class,
        e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    assertThat(rows(query("SELECT count(*) FROM customers")).get(0).get(0).asInt()).isEqualTo(2);

    ToolResult done = change(insert);
    assertThat(done.isError()).isFalse();
    assertThat(done.structuredContent().path("statement").asString()).isEqualTo("INSERT");
    assertThat(done.structuredContent().path("target").asString()).isEqualTo("customers");
    assertThat(done.structuredContent().path("rowsChanged").asLong()).isEqualTo(1);
    assertThat(done.content().getFirst().text()).isEqualTo("INSERT customers: 1 row changed.");
    assertThat(rows(query("SELECT name FROM customers WHERE id = 3")).get(0).get(0).asString()).isEqualTo("Kim");

    // Another statement than the approved one: nothing runs.
    ToolCall other = TestCalls.sql("duckdb_execute", "DELETE FROM customers");
    assertThatThrownBy(() -> duckdb.execute(other, config(Env.DEV), new TestCalls.NoSecrets(), approved(insert)))
        .hasMessageContaining("approved statement");
    assertThat(rows(query("SELECT count(*) FROM customers")).get(0).get(0).asInt()).isEqualTo(3);
  }

  @Test
  void changesReturnTheirRowsAndDdlWorks() throws Exception {
    ToolResult returning = change("UPDATE orders SET total = total + 1 RETURNING id, total");
    assertThat(returning.structuredContent().path("rowsChanged").asLong()).isEqualTo(2);
    assertThat(returning.structuredContent().path("returned")).hasSize(2);
    ToolResult created = change("CREATE TABLE daily AS SELECT customer_id, sum(total) AS total FROM orders GROUP BY "
        + "ALL");
    assertThat(created.isError()).isFalse();
    assertThat(created.structuredContent().path("statement").asString()).isEqualTo("CREATE TABLE");
    assertThat(rows(query("SELECT count(*) FROM daily")).get(0).get(0).asInt()).isEqualTo(2);
    assertThat(change("DROP TABLE daily").isError()).isFalse();
    assertThat(query("SELECT * FROM daily").isError()).isTrue();

    ToolResult imported = duckdb.execute(TestCalls.sql("duckdb_execute", "CREATE TABLE sales AS SELECT * FROM "
        + "read_csv('" + data.resolve("sales.csv") + "')"), withData(), new TestCalls.NoSecrets(),
        approved("CREATE TABLE sales AS SELECT * FROM read_csv('" + data.resolve("sales.csv") + "')"));
    assertThat(imported.isError()).as(String.valueOf(imported.content())).isFalse();
    assertThat(rows(query("SELECT sum(amount) FROM sales")).get(0).get(0).asInt()).isEqualTo(12);
  }

  @Test
  void aChangeDuckDbRefusesChangesNothing() throws Exception {
    ToolResult duplicate = change("INSERT INTO customers VALUES (1, 'again@example.test', 'Again')");
    assertThat(duplicate.isError()).isTrue();
    assertThat(duplicate.content().getFirst().text()).startsWith("DuckDB refused the change, so nothing changed")
        .contains("Constraint Error");
    assertThat(rows(query("SELECT count(*) FROM customers")).get(0).get(0).asInt()).isEqualTo(2);
    for (String refused : List.of("MERGE INTO customers USING (SELECT 1 AS id) s ON s.id = customers.id WHEN "
        + "MATCHED THEN DELETE", "SELECT 1", "COPY customers TO '/tmp/x.csv'", "CREATE TEMP TABLE x (a INT)",
        "CREATE SECRET s (TYPE s3)", "DELETE FROM customers; DROP TABLE orders")) {
      assertThatThrownBy(() -> change(refused)).as(refused).isInstanceOfSatisfying(ConnectorException.class,
          e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    }
  }

  @Test
  void aRunawayChangeStopsAtItsDeadlineAndRollsBack() throws Exception {
    String sql = "CREATE TABLE endless AS WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r) "
        + "SELECT n FROM r";
    TestCalls.Context ctx = new TestCalls.Context(workDir, true, TestCalls.sql("duckdb_execute", sql).arguments(),
        Env.DEV, TestCalls.limits(1000, Duration.ofSeconds(1)), Duration.ofSeconds(10));
    assertThatThrownBy(() -> duckdb.execute(TestCalls.sql("duckdb_execute", sql), config(Env.DEV),
        new TestCalls.NoSecrets(), ctx)).isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT);
          assertThat(e.getMessage()).contains("nothing changed");
        });
    assertThat(query("SELECT * FROM endless").isError()).isTrue();
  }

  @Test
  void aChangeKeepsTheStorageVersionOfAnOlderFile() throws Exception {
    Path old = temp.resolve("old").resolve("v1.duckdb");
    Files.createDirectories(old.getParent());
    java.util.Properties p = new java.util.Properties();
    p.setProperty("jdbc_instance_cache", "false");
    p.setProperty("storage_compatibility_version", "v1.0.0");
    try (var c = new org.duckdb.DuckDBDriver().connect("jdbc:duckdb:" + old, p); var st = c.createStatement()) {
      st.execute("CREATE TABLE t (id INTEGER)");
    }
    byte[] before = java.util.Arrays.copyOf(Files.readAllBytes(old), 20);
    ConnectionConfig cfg = TestCalls.config(Env.DEV, old.toString(), List.of());
    String sql = "INSERT INTO t SELECT * FROM range(1000)";
    ToolResult done = duckdb.execute(TestCalls.sql("duckdb_execute", sql), cfg, new TestCalls.NoSecrets(),
        approved(sql));
    assertThat(done.structuredContent().path("rowsChanged").asLong()).isEqualTo(1000);
    // The main header's version (bytes 12 to 19) is the one the file had: an older DuckDB still opens it.
    assertThat(java.util.Arrays.copyOfRange(Files.readAllBytes(old), 12, 20))
        .isEqualTo(java.util.Arrays.copyOfRange(before, 12, 20));
    TestResult t = duckdb.test(cfg, new TestCalls.NoSecrets(), read());
    assertThat(t.details().path("storageVersion").asString()).isEqualTo("v1.0.0+");
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void aFileThisAccountCantWriteRefusesChangesAndSaysSoAtTest() throws Exception {
    Files.setPosixFilePermissions(database, PosixFilePermissions.fromString("r--r--r--"));
    try {
      TestResult t = duckdb.test(config(Env.DEV), new TestCalls.NoSecrets(), read());
      assertThat(t.ok()).isTrue();
      assertThat(t.permissions().level()).isEqualTo(TestResult.Level.READ);
      assertThat(t.hints()).extracting(TestResult.Hint::code).contains("duckdb.file-read-only");
      assertThat(rows(query("SELECT count(*) FROM orders")).get(0).get(0).asInt()).isEqualTo(2);
      assertThatThrownBy(() -> change("DELETE FROM orders")).isInstanceOf(ConnectorException.class)
          .hasMessageContaining("Nothing changed");
    } finally {
      Files.setPosixFilePermissions(database, PosixFilePermissions.fromString("rw-r--r--"));
    }
  }

  // ── Classification and the exact action ─────────────────────────────────────────────────────────────────────

  @Test
  void classificationReadsTheStatement() {
    assertThat(duckdb.classify(TestCalls.sql("duckdb_query", "SELECT 1"), config(Env.DEV),
        TestCalls.policy(Env.DEV)).kind()).isEqualTo(Classification.Kind.READ);
    Classification drop = duckdb.classify(TestCalls.sql("duckdb_query", "DROP TABLE orders"), config(Env.DEV),
        TestCalls.policy(Env.DEV));
    assertThat(drop.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(drop.ruleId()).isEqualTo("dev.duckdb.read-only");
    Classification insert = duckdb.classify(TestCalls.sql("duckdb_execute", "INSERT INTO orders (id) VALUES (1)"),
        config(Env.DEV), TestCalls.policy(Env.DEV));
    assertThat(insert.kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(insert.facts().path("statement").asString()).isEqualTo("INSERT");
    assertThat(duckdb.classify(TestCalls.sql("duckdb_execute", "ATTACH 'x'"), config(Env.DEV),
        TestCalls.policy(Env.DEV)).kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(duckdb.classify(TestCalls.call("duckdb_list_tables", "{}"), config(Env.DEV),
        TestCalls.policy(Env.DEV)).kind()).isEqualTo(Classification.Kind.READ);
    assertThat(duckdb.classify(TestCalls.call("duckdb_nope", "{}"), config(Env.DEV),
        TestCalls.policy(Env.DEV)).kind()).isEqualTo(Classification.Kind.DENY);
  }

  @Test
  void theExactActionIsTheStatementAsWritten() {
    String sql = "DELETE FROM orders WHERE id = 11 -- old";
    ActionPreview p = duckdb.describeAction(TestCalls.sql("duckdb_execute", sql), config(Env.DEV));
    assertThat(p.kind()).isEqualTo(ActionPreview.Kind.SQL);
    assertThat(p.text()).isEqualTo(sql);
    assertThat(p.target()).isEqualTo("warehouse · shop.duckdb").doesNotContain(temp.toString());
    assertThat(p.summary()).isEqualTo("DELETE orders on warehouse");
    assertThat(p.payload().path("sql").asString()).isEqualTo(sql);
    assertThat(p.impact()).extracting(ActionPreview.Field::value).contains("DELETE", "orders");
    assertThat(p.auditFacets()).containsEntry("statement", "DELETE").containsEntry("tables", "orders");
    assertThat(p.warnings()).isEmpty();
    assertThat(duckdb.describeAction(TestCalls.sql("duckdb_execute", "DROP TABLE orders"), config(Env.DEV))
        .warnings()).anyMatch(w -> w.contains("can't be undone"));
    assertThat(duckdb.describeAction(TestCalls.sql("duckdb_execute", "COPY orders TO 'x'"), config(Env.DEV))
        .warnings()).anyMatch(w -> w.startsWith("GateRook will refuse this statement"));

    ObjectNode args = TestCalls.sql("duckdb_query", "SELECT * FROM orders").arguments();
    args.put("limit", 5);
    ActionPreview q = duckdb.describeAction(new ToolCall("tc", "duckdb_query", "warehouse", args, null),
        config(Env.DEV));
    assertThat(q.text()).isEqualTo("SELECT * FROM orders");
    assertThat(q.payload().path("limit").asInt()).isEqualTo(5);
    assertThat(q.fields()).extracting(ActionPreview.Field::value).anyMatch(v -> v.contains("at most 5 rows"));
  }

  // ── Test connection ─────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void testConnectionReportsTheFileAndWhatGateRookMayDoWithIt() throws Exception {
    TestResult t = duckdb.test(config(Env.DEV), new TestCalls.NoSecrets(), read());
    assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
    assertThat(t.serverVersion()).startsWith("DuckDB v");
    assertThat(t.details().path("tables").asInt()).isEqualTo(3);
    assertThat(t.details().path("views").asInt()).isEqualTo(1);
    assertThat(t.details().path("file").path("name").asString()).isEqualTo("shop.duckdb");
    assertThat(t.details().path("file").path("sizeBytes").asLong()).isPositive();
    assertThat(t.details().path("writable").asBoolean()).isTrue();
    assertThat(t.details().path("storageVersion").asString()).startsWith("v");
    assertThat(t.details().toString()).doesNotContain(temp.toString());
    assertThat(t.checks()).extracting(TestResult.Check::name).containsExactly("file", "open", "permissions",
        "data folders");
    assertThat(t.permissions().level()).isEqualTo(TestResult.Level.WRITE);
    assertThat(t.hints()).extracting(TestResult.Hint::code, TestResult.Hint::severity)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("duckdb.file-writable", TestResult.Severity.INFO));

    TestResult prod = duckdb.test(config(Env.PROD), new TestCalls.NoSecrets(), read());
    assertThat(prod.hints()).extracting(TestResult.Hint::severity).contains(TestResult.Severity.WARNING);
    TestResult folders = duckdb.test(withData(), new TestCalls.NoSecrets(), read());
    assertThat(folders.hints()).extracting(TestResult.Hint::code).contains("duckdb.data-folders");
  }

  @Test
  void testConnectionRefusesWhatIsntTheConnectionsDatabase() throws Exception {
    assertThat(duckdb.test(TestCalls.config(Env.DEV, temp.resolve("nope.duckdb").toString(), List.of()),
        new TestCalls.NoSecrets(), read()).error().code()).isEqualTo("duckdb.file");
    Path text = Files.writeString(temp.resolve("notes.duckdb"), "not a database");
    TestResult notDuck = duckdb.test(TestCalls.config(Env.DEV, text.toString(), List.of()), new TestCalls.NoSecrets(),
        read());
    assertThat(notDuck.error().message()).contains("isn't a DuckDB database");
    Path inside = TestCalls.database(workDir.resolve("mine.duckdb"), "CREATE TABLE t (a INT)");
    assertThat(duckdb.test(TestCalls.config(Env.DEV, inside.toString(), List.of()), new TestCalls.NoSecrets(),
        read()).error().message()).contains("GateRook's own files");
    TestResult folder = duckdb.test(TestCalls.config(Env.DEV, database.toString(),
        List.of(temp.resolve("missing").toString())), new TestCalls.NoSecrets(), read());
    assertThat(folder.error().code()).isEqualTo("duckdb.folder");
    Path empty = TestCalls.database(temp.resolve("empty").resolve("new.duckdb"));
    TestResult nothing = duckdb.test(TestCalls.config(Env.DEV, empty.toString(), List.of()),
        new TestCalls.NoSecrets(), read());
    assertThat(nothing.hints()).extracting(TestResult.Hint::code).contains("duckdb.empty-database");
  }
}
