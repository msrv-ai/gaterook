package ai.msrv.gaterook.connectors.logs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
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
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Public OpenSearch tools preserve REST semantics and the gateway's approval boundary. */
class OpenSearchApiTest {
  private static final Schema OUTPUT = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
      .getSchema(SchemaLocation.of("urn:mcp-gateway:opensearch.output"),
          SchemaResources.of(LogsConnector.class, "logs").load("opensearch.output"));
  private final MockWebServer server = new MockWebServer();
  private final CloudCredentials cloud = new CloudCredentials(CloudEndpoints.PRODUCTION, Clock.systemUTC());
  private final LogsConnector connector = new LogsConnector(cloud, Clock.systemUTC());
  private final TestSecrets secrets = new TestSecrets().with("/auth/secret", "os-api-canary");
  private ConnectionConfig full;
  private ConnectionConfig scoped;

  @BeforeEach
  void start() throws IOException {
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    scoped = Configs.openSearch("http://127.0.0.1:" + server.getPort());
    ObjectNode config = (ObjectNode) scoped.config().deepCopy();
    config.remove("apiAccess");
    full = Configs.of("search-full", Env.DEV, config.toString(), "/auth/secret");
  }

  @AfterEach
  void stop() throws IOException {
    connector.close();
    cloud.close();
    server.close();
  }

