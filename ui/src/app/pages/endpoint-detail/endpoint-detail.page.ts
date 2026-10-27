import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormField, form, maxLength, validate } from '@angular/forms/signals';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { isApiError } from '../../core/api/api-error';
import { ConnectionPreview, IssuedToken, SplitPreview, TokenRow, WiredConnection } from '../../core/api/endpoints.api';
import { ToolMode } from '../../core/api/types';
import { CoreStore } from '../../core/stores/core.store';
import { EndpointsStore } from '../../core/stores/endpoints.store';
import { TimeService } from '../../core/time/time.service';
import { GwCodeBlock } from '../../code/code-block';
import { Announcer } from '../../kit/announcer';
import { GwCopy } from '../../kit/copy';
import { GwDialog, GwDialogClose } from '../../kit/dialog';
import { GwToggle } from '../../kit/disclosure';
import { GwRiskCallout } from '../../kit/elements/risk-callout/risk-callout';
import { GwSplitDialog, SplitRequest } from '../../kit/elements/risk-callout/split-dialog';
import { GwSetupWiring, SetupWiring } from '../../kit/elements/setup-map/setup-map';
import { connectorIcon } from '../../kit/elements/shared/connector-icons';
import { GwTokenIssueDialog, GwTokens } from '../../kit/elements/token-issue/token-issue';
import { GwToolPolicyList, ToolModeChange, ToolOverridesChange } from '../../kit/elements/tool-policy-list/tool-policy-list';
import { toolPolicyGroups } from '../../kit/elements/tool-policy-list/tool-policy-view';
import { GwIcon } from '../../kit/icons';
import { GwMenu, GwMenuTrigger } from '../../kit/menu';
import { GwTabs } from '../../kit/tabs';
import { ToastService } from '../../kit/toast.service';
import { GwEndpointClients } from './endpoint-clients';
import { EndpointDetailData } from './endpoint-detail.data';
import {
  AddCandidate,
  ENVS,
  TabId,
  ToastText,
  addCandidates,
  addedToast,
  addressOf,
  clientsNote,
  connectionViews,
  copySlug,
  deleteFacts,
  disableBody,
  effectiveLimits,
  endpointClients,
  envCounts,
  flowText,
  headerDescription,
  limitsLine,
  parseTab,
  plural,
  primaryEnv,
  removeText,
  revokedToast,
  servedRevisions,
  signInText,
  slugError,
  splitClients,
  splitOptions,
  splitToast,
  statusLook,
  tabCounts,
  UNREACHABLE_ALERTS_MAX,
  sortTokens,
  unreachableAlerts,
  wiringClients,
  withTargets,
} from './endpoint-detail-view';
import { GwEndpointSettings } from './endpoint-settings';

/** The first render waits this long before it shows placeholders (kit: "when it takes longer than 300 ms"). */
export const SKELETON_DELAY_MS = 300;

const NO_ANSWER = 'The core didn’t answer. Try again.';

const sameJson = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);

/** The fragment that opens Split endpoint (links from Endpoints and Overview: `/endpoints/dev#ep-dlg-split`). */
export const SPLIT_FRAGMENT = 'ep-dlg-split';

/**
 * Endpoint detail (design/pages/endpoint-detail.html; ARCHITECTURE.md §8.4 "endpoint-detail"): one endpoint, the
 * URL a client calls, and everything that decides what an agent can do through it.
 *
 * - The header: slug, what it is for, Running or Disabled, the URL to copy, the MCP revisions it answers, how
 *   clients sign in, the requests waiting for approval; View activity, Open in Playground and a menu (Duplicate,
 *   Disable with Undo, Delete).
 * - The risk callout (present / partial, limited / contained, dismissed) with Split endpoint and Dismiss.
 * - How it connects: clients → endpoint → connections (setup-map wiring), each card leading to its tab.
 * - Tabs, mirrored in `?tab=`: Tools & policies (tool-policy-list), Connections (unreachable ones first, Add and
 *   Remove), Tokens (token-issue, OAuth sign-ins), Clients (compact client cards), Settings (instructions, limits,
 *   authorization, protocol).
 *
 * Live: approvals, health, calls and client changes arrive as events (EndpointDetailData). States: nothing for 300 ms,
 * then placeholders; an error with Try again; not found; out of date; changed outside MCP Gateway (Keep as stored).
 */
