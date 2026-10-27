package ai.msrv.gaterook.connectors.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import tools.jackson.databind.JsonNode;

/** Every answer the tests see must match the {@code outputSchema} its tool publishes (report 05 §3, §8). */
final class OutputSchema {

  private static final SchemaResources SCHEMAS = SchemaResources.of(RedisConnector.class, "redis");
  private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
  private static final Schema GET = REGISTRY.getSchema(SchemaLocation.of("urn:mcp-gateway:redis_get.output"),
      SCHEMAS.load("redis_get.output"));
  private static final Schema SCAN = REGISTRY.getSchema(SchemaLocation.of("urn:mcp-gateway:redis_scan.output"),
      SCHEMAS.load("redis_scan.output"));

  private OutputSchema() {}

  static ToolResult get(ToolResult r) {
    assertThat(r.isError()).isFalse();
    assertThat(GET.validate(r.structuredContent())).as(r.structuredContent().toString()).isEmpty();
    assertThat(r.content()).as("the same answer as text").isNotEmpty();
    return r;
  }

  static ToolResult scan(ToolResult r) {
    assertThat(r.isError()).isFalse();
    assertThat(SCAN.validate(r.structuredContent())).as(r.structuredContent().toString()).isEmpty();
    assertThat(r.content()).as("the same answer as text").isNotEmpty();
    return r;
  }

  static JsonNode s(ToolResult r) {
    return r.structuredContent();
  }
}
