package com.gaterook.connectors.athena;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connectors.cloudauth.AwsSessionPolicy;
import com.gaterook.connectors.cloudauth.AwsSpec;
import com.gaterook.connectors.support.Glob;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The settings of one Athena connection ({@code config.schema.json}): the region and workgroup, the session's catalog
 * and database, the databases agents may use, where results go, and the credentials of connectors/cloud-auth (as the
 * DynamoDB connector's: an access key or a profile, optionally a role with an external ID and a generated session
 * policy).
 *
 * @param catalog         the session catalog ({@code AwsDataCatalog} by default)
 * @param database        the session database, or {@code null}
 * @param databases       globs of {@code database} (in {@link #catalog}) or {@code catalog.database}; empty: every
 *                        database the credentials may read
 * @param outputLocation  {@code s3://bucket/prefix/} for results, or {@code null}: the workgroup's
 * @param generatedPolicy with a role: each call's session policy allows what it needs and nothing else
 */
record AthenaSettings(String region, String workgroup, String catalog, String database, List<String> databases,
    String outputLocation, AwsSpec credentials, boolean generatedPolicy) {

  static final String DEFAULT_CATALOG = "AwsDataCatalog";
  private static final Pattern REGION = Pattern.compile("[a-z]{2}(-[a-z]+)+-\\d+");
  static final Pattern WORKGROUP = Pattern.compile("[a-zA-Z0-9._-]{1,128}");
  /** A data catalog: {@code AwsDataCatalog}, a federated catalog, {@code s3tablescatalog/bucket}. */
  static final Pattern CATALOG = Pattern.compile("[A-Za-z0-9_/:-]{1,256}");
  static final Pattern DATABASE = Pattern.compile("[A-Za-z0-9_-]{1,255}");
  private static final Pattern GLOB = Pattern.compile("([A-Za-z0-9_/:*?-]{1,256}\\.)?[A-Za-z0-9_*?-]{1,255}");
  private static final Pattern S3 = Pattern.compile("s3://([a-z0-9][a-z0-9.-]{1,61}[a-z0-9])(/[^\\p{Cntrl}]*)?");

  static AthenaSettings of(ConnectionConfig cfg) throws ConnectorException {
    String region = cfg.string("/region");
    if (region == null || !REGION.matcher(region).matches()) {
      throw ConnectorException.badInput("Choose the AWS region of the workgroup, such as eu-central-1.");
    }
    String workgroup = blankToNull(cfg.string("/workgroup"));
    workgroup = workgroup == null ? "primary" : workgroup;
    if (!WORKGROUP.matcher(workgroup).matches()) {
      throw ConnectorException.badInput(workgroup + " isn't a workgroup name: letters, digits, ., _ and -.");
    }
    String catalog = blankToNull(cfg.string("/catalog"));
    catalog = catalog == null ? DEFAULT_CATALOG : catalog;
    if (!CATALOG.matcher(catalog).matches()) {
      throw ConnectorException.badInput(catalog + " isn't a data catalog name.");
    }
    String database = blankToNull(cfg.string("/database"));
    if (database != null && !DATABASE.matcher(database).matches()) {
      throw ConnectorException.badInput(database + " isn't a database name: letters, digits, _ and -.");
    }
    List<String> databases = List.copyOf(cfg.strings("/databases"));
    for (String glob : databases) {
      if (!GLOB.matcher(glob).matches()) {
        throw ConnectorException.badInput(glob + " isn't a database or catalog.database pattern.");
      }
    }
    String output = blankToNull(cfg.string("/outputLocation"));
    if (output != null && !S3.matcher(output).matches()) {
      throw ConnectorException.badInput("The output location must be an S3 URI such as s3://acme-athena-results/"
          + "gaterook/.");
    }
    AwsSpec spec;
    try {
      spec = AwsSpec.fromConfig(cfg.config(), "/credentials", region);
    } catch (IllegalArgumentException e) {
      throw ConnectorException.badInput("The AWS credentials of " + cfg.name() + " aren't complete: " + e.getMessage()
          + ".");
    }
    AthenaSettings s = new AthenaSettings(region, workgroup, catalog, database, databases, output, spec,
        !"none".equals(cfg.string("/credentials/sessionPolicy")));
    if (database != null && !s.allows(catalog, database)) {
      throw ConnectorException.badInput("The default database " + database + " isn't one of the databases agents may "
          + "use: add it to Databases or choose another.");
    }
    return s;
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  /** Whether agents may use {@code catalog.database}: on the list, or any when the list is empty. Case-insensitive. */
  boolean allows(String catalogName, String databaseName) {
    if (databases.isEmpty()) {
      return true;
    }
    if (catalogName == null || databaseName == null) {
      return false;
    }
    String c = catalogName.toLowerCase(Locale.ROOT);
    String d = databaseName.toLowerCase(Locale.ROOT);
    for (String entry : databases) {
      int dot = entry.lastIndexOf('.');
      String ec = (dot < 0 ? catalog : entry.substring(0, dot)).toLowerCase(Locale.ROOT);
      String ed = (dot < 0 ? entry : entry.substring(dot + 1)).toLowerCase(Locale.ROOT);
      if (Glob.text(ec).matches(c) && Glob.text(ed).matches(d)) {
        return true;
      }
    }
    return false;
  }

  /** Whether any database of {@code catalogName} may be used: what {@code athena_list_catalogs} shows. */
  boolean allowsCatalog(String catalogName) {
    if (databases.isEmpty()) {
      return true;
    }
    String c = catalogName.toLowerCase(Locale.ROOT);
    for (String entry : databases) {
      int dot = entry.lastIndexOf('.');
      if (Glob.text((dot < 0 ? catalog : entry.substring(0, dot)).toLowerCase(Locale.ROOT)).matches(c)) {
        return true;
      }
    }
    return false;
  }

  /** The catalog of a name: its own, or the session's. */
  String catalogOf(AthenaSql.Ref ref) {
    String own = ref.name().catalog(ref.role());
    return own != null ? own : catalog.toLowerCase(Locale.ROOT);
  }

  /** The database of a name: its own, or the session's; {@code null} when it names none and there is none. */
  String databaseOf(AthenaSql.Ref ref) {
    String own = ref.name().database(ref.role());
    return own != null ? own : database == null ? null : database.toLowerCase(Locale.ROOT);
  }

  /** {@code https://athena.<region>.amazonaws.com}, or {@code .amazonaws.com.cn} in China. */
  String endpoint() {
    return "https://athena." + region + ".amazonaws.com" + (region.startsWith("cn-") ? ".cn" : "");
  }

  /** The partition of the region's ARNs. */
  String partition() {
    return region.startsWith("cn-") ? "aws-cn" : region.startsWith("us-gov-") ? "aws-us-gov" : "aws";
  }

  // ── Session policies ────────────────────────────────────────────────────────────────────────────────────────

  /** Queries start, stop, report and return results in the connection's workgroup only. */
  static final List<String> QUERY_ACTIONS = List.of("athena:StartQueryExecution", "athena:StopQueryExecution",
      "athena:GetQueryExecution", "athena:GetQueryResults", "athena:GetWorkGroup");
  /** Athena's metadata reads: catalogs, databases, tables. No query of another workgroup. */
  static final List<String> ATHENA_READ = List.of("athena:List*", "athena:GetDataCatalog", "athena:GetDatabase",
      "athena:GetTableMetadata");
  /** Glue's catalog and database names: Athena's listings read them for every database. */
  static final List<String> GLUE_NAMES = List.of("glue:GetCatalog*", "glue:GetDatabase*");
  /** Glue's tables and partitions: only of the listed databases. */
  static final List<String> GLUE_TABLES = List.of("glue:GetTable*", "glue:GetPartition*", "glue:BatchGetPartition");
  /**
   * S3 reads of the tables' data (wherever their locations are; the role's own permissions bound them) and of the
   * results, the listing of a results upload in parts, and Lake Formation's credentials for data it governs.
   */
  static final List<String> DATA_READ = List.of("s3:GetObject", "s3:ListBucket", "s3:GetBucketLocation",
      "s3:ListBucketMultipartUploads", "lakeformation:GetDataAccess");
  /** Athena writes a query's results: to the results location only. */
  static final List<String> S3_RESULTS = List.of("s3:PutObject", "s3:AbortMultipartUpload",
      "s3:ListMultipartUploadParts");
  /** Glue changes of an approved statement (tables, partitions, the database itself), in its own database only. */
  static final List<String> GLUE_CHANGES = List.of("glue:*Table", "glue:*Partition", "glue:*Database");
  static final List<String> S3_CHANGES = List.of("s3:PutObject", "s3:DeleteObject", "s3:AbortMultipartUpload",
      "s3:ListMultipartUploadParts");

  /** The workgroup's ARN: queries start and stop only there. */
  String workgroupArn() {
    return "arn:" + partition() + ":athena:" + region + ":*:workgroup/" + workgroup;
  }

  private String glue(String resource) {
    return "arn:" + partition() + ":glue:" + region + ":*:" + resource;
  }

  /** {@code database/<db>} and {@code table/<db>/*} of each listed database of the session's catalog, or all. */
  private List<String> glueTables(List<String> names) {
    Set<String> out = new LinkedHashSet<>();
    out.add(glue("catalog"));
    for (String name : names) {
      out.add(glue("database/" + name));
      out.add(glue("table/" + name + "/*"));
    }
    return new ArrayList<>(out);
  }

  /**
   * The databases of the list in the Glue catalog ({@code AwsDataCatalog}, named or the session's), as IAM globs: all
   * ({@code *}) without a list; none when the list names only other catalogs.
   */
  private List<String> glueDatabases() {
    if (databases.isEmpty()) {
      return List.of("*");
    }
    List<String> out = new ArrayList<>();
    for (String entry : databases) {
      int dot = entry.lastIndexOf('.');
      if ((dot < 0 ? catalog : entry.substring(0, dot)).equalsIgnoreCase(DEFAULT_CATALOG)) {
        out.add((dot < 0 ? entry : entry.substring(dot + 1)).toLowerCase(Locale.ROOT));
      }
    }
    return out;
  }

  /** {@code arn:aws:s3:::bucket/prefix*} of an S3 output location. */
  String resultsArn(String location) {
    var m = S3.matcher(location);
    if (!m.matches()) {
      throw new IllegalArgumentException("Not an S3 URI");
    }
    String prefix = m.group(2) == null ? "/" : m.group(2);
    return "arn:" + partition() + ":s3:::" + m.group(1) + prefix + (prefix.endsWith("*") ? "" : "*");
  }

  /**
   * The session policy of what reading needs ({@code null} without a generated one): queries in this workgroup only,
   * Athena's reads, Glue's names, Glue's tables of the listed databases, S3 reads, Lake Formation's data access, and S3
   * writes only to the results location ({@code null}: results Athena keeps itself). No Glue or S3 change elsewhere:
   * AWS refuses a write a read call might attempt. STS takes at most 2,048 characters; a database list too long for
   * that keeps the actions and allows the tables of every database, still with no write.
   */
  String readPolicy(String resultsLocation) {
    if (!generatedPolicy) {
      return null;
    }
    try {
      return read(resultsLocation, glueTables(glueDatabases())).toJson();
    } catch (IllegalStateException tooLong) {
      return read(resultsLocation, glueTables(List.of("*"))).toJson();
    }
  }

  private AwsSessionPolicy read(String resultsLocation, List<String> tables) {
    AwsSessionPolicy p = AwsSessionPolicy.builder()
        .allow(QUERY_ACTIONS, List.of(workgroupArn()))
        .allow(ATHENA_READ, List.of("*"))
        .allow(GLUE_NAMES, List.of(glue("catalog"), glue("database/*")))
        .allow(GLUE_TABLES, tables)
        .allow(DATA_READ, List.of("*"));
    if (resultsLocation != null) {
      p.allow(S3_RESULTS, List.of(resultsArn(resultsLocation)));
    }
    return p;
  }

  /**
   * The session policy of one approved change: what reading needs, and Glue changes in the target's database only.
   * Athena writes a table's files where its location says, so the S3 writes of a change follow the role's own
   * permissions; {@code null} without a generated policy.
   */
  String writePolicy(String database, String resultsLocation) {
    if (!generatedPolicy) {
      return null;
    }
    String db = database == null ? "*" : database.toLowerCase(Locale.ROOT);
    List<String> target = List.of(glue("catalog"), glue("database/" + db), glue("table/" + db + "/*"));
    try {
      return read(resultsLocation, glueTables(glueDatabases())).allow(GLUE_CHANGES, target)
          .allow(S3_CHANGES, List.of("*")).toJson();
    } catch (IllegalStateException tooLong) {
      return read(resultsLocation, glueTables(List.of("*"))).allow(GLUE_CHANGES, target)
          .allow(S3_CHANGES, List.of("*")).toJson();
    }
  }

  /** The session policy of reading the workgroup's own settings, before the results location is known. */
  String inspectPolicy() {
    if (!generatedPolicy) {
      return null;
    }
    return AwsSessionPolicy.builder().allow(List.of("athena:GetWorkGroup"), List.of(workgroupArn())).toJson();
  }

  /** What the setup helpers list for the form: workgroups, data catalogs and databases, and no query. */
  static final List<String> LIST_ACTIONS = List.of("athena:ListWorkGroups", "athena:GetWorkGroup",
      "athena:ListDataCatalogs", "athena:ListDatabases");

  /**
   * The session policy of the setup helpers' listings ({@code null} without a generated one): the names of the
   * account's workgroups, catalogs and databases, the settings of a workgroup, and the Glue names Athena's listings
   * read; no query, no table, no data.
   */
  String listPolicy() {
    if (!generatedPolicy) {
      return null;
    }
    return AwsSessionPolicy.builder().allow(LIST_ACTIONS, List.of("*"))
        .allow(GLUE_NAMES, List.of(glue("catalog"), glue("database/*"))).toJson();
  }
}
