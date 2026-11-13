import { OverviewSummary, SummaryDay } from '../../core/api/overview.api';
import { formatBytes, formatCount } from '../../core/time/time-format';
import { SERIES, Series, niceMax, seriesCounts } from '../../kit/elements/log-explorer/log-view';

/**
 * What Overview's "Last 30 days" shows, computed from `GET /api/overview/summary`: pure functions of the answer, so
 * the tests check the words and the bars without a clock or a core. Traffic is said from GateRook's side: "in" is what
 * AI apps sent with their calls, "out" what went back to them.
 */

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

const SERIES_KEYS: readonly Series[] = ['success', 'warning', 'danger', 'neutral'];

const calls = (n: number) => `${formatCount(n)} ${n === 1 ? 'call' : 'calls'}`;

/** What the section shows: no call has ever reached the gateway, none in these 30 days, or the numbers. */
export type SummaryState = 'first-run' | 'quiet' | 'numbers';

export function summaryState(s: OverviewSummary): SummaryState {
  if (s.totals.calls > 0) return 'numbers';
  return s.firstCallAt ? 'quiet' : 'first-run';
}

/** A date of the summary as the axis and the tables say it: "Today", "Nov 3" (dates in the person's zone, as sent). */
export function dayLabel(date: string, today: string): string {
  if (date === today) return 'Today';
  const [, month, day] = date.split('-').map(Number);
  return month >= 1 && month <= 12 && day ? `${MONTHS[month - 1]} ${day}` : date;
}

/** "4.2 MB" as the tiles set it: the number, and its unit smaller beside it. */
export function bytesParts(bytes: number): { readonly value: string; readonly unit: string } {
  const [value, unit] = formatBytes(bytes).split(' ');
  return { value, unit: unit ?? '' };
}

/**
 * The top of a byte scale and whether its quarters are whole: the next 1, 2, 4, 5 or 10 times a power of ten in the
 * unit the busiest day is said in (a day of 180 KB → 200 KB, quarters of 50 KB; 3.1 MB → 4 MB).
 */
export function bytesScale(max: number): { readonly top: number; readonly quarters: boolean } {
  let unit = 1;
  while (max / unit >= 1024 && unit < 1024 ** 4) unit *= 1024;
  const top = niceMax(max / unit);
  return { top: top * unit, quarters: top % 4 === 0 };
}

// ── Tiles ────────────────────────────────────────────────────────────────────────────────────────────────────────

export interface SummaryTile {
  readonly id: 'calls' | 'denied' | 'in' | 'out';
  readonly label: string;
  readonly value: string;
  readonly unit: string | null;
  readonly meta: string;
}

/** The four numbers of the 30 days: calls, denied by policy, traffic in and out. */
export function summaryTiles(s: OverviewSummary): SummaryTile[] {
  const total = s.totals.calls;
  const perDay = Math.round(total / s.days.length);
  const active = s.days.filter((d) => d.calls > 0).length;
  const denied = s.totals.decisions['denied'] ?? 0;
  const rejected = s.totals.decisions['rejected'] ?? 0;
  const sent = bytesParts(s.totals.requestBytes);
  const back = bytesParts(s.totals.resultBytes);
  return [
    {
      id: 'calls',
      label: 'Calls',
      value: formatCount(total),
      unit: null,
      meta: perDay >= 1 ? `About ${formatCount(perDay)} a day` : `On ${active} of ${s.days.length} days`,
    },
    {
      id: 'denied',
      label: 'Denied by policy',
      value: formatCount(denied),
      unit: null,
      meta: [`${share(denied, total)} of calls`, ...(rejected ? [`${formatCount(rejected)} rejected by you`] : [])].join(' · '),
    },
    { id: 'in', label: 'Traffic in', value: sent.value, unit: sent.unit, meta: 'Sent by AI apps with their calls' },
    { id: 'out', label: 'Traffic out', value: back.value, unit: back.unit, meta: 'Returned to AI apps' },
  ];
}

/** "18%", "<1%": never "0%" for something that happened. */
function share(part: number, whole: number): string {
  if (whole <= 0) return '0%';
  const p = Math.round((part / whole) * 100);
  return p === 0 && part > 0 ? '<1%' : `${p}%`;
}

// ── Charts ───────────────────────────────────────────────────────────────────────────────────────────────────────

/** A segment of a bar: `key` names its kit class (`gw-chart__segment--success`, `--series-1`), `v` its height. */
export interface DaySegment {
  readonly key: string;
  readonly v: number;
}

/** A static bar chart of the 30 days (kit `gw-chart`), its legend, and its numbers for a screen reader. */
export interface DayChart {
  /** One per day, oldest first; `title` is the day's numbers on hover. */
  readonly bars: readonly { readonly date: string; readonly title: string; readonly segments: readonly DaySegment[] }[];
  readonly gridlines: readonly { readonly v: number; readonly label: string }[];
  readonly axis: readonly { readonly v: number; readonly label: string }[];
  readonly legend: readonly { readonly key: string; readonly label: string; readonly value: string }[];
  /** The table's columns after the day. */
  readonly columns: readonly string[];
  readonly rows: readonly { readonly label: string; readonly cells: readonly string[] }[];
  /** The chart in words (`role="img"`). */
  readonly aria: string;
  /** The conclusion under the card's title. */
  readonly description: string;
}

