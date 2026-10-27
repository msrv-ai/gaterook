import { ConnectionRow } from '../../core/api/connections.api';
import { EndpointRisk, EndpointSummary, TokenRow } from '../../core/api/endpoints.api';
import { TunnelState } from '../../core/api/tunnel.api';

/*
 * Test data only (imported by the specs, never by the page): answers in the shapes of the admin API, named after the
 * kit's world (BLUEPRINT §9, design/pages/endpoints.html) so assertions read like the kit page.
 */

export const NOW = Date.parse('2026-10-22T14:30:00+02:00');
/** An instant of October 2026 in the kit's zone: `at(22, '14:29:00')`. */
export const at = (day: number, hhmmss: string): string => new Date(`2026-10-${String(day).padStart(2, '0')}T${hhmmss}+02:00`).toISOString();

const src = (ingredient: string, tool: string, connection: string, text: string) => ({ ingredient, tool, connection, text });

export function risk(state: string, channel: 'present' | 'limited' | 'absent', sources = [] as EndpointRisk['sources'], published = false): EndpointRisk {
  return {
    state,
    underlying: state,
    present: ['privateData', 'untrustedContent', ...(channel === 'absent' ? [] : ['externalChannel'])],
    ingredients: { privateData: 'present', untrustedContent: 'present', externalChannel: channel },
    sources,
    published,
    dismissed: null,
  };
}

export function endpoint(slug: string, over: Partial<EndpointSummary> = {}): EndpointSummary {
  return {
    slug,
    description: null,
    url: `http://127.0.0.1:7465/mcp/${slug}`,
    env: 'dev',
    access: 'standard',
    status: 'running',
    tools: { read: 0, approval: 0, off: 0 },
    connections: { total: 0, byEnv: { dev: 0, stage: 0, prod: 0 } },
    health: { healthy: 0, degraded: 0, unreachable: 0, unknown: 0 },
    approvalsWaiting: 0,
    clients: [],
    tokens: { active: 0, expiringSoon: 0 },
    published: false,
    risk: risk('none', 'absent'),
    blockedReason: null,
    version: 1,
    ...over,
  };
}

/** The kit's three endpoints, in its order. */
export function kitEndpoints(): EndpointSummary[] {
  return [
    endpoint('dev', {
      description: 'Local development. Agents read freely; anything that changes data waits for your approval.',
      env: 'stage',
      tools: { read: 14, approval: 3, off: 5 },
      connections: { total: 8, byEnv: { dev: 6, stage: 2, prod: 0 } },
      health: { healthy: 6, degraded: 1, unreachable: 1, unknown: 0 },
      approvalsWaiting: 3,
      clients: ['claude-desktop', 'codex'],
      tokens: { active: 1, expiringSoon: 1 },
      risk: risk('present', 'present', [
        src('privateData', 'sql_query', 'dev-db', 'sql_query reads dev-db'),
        src('externalChannel', 'ssh_run', 'dev-vm', 'ssh_run runs fixed runbooks on dev-vm'),
        src('externalChannel', 'http_request', 'billing-api', 'http_request can send data to billing-api'),
      ]),
    }),
    endpoint('stage-readonly', {
      description: 'Stage data for claude.ai, read only. Nothing on it can change data.',
      env: 'stage',
      access: 'read-only',
      tools: { read: 8, approval: 0, off: 2 },
      connections: { total: 3, byEnv: { dev: 0, stage: 3, prod: 0 } },
      health: { healthy: 2, degraded: 0, unreachable: 1, unknown: 0 },
      published: true,
      risk: risk('present', 'present', [src('externalChannel', 'http_get', 'billing-api', 'http_get can send data to billing-api')], true),
    }),
    endpoint('incident-prod', {
      description: 'Production incidents: read the replica and the logs, run approved runbooks on prod-vm.',
      env: 'prod',
      // Kit after step KIT (§8.6.6): ssh_run is Read only on prod with the runbook's own flag — "10 · 7 read only 3 off".
      tools: { read: 7, approval: 0, off: 3 },
      connections: { total: 3, byEnv: { dev: 0, stage: 0, prod: 3 } },
      health: { healthy: 3, degraded: 0, unreachable: 0, unknown: 0 },
      clients: ['claude-desktop'],
      risk: risk('limited', 'limited', [src('externalChannel', 'ssh_run', 'prod-vm', 'ssh_run runs fixed runbooks on prod-vm')]),
    }),
  ];
}

