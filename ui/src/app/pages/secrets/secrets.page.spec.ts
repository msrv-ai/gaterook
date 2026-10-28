import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { ApiError } from '../../core/api/api-error';
import { ConnectionsApi } from '../../core/api/connections.api';
import { MasterKeyApi, MasterKeyStatus, SecretRow, SecretStore, SecretStoreCreate, SecretsApi, ShortLivedCredential } from '../../core/api/secrets.api';
import { DesktopBridge } from '../../core/desktop/desktop-bridge';
import { EventStream } from '../../core/events/event-stream';
import { CoreStore } from '../../core/stores/core.store';
import { absoluteTime, isoLocal, relativeTime, shortDate } from '../../core/time/time-format';
import { TimeService } from '../../core/time/time.service';
import { AriaDisabledGuard } from '../../kit/aria-disabled';
import { provideKitIcons } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { ENVS, NOW, kitMasterKey, kitSecrets, kitShortLived, kitStores } from './secrets.fixtures';
import { SKELETON_DELAY_MS, SecretsPage } from './secrets.page';

/**
 * The Secrets page against fake API services in the kit's world (design/pages/secrets.html): what it shows, what each
 * control asks the core, where the keyboard goes, and that a typed value never outlives its request.
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
  status: () => Promise<MasterKeyStatus>;
  list: () => Promise<SecretRow[]>;
  stores: () => Promise<SecretStore[]>;
  shortLived: () => Promise<ShortLivedCredential[]>;
  replace: (id: string, value: string, testBefore: boolean, reason: string | null) => Promise<unknown>;
  rotate: (id: string) => Promise<unknown>;
  testReference: (uri: string) => Promise<unknown>;
  createStore: (req: SecretStoreCreate) => Promise<SecretStore>;
  /** `GET /api/connections`: the owners' envs (prod needs a comment). */
  connections: () => Promise<unknown>;
}

let calls: string[];
let requests: unknown[];
let world: World;
let events: FakeEvents;
let desktop: { available: boolean; platform: string; saveFile: (purpose: string) => Promise<string | null> };
const now = signal(NOW);

const fakeTime: Partial<TimeService> = {
  now,
  minute: now,
  relative: (at) => (at ? relativeTime(at, now()) : ''),
  absolute: (at, withSeconds = true) => (at ? absoluteTime(at, withSeconds) : ''),
  iso: (at) => (at ? isoLocal(at) : ''),
  date: (at, year = false) => (at ? shortDate(at, now(), year) : ''),
};

function configure(): void {
  calls = [];
  requests = [];
  events = new FakeEvents();
  desktop = { available: false, platform: 'web', saveFile: () => Promise.resolve(null) };
  world = {
    status: () => Promise.resolve(kitMasterKey()),
    list: () => Promise.resolve(kitSecrets()),
    stores: () => Promise.resolve(kitStores()),
    shortLived: () => Promise.resolve(kitShortLived()),
    replace: (id) => Promise.resolve({ id, version: 4, state: 'stored', tested: true, testMessage: 'ok' }),
    rotate: () => Promise.resolve({ jobId: 'job-1' }),
    testReference: () => Promise.resolve({ found: true, resolvedVersion: '7', created: null, type: 'json', keys: ['password'], latencyMs: 18 }),
    createStore: (req) => Promise.resolve({ ...kitStores()[0], id: 'st-aws', kind: req.kind, name: req.name, config: req.config, references: [], signIn: [], issues: 0 }),
    connections: () => Promise.resolve({ items: [...ENVS].map(([name, env]) => ({ name, env })) }),
  };
  const secrets: Partial<SecretsApi> = {
    list: () => (calls.push('list'), world.list()),
    shortLived: () => world.shortLived(),
    stores: () => (calls.push('stores'), world.stores()),
    replace: (id, body) => {
      calls.push(`replace ${id}`);
      requests.push(body);
      return world.replace(id, body.value, body.testBefore ?? false, body.reason ?? null) as ReturnType<SecretsApi['replace']>;
    },
    rotate: (id, body) => (calls.push(`rotate ${id} ${body?.reason ?? null}`), world.rotate(id) as ReturnType<SecretsApi['rotate']>),
    testReference: (uri, store) => (calls.push(`test-ref ${uri} ${store ?? null}`), world.testReference(uri) as ReturnType<SecretsApi['testReference']>),
    setReference: (id, uri, store, reason) => (
      calls.push(`set-ref ${id} ${uri} ${store ?? null} ${reason ?? null}`),
      Promise.resolve({ id, name: 'n', owner: 'o', field: '/f', source: 'vault', ref: uri, store: store ?? null, remoteVersion: null, state: 'reference' })
    ),
    createStore: (req) => (calls.push(`create ${req.kind} ${req.name}`), requests.push(req), world.createStore(req)),
    testStore: (id) => (calls.push(`test-store ${id}`), Promise.resolve({ ok: true, latencyMs: 9, server: null, identity: null, permissions: [], hints: [], checks: [], error: null, reason: null })),
    deleteStore: (id, confirmName) => (calls.push(`delete ${id} ${confirmName}`), Promise.resolve({ referencesNotConnected: ['stage-db/password'], leasesRevoked: 1, secretsDestroyed: ['vault/approle-secret-id'] })),
    confirmStoreIntegrity: (id) => (calls.push(`keep ${id}`), Promise.resolve({ ...kitStores()[0], verified: true })),
  };
  const masterKey: Partial<MasterKeyApi> = {
    status: () => (calls.push('status'), world.status()),
    rotate: () => (calls.push('rotate-key'), Promise.resolve({ state: 'running', done: 0, total: 8, targetKeyId: 3920481776, error: null })),
    backup: (token, pass) => (calls.push(`backup ${token} ${pass}`), Promise.resolve({ path: '/Volumes/Vault/master-keyset-2026-10-22.json', at: '2026-10-22T12:30:00Z', keyIds: [2146530891], passphrase: !!pass, warnings: [] })),
    fixPermissions: () => (calls.push('fix'), Promise.resolve(kitMasterKey({ problems: [] }))),
  };
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: '**', children: [] }]),
      provideKitIcons(),
      { provide: SecretsApi, useValue: secrets },
      { provide: MasterKeyApi, useValue: masterKey },
      { provide: ConnectionsApi, useValue: { list: () => world.connections() } },
      { provide: EventStream, useValue: events },
      { provide: DesktopBridge, useFactory: () => desktop },
      { provide: CoreStore, useValue: { running: signal(true), instanceChanges: signal(0) } },
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

