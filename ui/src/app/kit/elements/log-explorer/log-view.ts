import { FacetValue, Facets, Histogram, HistogramBucket } from '../../../core/api/activity.api';
import { Env } from '../../../core/api/types';
import { absoluteTime, clockTime, formatCount, shortDate } from '../../../core/time/time-format';
import { clientName } from '../shared/client-mark';
import { decisionLook } from '../shared/dictionaries';

/**
 * What the log-explorer element shows (design/elements/log-explorer.html): the query as filter tags, the time range,
 * the facets with their counts, and the histogram by decision. Pure functions of the admin API's activity answers
 * (`/api/activity/facets`, `/api/activity/histogram`) and the query text; tested without a DOM.
 */

// ── The query ──────────────────────────────────────────────────────────────────────────────────────────────────────

const TAG = /^-?[a-z]+:\S+$/;

/** `decision:denied env:prod sync` → tags `decision:denied`, `env:prod` and the free text `sync`. */
export function parseQuery(q: string): { readonly tags: string[]; readonly text: string } {
  const tags: string[] = [];
  const words: string[] = [];
  for (const token of q.trim().split(/\s+/).filter(Boolean)) {
    if (TAG.test(token)) {
      if (!tags.includes(token)) tags.push(token);
    } else {
      words.push(token);
    }
  }
  return { tags, text: words.join(' ') };
}

export function joinQuery(tags: readonly string[], text: string): string {
  return [...tags, text.trim()].filter(Boolean).join(' ');
}

/** Adds the tags and words of `input` (what was typed and committed with Enter) to the query. */
export function commitInput(q: string, input: string): string {
  const now = parseQuery(q);
  const typed = parseQuery(input);
  return joinQuery([...now.tags, ...typed.tags.filter((t) => !now.tags.includes(t))], typed.text);
}

export function hasTag(q: string, tag: string): boolean {
  return parseQuery(q).tags.includes(tag);
}

/** Checks or unchecks a facet value: its tag joins or leaves the query. */
export function toggleTag(q: string, tag: string, on: boolean): string {
  const { tags, text } = parseQuery(q);
  const next = on ? (tags.includes(tag) ? tags : [...tags, tag]) : tags.filter((t) => t !== tag);
  return joinQuery(next, text);
}

export function clearTags(q: string): string {
  return parseQuery(q).text;
}

/** The tag's tone: only env keeps its environment's (`env:prod` → `gw-tag--prod`). */
export function tagTone(tag: string): Env | null {
  const m = /^env:(dev|stage|prod)$/.exec(tag);
  return m ? (m[1] as Env) : null;
}

// ── The time range ─────────────────────────────────────────────────────────────────────────────────────────────────

export type RangeKey = '15m' | '1h' | '24h' | '7d' | '30d';

export type LogRange =
  | { readonly kind: 'relative'; readonly key: RangeKey }
  | { readonly kind: 'custom'; readonly from: string; readonly to: string };

export interface RangeOption {
  readonly key: RangeKey;
  readonly label: string;
  readonly ms: number;
}

const DAY_MS = 24 * 3_600_000;

/** Activity's relative ranges (design/elements/log-explorer.html): a call is looked up minutes to days later. */
export const RANGES: readonly RangeOption[] = [
  { key: '15m', label: 'Last 15 minutes', ms: 15 * 60_000 },
  { key: '1h', label: 'Last hour', ms: 3_600_000 },
  { key: '24h', label: 'Last 24 hours', ms: DAY_MS },
  { key: '7d', label: 'Last 7 days', ms: 7 * DAY_MS },
];

/** Insights' ranges (design/pages/insights.html): sums want an hour at least and reach back a month. */
export const INSIGHTS_RANGES: readonly RangeOption[] = [
  { key: '1h', label: 'Last hour', ms: 3_600_000 },
  { key: '24h', label: 'Last 24 hours', ms: DAY_MS },
  { key: '7d', label: 'Last 7 days', ms: 7 * DAY_MS },
  { key: '30d', label: 'Last 30 days', ms: 30 * DAY_MS },
];

export function rangeOption(key: RangeKey): RangeOption | undefined {
  return RANGES.find((x) => x.key === key) ?? INSIGHTS_RANGES.find((x) => x.key === key);
}

/** The range as instants (relative ranges end now). */
export function rangeBounds(r: LogRange, now: number): { readonly from: number; readonly to: number; readonly toNow: boolean } {
  if (r.kind === 'custom') return { from: Date.parse(r.from), to: Date.parse(r.to), toNow: false };
  const ms = rangeOption(r.key)?.ms ?? DAY_MS;
  return { from: now - ms, to: now, toNow: true };
}

