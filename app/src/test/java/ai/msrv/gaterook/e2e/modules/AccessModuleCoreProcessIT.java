package ai.msrv.gaterook.e2e.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.msrv.gaterook.e2e.platform.CoreProcess;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Microsoft Access as the connector module a release downloads (docs/connector-delivery.md): the package of the
 * {@code msaccess} module, installed by the assembled core when the type's form is asked for, and run in a module
 * host. A person names an {@code .accdb} file on this computer; an agent reads it.
 *
 * <p>The module's source lives in the modules repository: the test takes its package from a checkout of it
 * ({@link BuiltModule#modulesRepo}) and is skipped without one. The file is written by Jackcess, from the module's
 * package: it needs no Access and no test service.
 */
class AccessModuleCoreProcessIT {

  private static BuiltModule module;
  private static Path file;

  @TempDir
  static Path home;

  @TempDir
  static Path data;

  @BeforeAll
  static void start() throws Exception {
    Path built = BuiltModule.packageOf("msaccess");
    assumeTrue(built != null, BuiltModule.howToBuild("msaccess"));
    file = data.toRealPath().resolve("orders.accdb");
    BuiltModule.runWith(built, data, """
        import io.github.spannm.jackcess.Column;
        import io.github.spannm.jackcess.ColumnBuilder;
        import io.github.spannm.jackcess.DataType;
        import io.github.spannm.jackcess.Database;
        import io.github.spannm.jackcess.DatabaseBuilder;
        import io.github.spannm.jackcess.Table;
        import io.github.spannm.jackcess.TableBuilder;
        import java.io.File;
        import java.math.BigDecimal;

        public class Seed {
          public static void main(String[] args) throws Exception {
            try (Database db = DatabaseBuilder.create(Database.FileFormat.V2010, new File(args[0]))) {
              Table t = new TableBuilder("Orders")
                  .addColumn(new ColumnBuilder("ID", DataType.LONG).withAutoNumber(true))
                  .addColumn(new ColumnBuilder("Note", DataType.TEXT).withLength(40))
                  .addColumn(new ColumnBuilder("Total", DataType.MONEY)).toTable(db);
              t.addRow(Column.AUTO_NUMBER, "first", new BigDecimal("12.50"));
              t.addRow(Column.AUTO_NUMBER, "second", new BigDecimal("99.00"));
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
  void aPersonsAccessFileIsReadInTheModulesHost() throws Exception {
    JsonNode card = module.api.card("msaccess");
    assertThat(card.path("name").asString()).isEqualTo("Microsoft Access");
    assertThat(card.path("kind").asString()).isEqualTo("built-in");
    assertThat(card.path("tools").asInt()).isEqualTo(4);
    assertThat(card.at("/module/installed").asBoolean()).isFalse();
    JsonNode form = module.api.get("/api/connector-types/msaccess/form");
    assertThat(form.at("/schema/properties/file").isObject()).as(form.toString()).isTrue();
    assertThat(module.api.module("msaccess").path("state").asString()).isEqualTo("ready");

    ObjectNode connection = CoreProcess.JSON.createObjectNode().put("name", "orders").put("type", "msaccess")
        .put("env", "dev");
    connection.putObject("config").put("file", file.toString());
    module.api.json(module.api.admin("POST", "/api/connections", connection.toString()), 201);
    JsonNode tested = module.api.json(module.api.admin("POST", "/api/connections/orders/test", null), 200);
    assertThat(tested.path("ok").asBoolean()).as(tested.toString()).isTrue();

    module.api.endpoint("office", "orders");
    assertThat(module.api.modes("office", "orders")).containsEntry("access_query", "READ_ONLY")
        .containsEntry("access_list_tables", "READ_ONLY").containsEntry("access_describe_table", "READ_ONLY")
        .containsEntry("access_execute", "APPROVAL").hasSize(4);

    JsonNode rows = module.api.call(2, "access_query", "{\"connection\":\"orders\",\"sql\":"
        + "\"SELECT Note, Total FROM Orders ORDER BY ID\"}");
    assertThat(rows.at("/result/isError").asBoolean(true)).as(rows.toString()).isFalse();
    assertThat(rows.at("/result/content/0/text").asString()).as(rows.toString()).contains("first").contains("99");
  }
}
