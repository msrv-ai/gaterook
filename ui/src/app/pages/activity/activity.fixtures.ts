import { ActivityEvent, EventDetail, Facets, Histogram, HistogramBucket, SetupChange, TraceDetail, TraceRow } from '../../core/api/activity.api';
import { TokenRow } from '../../core/api/endpoints.api';
import { Decision } from '../../core/api/types';

/**
 * The kit's Activity world (design/pages/activity.html) for the page's tests: the clock stands at 14:30 on
 * 2026-10-22, calls of the last hour, tasks, setup changes and the access that made the calls.
 */

/** 14:30 local time (times on screen are local, so the tests read the same in every time zone). */
export const NOW = new Date(2026, 9, 22, 14, 30).getTime();
export const MIN = 60_000;
export const HOUR = 60 * MIN;
/** An instant `ms` before now, as the core writes it. */
export const ago = (ms: number) => new Date(NOW - ms).toISOString();

export function call(id: number, msAgo: number, decision: Decision, tool: string, extra: Partial<ActivityEvent> = {}): ActivityEvent {
  return {
    id,
    callId: `ev_${id}`,
    at: ago(msAgo),
    phase: decision === 'pending' ? 'received' : 'completed',
    decision,
    tool,
    connection: 'dev-db',
    env: 'dev',
    client: 'codex',
    endpoint: 'dev',
    tokenLabel: 'codex-laptop',
    durationMs: decision === 'pending' ? null : 38,
    rule: null,
    approvalId: null,
    traceId: null,
    errorKind: null,
    errorMessage: null,
    ...extra,
  };
}

/** The first rows of the kit's events table, newest first. */
export function kitCalls(): ActivityEvent[] {
  return [
    call(18, 30_000, 'pending', 'http_request', { connection: 'billing-api', env: 'stage', approvalId: 'ap_http', traceId: 'c7e19d4a02b84f6e9a31d5c8b7f20e64' }),
    call(17, 48_000, 'pending', 'sql_execute', { approvalId: 'ap_sql', traceId: 'c7e19d4a02b84f6e9a31d5c8b7f20e64' }),
    call(16, 2 * MIN, 'allowed', 'sql_query', { durationMs: 38, traceId: 'c7e19d4a02b84f6e9a31d5c8b7f20e64' }),
    call(15, 2.3 * MIN, 'allowed', 'ssh_run', { connection: 'dev-vm', client: 'claude-desktop', tokenLabel: 'claude-desktop', durationMs: 412 }),
    call(9, 17 * MIN, 'denied', 'sql_execute', { connection: 'prod-replica', env: 'prod', client: 'claude-desktop', endpoint: 'incident-prod', tokenLabel: 'claude-desktop', durationMs: null, rule: 'prod.sql.block_ddl' }),
    call(6, 32 * MIN, 'rejected', 'k8s_apply', { connection: 'dev-cluster', client: 'claude-desktop', durationMs: null, approvalId: 'ap_k8s' }),
    call(4, 38 * MIN, 'approved', 'sql_execute', { durationMs: 47_000 }),
    call(3, 43 * MIN, 'error', 'redis_get', { connection: 'cache-redis', durationMs: 2_000 }),
    call(2, 59 * MIN, 'expired', 'http_request', { connection: 'billing-api', env: 'stage', durationMs: null }),
    call(1, 2 * HOUR, 'allowed', 'sql_query', { connection: 'stage-db', env: 'stage', client: 'claude-ai', endpoint: 'stage-readonly', tokenLabel: null }),
  ];
}

/** 24 hourly buckets ending now; `counts[i]` are the decisions of bucket i (oldest first). */
export function histogram(counts: Readonly<Record<string, number>>[] = []): Histogram {
  const buckets: HistogramBucket[] = [];
  for (let i = 0; i < 24; i++) {
    const decisions = counts[i] ?? {};
    const total = Object.values(decisions).reduce((n, v) => n + v, 0);
    buckets.push({ from: ago((24 - i) * HOUR), to: ago((23 - i) * HOUR), decisions, total });
  }
  return { from: buckets[0].from, to: ago(0), stepHours: 1, buckets };
}

/** The kit's last hours: 10 calls, 2 of them waiting, all in the last two buckets. */
export function kitHistogram(): Histogram {
  const counts: Record<string, number>[] = [];
  counts[22] = { allowed: 1 };
  counts[23] = { pending: 2, allowed: 2, denied: 1, rejected: 1, approved: 1, error: 1, expired: 1 };
  return histogram(counts);
}

