package ai.msrv.gaterook.core.audit.internal;

import ai.msrv.gaterook.core.audit.AuditWriter;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Writes the rows of {@link AuditBacklog} once the database takes writes again, oldest first, every second; then the
 * writes that belong with them, in order.
 */
@Component
class BacklogFlusher implements DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(BacklogFlusher.class);

  private final AuditBacklog backlog;
  private final AuditWriter writer;
  private final Object flushing = new Object();
  private ScheduledExecutorService timer;

  BacklogFlusher(AuditBacklog backlog, AuditWriter writer) {
    this.backlog = backlog;
    this.writer = writer;
  }

  @EventListener(ApplicationReadyEvent.class)
  synchronized void start() {
    if (timer == null) {
      timer = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("audit-backlog").factory());
      timer.scheduleWithFixedDelay(this::flush, 1, 1, TimeUnit.SECONDS);
    }
  }

  /** Writes what waits; stops at the first write that still fails, so the order is kept. */
  void flush() {
    synchronized (flushing) {
      flushInOrder();
    }
  }

  private void flushInOrder() {
    while (true) {
      Optional<AuditBacklog.Pending> next = backlog.peek();
      if (next.isEmpty()) {
        break;
      }
      try {
        writer.writeQueued(next.get().row(), next.get().traceId());
      } catch (RuntimeException e) {
        log.debug("Audit backlog still can't write: {}", e.toString());
        return;
      }
      backlog.remove(next.get());
      log.info("Wrote the waiting completed row of {}; {} still waiting", next.get().row().callId(), backlog.size());
    }
    // The rows first: once a request says how it ended, its audit row already does.
    while (true) {
      Optional<AuditBacklog.Retry> next = backlog.peekRetry();
      if (next.isEmpty()) {
        return;
      }
      try {
        next.get().write().run();
      } catch (RuntimeException e) {
        log.debug("Still can't write {}: {}", next.get().what(), e.toString());
        return;
      }
      backlog.remove(next.get());
      log.info("Wrote {}; {} still waiting", next.get().what(), backlog.size());
    }
  }

  @Override
  public synchronized void destroy() throws InterruptedException {
    if (timer != null) {
      timer.shutdownNow();
      timer.awaitTermination(5, TimeUnit.SECONDS);
      timer = null;
    }
    flush();
  }
}
