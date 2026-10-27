package ai.msrv.gaterook.connectors.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.TestResult;
import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Test connection of AWS (report 09 §5) against the fake STS, Cloud Control and CloudWatch. */
class ConnectionTestTest {

  private FakeAws aws;
  private AwsConnector connector;

  @BeforeEach
  void start() throws Exception {
    aws = new FakeAws();
    connector = new AwsConnector(aws.cloud, Clock.systemUTC());
    aws.on("ListResources", r -> FakeAws.json(200, "{\"ResourceDescriptions\":[]}"));
    aws.on("ListMetrics", r -> FakeAws.cbor(200, TestCtx.JSON.readTree("{\"Metrics\":[{\"Namespace\":\"AWS/EC2\","
        + "\"MetricName\":\"CPUUtilization\",\"Dimensions\":[]}]}")));
    aws.on("DescribeAlarms", r -> FakeAws.cbor(200, TestCtx.JSON.readTree("{\"MetricAlarms\":[]}")));
  }

  @AfterEach
  void stop() {
    connector.close();
    aws.close();
  }

  @Test
  void aDraftLeavesNoClientsOrCredentialsBehind() {
    // The core tests an unsaved form as draft-<uuid>, a new one each time: kept clients (a scheduler and threads
    // each) and an AssumeRole entry in Secrets › Short-lived would pile up with every Test of the form.
    ConnectionConfig draft = new ConnectionConfig("draft-3f9a1c2b7d4e", "aws-new", "aws", Env.STAGE, 0,
        Configs.stage().config(), java.util.Set.of(Configs.SECRET_FIELD), null);

    assertThat(connector.test(draft, Configs.secrets(), TestCtx.of()).ok()).isTrue();

    assertThat(connector.cachedClients()).isZero();
    assertThat(aws.cloud.issued()).isEmpty();
    // A saved connection keeps them for its next call.
    assertThat(connector.test(Configs.stage(), Configs.secrets(), TestCtx.of()).ok()).isTrue();
    assertThat(connector.cachedClients()).isPositive();
    assertThat(aws.cloud.issued()).isNotEmpty();
  }

  @Test
  void aRoleIsAssumedReadOnlyAndEveryStepIsShown() {
    aws.on("ListResources", r -> FakeAws.jsonBody(r).path("TypeName").asString().equals("AWS::Lambda::Function")
        ? FakeAws.cloudControlError(400, "AccessDeniedException", "User is not authorized to perform "
            + "lambda:ListFunctions") : FakeAws.json(200, "{\"ResourceDescriptions\":[]}"));

    TestResult r = connector.test(Configs.stage(), Configs.secrets(), TestCtx.of());

    assertThat(r.ok()).as(String.valueOf(r.error())).isTrue();
    assertThat(r.identity()).isEqualTo(FakeAws.SESSION_ARN);
    assertThat(r.serverVersion()).isEqualTo("Cloud Control API · CloudWatch · STS eu-central-1");
    assertThat(r.permissions().level()).isEqualTo(TestResult.Level.READ);
    assertThat(r.permissions().write()).contains("read-only session policy");
    assertThat(r.checks()).extracting(TestResult.Check::name).containsExactly("access key", "AssumeRole",
        "types in eu-central-1", "types in eu-west-1", "metrics", "alarms");
    assertThat(r.checks().get(0).detail()).isEqualTo(FakeAws.USER_ARN);
    assertThat(r.checks().get(1).detail()).contains("for 15 min, read only by the session policy");
    assertThat(r.checks().get(2).detail()).contains("Reads EC2::Instance, RDS::DBInstance")
        .contains("denied Lambda::Function");
    assertThat(r.hints()).extracting(TestResult.Hint::code).contains("aws.types-denied");
    JsonNode meta = r.metadataUpdate();
    assertThat(meta.path("account").asString()).isEqualTo(FakeAws.ACCOUNT);
    assertThat(meta.path("serverVersion").asString()).isEqualTo("AWS · eu-central-1 · 412398765012 acme-stage");
    assertThat(r.details().path("types").size()).isEqualTo(10);
    // Only reads went out: STS for credentials, Cloud Control lists, CloudWatch reads.
    assertThat(aws.requests).extracting(FakeAws::operation).allMatch(op -> op.equals("AssumeRole")
        || op.equals("GetCallerIdentity") || op.equals("ListResources") || op.equals("ListMetrics")
        || op.equals("DescribeAlarms"));
  }

