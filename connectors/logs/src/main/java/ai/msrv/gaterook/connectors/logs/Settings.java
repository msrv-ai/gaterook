package ai.msrv.gaterook.connectors.logs;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connectors.cloudauth.AwsSpec;
import ai.msrv.gaterook.connectors.cloudauth.GcpSpec;
import ai.msrv.gaterook.connectors.support.egress.AddressPolicy;
import ai.msrv.gaterook.connectors.support.egress.UrlRules;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;
import tools.jackson.databind.JsonNode;

/**
 * A Logs connection's configuration, read once per call from {@link ConnectionConfig} (the form of
 * {@code config.schema.json}, report 07 §4). Everything an agent can't change lives here: the backend, its address,
 * the tenant, the projects, log groups and indices it may read, the filter always added.
 */
sealed interface Settings permits Settings.Loki, Settings.Gcp, Settings.CloudWatch, Settings.OpenSearch {

  /** {@code loki | gcp-logging | cloudwatch | opensearch | elasticsearch}, the {@code backend} of the output schema. */
  String backend();

  /** What people call the backend: {@code Loki}, {@code GCP Cloud Logging}. */
  String title();

  /** Where it reads: {@code loki.dev.internal:3100}, {@code projects/acme-prod}, {@code eu-central-1}. */
  String target();

  /** What {@code source} may name; empty when the backend has none (Loki selects streams in the query). */
  List<String> sources();

  /** The query language agents write, for tool descriptions and errors. */
  String language();

  /** One query in that language. */
  String example();

