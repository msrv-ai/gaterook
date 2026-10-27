import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { ApiError } from '../../core/api/api-error';
import { ClientsApi } from '../../core/api/clients.api';
import { ConnectionsApi } from '../../core/api/connections.api';
import { Insights, InsightsApi, InsightsQuery } from '../../core/api/insights.api';
import { PoliciesApi } from '../../core/api/policies.api';
import { EventStream } from '../../core/events/event-stream';
import { CoreStore } from '../../core/stores/core.store';
import { TimeService } from '../../core/time/time.service';
import { AriaDisabledGuard } from '../../kit/aria-disabled';
import { provideKitIcons } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { NOW, clientCards, firstRun, insights } from './insights.fixtures';
import { InsightsPage, SKELETON_DELAY_MS } from './insights.page';

/** The page against fake admin API services: what it shows for each answer, and what it asks the core for. */

const now = signal(NOW);
const fakeTime: Partial<TimeService> = {
  now,
  minute: now,
  secondsUntil: (at: string) => (Date.parse(at) - now()) / 1000,
  relative: (at) => (at ? 'a while ago' : ''),
  absolute: (at) => (at ? `abs ${at}` : ''),
  iso: (at) => at ?? '',
  date: () => '',
  clock: () => '',
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

const HOUR = 3_600_000;
const policy = (minutes: number) => ({ sections: { approvals: { timeoutMinutes: minutes } } });
const health = (name: string, state: string, latencyMs: number | null = null) => ({ name, health: { state, latencyMs, message: null, checkedAt: new Date(NOW).toISOString() } });

let queries: InsightsQuery[];
let answer: (q: InsightsQuery) => Promise<Insights>;
let events: FakeEvents;
let core: { running: ReturnType<typeof signal<boolean>>; instanceChanges: ReturnType<typeof signal<number>> };

function setup(first: (q: InsightsQuery) => Promise<Insights> = () => Promise.resolve(insights())): void {
  queries = [];
  answer = first;
  events = new FakeEvents();
  core = { running: signal(true), instanceChanges: signal(0) };
  TestBed.configureTestingModule({
    providers: [
      // "See by rule" links to this page's own route (a section of it).
      provideRouter([{ path: 'insights', children: [] }]),
      provideKitIcons(),
      { provide: TimeService, useValue: fakeTime },
      {
        provide: InsightsApi,
        useValue: {
          get: (q: InsightsQuery = {}) => {
            queries.push(q);
            return answer(q);
          },
        },
      },
      { provide: ClientsApi, useValue: { list: () => Promise.resolve({ clients: clientCards() }) } },
      { provide: PoliciesApi, useValue: { all: () => Promise.resolve({ dev: policy(5), stage: policy(10), prod: policy(10) }) } },
      { provide: ConnectionsApi, useValue: { list: () => Promise.resolve({ items: [health('cache-redis', 'degraded', 1800), health('billing-api', 'unreachable')] }) } },
      { provide: EventStream, useValue: events },
      { provide: CoreStore, useValue: core },
    ],
  });
  TestBed.inject(AriaDisabledGuard).install();
}

afterEach(() => {
  document.body.innerHTML = '';
  TestBed.inject(ToastService).clear();
});

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function render(inputs: { q?: string; range?: string } = {}): Promise<{ fixture: ComponentFixture<InsightsPage>; el: HTMLElement }> {
  const fixture = TestBed.createComponent(InsightsPage);
  if (inputs.q !== undefined) fixture.componentRef.setInput('q', inputs.q);
  if (inputs.range !== undefined) fixture.componentRef.setInput('range', inputs.range);
  document.body.appendChild(fixture.nativeElement);
  await settle(fixture);
  return { fixture, el: fixture.nativeElement as HTMLElement };
}

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  for (let i = 0; i < 3; i++) {
    await fixture.whenStable();
    await sleep(0);
  }
  fixture.detectChanges();
  await fixture.whenStable();
}

const text = (el: Element | null | undefined) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
const byText = <T extends HTMLElement>(root: ParentNode, selector: string, name: string) =>
  Array.from(root.querySelectorAll<T>(selector)).find((b) => text(b) === name || /^[ :,]/.test(text(b).slice(name.length)) && text(b).startsWith(name)) ?? null;
