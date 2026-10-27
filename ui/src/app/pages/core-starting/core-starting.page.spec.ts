import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { ApiError } from '../../core/api/api-error';
import { CoreApi, Health } from '../../core/api/core.api';
import { MasterKeyApi, MasterKeyStatus } from '../../core/api/secrets.api';
import { DesktopBridge, DesktopCoreState } from '../../core/desktop/desktop-bridge';
import { ApprovalsStore } from '../../core/stores/approvals.store';
import { CoreStore } from '../../core/stores/core.store';
import { EndpointsStore } from '../../core/stores/endpoints.store';
import { TunnelStore } from '../../core/stores/tunnel.store';
import { absoluteTime, clockTime, relativeTime } from '../../core/time/time-format';
import { TimeService } from '../../core/time/time.service';
import { AriaDisabledGuard } from '../../kit/aria-disabled';
import { provideKitIcons } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { CoreStartingPage } from './core-starting.page';

/**
 * The core-starting page with the real CoreStore, fed the way Electron's main feeds it (`onCoreState`: progress lines,
 * the ready line, a failed start, an exit) and a fake core behind `/api/health` and `/api/master-key`: which card
 * shows, what each action asks of main or the core, where the keyboard goes, and that a passphrase never stays.
 */

const NOW = Date.parse('2026-10-22T14:30:00+02:00');
const KEY = '/home/alex/.mcp-gateway/master.keyset';
const LOG = '/home/alex/.mcp-gateway/logs/core.log';
const never = <T>() => new Promise<T>(() => undefined);
const problem = (status: number, body: object) => ApiError.fromHttp(new HttpErrorResponse({ status, error: body }));

class FakeDesktop {
  available = true;
  platform = 'linux' as const;
  calls: string[] = [];
  freePort: number | null = 7475;
  openFileToken: string | null = 'open-token-1';
  usePortResult: (port: number) => Promise<void> = () => Promise.resolve();
  restartResult: () => Promise<void> = () => Promise.resolve();
  private listener: ((s: DesktopCoreState) => void) | null = null;

  onCoreState(cb: (s: DesktopCoreState) => void): () => void {
    this.listener = cb;
    return () => (this.listener = null);
  }
  emit(s: DesktopCoreState): void {
    this.listener?.(s);
  }
  onDeepLink = () => () => undefined;
  onEvent = () => null;
  setNativeTheme(): void {}
  restartCore(): Promise<void> {
    this.calls.push('restart');
    return this.restartResult();
  }
  usePort(port: number): Promise<void> {
    this.calls.push(`usePort ${port}`);
    return this.usePortResult(port);
  }
  findFreePort(from: number): Promise<number | null> {
    this.calls.push(`findFreePort ${from}`);
    return Promise.resolve(this.freePort);
  }
  quit(): void {
    this.calls.push('quit');
  }
  openFile(purpose: string): Promise<string | null> {
    this.calls.push(`openFile ${purpose}`);
    return Promise.resolve(this.openFileToken);
  }
  revealInFolder(kind: string): Promise<void> {
    this.calls.push(`reveal ${kind}`);
    return Promise.resolve();
  }
}

const health = (p: Partial<Health>): Health => ({
  status: 'ok',
  locked: null,
  version: '0.4.0',
  uptimeMs: 60_000,
  ports: { local: 7465, public: 7466 },
  runtime: 'native',
  db: { path: '', sqlite: '', journal: '', schema: '', sizeBytes: 0 },
  masterKey: { protection: 'keychain', problems: [], warnings: [] },
  tunnel: { state: 'off', host: null },
  warnings: [],
  ...p,
});

const masterKey = (): MasterKeyStatus => ({
  path: KEY,
  mode: '0400',
  owner: 'alex',
  keys: [],
  osProtection: 'keychain',
  protects: 8,
  lastBackupAt: '2026-10-10T14:30:00+02:00',
  problems: ['key-missing'],
  warnings: [],
  locked: 'MASTER_KEY_MISSING',
  rotation: { state: 'idle', done: 0, total: 0, targetKeyId: null, error: null },
});

