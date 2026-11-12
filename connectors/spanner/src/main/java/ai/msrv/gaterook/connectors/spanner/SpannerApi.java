package ai.msrv.gaterook.connectors.spanner;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import com.google.auth.oauth2.GoogleCredentials;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Spanner's REST API v1 on {@code java.net.http}, with a token of connectors/cloud-auth. Every URL is the gateway's own:
 * {@code https://spanner.googleapis.com/v1} (a development build's test endpoint {@code gcp.spanner} replaces it) and a
 * path built from checked parts. Redirects are never followed and nothing is retried; each request runs against the
 * call's deadline, a whole answer is read up to {@link #MAX_BODY} and a streamed one is closed at the deadline; the
 * token goes to the call's taint.
 */
final class SpannerApi {

  static final URI BASE = URI.create("https://spanner.googleapis.com/v1/");
  /** Data for sessions, queries and commits; admin for the database's dialect, its DDL and schema changes. */
  static final List<String> SCOPES = List.of("https://www.googleapis.com/auth/spanner.data",
      "https://www.googleapis.com/auth/spanner.admin");
  static final long MAX_BODY = 16L * 1024 * 1024;
  static final Duration MARGIN = Duration.ofMillis(150);
  static final Duration MAX_REQUEST = Duration.ofSeconds(60);
  /** A name Spanner gave a session or an operation of the connection's database: checked before it becomes a path. */
  private static final Pattern CHILD = Pattern.compile("(sessions|operations)/[A-Za-z0-9_-]{1,256}");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final CloudCredentials cloud;
  private final HttpClient http = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .followRedirects(HttpClient.Redirect.NEVER)
      .proxy(HttpClient.Builder.NO_PROXY)
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  SpannerApi(CloudCredentials cloud) {
    this.cloud = cloud;
  }

  /** One call's way to Spanner. */
  record Call(ConnectionConfig cfg, SpannerSettings settings, SecretResolver secrets, ExecContext ctx) {

    /** The database's resource path. */
    String database() {
      return path("projects", settings.project(), "instances", settings.instance(), "databases",
          settings.database());
    }

    /**
     * A session or operation name Spanner returned, as a path: it must lie under the connection's database, so an
     * answer can't send the next request elsewhere.
     */
    String child(String name) throws ConnectorException {
      String prefix = database() + "/";
      if (name == null || !name.startsWith(prefix) || !CHILD.matcher(name.substring(prefix.length())).matches()) {
        throw ConnectorException.resourceError("Spanner answered with a name outside " + settings.label() + ".", null);
      }
      return name;
    }
  }

  private URI base() {
    URI test = cloud.endpoints().service("gcp.spanner");
    if (test == null) {
      return BASE;
    }
    String s = test.toString();
    return URI.create(s.endsWith("/") ? s : s + "/");
  }

  /** {@code projects/<p>/…}: each segment percent-encoded, no dot segments. */
  static String path(String... segments) {
    StringBuilder out = new StringBuilder();
    for (String segment : segments) {
      if (segment == null || segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        throw new IllegalArgumentException("Not a Spanner path segment");
      }
      if (!out.isEmpty()) {
        out.append('/');
      }
      out.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
    }
    return out.toString();
  }

  JsonNode get(Call call, String path, String what) throws ConnectorException {
    return send(call, HttpRequest.newBuilder(uri(path)).GET(), path, what, false);
  }

  /**
   * A {@code GET} whose refusal the caller words itself (a listing the form's suggestions may not get): the answer's
   * status and JSON. A failure to connect or to sign in still throws.
   */
  Response read(Call call, String path, String what) throws ConnectorException {
    return exchange(call, HttpRequest.newBuilder(uri(path)).GET(), path, what, false);
  }

  void delete(Call call, String path, String what) throws ConnectorException {
    send(call, HttpRequest.newBuilder(uri(path)).DELETE(), path, what, false);
  }

  /** @param change whether the request changes data: a connection lost after sending it leaves the outcome unknown */
  JsonNode post(Call call, String path, ObjectNode body, String what, boolean change) throws ConnectorException {
    return send(call, HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))), path, what, change);
  }

  /**
   * {@code PATCH <database>/ddl}: the schema change's operation, or {@code null} when an operation with the request's
   * {@code operationId} already exists (ALREADY_EXISTS): the same approval already sent it.
   */
  JsonNode updateDdl(Call call, ObjectNode body, String what) throws ConnectorException {
    String path = call.database() + "/ddl";
    Response r = exchange(call, HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
        .method("PATCH", HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))), path, what, true);
    if (r.status() / 100 == 2) {
      return r.tree();
    }
    JsonNode error = r.tree().has("error") ? r.tree().path("error") : r.tree();
    if ("ALREADY_EXISTS".equals(statusName(error))) {
      return null;
    }
    throw error(r.status(), r.tree(), what, call, true);
  }

  /**
   * A streamed query ({@code executeStreamingSql}): its messages go to {@code reader} as they arrive. The answer is
   * closed when the reader stops, which ends the query on Spanner's side too, and at the call's deadline.
   */
  void stream(Call call, String path, ObjectNode body, String what, ResultStream reader) throws ConnectorException {
    URI uri = uri(path);
    HttpRequest.Builder request = HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    Duration left = authorize(call, request, uri, what);
    HttpResponse<InputStream> r;
    try {
      r = http.send(request.timeout(left).build(), HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException e) {
      throw failure(e, what, false);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout(what + " was cancelled.");
    }
    AtomicBoolean late = new AtomicBoolean();
    try (InputStream in = r.body()) {
      if (r.statusCode() / 100 != 2) {
        throw error(r.statusCode(), tree(read(in, what), r.statusCode(), what), what, call, false);
      }
      long wait = Math.max(1, call.ctx().remaining().minus(MARGIN).toMillis());
      // Reading a streamed body has no timeout of its own: closing it at the deadline ends a stalled read.
      CompletableFuture.delayedExecutor(wait, TimeUnit.MILLISECONDS).execute(() -> {
        late.set(true);
        closeQuietly(in);
      });
      call.ctx().onCancel(() -> closeQuietly(in));
      reader.read(in);
    } catch (IOException e) {
      if (late.get()) {
        throw ConnectorException.timeout(what + " didn't finish within the call's time. Narrow the query or add a "
            + "LIMIT.");
      }
      throw failure(e, what, false);
    } catch (JacksonException e) {
      if (late.get()) {
        throw ConnectorException.timeout(what + " didn't finish within the call's time. Narrow the query or add a "
            + "LIMIT.");
      }
      throw ConnectorException.resourceError(what + " answered with something that isn't Spanner's results.", null);
    } catch (ResultStream.StreamError e) {
      throw error(e.status(), e.error(), what, call, false);
    }
  }

  private static void closeQuietly(InputStream in) {
    try {
      in.close();
    } catch (IOException e) {
      // Closing ends the read either way.
    }
  }

  private URI uri(String path) {
    return base().resolve(path);
  }

  /** Signs the request for the connection's credentials; the time it may take. */
  private Duration authorize(Call call, HttpRequest.Builder request, URI uri, String what) throws ConnectorException {
    if (!cloud.sendsGoogleTokensTo(uri)) {
      // A test endpoint gets tokens only from a test token endpoint (CloudCredentials.sendsGoogleTokensTo).
      throw ConnectorException.denied("GateRook sends Google tokens only to Google's APIs.");
    }
    GoogleCredentials credentials = cloud.gcp(new CloudCredentials.Owner(call.cfg().id(), call.cfg().version(),
        call.cfg().name()), call.settings().credentials(), SCOPES, call.secrets());
    for (Map.Entry<String, List<String>> h : CloudCredentials.requestMetadata(credentials, uri, call.ctx()::taint)
        .entrySet()) {
      for (String v : h.getValue()) {
        request.header(h.getKey(), v);
      }
    }
    request.header("Accept", "application/json");
    Duration left = call.ctx().remaining().minus(MARGIN);
    if (left.isNegative() || left.isZero()) {
      throw ConnectorException.timeout(what + " didn't answer in time.");
    }
    return left.compareTo(MAX_REQUEST) > 0 ? MAX_REQUEST : left;
  }

  private JsonNode send(Call call, HttpRequest.Builder request, String path, String what, boolean change)
      throws ConnectorException {
    Response r = exchange(call, request, path, what, change);
    if (r.status() / 100 != 2) {
      throw error(r.status(), r.tree(), what, call, change);
    }
    return r.tree();
  }

  /** An answer's status and JSON. */
  record Response(int status, JsonNode tree) {}

  private Response exchange(Call call, HttpRequest.Builder request, String path, String what, boolean change)
      throws ConnectorException {
    URI uri = uri(path);
    Duration left = authorize(call, request, uri, what);
    int status;
    byte[] body;
    try {
      HttpResponse<InputStream> r = http.send(request.timeout(left).build(), HttpResponse.BodyHandlers.ofInputStream());
      status = r.statusCode();
      try (InputStream in = r.body()) {
        body = read(in, what);
      }
    } catch (IOException e) {
      throw failure(e, what, change);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout(what + " was cancelled.");
    }
    return new Response(status, tree(body, status, what));
  }

  private static JsonNode tree(byte[] body, int status, String what) throws ConnectorException {
    try {
      return body.length == 0 ? JsonNodeFactory.instance.objectNode() : JSON.readTree(body);
    } catch (JacksonException e) {
      throw ConnectorException.resourceError(what + " answered " + status + " with something that isn't JSON.", null);
    }
  }

  private static ConnectorException failure(IOException e, String what, boolean change) {
    if (e instanceof HttpConnectTimeoutException) {
      return ConnectorException.unavailable("Can't connect to Spanner (timed out). Check that this computer can "
          + "reach Google Cloud.", e);
    }
    if (e instanceof HttpTimeoutException) {
      return change ? ConnectorException.outcomeUnknown("Spanner didn't answer the change in time, so whether it was "
          + "applied is not known. Check the data before retrying.", e)
          : ConnectorException.timeout(what + " didn't answer in time.");
    }
    if (e instanceof ConnectException || e instanceof SSLException) {
      return ConnectorException.unavailable("Can't connect to Spanner: " + reason(e) + ".", e);
    }
    return change ? ConnectorException.outcomeUnknown("The connection to Spanner failed during the change, so whether "
        + "it was applied is not known. Check the data before retrying.", e)
        : ConnectorException.unavailable(what + " failed: " + reason(e) + ".", e);
  }

  /** Google's error, with what to do: the API to turn on, the role to grant, the sign-in to renew. */
  static ConnectorException error(int status, JsonNode tree, String what, Call call, boolean change) {
    JsonNode error = tree.has("error") ? tree.path("error") : tree;
    String message = OutputSanitizer.sanitize(firstLine(error.path("message").asString("")));
    String code = statusName(error);
    String reason = "";
    for (JsonNode d : error.path("details")) {
      if (d.path("@type").asString("").endsWith("google.rpc.ErrorInfo")) {
        reason = d.path("reason").asString(reason);
      }
    }
    SpannerSettings s = call.settings();
    String project = s.project();
    if (status == 403 && (reason.equals("SERVICE_DISABLED") || reason.equals("accessNotConfigured"))) {
      return ConnectorException.resourceError("The Cloud Spanner API is turned off for project " + project + ". Run: "
          + "gcloud services enable spanner.googleapis.com --project=" + project, null);
    }
    if (code.equals("ABORTED") || status == 409 && !code.equals("ALREADY_EXISTS")) {
      return ConnectorException.resourceError(what + ": Spanner aborted the transaction because another one changed "
          + "the same data, so nothing changed. Run it again.", null);
    }
    return switch (status) {
      case 400 -> ConnectorException.badInput(what + " refused the statement: " + message);
      case 401 -> ConnectorException.unavailable(what + " refused the token: " + message + ". Sign in again (gcloud "
          + "auth application-default login) or check the key, then test again.", null);
      case 403 -> ConnectorException.resourceError(what + " denied " + s.credentials().identity() + ": " + message
          + ". Grant roles/spanner.databaseReader on " + s.label() + (s.databaseRole() == null ? ""
          : " and the database role " + s.databaseRole() + " (roles/spanner.fineGrainedAccessUser)") + ".", null);
      case 404 -> ConnectorException.badInput(what + ": not found. " + message);
      case 429 -> ConnectorException.resourceError(what + " is out of quota: try again in a minute.", null);
      case 504 -> change ? ConnectorException.outcomeUnknown("Spanner's deadline passed during the change, so whether "
          + "it was applied is not known. Check the data before retrying.", null)
          : ConnectorException.timeout(what + " didn't finish in time: " + message);
      default -> status >= 500
          ? (change ? ConnectorException.outcomeUnknown("Spanner failed with " + status + " during the change, so "
              + "whether it was applied is not known. Check the data before retrying.", null)
              : ConnectorException.unavailable(what + " failed (" + status + "): " + message, null))
          : ConnectorException.resourceError(what + " answered " + status + ": " + message, null);
    };
  }

  /** The canonical name of an error's status: Google's {@code status}, or a gRPC gateway's numeric {@code code}. */
  static String statusName(JsonNode error) {
    String status = error.path("status").asString("");
    if (!status.isEmpty()) {
      return status;
    }
    int code = error.path("code").asInt(-1);
    return code >= 0 && code < GRPC.size() ? GRPC.get(code) : "";
  }

  /** gRPC's status codes by number. */
  private static final List<String> GRPC = List.of("OK", "CANCELLED", "UNKNOWN", "INVALID_ARGUMENT",
      "DEADLINE_EXCEEDED", "NOT_FOUND", "ALREADY_EXISTS", "PERMISSION_DENIED", "RESOURCE_EXHAUSTED",
      "FAILED_PRECONDITION", "ABORTED", "OUT_OF_RANGE", "UNIMPLEMENTED", "INTERNAL", "UNAVAILABLE", "DATA_LOSS",
      "UNAUTHENTICATED");

  /** The HTTP status that goes with a gRPC status name, as Google's REST APIs answer it. */
  static int httpStatus(String name) {
    return switch (name) {
      case "INVALID_ARGUMENT", "FAILED_PRECONDITION", "OUT_OF_RANGE" -> 400;
      case "UNAUTHENTICATED" -> 401;
      case "PERMISSION_DENIED" -> 403;
      case "NOT_FOUND" -> 404;
      case "ALREADY_EXISTS", "ABORTED" -> 409;
      case "RESOURCE_EXHAUSTED" -> 429;
      case "CANCELLED" -> 499;
      case "UNIMPLEMENTED" -> 501;
      case "UNAVAILABLE" -> 503;
      case "DEADLINE_EXCEEDED" -> 504;
      default -> 500;
    };
  }

  private static byte[] read(InputStream in, String what) throws IOException, ConnectorException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
    byte[] buf = new byte[16384];
    long total = 0;
    int n;
    while ((n = in.read(buf)) > 0) {
      total += n;
      if (total > MAX_BODY) {
        throw ConnectorException.resourceError(what + " answered with more than " + (MAX_BODY >> 20) + " MiB.", null);
      }
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  private static String reason(Throwable e) {
    String m = e.getMessage();
    return m == null || m.isBlank() ? e.getClass().getSimpleName() : firstLine(m);
  }

  static String firstLine(String s) {
    if (s == null) {
      return "";
    }
    int nl = s.indexOf('\n');
    String line = (nl < 0 ? s : s.substring(0, nl)).strip();
    return line.endsWith(".") ? line.substring(0, line.length() - 1) : line;
  }
}
