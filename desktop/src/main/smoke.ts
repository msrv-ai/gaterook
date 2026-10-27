// The Electron smoke's runner inside main (development builds only: main.ts imports it when GW_SMOKE is set, and a
// release bundle doesn't contain it). scripts/smoke.mjs starts the app per scenario and reads the result lines:
//   [smoke] {"check": …, "ok": true|false, "detail": …}     one per check
//   [smoke-result] {"ok": …, "passed": n, "failed": n}      last; then the app quits (exit 0) or exits 1
// Scenarios: `fake` — the fake core (test/fixtures/fake-core.mjs): the hardening of §9, Use another port, locked
// mode, notifications, tray, the 700 ms input guard; `real` — the real core: the UI end to end through the proxy,
// the event stream through app://, the 700 ms guard on a real approval, quit.
import { app, BrowserWindow, Notification } from 'electron';
import { request as httpRequest } from 'node:http';
import { connect } from 'node:net';
import { readFileSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import type { SmokeHost } from './app.ts';
import { callCore } from './core-client.ts';
import { readDesktopConfig } from './desktop-config.ts';
import { markImage } from './tray.ts';

/** Marks the runner's code: scripts/build.mjs checks a release bundle doesn't contain it. */
export const SMOKE_MARKER = 'gw-smoke-runner';

interface Check {
  readonly check: string;
  readonly ok: boolean;
  readonly detail?: unknown;
}

const results: Check[] = [];

function record(check: string, ok: boolean, detail?: unknown): void {
  results.push({ check, ok, ...(detail === undefined ? {} : { detail }) });
  process.stdout.write(`[smoke] ${JSON.stringify({ check, ok, ...(detail === undefined ? {} : { detail }) })}\n`);
}

function expect(check: string, ok: boolean, detail?: unknown): void {
  record(check, ok, detail);
}

const sleep = (ms: number): Promise<void> => new Promise((r) => setTimeout(r, ms));

async function until<T>(what: string, probe: () => T | null | undefined | false | Promise<T | null | undefined | false>,
    timeoutMs = 30_000, everyMs = 100): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  let last: unknown = null;
  for (;;) {
    try {
      const v = await probe();
      if (v !== null && v !== undefined && v !== false) return v as T;
    } catch (e) {
      last = (e as Error).message;
    }
    if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}${last ? ` (${String(last)})` : ''}`);
    await sleep(everyMs);
  }
}

export function run(host: SmokeHost, scenario: string): void {
  process.stdout.write(`[smoke] ${JSON.stringify({ marker: SMOKE_MARKER, scenario })}\n`);
  const watchdog = setTimeout(() => {
    record('scenario finished in time', false, 'the smoke watchdog fired after 240 s');
    void finish(host);
  }, 240_000);
  void (async () => {
    try {
      if (scenario === 'fake') await fakeScenario(host);
      else if (scenario === 'real') await realScenario(host);
      else if (scenario === 'protect') await protectScenario(host);
      else if (scenario === 'unprotected') await unprotectedScenario(host);
      else record('known scenario', false, scenario);
    } catch (e) {
      record('scenario ran to the end', false, (e as Error).stack ?? String(e));
    }
    clearTimeout(watchdog);
    await finish(host);
  })();
}

async function finish(host: SmokeHost): Promise<void> {
  const failed = results.filter((r) => !r.ok).length;
  process.stdout.write(`[smoke-result] ${JSON.stringify({ ok: failed === 0, passed: results.length - failed, failed,
    corePid: host.supervisor.pid() })}\n`);
  if (failed === 0) {
    app.quit(); // the normal quit: the core gets stdin EOF and exits
  } else {
    await host.supervisor.stop();
    app.exit(1);
  }
}

// ── Helpers ───────────────────────────────────────────────────────────────────────────────────────────────────────

function win(host: SmokeHost): BrowserWindow {
  const w = host.window()?.win;
  if (!w) throw new Error('no window');
  return w;
}

/** Runs `fn` in the page's main world (what a script in the page would see) and returns its result. */
async function inPage<T>(host: SmokeHost, fn: string): Promise<T> {
  return (await win(host).webContents.executeJavaScript(`(async () => { ${fn} })()`, true)) as T;
}

async function waitForText(host: SmokeHost, what: string, pattern: RegExp, timeoutMs = 30_000): Promise<string> {
  return until(what, async () => {
    const text = await inPage<string>(host, 'return document.body ? document.body.innerText : "";');
    return pattern.test(text) ? text : null;
  }, timeoutMs, 250);
}

/**
 * Content protection is on: main asked for it, and on macOS and Windows the window has it (Linux has no such thing:
 * `isContentProtected()` is always false there).
 */
function protectedNow(host: SmokeHost): boolean {
  return !!host.window()?.contentProtection() && (process.platform === 'linux' || win(host).isContentProtected());
}

/** A real click, through Chromium's input pipeline (and main's before-mouse-event guard). */
function clickAt(host: SmokeHost, x: number, y: number): void {
  const contents = win(host).webContents;
  contents.sendInputEvent({ type: 'mouseDown', x, y, button: 'left', clickCount: 1 });
  contents.sendInputEvent({ type: 'mouseUp', x, y, button: 'left', clickCount: 1 });
}

function unsignedStatus(port: number, path: string): Promise<number> {
  return new Promise((resolve) => {
    const req = httpRequest({ host: '127.0.0.1', port, path, method: 'GET', headers: { host: `127.0.0.1:${port}` } }, (res) => {
      res.resume();
      resolve(res.statusCode ?? 0);
    });
    req.on('error', () => resolve(0));
    req.end();
  });
}

function fakeControl(host: SmokeHost, control: Record<string, unknown>): void {
  writeFileSync(join(host.layout.home, 'fake-core.json'), JSON.stringify(control));
}

function fakeEmit(port: number, event: string, data: unknown): Promise<number> {
  return new Promise((resolve, reject) => {
    const body = JSON.stringify({ event, data });
    const req = httpRequest({ host: '127.0.0.1', port, path: '/fake/emit', method: 'POST',
      headers: { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body) } }, (res) => {
      let text = '';
      res.on('data', (c) => (text += c));
      res.on('end', () => resolve((JSON.parse(text) as { streams: number }).streams));
    });
    req.on('error', reject);
    req.end(body);
  });
}

/** The event streams a core has once nothing is left behind: main's own and the page's one. */
const MAIN_AND_PAGE_STREAMS = 2;

/** The number of event streams the fake core has open, once it is down to main's and the page's (or after 10 s). */
async function openStreamsSettle(port: number): Promise<number> {
  return until('the abandoned streams to close', async () => {
    const open = await fakeEmit(port, 'noop', {});
    return open <= MAIN_AND_PAGE_STREAMS ? open : null;
  }, 10_000, 200).catch(async () => fakeEmit(port, 'noop', {}));
}

/** One request to the broker, as gw-bridge sends it (protocol v1, run/broker.json names the socket). */
function brokerToken(home: string): Promise<Record<string, unknown> | null> {
  return new Promise((resolve) => {
    let info: { path?: string };
    try {
      info = JSON.parse(readFileSync(join(home, 'run', 'broker.json'), 'utf8')) as { path?: string };
    } catch {
      resolve(null);
      return;
    }
    const socket = connect(info.path ?? '');
    let text = '';
    socket.on('connect', () => socket.write(JSON.stringify({ v: 1, op: 'token', client: 'codex', endpoint: 'dev', grant: 'gr_smoke0000000000' }) + '\n'));
    socket.on('data', (d) => (text += d));
    socket.on('end', () => {
      try {
        resolve(JSON.parse(text) as Record<string, unknown>);
      } catch {
        resolve(null);
      }
    });
    socket.on('error', () => resolve(null));
  });
}

function fakeRequests(home: string): { method: string; path: string; body: unknown; headers: Record<string, unknown> }[] {
  try {
    return readFileSync(join(home, 'fake-core-requests.jsonl'), 'utf8').trim().split('\n').filter(Boolean).map((l) => JSON.parse(l));
  } catch {
    return [];
  }
}

async function ready(host: SmokeHost, timeoutMs = 30_000) {
  return until('the core to be ready', () => host.supervisor.state().kind === 'ready' && host.supervisor.endpoint(), timeoutMs);
}

// ── The hardening of §9 "Electron hardening", "Admin API is inaccessible to agents and websites" ──────────────────────────────

async function hardening(host: SmokeHost): Promise<void> {
  const core = host.supervisor.endpoint()!;
  // Undocumented but present since Electron 13: the preferences the renderer actually got.
  const contents = win(host).webContents as unknown as { getLastWebPreferences?: () => Record<string, unknown> | null };
  const prefs = contents.getLastWebPreferences?.() ?? null;
  expect('window: sandbox, context isolation, no Node, no webview, web security',
    !!prefs && prefs['sandbox'] === true && prefs['contextIsolation'] === true && prefs['nodeIntegration'] === false
      && prefs['webviewTag'] === false && prefs['webSecurity'] === true, prefs ? { sandbox: prefs['sandbox'],
      contextIsolation: prefs['contextIsolation'], nodeIntegration: prefs['nodeIntegration'] } : null);

  const page = await inPage<Record<string, unknown>>(host, `
    const out = {};
    out.require = typeof require; out.process = typeof process; out.module = typeof module; out.Buffer = typeof Buffer;
    out.ipc = typeof ipcRenderer; out.keys = Object.keys(window.gwDesktop || {}).sort();
    const violations = [];
    document.addEventListener('securitypolicyviolation', (e) => violations.push(e.violatedDirective));
    try { const s = document.createElement('script'); s.text = 'window.__injected = 1'; document.head.appendChild(s); out.inject = 'ran:' + (window.__injected ?? 'no'); }
    catch (e) { out.inject = e.name + ': ' + e.message; }
    try { document.body.insertAdjacentHTML('beforeend', '<img src=x onerror="window.__onerror=1">'); out.html = 'allowed'; }
    catch (e) { out.html = e.name; }
    try { await fetch('http://127.0.0.1:${core.port}/api/health'); out.direct = 'reached'; } catch (e) { out.direct = e.name; }
    try { await fetch('http://localhost:${core.port}/api/health'); out.directLocalhost = 'reached'; } catch (e) { out.directLocalhost = e.name; }
    const health = await fetch('/api/health');
    out.health = health.status; out.instance = health.headers.get('x-gateway-instance');
    out.traversal = (await fetch('app://gw/..%2fpackage.json')).status;
    out.traversal2 = (await fetch('app://gw/%2e%2e%2f%2e%2e%2fpackage.json')).status;
    out.internal = (await fetch('/internal/files/grant', { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' })).status;
    out.apiTraversal = (await fetch('/api/%2e%2e/internal/files/grant')).status;
    const index = await fetch('/');
    out.csp = index.headers.get('content-security-policy');
    out.rootNonce = document.querySelector('gw-root')?.getAttribute('ngcspnonce') ?? null;
    out.popup = window.open('https://example.com/') === null ? 'blocked' : 'opened';
    try { out.notification = await Notification.requestPermission(); } catch (e) { out.notification = e.name; }
    try { out.clipboardRead = await navigator.clipboard.readText(); } catch (e) { out.clipboardRead = e.name; }
    await new Promise((r) => setTimeout(r, 300));
    out.violations = violations;
    return out;
  `);
  expect('renderer has no require, process, module, Buffer or ipcRenderer',
    page['require'] === 'undefined' && page['process'] === 'undefined' && page['module'] === 'undefined'
      && page['Buffer'] === 'undefined' && page['ipc'] === 'undefined', page);
  expect('window.gwDesktop is the contract and nothing else', JSON.stringify(page['keys']) === JSON.stringify([
    'applyGeneral', 'checkForUpdates', 'findFreePort', 'onCoreState', 'onDeepLink', 'onEvent', 'onUpdateState',
    'openClientConfig', 'openExternal', 'openFile', 'openMcpb', 'platform', 'quit', 'restartCore', 'revealInFolder',
    'saveFile', 'setNativeTheme', 'usePort']), page['keys']);
  expect('a script injected into the DOM is refused (Trusted Types)', /TypeError/.test(String(page['inject'])), page['inject']);
  expect('HTML injected into the DOM is refused (Trusted Types)', page['html'] === 'TypeError', page['html']);
  expect('a direct fetch to the core\'s port is blocked by the CSP', page['direct'] === 'TypeError'
    && page['directLocalhost'] === 'TypeError' && (page['violations'] as string[]).includes('connect-src'), page['violations']);
  expect('the UI reaches the core through the proxy, with the instance id', page['health'] === 200
    && page['instance'] === core.instanceNonce, { status: page['health'], instance: page['instance'] === core.instanceNonce });
  expect('app://gw/..%2f is 404', page['traversal'] === 404 && page['traversal2'] === 404, [page['traversal'], page['traversal2']]);
  expect('/internal/* from the renderer is 404', page['internal'] === 404 && page['apiTraversal'] === 404,
    [page['internal'], page['apiTraversal']]);
  const nonce = /'nonce-([^']+)'/.exec(String(page['csp']))?.[1];
  expect('index.html has a CSP with a fresh nonce, the one Angular reads', !!nonce && !String(page['csp']).includes('unsafe-inline')
    && String(page['csp']).includes("require-trusted-types-for 'script'") && typeof page['rootNonce'] === 'string', page['csp']);
  expect('window.open is denied', page['popup'] === 'blocked' && BrowserWindow.getAllWindows().length === 1, page['popup']);
  expect('permissions other than clipboard writes are denied', page['notification'] === 'denied'
    && page['clipboardRead'] === 'NotAllowedError', { notification: page['notification'], clipboardRead: page['clipboardRead'] });

  // An inline script in a page of the app's own origin: blocked by script-src (no 'unsafe-inline').
  const probe = new BrowserWindow({ show: false, webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false } });
  try {
    await probe.loadURL('app://gw/smoke/inline.html');
    const inline = await probe.webContents.executeJavaScript('({ ran: window.__inlineRan === true, probe: window.__probe === true, '
      + 'violations: (window.__violations || []).slice() })', true) as { ran: boolean; probe: boolean; violations: string[] };
    expect('an inline script is blocked by the CSP', inline.probe && !inline.ran && inline.violations.some((v) => v.startsWith('script-src')),
      inline);
  } finally {
    probe.destroy();
  }

  // Navigation away from the app is refused; the window stays on app://gw/.
  await inPage(host, 'setTimeout(() => { location.href = "https://example.com/"; }, 0); return true;');
  await sleep(500);
  expect('navigation away from app://gw/ is refused', win(host).webContents.getURL().startsWith('app://gw/'),
    win(host).webContents.getURL());

  const unsigned = await unsignedStatus(core.port, '/api/health');
  expect('a request without the launch signature gets 401', unsigned === 401, unsigned);
}

/** Main's own 700 ms input guard (before-mouse-event), with a button the smoke puts into the page. */
async function inputGuard(host: SmokeHost, route: string): Promise<void> {
  host.openRoute(route);
  await until('the decision route', () => win(host).webContents.getURL().includes(`/${route}`), 10_000);
  // main learns the route from did-navigate-in-page, a moment after the URL changed.
  expect('content protection is on for a decision screen',
    await until('content protection', () => protectedNow(host), 5000).catch(() => false));
  await inPage(host, `
    window.__clicks = 0;
    const b = document.createElement('button');
    b.id = 'smoke-guard-button'; b.textContent = 'Smoke';
    Object.assign(b.style, { position: 'fixed', left: '200px', top: '200px', width: '120px', height: '48px', zIndex: '2147483647' });
    b.addEventListener('click', () => { window.__clicks++; });
    document.body.appendChild(b);
    return true;`);
  host.openRoute(route); // a deep link again: the 700 ms start now
  clickAt(host, 260, 224);
  await sleep(150);
  const early = await inPage<number>(host, 'return window.__clicks;');
  expect('a click within 700 ms of a deep link is ignored', early === 0, early);
  await sleep(800);
  clickAt(host, 260, 224);
  await sleep(200);
  const later = await inPage<number>(host, 'return window.__clicks;');
  expect('a click after 700 ms goes through', later === 1, later);
  await inPage(host, 'document.getElementById("smoke-guard-button")?.remove(); return true;');
}

// ── Scenarios ─────────────────────────────────────────────────────────────────────────────────────────────────────

async function fakeScenario(host: SmokeHost): Promise<void> {
  const icon = markImage(512, 'macos');
  const trayIcon = markImage(16, 'mono');
  expect('the kit app icon loads at both Dock resolutions', !icon.isEmpty()
    && icon.getScaleFactors().includes(1) && icon.getScaleFactors().includes(2)
    && icon.getSize().width === 512 && icon.toPNG({ scaleFactor: 2 }).readUInt32BE(16) === 1024);
  expect('the menu bar uses the kit monochrome template', !trayIcon.isEmpty()
    && trayIcon.getSize().width === 16 && trayIcon.isTemplateImage());
  // A full-bleed tile looks oversized in the Dock even when the bitmap has the correct 512/1024 dimensions.
  const bitmap = icon.toBitmap({ scaleFactor: 1 });
  let left = 512; let top = 512; let right = -1; let bottom = -1;
  for (let y = 0; y < 512; y++) {
    for (let x = 0; x < 512; x++) {
      if ((bitmap[(y * 512 + x) * 4 + 3] ?? 0) >= 250) {
        left = Math.min(left, x); right = Math.max(right, x);
        top = Math.min(top, y); bottom = Math.max(bottom, y);
      }
    }
  }
  const width = right - left + 1;
  const height = bottom - top + 1;
  expect('the macOS Dock tile has clear space and is centered on its canvas',
    width >= 390 && width <= 430 && height >= 390 && height <= 430
    && Math.abs(left + right - 511) <= 2 && Math.abs(top + bottom - 511) <= 2,
    { left, top, right, bottom });
  const home = host.layout.home;
  const busyPort = host.config().port;
  const publicPort = host.config().publicPort;

  // 1. The harness holds the configured port: the core says PORT_IN_USE and the status screen shows it.
  const failed = await until('PORT_IN_USE', () => {
    const s = host.supervisor.state();
    return s.kind === 'failed' ? s : null;
  });
  expect('a taken port: failed with PORT_IN_USE, the owner and the log', failed.kind === 'failed' && failed.reason === 'PORT_IN_USE'
    && failed.portOwner?.pid === 4242 && /core\.log$/.test(failed.log ?? ''), failed);
  await waitForText(host, 'the port-in-use screen', /already in use/i);
  expect('the status screen says the port is in use', true);

  // 2. Use another port, as the screen does it.
  const answers = await inPage<Record<string, unknown>>(host, `
    const out = {};
    out.free = await window.gwDesktop.findFreePort(${busyPort});
    try { await window.gwDesktop.usePort(${busyPort}); out.busy = 'accepted'; } catch (e) { out.busy = String(e.message); }
    try { await window.gwDesktop.usePort(${publicPort}); out.tunnel = 'accepted'; } catch (e) { out.tunnel = String(e.message); }
    try { await window.gwDesktop.usePort(80); out.low = 'accepted'; } catch (e) { out.low = String(e.message); }
    return out;`);
  const free = answers['free'] as number;
  expect('findFreePort skips the taken port and the tunnel\'s', typeof free === 'number' && free !== busyPort && free !== publicPort, answers);
  expect('usePort refuses a taken port, the tunnel\'s, a privileged one', /in use/.test(String(answers['busy']))
    && /tunnel/.test(String(answers['tunnel'])) && /1024/.test(String(answers['low'])), answers);
  await inPage(host, `await window.gwDesktop.usePort(${free}); return true;`);
  let endpoint = await ready(host);
  const config = readDesktopConfig(home).config;
  const mode = process.platform === 'win32' ? 0o600 : statSync(join(home, 'desktop.json')).mode & 0o777;
  expect('Use another port: desktop.json has the new port (0600) and the core runs there', endpoint.port === free
    && config.port === free && config.publicPort === publicPort && mode === 0o600, { port: endpoint.port, config, mode: mode.toString(8) });

  // 2b. The tunnel's public listener moves the same way: the core binds it at start too (U16, REMAINING §4).
  const pub = await inPage<Record<string, unknown>>(host, `
    const out = {};
    out.free = await window.gwDesktop.findFreePort(${publicPort + 1});
    try { await window.gwDesktop.usePort({ publicPort: ${free} }); out.local = 'accepted'; } catch (e) { out.local = String(e.message); }
    try { await window.gwDesktop.usePort({}); out.empty = 'accepted'; } catch (e) { out.empty = String(e.message); }
    return out;`);
  const freePublic = pub['free'] as number;
  expect('usePort refuses the local API\'s port for the tunnel, and a request for nothing',
    typeof freePublic === 'number' && freePublic !== free && /local API's port/.test(String(pub['local']))
      && /port number/.test(String(pub['empty'])), pub);
  await inPage(host, `await window.gwDesktop.usePort({ publicPort: ${freePublic} }); return true;`);
  endpoint = await until('the core on the new public port', () => {
    const e = host.supervisor.state().kind === 'ready' ? host.supervisor.endpoint() : null;
    return e && e.publicPort === freePublic ? e : null;
  });
  const moved = readDesktopConfig(home).config;
  expect('Use another port for the tunnel: desktop.json has the new public port, the core listens there',
    moved.publicPort === freePublic && moved.port === free && endpoint.port === free, { moved, endpoint: endpoint.publicPort });
  await until('the shell', () => inPage<boolean>(host, 'return !!document.querySelector(".gw-shell");'), 30_000, 250);
  expect('the UI shows the app once the core answers', true);

  // 3. Hardening.
  await hardening(host);
  const proxied = fakeRequests(home).filter((r) => r.path === '/api/health');
  expect('proxied requests carry no Origin, Cookie, Referer or Sec-Fetch-Mode', proxied.length > 0 && proxied.every((r) =>
    r.headers['origin'] === null && r.headers['cookie'] === null && r.headers['referer'] === null && r.headers['secFetchMode'] === null),
  proxied.map((r) => r.headers));

  // 4. Notifications: the core's notify.desktop → an OS notification with Review and Deny, never Approve.
  const streams = await until('main\'s event stream', () => fakeEmit(endpoint.port, 'noop', {}).then((n) => n > 0 && n), 10_000);
  expect('main follows the core\'s event stream', streams > 0, streams);
  const notice = (id: string, extra: Record<string, unknown> = {}) => ({ kind: 'request', id, group: 'dev', env: 'dev', prod: false,
    title: 'Approval needed · sql_execute on dev-db', subtitle: 'dev environment · from codex-laptop', body: 'UPDATE t SET x = 1',
    footer: 'dev environment · codex-laptop', urgency: 'normal', silent: true, route: `/approvals/${id}`,
    actions: [{ id: 'review', text: 'Review' }, { id: 'deny', text: 'Deny' }, { id: 'approve', text: 'Approve' }], pendingCount: 2, ...extra });
  await fakeEmit(endpoint.port, 'notify.desktop', notice('ap_aaaaaaaaaaaa'));
  await fakeEmit(endpoint.port, 'notify.desktop', notice('ap_bbbbbbbbbbbb'));
  const shown = await until('two notices', () => {
    const v = host.notifications()?.visible() ?? [];
    return v.length >= 2 ? v : null;
  }, 10_000);
  expect('notifications offer Review and Deny only', shown.every((n) => JSON.stringify(n.actions.map((a) => a.id)) === '["review","deny"]'),
    shown.map((n) => n.actions));
  record('the OS supports notifications here (information: the smoke builds them but never shows them)', true,
    Notification.isSupported());
  expect('tray and badge count the waiting requests', (host.tray()?.labels() ?? []).includes('Approvals (2)')
    && (process.platform !== 'darwin' || app.getBadgeCount() === 2), { tray: host.tray()?.labels(), badge: app.getBadgeCount() });
  host.notifications()?.press('ap_aaaaaaaaaaaa', 1);
  const denied = await until('the deny', () => fakeRequests(home).find((r) => r.path === '/api/approvals/ap_aaaaaaaaaaaa/decision'), 10_000);
  expect('Deny in a notification denies that request (via desktop-notification)',
    JSON.stringify(denied.body) === JSON.stringify({ decision: 'deny', via: 'desktop-notification' }), denied.body);
  expect('nothing ever approves from a notification', !fakeRequests(home).some((r) =>
    (r.body as { decision?: string } | null)?.decision === 'approve'));
  await fakeEmit(endpoint.port, 'approval.resolved', { id: 'ap_bbbbbbbbbbbb', status: 'rejected', via: 'slack' });
  await until('the resolved notice to close', () => !(host.notifications()?.visible() ?? []).some((n) => n.id === 'ap_bbbbbbbbbbbb'), 5000);
  expect('a request decided elsewhere closes its notification', true);

  // "Open in app" in Slack or Telegram: the core's ui.open {path} brings the window to that request (§7.4).
  await fakeEmit(endpoint.port, 'ui.open', { path: '/approvals/ap_dddddddddddd' });
  const opened = await until('the window on the request', () => win(host).webContents.getURL().endsWith('/approvals/ap_dddddddddddd'),
    10_000).catch(() => false);
  expect('ui.open opens the request it names', opened, win(host).webContents.getURL());

  // An event stream the page aborts is closed at the core as well: no connection is left behind — at most main's own
  // stream and the page's one stay open.
  const streamsBefore = await fakeEmit(endpoint.port, 'noop', {});
  await inPage(host, `
    for (let i = 0; i < 3; i++) {
      const ctrl = new AbortController();
      const r = await fetch('/api/events', { headers: { accept: 'text/event-stream' }, signal: ctrl.signal });
      await r.body.getReader().read();
      ctrl.abort();
    }
    return true;`);
  const streamsAfter = await openStreamsSettle(endpoint.port);
  expect('an event stream the page aborts closes at the core too', streamsAfter <= MAIN_AND_PAGE_STREAMS,
    { before: streamsBefore, after: streamsAfter });

  // 5. Review → the window on the request, protected, with main's 700 ms guard.
  await inputGuard(host, 'approvals/ap_cccccccccccc');
  // A sign-in that arrives while the request is on screen (protected already) is a new decision: the 700 ms again.
  await until('the guard to settle', () => host.window()?.gate.ready() === true, 5000);
  await fakeEmit(endpoint.port, 'oauth.consent.requested', { id: 'oc_smoke', code: 'ABC-123', clientHost: 'claude.ai', endpoint: 'dev' });
  const rearmed = await until('the guard to rearm', () => (host.window()?.gate.remaining() ?? 0) > 0, 5000, 10).catch(() => false);
  expect('a sign-in on a protected screen rearms the 700 ms guard', rearmed && host.consents().includes('oc_smoke'), host.consents());
  await fakeEmit(endpoint.port, 'oauth.consent.resolved', { id: 'oc_smoke', decision: 'deny' });
  await until('no waiting sign-in', () => host.consents().length === 0, 5000);
  host.openRoute('settings/general');
  await until('a non-decision route', () => win(host).webContents.getURL().includes('/settings/general'), 10_000);
  // Main stops asking for it; macOS 27 keeps a window protected once it was (window.ts), Windows lifts it.
  expect('content protection is off elsewhere',
    await until('no content protection', () => !host.window()?.contentProtection(), 5000).catch(() => false),
    { asked: host.window()?.contentProtection(), window: win(host).isContentProtected() });

  // 6. What the renderer may ask of main: ids only, checked; nothing that opens a dialog or a file here.
  const asks = await inPage<Record<string, string>>(host, `
    const out = {};
    const attempt = async (name, fn) => { try { await fn(); out[name] = 'accepted'; } catch (e) { out[name] = String(e.message).replace(/^Error invoking remote method '[^']+': (Error: )?/, ''); } };
    await attempt('mcpbOutside', () => window.gwDesktop.openMcpb('outside'));
    await attempt('mcpbBadId', () => window.gwDesktop.openMcpb('../../etc/passwd'));
    await attempt('saveUnknown', () => window.gwDesktop.saveFile('/etc/passwd'));
    await attempt('saveRestore', () => window.gwDesktop.saveFile('master-key-restore'));
    await attempt('openBackup', () => window.gwDesktop.openFile('master-key-backup'));
    await attempt('configUnknown', () => window.gwDesktop.openClientConfig('/Users/x/.ssh/id_rsa'));
    await attempt('revealUnknown', () => window.gwDesktop.revealInFolder('..'));
    await attempt('external', () => window.gwDesktop.openExternal('file:///etc/passwd'));
    return out;`);
  expect('main refuses paths, unknown ids and the wrong kind of file dialog', (asks['mcpbOutside'] ?? '').includes('isn’t where')
    && asks['mcpbBadId'] === 'Unknown link' && /Unknown file purpose/.test(asks['saveUnknown'] ?? '')
    && /Unknown file purpose/.test(asks['saveRestore'] ?? '') && /Unknown file purpose/.test(asks['openBackup'] ?? '')
    && asks['configUnknown'] === 'Unknown client' && asks['revealUnknown'] === 'Unknown folder'
    && /only https/.test(asks['external'] ?? ''), asks);

  // 7. The token broker answers bridges and asks the core (K1).
  const brokerAnswer = await brokerToken(home);
  expect('the token broker gets a bridge its token from the core', brokerAnswer?.ok === true && brokerAnswer.token === 'gwb_fake',
    brokerAnswer);

  // 8. A reload of the page: it subscribes again, gets the state again, and the proxy opens again for it.
  const streamsBeforeReload = await fakeEmit(endpoint.port, 'noop', {});
  win(host).webContents.reload();
  await until('the shell after a reload', () => inPage<boolean>(host, 'return !!document.querySelector(".gw-shell");'), 30_000, 250);
  const again = await inPage<number>(host, 'return (await fetch("/api/health")).status;');
  expect('after a reload the page gets ready again and reaches the core', again === 200 && host.readyDelivered() === endpoint.instanceNonce,
    again);
  const streamsAfterReload = await openStreamsSettle(endpoint.port);
  expect('a reload leaves no stream of the old page open at the core', streamsAfterReload <= MAIN_AND_PAGE_STREAMS,
    { before: streamsBeforeReload, after: streamsAfterReload });

  // 9. Locked mode: the core starts locked; the screen says so and only the master key answers.
  fakeControl(host, { locked: 'MASTER_KEY_MISSING' });
  await inPage(host, 'await window.gwDesktop.restartCore(); return true;');
  await until('a locked core', () => {
    const s = host.supervisor.state();
    return s.kind === 'ready' && s.locked === 'MASTER_KEY_MISSING' && host.supervisor.endpoint();
  });
  await waitForText(host, 'the locked screen', /master key/i);
  const locked = await inPage<Record<string, unknown>>(host, `
    const h = await (await fetch('/api/health')).json();
    const s = (await fetch('/api/settings/general')).status;
    return { health: h.status, settings: s };`);
  expect('locked mode: the status screen and a locked core behind the proxy', locked['health'] === 'locked' && locked['settings'] === 503, locked);
  expect('the tray says the core is locked', (host.tray()?.labels() ?? []).includes('Core: locked'), host.tray()?.labels());

  // 10. Restore through the window unlocks the core without a restart (§7.1); main follows: the tray, the counters.
  const lockedCore = host.supervisor.endpoint()!;
  const countersBefore = fakeRequests(home).filter((r) => r.path === '/api/nav/counters').length;
  const restored = await inPage<number>(host, `return (await fetch('/api/master-key/restore', { method: 'POST',
    headers: { 'content-type': 'application/json' }, body: JSON.stringify({ openToken: 'ft_fake' }) })).status;`);
  const unlocked = await until('main to see the core unlocked', () => {
    const e = host.supervisor.endpoint();
    return e && e.locked === null ? e : null;
  }, 10_000).catch(() => null);
  const counted = await until('the counters read again', () =>
    fakeRequests(home).filter((r) => r.path === '/api/nav/counters').length > countersBefore, 10_000).catch(() => false);
  expect('Restore unlocks without a restart: main\'s core, the tray, the counters follow', restored === 200 && !!unlocked
    && unlocked.instanceNonce === lockedCore.instanceNonce && !(host.tray()?.labels() ?? []).includes('Core: locked') && counted,
  { restored, locked: unlocked?.locked, tray: host.tray()?.labels(), counted });
  const replayed = host.supervisor.replay().at(-1);
  expect('a page that loads now gets ready without the lock', replayed?.kind === 'ready' && replayed.locked === null, replayed);
  fakeControl(host, {});
  await inPage(host, 'await window.gwDesktop.restartCore(); return true;');
  await ready(host);
}

async function realScenario(host: SmokeHost): Promise<void> {
  const endpoint = await ready(host, 120_000);
  expect('the real core starts and says ready', endpoint.runtime === 'jvm' || endpoint.runtime === 'native', {
    runtime: endpoint.runtime, version: endpoint.version, port: endpoint.port, firstRun: endpoint.firstRun, locked: endpoint.locked });
  await until('the shell or onboarding', () => inPage<boolean>(host,
    'return !!document.querySelector(".gw-shell") && !document.querySelector("gw-status-screen");'), 60_000, 250);
  expect('the UI shows the app on the real core', true);

  // A first run may open Welcome (onboarding, no sidebar): leave it as a person would, to reach the pages — Skip
  // setup, or, when every step is done already (the demo world) and Skip is gone, the link to Overview.
  await sleep(1500);
  if (await inPage<boolean>(host, 'return location.pathname === "/onboarding";')) {
    const left = await inPage<string>(host, `
      const skip = [...document.querySelectorAll('a,button')].find((e) => /Skip setup/.test(e.textContent || ''));
      const overview = document.querySelector('a[href="/overview"]');
      (skip ?? overview)?.click();
      return skip ? 'Skip setup' : overview ? 'the link to Overview' : 'nothing to click';`);
    record(`Welcome was open: ${left} (information)`, true);
  }
  // A tour of every page in the sidebar and a few settings sections, under the real CSP and Trusted Types.
  const before = host.console().length;
  const links = await until('the sidebar', async () => {
    const found = await inPage<string[]>(host, `return [...new Set([...document.querySelectorAll('a.gw-nav__item[href]')]
      .map((a) => a.getAttribute('href')).filter((h) => h && h.startsWith('/')))];`);
    return found.length > 0 ? found : null;
  }, 30_000, 250).catch(async () => {
    // No sidebar: the tour below would check nothing, so this is a failure, not a pass.
    expect('the app shows its sidebar', false, await inPage<string>(host,
      'return location.pathname + " · " + (document.body.innerText || "").replace(/\\s+/g, " ").slice(0, 300);'));
    return [] as string[];
  });
  for (const href of links) {
    await inPage(host, `document.querySelector('a.gw-nav__item[href="${href}"]')?.click(); return true;`);
    await sleep(1200);
  }
  for (const section of ['general', 'local-api', 'notifications', 'tunnel']) {
    host.openRoute(`settings/${section}`);
    await sleep(1000);
  }
  const visited = await inPage<string>(host, 'return location.pathname;');
  const logged = host.console().slice(before);
  const refusals = logged.filter((l) => /Content Security Policy|Trusted ?Type|TrustedHTML|TrustedScript|require-trusted-types/i.test(l));
  expect(`no CSP or Trusted Types refusal while the UI ran (${links.length} pages, then settings)`,
    links.length > 0 && refusals.length === 0, { refusals: refusals.slice(0, 10), pages: links, last: visited });
  const otherErrors = logged.filter((l) => l.startsWith('error:') && !refusals.includes(l));
  record('console errors on the tour (information)', true, otherErrors.slice(0, 15));
  const page = await inPage<Record<string, unknown>>(host, `
    const out = {};
    const health = await fetch('/api/health');
    out.health = health.status; out.instance = health.headers.get('x-gateway-instance'); out.body = await health.json();
    const ctrl = new AbortController();
    const events = await fetch('/api/events', { headers: { accept: 'text/event-stream' }, signal: ctrl.signal });
    out.events = events.status;
    const reader = events.body.pipeThrough(new TextDecoderStream()).getReader();
    const first = await Promise.race([reader.read(), new Promise((r) => setTimeout(() => r({ value: 'timeout' }), 10000))]);
    out.firstChunk = first.value; ctrl.abort();
    try { await fetch('http://127.0.0.1:${endpoint.port}/api/health'); out.direct = 'reached'; } catch (e) { out.direct = e.name; }
    const pending = await (await fetch('/api/approvals?status=pending')).json();
    out.pending = (pending.items ?? pending).map((a) => a.id);
    return out;`);
  expect('the UI talks to the real core through the proxy', page['health'] === 200 && page['instance'] === endpoint.instanceNonce
    && (page['body'] as { status?: string }).status === 'ok', { status: page['health'], body: page['body'] });
  expect('the event stream streams through app://', page['events'] === 200 && String(page['firstChunk']).includes(': stream open'),
    page['firstChunk']);
  expect('a direct fetch to the core is blocked by the CSP', page['direct'] === 'TypeError', page['direct']);
  expect('a request without the launch signature gets 401', (await unsignedStatus(endpoint.port, '/api/health')) === 401);
  expect('the page streams events itself (no IPC relay needed)', host.relaying() === false);

  // The 700 ms guard on a real request: neither a synthetic DOM click (the UI's guard) nor a real one (main's)
  // decides it right after a deep link; a click after 700 ms does.
  const pending = (page['pending'] as string[]) ?? [];
  expect('the demo world has a pending request', pending.length > 0, pending);
  const id = pending[0];
  if (!id) return;
  const route = `approvals/${id}`;
  host.openRoute(route);
  const deny = `[...document.querySelectorAll('button')].find((b) => /^\\s*Deny\\b/.test(b.textContent || '') && !b.closest('gw-dialog, dialog'))`;
  await until('the Deny button', () => inPage<boolean>(host, `return !!(${deny});`), 30_000, 100);
  expect('content protection is on for the request',
    await until('content protection', () => protectedNow(host), 5000).catch(() => false));
  const status = async (): Promise<string> => {
    const answer = await callCore<{ status?: string }>(host.supervisor.endpoint()!, 'GET', `/api/approvals/${id}`);
    return String(answer.body?.status);
  };
  host.openRoute(route);
  await inPage(host, `(${deny}).click(); return true;`);
  await sleep(400);
  expect('a DOM click within 700 ms of a deep link decides nothing (UI guard)', (await status()) === 'pending');
  host.openRoute(route);
  const box = await inPage<{ x: number; y: number }>(host, `const r = (${deny}).getBoundingClientRect(); return { x: r.x + r.width / 2, y: r.y + r.height / 2 };`);
  clickAt(host, Math.round(box.x), Math.round(box.y));
  await sleep(400);
  expect('a real click within 700 ms of a deep link decides nothing (main\'s guard)', (await status()) === 'pending');
  await sleep(800);
  const box2 = await inPage<{ x: number; y: number }>(host, `const r = (${deny}).getBoundingClientRect(); return { x: r.x + r.width / 2, y: r.y + r.height / 2 };`);
  clickAt(host, Math.round(box2.x), Math.round(box2.y));
  const after = await until('the decision', async () => {
    const s = await status();
    return s !== 'pending' ? s : null;
  }, 15_000, 200);
  expect('a click after 700 ms denies the request', after === 'rejected', after);
}

/**
 * OS protection of the master key (A14) on this system, with the real core: a first start gets a file key from
 * safeStorage, the core writes the keyset encrypted under it, a restart opens it again. Opt-in (it writes a Keychain
 * entry, "GateRook Dev Safe Storage").
 */
async function protectScenario(host: SmokeHost): Promise<void> {
  const home = host.layout.home;
  const first = await ready(host, 120_000);
  expect('the core starts unlocked', first.locked === null, first.locked);
  // A keyset written before (the `unprotected` run) is switched over after ready: wait for the rewrite.
  await until('the keyset encrypted', () => readFileSync(join(home, 'master.keyset'), 'utf8').includes('encryptedKeyset'), 30_000, 200);
  const osk = join(home, 'master.keyset.osk');
  const mode = process.platform === 'win32' ? 0o600 : statSync(osk).mode & 0o777;
  expect('the file key is kept by safeStorage in master.keyset.osk (0600)', mode === 0o600, mode.toString(8));
  const keyset = readFileSync(join(home, 'master.keyset'), 'utf8');
  expect('the core wrote the keyset encrypted under the file key', keyset.includes('encryptedKeyset') && !keyset.includes('"keyData"'));
  const health = await callCore<{ masterKey?: { protection?: string } }>(first, 'GET', '/api/health');
  expect('the core reports OS protection', ['keychain', 'dpapi', 'libsecret', 'kwallet'].includes(String(health.body.masterKey?.protection)),
    health.body.masterKey);
  await host.supervisor.restart();
  const second = await ready(host, 120_000);
  expect('a restart opens the protected keyset again (not locked)', second.locked === null && second.instanceNonce !== first.instanceNonce,
    second.locked);
}

/** The first half of the switch-over check: a core started without OS protection writes a 0400 cleartext keyset. */
async function unprotectedScenario(host: SmokeHost): Promise<void> {
  const core = await ready(host, 120_000);
  const keyset = readFileSync(join(host.layout.home, 'master.keyset'), 'utf8');
  expect('without OS protection the keyset is a cleartext 0400 file', core.locked === null && !keyset.includes('encryptedKeyset')
    && (process.platform === 'win32' || (statSync(join(host.layout.home, 'master.keyset')).mode & 0o777) === 0o400));
}
