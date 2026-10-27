import assert from 'node:assert/strict';
import { createHmac, timingSafeEqual } from 'node:crypto';
import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import { after, before, describe, it } from 'node:test';
import { signRequest } from '@gaterook/broker';
import { mayUnlock, proxyToCore, type CoreEndpoint } from '../src/main/proxy.ts';
import { callCore, callCoreOk } from '../src/main/core-client.ts';

const SECRET = Buffer.from('0123456789abcdef0123456789abcdef').toString('base64url');
const NONCE = 'instance-nonce-0123456789';

interface Seen {
  method: string;
  url: string;
  headers: IncomingMessage['headers'];
  body: string;
  valid: boolean;
}

/** The core's guard, as platform.web.LocalGuardFilter checks a signature (HMAC of METHOD|target|ts|nonce, ±30 s, once). */
function verify(req: IncomingMessage, used: Set<string>): boolean {
  const parts = String(req.headers['x-gateway-auth'] ?? '').split('.');
  if (parts.length !== 3) return false;
  const [ts = '', nonce = '', mac = ''] = parts;
  if (Math.abs(Date.now() - Number(ts)) > 30_000 || used.has(nonce)) return false;
  const expected = createHmac('sha256', Buffer.from(SECRET, 'base64url')).update(`${req.method}|${req.url}|${ts}|${nonce}`).digest();
  const given = Buffer.from(mac, 'base64url');
  if (given.length !== expected.length || !timingSafeEqual(given, expected)) return false;
  used.add(nonce);
  return true;
}

let server: Server;
let port = 0;
const seen: Seen[] = [];
let instance: string | null = NONCE;
const used = new Set<string>();

before(async () => {
  server = createServer((req: IncomingMessage, res: ServerResponse) => {
    const chunks: Buffer[] = [];
    req.on('data', (c: Buffer) => chunks.push(c));
    req.on('end', () => {
      const valid = verify(req, used);
      seen.push({ method: req.method ?? '', url: req.url ?? '', headers: req.headers, body: Buffer.concat(chunks).toString(), valid });
      if (!valid) {
        res.writeHead(401).end();
        return;
      }
      if (instance) res.setHeader('X-Gateway-Instance', instance);
      if (req.url === '/api/events') {
        res.writeHead(200, { 'Content-Type': 'text/event-stream' });
        res.write(': stream open\n\n');
        let n = 0;
        const t = setInterval(() => {
          res.write(`event: tick\ndata: ${n++}\n\n`);
          if (n === 3) {
            clearInterval(t);
            res.end();
          }
        }, 60);
        return;
      }
      if (req.url === '/api/empty') {
        res.writeHead(204).end();
        return;
      }
      res.writeHead(200, { 'Content-Type': 'application/json', 'Set-Cookie': 'a=b', Connection: 'keep-alive' });
      res.end(JSON.stringify({ ok: true, url: req.url }));
    });
  });
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
  port = (server.address() as { port: number }).port;
});
after(() => new Promise<void>((r) => server.close(() => r())));

const core = (): CoreEndpoint => ({ port, launchSecret: SECRET, instanceNonce: NONCE });

describe('signing', () => {
  it('matches the core\'s LaunchSecret.sign for the shared vector', () => {
    // The same vector the bridge's tests and the core's ClientsUnitTest check.
    assert.equal(signRequest(SECRET, 'POST', '/internal/bridge/tokens', 1792900000000, 'AAAAAAAAAAAAAAAAAAAAAA'),
      '1792900000000.AAAAAAAAAAAAAAAAAAAAAA.pW2mL07tz9Ju6YR3gKetHyKTmOuLxaf-sj4y2G1o828');
  });
});

