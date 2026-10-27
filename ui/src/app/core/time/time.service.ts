import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { ApiClient } from '../api/api-client';
import { absoluteTime, clockTime, isoLocal, relativeTime, shortDate } from './time-format';

/**
 * The app's clock (ARCHITECTURE.md §8.1 "Time"): one ticker for every countdown and relative time, set to the
 * core's clock (the skew comes from the `Date` header of its answers), so "Auto-deny in 0:48" counts towards the
 * deadline the core enforces, not this machine's idea of it.
 */
@Injectable({ providedIn: 'root' })
export class TimeService {
  private readonly api = inject(ApiClient);
  private readonly tick = signal(Date.now());

  /** Now on the core's clock, updated every second. */
  readonly now = computed(() => this.tick() + this.api.clockSkewMs());

  /** Now, updated once a minute: enough for "3 min ago" and cheaper to depend on. */
  readonly minute = computed(() => Math.floor(this.now() / 60_000) * 60_000);

  constructor() {
    const timer = setInterval(() => this.tick.set(Date.now()), 1000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  /** Seconds from now to `at` on the core's clock (negative when past). */
  secondsUntil(at: string): number {
    return (Date.parse(at) - this.now()) / 1000;
  }

  relative(at: string | null | undefined): string {
    return at ? relativeTime(at, this.now()) : '';
  }

  /** "2026-10-22 14:29:30 +02:00" (without seconds: "2026-09-25 10:40 +02:00"), for `title`. */
  absolute(at: string | null | undefined, withSeconds = true): string {
    return at ? absoluteTime(at, withSeconds) : '';
  }

  iso(at: string | null | undefined): string {
    return at ? isoLocal(at) : '';
  }

  /** "Today", "Sep 25", "Oct 29, 2026" (`year`). */
  date(at: string | null | undefined, year = false): string {
    return at ? shortDate(at, this.now(), year) : '';
  }

  /** "14:29" (or "14:29:58"): a time of today in lists and logs. */
  clock(at: string | null | undefined, withSeconds = false): string {
    return at ? clockTime(at, withSeconds) : '';
  }
}
