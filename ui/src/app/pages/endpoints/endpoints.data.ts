import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { ConnectionRow, ConnectionsApi } from '../../core/api/connections.api';
import { EndpointStart, EndpointSummary, EndpointsApi, TokenRow, TokensApi } from '../../core/api/endpoints.api';
import { TemplatesApi } from '../../core/api/templates.api';
import { OAuthApi, OAuthGrant } from '../../core/api/tunnel.api';
import { JsonValue } from '../../core/api/types';
import { EventStream } from '../../core/events/event-stream';
import { StartFrom } from './endpoints-view';

/** How a reload goes: `list` reads only the endpoints (their counters); `full` also what the cards add to them. */
type Depth = 'list' | 'full';

/** The runbooks of one template (`natures` `{"read": 1, "mutation": 1}`): one per operation it compiles to. */
function runbookCount(natures: JsonValue): number {
  if (!natures || typeof natures !== 'object' || Array.isArray(natures)) return 0;
  return Object.values(natures).reduce<number>((n, v) => n + (typeof v === 'number' ? v : 0), 0);
}

/**
 * The Endpoints page's data (ARCHITECTURE.md §6 endpoints, §8.4): the list with its counters (`GET /api/endpoints`),
 * and what each card says beyond them — the connections with their live health (`/api/connections` and
 * `connection.health` events: which connection is slow or unreachable), each endpoint's tokens
 * (`/api/endpoints/{slug}/tokens`: active, expired, expiring), the OAuth sign-ins (`/api/oauth/grants`, step K3:
 * left out until the core has it) and the runbooks of a limited channel (`/api/templates?kind=ssh-runbook`). Only the
 * list is required: every other part is left out when it doesn't answer, nothing is made up.
 *
 * The actions of the page — create, duplicate, disable / enable, delete, keep a row changed outside GateRook,
 * show a dismissed risk warning again — go through here and patch the list at once with the core's answer.
 *
 * Reloads are coalesced: one in flight and one more after it, at the deepest depth asked for meanwhile. Approval
 * events and the tunnel change only the counters; a client link or a restarted core may change everything.
 */
@Injectable()
export class EndpointsData {
  private readonly api = inject(EndpointsApi);
  private readonly tokensApi = inject(TokensApi);
  private readonly connectionsApi = inject(ConnectionsApi);
  private readonly templatesApi = inject(TemplatesApi);
  private readonly oauthApi = inject(OAuthApi);
  private readonly events = inject(EventStream);

  private readonly rowsSignal = signal<readonly EndpointSummary[] | null>(null);
  private readonly errorSignal = signal<unknown>(null);
  private readonly staleSignal = signal<unknown>(null);
  private readonly refreshingSignal = signal(false);
  private readonly connectionsSignal = signal<readonly ConnectionRow[] | null>(null);
  private readonly tokensSignal = signal<ReadonlyMap<string, readonly TokenRow[]>>(new Map());
  private readonly oauthSignal = signal<readonly OAuthGrant[] | null>(null);
  private readonly runbooksSignal = signal<ReadonlyMap<string, number> | null>(null);
  private readonly busySignal = signal<ReadonlySet<string>>(new Set());
  private readonly startsSignal = signal<readonly EndpointStart[] | null>(null);

  /** The endpoints in the core's order; null until the first answer. */
  readonly rows = this.rowsSignal.asReadonly();
  /** Why the first load failed (nothing to show yet). */
  readonly error = this.errorSignal.asReadonly();
  /** Why the last reload failed while older cards are on screen. */
  readonly stale = this.staleSignal.asReadonly();
  readonly refreshing = this.refreshingSignal.asReadonly();
  /** Every connection with its health; null when the list didn't answer (the cards then count by the core). */
  readonly connections = this.connectionsSignal.asReadonly();
  /** The tokens of each endpoint that answered; an endpoint without an entry falls back to the core's counts. */
  readonly tokens = this.tokensSignal.asReadonly();
  /** The OAuth sign-ins (claude.ai and local OAuth clients); null until the core has them (K3). */
  readonly oauth = this.oauthSignal.asReadonly();
  /** Runbooks per SSH connection; null when the templates didn't answer. */
  readonly runbooks = this.runbooksSignal.asReadonly();
  /** Endpoints with an action in flight (Disable, Enable, Show warning): their items don't start a second one. */
  readonly busy = this.busySignal.asReadonly();
  /** What each way to start a new endpoint would give now (step API); null until it answered, or from an older core. */
  readonly starts = this.startsSignal.asReadonly();

