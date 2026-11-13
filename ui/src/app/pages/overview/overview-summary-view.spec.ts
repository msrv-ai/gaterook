import { busySummary, summary } from './overview.fixtures';
import { bytesParts, bytesScale, callsChart, dayLabel, summaryState, summaryTiles, trafficChart } from './overview-summary-view';

/** Overview's last 30 days from the core's summary: the state, the four numbers, both charts in bars and in words. */

describe('Last 30 days: state', () => {
  it('is first run before any call, quiet without calls in these days, else the numbers', () => {
    expect(summaryState(summary())).toBe('first-run');
    expect(summaryState(summary({}, { firstCallAt: '2026-06-01T10:00:00Z' }))).toBe('quiet');
    expect(summaryState(busySummary())).toBe('numbers');
  });
});

describe('Last 30 days: words', () => {
  it('says a date of the person’s zone as sent, today as Today', () => {
    expect(dayLabel('2026-10-05', '2026-10-22')).toBe('Oct 5');
    expect(dayLabel('2026-09-23', '2026-10-22')).toBe('Sep 23');
    expect(dayLabel('2026-10-22', '2026-10-22')).toBe('Today');
    expect(dayLabel('not a date', '2026-10-22')).toBe('not a date');
  });

  it('splits a size into its number and its unit', () => {
    expect(bytesParts(169_048)).toEqual({ value: '165', unit: 'KB' });
    expect(bytesParts(4_251_200)).toEqual({ value: '4.1', unit: 'MB' });
    expect(bytesParts(0)).toEqual({ value: '0', unit: 'B' });
  });

  it('tops a byte scale in the unit of the busiest day', () => {
    expect(bytesScale(180 * 1024)).toEqual({ top: 200 * 1024, quarters: true });
    expect(bytesScale(3.1 * 1024 * 1024)).toEqual({ top: 4 * 1024 * 1024, quarters: true });
    expect(bytesScale(4.5 * 1024 * 1024)).toEqual({ top: 6 * 1024 * 1024, quarters: false });
    expect(bytesScale(0)).toEqual({ top: 2, quarters: false });
  });
});

describe('Last 30 days: the four numbers', () => {
  it('calls, denied by policy with its share and your rejections, traffic in and out', () => {
    expect(summaryTiles(busySummary())).toEqual([
      { id: 'calls', label: 'Calls', value: '1,606', unit: null, meta: 'About 54 a day' },
      { id: 'denied', label: 'Denied by policy', value: '13', unit: null, meta: '1% of calls · 2 rejected by you' },
      { id: 'in', label: 'Traffic in', value: '165', unit: 'KB', meta: 'Sent by AI apps with their calls' },
      { id: 'out', label: 'Traffic out', value: '4.1', unit: 'MB', meta: 'Returned to AI apps' },
    ]);
  });

  it('a quiet month counts its active days and never says 0% for a denial that happened', () => {
    const s = summary({ '2026-10-01': { decisions: { denied: 1, allowed: 300 } }, '2026-10-02': { decisions: { allowed: 1 } } });
    const [calls, denied] = summaryTiles(s);
    expect(calls.meta).toBe('About 10 a day');
    expect(denied.meta).toBe('<1% of calls');
    const few = summaryTiles(summary({ '2026-10-01': { decisions: { allowed: 2 } }, '2026-10-09': { decisions: { allowed: 3 } } }));
    expect(few[0].meta).toBe('On 2 of 30 days');
    expect(few[1].meta).toBe('0% of calls');
  });
});

