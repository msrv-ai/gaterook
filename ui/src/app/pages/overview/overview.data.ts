import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { ActivityApi, ActivityEvent, Histogram } from '../../core/api/activity.api';
import { isNotAvailable } from '../../core/api/api-error';
import { ActionSegment, ApprovalItem, ApprovalsApi } from '../../core/api/approvals.api';
import { ClientsApi } from '../../core/api/clients.api';
import { HealthRow, Overview, OverviewApi } from '../../core/api/overview.api';
import { EventStream } from '../../core/events/event-stream';
import { ApprovalsDay, RECENT_CALLS, decidedCalls } from './overview-view';

const DAY = 24 * 3_600_000;

/** History pages read for the day's approval numbers (200 per page): enough for 1,000 requests a day. */
const HISTORY_PAGES = 5;

/**
 * How soon a refresh follows an event: decisions and new requests change the queue at once; health a moment later
 * (a Test all reports row by row); a call every few seconds at most (a busy agent makes many).
 */
const DELAY = { approval: 150, health: 1_000, call: 5_000 } as const;

/**
 * Overview's data (ARCHITECTURE.md §6 overview, §8.4: `overview`, SSE `approval.*`, `connection.health`): the core's
 * overview, plus what its tiles and lists need from the merged areas — the hour-by-hour decisions of the day
 * (`activity/histogram`), the day's approval requests (`approvals` history and queue: how many waited when, how
 * long a decision took), the exact action of each waiting request (`approvals/{id}`: the compact row shows its first
 * line), the last decided calls (`activity/events`, pending ones are the waiting list) and, while the client step
 * is open, the clients this computer has (K1 `clients`, "not available yet" before K1).
 *
 * Only the overview is required: when another call fails the part it feeds is left out, never filled in. Events
 * refresh everything, coalesced: one refresh in flight, one more after it when events came meanwhile.
 */
@Injectable()
export class OverviewData {
  private readonly api = inject(OverviewApi);
  private readonly activity = inject(ActivityApi);
  private readonly approvals = inject(ApprovalsApi);
  private readonly clients = inject(ClientsApi);
  private readonly events = inject(EventStream);

  private readonly overviewSignal = signal<Overview | null>(null);
  private readonly errorSignal = signal<unknown>(null);
  private readonly staleSignal = signal<unknown>(null);
  private readonly histogramSignal = signal<Histogram | null>(null);
  private readonly approvalsSignal = signal<ApprovalsDay | null>(null);
  private readonly recentSignal = signal<readonly ActivityEvent[] | null>(null);
  private readonly actionsSignal = signal<ReadonlyMap<string, readonly ActionSegment[]>>(new Map());
  private readonly detectedSignal = signal<readonly string[]>([]);
  private readonly refreshingSignal = signal(false);

  /** `GET /api/overview`; null until the first answer. */
  readonly overview = this.overviewSignal.asReadonly();
  /** Why the first load failed (nothing to show yet); null once anything loaded. */
  readonly error = this.errorSignal.asReadonly();
  /** Why the last refresh failed while older data is on screen. */
  readonly stale = this.staleSignal.asReadonly();
  readonly histogram = this.histogramSignal.asReadonly();
  readonly approvalsDay = this.approvalsSignal.asReadonly();
  /** The last decided calls; null when Activity didn't answer (the overview's own list stands in). */
  readonly recent = this.recentSignal.asReadonly();
  /** The exact action of each waiting request, by id, as the server segmented it. */
  readonly actions = this.actionsSignal.asReadonly();
  /** Names of the MCP clients found on this computer (empty until K1 answers). */
  readonly detected = this.detectedSignal.asReadonly();
  readonly refreshing = this.refreshingSignal.asReadonly();

