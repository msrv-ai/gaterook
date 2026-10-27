package ai.msrv.gaterook.connectors.logs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.TestResult;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The connector against a real OpenSearch 3.9.0 (the min distribution, one node, no security plugin): one timeline
 * over two indices of different timestamp precision, a cursor through documents that share a timestamp, Lucene syntax
 * kept inside the connection's index patterns, JSON Query DSL refused before it reaches the cluster, and a tail of
 * documents indexed while it waits. Profile {@code -Plogs-it}; the archive is downloaded once and checked against its
 * SHA-256, or {@code GW_OPENSEARCH_HOME} names an unpacked one. OpenSearch refuses to run as root, so under root the
 * node runs as nobody.
 */
@Tag("logs-it")
class OpenSearchIT {

  private static final String URL =
      "https://artifacts.opensearch.org/releases/core/opensearch/3.9.0/opensearch-min-3.9.0-linux-x64.tar.gz";
  private static final String SHA256 = "6126bdf7437446c19c42e2313e534cb432ef9cd87a412fc5dbad25ab45b89d26";

  private static Process node;
  private static URI base;
  private static Instant t0;
  private static final CloudCredentials CLOUD = new CloudCredentials(CloudEndpoints.PRODUCTION, Clock.systemUTC());
  private static final LogsConnector CONNECTOR = new LogsConnector(CLOUD, Clock.systemUTC());
  private static final TestSecrets NO_SECRETS = new TestSecrets();

