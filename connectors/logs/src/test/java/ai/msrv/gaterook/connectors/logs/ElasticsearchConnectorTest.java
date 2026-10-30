package ai.msrv.gaterook.connectors.logs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Elasticsearch exposes its own service identity while retaining the shared REST authorization guarantees. */
class ElasticsearchConnectorTest {
  private final MockWebServer server = new MockWebServer();
  private final CloudCredentials cloud = new CloudCredentials(CloudEndpoints.PRODUCTION, Clock.systemUTC());
  private final BackendConnector connector = new BackendConnector("elasticsearch", cloud);
  private final TestSecrets secrets = new TestSecrets().with("/auth/secret", "aWQ6a2V5");
  private ConnectionConfig cfg;

  @BeforeEach
  void start() throws IOException {
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    cfg = configuration("{\"mode\":\"none\"}");
  }

  @AfterEach
  void stop() throws IOException {
    connector.close();
    cloud.close();
    server.close();
  }

  @Test
  void catalogAndDescriptionsContainOnlyElasticsearchDatabaseTools() throws Exception {
    var manifest = connector.manifest();
    assertThat(manifest.id()).isEqualTo("elasticsearch");
    assertThat(manifest.name()).isEqualTo("Elasticsearch");
    assertThat(manifest.group()).isEqualTo("Databases");
    assertThat(manifest.policySchema()).isNull();
    assertThat(manifest.tools()).extracting(ToolDefinition::name)
        .containsExactly("elasticsearch_indices", "elasticsearch_query", "elasticsearch_request");
    assertThat(manifest.tools()).extracting(ToolDefinition::defaultMode)
        .containsExactly(ToolDefinition.Mode.READ_ONLY, ToolDefinition.Mode.READ_ONLY, ToolDefinition.Mode.APPROVAL);
    assertThat(manifest.tools()).extracting(ToolDefinition::prodDefaultMode)
        .containsExactly(ToolDefinition.Mode.READ_ONLY, ToolDefinition.Mode.READ_ONLY, ToolDefinition.Mode.APPROVAL);
    for (var tool : manifest.tools()) {
      assertThat(tool.description()).contains("Elasticsearch").doesNotContain("OpenSearch", "PPL", "opensearch_");
      assertThat(tool.inputSchema().toString()).doesNotContain("OpenSearch", "logs_search", "logs_tail");
      assertThat(connector.supportsTool(tool.name(), cfg)).isTrue();
    }
    var description = connector.describe(cfg, secrets, TestCtx.of(), null).orElseThrow();
    assertThat(description.path("language").asString()).isEqualTo("Elasticsearch REST / JSON Query DSL");
    assertThat(description.path("backend").asString()).isEqualTo("elasticsearch");
    assertThat(description.toString()).contains("elasticsearch_indices", "elasticsearch_query", "elasticsearch_request", "/_sql", "ES|QL")
        .doesNotContain("opensearch", "OpenSearch", "logs_search", "logs_tail", "PPL", "_plugins", "sources");
    assertThat(server.getRequestCount()).isZero();
  }

  @ParameterizedTest
  @CsvSource({"basic, Basic cmVhZGVyOmFXUTZhMlY1", "apiKey, ApiKey aWQ6a2V5", "bearer, Bearer aWQ6a2V5", "none, NONE"})
  void selectedAuthenticationReachesTheWireWithoutReencodingApiKeys(String mode, String expected) throws Exception {
    ConnectionConfig auth = configuration("{\"mode\":\"" + mode + "\",\"username\":\"reader\"}");
    server.enqueue(json(200, "{\"hits\":{\"hits\":[]}}"));
    ToolCall call = Configs.call("elasticsearch_query", """
        {"method":"POST","path":"/products/_search","query":{"routing":"team a/+&?"},
         "body":{"query":{"match":{"message":"literal OpenSearch opensearch_query"}}}}""");
    TestCtx context = TestCtx.of();
    connector.execute(call, auth, secrets, context);
    RecordedRequest request = take();
    assertThat(request.getMethod()).isEqualTo("POST");
    assertThat(TestCtx.JSON.readTree(request.getBody().utf8())).isEqualTo(call.arguments().path("body"));
    assertThat(request.getUrl().queryParameter("routing")).isEqualTo("team a/+&?");
    assertThat(request.getHeaders().get("Authorization")).isEqualTo(expected.equals("NONE") ? null : expected);
    if (!expected.equals("NONE")) assertThat(context.taints).contains(expected);
  }

