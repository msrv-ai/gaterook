import { HistogramBucket } from '../../core/api/activity.api';
import { ClientCard } from '../../core/api/clients.api';
import { ConnectionHealth } from '../../core/api/connections.api';
import { Insights } from '../../core/api/insights.api';

/**
 * The kit's Insights (design/pages/insights.html, BLUEPRINT §9) in the shape `GET /api/insights` returns — the last
 * 24 hours up to Thursday 14:30 of the demo world. For the page's tests only: production code never imports it
 * (step K5, which builds the route, isn't merged yet; until then the page says "not available yet").
 */

/**
 * "Now" of the demo world: Thursday 2026-10-22 14:30 in the local time zone (the kit's clock times are local, so the
 * fixture is too and the tests pass in any zone).
 */
export const NOW = new Date(2026, 9, 22, 14, 30).getTime();

const HOUR = 3_600_000;
const START = new Date(2026, 9, 21, 15).getTime();

/** Calls per hour from 15:00 yesterday to 14:00 today: [allowed or approved, waiting, denied or failed, expired]. */
const HOURS: readonly (readonly [number, number, number, number])[] = [
  [95, 0, 1, 0], [111, 0, 4, 1], [102, 0, 2, 0], [68, 0, 0, 0], [38, 0, 0, 0], [20, 0, 0, 0], [14, 0, 0, 0], [10, 0, 0, 0],
  [7, 0, 0, 0], [3, 0, 0, 0], [1, 0, 0, 0], [0, 0, 0, 0], [1, 0, 0, 0], [0, 0, 0, 0], [3, 0, 0, 0], [9, 0, 0, 0],
  [24, 0, 0, 0], [54, 0, 2, 0], [98, 0, 6, 0], [141, 0, 9, 0], [160, 0, 5, 1], [90, 0, 0, 0], [117, 0, 3, 1], [81, 3, 2, 0],
];

/** A local clock time today (`at`) or yesterday (`yesterday`) of the demo world, as the core sends it (ISO, UTC). */
const clock = (day: number, local: string) => {
  const [h, m, s] = local.split(':').map(Number);
  return new Date(2026, 9, day, h, m, s).toISOString();
};
const at = (local: string) => clock(22, local);
const yesterday = (local: string) => clock(21, local);

export function bucket(i: number, decisions: Record<string, number>, start = START, step = HOUR): HistogramBucket {
  const clean = Object.fromEntries(Object.entries(decisions).filter(([, n]) => n > 0));
  const total = Object.values(clean).reduce((s, n) => s + n, 0);
  return { from: new Date(start + i * step).toISOString(), to: new Date(start + (i + 1) * step).toISOString(), decisions: clean, total };
}

