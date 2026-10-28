import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, DOCUMENT, Injector, afterNextRender, computed, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { isApiError } from '../../core/api/api-error';
import { ClientCard, ClientId, ClientLink, ConnectRequest } from '../../core/api/clients.api';
import { DesktopBridge } from '../../core/desktop/desktop-bridge';
import { CoreStore } from '../../core/stores/core.store';
import { TimeService } from '../../core/time/time.service';
import { Announcer } from '../../kit/announcer';
import { GwCopy } from '../../kit/copy';
import { GwToggle } from '../../kit/disclosure';
import { ClientPreviewRequest, GwClientCard } from '../../kit/elements/client-card/client-card';
import { ConfigEntry, INSTALL_URL, viaText } from '../../kit/elements/client-card/client-view';
import { GwClientMark } from '../../kit/elements/shared/client-mark';
import { clientStatusLook } from '../../kit/elements/shared/dictionaries';
import { literalParts } from '../../kit/elements/shared/literals';
import { GwHowItWorks, GwHowItWorksStep } from '../../kit/elements/how-it-works/how-it-works';
import { GwIcon } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { GwCodeBlock } from '../../code/code-block';
import { checkPrompt } from '../overview/overview-view';
import { ClientsData } from './clients.data';
import {
  anchorBase,
  anchorClient,
  bridgeRemovedToast,
  checkSummary,
  connectToast,
  disconnectToast,
  endpointAddedToast,
  errorSentence,
  handTokens,
  headerCounts,
  noClients,
  scanSummary,
  setupLayout,
  stageDescription,
  usesTools,
} from './clients-view';
import { GwInlineConnectCard } from './inline-connect-card';
import { GwManualSetup } from './manual-setup';

/** The first render waits this long before it shows placeholders (kit: "when it takes longer than 300 ms"). */
export const SKELETON_DELAY_MS = 300;

/**
 * Clients (design/pages/clients.html; ARCHITECTURE.md §8.4 "clients"): which AI apps reach the gateway, and how.
 * Top to bottom, as the kit answers it:
 * 1. What does Connect do? — How it works (find → show the diff → back up and write → Disconnect undoes).
 * 2. Which apps are connected? — compact app rows with live status, in a stable order. Opening a row reveals the
 *    existing connection details and actions, or the Connect review for a detected app. Folding preserves choices.
 * 3. "No AI apps found" when the scan finds none.
 * 4. Anything else? — manual setup: the endpoint URL, the snippets, where tokens come from.
 *
 * Nothing is written blind: Connect shows the exact change first, Disconnect its reverse and backup. Live: the
 * cards follow `clients.drift` and the calls (`audit.call`). Placeholders only when the
 * first answer is slow; a failed reload with data on screen says the page may be out of date.
 *
 * Outside "in use" (the page's state is Overview's, from the core's facts; kit `?state=setup | first-run`): no How it
 * works; the apps are quiet rows. Setup — a connection, no AI app yet — opens the app Overview's Connect names
 * (`#cl-<app>-t`) as one Connect review on top, its heading focused: what it can use (the connection, its tools, the
 * endpoint), what will change, "Connect <app>"; once connected here, "Check that it works" with the prompt to copy.
 * Manual setup is collapsed. First run — nothing set up — keeps the rows with
 * Connect unavailable and why, the header's Add connection as the page's only primary, and no manual setup.
 */
@Component({
  selector: 'gw-clients-page',
  imports: [
    NgTemplateOutlet,
    RouterLink,
    GwIcon,
    GwToggle,
    GwCopy,
    GwClientMark,
    GwCodeBlock,
    GwHowItWorks,
    GwHowItWorksStep,
    GwClientCard,
    GwInlineConnectCard,
    GwManualSetup,
  ],
  providers: [ClientsData],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page' },
  templateUrl: './clients.page.html',
})
export class ClientsPage {
  protected readonly data = inject(ClientsData);
  protected readonly core = inject(CoreStore);
  protected readonly time = inject(TimeService);
  private readonly desktop = inject(DesktopBridge);
  private readonly toasts = inject(ToastService);
  private readonly announcer = inject(Announcer);
  private readonly doc = inject(DOCUMENT);
  private readonly route = inject(ActivatedRoute);
  private readonly injector = inject(Injector);
  private readonly fragment = toSignal(this.route.fragment, { initialValue: this.route.snapshot.fragment });
  protected readonly clientAnchor = (id: ClientId) => `${anchorBase(id)}-t`;

