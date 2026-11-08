import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { isNotAvailable } from '../../core/api/api-error';
import { ClientsApi, ClientsView } from '../../core/api/clients.api';
import { ConnectionRow, ConnectionsApi, nextHealth } from '../../core/api/connections.api';
import { EndpointSummary, EndpointsApi } from '../../core/api/endpoints.api';
import { HealthRow, Overview, OverviewApi } from '../../core/api/overview.api';
import { EventStream } from '../../core/events/event-stream';

/**
 * How soon a refresh follows an event: decisions and new requests change the queue at once; health a moment later
 * (a Test all reports row by row); a call or a change on this computer's AI apps every few seconds at most.
 */
const DELAY = { approval: 150, health: 1_000, call: 5_000, clients: 1_000 } as const;

export interface OverviewAreaErrors {
  readonly connections: unknown | null;
  readonly endpoints: unknown | null;
  readonly clients: unknown | null;
}

interface AreaResult<T> { readonly value: T | null; readonly error: unknown | null }

async function areaResult<T>(request: Promise<T>): Promise<AreaResult<T>> {
  try { return { value: await request, error: null }; }
  catch (error) { return { value: null, error }; }
}

/**
 * Overview's connection map and aggregate activity (ARCHITECTURE.md §6 overview, §8.4):
 * - the core's overview, including setup progress, health and the approval count;
 * - this computer's AI apps and their exact endpoint links (`/api/clients`);
 * - all saved connections and the endpoints that use them (`/api/connections`);
 * - every endpoint (`/api/endpoints`), including empty or disabled ones.
 *
 * Only the overview is required: when another call fails, the part it feeds is left out, never filled in. Events
 * refresh everything, coalesced: one refresh in flight, one more after it when events came meanwhile.
 */
@Injectable()
export class OverviewData {
  private readonly api = inject(OverviewApi);
  private readonly clientsApi = inject(ClientsApi);
  private readonly connectionsApi = inject(ConnectionsApi);
  private readonly endpointsApi = inject(EndpointsApi);
  private readonly events = inject(EventStream);

  private readonly overviewSignal = signal<Overview | null>(null);
  private readonly errorSignal = signal<unknown>(null);
  private readonly staleSignal = signal<unknown>(null);
  private readonly clientsSignal = signal<ClientsView | null>(null);
  private readonly connectionsSignal = signal<readonly ConnectionRow[] | null>(null);
  private readonly endpointsSignal = signal<readonly EndpointSummary[] | null>(null);
  private readonly refreshingSignal = signal(false);
  private readonly areaErrorsSignal = signal<OverviewAreaErrors>({ connections: null, endpoints: null, clients: null });
  private readonly connectionToolCounts = signal<{ readonly slug: string; readonly counts: ReadonlyMap<string, number> } | null>(null);
  private selectedEndpoint: string | null = null;
  private countsGeneration = 0;
  private countsInFlight: Promise<void> | null = null;

  /** `GET /api/overview`; null until the first answer. */
  readonly overview = this.overviewSignal.asReadonly();
  /** Why the first load failed (nothing to show yet); null once anything loaded. */
  readonly error = this.errorSignal.asReadonly();
  /** Why the last refresh failed while older data is on screen. */
  readonly stale = this.staleSignal.asReadonly();
  /** This computer's AI apps; null when the core has no clients area or it didn't answer. */
  readonly clients = this.clientsSignal.asReadonly();
  /** All saved connections, including external MCP servers; null when their list did not load. */
  readonly connections = this.connectionsSignal.asReadonly();
  /** All endpoints, including empty ones; null when their list did not load. */
  readonly endpoints = this.endpointsSignal.asReadonly();
  readonly refreshing = this.refreshingSignal.asReadonly();
  /** A missing optional area is unavailable, never an empty list or confirmed access. */
  readonly areaErrors = this.areaErrorsSignal.asReadonly();

