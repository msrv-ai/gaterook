import { DestroyRef, Injectable, WritableSignal, computed, inject, signal } from '@angular/core';
import { isNotAvailable } from '../../core/api/api-error';
import {
  BridgeRemoved,
  ClientCard,
  ClientId,
  ClientLink,
  ClientsApi,
  ClientsView,
  ConnectPreview,
  ConnectRequest,
  ConnectResult,
  DisconnectResult,
  ManualSetup,
} from '../../core/api/clients.api';
import { EndpointSummary, EndpointsApi, TokenRow, TokensApi, ToolGroup } from '../../core/api/endpoints.api';
import { Overview, OverviewApi } from '../../core/api/overview.api';
import { OAuthApi, OAuthGrant, PublishPreview, TunnelApi, TunnelState } from '../../core/api/tunnel.api';
import { EventStream } from '../../core/events/event-stream';
import { TunnelStore } from '../../core/stores/tunnel.store';
import { TimeService } from '../../core/time/time.service';
import { ClientFailure, ConfigEntry } from '../../kit/elements/client-card/client-view';
import { OverviewState, overviewState } from '../overview/overview-view';
import { FirstConnection, connectFailure, entryFromPreview, firstConnection, manualEndpoint, publishedEndpoint } from './clients-view';

/**
 * One part of the page as loaded: its value (null until the first answer), why its first load failed, and whether
 * this core has the route at all (`unavailable`: the backend step of the area isn't merged — the page says so and
 * never shows made-up data).
 */
export interface Part<T> {
  readonly value: T | null;
  readonly error: unknown;
  readonly unavailable: boolean;
}

const EMPTY: Part<never> = { value: null, error: null, unavailable: false };

/** Connect of one client: the exact change the core prepared, whether it is on its way, and why it was refused. */
export interface ConnectState {
  readonly preview: ConnectPreview | null;
  readonly loading: boolean;
  readonly failure: ClientFailure | null;
  /** The failure is the write's (after Confirm), not the preview's: a card shows it, not its connect dialog. */
  readonly written: boolean;
}

const IDLE: ConnectState = { preview: null, loading: false, failure: null, written: false };

/** `audit.call` is frequent: the cards' "Last call" and a restart proven by a call are read again at most this often. */
export const CALLS_THROTTLE_MS = 5_000;

/**
 * The Clients page's data (ARCHITECTURE.md §6 clients, tunnel / OAuth; §8.4 "clients"):
 * - the cards of this computer (`/api/clients`; `clients.drift` and, throttled, `audit.call` read them again — a
 *   call proves a restarted client and moves "Last call"), the endpoints they can connect to (`/api/endpoints`) and
 *   Codex's entry as it is in its file (the disconnect preview's lines: the part of the file that is ours);
 * - Connect: the exact change per client (`connect/preview`, newest selection wins), then the write;
 *   Disconnect with its preview, Add endpoint and Remove bridge of Claude Desktop, Scan;
 * - claude.ai: the tunnel (`TunnelStore`, the sidebar's and banner's source too), its sign-ins (`/api/oauth/grants`)
 *   and what Publish would make public (`/api/tunnel/publish/preview`) — before step K3 these routes don't exist and
 *   the card says so;
 * - manual setup: the URL and snippets of one endpoint (`/api/clients/manual`) and its tokens set up by hand;
 * - the page's state, as on Overview (`/api/overview`): first run, setup, in use; in setup the first connection and
 *   its tools on its endpoint (`/api/endpoints/{slug}/tools`), which the Connect review names.
 *
 * Each part loads on its own: one that fails or doesn't exist leaves the others working. Reloads are coalesced: one
 * in flight and one more after it.
 */
@Injectable()
export class ClientsData {
  private readonly clientsApi = inject(ClientsApi);
  private readonly endpointsApi = inject(EndpointsApi);
  private readonly tokensApi = inject(TokensApi);
  private readonly tunnelApi = inject(TunnelApi);
  private readonly oauthApi = inject(OAuthApi);
  private readonly overviewApi = inject(OverviewApi);
  private readonly tunnelStore = inject(TunnelStore);
  private readonly events = inject(EventStream);
  private readonly time = inject(TimeService);

