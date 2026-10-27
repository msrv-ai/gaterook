#!/usr/bin/env node
// A stand-in for the gateway core, speaking its process protocol (ARCHITECTURE.md §7.1; core: platform.boot.*) and a
// slice of its admin API behind the same local guard (platform.web.LocalGuardFilter): the unit tests of the
// supervisor and the Electron smoke start it the way main starts the real core.
//
//   node fake-core.mjs -Dgateway.home=<home> [other -D flags, ignored]
//
// Protocol: the bootstrap line on stdin (10 s), progress lines and one ready line on stdout, exit 0 on stdin EOF, 2
// after a ready:false line. Behaviour per start comes from <home>/fake-core.json (read at every start):
//   { "stepDelayMs": 20, "locked": null | "MASTER_KEY_MISSING", "failReason": null | "DB_LOCKED",
//     "hang": false, "crashAfterReadyMs": null, "exitCodeOnCrash": 137, "pendingApprovals": 0,
//     "firstRun": false, "stderr": ["…"] }
// The local API port is really bound: a taken port gives PORT_IN_USE, as the real core does. Every start appends
// what it got (ports, protection, whether a file key came — never the key) to <home>/fake-core-runs.jsonl, every
// signed request to <home>/fake-core-requests.jsonl. A locked core is unlocked by POST /api/master-key/restore or
// /create, without a restart, like the real one. Test-only routes, unsigned: POST /fake/emit {event, data} pushes an
// event to every open /api/events stream (its answer: how many streams are open).
import { createHmac, timingSafeEqual } from 'node:crypto';
import { appendFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { join } from 'node:path';

const out = process.stdout;
const line = (o) => out.write(JSON.stringify({ v: 1, ...o }) + '\n');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const homeArg = process.argv.find((a) => a.startsWith('-Dgateway.home='));
const home = homeArg ? homeArg.slice('-Dgateway.home='.length) : join(process.cwd(), '.fake-home');
mkdirSync(join(home, 'logs'), { recursive: true });
const logFile = join(home, 'logs', 'core.log');
const log = (text) => appendFileSync(logFile, `${new Date().toISOString()} INFO  fake-core ${text}\n`);
const control = existsSync(join(home, 'fake-core.json')) ? JSON.parse(readFileSync(join(home, 'fake-core.json'), 'utf8')) : {};
const stepDelay = control.stepDelayMs ?? 20;
for (const l of control.stderr ?? []) process.stderr.write(`${l}\n`);

// ── stdin: the bootstrap line, then EOF = the app is gone ──────────────────────────────────────────────────────────
let buffer = '';
let bootstrap = null;
let server = null;
const bootstrapTimer = setTimeout(() => {
  line({ ready: false, reason: 'BOOTSTRAP_TIMEOUT', detail: 'No bootstrap line on stdin within 10 s', log: logFile });
  process.exit(2);
}, 10_000);
process.stdin.setEncoding('utf8');
process.stdin.on('data', (chunk) => {
  if (bootstrap) return;
  buffer += chunk;
  const i = buffer.indexOf('\n');
  if (i >= 0) {
    clearTimeout(bootstrapTimer);
    void start(buffer.slice(0, i));
  }
});
process.stdin.on('end', () => {
  log('stdin closed: exiting');
  if (server) server.close();
  process.exit(0);
});

function invalid(why) {
  line({ ready: false, reason: 'BOOTSTRAP_INVALID', detail: `Invalid bootstrap: ${why}`, log: logFile });
  process.exit(2);
}

async function start(text) {
  let b;
  try {
    b = JSON.parse(text);
  } catch {
    return invalid('the bootstrap line is not JSON');
  }
  if (b.v !== 1) return invalid('unsupported bootstrap version');
  const secret = Buffer.from(String(b.launchSecret ?? ''), 'base64url');
  if (secret.length < 32) return invalid('launchSecret must be at least 32 bytes of base64url');
  if (!/^[A-Za-z0-9_-]{16,128}$/.test(String(b.instanceNonce ?? ''))) return invalid('instanceNonce');
  if (b.fileKey !== null && (typeof b.fileKey !== 'string' || Buffer.from(b.fileKey, 'base64url').length !== 32)) {
    return invalid('fileKey must be 32 bytes of base64url');
  }
  if (!Number.isInteger(b.port) || !Number.isInteger(b.publicPort) || b.port === b.publicPort) return invalid('ports');
  bootstrap = { secret, nonce: b.instanceNonce, port: b.port, publicPort: b.publicPort };
  appendFileSync(join(home, 'fake-core-runs.jsonl'), JSON.stringify({ pid: process.pid, port: b.port,
    publicPort: b.publicPort, protection: b.protection, fileKey: b.fileKey !== null, at: Date.now() }) + '\n');
  writeFileSync(join(home, 'fake-core.pid'), String(process.pid));
  log(`bootstrap read: port ${b.port}, protection ${b.protection}`);

  line({ step: 'master-key', state: 'running' });
  await sleep(stepDelay);
  line({ step: 'master-key', state: 'done' });
  if (control.failReason) {
    line({ ready: false, reason: control.failReason, detail: `Fake failure ${control.failReason}`, log: logFile });
    log(`ERROR failing on purpose: ${control.failReason}`);
    process.exit(2);
  }
  line({ step: 'db-migrations', state: 'running' });
  await sleep(stepDelay);
  line({ step: 'db-migrations', state: 'done' });
  if (control.hang) {
    log('hanging before the local API, on purpose');
    return;
  }
  line({ step: 'local-api', state: 'running' });
  server = createServer(handle);
  server.on('error', (e) => {
    line({ ready: false, reason: 'PORT_IN_USE', detail: `Address already in use: 127.0.0.1:${b.port}`,
      portOwner: { pid: 4242, user: 'someone' }, log: logFile });
    log(`ERROR can't bind 127.0.0.1:${b.port}: ${e.code}`);
    process.exit(2);
  });
  server.listen(b.port, '127.0.0.1', () => {
    line({ step: 'local-api', state: 'done' });
    started = Date.now();
    line({ ready: true, firstRun: control.firstRun === true, locked, version: '0.4.0',
      ports: { local: b.port, public: b.publicPort }, runtime: 'jvm', pid: process.pid });
    log(`ready on 127.0.0.1:${b.port}`);
    if (Number.isInteger(control.crashAfterReadyMs)) {
      setTimeout(() => {
        log('ERROR java.lang.OutOfMemoryError: Java heap space (fake crash)');
        process.exit(control.exitCodeOnCrash ?? 137);
      }, control.crashAfterReadyMs);
    }
  });
}

// ── The admin API behind the local guard ──────────────────────────────────────────────────────────────────────────
let started = Date.now();
const seenNonces = new Set();
const streams = new Set();
const recorded = [];
// Restore or Create of the master key unlocks the core without a restart, as secrets.MasterKeyService does.
let locked = control.locked ?? null;

function verify(req, target) {
  const header = req.headers['x-gateway-auth'];
  if (typeof header !== 'string') return false;
  const parts = header.split('.');
  if (parts.length !== 3 || !/^[0-9]{1,15}$/.test(parts[0]) || !/^[A-Za-z0-9_-]{16,64}$/.test(parts[1])) return false;
  if (Math.abs(Date.now() - Number(parts[0])) > 30_000) return false;
  const mac = createHmac('sha256', bootstrap.secret).update(`${req.method}|${target}|${parts[0]}|${parts[1]}`).digest();
  const given = Buffer.from(parts[2], 'base64url');
  if (given.length !== mac.length || !timingSafeEqual(given, mac)) return false;
  if (seenNonces.has(parts[1])) return false;
  seenNonces.add(parts[1]);
  return true;
}

function json(res, status, body) {
  const text = body === undefined ? '' : JSON.stringify(body);
  res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  res.end(text);
}

function readBody(req) {
  return new Promise((resolve) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const text = Buffer.concat(chunks).toString('utf8');
      try {
        resolve(text ? JSON.parse(text) : null);
      } catch {
        resolve(text);
      }
    });
  });
}

