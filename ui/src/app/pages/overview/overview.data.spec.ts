import { Injector, effect, runInInjectionContext, untracked } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ApiError } from '../../core/api/api-error';
import { ClientsApi } from '../../core/api/clients.api';
import { ConnectionsApi } from '../../core/api/connections.api';
import { EndpointDetail, EndpointSummary, EndpointsApi } from '../../core/api/endpoints.api';
import { Overview, OverviewApi } from '../../core/api/overview.api';
import { EventStream } from '../../core/events/event-stream';
import { ROWS, browserSignedIn, clientsBrowserSignedIn, clientsFound, clientsInUse, firstRun, overview, setup } from './overview.fixtures';
import { OverviewData } from './overview.data';
import { endpoint } from '../endpoints/endpoints.fixtures';
import { detail } from '../endpoint-detail/endpoint-detail.fixtures';

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
let endpointsAnswer: () => Promise<EndpointSummary[]>;
let endpointDetailAnswer: (slug: string) => Promise<EndpointDetail>;
let connectionsFail: boolean;
let events: FakeEvents;

function make(): OverviewData {
  gets = [];
  calls = [];
  events = new FakeEvents();
  clientsAnswer = () => Promise.resolve(clientsInUse());
  endpointsAnswer = () => Promise.resolve([]);
  endpointDetailAnswer = (slug) => Promise.resolve(detail({ endpoint: { slug }, connections: [] }));
  connectionsFail = false;
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
      if (connectionsFail) return Promise.reject(new Error('connections unavailable'));
      return Promise.resolve({ items: ROWS, facets: { health: {}, env: {}, type: {} }, total: ROWS.length });
    },
  };
  TestBed.configureTestingModule({
    providers: [
      { provide: OverviewApi, useValue: overviewApi },
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
      { provide: EndpointsApi, useValue: {
        list: () => { calls.push('endpoints'); return endpointsAnswer(); },
        get: (slug: string) => { calls.push(`endpoint ${slug}`); return endpointDetailAnswer(slug); },
      } },
      { provide: EventStream, useValue: events },
    ],
  });
  return runInInjectionContext(TestBed.inject(Injector), () => new OverviewData());
}

