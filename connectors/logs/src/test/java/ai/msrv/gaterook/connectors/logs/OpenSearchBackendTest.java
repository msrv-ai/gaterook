package ai.msrv.gaterook.connectors.logs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** OpenSearch {@code _search} as the connector sends it (report 07 §3.4, §5, §6), against MockWebServer. */
class OpenSearchBackendTest {

  private final MockWebServer server = new MockWebServer();
  private final CloudCredentials cloud = new CloudCredentials(CloudEndpoints.PRODUCTION, Clock.systemUTC());
  private final LogsConnector connector = new LogsConnector(cloud, Clock.systemUTC());
  private ConnectionConfig cfg;

  @BeforeEach
  void start() throws IOException {
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    cfg = Configs.openSearch("http://127.0.0.1:" + server.getPort());
  }

  @AfterEach
  void stop() throws IOException {
    server.close();
    cloud.close();
  }

  @Test
  void searchesTheConnectionsIndicesWithALuceneQueryOnly() throws Exception {
    server.enqueue(json(200, """
        {"took":3,"timed_out":false,"_shards":{"failed":0},"hits":{"hits":[
          {"_index":"logs-app-2026.10.22","_id":"d1","sort":[1792672198123456789],
           "_source":{"@timestamp":"2026-10-22T12:29:58.123456789Z","message":"timeout calling billing",
                      "log":{"level":"error"},"service":{"name":"api"},"host.name":"node-3"}},
          {"_index":"logs-app-2026.10.22","_id":"d2","sort":[1792672100000000000],
           "_source":{"@timestamp":"2026-10-22T12:28:20Z","message":"retrying","log.level":"warn"}}]}}"""));
    TestSecrets secrets = new TestSecrets().with("/auth/secret", "os-pass-cnry");

    ToolResult r = OutputSchema.valid(connector.execute(Configs.call("logs_search", "{\"query\":\"log.level:error\","
        + "\"from\":\"2026-10-22T12:00:00Z\",\"to\":\"2026-10-22T12:30:00Z\",\"limit\":5}"), cfg, secrets,
        TestCtx.of()));

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertThat(req.getMethod()).isEqualTo("POST");
    assertThat(req.getUrl().encodedPath()).isEqualTo("/logs-app-*/_search");
    assertThat(req.getUrl().queryParameter("ignore_unavailable")).isEqualTo("true");
    assertThat(req.getHeaders().get("Authorization")).isEqualTo("Basic " + Base64.getEncoder().encodeToString(
        "gw_reader:os-pass-cnry".getBytes(StandardCharsets.UTF_8)));
    JsonNode body = TestCtx.JSON.readTree(req.getBody().utf8());
    assertThat(body.path("size").asInt()).isEqualTo(6);
    assertThat(body.path("track_total_hits").asBoolean()).isFalse();
    assertThat(body.at("/sort/0/@timestamp").toString()).isEqualTo("{\"order\":\"desc\",\"numeric_type\":"
        + "\"date_nanos\"}");
    assertThat(body.at("/query/bool/filter/0/range/@timestamp").toString()).isEqualTo("{\"gte\":"
        + "\"2026-10-22T12:00:00Z\",\"lte\":\"2026-10-22T12:30:00Z\",\"format\":\"strict_date_optional_time_nanos\"}");
    JsonNode qs = body.at("/query/bool/must/0/query_string");
    assertThat(qs.path("query").asString()).isEqualTo("log.level:error");
    assertThat(qs.path("allow_leading_wildcard").asBoolean(true)).isFalse();
    assertThat(qs.path("default_field").asString()).isEqualTo("message");

    JsonNode s = r.structuredContent();
    assertThat(s.at("/entries/0/timestamp").asString()).isEqualTo("2026-10-22T12:29:58.123456789Z");
    assertThat(s.at("/entries/0/severity").asString()).isEqualTo("ERROR");
    assertThat(s.at("/entries/0/labels/service.name").asString()).isEqualTo("api");
    assertThat(s.at("/entries/0/labels/host.name").asString()).as("a flat dotted key").isEqualTo("node-3");
    assertThat(s.at("/entries/0/labels/index").asString()).isEqualTo("logs-app-2026.10.22");
    assertThat(s.at("/entries/0/id").asString()).isEqualTo("logs-app-2026.10.22/d1");
    assertThat(s.at("/entries/1/severity").asString()).isEqualTo("WARNING");
  }