async function handle(req, res) {
  const url = new URL(req.url, 'http://x');
  const target = req.url;
  if (url.pathname.startsWith('/fake/')) return controlRoute(req, res, url);
  if (req.headers.host !== `127.0.0.1:${bootstrap.port}` && req.headers.host !== `localhost:${bootstrap.port}`) {
    res.writeHead(421).end();
    return;
  }
  if (req.headers.origin !== undefined) {
    res.writeHead(403).end();
    return;
  }
  if (!url.pathname.startsWith('/api/') && !url.pathname.startsWith('/internal/')) {
    res.writeHead(404).end();
    return;
  }
  const site = req.headers['sec-fetch-site'];
  if (site === 'cross-site' || site === 'same-site') {
    res.writeHead(403).end();
    return;
  }
  if (!verify(req, target)) {
    res.writeHead(401).end();
    return;
  }
  res.setHeader('X-Gateway-Instance', bootstrap.nonce);
  const body = await readBody(req);
  const entry = { method: req.method, path: url.pathname, query: url.search, body, headers: {
    origin: req.headers.origin ?? null, cookie: req.headers.cookie ?? null, referer: req.headers.referer ?? null,
    secFetchMode: req.headers['sec-fetch-mode'] ?? null, contentType: req.headers['content-type'] ?? null } };
  recorded.push(entry);
  appendFileSync(join(home, 'fake-core-requests.jsonl'), JSON.stringify(entry) + '\n');
  const p = url.pathname;
  if (p === '/api/health') {
    return json(res, 200, {
      status: locked ? 'locked' : 'ok', locked, version: '0.4.0', uptimeMs: Date.now() - started,
      ports: { local: bootstrap.port, public: bootstrap.publicPort }, runtime: 'jvm',
      db: { path: join(home, 'gateway.db'), sqlite: '3.53.4', journal: 'wal', schema: '1', sizeBytes: 4096 },
      masterKey: { protection: 'off', problems: [], warnings: [] }, tunnel: { state: 'off', host: null }, warnings: [],
    });
  }
  if (p === '/api/events') {
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store' });
    res.write(': stream open\n\nretry: 2000\n\n');
    streams.add(res);
    const beat = setInterval(() => res.write(': hb\n\n'), 15_000);
    // The response's close: the client went away (the request's own close comes once its body was read).
    res.on('close', () => {
      clearInterval(beat);
      streams.delete(res);
    });
    return undefined;
  }
  if (locked && !['/api/master-key', '/api/master-key/restore', '/api/master-key/create', '/internal/files/grant'].includes(p)) {
    return json(res, 503, { type: 'about:blank', title: 'GateRook is locked', locked });
  }
  if (p === '/api/master-key') {
    return json(res, 200, { path: join(home, 'master.keyset'), mode: null, owner: null, keys: [], protection: 'off',
      protects: 3, lastBackupAt: null, problems: locked ? ['key-missing'] : [], warnings: [], locked, rotation: null });
  }
  if ((p === '/api/master-key/restore' || p === '/api/master-key/create') && req.method === 'POST') {
    if (!locked) return json(res, 409, { title: 'The master key is not missing', reason: 'NOT_LOCKED' });
    locked = null;
    log('unlocked by a master key restore or create');
    return json(res, 200, p.endsWith('restore') ? { secretsReadable: 3 } : { needValue: [] });
  }
  if (p === '/api/nav/counters') return json(res, 200, { pendingApprovals: control.pendingApprovals ?? 0, tunnel: 'off' });
  if (p === '/api/settings/general') {
    return json(res, 200, { name: 'general', values: { openAtLogin: true, startHidden: true, autoUpdate: true,
      singleKeyShortcuts: true }, version: 1, updatedAt: null });
  }
  if (/^\/api\/approvals\/ap_[a-z0-9]{12}\/decision$/.test(p) && req.method === 'POST') {
    if (body?.decision === 'approve' && body?.via === 'desktop-notification') {
      return json(res, 422, { title: 'Approve from a notification is not allowed' });
    }
    return json(res, 200, { id: p.split('/')[3], status: 'rejected' });
  }
  if (p === '/internal/notifications/desktop' && req.method === 'POST') return json(res, 204);
  if (p === '/internal/keyset/protect' && req.method === 'POST') return json(res, 204);
  if (p === '/internal/files/grant' && req.method === 'POST') return json(res, 200, { token: 'ft_fake', expiresInSeconds: 300 });
  const mcpb = /^\/internal\/clients\/links\/([A-Za-z0-9_-]+)\/mcpb$/.exec(p);
  // `outside`: a core that names a file outside the extensions folder (main must refuse to open it).
  if (mcpb) return json(res, 200, { path: mcpb[1] === 'outside' ? '/etc/hosts'
    : join(home, 'clients', 'claude-desktop', `gaterook-${mcpb[1]}.mcpb`) });
  if (p === '/api/clients') return json(res, 200, { scannedAt: null, clients: [] });
  if (p === '/internal/bridge/tokens' && req.method === 'POST') {
    return json(res, 200, { v: 1, ok: true, token: 'gwb_fake', expiresAt: new Date(Date.now() + 900_000).toISOString(),
      url: `http://127.0.0.1:${bootstrap.port}/mcp/${body?.endpoint ?? 'dev'}` });
  }
  return json(res, 404, undefined);
}

async function controlRoute(req, res, url) {
  if (url.pathname === '/fake/emit' && req.method === 'POST') {
    const { event, data } = await readBody(req);
    for (const s of streams) s.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
    return json(res, 200, { streams: streams.size });
  }
  if (url.pathname === '/fake/requests') return json(res, 200, recorded);
  res.writeHead(404).end();
  return undefined;
}
