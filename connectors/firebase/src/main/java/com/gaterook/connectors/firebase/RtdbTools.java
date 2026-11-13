package com.gaterook.connectors.firebase;

import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.connector.api.ToolDefinition.Annotations;
import com.gaterook.connector.api.ToolDefinition.Mode;
import com.gaterook.connector.api.ToolDefinition.Nature;
import com.gaterook.connector.api.ToolDefinition.ResultShape;
import com.gaterook.connectors.support.SchemaResources;
import java.util.List;

/**
 * The tools of the Realtime Database connector (docs/firebase-rtdb.md). A read is Read only by default: its answer is
 * capped as it is read. A change is one request a person approves.
 */
final class RtdbTools {

  static final String GET = "rtdb_get";
  static final String WRITE = "rtdb_write";

  private RtdbTools() {}

  static List<ToolDefinition> definitions(SchemaResources schemas) {
    return List.of(
        ToolDefinition.builder(GET)
            .title("Read Realtime Database data")
            .description("The data at one path. At the root only the top-level keys, unless you ask otherwise; orderBy "
                + "with limitToFirst, limitToLast, startAt, endAt or equalTo reads some children. An answer larger "
                + "than the response size is refused: narrow it.")
            .inputSchema(schemas.load(GET + ".input"))
            .annotations(Annotations.read())
            .nature(Nature.READ)
            .defaultMode(Mode.READ_ONLY, Mode.READ_ONLY)
            .resultShape(ResultShape.JSON)
            .build(),
        // No outputSchema: the answer may be an approval handle (report 01).
        ToolDefinition.builder(WRITE)
            .title("Change Realtime Database data (needs approval)")
            .description("Sets, updates or deletes the data at one path. You approve the exact request.")
            .inputSchema(schemas.load(WRITE + ".input"))
            .annotations(Annotations.write(true))
            .nature(Nature.MUTATION)
            .defaultMode(Mode.APPROVAL, Mode.APPROVAL)
            .resultShape(ResultShape.JSON)
            .build());
  }
}
