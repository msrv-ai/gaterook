import { Component } from '@angular/core';
import { provideLocationMocks } from '@angular/common/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { App } from '../app';
import { problemJsonInterceptor } from '../core/api/problem-json.interceptor';
import { Onboarding } from '../core/api/overview.api';
import { DesktopBridge } from '../core/desktop/desktop-bridge';
import { EVENT_STREAM_FETCH, EventStream } from '../core/events/event-stream';
import { DEMO_CONSENT as REMOTE_CONSENT } from '../dev/fixtures-clients';
import { ACTIVATION_DELAY_MS } from '../kit/activation-guard';
import { provideKitIcons } from '../kit/icons';
import { ToastService } from '../kit/toast.service';
import { requestToast } from './desktop-notice-toasts';
import { onboardingGuard, resetOnboardingGuard } from './onboarding.guard';

/**
 * What the window holds for every page: the OAuth consent dialog of a local app sign-in,
 * the in-app toast of a desktop notification while
 * the window is in front (§7.4, kit approval-notification "In-app toast"), and one decision where a start opens
 * (§8.1: Welcome on a first run, whatever else navigates meanwhile).
 */

const DEMO_CONSENT = { ...REMOTE_CONSENT, ingress: 'LOCAL' as const, clientHost: 'Cursor', redirectHost: '127.0.0.1' };

const HEALTH = { status: 'ok', version: '0.4.0', uptimeMs: 60_000, ports: { local: 7465, public: 7466 }, runtime: 'jvm', warnings: [] };

@Component({ template: '<p>page</p>' })
class Page {}

const desktop: Partial<DesktopBridge> = {
  available: false,
  platform: 'web',
  onDeepLink: () => () => undefined,
  onCoreState: () => () => undefined,
  onEvent: () => null,
  setNativeTheme: () => undefined,
};
// An event stream that never connects: the tests emit events themselves.
const neverFetch = (() => new Promise<Response>(() => undefined)) as typeof fetch;
const settle = () => new Promise((r) => setTimeout(r));
const text = (el: Element | null) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
const button = (root: ParentNode, name: string) =>
  Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find((b) => text(b) === name)!;

function configure(routes: Parameters<typeof provideRouter>[0]): void {
  TestBed.configureTestingModule({
    imports: [App],
    providers: [
      provideRouter(routes),
      provideHttpClient(withInterceptors([problemJsonInterceptor])),
      provideHttpClientTesting(),
      provideKitIcons(),
      { provide: DesktopBridge, useValue: desktop },
      { provide: EVENT_STREAM_FETCH, useValue: neverFetch },
    ],
  });
}

/** The App on a core that answers health: the frame, the stores and the consent list start. */
async function runningApp() {
  const http = TestBed.inject(HttpTestingController);
  const fixture = TestBed.createComponent(App);
  document.body.appendChild(fixture.nativeElement);
  await fixture.whenStable();
  await settle();
  http.match('/api/health').forEach((r) => r.flush(HEALTH));
  await settle();
  await fixture.whenStable();
  return { fixture, http, el: fixture.nativeElement as HTMLElement };
}

let clock = 0;
beforeEach(() => {
  clock = 1_000;
  vi.spyOn(performance, 'now').mockImplementation(() => clock);
});
afterEach(() => {
  TestBed.inject(HttpTestingController).match(() => true);
  vi.restoreAllMocks();
  document.body.innerHTML = '';
});

describe('titlebar navigation', () => {
  beforeEach(() => {
    configure([
      { path: 'overview', component: Page, data: { section: 'Overview' } },
      { path: 'connections', component: Page, data: { section: 'Connections' } },
    ]);
    TestBed.configureTestingModule({ providers: [provideLocationMocks()] });
  });

  it('enables Back after visiting another page and restores the previous section', async () => {
    const { fixture, el } = await runningApp();
    const router = TestBed.inject(Router);
    router.setUpLocationChangeListener();
    await router.navigateByUrl('/overview');
    await fixture.whenStable();
    const back = el.querySelector<HTMLButtonElement>('.gw-titlebar button[aria-label="Go back"]')!;
    expect(back.disabled).toBe(true);
    expect(back.title).toBe('No previous page');

    await router.navigateByUrl('/connections');
    await fixture.whenStable();
    expect(text(el.querySelector('.gw-titlebar__title'))).toBe('Connections');
    expect(back.disabled).toBe(false);

    back.click();
    await vi.waitFor(() => expect(router.url).toBe('/overview'));
    await fixture.whenStable();
    expect(text(el.querySelector('.gw-titlebar__title'))).toBe('Overview');
    expect(back.disabled).toBe(true);
  });
});

