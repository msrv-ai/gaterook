package com.gaterook.connectors.aws;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Answers read back by protocol under the model's member names, and errors as AWS gives them. */
class ApiResponseTest {

  private final ApiCatalog catalog = ApiCatalog.shared();

  private ApiResponse.Answer read(String service, String operation, int status, Map<String, List<String>> headers,
      String body) throws IOException {
    ApiModel m = catalog.model(catalog.find(service));
    return ApiResponse.read(m, m.operation(operation), status, headers, body.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void awsJsonTimestampsBecomeIsoInstants() throws IOException {
    ApiResponse.Answer a = read("ecs", "DescribeServices", 200, Map.of("x-amzn-RequestId", List.of("rq-1")), """
        {"services":[{"serviceName":"api","desiredCount":2,"createdAt":1762851600.5,
          "deployments":[{"status":"PRIMARY","rolloutState":"COMPLETED","updatedAt":1762851601}]}],"failures":[],
         "newMember":{"kept":true}}""");

    JsonNode s = a.output().path("services").get(0);
    assertThat(s.path("createdAt").asString()).isEqualTo("2025-11-11T09:00:00.500Z");
    assertThat(s.at("/deployments/0/updatedAt").asString()).isEqualTo("2025-11-11T09:00:01Z");
    assertThat(s.path("desiredCount").asInt()).isEqualTo(2);
    // A member the model doesn't know yet passes through.
    assertThat(a.output().at("/newMember/kept").asBoolean()).isTrue();
    assertThat(a.requestId()).isEqualTo("rq-1");
  }

  @Test
  void awsQueryReadsTheResultElement() throws IOException {
    ApiResponse.Answer a = read("elbv2", "DescribeTargetHealth", 200, Map.of(), """
        <DescribeTargetHealthResponse xmlns="http://elasticloadbalancing.amazonaws.com/doc/2015-12-01/">
          <DescribeTargetHealthResult>
            <TargetHealthDescriptions>
              <member>
                <Target><Id>i-0abc</Id><Port>8080</Port></Target>
                <HealthCheckPort>8080</HealthCheckPort>
                <TargetHealth><State>unhealthy</State><Reason>Target.FailedHealthChecks</Reason></TargetHealth>
              </member>
              <member><Target><Id>i-0def</Id><Port>8080</Port></Target><TargetHealth><State>healthy</State>
                </TargetHealth></member>
            </TargetHealthDescriptions>
          </DescribeTargetHealthResult>
          <ResponseMetadata><RequestId>rq-elb</RequestId></ResponseMetadata>
        </DescribeTargetHealthResponse>""");

    JsonNode d = a.output().path("TargetHealthDescriptions");
    assertThat(d).hasSize(2);
    assertThat(d.at("/0/Target/Id").asString()).isEqualTo("i-0abc");
    assertThat(d.at("/0/Target/Port").asInt()).isEqualTo(8080);
    assertThat(d.at("/0/Target/Port").isNumber()).isTrue();
    assertThat(d.at("/0/TargetHealth/State").asString()).isEqualTo("unhealthy");
    assertThat(d.at("/1/TargetHealth/State").asString()).isEqualTo("healthy");
    assertThat(a.requestId()).isEqualTo("rq-elb");
  }

  @Test
  void ec2QueryReadsItsElementNames() throws IOException {
    ApiResponse.Answer a = read("ec2", "DescribeInstances", 200, Map.of(), """
        <DescribeInstancesResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15/">
          <requestId>rq-ec2</requestId>
          <reservationSet><item><reservationId>r-1</reservationId><instancesSet><item>
            <instanceId>i-0abc</instanceId><instanceState><code>16</code><name>running</name></instanceState>
            <launchTime>2026-11-10T08:00:00.000Z</launchTime>
            <tagSet><item><key>env</key><value>stage</value></item></tagSet>
            <ebsOptimized>false</ebsOptimized>
          </item></instancesSet></item></reservationSet>
          <nextToken>page-2</nextToken>
        </DescribeInstancesResponse>""");

    JsonNode i = a.output().at("/Reservations/0/Instances/0");
    assertThat(i.path("InstanceId").asString()).isEqualTo("i-0abc");
    assertThat(i.at("/State/Name").asString()).isEqualTo("running");
    assertThat(i.at("/State/Code").asInt()).isEqualTo(16);
    assertThat(i.path("LaunchTime").asString()).isEqualTo("2026-11-10T08:00:00Z");
    assertThat(i.at("/Tags/0/Key").asString()).isEqualTo("env");
    assertThat(i.path("EbsOptimized").isBoolean()).isTrue();
    assertThat(a.output().path("NextToken").asString()).isEqualTo("page-2");
    assertThat(a.requestId()).isEqualTo("rq-ec2");
  }

  @Test
  void restXmlReadsThePayloadAndHeaders() throws IOException {
    ApiResponse.Answer a = read("cloudfront", "GetDistributionConfig", 200, Map.of("ETag", List.of("E2QWRUHEXAMPLE")),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <DistributionConfig xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
          <CallerReference>ref</CallerReference><Comment>stage</Comment><Enabled>true</Enabled>
          <Aliases><Quantity>1</Quantity><Items><CNAME>www.example.com</CNAME></Items></Aliases>
        </DistributionConfig>""");

    assertThat(a.output().path("ETag").asString()).isEqualTo("E2QWRUHEXAMPLE");
    JsonNode c = a.output().path("DistributionConfig");
    assertThat(c.path("Enabled").asBoolean()).isTrue();
    assertThat(c.at("/Aliases/Items/0").asString()).isEqualTo("www.example.com");
    assertThat(c.at("/Aliases/Quantity").asInt()).isEqualTo(1);
  }

  @Test
  void restJsonReadsHeadersStatusAndRenamedMembers() throws IOException {
    ApiResponse.Answer a = read("apigateway", "GetRestApis", 200, Map.of(), """
        {"item":[{"id":"a1b2c3","name":"orders","createdDate":1762851600}],"position":"next"}""");

    assertThat(a.output().at("/items/0/name").asString()).isEqualTo("orders");
    assertThat(a.output().at("/items/0/createdDate").asString()).isEqualTo("2025-11-11T09:00:00Z");
    assertThat(a.output().path("position").asString()).isEqualTo("next");
  }

  @Test
  void anXmlAnswerCantBringADoctype() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> read("elbv2", "DescribeTargetHealth", 200, Map.of(),
        "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>"))
        .isInstanceOf(IOException.class);
  }

  @Test
  void errorsSayTheCodeAndMessageOfEachProtocol() {
    ApiModel ecs = catalog.model(catalog.find("ecs"));
    ApiResponse.Error json = ApiResponse.error(ecs, 400, Map.of(), ("{\"__type\":\"com.amazonaws.ecs#"
        + "ServiceNotFoundException\",\"message\":\"Service not found.\"}").getBytes(StandardCharsets.UTF_8));
    assertThat(json.code()).isEqualTo("ServiceNotFoundException");
    assertThat(json.message()).isEqualTo("Service not found.");

    ApiModel lambda = catalog.model(catalog.find("lambda"));
    ApiResponse.Error rest = ApiResponse.error(lambda, 404, Map.of("x-amzn-ErrorType",
        List.of("ResourceNotFoundException:http://internal.amazon.com/coral/")), "{\"Message\":\"Function not found\"}"
        .getBytes(StandardCharsets.UTF_8));
    assertThat(rest.code()).isEqualTo("ResourceNotFoundException");
    assertThat(rest.message()).isEqualTo("Function not found");

    ApiModel elb = catalog.model(catalog.find("elbv2"));
    ApiResponse.Error query = ApiResponse.error(elb, 400, Map.of(), ("<ErrorResponse><Error><Type>Sender</Type>"
        + "<Code>TargetGroupNotFound</Code><Message>One or more target groups not found</Message></Error>"
        + "<RequestId>rq-q</RequestId></ErrorResponse>").getBytes(StandardCharsets.UTF_8));
    assertThat(query).isEqualTo(new ApiResponse.Error("TargetGroupNotFound", "One or more target groups not found",
        "rq-q"));

    ApiModel ec2 = catalog.model(catalog.find("ec2"));
    ApiResponse.Error e = ApiResponse.error(ec2, 400, Map.of(), ("<Response><Errors><Error><Code>"
        + "InvalidInstanceID.NotFound</Code><Message>The instance ID 'i-1' does not exist</Message></Error></Errors>"
        + "<RequestID>rq-e</RequestID></Response>").getBytes(StandardCharsets.UTF_8));
    assertThat(e.code()).isEqualTo("InvalidInstanceID.NotFound");
    assertThat(e.requestId()).isEqualTo("rq-e");

    assertThat(ApiResponse.error(ecs, 503, Map.of(), "<html>".getBytes(StandardCharsets.UTF_8)).code())
        .isEqualTo("HTTP503");
  }
}
