import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
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
import { NgTemplateOutlet } from '@angular/common';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ActivityEvent, EventDetail, TraceDetail, TraceRow } from '../../core/api/activity.api';
import { isApiError } from '../../core/api/api-error';
import { ApprovalDetail } from '../../core/api/approvals.api';
import { TokenRow } from '../../core/api/endpoints.api';
import { DesktopBridge, desktopErrorText } from '../../core/desktop/desktop-bridge';
import { CoreStore } from '../../core/stores/core.store';
import { formatCount } from '../../core/time/time-format';
import { TimeService } from '../../core/time/time.service';
import { GwCodeBlock } from '../../code/code-block';
import { Announcer } from '../../kit/announcer';
import { GwDialog, GwDialogClose } from '../../kit/dialog';
import { GwToggle } from '../../kit/disclosure';
import { GwEventDrawer } from '../../kit/elements/event-detail/event-drawer';
import { GwTraceView } from '../../kit/elements/event-detail/trace-view';
import { GwLogExplorer } from '../../kit/elements/log-explorer/log-explorer';
import { LogRange, rangeBounds } from '../../kit/elements/log-explorer/log-view';
import { GwClientMark } from '../../kit/elements/shared/client-mark';
import { GwIcon } from '../../kit/icons';
import { GwMenu, GwMenuTrigger, MenuPick } from '../../kit/menu';
import { GwTabs } from '../../kit/tabs';
import { GwSortRow, SortChange } from '../../kit/table';
import { safeStorage } from '../../kit/storage';
import { ToastService } from '../../kit/toast.service';
import { ActivityData } from './activity.data';
import {
  ActivityTab,
  NEWEST_FIRST,
  RevokeWords,
  Sort,
  backToRange,
  captionText,
  changeRow,
  changesPagerText,
  changesSince,
  clientView,
  decisionOf,
  deniedWaiting,
  drawerDescription,
  durationCell,
  emptyState,
  eventFromFragment,
  exportNote,
  exportedToast,
  insightsRangeParam,
  pagerText,
  parseRange,
  parseView,
  rangeParam,
  rangeWords,
  retentionNote,
  revokeWords,
  sortRows,
  tracesInRange,
  tracesPagerText,
  unsendable,
  viewParam,
  widerRange,
} from './activity-view';

/** The first render waits this long before it shows placeholders (kit: "when it takes longer than 300 ms"). */
export const SKELETON_DELAY_MS = 300;
/** Tasks shown at first and added by each "Load older tasks". */
export const TRACE_PAGE = 20;
const DAY_MS = 86_400_000;
const REFRESH_STORAGE_KEY = 'gw-activity-refresh-seconds';
const REFRESH_OPTIONS = [
  { seconds: 0, label: 'Off', short: 'Off' },
  { seconds: 5, label: '5 seconds', short: '5 s' },
  { seconds: 10, label: '10 seconds', short: '10 s' },
  { seconds: 30, label: '30 seconds', short: '30 s' },
  { seconds: 60, label: '1 minute', short: '1 min' },
  { seconds: 300, label: '5 minutes', short: '5 min' },
] as const;

function savedRefreshSeconds(): number {
  const stored = safeStorage.get(REFRESH_STORAGE_KEY);
  return REFRESH_OPTIONS.find((option) => String(option.seconds) === stored)?.seconds ?? 30;
}

const NO_ANSWER = 'The core didn’t answer. Try again.';

