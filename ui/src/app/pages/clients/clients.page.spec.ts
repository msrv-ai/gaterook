import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { ApiError } from '../../core/api/api-error';
import { ClientsApi, ClientsView, ConnectPreview, ConnectRequest } from '../../core/api/clients.api';
import { CoreApi } from '../../core/api/core.api';
import { EndpointSummary, EndpointsApi, TokensApi, ToolGroup, ToolRow } from '../../core/api/endpoints.api';
import { Overview, OverviewApi } from '../../core/api/overview.api';
import { OAuthApi, OAuthGrant, TunnelApi, TunnelState } from '../../core/api/tunnel.api';
import { DesktopBridge } from '../../core/desktop/desktop-bridge';
import { EventStream } from '../../core/events/event-stream';
import { CoreStore } from '../../core/stores/core.store';
import { absoluteTime, clockTime, isoLocal, relativeTime, shortDate } from '../../core/time/time-format';
import { TimeService } from '../../core/time/time.service';
import { DEMO_GRANTS, DEMO_PUBLISH, DEMO_TUNNEL_OFF } from '../../dev/fixtures-clients';
import { Announcer } from '../../kit/announcer';
import { AriaDisabledGuard } from '../../kit/aria-disabled';
import { provideKitIcons } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { SKELETON_DELAY_MS, ClientsPage } from './clients.page';
import { firstRun as firstRunOverview, setup as setupOverview } from '../overview/overview.fixtures';
import { NOW, bridgeDisconnect, codexDisconnect, connected, foundClients, kitClients, kitEndpoints, kitManual, kitTokens, noneFound, previewFor, setupEndpoints } from './clients.fixtures';

/**
 * The Clients page against fake API services in the kit's world (design/pages/clients.html): what it shows, what each
 * control asks the core, where the keyboard goes, and that nothing is written before its exact change is on screen.
 */

// ── The world ─────────────────────────────────────────────────────────────────────────────────────────────────────

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
const never = <T>() => new Promise<T>(() => undefined);

interface World {
  list: () => Promise<ClientsView>;
  preview: (client: string, r: ConnectRequest) => Promise<ConnectPreview>;
  tunnel: () => Promise<TunnelState>;
  grants: () => Promise<OAuthGrant[]>;
  /** `GET /api/overview`: the page's state (a core without it: in use). */
  overview: () => Promise<Overview>;
  endpoints: () => Promise<EndpointSummary[]>;
  tools: (slug: string) => Promise<ToolGroup[]>;
}

let calls: string[];
let world: World;
let events: FakeEvents;
let announced: string[];
const now = signal(NOW);

const fakeTime: Partial<TimeService> = {
  now,
  minute: now,
  relative: (at) => (at ? relativeTime(at, now()) : ''),
  absolute: (at, withSeconds = true) => (at ? absoluteTime(at, withSeconds) : ''),
  iso: (at) => (at ? isoLocal(at) : ''),
  date: (at, year = false) => (at ? shortDate(at, now(), year) : ''),
  clock: (at, withSeconds = false) => (at ? clockTime(at, withSeconds) : ''),
};

