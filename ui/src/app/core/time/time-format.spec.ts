import {
  absoluteTime,
  clockTime,
  countdown,
  duration,
  elapsed,
  everyInterval,
  formatBytes,
  formatCount,
  isoLocal,
  relativeTime,
  shortDate,
} from './time-format';

/** Local wall-clock instants, so the tests hold in any time zone. */
const at = (d: number, h: number, m = 0, s = 0) => new Date(2026, 9, d, h, m, s).getTime();
const NOW = at(22, 14, 30);

describe('time-format (BLUEPRINT §8 time as the kit writes it)', () => {
  it.each([
    [at(22, 14, 29, 58), 'just now'],
    [at(22, 14, 29, 30), '30 s ago'],
    [at(22, 14, 29), '1 min ago'],
    [at(22, 14, 26), '4 min ago'],
    [at(22, 11, 58), '2 h ago'],
    [at(22, 0, 10), '14 h ago'],
    [at(21, 8, 0), 'yesterday'],
    [at(16, 18, 12), '6 days ago'],
    [new Date(2026, 8, 11, 9).getTime(), '41 days ago'],
    [new Date(2026, 5, 1).getTime(), '5 months ago'],
    [new Date(2024, 5, 1).getTime(), '2 years ago'],
  ])('relative %s → %s', (t, text) => {
    expect(relativeTime(t, NOW)).toBe(text);
  });

  it('says "in …" for the future', () => {
    expect(relativeTime(at(22, 16, 30), NOW)).toBe('in 2 h');
    expect(relativeTime(at(23, 20), NOW)).toBe('tomorrow');
    expect(relativeTime(at(25, 12), NOW)).toBe('in 3 days');
  });

  it('dates in tables', () => {
    expect(shortDate(at(22, 8), NOW)).toBe('Today');
    expect(shortDate(at(29, 8), NOW)).toBe('Oct 29');
    expect(shortDate(at(29, 8), NOW, true)).toBe('Oct 29, 2026');
    expect(shortDate(new Date(2025, 8, 25).getTime(), NOW)).toBe('Sep 25, 2025');
  });

  it('absolute times carry the local offset', () => {
    const t = at(22, 14, 29, 30);
    expect(absoluteTime(t)).toMatch(/^2026-10-22 14:29:30 [+-]\d\d:\d\d$/);
    expect(absoluteTime(t, false)).toMatch(/^2026-10-22 14:29 [+-]\d\d:\d\d$/);
    expect(isoLocal(t)).toMatch(/^2026-10-22T14:29:30[+-]\d\d:\d\d$/);
    expect(Date.parse(isoLocal(t))).toBe(t);
    expect(clockTime(t)).toBe('14:29:30');
    expect(clockTime(t, false)).toBe('14:29');
    expect(absoluteTime('not a date')).toBe('');
  });

  it.each([
    [38, '38 ms'],
    [999, '999 ms'],
    [2400, '2.4 s'],
    [2000, '2 s'],
    [47_000, '47 s'],
    [5 * 60_000, '5 min'],
    [72 * 60_000, '1 h 12 min'],
    [2 * 3_600_000, '2 h'],
  ])('duration %i → %s', (ms, text) => {
    expect(duration(ms)).toBe(text);
  });

  it('elapsed keeps the seconds a decision took', () => {
    expect(elapsed(47_000)).toBe('47 s');
    expect(elapsed(109_000)).toBe('1 min 49 s');
    expect(elapsed(300_194)).toBe('5 min');
    expect(elapsed(3_780_000)).toBe('1 h 3 min');
    expect(elapsed(-1)).toBe('');
    expect(duration(null)).toBe('');
  });

  it('countdowns, counts and sizes', () => {
    expect(countdown(252)).toBe('4:12');
    expect(countdown(48.9)).toBe('0:48');
    expect(countdown(-3)).toBe('0:00');
    expect(formatCount(1287)).toBe('1,287');
    expect(formatBytes(312)).toBe('312 B');
    expect(formatBytes(2458)).toBe('2.4 KB');
    expect(formatBytes(12 * 1024 * 1024)).toBe('12 MB');
  });

  it('says how often something periodic runs', () => {
    expect(everyInterval(60)).toBe('every minute');
    expect(everyInterval(300)).toBe('every 5 minutes');
    expect(everyInterval(3600)).toBe('every hour');
    expect(everyInterval(7200)).toBe('every 2 hours');
    expect(everyInterval(90)).toBe('every 90 seconds');
    expect(everyInterval(1)).toBe('every 1 second');
  });
});
