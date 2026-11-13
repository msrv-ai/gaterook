package com.gaterook.connectors.mcpserver;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import okhttp3.Response;
import okhttp3.ResponseBody;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Streamable HTTP to a remote MCP server (ARCHITECTURE.md §7.5), both protocol eras, over {@link HttpCalls} and the
 * core's egress guard.
 *
 * <p>Every message is one POST; the answer is JSON or an event stream that carries the call's progress and log
 * messages and ends with the response. A JSON-RPC error that comes as an HTTP 4xx body (2026-07-28 answers an unknown
 * method with 404, an unsupported revision with 400) reaches the client as the error it is. The 2025 revisions number
 * a session ({@code Mcp-Session-Id} from {@code initialize}); a server that forgot it answers 404, and the next call
 * opens another one. Such a server may also keep a GET stream open for what isn't an answer ({@link #listen}); a server
 * of 2026-07-28 sends that on a {@code subscriptions/listen} request's stream instead, which stays open as long as the
 * session.
 */
final class GatewayHttpTransport implements Channel {

  /** POSTs block on network I/O: virtual threads, never a caller's platform thread. */
  private static final ExecutorService IO = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
      .name("mcp-http-", 0).factory());

  private final HttpCalls http;
  private final Set<Call> calls = ConcurrentHashMap.newKeySet();
  private volatile Sink sink;
  private volatile String sessionId;
  private volatile boolean closed;
  private volatile Thread listener;

  GatewayHttpTransport(HttpCalls http) {
    this.http = http;
  }

  String sessionId() {
    return sessionId;
  }

  HttpCalls http() {
    return http;
  }

  @Override
  public void open(Sink sink) {
    this.sink = sink;
  }

  @Override
  public Exchange send(ObjectNode message, Map<String, String> headers) {
    if (closed) {
      return Exchange.failed(new Failure("The session with " + http.server() + " is closed"));
    }
    CompletableFuture<Void> done = new CompletableFuture<>();
    AtomicReference<Call> current = new AtomicReference<>();
    IO.execute(() -> {
      try {
        post(message, headers, current);
        done.complete(null);
      } catch (RuntimeException e) {
        done.completeExceptionally(e);
      } finally {
        Call c = current.get();
        if (c != null) {
          calls.remove(c);
        }
      }
    });
    return new Exchange() {
      @Override
      public CompletableFuture<Void> done() {
        return done;
      }

      @Override
      public void abort() {
        Call c = current.get();
        if (c != null) {
          c.cancel();
        }
      }
    };
  }

  @Override
  public boolean answersInExchange() {
    return true;
  }

  @Override
  public boolean alive() {
    return !closed;
  }

  /** Ends the session on the server too ({@code DELETE} with the session id), best effort. */
  @Override
  public void close() {
    closed = true;
    Thread l = listener;
    if (l != null) {
      l.interrupt();
    }
    for (Call c : List.copyOf(calls)) {
      c.cancel();
    }
    String id = sessionId;
    if (id == null) {
      return;
    }
    try (Response ignored = http.send("DELETE", http.url(), null, Map.of("Mcp-Session-Id", id),
        HttpCalls.Budget.SHORT, null)) {
      // 200, 204 or 405 (a server without explicit termination): all fine.
    } catch (RuntimeException e) {
      // The server may already be gone, or the sign-in with it: it forgets the session by itself.
    }
  }

  // ── One message ─────────────────────────────────────────────────────────────────────────────────────────────

  private void post(ObjectNode message, Map<String, String> protocol, AtomicReference<Call> current) {
    byte[] bytes = Texts.JSON.writeValueAsBytes(message);
    String method = Texts.str(message.get("method"), "");
    boolean initialize = "initialize".equals(method);
    Map<String, String> extra = new LinkedHashMap<>(protocol);
    extra.put("Content-Type", "application/json");
    extra.put("Accept", "application/json, text/event-stream");
    String id = sessionId;
    if (id != null && !initialize) {
      extra.put("Mcp-Session-Id", id);
    }
    // A listen request's answer is a stream as long as the session: no deadline of its own.
    HttpCalls.Budget budget = "subscriptions/listen".equals(method) ? HttpCalls.Budget.STREAM
        : HttpCalls.Budget.CALL;
    Long awaited = Rpc.isRequest(message) ? Rpc.id(message) : null;
    try (Response response = http.send("POST", http.url(), bytes, extra, budget, call -> {
      current.set(call);
      calls.add(call);
      if (closed) {
        call.cancel();
      }
    })) {
      int status = response.code();
      String assigned = response.header("Mcp-Session-Id");
      if (initialize && assigned != null && !assigned.isBlank()) {
        sessionId = assigned.strip();
      }
      if (status == 404 && id != null && !initialize) {
        // The server forgot the session: the request never reached it, and the next start opens another.
        sessionId = null;
        throw new SessionGone(http.server() + " ended the session " + id);
      }
      if (status >= 400 && status != 401 && status != 403 && json(response) && answeredWithError(response,
          awaited, status)) {
        return;
      }
      Failure refusal = http.refusal(response);
      if (refusal != null) {
        throw refusal;
      }
      if (status == 202 || status == 204) {
        return;
      }
      ResponseBody body = response.body();
      if (body == null) {
        return;
      }
      if (type(response).startsWith("text/event-stream")) {
        http.readEvents(body.source(), e -> {
          for (ObjectNode m : parse(e.data())) {
            deliver(m, awaited);
            if (awaited != null && Rpc.isResponse(m) && awaited.equals(Rpc.id(m))) {
              return false;
            }
          }
          return !closed;
        });
      } else {
        for (ObjectNode m : parse(http.readBounded(body.source()))) {
          deliver(m, awaited);
        }
      }
    } catch (IOException e) {
      if (closed) {
        throw new Failure("The session with " + http.server() + " is closed", 0, e);
      }
      throw new Failure("Lost the answer of " + http.server() + ": " + Texts.clean(e.getMessage(), 200, false), 0, e);
    }
  }

  /**
   * A 4xx whose body is a JSON-RPC error: the answer to {@code awaited} reaches the client as that error; any other
   * error body ({@code id: null}, "no valid session") ends the exchange with its message and status.
   *
   * @return whether the answer was delivered
   */
  private boolean answeredWithError(Response response, Long awaited, int status) throws IOException {
    ResponseBody body = response.body();
    if (body == null) {
      return false;
    }
    String text = http.readBounded(body.source());
    List<ObjectNode> messages = parse(text, false);
    for (ObjectNode m : messages) {
      if (Rpc.isResponse(m) && m.has("error") && awaited != null && awaited.equals(Rpc.id(m))) {
        deliver(m, awaited);
        return true;
      }
    }
    for (ObjectNode m : messages) {
      if (m.path("error").isObject()) {
        throw new Failure(http.server() + " answered HTTP " + status + ": " + Rpc.Error.of(m.get("error"))
            .getMessage(), status, null);
      }
    }
    throw new Failure(http.server() + " answered HTTP " + status + ": " + Texts.clean(text, 300, false), status, null);
  }

  @Override
  public void listen(Map<String, String> headers) {
    if (closed || listener != null || sessionId == null) {
      return;
    }
    synchronized (this) {
      if (listener != null) {
        return;
      }
      listener = Thread.ofVirtual().name("mcp-http-stream").start(() -> streamLoop(Map.copyOf(headers)));
    }
  }

  /**
   * The GET stream while the session lives: opened again after it drops (1, 2, 4 … 30 s apart), given up when the
   * server has none (405) or refuses it.
   */
  private void streamLoop(Map<String, String> headers) {
    long backoff = 1_000;
    int refusals = 0;
    while (!closed && !Thread.currentThread().isInterrupted()) {
      Map<String, String> extra = new LinkedHashMap<>(headers);
      extra.put("Accept", "text/event-stream");
      String id = sessionId;
      if (id == null) {
        return;
      }
      extra.put("Mcp-Session-Id", id);
      long opened = System.nanoTime();
      try (Response response = http.send("GET", http.url(), null, extra, HttpCalls.Budget.STREAM, call -> {
        calls.add(call);
        if (closed) {
          call.cancel();
        }
      })) {
        int status = response.code();
        if (status == 405 || status == 404 || !type(response).startsWith("text/event-stream")) {
          // No stream for this server (or the session is gone, which the next request finds out).
          return;
        }
        if (http.refusal(response) != null && ++refusals >= 3) {
          return;
        }
        ResponseBody body = response.body();
        if (status == 200 && body != null) {
          http.readEvents(body.source(), e -> {
            for (ObjectNode m : parse(e.data())) {
              deliver(m, null);
            }
            return !closed;
          });
        }
      } catch (IOException | RuntimeException e) {
        // Dropped: opened again below while the session lives.
      }
      if (Duration.ofNanos(System.nanoTime() - opened).toSeconds() > 60) {
        backoff = 1_000;
      }
      try {
        Thread.sleep(backoff);
      } catch (InterruptedException e) {
        return;
      }
      backoff = Math.min(30_000, backoff * 2);
    }
  }

  private static String type(Response response) {
    return response.header("Content-Type", "").toLowerCase(Locale.ROOT);
  }

  private static boolean json(Response response) {
    return type(response).startsWith("application/json");
  }

  private List<ObjectNode> parse(String text) {
    return parse(text, true);
  }

  /** The JSON-RPC objects of a body or an event (one object or an array of them). */
  private List<ObjectNode> parse(String text, boolean strict) {
    List<ObjectNode> out = new ArrayList<>();
    if (text == null || text.isBlank()) {
      return out;
    }
    try {
      JsonNode tree = Texts.JSON.readTree(text);
      if (tree.isArray()) {
        for (JsonNode element : tree) {
          if (element instanceof ObjectNode o) {
            out.add(o);
          }
        }
      } else if (tree instanceof ObjectNode o) {
        out.add(o);
      }
    } catch (RuntimeException e) {
      if (strict) {
        throw new Failure(http.server() + " answered with something that isn't JSON-RPC: "
            + Texts.clean(text, 200, false));
      }
    }
    return out;
  }

  private void deliver(ObjectNode m, Long stream) {
    Sink s = sink;
    if (s != null) {
      s.message(m, stream);
    }
  }
}
