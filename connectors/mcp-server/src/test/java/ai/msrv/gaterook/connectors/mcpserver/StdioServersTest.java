package ai.msrv.gaterook.connectors.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.support.mcp.McpServerRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The runtime against a real stdio server of the MCP Java SDK in a child JVM ({@link FakeMcpServer}): start, tools,
 * calls, the environment a server gets, secrets in its log, approvals, the whole process tree, crashes and backoff,
 * idle stop, a server that changes a tool after the review, and servers that can't be talked to.
 */
class StdioServersTest {

  private static final String TOKEN = "fake-token-8c1d77e0a5b34f29";
  private static final String TOKEN_FIELD = "/env/0/secretRef";

  @TempDir
  Path home;

  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-25T10:00:00Z"));
  private Fixtures.Host host;
  private McpServers runtime;
  private final Fixtures.Secrets secrets = new Fixtures.Secrets().with(TOKEN_FIELD, TOKEN);

  @BeforeEach
  void attach() {
    host = new Fixtures.Host(home);
    runtime = runtime(clock);
    runtime.attach(host);
  }

  @AfterEach
  void close() {
    runtime.close();
  }

  private McpServers runtime(Clock c) {
    Map<String, String> coreEnv = Map.of("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"),
        "HOME", home.toString(), "USER", "tester", "CORE_ONLY_SECRET", "must-not-leak");
    return new McpServers(coreEnv, System.getProperty("os.name"), false, c, Duration.ofMillis(100),
        McpServerConnector.typeManifest());
  }

  private static ObjectNode config(String mode, String extra) {
    ObjectNode c = Fixtures.object("{\"transport\":\"stdio\",\"env\":[{\"name\":\"FAKE_TOKEN\"}]"
        + (extra.isEmpty() ? "" : "," + extra) + "}");
    ArrayNode command = c.putArray("command");
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(FakeMcpServer.class.getName());
    command.add(mode);
    return c;
  }

  private static ConnectionConfig cfg(String mode, String extra) {
    return Fixtures.cfg("cn_fake", "fake-mcp", Env.DEV, 1, config(mode, extra));
  }

  /** The review the core stores: the tools as the first start listed them. */
  private McpServerRuntime.Snapshot review(ConnectionConfig cfg) throws ConnectorException {
    McpServerRuntime.Handshake h = runtime.probe(cfg, secrets, Fixtures.Context.of(Duration.ofSeconds(60)));
    return new McpServerRuntime.Snapshot("fake_", h.serverVersion(), h.protocolVersion(), h.tools(), null);
  }

  private static String text(ToolResult r) {
    return r.content().getFirst().text();
  }

  private static ToolResult approved(Connector c, ConnectionConfig cfg, String tool, String args,
      Fixtures.Secrets secrets) throws ConnectorException {
    ActionPreview preview = c.describeAction(Fixtures.call(tool, args), cfg);
    return c.execute(Fixtures.call(tool, args), cfg, secrets, Fixtures.Context.approved(preview.payload()));
  }

  private static void await(BooleanSupplier condition, Duration within) throws InterruptedException {
    long end = System.nanoTime() + within.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > end) {
        throw new AssertionError("Condition not met in " + within);
      }
      Thread.sleep(50);
    }
  }

  @Test
  void aServerStartsListsItsToolsAndAnswersACall() throws Exception {
    ConnectionConfig cfg = cfg("normal", "");
    McpServerRuntime.Snapshot snapshot = review(cfg);

    assertThat(snapshot.serverVersion()).isEqualTo("1.2.3");
    assertThat(snapshot.protocolVersion()).isEqualTo("2025-11-25");
    assertThat(snapshot.tools()).extracting(t -> t.path("exposed").asString())
        .containsExactlyInAnyOrder("fake_echo", "fake_slow", "fake_crash", "fake_env", "fake_spawn", "fake_caps",
            "fake_change", "fake_flip");
    // The probe's process is gone with it.
    await(() -> ProcessHandle.current().children().noneMatch(ProcessHandle::isAlive), Duration.ofSeconds(10));

    Connector c = runtime.connector(cfg, snapshot);
    assertThat(c.manifest().version()).isEqualTo("1.2.3");
    assertThat(c.manifest().kind()).isEqualTo(ai.msrv.gaterook.connector.api.ConnectorManifest.Kind.MCP_SERVER);
    assertThat(c.classify(Fixtures.call("fake_echo", "{}"), cfg, Fixtures.policy()).kind())
        .isEqualTo(Classification.Kind.READ);
    assertThat(c.classify(Fixtures.call("fake_slow", "{}"), cfg, Fixtures.policy()).kind())
        .isEqualTo(Classification.Kind.MUTATION);
    assertThat(c.classify(Fixtures.call("fake_other", "{}"), cfg, Fixtures.policy()).kind())
        .isEqualTo(Classification.Kind.DENY);

    ToolResult r = c.execute(Fixtures.call("fake_echo", "{\"text\":\"hello\"}"), cfg, secrets,
        Fixtures.Context.of(Duration.ofSeconds(30)));

    assertThat(text(r)).isEqualTo("hello");
    assertThat(host.handshakes).hasSize(1);
    assertThat(runtime.process("cn_fake")).get().extracting(McpServerRuntime.ProcessView::state)
        .isEqualTo(McpServerRuntime.State.RUNNING);
    assertThat(c.health(cfg, secrets, Fixtures.Context.of(Duration.ofSeconds(10))).state())
        .isEqualTo(HealthResult.State.HEALTHY);
  }

  @Test
  void aServerGetsAMinimalEnvironmentItsOwnFolderAndItsSecretStaysOutOfTheLog() throws Exception {
    ConnectionConfig cfg = cfg("normal", "");
    Connector c = runtime.connector(cfg, review(cfg));
    Fixtures.Context ctx = Fixtures.Context.of(Duration.ofSeconds(30));

    String out = text(c.execute(Fixtures.call("fake_env", "{}"), cfg, secrets, ctx));

    Set<String> names = new TreeSet<>(List.of(out.lines().findFirst().orElseThrow().substring(6).split(",")));
    if (System.getProperty("os.name").startsWith("Mac")) {
      // CoreFoundation in the server's own runtime sets it when it's missing: not something the gateway passed.
      names.remove("__CF_USER_TEXT_ENCODING");
    }
    assertThat(names).contains("PATH", "HOME", "FAKE_TOKEN").doesNotContain("CORE_ONLY_SECRET")
        .isSubsetOf("PATH", "HOME", "USER", "LOGNAME", "SHELL", "TERM", "LANG", "NPM_CONFIG_UPDATE_NOTIFIER",
            "NPM_CONFIG_FUND", "FAKE_TOKEN", "TMPDIR", "npm_config_cache");
    // The server reports its folder resolved: macOS keeps temporary folders behind /var → /private/var.
    assertThat(out).contains("cwd=" + home.resolve("mcp/fake-mcp").toRealPath()).contains("token=" + TOKEN);
    // The value reached the process in an earlier start: each call's output is masked for it again.
    assertThat(ctx.taints).contains(TOKEN);
    String log = Files.readString(home.resolve("logs/mcp/fake-mcp.log"));
    assertThat(log).contains("fake server starting, token ‹masked›").doesNotContain(TOKEN);
  }

  @Test
  void aCallThatMayChangeDataRunsOnlyApprovedAndOnlyAsApproved() throws Exception {
    ConnectionConfig cfg = cfg("normal", "");
    Connector c = runtime.connector(cfg, review(cfg));

    assertThatThrownBy(() -> c.execute(Fixtures.call("fake_slow", "{\"ms\":1}"), cfg, secrets,
        Fixtures.Context.of(Duration.ofSeconds(30))))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));

    ActionPreview preview = c.describeAction(Fixtures.call("fake_slow", "{\"ms\":1}"), cfg);
    assertThat(preview.text()).contains("\"method\" : \"tools/call\"").contains("\"name\" : \"slow\"");
    assertThat(preview.warnings()).anyMatch(w -> w.contains("can't see what the server does"));

    // The approved payload runs, not the arguments of the call that carries it.
    ToolResult r = c.execute(Fixtures.call("fake_slow", "{\"ms\":999999}"), cfg, secrets,
        Fixtures.Context.approved(preview.payload()));
    assertThat(text(r)).isEqualTo("done");

    ObjectNode forged = (ObjectNode) preview.payload().deepCopy();
    forged.put("definitionHash", "0".repeat(64));
    assertThatThrownBy(() -> c.execute(Fixtures.call("fake_slow", "{}"), cfg, secrets,
        Fixtures.Context.approved(forged)))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
  }

  /** ARCHITECTURE.md §9: the client never offers roots, sampling or elicitation to a server. */
  @Test
  void theClientDeclaresNoCapabilities() throws Exception {
    ConnectionConfig cfg = cfg("normal", "");
    Connector c = runtime.connector(cfg, review(cfg));

    JsonNode caps = Texts.JSON.readTree(text(approved(c, cfg, "fake_caps", "{}", secrets)));

    assertThat(caps.has("roots")).isFalse();
    assertThat(caps.has("sampling")).isFalse();
    assertThat(caps.has("elicitation")).isFalse();
    assertThat(caps.has("experimental")).isFalse();
  }

  @Test
  void stoppingAServerStopsEverythingItStarted() throws Exception {
    ConnectionConfig cfg = cfg("normal", "");
    Connector c = runtime.connector(cfg, review(cfg));

    long child = Long.parseLong(text(approved(c, cfg, "fake_spawn", "{}", secrets)));
    Long root = runtime.process("cn_fake").orElseThrow().pid();
    assertThat(ProcessHandle.of(child)).get().matches(ProcessHandle::isAlive);

    runtime.stop("cn_fake");

    await(() -> ProcessHandle.of(child).map(p -> !p.isAlive()).orElse(true)
        && ProcessHandle.of(root).map(p -> !p.isAlive()).orElse(true), Duration.ofSeconds(15));
    assertThat(runtime.process("cn_fake").orElseThrow().state()).isEqualTo(McpServerRuntime.State.STOPPED);
  }

  @Test
  void whatACrashedServerLeftBehindIsStoppedAndItBacksOff() throws Exception {
    ConnectionConfig cfg = cfg("normal", "");
    Connector c = runtime.connector(cfg, review(cfg));
    long child = Long.parseLong(text(approved(c, cfg, "fake_spawn", "{}", secrets)));

    assertThatThrownBy(() -> approved(c, cfg, "fake_crash", "{}", secrets))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN));

    await(() -> runtime.process("cn_fake").orElseThrow().state() == McpServerRuntime.State.BACKOFF,
        Duration.ofSeconds(10));
    assertThat(runtime.process("cn_fake").orElseThrow().detail()).contains("code 3");
    await(() -> ProcessHandle.of(child).map(p -> !p.isAlive()).orElse(true), Duration.ofSeconds(15));
    assertThatThrownBy(() -> c.execute(Fixtures.call("fake_echo", "{\"text\":\"x\"}"), cfg, secrets,
        Fixtures.Context.of(Duration.ofSeconds(30))))
        .hasMessageContaining("crashed").hasMessageContaining("can start again in");

    clock.advance(Duration.ofSeconds(2));
    assertThat(text(c.execute(Fixtures.call("fake_echo", "{\"text\":\"again\"}"), cfg, secrets,
        Fixtures.Context.of(Duration.ofSeconds(30))))).isEqualTo("again");
  }

  @Test
  void fiveFailedStartsInTenMinutesAreFailedUntilAPersonStartsIt() throws Exception {
    ConnectionConfig cfg = cfg("exit", "");
    McpServerRuntime.Snapshot snapshot = new McpServerRuntime.Snapshot("fake_", "1.2.3", "2025-11-25",
        review(cfg("normal", "")).tools(), null);
    Connector c = runtime.connector(cfg, snapshot);
    for (int i = 0; i < Supervisor.MAX_CRASHES; i++) {
      assertThatThrownBy(() -> c.execute(Fixtures.call("fake_echo", "{}"), cfg, secrets,
          Fixtures.Context.of(Duration.ofSeconds(30))))
          .hasMessageContaining("exited with code 7").hasMessageContaining("missing configuration");
      clock.advance(Duration.ofSeconds(61));
    }

    assertThat(runtime.process("cn_fake").orElseThrow().state()).isEqualTo(McpServerRuntime.State.FAILED);
    assertThatThrownBy(() -> c.execute(Fixtures.call("fake_echo", "{}"), cfg, secrets,
        Fixtures.Context.of(Duration.ofSeconds(30))))
        .hasMessageContaining("failed").hasMessageContaining("Start it again");
    // A person's Test tries again whatever happened before.
    TestResult t = c.test(cfg, secrets, Fixtures.Context.of(Duration.ofSeconds(30)));
    assertThat(t.ok()).isFalse();
    assertThat(t.error().message()).contains("exited with code 7");
  }

  @Test
  void anIdleServerIsStopped() throws Exception {
    ConnectionConfig cfg = cfg("normal", "\"idleStopMinutes\":1");
    Connector c = runtime.connector(cfg, review(cfg));
    c.execute(Fixtures.call("fake_echo", "{\"text\":\"x\"}"), cfg, secrets, Fixtures.Context.of(Duration.ofSeconds(30)));
    Long pid = runtime.process("cn_fake").orElseThrow().pid();

    clock.advance(Duration.ofMinutes(2));

    await(() -> runtime.process("cn_fake").orElseThrow().state() == McpServerRuntime.State.STOPPED,
        Duration.ofSeconds(10));
    assertThat(runtime.process("cn_fake").orElseThrow().detail()).isEqualTo("Stopped after 1 min idle");
    await(() -> ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true), Duration.ofSeconds(10));
    // The next call starts it again.
    assertThat(text(c.execute(Fixtures.call("fake_echo", "{\"text\":\"back\"}"), cfg, secrets,
        Fixtures.Context.of(Duration.ofSeconds(30))))).isEqualTo("back");
  }

  @Test
  void aToolChangedAfterTheReviewDoesntRunAndTheHostHearsTheNewList() throws Exception {
    ConnectionConfig cfg = cfg("normal", "");
    Connector c = runtime.connector(cfg, review(cfg));

    approved(c, cfg, "fake_change", "{}", secrets);
    await(() -> host.handshakes.size() >= 2, Duration.ofSeconds(10));

    assertThatThrownBy(() -> c.execute(Fixtures.call("fake_echo", "{\"text\":\"x\"}"), cfg, secrets,
        Fixtures.Context.of(Duration.ofSeconds(30))))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).contains("changed echo since it was reviewed");
        });
    JsonNode echo = Snapshots.find(host.handshakes.getLast().tools(), "fake_echo").orElseThrow();
    assertThat(echo.path("description").asString()).contains("sends it elsewhere");
  }

  /**
   * Every list a server announces is a new snapshot, connection version and Setup change in the core: a server that
   * keeps changing its tools is heard at most once a tick and, past 10 changes in 10 minutes, stopped as if it crashed.
   */
  @Test
  void aServerThatKeepsChangingItsToolsIsHeardSparinglyAndStoppedAsIfItCrashed() throws Exception {
    ConnectionConfig cfg = cfg("normal", "");
    Connector c = runtime.connector(cfg, review(cfg));
    int before = host.handshakes.size();

    try {
      approved(c, cfg, "fake_flip", "{}", secrets);
    } catch (ConnectorException e) {
      // The process may be stopped while the call still flips.
    }
    await(() -> runtime.process("cn_fake").map(v -> v.state() == McpServerRuntime.State.BACKOFF).orElse(false),
        Duration.ofSeconds(20));

    assertThat(runtime.process("cn_fake").orElseThrow().detail())
        .contains("Changed its list of tools more than 10 times in 10 minutes");
    // The call's start, then at most the ten changes it was allowed, coalesced by the tick.
    assertThat(host.handshakes.size() - before).isBetween(1, 11);
    Thread.sleep(500);
    int after = host.handshakes.size();
    Thread.sleep(500);
    assertThat(host.handshakes.size()).as("nothing more once it is stopped").isEqualTo(after);
  }

  @Test
  void aRotatedSecretStartsANewProcess() throws Exception {
    ConnectionConfig cfg = cfg("normal", "");
    Connector c = runtime.connector(cfg, review(cfg));
    c.execute(Fixtures.call("fake_echo", "{\"text\":\"x\"}"), cfg, secrets, Fixtures.Context.of(Duration.ofSeconds(30)));
    Long before = runtime.process("cn_fake").orElseThrow().pid();

    secrets.rotate(TOKEN_FIELD, "rotated-token-5d2e9a10b7c4");
    String out = text(c.execute(Fixtures.call("fake_env", "{}"), cfg, secrets,
        Fixtures.Context.of(Duration.ofSeconds(30))));

    assertThat(out).contains("token=rotated-token-5d2e9a10b7c4");
    assertThat(runtime.process("cn_fake").orElseThrow().pid()).isNotEqualTo(before);
    await(() -> ProcessHandle.of(before).map(p -> !p.isAlive()).orElse(true), Duration.ofSeconds(10));
  }

  @Test
  void processesAPreviousRunLeftBehindAreStoppedAtTheNextStart() throws Exception {
    ConnectionConfig cfg = cfg("normal", "");
    Connector c = runtime.connector(cfg, review(cfg));
    c.execute(Fixtures.call("fake_echo", "{\"text\":\"x\"}"), cfg, secrets, Fixtures.Context.of(Duration.ofSeconds(30)));
    Long pid = runtime.process("cn_fake").orElseThrow().pid();

    // A core that was killed never stopped its servers; the next one reads the pid file.
    McpServers next = runtime(Clock.systemUTC());
    try {
      next.attach(new Fixtures.Host(home));
      await(() -> ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true), Duration.ofSeconds(15));
    } finally {
      next.close();
    }
  }

  @Test
  void serversThatCantBeTalkedToSayWhy() {
    Fixtures.Context ctx = Fixtures.Context.of(Duration.ofSeconds(60));

    assertThatThrownBy(() -> runtime.probe(cfg("garbage", ""), secrets, ctx))
        .hasMessageContaining("non-JSON").hasMessageContaining("this line is not JSON")
        .hasMessageNotContaining(TOKEN).hasMessageContaining(ServerLog.MASK);
    assertThatThrownBy(() -> runtime.probe(cfg("exit", ""), secrets, ctx))
        .hasMessageContaining("exited with code 7").hasMessageContaining("missing configuration")
        .hasMessageNotContaining(TOKEN);
    assertThatThrownBy(() -> runtime.probe(cfg("only-2026", ""), secrets, ctx))
        .hasMessageContaining("Speaks 2026-07-28 only; MCP Gateway connects up to 2025-11-25.");
    long t0 = System.nanoTime();
    assertThatThrownBy(() -> runtime.probe(cfg("hang", "\"startTimeoutSeconds\":5"), secrets, ctx))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT));
    assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(15));
    assertThat(ProcessHandle.current().children().filter(ProcessHandle::isAlive).count()).isZero();
  }

  @Test
  void theReviewShowsTheCommandAsItWillRun() {
    McpServerRuntime.Review r = runtime.review(Fixtures.cfg("cn_x", "github-mcp", Env.DEV, 1, Fixtures.object("""
        {"transport":"stdio","command":["npx","-y","@modelcontextprotocol/server-github"],
         "env":[{"name":"GITHUB_PERSONAL_ACCESS_TOKEN"}]}""")));

    assertThat(r.notPinned()).isTrue();
    assertThat(r.variables()).containsExactly("GITHUB_PERSONAL_ACCESS_TOKEN");
    assertThat(r.workDir()).isEqualTo(home.resolve("mcp/github-mcp").toString());
    assertThat(r.warnings()).anyMatch(w -> w.startsWith("Not pinned"));
  }

  /** A clock the test moves: backoff, the crash window and idle stops without waiting for them. */
  static final class MutableClock extends Clock {
    private final AtomicReference<Instant> now;

    MutableClock(Instant start) {
      this.now = new AtomicReference<>(start);
    }

    void advance(Duration d) {
      now.updateAndGet(i -> i.plus(d));
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  }
}
