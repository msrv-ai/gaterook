import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { CONNECTOR_FORMS } from '../../../testing/connector-schemas';
import { ApiError } from '../../core/api/api-error';
import { ApprovalItem, ApprovalsApi } from '../../core/api/approvals.api';
import { ConnectionDetail, ConnectionsApi, CreateConnectionRequest, TestDraftRequest, TestOutcome, UpdateConnectionRequest } from '../../core/api/connections.api';
import { ConnectionFormSpec, ConnectorCatalog, ConnectorTool, ConnectorTypeDetail, ConnectorTypesApi } from '../../core/api/connector-types.api';
import { ConnectionPreview, EndpointSummary, EndpointsApi } from '../../core/api/endpoints.api';
import { Policies, PoliciesApi } from '../../core/api/policies.api';
import { SecretRow, SecretsApi } from '../../core/api/secrets.api';
import { JsonObject } from '../../core/api/types';
import { EVENT_STREAM_FETCH, EventStream } from '../../core/events/event-stream';
import { TunnelStore } from '../../core/stores/tunnel.store';
import { TimeService } from '../../core/time/time.service';
import { Announcer } from '../../kit/announcer';
import { AriaDisabledGuard } from '../../kit/aria-disabled';
import { provideKitIcons } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { ConnectionNewPage } from './connection-new.page';

/**
 * Add connection against the kit's page script (design/pages/connection-new.html): the four steps with their focus
 * and announcements, what the test shows, the endpoints a connection may join, Save, the duplicate and the edit view
 * with its dialogs. The admin API is faked at the service level with the demo world's PostgreSQL (BLUEPRINT §9).
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
  needsConfirmation: { kind: 'host-key', text: 'MCP Gateway has not seen this server before.', data: { host: 'db.dev.internal', port: 22, keyType: 'ssh-ed25519', fingerprint: 'SHA256:9mX0bZ3J' } },
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
  tests: TestDraftRequest[] = [];
  nextTest: TestOutcome | ApiError = OK;
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
}

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
  };
  const connections: Partial<ConnectionsApi> = {
    testDraft: vi.fn(async (r: TestDraftRequest) => {
      core.tests.push(structuredClone(r));
      if (core.nextTest instanceof ApiError) throw core.nextTest;
      return structuredClone(core.nextTest);
    }),
    confirmDraft: vi.fn(async () => ({ kind: 'host-key', config: { connection: { hostKey: 'ssh-ed25519 AAAA' } }, retest: OK })),
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
    list: vi.fn(async () => ({ items: [{ name: 'dev-db', host: 'db.dev.internal:5432/app' }], facets: {}, total: 1 }) as never),
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
  const endpoints: Partial<EndpointsApi> = {
    list: vi.fn(async () => structuredClone(ENDPOINTS)),
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
      { provide: TunnelStore, useValue: { summary: signal('off') } },
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
  await p.type('cf-connection-database', 'app');
  await p.type('cf-credentials-user', 'app_rw');
  await p.type('cf-credentials-password', 's3cret-value');
  p.button('Continue', p.q('#cf-form')!).click();
  await p.settle();
}

describe('Add connection: wizard', () => {
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
          connection: { host: 'db.dev.internal', port: 5432, database: 'app', sslMode: 'verify-full' },
          credentials: { user: 'app_rw' },
          advanced: { poolSize: 2, defaultSchema: 'public', connectTimeoutMs: 5000 },
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
