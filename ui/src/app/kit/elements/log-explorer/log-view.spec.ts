import { DEMO_CONNECTION_ENVS, DEMO_FACETS, DEMO_FACETS_NONE, DEMO_HISTOGRAM } from '../../../dev/fixtures-activity';
import {
  clearTags,
  commitInput,
  countText,
  facetGroups,
  hasTag,
  histogramView,
  legendCounts,
  niceMax,
  niceQuarterMax,
  pageCountText,
  parseQuery,
  rangeBounds,
  rangeLabel,
  rangeTitle,
  seriesCounts,
  tagTone,
  toggleTag,
} from './log-view';

describe('log-explorer view (design/elements/log-explorer.html)', () => {
  it('splits a query into filter tags and free text', () => {
    expect(parseQuery('decision:denied  env:prod sync -tool:sql_* decision:denied')).toEqual({
      tags: ['decision:denied', 'env:prod', '-tool:sql_*'],
      text: 'sync',
    });
    expect(commitInput('env:prod', 'client:codex slow')).toBe('env:prod client:codex slow');
    expect(toggleTag('env:prod', 'decision:denied', true)).toBe('env:prod decision:denied');
    expect(toggleTag('env:prod decision:denied', 'env:prod', false)).toBe('decision:denied');
    expect(hasTag('env:prod x', 'env:prod')).toBe(true);
    expect(clearTags('env:prod sync')).toBe('sync');
    expect(tagTone('env:prod')).toBe('prod');
    expect(tagTone('decision:denied')).toBeNull();
  });

  it('names ranges and their bounds', () => {
    const now = Date.parse('2026-10-22T14:30:00+02:00');
    expect(rangeLabel({ kind: 'relative', key: '24h' }, now)).toBe('Last 24 hours');
    const b = rangeBounds({ kind: 'relative', key: '1h' }, now);
    expect(b.to - b.from).toBe(3_600_000);
    expect(b.toNow).toBe(true);
    const z = { kind: 'custom' as const, from: new Date(2026, 9, 22, 10).toISOString(), to: new Date(2026, 9, 22, 11).toISOString() };
    expect(rangeLabel(z, new Date(2026, 9, 22, 14, 30).getTime())).toBe('Today 10:00–11:00');
    expect(rangeTitle(z, now, false)).toMatch(/^2026-10-22 10:00 – 11:00 [+-]\d\d:\d\d$/);
    expect(rangeTitle({ kind: 'relative', key: '1h' }, new Date(2026, 9, 22, 14, 30).getTime(), true)).toBe('2026-10-22 13:30 – now (live)');
  });

  it('builds facets: fixed order for decisions and environments, by count otherwise', () => {
    const groups = facetGroups(DEMO_FACETS, '', DEMO_CONNECTION_ENVS);
    expect(groups.map((g) => g.label)).toEqual(['Decision', 'Tool', 'AI app', 'Endpoint', 'Connection', 'Environment']);
    const decision = groups[0];
    expect(decision.shown.map((o) => o.label)).toEqual(['Allowed', 'Approved', 'Waiting for approval', 'Denied by policy', 'Rejected', 'Expired', 'Error']);
    expect(decision.shown[0].iconTone).toBe('gw-fg-success');
    const tool = groups[1];
    expect(tool.shown).toHaveLength(6);
    expect(tool.more).toHaveLength(5);
    const conn = groups[4];
    expect(conn.shown.map((o) => [o.value, o.env])).toContainEqual(['prod-logs', 'prod']);
    expect(groups[2].shown.map((o) => o.label)).toEqual(['Codex', 'Claude Desktop', 'claude.ai']);
  });

  it('keeps checked values at zero and hides other empty ones', () => {
    const groups = facetGroups(DEMO_FACETS_NONE, 'decision:expired env:prod client:cursor', {});
    expect(groups.map((g) => [g.key, g.shown.map((o) => [o.value, o.count, o.checked])])).toEqual([
      ['decision', [['expired', 0, true]]],
      ['client', [['cursor', 0, true]]],
      ['env', [['prod', 0, true]]],
    ]);
  });

  it('counts calls as the kit says it', () => {
    expect(countText(null, 1287, false, false)).toBe('1,287 calls');
    expect(countText(6, 1287, true, false)).toBe('6 of 1,287 calls');
    expect(countText(4, null, true, true)).toBe('4 calls · new calls appear at the top');
    expect(countText(1, null, false, false)).toBe('1 call');
  });

  it('draws the histogram by decision on a round scale', () => {
    expect([niceMax(166), niceMax(3), niceMax(5), niceMax(9), niceMax(0)]).toEqual([200, 4, 6, 10, 2]);
    const now = Date.parse('2026-10-22T14:30:00+02:00');
    const v = histogramView(DEMO_HISTOGRAM, true, now);
    expect(v.scale).toBe(200);
    expect(v.gridlines.map((g) => g.label)).toEqual(['100', '200']);
    expect(v.bars).toHaveLength(24);
    expect(v.axis.at(-1)!.label).toBe('Now');
    expect(v.bars.at(-1)!.label).toMatch(/–now, 86 calls, 3 waiting for approval, 2 denied, rejected or failed$/);
    expect(v.bars.find((b) => b.total === 0)!.label).toMatch(/, no calls$/);
    expect(v.aria).toMatch(/^Calls per hour from \d\d:\d\d yesterday to now: 1,287 calls, peak of 166 at \d\d:\d\d\. Select an hour to zoom into it\.$/);
    const peak = v.bars.find((b) => b.total === 166)!;
    expect(peak.segments.map((s) => s.key)).toEqual(['success', 'danger', 'neutral']);
    expect(peak.segments[0].v).toBeCloseTo(0.8);
  });

  it('legend counts the range, or only the zoomed bar', () => {
    expect(legendCounts(DEMO_HISTOGRAM, null)).toEqual({ success: 1247, warning: 3, danger: 34, neutral: 3 });
    expect(legendCounts(DEMO_HISTOGRAM, DEMO_HISTOGRAM.buckets[19].from)).toEqual({ success: 141, warning: 0, danger: 9, neutral: 0 });
    expect(seriesCounts({ decisions: { allowed: 2, approved: 1, rejected: 1, error: 1 } })).toEqual({ success: 3, warning: 0, danger: 2, neutral: 0 });
  });
});

