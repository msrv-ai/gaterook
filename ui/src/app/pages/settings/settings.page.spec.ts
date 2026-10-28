import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { ApiError } from '../../core/api/api-error';
import { ApprovalDetail, ApprovalItem, ApprovalsApi } from '../../core/api/approvals.api';
import { ClientsApi } from '../../core/api/clients.api';
import { CoreApi } from '../../core/api/core.api';
import { EndpointsApi, TokensApi } from '../../core/api/endpoints.api';
import { ChannelKind, Notifications, NotificationsApi, NotificationsSent } from '../../core/api/notifications.api';
import { OverviewApi } from '../../core/api/overview.api';
import { MasterKeyApi, SecretsApi } from '../../core/api/secrets.api';
import { AuditExportApi, AuditExportDestination, SettingsApi, SettingsSection } from '../../core/api/settings.api';
import { OAuthApi, OAuthGrant, TunnelApi, TunnelState } from '../../core/api/tunnel.api';
import { DesktopBridge } from '../../core/desktop/desktop-bridge';
import { EventStream } from '../../core/events/event-stream';
import { ApprovalsStore } from '../../core/stores/approvals.store';
import { CoreStore } from '../../core/stores/core.store';
import { SettingsStore } from '../../core/stores/settings.store';
import { TunnelStore } from '../../core/stores/tunnel.store';
import { absoluteTime, clockTime, isoLocal, relativeTime, shortDate } from '../../core/time/time-format';
import { TimeService } from '../../core/time/time.service';
import { DEMO_APPROVALS } from '../../dev/fixtures-approvals';
import { AriaDisabledGuard } from '../../kit/aria-disabled';
import { provideKitIcons } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import {
  NOW,
  channel,
  kitBackup,
  kitClients,
  kitEndpoints,
  kitExports,
  kitGrant,
  kitHealth,
  kitInfo,
  kitMasterKey,
  kitNotifications,
  kitSections,
  kitTokens,
  kitTunnel,
} from './settings.fixtures';
import { SKELETON_DELAY_MS, SettingsPage } from './settings.page';

/**
 * The Settings page against fake API services in the kit's world (design/pages/settings.html): what each section
 * shows, what each control asks the core, the save model (applies at once with a toast, or a Save bar), the dialogs
 * and their initial focus, and the "not in this build" state of the areas whose backend step isn't merged.
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

const problem = (status: number, detail: string) => ApiError.fromHttp(new HttpErrorResponse({ status, error: { status, title: 'Refused', detail } }));
const notAvailable = () => ApiError.fromHttp(new HttpErrorResponse({ status: 404, error: { status: 404, error: 'Not Found' } }));
const never = <T>() => new Promise<T>(() => undefined);

interface World {
  sections: () => Promise<{ [name: string]: SettingsSection }>;
  update: (name: string, body: object) => Promise<SettingsSection>;
  notifications: () => Promise<Notifications>;
  tunnel: () => Promise<TunnelState>;
  grants: () => Promise<OAuthGrant[]>;
  exports: () => Promise<AuditExportDestination[]>;
  pending: () => Promise<ApprovalItem[]>;
  approval: (id: string) => Promise<ApprovalDetail>;
  /** What `POST /api/notifications/test` answers. */
  sent: () => Promise<NotificationsSent>;
  /** What `POST /api/audit-export/{id}/run` does. */
  run: (id: string) => Promise<AuditExportDestination>;
}

let calls: string[];
let bodies: Record<string, unknown>;
let world: World;
let events: FakeEvents;
let desktop: {
  available: boolean;
  platform: string;
  saveFile: (purpose: string) => Promise<string | null>;
  revealInFolder: (kind: string) => Promise<void>;
  applyGeneral: (v: object) => Promise<void>;
  resetApplication: (confirmation: string) => Promise<void>;
  checkForUpdates: () => Promise<void>;
  onUpdateState: (h: unknown) => () => void;
  setNativeTheme: (theme: string) => void;
};
const now = signal(NOW);

const fakeTime: Partial<TimeService> = {
  now,
  minute: now,
  secondsUntil: (at: string) => (Date.parse(at) - now()) / 1000,
  relative: (at) => (at ? relativeTime(at, now()) : ''),
  absolute: (at, withSeconds = true) => (at ? absoluteTime(at, withSeconds) : ''),
  iso: (at) => (at ? isoLocal(at) : ''),
  date: (at, year = false) => (at ? shortDate(at, now(), year) : ''),
  clock: (at, withSeconds = false) => (at ? clockTime(at, withSeconds) : ''),
};

