import { ActivityEvent, AuditRow, EventDetail, TraceDetail } from '../core/api/activity.api';
import { ApprovalDetail } from '../core/api/approvals.api';
import { DEMO_APPROVALS } from './fixtures-approvals';

/**
 * Logged calls of the demo world (BLUEPRINT §9) as `GET /api/activity/events/{id}` and
 * `GET /api/activity/traces/{id}` return them, for the event-detail gallery (design/elements/event-detail.html).
 * Dev only.
 */

const TRACE = '4f2a9c71e8d04b5a9c3e2b1f0a6d8e81';

const row = (r: Partial<AuditRow> & Pick<AuditRow, 'id' | 'phase' | 'at' | 'decision'>): AuditRow => ({
  classification: null,
  rule: null,
  approvalId: null,
  approvalDelivery: null,
  protocolVersion: '2026-07-28',
  ingress: 'local',
  principalKind: 'bridge',
  client: 'codex',
  clientInfo: { name: 'codex-mcp-client', version: '0.162.1' },
  tokenLabel: 'codex-laptop',
  args: null,
  argsSha256: null,
  durationMs: null,
  gatewayMs: null,
  resourceMs: null,
  approvalWaitMs: null,
  resultBytes: null,
  resultSha256: null,
  resultPreview: null,
  maskedCount: null,
  truncated: null,
  errorKind: null,
  errorMessage: null,
  facets: null,
  actionSha256: null,
  executedSha256: null,
  outcomeUnknown: null,
  decidedVia: null,
  commented: false,
  traceId: TRACE,
  spanId: null,
  rowSha256: `sha-${r.id}`,
  ...r,
});

const event = (e: Partial<ActivityEvent> & Pick<ActivityEvent, 'id' | 'callId' | 'at' | 'decision' | 'tool'>): ActivityEvent => ({
  phase: 'completed',
  connection: 'dev-db',
  env: 'dev',
  client: 'codex',
  endpoint: 'dev',
  tokenLabel: 'codex-laptop',
  durationMs: null,
  rule: null,
  approvalId: null,
  traceId: TRACE,
  errorKind: null,
  errorMessage: null,
  ...e,
});

const ACTION_SHA = '551fc479fedf19b8cdc4880d0432359a2a2c2833fc2b32d05a5143eff65e9407';

/** sql_execute on dev-db, approved after 47 s. */
const APPROVED: EventDetail = {
  event: event({ id: 412, callId: 'ev_7c41e2d0', at: '2026-10-22T13:52:04+02:00', decision: 'approved', tool: 'sql_execute', durationMs: 47046, rule: 'dev.sql.write-approval', approvalId: 'apr_5d21' }),
  rows: [
    row({
      id: 410,
      phase: 'received',
      at: '2026-10-22T13:52:04+02:00',
      decision: 'pending',
      classification: 'mutation',
      rule: 'dev.sql.write-approval',
      approvalId: 'apr_5d21',
      args: {
        sql: "UPDATE order_sync_jobs SET state = 'queued' WHERE id = 9172;",
        timeout_ms: 5000,
        context: { ticket: 'SUP-4471', customer_email: '‹masked›', customer_phone: '‹masked›' },
      },
    }),
    row({ id: 411, phase: 'resolved', at: '2026-10-22T13:52:51+02:00', decision: 'approved', rule: 'dev.sql.write-approval', approvalId: 'apr_5d21', approvalWaitMs: 47000, actionSha256: ACTION_SHA, decidedVia: 'desktop', commented: true }),
    row({
      id: 412,
      phase: 'completed',
      at: '2026-10-22T13:52:51+02:00',
      decision: 'approved',
      approvalId: 'apr_5d21',
      durationMs: 47046,
      gatewayMs: 8,
      resourceMs: 38,
      approvalWaitMs: 47000,
      resultBytes: 312,
      resultSha256: '7fc28077c9c9e3d925aa47db169d1cbdc7f730f5459f424157e7773578699118',
      resultPreview: '1 row updated',
      maskedCount: 0,
      truncated: false,
      actionSha256: ACTION_SHA,
      executedSha256: ACTION_SHA,
    }),
  ],
};

