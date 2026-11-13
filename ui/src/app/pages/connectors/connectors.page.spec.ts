import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { ApiError } from '../../core/api/api-error';
import { ConnectionRow, ConnectionsApi } from '../../core/api/connections.api';
import { ConnectorCatalog, ConnectorTypesApi } from '../../core/api/connector-types.api';
import { EndpointsApi } from '../../core/api/endpoints.api';
import { ConnectorModules, ModulesApi } from '../../core/api/modules.api';
import { CatalogIndex, McpProfile, McpRegistryPage, McpServersApi, PluginCheck, PluginsApi } from '../../core/api/plugins.api';
import { SecretsApi } from '../../core/api/secrets.api';
import { Template, TemplateSaved, TemplatesApi } from '../../core/api/templates.api';
import { EventStream } from '../../core/events/event-stream';
import { CoreStore } from '../../core/stores/core.store';
import { TimeService } from '../../core/time/time.service';
import { AriaDisabledGuard } from '../../kit/aria-disabled';
import { provideKitIcons } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import {
  NOW,
  ORDERS_DEFINITION,
  catalogEntries,
  catalogIndex,
  clickhouseCheck,
  connectionRows,
  endpointTools,
  endpoints,
  installedTypes,
  moduleCheck,
  moduleList,
  ownModuleType,
  rabbitUpdate,
  secretRows,
  sentryReview,
  serverDetail,
  templates,
  typeDetail,
  typesCatalog,
  typesWithListedModules,
} from './connectors.fixtures';
import { ConnectorsData } from './connectors.data';
import { ConnectorsPage, SKELETON_DELAY_MS } from './connectors.page';

/** The page against fake admin API services: what it shows for each answer, and what it asks the core to do. */

const now = signal(Date.parse(NOW));
const fakeTime: Partial<TimeService> = {
  now,
  minute: now,
  relative: (t) => (t ? `rel ${t.slice(0, 10)}` : ''),
  absolute: (t) => (t ? `abs ${t}` : ''),
  iso: (t) => t ?? '',
};

function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

class FakeEvents {
  readonly handlers = new Map<string, Set<(data: unknown) => void>>();
  on(type: string, h: (data: unknown) => void): () => void {
    if (!this.handlers.has(type)) this.handlers.set(type, new Set());
    this.handlers.get(type)!.add(h);
    return () => this.handlers.get(type)!.delete(h);
  }
  emit(type: string, data: unknown): void {
    this.handlers.get(type)?.forEach((h) => h(data));
  }
}

const notAvailable = () => new ApiError('not-available', 404, {});

/** The core's warning for a URL that carries a key (McpServerService.KEY_IN_URL). */
const KEY_IN_URL =
  "The URL carries a key. GateRook stores it with the server's settings, not as an encrypted secret, and the provider or a proxy may log it. If the server accepts the key in a header, add that header instead.";

/** Every answer of the fake core, replaceable per test. */
interface Answers {
  types: () => Promise<ConnectorCatalog>;
  catalog: () => Promise<CatalogIndex>;
  templates: () => Promise<Template[]>;
  templateKinds: Record<string, string[]>;
  serverTools: () => Promise<unknown[]>;
  rows: () => ConnectionRow[];
  update: () => ReturnType<typeof rabbitUpdate>;
  registry: (search: string, cursor: string | null) => Promise<McpRegistryPage>;
  profiles: () => Promise<McpProfile[]>;
  /** What the core finds in a JAR chosen on this computer: a plugin, or a connector module. */
  fileCheck: () => Promise<PluginCheck>;
  /** GateRook's connector modules and the state of their signed list. */
  modules: () => Promise<ConnectorModules>;
  /** What Check now gets: both lists after the core checked them again. By default the lists as they are. */
  catalogCheck?: () => Promise<CatalogIndex>;
  modulesCheck?: () => Promise<ConnectorModules>;
}

/**
 * The MCP registry's answers (§7.5), shaped like the official registry's on 2026-11-02: Linear's remote and an entry
 * that runs only as a Docker image, then a second page with an npm package.
 */
function registryPage(search: string, cursor: string | null): Promise<McpRegistryPage> {
  if (cursor) {
    return Promise.resolve({
      registry: 'registry.modelcontextprotocol.io',
      nextCursor: null,
      servers: [{ name: 'io.github.someone/linear-mcp', description: `${search} from the command line.`, status: 'active', remotes: [],
        packages: [{ registryType: 'npm', identifier: '@someone/linear-mcp', version: '0.3.2', transport: 'stdio',
          environmentVariables: [{ name: 'LINEAR_API_KEY', required: true, secret: true }], runtimeArguments: [], packageArguments: [] }] }],
    });
  }
  return Promise.resolve({
    registry: 'registry.modelcontextprotocol.io',
    nextCursor: 'app.linear/linear:1.0.2',
    servers: [
      { name: 'app.linear/linear', title: 'Linear', version: '1.0.2', description: 'Issues, projects and cycles.', status: 'active', packages: [],
        remotes: [{ type: 'streamable-http', url: 'https://mcp.linear.app/mcp', headers: [], variables: [] }] },
      { name: 'io.github.someone/image-only', status: 'deprecated', remotes: [],
        packages: [{ registryType: 'oci', identifier: 'ghcr.io/someone/linear:1', transport: 'stdio', environmentVariables: [], runtimeArguments: [], packageArguments: [] }] },
    ],
  });
}

const stripeRegistryServers: McpRegistryPage['servers'] = [
  { name: 'app.vercel.stripecheckup/stripecheckup', title: 'stripecheckup', status: 'active', packages: [],
    remotes: [{ type: 'streamable-http', url: 'https://stripecheckup.vercel.app', headers: [], variables: [] }] },
  { name: 'com.insightfulpipe/stripe', title: 'Official Stripe integration', status: 'active', packages: [],
    listing: { verification: 'vendor', featured: true },
    remotes: [{ type: 'streamable-http', url: 'https://stripe.insightfulmcp.com', headers: [], variables: [] }] },
  { name: 'com.stripe/mcp', title: 'mcp', version: '0.2.4', status: 'active', packages: [],
    remotes: [{ type: 'streamable-http', url: 'https://mcp.stripe.com', headers: [], variables: [] }] },
];

/** GateRook's verified profiles as `GET /api/mcp-profiles` answers (§7.5): one whose registry entry is a Popular card. */
function profilesFixture(): McpProfile[] {
  const base = { category: 'Cloud platform', checked: '2026-11-11', command: [], headers: [], notes: [], signIn: 'OAuth in your browser.' };
  return [
    { ...base, id: 'supabase', name: 'Supabase', publisher: 'Supabase', description: 'Projects, logs and read-only SQL.',
      documentation: 'https://supabase.com/docs/guides/getting-started/mcp', registryName: 'com.supabase/mcp', transport: 'http',
      url: 'https://mcp.supabase.com/mcp?project_ref={project_ref}&read_only=true',
      placeholders: [{ name: 'project_ref', description: 'Your project’s reference, from its dashboard URL.' }],
      pins: [{ in: 'query', name: 'read_only', setTo: 'true', purpose: 'Supabase lists only its read tools.', missing: 'Without read_only=true, Supabase lists its writes too.', set: null }],
      tools: [] },
    { ...base, id: 'cloudflare-api', name: 'Cloudflare API', publisher: 'Cloudflare', description: 'The whole Cloudflare API in Code Mode.',
      documentation: 'https://github.com/cloudflare/mcp', registryName: null, transport: 'http', url: 'https://mcp.cloudflare.com/mcp',
      placeholders: [], pins: [], tools: [{ tool: 'execute', start: 'approval', kind: 'code', reason: 'It runs JavaScript against the Cloudflare API.' }] },
  ];
}

let calls: string[];
let bodies: Record<string, unknown>;
let events: FakeEvents;
let answers: Answers;

function setup(over: Partial<Answers> = {}): void {
  calls = [];
  bodies = {};
  events = new FakeEvents();
  answers = {
    types: () => Promise.resolve(typesCatalog()),
    catalog: () => Promise.resolve(catalogIndex()),
    templates: () => Promise.resolve(templates()),
    templateKinds: {},
    serverTools: () => Promise.resolve(new Array(26).fill({})),
    rows: connectionRows,
    update: rabbitUpdate,
    registry: registryPage,
    profiles: () => Promise.resolve([]),
    fileCheck: () => Promise.resolve(clickhouseCheck()),
    modules: () => Promise.resolve(moduleList()),
    ...over,
  };
  const record = (name: string, body?: unknown) => {
    calls.push(name);
    if (body !== undefined) bodies[name] = body;
  };
  const typesApi: Partial<ConnectorTypesApi> = {
    catalog: () => {
      record('types');
      return answers.types();
    },
    get: (t) => Promise.resolve(typeDetail(t, answers.templateKinds[t] ?? [])),
    enable: (t) => {
      record(`enable-type ${t}`);
      return Promise.resolve(typeDetail(t).type);
    },
    confirmIntegrity: (t) => {
      record(`keep-type ${t}`);
      return Promise.resolve(typeDetail(t).type);
    },
  };
  const connections: Partial<ConnectionsApi> = {
    list: () => {
      record('connections');
      const items = answers.rows();
      return Promise.resolve({ items, facets: { health: {}, env: {}, type: {} }, total: items.length });
    },
    get: (name) => {
      record(`connection ${name}`);
      return Promise.resolve(serverDetail(name));
    },
  };
  const plugins: Partial<PluginsApi> = {
    ensureRuntime: async () => {},
    catalog: () => {
      record('catalog');
      return answers.catalog();
    },
    refreshCatalog: () => {
      record('catalog-refresh');
      return (answers.catalogCheck ?? answers.catalog)();
    },
    check: (source) => {
      record('check', source);
      return Promise.resolve(clickhouseCheck());
    },
    checkFile: (file) => {
      record(`check-file ${file.name}`);
      return answers.fileCheck();
    },
    install: (stagingId, ack) => {
      record(`install ${stagingId} ${ack}`);
      return Promise.resolve({});
    },
    action: (id, action, body) => {
      record(`plugin ${action} ${id}`, body);
      return Promise.resolve({});
    },
    updatePreview: (id, to) => {
      record(`update-preview ${id} ${to}`);
      return Promise.resolve(answers.update());
    },
    uninstall: (id) => {
      record(`uninstall ${id}`);
      return Promise.resolve();
    },
  };
  const servers: Partial<McpServersApi> = {
    tools: (name) => {
      record(`server-tools ${name}`);
      return answers.serverTools() as Promise<never>;
    },
    review: (source) => {
      record('review', source);
      if ('config' in source && source.config.transport === 'http') {
        // Over HTTP the core shows a key the URL carries masked, and warns; this server answers without a sign-in.
        const url = source.config.url ?? '';
        return Promise.resolve(
          sentryReview({
            name: source.config.name,
            transport: 'http',
            url: url.replace(/apiKey=[^&#]*/, 'apiKey=‹masked›'),
            argv: [],
            env: [],
            auth: { kind: 'none' },
            warnings: /[?&]apiKey=/.test(url) ? [KEY_IN_URL] : [],
          }),
        );
      }
      // A catalog entry is reviewed by the core's name, pin and resolved command, with the entry's variable (K6).
      return Promise.resolve(
        'catalog' in source
          ? sentryReview({
              name: 'github-mcp',
              catalogEntry: source.catalog,
              argv: ['node', '/Users/me/.gaterook/mcp/github-mcp/dist/index.js'],
              resolvedCommand: 'node /Users/me/.gaterook/mcp/github-mcp/dist/index.js',
              env: ['GITHUB_PERSONAL_ACCESS_TOKEN'],
            })
          : sentryReview(),
      );
    },
    create: (source) => {
      record('create-server', source);
      // The core answers with the name the server got: a catalog entry's own, else the typed one.
      const name = 'catalog' in source ? 'github-mcp' : source.config.name;
      return Promise.resolve({ name, state: 'running', tools: 12 });
    },
    searchRegistry: (search, cursor) => {
      record(`registry ${search}${cursor ? ` after ${cursor}` : ''}`);
      return answers.registry(search, cursor ?? null);
    },
    profiles: () => answers.profiles(),
    action: (name, action) => {
      record(`server ${action} ${name}`);
      return Promise.resolve({ name, state: 'running', tools: 26, previousTools: 26 });
    },
    delete: (name) => {
      record(`delete-server ${name}`);
      return Promise.resolve();
    },
  };
  const templatesApi: Partial<TemplatesApi> = {
    list: () => {
      record('templates');
      return answers.templates();
    },
    get: (id) => {
      record(`template ${id}`);
      const t = templates().find((x) => x.id === id)!;
      return Promise.resolve({ ...t, definition: id === 't1' ? ORDERS_DEFINITION : null });
    },
    create: (request) => {
      record('create-template', request);
      const name = (request.definition as { name: string } | undefined)?.name ?? 'pg_slow_queries';
      return Promise.resolve({
        template: { ...templates()[0], id: 't9', name },
        compile: { ok: true, tools: [], errors: [], warnings: [] },
      } as never);
    },
    delete: (id) => {
      record(`delete-template ${id}`);
      return Promise.resolve();
    },
  };
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      provideKitIcons(),
      { provide: TimeService, useValue: fakeTime },
      { provide: ConnectorTypesApi, useValue: typesApi },
      { provide: ConnectionsApi, useValue: connections },
      { provide: PluginsApi, useValue: plugins },
      {
        provide: ModulesApi,
        useValue: {
          list: () => {
            record('modules');
            return answers.modules();
          },
          refresh: () => {
            record('modules-refresh');
            return (answers.modulesCheck ?? answers.modules)();
          },
          uninstall: (id: string) => {
            record(`uninstall-module ${id}`);
            return Promise.resolve();
          },
          replaceOwn: (id: string) => {
            record(`replace-own ${id}`);
            return Promise.resolve({} as never);
          },
        } satisfies Partial<ModulesApi>,
      },
      { provide: McpServersApi, useValue: servers },
      { provide: TemplatesApi, useValue: templatesApi },
      {
        provide: EndpointsApi,
        useValue: {
          list: () => Promise.resolve(endpoints()),
          tools: (slug: string) => Promise.resolve(endpointTools(slug)),
          previewConnection: (slug: string, connection: string) => {
            record(`preview ${slug} ${connection}`);
            return Promise.resolve({ connection, env: 'dev', tools: [], warnings: [], refusal: null });
          },
          addConnection: (slug: string, connection: string) => {
            record(`add-to-endpoint ${slug} ${connection}`);
            return Promise.resolve({});
          },
        },
      },
      { provide: SecretsApi, useValue: { list: () => Promise.resolve(secretRows()) } },
      { provide: EventStream, useValue: events },
      { provide: CoreStore, useValue: { running: signal(true), instanceChanges: signal(0), version: signal('0.4.0') } },
    ],
  });
  TestBed.inject(AriaDisabledGuard).install();
}