@Component({
  selector: 'gw-endpoint-detail-page',
  imports: [
    RouterLink,
    FormField,
    GwIcon,
    GwMenu,
    GwMenuTrigger,
    GwDialog,
    GwDialogClose,
    GwToggle,
    GwCopy,
    GwTabs,
    GwCodeBlock,
    GwRiskCallout,
    GwSplitDialog,
    GwSetupWiring,
    GwToolPolicyList,
    GwTokens,
    GwTokenIssueDialog,
    GwEndpointSettings,
    GwEndpointClients,
  ],
  providers: [EndpointDetailData],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page' },
  templateUrl: './endpoint-detail.page.html',
})
export class EndpointDetailPage {
  protected readonly data = inject(EndpointDetailData);
  protected readonly time = inject(TimeService);
  private readonly core = inject(CoreStore);
  private readonly store = inject(EndpointsStore);
  private readonly router = inject(Router);
  private readonly toasts = inject(ToastService);
  private readonly announcer = inject(Announcer);
  private readonly injector = inject(Injector);
  private readonly host: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;
  private readonly fragment = toSignal(inject(ActivatedRoute).fragment, { initialValue: null });

  /** Route parameter `slug` (withComponentInputBinding). */
  readonly slug = input<string | null>(null);
  /** Query parameter `tab`: tools (default), connections, tokens, clients, settings. */
  readonly tab = input<string | null>(null);

  private readonly tabs = viewChild<GwTabs>('tabs');
  private readonly splitDialog = viewChild<GwSplitDialog>('split');
  private readonly duplicateDialog = viewChild<GwDialog>('duplicateDialog');
  private readonly deleteDialog = viewChild<GwDialog>('deleteDialog');
  private readonly addDialog = viewChild<GwDialog>('addDialog');
  private readonly removeDialog = viewChild<GwDialog>('removeDialog');
  private readonly keepDialog = viewChild<GwDialog>('keepDialog');
  private readonly tokenDialog = viewChild<GwTokenIssueDialog>('tokenDialog');

  protected readonly envs = ENVS;
  protected readonly connectorIcon = connectorIcon;
  protected readonly plural = plural;
  protected readonly removeText = removeText;

  protected readonly slow = signal(false);
  protected readonly ep = computed(() => this.data.slug() ?? this.slug() ?? '');
  protected readonly summary = computed(() => this.data.summary());
  protected readonly loading = computed(() => !this.data.detail() && !this.data.error() && !this.data.notFound());
  protected readonly selectedTab = computed(() => `tab-${parseTab(this.tab())}`);

  // ── Header ────────────────────────────────────────────────────────────────────────────────────────────────────

  protected readonly wired = computed<readonly WiredConnection[]>(() => this.data.detail()?.connections ?? []);
  protected readonly description = computed(() => {
    const s = this.summary();
    return s ? headerDescription(s, this.wired().length) : '';
  });
  protected readonly status = computed(() => {
    const s = this.summary();
    return s ? statusLook(s) : null;
  });
  protected readonly revisions = computed(() => servedRevisions(this.data.settings()));
  protected readonly signIn = computed(() => signInText(this.data.settings()?.oauthEnabled ?? false));
  protected readonly address = computed(() => addressOf(this.summary()?.url ?? ''));

  // ── Wiring and tabs ───────────────────────────────────────────────────────────────────────────────────────────

