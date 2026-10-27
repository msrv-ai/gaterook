import { ClientCard, ClientsView, ConnectPreview, ConnectRequest, ConnectResult, ManualSetup } from '../../core/api/clients.api';
import { EndpointSummary, TokenRow } from '../../core/api/endpoints.api';
import { DEMO_CLIENTS, DEMO_CLIENT_ENDPOINTS, DEMO_PREVIEW_CURSOR } from '../../dev/fixtures-clients';

/**
 * The Clients page's world for its tests: design/pages/clients.html's data (BLUEPRINT §9), shaped as the core's
 * answers were captured from a `GW_DEMO=1` core (`/api/clients`, `/api/endpoints`, `/api/clients/manual`,
 * `connect/preview`, `disconnect/preview`, `/api/endpoints/dev/tokens`). "Now" is 2026-10-22 14:30 CEST.
 */

export const NOW = Date.parse('2026-10-22T12:30:00Z');

/** Cursor detected (first, with Connect inline), Claude Desktop through the bridge, Codex, Antigravity not installed. */
export function kitClients(extra: Partial<ClientsView> = {}): ClientsView {
  return {
    scannedAt: '2026-10-22T14:29:00+02:00',
    clients: [DEMO_CLIENTS.claudeDesktop, DEMO_CLIENTS.codex, DEMO_CLIENTS.cursor, DEMO_CLIENTS.antigravity],
    ...extra,
  };
}

/** The same clients with none of them found (the empty state). */
export function noneFound(): ClientsView {
  const lost = (c: ClientCard): ClientCard => ({ ...c, status: 'not-installed', statusText: 'Not installed', detected: false, evidence: [], links: [] });
  return kitClients({ clients: kitClients().clients.map(lost) });
}

/** First run and setup (kit `?state=first-run | setup`): Claude Desktop, Codex and Cursor found, none connected yet. */
export function foundClients(): ClientsView {
  const found = (c: ClientCard): ClientCard => ({ ...c, status: 'detected', statusText: 'Detected, not connected', links: [] });
  const k = kitClients();
  return { ...k, clients: k.clients.map((c) => (c.status === 'not-installed' ? c : found(c))) };
}

/** Setup: one endpoint, dev, with dev-db on it. */
export function setupEndpoints(): EndpointSummary[] {
  const dev = kitEndpoints().find((e) => e.slug === 'dev')!;
  return [{ ...dev, published: false, tools: { read: 2, approval: 1, off: 0 }, connections: { total: 1, byEnv: { dev: 1 } } }];
}

export function kitEndpoints(): EndpointSummary[] {
  return DEMO_CLIENT_ENDPOINTS.map((e) => ({
    ...e,
    description: null,
    status: 'running',
    health: {},
    approvalsWaiting: 0,
    clients: [],
    tokens: { active: 1, expiringSoon: 0 },
    published: e.slug === 'stage-readonly',
    risk: { state: 'none', underlying: null, present: [], ingredients: { privateData: 'absent', untrustedContent: 'absent', externalChannel: 'absent' }, sources: [], published: false, dismissed: null },
    blockedReason: null,
    version: 1,
    connections: { total: e.connections.total, byEnv: { ...e.connections.byEnv } },
  }));
}

export function kitManual(): ManualSetup {
  const url = 'http://127.0.0.1:7465/mcp/dev';
  return {
    endpoint: 'dev',
    url,
    transport: 'streamable-http',
    others: [
      { slug: 'stage-readonly', url: 'http://127.0.0.1:7465/mcp/stage-readonly' },
      { slug: 'incident-prod', url: 'http://127.0.0.1:7465/mcp/incident-prod' },
    ],
    snippets: [
      {
        id: 'inspector-config',
        title: 'gw-inspector.json (chmod 600: it holds the token)',
        language: 'json',
        text: `{\n  "mcpServers": {\n    "gateway-dev": {\n      "type": "streamable-http",\n      "url": "${url}",\n      "headers": {\n        "Authorization": "Bearer ‹token›"\n      }\n    }\n  }\n}\n`,
      },
      { id: 'inspector-ui', title: 'Start MCP Inspector on dev', language: 'sh', text: 'npx -y @modelcontextprotocol/inspector@2.12.0 --config gw-inspector.json --server gateway-dev' },
      { id: 'inspector-cli', title: 'List the tools from a terminal', language: 'sh', text: 'npx -y @modelcontextprotocol/inspector@2.12.0 --cli --config gw-inspector.json --server gateway-dev --method tools/list' },
      { id: 'toml', title: 'An app that reads TOML', language: 'toml', text: `[mcp_servers.gateway-dev]\nurl = "${url}"\nhttp_headers = { Authorization = "Bearer ‹token›" }\n` },
    ],
  };
}

