package ai.msrv.gaterook.core.admin.overview;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.audit.AuditQueries;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Overview's last 30 days from the audit's hours: the person's calendar days (a 25-hour day when summer time ends, a
 * zone half an hour off the clock hours), the calls of a day by decision, the data of a day, and the totals.
 */
class OverviewSummaryTest {

  private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
  private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
  /** 11:15 in Berlin; the 30 days start on October 13, summer time ends on October 25. */
  private static final Instant NOW = Instant.parse("2026-11-11T10:15:00Z");

  @Test
  void thirtyDaysEndToday() {
    OverviewSummary.Summary s = OverviewSummary.of(NOW, BERLIN, null, hours(), List.of());

    assertThat(s.days()).hasSize(30);
    assertThat(s.days().getFirst().date()).isEqualTo("2026-10-13");
    assertThat(s.days().getLast().date()).isEqualTo("2026-11-11");
    assertThat(s.from()).isEqualTo(Instant.parse("2026-10-12T22:00:00Z")).isEqualTo(OverviewSummary.start(NOW, BERLIN));
    assertThat(s.to()).isEqualTo(NOW);
    assertThat(s.zone()).isEqualTo("Europe/Berlin");
    assertThat(s.days().getLast().to()).as("today ends at midnight, still ahead")
        .isEqualTo(Instant.parse("2026-11-11T23:00:00Z"));
    assertThat(s.totals().calls()).isZero();
    assertThat(s.totals().decisions()).isEmpty();
  }

  @Test
  void theDaySummerTimeEndsHasTwentyFiveHours() {
    OverviewSummary.Summary s = OverviewSummary.of(NOW, BERLIN, null, hours(
        bucket("2026-10-24T21:00:00Z", Map.of("allowed", 1L)),   // 23:00 on October 24
        bucket("2026-10-24T22:00:00Z", Map.of("allowed", 2L)),   // midnight, October 25
        bucket("2026-10-25T22:00:00Z", Map.of("denied", 3L)),    // 23:00 on October 25, after the clocks went back
        bucket("2026-10-25T23:00:00Z", Map.of("approved", 4L))), // midnight, October 26
        List.of());

    OverviewSummary.Day day = day(s, "2026-10-25");
    assertThat(day.from()).isEqualTo(Instant.parse("2026-10-24T22:00:00Z"));
    assertThat(day.to()).isEqualTo(Instant.parse("2026-10-25T23:00:00Z"));
    assertThat(day.decisions()).containsExactlyInAnyOrderEntriesOf(Map.of("allowed", 2L, "denied", 3L));
    assertThat(day.calls()).isEqualTo(5);
    assertThat(day(s, "2026-10-24").calls()).isEqualTo(1);
    assertThat(day(s, "2026-10-26").decisions()).containsExactly(Map.entry("approved", 4L));
    assertThat(s.totals().calls()).isEqualTo(10);
    assertThat(s.totals().decisions()).containsExactlyInAnyOrderEntriesOf(Map.of("allowed", 3L, "denied", 3L,
        "approved", 4L));
  }

  @Test
  void dataGoesToTheDayOfItsHour() {
    OverviewSummary.Summary s = OverviewSummary.of(NOW, BERLIN, null, hours(), List.of(
        traffic("2026-11-10T22:00:00Z", 100, 4_000),  // 23:00 on November 10
        traffic("2026-11-10T23:00:00Z", 20, 500),     // midnight, today
        traffic("2026-11-11T09:00:00Z", 5, 7)));

    assertThat(day(s, "2026-11-10").requestBytes()).isEqualTo(100);
    assertThat(day(s, "2026-11-10").resultBytes()).isEqualTo(4_000);
    assertThat(s.days().getLast().requestBytes()).isEqualTo(25);
    assertThat(s.days().getLast().resultBytes()).isEqualTo(507);
    assertThat(s.totals().requestBytes()).isEqualTo(125);
    assertThat(s.totals().resultBytes()).isEqualTo(4_507);
  }

  @Test
  void hoursOutsideTheDaysAddNothing() {
    OverviewSummary.Summary s = OverviewSummary.of(NOW, BERLIN, Instant.parse("2026-01-02T03:04:05Z"), hours(
        bucket("2026-10-12T21:00:00Z", Map.of("allowed", 7L)),   // 23:00 on October 12: the day before the first
        bucket("2026-11-11T23:00:00Z", Map.of("allowed", 9L))),  // tomorrow
        List.of(traffic("2026-10-12T21:00:00Z", 1_000, 1_000), traffic("2026-11-12T08:00:00Z", 1, 1)));

    assertThat(s.totals().calls()).isZero();
    assertThat(s.totals().requestBytes()).isZero();
    assertThat(s.totals().resultBytes()).isZero();
    assertThat(s.firstCallAt()).isEqualTo(Instant.parse("2026-01-02T03:04:05Z"));
  }

  /** India is 5:30 off UTC: its days start with the first whole hour after midnight, 00:30 there. */
  @Test
  void aZoneOffTheClockHoursStartsItsDaysAtTheNextWholeHour() {
    OverviewSummary.Summary s = OverviewSummary.of(NOW, KOLKATA, null, hours(
        bucket("2026-11-09T18:00:00Z", Map.of("allowed", 1L)),   // 23:30 on November 9 there
        bucket("2026-11-09T19:00:00Z", Map.of("allowed", 2L))),  // 00:30 on November 10 there
        List.of());

    assertThat(s.days().getLast().date()).isEqualTo("2026-11-11");
    assertThat(day(s, "2026-11-09").calls()).isEqualTo(1);
    assertThat(day(s, "2026-11-10").calls()).isEqualTo(2);
    assertThat(day(s, "2026-11-10").from()).isEqualTo(Instant.parse("2026-11-09T19:00:00Z"));
    assertThat(s.from()).isEqualTo(Instant.parse("2026-10-12T19:00:00Z"));
  }

  @Test
  void theZoneIsThePersonsOrTheComputersOwn() {
    assertThat(OverviewSummary.zone("Europe/Berlin")).isEqualTo(BERLIN);
    assertThat(OverviewSummary.zone(" Asia/Kolkata ")).isEqualTo(KOLKATA);
    assertThat(OverviewSummary.zone(null)).isEqualTo(ZoneId.systemDefault());
    assertThat(OverviewSummary.zone("")).isEqualTo(ZoneId.systemDefault());
    assertThat(OverviewSummary.zone("Mars/Olympus_Mons")).isEqualTo(ZoneId.systemDefault());
  }

  private static OverviewSummary.Day day(OverviewSummary.Summary s, String date) {
    return s.days().stream().filter(d -> d.date().equals(date)).findFirst().orElseThrow();
  }

  private static AuditQueries.Histogram hours(AuditQueries.Bucket... buckets) {
    return new AuditQueries.Histogram(Instant.parse("2026-10-12T22:00:00Z"), NOW, 1, List.of(buckets));
  }

  private static AuditQueries.Bucket bucket(String from, Map<String, Long> decisions) {
    Instant at = Instant.parse(from);
    return new AuditQueries.Bucket(at, at.plusSeconds(3_600), decisions,
        decisions.values().stream().mapToLong(Long::longValue).sum());
  }

  private static AuditQueries.HourTraffic traffic(String hour, long sent, long returned) {
    return new AuditQueries.HourTraffic(Math.floorDiv(Instant.parse(hour).toEpochMilli(), 3_600_000L), sent,
        returned);
  }
}
