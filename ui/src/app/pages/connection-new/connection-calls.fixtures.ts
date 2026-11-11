import { Insights, InsightsTotals } from '../../core/api/insights.api';
import { bucket, insights } from '../insights/insights.fixtures';

/**
 * `GET /api/insights?q=connection:dev-db` over the 30 days up to `now`, as the core answers it: one bar per day from
 * the hour 30 days ago, today's bar last. For the tests of the connection page only.
 */

const HOUR = 3_600_000;
const DAY = 24 * HOUR;

const NO_CALLS: InsightsTotals = { calls: 0, pending: 0, approved: 0, denied: 0, errors: 0, tasks: 0, gatewayP50Ms: null, gatewayP95Ms: null };

/** A month of 31 daily bars with `perDay[i]` allowed calls, the numbers in `current` and the 30 days before. */
export function connectionMonth(
  now: number,
  perDay: readonly number[],
  current: Partial<InsightsTotals> = {},
  previous: Partial<InsightsTotals> | null = null,
): Insights {
  const from = new Date(now - 30 * DAY).toISOString();
  const to = new Date(now).toISOString();
  const start = Math.floor((now - 30 * DAY) / HOUR) * HOUR;
  return insights({
    from,
    to,
    kpis: { current: { ...NO_CALLS, ...current }, previous: previous ? { ...NO_CALLS, ...previous } : null },
    callsOverTime: { from, to, stepHours: 24, buckets: perDay.map((n, i) => bucket(i, { allowed: n }, start, DAY)) },
  });
}

/** The month the tests show by default: 412 calls, busiest on day 20 of 31 (Oct 12 in the demo world). */
export function busyMonth(now: number): Insights {
  const perDay = Array.from({ length: 31 }, (_, i) => (i === 20 ? 41 : i % 7 === 5 ? 0 : 12));
  return connectionMonth(now, perDay, { calls: 412, approved: 6, denied: 2, errors: 3, pending: 1, tasks: 40 }, { calls: 350 });
}
