package ai.msrv.gaterook.connectors.duckdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DuckDB's own enforcement, with the classifier bypassed (docs/duckdb.md): statements go straight to the instances
 * {@link DuckDbEngine} opens. A read-only instance refuses every write, external access is off except for the data
 * folders, no extension installs or loads, the settings are locked, and a runaway statement stops at its deadline or
 * when the call is cancelled. One thing DuckDB allows: a statement may write files in a data folder, which is why a
 * data folder can't hold the database and the classifier refuses COPY and EXPORT.
 */
class DuckDbEngineTest {

  @TempDir
  Path temp;

  private Path workDir;
  private Path database;
  private Path data;
  private Path outside;

  @BeforeEach
  void setUp() throws Exception {
    workDir = TestCalls.workDir(temp.resolve("gaterook-home"));
    database = TestCalls.database(temp.resolve("project").resolve("warehouse.duckdb").toAbsolutePath(),
        "CREATE TABLE orders (id INTEGER PRIMARY KEY, total DECIMAL(10, 2))",
        "INSERT INTO orders VALUES (1, 10.50), (2, 99.00)").toRealPath();
    data = Files.createDirectories(temp.resolve("data")).toRealPath();
    Files.writeString(data.resolve("sales.csv"), "id,amount\n1,5\n2,7\n");
    outside = Files.createDirectories(temp.resolve("outside")).toRealPath();
    Files.writeString(outside.resolve("secret.csv"), "token\nnot-for-agents\n");
  }

  private DuckDbEngine.Spec readOnly(Path... folders) {
    return new DuckDbEngine.Spec(database, false, prefixes(folders), 256, 2);
  }

  private DuckDbEngine.Spec readWrite(Path... folders) {
    return new DuckDbEngine.Spec(database, true, prefixes(folders), 256, 2);
  }

  private static List<String> prefixes(Path... folders) {
    return DuckDbFiles.prefixes(List.of(folders));
  }

  private TestCalls.Context context() {
    return new TestCalls.Context(workDir, false);
  }

  /** What DuckDB says to {@code sql} on an instance of {@code spec}: its first value, or its error. */
  private String run(DuckDbEngine.Spec spec, String sql) throws Exception {
    try {
      return DuckDbEngine.run(spec, context(), 10_000, (c, call) -> {
        try (Statement st = c.createStatement()) {
          call.running(st);
          if (!st.execute(sql)) {
            return "updated " + st.getUpdateCount();
          }
          try (ResultSet rs = st.getResultSet()) {
            return rs.next() ? String.valueOf(rs.getObject(1)) : "no rows";
          }
        }
      });
    } catch (DuckDbEngine.Failure f) {
      return "refused: " + (f.sql == null ? f.getMessage() : f.sql.getMessage());
    }
  }