/** The approval of that call, as Approvals keeps it: the comment and the notifications. */
const APPROVED_APPROVAL: ApprovalDetail = {
  ...DEMO_APPROVALS.executed,
  id: 'apr_5d21',
  requestedAt: '2026-10-22T13:52:04+02:00',
  expiresAt: '2026-10-22T13:57:04+02:00',
  decidedAt: '2026-10-22T13:52:51+02:00',
  decidedVia: 'desktop',
  executedAt: '2026-10-22T13:52:51+02:00',
  comment: 'Re-queue the stuck job. Payload checked against SUP-4471.',
  result: { text: 'UPDATE 1', structured: { command: 'UPDATE', rowCount: 1 }, isError: false, truncated: false },
  timeline: [
    { id: 1, at: '2026-10-22T13:52:04+02:00', kind: 'requested', detail: { by: 'codex-laptop' } },
    { id: 2, at: '2026-10-22T13:52:04+02:00', kind: 'notified', detail: { channels: ['desktop', 'slack'] } },
    { id: 3, at: '2026-10-22T13:52:51+02:00', kind: 'decided', detail: { decision: 'approved', by: 'you', via: 'desktop' } },
    { id: 4, at: '2026-10-22T13:52:51+02:00', kind: 'completed', detail: { durationMs: 38, resultBytes: 312 } },
  ],
};

/** sql_query on dev-db, read only. */
const ALLOWED: EventDetail = {
  event: event({ id: 388, callId: 'ev_2b9f0c14', at: '2026-10-22T13:46:40+02:00', decision: 'allowed', tool: 'sql_query', durationMs: 38 }),
  rows: [
    row({ id: 387, phase: 'received', at: '2026-10-22T13:46:40+02:00', decision: 'allowed', classification: 'read', args: { sql: "SELECT id, status FROM orders WHERE created_at > now() - interval '1 hour' LIMIT 50", max_rows: 50 } }),
    row({ id: 388, phase: 'completed', at: '2026-10-22T13:46:40+02:00', decision: 'allowed', durationMs: 38, gatewayMs: 7, resourceMs: 31, resultBytes: 2150, resultSha256: '0b6e4fbd5d4e9c3f7d1e6b8a2c4f0e9d7a5b3c1e8f6d4b2a0c9e7f5d3b1a8c6e', resultPreview: '12 rows', maskedCount: 2, truncated: false }),
  ],
};

/** DROP TABLE on prod-replica, refused by the prod policy. */
const DENIED: EventDetail = {
  event: event({ id: 301, callId: 'ev_d40e7a93', at: '2026-10-22T11:42:09+02:00', phase: 'received', decision: 'denied', tool: 'sql_execute', connection: 'prod-replica', env: 'prod', endpoint: 'incident-prod', rule: 'prod.sql.block_ddl', traceId: 'b81e44d0920a4203e4b733f52954db89' }),
  rows: [
    row({ id: 301, phase: 'received', at: '2026-10-22T11:42:09+02:00', decision: 'denied', classification: 'deny', rule: 'prod.sql.block_ddl', args: { sql: 'DROP TABLE order_sync_failures;', timeout_ms: 5000 }, traceId: 'b81e44d0920a4203e4b733f52954db89' }),
  ],
};

