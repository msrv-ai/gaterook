package ai.msrv.gaterook.connectors.mongodb;

import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Annotations;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import ai.msrv.gaterook.connector.api.ToolDefinition.ResultShape;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import java.text.NumberFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MongoDB read and update tools. Only reads publish an outputSchema; updates may return an approval request.
 * Endpoint descriptions distinguish returned-document limits from update execution timeouts.
 */
final class MongoTools {

  static final String FIND = "mongo_find";
  static final String AGGREGATE = "mongo_aggregate";
  static final String UPDATE = "mongo_update";
  static final String UPDATE_MANY = "mongo_update_many";

  private MongoTools() {}

  static List<ToolDefinition> definitions(SchemaResources schemas) {
    return List.of(
        ToolDefinition.builder(FIND)
            .title("Find documents")
            .description("Find documents with a filter and a projection.")
            .inputSchema(schemas.load("mongo_find.input"))
            .outputSchema(schemas.load("mongo_find.output"))
            .annotations(Annotations.read())
            .nature(Nature.READ)
            .defaultMode(Mode.READ_ONLY, Mode.READ_ONLY)
            .resultShape(ResultShape.JSON)
            .build(),
        ToolDefinition.builder(AGGREGATE)
            .title("Run an aggregation pipeline")
            .description("Run an aggregation pipeline. $out and $merge are refused.")
            .inputSchema(schemas.load("mongo_aggregate.input"))
            .outputSchema(schemas.load("mongo_aggregate.output"))
            .annotations(Annotations.read())
            .nature(Nature.READ)
            .defaultMode(Mode.READ_ONLY, Mode.READ_ONLY)
            .resultShape(ResultShape.JSON)
            .build(),
        update(schemas, UPDATE, "Update one document", "Update the first document matching a filter."),
        update(schemas, UPDATE_MANY, "Update matching documents",
            "Update all documents matching a filter. The read document limit does not cap updates."));
  }

  private static ToolDefinition update(SchemaResources schemas, String name, String title, String description) {
    return ToolDefinition.builder(name)
        .title(title)
        .description(description + " Supports update operators or an update pipeline; upsert is optional.")
        .inputSchema(schemas.load("mongo_update.input"))
        .annotations(Annotations.write(true))
        .nature(Nature.MUTATION)
        .defaultMode(Mode.APPROVAL, Mode.APPROVAL)
        .resultShape(ResultShape.JSON)
        .build();
  }

  /** The same tools with the limits of an endpoint's connections in their descriptions. */
  static List<ToolDefinition> withLimits(List<ToolDefinition> tools, int maxDocuments, Duration timeLimit) {
    String limits = " Max " + NumberFormat.getIntegerInstance(Locale.US).format(maxDocuments) + " documents, "
        + seconds(timeLimit) + ".";
    List<ToolDefinition> out = new ArrayList<>();
    for (ToolDefinition t : tools) {
      String suffix = t.mutating() ? " Time limit " + seconds(timeLimit) + "." : limits;
      out.add(new ToolDefinition(t.name(), t.title(), t.description() + suffix, t.inputSchema(), t.outputSchema(),
          t.annotations(), t.nature(), t.defaultMode(), t.prodDefaultMode(), t.resultShape(), t.risk()));
    }
    return List.copyOf(out);
  }

  /** {@code 5 s}, {@code 2.5 s}. */
  static String seconds(Duration d) {
    long ms = d.toMillis();
    if (ms % 1_000 == 0) {
      return (ms / 1_000) + " s";
    }
    return String.format(Locale.US, "%.1f s", ms / 1_000.0);
  }
}
