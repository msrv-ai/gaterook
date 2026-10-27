package ai.msrv.gaterook.connectors.aws;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connectors.cloudauth.AwsSpec;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.services.cloudcontrol.CloudControlClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.RecentlyActive;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Test connection of AWS (report 09 §5): each step timed and shown, the first failure of the credentials stops the
 * test with what happened and what to do; what a role may read is a table, not a failure.
 *
 * <ol>
 *   <li>the base identity ({@code GetCallerIdentity} with the access key or profile): the user ARN for the role's
 *       trust policy; root keys get a warning;</li>
 *   <li>{@code AssumeRole} with the ExternalId, 900 s and the session policy, proven by {@code GetCallerIdentity} of
 *       the session ({@code …:assumed-role/gw-readonly/gaterook-aws-stage}); another account than the
 *       connection's fails;</li>
 *   <li>{@code ListResources} of a few common types in each region: "ok" or what AWS denied — a narrow role, or a
 *       handler that needs another service ({@code ec2:DescribeVpcs} for RDS);</li>
 *   <li>{@code ListMetrics} and {@code DescribeAlarms}.</li>
 * </ol>
 */
final class ConnectionTest {

  static final List<String> PROBE_TYPES = List.of("AWS::EC2::Instance", "AWS::RDS::DBInstance", "AWS::ECS::Cluster",
      "AWS::SQS::Queue", "AWS::Lambda::Function");
  private static final int MAX_REGIONS = 3;
  /** Time the type probes of one region may take (report 09 §5 step 4). */
  private static final Duration TYPES_BUDGET = Duration.ofSeconds(2);

  private final Call call;
  private final List<TestResult.Check> checks = new ArrayList<>();
  private final List<TestResult.Hint> hints = new ArrayList<>();

  ConnectionTest(Call call) {
    this.call = call;
  }

  @FunctionalInterface
  private interface Step<T> {
    T run() throws ConnectorException;
  }

  private <T> T step(String name, Step<T> step, java.util.function.Function<T, String> detail)
      throws ConnectorException {
    long started = System.nanoTime();
    try {
      T value = step.run();
      checks.add(new TestResult.Check(name, true, ms(started), detail.apply(value)));
      return value;
    } catch (ConnectorException e) {
      checks.add(new TestResult.Check(name, false, ms(started), e.getMessage()));
      throw e;
    }
  }

