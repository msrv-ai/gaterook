package ai.msrv.gaterook.connectors.trino;

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
import java.net.URISyntaxException;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Trino's client protocol on {@code java.net.http} (docs/trino.md): {@code POST /v1/statement} with the statement as
 * its body, then each {@code nextUri} until the query is done, {@code DELETE} on the current one to cancel it. Every
 * request goes to the connection's coordinator only: a {@code nextUri} on another scheme, host or port, or outside
 * {@code /v1/statement/}, ends the call before anything is sent there. Redirects are never followed; each request
 * runs against the call's deadline, a connect timeout and a body cap; the session's own time limit
 * ({@code query_max_run_time}) is the call's. A statement is sent once: only the polls of a running query are
 * repeated when the coordinator says it is busy, as the protocol asks.
 */
final class TrinoHttp implements AutoCloseable {

  static final long MAX_BODY = 16L * 1024 * 1024;
  static final Duration MAX_REQUEST = Duration.ofSeconds(30);
  /** Time kept back from the deadline to cancel the query and answer. */
  static final Duration CANCEL_RESERVE = Duration.ofMillis(1_000);
  static final String SOURCE = "gaterook";
  private static final int BUSY_POLLS = 5;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Why a statement is sent: a read stops at its limits; a change's lost answer leaves its outcome unknown. */
  enum Purpose { READ, CHANGE }

  /** One call's way to the coordinator. */
  record Call(ConnectionConfig cfg, TrinoSettings settings, SecretResolver secrets, ExecContext ctx) {}

  /** Takes the rows of an answer one by one; {@code false} when it wants no more. */
  interface Rows {
    boolean take(JsonNode columns, JsonNode row);
  }

  /**
   * What the coordinator answered for one statement.
   *
   * @param cut         {@code null} when the query finished; {@code rows} when the taker wanted no more and the query
   *                    was cancelled; {@code time} when the call's time ran out after some rows
   * @param updateType  {@code INSERT}, {@code CREATE TABLE}, … for a change
   * @param updateCount rows a change wrote, when Trino counts them
   */
  record Result(String queryId, JsonNode columns, String cut, String updateType, Long updateCount, JsonNode stats) {}

  private record Client(long version, String ca, HttpClient http) {}

  private final Map<String, Client> clients = new ConcurrentHashMap<>();

  /** The client of a connection: HTTP/1.1, no proxy, no redirects, its private CA when it names one. */
  private HttpClient client(Call c) throws ConnectorException {
    String ca = c.settings().caCertificate() == null ? "" : c.settings().caCertificate();
    Client current = clients.get(c.cfg().id());
    if (current != null && current.version() == c.cfg().version() && current.ca().equals(ca)) {
      return current.http();
    }
    HttpClient.Builder b = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER)
        .proxy(HttpClient.Builder.NO_PROXY)
        .connectTimeout(Duration.ofSeconds(5));
    if (!ca.isEmpty()) {
      b.sslContext(sslContext(ca));
    }
    HttpClient http = b.build();
    Client replaced = clients.put(c.cfg().id(), new Client(c.cfg().version(), ca, http));
    if (replaced != null && replaced.http() != http) {
      replaced.http().shutdown();
    }
    return http;
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

  /** Whether a client of {@code connectionId} is kept: the setup helpers drop only the ones they made themselves. */
  boolean has(String connectionId) {
    return clients.containsKey(connectionId);
  }

  void evict(String connectionId) {
    Client c = clients.remove(connectionId);
    if (c != null) {
      c.http().shutdown();
    }
  }

  @Override
  public void close() {
    clients.values().forEach(c -> c.http().shutdownNow());
    clients.clear();
  }

