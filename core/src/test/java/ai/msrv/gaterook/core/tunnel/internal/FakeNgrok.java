package ai.msrv.gaterook.core.tunnel.internal;

import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stand-in for the ngrok agent (ARCHITECTURE.md §10 K3: "ngrok on a fake agent"): {@code ngrok http
 * 127.0.0.1:<port> --config <run>/ngrok.yml --log stdout --log-format json --inspect=false [--url https://<domain>]}.
 * It logs JSON lines on stdout like the real agent, {@code "started tunnel"} with its {@code url} once "connected",
 * and forwards from its edge with ngrok's {@code X-Forwarded-For} ({@link FakeEdge}). It refuses to start with a
 * local API: the real agent's has no authentication, so the config must say {@code web_addr: false}.
 * Control file as {@link FakeCloudflared}'s; {@code mode} {@code ready}, {@code never-ready}, {@code wrong-domain},
 * {@code exit-at-once}; {@code readyDelayMs} until it is connected (300), {@code splitLineMs} between the two halves of
 * its "started tunnel" line (0: written at once).
 */
public final class FakeNgrok {

  private static final Pattern WEB_ADDR = Pattern.compile("(?m)^\\s*web_addr:\\s*(\\S+)\\s*$");

  private FakeNgrok() {}

  public static void main(String[] args) throws Exception {
    System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
    Properties control = new Properties();
    try (InputStream in = Files.newInputStream(Path.of(args[0]))) {
      control.load(in);
    }
    List<String> argv = List.of(args).subList(1, args.length);
    String mode = control.getProperty("mode", "ready");
    String configFile = FakeCloudflared.value(argv, "--config");
    String config = Files.readString(Path.of(configFile));
    Matcher web = WEB_ADDR.matcher(config);
    if (!web.find() || !web.group(1).equals("false") || !config.contains("update_check: false")
        || !argv.get(0).equals("http")) {
      log("crit", "unexpected config or argv: without web_addr: false the agent serves its API to this computer");
      System.exit(2);
    }
    log("info", "open config file", "path", configFile, "err", null);
    if (mode.equals("exit-at-once") || System.getenv("NGROK_AUTHTOKEN") == null) {
      // As 3.39.11 refuses an authtoken: JSON entries, the error again on stderr a piece per line, a last entry.
      String refused = "authentication failed: The authtoken you specified is properly formed, but it is invalid.\n"
          + "Go to your ngrok dashboard and double check that your authtoken is correct:\n"
          + "https://dashboard.ngrok.com/get-started/your-authtoken\r\n\r\nERR_NGROK_107\r\n";
      log("eror", "failed to reconnect session", "obj", "tunnels.session", "err", refused);
      log("crit", "command failed", "err", refused);
      for (String piece : (refused + "https://ngrok.com/docs/errors/err_ngrok_107\n").split("\r?\n", -1)) {
        System.err.println("ERROR:  " + piece);
      }
      System.err.flush();
      log("info", "no more state changes", "obj", "tunnels.session");
      System.exit(1);
    }
    String url = FakeCloudflared.value(argv, "--url");
    String host = url != null && !mode.equals("wrong-domain") ? url.substring("https://".length())
        : control.getProperty("hostname", "fake-" + ProcessHandle.current().pid() + ".ngrok-free.app");
    HttpServer edge = FakeEdge.start("http://" + argv.get(1), host, true);

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

    Thread.sleep(Long.parseLong(control.getProperty("readyDelayMs", "300")));
    if (mode.equals("never-ready")) {
      while (true) {
        log("eror", "failed to reconnect session", "obj", "tunnels.session", "err", "failed to dial ngrok server "
            + "with address \"connect.ngrok-agent.com:443\": dial tcp: lookup connect.ngrok-agent.com: no such host");
        Thread.sleep(1000);
      }
    }
    log("info", "client session established", "obj", "tunnels.session");
    String started = line("info", "started tunnel", "obj", "tunnels", "name", "command_line", "addr", "http://"
        + argv.get(1), "url", "https://" + host);
    long split = Long.parseLong(control.getProperty("splitLineMs", "0"));
    if (split > 0) {
      // The way a line can reach the log: in two writes, the end of it later.
      System.out.print(started.substring(0, started.length() / 2));
      System.out.flush();
      Thread.sleep(split);
      started = started.substring(started.length() / 2);
    }
    System.out.println(started);
    System.out.flush();
    Thread.currentThread().join();
  }

  private static void log(String level, String msg, String... fields) {
    System.out.println(line(level, msg, fields));
    System.out.flush();
  }

  /**
   * A line of ngrok's JSON log as 3.39.11 writes it: {@code t} (local time to the second, offset without a colon),
   * {@code lvl}, {@code msg}, then the fields in the order given; a {@code null} value is JSON's.
   */
  private static String line(String level, String msg, String... fields) {
    Map<String, String> entry = new LinkedHashMap<>();
    entry.put("t", OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")));
    entry.put("lvl", level);
    entry.put("msg", msg);
    for (int i = 0; i + 1 < fields.length; i += 2) {
      entry.put(fields[i], fields[i + 1]);
    }
    // The fake runs on the test classes alone: no Jackson, the JSON by hand.
    StringBuilder out = new StringBuilder("{");
    entry.forEach((k, v) -> out.append(out.length() == 1 ? "" : ",").append('"').append(k).append("\":")
        .append(v == null ? "null" : '"' + escaped(v) + '"'));
    return out.append('}').toString();
  }

  private static String escaped(String v) {
    StringBuilder out = new StringBuilder();
    for (char c : v.toCharArray()) {
      switch (c) {
        case '\\' -> out.append("\\\\");
        case '"' -> out.append("\\\"");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> out.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
      }
    }
    return out.toString();
  }
}