let fixture: ComponentFixture<SecretsPage>;

async function settle(): Promise<void> {
  for (let i = 0; i < 4; i++) {
    await new Promise((r) => setTimeout(r, 0));
    await fixture.whenStable();
  }
}

async function render(): Promise<HTMLElement> {
  fixture = TestBed.createComponent(SecretsPage);
  document.body.appendChild(fixture.nativeElement);
  await settle();
  return fixture.nativeElement as HTMLElement;
}

const text = (el: Element | null | undefined) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
const $ = <E extends Element = HTMLElement>(sel: string, root: ParentNode = document) => root.querySelector<E>(sel);
const $$ = <E extends Element = HTMLElement>(sel: string, root: ParentNode = document) => [...root.querySelectorAll<E>(sel)];
/** The row of a secret, by the name in its first cell. */
const row = (name: string) => $$('#secrets-table tbody tr').find((tr) => text(tr.querySelector('th')) === name)!;
/** A control by its visible words (sr-only parts included, as a screen reader reads it). */
const control = (root: ParentNode, words: string) => $$<HTMLElement>('button, a', root).find((b) => text(b).startsWith(words))!;
const toastTitles = () => TestBed.inject(ToastService).toasts().map((t) => t.title);

async function click(el: HTMLElement): Promise<void> {
  el.click();
  await settle();
}

async function type(el: HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement, value: string): Promise<void> {
  el.value = value;
  el.dispatchEvent(new Event('input', { bubbles: true }));
  if (el instanceof HTMLSelectElement) el.dispatchEvent(new Event('change', { bubbles: true }));
  await settle();
}

async function submit(dialog: string): Promise<void> {
  $<HTMLFormElement>(`${dialog} form`)!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
  await settle();
}

// ── Tests ─────────────────────────────────────────────────────────────────────────────────────────────────────────

