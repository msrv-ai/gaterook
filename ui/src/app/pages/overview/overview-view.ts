import { ActivityEvent, Histogram } from '../../core/api/activity.api';
import { ApprovalItem } from '../../core/api/approvals.api';
import { HealthRow, OverviewStats, SetupNode } from '../../core/api/overview.api';
import { clockTime, duration, elapsed, everyInterval, formatCount } from '../../core/time/time-format';
import { clientName } from '../../kit/elements/shared/client-mark';
import { healthLook } from '../../kit/elements/shared/dictionaries';
import { TextPart, isLiteralWord, literalParts } from '../../kit/elements/shared/literals';

/**
 * What Overview shows, computed from the admin API (ARCHITECTURE.md §6 overview, approvals, activity): pure functions
 * of the answers and "now", so the tests check them against the kit's wording (design/pages/overview.html) without a
 * clock or a core. The page binds them; nothing here invents a number the core didn't give.
 */

const HOUR = 3_600_000;
const DAY = 24 * HOUR;

/** The gateway's own budget per call (ARCHITECTURE.md M1.5: p95 of the overhead ≤ 50 ms; BLUEPRINT §9 "target ≤ 50 ms"). */
export const OVERHEAD_TARGET_MS = 50;

/** How many decided calls Recent activity lists (kit: "The last 8 calls with a decision"). */
export const RECENT_CALLS = 8;

// ── KPI tiles ────────────────────────────────────────────────────────────────────────────────────────────────────

/** `gw-stat__delta`: direction (icon and modifier), what it means (`data-sentiment`, none when flat) and the words. */
export interface StatDelta {
  readonly dir: 'up' | 'down' | 'flat';
  readonly sentiment: 'good' | 'bad' | 'neutral' | null;
  readonly icon: 'trending-up' | 'trending-down' | 'minus';
  readonly text: string;
}

/** A `gw-sparkline` in the tile's `__chart` slot: the polyline, its tone and the trend in words (`aria-label`). */
export interface StatChart {
  readonly points: string;
  readonly tone: 'brand' | 'warning' | 'danger';
  readonly label: string;
}

/** A run of `gw-stat__meta`: text, or a link into another page ("View denials"). */
export interface MetaPart {
  readonly text: string;
  readonly route?: string;
  readonly query?: { readonly [key: string]: string };
}

/** One `gw-stat` of the "Last 24 hours" row. */
export interface StatTile {
  readonly id: 'calls' | 'waiting' | 'denied' | 'overhead';
  readonly label: string;
  readonly value: string;
  readonly unit: string | null;
  readonly chart: StatChart | null;
  readonly delta: StatDelta | null;
  readonly meta: readonly MetaPart[];
}

/**
 * The approval requests of the last 24 hours as the page loaded them: the history (newest first, decided or expired),
 * the requests waiting now, and the core's counts of the day by status.
 */
export interface ApprovalsDay {
  readonly history: readonly ApprovalItem[];
  readonly pending: readonly ApprovalItem[];
  readonly counts: { readonly [status: string]: number };
}

/** Everything the tiles are made of; the optional parts degrade a tile to what is known. */
export interface StatsInput {
  readonly stats: OverviewStats;
  /** `GET /api/activity/histogram` of the last 24 hours, one bar per hour (decisions per hour). */
  readonly histogram: Histogram | null;
  readonly approvals: ApprovalsDay | null;
  /** Now on the core's clock (epoch ms). */
  readonly now: number;
}

/** "+5", "−4 ms", "+1,284": a change with its sign, a typographic minus and the kit's thousands. */
export function signed(n: number, unit = ''): string {
  const text = `${n < 0 ? '−' : '+'}${formatCount(Math.abs(n))}`;
  return unit ? `${text} ${unit}` : text;
}

/**
 * A change against `vs` ("yesterday", "an hour ago"): flat says "No change vs …" without a sentiment (kit
 * `__delta--flat`); otherwise the direction's icon and what the change means — more denials is bad, a lower p95 good.
 */
