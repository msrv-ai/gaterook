import { DestroyRef, Injectable, Signal, computed, inject, signal } from '@angular/core';
import { isNotAvailable } from '../../core/api/api-error';
import { ConnectionDetail, ConnectionRow, ConnectionsApi } from '../../core/api/connections.api';
import { ConnectorCatalog, ConnectorTypesApi } from '../../core/api/connector-types.api';
import { EndpointSummary, EndpointsApi, ToolGroup } from '../../core/api/endpoints.api';
import {
  CatalogIndex,
  McpServerReview,
  McpServerSource,
  McpServerStarted,
  McpServersApi,
  PluginCheck,
  PluginUpdatePreview,
  PluginsApi,
} from '../../core/api/plugins.api';
import { SecretRow, SecretsApi } from '../../core/api/secrets.api';
import { Template, TemplateSaved, TemplatesApi } from '../../core/api/templates.api';
import { JsonObject } from '../../core/api/types';
import { EventStream } from '../../core/events/event-stream';
import { McpServerView, mcpServerView } from './connectors-view';

/**
 * One answer of the core the page shows, loaded again on demand: `value` is the last good answer, `error` why the
 * first load failed (nothing to show), `stale` why a reload failed while the last answer stays on screen. Reloads are
 * coalesced: one in flight and one more after it (a change that came meanwhile is in neither answer yet).
 */
export class Source<T> {
  private readonly valueSignal = signal<T | null>(null);
  private readonly errorSignal = signal<unknown>(null);
  private readonly staleSignal = signal<unknown>(null);
  private readonly loadingSignal = signal(false);
  private inFlight: Promise<void> | null = null;
  private again = false;

  readonly value = this.valueSignal.asReadonly();
  readonly error = this.errorSignal.asReadonly();
  readonly stale = this.staleSignal.asReadonly();
  readonly loading = this.loadingSignal.asReadonly();
  /** This core has no such route yet (its step isn't merged): the panel says "not available yet". */
  readonly notAvailable = computed(() => isNotAvailable(this.errorSignal()));

  constructor(
    private readonly load: () => Promise<T>,
    private readonly after?: (value: T) => void,
  ) {}

  refresh(): Promise<void> {
    if (this.inFlight) {
      this.again = true;
      return this.inFlight;
    }
    this.loadingSignal.set(true);
    this.inFlight = this.run().finally(() => {
      this.inFlight = null;
      this.loadingSignal.set(false);
      if (this.again) {
        this.again = false;
        void this.refresh();
      }
    });
    return this.inFlight;
  }

  /** Sets the answer from elsewhere (an action that returned the new state). */
  set(value: T): void {
    this.valueSignal.set(value);
    this.errorSignal.set(null);
    this.staleSignal.set(null);
    this.after?.(value);
  }

  private async run(): Promise<void> {
    try {
      this.set(await this.load());
    } catch (e) {
      if (this.valueSignal() === null) this.errorSignal.set(e);
      else this.staleSignal.set(e);
    }
  }
}

/** How a server's tool snapshot loaded: a count, or null when this core has no snapshot route yet. */
type ToolsSnapshot = number | null;

/**
 * The Connectors page's data (ARCHITECTURE.md §6 "connectors", §8.4): connector types (Installed), the signed
 * catalog, templates, and the external MCP servers — connections of the `mcp-server` type (§2.3) with their config
 * and, from C10, their process and tool snapshot. Connections, endpoints and their tools tie it together: "3
 * connections · View activity", Runs on, On endpoints. Secrets fill "Value from secret" of Add MCP server.
 *
 * Plugins (K4), the catalog (K6) and MCP servers (C10) are later steps: until the core has their routes, those parts
 * of the page say they are not available yet (404 without a problem body), never invent data.
 *
 * Live: `plugin.state` and `mcp-server.state` events reload the types and note the server's state.
 */
@Injectable()
export class ConnectorsData {
  private readonly typesApi = inject(ConnectorTypesApi);
  private readonly connectionsApi = inject(ConnectionsApi);
  private readonly pluginsApi = inject(PluginsApi);
  private readonly serversApi = inject(McpServersApi);
  private readonly templatesApi = inject(TemplatesApi);
  private readonly endpointsApi = inject(EndpointsApi);
  private readonly secretsApi = inject(SecretsApi);
  private readonly events = inject(EventStream);

