package com.gaterook.connectors.athena;

import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.connector.api.ToolDefinition.Annotations;
import com.gaterook.connector.api.ToolDefinition.Mode;
import com.gaterook.connector.api.ToolDefinition.Nature;
import com.gaterook.connector.api.ToolDefinition.ResultShape;
import com.gaterook.connectors.support.SchemaResources;
import java.util.List;

/** The tools of the Athena connector (docs/athena.md). */
final class AthenaTools {

  static final String LIST_CATALOGS = "athena_list_catalogs";
  static final String LIST_DATABASES = "athena_list_databases";
  static final String LIST_TABLES = "athena_list_tables";
  static final String DESCRIBE_TABLE = "athena_describe_table";
  static final String QUERY = "athena_query";
  static final String EXECUTE = "athena_execute";

  private AthenaTools() {}

  static List<ToolDefinition> definitions(SchemaResources schemas) {
    return List.of(
        read(schemas, LIST_CATALOGS, "List Athena data catalogs", "Data catalogs agents may use in the region.",
            ResultShape.JSON),
        read(schemas, LIST_DATABASES, "List databases of a catalog", "Databases agents may use in one data catalog. "
            + "Pages with nextCursor.", ResultShape.JSON),
        read(schemas, LIST_TABLES, "List tables of a database", "Tables and views of one database. Pages with "
            + "nextCursor.", ResultShape.JSON),
        read(schemas, DESCRIBE_TABLE, "Describe an Athena table", "Columns with types and comments, partition keys, "
            + "the table type and its location.", ResultShape.JSON),
        read(schemas, QUERY, "Run an Athena query",
            "One SELECT, WITH, VALUES, SHOW, DESCRIBE or EXPLAIN in the connection's workgroup. Athena's own IO plan "
                + "checks a query first: it must write no table and read only databases of the connection. Reports "
                + "the data scanned; pages with nextCursor.", ResultShape.TABLE),
        // No outputSchema: the answer may be an approval handle (report 01).
        ToolDefinition.builder(EXECUTE)
            .title("Change data or schema (needs approval)")
            .description("One INSERT, UPDATE, DELETE, MERGE, CREATE, ALTER, DROP, MSCK REPAIR TABLE, OPTIMIZE or "
                + "VACUUM statement. You approve the exact text with the tables Athena's plan reads and writes.")
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
