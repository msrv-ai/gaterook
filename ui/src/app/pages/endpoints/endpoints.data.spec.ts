import { TestBed } from '@angular/core/testing';
import { ApiError } from '../../core/api/api-error';
import { ConnectionList, ConnectionsApi } from '../../core/api/connections.api';
import { EndpointSummary, EndpointsApi, TokenRow, TokensApi } from '../../core/api/endpoints.api';
import { Template, TemplatesApi } from '../../core/api/templates.api';
import { OAuthApi, OAuthGrant } from '../../core/api/tunnel.api';
import { EventStream } from '../../core/events/event-stream';
import { EndpointsData } from './endpoints.data';
import { at, kitConnections, kitEndpoints, kitTokens } from './endpoints.fixtures';

/** The page's data service against fake admin API services: what it reads, when, and how it patches the list. */

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

const notAvailable = () => new ApiError('not-available', 404, {});
const flush = async () => {
  for (let i = 0; i < 6; i++) await new Promise((r) => setTimeout(r, 0));
};

let calls: string[];
let events: FakeEvents;
let listAnswer: () => Promise<EndpointSummary[]>;
let tokensAnswer: (slug: string) => Promise<TokenRow[]>;
let connectionsAnswer: () => Promise<ConnectionList>;
let oauthAnswer: () => Promise<OAuthGrant[]>;
let templatesAnswer: () => Promise<Template[]>;
let api: Partial<Record<keyof EndpointsApi, (...a: never[]) => unknown>>;

function setup(): EndpointsData {
  calls = [];
  events = new FakeEvents();
  listAnswer = () => Promise.resolve(kitEndpoints());
  tokensAnswer = (slug) => Promise.resolve(kitTokens()[slug] ?? []);
  connectionsAnswer = () => Promise.resolve({ items: kitConnections(), facets: { health: {}, env: {}, type: {} }, total: 12 });
  oauthAnswer = () => Promise.reject(notAvailable());
  templatesAnswer = () => Promise.resolve([]);
  const row = (slug: string, over: Partial<EndpointSummary> = {}) => ({ ...kitEndpoints().find((e) => e.slug === slug)!, ...over });
  api = {
    list: () => {
      calls.push('list');
      return listAnswer();
    },
    create: (r: { slug: string; startFrom: string }) => {
      calls.push(`create ${r.slug} ${r.startFrom}`);
      return Promise.resolve({ ...row('dev'), slug: r.slug });
    },
    duplicate: (slug: string, newSlug: string) => {
      calls.push(`duplicate ${slug} ${newSlug}`);
      return Promise.resolve({ ...row(slug), slug: newSlug });
    },
    disable: (slug: string) => {
      calls.push(`disable ${slug}`);
      return Promise.resolve(row(slug, { status: 'disabled' }));
    },
    enable: (slug: string) => {
      calls.push(`enable ${slug}`);
      return Promise.resolve(row(slug));
    },
    delete: (slug: string, confirm: string) => {
      calls.push(`delete ${slug} ${confirm}`);
      return Promise.resolve({ clients: [], tokensRevoked: ['inspector-debug'], rejectedApprovals: 3 });
    },
    confirmIntegrity: (slug: string, body: { confirm?: string }) => {
      calls.push(`keep ${slug} ${body.confirm ?? '-'}`);
      return Promise.resolve(row(slug));
    },
    restoreRisk: (slug: string) => {
      calls.push(`restore ${slug}`);
      return Promise.resolve(row(slug).risk);
    },
  };
  TestBed.configureTestingModule({
    providers: [
      EndpointsData,
      { provide: EndpointsApi, useValue: api },
      {
        provide: TokensApi,
        useValue: {
          list: (slug: string) => {
            calls.push(`tokens ${slug}`);
            return tokensAnswer(slug);
          },
        },
      },
      {
        provide: ConnectionsApi,
        useValue: {
          list: () => {
            calls.push('connections');
            return connectionsAnswer();
          },
        },
      },
      { provide: OAuthApi, useValue: { grants: () => oauthAnswer() } },
      {
        provide: TemplatesApi,
        useValue: {
          list: (kind: string) => {
            calls.push(`templates ${kind}`);
            return templatesAnswer();
          },
        },
      },
      { provide: EventStream, useValue: events },
    ],
  });
  return TestBed.inject(EndpointsData);
}

