package com.gaterook.e2e.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.gaterook.e2e.platform.CoreProcess;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * DuckDB as the connector module a release downloads (docs/connector-delivery.md): the package of the
 * {@code duckdb} module, installed by the assembled core when the type's form is asked for, and run in a module
 * host with DuckDB's native library. A person names a database file on this computer; an agent reads it.
 *
 * <p>The module's source lives in the modules repository: the test takes its package from a checkout of it
 * ({@link BuiltModule#modulesRepo}) and is skipped without one. The file is made by DuckDB itself, from the module's
 * package. The package carries DuckDB for macOS on Apple Silicon and Windows x64, so the test runs there.
 */
class DuckDbModuleCoreProcessIT {

  private static BuiltModule module;
  private static Path file;

  @TempDir
  static Path home;

  @TempDir
  static Path data;

  @BeforeAll
  static void start() throws Exception {
    Path built = BuiltModule.packageOf("duckdb");
    assumeTrue(built != null, BuiltModule.howToBuild("duckdb"));
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    assumeTrue((os.startsWith("mac") && arch.equals("aarch64")) || (os.startsWith("windows") && arch.equals("amd64")),
        "The package has no DuckDB library for " + os + " on " + arch);
    file = data.toRealPath().resolve("warehouse.duckdb");
    BuiltModule.runWith(built, data, """
        import java.sql.Connection;
        import java.sql.Statement;
        import java.util.Properties;

        public class Seed {
          public static void main(String[] args) throws Exception {
            try (Connection c = new org.duckdb.DuckDBDriver().connect("jdbc:duckdb:" + args[0], new Properties());
                Statement s = c.createStatement()) {
              s.execute("CREATE TABLE orders (id INTEGER PRIMARY KEY, total DECIMAL(10, 2))");
              s.execute("INSERT INTO orders VALUES (1, 10.50), (2, 99.00)");
            }
          }
        }
        """, file.toString());
    module = BuiltModule.start(home, built);
  }

  @AfterAll
  static void stop() {
    if (module != null) {
      module.close();
    }
  }

  @Test
  void aPersonsDatabaseFileIsReadInTheModulesHost() throws Exception {
    JsonNode card = module.api.card("duckdb");
    assertThat(card.path("kind").asString()).isEqualTo("built-in");
    assertThat(card.path("tools").asInt()).isEqualTo(4);
    assertThat(card.at("/module/installed").asBoolean()).isFalse();
    JsonNode form = module.api.get("/api/connector-types/duckdb/form");
    assertThat(form.at("/schema/properties/file").isObject()).as(form.toString()).isTrue();
    assertThat(module.api.module("duckdb").path("state").asString()).isEqualTo("ready");

    ObjectNode connection = CoreProcess.JSON.createObjectNode().put("name", "warehouse").put("type", "duckdb")
        .put("env", "dev");
    connection.putObject("config").put("file", file.toString());
    module.api.json(module.api.admin("POST", "/api/connections", connection.toString()), 201);
    JsonNode tested = module.api.json(module.api.admin("POST", "/api/connections/warehouse/test", null), 200);
    assertThat(tested.path("ok").asBoolean()).as(tested.toString()).isTrue();

    module.api.endpoint("analytics", "warehouse");
    assertThat(module.api.modes("analytics", "warehouse")).containsEntry("duckdb_query", "READ_ONLY")
        .containsEntry("duckdb_list_tables", "READ_ONLY").containsEntry("duckdb_describe_table", "READ_ONLY")
        .containsEntry("duckdb_execute", "APPROVAL").hasSize(4);

    JsonNode rows = module.api.call(2, "duckdb_query", "{\"connection\":\"warehouse\",\"sql\":"
        + "\"SELECT id, total FROM orders ORDER BY id\"}");
    assertThat(rows.at("/result/isError").asBoolean(true)).as(rows.toString()).isFalse();
    assertThat(rows.at("/result/content/0/text").asString()).as(rows.toString()).contains("10.50").contains("99.00");

    // The connector's own classification: a change sent as a query is refused before DuckDB sees it.
    JsonNode refused = module.api.call(3, "duckdb_query", "{\"connection\":\"warehouse\",\"sql\":"
        + "\"DELETE FROM orders\"}");
    assertThat(refused.toString()).doesNotContain("\"isError\":false").contains("duckdb.read-only");
  }
}