/**
 * Activity (design/pages/activity.html; ARCHITECTURE.md §6 activity, §8.4 "activity"): every call an agent made
 * through the gateway, and every change made to its setup. Append-only.
 *
 * - Header: Refresh, a remembered auto-refresh interval, View in Insights (with the query) and Export — a CSV or
 *   JSONL file through the desktop's save dialog (the core writes it; outside the desktop app the items say why they
 *   are unavailable) or Send to SIEM… (a destination
 *   of Settings › Audit and retention; before step K5 the dialog says it isn't available yet).
 * - Agent calls: the log-explorer (query → tags, range, Live, saved views, facets, histogram by decision; a bar zooms
 *   into its hour) over Events and Traces.
 *   - Events: Time · Decision · Tool·connection · Client·endpoint · Duration, no sideways scroll at 1440 × 900; a
 *     waiting call has Review; Time and Duration sort; Load older calls. Each row opens the event-detail drawer at
 *     `#event-<id>` (also a deep link): Copy link, Open trace, the approval, and Revoke of the access that made it.
 *   - Traces: the agents' tasks (title unverified, In progress / waiting for you / ended, phases), Load older tasks.
 *   - Empty: an hour without calls, a query nothing matches, a range without calls, and the first run (no call has
 *     ever reached the gateway).
 * - Setup changes: Time | What | Change | By (You) | Reason for the last day, Load older changes.
 * - States: nothing for 300 ms, then placeholders; an error with Try again and Details; "may be out of date" when a
 *   reload fails; a query the core can't read; an event link that isn't in the log.
 */
@Component({
  selector: 'gw-activity-page',
  imports: [
    NgTemplateOutlet,
    RouterLink,
    GwIcon,
    GwMenu,
    GwMenuTrigger,
    GwTabs,
    GwSortRow,
    GwDialog,
    GwDialogClose,
    GwToggle,
    GwCodeBlock,
    GwClientMark,
    GwLogExplorer,
    GwEventDrawer,
    GwTraceView,
  ],
  providers: [ActivityData],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page gw-page--full' },
  templateUrl: './activity.page.html',
})
export class ActivityPage implements OnInit {
  protected readonly data = inject(ActivityData);
  protected readonly time = inject(TimeService);
  protected readonly desktop = inject(DesktopBridge);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly core = inject(CoreStore);
  private readonly toasts = inject(ToastService);
  private readonly announcer = inject(Announcer);
  private readonly injector = inject(Injector);
  private readonly host: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;

  /** `?q=` (withComponentInputBinding): links from every other page ("View activity" → `connection:dev-db`). */
  readonly q = input<string | null>(null);
  /** `?view=traces|changes`. */
  readonly view = input<string | null>(null);
  /**
   * `?range=15m|1h|24h|7d|30d` (§8.1): Insights' "View in Activity" links carry their page's range, so the calls listed
   * are the ones counted there; the page writes its own relative range back (`replaceUrl`), like `q`.
   */
  readonly range = input<string | null>(null);
  /** The range "View in Insights" carries over. */
  protected readonly insightsRange = computed(() => insightsRangeParam(this.data.range()));

  private readonly fragment = toSignal(this.route.fragment, { initialValue: null });
  private readonly topTabs = viewChild<GwTabs>('topTabs');
  private readonly callTabs = viewChild<GwTabs>('callTabs');
  private readonly drawer = viewChild.required<GwEventDrawer>('drawer');
  private readonly revokeDialog = viewChild.required<GwDialog>('revokeDialog');
  private readonly siemDialog = viewChild.required<GwDialog>('siemDialog');
  private readonly saveDialog = viewChild.required<GwDialog>('saveDialog');

  protected readonly formatCount = formatCount;
  protected readonly decision = decisionOf;

  /** The header's tab (Agent calls, Setup changes) and the calls' own (Events, Traces): each keeps its choice. */
  private readonly top = signal<'calls' | 'changes'>('calls');
  private readonly calls = signal<'events' | 'traces'>('events');
  protected readonly tab = computed<ActivityTab>(() => (this.top() === 'changes' ? 'changes' : this.calls()));
  protected readonly topTab = computed(() => (this.top() === 'changes' ? 'ac-tab-changes' : 'ac-tab-calls'));
  protected readonly callTab = computed(() => (this.calls() === 'traces' ? 'ac-tab-traces' : 'ac-tab-events'));

  /** Facets open from lg (1280 px) and folded below, where they would stack above the table; the toggle remembers. */
  protected readonly facetsOpen = signal(typeof window === 'undefined' || !window.matchMedia || window.matchMedia('(min-width: 1280px)').matches);

