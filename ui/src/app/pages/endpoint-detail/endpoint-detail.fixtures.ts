import { ClientCard, ClientsView } from '../../core/api/clients.api';
import { ConnectionRow } from '../../core/api/connections.api';
import {
  ConnectionPreview,
  EndpointDetail,
  EndpointRisk,
  EndpointSettings,
  EndpointSummary,
  ToolGroup,
  ToolRow,
  TokenRow,
  WiredConnection,
} from '../../core/api/endpoints.api';
import { Policies } from '../../core/api/policies.api';
import { Env, ToolMode } from '../../core/api/types';

/*
 * Test data only (imported by the specs, never by the page): endpoint `dev` of the demo world (BLUEPRINT §9) in the
 * shapes the admin API answers, so assertions read like the kit page.
 */

export const NOW = Date.parse('2026-10-22T14:30:00+02:00');
export const at = (hhmmss: string): string => new Date(`2026-10-22T${hhmmss}+02:00`).toISOString();

export function risk(over: Partial<EndpointRisk> = {}): EndpointRisk {
  return {
    state: 'present',
    underlying: 'present',
    present: ['privateData', 'untrustedContent', 'externalChannel'],
    ingredients: { privateData: 'present', untrustedContent: 'present', externalChannel: 'present' },
    sources: [
      { ingredient: 'privateData', tool: 'sql_query', connection: 'dev-db', text: 'sql_query reads dev-db' },
      { ingredient: 'untrustedContent', tool: 'logs_search', connection: 'app-logs', text: 'logs_search returns log lines users wrote' },
      { ingredient: 'externalChannel', tool: 'ssh_run', connection: 'dev-vm', text: 'ssh_run runs fixed runbooks on dev-vm' },
      { ingredient: 'externalChannel', tool: 'http_request', connection: 'billing-api', text: 'http_request can POST to billing-api' },
    ],
    published: false,
    dismissed: null,
    ...over,
  };
}

export function summary(over: Partial<EndpointSummary> = {}): EndpointSummary {
  return {
    slug: 'dev',
    description: 'Local development',
    url: 'http://127.0.0.1:7465/mcp/dev',
    env: 'stage',
    access: 'standard',
    status: 'running',
    tools: { read: 14, approval: 3, off: 5 },
    connections: { total: 8, byEnv: { dev: 6, stage: 2, prod: 0 } },
    health: { healthy: 6, degraded: 1, unreachable: 1 },
    approvalsWaiting: 3,
    clients: ['claude-desktop', 'codex'],
    tokens: { active: 2, expiringSoon: 1 },
    published: false,
    risk: risk(),
    blockedReason: null,
    version: 8,
    ...over,
  };
}

const wire = (name: string, type: string, typeName: string, env: Env, tools: [number, number, number], health = 'healthy'): WiredConnection => ({
  name,
  type,
  typeName,
  env,
  health,
  problem: null,
  tools: { read: tools[0], approval: tools[1], off: tools[2] },
});

/** The kit's eight connections on dev: six in dev, two in stage; billing-api unreachable, cache-redis slow. */
export function wired(): WiredConnection[] {
  return [
    wire('dev-db', 'postgres', 'PostgreSQL', 'dev', [3, 1, 0]),
    wire('orders-mongo', 'mongodb', 'MongoDB', 'dev', [2, 0, 0]),
    wire('cache-redis', 'redis', 'Redis', 'dev', [2, 0, 0], 'degraded'),
    wire('dev-vm', 'ssh', 'SSH', 'dev', [1, 0, 1]),
    wire('app-logs', 'logs', 'Logs', 'dev', [1, 0, 1]),
    wire('dev-cluster', 'kubernetes', 'Kubernetes', 'dev', [1, 1, 2]),
    wire('aws-stage', 'aws', 'AWS', 'stage', [2, 0, 0]),
    wire('billing-api', 'http', 'HTTP API', 'stage', [0, 1, 1], 'unreachable'),
  ];
}

export function settings(over: Partial<EndpointSettings> = {}): EndpointSettings {
  return {
    description: 'Local development',
    access: 'standard',
    instructions: null,
    instructionsMax: 4000,
    limits: {},
    protocol: { revisions: ['2026-07-28', '2025-11-25'], approvalDelivery: 'auto' },
    approvalWait: { firstWaitSeconds: 15 },
    oauthEnabled: false,
    version: 8,
    usage: { callsLastMinute: 14, rateLimitPerToken: 120, rateLimitPerTool: 30 },
    revisions: [
      { rev: '2026-07-28', served: true, clients: ['codex'], calls: 30 },
      { rev: '2025-11-25', served: true, clients: ['claude-desktop'], calls: 12 },
    ],
    ...over,
  };
}

