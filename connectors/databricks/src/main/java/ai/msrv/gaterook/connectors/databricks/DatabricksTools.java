package ai.msrv.gaterook.connectors.databricks;

import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Annotations;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import ai.msrv.gaterook.connector.api.ToolDefinition.ResultShape;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import java.util.List;

/** The tools of the Databricks connector (docs/databricks.md). */
final class DatabricksTools {

  static final String LIST_CATALOGS = "databricks_list_catalogs";
  static final String LIST_SCHEMAS = "databricks_list_schemas";
  static final String LIST_TABLES = "databricks_list_tables";
  static final String DESCRIBE_TABLE = "databricks_describe_table";
  static final String QUERY = "databricks_query";
  static final String EXECUTE = "databricks_execute";

  private DatabricksTools() {}

  static List<ToolDefinition> definitions(SchemaResources schemas) {
    return List.of(
        read(schemas, LIST_CATALOGS, "List Databricks catalogs", "Unity Catalog catalogs agents may use.",
            ResultShape.JSON),
        read(schemas, LIST_SCHEMAS, "List schemas of a catalog", "Schemas agents may use in one catalog.",
            ResultShape.JSON),
        read(schemas, LIST_TABLES, "List tables of a schema", "Tables, views and their kinds in one schema.",
            ResultShape.JSON),
        read(schemas, DESCRIBE_TABLE, "Describe a Databricks table",
            "Columns with their types, partitioning, format and comment of one table or view.", ResultShape.JSON),
        read(schemas, QUERY, "Run a Databricks SQL query",
            "One SELECT, WITH, VALUES, SHOW, DESCRIBE or EXPLAIN on the connection's SQL warehouse. Values as :name "
                + "parameters. Pages with nextCursor.", ResultShape.TABLE),
        // No outputSchema: the answer may be an approval handle (report 01).
        ToolDefinition.builder(EXECUTE)
            .title("Change data or tables (needs approval)")
            .description("One INSERT, UPDATE, DELETE, MERGE, DDL or table maintenance statement. You approve the "
                + "exact text.")
            .inputSchema(schemas.load(EXECUTE + ".input"))
            .annotations(Annotations.write(true))
            .nature(Nature.MUTATION)
            .defaultMode(Mode.APPROVAL, Mode.APPROVAL)
            .resultShape(ResultShape.JSON)
            .build());
  }

  private static ToolDefinition read(SchemaResources schemas, String name, String title, String description,
      ResultShape shape) {
    return ToolDefinition.builder(name)
        .title(title)
        .description(description)
        .inputSchema(schemas.load(name + ".input"))
        .annotations(Annotations.read())
        .nature(Nature.READ)
        .defaultMode(Mode.READ_ONLY, Mode.READ_ONLY)
        .resultShape(shape)
        .build();
  }
}