  protected readonly slow = signal(false);
  protected readonly sort = signal<Sort>(NEWEST_FIRST);
  protected readonly filtered = computed(() => this.data.query().trim().length > 0);
  protected readonly refreshOptions = REFRESH_OPTIONS;
  protected readonly refreshSeconds = signal(savedRefreshSeconds());
  protected readonly refreshLabel = computed(() => REFRESH_OPTIONS.find((option) => option.seconds === this.refreshSeconds())!.short);

  // ── Agent calls ─────────────────────────────────────────────────────────────────────────────────────────────────

  protected readonly words = computed(() => rangeWords(this.data.range(), this.data.zoomBucket(), this.time.minute()));
  protected readonly rowsShown = computed(() => sortRows(this.data.rows() ?? [], this.sort(), this.time.now()));
  protected readonly caption = computed(() => captionText(this.words(), this.data.query().trim()));
  protected readonly pager = computed(() => pagerText(this.data.rows()?.length ?? 0, this.data.matched(), this.filtered(), this.words()));
  protected readonly empty = computed(() => {
    const range = this.data.range();
    return emptyState({
      q: this.data.query().trim(),
      words: this.words(),
      zoom: this.data.zoomBucket(),
      back: backToRange(range, this.time.minute()),
      widest: widerRange(range) === null,
    });
  });
  protected readonly widerLabel = computed(() => {
    const w = widerRange(this.data.range());
    return w ? backToRange(w, this.time.minute()) : null;
  });
  protected readonly exportNote = computed(() => exportNote(this.data.matched(), this.desktop.available));
  /** Send to SIEM's scope: "of the last 24 hours", "between 10:00 and 11:00". */
  protected readonly siemScope = computed(() => this.words().replace(/^in the /, 'of the '));
  /** "Send 1,287 calls" (one text, so the button's gap doesn't split the number from its words). */
  protected readonly siemButton = computed(() => {
    const n = this.data.matched();
    return n == null ? 'Send calls' : `Send ${formatCount(n)} ${n === 1 ? 'call' : 'calls'}`;
  });
  protected readonly customNote = computed(() => retentionNote(this.data.retentionYears()));
  /** "Append-only, kept 365 days." — the retention of Settings › Audit and retention (at least a year). */
  protected readonly kept = computed(() => {
    const years = this.data.retentionYears() ?? 1;
    return years === 1 ? '365 days' : `${years} years`;
  });

  // ── Traces ──────────────────────────────────────────────────────────────────────────────────────────────────────

  private readonly tracesShownCount = signal(TRACE_PAGE);
  protected readonly tracesInRange = computed(() => {
    const value = this.data.traces().value;
    if (!value) return null;
    const b = rangeBounds(this.data.range(), this.time.minute());
    return tracesInRange(value, b.from, this.data.range().kind === 'custom' ? b.to : null);
  });
  protected readonly tracesShown = computed(() => (this.tracesInRange() ?? []).slice(0, this.tracesShownCount()));
  protected readonly tracesMore = computed(() => (this.tracesInRange()?.length ?? 0) > this.tracesShownCount() || !this.data.tracesComplete());
  protected readonly tracesPager = computed(() => {
    const all = this.tracesInRange() ?? [];
    return tracesPagerText(this.tracesShown().length, all.length, this.filtered(), rangeWords(this.data.range(), null, this.time.minute()));
  });
  protected readonly tracesLoading = signal(false);

  // ── Setup changes ───────────────────────────────────────────────────────────────────────────────────────────────

  protected readonly changesOlder = signal(false);
  protected readonly changesInDay = computed(() => {
    const list = this.data.changes().value;
    return list ? changesSince(list, this.time.minute() - DAY_MS) : null;
  });
  protected readonly changeRows = computed(() => {
    const list = this.changesOlder() ? this.data.changes().value : this.changesInDay();
    return (list ?? []).map(changeRow);
  });
  protected readonly changesMore = computed(() => {
    const all = this.data.changes().value ?? [];
    return !this.data.changesComplete() || (!this.changesOlder() && all.length > (this.changesInDay()?.length ?? 0));
  });
  protected readonly changesPager = computed(() => changesPagerText(this.changeRows().length, this.changesInDay()?.length ?? 0, this.changesOlder()));
  protected readonly changesLoading = signal(false);

