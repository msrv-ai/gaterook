import { ApprovalItem } from '../../core/api/approvals.api';
import { ClientCard, ClientLink, ClientsView } from '../../core/api/clients.api';
import { ConnectionDetail, ConnectionRow } from '../../core/api/connections.api';
import { HealthRow, Overview } from '../../core/api/overview.api';
import { TunnelState } from '../../core/api/tunnel.api';
import { Env } from '../../core/api/types';

/**
 * Test data in the shapes the core answers (admin.overview, clients, connections, tunnel), with the kit's numbers
 * where the tests compare wording (BLUEPRINT §9: "now" is 2026-10-22 14:30 CEST). Only the specs import this file.
 */

export const NOW = Date.parse('2026-10-22T14:30:00+02:00');

export const at = (hh: number, mm: number, ss = 0) =>
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
  return { name, type: 'postgres', env: 'dev', state, latencyMs: null, message: null, checkedAt: at(14, 29), typeLabel: 'PostgreSQL 16', host: null, ...over };
}

/** The kit's twelve connections (BLUEPRINT §9), in the core's order: by name. */
export const HEALTH: HealthRow[] = [
  health('app-logs', 'healthy', { type: 'logs', latencyMs: 30, typeLabel: 'Loki' }),
  health('aws-stage', 'healthy', { type: 'aws', env: 'stage', typeLabel: 'AWS' }),
  health('billing-api', 'unreachable', {
    type: 'http',
    env: 'stage',
    typeLabel: 'HTTP API',
    host: 'https://billing.stage.acme.dev',
    message: "Can't reach billing.stage.acme.dev. Check the VPN or the host, then test again.",
  }),
  health('cache-redis', 'degraded', { type: 'redis', latencyMs: 1800, typeLabel: 'Redis 7' }),
  health('dev-cluster', 'healthy', { type: 'kubernetes', typeLabel: 'Kubernetes' }),
  health('dev-db', 'healthy', { latencyMs: 12 }),
  health('dev-vm', 'healthy', { type: 'ssh', typeLabel: 'SSH' }),
  health('orders-mongo', 'healthy', { type: 'mongodb', typeLabel: 'MongoDB 7' }),
  health('prod-logs', 'healthy', { type: 'logs', env: 'prod', typeLabel: 'GCP Cloud Logging' }),
  health('prod-replica', 'healthy', { env: 'prod' }),
  health('prod-vm', 'healthy', { type: 'ssh', env: 'prod', typeLabel: 'SSH' }),
  health('stage-db', 'healthy', { env: 'stage' }),
];

type Step = Overview['gettingStarted']['steps'][number];

function steps(connection: boolean, client: boolean, connectionName = 'dev-db', count = 12): Step[] {
  return [
    { id: 'master-key', state: 'done', detail: { lastBackupAt: '2026-10-10T12:00:00Z' } },
    { id: 'connection', state: connection ? 'done' : 'current', detail: connection ? { name: connectionName, typeName: 'PostgreSQL 16', count } : {} },
    { id: 'endpoint', state: connection ? 'done' : 'todo', detail: connection ? { name: 'dev', tools: 4 } : {} },
    { id: 'client', state: client ? 'done' : connection ? 'current' : 'todo', detail: client ? { status: 'Claude Desktop, Codex' } : {} },
    { id: 'playground', state: 'todo', detail: {} },
    { id: 'notifications', state: 'todo', detail: {} },
  ];
}

