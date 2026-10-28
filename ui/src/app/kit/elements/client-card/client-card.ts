import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  booleanAttribute,
  computed,
  inject,
  input,
  model,
  numberAttribute,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { ClientCard, ClientId, ClientLink, ConnectPreview, ConnectRequest } from '../../../core/api/clients.api';
import { DEFAULT_TOKEN_TTL_DAYS, tokenTtlDays } from '../../../core/api/token-lifetime';
import { TimeService } from '../../../core/time/time.service';
import { GwCodeBlock } from '../../../code/code-block';
import { GwDialog, GwDialogClose } from '../../dialog';
import { GwToggle } from '../../disclosure';
import { GwIcon } from '../../icons';
import { GwClientMark } from '../shared/client-mark';
import { clientStatusLook } from '../shared/dictionaries';
import { GwClientConnectFlow } from './client-connect-flow';
import { GwClientDisclosure } from './client-disclosure';
import {
  ClientEndpoint,
  ClientFailure,
  ConfigEntry,
  INSTALL_URL,
  activityQuery,
  andList,
  approvalMode,
  authParts,
  bridgeFiles,
  disconnectFacts,
  disconnectWords,
  endpointOption,
  fileName,
  langLabel,
  keeps,
  lastCallAt,
  notInstalledText,
  observedSignIn,
  restartAlert,
  tableLayout,
  toolCount,
  toolsCell,
  usesBridgeExtension,
  viaText,
} from './client-view';

let nextId = 0;

/** What a card asks the page to do; the page calls `ClientsApi` and passes the answer back. */
export interface ClientPreviewRequest {
  readonly client: ClientId;
  readonly request: ConnectRequest;
}

/**
 * `<article gwClientCard [card]="codex" [endpoints]="endpoints" …>` (elements/client-card.html): one MCP client of
 * this computer: installed or not, connected to which endpoints, through which file or channel, how it
 * authenticates, and the next step. States follow the clients module (`connected`, `detected`, `not-installed`,
 * `needs-restart`, `error`); a failed Connect (`failure`) shows what failed, the fix, and Try again. Claude Desktop
 * through its bridge, and any client on several endpoints, lists one row per endpoint.
 *
 * Connect never writes blind: it opens the connect dialog (`gw-dialog--lg`, static) with the exact change from the
 * core's preview; Confirm closes it and emits `connect` with the preview's id. Disconnect asks first
 * (`gw-dialog--sm --danger`, Cancel focused) and needs the disconnect preview the page fetched on `disconnectStart`.
 * The card calls nothing itself.
 */
@Component({
  selector: 'article[gwClientCard], li[gwClientCard]',
  imports: [NgTemplateOutlet, RouterLink, GwIcon, GwClientMark, GwCodeBlock, GwDialog, GwDialogClose, GwToggle, GwClientConnectFlow, GwClientDisclosure],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'gw-card gw-client-card',
    '[class.gw-client-card--compact]': 'collapsible() || variant() === "compact"',
    '[attr.aria-labelledby]': 'variant() === "compact" ? null : uid + "-t"',
  },
  templateUrl: './client-card.html',
})
export class GwClientCard {
  protected readonly time = inject(TimeService);

  readonly card = input.required<ClientCard>();
  readonly defaultTtlDays = input(DEFAULT_TOKEN_TTL_DAYS, { transform: tokenTtlDays });
  /** AI apps starts with the name and status; details stay mounted while folded. */
  readonly collapsible = input(false, { transform: booleanAttribute });
  readonly expanded = model(false);
  /** The endpoints of this computer (tools per endpoint, the Connect choices). */
  readonly endpoints = input<readonly ClientEndpoint[]>([]);
  /** The client's name heading level (3 on Clients; sections inside are one below). */
  readonly level = input(3, { transform: numberAttribute });
  /**
   * `alone`: the only thing to do (onboarding, the one detected client): Connect is the primary button. `compact`: a
   * row of an endpoint's Clients tab (`<li gwClientCard variant="compact" endpoint="dev">`), about that endpoint only.
   */
  readonly variant = input<'grid' | 'alone' | 'compact'>('grid');
  /** The endpoint a compact row is about; Connect preselects it. */
  readonly endpoint = input<string | null>(null);
  /** The last Connect of this client failed: what failed and how to fix it. */
  readonly failure = input<ClientFailure | null>(null);
  /** The newest backup of the client's config (`GET /api/clients/{client}/backups`), shown after a write. */
  readonly backup = input<string | null>(null);
  /** Open config needs the desktop app (it opens the file in the default editor). */
  readonly canOpenConfig = input(true, { transform: booleanAttribute });
  /**
   * The card on the Clients screen (pages/clients.html): the Auth row and a missing client's note in that page's
   * words, which the element's demo says longer.
   */
  readonly page = input(false, { transform: booleanAttribute });
  /** The client's entry in its config file (Codex: "Entry in config.toml" and its Approvals row), when the page read it. */
  readonly entry = input<ConfigEntry | null>(null);
  /** Check again is scanning this computer (busy button). */
  readonly scanning = input(false, { transform: booleanAttribute });

