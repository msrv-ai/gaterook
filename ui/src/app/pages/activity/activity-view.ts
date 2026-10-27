import { ActivityEvent, EventDetail, Facets, Histogram, HistogramBucket, SavedView, SetupChange, TraceDetail, TraceRow } from '../../core/api/activity.api';
import { TokenRow } from '../../core/api/endpoints.api';
import { AuditExportDestination } from '../../core/api/settings.api';
import { GatewayEvents } from '../../core/events/gateway-events';
import { Env } from '../../core/api/types';
import { absoluteTime, clockTime, duration, formatCount, shortDate } from '../../core/time/time-format';
import { precise } from '../../kit/elements/event-detail/event-view';
import { LogRange, RANGES, parseQuery, rangeBounds, rangeLabel } from '../../kit/elements/log-explorer/log-view';
import { clientName } from '../../kit/elements/shared/client-mark';
import { Look, decisionLook } from '../../kit/elements/shared/dictionaries';

/**
 * What the Activity page says (design/pages/activity.html; ARCHITECTURE.md §6 activity, §8.4 "activity"), computed
 * from the admin API's answers: pure functions, tested without a DOM or a clock.
 */

// ── Tabs and the address ───────────────────────────────────────────────────────────────────────────────────────────

/** The page's three views: Agent calls › Events (default), Agent calls › Traces, Setup changes. */
export type ActivityTab = 'events' | 'traces' | 'changes';

/** `?view=` of a link (`activity.html?view=traces`, `&view=changes`); anything else is the events. */
export function parseView(view: string | null | undefined): ActivityTab {
  return view === 'traces' || view === 'changes' ? view : 'events';
}

/** The `view` query parameter of a tab: none for the default. */
export function viewParam(tab: ActivityTab): string | null {
  return tab === 'events' ? null : tab;
}

/** `#event-<id>` (a row, a deep link `mcp-gateway://activity/events/<id>`, Approvals): the audit row's number. */
export function eventFromFragment(fragment: string | null | undefined): number | null {
  const m = /^event-([0-9]{1,18})$/.exec(fragment ?? '');
  return m ? Number(m[1]) : null;
}

// ── The query and the facets ───────────────────────────────────────────────────────────────────────────────────────

/** The fields the query filters on (`-decision:allowed` filters on decision too). */
export function tagFields(q: string): string[] {
  return [...new Set(parseQuery(q).tags.map((t) => t.replace(/^-/, '').split(':')[0]))];
}

/** The query without one field's terms: what that field's facet counts within ("within the other filters"). */
export function queryWithout(q: string, field: string): string {
  const { tags, text } = parseQuery(q);
  return [...tags.filter((t) => t.replace(/^-/, '').split(':')[0] !== field), text].filter(Boolean).join(' ');
}

/**
 * The facets as the kit counts them (COMPONENTS.md log-explorer "counts respect all other filters"): a field
 * the query filters on counts within the other filters only, so its other values stay visible and checkable; the
 * rest count within the whole query.
 */
export function mergeFacets(base: Facets, perField: Readonly<Record<string, Facets>>): Facets {
  const out: Record<string, Facets[string]> = { ...base };
  for (const [field, facets] of Object.entries(perField)) out[field] = facets[field] ?? [];
  return out;
}

// ── Counts and the range ───────────────────────────────────────────────────────────────────────────────────────────

export function histogramTotal(h: Histogram | null | undefined): number | null {
  return h ? h.buckets.reduce((n, b) => n + b.total, 0) : null;
}

/** Calls waiting for approval in a histogram (the count line: "1,287 calls · 3 waiting for approval"). */
export function histogramWaiting(h: Histogram | null | undefined): number {
  return h ? h.buckets.reduce((n, b) => n + (b.decisions['pending'] ?? 0), 0) : 0;
}

/** The calls of the effective range that match: the zoomed bar's, else the whole histogram's. */
export function matchedCount(h: Histogram | null | undefined, zoom: HistogramBucket | null): number | null {
  if (!h) return null;
  return zoom ? (h.buckets.find((b) => b.from === zoom.from)?.total ?? zoom.total) : histogramTotal(h);
}

