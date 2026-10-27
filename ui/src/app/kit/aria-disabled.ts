import { DOCUMENT, Directive, Injectable, booleanAttribute, inject, input } from '@angular/core';
import { MenuService } from './menu.service';

/**
 * "Unavailable, with a reason" (kit.js guard, DESIGN.md §8 "Action hierarchy and disabled behavior"): a control with
 * `aria-disabled="true"` (or the `<label>` of such an input) stays focusable and explains itself through
 * `aria-describedby`, but never acts. Capture-phase listeners on the document, installed before anything else
 * (provideAppInitializer), cancel its click, Enter / Space (and ↑/↓ on a menu trigger), a Space released, a submit by
 * it, and revert a radio or checkbox change that slipped through (arrow keys move AND check in a radio group).
 * No Angular listener or kit directive ever sees the activation.
 */
@Injectable({ providedIn: 'root' })
export class AriaDisabledGuard {
  private readonly doc = inject(DOCUMENT);
  private readonly menus = inject(MenuService);
  private installed = false;
  /** The radio that was checked before a pointer or key acted on its group. */
  private radioBefore: HTMLInputElement | null = null;

  install(): void {
    if (this.installed) return;
    this.installed = true;
    const doc = this.doc;
    doc.addEventListener('click', (e) => this.onClick(e), true);
    doc.addEventListener('pointerdown', (e) => this.snapshotRadio(e), true);
    doc.addEventListener('keydown', (e) => this.onKeydown(e), true);
    doc.addEventListener('keyup', (e) => this.onKeyup(e), true);
    doc.addEventListener('change', (e) => this.onChange(e), true);
    doc.addEventListener('submit', (e) => this.onSubmit(e as SubmitEvent), true);
  }

  /** The aria-disabled control `node` belongs to, or null. */
  static offTarget(node: EventTarget | null): HTMLElement | null {
    if (!(node instanceof Element)) return null;
    const off = node.closest<HTMLElement>('[aria-disabled="true"]');
    if (off) return off;
    const label = node.closest('label');
    const control = label?.control;
    return control && control.getAttribute('aria-disabled') === 'true' ? control : null;
  }

  private onClick(e: Event): void {
    if (!AriaDisabledGuard.offTarget(e.target)) return;
    e.preventDefault(); // also keeps a radio / checkbox from toggling
    e.stopImmediatePropagation();
    // Still an outside click for an open menu.
    this.menus.closeIfOutside(e.target);
  }

  private snapshotRadio(e: Event): void {
    const t = e.target;
    if (!(t instanceof Element)) return;
    const label = t.closest('label');
    const candidate = t.closest('input[type="radio"]') ?? label?.control ?? null;
    if (!(candidate instanceof HTMLInputElement) || candidate.type !== 'radio' || !candidate.name) return;
    const group = Array.from(this.doc.getElementsByName(candidate.name)).filter(
      (x): x is HTMLInputElement => x instanceof HTMLInputElement && x.type === 'radio' && x.form === candidate.form,
    );
    this.radioBefore = group.find((x) => x.checked) ?? null;
  }

  private onKeydown(e: KeyboardEvent): void {
    this.snapshotRadio(e);
    const off = AriaDisabledGuard.offTarget(e.target);
    if (!off) return;
    const activates =
      e.key === 'Enter' ||
      e.key === ' ' ||
      ((e.key === 'ArrowDown' || e.key === 'ArrowUp') && off.hasAttribute('aria-haspopup'));
    if (activates) {
      e.preventDefault();
      e.stopImmediatePropagation();
    }
  }

  /** Some engines activate a button or checkbox when Space is released: cancel that too. */
  private onKeyup(e: KeyboardEvent): void {
    if (e.key !== ' ' || !AriaDisabledGuard.offTarget(e.target)) return;
    e.preventDefault();
    e.stopImmediatePropagation();
  }

  private onChange(e: Event): void {
    const input = e.target;
    if (!(input instanceof HTMLInputElement) || input.getAttribute('aria-disabled') !== 'true') return;
    if (input.type === 'checkbox') {
      input.checked = !input.checked;
    } else if (input.type === 'radio') {
      if (this.radioBefore && this.radioBefore !== input) this.radioBefore.checked = true;
      else input.checked = false;
    } else {
      return;
    }
    e.stopImmediatePropagation();
  }

  private onSubmit(e: SubmitEvent): void {
    const by = e.submitter;
    if (by && by.getAttribute('aria-disabled') === 'true') {
      e.preventDefault();
      e.stopImmediatePropagation();
    }
  }
}

/**
 * `[gwAriaDisabled]="reasonOrFlag"`: renders `aria-disabled="true"` while the condition holds and leaves the
 * attribute out otherwise (the guard does the rest). Point `aria-describedby` at the visible reason.
 */
@Directive({
  selector: '[gwAriaDisabled]',
  host: { '[attr.aria-disabled]': 'gwAriaDisabled() ? "true" : null' },
})
export class GwAriaDisabled {
  readonly gwAriaDisabled = input(false, { transform: booleanAttribute });
}