describe('log-explorer page variant (design/pages/activity.html)', () => {
  it('says the count line as the Activity page does', () => {
    const o = { matched: null, total: null, waiting: 0, filtered: false, live: false, zoom: null };
    expect(pageCountText(o)).toBe('Loading calls');
    expect(pageCountText({ ...o, matched: 1287, total: 1287, waiting: 3 })).toBe('1,287 calls · 3 waiting for approval');
    expect(pageCountText({ ...o, matched: 1, total: 1 })).toBe('1 call');
    expect(pageCountText({ ...o, matched: 6, total: 1287, filtered: true })).toBe('6 of 1,287 calls');
    expect(pageCountText({ ...o, matched: 0, total: 9, filtered: true })).toBe('0 of 9 calls');
    expect(pageCountText({ ...o, matched: null, total: 9, filtered: true })).toBe('0 of 9 calls');
    expect(pageCountText({ ...o, matched: 150, total: 1287, zoom: { from: '11:00', to: '12:00' } })).toBe('150 of 1,287 calls from 11:00 to 12:00');
    expect(pageCountText({ ...o, matched: 4, total: 1287, live: true })).toBe('1,287 calls · new calls appear at the top');
    expect(pageCountText({ ...o, matched: 4, total: 1287, live: true, filtered: true })).toBe('4 calls · new calls appear at the top');
  });

  it('draws four gridlines on a scale of round quarters (kit: 50, 100, 150, 200)', () => {
    expect([0, 3, 4, 7, 9, 20, 33, 166, 200, 201, 1500].map(niceQuarterMax)).toEqual([4, 4, 4, 8, 20, 20, 40, 200, 200, 400, 2000]);
    const now = Date.parse('2026-10-22T14:30:00+02:00');
    const v = histogramView(DEMO_HISTOGRAM, true, now, true);
    expect(v.scale).toBe(200);
    expect(v.gridlines.map((g) => g.label)).toEqual(['50', '100', '150', '200']);
    expect(v.gridlines.map((g) => g.v)).toEqual([0.25, 0.5, 0.75, 1]);
  });
});