  // ── Statements ──────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Runs {@code sql} and hands its rows to {@code rows} until the query finishes, {@code rows} wants no more, or the
   * call's time is up; a query that doesn't finish is cancelled.
   *
   * @throws ConnectorException Trino's refusal or failure of the statement, the deadline (a read without rows, or a
   *                            change: its outcome is unknown), the connection's errors
   */
  Result run(Call c, String sql, Purpose purpose, Rows rows) throws ConnectorException {
    ExecContext ctx = c.ctx();
    HttpClient http = client(c);
    Headers headers = headers(c, purpose);
    AtomicReference<URI> current = new AtomicReference<>();
    ctx.onCancel(() -> {
      URI u = current.getAndSet(null);
      if (u != null) {
        try {
          http.sendAsync(headers.apply(HttpRequest.newBuilder(u)).DELETE().timeout(Duration.ofSeconds(2)).build(),
              HttpResponse.BodyHandlers.discarding());
        } catch (RuntimeException e) {
          // The query ends at its own time limit.
        }
      }
    });
    URI statement = c.settings().base().resolve("/v1/statement");
    Answer a = send(c, http, headers.apply(HttpRequest.newBuilder(statement))
        .header("Content-Type", "text/plain; charset=utf-8")
        .POST(HttpRequest.BodyPublishers.ofString(sql, StandardCharsets.UTF_8)), purpose, true, null);
    JsonNode columns = null;
    String queryId = null;
    String updateType = null;
    Long updateCount = null;
    boolean anyRow = false;
    while (true) {
      JsonNode r = a.body();
      queryId = r.path("id").asString(queryId);
      if (r.path("error").isObject()) {
        throw failure(c, r.path("error"), purpose, queryId);
      }
      if (r.path("columns").isArray()) {
        columns = r.get("columns");
      }
      if (r.path("updateType").isString()) {
        updateType = r.get("updateType").asString();
      }
      if (r.path("updateCount").canConvertToLong()) {
        updateCount = r.get("updateCount").asLong();
      }
      URI next = next(c, r, purpose, queryId);
      current.set(next);
      for (JsonNode row : r.path("data")) {
        anyRow = true;
        if (!rows.take(columns, row)) {
          cancel(c, http, headers, next);
          current.set(null);
          return new Result(queryId, columns, "rows", updateType, updateCount, r.path("stats"));
        }
      }
      if (next == null) {
        current.set(null);
        return new Result(queryId, columns, null, updateType, updateCount, r.path("stats"));
      }
      if (ctx.remaining().compareTo(CANCEL_RESERVE) <= 0) {
        cancel(c, http, headers, next);
        current.set(null);
        if (purpose == Purpose.READ && anyRow) {
          return new Result(queryId, columns, "time", updateType, updateCount, r.path("stats"));
        }
        throw deadline(c, purpose, queryId);
      }
      try {
        a = poll(c, http, headers, next, purpose, queryId);
      } catch (ConnectorException e) {
        if (e.kind() == ConnectorException.Kind.TIMEOUT || e.kind() == ConnectorException.Kind.OUTCOME_UNKNOWN) {
          cancel(c, http, headers, next);
          current.set(null);
          if (purpose == Purpose.READ && anyRow && e.kind() == ConnectorException.Kind.TIMEOUT) {
            return new Result(queryId, columns, "time", updateType, updateCount, r.path("stats"));
          }
        }
        throw e;
      }
    }
  }

  /** {@code GET nextUri}; a busy coordinator (502, 503, 504) is asked again after a pause, as the protocol says. */
  private Answer poll(Call c, HttpClient http, Headers headers, URI next, Purpose purpose, String queryId)
      throws ConnectorException {
    long pause = 50;
    for (int attempt = 0; ; attempt++) {
      Answer a = send(c, http, headers.apply(HttpRequest.newBuilder(next)).GET(), purpose, false, queryId);
      if (!a.busy()) {
        return a;
      }
      if (attempt >= BUSY_POLLS || c.ctx().remaining().minus(CANCEL_RESERVE).toMillis() <= pause) {
        throw purpose == Purpose.CHANGE
            ? ConnectorException.outcomeUnknown(title(c) + " stayed busy while running the change (query " + queryId
                + "), so whether it ran is not known. Check the table before retrying.", null)
            : ConnectorException.unavailable(title(c) + " is busy (HTTP " + a.status() + "). Try again in a moment.",
                null);
      }
      try {
        Thread.sleep(pause);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw ConnectorException.timeout("The call was cancelled.");
      }
      pause = Math.min(pause * 2, 800);
    }
  }

  /** Asks the coordinator to stop the query at {@code next}; what it answers doesn't change the call. */
  private void cancel(Call c, HttpClient http, Headers headers, URI next) {
    if (next == null) {
      return;
    }
    try {
      TrinoAddresses.of(c.ctx()).check(next);
      long left = c.ctx().remaining().toMillis();
      Duration timeout = Duration.ofMillis(Math.max(500, Math.min(2_000, left)));
      http.send(headers.apply(HttpRequest.newBuilder(next)).DELETE().timeout(timeout).build(),
          HttpResponse.BodyHandlers.discarding());
    } catch (ConnectorException | IOException e) {
      // The query stops at its session's time limit.
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * {@code GET /v1/info}: the coordinator's version, environment and whether it is still starting. Public in Trino's
   * protocol, so it carries no credentials.
   */
  JsonNode info(Call c) throws ConnectorException {
    HttpClient http = client(c);
    Answer a = send(c, http, HttpRequest.newBuilder(c.settings().base().resolve("/v1/info"))
        .header("Accept", "application/json").header("User-Agent", "GateRook").GET(), Purpose.READ, true, null);
    if (a.busy()) {
      throw ConnectorException.unavailable(title(c) + " is busy (HTTP " + a.status() + "). Try again in a moment.",
          null);
    }
    return a.body();
  }

  // ── Requests ────────────────────────────────────────────────────────────────────────────────────────────────

  /** The request headers of one call, credentials included; every credential joins the call's taint. */
  @FunctionalInterface
  interface Headers {
    HttpRequest.Builder apply(HttpRequest.Builder b);
  }

  private static Headers headers(Call c, Purpose purpose) throws ConnectorException {
    TrinoSettings s = c.settings();
    String p = s.engine().prefix;
    String authorization = authorization(c);
    long ms = Math.max(1_000, c.ctx().remaining().minus(CANCEL_RESERVE).toMillis());
    StringBuilder session = new StringBuilder("query_max_run_time=").append(ms).append("ms,query_max_execution_time=")
        .append(ms).append("ms");
    if (s.maxScanBytes() != null && s.engine() == TrinoSettings.Engine.TRINO) {
      session.append(",query_max_scan_physical_bytes=").append(s.maxScanBytes()).append('B');
    }
    String info = "GateRook " + c.ctx().callId().replaceAll("[^A-Za-z0-9_-]", "");
    String trace = c.ctx().traceId() == null ? null : c.ctx().traceId().replaceAll("[^A-Za-z0-9_-]", "");
    return b -> {
      b.header("Accept", "application/json").header("User-Agent", "GateRook")
          .header(p + "Source", SOURCE)
          .header(p + "Session", session.toString())
          .header(p + "Client-Tags", SOURCE + "," + (purpose == Purpose.CHANGE ? "change" : "read"))
          .header(p + "Client-Info", info);
      if (s.user() != null) {
        b.header(p + "User", s.user());
      }
      if (s.catalog() != null) {
        b.header(p + "Catalog", s.catalog());
      }
      if (s.schema() != null) {
        b.header(p + "Schema", s.schema());
      }
      if (s.engine() == TrinoSettings.Engine.TRINO) {
        // Timestamps at their own precision, not cut to milliseconds as for clients that don't say so.
        b.header(p + "Client-Capabilities", "PARAMETRIC_DATETIME");
      }
      if (trace != null && !trace.isEmpty()) {
        b.header(p + "Trace-Token", trace);
      }
      if (authorization != null) {
        b.header("Authorization", authorization);
      }
      return b;
    };
  }

  /** {@code Basic …} or {@code Bearer …} of the connection's secret, or {@code null} without authentication. */
  private static String authorization(Call c) throws ConnectorException {
    TrinoSettings s = c.settings();
    String field = s.secretField();
    if (field == null) {
      return null;
    }
    if (!c.secrets().isSet(field)) {
      throw ConnectorException.unavailable("The connection has no stored " + (s.auth() == TrinoSettings.Auth.JWT
          ? "token" : "password") + ": set it under Authentication.", null);
    }
    try (SecretResolver.Secret secret = c.secrets().secret(field)) {
      String value = secret.unsafeString();
      c.ctx().taint(value);
      String header = s.auth() == TrinoSettings.Auth.JWT ? "Bearer " + value
          : "Basic " + Base64.getEncoder().encodeToString((s.user() + ":" + value).getBytes(StandardCharsets.UTF_8));
      c.ctx().taint(header.substring(header.indexOf(' ') + 1));
      return header;
    }
  }

  /** One answer: its status, its JSON, and whether the coordinator only said it was busy. */
  private record Answer(int status, JsonNode body, boolean busy) {}

  /**
   * Sends one request with the time the call has left.
   *
   * @param first whether this sends the statement itself: a change lost after it was sent has an unknown outcome
   */
  private Answer send(Call c, HttpClient http, HttpRequest.Builder request, Purpose purpose, boolean first,
      String queryId) throws ConnectorException {
    ExecContext ctx = c.ctx();
    Duration left = ctx.remaining().minus(first ? Duration.ofMillis(150) : CANCEL_RESERVE);
    if (left.isNegative() || left.isZero()) {
      throw first ? ConnectorException.timeout(title(c) + " didn't answer in time.") : deadline(c, purpose, queryId);
    }
    HttpRequest built = request.timeout(left.compareTo(MAX_REQUEST) > 0 ? MAX_REQUEST : left).build();
    TrinoAddresses.of(ctx).check(built.uri());
    boolean unknown = purpose == Purpose.CHANGE;
    int status;
    byte[] body;
    try {
      HttpResponse<InputStream> r = http.send(built, HttpResponse.BodyHandlers.ofInputStream());
      status = r.statusCode();
      try (InputStream in = r.body()) {
        body = read(in);
      }
    } catch (HttpConnectTimeoutException e) {
      // Nothing was sent: the connection never opened.
      if (!first && unknown) {
        throw lost(c, queryId, e);
      }
      throw ConnectorException.unavailable("Can't connect to " + c.settings().address() + " (timed out). Check the "
          + "URL and that this computer can reach it.", e);
    } catch (HttpTimeoutException e) {
      if (unknown) {
        throw lost(c, queryId, e);
      }
      throw first ? ConnectorException.timeout(title(c) + " didn't answer in time.") : deadline(c, purpose, queryId);
    } catch (ConnectException e) {
      if (!first && unknown) {
        throw lost(c, queryId, e);
      }
      throw ConnectorException.unavailable("Can't connect to " + c.settings().address() + ": " + reason(e)
          + ". Check the URL and that " + title(c) + " is running.", e);
    } catch (SSLHandshakeException e) {
      // The handshake comes before the request: a statement was not sent.
      if (!first && unknown) {
        throw lost(c, queryId, e);
      }
      throw ConnectorException.unavailable("The TLS handshake with " + c.settings().address() + " failed: "
          + reason(e) + ". If it uses a private CA, paste the CA certificate in the connection.", e);
    } catch (TooLarge e) {
      if (unknown) {
        throw lost(c, queryId, e);
      }
      throw ConnectorException.resourceError(title(c) + " answered with more than " + (MAX_BODY >> 20) + " MiB. "
          + "Narrow the query.", null);
    } catch (IOException e) {
      if (unknown) {
        throw lost(c, queryId, e);
      }
      throw ConnectorException.unavailable(title(c) + " failed: " + reason(e) + ".", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (unknown) {
        throw ConnectorException.outcomeUnknown("The call was cancelled while " + title(c) + " ran the change, so "
            + "whether it ran is not known. Check the table before retrying.", e);
      }
      throw ConnectorException.timeout("The call was cancelled.");
    }
    if (!first && (status == 502 || status == 503 || status == 504)) {
      return new Answer(status, null, true);
    }
    if (status != 200) {
      throw status(c, status, body, purpose, first, queryId);
    }
    try {
      JsonNode tree = JSON.readTree(body);
      if (tree == null || !tree.isObject()) {
        throw new IllegalStateException();
      }
      return new Answer(status, tree, false);
    } catch (JacksonException | IllegalStateException e) {
      if (unknown) {
        throw ConnectorException.outcomeUnknown(title(c) + " answered the change with something that isn't its "
            + "protocol, so whether it ran is not known. Check the table before retrying.", null);
      }
      throw ConnectorException.resourceError(title(c) + " answered with something that isn't its client "
          + "protocol. Check the URL and the engine (Trino or Presto).", null);
    }
  }

  /** An HTTP status other than 200, in words the person and the agent can act on. */
  private static ConnectorException status(Call c, int status, byte[] body, Purpose purpose, boolean first,
      String queryId) {
    String said = excerpt(body);
    boolean unknown = purpose == Purpose.CHANGE && (!first || status >= 500);
    if (unknown) {
      return ConnectorException.outcomeUnknown(title(c) + " answered " + status + " while running the change"
          + (queryId == null ? "" : " (query " + queryId + ")") + ", so whether it ran is not known. Check the table "
          + "before retrying.", null);
    }
    return switch (status) {
      case 401 -> ConnectorException.unavailable(title(c) + " refused the credentials of " + c.cfg().name()
          + " (401). Check the user and the " + (c.settings().auth() == TrinoSettings.Auth.JWT ? "token"
          : "password") + ", then test again.", null);
      case 403 -> ConnectorException.resourceError(title(c) + " refused the request (403)" + (said.isEmpty() ? ""
          : ": " + said) + ".", null);
      case 404 -> first ? ConnectorException.resourceError(c.settings().address() + " has no " + title(c) + " client "
          + "protocol at /v1/statement. Check the URL and the engine.", null)
          : ConnectorException.resourceError(title(c) + " no longer knows query " + queryId + ". Run it again.", null);
      default -> status >= 500
          ? ConnectorException.unavailable(title(c) + " failed with " + status + (said.isEmpty() ? "" : ": " + said)
              + ".", null)
          : ConnectorException.resourceError(title(c) + " answered " + status + (said.isEmpty() ? "" : ": " + said)
              + ".", null);
    };
  }

  /** The next address of a query: the coordinator's own and under {@code /v1/statement/}, or the call ends. */
  private static URI next(Call c, JsonNode r, Purpose purpose, String queryId) throws ConnectorException {
    String text = r.path("nextUri").asString(null);
    if (text == null) {
      return null;
    }
    URI base = c.settings().base();
    URI u;
    try {
      u = new URI(text);
    } catch (URISyntaxException e) {
      u = null;
    }
    if (u == null || u.getScheme() == null || u.getHost() == null || u.getRawUserInfo() != null
        || u.getRawFragment() != null || !u.getScheme().equalsIgnoreCase(base.getScheme())
        || !u.getHost().equalsIgnoreCase(base.getHost()) || port(u) != port(base) || u.getRawPath() == null
        || !u.getRawPath().startsWith("/v1/statement/") || u.getRawPath().contains("/../")
        || u.getRawPath().contains("/./")) {
      String where = u == null || u.getHost() == null ? "another address" : u.getScheme() + "://" + u.getHost()
          + (u.getPort() < 0 ? "" : ":" + u.getPort());
      String message = title(c) + " named " + OutputSanitizer.sanitize(where) + " for the rest of the query: GateRook "
          + "sends the connection's credentials to its own URL only. Behind a proxy, let " + title(c) + " see the "
          + "client's address (http-server.process-forwarded=true).";
      if (purpose == Purpose.CHANGE) {
        throw ConnectorException.outcomeUnknown(message + " Whether the change ran (query " + queryId + ") is not "
            + "known: check the table before retrying.", null);
      }
      throw ConnectorException.resourceError(message, null);
    }
    return u;
  }

  private static int port(URI u) {
    return u.getPort() >= 0 ? u.getPort() : "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
  }

  // ── Errors ──────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Trino's error of a query. A read's error says what to change. A change that Trino reports failed is no lost
   * answer: Trino's connectors write a statement's data when its query finishes, so it normally changed nothing, but
   * a connector without transactions may have written part of it, and the message says to check.
   */
  static ConnectorException failure(Call c, JsonNode error, Purpose purpose, String queryId) {
    String name = error.path("errorName").asString("");
    String type = error.path("errorType").asString("");
    String message = OutputSanitizer.sanitize(firstLine(error.path("message").asString(name)));
    if (message.length() > 1_000) {
      message = message.substring(0, 999) + "…";
    }
    String engine = title(c);
    if (purpose == Purpose.CHANGE) {
      if (type.equals("USER_ERROR") && !name.equals("EXCEEDED_TIME_LIMIT") && !name.equals("USER_CANCELED")) {
        return ConnectorException.badInput(engine + " refused the change: " + message + ". Nothing ran.");
      }
      return ConnectorException.resourceError(engine + " stopped the change (query " + queryId + "): " + message
          + ". Its connectors write a change when its query finishes, so this one normally changed nothing; check "
          + "the table before retrying.", null);
    }
    return switch (name) {
      case "EXCEEDED_TIME_LIMIT", "USER_CANCELED" -> ConnectorException.timeout(engine + " stopped the query at the "
          + "call's time limit (query " + queryId + "). Narrow it, or filter on a partition column.");
      case "PERMISSION_DENIED" -> ConnectorException.resourceError(engine + "'s access control denied "
          + (c.settings().user() == null ? "the token's user" : c.settings().user()) + ": " + message + ".", null);
      case "EXCEEDED_SCAN_LIMIT" -> ConnectorException.resourceError("The query would read more than the "
          + "connection's scan limit: " + message + ". Select fewer columns or filter on a partition column.", null);
      default -> switch (type) {
        case "USER_ERROR" -> ConnectorException.badInput(engine + " refused the statement: " + message);
        case "INSUFFICIENT_RESOURCES" -> ConnectorException.resourceError(engine + " ran out of resources for the "
            + "query: " + message + ". Narrow it.", null);
        default -> ConnectorException.resourceError(engine + " failed the query (" + (name.isEmpty() ? type : name)
            + "): " + message + ".", null);
      };
    };
  }

  private static ConnectorException deadline(Call c, Purpose purpose, String queryId) {
    if (purpose == Purpose.CHANGE) {
      return ConnectorException.outcomeUnknown(title(c) + " didn't finish the change within the call's time; "
          + "GateRook asked it to cancel query " + queryId + ", so whether it ran is not known. Check the table before "
          + "retrying.", null);
    }
    return ConnectorException.timeout(title(c) + " didn't finish the query within the call's time, so GateRook "
        + "cancelled it" + (queryId == null ? "" : " (query " + queryId + ")") + ". Narrow it, or filter on a "
        + "partition column.");
  }

  private static ConnectorException lost(Call c, String queryId, Throwable e) {
    return ConnectorException.outcomeUnknown("The connection to " + title(c) + " failed during the change"
        + (queryId == null ? "" : " (query " + queryId + ")") + ", so whether it ran is not known. Check the table "
        + "before retrying.", e);
  }

  static String title(Call c) {
    return c.settings().engine().title;
  }

  /** An answer larger than {@link #MAX_BODY}: read no further. */
  private static final class TooLarge extends IOException {
    private static final long serialVersionUID = 1L;

    TooLarge() {
      super("answer larger than " + (MAX_BODY >> 20) + " MiB");
    }
  }

  private static byte[] read(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
    byte[] buf = new byte[16384];
    long total = 0;
    int n;
    while ((n = in.read(buf)) > 0) {
      total += n;
      if (total > MAX_BODY) {
        throw new TooLarge();
      }
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  /** The first line of a body that isn't the protocol's JSON, sanitized and short. */
  private static String excerpt(byte[] body) {
    if (body == null || body.length == 0) {
      return "";
    }
    String text = firstLine(OutputSanitizer.decode(body, 0, Math.min(body.length, 2_000)));
    return text.length() <= 300 ? text : text.substring(0, 299) + "…";
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
