import { ApprovalChannels, ApprovalDetail, ApprovalItem, ApprovalStatus } from '../../core/api/approvals.api';
import { ActivityEvent } from '../../core/api/activity.api';
import { NotificationChannel, Notifications } from '../../core/api/notifications.api';
import { JsonValue } from '../../core/api/types';
import { clientName } from '../../kit/elements/shared/client-mark';
import { Look } from '../../kit/elements/shared/dictionaries';
import { requestState, statusBadge, who, windowMinutes } from '../../kit/elements/approval-request/approval-view';

/**
 * What the Approvals page shows (design/pages/approvals.html), computed from the admin API (ARCHITECTURE.md §6
 * approvals) as pure functions: the page binds them, the tests check them against the kit's wording. The request
 * itself is the kit element `approval-request`; this file is the page around it — the queue, the history table, the
 * delivery line and card, the toasts and announcements after a decision.
 */

/** Activity's query for every call that needed a person (the header's "View in Activity"). */
export const ACTIVITY_APPROVAL_CALLS = 'decision:pending decision:approved decision:rejected decision:expired';
/** Activity's query for decided requests (the history's "View all in Activity"). */
export const ACTIVITY_DECIDED = 'decision:approved decision:rejected decision:expired';
/**
 * The calls whose Activity events the page looks up to link requests to their call and trace: those of the kit's
 * query plus `error`, the decision a call gets when it failed after approval.
 */
export const ACTIVITY_INDEX_QUERY = `${ACTIVITY_APPROVAL_CALLS} decision:error`;

/** How many decided requests the History tab lists before "View all in Activity" (the table is a summary). */
export const HISTORY_ROWS = 10;
/** History counts and lists the last 24 hours, as the tab says ("58 decided in the last 24 hours"). */
export const HISTORY_WINDOW_MS = 24 * 60 * 60 * 1000;

/** "3 requests" / "1 request". */
export function requests(n: number): string {
  return `${n} ${n === 1 ? 'request' : 'requests'}`;
}

/** The queue's accessible name (kit: "Waiting for approval, 3 requests, most urgent first"). */
export function queueLabel(n: number): string {
  return `Waiting for approval, ${requests(n)}, most urgent first`;
}

/** Announced after a decision in the app (kit: "2 requests still waiting" / "Nothing waiting for approval"). */
export function stillWaiting(n: number): string {
  return n ? `${requests(n)} still waiting` : 'Nothing waiting for approval';
}

/** Read out once when a queued request's last minute starts (kit `data-gw-countdown-announce`). */
export function lastMinuteAnnouncement(item: Pick<ApprovalItem, 'tool' | 'connection' | 'endpoint'>): string {
  return `${item.tool} on ${item.connection ?? item.endpoint} is denied automatically in less than a minute`;
}

/** Decided requests of the last 24 hours (the History tab's count): every status the core counted but `pending`. */
export function decidedCount(counts: { readonly [status: string]: number } | null | undefined): number {
  if (!counts) return 0;
  return Object.entries(counts).reduce((sum, [status, n]) => (status === 'pending' ? sum : sum + (n ?? 0)), 0);
}

/** The queue, soonest auto-deny first (the core sorts it so; a list merged from events is sorted again). */
export function sortQueue(items: readonly ApprovalItem[]): ApprovalItem[] {
  return [...items].sort((a, b) => Date.parse(a.expiresAt) - Date.parse(b.expiresAt) || a.id.localeCompare(b.id));
}

/**
 * The request that opens when the URL names none (kit: "the request about to be denied is never left closed"): the
 * pending one with the least time left that hasn't run out in front of the person yet.
 */
export function mostUrgent(items: readonly ApprovalItem[], expiredHere: ReadonlySet<string>, nowMs: number): ApprovalItem | null {
  return sortQueue(items).find((i) => i.status === 'pending' && !expiredHere.has(i.id) && Date.parse(i.expiresAt) > nowMs) ?? null;
}

/** The request's call in Activity: its event (for `#event-<id>`) and its trace (for Activity › Traces). */
export interface CallRef {
  readonly eventId: number;
  readonly traceId: string | null;
}

/**
 * approval id → its call in Activity, from the events of {@link ACTIVITY_INDEX_QUERY}. The newest row of a call wins
 * (the core lists newest first); a request's call appears once per phase at most.
 */
export function callIndex(events: readonly ActivityEvent[]): Map<string, CallRef> {
  const index = new Map<string, CallRef>();
  for (const e of events) {
    if (e.approvalId && !index.has(e.approvalId)) index.set(e.approvalId, { eventId: e.id, traceId: e.traceId });
  }
  return index;
}

