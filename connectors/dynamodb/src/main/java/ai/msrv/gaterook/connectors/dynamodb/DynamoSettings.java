package ai.msrv.gaterook.connectors.dynamodb;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connectors.cloudauth.AwsSessionPolicy;
import ai.msrv.gaterook.connectors.cloudauth.AwsSpec;
import ai.msrv.gaterook.connectors.support.Glob;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The settings of one DynamoDB connection ({@code config.schema.json}): the region, the credentials of
 * connectors/cloud-auth, whether a generated session policy limits each call, and the tables agents may use.
 *
 * @param tables         globs of table names ({@code orders-*}); empty: every table the credentials may read
 * @param generatedPolicy with a role: each call's session policy allows what it needs and nothing else
 */
record DynamoSettings(String region, AwsSpec credentials, boolean generatedPolicy, List<String> tables,
    boolean consistentReads) {

  private static final Pattern REGION = Pattern.compile("[a-z]{2}(-[a-z]+)+-\\d+");

  static DynamoSettings of(ConnectionConfig cfg) throws ConnectorException {
    String region = cfg.string("/region");
    if (region == null || !REGION.matcher(region).matches()) {
      throw ConnectorException.badInput("Choose the AWS region of the tables, such as eu-central-1.");
    }
    AwsSpec spec;
    try {
      spec = AwsSpec.fromConfig(cfg.config(), "/credentials", region);
    } catch (IllegalArgumentException e) {
      throw ConnectorException.badInput("The AWS credentials of " + cfg.name() + " aren't complete: " + e.getMessage()
          + ".");
    }
    String policy = cfg.string("/credentials/sessionPolicy");
    List<String> tables = new ArrayList<>(cfg.strings("/tables"));
    return new DynamoSettings(region, spec, !"none".equals(policy), List.copyOf(tables),
        cfg.bool("/advanced/consistentReads", false));
  }

  /** Whether agents may use {@code table}: on the list, or any when the list is empty. */
  boolean allows(String table) {
    if (tables.isEmpty()) {
      return true;
    }
    for (String glob : tables) {
      if (Glob.text(glob).matches(table)) {
        return true;
      }
    }
    return false;
  }

  /** {@code https://dynamodb.<region>.amazonaws.com}, or {@code .amazonaws.com.cn} in China. */
  String endpoint() {
    return "https://dynamodb." + region + ".amazonaws.com" + (region.startsWith("cn-") ? ".cn" : "");
  }

  /** The partition of the region's ARNs. */
  private String partition() {
    return region.startsWith("cn-") ? "aws-cn" : region.startsWith("us-gov-") ? "aws-us-gov" : "aws";
  }

  private List<String> tableArns(List<String> names) {
    List<String> out = new ArrayList<>();
    for (String name : names) {
      String table = "arn:" + partition() + ":dynamodb:" + region + ":*:table/" + name;
      out.add(table);
      out.add(table + "/index/*");
    }
    return out;
  }

  /** Actions of the read tools. */
  static final List<String> READ_ACTIONS = List.of("dynamodb:DescribeTable", "dynamodb:DescribeTimeToLive",
      "dynamodb:Query", "dynamodb:Scan", "dynamodb:GetItem", "dynamodb:BatchGetItem", "dynamodb:PartiQLSelect");
  /** Actions of an approved PartiQL change. */
  static final List<String> WRITE_ACTIONS = List.of("dynamodb:PartiQLInsert", "dynamodb:PartiQLUpdate",
      "dynamodb:PartiQLDelete", "dynamodb:PartiQLSelect");

  /**
   * The session policy of reads, or {@code null} without one: the read actions on the listed tables (on every table
   * without a list) and ListTables, which has no table. STS takes at most 2,048 characters: a list too long for that
   * keeps the actions and drops the table names, AWS still allowing no write.
   */
  String readPolicy() {
    if (!generatedPolicy) {
      return null;
    }
    try {
      return AwsSessionPolicy.builder().allow(READ_ACTIONS, tables.isEmpty() ? List.of("*") : tableArns(tables))
          .allow(List.of("dynamodb:ListTables"), List.of("*")).toJson();
    } catch (IllegalStateException tooLong) {
      return AwsSessionPolicy.builder().allow(READ_ACTIONS, List.of("*"))
          .allow(List.of("dynamodb:ListTables"), List.of("*")).toJson();
    }
  }

  /** The session policy of one approved change: PartiQL writes to that table only; {@code null} without one. */
  String writePolicy(String table) {
    if (!generatedPolicy) {
      return null;
    }
    return AwsSessionPolicy.builder().allow(WRITE_ACTIONS, tableArns(List.of(table))).toJson();
  }
}
