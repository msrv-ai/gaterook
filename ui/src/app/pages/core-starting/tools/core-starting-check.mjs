// U16 · core-starting against REAL cores (dev tool, not part of the app bundle: nothing imports it).
//
// A stand-in for Electron's main (ARCHITECTURE.md §7.1) until E1 exists: it runs app.jar cores (JVM, dev mode) on
// throwaway homes, reads their stdout protocol (progress lines, ready line, ready:false) and their exit, turns them
// into the DesktopCoreState that preload's onCoreState delivers, serves the built UI with a signing /api proxy that
// opens only once it passed "ready" on (as main does), and drives Chromium: kit page vs app at 1440×900 in light,
// dark and high contrast, plus the real flows (Use another port, Restore with a passphrase, Create a new key,
// Restart core) and keyboard walks.
//
//   npm run sync-kit -w ui && (cd ui && npx ng build --configuration development)
//   mvn -B -ntp -pl app -am -DskipTests package            # app/target/app.jar, once (under the heavy lock)
//   JAVA_HOME=... node ui/src/app/pages/core-starting/tools/core-starting-check.mjs <scenario> [--themes light,dark,hc]
//
// Scenarios: starting, port, locked, restore, create, stopped, dblocked, unreachable. Screenshots (kit, app, side by
// side) go to ui/target/core-starting/; the side-by-side files are <scenario>-<theme>-sbs.png.
import { spawn } from 'node:child_process';
import { createHmac, randomBytes } from 'node:crypto';
import { createServer, request } from 'node:http';
import { createServer as netServer } from 'node:net';
import { existsSync, mkdirSync, readFileSync, rmSync, unlinkSync, writeFileSync } from 'node:fs';
import { extname, join, normalize } from 'node:path';
import { createInterface } from 'node:readline';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const ROOT = join(fileURLToPath(new URL('.', import.meta.url)), '..', '..', '..', '..', '..', '..');
const require = createRequire(join(ROOT, 'ui', 'package.json'));
const { chromium } = require('playwright-core');
// The UI's build: the Angular project is gaterook-ui (mcp-gateway-ui before decision 11, an older build still found).
const DIST = ['gaterook-ui', 'mcp-gateway-ui'].map((p) => join(ROOT, 'ui', 'dist', p, 'browser')).find((d) => existsSync(join(d, 'index.html'))) ?? join(ROOT, 'ui', 'dist', 'gaterook-ui', 'browser');
const JAR = join(ROOT, 'app', 'target', 'app.jar');
const JAVA = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', 'java') : 'java';
const WORK = join(ROOT, 'ui', 'target', 'core-starting');
/** The app's version: what main knows of the bundled core before it answers. */
const VERSION = JSON.parse(readFileSync(join(ROOT, 'ui', 'package.json'), 'utf8')).version;
const OUT = WORK;
const HOMES = join(WORK, 'homes');
mkdirSync(OUT, { recursive: true });
mkdirSync(HOMES, { recursive: true });

const args = process.argv.slice(2);
const scenario = args[0];
const opt = (n, d) => {
  const i = args.indexOf(n);
  return i >= 0 ? args[i + 1] : d;
};
const THEMES = { light: { theme: 'light', contrast: 'normal' }, dark: { theme: 'dark', contrast: 'normal' }, hc: { theme: 'light', contrast: 'more' } };
const themes = opt('--themes', 'light,dark,hc').split(',');
const log = (...a) => console.log('[harness]', ...a);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function freePort() {
  return new Promise((resolve, reject) => {
    const s = netServer();
    s.once('error', reject);
    s.listen(0, '127.0.0.1', () => {
      const { port } = s.address();
      s.close(() => resolve(port));
    });
  });
}

/** Can main bind this port now? (findFreePort) */
function canBind(port) {
  return new Promise((resolve) => {
    const s = netServer();
    s.once('error', () => resolve(false));
    s.listen(port, '127.0.0.1', () => s.close(() => resolve(true)));
  });
}

