#!/usr/bin/env node
// The Electron smoke (ARCHITECTURE.md §9 "Electron hardening", §10 E1): the development build started for real, per
// scenario, each in a throwaway gateway home (never ~/.mcp-gateway) with OS protection off (no Keychain entries):
//   switches  dangerous Chromium/Node switches: the app exits with code 3 before anything starts, and a requested
//             DevTools port never opens
//   fake      the fake core (test/fixtures/fake-core.mjs): the §9 hardening checks (no require, CSP blocks inline
//             scripts and direct fetches to the core, Trusted Types, app://gw/..%2f → 404, /internal → 404, no
//             window.open, no navigation away, permissions), Use another port, locked mode, notifications (Review and
//             Deny only), tray and badge, the 700 ms input guard, content protection, quit stops the core
//   real      the real core (app/target/app.jar, demo world): the UI end to end through the signing proxy, the event
//             stream through app://, the 700 ms guard on a real approval, quit stops the core
//   unlock    OS protection enabled: explanation and button precede key-store access; reload and quit while waiting
//
//   npm run smoke -w desktop                     all scenarios
//   npm run smoke -w desktop -- --only fake      one of them (switches, fake, real, unlock; protect only on request)
//   npm run smoke -w desktop -- --keep           keep the temporary homes (their logs) for a look
//   npm run smoke -w desktop -- --only real --core-binary <mcp-gateway-core>
//                                                `real` against a native core (its libsqlitejdbc next to it or in
//                                                native-libs/) instead of the jar
//
// Needs: ui/dist (npm run build -w ui; built here when missing), app/target/app.jar for `real`
// (mvn -B -ntp -pl app -am -DskipTests package), JAVA_HOME (JDK 25) for `real`.
// Linux CI: xvfb-run -a npm run smoke -w desktop; in a container without user namespaces add
// GW_DEV_ALLOW_NO_SANDBOX=1 (development builds only accept --no-sandbox then).
import { spawn, spawnSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { createServer, connect } from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const desktop = join(dirname(fileURLToPath(import.meta.url)), '..');
const repo = join(desktop, '..');
const args = process.argv.slice(2);
const only = args.includes('--only') ? args[args.indexOf('--only') + 1] : null;
const keep = args.includes('--keep');
const electron = createRequire(join(desktop, 'package.json'))('electron');
const fakeCore = join(desktop, 'test', 'fixtures', 'fake-core.mjs');
const uiDist = join(repo, 'ui', 'dist', 'mcp-gateway-ui', 'browser');
const jar = process.env.GW_CORE_JAR ?? join(repo, 'app', 'target', 'app.jar');
const coreBinary = args.includes('--core-binary') ? args[args.indexOf('--core-binary') + 1] : null;

const log = (...a) => console.log('[smoke]', ...a);
const failures = [];

function freePort() {
  return new Promise((resolve) => {
    const s = createServer();
    s.listen(0, '127.0.0.1', () => {
      const { port } = s.address();
      s.close(() => resolve(port));
    });
  });
}

function alive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

async function gone(pid, ms) {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    if (!alive(pid)) return true;
    await new Promise((r) => setTimeout(r, 100));
  }
  return !alive(pid);
}

function baseEnv(extra) {
  const env = { ...process.env, ...extra };
  for (const name of ['ELECTRON_RUN_AS_NODE', 'NODE_OPTIONS', 'ELECTRON_ENABLE_LOGGING']) delete env[name];
  return env;
}

function electronArgs(extra = []) {
  return [desktop, ...(process.env.GW_DEV_ALLOW_NO_SANDBOX === '1' ? ['--no-sandbox'] : []), ...extra];
}

/** Runs the app once; resolves with exit code, stdout lines, stderr. */
function runApp(env, extraArgs, timeoutMs, onSpawn) {
  return new Promise((resolve) => {
    const child = spawn(electron, electronArgs(extraArgs), { env: baseEnv(env), stdio: ['ignore', 'pipe', 'pipe'] });
    onSpawn?.(child);
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (d) => {
      stdout += d;
      for (const line of String(d).split('\n')) if (line.startsWith('[smoke]')) console.log(`  ${line}`);
    });
    child.stderr.on('data', (d) => (stderr += d));
    const timer = setTimeout(() => {
      failures.push(`the app didn't exit within ${timeoutMs / 1000} s`);
      child.kill('SIGKILL');
    }, timeoutMs);
    child.on('exit', (code, signal) => {
      clearTimeout(timer);
      resolve({ code, signal, stdout, stderr });
    });
  });
}

function check(name, ok, detail) {
  console.log(`  ${ok ? 'ok  ' : 'FAIL'} ${name}${!ok && detail !== undefined ? ` — ${JSON.stringify(detail)}` : ''}`);
  if (!ok) failures.push(name);
}

