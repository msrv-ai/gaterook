import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, inject, input, output, signal } from '@angular/core';
import { McpServersApi, McpSignIn } from '../../../core/api/plugins.api';
import { DesktopBridge } from '../../../core/desktop/desktop-bridge';
import { EventStream } from '../../../core/events/event-stream';
import { Announcer } from '../../../kit/announcer';
import { COPY_FAILED, ClipboardWriter } from '../../../kit/clipboard';
import { GwCopy } from '../../../kit/copy';
import { GwToggle } from '../../../kit/disclosure';
import { GwIcon } from '../../../kit/icons';
import { failureText } from './failure';

/** How often a waiting sign-in is looked at besides its events (a stream that reconnects may miss one). */
const POLL_MS = 3000;

let nextId = 0;

/**
 * `<div gwMcpSignIn>` (ARCHITECTURE.md §7.5): the OAuth sign-in to an MCP server in the person's browser, as Claude
 * Desktop, Cursor and Codex do it — except that the browser comes back to GateRook on this computer, and GateRook keeps
 * the tokens. Sign in starts the sign-in at the core (`start`), and the desktop opens its page by the sign-in's id (no
 * URL crosses IPC; in a browser the page is a link to copy). While it waits: open the page again, cancel, or paste the
 * address the browser ended on when the redirect can't reach this computer. It ends signed in (`signedIn`), failed or
 * expired, with Try again.
 *
 * A device sign-in (GateRook's own app at a provider such as GitHub, §7.6) shows its one-time code instead: Copy code
 * and open copies it and opens the provider's page, where the person enters it and allows access; the core finishes
 * by itself. Nothing comes back to this computer, so there is no address to paste, and the page may be opened on any
 * device.
 */