/**
 * A request's call in Activity: what its detail says (step API: `eventId`, `traceId` and whether that trace is a task
 * of Traces) or what Activity listed for it, the newer event of the two. A trace that isn't a task — the gateway's own
 * id for a lone call — gets no Trace row: Traces wouldn't list it. A core before step API leaves Activity's index.
 */
export function callOf(
  detail: Pick<ApprovalDetail, 'eventId' | 'traceId' | 'traceIsTask'> | null | undefined,
  listed: CallRef | undefined,
): CallRef | null {
  if (detail?.eventId === undefined || detail.eventId === null) return listed ?? null;
  const traceId = detail.traceIsTask ? (detail.traceId ?? null) : null;
  return { eventId: Math.max(detail.eventId, listed?.eventId ?? 0), traceId };
}

/** Activity's index with what the loaded details say about their calls (see {@link callOf}). */
export function withOwnCalls(
  calls: ReadonlyMap<string, CallRef>,
  details: ReadonlyMap<string, Pick<ApprovalDetail, 'eventId' | 'traceId' | 'traceIsTask'>>,
): Map<string, CallRef> {
  const out = new Map(calls);
  for (const [id, d] of details) {
    const call = callOf(d, calls.get(id));
    if (call) out.set(id, call);
  }
  return out;
}

/** Where a link leads: a route with its fragment or query. */
export interface Link {
  readonly route: string;
  readonly fragment?: string;
  readonly queryParams?: { readonly [key: string]: string };
}

/** One row of the History table (kit: Request | Decision). */
export interface HistoryRow {
  readonly id: string;
  readonly tool: string;
  readonly target: string;
  readonly env: ApprovalItem['env'];
  /** When it was decided (or ran out): the row's time. */
  readonly at: string;
  readonly badge: Look;
  /** "By you", "By you, from Slack", "No answer", "By MCP Gateway"… */
  readonly by: string;
  /** The row link's hidden rest: " on dev-db from Codex, approved by you: open in Activity". */
  readonly srText: string;
  readonly link: Link;
}

/** When a request left the queue: decided, or ran out (`expiresAt`), else when it was made. */
export function decidedAt(item: Pick<ApprovalItem, 'status' | 'decidedAt' | 'expiresAt' | 'requestedAt'>): string {
  if (item.decidedAt) return item.decidedAt;
  return item.status === 'expired' ? item.expiresAt : item.requestedAt;
}

/** Who decided, as the history's caption says it (desktop: one local user, "by you"). */
export function decidedBy(item: Pick<ApprovalItem, 'status' | 'decidedVia' | 'client'>): string {
  if (item.status === 'expired' || item.decidedVia === 'timeout') return 'No answer';
  switch (item.decidedVia) {
    case 'core':
      return 'By MCP Gateway';
    case 'client':
      return `Withdrawn by ${who(item.client)}`;
    case 'slack':
      return 'By you, from Slack';
    case 'telegram':
      return 'By you, from Telegram';
    case 'desktop-notification':
      return 'By you, from the desktop notification';
    default:
      return 'By you';
  }
}

/**
 * Approved by the person, then not run: the gateway checked again before running it (the connection left the endpoint,
 * the token was revoked, the policy changed) and stopped it. The request then reads `rejected` with the person's
 * channel as `decidedVia`; only its timeline tells the two apart.
 */
export function stoppedAfterApproval(d: Pick<ApprovalDetail, 'status' | 'timeline'> | null | undefined): boolean {
  if (!d || d.status !== 'rejected') return false;
  return d.timeline.some((e) => {
    const detail = e.detail && typeof e.detail === 'object' && !Array.isArray(e.detail) ? e.detail : {};
    return e.kind === 'decided' && detail['decision'] === 'approved';
  });
}

/** The outcome in words for the row link's screen-reader text ("approved by you from Slack"). */
function outcome(item: ApprovalItem, stopped: boolean): string {
  if (stopped) return 'approved by you, then stopped by MCP Gateway before it ran';
  const by = decidedBy(item);
  const window = windowMinutes(item);
  switch (requestState(item.status)) {
    case 'expired':
      return `expired after ${window} min with no answer`;
    case 'failed':
      return 'approved by you, then the call failed';
    case 'approved':
      return 'approved by you, running now';
    case 'executed':
      return by === 'By you' ? 'approved by you' : `approved ${lower(by)}`;
    case 'rejected':
      if (item.decidedVia === 'core') return 'rejected by MCP Gateway';
      if (item.decidedVia === 'client') return lower(by);
      return by === 'By you' ? 'rejected by you' : `rejected ${lower(by)}`;
    default:
      return 'cancelled';
  }
}