  TestResult run() {
    Settings s = call.settings();
    AwsSpec spec = s.credentials();
    String region = s.firstRegion();
    CloudCredentials.CallerIdentity session;
    long dataMs = 0;
    ArrayNode types = JsonNodeFactory.instance.arrayNode();
    try {
      if (spec.roleArn() != null) {
        CloudCredentials.CallerIdentity base = step(spec.source() == AwsSpec.Source.PROFILE ? "profile "
            + spec.profile() : "access key", () -> call.cloud().callerIdentity(base(), region),
            CloudCredentials.CallerIdentity::arn);
        root(base);
        session = step("AssumeRole", () -> {
          AwsCredentialsProvider assumed = call.credentials();
          Call.resolve(assumed);
          return call.cloud().callerIdentity(assumed, region);
        },
            id -> "Session " + id.arn() + " for " + spec.sessionSeconds() / 60 + " min"
                + (s.enforcedByAws() ? ", read only by the session policy" : ""));
      } else {
        session = step("identity", () -> call.cloud().callerIdentity(call.credentials(), region),
            CloudCredentials.CallerIdentity::arn);
        root(session);
      }
      if (s.accountId() != null && !s.accountId().equals(session.account())) {
        String text = "The credentials are for account " + session.account() + ", not " + s.accountId()
            + ". Fix Account ID or the credentials.";
        checks.add(new TestResult.Check("account", false, 0, text));
        return TestResult.failure("aws.wrong-account", text, null).withChecks(checks);
      }
      for (String r : s.regions().subList(0, Math.min(MAX_REGIONS, s.regions().size()))) {
        long started = System.nanoTime();
        long first = probeTypes(r, types, started);
        if (dataMs == 0) {
          dataMs = first;
        }
      }
      CloudWatchClient cw = call.cloudWatch(region);
      step("metrics", () -> {
        call.quota(region, "ListMetrics", Call.CLOUDWATCH_PER_MINUTE);
        AwsRequestOverrideConfiguration o = call.override();
        return call.aws("CloudWatch", "ListMetrics", () -> cw.listMetrics(b -> b.overrideConfiguration(o)
            .namespace("AWS/EC2").recentlyActive(RecentlyActive.PT3_H))).metrics().size();
      }, n -> n == 0 ? "No AWS/EC2 metrics in the last 3 h" : "AWS/EC2 has metrics in the last 3 h");
      step("alarms", () -> {
        call.quota(region, "DescribeAlarms", Call.CLOUDWATCH_PER_MINUTE);
        AwsRequestOverrideConfiguration o = call.override();
        return call.aws("CloudWatch", "DescribeAlarms", () -> cw.describeAlarms(b -> b.overrideConfiguration(o)
            .maxRecords(1))).metricAlarms().size();
      }, n -> "DescribeAlarms allowed");
    } catch (ConnectorException e) {
      String code = switch (e.kind()) {
        case TIMEOUT -> "aws.timeout";
        case RESOURCE_UNAVAILABLE -> "aws.unreachable";
        case BAD_INPUT -> "aws.invalid";
        default -> "aws.refused";
      };
      Throwable cause = e.getCause();
      return TestResult.failure(code, e.getMessage(), cause == null ? null : cause.getClass().getSimpleName() + ": "
          + cause.getMessage()).withChecks(checks);
    }
    boolean prod = call.cfg().env() == Env.PROD;
    if (spec.roleArn() == null) {
      hints.add(new TestResult.Hint(prod ? TestResult.Severity.WARNING : TestResult.Severity.INFO,
          "aws.long-lived-key", "/credentials/roleArn", "Use AssumeRole" + (prod ? " on prod" : "") + ": without a "
              + "role the key's own permissions apply, it doesn't expire, and the gateway's read-only session policy "
              + "can't be applied. A role session lasts " + spec.sessionSeconds() / 60 + " min."));
    } else if (!s.sessionPolicy()) {
      hints.add(new TestResult.Hint(TestResult.Severity.WARNING, "aws.no-session-policy",
          "/credentials/sessionPolicy", "Permissions unknown. Turn on the session policy or use a read-only role "
              + "for prod."));
    }
    for (String w : call.cloud().profileWarnings(spec)) {
      hints.add(new TestResult.Hint(TestResult.Severity.WARNING, "aws.profile", "/credentials/profile", w));
    }
    boolean enforced = s.enforcedByAws();
    TestResult.Permissions permissions = new TestResult.Permissions(
        enforced ? TestResult.Level.READ : TestResult.Level.UNKNOWN,
        "Reads " + String.join(", ", s.regions()) + " through Cloud Control and CloudWatch",
        enforced ? "None in this session: AWS enforces the gateway's read-only session policy" : "Unknown",
        List.of(), null);
    String account = session.account();
    ObjectNode details = JsonNodeFactory.instance.objectNode().put("account", account).put("arn", session.arn());
    details.set("types", types);
    if (enforced) {
      details.put("permissions", "Read only for this session: AWS enforces the gateway's session policy");
    }
    String target = s.firstRegion() + " · " + account + (s.accountAlias() == null ? "" : " " + s.accountAlias());
    TestResult result = TestResult.success(dataMs, "Cloud Control API · CloudWatch · STS " + region, session.arn(),
        permissions)
        .withChecks(checks)
        .withMetadata(JsonNodeFactory.instance.objectNode().put("serverVersion", "AWS · " + target)
            .put("account", account))
        .withDetails(details);
    for (TestResult.Hint h : hints) {
      result = result.withHint(h);
    }
    return result;
  }

