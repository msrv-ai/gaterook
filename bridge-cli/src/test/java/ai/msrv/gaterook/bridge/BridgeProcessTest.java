package ai.msrv.gaterook.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code gw-bridge} in a process of its own, as Codex runs its {@code http_headers_helper} and Cursor its stdio entry:
 * the exit codes, stdout and the log the clients see. With {@code -Dgateway.bridge.binary} these run the native
 * executable, so the release binary is checked by the same assertions as the jar.
 */
class BridgeProcessTest {

  private static final String GRANT = "gr_abcdefghijkl";
  private static final Duration WAIT = Duration.ofSeconds(30);

  @TempDir
  Path home;

  private BridgeProcess.Result run(String stdin, String... args) throws IOException, InterruptedException {
    return BridgeProcess.run(List.of(args), Map.of("GW_HOME", home.toString()), stdin, WAIT);
  }

  private static String issued(int port, String token) {
    return "{\"v\":1,\"ok\":true,\"token\":\"" + token + "\",\"expiresAt\":\"" + Instant.now().plusSeconds(900)
        + "\",\"url\":\"http://127.0.0.1:" + port + "/mcp/dev\",\"instance\":\"inst-1\"}";
  }

  @Test
  void versionAndUsage() throws Exception {
    BridgeProcess.Result v = run("", "--version");
    assertThat(v.code()).as(v.err()).isZero();
    assertThat(v.out().strip()).isEqualTo(Main.VERSION);
    assertThat(run("", "steal").code()).isEqualTo(3);
  }

  @Test
  void headersPrintsTheHeaderCodexWantsAndNothingElse() throws Exception {
    try (FakeBroker broker = new FakeBroker(home, r -> issued(7465, "gwb_0123456789abcdef"))) {
      BridgeProcess.Result r = run("", "headers", "--endpoint", "dev", "--grant", GRANT);
      assertThat(r.code()).as(r.err()).isZero();
      assertThat(r.out()).isEqualTo("{\"Authorization\":\"Bearer gwb_0123456789abcdef\"}");
      Object request = broker.requests.getFirst();
      assertThat(Json.string(request, "client")).isEqualTo("codex");
      assertThat(Json.string(request, "grant")).isEqualTo(GRANT);
      assertThat(Json.string(request, "bridge")).isEqualTo(Main.VERSION);
      assertThat(Json.get(request, "pid")).as("the helper's own pid").isInstanceOf(Long.class);
    }
  }

  @Test
  void headersFailsWithTheDocumentedCodesAndLogsWhyWithoutTheGrant() throws Exception {
    assertThat(run("", "headers", "--endpoint", "dev", "--grant", GRANT).code()).as("no broker").isEqualTo(1);
    assertThat(run("", "headers", "--endpoint", "dev").code()).as("no grant").isEqualTo(3);
    try (FakeBroker broker = new FakeBroker(home, r -> "{\"v\":1,\"ok\":false,\"error\":\"grant_revoked\","
        + "\"message\":\"Access for Codex to dev was revoked in GateRook. Connect again from the app.\"}")) {
      BridgeProcess.Result r = run("", "headers", "--endpoint", "dev", "--grant", GRANT);
      assertThat(r.code()).isEqualTo(2);
      assertThat(r.out()).isEmpty();
    }
    // Codex throws the helper's stderr away: the reason is in the bridge's log, owner-only, with the grant's prefix.
    Path log = home.resolve("logs").resolve("bridge.log");
    assertThat(Files.readString(log)).contains("grant_revoked").contains("gr_abcd…").doesNotContain(GRANT);
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(log))).isEqualTo("rw-------");
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(log.getParent()))).isEqualTo("rwx------");
  }

  @Test
  void stdioAnswersALineAfterGettingANewTokenOnA401() throws Exception {
    AtomicInteger issuedCount = new AtomicInteger();
    HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    http.createContext("/mcp/dev", ex -> {
      ex.getRequestBody().readAllBytes();
      ex.getResponseHeaders().add("X-Gateway-Bridge-Instance", "inst-1");
      // The first token the broker hands out has already expired, as after a laptop's sleep.
      if (!"Bearer gwb_t2".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
        ex.sendResponseHeaders(401, -1);
        ex.close();
        return;
      }
      byte[] out = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-11-25\"}}"
          .getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, out.length);
      ex.getResponseBody().write(out);
      ex.close();
    });
    http.start();
    int port = http.getAddress().getPort();
    try (FakeBroker broker = new FakeBroker(home, r -> issued(port, "gwb_t" + issuedCount.incrementAndGet()))) {
      BridgeProcess.Result r = run("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n",
          "stdio", "--endpoint", "dev", "--grant", GRANT, "--client", "cursor");
      assertThat(r.code()).as(r.err()).isZero();
      assertThat(r.out().strip()).isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":"
          + "\"2025-11-25\"}}");
      assertThat(broker.requests).hasSize(2);
      assertThat(r.out() + r.err()).doesNotContain("gwb_t");
    } finally {
      http.stop(0);
    }
  }
}
