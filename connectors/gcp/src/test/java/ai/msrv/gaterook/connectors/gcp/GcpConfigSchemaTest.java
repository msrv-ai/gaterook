package ai.msrv.gaterook.connectors.gcp;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connectors.support.SchemaResources;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GcpConfigSchemaTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Schema SCHEMA = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
      .getSchema(SchemaResources.of(GcpConnector.class, "gcp").load("config"));

  @Test
  void resourceScopesRequireTheirProjectFolderOrOrganizationPrefix() {
    for (String scope : List.of("projects/example-stage", "projects/123456789012", "folders/123456",
        "organizations/456789")) {
      assertThat(SCHEMA.validate(config(scope, "{\"source\":\"adc\"}"))).as(scope).isEmpty();
    }
    for (String scope : List.of("example-stage", "123456789012", "folders/example-stage", "organizations/",
        "https://cloudresourcemanager.googleapis.com/v3/projects/example-stage")) {
      assertThat(SCHEMA.validate(config(scope, "{\"source\":\"adc\"}"))).as(scope).isNotEmpty();
    }
  }

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
    return config("projects/example-stage", credentials);
  }

  private static JsonNode config(String scope, String credentials) {
    return JSON.readTree("{\"scopes\":[\"" + scope + "\"],\"credentials\":" + credentials + "}");
  }
}
