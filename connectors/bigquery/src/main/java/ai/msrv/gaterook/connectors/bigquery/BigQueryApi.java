package ai.msrv.gaterook.connectors.bigquery;

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
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SSLException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * BigQuery's REST API v2 on {@code java.net.http}, with a token of connectors/cloud-auth. Every URL is the gateway's
 * own: {@code https://bigquery.googleapis.com/bigquery/v2} (a development build's test endpoint {@code gcp.bigquery}
 * replaces it) and a path built from checked parts, each encoded. Redirects are never followed and nothing is
 * retried; each request runs against the call's deadline and an answer is read up to {@link #MAX_BODY}; the token
 * goes to the call's taint.
 */
final class BigQueryApi {

  static final URI BASE = URI.create("https://bigquery.googleapis.com/bigquery/v2/");
  static final String SCOPE = "https://www.googleapis.com/auth/bigquery";
  static final long MAX_BODY = 16L * 1024 * 1024;
  static final Duration MARGIN = Duration.ofMillis(150);
  static final Duration MAX_REQUEST = Duration.ofSeconds(30);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final CloudCredentials cloud;
  private final HttpClient http = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .followRedirects(HttpClient.Redirect.NEVER)
      .proxy(HttpClient.Builder.NO_PROXY)
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  BigQueryApi(CloudCredentials cloud) {
    this.cloud = cloud;
  }

  /** One call's way to BigQuery. */
  record Call(ConnectionConfig cfg, BigQuerySettings settings, SecretResolver secrets, ExecContext ctx) {}

  private URI base() {
    URI test = cloud.endpoints().service("gcp.bigquery");
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
        throw new IllegalArgumentException("Not a BigQuery path segment");
      }
      if (!out.isEmpty()) {
        out.append('/');
      }
      out.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
    }
    return out.toString();
  }

  /** A query string of {@code key=value} pairs, each encoded; {@code null} values left out. */
  static String query(String... pairs) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      if (pairs[i + 1] == null) {
        continue;
      }
      out.append(out.isEmpty() ? '?' : '&').append(pairs[i]).append('=')
          .append(URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
    }
    return out.toString();
  }

  JsonNode get(Call call, String pathAndQuery, String what) throws ConnectorException {
    return send(call, HttpRequest.newBuilder(uri(pathAndQuery)).GET(), pathAndQuery, what, false);
  }

  /** @param change whether the request changes data: a connection lost after sending it leaves the outcome unknown */
  JsonNode post(Call call, String pathAndQuery, ObjectNode body, String what, boolean change)
      throws ConnectorException {
    return send(call, HttpRequest.newBuilder(uri(pathAndQuery)).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))), pathAndQuery, what, change);
  }

  private URI uri(String pathAndQuery) {
    return base().resolve(pathAndQuery);
  }

  private JsonNode send(Call call, HttpRequest.Builder request, String pathAndQuery, String what, boolean change)
      throws ConnectorException {
    URI uri = uri(pathAndQuery);
    if (!cloud.sendsGoogleTokensTo(uri)) {
      // A test endpoint gets tokens only from a test token endpoint (CloudCredentials.sendsGoogleTokensTo).
      throw ConnectorException.denied("GateRook sends Google tokens only to Google's APIs.");
    }
    GoogleCredentials credentials = cloud.gcp(new CloudCredentials.Owner(call.cfg().id(), call.cfg().version(),
        call.cfg().name()), call.settings().credentials(), List.of(SCOPE), call.secrets());
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
      throw ConnectorException.unavailable("Can't connect to BigQuery (timed out). Check that this computer can "
          + "reach Google Cloud.", e);
    } catch (HttpTimeoutException e) {
      if (change) {
        throw ConnectorException.outcomeUnknown("BigQuery didn't answer the change in time, so whether it ran is not "
            + "known. Check the table before retrying.", e);
      }
      throw ConnectorException.timeout(what + " didn't answer in time.");
    } catch (ConnectException | SSLException e) {
      throw ConnectorException.unavailable("Can't connect to BigQuery: " + reason(e) + ".", e);
    } catch (IOException e) {
      if (change) {
        throw ConnectorException.outcomeUnknown("The connection to BigQuery failed during the change, so whether it "
            + "ran is not known. Check the table before retrying.", e);
      }
      throw ConnectorException.unavailable(what + " failed: " + reason(e) + ".", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout(what + " was cancelled.");
    }
    JsonNode tree;
    try {
      tree = body.length == 0 ? JsonNodeFactory.instance.objectNode() : JSON.readTree(body);
    } catch (JacksonException e) {
      throw ConnectorException.resourceError(what + " answered " + status + " with something that isn't JSON.", null);
    }
    if (status / 100 != 2) {
      throw error(status, tree, what, call, change);
    }
    return tree;
  }

  /** Google's error, with what to do: the API to turn on, the role to grant, the sign-in to renew. */
  static ConnectorException error(int status, JsonNode tree, String what, Call call, boolean change) {
    JsonNode error = tree.path("error");
    String message = OutputSanitizer.sanitize(firstLine(error.path("message").asString("")));
    String reason = error.path("errors").path(0).path("reason").asString("");
    for (JsonNode d : error.path("details")) {
      if (d.path("@type").asString("").endsWith("google.rpc.ErrorInfo")) {
        reason = d.path("reason").asString(reason);
      }
    }
    String project = call.settings().project();
    if (status == 403 && (reason.equals("SERVICE_DISABLED") || reason.equals("accessNotConfigured"))) {
      return ConnectorException.resourceError("The BigQuery API is turned off for project " + project + ". Run: "
          + "gcloud services enable bigquery.googleapis.com --project=" + project, null);
    }
    if (status == 403 && reason.equals("bytesBilledLimitExceeded")) {
      return ConnectorException.resourceError("The query would process more than the connection's limit of "
          + gib(call.settings().maxBytesBilled()) + ": " + message + ". Select fewer columns or filter on the "
          + "partitioning column.", null);
    }
    return switch (status) {
      case 400 -> ConnectorException.badInput(what + " refused the statement: " + message);
      case 401 -> ConnectorException.unavailable(what + " refused the token: " + message + ". Sign in again (gcloud "
          + "auth application-default login) or check the key, then test again.", null);
      case 403 -> ConnectorException.resourceError(what + " denied " + call.settings().credentials().identity() + ": "
          + message + ". Grant roles/bigquery.dataViewer on the datasets and roles/bigquery.jobUser on " + project
          + ".", null);
      case 404 -> ConnectorException.badInput(what + ": not found. " + message);
      case 429 -> ConnectorException.resourceError(what + " is out of quota: try again in a minute.", null);
      default -> status >= 500
          ? (change ? ConnectorException.outcomeUnknown("BigQuery failed with " + status + " during the change, so "
              + "whether it ran is not known. Check the table before retrying.", null)
              : ConnectorException.unavailable(what + " failed (" + status + "): " + message, null))
          : ConnectorException.resourceError(what + " answered " + status + ": " + message, null);
    };
  }

  static String gib(long bytes) {
    return bytes >= 1L << 30 ? String.format(Locale.US, "%.1f GiB", bytes / (double) (1L << 30))
        : String.format(Locale.US, "%.1f MiB", bytes / (double) (1L << 20));
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