@Component({
  selector: 'div[gwMcpSignIn]',
  imports: [GwIcon, GwToggle, GwCopy],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-stack gw-stack--sm' },
  template: `
    @let s = signIn();
    @if (!s || s.state === 'cancelled') {
      <div><button class="gw-btn" type="button" [id]="uid + '-start'" [attr.autofocus]="autofocus() ? '' : null" (click)="begin()" [attr.aria-busy]="busy() ? 'true' : null" [attr.aria-disabled]="busy() || blocked() ? 'true' : null" [attr.aria-describedby]="uid + '-hint'">@if (busy()) {<span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span>} @else {<svg lucideIcon="log-in" class="gw-icon"></svg>} Sign in{{ host() ? ' with ' + host() : '' }}</button></div>
      <p class="gw-field__hint" [id]="uid + '-hint'">{{ blocked() || (device()
        ? 'Shows a one-time code to enter on ' + (host() || 'the provider') + ', in a browser on any device. Nothing to set up. GateRook keeps the sign-in encrypted and renews it; the agent never sees it.'
        : 'Opens your browser. After you allow access there, the browser comes back to GateRook on this computer. GateRook keeps the sign-in encrypted and renews it; the agent never sees it.') }}</p>
    } @else if (s.state === 'waiting' && s.userCode) {
      <span class="gw-status gw-status--checking" role="status"><span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span><span class="gw-status__label">Waiting for you to enter the code{{ s.authorizationHost ? ' at ' + s.authorizationHost : '' }}</span></span>
      <div class="gw-field">
        <label class="gw-field__label" [for]="uid + '-code'">One-time code</label>
        <div class="gw-copy-field">
          <input class="gw-copy-field__value" [id]="uid + '-code'" type="text" readonly [value]="s.userCode" [attr.aria-describedby]="uid + '-code-hint'">
          <button class="gw-btn gw-btn--sm gw-copy-field__button" type="button" [gwCopyFrom]="'#' + uid + '-code'">
            <span class="gw-copy-field__copy"><svg lucideIcon="copy" class="gw-icon gw-icon--sm"></svg> Copy<span class="gw-sr-only"> code</span></span>
            <span class="gw-copy-field__copied"><svg lucideIcon="check" class="gw-icon gw-icon--sm"></svg> Copied</span>
          </button>
        </div>
        <p class="gw-field__hint" [id]="uid + '-code-hint'">Enter it on {{ s.authorizationHost || 'the provider' }}’s page and allow access there. GateRook finishes the sign-in by itself. The code works once and expires in a few minutes.</p>
      </div>
      @if (!desktop.opensSignIns && s.authorizationUrl) {
        <div class="gw-field">
          <label class="gw-field__label" [for]="uid + '-page'">Page to enter the code on</label>
          <div class="gw-copy-field">
            <input class="gw-copy-field__value" [id]="uid + '-page'" type="text" readonly [value]="s.authorizationUrl" [attr.aria-describedby]="uid + '-page-hint'">
            <button class="gw-btn gw-btn--sm gw-copy-field__button" type="button" [gwCopyFrom]="'#' + uid + '-page'">
              <span class="gw-copy-field__copy"><svg lucideIcon="copy" class="gw-icon gw-icon--sm"></svg> Copy<span class="gw-sr-only"> page address</span></span>
              <span class="gw-copy-field__copied"><svg lucideIcon="check" class="gw-icon gw-icon--sm"></svg> Copied</span>
            </button>
          </div>
          <p class="gw-field__hint" [id]="uid + '-page-hint'">Open it in a browser on any device.</p>
        </div>
      }
      <div class="gw-toolbar">
        <div class="gw-toolbar__start">
          @if (desktop.opensSignIns) {
            <button class="gw-btn gw-btn--sm" type="button" [id]="uid + '-open'" (click)="copyAndOpen()"><svg lucideIcon="external-link" class="gw-icon gw-icon--sm"></svg> Copy code and open {{ s.authorizationHost || 'the page' }}</button>
          }
          <button class="gw-btn gw-btn--ghost gw-btn--sm" type="button" (click)="cancel()">Cancel sign-in</button>
        </div>
      </div>
    } @else if (s.state === 'waiting') {
      <span class="gw-status gw-status--checking" role="status"><span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span><span class="gw-status__label">Waiting for you to sign in{{ s.authorizationHost ? ' at ' + s.authorizationHost : '' }} in your browser</span></span>
      @if (!desktop.opensSignIns && s.authorizationUrl) {
        <div class="gw-field">
          <label class="gw-field__label" [for]="uid + '-page'">Sign-in page</label>
          <div class="gw-copy-field">
            <input class="gw-copy-field__value" [id]="uid + '-page'" type="text" readonly [value]="s.authorizationUrl" [attr.aria-describedby]="uid + '-page-hint'">
            <button class="gw-btn gw-btn--sm gw-copy-field__button" type="button" [gwCopyFrom]="'#' + uid + '-page'">
              <span class="gw-copy-field__copy"><svg lucideIcon="copy" class="gw-icon gw-icon--sm"></svg> Copy<span class="gw-sr-only"> sign-in page</span></span>
              <span class="gw-copy-field__copied"><svg lucideIcon="check" class="gw-icon gw-icon--sm"></svg> Copied</span>
            </button>
          </div>
          <p class="gw-field__hint" [id]="uid + '-page-hint'">Open it in your browser on this computer.</p>
        </div>
      }
      <div class="gw-toolbar">
        <div class="gw-toolbar__start">
          @if (desktop.opensSignIns) {
            <button class="gw-btn gw-btn--sm" type="button" (click)="open()"><svg lucideIcon="external-link" class="gw-icon gw-icon--sm"></svg> Open the sign-in page again</button>
          }
          <button class="gw-btn gw-btn--ghost gw-btn--sm" type="button" (click)="cancel()">Cancel sign-in</button>
          <button class="gw-btn gw-btn--ghost gw-btn--sm" type="button" gwToggle [attr.aria-controls]="uid + '-paste'">The browser can’t reach GateRook? <svg lucideIcon="chevron-down" class="gw-icon gw-icon--sm gw-btn__chevron"></svg></button>
        </div>
      </div>
      <div class="gw-stack gw-stack--sm" [id]="uid + '-paste'" hidden>
        <div class="gw-field">
          <label class="gw-field__label" [for]="uid + '-address'">Address the browser shows</label>
          <input class="gw-input gw-input--mono" [id]="uid + '-address'" type="url" spellcheck="false" autocomplete="off" [placeholder]="(s.redirectUri || 'http://localhost:…/callback') + '?code=…&state=…'" (input)="pasteError.set(null)" [attr.aria-invalid]="pasteError() ? 'true' : null" [attr.aria-describedby]="uid + '-address-hint' + (pasteError() ? ' ' + uid + '-address-err' : '')">
          <p class="gw-field__hint" [id]="uid + '-address-hint'">After you allowed access, the browser goes to <span class="gw-mono">{{ s.redirectUri }}</span>. When that page can’t open — the browser is on another device, say — copy its whole address from the address bar here.</p>
          @if (pasteError()) {<p class="gw-field__error" [id]="uid + '-address-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ pasteError() }}</p>}
        </div>
        <div><button class="gw-btn gw-btn--sm" type="button" (click)="paste()" [attr.aria-busy]="busy() ? 'true' : null" [attr.aria-disabled]="busy() ? 'true' : null">@if (busy()) {<span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span>}Finish sign-in</button></div>
      </div>
    } @else if (s.state === 'signed-in') {
      <span class="gw-status gw-status--success" role="status"><span class="gw-status__dot" aria-hidden="true"></span><span class="gw-status__label">Signed in{{ s.authorizationHost ? ' at ' + s.authorizationHost : '' }}</span></span>
      @if (s.scope) {<p class="gw-field__hint">Permissions asked for: <span class="gw-mono">{{ s.scope }}</span></p>}
    } @else {
      <div class="gw-alert gw-alert--danger gw-alert--compact" role="alert">
        <svg lucideIcon="circle-alert" class="gw-icon gw-alert__icon"></svg>
        <div class="gw-alert__content"><p class="gw-alert__title">{{ s.state === 'expired' ? 'The sign-in expired' : 'The sign-in didn’t finish' }}</p><p class="gw-alert__body">{{ s.message }}</p></div>
        <div class="gw-alert__actions"><button class="gw-btn gw-btn--sm" type="button" (click)="begin()">Try again<span class="gw-sr-only"> to sign in</span></button></div>
      </div>
    }
    @if (error()) {
      <div class="gw-alert gw-alert--danger gw-alert--compact" role="alert">
        <svg lucideIcon="circle-alert" class="gw-icon gw-alert__icon"></svg>
        <div class="gw-alert__content"><p class="gw-alert__title">The sign-in didn’t start</p><p class="gw-alert__body">{{ error() }}</p></div>
      </div>
    }
  `,
})
export class GwMcpSignIn {
  private readonly api = inject(McpServersApi);
  protected readonly desktop = inject(DesktopBridge);
  private readonly events = inject(EventStream);
  private readonly announcer = inject(Announcer);
  private readonly clipboard = inject(ClipboardWriter);
  private readonly host$: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;

