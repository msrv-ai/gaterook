package ai.msrv.gaterook.connectors.neo4j;

import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Annotations;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import ai.msrv.gaterook.connector.api.ToolDefinition.ResultShape;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import java.util.List;

/** The tools of the Neo4j connector (docs/neo4j.md). */
final class Neo4jTools {

  static final String SCHEMA = "neo4j_schema";
  static final String QUERY = "neo4j_query";
  static final String EXECUTE = "neo4j_execute";

  private Neo4jTools() {}

  static List<ToolDefinition> definitions(SchemaResources schemas) {
    return List.of(
        read(schemas, SCHEMA, "Show the graph's schema", "Labels, relationship types, property keys, indexes and "
            + "constraints of the connection's database; optionally which labels connect through which types.",
            ResultShape.JSON),
        read(schemas, QUERY, "Run a Cypher read", "One Cypher read with $parameters, run in a READ transaction after "
            + "Neo4j's EXPLAIN calls it read only. Nodes, relationships, paths, temporal and spatial values come back "
            + "as JSON.", ResultShape.TABLE),
        // No outputSchema: the answer may be an approval handle (report 01).
        ToolDefinition.builder(EXECUTE)
            .title("Change the graph (needs approval)")
            .description("One Cypher write with $parameters. You approve the exact statement with Neo4j's plan of it; "
                + "it runs once, in one transaction, and reports what it changed.")
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
