package ai.msrv.gaterook.core.tunnel.internal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The provider's edge as the fake agents play it: a loopback HTTP port that forwards to the agent's target the way
 * Cloudflare or ngrok would — with the tunnel's {@code Host} and the caller's address in the provider's header. The
 * caller is the test's {@code X-Fake-Client-IP} (else {@link #DEFAULT_CLIENT_IP}). Cloudflare overwrites
 * {@code CF-Connecting-IP} and {@code X-Forwarded-For}; ngrok appends the caller to whatever {@code X-Forwarded-For}
 * the client sent.
 */
final class FakeEdge {

  /** The edge's caller when the test names none: inside Anthropic's range. */
  static final String DEFAULT_CLIENT_IP = "160.79.104.10";

  /** Headers the edge decides itself, or java.net.http does. */
  private static final Set<String> EDGE_HEADERS = Set.of("host", "cf-connecting-ip", "x-forwarded-proto", "cf-ray",
      "x-fake-client-ip", "connection", "content-length", "transfer-encoding", "upgrade", "expect");

  private FakeEdge() {}

  static HttpServer start(String target, String hostname, boolean ngrok) throws IOException {
    return start(target, hostname, ngrok, null);
  }

  /**
   * @param inspector receives each exchange as the agent would keep it for inspection (request line, headers, the
   *     answer's body), or {@code null} when the agent inspects nothing
   */
  static HttpServer start(String target, String hostname, boolean ngrok, Consumer<String> inspector)
      throws IOException {
    HttpServer edge = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    edge.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
        .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    edge.createContext("/", ex -> forward(ex, client, target, hostname, ngrok, inspector));
    edge.start();
    return edge;
  }

  private static void forward(HttpExchange ex, HttpClient client, String target, String hostname,
      boolean ngrok, Consumer<String> inspector) throws IOException {
    try (ex) {
      String caller = ex.getRequestHeaders().getFirst("X-Fake-Client-IP");
      if (caller == null) {
        caller = DEFAULT_CLIENT_IP;
      }
      URI uri = URI.create(target + ex.getRequestURI().getRawPath()
          + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery()));
      byte[] body = ex.getRequestBody().readAllBytes();
      HttpRequest.Builder rq = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
          .method(ex.getRequestMethod(), body.length == 0 ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofByteArray(body));
      String clientForwarded = ex.getRequestHeaders().getFirst("X-Forwarded-For");
      for (Map.Entry<String, List<String>> h : ex.getRequestHeaders().entrySet()) {
        String k = h.getKey().toLowerCase(Locale.ROOT);
        if (EDGE_HEADERS.contains(k) || k.equals("x-forwarded-for")) {
          continue;
        }
        for (String v : h.getValue()) {
          rq.header(h.getKey(), v);
        }
      }
      rq.header("Host", hostname);
      rq.header("X-Forwarded-Proto", "https");
      if (ngrok) {
        rq.header("X-Forwarded-For", clientForwarded == null ? caller : clientForwarded + ", " + caller);
      } else {
        rq.header("CF-Connecting-IP", caller);
        rq.header("X-Forwarded-For", caller);
        rq.header("Cf-Ray", "8d2c1f0a9b7e3c4d-FRA");
      }
      if (inspector != null) {
        // What ngrok's inspection keeps of the request: its line, every header, the body.
        inspector.accept(ex.getRequestMethod() + " " + ex.getRequestURI() + "\n" + ex.getRequestHeaders() + "\n"
            + new String(body, java.nio.charset.StandardCharsets.UTF_8));
      }
      HttpResponse<byte[]> rs;
      try {
        rs = client.send(rq.build(), HttpResponse.BodyHandlers.ofByteArray());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        FakeCloudflared.answer(ex, 502, "{}");
        return;
      } catch (IOException e) {
        // What Cloudflare shows when the origin doesn't answer.
        FakeCloudflared.answer(ex, 502, "{\"error\":\"origin unreachable\"}");
        return;
      }
      for (Map.Entry<String, List<String>> h : rs.headers().map().entrySet()) {
        String k = h.getKey().toLowerCase(Locale.ROOT);
        if (k.equals("content-length") || k.equals("transfer-encoding") || k.equals("connection")
            || k.startsWith(":")) {
          continue;
        }
        ex.getResponseHeaders().put(h.getKey(), new ArrayList<>(h.getValue()));
      }
      if (!ngrok) {
        ex.getResponseHeaders().set("Cf-Ray", "8d2c1f0a9b7e3c4d-FRA");
        ex.getResponseHeaders().set("Server", "cloudflare");
      }
      byte[] out = rs.body();
      if (inspector != null) {
        // ... and of the answer (a token endpoint's tokens among them).
        inspector.accept(rs.statusCode() + " " + new String(out, java.nio.charset.StandardCharsets.UTF_8));
      }
      ex.sendResponseHeaders(rs.statusCode(), out.length == 0 ? -1 : out.length);
      if (out.length > 0) {
        ex.getResponseBody().write(out);
      }
    }
  }

}
