import {
  DOCUMENT,
  DestroyRef,
  Directive,
  ElementRef,
  afterNextRender,
  inject,
  input,
  output,
} from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs';

/**
 * `[role=tablist][gwTabs]` (kit.js "tabs"): click selects, ←/→ (↑/↓ when `aria-orientation="vertical"`), Home and
 * End move AND select (automatic activation), roving tabindex, each tab's `aria-controls` panel is `hidden` unless
 * its tab is selected. Natively disabled and `aria-disabled` tabs are skipped by the arrows.
 *
 * Deep links: a URL fragment naming a tab, a panel or anything inside a hidden panel selects every tab around it
 * (innermost first) and scrolls the target into view — on first render and on every navigation (the router's
 * fragment, the app's equivalent of kit.js's `hashchange`).
 */
@Directive({
  selector: '[gwTabs]',
  exportAs: 'gwTabs',
  host: { '(click)': 'onClick($event)', '(keydown)': 'onKeydown($event)' },
})
export class GwTabs {
  private readonly el: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;
  private readonly doc = inject(DOCUMENT);

  /** The id of the tab to select first (else the one marked `aria-selected="true"`, else the first). */
  readonly gwTabsSelected = input<string | null>(null);
  /** The newly selected tab's id (a page mirrors it in its URL: `?tab=tokens`). */
  readonly selectedChange = output<string>();

  constructor() {
    afterNextRender(() => {
      const wanted = this.gwTabsSelected();
      const sel =
        (wanted ? this.tabs().find((t) => t.id === wanted) : undefined) ??
        this.el.querySelector<HTMLElement>('[role="tab"][aria-selected="true"]') ??
        this.el.querySelector<HTMLElement>('[role="tab"]');
      if (sel?.getAttribute('aria-controls')) this.select(sel, false, false);
      selectFromFragment(this.doc);
    });
  }

  /** Selects the tab with this id. */
  selectById(id: string, focus = false): void {
    const tab = this.tabs().find((t) => t.id === id);
    if (tab) this.select(tab, focus, true);
  }

  select(tab: HTMLElement, focus: boolean, notify = true): void {
    for (const t of this.tabs()) {
      const on = t === tab;
      t.setAttribute('aria-selected', String(on));
      t.tabIndex = on ? 0 : -1;
      const panelId = t.getAttribute('aria-controls');
      const panel = panelId ? this.doc.getElementById(panelId) : null;
      if (panel) panel.hidden = !on;
    }
    if (focus) tab.focus();
    if (notify) this.selectedChange.emit(tab.id);
  }

  protected onClick(e: MouseEvent): void {
    const tab = e.target instanceof Element ? e.target.closest<HTMLElement>('[role="tab"]') : null;
    if (tab && this.el.contains(tab) && tab.getAttribute('aria-controls') && tab.getAttribute('aria-disabled') !== 'true') {
      this.select(tab, false);
    }
  }

  protected onKeydown(e: KeyboardEvent): void {
    const tab = e.target instanceof Element ? e.target.closest<HTMLElement>('[role="tab"]') : null;
    if (!tab || !this.el.contains(tab)) return;
    const tabs = this.tabs().filter(
      (t) => !(t as HTMLButtonElement).disabled && t.getAttribute('aria-disabled') !== 'true',
    );
    const i = tabs.indexOf(tab);
    const vertical = this.el.getAttribute('aria-orientation') === 'vertical';
    const nextKey = vertical ? 'ArrowDown' : 'ArrowRight';
    const prevKey = vertical ? 'ArrowUp' : 'ArrowLeft';
    let next: HTMLElement | undefined;
    if (e.key === nextKey) next = tabs[(i + 1) % tabs.length];
    if (e.key === prevKey) next = tabs[(i - 1 + tabs.length) % tabs.length];
    if (e.key === 'Home') next = tabs[0];
    if (e.key === 'End') next = tabs[tabs.length - 1];
    if (next) {
      e.preventDefault();
      this.select(next, true);
    }
  }

  private tabs(): HTMLElement[] {
    return Array.from(this.el.querySelectorAll<HTMLElement>('[role="tab"]'));
  }
}

/**
 * Selects the tabs around the element the fragment names, then scrolls it into view (kit.js `tabFromHash`).
 * Returns whether the fragment named an element.
 */
export function selectFromFragment(doc: Document, fragment?: string | null): boolean {
  const raw = fragment ?? doc.defaultView?.location.hash.slice(1) ?? '';
  const id = decodeURIComponent(raw);
  if (!id || !/^[A-Za-z][\w:.-]*$/.test(id)) return false;
  const target = doc.getElementById(id);
  if (!target) return false;
  for (let panel = target.closest('[role="tabpanel"]'); panel; panel = panel.parentElement?.closest('[role="tabpanel"]') ?? null) {
    const owner = doc.querySelector<HTMLElement>(`[role="tab"][aria-controls="${panel.id}"]`);
    if (owner) selectTabElement(doc, owner);
  }
  if (target.getAttribute('role') === 'tab') selectTabElement(doc, target);
  target.scrollIntoView?.({ block: 'start' });
  return true;
}

function selectTabElement(doc: Document, tab: HTMLElement): void {
  const list = tab.closest('[role="tablist"]');
  if (!list) return;
  for (const t of Array.from(list.querySelectorAll<HTMLElement>('[role="tab"]'))) {
    const on = t === tab;
    t.setAttribute('aria-selected', String(on));
    t.tabIndex = on ? 0 : -1;
    const panelId = t.getAttribute('aria-controls');
    const panel = panelId ? doc.getElementById(panelId) : null;
    if (panel) panel.hidden = !on;
  }
}

/**
 * Follows the router's fragment for deep links into tabs (`/endpoints/dev#ep-dlg-split`, `/policies/prod#prod-sql`):
 * installed once by the app (a host directive of `App`), on every navigation including same-route fragment changes;
 * pages need nothing.
 */
@Directive({ selector: '[gwFragmentTabs]' })
export class GwFragmentTabs {
  constructor() {
    const doc = inject(DOCUMENT);
    const router = inject(Router, { optional: true });
    if (!router) return;
    const sub = router.events
      .pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd))
      .subscribe(() => {
        const fragment = router.parseUrl(router.url).fragment;
        if (fragment) setTimeout(() => selectFromFragment(doc, fragment));
      });
    inject(DestroyRef).onDestroy(() => sub.unsubscribe());
  }
}
