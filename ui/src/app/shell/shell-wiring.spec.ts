import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { App } from '../app';
import { problemJsonInterceptor } from '../core/api/problem-json.interceptor';
import { DesktopBridge } from '../core/desktop/desktop-bridge';
import { EVENT_STREAM_FETCH, EventStream } from '../core/events/event-stream';
import { CoreStore } from '../core/stores/core.store';
import { ActivationGuard } from '../kit/activation-guard';
import { provideKitIcons } from '../kit/icons';
import { deepLinkUrl, uiOpenUrl } from './deep-links';

describe('deep links: navigation only, strictly by allowlist (§7.1)', () => {
  it('maps the three kinds of link, with or without the scheme', () => {
    expect(deepLinkUrl('gaterook://approvals/ap_h7hbxuq2597l')).toBe('/approvals/ap_h7hbxuq2597l');
    expect(deepLinkUrl('approvals/ap_h7hbxuq2597l')).toBe('/approvals/ap_h7hbxuq2597l');
    expect(deepLinkUrl('gaterook://activity/events/412')).toBe('/activity#event-412');
    expect(deepLinkUrl('/settings/notifications')).toBe('/settings/notifications');
  });

  it('takes only its own scheme (decision 11: gaterook://, nothing is carried over from earlier builds)', () => {
    expect(deepLinkUrl('mcp-gateway://approvals/ap_h7hbxuq2597l')).toBeNull();
    expect(deepLinkUrl('other-app://activity/events/412')).toBeNull();
  });

  it('ignores anything else: other paths, queries, fragments, unknown sections, odd ids', () => {
    for (const link of [
      'gaterook://approvals/ap_H7HBXUQ2597L',
      'gaterook://approvals/ap_h7hbxuq2597l/decision',
      'gaterook://approvals/ap_h7hbxuq2597l?decision=approve',
      'gaterook://approvals/ap_h7hbxuq2597l#approve',
      'gaterook://approvals',
      'gaterook://activity/events/412x',
      'gaterook://settings/../../endpoints',
      'gaterook://settings/danger-zone',
      'gaterook://endpoints/dev',
      'https://evil.example/approvals/ap_h7hbxuq2597l',
      'javascript:alert(1)',
      '',
    ]) {
      expect(deepLinkUrl(link), link).toBeNull();
    }
  });

  it('ui.open ("Open in app" in a chat) reads the core\'s path through the same allowlist', () => {
    // The channels send `{path}` (notifications.internal Slack/TelegramChannel): a request, or the queue.
    expect(uiOpenUrl({ path: '/approvals/ap_h7hbxuq2597l' })).toBe('/approvals/ap_h7hbxuq2597l');
    expect(uiOpenUrl({ path: '/approvals' })).toBe('/approvals');
    expect(uiOpenUrl({ path: 'settings/tunnel' })).toBeNull();
    expect(uiOpenUrl({ path: '/approvals/../overview' })).toBeNull();
    expect(uiOpenUrl({ path: '/overview' })).toBeNull();
    expect(uiOpenUrl({ path: 42 } as never)).toBeNull();
    expect(uiOpenUrl(null)).toBeNull();
  });
});

describe('the shell routes what comes from outside and follows core restarts', () => {
  let deepLink: ((route: string) => void) | null = null;
  const desktop: Partial<DesktopBridge> = {
    available: false,
    platform: 'web',
    onDeepLink: (cb) => {
      deepLink = cb;
      return () => (deepLink = null);
    },
    onCoreState: () => () => undefined,
    onEvent: () => null,
    setNativeTheme: () => undefined,
  };
  // An event stream that never connects: the test emits events itself.
  const neverFetch = (() => new Promise<Response>(() => undefined)) as typeof fetch;

  beforeEach(() => {
    deepLink = null;
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([{ path: '**', children: [] }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideKitIcons(),
        { provide: DesktopBridge, useValue: desktop },
        { provide: EVENT_STREAM_FETCH, useValue: neverFetch },
      ],
    });
  });
  afterEach(() => TestBed.inject(HttpTestingController).match(() => true));

  it('an allowlisted deep link or ui.open navigates and re-arms the decision delay; others do nothing', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const router = TestBed.inject(Router);
    const go = vi.spyOn(router, 'navigateByUrl');
    const rearm = vi.spyOn(TestBed.inject(ActivationGuard), 'rearm');

    deepLink!('gaterook://approvals/ap_h7hbxuq2597l');
    deepLink!('gaterook://approvals/ap_h7hbxuq2597l?approve=1');
    TestBed.inject(EventStream).emit('ui.open', { path: '/approvals/ap_vyt2xvc248kr' });
    TestBed.inject(EventStream).emit('ui.open', { path: '/endpoints/dev' });

    expect(go.mock.calls.map((c) => String(c[0]))).toEqual(['/approvals/ap_h7hbxuq2597l', '/approvals/ap_vyt2xvc248kr']);
    expect(rearm).toHaveBeenCalledTimes(2);
    fixture.destroy();
  });

  it('in the desktop app ui.open is main\'s to open: the window waits for main\'s deep link', async () => {
    TestBed.overrideProvider(DesktopBridge, { useValue: { ...desktop, available: true } });
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const go = vi.spyOn(TestBed.inject(Router), 'navigateByUrl');

    TestBed.inject(EventStream).emit('ui.open', { path: '/approvals' });
    TestBed.inject(EventStream).emit('ui.open', { path: '/approvals/ap_vyt2xvc248kr' });
    expect(go).not.toHaveBeenCalled();

    // Main resolves the queue to its first waiting request and hands it over.
    deepLink!('approvals/ap_vyt2xvc248kr');
    expect(go.mock.calls.map((c) => String(c[0]))).toEqual(['/approvals/ap_vyt2xvc248kr']);
    fixture.destroy();
  });

  it('another core instance answering restarts the event stream without the old Last-Event-ID', async () => {
    const http = TestBed.inject(HttpTestingController);
    const core = TestBed.inject(CoreStore);
    const events = TestBed.inject(EventStream);
    const restart = vi.spyOn(events, 'restart');
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const settle = () => new Promise((r) => setTimeout(r));
    const health = async (uptimeMs: number, instance: string) => {
      await settle();
      const pending = http.match('/api/health');
      expect(pending.length).toBeGreaterThan(0);
      pending.forEach((r) =>
        r.flush(
          { status: 'ok', version: '0.4.0', uptimeMs, ports: { local: 7465, public: 7466 }, runtime: 'jvm', warnings: [] },
          { headers: { 'X-Gateway-Instance': instance } },
        ),
      );
      await settle();
      await fixture.whenStable();
    };
    await health(60_000, 'first-instance');
    expect(core.running()).toBe(true);
    expect(core.instanceChanges()).toBe(0);

    // The same instance again: nothing restarts.
    void core.refresh();
    await health(61_000, 'first-instance');
    expect(core.instanceChanges()).toBe(0);

    // A restart the window didn't see go down: a new nonce.
    void core.refresh();
    await health(2_000, 'second-instance');
    fixture.detectChanges();
    expect(core.instanceChanges()).toBe(1);
    expect(restart).toHaveBeenCalledTimes(1);
    fixture.destroy();
  });
});

