import { ActionSegment, ApprovalDetail, ApprovalStatus, ApprovalTimelineEntry, Diff } from '../../../core/api/approvals.api';
import { JsonValue } from '../../../core/api/types';
import { duration, elapsed } from '../../../core/time/time-format';
import { confusableTitle, inspectPlain, invisibleTitle } from '../../../code/unicode';
import { clientName } from '../shared/client-mark';
import { Look } from '../shared/dictionaries';

/**
 * What the approval-request element shows, computed from the admin API's `ApprovalDetail` (ARCHITECTURE.md §6
 * approvals). Pure functions: the element binds them, the tests check them against the kit's wording
 * (design/elements/approval-request.html).
 */

/** The element's `data-state` (the kit's states; `partial` exists only in server mode). */
export type RequestState = 'pending' | 'approved' | 'executed' | 'rejected' | 'expired' | 'failed' | 'cancelled';

export function requestState(status: ApprovalStatus): RequestState {
  switch (status) {
    case 'pending':
      return 'pending';
    case 'running':
      return 'approved';
    case 'completed':
      return 'executed';
    case 'rejected':
      return 'rejected';
    case 'expired':
      return 'expired';
    case 'failed':
      return 'failed';
    default:
      return 'cancelled';
  }
}

/** The status badge (BLUEPRINT §7): a decided request reads as its decision. */
export function statusBadge(state: RequestState): Look {
  switch (state) {
    case 'pending':
      return { tone: 'warning', icon: 'hourglass', text: 'Waiting for approval' };
    case 'approved':
    case 'executed':
      return { tone: 'success', icon: 'user-check', text: 'Approved' };
    case 'rejected':
      return { tone: 'danger', icon: 'user-x', text: 'Rejected' };
    case 'expired':
      return { tone: 'neutral', icon: 'timer-off', text: 'Expired' };
    case 'failed':
      return { tone: 'danger', icon: 'circle-alert', text: 'Error' };
    default:
      return { tone: 'neutral', icon: 'ban', text: 'Cancelled' };
  }
}

/** The code block's language label (overline) for an action kind. */
export function actionLabel(kind: string | null, language: string | null): string {
  switch ((kind ?? language ?? '').toLowerCase()) {
    case 'sql':
      return 'SQL';
    case 'command':
    case 'sh':
      return 'Command';
    case 'http':
      return 'HTTP';
    case 'runbook':
      return 'Runbook';
    case 'json':
      return 'JSON';
    case 'diff':
      return 'Diff';
    default:
      return 'Text';
  }
}

/** What the action is, for screen readers: "Statement", "Command", "HTTP request", "Runbook", "Changes". */
export function actionNoun(kind: string | null): string {
  switch ((kind ?? '').toLowerCase()) {
    case 'sql':
      return 'Statement';
    case 'command':
      return 'Command';
    case 'http':
      return 'HTTP request';
    case 'runbook':
      return 'Runbook';
    case 'diff':
      return 'Changes';
    default:
      return 'Action';
  }
}

/** The diff's header line: "2 lines changed" when every removed line has a replacement. */
export function diffSummary(d: Diff): string | null {
  if (d.added && d.added === d.removed) return `${d.added} ${d.added === 1 ? 'line' : 'lines'} changed`;
  return null;
}

/** The decision window in whole minutes (dev 5, stage and prod 10), from the request's own times. */
export function windowMinutes(r: Pick<ApprovalDetail, 'requestedAt' | 'expiresAt'>): number {
  return Math.round((Date.parse(r.expiresAt) - Date.parse(r.requestedAt)) / 60_000);
}

export function windowSeconds(r: Pick<ApprovalDetail, 'requestedAt' | 'expiresAt'>): number {
  return Math.max(1, Math.round((Date.parse(r.expiresAt) - Date.parse(r.requestedAt)) / 1000));
}

/** How the client authenticated: Claude Desktop gets its tokens from the bridge, never a named one. */
export function tokenText(client: string | null, tokenLabel: string | null): { readonly code: boolean; readonly text: string } {
  if (client === 'claude-desktop') return { code: false, text: 'Bridge (.mcpb)' };
  return { code: true, text: tokenLabel ?? 'unknown' };
}

/** "Codex", "Claude Desktop"; "the agent" when the client didn't say. */
export function who(client: string | null): string {
  return client ? clientName(client) : 'the agent';
}

