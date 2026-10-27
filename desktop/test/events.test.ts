import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import { after, before, describe, it } from 'node:test';
import { CoreEventFeed, type CoreEvent } from '../src/main/events.ts';
import type { CoreEndpoint } from '../src/main/proxy.ts';
import { waitFor } from './helpers.ts';

const SECRET = Buffer.alloc(32, 3).toString('base64url');
const NONCE = 'feed-instance-0123456789';

let server: Server;
let port = 0;
let instance = NONCE;
const connections: { lastEventId: string | undefined; signed: boolean }[] = [];
const open = new Set<ServerResponse>();

function signed(req: IncomingMessage): boolean {
  const [ts = '', nonce = '', mac = ''] = String(req.headers['x-gateway-auth'] ?? '').split('.');
  const expected = createHmac('sha256', Buffer.from(SECRET, 'base64url')).update(`GET|${req.url}|${ts}|${nonce}`).digest('base64url');
  return mac === expected && Math.abs(Date.now() - Number(ts)) < 30_000;
}

before(async () => {
  server = createServer((req, res) => {
    connections.push({ lastEventId: req.headers['last-event-id'] as string | undefined, signed: signed(req) });
    if (!signed(req)) {
      res.writeHead(401).end();
      return;
    }
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'X-Gateway-Instance': instance });
    res.write(': stream open\n\nretry: 50\n\n');
    open.add(res);
    req.on('close', () => open.delete(res));
  });
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
  port = (server.address() as { port: number }).port;
});
after(() => new Promise<void>((r) => {
  for (const res of open) res.destroy();
  server.close(() => r());
}));

const core = (): CoreEndpoint => ({ port, launchSecret: SECRET, instanceNonce: NONCE });
const emit = (text: string): void => {
  for (const res of open) res.write(text);
};

describe('main\'s event stream', () => {
  it('reads signed events, reconnects with Last-Event-ID, stops when told', async () => {
    const got: CoreEvent[] = [];
    let opened = 0;
    const feed = new CoreEventFeed({ onEvent: (e) => got.push(e), onOpen: () => opened++, retryMs: 50 });
    feed.start(core());
    await waitFor('the stream', () => open.size === 1);
    assert.ok(connections.at(-1)?.signed, 'signed like every admin request');
    emit('id: 41\nevent: notify.desktop\ndata: {"kind":"request","id":"ap_0123456789ab"}\n\n');
    emit('id: 42\nevent: tunnel.state\ndata: {"state":"on"}\n\n');
    await waitFor('two events', () => got.length === 2);
    assert.deepEqual(got[0], { type: 'notify.desktop', data: { kind: 'request', id: 'ap_0123456789ab' }, id: '41' });
    // The core drops the connection: the feed comes back and asks for what came after 42.
    for (const res of open) res.destroy();
    await waitFor('the reconnect', () => open.size === 1 && connections.length >= 2);
    assert.equal(connections.at(-1)?.lastEventId, '42');
    assert.ok(opened >= 2);
    feed.stop();
    await waitFor('the stream to close', () => open.size === 0);
    const count = connections.length;
    await new Promise((r) => setTimeout(r, 200));
    assert.equal(connections.length, count, 'no reconnect after stop');
  });

  it('refuses a stream that isn\'t from main\'s core', async () => {
    instance = 'someone-else';
    const got: CoreEvent[] = [];
    const logs: string[] = [];
    const feed = new CoreEventFeed({ onEvent: (e) => got.push(e), log: (l) => logs.push(l), retryMs: 50 });
    const before = connections.length;
    feed.start(core());
    await waitFor('a refused attempt', () => logs.some((l) => /not our core/.test(l)));
    emit('event: notify.desktop\ndata: {}\n\n');
    await new Promise((r) => setTimeout(r, 100));
    assert.equal(got.length, 0);
    feed.stop();
    instance = NONCE;
    assert.ok(connections.length > before);
  });

  it('a new launch starts without the old Last-Event-ID', async () => {
    const feed = new CoreEventFeed({ onEvent: () => undefined, retryMs: 50 });
    feed.start(core());
    await waitFor('the stream', () => open.size === 1);
    emit('id: 7\nevent: x\ndata: 1\n\n');
    await new Promise((r) => setTimeout(r, 50));
    feed.start(core());
    await waitFor('a fresh stream', () => connections.at(-1)?.lastEventId === undefined && open.size >= 1);
    feed.stop();
  });
});
