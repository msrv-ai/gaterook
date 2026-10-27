import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { GwDialog, GwDialogClose } from '../../../kit/dialog';
import { GwIcon } from '../../../kit/icons';
import { TextPart } from '../../../kit/elements/shared/literals';
import { failureText } from './failure';

/**
 * What one confirmation asks (kit `dlg-uninstall-jira`, `dlg-remove-mcp`): `danger` for what can't be undone,
 * `warning` for what is risky but reversible. The confirm button names the object ("Uninstall plugin Jira Service
 * Desk"); `run` does it — the dialog stays open, busy, until it answers, and shows its refusal in place.
 */
export interface ConfirmRequest {
  readonly tone: 'danger' | 'warning';
  readonly icon: string;
  readonly title: string;
  /** The consequences in one paragraph; parts in code are literals (a secret's name). */
  readonly description: readonly TextPart[];
  readonly confirm: string;
  readonly run: () => Promise<void>;
}

let nextId = 0;

/**
 * `<dialog gwConfirmDialog>`: a small confirmation (`gw-dialog--sm`) with only a header and the footer, as the kit's
 * Uninstall and Remove confirmations. Focus starts on Cancel; Enter never confirms by default. Closes with `done`
 * once `run` resolved; a failure stays in the dialog as an alert, Cancel still works.
 */
@Component({
  selector: 'dialog[gwConfirmDialog]',
  imports: [GwIcon, GwDialogClose],
  hostDirectives: [{ directive: GwDialog, outputs: ['closed'] }],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[class]': '"gw-dialog gw-dialog--sm gw-dialog--" + (request()?.tone ?? "danger")',
    '[attr.aria-labelledby]': 'request() ? uid + "-t" : null',
    '[attr.aria-describedby]': 'request() ? uid + "-d" : null',
  },
  template: `
    @if (request(); as r) {
      <header class="gw-dialog__header">
        <span class="gw-icon-tile"><svg [lucideIcon]="r.icon" class="gw-icon"></svg></span>
        <h2 class="gw-dialog__title" [id]="uid + '-t'">{{ r.title }}</h2>
        <p class="gw-dialog__description" [id]="uid + '-d'">@for (p of r.description; track $index) {@if (p.code) {<span class="gw-mono">{{ p.text }}</span>} @else {{{ p.text }}}}</p>
        <button class="gw-btn gw-btn--ghost gw-btn--icon gw-btn--sm gw-dialog__close" type="button" aria-label="Close" gwDialogClose><svg lucideIcon="x" class="gw-icon"></svg></button>
      </header>
      @if (error()) {
        <div class="gw-dialog__body">
          <div class="gw-alert gw-alert--danger gw-alert--compact" role="alert">
            <svg lucideIcon="circle-alert" class="gw-icon gw-alert__icon"></svg>
            <div class="gw-alert__content"><p class="gw-alert__body">{{ error() }}</p></div>
          </div>
        </div>
      }
      <footer class="gw-dialog__footer">
        <button class="gw-btn" type="button" gwDialogClose autofocus>Cancel</button>
        <button
          [class]="'gw-btn ' + (r.tone === 'danger' ? 'gw-btn--danger' : 'gw-btn--primary')"
          type="button"
          [attr.aria-busy]="busy() ? 'true' : null"
          [attr.aria-disabled]="busy() ? 'true' : null"
          (click)="confirm()"
        >@if (busy()) {<span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span>}{{ r.confirm }}</button>
      </footer>
    }
  `,
})
export class GwConfirmDialog {
  private readonly dialog = inject(GwDialog);
  protected readonly uid = `cf-${++nextId}`;
  protected readonly request = signal<ConfirmRequest | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.dialog.closed.subscribe(() => this.busy.set(false));
  }

  /** Asks; focus returns to `opener` (a menu item's menu trigger) when the dialog closes. */
  ask(request: ConfirmRequest, opener: HTMLElement | null): void {
    this.request.set(request);
    this.busy.set(false);
    this.error.set(null);
    this.dialog.openAfterRender(opener);
  }

  protected async confirm(): Promise<void> {
    const r = this.request();
    if (!r || this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      await r.run();
      this.dialog.close('done');
    } catch (e) {
      this.error.set(failureText(e));
    } finally {
      this.busy.set(false);
    }
  }
}
