import { DOCUMENT, Injectable, inject } from '@angular/core';
import { topLayer } from './top-layer';

/**
 * Live announcements (kit.js "live region / announce"): one polite `role=status` and one assertive `role=alert`
 * region, visually hidden, moved into the open modal dialog when there is one, cleared and refilled after 50 ms so a
 * repeated message is read again.
 *
 * Why not CDK `LiveAnnouncer` (ARCHITECTURE.md §8.2 named it): its region stays in `<body>`, which is inert while a
 * native modal `<dialog>` is open, so "Copied to clipboard" inside the token dialog would never be heard.
 */
@Injectable({ providedIn: 'root' })
export class Announcer {
  private readonly doc = inject(DOCUMENT);
  private polite: HTMLElement | null = null;
  private assertive: HTMLElement | null = null;
  /**
   * The pending refill of each region. Separate, so "Copied" (polite) and "… expired" (assertive) in the same moment
   * are both read; a second message for the same region replaces the first, as only the last text stays in it.
   */
  private readonly timers = new Map<'status' | 'alert', ReturnType<typeof setTimeout>>();

  /** Delay before the text is set: assistive tech reads a change, not a node that already had the text. */
  static readonly DELAY_MS = 50;

  announce(text: string, opts: { readonly assertive?: boolean } = {}): void {
    const role = opts.assertive ? 'alert' : 'status';
    const region = this.region(role);
    const host = topLayer(this.doc);
    if (region.parentNode !== host) host.appendChild(region);
    region.textContent = '';
    const pending = this.timers.get(role);
    if (pending) clearTimeout(pending);
    this.timers.set(
      role,
      setTimeout(() => {
        region.textContent = text;
        this.timers.delete(role);
      }, Announcer.DELAY_MS),
    );
  }

  private region(role: 'status' | 'alert'): HTMLElement {
    const existing = role === 'status' ? this.polite : this.assertive;
    if (existing) return existing;
    const el = this.doc.createElement('div');
    el.className = 'gw-sr-only';
    el.setAttribute('role', role);
    if (role === 'status') el.setAttribute('aria-live', 'polite');
    this.doc.body.appendChild(el);
    if (role === 'status') this.polite = el;
    else this.assertive = el;
    return el;
  }
}
