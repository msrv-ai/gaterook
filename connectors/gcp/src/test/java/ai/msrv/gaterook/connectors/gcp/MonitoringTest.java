package ai.msrv.gaterook.connectors.gcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code gcp_metrics} against fakes of Cloud Monitoring (report 09 §3.4, §8): the filter, interval and aggregation of
 * {@code timeSeries.list} exactly as the request says, the aligner chosen by the metric's kind, typed points oldest
 * first, limits in points and series, the cursor carrying its interval, and the metrics and alerts lists filtered by
 * the policy.
 */
class MonitoringTest {

  private static final Instant NOW = Instant.parse("2026-10-22T12:30:00Z");
  private static final String METRIC = "run.googleapis.com/request_count";
  private FakeGoogle google;
  private GcpConnector connector;
  private final ConnectionConfig cfg = Configs.stage();
  private int descriptorCalls;

  @BeforeEach
  void start() throws Exception {
    google = new FakeGoogle();
    google.on("/v3/projects/acme-stage/metricDescriptors/", r -> {
      descriptorCalls++;
      String type = r.getUrl().encodedPath().substring("/v3/projects/acme-stage/metricDescriptors/".length());
      return switch (type) {
        case METRIC -> FakeGoogle.json(200, "{\"type\":\"" + METRIC + "\",\"metricKind\":\"DELTA\",\"valueType\":"
            + "\"INT64\",\"unit\":\"1\"}");
        case "run.googleapis.com/request_latencies" -> FakeGoogle.json(200, "{\"metricKind\":\"DELTA\","
            + "\"valueType\":\"DISTRIBUTION\",\"unit\":\"ms\"}");
        case "compute.googleapis.com/instance/cpu/utilization" -> FakeGoogle.json(200, "{\"metricKind\":\"GAUGE\","
            + "\"valueType\":\"DOUBLE\",\"unit\":\"10^2.%\"}");
        default -> FakeGoogle.json(404, "{\"error\":{\"code\":404,\"message\":\"Could not find descriptor\","
            + "\"status\":\"NOT_FOUND\"}}");
      };
    });
    connector = new GcpConnector(google.cloud, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @AfterEach
  void stop() {
    connector.close();
    google.close();
  }

  private ToolResult run(String arguments, PolicyView policy, TestCtx ctx) throws ConnectorException {
    ToolCall call = Configs.call("gcp_metrics", cfg.name(), arguments);
    Classification c = connector.classify(call, cfg, policy);
    assertThat(c.kind()).as(c.reason()).isEqualTo(Classification.Kind.READ);
    return OutputSchema.metrics(connector.execute(call, cfg, new TestSecrets(), ctx));
  }

  private static TestCtx ctx() {
    return TestCtx.of().clock(Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void queriesOneMetricWithItsFilterIntervalAndTheAlignerOfItsKind() throws Exception {
    google.on("/v3/projects/acme-stage/timeSeries", r -> FakeGoogle.json(200, """
        {"timeSeries":[{"metric":{"type":"%s","labels":{"response_code_class":"5xx"}},
          "resource":{"type":"cloud_run_revision","labels":{"service_name":"api","location":"europe-west1"}},
          "metricKind":"DELTA","valueType":"DOUBLE","points":[
            {"interval":{"startTime":"2026-10-22T12:20:00Z","endTime":"2026-10-22T12:25:00Z"},"value":{"doubleValue":0.4}},
            {"interval":{"startTime":"2026-10-22T12:15:00Z","endTime":"2026-10-22T12:20:00Z"},"value":{"doubleValue":0.1}},
            {"interval":{"startTime":"2026-10-22T12:10:00Z","endTime":"2026-10-22T12:15:00Z"},"value":{"doubleValue":0.2}}]}]}
        """.formatted(METRIC)));
    TestCtx ctx = ctx();

    ToolResult r = run("{\"metric\":\"" + METRIC + "\",\"filter\":\"resource.labels.service_name = \\\"api\\\"\","
        + "\"from\":\"-1h\",\"period\":300}", Configs.policy(Env.STAGE), ctx);

    RecordedRequest list = google.to("/v3/projects/acme-stage/timeSeries").getFirst();
    assertThat(FakeGoogle.param(list, "filter")).isEqualTo("metric.type = \"" + METRIC + "\" AND "
        + "(resource.labels.service_name = \"api\")");
    assertThat(FakeGoogle.param(list, "interval.startTime")).isEqualTo("2026-10-22T11:30:00Z");
    assertThat(FakeGoogle.param(list, "interval.endTime")).isEqualTo("2026-10-22T12:30:00Z");
    assertThat(FakeGoogle.param(list, "aggregation.alignmentPeriod")).isEqualTo("300s");
    assertThat(FakeGoogle.param(list, "aggregation.perSeriesAligner")).as("DELTA → rate").isEqualTo("ALIGN_RATE");
    assertThat(FakeGoogle.param(list, "aggregation.crossSeriesReducer")).isNull();
    assertThat(FakeGoogle.param(list, "view")).isEqualTo("FULL");
    assertThat(FakeGoogle.param(list, "pageSize")).as("the policy's points").isEqualTo("2000");
    assertThat(list.getHeaders().get("Authorization")).isEqualTo("Bearer " + google.token);
    assertThat(ctx.quotaKeys).containsExactly("gcp:acme-stage:monitoring@300", "gcp:acme-stage:monitoring@300");
    assertThat(ctx.taints).contains(google.token);

    JsonNode s = r.structuredContent();
    assertThat(s.path("aligner").asString()).isEqualTo("ALIGN_RATE");
    assertThat(s.path("period").asInt()).isEqualTo(300);
    assertThat(s.at("/series/0/resource/labels/service_name").asString()).isEqualTo("api");
    assertThat(s.at("/series/0/metricLabels/response_code_class").asString()).isEqualTo("5xx");
    assertThat(s.at("/series/0/points/0/t").asString()).as("oldest first").isEqualTo("2026-10-22T12:15:00Z");
    assertThat(s.at("/series/0/points/2/v").asDouble()).isEqualTo(0.4);
    assertThat(s.at("/series/0/summary/max").asDouble()).isEqualTo(0.4);
    assertThat(s.at("/series/0/summary/last").asDouble()).isEqualTo(0.4);
    assertThat(s.at("/series/0/summary/avg").asDouble()).isEqualTo(0.2333);
    assertThat(r.content().getFirst().text()).startsWith("1 series of " + METRIC + " in acme-stage · "
        + "2026-10-22T11:30:00Z → 2026-10-22T12:30:00Z (1 h) · period 300 s · ALIGN_RATE · unit 1")
        .contains("location=europe-west1 service_name=api response_code_class=5xx (cloud_run_revision) · 3 points")
        .contains("2026-10-22T12:15:00Z 0.2, 2026-10-22T12:20:00Z 0.1, 2026-10-22T12:25:00Z 0.4");
  }

  @Test
  void typedValuesBecomeNumbersAndTheDescriptorIsAskedOnce() throws Exception {
    google.on("/v3/projects/acme-stage/timeSeries", r -> FakeGoogle.json(200, """
        {"timeSeries":[{"metric":{"type":"x"},"resource":{"type":"y"},"points":[
          {"interval":{"endTime":"2026-10-22T12:25:00Z"},"value":{"int64Value":"9007199254740993"}},
          {"interval":{"endTime":"2026-10-22T12:20:00Z"},"value":{"int64Value":"12"}}]},
         {"metric":{"type":"x"},"resource":{"type":"y"},"valueType":"DISTRIBUTION","points":[
          {"interval":{"endTime":"2026-10-22T12:25:00Z"},"value":{"distributionValue":{"count":"40","mean":12.5}}}]}]}
        """));

    JsonNode s = run("{\"metric\":\"" + METRIC + "\"}", Configs.policy(Env.STAGE), ctx()).structuredContent();
    run("{\"metric\":\"" + METRIC + "\",\"from\":\"-2h\"}", Configs.policy(Env.STAGE), ctx());

    assertThat(s.at("/series/0/points/1/v").asLong()).as("int64 beyond 2^53 stays exact").isEqualTo(
        9007199254740993L);
    assertThat(s.at("/series/0/points/0/v").isNumber()).isTrue();
    assertThat(s.at("/series/1/points/0/v/count").asLong()).isEqualTo(40);
    assertThat(s.at("/series/1/points/0/v/mean").asDouble()).isEqualTo(12.5);
    assertThat(s.path("period").asInt()).as("1 h in ≤ 360 points").isEqualTo(60);
    assertThat(descriptorCalls).as("cached for an hour").isEqualTo(1);
  }

  @Test
  void theAlignerFollowsTheKindAndAReducerGroups() throws Exception {
    google.on("/v3/projects/acme-stage/timeSeries", r -> FakeGoogle.json(200, "{\"timeSeries\":[]}"));

    run("{\"metric\":\"compute.googleapis.com/instance/cpu/utilization\",\"reducer\":\"REDUCE_MEAN\",\"groupBy\":"
        + "[\"resource.labels.zone\",\"metric.labels.instance_name\"]}", Configs.policy(Env.STAGE), ctx());
    run("{\"metric\":\"run.googleapis.com/request_latencies\"}", Configs.policy(Env.STAGE), ctx());

    RecordedRequest gauge = google.to("/v3/projects/acme-stage/timeSeries").getFirst();
    assertThat(FakeGoogle.param(gauge, "aggregation.perSeriesAligner")).isEqualTo("ALIGN_MEAN");
    assertThat(FakeGoogle.param(gauge, "aggregation.crossSeriesReducer")).isEqualTo("REDUCE_MEAN");
    assertThat(FakeGoogle.params(gauge, "aggregation.groupByFields")).containsExactly("resource.labels.zone",
        "metric.labels.instance_name");
    RecordedRequest distribution = google.to("/v3/projects/acme-stage/timeSeries").getLast();
    assertThat(FakeGoogle.param(distribution, "aggregation.perSeriesAligner")).isEqualTo("ALIGN_DELTA");
  }

  @Test
  void seriesBeyondTheLimitAreCountedAndACursorCarriesTheFirstInterval() throws Exception {
    google.on("/v3/projects/acme-stage/timeSeries", r -> {
      if (FakeGoogle.param(r, "pageToken") == null) {
        return FakeGoogle.json(200, "{\"timeSeries\":[{\"metric\":{\"type\":\"m\"},\"resource\":{\"type\":\"a\"},"
            + "\"points\":[]}],\"nextPageToken\":\"ts-page-2\"}");
      }
      return FakeGoogle.json(200, "{\"timeSeries\":[{\"metric\":{\"type\":\"m\"},\"resource\":{\"type\":\"b\"},"
          + "\"points\":[]},{\"metric\":{\"type\":\"m\"},\"resource\":{\"type\":\"c\"},\"points\":[]}]}");
    });

    JsonNode first = run("{\"metric\":\"" + METRIC + "\",\"limit\":1}", Configs.policy(Env.STAGE), ctx())
        .structuredContent();
    String cursor = first.path("nextCursor").asString();
    assertThat(cursor).startsWith("gc1.");

    // An hour later the same "-1h" means another interval; the cursor keeps the first one.
    TestCtx later = TestCtx.of().clock(Clock.fixed(NOW.plusSeconds(3_600), ZoneOffset.UTC));
    ToolCall next = Configs.call("gcp_metrics", cfg.name(), "{\"metric\":\"" + METRIC + "\",\"limit\":1,\"cursor\":\""
        + cursor + "\"}");
    connector.classify(next, cfg, Configs.policy(Env.STAGE));
    JsonNode second = OutputSchema.metrics(connector.execute(next, cfg, new TestSecrets(), later)).structuredContent();

    RecordedRequest page2 = google.to("/v3/projects/acme-stage/timeSeries").getLast();
    assertThat(FakeGoogle.param(page2, "pageToken")).isEqualTo("ts-page-2");
    assertThat(FakeGoogle.param(page2, "interval.startTime")).isEqualTo("2026-10-22T11:30:00Z");
    assertThat(second.path("truncated").asBoolean()).isTrue();
    assertThat(second.path("truncatedReason").asString()).isEqualTo("row_limit");
    assertThat(second.path("messages").toString()).contains("1 more series not shown: limit 1");
    assertThat(second.path("nextCursor").isNull()).isTrue();
  }

  @Test
  void anUnknownMetricSaysHowToFindOne() {
    assertThatThrownBy(() -> run("{\"metric\":\"run.googleapis.com/nope\"}", Configs.policy(Env.STAGE), ctx()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
          assertThat(e.getMessage()).contains("has no metric run.googleapis.com/nope").contains("list_metrics");
        });
  }

  @Test
  void metricListsLeaveOutWhatThePolicyDoesntShow() throws Exception {
    google.on("/v3/projects/acme-stage/metricDescriptors", r -> FakeGoogle.json(200, """
        {"metricDescriptors":[
          {"type":"run.googleapis.com/request_count","metricKind":"DELTA","valueType":"INT64","unit":"1",
           "displayName":"Request Count","labels":[{"key":"response_code"},{"key":"route"}]},
          {"type":"secretmanager.googleapis.com/secret/access_count","metricKind":"DELTA","valueType":"INT64"}]}"""));

    ToolResult r = run("{\"action\":\"list_metrics\",\"prefix\":\"run.googleapis.com/\"}",
        Configs.policy(Env.STAGE), ctx());

    RecordedRequest list = google.to("/v3/projects/acme-stage/metricDescriptors").getFirst();
    assertThat(FakeGoogle.param(list, "filter")).isEqualTo("metric.type = starts_with(\"run.googleapis.com/\")");
    assertThat(FakeGoogle.param(list, "activeOnly")).isEqualTo("true");
    JsonNode s = r.structuredContent();
    assertThat(s.path("metrics")).hasSize(1);
    assertThat(s.at("/metrics/0/labels").toString()).isEqualTo("[\"response_code\",\"route\"]");
    assertThat(s.path("messages").toString()).contains("1 metrics of services");
    assertThat(r.content().getFirst().text()).contains("run.googleapis.com/request_count · DELTA INT64 · unit 1");
  }

  @Test
  void alertsAreFilteredByStateHere() throws Exception {
    google.on("/v3/projects/acme-stage/alerts", r -> FakeGoogle.json(200, """
        {"alerts":[
          {"name":"projects/acme-stage/alerts/a1","state":"OPEN","openTime":"2026-10-22T12:01:00Z",
           "policy":{"displayName":"API 5xx above 1%","severity":"ERROR"},
           "resource":{"type":"cloud_run_revision","labels":{"service_name":"api"}},
           "metric":{"type":"run.googleapis.com/request_count","labels":{"response_code_class":"5xx"}}},
          {"name":"projects/acme-stage/alerts/a0","state":"CLOSED","openTime":"2026-10-21T08:00:00Z",
           "closeTime":"2026-10-21T08:30:00Z","policy":{"displayName":"Old"}}]}"""));

    ToolResult r = run("{\"action\":\"alerts\"}", Configs.policy(Env.STAGE), ctx());

    JsonNode s = r.structuredContent();
    assertThat(s.path("alerts")).hasSize(1);
    assertThat(s.at("/alerts/0/policy/severity").asString()).isEqualTo("ERROR");
    assertThat(r.content().getFirst().text()).startsWith("1 open alert in acme-stage:")
        .contains("API 5xx above 1% · ERROR · opened 2026-10-22T12:01:00Z · run.googleapis.com/request_count");
  }

  @Test
  void seriesAndAlertsOfOtherProjectsInTheMetricsScopeAreLeftOut() throws Exception {
    // acme-stage scopes acme-prod's metrics (a metrics scope): its answers carry prod's series and alerts too.
    ConnectionConfig own = Configs.of("gcp-stage", Env.STAGE, """
        {"scopes":["projects/acme-stage"],"credentials":{"source":"adc","impersonate":"%s"}}"""
        .formatted(FakeGoogle.SA));
    google.on("/v3/projects/acme-stage/timeSeries", r -> FakeGoogle.json(200, """
        {"timeSeries":[
          {"metric":{"type":"%1$s"},"resource":{"type":"cloud_run_revision","labels":{"project_id":"acme-stage",
           "service_name":"api"}},"points":[{"interval":{"endTime":"2026-10-22T12:25:00Z"},"value":{"int64Value":"7"}}]},
          {"metric":{"type":"%1$s"},"resource":{"type":"cloud_run_revision","labels":{"project_id":"acme-prod",
           "service_name":"api"}},"points":[{"interval":{"endTime":"2026-10-22T12:25:00Z"},"value":{"int64Value":"9"}}]}
        ]}""".formatted(METRIC)));
    google.on("/v3/projects/acme-stage/alerts", r -> FakeGoogle.json(200, """
        {"alerts":[
          {"name":"projects/acme-stage/alerts/a1","state":"OPEN","openTime":"2026-10-22T12:01:00Z",
           "policy":{"displayName":"stage 5xx"},"resource":{"labels":{"project_id":"acme-stage"}}},
          {"name":"projects/acme-stage/alerts/a2","state":"OPEN","openTime":"2026-10-22T12:02:00Z",
           "policy":{"displayName":"prod 5xx"},"resource":{"labels":{"project_id":"acme-prod"}}}]}"""));

    ToolCall query = Configs.call("gcp_metrics", own.name(), "{\"metric\":\"" + METRIC + "\"}");
    connector.classify(query, own, Configs.policy(Env.STAGE));
    JsonNode s = OutputSchema.metrics(connector.execute(query, own, new TestSecrets(), ctx())).structuredContent();
    ToolCall alerts = Configs.call("gcp_metrics", own.name(), "{\"action\":\"alerts\"}");
    connector.classify(alerts, own, Configs.policy(Env.STAGE));
    JsonNode a = OutputSchema.metrics(connector.execute(alerts, own, new TestSecrets(), ctx())).structuredContent();

    assertThat(s.path("series")).hasSize(1);
    assertThat(s.at("/series/0/resource/labels/project_id").asString()).isEqualTo("acme-stage");
    assertThat(s.path("messages").toString()).contains("1 series of other projects in acme-stage's metrics scope is "
        + "left out");
    assertThat(s.toString()).doesNotContain("acme-prod");
    assertThat(a.path("alerts")).hasSize(1);
    assertThat(a.at("/alerts/0/policy/displayName").asString()).isEqualTo("stage 5xx");
    assertThat(a.toString()).doesNotContain("prod 5xx");
  }

  @Test
  void anApprovedCallReadsTheIntervalThePersonSaw() throws Exception {
    google.on("/v3/projects/acme-stage/timeSeries", r -> FakeGoogle.json(200, "{\"timeSeries\":[]}"));
    ToolCall call = Configs.call("gcp_metrics", cfg.name(), "{\"metric\":\"" + METRIC + "\",\"from\":\"-1h\"}");
    JsonNode payload = connector.describeAction(call, cfg).payload();
    assertThat(payload.path("from").asString()).isEqualTo("2026-10-22T11:30:00Z");

    // Executed ten minutes later, as an approval would be.
    connector.classify(call, cfg, Configs.policy(Env.STAGE));
    connector.execute(call, cfg, new TestSecrets(), TestCtx.of().clock(Clock.fixed(NOW.plusSeconds(600),
        ZoneOffset.UTC)).approved(payload));

    RecordedRequest list = google.to("/v3/projects/acme-stage/timeSeries").getFirst();
    assertThat(FakeGoogle.param(list, "interval.startTime")).isEqualTo("2026-10-22T11:30:00Z");
    assertThat(FakeGoogle.param(list, "interval.endTime")).isEqualTo("2026-10-22T12:30:00Z");
  }
}
