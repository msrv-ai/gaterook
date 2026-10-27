import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { isNotAvailable } from '../../core/api/api-error';
import { ClientCard, ClientsApi } from '../../core/api/clients.api';
import { ConnectionHealth, ConnectionsApi } from '../../core/api/connections.api';
import { Insights, InsightsApi } from '../../core/api/insights.api';
import { PoliciesApi } from '../../core/api/policies.api';
import { Env } from '../../core/api/types';
import { EventStream } from '../../core/events/event-stream';
import { TimeService } from '../../core/time/time.service';
import { InsightsRangeKey, approvalWindows, rangeQuery } from './insights-view';

/**
 * How soon a refresh follows an event. Insights are sums over many calls: a busy agent makes many, and nothing on
 * this page needs to move with each one, so calls refresh the numbers every 15 s at most; a request coming or going
 * changes "Plus 3 waiting" and the approvals a little sooner.
 */
const DELAY = { approval: 2_000, call: 15_000 } as const;

/** What one read is for: the page's query and range. */
export interface InsightsFilter {
  readonly q: string;
  readonly key: InsightsRangeKey;
}

/**
 * Insights' data (ARCHITECTURE.md §6 insights, §8.4): `GET /api/insights` for the page's query and range, plus what
 * its cards say about things the audit doesn't know — the MCP clients on this computer without calls (`clients`,
 * K1), each failing connection's health now (`connections`) and the approval windows of the environments
 * (`policies`). Only Insights itself is required: another part that fails is left out, never filled in.
 *
 * Until step K5 the core has no Insights route (404 without a problem body): `notAvailable` turns on and events stop
 * asking (a new filter or a restarted core asks once more). Events refresh the numbers, coalesced: one read in flight,
 * one more after it when events came meanwhile; an answer for a query or range the page has left is dropped.
 */
@Injectable()
export class InsightsData {
  private readonly api = inject(InsightsApi);
  private readonly clientsApi = inject(ClientsApi);
  private readonly connectionsApi = inject(ConnectionsApi);
  private readonly policiesApi = inject(PoliciesApi);
  private readonly events = inject(EventStream);
  private readonly time = inject(TimeService);

  private readonly insightsSignal = signal<Insights | null>(null);
  private readonly loadedSignal = signal<InsightsFilter | null>(null);
  private readonly errorSignal = signal<unknown>(null);
  private readonly staleSignal = signal<unknown>(null);
  private readonly notAvailableSignal = signal(false);
  private readonly refreshingSignal = signal(false);
  private readonly cardsSignal = signal<readonly ClientCard[] | null>(null);
  private readonly healthSignal = signal<ReadonlyMap<string, ConnectionHealth>>(new Map());
  private readonly windowsSignal = signal<Partial<Record<Env, number>> | null>(null);

  /** The last answer; null until the first one. */
  readonly insights = this.insightsSignal.asReadonly();
  /** The query and range the shown answer is for. */
  readonly loaded = this.loadedSignal.asReadonly();
  /** Why the read for the current filter failed while nothing for it is shown. */
  readonly error = this.errorSignal.asReadonly();
  /** Why the last refresh failed while the numbers on screen are for the current filter (they may be out of date). */
  readonly stale = this.staleSignal.asReadonly();
  /** This core has no Insights yet (step K5 not merged). */
  readonly notAvailable = this.notAvailableSignal.asReadonly();
  readonly refreshing = this.refreshingSignal.asReadonly();
  /** The MCP clients this computer has; null when the clients area didn't answer. */
  readonly cards = this.cardsSignal.asReadonly();
  /** Health now of each connection, by name (Errors by connection). */
  readonly health = this.healthSignal.asReadonly();
  /** Approval windows in minutes by environment; null when the policies didn't answer. */
  readonly windows = this.windowsSignal.asReadonly();

