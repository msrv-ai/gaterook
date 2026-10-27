package ai.msrv.gaterook.connectors.gcp;

import com.google.auth.oauth2.GoogleCredentials;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
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
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SSLException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Google's REST APIs on {@code java.net.http}, with google-auth only for the token (report 09 §2, §7): Cloud Asset,
 * Cloud Monitoring, Cloud Resource Manager and the eight services of {@link LiveRoutes}.
 *
 * <p>Reading is the only thing this class can do: {@link #get} and {@link #testIamPermissions} (a POST that asks which
 * permissions the caller has and changes nothing) are its only requests, and every URL is the gateway's own — a base
 * of a Google API plus a path the connector built (ARCHITECTURE.md §9 "SSRF": GCP REST is checked against its allowlist
 * before the request, not by the egress guard). Redirects are never followed (the token must not travel), answers are
 * read up to 8 MB, every request runs against the call's deadline, and the access token goes to the call's taint.
 */
final class GoogleApi {

  /** Largest answer one request may give (report 09 §6 "HTTP"). */
  static final long MAX_BODY = 8L * 1024 * 1024;
  /** Time kept back from the deadline for building the answer. */
  static final Duration MARGIN = Duration.ofMillis(150);
  static final Duration MAX_REQUEST = Duration.ofSeconds(30);

  static final URI ASSET = URI.create("https://cloudasset.googleapis.com");
  static final URI MONITORING = URI.create("https://monitoring.googleapis.com");
  static final URI RESOURCE_MANAGER = URI.create("https://cloudresourcemanager.googleapis.com");

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** A refusal of a Google API, with what Google said: callers degrade on some of them (a live read denied). */
  static final class GoogleException extends ConnectorException {
    private static final long serialVersionUID = 1L;
    final int status;
    final String reason;

    GoogleException(Kind kind, String message, int status, String reason) {
      super(kind, message, null);
      this.status = status;
      this.reason = reason;
    }
  }

  private final HttpClient http;
  private final CloudCredentials cloud;
  private final CloudCredentials.Owner owner;
  private final Settings settings;
  private final SecretResolver secrets;
  private final ExecContext ctx;

  GoogleApi(HttpClient http, CloudCredentials cloud, CloudCredentials.Owner owner, Settings settings,
      SecretResolver secrets, ExecContext ctx) {
    this.http = http;
    this.cloud = cloud;
    this.owner = owner;
    this.settings = settings;
    this.secrets = secrets;
    this.ctx = ctx;
  }

