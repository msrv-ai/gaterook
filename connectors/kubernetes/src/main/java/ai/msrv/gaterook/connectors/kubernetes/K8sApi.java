package ai.msrv.gaterook.connectors.kubernetes;

import io.fabric8.kubernetes.client.http.HttpClient;
import io.fabric8.kubernetes.client.http.HttpRequest;
import io.fabric8.kubernetes.client.http.HttpResponse;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.URLEncoder;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The REST calls of the connector to one API server, over Fabric8's HTTP client (its TLS material and auth
 * interceptors), with every answer read as a JSON tree: no model class of Fabric8 is (de)serialized, so nothing here
 * needs reflection metadata in the native image.
 *
 * <p>Each request gets the time that is left of the call (and at most the connection's request timeout); no request
 * is retried by the client (report 08 §1: ten silent retries turned a 50 ms validation error into 19 s).
 */
final class K8sApi {

  static final JsonMapper JSON = JsonMapper.builder().build();

  static final String ACCEPT_JSON = "application/json";
  /** Server-side printing: the columns of {@code kubectl get} for any kind, with the objects' metadata. */
  static final String ACCEPT_TABLE = "application/json;as=Table;v=v1;g=meta.k8s.io,"
      + "application/json;as=Table;v=v1beta1;g=meta.k8s.io,application/json";
  /** Aggregated discovery: every group, version and resource in one answer (Kubernetes ≥ 1.27). */
  static final String ACCEPT_DISCOVERY = "application/json;g=apidiscovery.k8s.io;v=v2;as=APIGroupDiscoveryList,"
      + "application/json";
  static final String APPLY_PATCH = "application/apply-patch+yaml";
  static final String MERGE_PATCH = "application/merge-patch+json";

  /** Answers larger than this are refused, not parsed: a list that big needs a selector or a limit. */
  static final int MAX_BODY = 16 << 20;

  private final HttpClient http;
  private final String base;
  private final Duration requestTimeout;
  private final String token;
  private final ExecCredentials exec;

  /**
   * @param token the bearer token of the connection, or {@code null}
   * @param exec the credential command that gives the token instead, or {@code null}
   */
  K8sApi(HttpClient http, String base, Duration requestTimeout, String token, ExecCredentials exec) {
    this.http = http;
    this.base = base.endsWith("/") ? base : base + "/";
    this.requestTimeout = requestTimeout;
    this.token = token;
    this.exec = exec;
  }

  /** An error answer of the API server: its code and its {@code Status} (reason, message, causes). */
  static final class StatusException extends Exception {
    private static final long serialVersionUID = 1L;
    final int code;
    final String reason;
    final transient List<JsonNode> causes;

    StatusException(int code, String reason, String message, List<JsonNode> causes) {
      super(message);
      this.code = code;
      this.reason = reason;
      this.causes = causes;
    }
  }

  /** Query parameters in order; {@code null} values are left out. */
  static final class Query {
    private final Map<String, String> params = new LinkedHashMap<>();

    Query put(String name, Object value) {
      if (value != null) {
        params.put(name, String.valueOf(value));
      }
      return this;
    }

    String text() {
      if (params.isEmpty()) {
        return "";
      }
      StringBuilder out = new StringBuilder("?");
      for (Map.Entry<String, String> e : params.entrySet()) {
        if (out.length() > 1) {
          out.append('&');
        }
        out.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
            .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8).replace("+", "%20"));
      }
      return out.toString();
    }
  }

  static Query query() {
    return new Query();
  }

  JsonNode get(String path, Query query, String accept, ExecContext ctx) throws StatusException, ConnectorException {
    return send("GET", path, query, accept, null, null, ctx);
  }

  JsonNode post(String path, JsonNode body, ExecContext ctx) throws StatusException, ConnectorException {
    return send("POST", path, query(), ACCEPT_JSON, ACCEPT_JSON, JSON.writeValueAsString(body), ctx);
  }

  JsonNode patch(String path, Query query, String contentType, String body, ExecContext ctx)
      throws StatusException, ConnectorException {
    return send("PATCH", path, query, ACCEPT_JSON, contentType, body, ctx);
  }

  /**
   * One request and its JSON answer. Errors: the API server's own ({@link StatusException}), or a
   * {@link ConnectorException} when it couldn't be reached or didn't answer in time.
   */
  JsonNode send(String method, String path, Query query, String accept, String contentType, String body,
      ExecContext ctx) throws StatusException, ConnectorException {
    HttpResponse<InputStream> r = exchange(method, path, query, accept, contentType, body, ctx, false);
    // The request timeout ends with the headers; a server that stalls in the body is cut off at the deadline.
    Thread watchdog = closeAt(r.body(), ctx.remaining());
    try (InputStream in = r.body()) {
      byte[] bytes = readUpTo(in, MAX_BODY, path);
      if (r.code() >= 400) {
        throw status(r.code(), bytes);
      }
      if (bytes.length == 0) {
        return JSON.createObjectNode();
      }
      try {
        return JSON.readTree(bytes);
      } catch (RuntimeException e) {
        throw ConnectorException.resourceError("The API server answered " + path + " with something that isn't "
            + "JSON.", null);
      }
    } catch (IOException e) {
      throw ctx.remaining().isZero() ? timedOut(method) : failure(e, method);
    } finally {
      watchdog.interrupt();
    }
  }

  /** Closes {@code in} after {@code after} unless interrupted first: a blocked read then ends with an IOException. */
  static Thread closeAt(InputStream in, Duration after) {
    return Thread.ofVirtual().start(() -> {
      try {
        Thread.sleep(after);
        closeQuietly(in);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
  }

  /** A streamed answer (pod logs); the caller closes it. An error answer is read and thrown. */
  InputStream stream(String path, Query query, ExecContext ctx) throws StatusException, ConnectorException {
    HttpResponse<InputStream> r = exchange("GET", path, query, "*/*", null, null, ctx, true);
    if (r.code() >= 400) {
      try (InputStream in = r.body()) {
        throw status(r.code(), readUpTo(in, 64 * 1024, path));
      } catch (IOException e) {
        throw failure(e, "GET");
      }
    }
    return r.body();
  }

  private HttpResponse<InputStream> exchange(String method, String path, Query query, String accept,
      String contentType, String body, ExecContext ctx, boolean streaming) throws ConnectorException {
    for (int attempt = 0; ; attempt++) {
      Duration timeout = timeout(ctx);
      String bearer = token;
      if (exec != null) {
        bearer = exec.token(timeout);
        ctx.taint(bearer);
      }
      HttpRequest.Builder b = http.newHttpRequestBuilder().uri(base + path + query.text())
          .timeout(timeout.toMillis(), TimeUnit.MILLISECONDS).header("Accept", accept);
      if (bearer != null) {
        b.header("Authorization", "Bearer " + bearer);
      }
      if (body != null) {
        b.method(method, contentType, body);
      } else if (!"GET".equals(method)) {
        b.method(method, ACCEPT_JSON, "");
      }
      if (streaming) {
        b.forStreaming();
      }
      HttpResponse<InputStream> r = await(http.sendAsync(b.build(), InputStream.class), timeout, method, ctx);
      // A token from a credential command may have been revoked before it expired: run the command once more.
      if (r.code() == 401 && exec != null && attempt == 0) {
        closeQuietly(r.body());
        exec.invalidate();
        continue;
      }
      if (r.code() >= 300 && r.code() < 400) {
        // Redirects are off: following one would carry the token to wherever the answer points.
        closeQuietly(r.body());
        throw ConnectorException.resourceError("The API server answered " + path + " with a redirect (" + r.code()
            + "), which MCP Gateway doesn't follow. Check the server URL of the connection.", null);
      }
      return r;
    }
  }

  private HttpResponse<InputStream> await(CompletableFuture<HttpResponse<InputStream>> future, Duration timeout,
      String method, ExecContext ctx) throws ConnectorException {
    ctx.onCancel(() -> future.cancel(true));
    try {
      return future.get(timeout.toMillis() + 500, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout("The call was stopped before the API server answered.");
    } catch (TimeoutException e) {
      future.cancel(true);
      throw timedOut(method);
    } catch (ExecutionException e) {
      throw failure(e.getCause(), method);
    }
  }

  private Duration timeout(ExecContext ctx) throws ConnectorException {
    Duration left = ctx.remaining();
    if (left.toMillis() < 50) {
      throw ConnectorException.timeout("No time was left for the API server.");
    }
    return left.compareTo(requestTimeout) < 0 ? left : requestTimeout;
  }

  /**
   * Couldn't reach or didn't hear back. For a write the request may have arrived: the approved change is then
   * "outcome unknown" and never retried; reads and connection failures before sending are plain unavailability.
   */
  private static ConnectorException failure(Throwable cause, String method) {
    Throwable c = cause;
    while (c != null && (c instanceof ExecutionException || c instanceof java.util.concurrent.CompletionException
        || c.getClass().getName().equals("io.fabric8.kubernetes.client.KubernetesClientException")
        && c.getCause() != null)) {
      c = c.getCause();
    }
    // The HTTP client wraps the cause it saw (an IOException around the handshake failure): look down the chain.
    Throwable connect = find(c, HttpConnectTimeoutException.class, ConnectException.class,
        UnresolvedAddressException.class, java.net.UnknownHostException.class);
    if (connect != null) {
      return ConnectorException.unavailable("The API server can't be reached: " + describe(connect)
          + ". Check the address, the VPN and the proxy.", c);
    }
    Throwable tls = find(c, SSLException.class);
    if (tls != null) {
      return ConnectorException.unavailable("TLS with the API server failed: " + describe(tls) + ". Check the CA "
          + "certificate of the connection.", c);
    }
    if (find(c, HttpTimeoutException.class, InterruptedIOException.class) != null) {
      return timedOut(method);
    }
    if (!"GET".equals(method)) {
      return ConnectorException.outcomeUnknown("The connection to the API server broke after the " + method
          + " was sent: it may have been applied. Check with k8s_get before you ask again.", c);
    }
    return ConnectorException.unavailable("The API server can't be reached: " + describe(c) + ".", c);
  }

  @SafeVarargs
  private static Throwable find(Throwable t, Class<? extends Throwable>... types) {
    for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
      for (Class<? extends Throwable> type : types) {
        if (type.isInstance(c)) {
          return c;
        }
      }
    }
    return null;
  }

  private static ConnectorException timedOut(String method) {
    if (!"GET".equals(method)) {
      return ConnectorException.outcomeUnknown("The API server didn't answer the " + method + " in time: it may "
          + "have been applied. Check with k8s_get before you ask again.", null);
    }
    return ConnectorException.timeout("The API server didn't answer in time.");
  }

  private static String describe(Throwable c) {
    String m = c.getMessage();
    return m == null || m.isBlank() ? c.getClass().getSimpleName() : m;
  }

  /** The {@code Status} of an error answer; a body that isn't one keeps its first line. */
  static StatusException status(int code, byte[] body) {
    String reason = null;
    String message = null;
    List<JsonNode> causes = new ArrayList<>();
    try {
      JsonNode s = JSON.readTree(body);
      if ("Status".equals(s.path("kind").asString())) {
        reason = s.path("reason").asString(null);
        message = s.path("message").asString(null);
        s.path("details").path("causes").forEach(causes::add);
      }
    } catch (RuntimeException e) {
      // Not JSON: a proxy or a load balancer answered.
    }
    if (message == null) {
      String text = new String(body, StandardCharsets.UTF_8).strip();
      message = text.isEmpty() ? "HTTP " + code : text.lines().findFirst().orElse("").strip();
      if (message.length() > 300) {
        message = message.substring(0, 300) + "…";
      }
    }
    return new StatusException(code, reason, message, List.copyOf(causes));
  }

  private static byte[] readUpTo(InputStream in, int max, String path) throws IOException, ConnectorException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[16 * 1024];
    int n;
    while ((n = in.read(buf)) >= 0) {
      if (out.size() + n > max) {
        throw ConnectorException.resourceError("The API server's answer to " + path + " is larger than "
            + (max >> 20) + " MB. Narrow it with a selector or a smaller limit.", null);
      }
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  private static void closeQuietly(InputStream in) {
    try {
      in.close();
    } catch (IOException ignored) {
      // Nothing to read from a 401.
    }
  }

  /** A path segment the agent named, percent-encoded ({@code /} and {@code ?} can't leave the segment). */
  static String segment(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
