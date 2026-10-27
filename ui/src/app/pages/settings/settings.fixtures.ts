import { ClientsView } from '../../core/api/clients.api';
import { CoreInfo, Health } from '../../core/api/core.api';
import { EndpointSummary, TokenRow } from '../../core/api/endpoints.api';
import { NotificationChannel, Notifications } from '../../core/api/notifications.api';
import { MasterKeyStatus } from '../../core/api/secrets.api';
import { AuditExportDestination, BackupStatus, SettingsSection } from '../../core/api/settings.api';
import { OAuthGrant, TunnelState } from '../../core/api/tunnel.api';
import { JsonObject } from '../../core/api/types';

/**
 * The kit's Settings world (design/pages/settings.html, BLUEPRINT §9) as the core's answers, for the page's tests:
 * port 7465, endpoints dev / stage-readonly / incident-prod, a token of dev that runs out in 3 days, approval rules that
 * announce in the desktop and Slack, retention 1 year, a daily backup at 03:00.
 */

export const NOW = Date.parse('2026-10-22T12:30:00Z');
const DAY = 24 * 60 * 60 * 1000;

const section = (name: string, values: JsonObject, version = 3): SettingsSection => ({ name, values, version, updatedAt: '2026-10-20T09:00:00Z' });

export function kitSections(over: Partial<Record<string, JsonObject>> = {}): { [name: string]: SettingsSection } {
  const values: Record<string, JsonObject> = {
    general: { openAtLogin: true, startHidden: true, autoUpdate: true, singleKeyShortcuts: true },
    appearance: { theme: 'system', contrast: 'system' },
    authorization: { tokenTtlDays: 7, clientRegistration: 'cimd' },
    'approval-rules': {
      decisionsFrom: 'app-and-linked',
      requireCommentOnProd: true,
      perEnvironment: {
        dev: { timeoutMinutes: 5, comment: 'optional', announceIn: ['desktop', 'slack'] },
        stage: { timeoutMinutes: 10, comment: 'optional', announceIn: ['desktop', 'slack'] },
        prod: { timeoutMinutes: 10, comment: 'required', announceIn: ['desktop', 'slack'] },
      },
    },
    'local-api': { allowedOrigins: ['http://localhost:6274'], address: '127.0.0.1', port: 7465, publicPort: 7466 },
    audit: { retentionYears: 1 },
    backup: { daily: true, at: '03:00', keep: 7 },
    ...over,
  };
  return Object.fromEntries(Object.entries(values).map(([k, v]) => [k, section(k, v as JsonObject)]));
}

export const kitInfo = (over: Partial<CoreInfo> = {}): CoreInfo => ({
  version: '0.4.0',
  runtime: 'native',
  vm: '',
  port: 7465,
  logFile: '/Users/you/.mcp-gateway/logs/core.log',
  pluginRuntime: { bundledJre: true, home: '/Applications/MCP Gateway.app/jre', version: '25.0.1+8', present: true },
  ...over,
});

export const kitHealth = (): Health =>
  ({
    status: 'ok',
    locked: null,
    version: '0.4.0',
    uptimeMs: 1000,
    ports: { local: 7465, public: 7466 },
    runtime: 'native',
    db: { path: '/Users/you/.mcp-gateway/gateway.db', sqlite: '3.53', journal: 'wal', schema: '3', sizeBytes: 1 },
    masterKey: { protection: 'keychain', problems: [], warnings: [] },
    tunnel: { state: 'off', host: null },
    warnings: [],
  }) as Health;

export const kitBackup = (over: Partial<BackupStatus> = {}): BackupStatus => ({
  daily: true,
  at: '03:00',
  lastBackupAt: '2026-10-22T01:00:00Z',
  lastFile: 'gateway-2026-10-22.db',
  folder: '/Users/you/.mcp-gateway/backups',
  keep: 7,
  lastError: null,
  lastAttemptAt: '2026-10-22T01:00:00Z',
  nextRunAt: '2026-10-23T01:00:00Z',
  ...over,
});

export const kitMasterKey = (over: Partial<MasterKeyStatus> = {}): MasterKeyStatus => ({
  path: '/Users/you/.mcp-gateway/master.keyset',
  mode: '0400',
  owner: 'you',
  keys: [{ id: 2146530891, status: 'enabled', primary: true }],
  osProtection: 'keychain',
  protects: 8,
  lastBackupAt: '2026-10-10T16:20:00Z',
  problems: ['backup-needed'],
  warnings: [],
  locked: null,
  rotation: { state: 'idle', done: 0, total: 0, targetKeyId: null, error: null },
  ...over,
});

const endpoint = (slug: string, over: Partial<EndpointSummary> = {}): EndpointSummary =>
  ({
    slug,
    description: null,
    url: `http://127.0.0.1:7465/mcp/${slug}`,
    env: 'dev',
    access: 'standard',
    status: 'running',
    tools: { read: 1, approval: 1, off: 0 },
    connections: { total: 1, byEnv: {} },
    health: {},
    approvalsWaiting: 0,
    clients: [],
    tokens: { active: 1, expiringSoon: 0 },
    published: false,
    risk: { state: 'none', underlying: null, present: [], ingredients: {}, sources: [], published: false, dismissed: null },
    blockedReason: null,
    version: 1,
    ...over,
  }) as EndpointSummary;

