import { Histogram, HistogramBucket } from '../../core/api/activity.api';
import { ClientCard } from '../../core/api/clients.api';
import { ConnectionHealth } from '../../core/api/connections.api';
import {
  DecisionCounts,
  InsightClient,
  InsightDeniedRule,
  InsightErrors,
  InsightLatency,
  InsightSlowDecision,
  InsightUnusual,
  Insights,
  InsightsApprovals,
  InsightsTotals,
} from '../../core/api/insights.api';
import { Env } from '../../core/api/types';
import { clockTime, duration, elapsed, formatCount, shortDate } from '../../core/time/time-format';
import { clientName } from '../../kit/elements/shared/client-mark';
import { Look, healthLook } from '../../kit/elements/shared/dictionaries';
import { SERIES, Series, joinQuery, niceMax, parseQuery, seriesCounts } from '../../kit/elements/log-explorer/log-view';

/**
 * What Insights shows (design/pages/insights.html), computed from `GET /api/insights` (ARCHITECTURE.md §6): pure
 * functions of the answer, the range and "now", so the tests check them against the kit's wording without a clock or
 * a core. Every sentence on the page is written here from the core's numbers and names; nothing is invented.
 */

const HOUR = 3_600_000;
const DAY = 24 * HOUR;

/** The gateway's own budget per call (ARCHITECTURE.md M1.5: p95 of the overhead ≤ 50 ms; BLUEPRINT §9 "target ≤ 50 ms"). */
export const OVERHEAD_TARGET_MS = 50;

/** How many tools Top tools draws before the rest become one "N other tools" bar (kit: six). */
export const TOP_TOOLS = 6;

/** How many connections Latency by connection draws (kit: ten). */
export const LATENCY_ROWS = 10;

// ── Ranges ───────────────────────────────────────────────────────────────────────────────────────────────────────

export type InsightsRangeKey = '1h' | '24h' | '7d' | '30d';

export const RANGE_KEYS: readonly InsightsRangeKey[] = ['1h', '24h', '7d', '30d'];

const RANGE_MS: Record<InsightsRangeKey, number> = { '1h': HOUR, '24h': DAY, '7d': 7 * DAY, '30d': 30 * DAY };

/** The range in a sentence ("in the last 24 hours") and the one before it ("vs previous 24 hours"). */
export interface RangeWords {
  readonly span: string;
  readonly previous: string;
}

export function rangeWords(key: InsightsRangeKey): RangeWords {
  switch (key) {
    case '1h':
      return { span: 'last hour', previous: 'previous hour' };
    case '7d':
      return { span: 'last 7 days', previous: 'previous 7 days' };
    case '30d':
      return { span: 'last 30 days', previous: 'previous 30 days' };
    default:
      return { span: 'last 24 hours', previous: 'previous 24 hours' };
  }
}

export function isRangeKey(v: unknown): v is InsightsRangeKey {
  return typeof v === 'string' && (RANGE_KEYS as readonly string[]).includes(v);
}

/** The instants one read asks for: a relative range ends now (on the core's clock). */
export function rangeQuery(key: InsightsRangeKey, now: number): { readonly from: string; readonly to: string } {
  return { from: new Date(now - RANGE_MS[key]).toISOString(), to: new Date(now).toISOString() };
}

/** The next longer range, for "nothing in this range" (null for the longest). */
export function widerRange(key: InsightsRangeKey): InsightsRangeKey | null {
  const i = RANGE_KEYS.indexOf(key);
  return i >= 0 && i < RANGE_KEYS.length - 1 ? RANGE_KEYS[i + 1] : null;
}

// ── Words ────────────────────────────────────────────────────────────────────────────────────────────────────────

const plural = (n: number, one: string, many = `${one}s`) => `${formatCount(n)} ${n === 1 ? one : many}`;

const NUMBER_WORDS = ['no', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten'];

/** "two connections", "12 connections": small counts in words, as the kit's sentences say them. */
export function countWords(n: number, one: string, many = `${one}s`): string {
  const word = n >= 0 && n < NUMBER_WORDS.length ? NUMBER_WORDS[n] : formatCount(n);
  return `${word} ${n === 1 ? one : many}`;
}

/** "a and b", "a, b and c". */
export function listWords(items: readonly string[]): string {
  if (items.length <= 1) return items[0] ?? '';
  return `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`;
}

/** A share as a reader says it: "two thirds", "half", "almost all" (the nearest of the common fractions). */
export function shareWords(p: number): string {
  if (p >= 0.97) return 'almost all';
  const words: readonly [number, string][] = [
    [1 / 10, 'a tenth'],
    [1 / 5, 'a fifth'],
    [1 / 4, 'a quarter'],
    [1 / 3, 'a third'],
    [1 / 2, 'half'],
    [2 / 3, 'two thirds'],
    [3 / 4, 'three quarters'],
    [9 / 10, 'nine in ten'],
  ];
  let best = words[0];
  for (const w of words) if (Math.abs(w[0] - p) < Math.abs(best[0] - p)) best = w;
  return best[1];
}

/** "4 of 5": a share as the nearest simple fraction (denominators up to 5), "all" and "none" at the ends. */
export function fractionWords(p: number): string {
  if (p >= 0.995) return 'all';
  if (p <= 0.005) return 'none';
  let best = { n: 1, d: 2, err: Number.POSITIVE_INFINITY };
  for (let d = 2; d <= 5; d++) {
    for (let n = 1; n < d; n++) {
      const err = Math.abs(n / d - p);
      if (err < best.err - 1e-9) best = { n, d, err };
    }
  }
  return `${best.n} of ${best.d}`;
}

/** "18%", "<1%": a share of a whole in whole percent, never "0%" for something that happened. */
export function percent(part: number, whole: number): string {
  if (whole <= 0) return '0%';
  const p = Math.round((part / whole) * 100);
  return p === 0 && part > 0 ? '<1%' : `${p}%`;
}

/** "18 ms", "1.8 s", "2.0 s": a latency with one decimal over a second, as the kit writes p95s and timeouts. */
export function latency(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || Number.isNaN(ms)) return '';
  if (ms < 1000) return `${Math.round(ms)} ms`;
  return `${(ms / 1000).toFixed(1)} s`;
}

/** A wait to the second with padded seconds, as the kit's "Slowest decisions" write it: "3 min 05 s", "47 s". */
export function waitText(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || Number.isNaN(seconds) || seconds < 0) return '';
  const s = Math.round(seconds);
  if (s < 60) return `${s} s`;
  if (s < 3600) return `${Math.floor(s / 60)} min ${String(s % 60).padStart(2, '0')} s`;
  return elapsed(s * 1000);
}

// ── Text with literals and environment markers ────────────────────────────────────────────────────────────────────

/**
 * A run of a sentence: prose, a literal set in `gw-code` (tool, connection, statement), a literal set in plain mono
 * (a name in a table cell, next to a `gw-code` chip), or an environment marker.
 */