  @BeforeAll
  static void startOpenSearch() throws Exception {
    if (TestServices.enabled()) {
      // The OpenSearch of the Compose services keeps its indices between runs: this run's are made afresh.
      base = URI.create("http://127.0.0.1:" + TestServices.port("GW_OPENSEARCH_PORT"));
      Fixtures.awaitOk(base.resolve("/_cluster/health?wait_for_status=yellow&timeout=1s"), Duration.ofSeconds(120),
          null, null);
      for (String index : List.of("logs-app-nanos", "logs-app-millis", "secrets-db")) {
        Fixtures.send(HttpRequest.newBuilder(base.resolve("/" + index)).DELETE().build());
      }
    } else {
      startOwnOpenSearch();
    }

    t0 = Instant.now().minus(15, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
    put("/logs-app-nanos", """
        {"mappings":{"properties":{"@timestamp":{"type":"date_nanos"},"message":{"type":"text"},
          "log":{"properties":{"level":{"type":"keyword"}}},
          "service":{"properties":{"name":{"type":"keyword"}}},"host":{"properties":{"name":{"type":"keyword"}}}}}}""");
    put("/logs-app-millis", """
        {"mappings":{"properties":{"@timestamp":{"type":"date"},"message":{"type":"text"},
          "log":{"properties":{"level":{"type":"keyword"}}},
          "service":{"properties":{"name":{"type":"keyword"}}},"host":{"properties":{"name":{"type":"keyword"}}}}}}""");
    put("/secrets-db", """
        {"mappings":{"properties":{"@timestamp":{"type":"date"},"message":{"type":"text"}}}}""");
    StringBuilder bulk = new StringBuilder();
    for (int i = 0; i < 40; i++) {
      doc(bulk, "logs-app-nanos", t0.plusSeconds(i).plusNanos(123), i % 8 == 0 ? "error" : "info", "api",
          (i % 8 == 0 ? "payment failed " : "request served ") + "nanos " + i);
      doc(bulk, "logs-app-millis", t0.plusSeconds(i).plusMillis(250), "warn", "worker", "job step millis " + i);
    }
    for (int k = 0; k < 3; k++) {
      doc(bulk, "logs-app-nanos", t0.plusSeconds(20), "info", "api", "burst nanos " + k);
      doc(bulk, "logs-app-millis", t0.plusSeconds(20), "info", "worker", "burst millis " + k);
    }
    doc(bulk, "secrets-db", t0.plusSeconds(5), "info", "db", "top secret row");
    bulk(bulk);
  }

  /** OpenSearch 3.9.0 min of its own: the pinned archive (or {@code GW_OPENSEARCH_HOME}) on free loopback ports. */
  private static void startOwnOpenSearch() throws Exception {
    Path dir = Fixtures.workDir("opensearch");
    Path home;
    String own = System.getenv("GW_OPENSEARCH_HOME");
    if (own != null && !own.isBlank()) {
      home = Path.of(own);
    } else if (Fixtures.linuxX64()) {
      Path archive = Fixtures.download("opensearch-3.9.0", "opensearch-min-3.9.0-linux-x64.tar.gz", URL, SHA256);
      Process tar = new ProcessBuilder("tar", "xzf", archive.toString(), "-C", dir.toString()).inheritIO().start();
      assertThat(tar.waitFor()).as("tar").isZero();
      home = dir.resolve("opensearch-3.9.0");
    } else {
      throw new IllegalStateException("Set GW_OPENSEARCH_HOME to an unpacked OpenSearch 3.9.0 of this platform");
    }
    int http = Fixtures.freePort();
    int transport = Fixtures.freePort();
    Path config = home.resolve("config");
    Files.writeString(config.resolve("opensearch.yml"), """
        cluster.name: gw-logs-it
        node.name: n1
        discovery.type: single-node
        network.host: 127.0.0.1
        http.port: %d
        transport.port: %d
        path.data: %s
        path.logs: %s
        cluster.routing.allocation.disk.threshold_enabled: false
        """.formatted(http, transport, dir.resolve("data"), dir.resolve("logs")));
    if (Fixtures.root()) {
      Process chown = new ProcessBuilder("chown", "-R", "65534:65534", dir.toString()).inheritIO().start();
      assertThat(chown.waitFor()).as("chown").isZero();
    }
    Path log = dir.resolve("opensearch.out");
    node = Fixtures.start(Fixtures.unprivileged(List.of(home.resolve("bin/opensearch").toString())), home,
        log, Map.of("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m", "HOME", dir.toString()));
    base = URI.create("http://127.0.0.1:" + http);
    Fixtures.awaitOk(base.resolve("/_cluster/health?wait_for_status=yellow&timeout=1s"), Duration.ofSeconds(120),
        node, log);

  }

  @AfterAll
  static void stopOpenSearch() throws Exception {
    CONNECTOR.close();
    CLOUD.close();
    Fixtures.stop(node);
  }

  @Test
  void pagesThroughTwoIndicesWithoutLosingOrRepeatingADocument() throws Exception {
    String range = "\"from\":\"" + t0.minusSeconds(1) + "\",\"to\":\"" + t0.plusSeconds(60) + "\"";
    List<JsonNode> all = entries(OutputSchema.valid(CONNECTOR.execute(Configs.call("logs_search",
        "{\"query\":\"\"," + range + ",\"limit\":1000}"), cfg(), NO_SECRETS, TestCtx.of())).structuredContent());
    assertThat(all).hasSize(86);
    assertThat(Fixtures.instants(all)).as("newest first over both indices")
        .isSortedAccordingTo(Comparator.reverseOrder());
    assertThat(all.stream().filter(e -> e.path("timestamp").asString().equals(t0.plusSeconds(20).toString())))
        .hasSize(6);

    for (int limit : new int[] {1, 4, 25}) {
      List<JsonNode> paged = new ArrayList<>();
      String cursor = null;
      int calls = 0;
      do {
        JsonNode s = OutputSchema.valid(CONNECTOR.execute(Configs.call("logs_search", "{\"query\":\"\"," + range
            + ",\"limit\":" + limit + (cursor == null ? "" : ",\"cursor\":\"" + cursor + "\"") + "}"), cfg(),
            NO_SECRETS, TestCtx.of())).structuredContent();
        s.path("entries").forEach(paged::add);
        cursor = s.path("nextCursor").isNull() ? null : s.path("nextCursor").asString();
        calls++;
      } while (cursor != null && calls < 200);
      assertThat(cursor).as("limit %d ends", limit).isNull();
      // Documents sharing a timestamp come in the order OpenSearch picks for each page; each comes exactly once.
      assertThat(ids(paged)).as("limit %d: the same documents", limit).doesNotHaveDuplicates()
          .containsExactlyInAnyOrderElementsOf(ids(all));
      assertThat(Fixtures.instants(paged)).as("limit %d: newest first across pages", limit)
          .isSortedAccordingTo(Comparator.reverseOrder());
    }

    JsonNode error = all.stream().filter(e -> e.path("message").asString().equals("payment failed nanos 32"))
        .findFirst().orElseThrow();
    assertThat(error.path("severity").asString()).isEqualTo("ERROR");
    assertThat(error.at("/labels/service.name").asString()).isEqualTo("api");
    assertThat(error.at("/labels/index").asString()).isEqualTo("logs-app-nanos");
    assertThat(error.path("timestamp").asString()).as("nanoseconds kept").endsWith("000123Z");
  }

  @Test
  void luceneSyntaxSearchesOnlyTheConnectionsIndices() throws Exception {
    JsonNode errors = CONNECTOR.execute(Configs.call("logs_search", "{\"query\":\"log.level:error AND message:payment\","
        + "\"from\":\"-1h\",\"limit\":100}"), cfg(), NO_SECRETS, TestCtx.of()).structuredContent();
    assertThat(entries(errors)).hasSize(5).allSatisfy(e -> assertThat(e.path("message").asString())
        .startsWith("payment failed"));

    JsonNode other = CONNECTOR.execute(Configs.call("logs_search", "{\"query\":\"_index:secrets-db OR message:secret\","
        + "\"from\":\"-1h\",\"limit\":100}"), cfg(), NO_SECRETS, TestCtx.of()).structuredContent();
    assertThat(other.path("count").asInt()).as("other indices stay out of reach").isZero();

    JsonNode one = CONNECTOR.execute(Configs.call("logs_search", "{\"query\":\"\",\"source\":\"logs-app-millis\","
        + "\"from\":\"" + t0.minusSeconds(1) + "\",\"to\":\"" + t0.plusSeconds(60) + "\",\"limit\":1000}"),
        cfgWithTwoPatterns(), NO_SECRETS, TestCtx.of()).structuredContent();
    assertThat(entries(one)).hasSize(43).allSatisfy(e -> assertThat(e.at("/labels/index").asString())
        .isEqualTo("logs-app-millis"));
  }

  @Test
  void jsonQueryDslIsDeniedBeforeItReachesTheCluster() {
    Classification c = CONNECTOR.classify(Configs.call("logs_search",
        "{\"query\":\"{\\\"terms\\\":{\\\"user\\\":{\\\"index\\\":\\\"secrets-db\\\",\\\"id\\\":\\\"1\\\"}}}\"}"),
        cfg(), Configs.policy(Env.DEV, TestCtx.dev()));
    assertThat(c.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(c.ruleId()).isEqualTo("dev.logs.os_dsl");
  }

  @Test
  void openSearchsParseErrorReachesTheAgent() {
    assertThatThrownBy(() -> CONNECTOR.execute(Configs.call("logs_search", "{\"query\":\"message:(unbalanced\","
        + "\"from\":\"-1h\"}"), cfg(), NO_SECRETS, TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
          assertThat(e.getMessage()).containsIgnoringCase("parse");
        });
  }

  @Test
  void tailsDocumentsIndexedWhileItWaits() throws Exception {
    String query = "{\"query\":\"service.name:tailer\",\"limit\":100";
    JsonNode first = OutputSchema.valid(CONNECTOR.execute(Configs.call("logs_tail", query + ",\"wait\":0}"), cfg(),
        NO_SECRETS, TestCtx.of())).structuredContent();
    String cursor = first.path("nextCursor").asString();

    // After the first call's cursor, which holds the nanosecond it was made at.
    Instant at = Instant.now().plusMillis(5).truncatedTo(ChronoUnit.MILLIS);
    Thread.ofVirtual().start(() -> {
      try {
        Thread.sleep(500);
        StringBuilder b = new StringBuilder();
        doc(b, "logs-app-nanos", at, "info", "tailer", "tail one");
        doc(b, "logs-app-millis", at, "info", "tailer", "tail two");
        doc(b, "logs-app-nanos", at.plusNanos(5), "error", "tailer", "tail three");
        bulk(b);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    });
    JsonNode second = OutputSchema.valid(CONNECTOR.execute(Configs.call("logs_tail", query + ",\"wait\":8,"
        + "\"cursor\":\"" + cursor + "\"}"), cfg(), NO_SECRETS, TestCtx.of())).structuredContent();
    assertThat(messages(entries(second))).containsExactlyInAnyOrder("tail one", "tail two", "tail three");
    assertThat(messages(entries(second)).getLast()).as("oldest first").isEqualTo("tail three");

    StringBuilder b = new StringBuilder();
    doc(b, "logs-app-millis", at.plusMillis(1), "info", "tailer", "tail four");
    bulk(b);
    JsonNode third = OutputSchema.valid(CONNECTOR.execute(Configs.call("logs_tail", query + ",\"wait\":8,"
        + "\"cursor\":\"" + second.path("nextCursor").asString() + "\"}"), cfg(), NO_SECRETS, TestCtx.of()))
        .structuredContent();
    assertThat(messages(entries(third))).containsExactly("tail four");
  }

  @Test
  void theTestNamesTheVersionAndTheIndices() {
    TestResult r = CONNECTOR.test(cfg(), NO_SECRETS, TestCtx.of());
    assertThat(r.ok()).as(r.toString()).isTrue();
    assertThat(r.serverVersion()).isEqualTo("OpenSearch 3.9.0");
    assertThat(r.checks()).anySatisfy(c -> {
      assertThat(c.name()).isEqualTo("indices");
      assertThat(c.detail()).isEqualTo("2 indices, aliases or data streams");
    });
    assertThat(r.permissions().level()).as("no security plugin to ask").isEqualTo(TestResult.Level.UNKNOWN);
  }

  @Test
  void describesTheIndicesAPatternMatches() throws Exception {
    JsonNode d = CONNECTOR.describe(cfg(), NO_SECRETS, TestCtx.of(), "logs-app-*").orElseThrow();
    assertThat(d.path("matching").toString()).contains("logs-app-nanos").contains("logs-app-millis")
        .doesNotContain("secrets-db");
  }

  private static ConnectionConfig cfg() {
    return Configs.of("search-logs", Env.DEV, """
        {"backend":"opensearch","url":"%s","indices":["logs-app-*"],"auth":{"mode":"none"},
         "labelFields":["service.name","host.name"]}""".formatted(base));
  }

  private static ConnectionConfig cfgWithTwoPatterns() {
    return Configs.of("search-logs", Env.DEV, """
        {"backend":"opensearch","url":"%s","indices":["logs-app-nanos","logs-app-millis"],"auth":{"mode":"none"},
         "labelFields":["service.name"]}""".formatted(base));
  }

  private static void doc(StringBuilder bulk, String index, Instant at, String level, String service, String message) {
    bulk.append("{\"index\":{\"_index\":\"").append(index).append("\"}}\n");
    bulk.append("{\"@timestamp\":\"").append(at).append("\",\"log\":{\"level\":\"").append(level)
        .append("\"},\"service\":{\"name\":\"").append(service).append("\"},\"host\":{\"name\":\"node-1\"},")
        .append("\"message\":\"").append(message).append("\"}\n");
  }

  private static void put(String path, String body) throws Exception {
    HttpResponse<String> r = Fixtures.send(HttpRequest.newBuilder(base.resolve(path))
        .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(body)).build());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
  }

  private static void bulk(StringBuilder body) throws Exception {
    HttpResponse<String> r = Fixtures.send(HttpRequest.newBuilder(base.resolve("/_bulk?refresh=true"))
        .header("Content-Type", "application/x-ndjson").POST(HttpRequest.BodyPublishers.ofString(body.toString()))
        .build());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    assertThat(r.body()).as(r.body()).contains("\"errors\":false");
  }

  private static List<JsonNode> entries(JsonNode s) {
    List<JsonNode> out = new ArrayList<>();
    s.path("entries").forEach(out::add);
    return out;
  }

  private static List<String> messages(List<JsonNode> entries) {
    return entries.stream().map(e -> e.path("message").asString()).toList();
  }

  private static List<String> ids(List<JsonNode> entries) {
    return entries.stream().map(e -> e.path("id").asString()).toList();
  }
}