function resultOf(stdout) {
  const line = stdout.split('\n').find((l) => l.startsWith('[smoke-result] '));
  return line ? JSON.parse(line.slice('[smoke-result] '.length)) : null;
}

// ── Preparation ─────────────────────────────────────────────────────────────────────────────────────────────────────

log('building the development bundle');
if (spawnSync(process.execPath, [join(desktop, 'scripts', 'build.mjs')], { stdio: 'inherit' }).status !== 0) process.exit(1);
if (!existsSync(join(uiDist, 'index.html'))) {
  log('ui/dist is missing: npm run build -w ui');
  const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm';
  if (spawnSync(npm, ['run', 'build', '-w', 'ui'], { cwd: repo, stdio: 'inherit' }).status !== 0) process.exit(1);
}
const work = mkdtempSync(join(tmpdir(), 'gw-smoke-'));
const ui = join(work, 'ui');
cpSync(uiDist, ui, { recursive: true });
// A page of the app's own origin with an inline script: the CSP must block it (probe.js records what happened).
mkdirSync(join(ui, 'smoke'), { recursive: true });
writeFileSync(join(ui, 'smoke', 'inline.html'), '<!doctype html><html><head><script src="probe.js"></script></head>'
  + '<body><script>window.__inlineRan = true;</script></body></html>');
writeFileSync(join(ui, 'smoke', 'probe.js'), 'window.__probe = true; window.__violations = [];\n'
  + "document.addEventListener('securitypolicyviolation', (e) => window.__violations.push(e.violatedDirective));\n");
if (process.platform === 'linux' && !process.env.DISPLAY && !process.env.WAYLAND_DISPLAY) {
  log('no display: run it under xvfb-run -a');
}
log(`work folder ${work}`);

function home(name, ports) {
  const dir = join(work, name);
  mkdirSync(dir, { recursive: true, mode: 0o700 });
  if (ports) writeFileSync(join(dir, 'desktop.json'), JSON.stringify(ports), { mode: 0o600 });
  return dir;
}

// ── switches ────────────────────────────────────────────────────────────────────────────────────────────────────────

async function switches() {
  log('scenario switches');
  const devtools = await freePort();
  const cases = [`--remote-debugging-port=${devtools}`, '--remote-debugging-pipe', '--inspect=0', '--proxy-server=http://127.0.0.1:9',
    '--host-resolver-rules=MAP * 127.0.0.1', '--js-flags=--allow-natives-syntax', '--disable-web-security', '--load-extension=/tmp/x',
    ...(process.env.GW_DEV_ALLOW_NO_SANDBOX === '1' ? [] : ['--no-sandbox'])];
  for (const sw of cases) {
    const h = home(`switch-${cases.indexOf(sw)}`);
    let opened = false;
    let polling = true;
    const started = Date.now();
    const result = await runApp({ GW_HOME: h, GW_CORE_COMMAND: JSON.stringify([process.execPath, fakeCore]), GW_UI_DIST: ui }, [sw],
      15_000, () => {
        if (!sw.startsWith('--remote-debugging-port')) return;
        void (async () => {
          while (polling) {
            await new Promise((resolve) => {
              const s = connect(devtools, '127.0.0.1');
              s.on('connect', () => {
                opened = true;
                s.destroy();
                resolve();
              });
              s.on('error', resolve);
            });
            await new Promise((r) => setTimeout(r, 5));
          }
        })();
      });
    polling = false;
    const ms = Date.now() - started;
    check(`${sw}: refused with exit code 3 in ${ms} ms`, result.code === 3 && /refuses to start/.test(result.stderr),
      { code: result.code, stderr: result.stderr.slice(-300) });
    check(`${sw}: the core never started`, !existsSync(join(h, 'fake-core-runs.jsonl')));
    if (sw.startsWith('--remote-debugging-port')) check(`${sw}: the DevTools port never opened`, !opened);
  }
}

// ── fake ────────────────────────────────────────────────────────────────────────────────────────────────────────────

async function fake() {
  log('scenario fake');
  const busy = await freePort();
  let publicPort = await freePort();
  while (publicPort === busy) publicPort = await freePort();
  const h = home('fake', { port: busy, publicPort });
  writeFileSync(join(h, 'fake-core.json'), '{}');
  // Someone else holds the configured port: the first start must fail with PORT_IN_USE.
  const squatter = createServer();
  await new Promise((r) => squatter.listen(busy, '127.0.0.1', r));
  const result = await runApp({ GW_HOME: h, GW_CORE_COMMAND: JSON.stringify([process.execPath, fakeCore]), GW_UI_DIST: ui,
    GW_SMOKE: 'fake', GW_DESKTOP_PROTECTION: 'off' }, [], 300_000);
  await new Promise((r) => squatter.close(r));
  const summary = resultOf(result.stdout);
  check('fake: every check in the app passed', result.code === 0 && summary?.ok === true,
    { code: result.code, summary, stderr: result.code === 0 ? undefined : result.stderr.slice(-2000) });
  const pid = Number(readFileSync(join(h, 'fake-core.pid'), 'utf8'));
  check('fake: quitting the app stopped the core', await gone(pid, 5000), pid);
}

