package ai.msrv.gaterook.connectors.h2;

import static ai.msrv.gaterook.connectors.h2.TestCalls.call;
import static ai.msrv.gaterook.connectors.h2.TestCalls.config;
import static ai.msrv.gaterook.connectors.h2.TestCalls.policy;
import static ai.msrv.gaterook.connectors.h2.TestCalls.sql;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import org.h2.tools.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The H2 connector against a real H2 server in this JVM, over H2's TCP protocol (without a module host, so without the
 * TCP gate: H2ModuleHostIT covers that): what each tool returns, what a read can't do, how a change runs, and what a
 * test says about the user.
 */
class H2ConnectorTest {

  @TempDir
  static Path dir;

  static Server server;
  /** Keeps the in-memory database open for the whole class. */
  static Connection keeper;
  static int port;

  private final H2Connector connector = new H2Connector(TestCalls.definition(), false);

  @BeforeAll
  static void start() throws Exception {
    server = Server.createTcpServer("-tcpPort", "0", "-ifNotExists", "-baseDir", dir.toString()).start();
    port = server.getPort();
    String url = "jdbc:h2:tcp://127.0.0.1:" + port + "/mem:orders";
    keeper = DriverManager.getConnection(url, "sa", "sa-secret");
    TestCalls.run(url, "sa", "sa-secret",
        "CREATE TABLE ORDERS (ID BIGINT PRIMARY KEY, NOTE VARCHAR(20) DEFAULT 'new', TOTAL DECIMAL(10, 2), "
            + "PAID BOOLEAN, AT TIMESTAMP WITH TIME ZONE, DUE DATE, TAGS INTEGER ARRAY, DOC JSON, KEY_ UUID, "
            + "BLOB_ VARBINARY(8))",
        "COMMENT ON TABLE ORDERS IS 'Customer orders'",
        "CREATE INDEX BY_NOTE ON ORDERS(NOTE)",
        "CREATE TABLE LINES (ORDER_ID BIGINT REFERENCES ORDERS(ID), N INT, PRIMARY KEY (ORDER_ID, N))",
        "INSERT INTO ORDERS VALUES (1, 'first', 12.50, TRUE, TIMESTAMP WITH TIME ZONE '2026-11-11 09:30:00+01', "
            + "DATE '2026-12-01', ARRAY[1, 2], JSON '{\"a\":1}', '6e8bc430-9c3a-11d9-9669-0800200c9a66', X'0102')",
        "INSERT INTO ORDERS (ID, NOTE, TOTAL) VALUES (9007199254740993, 'big', 0.10)",
        "CREATE VIEW PAID_ORDERS AS SELECT ID, NOTE FROM ORDERS WHERE PAID",
        "CREATE SEQUENCE SEQ",
        "CREATE ALIAS MY_ABS FOR 'java.lang.Math.abs(int)'",
        "CREATE USER READER PASSWORD 'reader-secret'",
        "GRANT SELECT ON ORDERS TO READER",
        "CREATE USER WRITER PASSWORD 'writer-secret'",
        "GRANT SELECT, INSERT ON ORDERS TO WRITER");
  }

  @AfterAll
  static void stop() throws Exception {
    keeper.close();
    server.stop();
  }

  private static ConnectionConfig sa() {
    return config(Env.DEV, port, "mem:orders", "sa");
  }

  private static TestCalls.Context ctx() {
    return new TestCalls.Context(dir, false);
  }

  private static TestCalls.Secrets password(String p) {
    return TestCalls.Secrets.password(p);
  }

  private static String text(ToolResult r) {
    return r.content().isEmpty() ? "" : r.content().getFirst().text();
  }

