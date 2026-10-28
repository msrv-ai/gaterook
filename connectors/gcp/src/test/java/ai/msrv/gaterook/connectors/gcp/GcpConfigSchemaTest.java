package ai.msrv.gaterook.connectors.gcp;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connectors.support.SchemaResources;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GcpConfigSchemaTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Schema SCHEMA = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
      .getSchema(SchemaResources.of(GcpConnector.class, "gcp").load("config"));

  @Test
  void adcNeedsNoKeyWhileServiceAccountAuthenticationRequiresOne() {
    assertThat(SCHEMA.validate(config("{\"source\":\"adc\"}"))).isEmpty();
    assertThat(SCHEMA.validate(config("{\"source\":\"serviceAccountKey\",\"serviceAccountKey\":\"stored-key\"}")))
        .isEmpty();
    assertThat(SCHEMA.validate(config("{\"source\":\"serviceAccountKey\"}"))).isNotEmpty();
  }

  @Test
  void inactiveSavedKeysDoNotBlockAdcButActiveKeyLimitsStillApply() {
    var config = config("{\"source\":\"adc\"}");
    ((tools.jackson.databind.node.ObjectNode) config.path("credentials")).put("serviceAccountKey", "x".repeat(10001));
    assertThat(SCHEMA.validate(config)).isEmpty();
    ((tools.jackson.databind.node.ObjectNode) config.path("credentials")).put("source", "serviceAccountKey");
    assertThat(SCHEMA.validate(config)).isNotEmpty();
    assertThat(SCHEMA.validate(config("{\"source\":\"adc\",\"unknownCredential\":true}"))).isNotEmpty();
  }

  private static JsonNode config(String credentials) {
    return JSON.readTree("{\"scopes\":[\"projects/example-stage\"],\"credentials\":" + credentials + "}");
  }
}
