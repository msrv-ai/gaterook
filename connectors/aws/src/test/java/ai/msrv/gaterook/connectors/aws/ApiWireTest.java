package ai.msrv.gaterook.connectors.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** The wire form of each protocol, built from the model and canonical parameters only. */
class ApiWireTest {

  private final ApiCatalog catalog = ApiCatalog.shared();

  private ApiWire.Request wire(String service, String operation, String params) throws Refusal {
    ApiModel m = catalog.model(catalog.find(service));
    ApiModel.Operation op = m.operation(operation);
    ObjectNode canonical = ApiParams.check(m, op, TestCtx.JSON.readTree(params), "tc_wire").parameters();
    return ApiWire.build(m, op, canonical);
  }

  private static List<String> form(ApiWire.Request r) {
    return Arrays.stream(r.bodyText().split("&")).map(p -> URLDecoder.decode(p, StandardCharsets.UTF_8)).toList();
  }

  @Test
  void awsJsonPostsTheInputWithTheTargetHeader() throws Refusal {
    ApiWire.Request r = wire("ecs", "DescribeServices", "{\"cluster\":\"stage\",\"services\":[\"api\"]}");

    assertThat(r.method()).isEqualTo("POST");
    assertThat(r.target()).isEqualTo("/");
    assertThat(r.headers()).containsEntry("Content-Type", "application/x-amz-json-1.1")
        .containsEntry("X-Amz-Target", "AmazonEC2ContainerServiceV20141113.DescribeServices");
    assertThat(r.bodyText()).isEqualTo("{\"cluster\":\"stage\",\"services\":[\"api\"]}");
  }

  @Test
  void awsJsonWritesTimestampsAsEpochSeconds() throws Refusal {
    ApiWire.Request r = wire("cloudtrail", "LookupEvents", "{\"StartTime\":\"2026-11-11T09:00:00.250Z\","
        + "\"MaxResults\":5}");

    assertThat(r.headers()).containsEntry("X-Amz-Target", "CloudTrail_20131101.LookupEvents");
    assertThat(r.bodyText()).isEqualTo("{\"StartTime\":1794387600.25,\"MaxResults\":5}");
    ApiWire.Request cw = wire("cloudwatch", "DescribeAlarms", "{\"StateValue\":\"ALARM\"}");
    assertThat(cw.headers()).containsEntry("Content-Type", "application/x-amz-json-1.0")
        .containsEntry("X-Amz-Target", "GraniteServiceVersion20100801.DescribeAlarms");
  }

  @Test
  void restJsonBindsLabelsQueryAndBody() throws Refusal {
    ApiWire.Request get = wire("lambda", "GetFunctionConfiguration",
        "{\"FunctionName\":\"orders api\",\"Qualifier\":\"live\"}");
    assertThat(get.method()).isEqualTo("GET");
    assertThat(get.target()).isEqualTo("/2015-03-31/functions/orders%20api/configuration?Qualifier=live");
    assertThat(get.body()).isEmpty();

    ApiWire.Request alias = wire("lambda", "UpdateAlias", "{\"FunctionName\":\"api\",\"Name\":\"live\","
        + "\"FunctionVersion\":\"7\"}");
    assertThat(alias.method()).isEqualTo("PUT");
    assertThat(alias.target()).isEqualTo("/2015-03-31/functions/api/aliases/live");
    assertThat(alias.headers()).containsEntry("Content-Type", "application/json");
    assertThat(alias.bodyText()).isEqualTo("{\"FunctionVersion\":\"7\"}");
  }

  @Test
  void aPathLabelCantWalkToAnotherRoute() {
    assertThatThrownBy(() -> wire("lambda", "GetFunctionConfiguration", "{\"FunctionName\":\"..\"}"))
        .isInstanceOfSatisfying(Refusal.class, r -> assertThat(r.getMessage()).contains("path segment \"..\""));
    // Slashes inside a plain label are escaped, so they stay one segment.
    assertThat(catchTarget("{\"FunctionName\":\"a/../b\"}"))
        .isEqualTo("/2015-03-31/functions/a%2F..%2Fb/configuration");
  }

  private String catchTarget(String params) {
    try {
      return wire("lambda", "GetFunctionConfiguration", params).target();
    } catch (Refusal r) {
      return r.getMessage();
    }
  }