/** A run of a sentence; `mono` runs are literals the kit sets in `gw-mono` (escapes, letters, words). */
export interface MonoPart {
  readonly text: string;
  readonly mono: boolean;
}

/**
 * The warning above an action with hidden characters (kit: "Contains 3 hidden characters and 1 look-alike letter")
 * and its body naming each one, from the server's segments: the escapes the inspector cut and the word each
 * look-alike letter sits in ("the identifier contains Cyrillic U+043E, not a Latin o").
 */
export function hiddenWarning(segments: readonly ActionSegment[]): { readonly title: string; readonly body: MonoPart[] } | null {
  const hidden = segments.filter((s) => s.t === 'invisible');
  const confusable = segments.filter((s) => s.t === 'confusable');
  if (!hidden.length && !confusable.length) return null;
  const counts: string[] = [];
  if (hidden.length) counts.push(`${hidden.length} hidden ${hidden.length === 1 ? 'character' : 'characters'}`);
  if (confusable.length) counts.push(`${confusable.length} look-alike ${confusable.length === 1 ? 'letter' : 'letters'}`);
  const body: MonoPart[] = [];
  const text = (t: string): void => {
    const prev = body.at(-1);
    if (prev && !prev.mono) body[body.length - 1] = { text: prev.text + t, mono: false };
    else body.push({ text: t, mono: false });
  };
  const mono = (t: string): void => void body.push({ text: t, mono: true });
  const escapes = [...new Set(hidden.map((s) => s.v))];
  escapes.forEach((v, i) => {
    if (i > 0) text(i === escapes.length - 1 ? ' and ' : ', ');
    mono(v);
    text(` ${invisibleTitle(v).replace(/^U\+[0-9A-F]+ /, '')}`);
  });
  if (escapes.length) text(` ${escapes.length === 1 ? 'changes' : 'change'} how the text reads`);
  confusable.forEach((s, i) => {
    const title = confusableTitle(s.v);
    const [cp, script] = title.split(' ');
    const latin = title.match(/letter (\S+)$/)?.[1];
    text(escapes.length || i > 0 ? '; the ' : 'The ');
    mono(s.v);
    text(' in ');
    mono(wordAround(segments, s));
    text(` is ${script} (${cp})${latin ? `, not a Latin ${latin}` : ''}`);
  });
  const many = hidden.length + confusable.length > 1;
  text(`. ${many ? 'All are' : 'It is'} shown boxed below. Deny unless you expected ${many ? 'them' : 'it'}.`);
  if (body[0] && !body[0].mono) body[0] = { text: capitalize(body[0].text), mono: false };
  return { title: `Contains ${counts.join(' and ')}`, body };
}

/** The word (letters, digits, `_`) a confusable letter sits in, from the segments around it. */
function wordAround(segments: readonly ActionSegment[], at: ActionSegment): string {
  const i = segments.indexOf(at);
  let before = '';
  for (let j = i - 1; j >= 0 && segments[j].t !== 'invisible'; j--) {
    const m = /[\p{L}\p{N}_]*$/u.exec(segments[j].v);
    before = (m?.[0] ?? '') + before;
    if ((m?.[0].length ?? 0) < segments[j].v.length) break;
  }
  let after = '';
  for (let j = i + 1; j < segments.length && segments[j].t !== 'invisible'; j++) {
    const m = /^[\p{L}\p{N}_]*/u.exec(segments[j].v);
    after += m?.[0] ?? '';
    if ((m?.[0].length ?? 0) < segments[j].v.length) break;
  }
  return before + at.v + after;
}

function list(items: readonly string[]): string {
  if (items.length <= 1) return items.join('');
  return `${items.slice(0, -1).join(', ')} and ${items.at(-1)}`;
}

function capitalize(s: string): string {
  return s ? s[0].toUpperCase() + s.slice(1) : s;
}

/** Where a decision came from, as the history writes it. */
export function viaText(via: string | null | undefined): string {
  switch (via) {
    case 'app':
      return 'In the app';
    case 'desktop-notification':
      return 'From the desktop notification';
    case 'slack':
      return 'From Slack';
    case 'telegram':
      return 'From Telegram';
    case 'timeout':
      return 'No decision in time';
    default:
      return via ? `Via ${via}` : 'In the app';
  }
}

