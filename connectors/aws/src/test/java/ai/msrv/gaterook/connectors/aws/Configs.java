package ai.msrv.gaterook.connectors.aws;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.ToolCall;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.node.ObjectNode;

/** Connections, calls and policies of the tests, written as the forms, agents and the core write them. */
final class Configs {

  static final String ACCESS_KEY_ID = "AKIAZ7GWSTAGEDEMO000";
  static final String ROLE = "arn:aws:iam::412398765012:role/gw-readonly";
  static final String SECRET_FIELD = "/credentials/secretAccessKey";

  private static final AtomicInteger CALLS = new AtomicInteger();

  private Configs() {}

  static ConnectionConfig of(String name, Env env, String json) {
    return new ConnectionConfig("c-" + name, name, "aws", env, 1, TestCtx.JSON.readTree(json),
        Set.of(SECRET_FIELD), null);
  }

  /** {@code aws-stage} of the kit: an access key assuming {@link #ROLE} with an ExternalId. */
  static ConnectionConfig stage() {
    return of("aws-stage", Env.STAGE, """
        {"regions":["eu-central-1","eu-west-1"],"accountAlias":"acme-stage","credentials":{"source":"accessKey",
         "accessKeyId":"%s","roleArn":"%s","externalId":"gw-ext-3f9a1c2b7d4e"}}""".formatted(ACCESS_KEY_ID, ROLE));
  }

  static ConnectionConfig prod() {
    return of("aws-prod", Env.PROD, """
        {"regions":["eu-central-1"],"credentials":{"source":"accessKey","accessKeyId":"%s",
         "roleArn":"arn:aws:iam::412398765099:role/gw-readonly","externalId":"gw-ext-0123456789ab"}}"""
        .formatted(ACCESS_KEY_ID));
  }

  static TestSecrets secrets() {
    return new TestSecrets().with(SECRET_FIELD, FakeAws.BASE_SECRET);
  }

  /** A call with its own id, as the core makes one per tools/call. */
  static ToolCall call(String tool, String connection, String arguments) {
    return new ToolCall("tc_aws_" + CALLS.incrementAndGet(), tool, connection,
        (ObjectNode) TestCtx.JSON.readTree(arguments), null);
  }

  static PolicyView policy(Env env) {
    return new PolicyView(env, env.id() + ".cloud", null, limits(env));
  }

  static PolicyView policy(Env env, String section) {
    return new PolicyView(env, env.id() + ".cloud", TestCtx.JSON.readTree(section), limits(env));
  }

  /** The kit's stage section: ec2, ecs, rds, sqs, cloudwatch in eu-central-1, three ARN masks. */
  static PolicyView kitStage() {
    return policy(Env.STAGE, """
        {"allowedServices":["ec2","ecs","rds","sqs","cloudwatch"],"regions":["eu-central-1"],
         "resources":["arn:aws:ecs:eu-central-1:*:service/stage/*","arn:aws:rds:eu-central-1:*:db:stage-*",
                      "arn:aws:sqs:eu-central-1:*:stage-*"],"maxRangeDays":14,"maxPoints":2000,"maxItems":100}""");
  }

  static Limits limits(Env env) {
    return env == Env.PROD ? TestCtx.prod() : TestCtx.dev();
  }
}
