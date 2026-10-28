import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ApiError } from '../../core/api/api-error';
import { ClientsApi, ClientsView, ConnectPreview, ConnectRequest } from '../../core/api/clients.api';
import { CoreApi } from '../../core/api/core.api';
import { EndpointsApi, TokensApi, ToolGroup } from '../../core/api/endpoints.api';
import { Overview, OverviewApi } from '../../core/api/overview.api';
import { OAuthApi, OAuthGrant, PublishPreview, TunnelApi, TunnelState } from '../../core/api/tunnel.api';
import { EventStream } from '../../core/events/event-stream';
import { TimeService } from '../../core/time/time.service';
import { DEMO_CLIENTS, DEMO_GRANTS, DEMO_PUBLISH, DEMO_TUNNEL_OFF } from '../../dev/fixtures-clients';
import { CALLS_THROTTLE_MS, ClientsData } from './clients.data';
import { firstRun as firstRunOverview, overview as inUseOverview, setup as setupOverview } from '../overview/overview.fixtures';
import { NOW, codexDisconnect, connected, foundClients, kitClients, kitEndpoints, kitManual, kitTokens, previewFor, setupEndpoints } from './clients.fixtures';

/** The Clients page's data against fake API services: what loads, what each action asks the core, what it keeps. */

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

const problem = (status: number, body: object) => ApiError.fromHttp(new HttpErrorResponse({ status, error: body }));
const notAvailable = () => ApiError.fromHttp(new HttpErrorResponse({ status: 404, error: { status: 404, error: 'Not Found' } }));
const flush = async () => {
  for (let i = 0; i < 6; i++) await new Promise((r) => setTimeout(r, 0));
};

let calls: string[];
let events: FakeEvents;
let answers: {
  list: () => Promise<ClientsView>;
  preview: (client: string, r: ConnectRequest) => Promise<ConnectPreview>;
  connect: () => Promise<unknown>;
  tunnel: () => Promise<TunnelState>;
  grants: () => Promise<OAuthGrant[]>;
  publishPreview: () => Promise<PublishPreview>;
  overview: () => Promise<Overview>;
  endpoints: () => ReturnType<EndpointsApi['list']>;
  tools: () => Promise<ToolGroup[]>;
};

function setup(): ClientsData {
  calls = [];
  events = new FakeEvents();
  answers = {
    list: () => Promise.resolve(kitClients()),
    preview: (client, r) => Promise.resolve(previewFor(client as 'cursor', r)),
    connect: () => Promise.resolve(connected('cursor', { endpoint: 'dev', method: 'token', token: { name: 'cursor-laptop', ttlDays: 7 } })),
    tunnel: () => Promise.reject(notAvailable()),
    grants: () => Promise.reject(notAvailable()),
    publishPreview: () => Promise.resolve(DEMO_PUBLISH),
    overview: () => Promise.reject(notAvailable()),
    endpoints: () => Promise.resolve(kitEndpoints()),
    tools: () => Promise.resolve([]),
  };
  const clients: Partial<ClientsApi> = {
    list: () => (calls.push('list'), answers.list()),
    scan: () => (calls.push('scan'), answers.list()),
    connectPreview: (client, r) => (calls.push(`preview ${client} ${r.endpoint} ${r.method}`), answers.preview(client, r)),
    connect: (client, id) => (calls.push(`connect ${client} ${id}`), answers.connect() as ReturnType<ClientsApi['connect']>),
    disconnectPreview: (id) => (calls.push(`disconnect-preview ${id}`), Promise.resolve(id === 'cl_cx_dev' ? codexDisconnect() : { ...codexDisconnect(), files: [] })),
    disconnect: (id, previewId) => (calls.push(`disconnect ${id} ${previewId}`), Promise.resolve({ linkId: id, entryRemoved: true, rejectedApprovals: 0, notes: [] })),
    removeClaudeBridge: () => (calls.push('remove-bridge'), Promise.resolve({ revoked: [], removedFiles: [], rejectedApprovals: 0, notes: [] })),
    manual: (slug) => (calls.push(`manual ${slug}`), Promise.resolve(kitManual())),
  };
  TestBed.configureTestingModule({
    providers: [
      ClientsData,
      { provide: ClientsApi, useValue: clients },
      {
        provide: EndpointsApi,
        useValue: { list: () => (calls.push('endpoints'), answers.endpoints()), tools: (slug: string) => (calls.push(`tools ${slug}`), answers.tools()) },
      },
      { provide: OverviewApi, useValue: { get: () => (calls.push('overview'), answers.overview()) } },
      { provide: TokensApi, useValue: { list: (slug: string) => (calls.push(`tokens ${slug}`), Promise.resolve(kitTokens())) } },
      {
        provide: TunnelApi,
        useValue: {
          get: () => (calls.push('tunnel'), answers.tunnel()),
          publishPreview: (slug: string) => (calls.push(`publish-preview ${slug}`), answers.publishPreview()),
          publish: (slug: string) => (calls.push(`publish ${slug}`), Promise.resolve({ ...DEMO_TUNNEL_OFF, state: 'starting' })),
          stop: () => (calls.push('stop'), Promise.resolve(DEMO_TUNNEL_OFF)),
        },
      },
      { provide: OAuthApi, useValue: { grants: () => (calls.push('grants'), answers.grants()), revoke: (id: string) => (calls.push(`revoke ${id}`), Promise.resolve()) } },
      { provide: CoreApi, useValue: { counters: () => Promise.resolve({ approvals: 0, tunnel: 'off' }) } },
      { provide: EventStream, useValue: events },
      { provide: TimeService, useValue: { now: signal(NOW), minute: signal(NOW) } },
    ],
  });
  return TestBed.inject(ClientsData);
}

