import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { isTrustedSender } from '../src/main/ipc-guard.ts';
import { ACTIVATION_DELAY_MS, ActivationGate, activates, answeredByAPerson, isDecisionRoute } from '../src/main/activation-gate.ts';
import { deepLinkInArgv, deepLinkRoute, noticeRoute, uiOpenRoute } from '../src/main/deep-links.ts';
import { allowedExternalUrl } from '../src/main/external.ts';

describe('IPC sender check', () => {
  const ok = { frameUrl: 'app://gw/approvals/ap_0123456789ab', isMainFrame: true, isAppWindow: true };

  it('accepts the app window\'s top frame on app://gw/', () => {
    assert.ok(isTrustedSender(ok));
    assert.ok(isTrustedSender({ ...ok, frameUrl: 'app://gw/' }));
  });

  it('refuses frames, other windows, other origins and look-alikes', () => {
    assert.ok(!isTrustedSender({ ...ok, isMainFrame: false }));
    assert.ok(!isTrustedSender({ ...ok, isAppWindow: false }));
    for (const url of [undefined, '', 'https://gw/', 'app://gw.evil/', 'app://gwx/', 'app://evil@gw/', 'app://gw:1/',
      'file:///app/gw/', 'app://GW-evil/', 'about:blank', 'devtools://devtools/bundled/x', 'app:gw/']) {
      assert.ok(!isTrustedSender({ ...ok, frameUrl: url }), String(url));
    }
  });
});

describe('the 700 ms activation guard', () => {
  it('holds clicks and Enter on a decision screen until 700 ms after the last rearm', () => {
    let now = 1000;
    const gate = new ActivationGate(() => now);
    const click = { kind: 'mouse' as const, type: 'mouseDown' };
    const enter = { kind: 'key' as const, type: 'keyDown', key: 'Enter' };
    assert.ok(!gate.admits(click, true));
    assert.ok(!gate.admits(enter, true));
    now += ACTIVATION_DELAY_MS - 1;
    assert.ok(!gate.admits(click, true));
    now += 1;
    assert.ok(gate.admits(click, true));
    assert.equal(gate.remaining(), 0);
    gate.rearm(); // the window came forward again, a deep link arrived
    assert.ok(!gate.admits(enter, true));
    assert.equal(gate.remaining(), ACTIVATION_DELAY_MS);
  });

  it('never slows down anything else', () => {
    const gate = new ActivationGate(() => 0);
    assert.ok(gate.admits({ kind: 'mouse', type: 'mouseDown' }, false), 'other screens');
    assert.ok(gate.admits({ kind: 'mouse', type: 'mouseMove' }, true), 'moving the mouse');
    assert.ok(gate.admits({ kind: 'mouse', type: 'mouseWheel' }, true), 'scrolling');
    assert.ok(gate.admits({ kind: 'key', type: 'keyDown', key: 'a' }, true), 'typing a comment');
    assert.ok(!gate.admits({ kind: 'key', type: 'keyDown', key: ' ' }, true), 'Space presses a focused button');
  });

  it('knows which events press a button', () => {
    assert.ok(activates({ kind: 'mouse', type: 'mouseUp' }));
    assert.ok(activates({ kind: 'key', type: 'keyUp', key: 'NumpadEnter' }));
    assert.ok(!activates({ kind: 'key', type: 'keyDown', key: 'Tab' }));
  });

  it('knows the decision screens', () => {
    assert.ok(isDecisionRoute('app://gw/approvals'));
    assert.ok(isDecisionRoute('app://gw/approvals/ap_0123456789ab'));
    assert.ok(isDecisionRoute('app://gw/approvals?tab=history'));
    assert.ok(!isDecisionRoute('app://gw/overview'));
    assert.ok(!isDecisionRoute('app://gw/approvalsx'));
    assert.ok(!isDecisionRoute('https://gw/approvals'));
  });

  it('takes an answer to main\'s own dialog only after 700 ms', () => {
    assert.ok(!answeredByAPerson(120));
    assert.ok(answeredByAPerson(700));
  });
});