  @Test
  void discoversIndicesAndKeepsServerDataAndErrorsUnchanged() throws Exception {
    server.enqueue(json(200, "{\"indices\":[{\"name\":\"opensearch_literal\"}],\"aliases\":[],\"data_streams\":[]}"));
    var result = connector.execute(Configs.call("elasticsearch_indices", "{}"), cfg, secrets, TestCtx.of());
    assertThat(result.structuredContent().at("/body/indices/0/name").asString()).isEqualTo("opensearch_literal");
    assertThat(take().getUrl().encodedPath()).isEqualTo("/_resolve/index/*");
    assertThat(SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        .getSchema(connector.manifest().tools().getFirst().outputSchema()).validate(result.structuredContent())).isEmpty();
    String error = "{\"error\":\"literal OpenSearch opensearch_request\",\"status\":404}";
    server.enqueue(json(404, error));
    var response = connector.execute(Configs.call("elasticsearch_query", "{\"path\":\"/missing/_search\"}"), cfg, secrets, TestCtx.of());
    assertThat(response.structuredContent().path("status").asInt()).isEqualTo(404);
    assertThat(response.structuredContent().path("body")).isEqualTo(TestCtx.JSON.readTree(error));
  }

  @Test
  void approvedNdjsonUsesPreparedBytesAndCannotCrossServiceToolOrServer() throws Exception {
    String ndjson = "{\"index\":{\"_index\":\"products\"}}\n{\"name\":\"OpenSearch opensearch_request\"}";
    ObjectNode args = TestCtx.JSON.createObjectNode().put("method", "POST").put("path", "/_bulk")
        .put("contentType", "application/x-ndjson").put("body", ndjson);
    ToolCall call = Configs.call("elasticsearch_request", args.toString());
    var preview = connector.describeAction(call, cfg);
    assertThat(preview.payload().path("tool").asString()).isEqualTo("elasticsearch_request");
    assertThat(preview.payload().path("body").asString()).isEqualTo(ndjson + "\n");
    assertThatThrownBy(() -> connector.execute(call, cfg, secrets, TestCtx.of())).hasMessageContaining("authorization");
    assertThatThrownBy(() -> connector.execute(Configs.call("elasticsearch_query", "{\"path\":\"/\"}"), cfg,
        secrets, TestCtx.of().approved(preview.payload()))).hasMessageContaining("does not match");
    ObjectNode different = (ObjectNode) cfg.config().deepCopy();
    different.put("url", "http://127.0.0.1:1");
    assertThatThrownBy(() -> connector.execute(call, typed("elasticsearch", different), secrets,
        TestCtx.of().approved(preview.payload()))).hasMessageContaining("does not match");
    try (var opensearch = new BackendConnector("opensearch", cloud)) {
      different.put("backend", "opensearch").put("url", "http://127.0.0.1:" + server.getPort());
      assertThatThrownBy(() -> opensearch.execute(Configs.call("opensearch_request", args.toString()),
          typed("opensearch", different), secrets, TestCtx.of().approved(preview.payload())))
          .hasMessageContaining("does not match");
    }
    assertThat(server.getRequestCount()).isZero();
    server.enqueue(json(200, "{\"errors\":true,\"items\":[{\"index\":{\"status\":409}}]}"));
    var response = connector.execute(Configs.call("elasticsearch_request", "{\"method\":\"DELETE\",\"path\":\"/products\"}"),
        cfg, secrets, TestCtx.of().approved(preview.payload()));
    assertThat(response.structuredContent().at("/body/errors").asBoolean()).isTrue();
    RecordedRequest sent = take();
    assertThat(sent.getMethod()).isEqualTo("POST");
    assertThat(sent.getBody().utf8()).isEqualTo(ndjson + "\n");
    assertThat(sent.getHeaders().get("Content-Type")).isEqualTo("application/x-ndjson");
  }

