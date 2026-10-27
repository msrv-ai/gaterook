package ai.msrv.gaterook.connectors.aws;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

/**
 * What the gateway knows about common CloudFormation types beyond what Cloud Control returns (report 09 §1 #5,
 * §3.1, §9): where the status is, how to build the ARN of a type whose model has none ({@code AWS::EC2::Instance},
 * {@code AWS::EC2::VPC}: the "Resources" masks are ARN masks), and which parent a list needs.
 * Types not listed here still work: the status is found by name ({@code Status}, {@code State}, {@code …Status}), the
 * ARN from {@code Arn} or {@code <Type>Arn} or an ARN identifier.
 */
final class TypeCatalog {

  /**
   * @param status dotted path of the status property, or {@code null}: found by name
   * @param arn    ARN template ({@code {p}} partition, {@code {r}} region, {@code {a}} account, {@code {id}} the
   *               primary identifier), or {@code null}
   * @param parent the list's required parent, as an example of {@code parent}, or {@code null}
   */
  record Entry(String status, String arn, String parent) {}

  private static final Map<String, Entry> TYPES = Map.ofEntries(
      Map.entry("AWS::EC2::Instance", new Entry("State.Name", "arn:{p}:ec2:{r}:{a}:instance/{id}", null)),
      Map.entry("AWS::EC2::VPC", new Entry(null, "arn:{p}:ec2:{r}:{a}:vpc/{id}", null)),
      Map.entry("AWS::EC2::Subnet", new Entry(null, "arn:{p}:ec2:{r}:{a}:subnet/{id}", null)),
      Map.entry("AWS::EC2::SecurityGroup", new Entry(null, "arn:{p}:ec2:{r}:{a}:security-group/{id}", null)),
      Map.entry("AWS::EC2::Volume", new Entry(null, "arn:{p}:ec2:{r}:{a}:volume/{id}", null)),
      Map.entry("AWS::EC2::NatGateway", new Entry(null, "arn:{p}:ec2:{r}:{a}:natgateway/{id}", null)),
      Map.entry("AWS::EC2::InternetGateway", new Entry(null, "arn:{p}:ec2:{r}:{a}:internet-gateway/{id}", null)),
      Map.entry("AWS::EC2::RouteTable", new Entry(null, "arn:{p}:ec2:{r}:{a}:route-table/{id}", null)),
      Map.entry("AWS::EC2::NetworkInterface", new Entry(null, "arn:{p}:ec2:{r}:{a}:network-interface/{id}", null)),
      Map.entry("AWS::EC2::LaunchTemplate", new Entry(null, "arn:{p}:ec2:{r}:{a}:launch-template/{id}", null)),
      Map.entry("AWS::EC2::KeyPair", new Entry(null, "arn:{p}:ec2:{r}:{a}:key-pair/{id}", null)),
      Map.entry("AWS::RDS::DBInstance", new Entry("DBInstanceStatus", "arn:{p}:rds:{r}:{a}:db:{id}", null)),
      Map.entry("AWS::RDS::DBCluster", new Entry("Status", "arn:{p}:rds:{r}:{a}:cluster:{id}", null)),
      Map.entry("AWS::RDS::DBSubnetGroup", new Entry(null, "arn:{p}:rds:{r}:{a}:subgrp:{id}", null)),
      Map.entry("AWS::S3::Bucket", new Entry(null, "arn:{p}:s3:::{id}", null)),
      Map.entry("AWS::Lambda::Function", new Entry(null, "arn:{p}:lambda:{r}:{a}:function:{id}", null)),
      Map.entry("AWS::DynamoDB::Table", new Entry(null, "arn:{p}:dynamodb:{r}:{a}:table/{id}", null)),
      Map.entry("AWS::ECS::Cluster", new Entry(null, "arn:{p}:ecs:{r}:{a}:cluster/{id}", null)),
      Map.entry("AWS::ECS::Service", new Entry(null, null, "{\"Cluster\":\"stage\"}")),
      Map.entry("AWS::EKS::Cluster", new Entry(null, "arn:{p}:eks:{r}:{a}:cluster/{id}", null)),
      Map.entry("AWS::EKS::Nodegroup", new Entry(null, null, "{\"ClusterName\":\"stage\"}")),
      Map.entry("AWS::ElastiCache::CacheCluster", new Entry(null, "arn:{p}:elasticache:{r}:{a}:cluster:{id}",
          null)),
      Map.entry("AWS::Logs::LogGroup", new Entry(null, "arn:{p}:logs:{r}:{a}:log-group:{id}", null)),
      Map.entry("AWS::CloudWatch::Alarm", new Entry(null, "arn:{p}:cloudwatch:{r}:{a}:alarm:{id}", null)),
      Map.entry("AWS::ECR::Repository", new Entry(null, "arn:{p}:ecr:{r}:{a}:repository/{id}", null)),
      Map.entry("AWS::Kinesis::Stream", new Entry(null, "arn:{p}:kinesis:{r}:{a}:stream/{id}", null)),
      Map.entry("AWS::EFS::FileSystem", new Entry(null, "arn:{p}:elasticfilesystem:{r}:{a}:file-system/{id}", null)),
      Map.entry("AWS::ElasticLoadBalancingV2::Listener", new Entry(null, null,
          "{\"LoadBalancerArn\":\"arn:aws:elasticloadbalancing:…:loadbalancer/app/…\"}")),
      Map.entry("AWS::ElasticLoadBalancingV2::ListenerRule", new Entry(null, null,
          "{\"ListenerArn\":\"arn:aws:elasticloadbalancing:…:listener/app/…\"}")),
      Map.entry("AWS::Lambda::Version", new Entry(null, null, "{\"FunctionName\":\"orders-worker\"}")),
      Map.entry("AWS::Lambda::Alias", new Entry(null, null, "{\"FunctionName\":\"orders-worker\"}")),
      Map.entry("AWS::ApiGateway::Stage", new Entry(null, null, "{\"RestApiId\":\"a1b2c3d4e5\"}")),
      Map.entry("AWS::ApiGateway::Resource", new Entry(null, null, "{\"RestApiId\":\"a1b2c3d4e5\"}")));