function configure(): void {
  calls = [];
  announced = [];
  events = new FakeEvents();
  world = {
    list: () => Promise.resolve(kitClients()),
    preview: (client, r) => Promise.resolve(previewFor(client as 'cursor', r)),
    tunnel: () => Promise.reject(notAvailable()),
    grants: () => Promise.reject(notAvailable()),
    overview: () => Promise.reject(notAvailable()),
    endpoints: () => Promise.resolve(kitEndpoints()),
    tools: () => Promise.reject(notAvailable()),
  };
  const clients: Partial<ClientsApi> = {
    list: () => (calls.push('list'), world.list()),
    scan: () => (calls.push('scan'), world.list()),
    connectPreview: (client, r) => (calls.push(`preview ${client} ${r.endpoint} ${r.method}`), world.preview(client, r)),
    connect: (client, id) => (calls.push(`connect ${client} ${id}`), Promise.resolve(connected(client, { endpoint: 'dev', method: 'token', token: { name: 'cursor-laptop', ttlDays: 7 } }))),
    disconnectPreview: (id) => (calls.push(`disconnect-preview ${id}`), Promise.resolve(id === 'cl_cx_dev' ? codexDisconnect() : bridgeDisconnect(id === 'cl_cd_dev' ? 'dev' : 'incident-prod'))),
    disconnect: (id, previewId) => (calls.push(`disconnect ${id} ${previewId}`), Promise.resolve({ linkId: id, entryRemoved: id === 'cl_cx_dev', rejectedApprovals: 0, notes: [] })),
    removeClaudeBridge: () => (calls.push('remove-bridge'), Promise.resolve({ revoked: [], removedFiles: [], rejectedApprovals: 0, notes: [] })),
    manual: (slug) => (calls.push(`manual ${slug}`), Promise.resolve(kitManual())),
  };
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: '**', children: [] }]),
      provideKitIcons(),
      { provide: ClientsApi, useValue: clients },
      { provide: EndpointsApi, useValue: { list: () => world.endpoints(), tools: (slug: string) => (calls.push(`tools ${slug}`), world.tools(slug)) } },
      { provide: OverviewApi, useValue: { get: () => world.overview() } },
      { provide: TokensApi, useValue: { list: () => Promise.resolve(kitTokens()) } },
      {
        provide: TunnelApi,
        useValue: {
          get: () => world.tunnel(),
          // What Publish would make public, for the endpoint asked about (the kit's stage-readonly, or setup's dev).
          publishPreview: (slug: string) =>
            Promise.resolve(
              slug === DEMO_PUBLISH.endpoint
                ? DEMO_PUBLISH
                : { ...DEMO_PUBLISH, endpoint: slug, publicPaths: [`/mcp/${slug}`, '/oauth/*', '/.well-known/oauth-*'], connections: [{ name: 'dev-db', env: 'dev' }] },
            ),
          publish: (slug: string) => (calls.push(`publish ${slug}`), Promise.resolve({ ...DEMO_TUNNEL_OFF, state: 'starting' })),
          stop: () => (calls.push('stop'), Promise.resolve(DEMO_TUNNEL_OFF)),
        },
      },
      { provide: OAuthApi, useValue: { grants: () => world.grants(), revoke: (id: string) => (calls.push(`revoke ${id}`), Promise.resolve()) } },
      { provide: CoreApi, useValue: { counters: () => Promise.resolve({ approvals: 0, tunnel: 'off' }) } },
      { provide: EventStream, useValue: events },
      { provide: DesktopBridge, useValue: { available: false, openMcpb: () => Promise.resolve(), openClientConfig: () => Promise.resolve() } },
      { provide: CoreStore, useValue: { running: signal(true), instanceChanges: signal(0), address: signal('127.0.0.1:7465') } },
      { provide: TimeService, useValue: fakeTime },
    ],
  });
  TestBed.inject(AriaDisabledGuard).install();
  const announcer = TestBed.inject(Announcer);
  vi.spyOn(announcer, 'announce').mockImplementation((m: string) => void announced.push(m));
}

beforeEach(() => {
  now.set(NOW);
  configure();
});
afterEach(() => (document.body.innerHTML = ''));

// ── Helpers ───────────────────────────────────────────────────────────────────────────────────────────────────────

let fixture: ComponentFixture<ClientsPage>;

async function settle(): Promise<void> {
  for (let i = 0; i < 5; i++) {
    await new Promise((r) => setTimeout(r, 0));
    await fixture.whenStable();
  }
}

async function render(): Promise<HTMLElement> {
  fixture = TestBed.createComponent(ClientsPage);
  document.body.appendChild(fixture.nativeElement);
  await settle();
  return fixture.nativeElement as HTMLElement;
}

const text = (el: Element | null | undefined) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
const $ = <E extends Element = HTMLElement>(sel: string, root: ParentNode = document) => root.querySelector<E>(sel);
const $$ = <E extends Element = HTMLElement>(sel: string, root: ParentNode = document) => [...root.querySelectorAll<E>(sel)];
/** A control by its words (sr-only parts included, as a screen reader reads it). */
const control = (root: ParentNode, words: string) => $$<HTMLElement>('button, a', root).find((b) => text(b) === words || text(b).startsWith(words))!;
/** A client's card, by the name in its title. */
const card = (name: string) => $$('article').find((a) => text($('.gw-card__title', a)) === name)!;
const openDialog = () => $<HTMLDialogElement>('dialog[open]');
const toasts = () => TestBed.inject(ToastService).toasts();

async function click(el: HTMLElement): Promise<void> {
  el.click();
  await settle();
}

async function choose(select: HTMLSelectElement, value: string): Promise<void> {
  select.value = value;
  select.dispatchEvent(new Event('change', { bubbles: true }));
  await settle();
}

// ── Tests ─────────────────────────────────────────────────────────────────────────────────────────────────────────