// ── One core process, as main runs it ──────────────────────────────────────────────────────────────────────────────

class Core {
  constructor(home, port, publicPort, { demo = false } = {}) {
    this.home = home;
    this.port = port;
    this.publicPort = publicPort;
    this.secret = randomBytes(32).toString('base64url');
    this.demo = demo;
    this.steps = [];
    this.started = new Map();
    this.ready = null;
    this.onState = () => undefined;
    this.hold = null; // stop forwarding after this predicate matches (the "live" moment of a screenshot)
    this.held = false;
  }

  start() {
    mkdirSync(this.home, { recursive: true });
    this.t0 = Date.now();
    this.proc = spawn(
      JAVA,
      ['--enable-native-access=ALL-UNNAMED', '-Xmx384m', '-Dgateway.dev=true', `-Dgateway.home=${this.home}`, `-Dserver.port=${this.port}`, `-Dgateway.public-port=${this.publicPort}`, '-jar', JAR],
      { env: { ...process.env, GW_DEV_INTERNAL_SECRET: this.secret, ...(this.demo ? { GW_DEMO: '1' } : {}) }, stdio: ['pipe', 'pipe', 'pipe'] },
    );
    this.proc.stderr.on('data', () => undefined);
    this.steps = [{ step: 'runtime', state: 'running' }];
    this.emit({ kind: 'starting', steps: this.steps, port: this.port, publicPort: this.publicPort, version: VERSION });
    this.readyPromise = new Promise((resolve) => (this.resolveReady = resolve));
    this.exitPromise = new Promise((resolve) => this.proc.on('exit', (code, signal) => resolve({ code, signal, at: new Date() })));
    createInterface({ input: this.proc.stdout }).on('line', (line) => this.line(line));
    this.exitPromise.then((e) => this.exited(e));
    return this;
  }

  line(text) {
    let msg;
    try {
      msg = JSON.parse(text);
    } catch {
      return;
    }
    const now = Date.now();
    if (this.steps[0]?.step === 'runtime' && this.steps[0].state === 'running') {
      this.steps = [{ step: 'runtime', state: 'done', ms: now - this.t0 }];
    }
    if ('step' in msg) {
      if (msg.state === 'running') this.started.set(msg.step, now);
      const ms = msg.state === 'done' && this.started.has(msg.step) ? now - this.started.get(msg.step) : undefined;
      this.steps = [...this.steps.filter((s) => s.step !== msg.step), { step: msg.step, state: msg.state, ...(ms !== undefined ? { ms } : {}) }];
      this.emit({ kind: 'starting', steps: this.steps, port: this.port, publicPort: this.publicPort, version: VERSION });
      return;
    }
    if ('ready' in msg) {
      this.ready = msg;
      this.resolveReady(msg);
      if (msg.ready) {
        this.emit({ kind: 'ready', firstRun: msg.firstRun, locked: msg.locked, version: msg.version, ports: msg.ports, runtime: msg.runtime });
      } else {
        this.failure = msg; // the exit gives the exit code
      }
    }
  }

  exited({ code, signal, at }) {
    this.exit = { code, signal, at };
    const tail = this.tail(4);
    if (this.failure) {
      const f = this.failure;
      this.emit({ kind: 'failed', reason: f.reason, detail: f.detail ?? null, portOwner: f.portOwner ?? null, log: f.log ?? null, logTail: tail, exitCode: code, port: this.port, publicPort: this.publicPort, version: VERSION });
    } else if (this.ready?.ready && !this.stopping) {
      this.emit({
        kind: 'stopped',
        exitCode: code,
        reason: signal ? `Terminated by ${signal}` : null,
        uptimeMs: at.getTime() - this.t0,
        at: at.toISOString(),
        log: join(this.home, 'logs', 'core.log'),
        logTail: tail,
        port: this.port,
      });
    }
  }

