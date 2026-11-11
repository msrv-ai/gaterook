import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { isApiError, isNotAvailable } from '../../core/api/api-error';
import { OverviewApi, OverviewSummary } from '../../core/api/overview.api';
import { EventStream } from '../../core/events/event-stream';
import { CoreStore } from '../../core/stores/core.store';
import { GwIcon } from '../../kit/icons';
import { callsChart, summaryState, summaryTiles, trafficChart } from './overview-summary-view';

/** After a call, the numbers are read again this much later: one read of a month of the audit a minute at most. */
export const SUMMARY_REFRESH_MS = 60_000;
/** Placeholders only when the first answer is slow (kit: a skeleton only past ~300 ms). */
export const SUMMARY_SKELETON_MS = 300;
/** Today becomes yesterday a little after midnight, when the core's day has turned too. */
const AFTER_MIDNIGHT_MS = 5_000;

/** The person's zone, so the core's days are the dates this app shows; the core's own when the browser can't say. */
function personZone(): string | null {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || null;
  } catch {
    return null;
  }
}

/**
 * Overview's "Last 30 days" (`GET /api/overview/summary`): four numbers — calls, denied by policy, traffic in and
 * out — and two static charts by day, calls by what they came to and traffic. Read on its own, apart from the
 * overview's refreshes: when the page opens, a minute after calls came, at midnight and after the core restarted. A
 * core without the summary leaves the section out.
 */
@Component({
  selector: 'gw-overview-summary',
  imports: [NgTemplateOutlet, RouterLink, GwIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './overview-summary.html',
})
export class GwOverviewSummary {
  private readonly api = inject(OverviewApi);
  private readonly events = inject(EventStream);
  private readonly core = inject(CoreStore);

  protected readonly summary = signal<OverviewSummary | null>(null);
  /** Why the last read failed; with numbers on screen, they may be out of date. */
  protected readonly error = signal<unknown>(null);
  protected readonly loading = signal(false);
  /** This core has no summary: the section is left out, never filled with made-up numbers. */
  protected readonly unavailable = signal(false);
  protected readonly slow = signal(false);

  protected readonly state = computed(() => {
    const s = this.summary();
    return s ? summaryState(s) : null;
  });
  protected readonly tiles = computed(() => {
    const s = this.summary();
    return s ? summaryTiles(s) : [];
  });
  protected readonly calls = computed(() => {
    const s = this.summary();
    return s ? callsChart(s) : null;
  });
  protected readonly traffic = computed(() => {
    const s = this.summary();
    return s ? trafficChart(s) : null;
  });

  private refreshTimer: ReturnType<typeof setTimeout> | null = null;
  private midnightTimer: ReturnType<typeof setTimeout> | null = null;
  private inFlight: Promise<void> | null = null;
  private again = false;
  private stopped = false;

  constructor() {
    const skeleton = setTimeout(() => this.slow.set(true), SUMMARY_SKELETON_MS);
    const unsubscribe = this.events.on('audit.call', () => this.schedule());
    inject(DestroyRef).onDestroy(() => {
      this.stopped = true;
      clearTimeout(skeleton);
      unsubscribe();
      if (this.refreshTimer) clearTimeout(this.refreshTimer);
      if (this.midnightTimer) clearTimeout(this.midnightTimer);
    });
    let running = this.core.running();
    let instance = this.core.instanceChanges();
    effect(() => {
      const nextRunning = this.core.running();
      const nextInstance = this.core.instanceChanges();
      untracked(() => {
        if (nextRunning && (!running || nextInstance !== instance)) void this.load();
      });
      running = nextRunning;
      instance = nextInstance;
    });
    void this.load().finally(() => clearTimeout(skeleton));
  }

  /** Reads the summary now; while one read runs, one more follows it. */
  load(): Promise<void> {
    if (this.inFlight) {
      this.again = true;
      return this.inFlight;
    }
    this.loading.set(true);
    this.inFlight = this.read().finally(() => {
      this.inFlight = null;
      this.loading.set(false);
      if (this.again && !this.stopped) {
        this.again = false;
        void this.load();
      }
    });
    return this.inFlight;
  }

  protected retry(): void {
    void this.load();
  }

  protected errorText(e: unknown): string {
    return isApiError(e) ? e.detail : 'Check the local gateway and try again.';
  }

  /** A read a minute from now, unless one is planned already (calls in a burst make one read). */
  private schedule(): void {
    if (this.stopped || this.unavailable() || this.refreshTimer) return;
    this.refreshTimer = setTimeout(() => {
      this.refreshTimer = null;
      void this.load();
    }, SUMMARY_REFRESH_MS);
  }

  private async read(): Promise<void> {
    try {
      const s = await this.api.summary(personZone());
      if (this.stopped) return;
      // A core restarted as a newer build may have the summary that an older one didn't.
      this.unavailable.set(false);
      this.summary.set(s);
      this.error.set(null);
      this.untilTomorrow(s);
    } catch (e) {
      if (this.stopped) return;
      if (isNotAvailable(e)) this.unavailable.set(true);
      else this.error.set(e);
    }
  }

  /** At the end of today the bars move a day: read again then, calls or not. */
  private untilTomorrow(s: OverviewSummary): void {
    if (this.midnightTimer) clearTimeout(this.midnightTimer);
    const end = Date.parse(s.days.at(-1)?.to ?? '');
    if (Number.isNaN(end)) return;
    this.midnightTimer = setTimeout(() => {
      this.midnightTimer = null;
      void this.load();
    }, Math.max(AFTER_MIDNIGHT_MS, end - Date.parse(s.to) + AFTER_MIDNIGHT_MS));
  }
}
