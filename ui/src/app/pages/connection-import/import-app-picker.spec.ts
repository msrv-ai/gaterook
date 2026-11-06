import { provideHttpClient } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ClientCard, ClientId, ClientLink, ClientsApi, ClientsView, ConnectRequest } from '../../core/api/clients.api';
import { EndpointsApi, TokensApi } from '../../core/api/endpoints.api';
import { OverviewApi } from '../../core/api/overview.api';
import { SettingsApi } from '../../core/api/settings.api';
import { DesktopBridge } from '../../core/desktop/desktop-bridge';
import { EventStream } from '../../core/events/event-stream';
import { TunnelStore } from '../../core/stores/tunnel.store';
import { cursorInstallLink } from '../../kit/elements/client-card/client-view';
import { provideKitIcons } from '../../kit/icons';
import { ClientsData } from '../clients/clients.data';
import { connected, foundClients, kitEndpoints, kitManual, previewFor } from '../clients/clients.fixtures';
import { ImportAppPicker } from './import-app-picker';

const endpoints = kitEndpoints().slice(0, 2);
const first = endpoints[0];
const second = endpoints[1];
const content = (element: Element) => (element.textContent ?? '').replace(/\s+/g, ' ').trim();
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

describe('Import AI app picker', () => {
  let fixture: ComponentFixture<ImportAppPicker>;
  let picker: ImportAppPicker;
  let data: ClientsData;
  let view: ClientsView;
  let clients: { list: ReturnType<typeof vi.fn>; scan: ReturnType<typeof vi.fn>; connectPreview: ReturnType<typeof vi.fn>; connect: ReturnType<typeof vi.fn>; browserSignIn: ReturnType<typeof vi.fn> };
  let desktop: { available: boolean; openExternal: ReturnType<typeof vi.fn>; openMcpb: ReturnType<typeof vi.fn>; openClientConfig: ReturnType<typeof vi.fn> };
  let handlers: Map<string, (data: unknown) => void>;

  beforeEach(async () => {
    view = foundClients();
    handlers = new Map();
    clients = {
      list: vi.fn(async () => view),
      scan: vi.fn(async () => view),
      connectPreview: vi.fn(async (client: ClientId, request: ConnectRequest) => previewFor(client, request)),
      connect: vi.fn(),
      browserSignIn: vi.fn(async () => ({ link: cursorInstallLink(first) })),
    };
    desktop = { available: true, openExternal: vi.fn(async () => undefined), openMcpb: vi.fn(async () => undefined), openClientConfig: vi.fn(async () => undefined) };
    await TestBed.configureTestingModule({ imports: [ImportAppPicker], providers: [
      provideHttpClient(), provideRouter([]), provideKitIcons(), ClientsData,
      { provide: ClientsApi, useValue: { ...clients, manual: async (endpoint: string) => ({ ...kitManual(), endpoint }), disconnectPreview: async () => { throw new Error('No entry preview'); } } },
      { provide: EndpointsApi, useValue: { list: async () => endpoints } },
      { provide: TokensApi, useValue: { list: async () => [] } },
      { provide: OverviewApi, useValue: { get: async () => { throw new Error('Not needed for import'); } } },
      { provide: SettingsApi, useValue: { get: async () => ({ values: { tokenTtlDays: 184 } }) } },
      { provide: TunnelStore, useValue: { available: signal(false), state: signal(null) } },
      { provide: EventStream, useValue: { on: (type: string, handler: (data: unknown) => void) => { handlers.set(type, handler); return () => handlers.delete(type); } } },
      { provide: DesktopBridge, useValue: desktop },
    ] }).compileComponents();
    data = TestBed.inject(ClientsData);
    await data.start();
    fixture = TestBed.createComponent(ImportAppPicker);
    picker = fixture.componentInstance;
    fixture.componentRef.setInput('endpoint', first);
    await settle();
  });

  async function settle(): Promise<void> {
    for (let i = 0; i < 3; i++) {
      fixture.detectChanges();
      await fixture.whenStable();
    }
    fixture.detectChanges();
  }
  async function choose(client: ClientId): Promise<void> { picker.select(client); await settle(); }
  function card(client: ClientId): ClientCard { return view.clients.find(c => c.client === client)!; }
  function setLinks(client: ClientId, links: ClientLink[], status: ClientCard['status'] = 'connected'): void {
    view = { ...view, clients: view.clients.map(c => c.client === client ? { ...c, status, detected: true, links } : c) };
  }
  function link(client: ClientId, endpoint = first.slug, status: ClientLink['status'] = 'connected', method: ClientLink['method'] = 'token'): ClientLink {
    return { ...connected(client, { endpoint, method }).link, status, id: `${client}-${endpoint}-${method}` };
  }
  function button(label: string): HTMLButtonElement {
    return Array.from(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>).find(b => content(b) === label)!;
  }

  it('displays the preserved app selection when its options arrive asynchronously', async () => {
    view = { ...view, clients: [] };
    await data.refresh(); await settle();
    fixture.componentRef.setInput('client', 'cursor'); await settle();
    expect(fixture.nativeElement.querySelector('#import-ai-app')).toBeNull();
    const answer = deferred<ClientsView>();
    clients.list.mockReturnValueOnce(answer.promise);
    const refreshing = data.refresh();
    await settle();
    view = foundClients();
    setLinks('cursor', [link('cursor')]);
    answer.resolve(view); await refreshing; await settle();

    const select = fixture.nativeElement.querySelector('#import-ai-app') as HTMLSelectElement;
    expect(picker.client()).toBe('cursor');
    expect(picker.ready()).toBe(true);
    expect(select.value).toBe('cursor');
    expect(select.selectedOptions[0].textContent).toContain('Cursor · Connected');
    picker.select('codex'); await settle();
    expect(select.value).toBe('codex');
    picker.select(''); await settle();
    expect(select.value).toBe('');
    expect(select.selectedOptions[0].textContent).toBe('Choose an AI app');
  });

  it('requires a connection and shows endpoint-specific status, including observed OAuth sign-ins', async () => {
    setLinks('cursor', [link('cursor', second.slug)]);
    await data.refresh(); await choose('cursor');
    expect(picker.ready()).toBe(false);
    expect(content(fixture.nativeElement)).toContain('Connect an AI app to this endpoint first');
    expect(picker.status(card('cursor'))).toBe('Not connected here');
    expect(button('Connect Cursor')).toBeDefined();

    setLinks('cursor', [link('cursor', second.slug, 'needs-repair'), { ...link('cursor', first.slug, 'connected', 'oauth'), configPath: null, configDisplay: null, entryName: null }], 'error');
    await data.refresh(); await settle();
    expect(picker.ready()).toBe(true);
    expect(picker.status(card('cursor'))).toBe('Connected');
    expect(clients.connect).not.toHaveBeenCalled();
  });

  it('offers app cards that start a connected app or open reviewed connection setup', async () => {
    setLinks('cursor', [link('cursor')]);
    await data.refresh();
    fixture.componentRef.setInput('cardsMode', true);
    fixture.componentRef.setInput('startDisabledReason', 'Choose a repository folder above before starting.');
    const start = vi.fn(); picker.startImport.subscribe(start);
    await settle();
    const root = fixture.nativeElement as HTMLElement;
    const startCursor = root.querySelector<HTMLButtonElement>('[aria-label="Start import with Cursor"]')!;
    expect(root.querySelector('#import-ai-app')).toBeNull();
    expect(startCursor.disabled).toBe(true);
    expect(startCursor.getAttribute('aria-describedby')).toBe('import-app-start-reason');
    picker.startApp(card('cursor'));
    expect(start).not.toHaveBeenCalled();

    fixture.componentRef.setInput('startDisabledReason', null); await settle();
    startCursor.click(); await settle();
    expect(picker.client()).toBe('cursor');
    expect(start).toHaveBeenCalledWith({ client: 'cursor', name: 'Cursor', ready: true });
    expect(clients.connect).not.toHaveBeenCalled();
    root.querySelector<HTMLButtonElement>('[aria-label="Connect Codex"]')!.click(); await settle();
    expect(picker.client()).toBe('codex');
    expect(picker.reviewing()).toBe(true);
    expect(clients.connectPreview).toHaveBeenCalledWith('codex', expect.objectContaining({ endpoint: first.slug }));
    expect(clients.connect).not.toHaveBeenCalled();
  });

  it('keeps Codex selected when Cursor is connected and labels a restart acknowledgement honestly', async () => {
    setLinks('cursor', [link('cursor')]);
    setLinks('codex', [link('codex', first.slug, 'needs-restart', 'helper')], 'needs-restart');
    await data.refresh(); await choose('codex');
    const select = fixture.nativeElement.querySelector('#import-ai-app') as HTMLSelectElement;
    expect(select.value).toBe('codex');
    expect(select.selectedOptions[0].textContent).toContain('Codex · Restart required');
    expect(picker.connected()).toBeNull();
    expect(picker.ready()).toBe(false);
    picker.acknowledge('restart', true); await settle();
    expect(select.selectedOptions[0].textContent).toContain('Codex · Restart confirmed by you');
    expect(select.querySelector('option[value="cursor"]')?.textContent).toContain('Cursor · Connected');
    expect(picker.ready()).toBe(true);
    expect(picker.connected()).toBeNull();
    expect(content(fixture.nativeElement)).toContain('GateRook has not yet observed a new request');
    await choose('cursor');
    expect(picker.connected()?.client).toBe('cursor');
    expect(select.value).toBe('cursor');
  });

  it('coalesces pending status refreshes and stops after an observed connection', async () => {
    setLinks('cursor', [link('cursor', first.slug, 'needs-restart')], 'needs-restart');
    await data.refresh(); await choose('cursor');
    clients.list.mockClear();
    const answer = deferred<ClientsView>(); clients.list.mockReturnValueOnce(answer.promise);
    const firstRefresh = picker.refreshPending();
    const nextRefresh = picker.refreshPending();
    expect(clients.list).toHaveBeenCalledTimes(1);
    setLinks('cursor', [link('cursor')]);
    answer.resolve(view); await Promise.all([firstRefresh, nextRefresh]); await settle();
    expect(picker.connected()?.client).toBe('cursor');
    await picker.refreshPending();
    expect(clients.list).toHaveBeenCalledTimes(1);
    expect(clients.scan).not.toHaveBeenCalled();
    expect(clients.connect).not.toHaveBeenCalled();
  });

  it('refreshes pending handoffs only until the app or endpoint changes or the picker is destroyed', async () => {
    await choose('cursor'); clients.list.mockClear();
    await picker.refreshPending();
    expect(clients.list).not.toHaveBeenCalled();
    picker.pending.set({ client: 'cursor', endpoint: first.slug, method: 'oauth' });
    await picker.refreshPending();
    expect(clients.list).toHaveBeenCalledTimes(1);
    await choose('codex'); await picker.refreshPending();
    expect(clients.list).toHaveBeenCalledTimes(1);
    await choose('claude-desktop');
    picker.pending.set({ client: 'claude-desktop', endpoint: first.slug, method: 'mcpb' });
    await picker.refreshPending();
    expect(clients.list).toHaveBeenCalledTimes(2);
    fixture.componentRef.setInput('endpoint', second); await settle();
    await picker.refreshPending();
    expect(clients.list).toHaveBeenCalledTimes(2);
    picker.pending.set({ client: 'claude-desktop', endpoint: second.slug, method: 'mcpb' });
    fixture.destroy(); await picker.refreshPending();
    expect(clients.list).toHaveBeenCalledTimes(2);
  });

  it('previews the exact selected endpoint and writes only after the inline Connect click', async () => {
    await choose('cursor');
    picker.openReview(); await settle();
    expect(clients.connectPreview).toHaveBeenCalledWith('cursor', expect.objectContaining({ endpoint: first.slug }));
    expect(clients.connect).not.toHaveBeenCalled();
    const preview = data.connectState('cursor').preview!;
    expect(picker.reviewing()).toBe(true);
    expect(data.connectState('cursor').loading).toBe(false);
    clients.connect.mockImplementation(async () => {
      const result = { ...connected('cursor', { endpoint: first.slug, method: preview.method }), link: link('cursor', first.slug, 'needs-restart', preview.method) };
      setLinks('cursor', [result.link], 'needs-restart');
      return result;
    });
    button('Connect Cursor').click();
    await vi.waitFor(() => expect(picker.connecting()).toBe(false));
    await settle();
    expect(clients.connect).toHaveBeenCalledWith('cursor', preview.previewId);
    expect(picker.ready()).toBe(false);
    expect(content(fixture.nativeElement)).toContain('Fully quit and restart Cursor');
    picker.acknowledge('restart', true); await settle();
    expect(picker.ready()).toBe(true);
    expect(content(fixture.nativeElement)).toContain('Restart confirmed by you');
    fixture.componentRef.setInput('endpoint', second); await settle();
    expect(picker.ready()).toBe(false);
    expect(picker.acknowledgedRestart()).toBe(false);
  });

  it('drops old endpoint previews and refuses their confirmation after the source changes', async () => {
    await choose('cursor');
    const old = deferred<ReturnType<typeof previewFor>>();
    clients.connectPreview.mockReturnValueOnce(old.promise);
    picker.openReview(); await settle();
    fixture.componentRef.setInput('endpoint', second); await settle();
    old.resolve(previewFor('cursor', { endpoint: first.slug, method: 'token' }));
    await settle();
    expect(picker.preview()).toBeNull();
    expect(data.connectState('cursor').preview).toBeNull();
    await picker.connect(card('cursor'), 'old-preview', { endpoint: first.slug, method: 'token' });
    expect(clients.connect).not.toHaveBeenCalled();
    picker.openReview(); await settle();
    expect(clients.connectPreview).toHaveBeenLastCalledWith('cursor', expect.objectContaining({ endpoint: second.slug }));
  });

  it('keeps OAuth pending until an observed connection and allows retry after launch failure', async () => {
    await choose('cursor'); picker.openReview(); await settle();
    desktop.openExternal.mockRejectedValueOnce(new Error('Browser did not open'));
    await picker.connect(card('cursor'), null, { endpoint: first.slug, method: 'oauth' }); await settle();
    expect(clients.browserSignIn).toHaveBeenCalledWith('cursor', first.slug);
    expect(clients.connect).not.toHaveBeenCalled();
    expect(desktop.openExternal).toHaveBeenCalledWith(expect.stringContaining('https://cursor.com/install-mcp?'));
    expect(picker.ready()).toBe(false);
    expect(picker.manualAllowed()).toBe(false);
    expect(content(fixture.nativeElement)).toContain('Waiting for Cursor');
    button('Continue setup in Cursor').click(); await settle();
    expect(desktop.openExternal).toHaveBeenCalledTimes(2);
    setLinks('cursor', [{ ...link('cursor', first.slug, 'connected', 'oauth'), configPath: null, entryName: null }]);
    handlers.get('oauth.grant.changed')?.({}); await settle();
    expect(picker.ready()).toBe(true);
  });

  it('keeps extension installation pending and reopens only its managed link', async () => {
    await choose('claude-desktop'); picker.openReview(); await settle();
    const preview = data.connectState('claude-desktop').preview!;
    clients.connect.mockImplementation(async () => {
      const result = { ...connected('claude-desktop', { endpoint: first.slug, method: 'mcpb' }), link: link('claude-desktop', first.slug, 'needs-restart', 'mcpb') };
      setLinks('claude-desktop', [result.link], 'needs-restart');
      return result;
    });
    desktop.openMcpb.mockRejectedValueOnce(new Error('Claude Desktop did not open'));
    await picker.connect(card('claude-desktop'), preview.previewId, { endpoint: first.slug, method: 'mcpb' }); await settle();
    expect(desktop.openMcpb).toHaveBeenCalledWith(`claude-desktop-${first.slug}-mcpb`);
    expect(picker.ready()).toBe(false);
    picker.acknowledge('restart', true); await settle();
    expect(picker.ready()).toBe(false);
    button('Continue setup in Claude Desktop').click(); await settle();
    expect(desktop.openMcpb).toHaveBeenCalledTimes(2);
  });

  it('requires explicit manual confirmation for the exact app and live endpoint URL', async () => {
    await choose('cursor');
    picker.manual.set(true); await settle();
    expect(content(fixture.nativeElement)).toContain(first.url);
    expect(picker.ready()).toBe(false);
    picker.acknowledge('manual', true); await settle();
    expect(picker.ready()).toBe(true);
    expect(content(fixture.nativeElement)).toContain('Manually confirmed by you');
    expect(clients.connect).not.toHaveBeenCalled();
    fixture.componentRef.setInput('endpoint', { ...first, url: 'http://127.0.0.1:18475/mcp/dev' }); await settle();
    expect(picker.ready()).toBe(false);
    picker.acknowledge('manual', true); await settle();
    await choose('codex');
    expect(picker.ready()).toBe(false);
  });

  it('blocks stale cached connections while refresh fails or is pending', async () => {
    setLinks('cursor', [link('cursor')]); await data.refresh(); await choose('cursor');
    expect(picker.ready()).toBe(true);
    const answer = deferred<ClientsView>(); clients.list.mockReturnValueOnce(answer.promise);
    const refresh = picker.refresh(); await settle();
    expect(picker.ready()).toBe(false);
    answer.reject(new Error('Core unavailable')); await refresh; await settle();
    expect(picker.ready()).toBe(false);
    expect(content(fixture.nativeElement)).toContain('AI app connections could not be checked');
    await picker.refresh(); await settle();
    expect(picker.ready()).toBe(true);
  });

  it('freezes app selection while Connect is pending and ignores late results for another endpoint', async () => {
    await choose('cursor'); picker.openReview(); await settle();
    const preview = data.connectState('cursor').preview!;
    const answer = deferred<ReturnType<typeof connected>>(); clients.connect.mockReturnValueOnce(answer.promise);
    const connecting = picker.connect(card('cursor'), preview.previewId, { endpoint: first.slug, method: preview.method }); await settle();
    expect(fixture.nativeElement.querySelector('#import-ai-app').disabled).toBe(true);
    picker.select('codex'); expect(picker.client()).toBe('cursor');
    fixture.componentRef.setInput('endpoint', second); await settle();
    answer.resolve(connected('cursor', { endpoint: first.slug, method: preview.method }));
    await connecting; await settle();
    expect(picker.ready()).toBe(false);
    expect(desktop.openExternal).not.toHaveBeenCalled();
    expect(desktop.openMcpb).not.toHaveBeenCalled();
  });

  it('rescans installed apps only on request and keeps a failed scan from authorizing handoff', async () => {
    await choose('antigravity');
    expect(content(fixture.nativeElement)).toContain('How to install Antigravity');
    expect(clients.scan).not.toHaveBeenCalled();
    view = { ...view, clients: view.clients.map(c => c.client === 'antigravity' ? { ...c, detected: true } : c) };
    await picker.refresh(); await settle();
    expect(clients.scan).toHaveBeenCalledTimes(1);
    expect(button('Connect Antigravity')).toBeDefined();
    picker.acknowledge('manual', true); await settle();
    expect(picker.ready()).toBe(true);
    clients.scan.mockRejectedValueOnce(new Error('Scan failed'));
    await picker.refresh(); await settle();
    expect(picker.ready()).toBe(false);
    expect(content(fixture.nativeElement)).toContain('Scan failed');
  });

  it('waits for the authoritative endpoint URL before any manual confirmation or app setup', async () => {
    setLinks('cursor', [link('cursor')]); await data.refresh(); await choose('cursor');
    fixture.componentRef.setInput('endpoint', { ...first, url: '' }); await settle();
    expect(picker.checking()).toBe(true);
    expect(picker.ready()).toBe(false);
    picker.openReview();
    expect(picker.reviewing()).toBe(false);
    fixture.componentRef.setInput('endpoint', { ...first, url: 'http://127.0.0.1:18475/mcp/dev' }); await settle();
    expect(picker.ready()).toBe(true);
  });
});