/**
 * The instants a request covers: the zoomed hour, a custom range, or a relative range that ends now (`to` null: the
 * core reads up to its own now, so a call that arrives meanwhile isn't cut off).
 */
export function requestWindow(range: LogRange, zoom: Pick<HistogramBucket, 'from' | 'to'> | null, now: number): { readonly from: string; readonly to: string | null } {
  if (zoom) return { from: zoom.from, to: zoom.to };
  const b = rangeBounds(range, now);
  return { from: new Date(b.from).toISOString(), to: range.kind === 'custom' ? new Date(b.to).toISOString() : null };
}

/** "in the last 24 hours", "between 10:00 and 11:00" (a zoomed hour), "between Oct 21 09:00 and Oct 22 12:00". */
export function rangeWords(range: LogRange, zoom: Pick<HistogramBucket, 'from' | 'to'> | null, now: number): string {
  if (zoom) return `between ${clockTime(zoom.from, false)} and ${clockTime(zoom.to, false)}`;
  if (range.kind === 'relative') return `in the ${rangeLabel(range, now).toLowerCase()}`;
  const day = (t: string) => shortDate(t, now);
  const from = `${day(range.from)} ${clockTime(range.from, false)}`;
  const to = day(range.from) === day(range.to) ? clockTime(range.to, false) : `${day(range.to)} ${clockTime(range.to, false)}`;
  return `between ${from} and ${to}`;
}

/** What "Show the last 24 hours" returns to after a zoom: the relative range's name, else "the whole range". */
export function backToRange(range: LogRange, now: number): string {
  return range.kind === 'relative' ? `Show the ${rangeLabel(range, now).toLowerCase()}` : 'Show the whole range';
}

// ── Event rows ─────────────────────────────────────────────────────────────────────────────────────────────────────

/** A client of a call: its name and mark (Playground is the app itself). */
export function clientView(client: string | null | undefined): { readonly id: string; readonly name: string; readonly letter: string | null } {
  if (!client) return { id: 'unknown', name: 'Unknown client', letter: '?' };
  if (client === 'playground') return { id: 'playground', name: 'Playground', letter: 'P' };
  return { id: client, name: clientName(client), letter: null };
}

/**
 * The Duration cell (kit: "38 ms", "47.0 s", "2.0 s", "52 s", "10 min", "30 s so far"): a call that hasn't come to an
 * end counts up from when it was made; a wait for a person (rejected, expired) is said to the second, a run to a tenth
 * of a second; a call that never ran says so.
 */
export function durationCell(e: Pick<ActivityEvent, 'decision' | 'durationMs' | 'at'>, now: number): string {
  if (e.decision === 'pending' || (e.durationMs == null && (e.decision === 'allowed' || e.decision === 'approved'))) {
    return `${duration(Math.max(0, now - Date.parse(e.at)))} so far`;
  }
  if (e.durationMs == null) return e.decision === 'error' ? 'Failed' : 'Not run';
  if (e.decision === 'rejected' || e.decision === 'expired') return duration(e.durationMs);
  return precise(e.durationMs);
}

export type SortKey = 'time' | 'duration';
export interface Sort {
  readonly key: SortKey;
  readonly direction: 'ascending' | 'descending';
}

export const NEWEST_FIRST: Sort = { key: 'time', direction: 'descending' };

/** The loaded rows in the table's order (the core lists newest first; Duration sorts what is on screen). */
export function sortRows(rows: readonly ActivityEvent[], sort: Sort, now: number): ActivityEvent[] {
  const sign = sort.direction === 'ascending' ? 1 : -1;
  const value = (e: ActivityEvent) => (sort.key === 'time' ? Date.parse(e.at) : (e.durationMs ?? (e.decision === 'pending' ? now - Date.parse(e.at) : -1)));
  return [...rows].sort((a, b) => sign * (value(a) - value(b)) || b.id - a.id);
}

/**
 * The newest page merged over what is on screen (Live): new calls come first, a call already listed takes its newer
 * row, older loaded pages stay. When the newest page doesn't reach what was listed (more new calls than a page), the
 * list restarts from it: a gap would be worse than a shorter list.
 */
