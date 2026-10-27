package ai.msrv.gaterook.e2e.tunnel;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * A quick-tunnel {@code cloudflared} for the core process (ARCHITECTURE.md §10 K3): started by the core with the real
 * argv, it answers the metrics the core reads ({@code /quicktunnel}, {@code /ready}) and plays Cloudflare's edge on a
 * loopback port — the tunnel's {@code Host}, {@code CF-Connecting-IP} from the test's {@code X-Fake-Client-IP}
 * (else {@code 160.79.104.10}), what the client sent itself dropped. It reports its argv, environment facts, pid and
 * edge port to a properties file. Argument 1: the hostname; argument 2: the report file; then cloudflared's argv.
 */
public final class FakeCloudflaredAgent {

  private static final Set<String> EDGE = Set.of("host", "cf-connecting-ip", "x-forwarded-for", "x-forwarded-proto",
      "cf-ray", "x-fake-client-ip", "connection", "content-length", "transfer-encoding", "upgrade", "expect");

  private FakeCloudflaredAgent() {}

  public static void main(String[] args) throws Exception {
    System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
    String hostname = args[0];
    Path report = Path.of(args[1]);
    List<String> argv = List.of(args).subList(2, args.length);
    String metrics = argv.get(argv.indexOf("--metrics") + 1);
    String target = argv.get(argv.indexOf("--url") + 1);

    HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
        .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    HttpServer edge = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    edge.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    edge.createContext("/", ex -> forward(ex, client, target, hostname));
    edge.start();

    Properties r = new Properties();
    r.setProperty("argv", String.join("\u0001", argv));
    r.setProperty("edgePort", String.valueOf(edge.getAddress().getPort()));
    r.setProperty("pid", String.valueOf(ProcessHandle.current().pid()));
    r.setProperty("tunnelVars", String.valueOf(System.getenv().keySet().stream()
        .anyMatch(k -> k.toUpperCase(Locale.ROOT).startsWith("TUNNEL_"))));
    Path tmp = report.resolveSibling(report.getFileName() + ".tmp");
    try (OutputStream out = Files.newOutputStream(tmp)) {
      r.store(out, "fake cloudflared");
    }
    Files.move(tmp, report, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

    HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),
        Integer.parseInt(metrics.substring(metrics.lastIndexOf(':') + 1))), 0);
    server.createContext("/quicktunnel", ex -> answer(ex, 200, "{\"hostname\":\"" + hostname + "\"}"));
    server.createContext("/ready", ex -> answer(ex, 200, "{\"status\":200,\"readyConnections\":1}"));
    server.start();
    System.out.println(Instant.now() + " INF |  https://" + hostname + "  |");
    System.out.println(Instant.now() + " INF Registered tunnel connection connIndex=0 location=fra08 protocol=quic");
    System.out.flush();
    Thread.currentThread().join();
  }

  private static void forward(HttpExchange ex, HttpClient client, String target, String hostname)
      throws IOException {
    try (ex) {
      String caller = ex.getRequestHeaders().getFirst("X-Fake-Client-IP");
      caller = caller == null ? "160.79.104.10" : caller;
      URI uri = URI.create(target + ex.getRequestURI().getRawPath()
          + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery()));
      byte[] body;
      try (InputStream in = ex.getRequestBody()) {
        body = in.readAllBytes();
      }
      HttpRequest.Builder rq = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).method(
          ex.getRequestMethod(), body.length == 0 ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofByteArray(body));
      for (Map.Entry<String, List<String>> h : ex.getRequestHeaders().entrySet()) {
        if (!EDGE.contains(h.getKey().toLowerCase(Locale.ROOT))) {
          h.getValue().forEach(v -> rq.header(h.getKey(), v));
        }
      }
      rq.header("Host", hostname).header("CF-Connecting-IP", caller).header("X-Forwarded-For", caller)
          .header("X-Forwarded-Proto", "https").header("Cf-Ray", "8d2c1f0a9b7e3c4d-FRA");
      HttpResponse<byte[]> rs;
      try {
        rs = client.send(rq.build(), HttpResponse.BodyHandlers.ofByteArray());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        answer(ex, 502, "{}");
        return;
      } catch (IOException e) {
        answer(ex, 502, "{\"error\":\"origin unreachable\"}");
        return;
      }
      rs.headers().map().forEach((k, v) -> {
        String l = k.toLowerCase(Locale.ROOT);
        if (!l.equals("content-length") && !l.equals("transfer-encoding") && !l.equals("connection")
            && !l.startsWith(":")) {
          ex.getResponseHeaders().put(k, v);
        }
      });
      byte[] out = rs.body();
      ex.sendResponseHeaders(rs.statusCode(), out.length == 0 ? -1 : out.length);
      if (out.length > 0) {
        ex.getResponseBody().write(out);
      }
    }
  }

  private static void answer(HttpExchange ex, int status, String json) throws IOException {
    try (ex) {
      byte[] b = json.getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().set("Content-Type", "application/json");
      ex.sendResponseHeaders(status, b.length);
      ex.getResponseBody().write(b);
    }
  }
}