  tail(n) {
    const file = join(this.home, 'logs', 'core.log');
    if (!existsSync(file)) return null;
    const lines = readFileSync(file, 'utf8').split('\n').filter(Boolean);
    return lines.slice(-n).join('\n') || null;
  }

  emit(state) {
    if (this.held) return;
    if (state.kind === 'ready') this.forwardedReady = true;
    this.onState(state);
    if (this.hold && this.hold(state)) this.held = true;
  }

  get answering() {
    // Main proxies only after it passed the ready line on (a held screenshot moment keeps the window starting).
    return this.forwardedReady === true && this.ready?.ready === true && !this.exit;
  }

  async stop() {
    this.stopping = true;
    if (!this.exit) {
      this.proc.kill('SIGTERM');
      await this.exitPromise;
    }
  }

  sign(method, target) {
    const ts = Date.now();
    const nonce = randomBytes(16).toString('base64url');
    const mac = createHmac('sha256', Buffer.from(this.secret, 'base64url')).update(`${method}|${target}|${ts}|${nonce}`).digest('base64url');
    return `${ts}.${nonce}.${mac}`;
  }

  /** A signed call as main makes it (the /internal ones only main may). */
  async call(method, path, body) {
    const res = await fetch(`http://127.0.0.1:${this.port}${path}`, {
      method,
      headers: { 'X-Gateway-Auth': this.sign(method, path), 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await res.text();
    if (!res.ok) throw new Error(`${method} ${path}: ${res.status} ${text}`);
    return text ? JSON.parse(text) : null;
  }
}

// ── Main: the app's window, its proxy and its preload ─────────────────────────────────────────────────────────────

class Main {
  constructor() {
    this.core = null;
    this.state = null;
    this.calls = [];
    this.handlers = {};
  }

  attach(core) {
    this.core = core;
    core.onState = (s) => this.push(s);
  }

  push(state) {
    this.state = state;
    for (const p of this.pages ?? []) p.evaluate((s) => window.__gwPush?.(s), state).catch(() => undefined);
  }

  async serve() {
    const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.json': 'application/json', '.png': 'image/png', '.ico': 'image/x-icon' };
    this.server = createServer((req, res) => {
      const url = new URL(req.url, 'http://x');
      if (url.pathname.startsWith('/internal')) return res.writeHead(404).end();
      if (url.pathname.startsWith('/api/')) return this.proxy(req, res);
      let file = normalize(join(DIST, decodeURIComponent(url.pathname)));
      if (!file.startsWith(DIST) || !existsSync(file) || url.pathname === '/') file = join(DIST, 'index.html');
      try {
        const body = readFileSync(file);
        res.writeHead(200, { 'Content-Type': TYPES[extname(file)] ?? 'application/octet-stream', 'Cache-Control': 'no-store' });
        res.end(body);
      } catch {
        res.writeHead(404).end();
      }
    });
    this.uiPort = await freePort();
    await new Promise((r) => this.server.listen(this.uiPort, '127.0.0.1', r));
    this.url = `http://127.0.0.1:${this.uiPort}`;
  }

  /** As main: only to its own core, only after it said ready on that port; signed; no browser headers. */
  proxy(req, res) {
    const core = this.core;
    if (!core || !core.answering) {
      res.writeHead(502, { 'Content-Type': 'text/plain' }).end('core not ready');
      return;
    }
    const target = req.url;
    const headers = { ...req.headers, host: `127.0.0.1:${core.port}`, 'x-gateway-auth': core.sign(req.method, target) };
    for (const h of Object.keys(headers)) if (h.startsWith('sec-fetch-') || ['origin', 'referer', 'cookie'].includes(h)) delete headers[h];
    const up = request({ host: '127.0.0.1', port: core.port, path: target, method: req.method, headers }, (r) => {
      res.writeHead(r.statusCode, r.headers);
      r.pipe(res);
    });
    up.on('error', () => {
      if (!res.headersSent) res.writeHead(502).end();
    });
    req.pipe(up);
  }

  async page(browser, appearance, { desktop = true } = {}) {
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1, timezoneId: 'Europe/Berlin', locale: 'en-US' });
    await context.addInitScript((a) => {
      try {
        localStorage.setItem('gw-appearance', JSON.stringify(a));
      } catch {
        /* default theme */
      }
    }, appearance);
    if (desktop) {
      await context.exposeBinding('__gwCall', async (_src, name, arg) => {
        this.calls.push([name, arg]);
        const h = this.handlers[name];
        return h ? h(arg) : null;
      });
      await context.addInitScript(() => {
        const cbs = [];
        window.__gwPush = (s) => cbs.forEach((cb) => cb(s));
        window.gwDesktop = {
          platform: 'linux',
          setNativeTheme() {},
          openExternal: async () => undefined,
          saveFile: async () => null,
          openFile: (purpose) => window.__gwCall('openFile', purpose),
          openMcpb: async () => undefined,
          openClientConfig: async () => undefined,
          revealInFolder: (kind) => window.__gwCall('reveal', kind),
          onDeepLink: () => () => undefined,
          onCoreState(cb) {
            cbs.push(cb);
            window.__gwCall('state').then((s) => s && cb(s));
            return () => undefined;
          },
          onEvent: () => () => undefined,
          applyGeneral: async () => undefined,
          restartCore: () => window.__gwCall('restart'),
          usePort: (port) => window.__gwCall('usePort', port),
          findFreePort: (from) => window.__gwCall('findFreePort', from),
          quit: () => void window.__gwCall('quit'),
          checkForUpdates: async () => undefined,
          onUpdateState: () => () => undefined,
        };
      });
    }
    this.handlers.state = () => this.state;
    const page = await context.newPage();
    page.on('pageerror', (e) => log('pageerror', e.message));
    page.on('response', (r) => r.status() >= 400 && r.status() !== 502 && log('http', r.status(), r.request().method(), new URL(r.url()).pathname));
    page.on('console', (m) => m.type() === 'error' && !m.text().includes('502') && log('console', m.text()));
    (this.pages ??= []).push(page);
    page.on('close', () => (this.pages = this.pages.filter((p) => p !== page)));
    return { context, page };
  }
}


/** The keyboard walk: what each Tab focuses (name, role, state), then Esc where a dialog is open. */
async function tabWalk(page, n, label) {
  const stops = [];
  for (let i = 0; i < n; i++) {
    await page.keyboard.press('Tab');
    stops.push(
      await page.evaluate(() => {
        const e = document.activeElement;
        if (!e || e === document.body) return '(body)';
        const name = (e.getAttribute('aria-label') || e.textContent || e.id || '').replace(/\s+/g, ' ').trim().slice(0, 40);
        const extra = ['aria-disabled', 'aria-expanded', 'role'].map((a) => (e.getAttribute(a) ? `${a}=${e.getAttribute(a)}` : '')).filter(Boolean).join(' ');
        return `${e.tagName.toLowerCase()}:${name}${extra ? ` [${extra}]` : ''}`;
      }),
    );
  }
  log(`tab walk ${label}:\n  ${stops.join('\n  ')}`);
}

// ── Screenshots ───────────────────────────────────────────────────────────────────────────────────────────────────

async function settle(page) {
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(500);
}

async function shot(page, name) {
  await settle(page);
  const file = join(OUT, `${name}.png`);
  await page.screenshot({ path: file, fullPage: true });
  log('shot', file);
  return file;
}

async function kitShot(browser, kitUrl, t, state, name, then) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1, timezoneId: 'Europe/Berlin', locale: 'en-US' });
  await context.addInitScript((a) => localStorage.setItem('gw-appearance', JSON.stringify(a)), THEMES[t]);
  const page = await context.newPage();
  await page.goto(`${kitUrl}/pages/core-starting.html`, { waitUntil: 'networkidle' });
  if (state !== 'starting') await page.click(`#core-t-${state}`);
  if (then) await then(page);
  const file = await shot(page, name);
  await context.close();
  return file;
}

