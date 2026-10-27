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
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Loki's HTTP API as the connector speaks it (report 07 §1, §3.4, §5), against MockWebServer. */
class LokiBackendTest {

  private static final String TOKEN = "glc_loki_token_0123456789";
  private final MockWebServer server = new MockWebServer();
  private final CloudCredentials cloud = new CloudCredentials(CloudEndpoints.PRODUCTION, Clock.systemUTC());
  private final LogsConnector connector = new LogsConnector(cloud, Clock.systemUTC());
  private final TestSecrets secrets = new TestSecrets().with("/auth/secret", TOKEN);
  private ConnectionConfig cfg;

  @BeforeEach
  void start() throws IOException {
    server.start();
    cfg = Configs.loki(server.url("/").toString());
  }

  @AfterEach
  void stop() throws IOException {
    server.close();
    cloud.close();
  }

  @Test
  void searchesNewestFirstWithTheTenantAndMergesTheStreams() throws Exception {
    server.enqueue(json("""
        {"status":"success","data":{"resultType":"streams","result":[
          {"stream":{"app":"api","namespace":"app"},"values":[
            ["1792672198000000003","\\u001b[31mpayment failed\\u001b[0m",{"structuredMetadata":{"detected_level":"error","pod":"api-7d9"}}],
            ["1792672190000000000","GET /health 200",{"structuredMetadata":{"detected_level":"info"}}]]},
          {"stream":{"app":"worker"},"values":[
            ["1792672195000000000","job done",{}]]}],
         "stats":{"summary":{"totalBytesProcessed":4096,"totalLinesProcessed":3}}}}"""));

    ToolResult r = OutputSchema.valid(connector.execute(Configs.call("logs_search",
        "{\"query\":\"{app=~\\\"api|worker\\\"}\",\"from\":\"2026-10-22T12:00:00Z\",\"to\":\"2026-10-22T13:00:00Z\","
            + "\"limit\":2}"), cfg, secrets, TestCtx.of()));

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    Map<String, String> q = FakeLoki.query(req.getUrl().encodedQuery());
    assertThat(req.getUrl().encodedPath()).isEqualTo("/loki/api/v1/query_range");
    assertThat(q).containsEntry("query", "{app=~\"api|worker\"}").containsEntry("direction", "backward")
        .containsEntry("limit", "3").containsEntry("start", "1792670400000000000")
        .containsEntry("end", "1792674000000000001");
    assertThat(req.getHeaders().get("X-Scope-OrgID")).isEqualTo("team-a");
    assertThat(req.getHeaders().get("X-Loki-Response-Encoding-Flags")).isEqualTo("categorize-labels");
    assertThat(req.getHeaders().get("Authorization")).isEqualTo("Bearer " + TOKEN);

    JsonNode s = r.structuredContent();
    assertThat(s.path("backend").asString()).isEqualTo("loki");
    assertThat(s.path("count").asInt()).isEqualTo(2);
    assertThat(s.at("/entries/0/message").asString()).as("ANSI escapes become visible text")
        .isEqualTo("\\e[31mpayment failed\\e[0m");
    assertThat(s.at("/entries/0/severity").asString()).isEqualTo("ERROR");
    assertThat(s.at("/entries/0/timestamp").asString()).isEqualTo("2026-10-22T12:29:58.000000003Z");
    assertThat(s.at("/entries/0/labels/pod").asString()).isEqualTo("api-7d9");
    assertThat(s.at("/entries/1/message").asString()).as("streams merged into one timeline").isEqualTo("job done");
    assertThat(s.path("truncated").asBoolean()).isTrue();
    assertThat(s.path("truncatedReason").asString()).isEqualTo("limit");
    assertThat(s.path("nextCursor").asString()).startsWith("lc1.eyJ");
    assertThat(s.at("/stats/bytesScanned").asLong()).isEqualTo(4096);
    assertThat(r.truncatedReason()).isEqualTo("row_limit");
    String text = r.content().getFirst().text();
    assertThat(TestCtx.lines(text).get(1)).isEqualTo("2026-10-22T12:29:58.000000003Z ERROR [app=api detected_level=error "
        + "namespace=app pod=api-7d9] \\e[31mpayment failed\\e[0m");
    assertThat(text).contains("call logs_search again with the same query and cursor lc1.");
  }

  @Test
  void lokisOwnWordsReachTheAgent() {
    server.enqueue(new MockResponse.Builder().code(400).addHeader("Content-Type", "text/plain")
        .body("parse error at line 1, col 11: syntax error: unexpected IDENTIFIER").build());

    assertThatThrownBy(() -> connector.execute(Configs.call("logs_search", "{\"query\":\"{app=api}\"}"), cfg, secrets,
        TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
          assertThat(e.getMessage()).isEqualTo("Loki refused the query: parse error at line 1, col 11: syntax error: "
              + "unexpected IDENTIFIER");
        });
  }