export function mergeHead(head: readonly ActivityEvent[], rows: readonly ActivityEvent[]): { readonly rows: ActivityEvent[]; readonly restarted: boolean } {
  const seen = new Set(head.map((e) => e.callId));
  const overlap = rows.some((e) => seen.has(e.callId));
  if (rows.length && !overlap && head.length) return { rows: [...head], restarted: true };
  return { rows: [...head, ...rows.filter((e) => !seen.has(e.callId))], restarted: false };
}

/**
 * A row of a call already on screen, updated from its newer audit row (`audit.call`): a waiting call becomes approved,
 * rejected or expired in place. Null when the call isn't listed.
 */
export function applyCall(rows: readonly ActivityEvent[], c: GatewayEvents['audit.call']): ActivityEvent[] | null {
  const i = rows.findIndex((e) => e.callId === c.callId);
  if (i < 0 || rows[i].id > c.id) return null;
  const next: ActivityEvent = {
    ...rows[i],
    id: c.id,
    at: c.at,
    phase: c.phase as ActivityEvent['phase'],
    decision: c.decision,
    durationMs: c.durationMs,
    rule: c.rule ?? rows[i].rule,
    approvalId: c.approvalId ?? rows[i].approvalId,
    traceId: c.traceId ?? rows[i].traceId,
  };
  return rows.map((e, j) => (j === i ? next : e));
}

// ── Lines of the Events tab ────────────────────────────────────────────────────────────────────────────────────────

const calls = (n: number) => `${formatCount(n)} ${n === 1 ? 'call' : 'calls'}`;

/** The table's caption: "Calls in the last 24 hours matching decision:denied, newest first. Each row opens …". */
export function captionText(words: string, q: string): string {
  return `Calls ${words}${q ? ` matching ${q}` : ''}, newest first. Each row opens the call's details.`;
}

/** The paginator: "Showing 18 of 1,287 calls in the last 24 hours", "All 9 calls that match in the last hour". */
export function pagerText(shown: number, matched: number | null, filtered: boolean, words: string): string {
  const that = filtered ? ' that match' : '';
  if (matched == null || shown >= matched) return `${shown === 1 ? 'The only call' : `All ${calls(shown)}`}${that} ${words}`;
  return `Showing ${formatCount(shown)} of ${calls(matched)}${that} ${words}`;
}

export interface EmptyState {
  readonly title: string;
  readonly text: string;
  /** "Show the last 24 hours" (a zoomed hour), "Show the last 7 days" (nothing in a short range). */
  readonly reset: string | null;
  readonly widen: boolean;
  readonly clear: boolean;
}

/**
 * The Events tab when nothing is listed (kit `#ac-empty`): an hour without calls, a query nothing matches, or a range
 * with no calls at all (the gateway has calls, just not in it).
 */
export function emptyState(o: {
  readonly q: string;
  readonly words: string;
  readonly zoom: Pick<HistogramBucket, 'from' | 'to'> | null;
  readonly back: string;
  readonly widest: boolean;
}): EmptyState {
  if (o.q) {
    return {
      title: 'No calls match these filters',
      text: `No call ${o.words} matches ${o.q}. Remove a filter or widen the range.`,
      reset: o.zoom ? o.back : null,
      widen: false,
      clear: true,
    };
  }
  if (o.zoom) {
    return {
      title: 'No calls match these filters',
      text: `No agent called the gateway ${o.words}. Pick another hour or go back to the ${o.back.replace(/^Show the /, '')}.`,
      reset: o.back,
      widen: false,
      clear: false,
    };
  }
  return {
    title: `No calls ${o.words}`,
    text: o.widest ? 'Agents made no calls in this range. Pick a custom range to look further back.' : 'Agents made no calls in this range. A wider range shows the earlier ones.',
    reset: null,
    widen: !o.widest,
    clear: false,
  };
}

/**
 * `?range=` (ARCHITECTURE.md §8.1): Activity's relative ranges and the 30 days a link from Insights may carry
 * ("View in Activity" keeps the page's range, U13). Anything else — a custom range has no key — is ignored.
 */
