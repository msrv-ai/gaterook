package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Annotations;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import ai.msrv.gaterook.connector.api.ToolDefinition.ResultShape;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import java.util.List;

/**
 * The four tools both SQL dialects publish (report 04 §3, kit endpoint-detail). PostgreSQL and MySQL share names and
 * schemas on purpose: the core merges same-named tools of different types on one endpoint when the schemas are
 * equal up to the {@code connection} enum (ARCHITECTURE.md §5 step 4).
 */
final class SqlTools {

  static final String LIST_SCHEMAS = "sql_list_schemas";
  static final String DESCRIBE_TABLE = "sql_describe_table";
  static final String QUERY = "sql_query";
  static final String EXECUTE = "sql_execute";

  private SqlTools() {}

  static List<ToolDefinition> definitions(SchemaResources schemas) {
    return List.of(
        ToolDefinition.builder(LIST_SCHEMAS)
            .title("List schemas and tables")
            .description("List the schemas the connection user can see.")
            .inputSchema(schemas.load(LIST_SCHEMAS + ".input"))
            .outputSchema(schemas.load(LIST_SCHEMAS + ".output"))
            .annotations(Annotations.read())
            .nature(Nature.READ)
            .defaultMode(Mode.READ_ONLY, Mode.READ_ONLY)
            .resultShape(ResultShape.JSON)
            .build(),
        ToolDefinition.builder(DESCRIBE_TABLE)
            .title("Describe a table")
            .description("Columns, types, indexes and foreign keys of one table.")
            .inputSchema(schemas.load(DESCRIBE_TABLE + ".input"))
            .outputSchema(schemas.load(DESCRIBE_TABLE + ".output"))
            .annotations(Annotations.read())
            .nature(Nature.READ)
            .defaultMode(Mode.READ_ONLY, Mode.READ_ONLY)
            .resultShape(ResultShape.JSON)
            .build(),
        ToolDefinition.builder(QUERY)
            .title("Run a read-only SQL query")
            .description("Run one SELECT in a read-only transaction. PII columns come back masked.")
            .inputSchema(schemas.load(QUERY + ".input"))
            .outputSchema(schemas.load(QUERY + ".output"))
            .annotations(Annotations.read())
            .nature(Nature.READ)
            .defaultMode(Mode.READ_ONLY, Mode.READ_ONLY)
            .resultShape(ResultShape.TABLE)
            .build(),
        // No outputSchema: the answer may be an approval handle (report 01).
        ToolDefinition.builder(EXECUTE)
            .title("Change data (needs approval)")
            .description("INSERT, UPDATE or DELETE. You approve the exact statement.")
            .inputSchema(schemas.load(EXECUTE + ".input"))
            .annotations(Annotations.write(true))
            .nature(Nature.MUTATION)
            .defaultMode(Mode.APPROVAL, Mode.APPROVAL)
            .resultShape(ResultShape.JSON)
            .build());
  }
}
