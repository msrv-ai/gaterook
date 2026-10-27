// The token broker of MCP Gateway (ARCHITECTURE.md A7, §7.1, §7.2; report 03 §3.1, §7.4). Electron's main process
// runs it: on macOS and Linux a Unix socket ~/.mcp-gateway/run/<random>.sock (folder 0700, socket 0600), on Windows a
// named pipe \\.\pipe\mcp-gateway-<random 128 bit>; the name it chose goes into run/broker.json (0600), where the
// bridges look it up. A random name means nobody can take the name before the app starts.
//
// Protocol v1: one connection, one request: a JSON line of at most 4 KiB, answered with one JSON line.
//   → {"v":1,"op":"token","client":"codex","endpoint":"dev","grant":"gr_…","pid":48211,"bridge":"0.4.0"}
//   ← {"v":1,"ok":true,"token":"gwb_…","expiresAt":"…","url":"http://127.0.0.1:7465/mcp/dev","instance":"…"}
//   ← {"v":1,"ok":false,"error":"grant_revoked","message":"Access for Codex to dev was revoked in MCP Gateway…"}
//   → {"v":1,"op":"ping"}  ← {"v":1,"ok":true,"broker":"0.4.0"}
// Tokens come from the core (POST /internal/bridge/tokens, signed with the launch secret); the broker decides nothing
// except asking the person when the core says a prod grant needs confirmation (the confirm hook).

import crypto from 'node:crypto';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';

export const PROTOCOL_VERSION = 1;
export const BROKER_VERSION = '0.4.0';
export const MAX_REQUEST_BYTES = 4096;

const NOT_RUNNING = 'MCP Gateway is not running. Open MCP Gateway, then try again.';

/** run/broker.json of a gateway home. */
export function brokerInfoPath(home) {
  return path.join(home, 'run', 'broker.json');
}

/** What run/broker.json says, or null. */
export function readBrokerInfo(home) {
  try {
    const info = JSON.parse(fs.readFileSync(brokerInfoPath(home), 'utf8'));
    return info && typeof info.path === 'string' ? info : null;
  } catch {
    return null;
  }
}

/** A fresh, unguessable socket or pipe name. */
export function randomBrokerPath(home, platform = process.platform) {
  const random = crypto.randomBytes(16);
  return platform === 'win32'
    ? `\\\\.\\pipe\\mcp-gateway-${random.toString('hex')}`
    : path.join(home, 'run', `${random.toString('base64url')}.sock`);
}

/** Whether something answers a ping on `address` within `ms`. */
export function ping(address, ms = 1000) {
  return new Promise((resolve) => {
    const s = net.connect(address);
    let buf = '';
    const done = (v) => {
      s.destroy();
      clearTimeout(t);
      resolve(v);
    };
    const t = setTimeout(() => done(false), ms);
    s.on('connect', () => s.write(JSON.stringify({ v: PROTOCOL_VERSION, op: 'ping' }) + '\n'));
    s.on('data', (d) => {
      buf += d;
      if (buf.includes('\n')) done(true);
    });
    s.on('error', () => done(false));
    s.on('end', () => done(buf.length > 0));
  });
}

function refusal(error, message) {
  return { v: PROTOCOL_VERSION, ok: false, error, message };
}

function writeOwnerOnly(file, text) {
  const tmp = `${file}.${process.pid}.${crypto.randomBytes(4).toString('hex')}.tmp`;
  fs.writeFileSync(tmp, text, { mode: 0o600 });
  if (process.platform !== 'win32') fs.chmodSync(tmp, 0o600);
  fs.renameSync(tmp, file);
}

/** Checks the shape of a token request; the core checks the rest (grant ↔ endpoint ↔ client). */
function tokenRequest(req) {
  const ok = typeof req.client === 'string' && req.client.length <= 32
    && typeof req.endpoint === 'string' && req.endpoint.length <= 63
    && typeof req.grant === 'string' && req.grant.length <= 40
    && (req.pid === undefined || Number.isInteger(req.pid))
    && (req.bridge === undefined || (typeof req.bridge === 'string' && req.bridge.length <= 32));
  return ok ? {
    v: PROTOCOL_VERSION, op: 'token', client: req.client, endpoint: req.endpoint, grant: req.grant,
    ...(req.pid === undefined ? {} : { pid: req.pid }), ...(req.bridge === undefined ? {} : { bridge: req.bridge }),
  } : null;
}

/**
 * Starts the broker.
 *
 * @param {object} options
 * @param {string} options.home  the gateway home (~/.mcp-gateway)
 * @param {{request(path: string, body: object): Promise<object>}} options.core  e.g. createCoreClient(…)
 * @param {(ask: {client: string, endpoint: string, grant: string, message: string}) => Promise<boolean>} [options.confirm]
 *        asks the person whether a client may use a prod endpoint ("Codex wants to use incident-prod"); without it a
 *        bridge gets the core's "confirmation_required" and tries again later
 * @param {number} [options.confirmTimeoutMs]  how long a bridge waits for the person, default 55 s
 * @param {number} [options.requestTimeoutMs]  how long a connection may take to send its request line, default 3 s
 * @param {number} [options.maxConnections]    default 32
 * @param {(line: string) => void} [options.log]  one line per refusal and error, never a token
 * @param {string} [options.platform]  process.platform
 * @returns {Promise<{path: string, infoPath: string, close(): Promise<void>}>}
 */
