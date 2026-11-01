package ai.msrv.gaterook.connectors.cloudauth;

import java.net.URI;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;

/**
 * SigV4 for an HTTP request the gateway sends itself, such as one to an MCP server on AWS (ARCHITECTURE.md §7.6): the
 * headers that sign its method, URL, host and body with a credential, for a service in a region. Only {@code Host}
 * and the signature's own headers are signed, so the headers the transport adds after it ({@code Content-Type},
 * {@code Accept}, {@code Mcp-Session-Id}) can't break the signature.
 */
public final class AwsSigV4 {

  private static final AwsV4HttpSigner SIGNER = AwsV4HttpSigner.create();
  private static final String REGION = "[a-z]{2}(?:-[a-z]+)+-\\d+";
  /** {@code aws-mcp.us-east-1.api.aws}: a dual-stack service endpoint. */
  private static final Pattern API_AWS = Pattern.compile("^([a-z0-9-]+)\\.(" + REGION + ")\\.api\\.aws$");
  /** {@code bedrock-agentcore.us-west-2.amazonaws.com}, {@code abc123.execute-api.eu-west-1.amazonaws.com}. */
  private static final Pattern AMAZONAWS = Pattern.compile("^(?:[a-z0-9-]+\\.)*?([a-z0-9-]+)\\.(" + REGION
      + ")\\.amazonaws\\.com$");
  private static final Pattern SERVICE = Pattern.compile("^[a-z0-9-]{1,64}$");
  private static final Pattern REGION_NAME = Pattern.compile("^" + REGION + "$");

  private AwsSigV4() {}

  /** The service and region a request is signed for: {@code aws-mcp} in {@code us-east-1}. */
  public record Target(String service, String region) {

    public Target {
      if (service == null || !SERVICE.matcher(service).matches()) {
        throw new IllegalArgumentException("Not an AWS service name: " + service);
      }
      if (region == null || !REGION_NAME.matcher(region).matches()) {
        throw new IllegalArgumentException("Not an AWS region: " + region);
      }
    }
  }

  /**
   * The service and region an AWS endpoint's host names, as the MCP Proxy for AWS reads them: {@code aws-mcp} and
   * {@code us-east-1} for {@code aws-mcp.us-east-1.api.aws}, {@code bedrock-agentcore} and {@code us-west-2} for
   * {@code bedrock-agentcore.us-west-2.amazonaws.com}. Empty for any other host.
   */
  public static Optional<Target> fromHost(String host) {
    if (host == null) {
      return Optional.empty();
    }
    String h = host.toLowerCase(Locale.ROOT);
    for (Pattern p : List.of(API_AWS, AMAZONAWS)) {
      Matcher m = p.matcher(h);
      if (m.matches()) {
        return Optional.of(new Target(m.group(1), m.group(2)));
      }
    }
    return Optional.empty();
  }

  /**
   * What a request to {@code host} is signed for: {@code service} and {@code region} as given, each else what the host
   * names. Empty when either stays unknown or isn't valid.
   */
  public static Optional<Target> target(String service, String region, String host) {
    Optional<Target> named = fromHost(host);
    String s = service == null || service.isBlank() ? named.map(Target::service).orElse(null) : service;
    String r = region == null || region.isBlank() ? named.map(Target::region).orElse(null) : region;
    if (s == null || r == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(new Target(s, r));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /**
   * The headers that sign one request: {@code Authorization}, {@code X-Amz-Date}, {@code X-Amz-Security-Token} for a
   * session credential, and the {@code Host} the signature covers.
   *
   * @param url  the URL exactly as it is sent (its path and query are part of the signature)
   * @param body the body as it is sent; empty or {@code null} for none
   */
  public static Map<String, String> sign(AwsCredentialsIdentity credentials, Target target, String method, URI url,
      byte[] body, Clock clock) {
    Objects.requireNonNull(credentials, "credentials");
    Objects.requireNonNull(target, "target");
    SdkHttpRequest request = SdkHttpRequest.builder()
        .method(SdkHttpMethod.fromValue(method))
        .uri(url)
        .putHeader("Host", host(url))
        .build();
    SignedRequest signed = SIGNER.sign(r -> r
        .identity(credentials)
        .request(request)
        .payload(body == null || body.length == 0 ? null : ContentStreamProvider.fromByteArrayUnsafe(body))
        .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, target.service())
        .putProperty(AwsV4HttpSigner.REGION_NAME, target.region())
        .putProperty(HttpSigner.SIGNING_CLOCK, clock));
    Map<String, String> headers = new LinkedHashMap<>();
    signed.request().forEachHeader((name, values) -> {
      if (!values.isEmpty()) {
        headers.put(name, values.getFirst());
      }
    });
    return headers;
  }

  /** The {@code Host} header of {@code url} as HTTP clients write it: the port only when it isn't the scheme's own. */
  static String host(URI url) {
    String host = url.getHost();
    if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
      host = "[" + host + "]";
    }
    int port = url.getPort();
    int standard = "http".equalsIgnoreCase(url.getScheme()) ? 80 : 443;
    return port < 0 || port == standard ? host : host + ":" + port;
  }
}