/** The trigger's words: "Last 24 hours", "Today 10:00–11:00", "Oct 21 09:00–Oct 22 12:00". */
export function rangeLabel(r: LogRange, now: number): string {
  if (r.kind === 'relative') return rangeOption(r.key)?.label ?? 'Last 24 hours';
  const from = Date.parse(r.from);
  const to = Date.parse(r.to);
  const day = (t: number) => shortDate(t, now);
  const a = `${day(from)} ${clockTime(from, false)}`;
  return day(from) === day(to) ? `${a}–${clockTime(to, false)}` : `${a}–${day(to)} ${clockTime(to, false)}`;
}

/** The absolute range for `title` and screen readers: "2026-10-21 14:30 – 2026-10-22 14:30 +02:00", "… – now (live)". */
export function rangeTitle(r: LogRange, now: number, live: boolean): string {
  const b = rangeBounds(r, now);
  const from = absoluteTime(b.from, false);
  if (live) return `${from.slice(0, 16)} – now (live)`;
  const to = absoluteTime(b.to, false);
  const sameDay = from.slice(0, 10) === to.slice(0, 10);
  return `${from.slice(0, 16)} – ${sameDay ? to.slice(11) : to}`;
}

/** `datetime-local` value of an instant, local time. */
export function localInput(t: number): string {
  const d = new Date(t);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}

// ── Facets ─────────────────────────────────────────────────────────────────────────────────────────────────────────

export const FACET_FIELDS: readonly { readonly key: string; readonly label: string; readonly visible: number }[] = [
  { key: 'decision', label: 'Decision', visible: 7 },
  { key: 'tool', label: 'Tool', visible: 6 },
  { key: 'client', label: 'Client', visible: 6 },
  { key: 'endpoint', label: 'Endpoint', visible: 6 },
  { key: 'connection', label: 'Connection', visible: 5 },
  { key: 'env', label: 'Environment', visible: 3 },
];

const DECISION_ORDER = ['allowed', 'approved', 'pending', 'denied', 'rejected', 'expired', 'error'];
const ENV_ORDER = ['dev', 'stage', 'prod'];

export interface FacetOption {
  readonly value: string;
  readonly tag: string;
  readonly label: string;
  readonly count: number;
  readonly checked: boolean;
  /** Decision rows: the icon and its colour class (`gw-fg-success`). */
  readonly icon: string | null;
  readonly iconTone: string | null;
  readonly mono: boolean;
  /** Connection rows: the connection's environment; env rows: the value itself. */
  readonly env: Env | null;
}

export interface FacetGroup {
  readonly key: string;
  readonly label: string;
  readonly shown: FacetOption[];
  readonly more: FacetOption[];
}

const FG: Record<string, string> = { success: 'gw-fg-success', warning: 'gw-fg-warning', danger: 'gw-fg-danger', neutral: 'gw-fg-muted', info: 'gw-fg-info' };

/**
 * The facet groups: values with calls, plus checked ones at zero; decisions and environments in their fixed order,
 * the rest by count. Counts come from the core within the other filters.
 */
export function facetGroups(facets: Facets | null, q: string, connectionEnvs: Readonly<Record<string, Env>>): FacetGroup[] {
  const tags = parseQuery(q).tags;
  const groups: FacetGroup[] = [];
  for (const f of FACET_FIELDS) {
    const values: FacetValue[] = [...(facets?.[f.key] ?? [])];
    for (const t of tags) {
      const m = new RegExp(`^${f.key}:(.+)$`).exec(t);
      if (m && !values.some((v) => v.name === m[1])) values.push({ name: m[1], count: 0 });
    }
    const options = values
      .map((v) => option(f.key, v, tags, connectionEnvs))
      .filter((o) => o.count > 0 || o.checked);
    if (f.key === 'decision') options.sort((a, b) => DECISION_ORDER.indexOf(a.value) - DECISION_ORDER.indexOf(b.value));
    else if (f.key === 'env') options.sort((a, b) => ENV_ORDER.indexOf(a.value) - ENV_ORDER.indexOf(b.value));
    else options.sort((a, b) => b.count - a.count || a.value.localeCompare(b.value));
    if (!options.length) continue;
    groups.push({ key: f.key, label: f.label, shown: options.slice(0, f.visible), more: options.slice(f.visible) });
  }
  return groups;
}

