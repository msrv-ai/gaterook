import { ChangeDetectionStrategy, Component, Injector, TemplateRef, afterNextRender, booleanAttribute, computed, effect, inject, input, model, output, signal, viewChild } from '@angular/core';
import { ClientCard, ConnectPreview, ConnectRequest } from '../../core/api/clients.api';
import { DEFAULT_TOKEN_TTL_DAYS, tokenTtlDays } from '../../core/api/token-lifetime';
import { GwClientConnectFlow } from '../../kit/elements/client-card/client-connect-flow';
import { GwClientDisclosure } from '../../kit/elements/client-card/client-disclosure';
import { ClientEndpoint, ClientFailure, viaText } from '../../kit/elements/client-card/client-view';
import { clientStatusLook } from '../../kit/elements/shared/dictionaries';
import { GwClientMark } from '../../kit/elements/shared/client-mark';
import { GwIcon } from '../../kit/icons';

let nextId = 0;

/**
 * `<article gwInlineConnectCard [card]="cursor" …>` (pages/clients.html, Cursor): a detected client's connection
 * review, revealed by its compact row on AI apps — the endpoint, how
 * it signs in, the token it would get, the exact change of its config and the backup — and Connect as the card's
 * primary. Cursor browser sign-in opens its install link without editing configuration. Other methods write only
 * the change on screen: without a preview of the
 * current selection (it is on its way, or the core refused it) Connect asks the core again instead. A refused change
 * is the kit's "Connect failed" in the card, above its footer.
 *
 * `review` is the setup state's Connect review (pages/clients.html `?state=setup`, `#cl-cd-s`): opened from Overview's
 * Connect (`#cl-<app>-t`), its heading takes the focus (`anchor`: `cl-cd-s-t`), the page's "What <app> can use" comes
 * right after the first line (`uses`), the one endpoint needs no select, and Connect <app> is the footer's only button.
 */
@Component({
  selector: 'article[gwInlineConnectCard]',
  imports: [GwIcon, GwClientMark, GwClientConnectFlow, GwClientDisclosure],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-card gw-client-card', '[class.gw-client-card--compact]': 'collapsible()', '[attr.aria-labelledby]': 'headingId()' },
  template: `
    @if (collapsible()) {
      <header gwClientDisclosure [client]="card().client" [name]="card().name" [tone]="look().tone" [status]="look().text" [headingId]="headingId()" [panelId]="uid + '-details'" [(expanded)]="expanded"></header>
    } @else {
    <header class="gw-card__header gw-client-card__header">
      <div class="gw-client-card__identity">
        <span [gwClientMark]="card().client" size="lg"></span>
        <div class="gw-card__heading">
          <h3 class="gw-card__title" [id]="headingId()" [attr.tabindex]="anchor() ? -1 : null">{{ card().name }}</h3>
          <p class="gw-card__description gw-client-card__via" [class.gw-mono]="via().mono">{{ via().text }}</p>
        </div>
      </div>
      <span class="gw-status gw-status--{{ look().tone }} gw-client-card__status"><span class="gw-status__dot" aria-hidden="true"></span><span class="gw-status__label">{{ look().text }}</span></span>
    </header>
    }
    <div [id]="uid + '-details'" [hidden]="collapsible() && !expanded()">
    @if (!collapsible() || opened()) {
    <div class="gw-card__body gw-client-card__body" [class.gw-stack]="collapsible()">
      @if (collapsible()) {
        <p class="gw-card__description gw-client-card__via" [class.gw-mono]="via().mono">{{ via().text }}</p>
      }
      <div gwClientConnectFlow #flow page [single]="review()" [lead]="uses()" [card]="card()" [defaultTtlDays]="defaultTtlDays()" [endpoints]="endpoints()" [preview]="preview()" [loading]="loading()" [connecting]="connecting()" [failure]="failure()" [idPrefix]="anchor() ?? uid" [level]="4" [wrap]="true" [canOpenConfig]="canOpenConfig()" (openConfig)="openConfig.emit()" (requestChange)="onRequest($event)" (browserSignIn)="onConnect()"></div>
    </div>
    <footer class="gw-card__footer gw-client-card__footer">
      <div class="gw-client-card__actions">
        @if (!review() && !browserOnly()) {
          <button class="gw-btn gw-btn--ghost" type="button" [attr.aria-disabled]="canOpenConfig() ? null : 'true'" [attr.aria-describedby]="canOpenConfig() ? null : uid + '-oc-why'" (click)="canOpenConfig() && openConfig.emit()">Open config<span class="gw-sr-only"> of {{ card().name }}</span></button>
          @if (!canOpenConfig()) {
            <span class="gw-sr-only" [id]="uid + '-oc-why'">Opens the file in the desktop app</span>
          }
        }
        @if (busy()) {
          <p class="gw-sr-only" [id]="uid + '-busy'">{{ browserOnly() ? 'Opening browser sign-in' : connecting() ? 'Writing the change' : 'Preparing the exact change' }}</p>
        }
        <button class="gw-btn gw-btn--primary" type="button" [attr.aria-busy]="connecting() ? 'true' : null" [attr.aria-disabled]="busy() ? 'true' : null" [attr.aria-describedby]="describedBy()" (click)="onConnect()">@if (connecting()) {<span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span>} @else {<svg lucideIcon="plug-zap" class="gw-icon"></svg>} Connect {{ card().name }}</button>
      </div>
    </footer>
    }
    </div>
  `,
})
export class GwInlineConnectCard {
  readonly card = input.required<ClientCard>();
  readonly defaultTtlDays = input(DEFAULT_TOKEN_TTL_DAYS, { transform: tokenTtlDays });
  readonly collapsible = input(false, { transform: booleanAttribute });
  readonly expanded = model(false);
  readonly endpoints = input.required<readonly ClientEndpoint[]>();
  readonly preview = input<ConnectPreview | null>(null);
  readonly loading = input(false, { transform: booleanAttribute });
  readonly failure = input<ClientFailure | null>(null);
  /** The write is on its way. */
  readonly connecting = input(false, { transform: booleanAttribute });
  readonly canOpenConfig = input(true, { transform: booleanAttribute });
  /** The card's id base on the page (`cl-cd-s`): the heading is `cl-cd-s-t` and takes the focus. */
  readonly anchor = input<string | null>(null);
  /** The setup review: one endpoint named above (no select), Connect alone in the footer. */
  readonly review = input(false, { transform: booleanAttribute });
  /** "What <app> can use", right after the flow's first line. */
  readonly uses = input<TemplateRef<unknown> | null>(null);

