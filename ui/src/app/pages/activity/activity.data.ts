import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import {
  ActivityApi,
  ActivityEvent,
  EventDetail,
  Facets,
  Histogram,
  SavedView,
  SetupChange,
  TraceDetail,
  TraceRow,
} from '../../core/api/activity.api';
import { isApiError, isNotAvailable } from '../../core/api/api-error';
import { ApprovalDetail, ApprovalsApi } from '../../core/api/approvals.api';
import { ConnectionsApi } from '../../core/api/connections.api';
import { TokenRow, TokensApi } from '../../core/api/endpoints.api';
import { AuditExportApi, AuditExportDestination, SettingsApi } from '../../core/api/settings.api';
import { Env } from '../../core/api/types';
import { EventStream } from '../../core/events/event-stream';
import { GatewayEvents } from '../../core/events/gateway-events';
import { TimeService } from '../../core/time/time.service';
import { LogRange, rangeBounds } from '../../kit/elements/log-explorer/log-view';
import {
  applyCall,
  envsOf,
  histogramTotal,
  histogramWaiting,
  matchedCount,
  mergeFacets,
  mergeHead,
  queryWithout,
  requestWindow,
  revokeTarget,
  shownViews,
  tagFields,
  withView,
} from './activity-view';

/** Calls per page of the events table ("Load older calls" asks for the next). */
export const PAGE_SIZE = 50;
/** Live reloads the newest calls at most this often, however many calls arrive. */
export const LIVE_THROTTLE_MS = 1_000;
/** Tasks and Setup changes are read in pages of this size until the range is covered. */
export const LIST_PAGE = 100;
/** At most this many pages of tasks or changes are read to cover a range ("Load older …" reads further). */
export const MAX_PAGES = 5;
const DAY_MS = 86_400_000;

/** A part of the page that loads on its own: its value, why its first load failed, whether this core has it. */
export interface Part<T> {
  readonly value: T | null;
  readonly error: unknown;
  readonly unavailable: boolean;
}

const EMPTY: Part<never> = { value: null, error: null, unavailable: false };

/**
 * The Activity page's data (ARCHITECTURE.md §6 activity, §8.4 "activity"):
 * - Agent calls: for the query and range, the histogram by decision (the whole range, also when an hour is zoomed),
 *   the range's calls without filters (the count line's "of 1,287", the waiting ones), the facets — a field the
 *   query filters on counts within the other filters, so its other values stay checkable — and the newest page of
 *   events of the effective range (the zoomed hour, else the range), then older pages on request;
 * - Live: `audit.call` reloads the newest page, the counts and the histogram (at most once a second) and merges the
 *   page over what is on screen; without Live a listed call is updated in place (a waiting call that was approved);
 * - Traces: the agents' tasks of the range (pages of the core's newest-first list, until the range start), each
 *   task's phases on demand;
 * - Setup changes: the last day's, older pages on request;
 * - saved views, the audit retention (custom range), the connections' environments (facets), export to a file and
 *   to a SIEM destination, the drawer's call with its task, approval and the access that made it, and Revoke.
 *
 * Reloads of the calls are ordered: an answer to an older question (the query or range changed meanwhile) is dropped.
 */
@Injectable()
export class ActivityData {
  private readonly api = inject(ActivityApi);
  private readonly approvalsApi = inject(ApprovalsApi);
  private readonly tokensApi = inject(TokensApi);
  private readonly connectionsApi = inject(ConnectionsApi);
  private readonly settingsApi = inject(SettingsApi);
  private readonly exportApi = inject(AuditExportApi);
  private readonly events = inject(EventStream);
  private readonly time = inject(TimeService);

  // ── What the page asks for ──────────────────────────────────────────────────────────────────────────────────────

  readonly query = signal('');
  readonly range = signal<LogRange>({ kind: 'relative', key: '24h' });
  /** The zoomed bar's start (ISO), or null. */
  readonly zoom = signal<string | null>(null);
  readonly live = signal(false);

  // ── Agent calls ─────────────────────────────────────────────────────────────────────────────────────────────────

  private readonly histogramSignal = signal<Histogram | null>(null);
  private readonly allSignal = signal<Histogram | null>(null);
  private readonly facetsSignal = signal<Facets | null>(null);
  private readonly rowsSignal = signal<ActivityEvent[] | null>(null);
  private readonly cursorSignal = signal<string | null>(null);
  private readonly firstRunSignal = signal(false);
  private readonly errorSignal = signal<unknown>(null);
  private readonly staleSignal = signal<unknown>(null);
  private readonly queryErrorSignal = signal<unknown>(null);
  private readonly refreshingSignal = signal(false);
  private readonly olderSignal = signal(false);

