package com.gaterook.connectors.s3;

import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.connector.api.ToolDefinition.Annotations;
import com.gaterook.connector.api.ToolDefinition.Mode;
import com.gaterook.connector.api.ToolDefinition.Nature;
import com.gaterook.connector.api.ToolDefinition.ResultShape;
import com.gaterook.connectors.support.SchemaResources;
import java.util.List;
import java.util.Set;

/**
 * The tools of the S3 connector (docs/s3.md). Listings, metadata and bounded text reads are Read only by default; a
 * put, copy or delete is one request a person approves, exactly as it will be sent.
 */
final class S3Tools {

  static final String LIST_BUCKETS = "s3_list_buckets";
  static final String LIST_OBJECTS = "s3_list_objects";
  static final String HEAD_OBJECT = "s3_head_object";
  static final String READ_OBJECT = "s3_read_object";
  static final String PUT_OBJECT = "s3_put_object";
  static final String COPY_OBJECT = "s3_copy_object";
  static final String DELETE_OBJECT = "s3_delete_object";

  static final Set<String> CHANGES = Set.of(PUT_OBJECT, COPY_OBJECT, DELETE_OBJECT);

  private S3Tools() {}

  static List<ToolDefinition> definitions(SchemaResources schemas) {
    return List.of(
        read(schemas, LIST_BUCKETS, "List S3 buckets",
            "Buckets agents may use on this connection, with their creation dates. Pages with nextCursor."),
        read(schemas, LIST_OBJECTS, "List objects in a bucket",
            "Keys, sizes and modification times under a prefix. With the default delimiter / deeper keys are "
                + "grouped into prefixes, like folders; an empty delimiter lists every key. Pages with nextCursor."),
        read(schemas, HEAD_OBJECT, "Read an object's metadata",
            "Size, content type, ETag, version, storage class, encryption and user metadata of one object, "
                + "without its content."),
        read(schemas, READ_OBJECT, "Read a text object",
            "Up to maxBytes of one text object (JSON, CSV, logs, configuration) from offset, within the endpoint's "
                + "response size; continue with nextOffset. Binary objects are refused with their metadata."),
        // No outputSchema for the changes: the answer may be an approval handle (report 01).
        change(schemas, PUT_OBJECT, "Write a text object (needs approval)",
            "Creates or replaces one object with text content. You approve the exact request."),
        change(schemas, COPY_OBJECT, "Copy an object (needs approval)",
            "Copies one object to a key of a bucket of this connection, replacing what is there. You approve the "
                + "exact request."),
        change(schemas, DELETE_OBJECT, "Delete an object (needs approval)",
            "Deletes one object, or one version of it. You approve the exact request."));
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

  private static ToolDefinition change(SchemaResources schemas, String name, String title, String description) {
    return ToolDefinition.builder(name)
        .title(title)
        .description(description)
        .inputSchema(schemas.load(name + ".input"))
        .annotations(Annotations.write(true))
        .nature(Nature.MUTATION)
        .defaultMode(Mode.APPROVAL, Mode.APPROVAL)
        .resultShape(ResultShape.JSON)
        .build();
  }
}