describe('Secrets page: what it shows', () => {
  it('the kit’s world: master key facts, 11 secrets, two stores and a dashed AWS card, short-lived credentials', async () => {
    const el = await render();
    expect(text($('h1', el))).toBe('Secrets');

    const mk = $('#master-key', el)!;
    expect(text(mk)).toContain('/Users/you/.gaterook/master.keyset');
    expect(text(mk)).toContain('0400 Only you can read it');
    expect(text(mk)).toContain('2146530891');
    expect(text(mk)).toContain('On · the file is also encrypted with your macOS Keychain');
    expect(text(control(mk, '8 secrets stored here'))).toBe('8 secrets stored here');
    // backup-needed: Last backup is a warning, "12 days ago" on the core's clock.
    const backup = $('time', $$('.gw-kv__row', mk).find((r) => text(r).startsWith('Last backup')))!;
    expect(text(backup)).toBe('12 days ago');
    expect(backup.closest('.gw-status')!.classList).toContain('gw-status--warning');
    expect($$('.gw-card__body > .gw-stack > .gw-alert', mk)).toHaveLength(0);

    expect($$('#secrets-table tbody tr', el)).toHaveLength(11);
    expect(text($('#secret-count', el))).toBe('11 secrets');
    expect(text(row('billing-api/webhook-secret'))).toContain('AWS SM · not connected');

    expect(text($('#external-stores .gw-section__description', el))).toContain('2 of 3 connected.');
    expect(text($('#store-vault', el))).toContain('https://vault.acme.internal');
    expect(text($('#store-vault', el))).toContain('stage-db/password from vault://kv/stage/db#password');
    expect(text($('#store-gcp', el))).toContain('Connected');
    const aws = $('#store-aws', el)!;
    expect(aws.classList).toContain('gw-card--dashed');
    expect(text(aws)).toContain("billing-api/webhook-secret points here and can't be resolved until you connect.");

    expect($$('#short-lived article', el)).toHaveLength(3);
    expect(text($('#short-lived', el))).toContain('gw-runbooks');
    expect(text($('#short-lived', el))).not.toContain('prod-replica');
    expect(text($('#store-vault', el))).toContain('SSH certificates for 2 connections');
    expect(text(el)).not.toContain('database users');
  });

  it("what the core adds since step API: when the key was made, the role's ExternalId, the certificate's principal", async () => {
    world.status = () => Promise.resolve(kitMasterKey({ createdAt: '2026-07-14T08:02:00Z', rotatedAt: null }));
    world.shortLived = () =>
      Promise.resolve(
        kitShortLived().map((c) =>
          c.mechanism === 'AWS AssumeRole' ? { ...c, externalId: 'gw-ext-7c41d2' } : c.mechanism.startsWith('SSH') ? { ...c, principal: 'deploy' } : c,
        ),
      );
    const el = await render();
    const mk = $('#master-key', el)!;
    const created = $$('.gw-kv__row', mk).find((r) => text(r).startsWith('Created'))!;
    expect(text($('dd', created))).toBe('Jul 14, never rotated');
    expect($('time', created)!.getAttribute('datetime')).toBeTruthy();
    expect($$('.gw-kv__row', mk).some((r) => text(r).startsWith('Keys'))).toBe(false);
    const rows = (title: string) =>
      $$('.gw-kv__row', $$('#short-lived article', el).find((a) => text(a.querySelector('h3')) === title)!).map((r) => [text($('dt', r)), text($('dd', r))]);
    expect(rows('AWS AssumeRole with ExternalId').slice(0, 2)).toEqual([
      ['Role', 'arn:aws:iam::412398765012:role/gw-readonly'],
      ['ExternalId', 'gw-ext-7c41d2'],
    ]);
    expect(rows('SSH certificates from Vault CA').slice(0, 2)).toEqual([
      ['Signer', 'ssh-client-signer/sign/gw-runbooks'],
      ['Principal', 'deploy'],
    ]);
  });

  it('Rotate is offered only where the connector can set a value at the source; elsewhere it says why', async () => {
    await render();
    const enabled = control(row('dev-db/password'), 'Rotate');
    expect(enabled.getAttribute('aria-disabled')).toBeNull();

    const off = control(row('app-logs/token'), 'Rotate');
    expect(off.getAttribute('aria-disabled')).toBe('true');
    const why = document.getElementById(off.getAttribute('aria-describedby')!)!;
    expect(why.getAttribute('role')).toBe('tooltip');
    expect(text(why)).toBe("This connector can't issue a new value for itself. Replace it with a new value.");
    await click(off);
    expect($('#dlg-rotate-secret')!.hasAttribute('open')).toBe(false);
    // A reference rotates in its store, a not-connected one is connected first.
    expect(control(row('stage-db/password'), 'Test')).toBeTruthy();
    expect(control(row('billing-api/webhook-secret'), 'Connect store').getAttribute('href')).toContain('#store-aws');
    expect(control(row('dev-db/password'), 'View usage').getAttribute('href')).toContain('/activity?q=');
  });

  it('the master key’s problems in the kit’s words; Fix permissions asks the core and says what it did', async () => {
    world.status = () => Promise.resolve(kitMasterKey({ mode: '0644', problems: ['perms', 'synced-folder', 'no-os-protection'], osProtection: 'unavailable' }));
    const el = await render();
    const mk = $('#master-key', el)!;
    expect(text(mk)).toContain('0644 Other users can read it');
    expect(text(mk)).toContain('Permissions are 0644: other users on this computer can read the key');
    expect(text(mk)).toContain('OS protection unavailable');
    expect(text(mk)).toContain('The key is in a synced folder');
    expect(control(mk, 'Show the data folder').getAttribute('href')).toBe('/settings/general#settings-general');

    await click(control(mk, 'Fix permissions'));
    expect(calls).toContain('fix');
    expect(toastTitles()).toContain('Permissions fixed');
    expect(text(mk)).toContain('Only you can read it');
  });
});

describe('Secrets page: the table', () => {
  it('filters at once, says how many show, and Clear filters puts everything and the keyboard back', async () => {
    await render();
    const search = $<HTMLInputElement>('#secret-search')!;
    await type(search, 'billing');
    expect($$('#secrets-table tbody tr')).toHaveLength(2);
    expect(text($('#secret-count'))).toBe('2 of 11 secrets');

    await type($<HTMLSelectElement>('#secret-source')!, 'vault');
    expect(text($('#secrets-table tbody'))).toContain('No secrets match');
    await click($<HTMLButtonElement>('#secrets-clear')!);
    expect($$('#secrets-table tbody tr')).toHaveLength(11);
    expect(search.value).toBe('');
    expect($<HTMLSelectElement>('#secret-source')!.selectedOptions[0]?.textContent).toBe('All sources');
    expect(document.activeElement).toBe(search);
  });

  it('sorts by Name and Last rotated; never rotated counts as the oldest', async () => {
    await render();
    const names = () => $$('#secrets-table tbody th').map((th) => text(th));
    expect(names()[0]).toBe('app-logs/token');
    const rotated = $<HTMLElement>('th[data-sort-key="rotated"] button')!;
    await click(rotated);
    expect($('th[data-sort-key="rotated"]')!.getAttribute('aria-sort')).toBe('ascending');
    expect(names()[0]).toBe('billing-api/webhook-secret');
    await click(rotated);
    expect(names()[0]).toBe('dev-db/password');
  });

  it('a section with nothing stored says where secrets come from', async () => {
    world.list = () => Promise.resolve([]);
    const el = await render();
    expect(text($('#stored-secrets', el))).toContain('No secrets yet');
    expect(control($('#stored-secrets', el)!, 'Add connection').getAttribute('href')).toBe('/connections/new');
    expect($('#secrets-table', el)).toBeNull();
  });
});

