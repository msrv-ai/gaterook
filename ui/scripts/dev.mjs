#!/usr/bin/env node
// The UI's development loop in one command (ARCHITECTURE.md §8.1, README "UI development"):
//
//   npm run dev -w ui            # or: node ui/scripts/dev.mjs [--port 4300] [--core-port 7465] [--keep] [--no-demo]
//
// 1. app/target/app.jar is built once if it is missing (mvn -pl app -am -DskipTests package; under the shared
//    heavy-build lock when `flock` exists).
// 2. A fresh launch secret (32 random bytes, base64url) and free local ports.
// 3. The core on the JVM in dev mode (-Dgateway.dev=true + GW_DEV_INTERNAL_SECRET), with GW_DEMO=1 on an empty home
//    under ui/target/dev-home: the demo world of BLUEPRINT §9, the data every page is compared with the kit on.
//    --keep reuses the home of the last run (the demo seeds an empty home only); --no-demo starts empty.
// 4. Waits for the core's ready line, then `ng serve` with ui/proxy.conf.mjs signing every /api request with the
//    secret as Electron's main process does.
//
// Ctrl+C stops both. The secret lives only in this process tree's environment and is never written to disk.
import { spawn, spawnSync } from 'node:child_process';
import { createHmac, randomBytes } from 'node:crypto';
import { existsSync, mkdirSync, rmSync } from 'node:fs';
import { createServer } from 'node:net';
import { dirname, join } from 'node:path';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';
import { homedir } from 'node:os';
import { createRequire } from 'node:module';

const UI = join(dirname(fileURLToPath(import.meta.url)), '..');
const ROOT = join(UI, '..');
const JAR = join(ROOT, 'app', 'target', 'app.jar');

const args = process.argv.slice(2);
const option = (name, fallback) => {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
};
const flag = (name) => args.includes(name);

/** A port the OS says is free right now (0 = let it choose). */
function freePort(preferred = 0) {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.once('error', (e) => (preferred ? freePort(0).then(resolve, reject) : reject(e)));
    server.listen(preferred, '127.0.0.1', () => {
      const { port } = server.address();
      server.close(() => resolve(port));
    });
  });
}

