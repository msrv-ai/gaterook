package ai.msrv.gaterook.connectors.mcpserver;

import ai.msrv.gaterook.connector.api.ConnectorException;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.Response;
import okhttp3.ResponseBody;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The HTTP+SSE transport of the 2024-11-05 revision, for servers that never moved to Streamable HTTP: a GET opens an
 * event stream whose first event ({@code endpoint}) names where messages go, every message is a POST there (answered
 * 202), and every answer and notification comes back on the stream. The stream lives as long as the session; when it
 * ends, so does the session.
 *
 * <p>The message endpoint must be on the server's own origin (scheme, host and port of the configured URL): it is the
 * server's word, and the person's headers and sign-in go wherever it points. Both requests carry them, through
 * {@link HttpCalls} and the egress guard like every other request.
 */
final class GatewaySseTransport implements Channel {

  private static final ExecutorService IO = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
      .name("mcp-sse-", 0).factory());

  private final HttpCalls http;
  private final CompletableFuture<URI> endpoint = new CompletableFuture<>();
  private volatile Sink sink;
  private volatile Call stream;
  private volatile boolean closed;
  private volatile boolean opened;

  GatewaySseTransport(HttpCalls http) {
    this.http = http;
  }

  HttpCalls http() {
    return http;
  }

  /** Whether the server answered the GET with an event stream: it is an SSE server, whatever happened next. */
  boolean opened() {
    return opened;
  }

  /** Opens the event stream and waits for the server to name its message endpoint. */
  @Override
  public void open(Sink sink) throws ConnectorException {
    this.sink = sink;
    Response response = http.send("GET", http.url(), null, Map.of("Accept", "text/event-stream"),
        HttpCalls.Budget.STREAM, call -> stream = call);
    try {
      Failure refusal = http.refusal(response);
      if (refusal != null) {
        throw refusal;
      }
      String type = response.header("Content-Type", "").toLowerCase(Locale.ROOT);
      ResponseBody body = response.body();
      if (!type.startsWith("text/event-stream") || body == null) {
        throw new Failure(http.server() + " didn't answer with an event stream (" + Texts.clean(type, 80, false)
            + "): it isn't an SSE server.", response.code(), null);
      }
    } catch (RuntimeException e) {
      response.close();
      throw e;
    }
    opened = true;
    IO.execute(() -> read(response));
    try {
      endpoint.get(Math.max(1, http.timeout().toMillis()), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      close();
      throw ConnectorException.timeout(http.server() + " didn't name its message endpoint in "
          + Math.max(1, http.timeout().toSeconds()) + " s.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      close();
      throw ConnectorException.timeout("Opening the event stream of " + http.server() + " was cancelled.");
    } catch (ExecutionException e) {
      close();
      Throwable cause = e.getCause();
      throw ConnectorException.unavailable(cause.getMessage(), cause);
    }
  }

  private void read(Response response) {
    String why = "The server ended its event stream";
    try (response) {
      http.readEvents(response.body().source(), e -> {
        if ("endpoint".equals(e.type())) {
          if (!endpoint.isDone()) {
            URI resolved = resolve(e.data());
            if (resolved == null) {
              endpoint.completeExceptionally(new Failure(http.server() + " named a message endpoint outside its own "
                  + "origin, which GateRook doesn't send to: " + Texts.clean(e.data(), 200, false)));
              return false;
            }
            endpoint.complete(resolved);
          }
          return !closed;
        }
        if ("message".equals(e.type())) {
          ObjectNode m = parse(e.data());
          Sink s = sink;
          if (m != null && s != null) {
            s.message(m, null);
          }
        }
        return !closed;
      });
    } catch (IOException | RuntimeException e) {
      why = closed ? "The session is closed" : "Lost the event stream of " + http.server() + ": "
          + Texts.clean(String.valueOf(e.getMessage()), 200, false);
    } finally {
      closed = true;
      endpoint.completeExceptionally(new Failure(why));
      Sink s = sink;
      if (s != null) {
        s.closed(why);
      }
    }
  }

  /** The endpoint the server named, on the configured URL's origin; {@code null} when it points anywhere else. */
  URI resolve(String data) {
    String raw = data == null ? "" : data.strip();
    if (raw.isEmpty()) {
      return null;
    }
    HttpUrl base = HttpUrl.get(http.url().toString());
    HttpUrl target = base.resolve(raw);
    if (target == null || !target.scheme().equals(base.scheme()) || !target.host().equalsIgnoreCase(base.host())
        || target.port() != base.port() || !target.username().isEmpty() || !target.password().isEmpty()) {
      return null;
    }
    return target.uri();
  }

  private ObjectNode parse(String data) {
    try {
      JsonNode tree = Texts.JSON.readTree(data);
      return tree instanceof ObjectNode o ? o : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  @Override
  public Exchange send(ObjectNode message, Map<String, String> headers) {
    if (closed || !endpoint.isDone() || endpoint.isCompletedExceptionally()) {
      return Exchange.failed(new Failure("The event stream of " + http.server() + " is closed"));
    }
    URI target = endpoint.join();
    CompletableFuture<Void> done = new CompletableFuture<>();
    Call[] current = new Call[1];
    IO.execute(() -> {
      try (Response response = http.send("POST", target, Texts.JSON.writeValueAsBytes(message),
          Map.of("Content-Type", "application/json"), HttpCalls.Budget.CALL, call -> current[0] = call)) {
        Failure refusal = http.refusal(response);
        if (refusal != null) {
          throw refusal;
        }
        done.complete(null);
      } catch (RuntimeException e) {
        done.completeExceptionally(e);
      }
    });
    return new Exchange() {
      @Override
      public CompletableFuture<Void> done() {
        return done;
      }

      @Override
      public void abort() {
        Call c = current[0];
        if (c != null) {
          c.cancel();
        }
      }
    };
  }

  @Override
  public boolean answersInExchange() {
    return false;
  }

  @Override
  public boolean alive() {
    return !closed;
  }

  @Override
  public void close() {
    closed = true;
    Call c = stream;
    if (c != null) {
      c.cancel();
    }
  }
}