  @Test
  void listsAndDescribesTablesAndViews() throws Exception {
    ToolResult tables = connector.execute(call(H2Connector.LIST_TABLES, "{}"), sa(), password("sa-secret"), ctx());
    assertThat(tables.table().rows().toString()).contains("\"ORDERS\",\"table\"").contains("\"PAID_ORDERS\",\"view\"")
        .contains("Customer orders");
    ToolResult like = connector.execute(call(H2Connector.LIST_TABLES, "{\"like\":\"pai%\"}"), sa(),
        password("sa-secret"), ctx());
    assertThat(like.table().rows()).hasSize(1);

    JsonNode orders = connector.execute(call(H2Connector.DESCRIBE_TABLE, "{\"table\":\"orders\"}"), sa(),
        password("sa-secret"), ctx()).structuredContent();
    assertThat(orders.path("schema").asString()).isEqualTo("PUBLIC");
    assertThat(orders.path("kind").asString()).isEqualTo("table");
    assertThat(orders.path("comment").asString()).isEqualTo("Customer orders");
    assertThat(orders.path("primaryKey").toString()).isEqualTo("[\"ID\"]");
    assertThat(orders.path("columns").get(1).toString()).contains("\"name\":\"NOTE\"")
        .contains("\"type\":\"CHARACTER VARYING(20)\"").contains("\"default\":\"'new'\"");
    assertThat(orders.path("columns").get(2).path("type").asString()).isEqualTo("NUMERIC(10, 2)");
    assertThat(orders.path("indexes").toString()).contains("BY_NOTE");
    JsonNode lines = connector.execute(call(H2Connector.DESCRIBE_TABLE, "{\"table\":\"LINES\"}"), sa(),
        password("sa-secret"), ctx()).structuredContent();
    assertThat(lines.path("primaryKey").toString()).isEqualTo("[\"ORDER_ID\",\"N\"]");
    assertThat(lines.path("foreignKeys").get(0).path("references").asString()).isEqualTo("PUBLIC.ORDERS");
    JsonNode view = connector.execute(call(H2Connector.DESCRIBE_TABLE, "{\"table\":\"PAID_ORDERS\"}"), sa(),
        password("sa-secret"), ctx()).structuredContent();
    assertThat(view.path("kind").asString()).isEqualTo("view");
    assertThat(view.path("viewDefinition").asString()).contains("WHERE");
    ToolResult missing = connector.execute(call(H2Connector.DESCRIBE_TABLE, "{\"table\":\"NOPE\"}"), sa(),
        password("sa-secret"), ctx());
    assertThat(missing.isError()).isTrue();
  }

  @Test
  void aQueryKeepsH2sTypesExactAndNeverDeserializesJavaObjects() throws Exception {
    ToolResult r = connector.execute(sql(H2Connector.QUERY, "SELECT ID, NOTE, TOTAL, PAID, AT, DUE, TAGS, DOC, KEY_, "
        + "BLOB_ FROM ORDERS ORDER BY ID"), sa(), password("sa-secret"), ctx());
    String rows = r.table().rows().toString();
    assertThat(rows).contains("[1,\"first\",\"12.50\",true,\"2026-11-11T09:30+01:00\",\"2026-12-01\",[1,2],"
        + "{\"a\":1},\"6e8bc430-9c3a-11d9-9669-0800200c9a66\",\"\\\\x0102\"]");
    assertThat(rows).contains("[\"9007199254740993\",\"big\",\"0.10\"");
    ToolResult java = connector.execute(sql(H2Connector.QUERY, "SELECT CAST(X'ACED0005' AS JAVA_OBJECT) AS O"), sa(),
        password("sa-secret"), ctx());
    assertThat(java.table().rows().get(0).get(0).asString()).isEqualTo("\\xaced0005");
  }

  @Test
  void rowsStopAtTheLimitAndSayWhy() throws Exception {
    ObjectNode args = TestCalls.call(H2Connector.QUERY, "{}").arguments().put("sql", "SELECT X FROM SYSTEM_RANGE(1, "
        + "50)").put("limit", 10);
    ToolResult r = connector.execute(new ai.msrv.gaterook.connector.api.ToolCall("tc", H2Connector.QUERY, "warehouse",
        args, null), sa(), password("sa-secret"), ctx());
    assertThat(r.table().rows()).hasSize(10);
    assertThat(r.truncatedReason()).isEqualTo("row_limit");
  }

