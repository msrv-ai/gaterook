import { DOCUMENT, Injectable, inject } from '@angular/core';
import { MenuService } from './menu.service';

/**
 * Tooltips are CSS-only (`gw-tooltip`, shown on `:hover` and `:focus-visible`); kit.js adds the one thing CSS can't:
 * Esc hides the bubble of the hovered or focused tooltip (WCAG 1.4.13 dismissible) until the pointer or focus leaves
 * it. Installed once for the whole document; no markup beyond the kit's.
 */
@Injectable({ providedIn: 'root' })
export class TooltipDismiss {
  private readonly doc = inject(DOCUMENT);
  private readonly menus = inject(MenuService);
  private installed = false;

  install(): void {
    if (this.installed) return;
    this.installed = true;
    this.doc.addEventListener('keydown', (e) => {
      if (e.key !== 'Escape' || this.menus.current) return;
      let tips: Element[] = [];
      try {
        tips = Array.from(this.doc.querySelectorAll('.gw-tooltip:hover, .gw-tooltip:has(:focus-visible)'));
      } catch {
        // :has / :focus-visible unsupported (old engines, jsdom): fall back to the focused element's tooltip
        const t = this.doc.activeElement?.closest('.gw-tooltip');
        if (t) tips = [t];
      }
      tips.forEach((t) => t.setAttribute('data-dismissed', ''));
    });
    const clear = (e: Event): void => {
      const t = e.target instanceof Element ? e.target.closest('.gw-tooltip[data-dismissed]') : null;
      t?.removeAttribute('data-dismissed');
    };
    this.doc.addEventListener('mouseleave', clear, true);
    this.doc.addEventListener('focusout', clear);
  }
}