function configure(): void {
  calls = [];
  bodies = {};
  events = new FakeEvents();
  desktop = {
    available: false,
    platform: 'web',
    saveFile: () => Promise.resolve(null),
    revealInFolder: (kind) => (calls.push(`reveal ${kind}`), Promise.resolve()),
    applyGeneral: (v) => (calls.push(`apply ${JSON.stringify(v)}`), Promise.resolve()),
    resetApplication: (confirmation) => (calls.push(`reset ${confirmation}`), Promise.resolve()),
    checkForUpdates: () => Promise.resolve(),
    onUpdateState: () => () => undefined,
    setNativeTheme: () => undefined,
  };
  // Until K2, K3 and K5 are merged the core has none of their routes: the default world is today's core.
  world = {
    sections: () => Promise.resolve(kitSections()),
    update: (name, body) => {
      const current = kitSections()[name] ?? { name, values: {}, version: 0, updatedAt: null };
      const { version: _v, reason: _r, ...patch } = body as Record<string, unknown>;
      return Promise.resolve({ ...current, values: { ...current.values, ...patch }, version: current.version + 1 } as SettingsSection);
    },
    notifications: () => Promise.reject(notAvailable()),
    tunnel: () => Promise.reject(notAvailable()),
    grants: () => Promise.reject(notAvailable()),
    exports: () => Promise.reject(notAvailable()),
    pending: () => Promise.resolve([]),
    approval: () => Promise.resolve(DEMO_APPROVALS.sql),
    sent: () =>
      Promise.resolve({
        channels: [
          { channel: 'desktop', state: 'sent', detail: null },
          { channel: 'slack', state: 'sent', detail: null },
          { channel: 'telegram', state: 'off', detail: null },
        ],
      }),
    run: (id) => Promise.resolve(kitExports().find((d) => d.id === id)!),
  };
  const settings: Partial<SettingsApi> = {
    all: () => (calls.push('settings'), world.sections()),
    // Settings › Tunnel (K3) is a section of its own, not in `GET /api/settings`.
    get: (name) =>
      name === 'tunnel'
        ? world.tunnel().then(() => ({ name: 'tunnel', values: { provider: 'cloudflare-quick', ngrokAuthtokenStored: false }, version: 4, updatedAt: null }) as SettingsSection)
        : world.sections().then((s) => s[name]),
    update: (name, body) => (calls.push(`put ${name}`), (bodies[name] = body), world.update(name, body)),
    backup: () => Promise.resolve(kitBackup()),
    backupNow: () => (
      calls.push('backup-db'),
      Promise.resolve({ path: '/Users/you/.gaterook/backups/gateway-2026-10-22-1430.db', file: 'gateway-2026-10-22-1430.db', at: '2026-10-22T12:30:00Z', sizeBytes: 10 })
    ),
  };
  const notifications: Partial<NotificationsApi> = {
    get: () => (calls.push('notifications'), world.notifications()),
    // As K2's NotificationsController: flat fields merge into the channel's config; a Telegram token names its bot;
    // tokens are never part of the answer.
    update: async (kind: ChannelKind, body) => {
      calls.push(`put notify ${kind}`);
      bodies[`notify ${kind}`] = body;
      const { version: _v, reason: _r, confirm: _c, enabled, botToken, appToken: _a, ...fields } = body as unknown as Record<string, unknown>;
      const before = (await world.notifications().catch(() => null))?.channels.find((c) => c.kind === kind);
      const config = { ...(before?.config ?? {}), ...fields, ...(kind === 'telegram' && botToken ? { bot: { id: '7012345678', username: 'acme_gateway_bot' } } : {}) };
      return channel(kind, { enabled: (enabled as boolean | undefined) ?? before?.enabled ?? true, config: config as never, version: 3 });
    },
    sendTest: () => (calls.push('notify test'), world.sent()),
    slackChannels: () => Promise.resolve([{ id: 'C1', name: 'deploy-approvals' }, { id: 'C2', name: 'platform-oncall' }]),
    disconnectSlack: () => (calls.push('disconnect slack'), Promise.resolve()),
  };
  TestBed.configureTestingModule({
    providers: [
      provideRouter([
        { path: 'settings/:section', component: SettingsPage },
        { path: '**', children: [] },
      ]),
      provideKitIcons(),
      { provide: SettingsApi, useValue: settings },
      {
        provide: AuditExportApi,
        useValue: { list: () => (calls.push('exports'), world.exports()), run: (id: string) => (calls.push(`run ${id}`), world.run(id)) } satisfies Partial<AuditExportApi>,
      },
      { provide: CoreApi, useValue: { info: () => Promise.resolve(kitInfo()), health: () => Promise.resolve(kitHealth()) } satisfies Partial<CoreApi> },
      {
        provide: MasterKeyApi,
        useValue: {
          status: () => Promise.resolve(kitMasterKey()),
          backup: (token: string, pass: string | null) => (
            calls.push(`key-backup ${token} ${pass}`),
            Promise.resolve({ path: '/Volumes/Vault/master-keyset-2026-10-22.json', at: '2026-10-22T12:30:00Z', keyIds: [2146530891], passphrase: !!pass, warnings: [] })
          ),
        } satisfies Partial<MasterKeyApi>,
      },
      { provide: SecretsApi, useValue: { list: () => Promise.resolve([{ source: 'local' }, { source: 'vault' }, { source: 'vault' }, { source: 'gcp' }] as never) } },
      { provide: EndpointsApi, useValue: { list: () => Promise.resolve(kitEndpoints()) } satisfies Partial<EndpointsApi> },
      { provide: TokensApi, useValue: { list: () => Promise.resolve(kitTokens()) } satisfies Partial<TokensApi> },
      { provide: ClientsApi, useValue: { list: () => Promise.resolve(kitClients()) } satisfies Partial<ClientsApi> },
      { provide: OverviewApi, useValue: { get: () => Promise.resolve({ stats: { calls24h: 1284 } } as never) } satisfies Partial<OverviewApi> },
      {
        provide: ApprovalsApi,
        useValue: {
          list: () => world.pending().then((items) => ({ status: 'pending', items, nextCursor: null, pending: items.length, counts: {} }) as never),
          get: (id: string) => world.approval(id),
        } satisfies Partial<ApprovalsApi>,
      },
      { provide: NotificationsApi, useValue: notifications },
      {
        provide: TunnelApi,
        useValue: {
          get: () => (calls.push('tunnel'), world.tunnel()),
          publishPreview: (slug: string) =>
            Promise.resolve({ endpoint: slug, publicPaths: [`/mcp/${slug}`, '/oauth/*', '/.well-known/oauth-*'], local: ['The app and its admin API (127.0.0.1:7465)', '/mcp/dev', '/mcp/incident-prod'], connections: [{ name: 'stage-db', env: 'stage' }], risk: 'Risky combination, 3 of 3', refusal: null }),
          publish: (slug: string) => (calls.push(`publish ${slug}`), Promise.resolve(kitTunnel({ state: 'on', publicUrl: `https://gw-acme-7f3k.trycloudflare.com/mcp/${slug}`, host: 'gw-acme-7f3k.trycloudflare.com' }))),
        } satisfies Partial<TunnelApi>,
      },
      { provide: OAuthApi, useValue: { grants: () => world.grants(), revoke: (id: string) => (calls.push(`revoke ${id}`), (world.grants = () => Promise.resolve([])), Promise.resolve()) } satisfies Partial<OAuthApi> },
      { provide: EventStream, useValue: events },
      { provide: DesktopBridge, useFactory: () => desktop },
      { provide: CoreStore, useValue: { running: signal(true), instanceChanges: signal(0) } },
      { provide: ApprovalsStore, useValue: { pendingCount: signal(3) } },
      { provide: SettingsStore, useValue: { put: () => undefined } },
      { provide: TunnelStore, useValue: { refresh: () => Promise.resolve(), stop: () => (calls.push('tunnel stop'), Promise.resolve()) } },
      { provide: TimeService, useValue: fakeTime },
    ],
  });
  TestBed.inject(AriaDisabledGuard).install();
}

beforeEach(() => {
  now.set(NOW);
  configure();
});
afterEach(() => (document.body.innerHTML = ''));

