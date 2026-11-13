package com.gaterook.core.admin.overview;

import com.gaterook.core.audit.AuditQueries;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Overview's last 30 days, today included ({@code GET /api/overview/summary}): the calls of each day by what they came
 * to, and the data that went through GateRook with them — what AI apps sent with their calls and what went back to
 * them. Pure: the audit's hourly counts and sums in, days out.
 *
 * <p>A day is the clock hours that start on its date in the person's zone, because the audit counts by hour: midnight
 * to midnight wherever the zone's offset is whole hours, from the first whole hour after midnight elsewhere. A call is
 * in the day of its latest row (as on Activity's histogram); its request in the day it arrived, its result in the day
 * it went back.
 */
final class OverviewSummary {

  /** How many days the summary covers, today included. */
  static final int DAYS = 30;

  private static final long HOUR_MS = 3_600_000L;

  /**
   * One day.
   *
   * @param date         the date in the summary's zone, ISO ({@code 2026-11-11})
   * @param from         the first hour of the day
   * @param to           the first hour of the next day (today's is still ahead)
   * @param decisions    the day's calls by the decision of their latest row; a missing key is 0
   * @param requestBytes what apps sent with the calls received that day
   * @param resultBytes  what went back to apps with the calls completed that day
   */
  record Day(String date, Instant from, Instant to, Map<String, Long> decisions, long calls, long requestBytes,
      long resultBytes) {}

  /** The sums of the days. */
  record Totals(long calls, Map<String, Long> decisions, long requestBytes, long resultBytes) {}

  /**
   * @param from        the first hour of the first day
   * @param to          when the summary was made
   * @param zone        the zone the days are dates in
   * @param firstCallAt when the oldest call the audit keeps reached the gateway, {@code null} before the first call
   * @param days        oldest first, always {@value #DAYS}
   */
  record Summary(Instant from, Instant to, String zone, Instant firstCallAt, Totals totals, List<Day> days) {}

  private OverviewSummary() {}

  /**
   * The zone the person's days are in: the one the app sends ({@code Europe/Berlin}), else — none, or one this
   * runtime doesn't know — the computer's own, which the app on the same computer shares.
   */
  static ZoneId zone(String id) {
    if (id == null || id.isBlank()) {
      return ZoneId.systemDefault();
    }
    try {
      return ZoneId.of(id.strip());
    } catch (DateTimeException e) {
      return ZoneId.systemDefault();
    }
  }

  /** Where the summary made at {@code now} starts: the first hour of the oldest of its days. */
  static Instant start(Instant now, ZoneId zone) {
    return firstHour(LocalDate.ofInstant(now, zone).minusDays(DAYS - 1), zone);
  }

  /**
   * The summary made at {@code now} from the audit's hours since {@link #start}: {@code hours} with one-hour bars, the
   * hours with data in {@code traffic}. Hours outside the days add nothing.
   */
  static Summary of(Instant now, ZoneId zone, Instant firstCallAt, AuditQueries.Histogram hours,
      List<AuditQueries.HourTraffic> traffic) {
    LocalDate first = LocalDate.ofInstant(now, zone).minusDays(DAYS - 1);
    List<Map<String, Long>> decisions = new ArrayList<>(DAYS);
    long[] calls = new long[DAYS];
    long[] sent = new long[DAYS];
    long[] returned = new long[DAYS];
    for (int i = 0; i < DAYS; i++) {
      decisions.add(new TreeMap<>());
    }
    for (AuditQueries.Bucket bar : hours.buckets()) {
      int day = index(first, bar.from(), zone);
      if (day >= 0) {
        bar.decisions().forEach((decision, n) -> decisions.get(day).merge(decision, n, Long::sum));
        calls[day] += bar.total();
      }
    }
    for (AuditQueries.HourTraffic hour : traffic) {
      int day = index(first, Instant.ofEpochMilli(hour.hour() * HOUR_MS), zone);
      if (day >= 0) {
        sent[day] += hour.requestBytes();
        returned[day] += hour.resultBytes();
      }
    }
    List<Day> days = new ArrayList<>(DAYS);
    Map<String, Long> all = new TreeMap<>();
    long callsTotal = 0;
    long sentTotal = 0;
    long returnedTotal = 0;
    for (int i = 0; i < DAYS; i++) {
      LocalDate date = first.plusDays(i);
      days.add(new Day(date.toString(), firstHour(date, zone), firstHour(date.plusDays(1), zone),
          Map.copyOf(decisions.get(i)), calls[i], sent[i], returned[i]));
      decisions.get(i).forEach((decision, n) -> all.merge(decision, n, Long::sum));
      callsTotal += calls[i];
      sentTotal += sent[i];
      returnedTotal += returned[i];
    }
    return new Summary(firstHour(first, zone), now, zone.getId(), firstCallAt,
        new Totals(callsTotal, Map.copyOf(all), sentTotal, returnedTotal), List.copyOf(days));
  }

  /** The day of the hour starting {@code at}, counted from {@code first}; -1 outside the {@value #DAYS} days. */
  private static int index(LocalDate first, Instant at, ZoneId zone) {
    long day = ChronoUnit.DAYS.between(first, LocalDate.ofInstant(at, zone));
    return day >= 0 && day < DAYS ? (int) day : -1;
  }

  /** The first clock hour that starts on {@code date}: its midnight, or the next whole hour after it. */
  private static Instant firstHour(LocalDate date, ZoneId zone) {
    long midnight = date.atStartOfDay(zone).toInstant().toEpochMilli();
    return Instant.ofEpochMilli(Math.ceilDiv(midnight, HOUR_MS) * HOUR_MS);
  }
}