/** One step of the approval history (`gw-timeline__item`). */
export interface HistoryStep {
  readonly tone: 'neutral' | 'info' | 'success' | 'warning' | 'danger';
  readonly icon: string;
  readonly title: string;
  /** Text with literals: [text, code] runs. */
  readonly meta: readonly { readonly text: string; readonly code: boolean }[];
  readonly comment: string | null;
  readonly at: string;
  /** "Since 14:29" for the running step, else the clock time. */
  readonly since: boolean;
  readonly current: boolean;
}

const str = (v: JsonValue | undefined): string | null => (typeof v === 'string' ? v : null);
const num = (v: JsonValue | undefined): number | null => (typeof v === 'number' ? v : null);
const obj = (v: JsonValue): { readonly [k: string]: JsonValue } =>
  v && typeof v === 'object' && !Array.isArray(v) ? v : {};

/** "the desktop and Slack" from a channel list. */
function channelList(v: JsonValue | undefined): string {
  const names = (Array.isArray(v) ? v : typeof v === 'string' ? [v] : [])
    .filter((c): c is string => typeof c === 'string')
    .map((c) => (c === 'desktop' ? 'on the desktop' : c === 'slack' ? 'in Slack' : c === 'telegram' ? 'in Telegram' : `in ${c}`));
  return names.length ? list(names) : 'on the desktop';
}

/** Rows affected by an executed statement, from its structured result ("1 row affected"), when the result says. */
export function affected(structured: JsonValue | undefined): string | null {
  const s = obj(structured ?? null);
  const n = num(s['rowsAffected']);
  return n === null ? null : `${n} ${n === 1 ? 'row' : 'rows'} affected`;
}

/**
 * The history of a decided request (`approval_event` rows → timeline steps), as the kit's history reads:
 * "Requested by Codex · Token codex-laptop · endpoint dev", "Notified on the desktop and in Slack", "Approved by you ·
 * From Slack · after 1 min 3 s" (+ the comment), "Executed in 38 ms", "Failed", "Expired". A running request ends
 * with its current step, "Running on dev-cluster".
 */
export function historySteps(r: ApprovalDetail): HistoryStep[] {
  const client = who(r.client);
  const steps: HistoryStep[] = [];
  const windowMin = windowMinutes(r);
  for (const e of r.timeline) {
    const d = obj(e.detail);
    const step = (s: Omit<HistoryStep, 'at' | 'since' | 'current'>): void => {
      steps.push({ ...s, at: e.at, since: false, current: false });
    };
    switch (e.kind) {
      case 'requested': {
        const token = tokenText(r.client, r.tokenLabel);
        const meta = token.code
          ? [t('Token '), c(token.text), t(' · endpoint '), c(r.endpoint)]
          : [t('Bridge token (.mcpb) · endpoint '), c(r.endpoint)];
        step({ tone: 'neutral', icon: 'send', title: `Requested by ${client}`, meta, comment: null });
        break;
      }
      case 'notified':
      case 'delivered':
        step({
          tone: 'info',
          icon: 'bell',
          title: `Notified ${channelList(d['channels'] ?? d['channel'])}`,
          meta: [t(`Sent to you · auto-deny after ${windowMin} min${r.env !== 'dev' ? ` (${r.env})` : ''}`)],
          comment: null,
        });
        break;
      case 'decided': {
        const decision = str(d['decision']);
        const by = str(d['by']);
        const after = elapsed(Date.parse(e.at) - Date.parse(r.requestedAt));
        if (by && by !== 'you') {
          // Rejected by the gateway itself: the connection was deleted, the token revoked, the agent cancelled.
          step({
            tone: 'danger',
            icon: 'ban',
            title: by === 'client' ? `Withdrawn by ${client}` : 'Rejected by MCP Gateway',
            meta: [t(str(d['reason']) ?? 'Not executed')],
            comment: null,
          });
          break;
        }
        const approved = decision === 'approved';
        const meta = [viaText(str(d['via'])), after ? `after ${after}` : '', approved ? '' : r.comment ? `the comment was returned to ${client}` : '']
          .filter(Boolean)
          .join(' · ');
        step({
          tone: approved ? 'success' : 'danger',
          icon: approved ? 'user-check' : 'user-x',
          title: approved ? 'Approved by you' : 'Rejected by you',
          meta: [t(meta)],
          comment: r.comment,
        });
        break;
      }
      case 'completed': {
        const ms = num(d['durationMs']) ?? executedMs(r);
        const facts = [affected(r.result?.structured), `result returned to ${client}`].filter(Boolean).join(' · ');
        step({
          tone: 'success',
          icon: 'circle-check',
          title: ms !== null ? `Executed in ${duration(ms)}` : 'Executed',
          meta: [t(facts)],
          comment: null,
        });
        break;
      }
      case 'failed': {
        const ms = num(d['durationMs']);
        const why = str(d['error']) ?? str(d['reason']);
        const facts = [ms !== null ? `After ${duration(ms)}` : null, why === 'audit' ? 'the audit log refused the decision' : null, `error returned to ${client}`]
          .filter(Boolean)
          .join(' · ');
        step({ tone: 'danger', icon: 'circle-alert', title: 'Failed', meta: [t(facts)], comment: null });
        break;
      }
      case 'expired':
        step({
          tone: 'neutral',
          icon: 'timer-off',
          title: 'Expired',
          meta: [t(`No decision within ${windowMin} min · denied automatically`)],
          comment: null,
        });
        break;
      case 'running':
        break; // shown as the current step below while the request runs
      default:
        step({ tone: 'neutral', icon: 'circle-dot', title: capitalize(e.kind), meta: [], comment: null });
    }
  }
  if (r.status === 'running') {
    const since = r.timeline.find((e) => e.kind === 'running')?.at ?? r.decidedAt ?? r.requestedAt;
    steps.push({
      tone: 'info',
      icon: 'loader-circle',
      title: `Running on ${r.connection ?? r.endpoint}`,
      meta: [],
      comment: null,
      at: since,
      since: true,
      current: true,
    });
  }
  return steps;
}