describe('Secrets page: Replace and Rotate', () => {
  it('Replace: the keyboard starts in the value; the value is read once, sent, and gone; the opener gets the focus back', async () => {
    await render();
    const opener = control(row('dev-db/password'), 'Replace');
    await click(opener);
    const dialog = $<HTMLDialogElement>('#dlg-replace-secret')!;
    expect(dialog.hasAttribute('open')).toBe(true);
    const value = $<HTMLInputElement>('#replace-value')!;
    expect(value.type).toBe('password');
    expect(document.activeElement).toBe(value);
    expect($('[gwReveal]', dialog)).toBeNull(); // no "show" for a value that replaces a stored one

    // Empty: the field says so, nothing is sent.
    await submit('#dlg-replace-secret');
    expect(text($('#replace-value-err'))).toBe('Enter the new value.');
    expect(calls.some((c) => c.startsWith('replace'))).toBe(false);

    value.value = 'n3w-s3cr3t';
    await submit('#dlg-replace-secret');
    expect(requests).toEqual([{ value: 'n3w-s3cr3t', testBefore: true, reason: null }]);
    expect(dialog.hasAttribute('open')).toBe(false);
    expect(toastTitles()).toContain('New value saved');
    expect(document.activeElement).toBe(opener);
    expect(document.body.innerHTML).not.toContain('n3w-s3cr3t');
  });

  it('Replace on prod needs a comment; a failed test keeps the dialog, the old version and the value for another try', async () => {
    world.list = () => Promise.resolve(kitSecrets().map((r) => (r.name === 'dev-db/password' ? { ...r, owner: 'prod-replica' } : r)));
    world.replace = (id) => Promise.resolve({ id, version: 4, state: 'testing', tested: false, testMessage: 'password authentication failed' });
    await render();
    await click(control(row('dev-db/password'), 'Replace'));
    $<HTMLInputElement>('#replace-value')!.value = 'wrong';
    await submit('#dlg-replace-secret');
    const reason = $<HTMLTextAreaElement>('#secret-reason')!;
    expect(text($('#secret-reason-err'))).toBe('Add a comment: it is required to change a secret on prod.');
    expect(document.activeElement).toBe(reason);
    expect(requests).toHaveLength(0);

    await type(reason, 'Rotating after the leak report');
    await submit('#dlg-replace-secret');
    expect(requests).toEqual([{ value: 'wrong', testBefore: true, reason: 'Rotating after the leak report' }]);
    const alert = $('#dlg-replace-secret [role="alert"]')!;
    expect(text(alert)).toContain("prod-replica didn't accept the new value");
    expect(text(alert)).toContain('password authentication failed. Version 3 stays in use.');
    expect($('#dlg-replace-secret')!.hasAttribute('open')).toBe(true);

    // Closing takes the typed value with it.
    await click(control($('#dlg-replace-secret')!, 'Cancel'));
    expect($<HTMLInputElement>('#replace-value')).toBeNull();
  });

  it('Replace whose owner turns out to be prod: the core’s demand for a comment shows the field, with the value kept', async () => {
    // Review 2026-10-26: without Connections the page doesn't know the owner is prod and offers no Comment; the core's
    // 422 at /reason went to a field that wasn't on screen, so Save seemed to do nothing.
    world.connections = () => Promise.reject(problem(500, { title: 'Server error', detail: 'boom' }));
    world.replace = (_id, _value, _test, reason) =>
      reason
        ? Promise.resolve({ id: 's', version: 4, state: 'stored', tested: null, testMessage: null })
        : Promise.reject(problem(422, { title: 'Comment required', detail: 'Add a comment: it is required to change a secret on prod', field: '/reason' }));
    await render();
    await click(control(row('dev-db/password'), 'Replace'));
    expect($('#secret-reason')).toBeNull();
    const value = $<HTMLInputElement>('#replace-value')!;
    value.value = 'n3w';
    await submit('#dlg-replace-secret');
    const reason = $<HTMLTextAreaElement>('#secret-reason')!;
    expect(reason).not.toBeNull();
    expect(text($('#secret-reason-err'))).toBe('Add a comment: it is required to change a secret on prod');
    expect(document.activeElement).toBe(reason);
    expect($<HTMLInputElement>('#replace-value')!.value).toBe('n3w');

    await type(reason, 'Leak drill');
    await submit('#dlg-replace-secret');
    expect(requests.at(-1)).toEqual({ value: 'n3w', testBefore: true, reason: 'Leak drill' });
    expect($('#dlg-replace-secret')!.hasAttribute('open')).toBe(false);
  });

  it('Rotate: Cancel has the focus; Rotate asks the core; a refusal is the core’s reason', async () => {
    await render();
    await click(control(row('dev-db/password'), 'Rotate'));
    const dialog = $('#dlg-rotate-secret')!;
    expect(text($('#dlg-rotate-secret-d'))).toBe(
      'The connector sets a new value at the source, stores it as version 4 and tests dev-db. Version 3 keeps working until the test passes.',
    );
    expect(document.activeElement).toBe(control(dialog, 'Cancel'));
    await click(control(dialog, 'Rotate dev-db/password'));
    expect(calls).toContain('rotate id-dev-db/password null');
    expect(toastTitles()).toContain('Rotating dev-db/password');

    world.rotate = () => Promise.reject(problem(409, { title: 'Rotation running', detail: 'dev-db/password is rotating already.' }));
    await click(control(row('dev-db/password'), 'Rotate'));
    await click(control($('#dlg-rotate-secret')!, 'Rotate dev-db/password'));
    expect(TestBed.inject(ToastService).toasts().find((t) => t.title === "Can't rotate dev-db/password")?.body).toBe('dev-db/password is rotating already.');
  });

  it('Replace reference: checks the scheme, tests it without keeping the value, saves it for its store', async () => {
    await render();
    await click(control(row('stage-db/password'), 'Replace'));
    const field = $<HTMLInputElement>('#replace-ref')!;
    expect(document.activeElement).toBe(field);
    expect(field.value).toBe('vault://kv/stage/db#password');

    await type(field, 'kv/stage/db2');
    await click(control($('#dlg-replace-ref')!, 'Test reference'));
    expect(text($('#replace-ref-err'))).toBe('A reference starts with vault://, gcp-sm:// or aws-sm://.');
    expect(calls.some((c) => c.startsWith('test-ref'))).toBe(false);

    await type(field, 'vault://kv/stage/db2#password');
    await click(control($('#dlg-replace-ref')!, 'Test reference'));
    expect(calls).toContain('test-ref vault://kv/stage/db2#password vault');
    expect(text($('#replace-ref-result'))).toBe('Found · v7 · keys password. The value was neither shown nor kept.');

    await submit('#dlg-replace-ref');
    expect(calls).toContain('set-ref id-stage-db/password vault://kv/stage/db2#password vault null');
    expect(toastTitles()).toContain('Reference saved');
  });

  it('Test in a row is busy in place and its result is a toast', async () => {
    let answer!: (v: unknown) => void;
    world.testReference = () => new Promise((r) => (answer = r));
    await render();
    const test = control(row('stage-db/password'), 'Test');
    await click(test);
    expect(test.getAttribute('aria-busy')).toBe('true');
    answer({ found: true, resolvedVersion: '7', created: null, type: 'json', keys: [], latencyMs: 18 });
    await settle();
    expect(control(row('stage-db/password'), 'Test').getAttribute('aria-busy')).toBeNull();
    expect(toastTitles()).toContain('Reference stage-db/password works');
  });
});

