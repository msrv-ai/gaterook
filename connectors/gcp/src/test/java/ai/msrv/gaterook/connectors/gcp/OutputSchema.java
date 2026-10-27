package ai.msrv.gaterook.connectors.gcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import tools.jackson.databind.JsonNode;

/** Every answer the tests see must match the {@code outputSchema} its tool publishes (report 09 §3, §8). */
final class OutputSchema {

  private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
  private static final SchemaResources SCHEMAS = SchemaResources.of(GcpConnector.class, "gcp");
  private static final Schema DESCRIBE = REGISTRY.getSchema(SchemaLocation.of("urn:mcp-gateway:gcp_describe.output"),
      SCHEMAS.load("gcp_describe.output"));
  private static final Schema METRICS = REGISTRY.getSchema(SchemaLocation.of("urn:mcp-gateway:gcp_metrics.output"),
      SCHEMAS.load("gcp_metrics.output"));

  private OutputSchema() {}

  static ToolResult describe(ToolResult r) {
    return valid(DESCRIBE, r);
  }

  static ToolResult metrics(ToolResult r) {
    return valid(METRICS, r);
  }

  private static ToolResult valid(Schema schema, ToolResult r) {
    JsonNode s = r.structuredContent();
    assertThat(s).as("structuredContent").isNotNull();
    assertThat(schema.validate(s)).as(s.toString()).isEmpty();
    assertThat(r.content()).as("the same answer as text").isNotEmpty();
    assertThat(r.isError()).isFalse();
    return r;
  }
}
