import { ActivityEvent } from '../../core/api/activity.api';
import { ApprovalDetail, ApprovalItem, ApprovalTimelineEntry } from '../../core/api/approvals.api';
import { Notifications } from '../../core/api/notifications.api';
import {
  afterApprovalToast,
  callIndex,
  callOf,
  channelStatuses,
  decidedBy,
  decidedCount,
  decidersLine,
  decisionToast,
  deliveryCard,
  historyInfo,
  historyItems,
  historyRows,
  lastMinuteAnnouncement,
  mostUrgent,
  queueLabel,
  resolvedElsewhere,
  ruleNote,
  stillWaiting,
  stoppedAfterApproval,
  withOwnCalls,
} from './approvals-view';

/** The page around approval-request, against the kit's wording (design/pages/approvals.html) and BLUEPRINT §9. */

const NOW = Date.parse('2026-10-22T14:30:00+02:00');

function item(over: Partial<ApprovalItem>): ApprovalItem {
  return {
    id: 'ap_sql000000001',
    tool: 'sql_execute',
    connection: 'dev-db',
    env: 'dev',
    endpoint: 'dev',
    client: 'codex',
    tokenLabel: 'codex-laptop',
    requestedAt: '2026-10-22T14:29:12+02:00',
    expiresAt: '2026-10-22T14:34:12+02:00',
    status: 'pending',
    summary: null,
    decidedVia: null,
    decidedAt: null,
    commentPolicy: 'optional',
    ...over,
  };
}

const K8S = item({ id: 'ap_k8s000000001', tool: 'k8s_rollout_restart', connection: 'dev-cluster', client: 'claude-desktop', requestedAt: '2026-10-22T14:25:48+02:00', expiresAt: '2026-10-22T14:30:48+02:00' });
const SQL = item({});
const HTTP = item({ id: 'ap_http00000001', tool: 'http_request', connection: 'billing-api', env: 'stage', requestedAt: '2026-10-22T14:29:30+02:00', expiresAt: '2026-10-22T14:39:30+02:00' });

const timeline = (...entries: [string, Record<string, string>][]): ApprovalTimelineEntry[] =>
  entries.map(([kind, detail], i) => ({ id: i + 1, at: '2026-10-22T14:29:13+02:00', kind, detail }));

function notifications(over: Partial<Record<'desktop' | 'slack' | 'telegram', { enabled: boolean; config?: Record<string, string>; state?: string }>>): Notifications {
  return {
    channels: (['desktop', 'slack', 'telegram'] as const)
      .filter((k) => over[k])
      .map((kind) => ({
        kind,
        enabled: over[kind]!.enabled,
        config: over[kind]!.config ?? {},
        state: over[kind]!.state ?? null,
        stateDetail: null,
        lastDeliveryAt: null,
        version: 1,
      })),
    links: [],
  };
}

describe('the queue', () => {
  it('names itself and says what is left after a decision, as the kit does', () => {
    expect(queueLabel(3)).toBe('Waiting for approval, 3 requests, most urgent first');
    expect(queueLabel(1)).toBe('Waiting for approval, 1 request, most urgent first');
    expect(stillWaiting(2)).toBe('2 requests still waiting');
    expect(stillWaiting(1)).toBe('1 request still waiting');
    expect(stillWaiting(0)).toBe('Nothing waiting for approval');
    expect(lastMinuteAnnouncement(K8S)).toBe('k8s_rollout_restart on dev-cluster is denied automatically in less than a minute');
  });

  it('opens the request about to be denied, never one that already ran out here', () => {
    expect(mostUrgent([HTTP, SQL, K8S], new Set(), NOW)?.id).toBe(K8S.id);
    expect(mostUrgent([HTTP, SQL, K8S], new Set([K8S.id]), NOW)?.id).toBe(SQL.id);
    // Past its deadline on the core's clock: not pending in front of the person any more.
    expect(mostUrgent([HTTP, SQL, K8S], new Set(), Date.parse(K8S.expiresAt) + 1)?.id).toBe(SQL.id);
    expect(mostUrgent([item({ status: 'running' })], new Set(), NOW)).toBeNull();
    expect(mostUrgent([], new Set(), NOW)).toBeNull();
  });

  it('counts History as every decision of the last 24 hours but the pending ones (kit: History 58)', () => {
    expect(decidedCount({ pending: 3, completed: 46, rejected: 9, expired: 3 })).toBe(58);
    expect(decidedCount({ pending: 3 })).toBe(0);
    expect(decidedCount(null)).toBe(0);
  });
});

