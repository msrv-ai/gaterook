package ai.msrv.gaterook.core.tunnel.internal;

import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stand-in for the ngrok agent (ARCHITECTURE.md §10 K3: "ngrok on a fake agent"): {@code ngrok http
 * 127.0.0.1:<port> --config <run>/ngrok.yml --log stdout --log-format json --inspect=false [--url https://<domain>]}.
 * It reads its local API address from the config the core wrote, lists one https tunnel at {@code GET /api/tunnels}
 * once "connected", logs JSON lines like the real agent and forwards from its edge with ngrok's
 * {@code X-Forwarded-For} ({@link FakeEdge}). Like the real agent it inspects by default: without
 * {@code --inspect=false} it keeps every exchange it carries and lists them at {@code GET /api/requests/http}.
 * Control file as {@link FakeCloudflared}'s; {@code mode} {@code ready}, {@code never-ready}, {@code wrong-domain},
 * {@code exit-at-once}.
 */
public final class FakeNgrok {

  private static final Pattern WEB_ADDR = Pattern.compile("web_addr:\\s*127\\.0\\.0\\.1:(\\d+)");

  private FakeNgrok() {}

  public static void main(String[] args) throws Exception {
    System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
    Properties control = new Properties();
    try (InputStream in = Files.newInputStream(Path.of(args[0]))) {
      control.load(in);
    }
    List<String> argv = List.of(args).subList(1, args.length);
    String mode = control.getProperty("mode", "ready");
    String config = Files.readString(Path.of(FakeCloudflared.value(argv, "--config")));
    Matcher m = WEB_ADDR.matcher(config);
    if (!m.find() || !config.contains("update_check: false") || !argv.get(0).equals("http")) {
      log("crit", "unexpected config or argv");
      System.exit(2);
    }
    if (mode.equals("exit-at-once") || System.getenv("NGROK_AUTHTOKEN") == null) {
      log("eror", "authentication failed: The authtoken you specified is invalid. ERR_NGROK_107");
      System.exit(1);
    }
    String url = FakeCloudflared.value(argv, "--url");
    String host = url != null && !mode.equals("wrong-domain") ? url.substring("https://".length())
        : control.getProperty("hostname", "fake-" + ProcessHandle.current().pid() + ".ngrok-free.app");
    List<String> inspected = new java.util.concurrent.CopyOnWriteArrayList<>();
    boolean inspect = !argv.contains("--inspect=false");
    HttpServer edge = FakeEdge.start("http://" + argv.get(1), host, true, inspect ? inspected::add : null);

    Path report = control.getProperty("report") == null ? null : Path.of(control.getProperty("report"));
    if (report != null) {
      Properties r = new Properties();
      r.setProperty("argv", String.join("\u0001", argv));
      r.setProperty("edgePort", String.valueOf(edge.getAddress().getPort()));
      r.setProperty("hostname", host);
      r.setProperty("pid", String.valueOf(ProcessHandle.current().pid()));
      r.setProperty("authtoken", System.getenv("NGROK_AUTHTOKEN"));
      FakeCloudflared.write(report, r);
    }

    long readyAt = System.currentTimeMillis() + Long.parseLong(control.getProperty("readyDelayMs", "300"));
    HttpServer api = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),
        Integer.parseInt(m.group(1))), 0);
    api.createContext("/api/tunnels", ex -> {
      boolean up = !mode.equals("never-ready") && System.currentTimeMillis() >= readyAt;
      FakeCloudflared.answer(ex, 200, up ? "{\"tunnels\":[{\"name\":\"command_line\",\"public_url\":\"https://"
          + host + "\",\"proto\":\"https\",\"config\":{\"addr\":\"http://" + argv.get(1) + "\"}}],\"uri\":"
          + "\"/api/tunnels\"}" : "{\"tunnels\":[],\"uri\":\"/api/tunnels\"}");
    });
    api.createContext("/api/requests/http", ex -> {
      // The fake runs on the test classes alone: no Jackson, the JSON by hand.
      StringBuilder list = new StringBuilder("{\"requests\":[");
      for (int i = 0; i < inspected.size(); i++) {
        list.append(i == 0 ? "" : ",").append("{\"raw\":\"").append(inspected.get(i).replace("\\", "\\\\")
            .replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")).append("\"}");
      }
      FakeCloudflared.answer(ex, 200, list.append("]}").toString());
    });
    api.start();
    log("info", "starting web service");
    if (mode.equals("never-ready")) {
      log("eror", "failed to reconnect session: dial tcp: lookup connect.ngrok-agent.com: no such host");
    } else {
      log("info", "started tunnel");
    }
    Thread.currentThread().join();
  }

  private static void log(String level, String msg) {
    System.out.println("{\"lvl\":\"" + level + "\",\"msg\":\"" + msg + "\",\"t\":\"" + java.time.Instant.now()
        + "\"}");
    System.out.flush();
  }
}
