import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { ApprovalsApi } from '../../core/api/approvals.api';
import { ConnectionDetail, ConnectionDeleted, ConnectionHealth, ConnectionRow, ConnectionsApi, TestOutcome } from '../../core/api/connections.api';
import { ConnectorTypesApi } from '../../core/api/connector-types.api';
import { EndpointsApi } from '../../core/api/endpoints.api';
import { SecretRow, SecretsApi, ShortLivedCredential } from '../../core/api/secrets.api';
import { EventStream } from '../../core/events/event-stream';
import { DeleteImpact, HealthState, deleteImpact, healthState, isMcpServer, nextHealth, sortRows } from './connections-view';

/**
 * Test all waits for each connection's `connection.health`; when none came for this long the rest are taken as not
 * answering. Twice the core's test timeout (`gateway.connections.test-timeout`, 15 s): four tests run at a time, so a
 * quiet stretch of one timeout is normal.
 */
export const TEST_ALL_IDLE_MS = 30_000;

/** How a row test ended: the core's result, or its refusal (409 for a row changed outside GateRook, …). */
export type RowTest = { readonly ok: true; readonly outcome: TestOutcome } | { readonly ok: false; readonly error: unknown };

/** How Test all ended: one health per connection that reported back, out of how many the core tests. */
export interface TestAllEnd {
  readonly total: number;
  readonly results: readonly HealthState[];
}

/** What the delete confirmation needs; `failed` is why part of it didn't load (the person may still delete). */
export interface DeleteFacts {
  readonly impact: DeleteImpact;
  readonly detail: ConnectionDetail | null;
  readonly failed: unknown;
}

interface TestAllRun {
  /** How many the core tests (from its 202); null until it answered. */
  total: number | null;
  readonly waiting: Set<string>;
  readonly results: HealthState[];
  timer: ReturnType<typeof setTimeout> | null;
  readonly done: (end: TestAllEnd) => void;
}

/**
 * The Connections page's data (ARCHITECTURE.md §6 connections, §8.4: `connections`, `test-all`, SSE
 * `connection.health`): the list, with the secrets of each connection from the Secrets area for the Credentials
 * column (`/api/secrets`, `/api/secrets/short-lived`; left out when they don't answer), live health from the core's
 * events, and the actions of the page — test one, test all, delete, keep a row changed outside GateRook.
 *
 * Health arrives as events and patches its row; the list itself is reloaded after an action and when the core comes
 * back, coalesced: one load in flight and one more after it.
 */
@Injectable()
export class ConnectionsData {
  private readonly api = inject(ConnectionsApi);
  private readonly secretsApi = inject(SecretsApi);
  private readonly approvalsApi = inject(ApprovalsApi);
  private readonly endpointsApi = inject(EndpointsApi);
  private readonly typesApi = inject(ConnectorTypesApi);
  private readonly events = inject(EventStream);

  private readonly rowsSignal = signal<readonly ConnectionRow[] | null>(null);
  private readonly errorSignal = signal<unknown>(null);
  private readonly staleSignal = signal<unknown>(null);
  private readonly refreshingSignal = signal(false);
  private readonly secretsSignal = signal<readonly SecretRow[]>([]);
  private readonly shortLivedSignal = signal<readonly ShortLivedCredential[]>([]);
  private readonly checkingSignal = signal<ReadonlySet<string>>(new Set());
  private readonly testingAllSignal = signal<{ readonly total: number | null } | null>(null);
  private readonly intervalSignal = signal<number | null>(null);

  /** The connections by name; null until the first answer. */
  readonly rows = this.rowsSignal.asReadonly();
  /** Why the first load failed (nothing to show yet). */
  readonly error = this.errorSignal.asReadonly();
  /** Why the last reload failed while older rows are on screen. */
  readonly stale = this.staleSignal.asReadonly();
  readonly refreshing = this.refreshingSignal.asReadonly();
  /** The secrets table's rows (all owners); empty when Secrets didn't answer. */
  readonly secrets = this.secretsSignal.asReadonly();
  readonly shortLived = this.shortLivedSignal.asReadonly();
  /** Connections being tested now (Test connection, Test all): their health cell says "Checking". */
  readonly checking = this.checkingSignal.asReadonly();
  /** Test all is running: how many the core said it tests (null until it answered). */
  readonly testingAll = this.testingAllSignal.asReadonly();
  /** How often the core checks every connection, in seconds; null from a core that doesn't say (before step API). */
  readonly healthInterval = this.intervalSignal.asReadonly();