  @ParameterizedTest
  @ValueSource(strings = {"/_query", "/_sql", "/_reindex", "/products/_delete_by_query", "/_security/api_key", "/_async_search"})
  void nativeApisOutsideKnownReadsRequireApprovalEvenOnGet(String path) {
    String args = "{\"path\":\"" + path + "\"}";
    assertThat(connector.classify(Configs.call("elasticsearch_query", args), cfg, Configs.policy(Env.DEV, TestCtx.dev())).kind())
        .isEqualTo(Classification.Kind.DENY);
    ToolCall request = Configs.call("elasticsearch_request", args);
    assertThat(connector.classify(request, cfg, Configs.policy(Env.DEV, TestCtx.dev())).kind())
        .isEqualTo(Classification.Kind.MUTATION);
    assertThatThrownBy(() -> connector.execute(request, cfg, secrets, TestCtx.of())).hasMessageContaining("authorization");
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void settingsAndToolIsolationRejectAwsSigningLegacyLogsAndOtherServiceTypes() {
    ToolCall read = Configs.call("elasticsearch_query", "{\"path\":\"/\"}");
    for (String tool : List.of("logs_search", "opensearch_query", "opensearch_request")) {
      assertThat(connector.supportsTool(tool, cfg)).isFalse();
      assertThatThrownBy(() -> connector.execute(Configs.call(tool, "{}"), cfg, secrets, TestCtx.of()))
          .isInstanceOf(ConnectorException.class);
    }
    ObjectNode changed = (ObjectNode) cfg.config().deepCopy();
    changed.put("backend", "opensearch");
    assertThat(connector.supportsTool(read.tool(), typed("elasticsearch", changed))).isFalse();
    assertThat(connector.supportsTool(read.tool(), typed("logs", cfg.config()))).isFalse();
    changed.put("backend", "elasticsearch").put("apiAccess", "logs");
    assertThat(connector.classify(read, typed("elasticsearch", changed), Configs.policy(Env.DEV, TestCtx.dev())).kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThat(connector.classify(read, configuration("{\"mode\":\"awsSigV4\"}"), Configs.policy(Env.DEV, TestCtx.dev())).kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThat(server.getRequestCount()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"https://elsewhere.example.test/_search", "//elsewhere.example.test/_search", "/../_search", "/%2e%2e/_search", "/%252e%252e/_search", "/_search#fragment"})
  void invalidPathsNeverReachTheServer(String path) {
    var args = TestCtx.JSON.createObjectNode().put("path", path);
    ToolCall call = Configs.call("elasticsearch_query", args.toString());
    assertThat(connector.classify(call, cfg, Configs.policy(Env.DEV, TestCtx.dev())).kind()).isEqualTo(Classification.Kind.DENY);
    assertThatThrownBy(() -> connector.execute(call, cfg, secrets, TestCtx.of())).isInstanceOf(ConnectorException.class);
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void responseLimitsAndRequestLimitsApplyToElasticsearch() throws Exception {
    Limits d = TestCtx.dev();
    Limits small = new Limits(d.maxRows(), d.maxLines(), 1024, 128, d.maxOutputBytes(), d.maxStderrBytes(),
        d.maxCellChars(), d.statementTimeout(), d.commandTimeout(), d.toolTimeout(), d.maxRange());
    server.enqueue(json(200, "{\"value\":\"" + "x".repeat(8192) + "\"}"));
    var response = connector.execute(Configs.call("elasticsearch_query", "{\"path\":\"/_search\"}"), cfg, secrets, TestCtx.of().with(small));
    assertThat(response.truncated()).isTrue();
    assertThat(response.structuredContent().path("truncated").asBoolean()).isTrue();
    take();
    var args = TestCtx.JSON.createObjectNode().put("path", "/_search").put("body", "\"" + "x".repeat(1024) + "\"");
    assertThatThrownBy(() -> connector.execute(Configs.call("elasticsearch_query", args.toString()), cfg, secrets,
        TestCtx.of().with(small))).hasMessageContaining("maxRequestBytes");
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  void neitherRedirectsNorLostResponsesReplayApprovedRequests() throws Exception {
    ToolCall call = Configs.call("elasticsearch_request", "{\"path\":\"/_query\"}");
    JsonNode payload = connector.describeAction(call, cfg).payload();
    server.enqueue(new MockResponse.Builder().code(307).addHeader("Location", "http://127.0.0.1:1/other").build());
    var response = connector.execute(call, cfg, secrets, TestCtx.of().approved(payload));
    assertThat(response.structuredContent().path("status").asInt()).isEqualTo(307);
    take();
    server.enqueue(new MockResponse.Builder().onResponseStart(new mockwebserver3.SocketEffect.CloseSocket()).build());
    server.enqueue(json(200, "{\"acknowledged\":true}"));
    assertThatThrownBy(() -> connector.execute(call, cfg, secrets, TestCtx.of().approved(payload)))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN));
    assertThat(server.getRequestCount()).isEqualTo(2);
  }

  @Test
  void stalledResponsesRespectDeadlineWithoutRetry() {
    server.enqueue(new MockResponse.Builder().code(200).body("{\"acknowledged\":true}").bodyDelay(2, TimeUnit.SECONDS).build());
    ToolCall call = Configs.call("elasticsearch_request", "{\"path\":\"/_query\"}");
    var context = TestCtx.of().approved(connector.describeAction(call, cfg).payload()).timeout(Duration.ofMillis(650));
    assertThatThrownBy(() -> connector.execute(call, cfg, secrets, context))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT));
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void customCaKeepsHostnameVerification(boolean correctHostname) throws Exception {
    var ca = new HeldCertificate.Builder().commonName("Elasticsearch Test Root").certificateAuthority(0).build();
    var leaf = new HeldCertificate.Builder().commonName("Elasticsearch Test Server")
        .addSubjectAlternativeName(correctHostname ? "127.0.0.1" : "different.example.invalid").signedBy(ca).build();
    try (var tls = new MockWebServer()) {
      tls.useHttps(new HandshakeCertificates.Builder().heldCertificate(leaf, ca.certificate()).build().sslSocketFactory());
      tls.start(InetAddress.getByName("127.0.0.1"), 0);
      tls.enqueue(json(200, "{\"status\":\"green\"}"));
      var config = (ObjectNode) cfg.config().deepCopy();
      config.put("url", "https://127.0.0.1:" + tls.getPort()).put("caCertificate", ca.certificatePem());
      var call = Configs.call("elasticsearch_query", "{\"path\":\"/_cluster/health\"}");
      if (correctHostname) {
        assertThat(connector.execute(call, typed("elasticsearch", config), secrets, TestCtx.of()).structuredContent().path("status").asInt()).isEqualTo(200);
      } else {
        assertThatThrownBy(() -> connector.execute(call, typed("elasticsearch", config), secrets, TestCtx.of()))
            .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE));
        assertThat(tls.getRequestCount()).isZero();
      }
    }
  }

  private ConnectionConfig configuration(String auth) {
    return typed("elasticsearch", TestCtx.JSON.readTree("""
        {"backend":"elasticsearch","url":"http://127.0.0.1:%d","auth":%s}""".formatted(server.getPort(), auth)));
  }

  private static ConnectionConfig typed(String type, JsonNode config) {
    return new ConnectionConfig("c-search", "search", type, Env.DEV, 1, config, java.util.Set.of("/auth/secret"), null);
  }

  private RecordedRequest take() throws InterruptedException {
    RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
    assertThat(request).isNotNull();
    return request;
  }

  private static MockResponse json(int status, String body) {
    return new MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(body).build();
  }
}
