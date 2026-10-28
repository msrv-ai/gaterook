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
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Cloud Logging over REST against fakes of Google's token server, IAM and {@code entries:list} (report 07 §8): the
 * sign-in on this computer is impersonated, the token is tainted, the filter is the connection's, empty pages are
 * followed at most three times, and the project's shared quota is respected.
 */
class GcpLoggingBackendTest {

  private static final String SA = "gw-reader@acme-prod.iam.gserviceaccount.com";
  private static final String TOKEN = "ya29.impersonated-cnry-" + Long.toHexString(System.nanoTime());
  private final MockWebServer server = new MockWebServer();
  private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private final AtomicReference<Function<RecordedRequest, MockResponse>> logging = new AtomicReference<>();
  private CloudCredentials cloud;
  private LogsConnector connector;
  private final ConnectionConfig cfg = Configs.gcp();

  @BeforeEach
  void start() throws IOException {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        String path = request.getUrl().encodedPath();
        if (path.equals("/token")) {
          return json(200, "{\"access_token\":\"ya29.user-sign-in\",\"expires_in\":3600,\"token_type\":\"Bearer\"}");
        }
        if (path.endsWith(":generateAccessToken")) {
          return json(200, "{\"accessToken\":\"" + TOKEN + "\",\"expireTime\":\"" + Instant.now().plusSeconds(900)
              + "\"}");
        }
        return logging.get().apply(request);
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    Path dir = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "gcp-");
    Path adc = Files.writeString(dir.resolve("adc.json"), "{\"type\":\"authorized_user\",\"client_id\":\"c.apps"
        + ".googleusercontent.com\",\"client_secret\":\"d-cnry\",\"refresh_token\":\"1//cnry-refresh\"}");
    String base = server.url("/").toString();
    cloud = new CloudCredentials(new CloudEndpoints(server.url("/token").uri(),
        base + "v1/projects/-/serviceAccounts/%s:generateAccessToken", adc, null, null, null,
        Map.of("gcp.logging", URI.create(base), "gcp.cloudresourcemanager", URI.create(base))), Clock.systemUTC());
    connector = new LogsConnector(cloud, Clock.systemUTC());
  }

  @AfterEach
  void stop() throws IOException {
    cloud.close();
    server.close();
  }

  @Test
  void listsEntriesWithTheConnectionsFilterAndAnImpersonatedToken() throws Exception {
    logging.set(r -> json(200, """
        {"entries":[
          {"insertId":"a1","logName":"projects/acme-prod/logs/stdout","timestamp":"2026-10-22T12:29:58.123456789Z",
           "severity":"ERROR","resource":{"type":"k8s_container","labels":{"namespace_name":"app","pod_name":"api-7d9"}},
           "jsonPayload":{"message":"payment failed","orderId":48213}},
          {"insertId":"a2","logName":"projects/acme-prod/logs/cloudaudit.googleapis.com%2Factivity",
           "timestamp":"2026-10-22T12:20:00Z","severity":"NOTICE","resource":{"type":"gce_instance","labels":{}},
           "protoPayload":{"methodName":"v1.compute.instances.stop","resourceName":"projects/acme-prod/zones/x/vm-1",
             "authenticationInfo":{"principalEmail":"ops@acme.dev"}}},
          {"insertId":"a3","logName":"projects/acme-prod/logs/stdout","timestamp":"2026-10-22T12:10:00Z",
           "textPayload":"plain line"}]}"""));
    TestCtx ctx = TestCtx.of().env(ai.msrv.gaterook.connector.api.Env.PROD);

    ToolResult r = OutputSchema.valid(connector.execute(Configs.call("logs_search", "{\"query\":\"severity>=ERROR\","
        + "\"from\":\"2026-10-22T12:00:00Z\",\"to\":\"2026-10-22T12:30:00Z\",\"limit\":5}"), cfg, new TestSecrets(),
        ctx));

    RecordedRequest list = requests.stream().filter(q -> q.getUrl().encodedPath().equals("/v2/entries:list"))
        .findFirst().orElseThrow();
    JsonNode body = TestCtx.JSON.readTree(list.getBody().utf8());
    assertThat(body.path("resourceNames").toString()).isEqualTo("[\"projects/acme-prod\"]");
    assertThat(body.path("filter").asString()).isEqualTo("(resource.type=\"k8s_container\")\nAND (severity>=ERROR)\n"
        + "AND timestamp>=\"2026-10-22T12:00:00Z\"\nAND timestamp<=\"2026-10-22T12:30:00Z\"");
    assertThat(body.path("orderBy").asString()).isEqualTo("timestamp desc");
    assertThat(body.path("pageSize").asInt()).isEqualTo(6);
    assertThat(list.getHeaders().get("Authorization")).isEqualTo("Bearer " + TOKEN);
    assertThat(ctx.taints).as("the derived token is masked like a stored secret").contains(TOKEN);
    assertThat(ctx.quotaKeys).containsExactly("gcp:projects/acme-prod@30");

    JsonNode s = r.structuredContent();
    assertThat(s.path("backend").asString()).isEqualTo("gcp-logging");
    assertThat(s.at("/entries/0/timestamp").asString()).isEqualTo("2026-10-22T12:29:58.123456789Z");
    assertThat(s.at("/entries/0/message").asString()).isEqualTo("payment failed");
    assertThat(s.at("/entries/0/fields/orderId").asInt()).isEqualTo(48213);
    assertThat(s.at("/entries/0/labels/pod_name").asString()).isEqualTo("api-7d9");
    assertThat(s.at("/entries/0/labels/log").asString()).isEqualTo("stdout");
    assertThat(s.at("/entries/0/id").asString()).isEqualTo("a1");
    assertThat(s.at("/entries/1/message").asString()).isEqualTo("v1.compute.instances.stop "
        + "projects/acme-prod/zones/x/vm-1 by ops@acme.dev");
    assertThat(s.at("/entries/1/labels/log").asString()).isEqualTo("cloudaudit.googleapis.com/activity");
    assertThat(s.at("/entries/2/message").asString()).isEqualTo("plain line");
    assertThat(s.at("/entries/2/severity").asString()).isEqualTo("DEFAULT");
    assertThat(s.path("truncated").asBoolean()).isFalse();
  }

  @Test
  void readsACommentedExplorerFilterWithoutChangingItsQuotedValuesOrScope() throws Exception {
    logging.set(r -> json(200, "{\"entries\":[]}"));
    String query = """
        -- Select application containers
        resource.type="k8s_container"
        resource.labels.namespace_name="app"
        (textPayload:"--dry-run" OR jsonPayload.message =~ "timeout.*") -- optional match
        """;
    String arguments = TestCtx.JSON.createObjectNode().put("query", query)
        .put("from", "2026-10-22T12:00:00Z").put("to", "2026-10-22T12:30:00Z").toString();
    connector.execute(Configs.call("logs_search", arguments), cfg, new TestSecrets(), TestCtx.of());
    JsonNode body = TestCtx.JSON.readTree(requests.stream()
        .filter(r -> r.getUrl().encodedPath().equals("/v2/entries:list")).findFirst().orElseThrow().getBody().utf8());
    assertThat(body.path("resourceNames").toString()).isEqualTo("[\"projects/acme-prod\"]");
    assertThat(body.path("filter").asString()).startsWith("(resource.type=\"k8s_container\")\nAND (")
        .contains("textPayload:\"--dry-run\"", "resource.labels.namespace_name=\"app\"",
            "timestamp>=\"2026-10-22T12:00:00Z\"", "timestamp<=\"2026-10-22T12:30:00Z\"")
        .doesNotContain("Select application", "optional match");
  }

  @Test
  void identicalInsertIdsFromDifferentProjectsRemainDistinctAcrossSearchPages() throws Exception {
    logging.set(r -> json(200, """
        {"entries":[
          {"insertId":"same","logName":"projects/acme-stage/logs/stdout", "timestamp":"2026-10-22T12:29:00Z",
           "textPayload":"stage entry"},
          {"insertId":"same","logName":"projects/acme-prod/logs/stdout", "timestamp":"2026-10-22T12:29:00Z",
           "textPayload":"prod entry"}]}
        """));
    ConnectionConfig combined = Configs.of("combined-logs", Env.DEV, """
        {"backend":"gcp-logging","resourceNames":["projects/acme-stage","projects/acme-prod"],
         "credentials":{"source":"adc"}}
        """);
    TestCtx ctx = TestCtx.of();
    JsonNode first = connector.execute(Configs.call("logs_search", """
        {"query":"","from":"2026-10-22T12:00:00Z","to":"2026-10-22T12:30:00Z","limit":1}
        """), combined, new TestSecrets(), ctx).structuredContent();
    String cursor = first.path("nextCursor").asString();
    JsonNode second = connector.execute(Configs.call("logs_search", TestCtx.JSON.createObjectNode()
        .put("query", "").put("limit", 1).put("cursor", cursor).toString()), combined, new TestSecrets(), ctx.next())
        .structuredContent();
    assertThat(first.at("/entries/0/message").asString()).isEqualTo("stage entry");
    assertThat(second.at("/entries/0/message").asString()).isEqualTo("prod entry");
    assertThat(first.at("/entries/0/id").asString()).isEqualTo("same");
    assertThat(second.at("/entries/0/id").asString()).as("the displayed insertId is unchanged").isEqualTo("same");
    assertThat(second.path("count").asInt()).isEqualTo(1);
  }

  @Test
  void aShortPageKeepsTheSameRequestParametersWhenFollowingItsToken() throws Exception {
    logging.set(r -> {
      JsonNode request = TestCtx.JSON.readTree(r.getBody().utf8());
      return request.has("pageToken") ? json(200, """
          {"entries":[
            {"insertId":"b","timestamp":"2026-10-22T12:28:00Z","textPayload":"second"},
            {"insertId":"c","timestamp":"2026-10-22T12:27:00Z","textPayload":"third"}]}
          """) : json(200, """
          {"entries":[{"insertId":"a","timestamp":"2026-10-22T12:29:00Z","textPayload":"first"}],
           "nextPageToken":"second-page"}
          """);
    });
    JsonNode answer = OutputSchema.valid(connector.execute(Configs.call("logs_search", """
        {"query":"severity>=ERROR","from":"2026-10-22T12:00:00Z","to":"2026-10-22T12:30:00Z","limit":2}
        """), cfg, new TestSecrets(), TestCtx.of())).structuredContent();
    List<JsonNode> bodies = requests.stream().filter(r -> r.getUrl().encodedPath().equals("/v2/entries:list"))
        .map(r -> TestCtx.JSON.readTree(r.getBody().utf8())).toList();
    assertThat(bodies).hasSize(2);
    for (String parameter : List.of("resourceNames", "filter", "orderBy", "pageSize")) {
      assertThat(bodies.get(1).path(parameter)).as(parameter).isEqualTo(bodies.get(0).path(parameter));
    }
    assertThat(bodies.get(0).path("pageSize").asInt()).isEqualTo(3);
    assertThat(bodies.get(1).path("pageToken").asString()).isEqualTo("second-page");
    assertThat(answer.path("count").asInt()).isEqualTo(2);
    assertThat(answer.path("nextCursor").isString()).isTrue();
  }

  @Test
  void emptyPagesWithATokenAreFollowedThreeTimesThenTheCursorResumesThem() throws Exception {
    logging.set(r -> json(200, "{\"entries\":[],\"nextPageToken\":\"page-" + requests.size() + "\"}"));
    TestCtx ctx = TestCtx.of();

    ToolResult r = OutputSchema.valid(connector.execute(Configs.call("logs_search", "{\"query\":\"\",\"from\":\"-1h\"}"),
        cfg, new TestSecrets(), ctx));

    List<RecordedRequest> lists = requests.stream()
        .filter(q -> q.getUrl().encodedPath().equals("/v2/entries:list")).toList();
    assertThat(lists).hasSize(3);
    assertThat(TestCtx.JSON.readTree(lists.get(1).getBody().utf8()).has("pageToken")).isTrue();
    JsonNode s = r.structuredContent();
    assertThat(s.path("count").asInt()).isZero();
    assertThat(s.path("truncated").asBoolean()).isTrue();
    String cursor = s.path("nextCursor").asString();
    assertThat(cursor).startsWith("lc1.");

    String lastToken = TestCtx.JSON.readTree(lists.get(2).getBody().utf8()).path("pageToken").asString();
    logging.set(q -> json(200, "{\"entries\":[{\"insertId\":\"z\",\"timestamp\":\"" + Instant.now().minusSeconds(60)
        + "\",\"textPayload\":\"found\"}]}"));
    requests.clear();
    JsonNode next = connector.execute(Configs.call("logs_search", "{\"query\":\"\",\"limit\":1,\"cursor\":\"" + cursor + "\"}"),
        cfg, new TestSecrets(), ctx.next()).structuredContent();
    RecordedRequest resumed = requests.stream().filter(q -> q.getUrl().encodedPath().equals("/v2/entries:list"))
        .findFirst().orElseThrow();
    assertThat(TestCtx.JSON.readTree(resumed.getBody().utf8()).path("pageToken").asString())
        .as("the next call resumes where Cloud Logging stopped").isNotEqualTo(lastToken).startsWith("page-");
    assertThat(TestCtx.JSON.readTree(resumed.getBody().utf8()).path("pageSize"))
        .as("a changed tool limit must not change Google's pageSize for its existing token")
        .isEqualTo(TestCtx.JSON.readTree(lists.getFirst().getBody().utf8()).path("pageSize"));
    assertThat(next.at("/entries/0/message").asString()).isEqualTo("found");
  }

  @Test
  void aCommentCantReachPastItsOwnPartOfTheFilter() {
    // Cloud Logging reads "--" as a comment to the end of the line. On one line, a comment in the agent's query took
    // the gateway's time range with it: "x) AND timestamp>=\"2015-…\" --" read ten years where 24 h were allowed.
    long from = Search.nanos(Instant.parse("2026-10-22T12:00:00Z"));
    long to = Search.nanos(Instant.parse("2026-10-22T12:30:00Z"));
    Settings.Gcp scoped = (Settings.Gcp) Settings.parse(Configs.of("prod-logs", ai.msrv.gaterook.connector.api.Env.PROD,
        """
        {"backend":"gcp-logging","resourceNames":["projects/acme-prod"],
         "scopeFilter":"resource.type=\\"k8s_container\\" -- the app's pods only","credentials":{"source":"adc"}}"""));

    String filter = GcpLoggingBackend.filter(scoped, "severity>=ERROR -- copied from Logs Explorer", from, to);

    assertThat(filter).isEqualTo("""
        (resource.type="k8s_container")
        AND (severity>=ERROR)
        AND timestamp>="2026-10-22T12:00:00Z"
        AND timestamp<="2026-10-22T12:30:00Z\"""");
    // What Cloud Logging reads once comments are gone: the time range is still there, the parentheses balance.
    String read = filter.lines().map(l -> l.contains("--") ? l.substring(0, l.indexOf("--")) : l)
        .reduce("", (a, b) -> a + b + "\n");
    assertThat(read).contains("timestamp>=\"2026-10-22T12:00:00Z\"", "timestamp<=\"2026-10-22T12:30:00Z\"");
    assertThat(read.chars().filter(c -> c == '(').count()).isEqualTo(read.chars().filter(c -> c == ')').count());
    // An expression that escapes its parentheses is rejected before it reaches Google.
    assertThatThrownBy(() -> connector.execute(Configs.call("logs_search", "{\"query\":\"x) AND "
        + "timestamp>=\\\"2015-01-01T00:00:00Z\\\" --\"}"), cfg, new TestSecrets(), TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.getMessage()).contains("parentheses"));
    assertThat(requests).noneMatch(q -> q.getUrl().encodedPath().equals("/v2/entries:list"));
  }

  @Test
  void theSharedQuotaIsNeverExceeded() {
    logging.set(r -> json(200, "{\"entries\":[]}"));
    TestCtx ctx = TestCtx.of().quota((key, perMinute) -> false);

    assertThatThrownBy(() -> connector.execute(Configs.call("logs_search", "{\"query\":\"x\"}"), cfg,
        new TestSecrets(), ctx)).isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
          assertThat(e.getMessage()).contains("30 per minute").contains("Try again in a minute");
        });
    assertThat(requests).noneMatch(q -> q.getUrl().encodedPath().equals("/v2/entries:list"));
  }

  @Test
  void aQuotaErrorIsNotRetried() {
    logging.set(r -> json(429, "{\"error\":{\"code\":429,\"message\":\"Quota exceeded for quota metric 'Read "
        + "requests'\",\"status\":\"RESOURCE_EXHAUSTED\"}}"));

    assertThatThrownBy(() -> connector.execute(Configs.call("logs_search", "{\"query\":\"x\"}"), cfg,
        new TestSecrets(), TestCtx.of())).hasMessageContaining("60 reads per minute, shared with people");
    assertThat(requests.stream().filter(q -> q.getUrl().encodedPath().equals("/v2/entries:list"))).hasSize(1);
  }

  @Test
  void aBadFilterIsTheAgentsToFix() {
    logging.set(r -> json(400, "{\"error\":{\"code\":400,\"message\":\"Unparseable filter: syntax error at line 1, "
        + "column 9\",\"status\":\"INVALID_ARGUMENT\"}}"));

    assertThatThrownBy(() -> connector.execute(Configs.call("logs_search", "{\"query\":\"severity>>\"}"), cfg,
        new TestSecrets(), TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
          assertThat(e.getMessage()).contains("Unparseable filter");
        });
  }

  @Test
  void testConnectionReadsAndAsksWhatTheAccountMayDo() {
    logging.set(r -> r.getUrl().encodedPath().endsWith(":testIamPermissions")
        ? json(200, "{\"permissions\":[\"logging.logEntries.list\",\"logging.logs.delete\"]}")
        : json(200, "{\"entries\":[{\"insertId\":\"n\",\"timestamp\":\"" + Instant.now().minusSeconds(30)
            + "\",\"textPayload\":\"x\"}]}"));

    TestResult t = connector.test(cfg, new TestSecrets(), TestCtx.of().env(ai.msrv.gaterook.connector.api.Env.PROD));

    assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
    assertThat(t.serverVersion()).isEqualTo("Cloud Logging API v2");
    assertThat(t.identity()).isEqualTo(SA);
    assertThat(t.checks()).extracting(TestResult.Check::name).containsExactly("token", "read",
        "permissions on acme-prod");
    assertThat(t.checks().get(1).detail()).startsWith("newest entry ").endsWith("s ago");
    assertThat(t.permissions().level()).isEqualTo(TestResult.Level.WRITE);
    assertThat(t.hints()).anySatisfy(h -> {
      assertThat(h.code()).isEqualTo("logs.can-write");
      assertThat(h.text()).contains("logging.logs.delete").contains("roles/logging.viewer");
    });
    RecordedRequest perms = requests.stream().filter(q -> q.getUrl().encodedPath()
        .equals("/v3/projects/acme-prod:testIamPermissions")).findFirst().orElseThrow();
    assertThat(perms.getBody().utf8()).contains("logging.logEntries.list", "logging.sinks.update");
  }

  private static MockResponse json(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json; charset=utf-8")
        .body(body).build();
  }
}