  /** Placeholders only when the first answer is slow. */
  protected readonly slow = signal(false);
  protected readonly loading = computed(() => !this.data.loaded() || !this.stageKnown());
  /** Open config and opening the bridge extension need the desktop app. */
  protected readonly desktopApp = this.desktop.available;
  /** The client whose Connect is writing. */
  protected readonly connecting = signal<ClientId | null>(null);

  protected readonly cards = this.data.cards;
  protected readonly selectedCard = computed(() => this.cards().find((c) => this.clientAnchor(c.client) === this.fragment()) ?? null);
  protected readonly counts = computed(() => headerCounts(this.cards()));
  protected readonly expandedApps = signal<ReadonlySet<string>>(new Set());
  protected readonly none = computed(() => this.cards().length > 0 && noClients(this.cards()));
  protected readonly endpoints = this.data.endpointList;
  protected readonly hand = computed(() => handTokens(this.data.tokens(), this.cards()));
  protected readonly scannedAt = computed(() => this.data.clients().value?.scannedAt ?? null);

  // ── First run and setup ───────────────────────────────────────────────────────────────────────────────────────

  /** The page's state, as on Overview; in use until the core said otherwise. */
  protected readonly stage = computed(() => this.data.stage() ?? 'in-use');
  protected readonly stageKnown = computed(() => this.data.stage() !== null);
  /** The header's description outside "in use". */
  protected readonly description = computed(() => {
    const st = this.stage();
    return st === 'in-use' ? null : stageDescription(st, this.cards(), this.data.first());
  });
  /** Setup: the app whose Connect review is open on top (one at a time). */
  readonly review = signal<string | null>(null);
  protected readonly setup = computed(() => setupLayout(this.cards(), this.review()));
  /** Setup: apps connected here in this session: their cards say "Check that it works". */
  protected readonly fresh = signal<ReadonlySet<string>>(new Set());
  /** Setup: the first connection's endpoint. */
  protected readonly firstEndpoint = computed(() => {
    const slug = this.data.first()?.endpoint;
    return slug ? (this.endpoints().find((e) => e.slug === slug) ?? null) : null;
  });
  /** The first connection's tools on its endpoint, as the review's Tools row says them. */
  private readonly firstRows = computed(() => {
    const name = this.data.first()?.name;
    const groups = this.data.firstTools();
    return name && groups ? groups.filter((g) => g.connection === name).flatMap((g) => g.tools) : null;
  });
  protected readonly usesLine = computed(() => {
    const rows = this.firstRows();
    return rows ? usesTools(rows) : null;
  });
  /** "sql_execute" in "if it tries sql_execute, the request waits for you in Approvals". */
  protected readonly approvalTool = computed(() => this.firstRows()?.find((r) => r.mode === 'APPROVAL')?.name ?? null);
  /** The prompt "Check that it works" offers ("Show me the tables in dev-db"). */
  protected readonly prompt = computed(() => {
    const f = this.data.first();
    return checkPrompt(f?.type ?? null, f?.name ?? null);
  });
  /** Manual setup stays behind "Show manual setup". */
  protected readonly manualOpen = signal(false);
  protected readonly anchorBase = anchorBase;
  protected readonly literalParts = literalParts;
  protected readonly statusLook = clientStatusLook;
  protected readonly viaOf = viaText;
  protected readonly installUrl = (c: ClientCard) => INSTALL_URL[c.client];