  /** A configuration the form's schema allows but the connector can't use. */
  final class InvalidSettingsException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    InvalidSettingsException(String message) {
      super(message);
    }
  }

  /** Authentication of an HTTP backend (Loki, OpenSearch, Elasticsearch). */
  enum AuthMode { NONE, BEARER, BASIC, API_KEY, AWS_SIGV4 }

  /**
   * @param secretField JSON pointer of the token or password ({@code /auth/secret}); the value comes from the call's
   *                    {@code SecretResolver}
   */
  record HttpAuth(AuthMode mode, String username, String secretField) {}

  record Loki(URI base, String tenant, HttpAuth auth, String caCertificate) implements Settings {
    @Override public String backend() { return "loki"; }
    @Override public String title() { return "Loki"; }
    @Override public String target() { return hostPort(base); }
    @Override public List<String> sources() { return List.of(); }
    @Override public String language() { return "LogQL"; }
    @Override public String example() { return "{app=\"api\"} |= \"ERROR\""; }
  }

  /**
   * @param quotaPerMinute this gateway's share of the project's 60 {@code entries.list} calls per minute
   */
  record Gcp(List<String> resourceNames, GcpSpec credentials, String scopeFilter, int quotaPerMinute)
      implements Settings {
    @Override public String backend() { return "gcp-logging"; }
    @Override public String title() { return "GCP Cloud Logging"; }
    @Override public String target() { return String.join(", ", resourceNames); }
    @Override public List<String> sources() { return resourceNames; }
    @Override public String language() { return "Logging query language"; }
    @Override public String example() { return "severity>=ERROR AND resource.type=\"k8s_container\""; }

    /** The quota bucket of the first resource: Cloud Logging counts {@code entries.list} per project. */
    String quotaKey() {
      return "gcp:" + resourceNames.get(0).toLowerCase(Locale.ROOT);
    }
  }

  /** @param insights search with Logs Insights; otherwise filter patterns ({@code FilterLogEvents}) */
  record CloudWatch(String region, List<String> logGroups, AwsSpec credentials, boolean insights)
      implements Settings {
    @Override public String backend() { return "cloudwatch"; }
    @Override public String title() { return "AWS CloudWatch"; }
    @Override public String target() { return region; }
    @Override public List<String> sources() { return logGroups; }
    @Override public String language() { return insights ? "Logs Insights query language" : "filter patterns"; }
    @Override public String example() {
      return insights ? "filter @message like /ERROR/ | fields @timestamp, @message" : "ERROR";
    }

    /** The account whose quota the calls use: the role's, a log group ARN's, else the key or profile itself. */
    String account() {
      Optional<String> fromArn = accountOf(credentials.roleArn());
      if (fromArn.isPresent()) {
        return fromArn.get();
      }
      for (String g : logGroups) {
        Optional<String> a = accountOf(g);
        if (a.isPresent()) {
          return a.get();
        }
      }
      return credentials.source() == AwsSpec.Source.PROFILE ? "profile-" + credentials.profile()
          : "key-" + credentials.accessKeyId();
    }

    private static Optional<String> accountOf(String arn) {
      if (arn == null) {
        return Optional.empty();
      }
      String[] parts = arn.split(":");
      return parts.length > 4 && parts[0].equals("arn") && parts[4].matches("\\d{12}")
          ? Optional.of(parts[4]) : Optional.empty();
    }
  }

  /**
   * Shared OpenSearch and Elasticsearch REST settings; scoped log fields apply only to legacy OpenSearch.
   *
   * @param indices        index patterns used by the scoped log tools
   * @param awsCredentials credentials of {@code awsSigV4} (Amazon OpenSearch Service), else {@code null}
   * @param fullApi        explicitly enable REST tools with the connection user's server permissions
   */
  record OpenSearch(URI base, List<String> indices, String timestampField, String messageField, String severityField,
      List<String> labelFields, String scopeQuery, HttpAuth auth, String awsRegion, String awsService,
      AwsSpec awsCredentials, String caCertificate, boolean fullApi, String backend) implements Settings {
    @Override public String title() { return backend.equals("elasticsearch") ? "Elasticsearch" : "OpenSearch"; }
    String tool(String operation) { return backend + "_" + operation; }
    @Override public String target() { return hostPort(base); }
    @Override public List<String> sources() { return indices; }
    @Override public String language() { return "Lucene query_string"; }
    @Override public String example() { return "log.level:error AND message:timeout"; }
  }

  Pattern TENANT = Pattern.compile("[A-Za-z0-9!._*'()|-]{0,150}");
  Pattern RESOURCE = Pattern.compile("(projects|folders|organizations|billingAccounts)/[A-Za-z0-9._:-]+"
      + "(/locations/[a-z0-9-]+/buckets/[A-Za-z0-9_-]+/views/[A-Za-z0-9_-]+)?");
  Pattern LOG_GROUP = Pattern.compile("[.\\-_/#A-Za-z0-9]{1,512}|arn:aws[a-z-]*:logs:[a-z0-9-]+:\\d{12}:log-group:"
      + "[.\\-_/#A-Za-z0-9]+(:\\*)?");
  Pattern INDEX = Pattern.compile("[a-z0-9][a-z0-9._*-]{0,254}");
  Pattern FIELD = Pattern.compile("[@A-Za-z0-9_][@A-Za-z0-9_.-]{0,254}");
  Pattern REGION = Pattern.compile("[a-z]{2}(-[a-z]+)+-\\d+");

  /**
   * Reads the connection's config.
   *
   * @throws InvalidSettingsException with what to fix, in the kit's tone
   */
  static Settings parse(ConnectionConfig cfg) {
    JsonNode c = cfg.config();
    String backend = text(c, "backend", null);
    if (backend == null) {
      throw new InvalidSettingsException("Pick a backend: Loki, GCP Cloud Logging, AWS CloudWatch or OpenSearch.");
    }
    try {
      return switch (backend) {
        case "loki" -> new Loki(baseUrl(text(c, "url", null), true), tenant(text(c, "tenant", null)),
            auth(c.path("auth"), "/auth", "bearer", List.of("none", "bearer", "basic")),
            text(c, "caCertificate", null));
        case "gcp-logging" -> gcp(c);
        case "cloudwatch" -> cloudWatch(c);
        case "opensearch" -> openSearch(c);
        case "elasticsearch" -> {
          if (!cfg.type().equals("elasticsearch")) {
            throw new InvalidSettingsException("Use the dedicated Elasticsearch connector for this server.");
          }
          yield elasticsearch(c);
        }
        default -> throw new InvalidSettingsException("Unknown backend \"" + backend + "\".");
      };
    } catch (InvalidSettingsException e) {
      throw e;
    } catch (IllegalArgumentException e) {
      // GcpSpec, AwsSpec and UrlRules explain themselves.
      throw new InvalidSettingsException(e.getMessage());
    }
  }

  private static Gcp gcp(JsonNode c) {
    List<String> names = strings(c.path("resourceNames"));
    if (names.isEmpty() || names.size() > 100) {
      throw new InvalidSettingsException("Name 1 to 100 projects or log views under Resource names.");
    }
    for (String n : names) {
      if (!RESOURCE.matcher(n).matches()) {
        throw new InvalidSettingsException(n + " isn't a project, folder, organization, billing account or log view "
            + "(projects/acme-prod, projects/acme-prod/locations/global/buckets/_Default/views/app).");
      }
    }
    if (c.path("credentials").isMissingNode()) {
      throw new InvalidSettingsException("Choose how GateRook signs in to Google under Credentials.");
    }
    int quota = c.path("quotaPerMinute").asInt(30);
    if (quota < 1 || quota > 60) {
      throw new InvalidSettingsException("The quota share must be 1 to 60 calls per minute.");
    }
    String scopeFilter = text(c, "scopeFilter", null);
    if (scopeFilter != null) GcpLoggingFilter.expression(scopeFilter);
    return new Gcp(names, GcpSpec.fromConfig(c, "/credentials"), scopeFilter, quota);
  }

  private static CloudWatch cloudWatch(JsonNode c) {
    String region = text(c, "region", null);
    if (region == null || !REGION.matcher(region).matches()) {
      throw new InvalidSettingsException("Set the AWS region, like eu-central-1.");
    }
    List<String> groups = strings(c.path("logGroups"));
    if (groups.isEmpty() || groups.size() > 50) {
      throw new InvalidSettingsException("Name 1 to 50 log groups agents may read.");
    }
    for (String g : groups) {
      if (!LOG_GROUP.matcher(g).matches()) {
        throw new InvalidSettingsException(g + " isn't a log group name or ARN.");
      }
    }
    if (c.path("credentials").isMissingNode()) {
      throw new InvalidSettingsException("Choose how GateRook signs in to AWS under Credentials.");
    }
    String api = text(c, "searchApi", "insights");
    if (!api.equals("insights") && !api.equals("filter")) {
      throw new InvalidSettingsException("Search with Logs Insights or with filter patterns.");
    }
    return new CloudWatch(region, groups, AwsSpec.fromConfig(c, "/credentials", region), api.equals("insights"));
  }

  private static OpenSearch openSearch(JsonNode c) {
    URI base = baseUrl(text(c, "url", null), false);
    String apiAccess = text(c, "apiAccess", "full");
    if (!apiAccess.equals("logs") && !apiAccess.equals("full")) {
      throw new InvalidSettingsException("Choose OpenSearch API access: logs or full.");
    }
    boolean fullApi = apiAccess.equals("full");
    List<String> indices = !c.has("indices") && fullApi ? List.of("*") : strings(c.path("indices"));
    if (indices.isEmpty() || indices.size() > 20) {
      throw new InvalidSettingsException("Name 1 to 20 index patterns agents may read, like logs-app-*.");
    }
    for (String i : indices) {
      if (!(fullApi && i.equals("*")) && !INDEX.matcher(i).matches()) {
        throw new InvalidSettingsException(i + " isn't a supported log index pattern: lower case, no commas, "
            + "colons, leading - or _" + (fullApi ? "." : ", and not every index (*)."));
      }
    }
    String ts = field(c, "timestampField", "@timestamp");
    String message = field(c, "messageField", "message");
    String severity = field(c, "severityField", "log.level");
    List<String> labels = c.has("labelFields") ? strings(c.path("labelFields"))
        : List.of("service.name", "kubernetes.namespace", "kubernetes.pod.name", "host.name");
    for (String l : labels) {
      if (!FIELD.matcher(l).matches()) {
        throw new InvalidSettingsException(l + " isn't a field name.");
      }
    }
    HttpAuth auth = auth(c.path("auth"), "/auth", "basic", List.of("none", "basic", "apiKey", "bearer", "awsSigV4"));
    String awsRegion = null;
    String awsService = null;
    AwsSpec aws = null;
    if (auth.mode() == AuthMode.AWS_SIGV4) {
      awsRegion = text(c.path("auth"), "awsRegion", null);
      if (awsRegion == null || !REGION.matcher(awsRegion).matches()) {
        throw new InvalidSettingsException("Set the AWS region of the domain, like eu-central-1.");
      }
      awsService = text(c.path("auth"), "awsService", "es");
      if (!awsService.equals("es") && !awsService.equals("aoss")) {
        throw new InvalidSettingsException("The AWS service is es (OpenSearch Service) or aoss (Serverless).");
      }
      if (c.at("/auth/awsCredentials").isMissingNode()) {
        throw new InvalidSettingsException("Choose the AWS credentials that sign requests to the domain.");
      }
      aws = AwsSpec.fromConfig(c, "/auth/awsCredentials", awsRegion);
    }
    return new OpenSearch(base, indices, ts, message, severity, labels, text(c, "scopeQuery", null), auth, awsRegion,
        awsService, aws, text(c, "caCertificate", null), fullApi, "opensearch");
  }

  /** Elasticsearch shares REST transport with OpenSearch, but exposes no legacy log scope or AWS signing. */
  private static OpenSearch elasticsearch(JsonNode c) {
    if (!text(c, "apiAccess", "full").equals("full")) {
      throw new InvalidSettingsException("The Elasticsearch connector uses full REST API access.");
    }
    HttpAuth auth = auth(c.path("auth"), "/auth", "basic", List.of("none", "basic", "apiKey", "bearer"));
    return new OpenSearch(baseUrl(text(c, "url", null), false), List.of("*"), "@timestamp", "message", "log.level",
        List.of(), null, auth, null, null, null, text(c, "caCertificate", null), true, "elasticsearch");
  }

  private static HttpAuth auth(JsonNode a, String pointer, String dflt, List<String> allowed) {
    String mode = text(a, "mode", dflt);
    if (!allowed.contains(mode)) {
      throw new InvalidSettingsException("Unknown sign-in \"" + mode + "\".");
    }
    AuthMode m = switch (mode) {
      case "none" -> AuthMode.NONE;
      case "bearer" -> AuthMode.BEARER;
      case "basic" -> AuthMode.BASIC;
      case "apiKey" -> AuthMode.API_KEY;
      default -> AuthMode.AWS_SIGV4;
    };
    String username = text(a, "username", null);
    if (m == AuthMode.BASIC && username == null) {
      throw new InvalidSettingsException("Basic sign-in needs a username.");
    }
    return new HttpAuth(m, username, m == AuthMode.NONE || m == AuthMode.AWS_SIGV4 ? null : pointer + "/secret");
  }

  /**
   * A base URL a person typed: http(s), a canonical host, no credentials or query; never a cloud metadata address
   * nor the gateway's own listener. The address is checked here, before any resolution (ARCHITECTURE.md §9 "SSRF":
   * the person chose it); Loki and OpenSearch commonly live on private networks, so private ranges stay allowed.
   */
  static URI baseUrl(String url, boolean pathAllowed) {
    if (url == null) {
      throw new InvalidSettingsException("Set the URL, like http://loki.dev.internal:3100.");
    }
    HttpUrl parsed = UrlRules.parse(url);
    if (parsed.query() != null) {
      throw new InvalidSettingsException("The URL can't have a ?query.");
    }
    String path = parsed.encodedPath();
    if (!pathAllowed && !path.equals("/")) {
      throw new InvalidSettingsException("Give the address only, like https://search.dev.internal:9200.");
    }
    Optional<InetAddress> literal = AddressPolicy.canonicalIp(parsed.host());
    if (literal.isPresent()) {
      AddressPolicy.Verdict v = AddressPolicy.builder().build().check(literal.get(), parsed.port());
      if (v.reason() != AddressPolicy.Reason.PUBLIC && v.reason() != AddressPolicy.Reason.PRIVATE
          && v.reason() != AddressPolicy.Reason.ALLOWED_PRIVATE) {
        throw new InvalidSettingsException(v.text());
      }
    }
    String base = parsed.toString();
    return URI.create(base.endsWith("/") ? base.substring(0, base.length() - 1) : base);
  }

  private static String tenant(String tenant) {
    if (tenant != null && !TENANT.matcher(tenant).matches()) {
      throw new InvalidSettingsException("A tenant has letters, digits and !._*'()- only; several are joined by |.");
    }
    return tenant;
  }

  private static String field(JsonNode c, String name, String dflt) {
    String v = text(c, name, dflt);
    if (!FIELD.matcher(v).matches()) {
      throw new InvalidSettingsException(v + " isn't a field name.");
    }
    return v;
  }

  static String hostPort(URI uri) {
    return uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
  }

  private static List<String> strings(JsonNode array) {
    List<String> out = new ArrayList<>();
    if (array.isArray()) {
      for (JsonNode n : array) {
        if (n.isString() && !n.asString().isBlank()) {
          out.add(n.asString().strip());
        }
      }
    }
    return List.copyOf(out);
  }

  private static String text(JsonNode node, String field, String dflt) {
    JsonNode n = node.get(field);
    return n == null || n.isNull() || !n.isValueNode() || n.asString().isBlank() ? dflt : n.asString().strip();
  }
}
