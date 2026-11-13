package com.gaterook.connectors.redshift;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connectors.cloudauth.AwsSessionPolicy;
import com.gaterook.connectors.cloudauth.AwsSpec;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The settings of one Redshift connection ({@code config.schema.json}): the region, the warehouse (a provisioned
 * cluster or a Serverless workgroup), the database and how the Data API signs in to it, the AWS credentials of
 * connectors/cloud-auth, and whether a generated session policy limits each call.
 *
 * @param workgroup       a Serverless workgroup's name or ARN; {@code null} for a cluster
 * @param generatedPolicy with a role: each call's session policy allows what it needs and nothing else
 */
record RedshiftSettings(String region, Kind kind, String cluster, String workgroup, String database, Auth auth,
    String dbUser, String secretArn, AwsSpec credentials, boolean generatedPolicy) {

  /** What the Data API runs on. */
  enum Kind { CLUSTER, SERVERLESS }

  /** How the Data API signs in to the database. */
  enum Auth {
    /**
     * As the AWS identity, {@code IAM:<user>} or {@code IAMR:<role>}: {@code GetClusterCredentialsWithIAM}, or
     * {@code GetCredentials} on Serverless.
     */
    IAM,
    /** As a database user with temporary credentials ({@code GetClusterCredentials}); clusters only. */
    DB_USER,
    /** With the user and password of a Secrets Manager secret, which the Data API reads itself. */
    SECRET
  }

  static final Pattern REGION = Pattern.compile("[a-z]{2}(-[a-z]+)+-\\d+");
  static final Pattern CLUSTER = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");
  static final Pattern WORKGROUP = Pattern.compile("[a-z0-9-]{3,63}");
  static final Pattern WORKGROUP_ARN = Pattern.compile("arn:aws[a-z-]*:redshift-serverless:[a-z0-9-]+:\\d{12}:"
      + "workgroup/[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
  static final Pattern DATABASE = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]{0,126}");
  static final Pattern DB_USER_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_+.@$-]{0,127}");
  static final Pattern SECRET_NAME = Pattern.compile("[A-Za-z0-9/_+=.@-]{1,512}");
  static final Pattern SECRET_ARN = Pattern.compile("arn:aws[a-z-]*:secretsmanager:[a-z0-9-]+:\\d{12}:secret:"
      + "[A-Za-z0-9/_+=.@-]{1,512}");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  /** Why a database user's sign-in doesn't take a Serverless workgroup: Test connection's and the form's words. */
  static final String DB_USER_ON_SERVERLESS = "A database user with temporary credentials works on provisioned "
      + "clusters only. On Serverless, sign in as the AWS identity or with a Secrets Manager secret.";

  static RedshiftSettings of(ConnectionConfig cfg) throws ConnectorException {
    String region = cfg.string("/region");
    if (region == null || !REGION.matcher(region).matches()) {
      throw ConnectorException.badInput("Choose the AWS region of the warehouse, such as eu-central-1.");
    }
    Kind kind = "serverless".equals(cfg.string("/target/kind")) ? Kind.SERVERLESS : Kind.CLUSTER;
    String cluster = null;
    String workgroup = null;
    if (kind == Kind.CLUSTER) {
      cluster = cfg.string("/target/cluster");
      if (cluster == null || cluster.length() > 63 || !CLUSTER.matcher(cluster).matches()) {
        throw ConnectorException.badInput("Enter the cluster identifier, such as acme-analytics.");
      }
    } else {
      workgroup = cfg.string("/target/workgroup");
      if (workgroup == null
          || !(WORKGROUP.matcher(workgroup).matches() || WORKGROUP_ARN.matcher(workgroup).matches())) {
        throw ConnectorException.badInput("Enter the Serverless workgroup's name or ARN, such as acme-wg.");
      }
    }
    String database = cfg.string("/database");
    if (database == null || !DATABASE.matcher(database).matches()) {
      throw ConnectorException.badInput("Enter the database to connect to, such as dev.");
    }
    String method = cfg.string("/databaseAuth/method");
    Auth auth = method == null || method.equals("iam") ? Auth.IAM : method.equals("dbUser") ? Auth.DB_USER
        : method.equals("secret") ? Auth.SECRET : null;
    if (auth == null) {
      throw ConnectorException.badInput("Choose how the Data API signs in to the database.");
    }
    String dbUser = null;
    String secretArn = null;
    if (auth == Auth.DB_USER) {
      if (kind == Kind.SERVERLESS) {
        throw ConnectorException.badInput(DB_USER_ON_SERVERLESS);
      }
      dbUser = cfg.string("/databaseAuth/dbUser");
      if (dbUser == null || !DB_USER_NAME.matcher(dbUser).matches()) {
        throw ConnectorException.badInput("Enter the database user, such as gw_reader.");
      }
    }
    if (auth == Auth.SECRET) {
      secretArn = cfg.string("/databaseAuth/secretArn");
      if (secretArn == null || !(SECRET_ARN.matcher(secretArn).matches() || SECRET_NAME.matcher(secretArn).matches())) {
        throw ConnectorException.badInput("Enter the ARN or name of the Secrets Manager secret with the database "
            + "user's username and password.");
      }
    }
    AwsSpec spec;
    try {
      spec = AwsSpec.fromConfig(cfg.config(), "/credentials", region);
    } catch (IllegalArgumentException e) {
      throw ConnectorException.badInput("The AWS credentials of " + cfg.name() + " aren't complete: " + e.getMessage()
          + ".");
    }
    return new RedshiftSettings(region, kind, cluster, workgroup, database, auth, dbUser, secretArn, spec,
        !"none".equals(cfg.string("/credentials/sessionPolicy")));
  }

  /** {@code https://redshift-data.<region>.amazonaws.com}, or {@code .amazonaws.com.cn} in China. */
  String endpoint() {
    return "https://redshift-data." + region + ".amazonaws.com" + (region.startsWith("cn-") ? ".cn" : "");
  }

  /** The partition of the region's ARNs. */
  String partition() {
    return region.startsWith("cn-") ? "aws-cn" : region.startsWith("us-gov-") ? "aws-us-gov" : "aws";
  }

  /** {@code cluster acme-analytics} or {@code workgroup acme-wg}, for people. */
  String warehouse() {
    return kind == Kind.CLUSTER ? "cluster " + cluster : "workgroup " + workgroup;
  }

  /** What signs in to the database, for people: the database user, the secret or the AWS identity. */
  String signIn() {
    return switch (auth) {
      case DB_USER -> "database user " + dbUser;
      case SECRET -> "secret " + secretArn;
      case IAM -> "the AWS identity";
    };
  }

  /** The Data API's request fields that name the warehouse, the database and the sign-in. */
  ObjectNode target() {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    if (kind == Kind.CLUSTER) {
      out.put("ClusterIdentifier", cluster);
    } else {
      out.put("WorkgroupName", workgroup);
    }
    out.put("Database", database);
    if (auth == Auth.DB_USER) {
      out.put("DbUser", dbUser);
    } else if (auth == Auth.SECRET) {
      out.put("SecretArn", secretArn);
    }
    return out;
  }

  /** Whether the workgroup is named by its ARN, which a session policy can name. */
  boolean workgroupArn() {
    return workgroup != null && WORKGROUP_ARN.matcher(workgroup).matches();
  }

  /** The ARN of the cluster, or of the workgroup ({@code workgroup/*} when only its name is known). */
  String warehouseArn() {
    if (kind == Kind.CLUSTER) {
      return "arn:" + partition() + ":redshift:" + region + ":*:cluster:" + cluster;
    }
    return workgroupArn() ? workgroup : "arn:" + partition() + ":redshift-serverless:" + region + ":*:workgroup/*";
  }

  /** Data API actions of the read tools: one batch per read, the metadata calls. */
  static final List<String> READ_ACTIONS = List.of("redshift-data:BatchExecuteStatement",
      "redshift-data:ListDatabases", "redshift-data:ListSchemas", "redshift-data:ListTables",
      "redshift-data:DescribeTable");
  /** Data API actions of an approved change: one statement. */
  static final List<String> CHANGE_ACTIONS = List.of("redshift-data:ExecuteStatement");
  /** Actions on the statements a session ran itself; listing them is the health check. */
  static final List<String> STATEMENT_ACTIONS = List.of("redshift-data:DescribeStatement",
      "redshift-data:GetStatementResult", "redshift-data:CancelStatement", "redshift-data:ListStatements");

  /** The session policy of reads (metadata, read batches and plans), or {@code null} without one. */
  String readPolicy() {
    return generatedPolicy ? policy(READ_ACTIONS) : null;
  }

  /** The session policy of one approved change, or {@code null} without one. */
  String changePolicy() {
    return generatedPolicy ? policy(CHANGE_ACTIONS) : null;
  }

  /** What the setup helpers list for the form besides the databases: warehouses and secrets, by name only. */
  static final List<String> LIST_ACTIONS = List.of("redshift:DescribeClusters", "redshift-serverless:ListWorkgroups",
      "redshift-serverless:ListNamespaces", "secretsmanager:ListSecrets");

  /**
   * The session policy of the setup helpers' listings, or {@code null} without one: the account's clusters,
   * Serverless workgroups and namespaces and the secrets' names ({@code ListSecrets} returns no value), and the Data
   * API's {@code ListDatabases} on this warehouse with this one way of signing in.
   */
  String suggestPolicy() {
    if (!generatedPolicy) {
      return null;
    }
    ObjectNode policy = policyNode(List.of("redshift-data:ListDatabases"));
    ((ArrayNode) policy.get("Statement")).add(statement(LIST_ACTIONS, List.of("*")));
    return json(policy);
  }

  /** The session policy of the listings alone, while the form doesn't name its warehouse, database or secret yet. */
  static String listPolicy() {
    return AwsSessionPolicy.builder().allow(LIST_ACTIONS, List.of("*")).toJson();
  }

  /**
   * A session policy: {@code actions} on this warehouse only; describing, fetching and cancelling only the statements
   * this role session ran ({@code redshift-data:statement-owner-iam-userid}); and the one way of signing in to the
   * database: temporary credentials of this database user in this database, of the AWS identity in this database or
   * on this workgroup, or reading this one secret.
   */
  String policy(List<String> actions) {
    return json(policyNode(actions));
  }

  private ObjectNode policyNode(List<String> actions) {
    ObjectNode policy = JsonNodeFactory.instance.objectNode().put("Version", "2012-10-17");
    ArrayNode statements = policy.putArray("Statement");
    statements.add(statement(actions, List.of(warehouseArn())));
    ObjectNode owned = statement(STATEMENT_ACTIONS, List.of("*"));
    owned.putObject("Condition").putObject("StringEquals").put("redshift-data:statement-owner-iam-userid",
        "${aws:userid}");
    statements.add(owned);
    String p = partition();
    switch (auth) {
      case DB_USER -> statements.add(statement(List.of("redshift:GetClusterCredentials"), List.of(
          "arn:" + p + ":redshift:" + region + ":*:dbuser:" + cluster + "/" + dbUser,
          "arn:" + p + ":redshift:" + region + ":*:dbname:" + cluster + "/" + database)));
      case IAM -> statements.add(kind == Kind.CLUSTER
          ? statement(List.of("redshift:GetClusterCredentialsWithIAM"), List.of(
              "arn:" + p + ":redshift:" + region + ":*:dbname:" + cluster + "/" + database))
          : statement(List.of("redshift-serverless:GetCredentials"), List.of(warehouseArn())));
      case SECRET -> statements.add(statement(List.of("secretsmanager:GetSecretValue"), List.of(
          SECRET_ARN.matcher(secretArn).matches() ? secretArn
              // A name: Secrets Manager adds six random characters to the ARN of every secret.
              : "arn:" + p + ":secretsmanager:" + region + ":*:secret:" + secretArn + "-??????")));
    }
    return policy;
  }

  /** The policy as STS takes it, at most {@link AwsSessionPolicy#MAX_CHARACTERS}. */
  private static String json(ObjectNode policy) {
    String json = JSON.writeValueAsString(policy);
    if (json.length() > AwsSessionPolicy.MAX_CHARACTERS) {
      throw new IllegalStateException("The session policy has " + json.length() + " characters; STS accepts "
          + AwsSessionPolicy.MAX_CHARACTERS + ".");
    }
    return json;
  }

  private static ObjectNode statement(List<String> actions, List<String> resources) {
    ObjectNode s = JsonNodeFactory.instance.objectNode().put("Effect", "Allow");
    ArrayNode a = s.putArray("Action");
    actions.forEach(a::add);
    ArrayNode r = s.putArray("Resource");
    resources.forEach(r::add);
    return s;
  }
}
