/**
 * Time as the kit writes it (BLUEPRINT §8, DESIGN §8 "Activity"): relative in lists ("2 min ago", "30 s ago",
 * "3 weeks ago"), absolute ISO in `title` and `datetime` ("2026-10-22 14:29:30 +02:00"), durations with units
 * ("38 ms", "2.4 s", "47 s", "5 min"). Pure functions of an instant and "now", so they are tested without clocks.
 */

const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/**
 * "just now", "30 s ago", "1 min ago", "2 h ago", "yesterday", "6 days ago", "41 days ago", "4 months ago"; future:
 * "in 2 h", "in 3 days". Like the kit's pages: within a few hours it counts time; past that it counts calendar days
 * (a call on the 16th at 18:12 is "6 days ago" on the 22nd at 14:30), says "yesterday" for the day before, and
 * counts days up to about six weeks ("41 days ago", not "5 weeks ago").
 */
export function relativeTime(at: string | number | Date, now: number): string {
  const t = toMillis(at);
  if (Number.isNaN(t)) return '';
  const diff = now - t;
  const future = diff < 0;
  const d = Math.abs(diff);
  if (d < 5 * SECOND) return 'just now';
  const days = Math.abs(calendarDays(t, now));
  let text: string;
  if (d < MINUTE) text = `${Math.floor(d / SECOND)} s`;
  else if (d < HOUR) text = `${Math.floor(d / MINUTE)} min`;
  else if (d < 6 * HOUR || days === 0) text = `${Math.floor(d / HOUR)} h`;
  else if (days === 1) return future ? 'tomorrow' : 'yesterday';
  else if (days < 45) text = plural(days, 'day');
  else if (d < 365 * DAY) text = plural(Math.round(d / (30 * DAY)), 'month');
  else text = plural(Math.floor(d / (365 * DAY)), 'year');
  return future ? `in ${text}` : `${text} ago`;
}

/** Whole local calendar days from `a` to `b` (positive when `b` is later). */
function calendarDays(a: number, b: number): number {
  const da = new Date(a);
  const db = new Date(b);
  const ua = Date.UTC(da.getFullYear(), da.getMonth(), da.getDate());
  const ub = Date.UTC(db.getFullYear(), db.getMonth(), db.getDate());
  return Math.round((ub - ua) / DAY);
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "Today", "Sep 25", "Oct 29, 2026" with `year`, "Sep 25, 2025" in another year: dates in tables (tokens, backups). */
export function shortDate(at: string | number | Date, now: number, year = false): string {
  const t = toMillis(at);
  if (Number.isNaN(t)) return '';
  const d = new Date(t);
  const n = new Date(now);
  if (!year && d.toDateString() === n.toDateString()) return 'Today';
  const text = `${MONTHS[d.getMonth()]} ${d.getDate()}`;
  return year || d.getFullYear() !== n.getFullYear() ? `${text}, ${d.getFullYear()}` : text;
}

/** The local wall clock with its offset: "2026-10-22 14:29:30 +02:00" (titles, details). */
export function absoluteTime(at: string | number | Date, withSeconds = true): string {
  const t = toMillis(at);
  if (Number.isNaN(t)) return '';
  const d = new Date(t);
  const date = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  const time = `${pad(d.getHours())}:${pad(d.getMinutes())}${withSeconds ? `:${pad(d.getSeconds())}` : ''}`;
  return `${date} ${time} ${offset(d)}`;
}

/** The `datetime` attribute: ISO 8601 with the local offset ("2026-10-22T14:29:30+02:00"). */
export function isoLocal(at: string | number | Date): string {
  const t = toMillis(at);
  if (Number.isNaN(t)) return '';
  const d = new Date(t);
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
    `T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}${offset(d)}`
  );
}

/** "14:29:30" (timelines, "Auto-deny at 14:34:12"). */
export function clockTime(at: string | number | Date, withSeconds = true): string {
  const t = toMillis(at);
  if (Number.isNaN(t)) return '';
  const d = new Date(t);
  return `${pad(d.getHours())}:${pad(d.getMinutes())}${withSeconds ? `:${pad(d.getSeconds())}` : ''}`;
}

/** "38 ms", "2.4 s", "47 s", "5 min", "1 h 12 min". */
export function duration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || Number.isNaN(ms)) return '';
  if (ms < SECOND) return `${Math.round(ms)} ms`;
  if (ms < 10 * SECOND) return `${trim((ms / SECOND).toFixed(1))} s`;
  if (ms < MINUTE) return `${Math.round(ms / SECOND)} s`;
  if (ms < HOUR) return `${Math.round(ms / MINUTE)} min`;
  const h = Math.floor(ms / HOUR);
  const m = Math.round((ms % HOUR) / MINUTE);
  return m ? `${h} h ${m} min` : `${h} h`;
}

/**
 * A wait, to the second, as the approval history writes it: "47 s", "1 min 49 s", "1 h 3 min" (`duration` rounds to
 * the minute; "after 1 min 49 s" must not read "after 2 min").
 */
export function elapsed(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || Number.isNaN(ms) || ms < 0) return '';
  const s = Math.round(ms / SECOND);
  if (s < 60) return `${s} s`;
  if (s < 3600) {
    const m = Math.floor(s / 60);
    return s % 60 ? `${m} min ${s % 60} s` : `${m} min`;
  }
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  return m ? `${h} h ${m} min` : `${h} h`;
}

/** A countdown as the kit's timer shows it: "4:12", "0:48" (whole seconds, never negative). */
export function countdown(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  return `${Math.floor(s / 60)}:${pad(s % 60)}`;
}

/**
 * How often something periodic runs, as the kit says it: "every minute" (connection health), "every 5 minutes",
 * "every hour", "every 90 seconds".
 */
export function everyInterval(seconds: number): string {
  const count = (n: number, one: string) => `${formatCount(n)} ${n === 1 ? one : `${one}s`}`;
  if (seconds > 0 && seconds % 3600 === 0) {
    return seconds === 3600 ? 'every hour' : `every ${count(seconds / 3600, 'hour')}`;
  }
  if (seconds > 0 && seconds % 60 === 0) {
    return seconds === 60 ? 'every minute' : `every ${count(seconds / 60, 'minute')}`;
  }
  return `every ${count(seconds, 'second')}`;
}

/** "1,284", "12 MB": thousands with commas, as the kit's numbers. */
export function formatCount(n: number | null | undefined): string {
  if (n === null || n === undefined || Number.isNaN(n)) return '';
  return n.toLocaleString('en-US');
}

export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes)) return '';
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let v = bytes / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i += 1;
  }
  return `${trim(v.toFixed(v < 10 ? 1 : 0))} ${units[i]}`;
}

function toMillis(at: string | number | Date): number {
  if (at instanceof Date) return at.getTime();
  if (typeof at === 'number') return at;
  return Date.parse(at);
}

function plural(n: number, unit: string): string {
  return `${n} ${unit}${n === 1 ? '' : 's'}`;
}

function pad(n: number): string {
  return n < 10 ? `0${n}` : String(n);
}

function trim(s: string): string {
  return s.endsWith('.0') ? s.slice(0, -2) : s;
}

function offset(d: Date): string {
  const minutes = -d.getTimezoneOffset();
  const sign = minutes >= 0 ? '+' : '-';
  const abs = Math.abs(minutes);
  return `${sign}${pad(Math.floor(abs / 60))}:${pad(abs % 60)}`;
}
