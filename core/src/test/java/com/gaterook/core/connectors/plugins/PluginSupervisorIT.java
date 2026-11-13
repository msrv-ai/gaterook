package com.gaterook.core.connectors.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.gaterook.connector.api.ConnectorException;
import com.gaterook.core.connectors.ConnectorProcessStatus;
import com.gaterook.core.platform.GatewayPaths;
import com.gaterook.core.platform.HmacKeys;
import com.gaterook.core.platform.SealedHandles;
import com.gaterook.plugin.protocol.Gpp;
import com.gaterook.plugin.protocol.GppJson;
import com.gaterook.plugin.protocol.WireCodec;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The supervisor's life cycle against real hosts (ARCHITECTURE.md §7.5, report 16 §6): a side-by-side update switches
 * the running generation to a new one (and the old host stops), and a crash puts the plugin into a backoff and then,
 * after enough crashes, into Failed. Uses the built {@code plugin-host.jar} and plugin jars; it is skipped when they
 * aren't on disk (a {@code core}-only build).
 */
class PluginSupervisorIT {

  @TempDir
  Path home;

  private PluginPaths paths;
  private HostLauncher launcher;
  private PluginSupervisor supervisor;
  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-25T00:00:00Z"));
  /** The core's listeners, closed to every host's own sockets: the default ports unless a test listens on its own. */
  private final AtomicReference<Set<Integer>> corePorts = new AtomicReference<>(Set.of(7465, 7466));

  @BeforeEach
  void setUp() throws Exception {
    assumeTrue(hostJar() != null && Files.isRegularFile(hostJar()), "plugin-host.jar isn't built");
    System.setProperty("gateway.pluginHostJar", hostJar().toAbsolutePath().toString());
    GatewayPaths gatewayPaths = GatewayPaths.open(home);
    paths = new PluginPaths(gatewayPaths);
    // The checkout may live under a protected client directory such as .codex. Stage the test runtime in the
    // gateway's readable cache, just as the packaged runtime is outside the user's protected client files.
    Path runtimeJar = Files.copy(hostJar(), paths.cache().resolve("plugin-host.jar"));
    HostRuntimeLocator runtime = new HostRuntimeLocator(null, runtimeJar.toAbsolutePath().toString());
    AotCacheManager aot = new AotCacheManager(runtime, paths, false);
    launcher = new HostLauncher(runtime, paths, new SecretCallbackRegistry(),
        new SealedHandles(new FixedKeys(), GppJson.MAPPER, Clock.systemUTC()), aot, corePorts::get, clock);
    supervisor = new PluginSupervisor("jira-service-desk", launcher, clock);
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    if (supervisor != null) {
      supervisor.stop();
    }
    // Every host this test started is gone, and run/plugin-hosts.json forgot it (before the temp home is deleted).
    if (launcher != null) {
      waitFor(() -> launcher.pids().live().isEmpty());
      assertThat(launcher.pids().live()).as("hosts still recorded as running").isEmpty();
    }
  }

  @Test
  void aSideBySideUpdateSwitchesTheRunningGenerationAndStopsTheOldHost() throws Exception {
    assumeTrue(jiraJar("1.2.0") != null && jiraJar("1.3.0") != null, "the Jira plugin jars aren't built");
    HostLauncher.Spec v12 = spec("1.2.0", jiraJar("1.2.0"));
    supervisor.spec(v12);

    HostProcess host1;
    try (PluginSupervisor.Permit permit = supervisor.permit(deadline(10))) {
      host1 = permit.host();
      permit.returned();
    }
    assertThat(host1.loaded().tools()).extracting(com.gaterook.connector.api.ToolDefinition::name)
        .doesNotContain("jira_request_sla");
    // The host runs in this system's sandbox, set up before its JVM started (Linux: the first stage's Landlock).
    assertThat(host1.loaded().sandbox()).isEqualTo(launcher.sandboxKind());
    long oldPid = host1.pid();

    HostLauncher.Spec v13 = spec("1.3.0", jiraJar("1.3.0"));
    HostProcess host2 = supervisor.startAlternate(v13);
    assertThat(host2.loaded().tools()).extracting(com.gaterook.connector.api.ToolDefinition::name)
        .contains("jira_request_sla");
    supervisor.switchTo(host2, v13);

    // New calls get the new generation; the old host drains and stops.
    try (PluginSupervisor.Permit permit = supervisor.permit(deadline(10))) {
      assertThat(permit.host().pid()).isEqualTo(host2.pid()).isNotEqualTo(oldPid);
      assertThat(permit.host().loaded().tools()).extracting(com.gaterook.connector.api.ToolDefinition::name)
          .contains("jira_request_sla");
      permit.returned();
    }
    assertThat(ProcessHandle.of(oldPid).map(ProcessHandle::onExit).isPresent()).isTrue();
    boolean stopped = ProcessHandle.of(oldPid).map(h -> {
      try {
        h.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
        return true;
      } catch (Exception e) {
        return !h.isAlive();
      }
    }).orElse(true);
    assertThat(stopped).as("the old generation's host stops after it drains").isTrue();
  }

