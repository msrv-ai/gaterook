package ai.msrv.gaterook.core.tunnel.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.core.platform.TestGateway;
import ai.msrv.gaterook.core.tunnel.TunnelProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The agents against the fake cloudflared and ngrok, as child processes with the real argv and environment
 * (ARCHITECTURE.md §7.3, report 02 §7.3): the address comes from the agent's local interface, the URL is ready only
 * once the edge is, a token travels in the environment only, and an agent that doesn't make it is killed.
 */
class TunnelAgentsTest {

  private Path dir;
  private Path run;
  private Path logs;
  private FakeAgents.Agent cloudflared;
  private FakeAgents.Agent ngrok;
  private TunnelAgent.Started started;

  @BeforeEach
  void setUp() throws Exception {
    dir = TestGateway.newHome("agents");
    run = Files.createDirectories(dir.resolve("run"));
    logs = Files.createDirectories(dir.resolve("logs"));
    cloudflared = FakeAgents.cloudflared(dir.resolve("bin"));
    ngrok = FakeAgents.ngrok(dir.resolve("bin"));
  }

  @AfterEach
  void tearDown() {
    if (started != null) {
      started.process().stop();
    }
  }

  private TunnelAgent.StartRequest request(TunnelProvider provider, Path binary, String hostname, String secret) {
    return new TunnelAgent.StartRequest(provider, binary, 7466, hostname, secret == null ? null
        : secret.toCharArray(), run, logs);
  }

  @Test
  void aQuickTunnelGetsItsAddressFromTheMetricsAndIsReadyOnlyWithTheEdge() throws Exception {
    cloudflared.control(Map.of("mode", "ready", "hostname", "gw-smoke-7f3k.trycloudflare.com",
        "readyDelayMs", "1200"));
    long t0 = System.nanoTime();
    started = new CloudflaredAgent().start(request(TunnelProvider.CLOUDFLARE_QUICK, cloudflared.executable(), null,
        null));
    assertThat(Duration.ofNanos(System.nanoTime() - t0)).as("waited for /ready").isGreaterThan(Duration.ofSeconds(1));
    assertThat(started.host()).isEqualTo("gw-smoke-7f3k.trycloudflare.com");
    List<String> argv = cloudflared.argv();
    assertThat(argv).containsSubsequence("tunnel", "--no-autoupdate", "--config", run.resolve("cloudflared.yml")
        .toString(), "--metrics").containsSubsequence("--edge-ip-version", "4", "--protocol", "auto", "--url",
        "http://127.0.0.1:7466").doesNotContain("run");
    assertThat(argv.get(argv.indexOf("--metrics") + 1)).startsWith("127.0.0.1:");
    assertThat(cloudflared.report().getProperty("tunnelToken")).isEmpty();
    assertThat(Files.readString(run.resolve("cloudflared.yml"))).contains("no-autoupdate: true");
    assertThat(Files.readString(run.resolve("tunnel.pid"), StandardCharsets.UTF_8))
        .startsWith(started.process().pid() + "\n").contains(run.resolve("cloudflared.yml").toString());
    assertThat(Files.readString(logs.resolve("cloudflared.log"))).contains("starting cloudflared")
        .contains("Registered tunnel connection");

    long pid = started.process().pid();
    started.process().stop();
    FakeAgents.awaitGone(pid);
    assertThat(run.resolve("tunnel.pid")).doesNotExist();
    started = null;
  }

  @Test
  void aNamedTunnelGetsItsTokenInTheEnvironmentOnly() throws Exception {
    cloudflared.control(Map.of("mode", "ready", "target", "http://127.0.0.1:7466"));
    String token = "eyJhIjoiZmFrZS1hY2NvdW50IiwidCI6ImZha2UtdHVubmVsIiwicyI6ImZha2Utc2VjcmV0In0";
    started = new CloudflaredAgent().start(request(TunnelProvider.CLOUDFLARE_NAMED, cloudflared.executable(),
        "gw.example.com", token));
    assertThat(started.host()).isEqualTo("gw.example.com");
    assertThat(cloudflared.report().getProperty("tunnelToken")).isEqualTo(token);
    assertThat(cloudflared.argv()).contains("run").doesNotContain("--url").noneMatch(a -> a.contains(token));

    assertThatThrownBy(() -> new CloudflaredAgent().start(request(TunnelProvider.CLOUDFLARE_NAMED,
        cloudflared.executable(), "gw.example.com", null))).isInstanceOf(TunnelException.class)
        .hasMessageContaining("needs its token");
    assertThatThrownBy(() -> new CloudflaredAgent().start(request(TunnelProvider.CLOUDFLARE_NAMED,
        cloudflared.executable(), "not a host", token))).isInstanceOf(TunnelException.class)
        .hasMessageContaining("hostname");
  }