// ── Helpers ───────────────────────────────────────────────────────────────────────────────────────────────────────

let fixture: ComponentFixture<SettingsPage>;

async function settle(): Promise<void> {
  for (let i = 0; i < 5; i++) {
    await new Promise((r) => setTimeout(r, 0));
    await fixture.whenStable();
  }
}

async function render(): Promise<HTMLElement> {
  fixture = TestBed.createComponent(SettingsPage);
  document.body.appendChild(fixture.nativeElement);
  await settle();
  return fixture.nativeElement as HTMLElement;
}

const text = (el: Element | null | undefined) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
const $ = <E extends Element = HTMLElement>(sel: string, root: ParentNode = document) => root.querySelector<E>(sel);
const $$ = <E extends Element = HTMLElement>(sel: string, root: ParentNode = document) => [...root.querySelectorAll<E>(sel)];
/** A control by its visible words (sr-only parts included). */
const control = (root: ParentNode, words: string) => $$<HTMLElement>('button, a', root).find((b) => text(b).startsWith(words))!;
const toasts = () => TestBed.inject(ToastService).toasts().map((t) => `${t.title}${t.body ? ` · ${t.body}` : ''}`);
const reason = (el: HTMLElement) =>
  (el.getAttribute('aria-describedby') ?? '')
    .split(' ')
    .filter(Boolean)
    .map((id) => text(document.getElementById(id)))
    .join(' | ');

async function click(el: HTMLElement): Promise<void> {
  el.click();
  await settle();
}

async function toggle(el: HTMLInputElement): Promise<void> {
  el.click();
  await settle();
}

async function type(el: HTMLInputElement | HTMLSelectElement, value: string): Promise<void> {
  el.value = value;
  el.dispatchEvent(new Event('input', { bubbles: true }));
  if (el instanceof HTMLSelectElement) el.dispatchEvent(new Event('change', { bubbles: true }));
  await settle();
}

async function submit(form: HTMLFormElement | null): Promise<void> {
  form!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
  await settle();
}

const saveBar = (formId: string) => $<HTMLElement>(`.gw-form__actions:has([form="${formId}"])`)!;

// ── Tests ─────────────────────────────────────────────────────────────────────────────────────────────────────────

describe('Settings page: loading, failure, the layout', () => {
  it('nothing for 300 ms, then placeholders, while the sections load', async () => {
    world.sections = never;
    const el = await render();
    expect($('.gw-skeleton', el)).toBeNull();
    await new Promise((r) => setTimeout(r, SKELETON_DELAY_MS + 50));
    await settle();
    expect($$('.gw-skeleton', el).length).toBeGreaterThan(0);
    expect(text($('[role="status"]', el))).toBe('Loading settings');
    expect($('[gwSettingsLayout]', el)).toBeNull();
  });

  it('sections that don’t load: what happened, Try again and the details; Try again loads them', async () => {
    let fail = true;
    world.sections = () => (fail ? Promise.reject(problem(500, 'The settings table is locked.')) : Promise.resolve(kitSections()));
    const el = await render();
    const alert = $('.gw-alert--danger[role="alert"]', el)!;
    expect(text($('.gw-alert__title', alert))).toBe("Settings didn't load");
    expect(text($('.gw-alert__body', alert))).toBe('The settings table is locked.');
    expect($('#settings-error-details', el)!.hidden).toBe(true);
    await click(control(alert, 'Details'));
    expect($('#settings-error-details', el)!.hidden).toBe(false);
    expect(text($('#settings-error-details', el))).toContain('GET /api/settings → 500');

    fail = false;
    await click(control(alert, 'Try again'));
    expect($('#settings-general', el)).not.toBeNull();
  });

  it('the local settings sections in kit order, with the header and Desktop mode', async () => {
    const el = await render();
    expect(text($('h1', el))).toBe('Settings');
    expect(text($('.gw-page-header__meta', el))).toBe('Desktop mode One local user, no sign-in. Team settings show how server mode works.');
    expect($$('.gw-settings-layout__content > .gw-settings-layout__section', el).map((s) => s.id)).toEqual([
      'settings-general',
      'settings-appearance',
      'settings-authorization',
      'settings-users',
      'settings-notifications',
      'settings-approval-rules',
      'settings-local-api',
      'settings-audit',
      'settings-backup',
    ]);
    expect($$('.gw-settings-layout__nav .gw-nav__item', el).map((a) => a.getAttribute('href'))).toContain('/settings/approval-rules');
  });

  it('a failed reload keeps what is on screen and says it may be out of date', async () => {
    const el = await render();
    world.sections = () => Promise.reject(problem(503, 'The core is busy.'));
    // The core came back: everything is read again.
    const core = TestBed.inject(CoreStore) as unknown as { instanceChanges: ReturnType<typeof signal<number>> };
    core.instanceChanges.set(1);
    await settle();
    expect(text($('.gw-alert--warning .gw-alert__title', el))).toBe('This page may be out of date.');
    expect($('#settings-general', el)).not.toBeNull();
  });
});

