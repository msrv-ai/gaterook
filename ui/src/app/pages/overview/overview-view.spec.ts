import { clockTime } from '../../core/time/time-format';
import { HEALTH, NOW, SPARK, WAITING, approval, event, health, histogram, overview } from './overview.fixtures';
import {
  ApprovalsDay,
  callsDelta,
  clientLabel,
  decidedCalls,
  healthAlert,
  healthCalm,
  healthDescription,
  healthGroups,
  healthText,
  markLiterals,
  medianWaitMs,
  overheadChart,
  retestToast,
  setupStatus,
  signed,
  sparkPoints,
  sparseSparkPoints,
  statTiles,
  waitSpans,
  waitingAt,
  waitingPerHour,
} from './overview-view';

const HOUR = 3_600_000;
const iso = (ms: number) => new Date(ms).toISOString();
const text = (parts: readonly { readonly text: string }[]) => parts.map((p) => p.text).join('');
const codes = (parts: readonly { readonly text: string; readonly code: boolean }[]) => parts.filter((p) => p.code).map((p) => p.text);

/** The kit's day (BLUEPRINT §9): three waiting now, 46 approved and 9 rejected decided earlier, 3 expired. */
function kitDay(): ApprovalsDay {
  const decided = (id: string, minutesAgo: number, waitedS: number, status: 'completed' | 'rejected') =>
    approval(id, {
      status,
      requestedAt: iso(NOW - minutesAgo * 60_000),
      expiresAt: iso(NOW - minutesAgo * 60_000 + 300_000),
      decidedAt: iso(NOW - minutesAgo * 60_000 + waitedS * 1000),
      decidedVia: 'app',
    });
  return {
    pending: WAITING,
    history: [
      decided('ap_a', 32, 47, 'rejected'),
      decided('ap_b', 120, 30, 'completed'),
      decided('ap_c', 300, 60, 'completed'),
      approval('ap_core', { status: 'rejected', decidedVia: 'core', requestedAt: iso(NOW - 3 * HOUR), decidedAt: iso(NOW - 3 * HOUR + 1000) }),
      approval('ap_x', { status: 'expired', requestedAt: iso(NOW - 6 * HOUR), expiresAt: iso(NOW - 6 * HOUR + 300_000) }),
    ],
    counts: { pending: 3, approved: 46, rejected: 9, expired: 3 },
  };
}

