import { DOCUMENT, Directive, ElementRef, Injectable, Injector, afterNextRender, booleanAttribute, inject, input, output, signal } from '@angular/core';
import { MenuService } from './menu.service';

/**
 * The stack of open modal dialogs, so toasts and announcements can go into the top one (its outside is inert) and
 * ⌘K never opens on top of another dialog's decision.
 */
@Injectable({ providedIn: 'root' })
export class DialogStack {
  private readonly stack = signal<readonly HTMLDialogElement[]>([]);

  /** The open modal dialog on top, or null. */
  readonly top = (): HTMLDialogElement | null => this.stack().at(-1) ?? null;
  readonly openCount = (): number => this.stack().length;

  push(d: HTMLDialogElement): void {
    this.stack.update((s) => [...s.filter((x) => x !== d), d]);
  }

  remove(d: HTMLDialogElement): void {
    this.stack.update((s) => s.filter((x) => x !== d));
  }
}

/**
 * `<dialog class="gw-dialog" gwDialog #d="gwDialog">` and `<dialog class="gw-drawer" gwDialog>` (kit.js "dialogs &
 * drawers"): `open()` calls `showModal()` (focus trap, Esc, inert page, top layer); the initial focus is the
 * markup's `autofocus` (first field, or Cancel in a confirmation). On close, focus returns to the opener — the menu's
 * trigger when the opener was a menu item, the first opener of a dialog chain. A backdrop click closes it unless it
 * is `gwDialogStatic` (forms) or guarded.
 *
 * `gwDialogGuard`: Esc and the backdrop can't close it while its form is invalid ("I've stored this token").
 */
@Directive({
  selector: 'dialog[gwDialog]',
  exportAs: 'gwDialog',
  host: {
    '[attr.data-gw-static]': 'gwDialogStatic() || gwDialogGuard() ? "" : null',
    '(close)': 'onClose()',
    '(cancel)': 'onCancel($event)',
    '(click)': 'onClick($event)',
  },
})
export class GwDialog {
  readonly element: HTMLDialogElement = inject(ElementRef<HTMLDialogElement>).nativeElement;
  private readonly doc = inject(DOCUMENT);
  private readonly menus = inject(MenuService);
  private readonly stack = inject(DialogStack);
  private readonly injector = inject(Injector);
  /** Where focus goes back to on close. */
  returnTo: HTMLElement | null = null;

  readonly gwDialogStatic = input(false, { transform: booleanAttribute });
  readonly gwDialogGuard = input(false, { transform: booleanAttribute });
  /** The close value (`returnValue`): `''` for Cancel / Esc, `next` when a step handed over. */
  readonly closed = output<string>();
  readonly opened = output<void>();

  readonly isOpen = signal(false);

  /** Opens the dialog modally; `opener` is where focus returns (default: the focused element). */
  open(opener?: HTMLElement | null): void {
    if (this.element.open || typeof this.element.showModal !== 'function') return;
    const from = opener ?? (this.doc.activeElement instanceof HTMLElement ? this.doc.activeElement : null);
    this.returnTo = this.returnTarget(from);
    this.menus.close(false);
    // A dialog keeps its last returnValue across openings, and Esc closes without one: start from "" (Cancel), so
    // an earlier "allow" or "delete" never answers this opening.
    this.element.returnValue = '';
    this.element.showModal();
    this.element.setAttribute('data-gw-modal', '');
    this.stack.push(this.element);
    this.isOpen.set(true);
    this.opened.emit();
  }

  /**
   * Opens once the content the caller just changed has rendered (a dialog about "this row": set the row, then open),
   * so the markup's `autofocus` inside that content gets the initial focus.
   */
  openAfterRender(opener?: HTMLElement | null): void {
    const from = opener ?? (this.doc.activeElement instanceof HTMLElement ? this.doc.activeElement : null);
    afterNextRender(() => this.open(from), { injector: this.injector });
  }

  close(value = ''): void {
    if (this.element.open) this.element.close(value);
  }

