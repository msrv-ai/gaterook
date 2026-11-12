package ai.msrv.gaterook.connectors.search;

import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Annotations;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import ai.msrv.gaterook.connector.api.ToolDefinition.ResultShape;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import java.util.List;

/** The search_* tools (docs/search-engines.md): four reads, and a write and a delete of documents a person approves. */
final class SearchTools {

  static final String LIST = "search_list_indexes";
  static final String DESCRIBE = "search_describe_index";
  static final String QUERY = "search_query";
  static final String GET = "search_get_documents";
  static final String INDEX = "search_index_documents";
  static final String DELETE = "search_delete_documents";

  private SearchTools() {}

  static List<ToolDefinition> definitions(SchemaResources schemas) {
    return List.of(
        read(schemas, LIST, "List search indexes",
            "Indexes agents may use (collections on Typesense), with their size where the engine says it."),
        read(schemas, DESCRIBE, "Describe a search index",
            "One index: its fields and which may be searched, filtered, sorted and faceted, its ranking settings and "
                + "how many documents it holds."),
        read(schemas, QUERY, "Search an index",
            "Full-text search with a metadata filter, facets, sorting and paging; a vector too where the engine has "
                + "vector search."),
        read(schemas, GET, "Get documents",
            "Documents by id, up to 100."),
        // No outputSchema: the answer may be an approval handle (report 01).
        change(schemas, INDEX, "Index documents (needs approval)",
            "Adds or replaces up to 100 documents of one index, or merges their fields into existing ones. You "
                + "approve the exact documents. Never retried.", false),
        change(schemas, DELETE, "Delete documents (needs approval)",
            "Deletes documents by id, up to 1,000 of one index. You approve the exact ids; nothing else is deleted. "
                + "Never retried.", true));
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