describe('the history', () => {
  const rejected = item({ id: 'ap_rej00000001', tool: 'k8s_apply', connection: 'dev-cluster', client: 'claude-desktop', status: 'rejected', decidedVia: 'app', decidedAt: '2026-10-22T13:58:30+02:00' });
  const approved = item({ id: 'ap_app00000001', status: 'completed', decidedVia: 'app', decidedAt: '2026-10-22T13:52:04+02:00' });
  const expired = item({ id: 'ap_exp00000001', tool: 'http_request', connection: 'billing-api', env: 'stage', status: 'expired', decidedVia: 'timeout', requestedAt: '2026-10-22T13:21:20+02:00', expiresAt: '2026-10-22T13:31:20+02:00' });
  const slack = item({ id: 'ap_slk00000001', tool: 'ssh_run', connection: 'prod-vm', env: 'prod', endpoint: 'incident-prod', status: 'completed', decidedVia: 'slack', decidedAt: '2026-10-22T09:47:05+02:00' });
  const old = item({ id: 'ap_old00000001', status: 'rejected', decidedVia: 'app', decidedAt: '2026-10-21T09:00:00+02:00' });

  it('lists the decided requests of the last 24 hours with the kit’s badges and captions', () => {
    const rows = historyRows(historyItems([rejected, approved, expired, slack, old], NOW), new Map());
    expect(rows.map((r) => [r.tool, r.badge.text, r.badge.icon, r.by])).toEqual([
      ['k8s_apply', 'Rejected', 'user-x', 'By you'],
      ['sql_execute', 'Approved', 'user-check', 'By you'],
      ['http_request', 'Expired', 'timer-off', 'No answer'],
      ['ssh_run', 'Approved', 'user-check', 'By you, from Slack'],
    ]);
    expect(rows[0].srText).toBe(' on dev-cluster from Claude Desktop, rejected by you: open in Activity');
    expect(rows[2].srText).toBe(' on billing-api from Codex, expired after 10 min with no answer: open in Activity');
    expect(rows[3].srText).toBe(' on prod-vm from Codex, approved by you, from Slack: open in Activity');
    expect(rows[2].at).toBe(expired.expiresAt);
  });

  it('opens each call in Activity: its event when Activity has it, else Activity filtered to the request', () => {
    const calls = callIndex([
      { id: 412, approvalId: approved.id, traceId: 'c7e19d0000' } as ActivityEvent,
      { id: 400, approvalId: approved.id, traceId: 'older' } as ActivityEvent,
      { id: 13, approvalId: null, traceId: 'x' } as ActivityEvent,
    ]);
    expect(calls.get(approved.id)).toEqual({ eventId: 412, traceId: 'c7e19d0000' });
    const [toRejected, toApproved] = historyRows([rejected, approved], calls);
    expect(toApproved.link).toEqual({ route: '/activity', fragment: 'event-412' });
    expect(toRejected.link).toEqual({ route: '/activity', queryParams: { q: 'tool:k8s_apply connection:dev-cluster' } });
  });

  it("links the call the request's detail names (step API), its trace only when Traces lists it", () => {
    const listed = { eventId: 400, traceId: 'from-activity' };
    expect(callOf({ eventId: 412, traceId: 'c7e19d0000', traceIsTask: true }, listed)).toEqual({ eventId: 412, traceId: 'c7e19d0000' });
    // A lone call's own trace id is no task: no Trace row, the event still links.
    expect(callOf({ eventId: 412, traceId: 'a1b2c3d4', traceIsTask: false }, undefined)).toEqual({ eventId: 412, traceId: null });
    // A newer row Activity heard of wins the link; a core before step API leaves Activity's index.
    expect(callOf({ eventId: 300, traceId: 'c7e19d0000', traceIsTask: true }, listed)).toEqual({ eventId: 400, traceId: 'c7e19d0000' });
    expect(callOf({ eventId: null, traceId: null, traceIsTask: false }, listed)).toEqual(listed);
    expect(callOf({}, listed)).toEqual(listed);
    expect(callOf(undefined, undefined)).toBeNull();
    const merged = withOwnCalls(new Map([['ap_a', listed]]), new Map([['ap_b', { eventId: 9, traceId: 't', traceIsTask: true }]]));
    expect([...merged]).toEqual([['ap_a', listed], ['ap_b', { eventId: 9, traceId: 't' }]]);
    const [row] = historyRows([approved], withOwnCalls(new Map(), new Map([[approved.id, { eventId: 77, traceId: null, traceIsTask: false }]])));
    expect(row.link).toEqual({ route: '/activity', fragment: 'event-77' });
  });

  it('tells an approval the gateway stopped before it ran from a rejection, by the timeline', () => {
    const stopped = item({ id: 'ap_stp00000001', status: 'rejected', decidedVia: 'app', decidedAt: '2026-10-22T14:20:00+02:00' });
    const detail = { status: 'rejected' as const, timeline: timeline(['requested', {}], ['decided', { decision: 'approved', by: 'you', via: 'app' }], ['failed', { reason: 'Access of codex-laptop to dev was revoked. Not executed.' }]) };
    expect(stoppedAfterApproval(detail)).toBe(true);
    expect(stoppedAfterApproval({ status: 'rejected', timeline: timeline(['decided', { decision: 'rejected', by: 'you' }]) })).toBe(false);
    const [row] = historyRows([stopped], new Map(), new Map([[stopped.id, detail]]));
    expect(row.by).toBe('Approved by you, stopped by GateRook');
    expect(row.srText).toContain('approved by you, then stopped by GateRook before it ran');
  });

  it('names who decided when it was not you in the window', () => {
    expect(decidedBy(item({ status: 'rejected', decidedVia: 'core' }))).toBe('By GateRook');
    expect(decidedBy(item({ status: 'rejected', decidedVia: 'client' }))).toBe('Withdrawn by Codex');
    expect(decidedBy(item({ status: 'rejected', decidedVia: 'desktop-notification' }))).toBe('By you, from the desktop notification');
    expect(decidedBy(item({ status: 'completed', decidedVia: 'telegram' }))).toBe('By you, from Telegram');
  });

  it('says how much of the last 24 hours the table shows (kit: "Newest 4 of 58 in the last 24 hours")', () => {
    expect(historyInfo(4, 58)).toBe('Newest 4 of 58 in the last 24 hours');
    expect(historyInfo(4, 4)).toBe('All 4 in the last 24 hours');
    expect(historyInfo(1, 1)).toBe('The only one in the last 24 hours');
  });
});