  /** The histogram of the range, for the query (null until loaded, or when the query isn't valid). */
  readonly histogram = this.histogramSignal.asReadonly();
  readonly facets = this.facetsSignal.asReadonly();
  /** The listed calls, newest first (null until the first answer). */
  readonly rows = this.rowsSignal.asReadonly();
  /** Whether older calls of the effective range exist ("Load older calls"). */
  readonly hasOlder = computed(() => this.cursorSignal() !== null);
  /** No call has ever reached the gateway (kit `#ac-first-run`). */
  readonly firstRun = this.firstRunSignal.asReadonly();
  /** Why the first load failed (nothing on screen). */
  readonly error = this.errorSignal.asReadonly();
  /** Why the last reload failed while older data is on screen. */
  readonly stale = this.staleSignal.asReadonly();
  /** The core refused the query (422 at `/q`): what it said. */
  readonly queryError = this.queryErrorSignal.asReadonly();
  readonly refreshing = this.refreshingSignal.asReadonly();
  readonly loadingOlder = this.olderSignal.asReadonly();

  readonly zoomBucket = computed(() => {
    const z = this.zoom();
    return z ? (this.histogramSignal()?.buckets.find((b) => b.from === z) ?? null) : null;
  });
  /** Calls of the effective range matching the query. */
  readonly matched = computed(() => matchedCount(this.histogramSignal(), this.zoomBucket()));
  /** Calls of the range without filters. */
  readonly total = computed(() => histogramTotal(this.allSignal() ?? (this.query() ? null : this.histogramSignal())));
  /** Calls of the range waiting for approval. */
  readonly waiting = computed(() => histogramWaiting(this.allSignal() ?? (this.query() ? null : this.histogramSignal())));
  /** Whether the calls have answered once (or failed): the page shows placeholders until then. */
  readonly loaded = computed(() => this.rowsSignal() !== null || this.errorSignal() !== null || this.queryErrorSignal() !== null);

  // ── Traces, Setup changes and the rest ──────────────────────────────────────────────────────────────────────────

  private readonly tracesSignal = signal<Part<TraceRow[]>>(EMPTY);
  private readonly tracesCompleteSignal = signal(true);
  private readonly traceDetailsSignal = signal<ReadonlyMap<string, TraceDetail | null>>(new Map());
  private readonly changesSignal = signal<Part<SetupChange[]>>(EMPTY);
  private readonly changesCompleteSignal = signal(true);
  private readonly viewsSignal = signal<{ readonly views: readonly SavedView[]; readonly version: number } | null>(null);
  private readonly retentionSignal = signal<number | null>(null);
  private readonly envsSignal = signal<Readonly<Record<string, Env>>>({});
  private readonly destinationsSignal = signal<Part<AuditExportDestination[]>>(EMPTY);

  readonly traces = this.tracesSignal.asReadonly();
  /** Whether the task list reaches back to the start of the range (else "Load older tasks" reads on). */
  readonly tracesComplete = this.tracesCompleteSignal.asReadonly();
  /** Each task's phases and calls, once asked for (null: the core has no such task). */
  readonly traceDetails = this.traceDetailsSignal.asReadonly();
  readonly changes = this.changesSignal.asReadonly();
  /** Whether every Setup change has been read (else "Load older changes" reads on). */
  readonly changesComplete = this.changesCompleteSignal.asReadonly();
  /** The views offered: the person's own, or the starting points until they save one. */
  readonly views = computed(() => shownViews(this.viewsSignal()?.views));
  readonly retentionYears = this.retentionSignal.asReadonly();
  readonly connectionEnvs = this.envsSignal.asReadonly();
  readonly destinations = this.destinationsSignal.asReadonly();

