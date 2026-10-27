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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import tools.jackson.databind.JsonNode;

/**
 * CloudWatch Logs through the SDK against a fake of its JSON 1.1 protocol (report 07 §8): Logs Insights with its
 * partial answers and its deadline, filter patterns newest first and oldest first, throttling, the quota buckets, and
 * an SDK client that can't be talked into anything but reading.
 */
class CloudWatchBackendTest {

  private static final String SECRET_KEY = "cnry-aws-secret-" + Long.toHexString(System.nanoTime());
  private final MockWebServer server = new MockWebServer();
  private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private final Map<String, Function<JsonNode, MockResponse>> answers = new ConcurrentHashMap<>();
  private CloudCredentials cloud;
  private LogsConnector connector;
  private final TestSecrets secrets = new TestSecrets().with("/credentials/secretAccessKey", SECRET_KEY);

  @BeforeEach
  void start() throws IOException {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        String body = request.getBody() == null ? "" : request.getBody().utf8();
        if (body.contains("Action=GetCallerIdentity")) {
          return new MockResponse.Builder().code(200).addHeader("Content-Type", "text/xml").body(
              "<GetCallerIdentityResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\"><GetCallerIdentityResult>"
              + "<Arn>arn:aws:iam::123456789012:user/gw-logs</Arn><UserId>AIDA1</UserId><Account>123456789012"
              + "</Account></GetCallerIdentityResult><ResponseMetadata><RequestId>1</RequestId></ResponseMetadata>"
              + "</GetCallerIdentityResponse>").build();
        }
        String target = request.getHeaders().get("X-Amz-Target");
        String operation = target == null ? "" : target.substring(target.indexOf('.') + 1);
        Function<JsonNode, MockResponse> answer = answers.get(operation);
        if (answer == null) {
          return error(400, "UnknownOperationException", operation);
        }
        return answer.apply(TestCtx.JSON.readTree(body.isEmpty() ? "{}" : body));
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    URI base = server.url("/").uri();
    cloud = new CloudCredentials(new CloudEndpoints(null, null, null, base, null, null, Map.of("aws.logs", base)),
        Clock.systemUTC());
    connector = new LogsConnector(cloud, Clock.systemUTC());
  }

  @AfterEach
  void stop() throws IOException {
    connector.close();
    cloud.close();
    server.close();
  }

  @Test
  void logsInsightsRunsTheQueryAsShownOverTheConnectionsGroups() throws Exception {
    answers.put("StartQuery", b -> json("{\"queryId\":\"q-1\"}"));
    int[] polls = {0};
    answers.put("GetQueryResults", b -> polls[0]++ == 0
        ? json("{\"status\":\"Running\",\"results\":[" + row("2026-10-22 12:29:58.123", "ERROR payment failed", "p1")
            + "]}")
        : json("{\"status\":\"Complete\",\"statistics\":{\"bytesScanned\":52428.0,\"recordsScanned\":120.0,"
            + "\"recordsMatched\":2.0},\"results\":[" + row("2026-10-22 12:29:58.123", "ERROR payment failed", "p1")
            + "," + row("2026-10-22 12:20:00.000", "{\"level\":\"warn\",\"msg\":\"slow\"}", "p2") + "]}"));
    ConnectionConfig cfg = Configs.cloudWatch(true);
    TestCtx ctx = TestCtx.of();

    ToolResult r = OutputSchema.valid(connector.execute(Configs.call("logs_search", "{\"query\":\"filter @message "
        + "like /ERROR|warn/\",\"from\":\"2026-10-22T12:00:00Z\",\"to\":\"2026-10-22T12:30:00Z\",\"limit\":10}"), cfg,
        secrets, ctx));

    JsonNode start = TestCtx.JSON.readTree(find("StartQuery").getBody().utf8());
    assertThat(start.path("queryLanguage").asString()).isEqualTo("CWLI");
    assertThat(start.path("logGroupNames").toString()).as("plain names go by name")
        .isEqualTo("[\"/aws/ecs/api\",\"/aws/ecs/worker\"]");
    assertThat(start.has("logGroupIdentifiers")).isFalse();
    assertThat(start.path("queryString").asString()).isEqualTo("fields @timestamp, @message, @logStream, @log | "
        + "filter @message like /ERROR|warn/ | sort @timestamp desc");
    assertThat(start.path("limit").asInt()).isEqualTo(11);
    assertThat(start.path("startTime").asLong()).isEqualTo(1792670400L);
    assertThat(start.path("endTime").asLong()).isEqualTo(1792672200L);
    assertThat(find("StartQuery").getHeaders().get("Authorization")).contains("AKIAIOSFODNN7EXAMPLE/");
    assertThat(ctx.taints).as("the secret key is tainted when the SDK resolves it").contains(SECRET_KEY);
    assertThat(ctx.quotaKeys).contains("aws:key-akiaiosfodnn7example:eu-central-1:startquery@300");

    JsonNode s = r.structuredContent();
    assertThat(s.path("count").asInt()).isEqualTo(2);
    assertThat(s.at("/entries/0/timestamp").asString()).isEqualTo("2026-10-22T12:29:58.123Z");
    assertThat(s.at("/entries/0/severity").asString()).isEqualTo("ERROR");
    assertThat(s.at("/entries/0/labels/logStream").asString()).isEqualTo("ecs/api/1");
    assertThat(s.at("/entries/0/id").asString()).isEqualTo("p1");
    assertThat(s.at("/entries/1/severity").asString()).as("the level of a JSON message").isEqualTo("WARNING");
    assertThat(s.at("/stats/bytesScanned").asLong()).isEqualTo(52428);
    assertThat(s.at("/stats/requests").asInt()).isEqualTo(3);
    assertThat(s.path("truncated").asBoolean()).isFalse();
  }

