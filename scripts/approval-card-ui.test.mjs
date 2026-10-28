import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { JSDOM } from 'jsdom';

const directory = new URL('../core/src/main/resources/ai/msrv/gaterook/core/mcp/apps/', import.meta.url);
const html = await readFile(new URL('approval-card.html', directory), 'utf8');
const source = await readFile(new URL('approval-card.js', directory), 'utf8');
const { mountApprovalCard, readApprovalCard } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
const HOST = 'https://chat.example';
const HANDLE = 'ap_0123456789ab';
const OTHER_HANDLE = 'ap_abcdef012345';
const BASE_TIME = Date.parse('2026-10-28T12:00:00Z');
const settle = async () => { for (let count = 0; count < 8; count++) await Promise.resolve(); };

function result(overrides = {}) {
  return { structuredContent: {
    kind: 'gaterook.approval-card', version: 1, handle: HANDLE, tool: 'mongo_update',
    connection: 'example-database', environment: 'dev', status: 'pending',
    expiresAt: new Date(BASE_TIME + 300_000).toISOString(), serverTime: new Date(BASE_TIME).toISOString(),
    request: { language: 'json', text: '{"update":"examples","filter":{"active":true}}', truncated: false },
    commentRequired: false, canDeny: true, canOpen: true, approvalInApp: true, ...overrides,
  } };
}

function harness(t, origin = HOST) {
  const dom = new JSDOM(html.replace('{{KIT_CSS}}', '').replace('{{APP_JS}}', ''), { pretendToBeVisual: true });
  const win = dom.window;
  const sent = [];
  const parent = { postMessage(message, targetOrigin) { sent.push({ ...message, targetOrigin }); } };
  Object.defineProperty(win, 'parent', { value: parent });
  let now = 0;
  let serial = 0;
  let hidden = false;
  const timers = new Map();
  win.setTimeout = (callback, delay = 0) => { const id = ++serial; timers.set(id, { callback, when: now + delay }); return id; };
  win.setInterval = (callback, delay) => { const id = ++serial; timers.set(id, { callback, when: now + delay, interval: delay }); return id; };
  win.clearTimeout = win.clearInterval = id => timers.delete(id);
  Object.defineProperty(win.performance, 'now', { value: () => now });
  Object.defineProperty(win.document, 'hidden', { get: () => hidden });
  const app = mountApprovalCard(win);
  t.after(() => { app.destroy(); win.close(); });
  const receive = (message, from = parent, messageOrigin = origin) => win.dispatchEvent(new win.MessageEvent('message', { data: message, source: from, origin: messageOrigin }));
  const reply = async (call, response = result(), from = parent, messageOrigin = origin) => {
    receive({ jsonrpc: '2.0', id: call.id, result: response }, from, messageOrigin);
    await settle();
  };
  const notify = (method, params) => receive({ jsonrpc: '2.0', method, params });
  const initialize = async (hostCapabilities = { serverTools: {} }) => {
    await reply(sent[0], { protocolVersion: '2026-01-26', hostCapabilities, hostInfo: { name: 'Test host', version: '1' }, hostContext: { theme: 'dark' } });
  };
  const advance = async milliseconds => {
    const until = now + milliseconds;
    while (true) {
      const next = [...timers.entries()].filter(([, timer]) => timer.when <= until).sort((a, b) => a[1].when - b[1].when)[0];
      if (!next) break;
      const [id, timer] = next;
      now = timer.when;
      if (timer.interval) timer.when += timer.interval;
      else timers.delete(id);
      timer.callback();
      await settle();
    }
    now = until;
    await settle();
  };
  return {
    win, app, sent, timers, parent, receive, reply, notify, initialize, advance,
    el: id => win.document.getElementById(id),
    calls: () => sent.filter(message => message.method === 'tools/call'),
    show: (value = result()) => notify('ui/notifications/tool-result', value),
    visibility(value) { hidden = value; win.document.dispatchEvent(new win.Event('visibilitychange')); },
  };
}