describe('Clients page: what it shows', () => {
  it('keeps the requested installation card available when no AI app is installed', async () => {
    world.list = () => Promise.resolve(noneFound());
    await TestBed.inject(Router).navigateByUrl('/clients#cl-ag-t');
    const el = await render();
    const card = $('#cl-ag-t', el)!;
    expect(card).not.toBeNull();
    expect(card.querySelector('a[href="https://antigravity.google/"]')).not.toBeNull();
    expect(document.activeElement).toBe($('.gw-card__title', card));
    expect(el.querySelector('#cl-empty-t')).not.toBeNull();
  });

  it('opens the requested welcome-guide app with its Connect review inline and keyboard focus', async () => {
    world.list = () => Promise.resolve(kitClients({ clients: kitClients().clients.map((c) => c.client === 'codex' || c.client === 'cursor'
      ? { ...c, status: 'detected', statusText: 'Not connected', links: [] }
      : c) }));
    await TestBed.inject(Router).navigateByUrl('/clients#cl-cx-t');
    const el = await render();
    const first = $('section[aria-labelledby="cl-local-t"] article', el)!;
    expect(first.id).toBe('cl-cx-t');
    expect(text($('.gw-card__title', first))).toBe('Codex');
    expect(first.hasAttribute('gwInlineConnectCard')).toBe(true);
    expect(document.activeElement).toBe($('.gw-card__title', first));
    expect(calls.some((call) => call.startsWith('connect '))).toBe(false);
  });

  it('the kit’s world: counts, Cursor first with Connect open, the bridge, Codex, Antigravity, claude.ai, manual setup', async () => {
    const el = await render();
    expect(text($('h1', el))).toBe('AI apps');
    expect($$('.gw-page-header__meta .gw-status__label', el).map(text)).toEqual(['2 connected', '1 detected, not connected', '1 not installed']);
    expect(text($('#cl-scanned', el))).toBe('1 min ago');
    expect(text($('section[aria-labelledby="cl-local-t"] .gw-section__description', el))).toContain('127.0.0.1:7465');

    // What is left to do comes first: Cursor, wide, with Connect inline and its own primary.
    const articles = $$('section[aria-labelledby="cl-local-t"] article', el);
    expect(text($('.gw-card__title', articles[0]))).toBe('Cursor');
    const cursor = card('Cursor');
    expect(text(cursor)).toContain('Found on this computer. Pick an endpoint and how Cursor signs in, then check the change; nothing is written until you connect.');
    expect($$('.gw-btn--primary', cursor).map(text)).toEqual(['Connect Cursor']);

    const cd = card('Claude Desktop');
    expect(text(cd)).toContain('A short-lived token per endpoint, issued on the fly and never written to the config');
    const rows = $$('tbody tr', cd);
    expect(rows.map((r) => text($('a', r)))).toEqual(['dev', 'incident-prod']);
    expect($('.gw-env-marker, [class*="env"]', rows[1])).toBeTruthy();
    expect(control(cd, 'Remove bridge from Claude Desktop')).toBeTruthy();

    const codex = card('Codex');
    expect(text(codex)).toContain('Codex asks before write tools too: default_tools_approval_mode = "writes"');
    const entryToggle = control(codex, 'Entry in config.toml of Codex');
    const entry = document.getElementById(entryToggle.getAttribute('aria-controls')!)!;
    expect(entry.hidden).toBe(true);
    await click(entryToggle);
    expect(entry.hidden).toBe(false);
    expect(text(entry)).toContain('[mcp_servers.gateway-dev]');

    const ag = card('Antigravity');
    expect(text(ag)).toContain('Not found on this computer. Install it, then check again.');
    expect(control(ag, 'Check again for Antigravity')).toBeTruthy();

    // Before the tunnel step: the claude.ai card says so and offers nothing.
    const ai = $('article[aria-labelledby="cl-ai-t"]', el)!;
    expect(text($('.gw-status__label', ai))).toBe('Not available yet');
    expect($('.gw-card__footer', ai)).toBeNull();
    expect(control(ai, 'Publish for claude.ai')).toBeUndefined();

    expect($<HTMLInputElement>('#cl-man-url', el)!.value).toBe('http://127.0.0.1:7465/mcp/dev');
    expect($$('[role="tab"]', el).map(text)).toEqual(['MCP Inspector', 'JSON', 'TOML']);
    expect(text($('section[aria-labelledby="cl-manual-t"] .gw-card__footer', el))).toContain('Set up by hand: token inspector-debug gwt_K9xP…, expires in 3 days');
  });

  it('placeholders only when the first answer takes longer than 300 ms', async () => {
    world.list = never;
    vi.useFakeTimers();
    try {
      fixture = TestBed.createComponent(ClientsPage);
      document.body.appendChild(fixture.nativeElement);
      await vi.advanceTimersByTimeAsync(SKELETON_DELAY_MS - 50);
      fixture.detectChanges();
      expect($$('.gw-skeleton')).toHaveLength(0);
      await vi.advanceTimersByTimeAsync(100);
      fixture.detectChanges();
      expect($$('section[aria-labelledby="cl-local-t"] .gw-skeleton').length).toBeGreaterThan(0);
      expect($('section[aria-labelledby="cl-local-t"]')!.getAttribute('aria-busy')).toBe('true');
      expect(text($('section[aria-labelledby="cl-local-t"] [role="status"]'))).toBe('Loading the clients of this computer');
    } finally {
      vi.useRealTimers();
    }
  });

  it('a first load that fails says so, with Try again and the details', async () => {
    world.list = () => Promise.reject(problem(500, { title: 'Internal error', status: 500, detail: 'The database is busy.' }));
    const el = await render();
    const alert = $('section[aria-labelledby="cl-local-t"] .gw-alert--danger', el)!;
    expect(alert.getAttribute('role')).toBe('alert');
    expect(text($('.gw-alert__title', alert))).toBe("The clients of this computer didn't load");
    expect(text($('.gw-alert__body', alert))).toBe('The database is busy.');
    await click(control(alert, 'Details'));
    expect($('#cl-error-details')!.hidden).toBe(false);
    expect(text($('#cl-error-details'))).toContain('GET /api/clients → 500');

    world.list = () => Promise.resolve(kitClients());
    await click(control(alert, 'Try again'));
    expect(card('Cursor')).toBeTruthy();
  });

  it('a reload that fails keeps the cards and says the page may be out of date', async () => {
    await render();
    world.list = () => Promise.reject(problem(503, { title: 'Unavailable', status: 503, detail: 'The core is busy.' }));
    const core = TestBed.inject(CoreStore) as unknown as { instanceChanges: ReturnType<typeof signal<number>> };
    core.instanceChanges.set(1);
    await settle();
    expect(text($('.gw-alert--warning .gw-alert__title'))).toBe('This page may be out of date.');
    expect(card('Codex')).toBeTruthy();
  });

  it('no AI app found: what the scan looks for, Scan again, and Manual setup moves the keyboard there', async () => {
    world.list = () => Promise.resolve(noneFound());
    const el = await render();
    const empty = $('section[aria-labelledby="cl-empty-t"]', el)!;
    expect(text($('.gw-empty__title', empty))).toBe('No AI apps found');
    expect($$('section[aria-labelledby="cl-local-t"] article', el)).toHaveLength(0);

    await click(control(empty, 'Scan again'));
    expect(calls).toContain('scan');
    expect(announced).toEqual(['Scanning this computer for AI apps', 'Scan finished. No AI apps found on this computer.']);

    const link = control(empty, 'Manual setup');
    link.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }));
    await settle();
    expect(document.activeElement?.id).toBe('cl-manual-t');
  });

  it('a core without one-click Connect says so and keeps manual setup', async () => {
    world.list = () => Promise.reject(notAvailable());
    const el = await render();
    expect(text($('section[aria-labelledby="cl-local-t"]', el))).toContain("Connecting apps in one click isn't available in this version of the core yet.");
    expect($('#cl-man-url', el)).toBeTruthy();
  });
});

