import { DOCUMENT, Injectable, inject } from '@angular/core';
import { topLayer } from './top-layer';

/** What is announced when nothing could be copied: the person has to know the clipboard still holds the old text. */
export const COPY_FAILED = "Couldn't copy. Select the text and copy it.";

/**
 * Writes text to the clipboard and says whether it worked (kit.js "copy" uses `navigator.clipboard.writeText`).
 *
 * Why not CDK `Clipboard`: it copies through a hidden textarea appended to `<body>`, and `<body>` is inert while a
 * modal `<dialog>` is open — `execCommand('copy')` then copies nothing although it returns as if it had. Most copies
 * in the app happen in dialogs (the one-time token, the event drawer, client connect, the palette).
 *
 * 1. `navigator.clipboard.writeText` — Electron's permission handler allows `clipboard-sanitized-write` (§7.1), and
 *    browsers allow it on a user gesture in a secure context (127.0.0.1 in the dev loop).
 * 2. Only if that is missing or refused: the textarea + `execCommand('copy')` fallback, with the textarea placed in the
 *    open modal (`topLayer`) so it is not inert, and focus given back afterwards.
 */
@Injectable({ providedIn: 'root' })
export class ClipboardWriter {
  private readonly doc = inject(DOCUMENT);

  async write(text: string): Promise<boolean> {
    const clipboard = this.doc.defaultView?.navigator.clipboard;
    if (clipboard && typeof clipboard.writeText === 'function') {
      try {
        await clipboard.writeText(text);
        return true;
      } catch {
        // Refused (no focus, no permission): try the legacy path before telling the person it failed.
      }
    }
    return this.execCopy(text);
  }

  private execCopy(text: string): boolean {
    if (typeof this.doc.execCommand !== 'function') return false;
    const previous = this.doc.activeElement as HTMLElement | null;
    const area = this.doc.createElement('textarea');
    // Visually hidden through the kit's class (no inline style: CSP), still selectable; never read out.
    area.className = 'gw-sr-only';
    area.setAttribute('aria-hidden', 'true');
    area.setAttribute('tabindex', '-1');
    area.readOnly = true;
    area.value = text;
    topLayer(this.doc).appendChild(area);
    try {
      area.focus();
      area.select();
      area.setSelectionRange(0, text.length);
      return this.doc.execCommand('copy');
    } catch {
      return false;
    } finally {
      area.remove();
      previous?.focus?.({ preventScroll: true });
    }
  }
}