/** dev's tokens: inspector-debug set up by hand (3 days left), an expired and a revoked one, the bridge grants. */
export function kitTokens(): TokenRow[] {
  const row = (r: Partial<TokenRow> & Pick<TokenRow, 'id' | 'kind' | 'name' | 'state'>): TokenRow => ({
    prefix: null,
    client: null,
    createdAt: '2026-10-21T10:00:00+02:00',
    expiresAt: null,
    lastUsedAt: null,
    revokedAt: null,
    ...r,
  });
  return [
    row({ id: 'tok_old', kind: 'named', name: 'old-claude', prefix: 'gwt_fG1m', state: 'revoked', revokedAt: '2026-10-21T10:00:00+02:00' }),
    row({ id: 'tok_insp', kind: 'named', name: 'inspector-debug', prefix: 'gwt_K9xP', state: 'active', expiresAt: '2026-10-25T14:30:00+02:00' }),
    row({ id: 'tok_ci', kind: 'named', name: 'ci-smoke', prefix: 'gwt_jXQs', state: 'expired', expiresAt: '2026-10-21T14:30:00+02:00' }),
    row({ id: 'gr_cd', kind: 'grant', name: 'claude-desktop', client: 'claude-desktop', state: 'active' }),
    row({ id: 'gr_cx', kind: 'grant', name: 'codex-laptop', client: 'codex', state: 'active' }),
  ];
}

/** What the core prepares for a Connect of `client`, as the demo core answers it. */
export function previewFor(client: ClientCard['client'], request: ConnectRequest): ConnectPreview {
  const base = { ...DEMO_PREVIEW_CURSOR, client, endpoint: request.endpoint, env: DEMO_CLIENT_ENDPOINTS.find((e) => e.slug === request.endpoint)?.env ?? 'dev', method: request.method };
  switch (request.method) {
    case 'token':
      return {
        ...base,
        previewId: `pv_${client}_${request.endpoint}_token`,
        notes: [`Token ${request.token?.name} · expires in ${request.token?.ttlDays} days · written in plain text to ~/.cursor/mcp.json · Revoke it on the Tokens tab of the endpoint`, 'Turn the server on in Cursor: Settings › MCP'],
      };
    case 'oauth':
      return {
        ...base,
        previewId: `pv_${client}_${request.endpoint}_oauth`,
        notes: ['Cursor opens your browser and you allow it in this app. No token in the file.', `Turns on OAuth sign-in for endpoint ${request.endpoint}.`],
        link: 'cursor://anysphere.cursor-deeplink/mcp/install?name=gateway-dev&config=eyJ1cmwiOiJodHRwOi8vMTI3LjAuMC4xOjc0NjUvbWNwL2RldiJ9',
      };
    case 'mcpb':
      return {
        ...base,
        previewId: `pv_${client}_${request.endpoint}_mcpb`,
        files: [{ path: `/Users/maria/.gaterook/clients/claude-desktop/gaterook-${request.endpoint}.mcpb`, display: `gaterook-${request.endpoint}.mcpb`, realPath: '', exists: false, kind: 'mcpb', diff: null, backupPath: null, modeChange: null }],
        notes: [],
      };
    default:
      return {
        ...base,
        previewId: `pv_${client}_${request.endpoint}_${request.method}`,
        notes: ['No token is written: the bridge gets a 15-minute token from this app.', 'Turn the server on in Cursor: Settings › MCP'],
        warnings: request.endpoint === 'incident-prod' ? ['Endpoint incident-prod reaches prod. Connect it only to the app you use for incidents; its first token in each session waits until you allow it here.'] : [],
      };
  }
}

