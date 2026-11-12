package ai.msrv.gaterook.connectors.redshift;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import ai.msrv.gaterook.connectors.cloudauth.AwsSpec;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudLocal;
import ai.msrv.gaterook.connectors.redshift.RedshiftApi.Service;
import ai.msrv.gaterook.connectors.redshift.RedshiftApi.Xml;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.w3c.dom.Element;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Help for the Redshift form ({@link ai.msrv.gaterook.connector.api.Connector#suggest}).
 *
 * <ul>
 *   <li>On this computer: the profiles of {@code ~/.aws/config} (the preferred one filled in when the credentials come
 *       from a profile), what keeps the chosen profile from being used, the regions with the profile's region filled
 *       in, and a sign-in that can't work on Serverless.</li>
 *   <li>Signed in as Test connection signs in, under a session that may only list
 *       ({@link RedshiftSettings#suggestPolicy}): the provisioned clusters ({@code DescribeClusters}) and Serverless
 *       workgroups of the region, the secrets for a Secrets Manager sign-in (names and ARNs: {@code ListSecrets}
 *       returns no value), and the databases. An available cluster lists them through the Data API, as Test
 *       connection signs in; a Serverless workgroup offers its namespace's database instead, because any statement
 *       would wake it and bill it, and a paused cluster is never resumed.</li>
 * </ul>
 * A profile that runs a command for its credentials, or can't be used, is never signed in with; the suggestion's
 * sessions are dropped when it is done.
 */
final class RedshiftSuggestions {

  static final String PROFILE = "/credentials/profile";
  static final String REGION = "/region";
  static final String CLUSTER = "/target/cluster";
  static final String WORKGROUP = "/target/workgroup";
  static final String DATABASE = "/database";
  static final String SECRET = "/databaseAuth/secretArn";
  /** Said after what is wrong with a profile: why the form lists nothing from AWS. */
  static final String NO_SIGN_IN = "The setup helpers don't sign in with this profile, so nothing is listed from AWS.";
  /** Pages of 100 a listing reads: as many names as a field offers. */
  private static final int PAGES = 2;
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private RedshiftSuggestions() {}

  /**
   * A cluster or workgroup of the account.
   *
   * @param name     its identifier or name
   * @param value    what the form's field takes: the identifier, or a workgroup's ARN
   * @param ready    whether a statement can run on it now without resuming it
   * @param database its first database ({@code DBName}, the namespace's {@code dbName}), or {@code null}
   */
  private record Warehouse(String name, String value, String status, boolean ready, String database, String detail) {}

  private record Listing(List<Warehouse> items, boolean complete) {}

  static Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope,
      CloudCredentials cloud, CloudLocal local, RedshiftApi api) {
    Suggestions.Builder out = Suggestions.builder();
    if (scope == Suggestions.Scope.SERVICE) {
      service(cfg, secrets, ctx, cloud, api, out);
    } else {
      local(cfg, cloud, local, out);
    }
    return out.build();
  }

  // ── This computer ─────────────────────────────────────────────────────────────────────────────────────────────

  private static void local(ConnectionConfig cfg, CloudCredentials cloud, CloudLocal local, Suggestions.Builder out) {
    boolean fromProfile = "profile".equals(text(cfg, "/credentials/source"));
    String profile = fromProfile ? text(cfg, PROFILE) : null;
    local.aws(out, fromProfile ? PROFILE : null, profile, false, REGION);
    if (!fromProfile) {
      local.preferAwsProfile(out, "/credentials/source", text(cfg, "/credentials/accessKeyId") != null);
    }
    if (fromProfile) {
      if (local.awsProfiles().isEmpty()) {
        out.note("There are no AWS profiles on this computer: run aws configure, or choose Access key.");
      } else if (profile != null) {
        // Which files are read doesn't depend on the region: any valid one makes the spec.
        cloud.profileWarnings(new AwsSpec(AwsSpec.Source.PROFILE, null, null, profile, null, null, 900, "us-east-1"))
            .forEach(out::note);
      }
    }
    if ("serverless".equals(text(cfg, "/target/kind")) && "dbUser".equals(text(cfg, "/databaseAuth/method"))) {
      out.note(RedshiftSettings.DB_USER_ON_SERVERLESS);
    }
  }

  // ── Signed in ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static void service(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, CloudCredentials cloud,
      RedshiftApi api, Suggestions.Builder out) {
    // The warehouse, the database and the secret are listed here (x-suggest): they may still be empty or half typed.
    RedshiftSettings full = null;
    String problem = null;
    try {
      full = RedshiftSettings.of(cfg);
    } catch (ConnectorException e) {
      problem = e.getMessage();
    }
    RedshiftSettings s;
    String policy;
    try {
      s = full != null ? full : partial(cfg);
      policy = full != null ? full.suggestPolicy() : s.generatedPolicy() ? RedshiftSettings.listPolicy() : null;
    } catch (ConnectorException | IllegalStateException e) {
      out.note(e.getMessage());
      return;
    }
    if (full == null && !incomplete(s)) {
      // Nothing the helpers list is missing: the settings themselves can't work.
      out.note(problem);
    }
    if (s.credentials().source() == AwsSpec.Source.PROFILE) {
      List<String> warnings = cloud.profileWarnings(s.credentials());
      if (!warnings.isEmpty()) {
        warnings.forEach(out::note);
        out.note(NO_SIGN_IN);
        return;
      }
    }
    // Its own credentials, dropped afterwards: a form asks again with every change.
    ConnectionConfig own = own(cfg);
    RedshiftApi.Call c = new RedshiftApi.Call(own, s, secrets, ctx);
    List<String> notes = new ArrayList<>();
    String arn = null;
    try {
      try {
        AwsCredentialsProvider provider = CloudCredentials.tainting(cloud.aws(new CloudCredentials.Owner(own.id(),
            own.version(), own.name()), s.credentials(), policy, secrets), ctx::taint);
        arn = cloud.callerIdentity(provider, s.region()).arn();
      } catch (SdkException e) {
        throw ConnectorException.unavailable("The AWS credentials couldn't be loaded: "
            + RedshiftApi.firstLine(e.getMessage()) + ".", e);
      }
      Listing clusters = clusters(api, c, policy);
      Listing workgroups = workgroups(api, c, policy);
      offer(out, CLUSTER, clusters);
      offer(out, WORKGROUP, workgroups);
      boolean cluster = s.kind() == RedshiftSettings.Kind.CLUSTER;
      Warehouse w = find(cluster ? clusters : workgroups, cluster ? s.cluster() : s.workgroup());
      String databases = null;
      if (w == null) {
        missing(s, clusters, workgroups, notes);
      } else if (!w.ready()) {
        String status = w.status().isEmpty() ? "not available" : w.status().toLowerCase(Locale.ROOT);
        notes.add(!cluster ? "Workgroup " + w.name() + " is " + status + ": queries run once it is available."
            : "paused".equals(status) ? "Cluster " + w.name() + " is paused: resume it to list its databases."
            : "Cluster " + w.name() + " is " + status + ": its databases appear once it is available.");
      } else if (cluster && full != null) {
        databases = databases(api, c, policy, w, out, notes);
      }
      if (databases == null && w != null && w.database() != null) {
        // Its first database: the one to connect to when the form's can't be listed, or on Serverless.
        out.value(DATABASE, cluster ? "the cluster" : "the namespace",
            w.database().equals(s.database()) ? null : w.database(), List.of(Option.of(w.database())));
      }
      if (s.auth() == RedshiftSettings.Auth.SECRET) {
        secrets(api, c, policy, out, notes);
      }
      out.note("Signed in as " + arn + "." + (databases == null ? "" : " " + databases));
    } catch (ConnectorException e) {
      out.note(arn == null ? e.getMessage() : "Signed in as " + arn + ". " + e.getMessage());
    } finally {
      cloud.evict(own.id());
    }
    notes.forEach(out::note);
  }

  private static void offer(Suggestions.Builder out, String pointer, Listing l) {
    if (l == null || l.items().isEmpty()) {
      return;
    }
    List<Option> options = l.items().stream().map(w -> new Option(w.value(), w.value().equals(w.name()) ? null
        : w.name(), w.detail())).toList();
    out.field(pointer, new Suggestions.Field(options, l.complete() && l.items().size() == 1
        ? l.items().getFirst().value() : null, "the account", l.complete()));
  }

  /** The cluster or workgroup the form names, by identifier, name or ARN; {@code null} when the list lacks it. */
  private static Warehouse find(Listing l, String named) {
    if (l == null || named == null) {
      return null;
    }
    for (Warehouse w : l.items()) {
      if (w.name().equals(named) || w.value().equals(named)) {
        return w;
      }
    }
    return null;
  }

  /**
   * The draft's settings while a field the helpers list is still empty or half typed (the warehouse, the database, the
   * secret): such a field is {@code null}. What signs in must be complete.
   */
  static RedshiftSettings partial(ConnectionConfig cfg) throws ConnectorException {
    String region = cfg.string("/region");
    if (region == null || !RedshiftSettings.REGION.matcher(region).matches()) {
      throw ConnectorException.badInput("Choose the AWS region of the warehouse, such as eu-central-1.");
    }
    RedshiftSettings.Kind kind = "serverless".equals(cfg.string("/target/kind")) ? RedshiftSettings.Kind.SERVERLESS
        : RedshiftSettings.Kind.CLUSTER;
    String cluster = matching(cfg.string("/target/cluster"), RedshiftSettings.CLUSTER);
    String workgroup = matching(cfg.string("/target/workgroup"), RedshiftSettings.WORKGROUP);
    if (workgroup == null) {
      workgroup = matching(cfg.string("/target/workgroup"), RedshiftSettings.WORKGROUP_ARN);
    }
    String method = cfg.string("/databaseAuth/method");
    RedshiftSettings.Auth auth = "dbUser".equals(method) ? RedshiftSettings.Auth.DB_USER : "secret".equals(method)
        ? RedshiftSettings.Auth.SECRET : RedshiftSettings.Auth.IAM;
    String secret = matching(cfg.string("/databaseAuth/secretArn"), RedshiftSettings.SECRET_ARN);
    if (secret == null) {
      secret = matching(cfg.string("/databaseAuth/secretArn"), RedshiftSettings.SECRET_NAME);
    }
    AwsSpec spec;
    try {
      spec = AwsSpec.fromConfig(cfg.config(), "/credentials", region);
    } catch (IllegalArgumentException e) {
      throw ConnectorException.badInput("The AWS credentials of " + cfg.name() + " aren't complete: " + e.getMessage()
          + ".");
    }
    return new RedshiftSettings(region, kind, kind == RedshiftSettings.Kind.CLUSTER && cluster != null
        && cluster.length() <= 63 ? cluster : null, kind == RedshiftSettings.Kind.SERVERLESS ? workgroup : null,
        matching(cfg.string("/database"), RedshiftSettings.DATABASE), auth,
        matching(cfg.string("/databaseAuth/dbUser"), RedshiftSettings.DB_USER_NAME), secret, spec,
        !"none".equals(cfg.string("/credentials/sessionPolicy")));
  }

  /** Whether a field the helpers list is still missing: the warehouse, the database, or a sign-in's secret. */
  static boolean incomplete(RedshiftSettings s) {
    return (s.kind() == RedshiftSettings.Kind.CLUSTER ? s.cluster() == null : s.workgroup() == null)
        || s.database() == null || s.auth() == RedshiftSettings.Auth.SECRET && s.secretArn() == null;
  }

  private static String matching(String value, Pattern pattern) {
    return value != null && pattern.matcher(value.strip()).matches() ? value.strip() : null;
  }

  /**
   * What to say when the region lacks the warehouse the form names (as far as the lists say), or has none of its kind
   * while nothing is named yet.
   */
  private static void missing(RedshiftSettings s, Listing clusters, Listing workgroups, List<String> notes) {
    boolean cluster = s.kind() == RedshiftSettings.Kind.CLUSTER;
    Listing same = cluster ? clusters : workgroups;
    Listing other = cluster ? workgroups : clusters;
    if (same == null || !same.complete()) {
      return;
    }
    String named = cluster ? s.cluster() : s.workgroup();
    if (!same.items().isEmpty()) {
      // Nothing named yet: the list under the field is the suggestion.
      if (named != null) {
        notes.add("There is no " + (cluster ? "cluster " : "Serverless workgroup ") + named + " in " + s.region()
            + ": choose one of " + names(same) + ".");
      }
    } else if (other != null && !other.items().isEmpty()) {
      notes.add("There is no " + (cluster ? "provisioned cluster" : "Serverless workgroup") + " in " + s.region()
          + ", but " + (cluster ? "Serverless workgroups: " : "provisioned clusters: ") + names(other)
          + ". Choose " + (cluster ? "Redshift Serverless workgroup" : "Provisioned cluster") + " under Type.");
    } else if (other != null) {
      notes.add("There are no Redshift clusters or Serverless workgroups in " + s.region() + ".");
    }
  }

  private static String names(Listing l) {
    List<String> names = l.items().stream().map(Warehouse::name).toList();
    return String.join(", ", names.subList(0, Math.min(5, names.size()))) + (names.size() > 5 ? ", …" : "");
  }

  /** The provisioned clusters of the region, or {@code null} when the credentials may not list them. */
  private static Listing clusters(RedshiftApi api, RedshiftApi.Call c, String policy) throws ConnectorException {
    List<Warehouse> out = new ArrayList<>();
    String marker = null;
    for (int page = 0; page < PAGES; page++) {
      Map<String, String> params = new LinkedHashMap<>();
      params.put("MaxRecords", "100");
      if (marker != null) {
        params.put("Marker", marker);
      }
      Element root;
      try {
        root = api.query(c, "DescribeClusters", params, policy);
      } catch (ConnectorException e) {
        return denied(e);
      }
      Element result = Xml.child(root, "DescribeClustersResult");
      Element list = result == null ? null : Xml.child(result, "Clusters");
      for (Element cl : list == null ? List.<Element>of() : Xml.children(list, "Cluster")) {
        String id = Xml.text(cl, "ClusterIdentifier");
        if (id == null || !RedshiftSettings.CLUSTER.matcher(id).matches()) {
          continue;
        }
        String status = orEmpty(Xml.text(cl, "ClusterStatus"));
        String availability = Xml.text(cl, "ClusterAvailabilityStatus");
        boolean ready = availability != null ? availability.equalsIgnoreCase("Available")
            : status.toLowerCase(Locale.ROOT).startsWith("available");
        String db = Xml.text(cl, "DBName");
        String nodes = orEmpty(Xml.text(cl, "NumberOfNodes"));
        String type = orEmpty(Xml.text(cl, "NodeType"));
        out.add(new Warehouse(id, id, status, ready, db != null && RedshiftSettings.DATABASE.matcher(db).matches()
            ? db : null, status + (type.isEmpty() ? "" : " · " + type + (nodes.isEmpty() ? "" : " × " + nodes))));
      }
      marker = result == null ? null : Xml.text(result, "Marker");
      if (marker == null || marker.isEmpty()) {
        return new Listing(out, true);
      }
      if (c.ctx().remaining().compareTo(Duration.ofSeconds(1)) < 0) {
        break;
      }
    }
    return new Listing(out, false);
  }

  /** The Serverless workgroups of the region with their namespaces' databases, or {@code null} when not allowed. */
  private static Listing workgroups(RedshiftApi api, RedshiftApi.Call c, String policy) throws ConnectorException {
    List<JsonNode> items = new ArrayList<>();
    boolean complete;
    try {
      complete = pages(api, c, policy, "ListWorkgroups", "workgroups", items);
    } catch (ConnectorException e) {
      return denied(e);
    }
    Map<String, String> databases = new HashMap<>();
    if (!items.isEmpty()) {
      List<JsonNode> namespaces = new ArrayList<>();
      try {
        pages(api, c, policy, "ListNamespaces", "namespaces", namespaces);
      } catch (ConnectorException e) {
        // Without the namespaces the workgroups still show, without their databases.
        denied(e);
      }
      for (JsonNode ns : namespaces) {
        String db = ns.path("dbName").asString("");
        if (RedshiftSettings.DATABASE.matcher(db).matches()) {
          databases.put(ns.path("namespaceName").asString(""), db);
        }
      }
    }
    List<Warehouse> out = new ArrayList<>();
    for (JsonNode wg : items) {
      String name = wg.path("workgroupName").asString("");
      if (!RedshiftSettings.WORKGROUP.matcher(name).matches()) {
        continue;
      }
      String arn = wg.path("workgroupArn").asString("");
      String status = wg.path("status").asString("");
      String namespace = wg.path("namespaceName").asString("");
      out.add(new Warehouse(name, RedshiftSettings.WORKGROUP_ARN.matcher(arn).matches() ? arn : name, status,
          "AVAILABLE".equals(status), databases.get(namespace), status.toLowerCase(Locale.ROOT)
              + (namespace.isEmpty() ? "" : " · namespace " + namespace)));
    }
    return new Listing(out, complete);
  }

  /**
   * The databases of an available cluster through the Data API, signed in as the form says; the note's text, or
   * {@code null} when they can't be listed (a note says why).
   */
  private static String databases(RedshiftApi api, RedshiftApi.Call c, String policy, Warehouse w,
      Suggestions.Builder out, List<String> notes) throws ConnectorException {
    RedshiftSettings s = c.settings();
    List<Option> options = new ArrayList<>();
    String token = null;
    boolean complete = false;
    try {
      for (int page = 0; page < PAGES; page++) {
        ObjectNode req = s.target().put("MaxResults", 100);
        if (token != null) {
          req.put("NextToken", token);
        }
        JsonNode r = api.call(c, "ListDatabases", req, policy, false);
        for (JsonNode d : r.path("Databases")) {
          String name = d.asString("");
          if (RedshiftSettings.DATABASE.matcher(name).matches()) {
            options.add(Option.of(name));
          }
        }
        token = r.path("NextToken").asString(null);
        if (token == null || token.isEmpty()) {
          complete = true;
          break;
        }
        if (c.ctx().remaining().compareTo(Duration.ofSeconds(1)) < 0) {
          break;
        }
      }
    } catch (ConnectorException e) {
      if (e.kind() == ConnectorException.Kind.TIMEOUT) {
        throw e;
      }
      notes.add(e.getMessage());
      return null;
    }
    out.field(DATABASE, new Suggestions.Field(options, null, "the cluster", complete));
    return options.size() + (complete ? "" : "+") + (options.size() == 1 ? " database" : " databases") + " on "
        + w.name() + ": choose the one the Data API connects to.";
  }

  /** The secrets of the region for a Secrets Manager sign-in, those made for Redshift first; names and ARNs only. */
  private static void secrets(RedshiftApi api, RedshiftApi.Call c, String policy, Suggestions.Builder out,
      List<String> notes) throws ConnectorException {
    RedshiftSettings s = c.settings();
    List<JsonNode> items = new ArrayList<>();
    boolean complete;
    try {
      complete = pages(api, c, policy, "ListSecrets", "SecretList", items);
    } catch (ConnectorException e) {
      denied(e);
      return;
    }
    record Found(Option option, boolean redshift) {}
    List<Found> found = new ArrayList<>();
    boolean named = false;
    for (JsonNode secret : items) {
      String arn = secret.path("ARN").asString("");
      String name = secret.path("Name").asString("");
      if (!RedshiftSettings.SECRET_ARN.matcher(arn).matches()) {
        continue;
      }
      named |= arn.equals(s.secretArn()) || name.equals(s.secretArn());
      boolean managed = "redshift".equalsIgnoreCase(secret.path("OwningService").asString(""));
      boolean tagged = false;
      for (JsonNode tag : secret.path("Tags")) {
        tagged |= "RedshiftDataFullAccess".equals(tag.path("Key").asString(""));
      }
      String description = secret.path("Description").asString("").strip();
      String detail = managed ? "managed by Redshift" : tagged ? "tagged RedshiftDataFullAccess"
          : description.isEmpty() ? null : cut(description, 120);
      found.add(new Found(new Option(arn, name.isEmpty() ? null : name, detail), managed || tagged
          || name.toLowerCase(Locale.ROOT).contains("redshift")));
    }
    found.sort(Comparator.comparing(f -> !f.redshift()));
    out.field(SECRET, new Suggestions.Field(found.stream().map(Found::option).toList(), null, "Secrets Manager",
        complete));
    if (complete && !named && s.secretArn() != null) {
      notes.add("There is no secret " + s.secretArn() + " in " + s.region() + ".");
    }
  }

  /** Up to {@link #PAGES} pages of a JSON listing; whether that is all of it. */
  private static boolean pages(RedshiftApi api, RedshiftApi.Call c, String policy, String operation, String items,
      List<JsonNode> out) throws ConnectorException {
    Service service = operation.equals("ListSecrets") ? Service.SECRETS : Service.SERVERLESS;
    String token = null;
    for (int page = 0; page < PAGES; page++) {
      ObjectNode req = service == Service.SECRETS ? F.objectNode().put("MaxResults", 100)
          : F.objectNode().put("maxResults", 100);
      if (token != null) {
        req.put(service == Service.SECRETS ? "NextToken" : "nextToken", token);
      }
      JsonNode r = api.read(c, service, operation, req, policy);
      r.path(items).forEach(out::add);
      token = r.path(service == Service.SECRETS ? "NextToken" : "nextToken").asString(null);
      if (token == null || token.isEmpty()) {
        return true;
      }
      if (c.ctx().remaining().compareTo(Duration.ofSeconds(1)) < 0) {
        break;
      }
    }
    return false;
  }

  /** A listing the credentials may not read is left out ({@code null}); other failures stop the suggestions. */
  private static <T> T denied(ConnectorException e) throws ConnectorException {
    if (e.kind() == ConnectorException.Kind.RESOURCE_ERROR || e.kind() == ConnectorException.Kind.BAD_INPUT) {
      return null;
    }
    throw e;
  }

  private static String orEmpty(String s) {
    return s == null ? "" : s;
  }

  private static String cut(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }

  /** The draft under an owner of its own: the credentials a suggestion makes are dropped afterwards. */
  static ConnectionConfig own(ConnectionConfig cfg) {
    return new ConnectionConfig(cfg.id() + "#suggest", cfg.name(), cfg.type(), cfg.env(), cfg.version(), cfg.config(),
        cfg.secretFields(), cfg.metadata());
  }

  private static String text(ConnectionConfig cfg, String pointer) {
    String v = cfg.string(pointer);
    return v == null || v.isBlank() ? null : v.strip();
  }
}