  @Test
  void anotherAccountsGroupGoesByItsArn() throws Exception {
    answers.put("StartQuery", b -> json("{\"queryId\":\"q-3\"}"));
    answers.put("GetQueryResults", b -> json("{\"status\":\"Complete\",\"results\":[]}"));
    ConnectionConfig cfg = Configs.of("aws-logs", Env.DEV, """
        {"backend":"cloudwatch","region":"eu-central-1","searchApi":"insights",
         "logGroups":["/aws/ecs/api","arn:aws:logs:eu-central-1:210987654321:log-group:/shared/app:*"],
         "credentials":{"source":"accessKey","accessKeyId":"AKIAIOSFODNN7EXAMPLE"}}""", "/credentials/secretAccessKey");

    connector.execute(Configs.call("logs_search", "{\"query\":\"\",\"from\":\"-1h\"}"), cfg, secrets, TestCtx.of());

    JsonNode start = TestCtx.JSON.readTree(find("StartQuery").getBody().utf8());
    assertThat(start.path("logGroupIdentifiers").toString()).isEqualTo("[\"/aws/ecs/api\","
        + "\"arn:aws:logs:eu-central-1:210987654321:log-group:/shared/app\"]");
    assertThat(start.has("logGroupNames")).isFalse();
  }

  @Test
  void anInsightsQueryStoppedByTheDeadlineIsStoppedAndAnsweredWithWhatItFound() throws Exception {
    answers.put("StartQuery", b -> json("{\"queryId\":\"q-2\"}"));
    answers.put("GetQueryResults", b -> json("{\"status\":\"Running\",\"results\":["
        + row("2026-10-22 12:29:58.123", "early", "p1") + "]}"));
    answers.put("StopQuery", b -> json("{\"success\":true}"));

    ToolResult r = OutputSchema.valid(connector.execute(Configs.call("logs_search", "{\"query\":\"\",\"from\":"
        + "\"2026-10-22T12:00:00Z\",\"to\":\"2026-10-22T12:30:00Z\"}"), Configs.cloudWatch(true), secrets,
        TestCtx.of().timeout(Duration.ofMillis(1_800))));

    assertThat(find("StopQuery").getBody().utf8()).contains("q-2");
    JsonNode s = r.structuredContent();
    assertThat(s.path("truncatedReason").asString()).isEqualTo("partial");
    assertThat(s.at("/entries/0/message").asString()).isEqualTo("early");
    assertThat(s.path("nextCursor").isNull()).as("Insights has no cursor").isTrue();
    assertThat(r.content().getFirst().text()).contains("The backend stopped before it finished");
  }

  @Test
  void statsAreATable() throws Exception {
    answers.put("StartQuery", b -> json("{\"queryId\":\"q-3\"}"));
    answers.put("GetQueryResults", b -> json("{\"status\":\"Complete\",\"results\":[[{\"field\":\"bin(5m)\","
        + "\"value\":\"2026-10-22 12:25:00.000\"},{\"field\":\"count(*)\",\"value\":\"17\"}]]}"));

    JsonNode s = OutputSchema.valid(connector.execute(Configs.call("logs_search",
        "{\"query\":\"stats count(*) by bin(5m)\"}"), Configs.cloudWatch(true), secrets, TestCtx.of()))
        .structuredContent();

    assertThat(s.at("/aggregate/columns").toString()).isEqualTo("[\"bin(5m)\",\"count(*)\"]");
    assertThat(s.at("/aggregate/rows/0/1").asString()).isEqualTo("17");
  }

