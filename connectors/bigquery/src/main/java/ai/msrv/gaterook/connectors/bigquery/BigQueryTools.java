package ai.msrv.gaterook.connectors.bigquery;

import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Annotations;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import ai.msrv.gaterook.connector.api.ToolDefinition.ResultShape;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import java.util.List;

/** The tools of the BigQuery connector (docs/bigquery.md). */
final class BigQueryTools {

  static final String LIST_DATASETS = "bigquery_list_datasets";
  static final String LIST_TABLES = "bigquery_list_tables";
  static final String DESCRIBE_TABLE = "bigquery_describe_table";
  static final String QUERY = "bigquery_query";
  static final String EXECUTE = "bigquery_execute";

  private BigQueryTools() {}

  static List<ToolDefinition> definitions(SchemaResources schemas) {
    return List.of(
        read(schemas, LIST_DATASETS, "List BigQuery datasets", "Datasets agents may use in the connection's project.",
            ResultShape.JSON),
        read(schemas, LIST_TABLES, "List tables of a dataset", "Tables, views and their kinds in one dataset.",
            ResultShape.JSON),
        read(schemas, DESCRIBE_TABLE, "Describe a BigQuery table",
            "Columns with nested fields, partitioning, clustering, row count, size and a view's query.",
            ResultShape.JSON),
        read(schemas, QUERY, "Run a GoogleSQL query",
            "One SELECT, checked by a free dry run before it runs: BigQuery must call it a SELECT of allowed tables "
                + "within the connection's byte limit. Pages with nextCursor.", ResultShape.TABLE),
        // No outputSchema: the answer may be an approval handle (report 01).
        ToolDefinition.builder(EXECUTE)
            .title("Change data or schema (needs approval)")
            .description("One DML or DDL statement. You approve the exact text with the bytes it processes.")
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
