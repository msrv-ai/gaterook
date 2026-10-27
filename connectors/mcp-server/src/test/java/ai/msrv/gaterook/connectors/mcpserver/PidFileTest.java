package ai.msrv.gaterook.connectors.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code run/mcp-pids.json} between two runs of the core: what the previous run left is read when the runtime attaches,
 * so a server started before the background clean-up gets to it is neither stopped by it nor hides the orphans.
 */
class PidFileTest {

  @TempDir
  Path home;

  @Test
  void aServerStartedBeforeTheCleanUpKeepsRunningAndTheOrphansStillStop() throws Exception {
    Process orphan = new ProcessBuilder("sleep", "600").start();
    Process fresh = new ProcessBuilder("sleep", "600").start();
    try {
      Path file = home.resolve("run/mcp-pids.json");
      Files.createDirectories(file.getParent());
      long start = orphan.toHandle().info().startInstant().map(Instant::toEpochMilli).orElseThrow();
      Files.writeString(file, "{\"cn_old\":{\"pid\":" + orphan.pid() + ",\"start\":" + start + "}}");

      PidFile pids = new PidFile(file);
      // A server of this run starts before the background clean-up runs.
      pids.record("cn_new", fresh.toHandle());
      int stopped = pids.reapOrphans();

      assertThat(stopped).isEqualTo(1);
      assertThat(orphan.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).as("the orphan stopped").isTrue();
      Thread.sleep(Duration.ofMillis(200));
      assertThat(fresh.isAlive()).as("this run's server keeps running").isTrue();
      assertThat(Files.readString(file)).contains("cn_new").doesNotContain("cn_old");
    } finally {
      orphan.destroyForcibly();
      fresh.destroyForcibly();
    }
  }
}