function java() {
  const home = process.env.JAVA_HOME;
  return home ? join(home, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
}

function buildJar() {
  console.log('[dev] app/target/app.jar is missing: building it once (mvn -pl app -am -DskipTests package)');
  const mvn = ['mvn', '-B', '-ntp', '-pl', 'app', '-am', '-DskipTests', 'package'];
  const lock = join(homedir(), '.cache', 'mcp-gateway', 'heavy.lock');
  const hasFlock = spawnSync('flock', ['--version'], { stdio: 'ignore' }).status === 0;
  if (hasFlock) mkdirSync(dirname(lock), { recursive: true });
  const [cmd, ...rest] = hasFlock ? ['flock', '-s', lock, ...mvn] : mvn;
  const r = spawnSync(cmd, rest, { cwd: ROOT, stdio: 'inherit', shell: process.platform === 'win32' });
  if (r.status !== 0 || !existsSync(JAR)) {
    console.error('[dev] the build failed; see the Maven output above');
    process.exit(1);
  }
}

const children = [];
function stopAll(code = 0) {
  for (const c of children) if (c.exitCode === null) c.kill('SIGTERM');
  setTimeout(() => process.exit(code), 300).unref();
}
process.on('SIGINT', () => stopAll(0));
process.on('SIGTERM', () => stopAll(0));

async function main() {
  if (!existsSync(JAR)) buildJar();

  const secret = randomBytes(32).toString('base64url');
  const corePort = await freePort(Number(option('--core-port', 0)));
  const publicPort = await freePort(0);
  const uiPort = await freePort(Number(option('--port', 4200)));
  const home = option('--home', join(UI, 'target', 'dev-home'));
  const demo = !flag('--no-demo');
  if (!flag('--keep') && existsSync(home)) rmSync(home, { recursive: true, force: true });
  mkdirSync(home, { recursive: true });

  console.log(`[dev] core: 127.0.0.1:${corePort} (public ${publicPort}), home ${home}${demo ? ', demo world' : ''}`);
  const core = spawn(
    java(),
    [
      '--enable-native-access=ALL-UNNAMED',
      '-Dgateway.dev=true',
      `-Dgateway.home=${home}`,
      `-Dserver.port=${corePort}`,
      `-Dgateway.public-port=${publicPort}`,
      // The kit checks health every minute: the pages say the interval the core reports. The demo world's hosts don't
      // exist, so with GW_DEMO the core runs no rounds itself (HealthMonitor) and the rows show the health the seed
      // recorded; the deferred first round is a second lock for a core before that. Test connection and Test all
      // still check for real.
      ...(demo ? ['-Dgateway.connections.health-interval=1m', '-Dgateway.connections.health-initial-delay=7d'] : []),
      '-jar',
      JAR,
    ],
    {
      cwd: ROOT,
      env: { ...process.env, GW_DEV_INTERNAL_SECRET: secret, ...(demo ? { GW_DEMO: '1' } : {}) },
      // stdin stays open: the core exits when its launcher's pipe closes (ARCHITECTURE.md §7.1).
      stdio: ['pipe', 'pipe', 'pipe'],
    },
  );
  children.push(core);
  core.stderr.on('data', (d) => process.stderr.write(prefix('core', d)));
  core.on('exit', (code) => {
    console.error(`[dev] the core exited (${code})`);
    stopAll(code ?? 1);
  });

  const ready = await new Promise((resolve) => {
    const lines = createInterface({ input: core.stdout });
    lines.on('line', (line) => {
      process.stdout.write(prefix('core', `${line}\n`));
      try {
        const msg = JSON.parse(line);
        if ('ready' in msg) resolve(msg);
      } catch {
        // not a protocol line
      }
    });
  });
  if (!ready.ready) {
    console.error(`[dev] the core did not start: ${ready.reason} ${ready.detail ?? ''}`);
    stopAll(1);
    return;
  }

  if (demo && !flag('--keep')) {
    // The demo world is a gateway in the middle of its setup (BLUEPRINT §9), but its home was empty at this start,
    // so the ready line says firstRun and the app would open on Welcome. Mark onboarding skipped, as "Skip setup"
    // does: every page opens as in the kit; /onboarding stays reachable.
    await api(corePort, secret, 'POST', '/api/onboarding/skip').catch((e) => console.warn(`[dev] ${e.message}`));
  }

  console.log(`[dev] core ready; UI on http://127.0.0.1:${uiPort}/ (gallery: /dev/elements)`);
  // The CLI's own entry point under this Node, not `npx ng`: one process that Ctrl+C really stops.
  const ngBin = createRequire(join(UI, 'package.json')).resolve('@angular/cli/bin/ng.js');
  const ng = spawn(process.execPath, [ngBin, 'serve', '--host', '127.0.0.1', '--port', String(uiPort)], {
    cwd: UI,
    env: { ...process.env, GW_CORE_PORT: String(corePort), GW_DEV_INTERNAL_SECRET: secret },
    stdio: 'inherit',
  });
  children.push(ng);
  ng.on('exit', (code) => stopAll(code ?? 0));
}

/** One signed admin API call, as ui/proxy.conf.mjs and Electron main sign them. */
async function api(port, secret, method, path) {
  const ts = Date.now();
  const nonce = randomBytes(16).toString('base64url');
  const mac = createHmac('sha256', Buffer.from(secret, 'base64url')).update(`${method}|${path}|${ts}|${nonce}`).digest('base64url');
  const res = await fetch(`http://127.0.0.1:${port}${path}`, {
    method,
    headers: { 'X-Gateway-Auth': `${ts}.${nonce}.${mac}`, 'Content-Type': 'application/json' },
    body: method === 'GET' ? undefined : '{}',
  });
  if (!res.ok) throw new Error(`${method} ${path}: ${res.status}`);
  return res.json();
}

function prefix(name, data) {
  return String(data)
    .split(/(?<=\n)/)
    .map((l) => `[${name}] ${l}`)
    .join('');
}

main().catch((e) => {
  console.error(e);
  stopAll(1);
});
