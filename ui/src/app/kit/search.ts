import { DOCUMENT, Directive, ElementRef, inject, input } from '@angular/core';

/**
 * The kit's `.gw-search__clear` button (kit.js "search clear"): empties its `.gw-search__input`, fires `input` (so a
 * bound model and filters follow) and puts focus back into the field. Matches the kit's class: no extra markup.
 */
@Directive({ selector: 'button.gw-search__clear', host: { '(click)': 'clear()' } })
export class GwSearchClearButton {
  private readonly el: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;

  clear(): void {
    const input = this.el.closest('.gw-search')?.querySelector<HTMLInputElement>('.gw-search__input');
    if (!input) return;
    clearField(input);
  }
}

/** `<button gwSearchClear="#q">` from elsewhere (an empty state's "Clear search"; kit.js `data-gw-search-clear`). */
@Directive({ selector: '[gwSearchClear]', host: { '(click)': 'clear()' } })
export class GwSearchClear {
  private readonly doc = inject(DOCUMENT);
  readonly gwSearchClear = input.required<string | HTMLInputElement>();

  clear(): void {
    const target = this.gwSearchClear();
    const field = typeof target === 'string' ? this.doc.querySelector<HTMLInputElement>(target) : target;
    if (field) clearField(field);
  }
}

function clearField(input: HTMLInputElement): void {
  input.value = '';
  input.dispatchEvent(new Event('input', { bubbles: true }));
  input.focus();
}