function lower(s: string): string {
  return s ? s[0].toLowerCase() + s.slice(1) : s;
}

/** The requests the History tab lists: the newest {@link HISTORY_ROWS} decided in the last 24 hours. */
export function historyItems(items: readonly ApprovalItem[], nowMs: number): ApprovalItem[] {
  return items.filter((i) => i.status !== 'pending' && nowMs - Date.parse(decidedAt(i)) <= HISTORY_WINDOW_MS).slice(0, HISTORY_ROWS);
}

/**
 * The History tab (kit: "Decided and expired requests. Each row opens its call in Activity."). A row links its call's
 * event when Activity has it, else Activity filtered to the request's tool and connection. With the request's detail
 * at hand, a request approved here and then stopped by the gateway says so instead of a plain "By you".
 */
export function historyRows(
  items: readonly ApprovalItem[],
  calls: ReadonlyMap<string, CallRef>,
  details: ReadonlyMap<string, Pick<ApprovalDetail, 'status' | 'timeline'>> = new Map(),
): HistoryRow[] {
  return items.map((i) => {
    const target = i.connection ?? i.endpoint;
    const call = calls.get(i.id);
    const stopped = stoppedAfterApproval(details.get(i.id));
    const q = [`tool:${i.tool}`, i.connection ? `connection:${i.connection}` : `endpoint:${i.endpoint}`].join(' ');
    return {
      id: i.id,
      tool: i.tool,
      target,
      env: i.env,
      at: decidedAt(i),
      badge: statusBadge(requestState(i.status)),
      by: stopped ? 'Approved by you, stopped by MCP Gateway' : decidedBy(i),
      srText: ` on ${target} from ${who(i.client)}, ${outcome(i, stopped)}: open in Activity`,
      link: call ? { route: '/activity', fragment: `event-${call.eventId}` } : { route: '/activity', queryParams: { q } },
    };
  });
}

/** The paginator's line under the history (kit: "Newest 4 of 58 in the last 24 hours"). */
export function historyInfo(shown: number, total: number): string {
  if (shown >= total) return total === 1 ? 'The only one in the last 24 hours' : `All ${total} in the last 24 hours`;
  return `Newest ${shown} of ${total} in the last 24 hours`;
}

/** A toast after a decision in the app (kit: the decision buttons' `data-gw-toast`). */
export interface DecisionToast {
  readonly title: string;
  readonly body: string;
  readonly tone: 'success' | 'neutral';
  readonly icon: string;
}

/** What the action is in a sentence: "the statement", "the request". */
function actionThing(kind: string | null | undefined): string {
  switch ((kind ?? '').toLowerCase()) {
    case 'sql':
      return 'statement';
    case 'http':
      return 'request';
    case 'command':
      return 'command';
    case 'runbook':
      return 'runbook';
    case 'diff':
      return 'change';
    default:
      return 'action';
  }
}

/**
 * The toast after Deny or Approve and run (kit: "sql_execute on dev-db rejected" · "Codex was told the statement was
 * rejected. Nothing ran on dev-db." / "sql_execute on dev-db approved" · "Running on dev-db. The result goes back to
 * Codex and into Activity.").
 */
export function decisionToast(r: Pick<ApprovalDetail, 'tool' | 'connection' | 'endpoint' | 'client' | 'action'>, decision: 'approve' | 'deny'): DecisionToast {
  const target = r.connection ?? r.endpoint;
  const client = who(r.client);
  const agent = r.client ? client : 'The agent';
  if (decision === 'deny') {
    return {
      title: `${r.tool} on ${target} rejected`,
      body: `${agent} was told the ${actionThing(r.action?.kind)} was rejected. Nothing ran on ${target}.`,
      tone: 'neutral',
      icon: 'user-x',
    };
  }
  return {
    title: `${r.tool} on ${target} approved`,
    body: `Running on ${target}. The result goes back to ${client} and into Activity.`,
    tone: 'success',
    icon: 'user-check',
  };
}

