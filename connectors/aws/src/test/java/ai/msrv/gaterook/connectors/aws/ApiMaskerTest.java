package ai.msrv.gaterook.connectors.aws;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** What leaves an AWS API call: values of sensitive shapes, then what the models leave unmarked, then the Redactor. */
class ApiMaskerTest {

  private final ApiCatalog catalog = ApiCatalog.shared();

  private int mask(String service, String operation, JsonNode result) {
    ApiModel m = catalog.model(catalog.find(service));
    return ApiMasker.mask(m, m.operation(operation), result);
  }

  @Test
  void valuesOfSensitiveShapesAreMasked() {
    // Lambda marks its whole map of environment variables sensitive (names too), ECS its list of them.
    JsonNode fn = TestCtx.JSON.readTree("""
        {"FunctionName":"api","Environment":{"Variables":{"DB_HOST":"db.internal","feature_flag":"on"}},
         "Runtime":"java25"}""");

    int masked = mask("lambda", "GetFunctionConfiguration", fn);

    assertThat(fn.at("/Environment/Variables").asString()).isEqualTo("‹masked›");
    assertThat(fn.path("Runtime").asString()).isEqualTo("java25");
    assertThat(masked).isEqualTo(1);
    JsonNode td = TestCtx.JSON.readTree("""
        {"taskDefinition":{"family":"api","containerDefinitions":[{"name":"app","environment":[
          {"name":"DB_URL","value":"postgres://app:pw@db/app"}],"secrets":[{"name":"API_KEY",
          "valueFrom":"arn:aws:secretsmanager:eu-central-1:412398765012:secret:api"}]}]}}""");
    assertThat(mask("ecs", "DescribeTaskDefinition", td)).isEqualTo(1);
    assertThat(td.at("/taskDefinition/containerDefinitions/0/environment").asString()).isEqualTo("‹masked›");
    // A reference to a secret is an ARN, not the secret: it stays.
    assertThat(td.at("/taskDefinition/containerDefinitions/0/secrets/0/valueFrom").asString()).startsWith("arn:");

    // Cognito marks the client secret sensitive, and the client id as well: the model decides, not the key name.
    JsonNode client = TestCtx.JSON.readTree("""
        {"UserPoolClient":{"ClientId":"3n4b5urk1ft4fl3mg5e62d9ado","ClientSecret":"1q2w3e4r5t6y7u8i9o0p",
         "ClientName":"web"}}""");
    assertThat(mask("cognito-idp", "DescribeUserPoolClient", client)).isEqualTo(2);
    assertThat(client.at("/UserPoolClient/ClientSecret").asString()).isEqualTo("‹masked›");
    assertThat(client.at("/UserPoolClient/ClientId").asString()).isEqualTo("‹masked›");
    assertThat(client.at("/UserPoolClient/ClientName").asString()).isEqualTo("web");
  }

  @Test
  void whatTheModelsLeaveUnmarkedIsMaskedToo() {
    // Lambda's code location is a presigned URL: whoever has it downloads the package.
    JsonNode fn = TestCtx.JSON.readTree("""
        {"Configuration":{"FunctionName":"api"},"Code":{"RepositoryType":"S3","Location":
         "https://awslambda-eu-cent-1-tasks.s3.eu-central-1.amazonaws.com/snapshots/412398765012/api-1a2b?\
        X-Amz-Security-Token=IQoJb3&X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=abc123"}}""");
    assertThat(mask("lambda", "GetFunction", fn)).isEqualTo(1);
    assertThat(fn.at("/Code/Location").asString()).isEqualTo("‹masked›");
    assertThat(fn.at("/Code/RepositoryType").asString()).isEqualTo("S3");

    // EC2's DescribeInstanceAttribute returns user data as {Value}: the whole of it goes.
    JsonNode attr = TestCtx.JSON.readTree("{\"InstanceId\":\"i-0abc\",\"UserData\":{\"Value\":\"IyEvYmluL2Jhc2g=\"}}");
    assertThat(mask("ec2", "DescribeInstanceAttribute", attr)).isEqualTo(1);
    assertThat(attr.path("UserData").asString()).isEqualTo("‹masked›");

    // API Gateway key values and AppSync API keys (their id is the key).
    JsonNode keys = TestCtx.JSON.readTree("{\"items\":[{\"id\":\"k1\",\"name\":\"partner\",\"value\":\"AbCd1234\"}]}");
    assertThat(mask("apigateway", "GetApiKeys", keys)).isEqualTo(1);
    assertThat(keys.at("/items/0/value").asString()).isEqualTo("‹masked›");
    assertThat(keys.at("/items/0/name").asString()).isEqualTo("partner");
    JsonNode appsync = TestCtx.JSON.readTree("{\"apiKeys\":[{\"id\":\"da2-abcdefghijklmnop\",\"expires\":1}]}");
    assertThat(mask("appsync", "ListApiKeys", appsync)).isEqualTo(1);
    assertThat(appsync.at("/apiKeys/0/id").asString()).isEqualTo("‹masked›");
  }