  /** The connect dialog's preview, its state, and why it was refused. */
  readonly connectPreview = input<ConnectPreview | null>(null);
  readonly connectLoading = input(false, { transform: booleanAttribute });
  readonly connectFailure = input<ClientFailure | null>(null);
  /** The preview of the Disconnect being confirmed. */
  readonly disconnectPreview = input<ConnectPreview | null>(null);

  /** Connect opened: the page clears an old preview. */
  readonly connectStart = output<ClientId>();
  /** The connect selection changed: preview it (`connectPreview`). */
  readonly previewRequest = output<ClientPreviewRequest>();
  readonly connect = output<{ readonly client: ClientId; readonly previewId: string | null; readonly request: ConnectRequest | null }>();
  /** Claude Desktop: add an endpoint to the bridge (the page previews and connects in one go). */
  readonly addEndpoint = output<{ readonly client: ClientId; readonly endpoint: string }>();
  /** Disconnect pressed: fetch its preview (`disconnectPreview`). */
  readonly disconnectStart = output<ClientLink>();
  readonly disconnect = output<{ readonly link: ClientLink; readonly previewId: string }>();
  readonly removeBridge = output<ClientId>();
  readonly openConfig = output<ClientId>();
  /** Check again: scan this computer. */
  readonly scan = output<void>();

  protected readonly uid = `cc-${++nextId}`;
  private readonly connectDialog = viewChild<GwDialog>('connectDialog');
  private readonly addDialog = viewChild<GwDialog>('addDialog');
  private readonly disconnectDialog = viewChild<GwDialog>('disconnectDialog');
  private readonly bridgeDialog = viewChild<GwDialog>('bridgeDialog');
  private readonly flow = viewChild<GwClientConnectFlow>('flow');

  protected readonly c = computed(() => this.card());
  protected readonly state = computed(() => (this.failure() ? 'error' : this.card().status));
  protected readonly look = computed(() => clientStatusLook(this.state()));
  protected readonly via = computed(() => viaText(this.card()));
  protected readonly bridge = computed(() => usesBridgeExtension(this.card()));
  protected readonly table = computed(() => tableLayout(this.card()));
  protected readonly link = computed(() => this.card().links[0] ?? null);
  /** The compact row's entry: the client's link to its endpoint. */
  protected readonly scoped = computed(() => this.card().links.find((l) => l.endpoint === this.endpoint()) ?? null);
  /** The compact row's other endpoints of the same client ("also on incident-prod"). */
  protected readonly compactOthers = computed(() => {
    const l = this.scoped();
    return l ? [...new Set(keeps(this.card(), l).filter((endpoint) => endpoint !== l.endpoint))] : [];
  });
  protected readonly compactLook = computed(() => {
    const l = this.scoped();
    if (l) return clientStatusLook(l.status === 'connected' || l.status === 'needs-restart' ? l.status : 'error');
    return this.card().status === 'not-installed' ? clientStatusLook('not-installed') : { ...clientStatusLook('detected'), text: 'Detected' };
  });
  protected readonly lastCall = computed(() => lastCallAt(this.card().links));
  protected readonly auth = computed(() => authParts(this.card(), this.link(), this.state() === 'needs-restart', this.page()));
  protected readonly missing = computed(() => notInstalledText(this.card(), this.page()));
  /** Codex's approval mode, as its entry says it. */
  protected readonly approvals = computed(() => approvalMode(this.entry()));
  /** "config.toml": the entry's file by name, for its disclosure. */
  protected readonly entryFile = computed(() => fileName(this.entry()?.file ?? null));
  protected readonly restart = computed(() => restartAlert(this.card()));
  protected readonly installUrl = computed(() => INSTALL_URL[this.card().client]);
  protected readonly alone = computed(() => this.variant() === 'alone');
  protected readonly observedSignIn = observedSignIn;
  protected readonly observedOnly = computed(() => this.card().links.length > 0 && this.card().links.every(observedSignIn));
  /** Clients set up through a file have Open config; the bridge extension has no file. */
  protected readonly hasConfig = computed(() => !this.observedOnly() && !this.bridge() && !!this.card().configPath);