describe('Settings page: General', () => {
  it('the core as it runs: port, example URL, runtime rows (§8.6.1), files; desktop-only actions say why', async () => {
    const el = await render();
    const g = $('#settings-general', el)!;
    expect($<HTMLInputElement>('#st-port', g)!.value).toBe('7465');
    expect(text(g)).toContain('http://127.0.0.1:7465/mcp/dev');
    expect(text(g)).toContain('Native core');
    expect(text(g)).toContain('Bundled JRE 25, started only for Java plugins');
    expect(text(g)).toContain('/Users/you/.gaterook/logs/core.log');
    const logs = control(g, 'Open logs folder');
    expect(logs.getAttribute('aria-disabled')).toBe('true');
    expect(reason(logs)).toContain('desktop app');
    await click(logs);
    expect(calls).not.toContain('reveal logs');
  });

  it('a switch applies at once: the core and the desktop get it, a toast names it; a refusal puts it back', async () => {
    desktop.available = true;
    const el = await render();
    const login = $<HTMLInputElement>('#st-login', el)!;
    expect(login.checked).toBe(true);
    await toggle(login);
    expect(calls).toContain('put general');
    expect(bodies['general']).toEqual({ openAtLogin: false, version: 3 });
    expect(calls).toContain('apply {"openAtLogin":false}');
    expect(toasts()).toContain('Saved · Start GateRook when you sign in: off.');

    world.update = () => Promise.reject(problem(422, 'startHidden must be a boolean.'));
    const hidden = $<HTMLInputElement>('#st-minimized', el)!;
    await toggle(hidden);
    expect(hidden.checked).toBe(true);
    expect(toasts()).toContain('Start minimized wasn’t saved · startHidden must be a boolean.');
  });

  it('Change port: the field has focus, a bad port says why before anything is sent, a good one restarts the core', async () => {
    desktop.available = true;
    const el = await render();
    const opener = control($('#settings-general', el)!, 'Change port');
    await click(opener);
    const dlg = $<HTMLDialogElement>('#st-port-dlg')!;
    expect(dlg.hasAttribute('open')).toBe(true);
    expect(document.activeElement?.id).toBe('st-port-new');
    expect(text(dlg)).toContain('Codex, from AI apps');
    expect(text(dlg)).toContain('Claude Desktop: its bridge asks GateRook for the address');
    expect(text(dlg)).toContain('The 3 pending approval requests are kept.');

    const input = $<HTMLInputElement>('#st-port-new')!;
    await type(input, '7466');
    await submit($('form', dlg) as HTMLFormElement);
    expect(text($('#st-port-new-err'))).toBe("7466 is reserved by GateRook. Choose another one.");
    expect(calls).not.toContain('put local-api');

    await type(input, '7475');
    expect(text(dlg)).toContain('http://127.0.0.1:7475/mcp/dev, /mcp/stage-readonly and /mcp/incident-prod');
    await submit($('form', dlg) as HTMLFormElement);
    expect(bodies['local-api']).toEqual({ port: 7475, version: 3 });
    expect(dlg.hasAttribute('open')).toBe(false);
    expect(toasts()).toContain('Core restarting on port 7475 · Endpoint URLs now use port 7475. Connect Codex again in AI apps.');
  });

  it('outside the desktop app the port can’t change: the confirm button says why', async () => {
    const el = await render();
    await click(control($('#settings-general', el)!, 'Change port'));
    const confirm = control($('#st-port-dlg')!, 'Change port and restart core');
    expect(confirm.getAttribute('aria-disabled')).toBe('true');
    expect(reason(confirm)).toBe('Only the desktop app can restart the core on another port. Change it from there.');
  });

  it('Reset names the data and consequences, focuses Cancel and returns to its opener without changing anything', async () => {
    desktop.available = true;
    const el = await render();
    const opener = control($('#settings-general', el)!, 'Reset GateRook');
    await click(opener);
    const dlg = $<HTMLDialogElement>('#st-reset-dlg')!;
    expect(dlg.open).toBe(true);
    expect(document.activeElement).toBe(control(dlg, 'Cancel'));
    expect(text(dlg)).toContain('All connections, saved credentials, settings, the local database, backups, activity history and installed plugins will be removed.');
    expect(text(dlg)).toContain('The next launch starts fresh. You will need to reconnect your AI apps.');
    expect(text(dlg)).toContain('Your external databases and services are not changed.');
    await type($<HTMLInputElement>('#st-reset-confirmation')!, 'RESET');
    await click(control(dlg, 'Cancel'));
    expect(dlg.open).toBe(false);
    expect(document.activeElement).toBe(opener);
    expect(calls.some((c) => c.startsWith('reset '))).toBe(false);
    await click(opener);
    expect($<HTMLInputElement>('#st-reset-confirmation')!.value).toBe('');
  });

  it('Reset is unavailable outside the desktop app and explains why', async () => {
    const el = await render();
    const opener = control($('#settings-general', el)!, 'Reset GateRook');
    expect(opener.getAttribute('aria-disabled')).toBe('true');
    expect(reason(opener)).toBe('Reset is available only in the desktop app.');
    await click(opener);
    expect($<HTMLDialogElement>('#st-reset-dlg')!.open).toBe(false);
    expect(calls.some((c) => c.startsWith('reset '))).toBe(false);
  });

  it('Reset requires the exact confirmation and a deliberate button activation; Enter in the field cannot submit', async () => {
    desktop.available = true;
    const el = await render();
    await click(control($('#settings-general', el)!, 'Reset GateRook'));
    const dlg = $<HTMLDialogElement>('#st-reset-dlg')!;
    const input = $<HTMLInputElement>('#st-reset-confirmation')!;
    const confirm = control(dlg, 'Delete all local data') as HTMLButtonElement;
    for (const value of ['', 'reset', ' RESET', 'RESET ']) {
      await type(input, value);
      expect(confirm.getAttribute('aria-disabled')).toBe('true');
      await click(confirm);
    }
    expect(calls.some((c) => c.startsWith('reset '))).toBe(false);
    await type(input, 'RESET');
    expect(confirm.getAttribute('aria-disabled')).toBeNull();
    expect(confirm.type).toBe('button');
    expect(input.form).toBeNull();
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await settle();
    expect(calls.some((c) => c.startsWith('reset '))).toBe(false);
    await click(confirm);
    expect(calls.filter((c) => c.startsWith('reset '))).toEqual(['reset RESET']);
    expect(confirm.getAttribute('aria-busy')).toBe('true');
  });

  it('Reset blocks duplicate requests and dismissal while running, including after success until the app closes', async () => {
    desktop.available = true;
    let finish!: () => void;
    desktop.resetApplication = (confirmation) => {
      calls.push(`reset ${confirmation}`);
      return new Promise<void>((resolve) => (finish = resolve));
    };
    const el = await render();
    await click(control($('#settings-general', el)!, 'Reset GateRook'));
    const dlg = $<HTMLDialogElement>('#st-reset-dlg')!;
    await type($<HTMLInputElement>('#st-reset-confirmation')!, 'RESET');
    const confirm = control(dlg, 'Delete all local data');
    await click(confirm);
    expect($<HTMLInputElement>('#st-reset-confirmation')!.readOnly).toBe(true);
    expect(text($('#st-reset-status'))).toContain('GateRook will close when finished.');
    await click(confirm);
    await click(control(dlg, 'Cancel'));
    await click($<HTMLButtonElement>('button[aria-label="Close"]', dlg)!);
    const escape = new Event('cancel', { cancelable: true });
    dlg.dispatchEvent(escape);
    expect(escape.defaultPrevented).toBe(true);
    expect(dlg.open).toBe(true);
    expect(calls.filter((c) => c.startsWith('reset '))).toEqual(['reset RESET']);
    finish();
    await settle();
    await click(confirm);
    expect(confirm.getAttribute('aria-disabled')).toBe('true');
    expect(calls.filter((c) => c.startsWith('reset '))).toEqual(['reset RESET']);
  });

  it('Reset shows the desktop failure in the dialog and permits retry or cancellation', async () => {
    desktop.available = true;
    desktop.resetApplication = () => Promise.reject(new Error("Error invoking remote method 'gw:resetApplication': Error: Could not stop the core. Try again."));
    const el = await render();
    const opener = control($('#settings-general', el)!, 'Reset GateRook');
    await click(opener);
    const dlg = $<HTMLDialogElement>('#st-reset-dlg')!;
    await type($<HTMLInputElement>('#st-reset-confirmation')!, 'RESET');
    const confirm = control(dlg, 'Delete all local data');
    await click(confirm);
    expect(dlg.open).toBe(true);
    expect(text($('[role="alert"]', dlg))).toBe('Reset did not finishCould not stop the core. Try again.');
    expect(confirm.getAttribute('aria-busy')).toBeNull();
    expect(confirm.getAttribute('aria-disabled')).toBeNull();
    await click(control(dlg, 'Cancel'));
    await click(opener);
    expect($('[role="alert"]', dlg)).toBeNull();
    expect($<HTMLInputElement>('#st-reset-confirmation')!.value).toBe('');
    desktop.resetApplication = (confirmation) => (calls.push(`reset ${confirmation}`), Promise.resolve());
    await type($<HTMLInputElement>('#st-reset-confirmation')!, 'RESET');
    await click(confirm);
    expect(calls).toContain('reset RESET');
  });
});

