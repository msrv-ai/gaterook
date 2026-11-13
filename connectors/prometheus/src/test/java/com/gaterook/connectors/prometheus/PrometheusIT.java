package com.gaterook.connectors.prometheus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.gaterook.connector.api.ActionPreview;
import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.HealthResult;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolResult;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The connector against a real Prometheus of the test services (profile {@code prometheus}: start it with
 * {@code scripts/test-services.sh up prometheus}, then use {@code -Pservices,prometheus-it}). It scrapes itself, keeps
 * a target that is down and one relabeling drops, records {@code gw_it:up:sum} and fires {@code GwItAlwaysFiring}
 * (test-services/prometheus). Every tool runs as an agent calls it: classified first, then executed.
 */
@Tag("prometheus-it")
class PrometheusIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TestSecrets NO_SECRETS = new TestSecrets();
  private static PrometheusConnector connector;
  private static ConnectionConfig cfg;
  private static URI base;

  @BeforeAll
  static void server() throws Exception {
    assumeTrue(TestServices.enabled(), "Prometheus runs in the Compose services: scripts/test-services.sh up "
        + "prometheus, then -Pservices,prometheus-it");
    base = URI.create("http://127.0.0.1:" + TestServices.port("GW_PROMETHEUS_PORT"));
    connector = new PrometheusConnector(Clock.systemUTC());
    cfg = new ConnectionConfig("conn-prom-it", "it-prom", "prometheus", Env.DEV, 1,
        JSON.readTree("{\"url\": \"" + base + "\"}"), Set.of(), null);
    // A fresh container has scraped nothing and evaluated no rule yet.
    await("up{job=\"prometheus\"} == 1", "the self-scrape");
    await("up{job=\"gw-it-down\"} == 0", "the down target's first scrape");
    await("ALERTS{alertname=\"GwItAlwaysFiring\", alertstate=\"firing\"}", "the alert to fire");
    await("gw_it:up:sum", "the recording rule");
  }

  @AfterAll
  static void close() {
    if (connector != null) {
      connector.close();
    }
  }

  /** Waits until the instant query has a series, straight over HTTP. */
  private static void await(String promql, String what) throws Exception {
    HttpClient http = HttpClient.newHttpClient();
    Instant until = Instant.now().plusSeconds(90);
    String last = "";
    while (Instant.now().isBefore(until)) {
      try {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(base.resolve("/api/v1/query?query="
            + URLEncoder.encode(promql, StandardCharsets.UTF_8))).build(), HttpResponse.BodyHandlers.ofString());
        last = r.body();
        if (r.statusCode() == 200 && !JSON.readTree(r.body()).at("/data/result").isEmpty()) {
          return;
        }
      } catch (java.io.IOException e) {
        last = e.toString();
      }
      Thread.sleep(500);
    }
    throw new IllegalStateException("Prometheus didn't show " + what + " within 90 s: " + last);
  }

  private static ToolResult run(String tool, String args) throws ConnectorException {
    return run(tool, args, TestCtx.of());
  }

  private static ToolResult run(String tool, String args, TestCtx ctx) throws ConnectorException {
    ToolCall call = new ToolCall("tc_it", tool, "it-prom", (ObjectNode) JSON.readTree(args), null);
    Classification c = connector.classify(call, cfg, new PolicyView(Env.DEV, "dev.prometheus",
        JsonNodeFactory.instance.objectNode(), TestCtx.dev()));
    assertThat(c.kind()).as(c.reason()).isEqualTo(Classification.Kind.READ);
    return connector.execute(call, cfg, NO_SECRETS, ctx);
  }

  private static String text(ToolResult r) {
    return r.content().getFirst().text();
  }

  @Test
  void testConnectionAndHealthSeeTheRealServer() {
    TestResult t = connector.test(cfg, NO_SECRETS, TestCtx.of());
    assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
    assertThat(t.serverVersion()).isEqualTo("Prometheus 3.15.1");
    assertThat(t.checks()).extracting(TestResult.Check::name).containsExactly("server", "query", "targets");
    assertThat(t.checks()).allSatisfy(c -> assertThat(c.ok()).as(c.detail()).isTrue());
    assertThat(t.checks().get(2).detail()).isEqualTo("2 up series (scrape targets)");
    assertThat(connector.health(cfg, NO_SECRETS, TestCtx.of().timeout(Duration.ofSeconds(5))).state())
        .isEqualTo(HealthResult.State.HEALTHY);
  }

  @Test
  void anInstantQueryGivesExactValuesAndUtcTimes() throws Exception {
    ToolResult up = run("prometheus_query", "{\"query\": \"up\"}");
    JsonNode s = up.structuredContent();
    assertThat(s.path("resultType").asString()).isEqualTo("vector");
    List<String> jobs = new ArrayList<>();
    for (JsonNode series : s.path("series")) {
      jobs.add(series.at("/metric/job").asString() + "=" + series.at("/value/1").asString());
      assertThat(Instant.parse(series.at("/value/0").asString())).isCloseTo(Instant.now(),
          org.assertj.core.api.Assertions.within(1, ChronoUnit.MINUTES));
    }
    assertThat(jobs).containsExactlyInAnyOrder("prometheus=1", "gw-it-down=0");
    assertThat(text(up)).contains("up{instance=\"127.0.0.1:9090\", job=\"prometheus\"} 1");

    ToolResult special = run("prometheus_query", "{\"query\": \"label_replace(vector(0) / 0, \\\"v\\\", \\\"nan\\\", "
        + "\\\"\\\", \\\"\\\") or label_replace(vector(1) / 0, \\\"v\\\", \\\"inf\\\", \\\"\\\", \\\"\\\") or "
        + "label_replace(-vector(1) / 0, \\\"v\\\", \\\"-inf\\\", \\\"\\\", \\\"\\\") or label_replace(vector("
        + "0.1 + 0.2), \\\"v\\\", \\\"sum\\\", \\\"\\\", \\\"\\\")\"}");
    List<String> values = new ArrayList<>();
    special.structuredContent().path("series").forEach(x -> values.add(x.at("/metric/v").asString() + "="
        + x.at("/value/1").asString()));
    assertThat(values).containsExactlyInAnyOrder("nan=NaN", "inf=+Inf", "-inf=-Inf", "sum=0.30000000000000004");

    ToolResult scalar = run("prometheus_query", "{\"query\": \"scalar(vector(42.5))\", \"time\": "
        + "\"2026-01-02T03:04:05.678Z\"}");
    assertThat(scalar.structuredContent().path("value").toString())
        .isEqualTo("[\"2026-01-02T03:04:05.678Z\",\"42.5\"]");
    ToolResult string = run("prometheus_query", "{\"query\": \"\\\"hello\\\"\"}");
    assertThat(string.structuredContent().path("resultType").asString()).isEqualTo("string");
    assertThat(string.structuredContent().at("/value/1").asString()).isEqualTo("hello");

    // A range selector at one time: the raw samples, with their own millisecond times.
    ToolResult raw = run("prometheus_query", "{\"query\": \"up{job=\\\"prometheus\\\"}[30s]\"}");
    assertThat(raw.structuredContent().path("resultType").asString()).isEqualTo("matrix");
    assertThat(raw.structuredContent().at("/series/0/values").size()).isBetween(1, 7);
  }

  @Test
  void aRangeQueryHasEveryStepAndTheServerTakesTheTimeoutAndLimit() throws Exception {
    Instant end = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    ToolResult r = run("prometheus_query_range", "{\"query\": \"up\", \"start\": \"" + end.minusSeconds(60)
        + "\", \"end\": \"" + end + "\", \"step\": \"5s\"}");
    JsonNode s = r.structuredContent();
    assertThat(s.path("resultType").asString()).isEqualTo("matrix");
    assertThat(s.path("series")).hasSize(2);
    for (JsonNode series : s.path("series")) {
      JsonNode values = series.path("values");
      assertThat(values.size()).isBetween(1, 13);
      Instant last = Instant.parse(values.get(values.size() - 1).get(0).asString());
      assertThat(last).isBeforeOrEqualTo(end);
      assertThat(Duration.between(Instant.parse(values.get(0).get(0).asString()), last).toSeconds() % 5).isZero();
    }
    assertThat(text(r)).contains("step 5s");

    // More series than the limit: the server is asked for one more and the answer says it was cut.
    ToolResult limited = run("prometheus_query_range", "{\"query\": \"prometheus_http_requests_total\", \"limit\": 1, "
        + "\"start\": \"-1m\"}");
    assertThat(limited.structuredContent().path("series")).hasSize(1);
    assertThat(limited.truncatedReason()).isEqualTo("row_limit");

    // The most points Prometheus takes per series (11,000) is the most the connection allows.
    ToolResult most = run("prometheus_query_range", "{\"query\": \"vector(1)\", \"start\": \"" + end.minusSeconds(
        10_999) + "\", \"end\": \"" + end + "\", \"step\": \"1s\"}");
    assertThat(most.structuredContent().at("/series/0/pointCount").asInt()).isEqualTo(11_000);
    ToolCall tooMany = new ToolCall("tc_it", "prometheus_query_range", "it-prom", (ObjectNode) JSON.readTree(
        "{\"query\": \"vector(1)\", \"start\": \"" + end.minusSeconds(11_000) + "\", \"end\": \"" + end + "\", "
            + "\"step\": \"1s\"}"), null);
    assertThat(connector.classify(tooMany, cfg, new PolicyView(Env.DEV, "dev.prometheus",
        JsonNodeFactory.instance.objectNode(), TestCtx.dev())).ruleId()).isEqualTo("dev.prometheus.too-many-points");
  }

  @Test
  void nativeHistogramsKeepTheirCountSumAndBuckets() throws Exception {
    // The health check reads /-/ready every 2 s: Prometheus' own native histogram of it has data.
    String selector = "prometheus_http_request_duration_seconds{handler=\\\"/-/ready\\\"}";
    ToolResult h = run("prometheus_query", "{\"query\": \"" + selector + "\"}");
    JsonNode histogram = h.structuredContent().at("/series/0/histogram/1");
    assertThat(Long.parseLong(histogram.path("count").asString())).isPositive();
    assertThat(histogram.path("sum").asString()).isNotBlank();
    JsonNode bucket = histogram.path("buckets").get(0);
    assertThat(bucket.get(0).isInt()).isTrue();
    assertThat(bucket.get(1).isString()).isTrue();
    assertThat(text(h)).contains("histogram count=");
    ToolResult range = run("prometheus_query_range", "{\"query\": \"" + selector + "\", \"start\": \"-1m\", "
        + "\"step\": \"10s\"}");
    assertThat(range.structuredContent().at("/series/0/histograms").size()).isPositive();
  }

  @Test
  void seriesLabelsValuesAndMetadata() throws Exception {
    ToolResult series = run("prometheus_series", "{\"match\": [\"up\"], \"start\": \"-5m\"}");
    assertThat(series.structuredContent().path("series").toString()).contains("\"job\":\"prometheus\"",
        "\"job\":\"gw-it-down\"");

    ToolResult labels = run("prometheus_labels", "{\"match\": [\"up\"]}");
    assertThat(labels.structuredContent().path("labels").toString()).contains("\"__name__\"", "\"instance\"",
        "\"job\"");

    ToolResult jobs = run("prometheus_label_values", "{\"label\": \"job\"}");
    assertThat(jobs.structuredContent().path("values").toString()).contains("\"prometheus\"", "\"gw-it-down\"");
    ToolResult names = run("prometheus_label_values", "{\"label\": \"__name__\", \"limit\": 5}");
    assertThat(names.structuredContent().path("values")).hasSize(5);
    assertThat(names.truncatedReason()).isEqualTo("row_limit");
    // A name outside the classic set travels in Prometheus 3's encoding, which the server reads.
    ToolResult utf8 = run("prometheus_label_values", "{\"label\": \"http.method\"}");
    assertThat(utf8.structuredContent().path("values")).isEmpty();

    ToolResult meta = run("prometheus_metadata", "{\"metric\": \"prometheus_build_info\"}");
    assertThat(meta.structuredContent().at("/metrics/0/type").asString()).isEqualTo("gauge");
    assertThat(meta.structuredContent().at("/metrics/0/help").asString()).isNotBlank();
  }

  @Test
  void targetsRulesAndAlerts() throws Exception {
    ToolResult active = run("prometheus_targets", "{}");
    JsonNode targets = active.structuredContent().path("targets");
    assertThat(targets).hasSize(2);
    for (JsonNode t : targets) {
      if (t.path("job").asString().equals("gw-it-down")) {
        assertThat(t.path("health").asString()).isEqualTo("down");
        assertThat(t.path("lastError").asString()).contains("connection refused");
      } else {
        assertThat(t.path("job").asString()).isEqualTo("prometheus");
        assertThat(t.path("health").asString()).isEqualTo("up");
        assertThat(t.path("scrapeUrl").asString()).isEqualTo("http://127.0.0.1:9090/metrics");
      }
    }
    assertThat(text(active)).startsWith("2 active targets: ");
    ToolResult dropped = run("prometheus_targets", "{\"state\": \"dropped\"}");
    assertThat(dropped.structuredContent().path("targets").toString()).contains("\"gw_drop\":\"yes\"");

    ToolResult rules = run("prometheus_rules", "{}");
    assertThat(rules.structuredContent().path("rules")).hasSize(2);
    assertThat(text(rules)).contains("record gw_it:up:sum · group gw-it · health ok\n  expr: sum(up)",
        "alert GwItAlwaysFiring [firing] · group gw-it");
    assertThat(run("prometheus_rules", "{\"type\": \"record\"}").structuredContent().path("rules")).hasSize(1);

    ToolResult alerts = run("prometheus_alerts", "{\"state\": \"firing\"}");
    assertThat(alerts.structuredContent().at("/alerts/0/labels/alertname").asString()).isEqualTo("GwItAlwaysFiring");
    assertThat(alerts.structuredContent().at("/alerts/0/value").asString()).isEqualTo("1e+00");
    assertThat(text(alerts)).contains("Fires so that the integration test sees an alert.");
  }

  @Test
  void theServersOwnWordsForABadQuery() {
    assertThatThrownBy(() -> run("prometheus_query", "{\"query\": \"sum(up\"}"))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
          assertThat(e.getMessage()).startsWith("The server refused the query: ").contains("parse error");
        });
    assertThatThrownBy(() -> run("prometheus_query_range", "{\"query\": \"up[5m]\", \"start\": \"-5m\"}"))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.getMessage()).contains("range vector"));
  }

  @Test
  void anApprovedReadRunsTheWindowItWasApprovedWith() throws Exception {
    ToolCall call = new ToolCall("tc_it", "prometheus_query_range", "it-prom", (ObjectNode) JSON.readTree(
        "{\"query\": \"up{job=\\\"prometheus\\\"}\", \"start\": \"-2m\", \"step\": \"10s\"}"), null);
    ActionPreview preview = connector.describeAction(call, cfg);
    assertThat(preview.text()).startsWith("GET " + base + "/api/v1/query_range?query=");
    Instant end = Instant.parse(preview.payload().at("/arguments/end").asString());
    Thread.sleep(1_100);
    ToolResult r = connector.execute(call, cfg, NO_SECRETS, TestCtx.of().approved(preview.payload()));
    assertThat(r.structuredContent().path("end").asString()).isEqualTo(end.toString());
  }
}