const KIT_LIVE: DesktopCoreState = {
  kind: 'starting',
  port: 7465,
  publicPort: 7466,
  version: '0.4.0',
  steps: [
    { step: 'runtime', state: 'done', ms: 140 },
    { step: 'master-key', state: 'done', ms: 12 },
    { step: 'db-migrations', state: 'done', ms: 412 },
    { step: 'local-api', state: 'running' },
  ],
};

const PORT_IN_USE: DesktopCoreState = {
  kind: 'failed',
  reason: 'PORT_IN_USE',
  detail: 'Address already in use: 127.0.0.1:7465',
  portOwner: { pid: 4242, user: 'alex' },
  log: LOG,
  logTail: null,
  exitCode: 2,
  port: 7465,
  publicPort: 7466,
  version: '0.4.0',
};

let desktop: FakeDesktop;
let keyCalls: string[];
let tunnelState: ReturnType<typeof signal<unknown>>;
let world: {
  key: () => MasterKeyStatus;
  health: () => Promise<Health>;
  restore: (token: string, passphrase: string | null) => Promise<{ secretsReadable: number }>;
  create: () => Promise<{ needValue: string[] }>;
};
let fixture: ComponentFixture<CoreStartingPage>;

const fakeTime: Partial<TimeService> = {
  now: signal(NOW),
  minute: signal(NOW),
  relative: (at) => (at ? relativeTime(at, NOW) : ''),
  absolute: (at, withSeconds = true) => (at ? absoluteTime(at, withSeconds) : ''),
  clock: (at, withSeconds = false) => (at ? clockTime(at, withSeconds) : ''),
};

function configure(): void {
  desktop = new FakeDesktop();
  keyCalls = [];
  tunnelState = signal<unknown>({ provider: 'cloudflare-named', host: 'gw.acme.dev' });
  world = {
    key: masterKey,
    health: () => never(),
    restore: () => Promise.resolve({ secretsReadable: 8 }),
    create: () => Promise.resolve({ needValue: ['app-logs/token', 'dev-db/password'] }),
  };
  const core: Partial<CoreApi> = {
    health: () => world.health(),
    info: () => never(),
  };
  const keys: Partial<MasterKeyApi> = {
    status: () => (keyCalls.push('status'), Promise.resolve(world.key())),
    restore: (token, passphrase) => (keyCalls.push(`restore ${token} ${passphrase ?? null}`), world.restore(token, passphrase ?? null)),
    create: () => (keyCalls.push('create'), world.create()),
  };
  TestBed.configureTestingModule({
    imports: [CoreStartingPage],
    providers: [
      provideRouter([{ path: '**', children: [] }]),
      provideHttpClient(),
      provideKitIcons(),
      { provide: DesktopBridge, useFactory: () => desktop },
      { provide: CoreApi, useValue: core },
      { provide: MasterKeyApi, useValue: keys },
      { provide: ApprovalsStore, useValue: { pendingCount: signal(3) } },
      { provide: EndpointsStore, useValue: { list: signal([{ slug: 'dev' }, { slug: 'stage-readonly' }, { slug: 'incident-prod' }]) } },
      { provide: TunnelStore, useValue: { state: tunnelState } },
      { provide: TimeService, useValue: fakeTime },
    ],
  });
}

async function render(): Promise<HTMLElement> {
  TestBed.inject(AriaDisabledGuard);
  fixture = TestBed.createComponent(CoreStartingPage);
  document.body.appendChild(fixture.nativeElement);
  TestBed.inject(CoreStore).start();
  await settle();
  return fixture.nativeElement as HTMLElement;
}

async function settle(): Promise<void> {
  for (let i = 0; i < 3; i++) {
    await new Promise((r) => setTimeout(r));
    await fixture.whenStable();
  }
}

