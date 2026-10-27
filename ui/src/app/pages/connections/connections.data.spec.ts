import { TestBed } from '@angular/core/testing';
import { ApiError } from '../../core/api/api-error';
import { ApprovalsApi } from '../../core/api/approvals.api';
import { ConnectionList, ConnectionsApi, TestOutcome } from '../../core/api/connections.api';
import { ConnectorTypesApi } from '../../core/api/connector-types.api';
import { EndpointsApi } from '../../core/api/endpoints.api';
import { SecretsApi } from '../../core/api/secrets.api';
import { EventStream } from '../../core/events/event-stream';
import { ConnectionsData, TEST_ALL_IDLE_MS } from './connections.data';
import { SECRETS, SHORT_LIVED, at, detail, kitRows, outcome } from './connections.fixtures';

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

const list = (items = kitRows()): ConnectionList => ({ items, facets: { health: {}, env: {}, type: {} }, total: items.length });

let events: FakeEvents;
let calls: string[];
let listAnswer: () => Promise<ConnectionList>;
let testAllAnswer: () => Promise<{ connections: number }>;
let testAnswer: () => Promise<TestOutcome>;
let secretsAnswer: () => Promise<unknown>;

function setup(): ConnectionsData {
  events = new FakeEvents();
  calls = [];
  listAnswer = () => Promise.resolve(list());
  testAllAnswer = () => Promise.resolve({ connections: 12 });
  testAnswer = () => Promise.resolve(outcome());
  secretsAnswer = () => Promise.resolve(SECRETS);
  const connections: Partial<ConnectionsApi> = {
    list: () => {
      calls.push('list');
      return listAnswer();
    },
    testAll: () => {
      calls.push('test-all');
      return testAllAnswer();
    },
    test: (name) => {
      calls.push(`test ${name}`);
      return testAnswer();
    },
    get: (name) => {
      calls.push(`get ${name}`);
      return name === 'dev-db' ? Promise.resolve(detail('dev-db')) : Promise.reject(new ApiError('server', 500, { detail: 'Detail failed.' }));
    },
    delete: (name, confirmName) => {
      calls.push(`delete ${name} ${confirmName}`);
      return Promise.resolve({ stoppedTools: ['sql_query'], rejectedApprovals: 1, templates: [], clients: [], secretsDestroyed: ['dev-db/password'] });
    },
    confirmIntegrity: (name) => {
      calls.push(`keep ${name}`);
      return Promise.resolve({ ...kitRows()[5], verified: true });
    },
  };
  const secrets: Partial<SecretsApi> = {
    list: () => secretsAnswer() as never,
    shortLived: () => Promise.resolve(SHORT_LIVED),
  };
  TestBed.configureTestingModule({
    providers: [
      ConnectionsData,
      { provide: ConnectionsApi, useValue: connections },
      { provide: SecretsApi, useValue: secrets },
      { provide: ApprovalsApi, useValue: { list: () => Promise.resolve({ items: [{ tool: 'sql_execute', connection: 'dev-db', client: 'codex', status: 'pending' }] }) } },
      { provide: EndpointsApi, useValue: { list: () => Promise.reject(new ApiError('network', 0, {})) } },
      { provide: ConnectorTypesApi, useValue: { get: () => Promise.resolve({ tools: ['sql_list_schemas', 'sql_describe_table', 'sql_query', 'sql_execute'].map((name) => ({ name })) }) } },
      { provide: EventStream, useValue: events },
    ],
  });
  return TestBed.inject(ConnectionsData);
}

const health = (name: string, state: string, latencyMs?: number) => ({ name, env: 'dev', state, latencyMs, message: null, checkedAt: at('14:30:05') });

afterEach(() => vi.useRealTimers());

const tick = () => new Promise((r) => setTimeout(r, 0));