/** Five dates under the bars: the first day, three between, and today under the last bar. */
function axisOf(days: readonly SummaryDay[], today: string): DayChart['axis'] {
  const n = days.length;
  return [0, 0.25, 0.5, 0.75, 1].map((v) => ({ v, label: dayLabel(days[Math.min(n - 1, Math.round(v * (n - 1)))].date, today) }));
}

function peakOf(days: readonly SummaryDay[], value: (d: SummaryDay) => number): { readonly day: SummaryDay; readonly value: number } | null {
  let best: { day: SummaryDay; value: number } | null = null;
  for (const d of days) if (value(d) > 0 && (!best || value(d) > best.value)) best = { day: d, value: value(d) };
  return best;
}

/** Calls per day, stacked by what they came to (Insights' four series and colours). */
export function callsChart(s: OverviewSummary): DayChart {
  const today = s.days.at(-1)?.date ?? '';
  const scale = niceMax(Math.max(0, ...s.days.map((d) => d.calls)));
  const quarters = scale % 4 === 0;
  const sums = { success: 0, warning: 0, danger: 0, neutral: 0 } as Record<Series, number>;
  const bars = s.days.map((d) => {
    const counts = seriesCounts(d);
    for (const k of SERIES_KEYS) sums[k] += counts[k];
    return {
      date: d.date,
      title: `${dayLabel(d.date, today)}: ${calls(d.calls)}`,
      segments: SERIES_KEYS.filter((k) => counts[k] > 0).map((k) => ({ key: k, v: counts[k] / scale })),
    };
  });
  const peak = peakOf(s.days, (d) => d.calls);
  const shown = SERIES.filter((x) => x.key === 'success' || sums[x.key] > 0);
  const outcomes = shown.map((x) => `${formatCount(sums[x.key])} ${x.label.toLowerCase()}`).join(', ');
  const peakWords = peak ? `${dayLabel(peak.day.date, today)}, with ${calls(peak.value)}` : null;
  return {
    bars,
    gridlines: (quarters ? [0.25, 0.5, 0.75, 1] : [0.5, 1]).map((v) => ({ v, label: formatCount(scale * v) })),
    axis: axisOf(s.days, today),
    legend: shown.map((x) => ({ key: x.key, label: x.label, value: formatCount(sums[x.key]) })),
    columns: [...SERIES.map((x) => x.label), 'Total'],
    rows: s.days.map((d) => {
      const counts = seriesCounts(d);
      return { label: dayLabel(d.date, today), cells: [...SERIES.map((x) => formatCount(counts[x.key])), formatCount(d.calls)] };
    }),
    aria: `Calls per day, last 30 days: ${calls(s.totals.calls)}, ${outcomes}.${peakWords ? ` Busiest day ${peakWords}.` : ''}`,
    description: !peakWords
      ? 'No calls in the last 30 days.'
      : sums.danger === 0
        ? `Busiest day ${peakWords}. Nothing was denied or failed.`
        : `Busiest day ${peakWords}.`,
  };
}

/** Traffic per day: what went back to AI apps (out) under what they sent (in). */
export function trafficChart(s: OverviewSummary): DayChart {
  const today = s.days.at(-1)?.date ?? '';
  const total = (d: SummaryDay) => d.requestBytes + d.resultBytes;
  const { top, quarters } = bytesScale(Math.max(0, ...s.days.map(total)));
  const bars = s.days.map((d) => ({
    date: d.date,
    title: `${dayLabel(d.date, today)}: ${formatBytes(d.resultBytes)} out, ${formatBytes(d.requestBytes)} in`,
    segments: [
      { key: 'series-1', v: d.resultBytes / top },
      { key: 'series-2', v: d.requestBytes / top },
    ].filter((x) => x.v > 0),
  }));
  const peak = peakOf(s.days, total);
  const out = formatBytes(s.totals.resultBytes);
  const sent = formatBytes(s.totals.requestBytes);
  const any = s.totals.resultBytes + s.totals.requestBytes > 0;
  return {
    bars,
    gridlines: (quarters ? [0.25, 0.5, 0.75, 1] : [0.5, 1]).map((v) => ({ v, label: formatBytes(top * v) })),
    axis: axisOf(s.days, today),
    legend: [
      { key: 'series-1', label: 'Out, to AI apps', value: out },
      { key: 'series-2', label: 'In, from AI apps', value: sent },
    ],
    columns: ['In', 'Out'],
    rows: s.days.map((d) => ({ label: dayLabel(d.date, today), cells: [formatBytes(d.requestBytes), formatBytes(d.resultBytes)] })),
    aria: `Traffic per day, last 30 days: ${out} out to AI apps, ${sent} in from them.${peak ? ` Busiest day ${dayLabel(peak.day.date, today)}, with ${formatBytes(peak.value)}.` : ''}`,
    description: any ? `${out} went back to AI apps; they sent ${sent} with their calls.` : 'No traffic in the last 30 days.',
  };
}
