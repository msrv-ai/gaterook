package ai.msrv.gaterook.connectors.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mockwebserver3.MockResponse;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code aws_api_read} and {@code aws_api_change} end to end against AWS on this computer ({@link FakeAws}):
 * classification, the exact request on the wire of each protocol family, SigV4 for the endpoint's service and region,
 * the session policy AssumeRole carries, approval, cursors, masking and refusals that never reach AWS.
 */
class AwsCallTest {

  private FakeAws aws;
  private AwsConnector connector;

  @BeforeEach
  void start() throws IOException {
    aws = new FakeAws();
    connector = new AwsConnector(aws.cloud);
  }

  @AfterEach
  void stop() {
    connector.close();
    aws.close();
  }

  /** A development connection with an access key alone: no role, so no session policy. */
  private static ConnectionConfig keyOnly() {
    return Configs.of("aws-dev", Env.DEV, """
        {"regions":["eu-central-1"],"accountId":"412398765012","credentials":{"source":"accessKey",
         "accessKeyId":"%s"}}""".formatted(Configs.ACCESS_KEY_ID));
  }

  private ToolResult run(ConnectionConfig cfg, PolicyView policy, ToolCall call) throws ConnectorException {
    Classification c = connector.classify(call, cfg, policy);
    assertThat(c.kind()).as(c.reason()).isEqualTo(Classification.Kind.READ);
    return OutputSchema.apiRead(connector.execute(call, cfg, Configs.secrets(), TestCtx.of()));
  }