  private readonly clientsSignal = signal<Part<ClientsView>>(EMPTY);
  private readonly endpointsSignal = signal<Part<EndpointSummary[]>>(EMPTY);
  private readonly grantsSignal = signal<Part<OAuthGrant[]>>(EMPTY);
  private readonly manualSignal = signal<Part<ManualSetup>>(EMPTY);
  private readonly tokensSignal = signal<TokenRow[]>([]);
  private readonly publishSignal = signal<PublishPreview | null>(null);
  private readonly entriesSignal = signal<ReadonlyMap<string, ConfigEntry>>(new Map());
  private readonly connectSignal = signal<ReadonlyMap<ClientId, ConnectState>>(new Map());
  private readonly disconnectSignal = signal<ReadonlyMap<ClientId, ConnectPreview>>(new Map());
  private readonly scanningSignal = signal<'page' | ClientId | null>(null);
  private readonly staleSignal = signal<unknown>(null);
  private readonly refreshingSignal = signal(false);
  /** `GET /api/overview`; null before its answer, undefined once it failed or isn't there (the page is then in use). */
  private readonly overviewSignal = signal<Overview | null | undefined>(null);
  private readonly firstToolsSignal = signal<ToolGroup[] | null>(null);

  readonly clients = this.clientsSignal.asReadonly();
  readonly endpoints = this.endpointsSignal.asReadonly();
  readonly grants = this.grantsSignal.asReadonly();
  readonly manual = this.manualSignal.asReadonly();
  /** The manual setup endpoint's tokens (empty when they didn't load: the footer line just goes). */
  readonly tokens = this.tokensSignal.asReadonly();
  readonly publishPreview = this.publishSignal.asReadonly();
  /** Each helper link's entry by link id. */
  readonly entries = this.entriesSignal.asReadonly();
  /** What is scanning: the whole page (Scan again) or one card's Check again. */
  readonly scanning = this.scanningSignal.asReadonly();
  /** Why the last reload failed while older data is on screen. */
  readonly stale = this.staleSignal.asReadonly();
  readonly refreshing = this.refreshingSignal.asReadonly();

  /**
   * The page's state from the core's own facts, as on Overview (`overviewState`): `first-run` (no connection yet),
   * `setup` (a connection, no AI app yet), `in-use`. Null until the overview answered; a core without it, or one that
   * failed to answer, leaves the page in use. Read with the page, not after each write: a Connect made here keeps the
   * setup layout (the kit: "a card connected in this session stays").
   */
  readonly stage = computed<OverviewState | null>(() => {
    const o = this.overviewSignal();
    if (o === null) return null;
    return o ? overviewState(o) : 'in-use';
  });
  /** Setup: the first connection and its endpoint, as Overview's guide names them. */
  readonly first = computed<FirstConnection | null>(() => {
    const o = this.overviewSignal();
    return o ? firstConnection(o) : null;
  });
  /** Setup: the endpoint's tools (the first connection's group is what the Connect review says it can use). */
  readonly firstTools = this.firstToolsSignal.asReadonly();

  /** The tunnel (null before step K3: `/api/tunnel` doesn't exist). */
  readonly tunnel = computed<TunnelState | null>(() => (this.tunnelStore.available() ? this.tunnelStore.state() : null));
  readonly cards = computed<ClientCard[]>(() => this.clientsSignal().value?.clients ?? []);
  readonly endpointList = computed<EndpointSummary[]>(() => this.endpointsSignal().value ?? []);
  /** The endpoint the claude.ai card is about; in setup, the first endpoint (kit: "Publish dev", never published). */
  readonly published = computed(
    () => publishedEndpoint(this.tunnel(), this.endpointList()) ?? (this.stage() === 'setup' ? (this.first()?.endpoint ?? null) : null),
  );
  /** The endpoint the manual setup is about. */
  readonly manualSlug = computed(() => manualEndpoint(this.endpointList()));

