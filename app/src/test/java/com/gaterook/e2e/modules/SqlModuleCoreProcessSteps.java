package com.gaterook.e2e.modules;

import static com.gaterook.e2e.modules.SqlDialectFixture.arguments;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.gaterook.e2e.ConnectorDelivery;
import com.gaterook.e2e.TestServices;
import com.gaterook.e2e.platform.CoreProcess;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A SQL dialect as the connector module a release downloads (docs/connector-delivery.md): the package of one of
 * {@code modules/sql-*}, installed by a core that doesn't compile the dialect in, and run in a module host against
 * the dialect's database of the Compose services. The dialect's connections share the {@code sql_*} tools with the
 * dialects the core compiles in: one endpoint publishes one {@code sql_query} for a connection served by the module
 * and a PostgreSQL connection served by the core.
 *
 * <p>The core must be built without the bridge connectors ({@code -Dgateway.withoutBridge}): one that compiles the
 * dialect in serves it itself, whatever is installed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class SqlModuleCoreProcessSteps {

  private final SqlDialectFixture dialect;

  SqlModuleCoreProcessSteps(SqlDialectFixture dialect) {
    this.dialect = dialect;
  }

  private final String table = "GR_MOD_" + Long.toString(System.nanoTime(), 36).toUpperCase(java.util.Locale.ROOT);
  private BuiltModule module;

  @BeforeAll
  void start(@TempDir Path home) throws Exception {
    assumeTrue(TestServices.enabled(), "The SQL module tests need the Compose test services");
    assumeFalse(ConnectorDelivery.has(dialect.type()), "This core compiles " + dialect.type() + " in: build it with "
        + "-Dgateway.withoutBridge to run the module");
    Path built = BuiltModule.packageOf(dialect.moduleId());
    assumeTrue(built != null, "Build modules/" + dialect.moduleId() + " first");
    module = BuiltModule.start(home, built);
  }

  @AfterAll
  void stop() {
    if (module != null) {
      module.close();
    }
  }

  @Test
  @Order(1)
  void theDialectIsACardThatDownloadsAndItsFormInstallsTheModule() throws Exception {
    JsonNode card = module.api.card(dialect.type());
    assertThat(card.path("name").asString()).isEqualTo(dialect.typeName());
    assertThat(card.path("kind").asString()).isEqualTo("built-in");
    assertThat(card.at("/module/id").asString()).isEqualTo(dialect.moduleId());
    assertThat(card.at("/module/installed").asBoolean()).isFalse();
    assertThat(card.at("/module/sizeBytes").asLong()).isEqualTo(module.jar.length);

    JsonNode form = module.api.get("/api/connector-types/" + dialect.type() + "/form");
    assertThat(form.at("/schema/properties/connection").isObject()).as(form.toString()).isTrue();
    assertThat(module.api.module(dialect.moduleId()).path("state").asString()).isEqualTo("ready");
  }

  @Test
  @Order(2)
  void aConnectionIsTestedAndReadThroughTheModulesHost() throws Exception {
    module.api.json(module.api.admin("POST", "/api/connections", dialect.connection("db-dev").toString()), 201);

    JsonNode tested = module.api.json(module.api.admin("POST", "/api/connections/db-dev/test", null), 200);
    assertThat(tested.path("ok").asBoolean()).as(tested.toString()).isTrue();

    module.api.endpoint("db", "db-dev");
    assertThat(module.api.modes("db", "db-dev")).containsEntry("sql_query", "READ_ONLY")
        .containsEntry("sql_execute", "APPROVAL").containsEntry("sql_list_schemas", "READ_ONLY")
        .containsEntry("sql_describe_table", "READ_ONLY").hasSize(4);

    JsonNode rows = module.api.call(2, "sql_query", arguments("db-dev", dialect.selectFortyTwo()));
    assertThat(rows.at("/result/isError").asBoolean(true)).as(rows.toString()).isFalse();
    assertThat(rows.at("/result/structuredContent").toString()).contains("42");

    // The connector's own classification: a write sent as a query is refused before anything runs.
    JsonNode refused = module.api.call(3, "sql_query", arguments("db-dev", "DELETE FROM " + table));
    assertThat(refused.toString()).doesNotContain("\"isError\":false").contains("sql.query-writes");
  }

  @Test
  @Order(3)
  void aChangeWaitsForApprovalAndRunsTheApprovedStatement() throws Exception {
    String create = dialect.createTable(table);
    JsonNode pending = module.api.call(4, "sql_execute", arguments("db-dev", create));
    String handle = pending.at("/result/structuredContent/handle").asString();
    assertThat(handle).as(pending.toString()).startsWith("ap_");
    JsonNode detail = module.api.get("/api/approvals/" + handle);
    assertThat(detail.at("/action/text").asString()).as("the connector's exact action").contains(table);
    JsonNode status = module.api.approve(5, handle);
    assertThat(status.at("/result/structuredContent/status").asString()).as(status.toString())
        .isEqualTo("completed");

    JsonNode described = module.api.call(6, "sql_describe_table", "{\"connection\":\"db-dev\",\"table\":\"" + table
        + "\"}");
    assertThat(described.at("/result/isError").asBoolean(true)).as(described.toString()).isFalse();
    assertThat(described.at("/result/structuredContent").toString().toUpperCase(java.util.Locale.ROOT))
        .contains("OWNER");

    JsonNode dropped = module.api.call(7, "sql_execute", arguments("db-dev", "DROP TABLE " + table));
    JsonNode gone = module.api.approve(8, dropped.at("/result/structuredContent/handle").asString());
    assertThat(gone.at("/result/structuredContent/status").asString()).as(gone.toString()).isEqualTo("completed");
  }

  @Test
  @Order(4)
  void theModulesConnectionSharesTheSqlToolsWithOneTheCoreServes() throws Exception {
    ObjectNode config = CoreProcess.JSON.createObjectNode();
    config.putObject("connection").put("host", "127.0.0.1").put("port", TestServices.port("GW_PG_PORT"))
        .put("database", "postgres").put("sslMode", "disable");
    config.putObject("credentials").put("user", "postgres");
    ObjectNode connection = CoreProcess.JSON.createObjectNode().put("name", "pg-dev").put("type", "postgres")
        .put("env", "dev");
    connection.set("config", config);
    connection.putObject("secrets").putObject("/credentials/password").put("source", "local")
        .put("value", TestServices.value("GW_PG_PASSWORD"));
    module.api.json(module.api.admin("POST", "/api/connections", connection.toString()), 201);
    module.api.json(module.api.admin("POST", "/api/endpoints/db/connections/pg-dev", "{}"), 200);

    List<String> tools = module.api.tools(9);
    assertThat(tools).as("one tool for both connections").containsOnlyOnce("sql_query").containsOnlyOnce("sql_execute");
    JsonNode viaModule = module.api.call(10, "sql_query", arguments("db-dev", dialect.selectFortyTwo()));
    assertThat(viaModule.at("/result/structuredContent").toString()).as(viaModule.toString()).contains("42");
    JsonNode viaCore = module.api.call(11, "sql_query", arguments("pg-dev", "SELECT 41 + 1 AS n"));
    assertThat(viaCore.at("/result/isError").asBoolean(true)).as(viaCore.toString()).isFalse();
    assertThat(viaCore.at("/result/structuredContent").toString()).contains("42");
  }

  @Test
  @Order(5)
  void aTemplateOnTheModulesConnectionCompilesAndRunsInItsHost() throws Exception {
    ObjectNode definition = CoreProcess.JSON.createObjectNode().put("name", "plus_forty_one")
        .put("description", "Adds a number to 41.").put("sql", dialect.selectFortyOnePlus());
    definition.putArray("params").addObject().put("name", "n").put("type", "integer");
    ObjectNode template = CoreProcess.JSON.createObjectNode().put("kind", "sql-query").put("connection", "db-dev");
    template.set("definition", definition);
    JsonNode saved = module.api.json(module.api.admin("POST", "/api/templates", template.toString()), 201);
    assertThat(saved.at("/compile/ok").asBoolean()).as("compiled by the connector in the module: " + saved).isTrue();

    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
    List<String> tools = List.of();
    while (!tools.contains("plus_forty_one") && System.nanoTime() < deadline) {
      Thread.sleep(200);
      tools = module.api.tools(12);
    }
    assertThat(tools).contains("plus_forty_one");
    JsonNode rows = module.api.call(13, "plus_forty_one", "{\"n\":1}");
    assertThat(rows.at("/result/isError").asBoolean(true)).as(rows.toString()).isFalse();
    assertThat(rows.at("/result/structuredContent").toString()).contains("42");
  }
}