async function score(browser, a, b) {
  const page = await browser.newPage();
  const img = (f) => `data:image/png;base64,${readFileSync(f).toString('base64')}`;
  const r = await page.evaluate(
    async ([x, y]) => {
      const load = (src) => new Promise((res, rej) => { const i = new Image(); i.onload = () => res(i); i.onerror = rej; i.src = src; });
      const [ia, ib] = await Promise.all([load(x), load(y)]);
      const w = Math.min(ia.width, ib.width), h = Math.min(ia.height, ib.height);
      const data = (i) => { const c = document.createElement('canvas'); c.width = w; c.height = h; const g = c.getContext('2d'); g.drawImage(i, 0, 0); return g.getImageData(0, 0, w, h).data; };
      const da = data(ia), db = data(ib);
      let d = 0;
      for (let p = 0; p < da.length; p += 4) if (Math.abs(da[p] - db[p]) > 48 || Math.abs(da[p + 1] - db[p + 1]) > 48 || Math.abs(da[p + 2] - db[p + 2]) > 48) d++;
      return { diff: d / (w * h), heights: [ia.height, ib.height] };
    },
    [img(a), img(b)],
  );
  await page.close();
  return r;
}

async function sideBySide(browser, a, b, name) {
  const page = await browser.newPage({ viewport: { width: 2 * 1440 + 24, height: 900 } });
  const img = (f) => `data:image/png;base64,${readFileSync(f).toString('base64')}`;
  await page.setContent(`<body style="margin:0;background:#888"><div style="display:flex;gap:24px;align-items:flex-start"><img src="${img(a)}"><img src="${img(b)}"></div></body>`, { waitUntil: 'load' });
  const file = join(OUT, `${name}-sbs.png`);
  await page.screenshot({ path: file, fullPage: true });
  await page.close();
  return file;
}