  /** The selection changed (or Connect found no preview to write): ask the core for the exact change. */
  readonly previewRequest = output<ConnectRequest>();
  readonly connect = output<{ readonly previewId: string | null; readonly request: ConnectRequest }>();
  readonly openConfig = output<void>();

  protected readonly uid = `icc-${++nextId}`;
  private readonly injector = inject(Injector);
  private readonly flow = viewChild<GwClientConnectFlow>('flow');
  protected readonly opened = signal(false);
  protected readonly look = computed(() => clientStatusLook(this.failure() ? 'error' : this.card().status));
  protected readonly browserOnly = computed(() => this.card().client === 'cursor' && this.flow()?.method() === 'oauth');
  protected readonly via = computed(() => this.browserOnly() ? { text: 'Sign in through the browser', mono: false } : viaText(this.card()));
  protected readonly headingId = computed(() => `${this.anchor() ?? this.uid}-t`);
  protected readonly busy = computed(() => (!this.browserOnly() && this.loading()) || this.connecting());
  /** Connect's description: why it waits, and the endpoint's warnings (prod) the flow shows under its select. */
  protected readonly describedBy = computed(() => {
    const flow = this.flow();
    const ids = [this.busy() ? `${this.uid}-busy` : null, flow?.warnings().length ? flow.warningsId() : null].filter(Boolean);
    return ids.length ? ids.join(' ') : null;
  });

  constructor() {
    // Prepare the change only after the user opens this app; folding keeps their choices intact.
    effect(() => {
      if (this.expanded()) this.opened.set(true);
    });
    effect(() => {
      const flow = this.flow();
      if (flow) afterNextRender(() => flow.emit(), { injector: this.injector });
    });
  }

  protected onRequest(request: ConnectRequest | null): void {
    if (request) this.previewRequest.emit(request);
  }

  /** Starts browser sign-in directly, or writes the reviewed change for a config-based method. */
  protected onConnect(): void {
    if (this.busy()) return;
    const request = this.flow()?.request();
    if (!request) return; // the token name is invalid: its field says why
    if (this.browserOnly()) {
      this.connect.emit({ previewId: null, request });
      return;
    }
    const p = this.preview();
    if (p && !this.failure() && p.endpoint === request.endpoint && p.method === request.method) {
      this.connect.emit({ previewId: p.previewId, request });
    } else {
      this.previewRequest.emit(request);
    }
  }
}
