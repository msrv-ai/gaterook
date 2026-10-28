import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { ActivityApi, EventsPage, Histogram, SavedView, SetupChange, TraceDetail, TraceRow } from '../../core/api/activity.api';
import { ApiError } from '../../core/api/api-error';
import { ApprovalsApi } from '../../core/api/approvals.api';
import { ConnectionsApi } from '../../core/api/connections.api';
import { TokensApi } from '../../core/api/endpoints.api';
import { AuditExportApi, SettingsApi } from '../../core/api/settings.api';
import { DesktopBridge } from '../../core/desktop/desktop-bridge';
import { EventStream } from '../../core/events/event-stream';
import { CoreStore } from '../../core/stores/core.store';
import { TimeService } from '../../core/time/time.service';
import { Announcer } from '../../kit/announcer';
import { AriaDisabledGuard } from '../../kit/aria-disabled';
import { provideKitIcons } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { ENDED_TASK, FACETS, HOUR, NOW, WAITING_TASK, ago, call, devTokens, histogram, kitCalls, kitChanges, kitHistogram, traceDetail } from './activity.fixtures';
import { ActivityPage, SKELETON_DELAY_MS } from './activity.page';

/** The Activity page against fake admin API services, at /activity in a router: what it shows and what it asks. */

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

const now = signal(NOW);
const fakeTime: Partial<TimeService> = {
  now,
  minute: now,
  relative: (t) => (t ? `rel ${Math.round((NOW - Date.parse(t)) / 1000)}s` : ''),
  absolute: (t) => (t ? `abs ${t}` : ''),
  iso: (t) => t ?? '',
  clock: (t) => (t ? `clock ${t.slice(11, 16)}` : ''),
  date: (t) => (t ? 'Today' : ''),
};

const problem = (kind: ConstructorParameters<typeof ApiError>[0], status: number, detail = 'Nope.', field: string | null = null) =>
  new ApiError(kind, status, { title: kind === 'unprocessable' ? 'Unknown filter' : 'Refused', detail, field });

function deferred<T>() {
  let resolve!: (v: T) => void;
  const promise = new Promise<T>((res) => (resolve = res));
  return { promise, resolve };
}

let requests: { path: string; req: unknown }[];
let events: FakeEvents;
let desktop: { available: boolean; saveFile: (p: string) => Promise<string | null> };
let core: {
  histogram: (q: string) => Promise<Histogram>;
  events: (q: string) => Promise<EventsPage>;
  traces: () => Promise<{ traces: TraceRow[]; nextCursor: string | null }>;
  trace: (id: string) => Promise<TraceDetail>;
  changes: (cursor: string | null | undefined) => Promise<{ changes: SetupChange[]; nextCursor: string | null }>;
  event: (id: number) => Promise<{ event: ReturnType<typeof call>; rows: [] }>;
  saveViews: (views: SavedView[], version: number) => Promise<{ views: SavedView[]; version: number }>;
  /** Whether any call ever reached the gateway (the first-run check asks for one call of all time). */
  ever: boolean;
};

