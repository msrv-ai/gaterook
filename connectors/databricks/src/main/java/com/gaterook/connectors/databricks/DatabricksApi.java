package com.gaterook.connectors.databricks;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connectors.support.OutputSanitizer;
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
import java.time.Clock;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A Databricks workspace's REST APIs on {@code java.net.http}: the SQL Statement Execution API 2.0, Unity Catalog's
 * API 2.1, the SQL warehouses API and the workspace's OAuth token endpoint. Every URL is the connection's workspace
 * ({@link DatabricksSettings#base}, https only) and a fixed path built from checked parts, each encoded. TLS is
 * verified, redirects are never followed, nothing is retried, every address the host resolves to is judged first
 * ({@link WorkspaceAddresses}); each request runs against the call's deadline and an answer is read up to
 * {@link #MAX_BODY}.
 */
final class DatabricksApi {

  /** An INLINE result chunk and its JSON: the statements ask for at most {@link DatabricksConnector#BYTE_LIMIT}. */
  static final long MAX_BODY = 16L * 1024 * 1024;
  static final long MAX_SIGN_IN_BODY = 64L * 1024;
  static final Duration MARGIN = Duration.ofMillis(150);
  /** A statement waits up to 50 s on the server; the request a little longer. */
  static final Duration MAX_REQUEST = Duration.ofSeconds(55);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpClient http;
  final DatabricksAuth auth;

  DatabricksApi(SSLContext ssl, Clock clock) {
    this.http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .sslContext(ssl)
        .followRedirects(HttpClient.Redirect.NEVER)
        .proxy(HttpClient.Builder.NO_PROXY)
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    this.auth = new DatabricksAuth(this, clock);
  }

  /** One call's way to the workspace. */
  record Call(ConnectionConfig cfg, DatabricksSettings settings, SecretResolver secrets, ExecContext ctx) {}

  /** {@code api/2.0/sql/statements/<id>}: each segment percent-encoded, no dot segments. */
  static String path(String... segments) {
    StringBuilder out = new StringBuilder();
    for (String segment : segments) {
      if (segment == null || segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        throw new IllegalArgumentException("Not a Databricks path segment");
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
    return send(call, pathAndQuery, null, what, false);
  }

  /**
   * @param change whether the request starts a change: a connection lost after sending it leaves the outcome
   *               unknown
   */
  JsonNode post(Call call, String path, ObjectNode body, String what, boolean change) throws ConnectorException {
    return send(call, path, JSON.writeValueAsString(body), what, change);
  }

  private JsonNode send(Call call, String pathAndQuery, String body, String what, boolean change)
      throws ConnectorException {
    URI uri = call.settings().base().resolve(pathAndQuery);
    WorkspaceAddresses.of(call.ctx()).check(uri);
    String token = auth.token(call);
    HttpRequest.Builder request = HttpRequest.newBuilder(uri)
        .header("Authorization", "Bearer " + token)
        .header("Accept", "application/json");
    if (body == null) {
      request.GET();
    } else {
      request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
    }
    Answer a = exchange(call, request, what, change, MAX_BODY);
    JsonNode tree = parse(a, what);
    if (a.status() / 100 != 2) {
      if (a.status() == 401) {
        auth.refused(call);
      }
      throw error(a.status(), tree, what, call, change);
    }
    return tree;
  }

  /** The workspace's OAuth token endpoint: a form with the client's credentials in {@code Authorization}. */
  JsonNode signIn(Call call, URI uri, String authorization, String form) throws ConnectorException {
    WorkspaceAddresses.of(call.ctx()).check(uri);
    HttpRequest.Builder request = HttpRequest.newBuilder(uri)
        .header("Authorization", authorization)
        .header("Accept", "application/json")
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form));
    Answer a = exchange(call, request, "The service principal's sign-in", false, MAX_SIGN_IN_BODY);
    JsonNode tree = parse(a, "The service principal's sign-in");
    if (a.status() / 100 != 2) {
      String error = OutputSanitizer.sanitize(firstLine(tree.path("error").asString("")));
      String said = OutputSanitizer.sanitize(firstLine(tree.path("error_description").asString(error)));
      if (a.status() == 400 || a.status() == 401) {
        throw ConnectorException.unavailable("Databricks refused the service principal's client ID or secret"
            + (said.isBlank() ? "" : " (" + said + ")") + ". Check them under Credentials, then test again.", null);
      }
      throw ConnectorException.unavailable("The service principal's sign-in failed with " + a.status()
          + (said.isBlank() ? "" : ": " + said) + ".", null);
    }
    return tree;
  }

  private record Answer(int status, byte[] body, String location) {}

  private Answer exchange(Call call, HttpRequest.Builder request, String what, boolean change, long max)
      throws ConnectorException {
    Duration left = call.ctx().remaining().minus(MARGIN);
    if (left.isNegative() || left.isZero()) {
      throw ConnectorException.timeout(what + " didn't answer in time.");
    }
    HttpRequest built = request.timeout(left.compareTo(MAX_REQUEST) > 0 ? MAX_REQUEST : left).build();
    try {
      HttpResponse<InputStream> r = http.send(built, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream in = r.body()) {
        return new Answer(r.statusCode(), read(in, what, max), r.headers().firstValue("Location").orElse(null));
      }
    } catch (HttpConnectTimeoutException e) {
      throw ConnectorException.unavailable("Can't connect to the workspace " + call.settings().host() + " (timed "
          + "out). Check the workspace URL and that this computer can reach it.", e);
    } catch (HttpTimeoutException e) {
      if (change) {
        throw ConnectorException.outcomeUnknown("Databricks didn't answer the change in time, so whether it ran is not "
            + "known. Check the table and the warehouse's query history before retrying.", e);
      }
      throw ConnectorException.timeout(what + " didn't answer in time.");
    } catch (ConnectException | SSLException e) {
      throw ConnectorException.unavailable("Can't connect to the workspace " + call.settings().host() + ": "
          + reason(e) + ".", e);
    } catch (IOException e) {
      if (change) {
        throw ConnectorException.outcomeUnknown("The connection to Databricks failed during the change, so whether it "
            + "ran is not known. Check the table and the warehouse's query history before retrying.", e);
      }
      throw ConnectorException.unavailable(what + " failed: " + reason(e) + ".", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout(what + " was cancelled.");
    }
  }

  private static JsonNode parse(Answer a, String what) throws ConnectorException {
    if (a.status() / 100 == 3) {
      String to = a.location() == null ? "elsewhere" : host(a.location());
      throw ConnectorException.resourceError(what + " was redirected to " + to + ", which GateRook doesn't follow. "
          + "Check the workspace URL.", null);
    }
    try {
      return a.body().length == 0 ? JsonNodeFactory.instance.objectNode() : JSON.readTree(a.body());
    } catch (JacksonException e) {
      if (a.status() / 100 == 2) {
        throw ConnectorException.resourceError(what + " answered with something that isn't JSON. Check the "
            + "workspace URL.", null);
      }
      return JsonNodeFactory.instance.objectNode();
    }
  }

  private static String host(String url) {
    try {
      String h = URI.create(url).getHost();
      return h == null ? "another page" : OutputSanitizer.sanitize(h);
    } catch (IllegalArgumentException e) {
      return "another page";
    }
  }

  /** Databricks' {@code error_code} and message, with what to do. */
  static ConnectorException error(int status, JsonNode tree, String what, Call call, boolean change) {
    String code = tree.path("error_code").asString("");
    String message = OutputSanitizer.sanitize(firstLine(tree.path("message").asString("")));
    String said = message.isBlank() ? (code.isBlank() ? "status " + status : code) : message;
    return switch (status) {
      case 400 -> ConnectorException.badInput(what + " refused the request: " + said + ".");
      case 401 -> ConnectorException.unavailable(what + " refused the sign-in of " + call.cfg().name() + ": " + said
          + ". Create a new personal access token, or check the service principal, then test again.", null);
      case 403 -> ConnectorException.resourceError(what + ": the identity of " + call.cfg().name() + " may not do "
          + "this: " + said + ". It needs CAN USE on the warehouse and USE CATALOG, USE SCHEMA and SELECT on what "
          + "it reads.", null);
      case 404 -> ConnectorException.badInput(what + ": not found. " + said + ".");
      case 429 -> ConnectorException.resourceError("Databricks is limiting requests now: " + said + ". Try again in "
          + "a moment.", null);
      default -> status >= 500
          ? (change ? ConnectorException.outcomeUnknown("Databricks failed with " + status + " during the change, so "
              + "whether it ran is not known. Check the table and the warehouse's query history before retrying.", null)
              : ConnectorException.unavailable(what + " failed (" + status + "): " + said + ".", null))
          : ConnectorException.resourceError(what + " answered " + status + ": " + said + ".", null);
    };
  }

  private static byte[] read(InputStream in, String what, long max) throws IOException, ConnectorException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
    byte[] buf = new byte[16384];
    long total = 0;
    int n;
    while ((n = in.read(buf)) > 0) {
      total += n;
      if (total > max) {
        throw ConnectorException.resourceError(what + " answered with more than " + (max >> 10) + " KiB.", null);
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
