package ai.msrv.gaterook.connectors.search;

import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Annotations;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import ai.msrv.gaterook.connector.api.ToolDefinition.ResultShape;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import java.util.List;

/** The vector_* tools (docs/vector-databases.md): four reads, and an upsert and a delete a person approves. */
final class VectorTools {

  static final String LIST = "vector_list_collections";
  static final String DESCRIBE = "vector_describe_collection";
  static final String GET = "vector_get";
  static final String QUERY = "vector_query";
  static final String UPSERT = "vector_upsert";
  static final String DELETE = "vector_delete";

  private VectorTools() {}

  static List<ToolDefinition> definitions(SchemaResources schemas) {
    return List.of(
        read(schemas, LIST, "List vector collections",
            "Collections agents may use (indexes on Pinecone, classes on Weaviate), with their dimension, metric and "
                + "size where the store says them cheaply."),
        read(schemas, DESCRIBE, "Describe a vector collection",
            "One collection: its vectors (dimension, metric, named vectors), its fields or indexed metadata, its "
                + "namespaces or tenants, and how many records it holds."),
        read(schemas, GET, "Get records",
            "Records by id, or a page of the records a metadata filter matches (all of them without a filter). "
                + "Vectors only when asked for."),
        read(schemas, QUERY, "Search nearest records",
            "The records nearest to a vector, to a stored record (id), or to a text where the store embeds or "
                + "matches it itself. A metadata filter narrows them; topK caps them."),
        // No outputSchema: the answer may be an approval handle (report 01).
        change(schemas, UPSERT, "Upsert records (needs approval)",
            "Writes up to 100 records to one collection; a record with an existing id replaces it. You approve the "
                + "exact records. Never retried.", false),
        change(schemas, DELETE, "Delete records (needs approval)",
            "Deletes records by id, up to 1,000 of one collection. You approve the exact ids; nothing else is "
                + "deleted. Never retried.", true));
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

  private static ToolDefinition change(SchemaResources schemas, String name, String title, String description,
      boolean destructive) {
    return ToolDefinition.builder(name)
        .title(title)
        .description(description)
        .inputSchema(schemas.load(name + ".input"))
        .annotations(Annotations.write(destructive))
        .nature(Nature.MUTATION)
        .defaultMode(Mode.APPROVAL, Mode.APPROVAL)
        .resultShape(ResultShape.JSON)
        .build();
  }
}