  readonly types = new Source<ConnectorCatalog>(() => this.typesApi.catalog());
  readonly connections = new Source<readonly ConnectionRow[]>(
    () => this.connectionsApi.list().then((l) => l.items),
    (rows) => this.loadServers(rows),
  );
  readonly catalog = new Source<CatalogIndex>(() => this.pluginsApi.catalog());
  readonly templates = new Source<readonly Template[]>(() => this.templatesApi.list(), () => this.loadEndpointTools());
  readonly endpoints = new Source<readonly EndpointSummary[]>(() => this.endpointsApi.list(), () => this.loadEndpointTools());
  readonly secrets = new Source<readonly SecretRow[]>(() => this.secretsApi.list());
  /**
   * Which connection types offer which template kind (`GET /api/connector-types/{type}` › `templateKinds`): New
   * template offers a kind only when this core compiles it, and Runs on lists the connections of those types.
   */
  readonly templateKinds = new Source<ReadonlyMap<string, readonly string[]>>(() => this.loadTemplateKinds());

  private readonly detailsSignal = signal<ReadonlyMap<string, ConnectionDetail>>(new Map());
  private readonly snapshotsSignal = signal<ReadonlyMap<string, ToolsSnapshot>>(new Map());
  private readonly liveStatesSignal = signal<ReadonlyMap<string, string>>(new Map());
  private readonly endpointToolsSignal = signal<ReadonlyMap<string, readonly ToolGroup[]>>(new Map());

  /** Tools of each endpoint (only loaded when there are templates: On endpoints, Try in Playground). */
  readonly endpointTools: Signal<ReadonlyMap<string, readonly ToolGroup[]>> = this.endpointToolsSignal.asReadonly();

  /** The MCP servers, from their connections, config and process. */
  readonly servers = computed<McpServerView[]>(() => {
    const rows = this.connections.value() ?? [];
    const details = this.detailsSignal();
    const snapshots = this.snapshotsSignal();
    const live = this.liveStatesSignal();
    const installed = this.types.value()?.installed ?? [];
    const catalog = this.catalog.value();
    return rows
      .filter((r) => r.type === 'mcp-server')
      .map((r) =>
        mcpServerView(
          r,
          details.get(r.name) ?? null,
          installed.find((t) => t.kind === 'mcp-server' && t.id === r.name) ?? null,
          snapshots.get(r.name) ?? null,
          live.get(r.name) ?? null,
          catalog,
        ),
      );
  });

  private readonly unsubscribe: (() => void)[] = [];

  constructor() {
    inject(DestroyRef).onDestroy(() => this.unsubscribe.splice(0).forEach((u) => u()));
  }

  start(): Promise<void> {
    this.unsubscribe.push(
      this.events.on('plugin.state', () => void this.types.refresh()),
      this.events.on('mcp-server.state', (e) => {
        if (e?.name && e.state) this.liveStatesSignal.update((m) => new Map(m).set(e.name, e.state));
        void this.types.refresh();
      }),
    );
    return this.refreshAll();
  }

  refreshAll(): Promise<void> {
    return Promise.all([
      this.types.refresh(),
      this.connections.refresh(),
      this.catalog.refresh(),
      this.templates.refresh(),
      this.endpoints.refresh(),
      this.secrets.refresh(),
      this.templateKinds.refresh(),
    ]).then(() => undefined);
  }

  /** The types whose connectors may offer templates (SQL queries, SSH runbooks, OpenAPI imports). */
  private async loadTemplateKinds(): Promise<ReadonlyMap<string, readonly string[]>> {
    const types = ['postgres', 'mysql', 'oracle', 'sqlserver', 'ssh', 'http'];
    const answers = await Promise.allSettled(types.map((t) => this.typesApi.get(t)));
    const kinds = new Map<string, string[]>();
    answers.forEach((a, i) => {
      if (a.status !== 'fulfilled') return;
      for (const k of a.value.templateKinds) kinds.set(k, [...(kinds.get(k) ?? []), types[i]]);
    });
    return kinds;
  }

  // ── Derived loads ─────────────────────────────────────────────────────────────────────────────────────────────

  /** Config (command, variables, secrets) and tool snapshot of each MCP server; a failed one is left without. */
  private loadServers(rows: readonly ConnectionRow[]): void {
    const servers = rows.filter((r) => r.type === 'mcp-server');
    for (const r of servers) {
      const known = this.detailsSignal().get(r.name);
      if (!known || known.version !== r.version) {
        void this.connectionsApi
          .get(r.name)
          .then((d) => this.detailsSignal.update((m) => new Map(m).set(r.name, d)))
          .catch(() => undefined);
      }
      if (!this.snapshotsSignal().has(r.name)) {
        void this.serversApi
          .tools(r.name)
          .then((tools) => this.snapshotsSignal.update((m) => new Map(m).set(r.name, tools.length)))
          .catch(() => this.snapshotsSignal.update((m) => new Map(m).set(r.name, null)));
      }
    }
  }

  /** Each endpoint's tools, to find where the templates' tools are on. Nothing to find without templates. */
  private loadEndpointTools(): void {
    const templates = this.templates.value();
    const endpoints = this.endpoints.value();
    if (!templates?.length || !endpoints?.length) return;
    void Promise.all(
      endpoints.map((e) =>
        this.endpointsApi
          .tools(e.slug)
          .then((groups) => [e.slug, groups] as const)
          .catch(() => null),
      ),
    ).then((pairs) => {
      const map = new Map<string, readonly ToolGroup[]>();
      for (const p of pairs) if (p) map.set(p[0], p[1]);
      this.endpointToolsSignal.set(map);
    });
  }