export function parseRange(raw: string | null | undefined): LogRange | null {
  const key = (raw ?? '').trim();
  return key === '15m' || key === '1h' || key === '24h' || key === '7d' || key === '30d' ? { kind: 'relative', key } : null;
}

/** What the address says of a range: nothing for the default (the last 24 hours) and for a custom range. */
export function rangeParam(range: LogRange): string | null {
  return range.kind === 'relative' && range.key !== '24h' ? range.key : null;
}

/** The range "View in Insights" carries: Insights reads 1h, 7d and 30d (24 hours is its default; no 15 minutes). */
export function insightsRangeParam(range: LogRange): string | null {
  return range.kind === 'relative' && (range.key === '1h' || range.key === '7d' || range.key === '30d') ? range.key : null;
}

/**
 * Why Send to SIEM can't use a destination (the core answers 409 for these, `audit.AuditExports.send`), or null:
 * paused, changed outside GateRook, or one of the demo world's (shown, never called).
 */
export function unsendable(d: Pick<AuditExportDestination, 'enabled' | 'verified' | 'demo'>): string | null {
  if (d.demo === true) return 'demo, never called';
  if (!d.enabled) return 'paused';
  if (d.verified === false) return 'changed outside GateRook';
  return null;
}

/** The relative range one step wider than this one ("Show the last 7 days" after the last 24 hours). */
export function widerRange(range: LogRange): LogRange | null {
  if (range.kind !== 'relative') return null;
  const i = RANGES.findIndex((r) => r.key === range.key);
  return i >= 0 && i < RANGES.length - 1 ? { kind: 'relative', key: RANGES[i + 1].key } : null;
}

// ── Export and saved views ─────────────────────────────────────────────────────────────────────────────────────────

/** The Export menu's note (kit `#ac-export-note`). */
export function exportNote(n: number | null, desktop: boolean): string {
  const what = n == null ? 'the calls' : `the ${calls(n)}`;
  const base = `Exports ${what} that match the filters and range below. Masked values stay masked.`;
  return desktop ? base : `${base} Saving a file needs the GateRook desktop app.`;
}

export function exportedToast(r: { readonly path: string; readonly rows: number; readonly format: string }): { readonly title: string; readonly body: string } {
  return { title: `${calls(r.rows)} exported`, body: `${r.path}. Masked values stay masked.` };
}

/**
 * The views offered until the person saves one of their own (kit `#ac-views`): starting points, written to the
 * person's list (`activity.saved-views`) together with the first view they save.
 */
export const PRESET_VIEWS: readonly SavedView[] = [
  { name: 'Without log reads', q: '-tool:logs_*', view: 'events' },
  { name: 'Denied on prod', q: 'decision:denied env:prod', view: 'events' },
  { name: 'Waiting for approval', q: 'decision:pending', view: 'events' },
  { name: 'Slow calls', q: 'duration:>1s', view: 'events' },
];

export function shownViews(stored: readonly SavedView[] | null | undefined): readonly SavedView[] {
  return stored && stored.length ? stored : PRESET_VIEWS;
}

/** The list after saving a view: a name that exists is replaced in place, a new one goes last. */
export function withView(shown: readonly SavedView[], view: SavedView): SavedView[] {
  const i = shown.findIndex((v) => v.name.toLowerCase() === view.name.toLowerCase());
  return i >= 0 ? shown.map((v, j) => (j === i ? view : v)) : [...shown, view];
}

/** The custom range dialog's sentence about how long calls are kept (kit `#ac-custom-desc`). */
export function retentionNote(years: number | null | undefined): string | null {
  if (!years) return null;
  const kept = `${years} ${years === 1 ? 'year' : 'years'}`;
  return years === 1
    ? `The audit log keeps calls for ${kept}, the default retention in Settings › Audit and retention.`
    : `The audit log keeps calls for ${kept}, as set in Settings › Audit and retention.`;
}

// ── Traces ─────────────────────────────────────────────────────────────────────────────────────────────────────────