describe('decisions', () => {
  const sql: Pick<ApprovalDetail, 'tool' | 'connection' | 'endpoint' | 'client' | 'action'> = {
    tool: 'sql_execute',
    connection: 'dev-db',
    endpoint: 'dev',
    client: 'codex',
    action: { kind: 'sql', language: 'sql', text: 'UPDATE orders …', segments: [], hiddenChars: 0, confusables: 0, warning: null, diff: null },
  };

  it('toasts what happens next in the kit’s words', () => {
    expect(decisionToast(sql, 'deny')).toEqual({
      title: 'sql_execute on dev-db rejected',
      body: 'Codex was told the statement was rejected. Nothing ran on dev-db.',
      tone: 'neutral',
      icon: 'user-x',
    });
    expect(decisionToast(sql, 'approve')).toEqual({
      title: 'sql_execute on dev-db approved',
      body: 'Running on dev-db. The result goes back to Codex and into Activity.',
      tone: 'success',
      icon: 'user-check',
    });
    expect(decisionToast({ ...sql, client: null, action: null }, 'deny').body).toBe('The agent was told the action was rejected. Nothing ran on dev-db.');
  });

  it('says when an approved call didn’t run or failed, and stays quiet when it ran', () => {
    expect(afterApprovalToast({ tool: 'sql_execute', connection: 'dev-db', endpoint: 'dev', status: 'rejected', error: 'Access of codex-laptop to dev was revoked. Not executed.' })).toEqual({
      title: "sql_execute on dev-db didn't run",
      body: 'Access of codex-laptop to dev was revoked. Not executed.',
      tone: 'danger',
      icon: 'ban',
    });
    expect(afterApprovalToast({ tool: 'sql_execute', connection: 'dev-db', endpoint: 'dev', status: 'failed', error: null })?.title).toBe('sql_execute on dev-db failed');
    expect(afterApprovalToast({ tool: 'sql_execute', connection: 'dev-db', endpoint: 'dev', status: 'completed', error: null })).toBeNull();
  });

  it('says how a request decided elsewhere ended', () => {
    expect(resolvedElsewhere(SQL, 'rejected', 'slack')).toBe('sql_execute on dev-db was rejected in Slack');
    expect(resolvedElsewhere(SQL, 'running', 'telegram')).toBe('sql_execute on dev-db was approved in Telegram');
    expect(resolvedElsewhere(SQL, 'expired', 'timeout')).toBe('sql_execute on dev-db expired, denied automatically');
    expect(resolvedElsewhere(SQL, 'rejected', 'core')).toBe('sql_execute on dev-db was rejected by GateRook');
    expect(resolvedElsewhere(SQL, 'rejected', 'desktop-notification')).toBe('sql_execute on dev-db was rejected from the desktop notification');
  });
});