function change(n: number, text: string, vs: string, up: StatDelta['sentiment'], down: StatDelta['sentiment']): StatDelta {
  if (n === 0) return { dir: 'flat', sentiment: null, icon: 'minus', text: `No change vs ${vs}` };
  return n > 0
    ? { dir: 'up', sentiment: up, icon: 'trending-up', text: `${text} vs ${vs}` }
    : { dir: 'down', sentiment: down, icon: 'trending-down', text: `${text} vs ${vs}` };
}

/**
 * Calls against the day before (kit "+12% vs yesterday"): a share when the day before had calls, the number of calls
 * when it had none (a share of nothing would be infinite), and the number again when the share rounds to 0 %.
 */
export function callsDelta(calls: number, delta: number): StatDelta {
  const before = calls - delta;
  const pct = before > 0 ? Math.round((delta / before) * 100) : 0;
  const text = pct !== 0 ? `${pct > 0 ? '+' : '−'}${formatCount(Math.abs(pct))}%` : signed(delta);
  return change(delta, text, 'yesterday', 'neutral', 'neutral');
}

/**
 * The points of a `gw-sparkline` (viewBox 0 0 230 40, as the kit's tiles): first value at the left edge, last at the
 * right, the highest at y 4 and zero on the baseline (y 38).
 */
export function sparkPoints(values: readonly number[]): string {
  const max = Math.max(0, ...values);
  const step = values.length > 1 ? 230 / (values.length - 1) : 0;
  const round = (n: number) => String(Math.round(n * 10) / 10);
  return values.map((v, i) => `${round(i * step)},${round(38 - (max ? v / max : 0) * 34)}`).join(' ');
}

/**
 * As {@link sparkPoints} for a series with gaps (an hour without completed calls has no p95): the hours that have a
 * value keep their place on the x axis, the highest at y 4; empty without any value.
 */
export function sparseSparkPoints(values: readonly (number | null)[]): string {
  const known = values.filter((v): v is number => v !== null);
  const max = Math.max(0, ...known);
  const step = values.length > 1 ? 230 / (values.length - 1) : 0;
  const round = (n: number) => String(Math.round(n * 10) / 10);
  return values
    .map((v, i) => (v === null ? null : `${round(i * step)},${round(38 - (max ? v / max : 0) * 34)}`))
    .filter((p): p is string => p !== null)
    .join(' ');
}

/** The start of the clock hour `back` hours before the current one ("11:00" in the labels). */
function hourStart(now: number, back: number): number {
  return Math.floor(now / HOUR) * HOUR - back * HOUR;
}

/** The peak of hourly values and when it was ("peak of 166 at 11:00"); null without any. */
function peak(values: readonly number[], startOf: (i: number) => number): { readonly max: number; readonly at: string } | null {
  let best = -1;
  values.forEach((v, i) => {
    if (v > 0 && (best < 0 || v > values[best])) best = i;
  });
  return best < 0 ? null : { max: values[best], at: clockTime(startOf(best), false) };
}

/** Decisions of the day summed over the histogram's bars: `{allowed: 1201, approved: 46, …}`. */
export function decisionTotals(h: Histogram | null): { readonly [decision: string]: number } {
  const totals: Record<string, number> = {};
  for (const b of h?.buckets ?? []) for (const [d, n] of Object.entries(b.decisions)) totals[d] = (totals[d] ?? 0) + n;
  return totals;
}

/** When a request stopped waiting: its decision, else (expired, cancelled by the core) its auto-deny time. */
function resolvedAt(a: ApprovalItem): number {
  const decided = a.decidedAt ? Date.parse(a.decidedAt) : NaN;
  return Number.isNaN(decided) ? Date.parse(a.expiresAt) : decided;
}

/** A request's wait as an interval of epoch ms; one still waiting has no end. */
export interface WaitSpan {
  readonly from: number;
  readonly to: number;
}

