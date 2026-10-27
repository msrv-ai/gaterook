package ai.msrv.gaterook.core.tunnel.internal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * A stand-in for {@code cloudflared} that runs as the core's child process, started through the same argv and
 * environment as the real one (ARCHITECTURE.md §10 K3: FakeCloudflared in the main process). It serves the metrics
 * interface the core reads ({@code /quicktunnel}, {@code /ready}) and plays Cloudflare's edge: an HTTP port on loopback
 * that forwards to {@code --url} as the edge would ({@link FakeEdge}).
 *
 * <p>Its first argument is a control file (properties) of the test: {@code mode} ({@code ready}, {@code never-ready},
 * {@code no-hostname}, {@code no-edge}, {@code exit-after-ready}, {@code exit-at-once}), {@code hostname},
 * {@code target} (a named tunnel's route), {@code exitAfterMs}, {@code report} (a file it writes its argv, the token
 * it got and the edge port to, as properties).
 */
public final class FakeCloudflared {

  private FakeCloudflared() {}

  public static void main(String[] args) throws Exception {
    // The edge forwards with the tunnel's Host: java.net.http allows that header only with this property.
    System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
    Properties control = new Properties();
    try (InputStream in = Files.newInputStream(Path.of(args[0]))) {
      control.load(in);
    }
    List<String> argv = List.of(args).subList(1, args.length);
    String mode = control.getProperty("mode", "ready");
    String metrics = value(argv, "--metrics");
    String url = value(argv, "--url");
    boolean named = argv.contains("run");
    String hostname = control.getProperty("hostname", "fake-" + ProcessHandle.current().pid() + ".trycloudflare.com");
    String target = url != null ? url : control.getProperty("target");
    log("INF Starting tunnel tunnelID=fake version=2026.10.0 (fake)");
    if (mode.equals("exit-at-once")) {
      log("ERR Couldn't start tunnel error=\"Unauthorized: Invalid tunnel secret\"");
      System.exit(1);
    }

    HttpServer edge = FakeEdge.start(target, hostname, false);

    Path report = control.getProperty("report") == null ? null : Path.of(control.getProperty("report"));
    if (report != null) {
      Properties r = new Properties();
      r.setProperty("argv", String.join("\u0001", argv));
      r.setProperty("edgePort", String.valueOf(edge.getAddress().getPort()));
      r.setProperty("hostname", hostname);
      r.setProperty("pid", String.valueOf(ProcessHandle.current().pid()));
      String token = System.getenv("TUNNEL_TOKEN");
      r.setProperty("tunnelToken", token == null ? "" : token);
      r.setProperty("otherTunnelVars", String.valueOf(System.getenv().keySet().stream()
          .anyMatch(k -> k.toUpperCase(Locale.ROOT).startsWith("TUNNEL_") && !k.equals("TUNNEL_TOKEN"))));
      write(report, r);
    }

    long readyAt = System.currentTimeMillis() + Long.parseLong(control.getProperty("readyDelayMs", "300"));
    int port = Integer.parseInt(metrics.substring(metrics.lastIndexOf(':') + 1));
    HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
    server.createContext("/quicktunnel", ex -> {
      String host = named || mode.equals("no-hostname") ? "" : hostname;
      answer(ex, 200, "{\"hostname\":\"" + host + "\"}");
    });
    server.createContext("/ready", ex -> {
      boolean ready = !mode.equals("never-ready") && !mode.equals("no-edge")
          && System.currentTimeMillis() >= readyAt;
      answer(ex, ready ? 200 : 503, "{\"status\":" + (ready ? 200 : 503) + ",\"readyConnections\":"
          + (ready ? 1 : 0) + "}");
    });
    server.start();
    if (!named) {
      log("INF Requesting new quick Tunnel on trycloudflare.com...");
      log("INF |  https://" + hostname + "  |");
    }
    if (mode.equals("never-ready")) {
      log("ERR Failed to dial a quic connection error=\"failed to dial to edge with quic: timeout: no recent "
          + "network activity\" connIndex=0 event=0 ip=198.41.192.7");
    } else {
      log("INF Registered tunnel connection connIndex=0 location=fra08 protocol=quic");
    }
    if (mode.equals("no-edge")) {
      // What the real agent does where argotunnel.com's SRV records don't resolve: it gives up by itself.
      Thread.sleep(800);
      log("ERR edge discovery: error looking up Cloudflare edge IPs: the DNS query failed error=\"lookup "
          + "_v2-origintunneld._tcp.argotunnel.com on 8.8.8.8:53: no such host\" event=0");
      System.exit(1);
    }
    if (mode.equals("exit-after-ready")) {
      Thread.sleep(Long.parseLong(control.getProperty("exitAfterMs", "1500")));
      log("ERR Connection terminated error=\"connection with edge closed\" connIndex=0");
      System.exit(3);
    }
    Thread.currentThread().join();
  }

  /** Written whole or not at all: the test polls for the file. */
  static void write(Path report, Properties r) throws IOException {
    Path tmp = report.resolveSibling(report.getFileName() + ".tmp");
    try (OutputStream out = Files.newOutputStream(tmp)) {
      r.store(out, "fake tunnel agent");
    }
    Files.move(tmp, report, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
  }

  static void answer(HttpExchange ex, int status, String json) throws IOException {
    try (ex) {
      byte[] b = json.getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().set("Content-Type", "application/json");
      ex.sendResponseHeaders(status, b.length);
      ex.getResponseBody().write(b);
    }
  }

  static String value(List<String> argv, String flag) {
    int i = argv.indexOf(flag);
    return i >= 0 && i + 1 < argv.size() ? argv.get(i + 1) : null;
  }

  static void log(String line) {
    System.out.println(Instant.now() + " " + line);
    System.out.flush();
  }
}