async function emit(s: DesktopCoreState): Promise<void> {
  desktop.emit(s);
  await settle();
}

const $ = <T extends Element = HTMLElement>(sel: string) => document.querySelector<T>(sel);
const text = (el: Element | null) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
/** Each text node apart, as a screen reads blocks: "Exit code 3 Reason …" (textContent glues "Exit code3"). */
const words = (el: Element | null) => {
  if (!el) return '';
  const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
  const parts: string[] = [];
  for (let n = walker.nextNode(); n; n = walker.nextNode()) parts.push(n.textContent ?? '');
  return parts.join(' ').replace(/\s+/g, ' ').trim();
};
const button = (label: string, scope: ParentNode = document) =>
  [...scope.querySelectorAll<HTMLButtonElement>('button')].find((b) => text(b) === label) ?? null;

async function type(input: HTMLInputElement, value: string): Promise<void> {
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
  await settle();
}

beforeEach(configure);
afterEach(() => {
  fixture?.destroy();
  document.body.replaceChildren();
  TestBed.inject(ToastService).clear();
});

describe('starting', () => {
  it('shows the kit’s live state from the progress lines: three steps done, Local API binding', async () => {
    const page = await render();
    await emit(KIT_LIVE);
    const main = page.querySelector('gw-status-screen')!;
    expect(main.getAttribute('role')).toBe('main');
    expect(main.getAttribute('data-state')).toBe('starting');
    expect(text(document.getElementById(main.getAttribute('aria-labelledby')!))).toBe('Starting the gateway core');
    expect(text(page.querySelector('.gw-status-screen__text p'))).toBe(
      'Starting the native core and the local API on 127.0.0.1:7465. This usually takes about a second.',
    );
    const steps = [...page.querySelectorAll('.gw-stepper__step')];
    expect(steps.map((s) => s.getAttribute('data-state'))).toEqual(['complete', 'complete', 'complete', 'current']);
    expect(steps.map((s) => text(s))).toEqual([
      'Native core, completed Native image · 0.1 s',
      'Master key, completed Loaded ~/.mcp-gateway/master.keyset',
      'Database migrations, completed Up to date · 0.4 s',
      'Local API, in progress Binding 127.0.0.1:7465',
    ]);
    expect(steps[3].getAttribute('aria-current')).toBe('step');
    expect(text(page.querySelector('[role=status]'))).toBe('Starting the gateway core: local api, step 4 of 4.');
    expect(text(page.querySelector('.gw-status-screen__meta'))).toBe('GateRook 0.4.0 · core on 127.0.0.1:7465 · log ~/.mcp-gateway/logs/core.log');
  });

  it('names the port Electron starts the core on, not the default', async () => {
    const page = await render();
    await emit({ kind: 'starting', steps: [], port: 7475, publicPort: 7466 });
    expect(text(page.querySelector('.gw-status-screen__text p'))).toContain('127.0.0.1:7475');
    expect(text(page.querySelector('.gw-stepper__step[data-state=current]'))).toContain('Native core');
  });
});

