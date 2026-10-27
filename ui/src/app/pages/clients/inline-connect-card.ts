import { ChangeDetectionStrategy, Component, afterNextRender, booleanAttribute, computed, input, output, viewChild } from '@angular/core';
import { ClientCard, ConnectPreview, ConnectRequest } from '../../core/api/clients.api';
import { GwClientConnectFlow } from '../../kit/elements/client-card/client-connect-flow';
import { ClientEndpoint, ClientFailure } from '../../kit/elements/client-card/client-view';
import { clientStatusLook } from '../../kit/elements/shared/dictionaries';
import { GwClientMark } from '../../kit/elements/shared/client-mark';
import { GwIcon } from '../../kit/icons';

let nextId = 0;

/**
 * `<article gwInlineConnectCard [card]="cursor" …>` (pages/clients.html, Cursor): the detected client that is the
 * only thing left to do on this computer, first and full width, with Connect open in the card — the endpoint, how
 * it signs in, the token it would get, the exact change of its config and the backup — and Connect as the card's
 * primary. Nothing is written until Connect, and Connect writes only the change on screen: without a preview of the
 * current selection (it is on its way, or the core refused it) Connect asks the core again instead. A refused change
 * is the kit's "Connect failed" in the card, above its footer.
 */
@Component({
  selector: 'article[gwInlineConnectCard]',
  imports: [GwIcon, GwClientMark, GwClientConnectFlow],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-card gw-client-card', '[attr.aria-labelledby]': 'uid + "-t"' },
  template: `
    <header class="gw-card__header gw-client-card__header">
      <div class="gw-client-card__identity">
        <span [gwClientMark]="card().client" size="lg"></span>
        <div class="gw-card__heading">
          <h3 class="gw-card__title" [id]="uid + '-t'">{{ card().name }}</h3>
          <p class="gw-card__description gw-client-card__via gw-mono">{{ card().configDisplay }}</p>
        </div>
      </div>
      <span class="gw-status gw-status--{{ look().tone }} gw-client-card__status"><span class="gw-status__dot" aria-hidden="true"></span><span class="gw-status__label">{{ look().text }}</span></span>
    </header>
    <div class="gw-card__body gw-client-card__body">
      <div gwClientConnectFlow #flow page [card]="card()" [endpoints]="endpoints()" [preview]="preview()" [loading]="loading()" [failure]="failure()" [idPrefix]="uid" [level]="4" [wrap]="true" [canOpenConfig]="canOpenConfig()" (openConfig)="openConfig.emit()" (requestChange)="onRequest($event)"></div>
    </div>
    <footer class="gw-card__footer gw-client-card__footer">
      <div class="gw-client-card__actions">
        <button class="gw-btn gw-btn--ghost" type="button" [attr.aria-disabled]="canOpenConfig() ? null : 'true'" [attr.aria-describedby]="canOpenConfig() ? null : uid + '-oc-why'" (click)="canOpenConfig() && openConfig.emit()">Open config<span class="gw-sr-only"> of {{ card().name }}</span></button>
        @if (!canOpenConfig()) {
          <span class="gw-sr-only" [id]="uid + '-oc-why'">Opens the file in the desktop app</span>
        }
        @if (busy()) {
          <p class="gw-sr-only" [id]="uid + '-busy'">{{ connecting() ? 'Writing the change' : 'Preparing the exact change' }}</p>
        }
        <button class="gw-btn gw-btn--primary" type="button" [attr.aria-busy]="connecting() ? 'true' : null" [attr.aria-disabled]="busy() ? 'true' : null" [attr.aria-describedby]="describedBy()" (click)="onConnect()">@if (connecting()) {<span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span>} @else {<svg lucideIcon="plug-zap" class="gw-icon"></svg>} Connect {{ card().name }}</button>
      </div>
    </footer>
  `,
})
export class GwInlineConnectCard {
  readonly card = input.required<ClientCard>();
  readonly endpoints = input.required<readonly ClientEndpoint[]>();
  readonly preview = input<ConnectPreview | null>(null);
  readonly loading = input(false, { transform: booleanAttribute });
  readonly failure = input<ClientFailure | null>(null);
  /** The write is on its way. */
  readonly connecting = input(false, { transform: booleanAttribute });
  readonly canOpenConfig = input(true, { transform: booleanAttribute });

  /** The selection changed (or Connect found no preview to write): ask the core for the exact change. */
  readonly previewRequest = output<ConnectRequest>();
  readonly connect = output<{ readonly previewId: string; readonly request: ConnectRequest }>();
  readonly openConfig = output<void>();

  protected readonly uid = `icc-${++nextId}`;
  private readonly flow = viewChild.required<GwClientConnectFlow>('flow');
  protected readonly look = computed(() => clientStatusLook(this.card().status));
  protected readonly busy = computed(() => this.loading() || this.connecting());
  /** Connect's description: why it waits, and the endpoint's warnings (prod) the flow shows under its select. */
  protected readonly describedBy = computed(() => {
    const ids = [this.busy() ? `${this.uid}-busy` : null, this.flow().warnings().length ? this.flow().warningsId() : null].filter(Boolean);
    return ids.length ? ids.join(' ') : null;
  });

  constructor() {
    // Inline the flow is on screen from the start: preview the default selection once.
    afterNextRender(() => this.flow().emit());
  }

  protected onRequest(request: ConnectRequest | null): void {
    if (request) this.previewRequest.emit(request);
  }

  /** Writes the change on screen; with none for this selection (refused, or never prepared) it asks again. */
  protected onConnect(): void {
    if (this.busy()) return;
    const request = this.flow().request();
    if (!request) return; // the token name is invalid: its field says why
    const p = this.preview();
    if (p && !this.failure() && p.endpoint === request.endpoint && p.method === request.method) {
      this.connect.emit({ previewId: p.previewId, request });
    } else {
      this.previewRequest.emit(request);
    }
  }
}
