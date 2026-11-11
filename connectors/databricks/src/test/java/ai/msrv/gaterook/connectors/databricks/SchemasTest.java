package ai.msrv.gaterook.connectors.databricks;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/** The connector's schemas keep the rules every built-in connector's do: valid, closed, secrets write-only. */
class SchemasTest {

  private final ConnectorManifest manifest = new DatabricksConnector().manifest();

  @Test
  void everySchemaIsValidJsonSchema2020AndCompiles() {
    SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    Schema meta = registry.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));
    List<JsonNode> schemas = new ArrayList<>(List.of(manifest.configSchema()));
    manifest.tools().forEach(t -> schemas.add(t.inputSchema()));
    for (JsonNode schema : schemas) {
      assertThat(meta.validate(schema)).as(schema.path("title").asString("tool input")).isEmpty();
      Schema compiled = registry.getSchema(SchemaLocation.of("urn:test:databricks"), schema);
      compiled.initializeValidators();
      compiled.validate(JsonNodeFactory.instance.objectNode());
    }
  }

  @Test
  void toolInputsAreClosedWithTheConnectionPlaceholder() {
    for (ToolDefinition t : manifest.tools()) {
      JsonNode input = t.inputSchema();
      assertThat(input.path("type").asString()).as(t.name()).isEqualTo("object");
      assertThat(input.path("additionalProperties").asBoolean(true)).as(t.name()).isFalse();
      assertThat(input.at("/properties/connection")).as(t.name())
          .isEqualTo(JsonNodeFactory.instance.objectNode().put("type", "string"));
      assertThat(t.title()).as(t.name()).isNotBlank().isNotEqualTo(t.name());
      assertThat(t.name()).matches("^databricks_[a-z_]+$");
    }
  }

  @Test
  void secretsAreWriteOnlyAndAValidConfigPasses() {
    JsonNode auth = manifest.configSchema().at("/properties/auth/allOf");
    assertThat(auth.at("/0/then/properties/token/writeOnly").asBoolean()).isTrue();
    assertThat(auth.at("/0/then/properties/token/x-gateway-secret").asString()).isEqualTo("value");
    assertThat(auth.at("/1/then/properties/clientSecret/writeOnly").asBoolean()).isTrue();
    assertThat(auth.at("/1/then/properties/clientSecret/x-gateway-secret").asString()).isEqualTo("value");
    SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    Schema config = registry.getSchema(SchemaLocation.of("urn:test:databricks:config"), manifest.configSchema());
    JsonMapper json = JsonMapper.builder().build();
    assertThat(config.validate(json.readTree("{\"host\": \"https://dbc-a1b2c3d4-e5f6.cloud.databricks.com\", "
        + "\"warehouseId\": \"1a2b3c4d5e6f7a8b\", \"catalog\": \"main\", \"schema\": \"sales\", \"catalogs\": "
        + "[\"main\"], \"schemas\": [\"sales\", \"main.crm\"], \"auth\": {\"method\": \"oauth\", \"clientId\": "
        + "\"6f0e2c9a-1b3d-4e5f-8a7b-9c0d1e2f3a4b\", \"clientSecret\": \"x\"}}"))).isEmpty();
    assertThat(config.validate(json.readTree("{\"host\": \"https://dbc.cloud.databricks.com\", \"warehouseId\": "
        + "\"1a2b\", \"auth\": {\"method\": \"token\", \"token\": \"x\", \"other\": 1}}"))).isNotEmpty();
  }
}
