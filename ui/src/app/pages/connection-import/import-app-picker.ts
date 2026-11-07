import { ChangeDetectionStrategy, Component, DOCUMENT, DestroyRef, Injector, afterNextRender, computed, effect, inject, input, model, output, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ClientCard, ClientId, ConnectRequest } from '../../core/api/clients.api';
import { EndpointSummary } from '../../core/api/endpoints.api';
import { DesktopBridge, desktopErrorText } from '../../core/desktop/desktop-bridge';
import { GwCopy } from '../../kit/copy';
import { INSTALL_URL, browserInstallUrl } from '../../kit/elements/client-card/client-view';
import { GwClientMark } from '../../kit/elements/shared/client-mark';
import { GwIcon } from '../../kit/icons';
import { ClientsData } from '../clients/clients.data';
import { GwInlineConnectCard } from '../clients/inline-connect-card';

export interface ImportAppConsent { readonly kind: 'manual' | 'restart'; readonly key: string; }
export interface ImportAppSelection { readonly client: ClientId; readonly name: string; readonly ready: boolean; }

/** Endpoint-scoped app setup. A preview is read-only; only the inline Connect button writes it. */
@Component({
  selector: 'gw-import-app-picker',
  imports: [RouterLink, GwIcon, GwClientMark, GwCopy, GwInlineConnectCard],
  templateUrl: './import-app-picker.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-stack' },
})
export class ImportAppPicker {
  readonly data = inject(ClientsData);
  readonly desktop = inject(DesktopBridge);
  private readonly doc = inject(DOCUMENT);
  private readonly injector = inject(Injector);
  private readonly destroy = inject(DestroyRef);
  readonly endpoint = input<EndpointSummary | null>(null);
  readonly disabled = input(false);
  readonly cardsMode = input(false);
  readonly handoffMode = input(false);
  readonly analysisStarted = input(false);
  readonly startDisabledReason = input<string | null>(null);
  readonly client = model<ClientId | null>(null);
  readonly consent = model<ImportAppConsent | null>(null);
  readonly selectionChange = output<ImportAppSelection | null>();
  readonly startImport = output<ImportAppSelection>();
  readonly busyChange = output<boolean>();
  readonly connecting = signal(false);
  readonly reviewing = signal(false);
  readonly manual = signal(false);
  readonly message = signal('');
  readonly error = signal('');
  private readonly checkFailed = signal(false);
  readonly pending = signal<{ client: ClientId; endpoint: string; method: string } | null>(null);
  readonly pendingBrowserUrl = signal<string | null>(null);
  private readonly pendingMcpbLink = signal<string | null>(null);
  readonly cards = computed(() => this.data.cards());
  readonly card = computed(() => this.cards().find(c => c.client === this.client()) ?? null);
  readonly links = computed(() => this.card()?.links.filter(l => l.endpoint === this.endpoint()?.slug) ?? []);
  readonly connected = computed(() => this.links().find(l => l.status === 'connected') ?? null);
  readonly restart = computed(() => this.links().find(l => l.status === 'needs-restart') ?? null);
  readonly checking = computed(() => !this.data.loaded() || this.data.refreshing() || !!this.data.scanning() || (!!this.endpoint() && !this.endpoint()!.url && !this.data.manual().error && !this.data.manual().unavailable));
  readonly failed = computed(() => this.checkFailed() || !!this.data.clients().error || this.data.clients().unavailable || !!this.data.stale() || (!this.endpoint()?.url && (!!this.data.manual().error || this.data.manual().unavailable)));
  readonly connectedCount = computed(() => this.cards().filter(c => c.links.some(l => l.endpoint === this.endpoint()?.slug && l.status === 'connected')).length);
  readonly key = computed(() => `${this.endpoint()?.url ?? ''}|${this.endpoint()?.slug ?? ''}|${this.client() ?? ''}`);
  readonly restartKey = computed(() => `${this.key()}|${this.restart()?.id ?? ''}|${this.restart()?.method ?? ''}`);
  readonly manualAllowed = computed(() => !!this.card() && this.links().length === 0 && !this.isPending());
  readonly acknowledgedRestart = computed(() => this.restart()?.method !== 'mcpb' && this.consent()?.kind === 'restart' && this.consent()?.key === this.restartKey());
  readonly acknowledgedManual = computed(() => this.manualAllowed() && this.consent()?.kind === 'manual' && this.consent()?.key === this.key());
  readonly ready = computed(() => !!this.endpoint() && !!this.card() && !this.checking() && !this.failed() && !this.connecting() && !this.reviewing()
    && (!!this.connected() || (!!this.restart() && this.acknowledgedRestart()) || this.acknowledgedManual()));
  readonly preview = computed(() => {
    const client = this.client();
    const preview = client ? this.data.connectState(client).preview : null;
    return preview?.endpoint === this.endpoint()?.slug ? preview : null;
  });
  readonly scopedCard = computed<ClientCard | null>(() => {
    const card = this.card();
    if (!card) return null;
    const status = this.connected() ? 'connected' : this.restart() ? 'needs-restart' : this.links().length ? 'error' : card.detected ? 'detected' : 'not-installed';
    return { ...card, links: [...this.links()], status, statusText: this.status(card) };
  });
  readonly connectEndpoints = computed(() => this.endpoint() ? [this.endpoint()!] : []);
  readonly installUrl = computed(() => this.client() ? INSTALL_URL[this.client()!] : '');
  private context = '';
  private pendingRefresh: Promise<void> | null = null;

