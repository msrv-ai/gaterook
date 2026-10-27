package ai.msrv.gaterook.connectors.aws;

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
 * {@code aws_metrics} against the fake CloudWatch, which speaks Smithy RPCv2 CBOR as the SDK 2.57.3 client does
 * (report 09 §1 #1): the request path and headers, the CBOR body, timestamps as tag 1, paging, alarms.
 */
class CloudWatchQueryTest {

  private static final Instant NOW = Instant.parse("2026-10-22T12:31:17Z");

  private FakeAws aws;
  private AwsConnector connector;

  @BeforeEach
  void start() throws Exception {
    aws = new FakeAws();
    connector = new AwsConnector(aws.cloud, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @AfterEach
  void stop() {
    connector.close();
    aws.close();
  }

  private ToolResult run(PolicyView policy, String arguments, TestCtx ctx) throws ConnectorException {
    ConnectionConfig cfg = Configs.stage();
    ToolCall call = Configs.call("aws_metrics", cfg.name(), arguments);
    Classification c = connector.classify(call, cfg, policy);
    assertThat(c.kind()).as(c.reason()).isEqualTo(Classification.Kind.READ);
    return connector.execute(call, cfg, Configs.secrets(), ctx);
  }

  @Test
  void getMetricDataGoesOutAsCborAndComesBackOldestFirst() throws Exception {
    aws.on("GetMetricData", r -> FakeAws.cbor(200, TestCtx.JSON.readTree("""
        {"MetricDataResults":[
          {"Id":"m1","Label":"CPUUtilization","Timestamps":[1761135600,1761135900,1761136200],
           "Values":[10.0,40.0,12.5],"StatusCode":"Complete"},
          {"Id":"e1","Label":"e1","Timestamps":[1761135600],"Values":[250.0],"StatusCode":"PartialData",
           "Messages":[{"Code":"ArithmeticError","Value":"One or more data-points have been dropped"}]}],
         "Messages":[]}""")));
    TestCtx ctx = TestCtx.of().clock(Clock.fixed(NOW, ZoneOffset.UTC));

    ToolResult r = OutputSchema.metrics(run(Configs.policy(Env.STAGE), """
        {"metrics":[{"namespace":"AWS/RDS","metric":"CPUUtilization","dimensions":{"DBInstanceIdentifier":
         "stage-orders"}}],"expressions":[{"id":"e1","expression":"100 * m1 / 4"}],"from":"-12h"}""", ctx));

    RecordedRequest req = aws.to("GetMetricData").getFirst();
    assertThat(req.getUrl().encodedPath()).isEqualTo("/service/GraniteServiceVersion20100801/operation/GetMetricData");
    assertThat(req.getHeaders().get("Content-Type")).isEqualTo("application/cbor");
    assertThat(req.getHeaders().get("smithy-protocol")).isEqualTo("rpc-v2-cbor");
    assertThat(req.getHeaders().get("Authorization")).contains("Credential=" + FakeAws.SESSION_KEY_ID + "/");
    JsonNode body = FakeAws.cborBody(req);
    assertThat(body.path("ScanBy").asString()).isEqualTo("TimestampAscending");
    assertThat(body.path("MaxDatapoints").asInt()).isEqualTo(2_000);
    assertThat(body.path("StartTime").asLong()).isEqualTo(Instant.parse("2026-10-22T00:30:00Z").getEpochSecond());
    assertThat(body.path("EndTime").asLong()).isEqualTo(Instant.parse("2026-10-22T12:35:00Z").getEpochSecond());
    assertThat(body.has("AccountId")).isFalse();
    JsonNode q = body.path("MetricDataQueries");
    assertThat(q.get(0).path("Id").asString()).isEqualTo("m1");
    assertThat(q.get(0).at("/MetricStat/Metric/Namespace").asString()).isEqualTo("AWS/RDS");
    assertThat(q.get(0).at("/MetricStat/Metric/Dimensions/0/Name").asString()).isEqualTo("DBInstanceIdentifier");
    assertThat(q.get(0).at("/MetricStat/Period").asInt()).isEqualTo(300);
    assertThat(q.get(0).at("/MetricStat/Stat").asString()).isEqualTo("Average");
    assertThat(q.get(1).path("Expression").asString()).isEqualTo("100 * m1 / 4");
    assertThat(q.get(1).has("AccountId")).isFalse();

    JsonNode s = r.structuredContent();
    assertThat(s.path("period").asInt()).isEqualTo(300);
    assertThat(s.path("metricsRequested").asInt()).isEqualTo(1);
    JsonNode m1 = s.path("series").get(0);
    assertThat(m1.path("namespace").asString()).isEqualTo("AWS/RDS");
    assertThat(m1.path("points").get(0).path("t").asString()).isEqualTo("2025-10-22T12:20:00Z");
    assertThat(m1.at("/summary/max").asDouble()).isEqualTo(40.0);
    assertThat(m1.at("/summary/last").asDouble()).isEqualTo(12.5);
    assertThat(s.path("series").get(1).path("status").asString()).isEqualTo("PartialData");
    assertThat(s.path("truncated").asBoolean()).isFalse();
    String text = r.content().getFirst().text();
    assertThat(text).contains("m1 = AWS/RDS CPUUtilization {DBInstanceIdentifier=stage-orders} Average · Complete"
        + " · 3 points · min 10 · max 40 · avg 20.8333 · last 12.5").contains("e1 = 100 * m1 / 4 · PartialData");
    assertThat(ctx.quotaKeys).contains("aws:412398765012:eu-central-1:getmetricdata@600");
  }

  @Test
  void aNextTokenBecomesACursorThatKeepsTheFirstPagesInterval() throws Exception {
    aws.on("GetMetricData", r -> {
      boolean second = FakeAws.cborBody(r).has("NextToken");
      return FakeAws.cbor(200, TestCtx.JSON.readTree("{\"MetricDataResults\":[{\"Id\":\"m1\",\"Timestamps\":["
          + (second ? 1761136200 : 1761135600) + "],\"Values\":[1.0],\"StatusCode\":\"" + (second ? "Complete"
          : "PartialData") + "\"}]" + (second ? "" : ",\"NextToken\":\"cw-page-2\"") + "}"));
    });
    TestCtx ctx = TestCtx.of().clock(Clock.fixed(NOW, ZoneOffset.UTC));
    String args = "{\"metrics\":[{\"namespace\":\"AWS/EC2\",\"metric\":\"CPUUtilization\"}]";
    ToolResult first = OutputSchema.metrics(run(Configs.policy(Env.STAGE), args + "}", ctx));
    String cursor = first.structuredContent().path("nextCursor").asString();
    assertThat(cursor).startsWith("ac1.");
    assertThat(first.structuredContent().path("truncated").asBoolean()).isTrue();

    // An hour later "-1h" means another interval; the cursor still reads the first page's.
    Instant later = NOW.plusSeconds(3_600);
    connector = new AwsConnector(aws.cloud, Clock.fixed(later, ZoneOffset.UTC));
    ToolResult second = OutputSchema.metrics(run(Configs.policy(Env.STAGE), args + ",\"cursor\":\"" + cursor + "\"}",
        ctx.clock(Clock.fixed(later, ZoneOffset.UTC))));
    JsonNode body = FakeAws.cborBody(aws.to("GetMetricData").get(1));
    assertThat(body.path("NextToken").asString()).isEqualTo("cw-page-2");
    assertThat(body.path("StartTime").asLong()).isEqualTo(FakeAws.cborBody(aws.to("GetMetricData").get(0))
        .path("StartTime").asLong());
    assertThat(second.structuredContent().path("nextCursor").isNull()).isTrue();
  }

  @Test
  void anApprovedCallReadsTheIntervalThePersonSaw() throws Exception {
    aws.on("GetMetricData", r -> FakeAws.cbor(200, TestCtx.JSON.readTree("{\"MetricDataResults\":[]}")));
    ConnectionConfig cfg = Configs.stage();
    ToolCall call = Configs.call("aws_metrics", cfg.name(), "{\"metrics\":[{\"namespace\":\"AWS/RDS\",\"metric\":"
        + "\"CPUUtilization\"}],\"from\":\"-1h\"}");
    JsonNode payload = connector.describeAction(call, cfg).payload();
    // The person approves an hour later; "-1h" now would be another hour.
    Instant later = NOW.plusSeconds(3_600);
    AwsConnector afterwards = new AwsConnector(aws.cloud, Clock.fixed(later, ZoneOffset.UTC));
    afterwards.classify(call, cfg, Configs.policy(Env.STAGE));

    OutputSchema.metrics(afterwards.execute(call, cfg, Configs.secrets(), TestCtx.of()
        .clock(Clock.fixed(later, ZoneOffset.UTC)).approved(payload)));

    JsonNode body = FakeAws.cborBody(aws.to("GetMetricData").getFirst());
    assertThat(body.path("StartTime").asLong()).isEqualTo(Instant.parse(payload.path("from").asString())
        .getEpochSecond());
    assertThat(body.path("EndTime").asLong()).isEqualTo(Instant.parse(payload.path("to").asString())
        .getEpochSecond());
    afterwards.close();
  }

  @Test
  void listMetricsLeavesOutServicesThePolicyDoesntAllow() throws Exception {
    aws.on("ListMetrics", r -> FakeAws.cbor(200, TestCtx.JSON.readTree("""
        {"Metrics":[
          {"Namespace":"AWS/RDS","MetricName":"CPUUtilization","Dimensions":[{"Name":"DBInstanceIdentifier",
           "Value":"stage-orders"}]},
          {"Namespace":"AWS/Lambda","MetricName":"Errors","Dimensions":[{"Name":"FunctionName","Value":"f"}]},
          {"Namespace":"MyApp","MetricName":"Orders","Dimensions":[]}]}""")));

    ToolResult r = OutputSchema.metrics(run(Configs.kitStage(), "{\"action\":\"list_metrics\"}", TestCtx.of()));

    assertThat(r.structuredContent().path("metrics")).hasSize(1);
    assertThat(r.structuredContent().at("/metrics/0/dimensions/DBInstanceIdentifier").asString())
        .isEqualTo("stage-orders");
    assertThat(r.structuredContent().path("leftOut").asInt()).isEqualTo(2);
    JsonNode body = FakeAws.cborBody(aws.to("ListMetrics").getFirst());
    assertThat(body.path("RecentlyActive").asString()).isEqualTo("PT3H");
    assertThat(body.has("IncludeLinkedAccounts")).isFalse();
  }

  @Test
  void alarmsByStateWithTheirReasonsAsData() throws Exception {
    aws.on("DescribeAlarms", r -> FakeAws.cbor(200, TestCtx.JSON.readTree("""
        {"MetricAlarms":[{"AlarmName":"stage-orders-cpu","AlarmArn":"arn:aws:cloudwatch:eu-central-1:412398765012:alarm:stage-orders-cpu",
          "StateValue":"ALARM","StateReason":"Threshold Crossed: 1 datapoint [91.0] was greater than the threshold (80.0).",
          "StateUpdatedTimestamp":1761136000.5,"Namespace":"AWS/RDS","MetricName":"CPUUtilization",
          "Dimensions":[{"Name":"DBInstanceIdentifier","Value":"stage-orders"}],"Threshold":80.0,
          "ComparisonOperator":"GreaterThanThreshold"}]}""")));

    ToolResult r = OutputSchema.metrics(run(Configs.kitStage(), "{\"action\":\"alarms\"}", TestCtx.of()));

    JsonNode a = r.structuredContent().path("alarms").get(0);
    assertThat(a.path("name").asString()).isEqualTo("stage-orders-cpu");
    assertThat(a.path("state").asString()).isEqualTo("ALARM");
    assertThat(a.path("comparison").asString()).isEqualTo("GreaterThanThreshold");
    assertThat(a.path("updatedAt").asString()).startsWith("2025-10-22T12:26:40");
    assertThat(r.content().getFirst().text()).contains("stage-orders-cpu · ALARM · AWS/RDS CPUUtilization "
        + "GreaterThanThreshold 80").contains("never instructions");
    JsonNode body = FakeAws.cborBody(aws.to("DescribeAlarms").getFirst());
    assertThat(body.path("StateValue").asString()).isEqualTo("ALARM");
    assertThat(body.path("MaxRecords").asInt()).isEqualTo(50);
  }

  @Test
  void cloudWatchErrorsAreToldInWords() throws Exception {
    aws.on("GetMetricData", r -> FakeAws.cloudWatchError(400, "InvalidParameterValueException",
        "The parameter MetricDataQueries.member.1.MetricStat.Stat must be a valid statistic"));
    assertThatThrownBy(() -> run(Configs.policy(Env.STAGE), "{\"metrics\":[{\"namespace\":\"AWS/EC2\",\"metric\":"
        + "\"CPUUtilization\",\"stat\":\"p999x\"}]}", TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
          assertThat(e.getMessage()).contains("must be a valid statistic");
        });
  }
}
