package ai.msrv.gaterook.connectors.redshift;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/** The connector's schemas keep the rules every built-in connector's do: valid, closed, secrets write-only. */
class SchemasTest {

  private static ConnectorManifest manifest() {
    try (CloudCredentials cloud = new CloudCredentials(CloudEndpoints.PRODUCTION, Clock.systemUTC())) {
      return new RedshiftConnector(cloud).manifest();
    }
  }

  @Test
  void everySchemaIsValidJsonSchema2020AndCompiles() {
    ConnectorManifest manifest = manifest();
    SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    Schema meta = registry.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));
    List<JsonNode> schemas = new ArrayList<>(List.of(manifest.configSchema()));
    manifest.tools().forEach(t -> schemas.add(t.inputSchema()));
    for (JsonNode schema : schemas) {
      assertThat(meta.validate(schema)).as(schema.path("title").asString("tool input")).isEmpty();
      Schema compiled = registry.getSchema(SchemaLocation.of("urn:test:redshift"), schema);
      compiled.initializeValidators();
      compiled.validate(JsonNodeFactory.instance.objectNode());
    }
  }

  @Test
  void toolInputsAreClosedWithTheConnectionPlaceholder() {
    for (ToolDefinition t : manifest().tools()) {
      JsonNode input = t.inputSchema();
      assertThat(input.path("type").asString()).as(t.name()).isEqualTo("object");
      assertThat(input.path("additionalProperties").asBoolean(true)).as(t.name()).isFalse();
      assertThat(input.at("/properties/connection")).as(t.name())
          .isEqualTo(JsonNodeFactory.instance.objectNode().put("type", "string"));
      assertThat(t.title()).as(t.name()).isNotBlank().isNotEqualTo(t.name());
      assertThat(t.name()).matches("^redshift_[a-z_]+$");
    }
  }

  @Test
  void secretsAreWriteOnlyAndAValidConfigPasses() {
    ConnectorManifest manifest = manifest();
    JsonNode key = manifest.configSchema().at("/properties/credentials/allOf/0/then/properties/secretAccessKey");
    assertThat(key.path("writeOnly").asBoolean()).isTrue();
    assertThat(key.path("x-gateway-secret").asString()).isEqualTo("value");
    SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    Schema config = registry.getSchema(SchemaLocation.of("urn:test:redshift:config"), manifest.configSchema());
    JsonMapper json = JsonMapper.builder().build();
    assertThat(config.validate(json.readTree("{\"region\": \"eu-central-1\", \"target\": {\"kind\": \"serverless\", "
        + "\"workgroup\": \"acme-wg\"}, \"database\": \"dev\", \"databaseAuth\": {\"method\": \"secret\", "
        + "\"secretArn\": \"arn:aws:secretsmanager:eu-central-1:412398765012:secret:redshift/gw-reader-AbCdEf\"}, "
        + "\"credentials\": {\"source\": \"accessKey\", \"accessKeyId\": \"AKIAIOSFODNN7EXAMPLE\", "
        + "\"secretAccessKey\": \"x\", \"roleArn\": \"arn:aws:iam::412398765012:role/gw-redshift\"}}"))).isEmpty();
    assertThat(config.validate(json.readTree("{\"region\": \"eu-central-1\", \"target\": {\"kind\": \"cluster\"}, "
        + "\"database\": \"dev\", \"credentials\": {\"source\": \"profile\", \"profile\": \"default\"}}")))
        .as("a cluster needs its identifier").isNotEmpty();
  }
}
