package ai.msrv.gaterook.core.secrets.internal;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/**
 * Shreds expired previous secret versions (report 12 §6: kept 7 days) once the core is up and then every
 * {@code interval}: a desktop core runs for weeks from the tray, and a replaced value must not stay decryptable long
 * after its 7 days. Replace shreds as well, this covers the time in between.
 */
final class PreviousVersionShredder implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(PreviousVersionShredder.class);

  private final Runnable shred;
  private final Duration interval;
  private ScheduledExecutorService executor;

  PreviousVersionShredder(Runnable shred, Duration interval) {
    this.shred = shred;
    this.interval = interval;
  }

  @Override
  public synchronized void onApplicationEvent(ApplicationReadyEvent event) {
    if (executor != null) {
      return;
    }
    // One virtual thread that parks between runs: no platform thread is held for an hourly job.
    executor = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("secret-shredder").factory());
    executor.scheduleWithFixedDelay(this::runOnce, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
  }

  void runOnce() {
    try {
      shred.run();
    } catch (RuntimeException e) {
      // A busy database: the next run tries again.
      log.debug("Previous secret versions not cleaned up: {}", e.getMessage());
    }
  }

  @Override
  public synchronized void destroy() throws InterruptedException {
    if (executor == null) {
      return;
    }
    // A run in progress finishes its transaction; the next one is cancelled.
    executor.shutdown();
    if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
      executor.shutdownNow();
    }
    executor = null;
  }
}
