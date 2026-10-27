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
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The connector against a real Loki 3.7.9 (single binary, filesystem storage, multi-tenant): streams merged into one
 * timeline, a cursor that pages through entries sharing a timestamp without losing or repeating any, a tail that
 * picks up entries pushed while it waits, the tenant as the only boundary between teams, and Loki's own words for a
 * bad query. Profile {@code -Plogs-it}; the binary is downloaded once and checked against its SHA-256, or comes from
 * {@code GW_LOKI_BINARY} on other platforms.
 */
@Tag("logs-it")
class LokiIT {

  private static final String URL = "https://github.com/grafana/loki/releases/download/v3.7.9/loki-linux-amd64.zip";
  private static final String SHA256 = "17a5fae28ce733b3560c27c76620920b169bd2aea9473947b733b511f92e81dd";

  private static Process loki;
  private static URI base;
  private static Instant t0;
  private static final CloudCredentials CLOUD = new CloudCredentials(CloudEndpoints.PRODUCTION, Clock.systemUTC());
  private static final LogsConnector CONNECTOR = new LogsConnector(CLOUD, Clock.systemUTC());
  private static final TestSecrets NO_SECRETS = new TestSecrets();

  /** Tenants of this run: a Loki of the Compose services keeps what earlier runs pushed, so they get a suffix. */
  private static final String RUN = TestServices.enabled() ? "-" + Long.toString(System.currentTimeMillis(), 36) : "";