/** A link's target as the router writes it, decoded: path, query and fragment. */
const target = (a: Element | null) => {
  const u = new URL(a?.getAttribute('href') ?? '', 'http://x');
  return { path: u.pathname, query: Object.fromEntries(u.searchParams), hash: u.hash };
};
const card = (el: HTMLElement, title: string) => byText<HTMLElement>(el, '.gw-card__title', title)!.closest('.gw-card') as HTMLElement;

describe('Insights page', () => {
  it('shows nothing for a moment, then the skeleton until the core answers', async () => {
    const later = deferred<Insights>();
    setup(() => later.promise);
    const { fixture, el } = await render();
    expect(text(el.querySelector('h1'))).toBe('Insights');
    expect(el.querySelector('.gw-skeleton')).toBeNull();
    // The filter bar is there at once, without a count for numbers still on their way.
    expect(el.querySelector('[gwLogExplorer]')).not.toBeNull();
    expect(text(el.querySelector('.gw-log-explorer [role="status"]'))).toBe('');

    await sleep(SKELETON_DELAY_MS + 50);
    await settle(fixture);
    expect(el.querySelectorAll('.gw-skeleton').length).toBeGreaterThan(5);
    expect(byText(el, 'p[role="status"]', 'Loading insights')).not.toBeNull();

    later.resolve(insights());
    await settle(fixture);
    expect(el.querySelector('.gw-skeleton')).toBeNull();
    expect(el.querySelectorAll('.gw-stat')).toHaveLength(6);
  });

  it("reads the last 24 hours and renders the kit's world", async () => {
    setup();
    const { el } = await render();
    expect(queries).toEqual([{ q: null, from: new Date(NOW - 24 * HOUR).toISOString(), to: new Date(NOW).toISOString() }]);
    expect(text(el.querySelector('.gw-log-explorer [role="status"]'))).toBe('1,284 calls · compared with the previous 24 hours');

    const stats = Array.from(el.querySelectorAll('.gw-stat'));
    expect(stats.map((s) => [text(s.querySelector('.gw-stat__label')), text(s.querySelector('.gw-stat__value'))])).toEqual([
      ['Calls', '1,284'],
      ['Approved by you', '46'],
      ['Denied by policy', '17'],
      ['Errors', '8'],
      ['Gateway overhead p95', '31 ms'],
      ['Agent tasks', '61'],
    ]);
    expect(text(stats[0].querySelector('.gw-stat__delta'))).toBe('+12% vs previous 24 hours');
    expect(stats[2].querySelector('.gw-stat__delta')!.getAttribute('data-sentiment')).toBe('bad');
    expect(stats[3].querySelector('.gw-stat__delta')!.className).toBe('gw-stat__delta gw-stat__delta--down');
    expect(stats[0].querySelector('.gw-sparkline')!.getAttribute('aria-label')).toBe('Calls per hour: quiet overnight, peak of 166 at 11:00');
    expect(text(stats[0].querySelector('.gw-stat__meta'))).toBe('Plus 3 waiting for approval · Review 3 waiting requests');
    expect(target(stats[0].querySelector('.gw-stat__meta a')).path).toBe('/approvals');
    const meter = stats[4].querySelector('[role="meter"]')!;
    expect([meter.getAttribute('aria-valuenow'), meter.getAttribute('aria-valuemax'), meter.getAttribute('data-level')]).toEqual(['31', '50', 'ok']);
    expect(text(meter)).toBe('Target ≤ 50 ms · p50 9 ms 31 / 50 ms');
    expect(target(byText(stats[5], 'a', 'View traces'))).toEqual({ path: '/activity', query: { range: '24h', view: 'traces' }, hash: '' });

    const unusual = el.querySelector('[aria-label="Unusual activity, 3 items"]')!;
    expect(Array.from(unusual.querySelectorAll('.gw-alert__title')).map(text)).toEqual([
      'An agent tried to change prod',
      'sql_query on prod-replica environment prod is 4× its 7-day average',
      'cache-redis answers 45× slower than usual',
    ]);
    expect(unusual.querySelector('li')!.className).toBe('gw-alert gw-alert--compact gw-alert--danger');
    expect(target(byText(unusual, 'a', 'Open the task'))).toEqual({ path: '/activity', query: { q: 'trace:b81e44', range: '24h', view: 'traces' }, hash: '' });

    expect(Array.from(el.querySelectorAll('.gw-section__title')).map(text)).toEqual(['Unusual activity', 'Volume', 'Decisions', 'Latency and errors']);
    const calls = card(el, 'Calls over time');
    expect(text(calls.querySelector('.gw-card__description'))).toBe('Traffic peaked at 166 calls at 11:00, and most denials came between 09:00 and 11:00.');
    expect(calls.querySelectorAll('.gw-chart__bar')).toHaveLength(24);
    expect(Array.from(calls.querySelectorAll('.gw-chart__axis .gw-chart__label')).map(text)).toEqual(['15:00', '21:00', '03:00', '09:00', 'Now']);
    expect(calls.querySelectorAll('tbody tr')).toHaveLength(24);
    expect(target(byText(calls, 'a', 'View in Activity'))).toEqual({ path: '/activity', query: { range: '24h' }, hash: '' });

    const tools = card(el, 'Top tools');
    expect(text(tools.querySelector('.gw-card__description'))).toBe('sql_query and logs_search make up two thirds of all calls.');
    expect(tools.querySelectorAll('.gw-card__description code.gw-code')).toHaveLength(2);
    expect(target(tools.querySelector('.gw-card__footer a')).query).toEqual({ q: 'tool:sql_query tool:logs_search', range: '24h' });

    const clients = card(el, 'By client');
    // Each name follows the client's mark, hidden from screen readers as in the kit.
    expect(Array.from(clients.querySelectorAll('.gw-chart .gw-chart__label')).map(text)).toEqual(['Cx Codex', 'CD Claude Desktop', 'ai claude.ai']);
    expect(Array.from(clients.querySelectorAll('.gw-chart .gw-client-mark')).map((m) => [m.className, m.getAttribute('aria-hidden')])).toEqual([
      ['gw-client-mark gw-client-mark--codex gw-client-mark--sm', 'true'],
      ['gw-client-mark gw-client-mark--claude gw-client-mark--sm', 'true'],
      ['gw-client-mark gw-client-mark--claudeai gw-client-mark--sm', 'true'],
    ]);
    expect(text(clients.querySelector('.gw-empty__title'))).toBe('No calls from Cursor or Antigravity');
    expect(target(byText(clients, 'a', 'Connect a client')).path).toBe('/clients');
    expect(Array.from(clients.querySelectorAll('.gw-card__footer a')).map((a) => target(a).query['q'])).toEqual(['client:codex', 'client:claude-desktop', 'client:claude-ai']);

    const approvals = card(el, 'Approvals');
    expect(text(approvals.querySelector('.gw-card__description'))).toBe(
      'You approved 4 of 5 requests, most within a minute; 3 expired before you answered (5 min on dev, 10 min on stage and prod).',
    );
    expect(Array.from(approvals.querySelectorAll('.gw-kv__row')).map((r) => [text(r.querySelector('dt')), text(r.querySelector('dd'))])).toEqual([
      ['Decided within 1 min', '38 of 55'],
      ['p90 wait', '1 min 40 s'],
    ]);
    const slow = approvals.querySelector('tbody tr a')!;
    expect(text(slow)).toBe('k8s_rollout_restart on dev-cluster at 09:47, open call');
    expect(target(slow)).toEqual({ path: '/activity', query: {}, hash: '#event-1102' });
    expect(text(byText(approvals, '.gw-card__footer a', 'View 58 requests'))).toBe('View 58 requests in Activity');

    const denied = card(el, 'Denied by rule');
    expect(text(denied.querySelector('.gw-card__description'))).toBe('Most denials were calls to tools that are off; 4 were DDL on prod.');
    const rows = Array.from(denied.querySelectorAll('tbody tr'));
    expect(rows.map((r) => text(r.querySelector('th a')))).toEqual([
      'mode: off: view 6 denied calls in Activity',
      'prod.sql.block_ddl: view 4 denied calls in Activity',
      'sql.read_only_tool: view 3 denied calls in Activity',
      'http.block_private_ranges: view 2 denied calls in Activity',
      'k8s.namespace_allowlist: view 2 denied calls in Activity',
    ]);
    expect(text(rows[1].querySelector('.gw-table__cell--truncate'))).toBe('DROP TABLE on prod-replica environment prod');
    expect(rows[1].querySelector('.gw-table__cell--truncate')!.getAttribute('title')).toBe('DROP TABLE on prod-replica');

    const errors = card(el, 'Errors by connection');
    expect(text(errors.querySelector('.gw-card__description'))).toBe("All 8 errors come from two connections: cache-redis times out and billing-api can't be reached.");
    expect(Array.from(errors.querySelectorAll('tbody tr')).map((r) => text(r.querySelector('.gw-status')))).toEqual(['Slow · 1.8 s', 'Unreachable']);
    expect(target(errors.querySelector('tbody a')).query).toEqual({ q: 'decision:error connection:cache-redis', range: '24h' });

    const latency = card(el, 'Latency by connection');
    expect(latency.querySelectorAll('.gw-chart__value')).toHaveLength(10);
    expect(text(latency.querySelector('.gw-text-caption'))).toBe('p95 of each part, axis up to 2.0 s. Waiting for approval is not counted.');
    expect(target(byText(el, '.gw-page-header a', 'Open in Activity'))).toEqual({ path: '/activity', query: { range: '24h' }, hash: '' });
  });

  it('opens a filtered view from the URL, and writes the filter back as it changes', async () => {
    setup((q) => Promise.resolve(insights(q.q ? { unfilteredCalls: 1284, kpis: { ...insights().kpis, current: { ...insights().kpis.current, calls: 772 } } } : {})));
    const { fixture, el } = await render({ q: 'client:codex', range: '7d' });
    expect(queries[0]).toEqual({ q: 'client:codex', from: new Date(NOW - 7 * 24 * HOUR).toISOString(), to: new Date(NOW).toISOString() });
    expect(text(el.querySelector('.gw-log-explorer [role="status"]'))).toBe('772 of 1,284 calls · compared with the previous 7 days');
    expect(target(byText(el, '.gw-page-header a', 'Open in Activity')).query).toEqual({ q: 'client:codex', range: '7d' });
    // Links behind numbers keep the page's query.
    expect(target(byText(el, '.gw-stat a', 'View traces')).query).toEqual({ q: 'client:codex', range: '7d', view: 'traces' });

    const input = el.querySelector<HTMLInputElement>('input[type="search"]')!;
    input.value = 'env:prod';
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    await settle(fixture);
    expect(queries.at(-1)!.q).toBe('client:codex env:prod');
    const router = TestBed.inject(Router);
    expect(router.parseUrl(router.url).queryParams).toEqual({ q: 'client:codex env:prod', range: '7d' });

    el.querySelector<HTMLButtonElement>('[aria-haspopup="menu"][title]')!.click();
    el.querySelector<HTMLButtonElement>('[role="menuitemradio"][data-value="24h"]')!.click();
    await settle(fixture);
    expect(queries.at(-1)!.from).toBe(new Date(NOW - 24 * HOUR).toISOString());
    expect(router.parseUrl(router.url).queryParams).toEqual({ q: 'client:codex env:prod' });
  });

  it('says Insights are not available on a core without them, with the way to Activity', async () => {
    setup(() => Promise.reject(new ApiError('not-available', 404, {})));
    const { el } = await render();
    expect(text(el.querySelector('.gw-empty__title'))).toBe("Insights aren't available yet");
    expect(target(byText(el, '.gw-empty a', 'Open Activity')).path).toBe('/activity');
    expect(el.querySelector('[gwLogExplorer]')).toBeNull();
    expect(el.querySelector('.gw-alert[role="alert"]')).toBeNull();
    expect(el.querySelector('.gw-skeleton')).toBeNull();
  });

  it('a restarted core is asked again', async () => {
    let missing = true;
    setup(() => (missing ? Promise.reject(new ApiError('not-available', 404, {})) : Promise.resolve(insights())));
    const { fixture, el } = await render();
    expect(text(el.querySelector('.gw-empty__title'))).toBe("Insights aren't available yet");
    missing = false;
    core.instanceChanges.set(1);
    await settle(fixture);
    expect(queries).toHaveLength(2);
    expect(el.querySelectorAll('.gw-stat')).toHaveLength(6);
  });

  it('says what failed when nothing loaded, with the details, and Try again loads it', async () => {
    let fail = true;
    setup(() => (fail ? Promise.reject(new ApiError('server', 500, { title: 'The core failed', detail: 'Insights could not be computed.' })) : Promise.resolve(insights())));
    const { fixture, el } = await render();
    const alert = el.querySelector('.gw-alert--danger[role="alert"]')!;
    expect(text(alert.querySelector('.gw-alert__title'))).toBe("Insights didn't load");
    expect(text(alert.querySelector('.gw-alert__body'))).toBe('Insights could not be computed.');
    const details = el.querySelector<HTMLElement>('#insights-error-details')!;
    expect(details.hidden).toBe(true);
    byText<HTMLButtonElement>(alert, 'button', 'Details')!.click();
    await settle(fixture);
    expect(details.hidden).toBe(false);
    expect(text(details.querySelector('pre'))).toContain('GET /api/insights → 500 The core failed: Insights could not be computed.');

    fail = false;
    byText<HTMLButtonElement>(alert, 'button', 'Try again')!.click();
    await settle(fixture);
    expect(el.querySelector('.gw-alert[role="alert"]')).toBeNull();
    expect(el.querySelectorAll('.gw-stat')).toHaveLength(6);
  });

  it('keeps the numbers when a refresh fails, and says they may be out of date', async () => {
    let fail = false;
    setup(() => (fail ? Promise.reject(new ApiError('network', 0, {})) : Promise.resolve(insights())));
    const { fixture, el } = await render();
    fail = true;
    events.emit('approval.requested', {});
    await sleep(2_050);
    await settle(fixture);
    expect(queries).toHaveLength(2);
    const warning = el.querySelector('.gw-alert--warning[role="status"]')!;
    expect(text(warning.querySelector('.gw-alert__title'))).toBe('These numbers may be out of date.');
    expect(el.querySelectorAll('.gw-stat')).toHaveLength(6);

    fail = false;
    byText<HTMLButtonElement>(warning, 'button', 'Try again')!.click();
    await settle(fixture);
    expect(el.querySelector('.gw-alert--warning[role="status"]')).toBeNull();
  });

  it('first run: the kit empty state instead of the numbers', async () => {
    setup(() => Promise.resolve(firstRun()));
    const { el } = await render();
    const first = el.querySelector('#in-first-run')!;
    expect(text(first.querySelector('.gw-empty__title'))).toBe('Charts appear after the first calls');
    expect(target(byText(first, 'a', 'Try a tool in Playground')).path).toBe('/playground');
    expect(el.querySelector('.gw-stat')).toBeNull();
    expect(text(el.querySelector('.gw-log-explorer [role="status"]'))).toBe('0 calls');
  });

  it('no calls in the range: one way out, a longer range or no filters', async () => {
    const empty = () => ({ ...firstRun(), firstCallAt: '2026-09-25T08:40:00Z', unfilteredCalls: 1284 });
    setup(() => Promise.resolve(empty()));
    const { fixture, el } = await render({ range: '1h' });
    expect(text(el.querySelector('.gw-empty__title'))).toBe('No calls in the last hour');
    byText<HTMLButtonElement>(el, '.gw-empty button', 'Show the last 24 hours')!.click();
    await settle(fixture);
    expect(queries.at(-1)!.from).toBe(new Date(NOW - 24 * HOUR).toISOString());
    expect(text(el.querySelector('.gw-empty__title'))).toBe('No calls in the last 24 hours');
    expect(text(byText(el, '.gw-empty button', 'Show the last 7 days'))).toBe('Show the last 7 days');
  });

  it('no calls match the filters: Clear filters reads everything again', async () => {
    setup((q) => Promise.resolve(q.q ? { ...firstRun(), firstCallAt: '2026-09-25T08:40:00Z', unfilteredCalls: 1284 } : insights()));
    const { fixture, el } = await render({ q: 'client:cursor' });
    expect(text(el.querySelector('.gw-empty__title'))).toBe('No calls match these filters');
    expect(text(el.querySelector('.gw-empty__text'))).toBe('Nothing in the last 24 hours matches client:cursor. Remove a filter or widen the time range.');
    byText<HTMLButtonElement>(el, '.gw-empty button', 'Clear filters')!.click();
    await settle(fixture);
    expect(queries.at(-1)!.q).toBeNull();
    expect(el.querySelectorAll('.gw-stat')).toHaveLength(6);
  });

  it('See by rule moves to the Denied by rule card and focuses its heading', async () => {
    setup();
    const { fixture, el } = await render();
    const link = byText<HTMLAnchorElement>(el, '.gw-stat a', 'See by rule')!;
    expect(target(link)).toEqual({ path: '/insights', query: {}, hash: '#in-denied-title' });
    link.click();
    await settle(fixture);
    expect(document.activeElement?.id).toBe('in-denied-title');
    byText<HTMLAnchorElement>(el, '.gw-stat a', 'See by connection')!.click();
    await settle(fixture);
    expect(document.activeElement?.id).toBe('in-errors-title');
  });
});