  // ── The drawer ──────────────────────────────────────────────────────────────────────────────────────────────────

  /** The call the drawer shows, and its row's id (the row link carries `aria-current`). */
  protected readonly detail = signal<EventDetail | null>(null);
  protected readonly openId = signal<number | null>(null);
  protected readonly detailApproval = signal<ApprovalDetail | null>(null);
  protected readonly detailDescription = signal<string | null>(null);
  protected readonly detailTraced = signal<boolean | null>(null);
  /** A link to a call that isn't in the audit log (kit `#ac-missing`). */
  protected readonly missing = signal<number | null>(null);
  private openSeq = 0;

  // ── Revoke ──────────────────────────────────────────────────────────────────────────────────────────────────────

  protected readonly revokeTarget = signal<TokenRow | null>(null);
  protected readonly revoking = signal<{ readonly target: TokenRow; readonly words: RevokeWords; readonly event: ActivityEvent } | null>(null);
  protected readonly revokeBusy = signal(false);
  protected readonly revokeError = signal<string | null>(null);

  // ── Send to SIEM, Save view ─────────────────────────────────────────────────────────────────────────────────────

  protected readonly siemDestination = signal<string>('');
  protected readonly siemBusy = signal(false);
  protected readonly siemError = signal<string | null>(null);
  protected readonly unsendable = unsendable;
  /** Destinations exist, but none can take calls now (each says why in the list). */
  protected readonly noneSendable = computed(() => {
    const list = this.data.destinations().value ?? [];
    return list.length > 0 && list.every((d) => unsendable(d) !== null);
  });
  protected readonly saveName = signal('');
  protected readonly saveBusy = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly saveTried = signal(false);
  protected readonly saveReplaces = computed(() => {
    const name = this.saveName().trim().toLowerCase();
    return !!name && this.data.views().some((v) => v.name.toLowerCase() === name);
  });

  /** After "Show N calls" of a task, the keyboard moves to its calls once they are listed. */
  private focusCallsOnLoad = false;
  private started = false;