describe('KPI tiles', () => {
  it('reads like the kit: values, deltas with their sentiment, meta lines and links', () => {
    const o = overview();
    const [calls, waiting, denied, overhead] = statTiles({ stats: o.stats, histogram: histogram(), approvals: kitDay(), now: NOW });

    expect(calls.value).toBe('1,284');
    expect(calls.delta).toEqual({ dir: 'up', sentiment: 'neutral', icon: 'trending-up', text: '+12% vs yesterday' });
    expect(calls.meta).toEqual([{ text: '1,201 allowed · 46 approved' }]);
    expect(calls.chart?.tone).toBe('brand');
    expect(calls.chart?.label).toBe(`Calls per hour: peak of 166 at ${clockTime(Math.floor(NOW / HOUR) * HOUR - 3 * HOUR, false)}`);

    expect(waiting.value).toBe('3');
    expect(waiting.chart?.tone).toBe('warning');
    expect(waiting.chart?.label).toBe('Requests waiting per hour: never more than 3 at a time');
    // The three waiting now came within the last ten minutes: an hour ago nothing waited.
    expect(waiting.delta).toEqual({ dir: 'up', sentiment: 'neutral', icon: 'trending-up', text: '+3 vs an hour ago' });
    expect(waiting.meta).toEqual([{ text: 'Median wait 47 s · 3 expired' }]);

    expect(denied.value).toBe('17');
    expect(denied.delta).toEqual({ dir: 'up', sentiment: 'bad', icon: 'trending-up', text: '+5 vs yesterday' });
    expect(denied.chart?.tone).toBe('danger');
    expect(denied.meta).toEqual([
      { text: 'Also 9 rejected on review' },
      { text: ' · ' },
      { text: 'View denials', route: '/activity', query: { q: 'decision:denied' } },
    ]);

    expect(overhead.value).toBe('31');
    expect(overhead.unit).toBe('ms');
    expect(overhead.chart).toBeNull();
    expect(overhead.delta).toEqual({ dir: 'down', sentiment: 'good', icon: 'trending-down', text: '−4 ms vs yesterday' });
    expect(overhead.meta).toEqual([{ text: 'Target ≤ 50 ms · within target' }]);
  });

  it('says "No change" without a sentiment, and an empty day plainly', () => {
    const o = overview({
      stats: { calls24h: 0, pending: 0, denied24h: 0, gatewayP95Ms: null, deltas: { calls24h: 0, denied24h: 0, gatewayP95Ms: null }, spark: Array(24).fill(0) },
    });
    const empty: ApprovalsDay = { pending: [], history: [], counts: {} };
    const [calls, waiting, denied, overhead] = statTiles({ stats: o.stats, histogram: null, approvals: empty, now: NOW });
    expect(calls.delta).toEqual({ dir: 'flat', sentiment: null, icon: 'minus', text: 'No change vs yesterday' });
    expect(calls.chart).toBeNull();
    expect(calls.meta).toEqual([{ text: 'No calls in the last 24 hours' }]);
    expect(waiting.delta?.text).toBe('No change vs an hour ago');
    expect(waiting.chart).toBeNull();
    expect(waiting.meta).toEqual([{ text: 'No requests in the last 24 hours' }]);
    expect(denied.meta).toEqual([]);
    expect(overhead.value).toBe('None');
    expect(overhead.unit).toBeNull();
    expect(overhead.delta).toBeNull();
    expect(overhead.meta).toEqual([{ text: 'No completed calls in the last 24 hours' }]);
  });

  it('leaves out what an area that did not answer would have said', () => {
    const o = overview();
    const [calls, waiting, denied] = statTiles({ stats: o.stats, histogram: null, approvals: null, now: NOW });
    expect(calls.meta).toEqual([]);
    expect(waiting.chart).toBeNull();
    expect(waiting.delta).toBeNull();
    expect(waiting.meta).toEqual([]);
    expect(denied.chart).toBeNull();
    expect(denied.meta).toEqual([{ text: 'View denials', route: '/activity', query: { q: 'decision:denied' } }]);
  });

  it('writes changes with a sign, a typographic minus and thousands', () => {
    expect(signed(5)).toBe('+5');
    expect(signed(-4, 'ms')).toBe('−4 ms');
    expect(signed(1284)).toBe('+1,284');
    // A share needs a day before with calls; a share that rounds to 0 % is a count.
    expect(callsDelta(9, 9).text).toBe('+9 vs yesterday');
    expect(callsDelta(1000, -100).text).toBe('−9% vs yesterday');
    expect(callsDelta(100001, 1).text).toBe('+1 vs yesterday');
  });

  it('draws sparklines in the kit viewBox: peak at y 4, zero on the baseline', () => {
    expect(sparkPoints([0, 5, 10])).toBe('0,38 115,21 230,4');
    expect(sparkPoints([0, 0])).toBe('0,38 230,38');
    expect(sparkPoints(SPARK).split(' ')).toHaveLength(24);
  });

  it("draws the overhead per hour from the core's series, hours without calls left out (step API)", () => {
    expect(sparseSparkPoints([10, null, 20])).toBe('0,21 230,4');
    expect(sparseSparkPoints([null, null])).toBe('');
    const steady = [...Array<number | null>(20).fill(null), 28, 31, 30, 29];
    const o = overview();
    const [, , , overhead] = statTiles({ stats: { ...o.stats, gatewayP95Spark: steady }, histogram: null, approvals: null, now: NOW });
    expect(overhead.chart).toEqual({
      points: sparseSparkPoints(steady),
      tone: 'brand',
      label: 'Gateway overhead p95 per hour: always under the 50 ms target',
    });
    expect(overhead.chart!.points.split(' ')).toHaveLength(4);
    const spike = [...Array<number | null>(22).fill(30), 62, 31];
    expect(overheadChart(spike, NOW)).toMatchObject({
      tone: 'warning',
      label: `Gateway overhead p95 per hour: over the 50 ms target in 1 hour, worst 62 ms at ${clockTime(Math.floor(NOW / HOUR) * HOUR - HOUR, false)}`,
    });
    // An empty series, or a core before step API, draws nothing.
    expect(overheadChart([null, null], NOW)).toBeNull();
    expect(overheadChart(null, NOW)).toBeNull();
  });
});

