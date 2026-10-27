package ai.msrv.gaterook.core.secrets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PreviousVersionShredderTest {

  @Test
  void runsAtStartAndThenOnScheduleUntilTheCoreStops() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    PreviousVersionShredder shredder = new PreviousVersionShredder(() -> {
      if (runs.incrementAndGet() == 2) {
        throw new IllegalStateException("database busy");
      }
    }, Duration.ofMillis(20));

    shredder.onApplicationEvent(null);
    shredder.onApplicationEvent(null);
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (runs.get() < 4 && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(runs.get()).as("a failed run doesn't stop the schedule").isGreaterThanOrEqualTo(4);

    shredder.destroy();
    int stopped = runs.get();
    Thread.sleep(100);
    assertThat(runs.get()).isEqualTo(stopped);
  }
}