export function waitSpans(day: ApprovalsDay): WaitSpan[] {
  const spans: WaitSpan[] = [];
  for (const a of day.pending) spans.push({ from: Date.parse(a.requestedAt), to: Number.POSITIVE_INFINITY });
  for (const a of day.history) {
    const from = Date.parse(a.requestedAt);
    spans.push({ from, to: Math.max(from, resolvedAt(a)) });
  }
  return spans.filter((s) => !Number.isNaN(s.from) && !Number.isNaN(s.to));
}

/** How many requests waited at the instant `t`. */
export function waitingAt(spans: readonly WaitSpan[], t: number): number {
  return spans.filter((s) => s.from <= t && s.to > t).length;
}

/**
 * The most requests that waited at the same time in each of the last `hours` clock hours, oldest first, the current
 * (partial) hour last — the kit's "Requests waiting per hour: never more than 3 at a time". A sweep over the waits
 * clipped to each hour: an end and a start at the same instant don't overlap.
 */
export function waitingPerHour(spans: readonly WaitSpan[], now: number, hours = 24): number[] {
  const out: number[] = [];
  for (let i = 0; i < hours; i++) {
    const start = hourStart(now, hours - 1 - i);
    const end = Math.min(start + HOUR, now + 1);
    const edges: [number, number][] = [];
    for (const s of spans) {
      if (s.from < end && s.to > start) {
        edges.push([Math.max(s.from, start), 1], [Math.min(s.to, end), -1]);
      }
    }
    edges.sort((a, b) => a[0] - b[0] || a[1] - b[1]);
    let open = 0;
    let max = 0;
    for (const [, step] of edges) {
      open += step;
      max = Math.max(max, open);
    }
    out.push(max);
  }
  return out;
}

/**
 * The median time a person took to decide, over the requests of the last 24 hours someone decided (approved, then
 * run or failed, or rejected); expired ones and the core's own refusals (a revoked token, a deleted connection) are
 * no one's wait.
 */
export function medianWaitMs(day: ApprovalsDay, now: number): number | null {
  const since = now - DAY;
  const waits = day.history
    .filter((a) => a.decidedAt && a.decidedVia !== 'core' && ['running', 'completed', 'failed', 'rejected'].includes(a.status))
    .filter((a) => Date.parse(a.requestedAt) >= since)
    .map((a) => Date.parse(a.decidedAt!) - Date.parse(a.requestedAt))
    .filter((ms) => ms >= 0)
    .sort((a, b) => a - b);
  if (!waits.length) return null;
  const mid = Math.floor(waits.length / 2);
  return waits.length % 2 ? waits[mid] : Math.round((waits[mid - 1] + waits[mid]) / 2);
}

const plural = (n: number, one: string, many = `${one}s`) => `${formatCount(n)} ${n === 1 ? one : many}`;