test('the static template has one exact CSP-hashed script and no network assets or inline handlers', () => {
  assert.match(html, /<meta http-equiv="Content-Security-Policy" content="\{\{CSP\}\}">/);
  assert.match(html, /<style>\{\{KIT_CSS\}\}<\/style>/);
  assert.match(html, /<script type="module">\{\{APP_JS\}\}<\/script>/);
  assert.doesNotMatch(html, /\b(?:src|href|style|on\w+)\s*=/i);
  assert.doesNotMatch(source, /\.innerHTML|\beval\(|\bfetch\(/);
  assert.match(html, /Final approval happens in GateRook/);
});

test('versioned projection rejects malformed, oversized and non-native approval data', () => {
  assert.equal(readApprovalCard(result()).handle, HANDLE);
  for (const change of [
    { kind: 'other' }, { version: 2 }, { handle: 9 }, { handle: 'wrong' }, { status: 'approved' },
    { status: '__proto__' }, { environment: 'unknown' }, { expiresAt: 'tomorrow' }, { serverTime: null },
    { approvalInApp: false }, { canDeny: 'true' }, { request: { language: 'html', text: '<b>test</b>' } },
    { request: { language: 'json', text: 'x'.repeat(12_001) } }, { request: { language: 'json', text: '{}', truncated: 'yes' } },
  ]) assert.throws(() => readApprovalCard(result(change)), /unavailable/);
  assert.throws(() => readApprovalCard({ ...result(), isError: true }), /unavailable/);
});

test('handshake pins the parent origin, correlates replies, and ignores premature tool results', async t => {
  const h = harness(t);
  assert.deepEqual(h.sent[0].params, { appInfo: { name: 'GateRook approval', version: '1.0.0' }, appCapabilities: { availableDisplayModes: ['inline'] }, protocolVersion: '2026-01-26' });
  assert.equal(h.sent[0].targetOrigin, '*');
  h.show();
  assert.equal(h.el('request-content').hidden, true);
  await h.reply(h.sent[0], { protocolVersion: '2026-01-26', hostCapabilities: { serverTools: {} } }, {});
  assert.equal(h.sent.length, 1);
  await h.initialize();
  assert.equal(h.sent[1].method, 'ui/notifications/initialized');
  assert.equal(h.sent[1].targetOrigin, HOST);
  assert.equal(h.win.document.documentElement.dataset.theme, 'dark');
  h.receive({ jsonrpc: '2.0', method: 'ui/notifications/tool-result', params: result() }, h.parent, 'https://other.example');
  assert.equal(h.el('request-content').hidden, true);
  h.show();
  assert.equal(h.el('request-content').hidden, false);
  h.el('details-button').click();
  const call = h.calls()[0];
  await h.reply({ id: call.id + 9 }, result({ status: 'completed' }));
  assert.equal(h.el('status-badge').textContent, 'Waiting for approval');
  await h.reply(call);
  assert.equal(h.el('details-button').disabled, false);
});

test('opaque parent origins use wildcard output but still reject other input origins', async t => {
  const h = harness(t, 'null');
  await h.initialize();
  assert.equal(h.sent[1].targetOrigin, '*');
  h.receive({ jsonrpc: '2.0', method: 'ui/notifications/tool-result', params: result() }, h.parent, HOST);
  assert.equal(h.el('request-content').hidden, true);
  h.show();
  assert.equal(h.el('request-handle').textContent, HANDLE);
});

test('data renders as text, flags truncation and comment policy, and never exposes a direct approval tool', async t => {
  const h = harness(t);
  await h.initialize();
  const text = '<img src=x onerror=alert(1)>\n"</script><script>danger()</script>"';
  const staticMediaCount = h.win.document.querySelectorAll('img, svg').length;
  h.show(result({ tool: '<svg onload=alert(1)>', connection: '<b>Database</b>', request: { language: 'sql', text, truncated: true }, commentRequired: true }));
  assert.equal(h.el('request-text').textContent, text);
  assert.equal(h.el('request-text').childElementCount, 0);
  assert.equal(h.el('request-tool').childElementCount, 0);
  assert.equal(h.el('request-connection').childElementCount, 0);
  assert.equal(h.el('request-truncated').hidden, false);
  assert.equal(h.el('comment-required').hidden, false);
  assert.equal(h.win.document.querySelectorAll('img, svg').length, staticMediaCount);
  h.el('approve-button').click();
  assert.deepEqual(h.calls()[0].params, { name: 'approval_card_action', arguments: { handle: HANDLE, action: 'open' } });
  assert.equal(h.el('approve-button').getAttribute('aria-busy'), 'true');
  assert.equal(h.el('status-badge').textContent, 'Waiting for approval');
  await h.reply(h.calls()[0]);
  h.el('details-button').click();
  assert.equal(h.calls()[1].params.arguments.action, 'open');
  await h.reply(h.calls()[1]);
  h.el('deny-button').click();
  assert.equal(h.calls()[2].params.arguments.action, 'deny');
  assert.equal(h.el('status-badge').textContent, 'Waiting for approval');
  await h.reply(h.calls()[2], result({ status: 'rejected', canDeny: false }));
  assert.equal(h.el('status-badge').textContent, 'Denied');
  assert.equal(h.el('approve-button').hidden, true);
  assert.equal(h.el('deny-button').hidden, true);
  assert.equal(h.el('details-button').disabled, false);
});

test('polling is visible-only, single-flight, and ends for terminal states', async t => {
  const h = harness(t);
  await h.initialize();
  h.show();
  await h.advance(5_000);
  assert.equal(h.calls().length, 1);
  assert.equal(h.calls()[0].params.arguments.action, 'status');
  await h.advance(5_000);
  assert.equal(h.calls().length, 1);
  await h.reply(h.calls()[0], result({ serverTime: new Date(BASE_TIME + 10_000).toISOString() }));
  h.visibility(true);
  await h.advance(20_000);
  assert.equal(h.calls().length, 1);
  h.visibility(false);
  assert.equal(h.calls().length, 2);
  await h.reply(h.calls()[1], result({ status: 'completed', canDeny: false, serverTime: new Date(BASE_TIME + 30_000).toISOString() }));
  await h.advance(60_000);
  assert.equal(h.calls().length, 2);
  assert.equal(h.timers.size, 0);
});

test('local expiry disables decisions and checks server without inventing an expired state', async t => {
  const h = harness(t);
  await h.initialize();
  h.show(result({ expiresAt: new Date(BASE_TIME + 2_000).toISOString() }));
  await h.advance(2_000);
  assert.equal(h.el('deny-button').disabled, true);
  assert.equal(h.el('approve-button').disabled, true);
  assert.equal(h.el('status-badge').textContent, 'Waiting for approval');
  assert.match(h.el('request-expiry').textContent, /Checking the deadline/);
  assert.equal(h.calls().length, 1);
  assert.equal(h.calls()[0].params.arguments.action, 'status');
  await h.reply(h.calls()[0], result({ status: 'expired', canDeny: false, serverTime: new Date(BASE_TIME + 2_000).toISOString() }));
  assert.equal(h.el('status-badge').textContent, 'Expired');
});

test('request replacement invalidates both stale success and stale error responses', async t => {
  for (const isError of [false, true]) {
    const h = harness(t);
    await h.initialize();
    h.show();
    h.el('deny-button').click();
    const old = h.calls()[0];
    h.notify('ui/notifications/tool-input', { arguments: { handle: OTHER_HANDLE } });
    h.show(result({ handle: OTHER_HANDLE }));
    if (isError) {
      h.receive({ jsonrpc: '2.0', id: old.id, error: { code: -32000, message: 'Old request failed' } });
      await settle();
    } else await h.reply(old, result({ status: 'rejected' }));
    assert.equal(h.el('request-handle').textContent, OTHER_HANDLE);
    assert.equal(h.el('status-badge').textContent, 'Waiting for approval');
    assert.equal(h.el('card-error').hidden, true);
    assert.equal(h.el('deny-button').disabled, false);
  }
});

test('stale snapshots cannot regress a terminal state or replace the requested handle', async t => {
  const h = harness(t);
  await h.initialize();
  h.show(result({ status: 'completed' }));
  h.show(result({ serverTime: new Date(BASE_TIME + 1_000).toISOString() }));
  assert.equal(h.el('status-badge').textContent, 'Completed');
  h.notify('ui/notifications/tool-input', { arguments: { handle: OTHER_HANDLE } });
  h.show();
  assert.equal(h.el('request-content').hidden, true);
  assert.match(h.el('error-message').textContent, /different request/);
  assert.equal(h.el('approve-button').disabled, true);
});

test('errors and bounded timeouts preserve authoritative status and allow retry', async t => {
  const h = harness(t);
  await h.initialize();
  h.show();
  h.el('deny-button').click();
  await h.reply(h.calls()[0], { isError: true, content: [{ type: 'text', text: '<script>unsafe</script>' }] });
  assert.match(h.el('error-message').textContent, /could not complete/);
  assert.equal(h.el('status-badge').textContent, 'Waiting for approval');
  assert.equal(h.el('deny-button').disabled, false);
  h.el('details-button').click();
  await h.advance(10_000);
  assert.match(h.el('error-message').textContent, /did not respond/);
  assert.equal(h.el('details-button').disabled, false);
  await h.reply(h.calls()[1], result({ status: 'completed' }));
  assert.equal(h.el('status-badge').textContent, 'Waiting for approval');
});

test('missing host capability and malformed cards disable actions', async t => {
  const h = harness(t);
  await h.initialize({});
  h.show();
  assert.equal(h.el('approve-button').disabled, true);
  assert.equal(h.el('deny-button').disabled, true);
  await h.advance(60_000);
  assert.equal(h.calls().length, 0);
  h.show(result({ approvalInApp: false }));
  assert.equal(h.el('request-content').hidden, true);
  assert.match(h.el('error-message').textContent, /unavailable/);
});

test('teardown acknowledges the host and removes listeners, timers and outstanding work', async t => {
  const h = harness(t);
  await h.initialize();
  h.show();
  h.el('details-button').click();
  h.receive({ jsonrpc: '2.0', id: 'teardown', method: 'ui/resource-teardown', params: {} });
  await settle();
  assert.deepEqual(h.sent.at(-1), { jsonrpc: '2.0', id: 'teardown', result: {}, targetOrigin: HOST });
  assert.equal(h.timers.size, 0);
  assert.equal(h.el('details-button').disabled, true);
  const count = h.sent.length;
  await h.advance(60_000);
  h.show(result({ status: 'completed' }));
  assert.equal(h.el('status-badge').textContent, 'Waiting for approval');
  assert.equal(h.sent.length, count);
});

test('host context changes apply only the theme, and standard requests get correlated replies', async t => {
  const h = harness(t);
  await h.initialize();
  h.notify('ui/notifications/host-context-changed', { theme: 'light', styles: { css: { fonts: '@import "https://untrusted.example/font";' } } });
  assert.equal(h.win.document.documentElement.dataset.theme, 'light');
  assert.equal(h.win.document.querySelector('style').textContent, '');
  h.notify('ui/notifications/host-context-changed', { theme: 'url(javascript:bad)' });
  assert.equal(h.win.document.documentElement.dataset.theme, 'light');
  h.receive({ jsonrpc: '2.0', id: 'health', method: 'ping' });
  assert.deepEqual(h.sent.at(-1), { jsonrpc: '2.0', id: 'health', result: {}, targetOrigin: HOST });
  h.receive({ jsonrpc: '2.0', id: 'unsupported', method: 'tools/call', params: { name: 'approve' } });
  assert.deepEqual(h.sent.at(-1), { jsonrpc: '2.0', id: 'unsupported', error: { code: -32601, message: 'Method not found' }, targetOrigin: HOST });
});

test('tool cancellation stops polling and invalidates in-flight mutations without claiming a decision', async t => {
  const h = harness(t);
  await h.initialize();
  h.show();
  h.el('deny-button').click();
  const call = h.calls()[0];
  h.notify('ui/notifications/tool-cancelled', { reason: '<script>unsafe</script>' });
  assert.equal(h.el('status-badge').textContent, 'Unavailable');
  assert.equal(h.el('request-content').hidden, true);
  assert.match(h.el('status-description').textContent, /check its status/);
  await h.reply(call, result({ status: 'rejected' }));
  assert.equal(h.el('status-badge').textContent, 'Unavailable');
  assert.equal(h.el('deny-button').disabled, true);
  assert.equal(h.timers.size, 0);
});

test('an unsupported or silent host times out without accepting premature events', async t => {
  const h = harness(t);
  await h.reply(h.sent[0], { protocolVersion: '1900-01-01', hostCapabilities: { serverTools: {} } });
  h.show();
  await h.advance(10_000);
  assert.equal(h.el('status-badge').textContent, 'Unavailable');
  assert.match(h.el('error-message').textContent, /did not respond/);
  assert.equal(h.timers.size, 0);
});
