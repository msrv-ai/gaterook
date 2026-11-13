import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ApiError } from '../../core/api/api-error';
import { Insights, InsightsApi, InsightsQuery } from '../../core/api/insights.api';
import { EventStream } from '../../core/events/event-stream';
import { TimeService } from '../../core/time/time.service';
import { provideKitIcons } from '../../kit/icons';
import { GwConnectionCalls } from './connection-calls';
import { busyMonth, connectionMonth } from './connection-calls.fixtures';

/** The edit view's "Calls, last 30 days" against a fake Insights API, event stream and clock. */

const NOW = new Date(2026, 9, 22, 14, 30).getTime();
const DAY = 24 * 3_600_000;

class FakeEvents {
  readonly reopened = signal(0);
  private readonly handlers = new Map<string, Set<(data: unknown) => void>>();
  on(type: string, h: (data: unknown) => void): () => void {
    if (!this.handlers.has(type)) this.handlers.set(type, new Set());
    this.handlers.get(type)!.add(h);
    return () => this.handlers.get(type)!.delete(h);
  }
  emit(type: string, data: unknown): void {
    this.handlers.get(type)?.forEach((h) => h(data));
  }
  count(): number {
    return [...this.handlers.values()].reduce((n, s) => n + s.size, 0);
  }
}

@Component({
  imports: [GwConnectionCalls],
  template: '<dl class="gw-grid gw-grid--3"><div gwConnectionCalls [connection]="name()"></div></dl>',
})
class Host {
  readonly name = signal('dev-db');
}

let fixture: ComponentFixture<Host>;
let events: FakeEvents;
let queries: InsightsQuery[];
let answers: (() => Promise<Insights>)[];

const text = (el: Element | null | undefined) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
const stat = () => fixture.nativeElement.querySelector('.gw-stat') as HTMLElement;
const failure = () => Promise.reject(new ApiError('server', 500, { title: 'The core failed', detail: 'Insights could not be computed.' }));

async function flush(ms = 0): Promise<void> {
  await vi.advanceTimersByTimeAsync(ms);
  fixture.detectChanges();
  await vi.advanceTimersByTimeAsync(0);
  fixture.detectChanges();
}

async function render(): Promise<void> {
  fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  await flush();
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(NOW);
  events = new FakeEvents();
  queries = [];
  answers = [() => Promise.resolve(busyMonth(NOW))];
  const api: Partial<InsightsApi> = {
    get: (query) => {
      queries.push(query ?? {});
      const next = answers.length > 1 ? answers.shift()! : answers[0];
      return next();
    },
  };
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      provideKitIcons(),
      { provide: InsightsApi, useValue: api },
      { provide: EventStream, useValue: events },
      { provide: TimeService, useValue: { now: () => Date.now(), minute: () => NOW } },
    ],
  });
});

afterEach(() => {
  fixture?.destroy();
  vi.useRealTimers();
});