  protected readonly cards = computed(() => endpointClients(this.data.clients(), this.ep()));
  protected readonly views = computed(() => connectionViews(this.wired(), this.data.connections()));
  protected readonly limitsEnv = computed(() => primaryEnv(this.wired(), this.summary()?.env ?? 'dev'));
  protected readonly counts = computed(() => {
    const s = this.summary();
    return s ? tabCounts(s, this.wired().length, this.data.tokens(), this.cards(), s.slug) : null;
  });
  protected readonly wiring = computed<SetupWiring | null>(() => {
    const d = this.data.detail();
    if (!d) return null;
    const e = d.endpoint;
    const env = this.limitsEnv();
    const limits = limitsLine(effectiveLimits(this.data.policies()?.[env]?.sections, d.settings.limits));
    return {
      slug: e.slug,
      url: e.url,
      tools: e.tools,
      tokens: this.counts()?.tokens ?? e.tokens.active,
      oauth: d.settings.oauthEnabled,
      policy: `${env} rules · mutations need approval`,
      connections: this.views().map((v) => ({
        name: v.c.name,
        type: v.c.type,
        env: v.c.env,
        health: v.row?.health?.state ?? v.c.health,
        latencyMs: v.row?.health?.latencyMs ?? null,
      })),
      clients: wiringClients(this.cards(), e.slug),
      // The kit names the policy most of its tools follow ("dev rules"); Settings › Limits names the others.
      policyEnvs: [env],
      limits: limits ? { text: limits, env } : null,
      clientsNote: clientsNote(this.cards(), e.slug),
      flowText: this.flow(),
    };
  });
  /** "How dev connects", in words (the wiring's aria-describedby). */
  protected readonly flow = computed(() => {
    const s = this.summary();
    return s ? flowText(s.slug, this.cards(), this.wired()) : '';
  });

  protected readonly policyGroups = computed(() => withTargets(toolPolicyGroups(this.data.groups() ?? [], this.wired()), this.data.connections()));
  protected readonly policies = computed(() => {
    const p = this.data.policies();
    return p ? { dev: p.dev.sections, stage: p.stage.sections, prod: p.prod.sections } : {};
  });
  protected readonly envSummary = computed(() => envCounts(this.wired()));
  protected readonly unreachable = computed(() => unreachableAlerts(this.views(), this.data.groups()));
  /** Many unreachable connections at once share one alert (UNREACHABLE_ALERTS_MAX). */
  protected readonly unreachableGrouped = computed(() => this.unreachable().length > UNREACHABLE_ALERTS_MAX);
  protected readonly tokens = computed(() => {
    const t = this.data.tokens();
    return t ? sortTokens(t) : null;
  });

  // ── Risk and split ────────────────────────────────────────────────────────────────────────────────────────────

  // Compared by content: the dialog re-derives its pick from these, and live reloads must not undo the person's choice.
  protected readonly splitOptions = computed(
    () => {
      const risk = this.data.risk();
      return risk ? splitOptions(risk, this.data.groups() ?? [], this.wired(), this.ep()) : [];
    },
    { equal: sameJson },
  );
  protected readonly calledToday = signal<ReadonlyMap<string, string>>(new Map());
  protected readonly splitClients = computed(() => splitClients(this.cards(), this.ep(), this.calledToday()), { equal: sameJson });
  protected readonly splitPreview = signal<SplitPreview | null>(null);
  private splitSeq = 0;
  private splitOpenedFromFragment = false;

  // ── Dialog state ──────────────────────────────────────────────────────────────────────────────────────────────

  protected readonly takenSlugs = computed(() => (this.store.list() ?? []).map((e) => e.slug));
  protected readonly duplicateModel = signal({ slug: '' });
  protected readonly duplicateForm = form(
    this.duplicateModel,
    (p) => {
      // The kit's field stops at 40 characters (Signal Forms sets the input's maxlength from this).
      maxLength(p.slug, 40, { message: 'Use at most 40 characters.' });
      validate(p.slug, ({ value }) => {
        const error = slugError(value());
        if (error) return { kind: 'slug', message: error };
        const v = value().trim();
        return this.takenSlugs().includes(v) ? { kind: 'taken', message: `An endpoint named ${v} already exists. Pick another name.` } : null;
      });
    },
    { injector: this.injector },
  );
  protected readonly duplicateBusy = signal(false);
  protected readonly duplicateError = signal<string | null>(null);
  protected readonly duplicateSubmitted = signal(false);

