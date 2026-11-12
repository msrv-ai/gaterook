package ai.msrv.gaterook.connectors.prometheus;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import ai.msrv.gaterook.connectors.support.Pem;
import ai.msrv.gaterook.connectors.support.TrustStores;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The HTTP side of the connector on {@code java.net.http}. Every request is a GET of a {@link Plan}'s fixed read path
 * under the connection's own URL, with every parameter encoded: no request body, no other method, no other host.
 * Redirects are never followed (a token must not travel to another host), TLS is verified with the system's CAs and
 * an optional private CA, each request runs against the call's deadline, and an answer is read up to the
 * connection's size limit. The password or token is resolved per request and goes to the call's taint.
 */
final class PrometheusApi implements AutoCloseable {

  /** Time kept back from the deadline for building the answer. */
  static final Duration MARGIN = Duration.ofMillis(150);
  /** Time the server's evaluation leaves for sending its answer. */
  static final Duration TRANSFER = Duration.ofMillis(250);
  /** No single request waits longer than this, whatever the deadline. */
  static final Duration MAX_REQUEST = Duration.ofSeconds(60);
  /** What is read of an error's body: the server's message is at its start. */
  static final int MAX_ERROR_BODY = 64 * 1024;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** One call's way to the server. */
  record Call(ConnectionConfig cfg, PrometheusSettings settings, SecretResolver secrets, ExecContext ctx) {}

  /**
   * One answer.
   *
   * @param cut          whether the body was longer than the connection's limit and only its start was read
   * @param authenticate the {@code WWW-Authenticate} header of a 401 (the sign-in the server asks for), or {@code ""}
   */
  record Answer(int status, byte[] body, boolean cut, String contentType, long elapsedMs, String authenticate) {
    boolean ok() {
      return status / 100 == 2;
    }
  }

  private record Versioned(long version, String fingerprint, HttpClient client) {}

  private final Map<String, Versioned> clients = new ConcurrentHashMap<>();

  /** Sends {@code plan} and reads its answer; a refusal of the server is an exception with what to do. */
  Answer send(Call call, Plan plan) throws ConnectorException {
    Answer a = fetch(call, plan);
    if (!a.ok()) {
      throw error(a, plan, call.settings());
    }
    return a;
  }