export function detail(over: { endpoint?: Partial<EndpointSummary>; settings?: Partial<EndpointSettings>; connections?: WiredConnection[] } = {}): EndpointDetail {
  return { endpoint: summary(over.endpoint), connections: over.connections ?? wired(), settings: settings(over.settings) };
}

export function tool(name: string, mode: ToolMode, over: Partial<ToolRow> = {}): ToolRow {
  const readOnly = mode === 'READ_ONLY' || !/execute|request|restart|run/.test(name);
  return {
    toolKey: name,
    name,
    annotations: { readOnlyHint: readOnly, destructiveHint: !readOnly, idempotentHint: readOnly, openWorldHint: false },
    declaredBy: null,
    mode,
    storedMode: mode,
    readOnlyAllowed: readOnly,
    reason: readOnly ? null : 'Mutates data: approval, bypass or off',
    readOnlyConfirmed: false,
    definitionHash: `hash-${name}`,
    overrides: null,
    version: 0,
    ...over,
  } as ToolRow;
}

export function groups(): ToolGroup[] {
  return [
    {
      group: 'PostgreSQL',
      connection: 'dev-db',
      env: 'dev',
      tools: [tool('sql_list_schemas', 'READ_ONLY'), tool('sql_describe_table', 'READ_ONLY'), tool('sql_query', 'READ_ONLY'), tool('sql_execute', 'APPROVAL')],
    },
    { group: 'SSH', connection: 'dev-vm', env: 'dev', tools: [tool('ssh_run', 'APPROVAL'), tool('ssh_exec', 'OFF')] },
    { group: 'Logs', connection: 'app-logs', env: 'dev', tools: [tool('logs_search', 'READ_ONLY'), tool('logs_tail', 'OFF')] },
    { group: 'HTTP API', connection: 'billing-api', env: 'stage', tools: [tool('http_request', 'APPROVAL'), tool('http_get', 'OFF')] },
    { group: 'Core', connection: null, env: null, tools: [tool('list_connections', 'READ_ONLY'), tool('describe_connection', 'READ_ONLY')] },
  ];
}

export function token(name: string, over: Partial<TokenRow> = {}): TokenRow {
  return {
    id: `tok_${name}`,
    kind: 'named',
    name,
    prefix: 'gwt_K9xP',
    client: null,
    createdAt: at('08:00:00'),
    expiresAt: '2026-10-29T08:00:00.000Z',
    lastUsedAt: null,
    revokedAt: null,
    state: 'active',
    ...over,
  } as TokenRow;
}

/** The kit's Tokens tab: the bridge, codex-laptop, inspector-debug (expiring), ci-smoke (expired), old-claude (revoked). */
export function tokens(): TokenRow[] {
  return [
    token('old-claude', { state: 'revoked', revokedAt: at('09:00:00'), createdAt: '2026-08-02T09:20:00.000Z' }),
    token('inspector-debug', { createdAt: '2026-09-25T08:40:00.000Z', expiresAt: '2026-10-25T09:40:00.000Z' }),
    token('ci-smoke', { state: 'expired', createdAt: '2026-09-18T05:00:00.000Z' }),
    token('claude-desktop', { id: 'gr_cd', kind: 'grant', client: 'claude-desktop', prefix: null, expiresAt: null, lastUsedAt: at('14:27:40') }),
    token('codex-laptop', { id: 'gr_cx', kind: 'grant', client: 'codex', prefix: null, expiresAt: null, lastUsedAt: at('14:29:30') }),
  ];
}

function card(client: string, name: string, status: string, links: object[] = [], configDisplay = `~/.${client}/config`): ClientCard {
  return {
    client,
    name,
    status,
    statusText: status,
    configPath: `/home/u/.${client}/config`,
    configDisplay,
    detected: status !== 'not-installed',
    evidence: [],
    version: null,
    warnings: [],
    methods: [{ id: 'token', label: 'Token in the file', prodAllowed: true, reason: null }],
    links,
  } as unknown as ClientCard;
}

