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
 * (ARCHITECTURE.md §7.3, report 02 §7.3): the address comes from the agent itself (cloudflared's metrics server,
 * ngrok's log), the URL is ready only once the edge is, a token travels in the environment only, ngrok runs without
 * its local API, and an agent that doesn't make it is killed.
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
  void ngrokGetsItsUrlFromItsLogAndItsAuthtokenInTheEnvironment() throws Exception {
    ngrok.control(Map.of("mode", "ready", "readyDelayMs", "1200"));
    long t0 = System.nanoTime();
    started = new NgrokAgent().start(request(TunnelProvider.NGROK, ngrok.executable(), "gw-acme.ngrok.app",
        "2fakeNgrokAuthtoken_0123456789abcdefghij"));
    assertThat(Duration.ofNanos(System.nanoTime() - t0)).as("waited for \"started tunnel\"")
        .isGreaterThan(Duration.ofSeconds(1));
    assertThat(started.host()).isEqualTo("gw-acme.ngrok.app");
    assertThat(started.detail()).isEqualTo("local API off");
    assertThat(ngrok.argv()).containsExactly("http", "127.0.0.1:7466", "--config", run.resolve("ngrok.yml")
        .toString(), "--log", "stdout", "--log-format", "json", "--inspect=false", "--url",
        "https://gw-acme.ngrok.app");
    assertThat(ngrok.report().getProperty("authtoken")).isEqualTo("2fakeNgrokAuthtoken_0123456789abcdefghij");
    assertThat(Files.readString(run.resolve("ngrok.yml"))).contains("version: \"3\"").contains("web_addr: false")
        .contains("update_check: false").contains("console_ui: false").doesNotContain("127.0.0.1");
    assertThat(Files.readString(logs.resolve("ngrok.log"))).contains("starting ngrok")
        .contains("\"msg\":\"started tunnel\"").doesNotContain("2fakeNgrokAuthtoken");
    started.process().stop();
    started = null;

    // Without a reserved domain ngrok picks one; with one it must be that one. The log still holds the line of the
    // run before, with gw-acme.ngrok.app: only this run's lines count.
    ngrok.control(Map.of("mode", "ready"));
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
  void ngrokRunsWithoutItsLocalApiAndReadsOnlyThisRunsLog() throws Exception {
    // The agent's local API has no authentication: whoever reached its port could open tunnels on the person's
    // authtoken. The fake refuses to start with one, as the config must switch it off.
    Files.writeString(logs.resolve("ngrok.log"), "--- 2026-11-04T09:00:00Z starting ngrok ---\n"
        + "{\"t\":\"2026-11-04T10:00:01+0100\",\"lvl\":\"info\",\"msg\":\"started tunnel\",\"obj\":\"tunnels\","
        + "\"url\":\"https://gw-yesterday.ngrok-free.app\"}\n", StandardCharsets.UTF_8);
    ngrok.control(Map.of("mode", "never-ready"));
    assertThatThrownBy(() -> new NgrokAgent(Duration.ofSeconds(2)).start(request(TunnelProvider.NGROK,
        ngrok.executable(), null, "tok"))).isInstanceOf(TunnelException.class)
        .as("an earlier run's line is no answer").hasMessageContaining("didn't connect");
    FakeAgents.awaitGone(ngrok.pid());

    // A line written in two parts counts once it is whole.
    ngrok.control(Map.of("mode", "ready", "hostname", "gw-split.ngrok-free.app", "splitLineMs", "700"));
    started = new NgrokAgent(Duration.ofSeconds(10)).start(request(TunnelProvider.NGROK, ngrok.executable(), null,
        "tok"));
    assertThat(started.host()).isEqualTo("gw-split.ngrok-free.app");
    assertThat(ngrok.argv()).contains("--inspect=false");
    assertThat(Files.readString(run.resolve("ngrok.yml"))).contains("web_addr: false");
  }

  @Test
  void anAgentsLogIsReadALineAtATimeFromThisRunsStart() throws Exception {
    Path log = logs.resolve("follow.log");
    Files.writeString(log, "an earlier run\n", StandardCharsets.UTF_8);
    AgentProcess.LogFollower follower = new AgentProcess.LogFollower(log, Files.size(log));
    assertThat(follower.next()).isEmpty();
    append(log, "{\"msg\":\"started");
    assertThat(follower.next()).as("a line still being written").isEmpty();
    append(log, " tunnel\"}\nsecond\r\nthi");
    assertThat(follower.next()).containsExactly("{\"msg\":\"started tunnel\"}", "second");
    append(log, "rd\n" + "x".repeat(AgentProcess.LogFollower.LINE_LIMIT + 1) + "\nafter a long line\n");
    assertThat(follower.next()).containsExactly("third", "after a long line");
    Files.writeString(log, "emptied, then written again\n", StandardCharsets.UTF_8);
    assertThat(follower.next()).containsExactly("emptied, then written again");
    Files.delete(log);
    assertThat(follower.next()).isEmpty();
  }

  private static void append(Path file, String text) throws java.io.IOException {
    Files.writeString(file, text, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
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

      List<String> changedIdentity = new java.util.ArrayList<>(stored.lines().toList());
      changedIdentity.set(2, Long.toString(Long.parseLong(changedIdentity.get(2)) - 60_000));
      Files.write(pidFile, changedIdentity);
      AgentProcess.killOrphan(pidFile);
      assertThat(orphan.process().alive()).as("a reused PID with a different start time is left alone").isTrue();

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
  void ngrokIsReadyOnlyWithAStartedTunnelLineOfAnHttpsUrl() {
    assertThat(NgrokAgent.startedHost("{\"t\":\"2026-11-05T10:00:00+0100\",\"lvl\":\"info\",\"msg\":\"started "
        + "tunnel\",\"obj\":\"tunnels\",\"name\":\"command_line\",\"addr\":\"http://127.0.0.1:7466\",\"url\":"
        + "\"https://B.ngrok.app\"}")).contains("b.ngrok.app");
    assertThat(NgrokAgent.startedHost("{\"lvl\":\"info\",\"msg\":\"started tunnel\",\"url\":\"http://a.ngrok.app\"}"))
        .as("not https").isEmpty();
    assertThat(NgrokAgent.startedHost("{\"lvl\":\"info\",\"msg\":\"started tunnel\",\"url\":\"tcp://0.tcp.ngrok"
        + ".io:12345\"}")).isEmpty();
    assertThat(NgrokAgent.startedHost("{\"lvl\":\"info\",\"msg\":\"client session established\",\"note\":"
        + "\"started tunnel\",\"url\":\"https://a.ngrok.app\"}")).as("another message").isEmpty();
    assertThat(NgrokAgent.startedHost("{\"lvl\":\"info\",\"msg\":\"started tunnel\",\"url\":\"https://bad_host!\"}"))
        .isEmpty();
    assertThat(NgrokAgent.startedHost("{\"lvl\":\"info\",\"msg\":\"started tunnel\"}")).isEmpty();
    assertThat(NgrokAgent.startedHost("t=2026-11-05T10:00:00Z lvl=info msg=\"started tunnel\" "
        + "url=https://a.ngrok.app")).as("not the JSON log").isEmpty();
    assertThat(NgrokAgent.startedHost("[\"started tunnel\"]")).isEmpty();
    assertThat(NgrokAgent.startedHost("<html>")).isEmpty();
  }
}