describe('the shell notices a core that went away after start (browser, no Electron main)', () => {
  const desktop: Partial<DesktopBridge> = {
    available: false,
    platform: 'web',
    onDeepLink: () => () => undefined,
    onCoreState: () => () => undefined,
    onEvent: () => null,
    setNativeTheme: () => undefined,
  };
  let streamFails = false;
  const fetchFn = (() => (streamFails ? Promise.reject(new TypeError('Failed to fetch')) : new Promise<Response>(() => undefined))) as typeof fetch;
  const settle = () => new Promise((r) => setTimeout(r));
  const ok = { status: 'ok', version: '0.4.0', uptimeMs: 60_000, ports: { local: 7465, public: 7466 }, runtime: 'jvm', warnings: [] };

  beforeEach(() => {
    streamFails = false;
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([{ path: '**', children: [] }]),
        provideHttpClient(withInterceptors([problemJsonInterceptor])),
        provideHttpClientTesting(),
        provideKitIcons(),
        { provide: DesktopBridge, useValue: desktop },
        { provide: EVENT_STREAM_FETCH, useValue: fetchFn },
      ],
    });
  });
  afterEach(() => TestBed.inject(HttpTestingController).match(() => true));

  async function running() {
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    await settle();
    http.match('/api/health').forEach((r) => r.flush(ok));
    await settle();
    await fixture.whenStable();
    http.match(() => true); // the frame's first loads (approvals, tunnel, settings, info) are not under test
    expect(TestBed.inject(CoreStore).running()).toBe(true);
    return { http, fixture };
  }

  it('a call that meets a network error makes the shell ask /api/health once; a failing check shows the status screen', async () => {
    const { http, fixture } = await running();
    TestBed.inject(HttpClient).get('/api/approvals').subscribe({ error: () => undefined });
    http.expectOne('/api/approvals').error(new ProgressEvent('error'));
    await settle();
    const checks = http.match('/api/health');
    expect(checks.length).toBe(1);
    // The check's own failure goes through the interceptor too: it must not start another check.
    checks[0].error(new ProgressEvent('error'));
    await settle();
    expect(http.match('/api/health').length).toBe(0);
    expect(TestBed.inject(CoreStore).state().kind).toBe('unreachable');
    fixture.destroy();
  });

  it('a 404 of a route this core lacks does not', async () => {
    const { http, fixture } = await running();
    TestBed.inject(HttpClient).get('/api/tunnel').subscribe({ error: () => undefined });
    http.expectOne('/api/tunnel').flush(null, { status: 404, statusText: 'Not Found' });
    await settle();
    expect(http.match('/api/health').length).toBe(0);
    expect(TestBed.inject(CoreStore).running()).toBe(true);
    fixture.destroy();
  });

  it('an event stream that cannot reconnect makes the shell ask /api/health', async () => {
    const { http, fixture } = await running();
    // The core goes away: the stream's next attempt fails.
    streamFails = true;
    TestBed.inject(EventStream).restart();
    await settle();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(TestBed.inject(EventStream).failing()).toBeGreaterThan(0);
    expect(http.match('/api/health').length).toBe(1);
    fixture.destroy();
  });
});