describe('Clients page: Connect inline', () => {
  it('shows the exact change before anything is written; Connect writes exactly that change', async () => {
    await render();
    expect(calls).toContain('preview cursor dev token');
    const cursor = card('Cursor');
    expect(text(cursor)).toContain('Token cursor-laptop · expires in 7 days · written in plain text to ~/.cursor/mcp.json · Revoke');
    expect(text(cursor)).toContain('7 additions, 1 deletion');
    expect(text(cursor)).toContain('~/.cursor/mcp.json.bak-2026-10-22');
    // The token line is shown once, in the token step.
    expect(text(cursor).match(/written in plain text to/g)).toHaveLength(1);
    expect(calls.some((c) => c.startsWith('connect '))).toBe(false);

    await click(control(cursor, 'Connect Cursor'));
    expect(calls).toContain('connect cursor pv_cursor_dev_token');
    expect(toasts().map((t) => [t.title, t.body])).toContainEqual([
      'Cursor connected to dev',
      'One server added to ~/.cursor/mcp.json with token cursor-laptop, which expires in 7 days. Backup: ~/.cursor/mcp.json.bak-2026-10-22.',
    ]);
  });

  it('a prod endpoint: the sign-in follows before the change is asked for; what prod refuses stays, unavailable', async () => {
    await render();
    const cursor = card('Cursor');
    calls = [];
    await choose($<HTMLSelectElement>('select', cursor)!, 'incident-prod');
    expect(calls).toEqual(['preview cursor incident-prod config-bridge']);

    const radios = $$<HTMLInputElement>('input[type="radio"]', cursor);
    const label = (r: HTMLInputElement) => text(document.getElementById(r.getAttribute('aria-labelledby')!));
    expect(radios.map((r) => [label(r), r.getAttribute('aria-disabled'), r.checked])).toEqual([
      ['Token in the file', 'true', false],
      ['Sign in through the browser', 'true', false],
      ['Through the bridge', null, true],
    ]);
    expect(text(cursor)).toContain('Not on prod endpoints');
    // The endpoint's warning sits under its select, and Connect points at it.
    const warning = 'Endpoint incident-prod reaches prod. Connect it only to the app you use for incidents';
    expect(text(cursor)).toContain(warning);
    const connect = control(cursor, 'Connect Cursor');
    const described = (connect.getAttribute('aria-describedby') ?? '').split(' ').map((id) => text(document.getElementById(id)));
    expect(described.some((d) => d.includes(warning))).toBe(true);
    // The bridge's 15-minute token is on its choice card already: the core's note isn't repeated.
    expect(text(cursor)).not.toContain('No token is written: the bridge gets');

    // A refused choice can't be picked.
    radios[0].click();
    await settle();
    expect(radios[2].checked).toBe(true);
    expect(calls).toEqual(['preview cursor incident-prod config-bridge']);

    // Back on dev the bridge prod forced goes: the endpoint's first sign-in again (kit: "Token in the file").
    await choose($<HTMLSelectElement>('select', cursor)!, 'dev');
    expect(calls.at(-1)).toBe('preview cursor dev token');
    const again = $$<HTMLInputElement>('input[type="radio"]', cursor);
    expect(again.find((r) => r.checked) && label(again.find((r) => r.checked)!)).toBe('Token in the file');
  });

  it('sign-in through the browser offers the "Add to Cursor" link', async () => {
    await render();
    const cursor = card('Cursor');
    const oauth = $$<HTMLInputElement>('input[type="radio"]', cursor)[1];
    oauth.click();
    await settle();
    expect(calls).toContain('preview cursor dev oauth');
    expect(text(cursor)).toContain('Prefer to add it from Cursor? An Add to Cursor link carries only the endpoint URL');
    expect(control(cursor, 'Copy Add to Cursor link')).toBeTruthy();
    expect(text(cursor)).not.toContain('Turns on OAuth sign-in for endpoint dev.');
  });

  it('a change the core refuses is the kit’s "Connect failed": nothing written, Open config, Details', async () => {
    world.preview = () => Promise.reject(problem(422, { title: 'Not editable', status: 422, detail: 'The file isn’t valid JSON (line 7, column 5).', reason: 'invalid', line: 7, column: 5 }));
    await render();
    const cursor = card('Cursor');
    const alert = $('.gw-alert--danger', cursor)!;
    expect(alert.getAttribute('role')).toBe('alert');
    expect(text($('.gw-alert__title', alert))).toBe("Can't connect Cursor: ~/.cursor/mcp.json is not valid JSON");
    expect(text($('.gw-alert__body', alert))).toBe('The file is unchanged. Fix line 7 in Cursor or an editor, then connect again.');
    expect(text(cursor)).not.toContain('What will change');

    // Open config needs the desktop app: unavailable here, with the reason.
    const open = control(alert, 'Open config of Cursor');
    expect(open.getAttribute('aria-disabled')).toBe('true');
    expect(text(document.getElementById(open.getAttribute('aria-describedby')!))).toBe('Opens the file in the desktop app');

    const details = control(alert, 'Details of the error');
    const pane = document.getElementById(details.getAttribute('aria-controls')!)!;
    await click(details);
    expect(pane.hidden).toBe(false);
    expect(text(pane)).toContain('client-config: invalid, nothing written');

    // Connect without a change on screen asks the core again; it never writes blind.
    calls = [];
    await click(control(cursor, 'Connect Cursor'));
    expect(calls).toEqual(['preview cursor dev token']);
  });
});