export type Part =
  | { readonly kind: 'text'; readonly text: string }
  | { readonly kind: 'code'; readonly text: string }
  | { readonly kind: 'mono'; readonly text: string }
  | { readonly kind: 'env'; readonly text: Env };

const t = (text: string): Part => ({ kind: 'text', text });
const c = (text: string): Part => ({ kind: 'code', text });
const m = (text: string): Part => ({ kind: 'mono', text });
const e = (env: Env | null | undefined): Part[] => (env ? [t(' '), { kind: 'env', text: env }] : []);

/** The parts as plain text (for `title`, `aria-label` and the tests). */
export function plain(parts: readonly Part[]): string {
  return parts.map((p) => p.text).join('');
}

// ── Links into Activity ──────────────────────────────────────────────────────────────────────────────────────────

/** A link to another page: route, query parameters and fragment (`/activity?q=…`, `/activity#event-12`). */
export interface PageLink {
  readonly route: string;
  readonly query?: { readonly [key: string]: string };
  readonly fragment?: string;
}

/**
 * Activity with the page's query narrowed by `tags` (`tool:sql_query connection:prod-replica`) and the same range: a
 * number on this page opens exactly the calls behind it. A tag the query already has isn't repeated.
 */
export function activityLink(pageQuery: string, key: InsightsRangeKey, tags: readonly string[] = [], extra: { readonly view?: string } = {}): PageLink {
  const page = parseQuery(pageQuery);
  const all = [...page.tags, ...tags.filter((tag) => !page.tags.includes(tag))];
  const q = joinQuery(all, page.text);
  const query: Record<string, string> = {};
  if (q) query['q'] = q;
  query['range'] = key;
  if (extra.view) query['view'] = extra.view;
  return { route: '/activity', query };
}

/** A query value with spaces is still one tag (`rule:mode: off` would not be): such values aren't linked. */
function tagOf(field: string, value: string | null | undefined): string | null {
  return value && !/\s/.test(value) ? `${field}:${value}` : null;
}

// ── The filter bar ───────────────────────────────────────────────────────────────────────────────────────────────

/** The count line of the filter bar: "1,284 calls · compared with the previous 24 hours", "312 of 1,284 calls · …". */
export function statusLine(ins: Insights, filtered: boolean, key: InsightsRangeKey): string {
  const calls = ins.kpis.current.calls;
  const count = filtered ? `${formatCount(calls)} of ${plural(ins.unfilteredCalls, 'call')}` : plural(calls, 'call');
  return ins.kpis.previous ? `${count} · compared with the ${rangeWords(key).previous}` : count;
}

// ── KPI tiles ────────────────────────────────────────────────────────────────────────────────────────────────────

/** `gw-stat__delta`: direction (icon and modifier), what it means (`data-sentiment`, none when flat) and the words. */
export interface StatDelta {
  readonly dir: 'up' | 'down' | 'flat';
  readonly sentiment: 'good' | 'bad' | 'neutral' | null;
  readonly icon: 'trending-up' | 'trending-down' | 'minus';
  readonly text: string;
}

/** A `gw-sparkline` in the tile's chart slot, with the trend in words. */
export interface StatChart {
  readonly points: string;
  readonly label: string;
}

/** A run of `gw-stat__meta`: text, or a link (another page, or a section of this one). */
export interface MetaPart {
  readonly text: string;
  /** Hidden words that complete a short link name ("Review" → " 3 waiting requests"). */
  readonly sr?: string;
  readonly link?: PageLink;
  /** A section of this page the link jumps to (`in-denied-title`). */
  readonly section?: string;
}

/** The overhead tile's `gw-meter` against the target. */
export interface StatMeter {
  readonly label: string;
  readonly value: string;
  readonly fraction: number;
  readonly level: 'ok' | 'warn' | 'critical';
  readonly icon: 'triangle-alert' | 'circle-alert' | null;
  readonly now: number;
  readonly max: number;
  readonly valueText: string;
}

export interface StatTile {
  readonly id: 'calls' | 'approved' | 'denied' | 'errors' | 'overhead' | 'tasks';
  readonly label: string;
  readonly value: string;
  readonly unit: string | null;
  readonly chart: StatChart | null;
  readonly delta: StatDelta | null;
  readonly meta: readonly MetaPart[];
  readonly meter: StatMeter | null;
}

function change(n: number, text: string, vs: string, up: StatDelta['sentiment'], down: StatDelta['sentiment']): StatDelta {
  if (n === 0) return { dir: 'flat', sentiment: null, icon: 'minus', text: `No change vs ${vs}` };
  return n > 0 ? { dir: 'up', sentiment: up, icon: 'trending-up', text } : { dir: 'down', sentiment: down, icon: 'trending-down', text };
}

/** "+5", "−4 ms": a change with its sign, a typographic minus and the kit's thousands. */
export function signed(n: number, unit = ''): string {
  const text = `${n < 0 ? '−' : '+'}${formatCount(Math.abs(n))}`;
  return unit ? `${text} ${unit}` : text;
}

/** Calls against the previous range: "+12% vs previous 24 hours"; the count when there were none before. */
export function callsDelta(now: number, before: number, vs: string): StatDelta {
  const d = now - before;
  const pct = before > 0 ? Math.round((d / before) * 100) : 0;
  const text = pct !== 0 ? `${pct > 0 ? '+' : '−'}${formatCount(Math.abs(pct))}%` : signed(d);
  return change(d, `${text} vs ${vs}`, vs, 'neutral', 'neutral');
}

/** "6 more than previous 24 hours", "3 fewer than previous 24 hours" (the kit's wording for counts of events). */
export function countDelta(now: number, before: number, vs: string, up: StatDelta['sentiment'], down: StatDelta['sentiment']): StatDelta {
  const d = now - before;
  return change(d, `${formatCount(Math.abs(d))} ${d > 0 ? 'more' : 'fewer'} than ${vs}`, vs, up, down);
}

