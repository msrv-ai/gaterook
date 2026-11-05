package ai.msrv.gaterook.core.tunnel.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.platform.TestGateway;
import ai.msrv.gaterook.core.tunnel.TunnelProvider;
import ai.msrv.gaterook.core.tunnel.TunnelPublication;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The real ngrok agent 3.39.11 (profile {@code network}): the pinned release downloaded and hash-checked into
 * {@code ~/.cache/mcp-gateway-test/k3-ngrok} as the app installs it, started with our config and argv. It must take
 * {@code web_addr: false} and serve no local API: no {@code "starting web service"} in its log, no listening socket
 * while it runs (checked with {@code lsof}, {@code netstat} on Windows). With an authtoken in
 * {@code GW_TEST_NGROK_AUTHTOKEN} — and the account's reserved domain in {@code GW_TEST_NGROK_DOMAIN}, if it is to be
 * used — its {@code "started tunnel"} line must bring the public URL, which then reaches a server of this test on
 * the loopback port. Without one, ngrok refuses a made-up authtoken (ERR_NGROK_107) and the start fails.
 */
@Tag("network")
class RealNgrokIT {

  private static final String MADE_UP = "2fakeNgrokAuthtoken_0123456789abcdefghij";

  @Test
  void theRealAgentTellsItsUrlInItsLogAndListensOnNothing() throws Exception {
    Path cache = Path.of(System.getProperty("user.home"), ".cache", "mcp-gateway-test", "k3-ngrok");
    TunnelBinaries binaries = new TunnelBinaries(cache, "", "");
    Path binary = binaries.installNgrok();
    assertThat(binaries.ngrokStatus().sha256Ok()).isTrue();
    String token = System.getenv("GW_TEST_NGROK_AUTHTOKEN");
    boolean live = token != null && !token.isBlank();
    String domain = live ? System.getenv("GW_TEST_NGROK_DOMAIN") : null;

    Path home = TestGateway.newHome("real-ngrok");
    Path run = Files.createDirectories(home.resolve("run"));
    Path logs = Files.createDirectories(home.resolve("logs"));
    String nonce = UUID.randomUUID().toString();
    HttpServer origin = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    origin.createContext("/" + nonce, ex -> {
      try (ex) {
        byte[] body = nonce.getBytes(StandardCharsets.US_ASCII);
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
      }
    });
    origin.start();
    String config = run.resolve("ngrok.yml").toString();
    Set<String> listening = ConcurrentHashMap.newKeySet();
    Thread watcher = Thread.ofVirtual().start(() -> watchSockets(config, listening));
    TunnelAgent.Started started = null;
    String failure = null;
    try {
      try {
        started = new NgrokAgent().start(new TunnelAgent.StartRequest(TunnelProvider.NGROK, binary,
            origin.getAddress().getPort(), domain, (live ? token : MADE_UP).toCharArray(), run, logs));
      } catch (TunnelException e) {
        failure = e.getMessage();
      }
      if (started != null) {
        assertThat(TunnelPublication.validHost(started.host())).isTrue();
        if (domain != null && !domain.isBlank()) {
          assertThat(started.host()).isEqualTo(domain.toLowerCase(java.util.Locale.ROOT));
        }
        assertThat(throughTheEdge(started.host(), nonce)).as("the public URL reaches this computer").isEqualTo(nonce);
        listening.addAll(listeners(started.process().pid()));
      }
    } finally {
      watcher.interrupt();
      watcher.join();
      if (started != null) {
        started.process().stop();
      }
      origin.stop(0);
    }

    String log = Files.readString(logs.resolve("ngrok.log"));
    assertThat(log).contains("\"msg\":\"open config file\"").doesNotContain("starting web service");
    assertThat(listening).as("sockets the agent listened on").isEmpty();
    if (live) {
      assertThat(failure).as("the start").isNull();
      assertThat(log).contains("\"msg\":\"started tunnel\"");
      assertThat(log.contains(token)).as("the log holds the authtoken").isFalse();
    } else {
      assertThat(failure).contains("ngrok stopped (exit 1)");
      assertThat(log).contains("ERR_NGROK_107");
    }
    assertThat(run.resolve("tunnel.pid")).doesNotExist();
    assertThat(ProcessHandle.allProcesses().filter(h -> h.info().arguments().map(a -> Arrays.asList(a)
        .contains(config)).orElse(false))).as("no agent of this test left").isEmpty();
  }

  /** Until interrupted: the listening sockets of every process started with our config. */
  private static void watchSockets(String config, Set<String> into) {
    while (!Thread.currentThread().isInterrupted()) {
      ProcessHandle.allProcesses().filter(h -> h.info().arguments().map(a -> Arrays.asList(a).contains(config))
          .orElse(false)).forEach(h -> into.addAll(listeners(h.pid())));
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        return;
      }
    }
  }

  /** The TCP sockets {@code pid} listens on, as the OS lists them. */
  private static List<String> listeners(long pid) {
    boolean windows = System.getProperty("os.name").startsWith("Windows");
    List<String> command = windows ? List.of("netstat", "-ano", "-p", "TCP")
        : List.of("lsof", "-nP", "-a", "-p", Long.toString(pid), "-iTCP", "-sTCP:LISTEN");
    List<String> found = new ArrayList<>();
    try {
      Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      p.waitFor();
      for (String line : out.split("\r?\n")) {
        boolean ours = windows ? line.contains("LISTENING") && line.strip().endsWith(" " + pid)
            : line.contains("(LISTEN)");
        if (ours) {
          found.add(line.strip().replaceAll("\\s+", " "));
        }
      }
    } catch (IOException e) {
      throw new AssertionError(command.getFirst() + " is needed to list the agent's sockets: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return found;
  }

  /** What {@code https://<host>/<nonce>} answers through ngrok's edge, tried for up to 20 s. */
  private static String throughTheEdge(String host, String nonce) throws InterruptedException {
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    HttpRequest rq = HttpRequest.newBuilder(URI.create("https://" + host + "/" + nonce))
        .header("ngrok-skip-browser-warning", "1").timeout(Duration.ofSeconds(10)).GET().build();
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    String last = null;
    while (System.nanoTime() < deadline) {
      try {
        HttpResponse<String> rs = http.send(rq, HttpResponse.BodyHandlers.ofString());
        last = rs.statusCode() + " " + rs.body();
        if (rs.statusCode() == 200) {
          return rs.body();
        }
      } catch (IOException e) {
        last = e.toString();
      }
      Thread.sleep(1000);
    }
    return last;
  }
}
