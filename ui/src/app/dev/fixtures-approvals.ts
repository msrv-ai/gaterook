import { ActionSegment, ApprovalDetail, ApprovalItem, ApprovalTimelineEntry, Diff } from '../core/api/approvals.api';
import { JsonValue } from '../core/api/types';

/**
 * The approval requests of the demo world (BLUEPRINT §9) as `GET /api/approvals/{id}` returns them, one per state of
 * design/elements/approval-request.html. Dev only: the gallery imports it, production code never does.
 */

const text = (v: string): ActionSegment[] => [{ t: 'text', v }];

const action = (kind: string, language: string, value: string | ActionSegment[], diff: Diff | null = null) => {
  const segments = typeof value === 'string' ? text(value) : value;
  return {
    kind,
    language,
    text: segments.map((s) => s.v).join(''),
    segments,
    hiddenChars: segments.filter((s) => s.t === 'invisible').length,
    confusables: segments.filter((s) => s.t === 'confusable').length,
    warning: null,
    diff,
  };
};

let eventId = 0;
const ev = (at: string, kind: string, detail: { [k: string]: JsonValue } = {}): ApprovalTimelineEntry => ({
  id: ++eventId,
  at,
  kind,
  detail,
});

const base = (p: Partial<ApprovalDetail> & Pick<ApprovalDetail, 'id' | 'tool' | 'connection' | 'env' | 'requestedAt' | 'expiresAt'>): ApprovalDetail => ({
  callId: `tc_${p.id.slice(3)}`,
  toolKey: p.tool,
  endpoint: 'dev',
  client: 'codex',
  tokenLabel: 'codex-laptop',
  status: 'pending',
  action: null,
  target: null,
  summary: null,
  fields: [],
  impact: [],
  warnings: [],
  rule: null,
  agentNote: null,
  commentPolicy: 'optional',
  comment: null,
  digest: '6d04d068c5931d7b578d12a8ea12ae345aa8e7d26f49528610d25774675a582b',
  decidedVia: null,
  decidedAt: null,
  executedAt: null,
  error: null,
  result: null,
  resultExpired: false,
  timeline: [],
  channels: { desktop: 'sent', slack: 'sent', telegram: 'off' },
  ...p,
});

const UPDATE_48213 = "UPDATE orders SET status = 'cancelled' WHERE id = 48213;";
const SQL_RULE = { id: 'dev.sql.dml-approval', text: 'DML requires approval. Statements that change rows always wait for a human.' };
const K8S_RULE = { id: 'dev.k8s.restart-approval', text: 'restarting a workload requires approval.' };
const HTTP_RULE = { id: 'stage.http.write-approval', text: 'POST, PUT, PATCH and DELETE require approval.' };
const SQL_IMPACT = [
  { label: 'Target table', text: 'orders' },
  { label: 'Estimated rows', text: '1 (from EXPLAIN)' },
  { label: 'Transaction', text: 'Yes, rolled back on error' },
];
const SQL_NOTE = 'Customer asked to cancel order 48213 in ticket SUP-1182. Cancelling it so the order sync stops retrying the payment.';
const K8S_NOTE = 'The api pods still use the old ORDER_SYNC_URL after the ConfigMap change. A restart makes them read it again.';

/** The kit's demo states, in its order. */
export const DEMO_APPROVALS: Record<
  'sql' | 'k8s' | 'http' | 'prod' | 'hidden' | 'approved' | 'rejected' | 'expired' | 'executed' | 'failed',
  ApprovalDetail