export const kitEndpoints = (): EndpointSummary[] => [
  endpoint('dev', { tokens: { active: 2, expiringSoon: 1 } }),
  endpoint('stage-readonly', { env: 'stage', access: 'read-only', published: true, risk: { state: 'present', underlying: null, present: ['privateData', 'untrustedContent', 'externalChannel'] } as never }),
  endpoint('incident-prod', { env: 'prod' }),
];

export const kitTokens = (): TokenRow[] => [
  { id: 't1', kind: 'NAMED' as never, name: 'inspector-debug', prefix: 'gwt_K9xP', client: null, createdAt: '2026-10-01T00:00:00Z', expiresAt: new Date(NOW + 3 * DAY).toISOString(), lastUsedAt: null, revokedAt: null, state: 'active' },
  { id: 't2', kind: 'NAMED' as never, name: 'ci', prefix: 'gwt_A1b2', client: null, createdAt: '2026-10-01T00:00:00Z', expiresAt: new Date(NOW + 60 * DAY).toISOString(), lastUsedAt: null, revokedAt: null, state: 'active' },
];

export const kitClients = (): ClientsView =>
  ({
    scannedAt: null,
    clients: [
      { client: 'claude-desktop', name: 'Claude Desktop', links: [{ method: 'mcpb' }] },
      { client: 'codex', name: 'Codex', links: [{ method: 'headers-helper' }] },
    ],
  }) as unknown as ClientsView;

export const channel = (kind: 'desktop' | 'slack' | 'telegram', over: Partial<NotificationChannel> = {}): NotificationChannel => ({
  kind,
  enabled: true,
  config: {},
  state: 'connected',
  stateDetail: null,
  lastDeliveryAt: null,
  version: 2,
  ...over,
});

/**
 * K2's world as the kit draws it, in the core's shapes (`GET /api/notifications` of the demo world): desktop on,
 * Slack in #deploy-approvals of workspace acme, Telegram not set up.
 */
export const kitNotifications = (): Notifications => ({
  channels: [
    channel('desktop', { config: { soundForProd: true, maxBurst: 3 }, state: 'ok' }),
    channel('slack', {
      config: {
        deliverTo: 'channel',
        channelId: 'C1',
        channelName: '#deploy-approvals',
        workspace: { team: 'acme', teamId: 'T0ACME00001', bot: 'mcp-gateway', botUserId: 'U0MCPGW0001', appId: 'A0MCPGW0001' },
      },
      stateDetail: 'Connected · workspace acme',
      lastDeliveryAt: '2026-10-22T12:29:31Z',
    }),
    channel('telegram', { enabled: false, state: 'off', config: { protectContent: true, confirmApprove: true }, version: 0 }),
  ],
  links: [],
});

/** K3's world: the tunnel is off since 12:10, stage-readonly was public from 08:40 to 12:10 with 19 calls. */
export const kitTunnel = (over: Partial<TunnelState> = {}): TunnelState => ({
  provider: 'cloudflare-quick',
  state: 'off',
  host: null,
  publicUrl: null,
  endpoint: 'stage-readonly',
  since: '2026-10-22T10:10:00Z',
  callers: { anthropicOnly: true },
  cloudflared: { version: '2026.9.1', sha256Ok: true },
  error: null,
  history: [{ from: '2026-10-22T06:40:00Z', to: '2026-10-22T10:10:00Z', endpoint: 'stage-readonly', calls: 19 }],
  ...over,
});

export const kitGrant = (over: Partial<OAuthGrant> = {}): OAuthGrant => ({
  id: 'g1',
  client: 'claude.ai',
  endpoint: 'stage-readonly',
  ingress: 'TUNNEL',
  createdAt: '2026-10-22T06:42:00Z',
  expiresAt: '2026-11-21T06:42:00Z',
  lastUsedAt: '2026-10-22T10:08:14Z',
  state: 'active',
  ...over,
});

/** K5's world: SIEM streaming, S3 daily, BigQuery failing since 12:00. */
export const kitExports = (): AuditExportDestination[] => [
  { id: 'siem', kind: 'siem', name: 'SIEM', enabled: true, config: { host: 'siem.acme.internal', port: 6514 }, schedule: 'stream', state: 'ok', failingSince: null, lastAttemptAt: '2026-10-22T12:29:30Z', lastError: null },
  { id: 's3', kind: 's3', name: 'S3', enabled: true, config: { bucket: 'acme-audit', prefix: 'mcp-gateway/' }, schedule: 'daily@02:00', state: 'ok', failingSince: null, lastAttemptAt: '2026-10-22T00:00:00Z', lastError: null },
  {
    id: 'bq',
    kind: 'bigquery',
    name: 'BigQuery',
    enabled: true,
    config: { table: 'acme-prod.audit.mcp_calls' },
    schedule: 'hourly',
    state: 'failing',
    failingSince: '2026-10-22T10:00:00Z',
    lastAttemptAt: '2026-10-22T12:00:04Z',
    lastError: '403 Forbidden: Access Denied: Table acme-prod:audit.mcp_calls',
  },
];
