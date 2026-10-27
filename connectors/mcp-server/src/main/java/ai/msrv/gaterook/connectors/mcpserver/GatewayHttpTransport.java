package ai.msrv.gaterook.connectors.mcpserver;

import ai.msrv.gaterook.connectors.support.egress.EgressClients;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;

/**
 * Streamable HTTP to a remote MCP server (ARCHITECTURE.md §7.5) on the core's egress guard: the OkHttp client of the
 * connection's {@code GuardedEgress} resolves and connects under the environment's address policy (no metadata,
 * link-local or core ports, private ranges only as the policy allows), follows no redirect and never retries a POST.
 *
 * <p>Every message is one POST; the answer is JSON or an event stream that ends with the response, as the
 * 2025-03-26+ transport allows. The person's headers (values from secrets) go first and the transport's own
 * ({@code Content-Type}, {@code Accept}, {@code Mcp-Session-Id}, {@code MCP-Protocol-Version}) after them, so a
 * configured header can't replace them. There is no GET stream: server-initiated messages arrive only within a POST,
 * and the tool list is read again on every (re)start instead of on {@code list_changed}.
 */
final class GatewayHttpTransport implements McpClientTransport {

  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
  /** POSTs block on network I/O: virtual threads, never a Reactor or caller platform thread. */
  private static final ExecutorService IO = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
      .name("mcp-http-", 0).factory());
  private static final Scheduler SCHEDULER = Schedulers.fromExecutorService(IO, "mcp-http");

  private final URI url;
  private final OkHttpClient client;
  private final Map<String, String> headers;
  private final McpJsonMapper mapper;
  private final long maxBytes;
  private final Duration timeout;
  private final String server;
  private volatile Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler;
  private volatile Consumer<Throwable> exceptions = t -> {};
  private volatile String sessionId;
  private volatile String protocolVersion;
  private volatile boolean closed;

  /**
   * @param headers the person's headers with their resolved values; kept for the session (a new secret version is a
   *                new connection version, which starts a new session)
   * @param timeout the longest one POST may take, its answer included
   */
  GatewayHttpTransport(URI url, OkHttpClient client, Map<String, String> headers, McpJsonMapper mapper, long maxBytes,
      Duration timeout, String server) {
    this.url = url;
    this.client = client.newBuilder().callTimeout(timeout).readTimeout(timeout).build();
    this.headers = Map.copyOf(headers);
    this.mapper = mapper;
    this.maxBytes = maxBytes;
    this.timeout = timeout;
    this.server = server;
  }

  String sessionId() {
    return sessionId;
  }

  @Override
  public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
    this.handler = handler;
    return Mono.empty();
  }

  @Override
  public void setExceptionHandler(Consumer<Throwable> handler) {
    this.exceptions = handler;
  }

  @Override
  public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
    if (closed) {
      return Mono.error(new McpTransportException("The session with " + server + " is closed"));
    }
    return Mono.<Void>fromRunnable(() -> post(message)).subscribeOn(SCHEDULER);
  }

  @Override
  public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
    return mapper.convertValue(data, typeRef);
  }

  /** Ends the session on the server too ({@code DELETE} with the session id), best effort. */
  @Override
  public Mono<Void> closeGracefully() {
    return Mono.<Void>fromRunnable(() -> {
      closed = true;
      String id = sessionId;
      if (id == null) {
        return;
      }
      Request.Builder b = new Request.Builder().url(url.toString()).delete();
      if (!personsHeaders(b)) {
        return;
      }
      b.header("Mcp-Session-Id", id);
      Call call = client.newBuilder().callTimeout(Duration.ofSeconds(3)).build().newCall(b.build());
      try (Response ignored = call.execute()) {
        // 200, 204 or 405 (a server without explicit termination): all fine.
      } catch (IOException e) {
        // The server may already be gone.
      }
    }).subscribeOn(SCHEDULER);
  }

  // ── One message ─────────────────────────────────────────────────────────────────────────────────────────────

  private void post(McpSchema.JSONRPCMessage message) {
    String body;
    try {
      body = mapper.writeValueAsString(message);
    } catch (IOException e) {
      throw new McpTransportException("Couldn't write a message for " + server, e);
    }
    // One-shot: OkHttp would post a tools/call again by itself on a 503 with Retry-After: 0 (EgressClients.onceBody).
    Request.Builder b = new Request.Builder().url(url.toString())
        .post(EgressClients.onceBody(body.getBytes(StandardCharsets.UTF_8), JSON));
    if (!personsHeaders(b)) {
      throw new McpTransportException("A header for " + server + " (" + String.join(", ", unsendable())
          + ") has a line break or a control character in its value, which HTTP can't send. Set its secret again.");
    }
    b.header("Content-Type", "application/json");
    b.header("Accept", "application/json, text/event-stream");
    boolean initialize = message instanceof McpSchema.JSONRPCRequest r && McpSchema.METHOD_INITIALIZE.equals(r.method());
    String id = sessionId;
    if (id != null && !initialize) {
      b.header("Mcp-Session-Id", id);
    }
    String version = protocolVersion;
    if (version != null && !initialize) {
      b.header("MCP-Protocol-Version", version);
    }
    Call call = client.newCall(b.build());
    call.timeout().timeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
    try (Response response = call.execute()) {
      String assigned = response.header("Mcp-Session-Id");
      if (initialize && assigned != null && !assigned.isBlank()) {
        sessionId = assigned.strip();
      }
      int status = response.code();
      if (status == 404 && id != null && !initialize) {
        // The server forgot the session: the client starts a new one (LifecycleInitializer.handleException).
        sessionId = null;
        McpTransportSessionNotFoundException gone = new McpTransportSessionNotFoundException(id);
        exceptions.accept(gone);
        throw gone;
      }
      if (status >= 300 && status < 400) {
        throw new McpTransportException(server + " answered with a redirect (HTTP " + status + "), which GateRook "
            + "doesn't follow: check the URL.");
      }
      if (status == 401 || status == 403) {
        throw new McpTransportException(server + " refused the request (HTTP " + status + "): check the headers and "
            + "their secrets.");
      }
      if (status >= 400) {
        throw new McpTransportException(server + " answered HTTP " + status + snippet(response.body()));
      }
      if (status == 202 || status == 204) {
        return;
      }
      ResponseBody responseBody = response.body();
      String type = response.header("Content-Type", "").toLowerCase(Locale.ROOT);
      if (type.startsWith("text/event-stream")) {
        readEvents(responseBody.source(), message);
      } else {
        for (McpSchema.JSONRPCMessage m : parse(readBounded(responseBody.source()))) {
          deliver(m);
        }
      }
    } catch (InterruptedIOException e) {
      throw new McpTransportException(server + " didn't answer in " + timeout.toSeconds() + " s", e);
    } catch (IOException e) {
      throw new McpTransportException("Couldn't reach " + server + ": " + Texts.clean(e.getMessage(), 200, false), e);
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

  /**
   * Server-sent events until the response to {@code sent} arrived (then the stream is left), or the stream ends.
   * Each event's data is one JSON-RPC message; comments, ids and retries carry nothing for a POST stream.
   */
  private void readEvents(BufferedSource source, McpSchema.JSONRPCMessage sent) throws IOException {
    Object awaited = sent instanceof McpSchema.JSONRPCRequest r ? r.id() : null;
    StringBuilder data = new StringBuilder();
    long read = 0;
    while (true) {
      String line = line(source, maxBytes - read);
      if (line == null) {
        return;
      }
      read += line.length() + 1;
      if (read > maxBytes) {
        throw tooMuch();
      }
      if (line.isEmpty()) {
        if (!data.isEmpty()) {
          for (McpSchema.JSONRPCMessage m : parse(data.toString())) {
            deliver(m);
            if (awaited != null && m instanceof McpSchema.JSONRPCResponse resp && awaited.equals(resp.id())) {
              return;
            }
          }
          data.setLength(0);
        }
      } else if (line.startsWith("data:")) {
        if (!data.isEmpty()) {
          data.append('\n');
        }
        data.append(line.startsWith("data: ") ? line.substring(6) : line.substring(5));
      }
    }
  }

  /**
   * The next line of an event stream without its end ({@code \n} or {@code \r\n}), or {@code null} at the end. At most
   * {@code limit} bytes are ever held: {@code readUtf8Line()} would buffer a line without end for as long as the
   * server sends it.
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

  private McpTransportException tooMuch() {
    return new McpTransportException(server + " sent more than " + (maxBytes / (1024 * 1024)) + " MB");
  }

  private String readBounded(BufferedSource source) throws IOException {
    if (!source.request(maxBytes + 1)) {
      return source.readUtf8();
    }
    throw tooMuch();
  }

  private List<McpSchema.JSONRPCMessage> parse(String text) {
    List<McpSchema.JSONRPCMessage> out = new ArrayList<>();
    if (text == null || text.isBlank()) {
      return out;
    }
    try {
      JsonNode tree = Texts.JSON.readTree(text);
      if (tree.isArray()) {
        for (JsonNode element : tree) {
          out.add(McpSchema.deserializeJsonRpcMessage(mapper, Texts.JSON.writeValueAsString(element)));
        }
      } else {
        out.add(McpSchema.deserializeJsonRpcMessage(mapper, text));
      }
    } catch (IOException | RuntimeException e) {
      throw new McpTransportException(server + " answered with something that isn't JSON-RPC: "
          + Texts.clean(text, 200, false));
    }
    return out;
  }

  private void deliver(McpSchema.JSONRPCMessage m) {
    if (m instanceof McpSchema.JSONRPCResponse r && r.result() instanceof Map<?, ?> result
        && result.get("protocolVersion") instanceof String v && result.containsKey("capabilities")) {
      // The InitializeResult: every later request names the revision both agreed on.
      protocolVersion = v;
    }
    Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> h = handler;
    if (h != null) {
      h.apply(Mono.just(m)).subscribe(ignored -> {}, error -> {});
    }
  }

  private static String snippet(ResponseBody body) {
    try {
      String text = body.source().request(301) ? body.source().readUtf8(300) : body.string();
      String clean = Texts.clean(text, 300, false);
      return clean == null || clean.isBlank() ? "" : ": " + clean;
    } catch (IOException e) {
      return "";
    }
  }
}
