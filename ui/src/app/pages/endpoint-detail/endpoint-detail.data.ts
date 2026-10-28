import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { ActivityApi } from '../../core/api/activity.api';
import { isApiError } from '../../core/api/api-error';
import { ClientId, ClientsApi, ClientsView, ConnectPreview, ConnectRequest, ConnectResult, DisconnectResult } from '../../core/api/clients.api';
import { ConnectionRow, ConnectionsApi, nextHealth } from '../../core/api/connections.api';
import { ConnectorTypesApi } from '../../core/api/connector-types.api';
import {
  ConnectionPreview,
  EndpointCheck,
  EndpointDetail,
  EndpointRisk,
  EndpointSettings,
  EndpointSettingsUpdate,
  EndpointSummary,
  EndpointsApi,
  IssuedToken,
  SplitPreview,
  ToolGroup,
  TokenRow,
  TokensApi,
} from '../../core/api/endpoints.api';
import { OAuthApi, OAuthGrant } from '../../core/api/tunnel.api';
import { Policies, PoliciesApi } from '../../core/api/policies.api';
import { SettingsApi } from '../../core/api/settings.api';
import { JsonObject, ToolMode } from '../../core/api/types';
import { EventStream } from '../../core/events/event-stream';
import { EndpointsStore } from '../../core/stores/endpoints.store';

/** The core's own tools have no connector type to describe them: the kit's sentences (endpoint-detail "Built-in tools"). */
export function builtInDescriptions(slug: string): Record<string, string> {
  return {
    list_connections: `List the connections of ${slug} with their type and environment. No credentials.`,
    describe_connection: 'Type, environment and allowed tools of one connection.',
  };
}

/**
 * Calls of the endpoint come in as `audit.call`; tokens' and clients' "last used", the usage meter and the waiting
 * count follow them. One reload this long after the last of a burst is enough (agents call many times a second).
 */
export const LIVE_RELOAD_MS = 2_000;

/** A part of the page that a 404 without a problem body leaves "not available yet" (a step not merged). */
export type Availability = 'loading' | 'ready' | 'not-available' | 'failed';

/**
 * The endpoint page's data (ARCHITECTURE.md §6 endpoint-detail, §8.4): `GET /api/endpoints/{slug}` (header, wiring,
 * risk, settings), `/tools`, `/tokens`, the clients of this computer (K1), the connections list (host, latency, the
 * ones not on the endpoint yet), the environments' policies (inherited limits), the connector types' tool
 * descriptions, and `POST …/check` (the default instructions' length). Live: approvals, health, calls and client
 * drift arrive as events and reload what they change.
 *
 * The page asks; this service calls the core and keeps the answers. Every action returns what the core said, so the
 * page can toast and focus; it reloads what the action changed.
 */
@Injectable()
export class EndpointDetailData {
  private readonly endpoints = inject(EndpointsApi);
  private readonly tokensApi = inject(TokensApi);
  private readonly clientsApi = inject(ClientsApi);
  private readonly connectionsApi = inject(ConnectionsApi);
  private readonly typesApi = inject(ConnectorTypesApi);
  private readonly policiesApi = inject(PoliciesApi);
  private readonly activityApi = inject(ActivityApi);
  private readonly oauthApi = inject(OAuthApi);
  private readonly settingsApi = inject(SettingsApi);
  private readonly events = inject(EventStream);
  private readonly store = inject(EndpointsStore);

  private readonly slugSignal = signal<string | null>(null);
  private readonly detailSignal = signal<EndpointDetail | null>(null);
  private readonly groupsSignal = signal<readonly ToolGroup[] | null>(null);
  private readonly tokensSignal = signal<readonly TokenRow[] | null>(null);
  private readonly clientsSignal = signal<ClientsView | null>(null);
  private readonly clientsStateSignal = signal<Availability>('loading');
  private readonly connectionsSignal = signal<readonly ConnectionRow[] | null>(null);
  private readonly policiesSignal = signal<Policies | null>(null);
  private readonly descriptionsSignal = signal<Readonly<Record<string, string>>>({});
  private readonly checkSignal = signal<EndpointCheck | null>(null);
  private readonly grantsSignal = signal<readonly OAuthGrant[] | null>(null);
  private readonly grantsStateSignal = signal<Availability>('loading');
  private readonly authorizationSignal = signal<JsonObject | null>(null);
  private readonly errorSignal = signal<unknown>(null);
  private readonly staleSignal = signal<unknown>(null);
  private readonly notFoundSignal = signal(false);
  private readonly refreshingSignal = signal(false);