/** The tasks of a range: those with a call inside it (a task that started before the range still shows). */
export function tracesInRange(traces: readonly TraceRow[], from: number, to: number | null): TraceRow[] {
  return traces.filter((t) => Date.parse(t.endedAt) >= from && (to == null || Date.parse(t.startedAt) < to));
}

/** "Newest 4 of 61 tasks in the last 24 hours", "All 3 tasks in the last hour", "2 tasks match in the last 24 hours". */
export function tracesPagerText(shown: number, total: number, filtered: boolean, words: string): string {
  const tasks = (n: number) => `${formatCount(n)} ${n === 1 ? 'task' : 'tasks'}`;
  if (filtered) return `${tasks(total)} ${total === 1 ? 'matches' : 'match'} ${words}`;
  if (shown >= total) return `${total === 1 ? 'The only task' : `All ${tasks(total)}`} ${words}`;
  return `Newest ${formatCount(shown)} of ${tasks(total)} ${words}`;
}

/** The drawer's description (kit: "Call 3 of 5 in trace c7e19d"; a call outside a task: "Not part of a traced task"). */
export function drawerDescription(d: EventDetail, trace: TraceDetail | null): string {
  if (!trace) return 'Not part of a traced task';
  const short = trace.trace.traceId.slice(0, 6);
  const i = trace.calls.findIndex((c) => c.callId === d.event.callId);
  const n = Math.max(trace.trace.calls, trace.calls.length);
  return i >= 0 ? `Call ${i + 1} of ${formatCount(n)} in trace ${short}` : `Call in trace ${short}`;
}

// ── Setup changes ──────────────────────────────────────────────────────────────────────────────────────────────────

/** One side of a change: a tool mode, a status, or text the core wrote. */
export type ChangeValue =
  | { readonly kind: 'mode'; readonly cls: string; readonly icon: string; readonly text: string }
  | { readonly kind: 'status'; readonly tone: Look['tone']; readonly text: string }
  | { readonly kind: 'text'; readonly text: string };

export interface ChangeRow {
  readonly id: number;
  readonly at: string;
  /** What changed: its name (a link where the app has a page for it) and a caption. */
  readonly what: {
    readonly text: string;
    readonly mono: boolean;
    readonly route: readonly string[] | null;
    readonly queryParams: Readonly<Record<string, string>> | null;
    readonly caption: string | null;
  };
  readonly from: ChangeValue | null;
  readonly to: ChangeValue | null;
  readonly by: string;
  readonly reason: string | null;
}

const MODE_VALUES: Record<string, { readonly cls: string; readonly icon: string }> = {
  Off: { cls: 'gw-mode--off', icon: 'circle-off' },
  'Read only': { cls: 'gw-mode--read', icon: 'eye' },
  Approval: { cls: 'gw-mode--approval', icon: 'user-check' },
};

const STATUS_VALUES: Record<string, Look['tone']> = {
  On: 'success',
  Off: 'neutral',
  Active: 'success',
  Revoked: 'danger',
  Expired: 'neutral',
  Connected: 'success',
  'Not connected': 'neutral',
  Disconnected: 'neutral',
};

/** An instant the core wrote into a change ("expires 2026-11-01T18:54:55.550633007Z") in local time. */
export function localInstants(text: string): string {
  return text.replace(/\b\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?(?:Z|[+-]\d{2}:\d{2})/g, (iso) => {
    const t = Date.parse(iso);
    return Number.isNaN(t) ? iso : absoluteTime(t, false).slice(0, 16);
  });
}

function changeValue(what: string, value: string | null): ChangeValue | null {
  if (value == null || value === '') return null;
  const text = localInstants(value);
  if (what === 'mode' && MODE_VALUES[text]) return { kind: 'mode', ...MODE_VALUES[text], text };
  if (STATUS_VALUES[text] && what !== 'mode') return { kind: 'status', tone: STATUS_VALUES[text], text };
  return { kind: 'text', text };
}

/**
 * A Setup change as the kit's table shows it (Time | What | Change | By | Reason): what changed links to where it
 * lives (the endpoint's tools, its tokens, the connection, Clients, Secrets, Policies, Settings › Tunnel), tool modes
 * read as `gw-mode`, on / off and access states as `gw-status`, the rest is the core's own words.
 */