beforeEach(() => {
  now.set(NOW);
  requests = [];
  events = new FakeEvents();
  desktop = { available: true, saveFile: async () => 'save-token' };
  core = {
    histogram: async (q) => (q ? histogram([]) : kitHistogram()),
    events: async (q) => ({ events: q ? [] : kitCalls(), nextCursor: q ? null : 'c1' }),
    traces: async () => ({ traces: [WAITING_TASK, ENDED_TASK], nextCursor: null }),
    trace: async (id) => (id === WAITING_TASK.traceId ? traceDetail(WAITING_TASK, kitCalls().slice(0, 3).reverse()) : Promise.reject(problem('not-found', 404))),
    changes: async (cursor) => (cursor ? { changes: [], nextCursor: null } : { changes: kitChanges(), nextCursor: 'older' }),
    event: async (id) => {
      const e = kitCalls().find((c) => c.id === id);
      if (!e) throw problem('not-found', 404);
      return { event: e, rows: [] };
    },
    saveViews: async (views, version) => ({ views, version: version + 1 }),
    ever: true,
  };
  const log = (path: string, req: unknown) => requests.push({ path, req });
  const q = (req: unknown) => ((req as { q?: string }).q ?? '').trim();
  const api: Partial<ActivityApi> = {
    histogram: (req = {}) => (log('histogram', req), core.histogram(q(req))),
    facets: (req = {}) => (log('facets', req), Promise.resolve(FACETS)),
    events: (req = {}) => {
      log('events', req);
      return (req as { limit?: number }).limit === 1 ? Promise.resolve({ events: core.ever ? kitCalls().slice(0, 1) : [], nextCursor: null }) : core.events(q(req));
    },
    event: (id) => (log('event', id), core.event(id)),
    traces: () => (log('traces', null), core.traces()),
    trace: (id) => (log('trace', id), core.trace(id)),
    changes: (_q, cursor) => (log('changes', cursor), core.changes(cursor)),
    export: (req) => (log('export', req), Promise.resolve({ path: '/home/me/activity.csv', rows: 10, bytes: 1000, format: req.format })),
    views: () => Promise.resolve({ views: [], version: 3 }),
    saveViews: (views, version) => (log('save-views', { views, version }), core.saveViews(views, version)),
  };
  const tokens: Partial<TokensApi> = {
    list: () => Promise.resolve(devTokens()),
    revoke: (id) => (log('revoke', id), Promise.resolve({ id, rejectedApprovals: 0 })),
    revokeGrant: (id) => (log('revoke-grant', id), Promise.resolve({ id, rejectedApprovals: 2 })),
  };
  @Component({ template: '' })
  class Elsewhere {}
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: 'activity', component: ActivityPage }, { path: '**', component: Elsewhere }], withComponentInputBinding()),
      provideKitIcons(),
      { provide: TimeService, useValue: fakeTime },
      { provide: ActivityApi, useValue: api },
      { provide: TokensApi, useValue: tokens },
      { provide: ApprovalsApi, useValue: { get: () => Promise.reject(problem('not-found', 404)) } },
      { provide: ConnectionsApi, useValue: { list: () => Promise.resolve({ items: [{ name: 'dev-db', env: 'dev' }] }) } },
      { provide: SettingsApi, useValue: { get: () => Promise.resolve({ values: { retentionYears: 2 } }) } },
      { provide: AuditExportApi, useValue: { list: () => Promise.reject(problem('not-available', 404)) } },
      { provide: EventStream, useValue: events },
      { provide: CoreStore, useValue: { running: signal(true), instanceChanges: signal(0) } },
      { provide: DesktopBridge, useFactory: () => desktop },
    ],
  });
  TestBed.inject(AriaDisabledGuard).install();
});

afterEach(() => {
  document.body.innerHTML = '';
  TestBed.inject(ToastService).clear();
  localStorage.clear();
});

const text = (el: Element | null | undefined) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
const of = (path: string) => requests.filter((r) => r.path === path).map((r) => r.req);
const toasts = () => TestBed.inject(ToastService).toasts();

async function open(url = '/activity') {
  const harness = await RouterTestingHarness.create();
  document.body.appendChild(harness.fixture.nativeElement);
  await harness.navigateByUrl(url);
  await settle(harness);
  const el = harness.routeNativeElement as HTMLElement;
  const router = TestBed.inject(Router);
  return {
    harness,
    el,
    router,
    settle: () => settle(harness),
    rows: () => Array.from(el.querySelectorAll<HTMLElement>('#ac-events tbody tr')),
    button: (name: string, root: ParentNode = el) =>
      Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find((b) => text(b) === name || text(b).startsWith(`${name} `))!,
  };
}

async function settle(harness: RouterTestingHarness): Promise<void> {
  for (let i = 0; i < 8; i++) {
    await new Promise((r) => setTimeout(r));
    harness.detectChanges();
  }
  await harness.fixture.whenStable();
}

