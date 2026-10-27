import { DOCUMENT, DestroyRef, Injectable, inject } from '@angular/core';

/** How long decision buttons ignore activation (DECISIONS 6, ARCHITECTURE.md §7.1 and §9 "Self-approval through the GUI"). */
export const ACTIVATION_DELAY_MS = 700;

/**
 * Self-approval through the GUI: an agent with computer use (or UI automation of the same OS account) can click
 * "Approve and run" or "Allow access" the instant the screen shows, and a deep link brings the window to the front.
 * So decision buttons ignore activation for 700 ms after they appear, after the window gets focus (or becomes visible)
 * and after a deep link. A person needs longer than that to read the action; a script that clicks at once is ignored.
 *
 * This service holds the window-wide moments (focus, visibility, deep link); each decision screen holds its own
 * "appeared" moment in an {@link ActivationArm}. The clock is `performance.now()`: monotonic, so changing the system
 * time can't shorten the delay.
 */
@Injectable({ providedIn: 'root' })
export class ActivationGuard {
  private readonly doc = inject(DOCUMENT);
  private readonly clock = (): number => performance.now();
  private lastWindowEvent = this.clock();

  constructor() {
    const win = this.doc.defaultView;
    const onFocus = (): void => this.rearm();
    const onVisibility = (): void => {
      if (this.doc.visibilityState === 'visible') this.rearm();
    };
    win?.addEventListener('focus', onFocus);
    this.doc.addEventListener('visibilitychange', onVisibility);
    inject(DestroyRef).onDestroy(() => {
      win?.removeEventListener('focus', onFocus);
      this.doc.removeEventListener('visibilitychange', onVisibility);
    });
  }

  /** The window came to the front, became visible or followed a deep link: every decision button waits again. */
  rearm(): void {
    this.lastWindowEvent = this.clock();
  }

  /** A per-screen arm: `reset()` when the screen (or the request it shows) appears, `ready()` before acting. */
  arm(): ActivationArm {
    return new ActivationArm(this.clock, () => this.lastWindowEvent);
  }
}

/** One decision screen's activation delay; see {@link ActivationGuard}. */
export class ActivationArm {
  private appearedAt: number;

  constructor(
    private readonly clock: () => number,
    private readonly windowEventAt: () => number,
  ) {
    this.appearedAt = clock();
  }

  /** The screen appeared, or now shows another request: the delay starts again. */
  reset(): void {
    this.appearedAt = this.clock();
  }

  /** Whether an activation now is a person's (700 ms after the last appearance, focus or deep link). */
  ready(): boolean {
    return this.clock() - Math.max(this.appearedAt, this.windowEventAt()) >= ACTIVATION_DELAY_MS;
  }
}
