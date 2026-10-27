import { ChangeDetectionStrategy, Component, afterNextRender, booleanAttribute, computed, input, numberAttribute, output, viewChild } from '@angular/core';
import { ClientCard, ConnectPreview, ConnectRequest } from '../../../core/api/clients.api';
import { GwClientConnectFlow } from './client-connect-flow';
import { ClientEndpoint, ClientFailure, usesBridgeExtension } from './client-view';
import { ClientPreviewRequest } from './client-card';

let nextId = 0;

/**
 * `<section gwClientConnectCard [card]="codex" …>` (client-card.html, "Connect flow"): Connect opened inline in a
 * card instead of the dialog (the onboarding step, a page that is only about connecting). The same flow and rules as
 * the dialog: the exact change first, Confirm sends the preview's id.
 */
@Component({
  selector: 'section[gwClientConnectCard]',
  imports: [GwClientConnectFlow],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-card', '[attr.aria-labelledby]': 'uid + "-t"' },
  template: `
    <header class="gw-card__header">
      <div class="gw-card__heading">
        @if (level() === 2) {
          <h2 class="gw-card__title" [id]="uid + '-t'">Connect {{ card().name }}</h2>
        } @else {
          <h3 class="gw-card__title" [id]="uid + '-t'">Connect {{ card().name }}</h3>
        }
        <p class="gw-card__description">@if (bridge()) {Installs the bridge extension in Claude Desktop. Nothing is written to its config, and no token is stored.} @else {Adds one server to <span class="gw-mono">{{ card().configDisplay }}</span>. Nothing else in the file changes, and a backup is kept.}</p>
      </div>
    </header>
    <div class="gw-card__body">
      <div gwClientConnectFlow #flow [card]="card()" [endpoints]="endpoints()" [endpoint]="endpoint()" [preview]="preview()" [loading]="loading()" [failure]="failure()" [idPrefix]="uid" [level]="level() + 1" (requestChange)="onRequest($event)"></div>
    </div>
    <footer class="gw-card__footer">
      @if (blocked()) {
        <p class="gw-sr-only" [id]="uid + '-why'">{{ failure() ? 'The change can’t be written' : 'Waiting for the exact change' }}</p>
      }
      <button class="gw-btn" type="button" (click)="cancel.emit()">Cancel<span class="gw-sr-only"> connecting {{ card().name }}</span></button>
      <button class="gw-btn gw-btn--primary" type="button" [attr.aria-disabled]="blocked() ? 'true' : null" [attr.aria-describedby]="blocked() ? uid + '-why' : null" (click)="confirm()">Confirm and connect<span class="gw-sr-only"> {{ card().name }}</span></button>
    </footer>
  `,
})
export class GwClientConnectCard {
  readonly card = input.required<ClientCard>();
  readonly endpoints = input.required<readonly ClientEndpoint[]>();
  /** The endpoint to preselect. */
  readonly endpoint = input<string | null>(null);
  readonly preview = input<ConnectPreview | null>(null);
  readonly loading = input(false, { transform: booleanAttribute });
  readonly failure = input<ClientFailure | null>(null);
  readonly level = input(3, { transform: numberAttribute });

  readonly previewRequest = output<ClientPreviewRequest>();
  readonly connect = output<{ readonly client: ClientCard['client']; readonly previewId: string; readonly request: ConnectRequest | null }>();
  readonly cancel = output<void>();

  protected readonly uid = `ccf-${++nextId}`;
  private readonly flow = viewChild.required<GwClientConnectFlow>('flow');
  protected readonly bridge = computed(() => usesBridgeExtension(this.card()));
  protected readonly blocked = computed(() => !this.preview() || this.loading() || !!this.failure() || !this.flow().request());

  constructor() {
    // Inline, the flow is on screen from the start: preview the default selection once.
    afterNextRender(() => this.flow().emit());
  }

  protected onRequest(request: ConnectRequest | null): void {
    if (request) this.previewRequest.emit({ client: this.card().client, request });
  }

  protected confirm(): void {
    const p = this.preview();
    if (!p || this.blocked()) return;
    this.connect.emit({ client: this.card().client, previewId: p.previewId, request: this.flow().request() });
  }
}
