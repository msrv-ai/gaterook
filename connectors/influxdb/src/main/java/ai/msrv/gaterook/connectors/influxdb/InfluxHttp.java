package ai.msrv.gaterook.connectors.influxdb;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connectors.influxdb.InfluxSettings.AuthMethod;
import ai.msrv.gaterook.connectors.influxdb.InfluxSettings.Family;
import ai.msrv.gaterook.connectors.support.BuiltIn;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import ai.msrv.gaterook.connectors.support.Pem;
import ai.msrv.gaterook.connectors.support.TrustStores;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * InfluxDB's HTTP APIs on {@code java.net.http}. Every request goes to the connection's base URL with one of the APIs'
 * fixed paths and encoded parameters, after {@link Addresses} judged where the name leads; redirects are never
 * followed (a token must not travel to another host), TLS is verified with the platform's CAs or the connection's
 * private CA, nothing is retried. Each request runs against the call's deadline, at most {@link #MAX_REQUEST}, and an
 * answer is read up to a cap. The token or password goes to the call's taint and never into a URL or an error.
 */
final class InfluxHttp implements AutoCloseable {

  static final long MAX_BODY = 16L * 1024 * 1024;
  static final int MAX_ERROR_BODY = 64 * 1024;
  static final Duration MARGIN = Duration.ofMillis(150);
  static final Duration MAX_REQUEST = Duration.ofSeconds(30);
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  static final String USER_AGENT = "GateRook/" + BuiltIn.VERSION;
  static final JsonMapper JSON = JsonMapper.builder().build();

  private record Versioned(long version, String fingerprint, HttpClient client) {}

  private final HttpClient shared = newClient(null);
  private final Map<String, Versioned> clients = new ConcurrentHashMap<>();

  /** One call's way to the server. */
  record Call(ConnectionConfig cfg, InfluxSettings settings, SecretResolver secrets, ExecContext ctx) {}

  /**
   * One request: a fixed path of the API and its parameters.
   *
   * @param query  {@code key, value, key, value…}; a {@code null} value leaves its key out
   * @param change whether the request changes data: an answer lost after sending leaves the outcome unknown
   */
  record Request(String method, String path, List<String> query, String contentType, byte[] body, String what,
      boolean change) {

    static Request get(String path, String what, String... query) {
      return new Request("GET", path, Collections.unmodifiableList(Arrays.asList(query)), null, null, what, false);
    }

    static Request post(String path, String contentType, byte[] body, String what, boolean change,
        String... query) {
      return new Request("POST", path, Collections.unmodifiableList(Arrays.asList(query)), contentType, body, what,
          change);
    }
  }

  /** A whole answer. */
  record Response(int status, HttpHeaders headers, byte[] body) {

    boolean ok() {
      return status / 100 == 2;
    }

    String text() {
      return new String(body, StandardCharsets.UTF_8);
    }

    String header(String name) {
      return headers.firstValue(name).orElse(null);
    }
  }

  /** Reads a streamed answer of status 2xx. */
  @FunctionalInterface
  interface StreamReader<T> {
    T read(InputStream body) throws IOException, ConnectorException;
  }

  /** The URL of {@code path} under the connection's base, with each parameter encoded. */
  static URI uri(InfluxSettings s, String path, List<String> query) {
    StringBuilder out = new StringBuilder(s.base().toString()).append(path);
    char sep = '?';
    for (int k = 0; k + 1 < query.size(); k += 2) {
      if (query.get(k + 1) == null) {
        continue;
      }
      out.append(sep).append(query.get(k)).append('=')
          .append(URLEncoder.encode(query.get(k + 1), StandardCharsets.UTF_8).replace("+", "%20"));
      sep = '&';
    }
    return URI.create(out.toString());
  }