/** "+5 vs previous 24 hours", "−4 ms vs previous 24 hours". */
export function signedDelta(d: number, unit: string, vs: string, up: StatDelta['sentiment'], down: StatDelta['sentiment']): StatDelta {
  return change(d, `${signed(d, unit)} vs ${vs}`, vs, up, down);
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

/** "hour", "day", "6 hours", "5 minutes": what one bucket of the series covers. */
export function bucketUnit(stepHours: number): string {
  if (stepHours === 1) return 'hour';
  if (stepHours === 24) return 'day';
  if (stepHours < 1) return `${Math.round(stepHours * 60)} minutes`;
  return `${stepHours} hours`;
}

/** When a bucket starts, as its axis and table say it: "11:00" within a day, "Oct 21" for days. */
function bucketLabel(at: string | number, stepHours: number, now: number): string {
  return stepHours >= 24 ? shortDate(at, now) : clockTime(at, false);
}

/** Few calls between midnight and six in a day-long hourly series (kit "quiet overnight"). */
function quietOvernight(h: Histogram): boolean {
  if (h.stepHours !== 1 || h.buckets.length < 24) return false;
  const total = h.buckets.reduce((s, b) => s + b.total, 0);
  const night = h.buckets.filter((b) => new Date(b.from).getHours() < 6);
  if (!total || night.length < 6) return false;
  return night.reduce((s, b) => s + b.total, 0) <= total * 0.05;
}

/** The busiest bucket ("peak of 166 at 11:00"), null without calls. */
function peakOf(h: Histogram, now: number): { readonly total: number; readonly at: string } | null {
  let best: HistogramBucket | null = null;
  for (const b of h.buckets) if (b.total > 0 && (!best || b.total > best.total)) best = b;
  return best ? { total: best.total, at: bucketLabel(best.from, h.stepHours, now) } : null;
}

export interface TilesInput {
  readonly insights: Insights;
  readonly key: InsightsRangeKey;
  readonly pageQuery: string;
  readonly now: number;
}

/** The six tiles of the KPI row (kit order): calls, approved by you, denied by policy, errors, overhead p95, tasks. */
export function statTiles({ insights, key, pageQuery, now }: TilesInput): StatTile[] {
  const cur: InsightsTotals = insights.kpis.current;
  const prev = insights.kpis.previous;
  const vs = rangeWords(key).previous;
  const h = insights.callsOverTime;

  const peak = peakOf(h, now);
  const unit = bucketUnit(h.stepHours);
  const callsMeta: MetaPart[] = [];
  if (cur.pending > 0) {
    callsMeta.push({ text: `Plus ${formatCount(cur.pending)} waiting for approval · ` });
    callsMeta.push({ text: 'Review', sr: ` ${plural(cur.pending, 'waiting request')}`, link: { route: '/approvals' } });
  }
  const calls: StatTile = {
    id: 'calls',
    label: 'Calls',
    value: formatCount(cur.calls),
    unit: null,
    chart: peak
      ? {
          points: sparkPoints(h.buckets.map((b) => b.total)),
          label: `Calls per ${unit}: ${quietOvernight(h) ? 'quiet overnight, ' : ''}peak of ${formatCount(peak.total)} at ${peak.at}`,
        }
      : null,
    delta: prev ? callsDelta(cur.calls, prev.calls, vs) : null,
    meta: callsMeta,
    meter: null,
  };

  const median = insights.approvals.medianWaitS;
  const approved: StatTile = {
    id: 'approved',
    label: 'Approved by you',
    value: formatCount(cur.approved),
    unit: null,
    chart: null,
    delta: prev ? countDelta(cur.approved, prev.approved, vs, 'neutral', 'neutral') : null,
    meta: median !== null ? [{ text: `Median wait ${elapsed(median * 1000)}` }] : [],
    meter: null,
  };

  const denied: StatTile = {
    id: 'denied',
    label: 'Denied by policy',
    value: formatCount(cur.denied),
    unit: null,
    chart: null,
    delta: prev ? signedDelta(cur.denied - prev.denied, '', vs, 'bad', 'good') : null,
    meta: cur.denied > 0 && insights.deniedByRule.length ? [{ text: 'See by rule', section: 'in-denied-title' }] : [],
    meter: null,
  };

  const errors: StatTile = {
    id: 'errors',
    label: 'Errors',
    value: formatCount(cur.errors),
    unit: null,
    chart: null,
    delta: prev ? countDelta(cur.errors, prev.errors, vs, 'bad', 'good') : null,
    meta: cur.errors > 0 && insights.errorsByConnection.length ? [{ text: 'See by connection', section: 'in-errors-title' }] : [],
    meter: null,
  };

  const p95 = cur.gatewayP95Ms;
  const p50 = cur.gatewayP50Ms;
  let meter: StatMeter | null = null;
  if (p95 !== null) {
    const level = p95 <= OVERHEAD_TARGET_MS ? 'ok' : p95 <= 2 * OVERHEAD_TARGET_MS ? 'warn' : 'critical';
    const judged = level === 'ok' ? 'within target' : 'over target';
    meter = {
      label: `Target ≤ ${OVERHEAD_TARGET_MS} ms${p50 !== null ? ` · p50 ${formatCount(Math.round(p50))} ms` : ''}`,
      value: `${formatCount(Math.round(p95))} / ${OVERHEAD_TARGET_MS} ms`,
      fraction: Math.min(1, p95 / OVERHEAD_TARGET_MS),
      level,
      icon: level === 'warn' ? 'triangle-alert' : level === 'critical' ? 'circle-alert' : null,
      now: Math.min(Math.round(p95), OVERHEAD_TARGET_MS),
      max: OVERHEAD_TARGET_MS,
      valueText: `${formatCount(Math.round(p95))} ms of the ${OVERHEAD_TARGET_MS} ms target, ${judged}`,
    };
  }
  const prevP95 = prev?.gatewayP95Ms ?? null;
  const overhead: StatTile = {
    id: 'overhead',
    label: 'Gateway overhead p95',
    value: p95 === null ? 'None' : formatCount(Math.round(p95)),
    unit: p95 === null ? null : 'ms',
    chart: null,
    delta: p95 !== null && prevP95 !== null ? signedDelta(Math.round(p95) - Math.round(prevP95), 'ms', vs, 'bad', 'good') : null,
    meta: p95 === null ? [{ text: `No completed calls in the ${rangeWords(key).span}` }] : [],
    meter,
  };

  const tasksMeta: MetaPart[] = [];
  if (cur.tasks > 0) {
    tasksMeta.push({ text: `${formatCount(Math.round(cur.calls / cur.tasks))} calls per task on average · ` });
    tasksMeta.push({ text: 'View traces', link: activityLink(pageQuery, key, [], { view: 'traces' }) });
  }
  const tasks: StatTile = {
    id: 'tasks',
    label: 'Agent tasks',
    value: formatCount(cur.tasks),
    unit: null,
    chart: null,
    delta: prev ? countDelta(cur.tasks, prev.tasks, vs, 'neutral', 'neutral') : null,
    meta: tasksMeta,
    meter: null,
  };

  return [calls, approved, denied, errors, overhead, tasks];
}

// ── Unusual activity ─────────────────────────────────────────────────────────────────────────────────────────────

/** One finding as a compact alert: tone and icon, title and body with literals, and the calls behind it. */
export interface UnusualAlert {
  readonly tone: 'danger' | 'warning';
  readonly icon: string;
  readonly title: readonly Part[];
  readonly body: readonly Part[];
  readonly action: { readonly label: string; readonly sr: string; readonly link: PageLink } | null;
}

/** `sent TRUNCATE and then DROP TABLE`: the statements of a task in the order they came. */
function statementParts(statements: readonly string[]): Part[] {
  const out: Part[] = [];
  statements.forEach((s, i) => {
    if (i > 0) out.push(t(i === statements.length - 1 ? ' and then ' : ', '));
    out.push(c(s));
  });
  return out;
}

function deniedWords(denied: number, of: number): string {
  if (of === 2 && denied === 2) return 'Both were denied.';
  if (of === 1 && denied === 1) return 'It was denied.';
  if (denied === of) return `All ${formatCount(of)} were denied.`;
  return `${formatCount(denied)} of them ${denied === 1 ? 'was' : 'were'} denied.`;
}

/** "×4", from the core's factor (whole above 3, one decimal below). */
function factorText(f: number): string {
  return f >= 3 ? `${Math.round(f)}×` : `${(Math.round(f * 10) / 10).toString()}×`;
}

/** The kit's three detectors in its words; a finding of another kind by the core's sentence. */
export function unusualAlert(u: InsightUnusual, key: InsightsRangeKey, now: number): UnusualAlert {
  const traceLink = (id: string): PageLink => activityLink('', key, [`trace:${id.slice(0, 6)}`], { view: 'traces' });
  switch (u.kind) {
    case 'prod-ddl': {
      const statements = u.statements ?? [];
      const within = u.withinS ?? null;
      const client = u.client ? clientName(u.client) : 'An agent';
      const body: Part[] = [t(`${client} sent `), ...statementParts(statements)];
      if (u.connection) body.push(t(' to '), c(u.connection), ...(u.env ? [t(' '), { kind: 'env', text: u.env } as Part] : []));
      if (within !== null) body.push(t(within <= 60 ? ' within a minute' : ` within ${duration(within * 1000)}`));
      body.push(t(', in one task.'));
      if (u.denied != null) body.push(t(` ${deniedWords(u.denied, Math.max(statements.length, u.denied))}`));
      return {
        tone: 'danger',
        icon: 'shield-x',
        title: [t(`An agent tried to change ${u.env ?? 'prod'}`)],
        body,
        action: u.traceId ? { label: 'Open the task', sr: u.traceTitle ? ` ${u.traceTitle}` : ` ${u.traceId.slice(0, 6)}`, link: traceLink(u.traceId) } : null,
      };
    }
    case 'tool-spike': {
      const title: Part[] = [];
      if (u.tool) title.push(c(u.tool));
      if (u.connection) title.push(t(' on '), c(u.connection), ...(u.env ? [t(' '), { kind: 'env', text: u.env } as Part] : []));
      title.push(t(` is ${factorText(u.factor ?? 0)} its 7-day average`));
      const body: Part[] = [t(`${plural(u.calls ?? 0, 'call')} against ${formatCount(Math.round(u.dailyAverage ?? 0))} a day`)];
      if (u.client) body.push(t(`, mostly from ${clientName(u.client)}`));
      if (u.endpoint) body.push(t(' on '), c(u.endpoint));
      if (u.from && u.to) body.push(t(` between ${clockTime(u.from, false)} and ${clockTime(u.to, false)}`));
      body.push(t('.'));
      const tags = [tagOf('tool', u.tool), tagOf('connection', u.connection)].filter((x): x is string => !!x);
      return {
        tone: 'warning',
        icon: 'trending-up',
        title,
        body,
        action: { label: `View ${plural(u.calls ?? 0, 'call')}`, sr: ` of ${[u.tool, u.connection].filter(Boolean).join(' on ')}`, link: activityLink('', key, tags) },
      };
    }
    case 'slow-connection': {
      const title: Part[] = [];
      if (u.connection) title.push(c(u.connection));
      title.push(t(` answers ${factorText(u.factor ?? ((u.p95Ms ?? 0) / Math.max(1, u.usualMs ?? 1)))} slower than usual`));
      const since = u.since ? ` since ${sinceText(u.since, now)}` : '';
      const body: Part[] = [t(`${latency(u.p95Ms ?? 0)} at p95${since}, against ${latency(u.usualMs ?? 0)} over the last 7 days.`)];
      if (u.timeoutMs) body.push(t(` Calls that wait longer than ${latency(u.timeoutMs)} time out.`));
      const tag = tagOf('connection', u.connection);
      return {
        tone: 'warning',
        icon: 'timer',
        title,
        body,
        action: { label: `View ${plural(u.calls ?? 0, 'call')}`, sr: u.connection ? ` on ${u.connection}` : '', link: activityLink('', key, tag ? [tag] : []) },
      };
    }
    default:
      return {
        tone: 'warning',
        icon: 'triangle-alert',
        title: [t(u.text)],
        body: [],
        action: u.traceId ? { label: 'Open the task', sr: ` ${u.traceId.slice(0, 6)}`, link: traceLink(u.traceId) } : null,
      };
  }
}

/** "13:30" today, "13:30 yesterday", "Oct 19 13:30" before. */
function sinceText(at: string, now: number): string {
  const day = shortDate(at, now);
  if (day === 'Today') return clockTime(at, false);
  if (day === shortDate(now - DAY, now)) return `${clockTime(at, false)} yesterday`;
  return `${day} ${clockTime(at, false)}`;
}

// ── Charts ───────────────────────────────────────────────────────────────────────────────────────────────────────

/** A stacked bar's run (`gw-chart__segment--<key>` at `--v`; `default` is the kit's unmodified segment, chart.1). */
export interface Segment {
  readonly key: Series | 'brand' | 'default';
  readonly v: number;
}

const SERIES_KEYS: readonly Series[] = ['success', 'warning', 'danger', 'neutral'];

export interface CallsChart {
  readonly bars: readonly { readonly from: string; readonly segments: readonly Segment[] }[];
  readonly gridlines: readonly { readonly v: number; readonly label: string }[];
  readonly axis: readonly { readonly v: number; readonly label: string }[];
  readonly legend: readonly { readonly key: Series; readonly label: string; readonly value: number }[];
  readonly rows: readonly { readonly label: string; readonly counts: Record<Series, number>; readonly total: number }[];
  readonly unit: string;
  readonly aria: string;
  readonly description: string;
}

/** The densest run of up to three buckets of failures, if it holds most of them ("between 09:00 and 11:00"). */
function failureWindow(h: Histogram, now: number): string | null {
  const danger = h.buckets.map((b) => seriesCounts(b).danger);
  const total = danger.reduce((s, n) => s + n, 0);
  if (!total) return null;
  let best = { from: 0, sum: -1 };
  for (let i = 0; i < danger.length; i++) {
    const sum = danger.slice(i, i + 3).reduce((s, n) => s + n, 0);
    if (sum > best.sum) best = { from: i, sum };
  }
  if (best.sum * 2 <= total) return null;
  const last = Math.min(best.from + 2, danger.length - 1);
  return `${bucketLabel(h.buckets[best.from].from, h.stepHours, now)} and ${bucketLabel(h.buckets[last].from, h.stepHours, now)}`;
}

/** Calls over time: stacked bars by decision, four gridlines, five axis labels, the legend and the summary. */
export function callsChart(h: Histogram, key: InsightsRangeKey, now: number): CallsChart {
  const n = h.buckets.length;
  const max = Math.max(0, ...h.buckets.map((b) => b.total));
  const scale = niceMax(max);
  const quarters = scale % 4 === 0;
  const bars = h.buckets.map((b) => {
    const counts = seriesCounts(b);
    return { from: b.from, segments: SERIES_KEYS.filter((s) => counts[s] > 0).map((s) => ({ key: s, v: counts[s] / scale })) };
  });
  const toNow = Math.abs(Date.parse(h.to) - now) < 2 * 60_000;
  const axis = [0, 0.25, 0.5, 0.75, 1].map((v) => {
    if (v === 1 && toNow) return { v, label: 'Now' };
    const at = n ? (v === 1 ? h.buckets[n - 1].to : h.buckets[Math.min(n - 1, Math.round(v * n))].from) : new Date(Date.parse(h.from) + v * (Date.parse(h.to) - Date.parse(h.from))).toISOString();
    return { v, label: bucketLabel(at, h.stepHours, now) };
  });
  const legendCounts = { success: 0, warning: 0, danger: 0, neutral: 0 } as Record<Series, number>;
  const rows = h.buckets.map((b) => {
    const counts = seriesCounts(b);
    for (const s of SERIES_KEYS) legendCounts[s] += counts[s];
    return { label: bucketLabel(b.from, h.stepHours, now), counts, total: b.total };
  });
  const unit = bucketUnit(h.stepHours);
  const span = rangeWords(key).span;
  const finished = legendCounts.success + legendCounts.danger + legendCounts.neutral;
  const peak = peakOf(h, now);
  const window = failureWindow(h, now);
  const outcomes = [
    `${formatCount(legendCounts.success)} allowed or approved`,
    `${formatCount(legendCounts.danger)} denied, rejected or failed`,
    `${formatCount(legendCounts.neutral)} expired`,
  ];
  const waiting = legendCounts.warning ? `; ${formatCount(legendCounts.warning)} more ${legendCounts.warning === 1 ? 'is' : 'are'} waiting for approval` : '';
  const peakText = peak ? ` Peak of ${plural(peak.total, 'call')} at ${peak.at}.` : '';
  const windowText = window ? ` Most denials came between ${window}.` : '';
  const aria = `Calls per ${unit}, ${span}. ${plural(finished, 'call')} finished: ${outcomes.join(', ')}${waiting}.${peakText}${windowText}`;
  let description: string;
  if (!peak) description = `No calls in the ${span}.`;
  else if (window) description = `Traffic peaked at ${plural(peak.total, 'call')} at ${peak.at}, and most denials came between ${window}.`;
  else if (legendCounts.danger === 0) description = `Traffic peaked at ${plural(peak.total, 'call')} at ${peak.at}, and nothing was denied or failed.`;
  else description = `Traffic peaked at ${plural(peak.total, 'call')} at ${peak.at}.`;
  return {
    bars,
    gridlines: (quarters ? [0.25, 0.5, 0.75, 1] : [0.5, 1]).map((v) => ({ v, label: formatCount(scale * v) })),
    axis,
    legend: SERIES.map((s) => ({ key: s.key, label: s.label, value: legendCounts[s.key] })),
    rows,
    unit,
    aria,
    description,
  };
}

/** One row of a horizontal bar chart: label, stacked segments, value. */
export interface HbarRow {
  readonly label: string;
  /** The label is a literal (a tool, a connection): set in mono. */
  readonly mono: boolean;
  /** A client's mark before the label. */
  readonly client?: string;
  readonly segments: readonly Segment[];
  readonly value: string;
}

export interface ToolsChart {
  readonly rows: readonly HbarRow[];
  readonly table: readonly { readonly name: string; readonly count: number }[];
  readonly aria: string;
  readonly description: readonly Part[];
  readonly footer: { readonly parts: readonly Part[]; readonly link: PageLink } | null;
}

/** Top tools: the six busiest, then the rest as one neutral bar; the summary names the two that lead. */
export function toolsChart(tools: readonly { readonly name: string; readonly count: number }[], key: InsightsRangeKey, pageQuery: string): ToolsChart {
  const sorted = [...tools].sort((a, b) => b.count - a.count);
  const max = sorted[0]?.count ?? 0;
  const shown = sorted.length === TOP_TOOLS + 1 ? sorted : sorted.slice(0, TOP_TOOLS);
  const rest = sorted.slice(shown.length);
  const restCount = rest.reduce((s, x) => s + x.count, 0);
  const total = sorted.reduce((s, x) => s + x.count, 0);
  const rows: HbarRow[] = shown.map((x) => ({ label: x.name, mono: true, segments: [{ key: 'default', v: max ? x.count / max : 0 }], value: formatCount(x.count) }));
  if (rest.length) rows.push({ label: `${rest.length} other ${rest.length === 1 ? 'tool' : 'tools'}`, mono: false, segments: [{ key: 'neutral', v: max ? restCount / max : 0 }], value: formatCount(restCount) });
  const span = rangeWords(key).span;
  const named = shown.map((x) => `${x.name} ${formatCount(x.count)}`);
  let aria = `Top tools by calls, ${span}`;
  if (named.length) {
    const [first, ...others] = shown;
    aria += `: ${first.name} leads with ${formatCount(first.count)}`;
    if (others.length) aria += `, then ${listWords(named.slice(1))}`;
    if (rest.length) aria += `; ${rest.length} other ${rest.length === 1 ? 'tool' : 'tools'} made ${plural(restCount, 'call')}`;
    aria += '.';
  } else {
    aria += ': no calls.';
  }
  let description: Part[];
  let footer: ToolsChart['footer'] = null;
  if (!sorted.length) {
    description = [t(`No calls in the ${span}.`)];
  } else if (sorted.length === 1) {
    description = [c(sorted[0].name), t(` made every call.`)];
    footer = { parts: [t('View '), c(sorted[0].name), t(' calls in Activity')], link: activityLink(pageQuery, key, [`tool:${sorted[0].name}`]) };
  } else {
    const [a, b] = sorted;
    description = [c(a.name), t(' and '), c(b.name), t(` make up ${shareWords((a.count + b.count) / total)} of all calls.`)];
    footer = {
      parts: [t('View '), { kind: 'code', text: a.name }, t(' and '), { kind: 'code', text: b.name }, t(' calls in Activity')],
      link: activityLink(pageQuery, key, [`tool:${a.name}`, `tool:${b.name}`]),
    };
  }
  return { rows, table: sorted, aria, description, footer };
}

/** A client's decided calls as the chart's three series. */
function clientSeries(d: DecisionCounts): { readonly ok: number; readonly bad: number; readonly expired: number } {
  const n = (k: string) => d[k] ?? 0;
  return { ok: n('allowed') + n('approved'), bad: n('denied') + n('rejected') + n('error'), expired: n('expired') };
}

export interface IdleClients {
  readonly title: string;
  readonly text: string;
  readonly names: readonly string[];
}

export interface ClientsChart {
  readonly rows: readonly HbarRow[];
  readonly table: readonly { readonly name: string; readonly ok: number; readonly bad: number; readonly expired: number; readonly total: number }[];
  readonly aria: string;
  readonly description: readonly Part[];
  readonly idle: IdleClients | null;
  readonly links: readonly { readonly label: string; readonly link: PageLink }[];
}

/** How an MCP client without calls stands on this computer ("is detected but not connected"). */
function idleWords(card: ClientCard): string {
  switch (card.status) {
    case 'detected':
      return 'is detected but not connected';
    case 'not-installed':
      return 'is not installed';
    case 'needs-restart':
      return 'needs a restart';
    case 'error':
      return "can't be set up";
    default:
      return 'made no calls';
  }
}

/**
 * By client: each client's calls by outcome; the summary says who makes most calls and how often they are denied or
 * fail, and — for a client whose calls all went through — where they went. Clients this computer has that made no
 * call (from `GET /api/clients`) get the kit's compact empty state, but only without filters: a filter hides calls.
 */
export function clientsChart(clients: readonly InsightClient[], cards: readonly ClientCard[] | null, filtered: boolean, key: InsightsRangeKey, pageQuery: string): ClientsChart {
  const sorted = [...clients].sort((a, b) => b.count - a.count);
  const total = sorted.reduce((s, x) => s + x.count, 0);
  const max = sorted[0]?.count ?? 0;
  const rows: HbarRow[] = sorted.map((x) => {
    const s = clientSeries(x.decisions);
    const segments: Segment[] = [];
    if (s.ok) segments.push({ key: 'success', v: s.ok / max });
    if (s.bad) segments.push({ key: 'danger', v: s.bad / max });
    if (s.expired) segments.push({ key: 'neutral', v: s.expired / max });
    return { label: clientName(x.client), mono: false, client: x.client, segments, value: formatCount(x.count) };
  });
  const table = sorted.map((x) => {
    const s = clientSeries(x.decisions);
    return { name: clientName(x.client), ok: s.ok, bad: s.bad, expired: s.expired, total: x.count };
  });

  const callers = new Set(sorted.map((x) => x.client));
  const idleCards = !filtered && cards ? cards.filter((card) => !callers.has(card.client)) : [];
  let idle: IdleClients | null = null;
  if (idleCards.length) {
    const names = idleCards.map((card) => card.name || clientName(card.client));
    const words = idleCards.map((card, i) => `${names[i]} ${idleWords(card)}`);
    idle = {
      names,
      title: `No calls from ${names.length === 2 ? `${names[0]} or ${names[1]}` : listWords(names)}`,
      text: `${words.length > 1 ? `${words.slice(0, -1).join(', ')}, and ${words[words.length - 1]}` : words[0]}.`,
    };
  }

  const span = rangeWords(key).span;
  const ariaParts = sorted.map((x) => {
    const s = clientSeries(x.decisions);
    const name = clientName(x.client);
    const of: string[] = [];
    if (s.bad) of.push(`${formatCount(s.bad)} denied, rejected or failed`);
    if (s.expired) of.push(`${formatCount(s.expired)} expired`);
    const tail = of.length ? `, of them ${listWords(of)}` : ', all allowed or approved';
    return `${name} ${formatCount(x.count)} (${percent(x.count, total)})${tail}`;
  });
  const aria = ariaParts.length ? `Calls by AI app, ${span}: ${ariaParts.join('; ')}.` : `Calls by AI app, ${span}: no calls.`;

  const description: Part[] = [];
  if (sorted.length) {
    const top = sorted[0];
    description.push(t(`${clientName(top.client)} makes ${percent(top.count, total)} of calls`));
    const failing = sorted.filter((x) => clientSeries(x.decisions).bad > 0);
    if (failing.length) {
      const worst = Math.max(...failing.map((x) => clientSeries(x.decisions).bad / x.count));
      const bound = Math.max(1, Math.ceil(worst * 100 + 1e-9));
      const names = listWords(failing.map((x) => clientName(x.client)));
      description.push(t(`; ${names} ${failing.length === 1 ? 'is denied or fails' : 'are denied or fail'} on fewer than ${bound}% of them`));
    }
    description.push(t('.'));
    const clean = sorted.find((x) => x !== top && clientSeries(x.decisions).bad === 0 && clientSeries(x.decisions).expired === 0);
    if (clean) {
      description.push(t(` ${clientName(clean.client)}: ${plural(clean.count, 'call')}`));
      if (clean.firstAt && clean.lastAt) description.push(t(` from ${clockTime(clean.firstAt, false)} to ${clockTime(clean.lastAt, false)}`));
      description.push(t(clean.count === 1 ? ', allowed' : ', all allowed'));
      if (clean.connections.length === 1) description.push(t(', to '), c(clean.connections[0]));
      if (clean.endpoints.length === 1) description.push(t(' through '), c(clean.endpoints[0]));
      description.push(t('.'));
    }
  } else {
    description.push(t(`No calls in the ${span}.`));
  }

  const links = sorted.slice(0, 3).map((x) => ({ label: `View ${clientName(x.client)} calls`, link: activityLink(pageQuery, key, [`client:${x.client}`]) }));
  return { rows, table, aria, description, idle, links };
}

// ── Decisions ────────────────────────────────────────────────────────────────────────────────────────────────────

export interface ApprovalsView {
  readonly decided: number;
  readonly segments: readonly Segment[];
  readonly legend: readonly { readonly key: Series; readonly label: string; readonly value: number; readonly share: string }[];
  readonly aria: string;
  readonly description: string;
  /** "38 of 55": requests a person decided within a minute, of all a person decided. */
  readonly withinMinute: string | null;
  readonly p90: string | null;
  readonly slowest: readonly SlowRow[];
  readonly link: PageLink;
}

export interface SlowRow {
  readonly tool: string;
  readonly env: Env | null;
  readonly connection: string | null;
  readonly sr: string;
  readonly link: PageLink | null;
  readonly look: Look;
  readonly wait: string;
}

/** The approval windows of the environments ("5 min on dev, 10 min on stage and prod"); null when unknown. */
export function windowsWords(windows: Partial<Record<Env, number>> | null): string | null {
  if (!windows) return null;
  const groups = new Map<number, Env[]>();
  for (const env of ['dev', 'stage', 'prod'] as const) {
    const m = windows[env];
    if (m === undefined || m === null) continue;
    groups.set(m, [...(groups.get(m) ?? []), env]);
  }
  if (!groups.size) return null;
  return [...groups.entries()].map(([m, envs]) => `${formatCount(m)} min on ${listWords(envs)}`).join(', ');
}

/** When a call was, for a row's hidden words: "09:47", "16:41 yesterday", "Oct 19 16:41". */
function whenText(at: string, now: number): string {
  return sinceText(at, now);
}

/** The Approvals card: outcomes, how fast a person decided, the slowest decisions, and the summary sentence. */
export function approvalsView(a: InsightsApprovals, windows: Partial<Record<Env, number>> | null, key: InsightsRangeKey, pageQuery: string, now: number): ApprovalsView {
  const decided = a.approved + a.rejected + a.expired;
  const byPerson = a.approved + a.rejected;
  const share = (n: number) => (decided ? n / decided : 0);
  const segments: Segment[] = [];
  if (a.approved) segments.push({ key: 'success', v: share(a.approved) });
  if (a.rejected) segments.push({ key: 'danger', v: share(a.rejected) });
  if (a.expired) segments.push({ key: 'neutral', v: share(a.expired) });
  const legend = [
    { key: 'success' as Series, label: 'Approved', value: a.approved, share: percent(a.approved, decided) },
    { key: 'danger' as Series, label: 'Rejected', value: a.rejected, share: percent(a.rejected, decided) },
    { key: 'neutral' as Series, label: 'Expired', value: a.expired, share: percent(a.expired, decided) },
  ];
  const span = rangeWords(key).span;
  const aria = `${plural(decided, 'approval request')} decided in the ${span}: ${formatCount(a.approved)} approved (${percent(a.approved, decided)}), ${formatCount(a.rejected)} rejected (${percent(a.rejected, decided)}), ${formatCount(a.expired)} expired (${percent(a.expired, decided)}).`;

  const within = a.waitHistogram.filter((b) => b.toS !== null && b.toS <= 60).reduce((s, b) => s + b.count, 0);
  let description: string;
  if (!decided) {
    description = `No approval requests were decided in the ${span}.`;
  } else {
    const approvedShare = a.approvedShare ?? share(a.approved);
    const frac = fractionWords(approvedShare);
    description = frac === 'all' ? `You approved all ${plural(decided, 'request')}` : frac === 'none' ? `You approved none of the ${plural(decided, 'request')}` : `You approved ${frac} requests`;
    if (byPerson) {
      if (within * 2 > byPerson) description += ', most within a minute';
      else if (a.medianWaitS !== null) description += `, with a median wait of ${elapsed(a.medianWaitS * 1000)}`;
    }
    if (a.expired) {
      const w = windowsWords(windows);
      description += `; ${formatCount(a.expired)} expired before you answered${w ? ` (${w})` : ''}`;
    }
    description += '.';
  }

  const slowest: SlowRow[] = a.slowest.map((s: InsightSlowDecision) => ({
    tool: s.tool,
    env: s.env,
    connection: s.connection,
    sr: `${s.connection ? ` on ${s.connection}` : ''} at ${whenText(s.requestedAt, now)}, open call`,
    link: s.eventId !== null ? { route: '/activity', fragment: `event-${s.eventId}` } : null,
    look: s.status === 'approved' ? { tone: 'success', icon: 'user-check', text: 'Approved' } : { tone: 'danger', icon: 'user-x', text: 'Rejected' },
    wait: waitText(s.waitS),
  }));

  return {
    decided,
    segments,
    legend,
    aria,
    description,
    withinMinute: byPerson ? `${formatCount(within)} of ${formatCount(byPerson)}` : null,
    p90: a.p90WaitS !== null ? waitText(a.p90WaitS) : null,
    slowest,
    link: activityLink(pageQuery, key, ['decision:approved', 'decision:rejected', 'decision:expired']),
  };
}

/**
 * What a rule stops, in words, for the card's summary ("Most denials were calls to tools that are off"). Rule ids
 * are `<env>.<section>.<rule>` (ARCHITECTURE.md §5 step 7); one this list doesn't know is named by its id instead.
 */
export function ruleMeaning(rule: string): string | null {
  if (/(^|\.)mode[.-]off$/.test(rule)) return 'calls to tools that are off';
  if (/block_ddl$/.test(rule)) return 'DDL statements';
  if (/read_only_tool$/.test(rule) || /endpoint\.read-only$/.test(rule)) return 'writes through a read-only tool';
  if (/block_private_ranges$/.test(rule)) return 'requests to private addresses';
  if (/namespace(_allowlist|-not-allowed)$/.test(rule)) return 'calls outside the allowed namespaces';
  if (/core\.integrity$/.test(rule)) return 'calls under a policy changed outside GateRook';
  return null;
}

/** How the table names a rule: its id, except a tool that is off, which the kit writes as `mode: off`. */
export function ruleLabel(rule: string): string {
  return rule === 'mode.off' ? 'mode: off' : rule;
}

export interface DeniedRow {
  readonly rule: string;
  /** The rule as the table shows it (ruleLabel). */
  readonly label: string;
  readonly count: string;
  readonly sr: string;
  readonly link: PageLink | null;
  readonly example: readonly Part[] | null;
  readonly title: string;
}

export interface DeniedView {
  readonly total: number;
  readonly rows: readonly DeniedRow[];
  readonly description: readonly Part[];
  readonly link: PageLink;
}

/** Denied by rule: one row per rule with an example; the summary names the rule that denied most. */
export function deniedView(rules: readonly InsightDeniedRule[], key: InsightsRangeKey, pageQuery: string): DeniedView {
  const sorted = [...rules].sort((a, b) => b.count - a.count);
  const total = sorted.reduce((s, r) => s + r.count, 0);
  const rows: DeniedRow[] = sorted.map((r) => {
    const tag = tagOf('rule', r.rule);
    const ex = r.example;
    const example: Part[] | null = ex ? [c(ex.literal), ...(ex.connection ? [t(' on '), m(ex.connection)] : []), ...e(ex.env)] : null;
    return {
      rule: r.rule,
      label: ruleLabel(r.rule),
      count: formatCount(r.count),
      sr: `: view ${plural(r.count, 'denied call')} in Activity`,
      link: tag ? activityLink(pageQuery, key, ['decision:denied', tag]) : null,
      example,
      title: ex ? `${ex.literal}${ex.connection ? ` on ${ex.connection}` : ''}` : '',
    };
  });
  let description: Part[];
  if (!sorted.length) description = [t(`The policies denied no call in the ${rangeWords(key).span}.`)];
  else if (sorted.length === 1) description = [t(`Every denial came from `), c(ruleLabel(sorted[0].rule)), t('.')];
  else {
    const [top, second] = sorted;
    const meaning = ruleMeaning(top.rule);
    description =
      meaning && top.count > second.count
        ? [t(`Most denials were ${meaning}`)]
        : [c(ruleLabel(top.rule)), t(` denied the most: ${formatCount(top.count)} of ${formatCount(total)}`)];
    const ddl = sorted.find((r) => r !== top && /^prod\..*block_ddl$/.test(r.rule));
    description.push(t(ddl ? `; ${formatCount(ddl.count)} ${ddl.count === 1 ? 'was' : 'were'} DDL on prod.` : '.'));
  }
  return { total, rows, description, link: activityLink(pageQuery, key, ['decision:denied']) };
}

// ── Latency and errors ───────────────────────────────────────────────────────────────────────────────────────────

/** The axis top for times: the first round value at or above the slowest bar (1.8 s → 2.0 s). */
export function niceMs(max: number): number {
  const steps = [10, 20, 25, 50];
  for (let p = 1; p <= 1e6; p *= 10) for (const s of steps) if (s * p >= max) return s * p;
  return max;
}

export interface LatencyView {
  readonly rows: readonly HbarRow[];
  readonly table: readonly InsightLatency[];
  readonly axis: string;
  readonly aria: string;
  readonly description: readonly Part[];
  readonly link: PageLink;
}

/** Latency by connection: p95 of the gateway's part and of the resource's, on one axis. */
export function latencyView(all: readonly InsightLatency[], key: InsightsRangeKey, pageQuery: string): LatencyView {
  const sorted = [...all].sort((a, b) => b.gatewayP95 + b.resourceP95 - (a.gatewayP95 + a.resourceP95)).slice(0, LATENCY_ROWS);
  const top = niceMs(Math.max(1, ...sorted.map((r) => r.gatewayP95 + r.resourceP95)));
  const rows: HbarRow[] = sorted.map((r) => ({
    label: r.connection,
    mono: true,
    segments: [
      { key: 'neutral', v: r.gatewayP95 / top },
      { key: 'brand', v: r.resourceP95 / top },
    ],
    value: `${latency(r.gatewayP95)} + ${latency(r.resourceP95)}`,
  }));
  const span = rangeWords(key).span;
  const aria = sorted.length
    ? `p95 time per call by connection, ${span}, gateway then resource: ${sorted.map((r) => `${r.connection} ${latency(r.gatewayP95)} plus ${latency(r.resourceP95)}`).join(', ')}.`
    : `p95 time per call by connection, ${span}: no completed calls.`;
  const description: Part[] = [];
  if (sorted.length) {
    const g = sorted.map((r) => Math.round(r.gatewayP95));
    const lo = Math.min(...g);
    const hi = Math.max(...g);
    description.push(t(`The gateway adds ${lo === hi ? `${formatCount(lo)} ms` : `${formatCount(lo)} to ${formatCount(hi)} ms`} at p95${sorted.length > 1 ? ' everywhere' : ''}`));
    const slow = [...sorted].sort((a, b) => b.resourceP95 - a.resourceP95);
    if (slow[0].resourceP95 > hi) {
      description.push(t('; slow calls come from the resources, led by '), c(slow[0].connection));
      if (slow.length > 1 && slow[1].resourceP95 > hi) description.push(t(' and '), c(slow[1].connection));
      description.push(t('.'));
    } else {
      description.push(t(`; the resources answer within ${latency(slow[0].resourceP95)} at p95.`));
    }
  } else {
    description.push(t(`No completed calls in the ${span}.`));
  }
  return { rows, table: sorted, axis: latency(top), aria, description, link: activityLink(pageQuery, key, ['duration:>1s']) };
}

export interface ErrorRow {
  readonly connection: string;
  readonly env: Env | null;
  readonly count: string;
  readonly sr: string;
  readonly link: PageLink | null;
  readonly message: string;
  readonly health: Look;
}

export interface ErrorsView {
  readonly total: number;
  readonly rows: readonly ErrorRow[];
  readonly description: readonly Part[];
  readonly link: PageLink;
}

/** What a connection does when its calls fail, from the error kind ("times out", "can't be reached"). */
function failsHow(kind: string | null): string {
  switch ((kind ?? '').toUpperCase()) {
    case 'TIMEOUT':
      return 'times out';
    case 'RESOURCE_UNAVAILABLE':
      return "can't be reached";
    case 'RESOURCE_ERROR':
      return 'returns errors';
    case 'DENIED':
      return 'refuses the calls';
    case 'BAD_INPUT':
      return 'rejects the arguments';
    case 'OUTCOME_UNKNOWN':
      return 'drops calls mid-way';
    default:
      return 'fails';
  }
}

/** Errors by connection, with each connection's health now (from Connections). */
export function errorsView(all: readonly InsightErrors[], health: ReadonlyMap<string, ConnectionHealth>, key: InsightsRangeKey, pageQuery: string): ErrorsView {
  const sorted = [...all].sort((a, b) => b.count - a.count);
  const total = sorted.reduce((s, r) => s + r.count, 0);
  const rows: ErrorRow[] = sorted.map((r) => {
    const tag = tagOf('connection', r.connection);
    const h = health.get(r.connection);
    return {
      connection: r.connection,
      env: r.env,
      count: formatCount(r.count),
      sr: `: view ${plural(r.count, 'error')} in Activity`,
      link: tag ? activityLink(pageQuery, key, ['decision:error', tag]) : null,
      message: r.message ?? '',
      health: healthLook(h?.state, h?.latencyMs),
    };
  });
  const description: Part[] = [];
  if (!sorted.length) {
    description.push(t(`No call failed in the ${rangeWords(key).span}.`));
  } else if (sorted.length === 1) {
    description.push(t(`All ${plural(total, 'error')} come from `), c(sorted[0].connection), t(`: it ${failsHow(sorted[0].kind)}.`));
  } else if (sorted.length === 2) {
    const [a, b] = sorted;
    description.push(t(`All ${plural(total, 'error')} come from two connections: `), c(a.connection), t(` ${failsHow(a.kind)} and `), c(b.connection), t(` ${failsHow(b.kind)}.`));
  } else {
    description.push(t(`${plural(total, 'error')} come from ${countWords(sorted.length, 'connection')}, most from `), c(sorted[0].connection), t(`, which ${failsHow(sorted[0].kind)}.`));
  }
  return { total, rows, description, link: activityLink(pageQuery, key, ['decision:error']) };
}

// ── Page state ───────────────────────────────────────────────────────────────────────────────────────────────────

/** Which body the page shows below the filter bar. */
export type Body = 'first-run' | 'no-calls' | 'data';

/** First run until the audit holds a call; "no calls" when this range and filter have none; the numbers otherwise. */
export function bodyOf(ins: Insights): Body {
  if (!ins.firstCallAt) return 'first-run';
  return ins.kpis.current.calls === 0 && ins.kpis.current.pending === 0 ? 'no-calls' : 'data';
}

/** The approval windows from `GET /api/policies` (`sections.approvals.timeoutMinutes` of each environment). */
export function approvalWindows(policies: { readonly [env: string]: { readonly sections?: unknown } | undefined } | null): Partial<Record<Env, number>> | null {
  if (!policies) return null;
  const out: Partial<Record<Env, number>> = {};
  for (const env of ['dev', 'stage', 'prod'] as const) {
    const sections = policies[env]?.sections as { readonly approvals?: { readonly timeoutMinutes?: unknown } } | undefined;
    const m = sections?.approvals?.timeoutMinutes;
    if (typeof m === 'number' && m > 0) out[env] = m;
  }
  return Object.keys(out).length ? out : null;
}