describe('waiting over time', () => {
  it('counts the most requests that waited at once in each hour, and at an instant', () => {
    const spans = waitSpans(kitDay());
    const perHour = waitingPerHour(spans, NOW);
    expect(perHour).toHaveLength(24);
    expect(perHour.at(-1)).toBe(3);
    expect(Math.max(...perHour.slice(0, -1))).toBe(1);
    expect(waitingAt(spans, NOW)).toBe(3);
    expect(waitingAt(spans, NOW - HOUR)).toBe(0);
  });

  it('a wait that ends when another starts does not overlap it', () => {
    const t = Math.floor(NOW / HOUR) * HOUR;
    const spans = [
      { from: t + 1000, to: t + 2000 },
      { from: t + 2000, to: t + 3000 },
    ];
    expect(waitingPerHour(spans, NOW).at(-1)).toBe(1);
  });

  it("takes the median of a person's decisions, not the expired requests or the core's refusals", () => {
    expect(medianWaitMs(kitDay(), NOW)).toBe(47_000);
    expect(medianWaitMs({ pending: [], history: [], counts: {} }, NOW)).toBeNull();
  });
});

describe('setup map statuses', () => {
  it("puts the master key's problem codes in words and counts one in the singular", () => {
    const [secrets, connections, , , clients] = overview().setupMap.nodes;
    expect(setupStatus(secrets)).toBe('Master key: no OS protection, not backed up');
    expect(setupStatus(connections)).toBe('1 need attention');
    expect(setupStatus(clients)).toBe('1 named token');
    expect(setupStatus({ ...secrets, status: 'Master key: something-new' })).toBe('Master key: something-new');
  });
});