describe('Secrets page: external stores', () => {
  async function openVault(address: string): Promise<HTMLInputElement> {
    world.stores = () => Promise.resolve([]);
    await render();
    await click(control($('#store-vault')!, 'Connect'));
    await type($<HTMLInputElement>('#vault-address')!, address);
    await type($<HTMLSelectElement>('#vault-method')!, 'token');
    const token = $<HTMLInputElement>('#vault-token')!;
    token.value = 'test-vault-token';
    return token;
  }

  it('HTTP Vault waits for confirmation before creating or testing; Cancel preserves the form and returns focus', async () => {
    const token = await openVault('http://192.0.2.10:8200/');
    await submit('#dlg-connect-store');
    const warning = $<HTMLDialogElement>('#dlg-vault-http')!;
    expect(warning.open).toBe(true);
    expect(warning.classList).toContain('gw-dialog--warning');
    expect(text(warning)).toContain('http://192.0.2.10:8200/');
    expect(text(warning)).toContain('Sign-in credentials and secrets will travel without encryption');
    expect(text(warning)).not.toContain(token.value);
    expect(document.activeElement).toBe(control(warning, 'Cancel'));
    expect(calls.some((c) => c.startsWith('create') || c.startsWith('test-store'))).toBe(false);

    await click(control(warning, 'Cancel'));
    expect(warning.open).toBe(false);
    expect($<HTMLDialogElement>('#dlg-connect-store')!.open).toBe(true);
    expect(token.value).toBe('test-vault-token');
    expect(document.activeElement).toBe(control($('#dlg-connect-store')!, 'Test and connect'));
    expect(requests).toHaveLength(0);

    await type($<HTMLInputElement>('#vault-address')!, 'http://vault.acme.internal:8200');
    await submit('#dlg-connect-store');
    expect(warning.open).toBe(true);
    expect(text(warning)).toContain('http://vault.acme.internal:8200');
    await click(control(warning, 'Connect over HTTP'));
    expect(requests).toEqual([{
      kind: 'vault', name: 'vault',
      config: { address: 'http://vault.acme.internal:8200', auth: { method: 'token' }, allowPlainHttp: true },
      secrets: { '/auth/token': 'test-vault-token' },
    }]);
    expect(calls).toContain('test-store st-aws');
    expect(warning.open).toBe(false);
    expect($<HTMLDialogElement>('#dlg-connect-store')!.open).toBe(false);
    expect(token.value).toBe('');
  });

  it('HTTPS Vault connects without an HTTP warning or opt-in', async () => {
    await openVault('https://vault.acme.internal/');
    await submit('#dlg-connect-store');
    expect($<HTMLDialogElement>('#dlg-vault-http')!.open).toBe(false);
    expect((requests[0] as SecretStoreCreate).config).toEqual({ address: 'https://vault.acme.internal', auth: { method: 'token' } });
    expect(calls).toContain('test-store st-aws');
  });

  it('local HTTP Vault also asks first, and dismissing the warning never connects', async () => {
    await openVault('http://localhost:8200');
    await submit('#dlg-connect-store');
    const warning = $<HTMLDialogElement>('#dlg-vault-http')!;
    expect(warning.open).toBe(true);
    // Native Escape closes a dialog without a return value.
    warning.dispatchEvent(new Event('cancel', { cancelable: true }));
    warning.close();
    await settle();
    expect(requests).toHaveLength(0);
    await submit('#dlg-connect-store');
    expect(warning.open).toBe(true);
    expect(document.activeElement).toBe(control(warning, 'Cancel'));
    await click(control(warning, 'Cancel'));
    await type($<HTMLInputElement>('#vault-address')!, 'https://vault.acme.internal');
    await submit('#dlg-connect-store');
    expect((requests[0] as SecretStoreCreate).config['allowPlainHttp']).toBeUndefined();
  });

  it('HTTP consent applies to the displayed address and a single attempt', async () => {
    await openVault('http://vault.acme.internal');
    await submit('#dlg-connect-store');
    const warning = $<HTMLDialogElement>('#dlg-vault-http')!;
    // Browsers queue the close event; the shared jsdom shim dispatches it synchronously.
    const close = vi.spyOn(warning, 'close').mockImplementation((value?: string) => {
      if (!warning.open) return;
      if (value !== undefined) warning.returnValue = value;
      warning.removeAttribute('open');
      setTimeout(() => warning.dispatchEvent(new Event('close')), 0);
    });
    // Programmatic changes must not reuse a confirmation for a different destination.
    await type($<HTMLInputElement>('#vault-address')!, 'http://vault2.acme.internal');
    await click(control(warning, 'Connect over HTTP'));
    expect(requests).toHaveLength(0);
    expect(warning.open).toBe(true);
    expect(text(warning)).toContain('http://vault2.acme.internal');
    world.createStore = () => Promise.reject(problem(422, { title: 'Invalid store', detail: 'Fix the store settings.' }));
    await click(control(warning, 'Connect over HTTP'));
    expect(requests).toHaveLength(1);
    expect(text($('#dlg-connect-store'))).toContain('Fix the store settings.');
    await submit('#dlg-connect-store');
    expect(warning.open).toBe(true);
    expect(requests).toHaveLength(1);
    await click(control(warning, 'Cancel'));
    await click(control($('#dlg-connect-store')!, 'Cancel'));
    await click(control($('#store-vault')!, 'Connect'));
    expect($<HTMLInputElement>('#vault-address')!.value).toBe('');
    expect(warning.open).toBe(false);
    close.mockRestore();
  });

  it('HTTP confirmation cannot submit the same connection twice while the request is pending', async () => {
    await openVault('http://vault.acme.internal');
    await submit('#dlg-connect-store');
    world.createStore = () => never<SecretStore>();
    const confirm = control($('#dlg-vault-http')!, 'Connect over HTTP');
    confirm.click();
    confirm.click();
    await settle();
    await submit('#dlg-connect-store');
    expect(requests).toHaveLength(1);
    expect(control($('#dlg-connect-store')!, 'Test and connect').getAttribute('aria-busy')).toBe('true');
  });

  it('until the core has the stores’ API it says they aren’t available yet, and the rest works', async () => {
    world.stores = () => Promise.reject(notAvailable());
    const el = await render();
    expect(text($('#external-stores', el))).toContain("External stores aren't available yet");
    expect($('#store-vault', el)).toBeNull();
    expect($$('#secrets-table tbody tr', el)).toHaveLength(11);
  });

  it('Connect AWS: Role ARN has the focus, the ExternalId is shown to copy, the request carries the kind’s settings only', async () => {
    await render();
    const opener = control($('#store-aws')!, 'Connect');
    await click(opener);
    const dialog = $<HTMLDialogElement>('#dlg-connect-store')!;
    expect(text($('#dlg-connect-store-t'))).toBe('Connect AWS Secrets Manager');
    expect(document.activeElement).toBe($('#aws-role'));
    // The waiting reference names eu-central-1: the store starts there.
    expect($<HTMLSelectElement>('#aws-region')!.value).toBe('eu-central-1');
    const ext = text($('#aws-ext'));
    expect(ext).toMatch(/^gw-ext-[0-9a-f]{12}$/);

    await submit('#dlg-connect-store');
    expect(text($('#aws-role-err'))).toMatch(/^Enter the role/);
    expect(document.activeElement).toBe($('#aws-role'));
    expect(calls.some((c) => c.startsWith('create'))).toBe(false);

    await type($<HTMLInputElement>('#aws-role')!, 'arn:aws:iam::412398765012:role/gw-secrets-reader');
    await submit('#dlg-connect-store');
    expect(calls).toEqual(expect.arrayContaining(['create aws-sm aws-eu-central-1', 'test-store st-aws']));
    expect(requests.at(-1)).toEqual({
      kind: 'aws-sm',
      name: 'aws-eu-central-1',
      config: { region: 'eu-central-1', credentials: { source: 'profile', profile: 'default', roleArn: 'arn:aws:iam::412398765012:role/gw-secrets-reader', externalId: ext } },
    });
    expect(dialog.hasAttribute('open')).toBe(false);
    expect(toastTitles()).toContain('AWS Secrets Manager connected');
  });

  it('Connect AWS with an access key: the secret key is read from its field when sent and cleared after', async () => {
    await render();
    await click(control($('#store-aws')!, 'Connect'));
    await type($<HTMLSelectElement>('#aws-source')!, 'accessKey');
    await type($<HTMLInputElement>('#aws-key-id')!, 'AKIAABCDEFGHIJKLMNOP');
    await type($<HTMLInputElement>('#aws-role')!, 'arn:aws:iam::412398765012:role/gw-secrets-reader');
    await submit('#dlg-connect-store');
    expect(text($('#aws-secret-err'))).toBe('Enter the secret access key.');

    const secret = $<HTMLInputElement>('#aws-secret')!;
    secret.value = 'wJalrXUtnFEMI/K7MDENG';
    await submit('#dlg-connect-store');
    const req = requests.at(-1) as SecretStoreCreate;
    expect(req.secrets).toEqual({ '/credentials/secretAccessKey': 'wJalrXUtnFEMI/K7MDENG' });
    expect(JSON.stringify(req.config)).not.toContain('wJalrXUtnFEMI');
    expect(secret.value).toBe('');
  });

  it('Disconnect: Cancel has the focus; it names the store again; the card turns dashed and gets the keyboard', async () => {
    await render();
    const opener = control($('#store-vault')!, 'Disconnect');
    await click(opener);
    const dialog = $('#dlg-disconnect-store')!;
    expect(dialog.classList).toContain('gw-dialog--danger');
    expect(text($('#dlg-disconnect-store-d'))).toContain('prod-replica and stage-db fail on their next call: their credentials come from HashiCorp Vault.');
    expect(document.activeElement).toBe(control(dialog, 'Cancel'));

    await click(control(dialog, 'Cancel'));
    expect(document.activeElement).toBe(opener);
    expect(calls.some((c) => c.startsWith('delete'))).toBe(false);

    await click(opener);
    world.stores = () => Promise.resolve(kitStores().filter((st) => st.name !== 'vault'));
    await click(control($('#dlg-disconnect-store')!, 'Disconnect HashiCorp Vault'));
    expect(calls).toContain('delete st-vault vault');
    expect($('#store-vault')!.classList).toContain('gw-card--dashed');
    expect(document.activeElement).toBe(control($('#store-vault')!, 'Connect'));
    expect(toastTitles()).toContain('HashiCorp Vault disconnected');
  });

  it('a store changed outside GateRook isn’t tested until it is kept', async () => {
    world.stores = () => Promise.resolve(kitStores().map((s) => (s.name === 'vault' ? { ...s, verified: false } : s)));
    await render();
    const card = $('#store-vault')!;
    expect(text(card)).toContain('Changed outside GateRook');
    const test = control(card, 'Test');
    expect(test.getAttribute('aria-disabled')).toBe('true');
    await click(test);
    expect(calls.some((c) => c.startsWith('test-store'))).toBe(false);

    await click(control(card, 'Keep as stored'));
    const dialog = $('#dlg-keep-store')!;
    expect(dialog.classList).toContain('gw-dialog--warning');
    expect(document.activeElement).toBe(control(dialog, 'Cancel'));
    await click(control(dialog, 'Keep as stored'));
    expect(calls).toContain('keep st-vault');
  });
});

