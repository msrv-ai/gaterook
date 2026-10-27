import { DOCUMENT, DestroyRef, Directive, ElementRef, afterNextRender, inject, input } from '@angular/core';

/**
 * `<button [gwEnableWhen]="commentField">` (kit.js "enable when"): `aria-disabled` until the source is satisfied — a
 * checkbox checked ("I've stored this token"), a text field holding non-blank text (prod's "comment required" Approve
 * and run). A native `disabled` in the markup is the no-script fallback and is lifted. The button's
 * `aria-describedby` points at the hint that says what to check or type; the guard keeps it inert meanwhile.
 */
@Directive({ selector: '[gwEnableWhen]' })
export class GwEnableWhen {
  private readonly el: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;
  private readonly doc = inject(DOCUMENT);
  readonly gwEnableWhen = input.required<HTMLInputElement | HTMLTextAreaElement | string | null>();

  constructor() {
    const sync = (): void => this.sync();
    afterNextRender(() => {
      this.sync();
      this.doc.addEventListener('input', sync, true);
      this.doc.addEventListener('change', sync, true);
    });
    inject(DestroyRef).onDestroy(() => {
      this.doc.removeEventListener('input', sync, true);
      this.doc.removeEventListener('change', sync, true);
    });
  }

  /** Re-evaluates now (after the page set the field's value programmatically). */
  sync(): void {
    const ref = this.gwEnableWhen();
    const src = typeof ref === 'string' ? this.doc.querySelector<HTMLInputElement>(ref) : ref;
    if (!src) return;
    if ((this.el as HTMLButtonElement).disabled) (this.el as HTMLButtonElement).disabled = false;
    if (conditionMet(src)) this.el.removeAttribute('aria-disabled');
    else this.el.setAttribute('aria-disabled', 'true');
  }
}

export function conditionMet(src: HTMLInputElement | HTMLTextAreaElement): boolean {
  if (src instanceof HTMLInputElement && (src.type === 'checkbox' || src.type === 'radio')) return src.checked;
  return String(src.value ?? '').trim() !== '';
}
