import { ConnectionDetail, ConnectionRow } from '../../core/api/connections.api';
import { ConnectorCatalog, ConnectorTypeDetail, InstalledConnector } from '../../core/api/connector-types.api';
import { EndpointSummary, ToolGroup } from '../../core/api/endpoints.api';
import { CatalogEntry, CatalogIndex, McpServerReview, PluginCheck, PluginUpdatePreview } from '../../core/api/plugins.api';
import { SecretRow } from '../../core/api/secrets.api';
import { Template } from '../../core/api/templates.api';
import { JsonObject } from '../../core/api/types';

/**
 * Test answers in the shapes of the admin API (§6), with the kit page's world (design/pages/connectors.html): ten
 * built-in types, the RabbitMQ plugin, the github-mcp server, the catalog's five entries and the templates table.
 */

export const NOW = '2026-10-22T12:30:00Z';

function type(id: string, name: string, over: Partial<InstalledConnector> = {}): InstalledConnector {
  return {
    id,
    kind: 'built-in',
    name,
    icon: '',
    group: 'Databases',
    description: `${name} connector.`,
    version: '0.4.0',
    signed: null,
    tools: 4,
    connections: 0,
    enabled: true,
    verified: true,
    state: 'enabled',
    process: null,
    variants: [],
    ...over,
  };
}

export const RABBITMQ = type('rabbitmq', 'RabbitMQ', {
  kind: 'plugin',
  group: 'Messaging',
  description: 'Queues, exchanges and consumers over the management API.',
  version: '0.3.0',
  signed: false,
  tools: 5,
  process: { state: 'running', pid: 4242, rssMb: 54, detail: null },
});

export function installedTypes(): InstalledConnector[] {
  return [
    type('postgres', 'PostgreSQL', { connections: 2, tools: 4 }),
    type('mysql', 'MySQL', { connections: 1 }),
    type('mongodb', 'MongoDB'),
    type('redis', 'Redis'),
    type('ssh', 'SSH', { group: 'Servers', connections: 2, tools: 3 }),
    type('logs', 'Logs', { group: 'Servers' }),
    type('kubernetes', 'Kubernetes', { group: 'Servers' }),
    type('gcp', 'Google Cloud', { group: 'Clouds' }),
    type('aws', 'AWS', { group: 'Clouds' }),
    type('http', 'HTTP API', { group: 'Web' }),
    RABBITMQ,
  ];
}

export const typesCatalog = (installed = installedTypes()): ConnectorCatalog => ({ groups: [], installed });

export function row(name: string, typeId: string, over: Partial<ConnectionRow> = {}): ConnectionRow {
  return {
    name,
    type: typeId,
    typeName: typeId,
    env: 'dev',
    host: null,
    badges: [],
    secretSource: 'encrypted',
    health: { state: 'healthy', latencyMs: 3, message: null, checkedAt: NOW },
    usedBy: [],
    verified: true,
    version: 1,
    ...over,
  };
}

export function connectionRows(): ConnectionRow[] {
  return [
    row('dev-db', 'postgres', { usedBy: ['dev'] }),
    row('stage-db', 'postgres', { env: 'stage' }),
    row('dev-mysql', 'mysql'),
    row('dev-vm', 'ssh'),
    row('prod-vm', 'ssh', { env: 'prod' }),
    row('github-mcp', 'mcp-server', { usedBy: ['dev'] }),
  ];
}

export function serverDetail(name = 'github-mcp'): ConnectionDetail {
  return {
    name,
    type: 'mcp-server',
    typeName: 'MCP server',
    env: 'dev',
    config: {
      transport: 'stdio',
      command: ['node', `/Users/me/.gaterook/mcp/${name}/dist/index.js`],
      env: [{ name: 'GITHUB_PERSONAL_ACCESS_TOKEN', secretRef: 'secret:s1' }],
      catalogEntry: 'github@0.6.2',
    },
    version: 1,
    secrets: [{ field: '/env/0/secretRef', secretId: 's1', name: 'github-mcp/token' } as ConnectionDetail['secrets'][number]],
    health: { state: 'unknown', latencyMs: null, message: null, checkedAt: null },
    spark24h: [],
    usedBy: [],
    verified: true,
  };
}

