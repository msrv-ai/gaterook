package ai.msrv.gaterook.connectors.logs;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import tools.jackson.databind.JsonNode;

/** Every answer the tests see must match the {@code outputSchema} the tools publish (report 07 §3.3, §8). */
final class OutputSchema {

  private static final Schema SCHEMA = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
      .getSchema(SchemaLocation.of("urn:mcp-gateway:logs.output"),
          SchemaResources.of(LogsConnector.class, "logs").load("logs.output"));

  private OutputSchema() {}

  static ToolResult valid(ToolResult r) {
    valid(r.structuredContent());
    assertThat(r.content()).as("the same answer as text").isNotEmpty();
    return r;
  }

  static JsonNode valid(JsonNode structured) {
    assertThat(SCHEMA.validate(structured)).as(structured.toString()).isEmpty();
    return structured;
  }
}
