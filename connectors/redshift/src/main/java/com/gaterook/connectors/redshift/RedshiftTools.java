package com.gaterook.connectors.redshift;

import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.connector.api.ToolDefinition.Annotations;
import com.gaterook.connector.api.ToolDefinition.Mode;
import com.gaterook.connector.api.ToolDefinition.Nature;
import com.gaterook.connector.api.ToolDefinition.ResultShape;
import com.gaterook.connectors.support.SchemaResources;
import java.util.List;

/** The tools of the Redshift connector (docs/redshift.md). */
final class RedshiftTools {

  static final String LIST_SCHEMAS = "redshift_list_schemas";
  static final String LIST_TABLES = "redshift_list_tables";
  static final String DESCRIBE_TABLE = "redshift_describe_table";
  static final String QUERY = "redshift_query";
  static final String EXECUTE = "redshift_execute";

  private RedshiftTools() {}

  static List<ToolDefinition> definitions(SchemaResources schemas) {
    return List.of(
        read(schemas, LIST_SCHEMAS, "List Redshift schemas", "Schemas of the connection's database.",
            ResultShape.JSON),
        read(schemas, LIST_TABLES, "List tables of a schema", "Tables, views and their kinds in one schema.",
            ResultShape.JSON),
        read(schemas, DESCRIBE_TABLE, "Describe a Redshift table",
            "Columns of one table or view with their types, nullability and defaults.", ResultShape.JSON),
        read(schemas, QUERY, "Run a Redshift SQL query",
            "One SELECT, WITH, SHOW or EXPLAIN, run in a READ ONLY transaction that is rolled back. Values as "
                + ":name parameters. Pages with nextCursor.", ResultShape.TABLE),
        // No outputSchema: the answer may be an approval handle (report 01).
        ToolDefinition.builder(EXECUTE)
            .title("Change data or schema (needs approval)")
            .description("One INSERT, UPDATE, DELETE, MERGE or DDL statement. You approve the exact text with "
                + "Redshift's plan and row estimate.")
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