/** Said when a request the person is looking at was decided elsewhere (Slack, a notification, the core). */
export function resolvedElsewhere(r: Pick<ApprovalItem, 'tool' | 'connection' | 'endpoint'>, status: string, via: string | null): string {
  const what = `${r.tool} on ${r.connection ?? r.endpoint}`;
  switch (status as ApprovalStatus) {
    case 'expired':
      return `${what} expired, denied automatically`;
    case 'rejected':
      return via === 'core' ? `${what} was rejected by MCP Gateway` : `${what} was rejected${viaWords(via)}`;
    case 'running':
    case 'completed':
      return `${what} was approved${viaWords(via)}`;
    case 'failed':
      return `${what} failed after approval`;
    default:
      return `${what} is no longer waiting`;
  }
}

function viaWords(via: string | null): string {
  switch (via) {
    case 'slack':
      return ' in Slack';
    case 'telegram':
      return ' in Telegram';
    case 'desktop-notification':
      return ' from the desktop notification';
    case 'client':
      return ' by the agent';
    default:
      return '';
  }
}

// ── Delivery channels ────────────────────────────────────────────────────────────────────────────────────────────

/** One status of the header's "Sent to" line (`gw-status`); `mono` is a literal after the label (`#deploy-approvals`). */
export interface ChannelStatus {
  readonly tone: 'success' | 'neutral' | 'warning' | 'danger';
  readonly label: string;
  readonly mono: string | null;
}

const str = (v: JsonValue | undefined): string | null => (typeof v === 'string' && v.trim() ? v : null);

function channelOf(n: Notifications, kind: NotificationChannel['kind']): NotificationChannel | null {
  return n.channels.find((c) => c.kind === kind) ?? null;
}

/** Whether a channel was ever set up (Slack and Telegram need a token first; the desktop needs nothing). */
function configured(c: NotificationChannel | null): boolean {
  return !!c && (c.enabled || Object.keys(c.config ?? {}).length > 0);
}

/**
 * A chat that can't deliver now (K2 states: `error`, `stopped` — Socket Mode off in the Slack app; the desktop's
 * `failed`). Review 2026-10-26: `stopped` read as working ("Slack #deploy-approvals", "You decide … from Slack").
 */
function failing(c: NotificationChannel): boolean {
  return /^(error|failed|failing|stopped)$/i.test(c.state ?? '');
}

/** K2: the stored settings don't verify — the chat is off until they are saved again; it neither posts nor decides. */
function changedOutside(c: NotificationChannel | null): boolean {
  return c?.state === 'changed-outside';
}

/** A chat that posts requests and takes decisions now. */
function working(c: NotificationChannel | null): boolean {
  return !!c && c.enabled && !failing(c) && !changedOutside(c);
}

/**
 * The Slack channel requests are posted to (`#deploy-approvals`); null for direct messages to linked approvers. K2
 * says where in `deliverTo` (`dm`, its default, or `channel`) and keeps the last channel when Slack is switched to
 * direct messages, so a channel name alone doesn't mean the request was posted where "everyone in the channel sees
 * the exact action" (review 2026-10-26). Without `deliverTo` (the contract before K2) a `mode: dm` still reads as DM.
 */
export function slackChannel(n: Notifications | null): string | null {
  const c = n ? channelOf(n, 'slack') : null;
  if (!c) return null;
  const name = str(c.config['channelName']) ?? str(c.config['channel']);
  const deliverTo = str(c.config['deliverTo']) ?? (str(c.config['mode']) === 'dm' ? 'dm' : 'channel');
  if (!name || deliverTo !== 'channel') return null;
  return name.startsWith('#') ? name : `#${name}`;
}

/**
 * The header's "Sent to" line (kit: Desktop on · Slack `#deploy-approvals` · Telegram not set up), from Settings ›
 * Notifications. Desktop is on unless turned off; Slack and Telegram read "not set up" until they have a token.
 */
export function channelStatuses(n: Notifications): ChannelStatus[] {
  const desktop = channelOf(n, 'desktop');
  const slack = channelOf(n, 'slack');
  const telegram = channelOf(n, 'telegram');
  const out: ChannelStatus[] = [];
  out.push(desktop && !desktop.enabled ? { tone: 'neutral', label: 'Desktop off', mono: null } : { tone: 'success', label: 'Desktop on', mono: null });
  if (changedOutside(slack)) out.push({ tone: 'warning', label: 'Slack changed outside MCP Gateway', mono: null });
  else if (slack?.enabled && failing(slack)) out.push({ tone: 'danger', label: 'Slack failing', mono: null });
  else if (slack?.enabled) {
    const channel = slackChannel(n);
    out.push(channel ? { tone: 'success', label: 'Slack', mono: channel } : { tone: 'success', label: 'Slack direct messages', mono: null });
  } else out.push({ tone: 'neutral', label: configured(slack) ? 'Slack off' : 'Slack not set up', mono: null });
  if (changedOutside(telegram)) out.push({ tone: 'warning', label: 'Telegram changed outside MCP Gateway', mono: null });
  else if (telegram?.enabled && failing(telegram)) out.push({ tone: 'danger', label: 'Telegram failing', mono: null });
  else if (telegram?.enabled) out.push({ tone: 'success', label: 'Telegram on', mono: null });
  else out.push({ tone: 'neutral', label: configured(telegram) ? 'Telegram off' : 'Telegram not set up', mono: null });
  return out;
}

