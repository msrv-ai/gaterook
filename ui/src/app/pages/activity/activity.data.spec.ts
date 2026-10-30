import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivityApi, ActivityEvent, EventsPage, Facets, Histogram, SavedView, SetupChange, TraceDetail, TraceRow } from '../../core/api/activity.api';
import { ApiError } from '../../core/api/api-error';
import { ApprovalsApi } from '../../core/api/approvals.api';
import { ConnectionsApi } from '../../core/api/connections.api';
import { TokensApi } from '../../core/api/endpoints.api';
import { AuditExportApi, SettingsApi } from '../../core/api/settings.api';
import { EventStream } from '../../core/events/event-stream';
import { TimeService } from '../../core/time/time.service';
import { ActivityData, LIVE_THROTTLE_MS, MAX_PAGES, PAGE_SIZE } from './activity.data';
import { ENDED_TASK, FACETS, HOUR, NOW, OLD_TASK, WAITING_TASK, ago, call, change, devTokens, histogram, kitCalls, kitChanges, kitHistogram, traceDetail } from './activity.fixtures';

/** The Activity page's data against fake admin API services: what it asks the core, and what it makes of the answers. */

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

const problem = (kind: ConstructorParameters<typeof ApiError>[0], status: number, detail = 'Nope.', field: string | null = null) =>
  new ApiError(kind, status, { title: 'Refused', detail, field });

type Req = Record<string, unknown>;
let requests: { path: string; req: Req }[];
let events: FakeEvents;
let answers: {
  histogram: (req: Req) => Promise<Histogram>;
  facets: (req: Req) => Promise<Facets>;
  events: (req: Req) => Promise<EventsPage>;
  traces: (q: string | null | undefined, cursor: string | null | undefined) => Promise<{ traces: TraceRow[]; nextCursor: string | null }>;
  trace: (id: string) => Promise<TraceDetail>;
  changes: (cursor: string | null | undefined) => Promise<{ changes: SetupChange[]; nextCursor: string | null }>;
  views: () => Promise<{ views: SavedView[]; version: number }>;
  saveViews: (views: SavedView[], version: number) => Promise<{ views: SavedView[]; version: number }>;
  destinations: () => Promise<unknown[]>;
};

