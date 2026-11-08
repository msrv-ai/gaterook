import { GwAccessSwitch } from '../../kit/access-switch';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormField, form, submit } from '@angular/forms/signals';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { isApiError } from '../../core/api/api-error';
import { EndpointDetail, EndpointSummary, EndpointsApi } from '../../core/api/endpoints.api';
import { DesktopBridge } from '../../core/desktop/desktop-bridge';
import { CoreStore } from '../../core/stores/core.store';
import { TimeService } from '../../core/time/time.service';
import { GwCodeBlock } from '../../code/code-block';
import { Announcer } from '../../kit/announcer';
import { GwCopy } from '../../kit/copy';
import { GwDialog, GwDialogClose } from '../../kit/dialog';
import { GwToggle } from '../../kit/disclosure';
import { GwRiskCallout } from '../../kit/elements/risk-callout/risk-callout';
import { GwClientMark } from '../../kit/elements/shared/client-mark';
import { GwIcon } from '../../kit/icons';
import { GwMenu, GwMenuTrigger } from '../../kit/menu';
import { ToastService } from '../../kit/toast.service';
import { GwEndpointShare } from '../endpoint-detail/endpoint-share.component';
import { EndpointsData } from './endpoints.data';
import { GwEndpointCreate, endpointSlugRules } from './endpoint-create';
import {
  DeleteFacts,
  ToastText,
  blockedText,
  clientParts,
  connectionsByEnv,
  copySlug,
  deleteFacts,
  deletedToast,
  disableBody,
  disabledToast,
  duplicatedToast,
  headerStatuses,
  healthParts,
  plural,
  tokensFact,
  toolParts,
} from './endpoints-view';

/** The first render waits this long before it shows placeholders (kit: "when it takes longer than 300 ms"). */
export const SKELETON_DELAY_MS = 300;

const NO_ANSWER = 'The core didn’t answer. Try again.';

/**
 * Endpoints (ARCHITECTURE.md §8.4 "endpoints"): every MCP URL with what it gives an
 * agent, one card each.
 *
 * - The header counts the endpoints and says whether they run; Create endpoint is the page's one primary.
 * - A card: the slug (a link to its page) and description; Running / Disabled and the
 *   menu (Duplicate, Export, Disable or Enable, Delete endpoint); the URL to copy; connections by environment; connection
 *   health that names what is slow or unreachable; tools by mode; the clients; tokens (active, expired, one expiring
 *   soon); the approvals waiting; the compact risk callout with Review; View activity, Open in Playground, Configure.
 *   An endpoint changed outside GateRook says so and can be kept after review.
 * - Create asks for a slug (the URL shows as it is typed), a description, a preset and explicit connections, then opens the new
 *   endpoint's page; Duplicate asks for the copy's slug; Disable acts at once with Undo in a toast; Delete asks in a
 *   `gw-dialog--danger` that names what stops working, Cancel has the focus.
 *
 * States: nothing for 300 ms, then skeleton cards; an error with Try again; no endpoints (the kit's empty state, the
 * header keeps Create endpoint); out of date (a reload failed with cards on screen). Counters follow approvals,
 * client links and connection health as the core reports them.
 */
@Component({
  selector: 'gw-endpoints-page',
  imports: [GwAccessSwitch,
    RouterLink,
    FormField,
    GwIcon,
    GwMenu,
    GwMenuTrigger,
    GwDialog,
    GwDialogClose,
    GwCopy,
    GwToggle,
    GwCodeBlock,
    GwRiskCallout,
    GwClientMark,
    GwEndpointShare,
    GwEndpointCreate,
  ],
  providers: [EndpointsData],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page' },
  templateUrl: './endpoints.page.html',
})
export class EndpointsPage {
  protected readonly data = inject(EndpointsData);
  protected readonly time = inject(TimeService);
  private readonly core = inject(CoreStore);
  private readonly toasts = inject(ToastService);
  private readonly announcer = inject(Announcer);
  private readonly router = inject(Router);
  private readonly injector = inject(Injector);
  private readonly destroyRef = inject(DestroyRef);
  private readonly endpointsApi = inject(EndpointsApi);
  private readonly desktop = inject(DesktopBridge);
  private readonly host: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;
  private readonly fragment = toSignal(inject(ActivatedRoute).fragment, { initialValue: null });

