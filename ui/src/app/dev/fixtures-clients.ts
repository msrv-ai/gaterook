import { ClientCard, ClientDiffLine, ClientLink, ClientMethod, ConnectPreview } from '../core/api/clients.api';
import { OAuthConsent, OAuthGrant, PublishPreview, TunnelState } from '../core/api/tunnel.api';
import { ClientEndpoint, ClientFailure } from '../kit/elements/client-card/client-view';
import { literalParts } from '../kit/elements/shared/literals';

/**
 * The clients of the demo world (BLUEPRINT §9) as `GET /api/clients`, `GET /api/tunnel` and `GET /api/oauth/grants`
 * return them, for the client-card gallery (design/elements/client-card.html). Dev only.
 */

export const DEMO_CLIENT_ENDPOINTS: ClientEndpoint[] = [
  { slug: 'dev', env: 'dev', access: 'standard', url: 'http://127.0.0.1:7465/mcp/dev', tools: { read: 14, approval: 3, off: 5 }, connections: { total: 8, byEnv: { dev: 7, stage: 1 } } },
  { slug: 'stage-readonly', env: 'stage', access: 'read-only', url: 'http://127.0.0.1:7465/mcp/stage-readonly', tools: { read: 9, approval: 0, off: 4 }, connections: { total: 3, byEnv: { stage: 3 } } },
  { slug: 'incident-prod', env: 'prod', access: 'read-only', url: 'http://127.0.0.1:7465/mcp/incident-prod', tools: { read: 6, approval: 1, off: 3 }, connections: { total: 3, byEnv: { prod: 3 } } },
];

const method = (id: ClientMethod['id'], label: string, prodAllowed = true, reason: string | null = null): ClientMethod => ({ id, label, prodAllowed, reason });
const MCPB = method('mcpb', 'Bridge extension (.mcpb)');
const CD_BRIDGE = method('config-bridge', 'Bridge entry in claude_desktop_config.json');
const HELPER = method('helper', 'gw-bridge headers (http_headers_helper)');
const TOKEN = method('token', 'Token in the file', false, 'Use the bridge or sign-in for prod: a token in a file outlives the incident.');
const OAUTH = method('oauth', 'Sign in through the browser', false, "Sign-in through the browser isn't offered for prod endpoints.");
const BRIDGE = method('config-bridge', 'Through the bridge');

const link = (l: Partial<ClientLink> & Pick<ClientLink, 'id' | 'client' | 'endpoint' | 'env' | 'method'>): ClientLink => ({
  entryName: null,
  configPath: null,
  configDisplay: null,
  grantPrefix: null,
  grantLabel: null,
  tokenName: null,
  status: 'connected',
  statusText: 'Connected',
  createdAt: '2026-10-20T09:12:00+02:00',
  usedAt: null,
  mcpbPath: null,
  lastCallAt: null,
  revision: '2025-11-25',
  ...l,
});

const CLAUDE_DESKTOP: ClientCard = {
  client: 'claude-desktop',
  name: 'Claude Desktop',
  status: 'connected',
  statusText: 'Connected',
  configPath: '/Users/maria/Library/Application Support/Claude/claude_desktop_config.json',
  configDisplay: '~/Library/Application Support/Claude/claude_desktop_config.json',
  detected: true,
  evidence: ['/Applications/Claude.app'],
  version: '1.6.4',
  warnings: [],
  methods: [MCPB, CD_BRIDGE],
  links: [
    link({ id: 'cl_cd_dev', client: 'claude-desktop', endpoint: 'dev', env: 'dev', method: 'mcpb', grantPrefix: 'gr_Cd4p', grantLabel: 'claude-desktop', mcpbPath: '~/.gaterook/clients/claude-desktop/gaterook-dev.mcpb', lastCallAt: '2026-10-22T14:16:00+02:00' }),
    link({ id: 'cl_cd_prod', client: 'claude-desktop', endpoint: 'incident-prod', env: 'prod', method: 'mcpb', grantPrefix: 'gr_Cd9x', grantLabel: 'claude-desktop', mcpbPath: '~/.gaterook/clients/claude-desktop/gaterook-incident-prod.mcpb', lastCallAt: '2026-10-22T11:58:00+02:00' }),
  ],
};

const CODEX_LINK = link({
  id: 'cl_cx_dev',
  client: 'codex',
  endpoint: 'dev',
  env: 'dev',
  method: 'helper',
  entryName: 'gateway-dev',
  configPath: '/Users/maria/.codex/config.toml',
  configDisplay: '~/.codex/config.toml',
  grantPrefix: 'gr_Cx7q',
  grantLabel: 'codex-laptop',
  lastCallAt: '2026-10-22T14:28:00+02:00',
});

