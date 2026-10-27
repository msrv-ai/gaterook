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
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
import tools.jackson.databind.JsonNode;

/**
 * CloudWatch Logs through the AWS SDK against moto 5.2.4 (report 07 §8): the caller's identity from STS, filter
 * patterns over two streams, a tail paged through events that share a millisecond without losing or repeating one,
 * Logs Insights from StartQuery to its results, the shared quota buckets, and AssumeRole with the connector's session
 * policy. moto implements neither filter-pattern syntax beyond words nor Insights commands, so the queries here are
 * plain words and the Insights answer is checked for shape and order, not for filtering. moto also interleaves the
 * streams of a group only when asked with {@code interleaved}, which AWS ignores and always does; the tail therefore
 * pages through one stream, and the order across streams is the connector's own sort (the search below). Profile
 * {@code -Plogs-it}; moto comes from {@code GW_MOTO_SERVER} or the cached venv
 * {@code ~/.cache/mcp-gateway-test/moto-5.2.4} (README, "Development").
 */
@Tag("logs-it")
class MotoCloudWatchIT {

  private static final String SECRET_KEY = "moto-secret-" + Long.toHexString(System.nanoTime());
  private static Process moto;
  private static URI base;
  private static Instant t0;
  private static CloudCredentials cloud;
  private static LogsConnector connector;
  private static final TestSecrets SECRETS = new TestSecrets().with("/credentials/secretAccessKey", SECRET_KEY);