describe('ClientsData: loading', () => {
  it('loads the cards, the endpoints, Codex’s entry and the manual setup; remote access stays unloaded', async () => {
    const data = setup();
    await data.start();
    await flush();
    expect(data.loaded()).toBe(true);
    expect(data.cards().map((c) => c.client)).toEqual(['claude-desktop', 'codex', 'cursor', 'antigravity']);
    expect(data.endpointList().map((e) => e.slug)).toEqual(['dev', 'stage-readonly', 'incident-prod']);
    // Codex's entry, read once from its disconnect preview (a preview writes nothing); the bridge has no entry.
    expect(calls.filter((c) => c.startsWith('disconnect-preview'))).toEqual(['disconnect-preview cl_cx_dev']);
    expect(data.entries().get('cl_cx_dev')?.text).toContain('[mcp_servers.gaterook-dev]');
    expect(calls).toContain('manual dev');
    expect(calls).toContain('tokens dev');
    expect(data.manual().value?.url).toBe('http://127.0.0.1:7465/mcp/dev');
    expect(data.tunnel()).toBeNull();
    expect(data.grants().value).toBeNull();
    expect(data.publishPreview()).toBeNull();
    expect(calls.some((c) => c.startsWith('publish-preview'))).toBe(false);
    expect(data.stale()).toBeNull();
  });

  it('a first load that fails is the part’s error; a reload that fails keeps the data and says it may be out of date', async () => {
    const data = setup();
    answers.list = () => Promise.reject(problem(500, { title: 'Internal error', status: 500, detail: 'The database is busy.' }));
    await data.start();
    expect(data.loaded()).toBe(true);
    expect(data.clients().error).toBeTruthy();
    expect(data.cards()).toEqual([]);

    answers.list = () => Promise.resolve(kitClients());
    await data.refresh();
    expect(data.cards()).toHaveLength(4);
    answers.list = () => Promise.reject(problem(503, { title: 'Unavailable', status: 503, detail: 'The core is busy.' }));
    await data.refresh();
    expect(data.cards()).toHaveLength(4);
    expect((data.stale() as ApiError).detail).toBe('The core is busy.');
  });

  it('coalesces reloads: one in flight and one more after it', async () => {
    const data = setup();
    await data.start();
    calls = [];
    const a = data.refresh();
    const b = data.refresh();
    const c = data.refresh();
    await Promise.all([a, b, c]);
    await flush();
    expect(calls.filter((x) => x === 'list')).toHaveLength(2);
  });

  it('reads the cards again on clients.drift (with Codex’s entry) and, at most every 5 s, on calls', async () => {
    const data = setup();
    await data.start();
    await flush();
    calls = [];
    events.emit('clients.drift', {});
    await flush();
    expect(calls).toEqual(['list', 'disconnect-preview cl_cx_dev']);

    vi.useFakeTimers();
    try {
      calls = [];
      for (let i = 0; i < 5; i++) events.emit('audit.call', { endpoint: 'dev' });
      await vi.advanceTimersByTimeAsync(0);
      expect(calls).toEqual(['list']);
      calls = [];
      events.emit('audit.call', {});
      events.emit('audit.call', {});
      await vi.advanceTimersByTimeAsync(CALLS_THROTTLE_MS - 100);
      expect(calls).toEqual([]);
      await vi.advanceTimersByTimeAsync(200);
      // The trailing reload shows the newest call; a call never re-reads the entry.
      expect(calls).toEqual(['list']);
    } finally {
      vi.useRealTimers();
    }
    data.stop();
  });
});

