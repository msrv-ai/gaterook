package ai.msrv.gaterook.connectors.logs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Dedicated Elasticsearch tools against the pinned Compose server. Run with {@code -Pservices}; unlike the
 * OpenSearch fixture, this test does not download or launch a platform-specific server outside Compose.
 * Every mutation targets a unique test index, which is removed before the test ends.
 */
@Tag("logs-it")
class ElasticsearchIT {

  private static final TestSecrets NO_SECRETS = new TestSecrets();
  private static CloudCredentials cloud;
  private static BackendConnector connector;
  private static ConnectionConfig config;

  @BeforeAll
  static void connectToElasticsearch() throws Exception {
    assumeTrue(TestServices.enabled(), "ElasticsearchIT requires the Compose services (-Pservices)");
    URI base = URI.create("http://127.0.0.1:" + TestServices.port("GW_ELASTICSEARCH_PORT"));
    Fixtures.awaitOk(base.resolve("/_cluster/health?wait_for_status=yellow&timeout=1s"), Duration.ofSeconds(120),
        null, null);
    cloud = new CloudCredentials(CloudEndpoints.PRODUCTION, Clock.systemUTC());
    connector = new BackendConnector("elasticsearch", cloud);
    config = new ConnectionConfig("elasticsearch-it", "elasticsearch-it", "elasticsearch", Env.DEV, 1,
        TestCtx.JSON.readTree("""
            {"backend":"elasticsearch","url":"%s","apiAccess":"full","auth":{"mode":"none"}}
            """.formatted(base)), Set.of(), null);
  }

  @AfterAll
  static void closeConnector() {
    if (connector != null) connector.close();
    if (cloud != null) cloud.close();
  }

  @Test
  void createsDiscoversBulkIndexesQueriesConfiguresAndDeletesAnIndex() throws Exception {
    String index = "gaterook-elasticsearch-it-" + UUID.randomUUID();
    try {
      assertThat(request("PUT", "/" + index, """
          {"settings":{"number_of_shards":1,"number_of_replicas":0},
           "mappings":{"properties":{"category":{"type":"keyword"},"price":{"type":"integer"}}}}
          """).path("status").asInt()).isEqualTo(200);
      JsonNode inventory = execute(Configs.call("elasticsearch_indices", "{\"index\":\"" + index + "\"}"),
          TestCtx.of());
      assertThat(inventory.at("/body/indices/0/name").asString()).isEqualTo(index);

      ObjectNode bulk = TestCtx.JSON.createObjectNode().put("method", "POST").put("path", "/" + index + "/_bulk")
          .put("contentType", "application/x-ndjson").put("body",
              "{\"index\":{\"_id\":\"1\"}}\n{\"category\":\"books\",\"price\":12}\n"
                  + "{\"index\":{\"_id\":\"2\"}}\n{\"category\":\"books\",\"price\":18}\n");
      bulk.putObject("query").put("refresh", true);
      JsonNode inserted = approved(Configs.call("elasticsearch_request", bulk.toString()));
      assertThat(inserted.at("/body/errors").asBoolean()).isFalse();
      assertThat(inserted.at("/body/items").size()).isEqualTo(2);

      ObjectNode search = TestCtx.JSON.createObjectNode().put("method", "GET").put("path", "/" + index + "/_search");
      search.set("body", TestCtx.JSON.readTree("""
          {"size":0,"query":{"term":{"category":"books"}},"aggs":{"total":{"sum":{"field":"price"}}}}
          """));
      ToolCall query = Configs.call("elasticsearch_query", search.toString());
      assertThat(connector.classify(query, config, Configs.policy(Env.DEV, TestCtx.dev())).kind())
          .isEqualTo(Classification.Kind.READ);
      JsonNode found = execute(query, TestCtx.of());
      assertThat(found.at("/body/hits/total/value").asInt()).isEqualTo(2);
      assertThat(found.at("/body/aggregations/total/value").asDouble()).isEqualTo(30);

      assertThat(request("PUT", "/" + index + "/_settings", "{\"index\":{\"refresh_interval\":\"2s\"}}")
          .path("status").asInt()).isEqualTo(200);
      JsonNode settings = execute(Configs.call("elasticsearch_query", "{\"path\":\"/" + index + "/_settings\"}"),
          TestCtx.of());
      assertThat(settings.path("body").path(index).at("/settings/index/refresh_interval").asString()).isEqualTo("2s");

      assertThat(request("DELETE", "/" + index, null).path("status").asInt()).isEqualTo(200);
      JsonNode missing = execute(Configs.call("elasticsearch_query", "{\"path\":\"/" + index + "/_search\"}"),
          TestCtx.of());
      assertThat(missing.path("status").asInt()).isEqualTo(404);
      assertThat(missing.at("/body/error/type").asString()).isEqualTo("index_not_found_exception");
    } finally {
      request("DELETE", "/" + index, null);
    }
  }

