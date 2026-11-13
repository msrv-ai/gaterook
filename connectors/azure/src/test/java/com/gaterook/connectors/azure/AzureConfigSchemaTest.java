package com.gaterook.connectors.azure;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connectors.cloudauth.AzureSpec;
import com.gaterook.connectors.support.SchemaResources;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The connection form requires what the connector can't sign in without, and nothing more. */
class AzureConfigSchemaTest {

  private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
  private static final JsonNode CONFIG = SchemaResources.of(AzureConnector.class, "azure").load("config");

  @Test
  void theSchemaIsValidJsonSchema2020() {
    Schema meta = REGISTRY.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));
    assertThat(meta.validate(CONFIG)).isEmpty();
  }

  @Test
  void aServicePrincipalOrADeviceSignInNeedsItsTenantAndTheCliDoesNot() {
    Schema schema = REGISTRY.getSchema(CONFIG);
    for (String source : List.of("cli", "deviceCode", "clientSecret", "clientCertificate")) {
      JsonNode config = config(source, null);
      boolean accepted = schema.validate(config).isEmpty();
      boolean signsIn;
      try {
        AzureSpec.fromConfig(config, "/credentials");
        signsIn = true;
      } catch (IllegalArgumentException e) {
        signsIn = false;
      }
      assertThat(accepted).as(source + " without a tenant").isEqualTo(signsIn);
      assertThat(schema.validate(config(source, FakeAzure.TENANT))).as(source + " with a tenant").isEmpty();
    }
  }

  @Test
  void theTenantFollowsTheFormsRuleRequiredInSightAndOptionalUnderAdvanced() {
    // The form keeps a conditionally required field in sight while its condition holds, under Advanced otherwise.
    assertThat(CONFIG.at("/properties/tenantId/x-advanced").isMissingNode()).isTrue();
  }

  @Test
  void subscriptionsStayRequiredAndAreListedBeforeTheyAreFilledIn() {
    assertThat(CONFIG.path("required").toString()).contains("\"subscriptions\"");
    assertThat(CONFIG.at("/properties/subscriptions/minItems").asInt()).isEqualTo(1);
    assertThat(CONFIG.at("/properties/subscriptions/x-suggest").asBoolean(false)).isTrue();
    // What the connection needs to sign in is never relaxed for a suggestion.
    assertThat(CONFIG.at("/properties/tenantId/x-suggest").isMissingNode()).isTrue();
    assertThat(CONFIG.at("/properties/credentials/x-suggest").isMissingNode()).isTrue();
  }

  /** A config of {@code source} as the form writes it, the secret field filled in as the core checks a draft. */
  private static JsonNode config(String source, String tenant) {
    String secret = switch (source) {
      case "clientSecret" -> ",\"clientId\":\"" + FakeAzure.CLIENT + "\",\"clientSecret\":\"s\"";
      case "clientCertificate" -> ",\"clientId\":\"" + FakeAzure.CLIENT + "\",\"certificate\":\"pem\"";
      default -> "";
    };
    return TestCtx.JSON.readTree("{" + (tenant == null ? "" : "\"tenantId\":\"" + tenant + "\",")
        + "\"subscriptions\":[\"" + FakeAzure.SUB + "\"],\"credentials\":{\"source\":\"" + source + "\"" + secret
        + "}}");
  }
}