describe('Activity page', () => {
  it('shows nothing for a moment, then placeholders, then the calls', async () => {
    const answer = deferred<EventsPage>();
    core.events = () => answer.promise;
    const { el, settle, rows } = await open();
    expect(text(el.querySelector('h1'))).toBe('Activity');
    await new Promise((r) => setTimeout(r, SKELETON_DELAY_MS + 30));
    await settle();
    expect(el.querySelectorAll('#ac-panel-events .gw-skeleton').length).toBe(30);
    expect(text(el.querySelector('#ac-panel-events [role="status"]'))).toBe('Loading calls');
    answer.resolve({ events: kitCalls(), nextCursor: null });
    await settle();
    expect(el.querySelector('#ac-panel-events .gw-skeleton')).toBeNull();
    expect(rows()).toHaveLength(10);
  });

  it('renders the kit’s header, tabs and events table from the core’s answers', async () => {
    const { el, rows } = await open();
    expect(text(el.querySelector('.gw-page-header__description'))).toBe(
      'Every call an agent made through the gateway, and every change people made to its setup. Append-only, kept 2 years.',
    );
    // No primary action on the page itself (the dialogs have theirs).
    expect(Array.from(el.querySelectorAll('.gw-btn--primary')).filter((b) => !b.closest('dialog'))).toHaveLength(0);
    expect(text(el.querySelector('#ac-tab-calls'))).toBe('Agent calls 10 calls');
    expect(text(el.querySelector('#ac-tab-changes'))).toBe('Setup changes 4 changes');
    expect(text(el.querySelector('#ac-tab-events'))).toBe('Events 10 events');
    expect(text(el.querySelector('#ac-tab-traces'))).toBe('Traces 2 traces');
    expect(text(el.querySelector('.gw-log-explorer [role="status"]'))).toBe('10 calls · 2 waiting for approval');
    expect(el.querySelector('section.gw-log-explorer')!.getAttribute('aria-label')).toBe('Call log');
    expect(text(el.querySelector('#ac-table-cap'))).toBe("Calls in the last 24 hours, newest first. Each row opens the call's details.");

    const [pending, , allowed, ssh, denied] = rows();
    const cells = (tr: HTMLElement) => Array.from(tr.children).map(text);
    expect(cells(pending)).toEqual([
      'rel 30s at clock ' + kitCalls()[0].at.slice(11, 16) + ', open call http_request on billing-api',
      'Waiting for approvalReview http_request on billing-api in Approvals',
      'http_requestbilling-api environment stage',
      'Cx Codex, endpoint dev',
      '30 s so far',
    ]);
    expect(pending.querySelector('a.gw-table__row-link')!.getAttribute('href')).toBe('/activity#event-18');
    expect(pending.querySelector('a.gw-btn')!.getAttribute('href')).toBe('/approvals/ap_http');
    expect(pending.querySelector('.gw-badge')!.className).toContain('gw-badge--warning');
    expect(pending.querySelector('.gw-env')!.className).toContain('gw-env--stage');
    expect(cells(allowed)[4]).toBe('38 ms');
    expect(cells(ssh)[3]).toBe('CD Claude Desktop, endpoint dev');
    expect(cells(denied)[1]).toBe('Denied by policy');
    expect(cells(denied)[4]).toBe('Not run');
    expect(text(el.querySelector('#ac-pager-info'))).toBe('All 10 calls in the last 24 hours');
    expect(el.querySelector('#ac-pager button')).not.toBeNull();
  });

  it('sorts by duration and loads older calls', async () => {
    const { el, settle, rows, button } = await open();
    const sort = el.querySelector<HTMLButtonElement>('th[data-sort-key="duration"] .gw-table__sort')!;
    sort.click();
    sort.click();
    await settle();
    expect(el.querySelector('th[data-sort-key="duration"]')!.getAttribute('aria-sort')).toBe('descending');
    expect(el.querySelector('th[data-sort-key="time"]')!.getAttribute('aria-sort')).toBe('none');
    // Longest first: the 48 s wait still going, 47 s approved, the 30 s wait.
    expect(rows().slice(0, 3).map((r) => r.getAttribute('data-event'))).toEqual(['17', '4', '18']);
    core.events = async () => ({ events: [call(0, 3 * HOUR, 'allowed', 'sql_query')], nextCursor: null });
    button('Load older calls').click();
    await settle();
    expect(rows()).toHaveLength(11);
    expect(el.querySelector('#ac-pager button')).toBeNull();
    expect(of('events').at(-1)).toMatchObject({ cursor: 'c1' });
  });

  it('a row opens the call in the drawer at #event-<id>; closing it drops the fragment', async () => {
    const { el, settle, rows, router } = await open();
    rows()[1].querySelector<HTMLAnchorElement>('a.gw-table__row-link')!.click();
    await settle();
    expect(router.url).toBe('/activity#event-17');
    const drawer = el.querySelector<HTMLDialogElement>('dialog.gw-drawer')!;
    expect(drawer.open).toBe(true);
    expect(text(drawer.querySelector('.gw-drawer__title'))).toBe('Event ev_17');
    expect(text(drawer.querySelector('.gw-drawer__description'))).toBe('Call 2 of 5 in trace c7e19d');
    expect(rows()[1].querySelector('a.gw-table__row-link')!.getAttribute('aria-current')).toBe('true');
    expect(text(drawer.querySelector('.gw-btn--danger-ghost'))).toBe('Revoke codex-laptop');
    drawer.querySelector<HTMLButtonElement>('.gw-drawer__close')!.click();
    await settle();
    expect(drawer.open).toBe(false);
    expect(router.url).toBe('/activity');
    expect(rows()[1].querySelector('a.gw-table__row-link')!.hasAttribute('aria-current')).toBe(false);
  });

  it('a link to a call the audit log doesn’t have says so', async () => {
    const announce = vi.spyOn(TestBed.inject(Announcer), 'announce');
    const { el, router } = await open('/activity?view=changes#event-999');
    expect(text(el.querySelector('#ac-missing .gw-alert__title'))).toBe('Event not in the audit log');
    expect(text(el.querySelector('#ac-missing .gw-alert__body'))).toBe(
      'Event 999 is not among the calls the audit log keeps. Calls older than 2 years are removed; check the link you followed.',
    );
    expect(announce).toHaveBeenCalledWith('Event 999 is not in the audit log');
    expect(el.querySelector<HTMLElement>('#ac-panel-events')!.hidden).toBe(false);
    expect(router.url).toBe('/activity');
    expect(el.querySelector<HTMLDialogElement>('dialog.gw-drawer')!.open).toBe(false);
  });

  it('Revoke in the drawer asks first, Cancel focused, then revokes the grant', async () => {
    const { el, settle, button } = await open('/activity#event-17');
    const drawer = el.querySelector<HTMLDialogElement>('dialog.gw-drawer')!;
    drawer.querySelector<HTMLButtonElement>('.gw-btn--danger-ghost')!.click();
    await settle();
    const dialog = el.querySelector<HTMLDialogElement>('#ac-revoke')!;
    expect(dialog.open).toBe(true);
    expect(text(dialog.querySelector('h2'))).toBe('Revoke codex-laptop?');
    expect(text(dialog.querySelector('.gw-dialog__description'))).toContain('so Codex loses access to endpoint dev on its next call');
    expect(Array.from(dialog.querySelectorAll('.gw-kv__row')).map((r) => `${text(r.querySelector('dt'))}: ${text(r.querySelector('dd'))}`)).toEqual([
      'Access: codex-laptop · 15-minute tokens issued on the fly, none stored',
      'Used by: Codex, through gw-bridge headers, on endpoint dev',
      'Last used: rel 30s',
    ]);
    expect(document.activeElement).toBe(button('Cancel', dialog));
    expect(dialog.querySelectorAll('.gw-btn--danger')).toHaveLength(1);
    button('Revoke codex-laptop', dialog).click();
    await settle();
    expect(of('revoke-grant')).toEqual(['gr_codex-laptop']);
    expect(dialog.open).toBe(false);
    expect(toasts().at(-1)).toMatchObject({ tone: 'success', title: 'codex-laptop revoked', body: 'Codex loses access on its next call. 2 waiting requests were denied.' });
  });

  it('Export writes the listed calls to the file the save dialog chose; outside the desktop it says why not', async () => {
    const { el, settle, button } = await open('/activity?q=env:dev');
    button('Export').click();
    el.querySelector<HTMLButtonElement>('#ac-export [data-value="csv"]')!.click();
    await settle();
    expect(of('export')).toEqual([{ q: 'env:dev', from: ago(24 * HOUR), to: null, target: 'file', format: 'csv', saveToken: 'save-token' }]);
    expect(toasts().at(-1)).toMatchObject({ title: '10 calls exported', body: '/home/me/activity.csv. Masked values stay masked.' });
    // A cancelled save dialog does nothing.
    desktop.saveFile = async () => null;
    button('Export').click();
    el.querySelector<HTMLButtonElement>('#ac-export [data-value="jsonl"]')!.click();
    await settle();
    expect(of('export')).toHaveLength(1);
    // Main refusing the file (the core not ready, a place it won't write) is said, not lost.
    desktop.saveFile = () => Promise.reject(new Error("Error invoking remote method 'gw:saveFile': Error: The core isn't running."));
    button('Export').click();
    el.querySelector<HTMLButtonElement>('#ac-export [data-value="jsonl"]')!.click();
    await settle();
    expect(of('export')).toHaveLength(1);
    expect(toasts().at(-1)).toMatchObject({ tone: 'danger', title: "The calls weren't exported", body: "The core isn't running." });
  });

  it('outside the desktop app the file exports are unavailable, with the reason in the menu note', async () => {
    desktop = { available: false, saveFile: async () => null };
    const { el } = await open();
    expect(el.querySelector('#ac-export [data-value="csv"]')!.getAttribute('aria-disabled')).toBe('true');
    expect(el.querySelector('#ac-export')!.getAttribute('aria-describedby')).toBe('ac-export-note');
    expect(text(el.querySelector('#ac-export-note'))).toBe(
      'Exports the 10 calls that match the filters and range below. Masked values stay masked. Saving a file needs the GateRook desktop app.',
    );
  });

  it('Send to SIEM says it isn’t available before the core has destinations', async () => {
    const { el, settle, button } = await open();
    button('Export').click();
    el.querySelector<HTMLButtonElement>('#ac-export [data-value="siem"]')!.click();
    await settle();
    const dialog = el.querySelector<HTMLDialogElement>('#ac-siem')!;
    expect(dialog.open).toBe(true);
    expect(text(dialog.querySelector('#ac-siem-desc'))).toBe('Sends the 10 calls of the last 24 hours. Masked values stay masked, and nothing is removed from this log.');
    expect(text(dialog.querySelector('#ac-siem-off'))).toContain('Not available yet');
    const send = button('Send 10 calls', dialog);
    expect(send.getAttribute('aria-disabled')).toBe('true');
    expect(send.getAttribute('aria-describedby')).toBe('ac-siem-off');
    expect(dialog.querySelector('input[value="cef"]')!.getAttribute('aria-disabled')).toBe('true');
    send.click();
    await settle();
    expect(of('export')).toEqual([]);
  });

  it('Send to SIEM lists every destination; the ones the core refuses say why and can’t be chosen', async () => {
    const dest = (id: string, name: string, over: object = {}) => ({ id, kind: 'siem', name, enabled: true, verified: true, demo: false, config: {}, schedule: 'stream', state: 'ok', failingSince: null, lastAttemptAt: null, lastError: null, ...over });
    (TestBed.inject(AuditExportApi) as { list: () => Promise<unknown> }).list = () =>
      Promise.resolve([dest('d1', 'Paused SIEM', { enabled: false }), dest('d2', 'Changed SIEM', { verified: false }), dest('d3', 'Splunk')]);
    const { el, settle, button } = await open();
    button('Export').click();
    el.querySelector<HTMLButtonElement>('#ac-export [data-value="siem"]')!.click();
    await settle();
    const dialog = el.querySelector<HTMLDialogElement>('#ac-siem')!;
    const options = Array.from(dialog.querySelectorAll<HTMLOptionElement>('#ac-siem-dest option'));
    expect(options.map((o) => `${text(o)}${o.disabled ? ' [off]' : ''}`)).toEqual([
      'Paused SIEM (paused) [off]',
      'Changed SIEM (changed outside GateRook) [off]',
      'Splunk',
    ]);
    // The first one the core takes is chosen; Send goes to it.
    expect(options[2].selected).toBe(true);
    button('Send 10 calls', dialog).click();
    await settle();
    expect(of('export')).toEqual([expect.objectContaining({ target: 'siem', destinationId: 'd3' })]);
  });

  it('?range= from an Insights link sets the range the calls are read for; a new range goes back into the address', async () => {
    const { el, settle, router } = await open('/activity?q=client:codex&range=7d');
    const last = of('histogram').at(-1) as { from: string };
    expect(NOW - Date.parse(last.from)).toBe(7 * 24 * HOUR);
    expect(text(el.querySelector('[data-gw-menu-value]'))).toBe('Last 7 days');
    // "View in Insights" carries the range back.
    expect(el.querySelector('#ac-insights-link')!.getAttribute('href')).toBe('/insights?q=client:codex&range=7d');

    await router.navigateByUrl('/activity?q=client:codex&range=30d');
    await settle();
    expect(NOW - Date.parse((of('histogram').at(-1) as { from: string }).from)).toBe(30 * 24 * HOUR);
    const trigger = el.querySelector('[data-gw-menu-value]')!.closest('button')!;
    expect(text(trigger.querySelector('[data-gw-menu-value]'))).toBe('Last 30 days');

    // A range picked here: the address says it (and the default says nothing).
    trigger.click();
    await settle();
    el.querySelector<HTMLButtonElement>('[role="menu"][aria-label="Time range"] [data-value="1h"]')!.click();
    await settle();
    expect(router.url).toBe('/activity?q=client:codex&range=1h');
    trigger.click();
    await settle();
    el.querySelector<HTMLButtonElement>('[role="menu"][aria-label="Time range"] [data-value="24h"]')!.click();
    await settle();
    expect(router.url).toBe('/activity?q=client:codex');
  });

  it('saves the current view under a name', async () => {
    const { el, settle, button } = await open('/activity?q=env:prod');
    button('Saved views').click();
    await settle();
    Array.from(el.querySelectorAll<HTMLButtonElement>('[role="menuitem"]')).find((b) => text(b) === 'Save current view…')!.click();
    await settle();
    const dialog = el.querySelector<HTMLDialogElement>('#ac-save')!;
    expect(dialog.open).toBe(true);
    expect(text(dialog.querySelector('.gw-dialog__description'))).toBe('Saved views keep the filters, not the time range. This one shows env:prod.');
    button('Save view', dialog).click();
    await settle();
    expect(text(dialog.querySelector('.gw-field__error'))).toBe('Name the view.');
    expect(of('save-views')).toEqual([]);
    const name = dialog.querySelector<HTMLInputElement>('#ac-save-name')!;
    name.value = 'Prod only';
    name.dispatchEvent(new Event('input'));
    button('Save view', dialog).click();
    await settle();
    const saved = of('save-views')[0] as { views: SavedView[]; version: number };
    expect(saved.version).toBe(3);
    expect(saved.views.at(-1)).toEqual({ name: 'Prod only', q: 'env:prod', view: 'events' });
    expect(dialog.open).toBe(false);
    expect(toasts().at(-1)).toMatchObject({ title: 'View saved', body: 'Prod only is under Saved views.' });
  });

  it('a query nothing matches offers Clear filters; the address follows', async () => {
    const { el, settle, router, button } = await open('/activity?q=connection:aws-stage');
    expect(text(el.querySelector('#ac-empty-title'))).toBe('No calls match these filters');
    expect(text(el.querySelector('#ac-empty-text'))).toBe('No call in the last 24 hours matches connection:aws-stage. Remove a filter or widen the range.');
    expect(text(el.querySelector('.gw-log-explorer [role="status"]'))).toBe('0 of 10 calls');
    button('Clear filters', el.querySelector('#ac-empty')!).click();
    await settle();
    expect(router.url).toBe('/activity');
    expect(el.querySelectorAll('#ac-events tbody tr')).toHaveLength(10);
  });

  it('a query the core can’t read says what it said', async () => {
    core.histogram = async (q) => (q ? Promise.reject(problem('unprocessable', 422, "colour isn't a filter.", '/q')) : kitHistogram());
    core.events = async (q) => (q ? Promise.reject(problem('unprocessable', 422, "colour isn't a filter.", '/q')) : { events: kitCalls(), nextCursor: null });
    const { el } = await open('/activity?q=colour:red');
    const alert = el.querySelector('#ac-panel-events .gw-alert--warning')!;
    expect(text(alert)).toBe("Unknown filter colour isn't a filter. Clear filters");
    expect(el.querySelector('#ac-events')).toBeNull();
    expect(text(el.querySelector('.gw-log-explorer [role="status"]'))).toBe('0 of 10 calls');
  });

  it('an hour without calls goes back to the range', async () => {
    const { el, settle, button } = await open();
    const empty = el.querySelectorAll<HTMLButtonElement>('.gw-chart__bar')[3];
    core.events = async () => ({ events: [], nextCursor: null });
    empty.click();
    await settle();
    expect(text(el.querySelector('#ac-empty-text'))).toMatch(/^No agent called the gateway between \d\d:\d\d and \d\d:\d\d\. Pick another hour or go back to the last 24 hours\.$/);
    core.events = async () => ({ events: kitCalls(), nextCursor: null });
    button('Show the last 24 hours').click();
    await settle();
    expect(el.querySelectorAll('#ac-events tbody tr')).toHaveLength(10);
  });

  it('the first run invites a try in Playground', async () => {
    core.histogram = async () => histogram();
    core.events = async () => ({ events: [], nextCursor: null });
    core.ever = false;
    const { el } = await open();
    expect(text(el.querySelector('#ac-first-run h3'))).toBe('No calls yet');
    expect(el.querySelector('#ac-first-run .gw-btn--primary')!.getAttribute('href')).toBe('/playground');
    expect(el.querySelector('.gw-chart')).toBeNull();
  });

  it('a failed load says what happened, with Try again and the details', async () => {
    let fail = true;
    core.events = async () => (fail ? Promise.reject(problem('server', 500, 'The audit store did not answer.')) : { events: kitCalls(), nextCursor: null });
    const { el, settle, button } = await open();
    const alert = el.querySelector('#ac-panel-events .gw-alert--danger')!;
    expect(text(alert.querySelector('.gw-alert__title'))).toBe("The calls didn't load");
    expect(text(alert.querySelector('.gw-alert__body'))).toBe('The audit store did not answer.');
    expect(alert.getAttribute('role')).toBe('alert');
    button('Details', alert).click();
    await settle();
    expect(el.querySelector<HTMLElement>('#ac-error-details')!.hidden).toBe(false);
    fail = false;
    button('Try again', alert).click();
    await settle();
    expect(el.querySelector('#ac-panel-events .gw-alert--danger')).toBeNull();
    expect(el.querySelectorAll('#ac-events tbody tr')).toHaveLength(10);
  });

  it('?view=traces shows the agents’ tasks; Show N calls filters the page by the task', async () => {
    const { el, settle, router } = await open('/activity?view=traces');
    expect(el.querySelector<HTMLElement>('#ac-panel-traces')!.hidden).toBe(false);
    expect(el.querySelector<HTMLElement>('#ac-panel-events')!.hidden).toBe(true);
    const cards = Array.from(el.querySelectorAll<HTMLElement>('#ac-panel-traces [data-trace]'));
    expect(cards.map((c) => text(c.querySelector('h3')))).toEqual(['Cancel duplicate order 48213', 'Fix failing order sync']);
    expect(text(cards[0].querySelector('.gw-status'))).toBe('In progress, waiting for you');
    expect(text(el.querySelector('#ac-traces-info'))).toBe('All 2 tasks in the last 24 hours');
    expect(of('trace')).toEqual([WAITING_TASK.traceId, ENDED_TASK.traceId]);
    Array.from(cards[0].querySelectorAll('a')).find((a) => text(a).startsWith('Show 5 calls'))!.click();
    await settle();
    expect(router.url).toBe('/activity?q=trace:c7e19d');
    expect(el.querySelector<HTMLElement>('#ac-panel-events')!.hidden).toBe(false);
    expect(of('events').at(-1)).toMatchObject({ q: 'trace:c7e19d' });
  });

  it('?view=changes shows the day’s setup changes, older ones on request; tabs keep the address', async () => {
    const { el, settle, router, button } = await open('/activity?view=changes');
    expect(el.querySelector<HTMLElement>('#ac-panel-changes')!.hidden).toBe(false);
    expect(el.querySelector<HTMLElement>('#ac-panel-calls')!.hidden).toBe(true);
    const rows = () => Array.from(el.querySelectorAll<HTMLElement>('#ac-panel-changes tbody tr'));
    expect(rows().map((r) => Array.from(r.children).map(text))).toEqual([
      ['rel 7200s', 'Tunnel Cloudflare', 'On to Off', 'You', 'No reason given'],
      ['rel 14400s', 'http_get on billing-api, endpoint dev', 'Read only to Off', 'You', 'Agents only need http_request there, and it asks first.'],
      ['rel 18000s', 'ssh_exec on dev-vm, endpoint dev', 'Approval to Off', 'You', 'Runbooks cover what agents need on dev-vm.'],
      ['rel 21600s', 'Tunnel Cloudflare', 'Off to On', 'You', 'Try stage-readonly from claude.ai.'],
    ]);
    expect(rows()[0].querySelector('a')).toBeNull();
    expect(rows()[1].querySelector('a')!.getAttribute('href')).toBe('/endpoints/dev?tab=tools');
    expect(rows()[1].querySelector('.gw-mode--read')).not.toBeNull();
    expect(text(el.querySelector('#ac-panel-changes .gw-paginator__info'))).toBe('All 4 changes in the last 24 hours');
    button('Load older changes').click();
    await settle();
    expect(rows()).toHaveLength(5);
    expect(text(el.querySelector('#ac-panel-changes .gw-paginator__info'))).toBe('5 changes, newest first');

    el.querySelector<HTMLButtonElement>('#ac-tab-calls')!.click();
    await settle();
    expect(router.url).toBe('/activity');
    el.querySelector<HTMLButtonElement>('#ac-tab-traces')!.click();
    await settle();
    expect(router.url).toBe('/activity?view=traces');
  });

  it('a waiting call decided elsewhere changes in place', async () => {
    const { rows, settle } = await open();
    events.emit('audit.call', { ...kitCalls()[1], id: 60, phase: 'resolved', decision: 'approved', token: 'codex-laptop', durationMs: null });
    await settle();
    expect(text(rows()[1].children[1])).toBe('Approved');
    expect(rows()[1].querySelector('a.gw-table__row-link')!.getAttribute('href')).toBe('/activity#event-60');
  });
});
