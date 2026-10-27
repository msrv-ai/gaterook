package ai.msrv.gaterook.connectors.kubernetes;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.http.HttpClient;
import io.fabric8.kubernetes.client.internal.SSLUtils;
import io.fabric8.kubernetes.client.jdkhttp.JdkHttpClientFactory;
import io.fabric8.kubernetes.client.utils.BackwardsCompatibilityInterceptor;
import io.fabric8.kubernetes.client.utils.ImpersonatorInterceptor;
import io.fabric8.kubernetes.client.utils.TokenRefreshInterceptor;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connectors.support.Pem;
import ai.msrv.gaterook.connectors.support.TrustStores;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;

/**
 * HTTP clients to API servers, one per connection version and credentials ({@link ClusterAccess#fingerprint}), closed
 * when the connection changes or goes away.
 *
 * <p>A client is built from {@code Config.empty()} — never {@code new ConfigBuilder()}, which reads
 * {@code ~/.kube/config}, the environment and a pod's service account — with retries off, the connection's timeouts,
 * no credentials in the config (the connector adds the bearer token to each request itself),
 * our own trust (the cluster's CA <em>instead of</em> the platform's, like kubectl; Fabric8 would add it to them),
 * no redirects (a 3xx must not carry the token elsewhere), no proxy unless the connection names one (the JDK would
 * use the process's proxy properties) and virtual threads for the client's work.
 */
final class K8sClients implements AutoCloseable {

  /** Clients of connections being tested are not kept. */
  static final String DRAFT_PREFIX = "draft-";

  private static final int MAX_CLIENTS = 32;
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

  /** One API server as the connector talks to it. */
  record Client(K8sApi api, ClusterAccess access, ExecCredentials exec, HttpClient http) implements AutoCloseable {
    @Override
    public void close() {
      http.close();
    }
  }

  private record Key(String connectionId, long version, String fingerprint) {}

  private final Map<Key, Client> clients = new ConcurrentHashMap<>();
  private final String userAgent;
  private final Clock clock;

  K8sClients(String version, Clock clock) {
    this.userAgent = "mcp-gateway/" + version;
    this.clock = clock;
  }

  /**
   * The client of this connection version and access; a draft's client is the caller's to close
   * ({@link #cached(String)} says which).
   */
  Client client(String connectionId, long version, ClusterAccess access, int requestTimeoutMs)
      throws ConnectorException {
    if (!cached(connectionId)) {
      return build(access, requestTimeoutMs);
    }
    Key key = new Key(connectionId, version, access.fingerprint());
    Client existing = clients.get(key);
    if (existing != null) {
      return existing;
    }
    Client built = build(access, requestTimeoutMs);
    Client raced = clients.putIfAbsent(key, built);
    if (raced != null) {
      built.close();
      return raced;
    }
    // Older versions and credentials of the same connection are of no use any more.
    clients.entrySet().removeIf(e -> {
      boolean stale = e.getKey().connectionId().equals(connectionId) && !e.getKey().equals(key);
      if (stale) {
        e.getValue().close();
      }
      return stale;
    });
    trim();
    return built;
  }

  static boolean cached(String connectionId) {
    return !connectionId.startsWith(DRAFT_PREFIX);
  }

  void evict(String connectionId) {
    clients.entrySet().removeIf(e -> {
      boolean match = e.getKey().connectionId().equals(connectionId);
      if (match) {
        e.getValue().close();
      }
      return match;
    });
  }

  private void trim() {
    Iterator<Map.Entry<Key, Client>> it = clients.entrySet().iterator();
    while (clients.size() > MAX_CLIENTS && it.hasNext()) {
      Map.Entry<Key, Client> e = it.next();
      it.remove();
      e.getValue().close();
    }
  }

  @Override
  public void close() {
    clients.values().forEach(Client::close);
    clients.clear();
  }