/** The four tiles of "Last 24 hours": calls, waiting for approval, denied by policy, the gateway's p95. */
export function statTiles(input: StatsInput): StatTile[] {
  const { stats, histogram, approvals, now } = input;
  const totals = decisionTotals(histogram);
  const bars = histogram?.buckets.slice(-24) ?? [];

  // Calls: per clock hour from the overview's own spark (the current hour last).
  const callsPeak = peak(stats.spark, (i) => hourStart(now, stats.spark.length - 1 - i));
  const calls: StatTile = {
    id: 'calls',
    label: 'Calls',
    value: formatCount(stats.calls24h),
    unit: null,
    chart: callsPeak
      ? { points: sparkPoints(stats.spark), tone: 'brand', label: `Calls per hour: peak of ${formatCount(callsPeak.max)} at ${callsPeak.at}` }
      : null,
    delta: callsDelta(stats.calls24h, stats.deltas.calls24h),
    meta:
      stats.calls24h === 0
        ? [{ text: 'No calls in the last 24 hours' }]
        : histogram
          ? [{ text: `${formatCount(totals['allowed'] ?? 0)} allowed · ${formatCount(totals['approved'] ?? 0)} approved` }]
          : [],
  };

  // Waiting: the queue now; over the day and an hour ago from the requests' own times.
  let waitingChart: StatChart | null = null;
  let waitingDelta: StatDelta | null = null;
  const waitingMeta: MetaPart[] = [];
  if (approvals) {
    const spans = waitSpans(approvals);
    const perHour = waitingPerHour(spans, now);
    const most = Math.max(0, ...perHour);
    if (most > 0) {
      waitingChart = {
        points: sparkPoints(perHour),
        tone: 'warning',
        label: `Requests waiting per hour: never more than ${formatCount(most)} at a time`,
      };
    }
    const d = stats.pending - waitingAt(spans, now - HOUR);
    waitingDelta = change(d, signed(d), 'an hour ago', 'neutral', 'neutral');
    const requests = approvals.pending.length + approvals.history.filter((a) => Date.parse(a.requestedAt) >= now - DAY).length;
    if (requests === 0 && stats.pending === 0) {
      waitingMeta.push({ text: 'No requests in the last 24 hours' });
    } else {
      const median = medianWaitMs(approvals, now);
      const expired = approvals.counts['expired'] ?? 0;
      const parts = [median !== null ? `Median wait ${elapsed(median)}` : null, expired ? `${formatCount(expired)} expired` : 'none expired'];
      waitingMeta.push({ text: parts.filter((p): p is string => !!p).join(' · ') });
    }
  }
  const waiting: StatTile = {
    id: 'waiting',
    label: 'Waiting for approval',
    value: formatCount(stats.pending),
    unit: null,
    chart: waitingChart,
    delta: waitingDelta,
    meta: waitingMeta,
  };

  // Denied by policy: the day against the one before; per hour from the histogram.
  const denials = bars.map((b) => b.decisions['denied'] ?? 0);
  const deniedPeak = peak(denials, (i) => Date.parse(bars[i].from));
  const rejected = totals['rejected'] ?? 0;
  const deniedMeta: MetaPart[] = [];
  if (histogram) deniedMeta.push({ text: rejected ? `Also ${formatCount(rejected)} rejected on review` : 'None rejected on review' });
  if (stats.denied24h > 0) {
    if (deniedMeta.length) deniedMeta.push({ text: ' · ' });
    deniedMeta.push({ text: 'View denials', route: '/activity', query: { q: 'decision:denied' } });
  }
  const denied: StatTile = {
    id: 'denied',
    label: 'Denied by policy',
    value: formatCount(stats.denied24h),
    unit: null,
    chart: deniedPeak
      ? { points: sparkPoints(denials), tone: 'danger', label: `Denials per hour: peak of ${formatCount(deniedPeak.max)} at ${deniedPeak.at}` }
      : null,
    delta: change(stats.deltas.denied24h, signed(stats.deltas.denied24h), 'yesterday', 'bad', 'good'),
    meta: deniedMeta,
  };

  // The gateway's own time per hour (step API's `gatewayP95Spark`); a core without the series gives the number only.
  const p95 = stats.gatewayP95Ms;
  const p95Delta = stats.deltas.gatewayP95Ms;
  const overhead: StatTile = {
    id: 'overhead',
    label: 'Gateway overhead p95',
    value: p95 === null ? 'None' : formatCount(p95),
    unit: p95 === null ? null : 'ms',
    chart: overheadChart(stats.gatewayP95Spark ?? null, now),
    delta: p95 === null || p95Delta === null ? null : change(p95Delta, signed(p95Delta, 'ms'), 'yesterday', 'bad', 'good'),
    meta: [
      {
        text:
          p95 === null
            ? 'No completed calls in the last 24 hours'
            : `Target ≤ ${OVERHEAD_TARGET_MS} ms · ${p95 <= OVERHEAD_TARGET_MS ? 'within target' : 'over target'}`,
      },
    ],
  };

  return [calls, waiting, denied, overhead];
}

