package ai.msrv.gaterook.connectors.duckdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectorException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The file rules of a DuckDB connection (docs/duckdb.md), as the built-in SQLite connector has them: a path the
 * person chose on this computer, a real DuckDB database outside GateRook's home, and data folders that hold neither
 * GateRook's home, the person's home nor the database.
 */
class DuckDbFilesTest {

  @TempDir
  Path temp;

  private Path home;
  private Path database;

  @BeforeEach
  void setUp() throws Exception {
    home = Files.createDirectories(temp.resolve("gaterook-home"));
    TestCalls.workDir(home);
    Files.writeString(home.resolve("gateway.db"), "SQLite format 3\0");
    database = TestCalls.database(temp.resolve("project").resolve("warehouse.duckdb"),
        "CREATE TABLE t (id INTEGER)");
  }

  private static String refusal(ThrowingCall call) {
    try {
      call.run();
    } catch (ConnectorException e) {
      return e.getMessage();
    }
    throw new AssertionError("not refused");
  }

  private interface ThrowingCall {
    void run() throws ConnectorException;
  }

  @Test
  void aPathIsAbsoluteLocalAndPlain() throws Exception {
    assertThat(refusal(() -> DuckDbFiles.path(" ", "database file"))).contains("Choose the database file");
    assertThat(refusal(() -> DuckDbFiles.path("warehouse.duckdb", "database file"))).contains("absolute path");
    assertThat(refusal(() -> DuckDbFiles.path("/data/x.duckdb?access_mode=READ_WRITE", "database file")))
        .contains("question mark");
    assertThat(refusal(() -> DuckDbFiles.path("/data/x.duckdb;session_init_sql_file=/tmp/x.sql", "database file")))
        .contains("semicolon");
    assertThat(refusal(() -> DuckDbFiles.path("/data/x\u0000.duckdb", "database file"))).contains("control");
    assertThat(refusal(() -> DuckDbFiles.path("//server/share/x.duckdb", "database file"))).contains("network");
    if (!DuckDbFiles.WINDOWS) {
      assertThat(refusal(() -> DuckDbFiles.path("\\\\server\\share\\x.duckdb", "database file")))
          .contains("backslash");
      assertThat(refusal(() -> DuckDbFiles.path("/data/a\\b.duckdb", "database file"))).contains("backslash");
    }
    assertThat(DuckDbFiles.path("~/projects/w.duckdb", "database file"))
        .isEqualTo(Path.of(System.getProperty("user.home"), "projects", "w.duckdb"));
    assertThat(DuckDbFiles.path("/data/./a/../w.duckdb ", "database file")).isEqualTo(Path.of("/data/w.duckdb"));
  }

  @Test
  void theDatabaseIsAnExistingDuckDbFile() throws Exception {
    assertThat(DuckDbFiles.database(database.toString(), home)).isEqualTo(database.toRealPath());
    assertThat(refusal(() -> DuckDbFiles.database(temp.resolve("nope.duckdb").toString(), home)))
        .contains("doesn't exist");
    assertThat(refusal(() -> DuckDbFiles.database(temp.resolve("project").toString(), home))).contains("isn't a file");
    Path text = Files.writeString(temp.resolve("notes.duckdb"), "hello, this is not a database");
    assertThat(refusal(() -> DuckDbFiles.database(text.toString(), home))).contains("isn't a DuckDB database");
    Path sqlite = Files.write(temp.resolve("app.sqlite3"), "SQLite format 3\0........".getBytes(
        StandardCharsets.US_ASCII));
    assertThat(refusal(() -> DuckDbFiles.database(sqlite.toString(), home))).contains("isn't a DuckDB database");
    Path empty = Files.createFile(temp.resolve("empty.duckdb"));
    assertThat(refusal(() -> DuckDbFiles.database(empty.toString(), home))).contains("is empty");
    // The magic bytes alone at byte 8, as DuckDB writes its main header.
    byte[] head = new byte[64];
    System.arraycopy("DUCK".getBytes(StandardCharsets.US_ASCII), 0, head, 8, 4);
    Path headerOnly = Files.write(temp.resolve("header.duckdb"), head);
    assertThat(DuckDbFiles.database(headerOnly.toString(), home)).isEqualTo(headerOnly.toRealPath());
  }