describe('the /api proxy', () => {
  it('signs the raw target, drops browser headers and passes the core\'s answer on', async () => {
    instance = NONCE;
    const req = new Request('app://gw/api/activity?limit=5&q=a%20b', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Origin: 'app://gw', Cookie: 'x=y', Referer: 'app://gw/',
        'Sec-Fetch-Site': 'same-origin', 'Sec-Fetch-Mode': 'cors', Authorization: 'Bearer nope', 'X-Gateway-Auth': 'forged',
        Accept: 'application/json', 'Last-Event-ID': '41' },
      body: JSON.stringify({ hello: 'world' }),
    });
    const res = await proxyToCore(req, '/api/activity?limit=5&q=a%20b', core());
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { ok: true, url: '/api/activity?limit=5&q=a%20b' });
    assert.equal(res.headers.get('x-gateway-instance'), NONCE, 'the UI needs the instance id (U0)');
    assert.equal(res.headers.get('set-cookie'), null);
    const s = seen.at(-1);
    assert.ok(s?.valid, 'signature verified like the core does');
    assert.equal(s.headers.host, `127.0.0.1:${port}`);
    assert.equal(s.headers['content-type'], 'application/json');
    assert.equal(s.headers['last-event-id'], '41');
    assert.equal(s.headers['content-length'], String(Buffer.byteLength('{"hello":"world"}')));
    for (const h of ['origin', 'cookie', 'referer', 'sec-fetch-site', 'sec-fetch-mode', 'authorization']) {
      assert.equal(s.headers[h], undefined, h);
    }
    assert.equal(s.body, '{"hello":"world"}');
  });

  it('streams server-sent events as they come', async () => {
    instance = NONCE;
    const res = await proxyToCore(new Request('app://gw/api/events'), '/api/events', core());
    assert.equal(res.status, 200);
    const reader = res.body!.pipeThrough(new TextDecoderStream()).getReader();
    const got: string[] = [];
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      got.push(value);
    }
    assert.ok(got.length >= 3, `chunks arrived one by one (${got.length})`);
    assert.match(got.join(''), /event: tick\ndata: 2/);
  });

  it('gives a network error, not the answer, when another program answers on the port', async () => {
    instance = 'someone-else';
    const res = await proxyToCore(new Request('app://gw/api/health'), '/api/health', core());
    assert.equal(res.type, 'error');
    instance = null;
    const res2 = await proxyToCore(new Request('app://gw/api/health'), '/api/health', core());
    assert.equal(res2.type, 'error');
    instance = NONCE;
  });

  it('passes the guard\'s own refusals on without a body', async () => {
    const wrong = { ...core(), launchSecret: Buffer.alloc(32, 1).toString('base64url') };
    const res = await proxyToCore(new Request('app://gw/api/health'), '/api/health', wrong);
    assert.equal(res.status, 401);
    assert.equal(await res.text(), '');
  });

  it('gives a network error while no core is ready, or when it is gone', async () => {
    assert.equal((await proxyToCore(new Request('app://gw/api/health'), '/api/health', null)).type, 'error');
    const gone = { ...core(), port: 1 };
    assert.equal((await proxyToCore(new Request('app://gw/api/health'), '/api/health', gone)).type, 'error');
  });

  it('marks every answer as data, never a page, and refuses an oversized body before reading it', async () => {
    const res = await proxyToCore(new Request('app://gw/api/health'), '/api/health', core());
    assert.equal(res.headers.get('content-security-policy'), "default-src 'none'; frame-ancestors 'none'; sandbox");
    assert.equal(res.headers.get('x-content-type-options'), 'nosniff');
    const count = seen.length;
    const big = await proxyToCore(new Request('app://gw/api/plugins/check', { method: 'POST', headers: { 'content-length': String(300 * 1024 * 1024) },
      body: 'x' }), '/api/plugins/check', core());
    assert.equal(big.status, 413);
    assert.equal(seen.length, count, 'nothing was sent to the core');
  });

  it('keeps an empty answer empty', async () => {
    const res = await proxyToCore(new Request('app://gw/api/empty'), '/api/empty', core());
    assert.equal(res.status, 204);
    assert.equal(res.body, null);
  });

  it('refuses a streamed body as it passes the limit, without a length up front and without sending it', async () => {
    const count = seen.length;
    let pulled = 0;
    const stream = new ReadableStream<Uint8Array>({
      pull(c) {
        pulled++;
        c.enqueue(new Uint8Array(1024));
        if (pulled > 10_000) c.close();
      },
    });
    const req = new Request('app://gw/api/plugins/check', { method: 'POST', body: stream, duplex: 'half' } as RequestInit);
    assert.equal(req.headers.get('content-length'), null);
    const res = await proxyToCore(req, '/api/plugins/check', core(), { maxBodyBytes: 4096 });
    assert.equal(res.status, 413);
    assert.ok(pulled <= 6, `stopped reading at the limit (${pulled} chunks)`);
    assert.equal(seen.length, count, 'nothing was sent to the core');
    const fits = await proxyToCore(new Request('app://gw/api/x', { method: 'POST', headers: { 'content-type': 'application/json' },
      body: '{"a":1}' }), '/api/x', core(), { maxBodyBytes: 4096 });
    assert.equal(fits.status, 200);
    assert.equal(seen.at(-1)?.body, '{"a":1}');
  });
});

describe('the answers that may unlock the core', () => {
  it('are a Restore or Create of the master key that went through', () => {
    assert.ok(mayUnlock('POST', '/api/master-key/restore', 200));
    assert.ok(mayUnlock('post', '/api/master-key/create', 201));
    assert.ok(!mayUnlock('POST', '/api/master-key/restore', 409), 'refused: still locked');
    assert.ok(!mayUnlock('POST', '/api/master-key/restore', 0), 'no answer');
    assert.ok(!mayUnlock('GET', '/api/master-key', 200));
    assert.ok(!mayUnlock('POST', '/api/master-key/rotate', 202));
    assert.ok(mayUnlock('POST', '/api/master-key/restore?x=1', 200));
  });
});

describe('main\'s own calls', () => {
  it('signs, sends JSON and checks the instance', async () => {
    instance = NONCE;
    const answer = await callCore<{ url: string }>(core(), 'POST', '/internal/files/grant', { path: '/x', purpose: 'audit-export' });
    assert.equal(answer.status, 200);
    assert.equal(answer.body.url, '/internal/files/grant');
    const s = seen.at(-1);
    assert.ok(s?.valid);
    assert.equal(s.body, '{"path":"/x","purpose":"audit-export"}');
    instance = 'someone-else';
    await assert.rejects(callCoreOk(core(), 'GET', '/api/health'), /Another program/);
    instance = NONCE;
  });
});