  /** Starts the sign-in at the core: a draft's (`POST /api/mcp-sign-ins`) or a stored server's. */
  readonly start = input.required<() => Promise<McpSignIn>>();
  /** The host of the sign-in page, for the button ("Sign in with mcp.linear.app"). */
  readonly host = input<string | null>(null);
  /** Why Sign in can't start yet (a client ID is missing), or null. */
  readonly blocked = input<string | null>(null);
  /** The sign-in will be a device sign-in with a one-time code (GateRook's own app there), for the hint before it starts. */
  readonly device = input(false);
  /** Sign in is the dialog's initial focus (the dialog of a stored server). */
  readonly autofocus = input(false);
  readonly signedIn = output<McpSignIn>();

  protected readonly uid = `msi-${++nextId}`;
  readonly signIn = signal<McpSignIn | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly pasteError = signal<string | null>(null);
  private timer: ReturnType<typeof setInterval> | null = null;
  private readonly off: () => void;

  constructor() {
    this.off = this.events.on('mcp-server.sign-in', (e) => {
      const s = this.signIn();
      if (s && e?.id === s.id && s.state === 'waiting') void this.refresh();
    });
    inject(DestroyRef).onDestroy(() => {
      this.off();
      this.stopPolling();
      this.abandon();
    });
  }

