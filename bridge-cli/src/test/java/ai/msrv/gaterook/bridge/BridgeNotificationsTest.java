package ai.msrv.gaterook.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class BridgeNotificationsTest {

  private static final String NOTIFICATION = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}";

  @TempDir
  Path home;

  @Test
  @Timeout(20)
  void streamsNotificationsWithAuthenticationAndReconnectsUntilClosed() throws Exception {
    AtomicInteger tokens = new AtomicInteger();
    BlockingQueue<HttpExchange> streams = new LinkedBlockingQueue<>();
    BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    http.createContext("/mcp/dev", ex -> {
      ex.getResponseHeaders().add("X-Gateway-Bridge-Instance", "inst-1");
      if ("GET".equals(ex.getRequestMethod())) {
        if ("Bearer gwb_t1".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
          ex.sendResponseHeaders(401, -1);
          ex.close();
          return;
        }
        if ("Bearer gwb_t2".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
          ex.getResponseHeaders().set("X-Gateway-Bridge-Instance", "other");
          ex.getResponseHeaders().add("Content-Type", "text/event-stream");
          byte[] untrusted = "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/untrusted\"}\n\n"
              .getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(200, untrusted.length);
          ex.getResponseBody().write(untrusted);
          ex.close();
          return;
        }
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(200, 0);
        ex.getResponseBody().write((": connected\r\n\r\ndata: " + NOTIFICATION + "\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
        ex.getResponseBody().flush();
        streams.add(ex);
        return;
      }
      Object request = Json.parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      if ("initialize".equals(Json.string(request, "method"))) {
        byte[] answer = ("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-11-25\","
            + "\"capabilities\":{\"tools\":{\"listChanged\":true}}}}").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, answer.length);
        ex.getResponseBody().write(answer);
      } else {
        ex.sendResponseHeaders(202, -1);
      }
      ex.close();
    });
    http.start();
    int port = http.getAddress().getPort();
    OutputStream output = new OutputStream() {
      private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

      @Override
      public void write(int b) {
        if (b == '\n') {
          lines.add(pending.toString(StandardCharsets.UTF_8));
          pending.reset();
        } else {
          pending.write(b);
        }
      }
    };
    try (FakeBroker broker = new FakeBroker(home, r -> "{\"v\":1,\"ok\":true,\"token\":\"gwb_t"
        + tokens.incrementAndGet() + "\",\"expiresAt\":\"" + Instant.now().plusSeconds(900)
        + "\",\"url\":\"http://127.0.0.1:" + port + "/mcp/dev\",\"instance\":\"inst-1\"}");
        StdioBridge bridge = new StdioBridge("dev", "gr_abcdefghijkl", "cursor",
            BrokerClient.fromEnvironment(Map.of("GW_HOME", home.toString())),
            new PrintStream(output, true, StandardCharsets.UTF_8))) {
      bridge.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
      assertThat(lines.poll(5, TimeUnit.SECONDS)).contains("listChanged");
      assertThat(streams).isEmpty();
      bridge.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
      HttpExchange first = streams.poll(5, TimeUnit.SECONDS);
      assertThat(first).isNotNull();
      assertThat(first.getRequestHeaders().getFirst("Accept")).isEqualTo("text/event-stream");
      assertThat(first.getRequestHeaders().getFirst("MCP-Protocol-Version")).isEqualTo("2025-11-25");
      assertThat(tokens.get()).as("refresh after 401 and reject an unrelated core instance").isEqualTo(4);
      assertThat(Json.parse(lines.poll(5, TimeUnit.SECONDS))).as("forward the server's initial refresh")
          .isEqualTo(Json.parse(NOTIFICATION));
      first.getResponseBody().write(("data: invalid\n\ndata: {\"jsonrpc\":\"2.0\",\"id\":99,\"result\":{}}\n\n"
          + "event: message\r\ndata: {\"jsonrpc\":\"2.0\",\r\ndata: \"method\":\"notifications/")
          .getBytes(StandardCharsets.UTF_8));
      first.getResponseBody().flush();
      first.getResponseBody().write("tools/list_changed\"}\r\n\r\n".getBytes(StandardCharsets.UTF_8));
      first.getResponseBody().flush();
      assertThat(Json.parse(lines.poll(5, TimeUnit.SECONDS))).isEqualTo(Json.parse(NOTIFICATION));
      first.close();
      HttpExchange second = streams.poll(5, TimeUnit.SECONDS);
      assertThat(second).isNotNull();
      assertThat(Json.parse(lines.poll(5, TimeUnit.SECONDS))).as("forward the server refresh after reconnecting")
          .isEqualTo(Json.parse(NOTIFICATION));
      second.getResponseBody().write(("data: " + NOTIFICATION + "\n\n").getBytes(StandardCharsets.UTF_8));
      second.getResponseBody().flush();
      assertThat(Json.parse(lines.poll(5, TimeUnit.SECONDS))).isEqualTo(Json.parse(NOTIFICATION));
      bridge.close();
      second.close();
      assertThat(streams.poll(1500, TimeUnit.MILLISECONDS)).as("closed bridges do not reconnect").isNull();
      assertThat(lines).isEmpty();
    } finally {
      http.stop(0);
    }
  }
}
