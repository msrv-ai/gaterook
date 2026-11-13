package com.gaterook.connectors.firebase;

import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.connector.api.ToolDefinition.Annotations;
import com.gaterook.connector.api.ToolDefinition.Mode;
import com.gaterook.connector.api.ToolDefinition.Nature;
import com.gaterook.connector.api.ToolDefinition.ResultShape;
import com.gaterook.connectors.support.SchemaResources;
import java.util.List;

/**
 * The tools of the Firestore connector (docs/firestore.md). Reads are Read only by default: every Firestore query is
 * served by an index and bounded by its limit. A change is one write a person approves.
 */
final class FirestoreTools {

  static final String LIST_COLLECTIONS = "firestore_list_collections";
  static final String GET_DOCUMENT = "firestore_get_document";
  static final String QUERY = "firestore_query";
  static final String WRITE = "firestore_write";

  private FirestoreTools() {}

  static List<ToolDefinition> definitions(SchemaResources schemas) {
    return List.of(
        read(schemas, LIST_COLLECTIONS, "List Firestore collections",
            "Collection IDs at the root of the database or under one document, as far as agents may use them. Pages "
                + "with nextCursor."),
        read(schemas, GET_DOCUMENT, "Read a Firestore document",
            "One document by its path, such as users/alice: its fields as plain JSON and its create and update "
                + "times."),
        read(schemas, QUERY, "Query a Firestore collection",
            "Documents of one collection, or of every collection with one ID, with where filters, orderBy and a "
                + "limit. Pages with nextCursor."),
        // No outputSchema: the answer may be an approval handle (report 01).
        ToolDefinition.builder(WRITE)
            .title("Change a Firestore document (needs approval)")
            .description("Creates, updates or deletes one document. You approve the exact write.")
            .inputSchema(schemas.load(WRITE + ".input"))
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
