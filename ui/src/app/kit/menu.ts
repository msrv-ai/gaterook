import { Directive, ElementRef, inject, input, output } from '@angular/core';
import { MenuService } from './menu.service';

/** What a pick in a menu or listbox was: the item element and its value (`data-value`, else its label). */
export interface MenuPick {
  readonly item: HTMLElement;
  readonly value: string;
  readonly role: string | null;
}

/**
 * The panel of a `gw-menu-host`: `<div class="gw-menu" gwMenu #m="gwMenu" id="m-dev" role="menu" hidden>` (or a
 * `gw-popover` with `role="dialog"`). Picks inside it behave as kit.js: an `option` / `menuitemradio` becomes the
 * only selected one of its group and its label goes into the trigger's `[data-gw-menu-value]`, a
 * `menuitemcheckbox` flips, a `menuitem` or `[data-gw-menu-close]` closes and refocuses the trigger.
 */
@Directive({
  selector: '[gwMenu]',
  exportAs: 'gwMenu',
  host: { '(click)': 'onClick($event)' },
})
export class GwMenu {
  private readonly menus = inject(MenuService);
  readonly element: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;

  /** Fires for every pick (option, menuitemradio, menuitemcheckbox, menuitem) before the menu closes. */
  readonly picked = output<MenuPick>();

  get open(): boolean {
    return this.menus.isOpen(this.element);
  }

  close(returnFocus = true): void {
    if (this.open) this.menus.close(returnFocus);
  }

  protected onClick(e: MouseEvent): void {
    const target = e.target instanceof Element ? e.target : null;
    const current = this.menus.current;
    if (!target || !current || current.panel !== this.element) return;
    if (target.closest('[aria-disabled="true"]')) {
      e.preventDefault();
      return;
    }
    if (target.closest('[data-gw-menu-close]')) {
      this.menus.close(true);
      return;
    }
    const check = target.closest<HTMLElement>('[role="menuitemcheckbox"]');
    if (check && this.element.contains(check)) {
      check.setAttribute('aria-checked', String(check.getAttribute('aria-checked') !== 'true'));
      this.picked.emit({ item: check, value: valueOf(check), role: 'menuitemcheckbox' });
      return;
    }
    const item = target.closest<HTMLElement>('[role="option"], [role="menuitemradio"]');
    if (item && this.element.contains(item)) {
      const group = item.closest('[role="listbox"],[role="group"],.gw-menu') ?? this.element;
      const attr = item.getAttribute('role') === 'option' ? 'aria-selected' : 'aria-checked';
      group.querySelectorAll(`[${attr}]`).forEach((i) => i.setAttribute(attr, 'false'));
      item.setAttribute(attr, 'true');
      const slot = current.trigger.querySelector('[data-gw-menu-value]');
      if (slot) setText(slot, labelOf(item));
      this.picked.emit({ item, value: valueOf(item), role: item.getAttribute('role') });
      this.menus.close(true);
      return;
    }
    const plain = target.closest<HTMLElement>('[role="menuitem"]');
    if (plain && this.element.contains(plain)) {
      this.picked.emit({ item: plain, value: valueOf(plain), role: 'menuitem' });
      this.menus.close(true);
    }
  }
}

/**
 * The trigger, a direct child of `.gw-menu-host`: `<button [gwMenuTrigger]="m" aria-haspopup="menu" aria-controls="…"
 * aria-expanded="false">`. Click / Enter / Space toggle; ↑/↓ open on the last / first item (not for popovers).
 */
@Directive({
  selector: '[gwMenuTrigger]',
  host: {
    '[attr.aria-expanded]': 'expanded()',
    '[attr.aria-controls]': 'gwMenuTrigger().element.id || null',
    '(click)': 'onClick($event)',
    '(keydown)': 'onKeydown($event)',
  },
})
export class GwMenuTrigger {
  private readonly menus = inject(MenuService);
  private readonly element: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;

  readonly gwMenuTrigger = input.required<GwMenu>();

  protected expanded(): string {
    return String(this.menus.isOpen(this.gwMenuTrigger().element));
  }

  protected onClick(e: MouseEvent): void {
    e.preventDefault();
    this.menus.toggle(this.element, this.gwMenuTrigger().element);
  }

  protected onKeydown(e: KeyboardEvent): void {
    if ((e.key === 'ArrowDown' || e.key === 'ArrowUp') && this.element.getAttribute('aria-haspopup') !== 'dialog') {
      e.preventDefault();
      this.menus.openFor(this.element, this.gwMenuTrigger().element, e.key === 'ArrowUp');
    }
  }
}

function labelOf(item: HTMLElement): string {
  return (item.querySelector('.gw-menu__label') ?? item).textContent?.trim() ?? '';
}

function valueOf(item: HTMLElement): string {
  return item.getAttribute('data-value') ?? labelOf(item);
}

/** Sets a text slot without replacing the Text node Angular may be bound to. */
export function setText(el: Element, text: string): void {
  if (el.childNodes.length === 1 && el.firstChild?.nodeType === Node.TEXT_NODE) el.firstChild.nodeValue = text;
  else el.textContent = text;
}
