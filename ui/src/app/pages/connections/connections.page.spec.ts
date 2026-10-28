import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ApiError } from '../../core/api/api-error';
import { ApprovalsApi } from '../../core/api/approvals.api';
import { ConnectionDeleted, ConnectionDetail, ConnectionList, ConnectionRow, ConnectionsApi, TestOutcome } from '../../core/api/connections.api';
import { ConnectorTypesApi } from '../../core/api/connector-types.api';
import { EndpointsApi } from '../../core/api/endpoints.api';
import { SecretsApi } from '../../core/api/secrets.api';
import { EventStream } from '../../core/events/event-stream';
import { CoreStore } from '../../core/stores/core.store';
import { TimeService } from '../../core/time/time.service';
import { AriaDisabledGuard } from '../../kit/aria-disabled';
import { provideKitIcons } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { NOW, SECRETS, SHORT_LIVED, at, detail, kitRows, outcome, row } from './connections.fixtures';
import { ConnectionsPage, SKELETON_DELAY_MS } from './connections.page';

/** The page against fake admin API services: what it shows for each answer, and what it asks the core to do. */

const now = signal(NOW);
const fakeTime: Partial<TimeService> = {
  now,
  minute: now,
  relative: (t) => (t ? `rel ${t.slice(11, 19)}` : ''),
  absolute: (t) => (t ? `abs ${t}` : ''),
  iso: (t) => t ?? '',
  clock: (t) => (t ? t.slice(11, 16) : ''),
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

const list = (items: ConnectionRow[] = kitRows()): ConnectionList => ({ items, facets: { health: {}, env: {}, type: {} }, total: items.length });

let calls: string[];
let events: FakeEvents;
let listAnswers: (() => Promise<ConnectionList>)[];
let testAnswer: () => Promise<TestOutcome>;
let testAllAnswer: () => Promise<{ connections: number }>;
let detailAnswer: () => Promise<ConnectionDetail>;
let deleteAnswer: () => Promise<ConnectionDeleted>;

function setup(first: () => Promise<ConnectionList> = () => Promise.resolve(list())): void {
  calls = [];
  events = new FakeEvents();
  listAnswers = [first];
  testAnswer = () => Promise.resolve(outcome());
  testAllAnswer = () => Promise.resolve({ connections: 12 });
  detailAnswer = () => Promise.resolve(detail('dev-db'));
  deleteAnswer = () => Promise.resolve({ stoppedTools: ['sql_query'], rejectedApprovals: 1, templates: [], clients: [], secretsDestroyed: [] });
  const connections: Partial<ConnectionsApi> = {
    list: (query) => {
      calls.push(query?.kind ? `list ${query.kind}` : 'list');
      const next = listAnswers.length > 1 ? listAnswers.shift()! : listAnswers[0];
      return next();
    },
    test: (name) => {
      calls.push(`test ${name}`);
      return testAnswer();
    },
    testAll: () => {
      calls.push('test-all');
      return testAllAnswer();
    },
    get: (name) => {
      calls.push(`get ${name}`);
      return detailAnswer();
    },
    delete: (name, confirmName) => {
      calls.push(`delete ${name} ${confirmName}`);
      return deleteAnswer();
    },
    confirmIntegrity: (name) => {
      calls.push(`keep ${name}`);
      return Promise.resolve({ ...kitRows().find((r) => r.name === name)!, verified: true });
    },
  };
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      provideKitIcons(),
      { provide: TimeService, useValue: fakeTime },
      { provide: ConnectionsApi, useValue: connections },
      { provide: SecretsApi, useValue: { list: () => Promise.resolve(SECRETS), shortLived: () => Promise.resolve(SHORT_LIVED) } },
      {
        provide: ApprovalsApi,
        useValue: { list: () => Promise.resolve({ items: [{ tool: 'sql_execute', connection: 'dev-db', client: 'codex', status: 'pending' }] }) },
      },
      { provide: EndpointsApi, useValue: { list: () => Promise.resolve([{ slug: 'dev', clients: ['claude-desktop', 'codex'] }]) } },
      { provide: ConnectorTypesApi, useValue: { get: () => Promise.resolve({ tools: ['sql_list_schemas', 'sql_describe_table', 'sql_query', 'sql_execute'].map((name) => ({ name })) }) } },
      { provide: EventStream, useValue: events },
      { provide: CoreStore, useValue: { running: signal(true), instanceChanges: signal(0) } },
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

async function render(inputs: Record<string, string> = {}): Promise<{ fixture: ComponentFixture<ConnectionsPage>; el: HTMLElement }> {
  const fixture = TestBed.createComponent(ConnectionsPage);
  for (const [k, v] of Object.entries(inputs)) fixture.componentRef.setInput(k, v);
  document.body.appendChild(fixture.nativeElement);
  await settle(fixture);
  return { fixture, el: fixture.nativeElement as HTMLElement };
}

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  for (let i = 0; i < 4; i++) {
    await fixture.whenStable();
    await sleep(0);
  }
  fixture.detectChanges();
  await fixture.whenStable();
}

const text = (el: Element | null | undefined) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
const rowOf = (el: HTMLElement, name: string) => el.querySelector(`#conn-link-${name}`)?.closest('tr') ?? null;
const shownNames = (el: HTMLElement) => Array.from(el.querySelectorAll('#conn-table tbody th[scope="row"] a')).map(text);
const toasts = () => TestBed.inject(ToastService).toasts();

describe('Connections page', () => {
  it('shows nothing for a moment, then the skeleton, then the table', async () => {
    const answer = deferred<ConnectionList>();
    setup(() => answer.promise);
    const { fixture, el } = await render();
    expect(text(el.querySelector('h1'))).toBe('Connections');
    expect(el.querySelector('.gw-skeleton')).toBeNull();
    expect(el.querySelector('.gw-how-it-works')).toBeNull();

    await sleep(SKELETON_DELAY_MS + 50);
    await settle(fixture);
    expect(el.querySelectorAll('.gw-skeleton').length).toBe(9);
    expect(el.querySelector('[aria-busy="true"]')?.getAttribute('aria-labelledby')).toBe('conn-skel-cap');
    expect(text(el.querySelector('[role="status"]'))).toBe('Loading connections');

    answer.resolve(list());
    await settle(fixture);
    expect(el.querySelector('.gw-skeleton')).toBeNull();
    expect(shownNames(el)).toHaveLength(12);
  });

  it('renders the kit’s table from the core’s rows', async () => {
    setup();
    const { el } = await render();

    expect(Array.from(el.querySelectorAll('.gw-page-header__meta .gw-status')).map(text)).toEqual(['10 healthy', '1 slow', '1 unreachable']);
    expect(text(el.querySelector('.gw-page-header__meta .gw-text-small'))).toBe('Last checked rel 12:29:42');
    expect(el.querySelectorAll('.gw-page-header .gw-btn--primary')).toHaveLength(1);
    // how-it-works is folded when there are connections.
    expect(el.querySelector('.gw-how-it-works__toggle')?.getAttribute('aria-expanded')).toBe('false');
    expect(text(el.querySelector('#conn-count'))).toBe('12 connections');

    const devDb = rowOf(el, 'dev-db')!;
    const cells = Array.from(devDb.children).map(text);
    expect(cells[0]).toBe('dev-db environment dev db.dev.internal:5432/app');
    expect(devDb.querySelector('a')?.getAttribute('href')).toBe('/connections/dev-db');
    expect(devDb.querySelector('.gw-env')?.className).toContain('gw-env--dev');
    expect(cells[1]).toBe('PostgreSQL');
    expect(cells[2]).toBe('Encrypted here');
    expect(cells[3]).toBe('Healthy · 12 ms Checked rel 12:29:00');
    expect(devDb.querySelector('time')?.getAttribute('title')).toBe(`abs ${at('14:29:00')}`);
    expect(cells[4]).toBe('Endpoint dev');
    expect(devDb.querySelector('td:nth-child(5) a')?.getAttribute('href')).toBe('/endpoints/dev');

    expect(text(rowOf(el, 'prod-replica')!.children[1])).toBe('PostgreSQL Read replica');
    expect(text(rowOf(el, 'prod-replica')!.children[2])).toBe('Configured');
    expect(text(rowOf(el, 'stage-db')!.children[2])).toBe('Vault reference kv/stage/db#password');
    expect(text(rowOf(el, 'billing-api')!.children[2])).toBe('GCP SM reference Webhook secret unresolved');
    expect(text(rowOf(el, 'cache-redis')!.children[3])).toBe('Slow · 1.8 s Checked rel 12:29:42');
    expect(text(rowOf(el, 'prod-logs')!.children[2])).toBe('Nothing stored here');

    // The error of the unreachable row sits under it, with what to do.
    const alert = rowOf(el, 'billing-api')!.nextElementSibling!;
    expect(text(alert.querySelector('.gw-alert__title'))).toBe('billing-api: Connection refused');
    expect(text(alert.querySelector('.gw-alert__body'))).toBe('Check the stage VPN or the billing service, then test again.');
    expect(alert.querySelector('a')?.getAttribute('href')).toBe('/activity?q=connection:billing-api%20decision:error');

    // The row menu names its object and its links.
    const menu = el.querySelector('#conn-menu-dev-db')!;
    expect(menu.getAttribute('aria-label')).toBe('Actions for connection dev-db');
    expect(Array.from(menu.querySelectorAll('[role="menuitem"]')).map(text)).toEqual([
      'Test connection',
      'Edit',
      // Label and hint as the kit writes them: `View activity<span class="gw-menu__hint">connection:…</span>`.
      'View activityconnection:dev-db',
      'Duplicate',
      'Delete connection',
    ]);
    expect(Array.from(menu.querySelectorAll('a')).map((a) => a.getAttribute('href'))).toEqual([
      '/connections/dev-db',
      '/activity?q=connection:dev-db',
      '/connections/new?duplicate=dev-db',
    ]);
  });


  it('shows what the core adds since step API: the type in its words, since when, the interval, no MCP servers', async () => {
    const rows = kitRows().map((r) =>
      r.name === 'dev-db'
        ? { ...r, typeLabel: 'PostgreSQL 16', serverVersion: 'PostgreSQL 16.4' }
        : r.name === 'billing-api'
          ? { ...r, health: { ...r.health, since: '2026-10-22T11:42:00Z' } }
          : r,
    );
    rows.push(row('github-mcp', { type: 'mcp-server', typeName: 'MCP server', typeLabel: 'MCP server' }));
    setup(() => Promise.resolve({ ...list(rows), healthIntervalSeconds: 60 }));
    const { el } = await render();

    expect(calls).toContain('list connection');
    expect(text(el.querySelector('.gw-page-header__meta .gw-text-small'))).toBe('Health is checked every minute');
    expect(text(rowOf(el, 'dev-db')!.children[1])).toBe('PostgreSQL 16');
    expect(text(rowOf(el, 'billing-api')!.nextElementSibling!.querySelector('.gw-alert__title'))).toBe('billing-api: Connection refused since 11:42');
    // External MCP servers are Connectors': not a row here, not counted.
    expect(rowOf(el, 'github-mcp')).toBeNull();
    expect(text(el.querySelector('#conn-count'))).toBe('12 connections');
  });
  it('filters at once, with live counts, and clears back to the search', async () => {
    setup();
    const { fixture, el } = await render();
    const search = el.querySelector<HTMLInputElement>('#conn-q')!;
    search.value = 'billing';
    search.dispatchEvent(new Event('input'));
    await settle(fixture);
    expect(shownNames(el)).toEqual(['billing-api']);
    expect(text(el.querySelector('#conn-count'))).toBe('1 of 12 connections');

    el.querySelector<HTMLButtonElement>('.gw-search__clear')!.click();
    await settle(fixture);
    expect(shownNames(el)).toHaveLength(12);

    // Type: a listbox; the trigger names the pick.
    el.querySelector<HTMLButtonElement>('[aria-controls="conn-type-menu"]')!.click();
    await settle(fixture);
    const options = Array.from(el.querySelectorAll<HTMLElement>('#conn-type-menu [role="option"]'));
    expect(options.map((o) => `${text(o.querySelector('.gw-menu__label'))} · ${text(o.querySelector('.gw-menu__hint'))}`)).toEqual([
      'All types · 12 connections',
      'PostgreSQL · 3 connections',
      'MongoDB · 1 connection',
      'Redis · 1 connection',
      'SSH · 2 connections',
      'Logs · 2 connections',
      'Kubernetes · 1 connection',
      'AWS · 1 connection',
      'HTTP API · 1 connection',
    ]);
    options[1].click();
    await settle(fixture);
    expect(text(el.querySelector('[aria-controls="conn-type-menu"]'))).toBe('Type: PostgreSQL');
    expect(shownNames(el)).toEqual(['dev-db', 'prod-replica', 'stage-db']);
    expect(el.querySelector('#conn-type-menu [aria-selected="true"]')?.getAttribute('data-value')).toBe('postgres');

    el.querySelector<HTMLInputElement>('input[name="conn-env"][value="prod"]')!.click();
    await settle(fixture);
    expect(shownNames(el)).toEqual(['prod-replica']);

    el.querySelector<HTMLButtonElement>('[aria-controls="conn-health-menu"]')!.click();
    await settle(fixture);
    expect(Array.from(el.querySelectorAll('#conn-health-menu [role="option"]')).map((o) => `${text(o.querySelector('.gw-menu__label'))} · ${text(o.querySelector('.gw-menu__hint'))}`)).toEqual([
      'Any health · 12 connections',
      'Healthy · 10 connections',
      'Slow · 1 connection',
      'Unreachable · 1 connection',
    ]);
    el.querySelector<HTMLElement>('#conn-health-menu [data-value="degraded"]')!.click();
    await settle(fixture);
    expect(text(el.querySelector('[aria-controls="conn-health-menu"]'))).toBe('Health: Slow');
    expect(shownNames(el)).toEqual([]);
    expect(text(el.querySelector('.gw-table__empty .gw-empty__title'))).toBe('No connections match these filters');
    expect(text(el.querySelector('#conn-count'))).toBe('0 of 12 connections');

    el.querySelector<HTMLButtonElement>('#conn-clear')!.click();
    await settle(fixture);
    expect(shownNames(el)).toHaveLength(12);
    expect(text(el.querySelector('[aria-controls="conn-type-menu"]'))).toBe('Type: All types');
    expect(el.querySelector<HTMLInputElement>('input[name="conn-env"][value="all"]')!.checked).toBe(true);
    expect(document.activeElement).toBe(search);
  });

  it('takes its filters from the link', async () => {
    setup();
    const { el } = await render({ type: 'postgres', health: 'slow', env: 'dev' });
    expect(text(el.querySelector('[aria-controls="conn-type-menu"]'))).toBe('Type: PostgreSQL');
    expect(text(el.querySelector('[aria-controls="conn-health-menu"]'))).toBe('Health: Slow');
    expect(el.querySelector<HTMLInputElement>('input[name="conn-env"][value="dev"]')!.checked).toBe(true);
    expect(shownNames(el)).toEqual([]);
  });

  it('takes a search from the link', async () => {
    setup();
    const { el } = await render({ q: 'stage' });
    expect(el.querySelector<HTMLInputElement>('#conn-q')!.value).toBe('stage');
    expect(shownNames(el)).toEqual(['aws-stage', 'billing-api', 'stage-db']);
  });

  it('shows the first run: no toolbar or table, how-it-works open', async () => {
    setup(() => Promise.resolve(list([])));
    const { el } = await render();
    expect(el.querySelector('#conn-table')).toBeNull();
    expect(el.querySelector('.gw-toolbar')).toBeNull();
    expect(el.querySelector('.gw-page-header__meta')).toBeNull();
    expect(text(el.querySelector('.gw-empty__title'))).toBe('No connections yet');
    expect(el.querySelector('.gw-empty__actions a')?.getAttribute('href')).toBe('/connections/new');
    expect(el.querySelector('.gw-how-it-works__toggle')?.getAttribute('aria-expanded')).toBe('true');
  });

  it('says why the list didn’t load, and loads it again', async () => {
    setup(() => Promise.reject(new ApiError('server', 500, { title: 'Server error', detail: 'The connection list could not be read.' })));
    const { fixture, el } = await render();
    const alert = el.querySelector('.gw-alert--danger')!;
    expect(alert.getAttribute('role')).toBe('alert');
    expect(text(alert.querySelector('.gw-alert__title'))).toBe("The connections didn't load");
    expect(text(alert.querySelector('.gw-alert__body'))).toBe('The connection list could not be read.');
    expect(el.querySelector('#conn-error-details')?.hasAttribute('hidden')).toBe(true);

    listAnswers = [() => Promise.resolve(list())];
    Array.from(alert.querySelectorAll('button')).find((b) => text(b) === 'Try again')!.click();
    await settle(fixture);
    expect(el.querySelector('.gw-alert--danger[role="alert"]')).toBeNull();
    expect(shownNames(el)).toHaveLength(12);
  });

  it('keeps the rows when a reload fails, and says they may be out of date', async () => {
    setup();
    const { fixture, el } = await render();
    listAnswers = [() => Promise.reject(new ApiError('network', 0, {}))];
    // A test reloads the list afterwards.
    el.querySelector<HTMLButtonElement>('#conn-menu-dev-db [role="menuitem"]')!.click();
    await settle(fixture);
    expect(text(el.querySelector('.gw-alert--warning .gw-alert__title'))).toBe('This list may be out of date.');
    expect(shownNames(el)).toHaveLength(12);
  });

  it('tests one connection in place and reports the result', async () => {
    setup();
    const { fixture, el } = await render();
    const answer = deferred<TestOutcome>();
    testAnswer = () => answer.promise;
    el.querySelector<HTMLButtonElement>('[aria-controls="conn-menu-dev-db"]')!.click();
    el.querySelector<HTMLButtonElement>('#conn-menu-dev-db [role="menuitem"]')!.click();
    await settle(fixture);
    const devDb = rowOf(el, 'dev-db')!;
    expect(text(devDb.querySelector('.gw-status--checking'))).toBe('Checking');
    // A second test of the same row while it runs is unavailable, with the reason.
    expect(el.querySelector('#conn-menu-dev-db [role="menuitem"]')?.getAttribute('aria-disabled')).toBe('true');
    expect(text(el.querySelector('#conn-menu-dev-db [role="menuitem"] .gw-menu__hint'))).toBe('Testing now');

    answer.resolve(outcome({ latencyMs: 9 }));
    await settle(fixture);
    expect(devDb.querySelector('.gw-status--checking')).toBeNull();
    expect(toasts().at(-1)).toMatchObject({ tone: 'success', title: 'dev-db is healthy', body: 'Connected in 9 ms.' });
    expect(calls).toEqual(['list connection', 'test dev-db', 'list connection']);
  });

  it('marks Test again as busy while the unreachable row is tested', async () => {
    setup();
    const { fixture, el } = await render();
    const answer = deferred<TestOutcome>();
    testAnswer = () => answer.promise;
    const again = () => Array.from(rowOf(el, 'billing-api')!.nextElementSibling!.querySelectorAll('button')).find((b) => text(b).startsWith('Test again'))!;
    expect(text(again())).toBe('Test again billing-api');
    again().click();
    await settle(fixture);
    expect(again().getAttribute('aria-busy')).toBe('true');
    expect(again().getAttribute('aria-disabled')).toBe('true');
    expect(again().getAttribute('aria-describedby')).toBe('conn-checking-billing-api');
    expect(el.querySelector('#conn-checking-billing-api')).not.toBeNull();
    // aria-disabled doesn't run it twice.
    again().click();
    answer.resolve(outcome({ ok: false, error: { code: 'refused', message: 'Connection refused. Check the VPN.', technical: null } }));
    await settle(fixture);
    expect(calls.filter((c) => c === 'test billing-api')).toHaveLength(1);
    expect(toasts().at(-1)).toMatchObject({ tone: 'danger', title: 'billing-api is unreachable', body: 'Connection refused. Check the VPN.' });
  });

  it('runs Test all: busy in place, rows check until their events, then one toast', async () => {
    setup();
    const { fixture, el } = await render();
    const button = el.querySelector<HTMLButtonElement>('#conn-test-all')!;
    button.click();
    await settle(fixture);
    expect(button.getAttribute('aria-busy')).toBe('true');
    expect(button.getAttribute('aria-disabled')).toBe('true');
    expect(button.getAttribute('aria-describedby')).toBe('conn-count');
    expect(button.querySelector('.gw-spinner')).not.toBeNull();
    expect(text(el.querySelector('#conn-count'))).toBe('Testing 12 connections');
    expect(el.querySelectorAll('#conn-table .gw-status--checking')).toHaveLength(12);

    button.click(); // aria-disabled: no second run
    expect(calls.filter((c) => c === 'test-all')).toHaveLength(1);

    // The core records each result: the reload after Test all reads them back.
    const tested = kitRows().map((r) => ({ ...r, health: { state: r.name === 'billing-api' ? 'unreachable' : 'healthy', latencyMs: 20, message: null, checkedAt: at('14:30:10') } }));
    listAnswers = [() => Promise.resolve(list(tested))];
    for (const r of kitRows()) {
      events.emit('connection.health', { name: r.name, env: r.env, state: r.name === 'billing-api' ? 'unreachable' : 'healthy', latencyMs: 20, message: null, checkedAt: at('14:30:10') });
    }
    await settle(fixture);
    expect(button.hasAttribute('aria-busy')).toBe(false);
    expect(el.querySelectorAll('#conn-table .gw-status--checking')).toHaveLength(0);
    expect(text(rowOf(el, 'cache-redis')!.children[3])).toBe('Healthy · 20 ms Checked rel 12:30:10');
    expect(toasts().at(-1)).toMatchObject({ tone: 'warning', title: '12 connections tested', body: '11 healthy, 0 slow, 1 unreachable.' });
  });

  it('says when Test all didn’t start', async () => {
    setup();
    const { fixture, el } = await render();
    testAllAnswer = () => Promise.reject(new ApiError('conflict', 409, { detail: 'Test all is still running; results arrive one by one.' }));
    el.querySelector<HTMLButtonElement>('#conn-test-all')!.click();
    await settle(fixture);
    expect(toasts().at(-1)).toMatchObject({ tone: 'warning', title: "Test all didn't start", body: 'Test all is still running; results arrive one by one.' });
    expect(el.querySelector('#conn-test-all')!.hasAttribute('aria-busy')).toBe(false);
  });

  it('confirms a delete with what stops working, then moves to the search', async () => {
    setup();
    const { fixture, el } = await render();
    const trigger = el.querySelector<HTMLButtonElement>('[aria-controls="conn-menu-dev-db"]')!;
    trigger.click();
    el.querySelector<HTMLButtonElement>('#conn-menu-dev-db .gw-menu__item--danger')!.click();
    await settle(fixture);
    const dialog = el.querySelector<HTMLDialogElement>('#dlg-delete-connection')!;
    expect(dialog.open).toBe(true);
    expect(dialog.getAttribute('aria-labelledby')).toBe('dlg-del-title');
    expect(text(dialog.querySelector('#dlg-del-title'))).toBe('Delete connection dev-db?');
    // Cancel has the focus; Enter never deletes.
    expect(text(document.activeElement)).toBe('Cancel');
    expect(text(dialog.querySelector('#dlg-del-desc'))).toBe("Agents lose it on their next call. Its encrypted password (version 3) is destroyed. This can't be undone.");
    expect(text(dialog.querySelector('.gw-dialog__body'))).toContain(
      'Endpoint dev 4 tools: sql_list_schemas, sql_describe_table, sql_query, sql_execute · used by Claude Desktop and Codex',
    );
    expect(text(dialog.querySelector('.gw-alert--warning'))).toBe('1 pending approval is denied sql_execute from Codex');

    const confirm = dialog.querySelector<HTMLButtonElement>('.gw-btn--danger')!;
    expect(text(confirm)).toBe('Delete connection dev-db');
    expect(confirm.hasAttribute('aria-disabled')).toBe(false);
    listAnswers = [() => Promise.resolve(list(kitRows().filter((r) => r.name !== 'dev-db')))];
    confirm.click();
    await settle(fixture);
    expect(calls).toContain('delete dev-db dev-db');
    expect(dialog.open).toBe(false);
    expect(rowOf(el, 'dev-db')).toBeNull();
    expect(toasts().at(-1)).toMatchObject({ tone: 'success', title: 'Connection dev-db deleted', body: 'Removed from dev. 1 pending approval denied.' });
    expect(document.activeElement).toBe(el.querySelector('#conn-q'));
  });

  it('waits for the facts before Delete, and keeps the dialog open when the core refuses', async () => {
    setup();
    const { fixture, el } = await render();
    const facts = deferred<ConnectionDetail>();
    detailAnswer = () => facts.promise;
    el.querySelector<HTMLButtonElement>('[aria-controls="conn-menu-dev-db"]')!.click();
    el.querySelector<HTMLButtonElement>('#conn-menu-dev-db .gw-menu__item--danger')!.click();
    await settle(fixture);
    const dialog = el.querySelector<HTMLDialogElement>('#dlg-delete-connection')!;
    const confirm = dialog.querySelector<HTMLButtonElement>('.gw-btn--danger')!;
    expect(confirm.getAttribute('aria-disabled')).toBe('true');
    expect(confirm.getAttribute('aria-describedby')).toBe('dlg-del-loading');
    confirm.click();
    expect(calls.some((c) => c.startsWith('delete'))).toBe(false);

    facts.resolve(detail('dev-db'));
    deleteAnswer = () => Promise.reject(new ApiError('unprocessable', 422, { detail: 'Type dev-db to confirm.' }));
    await settle(fixture);
    confirm.click();
    await settle(fixture);
    expect(dialog.open).toBe(true);
    expect(text(dialog.querySelector('.gw-alert--danger'))).toBe("dev-db wasn't deleted Type dev-db to confirm.");
    expect(rowOf(el, 'dev-db')).not.toBeNull();

    // Cancel: focus back on the row's menu button.
    Array.from(dialog.querySelectorAll('button')).find((b) => text(b) === 'Cancel')!.click();
    await settle(fixture);
    expect(dialog.open).toBe(false);
    expect(document.activeElement).toBe(el.querySelector('[aria-controls="conn-menu-dev-db"]'));
  });

  it('keeps a row changed outside GateRook only after asking', async () => {
    setup(() => Promise.resolve(list(kitRows().map((r) => (r.name === 'dev-db' ? { ...r, verified: false } : r)))));
    const { fixture, el } = await render();
    const item = el.querySelector('#conn-menu-dev-db [role="menuitem"]')!;
    expect(item.getAttribute('aria-disabled')).toBe('true');
    expect(text(item.querySelector('.gw-menu__hint'))).toBe('Review the change first');

    const alert = rowOf(el, 'dev-db')!.nextElementSibling!;
    expect(text(alert.querySelector('.gw-alert__title'))).toBe('dev-db: Changed outside GateRook');
    expect(alert.querySelector('.gw-alert--warning')).not.toBeNull();
    expect(Array.from(alert.querySelectorAll('a')).map((a) => a.getAttribute('href'))).toEqual(['/connections/dev-db']);

    listAnswers = [() => Promise.resolve(list())];
    Array.from(alert.querySelectorAll('button')).find((b) => text(b).startsWith('Keep as stored'))!.click();
    await settle(fixture);
    const dialog = el.querySelector<HTMLDialogElement>('#dlg-keep-connection')!;
    expect(dialog.open).toBe(true);
    expect(dialog.classList).toContain('gw-dialog--warning');
    expect(text(document.activeElement)).toBe('Cancel');
    expect(text(dialog.querySelector('#dlg-keep-title'))).toBe('Keep dev-db as stored?');
    dialog.querySelector<HTMLButtonElement>('.gw-btn--primary')!.click();
    await settle(fixture);
    expect(calls).toContain('keep dev-db');
    expect(dialog.open).toBe(false);
    expect(toasts().at(-1)).toMatchObject({ tone: 'success', title: 'dev-db is in use again' });
    expect(rowOf(el, 'dev-db')!.nextElementSibling?.querySelector('.gw-alert')).toBeFalsy();
  });

  it('follows health events: the counts, the cell and the alert', async () => {
    setup();
    const { fixture, el } = await render();
    events.emit('connection.health', { name: 'billing-api', env: 'stage', state: 'healthy', latencyMs: 80, message: null, checkedAt: at('14:30:20') });
    await settle(fixture);
    expect(Array.from(el.querySelectorAll('.gw-page-header__meta .gw-status')).map(text)).toEqual(['11 healthy', '1 slow']);
    expect(text(rowOf(el, 'billing-api')!.children[3])).toBe('Healthy · 80 ms Checked rel 12:30:20');
    expect(rowOf(el, 'billing-api')!.nextElementSibling?.querySelector('.gw-alert')).toBeFalsy();
  });
});
