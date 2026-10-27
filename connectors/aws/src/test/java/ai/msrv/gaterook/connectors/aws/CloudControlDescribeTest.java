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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code aws_describe} against the fake Cloud Control (JSON 1.0): requests, paging, policy masks, masking, taint.
 */
class CloudControlDescribeTest {

  private FakeAws aws;
  private AwsConnector connector;

  @BeforeEach
  void start() throws Exception {
    aws = new FakeAws();
    connector = new AwsConnector(aws.cloud, Clock.systemUTC());
  }

  @AfterEach
  void stop() {
    connector.close();
    aws.close();
  }

  private ToolResult run(ConnectionConfig cfg, PolicyView policy, String arguments, TestCtx ctx)
      throws ConnectorException {
    ToolCall call = Configs.call("aws_describe", cfg.name(), arguments);
    Classification c = connector.classify(call, cfg, policy);
    assertThat(c.kind()).as(c.reason()).isEqualTo(Classification.Kind.READ);
    return connector.execute(call, cfg, Configs.secrets(), ctx);
  }

  @Test
  void listsOneTypeThroughTheAssumedRoleWithEverySecretTainted() throws Exception {
    aws.on("ListResources", r -> FakeAws.json(200, "{\"TypeName\":\"AWS::RDS::DBInstance\",\"ResourceDescriptions\":["
        + FakeAws.resource("stage-orders", "{\"DBInstanceIdentifier\":\"stage-orders\",\"DBInstanceArn\":\"arn:aws:"
            + "rds:eu-central-1:412398765012:db:stage-orders\",\"DBInstanceStatus\":\"available\",\"Engine\":"
            + "\"postgres\",\"MasterUserPassword\":\"cnry-master-pw\",\"Tags\":[{\"Key\":\"env\",\"Value\":"
            + "\"stage\"}]}") + "]}"));
    TestCtx ctx = TestCtx.of();

    ToolResult r = OutputSchema.describe(run(Configs.stage(), Configs.policy(Env.STAGE),
        "{\"type\":\"AWS::RDS::DBInstance\"}", ctx));

    JsonNode s = r.structuredContent();
    assertThat(s.path("mode").asString()).isEqualTo("list");
    assertThat(s.path("account").asString()).isEqualTo(FakeAws.ACCOUNT);
    assertThat(s.path("region").asString()).isEqualTo("eu-central-1");
    JsonNode item = s.path("resources").get(0);
    assertThat(item.path("identifier").asString()).isEqualTo("stage-orders");
    assertThat(item.path("status").asString()).isEqualTo("available");
    assertThat(item.path("arn").asString()).isEqualTo("arn:aws:rds:eu-central-1:412398765012:db:stage-orders");
    assertThat(item.path("tags").path("env").asString()).isEqualTo("stage");
    assertThat(item.path("properties").path("MasterUserPassword").asString()).isEqualTo("‹masked›");
    assertThat(item.path("properties").has("Tags")).isFalse();
    assertThat(item.path("complete").asBoolean(true)).isFalse();
    assertThat(s.path("masked").asInt()).isEqualTo(1);
    assertThat(r.content().getFirst().text()).contains("stage-orders · available").doesNotContain("cnry-master-pw");

    RecordedRequest list = aws.to("ListResources").getFirst();
    assertThat(list.getHeaders().get("Content-Type")).startsWith("application/x-amz-json-1.0");
    assertThat(list.getHeaders().get("Authorization")).contains("Credential=" + FakeAws.SESSION_KEY_ID + "/");
    assertThat(list.getHeaders().get("X-Amz-Security-Token")).isEqualTo(FakeAws.SESSION_TOKEN);
    JsonNode body = FakeAws.jsonBody(list);
    assertThat(body.path("TypeName").asString()).isEqualTo("AWS::RDS::DBInstance");
    assertThat(body.has("RoleArn")).as("Cloud Control never gets a role of its own").isFalse();
    RecordedRequest assume = aws.to("AssumeRole").getFirst();
    String form = URLDecoder.decode(assume.getBody().utf8(), StandardCharsets.UTF_8);
    assertThat(form).contains("RoleArn=" + Configs.ROLE, "ExternalId=gw-ext-3f9a1c2b7d4e", "DurationSeconds=900",
        "RoleSessionName=gaterook-aws-stage", "\"Effect\":\"Deny\"", "secretsmanager:GetSecretValue");
    assertThat(assume.getHeaders().get("Authorization")).contains("Credential=" + Configs.ACCESS_KEY_ID + "/");
    // The base key is the resolver's (the core taints what it hands out); the session is the connector's to taint.
    assertThat(ctx.taints).contains(FakeAws.SESSION_SECRET, FakeAws.SESSION_TOKEN);
    assertThat(ctx.quotaKeys).contains("aws:412398765012:eu-central-1:cloudcontrol@300");
  }

