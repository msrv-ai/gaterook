package ai.msrv.gaterook.connectors.access;

import static ai.msrv.gaterook.connectors.access.TestCalls.call;
import static ai.msrv.gaterook.connectors.access.TestCalls.config;
import static ai.msrv.gaterook.connectors.access.TestCalls.policy;
import static ai.msrv.gaterook.connectors.access.TestCalls.sql;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * The Access connector on a real .accdb file written by Jackcess, in this JVM (without a module host: AccessModuleHostIT
 * runs the release in one): what each tool returns, what a read can't do at each layer, and how a change reaches the
 * file.
 */
class AccessConnectorTest {

  @TempDir
  Path temp;

  private Path home;
  private Path workDir;
  private Path file;
  private final AccessConnector connector = new AccessConnector(TestCalls.definition());

  @BeforeEach
  void setUp() throws Exception {
    home = Files.createDirectories(temp.resolve("gaterook-home"));
    workDir = TestCalls.workDir(home);
    file = TestCalls.database(temp.resolve("project").resolve("orders.accdb"));
  }

  private TestCalls.Context ctx() {
    return new TestCalls.Context(workDir, false);
  }

  private TestCalls.Context approved(String statement) {
    return new TestCalls.Context(workDir, true, sql(AccessConnector.EXECUTE, statement).arguments(), Env.DEV,
        TestCalls.DEV, Duration.ofSeconds(20));
  }

  private static String text(ToolResult r) {
    return r.content().isEmpty() ? "" : r.content().getFirst().text();
  }

  @Test
  void listsAndDescribesTheTables() throws Exception {
    ToolResult tables = connector.execute(call(AccessConnector.LIST_TABLES, "{}"), config(Env.DEV, file.toString()),
        new TestCalls.NoSecrets(), ctx());
    assertThat(tables.table().rows().toString()).contains("[\"Order Lines\",\"table\"]")
        .contains("[\"Orders\",\"table\"]");
    ToolResult like = connector.execute(call(AccessConnector.LIST_TABLES, "{\"like\":\"order l%\"}"),
        config(Env.DEV, file.toString()), new TestCalls.NoSecrets(), ctx());
    assertThat(like.table().rows()).hasSize(1);

    JsonNode orders = connector.execute(call(AccessConnector.DESCRIBE_TABLE, "{\"table\":\"orders\"}"),
        config(Env.DEV, file.toString()), new TestCalls.NoSecrets(), ctx()).structuredContent();
    assertThat(orders.path("name").asString()).isEqualTo("Orders");
    assertThat(orders.path("kind").asString()).isEqualTo("table");
    assertThat(orders.path("primaryKey").toString()).isEqualTo("[\"ID\"]");
    assertThat(orders.path("columns").get(0).path("autoNumber").asBoolean()).isTrue();
    assertThat(orders.path("columns").get(1).path("name").asString()).isEqualTo("Note");
    ToolResult missing = connector.execute(call(AccessConnector.DESCRIBE_TABLE, "{\"table\":\"Order\"}"),
        config(Env.DEV, file.toString()), new TestCalls.NoSecrets(), ctx());
    assertThat(missing.isError()).isTrue();
    assertThat(text(missing)).contains("Close: Order Lines, Orders");
  }

  @Test
  void aQueryKeepsAccessTypesExact() throws Exception {
    ToolResult r = connector.execute(sql(AccessConnector.QUERY, "SELECT ID, Note, Total, Paid, OrderedAt, Memo, Picture, "
        + "Ratio FROM Orders ORDER BY ID"), config(Env.DEV, file.toString()), new TestCalls.NoSecrets(), ctx());
    assertThat(r.isError()).as(text(r)).isFalse();
    assertThat(r.table().rows().get(0).toString()).isEqualTo("[1,\"first\",\"12.5000\",true,\"2026-11-12T09:30\","
        + "\"long text\",\"\\\\x0102\",0.25]");
    ToolResult access = connector.execute(sql(AccessConnector.QUERY, "SELECT IIf(Paid, 'paid', 'open') AS State, "
        + "Note & '!' AS Shout FROM [Orders] WHERE Note LIKE 's*'"), config(Env.DEV, file.toString()),
        new TestCalls.NoSecrets(), ctx());
    assertThat(access.table().rows().toString()).isEqualTo("[[\"open\",\"second!\"]]");
  }

  @Test
  void aReadIsRefusedBeforeUcanaccessSeesItAndHsqldbRefusesAChangeItself() throws Exception {
    Classification c = connector.classify(sql(AccessConnector.QUERY, "SELECT LOAD_FILE('/etc/hosts') FROM Orders"),
        config(Env.DEV, file.toString()), policy(Env.DEV));
    assertThat(c.kind()).isEqualTo(Classification.Kind.DENY);
    assertThatThrownBy(() -> connector.execute(sql(AccessConnector.QUERY, "UPDATE Orders SET Note = 'x'"),
        config(Env.DEV, file.toString()), new TestCalls.NoSecrets(), ctx())).isInstanceOf(ConnectorException.class)
        .hasMessageContaining("access_query only reads");
    // Past GateRook's check, the read connection still refuses: HSQLDB's read-only transaction.
    try (Connection conn = AccessEngine.read(file, workDir); Statement st = conn.createStatement()) {
      assertThatThrownBy(() -> st.executeUpdate("UPDATE Orders SET Note = 'x' WHERE ID = 1"))
          .isInstanceOf(SQLException.class).hasMessageContaining("read-only");
    }
    String before = TestCalls.sha256(file);
    ToolResult after = connector.execute(sql(AccessConnector.QUERY, "SELECT Note FROM Orders WHERE ID = 1"),
        config(Env.DEV, file.toString()), new TestCalls.NoSecrets(), ctx());
    assertThat(after.table().rows().toString()).isEqualTo("[[\"first\"]]");
    assertThat(TestCalls.sha256(file)).isEqualTo(before);
  }

