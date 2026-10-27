#!/usr/bin/env node
// gw-bridge.mjs: the stdio bridge inside MCP Gateway's Claude Desktop extensions (ARCHITECTURE.md §7.2, report 03
// §7.4). Claude Desktop runs it with its own Node (>= 18); it has no dependencies and holds no secret.
//
//   node gw-bridge.mjs stdio --endpoint dev --grant gr_… [--client claude-desktop]
//
// Each line on stdin is a JSON-RPC message; it is POSTed to the endpoint URL the broker names, with a 15-minute token
// the broker gets for the grant from MCP Gateway. The answer goes to stdout as one line. On 401 the bridge asks for a
// new token once and sends again. It never exits on an error, so Claude doesn't mark the server as failed; it answers
// the request with a JSON-RPC error saying what to do. Logs go to stderr, never a token.
//
// Environment: GW_BROKER (the broker's run/broker.json, or its socket or pipe), GW_HOME (the gateway home, default
// ~/.mcp-gateway), GW_CLIENT (claude-desktop when absent).

import fs from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import readline from 'node:readline';
import { fileURLToPath } from 'node:url';

const VERSION = '0.4.0';
const MAX_ANSWER = 64 * 1024 * 1024;
const BROKER_WAIT_MS = 60_000;
const REQUEST_WAIT_MS = 120_000;
const INSTANCE_HEADER = 'x-gateway-bridge-instance';
const NOT_RUNNING = 'MCP Gateway is not running. Open MCP Gateway, then try again.';

const log = (...a) => process.stderr.write(`[gw-bridge] ${a.join(' ')}\n`);

/** --name value pairs after the subcommand. */
export function parseArgs(argv) {
  const out = { command: argv[0] };
  for (let i = 1; i < argv.length; i++) {
    const a = argv[i];
    if (!a.startsWith('--')) throw new Error(`unexpected argument ${a}`);
    const v = argv[i + 1];
    if (v === undefined || v.startsWith('--')) throw new Error(`${a} needs a value`);
    out[a.slice(2)] = v;
    i++;
  }
  return out;
}

/** Where the broker listens: GW_BROKER names a socket, a pipe or broker.json; else <home>/run/broker.json. */
export function brokerAddress(env = process.env) {
  const given = env.GW_BROKER;
  const home = env.GW_HOME || path.join(os.homedir(), '.mcp-gateway');
  const info = given && !given.endsWith('.sock') && !given.startsWith('\\\\.\\pipe\\')
    ? given : given ? null : path.join(home, 'run', 'broker.json');
  if (!info) return given;
  let parsed;
  try {
    // A broker that died while writing it leaves a file that isn't JSON: that is "not running" too.
    parsed = JSON.parse(fs.readFileSync(info, 'utf8'));
  } catch {
    throw Object.assign(new Error(NOT_RUNNING), { code: 'broker_unavailable' });
  }
  if (!parsed || typeof parsed.path !== 'string' || !parsed.path) {
    throw Object.assign(new Error(NOT_RUNNING), { code: 'broker_unavailable' });
  }
  return parsed.path;
}

/** One request to the broker: a JSON line out, a JSON line back. */
export function brokerCall(address, request, waitMs = BROKER_WAIT_MS) {
  return new Promise((resolve, reject) => {
    const socket = net.connect(address);
    let buf = '';
    let done = false;
    const finish = (err, value) => {
      if (done) return;
      done = true;
      socket.destroy();
      clearTimeout(timer);
      err ? reject(err) : resolve(value);
    };
    const timer = setTimeout(() => finish(Object.assign(new Error('The MCP Gateway broker didn\'t answer.'),
      { code: 'broker_timeout' })), waitMs);
    socket.setEncoding('utf8');
    socket.on('connect', () => socket.write(JSON.stringify(request) + '\n'));
    socket.on('data', (d) => {
      buf += d;
      if (buf.length > 64 * 1024) return finish(new Error('The broker\'s answer is too long.'));
      const i = buf.indexOf('\n');
      if (i < 0) return;
      try {
        finish(null, JSON.parse(buf.slice(0, i)));
      } catch {
        finish(new Error('The broker\'s answer isn\'t JSON.'));
      }
    });
    socket.on('error', (e) => finish(Object.assign(new Error(NOT_RUNNING), { code: 'broker_unavailable', cause: e })));
    socket.on('end', () => finish(Object.assign(new Error(NOT_RUNNING), { code: 'broker_unavailable' })));
  });
}

