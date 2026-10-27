package ai.msrv.gaterook.connectors.logs;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connectors.support.Pem;
import ai.msrv.gaterook.connectors.support.TrustStores;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;

/**
 * Clients the Logs connector keeps between calls, one per connection and config version (report 07 §7): a
 * {@code java.net.http} client for Loki and OpenSearch (with the connection's CA), one shared client for Google's
 * APIs, and a CloudWatch Logs client. A new config version, a rotated secret or a removed connection drops them.
 * Credentials are never part of a cached client: every request carries the credentials of its own call.
 */
final class Clients implements AutoCloseable {

  /** The only CloudWatch Logs operations the connector may call: reading. Anything else fails before the network. */
  static final Set<String> CLOUDWATCH_OPERATIONS = Set.of("StartQuery", "GetQueryResults", "StopQuery",
      "FilterLogEvents", "DescribeLogGroups");

  /** A client built without credentials: a request that forgets its own fails instead of going out unsigned. */
  private static final AwsCredentialsProvider NO_CREDENTIALS = () -> {
    throw SdkClientException.create("GateRook sends every CloudWatch request with the credentials of its call");
  };

  private record Versioned<T>(long version, String fingerprint, T client) {}

  private final Map<String, Versioned<HttpClient>> http = new ConcurrentHashMap<>();
  private final Map<String, Versioned<CloudWatchLogsClient>> cloudWatch = new ConcurrentHashMap<>();
  private final SdkHttpClient awsHttp = UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5))
      .socketTimeout(Duration.ofSeconds(30)).build();
  private volatile HttpClient google;

  /** The client of an HTTP backend; {@code caCertificate} is the connection's private CA (PEM) or {@code null}. */
  HttpClient http(ConnectionConfig cfg, String caCertificate) throws ConnectorException {
    String fingerprint = caCertificate == null ? "" : Integer.toHexString(caCertificate.hashCode());
    Versioned<HttpClient> current = http.get(cfg.id());
    if (current != null && current.version() == cfg.version() && current.fingerprint().equals(fingerprint)) {
      return current.client();
    }
    HttpClient client = Http.client(caCertificate == null ? null : sslContext(caCertificate));
    Versioned<HttpClient> replaced = http.put(cfg.id(), new Versioned<>(cfg.version(), fingerprint, client));
    if (replaced != null && replaced.client() != client) {
      // Calls in flight on the old version finish; nothing new starts on it.
      replaced.client().shutdown();
    }
    return client;
  }

  /** The client for {@code logging.googleapis.com} and Cloud Resource Manager: no per-connection state. */
  HttpClient google() {
    HttpClient c = google;
    if (c == null) {
      synchronized (this) {
        if (google == null) {
          google = Http.client(null);
        }
        c = google;
      }
    }
    return c;
  }

  /** The CloudWatch Logs client of a connection, in its region, limited to {@link #CLOUDWATCH_OPERATIONS}. */
  CloudWatchLogsClient cloudWatch(ConnectionConfig cfg, String region, URI endpointOverride) {
    String fingerprint = region + "|" + endpointOverride;
    Versioned<CloudWatchLogsClient> current = cloudWatch.get(cfg.id());
    if (current != null && current.version() == cfg.version() && current.fingerprint().equals(fingerprint)) {
      return current.client();
    }
    var builder = CloudWatchLogsClient.builder()
        .region(Region.of(region))
        .httpClient(awsHttp)
        .credentialsProvider(NO_CREDENTIALS)
        .overrideConfiguration(o -> o.addExecutionInterceptor(new OperationAllowlist()));
    if (endpointOverride != null) {
      builder.endpointOverride(endpointOverride);
    }
    CloudWatchLogsClient client = builder.build();
    Versioned<CloudWatchLogsClient> replaced = cloudWatch.put(cfg.id(),
        new Versioned<>(cfg.version(), fingerprint, client));
    if (replaced != null && replaced.client() != client) {
      replaced.client().close();
    }
    return client;
  }

  /** Drops every client of a connection that changed or was removed. */
  void evict(String connectionId) {
    Versioned<HttpClient> h = http.remove(connectionId);
    if (h != null) {
      h.client().shutdown();
    }
    Versioned<CloudWatchLogsClient> c = cloudWatch.remove(connectionId);
    if (c != null) {
      c.client().close();
    }
  }

  @Override
  public void close() {
    http.values().forEach(v -> v.client().shutdownNow());
    http.clear();
    cloudWatch.values().forEach(v -> v.client().close());
    cloudWatch.clear();
    HttpClient g = google;
    if (g != null) {
      g.shutdownNow();
    }
    awsHttp.close();
  }

  private static javax.net.ssl.SSLContext sslContext(String pem) throws ConnectorException {
    List<X509Certificate> certificates;
    try {
      certificates = Pem.certificates(pem);
    } catch (IllegalArgumentException e) {
      throw ConnectorException.badInput("The CA certificate isn't valid PEM: " + e.getMessage());
    }
    if (certificates.isEmpty()) {
      throw ConnectorException.badInput("The CA certificate field has no certificate.");
    }
    return TrustStores.sslContext(TrustStores.of(certificates, true));
  }

  /** Defence in depth: the connector can't be talked into a call that changes or deletes logs. */
  static final class OperationAllowlist implements ExecutionInterceptor {
    @Override
    public void beforeExecution(Context.BeforeExecution context, ExecutionAttributes attributes) {
      String operation = attributes.getAttribute(SdkExecutionAttribute.OPERATION_NAME);
      if (!CLOUDWATCH_OPERATIONS.contains(operation)) {
        throw new SecurityException("Blocked CloudWatch Logs " + operation + ": the Logs connector only reads");
      }
    }
  }
}
