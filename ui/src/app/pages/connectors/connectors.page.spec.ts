import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ApiError } from '../../core/api/api-error';
import { ConnectionRow, ConnectionsApi } from '../../core/api/connections.api';
import { ConnectorCatalog, ConnectorTypesApi } from '../../core/api/connector-types.api';
import { EndpointsApi } from '../../core/api/endpoints.api';
import { CatalogIndex, McpRegistryPage, McpServersApi, PluginsApi } from '../../core/api/plugins.api';
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
  rabbitUpdate,
  secretRows,
  sentryReview,
  serverDetail,
  templates,
  typeDetail,
  typesCatalog,
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
    check: (source) => {
      record('check', source);
      return Promise.resolve(clickhouseCheck());
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

async function searchMcpRegistry(fixture: ComponentFixture<ConnectorsPage>, el: HTMLElement, query: string) {
  button(el.querySelector('#menu-add-connector'), 'Add MCP server').click();
  await settle(fixture);
  const add = el.querySelector<HTMLDialogElement>('dialog[gwMcpAddDialog]')!;
  button(add, 'Official MCP Registry').click();
  await settle(fixture);
  const registry = add.querySelector<HTMLElement>('[role="tabpanel"][id$="-registry"]')!;
  const search = registry.querySelector<HTMLInputElement>('input[type="search"]')!;
  type(search, query);
  search.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
  await settle(fixture);
  return { add, registry, search,
    results: () => Array.from(registry.querySelectorAll('[aria-label="Servers in the MCP registry"] article')) };
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

  it('installs github-mcp from the catalog with the value of its variable, typed in the review and sent once', async () => {
    // Review 2026-10-26 (K6, E1): the catalog install sent no value and the core answered 422 "Secret missing".
    setup({ catalog: () => Promise.resolve(catalogIndex(catalogEntries().map((e) => (e.kind === 'mcp-server' ? { ...e, installed: null } : e)))) });
    const { fixture, el } = await render();
    button(cardOf(el, 'catalog', 'GitHub'), 'Install').click();
    await settle(fixture);
    expect(bodies['review']).toEqual({ catalog: 'github@0.6.2' });

    const review = el.querySelector<HTMLDialogElement>('dialog[gwMcpReviewDialog]')!;
    expect(review.open).toBe(true);
    expect(text(review.querySelector('.gw-dialog__title'))).toBe('Start MCP server github-mcp?');
    expect(text(document.activeElement)).toBe('Cancel');
    expect(Array.from(review.querySelectorAll('footer button')).map(text)).not.toContain('Back');
    const rows = Object.fromEntries(Array.from(review.querySelectorAll('.gw-kv__row')).map((r) => [text(r.querySelector('dt')), text(r.querySelector('dd'))]));
    expect(rows['Command']).toBe('node /Users/me/.gaterook/mcp/github-mcp/dist/index.js');
    expect(rows['Secrets passed']).toBe('GITHUB_PERSONAL_ACCESS_TOKEN, from a new secret of github-mcp, encrypted here');
    const value = review.querySelector<HTMLInputElement>('input[type="password"]')!;
    expect(text(review.querySelector(`label[for="${value.id}"]`))).toBe('Value of GITHUB_PERSONAL_ACCESS_TOKEN');
    expect(value.autocomplete).toBe('new-password');

    check(review.querySelector<HTMLInputElement>('input[type="checkbox"]')!);
    await settle(fixture);
    const start = button(review, 'Start MCP server github-mcp');
    // Empty: refused at the field, nothing sent.
    start.click();
    await settle(fixture);
    expect(calls).not.toContain('create-server');
    expect(value.getAttribute('aria-invalid')).toBe('true');
    expect(text(review.querySelector('.gw-field__error'))).toBe('Enter the value of GITHUB_PERSONAL_ACCESS_TOKEN.');
    expect(document.activeElement).toBe(value);

    type(value, 'ghp_cnry_123');
    await settle(fixture);
    expect(value.hasAttribute('aria-invalid')).toBe(false);
    start.click();
    await settle(fixture);
    expect(bodies['create-server']).toEqual({
      catalog: 'github@0.6.2',
      secrets: { '/env/0/secretRef': { source: 'local', value: 'ghp_cnry_123' } },
      acknowledge: true,
    });
    expect(review.open).toBe(false);
    // Write-only: the value left the page with the request (nothing in the DOM, no field keeps it).
    expect(value.value).toBe('');
    expect(document.body.innerHTML).not.toContain('ghp_cnry_123');
    // A catalog server ends on the endpoint step too.
    const step = el.querySelector<HTMLDialogElement>('dialog[gwMcpEndpointDialog]')!;
    expect(step.open).toBe(true);
    button(step, 'Add to endpoint').click();
    await settle(fixture);
    expect(calls).toContain('add-to-endpoint dev github-mcp');
    expect(toasts().at(-1)?.title).toBe('MCP server github-mcp started');
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

  it('adds a server: refuses an empty form at its fields, reviews, and starts it after the acknowledgement', async () => {
    setup();
    const { fixture, el } = await render();
    const header = el.querySelector('#menu-add-connector')!;
    button(header, 'Add MCP server').click();
    await settle(fixture);
    const add = el.querySelector<HTMLDialogElement>('dialog[gwMcpAddDialog]')!;
    expect(add.open).toBe(true);
    const name = add.querySelector<HTMLInputElement>('input[placeholder="sentry-mcp"]')!;
    expect(document.activeElement).toBe(name);

    button(add, 'Review and start').click();
    await settle(fixture);
    expect(text(add.querySelector('.gw-field__error'))).toBe('Name the server: lowercase letters, digits and -.');
    expect(calls).not.toContain('review');

    type(name, 'sentry-mcp');
    type(add.querySelector<HTMLInputElement>('input[placeholder^="npx"]')!, 'npx -y @sentry/mcp-server@0.12.0');
    type(add.querySelector<HTMLInputElement>('input[placeholder="SENTRY_AUTH_TOKEN"]')!, 'SENTRY_AUTH_TOKEN');
    // No "Value from secret" with other servers' secrets: the core takes none by name (C10), so no value field here.
    expect(add.querySelector('select')).toBeNull();
    expect(add.querySelector('input[type="password"]')).toBeNull();
    button(add, 'Review and start').click();
    await settle(fixture);
    // The review gets the names only: no value travels before the person starts the server.
    expect(bodies['review']).toEqual({
      config: { name: 'sentry-mcp', transport: 'stdio', command: ['npx', '-y', '@sentry/mcp-server@0.12.0'], env: [{ name: 'SENTRY_AUTH_TOKEN' }] },
    });

    const review = el.querySelector<HTMLDialogElement>('dialog[gwMcpReviewDialog]')!;
    expect(add.open).toBe(false);
    expect(review.open).toBe(true);
    expect(review.classList).toContain('gw-dialog--danger');
    expect(text(document.activeElement)).toBe('Cancel');
    const rows = Object.fromEntries(Array.from(review.querySelectorAll('.gw-kv__row')).map((r) => [text(r.querySelector('dt')), text(r.querySelector('dd'))]));
    expect(rows['Command']).toBe('npx -y @sentry/mcp-server@0.12.0');
    expect(rows['Runs as']).toContain('Not sandboxed on this system');

    const start = button(review, 'Start MCP server sentry-mcp');
    expect(start.getAttribute('aria-disabled')).toBe('true');
    start.click();
    await settle(fixture);
    expect(calls).not.toContain('create-server');
    check(review.querySelector<HTMLInputElement>('input[type="checkbox"]')!);
    await settle(fixture);
    type(review.querySelector<HTMLInputElement>('input[type="password"]')!, 'sntrys_value');
    start.click();
    await settle(fixture);
    expect(bodies['create-server']).toEqual({
      config: { name: 'sentry-mcp', transport: 'stdio', command: ['npx', '-y', '@sentry/mcp-server@0.12.0'], env: [{ name: 'SENTRY_AUTH_TOKEN' }] },
      secrets: { '/env/0/secretRef': { source: 'local', value: 'sntrys_value' } },
      acknowledge: true,
    });
    expect(review.open).toBe(false);

    // The last step, as the connection wizard's: its tools join an endpoint in their default modes.
    const step = el.querySelector<HTMLDialogElement>('dialog[gwMcpEndpointDialog]')!;
    expect(step.open).toBe(true);
    expect(text(step.querySelector('.gw-dialog__title'))).toBe('Add sentry-mcp to an endpoint');
    const boxes = Array.from(step.querySelectorAll<HTMLInputElement>('input[name="endpoints"]'));
    expect(boxes.map((b) => [b.value, b.checked, b.getAttribute('aria-disabled')])).toEqual([
      ['dev', true, null],
      ['incident-prod', false, 'true'],
    ]);
    expect(text(step)).toContain('Accepts prod connections only; sentry-mcp is dev.');
    expect(document.activeElement).toBe(boxes[0]);
    button(step, 'Add to endpoint').click();
    await settle(fixture);
    expect(calls).toContain('preview dev sentry-mcp');
    expect(calls).toContain('add-to-endpoint dev sentry-mcp');
    expect(calls).not.toContain('add-to-endpoint incident-prod sentry-mcp');
    expect(step.open).toBe(false);
    expect(toasts().at(-1)?.title).toBe('MCP server sentry-mcp started');
    expect(toasts().at(-1)?.body).toBe('Its 12 tools are on dev. Declared reads start as Read only; other tools start as Approval. Change their modes on the endpoint.');
    expect(toasts().at(-1)?.actions?.[0]?.label).toBe('Open endpoint dev');
  });

  it('leaves the tools of a new server Off when the endpoint step is skipped', async () => {
    setup();
    const { fixture, el } = await render();
    button(el.querySelector('#menu-add-connector'), 'Add MCP server').click();
    await settle(fixture);
    const add = el.querySelector<HTMLDialogElement>('dialog[gwMcpAddDialog]')!;
    type(add.querySelector<HTMLInputElement>('input[placeholder="sentry-mcp"]')!, 'sentry-mcp');
    type(add.querySelector<HTMLInputElement>('input[placeholder^="npx"]')!, 'npx -y @sentry/mcp-server@0.12.0');
    type(add.querySelector<HTMLInputElement>('input[placeholder="SENTRY_AUTH_TOKEN"]')!, 'SENTRY_AUTH_TOKEN');
    button(add, 'Review and start').click();
    await settle(fixture);
    const review = el.querySelector<HTMLDialogElement>('dialog[gwMcpReviewDialog]')!;
    check(review.querySelector<HTMLInputElement>('input[type="checkbox"]')!);
    type(review.querySelector<HTMLInputElement>('input[type="password"]')!, 'sntrys_value');
    await settle(fixture);
    button(review, 'Start MCP server sentry-mcp').click();
    await settle(fixture);
    const step = el.querySelector<HTMLDialogElement>('dialog[gwMcpEndpointDialog]')!;
    button(step, 'Not now').click();
    await settle(fixture);
    expect(step.open).toBe(false);
    expect(calls.filter((c) => c.startsWith('add-to-endpoint'))).toEqual([]);
    expect(toasts().at(-1)?.body).toBe('Its 12 tools are Off until you add them to an endpoint. Declared reads start as Read only; other tools start as Approval.');
  });

  it('loads registry browsing on first selection, keeps it across tabs and paginates without a search', async () => {
    const first = deferred<McpRegistryPage>();
    setup({ registry: (search, cursor) => cursor ? registryPage(search, cursor) : first.promise });
    const { fixture, el } = await render();
    button(el.querySelector('#menu-add-connector'), 'Add MCP server').click();
    await settle(fixture);
    const add = el.querySelector<HTMLDialogElement>('dialog[gwMcpAddDialog]')!;
    expect(calls.filter((call) => call.startsWith('registry'))).toEqual([]);

    button(add, 'Official MCP Registry').click();
    await settle(fixture);
    const registry = add.querySelector<HTMLElement>('[role="tabpanel"][id$="-registry"]')!;
    const results = () => Array.from(registry.querySelectorAll('[aria-label="Servers in the MCP registry"] article'));
    expect(text(registry.querySelector('[role="status"]'))).toBe('Loading servers from the MCP registry…');
    expect(calls.filter((call) => call.startsWith('registry'))).toEqual(['registry ']);
    first.resolve(await registryPage('', null));
    await settle(fixture);
    expect(results()).toHaveLength(2);
    button(add, 'Manual entry').click();
    button(add, 'Official MCP Registry').click();
    await settle(fixture);
    expect(results()).toHaveLength(2);
    expect(calls.filter((call) => call.startsWith('registry'))).toEqual(['registry ']);

    button(add, 'Show more').click();
    await settle(fixture);
    expect(calls).toContain('registry  after app.linear/linear:1.0.2');
    expect(results()).toHaveLength(3);
    expect(document.activeElement).toBe(button(results()[2], 'Use'));
    expect(calls).not.toContain('review');
    expect(bodies['create-server']).toBeUndefined();
  });

  it('restores registry browsing when the search is cleared', async () => {
    setup();
    const { fixture, el } = await render();
    const { registry, search, results } = await searchMcpRegistry(fixture, el, 'linear');
    type(search, '');
    await settle(fixture);
    expect(calls.filter((call) => call.startsWith('registry'))).toEqual(['registry ', 'registry linear', 'registry ']);
    expect(results()).toHaveLength(2);
    expect(text(registry.querySelector('[role="status"]'))).toBe('2 servers so far');
  });

  it('retries an initial registry listing and explains an empty listing without a search', async () => {
    let fail = true;
    setup({ registry: () => fail ? Promise.reject(new ApiError('server', 502, {
      title: 'The registry did not answer', detail: 'Registry is temporarily unavailable.',
    })) : Promise.resolve({ registry: 'registry.modelcontextprotocol.io', servers: [] }) });
    const fixture = TestBed.createComponent(ConnectorsPage);
    fixture.componentRef.setInput('add', 'mcp-server');
    fixture.componentRef.setInput('source', 'registry');
    document.body.appendChild(fixture.nativeElement);
    await settle(fixture);
    const add = fixture.nativeElement.querySelector('dialog[gwMcpAddDialog]') as HTMLDialogElement;
    const registry = add.querySelector<HTMLElement>('[role="tabpanel"][id$="-registry"]')!;
    expect(text(registry.querySelector('.gw-alert--danger'))).toContain('Registry is temporarily unavailable.');
    fail = false;
    button(registry, 'Try again').click();
    await settle(fixture);
    expect(calls.filter((call) => call.startsWith('registry'))).toEqual(['registry ', 'registry ']);
    expect(registry.querySelector('.gw-alert--danger')).toBeNull();
    expect(text(registry.querySelector('.gw-empty__title'))).toBe('No servers available');
    expect(text(registry.querySelector('[role="status"]'))).toBe('0 servers');
  });

  it('drops an initial listing as soon as a new query is typed, before its debounce finishes', async () => {
    const initial = deferred<McpRegistryPage>();
    setup({ registry: (search, cursor) => search ? registryPage(search, cursor) : initial.promise });
    const { fixture, el } = await render();
    button(el.querySelector('#menu-add-connector'), 'Add MCP server').click();
    await settle(fixture);
    const add = el.querySelector<HTMLDialogElement>('dialog[gwMcpAddDialog]')!;
    button(add, 'Official MCP Registry').click();
    await settle(fixture);
    const search = add.querySelector<HTMLInputElement>('input[type="search"]')!;
    type(search, 'linear');
    initial.resolve({ registry: 'registry.modelcontextprotocol.io', servers: stripeRegistryServers });
    await Promise.resolve();
    await Promise.resolve();
    fixture.detectChanges();
    expect(calls.filter((call) => call.startsWith('registry'))).toEqual(['registry ']);
    expect(add.querySelectorAll('[aria-label="Servers in the MCP registry"] article')).toHaveLength(0);
    search.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await settle(fixture);
    expect(calls.filter((call) => call.startsWith('registry'))).toEqual(['registry ', 'registry linear']);
    expect(text(add.querySelector('[aria-label="Servers in the MCP registry"]'))).toContain('Linear');
    expect(text(add.querySelector('[aria-label="Servers in the MCP registry"]'))).not.toContain('stripecheckup');
  });

  it.each(['Use', 'title'])('selects an MCP registry server with %s, fills the form and waits for review', async (action) => {
    setup();
    const { fixture, el } = await render();
    button(el.querySelector('#menu-add-connector'), 'Add MCP server').click();
    await settle(fixture);
    const add = el.querySelector<HTMLDialogElement>('dialog[gwMcpAddDialog]')!;
    // Manual entry first, as before; the registry is the other tab, with no review of its own in the footer.
    expect(button(add, 'Manual entry').getAttribute('aria-selected')).toBe('true');
    button(add, 'Official MCP Registry').click();
    await settle(fixture);
    expect(button(add, 'Official MCP Registry').getAttribute('aria-selected')).toBe('true');
    const registry = add.querySelector<HTMLElement>('[role="tabpanel"][id$="-registry"]')!;
    expect(registry.hidden).toBe(false);
    expect(add.querySelector<HTMLElement>('[role="tabpanel"][id$="-manual"]')!.hidden).toBe(true);
    expect(Array.from(add.querySelectorAll('footer button')).map(text)).toEqual(['Cancel']);
    const search = registry.querySelector<HTMLInputElement>('input[type="search"]')!;
    const status = () => text(registry.querySelector('[role="status"]'));

    // Browsing loads immediately; a nonempty search still needs at least 3 characters.
    type(search, 'li');
    search.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await settle(fixture);
    expect(status()).toBe('Type at least 3 characters of the server’s name.');
    expect(calls.filter((c) => c.startsWith('registry'))).toEqual(['registry ']);

    // Enter searches at once and doesn't submit the form for review.
    type(search, 'linear');
    search.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await settle(fixture);
    expect(calls.filter((c) => c.startsWith('registry'))).toEqual(['registry ', 'registry linear']);
    expect(calls).not.toContain('review');
    expect(status()).toBe('2 servers so far');
    const results = () => Array.from(add.querySelectorAll('[aria-label="Servers in the MCP registry"] article'));
    expect(results().map((r) => text(r.querySelector('.gw-card__title button')))).toEqual(['Linear', 'image-only']);
    expect(results()[0].querySelector('[aria-label="Official server by Linear"]')).not.toBeNull();
    expect(text(results()[0])).toContain('HTTP mcp.linear.app');
    expect(text(results()[1])).toContain('Can’t be added here');
    expect(text(results()[1])).toContain('Deprecated');
    expect(text(results()[1])).toContain('It runs as a Docker image, which GateRook doesn’t start from the registry yet.');
    expect(button(results()[1], 'Use').getAttribute('aria-disabled')).toBe('true');
    const unavailableTitle = results()[1].querySelector<HTMLButtonElement>('.gw-card__title button')!;
    expect(unavailableTitle.getAttribute('type')).toBe('button');
    expect(unavailableTitle.getAttribute('aria-disabled')).toBe('true');
    expect(unavailableTitle.getAttribute('aria-describedby')).toBe(button(results()[1], 'Use').getAttribute('aria-describedby'));
    unavailableTitle.click();
    await settle(fixture);
    expect(registry.hidden).toBe(false);
    expect(add.querySelector<HTMLInputElement>('input[placeholder="sentry-mcp"]')!.value).toBe('');
    expect(calls).not.toContain('review');
    expect(bodies['create-server']).toBeUndefined();
    expect(text(add)).toContain('From the Official MCP Registry (registry.modelcontextprotocol.io).');

    // The next page joins the list, and the keyboard goes to its first result.
    button(add, 'Show more').click();
    await settle(fixture);
    expect(calls).toContain('registry linear after app.linear/linear:1.0.2');
    expect(results()).toHaveLength(3);
    expect(document.activeElement).toBe(button(results()[2], 'Use'));
    expect(text(results()[2])).toContain('stdio npx @someone/linear-mcp@0.3.2');
    expect(Array.from(add.querySelectorAll('button')).some((b) => text(b) === 'Show more')).toBe(false);

    const choose = action === 'title' ? results()[0].querySelector<HTMLButtonElement>('.gw-card__title button')! : button(results()[0], 'Use');
    choose.click();
    await settle(fixture);
    const name = add.querySelector<HTMLInputElement>('input[placeholder="sentry-mcp"]')!;
    expect(name.value).toBe('linear-mcp');
    expect(add.querySelector<HTMLInputElement>('input[type="radio"][value="http"]')!.checked).toBe(true);
    expect(add.querySelector<HTMLInputElement>('input[type="url"]')!.value).toBe('https://mcp.linear.app/mcp');
    // Use returns to Manual entry with the fields filled, and its review.
    expect(button(add, 'Manual entry').getAttribute('aria-selected')).toBe('true');
    expect(registry.hidden).toBe(true);
    expect(text(add)).toContain('Filled in the name and the URL from Linear in the MCP registry.');
    expect(document.activeElement).toBe(name);
    expect(calls).not.toContain('review');
    expect(bodies['create-server']).toBeUndefined();

    button(add, 'Review and connect').click();
    await settle(fixture);
    expect(bodies['review']).toEqual({ config: { name: 'linear-mcp', transport: 'http', url: 'https://mcp.linear.app/mcp', headers: [] } });
    expect(bodies['create-server']).toBeUndefined();
  });

  it('highlights the official Stripe server and keeps other results in registry order', async () => {
    setup({ registry: () => Promise.resolve({ registry: 'registry.modelcontextprotocol.io', servers: stripeRegistryServers }) });
    const { fixture, el } = await render();
    const { results } = await searchMcpRegistry(fixture, el, 'stripe');

    expect(results().map((row) => text(row.querySelector('.gw-card__title button'))))
      .toEqual(['mcp', 'stripecheckup', 'Official Stripe integration']);
    expect(results()[0].querySelector('[aria-label="Official server by Stripe"]')).not.toBeNull();
    expect(results()[1].querySelector('.gw-card__title .gw-badge')).toBeNull();
    expect(results()[2].querySelector('.gw-card__title .gw-badge')).toBeNull();
    expect(calls.filter((call) => call.startsWith('registry'))).toEqual(['registry ', 'registry stripe']);
    expect(calls).not.toContain('review');
  });

  it('does not treat copied official IDs from another registry as official publishers', async () => {
    setup({ registry: () => Promise.resolve({ registry: 'registry.example.com', servers: stripeRegistryServers }) });
    const { fixture, el } = await render();
    const { results } = await searchMcpRegistry(fixture, el, 'stripe');

    expect(results().map((row) => text(row.querySelector('.gw-card__title button'))))
      .toEqual(['stripecheckup', 'Official Stripe integration', 'mcp']);
    expect(results().every((row) => row.querySelector('.gw-card__title .gw-badge') === null)).toBe(true);
    expect(calls.filter((call) => call.startsWith('registry'))).toEqual(['registry ', 'registry stripe']);
  });

  it('finds an official publisher beyond the first page and deduplicates it on Show more', async () => {
    const additional: McpRegistryPage['servers'][number] = { ...stripeRegistryServers[0], name: 'org.example/stripe', title: 'Another Stripe server' };
    setup({ registry: (search, cursor) => Promise.resolve({
      registry: 'registry.modelcontextprotocol.io',
      servers: cursor ? [stripeRegistryServers[2], additional, additional]
        : search === 'com.stripe/mcp' ? [stripeRegistryServers[0], stripeRegistryServers[2]] : stripeRegistryServers.slice(0, 2),
      nextCursor: !cursor && search === 'stripe' ? 'next-stripe-page' : null,
    }) });
    const { fixture, el } = await render();
    const { add, registry, results } = await searchMcpRegistry(fixture, el, 'stripe');

    expect(results().map((row) => text(row.querySelector('.gw-card__title button'))))
      .toEqual(['mcp', 'stripecheckup', 'Official Stripe integration']);
    expect(calls.filter((call) => call.startsWith('registry'))).toEqual(['registry ', 'registry stripe', 'registry com.stripe/mcp']);
    expect(text(registry.querySelector('[role="status"]'))).toBe('3 servers so far');

    button(add, 'Show more').click();
    await settle(fixture);
    expect(calls).toContain('registry stripe after next-stripe-page');
    expect(results()).toHaveLength(4);
    expect(results()[0].querySelector('[aria-label="Official server by Stripe"]')).not.toBeNull();
    expect(document.activeElement).toBe(button(results()[3], 'Use'));
    expect(text(registry.querySelector('[role="status"]'))).toBe('4 servers');
  });

  it('keeps ordinary search usable when the extra lookup fails and focuses a later official result after ranking', async () => {
    setup({ registry: (search, cursor) => search === 'com.stripe/mcp' ? Promise.reject(new Error('Lookup unavailable'))
      : Promise.resolve({ registry: 'registry.modelcontextprotocol.io',
        servers: cursor ? [stripeRegistryServers[0], stripeRegistryServers[2]] : stripeRegistryServers.slice(0, 2),
        nextCursor: cursor ? null : 'next-stripe-page' }) });
    const { fixture, el } = await render();
    const { add, registry, results } = await searchMcpRegistry(fixture, el, 'stripe');
    expect(results()).toHaveLength(2);
    expect(registry.querySelector('.gw-alert--danger')).toBeNull();

    button(add, 'Show more').click();
    await settle(fixture);
    expect(results()).toHaveLength(3);
    expect(results()[0].querySelector('[aria-label="Official server by Stripe"]')).not.toBeNull();
    expect(document.activeElement).toBe(button(results()[0], 'Use'));
  });

  it('discards an extra official lookup when a newer query has already completed', async () => {
    const oldOfficial = deferred<McpRegistryPage>();
    setup({ registry: (search, cursor) => search === 'com.stripe/mcp' ? oldOfficial.promise
      : search === 'stripe' ? Promise.resolve({ registry: 'registry.modelcontextprotocol.io', servers: stripeRegistryServers.slice(0, 2) })
        : registryPage(search, cursor) });
    const { fixture, el } = await render();
    const { search, results } = await searchMcpRegistry(fixture, el, 'stripe');
    type(search, 'linear');
    search.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await settle(fixture);

    oldOfficial.resolve({ registry: 'registry.modelcontextprotocol.io', servers: [stripeRegistryServers[2]] });
    await settle(fixture);
    expect(results().map((row) => text(row.querySelector('.gw-card__title button')))).toEqual(['Linear', 'image-only']);
  });

  it('does not append the previous search page while a new official lookup is pending', async () => {
    const official = deferred<McpRegistryPage>();
    setup({ registry: (search, cursor) => search === 'com.stripe/mcp' ? official.promise
      : search === 'stripe' ? Promise.resolve({ registry: 'registry.modelcontextprotocol.io', servers: stripeRegistryServers.slice(0, 2) })
        : registryPage(search, cursor) });
    const { fixture, el } = await render();
    const { add, search, results } = await searchMcpRegistry(fixture, el, 'linear');
    type(search, 'stripe');
    search.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await settle(fixture);

    expect(button(add, 'Show more').getAttribute('aria-disabled')).toBe('true');
    button(add, 'Show more').click();
    await settle(fixture);
    expect(calls).not.toContain('registry linear after app.linear/linear:1.0.2');

    official.resolve({ registry: 'registry.modelcontextprotocol.io', servers: [stripeRegistryServers[2]] });
    await settle(fixture);
    expect(results().map((row) => text(row.querySelector('.gw-card__title button'))))
      .toEqual(['mcp', 'stripecheckup', 'Official Stripe integration']);
  });

  it('says why the registry search failed, and searches again on request', async () => {
    let fail = true;
    setup({
      registry: (search, cursor) => (fail
        ? Promise.reject(new ApiError('server', 502, { title: 'The registry didn\'t answer', detail: 'registry.modelcontextprotocol.io answered HTTP 503 for the MCP registry\'s answer.' }))
        : registryPage(search, cursor)),
    });
    const { fixture, el } = await render();
    button(el.querySelector('#menu-add-connector'), 'Add MCP server').click();
    await settle(fixture);
    const add = el.querySelector<HTMLDialogElement>('dialog[gwMcpAddDialog]')!;
    button(add, 'Official MCP Registry').click();
    await settle(fixture);
    const registry = add.querySelector<HTMLElement>('[role="tabpanel"][id$="-registry"]')!;
    const search = registry.querySelector<HTMLInputElement>('input[type="search"]')!;
    type(search, 'linear');
    search.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await settle(fixture);
    const alert = registry.querySelector('.gw-alert--danger');
    expect(text(alert)).toContain('registry.modelcontextprotocol.io answered HTTP 503');

    fail = false;
    button(alert, 'Try again').click();
    await settle(fixture);
    expect(registry.querySelector('.gw-alert--danger')).toBeNull();
    expect(add.querySelectorAll('[aria-label="Servers in the MCP registry"] article')).toHaveLength(2);
  });

  it('opens on Manual entry again and cancels a registry search queued before closing', async () => {
    setup();
    const { fixture, el } = await render();
    button(el.querySelector('#menu-add-connector'), 'Add MCP server').click();
    await settle(fixture);
    const add = el.querySelector<HTMLDialogElement>('dialog[gwMcpAddDialog]')!;
    button(add, 'Official MCP Registry').click();
    await settle(fixture);
    type(add.querySelector<HTMLInputElement>('input[type="search"]')!, 'linear');
    button(add.querySelector('footer'), 'Cancel').click();
    await settle(fixture);
    expect(add.open).toBe(false);

    button(el.querySelector('#menu-add-connector'), 'Add MCP server').click();
    await settle(fixture);
    expect(add.open).toBe(true);
    expect(button(add, 'Manual entry').getAttribute('aria-selected')).toBe('true');
    expect(add.querySelector<HTMLElement>('[role="tabpanel"][id$="-registry"]')!.hidden).toBe(true);
    expect(document.activeElement).toBe(add.querySelector('input[placeholder="sentry-mcp"]'));
    expect(Array.from(add.querySelectorAll('footer button')).map(text)).toEqual(['Cancel', 'Review and start']);
    await sleep(400);
    expect(calls.filter((call) => call.startsWith('registry'))).toEqual(['registry ']);
    expect(add.querySelectorAll('[aria-label="Servers in the MCP registry"] article')).toHaveLength(0);
  });

  it('warns about a key in the URL in the review, and still connects the server with it', async () => {
    setup();
    const { fixture, el } = await render();
    button(el.querySelector('#menu-add-connector'), 'Add MCP server').click();
    await settle(fixture);
    const add = el.querySelector<HTMLDialogElement>('dialog[gwMcpAddDialog]')!;
    type(add.querySelector<HTMLInputElement>('input[placeholder="sentry-mcp"]')!, 'search-mcp');
    check(add.querySelector<HTMLInputElement>('input[type="radio"][value="http"]')!);
    await settle(fixture);
    type(add.querySelector<HTMLInputElement>('input[type="url"]')!, 'https://mcp.example.com/mcp?apiKey=sk-example-123');
    button(add, 'Review and connect').click();
    await settle(fixture);
    expect(add.querySelector('.gw-alert--danger')).toBeNull();

    const review = el.querySelector<HTMLDialogElement>('dialog[gwMcpReviewDialog]')!;
    expect(review.open).toBe(true);
    const rows = Object.fromEntries(Array.from(review.querySelectorAll('.gw-kv__row')).map((r) => [text(r.querySelector('dt')), text(r.querySelector('dd'))]));
    expect(rows['URL']).toBe('https://mcp.example.com/mcp?apiKey=‹masked›');
    expect(Array.from(review.querySelectorAll('.gw-alert--warning')).map(text)).toEqual([KEY_IN_URL]);

    check(review.querySelector<HTMLInputElement>('input[type="checkbox"]')!);
    await settle(fixture);
    button(review, 'Connect MCP server search-mcp').click();
    await settle(fixture);
    // The server takes its key in the URL: the core gets it as typed.
    expect(bodies['create-server']).toEqual({
      config: { name: 'search-mcp', transport: 'http', url: 'https://mcp.example.com/mcp?apiKey=sk-example-123', headers: [] },
      acknowledge: true,
    });
    expect(review.open).toBe(false);
  });

  it('opens Add MCP server from the connection wizard’s link (?add=mcp-server)', async () => {
    setup();
    const fixture = TestBed.createComponent(ConnectorsPage);
    fixture.componentRef.setInput('add', 'mcp-server');
    document.body.appendChild(fixture.nativeElement);
    await settle(fixture);
    const add = fixture.nativeElement.querySelector('dialog[gwMcpAddDialog]') as HTMLDialogElement;
    expect(add.open).toBe(true);
  });

  it.each(['url', 'registry'])('opens the source selected in Overview without starting a server: %s', async (source) => {
    setup();
    const fixture = TestBed.createComponent(ConnectorsPage);
    fixture.componentRef.setInput('add', 'mcp-server');
    fixture.componentRef.setInput('source', source);
    document.body.appendChild(fixture.nativeElement);
    await settle(fixture);
    const add = fixture.nativeElement.querySelector('dialog[gwMcpAddDialog]') as HTMLDialogElement;
    expect(add.open).toBe(true);
    if (source === 'registry') {
      expect(text(add.querySelector('[role="tab"][aria-selected="true"]'))).toContain('Official MCP Registry');
      expect((document.activeElement as HTMLElement)?.id).toContain('-find-q');
      expect(calls.filter((call) => call.startsWith('registry'))).toEqual(['registry ']);
      expect(add.querySelectorAll('[aria-label="Servers in the MCP registry"] article')).toHaveLength(2);
    } else {
      expect(text(add)).toContain('Review and connect');
      expect(add.querySelector<HTMLInputElement>('input[type="radio"][value="http"]')?.checked).toBe(true);
      expect(calls.filter((call) => call.startsWith('registry'))).toEqual([]);
    }
    expect(bodies['create-server']).toBeUndefined();
  });

  it('returns from a refused HTTP review to choose Bearer token and connects with the token prefixed automatically', async () => {
    setup();
    const reviewApi = vi.spyOn(TestBed.inject(McpServersApi), 'review').mockResolvedValueOnce(sentryReview({
      name: 'private-mcp',
      transport: 'http',
      url: 'https://mcp.example.com/mcp',
      argv: [],
      env: [],
      auth: { kind: 'refused', message: 'It refused the request (HTTP 401) and publishes no OAuth metadata.' },
    }));
    const { fixture, el } = await render();
    const opener = button(el.querySelector('.gw-page-header__actions'), 'Add connector');
    button(el.querySelector('#menu-add-connector'), 'Add MCP server').click();
    await settle(fixture);
    const add = el.querySelector<HTMLDialogElement>('dialog[gwMcpAddDialog]')!;
    const name = add.querySelector<HTMLInputElement>('input[placeholder="sentry-mcp"]')!;
    type(name, 'private-mcp');
    check(add.querySelector<HTMLInputElement>('input[type="radio"][value="http"]')!);
    await settle(fixture);
    const bearer = add.querySelector<HTMLInputElement>('input[type="radio"][value="bearer"]')!;
    expect(bearer.closest('[hidden]')).toBeNull();
    type(add.querySelector<HTMLInputElement>('input[type="url"]')!, 'https://mcp.example.com/mcp');
    button(add, 'Review and connect').click();
    await settle(fixture);

    const review = el.querySelector<HTMLDialogElement>('dialog[gwMcpReviewDialog]')!;
    expect(review.open).toBe(true);
    expect(text(review.querySelector('.gw-alert--warning'))).toContain('It wants a key, not a sign-in');
    expect(text(review.querySelector('.gw-alert--warning'))).toContain('Go back');
    check(review.querySelector<HTMLInputElement>('input[type="checkbox"]')!);
    await settle(fixture);
    button(review, 'Back').click();
    await settle(fixture);

    expect(review.open).toBe(false);
    expect(add.open).toBe(true);
    expect(name.value).toBe('private-mcp');
    expect(add.querySelector<HTMLInputElement>('input[type="radio"][value="http"]')!.checked).toBe(true);
    expect(add.querySelector<HTMLInputElement>('input[type="url"]')!.value).toBe('https://mcp.example.com/mcp');
    expect(document.activeElement).toBe(name);
    expect(calls).not.toContain('create-server');
    check(bearer);
    await settle(fixture);
    button(add, 'Review and connect').click();
    await settle(fixture);

    const config = { name: 'private-mcp', transport: 'http', url: 'https://mcp.example.com/mcp', headers: [{ name: 'Authorization' }] };
    expect(reviewApi).toHaveBeenCalledTimes(2);
    expect(reviewApi).toHaveBeenLastCalledWith({ config });
    expect(review.open).toBe(true);
    expect(add.open).toBe(false);
    expect(review.querySelector('.gw-alert--warning')).toBeNull();
    expect(review.querySelector<HTMLInputElement>('input[type="checkbox"]')!.checked).toBe(false);
    const connect = button(review, 'Connect MCP server private-mcp');
    expect(connect.getAttribute('aria-disabled')).toBe('true');
    let token = review.querySelector<HTMLInputElement>('input[type="password"]')!;
    expect(text(review.querySelector(`label[for="${token.id}"]`))).toBe('Bearer token');
    type(token, ' Bearer ');
    connect.click();
    await settle(fixture);
    expect(calls).not.toContain('create-server');
    check(review.querySelector<HTMLInputElement>('input[type="checkbox"]')!);
    await settle(fixture);
    connect.click();
    await settle(fixture);
    expect(calls).not.toContain('create-server');
    expect(token.getAttribute('aria-invalid')).toBe('true');
    expect(text(review.querySelector('.gw-field__error'))).toBe('Enter the bearer token.');
    button(review, 'Back').click();
    await settle(fixture);
    expect(token.value).toBe('');
    expect(bearer.checked).toBe(true);
    expect(add.querySelector<HTMLInputElement>('input[id$="-var-name-0"]')!.value).toBe('');
    button(add, 'Review and connect').click();
    await settle(fixture);
    expect(reviewApi).toHaveBeenCalledTimes(3);
    expect(reviewApi).toHaveBeenLastCalledWith({ config });
    expect(review.querySelector<HTMLInputElement>('input[type="checkbox"]')!.checked).toBe(false);
    token = review.querySelector<HTMLInputElement>('input[type="password"]')!;
    expect(token.value).toBe('');
    type(token, ' example-token ');
    check(review.querySelector<HTMLInputElement>('input[type="checkbox"]')!);
    await settle(fixture);
    connect.click();
    await settle(fixture);
    expect(bodies['create-server']).toEqual({
      config,
      secrets: { '/headers/0/secretRef': { source: 'local', value: 'Bearer example-token' } },
      acknowledge: true,
    });
    expect(token.value).toBe('');
    const step = el.querySelector<HTMLDialogElement>('dialog[gwMcpEndpointDialog]')!;
    button(step, 'Not now').click();
    await settle(fixture);
    expect(document.activeElement).toBe(opener);

    button(el.querySelector('#menu-add-connector'), 'Add MCP server').click();
    await settle(fixture);
    expect(bearer.checked).toBe(false);
    expect(add.querySelector<HTMLInputElement>('input[type="radio"][value="oauth"]')!.checked).toBe(true);
  });

  it('does not carry Bearer mode to a pasted configuration with its own Authorization header', async () => {
    setup();
    const { fixture, el } = await render();
    button(el.querySelector('#menu-add-connector'), 'Add MCP server').click();
    await settle(fixture);
    const add = el.querySelector<HTMLDialogElement>('dialog[gwMcpAddDialog]')!;
    check(add.querySelector<HTMLInputElement>('input[type="radio"][value="http"]')!);
    await settle(fixture);
    check(add.querySelector<HTMLInputElement>('input[type="radio"][value="bearer"]')!);
    button(add, 'Paste a configuration').click();
    await settle(fixture);
    const paste = add.querySelector<HTMLTextAreaElement>('textarea[id$="-paste-text"]')!;
    type(paste, '{"url":"https://mcp.example.com/mcp","headers":{"Authorization":"Basic example-credentials"}}');
    await settle(fixture);
    expect(add.querySelector<HTMLInputElement>('input[type="radio"][value="bearer"]')!.checked).toBe(false);
    expect(add.querySelector<HTMLInputElement>('input[type="radio"][value="oauth"]')!.checked).toBe(true);
    expect(add.querySelector<HTMLInputElement>('input[placeholder="Authorization"]')!.value).toBe('Authorization');
    expect(paste.value).toBe('');
    button(add, 'Review and connect').click();
    await settle(fixture);
    const review = el.querySelector<HTMLDialogElement>('dialog[gwMcpReviewDialog]')!;
    const value = review.querySelector<HTMLInputElement>('input[type="password"]')!;
    expect(text(review.querySelector(`label[for="${value.id}"]`))).toBe('Value of Authorization');
    expect(value.value).toBe('');
  });

  it('wipes the typed values when the review closes without a start', async () => {
    setup();
    const { fixture, el } = await render();
    button(el.querySelector('#menu-add-connector'), 'Add MCP server').click();
    await settle(fixture);
    const add = el.querySelector<HTMLDialogElement>('dialog[gwMcpAddDialog]')!;
    type(add.querySelector<HTMLInputElement>('input[placeholder="sentry-mcp"]')!, 'sentry-mcp');
    type(add.querySelector<HTMLInputElement>('input[placeholder^="npx"]')!, 'npx -y @sentry/mcp-server@0.12.0');
    type(add.querySelector<HTMLInputElement>('input[placeholder="SENTRY_AUTH_TOKEN"]')!, 'SENTRY_AUTH_TOKEN');
    button(add, 'Review and start').click();
    await settle(fixture);
    const review = el.querySelector<HTMLDialogElement>('dialog[gwMcpReviewDialog]')!;
    const value = review.querySelector<HTMLInputElement>('input[type="password"]')!;
    type(value, 'sntrys_typed');
    button(review, 'Back').click();
    await settle(fixture);
    expect(review.open).toBe(false);
    expect(value.value).toBe('');
    expect(add.open).toBe(true);
    expect(add.querySelector<HTMLInputElement>('input[placeholder="sentry-mcp"]')!.value).toBe('sentry-mcp');
    expect(add.querySelector<HTMLInputElement>('input[placeholder^="npx"]')!.value).toBe('npx -y @sentry/mcp-server@0.12.0');
    expect(add.querySelector<HTMLInputElement>('input[placeholder="SENTRY_AUTH_TOKEN"]')!.value).toBe('SENTRY_AUTH_TOKEN');
    button(add, 'Review and start').click();
    await settle(fixture);
    expect(review.open).toBe(true);
    expect(review.querySelector<HTMLInputElement>('input[type="password"]')!.value).toBe('');
    type(review.querySelector<HTMLInputElement>('input[type="password"]')!, 'sntrys_retyped');
    button(review, 'Cancel').click();
    await settle(fixture);
    expect(review.open).toBe(false);
    expect(value.value).toBe('');
    expect(calls).not.toContain('create-server');
    expect(document.activeElement).toBe(button(el.querySelector('.gw-page-header__actions'), 'Add connector'));

    button(el.querySelector('#menu-add-connector'), 'Add MCP server').click();
    await settle(fixture);
    expect(add.open).toBe(true);
    expect(add.querySelector<HTMLInputElement>('input[placeholder="sentry-mcp"]')!.value).toBe('');
    expect(add.querySelector<HTMLInputElement>('input[placeholder^="npx"]')!.value).toBe('');
    expect(add.querySelector<HTMLInputElement>('input[placeholder="SENTRY_AUTH_TOKEN"]')!.value).toBe('');
  });

  it('offers one Add MCP server in the empty state when there is none', async () => {
    setup({ rows: () => connectionRows().filter((r) => r.type !== 'mcp-server') });
    const { fixture, el } = await render();
    const panel = el.querySelector('#mcp-servers')!;
    expect(text(panel.querySelector('.gw-empty__title'))).toBe('No MCP servers yet');
    expect(Array.from(panel.querySelectorAll('button')).filter((b) => text(b).startsWith('Add MCP server'))).toHaveLength(1);
    expect(text(el.querySelector('#tab-mcp .gw-tabs__count'))).toBe('0 servers');
    expect(cardOf(el, 'installed', 'github-mcp')).toBeNull();

    button(panel.querySelector('.gw-empty'), 'Add MCP server').click();
    await settle(fixture);
    expect(el.querySelector<HTMLDialogElement>('dialog[gwMcpAddDialog]')!.open).toBe(true);
  });

  it('shows Not listed yet when the core has no tool snapshot route', async () => {
    setup({ serverTools: () => Promise.reject(notAvailable()) });
    const { el } = await render();
    expect(text(el.querySelector('#mcp-github-mcp'))).toContain('Not listed yet');
  });
});