  /** Whether the cards have answered (the page shows placeholders until then). */
  readonly loaded = computed(() => this.isSettled(this.clientsSignal()));

  private inFlight: Promise<void> | null = null;
  private again = false;
  private stopped = false;
  private readonly unsubscribe: (() => void)[] = [];
  private readonly seq = new Map<ClientId, number>();
  private callsTimer: ReturnType<typeof setTimeout> | null = null;
  private lastCallsReload = 0;
  private publishFor: string | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stop());
  }

  start(): Promise<void> {
    this.unsubscribe.push(
      // An entry removed or repaired outside the app, a port that moved: the cards say it at once.
      this.events.on('clients.drift', () => void this.reloadClients(true)),
      this.events.on('audit.call', () => this.onCall()),
    );
    return this.refresh();
  }

  stop(): void {
    this.stopped = true;
    if (this.callsTimer) clearTimeout(this.callsTimer);
    this.unsubscribe.splice(0).forEach((u) => u());
  }

  /** Reloads every part now; while one runs, one more follows it (a change that came meanwhile is in neither). */
  refresh(): Promise<void> {
    if (this.inFlight) {
      this.again = true;
      return this.inFlight;
    }
    this.refreshingSignal.set(true);
    this.inFlight = this.load().finally(() => {
      this.inFlight = null;
      this.refreshingSignal.set(false);
      if (this.again && !this.stopped) {
        this.again = false;
        void this.refresh();
      }
    });
    return this.inFlight;
  }

  private async load(): Promise<void> {
    const failures: unknown[] = [];
    await Promise.all([
      this.part(this.clientsSignal, () => this.clientsApi.list(), failures).then(() => this.loadEntries(false)),
      this.part(this.endpointsSignal, () => this.endpointsApi.list(), failures).then(() => this.loadManual()),
      this.loadOverview(),
    ]);
    if (!this.stopped) this.staleSignal.set(failures[0] ?? null);
  }

  /** The page's state, and in setup the tools the Connect review names; a failure leaves the page in use. */
  private async loadOverview(): Promise<void> {
    try {
      const o = await this.overviewApi.get();
      if (this.stopped) return;
      this.overviewSignal.set(o);
    } catch {
      if (!this.stopped && this.overviewSignal() === null) this.overviewSignal.set(undefined);
      return;
    }
    const slug = this.stage() === 'setup' ? this.first()?.endpoint : null;
    const tools = slug ? await this.endpointsApi.tools(slug).catch(() => null) : null;
    if (!this.stopped) this.firstToolsSignal.set(tools);
  }

  private async part<T>(target: WritableSignal<Part<T>>, call: () => Promise<T>, failures: unknown[]): Promise<void> {
    try {
      const value = await call();
      if (!this.stopped) target.set({ value, error: null, unavailable: false });
    } catch (error) {
      if (this.stopped) return;
      if (isNotAvailable(error)) target.set({ value: null, error: null, unavailable: true });
      else if (target().value !== null) failures.push(error); // older data stays; the page says it may be out of date
      else target.set({ value: null, error, unavailable: false });
    }
  }

  private isSettled(p: Part<unknown>): boolean {
    return p.value !== null || p.error !== null || p.unavailable;
  }

  // ── Cards ─────────────────────────────────────────────────────────────────────────────────────────────────────

  /** The cards again (after a write, a drift event or calls); `entries` re-reads Codex's entry too. */
  async reloadClients(entries = false): Promise<void> {
    const failures: unknown[] = [];
    await this.part(this.clientsSignal, () => this.clientsApi.list(), failures);
    await this.loadEntries(entries);
  }

  private onCall(): void {
    // Throttled with a trailing reload, so the newest call is always shown.
    const wait = this.lastCallsReload + CALLS_THROTTLE_MS - Date.now();
    if (this.callsTimer) return;
    this.callsTimer = setTimeout(
      () => {
        this.callsTimer = null;
        this.lastCallsReload = Date.now();
        if (!this.stopped) void this.reloadClients(false);
      },
      Math.max(0, wait),
    );
  }

  /**
   * Codex's entry as it is in its file: the lines its disconnect preview would remove (a preview writes nothing).
   * Read once per link; `force` after a drift event, when the file may have changed.
   */
  private async loadEntries(force: boolean): Promise<void> {
    const links = this.cards().flatMap((c) => c.links.filter((l) => l.method === 'helper' && l.status === 'connected'));
    const known = this.entriesSignal();
    const wanted = links.filter((l) => force || !known.has(l.id));
    const next = new Map([...known].filter(([id]) => links.some((l) => l.id === id)));
    await Promise.all(
      wanted.map(async (l) => {
        try {
          const entry = entryFromPreview(await this.clientsApi.disconnectPreview(l.id));
          if (entry) next.set(l.id, entry);
          else next.delete(l.id);
        } catch {
          next.delete(l.id); // the disclosure just doesn't appear
        }
      }),
    );
    if (!this.stopped) this.entriesSignal.set(next);
  }

  /** Scan again (`page`) or Check again of one card: this computer is looked at again. */
  async scan(origin: 'page' | ClientId): Promise<ClientsView> {
    this.scanningSignal.set(origin);
    try {
      const view = await this.clientsApi.scan();
      if (!this.stopped) this.clientsSignal.set({ value: view, error: null, unavailable: false });
      void this.loadEntries(false);
      return view;
    } finally {
      this.scanningSignal.set(null);
    }
  }

  // ── Connect ───────────────────────────────────────────────────────────────────────────────────────────────────

  connectState(client: ClientId): ConnectState {
    return this.connectSignal().get(client) ?? IDLE;
  }

  /** Connect opened again: an old preview and failure go. */
  resetConnect(client: ClientId): void {
    this.seq.set(client, (this.seq.get(client) ?? 0) + 1);
    this.setConnect(client, IDLE);
  }

  /** The exact change for a selection; only the newest selection's answer is kept. */
  async preview(card: ClientCard, request: ConnectRequest): Promise<void> {
    const n = (this.seq.get(card.client) ?? 0) + 1;
    this.seq.set(card.client, n);
    this.setConnect(card.client, { ...this.connectState(card.client), loading: true, failure: null, written: false });
    try {
      const preview = await this.clientsApi.connectPreview(card.client, request);
      if (this.seq.get(card.client) === n && !this.stopped) this.setConnect(card.client, { preview, loading: false, failure: null, written: false });
    } catch (e) {
      if (this.seq.get(card.client) === n && !this.stopped) {
        this.setConnect(card.client, { preview: null, loading: false, failure: connectFailure(e, card, this.time.now()), written: false });
      }
    }
  }

  /**
   * Writes what the preview showed. A refusal (the file changed since, it can't be written) becomes the card's
   * "Connect failed" and resolves to null; the page then says nothing more.
   */
  async connect(card: ClientCard, previewId: string): Promise<{ readonly result: ConnectResult; readonly preview: ConnectPreview | null } | null> {
    const preview = this.connectState(card.client).preview;
    try {
      const result = await this.clientsApi.connect(card.client, previewId);
      this.resetConnect(card.client);
      await this.reloadClients(true);
      return { result, preview };
    } catch (e) {
      this.seq.set(card.client, (this.seq.get(card.client) ?? 0) + 1);
      this.setConnect(card.client, { preview: null, loading: false, failure: connectFailure(e, card, this.time.now()), written: true });
      return null;
    }
  }

  /** Claude Desktop's Add endpoint: one more extension, previewed and written in one go (no config file changes). */
  async addEndpoint(card: ClientCard, endpoint: string): Promise<ConnectResult> {
    const method = card.methods.find((m) => m.id === 'mcpb') ? 'mcpb' : (card.links[0]?.method ?? 'mcpb');
    const preview = await this.clientsApi.connectPreview(card.client, { endpoint, method, grantLabel: card.links[0]?.grantLabel ?? null });
    const result = await this.clientsApi.connect(card.client, preview.previewId);
    await this.reloadClients(false);
    return result;
  }

  private setConnect(client: ClientId, state: ConnectState): void {
    this.connectSignal.update((m) => new Map(m).set(client, state));
  }

  // ── Disconnect ────────────────────────────────────────────────────────────────────────────────────────────────

  disconnectPreviewOf(client: ClientId): ConnectPreview | null {
    return this.disconnectSignal().get(client) ?? null;
  }

  /** The reverse edit the confirm shows (its backup); Disconnect stays unavailable until it is in. */
  async prepareDisconnect(link: ClientLink): Promise<void> {
    this.disconnectSignal.update((m) => {
      const next = new Map(m);
      next.delete(link.client);
      return next;
    });
    const preview = await this.clientsApi.disconnectPreview(link.id);
    if (!this.stopped) this.disconnectSignal.update((m) => new Map(m).set(link.client, preview));
  }

  async disconnect(link: ClientLink, previewId: string): Promise<DisconnectResult> {
    const result = await this.clientsApi.disconnect(link.id, previewId);
    await this.reloadClients(false);
    return result;
  }

  async removeBridge(): Promise<BridgeRemoved> {
    const result = await this.clientsApi.removeClaudeBridge();
    await this.reloadClients(false);
    return result;
  }

  // ── claude.ai ─────────────────────────────────────────────────────────────────────────────────────────────────

  private async reloadGrants(): Promise<void> {
    await this.part(this.grantsSignal, () => this.oauthApi.grants(), []);
  }

  /**
   * What Publish makes public, for the endpoint the card is about: the Publish confirm while the tunnel is off, the
   * card's "What gets published" (its connections) in every state. The preview writes nothing.
   */
  private async reloadPublishPreview(force: boolean): Promise<void> {
    const t = this.tunnel();
    const slug = this.published();
    if (!t || !slug) {
      this.publishFor = null;
      this.publishSignal.set(null);
      return;
    }
    if (!force && this.publishFor === slug && this.publishSignal()) return;
    this.publishFor = slug;
    try {
      const p = await this.tunnelApi.publishPreview(slug);
      if (!this.stopped && this.publishFor === slug) this.publishSignal.set(p);
    } catch {
      if (!this.stopped) this.publishSignal.set(null); // Publish waits with its reason
    }
  }

  /** Publish (202): the tunnel starts; its progress arrives as `tunnel.state`. */
  async publish(slug: string): Promise<TunnelState> {
    const state = await this.tunnelApi.publish(slug);
    await this.tunnelStore.refresh();
    return state;
  }

  /** Turn off tunnel: at once, without Undo (publishing again always asks). */
  async stopTunnel(): Promise<void> {
    await this.tunnelStore.stop();
    await this.reloadPublishPreview(true);
  }

  async revoke(grant: OAuthGrant): Promise<void> {
    await this.oauthApi.revoke(grant.id);
    this.grantsSignal.update((p) => (p.value ? { ...p, value: p.value.filter((g) => g.id !== grant.id) } : p));
    void this.reloadGrants();
  }

  // ── Manual setup ──────────────────────────────────────────────────────────────────────────────────────────────

  private async loadManual(): Promise<void> {
    const slug = this.manualSlug();
    if (!slug) {
      this.manualSignal.set({ value: null, error: null, unavailable: false });
      return;
    }
    await Promise.all([
      this.part(this.manualSignal, () => this.clientsApi.manual(slug), []),
      this.tokensApi.list(slug).then(
        (rows) => {
          if (!this.stopped) this.tokensSignal.set(rows);
        },
        () => undefined, // without tokens the footer line just goes
      ),
    ]);
  }
}
