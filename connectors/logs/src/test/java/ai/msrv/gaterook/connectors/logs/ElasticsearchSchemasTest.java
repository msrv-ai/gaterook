package ai.msrv.gaterook.connectors.logs;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connectors.support.SchemaResources;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class ElasticsearchSchemasTest {
  private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
  private static final SchemaResources RESOURCES = SchemaResources.of(LogsConnector.class, "logs");
  private static final Schema SCHEMA = SCHEMAS.getSchema(RESOURCES.load("elasticsearch.config"));

  @Test
  void requiresOnlyTheSelectedCredentialsAndRejectsAwsSigning() {
    for (String auth : new String[] {
        "{\"mode\":\"none\"}",
        "{\"mode\":\"basic\",\"username\":\"reader\",\"secret\":\"example-password\"}",
        "{\"username\":\"reader\",\"secret\":\"example-password\"}",
        "{\"mode\":\"apiKey\",\"secret\":\"aWQ6a2V5\"}",
        "{\"mode\":\"bearer\",\"secret\":\"example-token\"}"}) {
      assertThat(SCHEMA.validate(config(auth))).as(auth).isEmpty();
    }
    for (String auth : new String[] {
        "{\"mode\":\"basic\",\"secret\":\"example-password\"}",
        "{\"mode\":\"basic\",\"username\":\"reader\"}",
        "{\"mode\":\"basic\",\"username\":\" \",\"secret\":\"example-password\"}",
        "{\"mode\":\"apiKey\"}", "{\"mode\":\"apiKey\",\"secret\":\"\"}",
        "{\"mode\":\"bearer\"}", "{\"mode\":\"awsSigV4\"}"}) {
      assertThat(SCHEMA.validate(config(auth))).as(auth).isNotEmpty();
    }
  }

  @Test
  void fullRestSchemaCannotBeUsedAsLegacyLogsOrAnotherBackend() {
    ObjectNode config = (ObjectNode) config("{\"mode\":\"none\"}");
    assertThat(SCHEMAS.getSchema(RESOURCES.load("config")).validate(config)).isNotEmpty();
    config.put("apiAccess", "logs");
    assertThat(SCHEMA.validate(config)).isNotEmpty();
    config.put("apiAccess", "full").put("caCertificate", "example-pem");
    assertThat(SCHEMA.validate(config)).isEmpty();
    config.put("scopeQuery", "private:true");
    assertThat(SCHEMA.validate(config)).isNotEmpty();
    config.remove("scopeQuery");
    config.put("backend", "opensearch");
    assertThat(SCHEMA.validate(config)).isNotEmpty();
  }

  private static JsonNode config(String auth) {
    return TestCtx.JSON.readTree("""
        {"backend":"elasticsearch","url":"https://search.example.test:9200","auth":%s}""".formatted(auth));
  }
}
