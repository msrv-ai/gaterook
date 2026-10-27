package ai.msrv.gaterook.connectors.aws;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudcontrol.CloudControlClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;

/**
 * The Cloud Control and CloudWatch clients of each connection and region (report 09 §7 {@code AwsClients}), kept
 * between calls and dropped when the connection changes. Every client:
 * <ul>
 *   <li>runs on the URL-connection HTTP client (no Apache, no Netty: ARCHITECTURE.md §1);</li>
 *   <li>names its endpoint explicitly, so {@code AWS_ENDPOINT_URL*}, {@code aws.endpointUrl} or a profile's
 *       {@code endpoint_url} can't move signed requests elsewhere (report 09 §6 "HTTP");</li>
 *   <li>has no credentials of its own: a request without its call's credentials fails instead of going out unsigned
 *       or signed by someone else;</li>
 *   <li>refuses every operation but the five reads ({@link ReadOnlyGuard}).</li>
 * </ul>
 */
final class Clients implements AutoCloseable {

  /** A client built without credentials: every request must carry the credentials of its own call. */
  static final AwsCredentialsProvider NO_CREDENTIALS = () -> {
    throw SdkClientException.create("MCP Gateway sends every AWS request with the credentials of its call");
  };

  private record Versioned<T>(long version, String fingerprint, T client) {}

  private final Map<String, Versioned<CloudControlClient>> cloudControl = new ConcurrentHashMap<>();
  private final Map<String, Versioned<CloudWatchClient>> cloudWatch = new ConcurrentHashMap<>();
  private final SdkHttpClient http = UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5))
      .socketTimeout(Duration.ofSeconds(30)).build();

  /** The real Cloud Control endpoint of a region. */
  static URI cloudControlEndpoint(String region) {
    return URI.create("https://cloudcontrolapi." + region + "." + Settings.dnsSuffix(region));
  }

  /** The real CloudWatch endpoint of a region. */
  static URI cloudWatchEndpoint(String region) {
    return URI.create("https://monitoring." + region + "." + Settings.dnsSuffix(region));
  }

  /** @param test the test endpoint of a development build, or {@code null}: the real one */
  CloudControlClient cloudControl(ConnectionConfig cfg, String region, URI test) {
    URI endpoint = test != null ? test : cloudControlEndpoint(region);
    return cached(cloudControl, cfg, region, endpoint, e -> CloudControlClient.builder()
        .region(Region.of(region))
        .httpClient(http)
        .credentialsProvider(NO_CREDENTIALS)
        .endpointOverride(e)
        .overrideConfiguration(o -> o.addExecutionInterceptor(new ReadOnlyGuard()))
        .build());
  }

  /** @param test the test endpoint of a development build, or {@code null}: the real one */
  CloudWatchClient cloudWatch(ConnectionConfig cfg, String region, URI test) {
    URI endpoint = test != null ? test : cloudWatchEndpoint(region);
    return cached(cloudWatch, cfg, region, endpoint, e -> CloudWatchClient.builder()
        .region(Region.of(region))
        .httpClient(http)
        .credentialsProvider(NO_CREDENTIALS)
        .endpointOverride(e)
        .overrideConfiguration(o -> o.addExecutionInterceptor(new ReadOnlyGuard()))
        .build());
  }

  private static <T extends AutoCloseable> T cached(Map<String, Versioned<T>> clients, ConnectionConfig cfg,
      String region, URI endpoint, Function<URI, T> build) {
    String key = cfg.id() + "|" + region;
    String fingerprint = endpoint.toString();
    List<T> replaced = new ArrayList<>(1);
    // Atomic per key: two first calls at once build one client, and neither closes the other's.
    Versioned<T> current = clients.compute(key, (k, was) -> {
      if (was != null && was.version() == cfg.version() && was.fingerprint().equals(fingerprint)) {
        return was;
      }
      if (was != null) {
        replaced.add(was.client());
      }
      return new Versioned<>(cfg.version(), fingerprint, build.apply(endpoint));
    });
    // A client of the connection's previous version: the connection changed, as evict() would close it; the shared
    // HTTP client stays open.
    replaced.forEach(Clients::closeQuietly);
    return current.client();
  }

  /** How many clients are kept (tests). */
  int size() {
    return cloudControl.size() + cloudWatch.size();
  }

  /** Drops every client of a connection that changed or was removed. */
  void evict(String connectionId) {
    cloudControl.entrySet().removeIf(e -> {
      if (e.getKey().startsWith(connectionId + "|")) {
        closeQuietly(e.getValue().client());
        return true;
      }
      return false;
    });
    cloudWatch.entrySet().removeIf(e -> {
      if (e.getKey().startsWith(connectionId + "|")) {
        closeQuietly(e.getValue().client());
        return true;
      }
      return false;
    });
  }

  @Override
  public void close() {
    cloudControl.values().forEach(v -> closeQuietly(v.client()));
    cloudControl.clear();
    cloudWatch.values().forEach(v -> closeQuietly(v.client()));
    cloudWatch.clear();
    http.close();
  }

  private static void closeQuietly(AutoCloseable c) {
    try {
      c.close();
    } catch (Exception e) {
      // Closing a client releases nothing the connector needs afterwards.
    }
  }
}