  protected onClose(): void {
    this.element.removeAttribute('data-gw-modal');
    this.stack.remove(this.element);
    this.isOpen.set(false);
    const back = this.returnTo;
    this.returnTo = null;
    const now = this.doc.activeElement;
    if (back && back.isConnected && (!now || now === this.doc.body || this.element.contains(now))) back.focus();
    this.closed.emit(this.element.returnValue);
  }

  protected onCancel(e: Event): void {
    if (!this.gwDialogGuard()) return;
    const form = this.element.querySelector('form');
    if (form && !form.checkValidity()) {
      e.preventDefault();
      form.reportValidity();
    }
  }

  protected onClick(e: MouseEvent): void {
    // A backdrop click: the event target is the <dialog> itself, outside its box.
    if (e.target !== this.element || !this.element.open || this.gwDialogStatic() || this.gwDialogGuard()) return;
    const r = this.element.getBoundingClientRect();
    if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) this.close('');
  }

  /** The opener, or the trigger of the open menu the opener sat in (that item is hidden once the menu closes). */
  private returnTarget(opener: HTMLElement | null): HTMLElement | null {
    const m = this.menus.current;
    if (opener && m && m.panel.contains(opener)) return m.trigger;
    return opener;
  }
}

/** `<button [gwDialogOpen]="d">` (kit.js `data-gw-dialog-open`): opens the dialog, focus returns here on close. */
@Directive({ selector: '[gwDialogOpen]', host: { '(click)': 'onClick($event)', 'aria-haspopup': 'dialog' } })
export class GwDialogOpen {
  private readonly el: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;
  readonly gwDialogOpen = input.required<GwDialog>();

  protected onClick(e: MouseEvent): void {
    e.preventDefault();
    this.gwDialogOpen().open(this.el);
  }
}

/** `<button gwDialogClose="value">` inside a dialog (kit.js `data-gw-dialog-close`). */
@Directive({ selector: '[gwDialogClose]', host: { '(click)': 'onClick()' } })
export class GwDialogClose {
  private readonly el: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;
  readonly gwDialogClose = input<string>('');

  protected onClick(): void {
    this.el.closest('dialog')?.close(this.gwDialogClose() || (this.el as HTMLButtonElement).value || '');
  }
}

/**
 * Dialog steps (kit.js `data-gw-dialog-next`): on a form, a valid submit closes this dialog and opens the next one; on
 * a button (not submit), the same after `reportValidity()`. The chain hands its first opener on, so the last step
 * returns focus there.
 */
@Directive({
  selector: '[gwDialogNext]',
  host: { '(submit)': 'onSubmit($event)', '(click)': 'onClick($event)' },
})
export class GwDialogNext {
  private readonly el: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;
  /** The dialog around this step, found through the element injector. */
  private readonly hostDialog = inject(GwDialog, { optional: true });
  readonly gwDialogNext = input.required<GwDialog>();
  /** The dialog this step closes (default: the one around it). */
  readonly gwDialogFrom = input<GwDialog | null>(null);

  protected onSubmit(e: Event): void {
    if (this.el.tagName !== 'FORM') return;
    e.preventDefault(); // reached only when the form is valid
    this.handOver();
  }

  protected onClick(e: MouseEvent): void {
    if (this.el.tagName === 'FORM' || (this.el as HTMLButtonElement).type === 'submit') return;
    const form = this.el.closest('form');
    if (form && !form.reportValidity()) return;
    e.preventDefault();
    this.handOver();
  }

  private handOver(): void {
    const next = this.gwDialogNext();
    const current = this.gwDialogFrom() ?? this.hostDialog;
    // The chain hands its first opener on: the last step returns focus there, not to a button of a closed step.
    const firstOpener = current?.returnTo ?? null;
    if (current) {
      current.returnTo = null;
      current.close('next');
    } else {
      this.el.closest('dialog')?.close('next');
    }
    next.open(firstOpener ?? this.el);
  }
}
