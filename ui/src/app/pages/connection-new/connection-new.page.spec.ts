import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { CONNECTOR_FORMS } from '../../../testing/connector-schemas';
import { ApiError } from '../../core/api/api-error';
import { ApprovalItem, ApprovalsApi } from '../../core/api/approvals.api';
import { ConnectionDetail, ConnectionsApi, CreateConnectionRequest, SuggestRequest, Suggested, TestConfirmed, TestDraftRequest, TestOutcome, UpdateConnectionRequest } from '../../core/api/connections.api';
import { ConnectionFormSpec, ConnectorCatalog, ConnectorTool, ConnectorTypeDetail, ConnectorTypesApi } from '../../core/api/connector-types.api';
import { ConnectionPreview, EndpointSummary, EndpointsApi, ToolGroup, ToolRow } from '../../core/api/endpoints.api';
import { Insights, InsightsApi, InsightsQuery } from '../../core/api/insights.api';
import { Policies, PoliciesApi } from '../../core/api/policies.api';
import { ConnectorModules, ModulesApi } from '../../core/api/modules.api';
import { McpProfile, McpRegistryServer, McpServerReview, McpServerSource, McpServerStarted, McpServersApi, PluginCheck, PluginsApi } from '../../core/api/plugins.api';
import { MasterKeyApi, MasterKeyStatus, SecretRow, SecretsApi } from '../../core/api/secrets.api';
import { JsonObject, ToolMode } from '../../core/api/types';
import { EVENT_STREAM_FETCH, EventStream } from '../../core/events/event-stream';
import { TimeService } from '../../core/time/time.service';
import { Announcer } from '../../kit/announcer';
import { AriaDisabledGuard } from '../../kit/aria-disabled';
import { provideKitIcons } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { clickhouseCheck } from '../connectors/connectors.fixtures';
import { busyMonth } from './connection-calls.fixtures';
import { ConnectionNewPage } from './connection-new.page';

/**
 * Add connection against the kit's page script: the four steps with their focus
 * and announcements, what the test shows, the endpoints a connection may join, Save, the duplicate and the edit view
 * with its dialogs. The admin API is faked at the service level with canonical connector schemas.
 */

const NOW = Date.parse('2026-10-22T14:30:00+02:00');
const now = signal(NOW);
const fakeTime: Partial<TimeService> = {
  now,
  minute: now,
  relative: (at) => (at ? '1 min ago' : ''),
  absolute: (at) => at ?? '',
  iso: (at) => at ?? '',
  date: () => 'Today',
  clock: () => '14:02:11',
};

const tool = (over: Partial<ConnectorTool>): ConnectorTool => ({
  name: 'sql_query',
  title: 'Query',
  description: 'Run one SELECT in a read-only transaction.',
  nature: 'read',
  defaultMode: 'read-only',
  prodDefaultMode: 'read-only',
  annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
  ...over,
});

const TOOLS = [
  tool({ name: 'sql_list_schemas', description: 'List the schemas the connection user can see.' }),
  tool({ name: 'sql_query' }),
  tool({
    name: 'sql_execute',
    description: 'INSERT, UPDATE or DELETE. You approve the exact statement.',
    nature: 'mutation',
    defaultMode: 'approval',
    prodDefaultMode: 'approval',
    annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: false },
  }),
];

const CATALOG: ConnectorCatalog = {
  groups: [
    {
      name: 'Databases',
      cards: [
        { type: 'postgres', variant: null, name: 'PostgreSQL', icon: 'database', description: 'Read queries, schema, EXPLAIN. Writes wait for approval.', kind: 'built-in', version: null, signed: null, tools: 3, toolsFrom: null, connections: 3, available: true, reason: null, presetConfig: null },
        { type: 'clickhouse', variant: null, name: 'ClickHouse', icon: 'database', description: 'Analytics.', kind: 'plugin', version: '1.0.0', signed: true, tools: 6, toolsFrom: null, connections: 0, available: false, reason: 'Not installed yet. Continue reviews it and installs it from the catalog.', presetConfig: null, catalogRef: 'clickhouse@1.0.0' },
      ],
    },
  ],
  installed: [],
};

const SPEC: ConnectionFormSpec = {
  type: 'postgres',
  name: 'PostgreSQL',
  env: 'dev',
  schema: CONNECTOR_FORMS['postgres'],
  common: {},
  secretFields: [{ pointer: '/credentials/password', kind: 'value', title: 'Password' }],
  secretSources: [{ id: 'local', label: 'Encrypted here', available: true, reason: null }],
  envDefaults: { prod: [], stage: [] },
  variants: [],
};

const TYPE: ConnectorTypeDetail = { type: { kind: 'built-in' } as ConnectorTypeDetail['type'], tools: TOOLS, templateKinds: [] };

const endpoint = (over: Partial<EndpointSummary>): EndpointSummary => ({
  slug: 'dev',
  description: 'Local development. Reads run, writes wait for approval.',
  url: 'http://127.0.0.1:7465/mcp/dev',
  env: 'dev',
  access: 'standard',
  status: 'running',
  tools: { read: 3, approval: 1, off: 0 },
  connections: { total: 4, byEnv: { dev: 4 } },
  health: {},
  approvalsWaiting: 0,
  clients: ['claude-desktop', 'codex'],
  tokens: { active: 2, expiringSoon: 0 },
  published: false,
  risk: null as never,
  blockedReason: null,
  version: 1,
  ...over,
});

const ENDPOINTS = [
  endpoint({}),
  endpoint({ slug: 'stage-readonly', description: 'Read only.', env: 'stage', access: 'read-only', clients: [] }),
  endpoint({ slug: 'incident-prod', description: 'Prod logs.', env: 'prod', clients: ['claude-desktop'] }),
];

const outcome = (over: Partial<TestOutcome>): TestOutcome => ({
  ok: false,
  latencyMs: 0,
  serverVersion: null,
  identity: null,
  permissions: null,
  hints: [],
  checks: [],
  error: null,
  needsConfirmation: null,
  confirmationId: null,
  suggestedPolicyChange: null,
  details: null,
  ...over,
});

const OK = outcome({
  ok: true,
  latencyMs: 12,
  serverVersion: 'PostgreSQL 16.4',
  identity: 'app_rw',
  permissions: { level: 'write', read: 'SELECT on 42 tables', write: 'INSERT, UPDATE, DELETE on public.*', admin: [], details: { schemaChanges: 'None: no CREATE or ownership' } },
  hints: [
    {
      severity: 'warning',
      code: 'sql.can-write',
      field: '/credentials/user',
      text: 'This user can write. Fine for dev: every write still waits for approval. For stage and prod use a read-only user or a replica.',
      snippet: { language: 'sql', text: 'CREATE ROLE gw_reader LOGIN;\nGRANT SELECT ON ALL TABLES IN SCHEMA public TO gw_reader;' },
    },
  ],
});

const HOST_KEY = outcome({
  needsConfirmation: { kind: 'host-key', text: 'GateRook has not seen this server before.', data: { host: 'db.dev.internal', port: 22, keyType: 'ssh-ed25519', fingerprint: 'SHA256:9mX0bZ3J' } },
  confirmationId: 'cf-1',
});

const DETAIL: ConnectionDetail = {
  name: 'dev-db',
  type: 'postgres',
  typeName: 'PostgreSQL',
  env: 'dev',
  config: { connection: { host: 'db.dev.internal', port: 5432, database: 'app', sslMode: 'verify-full' }, credentials: { user: 'app_rw' } },
  version: 3,
  secrets: [{ field: '/credentials/password', secretId: 'sec-1', name: 'dev-db/password', state: 'stored', version: 3, source: 'local' }],
  health: { state: 'healthy', latencyMs: 12, message: null, checkedAt: '2026-10-22T14:29:43+02:00' },
  spark24h: [10, 12, 15],
  usedBy: [
    { tool: 'sql_execute', endpoint: 'dev', mode: 'APPROVAL' },
    { tool: 'sql_query', endpoint: 'dev', mode: 'READ_ONLY' },
    { tool: 'sql_list_schemas', endpoint: 'dev', mode: 'READ_ONLY' },
  ],
  verified: true,
  healthIntervalSeconds: 60,
} as ConnectionDetail;

const POLICIES = {
  dev: { sections: { sql: { readOnlyTransactions: true, rowLimit: 1000, statementTimeoutMs: 5000, denyDdl: false }, approvals: { timeoutMinutes: 5, comment: 'optional' } } },
  stage: { sections: {} },
  prod: { sections: { sql: { readOnlyTransactions: true, rowLimit: 100, statementTimeoutMs: 3000, denyDdl: true }, approvals: { timeoutMinutes: 10, comment: 'required' } } },
} as unknown as Policies;

const POLICY_SCHEMA: JsonObject = {
  sections: {
    sql: {
      properties: {
        readOnlyTransactions: { type: 'boolean', const: true, readOnly: true, title: 'Run queries in a read-only transaction' },
        statementTimeoutMs: { type: 'integer', title: 'Statement timeout', 'x-unit': 'ms' },
        rowLimit: { type: 'integer', title: 'Row limit', 'x-unit': 'rows' },
        denyDdl: { type: 'boolean', title: 'Block DDL' },
      },
    },
  },
};

const PENDING = {
  id: 'ap_sql000000001',
  tool: 'sql_execute',
  connection: 'dev-db',
  env: 'dev',
  endpoint: 'dev',
  client: 'codex',
  tokenLabel: 'codex-laptop',
  status: 'pending',
} as ApprovalItem;

/** The fake core: what it was sent, and what it answers next. */
class FakeCore {
  configuredTools: ConnectorTool[] | ApiError = structuredClone(TOOLS);
  pendingTools: Promise<{ tools: ConnectorTool[] }> | null = null;
  toolRequests: { type: string; config: JsonObject; env: string }[] = [];
  tests: TestDraftRequest[] = [];
  nextTest: TestOutcome | ApiError = OK;
  discoveries: TestDraftRequest[] = [];
  nextDiscovery: TestOutcome | ApiError = outcome({ ok: true, details: { databases: ['app', 'reports'] } });
  pendingDiscovery: Promise<TestOutcome> | null = null;
  projectRequests: TestDraftRequest[] = [];
  nextProjects: TestOutcome | ApiError = outcome({ ok: true, details: {
    projects: [{ projectId: 'example-dev', displayName: 'Example development' }], discoveryComplete: true,
  } });
  pendingProjects: Promise<TestOutcome> | null = null;
  suggestRequests: SuggestRequest[] = [];
  nextSuggestions: Suggested | ApiError = { fields: {}, notes: [] };
  fileRequests: (TestDraftRequest & { readonly path: string })[] = [];
  nextFiles: TestOutcome | ApiError = outcome({ ok: true, details: { path: '/var/log', parent: '/var',
    entries: [{ name: 'app.log', path: '/var/log/app.log', type: 'file', size: 20 }], truncated: false } });
  pendingFiles: Promise<TestOutcome> | null = null;
  pendingConfirm: Promise<TestConfirmed> | null = null;
  created: CreateConnectionRequest[] = [];
  createFails: ApiError | null = null;
  added: string[] = [];
  previews = new Map<string, ConnectionPreview>();
  updates: UpdateConnectionRequest[] = [];
  updateFails: ApiError[] = [];
  deleted: string[] = [];
  detail: ConnectionDetail | ApiError = structuredClone(DETAIL);
  spec: ConnectionFormSpec = structuredClone(SPEC);
  catalog: ConnectorCatalog | ApiError = structuredClone(CATALOG);
  pluginChecks: string[] = [];
  formTypes: string[] = [];
  /** A form that hasn't arrived yet: the request that installs a connector module and waits for it. */
  pendingForm: Promise<ConnectionFormSpec> | null = null;
  /** What `GET /api/modules` answers. */
  modules: ConnectorModules = { channel: { configured: true, checkedAt: null, offline: false, problem: null }, modules: [] };
  nextPluginCheck: PluginCheck | ApiError = clickhouseCheck(true);
  pluginInstalls: { stagingId: string; acknowledgeUnsigned: boolean }[] = [];
  replaced: { id: string; value: string; reason: string | null | undefined }[] = [];
  references: { id: string; uri: string; store: string | null | undefined; reason: string | null | undefined }[] = [];
  referenceFails: ApiError[] = [];
  /** Nothing set up yet: no connection, no endpoint (Add connection's first run). */
  empty = false;
  endpointsCreated: { slug: string; startFrom: string }[] = [];
  createEndpointFails: ApiError | null = null;
  /** The new endpoint's tools as the core keeps them (sql_execute starts in Approval on dev and prod). */
  endpointTools: ToolRow[] = [];
  modes: { slug: string; toolKey: string; connection: string | null; mode: ToolMode; version: number }[] = [];
  lastBackupAt: string | null = null;
  /** `GET /api/insights` for the edit view's "Calls, last 30 days", and what it was asked. */
  insights: Insights | ApiError = busyMonth(NOW);
  insightQueries: InsightsQuery[] = [];
  /** GateRook's verified MCP profiles, what the Official MCP Registry holds and was asked, the servers reviewed and started. */
  mcpProfiles: McpProfile[] = [];
  registryServers: McpRegistryServer[] = [];
  registrySearches: string[] = [];
  /** The registry the core searches (its answer names it) and a failure its next search answers with. */
  registryHost = 'registry.modelcontextprotocol.io';
  registryFails: ApiError | null = null;
  mcpReviews: McpServerSource[] = [];
  nextMcpReview: McpServerReview = {
    argv: ['npx', '-y', '@modelcontextprotocol/server-filesystem@2026.1.14', '/srv/docs'],
    resolvedCommand: 'npx -y @modelcontextprotocol/server-filesystem@2026.1.14 /srv/docs',
    node: '24.10.0',
    env: [],
    notPinned: false,
    catalogEntry: null,
    protocol: '2025-11-25',
    sandbox: 'sandbox-exec',
    name: 'docs',
    transport: 'stdio',
  };
  mcpStarted: (McpServerSource & { readonly acknowledge: true })[] = [];
  nextMcpStart: McpServerStarted = { name: 'docs', state: 'running', tools: 3 };
}

const row = (name: string, mode: ToolMode): ToolRow => ({
  toolKey: name,
  name,
  annotations: { readOnlyHint: mode === 'READ_ONLY', destructiveHint: mode !== 'READ_ONLY', idempotentHint: false, openWorldHint: false },
  declaredBy: null,
  mode,
  storedMode: mode,
  readOnlyAllowed: mode === 'READ_ONLY',
  reason: null,
  readOnlyConfirmed: false,
  definitionHash: null,
  overrides: null,
  version: 1,
});

let core: FakeCore;

beforeEach(() => {
  now.set(NOW);
  core = new FakeCore();
  const plugins: Partial<PluginsApi> = {
    check: vi.fn(async (source: { readonly url: string } | { readonly catalog: string }) => {
      core.pluginChecks.push('catalog' in source ? source.catalog : source.url);
      if (core.nextPluginCheck instanceof ApiError) throw core.nextPluginCheck;
      return structuredClone(core.nextPluginCheck);
    }),
    ensureRuntime: vi.fn(async () => undefined),
    install: vi.fn(async (stagingId: string, acknowledgeUnsigned = false) => {
      core.pluginInstalls.push({ stagingId, acknowledgeUnsigned });
      // The core now offers the installed plugin's type: the card is usable and no longer installs.
      if (!(core.catalog instanceof ApiError)) {
        core.catalog = { ...core.catalog, groups: core.catalog.groups.map((g) => ({
          ...g, cards: g.cards.map((c) => c.catalogRef ? { ...c, available: true, reason: null, catalogRef: null } : c),
        })) };
      }
      return {};
    }),
  };
  const mcp: Partial<McpServersApi> = {
    profiles: vi.fn(async () => structuredClone(core.mcpProfiles)),
    searchRegistry: vi.fn(async (search: string) => {
      core.registrySearches.push(search);
      const fail = core.registryFails;
      core.registryFails = null;
      if (fail) throw fail;
      const servers = core.registryServers.filter((s) => `${s.name} ${s.title ?? ''}`.toLowerCase().includes(search.toLowerCase()));
      return { registry: core.registryHost, servers: structuredClone(servers), nextCursor: null };
    }),
    review: vi.fn(async (source: McpServerSource) => {
      core.mcpReviews.push(structuredClone(source));
      return structuredClone(core.nextMcpReview);
    }),
    create: vi.fn(async (source: McpServerSource & { readonly acknowledge: true }) => {
      core.mcpStarted.push(structuredClone(source));
      return structuredClone(core.nextMcpStart);
    }),
  };
  const modules: Partial<ModulesApi> = {
    list: vi.fn(async () => structuredClone(core.modules)),
  };
  const types: Partial<ConnectorTypesApi> = {
    catalog: vi.fn(async () => {
      if (core.catalog instanceof ApiError) throw core.catalog;
      return structuredClone(core.catalog);
    }),
    form: vi.fn(async (type: string) => {
      core.formTypes.push(type);
      if (core.pendingForm) return core.pendingForm;
      return structuredClone(core.spec);
    }),
    get: vi.fn(async () => structuredClone(TYPE)),
    previewTools: vi.fn(async (type: string, config: JsonObject, env: string) => {
      core.toolRequests.push(structuredClone({ type, config, env }));
      if (core.pendingTools) return core.pendingTools;
      if (core.configuredTools instanceof ApiError) throw core.configuredTools;
      return { tools: structuredClone(core.configuredTools) };
    }),
  };
  const connections: Partial<ConnectionsApi> = {
    suggest: vi.fn(async (r: SuggestRequest) => {
      core.suggestRequests.push(structuredClone(r));
      if (core.nextSuggestions instanceof ApiError) throw core.nextSuggestions;
      return structuredClone(core.nextSuggestions);
    }),
    projects: vi.fn(async (r: TestDraftRequest) => {
      core.projectRequests.push(structuredClone(r));
      if (core.pendingProjects) return core.pendingProjects;
      if (core.nextProjects instanceof ApiError) throw core.nextProjects;
      return structuredClone(core.nextProjects);
    }),
    browseFiles: vi.fn(async (r: TestDraftRequest & { readonly path: string }) => {
      core.fileRequests.push(structuredClone(r));
      if (core.pendingFiles) return core.pendingFiles;
      if (core.nextFiles instanceof ApiError) throw core.nextFiles;
      return structuredClone(core.nextFiles);
    }),
    databases: vi.fn(async (r: TestDraftRequest) => {
      core.discoveries.push(structuredClone(r));
      if (core.pendingDiscovery) return core.pendingDiscovery;
      if (core.nextDiscovery instanceof ApiError) throw core.nextDiscovery;
      return structuredClone(core.nextDiscovery);
    }),
    testDraft: vi.fn(async (r: TestDraftRequest) => {
      core.tests.push(structuredClone(r));
      if (core.nextTest instanceof ApiError) throw core.nextTest;
      return structuredClone(core.nextTest);
    }),
    confirmDraft: vi.fn(async () => core.pendingConfirm ?? ({ kind: 'host-key', config: { connection: { hostKey: 'ssh-ed25519 AAAA' } }, retest: OK })),
    create: vi.fn(async (r: CreateConnectionRequest) => {
      if (core.createFails) throw core.createFails;
      core.created.push(structuredClone(r));
      return { name: r.name } as never;
    }),
    duplicate: vi.fn(async () => ({ type: 'postgres', env: 'dev' as const, config: structuredClone(DETAIL.config), emptySecretFields: ['/credentials/password'], notice: null })),
    get: vi.fn(async () => {
      if (core.detail instanceof ApiError) throw core.detail;
      return structuredClone(core.detail);
    }),
    setEnabled: vi.fn(async (_name: string, enabled: boolean) => {
      if (core.detail instanceof ApiError) throw core.detail;
      core.detail = { ...core.detail, enabled, version: core.detail.version + 1 };
      return { ...core.detail, usedBy: [], badges: [], secretSource: 'encrypted', host: null };
    }),
    list: vi.fn(async () =>
      (core.empty ? { items: [], facets: {}, total: 0 } : { items: [{ name: 'dev-db', host: 'db.dev.internal:5432/app', usedBy: ['dev'] }], facets: {}, total: 1 }) as never,
    ),
    update: vi.fn(async (_name: string, r: UpdateConnectionRequest) => {
      core.updates.push(structuredClone(r));
      const fail = core.updateFails.shift();
      if (fail) throw fail;
      return {} as never;
    }),
    delete: vi.fn(async (name: string) => {
      core.deleted.push(name);
      return { stoppedTools: [], rejectedApprovals: 1, templates: [], clients: [], secretsDestroyed: [] };
    }),
    test: vi.fn(async () => OK),
  };
  const groups = (connection: string): ToolGroup[] => [{ group: connection, connection, env: null, tools: structuredClone(core.endpointTools) }];
  const endpoints: Partial<EndpointsApi> = {
    list: vi.fn(async () => (core.empty ? [] : structuredClone(ENDPOINTS))),
    get: vi.fn(async (slug: string) => ({ endpoint: ENDPOINTS.find((endpoint) => endpoint.slug === slug) } as never)),
    create: vi.fn(async (r: { slug: string; startFrom: string }) => {
      if (core.createEndpointFails) throw core.createEndpointFails;
      core.endpointsCreated.push({ slug: r.slug, startFrom: r.startFrom });
      return endpoint({ slug: r.slug, url: `http://127.0.0.1:7465/mcp/${r.slug}`, clients: [] });
    }),
    tools: vi.fn(async () => groups('qa-db')),
    setMode: vi.fn(async (slug: string, r: { toolKey: string; connection: string | null; mode: ToolMode; version: number }) => {
      core.modes.push({ slug, toolKey: r.toolKey, connection: r.connection, mode: r.mode, version: r.version });
      core.endpointTools = core.endpointTools.map((t) => (t.toolKey === r.toolKey ? { ...t, mode: r.mode, storedMode: r.mode, version: t.version + 1 } : t));
      return groups(r.connection ?? 'qa-db');
    }),
    previewConnection: vi.fn(async (slug: string, name: string) => core.previews.get(slug) ?? { connection: name, env: 'dev' as const, tools: [], warnings: [], refusal: null }),
    addConnection: vi.fn(async (slug: string) => {
      core.added.push(slug);
      return {} as never;
    }),
  };
  const policies: Partial<PoliciesApi> = { all: vi.fn(async () => POLICIES), schema: vi.fn(async () => structuredClone(POLICY_SCHEMA)) };
  const secrets: Partial<SecretsApi> = {
    list: vi.fn(async () => [{ id: 'sec-1', rotatedAt: '2026-10-20T09:14:00Z', canRotate: false, rotateReason: "This connector can't issue a new value for itself." } as SecretRow]),
    testReference: vi.fn(async () => ({ found: true, resolvedVersion: '4', created: null, type: 'string', keys: [] })),
    setReference: vi.fn(async (id: string, uri: string, store?: string | null, reason?: string | null) => {
      core.references.push({ id, uri, store, reason });
      const fail = core.referenceFails.shift();
      if (fail) throw fail;
      return { id, name: 'cache/password', owner: 'cache', field: '/credentials/password', source: 'vault', ref: uri, store: store ?? null, remoteVersion: null, state: 'reference' };
    }),
    replace: vi.fn(async (id: string, r: { value: string; reason?: string | null }) => {
      core.replaced.push({ id, value: r.value, reason: r.reason });
      return { id, version: 4, state: 'stored', tested: null, testMessage: null };
    }),
  };
  const masterKey: Partial<MasterKeyApi> = { status: vi.fn(async () => ({ lastBackupAt: core.lastBackupAt }) as MasterKeyStatus) };
  const approvals: Partial<ApprovalsApi> = { list: vi.fn(async () => ({ status: 'pending', items: [PENDING], nextCursor: null, pending: 1, counts: {} }) as never) };
  const insights: Partial<InsightsApi> = {
    get: vi.fn(async (query?: InsightsQuery) => {
      core.insightQueries.push(structuredClone(query ?? {}));
      if (core.insights instanceof ApiError) throw core.insights;
      return structuredClone(core.insights);
    }),
  };
  @Component({ template: '' })
  class Elsewhere {}
  TestBed.configureTestingModule({
    providers: [
      provideRouter(
        [
          { path: 'connections/new', component: ConnectionNewPage },
          { path: 'connections/:name', component: ConnectionNewPage },
          { path: '**', component: Elsewhere },
        ],
        withComponentInputBinding(),
      ),
      provideKitIcons(),
      { provide: TimeService, useValue: fakeTime },
      { provide: ConnectorTypesApi, useValue: types },
      { provide: ConnectionsApi, useValue: connections },
      { provide: EndpointsApi, useValue: endpoints },
      { provide: PoliciesApi, useValue: policies },
      { provide: SecretsApi, useValue: secrets },
      { provide: ApprovalsApi, useValue: approvals },
      { provide: MasterKeyApi, useValue: masterKey },
      { provide: InsightsApi, useValue: insights },
      { provide: PluginsApi, useValue: plugins },
      { provide: ModulesApi, useValue: modules },
      { provide: McpServersApi, useValue: mcp },
      { provide: EVENT_STREAM_FETCH, useValue: (() => new Promise<Response>(() => undefined)) as typeof fetch },
    ],
  });
  TestBed.inject(AriaDisabledGuard).install();
});

