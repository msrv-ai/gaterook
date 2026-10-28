package ai.msrv.gaterook.connectors.logs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class BackendConnectorTest {

  @Test
  void eachServicePublishesItsOwnToolsAndOpenSearchIsADatabase() {
    try (var cloud = cloud(); var legacy = new LogsConnector(cloud)) {
      Map<String, List<String>> names = Map.of(
          "gcp-logging", List.of("gcp_logging_search", "gcp_logging_tail"),
          "loki", List.of("loki_search", "loki_tail"),
          "cloudwatch", List.of("cloudwatch_logs_search", "cloudwatch_logs_tail"),
          "opensearch", List.of("opensearch_indices", "opensearch_query", "opensearch_request"));
      for (var entry : names.entrySet()) {
        try (var connector = new BackendConnector(entry.getKey(), cloud)) {
          var manifest = connector.manifest();
          assertThat(manifest.id()).isEqualTo(entry.getKey());
          assertThat(manifest.tools()).extracting(ToolDefinition::name).containsExactlyElementsOf(entry.getValue());
          assertThat(manifest.configSchema().path("x-variants").isMissingNode()).isTrue();
          assertThat(manifest.configSchema().path("x-legacy").isMissingNode()).isTrue();
          assertThat(manifest.configSchema().at("/properties/backend/const").asString()).isEqualTo(entry.getKey());
          assertThat(manifest.configSchema().path("x-project-selection").isMissingNode())
              .isEqualTo(!entry.getKey().equals("gcp-logging"));
          if (entry.getKey().equals("opensearch")) {
            assertThat(manifest.group()).isEqualTo("Databases");
            assertThat(manifest.policySchema()).isNull();
            assertThat(manifest.configSchema().path("properties").propertyNames())
                .doesNotContain("resourceNames", "indices", "scopeQuery", "advanced", "timestampField");
            for (ToolDefinition tool : manifest.tools()) {
              assertThat(legacy.manifest().tools().stream().filter(t -> t.name().equals(tool.name())).findFirst())
                  .contains(tool);
              assertThat(tool.description()).doesNotContain("log-only", "Log-only", "scopeQuery", "log configuration");
              assertThat(tool.inputSchema().toString()).doesNotContain("log-only", "logs_search", "logs_tail");
            }
          } else {
            assertThat(manifest.group()).isEqualTo("Logs");
            assertThat(manifest.policySchema().path("x-policy-section").asString()).isEqualTo("logs");
          }
        }
      }
      assertThat(legacy.manifest().configSchema().path("x-legacy").asBoolean()).isTrue();
    }
  }

  @Test
  void openSearchDescriptionContainsOnlyDatabaseCapabilities() throws Exception {
    try (var cloud = cloud(); var connector = new BackendConnector("opensearch", cloud)) {
      ConnectionConfig cfg = typed("opensearch", Configs.of("search", Env.DEV, """
          {"backend":"opensearch","url":"https://search.example.test","auth":{"mode":"none"}}"""));
      JsonNode details = connector.describe(cfg, new TestSecrets(), TestCtx.of(), null).orElseThrow();
      assertThat(details.path("language").asString()).isEqualTo("OpenSearch REST / JSON Query DSL");
      assertThat(details.toString()).contains("opensearch_indices", "opensearch_query", "opensearch_request")
          .doesNotContain("logs_search", "logs_tail", "scopeQuery", "timestampField", "tailWaitsUpToSeconds", "sources");
    }
  }

  @Test
  void schemaRequiresOnlyItsServiceCredentialsAndRejectsBackendChanges() {
    try (var cloud = cloud()) {
      Map<String, String> configurations = Map.of(
          "gcp-logging", """
              {"backend":"gcp-logging","credentials":{"source":"adc"},"resourceNames":["projects/example-prod"]}""",
          "loki", """
              {"backend":"loki","url":"https://logs.example.test","auth":{"mode":"none"}}""",
          "cloudwatch", """
              {"backend":"cloudwatch","region":"eu-central-1","logGroups":["/app/api"],"credentials":{"source":"profile","profile":"example-dev"}}""",
          "opensearch", """
              {"backend":"opensearch","url":"https://search.example.test","auth":{"mode":"basic","username":"reader","secret":"test-secret"}}""");
      for (var entry : configurations.entrySet()) {
        try (var connector = new BackendConnector(entry.getKey(), cloud)) {
          var validator = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
              .getSchema(connector.manifest().configSchema());
          ObjectNode config = (ObjectNode) TestCtx.JSON.readTree(entry.getValue());
          assertThat(validator.validate(config)).as(entry.getKey()).isEmpty();
          config.put("backend", "another-backend");
          assertThat(validator.validate(config)).isNotEmpty();
        }
      }
      try (var connector = new BackendConnector("gcp-logging", cloud)) {
        var validator = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(connector.manifest().configSchema());
        ObjectNode config = (ObjectNode) TestCtx.JSON.readTree(configurations.get("gcp-logging"));
        ((ObjectNode) config.path("credentials")).put("source", "serviceAccountKey");
        assertThat(validator.validate(config)).isNotEmpty();
      }
    }
  }

  @Test
  void descriptionsAndLegacyPreviewsAreSpecificToTheSelectedService() {
    try (var cloud = cloud(); var legacy = new LogsConnector(cloud)) {
      var preview = legacy.previewTools(Configs.gcp());
      assertThat(preview).extracting(ToolDefinition::name).containsExactly("logs_search", "logs_tail");
      for (ToolDefinition tool : preview) {
        assertThat(tool.description()).contains("Google Cloud Logging").doesNotContain("OpenSearch", "Loki", "CloudWatch");
        assertThat(tool.inputSchema().toString()).doesNotContain("OpenSearch", "Loki", "CloudWatch");
      }
      for (String type : List.of("gcp-logging", "loki", "cloudwatch")) {
        try (var connector = new BackendConnector(type, cloud)) {
          for (ToolDefinition tool : connector.manifest().tools()) {
            assertThat(tool.description()).contains(tool.name()).doesNotContain("opensearch", "call logs_search again", "to logs_tail");
            assertThat(tool.outputSchema().at("/properties/backend/const").asString()).isEqualTo(type);
          }
        }
      }
    }
  }

  @Test
  void aliasesExecuteTheirBackendAndOnlyGeneratedContinuationGuidanceChanges() throws Exception {
    try (var server = new MockWebServer(); var cloud = cloud(); var connector = new BackendConnector("loki", cloud)) {
      server.start();
      server.enqueue(json("""
          {"status":"success","data":{"resultType":"streams","result":[{"stream":{"app":"api"},"values":[
          ["1792672198000000000","literal logs_search data"],["1792672190000000000","older"]]}]}}"""));
      ConnectionConfig cfg = typed("loki", Configs.loki(server.url("/").toString()));
      var call = Configs.call("loki_search", """
          {"query":"{app=\\"api\\"}","from":"2026-10-22T12:00:00Z","to":"2026-10-22T13:00:00Z","limit":1}""");
      assertThat(connector.classify(call, cfg, Configs.policy(Env.DEV, TestCtx.dev())).kind())
          .isEqualTo(Classification.Kind.READ);
      var result = connector.execute(call, cfg, new TestSecrets().with("/auth/secret", "example-token"), TestCtx.of());
      assertThat(server.takeRequest(1, TimeUnit.SECONDS).getUrl().encodedPath()).isEqualTo("/loki/api/v1/query_range");
      assertThat(result.structuredContent().at("/entries/0/message").asString()).isEqualTo("literal logs_search data");
      assertThat(result.content().getFirst().text()).contains("literal logs_search data", "call loki_search again");
      assertThat(SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
          .getSchema(connector.manifest().tools().getFirst().outputSchema()).validate(result.structuredContent())).isEmpty();
    }
  }

  @Test
  void approvalKeepsThePreparedTimeRangeAndRejectsAnotherOperation() throws Exception {
    Clock clock = Clock.fixed(Instant.parse("2026-10-22T12:30:00Z"), ZoneOffset.UTC);
    try (var server = new MockWebServer(); var cloud = cloud(); var connector = new BackendConnector("loki", cloud, clock)) {
      server.start();
      ConnectionConfig cfg = typed("loki", Configs.loki(server.url("/").toString()));
      var call = Configs.call("loki_search", "{\"query\":\"{app=\\\"api\\\"}\",\"from\":\"-1h\"}");
      var action = connector.describeAction(call, cfg);
      assertThat(action.text()).startsWith("loki_search · ");
      assertThat(action.payload().path("tool").asString()).isEqualTo("logs_search");
      assertThatThrownBy(() -> connector.execute(Configs.call("loki_tail", "{\"query\":\"{app=\\\"api\\\"}\"}"),
          cfg, new TestSecrets(), TestCtx.of().approved(action.payload())))
          .isInstanceOf(ConnectorException.class).hasMessageContaining("does not match");
      assertThat(server.getRequestCount()).isZero();
      server.enqueue(json("{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[]}}"));
      connector.execute(call, cfg, new TestSecrets().with("/auth/secret", "example-token"),
          TestCtx.of().approved(action.payload()));
      assertThat(FakeLoki.query(server.takeRequest(1, TimeUnit.SECONDS).getUrl().encodedQuery()))
          .containsEntry("start", "1792668600000000000");
    }
  }

  @Test
  void serviceCannotBeChangedToAnotherBackendOrCallItsTools() {
    try (var cloud = cloud(); var connector = new BackendConnector("gcp-logging", cloud)) {
      ConnectionConfig gcp = typed("gcp-logging", Configs.gcp());
      assertThat(connector.supportsTool("opensearch_request", gcp)).isFalse();
      assertThat(connector.supportsTool("logs_search", gcp)).isFalse();
      assertThat(connector.classify(Configs.call("opensearch_request", "{}"), gcp,
          Configs.policy(Env.DEV, TestCtx.dev())).kind()).isEqualTo(Classification.Kind.DENY);
      ConnectionConfig wrongBackend = typed("gcp-logging", Configs.openSearch("https://search.example.test"));
      assertThat(connector.supportsTool("gcp_logging_search", wrongBackend)).isFalse();
      assertThatThrownBy(() -> connector.execute(Configs.call("gcp_logging_search", "{}"), wrongBackend,
          new TestSecrets(), TestCtx.of())).isInstanceOf(ConnectorException.class).hasMessageContaining("GCP Cloud Logging");
    }
  }

  private static CloudCredentials cloud() {
    return new CloudCredentials(CloudEndpoints.PRODUCTION, Clock.systemUTC());
  }

  private static ConnectionConfig typed(String type, ConnectionConfig cfg) {
    return new ConnectionConfig(cfg.id(), cfg.name(), type, cfg.env(), cfg.version(), cfg.config(), cfg.secretFields(), cfg.metadata());
  }

  private static MockResponse json(String body) {
    return new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build();
  }
}