  private filter: InsightsFilter = { q: '', key: '24h' };
  private inFlight: Promise<void> | null = null;
  private again = false;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private dueAt = Number.POSITIVE_INFINITY;
  private stopped = true;
  private readonly unsubscribe: (() => void)[] = [];
  /** Clients and policies change rarely: read once per page, again after the core restarts. */
  private sidesLoaded = false;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stop());
  }

  /** Loads the filter's numbers and follows the core's events until the page goes away. */
  start(filter: InsightsFilter): Promise<void> {
    this.filter = filter;
    this.stopped = false;
    this.unsubscribe.push(
      this.events.on('audit.call', () => this.schedule(DELAY.call)),
      this.events.on('approval.requested', () => this.schedule(DELAY.approval)),
      this.events.on('approval.resolved', () => this.schedule(DELAY.approval)),
      this.events.on('connection.health', (h) => {
        // Health now is shown as it is: no need to wait for the next read.
        this.healthSignal.update((m) => new Map(m).set(h.name, { state: h.state, latencyMs: h.latencyMs ?? null, message: h.message, checkedAt: h.checkedAt }));
      }),
      this.events.on('clients.drift', () => {
        this.sidesLoaded = false;
        this.schedule(DELAY.approval);
      }),
    );
    return this.refresh();
  }

  stop(): void {
    this.stopped = true;
    this.unsubscribe.splice(0).forEach((u) => u());
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
  }

  /** A new query or range: read it now (what is on screen stays until the answer comes). */
  setFilter(filter: InsightsFilter): Promise<void> {
    if (filter.q === this.filter.q && filter.key === this.filter.key) return this.inFlight ?? Promise.resolve();
    this.filter = filter;
    return this.refresh();
  }

  /** The core came back or another instance answers: everything again, Insights included even if it was missing. */
  reconnect(): Promise<void> {
    this.notAvailableSignal.set(false);
    this.sidesLoaded = false;
    return this.refresh();
  }

  /** A refresh within `ms`; an earlier one already planned stands. */
  schedule(ms: number): void {
    if (this.stopped || this.notAvailableSignal()) return;
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

  /** Reads now; while one runs, one more follows it (the filter or the calls may have changed meanwhile). */
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
    const filter = this.filter;
    const sides = this.sidesLoaded ? Promise.resolve() : this.loadSides();
    let answer: Insights;
    try {
      [answer] = await Promise.all([this.api.get({ q: filter.q || null, ...rangeQuery(filter.key, this.time.now()) }), this.loadHealth()]);
    } catch (e) {
      await sides;
      if (this.stopped) return;
      if (isNotAvailable(e)) {
        this.notAvailableSignal.set(true);
        this.errorSignal.set(null);
        this.staleSignal.set(null);
        return;
      }
      // An answer for the current filter on screen may be out of date; one for another filter isn't the answer.
      if (this.insightsSignal() && same(this.loadedSignal(), this.filter)) this.staleSignal.set(e);
      else this.errorSignal.set(e);
      return;
    }
    await sides;
    if (this.stopped || !same(filter, this.filter)) return;
    this.insightsSignal.set(answer);
    this.loadedSignal.set(filter);
    this.notAvailableSignal.set(false);
    this.errorSignal.set(null);
    this.staleSignal.set(null);
  }

  /** Each connection's health now; a failed read keeps what was known. */
  private async loadHealth(): Promise<void> {
    try {
      const list = await this.connectionsApi.list();
      this.healthSignal.set(new Map(list.items.map((r) => [r.name, r.health])));
    } catch {
      // Health is a hint next to the errors: without it the column says "Not checked yet".
    }
  }

  private async loadSides(): Promise<void> {
    const [cards, policies] = await Promise.all([
      this.clientsApi.list().then(
        (v) => v.clients,
        () => null,
      ),
      this.policiesApi.all().then(
        (p) => approvalWindows(p as unknown as { readonly [env: string]: { readonly sections?: unknown } }),
        () => null,
      ),
    ]);
    if (this.stopped) return;
    this.cardsSignal.set(cards);
    this.windowsSignal.set(policies);
    this.sidesLoaded = true;
  }
}

function same(a: InsightsFilter | null, b: InsightsFilter): boolean {
  return !!a && a.q === b.q && a.key === b.key;
}
