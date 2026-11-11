package ai.msrv.gaterook.connectors.azure;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connectors.cloudauth.AzureCloud;
import ai.msrv.gaterook.connectors.cloudauth.AzureCloud.Audience;
import ai.msrv.gaterook.connectors.cloudauth.AzureCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Azure Resource Manager over {@code java.net.http}, with an Entra ID token of the connection for Resource Manager.
 *
 * <p>Every URL is the gateway's own: the cloud's Resource Manager host (or, in a development build, the test endpoint
 * the core configured with the test sign-in) and a path built from checked, percent-encoded segments
 * ({@link ResourceIds}). The token goes only where {@link AzureCredentials#sendsTokensTo} allows it, never to a
 * {@code nextLink} or {@code Location} of another host; redirects are never followed; answers are read up to 8 MB;
 * every request runs against the call's deadline. Each token's tenant must be the connection's pinned tenant, and the
 * token goes to the call's taint.
 */
final class Arm {

  static final long MAX_BODY = 8L * 1024 * 1024;
  static final Duration MARGIN = Duration.ofMillis(150);
  static final Duration MAX_REQUEST = Duration.ofSeconds(30);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** One answer of Resource Manager. {@code body} is JSON, else an object holding the text under {@code text}. */
  record Answer(int status, Map<String, List<String>> headers, JsonNode body, boolean truncated) {
    boolean ok() {
      return status / 100 == 2;
    }

    String header(String name) {
      for (Map.Entry<String, List<String>> e : headers.entrySet()) {
        if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
          return e.getValue().getFirst();
        }
      }
      return null;
    }
  }

  private final HttpClient http;
  private final CloudCredentials cloud;
  private final CloudCredentials.Owner owner;
  private final Settings settings;
  private final SecretResolver secrets;
  private final ExecContext ctx;
  private final boolean pinned;

  /**
   * @param pinned whether tokens must belong to the connection's pinned tenant; Test connection reads without the pin
   *               and records the tenant it found
   */
  Arm(HttpClient http, CloudCredentials cloud, CloudCredentials.Owner owner, Settings settings, SecretResolver secrets,
      ExecContext ctx, boolean pinned) {
    this.http = http;
    this.cloud = cloud;
    this.owner = owner;
    this.settings = settings;
    this.secrets = secrets;
    this.ctx = ctx;
    this.pinned = pinned;
  }

  /** A client for Resource Manager: HTTP/1.1, no proxy, no redirects; no per-connection state. */
  static HttpClient client() {
    return HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER)
        .proxy(HttpClient.Builder.NO_PROXY)
        .connectTimeout(Duration.ofSeconds(5))
        .build();
  }

  ExecContext ctx() {
    return ctx;
  }

  AzureCloud cloudOf() {
    return settings.credentials().cloud();
  }

  /** {@code <base><path>?api-version=…&…}: the path already encoded, the parameters encoded here. */
  URI url(String encodedPath, Map<String, String> query) {
    URI base = cloud.azure().base(cloudOf(), Audience.ARM);
    String b = base.toString().endsWith("/") ? base.toString().substring(0, base.toString().length() - 1)
        : base.toString();
    StringBuilder q = new StringBuilder();
    for (Map.Entry<String, String> e : query.entrySet()) {
      q.append(q.isEmpty() ? '?' : '&').append(encode(e.getKey())).append('=').append(encode(e.getValue()));
    }
    return URI.create(b + encodedPath + q);
  }

  /** A query map with {@code api-version} first. */
  static Map<String, String> query(String apiVersion) {
    Map<String, String> q = new LinkedHashMap<>();
    q.put("api-version", apiVersion);
    return q;
  }

  static String encode(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }

  /** The call's token for Resource Manager, of the pinned tenant, tainted. */
  AzureCredentials.AccessToken token() throws ConnectorException {
    AzureCredentials.AccessToken t = cloud.azure().token(owner, settings.credentials(), Audience.ARM, secrets,
        ctx.remaining().minus(MARGIN));
    ctx.taint(t.value());
    if (pinned) {
      String pin = settings.pinnedTenant();
      if (pin == null) {
        throw ConnectorException.denied("Test the connection once: GateRook pins the Azure tenant its sign-in "
            + "belongs to before agents read through it.");
      }
      if (t.tenantId() != null && !pin.equals(t.tenantId())) {
        cloud.azure().invalidate(owner.id(), Audience.ARM);
        throw ConnectorException.denied("The Azure sign-in now belongs to tenant " + t.tenantId() + ", not " + pin
            + ". Sign in to the connection's tenant (az login --tenant " + pin + "), or test the connection again.");
      }
    }
    return t;
  }

  /** A GET whose answer must be 2xx: other answers become errors for the agent. */
  JsonNode get(URI uri, String what) throws ConnectorException {
    Answer a = send("GET", uri, null, what);
    if (!a.ok()) {
      throw error(a, what);
    }
    return a.body();
  }

  /** A read-only POST (Resource Graph, metrics batch): the answer must be 2xx. */
  JsonNode post(URI uri, JsonNode body, String what) throws ConnectorException {
    Answer a = send("POST", uri, body, what);
    if (!a.ok()) {
      throw error(a, what);
    }
    return a.body();
  }

  /** One request, any answer: {@code azure_call} hands 4xx answers to the agent as data. */
  Answer send(String method, URI uri, JsonNode body, String what) throws ConnectorException {
    if (!cloud.azure().sendsTokensTo(cloudOf(), Audience.ARM, uri)) {
      throw new IllegalArgumentException("Not a Resource Manager URL of " + cloudOf().id() + ": " + uri.getScheme()
          + "://" + uri.getHost());
    }
    AzureCredentials.AccessToken token = token();
    HttpRequest.Builder b = HttpRequest.newBuilder(uri)
        .header("Authorization", "Bearer " + token.value())
        .header("Accept", "application/json")
        .header("x-ms-client-request-id", requestId());
    HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(body.toString());
    if (body != null) {
      b.header("Content-Type", "application/json");
    }
    b.method(method, publisher);
    Duration left = ctx.remaining().minus(MARGIN);
    if (left.isNegative() || left.isZero()) {
      throw ConnectorException.timeout(what + " didn't answer in time.");
    }
    HttpRequest built = b.timeout(left.compareTo(MAX_REQUEST) > 0 ? MAX_REQUEST : left).build();
    int status;
    byte[] bytes;
    boolean truncated;
    Map<String, List<String>> headers;
    try {
      HttpResponse<InputStream> r = http.send(built, HttpResponse.BodyHandlers.ofInputStream());
      status = r.statusCode();
      headers = r.headers().map();
      try (InputStream in = r.body()) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[16384];
        long total = 0;
        int n;
        truncated = false;
        while ((n = in.read(buf)) > 0) {
          total += n;
          if (total > MAX_BODY) {
            truncated = true;
            break;
          }
          out.write(buf, 0, n);
        }
        bytes = out.toByteArray();
      }
    } catch (HttpConnectTimeoutException e) {
      throw ConnectorException.unavailable("Can't connect to " + host(uri) + " (timed out). Check that this computer "
          + "can reach Azure.", e);
    } catch (HttpTimeoutException e) {
      throw ConnectorException.timeout(what + " didn't answer in time.");
    } catch (ConnectException e) {
      throw ConnectorException.unavailable("Can't connect to " + host(uri) + ": " + reason(e) + ".", e);
    } catch (SSLException e) {
      throw ConnectorException.unavailable("The TLS handshake with " + host(uri) + " failed: " + reason(e) + ".", e);
    } catch (IOException e) {
      throw ConnectorException.unavailable(what + " failed: " + reason(e), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout(what + " was cancelled.");
    }
    if (truncated && status / 100 == 2) {
      throw ConnectorException.resourceError(what + " answered with more than " + (MAX_BODY >> 20) + " MB. Narrow "
          + "the request ($filter, $top, $select).", null);
    }
    if (status == 401) {
      cloud.azure().invalidate(owner.id(), Audience.ARM);
    }
    JsonNode tree;
    if (bytes.length == 0) {
      tree = JsonNodeFactory.instance.objectNode();
    } else {
      try {
        tree = JSON.readTree(bytes);
      } catch (JacksonException e) {
        tree = JsonNodeFactory.instance.objectNode().put("text", excerpt(new String(bytes, StandardCharsets.UTF_8)));
      }
    }
    return new Answer(status, headers, tree, truncated);
  }

  /** What a Resource Manager refusal means and what to do. */
  ConnectorException error(Answer a, String what) {
    JsonNode error = a.body().path("error");
    String code = error.path("code").asString("");
    String message = excerpt(error.path("message").asString(a.body().path("text").asString("")));
    String identity = settings.credentials().identity();
    return switch (a.status()) {
      case 400 -> ConnectorException.badInput(what + " refused the request (" + code + "): " + message);
      case 401 -> ConnectorException.unavailable(what + " refused the token (" + code + "): " + message
          + ". Sign in again (az login or Test connection), then try again.", null);
      case 403 -> ConnectorException.resourceError(what + " denied " + identity + " (" + code + "): " + message
          + (code.equals("AuthorizationFailed") ? " Assign the Reader role on the subscription or resource group."
              : ""), null);
      case 404 -> ConnectorException.resourceError(what + ": not found (" + code + "). " + message, null);
      case 429 -> ConnectorException.resourceError(what + " is throttled by Azure Resource Manager"
          + (a.header("Retry-After") == null ? "" : ": retry after " + a.header("Retry-After") + " s")
          + ". Requests are shared with people and other tools.", null);
      default -> a.status() >= 500
          ? ConnectorException.unavailable(what + " failed (" + a.status() + (code.isEmpty() ? "" : ", " + code)
              + "): " + message, null)
          : ConnectorException.resourceError(what + " answered " + a.status() + (code.isEmpty() ? "" : " (" + code
              + ")") + ": " + message, null);
    };
  }

  private String requestId() {
    String id = ctx.callId();
    return id == null || !id.matches("[A-Za-z0-9_-]{1,64}") ? java.util.UUID.randomUUID().toString() : id;
  }

  static String excerpt(String text) {
    if (text == null || text.isBlank()) {
      return "";
    }
    String t = OutputSanitizer.sanitize(text.strip());
    return t.length() <= 600 ? t : t.substring(0, 599) + "…";
  }

  static String host(URI uri) {
    return uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
  }

  private static String reason(Throwable e) {
    String m = e.getMessage();
    return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
  }
}