/** The demo world's Insights for the last 24 hours, with parts replaced by `patch`. */
export function insights(patch: Partial<Insights> = {}): Insights {
  return {
    from: new Date(NOW - 24 * HOUR).toISOString(),
    to: new Date(NOW).toISOString(),
    firstCallAt: '2026-09-25T08:40:00Z',
    unfilteredCalls: 1284,
    kpis: {
      current: { calls: 1284, pending: 3, approved: 46, denied: 17, errors: 8, tasks: 61, gatewayP50Ms: 9, gatewayP95Ms: 31 },
      previous: { calls: 1146, pending: 1, approved: 40, denied: 12, errors: 11, tasks: 54, gatewayP50Ms: 10, gatewayP95Ms: 35 },
    },
    callsOverTime: {
      from: new Date(NOW - 24 * HOUR).toISOString(),
      to: new Date(NOW).toISOString(),
      stepHours: 1,
      buckets: HOURS.map(([ok, pending, bad, expired], i) => bucket(i, { allowed: ok, pending, denied: bad, expired })),
    },
    topTools: [
      { name: 'sql_query', count: 512 },
      { name: 'logs_search', count: 341 },
      { name: 'k8s_get', count: 150 },
      { name: 'ssh_run', count: 98 },
      { name: 'http_request', count: 61 },
      { name: 'sql_execute', count: 47 },
      { name: 'redis_get', count: 31 },
      { name: 'mongo_find', count: 23 },
      { name: 'k8s_apply', count: 9 },
      { name: 'k8s_rollout_restart', count: 6 },
      { name: 'ssh_exec', count: 6 },
    ],
    byClient: [
      {
        client: 'codex',
        count: 772,
        decisions: { allowed: 720, approved: 30, denied: 10, rejected: 6, error: 4, expired: 2 },
        endpoints: ['dev'],
        connections: ['dev-db', 'app-logs', 'dev-cluster', 'billing-api', 'orders-mongo'],
        firstAt: yesterday('15:02:11'),
        lastAt: at('14:29:30'),
      },
      {
        client: 'claude-desktop',
        count: 493,
        decisions: { allowed: 462, approved: 16, denied: 7, rejected: 3, error: 4, expired: 1 },
        endpoints: ['dev', 'incident-prod'],
        connections: ['dev-db', 'prod-replica', 'prod-logs', 'dev-cluster', 'cache-redis', 'dev-vm'],
        firstAt: yesterday('15:00:40'),
        lastAt: at('14:25:48'),
      },
      {
        client: 'claude-ai',
        count: 19,
        decisions: { allowed: 19 },
        endpoints: ['stage-readonly'],
        connections: ['stage-db'],
        firstAt: at('08:41:05'),
        lastAt: at('12:08:14'),
      },
    ],
    decisions: { allowed: 1201, approved: 46, denied: 17, rejected: 9, expired: 3, error: 8 },
    approvals: {
      approved: 46,
      rejected: 9,
      expired: 3,
      medianWaitS: 47,
      p90WaitS: 100,
      approvedShare: 46 / 58,
      waitHistogram: [
        { toS: 60, count: 38 },
        { toS: 120, count: 11 },
        { toS: 300, count: 6 },
        { toS: null, count: 0 },
      ],
      slowest: [
        { approvalId: 'ap_3c7a9d02kq1m', eventId: 1102, tool: 'k8s_rollout_restart', connection: 'dev-cluster', env: 'dev', requestedAt: at('09:47:12'), status: 'approved', waitS: 185 },
        { approvalId: 'ap_8d14e6b5zx2n', eventId: 1187, tool: 'sql_execute', connection: 'dev-db', env: 'dev', requestedAt: at('11:20:03'), status: 'approved', waitS: 151 },
        { approvalId: 'ap_f07b21c6wq3p', eventId: 1129, tool: 'http_request', connection: 'billing-api', env: 'stage', requestedAt: at('10:06:40'), status: 'rejected', waitS: 132 },
        { approvalId: 'ap_52e9a8d3ty4r', eventId: 431, tool: 'ssh_run', connection: 'dev-vm', env: 'dev', requestedAt: yesterday('16:41:09'), status: 'approved', waitS: 108 },
      ],
    },
    deniedByRule: [
      { rule: 'mode.off', count: 6, example: { literal: 'ssh_exec', connection: 'dev-vm', env: 'dev' } },
      { rule: 'prod.sql.block_ddl', count: 4, example: { literal: 'DROP TABLE', connection: 'prod-replica', env: 'prod' } },
      { rule: 'sql.read_only_tool', count: 3, example: { literal: 'DELETE', connection: 'dev-db', env: 'dev' } },
      { rule: 'http.block_private_ranges', count: 2, example: { literal: 'GET 169.254.169.254', connection: 'billing-api', env: 'stage' } },
      { rule: 'k8s.namespace_allowlist', count: 2, example: { literal: 'kube-system', connection: 'dev-cluster', env: 'dev' } },
    ],
    latency: {
      byConnection: [
        { connection: 'cache-redis', env: 'dev', calls: 31, gatewayP50: 6, gatewayP95: 18, resourceP50: 1200, resourceP95: 1800 },
        { connection: 'prod-logs', env: 'prod', calls: 41, gatewayP50: 14, gatewayP95: 38, resourceP50: 640, resourceP95: 1100 },
        { connection: 'dev-vm', env: 'dev', calls: 103, gatewayP50: 12, gatewayP95: 35, resourceP50: 380, resourceP95: 820 },
        { connection: 'billing-api', env: 'stage', calls: 59, gatewayP50: 9, gatewayP95: 27, resourceP50: 210, resourceP95: 480 },
        { connection: 'app-logs', env: 'dev', calls: 300, gatewayP50: 11, gatewayP95: 33, resourceP50: 120, resourceP95: 240 },
        { connection: 'dev-cluster', env: 'dev', calls: 166, gatewayP50: 8, gatewayP95: 22, resourceP50: 41, resourceP95: 96 },
        { connection: 'prod-replica', env: 'prod', calls: 65, gatewayP50: 9, gatewayP95: 29, resourceP50: 28, resourceP95: 62 },
        { connection: 'stage-db', env: 'stage', calls: 19, gatewayP50: 8, gatewayP95: 25, resourceP50: 22, resourceP95: 58 },
        { connection: 'dev-db', env: 'dev', calls: 470, gatewayP50: 7, gatewayP95: 24, resourceP50: 12, resourceP95: 41 },
        { connection: 'orders-mongo', env: 'dev', calls: 23, gatewayP50: 6, gatewayP95: 21, resourceP50: 9, resourceP95: 27 },
      ],
    },
    errorsByConnection: [
      { connection: 'cache-redis', env: 'dev', count: 5, kind: 'TIMEOUT', message: 'ETIMEDOUT after 2.0 s' },
      { connection: 'billing-api', env: 'stage', count: 3, kind: 'RESOURCE_UNAVAILABLE', message: 'ECONNREFUSED billing.stage.acme.dev:443' },
    ],
    unusual: [
      {
        kind: 'prod-ddl',
        text: 'Claude Desktop sent TRUNCATE and then DROP TABLE to prod-replica within a minute, in one task. Both were denied.',
        traceId: 'b81e44d09c3a4f7e8b2d61a5c0f93e17',
        traceTitle: 'Investigate failed order sync on prod',
        client: 'claude-desktop',
        connection: 'prod-replica',
        env: 'prod',
        statements: ['TRUNCATE', 'DROP TABLE'],
        withinS: 41,
        calls: 2,
        denied: 2,
      },
      {
        kind: 'tool-spike',
        text: 'sql_query on prod-replica is 4× its 7-day average.',
        tool: 'sql_query',
        connection: 'prod-replica',
        env: 'prod',
        calls: 65,
        dailyAverage: 16,
        factor: 4.06,
        client: 'claude-desktop',
        endpoint: 'incident-prod',
        from: at('09:00:00'),
        to: at('12:00:00'),
      },
      {
        kind: 'slow-connection',
        text: 'cache-redis answers 45× slower than usual.',
        connection: 'cache-redis',
        env: 'dev',
        p95Ms: 1800,
        usualMs: 40,
        factor: 45,
        since: at('13:30:00'),
        timeoutMs: 2000,
        calls: 31,
      },
    ],
    ...patch,
  };
}