  /** {@code ListResources(MaxResults=1)} of the probe types in one region; returns the first one's latency. */
  private long probeTypes(String region, ArrayNode rows, long started) throws ConnectorException {
    CloudControlClient cc = call.cloudControl(region);
    List<String> ok = new ArrayList<>();
    List<String> denied = new ArrayList<>();
    long firstMs = 0;
    for (String type : PROBE_TYPES) {
      if (System.nanoTime() - started > TYPES_BUDGET.toNanos() || !call.timeForAnotherPage()) {
        break;
      }
      ObjectNode row = rows.addObject().put("region", region).put("type", type);
      long t0 = System.nanoTime();
      try {
        call.quota(region, "CloudControl", Call.CLOUD_CONTROL_PER_MINUTE);
        AwsRequestOverrideConfiguration o = call.override();
        call.aws("Cloud Control", "ListResources", () -> cc.listResources(b -> b.overrideConfiguration(o)
            .typeName(type).maxResults(1)));
        row.put("ok", true);
        ok.add(type);
      } catch (ConnectorException e) {
        if (e.kind() == ConnectorException.Kind.RESOURCE_UNAVAILABLE || e.kind() == ConnectorException.Kind.TIMEOUT) {
          checks.add(new TestResult.Check("types in " + region, false, ms(started), e.getMessage()));
          throw e;
        }
        row.put("ok", false).put("detail", e.getMessage());
        denied.add(type.substring(5) + " (" + e.getMessage() + ")");
      }
      if (firstMs == 0) {
        firstMs = Math.max(1, ms(t0));
      }
    }
    String detail = (ok.isEmpty() ? "" : "Reads " + String.join(", ", ok.stream().map(t -> t.substring(5)).toList()))
        + (denied.isEmpty() ? "" : (ok.isEmpty() ? "" : "; ") + "denied " + String.join("; ", denied));
    checks.add(new TestResult.Check("types in " + region, true, ms(started), detail));
    if (!denied.isEmpty()) {
      hints.add(new TestResult.Hint(TestResult.Severity.INFO, "aws.types-denied", "/credentials/roleArn",
          "Some types can't be read in " + region + ": the role, the session policy or the type's handler needs "
              + "more read permissions (RDS also reads ec2:DescribeVpcs)."));
    }
    return firstMs;
  }

  /** The credentials and one cheap read: what calls need, without the details Test connection collects. */
  HealthResult health() {
    long started = System.nanoTime();
    String region = call.settings().firstRegion();
    try {
      CloudWatchClient cw = call.cloudWatch(region);
      call.quota(region, "DescribeAlarms", Call.CLOUDWATCH_PER_MINUTE);
      AwsRequestOverrideConfiguration o = call.override();
      try {
        call.aws("CloudWatch", "DescribeAlarms", () -> cw.describeAlarms(b -> b.overrideConfiguration(o)
            .maxRecords(1)));
      } catch (ConnectorException e) {
        if (e.kind() != ConnectorException.Kind.RESOURCE_ERROR) {
          throw e;
        }
        // The credentials work; the role just may not list alarms.
      }
      return HealthResult.healthy(ms(started));
    } catch (ConnectorException e) {
      return HealthResult.unreachable(e.getMessage());
    }
  }

  /** The access key or profile alone, without the role: the base identity of step 1. */
  private AwsCredentialsProvider base() throws ConnectorException {
    AwsSpec spec = call.settings().credentials();
    AwsSpec plain = new AwsSpec(spec.source(), spec.accessKeyId(), spec.secretKeyField(), spec.profile(), null, null,
        spec.sessionSeconds(), spec.region());
    AwsCredentialsProvider p = call.cloud().aws(new CloudCredentials.Owner(baseOwner(call.cfg().id()),
        call.cfg().version(), call.cfg().name()), plain, null, call.secrets());
    return CloudCredentials.tainting(p, call.ctx()::taint);
  }

  /** The credentials cache key of a connection's base identity (evicted with the connection). */
  static String baseOwner(String connectionId) {
    return connectionId + "#base";
  }

  private void root(CloudCredentials.CallerIdentity id) {
    if (id.arn() != null && id.arn().endsWith(":root")) {
      hints.add(new TestResult.Hint(TestResult.Severity.DANGER, "aws.root-key", "/credentials/accessKeyId",
          "Don't use root access keys: create an IAM user that may only assume the read-only role."));
    }
  }

  private static long ms(long started) {
    return (System.nanoTime() - started) / 1_000_000;
  }
}
