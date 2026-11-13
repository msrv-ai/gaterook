package com.gaterook.connectors.firebase;

import com.gaterook.connectors.support.PrettyJson;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connectors.cloudauth.CloudCredentials;
import com.gaterook.connectors.cloudauth.GcpSpec;
import com.gaterook.connectors.support.OutputSanitizer;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import javax.net.ssl.SSLException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Firestore's REST API and the Realtime Database on {@code java.net.http}, with google-auth (through
 * connectors/cloud-auth) only for the token: HTTP/1.1, no proxy, redirects never followed (the token must not travel),
 * nothing retried. Each request runs against the call's deadline, every answer is read up to a cap, and the token goes
 * to the call's taint. A request whose URL may not carry a Google token is refused before a token exists
 * ({@link CloudCredentials#sendsGoogleTokensTo} for Firestore, {@link CloudCredentials#sendsGoogleTokensToRealtimeDatabase}
 * for a database), and every URL is the connector's own: a fixed base and segments it encoded itself.
 */
final class GoogleHttp implements AutoCloseable {

  /** Time kept back from the deadline for building the answer. */
  static final Duration MARGIN = Duration.ofMillis(150);
  static final Duration MAX_REQUEST = Duration.ofSeconds(30);
  /** Largest answer read whole: a listing, a document, a commit. */
  static final long MAX_BODY = 16L * 1024 * 1024;
  /** Largest error answer read. */
  static final long MAX_ERROR = 64L * 1024;
  static final JsonMapper JSON = JsonMapper.builder().build();
  /** A change's text in the approval and its bytes on the wire: the same writer, the same bytes. */
  static final ObjectWriter PRETTY = PrettyJson.writer(JSON);

  private final CloudCredentials cloud;
  private volatile HttpClient client;

  GoogleHttp(CloudCredentials cloud) {
    this.cloud = cloud;
  }

  /** One call's way to Google: the connection, its credentials and the token's scopes, the call's secrets and context. */
  record Caller(ConnectionConfig cfg, GcpSpec spec, List<String> scopes, SecretResolver secrets, ExecContext ctx) {
    CloudCredentials.Owner owner() {
      return new CloudCredentials.Owner(cfg.id(), cfg.version(), cfg.name());
    }
  }

  CloudCredentials cloud() {
    return cloud;
  }

  /** The caller's Google credentials, cached by connectors/cloud-auth per connection, version and scopes. */
  GoogleCredentials credentials(Caller c) throws ConnectorException {
    return cloud.gcp(c.owner(), c.spec(), c.scopes(), c.secrets());
  }

  /**
   * The headers of a request to {@code uri}: google-auth's ({@code Authorization}, {@code x-goog-user-project}), or
   * {@code Authorization} alone. Refused, before any token is fetched, when {@code sendsTo} says {@code uri} may get
   * none.
   */
  Map<String, List<String>> token(Caller c, URI uri, Predicate<URI> sendsTo, boolean all) throws ConnectorException {
    if (!sendsTo.test(uri)) {
      throw ConnectorException.denied("GateRook sends this connection's Google token only to Google's own hosts, not "
          + "to " + host(uri) + ".");
    }
    Map<String, List<String>> headers = CloudCredentials.requestMetadata(credentials(c), uri, c.ctx()::taint);
    if (all) {
      return headers;
    }
    for (Map.Entry<String, List<String>> h : headers.entrySet()) {
      if (h.getKey().equalsIgnoreCase("Authorization")) {
        return Map.of(h.getKey(), h.getValue());
      }
    }
    return Map.of();
  }

  /**
   * Sends one request and returns the answer with its body open: the caller reads it within a cap and closes it.
   *
   * @param body   JSON, or {@code null}
   * @param what   who answers, for messages: {@code Firestore}, {@code The Realtime Database}
   * @param change whether the request changes data: a connection lost after sending it leaves the outcome unknown
   */
  Answer send(ExecContext ctx, String method, URI uri, Map<String, List<String>> headers, byte[] body, String what,
      boolean change) throws ConnectorException {
    requirePlainPath(uri);
    Duration left = ctx.remaining().minus(MARGIN);
    if (left.isNegative() || left.isZero()) {
      throw change ? ConnectorException.timeout(what + " wasn't asked to change anything: no time was left.")
          : ConnectorException.timeout(what + " didn't answer in time.");
    }
    HttpRequest.Builder request = HttpRequest.newBuilder(uri)
        .timeout(left.compareTo(MAX_REQUEST) > 0 ? MAX_REQUEST : left)
        .header("Accept", "application/json");
    for (Map.Entry<String, List<String>> h : headers.entrySet()) {
      for (String v : h.getValue()) {
        request.header(h.getKey(), v);
      }
    }
    if (body != null) {
      request.header("Content-Type", "application/json; charset=utf-8");
      request.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
    } else {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    }
    HttpResponse<InputStream> response;
    try {
      response = client().send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
    } catch (HttpConnectTimeoutException e) {
      throw ConnectorException.unavailable("Can't connect to " + host(uri) + " (timed out). Check that this computer "
          + "can reach Google.", e);
    } catch (HttpTimeoutException e) {
      if (change) {
        throw ConnectorException.outcomeUnknown(what + " didn't answer the change in time, so whether it ran is not "
            + "known. Check the data before retrying.", e);
      }
      throw ConnectorException.timeout(what + " didn't answer in time.");
    } catch (ConnectException e) {
      throw ConnectorException.unavailable("Can't connect to " + host(uri) + ": " + reason(e) + ".", e);
    } catch (SSLException e) {
      throw ConnectorException.unavailable("The TLS handshake with " + host(uri) + " failed: " + reason(e) + ".", e);
    } catch (IOException e) {
      if (change) {
        throw ConnectorException.outcomeUnknown("The connection to " + host(uri) + " failed during the change, so "
            + "whether it ran is not known. Check the data before retrying.", e);
      }
      throw ConnectorException.unavailable(what + " failed: " + reason(e) + ".", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (change) {
        throw ConnectorException.outcomeUnknown(what + " was cancelled during the change, so whether it ran is not "
            + "known. Check the data before retrying.", e);
      }
      throw ConnectorException.timeout(what + " was cancelled.");
    }
    Capped in = new Capped(response.body());
    ctx.onCancel(in::closeQuietly);
    return new Answer(response.statusCode(), in, what);
  }

  /** An answer: its status and its body, read within a cap. */
  static final class Answer implements AutoCloseable {
    final int status;
    final Capped body;
    private final String what;

    Answer(int status, Capped body, String what) {
      this.status = status;
      this.body = body;
      this.what = what;
    }

    boolean ok() {
      return status / 100 == 2;
    }

    /** The whole body, at most {@code max} bytes. */
    byte[] bytes(long max) throws ConnectorException {
      body.cap(max);
      ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
      byte[] buf = new byte[16384];
      try {
        int n;
        while ((n = body.read(buf)) > 0) {
          out.write(buf, 0, n);
        }
      } catch (IOException e) {
        if (body.exceeded) {
          throw ConnectorException.resourceError(what + "'s answer is larger than " + size(max) + ".", null);
        }
        throw ConnectorException.unavailable(what + " broke off its answer: " + reason(e) + ".", e);
      }
      return out.toByteArray();
    }

    /** The whole body as JSON, at most {@code max} bytes; an empty body is {@code {}}. */
    JsonNode json(long max) throws ConnectorException {
      byte[] bytes = bytes(max);
      if (bytes.length == 0) {
        return JsonNodeFactory.instance.objectNode();
      }
      try {
        return JSON.readTree(bytes);
      } catch (JacksonException e) {
        throw ConnectorException.resourceError(what + " answered " + status + " with something that isn't JSON: "
            + excerpt(OutputSanitizer.decode(bytes, 0, Math.min(bytes.length, 200))), null);
      }
    }

    /** The JSON of an error answer, or {@code null} when it has none. */
    JsonNode error() {
      try {
        byte[] bytes = bytes(MAX_ERROR);
        return bytes.length == 0 ? null : JSON.readTree(bytes);
      } catch (ConnectorException | JacksonException e) {
        return null;
      }
    }

    @Override
    public void close() {
      body.closeQuietly();
    }
  }

  /** The body of an answer, counted: past its cap a read fails and {@link #exceeded} says why. */
  static final class Capped extends FilterInputStream {
    private long count;
    private long cap = Long.MAX_VALUE;
    volatile boolean exceeded;

    Capped(InputStream in) {
      super(in);
    }

    void cap(long max) {
      cap = max;
    }

    long count() {
      return count;
    }

    @Override
    public int read() throws IOException {
      int b = super.read();
      if (b >= 0) {
        counted(1);
      }
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int n = super.read(b, off, len);
      if (n > 0) {
        counted(n);
      }
      return n;
    }

    private void counted(int n) throws IOException {
      count += n;
      if (count > cap) {
        exceeded = true;
        throw new IOException("The answer is larger than " + cap + " bytes");
      }
    }

    void closeQuietly() {
      try {
        close();
      } catch (IOException e) {
        // Nothing to do: the answer is abandoned.
      }
    }
  }

  /**
   * A path of the connector's own making has no dot segment and no empty one, raw or percent-encoded: Java's HTTP
   * client sends {@code ..} as it is, and a server that resolves it would answer a path nobody checked (defence in
   * depth: every segment was checked and encoded before).
   */
  static void requirePlainPath(URI uri) {
    String path = uri.getPath() == null ? "" : uri.getPath();
    String raw = uri.getRawPath() == null ? "" : uri.getRawPath().toLowerCase(Locale.ROOT);
    for (String segment : path.split("/", -1)) {
      if (segment.equals(".") || segment.equals("..")) {
        throw new IllegalArgumentException("A dot segment in a Firebase path");
      }
    }
    if (path.contains("//") || raw.contains("%2e") || raw.contains("%2f") || raw.contains("%5c")) {
      throw new IllegalArgumentException("An empty or encoded segment in a Firebase path");
    }
  }

  private HttpClient client() {
    HttpClient c = client;
    if (c == null) {
      synchronized (this) {
        if (client == null) {
          client = HttpClient.newBuilder()
              .version(HttpClient.Version.HTTP_1_1)
              .followRedirects(HttpClient.Redirect.NEVER)
              .proxy(HttpClient.Builder.NO_PROXY)
              .connectTimeout(Duration.ofSeconds(5))
              .build();
        }
        c = client;
      }
    }
    return c;
  }

  @Override
  public void close() {
    HttpClient c = client;
    if (c != null) {
      c.shutdownNow();
    }
  }

  static String host(URI uri) {
    if (uri.getHost() == null) {
      return "this address";
    }
    return uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
  }

  static String excerpt(String text) {
    if (text == null || text.isBlank()) {
      return "";
    }
    String t = OutputSanitizer.sanitize(text.strip());
    return t.length() <= 500 ? t : t.substring(0, 499) + "…";
  }

  /** {@code text} without its closing period, to go on inside a sentence. */
  static String sentence(String text) {
    String t = text.strip();
    while (t.endsWith(".")) {
      t = t.substring(0, t.length() - 1);
    }
    return t;
  }

  static String reason(Throwable e) {
    String m = e.getMessage();
    if (m == null || m.isBlank()) {
      return e.getClass().getSimpleName();
    }
    int nl = m.indexOf('\n');
    String line = (nl < 0 ? m : m.substring(0, nl)).strip();
    return line.endsWith(".") ? line.substring(0, line.length() - 1) : line;
  }

  static String size(long bytes) {
    return bytes >= 1024 * 1024 && bytes % (1024 * 1024) == 0 ? (bytes >> 20) + " MiB"
        : bytes >= 1024 ? (bytes >> 10) + " KB" : bytes + " bytes";
  }
}