  /** {@code application/x-www-form-urlencoded} of {@code key, value…} pairs; {@code null} values left out. */
  static byte[] form(String... pairs) {
    StringBuilder out = new StringBuilder();
    for (int k = 0; k + 1 < pairs.length; k += 2) {
      if (pairs[k + 1] == null) {
        continue;
      }
      if (!out.isEmpty()) {
        out.append('&');
      }
      out.append(pairs[k]).append('=').append(URLEncoder.encode(pairs[k + 1], StandardCharsets.UTF_8));
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  /** Sends {@code r} and reads its whole answer, up to {@code limit} bytes. */
  Response send(Call c, Request r, long limit) throws ConnectorException {
    HttpRequest request = build(c, r);
    try {
      HttpResponse<InputStream> response = client(c).send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream in = response.body()) {
        long cap = response.statusCode() / 100 == 2 ? limit : MAX_ERROR_BODY;
        return new Response(response.statusCode(), response.headers(), read(in, cap, r.what()));
      }
    } catch (IOException e) {
      throw failure(e, request.uri(), r);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw cancelled(r);
    }
  }

  private static void closeQuietly(InputStream in) {
    try {
      in.close();
    } catch (IOException ignored) {
      // The request ends either way.
    }
  }

  /** A call cancelled while its request was out: for a write, whether it ran is not known. */
  private static ConnectorException cancelled(Request r) {
    return r.change() ? ConnectorException.outcomeUnknown("The write was cancelled after it was sent, so whether the "
        + "points were written is not known. Read them back before writing again.", null)
        : ConnectorException.timeout(r.what() + " was cancelled.");
  }

  /**
   * Sends {@code r} and hands a 2xx answer's body to {@code reader} as it arrives; the body is closed after it, which
   * ends the request when the reader stopped early. Another status is thrown with the server's words.
   */
  <T> T stream(Call c, Request r, StreamReader<T> reader) throws ConnectorException {
    HttpRequest request = build(c, r);
    try {
      HttpResponse<InputStream> response = client(c).send(request, HttpResponse.BodyHandlers.ofInputStream());
      InputStream body = response.body();
      try (InputStream in = body) {
        if (response.statusCode() / 100 != 2) {
          throw Errors.of(response.statusCode(), read(in, MAX_ERROR_BODY, r.what()), r, c);
        }
        // A cancelled call ends its request: a read blocked on a stalled server returns.
        c.ctx().onCancel(() -> closeQuietly(body));
        return reader.read(in);
      }
    } catch (IOException e) {
      throw failure(e, request.uri(), r);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw cancelled(r);
    }
  }

  private HttpRequest build(Call c, Request r) throws ConnectorException {
    URI uri = uri(c.settings(), r.path(), r.query());
    Addresses.of(c.ctx()).check(uri);
    Duration left = c.ctx().remaining().minus(MARGIN);
    if (left.isNegative() || left.isZero()) {
      throw ConnectorException.timeout(r.what() + " didn't answer in time.");
    }
    HttpRequest.Builder b = HttpRequest.newBuilder(uri)
        .timeout(left.compareTo(MAX_REQUEST) > 0 ? MAX_REQUEST : left)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/json");
    String authorization = authorization(c);
    if (authorization != null) {
      b.header("Authorization", authorization);
    }
    if (r.body() != null) {
      b.header("Content-Type", r.contentType());
      b.method(r.method(), HttpRequest.BodyPublishers.ofByteArray(r.body()));
    } else {
      b.method(r.method(), HttpRequest.BodyPublishers.noBody());
    }
    return b.build();
  }

  /**
   * The {@code Authorization} header of the connection: {@code Bearer} with InfluxDB 3's token, {@code Token} with
   * InfluxDB 2.x's, {@code Basic} with InfluxDB 1.x's user and password; {@code null} without credentials. Every value
   * goes to the call's taint.
   */
  static String authorization(Call c) throws ConnectorException {
    InfluxSettings s = c.settings();
    if (s.auth() == AuthMethod.NONE) {
      return null;
    }
    String field = s.auth() == AuthMethod.TOKEN ? InfluxSettings.TOKEN : InfluxSettings.PASSWORD;
    if (!c.secrets().isSet(field)) {
      throw ConnectorException.unavailable("The connection has no stored " + (s.auth() == AuthMethod.TOKEN
          ? "API token" : "password") + ": set it under Credentials.", null);
    }
    try (SecretResolver.Secret secret = c.secrets().secret(field)) {
      String value = secret.unsafeString();
      c.ctx().taint(value);
      if (s.auth() == AuthMethod.TOKEN) {
        if (!printable(value)) {
          throw ConnectorException.unavailable("The stored API token holds characters a token doesn't have: store "
              + "it again under Credentials.", null);
        }
        return (s.family() == Family.V2 ? "Token " : "Bearer ") + value;
      }
      String basic = Base64.getEncoder().encodeToString((s.username() + ":" + value)
          .getBytes(StandardCharsets.UTF_8));
      c.ctx().taint(basic);
      return "Basic " + basic;
    }
  }

  private static boolean printable(String value) {
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if (ch < 0x21 || ch > 0x7E) {
        return false;
      }
    }
    return !value.isEmpty();
  }

