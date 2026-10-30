import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { CONNECTOR_FORMS } from '../../../testing/connector-schemas';
import { ApiError } from '../../core/api/api-error';
import { ApprovalItem, ApprovalsApi } from '../../core/api/approvals.api';
import { ConnectionDetail, ConnectionsApi, CreateConnectionRequest, TestConfirmed, TestDraftRequest, TestOutcome, UpdateConnectionRequest } from '../../core/api/connections.api';
import { ConnectionFormSpec, ConnectorCatalog, ConnectorTool, ConnectorTypeDetail, ConnectorTypesApi } from '../../core/api/connector-types.api';
import { ConnectionPreview, EndpointSummary, EndpointsApi, ToolGroup, ToolRow } from '../../core/api/endpoints.api';
import { Policies, PoliciesApi } from '../../core/api/policies.api';
import { MasterKeyApi, MasterKeyStatus, SecretRow, SecretsApi } from '../../core/api/secrets.api';
import { JsonObject, ToolMode } from '../../core/api/types';
import { EVENT_STREAM_FETCH, EventStream } from '../../core/events/event-stream';
import { TimeService } from '../../core/time/time.service';
import { Announcer } from '../../kit/announcer';
import { AriaDisabledGuard } from '../../kit/aria-disabled';
import { provideKitIcons } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { ConnectionNewPage } from './connection-new.page';

