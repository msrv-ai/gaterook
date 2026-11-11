import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ApiError } from '../../core/api/api-error';
import { OverviewApi, OverviewSummary } from '../../core/api/overview.api';
import { EventStream } from '../../core/events/event-stream';
import { CoreStore } from '../../core/stores/core.store';
import { provideKitIcons } from '../../kit/icons';
import { busySummary, summary } from './overview.fixtures';
import { GwOverviewSummary, SUMMARY_REFRESH_MS } from './overview-summary';

/** Overview's "Last 30 days" against a fake core: what it shows in each state, when it reads again, when it hides. */

class FakeEvents {
  readonly handlers = new Map<string, Set<(data: unknown) => void>>();
  on(type: string, h: (data: unknown) => void): () => void {
    if (!this.handlers.has(type)) this.handlers.set(type, new Set());
    this.handlers.get(type)!.add(h);
    return () => this.handlers.get(type)!.delete(h);
  }
  emit(type: string, data: unknown = {}): void {
    this.handlers.get(type)?.forEach((h) => h(data));
  }
}

let answers: (() => Promise<OverviewSummary>)[];
let zones: (string | null | undefined)[];
let events: FakeEvents;
let instance: ReturnType<typeof signal<number>>;

function mount(...next: (() => Promise<OverviewSummary>)[]) {
  answers = next;
  zones = [];
  events = new FakeEvents();
  instance = signal(0);
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      provideKitIcons(),
      {
        provide: OverviewApi,
        useValue: {
          summary: (zone?: string | null) => {
            zones.push(zone);
            return (answers.length > 1 ? answers.shift()! : answers[0])();
          },
        } satisfies Partial<OverviewApi>,
      },
      { provide: EventStream, useValue: events },
      { provide: CoreStore, useValue: { running: signal(true), instanceChanges: instance } },
    ],
  });
  const fixture = TestBed.createComponent(GwOverviewSummary);
  document.body.appendChild(fixture.nativeElement);
  return fixture;
}