/**
 * Where the person can decide (kit: "You decide, here or from Slack"): here, and in every chat that works now — a
 * failing, stopped or changed-outside chat takes no decision.
 */
export function decidersLine(n: Notifications | null): string {
  const chats = n ? (['slack', 'telegram'] as const).filter((k) => working(channelOf(n, k))) : [];
  const names = chats.map((k) => (k === 'slack' ? 'from Slack' : 'from Telegram'));
  if (!names.length) return 'You decide here';
  return `You decide, here or ${names.length === 2 ? `${names[0]} or ${names[1]}` : names[0]}`;
}

/** A run of the delivery card's description; `mono` runs are literals (`#deploy-approvals`). */
export interface Part {
  readonly text: string;
  readonly mono: boolean;
}

/** The card under "How this request was delivered" (approval-notification), for one request. */
export interface DeliveryCard {
  /** Which preview the card shows: the chat it was posted to, else the desktop notification; `none`: nothing sent. */
  readonly preview: 'slack' | 'telegram' | 'desktop' | 'none';
  readonly heading: string;
  /** Slack and Telegram show a generic letter mark; the others an icon tile. */
  readonly letter: string | null;
  readonly icon: string;
  readonly description: Part[];
  /** Slack: where it was posted, for the preview's caption. */
  readonly slackChannel: string | null;
  /** The description ends with a link to Notification settings (nothing was delivered). */
  readonly settingsLink: boolean;
}

interface Notified {
  readonly state: string;
  readonly at: string | null;
  readonly target: string | null;
}

/** The request's own `notified` events by channel (the newest wins), with the time and where it went, when told. */
function notifiedEvents(r: Pick<ApprovalDetail, 'timeline'>): Map<string, Notified> {
  const out = new Map<string, Notified>();
  for (const e of r.timeline) {
    if (e.kind !== 'notified' || !e.detail || typeof e.detail !== 'object' || Array.isArray(e.detail)) continue;
    const d = e.detail as { readonly [k: string]: JsonValue };
    const channel = str(d['channel']);
    if (!channel) continue;
    out.set(channel, { state: str(d['state']) ?? 'sent', at: e.at, target: str(d['target']) ?? str(d['to']) ?? str(d['channelName']) });
  }
  return out;
}

/**
 * The delivery card of one request (kit: "Posted to #deploy-approvals at 14:29:13, where everyone in the channel sees
 * the exact action, and shown as a desktop notification with Review and Deny. Telegram is not set up, so nothing was
 * sent there."), from the request's channels and `notified` events; Settings › Notifications only adds the Slack
 * channel's name and whether a channel is set up at all.
 */