describe('deep links', () => {
  it('lead only to a request, an event, a settings section', () => {
    assert.equal(deepLinkRoute('gaterook://approvals/ap_0123456789ab'), 'approvals/ap_0123456789ab');
    assert.equal(deepLinkRoute('gaterook:///approvals/ap_0123456789ab/'), 'approvals/ap_0123456789ab');
    assert.equal(deepLinkRoute('GATEROOK://activity/events/42'), 'activity/events/42');
    assert.equal(deepLinkRoute('gaterook://settings/notifications'), 'settings/notifications');
    assert.equal(deepLinkRoute('approvals/ap_0123456789ab'), 'approvals/ap_0123456789ab');
  });

  it('never carry an action, a query or anything else', () => {
    for (const bad of ['gaterook://approvals/ap_0123456789ab/approve', 'gaterook://approvals/ap_0123456789ab?decision=approve',
      'gaterook://approvals/ap_0123456789ab#x', 'gaterook://approvals/AP_0123456789AB', 'gaterook://approvals',
      'gaterook://user:pw@approvals/ap_0123456789ab', 'gaterook:approvals/ap_0123456789ab',
      'gaterook://settings/../internal', 'gaterook://activity/events/1e3', 'javascript:alert(1)',
      `gaterook://settings/${'a'.repeat(300)}`, 42, null]) {
      assert.equal(deepLinkRoute(bad), null, String(bad));
    }
  });

  it('are found among command-line arguments', () => {
    assert.equal(deepLinkInArgv(['/opt/GateRook/gaterook', '--hidden', 'gaterook://activity/events/7']), 'activity/events/7');
    assert.equal(deepLinkInArgv(['app', 'gaterook://evil/x']), null);
    assert.equal(deepLinkInArgv(['app']), null);
  });

  it('a notification may also open the queue', () => {
    assert.equal(noticeRoute('/approvals'), 'approvals');
    assert.equal(noticeRoute('/approvals/ap_0123456789ab'), 'approvals/ap_0123456789ab');
    assert.equal(noticeRoute('/settings/notifications'), 'settings/notifications');
    assert.equal(noticeRoute('https://evil'), null);
  });

  it('"Open in app" from Slack or Telegram opens the path the core names, within the same allowlist', () => {
    // core notifications.internal.{slack.SlackChannel,telegram.TelegramChannel}: ui.open {path}
    assert.equal(uiOpenRoute({ path: '/approvals/ap_0123456789ab' }), 'approvals/ap_0123456789ab');
    assert.equal(uiOpenRoute({ path: '/approvals' }), 'approvals');
    for (const bad of [{ path: '/approvals/ap_0123456789ab/decision' }, { path: 'gaterook://approvals/x' },
      { path: '/settings/../internal' }, { route: '/approvals' }, { path: 7 }, null, 'path']) {
      assert.equal(uiOpenRoute(bad), null, JSON.stringify(bad));
    }
  });
});

describe('links opened outside the app', () => {
  it('only https on documentation hosts or exact project URLs', () => {
    assert.equal(allowedExternalUrl('https://modelcontextprotocol.io/docs'), 'https://modelcontextprotocol.io/docs');
    assert.equal(allowedExternalUrl('https://msrv.dev'), 'https://msrv.dev/');
    for (const url of ['https://msrv.dev/', 'https://www.linkedin.com/in/dobroskok/', 'https://provenbase.com/']) {
      assert.equal(allowedExternalUrl(url), url);
    }
    for (const bad of ['http://modelcontextprotocol.io/', 'file:///etc/passwd', 'https://evil.example/',
      'https://modelcontextprotocol.io.evil.example/', 'https://user@github.com/', 'https://github.com:8443/',
      'https://www.linkedin.com/in/someone-else/', 'https://provenbase.com/other',
      'https://www.linkedin.com/in/dobroskok/?query=value', 'https://provenbase.com/?query=value',
      'https://www.linkedin.com.evil.example/in/dobroskok/', 'https://provenbase.com.evil.example/',
      'http://provenbase.com/', 'https://user@provenbase.com/', 'https://provenbase.com:8443/',
      'https://msrv.dev/other', 'https://msrv.dev/?query=value', 'https://msrv.dev.evil.example/',
      'http://msrv.dev/', 'https://user@msrv.dev/', 'https://msrv.dev:8443/',
      'smb://host/share', 'javascript:alert(1)', 'app://gw/', 42]) {
      assert.equal(allowedExternalUrl(bad), null, String(bad));
    }
  });
});
