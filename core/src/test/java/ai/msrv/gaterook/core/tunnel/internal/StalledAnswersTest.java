package ai.msrv.gaterook.core.tunnel.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpServer;
import ai.msrv.gaterook.core.platform.TestGateway;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Answers that stall after their headers (ARCHITECTURE.md §7.3): {@code java.net.http}'s request timeout ends with the
 * headers, so every exchange of the tunnel — the agent's local API, the self-test's public URL, the download of a
 * pinned agent — has a deadline of its own for the whole answer, and a stalled body never holds a start, a "Check
 * public URL" or ngrok's "Downloading…" forever.
 */
class StalledAnswersTest {

  private HttpServer server;
  private String base;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    // Headers and one byte of a promised hundred, then nothing for a minute.
    server.createContext("/", ex -> {
      ex.sendResponseHeaders(200, 100);
      OutputStream out = ex.getResponseBody();
      out.write('{');
      out.flush();
      try {
        Thread.sleep(60_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      ex.close();
    });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void theAgentsLocalApiIsAskedAgainRatherThanWaitedForForever() {
    assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThat(TunnelAgent.localGet(
        server.getAddress().getPort(), "/quicktunnel", Duration.ofMillis(500))).isEmpty());
  }

  @Test
  void theSelfTestReportsAPublicUrlThatStalls() {
    TunnelProbe.Http probe = new TunnelProbe.Http(Duration.ofMillis(500));
    assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThatThrownBy(() -> probe.send("GET",
        URI.create(base + "/.well-known/oauth-authorization-server"), Map.of(), null))
        .isInstanceOf(IOException.class).hasMessageContaining("didn't answer within 500 ms"));
  }

  @Test
  void aDownloadThatStallsEndsAtItsDeadlineAndCanBeTriedAgain() throws IOException {
    Path home = TestGateway.newHome("stalled");
    Path pins = home.resolve("pins.json");
    String platform = TunnelBinaries.platform(System.getProperty("os.name"), System.getProperty("os.arch"));
    Files.writeString(pins, "{\"ngrok\":{\"version\":\"3.39.11\",\"platforms\":{\"" + platform + "\":{\"url\":\""
        + base + "/ngrok.zip\",\"sha256\":\"" + "0".repeat(64) + "\",\"archive\":\"zip\",\"binary\":\"ngrok\","
        + "\"binarySha256\":\"" + "0".repeat(64) + "\"}}}}", StandardCharsets.UTF_8);
    TunnelBinaries binaries = new TunnelBinaries(home, "", pins.toString(), Duration.ofMillis(800));
    assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThatThrownBy(binaries::installNgrok)
        .isInstanceOf(TunnelException.class).hasMessageContaining("didn't finish within"));
    assertThat(binaries.ngrokStatus().error()).contains("didn't finish within");
  }
}
