import { HttpErrorResponse } from '@angular/common/http';
import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ApiError } from '../../core/api/api-error';
import { ClientsApi, ClientsView } from '../../core/api/clients.api';
import { Onboarding, OverviewApi } from '../../core/api/overview.api';
import { MasterKeyApi, MasterKeyStatus } from '../../core/api/secrets.api';
import { DesktopBridge } from '../../core/desktop/desktop-bridge';
import { EventStream } from '../../core/events/event-stream';
import { CoreStore } from '../../core/stores/core.store';
import { TimeService } from '../../core/time/time.service';
import { Announcer } from '../../kit/announcer';
import { AriaDisabledGuard } from '../../kit/aria-disabled';
import { provideKitIcons } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { NOW, backedUpKey, clients, masterKey, onboarding } from './onboarding.fixtures';
import { OnboardingPage, SKELETON_DELAY_MS } from './onboarding.page';

/**
 * Welcome against fake admin API services in the kit's first-run world (design/pages/onboarding.html): what it shows
 * for each answer, what each control asks the core, where the keyboard goes and what is announced.
 */

/** Links lead somewhere harmless: the page's own route table isn't under test. */
@Component({ template: '' })
class Elsewhere {}

class FakeEvents {
  on(): () => void {
    return () => undefined;
  }
}

const problem = (status: number, body: object) => ApiError.fromHttp(new HttpErrorResponse({ status, error: body }));
const notAvailable = () => ApiError.fromHttp(new HttpErrorResponse({ status: 404, error: { status: 404, error: 'Not Found' } }));

function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const now = signal(NOW);
const fakeTime: Partial<TimeService> = {
  now,
  minute: now,
  relative: (at) => (at ? 'just now' : ''),
  absolute: (at) => (at ? `abs ${at}` : ''),
  iso: (at) => at ?? '',
};

let calls: string[];
let onboardingAnswer: () => Promise<Onboarding>;
let keyAnswer: () => Promise<MasterKeyStatus>;
let clientsAnswer: () => Promise<ClientsView>;
let backupAnswer: () => Promise<{ path: string; at: string; keyIds: number[]; passphrase: boolean; warnings: string[] }>;
let skipAnswer: () => Promise<{ skipped: boolean }>;
let saveFile: () => Promise<string | null>;
let announced: string[];

function setup(opts: { readonly desktop?: boolean } = {}): void {
  calls = [];
  announced = [];
  onboardingAnswer = () => Promise.resolve(onboarding());
  keyAnswer = () => Promise.resolve(masterKey());
  clientsAnswer = () => Promise.resolve(clients());
  skipAnswer = () => Promise.resolve({ skipped: true });
  saveFile = () => Promise.resolve('ft_token');
  backupAnswer = () => {
    // The core wrote the copy: from now on it says the key is backed up.
    keyAnswer = () => Promise.resolve(backedUpKey());
    onboardingAnswer = () => Promise.resolve(onboarding({ 'master-key': { lastBackupAt: new Date(NOW - 1_000).toISOString() } }));
    return Promise.resolve({ path: '/Users/you/Downloads/master-keyset-2026-10-22.json', at: new Date(NOW).toISOString(), keyIds: [1], passphrase: true, warnings: [] });
  };
  const overview: Partial<OverviewApi> = {
    onboarding: () => {
      calls.push('GET /api/onboarding');
      return onboardingAnswer();
    },
    skipOnboarding: () => {
      calls.push('POST /api/onboarding/skip');
      return skipAnswer();
    },
  };
  const key: Partial<MasterKeyApi> = {
    status: () => keyAnswer(),
    backup: (token, passphrase) => {
      calls.push(`POST /api/master-key/backup ${token} ${passphrase ?? 'none'}`);
      return backupAnswer();
    },
  };
  const desktop = opts.desktop ?? true;
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: '**', component: Elsewhere }]),
      provideKitIcons(),
      { provide: TimeService, useValue: fakeTime },
      { provide: OverviewApi, useValue: overview },
      { provide: MasterKeyApi, useValue: key },
      { provide: ClientsApi, useValue: { list: () => clientsAnswer() } satisfies Partial<ClientsApi> },
      { provide: EventStream, useValue: new FakeEvents() },
      { provide: CoreStore, useValue: { running: signal(true), instanceChanges: signal(0), address: signal('127.0.0.1:7465') } },
      {
        provide: DesktopBridge,
        useValue: {
          available: desktop,
          platform: desktop ? 'darwin' : 'web',
          saveFile: () => {
            calls.push('save dialog');
            return desktop ? saveFile() : Promise.resolve(null);
          },
        },
      },
    ],
  });
  TestBed.inject(AriaDisabledGuard).install();
  vi.spyOn(TestBed.inject(Announcer), 'announce').mockImplementation((t: string) => void announced.push(t));
}

