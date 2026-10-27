package ai.msrv.gaterook.connectors.aws;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The AWS service catalog (report 09 §6 "Service catalogue"): which service a CloudFormation type
 * ({@code AWS::RDS::DBInstance}) or a CloudWatch namespace ({@code AWS/RDS}) belongs to, as the IAM prefix that the
 * Cloud policy's "Allowed services" names ({@code rds}) and the generated session policy uses. This is code, not
 * policy: a person lists services, the gateway knows what they open.
 *
 * <p>Some services are sensitive: their resources describe identities and keys ({@code iam}, {@code kms}), so agents
 * see them only when the policy lists them by name. Some types are never read at all: the read handler of
 * {@code AWS::SecretsManager::Secret} calls {@code GetSecretValue}, and {@code AWS::SSM::Parameter} carries the value
 * (report 09 §1 #4).
 */
final class Services {

  /** CloudFormation namespace (lower case) → IAM prefix, where they differ. */
  private static final Map<String, String> TYPE_NAMESPACES = Map.ofEntries(
      Map.entry("elasticloadbalancingv2", "elasticloadbalancing"),
      Map.entry("applicationautoscaling", "application-autoscaling"),
      Map.entry("stepfunctions", "states"),
      Map.entry("apigatewayv2", "apigateway"),
      Map.entry("efs", "elasticfilesystem"),
      Map.entry("opensearchservice", "es"),
      Map.entry("elasticsearch", "es"),
      Map.entry("msk", "kafka"),
      Map.entry("kinesisfirehose", "firehose"),
      Map.entry("certificatemanager", "acm"),
      Map.entry("docdb", "rds"),
      Map.entry("neptune", "rds"),
      Map.entry("cognito", "cognito-idp"),
      Map.entry("emr", "elasticmapreduce"));

  /** CloudWatch namespaces of AWS ({@code AWS/<X>}, lower case X) → IAM prefix, where they differ. */
  private static final Map<String, String> METRIC_NAMESPACES = Map.ofEntries(
      Map.entry("ebs", "ec2"),
      Map.entry("natgateway", "ec2"),
      Map.entry("transitgateway", "ec2"),
      Map.entry("vpn", "ec2"),
      Map.entry("privatelinkendpoints", "ec2"),
      Map.entry("applicationelb", "elasticloadbalancing"),
      Map.entry("networkelb", "elasticloadbalancing"),
      Map.entry("gatewayelb", "elasticloadbalancing"),
      Map.entry("elb", "elasticloadbalancing"),
      Map.entry("docdb", "rds"),
      Map.entry("neptune", "rds"),
      Map.entry("efs", "elasticfilesystem"),
      Map.entry("aoss", "es"),
      Map.entry("certificatemanager", "acm"),
      Map.entry("usage", "cloudwatch"));

  /** Namespaces outside {@code AWS/} that AWS itself writes. */
  private static final Map<String, String> AWS_OWNED_NAMESPACES = Map.of(
      "ecs/containerinsights", "ecs",
      "containerinsights", "eks");

  /** What people write in "Allowed services" instead of the IAM prefix. */
  private static final Map<String, String> SYNONYMS = Map.ofEntries(
      Map.entry("elb", "elasticloadbalancing"),
      Map.entry("elbv2", "elasticloadbalancing"),
      Map.entry("alb", "elasticloadbalancing"),
      Map.entry("nlb", "elasticloadbalancing"),
      Map.entry("ebs", "ec2"),
      Map.entry("vpc", "ec2"),
      Map.entry("aurora", "rds"),
      Map.entry("cloudwatch-logs", "logs"),
      Map.entry("stepfunctions", "states"),
      Map.entry("efs", "elasticfilesystem"),
      Map.entry("opensearch", "es"),
      Map.entry("msk", "kafka"),
      Map.entry("eventbridge", "events"),
      Map.entry("emr", "elasticmapreduce"),
      Map.entry("monitoring", "cloudwatch"));

  /** Seen only when the policy lists them by name ("secretsmanager, iam and kms are not on the list…", kit). */
  private static final Set<String> SENSITIVE = Set.of("iam", "kms", "secretsmanager", "sts", "organizations",
      "sso", "identitystore");

  /**
   * The services the session policy opens when the environment lists none: the common read-only surface of
   * {@code aws_describe} and {@code aws_metrics}, small enough for STS's 2,048 characters.
   */
  static final List<String> DEFAULT_SESSION = List.of("ec2", "ecs", "eks", "ecr", "rds", "sqs", "sns", "s3", "lambda",
      "dynamodb", "elasticache", "elasticloadbalancing", "autoscaling", "cloudwatch", "logs", "kinesis", "states",
      "apigateway", "cloudfront", "route53", "elasticfilesystem", "es");

  private Services() {}

  /** The service of {@code AWS::<Namespace>::<Type>}; {@code null} for a type outside {@code AWS::}. */
  static String ofType(String typeName) {
    if (typeName == null || !typeName.startsWith("AWS::")) {
      return null;
    }
    int end = typeName.indexOf("::", 5);
    if (end <= 5) {
      return null;
    }
    String ns = typeName.substring(5, end).toLowerCase(Locale.ROOT);
    return TYPE_NAMESPACES.getOrDefault(ns, ns);
  }

  /**
   * The service of a CloudWatch namespace: {@code AWS/RDS} → {@code rds}, {@code AWS/ApplicationELB} →
   * {@code elasticloadbalancing}; {@code null} for a custom namespace ({@code MyApp}).
   */
  static String ofNamespace(String namespace) {
    if (namespace == null) {
      return null;
    }
    String ns = namespace.toLowerCase(Locale.ROOT);
    if (AWS_OWNED_NAMESPACES.containsKey(ns)) {
      return AWS_OWNED_NAMESPACES.get(ns);
    }
    if (!ns.startsWith("aws/") || ns.length() == 4) {
      return null;
    }
    String svc = ns.substring(4);
    return METRIC_NAMESPACES.getOrDefault(svc, svc);
  }

  /** The IAM prefix a person meant: {@code ELBv2} → {@code elasticloadbalancing}. */
  static String id(String listed) {
    String s = listed.strip().toLowerCase(Locale.ROOT);
    return SYNONYMS.getOrDefault(s, s);
  }

  static boolean sensitive(String service) {
    return SENSITIVE.contains(service);
  }

  /**
   * Types whose read returns secret values whatever the policy says: every {@code AWS::SecretsManager::*} (the
   * read handler calls {@code GetSecretValue}) and {@code AWS::SSM::Parameter} (its {@code Value}).
   */
  static boolean secretValues(String typeName) {
    // Whatever the case the agent writes: whether Cloud Control resolves "AWS::SSM::parameter" is no question to test.
    String t = typeName.toLowerCase(Locale.ROOT);
    return t.startsWith("aws::secretsmanager::") || t.equals("aws::ssm::parameter");
  }
}