  protected readonly deleteBusy = signal(false);
  protected readonly deleteError = signal<string | null>(null);
  protected readonly deleteInfo = computed(() => {
    const s = this.summary();
    return s ? deleteFacts(s, this.cards(), this.data.tokens()) : null;
  });

  protected readonly previews = signal<ReadonlyMap<string, ConnectionPreview | null> | null>(null);
  protected readonly candidates = computed<AddCandidate[]>(() => addCandidates(this.data.connections() ?? [], this.wired(), this.previews() ?? new Map()));
  protected readonly connectionCandidates = computed(() => this.candidates().filter((c) => !c.mcpServer));
  protected readonly serverCandidates = computed(() => this.candidates().filter((c) => c.mcpServer));
  protected readonly picked = signal<ReadonlySet<string>>(new Set());
  protected readonly addBusy = signal(false);
  protected readonly addTried = signal(false);
  /** A prod endpoint to point to when the core refuses a prod connection here (kit: "incident-prod already has them"). */
  protected readonly prodEndpoint = computed(() => (this.store.list() ?? []).find((e) => e.env === 'prod' && e.slug !== this.ep())?.slug ?? null);
  protected readonly prodRefused = computed(() => this.candidates().some((c) => !!c.refusal && c.row.env === 'prod'));

  protected readonly removing = signal<WiredConnection | null>(null);
  protected readonly removeBusy = signal(false);
  protected readonly removeError = signal<string | null>(null);

  protected readonly keepBusy = signal(false);
  protected readonly keepError = signal<string | null>(null);

  protected readonly issued = signal<IssuedToken | null>(null);
  protected readonly issueError = signal<string | null>(null);
  protected readonly issueBusy = signal(false);

  protected readonly toggling = signal(false);