async function startKit() {
  const port = await freePort();
  const kit = spawn(process.execPath, [join(ROOT, 'design', 'scripts', 'serve.mjs'), '--port', String(port)], { stdio: ['ignore', 'pipe', 'inherit'] });
  await new Promise((r) => kit.stdout.once('data', r));
  return { url: `http://127.0.0.1:${port}`, stop: () => kit.kill() };
}

async function compare(browser, kitUrl, main, kitState, appName, prepare, { desktop = true, kitThen, appThen } = {}) {
  const rows = [];
  for (const t of themes) {
    const k = await kitShot(browser, kitUrl, t, kitState, `${appName}-${t}-kit`, kitThen);
    const { context, page } = await main.page(browser, THEMES[t], { desktop });
    await page.goto(main.url + (prepare?.route ?? '/overview'), { waitUntil: 'load' });
    await page.waitForSelector('gw-status-screen, .gw-shell', { timeout: 20_000 });
    if (appThen) await appThen(page);
    const a = await shot(page, `${appName}-${t}-app`);
    await sideBySide(browser, k, a, `${appName}-${t}`);
    const s = await score(browser, k, a);
    rows.push(`${appName} ${t}: ${(s.diff * 100).toFixed(1)}% differ, heights kit ${s.heights[0]} / app ${s.heights[1]}`);
    await context.close();
  }
  return rows;
}

function freshHome(name) {
  const home = join(HOMES, name);
  rmSync(home, { recursive: true, force: true });
  mkdirSync(home, { recursive: true });
  return home;
}

/** A demo home that has been seeded (GW_DEMO=1 on an empty home), then stopped. */
async function seededHome(name, extra) {
  const home = freshHome(name);
  const core = new Core(home, await freePort(), await freePort(), { demo: true }).start();
  await core.readyPromise;
  if (extra) await extra(core);
  await core.stop();
  return home;
}

// ── Scenarios ─────────────────────────────────────────────────────────────────────────────────────────────────────

