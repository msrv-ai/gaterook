import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { ActivityApi } from '../../core/api/activity.api';
import { ConnectionRow, ConnectionsApi } from '../../core/api/connections.api';
import { EndpointSummary, EndpointsApi } from '../../core/api/endpoints.api';
import { Notifications, NotificationsApi } from '../../core/api/notifications.api';
import { EnvPolicy, Policies, PoliciesApi } from '../../core/api/policies.api';
import { TemplatesApi } from '../../core/api/templates.api';
import { Env, JsonObject } from '../../core/api/types';
import { EventStream } from '../../core/events/event-stream';
import { TimeService } from '../../core/time/time.service';
import { Busiest, RunbookView, busiestOf, paramParts, runbookMode } from './policies-view';
import { ENVS, PoliciesByEnv, PolicySchemas } from './policy-model';

/** How often the meters age: a call leaves "the last minute" on its own. */
export const USAGE_REFRESH_MS = 15_000;
/** `audit.call` comes per audit row; a burst recounts once. */
const EVENT_DEBOUNCE_MS = 1_000;
/** The most calls of one environment the meters read per minute (`/api/activity/events` pages up to 500). */
const USAGE_PAGE = 500;

export type Usage = Readonly<Record<Env, { readonly token: Busiest | null; readonly tool: Busiest | null }>>;

/**
 * The Policies page's data (ARCHITECTURE.md §6 policies, §8.4): the three environment policies with their locks and
 * runbooks (`GET /api/policies`) and the section schemas (`GET /api/policies/schema`) are required; the rest is what
 * the page adds and is left out when it doesn't answer, nothing is made up:
 * - the connections (`/api/connections`): what each environment and section applies to;
 * - the endpoints (`/api/endpoints`): which ones mix private data, untrusted content and a channel out through the
 *   HTTP allowlist;
 * - each runbook's definition (`/api/templates/{id}`: command, parameters) and last run (`/api/activity/events`
 *   `tool:ssh_run connection:<c> runbook:<name>`);
 * - the use of the rate limits in the last minute, per environment (`/api/activity/events?q=env:<env>&from=`): the
 *   busiest token and tool × connection, refreshed by `audit.call` and as the minute moves on;
 * - the notification channels (`/api/notifications`, step K2: "not set up" lines; left out until it exists).
 *
 * Saving, resetting and keeping a row changed outside GateRook go through here and replace the environment with
 * the core's answer at once; the environments that inherit from it follow with a reload.
 */
@Injectable()
export class PoliciesData {
  private readonly api = inject(PoliciesApi);
  private readonly connectionsApi = inject(ConnectionsApi);
  private readonly endpointsApi = inject(EndpointsApi);
  private readonly templatesApi = inject(TemplatesApi);
  private readonly activityApi = inject(ActivityApi);
  private readonly notificationsApi = inject(NotificationsApi);
  private readonly events = inject(EventStream);
  private readonly time = inject(TimeService);

  private readonly policiesSignal = signal<PoliciesByEnv | null>(null);
  private readonly schemasSignal = signal<PolicySchemas | null>(null);
  private readonly errorSignal = signal<unknown>(null);
  private readonly staleSignal = signal<unknown>(null);
  private readonly refreshingSignal = signal(false);
  private readonly connectionsSignal = signal<readonly ConnectionRow[] | null>(null);
  private readonly endpointsSignal = signal<readonly EndpointSummary[] | null>(null);
  private readonly runbooksSignal = signal<readonly RunbookView[] | null>(null);
  private readonly usageSignal = signal<Usage | null>(null);
  private readonly notificationsSignal = signal<Notifications | null>(null);
  private readonly busySignal = signal<ReadonlySet<Env>>(new Set());