  @Test
  void theScopeQueryIsAFilterTheAgentCantLeave() throws Exception {
    ConnectionConfig scoped = Configs.of("search-logs", Env.DEV, """
        {"backend":"opensearch","url":"http://127.0.0.1:%d","indices":["logs-app-*","logs-web-*"],
         "scopeQuery":"kubernetes.namespace:app","auth":{"mode":"apiKey"}}""".formatted(server.getPort()),
        "/auth/secret");
    server.enqueue(json(200, "{\"hits\":{\"hits\":[]}}"));

    connector.execute(Configs.call("logs_search", "{\"query\":\"\",\"source\":\"logs-web-*\"}"), scoped,
        new TestSecrets().with("/auth/secret", "os_key"), TestCtx.of());

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertThat(req.getUrl().encodedPath()).isEqualTo("/logs-web-*/_search");
    assertThat(req.getHeaders().get("Authorization")).isEqualTo("ApiKey os_key");
    JsonNode body = TestCtx.JSON.readTree(req.getBody().utf8());
    assertThat(body.at("/query/bool/filter/1/query_string/query").asString()).isEqualTo("kubernetes.namespace:app");
    assertThat(body.at("/query/bool").has("must")).as("an empty query matches every document of the scope").isFalse();
  }

  @Test
  void openSearchsReasonReachesTheAgent() {
    server.enqueue(json(400, """
        {"error":{"root_cause":[{"type":"query_shard_exception","reason":"Failed to parse query [log.level:(]"}],
         "type":"search_phase_execution_exception","reason":"all shards failed"},"status":400}"""));

    assertThatThrownBy(() -> connector.execute(Configs.call("logs_search", "{\"query\":\"log.level:(\"}"), cfg,
        new TestSecrets().with("/auth/secret", "p"), TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
          assertThat(e.getMessage()).isEqualTo("OpenSearch refused the query: Failed to parse query [log.level:(]");
        });
  }

  @Test
  void amazonOpenSearchIsSignedWithSigV4() throws Exception {
    ConnectionConfig aws = Configs.of("aws-search", Env.DEV, """
        {"backend":"opensearch","url":"http://127.0.0.1:%d","indices":["logs-app-*"],
         "auth":{"mode":"awsSigV4","awsRegion":"eu-central-1","awsService":"es",
                 "awsCredentials":{"source":"accessKey","accessKeyId":"AKIAIOSFODNN7EXAMPLE"}}}"""
        .formatted(server.getPort()), "/auth/awsCredentials/secretAccessKey");
    server.enqueue(json(200, "{\"hits\":{\"hits\":[]}}"));
    TestCtx ctx = TestCtx.of();

    connector.execute(Configs.call("logs_search", "{\"query\":\"x\"}"), aws,
        new TestSecrets().with("/auth/awsCredentials/secretAccessKey", "cnry-sigv4-secret"), ctx);

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    String auth = req.getHeaders().get("Authorization");
    assertThat(auth).startsWith("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/")
        .contains("/eu-central-1/es/aws4_request").contains("SignedHeaders=").contains("host");
    assertThat(req.getHeaders().get("X-Amz-Date")).isNotBlank();
    assertThat(ctx.taints).contains("cnry-sigv4-secret");
  }

  @Test
  void testConnectionResolvesTheIndicesAndAsksTheSecurityPlugin() {
    server.enqueue(json(200, "{\"version\":{\"distribution\":\"opensearch\",\"number\":\"3.9.0\"}}"));
    server.enqueue(json(200, "{\"indices\":[{\"name\":\"logs-app-2026.10.22\"}],\"aliases\":[],\"data_streams\":[]}"));
    server.enqueue(json(200, "{\"hits\":{\"hits\":[{\"_index\":\"logs-app-2026.10.22\",\"_id\":\"1\",\"sort\":"
        + "[1792672198000000000],\"_source\":{\"message\":\"m\"}}]}}"));
    server.enqueue(json(200, "{\"user_name\":\"gw_reader\",\"roles\":[\"all_access\"],\"backend_roles\":[]}"));

    TestResult t = connector.test(cfg, new TestSecrets().with("/auth/secret", "p"), TestCtx.of());

    assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
    assertThat(t.serverVersion()).isEqualTo("OpenSearch 3.9.0");
    assertThat(t.identity()).isEqualTo("gw_reader");
    assertThat(t.checks()).extracting(TestResult.Check::name).containsExactly("version", "indices", "read",
        "permissions");
    assertThat(t.permissions().level()).isEqualTo(TestResult.Level.WRITE);
    assertThat(t.hints()).extracting(TestResult.Hint::code).contains("logs.can-write")
        .as("127.0.0.1 is this computer: no warning about plain http").doesNotContain("logs.plain-http",
            "logs.not-opensearch");
  }

  @Test
  void noMatchingIndexFailsTheTest() {
    server.enqueue(json(200, "{\"version\":{\"distribution\":\"opensearch\",\"number\":\"3.9.0\"}}"));
    server.enqueue(json(200, "{\"indices\":[],\"aliases\":[],\"data_streams\":[]}"));

    TestResult t = connector.test(cfg, new TestSecrets().with("/auth/secret", "p"), TestCtx.of());

    assertThat(t.ok()).isFalse();
    assertThat(t.error().message()).isEqualTo("No index matches logs-app-*.");
  }

  private static MockResponse json(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build();
  }
}
