import { Facets, Histogram, HistogramBucket, SavedView } from '../core/api/activity.api';
import { Env } from '../core/api/types';

/**
 * The Activity of the demo world (BLUEPRINT §9): 24 hours up to Thursday 14:30, as `/api/activity/histogram`,
 * `/api/activity/facets` and `/api/activity/events` return them, for the log-explorer gallery
 * (design/elements/log-explorer.html). Dev only.
 */

/** Calls per hour by decision, 15:00 yesterday to 14:00 today: [allowed, pending, denied, expired]. */
const HOURS: readonly (readonly [number, number, number, number])[] = [
  [95, 0, 1, 0], [111, 0, 4, 1], [102, 0, 2, 0], [68, 0, 0, 0], [38, 0, 0, 0], [20, 0, 0, 0], [14, 0, 0, 0], [10, 0, 0, 0],
  [7, 0, 0, 0], [3, 0, 0, 0], [1, 0, 0, 0], [0, 0, 0, 0], [1, 0, 0, 0], [0, 0, 0, 0], [3, 0, 0, 0], [9, 0, 0, 0],
  [24, 0, 0, 0], [54, 0, 2, 0], [98, 0, 6, 0], [141, 0, 9, 0], [160, 0, 5, 1], [90, 0, 0, 0], [117, 0, 3, 1], [81, 3, 2, 0],
];

/** Denied calls on prod in the same hours (the filtered state). */
const START = Date.parse('2026-10-21T15:00:00+02:00');
const HOUR = 3_600_000;

function bucket(i: number, decisions: Record<string, number>): HistogramBucket {
  const total = Object.values(decisions).reduce((s, n) => s + n, 0);
  const clean = Object.fromEntries(Object.entries(decisions).filter(([, n]) => n > 0));
  return { from: new Date(START + i * HOUR).toISOString(), to: new Date(START + (i + 1) * HOUR).toISOString(), decisions: clean, total };
}

const histogram = (buckets: HistogramBucket[]): Histogram => ({
  from: '2026-10-21T14:30:00+02:00',
  to: '2026-10-22T14:30:00+02:00',
  stepHours: 1,
  buckets,
});

export const DEMO_HISTOGRAM: Histogram = histogram(HOURS.map(([a, p, d, e], i) => bucket(i, { allowed: a, pending: p, denied: d, expired: e })));

const f = (pairs: readonly (readonly [string, number])[]) => pairs.map(([name, count]) => ({ name, count }));

export const DEMO_FACETS: Facets = {
  decision: f([['allowed', 1201], ['approved', 46], ['pending', 3], ['denied', 17], ['rejected', 9], ['expired', 3], ['error', 8]]),
  tool: f([['sql_query', 512], ['logs_search', 341], ['k8s_get', 150], ['ssh_run', 98], ['http_request', 62], ['sql_execute', 48], ['redis_get', 31], ['mongo_find', 23], ['k8s_apply', 9], ['k8s_rollout_restart', 7], ['ssh_exec', 6]]),
  client: f([['codex', 774], ['claude-desktop', 494], ['claude-ai', 19]]),
  endpoint: f([['dev', 1157], ['incident-prod', 111], ['stage-readonly', 19]]),
  connection: f([['dev-db', 472], ['app-logs', 247], ['dev-cluster', 172], ['dev-vm', 98], ['prod-logs', 94], ['prod-replica', 69], ['billing-api', 62], ['cache-redis', 31], ['orders-mongo', 23], ['stage-db', 19]]),
  env: f([['dev', 1095], ['stage', 81], ['prod', 111]]),
};

export const DEMO_FACETS_NONE: Facets = {
  decision: [],
  client: [],
  env: [],
};

export const DEMO_CONNECTION_ENVS: Record<string, Env> = {
  'dev-db': 'dev',
  'app-logs': 'dev',
  'dev-cluster': 'dev',
  'dev-vm': 'dev',
  'prod-logs': 'prod',
  'prod-replica': 'prod',
  'billing-api': 'stage',
  'cache-redis': 'dev',
  'orders-mongo': 'dev',
  'stage-db': 'stage',
};

export const DEMO_VIEWS: SavedView[] = [
  { name: 'Denied on prod', q: 'decision:denied env:prod', view: null },
  { name: 'Waiting for approval', q: 'decision:pending', view: null },
  { name: 'Slow calls', q: 'duration:>1s', view: null },
];