  @Test
  void anApprovedChangeIsWrittenToTheFileAndTheNextReadSeesIt() throws Exception {
    String insert = "INSERT INTO Orders (Note, Total) VALUES ('third', 5)";
    assertThatThrownBy(() -> connector.execute(sql(AccessConnector.EXECUTE, insert), config(Env.DEV, file.toString()),
        new TestCalls.NoSecrets(), ctx())).hasMessageContaining("Not approved");
    // A read first, so UCanAccess keeps the read-only copy the change must not use.
    connector.execute(sql(AccessConnector.QUERY, "SELECT COUNT(*) FROM Orders"), config(Env.DEV, file.toString()),
        new TestCalls.NoSecrets(), ctx());
    JsonNode out = connector.execute(sql(AccessConnector.EXECUTE, insert), config(Env.DEV, file.toString()),
        new TestCalls.NoSecrets(), approved(insert)).structuredContent();
    assertThat(out.path("statement").asString()).isEqualTo("INSERT");
    assertThat(out.path("target").asString()).isEqualTo("Orders");
    assertThat(out.path("rowsChanged").asLong()).isEqualTo(1);
    ToolResult rows = connector.execute(sql(AccessConnector.QUERY, "SELECT Note FROM Orders ORDER BY ID"),
        config(Env.DEV, file.toString()), new TestCalls.NoSecrets(), ctx());
    assertThat(rows.table().rows().toString()).isEqualTo("[[\"first\"],[\"second\"],[\"third\"]]");
    // Another program reads the change from the file itself.
    try (io.github.spannm.jackcess.Database db = io.github.spannm.jackcess.DatabaseBuilder.open(file.toFile())) {
      assertThat(db.getTable("Orders").getRowCount()).isEqualTo(3);
    }
    ToolResult refused = connector.execute(sql(AccessConnector.EXECUTE, "INSERT INTO Nope (A) VALUES (1)"),
        config(Env.DEV, file.toString()), new TestCalls.NoSecrets(), approved("INSERT INTO Nope (A) VALUES (1)"));
    assertThat(refused.isError()).isTrue();
    assertThat(text(refused)).startsWith("UCanAccess refused the change, so nothing changed:");
    ActionPreview preview = connector.describeAction(sql(AccessConnector.EXECUTE, "DROP TABLE [Order Lines]"),
        config(Env.DEV, file.toString()));
    assertThat(preview.warnings()).anySatisfy(w -> assertThat(w).startsWith("DROP TABLE can't be undone"));
  }

  @Test
  void aTestSaysWhatTheFileIsAndWhetherGateRookMayWriteIt() {
    TestResult t = connector.test(config(Env.DEV, file.toString()), new TestCalls.NoSecrets(), ctx());
    assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
    assertThat(t.serverVersion()).isEqualTo("Access 2010");
    assertThat(t.details().path("tables").asInt()).isEqualTo(2);
    assertThat(t.permissions().level()).isEqualTo(TestResult.Level.WRITE);
    assertThat(t.hints()).extracting(TestResult.Hint::code).contains("access.file-writable");
  }

  @Test
  void onlyAnAccessFileOfThePersonsOutsideGateRooksFolderOpens() throws Exception {
    Path text = Files.writeString(temp.resolve("notes.accdb"), "not an access file at all, just text");
    assertThat(connector.test(config(Env.DEV, text.toString()), new TestCalls.NoSecrets(), ctx()).error().message())
        .endsWith("isn't an Access database (.accdb or .mdb).");
    Path empty = Files.createFile(temp.resolve("empty.mdb"));
    assertThat(connector.test(config(Env.DEV, empty.toString()), new TestCalls.NoSecrets(), ctx()).error().message())
        .endsWith("is empty: it isn't an Access database.");
    assertThat(connector.test(config(Env.DEV, file + ";jackcessOpener=x.Y"), new TestCalls.NoSecrets(), ctx())
        .error().message()).contains("a semicolon");
    assertThat(connector.test(config(Env.DEV, "relative/orders.accdb"), new TestCalls.NoSecrets(), ctx()).error()
        .message()).contains("absolute path");
    Path inside = TestCalls.database(home.resolve("orders.accdb"));
    assertThat(connector.test(config(Env.DEV, inside.toString()), new TestCalls.NoSecrets(), ctx()).error().message())
        .isEqualTo("GateRook's own files can't be the database of a connection.");
  }
}
