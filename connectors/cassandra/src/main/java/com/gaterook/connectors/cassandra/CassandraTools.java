package com.gaterook.connectors.cassandra;

import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.connector.api.ToolDefinition.Annotations;
import com.gaterook.connector.api.ToolDefinition.Mode;
import com.gaterook.connector.api.ToolDefinition.Nature;
import com.gaterook.connector.api.ToolDefinition.ResultShape;
import com.gaterook.connectors.support.SchemaResources;
import java.util.List;

/**
 * The tools of the Cassandra connector (docs/cassandra.md): three reads, Read only by default on every environment, and
 * one change a person approves.
 */
final class CassandraTools {

  static final String LIST_KEYSPACES = "cassandra_list_keyspaces";
  static final String DESCRIBE_TABLE = "cassandra_describe_table";
  static final String QUERY = "cassandra_query";
  static final String EXECUTE = "cassandra_execute";

  private CassandraTools() {}

  static List<ToolDefinition> definitions(SchemaResources schemas) {
    return List.of(
        read(schemas, LIST_KEYSPACES, "List Cassandra keyspaces and tables",
            "Keyspaces agents may use, with their replication, tables and materialized views, from system_schema.",
            ResultShape.JSON),
        read(schemas, DESCRIBE_TABLE, "Describe a Cassandra table",
            "Columns of one table or view with their kinds (partition key, clustering, static, regular), types and "
                + "clustering order, its secondary indexes and views, default TTL and compaction.",
            ResultShape.JSON),
        read(schemas, QUERY, "Run a CQL SELECT",
            "One CQL SELECT with ? or :name values, at the connection's read consistency. Pages with nextCursor. "
                + "ALLOW FILTERING scans the cluster and is refused unless a dev connection allows it.",
            ResultShape.TABLE),
        // No outputSchema: the answer may be an approval handle (report 01).
        ToolDefinition.builder(EXECUTE)
            .title("Change Cassandra data or schema (needs approval)")
            .description("One INSERT, UPDATE, DELETE or TRUNCATE, or CREATE, ALTER or DROP of a table, index, type or "
                + "materialized view. You approve the exact statement and its values; it runs once, never retried.")
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
