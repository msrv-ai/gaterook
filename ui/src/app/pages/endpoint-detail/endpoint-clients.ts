import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { isApiError } from '../../core/api/api-error';
import { ClientCard, ClientId, ClientLink, ConnectPreview, ConnectRequest } from '../../core/api/clients.api';
import { DesktopBridge } from '../../core/desktop/desktop-bridge';
import { EndpointsStore } from '../../core/stores/endpoints.store';
import { GwClientCard } from '../../kit/elements/client-card/client-card';
import { ClientEndpoint, ClientFailure, browserInstallUrl, clientFailure, keeps, usesBridgeExtension } from '../../kit/elements/client-card/client-view';
import { GwIcon } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { EndpointDetailData } from './endpoint-detail.data';
import { connectedToast, disconnectedToast, endpointClients } from './endpoint-detail-view';

const NO_ANSWER = 'The core didn’t answer. Try again.';

/**
 * Endpoint detail › Clients (design/pages/endpoint-detail.html `#clients`): the MCP clients of this computer that use
 * the endpoint, then those installed but not connected to it. One compact client-card per client: how it reaches the
 * endpoint, its other endpoints, how it gets a token, its last call; View activity and Disconnect, or Connect.
 *
 * Connect opens the client's connect dialog on this endpoint (it is first in the dialog's choice); Disconnect asks
 * first with the core's disconnect preview. The cards ask, this tab calls the core (`/api/clients`) and toasts.
 */
@Component({
  selector: 'section[gwEndpointClients]',
  imports: [GwClientCard, GwIcon, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-section', 'aria-labelledby': 'ep-clients-title' },
  templateUrl: './endpoint-clients.html',
})
export class GwEndpointClients {
  protected readonly data = inject(EndpointDetailData);
  private readonly store = inject(EndpointsStore);
  private readonly toasts = inject(ToastService);
  private readonly desktop = inject(DesktopBridge);

  readonly slug = input.required<string>();

  protected readonly cards = computed(() => endpointClients(this.data.clients(), this.slug()));
  /** Connect's endpoint choice: this endpoint first (the dialog starts on it), then the others. */
  protected readonly endpoints = computed<ClientEndpoint[]>(() => {
    const here = this.data.summary();
    const others = (this.store.list() ?? []).filter((e) => e.slug !== this.slug());
    return here ? [here, ...others] : others;
  });

  protected readonly preview = signal<ConnectPreview | null>(null);
  protected readonly previewLoading = signal(false);
  protected readonly previewFailure = signal<{ readonly client: ClientId; readonly failure: ClientFailure } | null>(null);
  /** A failed write: its card shows what failed and Try again. */
  protected readonly failures = signal<ReadonlyMap<ClientId, ClientFailure>>(new Map());
  protected readonly leaving = signal<ConnectPreview | null>(null);
  private previewSeq = 0;

  protected failureOf(client: ClientId): ClientFailure | null {
    return this.failures().get(client) ?? null;
  }

  protected connectFailureOf(client: ClientId): ClientFailure | null {
    const f = this.previewFailure();
    return f && f.client === client ? f.failure : null;
  }

  protected retry(): void {
    void this.data.reloadClients();
  }

  // ── Connect ───────────────────────────────────────────────────────────────────────────────────────────────────

  protected onConnectStart(): void {
    this.preview.set(null);
    this.previewFailure.set(null);
  }

  /** Every change of the dialog's choices asks for a new preview; only the latest answer is shown. */
  protected async previewFor(card: ClientCard, request: ConnectRequest): Promise<void> {
    const seq = ++this.previewSeq;
    this.previewLoading.set(true);
    this.previewFailure.set(null);
    try {
      const p = await this.data.connectPreview(card.client, request);
      if (seq === this.previewSeq) this.preview.set(p);
    } catch (e) {
      if (seq !== this.previewSeq) return;
      this.preview.set(null);
      this.previewFailure.set({ client: card.client, failure: this.failure(e, card) });
    } finally {
      if (seq === this.previewSeq) this.previewLoading.set(false);
    }
  }

  protected async connect(card: ClientCard, previewId: string, request: ConnectRequest | null): Promise<void> {
    this.setFailure(card.client, null);
    const preview = this.preview();
    try {
      const result = await this.data.connect(card.client, previewId);
      const browserUrl = card.client === 'cursor' && result.link.method === 'oauth' && preview?.previewId === previewId ? browserInstallUrl(preview.link) : null;
      if (browserUrl) {
        try {
          await this.desktop.openExternal(browserUrl);
        } catch (e) {
          this.toasts.show({ tone: 'danger', title: 'Cursor is configured, but the browser did not open', body: e instanceof Error ? e.message : NO_ANSWER });
          return;
        }
      }
      const t = connectedToast(card.name, request?.endpoint ?? this.slug(), result.notes);
      this.toasts.show({ tone: t.tone, title: t.title, body: t.body });
    } catch (e) {
      // Nothing was written: the card says what failed and offers Try again (kit client-card "Error").
      this.setFailure(card.client, this.failure(e, card));
      this.toasts.show({ tone: 'danger', title: `${card.name} wasn't connected`, body: isApiError(e) ? e.detail : NO_ANSWER });
    }
  }

  // ── Disconnect ────────────────────────────────────────────────────────────────────────────────────────────────

  protected async disconnectFor(link: ClientLink): Promise<void> {
    this.leaving.set(null);
    try {
      this.leaving.set(await this.data.disconnectPreview(link.id));
    } catch (e) {
      this.toasts.show({ tone: 'danger', title: "What Disconnect changes didn't load", body: isApiError(e) ? e.detail : NO_ANSWER });
    }
  }

  protected async disconnect(card: ClientCard, link: ClientLink, previewId: string): Promise<void> {
    try {
      const result = await this.data.disconnect(link.id, previewId);
      const t = disconnectedToast(card.name, link.endpoint, keeps(card, link), usesBridgeExtension(card), result.rejectedApprovals);
      this.toasts.show({ tone: t.tone, title: t.title, body: t.body });
    } catch (e) {
      this.toasts.show({ tone: 'danger', title: `${card.name} wasn't disconnected`, body: isApiError(e) ? e.detail : NO_ANSWER });
    } finally {
      this.leaving.set(null);
    }
  }

  private failure(e: unknown, card: ClientCard): ClientFailure {
    return isApiError(e) ? clientFailure(e, card) : { title: [{ text: `${card.name} wasn't connected`, code: false }], body: [{ text: NO_ANSWER, code: false }], fix: null };
  }

  private setFailure(client: ClientId, failure: ClientFailure | null): void {
    this.failures.update((m) => {
      const next = new Map(m);
      if (failure) next.set(client, failure);
      else next.delete(client);
      return next;
    });
  }
}