  private static Map<String, String> form(RecordedRequest r) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String pair : r.getBody().utf8().split("&")) {
      int eq = pair.indexOf('=');
      out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
          URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
    }
    return out;
  }

  /** The session policy of the last AssumeRole. */
  private JsonNode sessionPolicy() {
    List<RecordedRequest> assume = aws.to("AssumeRole");
    assertThat(assume).isNotEmpty();
    return TestCtx.JSON.readTree(form(assume.getLast()).get("Policy"));
  }

  @Test
  void aReadRunsAtOnceSignedForItsServiceUnderThatServicesReadSession() throws ConnectorException {
    aws.on("DescribeServices", r -> FakeAws.json(200, """
        {"services":[{"serviceName":"api","status":"ACTIVE","desiredCount":2,"runningCount":1,
          "createdAt":1762851600,"deployments":[{"status":"PRIMARY","rolloutState":"IN_PROGRESS"}]}],
         "failures":[]}"""));
    ConnectionConfig cfg = Configs.stage();
    ToolCall call = Configs.call("aws_api_read", cfg.name(), """
        {"service":"ECS","operation":"describe-services","parameters":{"services":["api"],"cluster":"stage"}}""");

    ToolResult result = run(cfg, Configs.policy(Env.STAGE), call);

    JsonNode s = result.structuredContent();
    assertThat(s.path("service").asString()).isEqualTo("ecs");
    assertThat(s.path("operation").asString()).isEqualTo("DescribeServices");
    assertThat(s.path("region").asString()).isEqualTo("eu-central-1");
    assertThat(s.path("account").asString()).isEqualTo("412398765012");
    assertThat(s.path("access").asString()).isEqualTo("read");
    assertThat(s.at("/result/services/0/runningCount").asInt()).isEqualTo(1);
    assertThat(s.at("/result/services/0/createdAt").asString()).isEqualTo("2025-11-11T09:00:00Z");
    assertThat(s.path("nextCursor").isNull()).isTrue();
    assertThat(result.content().getFirst().text()).startsWith("ecs DescribeServices in eu-central-1")
        .contains("\"rolloutState\":\"IN_PROGRESS\"");

    RecordedRequest r = aws.to("DescribeServices").getFirst();
    assertThat(r.getMethod()).isEqualTo("POST");
    assertThat(r.getUrl().encodedPath()).isEqualTo("/");
    assertThat(r.getHeaders().get("X-Amz-Target")).isEqualTo("AmazonEC2ContainerServiceV20141113.DescribeServices");
    assertThat(r.getHeaders().get("Content-Type")).isEqualTo("application/x-amz-json-1.1");
    assertThat(r.getHeaders().get("Authorization")).contains("Credential=" + FakeAws.SESSION_KEY_ID + "/")
        .contains("/eu-central-1/ecs/aws4_request").contains("SignedHeaders=").contains("x-amz-target");
    assertThat(r.getHeaders().get("X-Amz-Security-Token")).isEqualTo(FakeAws.SESSION_TOKEN);
    assertThat(r.getBody().utf8()).isEqualTo("{\"cluster\":\"stage\",\"services\":[\"api\"]}");

    // AWS enforces the read: the session allows ECS's read verbs and denies the secret reads, nothing else.
    JsonNode policy = sessionPolicy();
    assertThat(policy.at("/Statement/0/Action").toString()).isEqualTo("[\"ecs:Describe*\",\"ecs:List*\","
        + "\"ecs:Get*\",\"ecs:BatchGet*\",\"ecs:Search*\",\"ecs:Lookup*\"]");
    assertThat(policy.at("/Statement/1/Effect").asString()).isEqualTo("Deny");
    assertThat(policy.at("/Statement/1/Action").toString()).contains("secretsmanager:GetSecretValue");
  }

  @Test
  void aChangeWaitsForAPersonAndRunsTheApprovedRequestUnderItsOneAction() throws ConnectorException {
    aws.on("UpdateService", r -> FakeAws.json(200, "{\"service\":{\"serviceName\":\"api\",\"desiredCount\":3}}"));
    ConnectionConfig cfg = Configs.stage();
    ToolCall call = Configs.call("aws_api_change", cfg.name(), """
        {"service":"ecs","operation":"UpdateService","parameters":{"cluster":"stage","service":"api",
         "desiredCount":3,"forceNewDeployment":true}}""");

    Classification c = connector.classify(call, cfg, Configs.policy(Env.STAGE));
    assertThat(c.kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(c.reason()).isEqualTo("ECS UpdateService changes something in AWS");
    assertThat(c.facts().path("iamAction").asString()).isEqualTo("ecs:UpdateService");

    ActionPreview preview = connector.describeAction(call, cfg);
    assertThat(preview.text()).isEqualTo("""
        aws_api_change · aws-stage (AWS · eu-central-1 · 412398765012 acme-stage · stage)
        POST https://ecs.eu-central-1.amazonaws.com/
        Content-Type: application/x-amz-json-1.1
        X-Amz-Target: AmazonEC2ContainerServiceV20141113.UpdateService

        {"cluster":"stage","service":"api","desiredCount":3,"forceNewDeployment":true}""");
    assertThat(preview.fields()).contains(new ActionPreview.Field("IAM action", "ecs:UpdateService"),
        new ActionPreview.Field("Session", "Only ecs:UpdateService, enforced by AWS"));
    assertThat(preview.summary()).isEqualTo("aws_api_change on aws-stage: ecs UpdateService");
    JsonNode payload = preview.payload();
    assertThat(payload.path("tool").asString()).isEqualTo("aws_api_change");

    // Without an approval nothing goes out (defence in depth: the core never runs it unapproved).
    assertThatThrownBy(() -> connector.execute(call, cfg, Configs.secrets(), TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).contains("a person approves it first");
        });
    assertThat(aws.to("UpdateService")).isEmpty();

    // Approved: the stored request runs, not whatever the call says now.
    ToolCall changed = Configs.call("aws_api_change", cfg.name(), """
        {"service":"ecs","operation":"UpdateService","parameters":{"cluster":"stage","service":"api",
         "desiredCount":9}}""");
    connector.classify(changed, cfg, Configs.policy(Env.STAGE));
    ToolResult done = connector.execute(changed, cfg, Configs.secrets(), TestCtx.of().approved(payload));

    assertThat(done.structuredContent().path("access").asString()).isEqualTo("change");
    assertThat(aws.to("UpdateService").getFirst().getBody().utf8())
        .isEqualTo("{\"cluster\":\"stage\",\"service\":\"api\",\"desiredCount\":3,\"forceNewDeployment\":true}");
    JsonNode policy = sessionPolicy();
    assertThat(policy.at("/Statement/0/Action").toString()).isEqualTo("[\"ecs:UpdateService\"]");
    assertThat(policy.at("/Statement/0/Resource").toString()).isEqualTo("[\"*\"]");
  }

  @Test
  void theApprovalShowsTheExactRequestAlsoForAServiceOnlyThePolicyAllows() {
    ConnectionConfig cfg = Configs.stage();
    ToolCall call = Configs.call("aws_api_change", cfg.name(), """
        {"service":"iam","operation":"TagRole","parameters":{"RoleName":"gw-readonly",
         "Tags":[{"Key":"team","Value":"platform"}]}}""");

    Classification c = connector.classify(call, cfg, Configs.policy(Env.STAGE, "{\"allowedServices\":[\"iam\"]}"));
    ActionPreview preview = connector.describeAction(call, cfg);

    assertThat(c.kind()).isEqualTo(Classification.Kind.MUTATION);
    // IAM is global: one host, signed for us-east-1; awsQuery's form is the body.
    assertThat(preview.text()).isEqualTo("""
        aws_api_change · aws-stage (AWS · eu-central-1 · 412398765012 acme-stage · stage)
        POST https://iam.amazonaws.com/
        Content-Type: application/x-www-form-urlencoded; charset=utf-8

        Action=TagRole&Version=2010-05-08&RoleName=gw-readonly&Tags.member.1.Key=team&Tags.member.1.Value=platform""");
    assertThat(preview.payload().path("parameters").toString())
        .isEqualTo("{\"RoleName\":\"gw-readonly\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"platform\"}]}");
  }

  @Test
  void aDeadLetterQueueIsReceivedOnlyWithAnApprovalAndItsContentStaysMasked() throws ConnectorException {
    aws.on("ReceiveMessage", r -> FakeAws.json(200, """
        {"Messages":[{"MessageId":"5fea7756-0ea4-451a-a703-a558b933e274","ReceiptHandle":"AQEBzbVvExample",
          "MD5OfBody":"fafb00f5732ab283681e124bf8747ed1","Body":"{\\"orderId\\":42}",
          "Attributes":{"ApproximateReceiveCount":"4","SentTimestamp":"1762851600000"},
          "MessageAttributes":{"trace":{"StringValue":"abc-123","DataType":"String"}}}]}"""));
    ConnectionConfig cfg = Configs.stage();
    String dlq = "https://sqs.eu-central-1.amazonaws.com/412398765012/stage-orders-dlq";
    String args = "{\"service\":\"sqs\",\"operation\":\"ReceiveMessage\",\"parameters\":{\"QueueUrl\":\"" + dlq
        + "\",\"MaxNumberOfMessages\":10,\"MessageSystemAttributeNames\":[\"All\"],\"MessageAttributeNames\":"
        + "[\"All\"]}}";

    // Never a read: the read tool sends it to the change tool.
    Classification asRead = connector.classify(Configs.call("aws_api_read", cfg.name(), args), cfg,
        Configs.policy(Env.STAGE));
    assertThat(asRead.ruleId()).isEqualTo("stage.cloud.change_tool");
    assertThat(asRead.hint()).contains("aws_api_change");

    ToolCall call = Configs.call("aws_api_change", cfg.name(), args);
    Classification c = connector.classify(call, cfg, Configs.policy(Env.STAGE));
    assertThat(c.kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(c.reason()).startsWith("SQS ReceiveMessage hides the messages it returns").contains("receive count");

    ActionPreview preview = connector.describeAction(call, cfg);
    assertThat(preview.text()).contains("POST https://sqs.eu-central-1.amazonaws.com/", "X-Amz-Target: "
        + "AmazonSQS.ReceiveMessage", "\"QueueUrl\":\"" + dlq + "\"");
    assertThat(preview.impact()).extracting(ActionPreview.Field::label).containsExactly("Effect", "Answer");
    assertThat(preview.impact().getFirst().value()).contains("visibility timeout", "receive count",
        "dead-letter queue");
    assertThat(preview.warnings()).anyMatch(w -> w.startsWith("This isn't a read: sqs ReceiveMessage hides"));

    ToolResult r = connector.execute(call, cfg, Configs.secrets(), TestCtx.of().approved(preview.payload()));

    JsonNode m = r.structuredContent().at("/result/Messages/0");
    assertThat(m.path("Body").asString()).isEqualTo("‹masked›");
    assertThat(m.at("/MessageAttributes/trace/StringValue").asString()).isEqualTo("‹masked›");
    assertThat(m.path("ReceiptHandle").asString()).isEqualTo("AQEBzbVvExample");
    assertThat(m.at("/Attributes/ApproximateReceiveCount").asString()).isEqualTo("4");
    assertThat(r.structuredContent().path("masked").asInt()).isEqualTo(2);
    assertThat(r.structuredContent().path("access").asString()).isEqualTo("change");
    // AWS lets this session receive and nothing else; its Deny no longer names the receive, the rest stays.
    JsonNode policy = sessionPolicy();
    assertThat(policy.at("/Statement/0/Action").toString()).isEqualTo("[\"sqs:ReceiveMessage\"]");
    assertThat(policy.at("/Statement/1/Action").toString()).doesNotContain("sqs:ReceiveMessage")
        .contains("kms:Decrypt");
    assertThat(aws.to("ReceiveMessage")).hasSize(1);
  }

  @Test
  void aQueueOutsideTheMasksOrOfAnotherAccountIsRefused() {
    ConnectionConfig cfg = Configs.stage();
    PolicyView masked = Configs.policy(Env.STAGE, "{\"resources\":[\"arn:aws:sqs:eu-central-1:*:stage-*\"]}");
    String other = "{\"service\":\"sqs\",\"operation\":\"ReceiveMessage\",\"parameters\":{\"QueueUrl\":"
        + "\"https://sqs.eu-central-1.amazonaws.com/412398765012/prod-orders-dlq\"}}";
    String foreign = "{\"service\":\"sqs\",\"operation\":\"ReceiveMessage\",\"parameters\":{\"QueueUrl\":"
        + "\"https://sqs.eu-central-1.amazonaws.com/999999999999/stage-orders-dlq\"}}";

    assertThat(connector.classify(Configs.call("aws_api_change", cfg.name(), other), cfg, masked).ruleId())
        .isEqualTo("stage.cloud.resource_not_allowed");
    assertThat(connector.classify(Configs.call("aws_api_change", cfg.name(), foreign), cfg,
        Configs.policy(Env.STAGE)).ruleId()).isEqualTo("stage.cloud.scope_not_allowed");
    assertThat(aws.requests).isEmpty();
  }

  @Test
  void refusalsNameTheirRuleAndNeverReachAws() {
    ConnectionConfig cfg = Configs.stage();
    PolicyView policy = Configs.policy(Env.STAGE);
    Map<String, String> refused = new LinkedHashMap<>();
    // A change through the read tool and a read through the change tool are each sent to the other.
    refused.put("change:{\"service\":\"ecs\",\"operation\":\"DescribeServices\",\"parameters\":"
        + "{\"services\":[\"api\"]}}", "stage.cloud.read_tool");
    refused.put("{\"service\":\"ecs\",\"operation\":\"UpdateService\",\"parameters\":{\"service\":\"api\"}}",
        "stage.cloud.change_tool");
    refused.put("{\"service\":\"sts\",\"operation\":\"GetCallerIdentity\"}", "stage.cloud.credential_issuing");
    refused.put("{\"service\":\"secretsmanager\",\"operation\":\"GetSecretValue\",\"parameters\":{\"SecretId\":\"x\"}}",
        "stage.cloud.credential_issuing");
    refused.put("{\"service\":\"ecr\",\"operation\":\"get-authorization-token\"}", "stage.cloud.credential_issuing");
    refused.put("{\"service\":\"dynamodb\",\"operation\":\"Scan\",\"parameters\":{\"TableName\":\"t\"}}",
        "stage.cloud.data_plane");
    refused.put("{\"service\":\"s3\",\"operation\":\"ListBuckets\"}", "stage.cloud.unsupported");
    refused.put("change:{\"service\":\"rds\",\"operation\":\"ModifyDBInstance\",\"parameters\":"
        + "{\"DBInstanceIdentifier\":\"db\",\"MasterUserPassword\":\"hunter2hunter2\"}}",
        "stage.cloud.sensitive_input");
    refused.put("{\"service\":\"iam\",\"operation\":\"ListRoles\"}", "stage.cloud.sensitive_type");
    refused.put("{\"service\":\"elbv2\",\"operation\":\"DescribeTargetHealth\",\"parameters\":{\"TargetGroupArn\":"
        + "\"arn:aws:elasticloadbalancing:eu-central-1:999999999999:targetgroup/x/1\"}}",
        "stage.cloud.scope_not_allowed");
    refused.put("{\"service\":\"ecs\",\"operation\":\"ListClusters\",\"region\":\"us-east-1\"}",
        "stage.cloud.scope_not_allowed");
    refused.put("{\"service\":\"nothing\",\"operation\":\"x\"}", "stage.cloud.bad_input");
    refused.put("{\"service\":\"ecs\",\"operation\":\"DescribeNothing\"}", "stage.cloud.bad_input");
    refused.forEach((spec, rule) -> {
      boolean change = spec.startsWith("change:");
      String args = change ? spec.substring("change:".length()) : spec;
      Classification c = connector.classify(Configs.call(change ? "aws_api_change" : "aws_api_read", cfg.name(), args),
          cfg, policy);
      assertThat(c.kind()).as(args).isEqualTo(Classification.Kind.DENY);
      assertThat(c.ruleId()).as(args).isEqualTo(rule);
    });
    assertThat(connector.classify(Configs.call("aws_api_read", cfg.name(), "{\"service\":\"dynamodb\",\"operation\":"
        + "\"Query\",\"parameters\":{\"TableName\":\"t\"}}"), cfg, policy).hint()).contains("dynamodb_query");
    // A service the environment lists isn't refused for its sensitivity.
    assertThat(connector.classify(Configs.call("aws_api_read", cfg.name(), "{\"service\":\"iam\",\"operation\":"
        + "\"ListRoles\"}"), cfg, Configs.policy(Env.STAGE, "{\"allowedServices\":[\"iam\"]}")).kind())
        .isEqualTo(Classification.Kind.READ);
    assertThat(connector.classify(Configs.call("aws_api_read", cfg.name(), "{\"service\":\"ecs\",\"operation\":"
        + "\"ListClusters\"}"), cfg, Configs.policy(Env.STAGE, "{\"allowedServices\":[\"ec2\"]}")).ruleId())
        .isEqualTo("stage.cloud.service_not_allowed");
    assertThat(aws.requests).isEmpty();
  }

  @Test
  void pagesComeBackAsSealedCursorsBoundToTheirRequest() throws ConnectorException {
    aws.on("ListServices", r -> FakeAws.json(200, FakeAws.jsonBody(r).has("nextToken")
        ? "{\"serviceArns\":[\"arn:aws:ecs:eu-central-1:412398765012:service/stage/web\"]}"
        : "{\"serviceArns\":[\"arn:aws:ecs:eu-central-1:412398765012:service/stage/api\"],\"nextToken\":\"t-2\"}"));
    ConnectionConfig cfg = Configs.stage();
    String args = "{\"service\":\"ecs\",\"operation\":\"ListServices\",\"parameters\":{\"cluster\":\"stage\"}";

    ToolResult first = run(cfg, Configs.policy(Env.STAGE), Configs.call("aws_api_read", cfg.name(), args + "}"));
    String cursor = first.structuredContent().path("nextCursor").asString();
    assertThat(cursor).startsWith("ac1.");
    assertThat(first.structuredContent().at("/result/nextToken").isMissingNode()).isTrue();

    ToolResult second = run(cfg, Configs.policy(Env.STAGE), Configs.call("aws_api_read", cfg.name(), args
        + ",\"cursor\":\"" + cursor + "\"}"));
    assertThat(second.structuredContent().at("/result/serviceArns/0").asString()).endsWith("/web");
    assertThat(second.structuredContent().path("nextCursor").isNull()).isTrue();
    assertThat(aws.to("ListServices").getLast().getBody().utf8()).isEqualTo("{\"cluster\":\"stage\","
        + "\"nextToken\":\"t-2\"}");

    // The cursor continues only the request it came from.
    ToolCall other = Configs.call("aws_api_read", cfg.name(), "{\"service\":\"ecs\",\"operation\":\"ListServices\","
        + "\"parameters\":{\"cluster\":\"prod\"},\"cursor\":\"" + cursor + "\"}");
    connector.classify(other, cfg, Configs.policy(Env.STAGE));
    assertThatThrownBy(() -> connector.execute(other, cfg, Configs.secrets(), TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.getMessage()).contains("belongs to another query"));
  }

  @Test
  void queryProtocolsPostAFormAndReadXml() throws ConnectorException {
    aws.on("DescribeTargetHealth", r -> FakeAws.xml("""
        <DescribeTargetHealthResponse xmlns="http://elasticloadbalancing.amazonaws.com/doc/2015-12-01/">
          <DescribeTargetHealthResult><TargetHealthDescriptions><member><Target><Id>i-0abc</Id><Port>8080</Port>
          </Target><TargetHealth><State>unhealthy</State><Reason>Target.FailedHealthChecks</Reason></TargetHealth>
          </member></TargetHealthDescriptions></DescribeTargetHealthResult>
          <ResponseMetadata><RequestId>rq-elb</RequestId></ResponseMetadata></DescribeTargetHealthResponse>"""));
    aws.on("DescribeInstances", r -> FakeAws.xml("""
        <DescribeInstancesResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15/"><requestId>rq-ec2</requestId>
          <reservationSet><item><instancesSet><item><instanceId>i-0abc</instanceId><instanceState><name>running</name>
          </instanceState></item></instancesSet></item></reservationSet></DescribeInstancesResponse>"""));
    ConnectionConfig cfg = Configs.stage();
    String arn = "arn:aws:elasticloadbalancing:eu-central-1:412398765012:targetgroup/api/0123456789abcdef";

    ToolResult health = run(cfg, Configs.policy(Env.STAGE), Configs.call("aws_api_read", cfg.name(),
        "{\"service\":\"elbv2\",\"operation\":\"DescribeTargetHealth\",\"parameters\":{\"TargetGroupArn\":\"" + arn
            + "\"}}"));
    ToolResult instances = run(cfg, Configs.policy(Env.STAGE), Configs.call("aws_api_read", cfg.name(),
        "{\"service\":\"ec2\",\"operation\":\"DescribeInstances\",\"parameters\":{\"Filters\":[{\"Name\":"
            + "\"tag:env\",\"Values\":[\"stage\"]}]}}"));

    assertThat(health.structuredContent().at("/result/TargetHealthDescriptions/0/TargetHealth/Reason").asString())
        .isEqualTo("Target.FailedHealthChecks");
    assertThat(health.structuredContent().path("requestId").asString()).isEqualTo("rq-elb");
    RecordedRequest elb = aws.to("DescribeTargetHealth").getFirst();
    assertThat(form(elb).keySet()).containsExactly("Action", "Version", "TargetGroupArn");
    assertThat(form(elb)).containsEntry("Action", "DescribeTargetHealth").containsEntry("Version", "2015-12-01")
        .containsEntry("TargetGroupArn", arn);
    assertThat(elb.getHeaders().get("Authorization")).contains("/eu-central-1/elasticloadbalancing/aws4_request");
    assertThat(instances.structuredContent().at("/result/Reservations/0/Instances/0/State/Name").asString())
        .isEqualTo("running");
    assertThat(form(aws.to("DescribeInstances").getFirst())).containsEntry("Filter.1.Name", "tag:env")
        .containsEntry("Filter.1.Value.1", "stage");
  }

  @Test
  void restProtocolsBindThePathAndGlobalServicesSignForTheirRegion() throws ConnectorException {
    aws.on("/2015-03-31/functions/api/configuration", r -> FakeAws.json(200, """
        {"FunctionName":"api","Runtime":"java25","State":"Active","Environment":{"Variables":{"DB_PASS":"x"}}}"""));
    aws.on("/2013-04-01/hostedzone", r -> FakeAws.xml("""
        <?xml version="1.0" encoding="UTF-8"?><ListHostedZonesResponse
          xmlns="https://route53.amazonaws.com/doc/2013-04-01/"><HostedZones><HostedZone><Id>/hostedzone/Z1</Id>
          <Name>example.com.</Name><CallerReference>r</CallerReference><Config><PrivateZone>false</PrivateZone>
          </Config><ResourceRecordSetCount>7</ResourceRecordSetCount></HostedZone></HostedZones>
          <IsTruncated>false</IsTruncated><MaxItems>100</MaxItems></ListHostedZonesResponse>"""));
    ConnectionConfig cfg = Configs.stage();

    ToolResult fn = run(cfg, Configs.policy(Env.STAGE), Configs.call("aws_api_read", cfg.name(),
        "{\"service\":\"lambda\",\"operation\":\"GetFunctionConfiguration\",\"parameters\":"
            + "{\"FunctionName\":\"api\"}}"));
    ToolResult zones = run(cfg, Configs.policy(Env.STAGE), Configs.call("aws_api_read", cfg.name(),
        "{\"service\":\"route53\",\"operation\":\"ListHostedZones\"}"));

    assertThat(fn.structuredContent().at("/result/State").asString()).isEqualTo("Active");
    assertThat(fn.structuredContent().at("/result/Environment/Variables").asString()).isEqualTo("‹masked›");
    assertThat(fn.structuredContent().path("masked").asInt()).isEqualTo(1);
    RecordedRequest lambda = aws.to("/2015-03-31/functions/api/configuration").getFirst();
    assertThat(lambda.getMethod()).isEqualTo("GET");
    assertThat(lambda.getHeaders().get("Authorization")).contains("/eu-central-1/lambda/aws4_request");
    assertThat(zones.structuredContent().at("/result/HostedZones/0/ResourceRecordSetCount").asInt()).isEqualTo(7);
    assertThat(zones.structuredContent().at("/result/HostedZones/0/Config/PrivateZone").asBoolean()).isFalse();
    // Route 53 is global: signed for us-east-1 whatever region the call names.
    assertThat(aws.to("/2013-04-01/hostedzone").getFirst().getHeaders().get("Authorization"))
        .contains("/us-east-1/route53/aws4_request");
  }

  @Test
  void resourceMasksNeedTheSessionPolicyAndBecomeItsResources() throws ConnectorException {
    PolicyView masked = Configs.policy(Env.STAGE, """
        {"resources":["arn:aws:ecs:eu-central-1:*:service/stage/*"]}""");
    ConnectionConfig key = keyOnly();
    Classification noPolicy = connector.classify(Configs.call("aws_api_read", key.name(), "{\"service\":\"ecs\","
        + "\"operation\":\"DescribeServices\",\"parameters\":{\"services\":[\"api\"]}}"), key, masked);
    assertThat(noPolicy.ruleId()).isEqualTo("stage.cloud.resources_need_session_policy");

    ConnectionConfig cfg = Configs.stage();
    Classification outside = connector.classify(Configs.call("aws_api_read", cfg.name(), "{\"service\":\"ecs\","
        + "\"operation\":\"DescribeServices\",\"parameters\":{\"services\":[\"arn:aws:ecs:eu-central-1:"
        + "412398765012:service/prod/api\"]}}"), cfg, masked);
    assertThat(outside.ruleId()).isEqualTo("stage.cloud.resource_not_allowed");

    aws.on("DescribeServices", r -> FakeAws.json(200, "{\"services\":[],\"failures\":[]}"));
    run(cfg, masked, Configs.call("aws_api_read", cfg.name(), "{\"service\":\"ecs\",\"operation\":\"DescribeServices\","
        + "\"parameters\":{\"cluster\":\"stage\",\"services\":[\"api\"]}}"));
    assertThat(sessionPolicy().at("/Statement/0/Resource").toString())
        .isEqualTo("[\"arn:aws:ecs:eu-central-1:*:service/stage/*\"]");
  }

  @Test
  void awsErrorsAreToldInWordsAndAChangesLostAnswerIsUnknown() {
    aws.on("DescribeClusters", r -> FakeAws.cloudControlError(400, "AccessDeniedException",
        "User is not authorized to perform ecs:DescribeClusters"));
    aws.on("DeleteService", r -> new MockResponse.Builder().code(503).body("{\"__type\":\"ServerException\"}")
        .build());
    ConnectionConfig cfg = Configs.stage();

    ToolCall denied = Configs.call("aws_api_read", cfg.name(),
        "{\"service\":\"ecs\",\"operation\":\"DescribeClusters\"}");
    connector.classify(denied, cfg, Configs.policy(Env.STAGE));
    assertThatThrownBy(() -> connector.execute(denied, cfg, Configs.secrets(), TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
          assertThat(e.getMessage()).startsWith("AWS denied ecs DescribeClusters: AccessDeniedException: User is "
              + "not authorized").contains("This call's session allows ecs:DescribeClusters and the service's read "
              + "actions.");
        });

    ToolCall delete = Configs.call("aws_api_change", cfg.name(), "{\"service\":\"ecs\",\"operation\":\"DeleteService\","
        + "\"parameters\":{\"service\":\"api\"}}");
    connector.classify(delete, cfg, Configs.policy(Env.STAGE));
    JsonNode payload = connector.describeAction(delete, cfg).payload();
    assertThatThrownBy(() -> connector.execute(delete, cfg, Configs.secrets(), TestCtx.of().approved(payload)))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN));
  }

  @Test
  void describeConnectionListsOperationsAndTheirParameters() throws ConnectorException {
    JsonNode all = connector.describe(Configs.stage(), Configs.secrets(), TestCtx.of(), null).orElseThrow();
    assertThat(all.at("/apiCall/services").asInt()).isGreaterThan(400);

    JsonNode service = connector.describe(Configs.stage(), Configs.secrets(), TestCtx.of(), "ecs").orElseThrow()
        .path("api");
    assertThat(service.path("reads").toString()).contains("\"DescribeServices\"");
    assertThat(service.path("changes").toString()).contains("\"UpdateService\"");

    JsonNode op = connector.describe(Configs.stage(), Configs.secrets(), TestCtx.of(), "ecs describe-services")
        .orElseThrow().path("api");
    assertThat(op.path("operation").asString()).isEqualTo("DescribeServices");
    assertThat(op.path("access").asString()).isEqualTo("read");
    assertThat(op.at("/parameters/services").asString()).isEqualTo("list of string, required");

    JsonNode secret = connector.describe(Configs.stage(), Configs.secrets(), TestCtx.of(),
        "secretsmanager:GetSecretValue").orElseThrow().path("api");
    assertThat(secret.path("access").asString()).isEqualTo("refused");

    // A CloudFormation type is still a type, as aws_describe reads it.
    JsonNode type = connector.describe(Configs.stage(), Configs.secrets(), TestCtx.of(), "AWS::ECS::Service")
        .orElseThrow();
    assertThat(type.at("/type/parentExample").asString()).contains("Cluster");
    assertThat(type.has("api")).isFalse();
  }
}
