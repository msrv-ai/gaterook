import { DestroyRef, Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { ActivityApi, ActivityEvent, EventDetail } from '../../core/api/activity.api';
import { ConnectionRow, ConnectionsApi } from '../../core/api/connections.api';
import { EndpointSummary, EndpointsApi, ToolGroup } from '../../core/api/endpoints.api';
import { Era, PlaygroundApi, PlaygroundCallView, PlaygroundTools } from '../../core/api/playground.api';
import { PoliciesApi } from '../../core/api/policies.api';
import { Env, JsonObject } from '../../core/api/types';
import { EventStream } from '../../core/events/event-stream';
import { EndpointsStore } from '../../core/stores/endpoints.store';
import { TimeService } from '../../core/time/time.service';
import { HISTORY_SIZE } from './playground-view';

/** One run of a tool from this page: the core's answer and what it was asked. */
export interface PlaygroundRun {
  readonly call: PlaygroundCallView;
  readonly endpoint: string;
  /** The endpoint's URL (`http://127.0.0.1:7465/mcp/dev`): the request line and Host of the JSON-RPC tab. */
  readonly url: string | null;
  readonly tool: string;
  /** The `connection` the call named; null for the endpoint's own tools. */
  readonly connection: string | null;
  readonly env: Env | null;
  readonly era: Era;
  /** When it came back, on the core's clock. */
  readonly at: string;
}

/** The tools of one endpoint: what the Playground can call there and each pair's mode. */
export interface EndpointTools {
  readonly slug: string;
  readonly tools: PlaygroundTools;
  readonly groups: readonly ToolGroup[];
  /** The MCP revisions the endpoint answers (Settings › Protocol); null when the settings didn't load. */
  readonly revisions: readonly string[] | null;
}

export type RunOutcome = { readonly ok: true; readonly run: PlaygroundRun } | { readonly ok: false; readonly error: unknown };

/** An approval request a run of this page made, until a person decides it. */
export interface PendingApproval {
  readonly handle: string;
  readonly tool: string;
  readonly connection: string | null;
  readonly env: Env | null;
}

/** Calls of the page come back as `audit.call` too; one history reload this long after the last of a burst. */
export const HISTORY_RELOAD_MS = 300;

/**
 * The Playground's data (ARCHITECTURE.md §6 playground, §8.4): the endpoints for the select (`EndpointsStore`), the
 * connections (type name and host of each picker group), one endpoint's tools — `GET /api/playground/tools` (schema,
 * whether Run works and why not), `GET /api/endpoints/{slug}/tools` (the mode of every tool × connection pair, the
 * picker's groups) and its settings (the revisions it answers) —, the prod policy's comment rule for the prod
 * warning, the history, and Run through the same pipeline as an agent (`POST /api/playground/call`).
 *
 * Live: this page's calls (and those of the Playground in another window) arrive as `audit.call` and reload the
 * history; `approval.requested` while a run waits says the request was sent; `approval.resolved` of this page's
 * request asks `approval_status` for the outcome, as an agent would, and that answer becomes the last run.
 */
@Injectable()
export class PlaygroundData {
  private readonly api = inject(PlaygroundApi);
  private readonly endpointsApi = inject(EndpointsApi);
  private readonly connectionsApi = inject(ConnectionsApi);
  private readonly policiesApi = inject(PoliciesApi);
  private readonly activity = inject(ActivityApi);
  private readonly store = inject(EndpointsStore);
  private readonly events = inject(EventStream);
  private readonly time = inject(TimeService);

  private readonly endpointsLoaded = signal(false);
  private readonly connectionsSignal = signal<readonly ConnectionRow[]>([]);
  private readonly slugSignal = signal<string | null>(null);
  private readonly toolsSignal = signal<EndpointTools | null>(null);
  private readonly toolsErrorSignal = signal<unknown>(null);
  private readonly commentSignal = signal<boolean | null>(null);
  private readonly historySignal = signal<readonly ActivityEvent[] | null>(null);
  private readonly historyErrorSignal = signal<unknown>(null);
  private readonly runningSignal = signal<{ readonly tool: string; readonly connection: string | null } | null>(null);
  private readonly lastRunSignal = signal<PlaygroundRun | null>(null);
  private readonly pendingSignal = signal<PendingApproval | null>(null);
  private readonly requestSentSignal = signal<{ readonly id: string; readonly tool: string; readonly connection: string | null } | null>(null);
  private readonly durations = new Map<string, number>();
  private readonly eventCache = new Map<string, EventDetail>();

  /** The endpoints for the select; null until the first answer. */
  readonly endpoints = computed<readonly EndpointSummary[] | null>(() => (this.endpointsLoaded() ? this.store.list() : null));
  /** Why the endpoints didn't load (nothing to show yet). */
  readonly endpointsError = computed(() => (this.endpointsLoaded() && !this.store.list() ? this.store.error() : null));
  readonly connections = this.connectionsSignal.asReadonly();
  readonly slug = this.slugSignal.asReadonly();
  /** The open endpoint's tools; null while they load. */
  readonly tools = this.toolsSignal.asReadonly();
  readonly toolsError = this.toolsErrorSignal.asReadonly();
  /** Whether approving on the open endpoint's environment needs a comment (its policy); null when unknown. */
  readonly commentRequired = this.commentSignal.asReadonly();
  readonly history = this.historySignal.asReadonly();
  readonly historyError = this.historyErrorSignal.asReadonly();
  /** A run in flight. */
  readonly running = this.runningSignal.asReadonly();
  readonly lastRun = this.lastRunSignal.asReadonly();
  /** The approval request of this page's last run, until it is decided. */
  readonly pending = this.pendingSignal.asReadonly();
  /** `approval.requested` of a run in flight: the page tells the person the request was sent. */
  readonly requestSent = this.requestSentSignal.asReadonly();

  readonly summary = computed<EndpointSummary | null>(() => this.endpoints()?.find((e) => e.slug === this.slugSignal()) ?? null);
  /** The revision a call is sent in: 2026-07-28 unless the endpoint answers only the older one. */
  readonly era = computed<Era>(() => {
    const revisions = this.toolsSignal()?.revisions;
    return revisions && !revisions.includes('2026-07-28') && revisions.includes('2025-11-25') ? '2025-11-25' : '2026-07-28';
  });

  private historyTimer: ReturnType<typeof setTimeout> | null = null;
  private loadSeq = 0;
  private readonly unsubscribe: (() => void)[] = [];

  constructor() {
    const destroyRef = inject(DestroyRef);
    destroyRef.onDestroy(() => this.stop());
    this.unsubscribe.push(
      this.events.on('audit.call', (e) => {
        if (e.endpoint === this.slugSignal() && e.client === 'playground') this.scheduleHistory();
      }),
      this.events.on('approval.requested', (e) => {
        const running = this.runningSignal();
        if (running && e.endpoint === this.slugSignal() && e.tool === running.tool) {
          this.requestSentSignal.set({ id: e.id, tool: e.tool, connection: e.connection });
        }
      }),
      this.events.on('approval.resolved', (e) => {
        if (this.pendingSignal()?.handle === e.id) void this.follow(e.id);
      }),
    );
    // The event stream came back (the core restarted, the network blinked): what it missed is read again.
    effect(() => {
      if (this.events.reopened() > 0) untracked(() => void this.refresh());
    });
  }

  /** The endpoints and connections (once), then the endpoint the page opens. */
  async start(): Promise<void> {
    await Promise.all([this.loadEndpoints(), this.loadConnections()]);
  }

  async loadEndpoints(): Promise<void> {
    await this.store.load();
    this.endpointsLoaded.set(true);
  }

  private async loadConnections(): Promise<void> {
    try {
      this.connectionsSignal.set((await this.connectionsApi.list()).items);
    } catch {
      // The picker's groups then name only the connector type: the tools still work.
      this.connectionsSignal.set([]);
    }
  }

  /** Opens one endpoint: its tools, pairs, revisions, the policy's comment rule and the history. */
  async open(slug: string): Promise<void> {
    const changed = this.slugSignal() !== slug;
    if (changed) {
      this.slugSignal.set(slug);
      this.toolsSignal.set(null);
      this.historySignal.set(null);
      this.historyErrorSignal.set(null);
      this.commentSignal.set(null);
    }
    this.toolsErrorSignal.set(null);
    await Promise.all([this.loadTools(slug), this.reloadHistory(), this.loadCommentRule()]);
  }

  /** Try again after a failed load of the tools. */
  retryTools(): Promise<void> {
    const slug = this.slugSignal();
    return slug ? this.open(slug) : Promise.resolve();
  }

  private async loadTools(slug: string): Promise<void> {
    const seq = ++this.loadSeq;
    try {
      const [tools, groups, revisions] = await Promise.all([
        this.api.tools(slug),
        this.endpointsApi.tools(slug),
        this.endpointsApi
          .settings(slug)
          .then((s) => {
            const r = s.protocol?.['revisions'];
            return Array.isArray(r) ? r.filter((x): x is string => typeof x === 'string') : null;
          })
          .catch(() => null),
      ]);
      if (seq !== this.loadSeq || this.slugSignal() !== slug) return;
      this.toolsSignal.set({ slug, tools, groups, revisions });
    } catch (e) {
      if (seq !== this.loadSeq || this.slugSignal() !== slug) return;
      this.toolsErrorSignal.set(e);
    }
  }

  /** "On prod, approving needs a comment": only the prod warning says it, so only prod asks. */
  private async loadCommentRule(): Promise<void> {
    const env = this.summary()?.env;
    if (env !== 'prod') return;
    try {
      const policy = await this.policiesApi.get(env);
      const approvals = policy.sections['approvals'];
      const comment = approvals && typeof approvals === 'object' && !Array.isArray(approvals) ? approvals['comment'] : null;
      this.commentSignal.set(comment === 'required');
    } catch {
      this.commentSignal.set(null);
    }
  }

  async reloadHistory(): Promise<void> {
    const slug = this.slugSignal();
    if (!slug) return;
    try {
      const page = await this.api.history(slug, null, HISTORY_SIZE);
      if (this.slugSignal() !== slug) return;
      this.historySignal.set(page.events);
      this.historyErrorSignal.set(null);
    } catch (e) {
      if (this.slugSignal() === slug) this.historyErrorSignal.set(e);
    }
  }

  private scheduleHistory(): void {
    if (this.historyTimer) clearTimeout(this.historyTimer);
    this.historyTimer = setTimeout(() => {
      this.historyTimer = null;
      void this.reloadHistory();
    }, HISTORY_RELOAD_MS);
  }

  /** After the event stream came back: the endpoints, the open endpoint's tools and the history. */
  private async refresh(): Promise<void> {
    await this.loadEndpoints();
    const slug = this.slugSignal();
    if (slug) await Promise.all([this.loadTools(slug), this.reloadHistory()]);
  }

  /**
   * Runs one tool as an agent would (principal `playground`, the endpoint's era). A mutation comes back as an
   * approval request (`approval.status` pending): the page follows it until a person decides.
   */
  async run(request: { readonly tool: string; readonly arguments: JsonObject; readonly connection: string | null; readonly env: Env | null }): Promise<RunOutcome> {
    const slug = this.slugSignal();
    if (!slug || this.runningSignal()) return { ok: false, error: new Error('busy') };
    const era = this.era();
    this.runningSignal.set({ tool: request.tool, connection: request.connection });
    this.requestSentSignal.set(null);
    try {
      const call = await this.api.call({ endpoint: slug, tool: request.tool, arguments: request.arguments, era });
      const run: PlaygroundRun = {
        call,
        endpoint: slug,
        url: this.summary()?.url ?? null,
        tool: request.tool,
        connection: request.connection,
        env: request.env,
        era,
        at: new Date(this.time.now()).toISOString(),
      };
      this.remember(run);
      const approval = call.approval;
      this.pendingSignal.set(
        approval && (approval.status === 'pending' || approval.status === 'running')
          ? { handle: approval.handle, tool: request.tool, connection: request.connection, env: request.env }
          : null,
      );
      return { ok: true, run };
    } catch (error) {
      return { ok: false, error };
    } finally {
      this.runningSignal.set(null);
      void this.reloadHistory();
    }
  }

  /**
   * A person decided the request of this page's run: `approval_status` brings the outcome as the agent would get it
   * (the result of the approved call, or "Rejected by you…"), and that answer is the last run now.
   */
  private async follow(handle: string): Promise<void> {
    const pending = this.pendingSignal();
    const slug = this.slugSignal();
    if (!pending || pending.handle !== handle || !slug) return;
    this.pendingSignal.set(null);
    const era = this.era();
    try {
      const call = await this.api.call({ endpoint: slug, tool: 'approval_status', arguments: { handle }, era });
      this.remember({ call, endpoint: slug, url: this.summary()?.url ?? null, tool: 'approval_status', connection: null, env: pending.env, era, at: new Date(this.time.now()).toISOString() });
    } catch {
      // The decision stays visible in Approvals and Activity; the last run keeps the pending answer.
    }
    void this.reloadHistory();
  }

  private remember(run: PlaygroundRun): void {
    if (run.call.callId) this.durations.set(run.call.callId, run.call.meta.durationMs);
    this.lastRunSignal.set(run);
  }

  /** Durations this page measured, by call id (the audit has none for a denied call). */
  measured(): ReadonlyMap<string, number> {
    return this.durations;
  }

  /** The audit event of a call (event details of the last run): its row in the history, then the event. */
  async eventFor(callId: string): Promise<EventDetail | null> {
    const cached = this.eventCache.get(callId);
    if (cached) return cached;
    let event = this.historySignal()?.find((e) => e.callId === callId) ?? null;
    if (!event) {
      await this.reloadHistory();
      event = this.historySignal()?.find((e) => e.callId === callId) ?? null;
    }
    if (!event) return null;
    const detail = await this.activity.event(event.id);
    this.eventCache.set(callId, detail);
    return detail;
  }

  /** The history row of a call, once the audit wrote it. */
  eventOf(callId: string | null): ActivityEvent | null {
    return callId ? (this.historySignal()?.find((e) => e.callId === callId) ?? null) : null;
  }

  private stop(): void {
    for (const u of this.unsubscribe) u();
    this.unsubscribe.length = 0;
    if (this.historyTimer) clearTimeout(this.historyTimer);
  }
}