describe('port in use', () => {
  it('says who holds the port and how to find it; Quit asks main', async () => {
    const page = await render();
    await emit(PORT_IN_USE);
    expect(text(page.querySelector('h1'))).toBe('Port 7465 is already in use');
    expect(text(page.querySelector('.gw-status-screen__text p'))).toContain('Another process (PID 4242, user alex) is listening on 127.0.0.1:7465');
    expect(text(page.querySelector('.gw-status-screen__text p'))).toContain('clients with the URL in their config, such as Codex and Cursor, need to be connected again');
    const failed = page.querySelector('.gw-stepper__step[data-state=error]')!;
    expect(text(failed)).toBe('Local API, failed Address already in use: 127.0.0.1:7465');
    expect(failed.getAttribute('aria-current')).toBe('step');
    expect(text(page.querySelector('.gw-code-block'))).toContain('lsof -nP -iTCP:7465 -sTCP:LISTEN');
    expect(text(page.querySelector('[role=alert]'))).toBe('The core failed to start: port 7465 is already in use.');
    expect(text(page.querySelector('.gw-status-screen__meta'))).toBe(`GateRook 0.4.0 · core on 127.0.0.1:7465 · log ${LOG}`);
    button('Quit')!.click();
    expect(desktop.calls).toEqual(['quit']);
  });

  it('the tunnel’s public port taken: the card names it and offers Restart core, not another local port', async () => {
    const page = await render();
    // The core binds both listeners at start; a taken public port fails the start the same way (StartupFailures).
    await emit({ ...PORT_IN_USE, detail: 'Address already in use: 127.0.0.1:7466' });
    expect(text(page.querySelector('h1'))).toBe('Port 7466 is already in use');
    expect(text(page.querySelector('.gw-status-screen__text p'))).toBe(
      "Another process (PID 4242, user alex) is listening on 127.0.0.1:7466, the port of the tunnel's public listener, so the core can't start. Stop that process, then restart the core.",
    );
    expect(text(page.querySelector('.gw-stepper__step[data-state=error]'))).toBe('Local API, failed Address already in use: 127.0.0.1:7466');
    expect(text(page.querySelector('.gw-code-block'))).toContain('lsof -nP -iTCP:7466 -sTCP:LISTEN');
    expect(button('Use another port')).toBeNull();
    button('Restart core')!.click();
    await settle();
    expect(desktop.calls).toEqual(['restart']);
  });

  it('Use another port: a port main checked, the kit’s dialog, the field’s checks, then main restarts the core', async () => {
    const page = await render();
    await emit(PORT_IN_USE);
    const opener = button('Use another port')!;
    opener.focus();
    opener.click();
    await settle();
    const dialog = $<HTMLDialogElement>('#core-port-dlg')!;
    expect(dialog.hasAttribute('open')).toBe(true);
    expect(dialog.classList.contains('gw-dialog--warning')).toBe(true);
    const input = $<HTMLInputElement>('#core-port-new')!;
    expect(document.activeElement).toBe(input);
    expect(desktop.calls).toEqual(['findFreePort 7475']);
    expect(input.value).toBe('7475');
    expect(text($('#core-port-new-hint'))).toBe('1024 to 65535. 7475 is free on this computer.');
    expect(text(dialog.querySelector('.gw-kv'))).toContain('Endpoint URLs http://127.0.0.1:7475/mcp/dev, /mcp/stage-readonly and /mcp/incident-prod');
    expect(text(dialog.querySelector('.gw-kv'))).toContain('claude.ai: the tunnel’s public URL stays the same');

    // ngrok without a reserved domain gets a new address with each start: the core says so (step API).
    tunnelState.set({ provider: 'ngrok', host: 'a1b2-c3d4.ngrok-free.app', ngrokDomain: null, stableAddress: false });
    await settle();
    expect(text(dialog.querySelector('.gw-kv'))).not.toContain('claude.ai');
    tunnelState.set({ provider: 'ngrok', host: 'gw-acme.ngrok.app', ngrokDomain: 'gw-acme.ngrok.app', stableAddress: true });
    await settle();
    expect(text(dialog.querySelector('.gw-kv'))).toContain('claude.ai: the tunnel’s public URL stays the same');

    // The tunnel's port: the field says why; main is not asked.
    await type(input, '7466');
    expect(text($('#core-port-new-hint'))).toBe('1024 to 65535.');
    dialog.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();
    expect(text($('#core-port-new-err'))).toBe('7466 is the tunnel’s port. Choose another one.');
    expect(input.getAttribute('aria-invalid')).toBe('true');
    expect(input.getAttribute('aria-describedby')).toBe('core-port-new-err core-port-new-hint');
    expect(desktop.calls).toEqual(['findFreePort 7475']);

    // Main refuses (taken meanwhile): its reason at the field, the dialog stays.
    desktop.usePortResult = () => Promise.reject(new Error("Error invoking remote method 'gw:use-port': Error: Port 7480 is in use now. Choose another one."));
    await type(input, '7480');
    dialog.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();
    expect(desktop.calls.at(-1)).toBe('usePort 7480');
    expect(text($('#core-port-new-err'))).toBe('Port 7480 is in use now. Choose another one.');
    expect(dialog.hasAttribute('open')).toBe(true);

    // Accepted: the dialog closes; main starts the core there and says so.
    desktop.usePortResult = () => Promise.resolve();
    await type(input, '7481');
    expect($('#core-port-new-err')).toBeNull();
    dialog.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();
    expect(desktop.calls.at(-1)).toBe('usePort 7481');
    expect(dialog.hasAttribute('open')).toBe(false);
    expect(document.activeElement).toBe(opener);
    await emit({ kind: 'starting', steps: [], port: 7481, publicPort: 7466, version: '0.4.0' });
    expect(text(page.querySelector('h1'))).toBe('Starting the gateway core');
    expect(text(page.querySelector('.gw-status-screen__text p'))).toContain('127.0.0.1:7481');
  });

  it('a new state closes the dialog: the port it was about is no longer the question', async () => {
    await render();
    await emit(PORT_IN_USE);
    button('Use another port')!.click();
    await settle();
    expect($('#core-port-dlg')!.hasAttribute('open')).toBe(true);
    await emit(KIT_LIVE);
    expect($('#core-port-dlg')!.hasAttribute('open')).toBe(false);
  });

  it('outside Electron the dialog says it can’t start the core, and nothing is asked', async () => {
    desktop.available = false;
    await render();
    await emit(PORT_IN_USE);
    button('Use another port')!.click();
    await settle();
    const start = button('Start on this port')!;
    expect(start.getAttribute('aria-disabled')).toBe('true');
    expect(text(document.getElementById(start.getAttribute('aria-describedby')!))).toContain('needs the GateRook desktop app');
    start.click();
    await settle();
    expect(desktop.calls.filter((c) => c.startsWith('usePort'))).toEqual([]);
  });
});