/**
 * The overhead tile's sparkline (kit: "Gateway overhead p95 per hour: steady, always under the 50 ms target"): the
 * hours with completed calls, and in words whether any hour went over the target and which was the worst.
 */
export function overheadChart(perHour: readonly (number | null)[] | null, now: number): StatChart | null {
  if (!perHour || !perHour.some((v) => v !== null)) return null;
  const worst = peak(perHour.map((v) => v ?? 0), (i) => hourStart(now, perHour.length - 1 - i));
  const over = perHour.filter((v): v is number => v !== null && v > OVERHEAD_TARGET_MS).length;
  const label = over
    ? `Gateway overhead p95 per hour: over the ${OVERHEAD_TARGET_MS} ms target in ${plural(over, 'hour')}, worst ${formatCount(worst!.max)} ms at ${worst!.at}`
    : `Gateway overhead p95 per hour: always under the ${OVERHEAD_TARGET_MS} ms target`;
  return { points: sparseSparkPoints(perHour), tone: over ? 'warning' : 'brand', label };
}

// ── Setup map ────────────────────────────────────────────────────────────────────────────────────────────────────

/** The master key's problem codes (`/api/master-key` `problems[]`) in words, for the setup map's Secrets line. */
const KEY_PROBLEMS: Record<string, string> = {
  perms: 'file readable by others',
  'synced-folder': 'in a synced folder',
  'backup-needed': 'not backed up',
  'no-os-protection': 'no OS protection',
  'key-missing': 'not found',
  'key-invalid': "can't be read",
};

/**
 * A node's status line as the core writes it, with the master key's problem codes in words ("Master key:
 * no-os-protection, backup-needed" → "Master key: no OS protection, not backed up") and a count of one in the
 * singular ("1 named tokens"). Anything else is shown as the core said it.
 */
export function setupStatus(node: SetupNode): string {
  const key = /^Master key: (.+)$/.exec(node.status);
  if (key) {
    const codes = key[1].split(/,\s*/);
    if (codes.every((c) => c in KEY_PROBLEMS)) return `Master key: ${codes.map((c) => KEY_PROBLEMS[c]).join(', ')}`;
  }
  return node.status.replace(/^1 named tokens$/, '1 named token');
}

// ── Connection health ────────────────────────────────────────────────────────────────────────────────────────────

/** A connection is a problem when its last check failed or was slow (BLUEPRINT §7: danger, warning). */
export function isProblem(row: Pick<HealthRow, 'state'>): boolean {
  const tone = healthLook(row.state).tone;
  return tone === 'danger' || tone === 'warning';
}

/** "Healthy · 12 ms", "Slow · 1.8 s", "Unreachable", "Not checked yet". */
export function healthText(row: HealthRow): string {
  const s = (row.state ?? '').toLowerCase();
  return healthLook(row.state, s === 'healthy' || s === 'degraded' ? row.latencyMs : null).text;
}

/** The rows in the kit's order: problems first (unreachable before slow), the rest folded behind a disclosure. */
export interface HealthGroups {
  readonly problems: readonly HealthRow[];
  readonly rest: readonly HealthRow[];
  /** The disclosure's words: "10 healthy connections", "12 connections not checked yet", "9 healthy · 1 not checked yet". */
  readonly restLabel: string;
}

export function healthGroups(rows: readonly HealthRow[]): HealthGroups {
  const rank = (r: HealthRow) => (healthLook(r.state).tone === 'danger' ? 0 : 1);
  const problems = rows.filter(isProblem).sort((a, b) => rank(a) - rank(b));
  const rest = rows.filter((r) => !isProblem(r));
  const count = (tone: string) => rest.filter((r) => healthLook(r.state).tone === tone).length;
  const kinds = [
    { n: count('success'), alone: (n: number) => `${plural(n, 'healthy connection')}`, part: 'healthy' },
    { n: count('neutral'), alone: (n: number) => `${plural(n, 'connection')} not checked yet`, part: 'not checked yet' },
    { n: count('info'), alone: (n: number) => `${plural(n, 'connection')} being checked`, part: 'being checked' },
  ].filter((k) => k.n > 0);
  const restLabel =
    kinds.length === 1 ? kinds[0].alone(kinds[0].n) : kinds.map((k) => `${formatCount(k.n)} ${k.part}`).join(' · ');
  return { problems, rest, restLabel };
}

