package com.gaterook.connectors.trino;

import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.connector.api.ToolDefinition.Annotations;
import com.gaterook.connector.api.ToolDefinition.Mode;
import com.gaterook.connector.api.ToolDefinition.Nature;
import com.gaterook.connector.api.ToolDefinition.ResultShape;
import com.gaterook.connectors.support.SchemaResources;
import java.util.List;

/** The tools of the Trino connector (docs/trino.md). */
final class TrinoTools {

  static final String LIST_CATALOGS = "trino_list_catalogs";
  static final String LIST_SCHEMAS = "trino_list_schemas";
  static final String LIST_TABLES = "trino_list_tables";
  static final String DESCRIBE_TABLE = "trino_describe_table";
  static final String QUERY = "trino_query";
  static final String EXECUTE = "trino_execute";

  private TrinoTools() {}

  static List<ToolDefinition> definitions(SchemaResources schemas) {
    return List.of(
        read(schemas, LIST_CATALOGS, "List Trino catalogs", "Catalogs agents may use on the coordinator.",
            ResultShape.JSON),
        read(schemas, LIST_SCHEMAS, "List schemas of a catalog", "Schemas of one catalog.", ResultShape.JSON),
        read(schemas, LIST_TABLES, "List tables of a schema", "Tables and views of one schema, optionally matching "
            + "a LIKE pattern.", ResultShape.JSON),
        read(schemas, DESCRIBE_TABLE, "Describe a Trino table", "Columns with their types and comments, and the "
            + "table's comment.", ResultShape.JSON),
        read(schemas, QUERY, "Run a Trino query",
            "One SELECT, WITH, VALUES, SHOW, DESCRIBE or EXPLAIN. Trino's own IO plan checks it first: it must "
                + "write no table and read only catalogs of the connection. Results end at the row limit: Trino "
                + "can't resume a query, so narrow it to see more.", ResultShape.TABLE),
        // No outputSchema: the answer may be an approval handle (report 01).
        ToolDefinition.builder(EXECUTE)
            .title("Change data or schema (needs approval)")
            .description("One INSERT, UPDATE, DELETE, MERGE, CREATE, ALTER, DROP, COMMENT, TRUNCATE, ANALYZE or "
                + "REFRESH statement. You approve the exact text with the tables Trino's plan reads and writes.")
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