  private inFlight: Promise<void> | null = null;
  private again = false;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private dueAt = Number.POSITIVE_INFINITY;
  private stopped = false;
  private readonly unsubscribe: (() => void)[] = [];
  /** Dismiss and Undo, one after the other; the version names the latest, whose answer is the checklist. */
  private checklistOps: Promise<void> = Promise.resolve();
  private checklistVersion = 0;
  /** The core answered that it has no clients area (K1 not merged): the checklist's client step reads nothing more. */
  private noClientsApi = false;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stop());
  }

  /** Loads everything and follows the core's events until the page goes away. */
  start(): Promise<void> {
    this.unsubscribe.push(
      this.events.on('approval.requested', () => this.schedule(DELAY.approval)),
      this.events.on('approval.resolved', (e) => {
        // The decided request leaves the list at once; the numbers follow with the refresh.
        this.dropWaiting(e.id);
        this.schedule(DELAY.approval);
      }),
      this.events.on('connection.health', (h) => {
        this.patchHealth({ name: h.name, state: h.state, latencyMs: h.latencyMs ?? null, message: h.message, checkedAt: h.checkedAt });
        this.schedule(DELAY.health);
      }),
      this.events.on('audit.call', () => this.schedule(DELAY.call)),
    );
    return this.refresh();
  }

  stop(): void {
    this.stopped = true;
    this.unsubscribe.splice(0).forEach((u) => u());
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
  }

  /** A refresh within `ms`; an earlier one already planned stands. */
  schedule(ms: number): void {
    if (this.stopped) return;
    const due = Date.now() + ms;
    if (this.timer && due >= this.dueAt) return;
    if (this.timer) clearTimeout(this.timer);
    this.dueAt = due;
    this.timer = setTimeout(() => {
      this.timer = null;
      this.dueAt = Number.POSITIVE_INFINITY;
      void this.refresh();
    }, ms);
  }

  /** Reloads now; while one runs, one more follows it (events that came meanwhile are in neither answer yet). */
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

  /**
   * Dismiss (or, as its Undo, restore) the checklist. The card goes at once — the action is undoable, so there is
   * nothing to wait for — and the core's answer is the checklist from then on. Dismiss and Undo go to the core in
   * the order they were pressed, and a refresh that started before one of them doesn't bring back what it replaced.
   * A refusal puts the card back as it was and rejects.
   */
  setChecklistDismissed(dismissed: boolean): Promise<void> {
    const before = this.overviewSignal()?.gettingStarted.dismissed ?? !dismissed;
    const version = ++this.checklistVersion;
    this.setDismissed(dismissed);
    const op = this.checklistOps.then(() => (dismissed ? this.api.dismissGettingStarted() : this.api.restoreGettingStarted()));
    this.checklistOps = op.then(
      () => undefined,
      () => undefined,
    );
    return op.then(
      (gettingStarted) => {
        // A later Dismiss or Undo owns the card now; its own answer will say.
        if (version === this.checklistVersion) this.overviewSignal.update((o) => (o ? { ...o, gettingStarted } : o));
      },
      (e: unknown) => {
        if (version === this.checklistVersion) this.setDismissed(before);
        throw e;
      },
    );
  }

  private setDismissed(dismissed: boolean): void {
    this.overviewSignal.update((o) => (o ? { ...o, gettingStarted: { ...o.gettingStarted, dismissed } } : o));
  }

  /** One connection's new health (an event or a test the page ran), until the next refresh. */
  patchHealth(h: Omit<HealthRow, 'type' | 'env'>): void {
    this.overviewSignal.update((o) =>
      o ? { ...o, health: o.health.map((r) => (r.name === h.name ? { ...r, ...h } : r)) } : o,
    );
  }

  private dropWaiting(id: string): void {
    this.overviewSignal.update((o) => (o ? { ...o, waiting: o.waiting.filter((w) => w.id !== id) } : o));
  }

  private async load(): Promise<void> {
    const checklist = this.checklistVersion;
    let overview: Overview;
    try {
      overview = await this.api.get();
    } catch (e) {
      if (this.overviewSignal()) this.staleSignal.set(e);
      else this.errorSignal.set(e);
      return;
    }
    if (this.stopped) return;
    const [histogram, approvals, recent, actions, detected] = await Promise.all([
      this.activity.histogram().catch(() => null),
      this.loadApprovals().catch(() => null),
      this.activity.events({ q: '-decision:pending', limit: RECENT_CALLS }).then((p) => decidedCalls(p.events), () => null),
      this.loadActions(overview.waiting),
      this.loadDetected(overview),
    ]);
    if (this.stopped) return;
    // Dismiss or Undo was pressed while this answer was on its way: the card stays as the person left it.
    const shown = this.overviewSignal();
    if (shown && checklist !== this.checklistVersion) overview = { ...overview, gettingStarted: shown.gettingStarted };
    this.overviewSignal.set(overview);
    this.errorSignal.set(null);
    this.staleSignal.set(null);
    this.histogramSignal.set(histogram);
    this.approvalsSignal.set(approvals);
    this.recentSignal.set(recent);
    this.actionsSignal.set(actions);
    this.detectedSignal.set(detected);
  }

  /** The requests of the last 24 hours: the queue and the history pages that reach back that far. */
  private async loadApprovals(): Promise<ApprovalsDay> {
    const since = Date.now() - DAY;
    const pending = await this.approvals.list('pending', null, 200);
    const history: ApprovalItem[] = [];
    let cursor: string | null = null;
    for (let page = 0; page < HISTORY_PAGES; page++) {
      const p = await this.approvals.list('history', cursor, 200);
      history.push(...p.items);
      cursor = p.nextCursor;
      const oldest = p.items.at(-1);
      if (!cursor || !oldest || Date.parse(oldest.requestedAt) < since) break;
    }
    return { pending: pending.items, history, counts: pending.counts };
  }

  /** The exact action of each waiting request; a request whose detail didn't load shows its summary instead. */
  private async loadActions(waiting: readonly ApprovalItem[]): Promise<ReadonlyMap<string, readonly ActionSegment[]>> {
    const known = this.actionsSignal();
    const entries = await Promise.all(
      waiting.map(async (w): Promise<[string, readonly ActionSegment[]] | null> => {
        // A waiting request's action never changes: it is what will run.
        const cached = known.get(w.id);
        if (cached) return [w.id, cached];
        try {
          const detail = await this.approvals.get(w.id);
          return detail.action?.segments?.length ? [w.id, detail.action.segments] : null;
        } catch {
          return null;
        }
      }),
    );
    return new Map(entries.filter((e): e is [string, readonly ActionSegment[]] => !!e));
  }

  /** While "Connect a client" is open: which clients are installed here ("Claude Desktop, Codex and Cursor are installed."). */
  private async loadDetected(overview: Overview): Promise<readonly string[]> {
    const step = overview.gettingStarted.steps.find((s) => s.id === 'client');
    if (!step || step.state === 'done' || overview.gettingStarted.dismissed || this.noClientsApi) return [];
    try {
      const view = await this.clients.list();
      return view.clients.filter((c) => c.detected || c.status !== 'not-installed').map((c) => c.name);
    } catch (e) {
      // This core has no clients area yet (K1 not merged): don't ask again on every refresh.
      if (isNotAvailable(e)) this.noClientsApi = true;
      return [];
    }
  }
}