/**
 * What the section says when no connection is a problem: "No problems" only for connections that were checked; a
 * gateway that hasn't checked them yet (they are checked shortly after it starts, then periodically) says so.
 */
export function healthCalm(rows: readonly HealthRow[]): {
  readonly checked: boolean;
  readonly title: string;
  readonly text: string;
} {
  const healthy = rows.filter((r) => healthLook(r.state).tone === 'success').length;
  const unchecked = rows.length - healthy;
  if (!healthy) {
    return { checked: false, title: 'Not checked yet', text: 'The gateway checks each connection in the background, shortly after it starts.' };
  }
  return {
    checked: true,
    title: 'No problems',
    text: unchecked
      ? `${plural(healthy, 'connection')} answered ${healthy === 1 ? 'its' : 'their'} last check; ${formatCount(unchecked)} ${unchecked === 1 ? "hasn't" : "haven't"} been checked yet.`
      : 'Every connection answered its last check.',
  };
}

/**
 * "12 connections, checked every minute. Problems first." (kit) with the core's interval (step API); a core that
 * doesn't say it gives "12 connections, last checked 2 min ago. Problems first."
 */
export function healthDescription(
  rows: readonly HealthRow[],
  relative: (at: string) => string,
  intervalSeconds: number | null = null,
): string {
  if (!rows.length) {
    return intervalSeconds
      ? `Each connection is checked ${everyInterval(intervalSeconds)}. Problems first.`
      : 'Each connection is checked in the background. Problems first.';
  }
  if (intervalSeconds) return `${plural(rows.length, 'connection')}, checked ${everyInterval(intervalSeconds)}. Problems first.`;
  const last = rows
    .map((r) => r.checkedAt)
    .filter((t): t is string => !!t)
    .sort()
    .at(-1);
  return `${plural(rows.length, 'connection')}${last ? `, last checked ${relative(last)}` : ''}. Problems first.`;
}

/** The danger alert above the table: what can't be reached, what to do, and what that breaks. */
export interface HealthAlert {
  readonly title: readonly TextPart[];
  readonly body: readonly TextPart[];
  /** The unreachable connections Test again tries. */
  readonly names: readonly string[];
}

/**
 * Prose with its identifiers in mono, as the kit sets the host in "Can't reach billing.stage.acme.dev.": each word
 * that can only be an identifier (a host, an address, a path) is a literal, the punctuation after it stays prose.
 * The parts concatenate to the text.
 */
export function markLiterals(text: string, names: readonly string[] = []): TextPart[] {
  const out: TextPart[] = [];
  const push = (t: string, code: boolean): void => {
    const prev = out.at(-1);
    if (prev && !prev.code && !code) out[out.length - 1] = { text: prev.text + t, code };
    else if (t) out.push({ text: t, code });
  };
  for (const part of literalParts(text, names)) {
    if (part.code) {
      push(part.text, true);
      continue;
    }
    for (const token of part.text.split(/(\s+)/)) {
      const m = /^(.*?)([.,;:!?)]*)$/s.exec(token)!;
      if (m[1] && isLiteralWord(m[1])) {
        push(m[1], true);
        push(m[2], false);
      } else {
        push(token, false);
      }
    }
  }
  return out;
}

/** "Can't reach billing.stage.acme.dev." + "Check the VPN or the host, then test again.": the core's text, split once. */
function firstSentence(text: string): [string, string] {
  const m = /^(.+?[.!?])\s+(\S.*)$/s.exec(text.trim());
  return m ? [m[1], m[2]] : [text.trim(), ''];
}

