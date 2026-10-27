// Vitest + jsdom setup (angular.json › test › setupFiles). The Angular unit-test builder initializes TestBed itself;
// this file only fills what jsdom lacks so the kit's behaviours can be tested as in a browser.

/**
 * `<dialog>`: jsdom knows the element but not modality. showModal() marks it open and modal (`:modal` doesn't
 * exist in jsdom, so the kit's top-layer lookup falls back to `[data-gw-modal]`, set by GwDialog); close() fires
 * `close` like a browser does, with the return value.
 */
const dialogProto = globalThis.HTMLDialogElement?.prototype as HTMLDialogElement | undefined;
if (dialogProto && typeof dialogProto.showModal !== 'function') {
  dialogProto.showModal = function (this: HTMLDialogElement) {
    this.setAttribute('open', '');
    const autofocus = this.querySelector<HTMLElement>('[autofocus]');
    autofocus?.focus();
  };
  dialogProto.show = function (this: HTMLDialogElement) {
    this.setAttribute('open', '');
  };
  dialogProto.close = function (this: HTMLDialogElement, value?: string) {
    if (!this.hasAttribute('open')) return;
    if (value !== undefined) this.returnValue = value;
    this.removeAttribute('open');
    this.dispatchEvent(new Event('close'));
  };
}