  /** Types worth showing an agent first ({@code describe_connection}, Test connection). */
  static final List<String> EXAMPLES = List.of("AWS::EC2::Instance", "AWS::RDS::DBInstance", "AWS::ECS::Cluster",
      "AWS::SQS::Queue", "AWS::Lambda::Function", "AWS::ElasticLoadBalancingV2::LoadBalancer", "AWS::S3::Bucket",
      "AWS::DynamoDB::Table");

  private TypeCatalog() {}

  static Entry of(String type) {
    return TYPES.get(type);
  }

  /** The status of a resource: the catalog's path, else {@code Status}, {@code State}, {@code State.Name} or {@code …Status}. */
  static String status(String type, JsonNode props) {
    Entry e = TYPES.get(type);
    if (e != null && e.status() != null) {
      String s = text(at(props, e.status()));
      if (s != null) {
        return s;
      }
    }
    for (String p : List.of("Status", "State", "State.Name")) {
      String s = text(at(props, p));
      if (s != null) {
        return s;
      }
    }
    Map<String, JsonNode> sorted = new TreeMap<>();
    props.properties().forEach(f -> sorted.put(f.getKey(), f.getValue()));
    for (Map.Entry<String, JsonNode> f : sorted.entrySet()) {
      if (f.getKey().endsWith("Status") && f.getValue().isString() && !f.getValue().asString().isBlank()) {
        return f.getValue().asString();
      }
    }
    return null;
  }

  /**
   * The ARN of a resource: {@code Arn}, {@code <Type>Arn} ({@code ServiceArn} of {@code AWS::ECS::Service}), an
   * identifier that is an ARN ({@code ServiceArn|Cluster}: its first part), else the catalog's template when the
   * account is known; {@code null} if none of these.
   */
  static String arn(String type, String identifier, JsonNode props, String region, String account) {
    String own = arnText(props == null ? null : props.get("Arn"));
    if (own != null) {
      return own;
    }
    String shortName = type.substring(type.lastIndexOf(':') + 1);
    own = arnText(props == null ? null : props.get(shortName + "Arn"));
    if (own != null) {
      return own;
    }
    if (identifier != null && identifier.startsWith("arn:")) {
      int bar = identifier.indexOf('|');
      return bar < 0 ? identifier : identifier.substring(0, bar);
    }
    return template(type, identifier, region, account);
  }

  /** The catalog's ARN of {@code identifier}, known before any request; {@code null} without template or account. */
  static String template(String type, String identifier, String region, String account) {
    Entry e = TYPES.get(type);
    if (e == null || e.arn() == null || identifier == null || identifier.contains("|")) {
      return null;
    }
    if (account == null && e.arn().contains("{a}")) {
      return null;
    }
    return e.arn().replace("{p}", Settings.partition(region)).replace("{r}", region)
        .replace("{a}", account == null ? "" : account).replace("{id}", identifier);
  }

  private static String arnText(JsonNode n) {
    return n != null && n.isString() && n.asString().startsWith("arn:") ? n.asString() : null;
  }

  static JsonNode at(JsonNode node, String dotted) {
    JsonNode n = node;
    for (String part : dotted.split("\\.")) {
      if (n == null || !n.isObject()) {
        return null;
      }
      n = n.get(part);
    }
    return n;
  }

  private static String text(JsonNode n) {
    return n != null && n.isValueNode() && !n.isNull() && !n.asString("").isBlank() ? n.asString() : null;
  }
}