afterEach(() => {
  document.body.innerHTML = '';
  TestBed.inject(ToastService).clear();
  localStorage.clear();
});

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function render(): Promise<{ fixture: ComponentFixture<ConnectorsPage>; el: HTMLElement }> {
  const fixture = TestBed.createComponent(ConnectorsPage);
  document.body.appendChild(fixture.nativeElement);
  await settle(fixture);
  return { fixture, el: fixture.nativeElement as HTMLElement };
}

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  for (let i = 0; i < 5; i++) {
    await fixture.whenStable();
    await sleep(0);
  }
  fixture.detectChanges();
  await fixture.whenStable();
}

const text = (el: Element | null | undefined) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
const toasts = () => TestBed.inject(ToastService).toasts();
const cardTitles = (el: HTMLElement) => Array.from(el.querySelectorAll('#installed-grid .gw-card__title')).map(text);
const cardOf = (el: HTMLElement, panel: string, name: string) =>
  Array.from(el.querySelectorAll<HTMLElement>(`#${panel} article`)).find((a) => text(a.querySelector('.gw-card__title')) === name) ?? null;

function button(root: Element | null, label: string): HTMLButtonElement {
  const found = Array.from(root?.querySelectorAll<HTMLButtonElement>('button') ?? []).find((b) => text(b).startsWith(label));
  if (!found) throw new Error(`No button "${label}"`);
  return found;
}