  private inFlight: Promise<void> | null = null;
  private again = false;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private dueAt = Number.POSITIVE_INFINITY;
  private stopped = false;
  private readonly unsubscribe: (() => void)[] = [];
  /** The core answered that it has no clients area: the tiles of AI apps are left out, not asked for again. */
  private noClientsApi = false;
  private clientsUnavailableError: unknown = null;

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
      this.events.on('mcp-server.state', () => this.schedule(DELAY.health)),
      this.events.on('mcp-server.sign-in', () => this.schedule(DELAY.health)),
      this.events.on('integrity.alert', () => this.schedule(DELAY.health)),
    );
    return this.refresh();
  }

  stop(): void {
    this.stopped = true;
    ++this.countsGeneration;
    this.connectionToolCounts.set(null);
    this.unsubscribe.splice(0).forEach((u) => u());
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
  }

  /** One wiring request for the selected endpoint supplies every service card's real tool count. */
  selectEndpoint(slug: string | null): Promise<void> {
    if (slug === this.selectedEndpoint) return this.countsInFlight ?? Promise.resolve();
    this.selectedEndpoint = slug;
    return this.loadConnectionToolCounts();
  }

  /** Missing data is unknown, not zero; counts never cross endpoint scopes. */
  connectionToolCount(slug: string | null, connection: string): number | null {
    const snapshot = this.connectionToolCounts();
    return snapshot?.slug === slug ? snapshot.counts.get(connection) ?? null : null;
  }

  private loadConnectionToolCounts(): Promise<void> {
    const generation = ++this.countsGeneration;
    const slug = this.selectedEndpoint;
    this.connectionToolCounts.set(null);
    if (!slug || this.stopped) {
      this.countsInFlight = null;
      return Promise.resolve();
    }
    const request = (async () => {
      try {
        const detail = await this.endpointsApi.get(slug);
        if (this.stopped || generation !== this.countsGeneration) return;
        this.connectionToolCounts.set({ slug, counts: new Map(detail.connections.map((connection) => [
          connection.name, connection.tools.read + connection.tools.approval + connection.tools.off + (connection.tools.bypass ?? 0),
        ])) });
      } catch {
        // The map stays usable without counts; a failed request must not claim zero available tools.
        if (generation === this.countsGeneration) this.connectionToolCounts.set(null);
      }
    })().finally(() => {
      if (generation === this.countsGeneration) this.countsInFlight = null;
    });
    this.countsInFlight = request;
    return request;
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
    this.connectionsSignal.update((rows) => rows?.map((row) => row.name === h.name ? {
      ...row,
      health: nextHealth(row.health, { state: h.state, latencyMs: h.latencyMs, message: h.message, checkedAt: h.checkedAt }),
    } : row) ?? null);
  }

  /** Apply a confirmed access mutation to every appearance while its refresh is in flight. */
  patchConnectionEnabled(name: string, enabled: boolean): void {
    this.connectionsSignal.update((rows) => rows?.map((row) => row.name === name ? { ...row, enabled } : row) ?? null);
  }

  /** The endpoint's saved wiring remains present when access is paused. */
  patchEndpointEnabled(slug: string, enabled: boolean): void {
    this.endpointsSignal.update((rows) => rows?.map((row) => row.slug === slug ? { ...row, status: enabled ? 'running' : 'disabled' } : row) ?? null);
  }

  /** Keep the new endpoint selectable while its connections and counters refresh. */
  addEndpoint(endpoint: EndpointSummary): void {
    this.endpointsSignal.update((rows) => [...(rows ?? []).filter((row) => row.slug !== endpoint.slug), endpoint]);
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
    const [clients, connections, endpoints] = await Promise.all([
      this.loadClients(),
      areaResult(this.connectionsApi.list().then((list) => list.items)),
      areaResult(this.endpointsApi.list()),
    ]);
    if (this.stopped) return;
    this.overviewSignal.set(overview);
    this.errorSignal.set(null);
    this.staleSignal.set(null);
    this.clientsSignal.set(clients.value);
    this.connectionsSignal.set(connections.value);
    this.endpointsSignal.set(endpoints.value);
    this.areaErrorsSignal.set({ connections: connections.error, endpoints: endpoints.error, clients: clients.error });
    if (this.selectedEndpoint && endpoints.value?.some((endpoint) => endpoint.slug === this.selectedEndpoint)) {
      await this.loadConnectionToolCounts();
    } else {
      ++this.countsGeneration;
      this.connectionToolCounts.set(null);
      this.countsInFlight = null;
    }
  }

  /** This computer's AI apps; a core without the clients area (or one that failed to answer) leaves them out. */
  private async loadClients(): Promise<AreaResult<ClientsView>> {
    if (this.noClientsApi) return { value: null, error: this.clientsUnavailableError };
    try {
      return { value: await this.clientsApi.list(), error: null };
    } catch (e) {
      if (isNotAvailable(e)) {
        this.noClientsApi = true;
        this.clientsUnavailableError = e;
      }
      return { value: null, error: e };
    }
  }
}
