package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** The SQLite connector against real files (docs/sqlite.md): no server, so it runs with the unit tests. */
class SqliteConnectorTest {

  @TempDir
  Path temp;

  private Path home;
  private Path workDir;
  private Path database;
  private JdbcSqlConnector sqlite;

  @BeforeEach
  void setUp() throws Exception {
    home = Files.createDirectories(temp.resolve("gaterook-home"));
    workDir = Files.createDirectories(home.resolve("connectors").resolve("sqlite"));
    database = Files.createDirectories(temp.resolve("project")).resolve("shop.sqlite3");
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + database);
         Statement st = c.createStatement()) {
      st.executeUpdate("CREATE TABLE customers (id INTEGER PRIMARY KEY, email TEXT NOT NULL UNIQUE, "
          + "name TEXT DEFAULT 'anonymous')");
      st.executeUpdate("CREATE TABLE \"Orders\" (id INTEGER PRIMARY KEY, customer_id INTEGER NOT NULL "
          + "REFERENCES customers(id) ON DELETE CASCADE, total REAL, note BLOB, created TEXT, "
          + "CHECK (total >= 0))");
      st.executeUpdate("CREATE INDEX orders_by_customer ON \"Orders\"(customer_id, created DESC)");
      st.executeUpdate("CREATE VIEW big_orders AS SELECT * FROM \"Orders\" WHERE total > 100");
      st.executeUpdate("INSERT INTO customers(id, email, name) VALUES (1, 'ada@example.test', 'Ada'), "
          + "(2, 'lin@example.test', NULL)");
      st.executeUpdate("INSERT INTO \"Orders\"(id, customer_id, total, note, created) VALUES "
          + "(10, 1, 250.5, x'00ff10', '2026-11-01 10:00:00'), (11, 2, 12, NULL, '2026-11-02 08:30:00')");
    }
    sqlite = new JdbcSqlConnector(new SqliteDialect());
  }

  @AfterEach
  void tearDown() {
    sqlite.close();
  }

  private ConnectionConfig config(Env env, String file) {
    return config(env, file, null);
  }

  private ConnectionConfig config(Env env, String file, ObjectNode advanced) {
    ObjectNode c = Fixtures.object();
    c.putObject("connection").put("file", file);
    if (advanced != null) {
      c.set("advanced", advanced);
    }
    return new ConnectionConfig("conn-sqlite-" + env.id(), env.id() + "-db", "sqlite", env, 1, c, Set.of(), null);
  }

  private ConnectionConfig config(Env env) {
    return config(env, database.toString());
  }

  private Fixtures.Context context(Env env) {
    return Fixtures.Context.of(env, workDir);
  }

  private ToolResult query(String sql) throws ConnectorException {
    ConnectionConfig cfg = config(Env.DEV);
    var call = Fixtures.sql("sql_query", sql);
    Classification c = sqlite.classify(call, cfg, Fixtures.policy(Env.DEV, null));
    assertThat(c.kind()).as(c.reason()).isEqualTo(Classification.Kind.READ);
    return sqlite.execute(call, cfg, Fixtures.Secrets.none(), context(Env.DEV));
  }

  private Classification classify(String tool, String sql) {
    return sqlite.classify(Fixtures.sql(tool, sql), config(Env.DEV), Fixtures.policy(Env.DEV, null));
  }

  // ── The connector ───────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void sharesTheSqlToolsAndDescribesItsFile() {
    assertThat(sqlite.manifest().id()).isEqualTo("sqlite");
    assertThat(sqlite.manifest().name()).isEqualTo("SQLite");
    assertThat(sqlite.manifest().group()).isEqualTo("Databases");
    assertThat(sqlite.manifest().tools()).isEqualTo(new JdbcSqlConnector(new MySqlDialect()).manifest().tools());
    assertThat(sqlite.templateKinds().getFirst().connectionTypes()).containsExactly("sqlite");
    JsonNode d = sqlite.describe(config(Env.DEV), null, null, null).orElseThrow();
    assertThat(d.path("file").asString()).isEqualTo("shop.sqlite3");
    assertThat(d.toString()).doesNotContain(temp.toString());
    assertThat(d.path("reads").asString()).contains("read-only handle");
    assertThat(d.path("changes").asString()).contains("One statement");
    assertThat(sqlite.canRotate(config(Env.DEV), ConnectionSettings.PASSWORD)).isFalse();
  }

  @Test
  void listsAndDescribesTablesOfMain() throws Exception {
    ConnectionConfig cfg = config(Env.DEV);
    var list = Fixtures.call("sql_list_schemas", Fixtures.object());
    assertThat(sqlite.classify(list, cfg, Fixtures.policy(Env.DEV, null)).kind()).isEqualTo(Classification.Kind.READ);
    ToolResult listed = sqlite.execute(list, cfg, Fixtures.Secrets.none(), context(Env.DEV));
    JsonNode out = listed.structuredContent();
    assertThat(out.path("schemas").get(0).path("name").asString()).isEqualTo("main");
    assertThat(out.path("schemas").get(0).path("tables").asInt()).isEqualTo(2);
    assertThat(out.path("schemas").get(0).path("views").asInt()).isEqualTo(1);
    assertThat(out.path("tables").toString()).contains("\"Orders\"", "customers", "big_orders")
        .doesNotContain("sqlite_");

    var describe = Fixtures.call("sql_describe_table", Fixtures.object().put("table", "orders"));
    assertThat(sqlite.classify(describe, cfg, Fixtures.policy(Env.DEV, null)).kind())
        .isEqualTo(Classification.Kind.READ);
    JsonNode t = sqlite.execute(describe, cfg, Fixtures.Secrets.none(), context(Env.DEV)).structuredContent();
    assertThat(t.path("name").asString()).isEqualTo("Orders");
    assertThat(t.path("kind").asString()).isEqualTo("table");
    assertThat(t.path("primaryKey").toString()).isEqualTo("[\"id\"]");
    assertThat(t.path("columns").get(1).path("name").asString()).isEqualTo("customer_id");
    assertThat(t.path("columns").get(1).path("nullable").asBoolean()).isFalse();
    assertThat(t.path("foreignKeys").get(0).path("refTable").asString()).isEqualTo("customers");
    assertThat(t.path("foreignKeys").get(0).path("definition").asString()).isEqualTo("ON DELETE CASCADE");
    assertThat(t.path("indexes").get(0).path("definition").asString()).contains("CREATE INDEX orders_by_customer");
    assertThat(t.path("definition").asString()).contains("CHECK (total >= 0)");
    JsonNode customers = sqlite.execute(Fixtures.call("sql_describe_table", Fixtures.object().put("table",
        "main.CUSTOMERS")), cfg, Fixtures.Secrets.none(), context(Env.DEV)).structuredContent();
    assertThat(customers.path("referencedBy").get(0).path("table").asString()).isEqualTo("Orders");
    assertThat(customers.path("indexes").get(0).path("unique").asBoolean()).isTrue();
    assertThat(customers.path("columns").get(2).path("default").asString()).isEqualTo("'anonymous'");
  }

  @Test
  void readsValuesByTheirStorageClass() throws Exception {
    ToolResult r = query("SELECT id, total, note, created, 9007199254740993 AS big, NULL AS empty "
        + "FROM \"Orders\" WHERE id = 10");
    JsonNode row = r.table().rows().get(0);
    assertThat(row.get(0).asLong()).isEqualTo(10);
    assertThat(row.get(1).asDouble()).isEqualTo(250.5);
    assertThat(row.get(2).asString()).isEqualTo("\\x00ff10");
    assertThat(row.get(3).asString()).isEqualTo("2026-11-01 10:00:00");
    assertThat(row.get(4).asString()).isEqualTo("9007199254740993");
    assertThat(row.get(5).isNull()).isTrue();
    assertThat(r.table().columns().get(0).sourceSchema()).isEqualTo("main");
    assertThat(r.table().columns().get(0).sourceTable()).isEqualTo("Orders");
  }

  @Test
  void sqliteSyntaxTheParserDoesNotKnowStillReads() throws Exception {
    assertThat(query("SELECT name FROM customers WHERE email GLOB '*@example.test'").table().rows()).hasSize(2);
    assertThat(query("SELECT name FROM [customers] WHERE `id` = 1").table().rows().get(0).get(0).asString())
        .isEqualTo("Ada");
    assertThat(query("EXPLAIN QUERY PLAN SELECT * FROM \"Orders\" WHERE customer_id = 1").table().rows())
        .isNotEmpty();
    assertThat(query("SELECT json_extract('{\"a\": 2}', '$.a') + 1").table().rows().get(0).get(0).asLong())
        .isEqualTo(3);
    assertThat(classify("sql_execute", "INSERT OR IGNORE INTO customers(id, email) VALUES (1, 'x@example.test')")
        .kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(classify("sql_execute", "UPDATE OR REPLACE customers SET name = 'A' WHERE id = 1").kind())
        .isEqualTo(Classification.Kind.MUTATION);
  }

  // ── What a read can't do ────────────────────────────────────────────────────────────────────────────────────

  @Test
  void sessionFileAndExtensionStatementsAreRefusedForEveryTool() {
    for (String sql : List.of("PRAGMA writable_schema = 1", "PRAGMA query_only = 0", "ATTACH '/tmp/x.db' AS x",
        "DETACH x", "VACUUM", "VACUUM INTO '/tmp/copy.db'", "ANALYZE", "REINDEX", "BEGIN IMMEDIATE",
        "CREATE TEMP TABLE scratch(a)", "CREATE TABLE temp.scratch(a)", "CREATE TABLE \"temp\".scratch(a)",
        "SELECT load_extension('/tmp/evil')", "SELECT fts3_tokenizer('simple', x'00')",
        "SELECT * FROM sqlite_dbpage('main')", "SELECT readfile('/etc/passwd')")) {
      for (String tool : List.of("sql_query", "sql_execute")) {
        assertThat(classify(tool, sql).kind()).as(tool + ": " + sql).isEqualTo(Classification.Kind.DENY);
      }
    }
  }

  @Test
  void readsAndChangesOneStatementAtATime() {
    assertThat(classify("sql_query", "SELECT 1; DELETE FROM customers").kind()).isEqualTo(Classification.Kind.DENY);
    Classification many = classify("sql_execute", "DELETE FROM \"Orders\" WHERE id = 10; DELETE FROM customers");
    assertThat(many.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(many.reason()).contains("SQLite runs one statement per sql_execute");
    assertThat(classify("sql_query", "DELETE FROM customers").kind()).isEqualTo(Classification.Kind.DENY);
  }

  @Test
  void theFileItselfRefusesWritesOfAReadEvenWhenTheGatewayIsBypassed() {
    ConnectionConfig cfg = config(Env.DEV);
    ConnectionSettings s = ConnectionSettings.of(cfg, sqlite.dialect());
    for (String sql : List.of("DELETE FROM customers", "INSERT INTO customers(email) VALUES ('x@example.test')",
        "CREATE TABLE intruder(a)", "CREATE TEMP TABLE scratch(a)",
        "ATTACH '" + temp.resolve("other.db") + "' AS other",
        "VACUUM INTO '" + temp.resolve("copy.db") + "'", "PRAGMA writable_schema = 1")) {
      try {
        sqlite.runRead(cfg, s, Fixtures.Secrets.none(), context(Env.DEV), SqlExecutor.BoundSql.plain(sql), 10);
      } catch (ConnectorException e) {
        continue; // refused by SQLite itself
      }
      // A statement that ran must not have changed anything.
    }
    assertThat(Files.exists(temp.resolve("other.db"))).isFalse();
    assertThat(Files.exists(temp.resolve("copy.db"))).isFalse();
    assertThatThrownBy(() -> sqlite.runRead(cfg, s, Fixtures.Secrets.none(), context(Env.DEV),
        SqlExecutor.BoundSql.plain("DELETE FROM customers"), 10))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.getMessage()).contains("read-only"));
  }

  @Test
  void aRunawayReadStopsAtTheStatementTimeout() {
    ConnectionConfig cfg = config(Env.DEV);
    var call = Fixtures.sql("sql_query", "WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r) "
        + "SELECT count(*) FROM r");
    assertThat(sqlite.classify(call, cfg, Fixtures.policy(Env.DEV, null)).kind()).isEqualTo(Classification.Kind.READ);
    long started = System.nanoTime();
    assertThatThrownBy(() -> sqlite.execute(call, cfg, Fixtures.Secrets.none(),
        context(Env.DEV).with(Fixtures.limits(100, Duration.ofMillis(300)))))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.getMessage()).contains("Statement timeout"));
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
  }

  @Test
  void aCancelledCallStopsItsStatement() throws Exception {
    ConnectionConfig cfg = config(Env.DEV);
    Fixtures.Context ctx = context(Env.DEV).with(Fixtures.limits(100, Duration.ofSeconds(30)));
    Thread.ofVirtual().start(() -> {
      try {
        Thread.sleep(300);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
      ctx.cancel();
    });
    long started = System.nanoTime();
    assertThatThrownBy(() -> sqlite.execute(Fixtures.sql("sql_query", "WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL "
        + "SELECT n + 1 FROM r) SELECT count(*) FROM r"), cfg, Fixtures.Secrets.none(), ctx))
        .isInstanceOf(ConnectorException.class);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
  }

  @Test
  void restrictedTablesCloseCatalogsAndPragmaFunctions() {
    ObjectNode policy = Fixtures.object();
    policy.putArray("allowlist").add("main.customers");
    ConnectionConfig cfg = config(Env.DEV);
    for (String sql : List.of("SELECT * FROM pragma_table_info('Orders')", "SELECT * FROM sqlite_schema",
        "SELECT * FROM \"Orders\"")) {
      assertThat(sqlite.classify(Fixtures.sql("sql_query", sql), cfg, Fixtures.policy(Env.DEV, policy)).kind())
          .as(sql).isEqualTo(Classification.Kind.DENY);
    }
    assertThat(sqlite.classify(Fixtures.sql("sql_query", "SELECT * FROM CUSTOMERS"), cfg,
        Fixtures.policy(Env.DEV, policy)).kind()).isEqualTo(Classification.Kind.READ);
  }

  // ── Approved changes ────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void anApprovedChangeCommitsWithForeignKeysAndReturning() throws Exception {
    ConnectionConfig cfg = config(Env.DEV);
    String sql = "UPDATE customers SET name = 'Lin' WHERE id = 2 RETURNING id, name";
    var call = Fixtures.sql("sql_execute", sql);
    assertThat(sqlite.classify(call, cfg, Fixtures.policy(Env.DEV, null)).kind())
        .isEqualTo(Classification.Kind.MUTATION);
    assertThatThrownBy(() -> sqlite.execute(call, cfg, Fixtures.Secrets.none(), context(Env.DEV)))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    ToolResult done = sqlite.execute(call, cfg, Fixtures.Secrets.none(),
        context(Env.DEV).approved(Fixtures.object().put("sql", sql)));
    assertThat(done.structuredContent().path("committed").asBoolean()).isTrue();
    assertThat(done.structuredContent().path("statements").get(0).path("returning").get(0).path("name").asString())
        .isEqualTo("Lin");
    assertThat(query("SELECT name FROM customers WHERE id = 2").table().rows().get(0).get(0).asString())
        .isEqualTo("Lin");

    String orphan = "INSERT INTO \"Orders\"(customer_id, total) VALUES (99, 1)";
    assertThatThrownBy(() -> sqlite.execute(Fixtures.sql("sql_execute", orphan), cfg, Fixtures.Secrets.none(),
        context(Env.DEV).approved(Fixtures.object().put("sql", orphan))))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.getMessage()).contains("FOREIGN KEY"));

    String cascade = "DELETE FROM customers WHERE id = 1";
    sqlite.execute(Fixtures.sql("sql_execute", cascade), cfg, Fixtures.Secrets.none(),
        context(Env.DEV).approved(Fixtures.object().put("sql", cascade)));
    assertThat(query("SELECT count(*) FROM \"Orders\" WHERE customer_id = 1").table().rows().get(0).get(0).asLong())
        .isZero();
  }

  @Test
  void schemaChangesOfSqliteRollBackOnError() {
    ConnectionConfig cfg = config(Env.DEV);
    var preview = sqlite.describeAction(Fixtures.sql("sql_execute", "ALTER TABLE customers ADD COLUMN phone TEXT"),
        cfg);
    assertThat(preview.impact().toString()).contains("rolled back on error");
    assertThat(preview.target()).contains(database.getFileName().toString());
  }

  // ── The file ────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void testConnectionReportsTheFileAndWhetherGateRookMayWriteIt() {
    TestResult dev = sqlite.test(config(Env.DEV), Fixtures.Secrets.none(), context(Env.DEV));
    assertThat(dev.ok()).as(String.valueOf(dev.error())).isTrue();
    assertThat(dev.serverVersion()).startsWith("SQLite 3.");
    assertThat(dev.checks()).extracting(TestResult.Check::name).contains("connect", "file", "permissions")
        .doesNotContain("tls");
    assertThat(dev.permissions().level()).isEqualTo(TestResult.Level.WRITE);
    assertThat(dev.hints()).anySatisfy(h -> assertThat(h.code()).isEqualTo("sql.user-can-write"));
    TestResult prod = sqlite.test(config(Env.PROD), Fixtures.Secrets.none(), context(Env.PROD));
    assertThat(prod.hints()).anySatisfy(h -> assertThat(h.severity()).isEqualTo(TestResult.Severity.WARNING));
  }

  @Test
  void aReadOnlyFileIsReportedAsReadOnly() throws Exception {
    assertThat(database.toFile().setWritable(false)).isTrue();
    try {
      TestResult r = sqlite.test(config(Env.PROD), Fixtures.Secrets.none(), context(Env.PROD));
      assertThat(r.ok()).isTrue();
      assertThat(r.permissions().level()).isEqualTo(TestResult.Level.READ);
      assertThat(query("SELECT count(*) FROM customers").table().rows().get(0).get(0).asLong()).isEqualTo(2);
    } finally {
      database.toFile().setWritable(true);
    }
  }

  @Test
  void onlyAnExistingDatabaseFileOutsideGateRookCanBeOpened() throws Exception {
    Path junk = Files.writeString(temp.resolve("notes.txt"), "Not a database, just text.", StandardCharsets.UTF_8);
    Path own = Files.copy(database, home.resolve("gateway.db"));
    Path inHome = Files.copy(database, home.resolve("copy.sqlite"));
    Path link = Files.createSymbolicLink(temp.resolve("innocent.db"), own);
    for (String file : List.of("relative/shop.sqlite3", database + "?open_mode=6", temp.resolve("missing.db").toString(),
        temp.toString(), junk.toString(), own.toString(), inHome.toString(), link.toString(), "\\\\server\\share\\x.db")) {
      TestResult r = sqlite.test(config(Env.DEV, file), Fixtures.Secrets.none(), context(Env.DEV));
      assertThat(r.ok()).as(file).isFalse();
      assertThat(r.error().code()).as(file).isEqualTo("sql.file");
      assertThatThrownBy(() -> sqlite.execute(Fixtures.sql("sql_query", "SELECT 1"), config(Env.DEV, file),
          Fixtures.Secrets.none(), context(Env.DEV))).as(file).isInstanceOf(ConnectorException.class);
    }
    assertThat(Files.exists(temp.resolve("missing.db"))).isFalse();
    assertThat(sqlite.test(config(Env.DEV, own.toString()), Fixtures.Secrets.none(), context(Env.DEV)).error()
        .message()).contains("GateRook's own files");
  }

  @Test
  void anEmptyFileIsAnEmptyDatabase() throws Exception {
    Path empty = Files.createFile(temp.resolve("empty.db"));
    TestResult r = sqlite.test(config(Env.DEV, empty.toString()), Fixtures.Secrets.none(), context(Env.DEV));
    assertThat(r.ok()).isTrue();
    assertThat(r.hints()).anySatisfy(h -> assertThat(h.code()).isEqualTo("sql.empty-database"));
    assertThat(Files.size(empty)).isZero();
  }

  @Test
  void healthOpensTheFileWithoutThePool() throws Exception {
    assertThat(sqlite.health(config(Env.DEV), Fixtures.Secrets.none(), context(Env.DEV)).state())
        .isEqualTo(HealthResult.State.HEALTHY);
    HealthResult missing = sqlite.health(config(Env.DEV, temp.resolve("missing.db").toString()),
        Fixtures.Secrets.none(), context(Env.DEV));
    assertThat(missing.state()).isEqualTo(HealthResult.State.UNREACHABLE);
    assertThat(missing.message()).contains("doesn't exist");
    assertThat(sqlite.pools().current("conn-sqlite-dev")).isNull();
  }

  @Test
  void readsAndChangesUseConnectionsOfTheirOwn() throws Exception {
    ConnectionConfig cfg = config(Env.DEV);
    query("SELECT 1");
    assertThat(sqlite.pools().current("conn-sqlite-dev", null)).isNotNull();
    assertThat(sqlite.pools().currentWrites("conn-sqlite-dev", null)).isNull();
    String sql = "UPDATE customers SET name = 'B' WHERE id = 2";
    sqlite.execute(Fixtures.sql("sql_execute", sql), cfg, Fixtures.Secrets.none(),
        context(Env.DEV).approved(Fixtures.object().put("sql", sql)));
    assertThat(sqlite.pools().currentWrites("conn-sqlite-dev", null)).isNotNull();
    try (Connection read = sqlite.pools().current("conn-sqlite-dev", null).borrow()) {
      assertThat(read.isReadOnly()).isTrue();
    }
  }

  // ── Lexing, names, values ───────────────────────────────────────────────────────────────────────────────────

  @Test
  void sqliteNamesAndLiterals() {
    assertThat(IdentifierRules.table("[Main].[Orders]", SqlSyntax.SQLITE, "main"))
        .isEqualTo(new IdentifierRules.TableRef("main", "orders"));
    assertThat(IdentifierRules.table("`a``b`", SqlSyntax.SQLITE, "main"))
        .isEqualTo(new IdentifierRules.TableRef("main", "a`b"));
    assertThat(IdentifierRules.parts("[a]]b]", SqlSyntax.SQLITE)).isEmpty();
    assertThat(IdentifierRules.normalize(new IdentifierRules.Part("ÄBC", true), SqlSyntax.SQLITE)).isEqualTo("Äbc");
    assertThat(IdentifierRules.quoteIfNeeded("Orders", SqlSyntax.SQLITE)).isEqualTo("Orders");
    assertThat(IdentifierRules.quoteIfNeeded("order items", SqlSyntax.SQLITE)).isEqualTo("\"order items\"");
    SqlLexer.Scan scan = SqlLexer.scan("SELECT '[x;', \"a\"\"b\", [c\"d], `e;` /* ; */ -- ;\n; SELECT 2",
        SqlSyntax.SQLITE);
    assertThat(scan.unterminated()).isFalse();
    assertThat(scan.statements()).hasSize(2);
    assertThat(SqlLexer.scan("SELECT 1 /* open", SqlSyntax.SQLITE).unterminated()).isTrue();
    assertThat(SqlClassifier.analyze("SELECT 'a\\' FROM customers", SqlSyntax.SQLITE).kind())
        .isEqualTo(SqlClassifier.Kind.READ);
  }

  @Test
  void templateValuesBindAsSqliteStoresThem() {
    assertThat(TemplateParams.sqliteTime(OffsetDateTime.of(2026, 11, 1, 12, 30, 0, 0, ZoneOffset.ofHours(2))))
        .isEqualTo("2026-11-01 10:30:00");
    assertThat(TemplateParams.sqliteTime(OffsetDateTime.of(2026, 11, 1, 12, 30, 0, 250_000_000, ZoneOffset.UTC)))
        .isEqualTo("2026-11-01 12:30:00.250");
  }

  @Test
  void errorsSaySqlitesOwnMessage() {
    assertThatThrownBy(() -> query("SELECT * FROM ordrs"))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.getMessage()).contains("SQLITE_ERROR").contains("no such table: ordrs"));
  }
}
