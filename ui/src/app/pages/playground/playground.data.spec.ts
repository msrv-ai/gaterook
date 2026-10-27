import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivityApi, ActivityEvent, EventDetail } from '../../core/api/activity.api';
import { ApiError } from '../../core/api/api-error';
import { ConnectionsApi } from '../../core/api/connections.api';
import { EndpointSummary, EndpointsApi } from '../../core/api/endpoints.api';
import { PlaygroundApi, PlaygroundCallView } from '../../core/api/playground.api';
import { PoliciesApi } from '../../core/api/policies.api';
import { JsonObject } from '../../core/api/types';
import { EventStream } from '../../core/events/event-stream';
import { EndpointsStore } from '../../core/stores/endpoints.store';
import { TimeService } from '../../core/time/time.service';
import { CONNECTIONS, DEV_GROUPS, ENDPOINTS, NOW, PROD_GROUPS, callView, event, toolsAnswer } from './playground.fixtures';
import { HISTORY_RELOAD_MS, PlaygroundData } from './playground.data';

/** The Playground's data against fake admin API services and a fake event stream. */

class FakeEvents {
  readonly reopened = signal(0);
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

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

let calls: string[];
let events: FakeEvents;
let history: ActivityEvent[];
let callAnswer: (body: { tool: string; arguments: JsonObject }) => Promise<PlaygroundCallView>;
let revisions: string[] | null;
let storeList: ReturnType<typeof signal<EndpointSummary[] | null>>;
let storeError: ReturnType<typeof signal<unknown>>;

function setup(): PlaygroundData {
  calls = [];
  events = new FakeEvents();
  history = [];
  revisions = ['2026-07-28', '2025-11-25'];
  callAnswer = () => Promise.resolve(callView());
  storeList = signal<EndpointSummary[] | null>(null);
  storeError = signal<unknown>(null);
  const play: Partial<PlaygroundApi> = {
    tools: (slug) => {
      calls.push(`tools ${slug}`);
      return Promise.resolve(toolsAnswer(slug));
    },
    call: (body) => {
      calls.push(`call ${body.endpoint} ${body.tool} ${body.era} ${JSON.stringify(body.arguments)}`);
      return callAnswer(body);
    },
    history: (slug, _cursor, limit) => {
      calls.push(`history ${slug} ${limit}`);
      return Promise.resolve({ events: history, nextCursor: null });
    },
  };
  const endpoints: Partial<EndpointsApi> = {
    tools: (slug) => {
      calls.push(`groups ${slug}`);
      return Promise.resolve(slug === 'incident-prod' ? PROD_GROUPS : DEV_GROUPS);
    },
    settings: (slug) => {
      calls.push(`settings ${slug}`);
      return revisions ? Promise.resolve({ protocol: { revisions } } as never) : Promise.reject(new Error('no settings'));
    },
  };
  TestBed.configureTestingModule({
    providers: [
      PlaygroundData,
      { provide: PlaygroundApi, useValue: play },
      { provide: EndpointsApi, useValue: endpoints },
      { provide: ConnectionsApi, useValue: { list: () => Promise.resolve({ items: CONNECTIONS, facets: {}, total: CONNECTIONS.length }) } },
      {
        provide: PoliciesApi,
        useValue: {
          get: (env: string) => {
            calls.push(`policy ${env}`);
            return Promise.resolve({ sections: { approvals: { comment: 'required' } } });
          },
        },
      },
      {
        provide: ActivityApi,
        useValue: {
          event: (id: number) => {
            calls.push(`event ${id}`);
            return Promise.resolve({ event: event(id) } as unknown as EventDetail);
          },
        },
      },
      {
        provide: EndpointsStore,
        useValue: {
          list: storeList,
          error: storeError,
          load: () => {
            calls.push('endpoints');
            storeList.set(ENDPOINTS);
            return Promise.resolve();
          },
        },
      },
      { provide: EventStream, useValue: events },
      { provide: TimeService, useValue: { now: signal(NOW) } },
    ],
  });
  return TestBed.inject(PlaygroundData);
}

describe('PlaygroundData', () => {
  it('loads the endpoints and connections, then one endpoint’s tools, pairs, revisions and history', async () => {
    const data = setup();
    expect(data.endpoints()).toBeNull();
    await data.start();
    expect(data.endpoints()?.map((e) => e.slug)).toEqual(['incident-prod', 'dev', 'stage-readonly']);
    expect(data.connections()).toHaveLength(CONNECTIONS.length);

    history = [event(2), event(1)];
    await data.open('dev');
    expect(calls).toEqual(['endpoints', 'tools dev', 'groups dev', 'settings dev', 'history dev 5']);
    expect(data.slug()).toBe('dev');
    expect(data.tools()).toMatchObject({ slug: 'dev', groups: DEV_GROUPS, revisions: ['2026-07-28', '2025-11-25'] });
    expect(data.history()?.map((e) => e.id)).toEqual([2, 1]);
    expect(data.summary()?.slug).toBe('dev');
    expect(data.era()).toBe('2026-07-28');
    // Only prod's warning says whether approving needs a comment.
    expect(data.commentRequired()).toBeNull();
  });

  it('reads the comment rule of a prod endpoint and the revision it answers', async () => {
    const data = setup();
    revisions = ['2025-11-25'];
    await data.start();
    await data.open('incident-prod');
    expect(calls).toContain('policy prod');
    expect(data.commentRequired()).toBe(true);
    expect(data.era()).toBe('2025-11-25');
  });

  it('keeps the newest revision when the settings didn’t load', async () => {
    const data = setup();
    revisions = null;
    await data.start();
    await data.open('dev');
    expect(data.tools()?.revisions).toBeNull();
    expect(data.era()).toBe('2026-07-28');
  });

  it('says why the endpoints or the tools didn’t load, and tries again', async () => {
    const data = setup();
    const store = TestBed.inject(EndpointsStore) as unknown as { load: () => Promise<void> };
    const failure = new ApiError('network', 0, { title: 'No answer', detail: 'The core did not answer.' });
    store.load = () => {
      storeError.set(failure);
      return Promise.resolve();
    };
    await data.loadEndpoints();
    expect(data.endpointsError()).toBe(failure);

    const play = TestBed.inject(PlaygroundApi) as Partial<PlaygroundApi>;
    const tools = play.tools!;
    play.tools = () => Promise.reject(failure);
    await data.open('dev');
    expect(data.tools()).toBeNull();
    expect(data.toolsError()).toBe(failure);
    play.tools = tools;
    await data.retryTools();
    expect(data.toolsError()).toBeNull();
    expect(data.tools()?.slug).toBe('dev');
  });

  it('runs a tool in the endpoint’s revision and keeps the answer as the last run', async () => {
    const data = setup();
    await data.start();
    await data.open('dev');
    calls = [];
    let during: unknown = null;
    callAnswer = () => {
      during = data.running();
      return Promise.resolve(callView({ callId: 'tc_9' }));
    };
    const outcome = await data.run({ tool: 'sql_query', arguments: { connection: 'dev-db', sql: 'SELECT 1' }, connection: 'dev-db', env: 'dev' });
    expect(during).toEqual({ tool: 'sql_query', connection: 'dev-db' });
    expect(data.running()).toBeNull();
    expect(outcome.ok).toBe(true);
    expect(calls).toEqual(['call dev sql_query 2026-07-28 {"connection":"dev-db","sql":"SELECT 1"}', 'history dev 5']);
    expect(data.lastRun()).toMatchObject({ endpoint: 'dev', url: 'http://127.0.0.1:7465/mcp/dev', tool: 'sql_query', connection: 'dev-db', env: 'dev', era: '2026-07-28', at: new Date(NOW).toISOString() });
    expect(data.measured().get('tc_9')).toBe(38);
    expect(data.pending()).toBeNull();
  });

  it('returns the refusal of the admin API without a last run', async () => {
    const data = setup();
    await data.start();
    await data.open('dev');
    const refusal = new ApiError('bad-request', 400, { title: 'Bad arguments', detail: 'sql is required.' });
    callAnswer = () => Promise.reject(refusal);
    const outcome = await data.run({ tool: 'sql_query', arguments: {}, connection: null, env: null });
    expect(outcome).toEqual({ ok: false, error: refusal });
    expect(data.lastRun()).toBeNull();
    expect(data.running()).toBeNull();
  });

  it('follows the approval request of a run until a person decides it', async () => {
    const data = setup();
    await data.start();
    await data.open('dev');
    callAnswer = (body) => {
      if (body.tool === 'sql_execute') {
        // The core sends approval.requested while the call waits.
        events.emit('approval.requested', { id: 'ap_7f3k', endpoint: 'dev', tool: 'sql_execute', connection: 'dev-db' });
        return Promise.resolve(callView({ tool: 'sql_execute', callId: 'tc_p', approval: { handle: 'ap_7f3k', status: 'pending' } }));
      }
      return Promise.resolve(callView({ tool: 'approval_status', callId: 'tc_s', text: 'Approved and ran: 1 row changed.' }));
    };
    await data.run({ tool: 'sql_execute', arguments: { connection: 'dev-db', sql: 'UPDATE orders SET status = 1' }, connection: 'dev-db', env: 'dev' });
    expect(data.requestSent()).toEqual({ id: 'ap_7f3k', tool: 'sql_execute', connection: 'dev-db' });
    expect(data.pending()).toEqual({ handle: 'ap_7f3k', tool: 'sql_execute', connection: 'dev-db', env: 'dev' });

    // Another request's decision changes nothing.
    calls = [];
    events.emit('approval.resolved', { id: 'ap_other', status: 'approved' });
    await sleep(0);
    expect(calls).toEqual([]);

    events.emit('approval.resolved', { id: 'ap_7f3k', status: 'approved' });
    await sleep(0);
    await sleep(0);
    expect(calls[0]).toBe('call dev approval_status 2026-07-28 {"handle":"ap_7f3k"}');
    expect(data.pending()).toBeNull();
    expect(data.lastRun()).toMatchObject({ tool: 'approval_status', connection: null, env: 'dev' });
    expect(data.lastRun()?.call.text).toBe('Approved and ran: 1 row changed.');
  });

  it('ignores approval.requested when nothing of this page runs', async () => {
    const data = setup();
    await data.start();
    await data.open('dev');
    events.emit('approval.requested', { id: 'ap_1', endpoint: 'dev', tool: 'sql_execute', connection: 'dev-db' });
    expect(data.requestSent()).toBeNull();
  });

  it('reloads the history once after a burst of this page’s audit.call', async () => {
    const data = setup();
    await data.start();
    await data.open('dev');
    calls = [];
    events.emit('audit.call', { endpoint: 'dev', client: 'playground' });
    events.emit('audit.call', { endpoint: 'dev', client: 'playground' });
    events.emit('audit.call', { endpoint: 'dev', client: 'codex' });
    events.emit('audit.call', { endpoint: 'stage-readonly', client: 'playground' });
    await sleep(HISTORY_RELOAD_MS + 50);
    expect(calls).toEqual(['history dev 5']);
  });

  it('reads everything again when the event stream comes back', async () => {
    const data = setup();
    await data.start();
    await data.open('dev');
    calls = [];
    events.reopened.set(1);
    TestBed.tick();
    await sleep(0);
    await sleep(0);
    expect(calls).toEqual(['endpoints', 'tools dev', 'groups dev', 'settings dev', 'history dev 5']);
  });

  it('finds the audit event of a call through the history, once', async () => {
    const data = setup();
    await data.start();
    history = [event(4, { callId: 'tc_4' })];
    await data.open('dev');
    expect(data.eventOf('tc_4')?.id).toBe(4);
    expect(data.eventOf(null)).toBeNull();
    calls = [];
    expect(await data.eventFor('tc_4')).toMatchObject({ event: { id: 4 } });
    expect(await data.eventFor('tc_4')).toMatchObject({ event: { id: 4 } });
    expect(calls).toEqual(['event 4']);
    // Not in the history yet: one reload, then nothing.
    expect(await data.eventFor('tc_missing')).toBeNull();
    expect(calls).toEqual(['event 4', 'history dev 5']);
  });

  it('forgets the old endpoint’s answers when another opens', async () => {
    const data = setup();
    await data.start();
    history = [event(1)];
    await data.open('dev');
    const opening = data.open('incident-prod');
    expect(data.tools()).toBeNull();
    expect(data.history()).toBeNull();
    await opening;
    expect(data.tools()?.slug).toBe('incident-prod');
  });
});