export function deliveryCard(
  r: Pick<ApprovalDetail, 'channels' | 'timeline'>,
  n: Notifications | null,
  clock: (at: string) => string,
): DeliveryCard {
  const channels: ApprovalChannels = r.channels ?? { desktop: null, slack: null, telegram: null };
  const events = notifiedEvents(r);
  const state = (k: 'desktop' | 'slack' | 'telegram'): string => channels[k] ?? events.get(k)?.state ?? 'off';
  const parts: Part[] = [];
  const text = (t: string): void => {
    const prev = parts.at(-1);
    if (prev && !prev.mono) parts[parts.length - 1] = { text: prev.text + t, mono: false };
    else parts.push({ text: t, mono: false });
  };
  const mono = (t: string): void => void parts.push({ text: t, mono: true });
  const at = (k: string): string => {
    const e = events.get(k);
    return e?.at ? ` at ${clock(e.at)}` : '';
  };
  const slackTo = events.get('slack')?.target ?? slackChannel(n);
  const sentences: (() => void)[] = [];

  const slack = state('slack');
  const telegram = state('telegram');
  const desktop = state('desktop');
  // The first sentence lists where it went: each clause starts with a capital only when it opens the sentence.
  const lead: ((first: boolean) => void)[] = [];
  const cap = (s: string, first: boolean): string => (first ? s[0].toUpperCase() + s.slice(1) : s);
  if (slack === 'sent') {
    lead.push((first) => {
      if (slackTo) {
        text(cap('posted to ', first));
        mono(slackTo);
        text(`${at('slack')}, where everyone in the channel sees the exact action`);
      } else text(cap(`sent to you in Slack as a direct message${at('slack')}`, first));
    });
  }
  if (telegram === 'sent') lead.push((first) => text(cap(`sent to your Telegram chat${at('telegram')}`, first)));
  if (desktop === 'sent') lead.push((first) => text(cap('shown as a desktop notification with Review and Deny', first)));
  if (lead.length) {
    sentences.push(() => {
      lead.forEach((write, i) => {
        if (i > 0) text(i === lead.length - 1 ? ', and ' : ', ');
        write(i === 0);
      });
      text('.');
    });
  }
  const failed = (['slack', 'telegram', 'desktop'] as const).filter((k) => state(k) === 'failed');
  for (const k of failed) {
    sentences.push(() => text(`${k === 'desktop' ? 'The desktop notification' : k === 'slack' ? 'Slack' : 'Telegram'} didn't take it${at(k)}.`));
  }
  const setUp = (k: 'slack' | 'telegram'): boolean | null => (n ? configured(channelOf(n, k)) : null);
  for (const k of ['slack', 'telegram'] as const) {
    if (state(k) !== 'off' || !lead.length) continue;
    const name = k === 'slack' ? 'Slack' : 'Telegram';
    sentences.push(() => text(setUp(k) === false ? `${name} is not set up, so nothing was sent there.` : `Nothing was sent to ${name}.`));
  }

  const preview: DeliveryCard['preview'] = slack === 'sent' ? 'slack' : telegram === 'sent' ? 'telegram' : desktop === 'sent' ? 'desktop' : 'none';
  if (preview === 'none') {
    // The card ends with a link to Notification settings (the template adds it after these words).
    if (failed.length) {
      sentences.forEach((write, i) => {
        if (i > 0) text(' ');
        write();
      });
      text(' The request waited here, in MCP Gateway. Check the channels in');
    } else {
      text('No notification channel sent this request, so it waited here, in MCP Gateway. Desktop notifications, Slack and Telegram are set up in');
    }
    return { preview, heading: 'Not sent outside the window', letter: null, icon: 'app-window', description: parts, slackChannel: null, settingsLink: true };
  }
  sentences.forEach((write, i) => {
    if (i > 0) text(' ');
    write();
  });
  const heading = preview === 'slack' ? 'Slack' : preview === 'telegram' ? 'Telegram' : 'Desktop notification';
  const letter = preview === 'slack' ? 'S' : preview === 'telegram' ? 'T' : null;
  return { preview, heading, letter, icon: 'monitor', description: parts, slackChannel: preview === 'slack' ? slackTo : null, settingsLink: false };
}

/**
 * After "Approve and run" the toast says it is running; the gateway checks everything again before it runs it. If it
 * then stops the call, or the call fails, the person hears about it even though the pane has moved on.
 */
export function afterApprovalToast(
  d: Pick<ApprovalDetail, 'tool' | 'connection' | 'endpoint' | 'status' | 'error'>,
): { readonly title: string; readonly body: string; readonly tone: 'danger'; readonly icon: string } | null {
  const target = d.connection ?? d.endpoint;
  if (d.status === 'rejected') {
    return {
      title: `${d.tool} on ${target} didn't run`,
      body: d.error ?? `MCP Gateway checked it again before running it and stopped it. Nothing ran on ${target}.`,
      tone: 'danger',
      icon: 'ban',
    };
  }
  if (d.status === 'failed') {
    return {
      title: `${d.tool} on ${target} failed`,
      body: d.error ?? 'The call failed after your approval. The request shows its output.',
      tone: 'danger',
      icon: 'circle-alert',
    };
  }
  return null;
}

/** The fact a chat message shows after its rule (kit: "Rule dev.sql.dml-approval · estimated rows 1"). */
export function ruleNote(r: Pick<ApprovalDetail, 'impact'>): string | null {
  const first = r.impact[0];
  if (!first) return null;
  return `${first.label.toLowerCase()} ${first.text}`;
}

/** "Codex" for a client id; the agent when the call didn't say (re-exported for the page template). */
export function clientLabel(client: string | null): string {
  return client ? clientName(client) : 'the agent';
}