function type(input: HTMLInputElement | HTMLTextAreaElement, value: string): void {
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

async function searchInlineRegistry(fixture: ComponentFixture<ConnectorsPage>, el: HTMLElement, query: string) {
  const region = el.querySelector<HTMLElement>('section[gwMcpRegistrySearch]')!;
  const search = region.querySelector<HTMLInputElement>('input[type="search"]')!;
  type(search, query);
  region.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
  await settle(fixture);
  return { region, search, results: () => Array.from(region.querySelectorAll('article')) };
}

/**
 * Router.navigate, stubbed: the page's links to Add connection, which has no route here. Returns the navigations to
 * `/connections/new` so far, as `[commands, extras]`.
 */
function navigations(): () => unknown[][] {
  const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  return () => navigate.mock.calls.filter(([commands]) => Array.isArray(commands) && commands[0] === '/connections/new');
}

function choose(select: HTMLSelectElement, value: string): void {
  select.value = value;
  select.dispatchEvent(new Event('change', { bubbles: true }));
}

function check(box: HTMLInputElement): void {
  box.checked = true;
  box.dispatchEvent(new Event('change', { bubbles: true }));
}

describe('Connectors page: Installed', () => {
  it('shows nothing for a moment, then placeholders, then the kit’s cards and tab counts', async () => {
    const answer = deferred<ConnectorCatalog>();
    setup({ types: () => answer.promise });
    // The first render, before the 300 ms pass (settling here could take longer than that on a busy machine).
    const fixture = TestBed.createComponent(ConnectorsPage);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(text(el.querySelector('h1'))).toBe('Connectors');
    expect(el.querySelectorAll('.gw-page-header .gw-btn--primary')).toHaveLength(1);
    expect(el.querySelector('#installed .gw-skeleton')).toBeNull();

    await sleep(SKELETON_DELAY_MS + 50);
    await settle(fixture);
    expect(el.querySelectorAll('#installed .gw-skeleton').length).toBe(9);
    expect(text(el.querySelector('#installed [role="status"]'))).toBe('Loading installed connectors');

    answer.resolve(typesCatalog());
    await settle(fixture);
    expect(el.querySelector('#installed .gw-skeleton')).toBeNull();
    expect(cardTitles(el)).toHaveLength(12);
    expect(cardTitles(el).slice(-2)).toEqual(['RabbitMQ', 'github-mcp']);
    expect(text(el.querySelector('#installed-count'))).toBe('10 built-in, 1 plugin, 1 MCP server');
    expect(Array.from(el.querySelectorAll('.gw-tabs__count')).map(text)).toEqual(['12 connectors', '5 connectors', '4 templates', '1 server']);

    const pg = cardOf(el, 'installed', 'PostgreSQL')!;
    expect(text(pg.querySelector('[gwConnectorCardNote]'))).toBe('2 connections use PostgreSQL · View activity for PostgreSQL');
    const rabbit = cardOf(el, 'installed', 'RabbitMQ')!;
    // The catalog has 0.4.0: the footer offers the update, the card its button.
    expect(text(rabbit.querySelector('.gw-card__footer'))).toContain('Update available · v0.4.0');
    expect(text(button(rabbit, 'Update'))).toBe('Update RabbitMQ to v0.4.0');
    const server = cardOf(el, 'installed', 'github-mcp')!;
    expect(text(server.querySelector('[gwConnectorCardNote]'))).toBe('Not started yet, see github-mcp in MCP servers · on dev');
  });

  it('filters as the person types, with the kit’s empty state and Clear search', async () => {
    setup();
    const { fixture, el } = await render();
    const search = el.querySelector<HTMLInputElement>('#installed-search')!;
    type(search, 'rabbit');
    await settle(fixture);
    expect(cardTitles(el)).toEqual(['RabbitMQ']);
    expect(text(el.querySelector('#installed-count'))).toBe('1 of 12 installed connectors');

    type(search, 'nothing like this');
    await settle(fixture);
    expect(el.querySelector<HTMLElement>('#installed-grid')!.hidden).toBe(true);
    expect(text(el.querySelector('#installed .gw-empty__title'))).toBe('No installed connector matches');

    button(el.querySelector('#installed .gw-empty'), 'Clear search').click();
    await settle(fixture);
    expect(search.value).toBe('');
    expect(cardTitles(el)).toHaveLength(12);
  });

  it('says why the types didn’t load, and loads them again on Try again', async () => {
    let fail = true;
    setup({ types: () => (fail ? Promise.reject(new ApiError('server', 500, { detail: 'The database is busy.' })) : Promise.resolve(typesCatalog())) });
    const { fixture, el } = await render();
    const alert = el.querySelector('#installed .gw-alert--danger')!;
    expect(text(alert.querySelector('.gw-alert__title'))).toBe('The installed connectors didn\'t load');
    expect(text(alert.querySelector('.gw-alert__body'))).toBe('The database is busy.');
    expect(el.querySelector('#installed-error-details')?.hasAttribute('hidden')).toBe(true);

    fail = false;
    button(alert, 'Try again').click();
    await settle(fixture);
    expect(el.querySelector('#installed .gw-alert--danger')).toBeNull();
    expect(cardTitles(el)).toHaveLength(12);
  });

  it('disables a plugin at once and offers Undo', async () => {
    setup();
    const { fixture, el } = await render();
    const rabbit = cardOf(el, 'installed', 'RabbitMQ')!;
    button(rabbit.querySelector('[role="menu"]'), 'Disable plugin').click();
    await settle(fixture);
    expect(calls).toContain('plugin disable rabbitmq');
    const toast = toasts().at(-1)!;
    expect(toast.title).toBe('RabbitMQ disabled');
    expect(toast.actions?.[0].label).toBe('Undo');

    toast.actions![0].onClick!();
    await settle(fixture);
    expect(calls).toContain('plugin enable rabbitmq');
  });

  it('asks before uninstalling, with focus on Cancel', async () => {
    setup();
    const { fixture, el } = await render();
    const rabbit = cardOf(el, 'installed', 'RabbitMQ')!;
    button(rabbit.querySelector('[role="menu"]'), 'Uninstall plugin').click();
    await settle(fixture);
    const dialog = el.querySelector<HTMLDialogElement>('dialog[gwConfirmDialog]')!;
    expect(dialog.open).toBe(true);
    expect(text(dialog.querySelector('.gw-dialog__title'))).toBe('Uninstall plugin RabbitMQ?');
    expect(text(dialog.querySelector('.gw-dialog__description'))).toContain('No connection uses it and its 5 tools are on no endpoint, so nothing stops working.');
    expect(text(document.activeElement)).toBe('Cancel');
    expect(calls).not.toContain('uninstall rabbitmq');

    button(dialog, 'Uninstall plugin RabbitMQ').click();
    await settle(fixture);
    expect(calls).toContain('uninstall rabbitmq');
    expect(dialog.open).toBe(false);
    expect(toasts().at(-1)?.title).toBe('Plugin RabbitMQ uninstalled');
  });

  it('opens the update with its manifest diff and updates to the catalog’s version', async () => {
    setup();
    const { fixture, el } = await render();
    button(cardOf(el, 'installed', 'RabbitMQ'), 'Update').click();
    await settle(fixture);
    expect(calls).toContain('update-preview rabbitmq 0.4.0');
    const dialog = el.querySelector<HTMLDialogElement>('dialog[gwPluginUpdateDialog]')!;
    expect(dialog.open).toBe(true);
    expect(dialog.querySelector('gw-diff')).not.toBeNull();
    expect(text(document.activeElement)).toBe('Cancel');

    button(dialog, 'Update RabbitMQ to v0.4.0').click();
    await settle(fixture);
    expect(calls).toContain('plugin update rabbitmq');
    expect(bodies['plugin update rabbitmq']).toEqual({ to: '0.4.0' });
    expect(toasts().at(-1)?.title).toBe('RabbitMQ updated to v0.4.0');
  });

  it('asks like an unsigned install before an update loads an unsigned release (§9: danger and acknowledgement)', async () => {
    // Review 2026-10-26: the catalog may list an unsigned release (`signed: false`); the dialog was a plain primary
    // "Update" with a warning badge. The core refuses such an update without `acknowledgeUnsigned`.
    setup({ update: () => ({ ...rabbitUpdate(), signed: false }) });
    const { fixture, el } = await render();
    button(cardOf(el, 'installed', 'RabbitMQ'), 'Update').click();
    await settle(fixture);
    const dialog = el.querySelector<HTMLDialogElement>('dialog[gwPluginUpdateDialog]')!;
    expect(dialog.open).toBe(true);
    expect(dialog.classList).toContain('gw-dialog--danger');
    expect(text(dialog.querySelector('.gw-dialog__title'))).toBe('Update RabbitMQ to unsigned v0.4.0?');
    expect(text(document.activeElement)).toBe('Cancel');

    const confirm = button(dialog, 'Update to unsigned RabbitMQ v0.4.0');
    expect(confirm.getAttribute('aria-disabled')).toBe('true');
    confirm.click();
    await settle(fixture);
    expect(calls).not.toContain('plugin update rabbitmq');

    check(dialog.querySelector<HTMLInputElement>('input[type="checkbox"]')!);
    await settle(fixture);
    expect(confirm.hasAttribute('aria-disabled')).toBe(false);
    confirm.click();
    await settle(fixture);
    expect(bodies['plugin update rabbitmq']).toEqual({ to: '0.4.0', acknowledgeUnsigned: true });
    expect(toasts().at(-1)).toMatchObject({ tone: 'warning', title: 'RabbitMQ updated to unsigned v0.4.0' });

    // The next update starts unacknowledged again.
    button(cardOf(el, 'installed', 'RabbitMQ'), 'Update').click();
    await settle(fixture);
    expect(dialog.querySelector<HTMLInputElement>('input[type="checkbox"]')!.checked).toBe(false);
    expect(button(dialog, 'Update to unsigned RabbitMQ v0.4.0').getAttribute('aria-disabled')).toBe('true');
  });

  it('reloads on plugin.state and shows the state an mcp-server.state brings', async () => {
    setup();
    const { fixture, el } = await render();
    const before = calls.filter((c) => c === 'types').length;
    events.emit('plugin.state', { id: 'rabbitmq', state: 'stopped' });
    await settle(fixture);
    expect(calls.filter((c) => c === 'types').length).toBe(before + 1);

    events.emit('mcp-server.state', { name: 'github-mcp', state: 'running' });
    await settle(fixture);
    expect(text(cardOf(el, 'installed', 'github-mcp')!.querySelector('[gwConnectorCardNote] a'))).toBe('Running, see github-mcp in MCP servers');
  });
});

describe('Connectors page: a connector module from a file', () => {
  /** Opens Install plugin or module from the page's menu, chooses `name` and has the core check it. */
  async function checkFile(fixture: ComponentFixture<ConnectorsPage>, el: HTMLElement, name: string): Promise<HTMLDialogElement> {
    button(el.querySelector('#menu-add-connector'), 'Install plugin or module').click();
    await settle(fixture);
    const install = el.querySelector<HTMLDialogElement>('dialog[gwPluginInstallDialog]')!;
    expect(install.open).toBe(true);
    expect(text(install.querySelector('.gw-dialog__title'))).toBe('Install plugin or module');
    expect(text(install.querySelector('.gw-file-drop__hint'))).toBe('One plugin JAR, up to 50 MB, or one connector module, up to 150 MB');
    const input = install.querySelector<HTMLInputElement>('input[type="file"]')!;
    Object.defineProperty(input, 'files', { value: [new File(['jar'], name)], configurable: true });
    input.dispatchEvent(new Event('change', { bubbles: true }));
    await settle(fixture);
    button(install, 'Check package').click();
    await settle(fixture);
    expect(calls).toContain(`check-file ${name}`);
    expect(install.open).toBe(false);
    return el.querySelector<HTMLDialogElement>('dialog[gwPluginReviewDialog]')!;
  }

  const rowsOf = (dialog: HTMLElement) =>
    Array.from(dialog.querySelectorAll('.gw-kv__row')).map((r) => [text(r.querySelector('dt')), text(r.querySelector('dd'))] as const);

  it('reviews the person’s own module as unsigned code and installs it only with their word', async () => {
    setup({ fileCheck: () => Promise.resolve(moduleCheck()) });
    const { fixture, el } = await render();
    const dialog = await checkFile(fixture, el, 'shop-1.2.0.jar');
    expect(dialog.open).toBe(true);
    expect(dialog.classList).toContain('gw-dialog--danger');
    expect(text(dialog.querySelector('.gw-dialog__title'))).toBe('Install unsigned connector module Shop?');
    expect(text(dialog.querySelector('.gw-dialog__description'))).toContain("GateRook didn't sign this package");
    expect(text(document.activeElement)).toBe('Cancel');
    const rows = rowsOf(dialog);
    const row = (key: string) => rows.find(([k]) => k === key)?.[1];
    expect(row('Signature')).toBe("Unsigned Not in GateRook's signed list of connector modules");
    expect(row('Package')).toBe('shop-1.2.0.jar · 2.6 MB');
    expect(row('Manifest')).toBe('id shop, version 1.2.0');
    expect(row('Adds')).toBe('Orders: orders_list orders_cancel');
    expect(rows.map(([, value]) => value)).toContain('Shipments: shipments_list');
    expect(row('Tools')).toContain('3 Declared by shop, not verified. On an endpoint each starts as Approval; Read only needs your confirmation per tool.');
    expect(row('Tools')).toContain('What you approve is the call\'s arguments');
    expect(row('Needs access to')).toBe('The host of each connection, port 443');
    expect(row('Updates')).toContain("None from GateRook's list");
    expect(row('Replaces')).toBeUndefined();
    expect(row('Runs as')).toBeUndefined();

    const install = button(dialog, 'Install unsigned module Shop');
    expect(install.getAttribute('aria-disabled')).toBe('true');
    install.click();
    await settle(fixture);
    expect(calls.some((x) => x.startsWith('install '))).toBe(false);

    check(dialog.querySelector<HTMLInputElement>('input[type="checkbox"]')!);
    await settle(fixture);
    install.click();
    await settle(fixture);
    expect(calls).toContain('install stg-m1 true');
    expect(dialog.open).toBe(false);
    expect(toasts().at(-1)).toMatchObject({
      tone: 'warning',
      title: 'Unsigned connector module Shop installed',
      body: 'Add a connection to use it. On an endpoint each of its 3 tools starts as Approval.',
    });
  });

  it('installs a package GateRook’s signed list has as GateRook’s own connector, without asking for trust', async () => {
    setup({ fileCheck: () => Promise.resolve(moduleCheck(true, { installedVersion: '1.1.0', installedSigned: false })) });
    const { fixture, el } = await render();
    const dialog = await checkFile(fixture, el, 'shop-1.2.0.jar');
    expect(dialog.classList).not.toContain('gw-dialog--danger');
    expect(text(dialog.querySelector('.gw-dialog__title'))).toBe('Install Shop connector');
    const rows = rowsOf(dialog);
    const row = (key: string) => rows.find(([k]) => k === key)?.[1];
    expect(row('Signature')).toBe("Signed GateRook's signed list of connector modules has this package");
    expect(row('Replaces')).toBe('Shop v1.1.0, your own build. Requests that wait for approval on its connections are rejected.');
    expect(row('Tools')).toBe('3, in the modes the connector declares: on an endpoint reads start as Read only and the others as Approval.');
    expect(row('Updates')).toBeUndefined();
    expect(dialog.querySelector('input[type="checkbox"]')).toBeNull();

    button(dialog, 'Install Shop connector').click();
    await settle(fixture);
    expect(calls).toContain('install stg-m1 false');
    expect(toasts().at(-1)).toMatchObject({ tone: 'success', title: 'Shop connector installed' });
  });

  it('lists a type of the person’s own module as a module, unsigned, and removes the module on request', async () => {
    setup({ types: () => Promise.resolve(typesCatalog([...installedTypes(), ownModuleType(), ownModuleType(null, { id: 'shipments', name: 'Shipments', tools: 1, connections: 0 })])) });
    const { fixture, el } = await render();
    expect(text(el.querySelector('#installed-count'))).toBe('10 built-in, 2 from your own module, 1 plugin, 1 MCP server');
    const orders = cardOf(el, 'installed', 'Orders')!;
    expect(Array.from(orders.querySelectorAll('.gw-connector-card__meta .gw-badge')).map(text)).toEqual(['Module', 'Unsigned']);
    expect(text(orders.querySelector('.gw-connector-card__meta .gw-mono'))).toBe('v1.2.0');
    expect(text(orders.querySelector('[gwConnectorCardNote]'))).toBe('2 tools, each starts as Approval · no connections yet · Add connection for Orders');
    expect(text(orders.querySelector('.gw-card__footer .gw-status'))).toBe('Sleeping');
    const menu = orders.querySelector('[role="menu"]')!;
    expect(Array.from(menu.querySelectorAll('[role="menuitem"]')).map((item) => text(item.querySelector('.gw-menu__label')))).toEqual(['Uninstall module']);

    button(menu, 'Uninstall module').click();
    await settle(fixture);
    const dialog = el.querySelector<HTMLDialogElement>('dialog[gwConfirmDialog]')!;
    expect(dialog.open).toBe(true);
    expect(text(dialog.querySelector('.gw-dialog__title'))).toBe('Uninstall module shop?');
    expect(text(dialog.querySelector('.gw-dialog__description'))).toBe(
      'Its code is unloaded, its package deleted and the connector types Orders and Shipments removed. No connection uses it, so nothing stops working. Install it from its file again to use it.',
    );
    expect(text(document.activeElement)).toBe('Cancel');
    expect(calls).not.toContain('uninstall-module shop');

    button(dialog, 'Uninstall module shop').click();
    await settle(fixture);
    expect(calls).toContain('uninstall-module shop');
    expect(dialog.open).toBe(false);
    expect(toasts().at(-1)?.title).toBe('Module shop uninstalled');
  });

  it('says that connections stand in the way of removing a module, and returns to GateRook’s build where its list has one', async () => {
    setup({ types: () => Promise.resolve(typesCatalog([...installedTypes(), ownModuleType('1.3.0', { connections: 2 })])) });
    const { fixture, el } = await render();
    const orders = cardOf(el, 'installed', 'Orders')!;
    expect(text(orders.querySelector('.gw-card__footer .gw-status'))).toBe('GateRook’s build available · v1.3.0');
    const menu = orders.querySelector('[role="menu"]')!;
    expect(Array.from(menu.querySelectorAll('[role="menuitem"]')).map((item) => text(item.querySelector('.gw-menu__label')).split('Replace')[0])).toEqual([
      "Use GateRook's build",
      'Uninstall module',
    ]);

    button(menu, 'Uninstall module').click();
    await settle(fixture);
    const dialog = el.querySelector<HTMLDialogElement>('dialog[gwConfirmDialog]')!;
    expect(text(dialog.querySelector('.gw-dialog__description'))).toContain('2 connections use it: remove those connections first, or the module stays.');
    button(dialog, 'Cancel').click();
    await settle(fixture);

    button(menu, "Use GateRook's build").click();
    await settle(fixture);
    expect(text(dialog.querySelector('.gw-dialog__title'))).toBe("Replace your Orders module with GateRook's v1.3.0?");
    expect(dialog.classList).toContain('gw-dialog--warning');
    button(dialog, "Use GateRook's v1.3.0").click();
    await settle(fixture);
    expect(calls).toContain('replace-own shop');
    expect(toasts().at(-1)?.title).toBe("GateRook's Orders v1.3.0 is installing");

    // The installation's steps reach the page as events: the cards follow them.
    const before = calls.filter((x) => x === 'types').length;
    events.emit('module.state', { id: 'shop', version: '1.3.0', step: 'ready', sizeBytes: 1 });
    await settle(fixture);
    expect(calls.filter((x) => x === 'types').length).toBe(before + 1);
  });
});

/** The core's words when GitHub can't be reached for GateRook's list of connector modules. */
const LIST_UNREACHABLE = "The catalog can't be reached: index.json can't be downloaded from raw.githubusercontent.com: timeout";

describe('Connectors page: Catalog', () => {
  it('says the catalog isn’t available on a core without it, and still installs from a file', async () => {
    setup({ catalog: () => Promise.reject(notAvailable()) });
    const { fixture, el } = await render();
    expect(text(el.querySelector('#catalog .gw-empty__title'))).toBe('The catalog isn\'t available yet');
    expect(el.querySelector('#tab-catalog .gw-tabs__count')).toBeNull();

    button(el.querySelector('#catalog'), 'Install from file or URL').click();
    await settle(fixture);
    const dialog = el.querySelector<HTMLDialogElement>('dialog[gwPluginInstallDialog]')!;
    expect(dialog.open).toBe(true);
  });

  it('checks an unsigned plugin and keeps Install inert until the risk is acknowledged', async () => {
    setup();
    const { fixture, el } = await render();
    const clickhouse = cardOf(el, 'catalog', 'ClickHouse')!;
    button(clickhouse, 'Install').click();
    await settle(fixture);
    expect(bodies['check']).toEqual({ catalog: 'clickhouse@0.9.1' });

    const dialog = el.querySelector<HTMLDialogElement>('dialog[gwPluginReviewDialog]')!;
    expect(dialog.open).toBe(true);
    expect(dialog.classList).toContain('gw-dialog--danger');
    expect(text(dialog.querySelector('.gw-dialog__title'))).toBe('Install unsigned plugin ClickHouse?');
    expect(text(document.activeElement)).toBe('Cancel');
    const rows = Object.fromEntries(Array.from(dialog.querySelectorAll('.gw-kv__row')).map((r) => [text(r.querySelector('dt')), text(r.querySelector('dd'))]));
    expect(rows['Package']).toBe('gw-clickhouse-0.9.1.jar · 1.4 MB');
    expect(rows['Connection form']).toBe('Host, port, user and password, from its config schema');
    expect(rows['Needs access to']).toBe('The host of each connection, port 8443');
    expect(rows['Tools']).toContain('3, all read only Declared by clickhouse, not verified');
    // The fixture's core reports no sandbox on this system (ARCHITECTURE §7.5): the review says so.
    expect(rows['Runs as']).toBe("Your user, in a separate plugin process. Not sandboxed on this system: it can read everything your account can, including GateRook's data.");

    const install = button(dialog, 'Install unsigned plugin ClickHouse');
    expect(install.getAttribute('aria-disabled')).toBe('true');
    install.click();
    await settle(fixture);
    expect(calls.some((c) => c.startsWith('install '))).toBe(false);

    check(dialog.querySelector<HTMLInputElement>('input[type="checkbox"]')!);
    await settle(fixture);
    expect(install.hasAttribute('aria-disabled')).toBe(false);
    install.click();
    await settle(fixture);
    expect(calls).toContain('install stg-1 true');
    expect(dialog.open).toBe(false);
    expect(toasts().at(-1)).toMatchObject({ tone: 'warning', title: 'Unsigned plugin ClickHouse installed' });
  });

  it('installs a catalog template on a connection it can run on', async () => {
    setup();
    const { fixture, el } = await render();
    button(cardOf(el, 'catalog', 'PostgreSQL slow queries'), 'Install').click();
    await settle(fixture);
    const dialog = el.querySelector<HTMLDialogElement>('dialog[gwTemplateInstallDialog]')!;
    expect(dialog.open).toBe(true);
    const select = dialog.querySelector<HTMLSelectElement>('select')!;
    expect(Array.from(select.options).map((o) => o.value)).toEqual(['dev-db', 'stage-db']);
    expect(document.activeElement).toBe(select);

    button(dialog, 'Install template').click();
    await settle(fixture);
    expect(bodies['create-template']).toEqual({ kind: 'sql-query', connection: 'dev-db', catalogRef: 'pg-slow-queries@1.1.0' });
  });

  it('says why there is no catalog in the core’s words, and keeps one way to install from a file', async () => {
    // Review 2026-10-26: the core without a configured catalog answers offline, no entries and `problem`; the panel said
    // "The catalog is empty — Its index lists no plugins…", as if an index had come.
    setup({
      catalog: () =>
        Promise.resolve({ checkedAt: null, source: 'none', offline: true, signature: null, entries: [], problem: 'No catalog is configured for this build.' }),
    });
    const { fixture, el } = await render();
    const panel = el.querySelector('#catalog')!;
    expect(text(panel.querySelector('.gw-empty__title'))).toBe('The catalog isn\'t available');
    expect(text(panel.querySelector('.gw-empty__text'))).toBe('No catalog is configured for this build. A plugin you have as a file can still be checked and installed.');
    expect(Array.from(panel.querySelectorAll('button')).filter((b) => text(b).startsWith('Install from file or URL'))).toHaveLength(1);
    button(panel.querySelector('.gw-empty'), 'Install from file or URL').click();
    await settle(fixture);
    expect(el.querySelector<HTMLDialogElement>('dialog[gwPluginInstallDialog]')!.open).toBe(true);
  });

  it('says why the catalog shown may be old: the core’s problem over the last good index', async () => {
    setup({ catalog: () => Promise.resolve({ ...catalogIndex(), offline: true, problem: 'The catalog can\'t be reached: timeout.' }) });
    const { el } = await render();
    const alert = el.querySelector('#catalog .gw-alert--warning');
    expect(text(alert?.querySelector('.gw-alert__title'))).toBe('The catalog couldn\'t be checked');
    expect(text(alert?.querySelector('.gw-alert__body'))).toBe(
      'The catalog can\'t be reached: timeout. These are the entries of the last index GateRook verified.',
    );
    expect(cardOf(el, 'catalog', 'ClickHouse')).not.toBeNull();
  });

  it('offers GateRook’s own modules that aren’t installed first, with what adding a connection downloads', async () => {
    setup({ types: () => Promise.resolve(typesWithListedModules()), modules: () => Promise.resolve(moduleList({ configured: true, checkedAt: '2026-10-22T11:00:00Z', offline: false })) });
    const { fixture, el } = await render();
    const titles = Array.from(el.querySelectorAll('#catalog-grid .gw-card__title')).map(text);
    expect(titles.slice(0, 3)).toEqual(['DuckDB', 'ClickHouse', 'Jira']);
    // The plugin of the same name in the catalog's fixture stays an entry of its own.
    expect(titles.filter((t) => t === 'ClickHouse')).toHaveLength(2);
    expect(text(el.querySelector('#tab-catalog .gw-tabs__count'))).toBe('7 connectors');

    const duckdb = cardOf(el, 'catalog', 'DuckDB')!;
    expect(text(duckdb.querySelector('.gw-connector-card__meta'))).toBe('Built-in Version v1.1.0');
    expect(text(duckdb.querySelector('[gwConnectorCardNote]'))).toBe('Not part of the installer: adding a connection downloads it (32 MB).');
    expect(text(duckdb.querySelector('.gw-connector-card__tools'))).toBe('4 tools');
    // Nothing to install by hand and nothing to review: the one action opens the type's form, which brings the module.
    expect(duckdb.querySelector('button')).toBeNull();
    const add = duckdb.querySelector<HTMLAnchorElement>('a.gw-btn')!;
    expect(text(add)).toBe('Add connection for DuckDB');
    expect(add.getAttribute('href')).toBe('/connections/new?type=duckdb');
    expect(text(Array.from(el.querySelectorAll('#catalog article')).find((a) => text(a.querySelector('[gwConnectorCardNote]')).includes('(56 KB)'))?.querySelector('.gw-card__title'))).toBe('ClickHouse');

    const search = el.querySelector<HTMLInputElement>('#catalog-search')!;
    search.value = 'duck';
    search.dispatchEvent(new Event('input'));
    await settle(fixture);
    expect(Array.from(el.querySelectorAll('#catalog-grid .gw-card__title')).map(text)).toEqual(['DuckDB']);
    expect(text(el.querySelector('#catalog-count'))).toBe('1 of 7 catalog connectors');
  });

  it('shows GateRook’s modules in a build that reads no catalog, with the check of their list', async () => {
    setup({
      types: () => Promise.resolve(typesWithListedModules()),
      catalog: () =>
        Promise.resolve({ checkedAt: null, source: 'none', offline: true, signature: null, entries: [], problem: 'No catalog is configured for this build.' }),
      modules: () => Promise.resolve(moduleList({ configured: true, checkedAt: '2026-10-22T11:00:00Z', offline: false })),
    });
    const { el } = await render();
    const panel = el.querySelector('#catalog')!;
    expect(panel.querySelector('.gw-empty')).toBeNull();
    expect(panel.querySelector('.gw-alert')).toBeNull();
    expect(Array.from(panel.querySelectorAll('#catalog-grid .gw-card__title')).map(text)).toEqual(['DuckDB', 'ClickHouse']);
    expect(text(panel.querySelector('#catalog-count'))).toContain('checked rel 2026-10-22');
    expect(text(el.querySelector('#tab-catalog .gw-tabs__count'))).toBe('2 connectors');
    // The toolbar keeps the way to install a package from a file.
    expect(Array.from(panel.querySelectorAll('button')).filter((b) => text(b).startsWith('Install from file or URL'))).toHaveLength(1);
  });

  it('says why GateRook’s list of modules couldn’t be read, over the cards of the last one', async () => {
    setup({
      types: () => Promise.resolve(typesWithListedModules()),
      modules: () => Promise.resolve(moduleList({ configured: true, checkedAt: '2026-10-21T11:00:00Z', offline: true, problem: LIST_UNREACHABLE })),
    });
    const { el } = await render();
    const alert = el.querySelector('#catalog .gw-alert--warning');
    expect(text(alert?.querySelector('.gw-alert__title'))).toBe("GateRook's list of connectors couldn't be checked");
    expect(text(alert?.querySelector('.gw-alert__body'))).toBe(`${LIST_UNREACHABLE} These are the connectors of the last list GateRook verified.`);
    expect(cardOf(el, 'catalog', 'DuckDB')).not.toBeNull();
  });

  it('says why nothing is offered after a first start without the network', async () => {
    // No list of modules was ever accepted, and this build reads no catalog either: the list's reason comes first.
    setup({
      catalog: () =>
        Promise.resolve({ checkedAt: null, source: 'none', offline: true, signature: null, entries: [], problem: 'No catalog is configured for this build.' }),
      modules: () => Promise.resolve(moduleList({ configured: true, offline: true, problem: LIST_UNREACHABLE })),
    });
    const { el } = await render();
    expect(text(el.querySelector('#catalog .gw-empty__title'))).toBe("The catalog isn't available");
    expect(text(el.querySelector('#catalog .gw-empty__text'))).toBe(`${LIST_UNREACHABLE} A plugin you have as a file can still be checked and installed.`);
  });

  it('calls the Catalog empty, not unavailable, when the list of modules was read and offers nothing', async () => {
    // Every listed module is installed or none fits this build, and this build reads no plugin catalog.
    setup({
      catalog: () =>
        Promise.resolve({ checkedAt: null, source: 'none', offline: true, signature: null, entries: [], problem: 'No catalog is configured for this build.' }),
      modules: () => Promise.resolve(moduleList({ configured: true, checkedAt: '2026-10-22T11:00:00Z', offline: false })),
    });
    const { el } = await render();
    const panel = el.querySelector('#catalog')!;
    expect(text(panel.querySelector('.gw-empty__title'))).toBe('The catalog is empty');
    expect(text(panel.querySelector('.gw-empty__text'))).not.toContain('No catalog is configured');
    expect(text(panel.querySelector('#catalog-count'))).toContain('checked rel 2026-10-22');
    expect(text(el.querySelector('#tab-catalog .gw-tabs__count'))).toBe('0 connectors');
  });

  it('reads the modules again when the core accepted another list in the background', async () => {
    let listed = false;
    setup({ types: () => Promise.resolve(listed ? typesWithListedModules() : typesCatalog()) });
    const { fixture, el } = await render();
    expect(cardOf(el, 'catalog', 'DuckDB')).toBeNull();
    const before = calls.length;

    listed = true;
    events.emit('module.list', { modules: 8 });
    await settle(fixture);
    expect(calls.slice(before).sort()).toEqual(['modules', 'types']);
    expect(cardOf(el, 'catalog', 'DuckDB')).not.toBeNull();
    expect(text(el.querySelector('#tab-catalog .gw-tabs__count'))).toBe('7 connectors');
  });

  it('checks both lists at once on Check now, shows what was published since and says so', async () => {
    let listed = false;
    let answer!: (list: ConnectorModules) => void;
    setup({
      types: () => Promise.resolve(listed ? typesWithListedModules() : typesCatalog()),
      catalogCheck: () => Promise.resolve({ ...catalogIndex(), checkedAt: '2026-10-23T09:00:00Z' }),
      modulesCheck: () => new Promise<ConnectorModules>((resolve) => (answer = resolve)),
    });
    const { fixture, el } = await render();
    const panel = el.querySelector('#catalog')!;
    const check = button(panel, 'Check now');
    expect(text(check)).toBe('Check now for changes in the catalog');
    expect(check.getAttribute('aria-busy')).toBeNull();
    expect(check.querySelector('svg')).not.toBeNull();
    const before = calls.length;

    check.click();
    await settle(fixture);
    // Busy while the core checks: a spinner for the icon, and a second click starts nothing.
    expect(check.getAttribute('aria-busy')).toBe('true');
    expect(check.getAttribute('aria-disabled')).toBe('true');
    expect(check.querySelector('.gw-spinner')).not.toBeNull();
    expect(check.querySelector('svg')).toBeNull();
    check.click();
    await settle(fixture);
    expect(calls.slice(before).sort()).toEqual(['catalog-refresh', 'modules-refresh']);
    expect(toasts()).toHaveLength(0);

    listed = true;
    answer(moduleList({ configured: true, checkedAt: '2026-10-23T09:00:00Z', offline: false }));
    await settle(fixture);
    // The answers are taken as they are (no second read of either list); the cards come with the types.
    expect(calls.slice(before).sort()).toEqual(['catalog-refresh', 'modules-refresh', 'types']);
    expect(check.getAttribute('aria-busy')).toBeNull();
    expect(check.getAttribute('aria-disabled')).toBeNull();
    expect(cardOf(el, 'catalog', 'DuckDB')).not.toBeNull();
    expect(text(panel.querySelector('#catalog-count'))).toContain('checked rel 2026-10-23');
    expect(text(el.querySelector('#tab-catalog .gw-tabs__count'))).toBe('7 connectors');
    expect(toasts()).toHaveLength(1);
    expect(toasts()[0]).toMatchObject({ tone: 'success', title: '2 new connectors in the catalog', body: 'DuckDB and ClickHouse' });
  });

  it('says that the catalog is up to date when a check brings nothing new', async () => {
    // A build without a plugin catalog: its "No catalog is configured" is no failure of this check.
    const none = (): Promise<CatalogIndex> =>
      Promise.resolve({ checkedAt: null, source: 'none', offline: true, signature: null, entries: [], problem: 'No catalog is configured for this build.' });
    setup({
      types: () => Promise.resolve(typesWithListedModules()),
      catalog: none,
      modules: () => Promise.resolve(moduleList({ configured: true, checkedAt: '2026-10-22T11:00:00Z', offline: false })),
      modulesCheck: () => Promise.resolve(moduleList({ configured: true, checkedAt: '2026-10-23T09:00:00Z', offline: false })),
    });
    const { fixture, el } = await render();
    const panel = el.querySelector('#catalog')!;
    expect(text(panel.querySelector('#catalog-count'))).toContain('checked rel 2026-10-22');

    button(panel, 'Check now').click();
    await settle(fixture);
    expect(toasts()).toHaveLength(1);
    expect(toasts()[0]).toMatchObject({ tone: 'success', title: 'The catalog is up to date' });
    expect(toasts()[0]?.body ?? null).toBeNull();
    expect(text(panel.querySelector('#catalog-count'))).toContain('checked rel 2026-10-23');
    expect(Array.from(panel.querySelectorAll('#catalog-grid .gw-card__title')).map(text)).toEqual(['DuckDB', 'ClickHouse']);
    expect(panel.querySelector('.gw-alert')).toBeNull();
  });

  it('says why a check couldn’t reach a list, in the toast and over the cards of the last one', async () => {
    setup({
      types: () => Promise.resolve(typesWithListedModules()),
      modules: () => Promise.resolve(moduleList({ configured: true, checkedAt: '2026-10-22T11:00:00Z', offline: false })),
      modulesCheck: () => Promise.resolve(moduleList({ configured: true, checkedAt: '2026-10-22T11:00:00Z', offline: true, problem: LIST_UNREACHABLE })),
    });
    const { fixture, el } = await render();
    const panel = el.querySelector('#catalog')!;
    expect(panel.querySelector('.gw-alert')).toBeNull();

    button(panel, 'Check now').click();
    await settle(fixture);
    expect(toasts()[0]).toMatchObject({ tone: 'warning', title: "GateRook's list of connectors couldn't be checked", body: LIST_UNREACHABLE });
    expect(text(panel.querySelector('.gw-alert--warning .gw-alert__title'))).toBe("GateRook's list of connectors couldn't be checked");
    expect(cardOf(el, 'catalog', 'DuckDB')).not.toBeNull();
    expect(button(panel, 'Check now').getAttribute('aria-busy')).toBeNull();
  });

  it('says why the plugin catalog couldn’t be checked when the list of modules could', async () => {
    const unreachable = "The catalog can't be reached: index.json can't be downloaded from catalog.gaterook.dev: timeout";
    setup({ catalogCheck: () => Promise.resolve({ ...catalogIndex(), offline: true, problem: unreachable }) });
    const { fixture, el } = await render();
    button(el.querySelector('#catalog'), 'Check now').click();
    await settle(fixture);
    expect(toasts()[0]).toMatchObject({ tone: 'warning', title: "The catalog couldn't be checked", body: unreachable });
    expect(text(el.querySelector('#catalog .gw-alert--warning .gw-alert__title'))).toBe("The catalog couldn't be checked");
  });

  it('offers Check now where nothing is offered yet, and says when the check itself failed', async () => {
    // A first start without the network left no list: the empty Catalog still has the way to ask again.
    setup({
      catalog: () =>
        Promise.resolve({ checkedAt: null, source: 'none', offline: true, signature: null, entries: [], problem: 'No catalog is configured for this build.' }),
      modules: () => Promise.resolve(moduleList({ configured: true, offline: true, problem: LIST_UNREACHABLE })),
      modulesCheck: () => Promise.reject(new Error('The core did not answer')),
    });
    const { fixture, el } = await render();
    const panel = el.querySelector('#catalog')!;
    expect(text(panel.querySelector('.gw-empty__title'))).toBe("The catalog isn't available");
    // One Install from file or URL, in the empty card; the toolbar has Check now alone.
    expect(Array.from(panel.querySelectorAll('.gw-toolbar__end button')).map(text)).toEqual(['Check now for changes in the catalog']);

    button(panel, 'Check now').click();
    await settle(fixture);
    expect(toasts()[0]).toMatchObject({ tone: 'danger', title: "The catalog wasn't checked" });
    expect(toasts()[0]?.body).toContain('The core did not answer');
    expect(button(panel, 'Check now').getAttribute('aria-busy')).toBeNull();
  });

  it('sets a catalog MCP server up in Add connection, which asks for its environment, review and endpoints', async () => {
    setup({ catalog: () => Promise.resolve(catalogIndex(catalogEntries().map((e) => (e.kind === 'mcp-server' ? { ...e, installed: null } : e)))) });
    const navigated = navigations();
    const { fixture, el } = await render();
    button(cardOf(el, 'catalog', 'GitHub'), 'Install').click();
    await settle(fixture);
    expect(navigated()).toEqual([[['/connections/new'], { queryParams: { catalog: 'github@0.6.2' } }]]);
    // Nothing is reviewed or started here: Add connection does both, after the person chose its environment.
    expect(calls).not.toContain('review');
    expect(calls).not.toContain('create-server');
    expect(el.querySelector('dialog[open]')).toBeNull();
  });
});

describe('Connectors page: Templates', () => {
  it('lists templates in the kit’s words', async () => {
    setup();
    const { el } = await render();
    const row = (id: string) => el.querySelector(`#template-${id}`)!;
    expect(text(row('t1').querySelector('th'))).toBe('orders_by_status, edit template orders_by_status SQL query · updated rel 2026-10-20');
    expect(text(row('t2').querySelectorAll('td')[1])).toBe('Read only · via ssh_run');
    expect(text(row('t3').querySelectorAll('td')[1])).toBe('Approval · via ssh_run, changes state');
    expect(text(row('t2').querySelectorAll('td')[2])).toBe('dev');
    expect(text(row('t4').querySelectorAll('td')[2])).toBe('incident-prod');
    expect(text(row('t1').querySelectorAll('td')[2])).toBe('None yet');
    expect(text(row('t1').querySelector('td'))).toBe('dev-db environment dev');
  });

  it("lists a SQL query with its signature when the core sends the parameters (step API)", async () => {
    setup({
      templates: () =>
        Promise.resolve(
          templates().map((t) => (t.id === 't1' ? { ...t, parameters: [{ name: 'status', type: 'string' }, { name: 'since', type: 'string' }] } : t)),
        ),
    });
    const { el } = await render();
    const name = el.querySelector('#template-t1 th a')!;
    expect(text(name)).toBe('orders_by_status(status, since), edit template orders_by_status');
  });

  it('offers New SQL query when Oracle is the connector providing templates', async () => {
    setup({ templates: () => Promise.resolve([]), templateKinds: { oracle: ['sql-query'] } });
    const { fixture, el } = await render();
    el.querySelector<HTMLButtonElement>('#tab-templates')!.click();
    await settle(fixture);
    const panel = el.querySelector('#templates')!;
    expect(text(panel.querySelector('.gw-empty__title'))).toBe('No templates yet');
    button(panel.querySelector('.gw-empty'), 'New SQL query').click();
    await settle(fixture);
    expect(text(el.querySelector('#tpl-title'))).toBe('New template');
    expect(document.activeElement?.id).toBe('tpl-name');
  });

  it('offers SQL query templates for SQL Server connections', async () => {
    setup({
      templateKinds: { sqlserver: ['sql-query'] },
      rows: () => [...connectionRows(), { ...connectionRows()[0], name: 'reporting', type: 'sqlserver', typeName: 'SQL Server' }],
    });
    const { fixture, el } = await render();
    el.querySelector<HTMLButtonElement>('#tab-templates')!.click();
    await settle(fixture);
    button(el.querySelector('#menu-new-template'), 'SQL query').click();
    await settle(fixture);
    expect(Array.from(el.querySelectorAll<HTMLOptionElement>('#tpl-conn option')).map((o) => o.value)).toEqual(['reporting']);
  });

  it('keeps the rows and says the list may be out of date when a reload fails', async () => {
    let fail = false;
    setup({ templates: () => (fail ? Promise.reject(new ApiError('network', 0, {})) : Promise.resolve(templates())) });
    const { fixture, el } = await render();
    fail = true;
    // A reload (the core came back, or a template changed) that fails.
    await fixture.debugElement.injector.get(ConnectorsData).templates.refresh();
    await settle(fixture);
    const panel = el.querySelector('#templates')!;
    expect(panel.querySelectorAll('tbody tr')).toHaveLength(4);
    expect(text(panel.querySelector('.gw-alert--warning .gw-alert__title'))).toBe('This list may be out of date.');
  });

  it('says why New template › SQL query isn’t offered when the core compiles none', async () => {
    setup();
    const { el } = await render();
    const item = button(el.querySelector('#menu-new-template'), 'SQL query');
    expect(item.getAttribute('aria-disabled')).toBe('true');
    expect(text(item.querySelector('.gw-menu__hint'))).toBe('No installed SQL connector offers query templates.');
    expect(button(el.querySelector('#menu-new-template'), 'SSH runbook').getAttribute('aria-disabled')).toBe('true');
  });

  it('builds a SQL query: placeholders become parameters, Save sends the definition', async () => {
    setup({
      templateKinds: { postgres: ['sql-query'] },
      templates: () => Promise.resolve(calls.includes('create-template') ? [...templates(), { ...templates()[0], id: 't9' }] : templates()),
    });
    const { fixture, el } = await render();
    el.querySelector<HTMLButtonElement>('#tab-templates')!.click();
    await settle(fixture);
    expect(el.querySelector<HTMLElement>('#templates')!.hidden).toBe(false);
    const item = button(el.querySelector('#menu-new-template'), 'SQL query');
    expect(item.hasAttribute('aria-disabled')).toBe(false);
    item.click();
    await settle(fixture);
    const editor = el.querySelector('#template-editor')!;
    expect(text(editor.querySelector('#tpl-title'))).toBe('New template');
    expect(document.activeElement?.id).toBe('tpl-name');
    expect(Array.from(editor.querySelectorAll<HTMLOptionElement>('#tpl-conn option')).map((o) => o.value)).toEqual(['dev-db', 'stage-db']);

    // Save refuses an empty form at its first field.
    button(editor, 'Save template').click();
    await settle(fixture);
    expect(text(editor.querySelector('#tpl-name-err'))).toBe('Use lowercase letters, digits and _, starting with a letter.');
    expect(text(editor.querySelector('#tpl-sql-err'))).toBe('Write the SQL the tool runs.');
    expect(calls).not.toContain('create-template');

    type(editor.querySelector<HTMLInputElement>('#tpl-name')!, 'orders_by_status');
    type(editor.querySelector<HTMLTextAreaElement>('#tpl-sql')!, 'SELECT id FROM orders WHERE status = :status AND created_at >= :since');
    await settle(fixture);
    expect(text(editor.querySelector('#tpl-sql-check'))).toBe('Parsed as SELECT, read only · 2 parameters: :status, :since');
    expect(text(editor)).toContain('Unsaved changes');
    const typeSelects = editor.querySelectorAll<HTMLSelectElement>('tbody select');
    expect(typeSelects).toHaveLength(2);
    choose(typeSelects[1], 'timestamp');
    await settle(fixture);
    expect(text(editor.querySelector('#tpl-schema'))).toContain('"format": "date-time"');

    button(editor, 'Save template').click();
    await settle(fixture);
    expect(bodies['create-template']).toEqual({
      kind: 'sql-query',
      connection: 'dev-db',
      definition: {
        name: 'orders_by_status',
        sql: 'SELECT id FROM orders WHERE status = :status AND created_at >= :since',
        params: [
          { name: 'status', type: 'string', required: true },
          { name: 'since', type: 'timestamp', required: true },
        ],
      },
    });
    expect(toasts().at(-1)?.title).toBe('Template orders_by_status saved');
    expect(el.querySelector('#template-editor')).toBeNull();
    expect(el.querySelector('#template-t9')).not.toBeNull();
    expect(document.activeElement).toBe(el.querySelector('#template-t9 th a'));

    el.querySelector<HTMLButtonElement>('#tab-installed')!.click();
    await settle(fixture);
    el.querySelector<HTMLButtonElement>('#tab-templates')!.click();
    await settle(fixture);
    expect(el.querySelector<HTMLElement>('#templates')!.hidden).toBe(false);
    expect(el.querySelector('#template-editor')).toBeNull();

    el.querySelector<HTMLButtonElement>('#new-template')!.click();
    button(el.querySelector('#menu-new-template'), 'SQL query').click();
    await settle(fixture);
    expect(text(el.querySelector('#tpl-title'))).toBe('New template');
    expect(el.querySelector<HTMLInputElement>('#tpl-name')!.value).toBe('');
    expect(el.querySelector<HTMLTextAreaElement>('#tpl-sql')!.value).toBe('');
    button(el.querySelector('#template-editor'), 'Close editor').click();
    await settle(fixture);
    expect(el.querySelector('#template-editor')).toBeNull();
    expect(document.activeElement?.id).toBe('new-template');
  });

  it('opens a saved SQL query from its definition and returns focus to its row when closed', async () => {
    setup({ templateKinds: { postgres: ['sql-query'] } });
    const { fixture, el } = await render();
    el.querySelector<HTMLButtonElement>('#tab-templates')!.click();
    await settle(fixture);
    el.querySelector<HTMLAnchorElement>('#template-t1 th a')!.click();
    await settle(fixture);
    expect(calls).toContain('template t1');
    const editor = el.querySelector('#template-editor')!;
    expect(text(editor.querySelector('#tpl-title'))).toBe('Edit template orders_by_status');
    expect(editor.querySelector<HTMLTextAreaElement>('#tpl-sql')!.value).toBe(ORDERS_DEFINITION.sql);
    expect(editor.querySelector<HTMLSelectElement>('#tpl-conn')!.disabled).toBe(true);
    expect(editor.querySelectorAll('tbody tr')).toHaveLength(2);
    button(editor, 'Close editor').click();
    await settle(fixture);
    expect(el.querySelector('#template-editor')).toBeNull();
    expect(document.activeElement).toBe(el.querySelector('#template-t1 th a'));
  });

  it('keeps a refused template open and shows compiler warnings beside the list after a successful retry', async () => {
    let created = false;
    const saved = { ...templates()[0], id: 't9', name: 'lookup_order' };
    setup({
      templateKinds: { postgres: ['sql-query'] },
      templates: () => Promise.resolve(created ? [...templates(), saved] : templates()),
    });
    const create = vi.spyOn(TestBed.inject(TemplatesApi), 'create')
      .mockResolvedValueOnce({
        template: null,
        compile: { ok: false, tools: [], errors: ['The gateway could not analyze the SQL.'], warnings: [], digest: null },
      })
      .mockImplementationOnce(async () => {
        created = true;
        return {
          template: saved,
          compile: { ok: true, tools: [], errors: [], warnings: ['Review the query before enabling it on an endpoint.'], digest: null },
        };
      });
    const { fixture, el } = await render();
    el.querySelector<HTMLButtonElement>('#tab-templates')!.click();
    await settle(fixture);
    button(el.querySelector('#menu-new-template'), 'SQL query').click();
    await settle(fixture);
    const editor = el.querySelector('#template-editor')!;
    type(editor.querySelector<HTMLInputElement>('#tpl-name')!, 'lookup_order');
    type(editor.querySelector<HTMLTextAreaElement>('#tpl-sql')!, 'SELECT id FROM orders WEHRE id = :id');
    await settle(fixture);
    button(editor, 'Save template').click();
    await settle(fixture);
    expect(el.querySelector('#template-editor')).toBe(editor);
    expect(text(editor.querySelector('#tpl-compile'))).toContain('The gateway could not analyze the SQL.');
    expect(editor.querySelector<HTMLInputElement>('#tpl-name')!.value).toBe('lookup_order');
    expect(editor.querySelector<HTMLTextAreaElement>('#tpl-sql')!.value).toBe('SELECT id FROM orders WEHRE id = :id');
    expect(el.querySelector('#template-t9')).toBeNull();

    type(editor.querySelector<HTMLTextAreaElement>('#tpl-sql')!, 'SELECT id FROM orders WHERE id = :id');
    await settle(fixture);
    button(editor, 'Save template').click();
    await settle(fixture);
    expect(create).toHaveBeenCalledTimes(2);
    expect(el.querySelector('#template-editor')).toBeNull();
    expect(el.querySelector('#template-t9')).not.toBeNull();
    expect(text(el.querySelector('#templates .gw-alert--warning'))).toContain('Review the query before enabling it on an endpoint.');
    expect(document.activeElement).toBe(el.querySelector('#template-t9 th a'));
  });

  it('keeps a newer draft open when an earlier template finishes saving', async () => {
    const pending = deferred<TemplateSaved>();
    setup({ templateKinds: { postgres: ['sql-query'] } });
    const create = vi.spyOn(TestBed.inject(TemplatesApi), 'create').mockReturnValueOnce(pending.promise);
    const { fixture, el } = await render();
    el.querySelector<HTMLButtonElement>('#tab-templates')!.click();
    await settle(fixture);
    button(el.querySelector('#menu-new-template'), 'SQL query').click();
    await settle(fixture);
    type(el.querySelector<HTMLInputElement>('#tpl-name')!, 'first_query');
    type(el.querySelector<HTMLTextAreaElement>('#tpl-sql')!, 'SELECT id FROM orders');
    await settle(fixture);
    button(el.querySelector('#template-editor'), 'Save template').click();
    await settle(fixture);
    expect(create).toHaveBeenCalledTimes(1);
    expect(button(el.querySelector('#template-editor'), 'Save template').getAttribute('aria-busy')).toBe('true');

    el.querySelector<HTMLButtonElement>('#new-template')!.click();
    button(el.querySelector('#menu-new-template'), 'SQL query').click();
    await settle(fixture);
    type(el.querySelector<HTMLInputElement>('#tpl-name')!, 'second_query');
    type(el.querySelector<HTMLTextAreaElement>('#tpl-sql')!, 'SELECT status FROM orders WHERE id = :id');
    await settle(fixture);

    pending.resolve({
      template: { ...templates()[0], id: 't9', name: 'first_query' },
      compile: { ok: true, tools: [], errors: [], warnings: [], digest: null },
    });
    await settle(fixture);
    const editor = el.querySelector('#template-editor')!;
    expect(editor).not.toBeNull();
    expect(text(editor.querySelector('#tpl-title'))).toBe('New template');
    expect(editor.querySelector<HTMLInputElement>('#tpl-name')!.value).toBe('second_query');
    expect(editor.querySelector<HTMLTextAreaElement>('#tpl-sql')!.value).toBe('SELECT status FROM orders WHERE id = :id');
    expect(text(editor)).toContain('Unsaved changes');
    expect(button(editor, 'Save template').getAttribute('aria-busy')).toBeNull();
  });

  it('asks before deleting a template', async () => {
    setup();
    const { fixture, el } = await render();
    button(el.querySelector('#menu-tpl-t2'), 'Delete runbook').click();
    await settle(fixture);
    const dialog = el.querySelector<HTMLDialogElement>('dialog[gwConfirmDialog]')!;
    expect(text(dialog.querySelector('.gw-dialog__title'))).toBe('Delete template compose-ps?');
    button(dialog, 'Delete template compose-ps').click();
    await settle(fixture);
    expect(calls).toContain('delete-template t2');
  });
});

describe('Connectors page: MCP servers', () => {
  it('opens runtime details from the server menu and returns focus when the drawer closes', async () => {
    setup();
    const { fixture, el } = await render();
    el.querySelector<HTMLButtonElement>('#tab-mcp')!.click();
    await settle(fixture);
    const panel = el.querySelector('#mcp-servers')!;
    expect(panel.querySelector('[aria-labelledby^="mcp-proc-title-"]')).toBeNull();
    const drawer = el.querySelector<HTMLDialogElement>('#mcp-runtime')!;
    expect(drawer.open).toBe(false);

    const trigger = panel.querySelector<HTMLButtonElement>('[aria-label="More actions for github-mcp"]')!;
    trigger.click();
    const menu = el.querySelector<HTMLElement>('#menu-mcp-github-mcp')!;
    button(menu, 'How it runs').click();
    await settle(fixture);

    expect(menu.hidden).toBe(true);
    expect(drawer.open).toBe(true);
    expect(text(drawer.querySelector('.gw-drawer__title'))).toBe('How github-mcp runs');
    const rows = Object.fromEntries(Array.from(drawer.querySelectorAll('.gw-kv__row')).map((r) => [text(r.querySelector('dt')), text(r.querySelector('dd'))]));
    expect(rows['Package']).toContain('github@0.6.2');
    expect(rows['Process']).toBe('Started by the core on the first call, stopped after 15 min without calls. A crash never stops the core.');
    expect(rows['Working directory']).toBe('~/.gaterook/mcp/github-mcp');
    expect(rows['Variables passed']).toContain('GITHUB_PERSONAL_ACCESS_TOKEN from the secret github-mcp/token');
    expect(rows['Log']).toBe('~/.gaterook/logs/mcp/github-mcp.log');
    const close = drawer.querySelector<HTMLButtonElement>('.gw-drawer__close')!;
    expect(document.activeElement).toBe(close);
    close.click();
    await settle(fixture);
    expect(drawer.open).toBe(false);
    expect(document.activeElement).toBe(trigger);
  });

  it('reopens the runtime drawer with the selected HTTP server instead of the previous process', async () => {
    setup({
      rows: () => [
        ...connectionRows(),
        { ...connectionRows().find((r) => r.name === 'github-mcp')!, name: 'remote-mcp', host: 'https://mcp.example.com/mcp' },
      ],
    });
    vi.spyOn(TestBed.inject(ConnectionsApi), 'get').mockImplementation((name) => Promise.resolve(name === 'remote-mcp' ? {
      ...serverDetail(name),
      config: { transport: 'http', url: 'https://mcp.example.com/mcp', headers: [{ name: 'Authorization', secretRef: 'secret:s2' }], toolPrefix: 'remote_' },
      secrets: [{ field: '/headers/0/secretRef', secretId: 's2', name: 'remote-mcp/token', state: 'stored', version: 1, source: 'encrypted' }],
    } : serverDetail(name)));
    const { fixture, el } = await render();
    el.querySelector<HTMLButtonElement>('#tab-mcp')!.click();
    await settle(fixture);
    el.querySelector<HTMLButtonElement>('[aria-label="More actions for github-mcp"]')!.click();
    button(el.querySelector('#menu-mcp-github-mcp'), 'How it runs').click();
    await settle(fixture);
    const drawer = el.querySelector<HTMLDialogElement>('#mcp-runtime')!;
    drawer.querySelector<HTMLButtonElement>('.gw-drawer__close')!.click();
    await settle(fixture);

    const trigger = el.querySelector<HTMLButtonElement>('[aria-label="More actions for remote-mcp"]')!;
    trigger.click();
    button(el.querySelector('#menu-mcp-remote-mcp'), 'How it runs').click();
    await settle(fixture);
    expect(drawer.open).toBe(true);
    expect(text(drawer.querySelector('.gw-drawer__title'))).toBe('How remote-mcp runs');
    const rows = Object.fromEntries(Array.from(drawer.querySelectorAll('.gw-kv__row')).map((r) => [text(r.querySelector('dt')), text(r.querySelector('dd'))]));
    expect(rows['Trust']).toContain('Every call sends its secrets to https://mcp.example.com/mcp.');
    expect(rows['Headers passed']).toBe('Authorization from the secret remote-mcp/token');
    expect(rows['Tool names']).toBe('Prefixed with remote_');
    expect(rows['Process']).toBeUndefined();
    expect(rows['Working directory']).toBeUndefined();
    expect(rows['Log']).toBeUndefined();
    expect(text(drawer)).not.toContain('github-mcp');
    drawer.querySelector<HTMLButtonElement>('.gw-drawer__close')!.click();
    await settle(fixture);
    expect(document.activeElement).toBe(trigger);
  });

  it('lists the server with its command, secrets and tools; Restart says what came back', async () => {
    setup();
    const { fixture, el } = await render();
    const row = el.querySelector('#mcp-github-mcp')!;
    expect(text(row.querySelector('th'))).toContain('github-mcp');
    expect(text(row)).toContain('node /Users/me/.gaterook/mcp/github-mcp/dist/index.js');
    expect(text(row.querySelector('a[href^="/secrets"]'))).toContain('GITHUB_PERSONAL_ACCESS_TOKEN');
    expect(text(row)).toContain('26');

    const restart = row.querySelector<HTMLButtonElement>('[aria-labelledby="tip-restart-github-mcp"]')!;
    expect(text(el.querySelector('#tip-restart-github-mcp'))).toBe('Restart github-mcp');
    restart.click();
    await settle(fixture);
    expect(calls).toContain('server restart github-mcp');
    expect(toasts().at(-1)).toMatchObject({ title: 'github-mcp restarted', body: 'Tools listed again: 26, same as before.' });
  });

  it('names the secrets a removal destroys, as the core does', async () => {
    // Review 2026-10-26: DELETE /api/mcp-servers/{name} deletes the connection with its secrets (`secretsDestroyed`);
    // the dialog said "The secret github-mcp/token stays in Secrets."
    setup();
    const { fixture, el } = await render();
    button(el.querySelector('#menu-mcp-github-mcp'), 'Remove').click();
    await settle(fixture);
    const dialog = el.querySelector<HTMLDialogElement>('dialog[gwConfirmDialog]')!;
    expect(text(dialog.querySelector('.gw-dialog__title'))).toBe('Remove MCP server github-mcp?');
    expect(text(dialog.querySelector('.gw-dialog__description'))).toBe(
      'The process stops and its 26 tools are removed. dev loses them. Its secret github-mcp/token is destroyed.',
    );
    button(dialog, 'Remove MCP server github-mcp').click();
    await settle(fixture);
    expect(calls).toContain('delete-server github-mcp');
    expect(toasts().at(-1)?.body).toBe('Its process stopped and its secret was destroyed. Past calls stay in Activity.');
  });

  it('opens Add connection on its MCP servers from the header menu and from the tab’s Add MCP server', async () => {
    setup();
    const navigated = navigations();
    const { fixture, el } = await render();
    button(el.querySelector('#menu-add-connector'), 'Add MCP server').click();
    await settle(fixture);
    el.querySelector<HTMLButtonElement>('#tab-mcp')!.click();
    await settle(fixture);
    const add = button(el.querySelector('#mcp-servers .gw-toolbar'), 'Add MCP server');
    // A page now, not a dialog.
    expect(add.hasAttribute('aria-haspopup')).toBe(false);
    add.click();
    await settle(fixture);
    expect(navigated()).toEqual([
      [['/connections/new'], { queryParams: { show: 'mcp' } }],
      [['/connections/new'], { queryParams: { show: 'mcp' } }],
    ]);
    expect(calls.filter((c) => c === 'review' || c.startsWith('registry'))).toEqual([]);
    expect(el.querySelector('dialog[open]')).toBeNull();
  });

  it('sends an older link to Add MCP server (?add=mcp-server) on to Add connection, in its place in the history', async () => {
    setup();
    const navigated = navigations();
    const fixture = TestBed.createComponent(ConnectorsPage);
    fixture.componentRef.setInput('add', 'mcp-server');
    document.body.appendChild(fixture.nativeElement);
    await settle(fixture);
    expect(navigated()).toEqual([[['/connections/new'], { queryParams: { show: 'mcp' }, replaceUrl: true }]]);
    expect((fixture.nativeElement as HTMLElement).querySelector('dialog[open]')).toBeNull();
  });

  it('offers the add button and twenty discovery cards when no server is connected', async () => {
    setup({ rows: () => connectionRows().filter((r) => r.type !== 'mcp-server') });
    const navigated = navigations();
    const { fixture, el } = await render();
    const panel = el.querySelector('#mcp-servers')!;
    expect(text(panel)).toContain('No MCP servers connected yet. Choose a server below or add your own.');
    expect(panel.querySelector('.gw-empty')).toBeNull();
    expect(panel.querySelectorAll('[aria-label="Popular MCP servers for developers"] article')).toHaveLength(20);
    expect(Array.from(panel.querySelectorAll('button')).filter((b) => text(b).startsWith('Add MCP server'))).toHaveLength(1);
    expect(text(el.querySelector('#tab-mcp .gw-tabs__count'))).toBe('0 servers');
    expect(cardOf(el, 'installed', 'github-mcp')).toBeNull();

    button(panel.querySelector('.gw-toolbar'), 'Add MCP server').click();
    await settle(fixture);
    expect(navigated()).toEqual([[['/connections/new'], { queryParams: { show: 'mcp' } }]]);
  });

  it('keeps discovery beside connected servers without loading twenty registry entries', async () => {
    setup();
    const { el } = await render();
    expect(el.querySelector('#mcp-github-mcp')).not.toBeNull();
    const cards = el.querySelectorAll('[aria-label="Popular MCP servers for developers"] article');
    expect(cards).toHaveLength(20);
    for (const card of cards) {
      // Each card opens its setup on the Add connection page, not in a dialog.
      expect(card.querySelector('button')?.hasAttribute('aria-haspopup')).toBe(false);
      expect(card.querySelector('button')?.getAttribute('aria-label')).toMatch(/^Add .+ from Official MCP Registry$/);
      expect(card.querySelector('img')?.getAttribute('src')).toMatch(/^(mcp-logos|assets\/integrations)\//);
    }
    expect(calls.filter((call) => call.startsWith('registry'))).toEqual([]);
    expect(el.querySelector('[aria-labelledby="mcp-profiles-title"]')).toBeNull();
  });

  it('opens a verified profile’s Cloud platforms card on that profile’s setup in Add connection', async () => {
    setup({ profiles: () => Promise.resolve(profilesFixture()) });
    const navigated = navigations();
    const { fixture, el } = await render();
    button(el, 'MCP servers').click();
    await settle(fixture);
    const list = el.querySelector('[aria-label="Cloud platform MCP servers with a verified profile"]')!;
    expect(list.querySelectorAll('article')).toHaveLength(2);
    expect(text(list)).toContain('Verified profile, checked 2026-11-11');
    expect(list.querySelector('img')?.getAttribute('src')).toBe('mcp-logos/supabase.png');
    const opener = button(list, 'Cloudflare API');
    expect(opener.getAttribute('aria-label')).toBe('Add Cloudflare API with GateRook’s verified profile');
    opener.click();
    await settle(fixture);
    expect(navigated()).toEqual([[['/connections/new'], { queryParams: { profile: 'cloudflare-api' } }]]);
    expect(calls.filter((call) => call.startsWith('registry'))).toEqual([]);
    expect(calls).not.toContain('review');
  });

  it('opens a Popular card that has a verified profile on that profile, not on its registry entry', async () => {
    setup({ profiles: () => Promise.resolve(profilesFixture()) });
    const navigated = navigations();
    const { fixture, el } = await render();
    button(el, 'MCP servers').click();
    await settle(fixture);
    const popular = el.querySelector('[aria-label="Popular MCP servers for developers"]')!;
    const supabase = button(popular, 'Supabase');
    expect(supabase.getAttribute('aria-label')).toBe('Add Supabase with GateRook’s verified profile');
    expect(text(supabase.closest('article'))).toContain('Verified profile');
    supabase.click();
    await settle(fixture);
    expect(navigated()).toEqual([[['/connections/new'], { queryParams: { profile: 'supabase' } }]]);
    expect(calls.filter((call) => call.startsWith('registry'))).toEqual([]);
  });

  it.each([
    ['Linear', 'app.linear/linear'],
    ['Playwright', 'io.github.microsoft/playwright-mcp'],
  ])('opens the %s card on its exact entry of the Official MCP Registry in Add connection', async (name, registryName) => {
    setup();
    const navigated = navigations();
    const { fixture, el } = await render();
    button(el, 'MCP servers').click();
    await settle(fixture);
    button(el.querySelector('[aria-label="Popular MCP servers for developers"]'), name).click();
    await settle(fixture);
    expect(navigated()).toEqual([[['/connections/new'], { queryParams: { registry: registryName } }]]);
    // Add connection reads the entry: nothing is looked up, reviewed or started here.
    expect(calls.filter((call) => call.startsWith('registry'))).toEqual([]);
    expect(calls).not.toContain('review');
    expect(calls).not.toContain('create-server');
  });

  it('shows Not listed yet when the core has no tool snapshot route', async () => {
    setup({ serverTools: () => Promise.reject(notAvailable()) });
    const { el } = await render();
    expect(text(el.querySelector('#mcp-github-mcp'))).toContain('Not listed yet');
  });
});

describe('Connectors page: inline MCP Registry search', () => {
  it('searches after three characters and opens the selected result in Add connection', async () => {
    setup();
    const navigated = navigations();
    const { fixture, el } = await render();
    button(el, 'MCP servers').click();
    await settle(fixture);
    const region = el.querySelector<HTMLElement>('section[gwMcpRegistrySearch]')!;
    const search = region.querySelector<HTMLInputElement>('input[type="search"]')!;
    const picks = el.querySelector<HTMLElement>('[aria-labelledby="mcp-featured-title"]')!;
    expect(region.querySelector('label')?.textContent).toBe('Search MCP Registry');
    type(search, 'li');
    await settle(fixture);
    expect(calls.filter((c) => c.startsWith('registry'))).toEqual([]);
    expect(text(region.querySelector('[role="status"]'))).toContain('at least 3 characters');
    type(search, 'linear');
    await sleep(400);
    await settle(fixture);
    expect(picks.hidden).toBe(true);
    expect(calls.filter((c) => c.startsWith('registry'))).toEqual(['registry linear']);
    expect(region.querySelectorAll('article')).toHaveLength(2);
    expect(text(region.querySelector('[role="status"]'))).toBe('2 servers so far');
    const use = button(region, 'Use Linear');
    expect(use.hasAttribute('aria-haspopup')).toBe(false);
    use.click();
    await settle(fixture);
    expect(navigated()).toEqual([[['/connections/new'], { queryParams: { registryServer: 'app.linear/linear' } }]]);
    expect(calls.filter((c) => c.startsWith('registry'))).toEqual(['registry linear']);
    expect(calls).not.toContain('review');
    expect(search.value).toBe('linear');
    // A result GateRook can't start from the registry goes nowhere.
    button(region, 'Use image-only').click();
    await settle(fixture);
    expect(navigated()).toHaveLength(1);
  });

  it('submits immediately and clearing the query restores popular cards without browsing the registry', async () => {
    setup();
    const { fixture, el } = await render();
    const { region, search, results } = await searchInlineRegistry(fixture, el, 'linear');
    expect(results()).toHaveLength(2);
    expect(calls.filter((c) => c.startsWith('registry'))).toEqual(['registry linear']);
    region.querySelector<HTMLButtonElement>('.gw-search__clear')!.click();
    await settle(fixture);
    expect(results()).toHaveLength(0);
    expect(search.value).toBe('');
    expect(el.querySelector<HTMLElement>('[aria-labelledby="mcp-featured-title"]')!.hidden).toBe(false);
    await sleep(400);
    expect(calls.filter((c) => c.startsWith('registry'))).toEqual(['registry linear']);
  });

  it('brings the official publisher into inline results before similarly named integrations', async () => {
    setup({ registry: async (search) => ({ registry: 'registry.modelcontextprotocol.io',
      servers: search === 'com.stripe/mcp' ? [stripeRegistryServers[2]] : stripeRegistryServers.slice(0, 2) }) });
    const { fixture, el } = await render();
    const { results } = await searchInlineRegistry(fixture, el, 'stripe');
    expect(calls.filter((c) => c.startsWith('registry'))).toEqual(['registry stripe', 'registry com.stripe/mcp']);
    expect(text(results()[0])).toContain('com.stripe/mcp');
    expect(results()[0].querySelector('[aria-label="Official server by Stripe"]')).not.toBeNull();
    expect(results()).toHaveLength(3);
  });

  it('paginates without duplicates, retries a failed next page and focuses the first added result', async () => {
    let fail = true;
    setup({ registry: async (search, cursor) => {
      if (cursor && fail) throw new Error('Next page unavailable');
      const page = await registryPage(search, cursor);
      return cursor ? { ...page, servers: [(await registryPage(search, null)).servers[0], ...page.servers] } : page;
    } });
    const { fixture, el } = await render();
    button(el, 'MCP servers').click();
    const { region, results } = await searchInlineRegistry(fixture, el, 'linear');
    button(region, 'Show more').click();
    await settle(fixture);
    expect(text(region.querySelector('[role="alert"]'))).toContain('Next page unavailable');
    expect(results()).toHaveLength(2);
    fail = false;
    button(region, 'Try again').click();
    await settle(fixture);
    expect(results()).toHaveLength(3);
    expect(text(region.querySelector('[role="status"]'))).toBe('3 servers');
    expect(region.querySelector('[role="alert"]')).toBeNull();
    expect(text(document.activeElement)).toBe('Use linear-mcp');
    expect(calls.filter((c) => c.startsWith('registry'))).toEqual([
      'registry linear', 'registry linear after app.linear/linear:1.0.2', 'registry linear after app.linear/linear:1.0.2',
    ]);
  });

  it('shows a failed search with retry, then an empty result', async () => {
    let fail = true;
    setup({ registry: async () => {
      if (fail) throw new Error('Registry unavailable');
      return { registry: 'registry.modelcontextprotocol.io', servers: [] };
    } });
    const navigated = navigations();
    const { fixture, el } = await render();
    const { region } = await searchInlineRegistry(fixture, el, 'unlisted');
    expect(text(region.querySelector('[role="alert"]'))).toContain('Registry unavailable');
    fail = false;
    button(region, 'Try again').click();
    await settle(fixture);
    expect(region.querySelector('[role="alert"]')).toBeNull();
    expect(text(region.querySelector('.gw-empty__title'))).toBe('No server name contains “unlisted”');
    expect(navigated()).toEqual([]);
  });

  it('shows a result from a configured registry without an official-publisher badge', async () => {
    setup({ registry: async () => ({ registry: 'registry.example.com', servers: [stripeRegistryServers[2]] }) });
    const { fixture, el } = await render();
    const { region, results } = await searchInlineRegistry(fixture, el, 'stripe');
    expect(text(region)).toContain('the MCP registry at registry.example.com');
    expect(results()[0].querySelector('[aria-label="Official server by Stripe"]')).toBeNull();
    expect(calls.filter((c) => c.startsWith('registry'))).toEqual(['registry stripe']);
  });

  it.each(['query', 'clear', 'destroy'])('discards an earlier inline result after %s', async (action) => {
    const pending = deferred<McpRegistryPage>();
    setup({ registry: () => pending.promise });
    const { fixture, el } = await render();
    const { region, search } = await searchInlineRegistry(fixture, el, 'linear');
    if (action === 'query') type(search, 'st');
    else if (action === 'clear') region.querySelector<HTMLButtonElement>('.gw-search__clear')!.click();
    else fixture.destroy();
    pending.resolve(await registryPage('linear', null));
    if (action !== 'destroy') {
      await settle(fixture);
      expect(region.querySelectorAll('article')).toHaveLength(0);
      expect(text(region)).not.toContain('mcp.linear.app');
    }
    await sleep(400);
    expect(calls.filter((c) => c.startsWith('registry'))).toEqual(['registry linear']);
  });
});
