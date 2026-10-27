import { DOCUMENT, Directive, ElementRef, Injectable, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { DialogStack } from './dialog';
import { MenuService } from './menu.service';
import { safeStorage } from './storage';

const SINGLE_KEYS = 'gw-single-keys';
const NOT_TEXT = /^(checkbox|radio|button|submit|reset|range|color|file|image|hidden)$/;

/**
 * The app's keyboard shortcuts, in one place (kit.js "⌘K / Ctrl+K", "/" key, "single keys"; DESIGN.md §12):
 * - ⌘K / Ctrl+K toggles the command palette from anywhere, unless another modal dialog is open (it never lies over
 *   someone's decision);
 * - ⌘, / Ctrl+, opens Settings (the palette lists it with `aria-keyshortcuts`, so it must really work);
 * - "/" focuses the visible field with `aria-keyshortcuts="/"` — only when focus is not in an editable field, no
 *   modifier is down, no menu is open, single-key shortcuts are on; inside an open modal only its own field counts.
 * Single-key shortcuts can be turned off (WCAG 2.1.4): `data-gw-single-keys="off"` on `<html>`, remembered.
 */
@Injectable({ providedIn: 'root' })
export class ShortcutService {
  private readonly doc = inject(DOCUMENT);
  private readonly menus = inject(MenuService);
  private readonly dialogs = inject(DialogStack);
  private readonly router = inject(Router, { optional: true });
  private installed = false;
  private paletteToggle: (() => void) | null = null;
  private readonly singleKeysSignal = signal(true);

  readonly singleKeys = this.singleKeysSignal.asReadonly();

  install(): void {
    if (this.installed) return;
    this.installed = true;
    const v = safeStorage.get(SINGLE_KEYS);
    if (v === 'off') this.doc.documentElement.setAttribute('data-gw-single-keys', 'off');
    if (v === 'on') this.doc.documentElement.removeAttribute('data-gw-single-keys');
    this.singleKeysSignal.set(this.singleKeysOn());
    this.doc.addEventListener('keydown', (e) => this.onKeydown(e));
  }

  /** The palette registers how it opens and closes (it owns its `<dialog>`). */
  registerPalette(toggle: () => void): void {
    this.paletteToggle = toggle;
  }

  setSingleKeys(on: boolean): void {
    if (on) this.doc.documentElement.removeAttribute('data-gw-single-keys');
    else this.doc.documentElement.setAttribute('data-gw-single-keys', 'off');
    safeStorage.set(SINGLE_KEYS, on ? 'on' : 'off');
    this.singleKeysSignal.set(on);
  }

  private onKeydown(e: KeyboardEvent): void {
    const mod = e.metaKey || e.ctrlKey;
    if (mod && !e.altKey && !e.shiftKey && !e.repeat && e.key?.toLowerCase() === 'k') {
      if (!this.paletteToggle) return;
      const palette = this.doc.getElementById('cmdk');
      const paletteOpen = palette instanceof HTMLDialogElement && palette.open;
      if (!paletteOpen && this.dialogs.openCount() > 0) return; // never on top of another dialog's decision
      e.preventDefault();
      this.paletteToggle();
      return;
    }
    if (mod && !e.altKey && !e.shiftKey && e.key === ',' && this.router && this.dialogs.openCount() === 0) {
      e.preventDefault();
      void this.router.navigateByUrl('/settings/general');
      return;
    }
    if (e.key !== '/' || e.ctrlKey || e.metaKey || e.altKey || e.isComposing || e.defaultPrevented) return;
    if (!this.singleKeysOn() || this.menus.current || isEditable(e.target)) return;
    const scope: ParentNode = this.doc.querySelector('dialog[open]') ?? this.doc;
    const field = Array.from(scope.querySelectorAll<HTMLElement>('[aria-keyshortcuts~="/"]')).find(
      (el) =>
        !(el as HTMLInputElement).disabled &&
        el.getAttribute('aria-disabled') !== 'true' &&
        el.getClientRects().length > 0 &&
        (scope !== this.doc || !el.closest('dialog:not([open])')),
    );
    if (!field) return;
    e.preventDefault(); // the "/" is not typed into the field
    field.focus();
  }

  private singleKeysOn(): boolean {
    return this.doc.documentElement.getAttribute('data-gw-single-keys') !== 'off';
  }
}

export function isEditable(el: EventTarget | null): boolean {
  if (!(el instanceof HTMLElement)) return false;
  if (el.isContentEditable || el.getAttribute('contenteditable') === 'true') return true;
  const tag = el.tagName;
  if (tag === 'TEXTAREA' || tag === 'SELECT') return true;
  if (tag === 'INPUT') return !NOT_TEXT.test((el as HTMLInputElement).type);
  const role = el.getAttribute('role');
  return role === 'textbox' || role === 'searchbox' || role === 'combobox';
}

/**
 * `input[type=checkbox][data-gw-single-keys-toggle]` (Settings › General, a `gw-switch`, default on): turns single-key
 * shortcuts on and off; checked from the current state.
 */
@Directive({
  selector: 'input[type=checkbox][data-gw-single-keys-toggle]',
  host: { '[checked]': 'shortcuts.singleKeys()', '(change)': 'onChange()' },
})
export class GwSingleKeysToggle {
  private readonly el: HTMLInputElement = inject(ElementRef<HTMLInputElement>).nativeElement;
  protected readonly shortcuts = inject(ShortcutService);

  protected onChange(): void {
    this.shortcuts.setSingleKeys(this.el.checked);
  }
}
