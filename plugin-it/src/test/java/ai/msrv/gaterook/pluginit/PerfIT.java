package ai.msrv.gaterook.pluginit;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.plugin.protocol.Gpp;
import ai.msrv.gaterook.plugin.protocol.GppJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The performance gates of report 16 §10.5, measured against a real host with the crash fixture loaded. The machine is
 * shared, so the bounds asserted here are the generous "no AOT cache" ones and the measured medians are printed; a CI
 * run on a quiet machine with the AOT cache tightens them. What matters for K4 is that the host starts in well under a
 * second, a round trip is a fraction of a millisecond, and the host leaves within a breath of EOF.
 */
class PerfIT {

  private static final boolean LINUX = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux");

  @TempDir
  Path home;

  @Test
  void spawnToReadyOverheadAndEofExitAreWithinBudget() throws Exception {
    // Spawn to ready: the median of a few cold starts (no AOT cache here).
    List<Long> ready = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      Path h = Files.createDirectories(home.resolve("ready" + i));
      try (Harness host = Harness.start("fixture-crash@1.0.0", Harness.fixtureJar("crash"), h, false)) {
        ready.add(host.readyMs());
      }
    }
    long readyMedian = median(ready);
    System.out.println("[perf] spawn->ready median " + readyMedian + " ms over " + ready);
    assertThat(readyMedian).as("spawn to ready without an AOT cache").isLessThan(1_500);

    // The same inside the OS sandbox: on Linux it adds the first stage (a small JVM that execs the host).
    if (Harness.sandboxKind() != null) {
      List<Long> sandboxed = new ArrayList<>();
      for (int i = 0; i < 5; i++) {
        Path h = Files.createDirectories(home.resolve("sandboxed" + i));
        try (Harness host = Harness.start("fixture-crash@1.0.0", Harness.fixtureJar("crash"), h, true)) {
          sandboxed.add(host.readyMs());
        }
      }
      long sandboxedMedian = median(sandboxed);
      System.out.println("[perf] spawn->ready in " + Harness.sandboxKind() + " median " + sandboxedMedian + " ms over "
          + sandboxed);
      assertThat(sandboxedMedian).as("spawn to ready in the sandbox, without an AOT cache").isLessThan(1_500);
    }

    // Round-trip overhead: host/ping has no plugin work, so it measures the channel.
    try (Harness host = Harness.start("fixture-crash@1.0.0", Harness.fixtureJar("crash"),
        Files.createDirectories(home.resolve("ping")), false)) {
      List<Long> rtt = new ArrayList<>();
      for (int i = 0; i < 300; i++) {
        long t0 = System.nanoTime();
        host.peer().request(Gpp.HOST_PING, GppJson.object()).get(Duration.ofSeconds(5));
        rtt.add((System.nanoTime() - t0) / 1_000);
      }
      long p95us = percentile(rtt, 95);
      System.out.println("[perf] ping p95 " + p95us + " us, idle RSS " + rssMb(host) + " MB");
      assertThat(p95us).as("core<->host round trip p95 (microseconds)").isLessThan(25_000);
      if (LINUX) {
        Integer rss = rssMb(host);
        if (rss != null) {
          assertThat(rss).as("idle host RSS (MB), report 16 §10.5: at most 80").isLessThanOrEqualTo(80);
        }
      }

      // EOF exit: closing the pipe ends the host promptly.
      long t0 = System.nanoTime();
      host.closeStdin();
      assertThat(host.process().waitFor(2, TimeUnit.SECONDS)).isTrue();
      long eofMs = (System.nanoTime() - t0) / 1_000_000;
      System.out.println("[perf] EOF->exit " + eofMs + " ms");
      assertThat(eofMs).as("the host exits soon after EOF").isLessThan(2_000);
    }
  }

  private static Integer rssMb(Harness host) {
    if (!LINUX) {
      return null;
    }
    Path status = Path.of("/proc", String.valueOf(host.process().pid()), "status");
    try {
      for (String line : Files.readAllLines(status)) {
        if (line.startsWith("VmRSS:")) {
          return (int) (Long.parseLong(line.split("\\s+")[1]) / 1024);
        }
      }
    } catch (Exception e) {
      return null;
    }
    return null;
  }

  private static long median(List<Long> values) {
    List<Long> sorted = values.stream().sorted().toList();
    return sorted.get(sorted.size() / 2);
  }

  private static long percentile(List<Long> values, int p) {
    List<Long> sorted = values.stream().sorted().toList();
    return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1));
  }
}
