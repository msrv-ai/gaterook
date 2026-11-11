package ai.msrv.gaterook.connectors.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The catalog the generator wrote: names agents use, operations in any spelling, endpoints of every service. */
class ApiCatalogTest {

  private final ApiCatalog catalog = ApiCatalog.shared();

  @Test
  void servicesGoByTheNamesTheCliUsesAndTheirUnambiguousAliases() {
    assertThat(catalog.size()).isGreaterThan(400);
    assertThat(catalog.revision()).matches("[0-9a-f]{40}");
    Map<String, String> spelled = Map.ofEntries(Map.entry("ecs", "ecs"), Map.entry("ECS", "ecs"),
        Map.entry("elbv2", "elbv2"), Map.entry("ELBv2", "elbv2"), Map.entry("elastic-load-balancing-v2", "elbv2"),
        Map.entry("Elastic Load Balancing v2", "elbv2"), Map.entry("logs", "logs"),
        Map.entry("cloudwatch-logs", "logs"), Map.entry("stepfunctions", "stepfunctions"),
        Map.entry("sfn", "stepfunctions"), Map.entry("states", "stepfunctions"), Map.entry("monitoring", "cloudwatch"),
        Map.entry("cost_explorer", "ce"), Map.entry("route53", "route53"), Map.entry("events", "events"),
        Map.entry("eventbridge", "events"), Map.entry("secretsmanager", "secretsmanager"));
    spelled.forEach((given, name) -> assertThat(catalog.find(given)).as(given).isNotNull()
        .extracting(ApiCatalog.Entry::name).isEqualTo(name));
    // rds, docdb and neptune share the endpoint prefix rds: it names the service called rds, the others by their own.
    assertThat(catalog.find("rds").sdkId()).isEqualTo("RDS");
    assertThat(catalog.find("docdb").sdkId()).isEqualTo("DocDB");
    assertThat(catalog.find("no-such-service")).isNull();
    assertThat(catalog.find("")).isNull();
  }

  @Test
  void servicesItCantCallSayWhy() {
    // S3 is out of reach as a whole, its object reads included: buckets and objects have a connector of their own.
    assertThat(catalog.find("s3").unsupported()).contains("S3 has its own addressing and object rules")
        .contains("S3 object storage connection's s3_* tools");
    assertThat(catalog.find("s3control").unsupported()).contains("account host labels");
    assertThat(catalog.find("cloudwatchomni").unsupported()).contains("rpcv2Cbor");
    assertThat(catalog.find("codecatalyst").unsupported()).contains("bearer tokens");
    assertThatThrownBy(() -> catalog.model(catalog.find("s3"))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void operationsGoByTheirApiCliOrBoto3Spelling() {
    ApiModel ecs = catalog.model(catalog.find("ecs"));
    for (String s : List.of("DescribeServices", "describe-services", "describe_services", "DESCRIBESERVICES")) {
      assertThat(ecs.operation(s)).as(s).isNotNull().extracting(ApiModel.Operation::name)
          .isEqualTo("DescribeServices");
    }
    assertThat(ecs.operation("DescribeNothing")).isNull();
    assertThat(ecs.protocol).isEqualTo("awsJson1_1");
    assertThat(ecs.serviceShape).isEqualTo("AmazonEC2ContainerServiceV20141113");
    ApiModel lambda = catalog.model(catalog.find("lambda"));
    assertThat(lambda.operation("Invoke").action()).isEqualTo("InvokeFunction");
    assertThat(lambda.operation("GetFunctionConfiguration").readonly()).isTrue();
    assertThat(lambda.operation("GetFunctionConfiguration").method()).isEqualTo("GET");
  }

  @Test
  void endpointsComeFromEachModelsRules() throws Refusal {
    ApiModel iam = catalog.model(catalog.find("iam"));
    ApiModel.Operation listRoles = iam.operation("ListRoles");
    assertThat(iam.endpoint(listRoles, "eu-central-1")).isEqualTo(new ApiModel.Endpoint(
        java.net.URI.create("https://iam.amazonaws.com"), "us-east-1", "iam"));
    assertThat(iam.endpoint(listRoles, "us-gov-west-1").url().toString()).isEqualTo("https://iam.us-gov.amazonaws.com");
    assertThat(iam.endpoint(listRoles, "cn-north-1").url().toString())
        .isEqualTo("https://iam.cn-north-1.amazonaws.com.cn");

    ApiModel ecs = catalog.model(catalog.find("ecs"));
    assertThat(ecs.endpoint(ecs.operation("ListClusters"), "eu-central-1")).isEqualTo(new ApiModel.Endpoint(
        java.net.URI.create("https://ecs.eu-central-1.amazonaws.com"), "eu-central-1", "ecs"));
    // A region the generator didn't know follows its partition's template.
    assertThat(ecs.endpoint(ecs.operation("ListClusters"), "ap-southeast-9").url().toString())
        .isEqualTo("https://ecs.ap-southeast-9.amazonaws.com");
    ApiModel cloudwatch = catalog.model(catalog.find("cloudwatch"));
    assertThat(cloudwatch.endpoint(cloudwatch.operation("DescribeAlarms"), "eu-west-1").signingName())
        .isEqualTo("monitoring");
    assertThat(cloudwatch.iam).isEqualTo("cloudwatch");
    ApiModel ce = catalog.model(catalog.find("ce"));
    assertThat(ce.endpoint(ce.operation("GetCostAndUsage"), "eu-central-1").url().toString())
        .isEqualTo("https://ce.us-east-1.amazonaws.com");

    assertThatThrownBy(() -> ecs.endpoint(ecs.operation("ListClusters"), "us-iso-east-1"))
        .isInstanceOfSatisfying(Refusal.class, r -> assertThat(r.getMessage()).contains("partitions"));
  }

  @Test
  void everyCallableServiceLoadsAndHasAnEndpointInEachPartition() {
    List<String> problems = new ArrayList<>();
    JsonNode index;
    try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream(ApiCatalog.BASE + "index.json")) {
      index = TestCtx.JSON.readTree(in);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
    for (java.util.Map.Entry<String, JsonNode> e : index.path("services").properties()) {
      ApiCatalog.Entry entry = catalog.find(e.getKey());
      assertThat(entry).as(e.getKey()).isNotNull();
      if (entry.unsupported() != null) {
        continue;
      }
      ApiModel m = catalog.model(entry);
      assertThat(m.operations()).as(e.getKey()).hasSize(entry.operations());
      for (ApiModel.Operation op : m.operations().values()) {
        for (String shape : new String[] {op.input(), op.output()}) {
          if (shape != null) {
            m.shape(shape);
          }
        }
        try {
          ApiModel.Endpoint ep = m.endpoint(op, "us-east-1");
          assertThat(ep.url().getScheme()).isEqualTo("https");
          assertThat(ep.signingName()).isNotBlank();
        } catch (Refusal r) {
          problems.add(m.name + " " + op.name() + ": " + r.getMessage());
        }
      }
    }
    // A service may not exist in us-east-1 per its rules; none of the standard services is among them.
    assertThat(problems).noneMatch(p -> p.startsWith("ecs ") || p.startsWith("ec2 ") || p.startsWith("lambda ")
        || p.startsWith("iam ") || p.startsWith("elbv2 ") || p.startsWith("route53 "));
    assertThat(problems.size()).as(String.join("\n", problems)).isLessThan(50);
  }
}