describe('Calls, last 30 days', () => {
  it("reads the connection's last 30 days on the core's clock and shows the count, the days, the change and what went wrong", async () => {
    await render();
    expect(queries).toEqual([{ q: 'connection:dev-db', from: new Date(NOW - 30 * DAY).toISOString(), to: new Date(NOW).toISOString() }]);
    expect(stat().getAttribute('aria-busy')).toBeNull();
    expect(text(stat())).toBe('Calls, last 30 days 412 +18% vs previous 30 days 3 errors · 2 denied by policy · 1 waiting for approval');
    expect(stat().querySelector('svg.gw-sparkline')?.getAttribute('aria-label')).toBe('Calls per day, last 30 days: peak of 41 on Oct 12');
    expect(stat().querySelector('.gw-stat__delta')?.getAttribute('data-sentiment')).toBe('neutral');
    expect(Array.from(stat().querySelectorAll('.gw-stat__meta a')).map((a) => a.getAttribute('href'))).toEqual([
      '/activity?q=connection:dev-db%20decision:error&range=30d',
      '/activity?q=connection:dev-db%20decision:denied&range=30d',
      '/approvals',
    ]);
  });

  it('shows a month without calls without a chart or a change', async () => {
    answers = [() => Promise.resolve(connectionMonth(NOW, Array(31).fill(0), {}, { calls: 0 }))];
    await render();
    expect(text(stat())).toBe('Calls, last 30 days 0 No calls in the last 30 days');
    expect(stat().querySelector('svg')).toBeNull();
  });

  it('is busy until the first answer', async () => {
    let resolve!: (answer: Insights) => void;
    answers = [() => new Promise((r) => { resolve = r; })];
    await render();
    expect(stat().getAttribute('aria-busy')).toBe('true');
    expect(text(stat())).toBe('Calls, last 30 days Loading');
    resolve(busyMonth(NOW));
    await flush();
    expect(stat().getAttribute('aria-busy')).toBeNull();
    expect(text(stat().querySelector('.gw-stat__value'))).toBe('412');
  });

  it('reads again 15 s after a call of this connection, once for many calls, and not for other connections', async () => {
    await render();
    events.emit('audit.call', { connection: 'prod-replica' });
    events.emit('audit.call', { connection: null });
    await flush(60_000);
    expect(queries).toHaveLength(1);

    answers = [() => Promise.resolve(connectionMonth(NOW + 70_000, Array(31).fill(14), { calls: 434 }, { calls: 350 }))];
    events.emit('audit.call', { connection: 'dev-db' });
    await flush(10_000);
    events.emit('audit.call', { connection: 'dev-db' });
    await flush(4_999);
    expect(queries).toHaveLength(1);
    await flush(1);
    expect(queries).toHaveLength(2);
    expect(queries[1]).toEqual({ q: 'connection:dev-db', from: new Date(NOW + 75_000 - 30 * DAY).toISOString(), to: new Date(NOW + 75_000).toISOString() });
    expect(text(stat().querySelector('.gw-stat__value'))).toBe('434');
    await flush(60_000);
    expect(queries).toHaveLength(2);
  });

  it('reads again when the event stream comes back', async () => {
    await render();
    events.reopened.set(1);
    await flush();
    expect(queries).toHaveLength(2);
  });

  it('offers a retry when the first read fails, and keeps the numbers when a refresh fails', async () => {
    answers = [failure, () => Promise.resolve(busyMonth(NOW))];
    await render();
    expect(text(stat())).toBe("Calls, last 30 days The calls didn't load. Try again to load the calls of dev-db");
    stat().querySelector<HTMLButtonElement>('button')!.click();
    await flush();
    expect(text(stat().querySelector('.gw-stat__value'))).toBe('412');

    answers = [failure];
    events.emit('audit.call', { connection: 'dev-db' });
    await flush(15_000);
    expect(queries).toHaveLength(3);
    expect(text(stat().querySelector('.gw-stat__value'))).toBe('412');
    expect(stat().querySelector('button')).toBeNull();
  });

  it('starts over for another connection and drops the answer for the one it left', async () => {
    let resolveOld!: (answer: Insights) => void;
    answers = [() => new Promise((r) => { resolveOld = r; }), () => Promise.resolve(connectionMonth(NOW, Array(31).fill(1), { calls: 31 }))];
    await render();
    fixture.componentInstance.name.set('prod-replica');
    fixture.detectChanges();
    await flush();
    expect(queries.map((q) => q.q)).toEqual(['connection:dev-db', 'connection:prod-replica']);
    resolveOld(busyMonth(NOW));
    await flush();
    expect(text(stat().querySelector('.gw-stat__value'))).toBe('31');

    events.emit('audit.call', { connection: 'dev-db' });
    await flush(15_000);
    expect(queries).toHaveLength(2);
  });

  it('stops following calls when it goes away', async () => {
    await render();
    expect(events.count()).toBe(1);
    events.emit('audit.call', { connection: 'dev-db' });
    fixture.destroy();
    await vi.advanceTimersByTimeAsync(15_000);
    expect(events.count()).toBe(0);
    expect(queries).toHaveLength(1);
  });
});