  readonly slug = this.slugSignal.asReadonly();
  readonly detail = this.detailSignal.asReadonly();
  readonly groups = this.groupsSignal.asReadonly();
  readonly tokens = this.tokensSignal.asReadonly();
  readonly clients = this.clientsSignal.asReadonly();
  /** `not-available` until the clients step answers (K1); the tab says so instead of an error. */
  readonly clientsState = this.clientsStateSignal.asReadonly();
  readonly connections = this.connectionsSignal.asReadonly();
  readonly policies = this.policiesSignal.asReadonly();
  readonly descriptions = this.descriptionsSignal.asReadonly();
  readonly check = this.checkSignal.asReadonly();
  /** The OAuth sign-ins of this endpoint (K3); `not-available` until that step answers. */
  readonly grants = this.grantsSignal.asReadonly();
  readonly grantsState = this.grantsStateSignal.asReadonly();
  /** Settings › Authorization (client registration for the OAuth key-values). */
  readonly authorization = this.authorizationSignal.asReadonly();
  /** Why the first load failed (nothing to show). */
  readonly error = this.errorSignal.asReadonly();
  /** Why the last reload failed while older data is on screen. */
  readonly stale = this.staleSignal.asReadonly();
  /** The core has no endpoint with this slug (deleted, renamed by a split, a stale link). */
  readonly notFound = this.notFoundSignal.asReadonly();
  readonly refreshing = this.refreshingSignal.asReadonly();

  readonly summary = computed<EndpointSummary | null>(() => this.detailSignal()?.endpoint ?? null);
  readonly settings = computed<EndpointSettings | null>(() => this.detailSignal()?.settings ?? null);
  readonly risk = computed<EndpointRisk | null>(() => this.detailSignal()?.endpoint.risk ?? null);

