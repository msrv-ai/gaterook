import { DOCUMENT, Directive, ElementRef, OnDestroy, inject, input } from '@angular/core';
import { Announcer } from './announcer';
import { COPY_FAILED, ClipboardWriter } from './clipboard';
import { GwToast } from './toast-region';

/** How long the button shows "Copied" (`[data-copied]`), as kit.js. */
export const COPIED_MS = 1600;

/**
 * `<button gwCopy="text">` or `<button [gwCopyFrom]="el">` / `gwCopyFrom="#id"` (kit.js "copy"): copies, shows the
 * button's "Copied" face for 1.6 s through `[data-copied]`, and announces "Copied to clipboard" — unless the button
 * also shows a toast (`gwToast`), which is the more precise message: one announcement per action.
 * The source's value is taken for inputs, its text for `div`, `pre` and `code` (copy what is visible: escapes too).
 *
 * Unlike kit.js (which shows "Copied" whether or not the write worked) the state, the announcement and the toast
 * follow the result: a one-time token the person believes is copied, but is not, is lost when the dialog closes.
 * On failure the button keeps its face and "Couldn't copy. Select the text and copy it." is announced.
 */
@Directive({ selector: '[gwCopy],[gwCopyFrom]', host: { '(click)': 'copy()' } })
export class GwCopy implements OnDestroy {
  private readonly el: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;
  private readonly doc = inject(DOCUMENT);
  private readonly clipboard = inject(ClipboardWriter);
  private readonly announcer = inject(Announcer);
  /** A toast on the same button is shown by this directive, and only once the text is on the clipboard. */
  private readonly toast = inject(GwToast, { self: true, optional: true });
  private timer: ReturnType<typeof setTimeout> | null = null;
  private destroyed = false;

  readonly gwCopy = input<string | null>(null);
  readonly gwCopyFrom = input<string | HTMLElement | null>(null);

  constructor() {
    this.toast?.deferToCopy();
  }

  /** Resolves once the outcome is shown (tests and callers that chain on it). */
  async copy(): Promise<boolean> {
    const ok = await this.clipboard.write(this.text());
    if (this.destroyed) return ok;
    if (!ok) {
      this.announcer.announce(COPY_FAILED, { assertive: true });
      return false;
    }
    this.el.setAttribute('data-copied', '');
    if (this.toast) this.toast.show();
    else this.announcer.announce('Copied to clipboard');
    if (this.timer) clearTimeout(this.timer);
    this.timer = setTimeout(() => this.el.removeAttribute('data-copied'), COPIED_MS);
    return true;
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    if (this.timer) clearTimeout(this.timer);
  }

  private text(): string {
    const direct = this.gwCopy();
    if (direct !== null && direct !== '') return direct;
    const from = this.gwCopyFrom();
    const src = typeof from === 'string' ? this.doc.querySelector<HTMLElement>(from) : from;
    if (!src) return direct ?? '';
    const isField = src instanceof HTMLInputElement || src instanceof HTMLTextAreaElement || src instanceof HTMLSelectElement;
    return isField ? src.value : (src.textContent ?? '');
  }
}
