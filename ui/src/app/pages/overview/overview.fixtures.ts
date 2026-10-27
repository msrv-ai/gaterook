import { ActivityEvent, Histogram } from '../../core/api/activity.api';
import { ApprovalItem } from '../../core/api/approvals.api';
import { HealthRow, Overview } from '../../core/api/overview.api';

/**
 * Test data in the shapes the core answers (admin.overview, approvals, activity), with the kit's numbers where the
 * tests compare wording (BLUEPRINT §9: "now" is 2026-10-22 14:30 CEST). Only the specs import this file.
 */

export const NOW = Date.parse('2026-10-22T14:30:00+02:00');

const at = (hh: number, mm: number, ss = 0) =>
  new Date(Date.parse(`2026-10-22T${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')}:${String(ss).padStart(2, '0')}+02:00`)).toISOString();

export function approval(id: string, over: Partial<ApprovalItem> = {}): ApprovalItem {
  return {
    id,
    tool: 'sql_execute',
    connection: 'dev-db',
    env: 'dev',
    endpoint: 'dev',
    client: 'codex',
    tokenLabel: 'codex-laptop',
    requestedAt: at(14, 29, 12),
    expiresAt: at(14, 34, 12),
    status: 'pending',
    summary: 'sql_execute on dev-db',
    decidedVia: null,
    decidedAt: null,
    commentPolicy: 'optional',
    ...over,
  };
}

export const WAITING: ApprovalItem[] = [
  approval('ap_k8s000000001', {
    tool: 'k8s_rollout_restart',
    connection: 'dev-cluster',
    client: 'claude-desktop',
    tokenLabel: 'claude-desktop',
    requestedAt: at(14, 25, 48),
    expiresAt: at(14, 30, 48),
    summary: 'Restart deployment/api in app-dev',
  }),
  approval('ap_sql000000001'),
  approval('ap_http00000001', {
    tool: 'http_request',
    connection: 'billing-api',
    env: 'stage',
    requestedAt: at(14, 29, 30),
    expiresAt: at(14, 39, 30),
    summary: 'POST /v1/refunds on billing-api',
  }),
];

export function health(name: string, state: string, over: Partial<HealthRow> = {}): HealthRow {
  return { name, type: 'postgres', env: 'dev', state, latencyMs: null, message: null, checkedAt: at(14, 29), ...over };
}

export const HEALTH: HealthRow[] = [
  health('app-logs', 'healthy', { latencyMs: 30 }),
  health('billing-api', 'unreachable', {
    type: 'http',
    env: 'stage',
    message: "Can't reach billing.stage.acme.dev. Check the VPN or the host, then test again.",
  }),
  health('cache-redis', 'degraded', { type: 'redis', latencyMs: 1800 }),
  health('dev-db', 'healthy', { latencyMs: 12 }),
];

export function event(id: number, over: Partial<ActivityEvent> = {}): ActivityEvent {
  return {
    id,
    callId: `tc_${id}`,
    at: at(14, 28, 11),
    phase: 'completed',
    decision: 'allowed',
    tool: 'sql_query',
    connection: 'dev-db',
    env: 'dev',
    client: 'codex',
    endpoint: 'dev',
    tokenLabel: 'codex-laptop',
    durationMs: 38,
    rule: null,
    approvalId: null,
    traceId: null,
    errorKind: null,
    errorMessage: null,
    ...over,
  };
}

/** Hourly calls of the kit's tile: quiet overnight, a peak of 166 at 11:00 (the current hour, 14:00, last). */
export const SPARK = [40, 52, 45, 23, 6, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 12, 31, 60, 120, 166, 140, 70, 94];

export function overview(over: Partial<Overview> = {}): Overview {
  return {
    gettingStarted: {
      done: 5,
      total: 6,
      dismissed: false,
      steps: [
        { id: 'master-key', state: 'done', detail: { lastBackupAt: '2026-10-10T12:00:00Z' } },
        { id: 'connection', state: 'done', detail: { name: 'dev-db', typeName: 'PostgreSQL 16', count: 12 } },
        { id: 'endpoint', state: 'done', detail: { name: 'dev', tools: 22 } },
        { id: 'client', state: 'done', detail: { clients: ['claude-desktop', 'codex'] } },
        { id: 'playground', state: 'current', detail: {} },
        { id: 'notifications', state: 'done', detail: { channels: ['Desktop', 'Slack'] } },
      ],
    },
    setupMap: {
      nodes: [
        { id: 'secrets', count: 11, state: 'attention', status: 'Master key: no-os-protection, backup-needed' },
        { id: 'connections', count: 12, state: 'attention', status: '1 need attention' },
        { id: 'policies', count: 3, state: 'done', status: 'dev, stage, prod' },
        { id: 'endpoints', count: 3, state: 'done', status: '3 endpoints' },
        { id: 'clients', count: 1, state: 'done', status: '1 named tokens' },
      ],
    },
    stats: {
      calls24h: 1284,
      pending: 3,
      denied24h: 17,
      gatewayP95Ms: 31,
      deltas: { calls24h: 138, denied24h: 5, gatewayP95Ms: -4 },
      spark: SPARK,
    },
    waiting: WAITING,
    recent: [event(9, { decision: 'pending', tool: 'http_request' }), event(8), event(7, { decision: 'denied', durationMs: null })],
    health: HEALTH,
    ...over,
  };
}

/** The day's decisions per hour: 1,201 allowed, 46 approved, 9 rejected, 17 denied (7 of them at 10:00). */
export function histogram(): Histogram {
  const buckets = Array.from({ length: 25 }, (_, i) => {
    const from = new Date(Math.floor(NOW / 3_600_000) * 3_600_000 - (24 - i) * 3_600_000).toISOString();
    const to = new Date(Date.parse(from) + 3_600_000).toISOString();
    return { from, to, decisions: {} as Record<string, number>, total: 0 };
  });
  buckets[20].decisions = { denied: 7, allowed: 600, approved: 20 };
  buckets[21].decisions = { denied: 10, allowed: 601, approved: 26, rejected: 9 };
  for (const b of buckets) b.total = Object.values(b.decisions).reduce((a, n) => a + n, 0);
  return { from: buckets[0].from, to: new Date(NOW).toISOString(), stepHours: 1, buckets };
}