const CODEX: ClientCard = {
  client: 'codex',
  name: 'Codex',
  status: 'connected',
  statusText: 'Connected',
  configPath: '/Users/maria/.codex/config.toml',
  configDisplay: '~/.codex/config.toml',
  detected: true,
  evidence: ['~/.codex'],
  version: '0.162.1',
  warnings: [],
  methods: [HELPER],
  links: [CODEX_LINK],
};

const CURSOR: ClientCard = {
  client: 'cursor',
  name: 'Cursor',
  status: 'detected',
  statusText: 'Detected, not connected',
  configPath: '/Users/maria/.cursor/mcp.json',
  configDisplay: '~/.cursor/mcp.json',
  detected: true,
  evidence: ['/Applications/Cursor.app', '~/.cursor'],
  version: '2.1.3',
  warnings: [],
  methods: [TOKEN, OAUTH, BRIDGE],
  links: [],
};

const ANTIGRAVITY: ClientCard = {
  client: 'antigravity',
  name: 'Antigravity',
  status: 'not-installed',
  statusText: 'Not installed',
  configPath: '/Users/maria/.gemini/config/mcp_config.json',
  configDisplay: '~/.gemini/config/mcp_config.json',
  detected: false,
  evidence: [],
  version: null,
  warnings: [],
  methods: [TOKEN, OAUTH, BRIDGE],
  links: [],
};

export const DEMO_CLIENTS = {
  claudeDesktop: CLAUDE_DESKTOP,
  codex: CODEX,
  cursor: CURSOR,
  antigravity: ANTIGRAVITY,
  /** Right after Connect: Codex reads config.toml only when it starts. */
  codexRestart: {
    ...CODEX,
    status: 'needs-restart',
    statusText: 'Restart Codex: Settings › MCP servers › Restart',
    links: [{ ...CODEX_LINK, status: 'needs-restart', statusText: 'Restart Codex: Settings › MCP servers › Restart', lastCallAt: null }],
  } satisfies ClientCard,
};

/** Connect of Cursor failed: the file belongs to root (409 "Connect failed"). */
export const DEMO_CLIENT_FAILURE: ClientFailure = {
  title: literalParts("Can't write ~/.cursor/mcp.json", ['~/.cursor/mcp.json']),
  body: literalParts('Permission denied: the file belongs to root. Give it back to your user, then try again. Nothing was written.', ['root']),
  fix: { title: 'Fix the owner', label: 'command', language: 'shell', text: 'sudo chown "$USER" ~/.cursor/mcp.json' },
};

const lines = (spec: readonly (readonly [ClientDiffLine['type'], string])[], oldStart: number, newStart: number): ClientDiffLine[] => {
  let o = oldStart;
  let n = newStart;
  return spec.map(([type, text]) =>
    type === 'added' ? { type, oldNo: null, newNo: n++, text } : type === 'removed' ? { type, oldNo: o++, newNo: null, text } : { type, oldNo: o++, newNo: n++, text },
  );
};

/** Connect Cursor to dev with a token in the file: one server added to ~/.cursor/mcp.json. */
export const DEMO_PREVIEW_CURSOR: ConnectPreview = {
  previewId: 'pv_cursor_dev',
  client: 'cursor',
  endpoint: 'dev',
  env: 'dev',
  method: 'token',
  repair: false,
  files: [
    {
      path: '/Users/maria/.cursor/mcp.json',
      display: '~/.cursor/mcp.json',
      realPath: '/Users/maria/.cursor/mcp.json',
      exists: true,
      kind: 'config',
      backupPath: '~/.cursor/mcp.json.bak-2026-10-22',
      modeChange: null,
      diff: {
        added: 7,
        removed: 1,
        hunks: [
          {
            header: '@@ -1,8 +1,14 @@',
            lines: lines(
              [
                ['context', '{'],
                ['context', '  "mcpServers": {'],
                ['context', '    "github": {'],
                ['context', '      "command": "npx",'],
                ['context', '      "args": ["-y", "@modelcontextprotocol/server-github"]'],
                ['removed', '    }'],
                ['added', '    },'],
                ['added', '    "gateway-dev": {'],
                ['added', '      "url": "http://127.0.0.1:7465/mcp/dev",'],
                ['added', '      "headers": {'],
                ['added', '        "Authorization": "Bearer ‹token cursor-laptop›"'],
                ['added', '      }'],
                ['added', '    }'],
                ['context', '  }'],
                ['context', '}'],
              ],
              1,
              1,
            ),
          },
        ],
      },
    },
  ],
  notes: [],
  warnings: [],
  link: null,
  expiresAt: '2026-10-22T14:40:00+02:00',
};