/** http_request on billing-api: nobody decided within 10 min. */
const EXPIRED: EventDetail = {
  event: event({ id: 276, callId: 'ev_5a1c86f2', at: '2026-10-22T13:35:12+02:00', phase: 'resolved', decision: 'expired', tool: 'http_request', connection: 'billing-api', env: 'stage', rule: 'stage.http.write-approval', approvalId: 'apr_4e90', traceId: '9b07d3a1c5e24f6081b9d2e4f6a8c0e2' }),
  rows: [
    row({
      id: 270,
      phase: 'received',
      at: '2026-10-22T13:35:12+02:00',
      decision: 'pending',
      classification: 'mutation',
      rule: 'stage.http.write-approval',
      approvalId: 'apr_4e90',
      args: {
        method: 'POST',
        url: 'https://billing.stage.acme.dev/v1/refunds',
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': '‹masked›' },
        body: { order_id: 48213, amount: 129.0, currency: 'EUR' },
      },
      traceId: '9b07d3a1c5e24f6081b9d2e4f6a8c0e2',
    }),
    row({ id: 276, phase: 'resolved', at: '2026-10-22T13:45:12+02:00', decision: 'expired', rule: 'stage.http.write-approval', approvalId: 'apr_4e90', approvalWaitMs: 600000, actionSha256: 'c3a1e0f9b8d7c6a5e4f3d2c1b0a9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b2a1', decidedVia: 'timeout', traceId: '9b07d3a1c5e24f6081b9d2e4f6a8c0e2' }),
  ],
};

/** redis_get on cache-redis: allowed, then the resource timed out. */
const ERROR: EventDetail = {
  event: event({ id: 371, callId: 'ev_e3f9b257', at: '2026-10-22T13:47:05+02:00', decision: 'error', tool: 'redis_get', connection: 'cache-redis', durationMs: 2010, errorKind: 'TIMEOUT', errorMessage: 'cache.dev.internal:6379 did not answer within 2.0 s' }),
  rows: [
    row({ id: 370, phase: 'received', at: '2026-10-22T13:47:05+02:00', decision: 'allowed', classification: 'read', args: { key: 'order:sync:cursor' } }),
    row({ id: 371, phase: 'completed', at: '2026-10-22T13:47:07+02:00', decision: 'error', durationMs: 2010, gatewayMs: 10, resourceMs: 2000, resultBytes: 118, resultSha256: 'e0d1c2b3a4958677a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1', maskedCount: 0, truncated: false, errorKind: 'TIMEOUT', errorMessage: 'cache.dev.internal:6379 did not answer within 2.0 s' }),
  ],
};

export const DEMO_EVENTS = { approved: APPROVED, allowed: ALLOWED, denied: DENIED, expired: EXPIRED, error: ERROR };
export const DEMO_EVENT_APPROVAL = APPROVED_APPROVAL;

/** The trace of the approved call: one agent task in four phases. */
export const DEMO_TRACE: TraceDetail = {
  trace: {
    traceId: TRACE,
    title: 'Fix failing order sync',
    titleSource: 'meta',
    client: 'codex',
    endpoint: 'dev',
    startedAt: '2026-10-22T13:44:02+02:00',
    endedAt: '2026-10-22T14:02:10+02:00',
    calls: 23,
    status: 'ended',
    decisions: { allowed: 21, approved: 1, error: 1 },
    waitedForPeopleMs: 47000,
  },
  phases: [
    { name: 'Explore', from: '2026-10-22T13:44:02+02:00', to: '2026-10-22T13:51:10+02:00', calls: 9, decisions: { allowed: 8, error: 1 }, summary: 'logs_search on app-logs, sql_query on dev-db, redis_get on cache-redis' },
    { name: 'Change', from: '2026-10-22T13:51:10+02:00', to: '2026-10-22T13:52:51+02:00', calls: 3, decisions: { allowed: 2, approved: 1 }, summary: 'sql_query twice, then sql_execute approved by you after 47 s' },
    { name: 'Wait for deploy', from: '2026-10-22T13:53:20+02:00', to: '2026-10-22T13:58:05+02:00', calls: 6, decisions: { allowed: 6 }, summary: 'k8s_get on dev-cluster, polling the rollout of deployment/api' },
    { name: 'Verify', from: '2026-10-22T13:58:05+02:00', to: '2026-10-22T14:02:10+02:00', calls: 5, decisions: { allowed: 5 }, summary: 'logs_search and sql_query: no sync errors since 13:58' },
  ],
  calls: [],
};