function link(client: string, endpoint: string, method: string, over: object = {}): object {
  return {
    id: `${client}-${endpoint}`,
    client,
    endpoint,
    env: 'stage',
    method,
    entryName: `gaterook-${endpoint}`,
    configPath: null,
    configDisplay: null,
    grantPrefix: null,
    grantLabel: null,
    tokenName: null,
    status: 'connected',
    statusText: 'Connected',
    lastCallAt: at('14:29:30'),
    revision: '2026-07-28',
    ...over,
  };
}

/** Claude Desktop (bridge, also incident-prod), Codex (headers helper), Cursor detected, Antigravity not installed. */
export function clientsView(): ClientsView {
  return {
    scannedAt: at('14:00:00'),
    clients: [
      card('claude-desktop', 'Claude Desktop', 'connected', [link('claude-desktop', 'dev', 'mcpb', { grantLabel: 'claude-desktop' }), link('claude-desktop', 'incident-prod', 'mcpb')]),
      card('codex', 'Codex', 'connected', [link('codex', 'dev', 'helper', { grantLabel: 'codex-laptop', configDisplay: '~/.codex/config.toml' })], '~/.codex/config.toml'),
      card('cursor', 'Cursor', 'detected', [], '~/.cursor/mcp.json'),
      card('antigravity', 'Antigravity', 'not-installed'),
    ],
  } as unknown as ClientsView;
}

export function connectionRow(name: string, over: Partial<ConnectionRow> = {}): ConnectionRow {
  return {
    name,
    type: 'postgres',
    typeName: 'PostgreSQL',
    env: 'dev',
    host: `${name}.internal:5432/app`,
    badges: [],
    secretSource: 'encrypted',
    health: { state: 'healthy', latencyMs: 12, message: null, checkedAt: at('14:29:00') },
    usedBy: ['dev'],
    verified: true,
    version: 1,
    ...over,
  };
}

/** The connections list: the eight on dev (with hosts and health) and the ones not on it yet. */
export function connectionRows(): ConnectionRow[] {
  return [
    ...wired().map((w) =>
      connectionRow(w.name, {
        type: w.type,
        typeName: w.typeName,
        env: w.env,
        host: w.name === 'billing-api' ? 'https://billing.stage.acme.dev' : `${w.name}.internal`,
        health: {
          state: w.health,
          latencyMs: w.health === 'degraded' ? 1800 : w.health === 'healthy' ? 12 : null,
          message: w.health === 'unreachable' ? 'connect timed out' : null,
          checkedAt: at('13:42:00'),
        },
      }),
    ),
    connectionRow('stage-db', { env: 'stage', secretSource: 'vault', usedBy: ['stage-readonly'] }),
    connectionRow('prod-replica', { env: 'prod', badges: ['read replica'], secretSource: 'short-lived', usedBy: ['incident-prod'] }),
  ];
}

export function preview(connection: string, over: Partial<ConnectionPreview> = {}): ConnectionPreview {
  return {
    connection,
    env: 'stage',
    tools: [
      { toolKey: 'sql_list_schemas', tool: 'sql_list_schemas', mode: 'READ_ONLY', locked: null, declaredBy: null },
      { toolKey: 'sql_describe_table', tool: 'sql_describe_table', mode: 'READ_ONLY', locked: null, declaredBy: null },
      { toolKey: 'sql_query', tool: 'sql_query', mode: 'READ_ONLY', locked: null, declaredBy: null },
      { toolKey: 'sql_execute', tool: 'sql_execute', mode: 'APPROVAL', locked: null, declaredBy: null },
    ],
    warnings: [],
    refusal: null,
    ...over,
  } as ConnectionPreview;
}

const limits = (perToken: number, perTool: number, kb: number) => ({ limits: { rateLimitPerToken: perToken, rateLimitPerTool: perTool, maxResponseKb: kb } });

export function policies(): Policies {
  const env = (e: Env, sections: object) => ({ env: e, inherits: null, sections, explicit: {}, inherited: {}, locks: [], runbooks: [], version: 1, verified: true, serving: true });
  return {
    dev: env('dev', limits(120, 30, 512)),
    stage: env('stage', limits(60, 20, 256)),
    prod: env('prod', limits(30, 10, 128)),
    usage: { busiestToken: { name: 'codex-laptop', calls: 14 }, busiestTool: null },
  } as unknown as Policies;
}