  /** Sends {@code plan} and reads its answer whatever its status. */
  Answer fetch(Call call, Plan plan) throws ConnectorException {
    ExecContext ctx = call.ctx();
    Duration left = ctx.remaining().minus(MARGIN);
    if (left.compareTo(Duration.ofMillis(200)) < 0) {
      throw ConnectorException.timeout(what(plan) + " had no time left to run.");
    }
    String timeout = plan.evaluates ? Math.max(100, left.minus(TRANSFER).toMillis()) + "ms" : null;
    URI uri = uri(call.settings(), plan, timeout);
    // Where the request goes is judged before any credential is resolved for it.
    Addresses.of(ctx).check(uri);
    HttpRequest.Builder request = HttpRequest.newBuilder(uri).GET().header("Accept", "application/json")
        .timeout(left.compareTo(MAX_REQUEST) > 0 ? MAX_REQUEST : left);
    if (call.settings().tenant() != null) {
      request.header("X-Scope-OrgID", call.settings().tenant());
    }
    String authorization = authorization(call);
    if (authorization != null) {
      request.header("Authorization", authorization);
    }
    HttpClient client = client(call.cfg(), call.settings());
    long started = System.nanoTime();
    try {
      HttpResponse<InputStream> r = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
      long cap = r.statusCode() / 100 == 2 ? call.settings().maxResponseBytes() : MAX_ERROR_BODY;
      byte[] body;
      boolean cut;
      try (InputStream in = r.body()) {
        // The request's timeout ends with the headers: the body is read against the call's deadline, and a cancelled
        // call closes it under a read that waits.
        ctx.onCancel(() -> closeQuietly(in));
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        cut = read(in, out, cap, ctx);
        body = out.toByteArray();
      }
      return new Answer(r.statusCode(), body, cut, r.headers().firstValue("Content-Type").orElse(""),
          (System.nanoTime() - started) / 1_000_000, r.headers().firstValue("WWW-Authenticate").orElse(""));
    } catch (HttpConnectTimeoutException e) {
      throw ConnectorException.unavailable("Can't connect to " + host(uri) + " (timed out). Check the URL and that "
          + "this computer can reach the server.", e);
    } catch (HttpTimeoutException e) {
      throw ConnectorException.timeout(what(plan) + " didn't answer in time." + (plan.evaluates ? " Narrow the "
          + "selector, shorten the range or raise step." : ""));
    } catch (ConnectException e) {
      throw ConnectorException.unavailable("Can't connect to " + host(uri) + ": " + reason(e) + ". Check the URL and "
          + "that the server is running.", e);
    } catch (SSLException e) {
      throw ConnectorException.unavailable("The TLS handshake with " + host(uri) + " failed: " + reason(e) + ". If a "
          + "private CA signed the server's certificate, paste that CA in the connection.", e);
    } catch (IOException e) {
      throw ConnectorException.unavailable(what(plan) + " failed: " + reason(e) + ".", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout(what(plan) + " was cancelled.");
    }
  }

  /**
   * The request's URI: the server's URL and the plan's fixed path, nothing an agent wrote outside the encoded
   * parameters. Checked once more against the URL, so no path can leave it.
   */
  static URI uri(PrometheusSettings s, Plan plan, String timeout) {
    URI uri = s.base().resolve(plan.path() + plan.queryString(timeout));
    String base = s.base().toString();
    if (!uri.toString().startsWith(base + plan.endpoint.path) || uri.getRawFragment() != null) {
      throw new IllegalStateException("A request left the server's URL");
    }
    return uri;
  }

  /** The {@code Authorization} value of the call, or {@code null}; the secret and its encoding join the taint. */
  private static String authorization(Call call) throws ConnectorException {
    PrometheusSettings s = call.settings();
    if (s.auth() == PrometheusSettings.Auth.NONE) {
      return null;
    }
    String kind = s.auth() == PrometheusSettings.Auth.BASIC ? "password" : "token";
    if (!call.secrets().isSet(PrometheusSettings.SECRET)) {
      throw ConnectorException.unavailable("The connection has no stored " + kind + ": set it under Credentials.",
          null);
    }
    try (SecretResolver.Secret secret = call.secrets().secret(PrometheusSettings.SECRET)) {
      String value = secret.unsafeString();
      call.ctx().taint(value);
      if (value.isEmpty() || value.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
        throw ConnectorException.badInput("The stored " + kind + " is empty or holds a line break or another "
            + "control character, which no HTTP header carries. Store it again under Credentials.");
      }
      if (s.auth() == PrometheusSettings.Auth.BEARER) {
        return "Bearer " + value;
      }
      String encoded = Base64.getEncoder().encodeToString((s.username() + ":" + value)
          .getBytes(StandardCharsets.UTF_8));
      call.ctx().taint(encoded);
      return "Basic " + encoded;
    }
  }

  /** The client of a connection: HTTP/1.1, no proxy, no redirects, the system's CAs and the connection's own. */
  private HttpClient client(ConnectionConfig cfg, PrometheusSettings s) throws ConnectorException {
    String ca = s.caCertificate();
    String fingerprint = ca == null ? "" : Integer.toHexString(ca.hashCode()) + ":" + ca.length();
    Versioned current = clients.get(cfg.id());
    if (current != null && current.version() == cfg.version() && current.fingerprint().equals(fingerprint)) {
      return current.client();
    }
    HttpClient.Builder b = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER)
        .proxy(HttpClient.Builder.NO_PROXY)
        .connectTimeout(Duration.ofSeconds(5));
    if (ca != null) {
      b.sslContext(sslContext(ca));
    }
    HttpClient client = b.build();
    Versioned replaced = clients.put(cfg.id(), new Versioned(cfg.version(), fingerprint, client));
    if (replaced != null && replaced.client() != client) {
      // Calls in flight on the old version finish; nothing new starts on it.
      replaced.client().shutdown();
    }
    return client;
  }

  private static SSLContext sslContext(String pem) throws ConnectorException {
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
  }

  /** Reads up to {@code cap} bytes before the call's deadline; whether there was more. */
  private static boolean read(InputStream in, ByteArrayOutputStream out, long cap, ExecContext ctx)
      throws IOException {
    byte[] buf = new byte[16384];
    long total = 0;
    int n;
    while ((n = in.read(buf)) > 0) {
      if (total + n > cap) {
        out.write(buf, 0, (int) (cap - total));
        return true;
      }
      total += n;
      out.write(buf, 0, n);
      if (ctx.remaining().isZero()) {
        throw new HttpTimeoutException("the answer didn't arrive before the call's deadline");
      }
    }
    return false;
  }

  private static void closeQuietly(InputStream in) {
    try {
      in.close();
    } catch (IOException e) {
      // The call is over either way.
    }
  }

  // ── Errors ────────────────────────────────────────────────────────────────────────────────────────────────