  @BeforeAll
  static void startLoki() throws Exception {
    if (TestServices.enabled()) {
      base = URI.create("http://127.0.0.1:" + TestServices.port("GW_LOKI_PORT"));
      Fixtures.awaitOk(base.resolve("/ready"), Duration.ofSeconds(90), null, null);
    } else {
      startOwnLoki();
    }

    // Ten minutes back, on a whole millisecond plus a few nanoseconds: Loki keeps nanoseconds and so must the cursor.
    t0 = Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS).plusNanos(7);
    List<String[]> api1 = new ArrayList<>();
    List<String[]> api2 = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      api1.add(entry(t0.plusSeconds(i), (i % 10 == 0 ? "level=error " : "level=info ") + "msg=\"api-1 request " + i
          + "\""));
      api2.add(entry(t0.plusSeconds(i).plusMillis(500), "{\"level\":\"warn\",\"msg\":\"api-2 request " + i + "\"}"));
    }
    // Five more entries on api-2 at the very instant of api-1's 30th, and two streams sharing it: a burst a page
    // boundary falls into.
    for (int k = 0; k < 5; k++) {
      api2.add(entry(t0.plusSeconds(30), "api-2 burst " + k));
    }
    api2.sort((a, b) -> a[0].compareTo(b[0]));
    List<String[]> web = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      web.add(entry(t0.plusSeconds(i), "web hit " + i));
    }
    push("acme", Map.of("app", "api", "pod", "api-1"), api1);
    push("acme", Map.of("app", "api", "pod", "api-2"), api2);
    push("acme", Map.of("app", "web"), web);
    push("other-team", Map.of("app", "api", "pod", "api-1"), List.<String[]>of(entry(t0.plusSeconds(5), "other team's entry")));
  }

  /** Loki 3.7.9 of its own: the pinned binary (or {@code GW_LOKI_BINARY}) on free loopback ports. */
  private static void startOwnLoki() throws Exception {
    Path dir = Fixtures.workDir("loki");
    Path binary;
    String own = System.getenv("GW_LOKI_BINARY");
    if (own != null && !own.isBlank()) {
      binary = Path.of(own);
    } else if (Fixtures.linuxX64()) {
      binary = Fixtures.unzipOne(Fixtures.download("loki-3.7.9", "loki-linux-amd64.zip", URL, SHA256),
          "loki-linux-amd64", dir);
    } else {
      throw new IllegalStateException("Set GW_LOKI_BINARY to a Loki 3.7.9 binary of this platform");
    }
    int http = Fixtures.freePort();
    int grpc = Fixtures.freePort();
    Path config = dir.resolve("loki.yaml");
    Files.writeString(config, """
        auth_enabled: true
        server:
          http_listen_address: 127.0.0.1
          http_listen_port: %d
          grpc_listen_address: 127.0.0.1
          grpc_listen_port: %d
          log_level: warn
        common:
          instance_addr: 127.0.0.1
          path_prefix: %s
          storage:
            filesystem:
              chunks_directory: %s/chunks
              rules_directory: %s/rules
          replication_factor: 1
          ring:
            kvstore:
              store: inmemory
        ingester:
          lifecycler:
            min_ready_duration: 0s
            final_sleep: 0s
        schema_config:
          configs:
            - from: 2024-01-01
              store: tsdb
              object_store: filesystem
              schema: v13
              index:
                prefix: index_
                period: 24h
        limits_config:
          allow_structured_metadata: true
          discover_log_levels: true
          reject_old_samples: false
        analytics:
          reporting_enabled: false
        """.formatted(http, grpc, dir.resolve("data"), dir.resolve("data"), dir.resolve("data")));
    Path log = dir.resolve("loki.log");
    loki = Fixtures.start(List.of(binary.toString(), "-config.file=" + config), dir, log, Map.of());
    base = URI.create("http://127.0.0.1:" + http);
    Fixtures.awaitOk(base.resolve("/ready"), Duration.ofSeconds(90), loki, log);
  }

  @AfterAll
  static void stopLoki() throws Exception {
    CONNECTOR.close();
    CLOUD.close();
    Fixtures.stop(loki);
  }

  @Test
  void pagesThroughMergedStreamsWithoutLosingOrRepeatingAnEntry() throws Exception {
    String range = "\"from\":\"" + t0.minusSeconds(1) + "\",\"to\":\"" + t0.plusSeconds(120) + "\"";
    List<JsonNode> all = entries(OutputSchema.valid(CONNECTOR.execute(Configs.call("logs_search",
        "{\"query\":\"{app=\\\"api\\\"}\"," + range + ",\"limit\":1000}"), cfg("acme"), NO_SECRETS, TestCtx.of())));
    assertThat(all).hasSize(125);

    for (int limit : new int[] {1, 3, 7, 50}) {
      List<JsonNode> paged = new ArrayList<>();
      String cursor = null;
      int calls = 0;
      do {
        String args = "{\"query\":\"{app=\\\"api\\\"}\"," + range + ",\"limit\":" + limit
            + (cursor == null ? "" : ",\"cursor\":\"" + cursor + "\"") + "}";
        JsonNode s = OutputSchema.valid(CONNECTOR.execute(Configs.call("logs_search", args), cfg("acme"), NO_SECRETS,
            TestCtx.of())).structuredContent();
        assertThat(s.path("gapPossible").asBoolean()).isFalse();
        s.path("entries").forEach(paged::add);
        cursor = s.path("nextCursor").isNull() ? null : s.path("nextCursor").asString();
        calls++;
      } while (cursor != null && calls < 200);
      assertThat(cursor).as("limit %d ends", limit).isNull();
      // Entries sharing a timestamp come in the order Loki picks for each page; each comes exactly once.
      assertThat(messages(paged)).as("limit %d: the same entries", limit).doesNotHaveDuplicates()
          .containsExactlyInAnyOrderElementsOf(messages(all));
      assertThat(Fixtures.instants(paged)).as("limit %d: newest first across pages", limit)
          .isSortedAccordingTo(Comparator.reverseOrder());
    }

    assertThat(Fixtures.instants(all)).as("newest first across both streams")
        .isSortedAccordingTo(Comparator.reverseOrder());
    assertThat(messages(all)).doesNotHaveDuplicates().noneMatch(m -> m.contains("web hit") || m.contains("other team"));
    assertThat(ids(all)).as("every entry has its own id").hasSize(all.size());
    assertThat(all.stream().filter(e -> e.path("timestamp").asString().equals(t0.plusSeconds(30).toString())))
        .as("six entries share one instant").hasSize(6);
    JsonNode first = all.getFirst();
    assertThat(first.path("message").asString()).contains("api-2 request 59");
    assertThat(first.path("severity").asString()).as("Loki's detected_level").isEqualTo("WARNING");
    assertThat(first.at("/labels/pod").asString()).isEqualTo("api-2");
  }

  @Test
  void oneTenantNeverSeesAnother() throws Exception {
    JsonNode s = CONNECTOR.execute(Configs.call("logs_search", "{\"query\":\"{app=\\\"api\\\"}\",\"from\":\"-1h\","
        + "\"limit\":1000}"), cfg("other-team"), NO_SECRETS, TestCtx.of()).structuredContent();
    assertThat(messages(entries(s))).containsExactly("other team's entry");
  }

  @Test
  void filtersAndLevelsAreLokis() throws Exception {
    JsonNode s = CONNECTOR.execute(Configs.call("logs_search", "{\"query\":\"{app=\\\"api\\\", pod=\\\"api-1\\\"} "
        + "|= \\\"level=error\\\"\",\"from\":\"-1h\",\"limit\":100}"), cfg("acme"), NO_SECRETS, TestCtx.of())
        .structuredContent();
    assertThat(entries(s)).hasSize(6).allSatisfy(e -> assertThat(e.path("severity").asString()).isEqualTo("ERROR"));
  }

  @Test
  void aMetricQueryAnswersWithATable() throws Exception {
    ToolResult r = OutputSchema.valid(CONNECTOR.execute(Configs.call("logs_search", "{\"query\":\"sum by (pod) "
        + "(count_over_time({app=\\\"api\\\"}[1h]))\",\"from\":\"" + t0.minusSeconds(60) + "\",\"to\":\""
        + t0.plusSeconds(120) + "\"}"), cfg("acme"), NO_SECRETS, TestCtx.of()));
    JsonNode aggregate = r.structuredContent().path("aggregate");
    assertThat(aggregate.path("columns").toString()).contains("pod");
    assertThat(aggregate.path("rows").size()).isPositive();
    assertThat(r.content().getFirst().text()).contains("api-1").contains("api-2");
  }

  @Test
  void tailsEntriesPushedWhileItWaitsAndHandsEachOutOnce() throws Exception {
    String query = "{\"query\":\"{app=\\\"api\\\", pod=\\\"api-tail\\\"}\",\"limit\":100";
    JsonNode first = OutputSchema.valid(CONNECTOR.execute(Configs.call("logs_tail", query + ",\"wait\":0}"),
        cfg("acme"), NO_SECRETS, TestCtx.of())).structuredContent();
    assertThat(first.path("count").asInt()).isZero();
    String cursor = first.path("nextCursor").asString();

    // After the first call's cursor, which holds the nanosecond it was made at.
    Instant at = Instant.now().plusMillis(5).truncatedTo(ChronoUnit.MILLIS);
    Thread.ofVirtual().start(() -> {
      try {
        Thread.sleep(500);
        push("acme", Map.of("app", "api", "pod", "api-tail"), List.of(entry(at, "tail one"), entry(at, "tail two"),
            entry(at.plusMillis(1), "tail three")));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    });
    long started = System.nanoTime();
    Tailed second = tail(query, cursor, 3);
    // "tail one" and "tail two" share their nanosecond: a tie goes by the entry's id (a hash), not by the push.
    assertThat(second.messages()).as("oldest first").containsExactlyInAnyOrder("tail one", "tail two", "tail three")
        .endsWith("tail three");
    assertThat(Duration.ofNanos(System.nanoTime() - started)).as("returns once entries arrive")
        .isLessThan(Duration.ofSeconds(7));

    // One more at the last instant handed out and one after it: only those two come next.
    push("acme", Map.of("app", "api", "pod", "api-tail"), List.of(entry(at.plusMillis(1), "tail four"),
        entry(at.plusMillis(2), "tail five")));
    Tailed third = tail(query, second.cursor(), 2);
    assertThat(third.messages()).containsExactly("tail four", "tail five");

    JsonNode fourth = CONNECTOR.execute(Configs.call("logs_tail", query + ",\"wait\":2,\"cursor\":\""
        + third.cursor() + "\"}"), cfg("acme"), NO_SECRETS, TestCtx.of()).structuredContent();
    assertThat(fourth.path("count").asInt()).isZero();
    assertThat(state(fourth.path("nextCursor").asString())).as("nothing new: the cursor stays where it was")
        .isEqualTo(state(third.cursor()));
  }

  /** What a few tails handed out, in order, and the cursor after them. */
  private record Tailed(List<String> messages, String cursor) {}

  /**
   * {@code logs_tail} from the cursor until {@code n} entries came: the window ends two seconds behind now, so entries
   * a millisecond apart may come in two calls — each once, in order, which is what the tail promises.
   */
  private static Tailed tail(String query, String cursor, int n) throws Exception {
    List<String> got = new ArrayList<>();
    String at = cursor;
    for (int call = 0; call < 4 && got.size() < n; call++) {
      JsonNode s = OutputSchema.valid(CONNECTOR.execute(Configs.call("logs_tail", query + ",\"wait\":8,"
          + "\"cursor\":\"" + at + "\"}"), cfg("acme"), NO_SECRETS, TestCtx.of())).structuredContent();
      got.addAll(messages(entries(s)));
      at = s.path("nextCursor").asString();
    }
    return new Tailed(got, at);
  }

  /** What a cursor holds, without its expiry (sealed afresh on every answer). */
  private static JsonNode state(String cursor) {
    String handle = cursor.substring("lc1.".length());
    return TestCtx.JSON.readTree(java.util.Base64.getUrlDecoder().decode(handle.substring(0, handle.indexOf('.'))))
        .path("d");
  }

  @Test
  void lokisParseErrorReachesTheAgentInItsOwnWords() {
    assertThatThrownBy(() -> CONNECTOR.execute(Configs.call("logs_search", "{\"query\":\"{app=api}\"}"), cfg("acme"),
        NO_SECRETS, TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
          assertThat(e.getMessage()).startsWith("Loki refused the query: ").contains("parse error");
        });
  }

  @Test
  void withoutATenantLokiSaysWhatToSet() {
    ConnectionConfig noTenant = Configs.of("app-logs", Env.DEV, """
        {"backend":"loki","url":"%s","auth":{"mode":"none"}}""".formatted(base));
    assertThatThrownBy(() -> CONNECTOR.execute(Configs.call("logs_search", "{\"query\":\"{app=\\\"api\\\"}\"}"),
        noTenant, NO_SECRETS, TestCtx.of())).hasMessageContaining("Tenant (X-Scope-OrgID)");
  }

  @Test
  void theTestReportsTheVersionAndThatThisLokiAcceptsWrites() {
    TestResult r = CONNECTOR.test(cfg("acme"), NO_SECRETS, TestCtx.of());
    assertThat(r.ok()).as(r.toString()).isTrue();
    assertThat(r.serverVersion()).contains("3.7.9");
    assertThat(r.permissions().level()).as("Loki without a gateway in front takes pushes")
        .isEqualTo(TestResult.Level.WRITE);
    assertThat(r.hints()).anySatisfy(h -> assertThat(h.code()).isEqualTo("logs.can-write"));
  }

  @Test
  void describesLabelsAndTheirValues() throws Exception {
    JsonNode labels = CONNECTOR.describe(cfg("acme"), NO_SECRETS, TestCtx.of(), null).orElseThrow();
    assertThat(labels.toString()).contains("\"app\"").contains("\"pod\"");
    JsonNode pods = CONNECTOR.describe(cfg("acme"), NO_SECRETS, TestCtx.of(), "pod").orElseThrow();
    assertThat(pods.toString()).contains("api-1").contains("api-2");
  }

  /** The native smoke's fixture touch ({@code smoke/logs.json}, fixture {@code loki}) holds against this Loki. */
  @Test
  void theSmokeTouchHoldsAgainstThisLoki() throws Exception {
    JsonNode touch = null;
    for (JsonNode t : SmokeDescriptorTest.descriptor().path("touches")) {
      if (t.path("fixture").asString("").equals("loki")) {
        touch = t;
      }
    }
    assertThat(touch).isNotNull();
    push("smoke", Map.of("app", "smoke"), List.of(entry(Instant.now().minusSeconds(30), "smoke one"),
        entry(Instant.now().minusSeconds(20), "smoke two")));
    ConnectionConfig cfg = SmokeDescriptorTest.connection(touch, Map.of("url", base.toString(), "tenant", "smoke" + RUN));

    ToolResult r = OutputSchema.valid(CONNECTOR.execute(new ai.msrv.gaterook.connector.api.ToolCall("tc_smoke",
        touch.at("/call/tool").asString(), cfg.name(), (ObjectNode) touch.at("/call/arguments"), null), cfg,
        NO_SECRETS, TestCtx.of()));
    JsonNode expected = touch.at("/expect/structuredContent");
    expected.properties().forEach(e -> assertThat(r.structuredContent().get(e.getKey())).as(e.getKey())
        .isEqualTo(e.getValue()));
    assertThat(r.structuredContent().path("nextCursor").asString()).startsWith("lc1.");
  }

  private static ConnectionConfig cfg(String tenant) {
    return Configs.of("app-logs", Env.DEV, """
        {"backend":"loki","url":"%s","tenant":"%s","auth":{"mode":"none"}}""".formatted(base, tenant + RUN));
  }

  private static String[] entry(Instant at, String line) {
    long ns = at.getEpochSecond() * 1_000_000_000L + at.getNano();
    return new String[] {Long.toString(ns), line};
  }

  private static void push(String tenant, Map<String, String> labels, List<String[]> values) throws Exception {
    ObjectNode body = JsonNodeFactory.instance.objectNode();
    ObjectNode stream = body.putArray("streams").addObject();
    ObjectNode l = stream.putObject("stream");
    labels.forEach(l::put);
    ArrayNode v = stream.putArray("values");
    for (String[] e : values) {
      v.addArray().add(e[0]).add(e[1]);
    }
    HttpResponse<String> r = Fixtures.send(HttpRequest.newBuilder(base.resolve("/loki/api/v1/push"))
        .header("Content-Type", "application/json").header("X-Scope-OrgID", tenant + RUN)
        .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(204);
  }

  private static List<JsonNode> entries(ToolResult r) {
    return entries(r.structuredContent());
  }

  private static List<JsonNode> entries(JsonNode s) {
    List<JsonNode> out = new ArrayList<>();
    s.path("entries").forEach(out::add);
    return out;
  }

  private static List<String> messages(List<JsonNode> entries) {
    return entries.stream().map(e -> e.path("message").asString()).toList();
  }

  private static Set<String> ids(List<JsonNode> entries) {
    Set<String> out = new HashSet<>();
    entries.forEach(e -> out.add(e.path("id").asString()));
    return out;
  }
}
