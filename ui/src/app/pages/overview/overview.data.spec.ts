import { Injector, runInInjectionContext } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ApiError } from '../../core/api/api-error';
import { ApprovalsApi } from '../../core/api/approvals.api';
import { ClientsApi } from '../../core/api/clients.api';
import { ConnectionsApi } from '../../core/api/connections.api';
import { Overview, OverviewApi } from '../../core/api/overview.api';
import { EventStream } from '../../core/events/event-stream';
import { ROWS, browserSignedIn, clientsBrowserSignedIn, clientsFound, clientsInUse, devDb, firstRun, overview, setup } from './overview.fixtures';
import { OverviewData } from './overview.data';

/** OverviewData: what it loads for each state, how it coalesces refreshes, and what it leaves out when a part fails. */

function deferred<T>() {
  let resolve!: (v: T) => void;
  const promise = new Promise<T>((r) => (resolve = r));
  return { promise, resolve };
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

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

let gets: ReturnType<typeof deferred<Overview>>[];
let calls: string[];
let clientsAnswer: () => Promise<unknown>;
let events: FakeEvents;

function make(): OverviewData {
  gets = [];
  calls = [];
  events = new FakeEvents();
  clientsAnswer = () => Promise.resolve(clientsInUse());
  const overviewApi: Partial<OverviewApi> = {
    get: () => {
      const d = deferred<Overview>();
      gets.push(d);
      return d.promise;
    },
  };
  const connectionsApi: Partial<ConnectionsApi> = {
    list: () => {
      calls.push('connections');
      return Promise.resolve({ items: ROWS, facets: { health: {}, env: {}, type: {} }, total: ROWS.length });
    },
    get: (name) => {
      calls.push(`connection ${name}`);
      return Promise.resolve(devDb());
    },
  };
  TestBed.configureTestingModule({
    providers: [
      { provide: OverviewApi, useValue: overviewApi },
      { provide: ApprovalsApi, useValue: { get: () => Promise.reject(new Error('no detail')) } },
      {
        provide: ClientsApi,
        useValue: {
          list: () => {
            calls.push('clients');
            return clientsAnswer();
          },
        },
      },
      { provide: ConnectionsApi, useValue: connectionsApi },
      { provide: EventStream, useValue: events },
    ],
  });
  return runInInjectionContext(TestBed.inject(Injector), () => new OverviewData());
}

describe('OverviewData', () => {
  it('refreshes setup progress and observed OAuth status without a tool call, then stops on disposal', async () => {
    const data = make();
    clientsAnswer = () => Promise.resolve(clientsFound());
    const first = data.start();
    gets[0].resolve(setup());
    await first;
    expect(data.overview()?.gettingStarted.steps.find((s) => s.id === 'client')?.state).toBe('current');
    expect(data.clients()?.clients.find((c) => c.client === 'cursor')?.status).toBe('detected');

    vi.useFakeTimers();
    try {
      clientsAnswer = () => Promise.resolve(clientsBrowserSignedIn());
      calls = [];
      events.emit('oauth.grant.changed', { id: 'og_cursor', endpoint: 'dev' });
      events.emit('oauth.grant.changed', { id: 'og_cursor', endpoint: 'dev' });
      await vi.advanceTimersByTimeAsync(1_000);
      expect(gets).toHaveLength(2);
      gets[1].resolve(browserSignedIn());
      await vi.advanceTimersByTimeAsync(0);
      expect(data.overview()?.gettingStarted.steps.find((s) => s.id === 'client')?.state).toBe('done');
      expect(data.overview()?.gettingStarted.steps.find((s) => s.id === 'playground')?.state).toBe('todo');
      expect(data.overview()?.stats.calls24h).toBe(0);
      const cursor = data.clients()?.clients.find((c) => c.client === 'cursor');
      expect(cursor?.status).toBe('connected');
      expect(cursor?.links).toEqual([expect.objectContaining({ id: 'og_cursor', method: 'oauth', configPath: null, tokenName: null, lastCallAt: null })]);
      expect(calls.sort()).toEqual(['clients', 'connections']);
      expect(data.first()).toBeNull();
      data.stop();
      expect(events.handlers.get('oauth.grant.changed')?.size).toBe(0);
      events.emit('oauth.grant.changed', { id: 'og_cursor', endpoint: 'dev' });
      await vi.advanceTimersByTimeAsync(1_000);
      expect(gets).toHaveLength(2);
    } finally {
      data.stop();
      vi.useRealTimers();
    }
  });

  it('runs one refresh at a time and one more after it for whatever came meanwhile', async () => {
    const data = make();
    const first = data.refresh();
    void data.refresh();
    void data.refresh();
    expect(gets).toHaveLength(1);
    gets[0].resolve(overview());
    await first;
    await sleep(0);
    expect(gets).toHaveLength(2);
    gets[1].resolve(overview());
    await sleep(0);
    expect(gets).toHaveLength(2);
  });

  it('in use: the AI apps and the connections with their endpoints; no first connection', async () => {
    const data = make();
    const done = data.refresh();
    gets[0].resolve(overview());
    await done;
    expect(calls.sort()).toEqual(['clients', 'connections']);
    expect(data.clients()?.clients).toHaveLength(4);
    expect(data.connections()).toHaveLength(ROWS.length);
    expect(data.first()).toBeNull();
  });

  it('setup: the first connection the core names, for its modes and its secret', async () => {
    const data = make();
    const done = data.refresh();
    gets[0].resolve(setup());
    await done;
    expect(calls.sort()).toEqual(['clients', 'connection dev-db']);
    expect(data.first()?.name).toBe('dev-db');
    expect(data.connections()).toBeNull();
  });

  it('first run: only the AI apps of this computer', async () => {
    const data = make();
    const done = data.refresh();
    gets[0].resolve(firstRun());
    await done;
    expect(calls).toEqual(['clients']);
  });

  it('a core without the clients area leaves the apps out and is not asked again', async () => {
    const data = make();
    clientsAnswer = () => Promise.reject(new ApiError('not-available', 404, {}));
    let done = data.refresh();
    gets[0].resolve(firstRun());
    await done;
    expect(data.clients()).toBeNull();
    done = data.refresh();
    gets[1].resolve(firstRun());
    await done;
    expect(calls).toEqual(['clients']);
  });

  it('keeps the apps it had when their reload fails', async () => {
    const data = make();
    let done = data.refresh();
    gets[0].resolve(overview());
    await done;
    clientsAnswer = () => Promise.reject(new Error('down'));
    done = data.refresh();
    gets[1].resolve(overview());
    await done;
    expect(data.clients()?.clients).toHaveLength(4);
  });

  it('a failed first load is an error; a failed refresh keeps the data and marks it stale', async () => {
    const data = make();
    let done = data.refresh();
    gets[0].promise.catch(() => undefined);
    (gets[0] as unknown as { resolve: (v: unknown) => void }).resolve(Promise.reject(new Error('no core')));
    await done;
    expect(data.error()).toBeTruthy();
    expect(data.overview()).toBeNull();

    done = data.refresh();
    gets[1].resolve(overview());
    await done;
    expect(data.error()).toBeNull();
    expect(data.overview()).not.toBeNull();

    done = data.refresh();
    (gets[2] as unknown as { resolve: (v: unknown) => void }).resolve(Promise.reject(new Error('gone')));
    await done;
    expect(data.stale()).toBeTruthy();
    expect(data.overview()).not.toBeNull();
  });

  it('drops a request that left the queue at once', async () => {
    const data = make();
    const done = data.refresh();
    gets[0].resolve(overview());
    await done;
    data.dropWaiting('ap_sql000000001');
    expect(data.overview()?.waiting.map((w) => w.id)).toEqual(['ap_k8s000000001', 'ap_http00000001']);
  });
});
