import { DOCUMENT, Injectable, inject, signal } from '@angular/core';

/** Space between a trigger and its panel, = --gw-space-1 (the kit's CSS gap). */
const MENU_GAP = 4;

const ITEM_SELECTOR = '[role="menuitem"],[role="menuitemradio"],[role="menuitemcheckbox"],[role="option"],.gw-menu__item';

interface OpenMenu {
  readonly trigger: HTMLElement;
  readonly panel: HTMLElement;
}

/**
 * The one open menu or popover (kit.js "menus & popovers"): at most one at a time, opened by click / Enter / Space or
 * ↑/↓ on its trigger (never on hover or focus), placed in the top layer (Popover API `popover="manual"`) under its
 * trigger from the trigger's rect so a scrolling table or a dialog body never clips it, flipped above
 * (`gw-menu--up` / `gw-popover--up`) when there is more room there, and following scroll and resize. Esc closes and
 * refocuses the trigger, Tab or an outside click closes it. Inside, ↑/↓ wrap, Home / End jump.
 *
 * Why not CDK Overlay (ARCHITECTURE.md A18): its pane lives in `<body>`, which is inert under a modal `<dialog>`; the
 * kit keeps the panel inside its host and lifts it with the Popover API instead.
 */
@Injectable({ providedIn: 'root' })
export class MenuService {
  private readonly doc = inject(DOCUMENT);
  /** A signal so `aria-expanded` bindings follow it; the attribute is also set directly, as kit.js does. */
  private readonly openSignal = signal<OpenMenu | null>(null);
  private listening = false;

  private get open(): OpenMenu | null {
    return this.openSignal();
  }

  private set open(value: OpenMenu | null) {
    this.openSignal.set(value);
  }

  get current(): OpenMenu | null {
    return this.openSignal();
  }

  isOpen(panel: HTMLElement): boolean {
    return this.openSignal()?.panel === panel;
  }

  /** Opens `panel` for `trigger`, closing any other; focus goes to the selected item (or first / last). */
  openFor(trigger: HTMLElement, panel: HTMLElement, focusLast = false): void {
    this.listen();
    this.close(false);
    panel.hidden = false;
    if (typeof panel.showPopover === 'function') {
      panel.setAttribute('popover', 'manual');
      try {
        panel.showPopover();
      } catch {
        panel.removeAttribute('popover');
      }
    }
    trigger.setAttribute('aria-expanded', 'true');
    this.open = { trigger, panel };
    this.place();
    const items = MenuService.items(panel);
    const selected = items.find(
      (i) => i.getAttribute('aria-selected') === 'true' || i.getAttribute('aria-checked') === 'true',
    );
    // A popover starts at its CHECKED radio (Appearance: the current theme), else its first field.
    const target =
      trigger.getAttribute('aria-haspopup') === 'dialog'
        ? (panel.querySelector<HTMLElement>(
            'input[type="radio"]:checked, input:not([type="radio"]):not([type="hidden"]), select, textarea, button, [tabindex]:not([tabindex="-1"])',
          ) ?? panel.querySelector<HTMLElement>("input,select,textarea,button,[tabindex]:not([tabindex='-1'])"))
        : (selected ?? (focusLast ? items[items.length - 1] : items[0]));
    target?.focus();
  }

  /** Closes the open panel; `returnFocus` puts the keyboard back on its trigger. */
  close(returnFocus: boolean): void {
    const m = this.open;
    if (!m) return;
    this.open = null;
    if (m.panel.hasAttribute('popover')) {
      try {
        m.panel.hidePopover();
      } catch {
        // already hidden
      }
      m.panel.removeAttribute('popover');
    }
    m.panel.style.top = '';
    m.panel.style.left = '';
    m.panel.style.maxHeight = '';
    m.panel.classList.remove('gw-menu--up', 'gw-popover--up');
    m.panel.hidden = true;
    m.trigger.setAttribute('aria-expanded', 'false');
    if (returnFocus) m.trigger.focus();
  }

  toggle(trigger: HTMLElement, panel: HTMLElement): void {
    if (this.open?.trigger === trigger) this.close(true);
    else this.openFor(trigger, panel);
  }

  /** A click that is outside the open panel and its trigger closes it (without moving focus). */
  closeIfOutside(target: EventTarget | null): void {
    const m = this.open;
    if (!m || !(target instanceof Node)) return;
    if (!m.panel.contains(target) && !m.trigger.contains(target)) this.close(false);
  }