  @Test
  void anAgentThatNeverReachesTheEdgeIsKilledWithAnErrorThePersonCanActOn() throws Exception {
    cloudflared.control(Map.of("mode", "never-ready"));
    CloudflaredAgent agent = new CloudflaredAgent(Duration.ofSeconds(15), Duration.ofSeconds(2));
    assertThatThrownBy(() -> agent.start(request(TunnelProvider.CLOUDFLARE_QUICK, cloudflared.executable(), null,
        null))).isInstanceOf(TunnelException.class).hasMessageContaining("port 7844")
        .hasMessageContaining("Last log line").hasMessageContaining("failed to dial");
    FakeAgents.awaitGone(cloudflared.pid());
    assertThat(run.resolve("tunnel.pid")).doesNotExist();

    cloudflared.control(Map.of("mode", "no-hostname"));
    CloudflaredAgent quick = new CloudflaredAgent(Duration.ofSeconds(2), Duration.ofSeconds(2));
    assertThatThrownBy(() -> quick.start(request(TunnelProvider.CLOUDFLARE_QUICK, cloudflared.executable(), null,
        null))).isInstanceOf(TunnelException.class).hasMessageContaining("didn't get an address");
    FakeAgents.awaitGone(cloudflared.pid());

    cloudflared.control(Map.of("mode", "no-edge"));
    assertThatThrownBy(() -> new CloudflaredAgent().start(request(TunnelProvider.CLOUDFLARE_QUICK,
        cloudflared.executable(), null, null))).isInstanceOf(TunnelException.class)
        .hasMessageContaining("Can't reach Cloudflare's edge: cloudflared stopped (exit 1)")
        .hasMessageContaining("firewall, VPN or DNS");

    cloudflared.control(Map.of("mode", "exit-at-once"));
    assertThatThrownBy(() -> new CloudflaredAgent().start(request(TunnelProvider.CLOUDFLARE_QUICK,
        cloudflared.executable(), null, null))).isInstanceOf(TunnelException.class)
        .hasMessageContaining("cloudflared stopped (exit 1)").hasMessageContaining("Invalid tunnel secret");
  }

