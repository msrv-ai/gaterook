package com.gaterook.connectors.azure;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.ToolResult;
import com.gaterook.connectors.support.SchemaResources;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;

/** Every answer the tests see must match the {@code outputSchema} its tool publishes . */
final class OutputSchema {

  private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
  private static final SchemaResources SCHEMAS = SchemaResources.of(AzureConnector.class, "azure");
  private static final Schema DESCRIBE = REGISTRY.getSchema(
      SchemaLocation.of("urn:mcp-gateway:azure_describe.output"), SCHEMAS.load("azure_describe.output"));
  private static final Schema MONITOR = REGISTRY.getSchema(
      SchemaLocation.of("urn:mcp-gateway:azure_monitor.output"), SCHEMAS.load("azure_monitor.output"));
  private static final Schema API_READ = REGISTRY.getSchema(
      SchemaLocation.of("urn:mcp-gateway:azure_api_read.output"), SCHEMAS.load("azure_api_read.output"));

  private static final Schema STORAGE_READ = REGISTRY.getSchema(
      SchemaLocation.of("urn:mcp-gateway:azure_storage_read.output"), SCHEMAS.load("azure_storage_read.output"));

  private OutputSchema() {}

  static ToolResult storageRead(ToolResult r) {
    return valid(STORAGE_READ, r);
  }

  static ToolResult describe(ToolResult r) {
    return valid(DESCRIBE, r);
  }

  static ToolResult monitor(ToolResult r) {
    return valid(MONITOR, r);
  }

  static ToolResult apiRead(ToolResult r) {
    return valid(API_READ, r);
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