  constructor() {
    void this.data.start();
    const timer = setTimeout(() => this.slow.set(true), SKELETON_DELAY_MS);
    effect((onCleanup) => {
      if (!this.loading()) clearTimeout(timer);
      onCleanup(() => clearTimeout(timer));
    });
    // The core came back (it was away, or another instance answers): everything may be stale.
    // Arriving with an app's anchor (Overview's tiles and guide: `#cl-cu-t`): its review, row or card, once loaded.
    effect(() => {
      const f = this.fragment();
      if (!f || !this.data.loaded() || !this.stageKnown()) return;
      untracked(() => this.arrive(f));
    });
    let wasRunning = this.core.running();
    let instance = this.core.instanceChanges();
    effect(() => {
      const running = this.core.running();
      const changes = this.core.instanceChanges();
      untracked(() => {
        if (running && (!wasRunning || changes !== instance)) void this.data.refresh();
        wasRunning = running;
        instance = changes;
      });
    });
  }

  /** Codex's entry as it is in its file, when the page read it. */
  protected entryOf(card: ClientCard): ConfigEntry | null {
    const link = card.links.find((l) => l.method === 'helper');
    return link ? (this.data.entries().get(link.id) ?? null) : null;
  }

  /**
   * An in-page link ("Manual setup" in the empty state): the router doesn't scroll to anchors in this app (the
   * window never scrolls as a whole), so the view and the keyboard move here, as a native anchor would move them.
   */
  protected jump(e: MouseEvent, id: string): void {
    if (e.button !== 0 || e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) return;
    if (id === 'cl-manual-t') this.manualOpen.set(true);
    queueMicrotask(() => {
      const target = this.doc.getElementById(id);
      if (!target) return;
      if (!target.hasAttribute('tabindex')) target.setAttribute('tabindex', '-1');
      target.scrollIntoView?.({ block: 'start' });
      target.focus({ preventScroll: true });
    });
  }

  /**
   * Where an app's anchor leads (kit page script): in setup a detected app's Connect review opens on top and its
   * heading takes the focus, never the primary; an app without entries outside "in use" is its row; else its card.
   */
  private arrive(fragment: string): void {
    const client = anchorClient(fragment);
    if (!client) return;
    if (client === 'claude-ai') return;
    const card = this.cards().find((c) => c.client === client);
    if (!card) return;
    const base = anchorBase(client);
    const st = this.stage();
    this.expandApp(client, true);
    if (this.none()) {
      // With no detected apps, a selected missing app keeps its installation card above the empty state.
      this.focus(null, client);
    } else if (st === 'setup' && !card.links.length && card.status !== 'not-installed') {
      this.review.set(client);
      this.focus(`${base}-s-t`);
    } else if (st !== 'in-use' && !card.links.length) {
      this.focus(`${base}-row-t`);
    } else {
      this.focus(null, client);
    }
  }

  /** Keep disclosure state independent of connection updates and card replacements. */
  protected expandApp(client: string, expanded: boolean): void {
    this.expandedApps.update((current) => {
      const next = new Set(current);
      if (expanded) next.add(client);
      else next.delete(client);
      return next;
    });
  }

  /** A row's Connect (setup): the same anchor again doesn't navigate, so the review opens here too. */
  protected openReview(card: ClientCard, e: MouseEvent): void {
    if (e.button !== 0 || e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) return;
    this.review.set(card.client);
    this.focus(`${anchorBase(card.client)}-s-t`);
  }

  /** Moves the view and the keyboard to a heading (by id, or a client card's title) once it is rendered. */
  private focus(id: string | null, client?: string): void {
    afterNextRender(
      () => {
        const target = id ? this.doc.getElementById(id) : this.doc.querySelector<HTMLElement>(`[data-client="${client}"] .gw-card__title`);
        if (!target) return;
        if (!target.hasAttribute('tabindex')) target.setAttribute('tabindex', '-1');
        (target.closest('article, li') ?? target).scrollIntoView?.({ block: 'nearest' });
        target.focus({ preventScroll: true });
      },
      { injector: this.injector },
    );
  }

  protected retry(): void {
    void this.data.refresh();
  }

  protected errorText(e: unknown): string {
    return isApiError(e) ? e.detail : "The core didn't answer. Try again.";
  }