/** The overview of a gateway in use (the kit's in-use page). */
export function overview(over: Partial<Overview> = {}): Overview {
  return {
    gettingStarted: { done: 4, total: 6, dismissed: false, steps: steps(true, true) },
    setupMap: {
      nodes: [
        { id: 'secrets', count: 11, state: 'done', status: 'Master key backed up · 11 secrets' },
        { id: 'connections', count: 12, state: 'attention', status: '12 connections · billing-api unreachable' },
        { id: 'policies', count: 3, state: 'done', status: '3 environments · mutations need approval' },
        { id: 'endpoints', count: 3, state: 'done', status: '3 endpoints · 2 active tokens' },
        { id: 'clients', count: 2, state: 'done', status: '2 connected · Cursor detected, not connected' },
      ],
    },
    stats: { calls24h: 1287, pending: 3, denied24h: 17, gatewayP95Ms: 31, deltas: { calls24h: 138, denied24h: 5, gatewayP95Ms: -4 }, spark: [] },
    waiting: WAITING,
    recent: [],
    health: HEALTH,
    healthIntervalSeconds: 60,
    ...over,
  };
}

/** Nothing set up yet: no connection, no endpoint, no AI app (the kit's first run). */
export function firstRun(): Overview {
  return overview({
    gettingStarted: { done: 1, total: 6, dismissed: false, steps: steps(false, false) },
    setupMap: { nodes: [{ id: 'connections', count: 0, state: 'todo', status: 'None yet · start here' }] },
    stats: { calls24h: 0, pending: 0, denied24h: 0, gatewayP95Ms: null, deltas: { calls24h: 0, denied24h: 0, gatewayP95Ms: null }, spark: [] },
    waiting: [],
    health: [],
  });
}

/** One connection, dev-db on endpoint dev, no AI app yet (the kit's setup). */
export function setup(): Overview {
  return overview({
    gettingStarted: { done: 3, total: 6, dismissed: false, steps: steps(true, false, 'dev-db', 1) },
    setupMap: { nodes: [{ id: 'connections', count: 1, state: 'done', status: '1 connection' }] },
    stats: { calls24h: 0, pending: 0, denied24h: 0, gatewayP95Ms: null, deltas: { calls24h: 0, denied24h: 0, gatewayP95Ms: null }, spark: [] },
    waiting: [],
    health: [health('dev-db', 'healthy', { latencyMs: 12, host: 'db.dev.internal:5432/app' })],
  });
}

/** Browser OAuth completed without a named token, managed config, or first tool call. */
export function browserSignedIn(): Overview {
  const o = setup();
  return {
    ...o,
    gettingStarted: {
      ...o.gettingStarted,
      done: o.gettingStarted.done + 1,
      steps: o.gettingStarted.steps.map((s) => s.id === 'client' ? { ...s, state: 'done', detail: { status: 'Cursor' } } : s),
    },
    setupMap: { nodes: [...o.setupMap.nodes, { id: 'clients', count: 1, state: 'done', status: '1 connected' }] },
  };
}

function link(client: ClientLink['client'], endpoint: string, env: Env, over: Partial<ClientLink> = {}): ClientLink {
  return {
    id: `cl_${client}_${endpoint}`,
    client,
    endpoint,
    env,
    method: client === 'codex' ? 'helper' : 'mcpb',
    entryName: `gaterook-${endpoint}`,
    configPath: null,
    configDisplay: null,
    grantPrefix: null,
    grantLabel: null,
    tokenName: null,
    status: 'connected',
    statusText: 'Connected',
    createdAt: at(9, 0),
    usedAt: at(14, 20),
    mcpbPath: null,
    lastCallAt: at(14, 20),
    revision: '2025-11-25',
    ...over,
  };
}

export function card(client: ClientCard['client'], name: string, status: ClientCard['status'], links: ClientLink[] = []): ClientCard {
  return {
    client,
    name,
    status,
    statusText: status === 'connected' ? 'Connected' : status === 'detected' ? 'Detected, not connected' : 'Not installed',
    configPath: null,
    configDisplay: null,
    detected: status !== 'not-installed',
    evidence: [],
    version: null,
    warnings: [],
    methods: [],
    links,
  };
}