  @Test
  void receivedMessagesKeepTheirIdsAndCountsButNotTheirContent() {
    JsonNode received = TestCtx.JSON.readTree("""
        {"Messages":[{"MessageId":"5fea7756-0ea4-451a-a703-a558b933e274","ReceiptHandle":"AQEBzbVv…",
          "MD5OfBody":"fafb00f5732ab283681e124bf8747ed1","Body":"{\\"orderId\\":42,\\"email\\":\\"jane@example.com\\"}",
          "Attributes":{"ApproximateReceiveCount":"4","SentTimestamp":"1762851600000"},
          "MessageAttributes":{"trace":{"StringValue":"abc-123","DataType":"String"},
            "tags":{"StringListValues":["a","b"],"DataType":"String"}}}]}""");

    assertThat(mask("sqs", "ReceiveMessage", received)).isEqualTo(3);

    JsonNode m = received.at("/Messages/0");
    assertThat(m.path("Body").asString()).isEqualTo("‹masked›");
    assertThat(m.at("/MessageAttributes/trace/StringValue").asString()).isEqualTo("‹masked›");
    assertThat(m.at("/MessageAttributes/tags/StringListValues").asString()).isEqualTo("‹masked›");
    assertThat(m.at("/MessageAttributes/trace/DataType").asString()).isEqualTo("String");
    assertThat(m.path("MessageId").asString()).startsWith("5fea7756");
    assertThat(m.path("ReceiptHandle").asString()).startsWith("AQEB");
    assertThat(m.at("/Attributes/ApproximateReceiveCount").asString()).isEqualTo("4");
    assertThat(m.path("MD5OfBody").asString()).hasSize(32);
  }

  @Test
  void theRedactorRunsOnTopAndCursorsStayReadable() {
    // CodeBuild doesn't mark its environment variables: the Redactor knows their shape and masks each value.
    JsonNode projects = TestCtx.JSON.readTree("""
        {"projects":[{"name":"api","environment":{"type":"LINUX_CONTAINER","environmentVariables":[
          {"name":"NPM_TOKEN","value":"npm_abc123","type":"PLAINTEXT"},
          {"name":"DB","value":"/stage/db","type":"PARAMETER_STORE"}]}}]}""");
    assertThat(mask("codebuild", "BatchGetProjects", projects)).isEqualTo(2);
    assertThat(projects.at("/projects/0/environment/environmentVariables/0/value").asString()).isEqualTo("‹masked›");
    assertThat(projects.at("/projects/0/environment/environmentVariables/0/name").asString()).isEqualTo("NPM_TOKEN");
    assertThat(projects.at("/projects/0/environment/type").asString()).isEqualTo("LINUX_CONTAINER");

    JsonNode page = TestCtx.JSON.readTree("{\"Roles\":[{\"RoleName\":\"gw\"}],\"Marker\":\"AAEAAa3k\","
        + "\"NextToken\":\"t-2\",\"DbPassword\":\"x\"}");
    assertThat(mask("iam", "ListRoles", page)).isEqualTo(1);
    assertThat(page.path("Marker").asString()).isEqualTo("AAEAAa3k");
    assertThat(page.path("NextToken").asString()).isEqualTo("t-2");
    assertThat(page.path("DbPassword").asString()).isEqualTo("‹masked›");
  }
}
