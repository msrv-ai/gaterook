package ai.msrv.gaterook.core.audit.export;

import ai.msrv.gaterook.core.audit.internal.AuditCommits;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * When each destination exports (kit Settings › Audit & retention: "destinations with independent schedules"):
 * <ul>
 *   <li>{@code stream} — a couple of seconds after agent call rows commit ({@link AuditCommits}), and once a minute
 *       anyway;</li>
 *   <li>{@code hourly} and {@code daily@HH:MM} — at the first scheduled time after the last attempt, in this
 *       computer's time zone; a time missed while the core was off is caught up right after the start;</li>
 *   <li>a failing destination is retried by itself, after as long as it has been failing (at least a minute, at most
 *       an hour), whatever its schedule; a batch left pending by a stop is finished first;</li>
 *   <li>a destination that never ran runs at once — its backlog now, then its schedule: a computer that is never on
 *       at 02:00 would otherwise never start a daily one — and so does one whose last attempt lies ahead of the clock
 *       (the clock went back: a run writes the time anew, instead of every export waiting for the clock).</li>
 * </ul>
 * Runs of one destination never overlap: the scheduled ones and Export now / Retry share its lock. Demo, paused and
 * changed-outside destinations never run.
 */
@Component
public class ExportScheduler implements DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(ExportScheduler.class);
  private static final Duration STREAM_DELAY = Duration.ofSeconds(2);
  private static final Duration STREAM_EVERY = Duration.ofMinutes(1);
  private static final Duration RETRY_MIN = Duration.ofMinutes(1);
  private static final Duration RETRY_MAX = Duration.ofHours(1);
  private static final Duration RUN_BUDGET = Duration.ofMinutes(5);

  private final Destinations destinations;
  private final ExportRunner runner;
  private final AuditCommits commits;
  private final Clock clock;
  private final ZoneId zone;
  private final Duration tick;
  private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
  /** Per destination: the commit count when its last run started, and whether that run left rows behind. */
  private final Map<String, Long> ranAtCommit = new ConcurrentHashMap<>();
  private final Map<String, Instant> ranAt = new ConcurrentHashMap<>();
  private final Map<String, Boolean> more = new ConcurrentHashMap<>();
  private final AtomicLong commitCount = new AtomicLong();
  private volatile List<Destinations.Destination> cached = List.of();
  private volatile Instant cachedAt = Instant.EPOCH;
  private ScheduledExecutorService timer;

  ExportScheduler(Destinations destinations, ExportRunner runner, AuditCommits commits, Clock clock,
      @Value("${gateway.audit.export.tick:2s}") Duration tick) {
    this.destinations = destinations;
    this.runner = runner;
    this.commits = commits;
    this.clock = clock;
    this.zone = ZoneId.systemDefault();
    this.tick = tick;
  }

  @EventListener(ApplicationReadyEvent.class)
  synchronized void start() {
    if (timer == null) {
      commits.follow(commitCount::incrementAndGet);
      timer = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("audit-export").factory());
      long every = Math.max(100, tick.toMillis());
      timer.scheduleWithFixedDelay(this::tickQuietly, every, every, TimeUnit.MILLISECONDS);
    }
  }

  /** Destinations changed (created, edited, deleted): read them again at the next tick. */
  public void refresh() {
    cachedAt = Instant.EPOCH;
  }

  /**
   * Export now / Retry: runs {@code id} here, after any run of it in progress, for up to {@code budget}; what is left
   * the scheduler sends next.
   *
   * @return what the run did, or empty when the destination is gone, paused, a demo or changed outside
   */
  public java.util.Optional<ExportRunner.Outcome> runNow(String id, Duration budget) throws InterruptedException {
    Destinations.Destination d = destinations.find(id).orElse(null);
    if (d == null || !d.runs()) {
      return java.util.Optional.empty();
    }
    ReentrantLock lock = lock(id);
    if (!lock.tryLock(budget.toMillis(), TimeUnit.MILLISECONDS)) {
      return java.util.Optional.empty();
    }
    try {
      return java.util.Optional.of(runLocked(d, budget));
    } finally {
      lock.unlock();
    }
  }

  void tickQuietly() {
    try {
      tick();
    } catch (RuntimeException e) {
      log.debug("Audit export tick failed: {}", e.toString());
    }
  }

  /** Starts every destination that is due, each on its own virtual thread. */
  void tick() {
    Instant now = clock.instant();
    if (now.isBefore(cachedAt) || Duration.between(cachedAt, now).compareTo(STREAM_EVERY) >= 0) {
      cached = destinations.all();
      cachedAt = now;
    }
    for (Destinations.Destination d : cached) {
      if (!d.runs() || !due(d, now)) {
        continue;
      }
      ReentrantLock lock = lock(d.id());
      if (lock.isLocked()) {
        continue;
      }
      Thread.ofVirtual().name("audit-export-" + d.name()).start(() -> {
        if (!lock.tryLock()) {
          return;
        }
        try {
          // The cached row may be old: the run reads its own, and the next tick sees what it did.
          runLocked(d, RUN_BUDGET);
          cachedAt = Instant.EPOCH;
        } finally {
          lock.unlock();
        }
      });
    }
  }

  /** Whether {@code d} should run at {@code now} (see the class comment). */
  boolean due(Destinations.Destination d, Instant now) {
    Instant last = d.lastAttemptAt();
    Instant ran = ranAt.get(d.id());
    if (ran != null && (last == null || ran.isAfter(last))) {
      last = ran;
    }
    if (last == null || last.isAfter(now)) {
      // Never tried, or tried "in the future" — the clock went back: see the class comment.
      return true;
    }
    if ("failing".equals(d.state())) {
      Duration failing = d.failingSince() == null ? Duration.ZERO : Duration.between(d.failingSince(), last);
      Duration wait = failing.compareTo(RETRY_MIN) < 0 ? RETRY_MIN : failing.compareTo(RETRY_MAX) > 0 ? RETRY_MAX
          : failing;
      return !now.isBefore(last.plus(wait));
    }
    if (d.pendingToId() != null || Boolean.TRUE.equals(more.get(d.id()))) {
      return true;
    }
    if (d.schedule().kind() == Schedule.Kind.STREAM) {
      boolean committed = commitCount.get() != ranAtCommit.getOrDefault(d.id(), -1L);
      return (committed && !now.isBefore(last.plus(STREAM_DELAY))) || !now.isBefore(last.plus(STREAM_EVERY));
    }
    return !now.isBefore(d.schedule().nextAfter(last, zone));
  }

  private ExportRunner.Outcome runLocked(Destinations.Destination d, Duration budget) {
    ranAtCommit.put(d.id(), commitCount.get());
    ranAt.put(d.id(), clock.instant());
    ExportRunner.Outcome outcome = runner.run(d, budget);
    more.put(d.id(), outcome.more());
    if (outcome.rows() > 0) {
      log.info("Audit export {}: {} rows in {} batches to {}", d.name(), outcome.rows(), outcome.batches(),
          d.target());
    }
    return outcome;
  }

  private ReentrantLock lock(String id) {
    return locks.computeIfAbsent(id, k -> new ReentrantLock());
  }

  @Override
  public synchronized void destroy() throws InterruptedException {
    if (timer != null) {
      timer.shutdownNow();
      timer.awaitTermination(5, TimeUnit.SECONDS);
      timer = null;
    }
  }
}
