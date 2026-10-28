import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivityApi } from '../../core/api/activity.api';
import { ApiError } from '../../core/api/api-error';
import { ClientsApi, ClientsView } from '../../core/api/clients.api';
import { ConnectionsApi } from '../../core/api/connections.api';
import { ConnectorTypesApi } from '../../core/api/connector-types.api';
import { EndpointDetail, EndpointsApi, TokensApi } from '../../core/api/endpoints.api';
import { PoliciesApi } from '../../core/api/policies.api';
import { SettingsApi } from '../../core/api/settings.api';
import { OAuthApi, OAuthGrant } from '../../core/api/tunnel.api';
import { EventStream } from '../../core/events/event-stream';
import { EndpointsStore } from '../../core/stores/endpoints.store';
import { DEMO_GRANTS } from '../../dev/fixtures-clients';
import { connected } from '../clients/clients.fixtures';
import { EndpointDetailData, LIVE_RELOAD_MS, builtInDescriptions } from './endpoint-detail.data';
import { clientsView, connectionRows, detail, groups, policies, risk, settings, summary, tokens } from './endpoint-detail.fixtures';

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

let events: FakeEvents;
let calls: string[];
let detailAnswer: () => Promise<EndpointDetail>;
let setModeAnswer: () => Promise<unknown>;
let addAnswer: (name: string) => Promise<EndpointDetail>;
let grantsAnswer: () => Promise<unknown>;
let clientsAnswer: () => Promise<ClientsView>;
let settingsBodies: unknown[];

function setup(): EndpointDetailData {
  events = new FakeEvents();
  calls = [];
  settingsBodies = [];
  detailAnswer = () => Promise.resolve(detail());
  setModeAnswer = () => Promise.resolve(groups());
  addAnswer = () => Promise.resolve(detail());
  grantsAnswer = () => Promise.reject(new ApiError('not-available', 404, {}));
  clientsAnswer = () => Promise.resolve(clientsView());
  const endpoints: Partial<EndpointsApi> = {
    get: (slug) => {
      calls.push(`get ${slug}`);
      return detailAnswer();
    },
    tools: (slug) => {
      calls.push(`tools ${slug}`);
      return Promise.resolve(groups());
    },
    check: () => Promise.resolve({ tools: 18, revisions: 2, instructionsChars: 827, instructionsMax: 4000, gatewayP95Ms: 3, problems: [], text: '' }),
    setMode: (slug, change) => {
      calls.push(`mode ${change.toolKey} ${change.mode}`);
      return setModeAnswer() as never;
    },
    confirmReadOnly: (slug, c) => {
      calls.push(`confirm ${c.toolKey}`);
      return Promise.reject(new ApiError('conflict', 409, { detail: 'The tool changed.' }));
    },
    bulk: (slug, r) => {
      calls.push(`bulk ${r.group} ${r.mode}`);
      return Promise.resolve({ changed: ['sql_query'], skipped: [{ toolKey: 'sql_execute', reason: 'Mutates data' }] });
    },
    addConnection: (slug, name) => {
      calls.push(`add ${name}`);
      return addAnswer(name);
    },
    removeConnection: (slug, name) => {
      calls.push(`remove ${name}`);
      return Promise.resolve({ stoppedTools: ['sql_query'] });
    },
    previewConnection: (slug, name) => (name === 'stage-db' ? Promise.resolve({ connection: name } as never) : Promise.reject(new Error('down'))),
    dismissRisk: (slug, reason) => {
      calls.push(`dismiss ${reason}`);
      return Promise.resolve(risk({ state: 'dismissed', dismissed: { by: 'you', reason } }));
    },
    disable: (slug) => {
      calls.push(`disable ${slug}`);
      return Promise.resolve(summary({ status: 'disabled' }));
    },
    delete: (slug, confirm) => {
      calls.push(`delete ${slug} ${confirm}`);
      return Promise.resolve({ clients: [], tokensRevoked: [], rejectedApprovals: 0 });
    },
    updateSettings: (slug, update) => {
      settingsBodies.push(update);
      return Promise.resolve(settings({ instructions: update.instructions ?? null, version: 9 }));
    },
  };
  TestBed.configureTestingModule({
    providers: [
      EndpointDetailData,
      { provide: EndpointsApi, useValue: endpoints },
      {
        provide: TokensApi,
        useValue: {
          list: () => {
            calls.push('tokens');
            return Promise.resolve(tokens());
          },
          revoke: (id: string) => {
            calls.push(`revoke ${id}`);
            return Promise.resolve({ id, rejectedApprovals: 0 });
          },
          revokeGrant: (id: string) => {
            calls.push(`revoke-grant ${id}`);
            return Promise.resolve({ id, rejectedApprovals: 1 });
          },
        },
      },
      {
        provide: ClientsApi,
        useValue: {
          list: () => {
            calls.push('clients');
            return clientsAnswer();
          },
        },
      },
      { provide: ConnectionsApi, useValue: { list: () => Promise.resolve({ items: connectionRows(), facets: {}, total: 10 }) } },
      { provide: ConnectorTypesApi, useValue: { get: (t: string) => Promise.resolve({ tools: [{ name: `${t}_tool`, description: `What ${t}_tool does.` }] }) } },
      { provide: PoliciesApi, useValue: { all: () => Promise.resolve(policies()) } },
      { provide: SettingsApi, useValue: { get: () => Promise.resolve({ name: 'authorization', values: { clientRegistration: 'cimd' }, version: 0, updatedAt: null }) } },
      {
        provide: ActivityApi,
        useValue: {
          facets: (r: { q: string }) =>
            Promise.resolve(r.q.includes('client:codex') ? { tool: [{ name: 'http_get', count: 1 }, { name: 'http_request', count: 3 }] } : { client: [{ name: 'codex', count: 4 }] }),
        },
      },
      { provide: OAuthApi, useValue: { grants: () => (calls.push('grants'), grantsAnswer()) } },
      { provide: EventStream, useValue: events },
      { provide: EndpointsStore, useValue: { list: signal([summary()]), load: () => Promise.resolve() } },
    ],
  });
  return TestBed.inject(EndpointDetailData);
}