describe('delivery channels', () => {
  const world = notifications({ desktop: { enabled: true }, slack: { enabled: true, config: { workspace: 'acme', channel: 'deploy-approvals' } }, telegram: { enabled: false } });

  it('reads the header line of BLUEPRINT §9: Desktop on · Slack #deploy-approvals · Telegram not set up', () => {
    expect(channelStatuses(world)).toEqual([
      { tone: 'success', label: 'Desktop on', mono: null },
      { tone: 'success', label: 'Slack', mono: '#deploy-approvals' },
      { tone: 'neutral', label: 'Telegram not set up', mono: null },
    ]);
    expect(decidersLine(world)).toBe('You decide, here or from Slack');
    expect(decidersLine(null)).toBe('You decide here');
    expect(decidersLine(notifications({ slack: { enabled: true }, telegram: { enabled: true, config: { chat: '1' } } }))).toBe('You decide, here or from Slack or from Telegram');
    expect(channelStatuses(notifications({ desktop: { enabled: false }, slack: { enabled: true, state: 'error' }, telegram: { enabled: false, config: { chat: '1' } } }))).toEqual([
      { tone: 'neutral', label: 'Desktop off', mono: null },
      { tone: 'danger', label: 'Slack failing', mono: null },
      { tone: 'neutral', label: 'Telegram off', mono: null },
    ]);
  });

  it('describes where one request went, as the kit’s card does', () => {
    const r = {
      channels: { desktop: 'sent', slack: 'sent', telegram: 'off' },
      timeline: timeline(['requested', {}], ['notified', { channel: 'slack', state: 'sent' }], ['notified', { channel: 'desktop', state: 'sent' }]),
    };
    const card = deliveryCard(r, world, () => '14:29:13');
    expect(card.preview).toBe('slack');
    expect(card.heading).toBe('Slack');
    expect(card.letter).toBe('S');
    expect(card.slackChannel).toBe('#deploy-approvals');
    expect(card.settingsLink).toBe(false);
    expect(card.description.map((p) => (p.mono ? `[${p.text}]` : p.text)).join('')).toBe(
      'Posted to [#deploy-approvals] at 14:29:13, where everyone in the channel sees the exact action, and shown as a desktop notification with Review and Deny. Telegram is not set up, so nothing was sent there.',
    );
  });

  it('says plainly when nothing went outside the window, with the way to set channels up', () => {
    const card = deliveryCard({ channels: { desktop: 'off', slack: 'off', telegram: 'off' }, timeline: [] }, null, () => '');
    expect(card.preview).toBe('none');
    expect(card.heading).toBe('Not sent outside the window');
    expect(card.settingsLink).toBe(true);
    expect(card.description.map((p) => p.text).join('')).toBe(
      'No notification channel sent this request, so it waited here, in GateRook. Desktop notifications, Slack and Telegram are set up in',
    );
    const failed = deliveryCard({ channels: { desktop: 'off', slack: 'failed', telegram: 'off' }, timeline: [] }, null, () => '');
    expect(failed.description.map((p) => p.text).join('')).toBe("Slack didn't take it. The request waited here, in GateRook. Check the channels in");
  });

  it('reads K2’s states: a stopped or changed-outside chat neither posts nor decides', () => {
    // Review 2026-10-26: K2's chats are off | connecting | connected | error | stopped | changed-outside; only `error`
    // read as not working, so Socket Mode off said "Slack #deploy-approvals" and "You decide, here or from Slack".
    const stopped = notifications({ slack: { enabled: true, config: { deliverTo: 'channel', channelName: '#deploy-approvals' }, state: 'stopped' } });
    expect(channelStatuses(stopped)[1]).toEqual({ tone: 'danger', label: 'Slack failing', mono: null });
    expect(decidersLine(stopped)).toBe('You decide here');
    const outside = notifications({ slack: { enabled: true, state: 'changed-outside' }, telegram: { enabled: true, state: 'changed-outside' } });
    expect(channelStatuses(outside).slice(1)).toEqual([
      { tone: 'warning', label: 'Slack changed outside GateRook', mono: null },
      { tone: 'warning', label: 'Telegram changed outside GateRook', mono: null },
    ]);
    expect(decidersLine(outside)).toBe('You decide here');
    // Connecting and connected chats work.
    expect(decidersLine(notifications({ slack: { enabled: true, state: 'connecting' }, telegram: { enabled: true, state: 'connected' } }))).toBe(
      'You decide, here or from Slack or from Telegram',
    );
  });

  it('reads K2’s deliverTo: a remembered channel is not where direct messages went', () => {
    // Review 2026-10-26: K2 writes `deliverTo` (`dm` by default) and keeps channelId/channelName when Slack is switched
    // to direct messages; the line read only `mode` and said "Posted to #deploy-approvals, where everyone… sees it".
    const k2 = (deliverTo: string) =>
      notifications({ slack: { enabled: true, config: { deliverTo, channelId: 'C0DEPLOYAPR', channelName: '#deploy-approvals' } } });
    const r = { channels: { desktop: 'off', slack: 'sent', telegram: 'off' }, timeline: [] };
    expect(deliveryCard(r, k2('dm'), () => '').description.map((p) => p.text).join('')).toBe(
      'Sent to you in Slack as a direct message. Telegram is not set up, so nothing was sent there.',
    );
    expect(channelStatuses(k2('dm'))[1]).toEqual({ tone: 'success', label: 'Slack direct messages', mono: null });
    expect(deliveryCard(r, k2('channel'), () => '').slackChannel).toBe('#deploy-approvals');
    expect(channelStatuses(k2('channel'))[1]).toEqual({ tone: 'success', label: 'Slack', mono: '#deploy-approvals' });
  });

  it('shows a direct message when Slack posts to linked approvers, and the desktop alone', () => {
    const dm = deliveryCard({ channels: { desktop: 'off', slack: 'sent', telegram: 'off' }, timeline: [] }, notifications({ slack: { enabled: true, config: { mode: 'dm' } } }), () => '');
    expect(dm.description.map((p) => p.text).join('')).toBe('Sent to you in Slack as a direct message. Telegram is not set up, so nothing was sent there.');
    const desktop = deliveryCard({ channels: { desktop: 'sent', slack: 'off', telegram: 'off' }, timeline: [] }, null, () => '');
    expect(desktop.preview).toBe('desktop');
    expect(desktop.description.map((p) => p.text).join('')).toBe('Shown as a desktop notification with Review and Deny. Nothing was sent to Slack. Nothing was sent to Telegram.');
  });

  it('adds the first impact to the chat’s rule line (kit: "estimated rows 1")', () => {
    expect(ruleNote({ impact: [{ label: 'Rows', text: '1 row (estimate)' }] })).toBe('rows 1 row (estimate)');
    expect(ruleNote({ impact: [] })).toBeNull();
  });
});
