import { TestBed } from '@angular/core/testing';
import { ApiError } from '../../core/api/api-error';
import { ClientsApi } from '../../core/api/clients.api';
import { ConnectionsApi } from '../../core/api/connections.api';
import { Insights, InsightsApi, InsightsQuery } from '../../core/api/insights.api';
import { PoliciesApi } from '../../core/api/policies.api';
import { EventStream } from '../../core/events/event-stream';
import { TimeService } from '../../core/time/time.service';
import { InsightsData } from './insights.data';
import { NOW, WINDOWS, clientCards, insights } from './insights.fixtures';

/** The page's data service against fake admin API services and a fake event stream. */

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
  count(): number {
    return [...this.handlers.values()].reduce((n, s) => n + s.size, 0);
  }
}

function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const HOUR = 3_600_000;
const policy = (minutes: number) => ({ sections: { approvals: { timeoutMinutes: minutes } } });
const row = (name: string, state: string, latencyMs: number | null = null) => ({ name, health: { state, latencyMs, message: null, checkedAt: new Date(NOW).toISOString() } });

let events: FakeEvents;
let calls: string[];
let queries: InsightsQuery[];
let answers: (() => Promise<Insights>)[];
let clientsAnswer: () => Promise<unknown>;
let policiesAnswer: () => Promise<unknown>;
let healthAnswer: () => Promise<unknown>;

function setup(): InsightsData {
  events = new FakeEvents();
  calls = [];
  queries = [];
  answers = [() => Promise.resolve(insights())];
  clientsAnswer = () => Promise.resolve({ clients: clientCards() });
  policiesAnswer = () => Promise.resolve({ dev: policy(5), stage: policy(10), prod: policy(10) });
  healthAnswer = () => Promise.resolve({ items: [row('cache-redis', 'degraded', 1800), row('billing-api', 'unreachable')] });
  const api: Partial<InsightsApi> = {
    get: (query) => {
      calls.push('insights');
      queries.push(query ?? {});
      const next = answers.length > 1 ? answers.shift()! : answers[0];
      return next();
    },
  };
  TestBed.configureTestingModule({
    providers: [
      InsightsData,
      { provide: InsightsApi, useValue: api },
      {
        provide: ClientsApi,
        useValue: {
          list: () => {
            calls.push('clients');
            return clientsAnswer();
          },
        },
      },
      {
        provide: PoliciesApi,
        useValue: {
          all: () => {
            calls.push('policies');
            return policiesAnswer();
          },
        },
      },
      {
        provide: ConnectionsApi,
        useValue: {
          list: () => {
            calls.push('connections');
            return healthAnswer();
          },
        },
      },
      { provide: EventStream, useValue: events },
      { provide: TimeService, useValue: { now: () => NOW } },
    ],
  });
  return TestBed.inject(InsightsData);
}

const tick = () => new Promise((r) => setTimeout(r, 0));
const failure = () => Promise.reject(new ApiError('server', 500, { title: 'The core failed', detail: 'Insights could not be computed.' }));
/** What a core without step K5 answers: 404 without a problem body. */
const missing = () => Promise.reject(new ApiError('not-available', 404, {}));

afterEach(() => vi.useRealTimers());

