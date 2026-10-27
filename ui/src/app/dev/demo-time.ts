import { Injectable, computed, signal } from '@angular/core';
import { TimeService } from '../core/time/time.service';

/** The demo world's clock (BLUEPRINT §9): Thursday 2026-10-22, 14:30, local time UTC+2. */
export const DEMO_NOW = Date.parse('2026-10-22T14:30:00+02:00');

/**
 * The gallery's clock: starts at the demo world's 14:30 and runs on, so "2 min ago" and the countdowns read as in
 * the kit's pages. Dev only (route provider of the gallery).
 */
@Injectable()
export class DemoTimeService extends TimeService {
  private readonly offset = DEMO_NOW - Date.now();
  private readonly demoTick = signal(Date.now());

  override readonly now = computed(() => this.demoTick() + this.offset);

  constructor() {
    super();
    const timer = setInterval(() => this.demoTick.set(Date.now()), 1000);
    // The gallery lives for the page's lifetime; the timer goes with it.
    globalThis.addEventListener?.('pagehide', () => clearInterval(timer));
  }
}