describe('locked: master key not found', () => {
  beforeEach(() => {
    world.health = () => Promise.resolve(health({ status: 'locked', locked: 'MASTER_KEY_MISSING' }));
  });

  it('words what /api/master-key says: the file, the secrets, the last backup; the key is the failed step', async () => {
    const page = await render();
    expect(keyCalls).toEqual(['status']);
    expect(text(page.querySelector('h1'))).toBe('Master key not found');
    expect(text(page.querySelector('.gw-status-screen__text p'))).toBe(
      `The core can't find ${KEY}, so it can't decrypt the 8 secrets stored here. Restore the key from your backup: the last copy was made 12 days ago.`,
    );
    const steps = [...page.querySelectorAll('.gw-stepper__step')];
    expect(steps.map((s) => s.getAttribute('data-state'))).toEqual(['complete', 'error', 'complete', 'complete']);
    expect(text(steps[1])).toBe(`Master key, failed No file at ${KEY}`);
    expect(steps[1].getAttribute('aria-current')).toBe('step');
    expect(text(page.querySelector('.gw-alert--danger .gw-alert__body'))).toBe('The 8 secrets encrypted here become unreadable, and you enter each one again.');
    expect(text(page.querySelector('.gw-status-screen__meta'))).toBe(`GateRook 0.4.0 · core on 127.0.0.1:7465 · log ${LOG}`);
  });

  it("says whose the secrets are and which references keep working when the core tells (step API)", async () => {
    world.key = () => ({
      ...masterKey(),
      protectsBy: [
        { kind: 'connection', secrets: 6, owners: ['app-logs', 'billing-api', 'cache-redis', 'dev-db', 'dev-vm', 'prod-vm'] },
        { kind: 'mcp-server', secrets: 1, owners: ['github-mcp'] },
        { kind: 'store', secrets: 1, owners: ['vault'] },
      ],
      references: { count: 3, sources: ['vault', 'gcp-sm', 'aws-sm'] },
    });
    const page = await render();
    const body = page.querySelector('.gw-alert--danger .gw-alert__body')!;
    // The kit's sentence (pages/core-starting.html), the server's name in mono.
    expect(text(body)).toBe(
      'The 8 secrets encrypted here become unreadable (6 connections, the github-mcp server token and the Vault sign-in), and you enter each one again. The 3 secret references to Vault, GCP and AWS keep working.',
    );
    expect(text(body.querySelector('.gw-mono'))).toBe('github-mcp');
  });

  it('Create a new key: the danger dialog focuses Cancel; confirmed, the core makes it and Secrets opens', async () => {
    const page = await render();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl');
    button('Create a new key')!.click();
    await settle();
    const dialog = page.querySelector<HTMLDialogElement>('dialog.gw-dialog--danger')!;
    expect(dialog.hasAttribute('open')).toBe(true);
    expect(text(document.activeElement)).toBe('Cancel');
    expect(words(dialog.querySelector('.gw-kv'))).toContain('Becomes unreadable 8 secrets');
    button('Cancel', dialog)!.click();
    await settle();
    expect(keyCalls).toEqual(['status']);

    world.health = () => Promise.resolve(health({}));
    button('Create a new key')!.click();
    await settle();
    button('Create a new master key', dialog)!.click();
    await settle();
    expect(keyCalls).toEqual(['status', 'create']);
    expect(TestBed.inject(CoreStore).running()).toBe(true);
    expect(navigate).toHaveBeenCalledWith('/secrets');
    expect(TestBed.inject(ToastService).toasts().map((t) => [t.tone, t.title, t.body])).toEqual([
      ['warning', 'New master key created', '2 secrets need a value again: app-logs/token and dev-db/password. Enter each one on Secrets.'],
    ]);
  });

  it('a new key the core refuses is said in the card (toasts sit under the screen)', async () => {
    world.create = () => Promise.reject(problem(422, { title: 'Can’t write or read the file', detail: 'Can’t write the key file: read-only file system' }));
    const page = await render();
    button('Create a new key')!.click();
    await settle();
    button('Create a new master key')!.click();
    await settle();
    const notice = page.querySelector('.gw-card .gw-alert--compact[role=alert]');
    expect(words(notice)).toBe('No new key was created Can’t write the key file: read-only file system');
    expect(TestBed.inject(CoreStore).locked()).toBe(true);
  });

  it('a new key the core no longer needs (409: unlocked meanwhile) is no error: the window goes back to the app', async () => {
    world.create = () => Promise.reject(problem(409, { title: 'Not locked', detail: 'The master key works: there is nothing to restore.' }));
    const page = await render();
    world.health = () => Promise.resolve(health({}));
    button('Create a new key')!.click();
    await settle();
    button('Create a new master key')!.click();
    await settle();
    expect(page.querySelector('.gw-card .gw-alert--compact[role=alert]')).toBeNull();
    expect(TestBed.inject(CoreStore).running()).toBe(true);
  });

  it('Restore from backup: passphrase first (focused), then the open dialog’s token; the core unlocks, the field is emptied', async () => {
    const page = await render();
    const opener = button('Restore from backup')!;
    opener.focus();
    opener.click();
    await settle();
    const dialog = $<HTMLDialogElement>('#core-restore-dlg')!;
    expect(dialog.hasAttribute('open')).toBe(true);
    const pass = $<HTMLInputElement>('#core-restore-pass')!;
    expect(document.activeElement).toBe(pass);
    expect(text(dialog.querySelector('.gw-dialog__description'))).toBe('Choose the copy you saved with Back up master key: the key that encrypted the 8 secrets stored here.');
    expect(words(dialog.querySelector('.gw-kv'))).toBe(`Key file ${KEY} Last backup 12 days ago`);

    world.health = () => Promise.resolve(health({}));
    pass.value = 'correct horse';
    dialog.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();
    expect(desktop.calls).toEqual(['openFile master-key-restore']);
    expect(keyCalls).toEqual(['status', 'restore open-token-1 correct horse']);
    expect(dialog.hasAttribute('open')).toBe(false);
    expect(pass.value).toBe('');
    expect(TestBed.inject(CoreStore).running()).toBe(true);
    expect(TestBed.inject(ToastService).toasts().map((t) => t.title)).toEqual(['Master key restored']);
    void page;
  });

  it('a backup that needs its passphrase: the field says so and takes the focus; the dialog stays', async () => {
    world.restore = () => Promise.reject(problem(422, { title: 'Can’t read the backup', detail: 'This backup is protected by a passphrase. Enter it to restore.' }));
    await render();
    button('Restore from backup')!.click();
    await settle();
    const dialog = $<HTMLDialogElement>('#core-restore-dlg')!;
    dialog.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();
    const pass = $<HTMLInputElement>('#core-restore-pass')!;
    expect(keyCalls.at(-1)).toBe('restore open-token-1 null');
    expect(text($('#core-restore-pass-err'))).toBe('This backup is protected by a passphrase. Enter it to restore.');
    expect(pass.getAttribute('aria-invalid')).toBe('true');
    expect(document.activeElement).toBe(pass);
    expect(dialog.hasAttribute('open')).toBe(true);

    // A key that doesn't fit is the dialog's alert, not the field's.
    world.restore = () => Promise.reject(problem(422, { title: 'The backup doesn’t fit', detail: 'This backup doesn’t open the stored data: it is another GateRook’s key.' }));
    dialog.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();
    expect($('#core-restore-pass-err')).toBeNull();
    expect(words(dialog.querySelector('.gw-alert--danger[role=alert]'))).toBe('The key wasn\'t restored This backup doesn’t open the stored data: it is another GateRook’s key.');
  });

  it('a cancelled file dialog asks nothing of the core', async () => {
    desktop.openFileToken = null;
    await render();
    button('Restore from backup')!.click();
    await settle();
    $('#core-restore-dlg form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();
    expect(desktop.calls).toEqual(['openFile master-key-restore']);
    expect(keyCalls).toEqual(['status']);
  });

  it('a file main can’t hand to the core is said in the dialog, nothing is asked of the core', async () => {
    desktop.openFile = () => Promise.reject(new Error("Error invoking remote method 'gw:openFile': Error: The core refused the file: choose a file, not a folder."));
    await render();
    button('Restore from backup')!.click();
    await settle();
    const dialog = $<HTMLDialogElement>('#core-restore-dlg')!;
    dialog.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();
    expect(keyCalls).toEqual(['status']);
    expect(dialog.hasAttribute('open')).toBe(true);
    expect(words(dialog.querySelector('.gw-alert--danger[role=alert]'))).toContain('The core refused the file: choose a file, not a folder.');
  });

  it('in a browser: choosing the file needs the desktop app, and Quit says why it can’t', async () => {
    desktop.available = false;
    await render();
    const quit = button('Quit')!;
    expect(quit.getAttribute('aria-disabled')).toBe('true');
    expect(text(document.getElementById(quit.getAttribute('aria-describedby')!))).toBe('This window is a browser tab: close the tab to leave.');
    quit.click();
    button('Restore from backup')!.click();
    await settle();
    const choose = button('Choose the backup file')!;
    expect(choose.getAttribute('aria-disabled')).toBe('true');
    expect(text(document.getElementById(choose.getAttribute('aria-describedby')!))).toContain('needs the GateRook desktop app');
    $('#core-restore-dlg form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();
    expect(desktop.calls).toEqual([]);
  });
});

describe('stopped and failed starts', () => {
  const STOPPED: DesktopCoreState = {
    kind: 'stopped',
    exitCode: 3,
    reason: 'java.lang.OutOfMemoryError: Java heap space',
    uptimeMs: (3 * 60 + 12) * 60_000,
    at: '2026-10-22T14:29:52+02:00',
    log: LOG,
    logTail: '14:29:52.405 INFO  Shutting down: 3 pending approval requests kept\n14:29:52.411 INFO  Terminating (exit 3)',
    port: 7465,
  };

  it('stopped: the exit facts, the requests this window saw waiting, the last log lines on demand', async () => {
    world.health = () => Promise.resolve(health({}));
    const page = await render();
    expect(TestBed.inject(CoreStore).running()).toBe(true);
    await emit(STOPPED);
    expect(text(page.querySelector('h1'))).toBe('Core stopped unexpectedly');
    expect(text(page.querySelector('.gw-status-screen__text p'))).toBe(
      `The gateway core exited at ${clockTime(STOPPED.kind === 'stopped' ? STOPPED.at! : '', false)}. Agents can't reach your endpoints until it runs again. The 3 pending approval requests are kept and expire as usual.`,
    );
    expect(words(page.querySelector('.gw-kv'))).toBe(`Exit code 3 Reason java.lang.OutOfMemoryError: Java heap space Uptime 3 h 12 min Log file ${LOG}`);
    const toggle = button('Show the last log lines')!;
    const log = document.getElementById(toggle.getAttribute('aria-controls')!)!;
    expect(log.hidden).toBe(true);
    toggle.click();
    await settle();
    expect(log.hidden).toBe(false);
    expect(text(log)).toContain('core.log · last 2 lines');
    expect(text(log)).toContain('Terminating (exit 3)');

    button('Open logs')!.click();
    expect(desktop.calls).toEqual(['reveal logs']);
  });

  it('Restart core asks main once, busy until the core says something new', async () => {
    let finish!: () => void;
    desktop.restartResult = () => new Promise<void>((r) => (finish = r));
    const page = await render();
    await emit(STOPPED);
    const restart = button('Restart core')!;
    restart.click();
    await settle();
    expect(restart.getAttribute('aria-busy')).toBe('true');
    restart.click();
    await settle();
    expect(desktop.calls).toEqual(['restart']);
    finish();
    await emit(KIT_LIVE);
    expect(text(page.querySelector('h1'))).toBe('Starting the gateway core');
  });

  it('a restart main can’t do is said in the card', async () => {
    desktop.restartResult = () => Promise.reject(new Error('The core binary is missing. Reinstall GateRook.'));
    const page = await render();
    await emit(STOPPED);
    button('Restart core')!.click();
    await settle();
    expect(words(page.querySelector('.gw-card .gw-alert--compact[role=alert]'))).toBe('The core didn’t restart The core binary is missing. Reinstall GateRook.');
  });

  it('a second core on the same home: its own card with the facts, no stepper, Open logs and Restart core', async () => {
    const page = await render();
    await emit({
      kind: 'failed',
      reason: 'DB_LOCKED',
      detail: 'Another GateRook core is using /home/alex/.mcp-gateway (core.lock is locked)',
      portOwner: null,
      log: LOG,
      logTail: null,
      exitCode: 2,
      port: 7465,
    });
    expect(text(page.querySelector('h1'))).toBe('Another core is using the database');
    expect(page.querySelector('.gw-stepper')).toBeNull();
    expect(words(page.querySelector('.gw-kv'))).toBe(
      `Exit code 2 Reason DB_LOCKED: Another GateRook core is using /home/alex/.mcp-gateway (core.lock is locked) Log file ${LOG}`,
    );
    expect(text(page.querySelector('[role=alert]'))).toBe('The core failed to start: Another core is using the database.');
    expect(button('Restart core')).not.toBeNull();
    expect(button('Open logs')).not.toBeNull();
  });
});