  /** The three environments; null until the first answer. */
  readonly policies = this.policiesSignal.asReadonly();
  readonly schemas = this.schemasSignal.asReadonly();
  /** Why the first load failed (nothing to show yet). */
  readonly error = this.errorSignal.asReadonly();
  /** Why the last reload failed while older values are on screen. */
  readonly stale = this.staleSignal.asReadonly();
  readonly refreshing = this.refreshingSignal.asReadonly();
  readonly connections = this.connectionsSignal.asReadonly();
  readonly endpoints = this.endpointsSignal.asReadonly();
  /** Runbooks of SSH connections; null when the templates didn't answer. */
  readonly runbooks = this.runbooksSignal.asReadonly();
  /** Rate-limit use in the last minute per environment; null until counted or when Activity doesn't answer. */
  readonly usage = this.usageSignal.asReadonly();
  /** The notification channels; null until the core has them (K2). */
  readonly notifications = this.notificationsSignal.asReadonly();
  /** Environments with a Save, Reset or Keep in flight. */
  readonly busy = this.busySignal.asReadonly();

  private inFlight: Promise<void> | null = null;
  private again = false;
  private stopped = false;
  private readonly unsubscribe: (() => void)[] = [];
  private usageTimer: ReturnType<typeof setInterval> | null = null;
  private usageDebounce: ReturnType<typeof setTimeout> | null = null;
  private runbookDebounce: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stop());
  }

  start(): Promise<void> {
    this.unsubscribe.push(
      // A policy row that doesn't verify any more (or verifies again) changes what the page must say.
      this.events.on('integrity.alert', (a) => {
        if (a.kind === 'policy') void this.refresh();
      }),
      this.events.on('audit.call', (call) => {
        if (call.phase !== 'received') return;
        this.debounceUsage();
        if (call.tool === 'ssh_run') this.debounceRunbooks();
      }),
    );
    this.usageTimer = setInterval(() => void this.loadUsage(), USAGE_REFRESH_MS);
    return this.refresh();
  }

  stop(): void {
    this.stopped = true;
    this.unsubscribe.splice(0).forEach((u) => u());
    if (this.usageTimer) clearInterval(this.usageTimer);
    if (this.usageDebounce) clearTimeout(this.usageDebounce);
    if (this.runbookDebounce) clearTimeout(this.runbookDebounce);
  }

  /** Reloads now; while one runs, one more follows it. */
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
    let policies: Policies;
    let schemas: PolicySchemas;
    try {
      [policies, schemas] = await Promise.all([
        this.api.all(),
        this.schemasSignal() ? Promise.resolve(this.schemasSignal()!) : (this.api.schema() as unknown as Promise<PolicySchemas>),
      ]);
    } catch (e) {
      if (this.policiesSignal()) this.staleSignal.set(e);
      else this.errorSignal.set(e);
      return;
    }
    if (this.stopped) return;
    const [connections, endpoints, notifications] = await Promise.all([
      this.connectionsApi.list().then((l) => l.items, () => null),
      this.endpointsApi.list().catch(() => null),
      this.notificationsApi.get().catch(() => null),
    ]);
    if (this.stopped) return;
    this.connectionsSignal.set(connections);
    this.endpointsSignal.set(endpoints);
    this.notificationsSignal.set(notifications);
    this.schemasSignal.set(schemas);
    this.policiesSignal.set({ dev: policies.dev, stage: policies.stage, prod: policies.prod });
    this.errorSignal.set(null);
    this.staleSignal.set(null);
    void this.loadRunbooks();
    void this.loadUsage();
  }

  // ── Runbooks ─────────────────────────────────────────────────────────────────────────────────────────────────

  private debounceRunbooks(): void {
    if (this.runbookDebounce) clearTimeout(this.runbookDebounce);
    this.runbookDebounce = setTimeout(() => void this.loadRunbooks(), EVENT_DEBOUNCE_MS);
  }

  /**
   * The runbooks the policies name, with their command and parameters (the template's current definition) and when
   * each ran last. A runbook whose template doesn't answer keeps its name and mode; a missing last run is "Never".
   */
  private async loadRunbooks(): Promise<void> {
    const policies = this.policiesSignal();
    if (!policies) return;
    const listed = ENVS.flatMap((e) => policies[e].runbooks);
    if (!listed.length) {
      this.runbooksSignal.set([]);
      return;
    }
    const templates = await this.templatesApi.list('ssh-runbook').catch(() => null);
    const views = await Promise.all(
      listed.map(async (r) => {
        const [detail, last] = await Promise.all([
          this.templatesApi.get(r.template).catch(() => null),
          r.lastRunAt
            ? Promise.resolve(r.lastRunAt)
            : this.activityApi
                .events({ q: `tool:ssh_run connection:${r.connection} runbook:${r.name}`, limit: 1 })
                .then((p) => p.events[0]?.at ?? null, () => null),
        ]);
        const definition = (detail?.definition ?? null) as JsonObject | null;
        const natures = detail?.natures ?? templates?.find((x) => x.id === r.template)?.natures;
        const view: RunbookView = {
          id: r.template,
          name: typeof definition?.['name'] === 'string' ? (definition['name'] as string) : r.name,
          connection: r.connection,
          description: typeof definition?.['description'] === 'string' ? (definition['description'] as string) : null,
          command: typeof definition?.['command'] === 'string' ? (definition['command'] as string) : null,
          params: definition ? paramParts(definition) : [],
          mode: runbookMode(natures, definition),
          status: r.status,
          lastRunAt: last,
        };
        return view;
      }),
    );
    if (!this.stopped) this.runbooksSignal.set(views);
  }

  // ── Usage ────────────────────────────────────────────────────────────────────────────────────────────────────

  private debounceUsage(): void {
    if (this.usageDebounce) return;
    this.usageDebounce = setTimeout(() => {
      this.usageDebounce = null;
      void this.loadUsage();
    }, EVENT_DEBOUNCE_MS);
  }

  /** The busiest token and tool × connection of each environment over the last minute of the core's clock. */
  private async loadUsage(): Promise<void> {
    if (!this.policiesSignal() || this.stopped) return;
    const from = new Date(this.time.now() - 60_000).toISOString();
    const pages = await Promise.all(
      ENVS.map((env) => this.activityApi.events({ q: `env:${env}`, from, limit: USAGE_PAGE }).catch(() => null)),
    );
    if (this.stopped) return;
    if (pages.some((p) => !p)) {
      // Counting from a part would show a quiet minute where there may be a busy one: say nothing instead.
      this.usageSignal.set(null);
      return;
    }
    const usage = {} as Record<Env, { token: Busiest | null; tool: Busiest | null }>;
    ENVS.forEach((env, i) => {
      usage[env] = busiestOf(pages[i]!.events.map((e) => ({ token: e.tokenLabel, tool: e.tool, connection: e.connection })));
    });
    this.usageSignal.set(usage);
  }

  // ── Actions ──────────────────────────────────────────────────────────────────────────────────────────────────

  private replace(p: EnvPolicy): void {
    this.policiesSignal.update((all) => (all ? { ...all, [p.env]: p } : all));
  }

  private async withBusy<T>(env: Env, run: () => Promise<T>): Promise<T> {
    this.busySignal.update((s) => new Set([...s, env]));
    try {
      return await run();
    } finally {
      this.busySignal.update((s) => {
        const next = new Set(s);
        next.delete(env);
        return next;
      });
    }
  }

  /** `PUT /api/policies/{env}` with the keys that change; `confirm: "danger"` when the person confirmed `ssh_exec`. */
  save(env: Env, sections: JsonObject, version: number, confirm: string | null): Promise<EnvPolicy> {
    return this.withBusy(env, async () => {
      const p = await this.api.update(env, { sections, version, ...(confirm ? { confirm } : {}) });
      this.replace(p);
      // Stage inherits dev: its effective values and hints change with it.
      void this.refresh();
      return p;
    });
  }

  reset(env: Env): Promise<EnvPolicy> {
    return this.withBusy(env, async () => {
      const p = await this.api.reset(env);
      this.replace(p);
      void this.refresh();
      return p;
    });
  }

  /** The person reviewed a policy changed outside GateRook and keeps it as stored. */
  keep(env: Env, confirm: string | null): Promise<EnvPolicy> {
    return this.withBusy(env, async () => {
      const p = await this.api.confirmIntegrity(env, confirm ? { confirm } : {});
      this.replace(p);
      void this.refresh();
      return p;
    });
  }
}