/** "app-logs, aws-stage, billing-api and 9 more". */
function someNames(names: readonly string[], shown = 3): string {
  if (names.length <= shown) return names.length > 1 ? `${names.slice(0, -1).join(', ')} and ${names.at(-1)}` : (names[0] ?? '');
  return `${names.slice(0, shown).join(', ')} and ${names.length - shown} more`;
}

/**
 * The kit's error-where-it-happens (overview.html "Can't reach billing.stage.acme.dev."): for one unreachable
 * connection the core's message (what happened, then what to do), for several their count and names; and the
 * requests waiting on them, which fail until then.
 */
export function healthAlert(rows: readonly HealthRow[], waiting: readonly ApprovalItem[]): HealthAlert | null {
  const down = rows.filter((r) => healthLook(r.state).tone === 'danger');
  if (!down.length) return null;
  const names = down.map((r) => r.name);
  const blocked = waiting.filter((w) => w.connection && names.includes(w.connection));
  const then =
    blocked.length === 1
      ? ` The waiting ${blocked[0].tool} on ${blocked[0].connection} fails until then.`
      : blocked.length > 1
        ? ` ${blocked.length} waiting requests fail until then.`
        : '';
  const literals = [...names, ...blocked.map((b) => b.tool)];
  const mark = (text: string): TextPart[] => markLiterals(text, literals);
  if (down.length === 1) {
    const message = down[0].message?.trim();
    const [title, rest] = message ? firstSentence(message) : [`${names[0]} is unreachable.`, 'Check the VPN or the host, then test again.'];
    return { title: mark(title), body: mark(`${rest}${then}`.trim()), names };
  }
  return {
    title: [{ text: `${plural(down.length, 'connection')} are unreachable.`, code: false }],
    body: mark(`${someNames(names)}. Check the VPN or the hosts, then test again.${then}`),
    names,
  };
}

/** What one Test again found for a connection. */
export interface RetestResult {
  readonly name: string;
  readonly ok: boolean;
  readonly latencyMs: number | null;
  /** What failed and what to do (the connector's words), when it failed. */
  readonly message: string | null;
}

/** The toast after Test again (kit: "billing-api is still unreachable" + what the host didn't do, in danger). */
export function retestToast(results: readonly RetestResult[]): {
  readonly tone: 'success' | 'warning' | 'danger';
  readonly title: string;
  readonly body: string;
} {
  const failed = results.filter((r) => !r.ok);
  if (results.length === 1) {
    const r = results[0];
    return r.ok
      ? { tone: 'success', title: `${r.name} is reachable again`, body: r.latencyMs !== null ? `It answered in ${duration(r.latencyMs)}.` : 'It answered.' }
      : { tone: 'danger', title: `${r.name} is still unreachable`, body: r.message ?? 'Check the VPN or the host, then test again.' };
  }
  if (!failed.length) return { tone: 'success', title: `${plural(results.length, 'connection')} are reachable again`, body: 'They answered the test.' };
  if (failed.length === results.length) {
    return { tone: 'danger', title: `${plural(results.length, 'connection')} are still unreachable`, body: 'Check the VPN or the hosts, then test again.' };
  }
  return {
    tone: 'warning',
    title: `${formatCount(results.length - failed.length)} of ${plural(results.length, 'connection')} are reachable again`,
    body: `Still unreachable: ${someNames(failed.map((f) => f.name))}.`,
  };
}

// ── Recent activity ──────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The last calls with a decision, newest first (kit: pending calls are not repeated here, they are "Waiting for you").
 */
export function decidedCalls(events: readonly ActivityEvent[], limit = RECENT_CALLS): ActivityEvent[] {
  return events.filter((e) => e.decision !== 'pending').slice(0, limit);
}

/** The client column: "Codex", "Claude Desktop", "Playground"; empty when the call didn't say. */
export function clientLabel(client: string | null): string {
  if (!client) return '';
  if (client === 'playground') return 'Playground';
  if (client === 'other') return 'Other client';
  return clientName(client);
}
