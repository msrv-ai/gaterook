import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { after, before, describe, it } from 'node:test';

import { createCoreClient, startBroker } from '../index.mjs';
import { headerName, parseArgs, sseData } from '../gw-bridge.mjs';
import { fakeCore, issued, SECRET, tempHome } from './helpers.mjs';

const BRIDGE = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'gw-bridge.mjs');
const GRANT = 'gr_abcdefghijkl';

/** The bridge as Claude Desktop runs it: a process, JSON-RPC lines in and out. */
function startBridge(home, { endpoint = 'dev', extraEnv = {}, args = [] } = {}) {
  const child = spawn(process.execPath, [BRIDGE, 'stdio', '--endpoint', endpoint, '--grant', GRANT, ...args], {
    env: { PATH: process.env.PATH, GW_HOME: home, GW_CLIENT: 'claude-desktop', ...extraEnv },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  const lines = [];
  const waiters = [];
  let buf = '';
  let stderr = '';
  child.stdout.setEncoding('utf8');
  child.stdout.on('data', (d) => {
    buf += d;
    let i;
    while ((i = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, i);
      buf = buf.slice(i + 1);
      const w = waiters.shift();
      if (w) w(line); else lines.push(line);
    }
  });
  child.stderr.on('data', (d) => { stderr += d; });
  return {
    child,
    stderr: () => stderr,
    send(msg) {
      child.stdin.write((typeof msg === 'string' ? msg : JSON.stringify(msg)) + '\n');
    },
    next(ms = 5000) {
      if (lines.length) return Promise.resolve(JSON.parse(lines.shift()));
      return new Promise((resolve, reject) => {
        const t = setTimeout(() => reject(new Error('no answer from the bridge; stderr: ' + stderr)), ms);
        waiters.push((l) => { clearTimeout(t); resolve(JSON.parse(l)); });
      });
    },
    async call(msg) {
      this.send(msg);
      return this.next();
    },
    stop() {
      child.stdin.end();
      return new Promise((resolve) => child.on('exit', resolve));
    },
  };
}

describe('gw-bridge.mjs', () => {
  let core;
  let broker;
  let home;
  let tokenAnswers;
  let mcpAnswers;
  let issuedCount;

  before(async () => {
    tokenAnswers = [];
    mcpAnswers = [];
    issuedCount = 0;
    core = await fakeCore({
      tokens: async (r) => tokenAnswers.shift()?.(r) ?? issued(core.port, r.endpoint, ++issuedCount),
      mcp: async (entry) => {
        const custom = mcpAnswers.shift();
        if (custom) return custom(entry);
        if (entry.headers.authorization !== `Bearer gwb_test_token_${issuedCount}`) return { status: 401 };
        const msg = entry.body;
        if (msg.id === undefined) return { status: 202 };
        const result = msg.method === 'initialize'
          ? { protocolVersion: '2025-11-25', capabilities: { tools: {} }, serverInfo: { name: 'gaterook' } }
          : msg.method === 'tools/list' ? { tools: [{ name: 'sql_query' }] } : { echo: msg.method };
        return { status: 200, type: 'application/json', body: JSON.stringify({ jsonrpc: '2.0', id: msg.id, result }) };
      },
    });
    home = tempHome('bridge');
    broker = await startBroker({ home, core: createCoreClient({ origin: core.origin, launchSecret: SECRET }) });
  });

  after(async () => {
    await broker?.close();
    await core?.close();
    fs.rmSync(home, { recursive: true, force: true });
  });

  it('speaks to the endpoint with a broker token and the negotiated revision', async () => {
    const b = startBridge(home);
    try {
      const init = await b.call({ jsonrpc: '2.0', id: 0, method: 'initialize', params: { protocolVersion: '2025-11-25',
        capabilities: {}, clientInfo: { name: 'claude-ai', version: '1' } } });
      assert.equal(init.result.protocolVersion, '2025-11-25');
      b.send({ jsonrpc: '2.0', method: 'notifications/initialized' });
      const list = await b.call({ jsonrpc: '2.0', id: 1, method: 'tools/list', params: {} });
      assert.deepEqual(list.result.tools, [{ name: 'sql_query' }]);
      const posts = core.seen.filter((s) => s.url === '/mcp/dev');
      assert.equal(posts[0].headers['mcp-protocol-version'], undefined, 'initialize has no revision header');
      assert.equal(posts.at(-1).headers['mcp-protocol-version'], '2025-11-25');
      assert.equal(posts.at(-1).headers.accept, 'application/json, text/event-stream');
      const asked = core.seen.find((s) => s.url === '/internal/bridge/tokens');
      assert.equal(asked.body.client, 'claude-desktop');
      assert.equal(asked.body.grant, GRANT);
      assert.equal(asked.body.bridge,
        JSON.parse(fs.readFileSync(new URL('../package.json', import.meta.url), 'utf8')).version);
      assert.equal(b.stderr().includes('gwb_'), false, 'no token in the logs');
    } finally {
      await b.stop();
    }
  });

  it('gets a new token once after a 401 and sends again', async () => {
    const b = startBridge(home);
    try {
      await b.call({ jsonrpc: '2.0', id: 0, method: 'initialize', params: { protocolVersion: '2025-11-25' } });
      const before = issuedCount;
      // The core forgets the token (expired, rotated): the next call is 401, the bridge asks the broker again.
      issuedCount++;
      const list = await b.call({ jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} });
      assert.deepEqual(list.result.tools, [{ name: 'sql_query' }]);
      assert.equal(issuedCount, before + 2);
    } finally {
      await b.stop();
    }
  });

  it('takes one token for a burst of calls, and one more when they all meet a 401', async () => {
    const b = startBridge(home);
    const asked = () => core.seen.filter((s) => s.url === '/internal/bridge/tokens').length;
    const burst = async (from) => {
      const ids = [0, 1, 2, 3, 4].map((i) => from + i);
      for (const id of ids) b.send({ jsonrpc: '2.0', id, method: 'tools/list', params: {} });
      const answers = [];
      for (const _ of ids) answers.push(await b.next());
      assert.deepEqual(answers.map((a) => a.id).sort((x, y) => x - y), ids);
      assert.ok(answers.every((a) => a.result), JSON.stringify(answers));
    };
    try {
      const before = asked();
      await burst(40);
      assert.equal(asked(), before + 1, 'five calls at once share one token');
      // The core forgets the token (it expired): all five meet a 401, and one new token serves them all.
      issuedCount++;
      await burst(50);
      assert.equal(asked(), before + 2);
    } finally {
      await b.stop();
    }
  });

  it('sends the modern headers from _meta, Mcp-Name as base64 when it isn\'t ASCII', async () => {
    const b = startBridge(home);
    try {
      await b.call({ jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'sql_query', arguments: {},
        _meta: { 'io.modelcontextprotocol/protocolVersion': '2026-07-28' } } });
      const post = core.seen.filter((s) => s.url === '/mcp/dev').at(-1);
      assert.equal(post.headers['mcp-protocol-version'], '2026-07-28');
      assert.equal(post.headers['mcp-method'], 'tools/call');
      assert.equal(post.headers['mcp-name'], 'sql_query');
      assert.equal(headerName('café'), `=?base64?${Buffer.from('café').toString('base64')}?=`);
    } finally {
      await b.stop();
    }
  });

  it('passes SSE data through, and answers a non-JSON status with an error carrying the request id', async () => {
    const b = startBridge(home);
    try {
      mcpAnswers.push(() => ({ status: 200, type: 'text/event-stream',
        body: 'event: message\ndata: {"jsonrpc":"2.0","id":5,"result":{"ok":true}}\n\n' }));
      assert.deepEqual(await b.call({ jsonrpc: '2.0', id: 5, method: 'ping' }), { jsonrpc: '2.0', id: 5,
        result: { ok: true } });
      mcpAnswers.push(() => ({ status: 403 }));
      const refused = await b.call({ jsonrpc: '2.0', id: 6, method: 'ping' });
      assert.equal(refused.id, 6);
      assert.match(refused.error.message, /HTTP 403/);
      const bad = await b.call('{not json');
      assert.equal(bad.error.code, -32700);
      assert.equal(bad.id, null);
    } finally {
      await b.stop();
    }
  });

  it('says what the broker says, keeps running, and accepts only the local endpoint URL', async () => {
    const b = startBridge(home);
    try {
      tokenAnswers.push(() => ({ v: 1, ok: false, error: 'grant_revoked', message: 'revoked' }));
      const revoked = await b.call({ jsonrpc: '2.0', id: 7, method: 'tools/list' });
      assert.match(revoked.error.message, /Access to dev was revoked in GateRook/);
      tokenAnswers.push(() => ({ ...issued(core.port, 'dev', ++issuedCount), url: 'http://evil.example/mcp/dev' }));
      const evil = await b.call({ jsonrpc: '2.0', id: 8, method: 'tools/list' });
      assert.match(evil.error.message, /doesn't accept/);
      tokenAnswers.push(() => ({ ...issued(core.port, 'other', ++issuedCount) }));
      assert.match((await b.call({ jsonrpc: '2.0', id: 9, method: 'tools/list' })).error.message, /doesn't accept/);
      assert.equal(b.child.exitCode, null, 'still running');
      const ok = await b.call({ jsonrpc: '2.0', id: 10, method: 'tools/list' });
      assert.ok(ok.result, 'and working again');
    } finally {
      await b.stop();
    }
  });

  it('refuses an answer that doesn\'t come from the core that issued the token', async () => {
    const b = startBridge(home);
    try {
      mcpAnswers.push(() => ({ status: 200, type: 'application/json', headers: { 'X-Gateway-Bridge-Instance': 'other' },
        body: '{"jsonrpc":"2.0","id":11,"result":{"stolen":true}}' }));
      const a = await b.call({ jsonrpc: '2.0', id: 11, method: 'tools/list' });
      assert.match(a.error.message, /GateRook is not running/);
    } finally {
      await b.stop();
    }
  });

  it('follows the core to another port and into its next run, sending nothing twice', async () => {
    const answer = (entry, result) => ({ status: 200, type: 'application/json',
      body: JSON.stringify({ jsonrpc: '2.0', id: entry.body.id, result }) });
    const moved = await fakeCore({ mcp: async (entry) => answer(entry, { port: 'old' }) });
    const b = startBridge(home);
    try {
      tokenAnswers.push(() => issued(moved.port, 'dev', ++issuedCount));
      assert.deepEqual((await b.call({ jsonrpc: '2.0', id: 30, method: 'tools/list' })).result, { port: 'old' });
      // The core moves to another port (Settings › Local API): the kept token's port refuses the connection, the
      // broker names the current URL, and the call goes there once.
      await moved.close();
      const there = await b.call({ jsonrpc: '2.0', id: 31, method: 'tools/list' });
      assert.deepEqual(there.result.tools, [{ name: 'sql_query' }]);
      // The core restarts: its new run still accepts the token, but answers with a new run id. The broker's next
      // token carries that id, so the answer goes through and the call isn't sent again.
      mcpAnswers.push((entry) => ({ ...answer(entry, { run: 2 }), headers: { 'X-Gateway-Bridge-Instance': 'run-2' } }));
      tokenAnswers.push(() => issued(core.port, 'dev', ++issuedCount, 'run-2'));
      const next = await b.call({ jsonrpc: '2.0', id: 32, method: 'tools/list' });
      assert.deepEqual(next.result, { run: 2 });
      assert.equal(core.seen.filter((s) => s.url === '/mcp/dev' && s.body && s.body.id === 32).length, 1);
      assert.equal(core.seen.filter((s) => s.url === '/mcp/dev' && s.body && s.body.id === 31).length, 1);
    } finally {
      await b.stop();
      await moved.close();
    }
  });

  it('goes straight to the core even when Node is told to use a proxy', async () => {
    // NODE_USE_ENV_PROXY makes Node's global agent follow HTTP_PROXY, for 127.0.0.1 too: the token would reach it.
    const proxied = [];
    const proxy = http.createServer((req, res) => {
      proxied.push(`${req.method} ${req.url} ${req.headers.authorization ?? ''}`);
      res.writeHead(502, { 'Content-Length': 0 });
      res.end();
    });
    await new Promise((resolve) => proxy.listen(0, '127.0.0.1', resolve));
    const viaProxy = `http://127.0.0.1:${proxy.address().port}`;
    const b = startBridge(home, { extraEnv: { NODE_USE_ENV_PROXY: '1', HTTP_PROXY: viaProxy, http_proxy: viaProxy } });
    try {
      const list = await b.call({ jsonrpc: '2.0', id: 14, method: 'tools/list', params: {} });
      assert.deepEqual(list.result?.tools, [{ name: 'sql_query' }], JSON.stringify(list));
      assert.deepEqual(proxied, []);
    } finally {
      await b.stop();
      await new Promise((resolve) => { proxy.closeAllConnections(); proxy.close(resolve); });
    }
  });

  it('says GateRook isn\'t running when there is no broker', async () => {
    const empty = tempHome('bridge-none');
    const b = startBridge(empty);
    try {
      const a = await b.call({ jsonrpc: '2.0', id: 12, method: 'initialize', params: {} });
      assert.equal(a.error.message, 'GateRook is not running. Open GateRook, then try again.');
      b.send({ jsonrpc: '2.0', method: 'notifications/initialized' });
      const again = await b.call({ jsonrpc: '2.0', id: 13, method: 'tools/list' });
      assert.equal(again.id, 13, 'notifications get no answer; the process keeps going');
    } finally {
      await b.stop();
      fs.rmSync(empty, { recursive: true, force: true });
    }
  });

  it('checks its arguments (exit 3)', async () => {
    const child = spawn(process.execPath, [BRIDGE, 'stdio', '--endpoint', 'Dev', '--grant', GRANT]);
    const code = await new Promise((resolve) => child.on('exit', resolve));
    assert.equal(code, 3);
    assert.throws(() => parseArgs(['stdio', '--endpoint']), /needs a value/);
    assert.deepEqual(sseData('data: a\ndata: b\n\nevent: x\ndata: c\n\n'), ['a\nb', 'c']);
  });

  it('forwards live tool notifications, refreshes auth, reconnects, and closes the stream with stdin', async () => {
    const notification = { jsonrpc: '2.0', method: 'notifications/tools/list_changed' };
    const streams = [];
    let tokens = 0;
    const streaming = await fakeCore({
      tokens: (r) => issued(streaming.port, r.endpoint, ++tokens),
      mcp: (entry) => {
        if (entry.method === 'GET') {
          if (entry.headers.authorization === 'Bearer gwb_test_token_1') return { status: 401 };
          if (entry.headers.authorization === 'Bearer gwb_test_token_2') return {
            status: 200, type: 'text/event-stream', headers: { 'X-Gateway-Bridge-Instance': 'other' },
            body: 'data: {"jsonrpc":"2.0","method":"notifications/untrusted"}\n\n',
          };
          return { status: 200, type: 'text/event-stream', stream: (res) => {
            streams.push(res);
            res.write(': connected\r\n\r\n');
            if (streams.length === 1) {
              res.write('data: invalid\n\ndata: {"jsonrpc":"2.0","id":99,"result":{}}\n\n');
              res.write('event: message\r\ndata: {"jsonrpc":"2.0",\r\ndata: "method":"notifications/');
              setImmediate(() => res.write('tools/list_changed"}\r\n\r\n'));
            } else {
              res.write(`data: ${JSON.stringify(notification)}\n\n`);
            }
          } };
        }
        if (entry.body.method === 'initialize') return { status: 200, type: 'application/json',
          body: JSON.stringify({ jsonrpc: '2.0', id: entry.body.id,
            result: { protocolVersion: '2025-11-25', capabilities: { tools: { listChanged: true } } } }) };
        if (entry.body.id !== undefined) return { status: 200, type: 'application/json',
          body: JSON.stringify({ jsonrpc: '2.0', id: entry.body.id, result: { tools: [] } }) };
        return { status: 202 };
      },
    });
    const streamingHome = tempHome('bridge-stream');
    const streamingBroker = await startBroker({ home: streamingHome,
      core: createCoreClient({ origin: streaming.origin, launchSecret: SECRET }) });
    const b = startBridge(streamingHome);
    try {
      await b.call({ jsonrpc: '2.0', id: 1, method: 'initialize', params: {} });
      assert.equal(streams.length, 0, 'wait for the client to finish initialization');
      b.send({ jsonrpc: '2.0', method: 'notifications/initialized' });
      assert.deepEqual(await b.next(), notification, 'deliver complete events while GET remains open');
      assert.equal(tokens, 4, 'refresh after a 401 and reject a stream from an unrelated core instance');
      streams[0].end();
      assert.deepEqual(await b.next(), notification, 'forward the server refresh after reconnecting');
      assert.equal(streams.length, 2);
      streams[1].write(`data: ${JSON.stringify(notification)}\n\n`);
      assert.deepEqual(await b.next(), notification);
      assert.equal((await b.call({ jsonrpc: '2.0', id: 2, method: 'tools/list' })).id, 2,
        'forward each server notification once, without a duplicate refresh from the bridge');
      for (const get of streaming.seen.filter((r) => r.method === 'GET')) {
        assert.equal(get.headers.accept, 'text/event-stream');
        assert.equal(get.headers['mcp-protocol-version'], '2025-11-25');
      }
      const disconnected = new Promise((resolve) => streams[1].on('close', resolve));
      assert.equal(await b.stop(), 0);
      await disconnected;
    } finally {
      if (b.child.exitCode === null) await b.stop();
      await streamingBroker.close();
      await streaming.close();
      fs.rmSync(streamingHome, { recursive: true, force: true });
    }
  });
});
