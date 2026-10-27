package ai.msrv.gaterook.core.connectors.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code run/plugin-hosts.json} (report 16 §3.4, review 2026-10-27): the core used to read the file at start and kill
 * every live pid in it, but nothing ever wrote it — so a host left behind by a killed core was never stopped, and a
 * pid alone would have named someone else's process after a reboot. Now every host is recorded with its start time,
 * forgotten when it exits, and a previous run's host is stopped only when both still match.
 */
class HostPidsTest {

  @TempDir
  Path run;

  @Test
  void aPreviousRunsHostIsStoppedAndAProcessWithAnotherStartTimeIsLeftAlone() throws Exception {
    assumeTrue(Files.isExecutable(Path.of("/bin/sleep")), "needs /bin/sleep");
    Path file = run.resolve("plugin-hosts.json");
    Process orphan = new ProcessBuilder("/bin/sleep", "60").start();
    Process stranger = new ProcessBuilder("/bin/sleep", "60").start();
    try {
      HostPids previousRun = new HostPids(file);
      previousRun.record(orphan.toHandle());
      assertThat(previousRun.live()).containsExactly(orphan.pid());
      long strangerStart = stranger.toHandle().info().startInstant().map(Instant::toEpochMilli).orElse(0L);
      // The stranger's pid with another start time: after a reboot its number belongs to someone else.
      String recorded = Files.readString(file).replace("]", ",{\"pid\":" + stranger.pid() + ",\"start\":"
          + (strangerStart - 60_000) + "}]");
      Files.writeString(file, recorded);

      HostPids nextRun = new HostPids(file);
      assertThat(nextRun.reapOrphans()).isEqualTo(1);
      assertThat(orphan.waitFor(5, TimeUnit.SECONDS)).as("the orphan host is stopped").isTrue();
      assertThat(stranger.isAlive()).as("a process the record doesn't really name is left alone").isTrue();
      assertThat(Files.readString(file)).isEqualTo("[]");
    } finally {
      orphan.destroyForcibly();
      stranger.destroyForcibly();
    }
  }

  @Test
  void aHostIsForgottenWhenItExits() throws Exception {
    assumeTrue(Files.isExecutable(Path.of("/bin/sleep")), "needs /bin/sleep");
    HostPids pids = new HostPids(run.resolve("plugin-hosts.json"));
    Process p = new ProcessBuilder("/bin/sleep", "60").start();
    pids.record(p.toHandle());
    assertThat(Files.readString(run.resolve("plugin-hosts.json"))).contains("\"pid\":" + p.pid());
    p.destroyForcibly().waitFor();
    pids.forget(p.pid());
    assertThat(pids.live()).isEmpty();
    assertThat(Files.readString(run.resolve("plugin-hosts.json"))).isEqualTo("[]");
  }
}
