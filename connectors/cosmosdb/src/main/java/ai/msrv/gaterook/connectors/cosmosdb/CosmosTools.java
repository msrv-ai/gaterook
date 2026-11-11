package ai.msrv.gaterook.connectors.cosmosdb;

import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Annotations;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import ai.msrv.gaterook.connector.api.ToolDefinition.ResultShape;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import java.util.List;

/**
 * The tools of the Cosmos DB connector (docs/cosmosdb.md). Reads are Read only by default; a change is one operation
 * on one item that a person approves.
 */
final class CosmosTools {

  static final String LIST_DATABASES = "cosmosdb_list_databases";
  static final String LIST_CONTAINERS = "cosmosdb_list_containers";
  static final String DESCRIBE_CONTAINER = "cosmosdb_describe_container";
  static final String QUERY = "cosmosdb_query";
  static final String READ_ITEM = "cosmosdb_read_item";
  static final String EXECUTE = "cosmosdb_execute";

  private CosmosTools() {}

  static List<ToolDefinition> definitions(SchemaResources schemas) {
    return List.of(
        read(schemas, LIST_DATABASES, "List Cosmos DB databases",
            "IDs of the databases agents may use in the account."),
        read(schemas, LIST_CONTAINERS, "List Cosmos DB containers",
            "Containers of one database that agents may use, with their partition key paths."),
        read(schemas, DESCRIBE_CONTAINER, "Describe a Cosmos DB container",
            "Partition key, indexing policy, default time to live, unique keys and, when the credentials may read it, "
                + "the throughput of one container."),
        read(schemas, QUERY, "Query items with Cosmos DB SQL",
            "One Cosmos DB SQL SELECT on one container, with @name parameters. Give partitionKey to read one "
                + "partition; without it the query fans out over every partition, where Cosmos DB's gateway refuses "
                + "TOP, ORDER BY, OFFSET LIMIT, DISTINCT, GROUP BY and aggregates. Pages with nextCursor and reports "
                + "the request units used."),
        read(schemas, READ_ITEM, "Read one Cosmos DB item",
            "One item by its id and partition key value, with its _etag for a later change."),
        // No outputSchema: the answer may be an approval handle (report 01).
        ToolDefinition.builder(EXECUTE)
            .title("Change one item (needs approval)")
            .description("One create, upsert, replace, patch or delete of one item in one container. You approve the "
                + "exact request.")
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