  protected errorDetails(e: unknown): string {
    if (isApiError(e)) return `GET /api/clients → ${e.status || 'no answer'}\n${e.title}: ${e.detail}`;
    return e instanceof Error ? `${e.name}: ${e.message}` : String(e);
  }

  // ── Scan ──────────────────────────────────────────────────────────────────────────────────────────────────────

  /** Scan again (header, empty state) or Check again (a card): busy on the pressed button, the result announced. */
  protected async scan(origin: 'page' | ClientCard): Promise<void> {
    if (this.data.scanning()) return;
    const before = this.cards();
    this.announcer.announce(origin === 'page' ? 'Scanning this computer for AI apps' : `Looking for ${origin.name}`);
    try {
      const view = await this.data.scan(origin === 'page' ? 'page' : origin.client);
      this.announcer.announce(origin === 'page' ? scanSummary(before, view.clients) : checkSummary(view.clients, origin.client, origin.name));
    } catch (e) {
      this.toasts.show({ tone: 'danger', title: "The scan didn't finish", body: errorSentence(e) });
    }
  }

  // ── Connect ───────────────────────────────────────────────────────────────────────────────────────────────────

  protected onPreview(card: ClientCard, request: ConnectRequest): void {
    void this.data.preview(card, request);
  }

  protected onPreviewRequest(r: ClientPreviewRequest): void {
    const card = this.cards().find((c) => c.client === r.client);
    if (card) void this.data.preview(card, r.request);
  }

  protected async onConnect(card: ClientCard, previewId: string, request: ConnectRequest | null): Promise<void> {
    this.connecting.set(card.client);
    try {
      const done = await this.data.connect(card, previewId);
      if (!done) return; // the card says what failed
      if (this.stage() === 'setup') {
        // The review gives way to the app's card, which stays on top with "Check that it works" (kit).
        this.fresh.update((s) => new Set(s).add(card.client));
      }
      this.focus(null, card.client);
      const opened = done.result.link.method === 'mcpb' && this.desktopApp;
      if (opened) await this.desktop.openMcpb(done.result.link.id);
      this.toasts.show({ tone: 'success', ...connectToast(card, request, done.preview, done.result, opened) });
    } finally {
      this.connecting.set(null);
    }
  }

  protected async onAddEndpoint(card: ClientCard, endpoint: string): Promise<void> {
    try {
      const result = await this.data.addEndpoint(card, endpoint);
      const opened = this.desktopApp;
      if (opened) await this.desktop.openMcpb(result.link.id);
      this.toasts.show({ tone: 'success', ...endpointAddedToast(card, endpoint, result, opened) });
    } catch (e) {
      this.toasts.show({ tone: 'danger', title: `${endpoint} wasn't added to ${card.name}`, body: errorSentence(e) });
    }
  }

  // ── Disconnect ────────────────────────────────────────────────────────────────────────────────────────────────

  protected onDisconnectStart(link: ClientLink): void {
    this.data.prepareDisconnect(link).catch((e) => {
      this.toasts.show({ tone: 'danger', title: `Can't disconnect from ${link.endpoint} now`, body: errorSentence(e) });
    });
  }

  protected async onDisconnect(card: ClientCard, link: ClientLink, previewId: string): Promise<void> {
    try {
      const result = await this.data.disconnect(link, previewId);
      this.toasts.show({ tone: 'success', ...disconnectToast(card, link, result) });
    } catch (e) {
      this.toasts.show({ tone: 'danger', title: `${card.name} wasn't disconnected`, body: errorSentence(e) });
    }
  }

  protected async onRemoveBridge(card: ClientCard): Promise<void> {
    try {
      const result = await this.data.removeBridge();
      this.toasts.show({ tone: 'success', ...bridgeRemovedToast(card, result) });
    } catch (e) {
      this.toasts.show({ tone: 'danger', title: `The bridge wasn't removed from ${card.name}`, body: errorSentence(e) });
    }
  }

  protected async onOpenConfig(client: ClientId): Promise<void> {
    if (!this.desktopApp) return;
    await this.desktop.openClientConfig(client);
  }

}
