import { DOCUMENT, Directive, ElementRef, Injectable, inject, signal } from '@angular/core';
import { DesktopBridge } from '../core/desktop/desktop-bridge';

export type ThemeChoice = 'system' | 'light' | 'dark';
export type ContrastChoice = 'system' | 'normal' | 'more';

export interface Appearance {
  readonly theme: ThemeChoice;
  readonly contrast: ContrastChoice;
  readonly resolved: { readonly theme: 'light' | 'dark'; readonly contrast: 'normal' | 'more' };
}

interface GwThemeApi {
  get(): Appearance;
  set(next: { theme?: ThemeChoice; contrast?: ContrastChoice }): void;
}

declare global {
  interface Window {
    gwTheme?: GwThemeApi;
  }
}

const FALLBACK: Appearance = { theme: 'system', contrast: 'system', resolved: { theme: 'light', contrast: 'normal' } };

/**
 * The appearance (kit.js "theme controls" over `assets/theme-boot.js`): theme-boot resolves System / Light / Dark and
 * System / Normal / High into `data-theme` / `data-contrast` on `<html>` before the first paint and follows the OS
 * live; this service mirrors it as a signal and tells Electron (`nativeTheme.themeSource`, the titlebar overlay).
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly doc = inject(DOCUMENT);
  private readonly desktop = inject(DesktopBridge);
  private readonly state = signal<Appearance>(this.read());

  readonly appearance = this.state.asReadonly();

  constructor() {
    this.doc.defaultView?.addEventListener('gw-theme-change', () => this.state.set(this.read()));
    this.desktop.setNativeTheme(this.state().theme);
  }

  set(next: { theme?: ThemeChoice; contrast?: ContrastChoice }): void {
    const api = this.doc.defaultView?.gwTheme;
    if (api) api.set(next);
    this.state.set(this.read());
    if (next.theme) this.desktop.setNativeTheme(next.theme);
  }

  /** The palette's "Toggle theme": light ↔ dark from what is shown now. */
  toggle(): void {
    this.set({ theme: this.state().resolved.theme === 'dark' ? 'light' : 'dark' });
  }

  private read(): Appearance {
    return this.doc.defaultView?.gwTheme?.get() ?? FALLBACK;
  }
}

/**
 * `input[name=gw-theme]` and `input[name=gw-contrast]` radios anywhere (the titlebar popover, Settings ›
 * Appearance): checked from the current choice and bound to it, every control on the page in sync (kit.js binds
 * them by name, so this directive matches them by name).
 */
@Directive({
  selector: 'input[type=radio][name=gw-theme], input[type=radio][name=gw-contrast]',
  host: { '[checked]': 'isChecked()', '(change)': 'onChange()' },
})
export class GwThemeControl {
  private readonly el: HTMLInputElement = inject(ElementRef<HTMLInputElement>).nativeElement;
  private readonly theme = inject(ThemeService);

  protected isChecked(): boolean {
    const a = this.theme.appearance();
    return this.el.name === 'gw-theme' ? a.theme === this.el.value : a.contrast === this.el.value;
  }

  protected onChange(): void {
    if (!this.el.checked) return;
    if (this.el.name === 'gw-theme') this.theme.set({ theme: this.el.value as ThemeChoice });
    else this.theme.set({ contrast: this.el.value as ContrastChoice });
  }
}
