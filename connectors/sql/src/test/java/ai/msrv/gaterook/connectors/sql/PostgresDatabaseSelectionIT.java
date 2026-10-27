package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.ToolCall;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import tools.jackson.databind.node.ObjectNode;

class PostgresDatabaseSelectionIT {
  @RegisterExtension
  static final LocalPostgres PG = new LocalPostgres();
  private static final Path WORK = Path.of("target", "pg-selection-work").toAbsolutePath();
  private final JdbcSqlConnector connector = new JdbcSqlConnector(new PostgresDialect());

  @BeforeAll
  static void seed() throws Exception {
    PG.exec("CREATE DATABASE selection_archive", "CREATE DATABASE selection_hidden",
        "REVOKE CONNECT ON DATABASE selection_hidden FROM PUBLIC");
    try (Connection c = PG.connect("selection_archive", "postgres", LocalPostgres.SUPERUSER_PASSWORD);
        Statement st = c.createStatement()) {
      st.execute("CREATE TABLE selection_marker (value text NOT NULL)");
      st.execute("INSERT INTO selection_marker VALUES ('before')");
      st.execute("GRANT SELECT, UPDATE ON selection_marker TO rw");
    }
  }

  @AfterEach
  void close() {
    connector.close();
  }

  @Test
  void discoveryUsesBootstrapWithoutChoosingADatabaseAndFiltersConnectPermissions() {
    ConnectionConfig cfg = config();
    ((ObjectNode) cfg.config().path("connection")).remove("databases");
    var result = connector.discoverDatabases(cfg, secrets(), ctx());
    assertThat(result.ok()).as(String.valueOf(result.error())).isTrue();
    assertThat(result.details().path("databases").toString()).contains("app", "selection_archive", "postgres")
        .doesNotContain("selection_hidden", "template0", "template1");
    assertThat(result.details().path("discoveryComplete").asBoolean()).isTrue();
    ((ObjectNode) cfg.config()).putObject("advanced").put("discoveryDatabase", "app");
    assertThat(connector.discoverDatabases(cfg, secrets(), ctx()).ok()).isTrue();
  }

  @Test
  void readsAndIntrospectionUseIndependentPoolsForSelectedDatabases() throws Exception {
    ConnectionConfig cfg = config();
    for (String database : new String[] {"app", "selection_archive", "app"}) {
      var result = connector.execute(Fixtures.call("sql_query", Fixtures.object().put("database", database)
          .put("sql", "SELECT current_database()")), cfg, secrets(), ctx());
      assertThat(result.table().rows().get(0).get(0).asString()).isEqualTo(database);
    }
    assertThat(connector.pools().current(cfg.id(), "app"))
        .isNotSameAs(connector.pools().current(cfg.id(), "selection_archive"));
    ToolCall list = Fixtures.call("sql_list_schemas", Fixtures.object().put("database", "selection_archive"));
    connector.classify(list, cfg, Fixtures.policy(Env.DEV, null));
    assertThat(connector.execute(list, cfg, secrets(), ctx()).structuredContent().path("tables")
        .findValuesAsString("name")).containsExactly("selection_marker");
    var described = connector.execute(Fixtures.call("sql_describe_table", Fixtures.object()
        .put("database", "selection_archive").put("table", "selection_marker")), cfg, secrets(), ctx());
    assertThat(described.structuredContent().path("columns").findValuesAsString("name")).containsExactly("value");
    assertThatThrownBy(() -> connector.execute(Fixtures.call("sql_query", Fixtures.object()
        .put("database", "postgres").put("sql", "SELECT 1")), cfg, secrets(), ctx()))
        .hasMessageContaining("not selected");
  }

  @Test
  void prepareAndApprovedExecutionStayOnTheApprovedDatabaseWhenArgumentsChange() throws Exception {
    ConnectionConfig cfg = config();
    ToolCall change = Fixtures.call("sql_execute", Fixtures.object().put("database", "selection_archive")
        .put("sql", "UPDATE selection_marker SET value = 'approved'"));
    PreparedAction prepared = connector.prepare(change, cfg, secrets(), ctx());
    assertThat(prepared.outcome()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    assertThat(prepared.preview().target()).endsWith("/selection_archive");
    change.arguments().put("database", "app").put("sql", "DELETE FROM orders");
    var result = connector.execute(change, cfg, secrets(), ctx().approved(prepared.preview().payload()));
    assertThat(result.structuredContent().path("rowsAffected").asLong()).isEqualTo(1);
    try (Connection c = PG.connect("selection_archive", "postgres", LocalPostgres.SUPERUSER_PASSWORD);
        Statement st = c.createStatement(); var rows = st.executeQuery("SELECT value FROM selection_marker")) {
      rows.next();
      assertThat(rows.getString(1)).isEqualTo("approved");
    }
  }

  @Test
  void templatesBindDatabaseSeparatelyFromAParameterNamedDatabase() throws Exception {
    ConnectionConfig cfg = config();
    var compiled = connector.templateKinds().getFirst().compile(Fixtures.json("""
        {"name":"database_value","database":"selection_archive",
         "sql":"SELECT current_database(), :database",
         "params":[{"name":"database","type":"string"}]}
        """), cfg);
    assertThat(compiled.errors()).isEmpty();
    var tool = compiled.tools().getFirst();
    ToolCall call = new ToolCall("tc_db_template", tool.definition().name(), cfg.name(),
        Fixtures.object().put("database", "parameter value"),
        new ToolCall.TemplateBinding("tpl_selection", 1, tool.opKey(), tool.binding()));
    var row = connector.execute(call, cfg, secrets(), ctx()).table().rows().get(0);
    assertThat(row.get(0).asString()).isEqualTo("selection_archive");
    assertThat(row.get(1).asString()).isEqualTo("parameter value");
  }

  @Test
  void testConnectionChecksEverySelectedDatabase() {
    ConnectionConfig cfg = config();
    var success = connector.test(cfg, secrets(), ctx().timeout(Duration.ofSeconds(30)));
    assertThat(success.ok()).as(String.valueOf(success.error())).isTrue();
    assertThat(success.details().path("databases").has("selection_archive")).isTrue();
    ((ObjectNode) cfg.config().path("connection")).withArray("databases").add("selection_missing");
    var failure = connector.test(cfg, secrets(), ctx().timeout(Duration.ofSeconds(30)));
    assertThat(failure.ok()).isFalse();
    assertThat(failure.error().message()).contains("selection_missing");
  }

  private static ConnectionConfig config() {
    ConnectionConfig cfg = Fixtures.config("postgres", Env.DEV, "127.0.0.1", PG.port(), null, "rw", null, null);
    ((ObjectNode) cfg.config().path("connection")).putArray("databases").add("app").add("selection_archive");
    return cfg;
  }

  private static Fixtures.Secrets secrets() { return Fixtures.Secrets.password(LocalPostgres.password("rw")); }
  private static Fixtures.Context ctx() { return Fixtures.Context.of(Env.DEV, WORK); }
}