function option(field: string, v: FacetValue, tags: readonly string[], envs: Readonly<Record<string, Env>>): FacetOption {
  const tag = `${field}:${v.name}`;
  const base = { value: v.name, tag, count: v.count, checked: tags.includes(tag), icon: null, iconTone: null, mono: false, env: null, label: v.name };
  switch (field) {
    case 'decision': {
      const look = decisionLook(v.name);
      return { ...base, label: look.text, icon: look.icon, iconTone: FG[look.tone] ?? null };
    }
    case 'client':
      return { ...base, label: v.name === 'claude-ai' ? 'claude.ai' : clientName(v.name) };
    case 'connection':
      return { ...base, mono: true, env: envs[v.name] ?? null };
    case 'env':
      return { ...base, env: (ENV_ORDER.includes(v.name) ? v.name : null) as Env | null };
    default:
      return { ...base, mono: true };
  }
}

/** "1,287 calls", "6 of 1,287 calls", "4 calls · new calls appear at the top". */
export function countText(matched: number | null, total: number | null, filtered: boolean, live: boolean): string {
  const n = matched ?? total ?? 0;
  const calls = (x: number) => `${formatCount(x)} ${x === 1 ? 'call' : 'calls'}`;
  if (live) return `${calls(n)} · new calls appear at the top`;
  if (filtered && total != null) return `${formatCount(n)} of ${calls(total)}`;
  return calls(n);
}

/**
 * The count line of the Activity page (pages/activity.html `#ac-count`): "1,287 calls · 3 waiting for approval"
 * without filters, "6 of 1,287 calls" with them, "166 of 1,287 calls from 11:00 to 12:00" in a zoomed hour, and
 * "Loading calls" until the core has answered (the line is `role=status`: it never says "0 calls" by mistake).
 */
export function pageCountText(o: {
  readonly matched: number | null;
  readonly total: number | null;
  readonly waiting: number | null;
  readonly filtered: boolean;
  readonly live: boolean;
  /** The zoomed bar's clock times ("11:00", "12:00"), or null. */
  readonly zoom: { readonly from: string; readonly to: string } | null;
}): string {
  if (o.matched == null && o.total == null) return 'Loading calls';
  const calls = (x: number) => `${formatCount(x)} ${x === 1 ? 'call' : 'calls'}`;
  const total = o.total ?? o.matched ?? 0;
  if (o.live) return `${calls(o.filtered || o.zoom ? (o.matched ?? total) : total)} · new calls appear at the top`;
  if (o.filtered || o.zoom) {
    const of = `${formatCount(o.matched ?? 0)} of ${calls(total)}`;
    return o.zoom ? `${of} from ${o.zoom.from} to ${o.zoom.to}` : of;
  }
  return o.waiting ? `${calls(total)} · ${formatCount(o.waiting)} waiting for approval` : calls(total);
}

// ── The histogram ──────────────────────────────────────────────────────────────────────────────────────────────────

export type Series = 'success' | 'warning' | 'danger' | 'neutral';

export const SERIES: readonly { readonly key: Series; readonly label: string; readonly decisions: readonly string[] }[] = [
  { key: 'success', label: 'Allowed or approved', decisions: ['allowed', 'approved'] },
  { key: 'warning', label: 'Waiting for approval', decisions: ['pending'] },
  { key: 'danger', label: 'Denied, rejected or failed', decisions: ['denied', 'rejected', 'error'] },
  { key: 'neutral', label: 'Expired', decisions: ['expired'] },
];

export function seriesCounts(b: Pick<HistogramBucket, 'decisions'>): Record<Series, number> {
  const out = { success: 0, warning: 0, danger: 0, neutral: 0 } as Record<Series, number>;
  for (const s of SERIES) for (const d of s.decisions) out[s.key] += b.decisions[d] ?? 0;
  return out;
}

/**
 * The top of the scale: 1, 2, 4 or 5 times a power of ten, at or above the highest bar (166 → 200, 3 → 4), so the
 * half-way gridline is a whole number too (5 → 6).
 */
export function niceMax(max: number): number {
  if (max <= 0) return 2;
  const p = 10 ** Math.floor(Math.log10(max));
  let top = 10 * p;
  for (const m of [1, 2, 4, 5, 10]) {
    if (m * p >= max) {
      top = m * p;
      break;
    }
  }
  return top % 2 === 0 ? top : top + 1;
}

/**
 * The top of a scale with four gridlines, as the Activity page draws its histogram (50 · 100 · 150 · 200 for a peak
 * of 166): four whole quarters of 1, 2, 2.5 or 5 times a power of ten (4, 8, 20, 40, 80, 100, 200…), at or above the
 * highest bar.
 */
