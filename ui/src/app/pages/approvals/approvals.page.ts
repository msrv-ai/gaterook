import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterRenderEffect,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink, UrlTree } from '@angular/router';
import { ActivityApi } from '../../core/api/activity.api';
import { ApiError, isApiError } from '../../core/api/api-error';
import { ApprovalDecision, ApprovalDetail, ApprovalItem, ApprovalsApi } from '../../core/api/approvals.api';
import { Notifications, NotificationsApi } from '../../core/api/notifications.api';
import { DesktopBridge } from '../../core/desktop/desktop-bridge';
import { EventStream } from '../../core/events/event-stream';
import { TimeService } from '../../core/time/time.service';
import { GwCodeBlock } from '../../code/code-block';
import { Announcer } from '../../kit/announcer';
import { GwToggle } from '../../kit/disclosure';
import { GwApprovalRequest } from '../../kit/elements/approval-request/approval-request';
import { GwApprovalRequestCompact } from '../../kit/elements/approval-request/approval-request-compact';
import { GwNotificationCard, GwNotificationPreview } from '../../kit/elements/approval-notification/approval-notification';
import { GwIcon } from '../../kit/icons';
import { GwTabs } from '../../kit/tabs';
import { ToastService } from '../../kit/toast.service';
import {
  ACTIVITY_APPROVAL_CALLS,
  ACTIVITY_DECIDED,
  ACTIVITY_INDEX_QUERY,
  CallRef,
  ChannelStatus,
  DeliveryCard,
  afterApprovalToast,
  callIndex,
  channelStatuses,
  decidedCount,
  decidersLine,
  decisionToast,
  deliveryCard,
  historyInfo,
  historyItems,
  historyRows,
  lastMinuteAnnouncement,
  mostUrgent,
  queueLabel,
  requests,
  resolvedElsewhere,
  ruleNote,
  sortQueue,
  stillWaiting,
  withOwnCalls,
} from './approvals-view';

type Tab = 'pending' | 'history';

/** How the header knows the delivery channels: Settings › Notifications arrives with step K2 (§6 settings). */
type ChannelsState = 'loading' | 'ready' | 'not-available' | 'error';

/** What the detail pane shows for the selected request. */
type DetailState = 'none' | 'loading' | 'ready' | 'not-found' | 'error';

/** How many history rows the page asks for: enough to fill the table after the 24-hour cut. */
const HISTORY_PAGE = 50;
/** The calls the page looks up in Activity to link requests to their call and trace. */
const ACTIVITY_PAGE = 200;
/** After a countdown ends here the core expires the request on its own timer; look again if no event said so. */
const EXPIRY_RECHECK_MS = 3000;

/**
 * Approvals (design/pages/approvals.html, ARCHITECTURE.md §8.4): the queue of requests waiting for a person, most
 * urgent first, next to the selected request in full (the kit element `approval-request`), and the history of the
 * last 24 hours. Data: `GET /api/approvals?status=pending|history`, `GET /api/approvals/{id}` for each queued request
 * (its exact action feeds the queue's preview line), `POST /api/approvals/{id}/decision`; live through the event
 * stream (`approval.requested`, `approval.resolved`, `audit.call`). Activity's events link each request to its call
 * and trace; Settings › Notifications names the delivery channels (step K2: until it is merged the line says so).
 *
 * Behaviour (the kit page's script):
 * - `/approvals/<id>` (Review, a deep link, Open in app) opens that request and focuses its title; with no id the
 *   most urgent pending request opens, so the one about to be denied is never left closed;
 * - after Deny or Approve and run the request leaves the queue, a toast says what happens next, the next most urgent
 *   request opens with focus on its title and "2 requests still waiting" is announced; after the last one focus goes
 *   to the empty detail's title;
 * - a countdown that ends here turns the request into Expired in place, and the Pending count and the queue's name
 *   follow the requests still pending; the core's own expiry then moves it to History;
 * - a request decided elsewhere (Slack, a notification, the core) leaves the queue; if it is the one open, it stays
 *   open and shows its outcome;
 * - History lists the newest decisions of the last 24 hours, each row opening its call in Activity, and the detail
 *   pane says so instead of keeping a live decision next to a list it is not part of.
 */
