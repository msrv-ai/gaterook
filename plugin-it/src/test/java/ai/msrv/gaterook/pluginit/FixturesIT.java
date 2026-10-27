package ai.msrv.gaterook.pluginit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.plugin.protocol.Gpp;
import ai.msrv.gaterook.plugin.protocol.GppJson;
import ai.msrv.gaterook.plugin.protocol.JsonRpcPeer;
import ai.msrv.gaterook.plugin.protocol.Messages;
import ai.msrv.gaterook.plugin.protocol.WireCodec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The misbehaving plugins against a real host (ARCHITECTURE.md §10 K4, report 16 §10): a crash, an OOM, a hang, a
 * stdout polluter and a secret thief, plus the two ways the core's death reaches the host — the stdin EOF and a
 * {@code kill -9} of the parent. Each proves the boundary the architecture promises: a plugin's fault ends its own
 * process and nothing else, non-JSON on the channel is skipped, and a plugin can't read MCP Gateway's home.
 */
class FixturesIT {

  private static final boolean LINUX = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux");

  @TempDir
  Path home;

  private Harness host;

  @AfterEach
  void stop() {
    if (host != null) {
      host.close();
    }
  }

  @Test
  void aCrashEndsOnlyItsOwnProcessAndFailsTheCallInFlight() throws Exception {
    host = Harness.start("fixture-crash@1.0.0", Harness.fixtureJar("crash"), home, true);
    JsonNode echo = host.execute("fixture-crash@1.0.0", call("crash_echo", "{\"text\":\"hi\"}"), cfg(), false,
        Messages.EgressGrant.NONE, Map.of(), Duration.ofSeconds(5));
    assertThat(WireCodec.result(echo).content().getFirst().text()).contains("echo hi");

    assertThatThrownBy(() -> host.execute("fixture-crash@1.0.0", call("crash_now", "{}"), cfg(), false,
        Messages.EgressGrant.NONE, Map.of(), Duration.ofSeconds(5)))
        .isInstanceOf(Harness.ProtocolError.class);
    assertThat(host.process().waitFor(5, TimeUnit.SECONDS)).isTrue();
    assertThat(host.process().exitValue()).isEqualTo(3);
  }

  @Test
  void anOutOfMemoryEndsTheProcess() throws Exception {
    host = Harness.start("fixture-oom@1.0.0", Harness.fixtureJar("oom"), home, true);
    assertThatThrownBy(() -> host.execute("fixture-oom@1.0.0", call("oom_fill", "{}"), cfg(), false,
        Messages.EgressGrant.NONE, Map.of(), Duration.ofSeconds(15)))
        .isInstanceOf(Harness.ProtocolError.class);
    assertThat(host.process().waitFor(15, TimeUnit.SECONDS)).isTrue();
    assertThat(host.process().exitValue()).isNotZero();
  }

  @Test
  void aHangIsCancelledAndTheProcessEndsOnEof() throws Exception {
    host = Harness.start("fixture-hang@1.0.0", Harness.fixtureJar("hang"), home, true);
    JsonRpcPeer.Call<JsonNode> stuck = host.executeAsync("fixture-hang@1.0.0", call("hang_forever", "{}"), cfg(),
        Messages.EgressGrant.NONE, Map.of(), Duration.ofSeconds(2));
    assertThatThrownBy(() -> stuck.get(Duration.ofSeconds(1))).isInstanceOf(java.util.concurrent.TimeoutException.class);
    host.peer().cancel(stuck, "deadline");
    // The call ignores interrupts, but closing the pipe ends the process anyway.
    host.closeStdin();
    assertThat(host.process().waitFor(5, TimeUnit.SECONDS)).as("EOF ends even a hung host").isTrue();
  }

  @Test
  void nonJsonOnTheChannelIsSkippedAndTheCallStillAnswers() throws Exception {
    host = Harness.start("fixture-stdout-polluter@1.0.0", Harness.fixtureJar("stdout-polluter"), home, true);
    JsonNode result = host.execute("fixture-stdout-polluter@1.0.0", call("polluter_echo", "{\"lines\":5}"), cfg(),
        false, Messages.EgressGrant.NONE, Map.of(), Duration.ofSeconds(5));
    assertThat(WireCodec.result(result).content().getFirst().text()).contains("still answered");
    assertThat(host.alive()).isTrue();
  }

