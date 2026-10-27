package ai.msrv.gaterook.core.clients.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import ai.msrv.gaterook.core.clients.internal.ClientEnvironment.Os;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Check's self-test of a bridge (report 03 §5, review K1-1 and K1-2): whatever the bridge does, Check ends — a bridge
 * that never answers is a failed step, not a request that hangs — and the helper's token goes to this core's own
 * listener only.
 */
class BridgeSelfTestTest {

  @TempDir
  Path dir;

  private ClientEnvironment env() {
    return new ClientEnvironment(Os.current(), dir, dir.resolve(".gaterook"), null, Map.of(), true, "laptop");
  }

  @Test
  @Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void aBridgeThatNeverAnswersEndsTheCheckInsteadOfHangingIt() {
    long start = System.nanoTime();
    // Reads stdin and says nothing: stdin stays open while Check waits.
    BridgeSelfTest.Outcome o = BridgeSelfTest.stdio(env(), List.of("/bin/sh", "-c", "cat > /dev/null"),
        Duration.ofSeconds(1));
    assertThat(o.ok()).isFalse();
    assertThat(o.text()).isEqualTo("The bridge didn't answer within 1 s.");
    assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start)).isLessThan(10);
  }

  @Test
  @Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void aBridgeThatEndsAtOnceSaysSoWithItsExitCode() {
    BridgeSelfTest.Outcome o = BridgeSelfTest.stdio(env(), List.of("/bin/sh", "-c", "exit 3"), Duration.ofSeconds(5));
    assertThat(o.ok()).isFalse();
    assertThat(o.text()).isEqualTo("The bridge ended without an answer (exit code 3).");
  }

  @Test
  void theHelpersTokenGoesToThisGatewayOnly() throws IOException {
    AtomicInteger asked = new AtomicInteger();
    HttpServer elsewhere = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    elsewhere.createContext("/", exchange -> {
      asked.incrementAndGet();
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    elsewhere.start();
    try {
      int port = elsewhere.getAddress().getPort();
      for (String url : List.of("http://localhost:" + port + "/mcp/dev", "https://127.0.0.1:" + port + "/mcp/dev",
          "http://127.0.0.1:" + port + "/elsewhere", "http://user@127.0.0.1:" + port + "/mcp/dev",
          "http://evil.example/mcp/dev", "not a url")) {
        BridgeSelfTest.Outcome o = BridgeSelfTest.listTools(url, "Bearer gwb_x", System.nanoTime(), "Helper");
        assertThat(o.ok()).as(url).isFalse();
        assertThat(o.text()).as(url).contains("isn't this gateway's endpoint");
      }
      assertThat(asked.get()).as("no request, no token").isZero();
    } finally {
      elsewhere.stop(0);
    }
  }
}
