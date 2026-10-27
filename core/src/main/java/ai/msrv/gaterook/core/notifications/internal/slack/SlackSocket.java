package ai.msrv.gaterook.core.notifications.internal.slack;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * One Socket Mode connection (report 13 §7) on {@code java.net.http.WebSocket}: Slack sends {@code hello}, then
 * envelopes; each envelope is acknowledged at once ({@code {"envelope_id": …}}, Slack's deadline is 3 s) and handed to
 * the handler, which routes it on its own thread. Text frames arrive in parts and are joined. Sends are chained: the
 * JDK refuses a text send while another one is pending. Pings and their pongs are watched by the owner.
 */
final class SlackSocket implements WebSocket.Listener {

  private static final Logger log = LoggerFactory.getLogger(SlackSocket.class);

  /** What the connection tells its owner. */
  interface Handler {
    /** {@code hello}: the connection is ready; {@code appId} is the app the connection belongs to. */
    void hello(SlackSocket socket, String appId);

    /** An envelope ({@code interactive}, {@code events_api}…), acknowledged already. */
    void envelope(SlackSocket socket, String envelopeId, String type, JsonNode payload);

    /** {@code disconnect} with its reason: {@code refresh_requested}, {@code warning}, {@code link_disabled}. */
    void disconnect(SlackSocket socket, String reason);

    /** The connection is gone (closed by either side, or failed). */
    void closed(SlackSocket socket, Throwable error);
  }

  private final JsonMapper json;
  private final Handler handler;
  private final StringBuilder frame = new StringBuilder();
  private final AtomicBoolean ended = new AtomicBoolean();
  private CompletableFuture<?> sends = CompletableFuture.completedFuture(null);
  private volatile WebSocket ws;
  private volatile long lastPongNanos = System.nanoTime();
  private volatile boolean greeted;

  private SlackSocket(JsonMapper json, Handler handler) {
    this.json = json;
    this.handler = handler;
  }

  /** Connects to {@code url} (from {@code apps.connections.open}); the future completes once the socket is open. */
  static CompletableFuture<SlackSocket> connect(HttpClient http, URI url, JsonMapper json, Handler handler,
      Duration timeout) {
    SlackSocket socket = new SlackSocket(json, handler);
    return http.newWebSocketBuilder().connectTimeout(timeout).buildAsync(url, socket)
        .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).thenApply(ws -> {
          socket.ws = ws;
          return socket;
        });
  }

  @Override
  public void onOpen(WebSocket webSocket) {
    this.ws = webSocket;
    webSocket.request(1);
  }

  @Override
  public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
    frame.append(data);
    webSocket.request(1);
    if (!last) {
      return null;
    }
    String text = frame.toString();
    frame.setLength(0);
    JsonNode m;
    try {
      m = json.readTree(text);
    } catch (RuntimeException e) {
      log.debug("Slack sent a frame that isn't JSON ({} chars)", text.length());
      return null;
    }
    String type = m.path("type").asString("");
    switch (type) {
      case "hello" -> {
        greeted = true;
        lastPongNanos = System.nanoTime();
        handler.hello(this, m.at("/connection_info/app_id").asString(null));
      }
      case "disconnect" -> handler.disconnect(this, m.path("reason").asString(""));
      default -> {
        String envelope = m.path("envelope_id").asString(null);
        if (envelope != null) {
          ack(envelope);
          handler.envelope(this, envelope, type, m.path("payload"));
        }
      }
    }
    return null;
  }

  @Override
  public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
    lastPongNanos = System.nanoTime();
    webSocket.request(1);
    return null;
  }

  @Override
  public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
    // The JDK answers pings itself; only ask for the next message.
    webSocket.request(1);
    return null;
  }

  @Override
  public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
    end(null);
    return null;
  }

  @Override
  public void onError(WebSocket webSocket, Throwable error) {
    end(error);
  }

  /** Acknowledges an envelope at once, behind any send still in flight. */
  synchronized void ack(String envelopeId) {
    String body = JsonNodeFactory.instance.objectNode().put("envelope_id", envelopeId).toString();
    WebSocket w = ws;
    if (w == null) {
      return;
    }
    sends = sends.handle((x, t) -> null).thenCompose(x -> w.sendText(body, true));
  }

  /** Sends a ping (a pong refreshes {@link #pongAge}). */
  synchronized void ping() {
    WebSocket w = ws;
    if (w != null && !w.isOutputClosed()) {
      w.sendPing(ByteBuffer.wrap(new byte[] {'g', 'w'})).exceptionally(t -> null);
    }
  }

  /** Slack said {@code hello} on this connection. */
  boolean greeted() {
    return greeted;
  }

  /** How long ago the last pong (or the connection) was. */
  Duration pongAge() {
    return Duration.ofNanos(System.nanoTime() - lastPongNanos);
  }

  /** Closes the connection politely; the handler hears {@code closed} once. */
  void close() {
    WebSocket w = ws;
    if (w != null && !w.isOutputClosed()) {
      w.sendClose(WebSocket.NORMAL_CLOSURE, "").orTimeout(2, TimeUnit.SECONDS).whenComplete((x, t) -> w.abort());
    }
    end(null);
  }

  void abort() {
    WebSocket w = ws;
    if (w != null) {
      w.abort();
    }
    end(null);
  }

  private void end(Throwable error) {
    if (ended.compareAndSet(false, true)) {
      handler.closed(this, error);
    }
  }
}
