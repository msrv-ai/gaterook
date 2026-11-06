import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { JSDOM } from 'jsdom';

const directory = new URL('../core/src/main/resources/ai/msrv/gaterook/core/mcp/apps/', import.meta.url);
const html = await readFile(new URL('connection-import-card.html', directory), 'utf8');
const source = await readFile(new URL('connection-import-card.js', directory), 'utf8');
const { mountConnectionImportCard, readConnectionImportCard } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
const ID = 'ci_0123456789ab';
const OTHER = 'ci_abcdef012345';
const HOST = 'https://chat.example';
const settle = async () => { for (let index = 0; index < 8; index++) await Promise.resolve(); };
const result = (overrides = {}) => ({ structuredContent: {
  kind: 'gaterook.connection-import-card', version: 1, sessionId: ID, status: 'review', count: 3,
  reviewUrl: `gaterook://connection-import/${ID}`, canOpen: true, ...overrides,
} });

function harness(t, origin = HOST) {
  const dom = new JSDOM(html.replace('{{KIT_CSS}}', '').replace('{{APP_JS}}', ''), { pretendToBeVisual: true });
  const win = dom.window;
  const sent = [];
  const parent = { postMessage(message, targetOrigin) { sent.push({ ...message, targetOrigin }); } };
  Object.defineProperty(win, 'parent', { value: parent });
  const app = mountConnectionImportCard(win);
  t.after(() => { app.destroy(); win.close(); });
  const receive = (message, from = parent, fromOrigin = origin) => win.dispatchEvent(new win.MessageEvent('message', { source: from, origin: fromOrigin, data: message }));
  const reply = async (call, value, from = parent, fromOrigin = origin) => {
    receive({ jsonrpc: '2.0', id: call.id, result: value }, from, fromOrigin);
    await settle();
  };
  const notify = (method, params) => receive({ jsonrpc: '2.0', method, params });
  return {
    win, app, sent, parent, receive, reply, notify,
    el: id => win.document.getElementById(id),
    calls: () => sent.filter(message => message.method === 'tools/call'),
    show: (data = result()) => notify('ui/notifications/tool-result', data),
    initialize: (capabilities = { serverTools: {} }) => reply(sent[0], {
      protocolVersion: '2026-01-26', hostCapabilities: capabilities, hostContext: { theme: 'dark' },
    }),
  };
}

test('the template is navigation-only and has no external assets, secret rendering or inline handlers', () => {
  assert.equal((html.match(/<button\b/g) ?? []).length, 1);
  assert.match(html, /Open import in GateRook/);
  assert.doesNotMatch(html, /\b(?:src|href|style|on\w+)\s*=/i);
  assert.doesNotMatch(source, /\.innerHTML|\beval\(|\bfetch\(|submissionToken|importFile|\/api\//);
  assert.deepEqual([...source.matchAll(/name: '([^']+)'/g)].map(match => match[1]), ['open_connection_import', 'GateRook connection import']);
});

test('projection validates handle, count, state and exact navigation URL, dropping unrelated data', () => {
  assert.deepEqual(readConnectionImportCard(result({ privateField: 'not-rendered' })), { sessionId: ID, status: 'review', count: 3, canOpen: true });
  for (const change of [
    { kind: 'other' }, { version: 2 }, { sessionId: '../settings' }, { count: -1 }, { count: 51 },
    { count: '3' }, { status: 'awaiting' }, { status: 'imported' }, { canOpen: false },
    { reviewUrl: 'gaterook://settings' }, { reviewUrl: `https://example.com/${ID}` },
  ]) assert.throws(() => readConnectionImportCard(result(change)), /unavailable/);
  assert.throws(() => readConnectionImportCard({ ...result(), isError: true }), /unavailable/);
});

test('handshake pins parent source and origin before accepting a card', async t => {
  const h = harness(t);
  h.show();
  assert.equal(h.el('open-import').disabled, true);
  await h.reply(h.sent[0], { protocolVersion: '2026-01-26', hostCapabilities: {} }, {});
  assert.equal(h.sent.length, 1);
  await h.initialize();
  assert.equal(h.sent[1].targetOrigin, HOST);
  assert.equal(h.win.document.documentElement.dataset.theme, 'dark');
  h.receive({ jsonrpc: '2.0', method: 'ui/notifications/tool-result', params: result() }, h.parent, 'https://other.example');
  assert.equal(h.el('open-import').disabled, true);
  h.show();
  assert.equal(h.el('open-import').disabled, false);
  assert.match(h.el('card-status').textContent, /3 connection candidates/);
});

test('the only action sends the submitted session handle, without credentials or a commit capability', async t => {
  const h = harness(t);
  await h.initialize();
  h.notify('ui/notifications/tool-input', { arguments: { sessionId: ID, submissionToken: 'private-capability' } });
  h.show(result({ manifest: { secret: 'private-value' } }));
  h.el('open-import').click();
  h.el('open-import').click();
  assert.equal(h.calls().length, 1);
  assert.deepEqual(h.calls()[0].params, { name: 'open_connection_import', arguments: { sessionId: ID } });
  assert.equal(h.el('open-import').getAttribute('aria-busy'), 'true');
  assert.doesNotMatch(h.win.document.body.textContent, /private/);
  await h.reply(h.calls()[0], result());
  assert.match(h.el('card-status').textContent, /Opened this import/);
  assert.equal(h.el('open-import').disabled, false);
});

test('a new invocation rejects an old open response and a mismatched tool result', async t => {
  const h = harness(t);
  await h.initialize();
  h.show();
  h.el('open-import').click();
  const old = h.calls()[0];
  h.notify('ui/notifications/tool-input', { arguments: { sessionId: OTHER } });
  await h.reply(old, result());
  assert.equal(h.el('open-import').disabled, true);
  h.show();
  assert.match(h.el('error-message').textContent, /different import/);
  assert.equal(h.el('open-import').disabled, true);
  h.show(result({ sessionId: OTHER, reviewUrl: `gaterook://connection-import/${OTHER}` }));
  assert.equal(h.el('open-import').disabled, false);
});

test('unsupported hosts and imported sessions offer no mutation or navigation controls', async t => {
  const h = harness(t);
  await h.initialize({});
  h.show();
  assert.equal(h.el('open-import').disabled, true);
  assert.match(h.el('error-message').textContent, /link in the tool result/);
  h.el('open-import').click();
  assert.equal(h.calls().length, 0);
  h.show(result({ status: 'imported', canOpen: false }));
  assert.match(h.el('card-status').textContent, /have been imported/);
  assert.equal(h.el('open-import').disabled, true);
});

test('server refusal and cancellation do not leak server errors or leave a live action', async t => {
  const h = harness(t, 'null');
  await h.initialize();
  assert.equal(h.sent[1].targetOrigin, '*');
  h.show();
  h.el('open-import').click();
  await h.reply(h.calls()[0], { isError: true, content: [{ text: 'private server detail' }] });
  assert.match(h.el('error-message').textContent, /unavailable/);
  assert.doesNotMatch(h.win.document.body.textContent, /private server detail/);
  h.notify('ui/notifications/tool-cancelled');
  assert.equal(h.el('open-import').disabled, true);
  h.receive({ jsonrpc: '2.0', id: 900, method: 'ui/resource-teardown' });
  assert.deepEqual(h.sent.at(-1).result, {});
  h.show();
  assert.equal(h.el('open-import').disabled, true);
});