describe('the OAuth consent dialog is the window’s, on any page (§7.3)', () => {
  beforeEach(() => configure([{ path: '**', component: Page }]));

  async function withConsent() {
    const app = await runningApp();
    const consents = app.http.match('/api/oauth/consents');
    expect(consents.length).toBe(1);
    consents[0].flush([{ ...REMOTE_CONSENT, id: 'oc_remote', ingress: 'TUNNEL' }, DEMO_CONSENT]);
    await settle();
    await app.fixture.whenStable();
    await Promise.resolve();
    const dialog = app.el.querySelector<HTMLDialogElement>('gw-oauth-consent dialog')!;
    return { ...app, dialog };
  }

  it('opens for the oldest sign-in with Deny focused; Allow waits 700 ms and sends the core’s decision', async () => {
    const { fixture, http, dialog } = await withConsent();
    TestBed.inject(Router).navigateByUrl('/settings/general');
    await fixture.whenStable();
    expect(dialog.hasAttribute('open')).toBe(true);
    expect(text(dialog.querySelector('.gw-dialog__title'))).toBe('Allow Cursor to sign in to stage-readonly?');
    expect(text(dialog)).toContain('4F7-2KQ');
    expect(document.activeElement).toBe(button(dialog, 'Deny'));
    // Outside the frame: the status screen's inert never covers it.
    expect(dialog.closest('.gw-shell')).toBeNull();

    button(dialog, 'Allow access').click();
    expect(http.match((r) => r.url.startsWith('/api/oauth/consents/')).length).toBe(0);
    expect(dialog.hasAttribute('open')).toBe(true);

    clock += ACTIVATION_DELAY_MS;
    button(dialog, 'Allow access').click();
    await settle();
    const answer = http.expectOne('/api/oauth/consents/oc_4F72KQ');
    expect(answer.request.method).toBe('POST');
    expect(answer.request.body).toEqual({ decision: 'allow' });
    answer.flush(null, { status: 204, statusText: 'No Content' });
    await settle();
    await fixture.whenStable();
    expect(dialog.hasAttribute('open')).toBe(false);
  });

  it('Enter never allows: "Allow access" swallows it; Deny, Esc and the close button deny', async () => {
    const { fixture, http, dialog } = await withConsent();
    clock += ACTIVATION_DELAY_MS;
    const enter = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true });
    button(dialog, 'Allow access').dispatchEvent(enter);
    expect(enter.defaultPrevented).toBe(true);

    dialog.dispatchEvent(new Event('cancel', { cancelable: true }));
    dialog.close();
    await settle();
    const answer = http.expectOne('/api/oauth/consents/oc_4F72KQ');
    expect(answer.request.body).toEqual({ decision: 'deny' });
    answer.flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
  });

  it('a request answered elsewhere closes the dialog without an answer; the next one gets Deny focused again', async () => {
    const { fixture, http, dialog } = await withConsent();
    const events = TestBed.inject(EventStream);
    // A second request arrives while the first one is open.
    events.emit('oauth.consent.requested', { id: 'oc_9ZZ1AB' });
    await settle();
    http.expectOne('/api/oauth/consents').flush([DEMO_CONSENT, { ...DEMO_CONSENT, id: 'oc_9ZZ1AB', code: '9ZZ-1AB' }]);
    await settle();
    await fixture.whenStable();
    button(dialog, 'Allow access').focus();

    events.emit('oauth.consent.resolved', { id: 'oc_4F72KQ', decision: 'expired' });
    await fixture.whenStable();
    await Promise.resolve();
    expect(dialog.hasAttribute('open')).toBe(true);
    expect(text(dialog)).toContain('9ZZ-1AB');
    expect(document.activeElement).toBe(button(dialog, 'Deny'));
    // The delay starts again for the new request.
    button(dialog, 'Allow access').click();
    expect(dialog.hasAttribute('open')).toBe(true);

    events.emit('oauth.consent.resolved', { id: 'oc_9ZZ1AB', decision: 'denied' });
    await fixture.whenStable();
    expect(dialog.hasAttribute('open')).toBe(false);
    expect(http.match((r) => r.url.startsWith('/api/oauth/consents/')).length).toBe(0);
  });

  it('an answer that came too late says so; one that didn’t arrive shows the request again', async () => {
    const { fixture, http, dialog } = await withConsent();
    const toasts = TestBed.inject(ToastService);
    button(dialog, 'Deny').click();
    await settle();
    http.expectOne('/api/oauth/consents/oc_4F72KQ').flush(
      { title: 'Already answered', detail: 'This sign-in was answered or expired.' },
      { status: 409, statusText: 'Conflict', headers: { 'Content-Type': 'application/problem+json' } },
    );
    await settle();
    expect(toasts.toasts().map((t) => t.title)).toContain('The sign-in request was closed');

    // A second request whose answer fails: the list is read again and the request comes back.
    TestBed.inject(EventStream).emit('oauth.consent.requested', { id: 'oc_9ZZ1AB' });
    await settle();
    http.expectOne('/api/oauth/consents').flush([{ ...DEMO_CONSENT, id: 'oc_9ZZ1AB', code: '9ZZ-1AB' }]);
    await settle();
    await fixture.whenStable();
    await Promise.resolve();
    button(dialog, 'Deny').click();
    await settle();
    http.expectOne('/api/oauth/consents/oc_9ZZ1AB').flush(
      { title: 'Server error', detail: 'Something failed.' },
      { status: 500, statusText: 'Server Error', headers: { 'Content-Type': 'application/problem+json' } },
    );
    await settle();
    http.expectOne('/api/oauth/consents').flush([{ ...DEMO_CONSENT, id: 'oc_9ZZ1AB', code: '9ZZ-1AB' }]);
    await settle();
    await fixture.whenStable();
    await Promise.resolve();
    expect(toasts.toasts().map((t) => t.title)).toContain('Your answer didn’t reach GateRook');
    expect(dialog.hasAttribute('open')).toBe(true);
    expect(text(dialog)).toContain('9ZZ-1AB');
  });
});