  @Test
  void aReadCallsNothingButH2sOwnFunctions() throws Exception {
    Classification c = connector.classify(sql(H2Connector.QUERY, "SELECT FILE_READ('/etc/hosts')"), sa(),
        policy(Env.DEV));
    assertThat(c.kind()).isEqualTo(Classification.Kind.DENY);
    assertThatThrownBy(() -> connector.execute(sql(H2Connector.QUERY, "SELECT MY_ABS(-3)"), sa(),
        password("sa-secret"), ctx())).isInstanceOf(ConnectorException.class)
        .hasMessageContaining("MY_ABS isn't one of H2's built-in functions");
    assertThat(connector.classify(sql(H2Connector.QUERY, "SELECT ABS(-3)"), sa(), policy(Env.DEV)).kind())
        .isEqualTo(Classification.Kind.READ);
    // NEXTVAL would advance the sequence past a rollback.
    assertThat(connector.classify(sql(H2Connector.QUERY, "SELECT NEXT VALUE FOR SEQ"), sa(), policy(Env.DEV)).kind())
        .isEqualTo(Classification.Kind.DENY);
    ToolResult refused = connector.execute(sql(H2Connector.QUERY, "SELECT * FROM NOPE"), sa(), password("sa-secret"),
        ctx());
    assertThat(refused.isError()).isTrue();
    assertThat(text(refused)).startsWith("H2 refused the query: Table \"NOPE\" not found");
  }

  @Test
  void aChangeRunsOnlyApprovedAndCommitsOnce() throws Exception {
    assertThatThrownBy(() -> connector.execute(sql(H2Connector.EXECUTE, "INSERT INTO ORDERS (ID) VALUES (5)"), sa(),
        password("sa-secret"), ctx())).hasMessageContaining("Not approved");
    String insert = "INSERT INTO ORDERS (ID, NOTE) VALUES (5, 'approved')";
    TestCalls.Context approved = new TestCalls.Context(dir, true, TestCalls.sql(H2Connector.EXECUTE, insert)
        .arguments(), Env.DEV, TestCalls.DEV, Duration.ofSeconds(10));
    JsonNode out = connector.execute(sql(H2Connector.EXECUTE, insert), sa(), password("sa-secret"), approved)
        .structuredContent();
    assertThat(out.path("statement").asString()).isEqualTo("INSERT");
    assertThat(out.path("target").asString()).isEqualTo("ORDERS");
    assertThat(out.path("rowsChanged").asLong()).isEqualTo(1);
    ToolResult after = connector.execute(sql(H2Connector.QUERY, "SELECT NOTE FROM ORDERS WHERE ID = 5"), sa(),
        password("sa-secret"), ctx());
    assertThat(after.table().rows().toString()).isEqualTo("[[\"approved\"]]");
    // The approved text, not another one.
    TestCalls.Context other = new TestCalls.Context(dir, true, TestCalls.sql(H2Connector.EXECUTE,
        "DELETE FROM ORDERS").arguments(), Env.DEV, TestCalls.DEV, Duration.ofSeconds(10));
    assertThatThrownBy(() -> connector.execute(sql(H2Connector.EXECUTE, insert), sa(), password("sa-secret"), other))
        .hasMessageContaining("The approved statement isn't this call's");
    ActionPreview preview = connector.describeAction(sql(H2Connector.EXECUTE, "DROP TABLE LINES"), sa());
    assertThat(preview.warnings()).contains("DROP TABLE can't be undone once it commits.");
    // H2's refusal of an approved change is an answer, and nothing changed.
    TestCalls.Context dup = new TestCalls.Context(dir, true, TestCalls.sql(H2Connector.EXECUTE, insert).arguments(),
        Env.DEV, TestCalls.DEV, Duration.ofSeconds(10));
    ToolResult again = connector.execute(sql(H2Connector.EXECUTE, insert), sa(), password("sa-secret"), dup);
    assertThat(again.isError()).isTrue();
    assertThat(text(again)).startsWith("H2 refused the change, so nothing changed:");
  }

  @Test
  void aTestSaysWhoTheUserIsToTheDatabase() {
    TestResult admin = connector.test(sa(), password("sa-secret"), ctx());
    assertThat(admin.ok()).isTrue();
    assertThat(admin.serverVersion()).isEqualTo("H2 2.5.252");
    assertThat(admin.identity()).isEqualTo("SA");
    assertThat(admin.permissions().level()).isEqualTo(TestResult.Level.ADMIN);
    assertThat(admin.hints()).extracting(TestResult.Hint::code).contains("h2.admin");

    TestResult reader = connector.test(config(Env.DEV, port, "mem:orders", "READER"), password("reader-secret"),
        ctx());
    assertThat(reader.ok()).isTrue();
    assertThat(reader.permissions().level()).isEqualTo(TestResult.Level.READ);
    assertThat(reader.hints()).extracting(TestResult.Hint::code).doesNotContain("h2.admin", "h2.writer");

    TestResult writer = connector.test(config(Env.DEV, port, "mem:orders", "WRITER"), password("writer-secret"),
        ctx());
    assertThat(writer.permissions().level()).isEqualTo(TestResult.Level.WRITE);
    assertThat(writer.hints()).extracting(TestResult.Hint::code).contains("h2.writer");
  }