  @Test
  void gateRooksOwnFilesAreNeverTheDatabaseAlsoThroughALink() throws Exception {
    Path inside = TestCalls.database(home.resolve("plugin-data").resolve("duckdb").resolve("x.duckdb"),
        "CREATE TABLE t (id INTEGER)");
    assertThatThrownBy(() -> DuckDbFiles.database(inside.toString(), home)).isInstanceOf(ConnectorException.class)
        .hasMessageContaining("GateRook's own files")
        .satisfies(e -> assertThat(((ConnectorException) e).kind()).isEqualTo(ConnectorException.Kind.DENIED));
    Path link = Files.createSymbolicLink(temp.resolve("innocent.duckdb"), inside);
    assertThat(refusal(() -> DuckDbFiles.database(link.toString(), home))).contains("GateRook's own files");
    Path toGatewayDb = Files.createSymbolicLink(temp.resolve("gateway-link.duckdb"), home.resolve("gateway.db"));
    assertThat(refusal(() -> DuckDbFiles.database(toGatewayDb.toString(), home))).contains("GateRook's own files");
    // A link elsewhere is followed: DuckDB gets the real file that was checked.
    Path toDatabase = Files.createSymbolicLink(temp.resolve("w.duckdb"), database);
    assertThat(DuckDbFiles.database(toDatabase.toString(), home)).isEqualTo(database.toRealPath());
  }

  @Test
  void aLinkMayNotLeadToAPathTheDriverReadsAsSettings() throws Exception {
    // The driver would split a URL with a semicolon into settings, so the file is made elsewhere and moved there.
    TestCalls.database(temp.resolve("ab").resolve("x.duckdb"), "CREATE TABLE t (id INTEGER)");
    Path odd = Files.move(temp.resolve("ab"), temp.resolve("a;b")).resolve("x.duckdb");
    Path link = Files.createSymbolicLink(temp.resolve("plain.duckdb"), odd);
    assertThat(refusal(() -> DuckDbFiles.database(link.toString(), home))).contains("semicolon");
  }

  @Test
  void theHomeIsAboveThePluginsWorkFolder() {
    Path work = home.resolve("plugin-data").resolve("duckdb");
    assertThat(DuckDbFiles.home(work)).isEqualTo(home.toAbsolutePath().normalize());
    assertThat(DuckDbFiles.home(temp.resolve("elsewhere"))).isEqualTo(temp.resolve("elsewhere").toAbsolutePath());
    assertThat(DuckDbFiles.home(null)).isNull();
  }

  @Test
  void dataFoldersAreFoldersOfTheirOwn() throws Exception {
    Path data = Files.createDirectories(temp.resolve("data").resolve("sales"));
    Path real = database.toRealPath();
    List<DuckDbFiles.Folder> folders = DuckDbFiles.folders(List.of(data.toString()), home, real);
    assertThat(folders).hasSize(1);
    assertThat(folders.getFirst().configured()).isEqualTo(data);
    assertThat(folders.getFirst().real()).isEqualTo(data.toRealPath());
    assertThat(DuckDbFiles.prefixes(List.of(data))).containsExactly(data + "/");

    assertThat(refusal(() -> DuckDbFiles.folders(List.of(temp.resolve("nope").toString()), home, database)))
        .contains("doesn't exist");
    Path file = Files.writeString(temp.resolve("data").resolve("x.csv"), "a\n1\n");
    assertThat(refusal(() -> DuckDbFiles.folders(List.of(file.toString()), home, database))).contains("isn't a "
        + "folder");
    assertThat(refusal(() -> DuckDbFiles.folders(List.of(home.resolve("plugin-data").toString()), home, database)))
        .contains("GateRook's own folder");
    assertThat(refusal(() -> DuckDbFiles.folders(List.of(temp.toString()), home, real)))
        .contains("GateRook's own folder");
    assertThat(refusal(() -> DuckDbFiles.folders(List.of(database.getParent().toString()), home, real)))
        .contains("holds the database file");
    assertThat(refusal(() -> DuckDbFiles.folders(List.of(System.getProperty("user.home")), null, null)))
        .contains("home folder");
    assertThat(refusal(() -> DuckDbFiles.folders(List.of("/"), null, null))).containsAnyOf("root of a disk",
        "home folder");
    assertThat(refusal(() -> DuckDbFiles.folders(List.of(data + "/*"), home, database))).contains("*");
    Path link = Files.createSymbolicLink(temp.resolve("to-home"), home);
    assertThat(refusal(() -> DuckDbFiles.folders(List.of(link.toString()), home, database)))
        .contains("GateRook's own folder");
  }
}
