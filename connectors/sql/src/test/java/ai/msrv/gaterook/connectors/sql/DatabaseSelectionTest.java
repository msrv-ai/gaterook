package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolCall;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class DatabaseSelectionTest {

  static ConnectionConfig selected(String type, String... databases) {
    ConnectionConfig cfg = Fixtures.config(type, Env.DEV, "127.0.0.1", 1, null, "ro", null, null);
    ObjectNode connection = (ObjectNode) cfg.config().path("connection");
    var selected = connection.putArray("databases");
    for (String database : databases) selected.add(database);
    return cfg;
  }

  @Test
  void singleDatabaseIsAutomaticAndMultipleRequireAnExactChoice() throws Exception {
    assertThat(DatabaseSelection.resolve(selected("postgres", "app"), null)).isEqualTo("app");
    ConnectionConfig cfg = selected("postgres", "app", "archive");
    assertThat(DatabaseSelection.resolve(cfg, "archive")).isEqualTo("archive");
    assertThatThrownBy(() -> DatabaseSelection.resolve(cfg, null)).hasMessageContaining("Choose a database");
    assertThatThrownBy(() -> DatabaseSelection.resolve(cfg, "other"))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    assertThatThrownBy(() -> DatabaseSelection.resolve(selected("mysql"), null))
        .hasMessageContaining("Select at least one");
  }

  @Test
  void legacyDefaultIsPreservedAndCannotBeOverriddenByAnArgument() throws Exception {
    ConnectionConfig cfg = Fixtures.config("postgres", Env.DEV, "localhost", 1, "app", "ro", null, null);
    assertThat(DatabaseSelection.resolve(cfg, null)).isEqualTo("app");
    assertThat(DatabaseSelection.resolve(cfg, "app")).isEqualTo("app");
    assertThatThrownBy(() -> DatabaseSelection.resolve(cfg, "other")).hasMessageContaining("not configured");
  }

  @Test
  void mysqlSelectionIntersectsTheSchemaAndTableAllowlistsWithoutTreatingNamesAsGlobs() {
    Allowlist allowlist = Allowlist.of(List.of("*.orders"), List.of("app*"), true, List.of("app", "logs"));
    assertThat(allowlist.allows(IdentifierRules.table("app.orders", true, "app"))).isTrue();
    assertThat(allowlist.allows(IdentifierRules.table("logs.orders", true, "app"))).isFalse();
    assertThat(allowlist.allows(IdentifierRules.table("app2.orders", true, "app"))).isFalse();
    assertThat(allowlist.allows(IdentifierRules.table("app.customers", true, "app"))).isFalse();
    assertThat(allowlist.mayAllowSchema("app2")).isFalse();
    assertThat(Allowlist.of(List.of(), List.of(), false, List.of("app"))
        .allows(IdentifierRules.table("reporting.orders", false, "public"))).isTrue();
  }

  @Test
  void classifyAndDirectExecutionRejectCrossDatabaseReadsBeforeConnecting() throws Exception {
    try (JdbcSqlConnector connector = new JdbcSqlConnector(new MySqlDialect())) {
      ConnectionConfig cfg = selected("mysql", "app", "archive");
      ToolCall allowed = Fixtures.call("sql_query", Fixtures.object().put("database", "app")
          .put("sql", "SELECT * FROM archive.orders"));
      ToolCall outside = Fixtures.call("sql_query", Fixtures.object().put("database", "app")
          .put("sql", "SELECT * FROM other.orders"));
      assertThat(connector.classify(allowed, cfg, Fixtures.policy(Env.DEV, null)).kind())
          .isEqualTo(Classification.Kind.READ);
      assertThat(connector.classify(outside, cfg, Fixtures.policy(Env.DEV, null)).kind())
          .isEqualTo(Classification.Kind.DENY);
      assertThatThrownBy(() -> connector.execute(outside, cfg, Fixtures.Secrets.none(),
          Fixtures.Context.of(Env.DEV, null))).hasMessageContaining("outside the selected database scope");
      assertThat(connector.classify(Fixtures.sql("sql_query", "SELECT 1"), cfg,
          Fixtures.policy(Env.DEV, null)).kind()).isEqualTo(Classification.Kind.DENY);
    }
  }

  @Test
  void approvalsAndTemplatesBindTheSelectedDatabase() {
    try (JdbcSqlConnector connector = new JdbcSqlConnector(new PostgresDialect())) {
      ConnectionConfig cfg = selected("postgres", "app", "archive");
      var preview = connector.describeAction(Fixtures.call("sql_execute", Fixtures.object()
          .put("database", "archive").put("sql", "DELETE FROM orders")), cfg);
      assertThat(preview.payload().path("database").asString()).isEqualTo("archive");
      assertThat(preview.target()).endsWith("/archive");
      assertThatThrownBy(() -> connector.execute(Fixtures.call("sql_execute", Fixtures.object()
          .put("database", "archive").put("sql", "DELETE FROM orders")), cfg, Fixtures.Secrets.none(),
          Fixtures.Context.of(Env.DEV, null).approved(null)))
          .hasMessageContaining("approved action does not specify a database");
      var kind = connector.templateKinds().getFirst();
      ObjectNode definition = Fixtures.object().put("name", "saved_query").put("sql", "SELECT 1");
      assertThat(kind.compile(definition, cfg).errors()).anyMatch(s -> s.contains("Choose a database"));
      definition.put("database", "archive");
      var compiled = kind.compile(definition, cfg);
      assertThat(compiled.errors()).isEmpty();
      assertThat(compiled.tools().getFirst().binding().path("database").asString()).isEqualTo("archive");
      assertThat(kind.preview(definition, Fixtures.object(), cfg).target()).endsWith("/archive");
      definition.put("database", "outside");
      assertThat(kind.compile(definition, cfg).errors()).anyMatch(s -> s.contains("not selected"));
    }
  }

  @Test
  void poolIdentityIncludesDatabaseAndConfigurationChangesInvalidateEveryDatabase() throws Exception {
    try (SqlPools pools = new SqlPools(new PostgresDialect())) {
      ConnectionConfig cfg = selected("postgres", "app", "archive");
      ConnectionSettings settings = ConnectionSettings.of(cfg, false);
      Path certs = Path.of("target", "selection-certificates");
      var first = pools.pool(cfg, settings, Credentials.resolve(cfg, Fixtures.Secrets.none()), certs);
      var second = pools.pool(cfg, settings.withDatabase("archive", false),
          Credentials.resolve(cfg, Fixtures.Secrets.none()), certs);
      assertThat(first).isNotSameAs(second);
      assertThat(pools.current(cfg.id(), "app")).isSameAs(first);
      assertThat(pools.current(cfg.id(), "archive")).isSameAs(second);
      var updated = pools.pool(Fixtures.withVersion(cfg, 2), settings,
          Credentials.resolve(cfg, Fixtures.Secrets.none()), certs);
      assertThat(updated).isNotSameAs(first);
      assertThat(pools.current(cfg.id(), "archive")).isNull();
      pools.drop(cfg.id());
      assertThat(pools.current(cfg.id())).isNull();
    }
  }

  @Test
  void jdbcDatabaseNamesCannotInjectUrlOptions() {
    ConnectionSettings settings = ConnectionSettings.of(selected("postgres", "app?sslmode=disable&x=y"), false);
    assertThat(new PostgresDialect().url(settings)).endsWith("/app%3Fsslmode%3Ddisable%26x%3Dy");
    assertThat(new MySqlDialect().url(settings)).endsWith("/app%3Fsslmode%3Ddisable%26x%3Dy");
  }
}