afterEach(() => {
  vi.restoreAllMocks();
  document.body.innerHTML = '';
});

const text = (el: Element | null | undefined) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();

async function open(url = '/connections/new') {
  const harness = await RouterTestingHarness.create();
  document.body.appendChild(harness.fixture.nativeElement);
  await harness.navigateByUrl(url);
  await settle(harness);
  const el = harness.routeNativeElement as HTMLElement;
  const q = <T extends Element = HTMLElement>(sel: string) => el.querySelector<T>(sel) ?? (document.querySelector(sel) as T | null);
  const button = (name: string, root: ParentNode = el) =>
    Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find((b) => text(b) === name || text(b).startsWith(`${name} `))!;
  const type = async (id: string, value: string) => {
    const input = document.getElementById(id) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await settle(harness);
  };
  return {
    harness,
    el,
    q,
    button,
    type,
    settle: () => settle(harness),
    page: () => harness.routeDebugElement!.componentInstance as ConnectionNewPage,
    visible: () => Array.from(el.querySelectorAll<HTMLElement>('section.gw-section[id^="step-"]')).filter((s) => !s.hidden).map((s) => s.id),
    steps: () => Array.from(el.querySelectorAll('.gw-stepper__step')).map((s) => `${s.getAttribute('data-state')}:${text(s)}`),
  };
}

/** Lets the fake API's promises, the effects and the render run until the page is quiet. */
async function settle(harness: RouterTestingHarness): Promise<void> {
  for (let i = 0; i < 8; i++) {
    await new Promise((r) => setTimeout(r));
    harness.detectChanges();
  }
  await harness.fixture.whenStable();
}

/** Step 1 → Configure → a filled form → Test. */
async function toTest(p: Awaited<ReturnType<typeof open>>, host = 'db.dev.internal') {
  p.button('Continue').click();
  await p.settle();
  await p.type('cf-name', 'qa-db');
  await p.type('cf-connection-host', host);
  await p.type('cf-credentials-user', 'app_rw');
  await p.type('cf-credentials-password', 's3cret-value');
  p.button('Load databases').click();
  await p.settle();
  p.q<HTMLInputElement>('#cf-database-0')!.click();
  await p.settle();
  p.button('Continue', p.q('#cf-form')!).click();
  await p.settle();
}

function mongoDb(): void {
  core.spec = { ...structuredClone(SPEC), type: 'mongodb', name: 'MongoDB', schema: CONNECTOR_FORMS['mongodb'] };
  core.catalog = { groups: [{ name: 'Databases', cards: [{ ...structuredClone(CATALOG.groups[0].cards[0]),
    type: 'mongodb', name: 'MongoDB', connections: 0,
  }] }], installed: [] };
  core.nextTest = outcome({ ok: true, serverVersion: 'MongoDB 8.0', identity: 'reader' });
}

/** The Logs catalog preset and canonical connector schema used by the OpenSearch Configure page. */
function openSearch(): void {
  core.spec = {
    ...structuredClone(SPEC), type: 'logs', name: 'Logs', schema: CONNECTOR_FORMS['logs'],
    secretFields: [{ pointer: '/auth/secret', kind: 'value', title: 'Password, API key or bearer token' }],
  };
  core.catalog = {
    groups: [{ name: 'Logs', cards: [{
      ...structuredClone(CATALOG.groups[0].cards[0]), type: 'logs', variant: 'opensearch', name: 'OpenSearch',
      presetConfig: { backend: 'opensearch' }, tools: 5, connections: 0,
    }] }], installed: [],
  };
}

async function openSearchToTest(p: Awaited<ReturnType<typeof open>>): Promise<void> {
  await p.type('cf-name', 'dev-search');
  await p.type('cf-url', 'http://localhost:9200');
  await p.type('cf-auth-username', 'search-user');
  await p.type('cf-auth-secret', 'search-password');
  p.button('Continue', p.q('#cf-form')!).click();
  await p.settle();
}

function cloudLogging(): void {
  core.spec = { ...structuredClone(SPEC), type: 'logs', name: 'Logs', schema: CONNECTOR_FORMS['logs'],
    secretFields: [{ pointer: '/credentials/serviceAccountKey', kind: 'value', title: 'Service account key (JSON)' }] };
  core.catalog = { groups: [{ name: 'Logs', cards: [{ ...structuredClone(CATALOG.groups[0].cards[0]),
    type: 'logs', variant: 'gcp-logging', name: 'Google Cloud Logging',
    presetConfig: { backend: 'gcp-logging' }, tools: 2, connections: 0,
  }] }], installed: [] };
}

describe('setup helpers', () => {
  it('asks the core what this computer knows and offers it under the field', async () => {
    core.nextSuggestions = { fields: { '/connection/host': { value: null, source: 'this computer', complete: true,
      options: [{ value: 'localhost', label: null, detail: 'A PostgreSQL server answers on port 5432' }] } },
    notes: ['A PostgreSQL server answers on localhost:5432.'] };
    const p = await open('/connections/new#step-2');
    await new Promise((resolve) => setTimeout(resolve, 600));
    await p.settle();
    expect(core.suggestRequests.length).toBeGreaterThan(0);
    expect(core.suggestRequests[0]).toMatchObject({ type: 'postgres', env: 'dev', scope: 'local' });
    expect(core.suggestRequests[0].existing).toBeUndefined();
    const line = p.q('#cf-connection-host-found-line')!;
    expect(text(line)).toContain('Found on this computer:');
    p.button('localhost', line).click();
    await p.settle();
    expect(p.q<HTMLInputElement>('#cf-connection-host')!.value).toBe('localhost');
    expect(text(p.q('#cf-helpers'))).toContain('A PostgreSQL server answers on localhost:5432.');
  });

  it('offers nothing new, and asks nothing, when the core refuses a lookup', async () => {
    core.nextSuggestions = new ApiError('conflict', 409, { title: 'Send the stored secret to a new host?', detail: 'x',
      confirm: 'send-secret-to-new-host' });
    const p = await open('/connections/new#step-2');
    await new Promise((resolve) => setTimeout(resolve, 600));
    await p.settle();
    expect(core.suggestRequests.length).toBeGreaterThan(0);
    expect(p.q('#cf-helpers')).toBeNull();
    expect(p.q('dialog[open]')).toBeNull();
  });
});

describe('Add connection: MCP servers', () => {
  type Opened = Awaited<ReturnType<typeof open>>;
  const titles = (p: Opened) => Array.from(p.el.querySelectorAll('#type-picker .gw-connector-card__group-title')).map((h) => text(h));
  const values = (p: Opened) => Array.from(p.el.querySelectorAll<HTMLInputElement>('#type-picker input[type=radio]')).map((r) => r.value);
  const status = (p: Opened) => text(p.el.querySelector('#step-1 search [role=status]'));
  const isMcp = (value: string) => value === 'mcp-server' || value.startsWith('mcp-server:');
  const mcpField = (p: Opened, suffix: string) => p.q<HTMLInputElement>(`#step-2-mcp [id$="${suffix}"]`)!;
  /** Typing waits for the registry: it is asked a moment after the last key. */
  const search = async (p: Opened, query: string) => {
    await p.type('type-search', query);
    await new Promise((resolve) => setTimeout(resolve, 400));
    await p.settle();
  };
  const show = async (p: Opened, kind: 'all' | 'connectors' | 'mcp') => {
    p.q<HTMLInputElement>(`#step-1 .gw-segmented input[value="${kind}"]`)!.click();
    await p.settle();
  };
  const SUPABASE: McpProfile = {
    id: 'supabase', name: 'Supabase', publisher: 'Supabase', category: 'Databases', description: 'Supabase’s own server, read only.',
    documentation: 'https://supabase.com/docs/guides/getting-started/mcp', registryName: 'com.supabase/mcp', checked: '2026-11-11',
    transport: 'http', url: 'https://mcp.supabase.com/mcp?project_ref={project_ref}&read_only=true', headers: [], command: [],
    placeholders: [{ name: 'project_ref', description: 'Your project’s reference.' }], pins: [], signIn: 'OAuth in your browser.',
    notes: [], tools: [],
  };

  it('lists the MCP servers after the connector types, External MCP server first; Show narrows the list to one kind', async () => {
    core.mcpProfiles = [SUPABASE];
    const p = await open();
    const groups = titles(p);
    const mcpAt = groups.indexOf('Any MCP server');
    expect(mcpAt).toBeGreaterThan(0);
    expect(groups.slice(mcpAt)).toEqual(['Any MCP server', 'Verified MCP servers', 'Popular MCP servers']);
    const all = values(p);
    const firstMcp = all.findIndex(isMcp);
    expect(all[firstMcp]).toBe('mcp-server');
    expect(all.slice(firstMcp).every(isMcp)).toBe(true);
    // A vendor with a verified profile shows once, as its verified card.
    expect(all).toContain('mcp-server:profile.supabase');
    expect(all).not.toContain('mcp-server:featured.com-supabase-mcp');
    expect(all).toContain('mcp-server:featured.io-github-github-github-mcp-server');
    expect(text(p.q('#type-picker input[value="mcp-server:profile.supabase"]')!.closest('label'))).toContain('Verified profile');
    const types = firstMcp;
    const servers = all.length - firstMcp;
    expect(status(p)).toBe(`${types} connector types and ${servers} MCP servers`);

    await show(p, 'mcp');
    expect(titles(p)).toEqual(['Any MCP server', 'Verified MCP servers', 'Popular MCP servers']);
    expect(values(p)).toHaveLength(servers);
    expect(status(p)).toBe(`${servers} MCP servers`);
    await show(p, 'connectors');
    expect(titles(p)).toEqual(groups.slice(0, mcpAt));
    expect(status(p)).toBe(`${types} connector types`);
    // The connector types alone never ask the registry.
    await search(p, 'post');
    expect(core.registrySearches).toEqual([]);
  });

  it('opens on the MCP servers with nothing chosen for Add MCP server (?show=mcp)', async () => {
    const p = await open('/connections/new?show=mcp');
    expect(titles(p)).toEqual(['Any MCP server', 'Popular MCP servers']);
    expect(values(p)[0]).toBe('mcp-server');
    expect(p.page().selected()).toBeNull();
    expect(text(p.q('#step-1 .gw-form__note'))).toBe('Choose a server, or enter one by hand with External MCP server.');
  });

  it('searches the Official MCP Registry from three characters and adds its servers last', async () => {
    core.registryServers = [{
      name: 'io.github.example/docs-mcp', title: 'Docs MCP', description: 'Search the team’s docs.', version: '1.4.0', status: 'active',
      remotes: [{ type: 'streamable-http', url: 'https://docs.example.test/mcp', headers: [], variables: [] }], packages: [],
    }];
    const p = await open();
    await search(p, 'do');
    expect(core.registrySearches).toEqual([]);
    await search(p, 'docs');
    expect(core.registrySearches).toContain('docs');
    expect(titles(p).at(-1)).toBe('Official MCP Registry');
    expect(values(p).at(-1)).toBe('mcp-server:registry.io-github-example-docs-mcp');
    expect(status(p)).toMatch(/; 1 in the Official MCP Registry$/);
  });

  it('reads a pasted MCP configuration as one card, chosen, never sent to the registry, and Configure starts from it', async () => {
    const p = await open();
    await search(p, '{"mcpServers":{"docs":{"command":"npx","args":["-y","@modelcontextprotocol/server-filesystem@2026.1.14","/srv/docs"]}}}');
    expect(titles(p)).toEqual(['From your paste']);
    expect(values(p)).toEqual(['mcp-server:paste']);
    expect(p.page().selected()).toBe('mcp-server:paste');
    expect(status(p)).toBe('Read from your paste: one card, chosen');
    expect(core.registrySearches).toEqual([]);
    p.button('Continue').click();
    await p.settle();
    expect(p.visible()).toEqual(['step-2-mcp']);
    expect(mcpField(p, '-name').value).toBe('docs');
    expect(mcpField(p, '-command').value).toBe('npx -y @modelcontextprotocol/server-filesystem@2026.1.14 /srv/docs');
  });

  it('reads a pasted PostgreSQL connection string into Configure, without its password', async () => {
    const p = await open();
    await search(p, 'postgres://app_rw:s3cret@db.dev.internal:6432/orders');
    expect(titles(p)).toEqual(['From your paste']);
    expect(values(p)).toEqual(['postgres:paste']);
    p.button('Continue').click();
    await p.settle();
    expect(p.visible()).toEqual(['step-2']);
    expect(p.q<HTMLInputElement>('#cf-connection-host')!.value).toBe('db.dev.internal');
    expect(p.q<HTMLInputElement>('#cf-connection-port')!.value).toBe('6432');
    expect(p.q<HTMLInputElement>('#cf-credentials-user')!.value).toBe('app_rw');
    expect(p.q<HTMLInputElement>('#cf-credentials-password')!.value).toBe('');
  });

  it('adds an External MCP server in the wizard: Configure, the review that starts it, then its endpoint', async () => {
    // The core's own External MCP server card isn't repeated: MCP servers have their section after the connector types.
    const card = { ...structuredClone(CATALOG.groups[0].cards[0]), type: 'mcp-server', name: 'External MCP server', icon: 'plug', description: 'Put any MCP server behind modes, approvals and audit.', tools: null, connections: 0 };
    core.catalog = { groups: [{ name: 'HTTP and MCP', cards: [card] }], installed: [] };
    const p = await open();
    expect(text(p.q('#step-1 .gw-alert__title'))).toBe('No connector types');
    expect(titles(p)).toEqual(['Any MCP server', 'Popular MCP servers']);
    p.q<HTMLInputElement>('#type-picker input[value="mcp-server"]')!.click();
    await p.settle();
    p.button('Continue').click();
    await p.settle();
    expect(TestBed.inject(Router).url).toBe('/connections/new');
    expect(p.visible()).toEqual(['step-2-mcp']);
    expect(text(p.q('#step-2-mcp-title'))).toBe('Configure your MCP server');
    expect(p.steps()).toEqual(['complete:Choose, completed External MCP server', 'current:2 Configure', 'upcoming:3 Review', 'upcoming:4 Add to endpoint']);

    await p.type(mcpField(p, '-name').id, 'docs');
    await p.type(mcpField(p, '-command').id, 'npx -y @modelcontextprotocol/server-filesystem@2026.1.14 /srv/docs');
    p.button('Continue', p.q('#step-2-mcp')!).click();
    await p.settle();
    // The review runs nothing: the core only reads what would run.
    expect(core.mcpReviews).toHaveLength(1);
    expect(core.mcpStarted).toEqual([]);
    expect(p.visible()).toEqual(['step-3-mcp']);
    expect(text(p.q('#step-3-mcp-title'))).toBe('Review docs');

    const start = p.button('Start MCP server docs', p.q('#step-3-mcp')!);
    expect(start.getAttribute('aria-disabled')).toBe('true');
    p.q<HTMLInputElement>('#step-3-mcp input[type=checkbox][id$="-ack"]')!.click();
    await p.settle();
    start.click();
    await p.settle();
    // Starting saves it, tools Off: the wizard can't go back past this point, and Cancel goes.
    expect(core.mcpStarted).toHaveLength(1);
    expect(p.visible()).toEqual(['step-4-mcp']);
    expect(text(p.q('#step-4-mcp-title'))).toBe('Add docs to an endpoint');
    expect(p.q('.gw-page-header__actions a')).toBeNull();

    p.button('Add to endpoint', p.q('#step-4-mcp')!).click();
    await p.settle();
    expect(core.added).toEqual(['dev']);
    expect(TestBed.inject(Router).url).toBe('/connections/docs');
  });

  const LINEAR_ENTRY: McpRegistryServer = {
    name: 'app.linear/linear', title: 'Linear', description: 'Issues, projects and cycles.', version: '1.0.2', status: 'active',
    remotes: [{ type: 'streamable-http', url: 'https://mcp.linear.app/mcp', headers: [], variables: [] }], packages: [],
  };

  it('opens a search result from Connectors on its Configure, from the registry the core searches (?registryServer=)', async () => {
    core.registryHost = 'registry.example.test';
    core.registryServers = [{ ...LINEAR_ENTRY, name: 'io.github.example/docs-mcp', title: 'Docs MCP', status: 'deprecated' }];
    const p = await open('/connections/new?registryServer=io.github.example/docs-mcp');
    expect(core.registrySearches).toEqual(['io.github.example/docs-mcp']);
    expect(p.visible()).toEqual(['step-2-mcp']);
    const origin = p.q('#step-2-mcp [id$="-origin"]')!;
    expect(text(origin.querySelector('.gw-alert__title'))).toBe('Filled in from the MCP registry at registry.example.test: Docs MCP');
    expect(text(origin)).toContain('Its publisher marked this entry deprecated.');
  });

  it('keeps a Popular shortcut to the Official MCP Registry: another registry’s entry never stands in (?registry=)', async () => {
    core.registryHost = 'registry.example.test';
    core.registryServers = [LINEAR_ENTRY];
    const p = await open('/connections/new?registry=app.linear/linear');
    expect(p.visible()).toEqual(['step-1']);
    expect(p.page().selected()).toBe('mcp-server:featured.app-linear-linear');
    expect(text(p.q('#type-picker'))).toContain(
      'Linear can’t be set up from the registry: This shortcut requires the Official MCP Registry. Search your configured registry or enter the server by hand.',
    );
  });

  it('tries a Popular pick again after its registry lookup failed, and Configure fills from the official entry', async () => {
    core.registryServers = [LINEAR_ENTRY];
    core.registryFails = new ApiError('network', 0, {});
    const p = await open('/connections/new?show=mcp');
    p.q<HTMLInputElement>('#type-picker input[value="mcp-server:featured.app-linear-linear"]')!.click();
    await p.settle();
    p.button('Continue').click();
    await p.settle();
    expect(p.visible()).toEqual(['step-1']);
    expect(text(p.q('#type-picker'))).toContain('Linear can’t be set up from the registry:');
    p.button('Continue').click();
    await p.settle();
    expect(core.registrySearches).toEqual(['app.linear/linear', 'app.linear/linear']);
    expect(p.visible()).toEqual(['step-2-mcp']);
    expect(text(p.q('#step-2-mcp [id$="-origin"] .gw-alert__title'))).toBe('Filled in from the Official MCP Registry: Linear');
  });

  it('keeps what Configure holds when the review goes Back, and reviews it again', async () => {
    const p = await open('/connections/new?type=mcp-server');
    await p.type(mcpField(p, '-name').id, 'docs');
    await p.type(mcpField(p, '-command').id, 'npx -y @modelcontextprotocol/server-filesystem@2026.1.14 /srv/docs');
    p.button('Continue', p.q('#step-2-mcp')!).click();
    await p.settle();
    expect(p.visible()).toEqual(['step-3-mcp']);
    p.button('Back', p.q('#step-3-mcp')!).click();
    await p.settle();
    expect(p.visible()).toEqual(['step-2-mcp']);
    expect(mcpField(p, '-name').value).toBe('docs');
    expect(mcpField(p, '-command').value).toBe('npx -y @modelcontextprotocol/server-filesystem@2026.1.14 /srv/docs');
    p.button('Continue', p.q('#step-2-mcp')!).click();
    await p.settle();
    expect(core.mcpReviews).toHaveLength(2);
    expect(core.mcpStarted).toEqual([]);
    expect(p.visible()).toEqual(['step-3-mcp']);
  });

  it('opens a link to the External MCP server type on its Configure, in the wizard (?type=mcp-server)', async () => {
    const p = await open('/connections/new?type=mcp-server');
    expect(TestBed.inject(Router).url).toBe('/connections/new?type=mcp-server');
    expect(p.visible()).toEqual(['step-2-mcp']);
    expect(p.page().selected()).toBe('mcp-server');
    expect(text(p.q('#step-2-mcp-title'))).toBe('Configure your MCP server');
  });

  it('offers the services that exist already when it comes from Overview’s Add service (?endpoint=)', async () => {
    const p = await open('/connections/new?endpoint=stage-readonly');
    const alert = p.q('#existing-services')!;
    expect(text(alert)).toContain('1 service you already have isn’t on stage-readonly yet.');
    const link = alert.querySelector<HTMLAnchorElement>('a')!;
    expect([text(link), link.getAttribute('href')]).toEqual(['Connect existing services', '/connections?notConnectedTo=stage-readonly']);
  });
});

describe('Redis production connections', () => {
  it('tests and saves Without TLS with a warning and no confirmation', async () => {
    core.spec = { ...structuredClone(SPEC), type: 'redis', name: 'Redis', schema: CONNECTOR_FORMS['redis'] };
    core.catalog = { groups: [{ name: 'Databases', cards: [{
      ...structuredClone(CATALOG.groups[0].cards[0]), type: 'redis', name: 'Redis', tools: 1, connections: 0,
    }] }], installed: [] };
    core.configuredTools = [tool({ name: 'redis_get', description: 'Read a Redis value.' })];
    const warning = 'TLS is off: the password and the values travel in clear text.';
    core.nextTest = outcome({
      ok: true, latencyMs: 3, serverVersion: 'Redis 7.2',
      hints: [{ severity: 'warning', code: 'redis.tls-off', field: 'connection.tls', text: warning, snippet: null }],
    });

    const p = await open('/connections/new#step-2');
    await p.type('cf-name', 'prod-cache');
    await p.type('cf-connection-host', 'cache.example.test');
    await p.type('cf-credentials-password', 'redis-test-password');
    p.q<HTMLInputElement>('input[name="cf-env"][value="prod"]')!.click();
    await p.settle();

    // Auto by default, TLS on prod: the choice sits under Advanced settings.
    p.q<HTMLButtonElement>('[aria-controls="cf-advanced-body"]')!.click();
    await p.settle();
    const tls = p.q<HTMLSelectElement>('#cf-connection-tls')!;
    expect(tls.tagName).toBe('SELECT');
    expect(tls.closest('[hidden]')).toBeNull();
    expect(tls.value).toBe('true');
    tls.value = 'false';
    tls.dispatchEvent(new Event('change', { bubbles: true }));
    await p.settle();
    expect(tls.selectedOptions[0].textContent).toBe('Without TLS');

    p.button('Continue', p.q('#cf-form')!).click();
    await p.settle();
    expect(p.visible()).toEqual(['step-3']);
    expect(core.tests).toHaveLength(1);
    expect(core.tests[0]).toMatchObject({
      type: 'redis', env: 'prod', config: { connection: { host: 'cache.example.test', tls: false } },
    });
    expect(text(p.q('#step-3 .gw-status'))).toContain('Connected');
    expect(text(p.q('#step-3 .gw-alert--warning'))).toBe(warning);
    expect(p.q('#step-3 .gw-alert--danger')).toBeNull();
    expect(document.querySelector('dialog[open]')).toBeNull();

    p.button('Continue', p.q('#step-3')!).click();
    await p.settle();
    const save = p.button('Save connection', p.q('#step-4')!);
    expect(save.getAttribute('aria-disabled')).toBeNull();
    save.click();
    await p.settle();
    expect(core.created).toHaveLength(1);
    expect(core.created[0]).toMatchObject({
      name: 'prod-cache', type: 'redis', env: 'prod', config: { connection: { host: 'cache.example.test', tls: false } },
    });
  });
});