/** Connect Codex to dev: the helper entry, nothing secret written. */
export const DEMO_PREVIEW_CODEX: ConnectPreview = {
  previewId: 'pv_codex_dev',
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
        added: 4,
        removed: 0,
        hunks: [
          {
            header: '@@ -4,3 +4,7 @@',
            lines: lines(
              [
                ['context', '[mcp_servers.github]'],
                ['context', 'command = "npx"'],
                ['context', 'args = ["-y", "@modelcontextprotocol/server-github"]'],
                ['added', ''],
                ['added', '[mcp_servers.gateway-dev]'],
                ['added', 'url = "http://127.0.0.1:7465/mcp/dev"'],
                ['added', 'http_headers_helper = "~/.gaterook/bin/gw-bridge headers --endpoint dev --grant gr_Cx7q"'],
              ],
              4,
              4,
            ),
          },
        ],
      },
    },
  ],
  notes: [],
  warnings: [],
  link: null,
  expiresAt: '2026-10-22T14:40:00+02:00',
};

/** Disconnect Codex from dev: the entry goes, the backup stays. */
export const DEMO_DISCONNECT_CODEX: ConnectPreview = {
  ...DEMO_PREVIEW_CODEX,
  previewId: 'pv_codex_dev_out',
  files: [{ ...DEMO_PREVIEW_CODEX.files[0], diff: null }],
};

/** The tunnel, off since 12:10 after a public morning (BLUEPRINT §9). */
export const DEMO_TUNNEL_OFF: TunnelState = {
  provider: 'cloudflare-quick',
  state: 'off',
  host: null,
  publicUrl: null,
  endpoint: 'stage-readonly',
  since: '2026-10-22T12:10:00+02:00',
  callers: { anthropicOnly: true },
  cloudflared: { version: '2025.10.1', sha256Ok: true },
  error: null,
  history: [{ from: '2026-10-22T08:40:00+02:00', to: '2026-10-22T12:10:00+02:00', endpoint: 'stage-readonly', calls: 19 }],
};

export const DEMO_TUNNEL_ON: TunnelState = {
  ...DEMO_TUNNEL_OFF,
  state: 'on',
  host: 'gw-acme-7f3k.trycloudflare.com',
  publicUrl: 'https://gw-acme-7f3k.trycloudflare.com/mcp/stage-readonly',
  since: '2026-10-22T14:30:00+02:00',
};

export const DEMO_PUBLISH: PublishPreview = {
  endpoint: 'stage-readonly',
  publicPaths: ['/mcp/stage-readonly', '/oauth/*', '/.well-known/oauth-*'],
  local: ['The app and its admin API (127.0.0.1:7465)', '/mcp/dev', '/mcp/incident-prod'],
  connections: [
    { name: 'stage-db', env: 'stage' },
    { name: 'aws-stage', env: 'stage' },
    { name: 'billing-api', env: 'stage' },
  ],
  risk: null,
  refusal: null,
  // As TunnelService.warnings words them (quick tunnel, with DCR on in this world).
  warnings: [
    'Dynamic client registration is on: any client that reaches the URL can register (it still needs your consent here).',
    'A quick tunnel gets a new address each time it starts; claude.ai must be pointed at the new URL. Use a named tunnel for a stable hostname.',
  ],
};

export const DEMO_GRANTS: OAuthGrant[] = [
  {
    id: 'og_7Hc2',
    client: 'claude.ai',
    endpoint: 'stage-readonly',
    ingress: 'TUNNEL',
    createdAt: '2026-10-22T08:52:00+02:00',
    expiresAt: '2026-10-29T08:52:00+01:00',
    lastUsedAt: '2026-10-22T12:08:14+02:00',
    state: 'active',
  },
];

export const DEMO_CONSENT: OAuthConsent = {
  id: 'oc_4F72KQ',
  code: '4F7-2KQ',
  clientHost: 'claude.ai',
  redirectHost: 'claude.ai',
  endpoint: 'stage-readonly',
  env: 'stage',
  tools: { read: 9, approval: 0, off: 4 },
  ip: '160.79.104.10',
  expiresAt: '2026-10-22T14:35:00+02:00',
};