  @Test
  void fullAccessIsTheDefaultButExplicitLogOnlyAndOtherBackendsRemainRestricted() {
    for (String tool : List.of("opensearch_query", "opensearch_request")) {
      ToolCall call = Configs.call(tool, "{\"path\":\"/_cluster/settings\"}");
      assertThat(connector.classify(call, scoped, Configs.policy(Env.DEV, TestCtx.dev())).kind())
          .isEqualTo(Classification.Kind.DENY);
      assertThatThrownBy(() -> connector.execute(call, scoped, secrets, TestCtx.of()))
          .isInstanceOf(ConnectorException.class);
      assertThat(connector.supportsTool(tool, scoped)).isFalse();
      assertThat(connector.supportsTool(tool, full)).isTrue();
    }
    ConnectionConfig loki = Configs.loki("http://127.0.0.1:" + server.getPort());
    assertThat(connector.supportsTool("opensearch_indices", loki)).isFalse();
    assertThat(connector.classify(Configs.call("opensearch_indices", "{}"), loki,
        Configs.policy(Env.DEV, TestCtx.dev())).kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void explicitLogOnlyModeRestrictsDiscoveryWhileDefaultFullAccessListsAll() throws Exception {
    String inventory = "{\"indices\":[{\"name\":\"logs-app-1\",\"attributes\":[\"open\"]}],"
        + "\"aliases\":[],\"data_streams\":[]}";
    server.enqueue(json(200, inventory));
    JsonNode result = result(connector.execute(Configs.call("opensearch_indices", "{}"), scoped, secrets,
        TestCtx.of()));
    assertThat(result.path("body")).isEqualTo(TestCtx.JSON.readTree(inventory));
    assertThat(take().getUrl().encodedPath()).isEqualTo("/_resolve/index/logs-app-*");
    ToolCall outside = Configs.call("opensearch_indices", "{\"index\":\"private-*\"}");
    assertThat(connector.classify(outside, scoped, Configs.policy(Env.DEV, TestCtx.dev())).kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThatThrownBy(() -> connector.execute(outside, scoped, secrets, TestCtx.of()))
        .isInstanceOf(ConnectorException.class);
    server.enqueue(json(200, inventory));
    connector.execute(Configs.call("opensearch_indices", "{\"expandWildcards\":\"all\"}"), full, secrets,
        TestCtx.of());
    RecordedRequest request = take();
    assertThat(request.getUrl().encodedPath()).isEqualTo("/_resolve/index/*");
    assertThat(request.getUrl().queryParameter("expand_wildcards")).isEqualTo("all");
  }

  @Test
  void executesUnmodifiedDslAndReturnsAggregationsAndNativeCursors() throws Exception {
    ToolCall call = Configs.call("opensearch_query", """
        {"method":"POST","path":"/products/_search","query":{"routing":"team a/+&?","size":0,
          "filter_path":["aggregations","_scroll_id"]},
         "body":{"size":0,"query":{"match_all":{}},"aggs":{"categories":{"terms":{"field":"category"}}}}}
        """);
    String response = "{\"took\":2,\"_scroll_id\":\"opaque-cursor\",\"aggregations\":{\"categories\":{"
        + "\"buckets\":[{\"key\":\"books\",\"doc_count\":7}]}}}";
    server.enqueue(json(200, response));
    assertThat(connector.classify(call, full, Configs.policy(Env.DEV, TestCtx.dev())).kind())
        .isEqualTo(Classification.Kind.READ);
    JsonNode result = result(connector.execute(call, full, secrets, TestCtx.of()));
    assertThat(result.path("body")).isEqualTo(TestCtx.JSON.readTree(response));
    assertThat(result.path("status").asInt()).isEqualTo(200);
    assertThat(result.path("truncated").asBoolean()).isFalse();
    RecordedRequest request = take();
    assertThat(request.getUrl().queryParameter("routing")).isEqualTo("team a/+&?");
    assertThat(request.getUrl().queryParameterValues("filter_path")).containsExactly("aggregations", "_scroll_id");
    assertThat(TestCtx.JSON.readTree(request.getBody().utf8())).isEqualTo(call.arguments().path("body"));
    assertThat(request.getHeaders().get("Authorization")).isEqualTo("Basic " + Base64.getEncoder()
        .encodeToString("gw_reader:os-api-canary".getBytes(StandardCharsets.UTF_8)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"/products/_settings", "/_cluster/settings"})
  void settingsReadAndWriteHaveDifferentAuthorization(String path) throws Exception {
    ToolCall read = Configs.call("opensearch_query", "{\"path\":\"" + path + "\"}");
    assertThat(connector.classify(read, full, Configs.policy(Env.DEV, TestCtx.dev())).kind())
        .isEqualTo(Classification.Kind.READ);
    ToolCall write = Configs.call("opensearch_request", "{\"method\":\"PUT\",\"path\":\"" + path
        + "\",\"body\":{\"persistent\":{\"cluster.routing.allocation.enable\":\"all\"}}}");
    assertThat(connector.classify(write, full, Configs.policy(Env.DEV, TestCtx.dev())).kind())
        .isEqualTo(Classification.Kind.MUTATION);
    assertThatThrownBy(() -> connector.execute(write, full, secrets, TestCtx.of()))
        .isInstanceOf(ConnectorException.class);
    assertThat(server.getRequestCount()).isZero();
    ActionPreview preview = connector.describeAction(write, full);
    server.enqueue(json(200, "{\"acknowledged\":true}"));
    result(connector.execute(write, full, secrets, TestCtx.of().approved(preview.payload())));
    RecordedRequest request = take();
    assertThat(request.getMethod()).isEqualTo("PUT");
    assertThat(request.getUrl().encodedPath()).isEqualTo(path);
    assertThat(request.getBody().utf8()).isEqualTo(preview.payload().path("body").asString());
  }

  @Test
  void executesTheApprovedPatchArrayEvenIfCurrentArgumentsDiffer() throws Exception {
    ToolCall original = Configs.call("opensearch_request", """
        {"method":"PATCH","path":"/_plugins/_security/api/roles/catalog-reader",
         "body":[{"op":"replace","path":"/cluster_permissions","value":["cluster_monitor"]}]}
        """);
    ActionPreview preview = connector.describeAction(original, full);
    ToolCall changed = Configs.call("opensearch_request", "{\"method\":\"DELETE\",\"path\":\"/products\"}");
    server.enqueue(json(200, "{\"status\":\"OK\"}"));
    result(connector.execute(changed, full, secrets, TestCtx.of().approved(preview.payload())));
    RecordedRequest request = take();
    assertThat(request.getMethod()).isEqualTo("PATCH");
    assertThat(request.getUrl().encodedPath()).isEqualTo("/_plugins/_security/api/roles/catalog-reader");
    assertThat(TestCtx.JSON.readTree(request.getBody().utf8())).isEqualTo(original.arguments().path("body"));
  }

  @Test
  void bulkApprovalAndWireUseTheSameNdjsonAndKeepItemErrors() throws Exception {
    String ndjson = "{\"index\":{\"_index\":\"products\",\"_id\":\"1\"}}\n{\"title\":\"Book\"}";
    ObjectNode args = TestCtx.JSON.createObjectNode().put("method", "POST").put("path", "/_bulk")
        .put("contentType", "application/x-ndjson").put("body", ndjson);
    ToolCall call = Configs.call("opensearch_request", args.toString());
    ActionPreview preview = connector.describeAction(call, full);
    assertThat(preview.text()).endsWith(ndjson + "\n");
    String response = "{\"errors\":true,\"items\":[{\"index\":{\"status\":409,\"error\":{"
        + "\"type\":\"version_conflict_engine_exception\"}}}]}";
    server.enqueue(json(200, response));
    JsonNode result = result(connector.execute(call, full, secrets, TestCtx.of().approved(preview.payload())));
    assertThat(result.path("body")).isEqualTo(TestCtx.JSON.readTree(response));
    RecordedRequest request = take();
    assertThat(request.getBody().utf8()).isEqualTo(ndjson + "\n");
    assertThat(request.getHeaders().get("Content-Type")).isEqualTo("application/x-ndjson");
  }

  @ParameterizedTest
  @ValueSource(strings = {"/_plugins/_sql", "/_plugins/_ppl", "/_plugins/_ml/agents/id/_execute",
      "/_plugins/custom/_search", "/products/_delete_by_query", "/_reindex"})
  void unprovenQueriesRequireTheRequestTool(String path) {
    ObjectNode args = TestCtx.JSON.createObjectNode().put("method", "POST").put("path", path);
    args.putObject("body").put("query", "DELETE FROM products");
    ToolCall call = Configs.call("opensearch_query", args.toString());
    assertThat(connector.classify(call, full, Configs.policy(Env.DEV, TestCtx.dev())).kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThat(connector.classify(Configs.call("opensearch_request", args.toString()), full,
        Configs.policy(Env.DEV, TestCtx.dev())).kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(server.getRequestCount()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"https://attacker.invalid/_search", "//attacker.invalid/_search", "/../_search",
      "/%2e%2e/_search", "/%252e%252e/_search", "/%2f/attacker.invalid", "/a\\b", "/_search#fragment",
      "/_search%0d%0aHost:attacker.invalid"})
  void rejectsOriginAndAmbiguousPathChangesBeforeNetwork(String path) {
    ObjectNode args = TestCtx.JSON.createObjectNode().put("path", path);
    for (String tool : List.of("opensearch_query", "opensearch_request")) {
      ToolCall call = Configs.call(tool, args.toString());
      assertThat(connector.classify(call, full, Configs.policy(Env.DEV, TestCtx.dev())).kind())
          .isEqualTo(Classification.Kind.DENY);
      assertThatThrownBy(() -> connector.execute(call, full, secrets, TestCtx.of()))
          .isInstanceOf(ConnectorException.class);
    }
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void preservesHttpErrorsAndPlainTextInsteadOfInventingSuccess() throws Exception {
    String body = "{\"error\":{\"type\":\"index_not_found_exception\",\"reason\":\"no such index\"},\"status\":404}";
    server.enqueue(json(404, body));
    ToolResult error = connector.execute(Configs.call("opensearch_query", "{\"path\":\"/missing/_search\"}"),
        full, secrets, TestCtx.of());
    assertThat(result(error).path("status").asInt()).isEqualTo(404);
    assertThat(error.isError()).isFalse();
    assertThat(error.structuredContent().path("body")).isEqualTo(TestCtx.JSON.readTree(body));
    take();
    server.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type", "text/plain")
        .body("node-one cpu=12%\n").build());
    JsonNode text = result(connector.execute(Configs.call("opensearch_query", "{\"path\":\"/_nodes/hot_threads\"}"),
        full, secrets, TestCtx.of()));
    assertThat(text.path("body").asString()).isEqualTo("node-one cpu=12%\n");
    assertThat(text.path("contentType").asString()).startsWith("text/plain");
  }

  @Test
  void limitsResponsesAndRejectsOversizedRequests() throws Exception {
    Limits d = TestCtx.dev();
    Limits small = new Limits(d.maxRows(), d.maxLines(), 1024, 128, d.maxOutputBytes(), d.maxStderrBytes(),
        d.maxCellChars(), d.statementTimeout(), d.commandTimeout(), d.toolTimeout(), d.maxRange());
    server.enqueue(json(200, "{\"value\":\"" + "x".repeat(8192) + "\"}"));
    ToolResult result = connector.execute(Configs.call("opensearch_query", "{\"path\":\"/products/_search\"}"),
        full, secrets, TestCtx.of().with(small));
    assertThat(result(result).path("truncated").asBoolean()).isTrue();
    assertThat(result.truncated()).isTrue();
    long bytes = result.structuredContent().toString().getBytes(StandardCharsets.UTF_8).length
        + result.content().stream().mapToLong(c -> c.text().getBytes(StandardCharsets.UTF_8).length).sum();
    assertThat(bytes).isLessThanOrEqualTo(1024);
    take();
    ObjectNode args = TestCtx.JSON.createObjectNode().put("method", "POST").put("path", "/products/_search");
    args.putObject("body").put("query", "x".repeat(1024));
    ToolCall call = Configs.call("opensearch_query", args.toString());
    assertThat(connector.classify(call, full, Configs.policy(Env.DEV, small)).kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThatThrownBy(() -> connector.execute(call, full, secrets, TestCtx.of().with(small)))
        .isInstanceOf(ConnectorException.class);
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  void signsApprovedPutRequestsWithSigV4() throws Exception {
    ConnectionConfig aws = Configs.of("aws-api", Env.DEV, """
        {"backend":"opensearch","url":"http://127.0.0.1:%d","apiAccess":"full",
         "auth":{"mode":"awsSigV4","awsRegion":"eu-central-1","awsService":"es",
          "awsCredentials":{"source":"accessKey","accessKeyId":"AKIAIOSFODNN7EXAMPLE"}}}
        """.formatted(server.getPort()), "/auth/awsCredentials/secretAccessKey");
    ToolCall call = Configs.call("opensearch_request", """
        {"method":"PUT","path":"/products/_settings","body":{"index":{"number_of_replicas":0}}}
        """);
    ActionPreview preview = connector.describeAction(call, aws);
    TestCtx context = TestCtx.of().approved(preview.payload());
    server.enqueue(json(200, "{\"acknowledged\":true}"));
    result(connector.execute(call, aws, new TestSecrets().with("/auth/awsCredentials/secretAccessKey",
        "sigv4-api-canary"), context));
    RecordedRequest request = take();
    assertThat(request.getMethod()).isEqualTo("PUT");
    assertThat(request.getBody().utf8()).isEqualTo(preview.payload().path("body").asString());
    assertThat(request.getHeaders().get("Authorization")).startsWith("AWS4-HMAC-SHA256 ")
        .contains("/eu-central-1/es/aws4_request");
    assertThat(request.getHeaders().get("X-Amz-Date")).isNotBlank();
    assertThat(context.taints).contains("sigv4-api-canary");
  }

  @Test
  void fullApiConnectionTestDoesNotRequireLogIndicesOrTimestampFields() throws Exception {
    server.enqueue(json(200, "{\"version\":{\"distribution\":\"opensearch\",\"number\":\"3.9.0\"}}"));
    server.enqueue(json(200, "{\"indices\":[],\"aliases\":[],\"data_streams\":[]}"));
    var tested = connector.test(full, secrets, TestCtx.of());
    assertThat(tested.ok()).as(String.valueOf(tested.error())).isTrue();
    assertThat(tested.checks()).extracting(ai.msrv.gaterook.connector.api.TestResult.Check::name)
        .containsExactly("version", "indices");
    assertThat(take().getUrl().encodedPath()).isEqualTo("/");
    assertThat(take().getUrl().encodedPath()).isEqualTo("/_resolve/index/*");
    assertThat(server.getRequestCount()).isEqualTo(2);
  }

  @Test
  void describesRestExamplesAndTheScopeBoundaryWithoutAProbe() throws Exception {
    JsonNode description = connector.describe(full, secrets, TestCtx.of(), null).orElseThrow();
    assertThat(description.path("apiAccess").asString()).isEqualTo("full");
    assertThat(description.path("language").asString()).contains("REST");
    assertThat(description.path("scope").asString()).contains("Server credentials", "logs_search");
    assertThat(description.path("apiExamples").toString()).contains("/_cluster/settings", "/_plugins/_sql");
    assertThat(server.getRequestCount()).isZero();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void deadlineCoversBothHeadersAndAStalledBodyWithoutRetry(boolean stallBody) throws Exception {
    MockResponse.Builder delayed = new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
        .body("{\"acknowledged\":true}");
    if (stallBody) {
      delayed.bodyDelay(2, TimeUnit.SECONDS);
    } else {
      delayed.headersDelay(2, TimeUnit.SECONDS);
    }
    server.enqueue(delayed.build());
    ToolCall call = Configs.call("opensearch_request", """
        {"method":"PUT","path":"/products/_settings","body":{"index":{"number_of_replicas":0}}}
        """);
    TestCtx context = TestCtx.of().approved(connector.describeAction(call, full).payload())
        .timeout(Duration.ofMillis(650));
    long start = System.nanoTime();
    assertThatThrownBy(() -> connector.execute(call, full, secrets, context))
        .isInstanceOfSatisfying(ConnectorException.class,
            error -> assertThat(error.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT));
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  void supportsReadOnlyNdjsonMultiSearch() throws Exception {
    String ndjson = "{\"index\":\"products\"}\n{\"query\":{\"match_all\":{}}}\n";
    var args = TestCtx.JSON.createObjectNode().put("method", "POST").put("path", "/_msearch")
        .put("contentType", "application/x-ndjson").put("body", ndjson);
    ToolCall multi = Configs.call("opensearch_query", args.toString());
    assertThat(connector.classify(multi, full, Configs.policy(Env.DEV, TestCtx.dev())).kind())
        .isEqualTo(Classification.Kind.READ);
    server.enqueue(json(200, "{\"responses\":[{\"hits\":{\"hits\":[]}}]}"));
    result(connector.execute(multi, full, secrets, TestCtx.of()));
    assertThat(take().getBody().utf8()).isEqualTo(ndjson);
  }

  @Test
  void doesNotFollowRedirectsOrReuseApprovalForAnotherServer() throws Exception {
    ToolCall call = Configs.call("opensearch_request", "{\"method\":\"DELETE\",\"path\":\"/products\"}");
    ActionPreview preview = connector.describeAction(call, full);
    ObjectNode changed = (ObjectNode) full.config().deepCopy();
    changed.put("url", "http://127.0.0.1:1");
    ConnectionConfig otherServer = Configs.of("other-search", Env.DEV, changed.toString(), "/auth/secret");
    assertThatThrownBy(() -> connector.execute(call, otherServer, secrets, TestCtx.of().approved(preview.payload())))
        .isInstanceOf(ConnectorException.class);
    assertThat(server.getRequestCount()).isZero();
    server.enqueue(new MockResponse.Builder().code(307).addHeader("Location", "http://127.0.0.1:1/other")
        .body("redirect refused").build());
    JsonNode redirected = result(connector.execute(call, full, secrets, TestCtx.of().approved(preview.payload())));
    assertThat(redirected.path("status").asInt()).isEqualTo(307);
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"GET", "POST"})
  void approvedRequestsAreNotRetriedAfterDispatchEvenWithAWarmQueryPool(String method) throws Exception {
    server.enqueue(json(200, "{\"status\":\"green\"}"));
    connector.execute(Configs.call("opensearch_query", "{\"path\":\"/_cluster/health\"}"), full, secrets,
        TestCtx.of());
    take();
    server.enqueue(new MockResponse.Builder().onResponseStart(new mockwebserver3.SocketEffect.CloseSocket()).build());
    // An unsafe transparent retry would consume this response and falsely report a known outcome.
    server.enqueue(json(200, "{\"acknowledged\":true}"));
    ToolCall call = Configs.call("opensearch_request", "{\"method\":\"" + method
        + "\",\"path\":\"/_plugins/custom/trigger\"}");
    assertThatThrownBy(() -> connector.execute(call, full, secrets,
        TestCtx.of().approved(connector.describeAction(call, full).payload())))
        .isInstanceOfSatisfying(ConnectorException.class,
            error -> assertThat(error.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN));
    assertThat(server.getRequestCount()).isEqualTo(2);
    assertThat(take().getMethod()).isEqualTo(method);
  }

  @Test
  void retryAfterDoesNotReplayAnApprovedRequest() throws Exception {
    server.enqueue(new MockResponse.Builder().code(503).addHeader("Content-Type", "application/json")
        .addHeader("Retry-After", "0").body("{\"error\":\"overloaded\"}").build());
    server.enqueue(json(200, "{\"acknowledged\":true}"));
    ToolCall call = Configs.call("opensearch_request", "{\"path\":\"/_plugins/custom/trigger\"}");
    JsonNode response = result(connector.execute(call, full, secrets,
        TestCtx.of().approved(connector.describeAction(call, full).payload())));
    assertThat(response.path("status").asInt()).isEqualTo(503);
    assertThat(response.at("/body/error").asString()).isEqualTo("overloaded");
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void privateCaDoesNotDisableHostnameVerification(boolean correctHostname) throws Exception {
    HeldCertificate ca = new HeldCertificate.Builder().commonName("OpenSearch Test Root").certificateAuthority(0)
        .build();
    HeldCertificate leaf = new HeldCertificate.Builder().commonName("OpenSearch Test Server")
        .addSubjectAlternativeName(correctHostname ? "127.0.0.1" : "different.example.invalid")
        .signedBy(ca).build();
    try (MockWebServer tlsServer = new MockWebServer()) {
      tlsServer.useHttps(new HandshakeCertificates.Builder().heldCertificate(leaf, ca.certificate()).build()
          .sslSocketFactory());
      tlsServer.start(InetAddress.getByName("127.0.0.1"), 0);
      tlsServer.enqueue(json(200, "{\"status\":\"green\"}"));
      ObjectNode settings = (ObjectNode) full.config().deepCopy();
      settings.put("url", "https://127.0.0.1:" + tlsServer.getPort()).put("caCertificate", ca.certificatePem());
      ConnectionConfig trusted = Configs.of("private-search", Env.DEV, settings.toString(), "/auth/secret");
      ToolCall call = Configs.call("opensearch_query", "{\"path\":\"/_cluster/health\"}");
      if (correctHostname) {
        assertThat(result(connector.execute(call, trusted, secrets, TestCtx.of())).path("status").asInt())
            .isEqualTo(200);
        assertThat(tlsServer.getRequestCount()).isEqualTo(1);
      } else {
        assertThatThrownBy(() -> connector.execute(call, trusted, secrets, TestCtx.of()))
            .isInstanceOfSatisfying(ConnectorException.class,
                error -> assertThat(error.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE));
        assertThat(tlsServer.getRequestCount()).isZero();
      }
    }
  }

  private RecordedRequest take() throws InterruptedException {
    RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
    assertThat(request).isNotNull();
    return request;
  }

  private static JsonNode result(ToolResult result) {
    JsonNode output = result.structuredContent();
    assertThat(OUTPUT.validate(output)).as(output.toString()).isEmpty();
    assertThat(result.content()).hasSize(1);
    assertThat(result.content().getFirst().text()).isEqualTo(output.toString());
    return output;
  }

  private static MockResponse json(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build();
  }
}
