import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ApiError } from '../../core/api/api-error';
import { ConnectionRow, ConnectionsApi } from '../../core/api/connections.api';
import { ConnectorCatalog, ConnectorTypesApi } from '../../core/api/connector-types.api';
import { EndpointsApi } from '../../core/api/endpoints.api';
import { CatalogIndex, McpServersApi, PluginsApi } from '../../core/api/plugins.api';
import { SecretsApi } from '../../core/api/secrets.api';
import { Template, TemplatesApi } from '../../core/api/templates.api';
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

/** Every answer of the fake core, replaceable per test. */
interface Answers {
  types: () => Promise<ConnectorCatalog>;
  catalog: () => Promise<CatalogIndex>;
  templates: () => Promise<Template[]>;
  templateKinds: Record<string, string[]>;
  serverTools: () => Promise<unknown[]>;
  rows: () => ConnectionRow[];
  update: () => ReturnType<typeof rabbitUpdate>;
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
      return Promise.resolve({ name: 'sentry-mcp', state: 'running', tools: 12 });
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
        useValue: { list: () => Promise.resolve(endpoints()), tools: (slug: string) => Promise.resolve(endpointTools(slug)) },
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

  it('shows the empty state with New SQL query when there is no template', async () => {
    setup({ templates: () => Promise.resolve([]), templateKinds: { postgres: ['sql-query'] } });
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
    expect(text(item.querySelector('.gw-menu__hint'))).toBe('This build’s PostgreSQL and MySQL connectors don’t offer SQL query templates yet.');
    expect(button(el.querySelector('#menu-new-template'), 'SSH runbook').getAttribute('aria-disabled')).toBe('true');
  });

  it('builds a SQL query: placeholders become parameters, Save sends the definition', async () => {
    setup({ templateKinds: { postgres: ['sql-query'] } });
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
  });

  it('opens a saved SQL query from its definition', async () => {
    setup({ templateKinds: { postgres: ['sql-query'] } });
    const { fixture, el } = await render();
    el.querySelector<HTMLAnchorElement>('#template-t1 th a')!.click();
    await settle(fixture);
    expect(calls).toContain('template t1');
    const editor = el.querySelector('#template-editor')!;
    expect(text(editor.querySelector('#tpl-title'))).toBe('Edit template orders_by_status');
    expect(editor.querySelector<HTMLTextAreaElement>('#tpl-sql')!.value).toBe(ORDERS_DEFINITION.sql);
    expect(editor.querySelector<HTMLSelectElement>('#tpl-conn')!.disabled).toBe(true);
    expect(editor.querySelectorAll('tbody tr')).toHaveLength(2);
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
    expect(toasts().at(-1)?.title).toBe('MCP server sentry-mcp started');
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
    button(review, 'Cancel').click();
    await settle(fixture);
    expect(review.open).toBe(false);
    expect(value.value).toBe('');
    expect(calls).not.toContain('create-server');
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