describe('the in-app toast of a desktop notification (§7.4, kit "In-app toast")', () => {
  beforeEach(() => configure([{ path: '**', component: Page }]));

  const REQUEST = {
    id: 'ap_h7hbxuq2597l',
    tool: 'sql_execute',
    connection: 'dev-db',
    endpoint: 'dev',
    env: 'dev',
    client: 'codex',
    status: 'pending',
    expiresAt: '2026-10-22T14:34:12+02:00',
  };

  it('says what the kit says: tool on connection, environment, client and the auto-deny time', () => {
    const t = requestToast(REQUEST as never);
    expect(t.title).toBe('Approval needed');
    expect(t.body).toMatch(/^sql_execute on dev-db \(dev\) from Codex\. Auto-deny at \d\d:\d\d:\d\d\.$/);
    expect(requestToast({ ...REQUEST, env: 'prod', client: null } as never).title).toBe('PROD · Approval needed');
  });

  it('in front: a toast with Review only, which opens the request; a decision elsewhere takes it away', async () => {
    const { fixture, http } = await runningApp();
    vi.spyOn(document, 'hasFocus').mockReturnValue(true);
    const toasts = TestBed.inject(ToastService);
    const events = TestBed.inject(EventStream);
    events.emit('notify.desktop', { kind: 'request', id: REQUEST.id, env: 'dev', prod: false, title: 'Approval needed · sql_execute on dev-db', subtitle: 'dev environment · from Codex', body: "UPDATE orders SET status = 'cancelled'" });
    await settle();
    http.expectOne(`/api/approvals/${REQUEST.id}`).flush(REQUEST);
    await settle();
    const [toast] = toasts.toasts();
    expect(toast.title).toBe('Approval needed');
    expect(toast.body).toContain('sql_execute on dev-db (dev) from Codex.');
    // Never the action, and never a decision.
    expect(toast.body).not.toContain('UPDATE');
    expect(toast.actions.map((a) => a.label)).toEqual(['Review']);
    expect(toast.timeout).toBe(0);

    const go = vi.spyOn(TestBed.inject(Router), 'navigateByUrl');
    toast.actions[0].onClick!();
    expect(String(go.mock.calls[0][0])).toBe('/approvals/ap_h7hbxuq2597l');

    events.emit('approval.resolved', { id: REQUEST.id, status: 'rejected', via: 'slack' });
    expect(toasts.toasts()).toEqual([]);
    await fixture.whenStable();
  });

  it('in the background there is no toast (Electron main shows the OS notification); a summary replaces itself', async () => {
    const { http } = await runningApp();
    const focus = vi.spyOn(document, 'hasFocus').mockReturnValue(false);
    const toasts = TestBed.inject(ToastService);
    const events = TestBed.inject(EventStream);
    events.emit('notify.desktop', { kind: 'request', id: REQUEST.id, title: 'Approval needed · sql_execute on dev-db' });
    await settle();
    expect(http.match((r) => r.url.startsWith('/api/approvals/')).length).toBe(0);
    expect(toasts.toasts()).toEqual([]);

    focus.mockReturnValue(true);
    events.emit('notify.desktop', { kind: 'summary', id: 'approvals-summary', title: '4 approval requests are waiting', body: 'Many requests arrived at once. Review them in GateRook.' });
    events.emit('notify.desktop', { kind: 'summary', id: 'approvals-summary', title: '5 approval requests are waiting', body: 'Many requests arrived at once. Review them in GateRook.' });
    await settle();
    expect(toasts.toasts().map((t) => t.title)).toEqual(['5 approval requests are waiting']);
  });
});