export function niceQuarterMax(max: number): number {
  for (let p = 1; p < 1e15; p *= 10) {
    for (const q of [1, 2, 2.5, 5]) {
      const quarter = q * p;
      if (Number.isInteger(quarter) && 4 * quarter >= max) return 4 * quarter;
    }
  }
  return 4 * Math.ceil(max / 4);
}

export interface Bar {
  readonly from: string;
  readonly to: string;
  readonly label: string;
  readonly total: number;
  readonly segments: { readonly key: Series; readonly v: number }[];
  readonly counts: Record<Series, number>;
  readonly hour: string;
}

export interface HistogramView {
  readonly bars: Bar[];
  readonly scale: number;
  readonly gridlines: { readonly v: number; readonly label: string }[];
  readonly axis: { readonly v: number; readonly label: string }[];
  readonly aria: string;
  readonly unit: string;
}

function unitOf(stepHours: number): string {
  if (stepHours === 1) return 'hour';
  if (stepHours === 24) return 'day';
  if (stepHours < 1) return `${Math.round(stepHours * 60)} minutes`;
  return `${stepHours} hours`;
}

/**
 * Bars, scale, axis labels and the chart's accessible summary. `toNow`: the last bar ends "now". `quarters`: four
 * gridlines (the Activity page) instead of the element's two.
 */
export function histogramView(h: Histogram, toNow: boolean, now: number, quarters = false): HistogramView {
  const n = h.buckets.length;
  const max = Math.max(0, ...h.buckets.map((b) => b.total));
  const scale = quarters ? niceQuarterMax(max) : niceMax(max);
  const bars = h.buckets.map((b, i) => {
    const counts = seriesCounts(b);
    const last = i === n - 1 && toNow;
    const span = `${clockTime(b.from, false)}–${last ? 'now' : clockTime(b.to, false)}`;
    const parts = [b.total === 0 ? 'no calls' : `${formatCount(b.total)} ${b.total === 1 ? 'call' : 'calls'}`];
    if (counts.warning) parts.push(`${counts.warning} waiting for approval`);
    if (counts.danger) parts.push(`${counts.danger} denied, rejected or failed`);
    if (counts.neutral) parts.push(`${counts.neutral} expired`);
    return {
      from: b.from,
      to: b.to,
      label: `${span}, ${parts.join(', ')}`,
      total: b.total,
      counts,
      hour: clockTime(b.from, false),
      segments: SERIES.filter((s) => counts[s.key] > 0).map((s) => ({ key: s.key, v: counts[s.key] / scale })),
    };
  });
  const from = Date.parse(h.from);
  const to = Date.parse(h.to);
  const axis = [0, 0.25, 0.5, 0.75, 1].map((v) => {
    if (v === 1 && toNow) return { v, label: 'Now' };
    const i = Math.min(n - 1, Math.round(v * n));
    const at = n ? (v === 1 ? h.buckets[n - 1].to : h.buckets[Math.max(0, i)].from) : new Date(from + v * (to - from)).toISOString();
    return { v, label: clockTime(at, false) };
  });
  const total = h.buckets.reduce((s, b) => s + b.total, 0);
  const peak = bars.reduce<Bar | null>((p, b) => (!p || b.total > p.total ? b : p), null);
  const startDay = shortDate(from, now);
  const start = `${clockTime(n ? h.buckets[0].from : h.from, false)}${startDay === 'Today' ? '' : startDay === shortDate(now - 86_400_000, now) ? ' yesterday' : ` on ${startDay}`}`;
  const unit = unitOf(h.stepHours);
  const aria = `Calls per ${unit} from ${start} to ${toNow ? 'now' : clockTime(h.to, false)}: ${formatCount(total)} ${total === 1 ? 'call' : 'calls'}${peak && peak.total ? `, peak of ${formatCount(peak.total)} at ${peak.hour}` : ''}. Select ${unit === 'hour' ? 'an hour' : 'a bar'} to zoom into it.`;
  return {
    bars,
    scale,
    gridlines: (quarters ? [0.25, 0.5, 0.75, 1] : [0.5, 1]).map((v) => ({ v, label: formatCount(scale * v) })),
    axis,
    aria,
    unit,
  };
}

/** The legend's numbers: the whole range, or the zoomed bar. */
export function legendCounts(h: Histogram | null, zoom: string | null): Record<Series, number> {
  const out = { success: 0, warning: 0, danger: 0, neutral: 0 } as Record<Series, number>;
  for (const b of h?.buckets ?? []) {
    if (zoom && b.from !== zoom) continue;
    const c = seriesCounts(b);
    for (const s of SERIES) out[s.key] += c[s.key];
  }
  return out;
}
