package com.gaterook.connectors.s3;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connectors.cloudauth.AwsSpec;
import com.gaterook.connectors.cloudauth.CloudEndpoints;
import com.gaterook.connectors.support.Glob;
import com.gaterook.connectors.support.egress.UrlRules;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;

/**
 * The settings of one S3 connection ({@code config.schema.json}).
 *
 * <ul>
 *   <li>Amazon S3: no {@code endpoint}; the region's endpoint {@code s3.<region>.amazonaws.com} ({@code .com.cn} in
 *       China; GovCloud regions as AWS names them). Credentials of connectors/cloud-auth: an access key or a profile,
 *       optionally a role to assume, each call under a session policy for what it does.</li>
 *   <li>S3-compatible storage: an {@code endpoint} URL (https, or http for a server on a private network), the
 *       provider's region ({@code auto} for Cloudflare R2), an access key. No role: only AWS has STS.</li>
 * </ul>
 *
 * @param endpoint        the custom endpoint without a trailing slash, or {@code null} for Amazon S3
 * @param pathStyle       {@code https://host/bucket/key} rather than {@code https://bucket.host/key}
 * @param buckets         globs of bucket names ({@code acme-*}); empty: every bucket the credentials list, which on
 *                        Amazon S3 means buckets of the credentials' own account
 * @param credentials     AWS credentials for Amazon S3, or {@code null}
 * @param accessKeyId     the access key of a custom endpoint, or {@code null}
 * @param generatedPolicy with a role: each call's session policy allows what it needs and nothing else
 */