/** The kit's in-use computer: Claude Desktop on dev and incident-prod, Codex on dev, Cursor found, Antigravity not. */
export function clientsInUse(): ClientsView {
  return {
    scannedAt: at(14, 29),
    clients: [
      card('claude-desktop', 'Claude Desktop', 'connected', [link('claude-desktop', 'dev', 'dev'), link('claude-desktop', 'incident-prod', 'prod')]),
      card('codex', 'Codex', 'connected', [link('codex', 'dev', 'dev')]),
      card('cursor', 'Cursor', 'detected'),
      card('antigravity', 'Antigravity', 'not-installed'),
    ],
  };
}

/** First run and setup: Claude Desktop, Codex and Cursor found, Antigravity not installed, nothing connected. */
export function clientsFound(): ClientsView {
  return {
    scannedAt: at(14, 29),
    clients: [
      card('claude-desktop', 'Claude Desktop', 'detected'),
      card('codex', 'Codex', 'detected'),
      card('cursor', 'Cursor', 'detected'),
      card('antigravity', 'Antigravity', 'not-installed'),
    ],
  };
}

export function clientsBrowserSignedIn(): ClientsView {
  const found = clientsFound();
  const observed = link('cursor', 'dev', 'dev', {
    id: 'og_cursor', method: 'oauth', entryName: null, lastCallAt: null, revision: null,
  });
  return { ...found, clients: found.clients.map((c) => c.client === 'cursor' ? card('cursor', 'Cursor', 'connected', [observed]) : c) };
}

export function row(name: string, env: Env, usedBy: string[]): ConnectionRow {
  return {
    name,
    type: 'postgres',
    typeName: 'PostgreSQL',
    env,
    host: null,
    badges: [],
    secretSource: 'encrypted',
    health: { state: 'healthy', latencyMs: 10, message: null, checkedAt: at(14, 29) },
    usedBy,
    verified: true,
    version: 1,
  };
}

/** The connections as `/api/connections` lists them: eight on dev, three prod ones on incident-prod. */
export const ROWS: ConnectionRow[] = [
  ...['app-logs', 'aws-stage', 'billing-api', 'cache-redis', 'dev-cluster', 'dev-db', 'dev-vm', 'orders-mongo'].map((n) => row(n, n.includes('stage') || n === 'billing-api' ? 'stage' : 'dev', ['dev'])),
  ...['prod-logs', 'prod-replica', 'prod-vm'].map((n) => row(n, 'prod', ['incident-prod'])),
  row('stage-db', 'stage', ['stage-readonly']),
  row('github-mcp', 'dev', []),
];

/** dev-db as `GET /api/connections/dev-db` answers it in setup: on dev, sql_execute in Approval, the password here. */
export function devDb(): ConnectionDetail {
  return {
    name: 'dev-db',
    type: 'postgres',
    typeName: 'PostgreSQL',
    env: 'dev',
    config: {},
    version: 1,
    secrets: [{ field: '/credentials/password', secretId: 'sec_1', name: 'dev-db/password', state: 'stored', version: 1, source: 'local' }],
    health: { state: 'healthy', latencyMs: 12, message: null, checkedAt: at(14, 29) },
    spark24h: [],
    usedBy: [
      { tool: 'sql_list_schemas', endpoint: 'dev', mode: 'READ_ONLY' },
      { tool: 'sql_describe_table', endpoint: 'dev', mode: 'READ_ONLY' },
      { tool: 'sql_query', endpoint: 'dev', mode: 'READ_ONLY' },
      { tool: 'sql_execute', endpoint: 'dev', mode: 'APPROVAL' },
    ],
    verified: true,
    typeLabel: 'PostgreSQL 16',
  };
}

/** The tunnel off, last public from 08:40 to 12:10 (kit: "In the browser · used until 12:10"). */
export function tunnelOff(): TunnelState {
  return {
    provider: 'cloudflare-quick',
    state: 'off',
    host: null,
    publicUrl: null,
    endpoint: 'stage-readonly',
    since: null,
    callers: { anthropicOnly: true },
    cloudflared: null,
    error: null,
    history: [{ from: at(8, 40), to: at(12, 10), endpoint: 'stage-readonly', calls: 19 }],
  };
}
