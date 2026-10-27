/**
 * The element announcements and toasts must live in (kit.js `topLayer()`): an open modal `<dialog>` makes the rest
 * of the document inert, so a live region or a toast outside it would be neither seen nor heard.
 */
export function topLayer(doc: Document = document): HTMLElement {
  return (openModal(doc) as HTMLElement | null) ?? doc.body;
}

/**
 * The open modal dialog, if any. `:modal` where the engine matches it; otherwise (jsdom parses `:modal` but never
 * matches it, older engines throw) the dialog gwDialog marked when it called `showModal()`.
 */
export function openModal(doc: Document = document): HTMLDialogElement | null {
  let modal: HTMLDialogElement | null = null;
  try {
    modal = doc.querySelector<HTMLDialogElement>('dialog[open]:modal');
  } catch {
    modal = null;
  }
  return modal ?? doc.querySelector<HTMLDialogElement>('dialog[open][data-gw-modal]');
}