describe('EndpointsData', () => {
  it('loads the list with what the cards add to it', async () => {
    const data = setup();
    templatesAnswer = () =>
      Promise.resolve<Template[]>([
        { id: 't', kind: 'ssh-runbook', name: 'r', connection: 'prod-vm', version: 1, status: 'active', catalogRef: null, natures: { read: 1, mutation: 1 }, updatedAt: at(20, '10:00:00'), verified: true },
        { id: 'u', kind: 'ssh-runbook', name: 'off', connection: 'prod-vm', version: 1, status: 'disabled', catalogRef: null, natures: { read: 4 }, updatedAt: at(20, '10:00:00'), verified: true },
      ]);
    await data.start();
    expect(data.rows()?.map((r) => r.slug)).toEqual(['dev', 'stage-readonly', 'incident-prod']);
    expect(calls).toEqual(['list', 'connections', 'tokens dev', 'tokens stage-readonly', 'tokens incident-prod', 'templates ssh-runbook']);
    expect(data.connections()).toHaveLength(12);
    expect(data.tokens().get('dev')).toHaveLength(5);
    expect(data.runbooks()?.get('prod-vm')).toBe(2);
    // OAuth sign-ins come with K3: until then the core has no such route, and the cards count none.
    expect(data.oauth()).toBeNull();
    expect(data.oauthSignIns('stage-readonly')).toBe(0);
    expect(data.error()).toBeNull();
  });

  it('counts the OAuth sign-ins of each endpoint once the core has them', async () => {
    const data = setup();
    const grant = (endpoint: string, state: OAuthGrant['state']): OAuthGrant => ({
      id: `og_${endpoint}_${state}`, client: 'claude.ai', endpoint, ingress: 'TUNNEL', createdAt: at(22, '08:52:00'), expiresAt: at(29, '08:52:00'), lastUsedAt: null, state,
    });
    oauthAnswer = () => Promise.resolve([grant('stage-readonly', 'active'), grant('stage-readonly', 'revoked'), grant('dev', 'active')]);
    await data.start();
    expect(data.oauthSignIns('stage-readonly')).toBe(1);
    expect(data.oauthSignIns('incident-prod')).toBe(0);
  });

  it('shows the list when the parts around it don’t answer', async () => {
    const data = setup();
    connectionsAnswer = () => Promise.reject(new ApiError('server', 500, {}));
    tokensAnswer = (slug) => (slug === 'dev' ? Promise.reject(new ApiError('server', 500, {})) : Promise.resolve([]));
    templatesAnswer = () => Promise.reject(notAvailable());
    await data.start();
    expect(data.rows()).toHaveLength(3);
    expect(data.connections()).toBeNull();
    expect(data.tokens().has('dev')).toBe(false);
    expect(data.tokens().has('incident-prod')).toBe(true);
    expect(data.runbooks()).toBeNull();
  });

  it('tells a failed first load from a failed reload', async () => {
    const data = setup();
    const boom = new ApiError('server', 500, { title: 'Server error', detail: 'database is locked' });
    listAnswer = () => Promise.reject(boom);
    await data.start();
    expect(data.rows()).toBeNull();
    expect(data.error()).toBe(boom);

    listAnswer = () => Promise.resolve(kitEndpoints());
    await data.refresh();
    expect(data.error()).toBeNull();
    expect(data.rows()).toHaveLength(3);

    listAnswer = () => Promise.reject(boom);
    await data.refresh();
    expect(data.stale()).toBe(boom);
    expect(data.rows()).toHaveLength(3);
  });

  it('re-reads only the counters on approvals and the tunnel, everything on a client link', async () => {
    const data = setup();
    await data.start();
    calls = [];
    events.emit('approval.requested', { id: 'ap_1', endpoint: 'dev' });
    await flush();
    expect(calls).toEqual(['list']);

    calls = [];
    events.emit('tunnel.state', { state: 'on' });
    await flush();
    expect(calls).toEqual(['list']);

    calls = [];
    events.emit('clients.drift', { client: 'codex' });
    await flush();
    expect(calls).toContain('connections');
    expect(calls).toContain('tokens dev');
  });

  it('coalesces reloads: one in flight, one after it at the deepest depth asked for', async () => {
    const data = setup();
    await data.start();
    calls = [];
    const first = deferred<EndpointSummary[]>();
    listAnswer = () => first.promise;
    const a = data.refresh('list');
    void data.refresh('list');
    void data.refresh('full');
    void data.refresh('list');
    expect(calls).toEqual(['list']);
    listAnswer = () => Promise.resolve(kitEndpoints());
    first.resolve(kitEndpoints());
    await a;
    await flush();
    // One more, a full one.
    expect(calls.filter((c) => c === 'list')).toHaveLength(2);
    expect(calls.filter((c) => c === 'connections')).toHaveLength(1);
  });

  it('patches a connection’s health as the core reports it', async () => {
    const data = setup();
    await data.start();
    events.emit('connection.health', { name: 'billing-api', env: 'stage', state: 'healthy', latencyMs: 80, message: null, checkedAt: at(22, '14:31:00') });
    const billing = data.connections()!.find((c) => c.name === 'billing-api')!;
    expect(billing.health).toEqual({ state: 'healthy', latencyMs: 80, message: null, checkedAt: at(22, '14:31:00') });
  });

  it('disables and enables at once with the core’s row, one action at a time', async () => {
    const data = setup();
    await data.start();
    const going = data.disable('dev');
    expect(data.busy().has('dev')).toBe(true);
    await going;
    expect(data.busy().has('dev')).toBe(false);
    expect(data.rows()!.find((r) => r.slug === 'dev')!.status).toBe('disabled');
    await data.enable('dev');
    expect(data.rows()!.find((r) => r.slug === 'dev')!.status).toBe('running');
    expect(calls).toContain('disable dev');
    expect(calls).toContain('enable dev');
  });

  it('shows a copy right after its source, and removes a deleted card at once', async () => {
    const data = setup();
    await data.start();
    listAnswer = () => new Promise(() => {}); // the reload never answers: the patch alone shows the change
    await data.duplicate('dev', 'dev-copy');
    expect(data.rows()!.map((r) => r.slug)).toEqual(['dev', 'dev-copy', 'stage-readonly', 'incident-prod']);

    const result = await data.remove('stage-readonly');
    expect(calls).toContain('delete stage-readonly stage-readonly');
    expect(result.rejectedApprovals).toBe(3);
    expect(data.rows()!.map((r) => r.slug)).toEqual(['dev', 'dev-copy', 'incident-prod']);
  });

  it('keeps a row changed outside GateRook, with the confirmation the core asked for', async () => {
    const data = setup();
    listAnswer = () => Promise.resolve(kitEndpoints().map((e) => (e.slug === 'dev' ? { ...e, blockedReason: 'endpoint row' } : e)));
    await data.start();
    await data.keep('dev', null);
    expect(calls).toContain('keep dev -');
    expect(data.rows()!.find((r) => r.slug === 'dev')!.blockedReason).toBeNull();
    await data.keep('dev', 'reset-invalid');
    expect(calls).toContain('keep dev reset-invalid');
  });

  it('creates through the core and shows a dismissed warning again', async () => {
    const data = setup();
    await data.start();
    await data.create('payments-dev', null, 'read-only-dev');
    expect(calls).toContain('create payments-dev read-only-dev');
    await data.restoreRisk('dev');
    expect(calls).toContain('restore dev');
  });

  it('stops listening when the page goes', async () => {
    const data = setup();
    await data.start();
    expect(events.count()).toBe(6);
    data.stop();
    expect(events.count()).toBe(0);
  });
});