  /** What went wrong on the way, in words the agent and the person can act on. */
  private static ConnectorException failure(IOException e, URI uri, Request r) {
    String host = uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
    if (e instanceof HttpConnectTimeoutException) {
      return ConnectorException.unavailable("Can't connect to " + host + " (timed out). Check the URL and that this "
          + "computer can reach it.", e);
    }
    if (e instanceof ConnectException) {
      return ConnectorException.unavailable("Can't connect to " + host + ": " + reason(e) + ". Check the URL and "
          + "that InfluxDB is running.", e);
    }
    if (e instanceof SSLHandshakeException) {
      return ConnectorException.unavailable("The TLS handshake with " + host + " failed: " + reason(e) + ". If the "
          + "server's certificate comes from a private CA, paste the CA certificate under Advanced.", e);
    }
    if (r.change()) {
      return ConnectorException.outcomeUnknown("The connection to InfluxDB failed during the write (" + reason(e)
          + "), so whether the points were written is not known. Read them back before writing again.", e);
    }
    if (Thread.currentThread().isInterrupted()) {
      return ConnectorException.timeout(r.what() + " was cancelled.");
    }
    if (e instanceof HttpTimeoutException) {
      return ConnectorException.timeout(r.what() + " didn't answer in time.");
    }
    if (e instanceof SSLException) {
      return ConnectorException.unavailable("The TLS connection to " + host + " failed: " + reason(e) + ".", e);
    }
    return ConnectorException.unavailable(r.what() + " failed: " + reason(e) + ".", e);
  }

  private static String reason(Throwable e) {
    String m = e.getMessage();
    return m == null || m.isBlank() ? e.getClass().getSimpleName() : Errors.firstLine(OutputSanitizer.sanitize(m));
  }

  /** At most {@code cap} bytes of {@code in}; more is an error, as the answer would be cut where nobody can tell. */
  static byte[] read(InputStream in, long cap, String what) throws IOException, ConnectorException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
    byte[] buf = new byte[16384];
    long total = 0;
    int n;
    while ((n = in.read(buf)) > 0) {
      total += n;
      if (total > cap) {
        if (cap == MAX_ERROR_BODY) {
          break;
        }
        throw ConnectorException.resourceError(what + " answered with more than " + (cap >> 20) + " MiB.", null);
      }
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  /** A JSON answer as a tree; text that isn't JSON is an error with the server's first words. */
  static JsonNode json(Response response, String what) throws ConnectorException {
    try {
      return response.body().length == 0 ? JSON.createObjectNode() : JSON.readTree(response.body());
    } catch (JacksonException e) {
      throw ConnectorException.resourceError(what + " answered " + response.status() + " with something that "
          + "isn't JSON: " + Errors.excerpt(response.body()), null);
    }
  }

  /** The client of a connection: the shared one, or one that trusts the connection's private CA too. */
  private HttpClient client(Call c) throws ConnectorException {
    String ca = c.settings().caCertificate();
    if (ca == null) {
      return shared;
    }
    String fingerprint = Integer.toHexString(ca.hashCode()) + ":" + ca.length();
    Versioned current = clients.get(c.cfg().id());
    if (current != null && current.version() == c.cfg().version() && current.fingerprint().equals(fingerprint)) {
      return current.client();
    }
    HttpClient client = newClient(sslContext(ca));
    Versioned replaced = clients.put(c.cfg().id(), new Versioned(c.cfg().version(), fingerprint, client));
    if (replaced != null && replaced.client() != client) {
      // Requests in flight on the old version finish; nothing new starts on it.
      replaced.client().shutdown();
    }
    return client;
  }

  private static HttpClient newClient(SSLContext ssl) {
    HttpClient.Builder b = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER)
        .proxy(HttpClient.Builder.NO_PROXY)
        .connectTimeout(CONNECT_TIMEOUT);
    if (ssl != null) {
      b.sslContext(ssl);
    }
    return b.build();
  }

  private static SSLContext sslContext(String pem) throws ConnectorException {
    List<X509Certificate> certificates;
    try {
      certificates = Pem.certificates(pem);
    } catch (IllegalArgumentException e) {
      throw ConnectorException.badInput("The CA certificate under Advanced isn't valid PEM: " + e.getMessage());
    }
    if (certificates.isEmpty()) {
      throw ConnectorException.badInput("The CA certificate under Advanced holds no certificate.");
    }
    return TrustStores.sslContext(TrustStores.of(certificates, true));
  }

  /** Drops the client of a connection that changed or was removed. */
  void evict(String connectionId) {
    Versioned v = clients.remove(connectionId);
    if (v != null) {
      v.client().shutdown();
    }
  }

  @Override
  public void close() {
    clients.values().forEach(v -> v.client().shutdownNow());
    clients.clear();
    shared.shutdownNow();
  }
}