describe('Settings page: Authorization', () => {
  it('the default token lifetime applies at once; a token that runs out within 7 days is named', async () => {
    const el = await render();
    const a = $('#settings-authorization', el)!;
    expect(text(a)).toContain('Token inspector-debug on endpoint dev expires in 3 days.');
    await type($<HTMLSelectElement>('#st-ttl', a)!, '30');
    expect(bodies['authorization']).toEqual({ tokenTtlDays: 30, version: 3 });
    expect(toasts()).toContain('Saved · Default token lifetime: 30 days.');
  });

  it('OAuth is a Save form: picking DCR warns, the bar appears, Save writes it; a stale version says so', async () => {
    const el = await render();
    expect(saveBar('st-oauth').hidden).toBe(true);
    const dcr = $<HTMLInputElement>('input[value="cimd+dcr"]', el)!;
    await toggle(dcr);
    expect($('#st-reg-dcr-warn', el)).not.toBeNull();
    expect(saveBar('st-oauth').hidden).toBe(false);

    world.update = () => Promise.reject(problem(409, 'stale'));
    await submit($('#st-oauth') as HTMLFormElement);
    expect(text($('#st-oauth [role="alert"]'))).toContain('These settings changed meanwhile, in another window.');
    expect(dcr.checked).toBe(true);

    world.update = (name, body) => Promise.resolve({ ...kitSections()[name], values: { ...kitSections()[name].values, ...(body as object) } } as SettingsSection);
    await submit($('#st-oauth') as HTMLFormElement);
    expect(bodies['authorization']).toEqual({ clientRegistration: 'cimd+dcr', version: 3 });
    expect(toasts()).toContain('OAuth settings saved');
  });

  it('keeps local OAuth settings without public issuer details or tunnel entry points', async () => {
    const el = await render();
    const a = $('#settings-authorization', el)!;
    expect($('#st-oauth', a)).not.toBeNull();
    expect($('#st-issuer', a)).toBeNull();
    expect(text(a)).not.toMatch(/claude\.ai|tunnel|public address/i);
    expect(text(a)).toContain("Sign-ins aren't listed in this build");
  });

  it('Revoke asks first with Cancel focused; the last one gone leaves the empty text with the focus', async () => {
    world.tunnel = () => Promise.resolve(kitTunnel());
    world.grants = () => Promise.resolve([kitGrant(), kitGrant({ id: 'local-grant', client: 'Cursor', ingress: 'LOCAL' })]);
    const el = await render();
    expect(text($('#settings-signed-in', el))).not.toContain('claude.ai');
    expect(text($('#st-grants-count', el))).toBe('1 sign-in');
    await click(control($('#settings-signed-in', el)!, 'Revoke'));
    const dlg = $<HTMLDialogElement>('#st-revoke-ai-dlg')!;
    expect(dlg.hasAttribute('open')).toBe(true);
    expect(text(document.activeElement)).toBe('Cancel');
    await click(control(dlg, 'Revoke Cursor access'));
    expect(calls).toContain('revoke local-grant');
    expect(document.activeElement?.id).toBe('st-grants-empty');
    expect(toasts()).toContain('Cursor access to stage-readonly revoked · Cursor has to sign in again, and you allow it here.');
  });
});