describe('Clients page: Disconnect and Remove bridge', () => {
  it('Codex: the confirm says what goes and from where, Cancel has the keyboard, Disconnect takes it back', async () => {
    await render();
    const codex = card('Codex');
    const opener = control(codex, 'Disconnect Codex');
    await click(opener);
    expect(calls).toContain('disconnect-preview cl_cx_dev');
    const dialog = openDialog()!;
    expect(text($('.gw-dialog__title', dialog))).toBe('Disconnect Codex?');
    expect(text(dialog)).toContain("The helper's grant codex-laptop");
    expect(text(dialog)).toContain('~/.codex/config.toml.bak-2026-10-22');
    expect(text(document.activeElement)).toBe('Cancel');

    await click(control(dialog, 'Disconnect Codex'));
    expect(calls).toContain('disconnect cl_cx_dev pv_codex_out');
    expect(openDialog()).toBeNull();
    expect(toasts().map((t) => t.title)).toContain('Codex disconnected');
  });

  it('Claude Desktop from one endpoint keeps the other; Remove bridge takes every endpoint', async () => {
    await render();
    const cd = card('Claude Desktop');
    await click(control(cd, 'Disconnect Claude Desktop from dev'));
    const dialog = openDialog()!;
    expect(text($('.gw-dialog__title', dialog))).toBe('Disconnect Claude Desktop from dev?');
    expect(text(dialog)).toContain('The bridge stays installed for incident-prod.');
    await click(control(dialog, 'Disconnect Claude Desktop from dev'));
    expect(calls).toContain('disconnect cl_cd_dev pv_cd_dev_out');
    expect(toasts().at(-1)?.body).toBe('incident-prod still works through the bridge.');

    await click(control(card('Claude Desktop'), 'Remove bridge from Claude Desktop'));
    const remove = openDialog()!;
    expect(text(document.activeElement)).toBe('Cancel');
    expect(text(remove)).toContain('incident-prod');
    await click(control(remove, 'Remove bridge from Claude Desktop'));
    expect(calls).toContain('remove-bridge');
    expect(toasts().at(-1)?.title).toBe('Bridge removed from Claude Desktop');
  });

  it('Check again is busy while it looks and says what it found', async () => {
    await render();
    const check = control(card('Antigravity'), 'Check again for Antigravity');
    await click(check);
    expect(calls).toContain('scan');
    expect(announced).toEqual(['Looking for Antigravity', 'Antigravity is still not installed.']);
  });
});