export function catalogEntry(id: string, kind: CatalogEntry['kind'], name: string, over: Partial<CatalogEntry> = {}): CatalogEntry {
  return { id, kind, name, version: '1.0.0', signed: true, description: `${name}.`, tools: 4, installed: null, ...over };
}

export function catalogIndex(entries = catalogEntries()): CatalogIndex {
  return {
    checkedAt: '2026-10-22T08:00:00Z',
    source: 'https://catalog.gaterook.dev/v1/index.json',
    offline: false,
    signature: { keyId: 'catalog-2026', sequence: 41, expiresAt: '2026-11-22T00:00:00Z', ok: true },
    entries,
  };
}

export function catalogEntries(): CatalogEntry[] {
  return [
    catalogEntry('jira', 'plugin', 'Jira', { version: '1.2.0', description: 'Issues and comments.', tools: 6 }),
    catalogEntry('clickhouse', 'plugin', 'ClickHouse', { version: '0.9.1', signed: false, icon: 'database', tools: 6 }),
    catalogEntry('rabbitmq', 'plugin', 'RabbitMQ', { version: '0.4.0', installed: '0.3.0', tools: 6 }),
    catalogEntry('pg-slow-queries', 'template', 'PostgreSQL slow queries', {
      version: '1.1.0',
      description: 'Adds pg_slow_queries and pg_locks as read-only SQL tools.',
      tools: 2,
      connectionTypes: ['postgres'],
    }),
    catalogEntry('github', 'mcp-server', 'GitHub', { version: '0.6.2', installed: 'github-mcp', tools: 26 }),
  ];
}

export function template(id: string, name: string, over: Partial<Template> = {}): Template {
  return {
    id,
    kind: 'ssh-runbook',
    name,
    connection: 'dev-vm',
    version: 1,
    status: 'active',
    catalogRef: null,
    natures: { read: 1 },
    updatedAt: '2026-10-01T08:12:00Z',
    verified: true,
    ...over,
  };
}

export const ORDERS_DEFINITION: JsonObject & { readonly sql: string } = {
  name: 'orders_by_status',
  description: 'Recent orders with a given status.',
  sql: 'SELECT id, status FROM orders WHERE status = :status AND created_at >= :since LIMIT 100',
  params: [
    { name: 'status', type: 'enum', required: true, values: ['pending', 'paid'] },
    { name: 'since', type: 'timestamp', required: false, default: '-PT24H' },
  ],
};

export function templates(): Template[] {
  return [
    template('t1', 'orders_by_status', { kind: 'sql-query', connection: 'dev-db', updatedAt: '2026-10-20T14:41:00Z' }),
    template('t2', 'compose-ps'),
    template('t3', 'compose-restart', { natures: { mutation: 1 } }),
    template('t4', 'service-status', { connection: 'prod-vm' }),
  ];
}

export function endpoints(): EndpointSummary[] {
  return [
    { slug: 'dev', env: 'dev', status: 'running' },
    { slug: 'incident-prod', env: 'prod', status: 'running' },
  ] as EndpointSummary[];
}

function toolRow(toolKey: string, name: string, mode: 'READ_ONLY' | 'APPROVAL' | 'OFF') {
  return { toolKey, name, mode, storedMode: mode } as unknown as ToolGroup['tools'][number];
}

export function endpointTools(slug: string): ToolGroup[] {
  if (slug === 'dev') {
    return [
      { group: 'dev-vm', connection: 'dev-vm', env: 'dev', tools: [toolRow('tpl:t2:main', 'compose-ps', 'READ_ONLY'), toolRow('tpl:t3:main', 'compose-restart', 'APPROVAL')] },
      { group: 'dev-db', connection: 'dev-db', env: 'dev', tools: [toolRow('postgres:sql_query', 'sql_query', 'READ_ONLY')] },
    ];
  }
  return [{ group: 'prod-vm', connection: 'prod-vm', env: 'prod', tools: [toolRow('tpl:t4:main', 'service-status', 'READ_ONLY')] }];
}