describe('Settings page: Notifications', () => {
  it('without K2: says so, and every control that would act is unavailable with that reason', async () => {
    const el = await render();
    const n = $('#settings-notifications', el)!;
    expect(text($('#st-notif-why', n))).toContain('Not available in this build');
    const test = control(n, 'Send test notification');
    expect(test.getAttribute('aria-disabled')).toBe('true');
    expect(reason(test)).toContain('Not available in this build');
    const desktopSwitch = $<HTMLInputElement>('#st-desktop', n)!;
    expect(desktopSwitch.getAttribute('aria-disabled')).toBe('true');
    await toggle(desktopSwitch);
    expect(calls.filter((c) => c.startsWith('put notify'))).toEqual([]);
    expect(control(n, 'Connect Telegram bot').getAttribute('aria-disabled')).toBe('true');
    await click(test);
    expect(calls).not.toContain('notify test');
  });

  it('with K2: the kit’s world, a channel change applies at once, Send test notification says where it went', async () => {
    world.notifications = () => Promise.resolve(kitNotifications());
    const el = await render();
    const n = $('#settings-notifications', el)!;
    expect(text(n)).toContain('Connected · workspace acme');
    expect($<HTMLInputElement>('#st-slack-to-channel', n)!.checked).toBe(true);
    expect($<HTMLSelectElement>('#st-slack-channel', n)!.selectedOptions[0].textContent).toBe('#deploy-approvals');
    expect(text(n)).toContain('Not set up');

    // K2 takes the changed field flat (NotificationsController.ChannelBody), with the channel's version.
    await toggle($<HTMLInputElement>('#st-sound', n)!);
    expect(bodies['notify desktop']).toEqual({ soundForProd: false, version: 2 });
    expect(toasts()).toContain('Saved · Play a sound for prod requests: off.');
    await toggle($<HTMLInputElement>('#st-desktop', n)!);
    expect(bodies['notify desktop']).toEqual({ enabled: false, version: 3 });

    await click(control(n, 'Send test notification'));
    expect(toasts()).toContain('Test notification sent · Sent to the desktop and #deploy-approvals in Slack. Telegram is not set up.');
  });

  it('Send test notification says what each channel did: a failure is never "sent"', async () => {
    world.notifications = () => Promise.resolve(kitNotifications());
    world.sent = () =>
      Promise.resolve({
        channels: [
          { channel: 'desktop', state: 'sent', detail: null },
          { channel: 'slack', state: 'failed', detail: 'Slack answered channel_not_found.' },
          { channel: 'telegram', state: 'off', detail: null },
        ],
      });
    const el = await render();
    await click(control($('#settings-notifications', el)!, 'Send test notification'));
    expect(toasts()).toContain(
      'The test notification didn’t reach every channel · Sent to the desktop. Slack failed: Slack answered channel_not_found. Telegram is not set up.',
    );
  });

  it('Slack: "Post to a channel" without a saved channel shows the picker first and saves with the channel', async () => {
    const kit = kitNotifications();
    world.notifications = () =>
      Promise.resolve({
        ...kit,
        channels: kit.channels.map((c) => (c.kind === 'slack' ? { ...c, config: { deliverTo: 'dm', workspace: { team: 'acme' } } } : c)),
      });
    const el = await render();
    const n = $('#settings-notifications', el)!;
    expect($<HTMLInputElement>('#st-slack-to-dm', n)!.checked).toBe(true);
    expect($('#st-slack-channel', n)).toBeNull();

    await toggle($<HTMLInputElement>('#st-slack-to-channel', n)!);
    // Nothing saved yet: the core refuses a channel delivery without a channel.
    expect(calls.filter((c) => c === 'put notify slack')).toEqual([]);
    const select = $<HTMLSelectElement>('#st-slack-channel', n)!;
    expect(select.selectedOptions[0].textContent).toBe('Choose a channel');

    select.value = 'C2';
    select.dispatchEvent(new Event('change'));
    await settle();
    expect(bodies['notify slack']).toEqual({ deliverTo: 'channel', channelId: 'C2', version: 2 });
    expect(toasts()).toContain('Saved · Channel: #platform-oncall.');
  });

  it('Disconnect Slack asks first (Cancel focused) and says what changes', async () => {
    world.notifications = () => Promise.resolve(kitNotifications());
    world.pending = () => Promise.resolve([DEMO_APPROVALS.sql, DEMO_APPROVALS.k8s, DEMO_APPROVALS.http] as unknown as ApprovalItem[]);
    const el = await render();
    await click(control($('#settings-notifications', el)!, 'Disconnect Slack'));
    const dlg = $<HTMLDialogElement>('#st-slack-disconnect-dlg')!;
    expect(dlg.hasAttribute('open')).toBe(true);
    expect(text(document.activeElement)).toBe('Cancel');
    expect(text($('#st-slack-disconnect-t'))).toBe('Disconnect Slack workspace acme?');
    expect(text(dlg)).toContain('Requests stop going to #deploy-approvals');
    const rows = $$('.gw-kv__row', dlg).map((r) => `${text($('dt', r))}: ${text($('dd', r))}`);
    expect(rows).toEqual([
      'Still notified by: Desktop notifications',
      'Policies: dev, stage and prod announce in desktop notifications only',
      'Pending requests: 3, still waiting in Approvals. Their Slack messages lose the buttons.',
    ]);
    world.notifications = () => Promise.resolve({ ...kitNotifications(), channels: [channel('desktop'), channel('slack', { enabled: false, state: 'not-set-up' })] });
    await click(control($('footer', dlg)!, 'Disconnect Slack'));
    expect(calls).toContain('disconnect slack');
    expect(toasts()).toContain('Slack disconnected · Approval requests now reach you on the desktop only.');
  });

  it('Connect a Telegram bot: the token has focus, says when it is missing, leaves with the one request, names the bot', async () => {
    world.notifications = () => Promise.resolve(kitNotifications());
    const el = await render();
    const n = $('#settings-notifications', el)!;
    await click(control(n, 'Connect Telegram bot'));
    const dlg = $<HTMLDialogElement>('#st-tg-connect-dlg')!;
    expect(document.activeElement?.id).toBe('st-tg-token');
    // K2 posts to the private chats of linked approvers: there is no chat id to give.
    expect($('#st-tg-chat', dlg)).toBeNull();
    await submit($('form', dlg) as HTMLFormElement);
    expect(text($('#st-tg-token-err'))).toBe('Enter the bot token.');
    expect(calls.filter((c) => c === 'put notify telegram')).toEqual([]);

    const token = $<HTMLInputElement>('#st-tg-token')!;
    await type(token, '7012345678:AAH-secret');
    await submit($('form', dlg) as HTMLFormElement);
    expect(bodies['notify telegram']).toEqual({ enabled: true, botToken: '7012345678:AAH-secret', version: 0 });
    expect(token.value).toBe('');
    expect(dlg.hasAttribute('open')).toBe(false);
    expect(toasts()).toContain('Telegram connected · The bot @acme_gateway_bot checks out. It sends requests to approvers who link their Telegram account.');
    expect(text(n)).toContain('Connected · bot @acme_gateway_bot');
    expect(control(n, 'Change bot')).toBeTruthy();
  });

  it('Connect a Telegram bot with a webhook: the core’s question in the dialog, then the same token with remove-webhook', async () => {
    world.notifications = () => Promise.resolve(kitNotifications());
    const el = await render();
    const api = TestBed.inject(NotificationsApi) as Partial<NotificationsApi>;
    const update = api.update!;
    let asked = 0;
    api.update = (kind, body) => {
      if (kind === 'telegram' && !(body as { confirm?: string }).confirm && asked++ === 0) {
        bodies['notify telegram (first)'] = body;
        return Promise.reject(
          ApiError.fromHttp(
            new HttpErrorResponse({
              status: 422,
              error: { title: 'Confirm first', detail: 'This bot sends updates to a webhook (https://bots.example/hook). Use it here? Its webhook will be removed.', field: '/botToken', confirm: 'remove-webhook' },
            }),
          ),
        );
      }
      return update(kind, body);
    };
    await click(control($('#settings-notifications', el)!, 'Connect Telegram bot'));
    const dlg = $<HTMLDialogElement>('#st-tg-connect-dlg')!;
    const token = $<HTMLInputElement>('#st-tg-token')!;
    await type(token, '7012345678:AAH-secret');
    await submit($('form', dlg) as HTMLFormElement);
    expect(dlg.hasAttribute('open')).toBe(true);
    expect(text(dlg)).toContain('This bot sends updates to a webhook (https://bots.example/hook).');
    expect(token.value).toBe('7012345678:AAH-secret');
    await submit($('form', dlg) as HTMLFormElement);
    expect(bodies['notify telegram']).toEqual({ enabled: true, botToken: '7012345678:AAH-secret', confirm: 'remove-webhook', version: 0 });
    expect(dlg.hasAttribute('open')).toBe(false);
  });

  it('Preview messages: a waiting request in each channel, the first tab focused; none waiting says so', async () => {
    world.pending = () => Promise.resolve([{ ...DEMO_APPROVALS.sql, expiresAt: new Date(NOW + 240_000).toISOString() }] as unknown as ApprovalItem[]);
    const el = await render();
    const opener = control($('#settings-notifications', el)!, 'Preview messages');
    await click(opener);
    const dlg = $<HTMLDialogElement>('#st-preview-dlg')!;
    expect(dlg.hasAttribute('open')).toBe(true);
    expect(document.activeElement?.id).toBe('st-an-tab-desktop');
    expect(text($('#st-preview-d'))).toContain('The pending sql_execute request on dev-db');
    expect($$('#st-an-p-desktop figure', dlg)).toHaveLength(2);
    expect($('#st-an-p-slack', dlg)!.hidden).toBe(true);
    expect(text($('#st-an-p-telegram', dlg))).toContain('Not set up yet. Once you connect a bot');
    await click(control($('footer', dlg)!, 'Close'));
    expect(document.activeElement).toBe(opener);

    world.pending = () => Promise.resolve([]);
    await click(opener);
    expect(text($('#st-preview-d'))).toContain('No request is waiting now.');
    expect(text(document.activeElement)).toBe('Close');
  });
});