  protected readonly rows = computed(() =>
    this.card().links.map((l) => {
      const e = this.endpoints().find((x) => x.slug === l.endpoint) ?? null;
      return { link: l, tools: e ? toolsCell(e) : null };
    }),
  );
  protected readonly endpointInfo = computed(() => {
    const l = this.link();
    return l ? (this.endpoints().find((e) => e.slug === l.endpoint) ?? null) : null;
  });
  /** Endpoints the client doesn't reach yet (Add endpoint, Connect). */
  protected readonly free = computed(() => {
    const have = new Set(this.card().links.map((l) => l.endpoint));
    return this.endpoints().filter((e) => !have.has(e.slug));
  });
  protected readonly connectEndpoints = computed(() => (this.free().length ? this.free() : this.endpoints()));

  /** The link whose Disconnect is being confirmed. */
  protected readonly leaving = signal<ClientLink | null>(null);
  protected readonly leavingFacts = computed(() => {
    const l = this.leaving();
    return l ? disconnectFacts(this.card(), l, this.matchingPreview()) : null;
  });
  protected readonly leavingWords = computed(() => {
    const l = this.leaving();
    return l ? disconnectWords(this.card(), l, this.table()) : null;
  });
  /**
   * The disconnect preview's notes, but its last one: the core ends with what is revoked, which the Revokes row
   * already says (ClientService.previewDisconnect).
   */
  protected readonly leavingNotes = computed(() => {
    const notes = this.matchingPreview()?.notes ?? [];
    return this.leavingFacts()?.revokes ? notes.slice(0, -1) : notes;
  });
  /** Remove bridge: the extensions it takes away. */
  protected readonly bridgeFiles = computed(() => bridgeFiles(this.card()));
  /** The disconnect preview, if it is the one of the link being confirmed. */
  protected readonly matchingPreview = computed(() => {
    const p = this.disconnectPreview();
    const l = this.leaving();
    return p && l && p.endpoint === l.endpoint && p.client === l.client ? p : null;
  });

  protected readonly addChoice = signal<string | null>(null);
  protected readonly addSlug = computed(() => this.addChoice() ?? this.free()[0]?.slug ?? null);

  protected readonly endpointOption = endpointOption;
  protected readonly langLabel = langLabel;
  protected readonly toolCount = toolCount;
  protected readonly andList = andList;
  protected readonly activity = activityQuery;

  /** Opens Connect (also Try again after a failure). */
  openConnect(opener?: HTMLElement | null): void {
    this.flow()?.changesExpanded.set(false);
    this.connectStart.emit(this.card().client);
    this.connectDialog()?.open(opener ?? undefined);
    queueMicrotask(() => this.flow()?.emit());
  }

  protected onPreviewRequest(request: ConnectRequest | null): void {
    if (request) this.previewRequest.emit({ client: this.card().client, request });
  }

  /** `<form method="dialog">`: Enter or Confirm; the dialog closes only when the preview can be carried out. */
  protected onConnectSubmit(e: Event): void {
    e.preventDefault();
    this.confirmConnect();
  }

  protected confirmConnect(): void {
    const p = this.connectPreview();
    if (this.connectBlocked()) return;
    this.connect.emit({ client: this.card().client, previewId: this.flow()?.browserOnly() ? null : p!.previewId, request: this.flow()?.request() ?? null });
    this.connectDialog()?.close('connect');
  }

  protected connectBlocked(): boolean {
    if (this.flow()?.browserOnly()) return !this.flow()?.request();
    return !this.connectPreview() || this.connectLoading() || !!this.connectFailure() || !this.flow()?.request();
  }

  protected openAdd(opener: HTMLElement): void {
    this.addChoice.set(null);
    this.addDialog()?.openAfterRender(opener);
  }

  protected onAddChoice(e: Event): void {
    this.addChoice.set((e.target as HTMLSelectElement).value);
  }

  protected confirmAdd(): void {
    const slug = this.addSlug();
    if (!slug) return;
    this.addEndpoint.emit({ client: this.card().client, endpoint: slug });
    this.addDialog()?.close('add');
  }

  protected askDisconnect(link: ClientLink, opener: HTMLElement): void {
    if (observedSignIn(link)) return;
    this.leaving.set(link);
    this.disconnectStart.emit(link);
    this.disconnectDialog()?.openAfterRender(opener);
  }

  protected confirmDisconnect(): void {
    const l = this.leaving();
    const p = this.matchingPreview();
    if (!l || !p || observedSignIn(l)) return;
    this.disconnect.emit({ link: l, previewId: p.previewId });
    this.disconnectDialog()?.close('disconnect');
  }

  protected askRemoveBridge(opener: HTMLElement): void {
    this.bridgeDialog()?.open(opener);
  }

  protected confirmRemoveBridge(): void {
    this.removeBridge.emit(this.card().client);
    this.bridgeDialog()?.close('remove');
  }

  protected onOpenConfig(): void {
    if (this.hasConfig() && this.canOpenConfig()) this.openConfig.emit(this.card().client);
  }
}