  private readonly createDialog = viewChild.required(GwEndpointCreate);
  private readonly duplicateDialog = viewChild.required<GwDialog>('duplicateDialog');
  private readonly deleteDialog = viewChild.required<GwDialog>('deleteDialog');
  private readonly keepDialog = viewChild.required<GwDialog>('keepDialog');
  private readonly shareDialog = viewChild.required<GwEndpointShare>('shareDialog');

  protected readonly sharing = signal<EndpointDetail | null>(null);
  protected readonly exportingSlug = signal<string | null>(null);

  /** Placeholders only when the first answer is slow. */
  protected readonly slow = signal(false);
  protected readonly loading = computed(() => !this.data.rows() && !this.data.error());
  protected readonly rows = computed(() => this.data.rows() ?? []);
  protected readonly empty = computed(() => this.data.rows()?.length === 0);

  /** `http://127.0.0.1:7465/mcp/`: where every endpoint answers, for the slug fields' live URL. */
  protected readonly base = computed(() => `http://${this.core.address()}/mcp/`);

  // ── Header ────────────────────────────────────────────────────────────────────────────────────────────────────

  protected readonly countText = computed(() => plural(this.rows().length, 'endpoint'));
  protected readonly statuses = computed(() => headerStatuses(this.rows()));

  // ── Cards ─────────────────────────────────────────────────────────────────────────────────────────────────────

  /** One view model per endpoint; expiry texts move with the clock (once a minute is enough for days). */
  protected readonly cards = computed(() => {
    const rows = this.rows();
    const connections = this.data.connections();
    const tokens = this.data.tokens();
    const runbooks = this.data.runbooks();
    const busy = this.data.busy();
    this.data.oauth();
    const now = this.time.minute();
    return rows.map((e) => {
      const limited = e.risk.sources.find((s) => s.ingredient === 'externalChannel' && /\bfixed runbooks\b/.test(s.text));
      return {
        e,
        id: `ep-${e.slug}`,
        blocked: !!e.blockedReason,
        blockedText: e.blockedReason ? blockedText(e.blockedReason) : null,
        busy: busy.has(e.slug),
        connections: connectionsByEnv(e),
        health: healthParts(e, connections),
        tools: toolParts(e),
        clients: clientParts(e),
        tokens: tokensFact(e, tokens.get(e.slug) ?? null, this.data.oauthSignIns(e.slug), now, (at) => this.time.relative(at)),
        runbooks: limited && runbooks ? (runbooks.get(limited.connection) ?? null) : null,
        showRisk: e.risk.state !== 'none',
      };
    });
  });

  // ── Duplicate ─────────────────────────────────────────────────────────────────────────────────────────────────

  protected readonly duplicating = signal<EndpointSummary | null>(null);
  protected readonly duplicateModel = signal({ slug: '' });
  protected readonly duplicateForm = form(this.duplicateModel, (p) => endpointSlugRules(p.slug));
  protected readonly duplicateTried = signal(false);
  protected readonly duplicateError = signal<string | null>(null);

  // ── Delete and keep ───────────────────────────────────────────────────────────────────────────────────────────

  protected readonly deleting = signal<EndpointSummary | null>(null);
  protected readonly deleteFacts = computed<DeleteFacts | null>(() => {
    const e = this.deleting();
    return e ? deleteFacts(e, this.data.tokens().get(e.slug) ?? null, this.time.minute()) : null;
  });
  protected readonly deleteBusy = signal(false);
  protected readonly deleteError = signal<string | null>(null);
  private deletedAt: number | null = null;

  protected readonly keeping = signal<EndpointSummary | null>(null);
  protected readonly keepBusy = signal(false);
  protected readonly keepError = signal<string | null>(null);
  /** The core asked to reset what an ordinary change couldn't have stored (`confirm: reset-invalid`). */
  protected readonly keepConfirm = signal<string | null>(null);

