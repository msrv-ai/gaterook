package ai.msrv.gaterook.connectors.snowflake;

import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Annotations;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import ai.msrv.gaterook.connector.api.ToolDefinition.ResultShape;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import java.util.List;

/**
 * The tools of the Snowflake connector (docs/snowflake.md). Reads are Read only by default, on prod as well; a change
 * is one statement a person approves.
 */
final class SnowflakeTools {

  static final String LIST_SCHEMAS = "snowflake_list_schemas";
  static final String DESCRIBE_TABLE = "snowflake_describe_table";
  static final String QUERY = "snowflake_query";
  static final String EXECUTE = "snowflake_execute";

  private SnowflakeTools() {}

  static List<ToolDefinition> definitions(SchemaResources schemas) {
    return List.of(
        read(schemas, LIST_SCHEMAS, "List Snowflake schemas and tables",
            "Schemas and tables agents may use in one database, with row counts and sizes, from its "
                + "INFORMATION_SCHEMA.", ResultShape.JSON),
        read(schemas, DESCRIBE_TABLE, "Describe a Snowflake table",
            "Columns, types, nullability, defaults and comments of one table or view, with its row count and size.",
            ResultShape.JSON),
        read(schemas, QUERY, "Run a read-only Snowflake query",
            "One SELECT, WITH … SELECT, SHOW or DESCRIBE. Snowflake plans a SELECT first, and one whose plan changes "
                + "data is refused. Rows are capped by the endpoint.", ResultShape.TABLE),
        // No outputSchema: the answer may be an approval handle (report 01).
        ToolDefinition.builder(EXECUTE)
            .title("Change Snowflake data (needs approval)")
            .description("One INSERT, UPDATE, DELETE, MERGE, TRUNCATE or DDL statement. You approve the exact "
                + "statement.")
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
        .outputSchema(schemas.load(name + ".output"))
        .annotations(Annotations.read())
        .nature(Nature.READ)
        .defaultMode(Mode.READ_ONLY, Mode.READ_ONLY)
        .resultShape(shape)
        .build();
  }
}