describe('where a start opens: one decision, whatever navigates meanwhile (§8.1)', () => {
  const onboarding = (firstRun: boolean, skipped = false): Onboarding => ({
    firstRun,
    skipped,
    gettingStarted: { done: 0, total: 6, dismissed: false, steps: [] },
    setupMap: { nodes: [{ id: 'connections', count: 9, state: 'done', status: '' }] },
  });

  beforeEach(() => {
    resetOnboardingGuard();
    configure([
      { path: '', pathMatch: 'full', redirectTo: 'overview' },
      {
        path: '',
        canActivate: [onboardingGuard],
        children: [
          { path: 'overview', component: Page, data: { section: 'Overview' } },
          { path: 'settings', component: Page, data: { section: 'Settings' } },
          { path: 'onboarding', component: Page, data: { section: 'Welcome', shell: 'welcome' } },
        ],
      },
    ]);
  });
  afterEach(() => resetOnboardingGuard());

  it('a first run opens the Overview guide even when another navigation starts while the window decides', async () => {
    const http = TestBed.inject(HttpTestingController);
    const router = TestBed.inject(Router);
    const fixture = TestBed.createComponent(App);
    document.body.appendChild(fixture.nativeElement);
    const first = router.navigateByUrl('/overview');
    await settle();
    // No frame yet: a sidebar now would lead past Welcome.
    expect(fixture.nativeElement.querySelector('.gw-sidebar')).toBeNull();
    // A click (or a deep link) while the window waits for the core.
    const second = router.navigateByUrl('/settings');
    await settle();
    http.match('/api/health').forEach((r) => r.flush(HEALTH));
    await settle();
    TestBed.tick();
    await settle();
    http.expectOne('/api/onboarding').flush(onboarding(true));
    await first.catch(() => undefined);
    await second.catch(() => undefined);
    await settle();
    await fixture.whenStable();
    expect(router.url).toBe('/overview');
    expect(fixture.nativeElement.querySelector('.gw-sidebar')).not.toBeNull();

    // Decided: the person's own navigations go where they lead, and nothing asks again.
    await router.navigateByUrl('/settings');
    await fixture.whenStable();
    expect(router.url).toBe('/settings');
    expect(http.match('/api/onboarding').length).toBe(0);
    expect(fixture.nativeElement.querySelector('.gw-sidebar')).not.toBeNull();
  });

  it('a gateway already set up, or a skipped Welcome, opens where it was asked', async () => {
    const http = TestBed.inject(HttpTestingController);
    const router = TestBed.inject(Router);
    const fixture = TestBed.createComponent(App);
    document.body.appendChild(fixture.nativeElement);
    const nav = router.navigateByUrl('/settings');
    await settle();
    http.match('/api/health').forEach((r) => r.flush(HEALTH));
    await settle();
    TestBed.tick();
    await settle();
    http.expectOne('/api/onboarding').flush(onboarding(true, true));
    await nav;
    await fixture.whenStable();
    expect(router.url).toBe('/settings');
  });
});