  /** Forgets the sign-in (a new review, a closed dialog): the core forgets it too. */
  reset(): void {
    this.abandon();
    this.stopPolling();
    this.signIn.set(null);
    this.error.set(null);
    this.pasteError.set(null);
    this.busy.set(false);
  }

  protected async begin(): Promise<void> {
    if (this.busy() || this.blocked()) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      const s = await this.start()();
      this.signIn.set(s);
      this.watch();
      if (s.state === 'waiting' && s.userCode) {
        // The code first: the person opens the page once they have it.
        this.announcer.announce(`Sign-in started. Enter the one-time code${s.authorizationHost ? ` on ${s.authorizationHost}` : ''}.`);
        setTimeout(() => this.host$.querySelector<HTMLElement>(`#${this.uid}-${this.desktop.opensSignIns ? 'open' : 'code'}`)?.focus());
      } else if (s.state === 'waiting') {
        this.announcer.announce('Sign-in started in your browser');
        if (this.desktop.opensSignIns) await this.open();
      }
    } catch (e) {
      this.error.set(failureText(e, 'This build of the gateway core can’t sign in to MCP servers yet.'));
    } finally {
      this.busy.set(false);
    }
  }

  protected async open(): Promise<void> {
    const s = this.signIn();
    if (!s) return;
    try {
      await this.desktop.openMcpSignIn(s.id);
    } catch (e) {
      this.error.set(failureText(e, 'The browser didn’t open.'));
    }
  }

  /** A device sign-in's code goes to the clipboard, then the page to enter it on opens. */
  protected async copyAndOpen(): Promise<void> {
    const s = this.signIn();
    if (!s?.userCode) return;
    const copied = await this.clipboard.write(s.userCode);
    this.announcer.announce(copied ? 'Code copied. Paste it on the page that opens.' : COPY_FAILED, { assertive: !copied });
    await this.open();
  }

  protected async cancel(): Promise<void> {
    const s = this.signIn();
    this.stopPolling();
    this.signIn.set(null);
    if (s) await this.api.cancelSignIn(s.id).catch(() => undefined);
    this.host$.querySelector<HTMLElement>(`#${this.uid}-start`)?.focus();
  }

  protected async paste(): Promise<void> {
    const s = this.signIn();
    const field = this.host$.querySelector<HTMLInputElement>(`#${this.uid}-address`);
    const address = field?.value.trim() ?? '';
    if (!s || this.busy()) return;
    if (!/^https?:\/\/\S+\?\S+$/i.test(address)) {
      this.pasteError.set('Paste the whole address, starting with http, with its code and state.');
      field?.focus();
      return;
    }
    this.busy.set(true);
    try {
      this.update(await this.api.finishSignIn(s.id, address));
      if (field) field.value = '';
    } catch (e) {
      this.pasteError.set(failureText(e, 'That address didn’t finish the sign-in.'));
      field?.focus();
    } finally {
      this.busy.set(false);
    }
  }

  private async refresh(): Promise<void> {
    const s = this.signIn();
    if (!s) return;
    try {
      this.update(await this.api.signIn(s.id));
    } catch {
      // Gone at the core (expired, the core restarted): the next look says so.
      this.update({ ...s, state: 'expired', message: 'The sign-in ended before it finished. Start it again.' });
    }
  }

  private update(next: McpSignIn): void {
    const before = this.signIn();
    if (!before || before.id !== next.id) return;
    this.signIn.set(next);
    if (next.state !== 'waiting') {
      this.stopPolling();
      if (next.state === 'signed-in' && before.state !== 'signed-in') {
        this.announcer.announce('Signed in');
        this.signedIn.emit(next);
      }
    }
  }

  private watch(): void {
    this.stopPolling();
    this.timer = setInterval(() => void this.refresh(), POLL_MS);
  }

  private stopPolling(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  /** A sign-in left behind: one still waiting stops listening, and a new server's unused one is forgotten. */
  private abandon(): void {
    const s = this.signIn();
    if (s?.state === 'waiting' || s?.state === 'signed-in') void this.api.cancelSignIn(s.id).catch(() => undefined);
  }
}
