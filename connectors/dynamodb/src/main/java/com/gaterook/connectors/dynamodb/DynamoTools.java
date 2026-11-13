package com.gaterook.connectors.dynamodb;

import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.connector.api.ToolDefinition.Annotations;
import com.gaterook.connector.api.ToolDefinition.Mode;
import com.gaterook.connector.api.ToolDefinition.Nature;
import com.gaterook.connector.api.ToolDefinition.ResultShape;
import com.gaterook.connectors.support.SchemaResources;
import java.util.List;

/**
 * The tools of the DynamoDB connector (docs/dynamodb.md). Reads are Read only by default; a Scan reads a whole table
 * and pays for every item, so prod asks a person first. A change is one PartiQL statement a person approves.
 */
final class DynamoTools {

  static final String LIST_TABLES = "dynamodb_list_tables";
  static final String DESCRIBE_TABLE = "dynamodb_describe_table";
  static final String QUERY = "dynamodb_query";
  static final String SCAN = "dynamodb_scan";
  static final String PARTIQL = "dynamodb_partiql";
  static final String EXECUTE = "dynamodb_execute";

  private DynamoTools() {}

  static List<ToolDefinition> definitions(SchemaResources schemas) {
    return List.of(
        read(schemas, LIST_TABLES, "List DynamoDB tables",
            "Names of the tables agents may use in the connection's region."),
        read(schemas, DESCRIBE_TABLE, "Describe a DynamoDB table",
            "Key schema, secondary indexes, item count, size, billing mode, stream and TTL of one table."),
        read(schemas, QUERY, "Query items by key",
            "Items of one partition key value, optionally narrowed by sort key, filter and projection; a table or "
                + "a secondary index. Pages with nextCursor."),
        read(schemas, SCAN, "Scan a table",
            "Reads items of a whole table or index with an optional filter: it uses read capacity for every item "
                + "it reads. Prefer dynamodb_query when you know the key."),
        read(schemas, PARTIQL, "Run a PartiQL SELECT",
            "One PartiQL SELECT with ? parameters. Without a key condition DynamoDB scans the table."),
        // No outputSchema: the answer may be an approval handle (report 01).
        ToolDefinition.builder(EXECUTE)
            .title("Change items (needs approval)")
            .description("One PartiQL INSERT, UPDATE or DELETE. You approve the exact statement.")
            .inputSchema(schemas.load(EXECUTE + ".input"))
            .annotations(Annotations.write(true))
            .nature(Nature.MUTATION)
            .defaultMode(Mode.APPROVAL, Mode.APPROVAL)
            .resultShape(ResultShape.JSON)
            .build());
  }

  private static ToolDefinition read(SchemaResources schemas, String name, String title, String description) {
    return ToolDefinition.builder(name)
        .title(title)
        .description(description)
        .inputSchema(schemas.load(name + ".input"))
        .annotations(Annotations.read())
        .nature(Nature.READ)
        .defaultMode(Mode.READ_ONLY, Mode.READ_ONLY)
        .resultShape(ResultShape.JSON)
        .build();
  }
}
