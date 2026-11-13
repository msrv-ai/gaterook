package com.gaterook.connectors.prometheus;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Durations as Prometheus writes them ({@code 1h30m}, {@code 15s}, {@code 500ms}) or as seconds ({@code 15},
 * {@code 0.5}), the steps of a range query chosen for a readable answer, and the texts sent to the server.
 */
final class Durations {

  /** Prometheus' duration: units from the largest down, each at most once. */
  private static final Pattern PROMETHEUS = Pattern.compile(
      "(?:(\\d{1,6})y)?(?:(\\d{1,6})w)?(?:(\\d{1,6})d)?(?:(\\d{1,6})h)?(?:(\\d{1,6})m)?(?:(\\d{1,6})s)?"
          + "(?:(\\d{1,6})ms)?");
  private static final Pattern SECONDS = Pattern.compile("\\d{1,9}(\\.\\d{1,3})?");
  private static final long[] UNIT_MS = {365L * 86_400_000, 7L * 86_400_000, 86_400_000, 3_600_000, 60_000, 1_000, 1};
  /** Steps a range query picks from when the agent gives none: round numbers people read at a glance. */
  static final List<Duration> NICE = List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5),
      Duration.ofSeconds(10), Duration.ofSeconds(15), Duration.ofSeconds(30), Duration.ofMinutes(1),
      Duration.ofMinutes(2), Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(15),
      Duration.ofMinutes(30), Duration.ofHours(1), Duration.ofHours(2), Duration.ofHours(3), Duration.ofHours(6),
      Duration.ofHours(12), Duration.ofDays(1), Duration.ofDays(2), Duration.ofDays(7));
  /** About this many points per series when the agent gives no step. */
  static final int DEFAULT_POINTS = 120;

  private Durations() {}

  /**
   * A positive duration of at least a millisecond, or {@code null} when {@code text} is none of the forms.
   */
  static Duration parse(String text) {
    if (text == null) {
      return null;
    }
    String s = text.strip();
    if (s.isEmpty()) {
      return null;
    }
    Duration d;
    if (SECONDS.matcher(s).matches()) {
      d = Duration.ofMillis(new BigDecimal(s).movePointRight(3).longValueExact());
    } else {
      Matcher m = PROMETHEUS.matcher(s);
      if (!m.matches()) {
        return null;
      }
      long ms = 0;
      for (int i = 0; i < UNIT_MS.length; i++) {
        String g = m.group(i + 1);
        if (g != null) {
          ms = Math.addExact(ms, Math.multiplyExact(Long.parseLong(g), UNIT_MS[i]));
        }
      }
      d = Duration.ofMillis(ms);
    }
    return d.isZero() || d.isNegative() ? null : d;
  }

  /**
   * The step of a range when the agent gives none: the smallest round step of at least a second that keeps the range
   * within about 120 points ({@code 1h} → {@code 30s}, 121 points) and within {@code maxPoints}.
   */
  static Duration defaultStep(Duration range, int maxPoints) {
    int intervals = Math.max(1, Math.min(DEFAULT_POINTS, maxPoints - 1));
    long rawMs = Math.max(1_000, ceilDiv(range.toMillis(), intervals));
    for (Duration nice : NICE) {
      if (nice.toMillis() >= rawMs) {
        return nice;
      }
    }
    return Duration.ofSeconds(ceilDiv(rawMs, 1_000));
  }

  /** The points of a range per series: {@code (end - start) / step + 1}, as Prometheus counts them. */
  static long points(Duration range, Duration step) {
    return range.toMillis() / step.toMillis() + 1;
  }

  /** The smallest whole-second step that keeps {@code range} within {@code maxPoints} points. */
  static Duration smallestStep(Duration range, int maxPoints) {
    long ms = Math.max(1_000, ceilDiv(range.toMillis(), Math.max(1, maxPoints - 1)));
    return Duration.ofSeconds(ceilDiv(ms, 1_000));
  }

  /** Seconds as the server reads them: {@code 60}, {@code 0.5}. */
  static String seconds(Duration d) {
    return BigDecimal.valueOf(d.toMillis()).movePointLeft(3).stripTrailingZeros().toPlainString();
  }

  /** A duration as people write it: {@code 1h30m}, {@code 15s}, {@code 500ms}. */
  static String human(Duration d) {
    long ms = d.toMillis();
    if (ms == 0) {
      return "0s";
    }
    StringBuilder out = new StringBuilder();
    long[] units = {86_400_000, 3_600_000, 60_000, 1_000, 1};
    String[] names = {"d", "h", "m", "s", "ms"};
    for (int i = 0; i < units.length; i++) {
      long n = ms / units[i];
      if (n > 0) {
        out.append(n).append(names[i]);
        ms -= n * units[i];
      }
    }
    return out.toString();
  }

  private static long ceilDiv(long a, long b) {
    return -Math.floorDiv(-a, b);
  }
}
