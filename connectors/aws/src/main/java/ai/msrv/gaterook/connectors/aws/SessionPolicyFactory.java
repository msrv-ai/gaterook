package ai.msrv.gaterook.connectors.aws;

import ai.msrv.gaterook.connectors.cloudauth.AwsSessionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * The read-only session policy of AssumeRole (report 09 §6 "AWS session policy"): AWS intersects it with the
 * role, so even an administrator role only reads in a gateway session — "Read only for this session: AWS enforces the
 * gateway's session policy".
 *
 * <ul>
 *   <li>Allow: {@code <service>:Describe*}, {@code List*} and {@code Get*} of the allowed services (Cloud Control's
 *       read handlers run with the caller's credentials and need them), Cloud Control's own
 *       {@code cloudformation:ListResources|GetResource} and CloudWatch's three reads;</li>
 *   <li>explicit Deny of reads that return data rather than configuration, so a broad allow never opens them:
 *       secret and parameter values, KMS decryption, S3 objects, DynamoDB items, queue messages, stream records,
 *       log events, Lambda invocation, Windows passwords and console output, registry credentials and layers, Step
 *       Functions tasks.</li>
 * </ul>
 * {@code LAMBDA()} in metric math is not covered by any Deny (CloudWatch invokes the function as its own service
 * principal): only the expression allowlist stops it ({@link ExpressionGuard}).
 */
final class SessionPolicyFactory {

  static final List<String> DENY = List.of("secretsmanager:GetSecretValue", "ssm:GetParameter*", "kms:Decrypt",
      "s3:GetObject*", "dynamodb:GetItem", "dynamodb:BatchGetItem", "dynamodb:Query", "dynamodb:Scan",
      "dynamodb:GetRecords", "sqs:ReceiveMessage", "lambda:InvokeFunction", "kinesis:GetRecords", "logs:GetLog*",
      "ec2:GetPasswordData", "ec2:GetConsoleOutput", "ec2:GetConsoleScreenshot", "ecr:GetAuthorizationToken",
      "ecr:GetDownloadUrlForLayer", "states:GetActivityTask");

  private SessionPolicyFactory() {}

  /**
   * The policy JSON for {@code services} (IAM prefixes).
   *
   * @throws IllegalStateException when it doesn't fit STS's 2,048 characters: "Allow fewer services"
   */
  static String readOnly(List<String> services) {
    TreeSet<String> svc = new TreeSet<>(services);
    List<String> allow = new ArrayList<>();
    for (String s : svc) {
      allow.add(s + ":Describe*");
      allow.add(s + ":List*");
      allow.add(s + ":Get*");
    }
    allow.add("cloudformation:ListResources");
    allow.add("cloudformation:GetResource");
    if (!svc.contains("cloudwatch")) {
      allow.add("cloudwatch:GetMetricData");
      allow.add("cloudwatch:ListMetrics");
      allow.add("cloudwatch:DescribeAlarms");
    }
    return AwsSessionPolicy.builder().allow(allow, List.of()).deny(DENY, List.of()).toJson();
  }
}