  private Client build(ClusterAccess access, int requestTimeoutMs) throws ConnectorException {
    ExecCredentials exec = access.exec() == null ? null : new ExecCredentials(access.exec(), clock);
    ConfigBuilder b = new ConfigBuilder(Config.empty())
        .withMasterUrl(access.server().toString())
        .withConnectionTimeout((int) CONNECT_TIMEOUT.toMillis())
        .withRequestTimeout(requestTimeoutMs)
        .withRequestRetryBackoffLimit(0)
        .withUserAgent(userAgent)
        .withTrustCerts(access.insecure());
    if (access.clientCertPem() != null) {
      b.withClientCertData(base64(access.clientCertPem()))
          .withClientKeyData(base64(access.clientKeyPem()))
          .withClientKeyAlgo(keyAlgorithm(access.clientKeyPem()));
    }
    if (access.proxy() != null) {
      b.withHttpsProxy(access.proxy().toString());
    }
    Config config = b.build();
    GatewayHttpClients factory = new GatewayHttpClients(access.proxy() != null);
    HttpClient.Builder hb;
    try {
      // newBuilder applies the config, client key included; then our trust replaces Fabric8's.
      hb = factory.newBuilder(config);
      hb.sslContext(keyManagers(config), trustManagers(access));
    } catch (ConnectorException e) {
      factory.executor.shutdownNow();
      throw e;
    } catch (Exception e) {
      factory.executor.shutdownNow();
      throw ConnectorException.badInput("The client certificate or key of the connection can't be used ("
          + e.getClass().getSimpleName() + ").");
    }
    // It retries failed requests against older API versions behind our back; the connector names its versions.
    hb.addOrReplaceInterceptor(BackwardsCompatibilityInterceptor.NAME, null);
    // The bearer token is the connector's to send: Fabric8's interceptor would resend a request after a 401 with
    // the same token, or re-read a kubeconfig; impersonation is refused with the kubeconfig.
    hb.addOrReplaceInterceptor(TokenRefreshInterceptor.NAME, null);
    hb.addOrReplaceInterceptor(ImpersonatorInterceptor.NAME, null);
    HttpClient http = hb.build();
    return new Client(new K8sApi(http, access.server().toString(), Duration.ofMillis(requestTimeoutMs),
        access.token(), exec), access, exec, http);
  }

  private static KeyManager[] keyManagers(Config config) throws Exception {
    return config.getClientCertData() == null ? null : SSLUtils.keyManagers(config);
  }

  private static TrustManager[] trustManagers(ClusterAccess access) throws Exception {
    if (access.insecure()) {
      return SSLUtils.trustManagers(null, null, true, null, null);
    }
    if (access.caPem() == null) {
      return new TrustManager[] {TrustStores.platform()};
    }
    List<X509Certificate> ca;
    try {
      ca = Pem.certificates(access.caPem());
    } catch (IllegalArgumentException e) {
      throw ConnectorException.badInput("The cluster's CA certificate isn't PEM: " + e.getMessage());
    }
    if (ca.isEmpty()) {
      throw ConnectorException.badInput("The cluster's CA certificate has no -----BEGIN CERTIFICATE----- block.");
    }
    return new TrustManager[] {TrustStores.of(ca, false)};
  }

  /** Fabric8 reads a key as RSA unless told otherwise; SEC1 and PKCS#8 EC keys need "EC" (report 08 §2). */
  static String keyAlgorithm(String keyPem) {
    if (keyPem.contains("BEGIN EC PRIVATE KEY")) {
      return "EC";
    }
    if (keyPem.contains("BEGIN RSA PRIVATE KEY")) {
      return "RSA";
    }
    try {
      return Pem.privateKey(keyPem).getAlgorithm();
    } catch (IllegalArgumentException e) {
      return "RSA";
    }
  }

  private static String base64(String pem) {
    return Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * The JDK client of Fabric8 with our defaults: no redirects, no system proxy, virtual threads. One factory per
   * client, so closing the client shuts down exactly its executor.
   */
  static final class GatewayHttpClients extends JdkHttpClientFactory {
    private final boolean proxied;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    GatewayHttpClients(boolean proxied) {
      this.proxied = proxied;
    }

    @Override
    protected java.net.http.HttpClient.Builder createNewHttpClientBuilder() {
      return java.net.http.HttpClient.newBuilder().executor(executor);
    }

    @Override
    protected void additionalConfig(java.net.http.HttpClient.Builder builder) {
      builder.followRedirects(java.net.http.HttpClient.Redirect.NEVER);
      if (!proxied) {
        builder.proxy(java.net.http.HttpClient.Builder.NO_PROXY);
      }
    }

    @Override
    protected void closeHttpClient(io.fabric8.kubernetes.client.jdkhttp.JdkHttpClientImpl client) {
      executor.shutdownNow();
    }
  }
}