  constructor() {
    void this.data.start();
    const timer = setTimeout(() => this.slow.set(true), SKELETON_DELAY_MS);
    effect((onCleanup) => {
      if (!this.loading()) clearTimeout(timer);
      onCleanup(() => clearTimeout(timer));
    });
    // `/endpoints#create` (getting started, the command palette's "Create endpoint") opens the dialog.
    effect(() => {
      if (this.fragment() === 'create') untracked(() => this.openCreate(null));
    });
    // The core came back (it was away, or another instance answers): the list may be stale.
    let wasRunning = this.core.running();
    let instance = this.core.instanceChanges();
    effect(() => {
      const running = this.core.running();
      const changes = this.core.instanceChanges();
      untracked(() => {
        if (running && (!wasRunning || changes !== instance)) void this.data.refresh('full');
        wasRunning = running;
        instance = changes;
      });
    });
  }

  protected url(slug: string): string {
    return `${this.base()}${slug.trim() || 'your-slug'}`;
  }

  protected exportUnavailableReason(endpoint: EndpointSummary): string | null {
    if (!this.desktop.endpointShareAvailable) return 'Available in an up-to-date GateRook desktop app';
    if (endpoint.blockedReason) return 'Review the endpoint before exporting it';
    return this.exportingSlug() ? 'Loading endpoint for export' : null;
  }

  protected async exportEndpoint(endpoint: EndpointSummary, event: Event): Promise<void> {
    if (this.exportUnavailableReason(endpoint)) return;
    const opener = this.menuTrigger(event);
    this.exportingSlug.set(endpoint.slug);
    try {
      const detail = await this.endpointsApi.get(endpoint.slug);
      if (this.destroyRef.destroyed) return;
      if (detail.endpoint.blockedReason) {
        this.toast({ tone: 'warning', title: `Endpoint ${endpoint.slug} needs review`, body: 'Review the endpoint before exporting it.' });
        return;
      }
      this.sharing.set(detail);
      afterNextRender(() => this.shareDialog().openFrom(opener), { injector: this.injector });
    } catch (err) {
      if (!this.destroyRef.destroyed) {
        this.toast({ tone: 'warning', title: `Endpoint ${endpoint.slug} couldn't be loaded for export`, body: this.errorText(err, NO_ANSWER) });
      }
    } finally {
      this.exportingSlug.set(null);
    }
  }

  // ── Create ────────────────────────────────────────────────────────────────────────────────────────────────────

  protected openCreate(opener: HTMLElement | null): void {
    this.createDialog().open(opener);
  }

  protected onEndpointCreated(endpoint: EndpointSummary): void {
    void this.data.refresh('full');
    void this.router.navigate(['/endpoints', endpoint.slug]);
  }

  protected onCreateClosed(): void {
    // Drop the fragment so the same Create endpoint link can open the dialog again.
    if (this.fragment() === 'create') void this.router.navigate([], { fragment: undefined, replaceUrl: true });
  }

  // ── Duplicate ─────────────────────────────────────────────────────────────────────────────────────────────────

  protected askDuplicate(e: EndpointSummary, ev: Event): void {
    if (e.blockedReason) return;
    this.duplicating.set(e);
    this.duplicateModel.set({ slug: copySlug(e.slug, this.rows().map((r) => r.slug)) });
    this.duplicateForm().reset();
    this.duplicateTried.set(false);
    this.duplicateError.set(null);
    this.duplicateDialog().openAfterRender(this.menuTrigger(ev));
  }

  protected async submitDuplicate(ev: Event): Promise<void> {
    ev.preventDefault();
    const source = this.duplicating();
    if (!source) return;
    this.duplicateTried.set(true);
    this.duplicateError.set(null);
    await submit(this.duplicateForm, {
      action: async () => {
        const slug = this.duplicateModel().slug.trim();
        try {
          await this.data.duplicate(source.slug, slug);
        } catch (err) {
          return this.duplicateProblem(err);
        }
        this.duplicateDialog().close('duplicate');
        this.toast(duplicatedToast(slug, source.slug));
        return undefined;
      },
      onInvalid: () => this.focusFirstInvalid(this.duplicateDialog().element),
    });
  }