export function conn(name: string, env: ConnectionRow['env'], state: string, latencyMs: number | null, usedBy: string[]): ConnectionRow {
  return {
    name,
    type: 'postgres',
    typeName: 'PostgreSQL',
    env,
    host: null,
    badges: [],
    secretSource: 'encrypted',
    health: { state, latencyMs, message: null, checkedAt: at(22, '14:29:40') },
    usedBy,
    verified: true,
    version: 1,
  };
}

/** The kit's twelve connections with the health its Endpoints page shows (cache-redis slow, billing-api unreachable). */
export function kitConnections(): ConnectionRow[] {
  return [
    conn('app-logs', 'dev', 'healthy', 64, ['dev']),
    conn('aws-stage', 'stage', 'healthy', 96, ['dev', 'stage-readonly']),
    conn('billing-api', 'stage', 'unreachable', null, ['dev', 'stage-readonly']),
    conn('cache-redis', 'dev', 'degraded', 1800, ['dev']),
    conn('dev-cluster', 'dev', 'healthy', 38, ['dev']),
    conn('dev-db', 'dev', 'healthy', 12, ['dev']),
    conn('dev-vm', 'dev', 'healthy', 46, ['dev']),
    conn('orders-mongo', 'dev', 'healthy', 9, ['dev']),
    conn('prod-logs', 'prod', 'healthy', 210, ['incident-prod']),
    conn('prod-replica', 'prod', 'healthy', 31, ['incident-prod']),
    conn('prod-vm', 'prod', 'healthy', 52, ['incident-prod']),
    conn('stage-db', 'stage', 'healthy', 24, ['stage-readonly']),
  ];
}

export function token(id: string, kind: 'named' | 'grant', name: string, state: TokenRow['state'], expiresAt: string | null, client: string | null = null): TokenRow {
  return {
    id,
    kind,
    name,
    prefix: kind === 'named' ? 'gwt_K9xP' : null,
    client,
    createdAt: at(18, '14:30:00'),
    expiresAt,
    lastUsedAt: null,
    revokedAt: state === 'revoked' ? at(20, '10:00:00') : null,
    state,
  };
}

/** The tokens of each kit endpoint (BLUEPRINT §9 "Tokens of endpoint dev"). */
export function kitTokens(): Record<string, TokenRow[]> {
  return {
    dev: [
      token('tok_1', 'named', 'inspector-debug', 'active', at(25, '14:30:00')),
      token('tok_2', 'named', 'ci-smoke', 'expired', at(21, '09:00:00')),
      token('tok_3', 'named', 'old-claude', 'revoked', at(17, '09:00:00')),
      token('gr_1', 'grant', 'claude-desktop', 'active', null, 'claude-desktop'),
      token('gr_2', 'grant', 'codex-laptop', 'active', null, 'codex'),
    ],
    'stage-readonly': [],
    'incident-prod': [token('gr_3', 'grant', 'claude-desktop', 'active', null, 'claude-desktop')],
  };
}

export const TUNNEL_OFF: TunnelState = {
  provider: 'cloudflare-quick',
  state: 'off',
  host: null,
  publicUrl: null,
  endpoint: 'stage-readonly',
  since: at(22, '12:10:00'),
  callers: { anthropicOnly: true },
  cloudflared: null,
  error: null,
  history: [],
};