async function main() {
  const browser = await chromium.launch({ executablePath: findChromium() });
  const kit = await startKit();
  const app = new Main();
  await app.serve();
  const results = [];
  const cores = [];
  try {
    switch (scenario) {
      case 'starting': {
        // The kit's live state: Local API binding on 127.0.0.1:<port>, the steps before it done.
        const core = new Core(freshHome('starting'), await freePort(), await freePort(), { demo: true });
        app.attach(core);
        core.hold = (s) => s.kind === 'starting' && s.steps.some((x) => x.step === 'local-api' && x.state === 'running');
        cores.push(core.start());
        await core.readyPromise;
        log('held state', JSON.stringify(app.state));
        results.push(...(await compare(browser, kit.url, app, 'starting', 'starting')));
        break;
      }
      case 'port': {
        // A core holds the port; a second core is started on it: its real ready:false PORT_IN_USE line.
        const holder = new Core(freshHome('port-holder'), await freePort(), await freePort()).start();
        cores.push(holder);
        await holder.readyPromise;
        const core = new Core(freshHome('port'), holder.port, await freePort());
        app.attach(core);
        cores.push(core.start());
        await core.exitPromise;
        await sleep(200);
        log('failed state', JSON.stringify(app.state));
        app.handlers.findFreePort = async (from) => {
          for (let p = from; p < from + 50; p++) if (p !== core.publicPort && (await canBind(p))) return p;
          return null;
        };
        results.push(...(await compare(browser, kit.url, app, 'port', 'port')));
        // The dialog, in each theme.
        results.push(
          ...(await compare(browser, kit.url, app, 'port', 'port-dialog', null, {
            kitThen: (p) => p.click('#core-p-port .gw-btn--primary'),
            appThen: (p) => p.click('gw-status-screen .gw-card__footer .gw-btn--primary'),
          })),
        );
        // The flow: Use another port → main starts the core there → the app runs.
        let next = null;
        app.handlers.usePort = async (port) => {
          if (!(await canBind(port))) throw new Error(`Port ${port} is in use now. Choose another one.`);
          next = new Core(core.home, port, core.publicPort);
          app.attach(next);
          cores.push(next.start());
          return null;
        };
        const { context, page } = await app.page(browser, THEMES.light);
        await page.goto(app.url + '/overview', { waitUntil: 'load' });
        await page.waitForSelector('gw-status-screen');
        await tabWalk(page, 5, 'port in use');
        await page.keyboard.press('Enter');
        await page.waitForSelector('#core-port-dlg[open]');
        await tabWalk(page, 4, 'port dialog');
        await page.keyboard.press('Escape');
        await sleep(200);
        log('after Esc', await page.evaluate(() => [document.querySelector('#core-port-dlg')?.open, document.activeElement?.textContent?.trim()]));
        await page.click('gw-status-screen .gw-card__footer .gw-btn--primary');
        await page.waitForSelector('#core-port-dlg[open]');
        await sleep(300);
        log('dialog focus', await page.evaluate(() => document.activeElement?.id), 'value', await page.inputValue('#core-port-new'));
        log('hint', await page.textContent('#core-port-new-hint'));
        // An invalid value first: the error is at the field, the dialog stays.
        await page.fill('#core-port-new', String(holder.port));
        await page.click('#core-port-dlg .gw-btn--primary');
        await sleep(200);
        log('error for the taken port', await page.textContent('#core-port-new-err').catch(() => null), 'focus', await page.evaluate(() => document.activeElement?.id));
        await page.fill('#core-port-new', String(await app.handlers.findFreePort(holder.port + 10)));
        await page.press('#core-port-new', 'Enter');
        await page.waitForSelector('.gw-shell', { timeout: 120_000 });
        log('after usePort: shell visible on', page.url(), 'core now on', app.core.port, 'title', await page.title());
        await shot(page, 'port-flow-after-light');
        await context.close();
        break;
      }
      case 'locked':
      case 'restore':
      case 'create': {
        // A demo home whose key was backed up (with a passphrase for "restore"), then deleted: the core starts locked.
        const backup = join(WORK, `${scenario}-backup.json`);
        rmSync(backup, { force: true });
        const home = await seededHome(scenario, async (c) => {
          const { token } = await c.call('POST', '/internal/files/grant', { path: backup, purpose: 'master-key-backup' });
          await c.call('POST', '/api/master-key/backup', { saveToken: token, passphrase: scenario === 'restore' ? 'correct horse' : null });
          log('backup written', backup);
        });
        unlinkSync(join(home, 'master.keyset'));
        const core = new Core(home, await freePort(), await freePort());
        app.attach(core);
        cores.push(core.start());
        await core.readyPromise;
        log('ready', JSON.stringify(core.ready));
        app.handlers.openFile = async (purpose) => (await core.call('POST', '/internal/files/grant', { path: backup, purpose })).token;
        if (scenario === 'locked') {
          results.push(...(await compare(browser, kit.url, app, 'key', 'locked')));
          results.push(
            ...(await compare(browser, kit.url, app, 'key', 'locked-newkey', null, {
              kitThen: (p) => p.click('#core-p-key .gw-alert .gw-btn'),
              appThen: (p) => p.click('gw-status-screen .gw-alert .gw-btn'),
            })),
          );
          results.push(
            ...(await compare(browser, kit.url, app, 'key', 'locked-restore', null, {
              appThen: (p) => p.click('gw-status-screen .gw-card__footer .gw-btn--primary'),
            })),
          );
          const { context, page } = await app.page(browser, THEMES.light);
          await page.goto(app.url + '/overview', { waitUntil: 'load' });
          await page.waitForSelector('gw-status-screen h1');
          await sleep(800);
          await tabWalk(page, 3, 'locked');
          await page.keyboard.press('Enter');
          await sleep(300);
          log('new key dialog open', await page.evaluate(() => [!!document.querySelector('dialog.gw-dialog--danger[open]'), document.activeElement?.textContent?.trim()]));
          await tabWalk(page, 3, 'new key dialog');
          await page.keyboard.press('Escape');
          await sleep(200);
          log('after Esc', await page.evaluate(() => document.activeElement?.textContent?.trim()));
          await tabWalk(page, 2, 'locked footer');
          await page.keyboard.press('Enter');
          await sleep(300);
          await tabWalk(page, 4, 'restore dialog');
          await page.keyboard.press('Escape');
          await sleep(200);
          log('after Esc', await page.evaluate(() => document.activeElement?.textContent?.trim()));
          await context.close();
        } else if (scenario === 'restore') {
          const { context, page } = await app.page(browser, THEMES.light);
          await page.goto(app.url + '/overview', { waitUntil: 'load' });
          await page.waitForSelector('gw-status-screen h1');
          await sleep(500);
          log('h1', await page.textContent('gw-status-screen h1'));
          await page.click('gw-status-screen .gw-card__footer .gw-btn--primary');
          await page.waitForSelector('#core-restore-dlg[open]');
          await sleep(200);
          log('restore dialog focus', await page.evaluate(() => document.activeElement?.id));
          // Without the passphrase: the core refuses, the field says why and has the focus.
          await page.click('#core-restore-dlg .gw-btn--primary');
          await page.waitForSelector('#core-restore-pass-err', { timeout: 10_000 });
          log('error', await page.textContent('#core-restore-pass-err'), 'focus', await page.evaluate(() => document.activeElement?.id));
          await shot(page, 'restore-passphrase-error-light');
          await page.fill('#core-restore-pass', 'correct horse');
          await page.press('#core-restore-pass', 'Enter');
          await page.waitForSelector('.gw-shell', { timeout: 20_000 });
          await sleep(800);
          log('restored: toast', await page.textContent('.gw-toast-region').catch(() => null));
          await shot(page, 'restore-after-light');
          await context.close();
        } else {
          const { context, page } = await app.page(browser, THEMES.light);
          await page.goto(app.url + '/overview', { waitUntil: 'load' });
          await page.waitForSelector('gw-status-screen h1');
          await page.click('gw-status-screen .gw-alert .gw-btn');
          await page.waitForSelector('dialog.gw-dialog--danger[open]');
          log('new key dialog focus', await page.evaluate(() => document.activeElement?.textContent?.trim()));
          await page.keyboard.press('Escape');
          await sleep(200);
          log('after Esc focus', await page.evaluate(() => document.activeElement?.textContent?.trim()));
          await page.click('gw-status-screen .gw-alert .gw-btn');
          await page.click('dialog.gw-dialog--danger[open] .gw-btn--danger');
          await page.waitForSelector('.gw-shell', { timeout: 20_000 });
          await sleep(1500);
          log('created: url', page.url(), 'toast', await page.textContent('.gw-toast-region').catch(() => null));
          await shot(page, 'create-after-light');
          await context.close();
        }
        break;
      }
      case 'stopped': {
        // The core runs (the shell loads its 3 pending requests), then dies: main reports the exit; Restart core.
        const home = freshHome('stopped');
        const core = new Core(home, await freePort(), await freePort(), { demo: true });
        app.attach(core);
        cores.push(core.start());
        await core.readyPromise;
        app.handlers.restart = async () => {
          const again = new Core(home, core.port, core.publicPort);
          app.attach(again);
          cores.push(again.start());
          return null;
        };
        for (const t of themes) {
          const { context, page } = await app.page(browser, THEMES[t]);
          await page.goto(app.url + '/overview', { waitUntil: 'load' });
          await page.waitForSelector('.gw-shell', { timeout: 20_000 });
          await sleep(1500);
          const victim = app.core;
          if (!victim.exit) {
            victim.proc.kill('SIGKILL');
            await victim.exitPromise;
          }
          await page.waitForSelector('gw-status-screen h1', { timeout: 10_000 });
          const k = await kitShot(browser, kit.url, t, 'stopped', `stopped-${t}-kit`);
          const a = await shot(page, `stopped-${t}-app`);
          await sideBySide(browser, k, a, `stopped-${t}`);
          const s = await score(browser, k, a);
          results.push(`stopped ${t}: ${(s.diff * 100).toFixed(1)}% differ, heights kit ${s.heights[0]} / app ${s.heights[1]}`);
          if (t === themes.at(-1)) {
            // Restart core: main starts it again, the window comes back.
            await page.click('gw-status-screen .gw-card__footer .gw-btn--primary');
            await page.waitForSelector('.gw-shell:not([inert])', { timeout: 30_000 });
            log('after restart: shell back');
          } else {
            await app.handlers.restart();
            await app.core.readyPromise;
          }
          await context.close();
        }
        break;
      }
      case 'dblocked': {
        // A second core on the same home: the real DB_LOCKED line.
        const home = freshHome('dblocked');
        const first = new Core(home, await freePort(), await freePort()).start();
        cores.push(first);
        await first.readyPromise;
        const core = new Core(home, await freePort(), await freePort());
        app.attach(core);
        cores.push(core.start());
        await core.exitPromise;
        await sleep(300);
        log('failed state', JSON.stringify(app.state));
        results.push(...(await compare(browser, kit.url, app, 'stopped', 'dblocked')));
        break;
      }
      case 'unreachable': {
        results.push(...(await compare(browser, kit.url, app, 'starting', 'unreachable', null, { desktop: false })));
        break;
      }
      default:
        throw new Error(`unknown scenario ${scenario}`);
    }
  } finally {
    for (const c of cores) await c.stop().catch(() => undefined);
    await browser.close();
    kit.stop();
    app.server.close();
  }
  console.log(results.join('\n'));
  log('calls', JSON.stringify(app.calls.filter(([n]) => n !== 'state')));
}

function findChromium() {
  for (const p of [process.env.PLAYWRIGHT_CHROMIUM, '/opt/pw-browsers/chromium']) if (p && existsSync(p)) return p;
  throw new Error('no chromium');
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
