package ai.msrv.gaterook.connectors.s3;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import ai.msrv.gaterook.connectors.cloudauth.AwsSpec;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudLocal;
import ai.msrv.gaterook.connectors.support.LocalPorts;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Element;
import tools.jackson.databind.node.ObjectNode;

/**
 * Help for the S3 form ({@link ai.msrv.gaterook.connector.api.Connector#suggest}).
 *
 * <ul>
 *   <li>Amazon S3, on this computer: the profiles of {@code ~/.aws/config} (the preferred one filled in when the
 *       credentials come from a profile), what keeps the chosen profile from being used, the regions with the
 *       profile's region filled in, and an S3-compatible server listening on this computer's usual ports (MinIO,
 *       LocalStack) as an endpoint to pick.</li>
 *   <li>S3-compatible storage: what the endpoint's host says about its provider (docs/s3.md): the region R2, B2,
 *       Wasabi, Hetzner and the others name, filled in, and path style off where the provider wants the bucket in the
 *       host name.</li>
 *   <li>Signed in as Test connection signs in (the same credentials, the same read session of a role): the buckets
 *       for Buckets, from {@code ListBuckets}. On Amazon S3 without buckets in the region, or while Region is still
 *       empty ({@code x-suggest}: the account is then asked in the region the AWS CLI would use), the regions where
 *       the account's buckets are.</li>
 * </ul>
 * A profile that runs a command for its credentials, or can't be used, is never signed in with; the suggestion's
 * sessions are dropped when it is done.
 */
final class S3Suggestions {

  static final String ENDPOINT = "/endpoint";
  static final String REGION = "/region";
  static final String PATH_STYLE = "/pathStyle";
  static final String BUCKETS = "/buckets";
  static final String PROFILE = "/credentials/profile";
  /** Said after what is wrong with a profile: why the form lists nothing from AWS. */
  static final String NO_SIGN_IN = "The setup helpers don't sign in with this profile, so nothing is listed from AWS.";
  private static final Pattern AWS_REGION = Pattern.compile("[a-z]{2}(-[a-z]+)+-\\d+");

  /** What an endpoint's host says: its provider, the region it names, whether it wants virtual-hosted requests. */
  record Provider(String name, String region, boolean virtualHosted) {}

  /** The providers of docs/s3.md whose host names the region (group 1) or that take one fixed region. */
  private static final List<Rule> RULES = List.of(
      new Rule("Cloudflare R2", Pattern.compile("[a-z0-9.-]+\\.r2\\.cloudflarestorage\\.com"), "auto", false),
      new Rule("DigitalOcean Spaces", Pattern.compile("[a-z0-9-]+\\.digitaloceanspaces\\.com"), "us-east-1", true),
      new Rule("Backblaze B2", Pattern.compile("s3\\.([a-z0-9-]+)\\.backblazeb2\\.com"), null, false),
      new Rule("Wasabi", Pattern.compile("s3\\.([a-z0-9-]+)\\.wasabisys\\.com"), null, false),
      new Rule("Wasabi", Pattern.compile("s3\\.wasabisys\\.com"), "us-east-1", false),
      new Rule("Hetzner Object Storage", Pattern.compile("([a-z0-9-]+)\\.your-objectstorage\\.com"), null, true),
      new Rule("OVHcloud", Pattern.compile("s3\\.([a-z0-9-]+)\\.io\\.cloud\\.ovh\\.net"), null, false),
      new Rule("Scaleway", Pattern.compile("s3\\.([a-z0-9-]+)\\.scw\\.cloud"), null, false),
      new Rule("Akamai (Linode)", Pattern.compile("[a-z0-9-]+\\.linodeobjects\\.com"), "us-east-1", false),
      new Rule("Vultr", Pattern.compile("[a-z0-9-]+\\.vultrobjects\\.com"), "us-east-1", true),
      new Rule("Oracle Cloud", Pattern.compile("[a-z0-9-]+\\.compat\\.objectstorage\\.([a-z0-9-]+)\\.oraclecloud\\.com"),
          null, false),
      new Rule("IBM Cloud Object Storage",
          Pattern.compile("s3\\.(?:private\\.|direct\\.)?([a-z0-9-]+)\\.cloud-object-storage\\.appdomain\\.cloud"), null,
          false),
      new Rule("Google Cloud Storage", Pattern.compile("storage\\.googleapis\\.com"), "auto", false));

  private record Rule(String name, Pattern host, String region, boolean virtualHosted) {}

  /** S3-compatible servers people run on their own computer, by their default port. */
  private static final Map<Integer, String> LOCAL_SERVERS = Map.of(9000, "MinIO", 4566, "LocalStack");

  private S3Suggestions() {}