  private inFlight: Promise<void> | null = null;
  /** Events received during a load also apply to rows that have not reached the screen yet. */
  private readonly loadingHealth = new Map<string, ConnectionHealth>();
  private again = false;
  private stopped = false;
  private run: TestAllRun | null = null;
  private readonly unsubscribe: (() => void)[] = [];

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stop());
  }

  start(): Promise<void> {
    this.unsubscribe.push(this.events.on('connection.health', (h) => this.onHealth(h)));
    return this.refresh();
  }

  stop(): void {
    this.stopped = true;
    this.unsubscribe.splice(0).forEach((u) => u());
    if (this.run?.timer) clearTimeout(this.run.timer);
    this.run = null;
  }

  /** Reloads now; while one runs, one more follows it (a change that came meanwhile is in neither answer yet). */
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

  private async load(): Promise<void> {
    this.loadingHealth.clear();
    let items: ConnectionRow[];
    try {
      // Unfiltered: the filters and their counts work on the whole list, at once as the person types. External MCP
      // servers are Connectors' (`kind=connection`, step API; a core before it lists them, so they are left out here).
      const page = await this.api.list({ kind: 'connection' });
      items = page.items.filter((r) => !isMcpServer(r));
      this.intervalSignal.set(page.healthIntervalSeconds ?? null);
    } catch (e) {
      if (this.rowsSignal()) this.staleSignal.set(e);
      else this.errorSignal.set(e);
      return;
    }
    const [secrets, shortLived] = await Promise.all([
      this.secretsApi.list().catch(() => null),
      this.secretsApi.shortLived().catch(() => null),
    ]);
    if (this.stopped) return;
    this.rowsSignal.set(sortRows(items.map((row) => {
      const health = this.loadingHealth.get(row.name);
      return health && (!row.health.checkedAt || Date.parse(health.checkedAt!) > Date.parse(row.health.checkedAt))
        ? { ...row, health: nextHealth(row.health, health) }
        : row;
    })));
    this.loadingHealth.clear();
    this.errorSignal.set(null);
    this.staleSignal.set(null);
    this.secretsSignal.set((secrets ?? []).filter((s) => s.ownerKind === 'connection'));
    this.shortLivedSignal.set(shortLived ?? []);
  }

  /** The secrets of one connection, for its Credentials cell. */
  secretsOf(name: string): SecretRow[] {
    return this.secretsSignal().filter((s) => s.owner === name);
  }

  // ── Health ────────────────────────────────────────────────────────────────────────────────────────────────────

  private onHealth(h: { readonly name: string; readonly state: string; readonly latencyMs?: number; readonly message: string | null; readonly checkedAt: string }): void {
    const health: ConnectionHealth = { state: h.state, latencyMs: h.latencyMs ?? null, message: h.message, checkedAt: h.checkedAt };
    if (this.inFlight) this.loadingHealth.set(h.name, health);
    this.rowsSignal.update((rows) =>
      rows
        ? rows.map((r) =>
            r.name === h.name
              ? { ...r, health: nextHealth(r.health, health) }
              : r,
          )
        : rows,
    );
    const run = this.run;
    if (run && run.waiting.delete(h.name)) {
      run.results.push(healthState({ health: { state: h.state, latencyMs: null, message: null, checkedAt: null } }));
      this.uncheck([h.name]);
      this.armIdle(run);
      this.finishIfDone(run);
    }
  }

  private check(names: readonly string[]): void {
    this.checkingSignal.update((s) => new Set([...s, ...names]));
  }

  private uncheck(names: readonly string[]): void {
    this.checkingSignal.update((s) => {
      const next = new Set(s);
      names.forEach((n) => next.delete(n));
      return next;
    });
  }

  /**
   * Test connection: the full test with the stored credentials. The core records the health and publishes it; the
   * list is reloaded so the row shows what the core recorded.
   */
  async test(name: string): Promise<RowTest> {
    this.check([name]);
    let result: RowTest;
    try {
      result = { ok: true, outcome: await this.api.test(name) };
    } catch (error) {
      result = { ok: false, error };
    }
    // The health event may still be on its way: the reload has the recorded health either way.
    await this.refresh();
    if (!this.run?.waiting.has(name)) this.uncheck([name]);
    return result;
  }

  /**
   * Test all: the core tests every connection it can in the background (202) and publishes each result as
   * `connection.health`; each row shows "Checking" until its result arrives. Resolves when every tested connection
   * reported back, or when none did for {@link TEST_ALL_IDLE_MS}. Rejects when the core refuses (409 already running).
   */
  async testAll(): Promise<TestAllEnd> {
    if (this.run) throw new Error('Test all is already running');
    // Rows changed outside GateRook are skipped by the core: they keep their health.
    const names = (this.rowsSignal() ?? []).filter((r) => r.verified).map((r) => r.name);
    let done!: (end: TestAllEnd) => void;
    const ended = new Promise<TestAllEnd>((resolve) => (done = resolve));
    // Waiting starts before the request: the first results can arrive before the core's 202 does.
    const run: TestAllRun = { total: null, waiting: new Set(names), results: [], timer: null, done };
    this.run = run;
    this.testingAllSignal.set({ total: null });
    this.check(names);
    try {
      const started = await this.api.testAll();
      run.total = started.connections;
    } catch (e) {
      this.end(run);
      throw e;
    }
    if (this.run !== run) return ended;
    this.testingAllSignal.set({ total: run.total });
    this.armIdle(run);
    this.finishIfDone(run);
    return ended;
  }

  private armIdle(run: TestAllRun): void {
    if (run.timer) clearTimeout(run.timer);
    if (run.total === null) return;
    run.timer = setTimeout(() => this.finish(run), TEST_ALL_IDLE_MS);
  }

  private finishIfDone(run: TestAllRun): void {
    // The core may test fewer than the page waits for (a connector type that is off): its count decides.
    if (run.total !== null && (run.results.length >= run.total || run.waiting.size === 0)) this.finish(run);
  }

  private finish(run: TestAllRun): void {
    if (this.run !== run) return;
    const total = run.total ?? run.results.length;
    this.end(run);
    void this.refresh();
    run.done({ total, results: [...run.results] });
  }

  private end(run: TestAllRun): void {
    if (run.timer) clearTimeout(run.timer);
    this.uncheck([...run.waiting]);
    this.run = null;
    this.testingAllSignal.set(null);
  }

  // ── Delete and keep ───────────────────────────────────────────────────────────────────────────────────────────

  /**
   * What the confirmation shows: the connection's tools per endpoint and its stored secrets (`connections/{name}`),
   * the connector's tool order, the endpoints' clients and the pending requests on it. Only the detail matters for
   * the facts; each of the others leaves its part out when it doesn't answer.
   */
  async deleteFacts(row: ConnectionRow): Promise<DeleteFacts> {
    const [detail, toolOrder, endpoints, pending] = await Promise.all([
      this.api.get(row.name).then(
        (d) => ({ d, e: null as unknown }),
        (e: unknown) => ({ d: null, e }),
      ),
      this.typesApi.get(row.type).then((t) => t.tools.map((x) => x.name), () => null),
      this.endpointsApi.list().catch(() => null),
      this.approvalsApi.list('pending', null, 200).then((p) => p.items, () => null),
    ]);
    return {
      impact: deleteImpact({ row, detail: detail.d, toolOrder, endpoints, pending }),
      detail: detail.d,
      failed: detail.e,
    };
  }

  /** Deletes (the dialog named it, so its name is the confirmation the core asks for); the row goes at once. */
  async remove(name: string): Promise<ConnectionDeleted> {
    const result = await this.api.delete(name, name);
    this.rowsSignal.update((rows) => (rows ? rows.filter((r) => r.name !== name) : rows));
    this.secretsSignal.update((s) => s.filter((x) => x.owner !== name));
    void this.refresh();
    return result;
  }

  /** The person reviewed a row changed outside GateRook and keeps it as stored (a Setup change). */
  async keep(name: string): Promise<void> {
    const row = await this.api.confirmIntegrity(name);
    this.rowsSignal.update((rows) => (rows ? rows.map((r) => (r.name === name ? row : r)) : rows));
    void this.refresh();
  }
}
