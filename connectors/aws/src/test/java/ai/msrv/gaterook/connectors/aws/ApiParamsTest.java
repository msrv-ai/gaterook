package ai.msrv.gaterook.connectors.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Parameters checked against the input shape and written in the one form the approval shows and the call runs. */
class ApiParamsTest {

  private final ApiCatalog catalog = ApiCatalog.shared();

  private ApiParams.Checked check(String service, String operation, String json) throws Refusal {
    ApiModel m = catalog.model(catalog.find(service));
    return ApiParams.check(m, m.operation(operation), TestCtx.JSON.readTree(json), "tc_params");
  }

  private Refusal refusal(String service, String operation, String json) {
    try {
      check(service, operation, json);
    } catch (Refusal r) {
      return r;
    }
    throw new AssertionError("not refused: " + service + " " + operation + " " + json);
  }

  @Test
  void membersComeInModelOrderUnderTheirModelNames() throws Refusal {
    ApiParams.Checked c = check("ecs", "DescribeServices",
        "{\"Services\":[\"api\"],\"cluster\":\"stage\",\"include\":[\"TAGS\"]}");

    assertThat(c.parameters().toString())
        .isEqualTo("{\"cluster\":\"stage\",\"services\":[\"api\"],\"include\":[\"TAGS\"]}");
  }

  @Test
  void unknownMembersAndWrongTypesAreRefusedWithWhatWouldDo() {
    Refusal unknown = refusal("ecs", "DescribeServices", "{\"cluster\":\"stage\",\"services\":[\"api\"],"
        + "\"serviceName\":\"api\"}");
    assertThat(unknown.rule).isEqualTo("bad_input");
    assertThat(unknown.getMessage()).contains("parameters has no member serviceName").contains("cluster, services");

    assertThat(refusal("ecs", "DescribeServices", "{\"services\":\"api\"}").getMessage())
        .isEqualTo("parameters.services is a list (a JSON array).");
    assertThat(refusal("ecs", "UpdateService", "{\"service\":\"api\",\"desiredCount\":\"2\"}").getMessage())
        .isEqualTo("parameters.desiredCount is a whole number.");
    assertThat(refusal("ecs", "UpdateService", "{\"service\":\"api\",\"forceNewDeployment\":\"yes\"}").getMessage())
        .isEqualTo("parameters.forceNewDeployment is true or false.");
    assertThat(refusal("ecs", "DescribeServices", "[1]").getMessage()).contains("is an object");
  }

  @Test
  void aRequiredTopLevelMemberMustBeThere() {
    assertThat(refusal("ecs", "UpdateService", "{\"cluster\":\"stage\"}").getMessage())
        .isEqualTo("UpdateService needs parameters.service.");
  }

  @Test
  void timestampsBecomeIsoInstants() throws Refusal {
    JsonNode p = check("cloudtrail", "LookupEvents",
        "{\"StartTime\":\"2026-11-11T10:00:00+01:00\",\"EndTime\":1762858800}").parameters();

    assertThat(p.path("StartTime").asString()).isEqualTo("2026-11-11T09:00:00Z");
    assertThat(p.path("EndTime").asString()).isEqualTo("2025-11-11T11:00:00Z");
    assertThat(refusal("cloudtrail", "LookupEvents", "{\"StartTime\":\"yesterday\"}").getMessage())
        .contains("is a time");
  }

  @Test
  void idempotencyTokensAreFilledTheSameForEveryCheckOfOneCall() throws Refusal {
    JsonNode first = check("ec2", "RunInstances", "{\"MinCount\":1,\"MaxCount\":1}").parameters();
    JsonNode again = check("ec2", "RunInstances", "{\"MinCount\":1,\"MaxCount\":1}").parameters();

    assertThat(first.path("ClientToken").asString()).matches("[0-9a-f-]{36}");
    assertThat(again).isEqualTo(first);
    assertThat(ApiParams.token("tc_other")).isNotEqualTo(first.path("ClientToken").asString());
    // An agent's own token is kept.
    assertThat(check("ec2", "RunInstances", "{\"MinCount\":1,\"MaxCount\":1,\"ClientToken\":\"mine\"}")
        .parameters().path("ClientToken").asString()).isEqualTo("mine");
  }

  @Test
  void secretValuesAreNeverSent() {
    // RDS's master password, a secret string, Lambda's environment values: the model marks each sensitive.
    Refusal rds = refusal("rds", "ModifyDBInstance",
        "{\"DBInstanceIdentifier\":\"stage-orders\",\"MasterUserPassword\":\"hunter2hunter2\"}");
    assertThat(rds.rule).isEqualTo("sensitive_input");
    assertThat(rds.getMessage()).startsWith("parameters.MasterUserPassword is a secret value");
    assertThat(refusal("secretsmanager", "PutSecretValue", "{\"SecretId\":\"x\",\"SecretString\":\"s3cr3t\"}").rule)
        .isEqualTo("sensitive_input");
    assertThat(refusal("lambda", "UpdateFunctionConfiguration",
        "{\"FunctionName\":\"api\",\"Environment\":{\"Variables\":{\"DB_PASS\":\"x\"}}}").rule)
        .isEqualTo("sensitive_input");
  }

  @Test
  void arnsAreRecordedForTheAccountAndMaskChecks() throws Refusal {
    ApiParams.Checked c = check("elbv2", "DescribeTargetHealth", "{\"TargetGroupArn\":\"arn:aws:elasticloadbalancing:"
        + "eu-central-1:412398765012:targetgroup/api/0123456789abcdef\"}");

    assertThat(c.arns()).containsExactly("arn:aws:elasticloadbalancing:eu-central-1:412398765012:targetgroup/api/"
        + "0123456789abcdef");
  }

  @Test
  void invisibleCharactersAreRefusedButLineBreaksPass() throws Refusal {
    assertThat(refusal("ecs", "DescribeServices", "{\"services\":[\"api\\u202e\"]}").getMessage())
        .contains("U+202E");
    assertThat(check("cloudformation", "ValidateTemplate", "{\"TemplateBody\":\"Resources:\\n  Q:\\n    Type: x\"}")
        .parameters().path("TemplateBody").asString()).contains("\n");
  }

  @Test
  void aUnionTakesExactlyOneMember() {
    // CloudWatch Logs' StartLiveTail is refused anyway; Bedrock Agent's data source configuration is a union.
    Refusal r = refusal("ec2", "DescribeInstances", "{\"MaxResults\":5,\"DryRun\":\"no\"}");
    assertThat(r.getMessage()).isEqualTo("parameters.DryRun is true or false.");
  }

  @Test
  void anOperationWithoutInputTakesNoParameters() throws Refusal {
    assertThat(check("ecs", "DescribeCapacityProviders", "{}").parameters().isEmpty()).isTrue();
    assertThat(refusal("iam", "GetAccountSummary", "{\"x\":1}").getMessage())
        .isEqualTo("GetAccountSummary takes no parameters.");
  }
}
