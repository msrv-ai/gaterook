import { DestroyRef, Directive, ElementRef, afterNextRender, inject, output } from '@angular/core';
import { Announcer } from './announcer';

/**
 * The kit's `.gw-chart` with pressable bars (kit.js "chart bars"): one selected range per chart (a click presses a
 * bar and releases the others, a second click releases it), announced; one tab stop per chart with ←/→/↑/↓, Home and
 * End moving between bars (roving focus). Charts without `.gw-chart__bar[aria-pressed]` buttons are untouched.
 *
 * Bars come and go with the data (a new range in Activity, Insights reloading): a MutationObserver keeps the single
 * tab stop when they are re-rendered, so a chart never turns into one Tab stop per bar.
 */
@Directive({
  selector: '.gw-chart',
  host: { '(click)': 'onClick($event)', '(keydown)': 'onKeydown($event)' },
})
export class GwChart {
  private readonly el: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;
  private readonly announcer = inject(Announcer);
  private readonly destroyRef = inject(DestroyRef);
  /** The pressed bar's index, or null when the range was cleared. */
  readonly rangeChange = output<number | null>();

  constructor() {
    afterNextRender(() => {
      this.rove();
      if (typeof MutationObserver === 'undefined') return;
      // Bars added or removed, or pressed by a binding: rove again (tabindex isn't watched, so this never loops).
      const observer = new MutationObserver(() => this.rove());
      observer.observe(this.el, { childList: true, subtree: true, attributes: true, attributeFilter: ['aria-pressed'] });
      this.destroyRef.onDestroy(() => observer.disconnect());
    });
  }

  /** Makes exactly one bar tabbable: the focused one, else the pressed one, else the last. */
  rove(): void {
    const bars = this.bars();
    if (!bars.length) return;
    const focused = bars.find((b) => b === this.el.ownerDocument.activeElement);
    const current = focused ?? bars.find((b) => b.getAttribute('aria-pressed') === 'true') ?? bars[bars.length - 1];
    bars.forEach((b) => {
      const tabIndex = b === current ? 0 : -1;
      if (b.tabIndex !== tabIndex) b.tabIndex = tabIndex;
    });
  }

  protected onClick(e: MouseEvent): void {
    const bar = e.target instanceof Element ? e.target.closest<HTMLElement>('.gw-chart__bar[aria-pressed]') : null;
    if (!bar || !this.el.contains(bar)) return;
    const bars = this.bars();
    const on = bar.getAttribute('aria-pressed') !== 'true';
    bars.forEach((b) => b.setAttribute('aria-pressed', 'false'));
    bar.setAttribute('aria-pressed', String(on));
    this.announcer.announce(on ? `Range selected: ${bar.getAttribute('aria-label') ?? ''}` : 'Range cleared');
    this.rangeChange.emit(on ? bars.indexOf(bar) : null);
  }

  protected onKeydown(e: KeyboardEvent): void {
    const bar = e.target instanceof Element ? e.target.closest<HTMLElement>('.gw-chart__bar[aria-pressed]') : null;
    if (!bar) return;
    const bars = this.bars();
    const i = bars.indexOf(bar);
    let next: HTMLElement | undefined;
    if (e.key === 'ArrowRight' || e.key === 'ArrowDown') next = bars[Math.min(i + 1, bars.length - 1)];
    if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') next = bars[Math.max(i - 1, 0)];
    if (e.key === 'Home') next = bars[0];
    if (e.key === 'End') next = bars[bars.length - 1];
    if (next) {
      e.preventDefault();
      bars.forEach((b) => (b.tabIndex = b === next ? 0 : -1));
      next.focus();
    }
  }

  private bars(): HTMLElement[] {
    return Array.from(this.el.querySelectorAll<HTMLElement>('.gw-chart__bar[aria-pressed]'));
  }
}