  constructor() {
    effect(() => {
      const key = this.key();
      if (key === this.context) return;
      this.context = key;
      untracked(() => {
        for (const card of this.cards()) this.data.resetConnect(card.client);
        this.reviewing.set(false); this.manual.set(false); this.error.set(''); this.message.set(''); this.pending.set(null);
        this.pendingBrowserUrl.set(null); this.pendingMcpbLink.set(null);
      });
    });
    effect(() => {
      const card = this.card();
      this.selectionChange.emit(card ? { client: card.client, name: card.name, ready: this.ready() } : null);
    });
    effect(() => this.busyChange.emit(this.connecting()));
    effect(() => {
      if (this.connected()) { this.pending.set(null); this.reviewing.set(false); }
    });
  }

  status(card: ClientCard): string {
    const links = card.links.filter(l => l.endpoint === this.endpoint()?.slug);
    if (links.some(l => l.status === 'connected')) return 'Connected';
    if (links.some(l => l.status === 'pending')) return 'Awaiting connection';
    const restart = links.find(l => l.status === 'needs-restart');
    if (restart) {
      if (restart.method === 'mcpb') return 'Finish installation in Claude Desktop';
      return card.client === this.client() && this.acknowledgedRestart() ? 'Restart confirmed by you' : 'Restart required';
    }
    if (links.length) return 'Connection needs attention';
    return card.detected ? 'Not connected here' : 'Not detected';
  }

  hasConnectedLink(card: ClientCard): boolean {
    return card.links.some(link => link.endpoint === this.endpoint()?.slug && link.status === 'connected');
  }

  pendingSetup(card: ClientCard): boolean {
    return card.links.some(link => link.endpoint === this.endpoint()?.slug && (link.status === 'needs-restart' || link.status === 'pending'))
      || (this.pending()?.client === card.client && this.pending()?.endpoint === this.endpoint()?.slug);
  }

  canStart(card: ClientCard): boolean {
    return !!this.endpoint() && !this.startDisabledReason() && !this.disabled() && !this.connecting() && !this.checking()
      && !this.failed() && !this.reviewing() && (this.hasConnectedLink(card) || (card.client === this.client() && this.ready()));
  }

  startApp(card: ClientCard): void {
    if (!this.canStart(card)) return;
    if (card.client !== this.client()) this.select(card.client);
    if (!this.ready()) return;
    this.startImport.emit({ client: card.client, name: card.name, ready: true });
  }

  setupApp(card: ClientCard): void {
    if (this.disabled() || this.connecting() || this.checking() || this.failed()) return;
    if (card.client !== this.client()) this.select(card.client);
    const context = this.key();
    afterNextRender(() => {
      if (this.destroy.destroyed || context !== this.key()) return;
      if (this.pendingSetup(card) || !card.detected || !card.methods.length) {
        this.doc.getElementById('import-selected-app')?.focus();
      } else this.openReview();
    }, { injector: this.injector });
  }

  select(value: string): void {
    if (this.connecting() || this.disabled()) return;
    this.client.set(this.cards().find(c => c.client === value)?.client ?? null);
    this.consent.set(null);
  }

  isPending(): boolean {
    const pending = this.pending();
    return !!pending && pending.client === this.client() && pending.endpoint === this.endpoint()?.slug;
  }

  acknowledge(kind: 'manual' | 'restart', checked: boolean): void {
    if (this.connecting() || this.disabled()) return;
    this.consent.set(checked ? { kind, key: kind === 'restart' ? this.restartKey() : this.key() } : null);
  }

  openReview(): void {
    const card = this.card();
    if (!card || this.disabled() || this.connecting() || this.checking() || this.failed()) return;
    this.data.resetConnect(card.client);
    this.consent.set(null); this.manual.set(false); this.reviewing.set(true); this.error.set('');
    afterNextRender(() => this.doc.getElementById('import-app-connect-t')?.focus(), { injector: this.injector });
  }

  closeReview(): void {
    if (this.connecting()) return;
    const client = this.client();
    if (client) this.data.resetConnect(client);
    this.reviewing.set(false);
  }