afterEach(() => {
  document.body.innerHTML = '';
  TestBed.inject(ToastService).clear();
  sessionStorage.clear();
});

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function render(): Promise<{ fixture: ComponentFixture<OnboardingPage>; el: HTMLElement }> {
  const fixture = TestBed.createComponent(OnboardingPage);
  document.body.appendChild(fixture.nativeElement);
  await settle(fixture);
  return { fixture, el: fixture.nativeElement as HTMLElement };
}

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  for (let i = 0; i < 4; i++) {
    await fixture.whenStable();
    await sleep(0);
  }
  fixture.detectChanges();
  await fixture.whenStable();
}

const text = (el: Element | null | undefined) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
const visiblePanel = (el: HTMLElement) => Array.from(el.querySelectorAll<HTMLElement>('section[id^="ob-panel-"]')).filter((p) => !p.hidden).map((p) => p.id);
const steps = (el: HTMLElement) =>
  Array.from(el.querySelectorAll('#ob-stepper > li')).map((li) => [li.getAttribute('data-state'), li.getAttribute('aria-current'), text(li)]);
const button = (root: ParentNode, name: string) =>
  Array.from(root.querySelectorAll<HTMLElement>('button, a')).find((b) => text(b) === name || text(b).startsWith(`${name} `)) ?? null;