  @Test
  void aMissingTenantIsNamed() {
    server.enqueue(new MockResponse.Builder().code(401).body("no org id\n").build());

    assertThatThrownBy(() -> connector.execute(Configs.call("logs_search", "{\"query\":\"{app=\\\"api\\\"}\"}"), cfg,
        secrets, TestCtx.of())).hasMessageContaining("set Tenant (X-Scope-OrgID)");
  }

  @Test
  void aMetricQueryIsATable() throws Exception {
    server.enqueue(json("""
        {"status":"success","data":{"resultType":"matrix","result":[
          {"metric":{"app":"api"},"values":[[1792672200,"42"],[1792672260.5,"40"]]}]}}"""));

    ToolResult r = OutputSchema.valid(connector.execute(Configs.call("logs_search",
        "{\"query\":\"sum by (app) (count_over_time({app=\\\"api\\\"}[1m]))\"}"), cfg, secrets, TestCtx.of()));

    JsonNode s = r.structuredContent();
    assertThat(s.at("/aggregate/columns").toString()).isEqualTo("[\"time\",\"app\",\"value\"]");
    assertThat(s.at("/aggregate/rows/1").toString()).isEqualTo("[\"2026-10-22T12:31:00.500Z\",\"api\",\"40\"]");
    assertThat(s.path("count").asInt()).isZero();
    assertThat(r.content().getFirst().text()).contains("2 rows from app-logs (Loki)").contains("time").contains("42");
  }

