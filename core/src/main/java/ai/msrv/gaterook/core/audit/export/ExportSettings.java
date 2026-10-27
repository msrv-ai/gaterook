package ai.msrv.gaterook.core.audit.export;

import ai.msrv.gaterook.connectors.cloudauth.AwsSpec;
import ai.msrv.gaterook.connectors.cloudauth.GcpSpec;
import ai.msrv.gaterook.connectors.support.Pem;
import ai.msrv.gaterook.connectors.support.egress.AddressPolicy;
import ai.msrv.gaterook.connectors.support.egress.Cidr;
import ai.msrv.gaterook.core.audit.internal.persistence.ExportKind;
import ai.msrv.gaterook.core.platform.ProblemException;
import java.net.InetAddress;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a person may set for each kind of destination (ARCHITECTURE.md §6 settings "audit export"), checked into its
 * stored form, and the stored form read back as typed settings. A config never holds a secret: the one secret a kind
 * may need is a field of the {@code secrets} module ({@link #secretField}), its id in {@code audit_export.secret_id}.
 * <ul>
 *   <li>{@code siem} — syslog over TLS (RFC 5425): {@code host}, {@code port} (6514), {@code facility} (13, log
 *       audit), {@code appName}, a private CA ({@code caCertificate}, PEM) and a client certificate for mutual TLS
 *       ({@code clientCertificate}; its key is the secret {@code /tls/clientKey});</li>
 *   <li>{@code s3} — {@code bucket}, {@code prefix}, {@code region}, {@code format} ({@code jsonl} or {@code csv}),
 *       an S3-compatible {@code endpoint} with {@code pathStyle}, and {@code credentials} as the AWS forms write
 *       them (an access key — its secret half {@code /credentials/secretAccessKey} — or a profile, optionally
 *       assuming a role);</li>
 *   <li>{@code bigquery} — {@code table} as {@code project.dataset.table} and {@code credentials} as the GCP forms
 *       write them (Google sign-in on this computer, or the key {@code /credentials/serviceAccountKey}, optionally
 *       impersonating a service account).</li>
 * </ul>
 * Addresses a person typed are checked here, before anything resolves them (§9 "SSRF": cloud metadata, link-local and
 * the core's own ports never; private networks yes — SIEMs live there).
 */
public final class ExportSettings {

  /** The secret of each kind: the client key of mutual TLS, the AWS secret key, the GCP service account key. */
  public static final String SIEM_CLIENT_KEY = "/tls/clientKey";
  public static final String S3_SECRET_KEY = "/credentials/secretAccessKey";
  public static final String BIGQUERY_KEY = "/credentials/serviceAccountKey";

  private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.:\\[\\]-]{1,253}");
  private static final Pattern APP_NAME = Pattern.compile("[!-~]{1,48}");
  private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");
  private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9!_.*'()/-]{0,512}");
  private static final Pattern REGION = Pattern.compile("[a-z]{2}(-[a-z]+)+-\\d+");
  private static final Pattern PROJECT = Pattern.compile("[a-z][a-z0-9-]{4,28}[a-z0-9]");
  private static final Pattern DATASET = Pattern.compile("[A-Za-z0-9_]{1,1024}");
  private static final Pattern TABLE = Pattern.compile("[A-Za-z0-9_-]{1,1024}");
  private static final List<String> PRIVATE = List.of("127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12",
      "192.168.0.0/16", "100.64.0.0/10", "198.18.0.0/15", "fc00::/7", "fec0::/10");

  /** A destination's settings as the senders use them. */
  public sealed interface Target permits Siem, S3, BigQuery {

    /** Where it writes, as Settings shows it: {@code siem.acme.internal:6514}, {@code s3://acme-audit/gaterook/}. */
    String describe();
  }

  /**
   * @param ca         a private CA the SIEM's certificate chains to, on top of the platform's; empty: platform only
   * @param clientCert the client certificate chain of mutual TLS; empty without
   */
  public record Siem(String host, int port, int facility, String appName, List<X509Certificate> ca,
      List<X509Certificate> clientCert) implements Target {

    @Override
    public String describe() {
      return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }
  }

  /** @param endpoint an S3-compatible service, or {@code null}: AWS S3 in {@code region} */
  public record S3(String bucket, String prefix, String region, URI endpoint, boolean pathStyle, String format,
      AwsSpec credentials) implements Target {

    @Override
    public String describe() {
      return "s3://" + bucket + "/" + prefix;
    }
  }

  public record BigQuery(String project, String dataset, String table, GcpSpec credentials) implements Target {

    @Override
    public String describe() {
      return project + "." + dataset + "." + table;
    }
  }

  private ExportSettings() {}

  /**
   * Checks {@code config} for {@code kind} and returns the form to store: known fields only, defaults filled in.
   *
   * @param corePorts the core's own listeners: a destination on this computer may not be the core itself
   * @throws ProblemException 422 at the field ({@code /config/host}, {@code /config/credentials}, …)
   */
  public static ObjectNode normalize(ExportKind kind, JsonNode config, Set<Integer> corePorts) {
    if (config == null || !config.isObject()) {
      throw ProblemException.unprocessable("Settings missing", "Give the destination's settings.", "/config");
    }
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    switch (kind) {
      case SIEM -> {
        String host = text(config, "host");
        out.put("host", host(host, "/config/host"));
        int port = config.path("port").asInt(6514);
        if (port < 1 || port > 65_535 || (!config.path("port").isMissingNode() && !config.path("port").isNull()
            && !config.path("port").isIntegralNumber())) {
          throw ProblemException.unprocessable("Invalid port", "A port is a number from 1 to 65535.", "/config/port");
        }
        address(host, port, corePorts, "/config/host");
        out.put("port", port);
        int facility = config.path("facility").asInt(13);
        if (facility < 0 || facility > 23) {
          throw ProblemException.unprocessable("Invalid facility", "A syslog facility is a number from 0 to 23 (13 "
              + "is log audit).", "/config/facility");
        }
        out.put("facility", facility);
        String app = text(config, "appName");
        app = app == null ? "gaterook" : app;
        if (!APP_NAME.matcher(app).matches()) {
          throw ProblemException.unprocessable("Invalid app name", "An app name has 1 to 48 printable ASCII "
              + "characters without spaces.", "/config/appName");
        }
        out.put("appName", app);
        pem(config, "caCertificate", out);
        pem(config, "clientCertificate", out);
      }
      case S3 -> {
        String bucket = text(config, "bucket");
        if (bucket == null || !BUCKET.matcher(bucket).matches() || bucket.contains("..")) {
          throw ProblemException.unprocessable("Invalid bucket", "A bucket name has 3 to 63 lower-case letters, "
              + "digits, dots and hyphens, like acme-audit.", "/config/bucket");
        }
        out.put("bucket", bucket);
        out.put("prefix", prefix(text(config, "prefix")));
        String region = text(config, "region");
        if (region == null || !REGION.matcher(region).matches()) {
          throw ProblemException.unprocessable("Invalid region", "Name the bucket's region, like eu-central-1.",
              "/config/region");
        }
        out.put("region", region);
        String endpoint = text(config, "endpoint");
        if (endpoint != null) {
          out.put("endpoint", endpoint(endpoint, corePorts).toString());
        }
        out.put("pathStyle", config.path("pathStyle").asBoolean(endpoint != null || bucket.indexOf('.') >= 0));
        String format = text(config, "format");
        format = format == null ? "jsonl" : format;
        if (!format.equals("jsonl") && !format.equals("csv")) {
          throw ProblemException.unprocessable("Unknown format", "Export as jsonl or csv.", "/config/format");
        }
        out.put("format", format);
        JsonNode credentials = config.path("credentials");
        try {
          AwsSpec.fromConfig(config, "/credentials", region);
        } catch (IllegalArgumentException e) {
          throw ProblemException.unprocessable("Invalid credentials", e.getMessage(), "/config/credentials");
        }
        out.set("credentials", known(credentials, "source", "accessKeyId", "profile", "roleArn", "externalId",
            "sessionSeconds"));
      }
      case BIGQUERY -> {
        String table = text(config, "table");
        String[] parts = table == null ? new String[0] : table.split("\\.", -1);
        if (parts.length != 3 || !PROJECT.matcher(parts[0]).matches() || !DATASET.matcher(parts[1]).matches()
            || !TABLE.matcher(parts[2]).matches()) {
          throw ProblemException.unprocessable("Invalid table", "Name the table as project.dataset.table, like "
              + "acme-prod.audit.mcp_calls.", "/config/table");
        }
        out.put("table", table);
        JsonNode credentials = config.path("credentials");
        if (!credentials.isObject()) {
          throw ProblemException.unprocessable("Credentials missing", "Choose Google sign-in on this computer "
              + "(adc) or a service account key.", "/config/credentials");
        }
        try {
          GcpSpec.fromConfig(config, "/credentials");
        } catch (IllegalArgumentException e) {
          throw ProblemException.unprocessable("Invalid credentials", e.getMessage(), "/config/credentials");
        }
        out.set("credentials", known(credentials, "source", "impersonate", "delegates", "lifetimeSeconds",
            "quotaProject"));
      }
    }
    return out;
  }

  /** The secret field {@code config} needs, or {@code null}. */
  public static String secretField(ExportKind kind, JsonNode config) {
    return switch (kind) {
      case SIEM -> config.hasNonNull("clientCertificate") ? SIEM_CLIENT_KEY : null;
      case S3 -> "profile".equals(config.path("credentials").path("source").asString("accessKey")) ? null
          : S3_SECRET_KEY;
      case BIGQUERY -> "serviceAccountKey".equals(config.path("credentials").path("source").asString("adc"))
          ? BIGQUERY_KEY : null;
    };
  }

  /** A stored config as typed settings (it passed {@link #normalize} when it was saved). */
  public static Target parse(ExportKind kind, JsonNode c) {
    return switch (kind) {
      case SIEM -> new Siem(c.path("host").asString(), c.path("port").asInt(6514), c.path("facility").asInt(13),
          c.path("appName").asString("gaterook"), certificates(c, "caCertificate"),
          certificates(c, "clientCertificate"));
      case S3 -> {
        String region = c.path("region").asString();
        yield new S3(c.path("bucket").asString(), c.path("prefix").asString(""), region,
            c.hasNonNull("endpoint") ? URI.create(c.path("endpoint").asString()) : null,
            c.path("pathStyle").asBoolean(false), c.path("format").asString("jsonl"),
            AwsSpec.fromConfig(c, "/credentials", region));
      }
      case BIGQUERY -> {
        String[] parts = c.path("table").asString().split("\\.", -1);
        yield new BigQuery(parts[0], parts[1], parts[2], GcpSpec.fromConfig(c, "/credentials"));
      }
    };
  }

  /** Where a stored config writes, without parsing what can fail (Settings, Setup changes). */
  public static String describe(ExportKind kind, JsonNode c) {
    return switch (kind) {
      case SIEM -> c.path("host").asString("?") + ":" + c.path("port").asInt(6514);
      case S3 -> "s3://" + c.path("bucket").asString("?") + "/" + c.path("prefix").asString("");
      case BIGQUERY -> c.path("table").asString("?");
    };
  }

  // ── Checks ──────────────────────────────────────────────────────────────────────────────────────────────────

  private static String host(String host, String pointer) {
    if (host == null || !HOST.matcher(host).matches()) {
      throw ProblemException.unprocessable("Invalid host", "Give the SIEM's host name or address, like "
          + "siem.acme.internal.", pointer);
    }
    String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    Optional<AddressPolicy.Verdict> syntax = AddressPolicy.checkHostSyntax(bare);
    if (syntax.isPresent()) {
      throw ProblemException.unprocessable("Invalid host", syntax.get().text(), pointer);
    }
    return bare.toLowerCase(Locale.ROOT);
  }

  /**
   * The address a person typed, judged before it resolves: an IP literal must be public or private (never metadata,
   * link-local or the core's own port); a name is resolved only when the export connects.
   */
  private static void address(String host, int port, Set<Integer> corePorts, String pointer) {
    String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    Optional<InetAddress> literal = AddressPolicy.canonicalIp(bare);
    boolean loopback = literal.map(InetAddress::isLoopbackAddress).orElse(bare.equalsIgnoreCase("localhost"));
    if (literal.isPresent()) {
      AddressPolicy policy = AddressPolicy.builder().allowPrivate(PRIVATE.stream().map(Cidr::parse).toList())
          .corePorts(corePorts).build();
      AddressPolicy.Verdict v = policy.check(bare, literal.get(), port);
      if (!v.allowed()) {
        throw ProblemException.unprocessable("Address not allowed", v.text(), pointer);
      }
    } else if (loopback && corePorts.contains(port)) {
      throw ProblemException.unprocessable("Address not allowed", "Port " + port + " on this computer is "
          + "GateRook itself.", pointer);
    }
  }

  /** An S3-compatible endpoint: https (http only to this computer), the address checked, no path or query. */
  private static URI endpoint(String text, Set<Integer> corePorts) {
    URI uri;
    try {
      uri = URI.create(text.strip());
    } catch (IllegalArgumentException e) {
      throw ProblemException.unprocessable("Invalid endpoint", "Give the endpoint as a URL, like "
          + "https://s3.eu-central-1.amazonaws.com.", "/config/endpoint");
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if ((!scheme.equals("https") && !scheme.equals("http")) || uri.getHost() == null) {
      throw ProblemException.unprocessable("Invalid endpoint", "The endpoint starts with https://.",
          "/config/endpoint");
    }
    if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
        || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/"))) {
      throw ProblemException.unprocessable("Invalid endpoint", "Give the endpoint's address only, without a path, "
          + "user, query or fragment.", "/config/endpoint");
    }
    String host = host(uri.getHost(), "/config/endpoint");
    int port = uri.getPort() > 0 ? uri.getPort() : scheme.equals("https") ? 443 : 80;
    address(host, port, corePorts, "/config/endpoint");
    boolean loopback = AddressPolicy.canonicalIp(host).map(InetAddress::isLoopbackAddress)
        .orElse(host.equals("localhost"));
    if (scheme.equals("http") && !loopback) {
      throw ProblemException.unprocessable("Use https", "Use https:// for an endpoint on another computer: the "
          + "audit and the request signatures would cross the network unencrypted.", "/config/endpoint");
    }
    return URI.create(scheme + "://" + (host.indexOf(':') >= 0 ? "[" + host + "]" : host)
        + (uri.getPort() > 0 ? ":" + uri.getPort() : ""));
  }

  /** {@code gaterook/}: safe characters only, no leading slash or dot segment, ending with a slash. */
  private static String prefix(String prefix) {
    if (prefix == null || prefix.isEmpty()) {
      return "";
    }
    if (!PREFIX.matcher(prefix).matches() || prefix.startsWith("/") || prefix.contains("//")
        || List.of(prefix.split("/")).stream().anyMatch(s -> s.equals(".") || s.equals(".."))) {
      throw ProblemException.unprocessable("Invalid prefix", "A prefix has letters, digits and !_.*'()/- only, "
          + "without a leading slash or . and .. folders, like gaterook/.", "/config/prefix");
    }
    return prefix.endsWith("/") ? prefix : prefix + "/";
  }

  private static void pem(JsonNode config, String field, ObjectNode out) {
    String text = text(config, field);
    if (text == null) {
      return;
    }
    try {
      Pem.certificates(text);
    } catch (IllegalArgumentException e) {
      throw ProblemException.unprocessable("Invalid certificate", e.getMessage(), "/config/" + field);
    }
    out.put(field, text.strip() + "\n");
  }

  private static List<X509Certificate> certificates(JsonNode c, String field) {
    return c.hasNonNull(field) ? Pem.certificates(c.path(field).asString()) : List.of();
  }

  private static ObjectNode known(JsonNode node, String... fields) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    for (String f : fields) {
      JsonNode v = node.get(f);
      if (v != null && !v.isNull()) {
        out.set(f, v.deepCopy());
      }
    }
    return out;
  }

  private static String text(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return v == null || v.isNull() || !v.isString() || v.asString().isBlank() ? null : v.asString().strip();
  }
}