  @Test
  void aSecretThiefIsRefusedTheSecretAndTheFiles() throws Exception {
    assumeTrue(Harness.sandboxKind() != null, "The OS sandbox is Landlock on Linux or sandbox-exec on macOS");
    // The files the thief tries to read, two folders above its data dir (the gateway home).
    Files.writeString(home.resolve("master.keyset"), "cnry-master-key-material");
    Files.writeString(home.resolve("gateway.db"), "cnry-database-bytes");

    host = Harness.start("fixture-secret-thief@1.0.0", Harness.fixtureJar("secret-thief"), home, true);
    assertThat(host.loaded().sandbox()).as("the host runs in the sandbox it was started in")
        .isEqualTo(Harness.sandboxKind());
    // A place outside its data folder and the gateway home: a login item or a shell profile would be one.
    Path outside = Files.createTempDirectory("thief-outside").resolve("planted.txt");
    JsonNode result = host.execute("fixture-secret-thief@1.0.0", call("thief_probe",
        "{\"writeProbe\":\"" + outside + "\"}"), cfg(), false, Messages.EgressGrant.NONE,
        Map.of("/password", "cnry-the-plugins-own-secret"), Duration.ofSeconds(10));
    JsonNode out = WireCodec.result(result).structuredContent();

    assertThat(out.path("foreignSecret").asString()).startsWith("denied:");
    // Read from the call's thread, a platform thread, the common pool and virtual threads: denied on every one.
    assertThat(out.path("gatewayHome").asString()).doesNotContain("UNEXPECTEDLY READ").contains("master.keyset");
    assertThat(out.path("ownData").asString()).isEqualTo("writable");
    // Nothing written outside its folder (review 2026-10-27: on macOS the profile only kept reads away).
    assertThat(out.path("outsideWrite").asString()).doesNotContain("UNEXPECTEDLY WROTE");
    assertThat(outside).doesNotExist();
    if (LINUX) {
      assertThat(out.path("coreEnviron").asString()).doesNotContain("UNEXPECTEDLY READ");
    }
    // The master key and database bytes never reached the plugin or its result.
    assertThat(result.toString()).doesNotContain("cnry-master-key-material", "cnry-database-bytes");
  }

  @Test
  void stdinEofEndsTheHostQuickly() throws Exception {
    host = Harness.start("fixture-crash@1.0.0", Harness.fixtureJar("crash"), home, true);
    long t0 = System.nanoTime();
    host.closeStdin();
    assertThat(host.process().waitFor(2, TimeUnit.SECONDS)).isTrue();
    long ms = (System.nanoTime() - t0) / 1_000_000;
    assertThat(ms).as("the host exits soon after EOF").isLessThan(2_000);
  }

  @Test
  void killOfTheParentMakesTheHostExitByEof() throws Exception {
    // A helper JVM is the host's parent; it holds the only writing end of the host's stdin. When it is killed
    // with SIGKILL, the host sees EOF and exits, with no orphan left behind.
    Path hostJar = Path.of(System.getProperty("gateway.pluginHostJar"));
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");
    Path marker = home.resolve("host.pid");
    Process parent = new ProcessBuilder(java.toString(), "-cp",
        System.getProperty("java.class.path"), ParentLauncher.class.getName(), hostJar.toString(),
        marker.toString()).inheritIO().start();

    long hostPid = waitForPid(marker);
    ProcessHandle hostHandle = ProcessHandle.of(hostPid).orElseThrow();
    assertThat(hostHandle.isAlive()).isTrue();

    // SIGKILL the parent; it can't close anything on the way out.
    parent.destroyForcibly();
    assertThat(parent.waitFor(5, TimeUnit.SECONDS)).isTrue();

    boolean exited = false;
    for (int i = 0; i < 50 && hostHandle.isAlive(); i++) {
      Thread.sleep(100);
    }
    assertThat(hostHandle.isAlive()).as("the host exits on EOF after the parent is killed").isFalse();
  }

  private static long waitForPid(Path marker) throws Exception {
    for (int i = 0; i < 100; i++) {
      if (Files.isRegularFile(marker) && Files.size(marker) > 0) {
        try {
          return Long.parseLong(Files.readString(marker).strip());
        } catch (NumberFormatException e) {
          // Not written fully yet.
        }
      }
      Thread.sleep(100);
    }
    throw new AssertionError("The parent didn't report the host pid");
  }

  private static ToolCall call(String tool, String args) {
    return new ToolCall("tc_" + Long.toHexString(System.nanoTime()), tool, "c1",
        (ObjectNode) GppJson.MAPPER.readTree(args), null);
  }

  private static ConnectionConfig cfg() {
    return new ConnectionConfig("c1", "c1", id(), Env.DEV, 1, GppJson.object(), Set.of("/password"), null);
  }

  private static String id() {
    return "fixture";
  }
}