  @Test
  void filterPatternsSearchNewestFirstInOneGroupAndPageWithTheCursor() throws Exception {
    answers.put("FilterLogEvents", b -> json("{\"events\":[{\"logStreamName\":\"api/1\",\"timestamp\":1792672190000,"
        + "\"message\":\"WARN slow\",\"eventId\":\"e2\"},{\"logStreamName\":\"api/1\",\"timestamp\":1792672198123,"
        + "\"message\":\"ERROR boom\",\"eventId\":\"e1\"}]}"));
    TestCtx ctx = TestCtx.of();

    JsonNode s = OutputSchema.valid(connector.execute(Configs.call("logs_search", "{\"query\":\"?ERROR ?WARN\","
        + "\"source\":\"/aws/ecs/worker\",\"from\":\"2026-10-22T12:00:00Z\",\"to\":\"2026-10-22T12:30:00Z\","
        + "\"limit\":1}"), Configs.cloudWatch(false), secrets, ctx)).structuredContent();

    JsonNode req = TestCtx.JSON.readTree(find("FilterLogEvents").getBody().utf8());
    assertThat(req.path("logGroupName").asString()).isEqualTo("/aws/ecs/worker");
    assertThat(req.path("startFromHead").asBoolean(true)).as("newest first").isFalse();
    assertThat(req.path("filterPattern").asString()).isEqualTo("?ERROR ?WARN");
    assertThat(req.path("limit").asInt()).isEqualTo(2);
    assertThat(req.path("startTime").asLong()).isEqualTo(1792670400000L);
    assertThat(req.path("endTime").asLong()).isEqualTo(1792672200000L);
    assertThat(req.path("unmask").asBoolean(true)).isFalse();
    assertThat(s.at("/entries/0/message").asString()).isEqualTo("ERROR boom");
    assertThat(s.at("/entries/0/severity").asString()).isEqualTo("ERROR");
    assertThat(s.path("nextCursor").asString()).startsWith("lc1.");
    assertThat(ctx.quotaKeys).containsExactly("aws:key-akiaiosfodnn7example:eu-central-1:filterlogevents@120");
  }

  @Test
  void tailReadsOldestFirstFiveSecondsBehindNow() throws Exception {
    long now = System.currentTimeMillis();
    answers.put("FilterLogEvents", b -> json("{\"events\":[{\"logStreamName\":\"api/1\",\"timestamp\":" + (now - 30_000)
        + ",\"message\":\"first\",\"eventId\":\"e1\"}]}"));

    JsonNode s = OutputSchema.valid(connector.execute(Configs.call("logs_tail", "{\"query\":\"\",\"since\":\"-1m\","
        + "\"wait\":0}"), Configs.cloudWatch(true), secrets, TestCtx.of())).structuredContent();

    JsonNode req = TestCtx.JSON.readTree(find("FilterLogEvents").getBody().utf8());
    assertThat(req.path("startFromHead").asBoolean()).isTrue();
    assertThat(req.path("logGroupName").asString()).as("one group per call: the first").isEqualTo("/aws/ecs/api");
    assertThat(now - req.path("endTime").asLong()).isBetween(4_000L, 6_500L);
    assertThat(req.has("filterPattern")).isFalse();
    assertThat(s.at("/entries/0/message").asString()).isEqualTo("first");
  }