  private inFlight: Promise<void> | null = null;
  private again = false;
  private stopped = false;
  private liveTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly unsubscribe: (() => void)[] = [];
  private readonly typeCache = new Map<string, Record<string, string>>();

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stop());
  }

  /** Starts on one endpoint (and again when the route's slug changes: a split's new endpoint, a link). */
  open(slug: string): Promise<void> {
    if (this.slugSignal() !== slug) {
      this.slugSignal.set(slug);
      this.detailSignal.set(null);
      this.groupsSignal.set(null);
      this.tokensSignal.set(null);
      this.checkSignal.set(null);
      this.grantsSignal.set(null);
      this.grantsStateSignal.set('loading');
      this.errorSignal.set(null);
      this.staleSignal.set(null);
      this.notFoundSignal.set(false);
    }
    if (!this.unsubscribe.length) this.listen();
    return this.refresh();
  }

  stop(): void {
    this.stopped = true;
    this.unsubscribe.splice(0).forEach((u) => u());
    if (this.liveTimer) clearTimeout(this.liveTimer);
  }

  private listen(): void {
    const mine = (endpoint: string | null | undefined) => !!endpoint && endpoint === this.slugSignal();
    this.unsubscribe.push(
      this.events.on('approval.requested', (e) => {
        if (mine(e.endpoint)) void this.reloadDetail();
      }),
      // A resolved request doesn't name its endpoint: the waiting count is re-read either way.
      this.events.on('approval.resolved', () => void this.reloadDetail()),
      this.events.on('connection.health', (h) => this.onHealth(h)),
      this.events.on('audit.call', (c) => {
        if (mine(c.endpoint)) this.scheduleLive();
      }),
      this.events.on('clients.drift', () => void this.reloadClients()),
    );
  }

  /** Reloads everything now; while one runs, one more follows it (a change that came meanwhile is in neither). */
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
    const slug = this.slugSignal();
    if (!slug) return;
    let detail: EndpointDetail;
    let groups: ToolGroup[];
    try {
      [detail, groups] = await Promise.all([this.endpoints.get(slug), this.endpoints.tools(slug)]);
    } catch (e) {
      if (this.slugSignal() !== slug) return;
      if (isApiError(e) && e.kind === 'not-found' && e.problem) {
        this.notFoundSignal.set(true);
        this.errorSignal.set(null);
      } else if (this.detailSignal()) this.staleSignal.set(e);
      else this.errorSignal.set(e);
      return;
    }
    const [tokens, connections, policies, check, authorization] = await Promise.all([
      this.tokensApi.list(slug).catch(() => null),
      this.connectionsApi.list().then((l) => l.items).catch(() => null),
      this.policiesApi.all().catch(() => null),
      this.endpoints.check(slug).catch(() => null),
      this.settingsApi.get('authorization').then((s) => s.values).catch(() => null),
      this.reloadClients(),
      this.loadDescriptions(detail),
      // Other endpoints: taken slugs (Duplicate, Split), the published one (Authorization), Connect's choices.
      this.store.list() ? Promise.resolve() : this.store.load().catch(() => undefined),
    ]);
    if (this.stopped || this.slugSignal() !== slug) return;
    this.detailSignal.set(detail);
    this.groupsSignal.set(groups);
    if (tokens) this.tokensSignal.set(tokens);
    if (connections) this.connectionsSignal.set(connections);
    if (policies) this.policiesSignal.set(policies);
    if (check) this.checkSignal.set(check);
    if (authorization) this.authorizationSignal.set(authorization);
    this.errorSignal.set(null);
    this.staleSignal.set(null);
    this.notFoundSignal.set(false);
    if (detail.settings.oauthEnabled) void this.reloadGrants();
    else this.grantsStateSignal.set('ready');
  }

  /** The header, wiring, risk and settings (approvals, a change of settings or modes). */
  async reloadDetail(): Promise<void> {
    const slug = this.slugSignal();
    if (!slug || !this.detailSignal()) return;
    try {
      const detail = await this.endpoints.get(slug);
      if (this.slugSignal() === slug) this.detailSignal.set(detail);
    } catch {
      // The full reload (Try again, the core coming back) says what failed.
    }
  }

  async reloadTools(): Promise<void> {
    const slug = this.slugSignal();
    if (!slug) return;
    try {
      const groups = await this.endpoints.tools(slug);
      if (this.slugSignal() === slug) this.groupsSignal.set(groups);
    } catch {
      // As reloadDetail.
    }
  }

  async reloadTokens(): Promise<void> {
    const slug = this.slugSignal();
    if (!slug) return;
    try {
      const tokens = await this.tokensApi.list(slug);
      if (this.slugSignal() === slug) this.tokensSignal.set(tokens);
    } catch {
      // As reloadDetail.
    }
  }

  async reloadClients(): Promise<void> {
    try {
      this.clientsSignal.set(await this.clientsApi.list());
      this.clientsStateSignal.set('ready');
    } catch (e) {
      if (!this.clientsSignal()) this.clientsStateSignal.set(isApiError(e) && e.kind === 'not-available' ? 'not-available' : 'failed');
    }
  }

  private async reloadGrants(): Promise<void> {
    const slug = this.slugSignal();
    try {
      const all = await this.oauthApi.grants();
      this.grantsSignal.set(all.filter((g) => g.endpoint === slug && g.ingress === 'LOCAL'));
      this.grantsStateSignal.set('ready');
    } catch (e) {
      this.grantsStateSignal.set(isApiError(e) && e.kind === 'not-available' ? 'not-available' : 'failed');
    }
  }

  /** Tool sentences from the connector types of the endpoint's connections (cached per type). */
  private async loadDescriptions(detail: EndpointDetail): Promise<void> {
    const types = [...new Set(detail.connections.map((c) => c.type))].filter((t) => !this.typeCache.has(t));
    await Promise.all(
      types.map((t) =>
        this.typesApi
          .get(t)
          .then((d) => this.typeCache.set(t, Object.fromEntries(d.tools.map((x) => [x.name, x.description]))))
          .catch(() => undefined),
      ),
    );
    const all: Record<string, string> = { ...builtInDescriptions(detail.endpoint.slug) };
    for (const c of detail.connections) Object.assign(all, this.typeCache.get(c.type) ?? {});
    this.descriptionsSignal.set(all);
  }

  private onHealth(h: { readonly name: string; readonly state: string; readonly latencyMs?: number; readonly message: string | null; readonly checkedAt: string }): void {
    this.connectionsSignal.update((rows) =>
      rows?.map((r) =>
        r.name === h.name ? { ...r, health: nextHealth(r.health, { state: h.state, latencyMs: h.latencyMs ?? null, message: h.message, checkedAt: h.checkedAt }) } : r,
      ) ?? rows,
    );
    this.detailSignal.update((d) =>
      d && d.connections.some((c) => c.name === h.name)
        ? { ...d, connections: d.connections.map((c) => (c.name === h.name ? { ...c, health: h.state } : c)) }
        : d,
    );
  }

  /** A call on this endpoint: last-used times, the usage meter and the waiting count follow, after the burst. */
  private scheduleLive(): void {
    if (this.liveTimer) clearTimeout(this.liveTimer);
    this.liveTimer = setTimeout(() => {
      this.liveTimer = null;
      void Promise.all([this.reloadTokens(), this.reloadClients(), this.reloadDetail(), this.reloadPolicies()]);
    }, LIVE_RELOAD_MS);
  }

  private async reloadPolicies(): Promise<void> {
    try {
      this.policiesSignal.set(await this.policiesApi.all());
    } catch {
      // The limits keep their last values.
    }
  }

  // ── Tools ─────────────────────────────────────────────────────────────────────────────────────────────────────

  /** One pair's mode or limits; the core answers with every group (a change can move other pairs' locks). */
  async setMode(change: { readonly toolKey: string; readonly connection: string | null; readonly mode: ToolMode; readonly overrides?: JsonObject | null; readonly version: number }): Promise<void> {
    const slug = this.requireSlug();
    try {
      this.groupsSignal.set(await this.endpoints.setMode(slug, change));
    } catch (e) {
      // A refusal: re-read what the core keeps, so the picker drops the mode it showed for the pick.
      await this.reloadTools();
      throw e;
    } finally {
      // Counts in the header, the wiring and the risk follow the modes.
      await this.reloadDetail();
    }
  }

  async bulk(connection: string | null, mode: ToolMode): Promise<{ readonly changed: string[]; readonly skipped: { readonly toolKey: string; readonly reason: string }[] }> {
    const slug = this.requireSlug();
    try {
      return await this.endpoints.bulk(slug, { group: connection as string, mode });
    } finally {
      await Promise.all([this.reloadTools(), this.reloadDetail()]);
    }
  }

  async confirmReadOnly(c: { readonly toolKey: string; readonly connection: string | null; readonly definitionHash: string }): Promise<void> {
    const slug = this.requireSlug();
    try {
      this.groupsSignal.set(await this.endpoints.confirmReadOnly(slug, c));
    } catch (e) {
      // The definition changed since the page loaded (another hash), or the core refused: show what it keeps.
      await this.reloadTools();
      throw e;
    } finally {
      await this.reloadDetail();
    }
  }

  // ── Connections ───────────────────────────────────────────────────────────────────────────────────────────────

  /** What adding each connection would do (Add connections): the core's preview, null where it didn't answer. */
  async previews(names: readonly string[]): Promise<Map<string, ConnectionPreview | null>> {
    const slug = this.requireSlug();
    const answers = await Promise.all(names.map((n) => this.endpoints.previewConnection(slug, n).catch(() => null)));
    return new Map(names.map((n, i) => [n, answers[i]]));
  }

  /**
   * Adds the connections one by one (the core checks each against the endpoint as it is then: a prod connection can't
   * follow a dev one). Returns which went on and which the core refused, with its reason.
   */
  async addConnections(names: readonly string[]): Promise<{ readonly added: string[]; readonly refused: { readonly name: string; readonly reason: string }[] }> {
    const slug = this.requireSlug();
    const added: string[] = [];
    const refused: { name: string; reason: string }[] = [];
    for (const name of names) {
      try {
        const detail = await this.endpoints.addConnection(slug, name);
        this.detailSignal.set(detail);
        added.push(name);
      } catch (e) {
        refused.push({ name, reason: isApiError(e) ? e.detail : 'The core didn’t answer.' });
      }
    }
    await Promise.all([this.reloadTools(), this.reloadConnections(), this.reloadDetail()]);
    return { added, refused };
  }

  async removeConnection(name: string): Promise<{ readonly stoppedTools: string[] }> {
    const slug = this.requireSlug();
    const result = await this.endpoints.removeConnection(slug, name);
    await Promise.all([this.reloadDetail(), this.reloadTools(), this.reloadConnections()]);
    return result;
  }

  private async reloadConnections(): Promise<void> {
    try {
      this.connectionsSignal.set((await this.connectionsApi.list()).items);
    } catch {
      // Keep the last list.
    }
  }

  // ── Risk and split ────────────────────────────────────────────────────────────────────────────────────────────

  async dismissRisk(reason: string): Promise<EndpointRisk> {
    const risk = await this.endpoints.dismissRisk(this.requireSlug(), reason);
    this.patchRisk(risk);
    return risk;
  }

  async restoreRisk(): Promise<EndpointRisk> {
    const risk = await this.endpoints.restoreRisk(this.requireSlug());
    this.patchRisk(risk);
    return risk;
  }

  private patchRisk(risk: EndpointRisk): void {
    this.detailSignal.update((d) => (d ? { ...d, endpoint: { ...d.endpoint, risk } } : d));
  }

  splitPreview(request: { readonly move: string[]; readonly newSlug: string; readonly clients: string[] }): Promise<SplitPreview> {
    return this.endpoints.splitPreview(this.requireSlug(), request);
  }

  async split(request: { readonly move: string[]; readonly newSlug: string; readonly clients: string[] }): Promise<EndpointSummary> {
    const created = await this.endpoints.split(this.requireSlug(), request);
    await Promise.all([this.refresh(), this.store.load()]);
    return created;
  }

  /**
   * Which clients called a tool of a connection on this endpoint today, and which tool (the split's "Codex called
   * http_request today"), from Activity's facets. Empty when Activity doesn't answer.
   */
  async calledToday(connection: string, now: number): Promise<Map<string, string>> {
    const slug = this.requireSlug();
    const midnight = new Date(now);
    midnight.setHours(0, 0, 0, 0);
    const out = new Map<string, string>();
    try {
      const facets = await this.activityApi.facets({ q: `endpoint:${slug} connection:${connection}`, from: midnight.toISOString() });
      const clients = facets['client'] ?? [];
      for (const c of clients) {
        const perClient = await this.activityApi.facets({ q: `endpoint:${slug} connection:${connection} client:${c.name}`, from: midnight.toISOString() });
        const tool = [...(perClient['tool'] ?? [])].sort((a, b) => b.count - a.count)[0]?.name;
        if (tool) out.set(c.name, tool);
      }
    } catch {
      // No pre-checked clients, no "called … today".
    }
    return out;
  }

  // ── Tokens ────────────────────────────────────────────────────────────────────────────────────────────────────

  async issueToken(name: string, ttlDays: number): Promise<IssuedToken> {
    const issued = await this.tokensApi.issue(this.requireSlug(), { name, ttlDays });
    await Promise.all([this.reloadTokens(), this.reloadDetail()]);
    return issued;
  }

  async revoke(row: TokenRow): Promise<{ readonly id: string; readonly rejectedApprovals: number }> {
    const grant = String(row.kind).toLowerCase() === 'grant';
    const result = grant ? await this.tokensApi.revokeGrant(row.id) : await this.tokensApi.revoke(row.id);
    await Promise.all([this.reloadTokens(), this.reloadDetail(), grant ? this.reloadClients() : Promise.resolve()]);
    return result;
  }

  async revokeGrant(id: string): Promise<void> {
    await this.oauthApi.revoke(id);
    await this.reloadGrants();
  }

  // ── Clients ───────────────────────────────────────────────────────────────────────────────────────────────────

  connectPreview(client: ClientId, request: ConnectRequest): Promise<ConnectPreview> {
    return this.clientsApi.connectPreview(client, request);
  }

  /** Writes the previewed change; the client's card, the tokens (a grant) and the wiring follow. */
  async connect(client: ClientId, previewId: string): Promise<ConnectResult> {
    const result = await this.clientsApi.connect(client, previewId);
    await Promise.all([this.reloadClients(), this.reloadTokens(), this.reloadDetail()]);
    return result;
  }

  disconnectPreview(linkId: string): Promise<ConnectPreview> {
    return this.clientsApi.disconnectPreview(linkId);
  }

  async disconnect(linkId: string, previewId: string): Promise<DisconnectResult> {
    const result = await this.clientsApi.disconnect(linkId, previewId);
    await Promise.all([this.reloadClients(), this.reloadTokens(), this.reloadDetail()]);
    return result;
  }

  // ── Endpoint ──────────────────────────────────────────────────────────────────────────────────────────────────

  async disable(): Promise<EndpointSummary> {
    const s = await this.endpoints.disable(this.requireSlug());
    this.patchSummary(s);
    void this.store.load();
    return s;
  }

  async enable(): Promise<EndpointSummary> {
    const s = await this.endpoints.enable(this.requireSlug());
    this.patchSummary(s);
    void this.store.load();
    return s;
  }

  private patchSummary(s: EndpointSummary): void {
    this.detailSignal.update((d) => (d ? { ...d, endpoint: s } : d));
  }

  async duplicate(newSlug: string): Promise<EndpointSummary> {
    const s = await this.endpoints.duplicate(this.requireSlug(), newSlug);
    void this.store.load();
    return s;
  }

  async remove(): Promise<{ readonly clients: string[]; readonly tokensRevoked: string[]; readonly rejectedApprovals: number }> {
    const slug = this.requireSlug();
    // The dialog is the confirmation: the core wants the slug repeated, as the kit's Delete asks nothing typed.
    const result = await this.endpoints.delete(slug, slug);
    void this.store.load();
    return result;
  }

  async confirmIntegrity(confirm?: string | null): Promise<void> {
    await this.endpoints.confirmIntegrity(this.requireSlug(), { confirm: confirm ?? null });
    await this.refresh();
  }

  // ── Settings ──────────────────────────────────────────────────────────────────────────────────────────────────

  /** One settings form (instructions, limits, OAuth, protocol): the core keeps the rest as it is. */
  async updateSettings(update: Omit<EndpointSettingsUpdate, 'version'>): Promise<EndpointSettings> {
    const slug = this.requireSlug();
    const version = this.detailSignal()?.settings.version ?? 0;
    const settings = await this.endpoints.updateSettings(slug, { ...update, version });
    this.detailSignal.update((d) => (d ? { ...d, settings } : d));
    // The header (sign-in badge, revisions), the wiring and the check follow the settings.
    void Promise.all([this.reloadDetail(), this.endpoints.check(slug).then((c) => this.checkSignal.set(c)).catch(() => undefined)]);
    if (update.oauthEnabled === true) void this.reloadGrants();
    return settings;
  }

  private requireSlug(): string {
    const slug = this.slugSignal();
    if (!slug) throw new Error('No endpoint is open');
    return slug;
  }
}