/** The run's own time, when the core says how long it took (`completed` event), else decided → executed. */
export function executedMs(r: Pick<ApprovalDetail, 'timeline' | 'decidedAt' | 'executedAt'>): number | null {
  const done = r.timeline.find((e: ApprovalTimelineEntry) => e.kind === 'completed' || e.kind === 'failed');
  const ms = done ? num(obj(done.detail)['durationMs']) : null;
  if (ms !== null) return ms;
  if (r.executedAt && r.decidedAt) {
    const d = Date.parse(r.executedAt) - Date.parse(r.decidedAt);
    return d >= 0 ? d : null;
  }
  return null;
}

/** The first line of an action (compact rows and channels): never the agent's words. */
export function firstLine(text: string | null | undefined): string {
  return (text ?? '').split('\n').find((l) => l.trim()) ?? '';
}

/** A run of a one-line preview: plain text, or an escaped invisible character / a look-alike letter with its name. */
export interface PreviewPart {
  readonly cls: 'gw-tok-invisible' | 'gw-tok-confusable' | null;
  readonly text: string;
  readonly title: string | null;
}

/**
 * The first non-blank line of an action for the compact row (DESIGN §8 "The complete action must remain visible"): from the server's
 * segments when the page has them (invisible escapes and look-alike letters kept as the inspector marked them), else
 * from plain text inspected here with the server's rules (`inspectPlain`: invisible, bidi, zero-width and tag
 * characters escaped, non-ASCII letters boxed), so a U+202E in the first line can't reorder what the row shows. No
 * syntax colours: the kit's preview is plain code text.
 */
export function previewParts(segments: readonly ActionSegment[] | null | undefined, text: string | null | undefined): PreviewPart[] {
  // The server's segments are authoritative (as in gw-code-block): their text runs are shown as sent. Plain text is
  // inspected here, line by line.
  const fromServer = !!segments?.length;
  const lines: ActionSegment[][] = [[]];
  for (const s of fromServer ? segments! : (text ? [{ t: 'text' as const, v: text }] : [])) {
    if (s.t !== 'text') {
      lines[lines.length - 1].push(s);
      continue;
    }
    s.v.split(/\r?\n/).forEach((piece, i) => {
      if (i > 0) lines.push([]);
      if (piece) lines[lines.length - 1].push({ t: 'text', v: piece });
    });
  }
  const line = lines.find((l) => l.some((s) => s.t !== 'text' || s.v.trim())) ?? [];
  const out: PreviewPart[] = [];
  for (const s of fromServer ? line : line.flatMap((p) => inspectPlain(p.v))) {
    if (s.t === 'invisible') out.push({ cls: 'gw-tok-invisible', text: s.v, title: invisibleTitle(s.v) });
    else if (s.t === 'confusable') out.push({ cls: 'gw-tok-confusable', text: s.v, title: confusableTitle(s.v) });
    else out.push({ cls: null, text: s.v, title: null });
  }
  return out;
}

const t = (text: string) => ({ text, code: false });
const c = (text: string) => ({ text, code: true });
