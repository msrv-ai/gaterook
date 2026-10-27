package ai.msrv.gaterook.core.platform.boot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sizes the virtual-thread scheduler before the first virtual thread exists (it reads its system properties once).
 *
 * <p>Why: SQLite waits for locks inside native code ({@code busy_timeout} in {@code sqlite3_step}), which pins the
 * carrier thread, and long reads pin theirs for the whole query. With every pooled connection busy in native code,
 * {@code pools} carriers are pinned; at least two more must stay free for Tomcat, long polls of
 * {@code approval_status} and SSE (report 14 §9.2, ARCHITECTURE.md M1.2). A smaller value set on the command line is
 * raised, a larger one kept. {@link #carriersAtOnce} then proves the scheduler honoured it — the same check runs in
 * the native image, whose runtime implements the scheduler itself.
 */
final class VirtualThreadScheduler {

  static final String PARALLELISM = "jdk.virtualThreadScheduler.parallelism";

  /** What the scheduler will use, and what was asked for. */
  record Parallelism(int effective, Integer requested, int minimum) {

    boolean raised() {
      return requested != null && requested < effective;
    }
  }

  private VirtualThreadScheduler() {}

  /**
   * How many virtual threads really run at the same time, up to {@code wanted}: each started thread spins (it never
   * parks, so it keeps its carrier) until all {@code wanted} have arrived or {@code patience} is over. With enough
   * carriers this takes the time to start them, about a millisecond; with too few, the first ones to arrive hold every
   * carrier until they give up, and their count is the answer. Run it before the application starts: it occupies
   * every carrier while it spins.
   */
  static int carriersAtOnce(int wanted, Duration patience) {
    AtomicInteger arrived = new AtomicInteger();
    AtomicInteger atGiveUp = new AtomicInteger(-1);
    long deadline = System.nanoTime() + patience.toNanos();
    List<Thread> threads = new ArrayList<>(wanted);
    for (int i = 0; i < wanted; i++) {
      threads.add(Thread.ofVirtual().name("gateway-carrier-check-" + i).start(() -> {
        if (atGiveUp.get() >= 0) {
          return; // arrived after the check gave up: it didn't run together with the others
        }
        arrived.incrementAndGet();
        while (arrived.get() < wanted) {
          if (System.nanoTime() - deadline > 0) {
            atGiveUp.compareAndSet(-1, arrived.get());
            return;
          }
          Thread.onSpinWait();
        }
      }));
    }
    for (Thread thread : threads) {
      try {
        thread.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    int gaveUpAt = atGiveUp.get();
    return gaveUpAt >= 0 ? gaveUpAt : Math.min(arrived.get(), wanted);
  }

  /** Ensures a parallelism of at least {@code minimum}; call before anything creates a virtual thread. */
  static Parallelism ensureAtLeast(int minimum) {
    Integer requested = null;
    String value = System.getProperty(PARALLELISM);
    if (value != null) {
      try {
        requested = Integer.parseInt(value.trim());
      } catch (NumberFormatException e) {
        requested = 0;
      }
    }
    int effective = requested != null ? requested : Runtime.getRuntime().availableProcessors();
    if (effective < minimum) {
      effective = minimum;
    }
    System.setProperty(PARALLELISM, Integer.toString(effective));
    return new Parallelism(effective, requested, minimum);
  }
}