describe('Settings page: Approval rules', () => {
  it('the table from the policies, a Save form for where decisions happen and the prod comment', async () => {
    const el = await render();
    const r = $('#settings-approval-rules', el)!;
    expect($$('tbody tr', r).map((tr) => text(tr))).toEqual([
      'dev 5 min You (local user) Optional Edit in Policies: dev approval rules',
      'stage 10 min You (local user) Optional Edit in Policies: stage approval rules',
      'prod 10 min You (local user) Required Edit in Policies: prod approval rules',
    ]);
    expect(control(r, 'Edit in Policies').getAttribute('href')).toBe('/policies/dev#dev-approvals');

    await toggle($<HTMLInputElement>('#st-comment-prod', r)!);
    expect(text($('#st-prod-comment', r))).toBe('Optional');
    expect(saveBar('st-rules').hidden).toBe(false);
    await click(control(saveBar('st-rules'), 'Discard'));
    expect(text($('#st-prod-comment', r))).toBe('Required');
    expect(saveBar('st-rules').hidden).toBe(true);

    await toggle($<HTMLInputElement>('#st-approvers-app', r)!);
    await submit($('#st-rules') as HTMLFormElement);
    expect(bodies['approval-rules']).toEqual({ decisionsFrom: 'app', requireCommentOnProd: true, version: 3 });
    expect(toasts()).toContain('Decisions saved');
  });
});

describe('Settings page: Local API', () => {
  it('allowed origins: checked like the core does, removed with the keyboard kept, saved with a restart note', async () => {
    const el = await render();
    const l = $('#settings-local-api', el)!;
    expect(text(l)).toContain('Checked · only 127.0.0.1:7465 and localhost:7465');
    const input = $<HTMLInputElement>('#st-origin-new', l)!;
    await type(input, 'http://localhost:3000/app');
    await click($<HTMLElement>('#st-origin-add', l)!);
    expect(text($('#st-origin-err', l))).toBe('Enter a scheme, host and port, for example http://localhost:3000. Paths aren’t allowed.');
    await type(input, 'HTTP://localhost:3000/');
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await settle();
    expect($$('#st-origins-list .gw-tag__label', l).map(text)).toEqual(['http://localhost:6274', 'http://localhost:3000']);
    expect(saveBar('st-origins').hidden).toBe(false);

    await click($<HTMLElement>('[aria-label="Remove http://localhost:6274"]', l)!);
    expect(document.activeElement?.getAttribute('aria-label')).toBe('Remove http://localhost:3000');
    await click($<HTMLElement>('[aria-label="Remove http://localhost:3000"]', l)!);
    expect(text($('#st-origins-empty', l))).toContain('No browser origins allowed');
    expect(document.activeElement).toBe(input);

    await submit($('#st-origins') as HTMLFormElement);
    expect(bodies['local-api']).toEqual({ allowedOrigins: [], version: 3 });
    expect(toasts()).toContain('Allowed origins saved · They apply the next time the core starts.');
  });
});

