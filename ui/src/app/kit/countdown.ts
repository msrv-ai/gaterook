import { Directive, ElementRef, computed, effect, inject, input, output, untracked } from '@angular/core';
import { TimeService } from '../core/time/time.service';
import { countdown } from '../core/time/time-format';
import { Announcer } from './announcer';

/** The last minute: the tone moves from warning to danger. */
export const URGENT_SECONDS = 60;

/**
 * `[gwCountdown]="expiresAt"` (kit.js "countdown") on the element that carries a countdown — an approval request's
 * `__countdown`, a timeline step. It ticks on the core's clock (TimeService), sets `--gw-countdown` (1 → 0) through
 * CSSOM (`style=""` is blocked by the CSP, `setProperty` is not), and the kit's states:
 * - ≤ 60 s: `[data-urgent]`, `gw-progress--warning` → `--danger` inside, the `[role=timer]` line turns
 *   `gw-fg-danger` (its icon drops `gw-fg-warning`), `gwCountdownAnnounce` is read out once (assertive);
 * - 0: `[data-expired]`, the bar is hidden, `ended` fires once.
 * The template renders the text from `text()` and the expired face from `expired()` (Angular owns those nodes).
 *
 * A new deadline (the element now shows another request) is a new countdown: the bar's 100 %, the tone, the
 * last-minute announcement and `ended` all start over, so the second request is announced and expires too.
 */
@Directive({
  selector: '[gwCountdown]',
  exportAs: 'gwCountdown',
  host: { '[attr.data-urgent]': 'urgent() ? "" : null', '[attr.data-expired]': 'expired() ? "" : null' },
})
export class GwCountdown {
  private readonly el: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;
  private readonly time = inject(TimeService);
  private readonly announcer = inject(Announcer);
  private announced = false;
  private endedOnce = false;
  /** The nodes whose tone this directive moved to danger (put back when a new deadline is not urgent). */
  private toned: { readonly bars: Element[]; readonly lines: Element[]; readonly icons: Element[] } | null = null;
  /** The deadline the one-shot flags belong to. */
  private deadline: string | null = null;

  /** The deadline (ISO instant). */
  readonly gwCountdown = input.required<string>();
  /** The window in seconds (the bar's 100 %); default: the time left when the countdown started. */
  readonly gwCountdownTotal = input<number | null>(null);
  /** Read out once, assertively, when the last minute starts. */
  readonly gwCountdownAnnounce = input<string | null>(null);
  readonly ended = output<void>();

  /** Seconds left when this deadline was first seen: follows the deadline, not the clock. */
  private readonly initialLeft = computed(() => {
    const at = this.gwCountdown();
    return Math.max(0, Math.ceil(untracked(() => this.time.secondsUntil(at))));
  });

  /** Whole seconds left (never negative). */
  readonly left = computed(() => Math.max(0, Math.ceil(this.time.secondsUntil(this.gwCountdown()))));
  readonly total = computed(() => this.gwCountdownTotal() ?? this.initialLeft());
  readonly fraction = computed(() => {
    const total = this.total();
    return total ? Math.min(1, this.left() / total) : 0;
  });
  readonly urgent = computed(() => this.left() <= URGENT_SECONDS);
  readonly expired = computed(() => this.left() <= 0);
  /** "4:12". */
  readonly text = computed(() => countdown(this.left()));

  constructor() {
    effect(() => {
      const at = this.gwCountdown();
      if (at !== this.deadline) {
        this.deadline = at;
        untracked(() => this.restart());
      }
      this.el.style.setProperty('--gw-countdown', String(this.fraction()));
      if (this.urgent()) untracked(() => this.onUrgent());
      if (this.expired()) untracked(() => this.onExpired());
    });
  }

  /** Another deadline: forget what the previous one already did, and undo its tone. */
  private restart(): void {
    this.announced = false;
    this.endedOnce = false;
    const t = this.toned;
    this.toned = null;
    if (!t) return;
    t.bars.forEach((p) => {
      p.classList.remove('gw-progress--danger');
      p.classList.add('gw-progress--warning');
    });
    t.lines.forEach((line) => line.classList.remove('gw-fg-danger'));
    t.icons.forEach((i) => i.classList.add('gw-fg-warning'));
    this.el.querySelectorAll<HTMLElement>('.gw-progress').forEach((p) => (p.hidden = false));
  }

  private onUrgent(): void {
    if (!this.toned) {
      const bars = Array.from(this.el.querySelectorAll('.gw-progress--warning'));
      const lines = Array.from(this.el.querySelectorAll('[role="timer"]'));
      const icons = lines.flatMap((line) => Array.from(line.querySelectorAll('.gw-fg-warning')));
      this.toned = { bars, lines, icons };
      bars.forEach((p) => {
        p.classList.remove('gw-progress--warning');
        p.classList.add('gw-progress--danger');
      });
      lines.forEach((line) => line.classList.add('gw-fg-danger'));
      icons.forEach((i) => i.classList.remove('gw-fg-warning'));
    }
    const say = this.gwCountdownAnnounce();
    if (!this.announced && say && this.left() > 0) {
      this.announced = true;
      this.announcer.announce(say, { assertive: true });
    }
  }

  private onExpired(): void {
    this.el.querySelectorAll<HTMLElement>('.gw-progress').forEach((p) => (p.hidden = true));
    if (!this.endedOnce) {
      this.endedOnce = true;
      this.ended.emit();
    }
  }
}