describe('InsightsData', () => {
  it('reads the range up to now with the query, and what the cards say beside it', async () => {
    const data = setup();
    await data.start({ q: '', key: '24h' });
    expect(queries).toEqual([{ q: null, from: new Date(NOW - 24 * HOUR).toISOString(), to: new Date(NOW).toISOString() }]);
    expect(calls.sort()).toEqual(['clients', 'connections', 'insights', 'policies']);
    expect(data.insights()?.kpis.current.calls).toBe(1284);
    expect(data.loaded()).toEqual({ q: '', key: '24h' });
    expect(data.cards()?.map((c) => c.client)).toEqual(['claude-desktop', 'codex', 'cursor', 'antigravity']);
    expect(data.windows()).toEqual(WINDOWS);
    expect(data.health().get('billing-api')?.state).toBe('unreachable');
    expect([data.error(), data.stale(), data.notAvailable(), data.refreshing()]).toEqual([null, null, false, false]);
  });

  it('a new filter reads once; the same one again does not; clients and policies are read once', async () => {
    const data = setup();
    await data.start({ q: '', key: '24h' });
    calls = [];
    await data.setFilter({ q: 'client:codex', key: '7d' });
    expect(queries[1]).toEqual({ q: 'client:codex', from: new Date(NOW - 7 * 24 * HOUR).toISOString(), to: new Date(NOW).toISOString() });
    expect(calls.sort()).toEqual(['connections', 'insights']);
    expect(data.loaded()).toEqual({ q: 'client:codex', key: '7d' });
    await data.setFilter({ q: 'client:codex', key: '7d' });
    expect(queries).toHaveLength(2);
  });

  it("parts beside Insights that fail are left out, and a failed health read keeps what was known", async () => {
    const data = setup();
    clientsAnswer = failure;
    policiesAnswer = () => Promise.resolve({ dev: { sections: {} } });
    await data.start({ q: '', key: '24h' });
    expect(data.insights()).not.toBeNull();
    expect(data.cards()).toBeNull();
    expect(data.windows()).toBeNull();
    expect(data.error()).toBeNull();

    healthAnswer = failure;
    await data.refresh();
    expect(data.health().get('cache-redis')?.state).toBe('degraded');
  });

  it('a core without Insights says so once, and events stop asking until it is back', async () => {
    vi.useFakeTimers();
    const data = setup();
    answers = [missing];
    await data.start({ q: '', key: '24h' });
    expect(data.notAvailable()).toBe(true);
    expect([data.insights(), data.error()]).toEqual([null, null]);
    // The clients and policies still answered.
    expect(data.cards()).not.toBeNull();

    events.emit('audit.call', {});
    events.emit('approval.requested', {});
    await vi.advanceTimersByTimeAsync(60_000);
    expect(queries).toHaveLength(1);

    // A new filter asks once more.
    await data.setFilter({ q: 'env:prod', key: '24h' });
    expect(queries).toHaveLength(2);
    expect(data.notAvailable()).toBe(true);

    // The core restarted with Insights.
    answers = [() => Promise.resolve(insights())];
    calls = [];
    await data.reconnect();
    expect(data.notAvailable()).toBe(false);
    expect(data.insights()).not.toBeNull();
    expect(calls.sort()).toEqual(['clients', 'connections', 'insights', 'policies']);
  });

  it('a failed first read is an error; a failed refresh of what is shown makes it stale', async () => {
    const data = setup();
    answers = [failure, () => Promise.resolve(insights()), failure];
    await data.start({ q: '', key: '24h' });
    expect(data.insights()).toBeNull();
    expect((data.error() as ApiError).detail).toBe('Insights could not be computed.');

    await data.refresh();
    expect(data.error()).toBeNull();
    expect(data.insights()).not.toBeNull();

    await data.refresh();
    expect(data.insights()).not.toBeNull();
    expect((data.stale() as ApiError).status).toBe(500);
    expect(data.error()).toBeNull();

    // A failed read for another filter isn't "out of date": the numbers on screen aren't for it.
    await data.setFilter({ q: 'client:cursor', key: '24h' });
    expect(data.error()).not.toBeNull();
    expect(data.loaded()).toEqual({ q: '', key: '24h' });

    // The next answer clears both.
    answers = [() => Promise.resolve(insights())];
    await data.refresh();
    expect([data.error(), data.stale()]).toEqual([null, null]);
    expect(data.loaded()).toEqual({ q: 'client:cursor', key: '24h' });
  });

  it("an answer for a filter the page has left is dropped, and the current one is read after it", async () => {
    const data = setup();
    const first = deferred<Insights>();
    const second = deferred<Insights>();
    answers = [() => first.promise, () => second.promise];
    const started = data.start({ q: '', key: '24h' });
    await tick();
    void data.setFilter({ q: 'tool:sql_query', key: '1h' });
    expect(queries).toHaveLength(1); // one read in flight at a time

    first.resolve(insights());
    await started;
    expect(data.insights()).toBeNull();
    await tick();
    expect(queries).toHaveLength(2);
    expect(queries[1].q).toBe('tool:sql_query');

    second.resolve(insights({ unfilteredCalls: 99 }));
    await tick();
    await tick();
    expect(data.insights()?.unfilteredCalls).toBe(99);
    expect(data.loaded()).toEqual({ q: 'tool:sql_query', key: '1h' });
  });

  it('calls refresh within 15 s, approvals within 2 s, all coalesced into one read', async () => {
    vi.useFakeTimers();
    const data = setup();
    await data.start({ q: '', key: '24h' });
    expect(queries).toHaveLength(1);

    events.emit('audit.call', {});
    events.emit('audit.call', {});
    await vi.advanceTimersByTimeAsync(14_999);
    expect(queries).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(queries).toHaveLength(2);

    // A call, then a request: the sooner one stands.
    events.emit('audit.call', {});
    events.emit('approval.requested', {});
    await vi.advanceTimersByTimeAsync(2_000);
    expect(queries).toHaveLength(3);
    await vi.advanceTimersByTimeAsync(20_000);
    expect(queries).toHaveLength(3);

    events.emit('approval.resolved', {});
    await vi.advanceTimersByTimeAsync(2_000);
    expect(queries).toHaveLength(4);
  });

  it('health events change health now at once; a client change re-reads the clients', async () => {
    vi.useFakeTimers();
    const data = setup();
    await data.start({ q: '', key: '24h' });
    events.emit('connection.health', { name: 'billing-api', env: 'stage', state: 'healthy', latencyMs: 40, message: null, checkedAt: new Date(NOW).toISOString() });
    expect(data.health().get('billing-api')).toEqual({ state: 'healthy', latencyMs: 40, message: null, checkedAt: new Date(NOW).toISOString() });
    expect(data.health().get('cache-redis')?.state).toBe('degraded');
    expect(queries).toHaveLength(1);

    calls = [];
    clientsAnswer = () => Promise.resolve({ clients: clientCards().map((c) => (c.client === 'cursor' ? { ...c, status: 'connected' } : c)) });
    events.emit('clients.drift', { client: 'cursor' });
    await vi.advanceTimersByTimeAsync(2_000);
    expect(calls.sort()).toEqual(['clients', 'connections', 'insights', 'policies']);
    expect(data.cards()?.find((c) => c.client === 'cursor')?.status).toBe('connected');
  });

  it('stops reading and listening when the page goes away', async () => {
    vi.useFakeTimers();
    const data = setup();
    await data.start({ q: '', key: '24h' });
    expect(events.count()).toBe(5);
    events.emit('audit.call', {});
    data.stop();
    expect(events.count()).toBe(0);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(queries).toHaveLength(1);
  });
});