  /** The server's refusal with what to do: the tenant to set, the credentials to check, the URL to fix. */
  static ConnectorException error(Answer a, Plan plan, PrometheusSettings s) {
    String errorType = "";
    String message = "";
    String text = new String(a.body(), StandardCharsets.UTF_8);
    boolean html = a.contentType().toLowerCase(Locale.ROOT).contains("html") || text.stripLeading().startsWith("<");
    if (!html) {
      try {
        JsonNode tree = JSON.readTree(a.body());
        errorType = tree.path("errorType").asString("");
        message = tree.path("error").asString("");
      } catch (JacksonException e) {
        message = text;
      }
    }
    String said = excerpt(message);
    String words = said.isEmpty() ? (html ? " with an HTML page" : "") : ": " + said;
    String what = what(plan);
    int status = a.status();
    if (status / 100 == 3) {
      return ConnectorException.unavailable("The server answered with a redirect (" + status + "). GateRook doesn't "
          + "follow redirects: put the address it redirects to in the connection's URL.", null);
    }
    return switch (status) {
      case 400 -> ConnectorException.badInput("The server refused " + what + words + ".");
      case 401 -> said.toLowerCase(Locale.ROOT).contains("no org id")
          ? ConnectorException.resourceError("The server needs a tenant: set Tenant (X-Scope-OrgID) in the "
              + "connection.", null)
          : ConnectorException.resourceError("The server refused the credentials (401)" + words + ". Check "
              + (s.auth() == PrometheusSettings.Auth.NONE ? "whether it needs a sign-in, and set one" : "the "
              + (s.auth() == PrometheusSettings.Auth.BASIC ? "username and password" : "token")) + " under "
              + "Credentials.", null);
      case 403 -> ConnectorException.resourceError("The server denied " + what + " (403)" + words + ". The "
          + "credentials may not read /" + plan.endpoint.path + ".", null);
      case 404 -> ConnectorException.resourceError(switch (plan.endpoint) {
        case TARGETS -> "This server has no list of scrape targets (404): Mimir, Cortex and servers fed by remote "
            + "write scrape nothing. Query up instead.";
        case RULES, ALERTS -> "This server serves no " + (plan.endpoint == Plan.Endpoint.RULES ? "rules" : "alerts")
            + " (404): its ruler may be off, or rules are evaluated elsewhere.";
        case METADATA -> "This server serves no metric metadata (404).";
        default -> "Nothing answers at /" + plan.endpoint.path + " of " + s.target() + " (404). Check the server URL: "
            + "Mimir's ends in /prometheus, Grafana Cloud's in /api/prom.";
      }, null);
      case 405 -> ConnectorException.resourceError("The server doesn't take GET at /" + plan.endpoint.path + " (405).",
          null);
      case 414 -> ConnectorException.badInput("The request is longer than the server takes (414): shorten the query "
          + "or send fewer selectors.");
      case 422 -> ConnectorException.resourceError("The server couldn't run " + what + words + ". Narrow the "
          + "selector, shorten the range or raise step.", null);
      case 429 -> ConnectorException.resourceError("The server is limiting requests (429)" + words + ". Try again "
          + "in a few seconds, or narrow the query.", null);
      case 503 -> errorType.equals("timeout") || errorType.equals("canceled")
          ? ConnectorException.timeout("The server stopped " + what + " at its time limit" + words + ". Narrow the "
              + "selector, shorten the range or raise step.")
          : ConnectorException.unavailable("The server is unavailable (503)" + words + ".", null);
      default -> status >= 500
          ? ConnectorException.unavailable("The server failed (" + status + ")" + words + ".", null)
          : ConnectorException.resourceError("The server answered " + status + words + ".", null);
    };
  }

  /** What the request is, for messages: {@code the query}, {@code the series lookup}. */
  static String what(Plan plan) {
    return switch (plan.endpoint) {
      case QUERY, QUERY_RANGE -> "the query";
      case SERIES -> "the series lookup";
      case LABELS -> "the label names";
      case LABEL_VALUES -> "the label values";
      case METADATA -> "the metadata";
      case TARGETS -> "the targets";
      case RULES -> "the rules";
      case ALERTS -> "the alerts";
      case BUILD_INFO -> "the build information";
    };
  }

  /** The first line of what the server said, sanitized and short. */
  static String excerpt(String s) {
    if (s == null || s.isBlank()) {
      return "";
    }
    String line = s.strip();
    int nl = line.indexOf('\n');
    if (nl >= 0) {
      line = line.substring(0, nl).strip();
    }
    String clean = OutputSanitizer.sanitize(line);
    if (clean.endsWith(".")) {
      clean = clean.substring(0, clean.length() - 1);
    }
    return clean.length() <= 500 ? clean : clean.substring(0, 499) + "…";
  }

  static String host(URI uri) {
    return uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
  }

  private static String reason(Throwable e) {
    String m = e.getMessage();
    return m == null || m.isBlank() ? e.getClass().getSimpleName() : excerpt(m);
  }
}
