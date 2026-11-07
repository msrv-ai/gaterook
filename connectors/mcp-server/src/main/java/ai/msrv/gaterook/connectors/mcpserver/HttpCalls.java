package ai.msrv.gaterook.connectors.mcpserver;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connectors.support.egress.EgressClients;
import ai.msrv.gaterook.connectors.support.mcp.McpServerRuntime;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * The HTTP requests of one remote server's session, on the core's egress guard (ARCHITECTURE.md §7.5): the OkHttp
 * client of the connection's {@code GuardedEgress} resolves and connects under the environment's address policy (no
 * metadata, link-local or core ports, private ranges only as the policy allows), follows no redirect and never retries
 * a POST. Both HTTP channels send through it.
 *
 * <p>The person's headers (values from secrets) go first and the protocol's after them, so a configured header can't
 * replace them. A server the person signed in to gets the core's {@code Authorization} header on every request, after
 * the person's headers. A 401 (or a 403 for {@code insufficient_scope}) is the one answer a request is sent again for:
 * the server refused the token before it read the message, so nothing ran; the core renews the token once and the
 * request goes again with it. A second refusal means the person has to sign in again.
 *
 * <p>A server that signs in with cloud credentials gets the headers of its {@link RequestCredentials} on every request
 * instead: a Google token, or a SigV4 signature over the method, URL and body as they go out. A 401 renews a token once;
 * a refusal after that, or a 403, names the credentials and quotes the server, which says what is missing.
 */
final class HttpCalls {

  static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

  private final URI url;
  private final OkHttpClient client;
  private final OkHttpClient streams;
  private final Map<String, String> headers;
  private final long maxBytes;
  private final Duration timeout;
  private final String server;
  private final McpServerRuntime.Authorization authorization;
  private final RequestCredentials credentials;

  /**
   * @param headers the person's headers with their resolved values; kept for the session (a new secret version is a
   *                new connection version, which starts a new session)
   * @param timeout the longest one request may take, its answer included; a stream of its own has none
   */
  HttpCalls(URI url, OkHttpClient client, Map<String, String> headers, long maxBytes, Duration timeout, String server,
      McpServerRuntime.Authorization authorization, RequestCredentials credentials) {
    if (authorization != null && credentials != null) {
      throw new IllegalArgumentException("A server signs in one way");
    }
    this.url = url;
    this.client = client.newBuilder().callTimeout(timeout).readTimeout(timeout).build();
    // A stream waits for events as long as the session lives: no deadline, and a server that sends nothing (not even
    // a comment) for this long has gone away.
    this.streams = client.newBuilder().callTimeout(Duration.ZERO).readTimeout(Duration.ofMinutes(10)).build();
    this.headers = Map.copyOf(headers);
    this.maxBytes = maxBytes;
    this.timeout = timeout;
    this.server = server;
    this.authorization = authorization;
    this.credentials = credentials;
  }

  URI url() {
    return url;
  }

  String server() {
    return server;
  }

  long maxBytes() {
    return maxBytes;
  }

  Duration timeout() {
    return timeout;
  }

  McpServerRuntime.Authorization authorization() {
    return authorization;
  }

  RequestCredentials credentials() {
    return credentials;
  }

  /** The call that is under way, so that it can be cancelled from another thread. */
  interface Started {
    void call(Call call);
  }

  /** How long a request may take. */
  enum Budget {
    /** The session's timeout, the answer included. */
    CALL,
    /** An answer that is a stream of its own and may stay open as long as the session. */
    STREAM,
    /** A few seconds: what is sent on the way out ({@code DELETE} of the session). */
    SHORT
  }

  /**
   * Sends one request and returns the server's answer, which the caller reads and closes: never a refusal of the
   * sign-in, which is renewed and sent once more, or ends the request with the reason.
   *
   * @param body    the bytes to send ({@code null} for a GET or a DELETE)
   * @param extra   the protocol's headers, set after the person's
   * @param started hears each call as it starts (a refused one is replaced by its second attempt), or {@code null}
   */
  Response send(String method, URI target, byte[] body, Map<String, String> extra, Budget budget, Started started) {
    if (credentials != null) {
      return sendSigned(method, target, body, extra, budget, started);
    }
    String auth = authorization == null ? null : signIn(authorization::header);
    for (int attempt = 1; ; attempt++) {
      Response response = execute(method, target, body, extra, auth, budget, started);
      int status = response.code();
      String challenge = response.header("WWW-Authenticate");
      if (authorization == null || !(status == 401 || (status == 403 && insufficientScope(challenge)))) {
        return response;
      }
      response.close();
      String refusedWith = auth;
      if (attempt >= 2 && status == 401) {
        // The renewed token was refused too: renewing again can't help this request. The sign-in itself stays (a
        // server may take a moment to accept a new token); the person signs in again if it keeps happening.
        throw new Channel.Failure(server + " refused its renewed sign-in (HTTP 401).", 401,
            new McpServerRuntime.SignInRequired(server + " refused its renewed sign-in. If this keeps happening, "
                + "sign in to it again in GateRook › Connectors › MCP servers."));
      }
      // Refused before the server read it: the core renews the token, and the same message goes once more with it.
      String renewed = signIn(() -> authorization.refused(refusedWith, challenge, status));
      if (attempt >= 2) {
        throw new Channel.Failure(server + " refused its sign-in again (HTTP " + status + ").", status,
            new McpServerRuntime.SignInRequired(server + " refused its sign-in. Sign in to it again in GateRook › "
                + "Connectors › MCP servers."));
      }
      auth = renewed;
    }
  }