describe('Clients page: claude.ai', () => {
  beforeEach(() => {
    world.tunnel = () => Promise.resolve(DEMO_TUNNEL_OFF);
    world.grants = () => Promise.resolve(DEMO_GRANTS);
  });

  it('Publish asks first: exactly what becomes public, Cancel has the keyboard', async () => {
    const el = await render();
    const ai = $('article[aria-labelledby="cl-ai-t"]', el)!;
    expect(text($('.gw-status__label', ai))).toBe('Tunnel off');
    expect(text(ai)).toContain('stage-db');
    expect(text(ai)).toContain('19 calls today');
    const publish = control(ai, 'Publish for claude.ai');
    await click(publish);
    const dialog = openDialog()!;
    expect(text($('.gw-dialog__title', dialog))).toBe('Publish stage-readonly to the internet?');
    expect(text(dialog)).toContain('/mcp/stage-readonly, /oauth/*, /.well-known/oauth-*');
    // The core's `local`: its own line for the app, then /mcp/<slug> of the other endpoints.
    expect(text(dialog)).toContain('Stays localThis app\'s UI, the admin API, endpoints dev and incident-prod');
    expect(text(ai)).toContain('This app\'s UI, its admin API, and endpoints dev and incident-prod');
    // The core's cautions the rows don't already say (the quick tunnel's address is the Public URL row).
    expect(text(dialog)).toContain('NoteDynamic client registration is on: any client that reaches the URL can register');
    expect(text(dialog)).not.toContain('Use a named tunnel for a stable hostname');
    expect(text(document.activeElement)).toBe('Cancel');
    expect(calls.some((c) => c.startsWith('publish '))).toBe(false);

    await click(control(dialog, 'Publish stage-readonly'));
    expect(calls).toContain('publish stage-readonly');
    expect(toasts().at(-1)?.title).toBe('stage-readonly is public');
  });

  it('Revoke a sign-in asks first, then cuts it off', async () => {
    const el = await render();
    const ai = $('article[aria-labelledby="cl-ai-t"]', el)!;
    await click(control(ai, 'Revoke'));
    const dialog = openDialog()!;
    expect(text($('.gw-dialog__title', dialog))).toBe('Revoke claude.ai access to stage-readonly?');
    await click(control(dialog, 'Revoke claude.ai access'));
    expect(calls).toContain('revoke og_7Hc2');
    expect(toasts().at(-1)?.title).toBe('claude.ai access to stage-readonly revoked');
  });
});

describe('Clients page: live', () => {
  it('clients.drift reads the cards again', async () => {
    await render();
    calls = [];
    world.list = () => Promise.resolve(kitClients({ clients: kitClients().clients.filter((c) => c.client !== 'codex') }));
    events.emit('clients.drift', { client: 'codex' });
    await settle();
    expect(calls[0]).toBe('list');
    expect($$('article').some((a) => text($('.gw-card__title', a)) === 'Codex')).toBe(false);
    expect($$('.gw-page-header__meta .gw-status__label').map(text)).toEqual(['1 connected', '1 detected, not connected', '1 not installed']);
  });
});