  @Test
  void awsQueryFlattensListsAsMembers() throws Refusal {
    ApiWire.Request r = wire("elbv2", "DescribeTargetGroups", "{\"Names\":[\"api\",\"web\"],\"PageSize\":20}");

    assertThat(r.headers()).containsEntry("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
    assertThat(form(r)).containsExactly("Action=DescribeTargetGroups", "Version=2015-12-01", "Names.member.1=api",
        "Names.member.2=web", "PageSize=20");
    ApiWire.Request empty = wire("elbv2", "DescribeTargetGroups", "{\"Names\":[]}");
    assertThat(form(empty)).contains("Names=");
  }

  @Test
  void ec2QueryUsesItsQueryNamesAndBareIndexes() throws Refusal {
    ApiWire.Request r = wire("ec2", "DescribeInstances", "{\"InstanceIds\":[\"i-1\",\"i-2\"],\"Filters\":"
        + "[{\"Name\":\"instance-state-name\",\"Values\":[\"running\"]}],\"DryRun\":false}");

    assertThat(form(r)).containsExactly("Action=DescribeInstances", "Version=2016-11-15", "InstanceId.1=i-1",
        "InstanceId.2=i-2", "DryRun=false", "Filter.1.Name=instance-state-name", "Filter.1.Value.1=running");
  }

  @Test
  void restXmlWritesTheInputDocument() throws Refusal {
    ApiWire.Request r = wire("route53", "ChangeResourceRecordSets", """
        {"HostedZoneId":"Z0123456789","ChangeBatch":{"Comment":"stage","Changes":[{"Action":"UPSERT",
         "ResourceRecordSet":{"Name":"api.example.com","Type":"A","TTL":60,
          "ResourceRecords":[{"Value":"192.0.2.10"}]}}]}}""");

    assertThat(r.method()).isEqualTo("POST");
    assertThat(r.target()).isEqualTo("/2013-04-01/hostedzone/Z0123456789/rrset");
    assertThat(r.headers()).containsEntry("Content-Type", "application/xml");
    assertThat(r.bodyText()).isEqualTo("<?xml version=\"1.0\" encoding=\"UTF-8\"?><ChangeResourceRecordSetsRequest "
        + "xmlns=\"https://route53.amazonaws.com/doc/2013-04-01/\"><ChangeBatch><Comment>stage</Comment><Changes>"
        + "<Change><Action>UPSERT</Action><ResourceRecordSet><Name>api.example.com</Name><Type>A</Type><TTL>60</TTL>"
        + "<ResourceRecords><ResourceRecord><Value>192.0.2.10</Value></ResourceRecord></ResourceRecords>"
        + "</ResourceRecordSet></Change></Changes></ChangeBatch></ChangeResourceRecordSetsRequest>");

    ApiWire.Request invalidation = wire("cloudfront", "CreateInvalidation", """
        {"DistributionId":"E2EXAMPLE","InvalidationBatch":{"Paths":{"Quantity":1,"Items":["/index.html"]},
         "CallerReference":"gw-1"}}""");
    assertThat(invalidation.target()).isEqualTo("/2020-05-31/distribution/E2EXAMPLE/invalidation");
    assertThat(invalidation.bodyText()).isEqualTo("<?xml version=\"1.0\" encoding=\"UTF-8\"?><InvalidationBatch "
        + "xmlns=\"http://cloudfront.amazonaws.com/doc/2020-05-31/\"><Paths><Quantity>1</Quantity><Items><Path>"
        + "/index.html</Path></Items></Paths><CallerReference>gw-1</CallerReference></InvalidationBatch>");
  }

  @Test
  void aHostPrefixTakesOnlyADnsLabel() throws Refusal {
    ApiModel m = catalog.model(catalog.find("servicediscovery"));
    ApiModel.Operation op = m.operation("DiscoverInstances");
    assertThat(op.hostPrefix()).isEqualTo("data-");
    assertThat(ApiWire.hostPrefix(m, op, TestCtx.JSON.readTree("{}"))).isEqualTo("data-");
    ApiModel.Operation plain = m.operation("ListNamespaces");
    assertThat(ApiWire.hostPrefix(m, plain, TestCtx.JSON.readTree("{}"))).isEmpty();
  }
}
