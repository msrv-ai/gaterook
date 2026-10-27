package ai.msrv.gaterook.connectors.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connectors.cloudauth.AwsSessionPolicy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The generated read-only session policy (report 09 §6): allows, explicit denies, STS's 2,048 characters. */
class SessionPolicyFactoryTest {

  @Test
  void theDefaultPolicyReadsTheCommonServicesAndFitsSts() {
    String json = SessionPolicyFactory.readOnly(Services.DEFAULT_SESSION);

    assertThat(json.length()).isLessThanOrEqualTo(AwsSessionPolicy.MAX_CHARACTERS);
    JsonNode policy = TestCtx.JSON.readTree(json);
    assertThat(policy.path("Version").asString()).isEqualTo("2012-10-17");
    JsonNode allow = policy.path("Statement").get(0);
    assertThat(allow.path("Effect").asString()).isEqualTo("Allow");
    List<String> actions = new ArrayList<>();
    allow.path("Action").forEach(a -> actions.add(a.asString()));
    assertThat(actions).contains("ec2:Describe*", "rds:List*", "lambda:Get*", "cloudformation:ListResources",
        "cloudformation:GetResource", "cloudwatch:Describe*");
    assertThat(actions).allMatch(a -> a.matches("[a-z0-9-]+:(Describe|List|Get)\\*")
        || a.equals("cloudformation:ListResources") || a.equals("cloudformation:GetResource"));
    assertThat(actions).noneMatch(a -> a.startsWith("iam:") || a.startsWith("kms:") || a.startsWith("sts:"));
    JsonNode deny = policy.path("Statement").get(1);
    assertThat(deny.path("Effect").asString()).isEqualTo("Deny");
    assertThat(deny.path("Action").toString()).contains("secretsmanager:GetSecretValue", "ssm:GetParameter*",
        "kms:Decrypt", "s3:GetObject*", "dynamodb:GetItem", "sqs:ReceiveMessage", "lambda:InvokeFunction",
        "logs:GetLog*", "ec2:GetPasswordData", "ecr:GetAuthorizationToken");
    assertThat(deny.path("Resource").toString()).isEqualTo("[\"*\"]");
  }

  @Test
  void theKitsStageListOpensOnlyItsServicesPlusCloudControlAndCloudWatch() {
    String json = SessionPolicyFactory.readOnly(List.of("ec2", "ecs", "rds", "sqs"));
    JsonNode allow = TestCtx.JSON.readTree(json).path("Statement").get(0).path("Action");

    assertThat(allow.toString()).contains("\"sqs:Get*\"", "\"cloudwatch:GetMetricData\"", "\"cloudwatch:ListMetrics\"",
        "\"cloudwatch:DescribeAlarms\"").doesNotContain("lambda", "s3:");
    assertThat(allow).hasSize(4 * 3 + 2 + 3);
  }

  @Test
  void tooManyServicesDontFit() {
    List<String> many = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      many.add("service" + i);
    }
    assertThatThrownBy(() -> SessionPolicyFactory.readOnly(many)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Allow fewer services");
  }
}
