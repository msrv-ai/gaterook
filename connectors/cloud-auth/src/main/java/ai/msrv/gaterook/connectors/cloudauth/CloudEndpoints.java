package ai.msrv.gaterook.connectors.cloudauth;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Where the cloud credential endpoints are. Production uses the real ones (all fields {@code null}); tests point them
 * at MockWebServer or moto (report 09 §7: {@code gateway.cloud.gcp.token-uri}, {@code gateway.cloud.aws.endpoint-
 * override}). The core never honours overrides in a release build ({@code platform.BuildFlags.RELEASE}).
 *
 * @param gcpTokenUri         OAuth token endpoint of user and service account credentials
 * @param gcpIamEndpoint      full {@code generateAccessToken} URL with {@code %s} for the service account, e.g.
 *                            {@code http://127.0.0.1:1234/v1/projects/-/serviceAccounts/%s:generateAccessToken}
 * @param gcpAdcFile          the ADC file; {@code null}: {@code GOOGLE_APPLICATION_CREDENTIALS}, else
 *                            {@code ~/.config/gcloud/application_default_credentials.json}
 * @param awsStsEndpoint      the STS endpoint
 * @param awsConfigFile       {@code ~/.aws/config} for profiles; {@code null}: the SDK's default location
 * @param awsCredentialsFile  {@code ~/.aws/credentials}; {@code null}: the SDK's default location
 * @param services            base URLs of the cloud APIs that connectors call with these credentials, by key
 *                            ({@code gcp.logging}, {@code aws.logs}; property {@code gateway.cloud.endpoints}). They
 *                            travel with the credential overrides: a token from a fake token endpoint is only good for
 *                            a fake API, and a release never sends a token anywhere but Google and AWS
 */
public record CloudEndpoints(URI gcpTokenUri, String gcpIamEndpoint, Path gcpAdcFile, URI awsStsEndpoint,
    Path awsConfigFile, Path awsCredentialsFile, Map<String, URI> services) {

  /** {@code gcp.logging}, {@code aws.logs}: the cloud, a dot, the API. */
  private static final Pattern SERVICE_KEY = Pattern.compile("(gcp|aws)\\.[a-z0-9-]{1,40}");

  /** The real endpoints. */
  public static final CloudEndpoints PRODUCTION = new CloudEndpoints(null, null, null, null, null, null);

  public CloudEndpoints {
    services = services == null ? Map.of() : Map.copyOf(services);
    for (Map.Entry<String, URI> e : services.entrySet()) {
      if (!SERVICE_KEY.matcher(e.getKey()).matches()) {
        throw new IllegalArgumentException("Not a cloud service key: " + e.getKey());
      }
      String scheme = Objects.requireNonNull(e.getValue(), e.getKey()).getScheme();
      if (!"http".equals(scheme) && !"https".equals(scheme)) {
        throw new IllegalArgumentException(e.getKey() + " must be an http(s) URL");
      }
    }
  }

  /** The credential endpoints, every cloud API at its real address. */
  public CloudEndpoints(URI gcpTokenUri, String gcpIamEndpoint, Path gcpAdcFile, URI awsStsEndpoint,
      Path awsConfigFile, Path awsCredentialsFile) {
    this(gcpTokenUri, gcpIamEndpoint, gcpAdcFile, awsStsEndpoint, awsConfigFile, awsCredentialsFile, Map.of());
  }

  /**
   * Parses {@code gateway.cloud.endpoints}: {@code key=url} pairs separated by commas, e.g.
   * {@code gcp.logging=http://127.0.0.1:4567,aws.logs=http://127.0.0.1:4568}.
   *
   * @throws IllegalArgumentException for a pair that isn't {@code key=url}
   */
  public static Map<String, URI> parseServices(String text) {
    Map<String, URI> out = new LinkedHashMap<>();
    if (text == null) {
      return out;
    }
    for (String pair : text.split(",")) {
      String p = pair.strip();
      if (p.isEmpty()) {
        continue;
      }
      int eq = p.indexOf('=');
      if (eq <= 0 || eq == p.length() - 1) {
        throw new IllegalArgumentException("Expected key=url in gateway.cloud.endpoints, got \"" + p + "\"");
      }
      out.put(p.substring(0, eq).strip(), URI.create(p.substring(eq + 1).strip()));
    }
    return out;
  }

  /** The base URL of the cloud API {@code key} in tests, or {@code null}: the real service. */
  public URI service(String key) {
    return services.get(key);
  }

  public boolean overridden() {
    return gcpTokenUri != null || gcpIamEndpoint != null || gcpAdcFile != null || awsStsEndpoint != null
        || awsConfigFile != null || awsCredentialsFile != null || !services.isEmpty();
  }
}