  private String sha256(Path file) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
  }

  @Test
  void aReadOnlyInstanceRefusesEveryWrite() throws Exception {
    String before = sha256(database);
    for (String write : List.of("INSERT INTO orders VALUES (3, 1)", "UPDATE orders SET total = 0",
        "DELETE FROM orders", "CREATE TABLE x (a INT)", "DROP TABLE orders", "ALTER TABLE orders ADD COLUMN y INT",
        "CREATE VIEW v AS SELECT 1", "CREATE SEQUENCE s", "COMMENT ON TABLE orders IS 'x'",
        "WITH x AS (DELETE FROM orders RETURNING *) SELECT * FROM x")) {
      assertThat(run(readOnly(), write)).as(write).startsWith("refused:").contains("read-only");
    }
    assertThat(run(readOnly(), "SELECT count(*) FROM orders")).isEqualTo("2");
    assertThat(sha256(database)).isEqualTo(before);
    assertThat(Files.exists(Path.of(database + ".wal"))).isFalse();
  }

  @Test
  void externalAccessIsOffAtTheEngine() throws Exception {
    Path other = TestCalls.database(temp.resolve("other.duckdb"), "CREATE TABLE secrets (s VARCHAR)");
    assertThat(run(readOnly(), "ATTACH '" + other + "' AS o")).contains("Permission Error");
    assertThat(run(readOnly(), "ATTACH ':memory:' AS m")).startsWith("refused:");
    assertThat(run(readOnly(), "COPY orders TO '" + outside.resolve("dump.csv") + "'")).contains("Permission Error");
    assertThat(Files.exists(outside.resolve("dump.csv"))).isFalse();
    assertThat(run(readOnly(), "EXPORT DATABASE '" + outside.resolve("export") + "'")).startsWith("refused:");
    assertThat(Files.exists(outside.resolve("export"))).isFalse();
    String secret = outside.resolve("secret.csv").toString();
    for (String read : List.of("SELECT * FROM read_csv('" + secret + "')", "SELECT * FROM '" + secret + "'",
        "SELECT * FROM \"" + secret + "\"", "SELECT content FROM read_text('" + secret + "')",
        "SELECT content FROM read_blob('" + secret + "')", "SELECT * FROM glob('" + outside + "/*')",
        "SELECT * FROM sniff_csv('" + secret + "')", "SELECT * FROM read_csv('" + data + "/sales.csv')")) {
      assertThat(run(readOnly(), read)).as(read).contains("Permission Error");
    }
  }

  @Test
  void extensionsNeitherInstallNorLoad() throws Exception {
    assertThat(run(readOnly(), "INSTALL httpfs")).startsWith("refused:");
    assertThat(run(readOnly(), "FORCE INSTALL httpfs")).startsWith("refused:");
    assertThat(run(readOnly(), "LOAD httpfs")).contains("Loading external extensions is disabled");
    assertThat(run(readOnly(), "LOAD '" + outside.resolve("evil.duckdb_extension") + "'")).startsWith("refused:");
    assertThat(run(readOnly(), "SELECT current_setting('autoinstall_known_extensions')")).isEqualTo("false");
    assertThat(run(readOnly(), "SELECT current_setting('autoload_known_extensions')")).isEqualTo("false");
    assertThat(run(readOnly(), "SELECT current_setting('allow_community_extensions')")).isEqualTo("false");
    assertThat(run(readOnly(), "SELECT current_setting('allow_persistent_secrets')")).isEqualTo("false");
  }

  @Test
  void theSettingsAreLocked() throws Exception {
    for (String change : List.of("SET enable_external_access = true", "SET memory_limit = '64GB'",
        "SET threads = 64", "SET allowed_directories = ['/']", "RESET enable_external_access",
        "PRAGMA enable_profiling", "SET lock_configuration = false", "SET autoload_known_extensions = true")) {
      assertThat(run(readOnly(), change)).as(change).startsWith("refused:").containsAnyOf(
          "configuration has been locked", "Cannot change");
    }
    assertThat(run(readOnly(), "SELECT current_setting('lock_configuration')")).isEqualTo("true");
    assertThat(run(readOnly(), "SELECT current_setting('enable_external_access')")).isEqualTo("false");
    assertThat(run(readOnly(), "SELECT current_setting('access_mode')")).isEqualTo("read_only");
    assertThat(run(readOnly(), "SELECT current_setting('threads')")).isEqualTo("2");
    assertThat(run(readOnly(), "SELECT current_setting('temp_directory')")).isEmpty();
  }

  @Test
  void anInstanceWhoseSettingsDifferIsRefused() throws Exception {
    Properties plain = new Properties();
    plain.setProperty("jdbc_instance_cache", "false");
    plain.setProperty("access_mode", "READ_ONLY");
    try (Connection c = new org.duckdb.DuckDBDriver().connect("jdbc:duckdb:" + database, plain)) {
      assertThatThrownBy(() -> DuckDbEngine.verify(c, readOnly())).isInstanceOf(SQLException.class)
          .hasMessageContaining("enable_external_access").hasMessageContaining("lock_configuration");
    }
  }

  @Test
  void dataFoldersOpenThoseFoldersOnly() throws Exception {
    assertThat(run(readOnly(data), "SELECT sum(amount) FROM read_csv('" + data.resolve("sales.csv") + "')"))
        .isEqualTo("12");
    assertThat(run(readOnly(data), "SELECT count(*) FROM '" + data + "/*.csv'")).isEqualTo("2");
    assertThat(run(readOnly(data), "SELECT * FROM read_csv('" + outside.resolve("secret.csv") + "')"))
        .contains("Permission Error");
    assertThat(run(readOnly(data), "SELECT * FROM read_csv('" + data + "/../outside/secret.csv')"))
        .contains("Permission Error");
    // A link inside a data folder that leads out of it: DuckDB resolves it and refuses.
    Files.createSymbolicLink(data.resolve("escape"), outside);
    assertThat(run(readOnly(data), "SELECT * FROM read_csv('" + data.resolve("escape").resolve("secret.csv") + "')"))
        .contains("Permission Error");
    assertThat(run(readOnly(data), "SELECT unnest(current_setting('allowed_directories'))"))
        .isEqualTo(data + "/");
  }

  @Test
  void duckDbItselfWouldWriteInADataFolderSoAFolderMayNotHoldTheDatabase() throws Exception {
    // DuckDB's allowed directories admit writes too, read-only instance or not.
    assertThat(run(readOnly(data), "COPY (SELECT 1 AS x) TO '" + data.resolve("written.csv") + "'"))
        .isEqualTo("updated 1");
    assertThat(Files.exists(data.resolve("written.csv"))).isTrue();
    // So the database can't sit in one: DuckDB would overwrite it on a statement the classifier missed.
    assertThatThrownBy(() -> DuckDbFiles.folders(List.of(database.getParent().toString()), null, database))
        .hasMessageContaining("holds the database file");
    assertThat(DuckSql.read("COPY (SELECT 1) TO '" + data.resolve("x.csv") + "'", prefixes(data)).ok()).isFalse();
  }

  @Test
  void aReadWriteInstanceKeepsTheSameLocks() throws Exception {
    assertThat(run(readWrite(), "INSERT INTO orders VALUES (3, 1.00)")).isEqualTo("updated 1");
    assertThat(run(readOnly(), "SELECT count(*) FROM orders")).isEqualTo("3");
    assertThat(run(readWrite(), "COPY orders TO '" + outside.resolve("dump.csv") + "'")).contains("Permission Error");
    assertThat(run(readWrite(), "ATTACH '" + outside.resolve("new.duckdb") + "' AS n")).contains("Permission Error");
    assertThat(Files.exists(outside.resolve("new.duckdb"))).isFalse();
    assertThat(run(readWrite(), "INSTALL httpfs")).startsWith("refused:");
    assertThat(run(readWrite(), "SET enable_external_access = true")).contains("locked");
    assertThat(run(readWrite(), "SELECT current_setting('access_mode')")).isEqualTo("read_write");
  }

  @Test
  void aRunawayRecursiveQueryStopsAtItsDeadline() throws Exception {
    long started = System.nanoTime();
    assertThatThrownBy(() -> DuckDbEngine.run(readOnly(), context(), 1_000, (c, call) -> {
      try (Statement st = c.createStatement()) {
        call.running(st);
        st.execute("WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r) SELECT count(*) FROM r");
        return "finished";
      }
    })).isInstanceOfSatisfying(DuckDbEngine.Failure.class, f -> {
      assertThat(f.stopped).isTrue();
      assertThat(f.deadline).isTrue();
      assertThat(f.stillRunning).isFalse();
      assertThat((Object) f.sql).isNotNull();
      assertThat(f.sql.getMessage()).contains("INTERRUPT");
    });
    long ms = (System.nanoTime() - started) / 1_000_000;
    assertThat(ms).isBetween(900L, 3_000L);
    // The stopped instance closed and gave the file back: the next call reads at once.
    assertThat(run(readOnly(), "SELECT count(*) FROM orders")).isEqualTo("2");
  }

  @Test
  void aCancelledCallStopsItsStatement() throws Exception {
    TestCalls.Context ctx = context();
    CompletableFuture<Void> cancel = CompletableFuture.runAsync(() -> {
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      ctx.cancel();
    });
    long started = System.nanoTime();
    assertThatThrownBy(() -> DuckDbEngine.run(readOnly(), ctx, 20_000, (c, call) -> {
      try (Statement st = c.createStatement()) {
        call.running(st);
        st.execute("SELECT count(*) FROM range(100000000) a, range(100000) b WHERE a.range + b.range = -1");
        return "finished";
      }
    })).isInstanceOfSatisfying(DuckDbEngine.Failure.class, f -> {
      assertThat(f.stopped).isTrue();
      assertThat(f.deadline).isFalse();
    });
    cancel.join();
    assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(5_000L);
  }

  @Test
  void theMemoryLimitStopsAHungryQuery() throws Exception {
    DuckDbEngine.Spec small = new DuckDbEngine.Spec(database, false, List.of(), 64, 1);
    assertThat(run(small, "SELECT max(len(l)) FROM (SELECT range % 100 AS g, list(range) AS l FROM range(50000000) "
        + "GROUP BY g)")).contains("Out of Memory");
  }

  @Test
  void aChangeWaitsForAReadOfTheSameFile() throws Exception {
    // The read holds the file until its deadline stops it; DuckDB would refuse a read-write instance meanwhile.
    CompletableFuture<String> read = CompletableFuture.supplyAsync(() -> {
      try {
        return DuckDbEngine.run(readOnly(), context(), 2_000, (c, call) -> {
          try (Statement st = c.createStatement()) {
            call.running(st);
            st.execute("WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r) SELECT count(*) FROM r");
            return "finished";
          }
        });
      } catch (DuckDbEngine.Failure f) {
        return f.deadline ? "stopped at the deadline" : f.getMessage();
      } catch (Exception e) {
        return e.toString();
      }
    });
    Thread.sleep(500);
    long started = System.nanoTime();
    String change = DuckDbEngine.run(readWrite(), context(), 10_000, (c, call) -> {
      try (Statement st = c.createStatement()) {
        call.running(st);
        return "updated " + st.executeUpdate("INSERT INTO orders VALUES (4, 2.00)");
      }
    });
    assertThat(change).isEqualTo("updated 1");
    assertThat(read.get()).isEqualTo("stopped at the deadline");
    assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isGreaterThan(1_000L);
    assertThat(run(readOnly(), "SELECT count(*) FROM orders")).isEqualTo("3");
  }

  @Test
  void theLibraryOfThisSystemIsNamedAsTheDriverNamesIt() {
    assertThat(DuckDbEngine.libraryName()).startsWith("libduckdb_java.so_");
    // The test classpath has DuckDB's own JAR, with the libraries of every system DuckDB builds for.
    assertThat(DuckDbEngine.missingLibrary()).isNull();
    assertThat(DuckDbEngine.listLiteral(List.of("/a/", "/b'c\\d/"))).isEqualTo("['/a/', '/b\\'c\\\\d/']");
  }
}