export async function startBroker({
  home, core, confirm, confirmTimeoutMs = 55_000, requestTimeoutMs = 3000, maxConnections = 32,
  log = () => {}, platform = process.platform,
}) {
  if (!home) throw new Error('startBroker needs the gateway home');
  if (!core || typeof core.request !== 'function') throw new Error('startBroker needs a core client');
  const run = path.join(home, 'run');
  const infoPath = brokerInfoPath(home);
  if (platform !== 'win32') {
    fs.mkdirSync(run, { recursive: true, mode: 0o700 });
    fs.chmodSync(run, 0o700);
  } else {
    fs.mkdirSync(run, { recursive: true });
  }
  const previous = readBrokerInfo(home);
  if (previous && await ping(previous.path)) {
    throw Object.assign(new Error(`Another broker answers on ${previous.path}`), { code: 'EADDRINUSE' });
  }
  if (previous && platform !== 'win32' && path.dirname(previous.path) === run && previous.path.endsWith('.sock')) {
    fs.rmSync(previous.path, { force: true });
  }

  const address = randomBrokerPath(home, platform);
  if (platform !== 'win32') {
    // sun_path holds 108 bytes on Linux and 104 on macOS and the BSDs, the final NUL included; listen() would only
    // say EINVAL (review K1-6).
    const max = platform === 'linux' ? 107 : 103;
    const length = Buffer.byteLength(address);
    if (length > max) {
      throw Object.assign(new Error(`The gateway home ${home} is too deep: the broker's socket ${address} would be `
        + `${length} bytes, too long for a Unix socket on this system (at most ${max})`), { code: 'ENAMETOOLONG' });
    }
  }
  const asking = new Map();

  async function askOnce(answer, req) {
    if (!confirm) return null;
    let pending = asking.get(req.grant);
    if (!pending) {
      pending = Promise.race([
        Promise.resolve().then(() => confirm({
          client: req.client, endpoint: req.endpoint, grant: req.grant, message: answer.message,
        })).then((v) => v === true, () => false),
        new Promise((resolve) => setTimeout(() => resolve(null), confirmTimeoutMs).unref()),
      ]).finally(() => asking.delete(req.grant));
      asking.set(req.grant, pending);
    }
    return pending;
  }

  async function token(req) {
    let answer;
    try {
      answer = await core.request('/internal/bridge/tokens', req);
    } catch (e) {
      log(`token for ${req.client} on ${req.endpoint}: the core is unavailable (${e.message})`);
      return refusal('core_unavailable', NOT_RUNNING);
    }
    if (answer && answer.ok === false && answer.error === 'confirmation_required') {
      const allowed = await askOnce(answer, req);
      if (allowed === null) return answer;
      try {
        await core.request('/internal/bridge/confirm', { grant: req.grant, decision: allowed ? 'allow' : 'deny' });
        answer = await core.request('/internal/bridge/tokens', req);
      } catch (e) {
        log(`confirmation for ${req.client} on ${req.endpoint}: the core is unavailable (${e.message})`);
        return refusal('core_unavailable', NOT_RUNNING);
      }
    }
    if (answer && answer.ok === false) log(`no token for ${req.client} on ${req.endpoint}: ${answer.error}`);
    return answer;
  }

  async function handle(line) {
    let req;
    try {
      req = JSON.parse(line);
    } catch {
      return refusal('bad_request', 'The request isn\'t JSON.');
    }
    if (!req || typeof req !== 'object' || req.v !== PROTOCOL_VERSION) {
      return refusal('bad_request', 'MCP Gateway\'s broker speaks protocol v1: update the bridge.');
    }
    if (req.op === 'ping') return { v: PROTOCOL_VERSION, ok: true, broker: BROKER_VERSION };
    if (req.op !== 'token') return refusal('bad_request', `Unknown operation ${String(req.op).slice(0, 32)}.`);
    const clean = tokenRequest(req);
    if (!clean) return refusal('bad_request', 'The token request is malformed.');
    return token(clean);
  }

  const sockets = new Set();
  const server = net.createServer((socket) => {
    sockets.add(socket);
    socket.on('close', () => sockets.delete(socket));
    let buf = '';
    let answered = false;
    socket.setEncoding('utf8');
    socket.setTimeout(requestTimeoutMs, () => {
      if (!answered) socket.destroy();
    });
    socket.on('data', (d) => {
      if (answered) return;
      buf += d;
      if (Buffer.byteLength(buf, 'utf8') > MAX_REQUEST_BYTES + 1) {
        answered = true;
        socket.destroy();
        return;
      }
      const i = buf.indexOf('\n');
      if (i < 0) return;
      answered = true;
      // The request is in; waiting for the person may take longer than reading it did.
      socket.setTimeout(0);
      handle(buf.slice(0, i)).then((a) => socket.end(JSON.stringify(a) + '\n'),
        () => socket.end(JSON.stringify(refusal('core_unavailable', NOT_RUNNING)) + '\n'));
    });
    socket.on('error', () => socket.destroy());
  });
  server.maxConnections = maxConnections;
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(address, () => {
      server.off('error', reject);
      resolve();
    });
  });
  if (platform !== 'win32') fs.chmodSync(address, 0o600);
  writeOwnerOnly(infoPath, JSON.stringify({
    v: PROTOCOL_VERSION, path: address, pid: process.pid, broker: BROKER_VERSION, startedAt: new Date().toISOString(),
  }) + '\n');

  return {
    path: address,
    infoPath,
    async close() {
      const closed = new Promise((resolve) => server.close(() => resolve()));
      for (const s of sockets) s.destroy();
      await closed;
      const info = readBrokerInfo(home);
      if (info && info.path === address) fs.rmSync(infoPath, { force: true });
      if (platform !== 'win32') fs.rmSync(address, { force: true });
    },
  };
}
