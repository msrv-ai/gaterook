package com.gaterook.connectors.logs;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connectors.support.SchemaResources;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Authentication requires only credentials used by the selected backend and sign-in method. */
class LogsCredentialSchemasTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Schema SCHEMA = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
      .getSchema(SchemaResources.of(LogsConnector.class, "logs").load("config"));

  @Test
  void lokiCanUseNoAuthenticationOrOnlyTheSelectedMethodsCredentials() {
    for (String auth : new String[] {
        "{\"mode\":\"none\"}",
        "{\"mode\":\"bearer\",\"secret\":\"token\"}",
        "{\"mode\":\"basic\",\"username\":\"reader\",\"secret\":\"password\"}"}) {
      assertThat(SCHEMA.validate(JSON.readTree("""
          {"backend":"loki","url":"https://logs.example.test","auth":%s}""".formatted(auth))))
          .as(auth).isEmpty();
    }
    for (String auth : new String[] {
        "{\"mode\":\"bearer\"}",
        "{\"mode\":\"basic\",\"secret\":\"password\"}",
        "{\"mode\":\"basic\",\"username\":\"reader\"}"}) {
      assertThat(SCHEMA.validate(JSON.readTree("""
          {"backend":"loki","url":"https://logs.example.test","auth":%s}""".formatted(auth))))
          .as(auth).isNotEmpty();
    }
  }

  @Test
  void googleSignInNeedsAKeyOnlyForTheServiceAccountKeySource() {
    for (String credentials : new String[] {
        "{\"source\":\"adc\"}",
        "{\"source\":\"serviceAccountKey\",\"serviceAccountKey\":\"stored-key\"}"}) {
      assertThat(SCHEMA.validate(JSON.readTree("""
          {"backend":"gcp-logging","resourceNames":["projects/example-dev"],"credentials":%s}
          """.formatted(credentials)))).as(credentials).isEmpty();
    }
    assertThat(SCHEMA.validate(JSON.readTree("""
        {"backend":"gcp-logging","resourceNames":["projects/example-dev"],
         "credentials":{"source":"serviceAccountKey"}}"""))).isNotEmpty();
  }

  @Test
  void azureMonitorNeedsATenantForAServicePrincipalOrADeviceSignInButNotForTheCli() {
    String base = """
        {"backend":"azure-monitor","workspaces":["5b2f8c1e-0a3d-4e6f-9b7a-1c2d3e4f5a6b"],"credentials":%s%s}""";
    assertThat(SCHEMA.validate(JSON.readTree(base.formatted("{\"source\":\"cli\"}", "")))).isEmpty();
    for (String credentials : new String[] {
        "{\"source\":\"deviceCode\"}",
        "{\"source\":\"clientSecret\",\"clientId\":\"0a1b2c3d-1111-4222-8333-444455556666\",\"clientSecret\":\"s\"}",
        "{\"source\":\"clientCertificate\",\"clientId\":\"0a1b2c3d-1111-4222-8333-444455556666\","
            + "\"certificate\":\"pem\"}"}) {
      assertThat(SCHEMA.validate(JSON.readTree(base.formatted(credentials, "")))).as(credentials).isNotEmpty();
      assertThat(SCHEMA.validate(JSON.readTree(base.formatted(credentials, ",\"tenantId\":\"contoso.onmicrosoft.com\""))))
          .as(credentials).isEmpty();
    }
  }

  @Test
  void theFormsShowWhatTheConnectionNeedsAndMarkWhatSignedInHelpersList() {
    try (var cloud = new com.gaterook.connectors.cloudauth.CloudCredentials(
        com.gaterook.connectors.cloudauth.CloudEndpoints.PRODUCTION, java.time.Clock.systemUTC())) {
      var cloudWatch = new BackendConnector("cloudwatch", cloud).manifest().configSchema();
      assertThat(cloudWatch.at("/properties/logGroups/x-suggest").asBoolean()).isTrue();
      assertThat(cloudWatch.at("/properties/region/x-suggest").isMissingNode()).as("the API needs it").isTrue();
      var gcp = new BackendConnector("gcp-logging", cloud).manifest().configSchema();
      assertThat(gcp.at("/properties/resourceNames/x-suggest").asBoolean()).isTrue();
      // Loki's tenant is optional like every Advanced setting: its helper says when the server needs one.
      var loki = new BackendConnector("loki", cloud).manifest().configSchema();
      assertThat(loki.at("/properties/tenant/x-advanced").isMissingNode()).isTrue();
      var azure = new BackendConnector("azure-monitor", cloud).manifest().configSchema();
      assertThat(azure.at("/properties/tenantId/x-advanced").isMissingNode()).as("tenantId").isTrue();
      for (String field : new String[] {"workspaces", "applicationInsights"}) {
        assertThat(azure.at("/properties/" + field + "/x-advanced").asBoolean(true)).as(field).isFalse();
      }
    }
    var legacy = SchemaResources.of(LogsConnector.class, "logs").load("config");
    for (var branch : legacy.path("oneOf")) {
      if (branch.at("/properties/backend/const").asString("").equals("opensearch")) {
        assertThat(branch.at("/allOf/0/then/properties/indices/x-suggest").asBoolean())
            .as("scoped log search requires index patterns").isTrue();
      }
    }
  }

  @Test
  void cloudWatchProfileDoesNotRequireAnAccessKey() {
    for (String credentials : new String[] {
        "{\"source\":\"profile\",\"profile\":\"dev-logs\"}",
        "{\"source\":\"accessKey\",\"accessKeyId\":\"AKIAIOSFODNN7EXAMPLE\",\"secretAccessKey\":\"stored-key\"}"}) {
      assertThat(SCHEMA.validate(JSON.readTree("""
          {"backend":"cloudwatch","region":"eu-central-1","logGroups":["/app/api"],"credentials":%s}
          """.formatted(credentials)))).as(credentials).isEmpty();
    }
    for (String credentials : new String[] {
        "{\"source\":\"profile\"}",
        "{\"source\":\"accessKey\",\"accessKeyId\":\"AKIAIOSFODNN7EXAMPLE\"}",
        "{\"source\":\"accessKey\",\"secretAccessKey\":\"stored-key\"}"}) {
      assertThat(SCHEMA.validate(JSON.readTree("""
          {"backend":"cloudwatch","region":"eu-central-1","logGroups":["/app/api"],"credentials":%s}
          """.formatted(credentials)))).as(credentials).isNotEmpty();
    }
  }
}