  /** Asks the core what each Start from card would give now (the Create dialog opens). */
  async loadStarts(): Promise<void> {
    try {
      this.startsSignal.set(await this.api.starts());
    } catch {
      this.startsSignal.set(null);
    }
  }

  private inFlight: Promise<void> | null = null;
  private again: Depth | null = null;
  private stopped = false;
  private readonly unsubscribe: (() => void)[] = [];

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stop());
  }

  start(): Promise<void> {
    const list = () => void this.refresh('list');
    this.unsubscribe.push(
      this.events.on('approval.requested', list),
      this.events.on('approval.resolved', list),
      this.events.on('tunnel.state', list),
      this.events.on('integrity.alert', list),
      this.events.on('clients.drift', () => void this.refresh('full')),
      this.events.on('connection.health', (h) => this.onHealth(h)),
    );
    return this.refresh('full');
  }

  stop(): void {
    this.stopped = true;
    this.unsubscribe.splice(0).forEach((u) => u());
  }

  /** Reloads now; while one runs, one more follows it at the deepest depth asked for meanwhile. */
  refresh(depth: Depth = 'full'): Promise<void> {
    if (this.inFlight) {
      this.again = this.again === 'full' || depth === 'full' ? 'full' : 'list';
      return this.inFlight;
    }
    this.refreshingSignal.set(true);
    this.inFlight = this.load(depth).finally(() => {
      this.inFlight = null;
      this.refreshingSignal.set(false);
      const next = this.again;
      this.again = null;
      if (next && !this.stopped) void this.refresh(next);
    });
    return this.inFlight;
  }

  private async load(depth: Depth): Promise<void> {
    let rows: EndpointSummary[];
    try {
      rows = await this.api.list();
    } catch (e) {
      if (this.rowsSignal()) this.staleSignal.set(e);
      else this.errorSignal.set(e);
      return;
    }
    if (this.stopped) return;
    if (depth === 'full' || !this.rowsSignal()) await this.loadExtras(rows);
    if (this.stopped) return;
    this.rowsSignal.set(rows);
    this.errorSignal.set(null);
    this.staleSignal.set(null);
  }

  /** What the cards add to the core's counters; each part is left out (kept as it was) when it doesn't answer. */
  private async loadExtras(rows: readonly EndpointSummary[]): Promise<void> {
    const [connections, tokens, oauth, templates] = await Promise.all([
      this.connectionsApi.list().then((l) => l.items, () => null),
      Promise.all(rows.map((r) => this.tokensApi.list(r.slug).then((t) => [r.slug, t] as const, () => null))),
      this.oauthApi.grants().catch(() => null),
      this.templatesApi.list('ssh-runbook').catch(() => null),
    ]);
    if (this.stopped) return;
    this.connectionsSignal.set(connections);
    const map = new Map<string, readonly TokenRow[]>();
    for (const entry of tokens) if (entry) map.set(entry[0], entry[1]);
    this.tokensSignal.set(map);
    this.oauthSignal.set(oauth?.filter((g) => g.ingress === 'LOCAL') ?? null);
    if (templates) {
      const runbooks = new Map<string, number>();
      for (const t of templates) {
        if (t.connection && t.status === 'active') runbooks.set(t.connection, (runbooks.get(t.connection) ?? 0) + runbookCount(t.natures));
      }
      this.runbooksSignal.set(runbooks);
    } else {
      this.runbooksSignal.set(null);
    }
  }

  /** The active OAuth sign-ins of one endpoint (0 until the core reports any). */
  oauthSignIns(slug: string): number {
    return this.oauthSignal()?.filter((g) => g.endpoint === slug && g.state === 'active').length ?? 0;
  }

  /** A connection's health changed: its card says so at once (the core's counters follow on the next reload). */
  private onHealth(h: { readonly name: string; readonly state: string; readonly latencyMs?: number; readonly message: string | null; readonly checkedAt: string }): void {
    this.connectionsSignal.update((rows) =>
      rows ? rows.map((r) => (r.name === h.name ? { ...r, health: { state: h.state, latencyMs: h.latencyMs ?? null, message: h.message, checkedAt: h.checkedAt } } : r)) : rows,
    );
  }

  private replace(row: EndpointSummary): void {
    this.rowsSignal.update((rows) => (rows ? rows.map((r) => (r.slug === row.slug ? row : r)) : rows));
  }

  private async withBusy<T>(slug: string, run: () => Promise<T>): Promise<T> {
    this.busySignal.update((s) => new Set([...s, slug]));
    try {
      return await run();
    } finally {
      this.busySignal.update((s) => {
        const next = new Set(s);
        next.delete(slug);
        return next;
      });
    }
  }

  // ── Actions ───────────────────────────────────────────────────────────────────────────────────────────────────

  async create(slug: string, description: string | null, startFrom: StartFrom): Promise<EndpointSummary> {
    const row = await this.api.create({ slug, description, startFrom });
    void this.refresh('full');
    return row;
  }

  async duplicate(slug: string, newSlug: string): Promise<EndpointSummary> {
    const row = await this.api.duplicate(slug, newSlug);
    // The copy shows at once, after its source; the reload brings the order and the extras of the core.
    this.rowsSignal.update((rows) => {
      if (!rows || rows.some((r) => r.slug === row.slug)) return rows;
      const at = rows.findIndex((r) => r.slug === slug);
      return at < 0 ? [...rows, row] : [...rows.slice(0, at + 1), row, ...rows.slice(at + 1)];
    });
    void this.refresh('full');
    return row;
  }

  /** Disable is reversible: it acts at once, and Undo is {@link enable}. */
  disable(slug: string): Promise<EndpointSummary> {
    return this.withBusy(slug, async () => {
      const row = await this.api.disable(slug);
      this.replace(row);
      return row;
    });
  }

  enable(slug: string): Promise<EndpointSummary> {
    return this.withBusy(slug, async () => {
      const row = await this.api.enable(slug);
      this.replace(row);
      return row;
    });
  }

  /** Deletes (the dialog names it, so its slug is the confirmation the core asks for); the card goes at once. */
  async remove(slug: string): Promise<{ readonly clients: string[]; readonly tokensRevoked: string[]; readonly rejectedApprovals: number }> {
    const result = await this.api.delete(slug, slug);
    this.rowsSignal.update((rows) => (rows ? rows.filter((r) => r.slug !== slug) : rows));
    this.tokensSignal.update((m) => {
      const next = new Map(m);
      next.delete(slug);
      return next;
    });
    void this.refresh('full');
    return result;
  }

  /** The person reviewed an endpoint changed outside GateRook and keeps it (`confirm: reset-invalid` if asked). */
  async keep(slug: string, confirm: string | null): Promise<EndpointSummary> {
    const row = await this.api.confirmIntegrity(slug, confirm ? { confirm } : {});
    this.replace(row);
    void this.refresh('full');
    return row;
  }

  /** "Show warning" on a dismissed risk callout. */
  restoreRisk(slug: string): Promise<void> {
    return this.withBusy(slug, async () => {
      await this.api.restoreRisk(slug);
      await this.refresh('list');
    });
  }
}