  constructor() {
    // Placeholders only when the first answer takes longer than 300 ms (each part shows them while it has nothing).
    const timer = setTimeout(() => this.slow.set(true), SKELETON_DELAY_MS);
    inject(DestroyRef).onDestroy(() => clearTimeout(timer));

    // Wait for each refresh to finish before scheduling another; stop polling when the page is destroyed.
    effect((onCleanup) => {
      const seconds = this.refreshSeconds();
      const running = this.core.running();
      if (!seconds || !running) return;
      let cancelled = false;
      let refreshTimer: ReturnType<typeof setTimeout>;
      const schedule = () => {
        if (!cancelled) refreshTimer = setTimeout(() => void poll(), seconds * 1_000);
      };
      const poll = async () => {
        try {
          if (document.visibilityState !== 'hidden' && !this.data.busy()) await this.data.refresh('head');
        } finally {
          schedule();
        }
      };
      schedule();
      onCleanup(() => {
        cancelled = true;
        clearTimeout(refreshTimer);
      });
    });

    // A link with a query while the page is open (a trace's Show N calls, the palette, another page).
    effect(() => {
      const q = (this.q() ?? '').trim();
      untracked(() => {
        if (this.started && q !== this.data.query()) this.applyQuery(q);
      });
    });
    // A link with a range while the page is open (Insights in another tab of history, Back).
    effect(() => {
      const r = parseRange(this.range());
      untracked(() => {
        if (this.started && r && rangeParam(r) !== rangeParam(this.data.range())) this.applyRange(r);
      });
    });
    // `?view=` (Open trace in the drawer, a link to Setup changes, Back).
    effect(() => {
      const tab = parseView(this.view());
      untracked(() => this.showTab(tab));
    });
    // `#event-<id>`: a row, a deep link, Approvals or a task's phase opens the drawer; leaving the fragment closes it.
    effect(() => {
      const id = eventFromFragment(this.fragment());
      untracked(() => {
        if (id !== null) {
          if (id !== this.openId() || !this.drawer().isOpen) void this.openEvent(id);
        } else if (this.drawer().isOpen) {
          this.drawer().close();
        }
      });
    });
    // The tasks on screen get their phases once the Traces tab shows them.
    effect(() => {
      if (this.tab() !== 'traces') return;
      const shown = this.tracesShown();
      untracked(() => {
        for (const t of shown) if (!this.data.traceDetails().has(t.traceId)) void this.data.loadTraceDetail(t.traceId).catch(() => undefined);
      });
    });
    // After a task's Show N calls: focus its calls once they are listed.
    effect(() => {
      const rows = this.data.rows();
      if (!rows || !this.focusCallsOnLoad || this.data.refreshing()) return;
      this.focusCallsOnLoad = false;
      this.focusAfterRender(() => this.host.querySelector<HTMLElement>('#ac-table-wrap') ?? this.host.querySelector<HTMLElement>('#ac-panel-events'));
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

  /** The first load asks for the address's query and range at once (inputs are bound by now; the constructor is too early). */
  ngOnInit(): void {
    this.data.query.set((this.q() ?? '').trim());
    const range = parseRange(this.range());
    if (range) this.data.range.set(range);
    this.started = true;
    void this.data.start();
  }

  // ── Query, range, zoom, Live ────────────────────────────────────────────────────────────────────────────────────

  /** The query changed in the explorer (a tag, a facet, Clear all, a saved view): the address follows. */
  protected onQuery(q: string): void {
    const next = q.trim();
    if (next === this.data.query()) return;
    this.applyQuery(next);
    void this.router.navigate([], { queryParams: { q: next || null }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  private applyQuery(q: string): void {
    this.data.query.set(q);
    this.tracesShownCount.set(TRACE_PAGE);
    void this.data.loadCalls();
    void this.data.loadTraces();
  }

  /** The range changed in the explorer: the address follows (`?range=`, nothing for the default or a custom range). */
  protected onRange(r: LogRange): void {
    this.applyRange(r);
    const range = rangeParam(r);
    if (range !== (this.range() || null)) void this.router.navigate([], { queryParams: { range }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  private applyRange(r: LogRange): void {
    this.data.range.set(r);
    this.data.zoom.set(null);
    this.tracesShownCount.set(TRACE_PAGE);
    void this.data.loadCalls();
    void this.data.loadTraces();
  }

  protected onZoom(z: string | null): void {
    if (z === this.data.zoom()) return;
    this.data.zoom.set(z);
    void this.data.loadCalls('zoom');
  }

  /** Live ends the range at now: a custom range goes back to the last 24 hours, a zoom back to the whole range. */
  protected onLive(on: boolean): void {
    this.data.live.set(on);
    if (!on) return;
    if (this.data.range().kind === 'custom') this.data.range.set({ kind: 'relative', key: '24h' });
    this.data.zoom.set(null);
    void this.data.loadCalls();
  }

  protected clearFilters(): void {
    this.onQuery('');
    this.focusAfterRender(() => this.host.querySelector<HTMLElement>('.gw-log-explorer__search input'));
  }

  /** "Show the last 24 hours" after a zoomed hour without calls. */
  protected unzoom(): void {
    this.onZoom(null);
    this.focusAfterRender(() => this.host.querySelector<HTMLElement>('#ac-table-wrap') ?? this.host.querySelector<HTMLElement>('.gw-chart__bar'));
  }

  protected widen(): void {
    const w = widerRange(this.data.range());
    if (w) this.onRange(w);
  }

  protected retry(): void {
    if (!this.data.busy()) void this.data.refresh();
  }

  protected refresh(): void {
    if (!this.data.busy()) void this.data.refresh('head');
  }

  protected onRefreshInterval(pick: MenuPick): void {
    const seconds = Number(pick.value);
    if (!REFRESH_OPTIONS.some((option) => option.seconds === seconds)) return;
    this.refreshSeconds.set(seconds);
    safeStorage.set(REFRESH_STORAGE_KEY, String(seconds));
  }

  // ── Tabs ────────────────────────────────────────────────────────────────────────────────────────────────────────

  /** A tab picked by hand: the address follows (`?view=traces`, `?view=changes`, none for Events). */
  protected onTopTab(id: string): void {
    this.top.set(id === 'ac-tab-changes' ? 'changes' : 'calls');
    this.syncView();
  }

  protected onCallTab(id: string): void {
    this.calls.set(id === 'ac-tab-traces' ? 'traces' : 'events');
    this.syncView();
  }

  /** Selects a view's tabs (the inner one keeps its choice when Setup changes is shown). */
  private showTab(tab: ActivityTab): void {
    if (tab === 'changes') {
      this.top.set('changes');
    } else {
      this.top.set('calls');
      this.calls.set(tab);
    }
    this.topTabs()?.selectById(this.topTab());
    this.callTabs()?.selectById(this.callTab());
  }

  private syncView(): void {
    const view = viewParam(this.tab());
    if (view === (this.view() || null)) return;
    void this.router.navigate([], { queryParams: { view }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  // ── Events ──────────────────────────────────────────────────────────────────────────────────────────────────────

  protected onSort(s: SortChange): void {
    this.sort.set({ key: s.key === 'duration' ? 'duration' : 'time', direction: s.direction });
  }

  protected async loadOlder(): Promise<void> {
    const before = this.data.rows()?.length ?? 0;
    try {
      await this.data.loadOlder();
      const added = (this.data.rows()?.length ?? 0) - before;
      if (added > 0) this.announcer.announce(`${formatCount(added)} older ${added === 1 ? 'call' : 'calls'} loaded`);
    } catch (e) {
      this.toasts.show({ tone: 'danger', title: "Older calls didn't load", body: this.errorText(e, NO_ANSWER) });
    }
  }

  protected durationText(e: ActivityEvent): string {
    return durationCell(e, this.time.now());
  }

  protected client(e: ActivityEvent) {
    return clientView(e.client);
  }

  // ── Traces ──────────────────────────────────────────────────────────────────────────────────────────────────────

  /** A task's card: its phases once loaded, its row until then (header, status and decisions show at once). */
  protected traceOf(t: TraceRow): TraceDetail {
    return this.data.traceDetails().get(t.traceId) ?? { trace: t, phases: [], calls: [] };
  }

  protected onShowCalls(): void {
    this.focusCallsOnLoad = true;
  }

  protected async loadOlderTasks(): Promise<void> {
    const before = this.tracesShown().length;
    this.tracesLoading.set(true);
    try {
      if ((this.tracesInRange()?.length ?? 0) <= this.tracesShownCount() && !this.data.tracesComplete()) await this.data.loadMoreTraces();
      this.tracesShownCount.update((n) => n + TRACE_PAGE);
      const next = this.tracesShown()[before];
      if (next) this.focusAfterRender(() => this.host.querySelector<HTMLElement>(`[data-trace="${next.traceId}"] .gw-text-h3`));
    } catch (e) {
      this.toasts.show({ tone: 'danger', title: "Older tasks didn't load", body: this.errorText(e, NO_ANSWER) });
    } finally {
      this.tracesLoading.set(false);
    }
  }

  // ── Setup changes ───────────────────────────────────────────────────────────────────────────────────────────────

  protected async loadOlderChanges(): Promise<void> {
    this.changesLoading.set(true);
    try {
      if (this.changesOlder() || this.data.changes().value?.length === this.changesInDay()?.length) await this.data.loadOlderChanges();
      this.changesOlder.set(true);
    } catch (e) {
      this.toasts.show({ tone: 'danger', title: "Older changes didn't load", body: this.errorText(e, NO_ANSWER) });
    } finally {
      this.changesLoading.set(false);
    }
  }

  // ── The drawer ──────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Opens one call (its audit rows), then fills in what it needs from elsewhere: its task (the description and Open
   * trace), the approval (comment, notifications) and the access that made it (Revoke).
   */
  private async openEvent(id: number): Promise<void> {
    const seq = ++this.openSeq;
    const doc = this.host.ownerDocument;
    const opener = doc.activeElement instanceof HTMLElement && doc.activeElement !== doc.body ? doc.activeElement : null;
    this.missing.set(null);
    let d: EventDetail;
    try {
      d = await this.data.event(id);
    } catch (e) {
      if (seq !== this.openSeq) return;
      if (isApiError(e) && (e.kind === 'not-found' || e.kind === 'not-available')) {
        // The note sits above the calls: Events is shown, and one navigation drops the fragment and the view.
        this.missing.set(id);
        this.showTab('events');
        this.announcer.announce(`Event ${id} is not in the audit log`);
        this.dropFragment({ view: null });
      } else {
        this.toasts.show({ tone: 'danger', title: "The call didn't load", body: this.errorText(e, NO_ANSWER) });
        this.dropFragment();
      }
      return;
    }
    if (seq !== this.openSeq) return;
    this.detail.set(d);
    this.openId.set(id);
    this.detailApproval.set(null);
    this.detailTraced.set(d.event.traceId ? null : false);
    this.detailDescription.set(d.event.traceId ? null : drawerDescription(d, null));
    this.revokeTarget.set(null);
    if (!this.drawer().isOpen) this.drawer().open(opener);
    const [trace, approval, target] = await Promise.all([
      d.event.traceId ? this.data.loadTraceDetail(d.event.traceId).catch(() => null) : Promise.resolve(null),
      d.event.approvalId ? this.data.approval(d.event.approvalId) : Promise.resolve(null),
      this.data.revokeTargetOf(d.event),
    ]);
    if (seq !== this.openSeq) return;
    this.detailDescription.set(drawerDescription(d, trace));
    this.detailTraced.set(trace !== null);
    this.detailApproval.set(approval);
    this.revokeTarget.set(target);
  }

  /** The drawer closed (Esc, Close, the backdrop): the address drops `#event-<id>` and the row is no longer current. */
  protected onDrawerClosed(): void {
    this.openId.set(null);
    if (eventFromFragment(this.fragment()) !== null) this.dropFragment();
  }

  /** The address without `#event-<id>` (and with `params` changed). */
  private dropFragment(params: Readonly<Record<string, string | null>> = {}): void {
    void this.router.navigate([], { queryParams: params, queryParamsHandling: 'merge', replaceUrl: true });
  }

  // ── Revoke ──────────────────────────────────────────────────────────────────────────────────────────────────────

  protected askRevoke(opener: HTMLElement): void {
    const target = this.revokeTarget();
    const event = this.detail()?.event;
    if (!target || !event) return;
    const expires = target.expiresAt ? this.time.relative(target.expiresAt) : null;
    this.revoking.set({ target, event, words: revokeWords(target, event.endpoint, expires) });
    this.revokeError.set(null);
    this.revokeBusy.set(false);
    this.revokeDialog().openAfterRender(opener);
  }

  protected async confirmRevoke(): Promise<void> {
    const r = this.revoking();
    if (!r || this.revokeBusy()) return;
    this.revokeBusy.set(true);
    this.revokeError.set(null);
    try {
      const result = await this.data.revoke(r.target);
      this.revokeDialog().close('revoked');
      this.revokeTarget.set(null);
      this.toasts.show({ tone: 'success', title: r.words.toastTitle, body: r.words.toastBody + deniedWaiting(result.rejectedApprovals) });
    } catch (e) {
      this.revokeError.set(this.errorText(e, NO_ANSWER));
    } finally {
      this.revokeBusy.set(false);
    }
  }

  // ── Export ──────────────────────────────────────────────────────────────────────────────────────────────────────

  protected onExport(p: MenuPick): void {
    if (p.value === 'csv' || p.value === 'jsonl') void this.exportFile(p.value);
    else if (p.value === 'siem') this.openSiem();
  }

  /** CSV or JSONL: the desktop's save dialog picks the file, the core writes what the table lists into it. */
  private async exportFile(format: 'csv' | 'jsonl'): Promise<void> {
    if (!this.desktop.available) return;
    try {
      // Main may refuse the file (the core isn't ready, or it won't write there): that is said, not lost.
      const token = await this.desktop.saveFile('audit-export');
      if (!token) return; // the save dialog was cancelled: nothing happened
      const r = await this.data.exportToFile(format, token);
      const t = exportedToast(r);
      this.toasts.show({ tone: 'success', title: t.title, body: t.body });
    } catch (e) {
      this.toasts.show({ tone: 'danger', title: "The calls weren't exported", body: isApiError(e) ? this.errorText(e, NO_ANSWER) : desktopErrorText(e, NO_ANSWER) });
    }
  }

  /** The menu has already given focus back to Export, where it returns when the dialog closes. */
  private openSiem(): void {
    this.siemError.set(null);
    this.siemBusy.set(false);
    this.siemDestination.set('');
    void this.data.loadDestinations().then(() => {
      // The first one the core will take (paused, demo and changed-outside ones are listed, unavailable).
      const first = this.data.destinations().value?.find((d) => unsendable(d) === null);
      if (first && !this.siemDestination()) this.siemDestination.set(first.id);
    });
    this.siemDialog().openAfterRender();
  }

  protected async sendToSiem(e: Event): Promise<void> {
    e.preventDefault();
    const id = this.siemDestination();
    if (!id || this.siemBusy()) return;
    this.siemBusy.set(true);
    this.siemError.set(null);
    try {
      const r = await this.data.sendToSiem(id);
      this.siemDialog().close('sent');
      const name = this.data.destinations().value?.find((d) => d.id === id)?.name ?? 'the destination';
      this.toasts.show({ tone: 'success', title: `${formatCount(r.rows)} ${r.rows === 1 ? 'call' : 'calls'} sent`, body: `To ${name}. Masked values stay masked.` });
    } catch (err) {
      this.siemError.set(this.errorText(err, NO_ANSWER));
    } finally {
      this.siemBusy.set(false);
    }
  }

  // ── Save current view ───────────────────────────────────────────────────────────────────────────────────────────

  protected openSaveView(): void {
    this.saveName.set('');
    this.saveError.set(null);
    this.saveTried.set(false);
    this.saveBusy.set(false);
    this.saveDialog().openAfterRender();
  }

  protected async confirmSaveView(e: Event): Promise<void> {
    e.preventDefault();
    this.saveTried.set(true);
    const name = this.saveName().trim();
    if (!name || name.length > 64 || this.saveBusy()) {
      this.focusAfterRender(() => this.host.querySelector<HTMLElement>('#ac-save-name'));
      return;
    }
    this.saveBusy.set(true);
    this.saveError.set(null);
    try {
      await this.data.saveView({ name, q: this.data.query().trim(), view: this.tab() === 'traces' ? 'traces' : 'events' });
      this.saveDialog().close('saved');
      this.toasts.show({ tone: 'success', title: 'View saved', body: `${name} is under Saved views.` });
    } catch (err) {
      this.saveError.set(this.errorText(err, NO_ANSWER));
    } finally {
      this.saveBusy.set(false);
    }
  }

  protected saveNameError(): string | null {
    if (!this.saveTried()) return null;
    const name = this.saveName().trim();
    if (!name) return 'Name the view.';
    if (name.length > 64) return 'Use at most 64 characters.';
    return null;
  }

  // ── Errors and focus ────────────────────────────────────────────────────────────────────────────────────────────

  /** What a failed call says to do: the core's words when it sent them. */
  protected errorText(e: unknown, fallback = 'See the core log for details, then try again.'): string {
    return isApiError(e) ? e.detail : fallback;
  }

  protected errorTitle(e: unknown): string {
    return isApiError(e) ? e.title : 'The filter didn’t work';
  }

  /** The technical side of a failed load, for the code block under Details. */
  protected errorDetails(e: unknown): string {
    if (isApiError(e)) return `GET /api/activity/events → ${e.status || 'no answer'}\n${e.title}: ${e.detail}`;
    return e instanceof Error ? `${e.name}: ${e.message}` : String(e);
  }

  private focusAfterRender(target: () => HTMLElement | null | undefined): void {
    afterNextRender(() => target()?.focus(), { injector: this.injector });
  }
}
