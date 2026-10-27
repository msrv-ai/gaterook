package ai.msrv.gaterook.core.insights.internal;

import java.util.Arrays;
import java.util.Collection;

/**
 * Nearest-rank percentiles, the definition Overview's p95 uses too ({@code AuditQueries.gatewayP95}): the value at
 * rank ⌈p·n⌉ of the sorted values, always one of them — "31 ms at p95" is a call that took 31 ms.
 */
public final class Percentiles {

  private Percentiles() {}

  /** The {@code p}-th percentile ({@code 0 < p ≤ 1}) of {@code values}, or {@code null} without values. */
  public static Long of(Collection<Long> values, double p) {
    if (values.isEmpty()) {
      return null;
    }
    long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
    return at(sorted, p);
  }

  /** The same over values already sorted ascending. */
  public static long at(long[] sorted, double p) {
    int rank = (int) Math.ceil(sorted.length * p);
    return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
  }

  public static long[] sorted(Collection<Long> values) {
    long[] out = values.stream().mapToLong(Long::longValue).toArray();
    Arrays.sort(out);
    return out;
  }
}