export function changeRow(c: SetupChange): ChangeRow {
  return { id: c.id, at: c.at, what: changeWhat(c), from: changeValue(c.what, c.from), to: changeValue(c.what, c.to), by: c.by || 'You', reason: c.reason };
}

function changeWhat(c: SetupChange): ChangeRow['what'] {
  const plain = (text: string, route: readonly string[] | null = null, queryParams: Record<string, string> | null = null) => ({ text, mono: false, route, queryParams, caption: null });
  const ep = c.endpoint;
  switch (c.what) {
    case 'mode': {
      // "http_get on billing-api, endpoint dev" · "describe_connection, endpoint dev"
      const m = /^(\S+?)(,| on )(.*)$/.exec(c.subject);
      const tool = m ? m[1] : c.subject;
      const caption = m ? `${m[2] === ',' ? '' : 'on '}${m[3]}`.trim() : null;
      return { text: tool, mono: true, route: ep ? ['/endpoints', ep] : null, queryParams: ep ? { tab: 'tools' } : null, caption };
    }
    case 'tunnel':
      return { text: 'Tunnel', mono: false, route: ['/settings', 'tunnel'], queryParams: null, caption: c.subject && c.subject !== 'Tunnel' ? c.subject.replace(/^Tunnel\s*·?\s*/, '') : null };
    case 'token':
    case 'grant': {
      // "Token old-claude for dev" · "Grant codex-laptop (codex) for dev"
      const m = /^(?:Token|Grant) (\S+)(?: \(([^)]+)\))? for (\S+)$/.exec(c.subject);
      if (!m) return plain(c.subject, ep ? ['/endpoints', ep] : null, ep ? { tab: 'tokens' } : null);
      const kind = c.what === 'token' ? 'token' : `access of ${clientName(m[2] ?? '')}`;
      return { text: m[1], mono: true, route: ['/endpoints', m[3]], queryParams: { tab: 'tokens' }, caption: `${kind}, endpoint ${m[3]}` };
    }
    case 'client': {
      // "Codex → dev"
      const m = /^(.+?) → (\S+)$/.exec(c.subject);
      return { text: m ? m[1] : c.subject, mono: false, route: ['/clients'], queryParams: null, caption: m ? `endpoint ${m[2]}` : null };
    }
    case 'connection': {
      const name = c.connection;
      if (!name) return plain(c.subject, ['/connections']);
      const caption = c.subject.replace(/^Connection /, '').replace(name, '').replace(/\s+/g, ' ').trim();
      return { text: name, mono: true, route: ['/connections', name], queryParams: null, caption: caption || null };
    }
    case 'settings': {
      if (ep && c.subject.startsWith(`Endpoint ${ep}`)) {
        const caption = c.subject.slice(`Endpoint ${ep}`.length).replace(/^\s*·\s*/, '').trim();
        return { text: ep, mono: true, route: ['/endpoints', ep], queryParams: null, caption: caption ? `endpoint, ${caption}` : 'endpoint' };
      }
      return plain(c.subject);
    }
    case 'risk-warning':
      return plain(c.subject, ep ? ['/endpoints', ep] : null);
    case 'secret':
      return plain(c.subject, ['/secrets']);
    case 'policy':
      return plain(c.subject, c.env ? ['/policies', c.env] : ['/policies']);
    case 'plugin':
    case 'mcp-server':
      return plain(c.subject, ['/connectors']);
    case 'host-key':
      return plain(c.subject, c.connection ? ['/connections', c.connection] : null);
    case 'oauth':
      return plain(c.subject, ['/clients']);
    default:
      return plain(c.subject);
  }
}

/** Setup changes of the last day (the tab's count, kit "Setup changes 4"). */
export function changesSince(changes: readonly SetupChange[], since: number): SetupChange[] {
  return changes.filter((c) => Date.parse(c.at) >= since);
}

