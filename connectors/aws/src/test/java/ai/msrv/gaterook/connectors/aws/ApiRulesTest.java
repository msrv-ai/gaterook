package ai.msrv.gaterook.connectors.aws;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/**
 * Classification of every operation from the model's facts: the {@code readonly} trait, then the read verbs, then the
 * overrides; refused first, whatever the trait or the verb say.
 */
class ApiRulesTest {

  private final ApiCatalog catalog = ApiCatalog.shared();

  private ApiModel.Operation op(String service, String operation) {
    ApiModel.Operation op = catalog.model(catalog.find(service)).operation(operation);
    assertThat(op).as(service + " " + operation).isNotNull();
    return op;
  }

  @ParameterizedTest(name = "{0} {1} is {2}")
  @CsvSource({
      // The readonly trait (Lambda and ECS carry it).
      "lambda, GetFunctionConfiguration, READ",
      "ecs, DescribeServices, READ",
      // No trait in the model: the verb.
      "iam, ListRoles, READ",
      "dynamodb, DescribeTable, READ",
      "elbv2, DescribeTargetHealth, READ",
      "cloudformation, DescribeStackEvents, READ",
      "cloudtrail, LookupEvents, READ",
      "codecommit, BatchGetRepositories, READ",
      "ec2, SearchTransitGatewayRoutes, READ",
      "secretsmanager, DescribeSecret, READ",
      "ssm, DescribeParameters, READ",
      "kms, DescribeKey, READ",
      // The override table: reads without a read verb, and a read verb that starts work.
      "iam, SimulatePrincipalPolicy, READ",
      "route53, TestDNSAnswer, READ",
      "cloudformation, ValidateTemplate, READ",
      "elasticbeanstalk, RetrieveEnvironmentInfo, CHANGE",
      // Everything else changes something and is approved first.
      "ecs, UpdateService, CHANGE",
      "lambda, UpdateAlias, CHANGE",
      "autoscaling, SetDesiredCapacity, CHANGE",
      "ec2, RebootInstances, CHANGE",
      "sqs, StartMessageMoveTask, CHANGE",
      // A receive hides the messages and counts it: never a read (§5.2), so a person approves it.
      "sqs, ReceiveMessage, CHANGE",
      "cloudfront, CreateInvalidation, CHANGE",
      "route53, ChangeResourceRecordSets, CHANGE",
      "cloudformation, DetectStackDrift, CHANGE"})
  void accessFollowsTraitVerbAndOverrides(String service, String operation, ApiRules.Access expected) {
    ApiModel.Operation op = op(service, operation);
    String name = catalog.find(service).name();

    assertThat(ApiRules.denial(name, op)).isNull();
    assertThat(ApiRules.access(name, op)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0} {1} is refused: {2}")
  @CsvSource({
      // Credential issuing and secret values.
      "sts, GetCallerIdentity, credential_issuing",
      "sts, AssumeRole, credential_issuing",
      "sso, GetRoleCredentials, credential_issuing",
      "cognito-identity, GetCredentialsForIdentity, credential_issuing",
      "cognito-idp, InitiateAuth, credential_issuing",
      "ecr, GetAuthorizationToken, credential_issuing",
      "ecr-public, GetAuthorizationToken, credential_issuing",
      "codeartifact, GetAuthorizationToken, credential_issuing",
      "redshift, GetClusterCredentials, credential_issuing",
      "redshift, GetClusterCredentialsWithIAM, credential_issuing",
      "redshift-serverless, GetCredentials, credential_issuing",
      "secretsmanager, GetSecretValue, credential_issuing",
      "secretsmanager, BatchGetSecretValue, credential_issuing",
      "ssm, GetParameter, credential_issuing",
      "ssm, GetParameters, credential_issuing",
      "ssm, GetParametersByPath, credential_issuing",
      "ssm, StartSession, credential_issuing",
      "kms, Decrypt, credential_issuing",
      "kms, GenerateDataKey, credential_issuing",
      "ec2, GetPasswordData, credential_issuing",
      "ec2, CreateKeyPair, credential_issuing",
      "iam, CreateAccessKey, credential_issuing",
      "iam, CreateLoginProfile, credential_issuing",
      "lightsail, GetInstanceAccessDetails, credential_issuing",
      "sagemaker, CreatePresignedDomainUrl, credential_issuing",
      "quicksight, GenerateEmbedUrlForAnonymousUser, credential_issuing",
      "emr, GetClusterSessionCredentials, credential_issuing",
      "eks-auth, AssumeRoleForPodIdentity, credential_issuing",
      "deadline, AssumeQueueRoleForUser, credential_issuing",
      // Data plane.
      "dynamodb, GetItem, data_plane",
      "dynamodb, Query, data_plane",
      "dynamodb, PutItem, data_plane",
      "kinesis, GetRecords, data_plane",
      "logs, FilterLogEvents, data_plane",
      "logs, GetLogEvents, data_plane",
      "lambda, Invoke, data_plane",
      "bedrock-runtime, InvokeModel, data_plane",
      "athena, GetQueryResults, data_plane",
      "ecr, GetDownloadUrlForLayer, data_plane",
      "geo-places, Geocode, data_plane",
      "bedrock-agentcore-control, GetGateway, ",
      // What the request layer can't send.
      "lambda, InvokeWithResponseStream, data_plane",
      "transcribe-streaming, StartStreamTranscription, unsupported",
      "timestream-query, Query, data_plane"})
  void refusedWhateverTheVerb(String service, String operation, String rule) {
    ApiCatalog.Entry entry = catalog.find(service);
    if (entry.unsupported() != null) {
      // The whole service is out of reach (its model isn't shipped): the AWS API tools refuse it by the index.
      assertThat(rule).isEqualTo("unsupported");
      return;
    }
    ApiRules.Denial d = ApiRules.denial(entry.name(), op(service, operation));

    if (rule == null) {
      assertThat(d).isNull();
    } else {
      assertThat(d).as(service + " " + operation).isNotNull();
      assertThat(d.rule()).isEqualTo(rule);
    }
  }

  @Test
  void dataPlaneRefusalsPointToTheToolThatHasIt() {
    assertThat(ApiRules.denial("dynamodb", op("dynamodb", "Scan")).hint()).contains("dynamodb_scan");
    assertThat(ApiRules.denial("logs", op("logs", "StartQuery")).hint()).contains("logs_search");
  }

  @Test
  void aWafClassicChangeTokenIsNoCredential() {
    assertThat(ApiRules.denial("waf", op("waf", "GetChangeToken"))).isNull();
  }

  @Test
  void acrossTheCatalogReadsAreTheLargestShareAndRefusalsStaySmall() {
    Map<String, Integer> counts = new java.util.TreeMap<>();
    Map<ApiRules.Access, Integer> access = new EnumMap<>(ApiRules.Access.class);
    int refused = 0;
    int total = 0;
    for (String name : java.util.List.of("ec2", "ecs", "lambda", "rds", "iam", "elbv2", "cloudformation", "eks",
        "autoscaling", "cloudwatch", "route53", "cloudfront", "sqs", "sns", "kms", "secretsmanager", "ssm")) {
      ApiModel m = catalog.model(catalog.find(name));
      for (ApiModel.Operation op : m.operations().values()) {
        total++;
        if (ApiRules.denial(m.name, op) != null) {
          refused++;
          counts.merge(m.name, 1, Integer::sum);
          continue;
        }
        access.merge(ApiRules.access(m.name, op), 1, Integer::sum);
      }
    }
    assertThat(access.get(ApiRules.Access.READ)).isGreaterThan(total / 3);
    assertThat(access.get(ApiRules.Access.CHANGE)).isGreaterThan(total / 3);
    assertThat(refused).as(counts.toString()).isLessThan(total / 20);
  }

  @Test
  void theSessionPolicyNamesOnlyTheCallsActions() {
    ApiModel ecs = catalog.model(catalog.find("ecs"));
    String read = ApiPolicies.of(ecs, ecs.operation("DescribeServices"), ApiRules.Access.READ, java.util.List.of());
    assertThat(read).contains("\"ecs:Describe*\"", "\"ecs:List*\"", "\"ecs:Get*\"", "\"Resource\":[\"*\"]")
        .doesNotContain("ecs:UpdateService").contains("secretsmanager:GetSecretValue");

    String change = ApiPolicies.of(ecs, ecs.operation("UpdateService"), ApiRules.Access.CHANGE,
        java.util.List.of("arn:aws:ecs:eu-central-1:*:service/stage/*", "//run.googleapis.com/projects/x"));
    assertThat(change).contains("{\"Effect\":\"Allow\",\"Action\":[\"ecs:UpdateService\"],\"Resource\":"
        + "[\"arn:aws:ecs:eu-central-1:*:service/stage/*\"]}").doesNotContain("ecs:Describe*", "googleapis");

    ApiModel lambda = catalog.model(catalog.find("lambda"));
    assertThat(ApiPolicies.of(lambda, lambda.operation("GetFunction"), ApiRules.Access.READ, java.util.List.of()))
        .contains("\"lambda:InvokeFunction\"");

    // An approved receive needs its own action, which every other session denies; the rest of the Deny stays.
    ApiModel sqs = catalog.model(catalog.find("sqs"));
    String receive = ApiPolicies.of(sqs, sqs.operation("ReceiveMessage"), ApiRules.Access.CHANGE, java.util.List.of());
    assertThat(receive).startsWith("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":"
        + "[\"sqs:ReceiveMessage\"],\"Resource\":[\"*\"]}").contains("\"secretsmanager:GetSecretValue\"",
        "\"kms:Decrypt\"");
    assertThat(receive.substring(receive.indexOf("\"Deny\""))).doesNotContain("sqs:ReceiveMessage");
    // A read session of the same service still denies the receive.
    assertThat(ApiPolicies.of(sqs, sqs.operation("GetQueueAttributes"), ApiRules.Access.READ, java.util.List.of()))
        .contains("\"sqs:ReceiveMessage\"");
    ApiModel apigw = catalog.model(catalog.find("apigateway"));
    assertThat(ApiPolicies.action(apigw, apigw.operation("UpdateStage"))).isEqualTo("apigateway:PATCH");
    assertThat(ApiPolicies.of(apigw, apigw.operation("GetRestApis"), ApiRules.Access.READ, java.util.List.of()))
        .contains("\"apigateway:GET\"").doesNotContain("apigateway:Describe*");
    // IAM's wildcards are GateRook's: '*' stays, '?' and '$' are literal characters there.
    assertThat(ApiPolicies.resources(java.util.List.of("arn:aws:sqs:*:*:stage-?$")))
        .containsExactly("arn:aws:sqs:*:*:stage-${?}${$}");
  }
}
