import { DOCUMENT, Directive, ElementRef, inject, input, model } from '@angular/core';

/**
 * `<button gwReveal aria-controls="pw">` (kit.js "reveal"): shows a NEW secret while it is typed for the first time
 * (password ↔ text, `aria-pressed`). Never offered for a stored secret: `gw-secret` renders it only in `empty`.
 */
@Directive({
  selector: 'button[gwReveal]',
  host: { '[attr.aria-pressed]': 'String(shown())', '(click)': 'toggle()' },
})
export class GwReveal {
  private readonly el: HTMLButtonElement = inject(ElementRef<HTMLButtonElement>).nativeElement;
  private readonly doc = inject(DOCUMENT);
  /** The input to reveal (default: the one `aria-controls` names). */
  readonly gwReveal = input<HTMLInputElement | '' | null>(null);
  readonly shown = model(false);
  protected readonly String = String;

  toggle(): void {
    const next = !this.shown();
    this.shown.set(next);
    const id = this.el.getAttribute('aria-controls');
    const field = this.gwReveal() || (id ? (this.doc.getElementById(id) as HTMLInputElement | null) : null);
    if (field) field.type = next ? 'text' : 'password';
  }
}