export const FACETS: Facets = {
  decision: [
    { name: 'allowed', count: 3 },
    { name: 'pending', count: 2 },
    { name: 'denied', count: 1 },
  ],
  tool: [
    { name: 'sql_query', count: 2 },
    { name: 'sql_execute', count: 3 },
  ],
  client: [{ name: 'codex', count: 7 }],
  endpoint: [{ name: 'dev', count: 9 }],
  connection: [{ name: 'dev-db', count: 4 }],
  env: [{ name: 'dev', count: 7 }],
};

export function trace(id: string, title: string, msAgoStart: number, msAgoEnd: number, extra: Partial<TraceRow> = {}): TraceRow {
  return {
    traceId: id,
    title,
    titleSource: 'meta',
    client: 'codex',
    endpoint: 'dev',
    startedAt: ago(msAgoStart),
    endedAt: ago(msAgoEnd),
    calls: 5,
    status: 'ended',
    decisions: { allowed: 5 },
    waitedForPeopleMs: 0,
    ...extra,
  };
}

export const WAITING_TASK = trace('c7e19d4a02b84f6e9a31d5c8b7f20e64', 'Cancel duplicate order 48213', 5 * MIN, 30_000, { status: 'waiting', decisions: { allowed: 3, pending: 2 } });
export const ENDED_TASK = trace('4f2a9c71e8d04b5a9c3e2b1f0a6d8e81', 'Fix failing order sync', 46 * MIN, 28 * MIN, { calls: 23, decisions: { allowed: 21, approved: 1, error: 1 }, waitedForPeopleMs: 47_000 });
export const OLD_TASK = trace('0a1b2c3d4e5f60718293a4b5c6d7e8f9', 'Rotate the cache', 30 * HOUR, 29 * HOUR);

export function traceDetail(t: TraceRow, calls: ActivityEvent[] = []): TraceDetail {
  return { trace: t, phases: [], calls };
}

export function change(id: number, msAgo: number, what: string, subject: string, extra: Partial<SetupChange> = {}): SetupChange {
  return { id, at: ago(msAgo), what, subject, from: null, to: null, by: 'You', reason: null, env: null, endpoint: null, connection: null, details: null, ...extra };
}

/** The kit's four changes of the day (newest first), and one older. */
export function kitChanges(): SetupChange[] {
  return [
    change(4, 2 * HOUR, 'tunnel', 'Tunnel · Cloudflare', { from: 'On', to: 'Off' }),
    change(3, 4 * HOUR, 'mode', 'http_get on billing-api, endpoint dev', { from: 'Read only', to: 'Off', endpoint: 'dev', reason: 'Agents only need http_request there, and it asks first.' }),
    change(2, 5 * HOUR, 'mode', 'ssh_exec on dev-vm, endpoint dev', { from: 'Approval', to: 'Off', endpoint: 'dev', reason: 'Runbooks cover what agents need on dev-vm.' }),
    change(1, 6 * HOUR, 'tunnel', 'Tunnel · Cloudflare', { from: 'Off', to: 'On', reason: 'Try stage-readonly from claude.ai.' }),
    change(0, 30 * HOUR, 'token', 'Token old-claude for dev', { from: 'Active', to: 'Revoked', endpoint: 'dev' }),
  ];
}

export function token(name: string, kind: 'named' | 'grant', extra: Partial<TokenRow> = {}): TokenRow {
  return {
    id: kind === 'grant' ? `gr_${name}` : `tok_${name}`,
    kind,
    name,
    prefix: kind === 'grant' ? null : 'gwt_Ie-a',
    client: kind === 'grant' ? name.split('-')[0] : null,
    createdAt: ago(10 * HOUR),
    expiresAt: kind === 'grant' ? null : ago(-3 * 24 * HOUR),
    lastUsedAt: ago(30_000),
    revokedAt: null,
    state: 'active',
    ...extra,
  };
}

/** The access of the kit's endpoint dev: Codex's grant, an active named token, a revoked one. */
export function devTokens(): TokenRow[] {
  return [
    token('codex-laptop', 'grant', { client: 'codex' }),
    token('inspector-debug', 'named'),
    token('old-claude', 'named', { state: 'revoked', revokedAt: ago(HOUR) }),
  ];
}

export function detailOf(e: ActivityEvent): EventDetail {
  return { event: e, rows: [] };
}