describe('connection health', () => {
  it('puts problems first, unreachable before slow, and folds the rest', () => {
    const g = healthGroups(HEALTH);
    expect(g.problems.map((r) => r.name)).toEqual(['billing-api', 'cache-redis']);
    expect(g.rest.map((r) => r.name)).toEqual(['app-logs', 'dev-db']);
    expect(g.restLabel).toBe('2 healthy connections');
    expect(healthGroups([health('a', 'unknown'), health('b', 'unknown')]).restLabel).toBe('2 connections not checked yet');
    expect(healthGroups([health('a', 'healthy'), health('b', 'unknown'), health('c', 'healthy')]).restLabel).toBe('2 healthy · 1 not checked yet');
    expect(healthGroups([health('a', 'healthy')]).restLabel).toBe('1 healthy connection');
  });

  it('writes the status as the kit does', () => {
    expect(healthText(health('dev-db', 'healthy', { latencyMs: 12 }))).toBe('Healthy · 12 ms');
    expect(healthText(health('cache-redis', 'degraded', { latencyMs: 1800 }))).toBe('Slow · 1.8 s');
    expect(healthText(health('billing-api', 'unreachable', { latencyMs: 4000 }))).toBe('Unreachable');
    expect(healthText(health('x', 'unknown'))).toBe('Not checked yet');
  });

  it('says how many and when they were last checked', () => {
    expect(healthDescription(HEALTH, () => '1 min ago')).toBe('4 connections, last checked 1 min ago. Problems first.');
    expect(healthDescription([health('a', 'unknown', { checkedAt: null })], () => 'x')).toBe('1 connection. Problems first.');
  });

  it("says how often the core checks when it tells (kit: 'checked every minute')", () => {
    expect(healthDescription(HEALTH, () => '1 min ago', 60)).toBe('4 connections, checked every minute. Problems first.');
    expect(healthDescription([], () => 'x', 300)).toBe('Each connection is checked every 5 minutes. Problems first.');
  });

  it('says "No problems" only for checked connections', () => {
    expect(healthCalm([health('a', 'healthy')])).toEqual({ checked: true, title: 'No problems', text: 'Every connection answered its last check.' });
    expect(healthCalm([health('a', 'unknown')]).checked).toBe(false);
    expect(healthCalm([health('a', 'healthy'), health('b', 'unknown')]).text).toBe(
      "1 connection answered its last check; 1 hasn't been checked yet.",
    );
  });

  it("splits one unreachable connection's message into what happened and what to do, and names what waits on it", () => {
    const a = healthAlert(HEALTH, WAITING)!;
    expect(text(a.title)).toBe("Can't reach billing.stage.acme.dev.");
    expect(codes(a.title)).toEqual(['billing.stage.acme.dev']);
    expect(text(a.body)).toBe('Check the VPN or the host, then test again. The waiting http_request on billing-api fails until then.');
    expect(codes(a.body)).toEqual(['http_request', 'billing-api']);
    expect(a.names).toEqual(['billing-api']);
  });

  it('counts several unreachable connections and the requests that wait on them', () => {
    const rows = ['app-logs', 'aws-stage', 'billing-api', 'cache-redis', 'dev-cluster'].map((n) => health(n, 'unreachable'));
    const a = healthAlert(rows, WAITING)!;
    expect(text(a.title)).toBe('5 connections are unreachable.');
    expect(text(a.body)).toBe(
      'app-logs, aws-stage, billing-api and 2 more. Check the VPN or the hosts, then test again. 2 waiting requests fail until then.',
    );
    expect(a.names).toHaveLength(5);
    expect(healthAlert([health('x', 'unreachable')], [])!.body.map((p) => p.text).join('')).toBe('Check the VPN or the host, then test again.');
    expect(healthAlert([health('a', 'degraded')], WAITING)).toBeNull();
  });

  it('marks identifiers in prose and keeps the sentence intact', () => {
    const parts = markLiterals("Can't reach db.dev.internal:5432, then retry on read-only replicas.");
    expect(text(parts)).toBe("Can't reach db.dev.internal:5432, then retry on read-only replicas.");
    expect(codes(parts)).toEqual(['db.dev.internal:5432']);
  });

  it('tells the result of Test again', () => {
    expect(retestToast([{ name: 'billing-api', ok: false, latencyMs: null, message: 'billing.stage.acme.dev did not answer.' }])).toEqual({
      tone: 'danger',
      title: 'billing-api is still unreachable',
      body: 'billing.stage.acme.dev did not answer.',
    });
    expect(retestToast([{ name: 'dev-db', ok: true, latencyMs: 42, message: null }])).toEqual({
      tone: 'success',
      title: 'dev-db is reachable again',
      body: 'It answered in 42 ms.',
    });
    const mixed = retestToast([
      { name: 'a', ok: true, latencyMs: 1, message: null },
      { name: 'b', ok: false, latencyMs: null, message: null },
    ]);
    expect(mixed).toEqual({ tone: 'warning', title: '1 of 2 connections are reachable again', body: 'Still unreachable: b.' });
  });
});

describe('recent activity', () => {
  it('keeps decided calls only: pending ones are the waiting list', () => {
    const list = decidedCalls([event(3, { decision: 'pending' }), event(2), event(1, { decision: 'denied' })]);
    expect(list.map((e) => e.id)).toEqual([2, 1]);
    expect(decidedCalls(Array.from({ length: 12 }, (_, i) => event(i)))).toHaveLength(8);
  });

  it('names clients as the kit does', () => {
    expect(clientLabel('claude-desktop')).toBe('Claude Desktop');
    expect(clientLabel('codex')).toBe('Codex');
    expect(clientLabel('playground')).toBe('Playground');
    expect(clientLabel(null)).toBe('');
  });
});