  @Test
  void rootKeysAreDangerousAndKeysWithoutARoleAreToldAbout() {
    aws.on("GetCallerIdentity", r -> FakeAws.xml("<GetCallerIdentityResponse xmlns=\"https://sts.amazonaws.com/doc/"
        + "2011-06-15/\"><GetCallerIdentityResult><Arn>arn:aws:iam::412398765012:root</Arn><UserId>4123</UserId>"
        + "<Account>412398765012</Account></GetCallerIdentityResult></GetCallerIdentityResponse>"));
    ConnectionConfig stage = Configs.of("aws-keys", Env.STAGE, """
        {"regions":["eu-central-1"],"credentials":{"source":"accessKey","accessKeyId":"AKIAZ7GWSTAGEDEMO000"}}""");

    TestResult r = connector.test(stage, Configs.secrets(), TestCtx.of().env(Env.STAGE));

    assertThat(r.ok()).isTrue();
    assertThat(r.hints()).extracting(TestResult.Hint::code).contains("aws.root-key", "aws.long-lived-key");
    assertThat(r.hints()).filteredOn(h -> h.code().equals("aws.root-key"))
        .allMatch(h -> h.severity() == TestResult.Severity.DANGER);
    assertThat(r.permissions().level()).isEqualTo(TestResult.Level.UNKNOWN);
    assertThat(aws.to("AssumeRole")).isEmpty();
  }

  @Test
  void prodReadsOnlyThroughARoleWithTheSessionPolicy() {
    // §9 "Stricter production rules": no AWS without the session policy on prod, and only an assumed role carries one.
    ConnectionConfig prod = Configs.of("aws-prod", Env.PROD, """
        {"regions":["eu-central-1"],"credentials":{"source":"accessKey","accessKeyId":"AKIAZ7GWSTAGEDEMO000"}}""");

    TestResult r = connector.test(prod, Configs.secrets(), TestCtx.of().env(Env.PROD));

    assertThat(r.ok()).isFalse();
    assertThat(r.error().message()).contains("Prod connections read through a role: add Role ARN");
    assertThat(aws.requests).as("nothing reached AWS").isEmpty();
    ai.msrv.gaterook.connector.api.ToolCall call = Configs.call("aws_describe", prod.name(),
        "{\"type\":\"AWS::EC2::VPC\"}");
    assertThat(connector.classify(call, prod, Configs.policy(Env.PROD)).ruleId()).isEqualTo("prod.cloud.config_invalid");
    // With a role (Configs.prod()) a prod connection reads, read only by the session policy AWS enforces.
    assertThat(assertDoesNotThrow(() -> Settings.parse(Configs.prod())).enforcedByAws()).isTrue();
  }

  @Test
  void anotherAccountThanTheConnectionsFails() {
    ConnectionConfig cfg = Configs.of("aws-x", Env.DEV, """
        {"regions":["eu-central-1"],"accountId":"111111111111","credentials":{"source":"accessKey",
         "accessKeyId":"AKIAZ7GWSTAGEDEMO000"}}""");

    TestResult r = connector.test(cfg, Configs.secrets(), TestCtx.of());

    assertThat(r.ok()).isFalse();
    assertThat(r.error().code()).isEqualTo("aws.wrong-account");
    assertThat(r.error().message()).contains("account 412398765012, not 111111111111");
    assertThat(aws.to("ListResources")).isEmpty();
  }

  @Test
  void aRoleThatRefusesTheGatewaySaysWhatToFix() {
    aws.on("AssumeRole", r -> FakeAws.stsError(403, "AccessDenied", "User: arn:aws:iam::412398765012:user/gw is "
        + "not authorized to perform: sts:AssumeRole"));

    TestResult r = connector.test(Configs.stage(), Configs.secrets(), TestCtx.of());

    assertThat(r.ok()).isFalse();
    assertThat(r.error().code()).isEqualTo("aws.unreachable");
    assertThat(r.error().message()).contains("trust policy");
    assertThat(r.checks()).extracting(TestResult.Check::name).containsExactly("access key", "AssumeRole");
    assertThat(r.checks().get(1).ok()).isFalse();
  }

  @Test
  void healthNeedsWorkingCredentialsNotAlarmPermissions() {
    aws.on("DescribeAlarms", r -> FakeAws.cloudWatchError(400, "AccessDenied", "not authorized"));
    HealthResult ok = connector.health(Configs.stage(), Configs.secrets(), TestCtx.of());
    assertThat(ok.state()).isNotEqualTo(HealthResult.State.UNREACHABLE);

    aws.on("AssumeRole", r -> FakeAws.stsError(403, "AccessDenied", "no"));
    ConnectionConfig fresh = Configs.of("aws-fresh", Env.STAGE, Configs.stage().config().toString());
    HealthResult down = connector.health(fresh, Configs.secrets(), TestCtx.of());
    assertThat(down.state()).isEqualTo(HealthResult.State.UNREACHABLE);
  }

  @Test
  void aKeyWithoutARoleIsCheckedAgainstItsAccountOncePerVersion() throws Exception {
    ConnectionConfig cfg = Configs.of("aws-key", Env.DEV, """
        {"regions":["eu-central-1"],"accountId":"111111111111","credentials":{"source":"accessKey",
         "accessKeyId":"AKIAZ7GWSTAGEDEMO000"}}""");
    var call = Configs.call("aws_describe", cfg.name(), "{\"type\":\"AWS::SQS::Queue\"}");
    connector.classify(call, cfg, Configs.policy(Env.DEV));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> connector.execute(call, cfg, Configs.secrets(),
        TestCtx.of())).hasMessageContaining("are for account 412398765012, not 111111111111");
    assertThat(aws.to("ListResources")).isEmpty();
  }
}