  private gen = 0;
  private tracesGen = 0;
  private stopped = false;
  private tracesCursor: string | null = null;
  private changesCursor: string | null = null;
  private liveTimer: ReturnType<typeof setTimeout> | null = null;
  private lastHead = 0;
  private readonly unsubscribe: (() => void)[] = [];

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stop());
  }

  start(): Promise<void> {
    this.unsubscribe.push(this.events.on('audit.call', (c) => this.onCall(c)));
    void this.loadViews();
    void this.loadSide();
    return this.refresh();
  }

  stop(): void {
    this.stopped = true;
    if (this.liveTimer) clearTimeout(this.liveTimer);
    this.unsubscribe.splice(0).forEach((u) => u());
  }

  /** Everything again (Try again, the core came back): the calls, the tasks and the changes. */
  refresh(): Promise<void> {
    void this.loadTraces();
    void this.loadChanges();
    return this.loadCalls('all');
  }

  // ── Agent calls ─────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Reads the calls for the current query and range. `zoom`: only the zoomed hour changed (the histogram stays);
   * `head`: Live, the newest page is merged over the listed rows instead of replacing them.
   */
  async loadCalls(kind: 'all' | 'zoom' | 'head' = 'all'): Promise<void> {
    const gen = ++this.gen;
    const q = this.query().trim();
    const range = this.range();
    const now = this.time.now();
    const whole = requestWindow(range, null, now);
    const zoom = this.zoomBucket();
    const win = requestWindow(range, zoom, now);
    if (kind !== 'head') this.refreshingSignal.set(true);
    try {
      const [histogram, all, facets, page] = await Promise.all([
        kind === 'zoom' ? Promise.resolve(this.histogramSignal()) : this.api.histogram({ q, ...whole }),
        kind === 'zoom' ? Promise.resolve(this.allSignal()) : q ? this.api.histogram(whole) : Promise.resolve(null),
        this.facetsFor(q, win),
        this.api.events({ q, ...win, limit: PAGE_SIZE }),
      ]);
      if (gen !== this.gen || this.stopped) return;
      const firstRun = !q && !zoom && kind !== 'zoom' && histogramTotal(histogram) === 0 ? await this.neverCalled() : false;
      if (gen !== this.gen || this.stopped) return;
      this.histogramSignal.set(histogram);
      this.allSignal.set(all);
      this.facetsSignal.set(facets);
      const rows = this.rowsSignal();
      if (kind === 'head' && rows) {
        const merged = mergeHead(page.events, rows);
        this.rowsSignal.set(merged.rows);
        if (merged.restarted) this.cursorSignal.set(page.nextCursor);
      } else {
        this.rowsSignal.set(page.events);
        this.cursorSignal.set(page.nextCursor);
      }
      this.firstRunSignal.set(firstRun);
      this.errorSignal.set(null);
      this.staleSignal.set(null);
      this.queryErrorSignal.set(null);
    } catch (e) {
      if (gen !== this.gen || this.stopped) return;
      if (isApiError(e) && e.kind === 'unprocessable' && (e.field === '/q' || e.field === null)) {
        // The query, not the core: nothing below matches what the tags say, so nothing is shown for it.
        this.queryErrorSignal.set(e);
        this.histogramSignal.set(null);
        this.facetsSignal.set(null);
        this.rowsSignal.set(null);
        this.cursorSignal.set(null);
        this.errorSignal.set(null);
        // The range without filters still counts ("0 of 9 calls", the Agent calls tab).
        const all = await this.api.histogram(whole).catch(() => null);
        if (gen === this.gen && !this.stopped) this.allSignal.set(all);
      } else if (this.rowsSignal() !== null) {
        this.staleSignal.set(e);
      } else {
        this.errorSignal.set(e);
      }
    } finally {
      if (gen === this.gen) this.refreshingSignal.set(false);
    }
  }

  /** "Load older calls": the next page of the effective range. */
  async loadOlder(): Promise<void> {
    const cursor = this.cursorSignal();
    if (!cursor || this.olderSignal()) return;
    const gen = this.gen;
    this.olderSignal.set(true);
    try {
      const page = await this.api.events({ q: this.query().trim(), ...requestWindow(this.range(), this.zoomBucket(), this.time.now()), cursor, limit: PAGE_SIZE });
      if (gen !== this.gen || this.stopped) return;
      const rows = this.rowsSignal() ?? [];
      const seen = new Set(rows.map((e) => e.callId));
      this.rowsSignal.set([...rows, ...page.events.filter((e) => !seen.has(e.callId))]);
      this.cursorSignal.set(page.nextCursor);
    } finally {
      this.olderSignal.set(false);
    }
  }

  /** The facets: one request for the query, one more for each field it filters on (counted without that field). */
  private async facetsFor(q: string, win: { readonly from: string; readonly to: string | null }): Promise<Facets> {
    const fields = tagFields(q);
    const [base, ...rest] = await Promise.all([this.api.facets({ q, ...win }), ...fields.map((f) => this.api.facets({ q: queryWithout(q, f), ...win }))]);
    return mergeFacets(base, Object.fromEntries(fields.map((f, i) => [f, rest[i]])));
  }

  /** Whether no call has ever reached the gateway (an empty range of a used gateway isn't a first run). */
  private async neverCalled(): Promise<boolean> {
    try {
      return (await this.api.events({ limit: 1 })).events.length === 0;
    } catch {
      return false;
    }
  }

  private onCall(c: GatewayEvents['audit.call']): void {
    const rows = this.rowsSignal();
    const updated = rows ? applyCall(rows, c) : null;
    if (updated) this.rowsSignal.set(updated);
    if (this.live()) this.scheduleHead();
  }

  /** Live: the newest calls again, at most once a second, the last call of a burst always included. */
  private scheduleHead(): void {
    if (this.liveTimer || this.stopped) return;
    const wait = Math.max(0, this.lastHead + LIVE_THROTTLE_MS - Date.now());
    this.liveTimer = setTimeout(() => {
      this.liveTimer = null;
      this.lastHead = Date.now();
      if (this.live() && !this.stopped) void this.loadCalls('head');
    }, wait);
  }

  // ── Traces ──────────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The tasks for the query, newest first, read page by page until the list reaches the start of the range (a task
   * is in the range when one of its calls is). The core takes the range itself since step API (every page is then in
   * it, over the whole audit); an older core answers without it and the pages stop at the range's start.
   */
  async loadTraces(): Promise<void> {
    const gen = ++this.tracesGen;
    const from = rangeBounds(this.range(), this.time.now()).from;
    const q = this.query().trim() || null;
    const window = requestWindow(this.range(), null, this.time.now());
    const list: TraceRow[] = [];
    let cursor: string | null = null;
    try {
      for (let i = 0; i < MAX_PAGES; i++) {
        const page: { readonly traces: TraceRow[]; readonly nextCursor: string | null } = await this.api.traces(q, cursor, LIST_PAGE, window);
        if (gen !== this.tracesGen || this.stopped) return;
        list.push(...page.traces);
        cursor = page.nextCursor;
        const oldest = list.at(-1);
        if (!cursor || (oldest && Date.parse(oldest.endedAt) < from)) break;
      }
      this.tracesCursor = cursor;
      this.tracesCompleteSignal.set(cursor === null || Date.parse(list.at(-1)?.endedAt ?? '') < from);
      this.tracesSignal.set({ value: list, error: null, unavailable: false });
    } catch (e) {
      if (gen !== this.tracesGen || this.stopped) return;
      this.tracesSignal.update((p) => (isNotAvailable(e) ? { value: null, error: null, unavailable: true } : p.value ? p : { value: null, error: e, unavailable: false }));
    }
  }

  /** "Load older tasks" past what was read: one more page. */
  async loadMoreTraces(): Promise<void> {
    if (!this.tracesCursor) return;
    const gen = this.tracesGen;
    const from = rangeBounds(this.range(), this.time.now()).from;
    const page = await this.api.traces(this.query().trim() || null, this.tracesCursor, LIST_PAGE, requestWindow(this.range(), null, this.time.now()));
    if (gen !== this.tracesGen || this.stopped) return;
    const list = [...(this.tracesSignal().value ?? []), ...page.traces];
    this.tracesCursor = page.nextCursor;
    this.tracesCompleteSignal.set(page.nextCursor === null || Date.parse(list.at(-1)?.endedAt ?? '') < from);
    this.tracesSignal.set({ value: list, error: null, unavailable: false });
  }

  /** One task's phases and calls (once; null when the core doesn't have it). */
  async loadTraceDetail(traceId: string, force = false): Promise<TraceDetail | null> {
    const known = this.traceDetailsSignal();
    if (!force && known.has(traceId)) return known.get(traceId) ?? null;
    try {
      const d = await this.api.trace(traceId);
      this.traceDetailsSignal.update((m) => new Map(m).set(traceId, d));
      return d;
    } catch (e) {
      if (isApiError(e) && (e.kind === 'not-found' || e.kind === 'not-available')) {
        this.traceDetailsSignal.update((m) => new Map(m).set(traceId, null));
        return null;
      }
      throw e;
    }
  }

  // ── Setup changes ───────────────────────────────────────────────────────────────────────────────────────────────

  /** The changes of the last day (and the page that reaches past it). */
  async loadChanges(): Promise<void> {
    const since = this.time.now() - DAY_MS;
    const list: SetupChange[] = [];
    let cursor: string | null = null;
    try {
      for (let i = 0; i < MAX_PAGES; i++) {
        const page: { readonly changes: SetupChange[]; readonly nextCursor: string | null } = await this.api.changes(null, cursor, LIST_PAGE);
        if (this.stopped) return;
        list.push(...page.changes);
        cursor = page.nextCursor;
        const oldest = list.at(-1);
        if (!cursor || (oldest && Date.parse(oldest.at) < since)) break;
      }
      this.changesCursor = cursor;
      this.changesCompleteSignal.set(cursor === null);
      this.changesSignal.set({ value: list, error: null, unavailable: false });
    } catch (e) {
      if (this.stopped) return;
      this.changesSignal.update((p) => (isNotAvailable(e) ? { value: null, error: null, unavailable: true } : p.value ? p : { value: null, error: e, unavailable: false }));
    }
  }

  /** "Load older changes": the next page. */
  async loadOlderChanges(): Promise<void> {
    if (!this.changesCursor) return;
    const page = await this.api.changes(null, this.changesCursor, LIST_PAGE);
    if (this.stopped) return;
    this.changesCursor = page.nextCursor;
    this.changesCompleteSignal.set(page.nextCursor === null);
    this.changesSignal.update((p) => ({ value: [...(p.value ?? []), ...page.changes], error: null, unavailable: false }));
  }

  // ── Saved views, retention, environments, destinations ─────────────────────────────────────────────────────────

  private async loadViews(): Promise<void> {
    try {
      this.viewsSignal.set(await this.api.views());
    } catch {
      // Without the stored list the starting points are offered; saving reads it again first.
    }
  }

  /**
   * Saves a view under a name (a name that exists is replaced). The list starts from the starting points when the
   * person had none, so they stay. A list changed meanwhile (409) is read again and the view saved on top of it.
   */
  async saveView(view: SavedView): Promise<void> {
    for (let attempt = 0; ; attempt++) {
      const stored = this.viewsSignal() ?? (await this.api.views());
      try {
        const saved = await this.api.saveViews(withView(shownViews(stored.views), view), stored.version);
        this.viewsSignal.set(saved);
        return;
      } catch (e) {
        if (attempt === 0 && isApiError(e) && e.kind === 'conflict') {
          this.viewsSignal.set(await this.api.views());
          continue;
        }
        throw e;
      }
    }
  }

  private async loadSide(): Promise<void> {
    await Promise.all([
      this.settingsApi.get('audit').then(
        (s) => this.retentionSignal.set(typeof s.values['retentionYears'] === 'number' ? (s.values['retentionYears'] as number) : null),
        () => undefined,
      ),
      this.connectionsApi.list().then(
        (l) => this.envsSignal.set(envsOf(l.items)),
        () => undefined,
      ),
    ]);
  }

  /** The audit export destinations (Send to SIEM…); before step K5 the core has no such route. */
  async loadDestinations(): Promise<void> {
    try {
      this.destinationsSignal.set({ value: await this.exportApi.list(), error: null, unavailable: false });
    } catch (e) {
      this.destinationsSignal.set(isNotAvailable(e) ? { value: null, error: null, unavailable: true } : { value: null, error: e, unavailable: false });
    }
  }

  // ── Export ──────────────────────────────────────────────────────────────────────────────────────────────────────

  /** Exports what the table lists (query, effective range) to the file chosen in the desktop's save dialog. */
  exportToFile(format: 'csv' | 'jsonl', saveToken: string) {
    return this.api.export({ q: this.query().trim() || null, ...requestWindow(this.range(), this.zoomBucket(), this.time.now()), target: 'file', format, saveToken });
  }

  /** Sends what the table lists to an audit export destination. */
  sendToSiem(destinationId: string) {
    return this.api.export({ q: this.query().trim() || null, ...requestWindow(this.range(), this.zoomBucket(), this.time.now()), target: 'siem', format: 'jsonl', destinationId });
  }

  // ── One call (the drawer) ───────────────────────────────────────────────────────────────────────────────────────

  event(id: number): Promise<EventDetail> {
    return this.api.event(id);
  }

  approval(id: string): Promise<ApprovalDetail | null> {
    return this.approvalsApi.get(id).catch(() => null);
  }

  /** The token or grant behind a call, while it can be revoked (null when it can't, or the tokens don't load). */
  async revokeTargetOf(e: ActivityEvent): Promise<TokenRow | null> {
    if (!e.tokenLabel || !e.endpoint) return null;
    try {
      return revokeTarget(e, await this.tokensApi.list(e.endpoint));
    } catch {
      return null;
    }
  }

  revoke(t: TokenRow): Promise<{ readonly id: string; readonly rejectedApprovals: number }> {
    return t.kind === 'grant' ? this.tokensApi.revokeGrant(t.id) : this.tokensApi.revoke(t.id);
  }
}
