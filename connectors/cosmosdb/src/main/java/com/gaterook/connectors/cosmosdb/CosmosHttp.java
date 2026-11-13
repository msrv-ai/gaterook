package com.gaterook.connectors.cosmosdb;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connectors.support.BuiltIn;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cosmos DB's REST API on {@code java.net.http}: HTTP/1.1, no proxy, redirects never followed (a key's signature or a
 * token must not travel), nothing retried, TLS verified against the platform's CAs. Every request goes to the
 * connection's endpoint ({@link CosmosEndpoint}) at a path the connector built from IDs it checked and encoded itself,
 * with {@code x-ms-version} {@value #API_VERSION}, its own {@code x-ms-date} and authorization, within the call's
 * deadline, and its answer is read up to {@link #MAX_BODY}.
 */
final class CosmosHttp implements AutoCloseable {

  /** The REST API version every request names: the one Cosmos DB's current SDKs send, with partial updates. */
  static final String API_VERSION = "2020-07-15";
  /** Time kept back from the deadline for building the answer. */
  static final Duration MARGIN = Duration.ofMillis(150);
  static final Duration MAX_REQUEST = Duration.ofSeconds(30);
  /** Cosmos DB's pages are at most 4 MB; a little more is read before an answer is refused. */
  static final long MAX_BODY = 16L * 1024 * 1024;
  static final long MAX_ERROR = 64L * 1024;
  static final String USER_AGENT = "GateRook/" + BuiltIn.VERSION;
  static final JsonMapper JSON = JsonMapper.builder().build();
  /** A change's body in the approval and on the wire: the same writer, the same bytes. */
  static final ObjectWriter PRETTY = JSON.writerWithDefaultPrettyPrinter();

  /** Made on the first request: a client starts its selector thread, which a connector nobody uses doesn't need. */
  private volatile HttpClient client;
  /** Tests only: where the fake gateway listens instead of the endpoint. Never a setting. */
  private final URI testBase;
  private final CosmosAuth auth;

  CosmosHttp(URI testBase, URI testLogin) {
    this.testBase = testBase;
    this.auth = new CosmosAuth(this::client, testLogin);
  }

  HttpClient client() {
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

  CosmosAuth auth() {
    return auth;
  }

  /** One call's way to Cosmos DB: the connection, its settings and secrets, the call's context. */
  record Call(ConnectionConfig cfg, CosmosSettings settings, SecretResolver secrets, ExecContext ctx) {}

  /**
   * One request.
   *
   * @param verb         HTTP method
   * @param path         the encoded path, {@code /dbs/app/colls/orders/docs}
   * @param resourceType for the signature: {@code dbs}, {@code colls}, {@code docs}, {@code offers} or empty
   * @param resourceLink for the signature: the IDs as they are
   * @param headers      Cosmos DB's headers of this request
   * @param body         the bytes sent, or {@code null}
   * @param contentType  the body's type, or {@code null}
   * @param change       whether the request changes data: an answer lost after sending it leaves the outcome unknown
   * @param what         what the request is, for messages: {@code the query}, {@code the item o-1}
   */
  record Request(String verb, String path, String resourceType, String resourceLink, Map<String, String> headers,
      byte[] body, String contentType, boolean change, String what) {

    static Request get(String path, String type, String link, String what) {
      return new Request("GET", path, type, link, new LinkedHashMap<>(), null, null, false, what);
    }

    Request with(String header, String value) {
      Map<String, String> h = new LinkedHashMap<>(headers);
      h.put(header, value);
      return new Request(verb, path, resourceType, resourceLink, h, body, contentType, change, what);
    }
  }

  /** What Cosmos DB answered: its status, its JSON ({@code {}} when it sent none) and its headers. */
  record Answer(int status, JsonNode body, Map<String, List<String>> headers) {

    String header(String name) {
      for (Map.Entry<String, List<String>> h : headers.entrySet()) {
        if (h.getKey().equalsIgnoreCase(name) && !h.getValue().isEmpty()) {
          return h.getValue().getFirst();
        }
      }
      return null;
    }

    /** The request units the request used, {@code x-ms-request-charge}; 0 when Cosmos DB doesn't say. */
    double charge() {
      String c = header("x-ms-request-charge");
      try {
        return c == null ? 0 : Math.max(0, Double.parseDouble(c));
      } catch (NumberFormatException e) {
        return 0;
      }
    }

    /** The continuation token of a page, or {@code null} when it was the last. */
    String continuation() {
      String c = header("x-ms-continuation");
      return c == null || c.isBlank() ? null : c;
    }

    int substatus() {
      String s = header("x-ms-substatus");
      try {
        return s == null ? 0 : Integer.parseInt(s.strip());
      } catch (NumberFormatException e) {
        return 0;
      }
    }
  }

  /**
   * Sends {@code r} and returns Cosmos DB's answer when it is 2xx, or one of {@code accepted}; any other answer becomes
   * the {@link ConnectorException} that says what to do ({@link CosmosErrors}).
   */
  Answer send(Call c, Request r, int... accepted) throws ConnectorException {
    ExecContext ctx = c.ctx();
    URI uri = URI.create((testBase != null ? testBase : c.settings().endpoint().base()).toString() + r.path());
    requirePlainPath(uri);
    Duration left = ctx.remaining().minus(MARGIN);
    if (left.isNegative() || left.isZero()) {
      throw r.change() ? ConnectorException.timeout("No time was left to send the change, so nothing changed.")
          : ConnectorException.timeout("No time was left to ask Cosmos DB for " + r.what() + ".");
    }
    CosmosAuth.Signed signed = auth.authorize(c.cfg(), c.settings(), c.secrets(), ctx, r.verb(), r.resourceType(),
        r.resourceLink());
    HttpRequest.Builder b = HttpRequest.newBuilder(uri)
        .timeout(left.compareTo(MAX_REQUEST) > 0 ? MAX_REQUEST : left)
        .header("Accept", "application/json")
        .header("User-Agent", USER_AGENT)
        .header("x-ms-version", API_VERSION)
        .header("x-ms-date", signed.date())
        .header("authorization", signed.authorization());
    for (Map.Entry<String, String> h : r.headers().entrySet()) {
      b.header(h.getKey(), h.getValue());
    }
    if (r.body() != null) {
      b.header("Content-Type", r.contentType() == null ? "application/json" : r.contentType());
      b.method(r.verb(), HttpRequest.BodyPublishers.ofByteArray(r.body()));
    } else {
      b.method(r.verb(), HttpRequest.BodyPublishers.noBody());
    }
    String host = c.settings().label();
    HttpResponse<InputStream> response;
    try {
      response = client().send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
    } catch (HttpConnectTimeoutException e) {
      throw ConnectorException.unavailable("Can't connect to " + host + " (timed out)" + (r.change()
          ? ", so nothing changed" : "") + ". Check that this computer can reach the account.", e);
    } catch (ConnectException | SSLHandshakeException e) {
      throw ConnectorException.unavailable("Can't connect to " + host + ": " + reason(e) + (r.change()
          ? ". Nothing changed" : "") + ".", e);
    } catch (HttpTimeoutException e) {
      if (r.change()) {
        throw ConnectorException.outcomeUnknown("Cosmos DB didn't answer the change in time, so whether it ran is not "
            + "known. Read the item before trying again.", e);
      }
      throw ConnectorException.timeout("Cosmos DB didn't answer " + r.what() + " in time.");
    } catch (IOException e) {
      if (r.change()) {
        throw ConnectorException.outcomeUnknown("The connection to " + host + " failed during the change, so whether "
            + "it ran is not known. Read the item before trying again.", e);
      }
      throw ConnectorException.unavailable("Cosmos DB failed to answer " + r.what() + ": " + reason(e) + ".", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (r.change()) {
        throw ConnectorException.outcomeUnknown("The change was cancelled while Cosmos DB ran it, so whether it ran "
            + "is not known. Read the item before trying again.", e);
      }
      throw ConnectorException.timeout("Asking Cosmos DB for " + r.what() + " was cancelled.");
    }
    int status = response.statusCode();
    boolean ok = status / 100 == 2 || contains(accepted, status);
    byte[] bytes;
    InputStream in = response.body();
    ctx.onCancel(() -> closeQuietly(in));
    try (in) {
      bytes = readAtMost(in, ok ? MAX_BODY : MAX_ERROR);
    } catch (TooLarge e) {
      if (!ok) {
        bytes = new byte[0];
      } else if (r.change()) {
        // The change ran: only its answer is too large to read.
        return new Answer(status, JSON.createObjectNode(), response.headers().map());
      } else {
        throw ConnectorException.resourceError("Cosmos DB's answer to " + r.what() + " is larger than "
            + size(MAX_BODY) + ": ask for fewer items, or project fewer properties.", null);
      }
    } catch (IOException e) {
      if (r.change() && status / 100 == 2) {
        return new Answer(status, JSON.createObjectNode(), response.headers().map());
      }
      if (r.change()) {
        bytes = new byte[0];
      } else {
        throw ConnectorException.unavailable("Cosmos DB broke off its answer to " + r.what() + ": " + reason(e) + ".",
            e);
      }
    }
    JsonNode body;
    try {
      body = bytes.length == 0 ? JSON.createObjectNode() : JSON.readTree(bytes);
    } catch (JacksonException e) {
      if (ok && !r.change()) {
        throw ConnectorException.resourceError("Cosmos DB answered " + status + " to " + r.what() + " with something "
            + "that isn't JSON.", null);
      }
      body = JSON.createObjectNode();
    }
    Answer answer = new Answer(status, body == null ? JSON.createObjectNode() : body, response.headers().map());
    if (!ok) {
      throw CosmosErrors.of(answer, r, c);
    }
    return answer;
  }

  private static boolean contains(int[] values, int v) {
    for (int x : values) {
      if (x == v) {
        return true;
      }
    }
    return false;
  }

  /** Thrown by {@link #readAtMost} past its cap. */
  static final class TooLarge extends IOException {
    private static final long serialVersionUID = 1L;

    TooLarge() {
      super(null, null);
    }
  }

  /** The whole stream, at most {@code max} bytes. */
  static byte[] readAtMost(InputStream in, long max) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
    byte[] buf = new byte[16384];
    long total = 0;
    int n;
    while ((n = in.read(buf)) > 0) {
      total += n;
      if (total > max) {
        throw new TooLarge();
      }
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  /**
   * A path of the connector's own making has no dot segment and no empty one, raw or percent-encoded: Java's HTTP
   * client sends {@code ..} as it is, and a server that resolved it would answer a path nobody checked (defence in
   * depth: every ID was checked and encoded before).
   */
  static void requirePlainPath(URI uri) {
    String path = uri.getPath() == null ? "" : uri.getPath();
    String raw = uri.getRawPath() == null ? "" : uri.getRawPath().toLowerCase(Locale.ROOT);
    for (String segment : raw.split("/", -1)) {
      if (segment.equals(".") || segment.equals("..") || segment.equals("%2e") || segment.equals("%2e%2e")
          || segment.equals(".%2e") || segment.equals("%2e.")) {
        throw new IllegalArgumentException("A dot segment in a Cosmos DB path");
      }
    }
    if (raw.substring(Math.min(1, raw.length())).contains("//") || path.contains("\\")) {
      throw new IllegalArgumentException("An empty segment in a Cosmos DB path");
    }
  }

  private static void closeQuietly(InputStream in) {
    try {
      in.close();
    } catch (IOException e) {
      // The answer is abandoned.
    }
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

  @Override
  public void close() {
    HttpClient c = client;
    if (c != null) {
      c.shutdownNow();
    }
  }
}