describe('ClientsData: Connect', () => {
  it('keeps only the newest selection’s preview', async () => {
    const data = setup();
    await data.start();
    const cursor = DEMO_CLIENTS.cursor;
    let release!: () => void;
    const slow = new Promise<void>((r) => (release = r));
    answers.preview = async (client, r) => {
      if (r.method === 'token') await slow;
      return previewFor(client as 'cursor', r);
    };
    const first = data.preview(cursor, { endpoint: 'dev', method: 'token', token: { name: 'cursor-laptop', ttlDays: 7 } });
    await data.preview(cursor, { endpoint: 'dev', method: 'oauth', grantLabel: null });
    release();
    await first;
    expect(data.connectState('cursor').preview?.method).toBe('oauth');
    expect(data.connectState('cursor').loading).toBe(false);
  });

  it('a refused preview is the card’s failure; a refused write is marked as the write’s', async () => {
    const data = setup();
    await data.start();
    const cursor = DEMO_CLIENTS.cursor;
    answers.preview = () => Promise.reject(problem(422, { title: 'Not editable', status: 422, detail: 'Not valid.', reason: 'invalid', line: 7, column: 5 }));
    await data.preview(cursor, { endpoint: 'dev', method: 'token', token: { name: 'cursor-laptop', ttlDays: 7 } });
    const s = data.connectState('cursor');
    expect(s.preview).toBeNull();
    expect(s.written).toBe(false);
    expect(s.failure?.title.map((p) => p.text).join('')).toBe("Can't connect Cursor: ~/.cursor/mcp.json is not valid JSON");

    answers.preview = (client, r) => Promise.resolve(previewFor(client as 'cursor', r));
    await data.preview(cursor, { endpoint: 'dev', method: 'token', token: { name: 'cursor-laptop', ttlDays: 7 } });
    answers.connect = () => Promise.reject(problem(409, { title: 'The file changed', status: 409, detail: '~/.cursor/mcp.json changed after the preview.' }));
    expect(await data.connect(cursor, 'pv_cursor_dev_token')).toBeNull();
    expect(data.connectState('cursor').written).toBe(true);
    expect(data.connectState('cursor').failure?.title.map((p) => p.text).join('')).toBe("Can't connect Cursor: ~/.cursor/mcp.json changed since you checked it");
  });

  it('a write that succeeds starts Connect over and reads the cards again', async () => {
    const data = setup();
    await data.start();
    const cursor = DEMO_CLIENTS.cursor;
    await data.preview(cursor, { endpoint: 'dev', method: 'token', token: { name: 'cursor-laptop', ttlDays: 7 } });
    calls = [];
    const done = await data.connect(cursor, 'pv_cursor_dev_token');
    expect(done?.result.link.tokenName).toBe('cursor-laptop');
    expect(done?.preview?.previewId).toBe('pv_cursor_dev_token');
    expect(calls.slice(0, 2)).toEqual(['connect cursor pv_cursor_dev_token', 'list']);
    expect(data.connectState('cursor')).toEqual({ preview: null, loading: false, failure: null, written: false });
  });

  it('Add endpoint previews and writes one more extension in one go', async () => {
    const data = setup();
    await data.start();
    calls = [];
    await data.addEndpoint(DEMO_CLIENTS.claudeDesktop, 'stage-readonly');
    expect(calls.slice(0, 3)).toEqual(['preview claude-desktop stage-readonly mcpb', 'connect claude-desktop pv_claude-desktop_stage-readonly_mcpb', 'list']);
  });
});