describe('Welcome page', () => {
  it('renders the kit first run from the core answers', async () => {
    setup();
    const { el } = await render();

    expect(text(el.querySelector('h1'))).toBe('Welcome to GateRook');
    expect(text(el.querySelector('.gw-page-header__actions'))).toBe('Skip setup');
    expect(el.querySelector('.gw-page-header__actions a')?.getAttribute('href')).toBe('/overview');
    expect(Array.from(el.querySelectorAll('ul[aria-label="What GateRook guarantees"] .gw-text-h3')).map(text)).toEqual([
      'Your agent never sees secrets',
      'You approve every change',
      'Every call is logged',
    ]);
    expect(steps(el)).toEqual([
      ['current', 'step', '1 Protect master key Back it up off this computer'],
      ['upcoming', null, '2 Add a connection A database, server, log store or API'],
      ['upcoming', null, '3 Create an endpoint One MCP URL with the tools you choose'],
      ['upcoming', null, '4 Connect your AI app Claude Desktop, Codex, Cursor and more'],
    ]);
    expect(visiblePanel(el)).toEqual(['ob-panel-1']);

    // Step 1: the key the core created, where it is and who can read it.
    expect(text(el.querySelector('#ob-key-path'))).toBe('/Users/you/.gaterook/master.keyset');
    expect(Array.from(el.querySelectorAll('#ob-panel-1 .gw-kv__row')).map(text)).toEqual([
      'Permissions 0400 · only your user can read it',
      'Format Google Tink keyset, sealed with a key from the macOS Keychain',
    ]);
    expect(el.querySelector('#ob-panel-1 .gw-text-body-read')?.textContent).toContain('not against programs you run, including AI agents with a terminal');
    expect(text(el.querySelector('#ob-panel-1 .gw-alert--warning .gw-alert__title'))).toBe('Not backed up yet');
    expect(button(el, "I'll do it later")?.getAttribute('aria-describedby')).toBe('ob-later-why');
    const backup = button(el, 'Back up key')!;
    expect(backup.classList).toContain('gw-btn--primary');
    expect(backup.getAttribute('aria-describedby')).toBe('ob-backup-hint');
    expect(backup.getAttribute('aria-disabled')).toBeNull();
    expect(el.querySelector<HTMLElement>('#ob-backup-status')!.hidden).toBe(true);

    // Steps 2–4 lead to their pages; the endpoint URL is this core's.
    expect(el.querySelector('#ob-panel-2 .gw-btn--primary')?.getAttribute('href')).toBe('/connections/new');
    expect(el.querySelector('#ob-panel-3 .gw-btn--primary')?.getAttribute('href')).toBe('/endpoints#create');
    expect(text(el.querySelector('#ob-panel-3 .gw-card__description'))).toBe('One MCP URL, such as http://127.0.0.1:7465/mcp/dev.');
    expect(el.querySelector('#ob-panel-4 .gw-btn--primary')?.getAttribute('href')).toBe('/clients');
    expect(text(el.querySelector('#ob-panel-4 .gw-card__description'))).toBe('Claude Desktop, Codex and Cursor are installed on this computer.');
    expect(el.querySelector('#ob-panel-4 .gw-text-body-read')?.textContent).toContain('signs in through the browser or goes through the bridge instead');

    // The setup map, named by its heading and described by the steps.
    const map = el.querySelector('ol.gw-setup-map')!;
    expect(map.classList).toContain('gw-setup-map--vertical');
    expect(map.getAttribute('aria-labelledby')).toBe('ob-map-title');
    expect(map.getAttribute('aria-label')).toBeNull();
    expect(text(el.querySelector(`#${map.getAttribute('aria-describedby')}`))).toMatch(/^Set it up top to bottom: step 1 protects secrets and resources/);
    expect(Array.from(el.querySelectorAll('.gw-setup-map__title')).map((h) => h.tagName)).toEqual(['H4', 'H4', 'H4', 'H4', 'H4']);
    expect(Array.from(el.querySelectorAll('.gw-setup-map__status')).map(text)).toEqual([
      'Step 1 · master key not backed up',
      'Step 2 · none yet',
      'Safe defaults on · review any time',
      'Step 3 · none yet',
      'Step 4 · 3 detected',
    ]);
    expect(Array.from(el.querySelectorAll('.gw-setup-map__state')).map(text)).toEqual(['Needs attention', 'Not set up', 'Done', 'Not set up', 'Not set up']);
  });

  it('shows nothing for a moment, then the skeleton until the core answers', async () => {
    setup();
    const answer = deferred<Onboarding>();
    onboardingAnswer = () => answer.promise;
    const { fixture, el } = await render();
    expect(el.querySelector('.gw-skeleton')).toBeNull();
    expect(text(el.querySelector('h1'))).toBe('Welcome to GateRook');

    await sleep(SKELETON_DELAY_MS + 50);
    await settle(fixture);
    expect(el.querySelectorAll('.gw-skeleton').length).toBeGreaterThan(5);
    expect(el.querySelector('section[aria-labelledby="setup-title"]')?.getAttribute('aria-busy')).toBe('true');
    expect(text(el.querySelector('[role="status"]'))).toBe('Loading your setup');

    answer.resolve(onboarding());
    await settle(fixture);
    expect(el.querySelector('#ob-stepper')).not.toBeNull();
    expect(el.querySelector('[aria-busy="true"]')).toBeNull();
  });

  it('says what failed, with Try again and the details', async () => {
    setup();
    onboardingAnswer = () => Promise.reject(problem(500, { title: 'Internal error', detail: 'The database is locked by another process.' }));
    const { fixture, el } = await render();
    const alert = el.querySelector('.gw-alert--danger[role="alert"]')!;
    expect(text(alert.querySelector('.gw-alert__title'))).toBe("Your setup didn't load");
    expect(text(alert.querySelector('.gw-alert__body'))).toBe('The database is locked by another process.');
    expect(el.querySelector('#ob-error-details')?.textContent).toContain('GET /api/onboarding → 500');

    onboardingAnswer = () => Promise.resolve(onboarding());
    button(alert, 'Try again')!.click();
    await settle(fixture);
    expect(el.querySelector('.gw-alert--danger')).toBeNull();
    expect(el.querySelector('#ob-stepper')).not.toBeNull();
  });

  it("puts the backup off for this window: step 2 opens, focused and announced (kit I'll do it later)", async () => {
    setup();
    const { fixture, el } = await render();
    button(el, "I'll do it later")!.click();
    await settle(fixture);
    expect(visiblePanel(el)).toEqual(['ob-panel-2']);
    expect(steps(el).map((s) => [s[0], s[2]])).toEqual([
      ['upcoming', '1 Protect master key Skipped · no backup yet'],
      ['current', '2 Add a connection A database, server, log store or API'],
      ['upcoming', '3 Create an endpoint One MCP URL with the tools you choose'],
      ['upcoming', '4 Connect your AI app Claude Desktop, Codex, Cursor and more'],
    ]);
    expect(document.activeElement?.id).toBe('ob-panel-2-title');
    expect(announced).toEqual(['Master key backup skipped. Step 2 of 4: Add a connection.']);

    // Back returns to step 1 (kit data-ob-go).
    button(el.querySelector('#ob-panel-2')!, 'Back')!.click();
    await settle(fixture);
    expect(visiblePanel(el)).toEqual(['ob-panel-1']);
    expect(document.activeElement?.id).toBe('ob-panel-1-title');
    expect(announced.at(-1)).toBe('Step 1 of 4: Protect master key.');

    // Opened again in the same window, Welcome remembers.
    fixture.destroy();
    const again = await render();
    expect(visiblePanel(again.el)).toEqual(['ob-panel-2']);
  });

  it('backs up the key: busy in place, then step 2, the toast that stays, the passphrase gone', async () => {
    setup();
    const dialog = deferred<string | null>();
    saveFile = () => dialog.promise;
    const write = deferred<void>();
    const finish = backupAnswer;
    backupAnswer = () => write.promise.then(finish);
    const { fixture, el } = await render();
    const field = el.querySelector<HTMLInputElement>('#ob-backup-pass')!;
    field.value = 'correct horse battery staple';
    const reveal = el.querySelector<HTMLElement>('button[aria-controls="ob-backup-pass"]')!;
    reveal.click();
    await settle(fixture);
    expect([field.type, reveal.getAttribute('aria-pressed')]).toEqual(['text', 'true']);

    button(el, 'Back up key')!.click();
    await settle(fixture);
    // Waiting for the save dialog: busy, the status line says so and the button points at it.
    const busy = el.querySelector<HTMLElement>('#ob-backup')!;
    expect(busy.getAttribute('aria-busy')).toBe('true');
    expect(busy.getAttribute('aria-disabled')).toBe('true');
    expect(busy.querySelector('.gw-spinner')).not.toBeNull();
    expect(text(busy)).toBe('Back up key');
    expect(busy.getAttribute('aria-describedby')).toBe('ob-backup-status');
    expect(el.querySelector<HTMLElement>('#ob-backup-status')!.hidden).toBe(false);
    expect(text(el.querySelector('#ob-backup-status'))).toBe('Waiting for the save dialog: pick where the copy of master.keyset goes.');
    // A second click while busy does nothing.
    busy.click();
    expect(calls.filter((c) => c === 'save dialog').length).toBe(1);

    dialog.resolve('ft_token');
    await settle(fixture);
    expect(text(el.querySelector('#ob-backup-status'))).toBe('Writing the copy of master.keyset to the place you picked.');
    write.resolve();
    await settle(fixture);
    await settle(fixture);

    expect(calls).toContain('POST /api/master-key/backup ft_token correct horse battery staple');
    expect(field.value).toBe('');
    expect([field.type, reveal.getAttribute('aria-pressed')]).toEqual(['password', 'false']);
    expect(fixture.nativeElement.innerHTML).not.toContain('correct horse');
    expect(visiblePanel(el)).toEqual(['ob-panel-2']);
    expect(steps(el)[0]).toEqual(['complete', null, 'Protect master key, completed Backed up just now']);
    expect(el.querySelector('#ob-stepper li .gw-stepper__marker svg')).not.toBeNull();
    expect(document.activeElement?.id).toBe('ob-panel-2-title');
    expect(announced).toEqual(['Master key backed up. Step 2 of 4: Add a connection.']);
    expect(text(el.querySelector('.gw-setup-map__status'))).toBe('Step 1 · master key backed up, no secrets yet');
    expect(text(el.querySelector('.gw-setup-map__state'))).toBe('Done');
    const [toast] = TestBed.inject(ToastService).toasts();
    expect(toast).toMatchObject({
      tone: 'success',
      title: 'Master key backed up, encrypted with your passphrase',
      body: 'Saved to /Users/you/Downloads/master-keyset-2026-10-22.json. Move it to your password manager or an encrypted drive, then delete it from this computer.',
      timeout: 0,
    });

    // Back to step 1: it says the key is backed up, Continue leads on.
    button(el.querySelector('#ob-panel-2')!, 'Back')!.click();
    await settle(fixture);
    expect(text(el.querySelector('#ob-panel-1 .gw-alert--success .gw-alert__title'))).toBe('Backed up just now');
    expect(button(el, 'Back up again')).not.toBeNull();
    button(el.querySelector('#ob-panel-1')!, 'Continue')!.click();
    await settle(fixture);
    expect(visiblePanel(el)).toEqual(['ob-panel-2']);
  });

  it('changes nothing when the save dialog is cancelled', async () => {
    setup();
    saveFile = () => Promise.resolve(null);
    const { fixture, el } = await render();
    button(el, 'Back up key')!.click();
    await settle(fixture);
    expect(calls).toContain('save dialog');
    expect(calls.some((c) => c.startsWith('POST /api/master-key/backup'))).toBe(false);
    expect(el.querySelector('#ob-backup')?.getAttribute('aria-busy')).toBeNull();
    expect(visiblePanel(el)).toEqual(['ob-panel-1']);
  });

  it('keeps the card and the passphrase when the core refuses the backup', async () => {
    setup();
    backupAnswer = () => Promise.reject(problem(422, { title: 'Choose the file again', detail: 'The save token expired. Choose the file again.', field: '/saveToken' }));
    const { fixture, el } = await render();
    el.querySelector<HTMLInputElement>('#ob-backup-pass')!.value = 'secret words';
    button(el, 'Back up key')!.click();
    await settle(fixture);
    const alert = el.querySelector('#ob-panel-1 .gw-alert--danger[role="alert"]')!;
    expect(text(alert)).toBe("The key wasn't backed up The save token expired. Choose the file again.");
    expect(el.querySelector<HTMLInputElement>('#ob-backup-pass')!.value).toBe('secret words');
    expect(visiblePanel(el)).toEqual(['ob-panel-1']);
    expect(TestBed.inject(ToastService).toasts()).toEqual([]);
  });

  it('says Back up needs the desktop app outside it', async () => {
    setup({ desktop: false });
    const { fixture, el } = await render();
    const backup = el.querySelector<HTMLElement>('#ob-backup')!;
    expect(backup.getAttribute('aria-disabled')).toBe('true');
    expect(backup.getAttribute('aria-describedby')).toBe('ob-backup-why');
    expect(text(el.querySelector('#ob-backup-why'))).toBe('Saving a file needs the GateRook desktop app: it opens the save dialog. Back up the key from there.');
    backup.click();
    await settle(fixture);
    expect(calls).not.toContain('save dialog');
  });

  it('opens on the first step left when coming back, and says what a done step set up', async () => {
    setup();
    onboardingAnswer = () => Promise.resolve(onboarding({ 'master-key': { lastBackupAt: '2026-10-22T12:00:00Z' }, connection: { name: 'dev-db', typeName: 'PostgreSQL', count: 1 } }));
    keyAnswer = () => Promise.resolve(backedUpKey('2026-10-22T12:00:00Z'));
    const { fixture, el } = await render();
    expect(visiblePanel(el)).toEqual(['ob-panel-3']);
    expect(steps(el).map((s) => s[0])).toEqual(['complete', 'complete', 'current', 'upcoming']);
    expect(text(el.querySelectorAll('#ob-stepper .gw-stepper__description')[1])).toBe('dev-db · PostgreSQL');
    expect(el.querySelector('#ob-stepper .gw-stepper__description .gw-mono')?.textContent).toBe('dev-db');
    // No focus moves on load: the page doesn't steal the keyboard.
    expect(document.activeElement?.id).not.toBe('ob-panel-3-title');

    button(el.querySelector('#ob-panel-3')!, 'Back')!.click();
    await settle(fixture);
    const done = el.querySelector('#ob-panel-2 .gw-alert--success')!;
    expect(text(done)).toBe('Connection dev-db is set up. Add another connection');
    expect(done.querySelector('a')?.getAttribute('href')).toBe('/connections/new');
    expect(text(el.querySelector('#ob-panel-2 .gw-card__footer .gw-btn--primary'))).toBe('Continue to create an endpoint');
    button(el.querySelector('#ob-panel-2')!, 'Continue')!.click();
    await settle(fixture);
    expect(visiblePanel(el)).toEqual(['ob-panel-3']);
    expect(announced.at(-1)).toBe('Step 3 of 4: Create an endpoint.');
  });

  it('leads to Overview once every step is done, without Skip setup', async () => {
    setup();
    onboardingAnswer = () =>
      Promise.resolve(
        onboarding({
          'master-key': { lastBackupAt: '2026-10-22T12:00:00Z' },
          connection: { name: 'dev-db', count: 1 },
          endpoint: { name: 'dev', tools: 22 },
          client: { clients: ['codex'] },
        }),
      );
    keyAnswer = () => Promise.resolve(backedUpKey('2026-10-22T12:00:00Z'));
    clientsAnswer = () => Promise.resolve(clients(['codex']));
    const { el } = await render();
    expect(el.querySelector('.gw-page-header__actions')).toBeNull();
    expect(visiblePanel(el)).toEqual(['ob-panel-4']);
    expect(steps(el).map((s) => s[0])).toEqual(['complete', 'complete', 'complete', 'current']);
    expect(text(el.querySelector('#ob-panel-4 .gw-alert--success .gw-alert__body'))).toBe('Codex uses GateRook.');
    const open = el.querySelector('#ob-panel-4 .gw-card__footer .gw-btn--primary')!;
    expect(text(open)).toBe('Open overview');
    expect(open.getAttribute('href')).toBe('/overview');
  });

  it('names what can connect before K1 answers', async () => {
    setup();
    clientsAnswer = () => Promise.reject(notAvailable());
    const { el } = await render();
    expect(text(el.querySelector('#ob-panel-4 .gw-card__description'))).toBe('Claude Desktop, Codex, Cursor and Antigravity can connect.');
    expect(text(el.querySelectorAll('.gw-setup-map__status')[4])).toBe('Step 4 · none yet');
  });

  it("says when the key's status didn't load, and still offers the backup", async () => {
    setup();
    keyAnswer = () => Promise.reject(problem(500, { title: 'Error', detail: 'The key file is unreadable.' }));
    const { el } = await render();
    expect(text(el.querySelector('#ob-panel-1 .gw-alert--danger'))).toContain('The key file is unreadable.');
    expect(el.querySelector('#ob-key-path')).toBeNull();
    expect(el.querySelector('#ob-backup')?.getAttribute('aria-disabled')).toBeNull();
  });

  it('skips setup, and says so when the core refused', async () => {
    setup();
    const { fixture, el } = await render();
    const skip = button(el, 'Skip setup')!;
    skip.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    await settle(fixture);
    expect(calls).toContain('POST /api/onboarding/skip');
    expect(TestBed.inject(ToastService).toasts()).toEqual([]);

    skipAnswer = () => Promise.reject(problem(500, { detail: 'nope' }));
    skip.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    await settle(fixture);
    expect(TestBed.inject(ToastService).toasts()[0]).toMatchObject({ tone: 'warning', title: "Couldn't skip setup" });
  });

  it('keeps the last answer on screen when a refresh fails', async () => {
    setup();
    const { fixture, el } = await render();
    onboardingAnswer = () => Promise.reject(problem(500, { detail: 'The core is busy.' }));
    (fixture.componentInstance as unknown as { retry(): void }).retry();
    await settle(fixture);
    expect(el.querySelector('#ob-stepper')).not.toBeNull();
    expect(text(el.querySelector('.gw-alert--warning[role="status"] .gw-alert__title'))).toBe('This page may be out of date.');
  });
});