  static Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope,
      CloudCredentials cloud, CloudLocal local, S3Api api) {
    Suggestions.Builder out = Suggestions.builder();
    if (scope == Suggestions.Scope.SERVICE) {
      service(cfg, secrets, ctx, cloud, local, api, out);
    } else {
      local(cfg, cloud, local, out);
    }
    return out.build();
  }

  // ── This computer ─────────────────────────────────────────────────────────────────────────────────────────────

  private static void local(ConnectionConfig cfg, CloudCredentials cloud, CloudLocal local, Suggestions.Builder out) {
    String endpoint = text(cfg, ENDPOINT);
    if (endpoint != null) {
      endpoint(endpoint, out);
      return;
    }
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
    List<Option> servers = new ArrayList<>();
    for (int port : List.of(9000, 4566)) {
      if (LocalPorts.listening(port)) {
        servers.add(new Option("http://localhost:" + port, null, "A server answers on port " + port + ", "
            + LOCAL_SERVERS.get(port) + "'s default"));
      }
    }
    out.options(ENDPOINT, "this computer", servers);
  }

  /** The region and addressing the provider of {@code endpoint} names; nothing for a server docs/s3.md doesn't know. */
  private static void endpoint(String endpoint, Suggestions.Builder out) {
    String host = host(endpoint);
    if (host == null) {
      return;
    }
    if (host.endsWith(".amazonaws.com") || host.endsWith(".amazonaws.com.cn") || host.equals("amazonaws.com")) {
      out.note("For Amazon S3, leave Endpoint empty: GateRook uses the region's endpoint.");
      return;
    }
    Provider p = provider(host);
    if (p == null) {
      return;
    }
    String source = "the " + p.name() + " endpoint";
    out.value(REGION, source, p.region(), List.of(Option.of(p.region())));
    if (p.virtualHosted()) {
      out.value(PATH_STYLE, source, "false", List.of(Option.of("false")));
    }
  }

  /** The provider of an endpoint's host ({@code fsn1.your-objectstorage.com}), or {@code null}. */
  static Provider provider(String host) {
    String h = host.toLowerCase(Locale.ROOT);
    for (Rule r : RULES) {
      Matcher m = r.host().matcher(h);
      if (m.matches()) {
        String region = r.region() != null ? r.region() : m.group(1);
        return new Provider(r.name(), region, r.virtualHosted());
      }
    }
    return null;
  }

  /** The host of a typed endpoint, with or without its scheme yet; {@code null} while it has none. */
  static String host(String endpoint) {
    try {
      String host = URI.create(endpoint.contains("://") ? endpoint : "https://" + endpoint).getHost();
      return host == null || host.isBlank() ? null : host.toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  // ── Signed in ─────────────────────────────────────────────────────────────────────────────────────────────────

  private record Listing(List<Bucket> buckets, boolean complete) {}

  private record Bucket(String name, String region) {}

  private static void service(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, CloudCredentials cloud,
      CloudLocal local, S3Api api, Suggestions.Builder out) {
    // Amazon S3's region is listed here too (x-suggest): while it is empty or half typed, the account is asked in the
    // region the AWS CLI would use, and the regions of its buckets are offered.
    String chosen = null;
    ConnectionConfig probe = cfg;
    if (text(cfg, ENDPOINT) == null) {
      String region = text(cfg, REGION);
      if (region != null && AWS_REGION.matcher(region).matches()) {
        chosen = region;
      } else {
        probe = withRegion(cfg, cliRegion(cfg, local));
      }
    }
    S3Settings s;
    try {
      s = S3Settings.of(probe);
    } catch (ConnectorException e) {
      out.note(e.getMessage());
      return;
    }
    if (s.aws() && s.credentials().source() == AwsSpec.Source.PROFILE) {
      List<String> warnings = cloud.profileWarnings(s.credentials());
      if (!warnings.isEmpty()) {
        warnings.forEach(out::note);
        out.note(NO_SIGN_IN);
        return;
      }
    }
    // Its own credentials, dropped afterwards: a form asks again with every change.
    ConnectionConfig own = own(probe);
    S3Api.Call c = new S3Api.Call(own, s, secrets, ctx);
    // On Amazon S3 the identity proves the sign-in; elsewhere a listing that answers does.
    String signedIn = null;
    try {
      if (s.aws()) {
        signedIn = "Signed in as " + api.identity(c, SessionPolicies.read(s, S3Tools.LIST_BUCKETS)).arn() + ".";
        if (chosen == null) {
          // No region yet: where the account's buckets are, the one region filled in when there is only one.
          Map<String, Integer> regions = regions(list(api, c, null), null);
          String only = regions.size() == 1 ? regions.keySet().iterator().next() : null;
          out.value(REGION, "the account", only, regionOptions(regions));
          out.note(regions.isEmpty() ? signedIn + " The credentials list no buckets." : signedIn + " The account has "
              + "buckets in " + shown(regions) + ": choose the region of the buckets agents may use.");
          return;
        }
      }
      Listing listing = list(api, c, s.aws() ? s.region() : null);
      if (!s.aws()) {
        signedIn = "Signed in to " + s.target() + ".";
      }
      List<Option> options = listing.buckets().stream().map(b -> Option.of(b.name())).toList();
      int n = options.size();
      String more = listing.complete() ? "" : "+";
      if (n > 0) {
        out.field(BUCKETS, new Suggestions.Field(options, null, s.aws() ? "the account" : "the storage service",
            listing.complete()));
        out.note(signedIn + " " + n + more + (n == 1 ? " bucket" : " buckets") + (s.aws() ? " in " + s.region() : "")
            + ": choose the ones agents may use.");
      } else if (s.aws()) {
        // None in the region: the regions where the account's buckets are, as Region's options.
        Map<String, Integer> regions = regions(list(api, c, null), s.region());
        out.options(REGION, "the account", regionOptions(regions));
        out.note(regions.isEmpty() ? signedIn + " The credentials list no buckets in " + s.region() + "."
            : signedIn + " No buckets in " + s.region() + "; the account has buckets in " + shown(regions)
                + ": choose that region, or add a connection for each.");
      } else {
        out.note(signedIn + " The credentials list no buckets.");
      }
    } catch (ConnectorException e) {
      out.note(signedIn == null ? e.getMessage() : signedIn + " " + e.getMessage());
    } finally {
      cloud.evict(own.id());
      api.forget(own.id());
    }
  }

  /** How many of the listed buckets each region holds, {@code except} left out. */
  private static Map<String, Integer> regions(Listing all, String except) {
    Map<String, Integer> regions = new LinkedHashMap<>();
    for (Bucket b : all.buckets()) {
      if (b.region() != null && AWS_REGION.matcher(b.region()).matches() && !b.region().equals(except)) {
        regions.merge(b.region(), 1, Integer::sum);
      }
    }
    return regions;
  }

  private static List<Option> regionOptions(Map<String, Integer> regions) {
    List<Option> options = new ArrayList<>();
    regions.forEach((region, count) -> options.add(new Option(region, null, count + (count == 1 ? " bucket"
        : " buckets"))));
    return options;
  }

  /** {@code us-east-1 (2), eu-west-1 (1)}. */
  private static String shown(Map<String, Integer> regions) {
    List<String> shown = new ArrayList<>();
    regions.forEach((region, count) -> shown.add(region + " (" + count + ")"));
    return String.join(", ", shown);
  }

  /** The region the AWS CLI would use with the form's profile (or its default one), else {@code us-east-1}. */
  private static String cliRegion(ConnectionConfig cfg, CloudLocal local) {
    boolean fromProfile = "profile".equals(text(cfg, "/credentials/source"));
    String region = local.awsRegion(fromProfile ? text(cfg, PROFILE) : null);
    return region != null && AWS_REGION.matcher(region).matches() ? region : "us-east-1";
  }

  /** The draft with {@code region}, for the requests of a suggestion only. */
  private static ConnectionConfig withRegion(ConnectionConfig cfg, String region) {
    if (!(cfg.config() instanceof ObjectNode config)) {
      return cfg;
    }
    ObjectNode copy = config.deepCopy().put("region", region);
    return new ConnectionConfig(cfg.id(), cfg.name(), cfg.type(), cfg.env(), cfg.version(), copy, cfg.secretFields(),
        cfg.metadata());
  }

  /** One page of {@code ListBuckets}, of {@code region} when given (Amazon S3 only). */
  private static Listing list(S3Api api, S3Api.Call c, String region) throws ConnectorException {
    S3Settings s = c.settings();
    S3Request r = S3Request.of("ListBuckets", "GET", null, null);
    if (s.aws()) {
      r = r.param("max-buckets", "1000");
      if (region != null) {
        r = r.param("bucket-region", region);
      }
    }
    S3Api.Answer a = api.send(c, r, SessionPolicies.read(s, S3Tools.LIST_BUCKETS), Reads.MAX_LIST);
    if (a.status() != 200) {
      if (a.status() == 403 || "AccessDenied".equals(S3Api.parseError(a).code())) {
        throw ConnectorException.resourceError("The credentials may not list buckets: type the buckets agents may "
            + "use.", null);
      }
      throw S3Api.error(a, r, c, false);
    }
    Element root;
    try {
      root = S3Xml.parse(a.body());
    } catch (IOException e) {
      throw ConnectorException.resourceError("The answer to ListBuckets isn't S3's XML.", null);
    }
    List<Bucket> out = new ArrayList<>();
    Element buckets = S3Xml.child(root, "Buckets");
    for (Element b : buckets == null ? List.<Element>of() : S3Xml.children(buckets, "Bucket")) {
      String name = S3Xml.text(b, "Name");
      if (name != null && !name.isBlank()) {
        out.add(new Bucket(name, S3Xml.text(b, "BucketRegion")));
      }
    }
    String token = S3Xml.text(root, "ContinuationToken");
    return new Listing(out, (token == null || token.isBlank()) && !a.truncated());
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