describe('ClientsData: Disconnect, Remove bridge, Scan', () => {
  it('asks for the reverse edit first, then disconnects with it', async () => {
    const data = setup();
    await data.start();
    const link = DEMO_CLIENTS.codex.links[0];
    await data.prepareDisconnect(link);
    expect(data.disconnectPreviewOf('codex')?.previewId).toBe('pv_codex_out');
    calls = [];
    const r = await data.disconnect(link, 'pv_codex_out');
    expect(r.entryRemoved).toBe(true);
    expect(calls.slice(0, 2)).toEqual(['disconnect cl_cx_dev pv_codex_out', 'list']);
    calls = [];
    await data.removeBridge();
    expect(calls.slice(0, 2)).toEqual(['remove-bridge', 'list']);
  });

  it('Scan says what is scanning while it runs and replaces the cards with its answer', async () => {
    const data = setup();
    await data.start();
    answers.list = () => Promise.resolve(kitClients({ scannedAt: '2026-10-22T14:30:00+02:00' }));
    const scan = data.scan('antigravity');
    expect(data.scanning()).toBe('antigravity');
    await scan;
    expect(data.scanning()).toBeNull();
    expect(data.clients().value?.scannedAt).toBe('2026-10-22T14:30:00+02:00');
  });
});

describe('ClientsData: local access', () => {
  it('does not load remote access on startup, refresh or tunnel events', async () => {
    const data = setup();
    answers.tunnel = () => Promise.resolve(DEMO_TUNNEL_OFF);
    answers.grants = () => Promise.resolve(DEMO_GRANTS);
    await data.start();
    await data.refresh();
    events.emit('tunnel.state', { state: 'on' });
    await flush();
    expect(data.tunnel()).toBeNull();
    expect(data.grants().value).toBeNull();
    expect(data.publishPreview()).toBeNull();
    expect(calls).not.toContain('tunnel');
    expect(calls).not.toContain('grants');
    expect(calls.some((c) => c.startsWith('publish-preview'))).toBe(false);
  });
});

describe('ClientsData: the page’s state, as on Overview', () => {
  it('is in use when the core has no overview (or it failed), unknown until it answered', async () => {
    const data = setup();
    expect(data.stage()).toBeNull();
    await data.start();
    await flush();
    expect(data.stage()).toBe('in-use');
    expect(data.first()).toBeNull();
    expect(calls.some((c) => c.startsWith('tools'))).toBe(false);
  });

  it('first run with no connection, in use once an AI app is connected', async () => {
    const data = setup();
    answers.overview = () => Promise.resolve(firstRunOverview());
    answers.list = () => Promise.resolve(foundClients());
    answers.endpoints = () => Promise.resolve([]);
    await data.start();
    await flush();
    expect(data.stage()).toBe('first-run');
    expect(data.published()).toBeNull();

    answers.overview = () => Promise.resolve(inUseOverview());
    await data.refresh();
    expect(data.stage()).toBe('in-use');
  });

  it('setup: the first connection and its endpoint and tools', async () => {
    const data = setup();
    answers.overview = () => Promise.resolve(setupOverview());
    answers.list = () => Promise.resolve(foundClients());
    answers.endpoints = () => Promise.resolve(setupEndpoints());
    answers.tunnel = () => Promise.resolve({ ...DEMO_TUNNEL_OFF, endpoint: null, history: [] });
    answers.tools = () => Promise.resolve([{ group: 'dev-db', connection: 'dev-db', env: 'dev', tools: [] }]);
    await data.start();
    await flush();
    expect(data.stage()).toBe('setup');
    expect(data.first()).toEqual({ name: 'dev-db', env: 'dev', type: 'postgres', typeName: 'PostgreSQL 16', endpoint: 'dev' });
    expect(calls).toContain('tools dev');
    expect(data.firstTools()).toEqual([{ group: 'dev-db', connection: 'dev-db', env: 'dev', tools: [] }]);
    expect(data.published()).toBe('dev');
    expect(calls).not.toContain('publish-preview dev');
  });
});
