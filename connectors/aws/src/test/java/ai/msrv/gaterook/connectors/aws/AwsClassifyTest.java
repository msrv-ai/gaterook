package ai.msrv.gaterook.connectors.aws;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@code classify} without I/O (report 09 §3.5): every row of the refusal table, as DENY {@code <env>.cloud.<rule>};
 * never MUTATION. The kit's stage policy allows ec2, ecs, rds, sqs and cloudwatch in eu-central-1 with three ARN masks.
 */
class AwsClassifyTest {

  private static final Instant NOW = Instant.parse("2026-10-22T12:31:17Z");
  private final CloudCredentials cloud = new CloudCredentials(CloudEndpoints.PRODUCTION, Clock.systemUTC());
  private final AwsConnector connector = new AwsConnector(cloud, Clock.fixed(NOW, ZoneOffset.UTC));
  private final List<Classification> seen = new ArrayList<>();

  @AfterEach
  void neverAMutation() {
    assertThat(seen).noneMatch(c -> c.kind() == Classification.Kind.MUTATION);
    connector.close();
    cloud.close();
  }

  private Classification classify(String tool, String args, PolicyView policy) {
    return classify(Configs.stage(), tool, args, policy);
  }

  private Classification classify(ConnectionConfig cfg, String tool, String args, PolicyView policy) {
    Classification c = connector.classify(Configs.call(tool, cfg.name(), args), cfg, policy);
    seen.add(c);
    return c;
  }