/** The header value of Mcp-Name: as is when plain ASCII, else the base64 sentinel of the 2026-07-28 transport. */
export function headerName(value) {
  return /^[\x20-\x7e]*$/.test(value) && value.trim() === value ? value
    : `=?base64?${Buffer.from(value, 'utf8').toString('base64')}?=`;
}

/** Data lines of an SSE body, joined per event. */
export function sseData(body) {
  const out = [];
  for (const event of body.split(/\r?\n\r?\n/)) {
    const data = event.split(/\r?\n/).filter((l) => l.startsWith('data:')).map((l) => l.slice(5).replace(/^ /, ''));
    if (data.length) out.push(data.join('\n'));
  }
  return out;
}

/** The bridge itself; exported so that tests drive it without a process. */
export function createBridge({ endpoint, grant, client, env = process.env, write, brokerWaitMs = BROKER_WAIT_MS }) {
  let token = null;
  let negotiated = null;
  let fetching = null;

  /**
   * The token to send with. `force` asks the broker for a new one — unless another message already replaced `stale`,
   * the token that was just refused. Messages that need a token at the same moment share one broker request, so a
   * burst of calls (or of 401s after the token expired) takes one token, not one each: a grant keeps only four.
   */
  function tokenFor(force, stale = null) {
    const usable = token && token.exp - Date.now() > 60_000;
    if (usable && (!force || (stale !== null && token !== stale))) return Promise.resolve(token);
    if (!fetching) fetching = fetchToken().finally(() => { fetching = null; });
    return fetching;
  }

  async function fetchToken() {
    token = null;
    const answer = await brokerCall(brokerAddress(env), {
      v: 1, op: 'token', client, endpoint, grant, pid: process.pid, bridge: VERSION,
    }, brokerWaitMs);
    if (!answer || answer.ok !== true) {
      throw Object.assign(new Error(answer && answer.message ? answer.message : NOT_RUNNING),
        { code: answer && answer.error ? answer.error : 'core_unavailable' });
    }
    let url;
    try {
      url = new URL(answer.url);
    } catch {
      throw new Error('The broker named an endpoint URL the bridge doesn\'t accept.');
    }
    // Only the local core, only this endpoint: a broker (or anything posing as one) can't send the token elsewhere.
    if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || !/^\d+$/.test(url.port)
      || url.pathname !== `/mcp/${endpoint}` || url.search || url.username || url.password) {
      throw new Error('The broker named an endpoint URL the bridge doesn\'t accept.');
    }
    token = { value: answer.token, url, exp: Date.parse(answer.expiresAt), instance: answer.instance };
    return token;
  }

  function post(t, body, msg) {
    const headers = {
      'Content-Type': 'application/json',
      Accept: 'application/json, text/event-stream',
      Authorization: `Bearer ${t.value}`,
    };
    const meta = msg && msg.params && msg.params._meta;
    const modern = meta && meta['io.modelcontextprotocol/protocolVersion'];
    if (modern) {
      headers['MCP-Protocol-Version'] = modern;
      headers['Mcp-Method'] = msg.method;
      const name = msg.method === 'resources/read' ? msg.params.uri
        : (msg.method === 'tools/call' || msg.method === 'prompts/get') ? msg.params.name : null;
      if (typeof name === 'string') headers['Mcp-Name'] = headerName(name);
    } else if (negotiated && msg && msg.method !== 'initialize') {
      headers['MCP-Protocol-Version'] = negotiated;
    }
    return new Promise((resolve, reject) => {
      // agent: false — straight to 127.0.0.1: Node's global agent follows HTTP_PROXY under NODE_USE_ENV_PROXY and
      // would hand the token to a proxy (review K1-11).
      const req = http.request({
        host: '127.0.0.1', port: Number(t.url.port), path: t.url.pathname, method: 'POST', headers,
        timeout: REQUEST_WAIT_MS, agent: false,
      }, (res) => {
        const chunks = [];
        let size = 0;
        res.on('data', (c) => {
          size += c.length;
          if (size > MAX_ANSWER) {
            req.destroy(new Error('The answer is larger than 64 MiB.'));
            return;
          }
          chunks.push(c);
        });
        res.on('end', () => resolve({
          status: res.statusCode, type: String(res.headers['content-type'] || ''),
          instance: res.headers[INSTANCE_HEADER], body: Buffer.concat(chunks).toString('utf8'),
        }));
        res.on('error', reject);
      });
      req.on('timeout', () => req.destroy(new Error('MCP Gateway didn\'t answer in time.')));
      req.on('error', (e) => reject(Object.assign(new Error(e.code === 'ECONNREFUSED' ? NOT_RUNNING : e.message),
        { code: e.code === 'ECONNREFUSED' ? 'core_unavailable' : e.code })));
      req.end(body);
    });
  }

  function reply(id, code, message) {
    if (id === undefined) return;
    write(JSON.stringify({ jsonrpc: '2.0', id, error: { code, message } }));
  }

  function messageFor(e) {
    switch (e.code) {
      case 'grant_revoked':
        return `Access to ${endpoint} was revoked in MCP Gateway. Remove this extension in Claude: Settings › `
          + 'Extensions, or connect again from MCP Gateway.';
      default:
        return e.message || NOT_RUNNING;
    }
  }

  async function handle(line) {
    if (!line.trim()) return;
    let msg;
    try {
      msg = JSON.parse(line);
    } catch {
      write(JSON.stringify({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Parse error' } }));
      return;
    }
    const id = msg && typeof msg === 'object' && !Array.isArray(msg) ? msg.id : undefined;
    try {
      const held = token;
      let t = await tokenFor(false);
      let r;
      try {
        r = await post(t, line, msg);
      } catch (e) {
        // Nobody got the request. A token kept from before may name the port of a core that has since moved
        // (Settings › Local API, a restart): the broker knows the current URL, and sending again is safe.
        if (e.code !== 'core_unavailable' || t !== held) {
          token = null;
          throw e;
        }
        t = await tokenFor(true, held);
        r = await post(t, line, msg);
      }
      if (r.status === 401) {
        t = await tokenFor(true, t);
        r = await post(t, line, msg);
      }
      if (t.instance && r.instance !== t.instance) {
        // Another run of the core has another id, and it may still accept a token issued before its restart. The
        // broker vouches for the run it gets tokens from now: an answer from that run goes through (it already
        // ran there, so it isn't sent again); an answer from any other program on the port goes nowhere, and that
        // program gets no further request with a token.
        let current = null;
        try {
          current = await tokenFor(true, t);
        } catch {
          // No broker, no core: not running.
        }
        if (!current || !current.instance || current.instance !== r.instance) {
          token = null;
          reply(id, -32603, NOT_RUNNING);
          return;
        }
      }
      if (r.status === 202) return;
      if (r.type.startsWith('text/event-stream')) {
        for (const data of sseData(r.body)) write(data.trim());
        return;
      }
      if (r.type.startsWith('application/json') && r.body.trim()) {
        if (msg.method === 'initialize') {
          try {
            negotiated = JSON.parse(r.body).result?.protocolVersion ?? negotiated;
          } catch {
            // The answer goes through as it is.
          }
        }
        write(r.body.trim());
        return;
      }
      reply(id, -32603, `MCP Gateway answered HTTP ${r.status}.`);
    } catch (e) {
      log(`${msg && msg.method ? msg.method : 'message'} failed: ${e.code || ''} ${e.message}`);
      reply(id, -32603, messageFor(e));
    }
  }

  return { handle, tokenFor, state: () => ({ negotiated, hasToken: token !== null }) };
}

async function main() {
  const argv = process.argv.slice(2);
  if (argv[0] === '--version') {
    process.stdout.write(`${VERSION}\n`);
    return;
  }
  let args;
  try {
    args = parseArgs(argv);
    if (args.command !== 'stdio') throw new Error('usage: gw-bridge.mjs stdio --endpoint <slug> --grant <gr_…>');
    if (!/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(args.endpoint || '')) throw new Error('--endpoint is a slug');
    if (!/^gr_[a-z0-9]{12}$/.test(args.grant || '')) throw new Error('--grant is an access id gr_…');
  } catch (e) {
    log(e.message);
    process.exitCode = 3;
    return;
  }
  const client = args.client || process.env.GW_CLIENT || 'claude-desktop';
  const bridge = createBridge({
    endpoint: args.endpoint, grant: args.grant, client, write: (line) => process.stdout.write(line + '\n'),
  });
  const pending = new Set();
  const lines = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
  lines.on('line', (line) => {
    const p = bridge.handle(line).finally(() => pending.delete(p));
    pending.add(p);
  });
  lines.on('close', async () => {
    await Promise.allSettled([...pending]);
  });
}

function invokedDirectly() {
  try {
    return Boolean(process.argv[1]) && fs.realpathSync(process.argv[1]) === fs.realpathSync(fileURLToPath(import.meta.url));
  } catch {
    return false;
  }
}

if (invokedDirectly()) {
  main().catch((e) => {
    log(e.message);
  });
}