record S3Settings(URI endpoint, String region, boolean pathStyle, List<String> buckets, AwsSpec credentials,
    String accessKeyId, boolean generatedPolicy) {

  static final String SECRET_KEY = "/credentials/secretAccessKey";
  private static final Pattern AWS_REGION = Pattern.compile("^[a-z]{2}(-[a-z]+)+-\\d+$");
  /** Regions of other providers: {@code auto} (R2), {@code fsn1} (Hetzner), {@code us-west-002} (B2). */
  private static final Pattern REGION = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");
  private static final Pattern ACCESS_KEY = Pattern.compile("^[A-Za-z0-9._+-]{3,128}$");

  static S3Settings of(ConnectionConfig cfg) throws ConnectorException {
    URI endpoint = endpoint(cfg.string("/endpoint"));
    boolean aws = endpoint == null;
    String region = cfg.string("/region");
    if (aws && (region == null || !AWS_REGION.matcher(region).matches())) {
      throw ConnectorException.badInput("Choose the AWS region of the buckets, such as eu-central-1.");
    }
    if (!aws && (region == null || !REGION.matcher(region).matches())) {
      throw ConnectorException.badInput("Choose the region the provider names for its endpoint, such as auto for "
          + "Cloudflare R2 or us-east-1 when it names none.");
    }
    List<String> buckets = new ArrayList<>();
    for (String glob : cfg.strings("/buckets")) {
      if (!Names.BUCKET_GLOB.matcher(glob).matches()) {
        throw ConnectorException.badInput("Buckets lists bucket names and patterns such as acme-*; "
            + Names.shown(glob) + " isn't one.");
      }
      buckets.add(glob);
    }
    String source = cfg.string("/credentials/source");
    String roleArn = blankToNull(cfg.string("/credentials/roleArn"));
    if (aws) {
      AwsSpec spec;
      try {
        spec = AwsSpec.fromConfig(cfg.config(), "/credentials", region);
      } catch (IllegalArgumentException e) {
        throw ConnectorException.badInput("The AWS credentials of " + cfg.name() + " aren't complete: "
            + e.getMessage() + ".");
      }
      boolean generated = spec.roleArn() != null && !"none".equals(cfg.string("/credentials/sessionPolicy"));
      return new S3Settings(null, region, cfg.bool("/pathStyle", false), List.copyOf(buckets), spec, null,
          generated);
    }
    if (source != null && !"accessKey".equals(source)) {
      throw ConnectorException.badInput("An S3-compatible endpoint takes an access key: choose Access key under "
          + "Credentials.");
    }
    if (roleArn != null) {
      throw ConnectorException.badInput("Only Amazon S3 assumes roles: leave Role to assume empty for "
          + endpoint.getHost() + ".");
    }
    String accessKeyId = cfg.string("/credentials/accessKeyId");
    if (accessKeyId == null || !ACCESS_KEY.matcher(accessKeyId).matches()) {
      throw ConnectorException.badInput("The access key ID of " + cfg.name() + " is missing or has characters an "
          + "S3 signature can't carry.");
    }
    return new S3Settings(endpoint, region, cfg.bool("/pathStyle", true), List.copyOf(buckets), null, accessKeyId,
        false);
  }

  /**
   * The custom endpoint: http or https with a host, optionally a port and a base path (Supabase Storage serves S3
   * under {@code /storage/v1/s3}); no user info, query or fragment, by the egress guard's own URL rules. Amazon S3's
   * hosts are refused: an empty endpoint picks them, with the region and the role.
   */
  static URI endpoint(String text) throws ConnectorException {
    if (text == null || text.isBlank()) {
      return null;
    }
    String typed = text.strip();
    HttpUrl url;
    try {
      url = UrlRules.parse(typed);
    } catch (IllegalArgumentException e) {
      throw ConnectorException.badInput("The endpoint isn't usable: " + e.getMessage() + ".");
    }
    // The URL parser resolves . and .. by itself: refuse them as typed rather than send another path.
    int scheme = typed.indexOf("://");
    int slash = scheme < 0 ? -1 : typed.indexOf('/', scheme + 3);
    if (slash >= 0) {
      for (String segment : typed.substring(slash).split("[/?#]", -1)) {
        String bare = segment.toLowerCase(Locale.ROOT).replace("%2e", ".");
        if (bare.equals(".") || bare.equals("..")) {
          throw ConnectorException.badInput("The endpoint's path can't have . or .. segments.");
        }
      }
    }
    if (url.encodedQuery() != null) {
      throw ConnectorException.badInput("The endpoint can't have a query: give the service's URL, such as "
          + "https://s3.us-west-002.backblazeb2.com.");
    }
    String host = url.host().toLowerCase(Locale.ROOT);
    if (host.endsWith(".amazonaws.com") || host.endsWith(".amazonaws.com.cn") || host.equals("amazonaws.com")) {
      throw ConnectorException.badInput("For Amazon S3, leave the endpoint empty: GateRook uses the region's "
          + "endpoint.");
    }
    String path = url.encodedPath();
    while (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    boolean defaultPort = url.port() == HttpUrl.defaultPort(url.scheme());
    String authority = (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + (defaultPort ? "" : ":" + url.port());
    return URI.create(url.scheme() + "://" + authority + path);
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  /** Whether this is Amazon S3 itself (no custom endpoint). */
  boolean aws() {
    return endpoint == null;
  }

  /** Whether agents may use {@code bucket}: on the list, or any when the list is empty. */
  boolean allows(String bucket) {
    return buckets.isEmpty() || Glob.anyText(buckets, bucket);
  }

  /** Whether the connection lists its buckets. */
  boolean listed() {
    return !buckets.isEmpty();
  }

  /**
   * Where requests go: the custom endpoint, or Amazon S3 in the region (a development build's test endpoint
   * {@code aws.s3} instead, with the test credentials that go with it).
   */
  URI base(CloudEndpoints endpoints) {
    if (endpoint != null) {
      return endpoint;
    }
    URI test = endpoints.service("aws.s3");
    if (test != null) {
      String t = test.toString();
      return URI.create(t.endsWith("/") ? t.substring(0, t.length() - 1) : t);
    }
    return URI.create("https://s3." + region + ".amazonaws.com" + (region.startsWith("cn-") ? ".cn" : ""));
  }

  /** The partition of the region's ARNs. */
  String partition() {
    return region.startsWith("cn-") ? "aws-cn" : region.startsWith("us-gov-") ? "aws-us-gov" : "aws";
  }

  /** {@code Amazon S3 · eu-central-1}, or the endpoint's host. */
  String target() {
    return aws() ? "Amazon S3 · " + region : endpoint.getHost() + (endpoint.getPort() < 0 ? "" : ":"
        + endpoint.getPort());
  }

  /** The identity a preview names: the role, the profile or the access key ID. */
  String identity() {
    return credentials != null ? credentials.identity() : accessKeyId;
  }

  /** Whether requests travel unencrypted. */
  boolean plainHttp() {
    return endpoint != null && "http".equals(endpoint.getScheme());
  }
}