// ── real ────────────────────────────────────────────────────────────────────────────────────────────────────────────

async function real() {
  log(`scenario real (${coreBinary ? `native core ${coreBinary}` : `jar ${jar}`})`);
  let core;
  if (coreBinary) {
    const libs = [join(dirname(coreBinary), 'native-libs'), dirname(coreBinary)]
      .find((d) => existsSync(join(d, process.platform === 'darwin' ? 'libsqlitejdbc.dylib' : process.platform === 'win32' ? 'sqlitejdbc.dll' : 'libsqlitejdbc.so')));
    if (!existsSync(coreBinary) || !libs) {
      check(`real: ${coreBinary} and its libsqlitejdbc exist`, false);
      return;
    }
    core = { GW_CORE_BINARY: coreBinary, GW_CORE_LIBS: libs };
  } else {
    if (!existsSync(jar)) {
      check(`real: ${jar} exists (mvn -B -ntp -pl app -am -DskipTests package)`, false);
      return;
    }
    core = { GW_CORE_JAR: jar };
  }
  const port = await freePort();
  let publicPort = await freePort();
  while (publicPort === port) publicPort = await freePort();
  const h = home('real', { port, publicPort });
  const result = await runApp({ GW_HOME: h, ...core, GW_UI_DIST: ui, GW_SMOKE: 'real', GW_DEMO: '1',
    GW_DESKTOP_PROTECTION: 'off' }, [], 300_000);
  const summary = resultOf(result.stdout);
  check('real: every check in the app passed', result.code === 0 && summary?.ok === true,
    { code: result.code, summary, stderr: result.code === 0 ? undefined : result.stderr.slice(-2000) });
  if (summary?.corePid) check('real: quitting the app stopped the core', await gone(summary.corePid, 25_000), summary.corePid);
}

// OS protection with the real core: opt-in (`--only protect`), it writes a Keychain / DPAPI / Secret Service entry.
async function protect() {
  log('scenario protect (writes the "MCP Gateway Dev Safe Storage" entry of the OS key store)');
  if (!existsSync(jar)) {
    check(`protect: ${jar} exists`, false);
    return;
  }
  const port = await freePort();
  let publicPort = await freePort();
  while (publicPort === port) publicPort = await freePort();
  for (const [name, protection] of [['fresh', true], ['switch-over', false]]) {
    const h = home(`protect-${name}`, { port, publicPort });
    if (!protection) {
      // First a start without OS protection (a 0400 keyset), then one with it: main switches the keyset over.
      const before = await runApp({ GW_HOME: h, GW_CORE_JAR: jar, GW_UI_DIST: ui, GW_SMOKE: 'unprotected', GW_DESKTOP_PROTECTION: 'off' },
        [], 300_000);
      check('protect: a start without OS protection writes a cleartext keyset', before.code === 0 && resultOf(before.stdout)?.ok === true,
        resultOf(before.stdout));
    }
    const result = await runApp({ GW_HOME: h, GW_CORE_JAR: jar, GW_UI_DIST: ui, GW_SMOKE: 'protect' }, [], 300_000);
    const summary = resultOf(result.stdout);
    check(`protect (${name}): every check in the app passed`, result.code === 0 && summary?.ok === true,
      { code: result.code, summary, stderr: result.code === 0 ? undefined : result.stderr.slice(-2000) });
  }
}

async function unlock() {
  log('scenario unlock (does not access the OS key store)');
  const h = home('unlock');
  const result = await runApp({ GW_HOME: h, GW_CORE_COMMAND: JSON.stringify([process.execPath, fakeCore]), GW_UI_DIST: ui,
    GW_SMOKE: 'unlock', GW_DESKTOP_PROTECTION: 'os' }, [], 60_000);
  const summary = resultOf(result.stdout);
  check('unlock: explanation, reload, and quit while locked passed', result.code === 0 && summary?.ok === true,
    { code: result.code, summary, stderr: result.code === 0 ? undefined : result.stderr.slice(-2000) });
  check('unlock: no core or protected key was created', summary?.corePid === null && !existsSync(join(h, 'master.keyset.osk')));
}

const scenarios = { switches, fake, real, unlock };
for (const [name, fn] of Object.entries(scenarios)) {
  if (only && only !== name) continue;
  await fn();
}
if (only === 'protect') await protect();
if (!keep) rmSync(work, { recursive: true, force: true });
else log(`kept ${work}`);
if (failures.length > 0) {
  log(`${failures.length} failed:\n  - ${failures.join('\n  - ')}`);
  process.exit(1);
}
log('all passed');