  /**
   * One request of a server that signs in with cloud credentials. A 401 renews a token and the request goes once
   * more (the server refused it before reading it, so nothing ran); a refusal after that, or a 403, ends it with the
   * server's words, which name the permission or role that is missing.
   */
  private Response sendSigned(String method, URI target, byte[] body, Map<String, String> extra, Budget budget,
      Started started) {
    for (int attempt = 1; ; attempt++) {
      Response response = execute(method, target, body, extra, null, budget, started);
      int status = response.code();
      if (status != 401 && status != 403) {
        return response;
      }
      String detail = snippet(response.body());
      response.close();
      if (attempt == 1 && status == 401 && credentials.refused()) {
        continue;
      }
      throw new Channel.Failure(server + " refused GateRook's " + credentials.label() + " (HTTP " + status + ")"
          + detail, status, null);
    }
  }

  private Response execute(String method, URI target, byte[] body, Map<String, String> extra, String auth,
      Budget budget, Started started) {
    Request.Builder b = new Request.Builder().url(target.toString());
    if (body != null) {
      // One-shot: OkHttp would post a tools/call again by itself on a 503 with Retry-After: 0 (EgressClients.onceBody).
      b.method(method, EgressClients.onceBody(body, JSON));
    } else if ("DELETE".equals(method)) {
      b.delete();
    } else {
      b.method(method, method.equals("GET") ? null : RequestBody.create(new byte[0]));
    }
    if (!personsHeaders(b)) {
      throw new Channel.Failure("A header for " + server + " (" + String.join(", ", unsendable())
          + ") has a line break or a control character in its value, which HTTP can't send. Set its secret again.");
    }
    if (auth != null) {
      try {
        b.header("Authorization", auth);
      } catch (IllegalArgumentException e) {
        // OkHttp names the header, not its value (Authorization is never quoted).
        throw new Channel.Failure(server + " got a token HTTP can't send. Sign in to it again.", 0,
            new McpServerRuntime.SignInRequired(server + " got a token HTTP can't send. Sign in to it again in "
                + "GateRook › Connectors › MCP servers."));
      }
    }
    if (credentials != null) {
      try {
        credentials.headers(method, HttpUrl.get(target.toString()).uri(), body == null ? new byte[0] : body)
            .forEach(b::header);
      } catch (ConnectorException e) {
        throw new Channel.Failure(e.getMessage(), 0, e);
      } catch (IllegalArgumentException e) {
        throw new Channel.Failure(server + " got a credential HTTP can't send.", 0, e);
      }
    }
    try {
      extra.forEach(b::header);
    } catch (IllegalArgumentException e) {
      throw new Channel.Failure("A header for " + server + " can't be sent: " + Texts.clean(e.getMessage(), 120,
          false));
    }
    Call call = (budget == Budget.STREAM ? streams : client).newCall(b.build());
    if (budget != Budget.STREAM) {
      call.timeout().timeout(budget == Budget.SHORT ? 3_000 : timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
    if (started != null) {
      started.call(call);
    }
    try {
      return call.execute();
    } catch (InterruptedIOException e) {
      throw new Channel.Failure(server + " didn't answer in " + timeout.toSeconds() + " s", 0, e);
    } catch (IOException e) {
      if (call.isCanceled()) {
        throw new Channel.Failure("The request to " + server + " was cancelled.", 0, e);
      }
      throw new Channel.Failure("Couldn't reach " + server + ": " + Texts.clean(e.getMessage(), 200, false), 0, e);
    }
  }

  /**
   * What an answer that isn't the protocol's says, or {@code null} when it is a success ({@code < 300}): a redirect
   * (never followed), a sign-in the server wants, a refused header, an error status. Closes nothing.
   */
  Channel.Failure refusal(Response response) {
    int status = response.code();
    if (status >= 300 && status < 400) {
      return new Channel.Failure(server + " answered with a redirect (HTTP " + status + "), which GateRook doesn't "
          + "follow: check the URL.", status, null);
    }
    String challenge = response.header("WWW-Authenticate");
    if (status == 401 && authorization == null && credentials == null && challenge != null
        && challenge.toLowerCase(Locale.ROOT).contains("resource_metadata")) {
      return new Channel.Failure(server + " wants you to sign in to it (OAuth): open it in GateRook › Connectors › "
          + "MCP servers and choose Sign in.", status, new McpServerRuntime.SignInRequired(server + " wants you to "
              + "sign in to it. Open it in GateRook › Connectors › MCP servers and choose Sign in."));
    }
    if (status == 401 || status == 403) {
      return new Channel.Failure(server + " refused the request (HTTP " + status + "): check the headers and their "
          + "secrets.", status, null);
    }
    if (status >= 400) {
      return new Channel.Failure(server + " answered HTTP " + status + snippet(response.body()), status, null);
    }
    return null;
  }

  /** A 403 that asks for more permissions ({@code error="insufficient_scope"}, RFC 6750 §3.1). */
  static boolean insufficientScope(String challenge) {
    if (challenge == null) {
      return false;
    }
    String c = challenge.toLowerCase(Locale.ROOT).replace(" ", "");
    return c.contains("error=\"insufficient_scope\"") || c.contains("error=insufficient_scope");
  }

  /** A step of the core's sign-in; a missing one ends the request with the reason before anything is sent. */
  private interface SignInStep {
    String run() throws McpServerRuntime.SignInRequired, ConnectorException;
  }

  private String signIn(SignInStep step) {
    try {
      String header = step.run();
      if (header == null || header.isBlank()) {
        throw new Channel.Failure(server + " has no sign-in to send.", 0,
            new McpServerRuntime.SignInRequired(server + " isn't signed in. Sign in to it in GateRook › Connectors › "
                + "MCP servers."));
      }
      return header;
    } catch (McpServerRuntime.SignInRequired e) {
      throw new Channel.Failure(e.getMessage(), 0, e);
    } catch (ConnectorException e) {
      throw new Channel.Failure(e.getMessage(), 0, e);
    }
  }

  /**
   * The person's headers on {@code b}; {@code false} when a value can't be sent. OkHttp's message for such a value
   * quotes it (for every header but Authorization and Cookie): it is dropped, only the names are reported.
   */
  private boolean personsHeaders(Request.Builder b) {
    try {
      headers.forEach(b::header);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /** The names of the person's headers whose values HTTP can't carry. */
  private List<String> unsendable() {
    List<String> out = new ArrayList<>();
    headers.forEach((name, value) -> {
      try {
        new Request.Builder().header(name, value);
      } catch (IllegalArgumentException e) {
        out.add(name);
      }
    });
    return out;
  }

  static String snippet(ResponseBody body) {
    if (body == null) {
      return "";
    }
    try {
      String text = body.source().request(301) ? body.source().readUtf8(300) : body.string();
      String clean = Texts.clean(text, 300, false);
      return clean == null || clean.isBlank() ? "" : ": " + clean;
    } catch (IOException e) {
      return "";
    }
  }

  // ── Event streams ───────────────────────────────────────────────────────────────────────────────────────────

  /** One server-sent event: its type ({@code message} unless named) and its data. */
  record Event(String type, String data) {}

  /** Hears the events of a stream; {@code false} stops reading it. */
  interface Events {
    boolean event(Event e);
  }

  /**
   * Server-sent events until {@code events} says stop or the stream ends. At most {@link #maxBytes} of one event are
   * ever held: {@code readUtf8Line()} would buffer a line without end for as long as the server sends it.
   */
  void readEvents(BufferedSource source, Events events) throws IOException {
    StringBuilder data = new StringBuilder();
    String type = null;
    while (true) {
      String line = line(source, maxBytes - data.length());
      if (line == null) {
        return;
      }
      if (line.isEmpty()) {
        if (!data.isEmpty() || type != null) {
          if (!events.event(new Event(type == null ? "message" : type, data.toString()))) {
            return;
          }
        }
        data.setLength(0);
        type = null;
      } else if (line.startsWith("data:")) {
        if (!data.isEmpty()) {
          data.append('\n');
        }
        data.append(line.startsWith("data: ") ? line.substring(6) : line.substring(5));
        if (data.length() > maxBytes) {
          throw tooMuch();
        }
      } else if (line.startsWith("event:")) {
        type = line.substring(6).strip();
      }
      // Comments, ids and retries carry nothing the client uses.
    }
  }

  /**
   * The next line of an event stream without its end ({@code \n} or {@code \r\n}), or {@code null} at the end. At most
   * {@code limit} bytes are ever held.
   */
  private String line(BufferedSource source, long limit) throws IOException {
    long newline = source.indexOf((byte) '\n', 0, Math.max(0, limit) + 1);
    if (newline < 0) {
      if (source.request(Math.max(0, limit) + 1)) {
        throw tooMuch();
      }
      // The stream ended without a line break: what is left is its last line.
      return source.exhausted() ? null : stripCr(source.readUtf8());
    }
    String line = source.readUtf8(newline);
    source.skip(1);
    return stripCr(line);
  }

  private static String stripCr(String line) {
    return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
  }

  Channel.Failure tooMuch() {
    return new Channel.Failure(server + " sent more than " + (maxBytes / (1024 * 1024)) + " MB");
  }

  /** A JSON body of at most {@link #maxBytes}. */
  String readBounded(BufferedSource source) throws IOException {
    if (!source.request(maxBytes + 1)) {
      return source.readUtf8();
    }
    throw tooMuch();
  }
}