  /** Keyboard inside the open panel: ↑/↓ wrap, Home / End; Esc closes; Tab leaves (menus, not popovers). */
  onKeydown(e: KeyboardEvent): void {
    const m = this.open;
    if (!m) return;
    if (e.key === 'Escape') {
      e.preventDefault();
      this.close(true);
      return;
    }
    if (e.key === 'Tab' && m.trigger.getAttribute('aria-haspopup') !== 'dialog') {
      this.close(false);
      return;
    }
    if (!(e.target instanceof Node) || !m.panel.contains(e.target)) return;
    const items = MenuService.items(m.panel);
    const i = items.indexOf(this.doc.activeElement as HTMLElement);
    let next: HTMLElement | undefined;
    if (e.key === 'ArrowDown') next = items[(i + 1) % items.length];
    if (e.key === 'ArrowUp') next = items[(i - 1 + items.length) % items.length];
    if (e.key === 'Home') next = items[0];
    if (e.key === 'End') next = items[items.length - 1];
    if (next) {
      e.preventDefault();
      next.focus();
    }
  }

  /** Items in DOM order: natively disabled and hidden ones are skipped, aria-disabled ones stay (their reason reads). */
  static items(panel: HTMLElement): HTMLElement[] {
    return Array.from(panel.querySelectorAll<HTMLElement>(ITEM_SELECTOR)).filter(
      (el) => !(el as HTMLButtonElement).disabled && isRendered(el),
    );
  }

  /** Places the open panel under (or above) its trigger, inside the viewport. */
  place(): void {
    const m = this.open;
    if (!m) return;
    const { panel, trigger } = m;
    const upClass = panel.classList.contains('gw-popover') ? 'gw-popover--up' : 'gw-menu--up';
    const r = trigger.getBoundingClientRect();
    const vw = this.doc.documentElement.clientWidth;
    const vh = this.doc.documentElement.clientHeight;
    panel.classList.remove(upClass);
    panel.style.maxHeight = '';
    let h = panel.offsetHeight;
    const w = panel.offsetWidth;
    const below = vh - r.bottom - 2 * MENU_GAP;
    const above = r.top - 2 * MENU_GAP;
    const up = h > below && above > below;
    panel.classList.toggle(upClass, up);
    if (!panel.hasAttribute('popover')) return; // absolute fallback: the --up class flips it
    const room = up ? above : below;
    if (h > room) {
      panel.style.maxHeight = `${Math.max(room, 0)}px`;
      h = panel.offsetHeight;
    }
    const rtl = getComputedStyle(panel).direction === 'rtl';
    const atEnd = panel.classList.contains('gw-menu--right') || panel.classList.contains('gw-popover--right');
    let left = atEnd !== rtl ? r.right - w : r.left;
    left = Math.max(MENU_GAP, Math.min(left, vw - w - MENU_GAP));
    panel.style.left = `${left}px`;
    panel.style.top = `${up ? r.top - MENU_GAP - h : r.bottom + MENU_GAP}px`;
  }

  private listen(): void {
    if (this.listening) return;
    this.listening = true;
    const win = this.doc.defaultView;
    const follow = (e?: Event): void => {
      const m = this.open;
      if (!m) return;
      if (e?.target instanceof Element && m.panel.contains(e.target)) return; // the panel's own scroll
      this.place();
    };
    win?.addEventListener('scroll', follow, true);
    win?.addEventListener('resize', () => follow());
    this.doc.addEventListener('click', (e) => this.closeIfOutside(e.target));
    this.doc.addEventListener('keydown', (e) => this.onKeydown(e));
    this.doc.addEventListener('focusout', () => {
      const m = this.open;
      if (!m || m.trigger.getAttribute('aria-haspopup') !== 'dialog') return;
      setTimeout(() => {
        const now = this.open;
        if (now && !now.panel.contains(this.doc.activeElement) && this.doc.activeElement !== now.trigger) this.close(false);
      }, 0);
    });
  }
}

function isRendered(el: HTMLElement): boolean {
  // jsdom has no layout (offsetParent is always null): treat [hidden] ancestry as the visibility test there.
  if (el.offsetParent !== null) return true;
  if (typeof navigator !== 'undefined' && /jsdom/i.test(navigator.userAgent)) return !el.closest('[hidden]');
  return getComputedStyle(el).position === 'fixed';
}