  @Test
  void aTestNamesWhatWentWrong() {
    TestResult login = connector.test(sa(), password("wrong"), ctx());
    assertThat(login.error().code()).isEqualTo("h2.login");
    TestResult missing = connector.test(config(Env.DEV, port, "mem:missing", "sa"), password("sa-secret"), ctx());
    assertThat(missing.error().code()).as(missing.error().technical()).isEqualTo("h2.no-database");
    TestResult internet = connector.test(config(Env.DEV, "8.8.8.8", port, "mem:orders", "sa", null),
        password("sa-secret"), ctx());
    assertThat(internet.error().code()).isEqualTo("h2.config");
    assertThat(internet.error().message()).contains("an address on the internet");
    TestResult closed = connector.test(config(Env.DEV, port + 1 == 65_536 ? 1 : port + 1, "mem:orders", "sa"),
        password("sa-secret"), ctx());
    assertThat(closed.error().code()).isEqualTo("h2.unreachable");
    assertThat(connector.test(config(Env.DEV, port, "mem:x;INIT=RUNSCRIPT FROM 'y'", "sa"), password("sa-secret"),
        ctx()).error().message()).contains("GateRook sets the URL's settings itself");
  }

  @Test
  void aSlowQueryStopsAtItsTimeout() {
    TestCalls.Context ctx = new TestCalls.Context(dir, false, null, Env.DEV,
        TestCalls.limits(1000, Duration.ofSeconds(1)), Duration.ofSeconds(20));
    long started = System.nanoTime();
    assertThatThrownBy(() -> connector.execute(sql(H2Connector.QUERY, "SELECT COUNT(*) FROM SYSTEM_RANGE(1, "
        + "100000000) A, SYSTEM_RANGE(1, 1000) B"), sa(), password("sa-secret"), ctx))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("ran past its time limit");
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(15));
  }

  @Test
  void withoutTheHostsTcpGateThePluginOpensNoSocket() {
    H2Connector gated = new H2Connector(TestCalls.definition(), true);
    assertThatThrownBy(() -> gated.execute(sql(H2Connector.QUERY, "SELECT 1"), sa(), password("sa-secret"), ctx()))
        .hasMessageContaining("doesn't check this connector's TCP connections");
  }

  @Test
  void theUrlCarriesOnlyGateRooksSettings() throws Exception {
    H2Config c = H2Config.of(config(Env.DEV, "::1", 9092, "/srv/h2/orders", "sa", null));
    assertThat(c.url()).isEqualTo("jdbc:h2:tcp://[::1]:9092//srv/h2/orders;IFEXISTS=TRUE");
    assertThat(H2Config.of(config(Env.DEV, port, "~/orders", "sa")).url()).endsWith("/~/orders;IFEXISTS=TRUE");
    assertThat(H2Config.databaseProblem("tcp://other:9092/x")).contains("not another server");
    assertThat(H2Connector.privateOrLocal(java.net.InetAddress.ofLiteral("10.1.2.3"))).isTrue();
    assertThat(H2Connector.privateOrLocal(java.net.InetAddress.ofLiteral("::ffff:192.168.1.5"))).isTrue();
    assertThat(H2Connector.privateOrLocal(java.net.InetAddress.ofLiteral("fd12::1"))).isTrue();
    assertThat(H2Connector.privateOrLocal(java.net.InetAddress.ofLiteral("100.64.0.1"))).isTrue();
    assertThat(H2Connector.privateOrLocal(java.net.InetAddress.ofLiteral("1.1.1.1"))).isFalse();
    assertThat(H2Connector.privateOrLocal(java.net.InetAddress.ofLiteral("172.32.0.1"))).isFalse();
  }
}