/** Nothing has reached the gateway yet: the first-run state. */
export function firstRun(): Insights {
  const empty = { calls: 0, pending: 0, approved: 0, denied: 0, errors: 0, tasks: 0, gatewayP50Ms: null, gatewayP95Ms: null };
  return insights({
    firstCallAt: null,
    unfilteredCalls: 0,
    kpis: { current: empty, previous: null },
    callsOverTime: { from: new Date(NOW - 24 * HOUR).toISOString(), to: new Date(NOW).toISOString(), stepHours: 1, buckets: [] },
    topTools: [],
    byClient: [],
    decisions: {},
    approvals: { approved: 0, rejected: 0, expired: 0, medianWaitS: null, p90WaitS: null, approvedShare: null, waitHistogram: [], slowest: [] },
    deniedByRule: [],
    latency: { byConnection: [] },
    errorsByConnection: [],
    unusual: [],
  });
}

/** The clients this computer has (`GET /api/clients`), as the demo world: two connected, Cursor found, Antigravity not. */
export function clientCards(): ClientCard[] {
  const card = (client: ClientCard['client'], name: string, status: ClientCard['status']): ClientCard => ({
    client,
    name,
    status,
    statusText: status,
    configPath: null,
    configDisplay: null,
    detected: status !== 'not-installed',
    evidence: [],
    version: null,
    warnings: [],
    methods: [],
    links: [],
  });
  return [
    card('claude-desktop', 'Claude Desktop', 'connected'),
    card('codex', 'Codex', 'connected'),
    card('cursor', 'Cursor', 'detected'),
    card('antigravity', 'Antigravity', 'not-installed'),
  ];
}

/** Health now of the connections with errors (`GET /api/connections`). */
export function healthNow(): Map<string, ConnectionHealth> {
  return new Map<string, ConnectionHealth>([
    ['cache-redis', { state: 'degraded', latencyMs: 1800, message: null, checkedAt: at('14:28:00') }],
    ['billing-api', { state: 'unreachable', latencyMs: null, message: 'Connection refused', checkedAt: at('14:28:00') }],
  ]);
}

/** Approval windows of the demo policies (minutes). */
export const WINDOWS = { dev: 5, stage: 10, prod: 10 } as const;