function setup(): ActivityData {
  requests = [];
  events = new FakeEvents();
  answers = {
    histogram: (req) => Promise.resolve(req['q'] ? histogram([{ denied: 1 }]) : kitHistogram()),
    facets: () => Promise.resolve(FACETS),
    events: (req) => Promise.resolve({ events: req['limit'] === 1 ? kitCalls().slice(0, 1) : kitCalls(), nextCursor: 'c1' }),
    traces: () => Promise.resolve({ traces: [WAITING_TASK, ENDED_TASK], nextCursor: null }),
    trace: (id) => Promise.resolve(traceDetail(id === WAITING_TASK.traceId ? WAITING_TASK : ENDED_TASK)),
    changes: () => Promise.resolve({ changes: kitChanges(), nextCursor: null }),
    views: () => Promise.resolve({ views: [], version: 0 }),
    saveViews: (views, version) => Promise.resolve({ views, version: version + 1 }),
    destinations: () => Promise.reject(problem('not-available', 404)),
  };
  const log = (path: string, req: Req) => requests.push({ path, req });
  const api: Partial<ActivityApi> = {
    histogram: (req = {}) => (log('histogram', req as Req), answers.histogram(req as Req)),
    facets: (req = {}) => (log('facets', req as Req), answers.facets(req as Req)),
    events: (req = {}) => (log('events', req as Req), answers.events(req as Req)),
    event: (id) => (log('event', { id }), Promise.resolve({ event: kitCalls()[0], rows: [] })),
    traces: (q, cursor, _limit, range) => (log('traces', { q, cursor }), log('traces-range', { ...range }), answers.traces(q, cursor)),
    trace: (id) => (log('trace', { id }), answers.trace(id)),
    changes: (q, cursor) => (log('changes', { q, cursor }), answers.changes(cursor)),
    export: (req) => (log('export', req as unknown as Req), Promise.resolve({ path: '/tmp/a.csv', rows: 10, bytes: 100, format: req.format })),
    views: () => (log('views', {}), answers.views()),
    saveViews: (views, version) => (log('save-views', { views, version }), answers.saveViews(views, version)),
  };
  const tokens: Partial<TokensApi> = {
    list: (slug) => (log('tokens', { slug }), Promise.resolve(devTokens())),
    revoke: (id) => (log('revoke', { id }), Promise.resolve({ id, rejectedApprovals: 0 })),
    revokeGrant: (id) => (log('revoke-grant', { id }), Promise.resolve({ id, rejectedApprovals: 2 })),
  };
  const now = signal(NOW);
  TestBed.configureTestingModule({
    providers: [
      ActivityData,
      { provide: TimeService, useValue: { now, minute: now } },
      { provide: ActivityApi, useValue: api },
      { provide: TokensApi, useValue: tokens },
      { provide: ApprovalsApi, useValue: { get: (id: string) => (id === 'ap_sql' ? Promise.resolve({ id }) : Promise.reject(problem('not-found', 404))) } },
      { provide: ConnectionsApi, useValue: { list: () => Promise.resolve({ items: [{ name: 'dev-db', env: 'dev' }, { name: 'prod-replica', env: 'prod' }] }) } },
      { provide: SettingsApi, useValue: { get: () => Promise.resolve({ values: { retentionYears: 2 } }) } },
      { provide: AuditExportApi, useValue: { list: () => answers.destinations() } },
      { provide: EventStream, useValue: events },
    ],
  });
  return TestBed.inject(ActivityData);
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
const of = (path: string) => requests.filter((r) => r.path === path).map((r) => r.req);

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => (resolve = r));
  return { promise, resolve };
}