  @Test
  void aRedirectIsNeverFollowedWithTheToken() throws Exception {
    server.enqueue(new MockResponse.Builder().code(302).addHeader("Location", "http://169.254.169.254/").build());

    assertThatThrownBy(() -> connector.execute(Configs.call("logs_search", "{\"query\":\"{app=\\\"api\\\"}\"}"), cfg,
        secrets, TestCtx.of())).isInstanceOf(ConnectorException.class).hasMessageContaining("302");
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  void anApprovedSearchRunsTheStoredRangeNotANewOne() throws Exception {
    server.enqueue(json("{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[]}}"));
    JsonNode payload = TestCtx.JSON.readTree("""
        {"tool":"logs_search","arguments":{"query":"{app=\\"api\\"}","from":"2026-10-22T11:30:00Z",
         "to":"2026-10-22T12:30:00Z","limit":10}}""");

    ToolResult r = connector.execute(Configs.call("logs_search", "{\"query\":\"{app=\\\"api\\\"}\",\"from\":\"-5m\"}"),
        cfg, secrets, TestCtx.of().approved(payload));

    Map<String, String> q = FakeLoki.query(server.takeRequest(1, TimeUnit.SECONDS).getUrl().encodedQuery());
    assertThat(q).containsEntry("start", "1792668600000000000").containsEntry("limit", "11");
    assertThat(r.content().getFirst().text()).startsWith("No entries on app-logs (Loki) match between "
        + "2026-10-22T11:30:00Z → 2026-10-22T12:30:00Z.");
  }

  @Test
  void thePolicysDefaultLineCountReachesTheCall() throws Exception {
    server.enqueue(json("{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[]}}"));
    var call = Configs.call("logs_search", "{\"query\":\"{app=\\\"api\\\"}\"}");

    connector.classify(call, cfg, Configs.policy(Env.DEV, TestCtx.dev(), "{\"defaultLimit\":7}"));
    connector.execute(call, cfg, secrets, TestCtx.of());

    assertThat(FakeLoki.query(server.takeRequest(1, TimeUnit.SECONDS).getUrl().encodedQuery()))
        .containsEntry("limit", "8");
  }

  @Test
  void aLimitAboveThePolicyIsLoweredAndSaid() throws Exception {
    server.enqueue(json("{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[]}}"));

    ToolResult r = connector.execute(Configs.call("logs_search", "{\"query\":\"{app=\\\"api\\\"}\",\"limit\":900}"),
        Configs.of("prod-loki", Env.PROD, cfg.config().toString(), "/auth/secret"), secrets,
        TestCtx.of().env(Env.PROD));

    assertThat(FakeLoki.query(server.takeRequest(1, TimeUnit.SECONDS).getUrl().encodedQuery()))
        .containsEntry("limit", "201");
    assertThat(r.content().getFirst().text()).contains("Note: limit lowered to 200 by the prod policy.");
    assertThat(r.structuredContent().at("/notes/0").asString()).isEqualTo("limit lowered to 200 by the prod policy");
  }

  @Test
  void testConnectionReadsAndProbesWithoutWriting() throws Exception {
    server.enqueue(new MockResponse.Builder().code(200).body("ready\n").build());
    server.enqueue(json("{\"version\":\"3.7.9\",\"revision\":\"abc\"}"));
    server.enqueue(json("{\"status\":\"success\",\"data\":[\"app\",\"service_name\"]}"));
    server.enqueue(new MockResponse.Builder().code(422)
        .body("error at least one valid stream is required for ingestion").build());

    TestResult t = connector.test(cfg, secrets, TestCtx.of());

    assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
    assertThat(t.serverVersion()).isEqualTo("Loki 3.7.9");
    assertThat(t.identity()).isEqualTo("tenant team-a");
    assertThat(t.checks()).extracting(TestResult.Check::name).containsExactly("ready", "version", "read",
        "write probe");
    assertThat(t.permissions().level()).isEqualTo(TestResult.Level.WRITE);
    assertThat(t.hints()).extracting(TestResult.Hint::code).contains("logs.can-write");
    assertThat(t.metadataUpdate().path("serverVersion").asString()).isEqualTo("Loki 3.7.9");
    server.takeRequest();
    server.takeRequest();
    server.takeRequest();
    RecordedRequest push = server.takeRequest();
    assertThat(push.getUrl().encodedPath()).isEqualTo("/loki/api/v1/push");
    assertThat(push.getBody().utf8()).as("the probe pushes no stream").isEqualTo("{\"streams\":[]}");
  }

  @Test
  void aReadOnlyTokenPassesTheProbe() {
    server.enqueue(new MockResponse.Builder().code(200).body("ready\n").build());
    server.enqueue(new MockResponse.Builder().code(404).body("404 page not found").build());
    server.enqueue(json("{\"status\":\"success\",\"data\":[]}"));
    server.enqueue(new MockResponse.Builder().code(403).body("forbidden").build());

    TestResult t = connector.test(cfg, secrets, TestCtx.of());

    assertThat(t.ok()).isTrue();
    assertThat(t.serverVersion()).isEqualTo("Loki unknown");
    assertThat(t.permissions().level()).isEqualTo(TestResult.Level.READ);
    assertThat(t.hints()).extracting(TestResult.Hint::code).containsExactly("logs.empty");
  }

  @Test
  void anUnreachableLokiFailsTheTestWithItsChecks() throws IOException {
    server.close();

    TestResult t = connector.test(cfg, secrets, TestCtx.of());

    assertThat(t.ok()).isFalse();
    assertThat(t.error().code()).isEqualTo("logs.unreachable");
    assertThat(t.error().message()).startsWith("Can't connect to");
    assertThat(t.checks()).singleElement().satisfies(c -> assertThat(c.ok()).isFalse());
  }

  @Test
  void describeListsTheLabelsOfTheLastHour() throws Exception {
    server.enqueue(json("{\"status\":\"success\",\"data\":[\"app\",\"namespace\"]}"));
    server.enqueue(json("{\"status\":\"success\",\"data\":[\"api\",\"worker\"]}"));

    JsonNode all = connector.describe(cfg, secrets, TestCtx.of(), null).orElseThrow();
    JsonNode app = connector.describe(cfg, secrets, TestCtx.of(), "app").orElseThrow();

    assertThat(all.path("labels").toString()).isEqualTo("[\"app\",\"namespace\"]");
    assertThat(all.path("language").asString()).isEqualTo("LogQL");
    assertThat(app.path("values").toString()).isEqualTo("[\"api\",\"worker\"]");
    server.takeRequest();
    assertThat(server.takeRequest().getUrl().encodedPath()).isEqualTo("/loki/api/v1/label/app/values");
    assertThatThrownBy(() -> connector.describe(cfg, secrets, TestCtx.of(), "a/../../x"))
        .isInstanceOf(ConnectorException.class);
  }

  @Test
  void tailAsksOldestFirstUpToTheDelay() throws Exception {
    Instant now = Instant.now();
    long minuteAgo = (now.getEpochSecond() - 60) * 1_000_000_000L;
    server.enqueue(json("""
        {"status":"success","data":{"resultType":"streams","result":[
          {"stream":{"app":"api"},"values":[["%d","first",{}],["%d","second",{}]]}]}}"""
        .formatted(minuteAgo, minuteAgo + 1_000_000_000L)));

    ToolResult r = OutputSchema.valid(connector.execute(Configs.call("logs_tail",
        "{\"query\":\"{app=\\\"api\\\"}\",\"since\":\"-5m\",\"wait\":0}"), cfg, secrets, TestCtx.of()));

    Map<String, String> q = FakeLoki.query(server.takeRequest(1, TimeUnit.SECONDS).getUrl().encodedQuery());
    assertThat(q).containsEntry("direction", "forward");
    long end = Long.parseLong(q.get("end"));
    long nowNs = now.getEpochSecond() * 1_000_000_000L + now.getNano();
    assertThat(nowNs - end).as("two seconds behind now").isBetween(1_000_000_000L, 2_500_000_000L);
    assertThat(r.structuredContent().at("/entries/0/message").asString()).isEqualTo("first");
    assertThat(r.content().getFirst().text()).startsWith("2 new entries from app-logs (Loki) after")
        .contains("Next: call logs_tail again with cursor lc1.");
  }

  private static MockResponse json(String body) {
    return new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build();
  }
}