  constructor() {
    const timer = setTimeout(() => this.slow.set(true), SKELETON_DELAY_MS);
    effect((onCleanup) => {
      if (!this.loading()) clearTimeout(timer);
      onCleanup(() => clearTimeout(timer));
    });
    // The route's slug: a link, the palette, or the new endpoint after a split.
    effect(() => {
      const slug = this.slug();
      if (slug) untracked(() => void this.data.open(slug));
    });
    // `?tab=` changed while the page is open (the wiring's footer links, the risk callout's Review tools).
    effect(() => {
      const id = this.selectedTab();
      const tabs = this.tabs();
      if (tabs) untracked(() => tabs.selectById(id));
    });
    // `#ep-dlg-split` opens Split endpoint once the risk is known (and only where there is something to split).
    effect(() => {
      const fragment = this.fragment();
      const options = this.splitOptions();
      const ready = !!this.data.detail() && !!this.splitDialog();
      if (fragment !== SPLIT_FRAGMENT) {
        this.splitOpenedFromFragment = false;
        return;
      }
      if (!ready || this.splitOpenedFromFragment) return;
      this.splitOpenedFromFragment = true;
      untracked(() => {
        if (options.length && this.data.risk()?.state === 'present') this.openSplit(null);
      });
    });
    // The core came back (it was away, or another instance answers): everything may be stale.
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

  // ── Tabs ──────────────────────────────────────────────────────────────────────────────────────────────────────

  /** A tab picked by hand: the URL follows (replaced, so Back leaves the page) and drops a section's fragment. */
  protected onTab(id: string): void {
    const tab = id.replace(/^tab-/, '') as TabId;
    if (parseTab(this.tab()) === tab && !this.fragment()) return;
    void this.router.navigate([], { queryParams: { tab: tab === 'tools' ? null : tab }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  // ── Tools ─────────────────────────────────────────────────────────────────────────────────────────────────────

  protected async setMode(c: ToolModeChange): Promise<void> {
    try {
      await this.data.setMode({ toolKey: c.toolKey, connection: c.connection, mode: c.mode, version: c.version });
    } catch (e) {
      this.toast({ tone: 'danger', title: "The mode wasn't changed", body: this.errorText(e) });
    }
  }

  protected async setOverrides(c: ToolOverridesChange): Promise<void> {
    const row = this.data.groups()?.find((g) => g.connection === c.connection)?.tools.find((t) => t.toolKey === c.toolKey);
    if (!row) return;
    try {
      await this.data.setMode({ toolKey: c.toolKey, connection: c.connection, mode: row.storedMode, overrides: c.overrides, version: c.version });
      this.toast({ tone: 'success', title: c.overrides ? `Limits of ${row.name} saved` : `${row.name} follows the policy again` });
    } catch (e) {
      this.toast({ tone: 'danger', title: `The limits of ${row.name} weren't saved`, body: this.errorText(e) });
    }
  }

  /** "Set all to…": tools that can't take the mode stay as they are, and the announcement names them (kit). */
  protected async bulk(b: { readonly connection: string | null; readonly mode: ToolMode }): Promise<void> {
    try {
      const result = await this.data.bulk(b.connection, b.mode);
      const names = result.skipped.map((s) => s.toolKey.split('/').at(-1) ?? s.toolKey);
      const label = b.mode === 'READ_ONLY' ? 'Read only' : b.mode === 'APPROVAL' ? 'Approval' : 'Off';
      this.announcer.announce(`Set to ${label}${names.length ? `. ${names.join(' and ')} kept ${names.length > 1 ? 'their modes' : 'its mode'}` : ''}`);
    } catch (e) {
      this.toast({ tone: 'danger', title: "The modes weren't changed", body: this.errorText(e) });
    }
  }

  protected async confirmReadOnly(c: { readonly toolKey: string; readonly connection: string | null; readonly definitionHash: string }): Promise<void> {
    try {
      await this.data.confirmReadOnly(c);
    } catch (e) {
      this.toast({ tone: 'danger', title: "Read only wasn't confirmed", body: this.errorText(e) });
    }
  }

  // ── Risk ──────────────────────────────────────────────────────────────────────────────────────────────────────

  protected async dismissRisk(reason: string): Promise<void> {
    try {
      await this.data.dismissRisk(reason);
      this.announcer.announce(`Risk warning dismissed for ${this.ep()}. The reason is in Setup changes.`);
      // The callout became its dismissed line: the keyboard goes to its one action, Show warning (kit).
      this.focusAfterRender(() => this.host.querySelector<HTMLElement>('#ep-risk .gw-alert__actions .gw-btn'));
    } catch (e) {
      this.toast({ tone: 'danger', title: "The warning wasn't dismissed", body: this.errorText(e) });
    }
  }

  protected async restoreRisk(): Promise<void> {
    try {
      await this.data.restoreRisk();
      this.announcer.announce(`Risk warning shown again for ${this.ep()}`);
      this.focusAfterRender(
        () => this.host.querySelector<HTMLElement>('#ep-risk .gw-alert__actions .gw-btn--link') ?? this.host.querySelector<HTMLElement>('#ep-risk .gw-alert__actions .gw-btn'),
      );
    } catch (e) {
      this.toast({ tone: 'danger', title: "The warning wasn't shown again", body: this.errorText(e) });
    }
  }

  /** Split endpoint: opens on the recommended move; the clients that used it today come pre-checked. */
  protected openSplit(opener: HTMLElement | null): void {
    const dialog = this.splitDialog();
    if (!dialog) return;
    this.splitPreview.set(null);
    this.calledToday.set(new Map());
    dialog.open(opener ?? (this.host.ownerDocument.activeElement as HTMLElement | null));
  }

  protected onSplitFromCallout(): void {
    this.openSplit(this.host.ownerDocument.activeElement as HTMLElement | null);
  }

  /** Every change of what moves (or of the name) asks the core what each endpoint would hold after the split. */
  protected async onSplitChanged(request: SplitRequest): Promise<void> {
    const seq = ++this.splitSeq;
    const move = request.move[0];
    const [preview, called] = await Promise.all([
      this.data.splitPreview(request).catch(() => null),
      move ? this.data.calledToday(move, this.time.now()) : Promise.resolve(new Map<string, string>()),
    ]);
    if (seq !== this.splitSeq) return;
    this.splitPreview.set(preview);
    this.calledToday.set(called);
  }

  protected async onSplitCreate(request: SplitRequest): Promise<void> {
    const remaining = this.splitPreview()?.remaining ?? null;
    try {
      const created = await this.data.split(request);
      const t = splitToast(created.slug, request.move[0] ?? '', this.ep(), remaining, request.clients);
      this.toasts.show({
        tone: t.tone,
        title: t.title,
        body: t.body,
        // The new endpoint has no client yet: each one connects from its Clients tab.
        actions: [
          {
            label: `Open ${created.slug}`,
            onClick: () => void this.router.navigate(['/endpoints', created.slug], { queryParams: { tab: request.clients.length ? 'clients' : null } }),
          },
        ],
      });
      this.focusAfterRender(() => this.host.querySelector<HTMLElement>('#ep-risk .gw-alert__actions .gw-btn') ?? this.host.querySelector<HTMLElement>('#ep-title'), true);
    } catch (e) {
      this.toast({ tone: 'danger', title: `${request.newSlug} wasn't created`, body: this.errorText(e) });
    }
  }

  // ── Endpoint actions ──────────────────────────────────────────────────────────────────────────────────────────

  protected askDuplicate(e: Event): void {
    this.duplicateModel.set({ slug: copySlug(this.ep(), this.takenSlugs()) });
    this.duplicateForm().reset();
    this.duplicateError.set(null);
    this.duplicateSubmitted.set(false);
    this.duplicateDialog()?.openAfterRender(this.menuTrigger(e));
  }

  protected duplicateErrorText(): string | null {
    const f = this.duplicateForm.slug();
    return this.duplicateSubmitted() || f.touched() ? (f.errors()[0]?.message ?? null) : null;
  }

  protected async confirmDuplicate(e: Event): Promise<void> {
    e.preventDefault();
    this.duplicateSubmitted.set(true);
    if (this.duplicateBusy()) return;
    if (this.duplicateForm().invalid()) {
      this.host.querySelector<HTMLElement>('#ep-dup-slug')?.focus();
      return;
    }
    const newSlug = this.duplicateModel().slug.trim();
    this.duplicateBusy.set(true);
    this.duplicateError.set(null);
    try {
      await this.data.duplicate(newSlug);
      this.duplicateDialog()?.close('duplicate');
      this.toast({ tone: 'success', title: `Endpoint ${newSlug} created`, body: `A copy of ${this.ep()} without tokens or client links.` });
    } catch (err) {
      this.duplicateError.set(this.errorText(err));
    } finally {
      this.duplicateBusy.set(false);
    }
  }

  /** Disable is reversible: it acts at once and offers Undo (kit, DESIGN §8). */
  protected async disable(): Promise<void> {
    if (this.toggling()) return;
    this.toggling.set(true);
    const slug = this.ep();
    try {
      await this.data.disable();
      this.toasts.show({
        tone: 'neutral',
        title: `Endpoint ${slug} disabled`,
        body: disableBody(this.cards(), slug, (this.data.tokens() ?? []).some((t) => t.state === 'active')),
        actions: [{ label: 'Undo', onClick: () => void this.enable(true) }],
      });
    } catch (e) {
      this.toast({ tone: 'danger', title: `${slug} wasn't disabled`, body: this.errorText(e) });
    } finally {
      this.toggling.set(false);
    }
  }

  protected async enable(undo = false): Promise<void> {
    if (this.toggling()) return;
    this.toggling.set(true);
    const slug = this.ep();
    try {
      await this.data.enable();
      if (undo) this.announcer.announce(`Endpoint ${slug} is running again`);
      else this.toast({ tone: 'success', title: `Endpoint ${slug} is running again`, body: 'Clients can call it from their next call.' });
    } catch (e) {
      this.toast({ tone: 'danger', title: `${slug} wasn't enabled`, body: this.errorText(e) });
    } finally {
      this.toggling.set(false);
    }
  }

  protected askDelete(e: Event): void {
    this.deleteError.set(null);
    this.deleteBusy.set(false);
    this.deleteDialog()?.openAfterRender(this.menuTrigger(e));
  }

  protected async confirmDelete(): Promise<void> {
    if (this.deleteBusy()) return;
    const slug = this.ep();
    this.deleteBusy.set(true);
    this.deleteError.set(null);
    try {
      await this.data.remove();
      this.deleteDialog()?.close('delete');
      this.toast({ tone: 'success', title: `Endpoint ${slug} deleted`, body: 'Its URL stops answering. The audit log keeps every call.' });
      void this.router.navigate(['/endpoints']);
    } catch (e) {
      this.deleteError.set(this.errorText(e));
    } finally {
      this.deleteBusy.set(false);
    }
  }

  protected askKeep(e: Event): void {
    this.keepError.set(null);
    this.keepDialog()?.openAfterRender(e.currentTarget as HTMLElement);
  }

  protected async confirmKeep(): Promise<void> {
    if (this.keepBusy()) return;
    this.keepBusy.set(true);
    this.keepError.set(null);
    try {
      await this.data.confirmIntegrity();
      this.keepDialog()?.close('kept');
      this.toast({ tone: 'success', title: `${this.ep()} is in use again`, body: 'Agents can call it from their next call.' });
      this.focusAfterRender(() => this.host.querySelector<HTMLElement>('#ep-title'));
    } catch (e) {
      this.keepError.set(this.errorText(e));
    } finally {
      this.keepBusy.set(false);
    }
  }

  // ── Connections ───────────────────────────────────────────────────────────────────────────────────────────────

  /** Add connections: opens at once; what each would do fills in as the core answers its previews. */
  protected askAdd(e: Event): void {
    this.picked.set(new Set());
    this.addTried.set(false);
    this.previews.set(null);
    this.addDialog()?.openAfterRender(e.currentTarget as HTMLElement);
    const names = (this.data.connections() ?? []).filter((r) => !this.wired().some((w) => w.name === r.name)).map((r) => r.name);
    void this.data.previews(names).then((p) => {
      this.previews.set(p);
      // The refused ones move to the end: if the first box (now refused) still has the focus, give it to the first
      // connection that can be added.
      this.focusAfterRender(() => {
        const dialog = this.host.querySelector('#ep-dlg-add-conn');
        const active = this.host.ownerDocument.activeElement;
        if (!dialog?.contains(active) || active?.getAttribute('aria-disabled') !== 'true' || this.picked().size) return null;
        return dialog.querySelector<HTMLElement>('input[type="checkbox"]:not([aria-disabled="true"])');
      });
    });
  }

  protected togglePick(c: AddCandidate, on: boolean): void {
    if (c.refusal) return;
    this.picked.update((s) => {
      const next = new Set(s);
      if (on) next.add(c.row.name);
      else next.delete(c.row.name);
      return next;
    });
  }

  protected async confirmAdd(): Promise<void> {
    this.addTried.set(true);
    const names = [...this.picked()];
    if (this.addBusy() || !names.length) return;
    this.addBusy.set(true);
    try {
      const chosen = this.candidates().filter((c) => names.includes(c.row.name));
      const result = await this.data.addConnections(names);
      this.addDialog()?.close('add');
      this.toast(addedToast(this.ep(), chosen.filter((c) => result.added.includes(c.row.name)), result.refused));
      const first = result.added[0];
      if (first) this.focusAfterRender(() => this.host.querySelector<HTMLElement>(`#ep-conn-${CSS.escape(first)}`));
    } finally {
      this.addBusy.set(false);
    }
  }

  protected askRemove(c: WiredConnection, e: Event): void {
    this.removing.set(c);
    this.removeError.set(null);
    this.removeBusy.set(false);
    this.removeDialog()?.openAfterRender(e.currentTarget as HTMLElement);
  }

  protected async confirmRemove(): Promise<void> {
    const c = this.removing();
    if (!c || this.removeBusy()) return;
    this.removeBusy.set(true);
    this.removeError.set(null);
    try {
      await this.data.removeConnection(c.name);
      this.removeDialog()?.close('remove');
      this.toast({ tone: 'success', title: `${c.name} removed from ${this.ep()}`, body: 'Add it back any time with Add connection.' });
      // The row is gone: the keyboard goes to Add connection (or to the empty state's).
      this.focusAfterRender(() => this.host.querySelector<HTMLElement>('#ep-conn-add, #ep-conn-empty-add'));
    } catch (e) {
      this.removeError.set(this.errorText(e));
    } finally {
      this.removeBusy.set(false);
    }
  }

  protected onRemoveClosed(): void {
    this.removing.set(null);
  }

  // ── Tokens ────────────────────────────────────────────────────────────────────────────────────────────────────

  protected openIssue(opener: HTMLElement): void {
    this.issued.set(null);
    this.issueError.set(null);
    this.tokenDialog()?.open(opener);
  }

  protected async issue(request: { readonly name: string; readonly ttlDays: number }): Promise<void> {
    if (this.issueBusy()) return;
    this.issueBusy.set(true);
    this.issueError.set(null);
    try {
      this.issued.set(await this.data.issueToken(request.name, request.ttlDays));
    } catch (e) {
      this.issueError.set(this.errorText(e));
    } finally {
      this.issueBusy.set(false);
    }
  }

  /** Done (after "I've stored this token"): the page drops its copy too. */
  protected onIssueDone(): void {
    this.issued.set(null);
  }

  protected async revoke(row: TokenRow): Promise<void> {
    try {
      const result = await this.data.revoke(row);
      this.toast(revokedToast(row, result.rejectedApprovals));
    } catch (e) {
      this.toast({ tone: 'danger', title: `${row.name} wasn't revoked`, body: this.errorText(e) });
    }
  }

  protected async revokeGrant(id: string, name: string): Promise<void> {
    try {
      await this.data.revokeGrant(id);
      this.toast({ tone: 'success', title: `Sign-in of ${name} revoked`, body: 'It is cut off at once.' });
    } catch (e) {
      this.toast({ tone: 'danger', title: `${name} wasn't revoked`, body: this.errorText(e) });
    }
  }

  // ── Errors ────────────────────────────────────────────────────────────────────────────────────────────────────

  protected retry(): void {
    void this.data.refresh();
  }

  protected errorText(e: unknown, fallback = NO_ANSWER): string {
    return isApiError(e) ? e.detail : fallback;
  }

  protected errorDetails(e: unknown): string {
    if (isApiError(e)) return `GET /api/endpoints/${this.ep()} → ${e.status || 'no answer'}\n${e.title}: ${e.detail}`;
    return e instanceof Error ? `${e.name}: ${e.message}` : String(e);
  }

  private toast(t: ToastText): void {
    this.toasts.show({ tone: t.tone, title: t.title, body: t.body });
  }

  /** The menu that held the item closes: focus goes back to its trigger (kit), not to the hidden item. */
  private menuTrigger(e: Event): HTMLElement | null {
    return (e.target as Element | null)?.closest('.gw-menu-host')?.querySelector<HTMLElement>('[aria-haspopup="menu"]') ?? null;
  }

  /** Moves focus once the view shows the change; `onlyIfLost` leaves it alone unless it fell back to the body. */
  private focusAfterRender(target: () => HTMLElement | null | undefined, onlyIfLost = false): void {
    afterNextRender(
      () => {
        const doc = this.host.ownerDocument;
        const active = doc.activeElement;
        if (onlyIfLost && active && active !== doc.body && doc.contains(active)) return;
        target()?.focus();
      },
      { injector: this.injector },
    );
  }
}