describe('ActivityData', () => {
  it('reads the range: histogram, facets and the newest page; counts come from the histogram', async () => {
    const d = setup();
    await d.start();
    await sleep(0);
    expect(of('histogram')).toEqual([{ q: '', from: ago(24 * HOUR), to: null }]);
    expect(of('facets')).toEqual([{ q: '', from: ago(24 * HOUR), to: null }]);
    expect(of('events')).toEqual([{ q: '', from: ago(24 * HOUR), to: null, limit: PAGE_SIZE }]);
    expect(d.rows()!.map((e) => e.id)).toEqual(kitCalls().map((e) => e.id));
    expect([d.matched(), d.total(), d.waiting(), d.hasOlder(), d.firstRun(), d.loaded()]).toEqual([10, 10, 2, true, false, true]);
    // The side parts: retention, the connections' environments, the starting views, tasks and changes.
    expect(d.retentionYears()).toBe(2);
    expect(d.connectionEnvs()).toEqual({ 'dev-db': 'dev', 'prod-replica': 'prod' });
    expect(d.views().map((v) => v.name)).toEqual(['Without log reads', 'Denied on prod', 'Waiting for approval', 'Slow calls']);
    expect(d.traces().value).toEqual([WAITING_TASK, ENDED_TASK]);
    expect(d.changes().value).toHaveLength(5);
  });

  it('a query counts within itself, the range without it, and each filtered facet without its own field', async () => {
    const d = setup();
    d.query.set('decision:denied env:prod');
    await d.loadCalls();
    expect(of('histogram').map((r) => r['q'] ?? null)).toEqual(['decision:denied env:prod', null]);
    expect(of('facets').map((r) => r['q'])).toEqual(['decision:denied env:prod', 'env:prod', 'decision:denied']);
    expect([d.matched(), d.total(), d.waiting()]).toEqual([1, 10, 2]);
  });

  it('a zoomed hour lists only its calls and keeps the histogram of the range', async () => {
    const d = setup();
    await d.loadCalls();
    const bucket = d.histogram()!.buckets[22];
    requests = [];
    d.zoom.set(bucket.from);
    await d.loadCalls('zoom');
    expect(of('histogram')).toEqual([]);
    expect(of('events')).toEqual([{ q: '', from: bucket.from, to: bucket.to, limit: PAGE_SIZE }]);
    expect([d.matched(), d.total()]).toEqual([1, 10]);
  });

  it('the first run: no call has ever reached the gateway', async () => {
    const d = setup();
    answers.histogram = () => Promise.resolve(histogram());
    answers.events = () => Promise.resolve({ events: [], nextCursor: null });
    await d.loadCalls();
    expect(of('events').at(-1)).toEqual({ limit: 1 });
    expect(d.firstRun()).toBe(true);
    // An empty range of a used gateway is not a first run.
    answers.events = (req) => Promise.resolve({ events: req['limit'] === 1 ? [call(1, 30 * HOUR, 'allowed', 'sql_query')] : [], nextCursor: null });
    await d.loadCalls();
    expect(d.firstRun()).toBe(false);
  });

  it('a query the core cannot read shows its words, nothing for it, and the range still counts', async () => {
    const d = setup();
    await d.loadCalls();
    answers.histogram = (req) => (req['q'] ? Promise.reject(problem('unprocessable', 422, "colour isn't a filter.", '/q')) : Promise.resolve(kitHistogram()));
    answers.events = () => Promise.reject(problem('unprocessable', 422, "colour isn't a filter.", '/q'));
    d.query.set('colour:red');
    await d.loadCalls();
    expect((d.queryError() as ApiError).detail).toBe("colour isn't a filter.");
    expect([d.rows(), d.histogram(), d.facets(), d.error(), d.stale()]).toEqual([null, null, null, null, null]);
    expect(d.total()).toBe(10);
    answers.events = () => Promise.resolve({ events: kitCalls(), nextCursor: null });
    d.query.set('');
    await d.loadCalls();
    expect(d.queryError()).toBeNull();
    expect(d.rows()).toHaveLength(10);
  });

  it('a failed first load is an error; a failed reload keeps the calls and says they may be out of date', async () => {
    const d = setup();
    answers.events = () => Promise.reject(problem('server', 500, 'The audit store did not answer.'));
    await d.loadCalls();
    expect((d.error() as ApiError).detail).toBe('The audit store did not answer.');
    expect(d.loaded()).toBe(true);
    answers.events = () => Promise.resolve({ events: kitCalls(), nextCursor: null });
    await d.loadCalls();
    expect(d.error()).toBeNull();
    answers.events = () => Promise.reject(problem('server', 500));
    await d.loadCalls();
    expect(d.stale()).not.toBeNull();
    expect(d.rows()).toHaveLength(10);
  });

  it('drops an answer to an older question', async () => {
    const d = setup();
    let release!: () => void;
    const slow = new Promise<void>((r) => (release = r));
    answers.events = (req) => (req['q'] === 'env:prod' ? slow.then(() => ({ events: [call(1, 0, 'allowed', 'old')], nextCursor: null })) : Promise.resolve({ events: kitCalls(), nextCursor: null }));
    d.query.set('env:prod');
    const first = d.loadCalls();
    d.query.set('');
    await d.loadCalls();
    release();
    await first;
    expect(d.rows()).toHaveLength(10);
  });

  it('Load older calls appends the next page once', async () => {
    const d = setup();
    await d.loadCalls();
    answers.events = () => Promise.resolve({ events: [kitCalls()[9], call(0, 3 * HOUR, 'allowed', 'sql_query')], nextCursor: null });
    await d.loadOlder();
    expect(of('events').at(-1)).toEqual({ q: '', from: ago(24 * HOUR), to: null, cursor: 'c1', limit: PAGE_SIZE });
    expect(d.rows()!.map((e) => e.id).slice(-2)).toEqual([1, 0]);
    expect(d.hasOlder()).toBe(false);
  });

  it('refresh waits for every journal and the previously opened task details', async () => {
    const d = setup();
    await d.start();
    await d.loadTraceDetail(WAITING_TASK.traceId);
    const pendingChanges = deferred<{ changes: SetupChange[]; nextCursor: string | null }>();
    const pendingDetail = deferred<TraceDetail>();
    answers.changes = () => pendingChanges.promise;
    answers.trace = () => pendingDetail.promise;
    let finished = false;
    const refresh = d.refresh('head').then(() => (finished = true));
    await sleep(0);
    expect(d.refreshing()).toBe(false);
    expect(d.busy()).toBe(true);
    expect(finished).toBe(false);
    pendingChanges.resolve({ changes: [], nextCursor: null });
    await sleep(0);
    expect(d.busy()).toBe(true);
    const updated = traceDetail({ ...WAITING_TASK, status: 'ended', calls: 12 });
    pendingDetail.resolve(updated);
    await refresh;
    expect(d.busy()).toBe(false);
    expect(d.traceDetails().get(WAITING_TASK.traceId)).toEqual(updated);
    expect(d.changes().value).toEqual([]);
  });

  it('head refresh keeps loaded older calls and their cursor, and removes calls outside the moving range', async () => {
    const d = setup();
    await d.start();
    answers.events = () => Promise.resolve({ events: [call(0, 3 * HOUR, 'allowed', 'sql_query'), call(-1, 25 * HOUR, 'allowed', 'sql_query')], nextCursor: 'c2' });
    await d.loadOlder();
    answers.events = () => Promise.resolve({ events: [call(50, 0, 'allowed', 'sql_query'), ...kitCalls()], nextCursor: 'c1' });
    await d.refresh('head');
    expect(d.rows()!.map((e) => e.id)).toEqual([50, ...kitCalls().map((e) => e.id), 0]);
    await d.loadOlder();
    expect(of('events').at(-1)!['cursor']).toBe('c2');

    answers.histogram = () => Promise.resolve(histogram());
    answers.events = () => Promise.resolve({ events: [], nextCursor: null });
    await d.refresh('head');
    expect(d.rows()).toEqual([]);
    expect(d.hasOlder()).toBe(false);
  });

  it('head refresh does not merge calls left over from a failed filter change', async () => {
    const d = setup();
    await d.start();
    d.query.set('env:prod');
    answers.events = () => Promise.reject(problem('server', 500));
    await d.loadCalls();
    expect(d.stale()).not.toBeNull();
    const matching = kitCalls().slice(0, 1);
    answers.events = () => Promise.resolve({ events: matching, nextCursor: 'filtered' });
    await d.refresh('head');
    expect(d.rows()).toEqual(matching);
    await d.loadOlder();
    expect(of('events').at(-1)!['cursor']).toBe('filtered');
  });

  it('only reloads cached details for tasks still in the current list', async () => {
    const d = setup();
    await d.start();
    await d.loadTraceDetail(WAITING_TASK.traceId);
    await d.loadTraceDetail(ENDED_TASK.traceId);
    requests = [];
    answers.traces = () => Promise.resolve({ traces: [ENDED_TASK], nextCursor: null });
    await d.refresh('head');
    expect(of('trace')).toEqual([{ id: ENDED_TASK.traceId }]);
  });

  it('Live waits for an older page to finish and then includes the trailing update', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] });
    vi.setSystemTime(NOW);
    try {
      const d = setup();
      await d.start();
      d.live.set(true);
      const older = deferred<EventsPage>();
      answers.events = () => older.promise;
      const loadingOlder = d.loadOlder();
      events.emit('audit.call', { id: 50, callId: 'ev_50' });
      await vi.advanceTimersByTimeAsync(LIVE_THROTTLE_MS * 2);
      expect(of('events')).toHaveLength(2);
      expect(d.busy()).toBe(true);
      older.resolve({ events: [call(0, 3 * HOUR, 'allowed', 'sql_query')], nextCursor: 'c2' });
      await loadingOlder;
      answers.events = () => Promise.resolve({ events: [call(50, 0, 'allowed', 'sql_query'), ...kitCalls()], nextCursor: 'c1' });
      await vi.advanceTimersByTimeAsync(LIVE_THROTTLE_MS);
      expect(of('events')).toHaveLength(3);
      expect(d.rows()!.map((e) => e.id)).toEqual([50, ...kitCalls().map((e) => e.id), 0]);
      d.stop();
    } finally {
      vi.useRealTimers();
    }
  });

  it('updates a listed call in place; with Live, reloads the newest calls at most once a second', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] });
    vi.setSystemTime(NOW);
    try {
      const d = setup();
      await d.start();
      await vi.advanceTimersByTimeAsync(0);
      const sse = { id: 40, callId: 'ev_17', at: ago(0), phase: 'resolved', decision: 'approved', tool: 'sql_execute', connection: 'dev-db', env: 'dev', client: 'codex', endpoint: 'dev', token: 'codex-laptop', durationMs: null, rule: null, approvalId: 'ap_sql', traceId: null };
      events.emit('audit.call', sse);
      expect(d.rows()![1]).toMatchObject({ id: 40, decision: 'approved' });
      expect(of('events')).toHaveLength(1);

      d.live.set(true);
      answers.events = () => Promise.resolve({ events: [call(50, 0, 'allowed', 'sql_query'), ...kitCalls()], nextCursor: 'c1' });
      events.emit('audit.call', { ...sse, id: 50, callId: 'ev_50', decision: 'allowed' });
      events.emit('audit.call', { ...sse, id: 51, callId: 'ev_51', decision: 'allowed' });
      await vi.advanceTimersByTimeAsync(LIVE_THROTTLE_MS);
      expect(of('events')).toHaveLength(2);
      expect(d.rows()![0].id).toBe(50);
      expect(d.rows()).toHaveLength(11);
      d.stop();
    } finally {
      vi.useRealTimers();
    }
  });

  it('reads tasks page by page until the range starts, and each task’s detail once', async () => {
    const d = setup();
    const pages: Record<string, { traces: TraceRow[]; nextCursor: string | null }> = {
      first: { traces: [WAITING_TASK], nextCursor: 'p2' },
      p2: { traces: [ENDED_TASK], nextCursor: 'p3' },
      p3: { traces: [OLD_TASK], nextCursor: 'p4' },
      p4: { traces: [{ ...OLD_TASK, traceId: 'older' }], nextCursor: null },
    };
    answers.traces = (_q, cursor) => Promise.resolve(pages[cursor ?? 'first']);
    d.query.set('client:codex');
    await d.loadTraces();
    expect(of('traces')).toEqual([
      { q: 'client:codex', cursor: null },
      { q: 'client:codex', cursor: 'p2' },
      { q: 'client:codex', cursor: 'p3' },
    ]);
    expect(d.traces().value!.map((t) => t.title)).toEqual(['Cancel duplicate order 48213', 'Fix failing order sync', 'Rotate the cache']);
    expect(d.tracesComplete()).toBe(true);

    answers.trace = (id) => (id === 'gone' ? Promise.reject(problem('not-found', 404)) : Promise.resolve(traceDetail(WAITING_TASK)));
    expect(await d.loadTraceDetail(WAITING_TASK.traceId)).toEqual(traceDetail(WAITING_TASK));
    expect(await d.loadTraceDetail(WAITING_TASK.traceId)).toEqual(traceDetail(WAITING_TASK));
    expect(await d.loadTraceDetail('gone')).toBeNull();
    expect(d.traceDetails().get('gone')).toBeNull();
    expect(of('trace')).toHaveLength(2);
  });

  it('asks the core for the tasks of the range (step API), the next page too', async () => {
    const d = setup();
    answers.traces = (_q, cursor) => Promise.resolve(cursor ? { traces: [OLD_TASK], nextCursor: null } : { traces: [WAITING_TASK], nextCursor: 'p2' });
    d.range.set({ kind: 'relative', key: '1h' });
    await d.loadTraces();
    const [first] = of('traces-range') as { from?: string; to?: string | null }[];
    expect(Date.parse(first.from!)).toBe(NOW - 3_600_000);
    expect(first.to).toBeNull();
    await d.loadMoreTraces();
    expect(of('traces-range').every((r) => (r as { from?: string }).from === first.from)).toBe(true);
  });

  it('Load older tasks reads one more page past the range', async () => {
    const d = setup();
    // Five pages of tasks of the last hour: the list stops reading, though it may reach further back.
    answers.traces = (_q, cursor) => {
      const n = cursor ? Number(cursor) : 0;
      return Promise.resolve(n < MAX_PAGES ? { traces: [{ ...ENDED_TASK, traceId: `t${n}` }], nextCursor: String(n + 1) } : { traces: [OLD_TASK], nextCursor: null });
    };
    d.range.set({ kind: 'relative', key: '1h' });
    await d.loadTraces();
    expect(of('traces')).toHaveLength(MAX_PAGES);
    expect(d.tracesComplete()).toBe(false);
    await d.loadMoreTraces();
    expect(d.traces().value).toHaveLength(MAX_PAGES + 1);
    expect(d.tracesComplete()).toBe(true);
  });

  it('reads the day’s setup changes, and older ones on request', async () => {
    const d = setup();
    answers.changes = (cursor) =>
      Promise.resolve(cursor ? { changes: [change(-1, 40 * HOUR, 'secret', 'Secret dev-db password')], nextCursor: null } : { changes: kitChanges(), nextCursor: 'older' });
    await d.loadChanges();
    expect(of('changes')).toEqual([{ q: null, cursor: null }]);
    expect(d.changesComplete()).toBe(false);
    await d.loadOlderChanges();
    expect(d.changes().value).toHaveLength(6);
    expect(d.changesComplete()).toBe(true);
  });

  it('head refresh preserves loaded older tasks and setup changes with their pagination cursors', async () => {
    const d = setup();
    answers.traces = (_q, cursor) => {
      const n = cursor ? Number(cursor) : 0;
      return Promise.resolve({ traces: [{ ...ENDED_TASK, traceId: `t${n}` }], nextCursor: String(n + 1) });
    };
    const boundary = change(1, 25 * HOUR, 'secret', 'Secret dev-db password');
    const older = change(0, 40 * HOUR, 'secret', 'Secret dev-db password');
    answers.changes = (cursor) => Promise.resolve(cursor ? { changes: [older], nextCursor: 'oldest' } : { changes: [boundary], nextCursor: 'older' });
    await d.start();
    await d.loadMoreTraces();
    await d.loadOlderChanges();
    await d.refresh('head');
    expect(d.traces().value!.map((t) => t.traceId)).toEqual(['t0', 't1', 't2', 't3', 't4', 't5']);
    expect(d.changes().value).toEqual([boundary, older]);
    await d.loadMoreTraces();
    await d.loadOlderChanges();
    expect(of('traces').at(-1)!['cursor']).toBe('6');
    expect(of('changes').at(-1)!['cursor']).toBe('oldest');
  });

  it('drops stale setup-change refresh and pagination responses', async () => {
    const d = setup();
    await d.start();
    const oldRefresh = deferred<{ changes: SetupChange[]; nextCursor: string | null }>();
    answers.changes = () => oldRefresh.promise;
    const first = d.loadChanges();
    const newest = change(20, 0, 'secret', 'Secret dev-db password');
    answers.changes = () => Promise.resolve({ changes: [newest, change(1, 25 * HOUR, 'secret', 'Secret dev-db password')], nextCursor: 'older' });
    await d.loadChanges();
    const latest = d.changes().value;
    oldRefresh.resolve({ changes: kitChanges(), nextCursor: null });
    await first;
    expect(d.changes().value).toBe(latest);

    const oldPage = deferred<{ changes: SetupChange[]; nextCursor: string | null }>();
    answers.changes = () => oldPage.promise;
    const pagination = d.loadOlderChanges();
    answers.changes = () => Promise.resolve({ changes: [newest], nextCursor: null });
    await d.loadChanges();
    oldPage.resolve({ changes: kitChanges(), nextCursor: 'outdated' });
    await pagination;
    expect(d.changes().value).toEqual([newest]);
    expect(d.changesComplete()).toBe(true);
    expect(d.busy()).toBe(false);
  });

  it('saves a view with the starting points, and on top of a list changed meanwhile', async () => {
    const d = setup();
    await d.start();
    await sleep(0);
    await d.saveView({ name: 'Prod', q: 'env:prod', view: 'events' });
    expect(of('save-views')[0]['version']).toBe(0);
    expect((of('save-views')[0]['views'] as SavedView[]).map((v) => v.name)).toEqual(['Without log reads', 'Denied on prod', 'Waiting for approval', 'Slow calls', 'Prod']);
    expect(d.views().at(-1)!.name).toBe('Prod');

    let tries = 0;
    answers.saveViews = (views, version) => (tries++ === 0 ? Promise.reject(problem('conflict', 409)) : Promise.resolve({ views, version: version + 1 }));
    answers.views = () => Promise.resolve({ views: [{ name: 'Theirs', q: 'env:dev', view: 'events' }], version: 7 });
    await d.saveView({ name: 'Mine', q: 'env:stage', view: 'traces' });
    expect(of('save-views').at(-1)).toEqual({ views: [{ name: 'Theirs', q: 'env:dev', view: 'events' }, { name: 'Mine', q: 'env:stage', view: 'traces' }], version: 7 });
  });

  it('exports what the table lists, to a file or a destination; no destinations before step K5', async () => {
    const d = setup();
    await d.loadCalls();
    d.query.set('env:prod');
    await d.exportToFile('csv', 'save-token-1');
    expect(of('export')[0]).toEqual({ q: 'env:prod', from: ago(24 * HOUR), to: null, target: 'file', format: 'csv', saveToken: 'save-token-1' });
    await d.sendToSiem('dest-1');
    expect(of('export')[1]).toEqual({ q: 'env:prod', from: ago(24 * HOUR), to: null, target: 'siem', format: 'jsonl', destinationId: 'dest-1' });
    await d.loadDestinations();
    expect(d.destinations()).toEqual({ value: null, error: null, unavailable: true });
    answers.destinations = () => Promise.resolve([{ id: 'syslog', name: 'Syslog (TLS)' }]);
    await d.loadDestinations();
    expect(d.destinations().value).toHaveLength(1);
  });

  it('finds and revokes the access behind a call', async () => {
    const d = setup();
    const grant = await d.revokeTargetOf(kitCalls()[0]);
    expect(grant?.kind).toBe('grant');
    expect(of('tokens')).toEqual([{ slug: 'dev' }]);
    expect(await d.revokeTargetOf({ ...kitCalls()[0], tokenLabel: 'old-claude' })).toBeNull();
    expect(await d.revokeTargetOf({ ...kitCalls()[0], tokenLabel: null } as ActivityEvent)).toBeNull();
    expect(await d.revoke(grant!)).toEqual({ id: 'gr_codex-laptop', rejectedApprovals: 2 });
    await d.revoke(devTokens()[1]);
    expect(of('revoke')).toEqual([{ id: 'tok_inspector-debug' }]);
    expect(await d.approval('ap_sql')).toEqual({ id: 'ap_sql' });
    expect(await d.approval('ap_gone')).toBeNull();
  });
});