describe('Secrets page: master key backup and rotation', () => {
  it('Back up outside the desktop app: the dialog explains it can’t save, and nothing is asked of the core', async () => {
    await render();
    await click(control($('#master-key')!, 'Back up key'));
    const dialog = $('#dlg-backup-key')!;
    expect(document.activeElement).toBe($('#backup-pass'));
    const save = control(dialog, 'Choose where to save');
    expect(save.getAttribute('aria-disabled')).toBe('true');
    expect(text(document.getElementById(save.getAttribute('aria-describedby')!))).toContain('needs the GateRook desktop app');
    await submit('#dlg-backup-key');
    expect(calls.some((c) => c.startsWith('backup'))).toBe(false);
  });

  it('Back up in the desktop app: the save dialog’s token and the passphrase go once; the passphrase leaves the page', async () => {
    configureDesktop();
    await render();
    await click(control($('#master-key')!, 'Back up key'));
    const pass = $<HTMLInputElement>('#backup-pass')!;
    pass.value = 'correct horse battery';
    await submit('#dlg-backup-key');
    expect(calls).toContain('backup save_tok_1 correct horse battery');
    expect($('#dlg-backup-key')!.hasAttribute('open')).toBe(false);
    expect(pass.value).toBe('');
    expect(toastTitles()).toContain('Master key backed up, encrypted with your passphrase');
  });

  it('Rotate key: four steps with Cancel focused, then progress from the core’s events, then Back up key', async () => {
    await render();
    const opener = control($('#master-key')!, 'Rotate key');
    await click(opener);
    expect($$('#dlg-rotate-key .gw-stepper__step')).toHaveLength(4);
    expect(document.activeElement).toBe(control($('#dlg-rotate-key')!, 'Cancel'));

    await submit('#dlg-rotate-key');
    expect(calls).toContain('rotate-key');
    const progress = $('#dlg-rotate-progress')!;
    expect(progress.hasAttribute('open')).toBe(true);
    expect($('#dlg-rotate-key')!.hasAttribute('open')).toBe(false);

    events.emit('master-key.rotation', { state: 'running', done: 5, total: 8 });
    await settle();
    expect(text($('#rotate-progress-label'))).toBe('Re-wrapping data keys · 5 of 8');
    const bar = $('[role="progressbar"]', progress)!;
    expect(bar.getAttribute('aria-valuenow')).toBe('5');
    expect(bar.getAttribute('aria-valuemax')).toBe('8');

    // Continue in background: the card shows the progress, Rotate key waits and says why.
    await click(control(progress, 'Continue in background'));
    expect(document.activeElement).toBe(opener);
    expect(opener.getAttribute('aria-disabled')).toBe('true');
    expect(text(document.getElementById(opener.getAttribute('aria-describedby')!))).toContain('Re-wrapping data keys · 5 of 8');

    world.status = () => Promise.resolve(kitMasterKey({ keys: [{ id: 3920481776, status: 'enabled', primary: true }, { id: 2146530891, status: 'enabled', primary: false }], rotation: { state: 'done', done: 8, total: 8, targetKeyId: 3920481776, error: null } }));
    await click(control($('#master-key')!, 'Show progress'));
    events.emit('master-key.rotation', { state: 'done', done: 8, total: 8 });
    await settle();
    expect(text($('#dlg-rotate-progress-t'))).toBe('Master key rotated');
    expect(control(progress, 'Close')).toBeTruthy();
    await click(control(progress, 'Back up key'));
    expect($('#dlg-backup-key')!.hasAttribute('open')).toBe(true);
    expect(text($('#master-key'))).toContain('3920481776');
  });
});