describe('Settings page: Audit & retention', () => {
  it('retention is a Save form with today’s estimate; without K5 the export says it isn’t in this build', async () => {
    const el = await render();
    const a = $('#settings-audit', el)!;
    expect(text($('#st-keep-hint', a))).toContain('1,284 calls in the last 24 hours, a year holds about 470,000 records.');
    await type($<HTMLSelectElement>('#st-keep', a)!, '2');
    expect(text($('#st-keep-hint', a))).toContain('2 years hold about 940,000 records.');
    await submit($('#st-retention') as HTMLFormElement);
    expect(bodies['audit']).toEqual({ retentionYears: 2, version: 3 });
    expect(toasts()).toContain('Retention saved');
    expect(text($('#st-export-why', a))).toContain('Not available in this build');
    expect(control(a, 'Export now').getAttribute('aria-disabled')).toBe('true');
  });

  it('with K5: one card per destination, a failing one with Retry and its error under a disclosure', async () => {
    world.exports = () => Promise.resolve(kitExports());
    const el = await render();
    const a = $('#settings-audit', el)!;
    expect($$('.gw-card__title', a).map(text)).toEqual(['SIEM · syslog', 'Amazon S3', 'BigQuery']);
    expect(text(a)).toContain('Export to BigQuery is failing');
    const details = $('#st-exp-bq-details', a)!;
    expect(details.hidden).toBe(true);
    await click(control(a, 'Show error details'));
    expect(details.hidden).toBe(false);
    expect(text(details)).toContain('403 Forbidden');
    await click(control(a, 'Retry export to BigQuery'));
    expect(calls).toContain('run bq');
    await click(control(a, 'Export now'));
    expect(toasts()).toContain('Export started · Sending records since the last run to SIEM · syslog, Amazon S3 and BigQuery.');
    expect(control(a, 'Edit').getAttribute('aria-disabled')).toBe('true');
  });

  it('Export now runs each destination on its own: the ones that didn’t start are named with the core’s reason', async () => {
    world.exports = () => Promise.resolve(kitExports());
    world.run = (id) =>
      id === 's3'
        ? Promise.reject(ApiError.fromHttp(new HttpErrorResponse({ status: 409, error: { title: 'Export running', detail: 'An export to this destination is running.' } })))
        : Promise.resolve(kitExports().find((d) => d.id === id)!);
    const el = await render();
    await click(control($('#settings-audit', el)!, 'Export now'));
    expect(calls.filter((c) => c.startsWith('run '))).toEqual(['run siem', 'run s3', 'run bq']);
    expect(toasts()).toContain('The export started for SIEM · syslog and BigQuery only · Amazon S3: An export to this destination is running.');
  });

  it('the demo world’s destinations (shown, never called) keep the kit’s layout, but nothing runs them', async () => {
    world.exports = () => Promise.resolve(kitExports().map((d) => ({ ...d, demo: true })));
    const el = await render();
    const a = $('#settings-audit', el)!;
    const retry = control(a, 'Retry export to BigQuery');
    expect(retry.getAttribute('aria-disabled')).toBe('true');
    expect(reason(retry)).toContain('A destination of the demo world: shown, never called.');
    const now = control(a, 'Export now');
    expect(now.getAttribute('aria-disabled')).toBe('true');
    expect(reason(now)).toContain('No destination can run now');
    await click(retry);
    await click(now);
    expect(calls.filter((c) => c.startsWith('run '))).toEqual([]);
  });
});

describe('Settings page: Backup', () => {
  it('the daily copy applies at once; Back up now names the file; the folder opens only from the desktop app', async () => {
    const el = await render();
    const b = $('#settings-backup', el)!;
    expect(text(b)).toContain('gateway-2026-10-22.db');
    expect(text(b)).toContain('8 secrets stored here · 3 secret references don’t need it');
    expect(text(b)).toContain('Copies of /Users/you/.gaterook/ include master.keyset');
    await toggle($<HTMLInputElement>('#st-daily-backup', b)!);
    expect(bodies['backup']).toEqual({ daily: false, version: 3 });
    expect(toasts()).toContain('Saved · Back up the database every day: off.');
    await click(control(b, 'Back up now'));
    expect(toasts()).toContain('Database backed up · Saved as gateway-2026-10-22-1430.db in /Users/you/.gaterook/backups/.');
    expect(control(b, 'Open backup folder').getAttribute('aria-disabled')).toBe('true');
  });

  it('Back up master key: Cancel focused; the save dialog names the file; the passphrase leaves with the request', async () => {
    desktop.available = true;
    desktop.saveFile = () => Promise.resolve('save-token-1');
    const el = await render();
    await click(control($('#settings-backup', el)!, 'Back up master key'));
    const dlg = $<HTMLDialogElement>('#st-backup-key-dlg')!;
    expect(text(document.activeElement)).toBe('Cancel');
    expect(text(dlg)).toContain('Without a copy, the 8 secrets stored here can\'t be recovered if this computer is lost.');
    const pass = $<HTMLInputElement>('#st-backup-pass')!;
    await type(pass, 'correct horse');
    await submit($('form', dlg) as HTMLFormElement);
    expect(calls).toContain('key-backup save-token-1 correct horse');
    expect(pass.value).toBe('');
    expect(dlg.hasAttribute('open')).toBe(false);
    expect(toasts()[0]).toContain('Master key backed up, encrypted with your passphrase');
  });

  it('Back up master key: the dialog names the file the save dialog proposes; main refusing the file says so', async () => {
    desktop.available = true;
    desktop.saveFile = () => Promise.reject(new Error("Error invoking remote method 'gw:saveFile': Error: The core refused the file: choose a file, not a folder."));
    const el = await render();
    await click(control($('#settings-backup', el)!, 'Back up master key'));
    const dlg = $<HTMLDialogElement>('#st-backup-key-dlg')!;
    expect(text(dlg)).toContain(`File namegaterook-master-key-${new Date(NOW).toISOString().slice(0, 10)}.json`);
    await submit($('form', dlg) as HTMLFormElement);
    expect(dlg.hasAttribute('open')).toBe(true);
    expect(text(dlg)).toContain('The core refused the file: choose a file, not a folder.');
    expect(calls.filter((c) => c.startsWith('key-backup'))).toEqual([]);
  });
});

describe('Settings page: routes and live updates', () => {
  it('a link to a section, or an anchor in it, puts the view and the keyboard there; an unknown one goes to General', async () => {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/settings/local-api#st-origins', SettingsPage);
    for (let i = 0; i < 6; i++) {
      await new Promise((r) => setTimeout(r, 0));
      await harness.fixture.whenStable();
    }
    expect(document.activeElement?.id).toBe('st-origins');
    expect($('.gw-settings-layout__nav [aria-current="location"]')?.textContent).toContain('Local API');

    await harness.navigateByUrl('/settings/tunnel#settings-tunnel-callers');
    await new Promise((r) => setTimeout(r, 0));
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/settings/general');

    await harness.navigateByUrl('/settings/nope');
    await new Promise((r) => setTimeout(r, 0));
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/settings/general');
  });

  it('does not expose or load tunnel settings, including on live events', async () => {
    const el = await render();
    expect($('#settings-tunnel', el)).toBeNull();
    expect($('.gw-settings-layout__nav [href="/settings/tunnel"]', el)).toBeNull();
    expect($('.gw-settings-layout__jump option[value="tunnel"]', el)).toBeNull();
    events.emit('tunnel.state', { state: 'on' });
    await settle();
    expect(calls).not.toContain('tunnel');
  });
});