  @Test
  void pagesWithACursorThatLosesNothingWhenAPageHoldsMoreThanTheLimit() throws Exception {
    aws.on("ListResources", r -> {
      String token = FakeAws.jsonBody(r).path("NextToken").asString("");
      if (token.isEmpty()) {
        return FakeAws.json(200, "{\"ResourceDescriptions\":[" + queue("stage-a") + "," + queue("stage-b") + ","
            + queue("stage-c") + "],\"NextToken\":\"page-2\"}");
      }
      return FakeAws.json(200, "{\"ResourceDescriptions\":[" + queue("stage-d") + "]}");
    });
    ConnectionConfig cfg = Configs.stage();
    TestCtx ctx = TestCtx.of();

    ToolResult first = OutputSchema.describe(run(cfg, Configs.policy(Env.STAGE),
        "{\"type\":\"AWS::SQS::Queue\",\"limit\":2}", ctx));
    assertThat(first.structuredContent().path("resources")).extracting(n -> n.path("identifier").asString())
        .containsExactly(url("stage-a"), url("stage-b"));
    String cursor = first.structuredContent().path("nextCursor").asString();
    assertThat(cursor).startsWith("ac1.");
    assertThat(first.content().getFirst().text()).contains("cursor " + cursor);

    ToolResult second = OutputSchema.describe(run(cfg, Configs.policy(Env.STAGE),
        "{\"type\":\"AWS::SQS::Queue\",\"limit\":2,\"cursor\":\"" + cursor + "\"}", ctx.next()));
    assertThat(second.structuredContent().path("resources")).extracting(n -> n.path("identifier").asString())
        .containsExactly(url("stage-c"), url("stage-d"));
    assertThat(second.structuredContent().path("nextCursor").isNull()).isTrue();
    // The second call read the first page again (same token, same size) and skipped what was shown.
    List<RecordedRequest> lists = aws.to("ListResources");
    assertThat(lists).hasSize(3);
    assertThat(FakeAws.jsonBody(lists.get(1)).has("NextToken")).isFalse();
    assertThat(FakeAws.jsonBody(lists.get(1)).path("MaxResults").asInt())
        .isEqualTo(FakeAws.jsonBody(lists.get(0)).path("MaxResults").asInt());
    assertThat(FakeAws.jsonBody(lists.get(2)).path("NextToken").asString()).isEqualTo("page-2");

    // A cursor of another query or a forged one is refused before AWS is asked.
    int before = aws.requests.size();
    assertThatThrownBy(() -> run(cfg, Configs.policy(Env.STAGE), "{\"type\":\"AWS::EC2::Instance\",\"cursor\":\""
        + cursor + "\"}", ctx.next())).isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.getMessage()).contains("another query"));
    assertThatThrownBy(() -> run(cfg, Configs.policy(Env.STAGE), "{\"type\":\"AWS::SQS::Queue\",\"cursor\":\""
        + cursor.substring(0, cursor.length() - 3) + "abc\"}", ctx.next()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.getMessage()).contains("invalid"));
    assertThat(aws.requests).hasSize(before);
  }

  @Test
  void anAnswerOverTheSizeLimitKeepsWholeResourcesAndGoesOnAfterTheLastOne() throws Exception {
    StringBuilder items = new StringBuilder();
    for (int i = 0; i < 30; i++) {
      items.append(i == 0 ? "" : ",").append(FakeAws.resource("stage-" + i, "{\"DBInstanceIdentifier\":\"stage-"
          + i + "\",\"Description\":\"" + "x".repeat(400) + "\"}"));
    }
    aws.on("ListResources", r -> FakeAws.json(200, "{\"ResourceDescriptions\":[" + items + "]}"));
    ai.msrv.gaterook.connector.api.Limits small = new ai.msrv.gaterook.connector.api.Limits(1_000, 1_000, 8 * 1024,
        8 * 1024, 8 * 1024, 8 * 1024, 8 * 1024, java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(30),
        java.time.Duration.ofSeconds(10), java.time.Duration.ofHours(168));
    ConnectionConfig cfg = Configs.stage();
    TestCtx ctx = TestCtx.of().with(small);

    ToolResult r = OutputSchema.describe(run(cfg, Configs.policy(Env.STAGE),
        "{\"type\":\"AWS::RDS::DBInstance\",\"limit\":30}", ctx));

    JsonNode s = r.structuredContent();
    int shown = s.path("count").asInt();
    assertThat(shown).isBetween(1, 29);
    assertThat(s.path("truncatedReason").asString()).isEqualTo("size_limit");
    long size = Call.utf8(r.content().getFirst().text()) + Call.utf8(s.toString());
    assertThat(size).isLessThanOrEqualTo(8 * 1024 * 3 / 4);
    ToolResult next = OutputSchema.describe(run(cfg, Configs.policy(Env.STAGE),
        "{\"type\":\"AWS::RDS::DBInstance\",\"limit\":30,\"cursor\":\"" + s.path("nextCursor").asString()
            + "\"}", ctx.next()));
    assertThat(next.structuredContent().path("resources").get(0).path("identifier").asString())
        .isEqualTo("stage-" + shown);
  }

  @Test
  void oneResourceLargerThanTheLimitIsCutNotSkipped() throws Exception {
    aws.on("ListResources", r -> FakeAws.json(200, "{\"ResourceDescriptions\":["
        + FakeAws.resource("stage-huge", "{\"DBInstanceIdentifier\":\"stage-huge\",\"DBInstanceStatus\":\"available\","
            + "\"Description\":\"" + "x".repeat(20_000) + "\"}")
        + "," + FakeAws.resource("stage-next", "{\"DBInstanceIdentifier\":\"stage-next\"}") + "]}"));
    ai.msrv.gaterook.connector.api.Limits small = new ai.msrv.gaterook.connector.api.Limits(1_000, 1_000, 8 * 1024,
        8 * 1024, 8 * 1024, 8 * 1024, 8 * 1024, java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(30),
        java.time.Duration.ofSeconds(10), java.time.Duration.ofHours(168));
    TestCtx ctx = TestCtx.of().with(small);

    ToolResult r = OutputSchema.describe(run(Configs.stage(), Configs.policy(Env.STAGE),
        "{\"type\":\"AWS::RDS::DBInstance\",\"limit\":1}", ctx));

    JsonNode s = r.structuredContent();
    assertThat(s.path("resources")).extracting(n -> n.path("identifier").asString()).containsExactly("stage-huge");
    assertThat(s.at("/resources/0/status").asString()).isEqualTo("available");
    assertThat(s.at("/resources/0/complete").asBoolean(true)).isFalse();
    assertThat(s.path("truncatedReason").asString()).isEqualTo("size_limit");
    assertThat(Call.utf8(r.content().getFirst().text()) + Call.utf8(s.toString())).isLessThanOrEqualTo(8 * 1024);
    // The cursor goes on after it, not past something never shown.
    ToolResult next = OutputSchema.describe(run(Configs.stage(), Configs.policy(Env.STAGE),
        "{\"type\":\"AWS::RDS::DBInstance\",\"limit\":1,\"cursor\":\"" + s.path("nextCursor").asString() + "\"}",
        ctx.next()));
    assertThat(next.structuredContent().path("resources")).extracting(n -> n.path("identifier").asString())
        .containsExactly("stage-next");
  }

  @Test
  void resourceMasksHideWhatThePolicyDoesntShowAndWhereFiltersTheRest() throws Exception {
    aws.on("ListResources", r -> FakeAws.json(200, "{\"ResourceDescriptions\":[" + db("stage-orders", "available")
        + "," + db("prod-orders", "available") + "," + db("stage-billing", "stopped") + "]}"));
    ToolResult r = OutputSchema.describe(run(Configs.stage(), Configs.kitStage(),
        "{\"type\":\"AWS::RDS::DBInstance\",\"where\":{\"DBInstanceStatus\":\"avail*\"}}", TestCtx.of()));

    JsonNode s = r.structuredContent();
    assertThat(s.path("resources")).extracting(n -> n.path("identifier").asString()).containsExactly("stage-orders");
    assertThat(s.path("hiddenByPolicy").asInt()).isEqualTo(1);
    assertThat(r.content().getFirst().text()).contains("1 resource hidden by the stage policy").doesNotContain(
        "prod-orders");
  }

  @Test
  void whereSeesOnlyWhatTheAnswerShowsSoItCantSpellOutAMaskedValue() throws Exception {
    // What the connector's own Redactor leaves to the core's masker: a key the core calls secret (apiKey), a tag
    // whose key names a secret, a value of the call's taint. A glob on them would keep a resource only when the
    // guessed prefix is right.
    aws.on("ListResources", r -> FakeAws.json(200, "{\"ResourceDescriptions\":["
        + FakeAws.resource("stage-a", "{\"DBInstanceIdentifier\":\"stage-a\",\"Variables\":{\"apiKey\":\"kAAA1\"},"
            + "\"Tags\":[{\"Key\":\"db_password\",\"Value\":\"hunter2\"}],\"Description\":\"cnry-where-taint\"}")
        + "," + FakeAws.resource("stage-b", "{\"DBInstanceIdentifier\":\"stage-b\",\"Variables\":{\"apiKey\":"
            + "\"zBBB2\"},\"Engine\":\"postgres\"}") + "]}"));
    TestCtx ctx = TestCtx.of();
    ctx.taint("cnry-where-taint");

    for (String where : List.of("{\"Variables.apiKey\":\"k*\"}", "{\"tags.db_password\":\"h*\"}",
        "{\"Description\":\"cnry*\"}")) {
      JsonNode s = run(Configs.stage(), Configs.policy(Env.STAGE), "{\"type\":\"AWS::RDS::DBInstance\",\"where\":"
          + where + "}", ctx).structuredContent();
      assertThat(s.path("count").asInt()).as(where).isZero();
    }
    // What the answer shows in plain filters as before.
    JsonNode plain = run(Configs.stage(), Configs.policy(Env.STAGE), "{\"type\":\"AWS::RDS::DBInstance\","
        + "\"where\":{\"Engine\":\"post*\"}}", ctx).structuredContent();
    assertThat(plain.path("resources")).extracting(n -> n.path("identifier").asString()).containsExactly("stage-b");
  }

  @Test
  void aHugeValueIsNeverGlobbed() {
    // A glob is O(pattern × text): 256 characters over a 1 MB state machine definition, 100 resources a page.
    tools.jackson.databind.node.ObjectNode view = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    tools.jackson.databind.node.ObjectNode props = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode()
        .put("Small", "a".repeat(CloudControlDescribe.MAX_MATCHED_VALUE))
        .put("DefinitionString", "a".repeat(CloudControlDescribe.MAX_MATCHED_VALUE + 1));

    assertThat(CloudControlDescribe.matches(view, props, java.util.Map.of("Small", "a*"))).isTrue();
    assertThat(CloudControlDescribe.matches(view, props, java.util.Map.of("DefinitionString", "a*"))).isFalse();
  }

  @Test
  void anEc2InstanceGetsItsArnFromTheCatalogAndItsUserDataMasked() throws Exception {
    aws.on("GetResource", r -> FakeAws.json(200, "{\"TypeName\":\"AWS::EC2::Instance\",\"ResourceDescription\":"
        + FakeAws.resource("i-0abc", "{\"InstanceId\":\"i-0abc\",\"State\":{\"Code\":16,\"Name\":\"running\"},"
            + "\"UserData\":\"IyEvYmluL2Jhc2gKZXhwb3J0IFRPS0VOPWNucnk=\",\"SsmAssociations\":[{\"DocumentName\":"
            + "\"x\",\"AssociationParameters\":[{\"Key\":\"token\",\"Value\":[\"cnry-ssm\"]}]}]}") + "}"));

    ToolResult r = OutputSchema.describe(run(Configs.stage(), Configs.policy(Env.STAGE),
        "{\"type\":\"AWS::EC2::Instance\",\"identifier\":\"i-0abc\"}", TestCtx.of()));

    JsonNode res = r.structuredContent().path("resource");
    assertThat(r.structuredContent().path("mode").asString()).isEqualTo("get");
    assertThat(res.path("arn").asString()).isEqualTo("arn:aws:ec2:eu-central-1:412398765012:instance/i-0abc");
    assertThat(res.path("status").asString()).isEqualTo("running");
    assertThat(res.path("complete").asBoolean()).isTrue();
    assertThat(res.at("/properties/UserData").asString()).isEqualTo("‹masked›");
    assertThat(res.at("/properties/SsmAssociations/0/AssociationParameters/0/Value").asString())
        .isEqualTo("‹masked›");
    assertThat(r.structuredContent().path("masked").asInt()).isEqualTo(2);
    assertThat(r.toString()).doesNotContain("cnry-ssm").doesNotContain("IyEvYmluL2Jhc2g");
    JsonNode body = FakeAws.jsonBody(aws.to("GetResource").getFirst());
    assertThat(body.path("Identifier").asString()).isEqualTo("i-0abc");
    assertThat(body.path("TypeName").asString()).isEqualTo("AWS::EC2::Instance");
  }

  @Test
  void aResourceReadOutsideTheMasksIsRefusedWithoutItsProperties() throws Exception {
    aws.on("GetResource", r -> FakeAws.json(200, "{\"ResourceDescription\":" + FakeAws.resource("orders",
        "{\"Arn\":\"arn:aws:sqs:eu-central-1:412398765012:orders\",\"QueueName\":\"orders\"}") + "}"));
    ConnectionConfig cfg = Configs.stage();
    ToolCall call = Configs.call("aws_describe", cfg.name(), "{\"type\":\"AWS::SQS::Queue\",\"identifier\":"
        + "\"https://sqs.eu-central-1.amazonaws.com/412398765012/orders\"}");
    assertThat(connector.classify(call, cfg, Configs.kitStage()).kind()).isEqualTo(Classification.Kind.READ);

    assertThatThrownBy(() -> connector.execute(call, cfg, Configs.secrets(), TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).contains("arn:aws:sqs:eu-central-1:412398765012:orders is outside");
        });
  }

  @Test
  void cloudControlErrorsAreToldInWords() throws Exception {
    aws.on("ListResources", r -> FakeAws.cloudControlError(400, "InvalidRequestException", "Missing or invalid "
        + "ResourceModel property in AWS::ECS::Service list handler request input. Required property: [Cluster]"));
    assertThatThrownBy(() -> run(Configs.stage(), Configs.policy(Env.STAGE), "{\"type\":\"AWS::ECS::Service\"}",
        TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
          assertThat(e.getMessage()).contains("Cloud Control refused the request").contains("Cluster");
        });

    aws.on("ListResources", r -> FakeAws.cloudControlError(400, "ThrottlingException", "Rate exceeded"));
    assertThatThrownBy(() -> run(Configs.stage(), Configs.policy(Env.STAGE), "{\"type\":\"AWS::SQS::Queue\"}",
        TestCtx.of().timeout(java.time.Duration.ofSeconds(30)))).isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.getMessage()).contains("AWS is throttling ListResources"));
  }

  private static String queue(String name) {
    return FakeAws.resource(url(name), "{\"QueueUrl\":\"" + url(name) + "\",\"Arn\":\"arn:aws:sqs:eu-central-1:"
        + "412398765012:" + name + "\"}");
  }

  private static String url(String name) {
    return "https://sqs.eu-central-1.amazonaws.com/412398765012/" + name;
  }

  private static String db(String id, String status) {
    return FakeAws.resource(id, "{\"DBInstanceIdentifier\":\"" + id + "\",\"DBInstanceArn\":\"arn:aws:rds:"
        + "eu-central-1:412398765012:db:" + id + "\",\"DBInstanceStatus\":\"" + status + "\"}");
  }
}
