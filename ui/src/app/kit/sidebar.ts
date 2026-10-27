import { Directive, ElementRef, afterNextRender, inject } from '@angular/core';
import { safeStorage } from './storage';

const KEY = 'gw-sidebar-collapsed';

/**
 * `<button gwSidebarToggle aria-controls="gw-sidebar">` (kit.js "sidebar"): toggles `data-collapsed` on its
 * `.gw-shell`, keeps `aria-expanded` and the label ("Collapse sidebar" / "Expand sidebar") in sync. Only the real app
 * shell (the one holding `nav[data-gw-nav]`) remembers the choice; demos don't. Below `md` the kit collapses the
 * sidebar by CSS and hides this button.
 */
@Directive({ selector: 'button[gwSidebarToggle]', host: { '(click)': 'toggle()' } })
export class GwSidebarToggle {
  private readonly el: HTMLButtonElement = inject(ElementRef<HTMLButtonElement>).nativeElement;

  constructor() {
    afterNextRender(() => {
      const shell = this.shell();
      if (!shell) return;
      const persisted = this.persisted(shell);
      this.apply(shell, persisted ? safeStorage.get(KEY) === '1' : shell.hasAttribute('data-collapsed'));
    });
  }

  toggle(): void {
    const shell = this.shell();
    if (!shell) return;
    this.apply(shell, !shell.hasAttribute('data-collapsed'));
    if (this.persisted(shell)) safeStorage.set(KEY, shell.hasAttribute('data-collapsed') ? '1' : '');
  }

  private apply(shell: HTMLElement, collapsed: boolean): void {
    shell.toggleAttribute('data-collapsed', collapsed);
    this.el.setAttribute('aria-expanded', String(!collapsed));
    this.el.setAttribute('aria-label', collapsed ? 'Expand sidebar' : 'Collapse sidebar');
  }

  private shell(): HTMLElement | null {
    return this.el.closest<HTMLElement>('.gw-shell');
  }

  private persisted(shell: HTMLElement): boolean {
    return shell.querySelector('[data-gw-nav]') !== null;
  }
}