describe('Clients page: first run and setup (kit ?state=first-run | setup)', () => {
  const toolRow = (name: string, mode: ToolRow['mode']): ToolRow => ({
    toolKey: name,
    name,
    annotations: { readOnlyHint: mode === 'READ_ONLY', destructiveHint: mode !== 'READ_ONLY', idempotentHint: false, openWorldHint: false },
    declaredBy: null,
    mode,
    storedMode: mode,
    readOnlyAllowed: mode === 'READ_ONLY',
    reason: null,
    readOnlyConfirmed: false,
    definitionHash: null,
    overrides: null,
    version: 1,
  });
  const never: TunnelState = { ...DEMO_TUNNEL_OFF, endpoint: null, since: null, history: [] };
  const rows = (el: ParentNode) => $$('#cl-rows > li', el);
  const kv = (root: ParentNode) => $$('.gw-kv__row', root).map((r) => [text($('dt', r)), text($('dd', r))]);

  function firstRun(): void {
    world.overview = () => Promise.resolve(firstRunOverview());
    world.list = () => Promise.resolve(foundClients());
    world.endpoints = () => Promise.resolve([]);
    world.tunnel = () => Promise.resolve(never);
    world.grants = () => Promise.resolve([]);
  }

  function setup(): void {
    world.overview = () => Promise.resolve(setupOverview());
    world.list = () => Promise.resolve(foundClients());
    world.endpoints = () => Promise.resolve(setupEndpoints());
    world.tools = () =>
      Promise.resolve([
        { group: 'core', connection: null, env: null, tools: [toolRow('list_connections', 'OFF')] },
        { group: 'dev-db', connection: 'dev-db', env: 'dev', tools: [toolRow('sql_list_schemas', 'READ_ONLY'), toolRow('sql_query', 'READ_ONLY'), toolRow('sql_execute', 'APPROVAL')] },
      ]);
    world.tunnel = () => Promise.resolve(never);
    world.grants = () => Promise.resolve([]);
  }

  /** The page as Overview's links open it: `/clients#cl-cx-t`. */
  async function arrive(fragment: string): Promise<HTMLElement> {
    await TestBed.inject(Router).navigateByUrl(`/clients#${fragment}`);
    return render();
  }

  it('first run: the apps found are quiet rows whose Connect waits for a connection, and says why', async () => {
    firstRun();
    const el = await render();
    expect(text($('.gw-page-header__description', el))).toBe(
      'Claude Desktop, Codex and Cursor are already on this computer. Add a connection first, so they have something to work with. Connect then shows the exact change before anything is written.',
    );
    expect($$('.gw-page-header__meta .gw-status__label', el).map(text)).toEqual(['3 detected, not connected', '1 not installed']);
    // The header's Add connection is the page's only primary; nothing explains Connect yet, nothing to set up by hand.
    expect($$('.gw-btn--primary', el).map((b) => [text(b), b.getAttribute('href')])).toEqual([['Add connection', '/connections/new']]);
    expect($('.gw-how-it-works', el)).toBeNull();
    expect($('#cl-manual-t', el)).toBeNull();
    expect(text($('section[aria-labelledby="cl-local-t"] .gw-section__description', el))).toBe('These apps reach the gateway at 127.0.0.1:7465 directly, once you connect them.');

    expect($('#cl-rows', el)!.getAttribute('aria-label')).toBe('AI apps on this computer');
    expect(rows(el).map((r) => [text($('h3', r)), text($('.gw-client-card__via', r)), text($('.gw-status__label', r))])).toEqual([
      ['Claude Desktop', 'Found on this computer · Bridge extension (.mcpb)', 'Detected, not connected'],
      ['Codex', 'Found on this computer · ~/.codex/config.toml', 'Detected, not connected'],
      ['Cursor', 'Found on this computer · ~/.cursor/mcp.json', 'Detected, not connected'],
      ['Antigravity', 'Not found on this computer · ~/.gemini/config/mcp_config.json', 'Not installed'],
    ]);
    const connect = control($('#cl-cx-row', el)!, 'Connect Codex');
    expect([connect.tagName, connect.getAttribute('aria-disabled'), connect.getAttribute('aria-describedby')]).toEqual(['BUTTON', 'true', 'cl-first-why']);
    expect(text($('#cl-first-why', el))).toBe('Add a connection first: your AI app needs something to work with.');
    await click(connect);
    expect(calls.filter((c) => c.startsWith('preview'))).toEqual([]);
    const ag = $('#cl-ag-row', el)!;
    expect(control(ag, 'How to install Antigravity (opens in your browser)').getAttribute('href')).toBe('https://antigravity.google/');
    expect(control(ag, 'Check again for Antigravity')).toBeTruthy();

    // claude.ai: there is no endpoint to publish yet.
    const ai = $('article[aria-labelledby="cl-ai-t"]', el)!;
    expect(kv($('.gw-card__body', ai)!)).toEqual([['Tunnel', 'Never published · Tunnel settings']]);
    expect(text($('#cl-ai-first-why', ai))).toBe('Add a connection first: there is no endpoint to publish yet.');
    const publish = control(ai, 'Publish for claude.ai');
    expect([publish.getAttribute('aria-disabled'), publish.getAttribute('aria-describedby')]).toEqual(['true', 'cl-ai-first-why']);
  });

  it('first run: Overview’s How to install (#cl-ag-t) puts the keyboard on that app’s row', async () => {
    firstRun();
    await arrive('cl-ag-t');
    await settle();
    expect(document.activeElement?.id).toBe('cl-ag-row-t');
  });

  it('setup: Overview’s Connect (#cl-cx-t) opens Codex’s review on top, heading focused, with what it can use', async () => {
    setup();
    const el = await arrive('cl-cx-t');
    await settle();
    expect(text($('.gw-page-header__description', el))).toBe(
      'Connect the AI app you use, and it can start reading dev-db. You see the exact change before anything is written, and Disconnect takes it back.',
    );
    expect(text($('section[aria-labelledby="cl-local-t"] .gw-section__description', el))).toBe(
      'These apps reach the gateway at 127.0.0.1:7465 directly. The one you connect gets the tools of endpoint dev and nothing else.',
    );
    const review = $('article[aria-labelledby="cl-cx-s-t"]', el)!;
    expect(document.activeElement?.id).toBe('cl-cx-s-t');
    expect(text($('.gw-client-card__flow > p', review))).toBe(
      "Found on this computer. Connect adds one server to Codex's config, with a backup first. Nothing is written until you click Connect Codex.",
    );
    expect(text($('.gw-client-card__step h4', review))).toBe('What Codex can use');
    expect(kv($('.gw-client-card__step', review)!)).toEqual([
      ['Connection', 'dev-db environment dev · PostgreSQL 16'],
      ['Tools', 'sql_list_schemas and sql_query run at once (read only). sql_execute waits for your approval.'],
      ['Endpoint', 'dev · http://127.0.0.1:7465/mcp/dev'],
    ]);
    expect(text($('.gw-client-card__step .gw-text-caption', review))).toBe('dev-db is on endpoint dev, your only endpoint. Anything you add to dev later reaches Codex too.');
    // One endpoint: no select. What will change comes from the core before anything is written; Connect is alone.
    expect($('select', review)).toBeNull();
    expect(calls).toContain('preview codex dev helper');
    expect(calls).toContain('tools dev');
    expect($$('.gw-card__footer .gw-btn', review).map(text)).toEqual(['Connect Codex']);
    // The other apps stay quiet rows, under their own label.
    expect(text($('#cl-other-t', el))).toBe('Other apps on this computer');
    expect($('#cl-rows', el)!.getAttribute('aria-labelledby')).toBe('cl-other-t');
    expect(rows(el).map((r) => text($('h3', r)))).toEqual(['Claude Desktop', 'Cursor', 'Antigravity']);
  });

  it('setup: Connect writes the change; the card stays on top with “Check that it works” and the prompt to copy', async () => {
    setup();
    const el = await arrive('cl-cx-t');
    await settle();
    const codexLinked = foundClients();
    world.list = () =>
      Promise.resolve({
        ...codexLinked,
        clients: codexLinked.clients.map((c) => (c.client === 'codex' ? { ...kitClients().clients.find((k) => k.client === 'codex')!, status: 'needs-restart', statusText: 'Needs restart' } : c)),
      });
    await click(control($('article[aria-labelledby="cl-cx-s-t"]', el)!, 'Connect Codex'));
    await settle();
    expect(calls).toContain('connect codex pv_codex_dev_helper');
    expect($('article[aria-labelledby="cl-cx-s-t"]', el)).toBeNull();
    const codex = card('Codex');
    expect(document.activeElement).toBe($('.gw-card__title', codex));
    const check = $('section[aria-labelledby="cl-cx-check-t"]', el)!;
    expect(text($('h4', check))).toBe('Check that it works');
    expect(text($('label', check))).toBe('After the restart, open a new chat in Codex and ask');
    expect($<HTMLInputElement>('#cl-cx-ask', check)!.value).toBe('Show me the tables in dev-db');
    expect(text($('.gw-text-caption', check))).toBe(
      'Its first request shows up on Overview and in Activity. Reads run at once; if it tries sql_execute, the request waits for you in Approvals.',
    );
    expect(rows(el).map((r) => text($('h3', r)))).toEqual(['Claude Desktop', 'Cursor', 'Antigravity']);
  });

  it('setup: a row’s Connect opens that app’s review, one at a time; manual setup waits behind its toggle', async () => {
    setup();
    const el = await arrive('cl-cx-t');
    await settle();
    await click(control($('#cl-cu-row', el)!, 'Connect Cursor'));
    await settle();
    expect($('article[aria-labelledby="cl-cx-s-t"]', el)).toBeNull();
    expect($('article[aria-labelledby="cl-cu-s-t"]', el)).not.toBeNull();
    expect(document.activeElement?.id).toBe('cl-cu-s-t');
    expect(rows(el).map((r) => text($('h3', r)))).toEqual(['Claude Desktop', 'Codex', 'Antigravity']);

    const toggle = control($('section[aria-labelledby="cl-manual-t"]', el)!, 'Show manual setup');
    const manual = $('#cl-manual-p', el)!;
    expect([toggle.getAttribute('aria-expanded'), manual.hidden]).toEqual(['false', true]);
    await click(toggle);
    expect([toggle.getAttribute('aria-expanded'), manual.hidden]).toEqual(['true', false]);
  });

  it('setup: claude.ai was never published; Publish dev is there, secondary', async () => {
    setup();
    const el = await render();
    const ai = $('article[aria-labelledby="cl-ai-t"]', el)!;
    expect(text($('.gw-card__body > p', ai))).toBe(
      "Publishing gives endpoint dev a public HTTPS address behind three gates: Anthropic IP ranges only, your OK on this computer for each new sign-in, and the endpoint's own modes.",
    );
    expect(kv($('.gw-card__body', ai)!)).toEqual([
      ['Public URL', 'None while the tunnel is off'],
      ['Tunnel', 'Never published · Tunnel settings'],
      ['Signed in', 'No one yet. Each new sign-in waits until you allow it here.'],
    ]);
    const publish = control(ai, 'Publish for claude.ai');
    expect(publish.classList.contains('gw-btn--primary')).toBe(false);
    await click(publish);
    expect(text($('dialog[open] h2'))).toBe('Publish dev to the internet?');
  });

  it('in use: an app’s anchor puts the keyboard on its card', async () => {
    world.tunnel = () => Promise.resolve(DEMO_TUNNEL_OFF);
    await arrive('cl-cd-t');
    await settle();
    expect(document.activeElement).toBe($('.gw-card__title', card('Claude Desktop')));
    expect($('.gw-how-it-works')).not.toBeNull();
  });
});
