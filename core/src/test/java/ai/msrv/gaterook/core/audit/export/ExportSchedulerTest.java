package ai.msrv.gaterook.core.audit.export;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.audit.internal.AuditCommits;
import ai.msrv.gaterook.core.audit.internal.persistence.ExportKind;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * When a destination is due: its schedule after its last attempt, a batch left pending first, a failing one after
 * as long as it has failed — and neither a destination that never ran nor a clock that went back leaves an export
 * waiting for a time that doesn't come.
 */
class ExportSchedulerTest {

  /** 09:00 on this computer, the time zone schedules run in: far from 02:00 and from any top of the hour's edge. */
  private static final Instant NOW = java.time.LocalDate.of(2026, 10, 27).atTime(9, 0)
      .atZone(ZoneId.systemDefault()).toInstant();
  private final ExportScheduler scheduler = new ExportScheduler(null, null, new AuditCommits(), Clock.systemUTC(),
      Duration.ofSeconds(2));

  @Test
  void aDestinationThatNeverRanIsDueAtOnce() {
    // A laptop off every night never reaches 02:00 with the core running: a daily export that waits for its first
    // 02:00 "after it was first seen" never comes, and retention holds the whole audit for it.
    assertThat(scheduler.due(destination(Schedule.DAILY, null, null, null, null), NOW)).isTrue();
    assertThat(scheduler.due(destination(Schedule.HOURLY, null, null, null, null), NOW)).isTrue();
  }

  @Test
  void aScheduleRunsAtItsFirstTimeAfterTheLastAttemptAndCatchesUpAfterAStop() {
    ZoneId zone = ZoneId.systemDefault();
    Instant last = NOW.minusSeconds(90);
    Instant next = Schedule.HOURLY.nextAfter(last, zone);
    assertThat(scheduler.due(destination(Schedule.HOURLY, last, null, null, null), next.minusMillis(1))).isFalse();
    assertThat(scheduler.due(destination(Schedule.HOURLY, last, null, null, null), next)).isTrue();

    // The last daily run was two days ago: the core was off at the time since, it runs right after the start.
    Instant daily = Schedule.DAILY.nextAfter(NOW.minus(Duration.ofDays(3)), zone);
    assertThat(scheduler.due(destination(Schedule.DAILY, daily, null, null, null), NOW)).isTrue();
    assertThat(scheduler.due(destination(Schedule.DAILY, NOW.minusSeconds(60), null, null, null), NOW)).isFalse();
  }

  @Test
  void aPendingBatchGoesFirstAndAFailingDestinationWaitsAsLongAsItHasFailed() {
    assertThat(scheduler.due(destination(Schedule.DAILY, NOW.minusSeconds(60), 42L, null, null), NOW)).isTrue();

    Instant since = NOW.minus(Duration.ofMinutes(30));
    Instant last = NOW.minus(Duration.ofMinutes(20));
    // Failing for ten minutes at its last attempt: the next one ten minutes after it.
    assertThat(scheduler.due(destination(Schedule.STREAM, last, 42L, "failing", since), last.plus(Duration
        .ofMinutes(10)).minusMillis(1))).isFalse();
    assertThat(scheduler.due(destination(Schedule.STREAM, last, 42L, "failing", since), last.plus(Duration
        .ofMinutes(10)))).isTrue();
    // At least a minute, at most an hour.
    assertThat(scheduler.due(destination(Schedule.STREAM, last, 42L, "failing", last), last.plusSeconds(59)))
        .isFalse();
    Instant longAgo = last.minus(Duration.ofDays(2));
    assertThat(scheduler.due(destination(Schedule.STREAM, last, 42L, "failing", longAgo), last.plus(Duration
        .ofHours(1)))).isTrue();
  }

  @Test
  void aClockThatWentBackDoesntHoldExportsUntilItCatchesUp() {
    // The last attempt is three days "ahead": the clock was set back. Waiting for the clock would stop every kind of
    // export for three days; a run now writes the time anew.
    Instant ahead = NOW.plus(Duration.ofDays(3));
    assertThat(scheduler.due(destination(Schedule.HOURLY, ahead, null, null, null), NOW)).isTrue();
    assertThat(scheduler.due(destination(Schedule.DAILY, ahead, null, null, null), NOW)).isTrue();
    assertThat(scheduler.due(destination(Schedule.STREAM, ahead, null, null, null), NOW)).isTrue();
    assertThat(scheduler.due(destination(Schedule.STREAM, ahead, 42L, "failing", ahead), NOW)).isTrue();
  }

  private static Destinations.Destination destination(Schedule schedule, Instant lastAttempt, Long pending,
      String state, Instant failingSince) {
    return new Destinations.Destination("ex_" + schedule.id(), ExportKind.SIEM, "soc", true,
        JsonNodeFactory.instance.objectNode().put("host", "siem.acme.internal"), schedule, null, 41L, lastAttempt,
        pending, state, failingSince, lastAttempt, null, 1, true);
  }
}