  /** Each change of the process state is reported: the core publishes it as {@code plugin.state} (review U1-U8). */
  @Test
  void eachStateChangeIsReported() throws Exception {
    assumeTrue(jiraJar("1.2.0") != null, "the Jira plugin jars aren't built");
    List<ConnectorProcessStatus.State> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
    AtomicReference<PluginSupervisor> self = new AtomicReference<>();
    self.set(new PluginSupervisor("jira-service-desk", launcher, clock,
        () -> seen.add(self.get().status().orElseThrow().state())));
    supervisor = self.get();
    supervisor.spec(spec("1.2.0", jiraJar("1.2.0")));

    try (PluginSupervisor.Permit permit = supervisor.permit(deadline(10))) {
      permit.returned();
    }
    supervisor.stop();

    assertThat(seen).containsSubsequence(ConnectorProcessStatus.State.STARTING, ConnectorProcessStatus.State.RUNNING,
        ConnectorProcessStatus.State.STOPPED);
  }

  /**
   * "tools ⊆ manifest" at every start, not only at an update (report 16 §4.4, review 2026-10-27): a generation whose
   * code reports a tool its manifest doesn't declare is refused, and its host doesn't stay behind.
   */
  @Test
  void aGenerationWhoseCodeReportsAToolItsManifestDoesntDeclareIsRefusedAndItsHostStops() throws Exception {
    assumeTrue(jiraJar("1.3.0") != null, "the Jira plugin jars aren't built");
    HostLauncher.Spec real = spec("1.3.0", jiraJar("1.3.0"));
    java.util.Map<String, String> declared = new java.util.HashMap<>(real.toolHashes());
    declared.remove("jira_request_sla");
    supervisor.spec(new HostLauncher.Spec(real.id(), real.version(), real.jar(), real.sha256(), real.maxHeapMb(),
        declared, List.of()));

    assertThatThrownBy(() -> supervisor.permit(deadline(30)).close())
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.getMessage())
            .contains("jira_request_sla isn't the tool the manifest declares"));
    waitFor(() -> launcher.pids().live().isEmpty());
    assertThat(launcher.pids().live()).as("the refused host was stopped").isEmpty();

    // The manifest's own tools: the same JAR starts.
    supervisor.spec(real);
    supervisor.restart();
    assertThat(supervisor.running()).isTrue();
  }

  /**
   * The housekeeping round (report 16 §3.4, review 2026-10-27: nothing ever called the supervisors' ping and idle
   * sweep, so a host ran until the core stopped): a running host is pinged and stays, an idle one is stopped once its
   * idle time has passed, and the next call starts it again.
   */
  @Test
  void theHousekeepingRoundStopsAHostIdlePastItsTime() throws Exception {
    assumeTrue(jiraJar("1.2.0") != null, "the Jira plugin jars aren't built");
    HostLauncher.Spec v12 = spec("1.2.0", jiraJar("1.2.0"));
    PluginConnectorSource source = new PluginConnectorSource(launcher, new SecretCallbackRegistry(),
        new org.springframework.beans.factory.ObjectProvider<>() {
          @Override
          public com.gaterook.core.connectors.EnvironmentPolicies getObject() {
            return null;
          }
        }, null, clock, new com.gaterook.core.platform.EventBus(clock));
    source.register(manifestOf(v12.jar()).connectorManifest(), v12, "info", 15, true, false);
    supervisor = source.supervisor("jira-service-desk");
    HostProcess host;
    try (PluginSupervisor.Permit permit = supervisor.permit(deadline(10))) {
      host = permit.host();
      permit.returned();
    }

    clock.advance(Duration.ofMinutes(14));
    source.sweep();
    assertThat(supervisor.running()).as("pinged, not idle long enough").isTrue();
    assertThat(host.stuck()).isZero();

    clock.advance(Duration.ofMinutes(2));
    source.sweep();
    assertThat(supervisor.running()).as("idle past its 15 minutes").isFalse();
    waitFor(() -> !host.alive());
    assertThat(host.alive()).isFalse();

    try (PluginSupervisor.Permit permit = supervisor.permit(deadline(10))) {
      assertThat(permit.host()).isNotSameAs(host);
      permit.returned();
    }
  }

  @Test
  void aCrashBecomesABackoffAndThenFailed() throws Exception {
    assumeTrue(fixtureJar("crash") != null, "the crash fixture isn't built");
    supervisor = new PluginSupervisor("fixture-crash", launcher, clock);
    supervisor.spec(spec("crash", fixtureJar("crash"), "fixture-crash", "1.0.0"));

    for (int attempt = 0; attempt < PluginSupervisor.CRASHES_TO_FAIL; attempt++) {
      HostProcess host;
      try (PluginSupervisor.Permit permit = supervisor.permit(deadline(10))) {
        host = permit.host();
        // Make it crash: the call never returns, the process exits 3.
        host.request(Gpp.CONNECTOR_EXECUTE, crashCall()).future();
        host.process().waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        permit.returned();
      }
      // The supervisor hears the exit; the next attempt is within a backoff.
      waitFor(() -> supervisor.status().map(ConnectorProcessStatus.ProcessInfo::state)
          .filter(s -> s == ConnectorProcessStatus.State.BACKOFF || s == ConnectorProcessStatus.State.FAILED)
          .isPresent());
      // Move past the backoff so the next start is allowed (until the fifth crash).
      clock.advance(Duration.ofMinutes(2));
    }
    // Five crashes in ten minutes: Failed, and a call is refused at once.
    clock.set(Instant.parse("2026-10-25T00:00:00Z"));
    assertThat(supervisor.status().orElseThrow().state()).isEqualTo(ConnectorProcessStatus.State.FAILED);
    assertThatThrownBy(() -> supervisor.permit(deadline(2)).close())
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.getMessage()).contains("keeps crashing"));
  }

  /**
   * A plugin's own sockets (a JDBC driver, a raw socket, an address literal: nothing the egress client or the resolver
   * hook sees) can neither reach the core's listeners nor take one over, while a port the core doesn't own stays open
   * (a database the plugin serves). The OS sandbox the core starts the host in closes them: a profile for
   * {@code sandbox-exec} on macOS, Landlock's TCP rules on Linux (ABI 4 or later). Windows' low integrity doesn't
   * judge sockets: there the core refuses requests from a sandboxed process itself (SandboxedPeerFilterIT).
   */
  @Test
  void aPluginsOwnSocketsCantReachOrTakeTheCoresPorts() throws Exception {
    assumeTrue(fixtureJar("secret-thief") != null, "the secret-thief fixture isn't built");
    assumeTrue(launcher.sandboxKind() != null, "no OS sandbox on this system");
    assumeFalse(HostLauncher.LOW_INTEGRITY.equals(launcher.sandboxKind()),
        "low integrity doesn't judge sockets: the core refuses sandboxed peers itself");
    InetAddress loopback = InetAddress.getByName("127.0.0.1");
    try (ServerSocket core = new ServerSocket(0, 50, loopback);
        ServerSocket database = new ServerSocket(0, 50, loopback)) {
      for (ServerSocket listener : List.of(core, database)) {
        Thread.ofVirtual().start(() -> {
          while (!listener.isClosed()) {
            try {
              listener.accept().close();
            } catch (java.io.IOException e) {
              // Closed at the end of the test.
            }
          }
        });
      }
      int notListening;
      try (ServerSocket probe = new ServerSocket(0, 1, loopback)) {
        notListening = probe.getLocalPort();
      }
      corePorts.set(Set.of(core.getLocalPort(), notListening));
      supervisor = new PluginSupervisor("fixture-secret-thief", launcher, clock);
      supervisor.spec(spec("secret-thief", fixtureJar("secret-thief"), "fixture-secret-thief", "1.0.0"));

      try (PluginSupervisor.Permit permit = supervisor.permit(deadline(30))) {
        assumeFalse(permit.host().log().tail().stream().anyMatch(l -> l.contains("can't close TCP ports")),
            "this kernel's Landlock has no network rules (ABI 4, Linux 6.7)");
        tools.jackson.databind.node.ObjectNode args = GppJson.object().put("corePort", core.getLocalPort())
            .put("bindPort", notListening).put("openPort", database.getLocalPort());
        tools.jackson.databind.JsonNode result = permit.host().request(Gpp.CONNECTOR_EXECUTE,
            execute("fixture-secret-thief", "thief_probe", args)).get(Duration.ofSeconds(30));
        permit.returned();
        tools.jackson.databind.JsonNode out = WireCodec.result(result).structuredContent();

        // 127.0.0.1, localhost, the mapped address and the wildcard (which connects to this computer): all refused.
        assertThat(out.path("coreSockets").asString()).contains("127.0.0.1 ", "localhost ", "0.0.0.0 ")
            .doesNotContain("UNEXPECTEDLY");
        assertThat(out.path("coreBind").asString()).doesNotContain("UNEXPECTEDLY", "not asked");
        assertThat(out.path("openSocket").asString()).isEqualTo("reached");
      }
    }
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  /** A {@code connector/execute} of {@code tool} with {@code args}; its token is unknown: no secret is handed out. */
  private tools.jackson.databind.JsonNode execute(String id, String tool, tools.jackson.databind.node.ObjectNode args) {
    return new com.gaterook.plugin.protocol.Messages.Execute(id + "@1.0.0", "ct_dummy",
        new com.gaterook.connector.api.ToolCall("tc_1", tool, "c1", args, null),
        new com.gaterook.connector.api.ConnectionConfig("c1", "c1", id, com.gaterook.connector.api.Env.DEV,
            1, GppJson.object(), java.util.Set.of(), null), 20_000, false, null, null, null, limits(), null, null,
        com.gaterook.plugin.protocol.Messages.EgressGrant.NONE).write();
  }

  private tools.jackson.databind.JsonNode crashCall() {
    // A connector/execute of crash_now; no secret is asked, so the dummy token is never used.
    return new com.gaterook.plugin.protocol.Messages.Execute("fixture-crash@1.0.0", "ct_dummy",
        new com.gaterook.connector.api.ToolCall("tc_1", "crash_now", "c1", GppJson.object(), null),
        connection(), 5_000, false, null, null, null, limits(), null, null,
        com.gaterook.plugin.protocol.Messages.EgressGrant.NONE).write();
  }

  private static com.gaterook.connector.api.Limits limits() {
    return new com.gaterook.connector.api.Limits(1000, 1000, 524_288, 1_048_576, 262_144, 65_536, 8192,
        Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofHours(168));
  }

  private com.gaterook.connector.api.ConnectionConfig connection() {
    return new com.gaterook.connector.api.ConnectionConfig("c1", "c1", "fixture-crash",
        com.gaterook.connector.api.Env.DEV, 1, GppJson.object(), java.util.Set.of(), null);
  }

  private HostLauncher.Spec spec(String version, Path jar) throws Exception {
    return spec(version, jar, "jira-service-desk", version);
  }

  private HostLauncher.Spec spec(String version, Path jar, String id, String realVersion) throws Exception {
    Path dest = paths.jar(id, realVersion);
    Files.createDirectories(dest.getParent());
    Files.copy(jar, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    com.gaterook.plugin.protocol.PluginManifest manifest = manifestOf(dest);
    return new HostLauncher.Spec(id, realVersion, dest, PluginInstaller.sha256(dest), 128,
        manifest.definitionHashes(), List.of());
  }

  /** The manifest of a plugin JAR, as the core reads it at install. */
  static com.gaterook.plugin.protocol.PluginManifest manifestOf(Path jar) throws Exception {
    try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
      try (var in = zip.getInputStream(zip.getEntry(com.gaterook.plugin.protocol.PluginManifest.FILE))) {
        return com.gaterook.plugin.protocol.PluginManifest.parse(GppJson.MAPPER.readTree(in.readAllBytes()));
      }
    }
  }


  private java.time.Instant deadline(int seconds) {
    return clock.instant().plusSeconds(seconds);
  }

  private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
    for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) {
      Thread.sleep(50);
    }
  }

  private static Path hostJar() {
    String prop = System.getProperty("gateway.pluginHostJar");
    if (prop != null && Files.isRegularFile(Path.of(prop))) {
      return Path.of(prop);
    }
    Path p = Path.of("..", "plugin-host", "target", "plugin-host.jar");
    return Files.isRegularFile(p) ? p : null;
  }

  private static Path jiraJar(String version) {
    Path p = Path.of("..", "plugins", "jira-service-desk", "target",
        "plugins-jira-service-desk-" + System.getProperty("gateway.build.version") + "-" + version + ".jar");
    return Files.isRegularFile(p) ? p : null;
  }

  private static Path fixtureJar(String name) {
    Path p = Path.of("..", "plugins", "test-fixtures", "target",
        "plugins-test-fixtures-" + System.getProperty("gateway.build.version") + "-" + name + ".jar");
    return Files.isRegularFile(p) ? p : null;
  }

  /** A clock the test moves to exercise backoff and idle windows. */
  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> now;

    MutableClock(Instant start) {
      this.now = new AtomicReference<>(start);
    }

    void advance(Duration by) {
      now.updateAndGet(i -> i.plus(by));
    }

    void set(Instant i) {
      now.set(i);
    }

    @Override
    public Instant instant() {
      return now.get();
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }

  private static final class FixedKeys implements HmacKeys {
    @Override
    public byte[] mac(Purpose purpose, byte[] data) {
      try {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(("k-" + purpose.name()).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(data);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