  previewRequest(card: ClientCard, request: ConnectRequest): void {
    if (this.disabled() || this.connecting() || this.checking() || this.failed() || !this.reviewing() || card.client !== this.client() || request.endpoint !== this.endpoint()?.slug) return;
    void this.data.preview(card, request);
  }

  async connect(card: ClientCard, previewId: string | null, request: ConnectRequest): Promise<void> {
    if (this.disabled() || this.connecting() || this.checking() || this.failed() || !this.reviewing() || card.client !== this.client() || request.endpoint !== this.endpoint()?.slug) return;
    if (!card.methods.some(m => m.id === request.method && (this.endpoint()?.env !== 'prod' || m.prodAllowed))) return;
    const browser = card.client === 'cursor' && request.method === 'oauth';
    const preview = this.preview();
    if (!browser && (!previewId || preview?.previewId !== previewId || preview.client !== card.client || preview.method !== request.method)) return;
    const context = this.key();
    this.connecting.set(true); this.error.set(''); this.message.set('');
    try {
      if (browser) {
        const result = await this.data.browserSignIn(card.client, request.endpoint);
        if (this.destroy.destroyed || context !== this.key()) return;
        const url = browserInstallUrl(result.link);
        if (!url) throw new Error('The core returned an invalid Cursor install link.');
        this.pending.set({ client: card.client, endpoint: request.endpoint, method: 'oauth' });
        this.pendingBrowserUrl.set(url);
        this.reviewing.set(false);
        this.message.set('Continue in Cursor: add this endpoint, then finish signing in through the browser. Check the connection when you return.');
        await this.desktop.openExternal(url);
      } else {
        const done = await this.data.connect(card, previewId!);
        if (!done || this.destroy.destroyed || context !== this.key()) return;
        this.reviewing.set(false);
        if (done.result.link.method === 'mcpb' && done.result.link.status !== 'connected') {
          this.pending.set({ client: card.client, endpoint: request.endpoint, method: 'mcpb' });
          this.pendingMcpbLink.set(done.result.link.id);
          this.message.set('Finish installing the extension in Claude Desktop, enable it for this endpoint, then check the connection.');
          if (this.desktop.available) await this.desktop.openMcpb(done.result.link.id);
          else this.message.set(`Install the generated extension in Claude Desktop: ${done.result.mcpbPath ?? 'open GateRook on your desktop to continue'}. Then check the connection.`);
        } else if (done.result.link.status === 'needs-restart') {
          this.message.set(`Fully quit and restart ${card.name}, then open a new chat. Confirm below after restarting.`);
        }
      }
    } catch (error) {
      if (!this.destroy.destroyed && context === this.key()) this.error.set(desktopErrorText(error, 'App setup could not be completed.'));
    } finally { this.connecting.set(false); }
  }

  async continueSetup(): Promise<void> {
    if (this.disabled() || this.connecting() || !this.desktop.available) return;
    const url = this.isPending() ? this.pendingBrowserUrl() : null;
    const link = this.isPending() ? this.pendingMcpbLink() : this.restart()?.method === 'mcpb' ? this.restart()!.id : null;
    if (!url && !link) return;
    const context = this.key();
    this.connecting.set(true); this.error.set('');
    try {
      if (url) await this.desktop.openExternal(url);
      else await this.desktop.openMcpb(link!);
    } catch (error) {
      if (!this.destroy.destroyed && context === this.key()) this.error.set(desktopErrorText(error, 'App setup could not be reopened.'));
    } finally { this.connecting.set(false); }
  }

  async refresh(): Promise<void> {
    if (this.connecting() || this.checking()) return;
    this.error.set(''); this.checkFailed.set(false);
    try { await this.data.scan('page'); if (!this.destroy.destroyed) await this.data.refresh(); }
    catch (error) {
      if (!this.destroy.destroyed) { this.checkFailed.set(true); this.error.set(desktopErrorText(error, 'App connections could not be checked.')); }
    }
  }

  /** The import page's timer checks only setup awaiting app activity; it never scans or writes configuration. */
  async refreshPending(): Promise<void> {
    if (this.destroy.destroyed || this.disabled() || this.connecting() || this.reviewing() || this.checking() || this.failed()
      || this.connected() || (!this.restart() && !this.isPending())) return;
    if (this.pendingRefresh) return this.pendingRefresh;
    const context = this.key();
    this.pendingRefresh = this.data.reloadClients().catch(error => {
      if (!this.destroy.destroyed && context === this.key()) {
        this.checkFailed.set(true);
        this.error.set(desktopErrorText(error, 'App connections could not be checked.'));
      }
    }).finally(() => { this.pendingRefresh = null; });
    return this.pendingRefresh;
  }

  async openConfig(): Promise<void> {
    const client = this.client();
    if (!client || !this.desktop.available) return;
    try { await this.desktop.openClientConfig(client); }
    catch (error) { if (!this.destroy.destroyed) this.error.set(desktopErrorText(error, 'The app configuration could not be opened.')); }
  }
}