  @Test
  void throttlingIsToldNotHidden() {
    answers.put("FilterLogEvents", b -> error(400, "ThrottlingException", "Rate exceeded"));

    assertThatThrownBy(() -> connector.execute(Configs.call("logs_search", "{\"query\":\"x\"}"),
        Configs.cloudWatch(false), secrets, TestCtx.of().timeout(Duration.ofSeconds(20))))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.getMessage()).isEqualTo("CloudWatch is throttling FilterLogEvents, try again in a few seconds.");
          assertThat(e.countsForCircuitBreaker()).isFalse();
        });
  }

  @Test
  void aMissingGroupIsNamed() {
    answers.put("FilterLogEvents", b -> error(400, "ResourceNotFoundException", "The specified log group does not "
        + "exist."));

    assertThatThrownBy(() -> connector.execute(Configs.call("logs_tail", "{\"query\":\"\",\"wait\":0,\"since\":\"-1m\"}"),
        Configs.cloudWatch(true), secrets, TestCtx.of())).hasMessage("CloudWatch Logs FilterLogEvents: The "
        + "specified log group does not exist. (region eu-central-1).");
  }

  @Test
  void theClientRefusesEveryOperationThatIsntReading() {
    try (Clients clients = new Clients()) {
      CloudWatchLogsClient cw = clients.cloudWatch(Configs.cloudWatch(true), "eu-central-1", server.url("/").uri());

      assertThatThrownBy(() -> cw.deleteLogGroup(r -> r.logGroupName("/aws/ecs/api")))
          .isInstanceOf(SecurityException.class).hasMessageContaining("DeleteLogGroup");
      assertThatThrownBy(() -> cw.putLogEvents(r -> r.logGroupName("/aws/ecs/api").logStreamName("x")))
          .isInstanceOf(SecurityException.class);
      assertThat(requests).as("nothing reached the network").isEmpty();
    }
  }

  @Test
  void testConnectionShowsWhoAndWhatItReads() {
    answers.put("DescribeLogGroups", b -> json("{\"logGroups\":[{\"logGroupName\":\""
        + b.path("logGroupNamePrefix").asString() + "\",\"retentionInDays\":30,\"storedBytes\":10485760}]}"));
    answers.put("FilterLogEvents", b -> json("{\"events\":[]}"));
    answers.put("StartQuery", b -> json("{\"queryId\":\"probe\"}"));
    answers.put("StopQuery", b -> json("{\"success\":true}"));

    TestResult t = connector.test(Configs.cloudWatch(true), secrets, TestCtx.of());

    assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
    assertThat(t.identity()).isEqualTo("arn:aws:iam::123456789012:user/gw-logs");
    assertThat(t.checks()).extracting(TestResult.Check::name).containsExactly("identity", "log group /aws/ecs/api",
        "log group /aws/ecs/worker", "read", "insights");
    assertThat(t.checks().get(1).detail()).isEqualTo("kept 30 days, 10 MB");
    assertThat(t.checks().get(3).detail()).isEqualTo("no events in the last hour");
    assertThat(find("StopQuery").getBody().utf8()).contains("probe");
  }

  @Test
  void aGroupThatIsntThereFailsTheTest() {
    answers.put("DescribeLogGroups", b -> json("{\"logGroups\":[{\"logGroupName\":\"/aws/ecs/api-old\"}]}"));

    TestResult t = connector.test(Configs.cloudWatch(true), secrets, TestCtx.of());

    assertThat(t.ok()).isFalse();
    assertThat(t.error().message()).isEqualTo("Log group /aws/ecs/api not found in eu-central-1.");
  }

  @Test
  void insightsTimestampsInEveryShape() {
    assertThat(CloudWatchBackend.insightsTime("2026-10-22 12:29:58.123")).isEqualTo(1792672198123000000L);
    assertThat(CloudWatchBackend.insightsTime("2026-10-22 12:29:58")).isEqualTo(1792672198000000000L);
    assertThat(CloudWatchBackend.insightsTime("1792672198123")).isEqualTo(1792672198123000000L);
    assertThat(CloudWatchBackend.insightsTime("2026-10-22T12:29:58.123Z")).isEqualTo(1792672198123000000L);
    assertThat(Instant.ofEpochMilli(CloudWatchBackend.insightsTime("nonsense"))).isEqualTo(Instant.EPOCH);
  }

  private RecordedRequest find(String operation) {
    return requests.stream().filter(r -> ("Logs_20140328." + operation).equals(r.getHeaders().get("X-Amz-Target")))
        .findFirst().orElseThrow(() -> new AssertionError("no " + operation + " in " + requests));
  }

  private static String row(String ts, String message, String ptr) {
    return "[{\"field\":\"@timestamp\",\"value\":\"" + ts + "\"},{\"field\":\"@message\",\"value\":"
        + TestCtx.JSON.writeValueAsString(message) + "},{\"field\":\"@logStream\",\"value\":\"ecs/api/1\"},"
        + "{\"field\":\"@log\",\"value\":\"123456789012:/aws/ecs/api\"},{\"field\":\"@ptr\",\"value\":\"" + ptr
        + "\"}]";
  }

  private static MockResponse json(String body) {
    return new MockResponse.Builder().code(200).addHeader("Content-Type", "application/x-amz-json-1.1").body(body)
        .build();
  }

  private static MockResponse error(int code, String type, String message) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/x-amz-json-1.1")
        .addHeader("x-amzn-ErrorType", type).body("{\"__type\":\"" + type + "\",\"message\":\"" + message + "\"}")
        .build();
  }
}