export function secretRows(): SecretRow[] {
  return [
    {
      id: 's1',
      name: 'github-mcp/token',
      owner: 'github-mcp',
      ownerKind: 'mcp-server',
      field: '/env/0/secretRef',
      usedBy: ['github-mcp'],
      source: 'local',
      version: 1,
      remoteVersion: null,
      rotatedAt: null,
      state: 'ok',
      canRotate: true,
      rotateReason: null,
    },
    {
      id: 's2',
      name: 'dev-db/password',
      owner: 'dev-db',
      ownerKind: 'connection',
      field: '/password',
      usedBy: ['dev-db'],
      source: 'local',
      version: 1,
      remoteVersion: null,
      rotatedAt: null,
      state: 'ok',
      canRotate: true,
      rotateReason: null,
    },
  ];
}

export function typeDetail(id: string, templateKinds: string[] = []): ConnectorTypeDetail {
  return { type: installedTypes().find((t) => t.id === id) ?? type(id, id), tools: [], templateKinds };
}

/** The ClickHouse check of the kit (`dlg-review-clickhouse`): unsigned here, so the review is the danger one. */
export function clickhouseCheck(signed = false): PluginCheck {
  return {
    stagingId: 'stg-1',
    report: {
      signed,
      sha256: '9f2c4e1ab07d33c5e8a1f2b6c9d04e7a5b3c1d2e9f8a7b6c5d4e3f2a1b0c9d8e',
      size: 1.4 * 1024 * 1024,
      id: 'clickhouse',
      version: '0.9.1',
      name: 'ClickHouse',
      file: 'gw-clickhouse-0.9.1.jar',
      requires: { gateway: '>=0.4.0' },
      tools: ['ch_query', 'ch_tables', 'ch_describe'].map((name) => ({ name, declared: { readOnlyHint: true } })),
      access: { hosts: ['{connection.host}:8443'] },
      configPreview: [{ title: 'Host' }, { title: 'Port' }, { title: 'User' }, { title: 'Password' }],
      // No sandbox for the plugin process on this system (K4 review: `landlock`, `sandbox-exec` or null).
      sandbox: null,
    },
  };
}

export function rabbitUpdate(): PluginUpdatePreview {
  return {
    id: 'rabbitmq',
    name: 'RabbitMQ',
    from: '0.3.0',
    to: '0.4.0',
    signed: true,
    package: { file: 'gw-rabbitmq-0.4.0.jar', size: 2 * 1024 * 1024, sha256: 'ab'.repeat(32) },
    summary: 'Adds rabbitmq_shovels.',
    diff: {
      added: 2,
      removed: 1,
      hunks: [
        {
          header: '@@ tools @@',
          lines: [
            { t: 'context', v: '  rabbitmq_queues' },
            { t: 'del', v: '  rabbitmq_old' },
            { t: 'add', v: '  rabbitmq_shovels' },
            { t: 'add', v: '  rabbitmq_policies' },
          ],
        },
      ],
    },
    toolsAdded: ['rabbitmq_shovels', 'rabbitmq_policies'],
    toolsRemoved: ['rabbitmq_old'],
  };
}

export function sentryReview(over: Partial<McpServerReview> = {}): McpServerReview {
  return {
    argv: ['npx', '-y', '@sentry/mcp-server@0.12.0'],
    resolvedCommand: 'npx -y @sentry/mcp-server@0.12.0',
    node: '24.10.0',
    env: ['SENTRY_AUTH_TOKEN'],
    notPinned: false,
    catalogEntry: null,
    protocol: '2025-11-25',
    sandbox: null,
    ...over,
  };
}
