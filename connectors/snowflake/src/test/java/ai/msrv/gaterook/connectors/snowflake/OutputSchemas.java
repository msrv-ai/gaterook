package ai.msrv.gaterook.connectors.snowflake;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;

/** Every answer of a read tool the tests see must match the {@code outputSchema} the tool publishes. */
final class OutputSchemas {

  private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
  private static final SchemaResources SCHEMAS = SchemaResources.of(SnowflakeConnector.class, "snowflake");
  private static final Schema LIST = schema("snowflake_list_schemas.output");
  private static final Schema DESCRIBE = schema("snowflake_describe_table.output");

  private OutputSchemas() {}

  private static Schema schema(String name) {
    return REGISTRY.getSchema(SchemaLocation.of("urn:mcp-gateway:" + name), SCHEMAS.load(name));
  }

  static ToolResult listSchemas(ToolResult r) {
    return valid(LIST, r);
  }

  static ToolResult describeTable(ToolResult r) {
    return valid(DESCRIBE, r);
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