> = {
  sql: base({
    id: 'ap_3kq7m2x9vt4p',
    tool: 'sql_execute',
    connection: 'dev-db',
    env: 'dev',
    requestedAt: '2026-10-22T14:29:12+02:00',
    expiresAt: '2026-10-22T14:34:12+02:00',
    action: action('sql', 'sql', UPDATE_48213),
    target: 'dev-db · db.dev.internal:5432/app',
    rule: SQL_RULE,
    impact: [...SQL_IMPACT, { label: 'Statement timeout', text: '30 s' }],
    agentNote: SQL_NOTE,
  }),
  k8s: base({
    id: 'ap_8hx2c4k1rw7q',
    tool: 'k8s_rollout_restart',
    connection: 'dev-cluster',
    env: 'dev',
    client: 'claude-desktop',
    tokenLabel: 'claude-desktop',
    requestedAt: '2026-10-22T14:25:48+02:00',
    expiresAt: '2026-10-22T14:30:48+02:00',
    action: action('command', 'sh', 'kubectl rollout restart deployment/api --namespace app-dev'),
    target: 'dev-cluster · namespace app-dev',
    rule: K8S_RULE,
    impact: [
      { label: 'Workload', text: 'deployment/api in app-dev' },
      { label: 'Pods restarted', text: '3, one at a time' },
      { label: 'Expected downtime', text: 'None (rolling, max unavailable 0)' },
      { label: 'Image', text: 'registry.acme.dev/api:1.42.0, unchanged' },
    ],
    agentNote: K8S_NOTE,
  }),
  http: base({
    id: 'ap_5tq9b3m8zr2k',
    tool: 'http_request',
    connection: 'billing-api',
    env: 'stage',
    requestedAt: '2026-10-22T14:29:30+02:00',
    expiresAt: '2026-10-22T14:39:30+02:00',
    action: action(
      'http',
      'http',
      'POST https://billing.stage.acme.dev/v1/refunds\nContent-Type: application/json\nIdempotency-Key: refund-48213\nAuthorization: ‹masked›\n\n{\n  "order_id": 48213,\n  "amount": 1999,\n  "currency": "EUR",\n  "reason": "duplicate charge on order 48213"\n}',
    ),
    target: 'billing-api · billing.stage.acme.dev',
    rule: HTTP_RULE,
    impact: [
      { label: 'Host', text: 'billing.stage.acme.dev, in the allowlist' },
      { label: 'Retries', text: 'Safe: idempotency key set' },
      { label: 'Timeout', text: '10 s' },
    ],
    agentNote: 'The customer was charged twice for order 48213. Refunding the duplicate charge of 19.99 EUR.',
  }),
  prod: base({
    id: 'ap_7rv2n6x4qm1d',
    tool: 'ssh_run',
    connection: 'prod-vm',
    env: 'prod',
    endpoint: 'incident-prod',
    client: 'claude-desktop',
    tokenLabel: 'claude-desktop',
    requestedAt: '2026-10-22T14:29:05+02:00',
    expiresAt: '2026-10-22T14:39:05+02:00',
    action: action('runbook', 'sh', '# runbook restart-api · parameters: none · expands to:\nsudo systemctl restart api.service'),
    target: 'prod-vm · 10.40.0.21 · runbook restart-api · as deploy',
    rule: { id: 'prod.ssh.runbook-approval', text: 'restart-api changes the host. Every runbook that is not read only runs on prod only after a human approves it with a comment.' },
    impact: [
      { label: 'Runbook', text: 'restart-api · no parameters' },
      { label: 'Host', text: 'prod-vm · 10.40.0.21' },
      { label: 'Runs as', text: 'deploy, SSH CA certificate valid 5 min' },
      { label: 'Timeout', text: '60 s' },
    ],
    agentNote: 'The api on prod has returned 502 since 13:52, according to prod-logs, and service-status shows it stuck. The runbook for this is restart-api.',
    commentPolicy: 'required',
  }),
  hidden: base({
    id: 'ap_2wd8k5q7nv3x',
    tool: 'sql_execute',
    connection: 'dev-db',
    env: 'dev',
    requestedAt: '2026-10-22T14:28:56+02:00',
    expiresAt: '2026-10-22T14:33:56+02:00',
    action: action('sql', 'sql', [
      { t: 'text', v: 'UPDATE ' },
      { t: 'confusable', v: 'о' },
      { t: 'text', v: "rders SET status = 'cancelled', note = 'customer asked in SUP-1190, refund handled by billing' WHERE id = 48213;" },
      { t: 'invisible', v: '‹U+202E›' },
      { t: 'text', v: '\n' },
      { t: 'invisible', v: '‹U+2066›' },
      { t: 'text', v: 'DELETE FROM payments WHERE order_id = 48213;' },
      { t: 'invisible', v: '‹U+2069›' },
    ]),
    target: 'dev-db · db.dev.internal:5432/app · 2 statements',
    rule: SQL_RULE,
    impact: [
      { label: 'Statements', text: '2: UPDATE, DELETE' },
      { label: 'Target tables', text: 'оrders (not orders), payments' },
      { label: 'Transaction', text: 'Yes, rolled back on error' },
    ],
    agentNote: 'Cancelling order 48213 as the customer asked in ticket SUP-1190.',
  }),
  approved: base({
    id: 'ap_9mk3t7v2xq5w',
    tool: 'k8s_rollout_restart',
    connection: 'dev-cluster',
    env: 'dev',
    client: 'claude-desktop',
    tokenLabel: 'claude-desktop',
    status: 'running',
    requestedAt: '2026-10-22T14:27:31+02:00',
    expiresAt: '2026-10-22T14:32:31+02:00',
    decidedAt: '2026-10-22T14:29:20+02:00',
    decidedVia: 'app',
    comment: 'Config change is merged, restart is expected.',
    action: action('command', 'sh', 'kubectl rollout restart deployment/api --namespace app-dev'),
    target: 'dev-cluster · namespace app-dev',
    rule: K8S_RULE,
    impact: [
      { label: 'Workload', text: 'deployment/api in app-dev' },
      { label: 'Pods restarted', text: '3, one at a time' },
      { label: 'Expected downtime', text: 'None (rolling, max unavailable 0)' },
    ],
    agentNote: K8S_NOTE,
    timeline: [
      ev('2026-10-22T14:27:31+02:00', 'requested', { by: 'claude-desktop' }),
      ev('2026-10-22T14:27:31+02:00', 'notified', { channels: ['desktop', 'slack'] }),
      ev('2026-10-22T14:29:20+02:00', 'decided', { decision: 'approved', by: 'you', via: 'app' }),
    ],
  }),
  rejected: base({
    id: 'ap_4pz6r1m9kx8t',
    tool: 'k8s_apply',
    connection: 'dev-cluster',
    env: 'dev',
    client: 'claude-desktop',
    tokenLabel: 'claude-desktop',
    status: 'rejected',
    requestedAt: '2026-10-22T13:41:05+02:00',
    expiresAt: '2026-10-22T13:46:05+02:00',
    decidedAt: '2026-10-22T13:42:17+02:00',
    decidedVia: 'slack',
    comment: '1.43.1 has not passed CI yet. Wait for the green build.',
    action: action('diff', 'diff', '', {
      added: 2,
      removed: 2,
      hunks: [
        {
          header: '@@ -12,4 +12,4 @@ metadata:',
          lines: [
            { t: 'context', v: 'spec:' },
            { t: 'del', v: '  replicas: 2' },
            { t: 'add', v: '  replicas: 3' },
            { t: 'context', v: '  selector:' },
          ],
        },
        {
          header: '@@ -21,4 +21,4 @@ spec:',
          lines: [
            { t: 'context', v: '      containers:' },
            { t: 'context', v: '        - name: api' },
            { t: 'del', v: '          image: registry.acme.dev/api:1.42.0' },
            { t: 'add', v: '          image: registry.acme.dev/api:1.43.1' },
          ],
        },
      ],
    }),
    target: 'deployment/api · app-dev',
    rule: { id: 'dev.k8s.apply-approval', text: 'applying a manifest requires approval.' },
    impact: [
      { label: 'Workload', text: 'deployment/api in app-dev' },
      { label: 'Replicas', text: '2 to 3' },
      { label: 'Image', text: 'api:1.42.0 to api:1.43.1' },
    ],
    agentNote: 'Ships 1.43.1 with the order sync fix and adds a replica for the backlog.',
    timeline: [
      ev('2026-10-22T13:41:05+02:00', 'requested', { by: 'claude-desktop' }),
      ev('2026-10-22T13:41:05+02:00', 'notified', { channels: ['desktop', 'slack'] }),
      ev('2026-10-22T13:42:17+02:00', 'decided', { decision: 'rejected', by: 'you', via: 'slack' }),
    ],
  }),
  expired: base({
    id: 'ap_6nh4w8c2tq9r',
    tool: 'http_request',
    connection: 'billing-api',
    env: 'stage',
    status: 'expired',
    requestedAt: '2026-10-22T13:05:40+02:00',
    expiresAt: '2026-10-22T13:15:40+02:00',
    action: action('http', 'http', 'POST https://billing.stage.acme.dev/v1/invoices/inv_2291/void\nAuthorization: ‹masked›'),
    target: 'billing-api · not run',
    rule: HTTP_RULE,
    impact: [
      { label: 'Host', text: 'billing.stage.acme.dev, in the allowlist' },
      { label: 'Retries', text: 'Not safe: no idempotency key' },
    ],
    agentNote: 'Invoice inv_2291 was issued for the cancelled order. Voiding it.',
    timeline: [
      ev('2026-10-22T13:05:40+02:00', 'requested', { by: 'codex-laptop' }),
      ev('2026-10-22T13:05:40+02:00', 'notified', { channels: ['desktop', 'slack'] }),
      ev('2026-10-22T13:15:40+02:00', 'expired', { at: '2026-10-22T11:15:40Z' }),
    ],
  }),
  executed: base({
    id: 'ap_1xr5v9m3kd7p',
    tool: 'sql_execute',
    connection: 'dev-db',
    env: 'dev',
    status: 'completed',
    requestedAt: '2026-10-22T12:20:31+02:00',
    expiresAt: '2026-10-22T12:25:31+02:00',
    decidedAt: '2026-10-22T12:21:18+02:00',
    decidedVia: 'app',
    executedAt: '2026-10-22T12:21:18.038+02:00',
    comment: 'Customer asked to cancel order 48213, confirmed in the support ticket.',
    action: action('sql', 'sql', UPDATE_48213),
    target: 'dev-db · ran at 12:21:18',
    rule: { id: 'dev.sql.dml-approval', text: 'DML requires approval.' },
    impact: SQL_IMPACT,
    agentNote: SQL_NOTE,
    result: { text: 'Approved by you and executed on dev-db (dev): 1 row updated.', structured: { committed: true, rowsAffected: 1 }, isError: false, truncated: false },
    timeline: [
      ev('2026-10-22T12:20:31+02:00', 'requested', { by: 'codex-laptop' }),
      ev('2026-10-22T12:20:31+02:00', 'notified', { channels: ['desktop', 'slack'] }),
      ev('2026-10-22T12:21:18+02:00', 'decided', { decision: 'approved', by: 'you', via: 'app' }),
      ev('2026-10-22T12:21:18+02:00', 'completed', { durationMs: 38, resultBytes: 212 }),
    ],
  }),
  failed: base({
    id: 'ap_3cw7q2n8vh4m',
    tool: 'ssh_run',
    connection: 'dev-vm',
    env: 'dev',
    status: 'failed',
    requestedAt: '2026-10-22T13:58:02+02:00',
    expiresAt: '2026-10-22T14:03:02+02:00',
    decidedAt: '2026-10-22T13:59:05+02:00',
    decidedVia: 'slack',
    executedAt: '2026-10-22T13:59:07+02:00',
    error: 'The runbook failed with exit code 1',
    action: action('runbook', 'sh', '# runbook compose-restart · service = worker · expands to:\ndocker compose restart worker'),
    target: 'dev-vm · 10.20.0.14 · runbook compose-restart · as deploy',
    rule: { id: 'dev.ssh.runbook-approval', text: 'read-only runbooks run at once, a runbook that changes the host always waits for a human.' },
    impact: [
      { label: 'Runbook', text: 'compose-restart · service worker' },
      { label: 'Host', text: 'dev-vm · 10.20.0.14' },
      { label: 'Runs as', text: 'deploy, SSH CA certificate valid 5 min' },
      { label: 'Timeout', text: '60 s' },
    ],
    agentNote: 'The worker container holds a stale connection to cache-redis. Restarting it should clear the timeouts.',
    result: {
      text: 'Error response from daemon: Cannot restart container app-worker-1: container is marked for removal and cannot be started',
      structured: { exitCode: 1 },
      isError: true,
      truncated: false,
    },
    timeline: [
      ev('2026-10-22T13:58:02+02:00', 'requested', { by: 'codex-laptop' }),
      ev('2026-10-22T13:58:02+02:00', 'notified', { channels: ['desktop', 'slack'] }),
      ev('2026-10-22T13:59:05+02:00', 'decided', { decision: 'approved', by: 'you', via: 'slack' }),
      ev('2026-10-22T13:59:07+02:00', 'failed', { durationMs: 2400, error: 'exit code 1' }),
    ],
  }),
};

const item = (d: ApprovalDetail): ApprovalItem => ({
  id: d.id,
  tool: d.tool,
  connection: d.connection,
  env: d.env,
  endpoint: d.endpoint,
  client: d.client,
  tokenLabel: d.tokenLabel,
  requestedAt: d.requestedAt,
  expiresAt: d.expiresAt,
  status: d.status,
  summary: d.summary,
  decidedVia: d.decidedVia,
  decidedAt: d.decidedAt,
  commentPolicy: d.commentPolicy,
});

/** Overview's "Waiting for you": the three pending requests, by time to auto-deny. */
export const DEMO_WAITING: { readonly item: ApprovalItem; readonly preview: string }[] = [
  { item: item(DEMO_APPROVALS.k8s), preview: 'kubectl rollout restart deployment/api --namespace app-dev' },
  { item: item(DEMO_APPROVALS.sql), preview: UPDATE_48213 },
  { item: item(DEMO_APPROVALS.http), preview: 'POST https://billing.stage.acme.dev/v1/refunds' },
];