  /** A taken or refused slug is about the field; "changed outside GateRook" is about the source, in the alert. */
  private duplicateProblem(err: unknown) {
    const message = this.errorText(err, NO_ANSWER);
    const dialog = this.duplicateDialog().element;
    this.focusAfterRender(() => this.firstInvalid(dialog) ?? dialog.querySelector<HTMLElement>('[role="alert"]'));
    const onSlug = isApiError(err) && (err.field === '/slug' || (err.kind === 'conflict' && /slug in use/i.test(err.title)));
    if (onSlug) return [{ kind: 'server', message, fieldTree: this.duplicateForm.slug }];
    this.duplicateError.set(message);
    return undefined;
  }

  protected onDuplicateClosed(): void {
    this.duplicating.set(null);
  }

  // ── Disable, enable ───────────────────────────────────────────────────────────────────────────────────────────

  /** Disable is reversible, so it acts at once and offers Undo in a toast instead of asking first. */
  protected async disable(e: EndpointSummary): Promise<void> {
    if (this.data.busy().has(e.slug)) return;
    // Said before the change: the tokens and clients it names are the ones the endpoint has now.
    const body = disableBody(e, this.rows(), this.data.tokens().get(e.slug) ?? null, this.time.now());
    try {
      await this.data.disable(e.slug);
    } catch (err) {
      this.toast({ tone: 'warning', title: `Endpoint ${e.slug} wasn't disabled`, body: this.errorText(err, NO_ANSWER) });
      return;
    }
    const t = disabledToast(e, body);
    this.toasts.show({ tone: t.tone, title: t.title, body: t.body, actions: [{ label: 'Undo', onClick: () => void this.enable(e, true) }] });
  }

  protected async enable(e: EndpointSummary, undo = false): Promise<void> {
    if (this.data.busy().has(e.slug)) return;
    try {
      await this.data.enable(e.slug);
    } catch (err) {
      this.toast({ tone: 'warning', title: `Endpoint ${e.slug} wasn't enabled`, body: this.errorText(err, NO_ANSWER) });
      return;
    }
    if (undo) this.announcer.announce(`Endpoint ${e.slug} is running again`);
    else this.toast({ tone: 'success', title: `Endpoint ${e.slug} is running again`, body: 'Agents can call it from their next call.' });
  }

  // ── Delete ────────────────────────────────────────────────────────────────────────────────────────────────────

  protected askDelete(e: EndpointSummary, ev: Event): void {
    this.deleting.set(e);
    this.deleteError.set(null);
    this.deleteBusy.set(false);
    this.deletedAt = null;
    this.deleteDialog().openAfterRender(this.menuTrigger(ev));
  }

  protected async confirmDelete(): Promise<void> {
    const e = this.deleting();
    if (!e || this.deleteBusy()) return;
    this.deleteBusy.set(true);
    this.deleteError.set(null);
    const at = this.rows().findIndex((r) => r.slug === e.slug);
    try {
      const result = await this.data.remove(e.slug);
      this.deletedAt = at;
      this.deleteDialog().close('deleted');
      this.toast(deletedToast(e.slug, result));
    } catch (err) {
      this.deleteError.set(this.errorText(err, NO_ANSWER));
    } finally {
      this.deleteBusy.set(false);
    }
  }

  /** After a delete the card is gone: the keyboard goes to the card that took its place, else to Create endpoint. */
  protected onDeleteClosed(): void {
    const at = this.deletedAt;
    if (at !== null) {
      this.focusAfterRender(() => {
        const links = this.host.querySelectorAll<HTMLElement>('.gw-card__title a');
        return links[Math.min(at, links.length - 1)] ?? this.host.querySelector<HTMLElement>('#ep-create-open');
      });
    }
    this.deletedAt = null;
    this.deleting.set(null);
  }