  /** A client for Google's APIs: HTTP/1.1, no proxy, no redirects; no per-connection state. */
  static HttpClient client() {
    return HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER)
        .proxy(HttpClient.Builder.NO_PROXY)
        .connectTimeout(Duration.ofSeconds(5))
        .build();
  }

  /** The base URL of a Google API: the test endpoint of {@code key} in development builds, else {@code real}. */
  static URI base(CloudEndpoints endpoints, String key, URI real) {
    URI test = endpoints.service(key);
    return test != null ? test : real;
  }

  ExecContext ctx() {
    return ctx;
  }

  /** The request headers of a Google call; fetches or refreshes the token and taints it. */
  Map<String, List<String>> headers(URI uri) throws ConnectorException {
    GoogleCredentials credentials = cloud.gcp(owner, settings.credentials(), List.of(CloudCredentials.CLOUD_PLATFORM),
        secrets);
    return CloudCredentials.requestMetadata(credentials, uri, ctx::taint);
  }

  JsonNode get(URI uri, String what) throws ConnectorException {
    return send(HttpRequest.newBuilder(uri).GET(), uri, what);
  }

  /**
   * {@code POST <resource>:testIamPermissions}: which of {@code permissions} the caller holds on the resource. The
   * only POST: the path must end in {@code :testIamPermissions}.
   */
  List<String> testIamPermissions(URI uri, List<String> permissions, String what) throws ConnectorException {
    if (!uri.getPath().endsWith(":testIamPermissions")) {
      throw new IllegalArgumentException("Only testIamPermissions may be posted");
    }
    ObjectNode body = JsonNodeFactory.instance.objectNode();
    permissions.forEach(body.putArray("permissions")::add);
    JsonNode answer = send(HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body.toString())), uri, what);
    List<String> granted = new java.util.ArrayList<>();
    for (JsonNode p : answer.path("permissions")) {
      if (!p.asString("").isEmpty()) {
        granted.add(p.asString());
      }
    }
    return List.copyOf(granted);
  }

  private JsonNode send(HttpRequest.Builder request, URI uri, String what) throws ConnectorException {
    requireGoogle(uri);
    requirePlainPath(uri);
    request.header("Accept", "application/json");
    for (Map.Entry<String, List<String>> h : headers(uri).entrySet()) {
      for (String v : h.getValue()) {
        request.header(h.getKey(), v);
      }
    }
    Duration left = ctx.remaining().minus(MARGIN);
    if (left.isNegative() || left.isZero()) {
      throw ConnectorException.timeout(what + " didn't answer in time.");
    }
    HttpRequest built = request.timeout(left.compareTo(MAX_REQUEST) > 0 ? MAX_REQUEST : left).build();
    int status;
    byte[] body;
    try {
      HttpResponse<InputStream> r = http.send(built, HttpResponse.BodyHandlers.ofInputStream());
      status = r.statusCode();
      try (InputStream in = r.body()) {
        body = read(in, what);
      }
    } catch (HttpConnectTimeoutException e) {
      throw ConnectorException.unavailable("Can't connect to " + host(uri) + " (timed out). Check that this computer "
          + "can reach Google Cloud.", e);
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
    JsonNode tree;
    try {
      tree = body.length == 0 ? JsonNodeFactory.instance.objectNode() : JSON.readTree(body);
    } catch (JacksonException e) {
      throw ConnectorException.resourceError(what + " answered " + status + " with something that isn't JSON: "
          + excerpt(new String(body, java.nio.charset.StandardCharsets.UTF_8)), null);
    }
    if (status / 100 != 2) {
      throw error(status, tree, what, uri);
    }
    return tree;
  }

  /**
   * A path of the gateway's own making has no dot segment and no empty one, raw or percent-encoded: Java's HTTP client
   * sends {@code ..} as it is, and a server that resolves it would answer a path nobody checked (defence in depth: each
   * caller builds its path from checked parts).
   */
  static void requirePlainPath(URI uri) {
    String path = uri.getPath() == null ? "" : uri.getPath();
    String raw = uri.getRawPath() == null ? "" : uri.getRawPath().toLowerCase(Locale.ROOT);
    for (String segment : path.split("/", -1)) {
      if (segment.equals(".") || segment.equals("..")) {
        throw new IllegalArgumentException("A dot segment in a Google API path");
      }
    }
    if (path.contains("//") || raw.contains("%2e") || raw.contains("%2f")) {
      throw new IllegalArgumentException("An empty or encoded segment in a Google API path");
    }
  }

  /**
   * The gateway's own URLs only: {@code https://<api>.googleapis.com} or, in a development build, a test endpoint the
   * core configured for the credentials ({@code gateway.cloud.endpoints}).
   */
  private void requireGoogle(URI uri) {
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    boolean google = "https".equals(uri.getScheme()) && host.endsWith(".googleapis.com") && uri.getUserInfo() == null
        && (uri.getPort() == -1 || uri.getPort() == 443);
    if (google) {
      return;
    }
    for (URI test : cloud.endpoints().services().values()) {
      if (test.getHost() != null && test.getHost().equalsIgnoreCase(uri.getHost()) && test.getPort() == uri.getPort()
          && test.getScheme().equals(uri.getScheme())) {
        return;
      }
    }
    throw new IllegalArgumentException("Not a Google API URL: " + uri.getScheme() + "://" + host);
  }

  /** What a Google refusal means and what to do (report 09 §5). */
  private GoogleException error(int status, JsonNode tree, String what, URI uri) {
    JsonNode error = tree.path("error");
    String message = excerpt(error.path("message").asString(""));
    String code = error.path("status").asString("");
    String reason = "";
    String consumer = "";
    String service = "";
    for (JsonNode d : error.path("details")) {
      if (d.path("@type").asString("").endsWith("google.rpc.ErrorInfo")) {
        reason = d.path("reason").asString("");
        consumer = d.at("/metadata/consumer").asString("");
        service = d.at("/metadata/service").asString("");
      }
    }
    String identity = settings.credentials().identity();
    if (status == 403 && (reason.equals("SERVICE_DISABLED") || reason.equals("API_DISABLED"))) {
      String project = consumer.startsWith("projects/") ? consumer.substring("projects/".length())
          : settings.consumerProject();
      String api = service.isBlank() ? uri.getHost() : service;
      return new GoogleException(ConnectorException.Kind.RESOURCE_ERROR, what + " is turned off for project "
          + project + ". Run: gcloud services enable " + api + " --project=" + project, status, reason);
    }
    return switch (status) {
      case 400 -> new GoogleException(ConnectorException.Kind.BAD_INPUT, what + " refused the request: " + message,
          status, code);
      case 401 -> new GoogleException(ConnectorException.Kind.RESOURCE_UNAVAILABLE, what + " refused the token: "
          + message + ". Sign in again (gcloud auth application-default login) or check the key, then test again.",
          status, code);
      case 403 -> new GoogleException(ConnectorException.Kind.RESOURCE_ERROR, what + " denied " + identity + ": "
          + message + roleHint(what), status, code);
      case 404 -> new GoogleException(ConnectorException.Kind.RESOURCE_ERROR, what + ": not found. " + message, status,
          code);
      case 429 -> new GoogleException(ConnectorException.Kind.RESOURCE_ERROR, what + " is out of quota ("
          + (reason.isBlank() ? code : reason) + "): the project's quota is shared with people and other tools. Try "
          + "again in a minute.", status, code);
      default -> status >= 500
          ? new GoogleException(ConnectorException.Kind.RESOURCE_UNAVAILABLE, what + " failed (" + status + "): "
              + message, status, code)
          : new GoogleException(ConnectorException.Kind.RESOURCE_ERROR, what + " answered " + status + ": " + message,
              status, code);
    };
  }

  /** The predefined role that gives what was denied (report 09 §5, §6 "Permission hints"). */
  private static String roleHint(String what) {
    return switch (what) {
      case "Cloud Asset" -> ". Grant roles/cloudasset.viewer on the scope.";
      case "Cloud Monitoring" -> ". Grant roles/monitoring.viewer on the project.";
      case "Cloud Resource Manager" -> ". Grant roles/browser on the scope.";
      default -> "";
    };
  }

  static String excerpt(String text) {
    if (text == null || text.isBlank()) {
      return "";
    }
    String t = OutputSanitizer.sanitize(text.strip());
    return t.length() <= 500 ? t : t.substring(0, 499) + "…";
  }

  static String host(URI uri) {
    return uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
  }

  private static byte[] read(InputStream in, String what) throws IOException, ConnectorException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
    byte[] buf = new byte[16384];
    long total = 0;
    int n;
    while ((n = in.read(buf)) > 0) {
      total += n;
      if (total > MAX_BODY) {
        throw ConnectorException.resourceError(what + " answered with more than " + (MAX_BODY >> 20) + " MB. Narrow "
            + "the query or lower limit.", null);
      }
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  private static String reason(Throwable e) {
    String m = e.getMessage();
    return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
  }
}