  @Test
  void requiresApprovalBeforeCreatingAnIndexAndKeepsMalformedQueryErrors() throws Exception {
    String index = "gaterook-elasticsearch-approval-it-" + UUID.randomUUID();
    ToolCall create = Configs.call("elasticsearch_request", "{\"method\":\"PUT\",\"path\":\"/" + index + "\"}");
    try {
      assertThat(connector.classify(create, config, Configs.policy(Env.DEV, TestCtx.dev())).kind())
          .isEqualTo(Classification.Kind.MUTATION);
      assertThatThrownBy(() -> connector.execute(create, config, NO_SECRETS, TestCtx.of()))
          .isInstanceOfSatisfying(ConnectorException.class,
              error -> assertThat(error.kind()).isEqualTo(ConnectorException.Kind.DENIED));
      assertThat(execute(Configs.call("elasticsearch_query", "{\"method\":\"HEAD\",\"path\":\"/" + index + "\"}"),
          TestCtx.of()).path("status").asInt()).isEqualTo(404);
      assertThat(approved(create).path("status").asInt()).isEqualTo(200);
      ObjectNode badQuery = TestCtx.JSON.createObjectNode().put("path", "/" + index + "/_search");
      badQuery.set("body", TestCtx.JSON.readTree("{\"query\":{\"not_a_query\":{}}}"));
      JsonNode error = execute(Configs.call("elasticsearch_query", badQuery.toString()), TestCtx.of());
      assertThat(error.path("status").asInt()).isEqualTo(400);
      assertThat(error.at("/body/error").isObject()).isTrue();
    } finally {
      request("DELETE", "/" + index, null);
    }
  }

  @Test
  void nativeEsqlUsesTheApprovedRestRequestTool() throws Exception {
    String index = "gaterook-elasticsearch-esql-it-" + UUID.randomUUID();
    try {
      assertThat(request("PUT", "/" + index,
          "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0}}")
          .path("status").asInt()).isEqualTo(200);
      assertThat(request("PUT", "/" + index + "/_doc/1?refresh=true", "{\"category\":\"books\"}")
          .path("status").asInt()).isEqualTo(201);
      JsonNode response = request("POST", "/_query",
          TestCtx.JSON.createObjectNode().put("query", "FROM " + index + " | STATS n = COUNT(*)").toString());
      assertThat(response.path("status").asInt()).as(response.toString()).isEqualTo(200);
      assertThat(response.at("/body/columns/0/name").asString()).isEqualTo("n");
      assertThat(response.at("/body/values/0/0").asInt()).isEqualTo(1);
    } finally {
      request("DELETE", "/" + index, null);
    }
  }

  @Test
  void reportsElasticsearchVersionAndHealth() {
    TestResult result = connector.test(config, NO_SECRETS, TestCtx.of());
    assertThat(result.ok()).as(result.toString()).isTrue();
    assertThat(result.serverVersion()).isEqualTo("Elasticsearch " + TestServices.value("GW_ELASTICSEARCH_TAG"));
    assertThat(connector.health(config, NO_SECRETS, TestCtx.of()).state()).isNotEqualTo(HealthResult.State.UNREACHABLE);
  }

  private static JsonNode request(String method, String path, String body) throws Exception {
    ObjectNode args = TestCtx.JSON.createObjectNode().put("method", method).put("path", path);
    if (body != null) args.set("body", TestCtx.JSON.readTree(body));
    return approved(Configs.call("elasticsearch_request", args.toString()));
  }

  private static JsonNode approved(ToolCall call) throws Exception {
    return execute(call, TestCtx.of().approved(connector.describeAction(call, config).payload()));
  }

  private static JsonNode execute(ToolCall call, TestCtx context) throws Exception {
    JsonNode result = connector.execute(call, config, NO_SECRETS, context).structuredContent();
    // Mutation tools omit outputSchema to permit approval responses, but REST results share the query envelope.
    JsonNode schema = connector.manifest().tools().stream().filter(tool -> tool.name().equals("elasticsearch_query"))
        .findFirst().orElseThrow().outputSchema();
    assertThat(SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schema)
        .validate(result)).as(call.tool() + " output schema").isEmpty();
    return result;
  }
}