  @Test
  void anInterruptedStartKillsTheAgentAtOnce() throws Exception {
    cloudflared.control(Map.of("mode", "never-ready"));
    CloudflaredAgent agent = new CloudflaredAgent(Duration.ofSeconds(60), Duration.ofSeconds(60));
    java.util.concurrent.atomic.AtomicReference<Throwable> outcome = new java.util.concurrent.atomic.AtomicReference<>();
    Thread start = Thread.ofVirtual().start(() -> {
      try {
        agent.start(request(TunnelProvider.CLOUDFLARE_QUICK, cloudflared.executable(), null, null));
      } catch (Throwable t) {
        outcome.set(t);
      }
    });
    long pid = cloudflared.pid();
    long t0 = System.nanoTime();
    start.interrupt();
    assertThat(start.join(Duration.ofSeconds(10))).as("the start ended").isTrue();
    assertThat(outcome.get()).isInstanceOf(InterruptedException.class);
    assertThat(FakeAgents.alive(pid)).isFalse();
    assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(10));
    assertThat(run.resolve("tunnel.pid")).doesNotExist();
  }

  @Test
  void ngrokGetsItsUrlFromItsAgentApiAndItsAuthtokenInTheEnvironment() throws Exception {
    started = new NgrokAgent().start(request(TunnelProvider.NGROK, ngrok.executable(), "gw-acme.ngrok.app",
        "2fakeNgrokAuthtoken_0123456789abcdefghij"));
    assertThat(started.host()).isEqualTo("gw-acme.ngrok.app");
    assertThat(ngrok.argv()).containsExactly("http", "127.0.0.1:7466", "--config", run.resolve("ngrok.yml")
        .toString(), "--log", "stdout", "--log-format", "json", "--inspect=false", "--url",
        "https://gw-acme.ngrok.app");
    assertThat(ngrok.report().getProperty("authtoken")).isEqualTo("2fakeNgrokAuthtoken_0123456789abcdefghij");
    assertThat(Files.readString(run.resolve("ngrok.yml"))).contains("version: \"3\"").contains("web_addr: 127.0.0.1:")
        .contains("update_check: false").contains("console_ui: false");
    started.process().stop();
    started = null;

    // Without a reserved domain ngrok picks one; with one it must be that one.
    started = new NgrokAgent().start(request(TunnelProvider.NGROK, ngrok.executable(), null, "tok"));
    assertThat(started.host()).endsWith(".ngrok-free.app");
    started.process().stop();
    started = null;
    ngrok.control(Map.of("mode", "wrong-domain", "hostname", "someone-else.ngrok-free.app"));
    assertThatThrownBy(() -> new NgrokAgent().start(request(TunnelProvider.NGROK, ngrok.executable(),
        "gw-acme.ngrok.app", "tok"))).isInstanceOf(TunnelException.class).hasMessageContaining("instead of");
    FakeAgents.awaitGone(ngrok.pid());

    assertThatThrownBy(() -> new NgrokAgent().start(request(TunnelProvider.NGROK, ngrok.executable(), null, null)))
        .isInstanceOf(TunnelException.class).hasMessageContaining("authtoken");
    ngrok.control(Map.of("mode", "exit-at-once"));
    assertThatThrownBy(() -> new NgrokAgent().start(request(TunnelProvider.NGROK, ngrok.executable(), null, "bad")))
        .isInstanceOf(TunnelException.class).hasMessageContaining("ngrok stopped (exit 1)")
        .hasMessageContaining("ERR_NGROK_107");
    ngrok.control(Map.of("mode", "never-ready"));
    assertThatThrownBy(() -> new NgrokAgent(Duration.ofSeconds(2)).start(request(TunnelProvider.NGROK,
        ngrok.executable(), null, "tok"))).isInstanceOf(TunnelException.class)
        .hasMessageContaining("didn't connect").hasMessageContaining("no such host");
    FakeAgents.awaitGone(ngrok.pid());
  }

  @Test
  void ngrokKeepsNothingOfTheTrafficItCarries() throws Exception {
    // The real agent inspects by default: every request and answer it carries (bearer tokens, the token endpoint's
    // tokens) would be listed to anyone on this computer at its local API.
    int origin;
    try (java.net.ServerSocket free = new java.net.ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
      origin = free.getLocalPort();
    }
    started = new NgrokAgent().start(new TunnelAgent.StartRequest(TunnelProvider.NGROK, ngrok.executable(), origin,
        null, "tok".toCharArray(), run, logs));
    String token = "gwo_" + "c".repeat(43);
    java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
        .proxy(java.net.http.HttpClient.Builder.NO_PROXY).build();
    http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + ngrok.edgePort()
        + "/mcp/smoke")).header("Authorization", "Bearer " + token).POST(java.net.http.HttpRequest.BodyPublishers
        .ofString("{}")).build(), java.net.http.HttpResponse.BodyHandlers.discarding());
    java.util.regex.Matcher web = java.util.regex.Pattern.compile("web_addr: 127\\.0\\.0\\.1:(\\d+)")
        .matcher(Files.readString(run.resolve("ngrok.yml")));
    assertThat(web.find()).isTrue();
    String inspected = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"
        + web.group(1) + "/api/requests/http")).build(), java.net.http.HttpResponse.BodyHandlers.ofString()).body();
    assertThat(inspected).doesNotContain(token).contains("\"requests\":[]");
  }

  @Test
  void anAgentGetsOnlyWhatARunningProgramNeedsOfTheCoresEnvironment() {
    Map<String, String> core = Map.of("PATH", "/usr/bin:/bin", "HOME", "/Users/me", "https_proxy",
        "http://proxy.corp:3128", "TMPDIR", "/var/folders/x", "TUNNEL_TOKEN", "a-token", "TUNNEL_ORIGIN_CERT", "/c",
        "NGROK_AUTHTOKEN", "b-token", "AWS_SECRET_ACCESS_KEY", "c-secret", "GITHUB_TOKEN", "ghp_x", "GW_DEMO", "1");
    assertThat(AgentProcess.environment(core)).containsOnlyKeys("PATH", "HOME", "https_proxy", "TMPDIR");
  }

  @Test
  void anAgentWhosePidFileCantBeWrittenDoesntOutliveTheFailure() throws Exception {
    // A directory where the pid file goes: writing it fails after the process started.
    Path pidFile = Files.createDirectories(run.resolve("tunnel.pid"));
    String mark = "47.123";
    assertThatThrownBy(() -> AgentProcess.start(List.of("/bin/sleep", mark), Map.of(), run,
        logs.resolve("sleep.log"), pidFile, "marker")).isInstanceOf(TunnelException.class);
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (ProcessHandle.current().children().anyMatch(h -> h.isAlive()
        && List.of(h.info().arguments().orElse(new String[0])).contains(mark))) {
      assertThat(System.nanoTime()).as("the started process is gone").isLessThan(deadline);
      Thread.sleep(50);
    }
  }

  @Test
  void anOrphanOfACrashIsKilledOnlyWhenItRunsOurCommandLine() throws Exception {
    cloudflared.control(Map.of("mode", "ready"));
    TunnelAgent.Started orphan = new CloudflaredAgent().start(request(TunnelProvider.CLOUDFLARE_QUICK,
        cloudflared.executable(), null, null));
    Path pidFile = run.resolve("tunnel.pid");
    String stored = Files.readString(pidFile);
    try {
      // Another program with the pid: left alone.
      Files.writeString(pidFile, orphan.process().pid() + "\n/somewhere/else.yml\n");
      AgentProcess.killOrphan(pidFile);
      assertThat(orphan.process().alive()).isTrue();
      assertThat(pidFile).doesNotExist();

      Files.writeString(pidFile, stored);
      AgentProcess.killOrphan(pidFile);
      FakeAgents.awaitGone(orphan.process().pid());
    } finally {
      orphan.process().stop();
    }
    AgentProcess.killOrphan(pidFile);
    Files.writeString(pidFile, "not a pid\n");
    AgentProcess.killOrphan(pidFile);
    assertThat(pidFile).doesNotExist();
  }

  @Test
  void theLastErrorLineIsWhatThePersonSees() {
    assertThat(TunnelAgent.lastError(List.of("2026-10-25T10:00:00Z INF Starting tunnel",
        "2026-10-25T10:00:01Z ERR Failed to dial a quic connection error=\"timeout\"", "2026-10-25T10:00:02Z INF "
            + "Retrying connection in up to 2s"))).contains("Failed to dial");
    assertThat(TunnelAgent.lastError(List.of("{\"lvl\":\"eror\",\"msg\":\"session closed\"}"))).contains("session");
    assertThat(TunnelAgent.lastError(List.of("INF all good"))).isNull();
    assertThat(TunnelAgent.lastError(List.of("2026-10-25T10:00:00Z ERR " + "x".repeat(400)))).hasSize(301);
  }

  @Test
  void ngrokListsOnlyAnHttpsTunnel() {
    assertThat(NgrokAgent.httpsHost("{\"tunnels\":[{\"public_url\":\"http://a.ngrok.app\"},"
        + "{\"public_url\":\"https://B.ngrok.app\"}]}")).contains("b.ngrok.app");
    assertThat(NgrokAgent.httpsHost("{\"tunnels\":[]}")).isEmpty();
    assertThat(NgrokAgent.httpsHost("<html>")).isEmpty();
    assertThat(NgrokAgent.httpsHost("{\"tunnels\":[{\"public_url\":\"https://bad_host!\"}]}")).isEmpty();
  }
}
