import { ChangeDetectionStrategy, Component, ElementRef, computed, effect, inject, input, output, untracked, viewChild } from '@angular/core';
import { OAuthConsent } from '../../../core/api/tunnel.api';
import { TimeService } from '../../../core/time/time.service';
import { ActivationGuard } from '../../activation-guard';
import { GwDialog, GwDialogClose } from '../../dialog';
import { GwIcon } from '../../icons';
import { answerWithin, consentTools, consentWants } from './tunnel-view';

let nextId = 0;

/**
 * `<gw-oauth-consent [consent]="c" (decide)="…">` (client-card.html, the sign-in request): shown on this computer
 * when someone signs in through the tunnel (`oauth.consent.requested`). It opens by itself when `consent` is set and
 * closes when it is cleared. Deny is the default: it has focus, and Esc, the close button and the backdrop all deny;
 * "Allow access" is the dialog's primary, never `--approve`. No answer in time denies on the core.
 *
 * Self-approval through the GUI (DECISIONS 6, §9): "Allow access" ignores activation for 700 ms after the request
 * appears, after the window gets focus and after a deep link (`ActivationGuard`); Deny is never delayed. Enter never
 * allows: Deny has the focus whenever a request appears (another one in the open dialog too), and "Allow access"
 * ignores Enter — a click or Space after the delay, nothing else.
 *
 * The shell mounts one for the whole app (`App`, fed by `ConsentStore`): a sign-in waits for an answer on any page.
 */
@Component({
  selector: 'gw-oauth-consent',
  imports: [GwDialog, GwDialogClose, GwIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <dialog class="gw-dialog gw-dialog--md gw-dialog--warning" gwDialog #dialog="gwDialog" [attr.aria-labelledby]="consent() ? uid + '-t' : null" [attr.aria-describedby]="consent() ? uid + '-d' : null" (closed)="onClosed($event)">
      @if (consent(); as c) {
        <header class="gw-dialog__header">
          <span class="gw-icon-tile"><svg lucideIcon="log-in" class="gw-icon"></svg></span>
          <h2 class="gw-dialog__title" [id]="uid + '-t'">Allow {{ c.clientHost }} to sign in to <span class="gw-mono">{{ c.endpoint }}</span>?</h2>
          <p class="gw-dialog__description" [id]="uid + '-d'">Someone signed in to {{ c.clientHost }} asked to connect. Allow it only if it was you and the code matches the one on the {{ c.clientHost }} page.</p>
          <button class="gw-btn gw-btn--ghost gw-btn--icon gw-btn--sm gw-dialog__close" type="button" aria-label="Close and deny" gwDialogClose="deny"><svg lucideIcon="x" class="gw-icon"></svg></button>
        </header>
        <div class="gw-dialog__body">
          <dl class="gw-kv">
            <div class="gw-kv__row"><dt class="gw-kv__key">Code</dt><dd class="gw-kv__value"><span class="gw-mono gw-text-h2">{{ c.code }}</span></dd></div>
            <div class="gw-kv__row"><dt class="gw-kv__key">Wants</dt><dd class="gw-kv__value">{{ wants() }} <span class="gw-mono">{{ c.endpoint }}</span> <span class="gw-sr-only">environment</span> <span class="gw-env gw-env--{{ c.env }} gw-env--sm">{{ c.env }}</span>@if (tools(); as t) {<span class="gw-fg-muted"> · {{ t }}</span>}</dd></div>
            <div class="gw-kv__row"><dt class="gw-kv__key">Returns to</dt><dd class="gw-kv__value gw-mono">{{ c.redirectHost }}</dd></div>
            <div class="gw-kv__row"><dt class="gw-kv__key">From</dt><dd class="gw-kv__value">@if (c.ip) {<span class="gw-mono">{{ c.ip }}</span>} @else {An unknown address}</dd></div>
            <div class="gw-kv__row"><dt class="gw-kv__key">Answer within</dt><dd class="gw-kv__value">{{ within() }} <span class="gw-fg-muted">· no answer denies</span></dd></div>
          </dl>
        </div>
        <footer class="gw-dialog__footer">
          <button #deny class="gw-btn" type="button" gwDialogClose="deny" autofocus>Deny</button>
          <button class="gw-btn gw-btn--primary" type="button" (keydown.enter)="$event.preventDefault()" (click)="allow()">Allow access</button>
        </footer>
      }
    </dialog>
  `,
})
export class GwOAuthConsent {
  private readonly time = inject(TimeService);
  /** The 700 ms activation delay of "Allow access". */
  private readonly arm = inject(ActivationGuard).arm();

  readonly consent = input<OAuthConsent | null>(null);
  /** Allow only from the Allow button; every other way out denies. */
  readonly decide = output<{ readonly id: string; readonly decision: 'allow' | 'deny' }>();

  protected readonly uid = `oc-${++nextId}`;
  private readonly dialog = viewChild.required<GwDialog>('dialog');
  private readonly denyButton = viewChild<ElementRef<HTMLButtonElement>>('deny');
  /** The consent the open dialog is about (the input may already be the next one when it closes). */
  private shown: OAuthConsent | null = null;

  protected readonly wants = computed(() => {
    const c = this.consent();
    return c ? consentWants(c) : '';
  });
  protected readonly tools = computed(() => {
    const c = this.consent();
    return c ? consentTools(c) : null;
  });
  protected readonly within = computed(() => {
    const c = this.consent();
    return c ? answerWithin(c.expiresAt, this.time.now()) : '';
  });

  constructor() {
    effect(() => {
      const c = this.consent();
      untracked(() => {
        const d = this.dialog();
        if (c && !d.isOpen()) {
          this.shown = c;
          // Effects run during change detection, before this view renders the consent: open once that pass is over,
          // so the dialog's initial focus is Deny.
          queueMicrotask(() => {
            d.open();
            this.arm.reset();
          });
        } else if (!c && d.isOpen()) {
          this.shown = null;
          d.close('gone');
        } else if (c) {
          // Another request in the open dialog: the delay starts again for it, and Deny takes the focus back, so an
          // Enter meant for the last one can't answer this one.
          if (this.shown?.id !== c.id) {
            this.arm.reset();
            queueMicrotask(() => this.denyButton()?.nativeElement.focus());
          }
          this.shown = c;
        }
      });
    });
  }

  /** "Allow access": only a person's click, 700 ms after the request appeared, closes the dialog with allow. */
  protected allow(): void {
    if (!this.arm.ready()) return;
    this.dialog().close('allow');
  }

  protected onClosed(value: string): void {
    const c = this.shown;
    this.shown = null;
    if (!c || value === 'gone') return;
    this.decide.emit({ id: c.id, decision: value === 'allow' ? 'allow' : 'deny' });
  }
}