describe('ConnectionsData', () => {
  it('loads the list by name with the secrets of connections, then follows health events', async () => {
    const data = setup();
    await data.start();
    expect(data.rows()?.map((r) => r.name).slice(0, 2)).toEqual(['app-logs', 'aws-stage']);
    expect(data.secretsOf('billing-api').map((s) => s.name)).toEqual(['billing-api/token', 'billing-api/webhook-secret']);
    expect(data.shortLived()).toHaveLength(2);
    expect(data.error()).toBeNull();

    events.emit('connection.health', health('cache-redis', 'healthy', 40));
    const redis = data.rows()!.find((r) => r.name === 'cache-redis')!;
    // cache-redis was slow: a new state starts at the check that found it (the event doesn't say since when).
    expect(redis.health).toEqual({ state: 'healthy', latencyMs: 40, message: null, checkedAt: at('14:30:05'), since: at('14:30:05') });
    expect(calls).toEqual(['list']);
  });

  it('keeps the list when Secrets doesn’t answer', async () => {
    const data = setup();
    secretsAnswer = () => Promise.reject(new ApiError('not-available', 404, {}));
    await data.start();
    expect(data.rows()).toHaveLength(12);
    expect(data.secrets()).toEqual([]);
  });

  it('tells a failed first load from a failed reload', async () => {
    const data = setup();
    listAnswer = () => Promise.reject(new ApiError('server', 500, { detail: 'Locked.' }));
    await data.start();
    expect(data.rows()).toBeNull();
    expect((data.error() as ApiError).detail).toBe('Locked.');

    listAnswer = () => Promise.resolve(list());
    await data.refresh();
    expect(data.error()).toBeNull();
    expect(data.rows()).toHaveLength(12);

    listAnswer = () => Promise.reject(new ApiError('network', 0, {}));
    await data.refresh();
    expect(data.rows()).toHaveLength(12);
    expect(data.stale()).toBeInstanceOf(ApiError);
    expect(data.error()).toBeNull();
  });

  it('coalesces reloads: one in flight, one more after it', async () => {
    const data = setup();
    await data.start();
    calls = [];
    const first = deferred<ConnectionList>();
    listAnswer = () => first.promise;
    const a = data.refresh();
    const b = data.refresh();
    void data.refresh();
    expect(a).toBe(b);
    listAnswer = () => Promise.resolve(list());
    first.resolve(list());
    await a;
    await tick();
    // Three asks while one was in flight: that one and a single follow-up.
    expect(calls).toEqual(['list', 'list']);
    expect(data.refreshing()).toBe(false);
  });

  it('tests one connection: "Checking" while the test runs, then the recorded health', async () => {
    const data = setup();
    await data.start();
    const answer = deferred<TestOutcome>();
    testAnswer = () => answer.promise;
    const run = data.test('dev-db');
    expect(data.checking().has('dev-db')).toBe(true);
    answer.resolve(outcome({ latencyMs: 9 }));
    const result = await run;
    expect(result).toEqual({ ok: true, outcome: outcome({ latencyMs: 9 }) });
    expect(data.checking().size).toBe(0);
    expect(calls).toEqual(['list', 'test dev-db', 'list']);

    testAnswer = () => Promise.reject(new ApiError('conflict', 409, { detail: 'dev-db was changed outside GateRook. Review it first.' }));
    const refused = await data.test('dev-db');
    expect(refused.ok).toBe(false);
    expect(data.checking().size).toBe(0);
  });

  it('runs Test all row by row from health events, even before the core’s 202 arrives', async () => {
    const data = setup();
    await data.start();
    const started = deferred<{ connections: number }>();
    testAllAnswer = () => started.promise;
    let ended: unknown = null;
    void data.testAll().then((e) => (ended = e));
    expect(data.testingAll()).toEqual({ total: null });
    expect(data.checking().size).toBe(12);

    // The first result comes before the 202.
    events.emit('connection.health', health('dev-db', 'healthy', 12));
    expect(data.checking().has('dev-db')).toBe(false);
    started.resolve({ connections: 3 });
    await tick();
    expect(data.testingAll()).toEqual({ total: 3 });

    events.emit('connection.health', health('cache-redis', 'degraded', 1800));
    events.emit('connection.health', health('dev-db', 'healthy', 10)); // a second event for a row already counted
    expect(ended).toBeNull();
    events.emit('connection.health', health('billing-api', 'unreachable'));
    await tick();
    // The core tested three: the rest stop "Checking" and keep their health.
    expect(ended).toEqual({ total: 3, results: ['healthy', 'degraded', 'unreachable'] });
    expect(data.testingAll()).toBeNull();
    expect(data.checking().size).toBe(0);
  });

  it('stops waiting when no result came for a while', async () => {
    vi.useFakeTimers();
    const data = setup();
    await data.start();
    let ended: { total: number; results: readonly string[] } | null = null;
    void data.testAll().then((e) => (ended = e));
    await vi.advanceTimersByTimeAsync(0);
    events.emit('connection.health', health('dev-db', 'healthy', 12));
    await vi.advanceTimersByTimeAsync(TEST_ALL_IDLE_MS - 1);
    expect(ended).toBeNull();
    await vi.advanceTimersByTimeAsync(1);
    expect(ended).toEqual({ total: 12, results: ['healthy'] });
    expect(data.checking().size).toBe(0);
  });

  it('gives up Test all when the core refuses it, and doesn’t run two', async () => {
    const data = setup();
    await data.start();
    testAllAnswer = () => Promise.reject(new ApiError('conflict', 409, { detail: 'Test all is still running; results arrive one by one.' }));
    await expect(data.testAll()).rejects.toBeInstanceOf(ApiError);
    expect(data.testingAll()).toBeNull();
    expect(data.checking().size).toBe(0);

    testAllAnswer = () => new Promise(() => undefined);
    void data.testAll();
    await expect(data.testAll()).rejects.toThrow('already running');
  });

  it('skips rows changed outside GateRook in Test all', async () => {
    const data = setup();
    listAnswer = () => Promise.resolve(list(kitRows().map((r) => (r.name === 'dev-db' ? { ...r, verified: false } : r))));
    await data.start();
    void data.testAll();
    expect(data.checking().has('dev-db')).toBe(false);
    expect(data.checking().size).toBe(11);
  });

  it('collects the delete confirmation’s facts; a missing part is left out', async () => {
    const data = setup();
    await data.start();
    const rows = data.rows()!;
    const facts = await data.deleteFacts(rows.find((r) => r.name === 'dev-db')!);
    expect(facts.failed).toBeNull();
    expect(facts.impact).toEqual({
      endpoints: [{ slug: 'dev', tools: ['sql_list_schemas', 'sql_describe_table', 'sql_query', 'sql_execute'], clients: null }],
      pending: [{ tool: 'sql_execute', client: 'Codex' }],
      secrets: 'Its encrypted password (version 3) is destroyed.',
    });
    const other = await data.deleteFacts(rows.find((r) => r.name === 'app-logs')!);
    expect((other.failed as ApiError).detail).toBe('Detail failed.');
    expect(other.impact.secrets).toBeNull();
    expect(other.impact.endpoints).toEqual([{ slug: 'dev', tools: [], clients: null }]);
  });

  it('deletes with the name as the confirmation, and keeps a reviewed row', async () => {
    const data = setup();
    await data.start();
    const result = await data.remove('dev-db');
    expect(result.rejectedApprovals).toBe(1);
    expect(calls).toContain('delete dev-db dev-db');
    expect(data.rows()!.some((r) => r.name === 'dev-db')).toBe(false);
    expect(data.secretsOf('dev-db')).toEqual([]);

    await data.keep('dev-db');
    expect(calls).toContain('keep dev-db');
  });

  it('stops following events when the page goes', async () => {
    const data = setup();
    await data.start();
    data.stop();
    events.emit('connection.health', health('cache-redis', 'healthy', 40));
    expect(data.rows()!.find((r) => r.name === 'cache-redis')!.health.state).toBe('degraded');
  });
});
