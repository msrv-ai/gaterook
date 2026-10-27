package ai.msrv.gaterook.core.platform.boot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The property logic only: whether the scheduler honours it is checked by {@code SqliteConcurrencyTest} (JVM) and the
 * process tests (JVM jar and native binary). Setting the property here is harmless: the scheduler of this JVM already
 * exists or reads it later with a value at least as large.
 */
class VirtualThreadSchedulerTest {

  private String saved;

  @BeforeEach
  void save() {
    saved = System.getProperty(VirtualThreadScheduler.PARALLELISM);
  }

  @AfterEach
  void restore() {
    if (saved == null) {
      System.clearProperty(VirtualThreadScheduler.PARALLELISM);
    } else {
      System.setProperty(VirtualThreadScheduler.PARALLELISM, saved);
    }
  }

  @Test
  void aSmallerRequestIsRaisedToTheMinimum() {
    System.setProperty(VirtualThreadScheduler.PARALLELISM, "2");

    VirtualThreadScheduler.Parallelism p = VirtualThreadScheduler.ensureAtLeast(6);

    assertThat(p.effective()).isEqualTo(6);
    assertThat(p.raised()).isTrue();
    assertThat(System.getProperty(VirtualThreadScheduler.PARALLELISM)).isEqualTo("6");
  }

  @Test
  void aLargerRequestIsKept() {
    System.setProperty(VirtualThreadScheduler.PARALLELISM, "16");

    assertThat(VirtualThreadScheduler.ensureAtLeast(6).effective()).isEqualTo(16);
  }

  @Test
  void theCarrierCheckSeesThreadsRunningTogether() {
    // This JVM's scheduler has one carrier per CPU (4 here, at least 2 on any CI runner).
    assertThat(VirtualThreadScheduler.carriersAtOnce(2, java.time.Duration.ofSeconds(5))).isEqualTo(2);
  }

  @Test
  void withoutARequestTheCpuCountIsTheFloor() {
    System.clearProperty(VirtualThreadScheduler.PARALLELISM);

    VirtualThreadScheduler.Parallelism p = VirtualThreadScheduler.ensureAtLeast(6);

    assertThat(p.effective()).isEqualTo(Math.max(6, Runtime.getRuntime().availableProcessors()));
    assertThat(p.raised()).isFalse();
  }
}