/** "All 4 changes in the last 24 hours"; after Load older changes: "46 changes, newest first". */
export function changesPagerText(shown: number, inDay: number, older: boolean): string {
  const changes = (n: number) => `${formatCount(n)} ${n === 1 ? 'change' : 'changes'}`;
  if (older) return `${changes(shown)}, newest first`;
  return inDay === 1 ? 'The only change in the last 24 hours' : `All ${changes(inDay)} in the last 24 hours`;
}

// ── Revoke the access that made a call ─────────────────────────────────────────────────────────────────────────────

/** The token or grant behind a call, while it can still be revoked (kit drawer A: "Revoke codex-laptop"). */
export function revokeTarget(e: Pick<ActivityEvent, 'tokenLabel'>, tokens: readonly TokenRow[]): TokenRow | null {
  if (!e.tokenLabel) return null;
  return tokens.find((t) => t.name === e.tokenLabel && t.state === 'active') ?? null;
}

export interface RevokeWords {
  readonly title: string;
  /** The description around the endpoint's name, which the kit sets in mono: `before` + endpoint + `after`. */
  readonly before: string;
  readonly endpoint: string;
  readonly after: string;
  /** Access: the name (mono) and what it is. */
  readonly accessRest: string;
  /** Used by: who, through what (mono when it is a command), on the endpoint. */
  readonly usedBy: string;
  readonly through: { readonly text: string; readonly mono: boolean } | null;
  readonly button: string;
  readonly toastTitle: string;
  readonly toastBody: string;
}

/** The whole description as one sentence (tests, the screen reader's view of the dialog). */
export function revokeDescription(w: RevokeWords): string {
  return `${w.before}${w.endpoint}${w.after}`;
}

/**
 * The confirmation (kit `#ac-revoke`, the same words as endpoint-detail's): a grant is a client's access through the
 * bridge or the headers helper, whose 15-minute tokens are issued on the fly and never stored; a named token is one
 * pasted into a client by hand.
 */
export function revokeWords(t: TokenRow, endpoint: string, expiresIn: string | null): RevokeWords {
  const button = `Revoke ${t.name}`;
  if (t.kind === 'grant') {
    const who = clientName(t.client ?? 'the client');
    const via = t.client === 'codex' ? 'its headers helper' : t.client === 'claude-desktop' ? 'its bridge' : 'it';
    const through =
      t.client === 'codex' ? { text: 'gw-bridge headers', mono: true } : t.client === 'claude-desktop' ? { text: 'the bridge extension (.mcpb)', mono: false } : null;
    return {
      title: `${button}?`,
      before: `${who}'s current token stops working at once and ${via} gets no new one, so ${who} loses access to endpoint `,
      endpoint,
      after: ` on its next call. Connect ${who} again under Clients to restore it. Its past calls stay in this log.`,
      accessRest: ' · 15-minute tokens issued on the fly, none stored',
      usedBy: who,
      through,
      button,
      toastTitle: `${t.name} revoked`,
      toastBody: `${who} loses access on its next call.`,
    };
  }
  return {
    title: `${button}?`,
    before: `Token ${t.name} stops working at once, so whatever uses it loses access to endpoint `,
    endpoint,
    after: ' on its next call. Issue a new token on the endpoint to restore it. Its past calls stay in this log.',
    accessRest: `${t.prefix ? ` · ${t.prefix}…` : ''}${expiresIn ? `, expires ${expiresIn}` : ''}`,
    usedBy: 'Clients that send it',
    through: null,
    button,
    toastTitle: `${t.name} revoked`,
    toastBody: 'Whatever uses it loses access on its next call.',
  };
}

/** The toast's second sentence when the revoke denied requests of that access that were waiting. */
export function deniedWaiting(n: number): string {
  return n ? ` ${n} waiting ${n === 1 ? 'request was' : 'requests were'} denied.` : '';
}

// ── Facet rows' environments ───────────────────────────────────────────────────────────────────────────────────────

/** Each connection's environment from the connections list, for the Connection facet's `gw-env` marks. */
export function envsOf(items: readonly { readonly name: string; readonly env: Env }[]): Record<string, Env> {
  const out: Record<string, Env> = {};
  for (const c of items) out[c.name] = c.env;
  return out;
}

/** The decision badge of a row (BLUEPRINT §7). */
export const decisionOf = decisionLook;
