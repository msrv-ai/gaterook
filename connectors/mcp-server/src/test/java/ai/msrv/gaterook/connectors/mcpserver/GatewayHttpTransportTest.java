package ai.msrv.gaterook.connectors.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ai.msrv.gaterook.connectors.support.egress.AddressPolicy;
import ai.msrv.gaterook.connectors.support.egress.EgressClients;
import ai.msrv.gaterook.connectors.support.egress.EgressSpec;
import ai.msrv.gaterook.connectors.support.egress.HostResolver;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
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
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

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
    OkHttpClient guarded = clients.egress(EgressSpec.of("cn_test", AddressPolicy.builder()
        .allowPrivate("127.0.0.0/8").build())).client();
    URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
    GatewayHttpTransport t = new GatewayHttpTransport(url, guarded, Map.of(), Session.MAPPER, 1024 * 1024, timeout,
        "test-server");
    t.connect(m -> m.then(Mono.empty())).block();
    return t;
  }

  private static McpSchema.JSONRPCRequest call() {
    return new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, McpSchema.METHOD_TOOLS_CALL, 7,
        Map.of("name", "refund", "arguments", Map.of("amount", 100)));
  }

  @Test
  void aCallIsPostedOnceWhenTheServerAsksForItAgainAtOnce() throws Exception {
    GatewayHttpTransport t = transport(Duration.ofSeconds(5));

    assertThatThrownBy(() -> t.sendMessage(call()).block(Duration.ofSeconds(10)))
        .isInstanceOf(McpTransportException.class).hasMessageContaining("answered HTTP 503");
    Thread.sleep(200);
    assertThat(posts.get()).as("tools/call posted once").isEqualTo(1);
  }

  /** OkHttp's message for a value it refuses quotes the value of every header but Authorization: never pass it on. */
  @Test
  void aSecretHeaderHttpCantCarryIsNamedNeverQuoted() {
    OkHttpClient guarded = clients.egress(EgressSpec.of("cn_test", AddressPolicy.builder()
        .allowPrivate("127.0.0.0/8").build())).client();
    GatewayHttpTransport t = new GatewayHttpTransport(URI.create("http://127.0.0.1:" + server.getAddress().getPort()
        + "/mcp"), guarded, Map.of("X-Api-Key", "pasted-cnry-31\n"), Session.MAPPER, 1024 * 1024,
        Duration.ofSeconds(5), "test-server");
    t.connect(m -> m.then(Mono.empty())).block();

    assertThatThrownBy(() -> t.sendMessage(call()).block(Duration.ofSeconds(10)))
        .isInstanceOf(McpTransportException.class).hasMessageContaining("X-Api-Key")
        .hasMessageNotContaining("cnry");
    assertThat(posts.get()).isZero();
  }

  @Test
  void anEventLineWithoutEndStopsAtTheMessageLimit() {
    mode = "endless";
    GatewayHttpTransport t = transport(Duration.ofSeconds(8));
    long started = System.nanoTime();

    assertThatThrownBy(() -> t.sendMessage(call()).block(Duration.ofSeconds(20)))
        .isInstanceOf(McpTransportException.class).hasMessageContaining("sent more than 1 MB");
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
    assertThat(streamed.get()).as("bytes the server got out").isLessThan(64L * 1024 * 1024);
  }
}