afterEach(() => vi.useRealTimers());

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

describe('EndpointDetailData', () => {
  it('loads the endpoint with its tools, tokens, clients, connections, policies and tool sentences', async () => {
    const data = setup();
    await data.open('dev');
    expect(data.summary()?.slug).toBe('dev');
    expect(data.groups()).toHaveLength(5);
    expect(data.tokens()).toHaveLength(5);
    expect(data.clientsState()).toBe('ready');
    expect(data.connections()).toHaveLength(10);
    expect(data.policies()?.dev).toBeTruthy();
    expect(data.check()?.instructionsChars).toBe(827);
    expect(data.authorization()).toEqual({ clientRegistration: 'cimd' });
    expect(data.descriptions()['postgres_tool']).toBe('What postgres_tool does.');
    expect(data.descriptions()['list_connections']).toBe(builtInDescriptions('dev')['list_connections']);
    // OAuth is off: no sign-ins to ask for.
    expect(data.grantsState()).toBe('ready');
  });

  it('says not found for a 404 with a problem, and fails or goes stale otherwise', async () => {
    const data = setup();
    detailAnswer = () => Promise.reject(new ApiError('not-found', 404, { detail: 'No endpoint nope.' }, { detail: 'No endpoint nope.' }));
    await data.open('nope');
    expect(data.notFound()).toBe(true);
    expect(data.error()).toBeNull();

    detailAnswer = () => Promise.reject(new ApiError('server', 500, { detail: 'Boom.' }));
    await data.open('dev');
    expect(data.error()).toBeTruthy();
    expect(data.notFound()).toBe(false);

    detailAnswer = () => Promise.resolve(detail());
    await data.refresh();
    expect(data.error()).toBeNull();
    detailAnswer = () => Promise.reject(new ApiError('network', 0, {}));
    await data.refresh();
    expect(data.stale()).toBeTruthy();
    expect(data.summary()?.slug).toBe('dev');
  });

  it('follows approvals of this endpoint, health and client drift', async () => {
    const data = setup();
    await data.open('dev');
    calls.length = 0;
    events.emit('approval.requested', { endpoint: 'other' });
    events.emit('approval.requested', { endpoint: 'dev' });
    events.emit('approval.resolved', {});
    await Promise.resolve();
    expect(calls.filter((c) => c === 'get dev')).toHaveLength(2);

    events.emit('connection.health', { name: 'billing-api', state: 'healthy', latencyMs: 40, message: null, checkedAt: '2026-10-22T12:31:00.000Z' });
    // billing-api was unreachable: a new state starts at the check that found it (the event doesn't say since when).
    expect(data.connections()?.find((r) => r.name === 'billing-api')?.health).toEqual({
      state: 'healthy',
      latencyMs: 40,
      message: null,
      checkedAt: '2026-10-22T12:31:00.000Z',
      since: '2026-10-22T12:31:00.000Z',
    });
    expect(data.detail()?.connections.find((c) => c.name === 'billing-api')?.health).toBe('healthy');

    events.emit('clients.drift', {});
    await Promise.resolve();
    expect(calls).toContain('clients');
  });

  it('reloads tokens, clients and the header once after a burst of calls on this endpoint', async () => {
    const data = setup();
    await data.open('dev');
    vi.useFakeTimers();
    calls.length = 0;
    for (let i = 0; i < 5; i++) events.emit('audit.call', { endpoint: 'dev' });
    events.emit('audit.call', { endpoint: 'other' });
    await vi.advanceTimersByTimeAsync(LIVE_RELOAD_MS - 1);
    expect(calls).toEqual([]);
    await vi.advanceTimersByTimeAsync(1);
    expect(calls.sort()).toEqual(['clients', 'get dev', 'tokens']);
  });

  it('refreshes an OAuth connection and sign-ins only for this endpoint without a tool call', async () => {
    const data = setup();
    detailAnswer = () => Promise.resolve(detail({ settings: { oauthEnabled: true } }));
    const link = connected('cursor', { endpoint: 'dev', method: 'oauth' }).link;
    let status: 'needs-restart' | 'connected' = 'needs-restart';
    clientsAnswer = () => Promise.resolve({ ...clientsView(), clients: clientsView().clients.map((c) => c.client === 'cursor' ? { ...c, status, links: [{ ...link, status }] } : c) });
    grantsAnswer = () => Promise.resolve([]);
    await data.open('dev');
    expect(data.clients()?.clients.find((c) => c.client === 'cursor')?.status).toBe('needs-restart');
    calls.length = 0;
    events.emit('oauth.grant.changed', { id: 'og_other', endpoint: 'stage-readonly' });
    await Promise.resolve();
    expect(calls).toEqual([]);

    status = 'connected';
    const grant: OAuthGrant = { ...DEMO_GRANTS[0], id: 'og_cursor', client: 'localhost', clientName: 'Cursor', clientKind: 'dcr', endpoint: 'dev', ingress: 'LOCAL' };
    grantsAnswer = () => Promise.resolve([grant]);
    events.emit('oauth.grant.changed', { id: grant.id, endpoint: 'dev' });
    await Promise.resolve();
    await Promise.resolve();
    expect(calls.sort()).toEqual(['clients', 'get dev', 'grants']);
    expect(data.clients()?.clients.find((c) => c.client === 'cursor')?.status).toBe('connected');
    expect(data.grants()).toEqual([grant]);
  });

  it.each(['success', 'failure'] as const)('ignores a late grant %s from the previous endpoint', async (outcome) => {
    const data = setup();
    detailAnswer = () => Promise.resolve(detail({ settings: { oauthEnabled: true } }));
    grantsAnswer = () => Promise.resolve([]);
    await data.open('dev');

    const pending = deferred<OAuthGrant[]>();
    grantsAnswer = () => pending.promise;
    events.emit('oauth.grant.changed', { id: 'og_dev', endpoint: 'dev' });

    const stageGrant = { ...DEMO_GRANTS[0], id: 'og_stage', endpoint: 'stage-readonly' };
    grantsAnswer = () => Promise.resolve([stageGrant]);
    detailAnswer = () => Promise.resolve(detail({ endpoint: { slug: 'stage-readonly' }, settings: { oauthEnabled: true } }));
    await data.open('stage-readonly');
    expect(data.grants()).toEqual([stageGrant]);
    if (outcome === 'success') pending.resolve([{ ...DEMO_GRANTS[0], id: 'og_dev', endpoint: 'dev' }]);
    else pending.reject(new ApiError('not-available', 404, {}));
    await Promise.resolve();
    expect(data.grants()).toEqual([stageGrant]);
    expect(data.grantsState()).toBe('ready');
  });

  it('keeps a newly revoked grant from returning when an older reload finishes', async () => {
    const data = setup();
    const grant = { ...DEMO_GRANTS[0], id: 'og_cursor', endpoint: 'dev' };
    detailAnswer = () => Promise.resolve(detail({ settings: { oauthEnabled: true } }));
    grantsAnswer = () => Promise.resolve([grant]);
    await data.open('dev');
    const pending = deferred<OAuthGrant[]>();
    grantsAnswer = () => pending.promise;
    events.emit('oauth.grant.changed', { id: grant.id, endpoint: 'dev' });
    grantsAnswer = () => Promise.resolve([]);
    events.emit('oauth.grant.changed', { id: grant.id, endpoint: 'dev' });
    await Promise.resolve();
    expect(data.grants()).toEqual([]);
    pending.resolve([grant]);
    await Promise.resolve();
    expect(data.grants()).toEqual([]);
  });

  it.each(['success', 'failure'] as const)('ignores a pending grant %s after disposal', async (outcome) => {
    const data = setup();
    const pending = deferred<OAuthGrant[]>();
    detailAnswer = () => Promise.resolve(detail({ settings: { oauthEnabled: true } }));
    grantsAnswer = () => pending.promise;
    await data.open('dev');
    data.stop();
    if (outcome === 'success') pending.resolve([{ ...DEMO_GRANTS[0], endpoint: 'dev' }]);
    else pending.reject(new ApiError('not-available', 404, {}));
    await Promise.resolve();
    expect(data.grants()).toBeNull();
    expect(data.grantsState()).toBe('loading');
  });

  it('re-reads the tools when the core refuses a mode, and passes the refusal on', async () => {
    const data = setup();
    await data.open('dev');
    setModeAnswer = () => Promise.reject(new ApiError('unprocessable', 422, { detail: 'Read only is not allowed.' }));
    calls.length = 0;
    await expect(data.setMode({ toolKey: 'sql_execute', connection: 'dev-db', mode: 'READ_ONLY', version: 0 })).rejects.toThrow('Read only is not allowed.');
    expect(calls).toEqual(['mode sql_execute READ_ONLY', 'tools dev', 'get dev']);

    calls.length = 0;
    await expect(data.confirmReadOnly({ toolKey: 'github_list', connection: 'gh', definitionHash: 'h' })).rejects.toThrow('The tool changed.');
    expect(calls).toEqual(['confirm github_list', 'tools dev', 'get dev']);

    const r = await data.bulk('dev-db', 'READ_ONLY');
    expect(r.skipped).toEqual([{ toolKey: 'sql_execute', reason: 'Mutates data' }]);
  });

  it('adds connections one by one and reports the ones the core refused', async () => {
    const data = setup();
    await data.open('dev');
    addAnswer = (name) => (name === 'prod-replica' ? Promise.reject(new ApiError('unprocessable', 422, { detail: 'Goes only on a prod endpoint.' })) : Promise.resolve(detail()));
    const r = await data.addConnections(['stage-db', 'prod-replica']);
    expect(r).toEqual({ added: ['stage-db'], refused: [{ name: 'prod-replica', reason: 'Goes only on a prod endpoint.' }] });
    expect(calls.filter((c) => c.startsWith('add'))).toEqual(['add stage-db', 'add prod-replica']);

    const p = await data.previews(['stage-db', 'billing-api']);
    expect(p.get('stage-db')).toEqual({ connection: 'stage-db' });
    expect(p.get('billing-api')).toBeNull();
  });

  it('revokes a named token or a client’s access through its own route', async () => {
    const data = setup();
    await data.open('dev');
    await data.revoke(tokens()[1]);
    await data.revoke(tokens()[4]);
    expect(calls).toContain('revoke tok_inspector-debug');
    expect(calls).toContain('revoke-grant gr_cx');
  });

  it('dismisses the risk in place, disables in place, and deletes with the slug as confirmation', async () => {
    const data = setup();
    await data.open('dev');
    await data.dismissRisk('sandbox only');
    expect(data.risk()?.state).toBe('dismissed');
    await data.disable();
    expect(data.summary()?.status).toBe('disabled');
    await data.remove();
    expect(calls).toContain('delete dev dev');
  });

  it('saves settings with the version it read and keeps the answer', async () => {
    const data = setup();
    await data.open('dev');
    // The core answers the reload that follows with what it now keeps.
    detailAnswer = () => Promise.resolve(detail({ settings: { instructions: 'Be careful.', version: 9 } }));
    await data.updateSettings({ instructions: 'Be careful.' });
    expect(settingsBodies).toEqual([{ instructions: 'Be careful.', version: 8 }]);
    expect(data.settings()?.instructions).toBe('Be careful.');
    expect(data.settings()?.version).toBe(9);
  });

  it('lists the OAuth sign-ins only when OAuth is on, and says when the core can’t', async () => {
    const data = setup();
    detailAnswer = () => Promise.resolve(detail({ settings: { oauthEnabled: true } }));
    await data.open('dev');
    await Promise.resolve();
    await Promise.resolve();
    expect(data.grantsState()).toBe('not-available');
  });

  it('finds which client called a moving connection today, and with which tool', async () => {
    const data = setup();
    await data.open('dev');
    const called = await data.calledToday('billing-api', Date.parse('2026-10-22T14:30:00+02:00'));
    expect([...called]).toEqual([['codex', 'http_request']]);
  });

  it('stops listening when it is destroyed', async () => {
    const data = setup();
    await data.open('dev');
    expect(events.handlers.get('audit.call')?.size).toBe(1);
    expect(events.handlers.get('oauth.grant.changed')?.size).toBe(1);
    data.stop();
    expect(events.handlers.get('audit.call')?.size).toBe(0);
    expect(events.handlers.get('oauth.grant.changed')?.size).toBe(0);
    calls.length = 0;
    events.emit('oauth.grant.changed', { id: 'og_cursor', endpoint: 'dev' });
    await Promise.resolve();
    expect(calls).toEqual([]);
  });
});