/** Disconnect Codex from dev: the core's reverse edit, whose removed lines are the entry as it is in the file. */
export function codexDisconnect(): ConnectPreview {
  const removed = [
    '',
    '# >>> GateRook · gateway-dev · managed entry: Disconnect in GateRook removes it',
    '[mcp_servers.gateway-dev]',
    'url = "http://127.0.0.1:7465/mcp/dev"',
    // The core's TomlBlockEditor writes the shim of ~/.gaterook/bin as one command string (ARCHITECTURE §7.2, KIT).
    'http_headers_helper = "~/.gaterook/bin/gw-bridge headers --endpoint dev --grant gr_Cx7q"',
    'default_tools_approval_mode = "writes"',
    'startup_timeout_sec = 20',
    'tool_timeout_sec = 60',
    '# <<< GateRook · gateway-dev',
  ];
  return {
    previewId: 'pv_codex_out',
    client: 'codex',
    endpoint: 'dev',
    env: 'dev',
    method: 'helper',
    repair: false,
    files: [
      {
        path: '/Users/maria/.codex/config.toml',
        display: '~/.codex/config.toml',
        realPath: '/Users/maria/.codex/config.toml',
        exists: true,
        kind: 'config',
        backupPath: '~/.codex/config.toml.bak-2026-10-22',
        modeChange: null,
        diff: {
          added: 0,
          removed: removed.length,
          hunks: [
            {
              header: '@@ -1,14 +1,5 @@',
              lines: [
                { type: 'context', oldNo: 5, newNo: 5, text: 'bearer_token_env_var = "GITHUB_PAT"' },
                ...removed.map((text, i) => ({ type: 'removed' as const, oldNo: 6 + i, newNo: null, text })),
              ],
            },
          ],
        },
      },
    ],
    notes: ['The access is revoked: the current token stops on its next call, and no new one is issued.'],
    warnings: [],
    link: null,
    expiresAt: '2026-10-22T14:40:00+02:00',
  };
}

/** Disconnect of one of Claude Desktop's extensions: no file of its own changes. */
export function bridgeDisconnect(endpoint: string): ConnectPreview {
  return {
    ...codexDisconnect(),
    previewId: `pv_cd_${endpoint}_out`,
    client: 'claude-desktop',
    endpoint,
    method: 'mcpb',
    files: [{ path: '', display: `gaterook-${endpoint}.mcpb`, realPath: '', exists: true, kind: 'mcpb', diff: null, backupPath: null, modeChange: null }],
    notes: ['Claude Desktop keeps the extension until you remove it there: Settings › Extensions. Its access is revoked already.'],
  };
}

/** What Connect answers: the new link. */
export function connected(client: ClientCard['client'], request: ConnectRequest): ConnectResult {
  return {
    link: {
      id: `cl_${client}_${request.endpoint}`,
      client,
      endpoint: request.endpoint,
      env: 'dev',
      method: request.method,
      entryName: `gateway-${request.endpoint}`,
      configPath: null,
      configDisplay: null,
      grantPrefix: null,
      grantLabel: request.grantLabel ?? null,
      tokenName: request.token?.name ?? null,
      status: 'connected',
      statusText: 'Connected',
      createdAt: '2026-10-22T14:30:00+02:00',
      usedAt: null,
      mcpbPath: request.method === 'mcpb' ? `/Users/maria/.gaterook/clients/claude-desktop/gaterook-${request.endpoint}.mcpb` : null,
      lastCallAt: null,
      revision: '2025-11-25',
    },
    mcpbPath: request.method === 'mcpb' ? `/Users/maria/.gaterook/clients/claude-desktop/gaterook-${request.endpoint}.mcpb` : null,
    notes: [],
  };
}