describe('connection-specific tool preview', () => {
  const gcpTools = [
    tool({ name: 'gcp_logging_search', description: 'Search Google Cloud Logging using Logs Explorer filters.' }),
    tool({ name: 'gcp_logging_tail', description: 'Read new Google Cloud Logging entries.' }),
  ];

  it('uses the configured connector tools in every endpoint card and mutation hint', async () => {
    cloudLogging();
    core.configuredTools = gcpTools;
    const p = await open('/connections/new#step-4');
    expect(core.toolRequests.at(-1)).toMatchObject({ type: 'logs', config: { backend: 'gcp-logging' } });
    expect(core.toolRequests.at(-1)).not.toHaveProperty('secrets');
    expect(text(p.q('#pv-rows'))).toContain('gcp_logging_search');
    expect(text(p.q('#pv-rows'))).toContain('gcp_logging_tail');
    expect(text(p.q('#step-4'))).not.toMatch(/sql_execute|opensearch_|Loki|CloudWatch/);
    expect(text(p.q('#pv-rows .gw-card__description'))).toBe('2 tools · 2 read only');
  });

  it('does not substitute a category tool list when discovery fails and allows retry', async () => {
    cloudLogging();
    core.configuredTools = new ApiError('server', 503, { detail: 'Tool metadata is temporarily unavailable.' });
    const p = await open('/connections/new#step-4');
    expect(text(p.q('#tools-preview-status'))).toContain('Tool metadata is temporarily unavailable.');
    expect(text(p.q('#pv-rows'))).not.toContain('sql_query');
    expect(p.button('Save connection', p.q('#step-4')!).getAttribute('aria-disabled')).toBe('true');
    core.configuredTools = gcpTools;
    p.button('Retry loading tools').click();
    await p.settle();
    expect(text(p.q('#pv-rows'))).toContain('gcp_logging_search');
    expect(p.q('#tools-preview-status')).toBeNull();
    expect(p.button('Save connection', p.q('#step-4')!).getAttribute('aria-disabled')).toBeNull();
  });

  it('ignores late tool metadata after the connection configuration changes', async () => {
    cloudLogging();
    let resolve!: (value: { tools: ConnectorTool[] }) => void;
    core.pendingTools = new Promise((done) => { resolve = done; });
    const p = await open('/connections/new#step-4');
    expect(text(p.q('#tools-preview-status'))).toContain('Loading tools');
    core.pendingTools = null;
    core.configuredTools = [tool({ name: 'loki_search', description: 'Search Loki with LogQL.' })];
    p.page().form()!.state().model.update((value) => ({ ...value, backend: 'loki', url: 'https://logs.example.test' }));
    await p.settle();
    expect(text(p.q('#pv-rows'))).toContain('loki_search');
    resolve({ tools: gcpTools });
    await p.settle();
    expect(text(p.q('#pv-rows'))).toContain('loki_search');
    expect(text(p.q('#pv-rows'))).not.toContain('gcp_logging_search');
  });
});

describe('Google Cloud project discovery', () => {
  it.each(['logs', 'gcp'])('carries a discovered %s project through the draft API, test and save', async (type) => {
    cloudLogging();
    if (type === 'gcp') {
      core.spec = { ...core.spec, type: 'gcp', name: 'GCP', schema: CONNECTOR_FORMS['gcp'] };
      core.catalog = { groups: [{ name: 'Cloud', cards: [{ ...structuredClone(CATALOG.groups[0].cards[0]),
        type: 'gcp', variant: null, name: 'GCP', presetConfig: null,
      }] }], installed: [] };
    }
    const scopeField = type === 'gcp' ? 'scopes' : 'resourceNames';
    const p = await open('/connections/new#step-2');
    await p.type('cf-name', 'dev-cloud');
    p.button('Load projects').click();
    await p.settle();
    expect(core.projectRequests).toHaveLength(1);
    expect(core.projectRequests[0]).toMatchObject({ type, env: 'dev', config: {
      ...(type === 'logs' ? { backend: 'gcp-logging' } : {}), credentials: { source: 'adc' },
    }, secrets: {} });
    expect(core.created).toEqual([]);
    const select = p.q<HTMLSelectElement>('#cf-project-select')!;
    select.value = 'example-dev';
    select.dispatchEvent(new Event('change', { bubbles: true }));
    await p.settle();
    p.button('Continue', p.q('#cf-form')!).click();
    await p.settle();
    expect(p.visible()).toEqual(['step-3']);
    expect(core.tests[0].type).toBe(type);
    expect(core.tests[0].config[scopeField]).toEqual(['projects/example-dev']);
    p.button('Continue', p.q('#step-3')!).click();
    await p.settle();
    p.button('Save connection', p.q('#step-4')!).click();
    await p.settle();
    expect(core.created[0].type).toBe(type);
    expect(core.created[0].config[scopeField]).toEqual(['projects/example-dev']);
  });

  it('ignores a late project list after a credential change and retries an API failure', async () => {
    cloudLogging();
    const p = await open('/connections/new#step-2');
    let answer!: (value: TestOutcome) => void;
    core.pendingProjects = new Promise((resolve) => { answer = resolve; });
    p.button('Load projects').click();
    await p.settle();
    await p.type('cf-credentials-impersonate', 'reader@example-dev.iam.gserviceaccount.com');
    core.pendingProjects = null;
    core.nextProjects = new ApiError('network', 0, { title: 'Network error', detail: 'The project API is unavailable.' });
    p.button('Load projects').click();
    await p.settle();
    expect(text(p.q('#cf-form'))).toContain('The project API is unavailable.');
    answer(outcome({ ok: true, details: { projects: [{ projectId: 'stale-project', displayName: 'Stale project' }] } }));
    await p.settle();
    expect(text(p.q('#cf-form'))).not.toContain('Stale project');
    core.nextProjects = outcome({ ok: true, details: { projects: [{ projectId: 'current-project', displayName: 'Current project' }] } });
    p.button('Load projects').click();
    await p.settle();
    expect(text(p.q('#cf-project-select'))).toContain('Current project');
    expect(core.projectRequests.at(-1)?.config['credentials']).toMatchObject({ impersonate: 'reader@example-dev.iam.gserviceaccount.com' });
  });

  it('ignores a late project API validation failure after switching to another logging backend', async () => {
    cloudLogging();
    const p = await open('/connections/new#step-2');
    let reject!: (error: ApiError) => void;
    core.pendingProjects = new Promise((_resolve, rejectPromise) => { reject = rejectPromise; });
    p.button('Load projects').click();
    await p.settle();
    const backend = p.q<HTMLSelectElement>('#cf-backend')!;
    backend.value = 'loki';
    backend.dispatchEvent(new Event('change', { bubbles: true }));
    await p.settle();
    expect(p.q('#cf-project-load')).toBeNull();
    reject(new ApiError('unprocessable', 422, { title: 'Invalid credentials', detail: 'The old Google credential is invalid.',
      errors: [{ field: '/config/credentials/serviceAccountKey', message: 'The old Google credential is invalid.' }] }));
    await p.settle();
    expect(text(p.q('#cf-form'))).not.toContain('The old Google credential is invalid.');
  });
});