describe('OverviewData', () => {
  it('does not duplicate the initial wiring load when the page selects the first loaded endpoint', async () => {
    const data = make();
    endpointsAnswer = async () => [endpoint('dev')];
    runInInjectionContext(TestBed.inject(Injector), () => effect(() => {
      const slug = data.endpoints()?.[0]?.slug ?? null;
      untracked(() => void data.selectEndpoint(slug));
    }));
    TestBed.tick();
    let done = data.refresh();
    gets[0].resolve(overview());
    await done;
    TestBed.tick();
    await sleep(0);
    expect(calls.filter((call) => call.startsWith('endpoint '))).toEqual(['endpoint dev']);

    done = data.refresh();
    gets[1].resolve(overview());
    await done;
    TestBed.tick();
    await sleep(0);
    expect(calls.filter((call) => call.startsWith('endpoint '))).toEqual(['endpoint dev', 'endpoint dev']);
  });

  it('loads all service counts with one selected-endpoint request and includes every saved mode', async () => {
    const data = make();
    endpointDetailAnswer = async (slug) => detail({ endpoint: { slug }, connections: [
      { name: 'dev-db', type: 'postgres', typeName: 'PostgreSQL', env: 'dev', health: 'healthy', problem: null, tools: { read: 2, approval: 3, off: 4, bypass: 1 } },
      { name: 'empty-mcp', type: 'mcp-server', typeName: 'MCP server', env: 'dev', health: 'healthy', problem: null, tools: { read: 0, approval: 0, off: 0 } },
    ] });
    expect(data.connectionToolCount('dev', 'dev-db')).toBeNull();
    await data.selectEndpoint('dev');
    await data.selectEndpoint('dev');
    expect(calls).toEqual(['endpoint dev']);
    expect(data.connectionToolCount('dev', 'dev-db')).toBe(10);
    expect(data.connectionToolCount('dev', 'empty-mcp')).toBe(0);
    expect(data.connectionToolCount('dev', 'unknown-db')).toBeNull();
    expect(data.connectionToolCount('stage', 'dev-db')).toBeNull();
  });

  it('clears unavailable counts and reloads the selected scope with an Overview refresh', async () => {
    const data = make();
    endpointsAnswer = async () => [endpoint('dev')];
    endpointDetailAnswer = async () => detail();
    await data.selectEndpoint('dev');
    expect(data.connectionToolCount('dev', 'dev-db')).toBe(4);
    endpointDetailAnswer = async () => { throw new Error('unavailable'); };
    let done = data.refresh();
    gets[0].resolve(overview());
    await done;
    expect(data.connectionToolCount('dev', 'dev-db')).toBeNull();
    endpointDetailAnswer = async () => detail({ connections: [
      { name: 'dev-db', type: 'postgres', typeName: 'PostgreSQL', env: 'dev', health: 'healthy', problem: null, tools: { read: 2, approval: 1, off: 0 } },
    ] });
    done = data.refresh();
    gets[1].resolve(overview());
    await done;
    expect(data.connectionToolCount('dev', 'dev-db')).toBe(3);
    expect(calls.filter((call) => call.startsWith('endpoint '))).toEqual(['endpoint dev', 'endpoint dev', 'endpoint dev']);
  });

  it('ignores late counts after changing endpoints or disposing the page', async () => {
    const data = make();
    const late = deferred<EndpointDetail>();
    endpointDetailAnswer = (slug) => slug === 'dev' ? late.promise : Promise.resolve(detail({ endpoint: { slug } }));
    const old = data.selectEndpoint('dev');
    await data.selectEndpoint('stage');
    expect(data.connectionToolCount('stage', 'dev-db')).toBe(4);
    expect(data.connectionToolCount('dev', 'dev-db')).toBeNull();
    late.resolve(detail());
    await old;
    expect(data.connectionToolCount('stage', 'dev-db')).toBe(4);
    await data.selectEndpoint(null);
    expect(data.connectionToolCount('stage', 'dev-db')).toBeNull();
    const afterStop = deferred<EndpointDetail>();
    endpointDetailAnswer = () => afterStop.promise;
    const loading = data.selectEndpoint('dev');
    data.stop();
    afterStop.resolve(detail());
    await loading;
    expect(data.connectionToolCount('dev', 'dev-db')).toBeNull();
  });

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
      expect(calls.sort()).toEqual(['clients', 'connections', 'endpoints']);
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

  it('loads the connection map and aggregate approvals without fetching individual action details', async () => {
    const data = make();
    const done = data.refresh();
    gets[0].resolve(overview());
    await done;
    expect(calls.sort()).toEqual(['clients', 'connections', 'endpoints']);
    expect(data.clients()?.clients).toHaveLength(4);
    expect(data.connections()).toHaveLength(ROWS.length);
    expect(data.endpoints()).toEqual([]);
    expect(data.overview()?.waiting).toHaveLength(3);
  });

  it('setup loads the same map lists without fetching the first connection detail', async () => {
    const data = make();
    const done = data.refresh();
    gets[0].resolve(setup());
    await done;
    expect(calls.sort()).toEqual(['clients', 'connections', 'endpoints']);
    expect(data.connections()).toHaveLength(ROWS.length);
    expect(data.endpoints()).toEqual([]);
  });

  it('first run: loads AI apps, endpoints and saved services even before the guide marks a connection complete', async () => {
    const data = make();
    endpointsAnswer = () => Promise.resolve([endpoint('dev')]);
    const done = data.refresh();
    gets[0].resolve(firstRun());
    await done;
    expect(calls).toEqual(['clients', 'connections', 'endpoints']);
    expect(data.endpoints()).toEqual([endpoint('dev')]);
    expect(data.connections()).toHaveLength(ROWS.length);
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
    expect(calls).toEqual(['clients', 'connections', 'endpoints', 'connections', 'endpoints']);
    expect(data.areaErrors().clients).toBeInstanceOf(ApiError);
  });

  it('marks app links unavailable when their reload fails instead of claiming stale access is current', async () => {
    const data = make();
    let done = data.refresh();
    gets[0].resolve(overview());
    await done;
    clientsAnswer = () => Promise.reject(new Error('down'));
    done = data.refresh();
    gets[1].resolve(overview());
    await done;
    expect(data.clients()).toBeNull();
    expect(data.areaErrors().clients).toEqual(new Error('down'));
  });

  it('keeps overview health when endpoint or wiring requests fail and recovers on the next refresh', async () => {
    const data = make();
    endpointsAnswer = () => Promise.reject(new Error('endpoints unavailable'));
    let done = data.refresh();
    gets[0].resolve(overview());
    await done;
    expect(data.overview()?.health).toHaveLength(12);
    expect(data.endpoints()).toBeNull();
    expect(data.connections()).toHaveLength(ROWS.length);
    expect(data.error()).toBeNull();
    expect(data.areaErrors()).toEqual({ connections: null, clients: null, endpoints: new Error('endpoints unavailable') });

    endpointsAnswer = () => Promise.resolve([]);
    connectionsFail = true;
    done = data.refresh();
    gets[1].resolve(overview());
    await done;
    expect(data.endpoints()).toEqual([]);
    expect(data.connections()).toBeNull();
    expect(data.areaErrors()).toEqual({ connections: new Error('connections unavailable'), clients: null, endpoints: null });

    connectionsFail = false;
    done = data.refresh();
    gets[2].resolve(overview());
    await done;
    expect(data.connections()).toHaveLength(ROWS.length);
    expect(data.overview()?.health).toHaveLength(12);
    expect(data.areaErrors()).toEqual({ connections: null, clients: null, endpoints: null });
  });

  it('applies confirmed switches without deleting endpoint wiring or changing another connection setting', async () => {
    const data = make();
    endpointsAnswer = () => Promise.resolve([endpoint('dev'), endpoint('stage-readonly')]);
    const done = data.refresh();
    gets[0].resolve(setup());
    await done;
    data.patchConnectionEnabled('dev-db', false);
    expect(data.connections()?.find((row) => row.name === 'dev-db')).toEqual(expect.objectContaining({ enabled: false, usedBy: ['dev'] }));
    expect(data.connections()?.find((row) => row.name === 'cache-redis')?.enabled).toBeUndefined();
    data.patchEndpointEnabled('dev', false);
    expect(data.endpoints()?.find((row) => row.slug === 'dev')?.status).toBe('disabled');
    expect(data.endpoints()?.find((row) => row.slug === 'stage-readonly')?.status).toBe('running');
    expect(data.connections()?.find((row) => row.name === 'cache-redis')?.usedBy).toEqual(['dev']);
    expect(data.connections()?.find((row) => row.name === 'dev-db')?.enabled).toBe(false);
  });

  it('refreshes MCP availability and external configuration blocks without a tool call', async () => {
    const data = make();
    const done = data.start();
    gets[0].resolve(firstRun());
    await done;
    vi.useFakeTimers();
    try {
      events.emit('mcp-server.state', { server: 'docs', state: 'running' });
      events.emit('mcp-server.sign-in', { server: 'docs', state: 'signed-in' });
      events.emit('integrity.alert', { scope: 'endpoint', name: 'dev' });
      await vi.advanceTimersByTimeAsync(1_000);
      expect(gets).toHaveLength(2);
      gets[1].resolve(firstRun());
      await vi.advanceTimersByTimeAsync(0);
      expect(data.connections()).toHaveLength(ROWS.length);
    } finally {
      data.stop();
      vi.useRealTimers();
    }
  });

  it('patches grouped connection health even for a server absent from overview health', async () => {
    const data = make();
    const done = data.refresh();
    gets[0].resolve(overview());
    await done;
    expect(data.overview()?.health.some((row) => row.name === 'github-mcp')).toBe(false);
    const checkedAt = '2026-10-22T12:30:00Z';
    data.patchHealth({ name: 'github-mcp', state: 'unreachable', latencyMs: null, message: 'Server unavailable', checkedAt });
    expect(data.connections()?.find((row) => row.name === 'github-mcp')?.health).toEqual({
      state: 'unreachable', latencyMs: null, message: 'Server unavailable', checkedAt, since: checkedAt,
    });
    expect(data.connections()?.find((row) => row.name === 'dev-db')?.health.state).toBe('healthy');
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
