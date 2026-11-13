package com.gaterook.connectors.spanner;

import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.connector.api.ToolDefinition.Annotations;
import com.gaterook.connector.api.ToolDefinition.Mode;
import com.gaterook.connector.api.ToolDefinition.Nature;
import com.gaterook.connector.api.ToolDefinition.ResultShape;
import com.gaterook.connectors.support.SchemaResources;
import java.util.List;

/** The tools of the Spanner connector (docs/spanner.md). */
final class SpannerTools {

  static final String LIST_TABLES = "spanner_list_tables";
  static final String DESCRIBE_TABLE = "spanner_describe_table";
  static final String QUERY = "spanner_query";
  static final String EXECUTE = "spanner_execute";

  private SpannerTools() {}

  static List<ToolDefinition> definitions(SchemaResources schemas) {
    return List.of(
        read(schemas, LIST_TABLES, "List Spanner tables", "Tables and views of the database, with their schema, kind "
            + "and the parent an interleaved table belongs to.", ResultShape.JSON),
        read(schemas, DESCRIBE_TABLE, "Describe a Spanner table", "Columns with their types, the primary key, "
            + "indexes, the interleaving parent and, where the credentials may read it, the table's DDL.",
            ResultShape.JSON),
        read(schemas, QUERY, "Run a Spanner query", "One GoogleSQL or PostgreSQL-dialect query, run in a read-only "
            + "transaction in which Spanner refuses every change. Pages with nextCursor at the first page's snapshot; "
            + "add ORDER BY for stable pages.", ResultShape.TABLE),
        // No outputSchema: the answer may be an approval handle (report 01).
        ToolDefinition.builder(EXECUTE)
            .title("Change data or schema (needs approval)")
            .description("One INSERT, UPDATE or DELETE, committed in its own transaction, or one schema change "
                + "(CREATE, ALTER, DROP). You approve the exact text.")
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
