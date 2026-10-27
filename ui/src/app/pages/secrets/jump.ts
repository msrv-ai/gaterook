import { DOCUMENT, Directive, inject, input } from '@angular/core';

/**
 * Moves the view and the keyboard to a card or section of this page (`#master-key`, `#stored-secrets`,
 * `#store-aws`): it scrolls the target into view inside the shell's scrolling main and focuses it, the way a native
 * in-page anchor moves the sequential focus starting point. The router doesn't scroll to anchors in this app (the
 * window never scrolls as a whole), and a second click on the same link doesn't navigate at all.
 */
export function jumpTo(doc: Document, id: string): boolean {
  const target = doc.getElementById(id);
  if (!target) return false;
  if (!target.hasAttribute('tabindex') && !isFocusable(target)) target.setAttribute('tabindex', '-1');
  // jsdom has no layout: scrollIntoView may be missing there.
  target.scrollIntoView?.({ block: 'start' });
  target.focus({ preventScroll: true });
  return true;
}

function isFocusable(el: HTMLElement): boolean {
  return el.matches('a[href], button, input, select, textarea, [tabindex]');
}

/**
 * `<a [routerLink]="[]" fragment="stored-secrets" gwJump="stored-secrets">`: the link keeps its real URL
 * (`/secrets#stored-secrets`, for a new tab) and the router records the fragment; the jump happens here, on every
 * click, after the router has handled it.
 */
@Directive({ selector: 'a[gwJump]', host: { '(click)': 'onClick($event)' } })
export class GwJump {
  private readonly doc = inject(DOCUMENT);
  readonly gwJump = input.required<string>();

  protected onClick(e: MouseEvent): void {
    // A modified click opens a new tab or window: leave it to the browser.
    if (e.button !== 0 || e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) return;
    const id = this.gwJump();
    queueMicrotask(() => jumpTo(this.doc, id));
  }
}
