package com.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

/**
 * Help for the SQL forms: localhost for a server listening on this computer, SQLite files next to the typed path, and
 * once the draft signs in, what the user may use. Servers' listings run against real databases in the ITs; here the
 * fake PostgreSQL refuses every login, so what's checked is what a failure says and which databases are tried.
 */
class SqlSuggestionsTest {

  private static final Path WORK = Path.of("target", "suggest-work").toAbsolutePath();

  @TempDir
  Path dir;

  private static ConnectionConfig server(String type, String host, Integer port) {
    ObjectNode c = Fixtures.object();
    ObjectNode connection = c.putObject("connection");
    if (host != null) {
      connection.put("host", host);
    }
    if (port != null) {
      connection.put("port", port);
    }
    c.putObject("credentials").put("user", "ro");
    return new ConnectionConfig("draft-" + type, "draft", type, Env.DEV, 0, c, Set.of(ConnectionSettings.PASSWORD),
        null);
  }

  private static int closedPort() throws IOException {
    try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      return s.getLocalPort();
    }
  }

  // ── This computer ─────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aServerListeningOnThisComputerIsOfferedAsLocalhostNeverFilledIn() throws IOException {
    try (ServerSocket listening = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      Suggestions.Builder out = Suggestions.builder();

      SqlSuggestions.server(server("postgres", null, null), "A PostgreSQL server", listening.getLocalPort(), out);

      Suggestions.Field host = out.build().fields().get(SqlSuggestions.HOST);
      assertThat(host.value()).isNull();
      assertThat(host.source()).isEqualTo("this computer");
      assertThat(host.options()).singleElement().satisfies(o -> {
        assertThat(o.value()).isEqualTo("localhost");
        assertThat(o.detail()).isEqualTo("A PostgreSQL server answers on port " + listening.getLocalPort());
      });
      assertThat(out.build().fields()).doesNotContainKey(SqlSuggestions.PORT);
    }
  }

  @Test
  void theUsualPortIsOfferedWhenTheTypedOneOfThisComputerDoesntAnswer() throws IOException {
    int typed = closedPort();
    try (ServerSocket listening = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      Suggestions.Builder out = Suggestions.builder();

      SqlSuggestions.server(server("mysql", "localhost", typed), "A MySQL or MariaDB server",
          listening.getLocalPort(), out);

      assertThat(out.build().fields().get(SqlSuggestions.PORT).options()).extracting(Option::value)
          .containsExactly(Integer.toString(listening.getLocalPort()));
    }
  }

  @Test
  void nothingIsOfferedWhenNothingListens() throws IOException {
    Suggestions.Builder out = Suggestions.builder();

    SqlSuggestions.server(server("postgres", "localhost", 5433), "A PostgreSQL server", closedPort(), out);

    assertThat(out.build().isEmpty()).isTrue();
  }

  @Test
  void eachServerTypeAnswersLocallyWithoutSigningIn() {
    for (SqlDialect dialect : List.of(new PostgresDialect(), new MySqlDialect(), new SqlServerDialect(),
        new OracleDialect())) {
      try (JdbcSqlConnector connector = new JdbcSqlConnector(dialect)) {
        Suggestions s = connector.suggest(server(dialect.type(), null, null), Fixtures.Secrets.none(),
            Fixtures.Context.of(Env.DEV, WORK), Suggestions.Scope.LOCAL);

        // Whatever this computer runs: localhost is at most offered, never filled in, and nothing signs in.
        assertThat(s.fields().keySet()).as(dialect.type()).isSubsetOf(SqlSuggestions.HOST, SqlSuggestions.PORT);
        s.fields().values().forEach(f -> assertThat(f.value()).as(dialect.type()).isNull());
        assertThat(s.notes()).as(dialect.type()).isEmpty();
      }
    }
  }

  @Test
  void loopbackNames() {
    assertThat(SqlSuggestions.loopback("localhost")).isTrue();
    assertThat(SqlSuggestions.loopback("127.0.0.1")).isTrue();
    assertThat(SqlSuggestions.loopback("[::1]")).isTrue();
    assertThat(SqlSuggestions.loopback("db.dev.internal")).isFalse();
    assertThat(SqlSuggestions.loopback("10.0.0.5")).isFalse();
  }

  // ── SQLite ────────────────────────────────────────────────────────────────────────────────────────────────────

  private Path database(String name) throws SQLException {
    Path file = dir.resolve(name);
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement st = c.createStatement()) {
      st.executeUpdate("CREATE TABLE customers (id INTEGER PRIMARY KEY, email TEXT)");
      st.executeUpdate("CREATE TABLE orders (id INTEGER PRIMARY KEY, customer_id INTEGER)");
      st.executeUpdate("CREATE VIEW big_orders AS SELECT * FROM orders WHERE id > 100");
    }
    return file;
  }

  private static ConnectionConfig sqlite(String file) {
    ObjectNode c = Fixtures.object();
    c.putObject("connection").put("file", file);
    return new ConnectionConfig("draft-sqlite", "draft", "sqlite", Env.DEV, 0, c, Set.of(), null);
  }

  private static Suggestions suggest(ConnectionConfig cfg, Suggestions.Scope scope) {
    try (JdbcSqlConnector connector = new JdbcSqlConnector(new SqliteDialect())) {
      return connector.suggest(cfg, Fixtures.Secrets.none(), Fixtures.Context.of(Env.DEV, WORK), scope);
    }
  }

  @Test
  void theDatabaseFilesOfTheTypedFolderAreOffered() throws Exception {
    Path shop = database("shop.sqlite3");
    database("test.db");
    Files.writeString(dir.resolve("notes.db"), "not a database");
    Files.writeString(dir.resolve("readme.txt"), "hello");

    Suggestions s = suggest(sqlite(dir.resolve("shop").toString()), Suggestions.Scope.LOCAL);

    Suggestions.Field file = s.fields().get(SqlSuggestions.FILE);
    assertThat(file.value()).isNull();
    assertThat(file.options()).extracting(Option::value)
        .containsExactly(SqlSuggestions.shown(shop), SqlSuggestions.shown(dir.resolve("test.db")));
    assertThat(s.notes()).singleElement().satisfies(n -> assertThat(n).endsWith("doesn't exist on this computer: "
        + "choose a database file of its folder."));

    // The folder itself: its files, no note.
    Suggestions folder = suggest(sqlite(dir.toString()), Suggestions.Scope.LOCAL);
    assertThat(folder.fields().get(SqlSuggestions.FILE).options()).hasSize(2);
    assertThat(folder.notes()).isEmpty();
  }

  @Test
  void aTypedFileThatIsntADatabaseIsANote() throws Exception {
    Path notes = Files.writeString(dir.resolve("notes.db"), "not a database");

    Suggestions s = suggest(sqlite(notes.toString()), Suggestions.Scope.LOCAL);

    assertThat(s.notes()).containsExactly(notes + " isn't a SQLite database.");
  }

  @Test
  void aPathThatIsntOneYetSaysNothing() {
    assertThat(suggest(sqlite("relative/shop.db"), Suggestions.Scope.LOCAL).isEmpty()).isTrue();
    assertThat(suggest(sqlite("  "), Suggestions.Scope.LOCAL).isEmpty()).isTrue();
  }

  @Test
  void signedInTheFileSaysWhatItHolds() throws Exception {
    Path shop = database("shop.sqlite3");

    Suggestions s = suggest(sqlite(shop.toString()), Suggestions.Scope.SERVICE);

    assertThat(s.notes()).containsExactly("shop.sqlite3 holds 2 tables and 1 view.");
    assertThat(s.fields()).isEmpty();
  }

  @Test
  void signedInAMissingFileIsANote() {
    Suggestions s = suggest(sqlite(dir.resolve("gone.db").toString()), Suggestions.Scope.SERVICE);

    assertThat(s.notes()).singleElement().satisfies(n -> assertThat(n).contains("doesn't exist"));
  }

  // ── Signed in to a server ─────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aServerThatRefusesTheLoginIsANoteWithoutOptions() throws Exception {
    try (FakePostgres fake = new FakePostgres(FakePostgres.Answer.PLAIN);
         JdbcSqlConnector connector = new JdbcSqlConnector(new PostgresDialect())) {
      ConnectionConfig cfg = Fixtures.config("postgres", Env.DEV, "127.0.0.1", fake.port(), "app", "ro", null, null);

      Suggestions s = connector.suggest(cfg, Fixtures.Secrets.password("wrong-pw-0001"),
          Fixtures.Context.of(Env.DEV, WORK), Suggestions.Scope.SERVICE);

      assertThat(s.fields()).isEmpty();
      assertThat(s.notes()).containsExactly("Password authentication failed for user ro.");
    }
  }

  @Test
  void withoutADatabaseAClosedDiscoveryDatabaseIsTriedAgainAsTemplate1() throws Exception {
    try (FakePostgres fake = new FakePostgres(FakePostgres.Answer.PLAIN);
         JdbcSqlConnector connector = new JdbcSqlConnector(new PostgresDialect())) {
      fake.refuse(startup -> "postgres".equals(startup.get("database"))
          ? new String[] {"3D000", "database \"postgres\" does not exist"}
          : new String[] {"28P01", "password authentication failed for user \"ro\""});
      ConnectionConfig cfg = Fixtures.config("postgres", Env.DEV, "127.0.0.1", fake.port(), null, "ro", null, null);

      Suggestions s = connector.suggest(cfg, Fixtures.Secrets.password("ro-pw-0001"),
          Fixtures.Context.of(Env.DEV, WORK), Suggestions.Scope.SERVICE);

      assertThat(fake.events()).filteredOn(e -> e.startsWith("startup")).containsExactly(
          "startup plain user=ro database=postgres", "startup plain user=ro database=template1");
      // template1 refused the login too: what the discovery database said is the answer.
      assertThat(s.notes()).containsExactly("Database postgres doesn't exist.");
      assertThat(s.fields()).isEmpty();
    }
  }

  @Test
  void aRefusedLoginIsNotRetriedOnAnotherDatabase() throws Exception {
    try (FakePostgres fake = new FakePostgres(FakePostgres.Answer.PLAIN);
         JdbcSqlConnector connector = new JdbcSqlConnector(new PostgresDialect())) {
      ConnectionConfig cfg = Fixtures.config("postgres", Env.DEV, "127.0.0.1", fake.port(), null, "ro", null, null);

      connector.suggest(cfg, Fixtures.Secrets.password("wrong-pw-0001"), Fixtures.Context.of(Env.DEV, WORK),
          Suggestions.Scope.SERVICE);

      assertThat(fake.events()).filteredOn(e -> e.startsWith("startup"))
          .containsExactly("startup plain user=ro database=postgres");
    }
  }

  @Test
  void everyServerTypeThatCantBeReachedSaysWhy() throws Exception {
    int closed = closedPort();
    for (SqlDialect dialect : List.of(new PostgresDialect(), new MySqlDialect(), new SqlServerDialect(),
        new OracleDialect())) {
      try (JdbcSqlConnector connector = new JdbcSqlConnector(dialect)) {
        ConnectionConfig cfg = Fixtures.config(dialect.type(), Env.DEV, "127.0.0.1", closed, "app", "ro", null,
            null);

        Suggestions s = connector.suggest(cfg, Fixtures.Secrets.password("ro-pw-0001"),
            Fixtures.Context.of(Env.DEV, WORK), Suggestions.Scope.SERVICE);

        assertThat(s.fields()).as(dialect.type()).isEmpty();
        assertThat(s.notes()).as(dialect.type()).hasSize(1);
      }
    }
  }

  @Test
  void onlyAMissingOrClosedDatabaseIsTriedAgain() {
    assertThat(SqlSuggestions.databaseRefused(new SQLException("database \"postgres\" does not exist", "3D000"),
        "postgres")).isTrue();
    assertThat(SqlSuggestions.databaseRefused(new SQLException("permission denied for database postgres", "42501"),
        "postgres")).isTrue();
    assertThat(SqlSuggestions.databaseRefused(new SQLException("no pg_hba.conf entry for host \"10.0.0.2\", user "
        + "\"ro\", database \"postgres\", no encryption", "28000"), "postgres")).isTrue();
    assertThat(SqlSuggestions.databaseRefused(new SQLException("role \"ro\" does not exist", "28000"), "postgres"))
        .isFalse();
    assertThat(SqlSuggestions.databaseRefused(new SQLException("password authentication failed", "28P01"),
        "postgres")).isFalse();
    assertThat(SqlSuggestions.databaseRefused(null, "postgres")).isFalse();
  }

  // ── What a listing offers ─────────────────────────────────────────────────────────────────────────────────────

  private static SqlCatalog.Listing listing(Map<String, int[]> counts) {
    return new SqlCatalog.Listing(List.copyOf(counts.keySet()), List.of(), counts, false);
  }

  @Test
  void theDefaultSchemaFilledInIsWhereTheTablesAre() {
    Map<String, int[]> onlyApp = new LinkedHashMap<>();
    onlyApp.put("app", new int[] {4, 1});
    onlyApp.put("public", new int[] {0, 0});
    assertThat(SqlSuggestions.preferredSchema("public", listing(onlyApp))).isEqualTo("app");

    Map<String, int[]> both = new LinkedHashMap<>(onlyApp);
    both.put("public", new int[] {2, 0});
    assertThat(SqlSuggestions.preferredSchema("public", listing(both))).isEqualTo("public");

    Map<String, int[]> empty = new LinkedHashMap<>();
    empty.put("public", new int[] {0, 0});
    assertThat(SqlSuggestions.preferredSchema("public", listing(empty))).isEqualTo("public");

    Map<String, int[]> several = new LinkedHashMap<>();
    several.put("sales", new int[] {3, 0});
    several.put("hr", new int[] {1, 0});
    assertThat(SqlSuggestions.preferredSchema("GW_READER", listing(several))).isNull();
    assertThat(SqlSuggestions.preferredSchema(null, listing(onlyApp))).isEqualTo("app");
  }

  @Test
  void schemasAreOfferedWithWhatTheUserMayRead() {
    Map<String, int[]> counts = new LinkedHashMap<>();
    counts.put("public", new int[] {4, 1});
    counts.put("Sales", new int[] {1, 0});
    counts.put("empty", new int[] {0, 0});

    List<Option> options = SqlSuggestions.schemaOptions(listing(counts),
        name -> IdentifierRules.quoteIfNeeded(name, SqlSyntax.POSTGRES));

    assertThat(options).extracting(Option::value).containsExactly("public", "\"Sales\"", "empty");
    assertThat(options).extracting(Option::detail).containsExactly("4 tables · 1 view", "1 table",
        "No tables you may read");
  }

  @Test
  void oracleSchemasAreNamedAsTheFormTakesThem() {
    assertThat(SqlSuggestions.oracleName("APP")).isEqualTo("APP");
    assertThat(SqlSuggestions.oracleName("GW$READER#1")).isEqualTo("GW$READER#1");
    assertThat(SqlSuggestions.oracleName("app_mixed")).isEqualTo("\"app_mixed\"");
    assertThat(SqlSuggestions.oracleName("A\"B")).isEqualTo("\"A\"\"B\"");
  }
}