  // ── Built-in types ────────────────────────────────────────────────────────────────────────────────────────────

  async enableType(id: string): Promise<void> {
    await this.typesApi.enable(id);
    await this.types.refresh();
  }

  async keepType(id: string): Promise<void> {
    await this.typesApi.confirmIntegrity(id);
    await this.types.refresh();
  }

  // ── Plugins ───────────────────────────────────────────────────────────────────────────────────────────────────

  checkPlugin(source: File | { readonly url: string } | { readonly catalog: string }): Promise<PluginCheck> {
    return source instanceof File ? this.pluginsApi.checkFile(source) : this.pluginsApi.check(source);
  }

  async installPlugin(stagingId: string, acknowledgeUnsigned: boolean): Promise<void> {
    await this.pluginsApi.install(stagingId, acknowledgeUnsigned);
    await Promise.all([this.types.refresh(), this.catalog.refresh()]);
  }

  async setPluginEnabled(id: string, enabled: boolean): Promise<void> {
    await this.pluginsApi.action(id, enabled ? 'enable' : 'disable');
    await this.types.refresh();
  }

  async restartPlugin(id: string): Promise<void> {
    await this.pluginsApi.action(id, 'restart');
    await this.types.refresh();
  }

  updatePreview(id: string, to: string): Promise<PluginUpdatePreview> {
    return this.pluginsApi.updatePreview(id, to);
  }

  /** `acknowledgeUnsigned` only for a release the catalog doesn't sign: the core refuses it without (§9). */
  async updatePlugin(id: string, to: string, acknowledgeUnsigned = false): Promise<void> {
    await this.pluginsApi.action(id, 'update', acknowledgeUnsigned ? { to, acknowledgeUnsigned } : { to });
    await Promise.all([this.types.refresh(), this.catalog.refresh()]);
  }

  async uninstallPlugin(id: string): Promise<void> {
    await this.pluginsApi.uninstall(id);
    await Promise.all([this.types.refresh(), this.catalog.refresh()]);
  }

  // ── MCP servers ───────────────────────────────────────────────────────────────────────────────────────────────

  reviewServer(source: McpServerSource): Promise<McpServerReview> {
    return this.serversApi.review(source);
  }

  async addServer(source: McpServerSource): Promise<McpServerStarted> {
    const started = await this.serversApi.create({ ...source, acknowledge: true });
    await Promise.all([this.connections.refresh(), this.types.refresh(), this.catalog.refresh(), this.secrets.refresh()]);
    return started;
  }

  async restartServer(name: string): Promise<McpServerStarted> {
    const r = await this.serversApi.action(name, 'restart');
    this.snapshotsSignal.update((m) => {
      const next = new Map(m);
      next.delete(name);
      return next;
    });
    await Promise.all([this.types.refresh(), this.connections.refresh()]);
    return r;
  }

  async removeServer(name: string): Promise<void> {
    await this.serversApi.delete(name);
    this.detailsSignal.update((m) => {
      const next = new Map(m);
      next.delete(name);
      return next;
    });
    await Promise.all([this.connections.refresh(), this.types.refresh(), this.catalog.refresh()]);
  }

  // ── Templates ─────────────────────────────────────────────────────────────────────────────────────────────────

  /** One template with its definition when the core sends it (`GET /api/templates/{id}`). */
  getTemplate(id: string): Promise<Template> {
    return this.templatesApi.get(id);
  }

  async createTemplate(kind: Template['kind'], connection: string, definition: JsonObject): Promise<TemplateSaved> {
    const saved = await this.templatesApi.create({ kind, connection, definition });
    if (saved.template) await this.templates.refresh();
    return saved;
  }

  async updateTemplate(id: string, definition: JsonObject, baseVersion: number): Promise<TemplateSaved> {
    const saved = await this.templatesApi.update(id, { definition, baseVersion });
    if (saved.compile?.ok !== false) await this.templates.refresh();
    return saved;
  }

  async installCatalogTemplate(catalogRef: string, connection: string): Promise<TemplateSaved> {
    const saved = await this.templatesApi.create({ kind: 'sql-query', connection, catalogRef });
    await Promise.all([this.templates.refresh(), this.catalog.refresh()]);
    return saved;
  }

  async deleteTemplate(id: string): Promise<void> {
    await this.templatesApi.delete(id);
    await this.templates.refresh();
  }

  async keepTemplate(id: string): Promise<void> {
    await this.templatesApi.confirmIntegrity(id);
    await this.templates.refresh();
  }
}