@Component({
  selector: 'gw-approvals-page',
  imports: [
    RouterLink,
    GwIcon,
    GwTabs,
    GwToggle,
    GwCodeBlock,
    GwApprovalRequest,
    GwApprovalRequestCompact,
    GwNotificationCard,
    GwNotificationPreview,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page gw-page--full' },
  templateUrl: './approvals.page.html',
})
export class ApprovalsPage {
  private readonly api = inject(ApprovalsApi);
  private readonly activity = inject(ActivityApi);
  private readonly notificationsApi = inject(NotificationsApi);
  private readonly events = inject(EventStream);
  private readonly router = inject(Router);
  private readonly time = inject(TimeService);
  private readonly toasts = inject(ToastService);
  private readonly announcer = inject(Announcer);
  private readonly desktop = inject(DesktopBridge);
  private readonly host: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;

  /** Route parameter `id` (`/approvals/<id>`, withComponentInputBinding); null or undefined on `/approvals`. */
  readonly id = input<string | null | undefined>(null);

  protected readonly ACTIVITY_APPROVAL_CALLS = ACTIVITY_APPROVAL_CALLS;
  protected readonly ACTIVITY_DECIDED = ACTIVITY_DECIDED;
  protected readonly requests = requests;
  protected readonly lastMinute = lastMinuteAnnouncement;
  protected readonly ruleNote = ruleNote;

  // ── Data from the core ──────────────────────────────────────────────────────────────────────────────────────
  /** The pending list as the core last sent it; null until the first answer. */
  private readonly serverQueue = signal<ApprovalItem[] | null>(null);
  private readonly history = signal<ApprovalItem[]>([]);
  private readonly counts = signal<{ readonly [status: string]: number }>({});
  /** The last load failed (kept next to the data it couldn't refresh). */
  protected readonly loadError = signal<ApiError | null>(null);
  protected readonly details = signal<ReadonlyMap<string, ApprovalDetail>>(new Map());
  private readonly detailErrors = signal<ReadonlyMap<string, ApiError>>(new Map());
  private readonly calls = signal<ReadonlyMap<string, CallRef>>(new Map());
  private readonly notifications = signal<Notifications | null>(null);
  protected readonly channelsState = signal<ChannelsState>('loading');

  // ── What happened here, before the core's next answer ──────────────────────────────────────────────────────
  /** Requests whose countdown ended on screen: no longer counted as waiting. */
  private readonly expiredHere = signal<ReadonlySet<string>>(new Set());
  /** Requests decided in this window: out of the queue even if an answer from before the decision still lists them. */
  private readonly decidedHere = signal<ReadonlySet<string>>(new Set());

  // ── Selection, tabs, decisions ─────────────────────────────────────────────────────────────────────────────
  /** The request the URL names. */
  private readonly chosen = signal<string | null>(null);
  /** The request opened for the person when the URL names none (the most urgent, pinned once chosen). */
  private readonly autoSelected = signal<string | null>(null);
  protected readonly tab = signal<Tab>('pending');
  protected readonly busy = signal<{ readonly id: string; readonly decision: 'approve' | 'deny' } | null>(null);
  protected readonly decisionError = signal<{ readonly id: string; readonly text: string } | null>(null);
  /** Where focus goes once it is on screen: a request's title (its id) or the empty detail's title. */
  private readonly focusTarget = signal<string | null>(null);

  private readonly requestView = viewChild(GwApprovalRequest);
  private readonly emptyTitle = viewChild<ElementRef<HTMLElement>>('emptyTitle');
  private readonly missingTitle = viewChild<ElementRef<HTMLElement>>('missingTitle');
  private readonly tabs = viewChild(GwTabs);
  private readonly fragment = toSignal(inject(ActivatedRoute).fragment, { initialValue: null });

  // ── Derived ────────────────────────────────────────────────────────────────────────────────────────────────
  protected readonly loaded = computed(() => this.serverQueue() !== null);
  /** The queue as shown: the core's list without the requests decided here, soonest auto-deny first. */
  protected readonly queue = computed(() => {
    const decided = this.decidedHere();
    return sortQueue((this.serverQueue() ?? []).filter((i) => !decided.has(i.id)));
  });
  /** The requests still waiting: not decided, not run out on screen. */
  protected readonly waiting = computed(() => {
    const expired = this.expiredHere();
    return this.queue().filter((i) => i.status === 'pending' && !expired.has(i.id));
  });
  protected readonly pendingCount = computed(() => this.waiting().length);
  protected readonly queueName = computed(() => queueLabel(this.pendingCount()));
  protected readonly historyTotal = computed(() => decidedCount(this.counts()));
  private readonly historyItems = computed(() => historyItems(this.history(), this.time.minute()));
  /** Every request's call: Activity's index and live rows, with what the requests' details say (step API). */
  private readonly allCalls = computed(() => withOwnCalls(this.calls(), this.details()));
  protected readonly historyRows = computed(() => historyRows(this.historyItems(), this.allCalls(), this.details()));
  protected readonly historyLine = computed(() => {
    const shown = this.historyRows().length;
    return historyInfo(shown, Math.max(this.historyTotal(), shown));
  });

  protected readonly selectedId = computed(() => this.chosen() ?? this.autoSelected());
  protected readonly selected = computed(() => {
    const id = this.selectedId();
    return id ? (this.details().get(id) ?? null) : null;
  });
  /** The open request as a one-element list: a new request gets new elements (its own disclosure, delay and comment). */
  protected readonly shown = computed(() => {
    const d = this.selected();
    return d ? [d] : [];
  });
  protected readonly detailState = computed<DetailState>(() => {
    const id = this.selectedId();
    if (!id) return 'none';
    if (this.details().has(id)) return 'ready';
    const error = this.detailErrors().get(id);
    if (error) return error.kind === 'not-found' ? 'not-found' : 'error';
    return 'loading';
  });
  protected readonly detailError = computed(() => {
    const id = this.selectedId();
    return id ? (this.detailErrors().get(id) ?? null) : null;
  });

  protected readonly channels = computed<ChannelStatus[]>(() => {
    const n = this.notifications();
    return n ? channelStatuses(n) : [];
  });
  protected readonly deciders = computed(() => decidersLine(this.notifications()));
  protected readonly delivery = computed<DeliveryCard | null>(() => {
    const d = this.selected();
    return d ? deliveryCard(d, this.notifications(), (at) => this.time.clock(at, true)) : null;
  });
  /** The desktop preview follows the OS the app runs on. */
  protected readonly os: 'mac' | 'win' = this.desktop.platform === 'win32' ? 'win' : 'mac';

  private readonly inflight = new Map<string, Promise<ApprovalDetail | null>>();
  /** Requests approved in this window whose run hasn't ended yet: if the gateway stops one, the person is told. */
  private readonly approvedHere = new Set<string>();
  private refreshRunning: Promise<void> | null = null;
  private refreshAgain = false;
  /** The request a decision is being sent for: its own `approval.resolved` isn't news to the person. */
  private deciding: string | null = null;
  private readonly timers = new Set<ReturnType<typeof setTimeout>>();

  constructor() {
    const destroy = inject(DestroyRef);

    // The URL names the request to open: Review, a deep link, the next one after a decision.
    effect(() => {
      const id = this.id() ?? null;
      untracked(() => {
        this.chosen.set(id);
        this.autoSelected.set(null);
        if (!id) return;
        this.decisionError.set(null);
        this.ensureDetail(id);
        this.focusTarget.set(id);
        if (this.tab() !== 'pending') this.showPending(false);
      });
    });

    // With no request in the URL the most urgent one opens (once: a new arrival doesn't pull the pane away).
    effect(() => {
      if (this.chosen() !== null || this.autoSelected() !== null || !this.loaded()) return;
      const next = mostUrgent(this.queue(), this.expiredHere(), untracked(() => this.time.now()));
      if (next) untracked(() => this.autoSelected.set(next.id));
    });

    // Every queued request's exact action: the queue's preview line is its first line, as the core cut it.
    effect(() => {
      const details = this.details();
      const errors = this.detailErrors();
      const missing = this.queue().filter((i) => !details.has(i.id) && !errors.has(i.id));
      untracked(() => missing.forEach((i) => this.ensureDetail(i.id)));
    });

    // The history rows' details (≤ 10): only the timeline tells "approved, then stopped by the gateway" from a
    // rejection. A detail older than the row's status (running → completed) is read again.
    effect(() => {
      const details = this.details();
      const errors = this.detailErrors();
      const stale = this.historyItems().filter((i) => !errors.has(i.id) && details.get(i.id)?.status !== i.status);
      untracked(() => stale.forEach((i) => this.ensureDetail(i.id, details.has(i.id))));
    });

    // `#ap-panel-history` (the kit's tab deep link) opens History; GwTabs selects the tab itself.
    effect(() => {
      const f = this.fragment();
      if (f === 'ap-panel-history' || f === 'ap-tab-history') untracked(() => this.tab.set('history'));
    });

    // Focus moves only once its target is on screen: a request's title, or the empty detail's.
    afterRenderEffect(() => {
      const target = this.focusTarget();
      if (!target) return;
      // The pane moved on before the target showed up (another request arrived, another tab): never steal focus later.
      const stale = target === 'empty' ? this.selectedId() !== null : this.selectedId() !== target;
      if (stale || this.tab() !== 'pending') {
        untracked(() => this.focusTarget.set(null));
        return;
      }
      const view = this.requestView();
      let focus: (() => void) | null = null;
      if (target === 'empty') {
        const el = this.emptyTitle()?.nativeElement;
        if (el) focus = () => el.focus();
      } else if (view && view.request().id === target) {
        focus = () => {
          view.focusTitle();
          // A deep link can name a request far down the queue: its row comes into view next to it.
          this.host.querySelector(`#apq-list [data-ap-item="${CSS.escape(target)}"]`)?.scrollIntoView?.({ block: 'nearest' });
        };
      } else if (this.detailState() === 'not-found') {
        const el = this.missingTitle()?.nativeElement;
        if (el) focus = () => el.focus();
      }
      if (!focus) return;
      focus();
      untracked(() => this.focusTarget.set(null));
    });

    const off = [
      this.events.on('approval.requested', (e) => {
        this.announcer.announce(`New approval request: ${e.tool} on ${e.connection ?? e.endpoint}`);
        void this.refresh();
      }),
      this.events.on('approval.resolved', (e) => {
        const known = this.details().get(e.id) ?? this.queue().find((i) => i.id === e.id);
        if (known && e.id === this.selectedId() && e.id !== this.deciding && !this.decidedHere().has(e.id)) {
          this.announcer.announce(resolvedElsewhere(known, e.status, e.via));
        }
        if (this.approvedHere.has(e.id)) void this.ensureDetail(e.id, true).then((d) => this.reportRun(e.id, d));
        else if (this.details().has(e.id)) void this.ensureDetail(e.id, true);
        void this.refresh();
      }),
      this.events.on('audit.call', (e) => {
        const approvalId = e.approvalId;
        if (!approvalId) return;
        const prev = this.calls().get(approvalId);
        if (prev && prev.eventId >= e.id) return;
        this.calls.update((m) => new Map(m).set(approvalId, { eventId: e.id, traceId: e.traceId ?? prev?.traceId ?? null }));
      }),
    ];
    // A stream that dropped and opened again may have missed events: read everything again.
    let reopened = untracked(() => this.events.reopened());
    effect(() => {
      const n = this.events.reopened();
      if (n !== reopened) {
        reopened = n;
        untracked(() => void this.refresh());
      }
    });
    destroy.onDestroy(() => {
      off.forEach((f) => f());
      this.timers.forEach((t) => clearTimeout(t));
    });

    void this.refresh();
    void this.loadChannels();
  }

  // ── Loading ────────────────────────────────────────────────────────────────────────────────────────────────

  /** Reads the queue, the history and the counts again; calls that arrive meanwhile run once more after it. */
  refresh(): Promise<void> {
    if (this.refreshRunning) {
      this.refreshAgain = true;
      return this.refreshRunning;
    }
    this.refreshRunning = (async () => {
      do {
        this.refreshAgain = false;
        await this.load();
      } while (this.refreshAgain);
    })().finally(() => (this.refreshRunning = null));
    return this.refreshRunning;
  }

  private async load(): Promise<void> {
    try {
      const [pending, history] = await Promise.all([this.api.list('pending'), this.api.list('history', null, HISTORY_PAGE)]);
      const ids = new Set(pending.items.map((i) => i.id));
      this.serverQueue.set(pending.items);
      this.history.set(history.items);
      this.counts.set(pending.counts);
      this.loadError.set(null);
      // What happened here is in the core's answer now: forget it.
      this.expiredHere.update((s) => new Set([...s].filter((id) => ids.has(id))));
      this.decidedHere.update((s) => new Set([...s].filter((id) => ids.has(id))));
      void this.loadCalls();
    } catch (e) {
      this.loadError.set(asApiError(e));
    }
  }

  /** Links requests to their call in Activity (best effort: without it the history links Activity by filter). */
  private async loadCalls(): Promise<void> {
    try {
      const page = await this.activity.events({ q: ACTIVITY_INDEX_QUERY, limit: ACTIVITY_PAGE });
      const index = callIndex(page.events);
      // Keep what live events added since: they can be newer than this page.
      this.calls.update((current) => {
        const merged = new Map(index);
        for (const [id, ref] of current) {
          const listed = merged.get(id);
          if (!listed || listed.eventId < ref.eventId) merged.set(id, ref);
        }
        return merged;
      });
    } catch {
      // Activity unavailable: history rows link Activity by filter, the request has no Trace row.
    }
  }

  private async loadChannels(): Promise<void> {
    try {
      this.notifications.set(await this.notificationsApi.get());
      this.channelsState.set('ready');
    } catch (e) {
      this.channelsState.set(isApiError(e) && e.kind === 'not-available' ? 'not-available' : 'error');
    }
  }

  /** Loads a request's detail once (or again, `force`, after it changed); resolves with it (null when it failed). */
  private ensureDetail(id: string, force = false): Promise<ApprovalDetail | null> {
    const running = this.inflight.get(id);
    if (running) return running;
    const known = this.details().get(id);
    if (known && !force) return Promise.resolve(known);
    const load = this.api
      .get(id)
      .then(
        (d) => {
          this.details.update((m) => new Map(m).set(id, d));
          this.detailErrors.update((m) => without(m, id));
          return d;
        },
        (e) => {
          this.detailErrors.update((m) => new Map(m).set(id, asApiError(e)));
          return null;
        },
      )
      .finally(() => this.inflight.delete(id));
    this.inflight.set(id, load);
    return load;
  }

  protected retryLoad(): void {
    void this.refresh();
    if (this.channelsState() === 'error') void this.loadChannels();
  }

  protected retryDetail(): void {
    const id = this.selectedId();
    if (!id) return;
    this.detailErrors.update((m) => without(m, id));
    this.ensureDetail(id, true);
  }

  // ── Tabs ───────────────────────────────────────────────────────────────────────────────────────────────────

  protected onTab(tabId: string): void {
    this.tab.set(tabId === 'ap-tab-history' ? 'history' : 'pending');
  }

  /** "Back to pending requests": the Pending tab, focused (kit `data-ap-back`). */
  protected showPending(focus = true): void {
    this.tab.set('pending');
    this.tabs()?.selectById('ap-tab-pending', focus);
  }

  // ── The queue ──────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Review on the request the URL already names: the router has nothing to do, but the kit focuses the request's
   * title then too. (Review on any other request changes the URL, and the URL focuses it.)
   */
  protected onQueueClick(e: MouseEvent): void {
    const link = e.target instanceof Element ? e.target.closest('.gw-approval-request__end a') : null;
    const id = link?.closest<HTMLElement>('[data-ap-item]')?.dataset['apItem'];
    if (id && id === this.chosen()) this.focusTarget.set(id);
  }

  /** A countdown reached zero here: the request no longer counts as waiting; the core expires it on its timer. */
  protected onExpired(id: string): void {
    if (this.expiredHere().has(id)) return;
    this.expiredHere.update((s) => new Set(s).add(id));
    const t = setTimeout(() => {
      this.timers.delete(t);
      void this.refresh();
    }, EXPIRY_RECHECK_MS);
    this.timers.add(t);
  }

  protected relative(at: string): string {
    return this.time.relative(at);
  }

  protected absolute(at: string): string {
    return this.time.absolute(at);
  }

  protected iso(at: string): string {
    return this.time.iso(at);
  }

  // ── The open request ──────────────────────────────────────────────────────────────────────────────────────

  protected busyFor(id: string): 'approve' | 'deny' | null {
    const b = this.busy();
    return b && b.id === id ? b.decision : null;
  }

  protected errorFor(id: string): string | null {
    const e = this.decisionError();
    return e && e.id === id ? e.text : null;
  }

  protected traceFor(id: string): string | null {
    return this.allCalls().get(id)?.traceId ?? null;
  }

  /** The request's call in Activity (its drawer), for "View event" on executed and failed requests. */
  protected eventLink(id: string): UrlTree | null {
    const call = this.allCalls().get(id);
    return call ? this.router.createUrlTree(['/activity'], { fragment: `event-${call.eventId}` }) : null;
  }

  /**
   * Sends the person's decision (the element already checked the comment, the 700 ms delay and attached the digest).
   * Done: the request leaves the queue and the next most urgent one opens. 409 (decided elsewhere, expired): the
   * request shows what happened. 422 (a comment, a changed action): the reason stays next to the buttons.
   */
  protected async decide(r: ApprovalDetail, d: ApprovalDecision): Promise<void> {
    if (this.busy()) return;
    this.busy.set({ id: r.id, decision: d.decision });
    this.decisionError.set(null);
    this.deciding = r.id;
    try {
      const result = await this.api.decide(r.id, d);
      this.toasts.show(decisionToast(r, d.decision));
      this.decidedHere.update((s) => new Set(s).add(r.id));
      // The gateway checks again before it runs an approved call and may stop it (a revoked token, a changed policy):
      // the end of the run is reported by `approval.resolved`, or by this read if that event came first.
      if (d.decision === 'approve' && (result.status === 'running' || result.status === 'pending')) this.approvedHere.add(r.id);
      void this.ensureDetail(r.id, true).then((detail) => this.reportRun(r.id, detail));
      this.next();
      void this.refresh();
    } catch (e) {
      const error = asApiError(e);
      if (error.kind === 'conflict') {
        this.toasts.show({ tone: 'warning', title: error.title, body: error.detail });
        this.ensureDetail(r.id, true);
        void this.refresh();
      } else {
        this.decisionError.set({ id: r.id, text: error.kind === 'unprocessable' ? error.detail : `${error.title}. ${error.detail}` });
        // A changed action (digest) or policy: show what is there now; the comment stays (same request).
        if (error.kind === 'unprocessable') this.ensureDetail(r.id, true);
      }
    } finally {
      this.busy.set(null);
      this.deciding = null;
    }
  }

  /** An approved request's run ended: stopped by the gateway or failed is said in a toast (completed was promised). */
  private reportRun(id: string, d: ApprovalDetail | null): void {
    if (!d || !this.approvedHere.has(id) || d.status === 'pending' || d.status === 'running') return;
    this.approvedHere.delete(id);
    const toast = afterApprovalToast(d);
    if (toast) this.toasts.show(toast);
  }

  /** After a decision here: the next most urgent request opens, focused; none left — the empty detail, focused. */
  private next(): void {
    const remaining = this.waiting();
    const next = mostUrgent(remaining, this.expiredHere(), this.time.now());
    this.announcer.announce(stillWaiting(remaining.length));
    this.autoSelected.set(null);
    if (next) {
      void this.router.navigate(['/approvals', next.id], { replaceUrl: true });
      return;
    }
    this.chosen.set(null);
    this.focusTarget.set('empty');
    if (this.id()) void this.router.navigate(['/approvals'], { replaceUrl: true });
  }
}

function asApiError(e: unknown): ApiError {
  return isApiError(e) ? e : new ApiError('server', 0, { detail: e instanceof Error ? e.message : String(e) });
}

function without<K, V>(m: ReadonlyMap<K, V>, key: K): ReadonlyMap<K, V> {
  if (!m.has(key)) return m;
  const copy = new Map(m);
  copy.delete(key);
  return copy;
}
