package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolCall;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import tools.jackson.databind.node.ObjectNode;

@Tag("mysql-it")
class MysqlDatabaseSelectionIT {
  @RegisterExtension
  static final LocalMysql MYSQL = LocalMysql.mysql();
  private static final Path WORK = Path.of("target", "mysql-selection-work").toAbsolutePath();
  private final JdbcSqlConnector connector = new JdbcSqlConnector(new MySqlDialect());

  @BeforeAll
  static void seed() throws Exception {
    MYSQL.exec("CREATE DATABASE selection_archive", "CREATE DATABASE selection_hidden",
        "CREATE TABLE selection_archive.selection_marker (value VARCHAR(20) NOT NULL)",
        "INSERT INTO selection_archive.selection_marker VALUES ('archive')",
        "GRANT SELECT ON selection_archive.* TO 'ro'@'%'");
  }

  @AfterEach
  void close() { connector.close(); }

  @Test
  void discoveryUsesTheLoginBeforeADatabaseIsSelectedAndHidesSystemDatabases() {
    ConnectionConfig cfg = config();
    ((ObjectNode) cfg.config().path("connection")).remove("databases");
    var result = connector.discoverDatabases(cfg, secrets(), ctx());
    assertThat(result.ok()).as(String.valueOf(result.error())).isTrue();
    assertThat(result.details().path("databases").toString()).isEqualTo("[\"app\",\"selection_archive\"]");
    assertThat(result.details().path("discoveryComplete").asBoolean()).isTrue();
  }

  @Test
  void selectedDatabasesRouteReadsAndBoundCatalogListings() throws Exception {
    ConnectionConfig cfg = config();
    for (String database : new String[] {"app", "selection_archive", "app"}) {
      var result = connector.execute(Fixtures.call("sql_query", Fixtures.object().put("database", database)
          .put("sql", "SELECT DATABASE()")), cfg, secrets(), ctx());
      assertThat(result.table().rows().get(0).get(0).asString()).isEqualTo(database);
    }
    var crossDatabase = connector.execute(Fixtures.call("sql_query", Fixtures.object().put("database", "app")
        .put("sql", "SELECT value FROM selection_archive.selection_marker")), cfg, secrets(), ctx());
    assertThat(crossDatabase.table().rows().get(0).get(0).asString()).isEqualTo("archive");
    ((ObjectNode) cfg.config()).putObject("advanced").putArray("schemaAllowlist").add("app");
    ToolCall list = Fixtures.call("sql_list_schemas", Fixtures.object().put("database", "app"));
    connector.classify(list, cfg, Fixtures.policy(Env.DEV, null));
    assertThat(connector.execute(list, cfg, secrets(), ctx()).structuredContent().path("schemas")
        .findValuesAsString("name")).containsExactly("app");
    assertThatThrownBy(() -> connector.execute(Fixtures.call("sql_query", Fixtures.object().put("database", "app")
        .put("sql", "SELECT * FROM selection_hidden.secret")), cfg, secrets(), ctx()))
        .hasMessageContaining("outside the selected database scope");
  }

  @Test
  void testConnectionChecksEverySelectedDatabase() {
    ConnectionConfig cfg = config();
    var success = connector.test(cfg, secrets(), ctx().timeout(Duration.ofSeconds(30)));
    assertThat(success.ok()).as(String.valueOf(success.error())).isTrue();
    assertThat(success.details().path("databases").has("selection_archive")).isTrue();
    ((ObjectNode) cfg.config().path("connection")).withArray("databases").add("selection_hidden");
    var failure = connector.test(cfg, secrets(), ctx().timeout(Duration.ofSeconds(30)));
    assertThat(failure.ok()).isFalse();
  }

  private static ConnectionConfig config() {
    ConnectionConfig cfg = Fixtures.config("mysql", Env.DEV, "127.0.0.1", MYSQL.port(), null, "ro", null, null);
    ((ObjectNode) cfg.config().path("connection")).putArray("databases").add("app").add("selection_archive");
    return cfg;
  }

  private static Fixtures.Secrets secrets() { return Fixtures.Secrets.password(LocalMysql.password("ro")); }
  private static Fixtures.Context ctx() { return Fixtures.Context.of(Env.DEV, WORK); }
}