describe('Secrets page: loading, errors and links', () => {
  it('placeholders only after 300 ms; a part that fails says so with Try again, the others show', async () => {
    let answer!: (v: SecretRow[]) => void;
    world.list = () => new Promise((r) => (answer = r));
    world.status = () => never();
    const el = await render();
    expect($('#master-key', el)).toBeNull();
    await new Promise((r) => setTimeout(r, SKELETON_DELAY_MS + 50));
    await settle();
    expect($('#master-key', el)!.getAttribute('aria-busy')).toBe('true');
    expect($('#stored-secrets .gw-skeleton', el)).not.toBeNull();
    expect(text($('[role="status"].gw-sr-only', el))).toBe('Loading secrets');
    answer(kitSecrets());
    await settle();
    expect($$('#secrets-table tbody tr', el)).toHaveLength(11);
  });

  it('a failed first load is the part’s error; Try again loads it', async () => {
    world.list = () => Promise.reject(problem(500, { title: 'Internal error', detail: 'The database is busy.' }));
    const el = await render();
    const section = $('#stored-secrets', el)!;
    expect(text(section)).toContain("The stored secrets didn't load");
    expect(text(section)).toContain('The database is busy.');
    world.list = () => Promise.resolve(kitSecrets());
    await click(control(section, 'Try again'));
    expect($$('#secrets-table tbody tr', el)).toHaveLength(11);
  });

  it('a link to a section of the page takes the keyboard there once the data is in', async () => {
    await TestBed.inject(Router).navigateByUrl('/secrets#external-stores');
    await render();
    expect(document.activeElement).toBe($('#external-stores'));
  });
});

function configureDesktop(): void {
  desktop.available = true;
  desktop.platform = 'linux';
  desktop.saveFile = (purpose) => (calls.push(`save-file ${purpose}`), Promise.resolve('save_tok_1'));
}
