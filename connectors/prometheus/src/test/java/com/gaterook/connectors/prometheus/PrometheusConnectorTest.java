package com.gaterook.connectors.prometheus;

import static com.gaterook.connectors.prometheus.FakePrometheus.error;
import static com.gaterook.connectors.prometheus.FakePrometheus.json;
import static com.gaterook.connectors.prometheus.FakePrometheus.matrix;
import static com.gaterook.connectors.prometheus.FakePrometheus.param;
import static com.gaterook.connectors.prometheus.FakePrometheus.params;
import static com.gaterook.connectors.prometheus.FakePrometheus.success;
import static com.gaterook.connectors.prometheus.FakePrometheus.vector;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connector.api.ActionPreview;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.HealthResult;
import com.gaterook.connector.api.Limits;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolResult;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The Prometheus connector against a fake of Prometheus' HTTP API: what it sends, what it answers, what it refuses. */
class PrometheusConnectorTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String UP = "{\"__name__\":\"up\",\"instance\":\"localhost:9090\",\"job\":\"prometheus\"}";

  private FakePrometheus prom;
  private PrometheusConnector connector;
  private final TestSecrets secrets = new TestSecrets();

  @BeforeEach
  void start() throws Exception {
    prom = new FakePrometheus();
    connector = new PrometheusConnector(Clock.systemUTC());
  }

  @AfterEach
  void stop() {
    connector.close();
    prom.close();
  }

  private ConnectionConfig config(String extra) {
    ObjectNode c = (ObjectNode) JSON.readTree("{\"url\": \"" + prom.url() + "\"" + (extra == null ? "" : ", " + extra)
        + "}");
    return new ConnectionConfig("conn-prom", "dev-prom", "prometheus", Env.DEV, 1, c, Set.of("/auth/secret"), null);
  }

  private ConnectionConfig config() {
    return config(null);
  }

  private static ToolCall call(String tool, String args) {
    return new ToolCall("tc_" + Long.toHexString(System.nanoTime()), tool, "dev-prom",
        (ObjectNode) JSON.readTree(args), null);
  }

  private ToolResult run(String tool, String args) throws ConnectorException {
    return connector.execute(call(tool, args), config(), secrets, TestCtx.of());
  }

  private static String text(ToolResult r) {
    return r.content().getFirst().text();
  }

  private static long size(ToolResult r) {
    return text(r).getBytes(StandardCharsets.UTF_8).length
        + r.structuredContent().toString().getBytes(StandardCharsets.UTF_8).length;
  }

  // ── What is sent ──────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aQueryIsOneGetOfItsReadPathWithEncodedParametersAndTheCallsTime() throws Exception {
    prom.on("/api/v1/query", vector("1700000000", UP, "1"));
    run("prometheus_query", "{\"query\": \"sum by (job) (rate(x{a=\\\"b c\\\"}[5m]))\", \"limit\": 10}");

    RecordedRequest r = prom.to("/api/v1/query").getFirst();
    assertThat(r.getMethod()).isEqualTo("GET");
    assertThat(r.getBodySize()).isZero();
    assertThat(param(r, "query")).isEqualTo("sum by (job) (rate(x{a=\"b c\"}[5m]))");
    assertThat(Instant.parse(param(r, "time"))).isCloseTo(Instant.now(), org.assertj.core.api.Assertions.within(
        10, java.time.temporal.ChronoUnit.SECONDS));
    assertThat(param(r, "limit")).isEqualTo("11");
    // The server's evaluation stops before the call's deadline (10 s in TestCtx).
    String timeout = param(r, "timeout");
    assertThat(timeout).endsWith("ms");
    assertThat(Long.parseLong(timeout.replace("ms", ""))).isBetween(8_000L, 10_000L);
    assertThat(r.getHeaders().get("Accept")).isEqualTo("application/json");
    assertThat(r.getHeaders().get("Authorization")).isNull();
    assertThat(r.getHeaders().get("X-Scope-OrgID")).isNull();
  }

  @Test
  void credentialsAndTheTenantGoInHeadersAndJoinTheTaint() throws Exception {
    prom.on("/api/v1/query", vector("1700000000", UP, "1"));
    TestCtx ctx = TestCtx.of();
    TestSecrets basic = new TestSecrets().with("/auth/secret", "test-only-reader-password");
    connector.execute(call("prometheus_query", "{\"query\": \"up\"}"), config("\"tenant\": \"team-a|team-b\", "
        + "\"auth\": {\"mode\": \"basic\", \"username\": \"reader\"}"), basic, ctx);
    String encoded = Base64.getEncoder().encodeToString("reader:test-only-reader-password"
        .getBytes(StandardCharsets.UTF_8));
    RecordedRequest r = prom.requests.getLast();
    assertThat(r.getHeaders().get("Authorization")).isEqualTo("Basic " + encoded);
    assertThat(r.getHeaders().get("X-Scope-OrgID")).isEqualTo("team-a|team-b");
    assertThat(ctx.taints).contains("test-only-reader-password", encoded);

    TestCtx bearerCtx = TestCtx.of();
    connector.execute(call("prometheus_query", "{\"query\": \"up\"}"), config("\"auth\": {\"mode\": \"bearer\"}"),
        new TestSecrets().with("/auth/secret", "test-only-bearer-token"), bearerCtx);
    assertThat(prom.requests.getLast().getHeaders().get("Authorization")).isEqualTo("Bearer test-only-bearer-token");
    assertThat(bearerCtx.taints).contains("test-only-bearer-token");

    // No stored secret: nothing is sent.
    int before = prom.requests.size();
    assertThatThrownBy(() -> connector.execute(call("prometheus_query", "{\"query\": \"up\"}"),
        config("\"auth\": {\"mode\": \"bearer\"}"), new TestSecrets(), TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE);
          assertThat(e.getMessage()).contains("no stored token");
        });
    // A secret no header can carry is refused, without the secret in the message.
    assertThatThrownBy(() -> connector.execute(call("prometheus_query", "{\"query\": \"up\"}"),
        config("\"auth\": {\"mode\": \"bearer\"}"), new TestSecrets().with("/auth/secret", "tok-0002\r\nX-Evil: 1"),
        TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.getMessage())
            .contains("line break").doesNotContain("tok-0002", "X-Evil"));
    assertThat(prom.requests).hasSize(before);
  }

  @Test
  void aPathBeforeApiV1IsKeptAndEveryToolUsesOnlyItsReadPath() throws Exception {
    prom.on("/mimir/prometheus/api/v1/", r -> json(200, success("[]")));
    ConnectionConfig cfg = new ConnectionConfig("conn-mimir", "mimir", "prometheus", Env.DEV, 1,
        JSON.readTree("{\"url\": \"" + prom.url() + "/mimir/prometheus\"}"), Set.of(), null);
    for (String[] c : new String[][] {{"prometheus_series", "{\"match\": [\"up\"]}"}, {"prometheus_labels", "{}"},
        {"prometheus_label_values", "{\"label\": \"job\"}"}}) {
      connector.execute(call(c[0], c[1]), cfg, secrets, TestCtx.of());
    }
    assertThat(prom.requests).extracting(r -> r.getUrl().encodedPath()).containsExactly(
        "/mimir/prometheus/api/v1/series", "/mimir/prometheus/api/v1/labels",
        "/mimir/prometheus/api/v1/label/job/values");
    assertThat(prom.requests).allSatisfy(r -> assertThat(r.getMethod()).isEqualTo("GET"));
  }

  @Test
  void noToolEverReachesAnAdminWriteOrLifecyclePath() throws Exception {
    prom.on("/", r -> json(200, success("{\"resultType\":\"vector\",\"result\":[]}")));
    String[][] calls = {{"prometheus_query", "{\"query\": \"up\"}"},
        {"prometheus_query_range", "{\"query\": \"up\"}"}, {"prometheus_series", "{\"match\": [\"up\"]}"},
        {"prometheus_labels", "{}"}, {"prometheus_label_values", "{\"label\": \"../../admin/tsdb/snapshot\"}"},
        {"prometheus_metadata", "{}"}, {"prometheus_targets", "{}"}, {"prometheus_rules", "{}"},
        {"prometheus_alerts", "{}"}};
    for (String[] c : calls) {
      try {
        run(c[0], c[1]);
      } catch (ConnectorException e) {
        // The fake's answers don't fit every tool; only the requests matter here.
      }
    }
    assertThat(prom.requests).hasSize(calls.length);
    for (RecordedRequest r : prom.requests) {
      assertThat(r.getMethod()).isEqualTo("GET");
      // A label's name, whatever it says, is one encoded segment of the label values path.
      assertThat(r.getUrl().encodedPath()).matches("/api/v1/(query|query_range|series|labels|label/[A-Za-z0-9_]+/values"
          + "|metadata|targets|rules|alerts)").doesNotStartWith("/api/v1/admin").doesNotContain("/-/", "..");
      assertThat(r.getBodySize()).isZero();
    }
  }

  @Test
  void aRedirectIsNotFollowed() throws Exception {
    prom.on("/api/v1/query", r -> new MockResponse.Builder().code(302).addHeader("Location",
        "https://login.example.com/oauth?next=/").build());
    assertThatThrownBy(() -> run("prometheus_query", "{\"query\": \"up\"}"))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE);
          assertThat(e.getMessage()).contains("redirect", "doesn't follow");
        });
    assertThat(prom.requests).hasSize(1);
  }

  // ── What comes back ───────────────────────────────────────────────────────────────────────────────────────

  @Test
  void anInstantVectorKeepsValuesAsWrittenAndTimesInUtc() throws Exception {
    prom.on("/api/v1/query", success("{\"resultType\":\"vector\",\"result\":["
        + "{\"metric\":{\"__name__\":\"up\",\"job\":\"api\"},\"value\":[1700000000.123,\"1\"]},"
        + "{\"metric\":{\"job\":\"nan\"},\"value\":[1700000000.123,\"NaN\"]},"
        + "{\"metric\":{\"job\":\"inf\"},\"value\":[1700000000.123,\"+Inf\"]},"
        + "{\"metric\":{\"job\":\"big\"},\"value\":[1700000000.123,\"123456789012345678901234567890\"]},"
        + "{\"metric\":{\"job\":\"tiny\"},\"value\":[1700000000.123,\"0.000000000000000000001\"]}]}"));
    ToolResult r = run("prometheus_query", "{\"query\": \"up\", \"time\": \"2023-11-14T22:13:20.123Z\"}");
    JsonNode s = r.structuredContent();
    assertThat(s.path("resultType").asString()).isEqualTo("vector");
    assertThat(s.path("time").asString()).isEqualTo("2023-11-14T22:13:20.123Z");
    assertThat(s.path("seriesCount").asInt()).isEqualTo(5);
    JsonNode first = s.path("series").get(0);
    assertThat(first.path("metric").path("__name__").asString()).isEqualTo("up");
    assertThat(first.path("value").get(0).asString()).isEqualTo("2023-11-14T22:13:20.123Z");
    assertThat(s.at("/series/0/value/1").asString()).isEqualTo("1");
    assertThat(s.at("/series/1/value/1").asString()).isEqualTo("NaN");
    assertThat(s.at("/series/2/value/1").asString()).isEqualTo("+Inf");
    assertThat(s.at("/series/3/value/1").asString()).isEqualTo("123456789012345678901234567890");
    assertThat(s.at("/series/4/value/1").asString()).isEqualTo("0.000000000000000000001");
    assertThat(text(r)).startsWith("vector · 5 series at 2023-11-14T22:13:20.123Z")
        .contains("\nup{job=\"api\"} 1\n", "{job=\"nan\"} NaN", "{job=\"inf\"} +Inf")
        .endsWith(QueryAnswers.FOOTER);
    assertThat(r.truncated()).isFalse();
  }

  @Test
  void aRangeQueryListsPointsOldestFirstWithASummary() throws Exception {
    prom.on("/api/v1/query_range", matrix(1700000000, 60, 5, UP, "{\"__name__\":\"up\",\"job\":\"node\"}"));
    ToolResult r = run("prometheus_query_range", "{\"query\": \"up\", \"start\": \"2023-11-14T22:13:20Z\", \"end\": "
        + "\"2023-11-14T22:17:20Z\", \"step\": \"1m\"}");
    RecordedRequest sent = prom.requests.getFirst();
    assertThat(param(sent, "start")).isEqualTo("2023-11-14T22:13:20Z");
    assertThat(param(sent, "end")).isEqualTo("2023-11-14T22:17:20Z");
    assertThat(param(sent, "step")).isEqualTo("60");
    assertThat(param(sent, "timeout")).endsWith("ms");
    JsonNode s = r.structuredContent();
    assertThat(s.path("resultType").asString()).isEqualTo("matrix");
    assertThat(s.path("step").asString()).isEqualTo("60");
    assertThat(s.at("/series/0/values")).hasSize(5);
    assertThat(s.at("/series/0/values/0/0").asString()).isEqualTo("2023-11-14T22:13:20Z");
    assertThat(s.at("/series/0/values/4/1").asString()).isEqualTo("4");
    assertThat(s.at("/series/0/pointCount").asInt()).isEqualTo(5);
    assertThat(text(r)).startsWith("matrix · 2 series · 2023-11-14T22:13:20Z → 2023-11-14T22:17:20Z, step 1m")
        .contains("up{instance=\"localhost:9090\", job=\"prometheus\"} · 5 points · min 0 · max 4 · last 4 at "
            + "2023-11-14T22:17:20Z", "\n  2023-11-14T22:13:20Z 0, 2023-11-14T22:14:20Z 1,");
  }

  @Test
  void scalarsStringsAndNativeHistograms() throws Exception {
    prom.on("/api/v1/query", success("{\"resultType\":\"scalar\",\"result\":[1700000000,\"42.5\"]}"));
    ToolResult scalar = run("prometheus_query", "{\"query\": \"scalar(sum(up))\"}");
    assertThat(scalar.structuredContent().path("resultType").asString()).isEqualTo("scalar");
    assertThat(scalar.structuredContent().at("/value/0").asString()).isEqualTo("2023-11-14T22:13:20Z");
    assertThat(scalar.structuredContent().at("/value/1").asString()).isEqualTo("42.5");
    assertThat(text(scalar)).startsWith("scalar 42.5 at 2023-11-14T22:13:20Z");

    prom.on("/api/v1/query", success("{\"resultType\":\"string\",\"result\":[1700000000,\"he said \\\"hi\\\"\"]}"));
    assertThat(text(run("prometheus_query", "{\"query\": \"\\\"x\\\"\"}"))).startsWith("string \"he said \\\"hi\\\"\"");

    String h = "{\"count\":\"10\",\"sum\":\"3.25\",\"buckets\":[[0,\"0.5\",\"1\",\"4\"],[0,\"1\",\"2\",\"6\"]]}";
    prom.on("/api/v1/query", success("{\"resultType\":\"vector\",\"result\":[{\"metric\":{\"__name__\":"
        + "\"rpc_seconds\"},\"histogram\":[1700000000," + h + "]}]}"));
    ToolResult vh = run("prometheus_query", "{\"query\": \"rpc_seconds\"}");
    assertThat(vh.structuredContent().at("/series/0/histogram/1/count").asString()).isEqualTo("10");
    assertThat(vh.structuredContent().at("/series/0/histogram/1/buckets/1").toString())
        .isEqualTo("[0,\"1\",\"2\",\"6\"]");
    assertThat(text(vh)).contains("rpc_seconds histogram count=10 sum=3.25 (2 buckets)");

    prom.on("/api/v1/query_range", success("{\"resultType\":\"matrix\",\"result\":[{\"metric\":{\"__name__\":"
        + "\"rpc_seconds\"},\"histograms\":[[1700000000," + h + "],[1700000060," + h + "]]}]}"));
    ToolResult mh = run("prometheus_query_range", "{\"query\": \"rpc_seconds\"}");
    assertThat(mh.structuredContent().at("/series/0/histograms")).hasSize(2);
    assertThat(mh.structuredContent().at("/series/0/histograms/1/0").asString()).isEqualTo("2023-11-14T22:14:20Z");
  }

  @Test
  void moreSeriesThanTheLimitAreCutAndSaid() throws Exception {
    StringBuilder series = new StringBuilder();
    for (int i = 0; i < 4; i++) {
      series.append(i == 0 ? "" : ",").append("{\"metric\":{\"i\":\"").append(i).append("\"},\"value\":[1700000000,\"")
          .append(i).append("\"]}");
    }
    prom.on("/api/v1/query", "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[" + series
        + "]},\"warnings\":[\"results truncated due to limit\"]}");
    ToolResult r = run("prometheus_query", "{\"query\": \"x\", \"limit\": 3}");
    assertThat(param(prom.requests.getFirst(), "limit")).isEqualTo("4");
    assertThat(r.truncated()).isTrue();
    assertThat(r.truncatedReason()).isEqualTo("row_limit");
    assertThat(r.structuredContent().path("series")).hasSize(3);
    assertThat(r.structuredContent().path("moreSeries").asBoolean()).isTrue();
    // The server's warning about the limit GateRook asked for is said once, by GateRook.
    assertThat(r.structuredContent().path("warnings")).isEmpty();
    assertThat(text(r)).startsWith("vector · 3+ series").contains("More series than the limit of 3")
        .doesNotContain("Server warning");

    // The endpoint's row limit is lower than the connection's.
    Limits rows2 = new Limits(2, 1_000, 512 * 1024, 256 * 1024, 256 * 1024, 64 * 1024, 8 * 1024,
        Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(10), null);
    ToolResult two = connector.execute(call("prometheus_query", "{\"query\": \"x\"}"), config(), secrets,
        TestCtx.of().with(rows2));
    assertThat(two.structuredContent().path("series")).hasSize(2);
  }

  @Test
  void aLargeMatrixFitsTheBudgetWithEachSeriesNewestPoints() throws Exception {
    prom.on("/api/v1/query_range", matrix(1700000000, 15, 1_000, "{\"pod\":\"a\"}", "{\"pod\":\"b\"}",
        "{\"pod\":\"c\"}"));
    Limits small = new Limits(1_000, 1_000, 24 * 1024, 256 * 1024, 256 * 1024, 64 * 1024, 8 * 1024,
        Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(10), null);
    TestCtx ctx = TestCtx.of().with(small);
    ToolResult r = connector.execute(call("prometheus_query_range", "{\"query\": \"x\", \"start\": \"-4h\", \"step\": "
        + "\"15s\"}"), config(), secrets, ctx);
    assertThat(r.truncated()).isTrue();
    assertThat(r.truncatedReason()).isEqualTo("size_limit");
    assertThat(size(r)).isLessThanOrEqualTo(PrometheusConnector.budget(ctx));
    JsonNode series = r.structuredContent().path("series");
    assertThat(series).hasSize(3);
    int shown = series.get(0).path("values").size();
    assertThat(shown).isGreaterThan(10).isLessThan(1_000);
    for (JsonNode one : series) {
      // Every series keeps the same number of its newest points.
      assertThat(one.path("values")).hasSize(shown);
      assertThat(one.path("values").get(shown - 1).get(1).asString()).isEqualTo("999");
      assertThat(one.path("pointCount").asInt()).isEqualTo(1_000);
    }
    assertThat(text(r)).contains("1000 points, last " + shown + " shown", "The answer was cut to the size limit: "
        + "each series shows at most its last " + shown + " points");
  }

  @Test
  void anAnswerLargerThanTheConnectionsLimitIsReadUpToItAndSaidToBeCut() throws Exception {
    // About 2.6 MB of points, read up to the connection's 1 MB.
    prom.on("/api/v1/query_range", matrix(1700000000, 1, 10_000, "{\"pod\":\"a\"}", "{\"pod\":\"b\"}",
        "{\"pod\":\"c\"}", "{\"pod\":\"d\"}", "{\"pod\":\"e\"}", "{\"pod\":\"f\"}", "{\"pod\":\"g\"}",
        "{\"pod\":\"h\"}", "{\"pod\":\"i\"}", "{\"pod\":\"j\"}"));
    ToolResult r = connector.execute(call("prometheus_query_range", "{\"query\": \"x\", \"start\": \"-1h\", \"step\": "
        + "\"1s\"}"), config("\"advanced\": {\"maxResponseMb\": 1}"), secrets, TestCtx.of());
    assertThat(r.truncated()).isTrue();
    assertThat(r.truncatedReason()).isEqualTo("size_limit");
    assertThat(text(r)).contains("larger than 1 MB").doesNotContain("No series matched");
    assertThat(r.structuredContent().path("moreSeries").asBoolean()).isTrue();
    assertThat(r.structuredContent().path("series").size()).isBetween(1, 9);
    assertThat(r.structuredContent().path("series").get(0).path("metric").path("pod").asString()).isEqualTo("a");
  }

  @Test
  void histogramPointsAreKeptByWhatTheyCostNotByHowManyThereAre() throws Exception {
    StringBuilder buckets = new StringBuilder();
    for (int b = 0; b < 100; b++) {
      buckets.append(b == 0 ? "" : ",").append("[0,\"").append(b).append("\",\"").append(b + 1).append("\",\"3\"]");
    }
    StringBuilder points = new StringBuilder();
    for (int i = 0; i < 2_000; i++) {
      points.append(i == 0 ? "" : ",").append('[').append(1700000000 + i).append(",{\"count\":\"").append(i)
          .append("\",\"sum\":\"1.5\",\"buckets\":[").append(buckets).append("]}]");
    }
    prom.on("/api/v1/query_range", success("{\"resultType\":\"matrix\",\"result\":[{\"metric\":{\"__name__\":"
        + "\"rpc_seconds\"},\"histograms\":[" + points + "]}]}"));
    TestCtx ctx = TestCtx.of();
    ToolResult r = connector.execute(call("prometheus_query_range", "{\"query\": \"rpc_seconds\", \"start\": \"-1h\", "
        + "\"step\": \"1s\"}"), config(), secrets, ctx);
    JsonNode shown = r.structuredContent().at("/series/0/histograms");
    assertThat(r.truncatedReason()).isEqualTo("size_limit");
    assertThat(shown.size()).isBetween(20, 200);
    assertThat(shown.get(shown.size() - 1).get(1).path("count").asString()).isEqualTo("1999");
    assertThat(shown.get(0).get(1).path("buckets")).hasSize(100);
    assertThat(r.structuredContent().at("/series/0/pointCount").asInt()).isEqualTo(2_000);
    assertThat(size(r)).isLessThanOrEqualTo(PrometheusConnector.budget(ctx));
  }

  @Test
  void aBodyCutAfterItsResultLosesOnlyTheServersWarnings() throws Exception {
    String huge = "w".repeat(1_200_000);
    prom.on("/api/v1/query", "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{\"metric\":"
        + UP + ",\"value\":[1700000000,\"1\"]}]},\"warnings\":[\"short one\",\"" + huge + "\"]}");
    prom.on("/api/v1/labels", "{\"status\":\"success\",\"data\":[\"job\"],\"warnings\":[\"" + huge + "\"]}");
    ConnectionConfig oneMb = config("\"advanced\": {\"maxResponseMb\": 1}");
    ToolResult q = connector.execute(call("prometheus_query", "{\"query\": \"up\"}"), oneMb, secrets, TestCtx.of());
    assertThat(q.truncated()).isFalse();
    assertThat(q.structuredContent().path("series")).hasSize(1);
    assertThat(q.structuredContent().path("warnings").toString()).isEqualTo("[\"short one\"]");
    ToolResult l = connector.execute(call("prometheus_labels", "{}"), oneMb, secrets, TestCtx.of());
    assertThat(l.truncated()).isFalse();
    assertThat(l.structuredContent().path("labels").toString()).isEqualTo("[\"job\"]");
  }

  @Test
  void emptyAnswersWarningsAndInfosAreSaid() throws Exception {
    prom.on("/api/v1/query", "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]},"
        + "\"warnings\":[\"PromQL warning: encountered a mix of histograms and floats\"],\"infos\":[\"PromQL info: "
        + "metric might not be a counter\"]}");
    ToolResult r = run("prometheus_query", "{\"query\": \"nope\"}");
    assertThat(text(r)).startsWith("vector · no series").contains("No series matched at this time",
        "Server warning: PromQL warning: encountered a mix", "Server note: PromQL info: metric might not");
    assertThat(r.structuredContent().path("warnings")).hasSize(1);
    assertThat(r.structuredContent().path("infos")).hasSize(1);
  }

  @Test
  void whatTheServerWritesCantRecolourHideOrReorderText() throws Exception {
    prom.on("/api/v1/query", success("{\"resultType\":\"vector\",\"result\":[{\"metric\":{\"job\":\"api\\u001b[2J"
        + "\\u202eevil\",\"note\":\"" + "x".repeat(20_000) + "\"},\"value\":[1700000000,\"1\"]}]}"));
    ToolResult r = run("prometheus_query", "{\"query\": \"up\"}");
    String job = r.structuredContent().at("/series/0/metric/job").asString();
    assertThat(job).isEqualTo("api\\e[2J\\u202Eevil");
    assertThat(text(r)).doesNotContain("\u001b", "\u202e");
    assertThat(r.structuredContent().at("/series/0/metric/note").asString()).hasSize(8 * 1024).endsWith("…");
  }

  // ── The other reads ───────────────────────────────────────────────────────────────────────────────────────

  @Test
  void seriesLabelsAndValuesListWhatTheServerHas() throws Exception {
    prom.on("/api/v1/series", success("[" + UP + ",{\"__name__\":\"up\",\"instance\":\"node:9100\",\"job\":"
        + "\"node\"}]"));
    ToolResult s = run("prometheus_series", "{\"match\": [\"up\", \"{job=~\\\"node|api\\\"}\"], \"start\": \"-15m\"}");
    RecordedRequest sent = prom.to("/api/v1/series").getFirst();
    assertThat(params(sent, "match[]")).containsExactly("up", "{job=~\"node|api\"}");
    assertThat(param(sent, "limit")).isEqualTo("1001");
    assertThat(s.structuredContent().path("series")).hasSize(2);
    assertThat(text(s)).startsWith("2 series matching up or {job=~\"node|api\"}")
        .contains("\nup{instance=\"node:9100\", job=\"node\"}");

    prom.on("/api/v1/labels", success("[\"__name__\",\"instance\",\"job\"]"));
    ToolResult l = run("prometheus_labels", "{}");
    assertThat(l.structuredContent().path("labels").toString()).isEqualTo("[\"__name__\",\"instance\",\"job\"]");
    assertThat(text(l)).startsWith("3 label names").contains("\n__name__, instance, job");

    prom.on("/api/v1/label/job/values", success("[\"api\",\"node\",\"prometheus\"]"));
    ToolResult v = run("prometheus_label_values", "{\"label\": \"job\", \"limit\": 2}");
    assertThat(param(prom.to("/api/v1/label/job/values").getFirst(), "limit")).isEqualTo("3");
    assertThat(v.structuredContent().path("values")).hasSize(2);
    assertThat(v.structuredContent().path("label").asString()).isEqualTo("job");
    assertThat(v.truncatedReason()).isEqualTo("row_limit");
    assertThat(text(v)).startsWith("2+ values of job").contains("More than 2 values");
  }

  @Test
  void metadataTargetsRulesAndAlerts() throws Exception {
    prom.on("/api/v1/metadata", success("{\"up\":[{\"type\":\"gauge\",\"help\":\"1 if the target is up.\","
        + "\"unit\":\"\"}],\"http_request_duration_seconds\":[{\"type\":\"histogram\",\"help\":\"Latency.\","
        + "\"unit\":\"seconds\"}]}"));
    ToolResult m = run("prometheus_metadata", "{}");
    assertThat(m.structuredContent().path("metrics")).hasSize(2);
    assertThat(text(m)).contains("up · gauge · 1 if the target is up.",
        "http_request_duration_seconds · histogram · unit seconds · Latency.");

    prom.on("/api/v1/targets", success("{\"activeTargets\":[{\"discoveredLabels\":{\"__address__\":\"node:9100\","
        + "\"__meta_secret\":\"x\"},\"labels\":{\"instance\":\"node:9100\",\"job\":\"node\"},\"scrapePool\":\"node\","
        + "\"scrapeUrl\":\"http://node:9100/metrics?token=abc123\",\"globalUrl\":\"http://node:9100/metrics\","
        + "\"lastError\":\"connection refused\",\"lastScrape\":\"2026-11-11T10:00:00.123Z\",\"lastScrapeDuration\":"
        + "0.0123,\"health\":\"down\",\"scrapeInterval\":\"15s\",\"scrapeTimeout\":\"10s\"},{\"labels\":"
        + "{\"instance\":\"localhost:9090\",\"job\":\"prometheus\"},\"scrapePool\":\"prometheus\",\"scrapeUrl\":"
        + "\"http://localhost:9090/metrics\",\"lastError\":\"\",\"lastScrape\":\"2026-11-11T10:00:01Z\","
        + "\"lastScrapeDuration\":0.004,\"health\":\"up\"}],\"droppedTargets\":[],\"droppedTargetCounts\":"
        + "{\"node\":3}}"));
    ToolResult t = run("prometheus_targets", "{}");
    assertThat(param(prom.to("/api/v1/targets").getFirst(), "state")).isEqualTo("active");
    JsonNode first = t.structuredContent().path("targets").get(0);
    assertThat(first.path("scrapeUrl").asString()).isEqualTo("http://node:9100/metrics");
    assertThat(first.has("discoveredLabels")).isFalse();
    assertThat(first.path("lastScrapeDurationSeconds").asDouble()).isEqualTo(0.0123);
    assertThat(t.structuredContent().path("droppedByPool").path("node").asInt()).isEqualTo(3);
    assertThat(text(t)).startsWith("2 active targets: 1 down, 1 up")
        .contains("node node:9100 · down · last scrape 2026-11-11T10:00:00.123Z (12 ms)", "  error: connection refused")
        .doesNotContain("abc123");

    prom.on("/api/v1/rules", success("{\"groups\":[{\"name\":\"api\",\"file\":\"/etc/prometheus/rules.yml\","
        + "\"rules\":[{\"type\":\"alerting\",\"name\":\"HighErrorRate\",\"query\":\"rate(errors[5m]) > 0.1\","
        + "\"duration\":600,\"labels\":{\"severity\":\"page\"},\"annotations\":{\"summary\":\"Errors\"},"
        + "\"alerts\":[{\"state\":\"firing\"}],\"health\":\"ok\",\"lastError\":\"\",\"state\":\"firing\"},"
        + "{\"type\":\"recording\",\"name\":\"job:errors:rate5m\",\"query\":\"sum by (job) (rate(errors[5m]))\","
        + "\"health\":\"err\",\"lastError\":\"many-to-many matching not allowed\"}],\"interval\":60}]}"));
    ToolResult rules = run("prometheus_rules", "{}");
    assertThat(param(prom.to("/api/v1/rules").getFirst(), "exclude_alerts")).isEqualTo("true");
    assertThat(rules.structuredContent().path("rules")).hasSize(2);
    assertThat(rules.structuredContent().at("/rules/0/forSeconds").asInt()).isEqualTo(600);
    assertThat(rules.structuredContent().at("/rules/0").has("alerts")).isFalse();
    assertThat(text(rules)).startsWith("2 rules: 1 alerting, 1 recording")
        .contains("alert HighErrorRate [firing] · for 10m · group api · health ok\n  expr: rate(errors[5m]) > 0.1",
            "record job:errors:rate5m · group api · health err", "error: many-to-many matching not allowed");

    prom.on("/api/v1/alerts", success("{\"alerts\":[{\"labels\":{\"alertname\":\"HighErrorRate\",\"job\":\"api\"},"
        + "\"annotations\":{\"summary\":\"Errors above 10%\"},\"state\":\"firing\",\"activeAt\":"
        + "\"2026-11-11T09:00:00Z\",\"value\":\"1.5e-01\"},{\"labels\":{\"alertname\":\"Slow\"},\"annotations\":{},"
        + "\"state\":\"pending\",\"activeAt\":\"2026-11-11T09:59:00Z\",\"value\":\"2e+00\"}]}"));
    ToolResult firing = run("prometheus_alerts", "{\"state\": \"firing\"}");
    assertThat(firing.structuredContent().path("alerts")).hasSize(1);
    assertThat(firing.structuredContent().at("/alerts/0/value").asString()).isEqualTo("1.5e-01");
    assertThat(text(firing)).startsWith("1 alert: 1 firing").contains("{alertname=\"HighErrorRate\", job=\"api\"} · "
        + "firing since 2026-11-11T09:00:00Z · value 1.5e-01\n  Errors above 10%");
    assertThat(text(run("prometheus_alerts", "{}"))).startsWith("2 alerts: 1 firing, 1 pending");
  }

  @Test
  void aListLargerThanTheBudgetLosesWholeItemsFromTheEnd() throws Exception {
    StringBuilder names = new StringBuilder();
    for (int i = 0; i < 3_000; i++) {
      names.append(i == 0 ? "" : ",").append("\"metric_name_number_").append(i).append("_total\"");
    }
    prom.on("/api/v1/label/__name__/values", success("[" + names + "]"));
    Limits small = new Limits(10_000, 1_000, 32 * 1024, 256 * 1024, 256 * 1024, 64 * 1024, 8 * 1024,
        Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(10), null);
    TestCtx ctx = TestCtx.of().with(small);
    ToolResult r = connector.execute(call("prometheus_label_values", "{\"label\": \"__name__\", \"limit\": 5000}"),
        config(), secrets, ctx);
    assertThat(r.truncatedReason()).isEqualTo("size_limit");
    assertThat(size(r)).isLessThanOrEqualTo(PrometheusConnector.budget(ctx));
    int kept = r.structuredContent().path("values").size();
    assertThat(kept).isBetween(100, 2_999);
    assertThat(r.structuredContent().path("values").get(kept - 1).asString()).isEqualTo("metric_name_number_"
        + (kept - 1) + "_total");
    assertThat(text(r)).contains("metric_name_number_" + (kept - 1) + "_total\n", "more values are left out");
  }

  // ── What the server refuses ───────────────────────────────────────────────────────────────────────────────

  @Test
  void theServersRefusalsSayWhatToDo() throws Exception {
    record Case(int status, String body, String path, String tool, String args, ConnectorException.Kind kind,
        String says) {}
    for (Case c : List.of(
        new Case(400, error("bad_data", "invalid parameter \\\"query\\\": 1:4: parse error: unexpected \\\"{\\\""),
            "/api/v1/query", "prometheus_query", "{\"query\": \"up{\"}", ConnectorException.Kind.BAD_INPUT,
            "The server refused the query: invalid parameter \"query\": 1:4: parse error"),
        new Case(422, error("execution", "query processing would load too many samples into memory"),
            "/api/v1/query_range", "prometheus_query_range", "{\"query\": \"x\"}",
            ConnectorException.Kind.RESOURCE_ERROR, "too many samples"),
        new Case(503, error("timeout", "query timed out in expression evaluation"), "/api/v1/query",
            "prometheus_query", "{\"query\": \"x\"}", ConnectorException.Kind.TIMEOUT, "at its time limit"),
        new Case(401, "no org id\n", "/api/v1/query", "prometheus_query", "{\"query\": \"x\"}",
            ConnectorException.Kind.RESOURCE_ERROR, "needs a tenant: set Tenant (X-Scope-OrgID)"),
        new Case(401, "Unauthorized\n", "/api/v1/query", "prometheus_query", "{\"query\": \"x\"}",
            ConnectorException.Kind.RESOURCE_ERROR, "refused the credentials (401)"),
        new Case(404, "404 page not found\n", "/api/v1/targets", "prometheus_targets", "{}",
            ConnectorException.Kind.RESOURCE_ERROR, "Mimir, Cortex and servers fed by remote write scrape nothing"),
        new Case(404, "404 page not found\n", "/api/v1/query", "prometheus_query", "{\"query\": \"x\"}",
            ConnectorException.Kind.RESOURCE_ERROR, "Check the server URL: Mimir's ends in /prometheus"),
        new Case(429, error("too_many_requests", "rate limited"), "/api/v1/query", "prometheus_query",
            "{\"query\": \"x\"}", ConnectorException.Kind.RESOURCE_ERROR, "limiting requests (429)"),
        new Case(500, error("internal", "boom"), "/api/v1/series", "prometheus_series", "{\"match\": [\"up\"]}",
            ConnectorException.Kind.RESOURCE_UNAVAILABLE, "failed (500): boom"),
        new Case(200, "<html><body>Sign in</body></html>", "/api/v1/query", "prometheus_query", "{\"query\": \"x\"}",
            ConnectorException.Kind.RESOURCE_ERROR, "isn't Prometheus' JSON"),
        new Case(200, "<html><body>Sign in</body></html>", "/api/v1/labels", "prometheus_labels", "{}",
            ConnectorException.Kind.RESOURCE_ERROR, "an HTML page"))) {
      prom.on(c.path(), r -> json(c.status(), c.body()));
      assertThatThrownBy(() -> run(c.tool(), c.args())).as(c.body())
          .isInstanceOfSatisfying(ConnectorException.class, e -> {
            assertThat(e.kind()).as(e.getMessage()).isEqualTo(c.kind());
            assertThat(e.getMessage()).contains(c.says());
          });
    }
  }

  @Test
  void aSlowServerStopsAtTheCallsDeadline() throws Exception {
    prom.on("/api/v1/query", r -> new MockResponse.Builder().code(200).headersDelay(3, TimeUnit.SECONDS)
        .body(vector("1700000000", UP, "1")).build());
    long started = System.nanoTime();
    assertThatThrownBy(() -> connector.execute(call("prometheus_query", "{\"query\": \"up\"}"), config(), secrets,
        TestCtx.of().timeout(Duration.ofSeconds(1))))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT));
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2_500));
    assertThat(Long.parseLong(param(prom.requests.getFirst(), "timeout").replace("ms", ""))).isLessThan(1_000);
  }

  @Test
  void aBodyThatTricklesStopsAtTheCallsDeadline() throws Exception {
    // The request's own timeout ends with the headers; the body is read against the call's deadline.
    prom.on("/api/v1/query", r -> new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
        .body(matrix(1700000000, 1, 3_000, UP)).throttleBody(2_048, 250, TimeUnit.MILLISECONDS).build());
    long started = System.nanoTime();
    assertThatThrownBy(() -> connector.execute(call("prometheus_query", "{\"query\": \"up[1h]\"}"), config(), secrets,
        TestCtx.of().timeout(Duration.ofSeconds(1))))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT));
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
  }

  @Test
  void aServerThatIsntThereIsUnavailable() throws Exception {
    int port = prom.port();
    prom.close();
    ConnectionConfig gone = new ConnectionConfig("conn-gone", "gone", "prometheus", Env.DEV, 1,
        JSON.readTree("{\"url\": \"http://127.0.0.1:" + port + "\"}"), Set.of(), null);
    assertThatThrownBy(() -> connector.execute(call("prometheus_query", "{\"query\": \"up\"}"), gone, secrets,
        TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE));
    TestResult t = connector.test(gone, secrets, TestCtx.of());
    assertThat(t.ok()).isFalse();
    assertThat(t.error().code()).isEqualTo("prometheus.unreachable");
    assertThat(connector.health(gone, secrets, TestCtx.of()).state()).isEqualTo(HealthResult.State.UNREACHABLE);
  }

  // ── Approval, Test connection, health ─────────────────────────────────────────────────────────────────────

  @Test
  void aReadAnEndpointPutInApprovalRunsTheExactRequestThatWasShown() throws Exception {
    TestCtx.MovingClock clock = new TestCtx.MovingClock(Instant.parse("2026-11-11T10:00:00Z"));
    PrometheusConnector at10 = new PrometheusConnector(clock);
    ToolCall c = call("prometheus_query_range", "{\"query\": \"rate(http_requests_total[5m])\", \"start\": \"-2h\", "
        + "\"step\": \"5m\"}");
    ActionPreview preview = at10.describeAction(c, config("\"tenant\": \"team-a\", \"auth\": {\"mode\": "
        + "\"bearer\"}"));
    assertThat(preview.kind()).isEqualTo(ActionPreview.Kind.HTTP);
    assertThat(preview.text()).startsWith("GET " + prom.url() + "/api/v1/query_range?query=rate%28http_requests_total"
        + "%5B5m%5D%29&start=2026-11-11T08%3A00%3A00Z&end=2026-11-11T10%3A00%3A00Z&step=300&limit=1001")
        .contains("\nX-Scope-OrgID: team-a", "\nAuthorization: ‹masked›");
    assertThat(preview.fields()).extracting(ActionPreview.Field::label).contains("Query", "Range", "Step",
        "Server timeout");
    assertThat(preview.payload().at("/arguments/start").asString()).isEqualTo("2026-11-11T08:00:00Z");

    // Approved an hour later, it still reads the window that was approved, not the arguments as they are now.
    clock.advance(Duration.ofHours(1));
    prom.on("/api/v1/query_range", matrix(1762840800, 300, 3, UP));
    at10.execute(call("prometheus_query_range", "{\"query\": \"up\"}"), config("\"tenant\": \"team-a\", \"auth\": "
        + "{\"mode\": \"bearer\"}"), new TestSecrets().with("/auth/secret", "tok-0001"),
        TestCtx.of().clock(clock).approved(preview.payload()));
    RecordedRequest sent = prom.requests.getLast();
    assertThat(param(sent, "query")).isEqualTo("rate(http_requests_total[5m])");
    assertThat(param(sent, "start")).isEqualTo("2026-11-11T08:00:00Z");
    assertThat(param(sent, "end")).isEqualTo("2026-11-11T10:00:00Z");
    at10.close();
  }

  @Test
  void testConnectionReportsTheServerAQueryAndTheScrapedTargets() throws Exception {
    prom.on("/api/v1/status/buildinfo", success("{\"version\":\"3.15.1\",\"revision\":\"abc\",\"branch\":\"HEAD\"}"))
        .on("/api/v1/query", r -> "count(up)".equals(param(r, "query")) ? json(200, vector("1700000000", "{}", "7"))
            : json(200, vector("1700000000", "{}", "1")));
    TestResult t = connector.test(config("\"auth\": {\"mode\": \"basic\", \"username\": \"reader\"}, \"tenant\": "
        + "\"team-a\""), new TestSecrets().with("/auth/secret", "pw-0001"), TestCtx.of());
    assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
    assertThat(t.serverVersion()).isEqualTo("Prometheus 3.15.1");
    assertThat(t.identity()).isEqualTo("reader · tenant team-a");
    assertThat(t.checks()).extracting(TestResult.Check::name).containsExactly("server", "query", "targets");
    assertThat(t.checks().get(2).detail()).isEqualTo("7 up series (scrape targets)");
    assertThat(t.permissions().level()).isEqualTo(TestResult.Level.READ);
    assertThat(t.hints()).extracting(TestResult.Hint::code).containsExactly("prometheus.read-api",
        "prometheus.metric-scope");
    assertThat(t.metadataUpdate().path("serverVersion").asString()).isEqualTo("Prometheus 3.15.1");
    assertThat(prom.requests).allSatisfy(r -> assertThat(r.getHeaders().get("Authorization")).startsWith("Basic "));

    // Mimir names itself; a server without build information still passes; no up series is said.
    prom.on("/api/v1/status/buildinfo", success("{\"application\":\"Grafana Mimir\",\"version\":\"2.17.0\"}"));
    assertThat(connector.test(config(), secrets, TestCtx.of()).serverVersion()).isEqualTo("Grafana Mimir 2.17.0");
    prom.on("/api/v1/status/buildinfo", r -> json(404, "404 page not found"))
        .on("/api/v1/query", r -> "count(up)".equals(param(r, "query"))
            ? json(200, success("{\"resultType\":\"vector\",\"result\":[]}")) : json(200, vector("1", "{}", "1")));
    TestResult vm = connector.test(config(), secrets, TestCtx.of());
    assertThat(vm.ok()).isTrue();
    assertThat(vm.checks().getFirst().detail()).isEqualTo("Version not reported (404)");
    assertThat(vm.hints()).extracting(TestResult.Hint::code).contains("prometheus.no-up");
  }

  @Test
  void testConnectionStopsAtTheFirstFailureWithWhatToDo() throws Exception {
    prom.on("/api/v1/status/buildinfo", r -> json(401, "Unauthorized"));
    TestResult t = connector.test(config("\"auth\": {\"mode\": \"bearer\"}"), new TestSecrets().with("/auth/secret",
        "wrong-0001"), TestCtx.of());
    assertThat(t.ok()).isFalse();
    assertThat(t.error().code()).isEqualTo("prometheus.refused");
    assertThat(t.error().message()).contains("refused the credentials (401)", "Check the token");
    assertThat(t.checks()).extracting(TestResult.Check::ok).containsExactly(false);

    prom.on("/api/v1/status/buildinfo", success("{\"version\":\"3.15.1\"}"))
        .on("/api/v1/query", r -> json(200, "{\"not\":\"prometheus\"}"));
    TestResult q = connector.test(config(), secrets, TestCtx.of());
    assertThat(q.ok()).isFalse();
    assertThat(q.checks()).extracting(TestResult.Check::name).containsExactly("server", "query");
    ConnectionConfig plain = new ConnectionConfig("conn-plain", "plain", "prometheus", Env.DEV, 1,
        JSON.readTree("{\"url\": \"http://prometheus.example.com\"}"), Set.of(), null);
    assertThat(connector.test(plain, secrets, TestCtx.of()).error().code()).isEqualTo("prometheus.settings");
  }

  @Test
  void healthIsTheBuildInformationOrAQuery() throws Exception {
    prom.on("/api/v1/status/buildinfo", success("{\"version\":\"3.15.1\"}"));
    assertThat(connector.health(config(), secrets, TestCtx.of()).state()).isEqualTo(HealthResult.State.HEALTHY);
    prom.on("/api/v1/status/buildinfo", r -> json(404, "404 page not found"))
        .on("/api/v1/query", vector("1700000000", "{}", "1"));
    assertThat(connector.health(config(), secrets, TestCtx.of()).state()).isEqualTo(HealthResult.State.HEALTHY);
    assertThat(prom.to("/api/v1/query")).hasSize(1);
    prom.on("/api/v1/status/buildinfo", r -> json(401, "Unauthorized"));
    HealthResult h = connector.health(config(), secrets, TestCtx.of());
    assertThat(h.state()).isEqualTo(HealthResult.State.UNREACHABLE);
    assertThat(h.message()).contains("401");
  }
}