async function settle(fixture: ReturnType<typeof mount>): Promise<HTMLElement> {
  await fixture.whenStable();
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

const text = (el: Element | null) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();

afterEach(() => {
  vi.useRealTimers();
  document.body.innerHTML = '';
});

describe('Last 30 days', () => {
  it('shows the four numbers and both charts, asking for the days of this computer’s zone', async () => {
    const el = await settle(mount(() => Promise.resolve(busySummary())));

    expect(zones).toEqual([Intl.DateTimeFormat().resolvedOptions().timeZone]);
    expect(text(el.querySelector('#ov-month-title'))).toBe('Last 30 days');
    const stats = [...el.querySelectorAll('#ov-month-stats .gw-stat')].map((s) => text(s));
    expect(stats).toEqual([
      'Calls 1,606 About 54 a day',
      'Denied by policy 13 1% of calls · 2 rejected by you',
      'Traffic in 165 KB Sent by AI apps with their calls',
      'Traffic out 4.1 MB Returned to AI apps',
    ]);
    const charts = [...el.querySelectorAll('.gw-chart[role="img"]')];
    expect(charts.map((c) => c.getAttribute('aria-label'))).toEqual([
      expect.stringContaining('Calls per day, last 30 days: 1,606 calls'),
      expect.stringContaining('Traffic per day, last 30 days: 4.1 MB out to AI apps, 165 KB in from them.'),
    ]);
    for (const chart of charts) expect(chart.querySelectorAll('.gw-chart__bar')).toHaveLength(30);
    const traffic = el.querySelector('#ov-month-traffic')!.closest('.gw-card')!;
    expect(text(traffic.querySelector('.gw-card__description'))).toBe('4.1 MB went back to AI apps; they sent 165 KB with their calls.');
    expect([...traffic.querySelectorAll('.gw-legend__item')].map(text)).toEqual(['Out, to AI apps 4.1 MB', 'In, from AI apps 165 KB']);
    expect(traffic.querySelector('[title="Oct 5: 3.3 MB out, 117 KB in"] .gw-chart__segment--series-1')).not.toBeNull();
    // The numbers of every bar, for a screen reader.
    expect(traffic.querySelectorAll('table tbody tr')).toHaveLength(30);
    expect(text(traffic.querySelector('table caption'))).toBe('Traffic in and out per day, last 30 days');
    expect(el.querySelector('a[href="/insights?range=30d"]')).not.toBeNull();
  });

  it('before the first call and in a quiet month, it says so instead of empty charts', async () => {
    let el = await settle(mount(() => Promise.resolve(summary())));
    expect(text(el.querySelector('#ov-month-empty'))).toContain('No calls yet');
    expect(el.querySelector('.gw-chart')).toBeNull();
    TestBed.resetTestingModule();
    el = await settle(mount(() => Promise.resolve(summary({}, { firstCallAt: '2026-06-01T08:00:00Z' }))));
    expect(text(el.querySelector('#ov-month-empty'))).toContain('No calls in the last 30 days');
    expect(el.querySelector('#ov-month-empty a[href="/activity"]')).not.toBeNull();
  });

  it('a failed first read offers Try again; a later failure keeps the numbers and says they may be old', async () => {
    const failed = () => Promise.reject(new ApiError('server', 500, { title: 'Failed', detail: 'The audit could not be read.' }));
    const fixture = mount(failed, () => Promise.resolve(busySummary()), failed);
    let el = await settle(fixture);
    expect(text(el.querySelector('[role="alert"]'))).toContain("The last 30 days didn't load The audit could not be read.");

    (el.querySelector('[role="alert"] button') as HTMLButtonElement).click();
    el = await settle(fixture);
    expect(el.querySelector('[role="alert"]')).toBeNull();
    expect(el.querySelectorAll('#ov-month-stats .gw-stat')).toHaveLength(4);

    await fixture.componentInstance.load();
    el = await settle(fixture);
    expect(text(el.querySelector('[role="status"]'))).toContain('These numbers may be out of date.');
    expect(el.querySelectorAll('#ov-month-stats .gw-stat')).toHaveLength(4);
  });

  it('a core without the summary leaves the section out, until a restarted core has it', async () => {
    const fixture = mount(() => Promise.reject(new ApiError('not-available', 404, {})), () => Promise.resolve(busySummary()));
    let el = await settle(fixture);
    expect(el.querySelector('#ov-month')).toBeNull();
    events.emit('audit.call');
    expect(zones).toHaveLength(1);

    instance.set(1);
    fixture.detectChanges();
    el = await settle(fixture);
    expect(zones).toHaveLength(2);
    expect(el.querySelectorAll('#ov-month-stats .gw-stat')).toHaveLength(4);
  });

  it('reads again a minute after calls, once for a burst, and after the core restarted', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    const fixture = mount(() => Promise.resolve(busySummary()));
    fixture.detectChanges();
    await vi.advanceTimersByTimeAsync(0);
    expect(zones).toHaveLength(1);

    events.emit('audit.call');
    events.emit('audit.call');
    await vi.advanceTimersByTimeAsync(SUMMARY_REFRESH_MS - 1);
    expect(zones).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(zones).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(SUMMARY_REFRESH_MS);
    expect(zones).toHaveLength(2);

    instance.set(1);
    fixture.detectChanges();
    await vi.advanceTimersByTimeAsync(0);
    expect(zones).toHaveLength(3);
  });

  it('turns the day at midnight without calls', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    // The fixtures' summary was made at 14:30 on October 22, Berlin: the day ends 9 h 30 min later.
    const fixture = mount(() => Promise.resolve(busySummary()));
    fixture.detectChanges();
    await vi.advanceTimersByTimeAsync(0);
    expect(zones).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(9.5 * 3_600_000);
    expect(zones).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(5_000);
    expect(zones).toHaveLength(2);
  });
});
