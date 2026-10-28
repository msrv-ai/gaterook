import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { isNotAvailable } from '../../core/api/api-error';
import { ActionSegment, ApprovalItem, ApprovalsApi } from '../../core/api/approvals.api';
import { ClientsApi, ClientsView } from '../../core/api/clients.api';
import { ConnectionDetail, ConnectionRow, ConnectionsApi } from '../../core/api/connections.api';
import { HealthRow, Overview, OverviewApi } from '../../core/api/overview.api';
import { EventStream } from '../../core/events/event-stream';
import { overviewState } from './overview-view';

/**
 * How soon a refresh follows an event: decisions and new requests change the queue at once; health a moment later
 * (a Test all reports row by row); a call or a change on this computer's AI apps every few seconds at most.
 */
const DELAY = { approval: 150, health: 1_000, call: 5_000, clients: 1_000 } as const;

/**
 * Overview's data (ARCHITECTURE.md §6 overview, §8.4: `overview`, SSE `approval.*`, `connection.health`): the core's
 * overview — which also decides the state the page is in (first run, setup, in use) — and what each state needs from
 * the merged areas:
 * - the AI apps of this computer (`/api/clients`: the guide's tiles in every state, the home screen's in use);
 * - in use, the connections with the endpoints that use them (`/api/connections`): what each connected app can use;
 * - in setup, the first connection (`/api/connections/{name}`): its tool modes and where its secret is;
 * - the exact action of each waiting request (`/api/approvals/{id}`: the compact row shows its first line).
 *
 * Only the overview is required: when another call fails, the part it feeds is left out, never filled in. Events
 * refresh everything, coalesced: one refresh in flight, one more after it when events came meanwhile.
 */
@Injectable()
export class OverviewData {
  private readonly api = inject(OverviewApi);
  private readonly approvals = inject(ApprovalsApi);
  private readonly clientsApi = inject(ClientsApi);
  private readonly connectionsApi = inject(ConnectionsApi);
  private readonly events = inject(EventStream);

  private readonly overviewSignal = signal<Overview | null>(null);
  private readonly errorSignal = signal<unknown>(null);
  private readonly staleSignal = signal<unknown>(null);
  private readonly actionsSignal = signal<ReadonlyMap<string, readonly ActionSegment[]>>(new Map());
  private readonly clientsSignal = signal<ClientsView | null>(null);
  private readonly connectionsSignal = signal<readonly ConnectionRow[] | null>(null);
  private readonly firstSignal = signal<ConnectionDetail | null>(null);
  private readonly refreshingSignal = signal(false);

  /** `GET /api/overview`; null until the first answer. */
  readonly overview = this.overviewSignal.asReadonly();
  /** Why the first load failed (nothing to show yet); null once anything loaded. */
  readonly error = this.errorSignal.asReadonly();
  /** Why the last refresh failed while older data is on screen. */
  readonly stale = this.staleSignal.asReadonly();
  /** The exact action of each waiting request, by id, as the server segmented it. */
  readonly actions = this.actionsSignal.asReadonly();
  /** This computer's AI apps; null when the core has no clients area or it didn't answer. */
  readonly clients = this.clientsSignal.asReadonly();
  /** The connections with the endpoints that use them (in use only); null when not loaded. */
  readonly connections = this.connectionsSignal.asReadonly();
  /** The first connection's detail (setup only): its tool modes on endpoints, where its secret is. */
  readonly first = this.firstSignal.asReadonly();
  readonly refreshing = this.refreshingSignal.asReadonly();

  private inFlight: Promise<void> | null = null;
  private again = false;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private dueAt = Number.POSITIVE_INFINITY;
  private stopped = false;
  private readonly unsubscribe: (() => void)[] = [];
  /** The core answered that it has no clients area: the tiles of AI apps are left out, not asked for again. */
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
      // A call moves the day's numbers and proves a connected AI app; a config written or removed outside the app
      // changes what the tiles say.
      this.events.on('audit.call', () => this.schedule(DELAY.call)),
      this.events.on('clients.drift', () => this.schedule(DELAY.clients)),
      this.events.on('oauth.grant.changed', () => this.schedule(DELAY.clients)),
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

  /** One connection's new health (an event or a test the page ran), until the next refresh. */
  patchHealth(h: Omit<HealthRow, 'type' | 'env'>): void {
    this.overviewSignal.update((o) => (o ? { ...o, health: o.health.map((r) => (r.name === h.name ? { ...r, ...h } : r)) } : o));
  }

  /** A request left the queue (decided, or it ran out of time here): off the list at once. */
  dropWaiting(id: string): void {
    this.overviewSignal.update((o) => (o ? { ...o, waiting: o.waiting.filter((w) => w.id !== id) } : o));
  }

  private async load(): Promise<void> {
    let overview: Overview;
    try {
      overview = await this.api.get();
    } catch (e) {
      if (this.overviewSignal()) this.staleSignal.set(e);
      else this.errorSignal.set(e);
      return;
    }
    if (this.stopped) return;
    const state = overviewState(overview);
    const firstName = overview.gettingStarted.steps.find((s) => s.id === 'connection')?.detail['name'];
    const [actions, clients, connections, first] = await Promise.all([
      this.loadActions(overview.waiting),
      this.loadClients(),
      state === 'in-use' ? this.connectionsApi.list().then((l) => l.items, () => null) : Promise.resolve(null),
      state === 'setup' && typeof firstName === 'string' ? this.connectionsApi.get(firstName).catch(() => null) : Promise.resolve(null),
    ]);
    if (this.stopped) return;
    this.overviewSignal.set(overview);
    this.errorSignal.set(null);
    this.staleSignal.set(null);
    this.actionsSignal.set(actions);
    this.clientsSignal.set(clients);
    this.connectionsSignal.set(connections);
    this.firstSignal.set(first);
  }

  /** This computer's AI apps; a core without the clients area (or one that failed to answer) leaves them out. */
  private async loadClients(): Promise<ClientsView | null> {
    if (this.noClientsApi) return null;
    try {
      return await this.clientsApi.list();
    } catch (e) {
      if (isNotAvailable(e)) this.noClientsApi = true;
      return this.clientsSignal();
    }
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
}