describe('Last 30 days: calls per day', () => {
  const ch = callsChart(busySummary());

  it('draws 30 bars by decision on a scale with whole quarters', () => {
    expect(ch.bars).toHaveLength(30);
    expect(ch.gridlines.map((g) => g.label)).toEqual(['500', '1,000', '1,500', '2,000']);
    const busiest = ch.bars.find((b) => b.date === '2026-10-05')!;
    expect(busiest.title).toBe('Oct 5: 1,240 calls');
    expect(busiest.segments).toEqual([
      { key: 'success', v: 1_226 / 2_000 },
      { key: 'danger', v: 14 / 2_000 },
    ]);
    expect(ch.bars[0].segments).toEqual([{ key: 'success', v: 40 / 2_000 }]);
    expect(ch.bars[1].segments).toEqual([]);
    expect(ch.axis.map((a) => a.label)).toEqual(['Sep 23', 'Sep 30', 'Oct 8', 'Oct 15', 'Today']);
  });

  it('has the legend, the table and the conclusion in words', () => {
    expect(ch.legend).toEqual([
      { key: 'success', label: 'Allowed or approved', value: '1,584' },
      { key: 'warning', label: 'Waiting for approval', value: '2' },
      { key: 'danger', label: 'Denied, rejected or failed', value: '19' },
      { key: 'neutral', label: 'Expired', value: '1' },
    ]);
    expect(ch.columns).toEqual(['Allowed or approved', 'Waiting for approval', 'Denied, rejected or failed', 'Expired', 'Total']);
    expect(ch.rows[12]).toEqual({ label: 'Oct 5', cells: ['1,226', '0', '14', '0', '1,240'] });
    expect(ch.rows[29].label).toBe('Today');
    expect(ch.description).toBe('Busiest day Oct 5, with 1,240 calls.');
    expect(ch.aria).toBe(
      'Calls per day, last 30 days: 1,606 calls, 1,584 allowed or approved, 2 waiting for approval, 19 denied, rejected or failed, 1 expired. Busiest day Oct 5, with 1,240 calls.',
    );
  });

  it('a month without a failure says so, and leaves out series that never happened', () => {
    const calm = callsChart(summary({ '2026-10-10': { decisions: { allowed: 7 } } }));
    expect(calm.description).toBe('Busiest day Oct 10, with 7 calls. Nothing was denied or failed.');
    expect(calm.legend.map((l) => l.label)).toEqual(['Allowed or approved']);
    expect(callsChart(summary()).description).toBe('No calls in the last 30 days.');
  });
});

describe('Last 30 days: traffic per day', () => {
  const ch = trafficChart(busySummary());

  it('draws what went back under what came in, on a byte scale', () => {
    expect(ch.gridlines.map((g) => g.label)).toEqual(['1 MB', '2 MB', '3 MB', '4 MB']);
    const busiest = ch.bars.find((b) => b.date === '2026-10-05')!;
    expect(busiest.title).toBe('Oct 5: 3.3 MB out, 117 KB in');
    expect(busiest.segments).toEqual([
      { key: 'series-1', v: 3_500_000 / (4 * 1024 * 1024) },
      { key: 'series-2', v: 120_000 / (4 * 1024 * 1024) },
    ]);
    expect(ch.bars[1].segments).toEqual([]);
    expect(ch.axis.at(-1)!.label).toBe('Today');
  });

  it('has the legend, the table and the conclusion in words', () => {
    expect(ch.legend).toEqual([
      { key: 'series-1', label: 'Out, to AI apps', value: '4.1 MB' },
      { key: 'series-2', label: 'In, from AI apps', value: '165 KB' },
    ]);
    expect(ch.columns).toEqual(['In', 'Out']);
    expect(ch.rows[29]).toEqual({ label: 'Today', cells: ['2 KB', '50 KB'] });
    expect(ch.description).toBe('4.1 MB went back to AI apps; they sent 165 KB with their calls.');
    expect(ch.aria).toBe('Traffic per day, last 30 days: 4.1 MB out to AI apps, 165 KB in from them. Busiest day Oct 5, with 3.5 MB.');
  });

  it('says when nothing went through', () => {
    const none = trafficChart(summary({ '2026-10-10': { decisions: { denied: 1 } } }));
    expect(none.description).toBe('No traffic in the last 30 days.');
    expect(none.bars.every((b) => b.segments.length === 0)).toBe(true);
  });
});