describe('Add connection: wizard', () => {
  it('OpenSearch highlights invalid visible fields and advances after the basic credentials are filled', async () => {
    openSearch();
    const p = await open('/connections/new#step-2');
    await p.type('cf-name', 'dev-search');
    p.button('Continue', p.q('#cf-form')!).click();
    await p.settle();
    expect(p.visible()).toEqual(['step-2']);
    expect(core.tests).toEqual([]);
    expect(p.q('#cf-url')!.getAttribute('aria-invalid')).toBe('true');
    expect(text(p.q('#cf-url-err'))).not.toBe('');
    expect(document.activeElement?.id).toBe('cf-url');
    expect(p.q('#cf-auth-awsRegion')).toBeNull();
    await openSearchToTest(p);
    expect(p.visible()).toEqual(['step-3']);
    expect(core.tests).toHaveLength(1);
    expect(core.tests[0]).toMatchObject({
      type: 'logs', config: { backend: 'opensearch', url: 'http://localhost:9200', auth: { mode: 'basic', username: 'search-user' } },
      secrets: { '/auth/secret': { source: 'local', value: 'search-password' } },
    });
    p.button('Continue', p.q('#step-3')!).click();
    await p.settle();
    p.button('Save connection').click();
    await p.settle();
    expect(core.created.map((request) => request.name)).toEqual(['dev-search']);
  });

  it('an OpenSearch server field error returns to Configure, opens Advanced and focuses the field', async () => {
    openSearch();
    core.createFails = new ApiError('unprocessable', 422, {
      title: 'Invalid connection', errors: [{ field: '/config/caCertificate', message: 'Use a PEM-encoded CA certificate.' }],
    });
    const p = await open('/connections/new#step-2');
    await openSearchToTest(p);
    p.button('Continue', p.q('#step-3')!).click();
    await p.settle();
    p.button('Save connection').click();
    await p.settle();
    expect(p.visible()).toEqual(['step-2']);
    expect(text(p.q('#cf-caCertificate-err'))).toBe('Use a PEM-encoded CA certificate.');
    const field = p.q('#cf-caCertificate')!;
    expect(field.closest('[hidden]')).toBeNull();
    expect(document.activeElement).toBe(field);
    expect(p.q('#connection-form-error')).toBeNull();
    expect(core.created).toEqual([]);
  });

  it.each([
    { errors: [{ field: '/config', message: 'Choose an authentication mode supported by this cluster.' }] },
    { errors: [] },
  ])('keeps a root save error in Configure and focuses the alert when errors are $errors', async ({ errors }) => {
    core.createFails = new ApiError('unprocessable', 422, {
      title: 'Invalid connection', detail: 'Choose an authentication mode supported by this cluster.', errors,
    });
    const p = await open();
    await toTest(p);
    p.button('Continue', p.q('#step-3')!).click();
    await p.settle();
    p.button('Save connection').click();
    await p.settle();
    expect(p.visible()).toEqual(['step-2']);
    const alert = p.q('#connection-form-error')!;
    expect(alert.getAttribute('role')).toBe('alert');
    expect(text(alert)).toContain('Choose an authentication mode supported by this cluster.');
    expect(document.activeElement).toBe(alert);
    expect(core.created).toEqual([]);
    core.createFails = null;
    p.button('Continue', p.q('#cf-form')!).click();
    await p.settle();
    expect(p.visible()).toEqual(['step-3']);
    expect(p.q('#connection-form-error')).toBeNull();
  });

  it('discovers with the entered credentials, retries errors and empty lists, and saves multiple choices', async () => {
    const p = await open('/connections/new#step-2');
    await p.type('cf-name', 'qa-db');
    await p.type('cf-connection-host', '127.0.0.1');
    await p.type('cf-credentials-user', 'pp_user');
    await p.type('cf-credentials-password', 'local-password');
    expect(p.q('#cf-connection-database')).toBeNull();
    core.nextDiscovery = outcome({ error: { code: 'access-denied', message: 'The password was rejected.', technical: null } });
    p.button('Load databases').click();
    await p.settle();
    expect(core.discoveries[0]).toMatchObject({
      type: 'postgres', env: 'dev', config: { connection: { host: '127.0.0.1', port: 5432 }, credentials: { user: 'pp_user' } },
      secrets: { '/credentials/password': { source: 'local', value: 'local-password' } },
    });
    expect(text(p.q('#cf-form'))).toContain('The password was rejected.');
    core.nextDiscovery = outcome({ ok: true, details: { databases: [] } });
    p.button('Load databases').click();
    await p.settle();
    expect(text(p.q('#cf-form'))).toContain('No databases are available to this user');
    p.button('Continue', p.q('#cf-form')!).click();
    await p.settle();
    expect(p.visible()).toEqual(['step-2']);
    expect(core.tests).toEqual([]);
    core.nextDiscovery = outcome({ ok: true, details: { databases: ['app', 'reports'] } });
    p.button('Reload databases').click();
    await p.settle();
    p.q<HTMLInputElement>('#cf-database-0')!.click();
    p.q<HTMLInputElement>('#cf-database-1')!.click();
    await p.settle();
    p.button('Continue', p.q('#cf-form')!).click();
    await p.settle();
    expect(core.tests[0].config['connection']).toMatchObject({ databases: ['app', 'reports'] });
    p.button('Continue', p.q('#step-3')!).click();
    await p.settle();
    p.button('Save connection', p.q('#step-4')!).click();
    await p.settle();
    expect(core.created).toHaveLength(1);
    expect(core.created[0].config['connection']).toMatchObject({ databases: ['app', 'reports'] });
    expect(core.created[0].config['connection']).not.toHaveProperty('database');
  });

  it.each([false, true])('saves selected databases after switching to prod without reloading (tested in dev: %s)', async (testInDev) => {
    const p = await open('/connections/new#step-2');
    await p.type('cf-name', 'prod-db');
    await p.type('cf-connection-host', 'db.example.test');
    await p.type('cf-credentials-user', 'reader');
    await p.type('cf-credentials-password', 'local-password');
    p.button('Load databases').click();
    await p.settle();
    p.q<HTMLInputElement>('#cf-database-0')!.click();
    await p.settle();
    if (testInDev) {
      p.button('Continue', p.q('#cf-form')!).click();
      await p.settle();
      p.button('Back', p.q('#step-3')!).click();
      await p.settle();
    }

    p.q<HTMLInputElement>('input[name="cf-env"][value="prod"]')!.click();
    await p.settle();
    p.button('Continue', p.q('#cf-form')!).click();
    await p.settle();
    expect(p.visible()).toEqual(['step-3']);
    // Prod moves the SSL mode from Auto to verify-full: the list is loaded again with it, the choice kept.
    expect(core.discoveries).toHaveLength(2);
    expect(core.discoveries[1]).toMatchObject({ env: 'prod', config: { connection: { sslMode: 'verify-full' } } });
    expect(core.tests.map((request) => request.env)).toEqual(testInDev ? ['dev', 'prod'] : ['prod']);
    expect(core.tests.at(-1)!.config['connection']).toMatchObject({ databases: ['app'], sslMode: 'verify-full' });

    p.button('Continue', p.q('#step-3')!).click();
    await p.settle();
    p.button('Save connection', p.q('#step-4')!).click();
    await p.settle();
    expect(core.created).toHaveLength(1);
    expect(core.created[0]).toMatchObject({ env: 'prod', config: { connection: { databases: ['app'] } } });
  });

  // Auto, the default, becomes TLS on prod; a transport the person chose (Without TLS) stays as chosen.
  it.each(['auto', false])('refreshes selected MongoDB databases with production settings before testing and saving (initial TLS: %s)', async (initialTls) => {
    mongoDb();
    const p = await open('/connections/new#step-2');
    await p.type('cf-name', 'prod-mongo');
    await p.type('cf-connection-host', 'mongo.example.test');
    await p.type('cf-credentials-user', 'reader');
    await p.type('cf-credentials-password', 'local-password');
    if (initialTls === false) {
      const tls = p.q<HTMLSelectElement>('#cf-connection-tls')!;
      tls.value = 'false';
      tls.dispatchEvent(new Event('change', { bubbles: true }));
      await p.settle();
    }
    const productionTls = initialTls === 'auto' ? true : false;
    p.button('Load databases').click();
    await p.settle();
    expect(core.discoveries[0].config['connection']).toMatchObject({ tls: initialTls });
    p.q<HTMLInputElement>('#cf-database-0')!.click();
    await p.settle();
    p.button('Continue', p.q('#cf-form')!).click();
    await p.settle();
    p.button('Back', p.q('#step-3')!).click();
    await p.settle();

    let answer!: (value: TestOutcome) => void;
    core.pendingDiscovery = new Promise((resolve) => { answer = resolve; });
    p.q<HTMLInputElement>('input[name="cf-env"][value="prod"]')!.click();
    await p.settle();
    expect(core.discoveries).toHaveLength(2);
    expect(core.discoveries[1]).toMatchObject({ type: 'mongodb', env: 'prod', config: {
      connection: { host: 'mongo.example.test', databases: ['app'], tls: productionTls, readPreference: 'secondaryPreferred' },
    } });
    expect(p.q('#cf-database-load')!.getAttribute('aria-busy')).toBe('true');
    expect(text(p.q('#cf-database-status'))).toContain('Connecting and loading databases');
    expect(p.q('#cf-database-error')).toBeNull();
    expect(p.button('Continue', p.q('#cf-form')!).getAttribute('aria-disabled')).toBe('true');
    expect(p.button('Continue', p.q('#cf-form')!).getAttribute('aria-describedby')).toBe('cf-database-status');
    p.button('Continue', p.q('#cf-form')!).click();
    await p.settle();
    expect(p.visible()).toEqual(['step-2']);
    expect(core.tests.map((request) => request.env)).toEqual(['dev']);
    expect(text(p.q('#cf-form'))).not.toContain('Load databases with the current connection settings');

    core.pendingDiscovery = null;
    answer(outcome({ ok: true, details: { databases: ['app', 'reports'] } }));
    await p.settle();
    expect(p.q<HTMLInputElement>('#cf-database-0')!.checked).toBe(true);
    expect(core.discoveries).toHaveLength(2);
    expect(p.button('Continue', p.q('#cf-form')!).getAttribute('aria-disabled')).toBeNull();
    expect(p.button('Continue', p.q('#cf-form')!).getAttribute('aria-describedby')).toBeNull();
    p.button('Continue', p.q('#cf-form')!).click();
    await p.settle();
    expect(p.visible()).toEqual(['step-3']);
    expect(core.tests.map((request) => request.env)).toEqual(['dev', 'prod']);
    expect(core.tests[1].config['connection']).toMatchObject({ databases: ['app'], tls: productionTls });
    p.button('Continue', p.q('#step-3')!).click();
    await p.settle();
    p.button('Save connection', p.q('#step-4')!).click();
    await p.settle();
    expect(core.created).toHaveLength(1);
    expect(core.created[0]).toMatchObject({ type: 'mongodb', env: 'prod', config: {
      connection: { databases: ['app'], tls: productionTls, readPreference: 'secondaryPreferred' },
    } });
  });

  it('shows a failed MongoDB environment refresh without retrying until requested', async () => {
    mongoDb();
    const p = await open('/connections/new#step-2');
    await p.type('cf-name', 'prod-mongo');
    await p.type('cf-connection-host', 'mongo.example.test');
    await p.type('cf-credentials-user', 'reader');
    await p.type('cf-credentials-password', 'local-password');
    p.button('Load databases').click();
    await p.settle();
    p.q<HTMLInputElement>('#cf-database-0')!.click();
    await p.settle();

    core.nextDiscovery = outcome({ error: { code: 'mongo.tls', message: 'The TLS certificate could not be verified.', technical: null } });
    p.q<HTMLInputElement>('input[name="cf-env"][value="prod"]')!.click();
    await p.settle();
    expect(core.discoveries).toHaveLength(2);
    expect(text(p.q('#cf-form'))).toContain('The TLS certificate could not be verified.');
    expect(p.q<HTMLInputElement>('#cf-database-0')!.checked).toBe(true);
    await p.type('cf-name', 'prod-mongo-reader');
    p.button('Continue', p.q('#cf-form')!).click();
    await p.settle();
    expect(p.visible()).toEqual(['step-2']);
    expect(core.discoveries).toHaveLength(2);
    expect(core.tests).toEqual([]);
    expect(core.created).toEqual([]);

    core.nextDiscovery = outcome({ ok: true, details: { databases: ['app', 'reports'] } });
    p.button('Load databases').click();
    await p.settle();
    expect(core.discoveries).toHaveLength(3);
    expect(p.q<HTMLInputElement>('#cf-database-0')!.checked).toBe(true);
    p.button('Continue', p.q('#cf-form')!).click();
    await p.settle();
    expect(p.visible()).toEqual(['step-3']);
    expect(core.tests[0].env).toBe('prod');
  });

  it('ignores late discovery after the host changes and keeps Continue on Configure until refreshed', async () => {
    const p = await open('/connections/new#step-2');
    await p.type('cf-name', 'qa-db');
    await p.type('cf-connection-host', 'first.internal');
    await p.type('cf-credentials-user', 'app_rw');
    await p.type('cf-credentials-password', 'password');
    let answer!: (value: TestOutcome) => void;
    core.pendingDiscovery = new Promise((resolve) => { answer = resolve; });
    p.button('Load databases').click();
    await p.settle();
    expect(p.q('#cf-database-load')!.getAttribute('aria-busy')).toBe('true');
    await p.type('cf-connection-host', 'second.internal');
    core.pendingDiscovery = null;
    core.nextDiscovery = outcome({ ok: true, details: { databases: ['current'] } });
    p.button('Load databases').click();
    await p.settle();
    answer(outcome({ ok: true, details: { databases: ['stale'] } }));
    await p.settle();
    expect(text(p.q('#cf-form'))).not.toContain('stale');
    expect(p.q<HTMLInputElement>('#cf-database-0')!.value).toBe('current');
    p.q<HTMLInputElement>('#cf-database-0')!.click();
    await p.settle();
    await p.type('cf-credentials-password', 'new-password');
    p.button('Continue', p.q('#cf-form')!).click();
    await p.settle();
    expect(p.visible()).toEqual(['step-2']);
    expect(text(p.q('#cf-database-error'))).toContain('current connection settings');
    expect(core.tests).toEqual([]);
  });

  it('opens on Choose with the catalog by group, the first usable type picked and how many use it', async () => {
    const { el, steps, visible } = await open();
    expect(visible()).toEqual(['step-1']);
    expect(steps()).toEqual(['current:1 Choose', 'upcoming:2 Configure', 'upcoming:3 Test', 'upcoming:4 Add to endpoint']);
    const radios = Array.from(el.querySelectorAll<HTMLInputElement>('#type-picker input[type=radio]'));
    // ClickHouse isn't installed but the signed catalog offers it: it can be chosen, and Continue installs it.
    expect(radios.filter((r) => !r.value.includes(':')).map((r) => [r.value, r.checked, r.getAttribute('aria-disabled')])).toEqual([
      ['postgres', true, null],
      ['clickhouse', false, null],
      // External MCP server leads the MCP section, after the connector types; it is never the first choice.
      ['mcp-server', false, null],
    ]);
    expect(radios.some((r) => r.value === 'postgres:supabase')).toBe(true);
    expect(text(el.querySelector('#step-1 .gw-form__note'))).toBe('3 connections use PostgreSQL');
    expect(document.activeElement).toBe(document.body);
  });

  it('groups the types by category with vendor marks, the MCP servers after them; the search filters both and Enter picks a lone match', async () => {
    const pg = CATALOG.groups[0].cards[0];
    core.catalog = { groups: [
      { name: 'Databases', cards: [
        structuredClone(pg),
        { ...structuredClone(pg), type: 'mongodb', name: 'MongoDB', description: 'Find and aggregate.', connections: 0 },
        structuredClone(CATALOG.groups[0].cards[1]),
      ] },
      { name: 'Remote access', cards: [
        { ...structuredClone(pg), type: 'ssh', name: 'SSH', icon: 'terminal', description: 'Runbooks.', connections: 0 },
      ] },
    ], installed: [] };
    const p = await open();
    const titles = () => Array.from(p.el.querySelectorAll('#type-picker .gw-connector-card__group-title')).map((h) => text(h));
    const radios = () => Array.from(p.el.querySelectorAll<HTMLInputElement>('#type-picker input[type=radio]')).map((r) => r.value);
    const types = () => radios().filter((v) => v !== 'mcp-server' && !v.startsWith('mcp-server:'));
    const status = () => text(p.el.querySelector('#step-1 search [role=status]'));
    // Three characters ask the Official MCP Registry a moment after the last key; its answer ends the status.
    const search = async (query: string) => {
      await p.type('type-search', query);
      await new Promise((resolve) => setTimeout(resolve, 400));
      await p.settle();
    };
    // The base types first, then the services that speak a base type's protocol, then the MCP servers.
    expect(titles()).toEqual(['SQL databases', 'NoSQL databases', 'Data warehouses', 'Remote access', 'PostgreSQL-compatible',
      'MongoDB-compatible', 'Any MCP server', 'Popular MCP servers']);
    const mark = p.el.querySelector<HTMLImageElement>('#step-1 input[value="postgres"] ~ .gw-choice-card__mark img')!;
    expect(mark.getAttribute('src')).toBe('assets/integrations/postgresql.svg');
    expect(mark.getAttribute('alt')).toBe('');
    expect(p.el.querySelector('#step-1 input[value="ssh"] ~ .gw-choice-card__icon')).not.toBeNull();
    const total = types().length;
    const all = radios().length;
    expect(status()).toBe(`${total} connector types and ${all - total} MCP servers`);

    // A compatible service's name finds its own card; "sql" finds the SQL databases and their family, never NoSQL.
    await search('cockroach');
    expect(titles()).toEqual(['PostgreSQL-compatible']);
    expect(radios()).toEqual(['postgres:cockroachdb']);
    expect(status()).toBe('1 connector type and 0 MCP servers match “cockroach”; 0 in the Official MCP Registry');
    await search('sql');
    expect(titles()).toEqual(['SQL databases', 'PostgreSQL-compatible']);
    expect(radios()).not.toContain('mongodb');
    // An MCP server's own words find it beside the connectors: "postgres" finds Supabase's and Neon's servers too.
    await search('postgres');
    expect(radios()).toEqual(expect.arrayContaining(['postgres', 'mcp-server:featured.com-supabase-mcp', 'mcp-server:featured.com-neon-mcp']));

    // Enter never submits the step: with one usable match it picks it.
    await search('ssh');
    const input = document.getElementById('type-search') as HTMLInputElement;
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
    await p.settle();
    expect(p.page().selected()).toBe('ssh');
    expect(p.visible()).toEqual(['step-1']);

    await search('zzz');
    expect(p.q<HTMLElement>('#type-picker')!.hidden).toBe(true);
    expect(text(p.el.querySelector('#step-1 .gw-empty__title'))).toBe('Nothing matches “zzz”');
    p.button('Clear search').click();
    await p.settle();
    expect(radios()).toHaveLength(all);
    expect(document.activeElement).toBe(input);
  });

  it('a compatible service opens its base connector with the service’s starting values', async () => {
    const p = await open();
    const supabase = p.q<HTMLInputElement>('#step-1 input[value="postgres:supabase"]')!;
    const card = supabase.closest('label')!;
    expect(text(card.querySelector('.gw-choice-card__title'))).toBe('Supabase');
    expect(text(card.querySelector('.gw-choice-card__meta'))).toBe('PostgreSQL connector · 3 tools');
    expect(card.querySelector('.gw-choice-card__mark img')!.getAttribute('src')).toBe('assets/integrations/supabase.svg');
    // A service without a mark of its own shows the base type's icon, never the base type's mark.
    const greenplum = p.q<HTMLInputElement>('#step-1 input[value="postgres:greenplum"]')!.closest('label')!;
    expect(greenplum.querySelector('.gw-choice-card__mark, .gw-choice-card__icon')).not.toBeNull();
    supabase.click();
    await p.settle();
    // No connection count of its own: the base type's count isn't the service's.
    expect(p.el.querySelector('#step-1 .gw-form__note')).toBeNull();
    p.button('Continue').click();
    await p.settle();
    expect(p.visible()).toEqual(['step-2']);
    expect(core.formTypes.at(-1)).toBe('postgres');
    const start = (p.page() as unknown as { startConfig(): unknown }).startConfig();
    expect(start).toEqual({ connection: { sslMode: 'verify-full' } });
  });

  it('names a plugin’s tools in its review, the last one right before the comma', async () => {
    const check = clickhouseCheck(true);
    core.nextPluginCheck = { ...check, report: { ...check.report, tools: [
      { name: 'ch_query', declared: { readOnlyHint: true } }, { name: 'ch_execute', declared: { readOnlyHint: false } }] } };
    const p = await open();
    p.q<HTMLInputElement>('#step-1 input[value="clickhouse"]')!.click();
    await p.settle();
    p.button('Continue').click();
    await p.settle();
    const review = document.querySelector<HTMLDialogElement>('dialog.gw-dialog[open]')!;
    const tools = Array.from(review.querySelectorAll('.gw-kv__row')).find((r) => text(r.querySelector('dt')) === 'Tools')!;
    expect(tools.querySelector('dd')!.textContent!.replace(/\s+/g, ' ').trim()).toBe('ch_query ch_execute, Off until you '
      + 'add them to an endpoint. There reads start as Read only and the others as Approval: the catalog key signed this build.');
  });

  it('a catalog plugin that isn’t installed is reviewed and installed on Continue, then Configure opens on it', async () => {
    const p = await open();
    p.q<HTMLInputElement>('#step-1 input[value="clickhouse"]')!.click();
    await p.settle();
    expect(text(p.el.querySelector('#step-1 .gw-form__note'))).toBe(
      "The ClickHouse plugin isn't installed yet: Continue shows what it is and installs it from the catalog.");
    p.button('Continue').click();
    await p.settle();
    expect(core.pluginChecks).toEqual(['clickhouse@1.0.0']);
    const review = document.querySelector<HTMLDialogElement>('dialog.gw-dialog[open]')!;
    expect(text(review.querySelector('.gw-dialog__title'))).toBe('Install ClickHouse plugin');
    expect(p.visible()).toEqual(['step-1']);
    p.button('Install ClickHouse plugin', review).click();
    await p.settle();
    expect(core.pluginInstalls).toEqual([{ stagingId: 'stg-1', acknowledgeUnsigned: false }]);
    expect(review.open).toBe(false);
    expect(p.visible()).toEqual(['step-2']);
    expect(core.formTypes.at(-1)).toBe('clickhouse');
  });

  /** A type of a connector module that isn't installed: a built-in card that downloads when it is chosen. */
  function cassandraModule(state = 'available'): void {
    const module = { id: 'cassandra', version: '1.0.0', state, step: null, sizeBytes: 9_871_234, installed: false, message: null };
    core.catalog = { groups: [{ name: 'Databases', cards: [{ ...structuredClone(CATALOG.groups[0].cards[0]),
      type: 'cassandra', name: 'Cassandra', kind: 'built-in', version: '1.0.0', tools: 3, connections: 0, module,
    }] }], installed: [] };
    core.spec = { ...structuredClone(SPEC), type: 'cassandra', name: 'Cassandra' };
  }

  it('a type of a connector module downloads on Continue with no question, and says which step it is at', async () => {
    cassandraModule();
    let arrive!: (spec: ConnectionFormSpec) => void;
    core.pendingForm = new Promise((done) => { arrive = done; });
    const p = await open();
    const card = p.q<HTMLInputElement>('#step-1 input[value="cassandra"]')!;
    expect(text(card.closest('.gw-choice-card'))).toContain('Built-in · 3 tools · 9.4 MB download');
    card.click();
    await p.settle();
    p.button('Continue').click();
    await p.settle();

    expect(document.querySelector('dialog.gw-dialog[open]'), 'nothing to review or confirm').toBeNull();
    expect(p.visible()).toEqual(['step-2']);
    expect(core.formTypes.at(-1)).toBe('cassandra');
    const notice = p.q('#module-download')!;
    expect(notice.getAttribute('role')).toBe('status');
    expect(text(notice)).toBe('Getting Cassandra ready Downloading Cassandra (9.4 MB). This happens once.');

    TestBed.inject(EventStream).emit('module.state', { id: 'other', version: '1.0.0', step: 'installing', sizeBytes: 1 });
    TestBed.inject(EventStream).emit('module.state', { id: 'cassandra', version: '1.0.0', step: 'verifying', sizeBytes: 9_871_234 });
    await p.settle();
    expect(text(p.q('#module-download'))).toBe('Getting Cassandra ready Checking the download of Cassandra.');
    TestBed.inject(EventStream).emit('module.state', { id: 'cassandra', version: '1.0.0', step: 'runtime', sizeBytes: 9_871_234 });
    await p.settle();
    expect(text(p.q('#module-download'))).toContain('Preparing the Java runtime that Cassandra runs on. This happens once.');

    core.pendingForm = null;
    arrive(structuredClone(core.spec));
    await p.settle();
    expect(p.q('#module-download')).toBeNull();
    expect(text(p.q('#step-2-title'))).toBe('Configure Cassandra');
    expect(document.getElementById('cf-connection-host')).not.toBeNull();
  });

  it('a connector module that doesn’t arrive says why in the core’s words, and Try again asks for it again', async () => {
    cassandraModule();
    core.pendingForm = Promise.reject(new ApiError('not-found', 404, { title: 'Connector not found', detail: 'There is no connector type cassandra.' }));
    core.pendingForm.catch(() => undefined);
    core.modules = { ...core.modules, modules: [{ id: 'cassandra', name: 'Cassandra', version: '1.0.0', state: 'failed', step: null,
      sizeBytes: 9_871_234, message: 'Cassandra can’t be downloaded from github.com: no route to host', types: [], installed: false }] };
    const p = await open();
    p.q<HTMLInputElement>('#step-1 input[value="cassandra"]')!.click();
    await p.settle();
    p.button('Continue').click();
    await p.settle();

    const alert = p.q('#step-2 .gw-alert--danger')!;
    expect(text(alert.querySelector('.gw-alert__title'))).toBe("Cassandra didn't download");
    expect(text(alert.querySelector('.gw-alert__body'))).toBe('Cassandra can’t be downloaded from github.com: no route to host');
    expect(p.q('#module-download')).toBeNull();

    core.pendingForm = null;
    p.button('Try again', alert).click();
    await p.settle();
    expect(core.formTypes.filter((t) => t === 'cassandra')).toHaveLength(2);
    expect(p.q('#step-2 .gw-alert--danger')).toBeNull();
    expect(text(p.q('#step-2-title'))).toBe('Configure Cassandra');
  });

  it('Continue opens Configure: the form from the schema, focus on the step title, "Step 2 of 4: Configure"', async () => {
    const announce = vi.spyOn(TestBed.inject(Announcer), 'announce');
    const p = await open();
    p.button('Continue').click();
    await p.settle();
    expect(p.visible()).toEqual(['step-2']);
    expect(text(p.q('#step-2-title'))).toBe('Configure PostgreSQL');
    expect(document.activeElement?.id).toBe('step-2-title');
    expect(announce).toHaveBeenCalledWith('Step 2 of 4: Configure');
    expect(p.steps()[0]).toBe('complete:Choose, completed PostgreSQL');
    expect(document.getElementById('cf-connection-host')).not.toBeNull();
  });

  it('stays on Configure until the form is valid, with the name error under Name', async () => {
    const p = await open();
    p.button('Continue').click();
    await p.settle();
    p.button('Continue', p.q('#cf-form')!).click();
    await p.settle();
    expect(p.visible()).toEqual(['step-2']);
    expect(text(p.q('#cf-name-err'))).toBe('Name the connection, for example dev-db.');
    expect(core.tests).toEqual([]);
  });

  it('sends what was typed to the test once, shows the result and the copyable fix', async () => {
    const p = await open();
    await toTest(p);
    expect(p.visible()).toEqual(['step-3']);
    expect(document.activeElement?.id).toBe('step-3-title');
    // The typed text values reach the config (Signal Forms reads a null-bound text input as a number field).
    expect(core.tests).toEqual([
      {
        type: 'postgres',
        env: 'dev',
        config: {
          connection: { host: 'db.dev.internal', port: 5432, databases: ['app'], sslMode: 'auto' },
          credentials: { user: 'app_rw' },
          advanced: { poolSize: 2, defaultSchema: 'public', connectTimeoutMs: 5000, discoveryDatabase: 'postgres' },
        },
        secrets: { '/credentials/password': { source: 'local', value: 's3cret-value' } },
      },
    ]);
    expect(text(p.q('#step-3 .gw-card__description'))).toBe('app_rw at db.dev.internal:5432/app environment dev');
    expect(text(p.q('#step-3 .gw-status'))).toBe('Connected · PostgreSQL 16.4 · 12 ms');
    expect(text(p.q('#step-3 .gw-alert--warning .gw-alert__title'))).toBe('This user can write');
    expect(text(p.q('#step-3 .gw-connection-form__toggle'))).toBe('Create a read-only user');
    expect(text(p.q('#ct-readonly .gw-code-block__filename'))).toBe('Run as an admin on app, then use gw_reader as the user');
    expect(p.steps()[2]).toBe('current:3 Test');
    // Back and Continue again: same values, no second test.
    p.button('Back', p.q('#step-3')!).click();
    await p.settle();
    p.button('Continue', p.q('#cf-form')!).click();
    await p.settle();
    expect(core.tests).toHaveLength(1);
    // A changed value is tested again.
    p.button('Back', p.q('#step-3')!).click();
    await p.settle();
    await p.type('cf-connection-host', 'db2.dev.internal');
    p.button('Load databases').click();
    await p.settle();
    p.button('Continue', p.q('#cf-form')!).click();
    await p.settle();
    expect(core.tests.map((t) => (t.config['connection'] as JsonObject)['host'])).toEqual(['db.dev.internal', 'db2.dev.internal']);
  });

  it('marks Test failed in the stepper and still lets the person continue', async () => {
    core.nextTest = outcome({ error: { code: 'unreachable', message: 'The connection timed out after 5 s.', technical: 'SocketTimeoutException' } });
    const p = await open();
    await toTest(p);
    expect(text(p.q('#step-3 .gw-alert--danger .gw-alert__title'))).toBe("Can't reach db.dev.internal:5432");
    // The failed step's marker is an x (stepper): no number, "failed" for screen readers.
    expect(p.steps()[2]).toBe("error:Test, failed Can't reach db.dev.internal:5432");
    p.button('Continue', p.q('#step-3')!).click();
    await p.settle();
    expect(p.visible()).toEqual(['step-4']);
    expect(p.steps()[2]).toContain('error:');
  });

  it('a host key: Trust confirms the draft, merges what the core patched and shows the retest', async () => {
    core.nextTest = HOST_KEY;
    const p = await open();
    await toTest(p);
    expect(text(p.q('#step-3 .gw-alert--warning .gw-alert__title'))).toBe('Check the host key of db.dev.internal:22');
    p.button('Trust this key and test again', p.q('#step-3')!).click();
    await p.settle();
    expect(TestBed.inject(ConnectionsApi).confirmDraft).toHaveBeenCalledWith('cf-1');
    expect(text(p.q('#step-3 .gw-status'))).toBe('Connected · PostgreSQL 16.4 · 12 ms');
    expect((p.page().form()!.value().config['connection'] as JsonObject)['hostKey']).toBe('ssh-ed25519 AAAA');
  });

  it('a host key on an unsaved draft: Trust pins the key, then the wizard tests the patched draft itself', async () => {
    core.nextTest = HOST_KEY;
    const p = await open();
    await toTest(p);
    // The core keeps no credentials of an unsaved draft, so it returns the patched config without a retest.
    core.pendingConfirm = Promise.resolve({ kind: 'host-key', config: { connection: { hostKey: 'ssh-ed25519 AAAA' } }, retest: null });
    core.nextTest = OK;
    const tests = core.tests.length;
    p.button('Trust this key and test again', p.q('#step-3')!).click();
    await p.settle();
    expect(TestBed.inject(ConnectionsApi).confirmDraft).toHaveBeenCalledWith('cf-1');
    expect(core.tests).toHaveLength(tests + 1);
    expect((core.tests.at(-1)!.config['connection'] as JsonObject)['hostKey']).toBe('ssh-ed25519 AAAA');
    expect(text(p.q('#step-3 .gw-status'))).toBe('Connected · PostgreSQL 16.4 · 12 ms');
  });

  it('a refused draft (422) is a failed test whose field shows the error back in Configure', async () => {
    core.nextTest = new ApiError('unprocessable', 422, { title: "Can't test it", detail: 'Check the host.', errors: [{ field: '/config/connection/host', message: 'Use a host name or an IP address.' }] });
    const p = await open();
    await toTest(p);
    expect(text(p.q('#step-3 .gw-alert--danger'))).toContain("Can't test it. Check the host.");
    p.button('Back', p.q('#step-3')!).click();
    await p.settle();
    expect(text(p.q('#cf-connection-host-err'))).toBe('Use a host name or an IP address.');
  });

  it('Change user goes back to Configure with focus on User', async () => {
    const p = await open();
    await toTest(p);
    p.button('Change user', p.q('#step-3')!).click();
    await p.settle();
    expect(p.visible()).toEqual(['step-2']);
    expect(document.activeElement?.id).toBe('cf-credentials-user');
  });

  it('Add to endpoint: dev ticked, refused endpoints stay visible with their reason, the preview follows', async () => {
    const p = await open();
    await toTest(p);
    p.button('Continue', p.q('#step-3')!).click();
    await p.settle();
    const boxes = Array.from(p.el.querySelectorAll<HTMLInputElement>('#step-4 input[type=checkbox]'));
    expect(boxes.map((b) => [b.value, b.checked, b.getAttribute('aria-disabled')])).toEqual([
      ['dev', true, null],
      ['stage-readonly', false, null],
      ['incident-prod', false, 'true'],
    ]);
    expect(text(p.q('#ep-incident-prod-d'))).toBe('Accepts prod connections only; qa-db is dev.');
    expect(text(p.q('#ep-dev-m'))).toBe('Claude Desktop, Codex');
    expect(text(p.q('#pv-dev-t'))).toBe('qa-db environment dev on endpoint dev');
    expect(text(p.q('#step-4 .gw-tool-policy-list .gw-card__description'))).toBe('3 tools · 2 read only · 1 approval');
    // A refused card never becomes a choice; the read-only one adds its own preview with the lock.
    boxes[2].click();
    boxes[1].click();
    await p.settle();
    expect(boxes[2].checked).toBe(false);
    expect(Array.from(p.el.querySelectorAll('#step-4 .gw-tool-policy-list h4')).map(text)).toEqual([
      'qa-db environment dev on endpoint dev',
      'qa-db environment dev on endpoint stage-readonly',
    ]);
    expect(text(p.el.querySelectorAll('#step-4 .gw-tool-policy-list .gw-card__description')[1])).toBe('3 tools · 2 read only · 1 off, locked by the read-only endpoint');
    // Nothing chosen: the empty state.
    boxes[0].click();
    boxes[1].click();
    await p.settle();
    expect(text(p.q('#step-4 .gw-empty__title'))).toBe('Not on an endpoint yet');
    expect(p.steps()[3]).toBe('current:4 Add to endpoint');
    expect(text(p.q('#step-4 .gw-form__note'))).toBe('The password is encrypted with your master key on save');
  });

  it('waits for endpoint loading before saving so default cannot be missed', async () => {
    let resolve!: (rows: EndpointSummary[]) => void;
    vi.mocked(TestBed.inject(EndpointsApi).list).mockImplementation(() => new Promise((done) => { resolve = done; }));
    const p = await open();
    await toTest(p);
    p.button('Continue', p.q('#step-3')!).click();
    await p.settle();
    expect(p.button('Save connection').getAttribute('aria-disabled')).toBe('true');
    p.q('#step-4 form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await p.settle();
    expect(core.created).toEqual([]);
    resolve([endpoint({ slug: 'default', connections: { total: 0, byEnv: {} }, clients: [] })]);
    await p.settle();
    expect(p.button('Save connection').getAttribute('aria-disabled')).toBeNull();
    p.button('Save connection').click();
    await p.settle();
    expect(core.added).toEqual(['default']);
  });

  it('selects the default endpoint and attaches the saved connection to it', async () => {
    vi.mocked(TestBed.inject(EndpointsApi).list).mockResolvedValue([
      endpoint({ slug: 'default', connections: { total: 0, byEnv: {} }, clients: [] }),
      ...ENDPOINTS,
    ]);
    const p = await open();
    await toTest(p);
    p.button('Continue', p.q('#step-3')!).click();
    await p.settle();
    const checked = [...p.el.querySelectorAll<HTMLInputElement>('#step-4 input[type=checkbox]:checked')].map((b) => b.value);
    expect(checked).toEqual(['default']);
    p.button('Save connection').click();
    await p.settle();
    expect(core.added).toEqual(['default']);
  });

  it('selects default when the initial setup lookup finishes while step 4 is loading', async () => {
    const pending: ((rows: EndpointSummary[]) => void)[] = [];
    vi.mocked(TestBed.inject(EndpointsApi).list).mockImplementation(() => new Promise((resolve) => { pending.push(resolve); }));
    const p = await open();
    await toTest(p);
    p.button('Continue', p.q('#step-3')!).click();
    await p.settle();
    expect(pending).toHaveLength(2);
    pending[0]([endpoint({ slug: 'default', connections: { total: 0, byEnv: {} }, clients: [] })]);
    await p.settle();
    expect(p.button('Save connection').getAttribute('aria-disabled')).toBeNull();
    expect(p.q<HTMLInputElement>('#step-4 input[value="default"]')?.checked).toBe(true);
    p.button('Save connection').click();
    await p.settle();
    expect(core.added).toEqual(['default']);
  });

  it('Save connection creates it, adds it where the core agrees, then opens its own view with a toast', async () => {
    core.previews.set('stage-readonly', { connection: 'qa-db', env: 'dev', tools: [], warnings: [], refusal: 'stage-readonly changed meanwhile.' });
    const p = await open();
    await toTest(p);
    p.button('Continue', p.q('#step-3')!).click();
    await p.settle();
    p.el.querySelector<HTMLInputElement>('#step-4 input[value=stage-readonly]')!.click();
    await p.settle();
    p.button('Save connection').click();
    await p.settle();
    expect(core.created).toEqual([
      {
        name: 'qa-db',
        type: 'postgres',
        env: 'dev',
        config: core.tests[0].config,
        secrets: { '/credentials/password': { source: 'local', value: 's3cret-value' } },
      },
    ]);
    expect(core.added).toEqual(['dev']);
    expect(TestBed.inject(Router).url).toBe('/connections/qa-db');
    expect(TestBed.inject(ToastService).toasts().map((t) => [t.tone, t.title, t.body])).toEqual([
      ['warning', 'Connection qa-db saved, not added to stage-readonly', 'Added to dev. stage-readonly changed meanwhile.'],
    ]);
  });

  it('a prod user’s rights (409 confirm: danger) ask in a danger dialog; Save anyway sends the confirmation', async () => {
    core.createFails = new ApiError('conflict', 409, { title: 'Keep these rights on prod?', detail: 'qa-db: The user is a superuser.', confirm: 'danger' });
    const p = await open();
    await toTest(p);
    p.button('Continue', p.q('#step-3')!).click();
    await p.settle();
    p.button('Save connection').click();
    await p.settle();
    const dialog = document.getElementById('dlg-prod-rights') as HTMLDialogElement;
    expect(dialog.open).toBe(true);
    expect(text(dialog.querySelector('h2'))).toBe('Keep these rights on prod?');
    expect(text(document.activeElement)).toBe('Cancel');
    core.createFails = null;
    p.button('Save anyway', dialog).click();
    await p.settle();
    expect(core.created.map((c) => [c.name, c.confirm])).toEqual([['qa-db', 'danger']]);
    expect(TestBed.inject(Router).url).toBe('/connections/qa-db');
  });

  it('a taken name (409) goes back to Configure with the core’s words under Name and focus there', async () => {
    core.createFails = new ApiError('conflict', 409, { title: 'Name taken', detail: 'A connection named qa-db exists. Pick another name.' });
    const p = await open();
    await toTest(p);
    p.button('Continue', p.q('#step-3')!).click();
    await p.settle();
    p.button('Save connection').click();
    await p.settle();
    expect(p.visible()).toEqual(['step-2']);
    expect(text(p.q('#cf-name-err'))).toBe('A connection named qa-db exists. Pick another name.');
    expect(document.activeElement?.id).toBe('cf-name');
  });

  it('a rejected name takes focus before schema fields while root errors stay visible', async () => {
    core.createFails = new ApiError('unprocessable', 422, {
      title: 'Invalid connection', errors: [
        { field: '/config/connection/host', message: 'Use an allowed host.' },
        { field: '/name', message: 'Choose another connection name.' },
        { field: '/config', message: 'This combination is not allowed.' },
      ],
    });
    const p = await open();
    await toTest(p);
    p.button('Continue', p.q('#step-3')!).click();
    await p.settle();
    p.button('Save connection').click();
    await p.settle();
    expect(text(p.q('#cf-name-err'))).toBe('Choose another connection name.');
    expect(text(p.q('#cf-connection-host-err'))).toBe('Use an allowed host.');
    expect(text(p.q('#connection-form-error'))).toContain('This combination is not allowed.');
    expect(document.activeElement?.id).toBe('cf-name');
  });

  it('shows prod’s stricter defaults read from the policies', async () => {
    const p = await open();
    p.button('Continue').click();
    await p.settle();
    p.el.querySelector<HTMLInputElement>('input[name="cf-env"][value="prod"]')!.click();
    await p.settle();
    expect(Array.from(p.el.querySelectorAll('.gw-connection-form__defaults li')).map(text)).toEqual([
      'Read-only transactions',
      'Row limit 100 (dev: 1,000)',
      'Statement timeout 3 s (dev: 5 s)',
      'No DDL: CREATE, ALTER, DROP, TRUNCATE are refused',
      'Approval timeout 10 min, a comment is required to approve',
    ]);
  });

  it('opens a deep-linked step without moving focus (#step-2)', async () => {
    const p = await open('/connections/new#step-2');
    expect(p.visible()).toEqual(['step-2']);
    expect(document.activeElement).toBe(document.body);
  });

  it('says when the catalog didn’t load and tries again', async () => {
    core.catalog = new ApiError('network', 0, {});
    const p = await open();
    expect(text(p.q('#step-1 .gw-alert__title'))).toBe("The connector types didn't load");
    core.catalog = structuredClone(CATALOG);
    p.button('Try again').click();
    await p.settle();
    const base = Array.from(p.el.querySelectorAll<HTMLInputElement>('#type-picker input[type=radio]')).filter((r) => !r.value.includes(':'));
    expect(base.map((r) => r.value)).toEqual(['postgres', 'clickhouse', 'mcp-server']);
  });
});

describe('Add connection: duplicate', () => {
  it('starts at Configure with the copied settings, an empty secret and the info alert', async () => {
    const p = await open('/connections/new?duplicate=dev-db');
    expect(p.visible()).toEqual(['step-2']);
    expect(text(p.q('h1'))).toBe('Duplicate dev-db');
    expect(p.q('gw-access-switch')).toBeNull();
    expect(text(p.q('#cf-form .gw-alert--info'))).toBe(
      'Copied from dev-db: where it is, the environment and the advanced settings. The credentials are not: enter a password or reference a secret for the copy.',
    );
    expect((document.getElementById('cf-name') as HTMLInputElement).value).toBe('dev-db-copy');
    expect((document.getElementById('cf-connection-host') as HTMLInputElement).value).toBe('db.dev.internal');
    expect((document.getElementById('cf-credentials-password') as HTMLInputElement).value).toBe('');
    expect(p.steps()[0]).toBe('complete:Choose, completed PostgreSQL');
  });
});

describe('Connection: edit view with references', () => {
  const VAULTS = [
    { id: 'local', label: 'Encrypted here', available: true, reason: null },
    { id: 'vault', label: 'HashiCorp Vault', available: true, reason: null, store: 'vault-dev', detail: 'https://vault.dev.example.test' },
    { id: 'vault', label: 'HashiCorp Vault', available: true, reason: null, store: 'vault-stage', detail: 'https://vault.stage.example.test' },
  ];

  /** A Redis connection whose password is a reference into vault-dev (no database discovery to redo). */
  function cacheWithReference(env: 'dev' | 'prod' = 'dev') {
    core.spec = { ...structuredClone(SPEC), type: 'redis', name: 'Redis', schema: CONNECTOR_FORMS['redis'], secretSources: VAULTS };
    core.detail = {
      ...structuredClone(DETAIL), name: 'cache', type: 'redis', typeName: 'Redis', env,
      config: { connection: { host: 'cache.dev.internal', port: 6379 } },
      secrets: [{ field: '/credentials/password', secretId: 'sec-1', name: 'cache/password', state: 'reference', version: null, source: 'vault' }],
    } as ConnectionDetail;
    vi.mocked(TestBed.inject(SecretsApi).list).mockResolvedValue([
      { id: 'sec-1', ref: 'vault://kv/dev/cache#password', store: 'vault-dev', canRotate: false } as SecretRow,
    ]);
  }

  /** Picks where a field's value comes from in its menu (Text, a store), as the person does. */
  async function pick(p: Awaited<ReturnType<typeof open>>, field: string, value: string) {
    p.q<HTMLButtonElement>(`#cf-${field}-src`)!.click();
    await p.settle();
    p.q<HTMLElement>(`#cf-${field}-src-menu [data-value="${value}"]`)!.click();
    await p.settle();
  }

  it.each([
    { reason: 'not-found', error: 'No secret at kv/dev/cache.', title: 'Reference of cache not found' },
    { reason: 'not-found', error: 'Key "password" was not found in this secret. Check the key after # in the reference.', title: 'Reference of cache not found' },
    { reason: 'denied', error: "The gateway's token can't read kv/data/dev/cache.", title: 'Access to reference of cache denied' },
    { reason: 'unavailable', error: 'The Vault token has expired. Sign in again.', title: 'Secret store unavailable' },
    { reason: 'unavailable', error: 'Vault could not be reached. Check network access.', title: 'Secret store unavailable' },
    { reason: 'gone', error: 'Version 3 of kv/dev/cache is destroyed.', title: 'Reference of cache is no longer available' },
    { reason: 'invalid', error: 'Vault cannot use this reference with the configured engine.', title: 'Reference of cache is invalid' },
    { reason: 'not-connected', error: "HashiCorp Vault isn't connected: connect it in Secrets › External stores.", title: 'Secret store is not connected' },
  ])('reference test shows the $reason diagnostic: $error', async ({ reason, error, title }) => {
    cacheWithReference();
    vi.mocked(TestBed.inject(SecretsApi).testReference).mockResolvedValue({
      found: false, resolvedVersion: null, created: null, type: null, keys: [], reason, error,
    });
    const p = await open('/connections/cache');
    p.q<HTMLButtonElement>('button[aria-label^="Test reference"]')!.click();
    await p.settle();
    expect(TestBed.inject(SecretsApi).testReference).toHaveBeenCalledExactlyOnceWith('vault://kv/dev/cache#password', 'vault-dev');
    expect(TestBed.inject(ToastService).toasts().at(-1)).toMatchObject({ tone: 'danger', title, body: error });
    expect(core.updates).toEqual([]);
    expect(core.references).toEqual([]);
  });

  it('reference test omits a large sibling-key list returned by an older core', async () => {
    cacheWithReference();
    const keys = Array.from({ length: 1000 }, (_, i) => `service-${i}.credential`).join(', ');
    vi.mocked(TestBed.inject(SecretsApi).testReference).mockResolvedValue({
      found: false, resolvedVersion: null, created: null, type: null, keys: [], reason: 'not-found',
      error: `kv/dev/cache has no key "password". Keys: ${keys}`,
    });
    const p = await open('/connections/cache');
    p.q<HTMLButtonElement>('button[aria-label^="Test reference"]')!.click();
    await p.settle();
    expect(TestBed.inject(ToastService).toasts().at(-1)).toMatchObject({
      tone: 'danger', title: 'Reference of cache not found',
      body: 'kv/dev/cache has no key "password". Check the key after # in the reference.',
    });
    expect(core.updates).toEqual([]);
  });

  it('reference test keeps an oversized permission error actionable and compact', async () => {
    cacheWithReference();
    vi.mocked(TestBed.inject(SecretsApi).testReference).mockResolvedValue({
      found: false, resolvedVersion: null, created: null, type: null, keys: [], reason: 'denied',
      error: `The gateway's token cannot read ${'long/path/'.repeat(500)}.`,
    });
    const p = await open('/connections/cache');
    p.q<HTMLButtonElement>('button[aria-label^="Test reference"]')!.click();
    await p.settle();
    expect(TestBed.inject(ToastService).toasts().at(-1)).toMatchObject({
      tone: 'danger', title: 'Access to reference of cache denied',
      body: 'Check the selected store identity and its permission to read this secret.',
    });
  });

  it.each([
    { reason: 'denied', error: null, title: 'Access to reference of cache denied', body: 'permission to read this secret' },
    { reason: 'unavailable', error: ' ', title: 'Secret store unavailable', body: 'network access and sign-in' },
    { reason: null, error: null, title: "Couldn't test the reference of cache", body: 'Check the selected store connection' },
    { reason: 'future-reason', error: 'The reference test did not finish.', title: "Couldn't test the reference of cache", body: 'The reference test did not finish.' },
  ])('reference test handles absent or unknown diagnostics without claiming a missing path ($reason)', async ({ reason, error, title, body }) => {
    cacheWithReference();
    vi.mocked(TestBed.inject(SecretsApi).testReference).mockResolvedValue({
      found: false, resolvedVersion: null, created: null, type: null, keys: [], reason, error,
    });
    const p = await open('/connections/cache');
    p.q<HTMLButtonElement>('button[aria-label^="Test reference"]')!.click();
    await p.settle();
    const toast = TestBed.inject(ToastService).toasts().at(-1)!;
    expect(toast.title).toBe(title);
    expect(toast.body).toContain(body);
    expect(toast.title).not.toContain('not found');
  });

  it('reference test success shows version metadata without adding secret details', async () => {
    cacheWithReference();
    const p = await open('/connections/cache');
    p.q<HTMLButtonElement>('button[aria-label^="Test reference"]')!.click();
    await p.settle();
    expect(TestBed.inject(ToastService).toasts().at(-1)).toMatchObject({
      tone: 'success', title: 'Reference of cache works', body: 'Found version 4. The value was neither shown nor kept.',
    });
    expect(core.references).toEqual([]);
    expect(core.replaced).toEqual([]);
  });

  it('Change opens the stored reference in its Vault and saves the new one after the settings', async () => {
    cacheWithReference();
    const p = await open('/connections/cache');
    expect(p.q('input[name="cf-src"]')).toBeNull();
    p.q<HTMLButtonElement>('button[aria-label^="Change reference"]')!.click();
    await p.settle();
    expect(p.q<HTMLInputElement>('#cf-credentials-password')!.value).toBe('vault://kv/dev/cache#password');
    expect(text(p.q('#cf-credentials-password-src'))).toBe('vault-dev');
    await p.type('cf-credentials-password', 'vault://kv/dev/cache-v2#password');
    p.button('Save changes').click();
    await p.settle();
    expect(core.updates).toHaveLength(1);
    expect(core.updates[0]).not.toHaveProperty('secrets');
    expect(core.references).toEqual([{ id: 'sec-1', uri: 'vault://kv/dev/cache-v2#password', store: 'vault-dev', reason: null }]);
  });

  it('tests an edited stored reference without saving its new target', async () => {
    cacheWithReference();
    const p = await open('/connections/cache');
    p.q<HTMLButtonElement>('button[aria-label^="Change reference"]')!.click();
    await p.settle();
    await p.type('cf-credentials-password', 'vault://kv/dev/cache-v2#password');
    p.q<HTMLButtonElement>('#edit-test')!.click();
    await p.settle();
    expect(core.tests).toHaveLength(1);
    expect(core.tests[0]).toMatchObject({ existing: 'cache', secrets: {
      '/credentials/password': { source: 'reference', ref: 'vault://kv/dev/cache-v2#password', store: 'vault-dev' },
    } });
    expect(core.references).toEqual([]);
    expect(core.updates).toEqual([]);
    expect(TestBed.inject(ConnectionsApi).test).not.toHaveBeenCalled();
  });

  it("picking another Vault in the field's menu moves the stored reference there, with the same path", async () => {
    cacheWithReference();
    const p = await open('/connections/cache');
    p.q<HTMLButtonElement>('button[aria-label^="Change reference"]')!.click();
    await p.settle();
    await pick(p, 'credentials-password', 'vault/vault-stage');
    expect(p.q<HTMLInputElement>('#cf-credentials-password')!.value).toBe('vault://kv/dev/cache#password');
    p.button('Save changes').click();
    await p.settle();
    expect(core.updates).toHaveLength(1);
    expect(core.references).toEqual([{ id: 'sec-1', uri: 'vault://kv/dev/cache#password', store: 'vault-stage', reason: null }]);
  });

  it('saving without a change of reference sends none', async () => {
    cacheWithReference();
    const p = await open('/connections/cache');
    await p.type('cf-connection-host', 'cache2.dev.internal');
    p.button('Save changes').click();
    await p.settle();
    expect(core.updates).toHaveLength(1);
    expect(core.references).toEqual([]);
  });

  it('on prod the changed reference waits for a comment, which goes with it', async () => {
    cacheWithReference('prod');
    const p = await open('/connections/cache');
    p.q<HTMLButtonElement>('button[aria-label^="Change reference"]')!.click();
    await p.settle();
    await p.type('cf-credentials-password', 'vault://kv/prod/cache#password');
    p.button('Save changes').click();
    await p.settle();
    const dialog = document.getElementById('dlg-comment') as HTMLDialogElement;
    expect(dialog.open).toBe(true);
    expect(text(dialog.querySelector('#dlg-comment-t'))).toBe('Save the new password reference of cache?');
    expect(core.updates).toEqual([]);
    const area = dialog.querySelector<HTMLTextAreaElement>('#dlg-comment-text')!;
    area.value = 'moved to the prod path';
    area.dispatchEvent(new Event('input', { bubbles: true }));
    dialog.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await p.settle();
    expect(core.updates).toHaveLength(1);
    expect(core.references).toEqual([{ id: 'sec-1', uri: 'vault://kv/prod/cache#password', store: 'vault-dev', reason: 'moved to the prod path' }]);
  });

  it('a reference the core refuses leaves the saved settings and says why', async () => {
    cacheWithReference();
    core.referenceFails = [new ApiError('unprocessable', 422, { title: 'Check the reference', detail: 'kv/missing is not a KV path.' })];
    const p = await open('/connections/cache');
    p.q<HTMLButtonElement>('button[aria-label^="Change reference"]')!.click();
    await p.settle();
    await p.type('cf-credentials-password', 'vault://kv/missing#password');
    p.button('Save changes').click();
    await p.settle();
    expect(core.updates).toHaveLength(1);
    const toasts = TestBed.inject(ToastService).toasts();
    expect(toasts.at(-1)).toMatchObject({ tone: 'danger', title: "The password reference of cache didn't change" });
    expect(toasts.at(-1)!.body).toContain('kv/missing is not a KV path.');
  });

  it('a stored reference stays in its store: Text in its menu says why instead of being picked', async () => {
    cacheWithReference();
    const p = await open('/connections/cache');
    p.q<HTMLButtonElement>('button[aria-label^="Change reference"]')!.click();
    await p.settle();
    const option = p.q<HTMLElement>('#cf-credentials-password-src-menu [data-value="text"]')!;
    expect(option.getAttribute('aria-disabled')).toBe('true');
    expect(text(option)).toContain('A secret read from a store stays there: change its reference instead.');
    await pick(p, 'credentials-password', 'text');
    expect(p.q<HTMLInputElement>('#cf-credentials-password')!.value).toBe('vault://kv/dev/cache#password');
  });

  it('a user typed in place of a stored reference goes with the settings, and a new reference with them too', async () => {
    cacheWithReference();
    const detail = core.detail as ConnectionDetail;
    core.detail = { ...detail, secrets: [...detail.secrets,
      { field: '/credentials/user', secretId: 'sec-2', name: 'cache/user', state: 'reference', version: null, source: 'vault' }] } as ConnectionDetail;
    vi.mocked(TestBed.inject(SecretsApi).list).mockResolvedValue([
      { id: 'sec-1', ref: 'vault://kv/dev/cache#password', store: 'vault-dev', canRotate: false } as SecretRow,
      { id: 'sec-2', ref: 'vault://kv/dev/cache#username', store: 'vault-dev', canRotate: false } as SecretRow,
    ]);
    const p = await open('/connections/cache');
    p.q<HTMLButtonElement>('button[aria-label^="Change ACL user reference"]')!.click();
    await p.settle();
    await pick(p, 'credentials-user', 'text');
    await p.type('cf-credentials-user', 'app');
    p.button('Save changes').click();
    await p.settle();
    expect(core.updates).toHaveLength(1);
    expect(core.updates[0]).toMatchObject({ config: { credentials: { user: 'app' } } });
    expect(core.updates[0]).not.toHaveProperty('secrets');
    expect(core.references).toEqual([]);
  });

  it('a value typed where a reference is stored is refused instead of dropped', async () => {
    cacheWithReference();
    const p = await open('/connections/cache');
    const page = p.page() as unknown as { saveEdit(value: unknown): Promise<void> };
    await page.saveEdit({ name: 'cache', env: 'dev', config: { connection: { host: 'cache.dev.internal', port: 6379 } },
      secrets: { '/credentials/password': { source: 'local', value: 'typed-here' } } });
    await p.settle();
    expect(text(p.q('#connection-form-error'))).toContain("can't be turned into a value stored here");
    expect(core.updates).toEqual([]);
    expect(core.references).toEqual([]);
  });
});

describe('Connection: edit view', () => {
  it('keeps the shared Save changes bar visible on Tools and submits the mounted settings draft', async () => {
    const p = await open('/connections/dev-db');
    const form = p.q<HTMLFormElement>('#cf-form')!;
    const actions = p.q('#edit-connection-actions')!;
    const save = p.button('Save changes');
    expect(save.form).toBe(form);
    expect(actions.parentElement).toBe(p.el);
    expect(p.el.classList.contains('gw-form--sticky-actions')).toBe(true);
    await p.type('cf-credentials-user', 'draft_user');
    p.button('Load databases').click();
    await p.settle();
    p.q<HTMLButtonElement>('#connection-tools-tab')!.click();
    await p.settle();

    expect(p.q<HTMLElement>('#connection-settings')!.hidden).toBe(true);
    expect(actions.closest('[hidden]')).toBeNull();
    expect(p.q('#cf-form')).toBe(form);
    expect(p.q<HTMLInputElement>('#cf-credentials-user')!.value).toBe('draft_user');
    save.click();
    await p.settle();
    expect(core.updates).toHaveLength(1);
    expect(core.updates[0].config).toMatchObject({ credentials: { user: 'draft_user' } });
    expect(core.updates[0].version).toBe(DETAIL.version);
  });

  it('discards the settings draft from the shared action bar while Tools is selected', async () => {
    const p = await open('/connections/dev-db');
    const user = p.q<HTMLInputElement>('#cf-credentials-user')!.value;
    await p.type('cf-credentials-user', 'draft_user');
    p.q<HTMLButtonElement>('#connection-tools-tab')!.click();
    await p.settle();
    const discard = p.button('Discard');
    expect(discard.closest('[hidden]')).toBeNull();
    discard.click();
    await p.settle();
    expect(p.q<HTMLInputElement>('#cf-credentials-user')!.value).toBe(user);
    expect(core.updates).toEqual([]);
    expect(p.q<HTMLElement>('#used-by')!.hidden).toBe(false);
  });

  it('changes a tool mode immediately without saving or replacing the connection draft', async () => {
    core.endpointTools = TOOLS.map((tool) => row(tool.name, tool.name === 'sql_execute' ? 'APPROVAL' : 'READ_ONLY'));
    vi.mocked(TestBed.inject(EndpointsApi).tools).mockImplementation(async () => [{
      group: 'dev-db', connection: 'dev-db', env: 'dev', tools: structuredClone(core.endpointTools),
    }]);
    const p = await open('/connections/dev-db');
    await p.type('cf-connection-host', 'unsaved.dev.internal');
    p.q<HTMLButtonElement>('button[aria-label="Read only: sql_query on dev"]')!.click();
    p.q<HTMLButtonElement>('[role="menu"][aria-label="Mode of sql_query on dev"] [data-value="OFF"]')!.click();
    await p.settle();
    expect(core.modes).toEqual([{ slug: 'dev', toolKey: 'sql_query', connection: 'dev-db', mode: 'OFF', version: 1 }]);
    expect(core.updates).toEqual([]);
    expect(p.q<HTMLInputElement>('#cf-connection-host')!.value).toBe('unsaved.dev.internal');
    expect(text(p.q('.gw-stat:last-child'))).toContain('2 tools on dev');
  });

  it('keeps unmatched update errors beside the form until retry or Discard', async () => {
    core.updateFails = [new ApiError('unprocessable', 422, {
      title: 'Invalid connection', errors: [{ field: '/config', message: 'The selected settings cannot be used together.' }],
    })];
    const p = await open('/connections/dev-db');
    p.button('Save changes').click();
    await p.settle();
    const alert = p.q('#connection-form-error')!;
    expect(text(alert)).toContain('The selected settings cannot be used together.');
    expect(document.activeElement).toBe(alert);
    p.button('Discard').click();
    await p.settle();
    expect(p.q('#connection-form-error')).toBeNull();
  });

  it.each([
    ['/config/credentials/user', '#cf-credentials-user'],
    ['/config', '#connection-form-error'],
  ])('reveals Settings before focusing a delayed update error for %s', async (field, target) => {
    let reject!: (error: ApiError) => void;
    vi.mocked(TestBed.inject(ConnectionsApi).update).mockReturnValueOnce(new Promise((_resolve, fail) => { reject = fail; }));
    const p = await open('/connections/dev-db');
    p.button('Save changes').click();
    await p.settle();
    p.q<HTMLButtonElement>('#connection-tools-tab')!.click();
    await p.settle();
    expect(p.q<HTMLElement>('#connection-settings')!.hidden).toBe(true);

    reject(new ApiError('unprocessable', 422, { title: 'Invalid connection', errors: [{ field, message: 'Review this setting.' }] }));
    await p.settle();
    expect(p.q<HTMLElement>('#connection-settings')!.hidden).toBe(false);
    expect(p.q<HTMLElement>('#used-by')!.hidden).toBe(true);
    expect(p.q('#connection-settings-tab')!.getAttribute('aria-selected')).toBe('true');
    expect(document.activeElement).toBe(p.q(target));
    expect(text(p.q('#cf-form'))).toContain('Review this setting.');
  });

  it('reveals Settings before focusing validation when Save changes is clicked from Tools', async () => {
    const p = await open('/connections/dev-db');
    await p.type('cf-credentials-user', '');
    p.q<HTMLButtonElement>('#connection-tools-tab')!.click();
    await p.settle();
    expect(p.q<HTMLElement>('#connection-settings')!.hidden).toBe(true);
    p.button('Save changes').click();
    await p.settle();
    expect(p.q<HTMLElement>('#connection-settings')!.hidden).toBe(false);
    expect(p.q<HTMLElement>('#used-by')!.hidden).toBe(true);
    expect(document.activeElement).toBe(p.q('#cf-credentials-user'));
    expect(core.updates).toEqual([]);
  });

  it('keeps a saved database and requests stored-secret consent before discovering on a new host', async () => {
    core.nextDiscovery = new ApiError('conflict', 409, {
      title: 'Send the stored password to db2.internal?', detail: 'The password will be used to load databases.', confirm: 'send-secret-to-new-host',
    });
    const p = await open('/connections/dev-db');
    expect(p.q<HTMLInputElement>('#cf-database-0')!.checked).toBe(true);
    await p.type('cf-connection-host', 'db2.internal');
    p.button('Load databases').click();
    await p.settle();
    expect(core.discoveries[0].existing).toBe('dev-db');
    expect(core.discoveries[0].secrets).toEqual({});
    const dialog = document.getElementById('dlg-new-host') as HTMLDialogElement;
    expect(dialog.open).toBe(true);
    core.nextDiscovery = outcome({ ok: true, details: { databases: ['app', 'reports'] } });
    p.button('Send and load databases', dialog).click();
    await p.settle();
    expect(core.discoveries[1].confirm).toBe('send-secret-to-new-host');
    expect(p.q<HTMLInputElement>('#cf-database-0')!.checked).toBe(true);
    expect(core.updates).toEqual([]);
  });

  it('shows the incomplete import checklist and blocks enabling access until setup is completed', async () => {
    core.detail = { ...structuredClone(DETAIL), enabled: false, setupRequired: true,
      setupMissingFields: ['/connection/host', '/credentials/password'],
      setupSources: [{ field: '/credentials/password', source: 'reference', reference: 'vault://secret/example/dev#password', instructions: 'The deployment obtains DB_PASSWORD from this secret.' }] };
    const p = await open('/connections/dev-db');
    expect(text(p.q('#finish-setup-title'))).toContain('Finish setup for dev-db');
    expect(p.el.textContent).toContain('/connection/host');
    expect(p.el.textContent).toContain('vault://secret/example/dev#password');
    expect(p.el.textContent).toContain('DB_PASSWORD');
    expect(text(p.q('.gw-page-header__meta .gw-status'))).toBe('Needs setup');
    p.q<HTMLInputElement>('gw-access-switch input')!.click();
    await p.settle();
    expect(TestBed.inject(ConnectionsApi).setEnabled).not.toHaveBeenCalled();
  });

  it('includes newly entered missing credentials when finishing an imported connection', async () => {
    core.detail = { ...structuredClone(DETAIL), enabled: false, setupRequired: true,
      setupMissingFields: ['/credentials/password'], secrets: [] };
    const p = await open('/connections/dev-db');
    await p.type('cf-credentials-password', 'fixture-completion-password');
    p.button('Load databases').click();
    await p.settle();
    p.button('Save changes').click();
    await p.settle();
    expect(core.updates[0]?.secrets).toEqual({ '/credentials/password': { source: 'local', value: 'fixture-completion-password' } });
    expect(TestBed.inject(ConnectionsApi).setEnabled).not.toHaveBeenCalled();
  });

  it('restores unresolved imported login references as drafts and sends them only after the user saves', async () => {
    core.spec = { ...structuredClone(SPEC), secretSources: [
      ...SPEC.secretSources,
      { id: 'vault', label: 'Vault', store: 'vault-dev', available: true, reason: null },
    ] };
    core.detail = { ...structuredClone(DETAIL), enabled: false, setupRequired: true,
      config: { connection: structuredClone(DETAIL.config['connection']) }, secrets: [],
      setupMissingFields: ['/credentials/user', '/credentials/password'],
      setupSources: [
        { field: '/credentials/user', source: 'reference', reference: 'vault://kv/dev/example#user', store: 'vault-dev' },
        { field: '/credentials/password', source: 'reference', reference: 'vault://kv/dev/example#password', store: 'vault-dev' },
      ] };
    const p = await open('/connections/dev-db');
    expect(p.q<HTMLInputElement>('#cf-credentials-user')!.value).toBe('vault://kv/dev/example#user');
    expect(p.q<HTMLInputElement>('#cf-credentials-password')!.value).toBe('vault://kv/dev/example#password');
    expect(p.page().form()!.state().stored.size).toBe(0);
    expect(core.updates).toEqual([]);
    expect(core.tests).toEqual([]);
    expect(core.references).toEqual([]);
    p.button('Load databases').click();
    await p.settle();
    p.button('Save changes').click();
    await p.settle();
    expect(core.updates[0]?.secrets).toEqual({
      '/credentials/user': { source: 'reference', ref: 'vault://kv/dev/example#user', store: 'vault-dev' },
      '/credentials/password': { source: 'reference', ref: 'vault://kv/dev/example#password', store: 'vault-dev' },
    });
    expect(core.updates[0]?.config['credentials'] ?? {}).toEqual({});
    expect(TestBed.inject(ConnectionsApi).setEnabled).not.toHaveBeenCalled();
  });

  it('keeps an absent imported host empty and requires the missing reference store before finishing setup', async () => {
    core.detail = { ...structuredClone(DETAIL), enabled: false, setupRequired: true, config: {}, secrets: [],
      setupMissingFields: ['/connection/host', '/credentials/password'],
      setupSources: [
        { field: '/credentials/user', source: 'reference', reference: 'vault://kv/dev/example#user', store: 'vault-unavailable' },
        { field: '/credentials/password', source: 'reference', reference: 'vault://kv/dev/example#password', store: 'vault-unavailable' },
        { field: '/connection/host', source: 'reference', reference: 'vault://kv/dev/example#host' },
      ] };
    const p = await open('/connections/dev-db');
    expect(p.q<HTMLInputElement>('#cf-connection-host')!.value).toBe('');
    expect(text(p.q('#cf-credentials-password-src'))).toBe('vault-unavailable');
    expect(p.el.textContent).toContain('The reference names vault-unavailable, but no connected store matches this name.');
    p.button('Save changes').click();
    await p.settle();
    expect(core.updates).toEqual([]);
    expect(core.references).toEqual([]);
    expect(TestBed.inject(ConnectionsApi).setEnabled).not.toHaveBeenCalled();
  });

  it('does not send an unsaved existing credential replacement through setup completion', async () => {
    core.detail = { ...structuredClone(DETAIL), enabled: false, setupRequired: true,
      setupMissingFields: ['/connection/host'] };
    const p = await open('/connections/dev-db');
    const form = p.page().form()!;
    const node = form.state().compiled.all.find(field => field.pointer === '/credentials/password')!;
    form.state().replace(node, true);
    form.state().setSecret(node, 'fixture-replacement-password');
    await p.settle();
    p.button('Load databases').click();
    await p.settle();
    p.button('Save changes').click();
    await p.settle();
    expect(core.updates).toEqual([]);
    expect(text(p.q('#connection-form-error'))).toContain('Save credential replacements first');
  });

  it('changes access immediately without losing draft fields and saves with the refreshed version', async () => {
    const p = await open('/connections/dev-db');
    await p.type('cf-credentials-user', 'draft_user');
    p.button('Load databases').click();
    await p.settle();
    const access = p.q<HTMLInputElement>('gw-access-switch input')!;
    expect(access.checked).toBe(true);
    expect(access.closest('form')).toBeNull();
    access.click();
    await p.settle();
    expect(TestBed.inject(ConnectionsApi).setEnabled).toHaveBeenCalledWith('dev-db', false);
    expect(core.updates).toEqual([]);
    expect(p.q<HTMLInputElement>('#cf-credentials-user')!.value).toBe('draft_user');
    expect(text(p.q('.gw-page-header__meta .gw-status'))).toBe('Disabled by you');
    expect(text(p.q('.gw-alert--neutral'))).toContain('Access is off across all endpoints.');
    p.button('Save changes').click();
    await p.settle();
    expect(core.updates[0].version).toBe(4);
    expect(core.updates[0].config['credentials']).toMatchObject({ user: 'draft_user' });
    expect(p.q<HTMLInputElement>('gw-access-switch input')!.checked).toBe(false);
    p.q<HTMLInputElement>('gw-access-switch input')!.click();
    await p.settle();
    expect(p.q<HTMLInputElement>('gw-access-switch input')!.checked).toBe(true);
    expect(p.q('.gw-alert--neutral')).toBeNull();
  });

  it('keeps the draft and blocks Save until a failed access refresh succeeds', async () => {
    const p = await open('/connections/dev-db');
    await p.type('cf-credentials-user', 'draft_user');
    p.button('Load databases').click();
    await p.settle();
    const api = TestBed.inject(ConnectionsApi);
    vi.mocked(api.get).mockRejectedValueOnce(new Error('offline'));
    p.q<HTMLInputElement>('gw-access-switch input')!.click();
    await p.settle();
    expect(p.q<HTMLInputElement>('#cf-credentials-user')!.value).toBe('draft_user');
    expect(text(p.q('#edit-access-error'))).toContain('Your draft is kept.');
    expect(p.button('Save changes').getAttribute('aria-disabled')).toBe('true');
    p.button('Save changes').click();
    await p.settle();
    expect(core.updates).toEqual([]);
    p.button('Check again', p.q('#edit-access-error')!).click();
    await p.settle();
    expect(p.q('#edit-access-error')).toBeNull();
    expect(p.q<HTMLInputElement>('#cf-credentials-user')!.value).toBe('draft_user');
    p.button('Save changes').click();
    await p.settle();
    expect(core.updates[0].version).toBe(4);
  });

  it('does not adopt a concurrent settings change as the version of the draft', async () => {
    const p = await open('/connections/dev-db');
    await p.type('cf-credentials-user', 'draft_user');
    p.button('Load databases').click();
    await p.settle();
    core.detail = { ...structuredClone(DETAIL), version: 4, config: { ...DETAIL.config, credentials: { user: 'changed_elsewhere' } } };
    p.q<HTMLInputElement>('gw-access-switch input')!.click();
    await p.settle();
    expect(text(p.q('#edit-access-error'))).toContain('Saved settings changed elsewhere.');
    expect(p.q<HTMLInputElement>('#cf-credentials-user')!.value).toBe('draft_user');
    expect(p.button('Save changes').getAttribute('aria-disabled')).toBe('true');
    expect(core.updates).toEqual([]);
  });

  it('refreshes access when secret-store metadata is enriched separately from connection details', async () => {
    core.detail = { ...structuredClone(DETAIL), secrets: [{ ...DETAIL.secrets[0], state: 'reference', source: 'reference' }] };
    vi.mocked(TestBed.inject(SecretsApi).list).mockResolvedValueOnce([{ id: 'sec-1', store: 'vault-main', canRotate: false } as SecretRow]);
    const p = await open('/connections/dev-db');
    p.q<HTMLInputElement>('gw-access-switch input')!.click();
    await p.settle();
    expect(p.q('#edit-access-error')).toBeNull();
    expect(p.button('Save changes').getAttribute('aria-disabled')).toBeNull();
  });

  it('shows health, who uses it and the settings with the stored secret', async () => {
    const p = await open('/connections/dev-db');
    expect(text(p.q('h1'))).toBe('Edit connection dev-db');
    expect(text(p.q('.gw-page-header__description'))).toBe('PostgreSQL at db.dev.internal:5432/app. Saved changes apply from the next call; calls in flight finish with the old settings.');
    expect(text(p.q('.gw-page-header__meta .gw-status'))).toBe('Healthy · 12 ms');
    expect(Array.from(p.el.querySelectorAll('.gw-stat')).map(text)).toEqual([
      'Health check, last 24 hours 12ms Between 10 ms and 15 ms · checked every minute',
      'Calls, last 30 days 412 +18% vs previous 30 days 3 errors · 2 denied by policy · 1 waiting for approval',
      'Used by 1endpoint 3 tools on dev · Claude Desktop and Codex',
    ]);
    expect(core.insightQueries).toEqual([{ q: 'connection:dev-db', from: new Date(NOW - 30 * 86_400_000).toISOString(), to: new Date(NOW).toISOString() }]);
    expect(text(p.q('#connection-tools-tab'))).toBe('Tools 3 tools');
    expect(Array.from(p.el.querySelectorAll('#used-by tbody tr')).map((r) => text(r.querySelector('th')))).toEqual(['sql_list_schemas', 'sql_query', 'sql_execute']);
    expect(p.el.querySelector('#used-by tbody a.gw-btn')?.getAttribute('href')).toBe('/activity?q=connection:dev-db%20tool:sql_list_schemas%20endpoint:dev');
    expect(text(p.q('#step-2-title'))).toBe('Settings');
    expect(p.el.querySelector('.gw-stepper')).toBeNull();
    expect((document.getElementById('cf-name') as HTMLInputElement).readOnly).toBe(false);
    expect(text(p.q('#cf-name-hint'))).toContain('Renaming applies from the next call: endpoints, policies and secret names follow, and past calls keep the old name.');
    expect(text(p.q('.gw-secret[data-state=stored]'))).toContain('version 3');
  });

  it('counts each tool once in the Tools tab, and keeps the page when the calls do not load', async () => {
    core.detail = { ...structuredClone(DETAIL), usedBy: [...DETAIL.usedBy, { tool: 'sql_query', endpoint: 'stage-readonly', mode: 'READ_ONLY' }] };
    core.insights = new ApiError('server', 500, { title: 'The core failed', detail: 'Insights could not be computed.' });
    const p = await open('/connections/dev-db');
    expect(text(p.q('#connection-tools-tab .gw-tabs__count'))).toBe('3 tools');
    expect(Array.from(p.el.querySelectorAll('#used-by tbody tr'))).toHaveLength(3);
    expect(Array.from(p.el.querySelectorAll('.gw-stat')).map(text)).toEqual([
      'Health check, last 24 hours 12ms Between 10 ms and 15 ms · checked every minute',
      "Calls, last 30 days The calls didn't load. Try again to load the calls of dev-db",
      'Used by 2endpoints 4 tools on dev and stage-readonly · Claude Desktop and Codex',
    ]);
    core.insights = busyMonth(NOW);
    p.button('Try again').click();
    await p.settle();
    expect(text(p.q('.gw-stat:nth-child(2) .gw-stat__value'))).toBe('412');
  });

  it('renames the connection with its settings and opens it under the new name', async () => {
    const p = await open('/connections/dev-db');
    vi.mocked(TestBed.inject(ConnectionsApi).update).mockImplementationOnce(async (_name, r) => {
      core.updates.push(structuredClone(r));
      core.detail = { ...structuredClone(DETAIL), name: r.name!, version: DETAIL.version + 1,
        secrets: [{ ...DETAIL.secrets[0], name: `${r.name}/password` }] };
      return {} as never;
    });
    await p.type('cf-name', 'dev-reader');
    expect(text(p.q('#cf-name-hint'))).toContain('Saving renames dev-db from the next call');
    expect(text(p.q('#cf-name-hint'))).toContain('past calls keep dev-db.');

    p.button('Save changes').click();
    await p.settle();

    expect(TestBed.inject(ConnectionsApi).update).toHaveBeenCalledWith('dev-db', expect.objectContaining({ name: 'dev-reader' }));
    expect(core.updates).toHaveLength(1);
    expect(core.updates[0]).toMatchObject({ name: 'dev-reader', version: DETAIL.version, env: 'dev' });
    expect(TestBed.inject(ToastService).toasts().at(-1)).toMatchObject({ tone: 'success', title: 'Connection dev-db renamed to dev-reader' });
    expect(TestBed.inject(Router).url).toBe('/connections/dev-reader');
    await p.settle();
    expect(text(p.q('h1'))).toBe('Edit connection dev-reader');
    expect((document.getElementById('cf-name') as HTMLInputElement).value).toBe('dev-reader');
    expect(text(p.q('#cf-name-hint'))).toContain('Renaming applies from the next call');
  });

  it('sends no name when the name stays', async () => {
    const p = await open('/connections/dev-db');
    p.button('Save changes').click();
    await p.settle();
    expect(core.updates).toHaveLength(1);
    expect(core.updates[0]).not.toHaveProperty('name');
    expect(TestBed.inject(Router).url).toBe('/connections/dev-db');
  });

  it('says under the Name that another connection has it, and stays on the connection', async () => {
    core.updateFails = [new ApiError('conflict', 409, { title: 'Name in use', detail: 'A connection named qa-db already exists.' })];
    const p = await open('/connections/dev-db');
    await p.type('cf-name', 'qa-db');
    p.button('Save changes').click();
    await p.settle();
    expect(core.updates).toHaveLength(1);
    expect(text(p.q('#cf-name-err'))).toBe('A connection named qa-db already exists.');
    expect(document.activeElement?.id).toBe('cf-name');
    expect(TestBed.inject(Router).url).toBe('/connections/dev-db');
    await p.type('cf-name', 'qa-db-2');
    expect(p.q('#cf-name-err')).toBeNull();
  });

  it('refuses a new name that is not a slug before anything is sent', async () => {
    const p = await open('/connections/dev-db');
    await p.type('cf-name', 'Dev DB');
    p.button('Save changes').click();
    await p.settle();
    expect(text(p.q('#cf-name-err'))).toContain('Dev DB is not a slug.');
    expect(core.updates).toEqual([]);
  });

  it('shows an attached connection with no tools without claiming its endpoint is missing', async () => {
    core.detail = { ...structuredClone(DETAIL), name: 'search-dev', usedBy: [] };
    vi.mocked(TestBed.inject(ConnectionsApi).list).mockResolvedValueOnce({
      items: [{ name: 'search-dev', host: 'search.example.test:9200', usedBy: ['dev'] }], facets: {}, total: 1,
    } as never);
    const p = await open('/connections/search-dev');
    expect(Array.from(p.el.querySelectorAll('.gw-stat')).map(text)[2]).toBe('Used by 1endpoint 0 tools on dev · Claude Desktop and Codex');
    expect(text(p.q('#connection-tools-tab .gw-tabs__count'))).toBe('0 tools');
    p.q<HTMLButtonElement>('#connection-tools-tab')!.click();
    await p.settle();
    expect(text(p.q('#used-by .gw-empty__title'))).toBe('No tools available');
    expect(text(p.q('#used-by'))).not.toContain('Not on an endpoint yet');
    expect(p.q<HTMLAnchorElement>('#used-by .gw-empty__actions a')!.getAttribute('href')).toBe('/endpoints/dev?tab=tools');
  });

  it('still shows the attach action when a connection has no saved endpoint membership', async () => {
    core.detail = { ...structuredClone(DETAIL), usedBy: [] };
    vi.mocked(TestBed.inject(ConnectionsApi).list).mockResolvedValueOnce({
      items: [{ name: 'dev-db', usedBy: [] }], facets: {}, total: 1,
    } as never);
    const p = await open('/connections/dev-db');
    expect(Array.from(p.el.querySelectorAll('.gw-stat')).map(text)[2]).toContain('Used by 0endpoints');
    expect(text(p.q('#used-by .gw-empty__title'))).toBe('Not on an endpoint yet');
    expect(p.q<HTMLAnchorElement>('#used-by .gw-empty__actions a')!.getAttribute('href')).toBe('/endpoints');
  });

  it('switches Settings and Tools with the keyboard without losing unsaved settings', async () => {
    const p = await open('/connections/dev-db');
    const settings = p.q<HTMLElement>('#connection-settings')!;
    const tools = p.q<HTMLElement>('#used-by')!;
    const settingsTab = p.q<HTMLButtonElement>('#connection-settings-tab')!;
    const toolsTab = p.q<HTMLButtonElement>('#connection-tools-tab')!;
    const form = p.q<HTMLFormElement>('#cf-form')!;
    expect(text(settingsTab)).toBe('Settings');
    expect(text(toolsTab)).toBe('Tools 3 tools');
    expect(settings.hidden).toBe(false);
    expect(tools.hidden).toBe(true);
    expect(settings.contains(p.q('#delete-connection'))).toBe(true);
    await p.type('cf-credentials-user', 'unsaved_reader');

    settingsTab.focus();
    settingsTab.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    await p.settle();
    expect(document.activeElement).toBe(toolsTab);
    expect(toolsTab.getAttribute('aria-selected')).toBe('true');
    expect(settings.hidden).toBe(true);
    expect(tools.hidden).toBe(false);
    expect(tools.querySelector('gw-connection-tool-modes')).not.toBeNull();

    toolsTab.dispatchEvent(new KeyboardEvent('keydown', { key: 'Home', bubbles: true }));
    await p.settle();
    expect(document.activeElement).toBe(settingsTab);
    expect(settingsTab.getAttribute('aria-selected')).toBe('true');
    expect(settings.hidden).toBe(false);
    expect(tools.hidden).toBe(true);
    expect(p.q('#cf-form')).toBe(form);
    expect(p.q<HTMLInputElement>('#cf-credentials-user')!.value).toBe('unsaved_reader');
    expect(core.updates).toEqual([]);
    expect(core.modes).toEqual([]);
  });

  it("lists the kubeconfig's contexts the core found (metadata, step API) and says how often health is checked", async () => {
    core.spec = {
      ...structuredClone(SPEC),
      type: 'kubernetes',
      name: 'Kubernetes',
      schema: CONNECTOR_FORMS['kubernetes'],
      secretFields: [{ pointer: '/token', kind: 'value', title: 'Service account token' }],
    };
    core.detail = {
      ...structuredClone(DETAIL),
      name: 'dev-cluster',
      type: 'kubernetes',
      typeName: 'Kubernetes',
      config: { auth: 'kubeconfig-file', kubeconfigPath: '~/.kube/config', context: 'kind-dev', namespace: 'default' },
      secrets: [],
      metadata: { kubeconfigContexts: { 'kind-dev': 'https://127.0.0.1:6443', 'gke-prod': 'https://34.1.2.3' } },
      host: 'k8s.dev.internal:6443 · ns default',
      typeLabel: 'Kubernetes 1',
      healthIntervalSeconds: 300,
    };
    const p = await open('/connections/dev-cluster');
    const context = document.getElementById('cf-context') as HTMLSelectElement;
    expect(context.tagName).toBe('SELECT');
    expect([...context.options].map((o) => o.value).filter(Boolean)).toEqual(['kind-dev', 'gke-prod']);
    expect(context.value).toBe('kind-dev');
    expect(text(p.q('.gw-page-header__description'))).toContain('Kubernetes 1 at k8s.dev.internal:6443 · ns default.');
    expect(Array.from(p.el.querySelectorAll('.gw-stat')).map(text)[0]).toContain('· checked every 5 minutes');
  });

  it('follows connection.health live', async () => {
    const p = await open('/connections/dev-db');
    TestBed.inject(EventStream).emit('connection.health', { name: 'dev-db', env: 'dev', state: 'degraded', latencyMs: 1800, message: null, checkedAt: '2026-10-22T14:30:00+02:00' });
    await p.settle();
    expect(text(p.q('.gw-page-header__meta .gw-status'))).toBe('Slow · 1.8 s');
    TestBed.inject(EventStream).emit('connection.health', { name: 'stage-db', env: 'stage', state: 'unreachable', message: null, checkedAt: '2026-10-22T14:30:00+02:00' });
    await p.settle();
    expect(text(p.q('.gw-page-header__meta .gw-status'))).toBe('Slow · 1.8 s');
  });

  it('keeps health received while the saved connection detail is still loading', async () => {
    core.detail = { ...structuredClone(DETAIL), health: { state: 'unknown', latencyMs: null, message: null, checkedAt: null } };
    let resolveForm!: (spec: ConnectionFormSpec) => void;
    vi.mocked(TestBed.inject(ConnectorTypesApi).form).mockReturnValueOnce(new Promise((resolve) => { resolveForm = resolve; }));
    const harness = await RouterTestingHarness.create();
    document.body.appendChild(harness.fixture.nativeElement);
    await harness.navigateByUrl('/connections/dev-db');
    await vi.waitFor(() => expect(TestBed.inject(ConnectorTypesApi).form).toHaveBeenCalled());

    TestBed.inject(EventStream).emit('connection.health', {
      name: 'dev-db', env: 'dev', state: 'healthy', latencyMs: 23, message: null, checkedAt: '2026-10-22T14:30:00+02:00',
    });
    resolveForm(structuredClone(SPEC));
    await settle(harness);

    expect(text(harness.routeNativeElement?.querySelector('.gw-page-header__meta .gw-status'))).toBe('Healthy · 23 ms');
  });

  it('Test connection works in place on the button and says the result in a toast', async () => {
    const p = await open('/connections/dev-db');
    const actions = p.q('#edit-connection-actions')!;
    expect(p.q('#edit-test')!.parentElement).toBe(actions);
    expect(p.q('#edit-test')!.nextElementSibling).toBe(p.button('Save changes'));
    expect(p.q('.gw-page-header #edit-test')).toBeNull();
    p.q<HTMLButtonElement>('#edit-test')!.click();
    p.harness.detectChanges();
    expect(p.q('#edit-test')!.getAttribute('aria-busy')).toBe('true');
    expect(text(p.q('#edit-checking'))).toBe('Checking dev-db');
    await p.settle();
    expect(p.q('#edit-test')!.hasAttribute('aria-busy')).toBe(false);
    expect(TestBed.inject(ToastService).toasts().map((t) => [t.title, t.body])).toEqual([['dev-db is healthy', 'Answered in 12 ms.']]);
    expect(core.tests[0]).toMatchObject({ type: 'postgres', env: 'dev', existing: 'dev-db', secrets: {} });
    expect(TestBed.inject(ConnectionsApi).test).not.toHaveBeenCalled();
  });

  it('tests unsaved settings from Tools and keeps the draft and saved health separate', async () => {
    const p = await open('/connections/dev-db');
    const api = TestBed.inject(ConnectionsApi);
    const loaded = vi.mocked(api.get).mock.calls.length;
    await p.type('cf-connection-host', 'draft.dev.internal');
    await p.type('cf-credentials-user', 'draft_reader');
    p.q<HTMLButtonElement>('#connection-tools-tab')!.click();
    await p.settle();
    const test = p.q<HTMLButtonElement>('#edit-test')!;
    expect(test.closest('[hidden]')).toBeNull();
    test.click();
    await p.settle();

    expect(core.tests).toHaveLength(1);
    expect(core.tests[0]).toMatchObject({ existing: 'dev-db', secrets: {}, config: {
      connection: { host: 'draft.dev.internal' }, credentials: { user: 'draft_reader' },
    } });
    expect(api.test).not.toHaveBeenCalled();
    expect(api.get).toHaveBeenCalledTimes(loaded);
    expect(core.updates).toEqual([]);
    expect(p.q<HTMLInputElement>('#cf-connection-host')!.value).toBe('draft.dev.internal');
    expect(p.q<HTMLInputElement>('#cf-credentials-user')!.value).toBe('draft_reader');
  });

  it('tests an imported connection with its newly entered missing credential before saving', async () => {
    core.detail = { ...structuredClone(DETAIL), enabled: false, setupRequired: true,
      setupMissingFields: ['/credentials/password'], secrets: [] };
    const p = await open('/connections/dev-db');
    await p.type('cf-credentials-password', 'fixture-unsaved-password');
    const test = p.q<HTMLButtonElement>('#edit-test')!;
    expect(test.getAttribute('aria-disabled')).not.toBe('true');
    test.click();
    p.harness.detectChanges();
    expect(text(p.q('#edit-connection-actions #edit-checking'))).toBe('Checking dev-db');
    await p.settle();

    expect(core.tests[0]).toMatchObject({ existing: 'dev-db', secrets: {
      '/credentials/password': { source: 'local', value: 'fixture-unsaved-password' },
    } });
    expect(TestBed.inject(ConnectionsApi).test).not.toHaveBeenCalled();
    expect(TestBed.inject(ConnectionsApi).setEnabled).not.toHaveBeenCalled();
    expect(core.updates).toEqual([]);
    expect(core.replaced).toEqual([]);
    expect(text(p.q('.gw-page-header__meta .gw-status'))).toBe('Needs setup');
  });

  it('tests an unsaved replacement of a stored credential without replacing it', async () => {
    const p = await open('/connections/dev-db');
    p.button('Replace').click();
    await p.settle();
    await p.type('cf-credentials-password', 'fixture-replacement-password');
    p.q<HTMLButtonElement>('#edit-test')!.click();
    await p.settle();
    expect(core.tests[0]).toMatchObject({ existing: 'dev-db', secrets: {
      '/credentials/password': { source: 'local', value: 'fixture-replacement-password' },
    } });
    expect(core.replaced).toEqual([]);
    expect(core.updates).toEqual([]);
    expect(p.q<HTMLInputElement>('#cf-credentials-password')!.value).toBe('fixture-replacement-password');
  });

  it('reveals Settings and focuses a refused test field when testing from Tools', async () => {
    core.nextTest = new ApiError('unprocessable', 422, { title: 'Invalid connection',
      errors: [{ field: '/config/credentials/user', message: 'This login is unavailable.' }] });
    const p = await open('/connections/dev-db');
    p.q<HTMLButtonElement>('#connection-tools-tab')!.click();
    await p.settle();
    p.q<HTMLButtonElement>('#edit-test')!.click();
    await p.settle();
    expect(p.q<HTMLElement>('#connection-settings')!.hidden).toBe(false);
    expect(document.activeElement).toBe(p.q('#cf-credentials-user'));
    expect(text(p.q('#cf-credentials-user-err'))).toContain('This login is unavailable.');
    expect(core.updates).toEqual([]);
  });

  it('confirms sending stored credentials for the tested snapshot without saving it', async () => {
    core.nextTest = new ApiError('conflict', 409, { title: 'Send the stored secret to a new host?',
      detail: 'The stored password will be sent to draft.dev.internal.', confirm: 'send-secret-to-new-host' });
    const p = await open('/connections/dev-db');
    await p.type('cf-connection-host', 'draft.dev.internal');
    p.q<HTMLButtonElement>('#edit-test')!.click();
    await p.settle();
    const dialog = document.getElementById('dlg-new-host') as HTMLDialogElement;
    expect(dialog.open).toBe(true);
    expect(text(document.activeElement)).toBe('Cancel');
    const tested = structuredClone(core.tests[0]);
    await p.type('cf-connection-host', 'later.dev.internal');
    core.nextTest = OK;
    p.button('Send and test', dialog).click();
    await p.settle();
    expect(core.tests).toHaveLength(2);
    expect(core.tests[1]).toEqual({ ...tested, confirm: 'send-secret-to-new-host' });
    expect(core.updates).toEqual([]);
    expect(p.q<HTMLInputElement>('#cf-connection-host')!.value).toBe('later.dev.internal');
  });

  it('trusts a host key in the draft and tests again without saving or reloading', async () => {
    core.nextTest = HOST_KEY;
    const p = await open('/connections/dev-db');
    await p.type('cf-credentials-user', 'draft_reader');
    const api = TestBed.inject(ConnectionsApi);
    const loaded = vi.mocked(api.get).mock.calls.length;
    p.q<HTMLButtonElement>('#edit-test')!.click();
    await p.settle();
    const tested = structuredClone(core.tests[0].config);
    const trusted = { ...tested, connection: { ...tested['connection'] as JsonObject, hostKey: 'ssh-ed25519 AAAA' } };
    core.pendingConfirm = Promise.resolve({ kind: 'host-key', config: trusted, retest: null });
    core.nextTest = OK;
    p.button('Trust this key and test again', document.getElementById('dlg-trust')!).click();
    await p.settle();
    expect(api.confirmDraft).toHaveBeenCalledWith('cf-1');
    expect(core.tests).toHaveLength(2);
    expect(core.tests[1]).toMatchObject({ existing: 'dev-db', config: trusted });
    expect(p.page().form()!.value().config).toMatchObject(trusted);
    expect(p.q<HTMLInputElement>('#cf-credentials-user')!.value).toBe('draft_reader');
    expect(core.updates).toEqual([]);
    expect(api.get).toHaveBeenCalledTimes(loaded);
  });

  it('retains new-host consent through draft host-key trust and the subsequent test', async () => {
    core.nextTest = new ApiError('conflict', 409, { title: 'Send the stored secret to a new host?',
      detail: 'The stored password will be sent to draft.dev.internal.', confirm: 'send-secret-to-new-host' });
    const p = await open('/connections/dev-db');
    await p.type('cf-connection-host', 'draft.dev.internal');
    p.q<HTMLButtonElement>('#edit-test')!.click();
    await p.settle();
    core.nextTest = HOST_KEY;
    p.button('Send and test', document.getElementById('dlg-new-host')!).click();
    await p.settle();
    expect(core.tests).toHaveLength(2);
    const tested = structuredClone(core.tests[1]);
    const trusted = { ...tested.config,
      connection: { ...tested.config['connection'] as JsonObject, hostKey: 'ssh-ed25519 AAAA' } };
    core.pendingConfirm = Promise.resolve({ kind: 'host-key', config: trusted, retest: null });
    core.nextTest = OK;
    p.button('Trust this key and test again', document.getElementById('dlg-trust')!).click();
    await p.settle();

    expect(TestBed.inject(ConnectionsApi).confirmDraft).toHaveBeenCalledWith('cf-1');
    expect(core.tests).toHaveLength(3);
    expect(core.tests.map((request) => request.confirm ?? null)).toEqual([
      null, 'send-secret-to-new-host', 'send-secret-to-new-host',
    ]);
    expect(core.tests[2]).toEqual({ ...tested, config: trusted });
    expect(TestBed.inject(ConnectionsApi).test).not.toHaveBeenCalled();
    expect(core.updates).toEqual([]);
    expect(core.replaced).toEqual([]);
    expect(core.references).toEqual([]);
    expect(TestBed.inject(ToastService).toasts().at(-1)?.title).toBe('dev-db is healthy');
  });

  it('does not let a pending trust response overwrite a newer draft', async () => {
    core.nextTest = HOST_KEY;
    const p = await open('/connections/dev-db');
    p.q<HTMLButtonElement>('#edit-test')!.click();
    await p.settle();
    const tested = structuredClone(core.tests[0].config);
    let resolve!: (result: TestConfirmed) => void;
    core.pendingConfirm = new Promise((done) => { resolve = done; });
    p.button('Trust this key and test again', document.getElementById('dlg-trust')!).click();
    await p.settle();
    expect(TestBed.inject(ConnectionsApi).confirmDraft).toHaveBeenCalledWith('cf-1');
    await p.type('cf-connection-host', 'later.dev.internal');
    resolve({ kind: 'host-key', config: { ...tested,
      connection: { ...tested['connection'] as JsonObject, hostKey: 'ssh-ed25519 AAAA' } }, retest: null });
    await p.settle();
    expect(p.q<HTMLInputElement>('#cf-connection-host')!.value).toBe('later.dev.internal');
    expect(p.page().form()!.value().config['connection']).not.toHaveProperty('hostKey');
    expect(core.tests).toHaveLength(1);
    expect(core.updates).toEqual([]);
  });

  it('Save changes sends the version; a new host with a stored secret asks first, then sends the confirmation', async () => {
    core.updateFails = [new ApiError('conflict', 409, { title: 'Send the stored password to db2.dev.internal?', detail: 'The password of dev-db goes to the new host on the next call.', confirm: 'send-secret-to-new-host' })];
    const p = await open('/connections/dev-db');
    await p.type('cf-connection-host', 'db2.dev.internal');
    p.button('Load databases').click();
    await p.settle();
    p.button('Save changes').click();
    await p.settle();
    const dialog = document.getElementById('dlg-new-host') as HTMLDialogElement;
    expect(dialog.open).toBe(true);
    expect(text(dialog.querySelector('h2'))).toBe('Send the stored password to db2.dev.internal?');
    expect(text(document.activeElement)).toBe('Cancel');
    p.button('Send and save', dialog).click();
    await p.settle();
    expect(core.updates.map((u) => [(u.config['connection'] as JsonObject)['host'], u.version, u.confirm])).toEqual([
      ['db2.dev.internal', 3, null],
      ['db2.dev.internal', 3, 'send-secret-to-new-host'],
    ]);
    expect(TestBed.inject(ToastService).toasts().map((t) => t.title)).toEqual(['Connection dev-db saved']);
  });

  it('Save changes collects the confirmations the core asks for: a new host, then the prod user’s rights', async () => {
    core.updateFails = [
      new ApiError('conflict', 409, { title: 'Send the stored secret to a new host?', detail: 'Send the stored credentials of dev-db to db2.dev.internal?', confirm: 'send-secret-to-new-host' }),
      new ApiError('conflict', 409, { title: 'Keep these rights on prod?', detail: 'dev-db: The user is a superuser.', confirm: 'danger' }),
    ];
    const p = await open('/connections/dev-db');
    await p.type('cf-connection-host', 'db2.dev.internal');
    p.button('Load databases').click();
    await p.settle();
    p.button('Save changes').click();
    await p.settle();
    p.button('Send and save', document.getElementById('dlg-new-host')!).click();
    await p.settle();
    const danger = document.getElementById('dlg-prod-rights') as HTMLDialogElement;
    expect(danger.open).toBe(true);
    p.button('Save anyway', danger).click();
    await p.settle();
    expect(core.updates.map((u) => u.confirm)).toEqual([null, 'send-secret-to-new-host', 'send-secret-to-new-host,danger']);
    expect(TestBed.inject(ToastService).toasts().map((t) => t.title)).toEqual(['Connection dev-db saved']);
  });

  it('Replace on a prod secret asks for the comment the core requires', async () => {
    core.detail = { ...structuredClone(DETAIL), env: 'prod' };
    const p = await open('/connections/dev-db');
    p.button('Replace').click();
    await p.settle();
    expect(document.activeElement?.id).toBe('cf-credentials-password');
    await p.type('cf-credentials-password', 'n3w-value');
    p.button('Save password').click();
    await p.settle();
    const dialog = document.getElementById('dlg-comment') as HTMLDialogElement;
    expect(dialog.open).toBe(true);
    expect(document.activeElement?.id).toBe('dlg-comment-text');
    p.button('Save password', dialog).click();
    await p.settle();
    expect(text(dialog.querySelector('#dlg-comment-err'))).toBe('Add a comment: it is required to change a secret on prod.');
    const area = dialog.querySelector<HTMLTextAreaElement>('#dlg-comment-text')!;
    area.value = 'Rotated after the leak drill';
    area.dispatchEvent(new Event('input', { bubbles: true }));
    p.button('Save password', dialog).click();
    await p.settle();
    expect(core.replaced).toEqual([{ id: 'sec-1', value: 'n3w-value', reason: 'Rotated after the leak drill' }]);
    expect(TestBed.inject(ToastService).toasts().map((t) => t.title)).toEqual(['Password of dev-db saved']);
  });

  it('Delete: a danger dialog with what stops working and the denied approvals; Cancel has the focus', async () => {
    const p = await open('/connections/dev-db');
    p.button('Delete connection').click();
    await p.settle();
    const dialog = document.getElementById('dlg-delete-connection') as HTMLDialogElement;
    expect(dialog.open).toBe(true);
    expect(dialog.classList).toContain('gw-dialog--danger');
    expect(text(document.activeElement)).toBe('Cancel');
    expect(text(dialog.querySelector('#dlg-del-desc'))).toBe("Agents lose it on their next call. Its encrypted password (version 3) is destroyed. This can't be undone.");
    expect(text(dialog.querySelector('.gw-kv__row'))).toBe('Endpoint dev 3 tools: sql_list_schemas, sql_query, sql_execute · used by Claude Desktop and Codex');
    expect(text(dialog.querySelector('.gw-alert'))).toBe('1 pending approval is denied sql_execute from Codex');
    p.button('Delete connection dev-db', dialog).click();
    await p.settle();
    expect(core.deleted).toEqual(['dev-db']);
    expect(TestBed.inject(Router).url).toBe('/connections');
    expect(TestBed.inject(ToastService).toasts().map((t) => [t.title, t.body])).toEqual([['Connection dev-db deleted', 'Removed from dev.']]);
  });

  it('says when the connection is gone', async () => {
    core.detail = new ApiError('not-found', 404, { title: 'Not found', detail: 'There is no connection old-db.' });
    const p = await open('/connections/old-db');
    expect(text(p.q('.gw-empty__title'))).toBe('No connection named old-db');
    expect(p.el.querySelector('#cf-form')).toBeNull();
  });
});

describe('Add connection: first run (kit ?state=first-run)', () => {
  beforeEach(() => {
    core.empty = true;
    core.endpointTools = [row('sql_list_schemas', 'READ_ONLY'), row('sql_query', 'READ_ONLY'), row('sql_execute', 'APPROVAL')];
  });

  /** A gw-kv list as [key, value] pairs. */
  const kv = (sel: string) => Array.from(document.querySelectorAll(`${sel} .gw-kv__row`)).map((r) => [text(r.querySelector('dt')), text(r.querySelector('dd'))]);

  /** Step 1 → Configure (prod if asked) → a filled form → Test → Create endpoint. */
  async function toEndpoint(p: Awaited<ReturnType<typeof open>>, env: 'dev' | 'prod' = 'dev') {
    p.button('Continue').click();
    await p.settle();
    if (env === 'prod') {
      p.el.querySelector<HTMLInputElement>('input[name="cf-env"][value="prod"]')!.click();
      await p.settle();
    }
    await p.type('cf-name', 'qa-db');
    await p.type('cf-connection-host', 'db.dev.internal');
    await p.type('cf-credentials-user', 'app_rw');
    await p.type('cf-credentials-password', 's3cret-value');
    p.button('Load databases').click();
    await p.settle();
    p.q<HTMLInputElement>('#cf-database-0')!.click();
    await p.settle();
    p.button('Continue', p.q('#cf-form')!).click();
    await p.settle();
    p.button('Continue', p.q('#step-3')!).click();
    await p.settle();
  }

  function useDefault(over: Partial<EndpointSummary> = {}): void {
    vi.mocked(TestBed.inject(EndpointsApi).list).mockResolvedValue([endpoint({
      slug: 'default', url: 'http://127.0.0.1:7465/mcp/default', connections: { total: 0, byEnv: {} },
      clients: [], tokens: { active: 0, expiringSoon: 0 }, tools: { read: 2, approval: 0, off: 0 }, ...over,
    })]);
  }

  it('keeps the beginner flow and ready card while reusing the empty default endpoint', async () => {
    useDefault();
    const p = await open();
    expect(text(p.q('#page-title'))).toBe('Add your first connection');
    expect(text(p.q('.gw-page-header__description'))).toContain('adds it to endpoint default');
    await toEndpoint(p);
    expect(p.steps()[3]).toBe('current:4 Add to endpoint');
    expect(kv('#step-4')[0]).toEqual(['Name', 'default']);
    expect(text(p.q('#ep-new-t + .gw-form__description'))).toContain('Endpoint default is already set up.');
    p.button('Save connection').click();
    await p.settle();
    expect(core.endpointsCreated).toEqual([]);
    expect(core.added).toEqual(['default']);
    expect(core.modes).toEqual([]);
    expect(text(p.q('#fr-done-t'))).toBe('qa-db is ready');
    expect(kv('#fr-done')[0]).toEqual(['Endpoint', 'default · http://127.0.0.1:7465/mcp/default · connection added 1 min ago']);
    expect(p.steps()[3]).toBe('complete:Add to endpoint, completed Endpoint default');
    expect(p.q('#fr-done a[href="/clients"]')).not.toBeNull();
    expect(TestBed.inject(ToastService).toasts().at(-1)?.body).toBe('Added to endpoint default with its tools. Next: connect your AI app.');
  });

  it('keeps production writes in Approval when the first connection joins the empty default endpoint', async () => {
    useDefault();
    const p = await open();
    await toEndpoint(p, 'prod');
    expect(text(p.q('#pv-default-t'))).toBe('qa-db environment prod on endpoint default');
    expect(text(p.q('#step-4 .gw-tool-policy-list .gw-card__description'))).toBe('3 tools · 2 read only · 1 approval');
    p.button('Save connection').click();
    await p.settle();
    expect(core.endpointsCreated).toEqual([]);
    expect(core.added).toEqual(['default']);
    expect(core.modes).toEqual([]);
    expect(text(p.q('#fr-done'))).toContain('sql_execute waits for your approval');
  });

  it.each(['known', 'discovered'] as const)('uses a separate prod endpoint when the default has %s live access across the prod boundary', async (when) => {
    useDefault(when === 'known' ? { tokens: { active: 1, expiringSoon: 0 } } : {});
    if (when === 'discovered') core.previews.set('default', {
      connection: 'qa-db', env: 'prod', tools: [], warnings: [], refusal: 'default has a live dev client grant; create a prod endpoint.',
    });
    const p = await open();
    await toEndpoint(p, 'prod');
    p.button('Save connection').click();
    await p.settle();
    expect(core.endpointsCreated).toEqual([{ slug: 'prod', startFrom: 'empty' }]);
    expect(core.added).toEqual(['prod']);
    expect(core.modes).toEqual([]);
    expect(kv('#fr-done')[0]?.[1]).toContain('prod · http://127.0.0.1:7465/mcp/prod');
    expect(p.steps()[3]).toBe('complete:Create endpoint, completed Endpoint prod');
  });

  it('speaks to a beginner when nothing is set up: the title, Cancel back to Overview, step 4 creates the endpoint', async () => {
    const p = await open();
    expect(text(p.q('#page-title'))).toBe('Add your first connection');
    expect(text(p.q('#page-crumb'))).toBe('Add your first connection');
    expect(TestBed.inject(Title).getTitle()).toBe('Add your first connection · GateRook');
    expect(text(p.q('.gw-page-header__description'))).toBe(
      'Choose its type, say where it is and how to sign in, and test it. GateRook then creates your first endpoint, the address your AI apps connect to. Nothing is saved until the last step.',
    );
    const cancel = p.q<HTMLAnchorElement>('.gw-page-header__actions a')!;
    expect([text(cancel), cancel.getAttribute('href')]).toEqual(['Cancel and go back to Overview', '/overview']);
    expect(text(p.q('#step-1 .gw-section__description'))).toBe(
      'A database, a cloud, a service or an MCP server. What you choose decides what you fill in next and what your AI apps can do with it.',
    );
    expect(p.steps()).toEqual(['current:1 Choose', 'upcoming:2 Configure', 'upcoming:3 Test', 'upcoming:4 Create endpoint']);
  });

  it("Configure in plain words; the secret stays encrypted here unless its field's menu picks a store", async () => {
    core.spec = {
      ...core.spec,
      secretSources: [
        { id: 'local', label: 'Encrypted here', available: true, reason: null },
        { id: 'vault', label: 'HashiCorp Vault', available: true, reason: null },
      ],
    };
    const p = await open();
    p.button('Continue').click();
    await p.settle();
    expect(text(p.q('#step-2 .gw-section__description'))).toBe('Enter the server and credentials, load its databases, then choose which ones agents may use.');
    expect(text(p.q('#cf-name-hint'))).toBe('Lowercase letters, digits and dashes, like dev-db. Your AI apps use this name to pick the connection.');
    const descriptions = Array.from(p.el.querySelectorAll('#cf-form .gw-form__description')).map(text);
    expect(descriptions).toContain('GateRook connects from this computer, so your VPN and hosts file apply.');
    expect(descriptions).toContain('Your AI never sees these. GateRook uses them only to sign in to the database.');
    expect(descriptions).toContain('Pick dev for your own work and test data. Prod turns on stricter rules.');
    expect(text(p.q('#cf-env-dev-d'))).toBe('Local work and test data. Changes to data still wait for your approval.');
    expect(text(p.q('#cf-credentials-password-hint'))).toBe('Encrypted on this computer when you save.');
    // The secret is encrypted here unless a store is picked in its field's menu: no form-wide source to choose.
    expect(p.q('#cf-src-field')).toBeNull();
    expect(text(p.q('#cf-credentials-password-src'))).toBe('Text');
    p.q<HTMLButtonElement>('#cf-credentials-password-src')!.click();
    await p.settle();
    expect(text(p.q('#cf-credentials-password-src-menu [data-value="text"]'))).toContain('Encrypted on this computer');
    p.q<HTMLElement>('#cf-credentials-password-src-menu [data-value="vault/"]')!.click();
    await p.settle();
    expect(text(p.q('#cf-credentials-password-hint'))).toBe('Read from Vault at call time.');
  });

  it('step 4 shows the endpoint Save creates, named after the environment, and what the AI apps can do there', async () => {
    const announce = vi.spyOn(TestBed.inject(Announcer), 'announce');
    const p = await open();
    await toEndpoint(p);
    expect(p.visible()).toEqual(['step-4']);
    expect(announce).toHaveBeenCalledWith('Step 4 of 4: Create endpoint');
    expect(text(p.q('#step-3 .gw-section__description'))).toBe(
      'GateRook connects from this computer with what you entered and checks what that user may do in the database.',
    );
    expect(text(p.q('#step-4-title'))).toBe('Make qa-db available to your AI apps');
    expect(text(p.q('#ep-new-t'))).toBe('Your first endpoint');
    expect(kv('#step-4')).toEqual([
      ['Name', 'dev'],
      ['Address', 'http://127.0.0.1:7465/mcp/dev, the one your AI apps connect to'],
      ['AI apps', 'None yet. You connect one next.'],
    ]);
    expect(p.el.querySelectorAll('#step-4 input[type=checkbox]')).toHaveLength(0);
    expect(text(p.q('#pv-title'))).toBe('What your AI apps can do');
    expect(text(p.q('#pv-title + .gw-form__description'))).toBe(
      'Reads run at once. Anything that changes data, like sql_execute, waits for your approval. You can change the modes later.',
    );
    expect(text(p.q('#pv-dev-t'))).toBe('qa-db environment dev on endpoint dev');
    expect(text(p.q('#step-4 .gw-tool-policy-list .gw-card__description'))).toBe('3 tools · 2 read only · 1 approval');
    expect(text(p.q('#step-4 .gw-form__note'))).toBe('The password is encrypted on this computer when you save');
    expect(p.steps()[3]).toBe('current:4 Create endpoint');
  });

  it('Save creates the connection, the endpoint and puts it there, then the ready card takes the wizard’s place', async () => {
    const p = await open();
    await toEndpoint(p);
    p.button('Save connection').click();
    await p.settle();
    expect(core.created.map((c) => [c.name, c.env])).toEqual([['qa-db', 'dev']]);
    expect(core.endpointsCreated).toEqual([{ slug: 'dev', startFrom: 'empty' }]);
    expect(core.added).toEqual(['dev']);
    expect(core.modes).toEqual([]);
    // Every step done, no panel left, the header's Cancel gone; the card has the focus and the ways on.
    expect(p.visible()).toEqual([]);
    expect(p.steps()).toEqual([
      'complete:Choose, completed PostgreSQL',
      'complete:Configure, completed qa-db · dev',
      'complete:Test, completed Connected · 12 ms',
      'complete:Create endpoint, completed Endpoint dev',
    ]);
    expect(p.q('.gw-page-header__actions a')).toBeNull();
    expect(text(p.q('.gw-page-header__description'))).toBe('qa-db is saved and on your first endpoint. Next, connect the AI app you use.');
    expect(document.activeElement?.id).toBe('fr-done-t');
    expect(text(p.q('#fr-done-t'))).toBe('qa-db is ready');
    expect(text(p.q('#fr-done .gw-card__description'))).toBe('Saved and tested. Your AI apps can use it as soon as you connect one.');
    expect(text(p.q('#fr-done .gw-status'))).toBe('Healthy · 12 ms');
    expect(kv('#fr-done')).toEqual([
      ['Endpoint', 'dev · http://127.0.0.1:7465/mcp/dev · created 1 min ago'],
      ['Tools', 'sql_list_schemas and sql_query run at once (read only); sql_execute waits for your approval'],
      ['Password', 'Encrypted with a key kept on this computer. Back up the key so you can restore it.'],
    ]);
    expect(Array.from(p.el.querySelectorAll('#fr-done .gw-kv__row .gw-mono')).map(text)).toEqual([
      'dev',
      'http://127.0.0.1:7465/mcp/dev',
      'sql_list_schemas',
      'sql_query',
      'sql_execute',
    ]);
    expect(p.q('#fr-done a[href="/secrets#master-key"]')).not.toBeNull();
    expect(Array.from(p.el.querySelectorAll('#fr-done .gw-card__footer a')).map((a) => [text(a), a.getAttribute('href')])).toEqual([
      ['Back to Overview', '/overview'],
      ['Connect your AI app', '/clients'],
    ]);
    expect(TestBed.inject(Router).url).toBe('/connections/new');
    expect(TestBed.inject(ToastService).toasts().map((t) => [t.tone, t.title, t.body])).toEqual([
      ['success', 'Connection qa-db saved', 'Endpoint dev created with its tools. Next: connect your AI app.'],
    ]);
  });

  it('prod: the endpoint is prod and its tools keep their defaults, changes waiting for approval', async () => {
    core.lastBackupAt = '2026-10-20T09:00:00Z';
    const p = await open();
    await toEndpoint(p, 'prod');
    expect(text(p.q('#pv-title + .gw-form__description'))).toBe(
      'Reads run at once. Anything that changes data, like sql_execute, waits for your approval. You can change the modes later.',
    );
    expect(text(p.q('#pv-prod-t'))).toBe('qa-db environment prod on endpoint prod');
    expect(text(p.q('#step-4 .gw-tool-policy-list .gw-card__description'))).toBe('3 tools · 2 read only · 1 approval');
    expect(p.q('#step-4 .gw-tool-policy-list__row[data-mode="off"]')).toBeNull();
    p.button('Save connection').click();
    await p.settle();
    expect(core.endpointsCreated).toEqual([{ slug: 'prod', startFrom: 'empty' }]);
    expect(core.added).toEqual(['prod']);
    expect(core.modes).toEqual([]);
    expect(kv('#fr-done').slice(1)).toEqual([
      ['Tools', 'sql_list_schemas and sql_query run at once (read only); sql_execute waits for your approval'],
      // The key has a backup: no need to ask for one.
      ['Password', 'Encrypted with a key kept on this computer.'],
    ]);
  });

  it('an endpoint the core refuses: the connection is saved, the toast says why and its own page opens', async () => {
    core.createEndpointFails = new ApiError('unprocessable', 422, { title: 'Invalid slug', detail: 'Lowercase letters, digits and hyphens, up to 63.' });
    const p = await open();
    await toEndpoint(p);
    p.button('Save connection').click();
    await p.settle();
    expect(core.created.map((c) => c.name)).toEqual(['qa-db']);
    expect(core.added).toEqual([]);
    expect(TestBed.inject(Router).url).toBe('/connections/qa-db');
    expect(TestBed.inject(ToastService).toasts().map((t) => [t.tone, t.title, t.body])).toEqual([
      ['warning', 'Connection qa-db saved, endpoint dev not ready', 'Lowercase letters, digits and hyphens, up to 63. Add qa-db to an endpoint under Endpoints.'],
    ]);
  });

  it('is the usual wizard once something exists', async () => {
    core.empty = false;
    const p = await open();
    expect(text(p.q('#page-title'))).toBe('Add connection');
    expect(p.steps()[3]).toBe('upcoming:4 Add to endpoint');
  });

  /** External MCP server on the first run: Configure, then the review that starts it. */
  async function startMcp(p: Awaited<ReturnType<typeof open>>) {
    const field = (suffix: string) => p.q<HTMLInputElement>(`#step-2-mcp [id$="${suffix}"]`)!;
    await p.type(field('-name').id, 'docs');
    await p.type(field('-command').id, 'npx -y @modelcontextprotocol/server-filesystem@2026.1.14 /srv/docs');
    p.button('Continue', p.q('#step-2-mcp')!).click();
    await p.settle();
    p.q<HTMLInputElement>('#step-3-mcp input[type=checkbox][id$="-ack"]')!.click();
    await p.settle();
    p.button('Start MCP server docs', p.q('#step-3-mcp')!).click();
    await p.settle();
  }

  it('creates the first endpoint for a first MCP server, named after its environment, then the ready card', async () => {
    vi.mocked(TestBed.inject(EndpointsApi).tools).mockResolvedValue([
      { group: 'docs', connection: 'docs', env: null, tools: [row('read_file', 'READ_ONLY'), row('write_file', 'APPROVAL')] },
    ]);
    const p = await open('/connections/new?type=mcp-server');
    expect(text(p.q('#page-title'))).toBe('Add your first connection');
    expect(text(p.q('.gw-page-header__description'))).toContain('An MCP server is saved when it first connects.');
    await startMcp(p);
    expect(p.visible()).toEqual(['step-4-mcp']);
    expect(text(p.q('#step-4-mcp-title'))).toBe('Make docs available to your AI apps');
    expect(p.steps()[3]).toBe('current:4 Create endpoint');
    expect(kv('#step-4-mcp')[0]).toEqual(['Name', 'dev']);
    // The server is saved: Cancel would undo nothing.
    expect(p.q('.gw-page-header__actions a')).toBeNull();
    p.button('Create endpoint', p.q('#step-4-mcp')!).click();
    await p.settle();
    expect(core.endpointsCreated).toEqual([{ slug: 'dev', startFrom: 'empty' }]);
    expect(core.added).toEqual(['dev']);
    expect(p.visible()).toEqual([]);
    expect(text(p.q('#fr-done-t'))).toBe('docs is ready');
    expect(text(p.q('#fr-done .gw-card__description'))).toBe('Saved and started. Your AI apps can use it as soon as you connect one.');
    expect(kv('#fr-done').map(([key]) => key)).toEqual(['Endpoint', 'Tools']);
    expect(kv('#fr-done')[0]?.[1]).toContain('dev · http://127.0.0.1:7465/mcp/dev · created');
    expect(p.steps()).toEqual([
      'complete:Choose, completed External MCP server',
      'complete:Configure, completed docs · dev',
      'complete:Review, completed Started · 3 tools',
      'complete:Create endpoint, completed Endpoint dev',
    ]);
    expect(TestBed.inject(Router).url).toBe('/connections/new?type=mcp-server');
    expect(TestBed.inject(ToastService).toasts().at(-1)).toMatchObject({
      title: 'MCP server docs started', body: 'Endpoint dev created with its tools. Next: connect your AI app.',
    });
  });

  it('puts a first MCP server on the empty default endpoint, its tools previewed there', async () => {
    useDefault();
    core.previews.set('default', {
      connection: 'docs', env: 'dev', warnings: [], refusal: null,
      tools: [{ toolKey: 'docs:read_file', tool: 'read_file', mode: 'READ_ONLY', locked: null, declaredBy: 'server' }],
    } as ConnectionPreview);
    const p = await open('/connections/new?type=mcp-server');
    await startMcp(p);
    expect(p.steps()[3]).toBe('current:4 Add to endpoint');
    expect(text(p.q('#step-4-mcp [id$="-first-t"] + .gw-form__description'))).toContain('Endpoint default is already set up.');
    expect(text(p.q('#step-4-mcp table'))).toContain('read_file');
    p.button('Add to endpoint', p.q('#step-4-mcp')!).click();
    await p.settle();
    expect(core.endpointsCreated).toEqual([]);
    expect(core.added).toEqual(['default']);
    expect(text(p.q('#fr-done-t'))).toBe('docs is ready');
    expect(kv('#fr-done')[0]?.[1]).toContain('default · http://127.0.0.1:7465/mcp/default · connection added');
    expect(TestBed.inject(ToastService).toasts().at(-1)?.body).toBe('Added to endpoint default with its tools. Next: connect your AI app.');
  });

  it('keeps a first MCP server’s last step when its endpoint can’t be created, and tries again', async () => {
    core.createEndpointFails = new ApiError('unprocessable', 422, { detail: 'The endpoint name is reserved.' });
    const p = await open('/connections/new?type=mcp-server');
    await startMcp(p);
    p.button('Create endpoint', p.q('#step-4-mcp')!).click();
    await p.settle();
    expect(core.added).toEqual([]);
    expect(p.visible()).toEqual(['step-4-mcp']);
    expect(text(p.q('#step-4-mcp [role=alert]'))).toContain('dev: The endpoint name is reserved.');
    core.createEndpointFails = null;
    p.button('Create endpoint', p.q('#step-4-mcp')!).click();
    await p.settle();
    expect(core.endpointsCreated).toEqual([{ slug: 'dev', startFrom: 'empty' }]);
    expect(core.added).toEqual(['dev']);
    expect(text(p.q('#fr-done-t'))).toBe('docs is ready');
  });
});


/** The actual connector schema also exercises conditional password versus local key-file fields. */
function sshLogs() {
  core.spec = { ...SPEC, type: 'ssh-logs', name: 'SSH log files', schema: CONNECTOR_FORMS['ssh-logs'] };
  core.catalog = { groups: [{ name: 'Logs', cards: [{ ...CATALOG.groups[0].cards[0], type: 'ssh-logs', name: 'SSH log files', icon: 'scroll-text' }] }], installed: [] };
  core.detail = { ...structuredClone(DETAIL), type: 'ssh-logs', typeName: 'SSH log files',
    config: { connection: { host: 'logs.example.test', username: 'reader' }, credentials: { method: 'password' },
      logs: { root: '/var/log', allowedFiles: ['app.log'] } } };
}

const SSH_LOG_KEY = 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIG4q5cLnwuK6nh3KekSjpSxn3RUE8TATPm0X+fK02R8D';
const firstSshLogHost = (host = 'logs.example.test') => outcome({
  confirmationId: 'cf_ssh_first',
  needsConfirmation: { kind: 'host-key', text: `First connection to ${host}:22.`, data: {
    kind: 'host-key', host, port: 22, bastion: false, keyType: 'ssh-ed25519',
    fingerprint: 'SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA', publicKey: SSH_LOG_KEY,
  } },
});

describe('connection-new: SSH remote files', () => {
  it('automatically pins the first browsed host only in the draft until Save changes', async () => {
    sshLogs();
    const p = await open('/connections/dev-db');
    let finish!: (value: TestConfirmed) => void;
    core.pendingConfirm = new Promise((resolve) => { finish = resolve; });
    core.nextFiles = firstSshLogHost();
    p.button('Browse folders').click(); await p.settle();
    expect(TestBed.inject(ConnectionsApi).confirmDraft).toHaveBeenCalledExactlyOnceWith('cf_ssh_first');
    expect(text(p.q('#cf-form'))).not.toContain('Trust host key and browse');
    core.nextFiles = outcome({ ok: true, details: { path: '/var/log', parent: '/var', entries: [], truncated: false } });
    finish({ kind: 'host-key', config: { ...(core.detail as ConnectionDetail).config, hostKeys: { pinned: [SSH_LOG_KEY] } }, retest: null });
    await p.settle();
    expect(core.fileRequests).toHaveLength(2);
    expect(core.fileRequests[1].config['hostKeys']).toEqual({ pinned: [SSH_LOG_KEY] });
    expect(p.page().form()!.value().config['hostKeys']).toEqual({ pinned: [SSH_LOG_KEY] });
    expect((core.detail as ConnectionDetail).config['hostKeys']).toBeUndefined();
    expect(core.updates).toEqual([]);
    expect(core.created).toEqual([]);

    p.button('Save changes').click(); await p.settle();
    expect(core.updates).toHaveLength(1);
    expect(core.updates[0].config['hostKeys']).toEqual({ pinned: [SSH_LOG_KEY] });
  });

  it.each(['ssh.host-key-changed', 'ssh.host-key-revoked'])('does not automatically trust %s while browsing', async (code) => {
    sshLogs();
    const p = await open('/connections/dev-db');
    core.nextFiles = outcome({ error: { code, message: 'The previously trusted host key is no longer accepted.', technical: null } });
    p.button('Browse folders').click(); await p.settle();
    expect(TestBed.inject(ConnectionsApi).confirmDraft).not.toHaveBeenCalled();
    expect(core.fileRequests).toHaveLength(1);
    expect(core.updates).toEqual([]);
    expect(p.page().form()!.value().config).not.toHaveProperty('hostKeys.pinned');
    expect(text(p.q('#cf-form'))).toContain('previously trusted host key');
  });

  it('discards a late automatic host pin when the connection target changes', async () => {
    sshLogs();
    const p = await open('/connections/dev-db');
    let finish!: (value: TestConfirmed) => void;
    core.pendingConfirm = new Promise((resolve) => { finish = resolve; });
    core.nextFiles = firstSshLogHost();
    p.button('Browse folders').click(); await p.settle();
    expect(TestBed.inject(ConnectionsApi).confirmDraft).toHaveBeenCalledExactlyOnceWith('cf_ssh_first');
    await p.type('cf-connection-host', 'another.example.test');
    finish({ kind: 'host-key', config: { ...(core.detail as ConnectionDetail).config, hostKeys: { pinned: [SSH_LOG_KEY] } }, retest: null });
    await p.settle();
    expect(p.page().form()!.value().config['connection']).toMatchObject({ host: 'another.example.test' });
    expect(p.page().form()!.value().config).not.toHaveProperty('hostKeys.pinned');
    expect(core.fileRequests).toHaveLength(1);
    expect(core.updates).toEqual([]);
  });

  it('uses stored credentials for edits and retries browsing only after consent to a changed host', async () => {
    sshLogs();
    const p = await open('/connections/dev-db');
    await p.type('cf-connection-host', 'other.example.test');
    core.nextFiles = new ApiError('conflict', 409, { title: 'Send the stored password to other.example.test?',
      detail: 'The password will be used to browse this server.', confirm: 'send-secret-to-new-host' });
    p.button('Browse folders').click(); await p.settle();
    expect(core.fileRequests[0]).toMatchObject({ existing: 'dev-db', type: 'ssh-logs', path: '/var/log', secrets: {} });
    expect(core.fileRequests[0].config).not.toHaveProperty('credentials.password');
    const dialog = p.q<HTMLDialogElement>('#dlg-new-host')!;
    expect(dialog.open).toBe(true);
    core.nextFiles = outcome({ ok: true, details: { path: '/var/log', parent: '/var', entries: [], truncated: false } });
    p.button('Send and browse folders', dialog).click(); await p.settle();
    expect(core.fileRequests).toHaveLength(2);
    expect(core.fileRequests[1].confirm).toBe('send-secret-to-new-host');
    expect(text(p.q('#cf-form'))).toContain('No regular files or folders');
    expect(text(p.q('#cf-form'))).toContain('app.log');
  });

  it('retains explicit new-host consent only through the unchanged host-key trust and browse retry', async () => {
    sshLogs();
    const p = await open('/connections/dev-db');
    await p.type('cf-connection-host', 'other.example.test');
    core.nextFiles = new ApiError('conflict', 409, { title: 'Send the stored password?', detail: 'The host changed.', confirm: 'send-secret-to-new-host' });
    p.button('Browse folders').click(); await p.settle();
    core.nextFiles = firstSshLogHost('other.example.test');
    let finish!: (value: TestConfirmed) => void;
    core.pendingConfirm = new Promise((resolve) => { finish = resolve; });
    p.button('Send and browse folders', p.q('#dlg-new-host')!).click(); await p.settle();
    expect(TestBed.inject(ConnectionsApi).confirmDraft).toHaveBeenCalledExactlyOnceWith('cf_ssh_first');
    core.nextFiles = outcome({ ok: true, details: { path: '/var/log', parent: '/var', entries: [], truncated: false } });
    finish({ kind: 'host-key', config: { hostKeys: { pinned: [SSH_LOG_KEY] } }, retest: null });
    await p.settle();
    expect(core.fileRequests).toHaveLength(3);
    expect(core.fileRequests[2]).toMatchObject({ confirm: 'send-secret-to-new-host',
      config: { connection: { host: 'other.example.test' }, hostKeys: { pinned: [SSH_LOG_KEY] } } });
    await p.type('cf-connection-host', 'different.example.test');
    p.button('Browse folders').click(); await p.settle();
    expect(core.fileRequests[3].confirm).toBeUndefined();
  });

  it('does not restore file permissions removed while host-key confirmation is in flight', async () => {
    sshLogs();
    const p = await open('/connections/dev-db');
    core.nextFiles = firstSshLogHost();
    let finish!: (value: TestConfirmed) => void;
    core.pendingConfirm = new Promise((resolve) => { finish = resolve; });
    p.button('Browse folders').click(); await p.settle();
    expect(TestBed.inject(ConnectionsApi).confirmDraft).toHaveBeenCalledExactlyOnceWith('cf_ssh_first');
    p.q<HTMLButtonElement>('button[aria-label="Remove allowed file app.log"]')!.click(); await p.settle();
    core.nextFiles = outcome({ ok: true, details: { path: '/var/log', parent: '/var', entries: [], truncated: false } });
    finish({ kind: 'host-key', config: { ...(core.detail as ConnectionDetail).config, hostKeys: { pinned: [SSH_LOG_KEY] } }, retest: null });
    await p.settle();
    expect(p.page().form()!.files()!.selected()).toEqual([]);
    expect(p.page().form()!.value().config['hostKeys']).toEqual({ pinned: [SSH_LOG_KEY] });
    expect(core.fileRequests).toHaveLength(2);
    expect(core.fileRequests[1].config['logs']).toMatchObject({ root: '/var/log' });
    expect(core.fileRequests[1].config['logs']).not.toHaveProperty('allowedFiles');
  });

  it('drops late folder replies after the connection changes and renders password and key-file choices', async () => {
    sshLogs();
    const p = await open('/connections/new#step-2');
    await p.type('cf-connection-host', 'logs.example.test');
    await p.type('cf-connection-username', 'reader');
    await p.type('cf-credentials-password', 'test-only-password');
    expect(p.q('#cf-credentials-privateKeyPath')).toBeNull();
    let finish!: (value: TestOutcome) => void;
    core.pendingFiles = new Promise((resolve) => { finish = resolve; });
    p.button('Browse folders').click(); await p.settle();
    expect(core.fileRequests[0].secrets).toEqual({ '/credentials/password': { source: 'local', value: 'test-only-password' } });
    const method = p.q<HTMLSelectElement>('#cf-credentials-method')!;
    method.value = 'privateKeyFile'; method.dispatchEvent(new Event('change', { bubbles: true })); await p.settle();
    await p.type('cf-credentials-privateKeyPath', '/example/keys/reader');
    expect(p.q('#cf-credentials-password')).toBeNull();
    core.pendingFiles = null;
    p.button('Browse folders').click(); await p.settle();
    expect(core.fileRequests[1].config['credentials']).toEqual({ method: 'privateKeyFile', privateKeyPath: '/example/keys/reader' });
    expect(core.fileRequests[1].secrets).toEqual({});
    finish(outcome({ ok: true, details: { path: '/stale', parent: '/', entries: [], truncated: false } })); await p.settle();
    expect(p.page().form()!.files()!.directory()?.path).toBe('/var/log');
  });
});