  // ── Keep an endpoint changed outside GateRook ──────────────────────────────────────────────────────────────

  protected askKeep(e: EndpointSummary, ev: Event): void {
    this.keeping.set(e);
    this.keepError.set(null);
    this.keepConfirm.set(null);
    this.keepBusy.set(false);
    this.keepDialog().openAfterRender(ev.currentTarget as HTMLElement);
  }

  protected async confirmKeep(): Promise<void> {
    const e = this.keeping();
    if (!e || this.keepBusy()) return;
    this.keepBusy.set(true);
    this.keepError.set(null);
    try {
      await this.data.keep(e.slug, this.keepConfirm());
      this.keepDialog().close('kept');
      this.toast({ tone: 'success', title: `Endpoint ${e.slug} is in use again`, body: 'Agents can call it from their next call.' });
      this.focusAfterRender(() => this.host.querySelector<HTMLElement>(`#ep-${e.slug}-link`));
    } catch (err) {
      // 422 with `confirm: reset-invalid`: some values couldn't come from an ordinary change; keeping resets them.
      if (isApiError(err) && err.confirm && !this.keepConfirm()) this.keepConfirm.set(err.confirm);
      this.keepError.set(this.errorText(err, NO_ANSWER));
    } finally {
      this.keepBusy.set(false);
    }
  }

  protected onKeepClosed(): void {
    this.keeping.set(null);
    this.keepConfirm.set(null);
  }

  // ── Risk ──────────────────────────────────────────────────────────────────────────────────────────────────────

  /** "Show warning" on a dismissed callout: the warning is back, and the audit says who brought it back. */
  protected async restoreRisk(e: EndpointSummary): Promise<void> {
    if (this.data.busy().has(e.slug)) return;
    try {
      await this.data.restoreRisk(e.slug);
      this.announcer.announce(`The risk warning for ${e.slug} is shown again`);
    } catch (err) {
      this.toast({ tone: 'warning', title: `The warning for ${e.slug} wasn't restored`, body: this.errorText(err, NO_ANSWER) });
    }
  }

  // ── Errors, focus ─────────────────────────────────────────────────────────────────────────────────────────────

  protected retry(): void {
    void this.data.refresh('full');
  }

  /** What a failed call says to do: the core's words when it sent them. */
  protected errorText(e: unknown, fallback = 'See the core log for details, then try again.'): string {
    return isApiError(e) ? e.detail : fallback;
  }

  /** The technical side of a failed load, for the code block under Details. */
  protected errorDetails(e: unknown): string {
    if (isApiError(e)) return `GET /api/endpoints → ${e.status || 'no answer'}\n${e.title}: ${e.detail}`;
    return e instanceof Error ? `${e.name}: ${e.message}` : String(e);
  }

  /** The first error message of a field, once the person tried to submit. */
  protected fieldError(tried: boolean, errors: readonly { readonly message?: string }[]): string | null {
    return tried ? (errors[0]?.message ?? null) : null;
  }

  private toast(t: ToastText): void {
    this.toasts.show({ tone: t.tone, title: t.title, body: t.body });
  }

  /** The trigger of the menu the item sat in: the item is hidden once the menu closes, so focus returns there. */
  private menuTrigger(ev: Event): HTMLElement | null {
    return (ev.target as Element | null)?.closest('.gw-menu-host')?.querySelector<HTMLElement>('[aria-haspopup="menu"]') ?? null;
  }

  private firstInvalid(scope: HTMLElement): HTMLElement | null {
    return scope.querySelector<HTMLElement>('[aria-invalid="true"]');
  }

  /** Focus on the first field in error, once its error shows. */
  private focusFirstInvalid(scope: HTMLElement): void {
    this.focusAfterRender(() => this.firstInvalid(scope));
  }

  private focusAfterRender(target: () => HTMLElement | null | undefined): void {
    afterNextRender(() => target()?.focus(), { injector: this.injector });
  }
}