  @BeforeAll
  static void startMoto() throws Exception {
    if (TestServices.enabled()) {
      // moto of the Compose services, emptied of what earlier runs made.
      base = URI.create("http://127.0.0.1:" + TestServices.port("GW_MOTO_PORT"));
      Fixtures.awaitOk(base.resolve("/moto-api/"), Duration.ofSeconds(30), null, null);
      Fixtures.send(HttpRequest.newBuilder(base.resolve("/moto-api/reset")).POST(HttpRequest.BodyPublishers.noBody())
          .build());
    } else {
      startOwnMoto();
    }
    cloud = new CloudCredentials(new CloudEndpoints(null, null, null, base, null, null, Map.of("aws.logs", base)),
        Clock.systemUTC());
    connector = new LogsConnector(cloud, Clock.systemUTC());

    t0 = Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
    try (CloudWatchLogsClient setup = CloudWatchLogsClient.builder().endpointOverride(base)
        .region(Region.EU_CENTRAL_1).httpClient(UrlConnectionHttpClient.create())
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIAIOSFODNN7EXAMPLE",
            "setup"))).build()) {
      setup.createLogGroup(r -> r.logGroupName("/aws/ecs/api"));
      setup.putRetentionPolicy(r -> r.logGroupName("/aws/ecs/api").retentionInDays(14));
      setup.createLogGroup(r -> r.logGroupName("/aws/ecs/billing"));
      List<InputLogEvent> one = new ArrayList<>();
      List<InputLogEvent> two = new ArrayList<>();
      for (int i = 0; i < 30; i++) {
        one.add(event(t0.plusSeconds(i), "{\"level\":\"" + (i % 6 == 0 ? "error" : "info") + "\",\"msg\":\"api one "
            + i + "\"}"));
        two.add(event(t0.plusSeconds(i).plusMillis(500), "WARN api two " + i));
      }
      // Seven events of two streams in one millisecond, next to the regular one of stream one: a burst a page
      // boundary falls into.
      for (int k = 0; k < 4; k++) {
        one.add(event(t0.plusSeconds(15), "INFO burst one " + k));
      }
      for (int k = 0; k < 3; k++) {
        two.add(event(t0.plusSeconds(15), "INFO burst two " + k));
      }
      one.sort(Comparator.comparing(InputLogEvent::timestamp));
      two.sort(Comparator.comparing(InputLogEvent::timestamp));
      put(setup, "/aws/ecs/api", "ecs/api/1", one);
      put(setup, "/aws/ecs/api", "ecs/api/2", two);
      put(setup, "/aws/ecs/billing", "billing/1", List.of(event(t0.plusSeconds(3), "card 4111 charged")));
      setup.createLogGroup(r -> r.logGroupName("/aws/ecs/tail"));
      List<InputLogEvent> tail = new ArrayList<>();
      for (int i = 0; i < 40; i++) {
        tail.add(event(t0.plusSeconds(i).plusMillis(250), "INFO tail " + i));
      }
      for (int k = 0; k < 7; k++) {
        tail.add(event(t0.plusSeconds(15).plusMillis(250), "INFO tail burst " + k));
      }
      tail.sort(Comparator.comparing(InputLogEvent::timestamp));
      put(setup, "/aws/ecs/tail", "ecs/tail/1", tail);
    }
  }

  /** moto 5.2.4 of its own: {@code GW_MOTO_SERVER} or the cached venv, on a free loopback port. */
  private static void startOwnMoto() throws Exception {
    String own = System.getenv("GW_MOTO_SERVER");
    Path binary = own != null && !own.isBlank() ? Path.of(own)
        : Fixtures.CACHE.resolve("moto-5.2.4").resolve("bin").resolve("moto_server");
    if (!Files.isExecutable(binary)) {
      throw new IllegalStateException("moto_server not found at " + binary + ": python3 -m venv "
          + "~/.cache/mcp-gateway-test/moto-5.2.4 && ~/.cache/mcp-gateway-test/moto-5.2.4/bin/pip install "
          + "'moto[server]==5.2.4' (or set GW_MOTO_SERVER)");
    }
    Path dir = Fixtures.workDir("moto");
    int port = Fixtures.freePort();
    Path log = dir.resolve("moto.log");
    moto = Fixtures.start(List.of(binary.toString(), "-H", "127.0.0.1", "-p", Integer.toString(port)), dir, log,
        Map.of());
    base = URI.create("http://127.0.0.1:" + port);
    Fixtures.awaitOk(base.resolve("/moto-api/"), Duration.ofSeconds(30), moto, log);
  }

  @AfterAll
  static void stopMoto() throws Exception {
    if (connector != null) {
      connector.close();
      cloud.close();
    }
    Fixtures.stop(moto);
  }

  @Test
  void tailPagesThroughEventsSharingAMillisecondWithoutLosingOrRepeatingOne() throws Exception {
    List<JsonNode> seen = new ArrayList<>();
    String cursor = null;
    for (int calls = 0; calls < 50; calls++) {
      String args = "{\"query\":\"\",\"limit\":3,\"wait\":0" + (cursor == null ? ",\"since\":\"" + t0.minusSeconds(1)
          + "\"" : ",\"cursor\":\"" + cursor + "\"") + "}";
      JsonNode s = OutputSchema.valid(connector.execute(Configs.call("logs_tail", args), cfg("/aws/ecs/tail", false),
          SECRETS, TestCtx.of())).structuredContent();
      cursor = s.path("nextCursor").asString();
      if (s.path("count").asInt() == 0) {
        break;
      }
      s.path("entries").forEach(seen::add);
    }
    List<String> messages = seen.stream().map(e -> e.path("message").asString()).toList();
    assertThat(messages).hasSize(47).doesNotHaveDuplicates();
    assertThat(Fixtures.instants(seen)).as("oldest first").isSorted();
    assertThat(seen).filteredOn(e -> e.path("timestamp").asString().equals(t0.plusSeconds(15).plusMillis(250)
        .toString())).as("eight events in one millisecond, over three pages").hasSize(8);
  }

  @Test
  void filterPatternsSearchOneGroupNewestFirstWithSeverityFromTheMessage() throws Exception {
    TestCtx ctx = TestCtx.of();
    JsonNode s = OutputSchema.valid(connector.execute(Configs.call("logs_search", "{\"query\":\"api\",\"from\":\""
        + t0.minusSeconds(1) + "\",\"to\":\"" + t0.plusSeconds(60) + "\",\"limit\":1000}"), cfg(false), SECRETS, ctx))
        .structuredContent();
    List<JsonNode> entries = new ArrayList<>();
    s.path("entries").forEach(entries::add);
    assertThat(entries).hasSize(60);
    assertThat(Fixtures.instants(entries)).as("newest first over both streams")
        .isSortedAccordingTo(Comparator.reverseOrder());
    assertThat(entries.getFirst().path("message").asString()).isEqualTo("WARN api two 29");
    assertThat(entries.getFirst().path("severity").asString()).isEqualTo("WARNING");
    assertThat(entries.getFirst().at("/labels/logStream").asString()).isEqualTo("ecs/api/2");
    assertThat(entries).filteredOn(e -> e.path("message").asString().contains("api one 0\""))
        .singleElement().satisfies(e -> assertThat(e.path("severity").asString()).isEqualTo("ERROR"));
    assertThat(ctx.quotaKeys).as("every FilterLogEvents takes its share of the account's quota")
        .allMatch(k -> k.equals("aws:key-akiaiosfodnn7example:eu-central-1:filterlogevents@120"));
    assertThat(ctx.taints).contains(SECRET_KEY);
  }

  @Test
  void logsInsightsRunsTheQueryAndCutsToTheLimitWithoutACursor() throws Exception {
    TestCtx ctx = TestCtx.of();
    ToolResult r = OutputSchema.valid(connector.execute(Configs.call("logs_search", "{\"query\":\"\",\"from\":\""
        + t0.minusSeconds(1) + "\",\"to\":\"" + t0.plusSeconds(60) + "\",\"limit\":5}"), cfg(true), SECRETS, ctx));
    JsonNode s = r.structuredContent();
    assertThat(s.path("count").asInt()).isEqualTo(5);
    assertThat(s.at("/entries/0/message").asString()).isEqualTo("WARN api two 29");
    assertThat(s.at("/entries/0/timestamp").asString()).isEqualTo(t0.plusSeconds(29).plusMillis(500).toString());
    assertThat(s.path("truncated").asBoolean()).isTrue();
    assertThat(s.path("nextCursor").isNull()).as("Insights has no cursor").isTrue();
    assertThat(r.content().getFirst().text()).contains("search again with to=");
    assertThat(ctx.quotaKeys).contains("aws:key-akiaiosfodnn7example:eu-central-1:startquery@300",
        "aws:key-akiaiosfodnn7example:eu-central-1:getqueryresults@300");
  }

  @Test
  void aSpentQuotaRefusesBeforeCloudWatchIsAsked() {
    TestCtx ctx = TestCtx.of().quota((key, perMinute) -> false);
    assertThatThrownBy(() -> connector.execute(Configs.call("logs_search", "{\"query\":\"\",\"from\":\"-1h\"}"),
        cfg(false), SECRETS, ctx)).isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
          assertThat(e.getMessage()).contains("FilterLogEvents to 2 calls per second");
        });
    assertThatThrownBy(() -> connector.execute(Configs.call("logs_search", "{\"query\":\"\",\"from\":\"-1h\"}"),
        cfg(true), SECRETS, ctx)).hasMessageContaining("CloudWatch StartQuery calls of this account are at MCP "
        + "Gateway's limit");
  }

  @Test
  void aGroupOutsideTheConnectionIsRefusedAndAMissingOneIsNamed() {
    assertThat(connector.classify(Configs.call("logs_search", "{\"query\":\"\",\"source\":\"/aws/ecs/billing\"}"),
        cfg(false), Configs.policy(Env.DEV, TestCtx.dev())).ruleId()).isEqualTo("dev.logs.source_not_allowed");

    ConnectionConfig missing = Configs.of("aws-logs", Env.DEV, """
        {"backend":"cloudwatch","region":"eu-central-1","logGroups":["/aws/ecs/gone"],"searchApi":"filter",
         "credentials":{"source":"accessKey","accessKeyId":"AKIAIOSFODNN7EXAMPLE"}}""", "/credentials/secretAccessKey");
    assertThatThrownBy(() -> connector.execute(Configs.call("logs_search", "{\"query\":\"\",\"from\":\"-1h\"}"),
        missing, SECRETS, TestCtx.of())).hasMessageContaining("CloudWatch Logs FilterLogEvents")
        .hasMessageContaining("eu-central-1");
  }

  @Test
  void theTestNamesTheIdentityTheGroupAndInsights() {
    TestResult r = connector.test(cfg(true), SECRETS, TestCtx.of());
    assertThat(r.ok()).as(r.toString()).isTrue();
    assertThat(r.identity()).isEqualTo("arn:aws:sts::123456789012:user/moto");
    assertThat(r.checks()).extracting(TestResult.Check::name).contains("identity", "log group /aws/ecs/api", "read",
        "insights");
    assertThat(r.checks()).filteredOn(c -> c.name().equals("log group /aws/ecs/api")).singleElement()
        .satisfies(c -> assertThat(c.detail()).startsWith("kept 14 days"));
  }

  @Test
  void anAssumedRoleReadsWithTheSessionPolicy() throws Exception {
    ConnectionConfig role = Configs.of("aws-logs", Env.DEV, """
        {"backend":"cloudwatch","region":"eu-central-1","logGroups":["/aws/ecs/api"],"searchApi":"filter",
         "credentials":{"source":"accessKey","accessKeyId":"AKIAIOSFODNN7EXAMPLE",
           "roleArn":"arn:aws:iam::123456789012:role/gw-logs-read","externalId":"gw-ext-5c1d"}}""",
        "/credentials/secretAccessKey");
    TestResult r = connector.test(role, SECRETS, TestCtx.of());
    assertThat(r.ok()).as(r.toString()).isTrue();
    assertThat(r.identity()).contains("assumed-role/gw-logs-read/");

    TestCtx ctx = TestCtx.of();
    JsonNode s = connector.execute(Configs.call("logs_search", "{\"query\":\"burst\",\"from\":\"-1h\",\"limit\":100}"),
        role, SECRETS, ctx).structuredContent();
    assertThat(s.path("count").asInt()).isEqualTo(7);
    assertThat(ctx.quotaKeys).as("the quota bucket is the role's account")
        .allMatch(k -> k.startsWith("aws:123456789012:eu-central-1:"));
    assertThat(ctx.taints).as("the session's keys join the taint").anyMatch(t -> !t.equals(SECRET_KEY));
  }

  private static ConnectionConfig cfg(boolean insights) {
    return cfg("/aws/ecs/api", insights);
  }

  private static ConnectionConfig cfg(String group, boolean insights) {
    return Configs.of("aws-logs", Env.DEV, """
        {"backend":"cloudwatch","region":"eu-central-1","logGroups":["%s"],"searchApi":"%s",
         "credentials":{"source":"accessKey","accessKeyId":"AKIAIOSFODNN7EXAMPLE"}}"""
        .formatted(group, insights ? "insights" : "filter"), "/credentials/secretAccessKey");
  }

  private static InputLogEvent event(Instant at, String message) {
    return InputLogEvent.builder().timestamp(at.toEpochMilli()).message(message).build();
  }

  private static void put(CloudWatchLogsClient c, String group, String stream, List<InputLogEvent> events) {
    c.createLogStream(r -> r.logGroupName(group).logStreamName(stream));
    c.putLogEvents(r -> r.logGroupName(group).logStreamName(stream).logEvents(events));
  }
}