  @ParameterizedTest(name = "{0} {1} → {2}")
  @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
      aws_describe | {"type":"AWS::RDS::DBInstance","region":"us-east-1"}                     | stage.cloud.scope_not_allowed
      aws_describe | {"type":"AWS::RDS::DBInstance","region":"eu-west-1"}                     | stage.cloud.scope_not_allowed
      aws_describe | {"type":"AWS::Lambda::Function"}                                         | stage.cloud.service_not_allowed
      aws_describe | {"type":"AWS::SecretsManager::Secret"}                                   | stage.cloud.sensitive_type
      aws_describe | {"type":"AWS::SSM::Parameter"}                                           | stage.cloud.sensitive_type
      aws_describe | {"type":"AWS::IAM::Role"}                                                | stage.cloud.sensitive_type
      aws_describe | {"type":"Acme::Billing::Invoice"}                                        | stage.cloud.third_party_type
      aws_describe | {"type":"AWS::EC2::Instance","identifier":"i-0abc"}                      | stage.cloud.resource_not_allowed
      aws_describe | {"type":"AWS::RDS::DBInstance","identifier":"prod-orders"}               | stage.cloud.resource_not_allowed
      aws_describe | {"type":"AWS::EC2::Instance","identifier":"i-0abc\\u202e"}               | stage.cloud.bad_input
      aws_describe | {"type":"AWS::RDS::DBInstance","identifier":"stage-x","parent":{"A":"b"}}| stage.cloud.bad_input
      aws_metrics  | {"metrics":[{"namespace":"AWS/Lambda","metric":"Errors"}]}              | stage.cloud.service_not_allowed
      aws_metrics  | {"metrics":[{"namespace":"MyApp","metric":"Orders"}]}                   | stage.cloud.service_not_allowed
      aws_metrics  | {"metrics":[{"namespace":"AWS/RDS","metric":"CPUUtilization"}],"expressions":[{"id":"e1","expression":"LAMBDA(\\"f\\", m1)"}]} | stage.cloud.metric_expression
      aws_metrics  | {"metrics":[{"namespace":"AWS/RDS","metric":"CPUUtilization"}],"expressions":[{"id":"e1","expression":"SEARCH('{AWS/EC2} CPUUtilization', 'Average', 300)"}]} | stage.cloud.metric_expression
      aws_metrics  | {"metrics":[{"namespace":"AWS/RDS","metric":"CPUUtilization"}],"expressions":[{"id":"e1","expression":"SELECT AVG(CPUUtilization) FROM SCHEMA(\\"AWS/EC2\\", InstanceId)"}]} | stage.cloud.metric_expression
      aws_metrics  | {"metrics":[{"namespace":"AWS/RDS","metric":"CPUUtilization"}],"expressions":[{"id":"e1","expression":"m1 + m9"}]} | stage.cloud.metric_expression
      aws_metrics  | {"metrics":[{"namespace":"AWS/RDS","metric":"CPUUtilization"}],"from":"-1h","to":"-2h"} | stage.cloud.range
      aws_metrics  | {"metrics":[{"namespace":"AWS/RDS","metric":"CPUUtilization"}],"from":"-15d"} | stage.cloud.range
      aws_metrics  | {"metrics":[{"namespace":"AWS/RDS","metric":"CPUUtilization"}],"from":"1761135600"} | stage.cloud.range
      aws_metrics  | {"action":"query"}                                                       | stage.cloud.bad_input
      """)
  void theKitsStagePolicyDenies(String tool, String args, String rule) {
    Classification c = classify(tool, args.replace("\\u202e", "‮"), Configs.kitStage());

    assertThat(c.kind()).as(c.reason()).isEqualTo(Classification.Kind.DENY);
    assertThat(c.ruleId()).isEqualTo(rule);
  }

  @Test
  void whatThePolicyAllowsIsRead() {
    PolicyView p = Configs.kitStage();
    assertThat(classify("aws_describe", "{\"type\":\"AWS::RDS::DBInstance\"}", p).kind())
        .isEqualTo(Classification.Kind.READ);
    assertThat(classify("aws_describe", "{\"type\":\"AWS::RDS::DBInstance\",\"identifier\":\"stage-orders\"}", p)
        .kind()).isEqualTo(Classification.Kind.READ);
    assertThat(classify("aws_describe", "{\"type\":\"AWS::ECS::Service\",\"parent\":{\"Cluster\":\"stage\"}}", p)
        .kind()).isEqualTo(Classification.Kind.READ);
    assertThat(classify("aws_metrics", "{\"metrics\":[{\"namespace\":\"AWS/EBS\",\"metric\":\"VolumeReadOps\"}],"
        + "\"expressions\":[{\"id\":\"e1\",\"expression\":\"FILL(m1, REPEAT) * 2\"},{\"id\":\"e2\",\"expression\":"
        + "\"SUM(METRICS(\\\"m\\\")) / PERIOD(m1)\"}],\"from\":\"-14d\"}", p).kind())
        .isEqualTo(Classification.Kind.READ);
    assertThat(classify("aws_metrics", "{\"action\":\"alarms\"}", p).kind()).isEqualTo(Classification.Kind.READ);
    assertThat(classify("aws_metrics", "{\"action\":\"list_metrics\"}", p).kind()).isEqualTo(Classification.Kind.READ);
  }

  @Test
  void anEnvironmentThatListsNoServicesStillHidesTheSensitiveOnesAndSecretValuesAlways() {
    PolicyView open = Configs.policy(Env.STAGE);
    assertThat(classify("aws_describe", "{\"type\":\"AWS::Lambda::Function\"}", open).kind())
        .isEqualTo(Classification.Kind.READ);
    assertThat(classify("aws_metrics", "{\"metrics\":[{\"namespace\":\"MyApp\",\"metric\":\"Orders\"}]}", open)
        .kind()).isEqualTo(Classification.Kind.READ);
    assertThat(classify("aws_describe", "{\"type\":\"AWS::KMS::Key\"}", open).ruleId())
        .isEqualTo("stage.cloud.sensitive_type");

    PolicyView listed = Configs.policy(Env.STAGE, "{\"allowedServices\":[\"iam\",\"secretsmanager\",\"ssm\"]}");
    assertThat(classify("aws_describe", "{\"type\":\"AWS::IAM::Role\"}", listed).kind())
        .isEqualTo(Classification.Kind.READ);
    Classification secret = classify("aws_describe", "{\"type\":\"AWS::SecretsManager::Secret\"}", listed);
    assertThat(secret.ruleId()).isEqualTo("stage.cloud.sensitive_type");
    assertThat(secret.reason()).contains("returns secret values");
    assertThat(classify("aws_describe", "{\"type\":\"AWS::SSM::Parameter\"}", listed).ruleId())
        .isEqualTo("stage.cloud.sensitive_type");
    // In any case the agent writes them.
    assertThat(classify("aws_describe", "{\"type\":\"AWS::SSM::parameter\"}", listed).ruleId())
        .isEqualTo("stage.cloud.sensitive_type");
    assertThat(classify("aws_describe", "{\"type\":\"AWS::secretsManager::Secret\"}", listed).ruleId())
        .isEqualTo("stage.cloud.sensitive_type");
  }

  @Test
  void synonymsNameTheSameService() {
    PolicyView p = Configs.policy(Env.STAGE, "{\"allowedServices\":[\"elbv2\",\"EBS\"]}");
    assertThat(classify("aws_describe", "{\"type\":\"AWS::ElasticLoadBalancingV2::LoadBalancer\"}", p).kind())
        .isEqualTo(Classification.Kind.READ);
    assertThat(classify("aws_metrics", "{\"metrics\":[{\"namespace\":\"AWS/ApplicationELB\",\"metric\":"
        + "\"HTTPCode_Target_5XX_Count\"}]}", p).kind()).isEqualTo(Classification.Kind.READ);
    assertThat(classify("aws_describe", "{\"type\":\"AWS::EC2::Volume\"}", p).kind())
        .isEqualTo(Classification.Kind.READ);
  }

  @Test
  void theDenialSaysWhatIsAllowed() {
    Classification c = classify("aws_describe", "{\"type\":\"AWS::Lambda::Function\"}", Configs.kitStage());
    assertThat(c.reason()).isEqualTo("AWS::Lambda::Function belongs to lambda, which isn't allowed: ec2, ecs, rds, "
        + "sqs, cloudwatch are allowed on stage.");
    Classification lambda = classify("aws_metrics", "{\"metrics\":[{\"namespace\":\"AWS/RDS\",\"metric\":\"CPU\"}],"
        + "\"expressions\":[{\"id\":\"e1\",\"expression\":\"LAMBDA(\\\"f\\\", m1)\"}]}", Configs.kitStage());
    assertThat(lambda.reason()).contains("LAMBDA reads or runs something outside this call's queries");
    assertThat(lambda.hint()).contains("LAMBDA, SEARCH and SELECT aren't sent");
  }

  @Test
  void prodHasAShorterRangeAndFewerItems() throws Exception {
    PolicyView prod = Configs.policy(Env.PROD);
    Classification week = classify(Configs.prod(), "aws_metrics", "{\"metrics\":[{\"namespace\":\"AWS/RDS\","
        + "\"metric\":\"CPUUtilization\"}],\"from\":\"-8d\"}", prod);
    assertThat(week.ruleId()).isEqualTo("prod.cloud.range");
    assertThat(week.reason()).contains("at most 7 days");

    Settings s = Settings.parse(Configs.prod());
    DescribeRequest r = DescribeRequest.of(TestCtx.JSON.readTree("{\"type\":\"AWS::RDS::DBInstance\",\"limit\":"
        + "80}"), "aws-prod", s, AwsPolicy.defaults(Env.PROD));
    assertThat(r.limit()).isEqualTo(50);
    assertThat(r.notes()).containsExactly("limit lowered to 50 by the prod policy.");
    DescribeRequest dflt = DescribeRequest.of(TestCtx.JSON.readTree("{\"type\":\"AWS::RDS::DBInstance\"}"),
        "aws-prod", s, AwsPolicy.defaults(Env.PROD));
    assertThat(dflt.limit()).isEqualTo(10);
  }

  @Test
  void thePeriodFollowsTheRangeAndCloudWatchsRetention() throws Exception {
    Settings s = Settings.parse(Configs.stage());
    AwsPolicy p = AwsPolicy.of(Configs.policy(Env.STAGE, "{\"maxRangeDays\":100}"), Env.STAGE);
    MetricsRequest hour = metrics(s, p, "{\"from\":\"-1h\"}");
    assertThat(hour.period()).isEqualTo(60);
    MetricsRequest day = metrics(s, p, "{\"from\":\"-1d\"}");
    assertThat(day.period()).isEqualTo(300);
    MetricsRequest old = metrics(s, p, "{\"from\":\"-20d\",\"to\":\"-19d\",\"period\":60}");
    assertThat(old.period()).isEqualTo(300);
    assertThat(old.notes()).anyMatch(n -> n.startsWith("period raised to 300 s by retention"));
    MetricsRequest older = metrics(s, p, "{\"from\":\"-70d\",\"to\":\"-69d\"}");
    assertThat(older.period()).isEqualTo(3_600);
    MetricsRequest many = metrics(s, AwsPolicy.defaults(Env.STAGE), "{\"from\":\"-14d\",\"period\":300}");
    assertThat(many.notes()).anyMatch(n -> n.contains("2000 data points per call"));
    assertThat(hour.from().getEpochSecond() % 60).isZero();
  }

  @Test
  void brokenSettingsDenyEveryCall() {
    ConnectionConfig mismatched = Configs.of("aws-x", Env.STAGE, """
        {"regions":["eu-central-1"],"accountId":"111111111111","credentials":{"source":"accessKey",
         "accessKeyId":"AKIAZ7GWSTAGEDEMO000","roleArn":"arn:aws:iam::412398765012:role/gw-readonly"}}""");
    Classification c = classify(mismatched, "aws_describe", "{\"type\":\"AWS::EC2::VPC\"}", Configs.policy(Env.STAGE));
    assertThat(c.ruleId()).isEqualTo("stage.cloud.config_invalid");
    assertThat(c.reason()).contains("account 412398765012, but the connection says 111111111111");

    ConnectionConfig noPolicyOnProd = Configs.of("aws-p", Env.PROD, """
        {"regions":["eu-central-1"],"credentials":{"source":"accessKey","accessKeyId":"AKIAZ7GWSTAGEDEMO000",
         "roleArn":"arn:aws:iam::412398765012:role/gw-readonly","sessionPolicy":"none"}}""");
    assertThat(classify(noPolicyOnProd, "aws_describe", "{\"type\":\"AWS::EC2::VPC\"}", Configs.policy(Env.PROD))
        .ruleId()).isEqualTo("prod.cloud.config_invalid");
  }

  @Test
  void aSessionPolicyTooLargeForStsIsDeniedBeforeAnyRequest() {
    StringBuilder services = new StringBuilder();
    for (int i = 0; i < 60; i++) {
      services.append(i == 0 ? "" : ",").append("\"service").append(i).append('"');
    }
    services.append(",\"ec2\"");
    Classification c = classify("aws_describe", "{\"type\":\"AWS::EC2::VPC\"}",
        Configs.policy(Env.STAGE, "{\"allowedServices\":[" + services + "]}"));
    assertThat(c.ruleId()).isEqualTo("stage.cloud.session_policy_too_large");
    assertThat(c.reason()).contains("STS accepts 2048");
  }

  private static MetricsRequest metrics(Settings s, AwsPolicy p, String extra) throws Refusal {
    String args = "{\"metrics\":[{\"namespace\":\"AWS/RDS\",\"metric\":\"CPUUtilization\"}]," + extra.substring(1);
    return MetricsRequest.of(TestCtx.JSON.readTree(args), "aws-stage", s, p, NOW);
  }
}
