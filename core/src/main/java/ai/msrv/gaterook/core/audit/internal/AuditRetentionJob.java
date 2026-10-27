package ai.msrv.gaterook.core.audit.internal;

import ai.msrv.gaterook.core.audit.export.Destinations;
import ai.msrv.gaterook.core.platform.SettingStore;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Set;
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
 * Audit retention (ARCHITECTURE.md §4 "Retention", kit Settings › Audit & retention: 1, 2, 3 or 5 years, 1 by default):
 * the only code that deletes audit rows. A row goes when it is older than the retention <em>and</em>, for agent calls,
 * every export destination already took it ({@code id ≤ min(last_exported_id)}, a paused destination included, K5;
 * a destination whose row doesn't verify holds everything — its position may have been moved, review K5): what wasn't
 * exported is never deleted. The V1 triggers refuse anything younger than a year whatever this job thinks.
 * Runs 10 minutes after start and then daily; traces whose last call is gone go too. The delete runs through
 * {@link AuditHead#retain}: the chain since its anchor is checked first and the anchor follows the delete, so retention
 * neither hides a break nor looks like one.
 */
@Component
public class AuditRetentionJob implements DisposableBean {

  /** {@code setting audit}: {@code {"retentionYears": 1}}. */
  public static final String SETTING = "audit";

  private static final Set<Integer> YEARS = Set.of(1, 2, 3, 5);
  private static final Logger log = LoggerFactory.getLogger(AuditRetentionJob.class);

  /** What one run deleted. */
  public record Run(Instant cutoff, int toolCalls, int setupChanges, int traces) {}

  @PersistenceContext
  private EntityManager em;

  private final SettingStore settings;
  private final AuditHead head;
  private final Destinations exports;
  private final Clock clock;
  private ScheduledExecutorService timer;

  AuditRetentionJob(SettingStore settings, AuditHead head, Destinations exports, Clock clock) {
    this.settings = settings;
    this.head = head;
    this.exports = exports;
    this.clock = clock;
  }

  @EventListener(ApplicationReadyEvent.class)
  synchronized void start() {
    if (timer == null) {
      timer = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("audit-retention").factory());
      timer.scheduleWithFixedDelay(this::runQuietly, 10, 24 * 60, TimeUnit.MINUTES);
    }
  }

  /** The retention in years from Settings; anything unknown counts as the default 1. */
  public int retentionYears() {
    int years = settings.find(SETTING).map(s -> s.value().path("retentionYears").asInt(1)).orElse(1);
    return YEARS.contains(years) ? years : 1;
  }

  void runQuietly() {
    try {
      Run run = run();
      if (run.toolCalls() + run.setupChanges() + run.traces() > 0) {
        log.info("Audit retention: removed {} agent calls, {} setup changes and {} traces older than {}",
            run.toolCalls(), run.setupChanges(), run.traces(), run.cutoff());
      }
    } catch (RuntimeException e) {
      log.warn("Audit retention didn't run: {}", e.toString());
    }
  }

  public Run run() {
    Instant cutoff = ZonedDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC)
        .minusYears(retentionYears()).toInstant();
    return head.retain(() -> {
      // In the delete's own transaction: a destination added meanwhile (its cursor at 0) holds everything back, and
      // so does one whose row doesn't verify.
      long maxId = exports.retentionBound();
      int calls = em.createNativeQuery("delete from tool_call where created_at < ?1 and id <= ?2")
          .setParameter(1, cutoff.toEpochMilli()).setParameter(2, maxId).executeUpdate();
      int changes = em.createNativeQuery("delete from setup_change where created_at < ?1")
          .setParameter(1, cutoff.toEpochMilli()).executeUpdate();
      int traces = em.createNativeQuery("delete from trace where last_at < ?1")
          .setParameter(1, cutoff.toEpochMilli()).executeUpdate();
      return new Run(cutoff, calls, changes, traces);
    });
  }

  /** How long a call stays at least: the trigger's floor. */
  public static Duration floor() {
    return Duration.ofDays(365);
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
