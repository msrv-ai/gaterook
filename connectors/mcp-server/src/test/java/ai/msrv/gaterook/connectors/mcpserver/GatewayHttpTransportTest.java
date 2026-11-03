package ai.msrv.gaterook.connectors.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ai.msrv.gaterook.connectors.support.egress.AddressPolicy;
import ai.msrv.gaterook.connectors.support.egress.EgressClients;
import ai.msrv.gaterook.connectors.support.egress.EgressSpec;
import ai.msrv.gaterook.connectors.support.egress.HostResolver;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Streamable HTTP transport against a server that misbehaves on purpose (ARCHITECTURE.md §9 "SSRF", report 11
 * §6): on the guarded client of the core, a message is sent once even when the server asks for it again, and an
 * event stream can't make the core hold more than the message limit in memory.
 */
class GatewayHttpTransportTest {

  private HttpServer server;
  private EgressClients clients;
  private final AtomicInteger posts = new AtomicInteger();
  private final AtomicLong streamed = new AtomicLong();
  private volatile String mode = "retry";

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/mcp", this::answer);
    server.start();
    clients = new EgressClients(new EgressClients.Settings("GateRook/test", Set.of(), HostResolver.SYSTEM));
  }

  @AfterEach
  void stop() {
    server.stop(0);
    clients.close();
  }

  private void answer(HttpExchange exchange) throws IOException {
    exchange.getRequestBody().readAllBytes();
    posts.incrementAndGet();
    if (mode.equals("retry")) {
      // "Unavailable, send it again right away": OkHttp would, unless the body is one-shot.
      exchange.getResponseHeaders().add("Retry-After", "0");
      exchange.sendResponseHeaders(503, -1);
      exchange.close();
      return;
    }
    // An event stream whose data line never ends.
    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
    exchange.sendResponseHeaders(200, 0);
    byte[] chunk = new byte[64 * 1024];
    Arrays.fill(chunk, (byte) 'a');
    try (OutputStream out = exchange.getResponseBody()) {
      out.write("data: ".getBytes(StandardCharsets.US_ASCII));
      for (int i = 0; i < 16 * 1024; i++) {
        out.write(chunk);
        out.flush();
        streamed.addAndGet(chunk.length);
        Thread.sleep(1);
      }
    } catch (IOException | InterruptedException e) {
      // The client let go.
    }
  }

  private GatewayHttpTransport transport(Duration timeout) {
    return transport(timeout, Map.of());
  }

  private GatewayHttpTransport transport(Duration timeout, Map<String, String> headers) {
    OkHttpClient guarded = clients.egress(EgressSpec.of("cn_test", AddressPolicy.builder()
        .allowPrivate("127.0.0.0/8").build())).client();
    URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
    GatewayHttpTransport t = new GatewayHttpTransport(new HttpCalls(url, guarded, headers, 1024 * 1024, timeout,
        "test-server", null, null));
    t.open(new Channel.Sink() {
      @Override
      public void message(ObjectNode message, Long stream) {}

      @Override
      public void closed(String reason) {}
    });
    return t;
  }

  private static ObjectNode call() {
    return Rpc.request(7, "tools/call", Fixtures.object("{\"name\":\"refund\",\"arguments\":{\"amount\":100}}"));
  }

  /** What the exchange of one message failed with. */
  private static Throwable failure(GatewayHttpTransport t) throws Exception {
    try {
      t.send(call(), Map.of()).done().get(20, TimeUnit.SECONDS);
      throw new AssertionError("The exchange didn't fail");
    } catch (ExecutionException e) {
      return e.getCause();
    }
  }

  @Test
  void aCallIsPostedOnceWhenTheServerAsksForItAgainAtOnce() throws Exception {
    GatewayHttpTransport t = transport(Duration.ofSeconds(5));

    assertThat(failure(t)).isInstanceOf(Channel.Failure.class).hasMessageContaining("answered HTTP 503");
    Thread.sleep(200);
    assertThat(posts.get()).as("tools/call posted once").isEqualTo(1);
  }

  /** OkHttp's message for a value it refuses quotes the value of every header but Authorization: never pass it on. */
  @Test
  void aSecretHeaderHttpCantCarryIsNamedNeverQuoted() throws Exception {
    GatewayHttpTransport t = transport(Duration.ofSeconds(5), Map.of("X-Api-Key", "pasted-cnry-31\n"));

    assertThat(failure(t)).isInstanceOf(Channel.Failure.class).hasMessageContaining("X-Api-Key")
        .hasMessageNotContaining("cnry");
    assertThat(posts.get()).isZero();
  }

  @Test
  void anEventLineWithoutEndStopsAtTheMessageLimit() throws Exception {
    mode = "endless";
    GatewayHttpTransport t = transport(Duration.ofSeconds(8));
    long started = System.nanoTime();

    assertThat(failure(t)).isInstanceOf(Channel.Failure.class).hasMessageContaining("sent more than 1 MB");
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
    assertThat(streamed.get()).as("bytes the server got out").isLessThan(64L * 1024 * 1024);
  }
}
