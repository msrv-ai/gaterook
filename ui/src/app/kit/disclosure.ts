import { DOCUMENT, Directive, ElementRef, afterNextRender, inject, input, model } from '@angular/core';
import { safeStorage } from './storage';

/**
 * `<button gwToggle aria-controls="id" [(expanded)]>` (kit.js "disclosure"): toggles `hidden` on the target and
 * `aria-expanded` on itself. `gwTogglePersist="key"` remembers open / closed in localStorage `gw-toggle-<key>`
 * (how-it-works strips, "Show raw").
 */
@Directive({
  selector: 'button[gwToggle]',
  exportAs: 'gwToggle',
  host: { '[attr.aria-expanded]': 'String(expanded())', '(click)': 'toggle()' },
})
export class GwToggle {
  private readonly el: HTMLButtonElement = inject(ElementRef<HTMLButtonElement>).nativeElement;
  private readonly doc = inject(DOCUMENT);

  readonly expanded = model(false);
  readonly gwTogglePersist = input<string | null>(null);
  protected readonly String = String;

  constructor() {
    afterNextRender(() => {
      const key = this.gwTogglePersist();
      const stored = key ? safeStorage.get(`gw-toggle-${key}`) : null;
      if (stored) this.expanded.set(stored === 'open');
      this.sync();
    });
  }

  toggle(): void {
    this.set(!this.expanded());
  }

  set(open: boolean): void {
    this.expanded.set(open);
    this.sync();
    const key = this.gwTogglePersist();
    if (key) safeStorage.set(`gw-toggle-${key}`, open ? 'open' : 'closed');
  }

  private sync(): void {
    const id = this.el.getAttribute('aria-controls');
    const target = id ? this.doc.getElementById(id) : null;
    if (target) target.hidden = !this.expanded();
  }
}

/**
 * `<button gwExpand="#raw">` (kit.js "expand", "View raw"): opens that disclosure (and syncs the `aria-expanded` of
 * its toggle buttons), then puts the keyboard inside it — its first `tabindex="0"` region, else the target itself.
 */
@Directive({ selector: '[gwExpand]', host: { '(click)': 'onClick($event)' } })
export class GwExpand {
  private readonly doc = inject(DOCUMENT);
  readonly gwExpand = input.required<string>();

  protected onClick(e: Event): void {
    const target = this.doc.querySelector<HTMLElement>(this.gwExpand());
    if (!target) return;
    e.preventDefault();
    if (target.id) {
      this.doc
        .querySelectorAll(`[aria-controls="${target.id}"][aria-expanded]`)
        .forEach((t) => t.setAttribute('aria-expanded', 'true'));
    }
    target.hidden = false;
    let into = target.querySelector<HTMLElement>('[tabindex="0"]');
    if (!into) {
      into = target;
      if (!target.hasAttribute('tabindex')) target.setAttribute('tabindex', '-1');
    }
    into.focus();
  }
}