/**
 * Add connection against the kit's page script (design/pages/connection-new.html): the four steps with their focus
 * and announcements, what the test shows, the endpoints a connection may join, Save, the duplicate and the edit view
 * with its dialogs. The admin API is faked at the service level with PostgreSQL and the real OpenSearch schema.
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
        { type: 'clickhouse', variant: null, name: 'ClickHouse', icon: 'database', description: 'Analytics.', kind: 'plugin', version: '1.0.0', signed: true, tools: 6, toolsFrom: null, connections: 0, available: false, reason: 'Not installed. Install it from the catalog in Connectors first.', presetConfig: null },
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
  replaced: { id: string; value: string; reason: string | null | undefined }[] = [];
  /** Nothing set up yet: no connection, no endpoint (Add connection's first run). */
  empty = false;
  endpointsCreated: { slug: string; startFrom: string }[] = [];
  createEndpointFails: ApiError | null = null;
  /** The new endpoint's tools as the core keeps them (sql_execute starts in Approval on dev and prod). */
  endpointTools: ToolRow[] = [];
  modes: { slug: string; toolKey: string; connection: string | null; mode: ToolMode; version: number }[] = [];
  lastBackupAt: string | null = null;
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
  const types: Partial<ConnectorTypesApi> = {
    catalog: vi.fn(async () => {
      if (core.catalog instanceof ApiError) throw core.catalog;
      return structuredClone(core.catalog);
    }),
    form: vi.fn(async () => structuredClone(core.spec)),
    get: vi.fn(async () => structuredClone(TYPE)),
    previewTools: vi.fn(async (type: string, config: JsonObject, env: string) => {
      core.toolRequests.push(structuredClone({ type, config, env }));
      if (core.pendingTools) return core.pendingTools;
      if (core.configuredTools instanceof ApiError) throw core.configuredTools;
      return { tools: structuredClone(core.configuredTools) };
    }),
  };
  const connections: Partial<ConnectionsApi> = {
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
    list: vi.fn(async () =>
      (core.empty ? { items: [], facets: {}, total: 0 } : { items: [{ name: 'dev-db', host: 'db.dev.internal:5432/app' }], facets: {}, total: 1 }) as never,
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
    create: vi.fn(async (r: { slug: string; startFrom: string }) => {
      if (core.createEndpointFails) throw core.createEndpointFails;
      core.endpointsCreated.push({ slug: r.slug, startFrom: r.startFrom });
      return endpoint({ slug: r.slug, url: `http://127.0.0.1:7465/mcp/${r.slug}`, clients: [] });
    }),
    tools: vi.fn(async () => groups('qa-db')),
    setMode: vi.fn(async (slug: string, r: { toolKey: string; connection: string | null; mode: ToolMode; version: number }) => {
      core.modes.push({ slug, toolKey: r.toolKey, connection: r.connection, mode: r.mode, version: r.version });
      core.endpointTools = core.endpointTools.map((t) => (t.toolKey === r.toolKey ? { ...t, mode: r.mode, version: t.version + 1 } : t));
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
    replace: vi.fn(async (id: string, r: { value: string; reason?: string | null }) => {
      core.replaced.push({ id, value: r.value, reason: r.reason });
      return { id, version: 4, state: 'stored', tested: null, testMessage: null };
    }),
  };
  const masterKey: Partial<MasterKeyApi> = { status: vi.fn(async () => ({ lastBackupAt: core.lastBackupAt }) as MasterKeyStatus) };
  const approvals: Partial<ApprovalsApi> = { list: vi.fn(async () => ({ status: 'pending', items: [PENDING], nextCursor: null, pending: 1, counts: {} }) as never) };
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

describe('connection-specific tool preview', () => {
  const gcpTools = [
    tool({ name: 'gcp_logging_search', description: 'Search Google Cloud Logging using Logs Explorer filters.' }),
    tool({ name: 'gcp_logging_tail', description: 'Read new Google Cloud Logging entries.', defaultMode: 'off' }),
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
    expect(text(p.q('#pv-rows .gw-card__description'))).toBe('2 tools · 1 read only · 1 off');
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
    expect(core.discoveries).toHaveLength(1);
    expect(core.tests.map((request) => request.env)).toEqual(testInDev ? ['dev', 'prod'] : ['prod']);
    expect(core.tests.at(-1)!.config['connection']).toMatchObject({ databases: ['app'] });

    p.button('Continue', p.q('#step-3')!).click();
    await p.settle();
    p.button('Save connection', p.q('#step-4')!).click();
    await p.settle();
    expect(core.created).toHaveLength(1);
    expect(core.created[0]).toMatchObject({ env: 'prod', config: { connection: { databases: ['app'] } } });
  });

  it('refreshes selected MongoDB databases with production settings before testing and saving', async () => {
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
      connection: { host: 'mongo.example.test', databases: ['app'], tls: true, readPreference: 'secondaryPreferred' },
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
    expect(core.tests[1].config['connection']).toMatchObject({ databases: ['app'], tls: true });
    p.button('Continue', p.q('#step-3')!).click();
    await p.settle();
    p.button('Save connection', p.q('#step-4')!).click();
    await p.settle();
    expect(core.created).toHaveLength(1);
    expect(core.created[0]).toMatchObject({ type: 'mongodb', env: 'prod', config: {
      connection: { databases: ['app'], tls: true, readPreference: 'secondaryPreferred' },
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

  it('opens on Choose type with the catalog by group, the first usable type picked and how many use it', async () => {
    const { el, steps, visible } = await open();
    expect(visible()).toEqual(['step-1']);
    expect(steps()).toEqual(['current:1 Choose type', 'upcoming:2 Configure', 'upcoming:3 Test', 'upcoming:4 Add to endpoint']);
    const radios = Array.from(el.querySelectorAll<HTMLInputElement>('#step-1 input[type=radio]'));
    expect(radios.map((r) => [r.value, r.checked, r.getAttribute('aria-disabled')])).toEqual([
      ['postgres', true, null],
      ['clickhouse', false, 'true'],
    ]);
    expect(text(el.querySelector('#step-1 .gw-form__note'))).toBe('3 connections use PostgreSQL');
    expect(document.activeElement).toBe(document.body);
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
    expect(p.steps()[0]).toBe('complete:Choose type, completed PostgreSQL');
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
          connection: { host: 'db.dev.internal', port: 5432, databases: ['app'], sslMode: 'verify-full' },
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
    // The failed step's marker is an x (COMPONENTS.md stepper): no number, "failed" for screen readers.
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
    expect(p.el.querySelectorAll('#step-1 input[type=radio]')).toHaveLength(2);
  });
});

describe('Add connection: duplicate', () => {
  it('starts at Configure with the copied settings, an empty secret and the info alert', async () => {
    const p = await open('/connections/new?duplicate=dev-db');
    expect(p.visible()).toEqual(['step-2']);
    expect(text(p.q('h1'))).toBe('Duplicate dev-db');
    expect(text(p.q('#cf-form .gw-alert--info'))).toBe(
      'Copied from dev-db: where it is, the environment and the advanced settings. The credentials are not: enter a password or reference a secret for the copy.',
    );
    expect((document.getElementById('cf-name') as HTMLInputElement).value).toBe('dev-db-copy');
    expect((document.getElementById('cf-connection-host') as HTMLInputElement).value).toBe('db.dev.internal');
    expect((document.getElementById('cf-credentials-password') as HTMLInputElement).value).toBe('');
    expect(p.steps()[0]).toBe('complete:Choose type, completed PostgreSQL');
  });
});

describe('Connection: edit view', () => {
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

  it('shows health, who uses it and the settings with the stored secret', async () => {
    const p = await open('/connections/dev-db');
    expect(text(p.q('h1'))).toBe('Edit connection dev-db');
    expect(text(p.q('.gw-page-header__description'))).toBe('PostgreSQL at db.dev.internal:5432/app. Saved changes apply from the next call; calls in flight finish with the old settings.');
    expect(text(p.q('.gw-page-header__meta .gw-status'))).toBe('Healthy · 12 ms');
    expect(Array.from(p.el.querySelectorAll('.gw-stat')).map(text)).toEqual([
      'Health check, last 24 hours 12ms Between 10 ms and 15 ms · checked every minute',
      'Used by 1endpoint 3 tools on dev · Claude Desktop and Codex',
    ]);
    expect(Array.from(p.el.querySelectorAll('#used-by tbody tr')).map((r) => text(r.querySelector('th')))).toEqual(['sql_list_schemas', 'sql_query', 'sql_execute']);
    expect(p.el.querySelector('#used-by tbody a.gw-btn')?.getAttribute('href')).toBe('/activity?q=connection:dev-db%20tool:sql_list_schemas%20endpoint:dev');
    expect(text(p.q('#step-2-title'))).toBe('Settings');
    expect(p.el.querySelector('.gw-stepper')).toBeNull();
    expect((document.getElementById('cf-name') as HTMLInputElement).readOnly).toBe(true);
    expect(text(p.q('.gw-secret[data-state=stored]'))).toContain('version 3');
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
    p.q<HTMLButtonElement>('#edit-test')!.click();
    p.harness.detectChanges();
    expect(p.q('#edit-test')!.getAttribute('aria-busy')).toBe('true');
    expect(text(p.q('#edit-checking'))).toBe('Checking dev-db');
    await p.settle();
    expect(p.q('#edit-test')!.hasAttribute('aria-busy')).toBe(false);
    expect(TestBed.inject(ToastService).toasts().map((t) => [t.title, t.body])).toEqual([['dev-db is healthy', 'Answered in 12 ms.']]);
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

  it('keeps production writes Off when the first connection joins the empty default endpoint', async () => {
    useDefault();
    const p = await open();
    await toEndpoint(p, 'prod');
    expect(text(p.q('#pv-default-t'))).toBe('qa-db environment prod on endpoint default');
    expect(text(p.q('#step-4 .gw-tool-policy-list .gw-card__description'))).toBe('3 tools · 2 read only · 1 off');
    p.button('Save connection').click();
    await p.settle();
    expect(core.endpointsCreated).toEqual([]);
    expect(core.added).toEqual(['default']);
    expect(core.modes).toEqual([{ slug: 'default', toolKey: 'sql_execute', connection: 'qa-db', mode: 'OFF', version: 1 }]);
    expect(text(p.q('#fr-done'))).toContain('sql_execute is off: prod is read only by default');
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
    expect(core.modes).toEqual([{ slug: 'prod', toolKey: 'sql_execute', connection: 'qa-db', mode: 'OFF', version: 1 }]);
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
    expect(text(p.q('#step-1 .gw-section__description'))).toBe('The type decides what you fill in next and what your AI apps can do with it.');
    expect(p.steps()).toEqual(['current:1 Choose type', 'upcoming:2 Configure', 'upcoming:3 Test', 'upcoming:4 Create endpoint']);
  });

  it('Configure in plain words; the secret stays encrypted here, the secret managers wait behind their disclosure', async () => {
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
    const lock = () => Array.from(p.el.querySelectorAll('#cf-form .gw-text-small.gw-fg-secondary')).map(text);
    expect(lock()).toEqual(['The password is stored encrypted on this computer. Nobody can view it afterwards, not even you.']);
    const toggle = p.button('Use a secret manager instead (Vault, GCP, AWS)', p.q('#cf-form')!);
    const sources = document.getElementById('cf-src-field')!;
    expect([toggle.getAttribute('aria-expanded'), toggle.getAttribute('aria-controls'), sources.hidden]).toEqual(['false', 'cf-src-field', true]);
    toggle.click();
    await p.settle();
    expect([toggle.getAttribute('aria-expanded'), sources.hidden]).toEqual(['true', false]);
    // Every line about the secret follows the source picked.
    sources.querySelector<HTMLInputElement>('input[value="vault"]')!.click();
    await p.settle();
    expect(lock()).toEqual(['The secret stays in your secret manager. GateRook reads it only when a call needs it.']);
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
      'complete:Choose type, completed PostgreSQL',
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

  it('prod: the endpoint is prod and its tools that would wait for approval start Off', async () => {
    core.lastBackupAt = '2026-10-20T09:00:00Z';
    const p = await open();
    await toEndpoint(p, 'prod');
    expect(text(p.q('#pv-title + .gw-form__description'))).toBe(
      'Reads run at once. sql_execute is off: prod is read only by default. You can change the modes later.',
    );
    expect(text(p.q('#pv-prod-t'))).toBe('qa-db environment prod on endpoint prod');
    expect(text(p.q('#step-4 .gw-tool-policy-list .gw-card__description'))).toBe('3 tools · 2 read only · 1 off');
    expect(text(p.q('#step-4 .gw-tool-policy-list__row[data-mode="off"] .gw-tool-policy-list__mode-note'))).toBe('Off on prod: read only by default');
    p.button('Save connection').click();
    await p.settle();
    expect(core.endpointsCreated).toEqual([{ slug: 'prod', startFrom: 'empty' }]);
    expect(core.added).toEqual(['prod']);
    expect(core.modes).toEqual([{ slug: 'prod', toolKey: 'sql_execute', connection: 'qa-db', mode: 'OFF', version: 1 }]);
    expect(kv('#fr-done').slice(1)).toEqual([
      ['Tools', 'sql_list_schemas and sql_query run at once (read only); sql_execute is off: prod is read only by default'],
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
});


/** The actual connector schema also exercises conditional password versus local key-file fields. */
function sshLogs() {
  core.spec = { ...SPEC, type: 'ssh-logs', name: 'SSH log files', schema: CONNECTOR_FORMS['ssh-logs'] };
  core.catalog = { groups: [{ name: 'Logs', cards: [{ ...CATALOG.groups[0].cards[0], type: 'ssh-logs', name: 'SSH log files', icon: 'scroll-text' }] }], installed: [] };
  core.detail = { ...structuredClone(DETAIL), type: 'ssh-logs', typeName: 'SSH log files',
    config: { connection: { host: 'logs.example.test', username: 'reader' }, credentials: { method: 'password' },
      logs: { root: '/var/log', allowedFiles: ['app.log'] } } };
}

describe('connection-new: SSH remote files', () => {
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
    core.nextFiles = HOST_KEY;
    p.button('Send and browse folders', p.q('#dlg-new-host')!).click(); await p.settle();
    core.pendingConfirm = Promise.resolve({ kind: 'host-key', config: { hostKeys: { pinned: ['ssh-ed25519 AAAA'] } }, retest: OK });
    core.nextFiles = outcome({ ok: true, details: { path: '/var/log', parent: '/var', entries: [], truncated: false } });
    p.button('Trust host key and browse').click(); await p.settle();
    expect(core.fileRequests).toHaveLength(3);
    expect(core.fileRequests[2]).toMatchObject({ confirm: 'send-secret-to-new-host',
      config: { connection: { host: 'other.example.test' }, hostKeys: { pinned: ['ssh-ed25519 AAAA'] } } });
    await p.type('cf-connection-host', 'different.example.test');
    p.button('Browse folders').click(); await p.settle();
    expect(core.fileRequests[3].confirm).toBeUndefined();
  });

  it('does not restore file permissions removed while host-key confirmation is in flight', async () => {
    sshLogs();
    const p = await open('/connections/dev-db');
    core.nextFiles = HOST_KEY;
    p.button('Browse folders').click(); await p.settle();
    let finish!: (value: TestConfirmed) => void;
    core.pendingConfirm = new Promise((resolve) => { finish = resolve; });
    p.button('Trust host key and browse').click(); await p.settle();
    p.q<HTMLButtonElement>('button[aria-label="Remove allowed file app.log"]')!.click(); await p.settle();
    core.nextFiles = outcome({ ok: true, details: { path: '/var/log', parent: '/var', entries: [], truncated: false } });
    finish({ kind: 'host-key', config: { ...(core.detail as ConnectionDetail).config, hostKeys: { pinned: ['ssh-ed25519 AAAA'] } }, retest: OK });
    await p.settle();
    expect(p.page().form()!.files()!.selected()).toEqual([]);
    expect(p.page().form()!.value().config['hostKeys']).toEqual({ pinned: ['ssh-ed25519 AAAA'] });
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
