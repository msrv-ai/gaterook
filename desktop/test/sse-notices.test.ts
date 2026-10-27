import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { SseParser, type SseMessage } from '../src/main/sse.ts';
import { actionAt, actionIndexOf, consentNotice, denyRequest, notificationSpec, parseNotice, placement } from '../src/main/notices.ts';

describe('server-sent events', () => {
  it('parses events, ids, retries and comments across chunk boundaries', () => {
    const got: SseMessage[] = [];
    const retries: number[] = [];
    const p = new SseParser((m) => got.push(m), (ms) => retries.push(ms));
    p.push(': stream open\n\nretry: 2000\n\nid: 7\nevent: approval.requested\nda');
    p.push('ta: {"id":"ap_1"}\n\n');
    p.push('id: 8\r\nevent: x\r\ndata: a\r\ndata: b\r');
    p.push('\n\r\n');
    p.push('data: plain\n\n');
    assert.deepEqual(retries, [2000]);
    assert.deepEqual(got, [
      { event: 'approval.requested', data: '{"id":"ap_1"}', id: '7' },
      { event: 'x', data: 'a\nb', id: '8' },
      { event: 'message', data: 'plain', id: '8' },
    ]);
    assert.equal(p.lastEventId, '8');
  });
});

const request = {
  kind: 'request', id: 'ap_0123456789ab', group: 'dev', env: 'dev', prod: false,
  title: 'Approval needed · sql_execute on dev-db', subtitle: 'dev environment · from codex-laptop',
  body: 'UPDATE users SET plan = \'pro\' WHERE id = 42', footer: 'dev environment · codex-laptop · auto-deny at 14:05',
  urgency: 'normal', silent: true, route: '/approvals/ap_0123456789ab', expiresAt: '2026-10-26T14:05:00Z',
  actions: [{ id: 'review', text: 'Review' }, { id: 'deny', text: 'Deny' }], pendingCount: 3,
};

describe('desktop notifications of requests', () => {
  it('offer Review and Deny, never Approve', () => {
    const n = parseNotice({ ...request, actions: [{ id: 'approve', text: 'Approve' }, ...request.actions] });
    assert.ok(n);
    assert.deepEqual(n.actions.map((a) => a.id), ['review', 'deny']);
    assert.equal(actionAt(n, 0), 'review');
    assert.equal(actionAt(n, 1), 'deny');
    assert.equal(actionAt(n, null), 'review', 'a click on the body reviews');
    assert.equal(actionAt(n, 7), 'review');
  });

  it('read the pressed button where Electron 45 puts it, the deprecated argument only as a fallback', () => {
    assert.equal(actionIndexOf({ actionIndex: 1, selectionIndex: -1 }, undefined), 1, 'details.actionIndex');
    assert.equal(actionIndexOf({ actionIndex: 1 }, 0), 1, 'the details win');
    assert.equal(actionIndexOf({}, 1), 1, 'older: the positional index');
    assert.equal(actionIndexOf(null, undefined), null);
    assert.equal(actionIndexOf({ actionIndex: -1 }, '1'), null);
    const n = parseNotice(request)!;
    assert.equal(actionAt(n, actionIndexOf({ actionIndex: 1 }, undefined)), 'deny');
  });

  it('deny exactly the request they show, through the decision route', () => {
    const n = parseNotice(request);
    assert.ok(n);
    assert.deepEqual(denyRequest(n), { path: '/api/approvals/ap_0123456789ab/decision',
      body: { decision: 'deny', via: 'desktop-notification' } });
  });

  it('a summary or a test can\'t deny anything', () => {
    const summary = parseNotice({ kind: 'summary', id: 'approvals-summary', title: '5 approval requests are waiting',
      route: '/approvals', actions: [{ id: 'review', text: 'Review' }, { id: 'deny', text: 'Deny' }], urgency: 'critical' });
    assert.ok(summary);
    assert.deepEqual(summary.actions.map((a) => a.id), ['review']);
    assert.equal(denyRequest(summary), null);
    const test = parseNotice({ ...request, kind: 'test', id: 'notify-test' });
    assert.ok(test);
    assert.equal(denyRequest(test), null);
  });

  it('refuse what isn\'t a notice', () => {
    for (const bad of [null, 'x', {}, { ...request, kind: 'approve' }, { ...request, id: '../x' }, { ...request, title: '' }]) {
      assert.equal(parseNotice(bad), null);
    }
  });

  it('go to the OS only when the window isn\'t in front', () => {
    assert.equal(placement(true, true), 'window');
    assert.equal(placement(false, true), 'os');
    assert.equal(placement(false, false), 'unsupported');
  });

  it('use the subtitle on macOS and fold it into the body elsewhere', () => {
    const n = parseNotice(request)!;
    const mac = notificationSpec(n, 'darwin');
    assert.equal(mac.subtitle, request.subtitle);
    assert.equal(mac.body, request.body);
    assert.deepEqual(mac.actions, [{ type: 'button', text: 'Review' }, { type: 'button', text: 'Deny' }]);
    assert.equal(mac.timeoutType, 'never');
    const win = notificationSpec(n, 'win32');
    assert.equal(win.subtitle, undefined);
    assert.equal(win.body, `${request.subtitle}\n${request.body}\n${request.footer}`);
    const prod = notificationSpec(parseNotice({ ...request, prod: true, urgency: 'critical', silent: false })!, 'linux');
    assert.equal(prod.urgency, 'critical');
    assert.equal(prod.silent, false);
  });

  it('a sign-in on this computer names its code', () => {
    assert.deepEqual(consentNotice({ id: 'oc_1', code: '4F7-2KQ', clientHost: 'claude.ai', endpoint: 'stage-readonly' }), {
      id: 'oc_1', title: 'claude.ai wants to sign in · code 4F7-2KQ',
      body: 'Check the code, then allow or deny it in MCP Gateway (endpoint stage-readonly).' });
    assert.equal(consentNotice({ id: 'oc_1', code: 'nope' }), null);
  });
});
