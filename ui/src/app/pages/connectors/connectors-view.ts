import { CatalogEntry, CatalogIndex } from '../../core/api/plugins.api';
import { ConnectionDetail, ConnectionRow } from '../../core/api/connections.api';
import { ConnectorProcess, InstalledConnector } from '../../core/api/connector-types.api';
import { EndpointSummary, ToolGroup } from '../../core/api/endpoints.api';
import { Template, TemplateKind } from '../../core/api/templates.api';
import { Env, JsonObject, JsonValue } from '../../core/api/types';
import { ConnectorCardStatus, ConnectorCardView } from '../../kit/elements/connector-card/connector-card';
import { connectorIcon } from '../../kit/elements/shared/connector-icons';

/**
 * What the Connectors page shows, as pure functions of the core's answers (ARCHITECTURE.md §6 "connectors", §8.4):
 * the cards of Installed and Catalog, the rows of Templates and MCP servers, and every sentence the kit words from
 * data ("10 built-in, 1 plugin, 1 MCP server", "Running · 54 MB", "Read only · 1 tool"). The page and its panels only
 * render these; the tests hold them against the kit's copy.
 */

export type TabId = 'installed' | 'catalog' | 'templates' | 'mcp-servers';

export function plural(n: number, one: string, many = `${one}s`): string {
  return `${n.toLocaleString('en-US')} ${n === 1 ? one : many}`;
}

/** "v1.2.0" for "1.2.0" (a version already written with its v stays as it is). */
export function v(version: string | null | undefined): string | null {
  if (!version) return null;
  return version.startsWith('v') ? version : `v${version}`;
}

/** Negative, zero or positive as `a` is older than, equal to or newer than `b` (numeric dot parts, then text). */
export function compareVersions(a: string, b: string): number {
  const pa = a.replace(/^v/, '').split(/[.+-]/);
  const pb = b.replace(/^v/, '').split(/[.+-]/);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const x = pa[i] ?? '0';
    const y = pb[i] ?? '0';
    const nx = /^\d+$/.test(x) ? Number(x) : NaN;
    const ny = /^\d+$/.test(y) ? Number(y) : NaN;
    if (!Number.isNaN(nx) && !Number.isNaN(ny)) {
      if (nx !== ny) return nx - ny;
    } else if (x !== y) {
      return x < y ? -1 : 1;
    }
  }
  return 0;
}

/** "1.4 MB", "820 KB", "512 bytes": sizes of packages as the kit writes them. */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || !Number.isFinite(bytes)) return '';
  if (bytes < 1024) return plural(bytes, 'byte');
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  const mb = bytes / (1024 * 1024);
  return `${mb < 10 ? mb.toFixed(1) : Math.round(mb)} MB`;
}

/** Joins words as the kit does: "a", "a and b", "a, b and c". */
export function joinAnd(words: readonly string[]): string {
  if (words.length <= 1) return words.join('');
  return `${words.slice(0, -1).join(', ')} and ${words.at(-1)}`;
}

/** The activity filter of a set of connections: `connection:dev-db connection:stage-db` (a repeated field is OR). */
export function connectionsQuery(names: readonly string[]): string | null {
  return names.length ? names.map((n) => `connection:${n}`).join(' ') : null;
}

// ── Process status ──────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The state of a plugin's host or an MCP server's process in the kit's words (§7.5 states; BLUEPRINT §7 tones):
 * "Running · 54 MB", "Sleeping" (stopped after its idle time: it starts again on the next call), "Starting",
 * "Restarting" (crashed, waiting to start again), "Failed" (stopped restarting: Restart it), "Finishing calls".
 */
export function processStatus(process: Pick<ConnectorProcess, 'state' | 'rssMb'> | null | undefined): ConnectorCardStatus | null {
  if (!process?.state) return null;
  switch (process.state.toLowerCase()) {
    case 'running':
      return { tone: 'success', text: process.rssMb !== null && process.rssMb !== undefined ? `Running · ${process.rssMb} MB` : 'Running' };
    case 'stopped':
    case 'idle':
    case 'sleeping':
      return { tone: 'neutral', text: 'Sleeping' };
    case 'starting':
      return { tone: 'info', text: 'Starting' };
    case 'draining':
      return { tone: 'info', text: 'Finishing calls' };
    case 'crashed':
    case 'backoff':
      return { tone: 'warning', text: 'Restarting' };
    case 'failed':
      return { tone: 'danger', text: 'Failed' };
    default:
      return { tone: 'neutral', text: capitalize(process.state) };
  }
}

function capitalize(s: string): string {
  return s ? s[0].toUpperCase() + s.slice(1).replace(/-/g, ' ') : s;
}

// ── Installed ───────────────────────────────────────────────────────────────────────────────────────────────────

/** An update the catalog offers for an installed plugin: its entry and the version it brings. */
export interface PluginUpdate {
  readonly entry: CatalogEntry;
  readonly to: string;
}

/** The newer catalog version of an installed plugin, if the catalog has one. */
export function pluginUpdate(plugin: Pick<InstalledConnector, 'id' | 'version'>, catalog: CatalogIndex | null): PluginUpdate | null {
  const entry = catalog?.entries.find((e) => e.kind === 'plugin' && e.id === plugin.id);
  return entry && compareVersions(entry.version, plugin.version) > 0 ? { entry, to: entry.version } : null;
}

/**
 * One card of Installed. `note` is the line under the description; `status` replaces the tool count in the footer
 * of a plugin that needs attention or runs a process.
 */
export interface InstalledView {
  readonly key: string;
  readonly kind: 'built-in' | 'plugin' | 'mcp-server';
  readonly id: string;
  readonly name: string;
  readonly card: ConnectorCardView;
  /** Connections of this type, for "3 connections · View activity". */
  readonly connections: number;
  readonly activityQuery: string | null;
  /** The type is switched off (built-ins: Turn on) or was changed outside GateRook (Review). */
  readonly state: 'enabled' | 'disabled' | 'changed-outside';
  readonly update: PluginUpdate | null;
  /** The process failed (plugins): the menu offers Restart. */
  readonly failed: boolean;
  /** The server behind an MCP server card. */
  readonly server: McpServerView | null;
  /** Lowercase text the search matches (name, kind, description, version). */
  readonly search: string;
}

const KIND_ORDER: Record<InstalledView['kind'], number> = { 'built-in': 0, plugin: 1, 'mcp-server': 2 };

/** Built-in connectors and plugins from `/api/connector-types`, then the MCP servers (the kit's order). */
export function installedViews(
  installed: readonly InstalledConnector[],
  connections: readonly ConnectionRow[],
  catalog: CatalogIndex | null,
  servers: readonly McpServerView[],
): InstalledView[] {
  const byType = new Map<string, string[]>();
  for (const c of connections) byType.set(c.type, [...(byType.get(c.type) ?? []), c.name]);
  const views: InstalledView[] = [];
  for (const t of installed) {
    if (t.kind !== 'built-in' && t.kind !== 'plugin') continue;
    const names = byType.get(t.id) ?? [];
    views.push(t.kind === 'plugin' ? pluginView(t, names, catalog) : builtInView(t, names));
  }
  for (const s of servers) views.push(serverCardView(s));
  return views.sort((a, b) => KIND_ORDER[a.kind] - KIND_ORDER[b.kind]);
}

function stateOf(t: InstalledConnector): InstalledView['state'] {
  return !t.verified || t.state === 'changed-outside' ? 'changed-outside' : t.enabled ? 'enabled' : 'disabled';
}

function builtInView(t: InstalledConnector, names: readonly string[]): InstalledView {
  const state = stateOf(t);
  const status: ConnectorCardStatus | null =
    state === 'changed-outside' ? { tone: 'warning', text: 'Changed outside GateRook' } : state === 'disabled' ? { tone: 'neutral', text: 'Switched off' } : null;
  return {
    key: `type:${t.id}`,
    kind: 'built-in',
    id: t.id,
    name: t.name,
    card: {
      type: t.id,
      variant: null,
      name: t.name,
      icon: t.icon || connectorIcon(t.id),
      kind: 'built-in',
      version: t.version,
      signed: null,
      description: t.description,
      tools: t.tools,
      action: state === 'enabled' ? 'add' : null,
      status,
    },
    connections: t.connections,
    activityQuery: connectionsQuery(names),
    state,
    update: null,
    failed: false,
    server: null,
    search: searchText(t.name, 'built-in', t.description, t.version),
  };
}

function pluginView(t: InstalledConnector, names: readonly string[], catalog: CatalogIndex | null): InstalledView {
  const state = stateOf(t);
  const update = pluginUpdate(t, catalog);
  const process = processStatus(t.process);
  const failed = t.process?.state?.toLowerCase() === 'failed';
  // The footer says what needs the person first: a change made outside, switched off, a failed process, an update,
  // and otherwise what its process is doing (the tool count is on the line under the description).
  const status: ConnectorCardStatus | null =
    state === 'changed-outside'
      ? { tone: 'warning', text: 'Changed outside GateRook' }
      : state === 'disabled'
        ? { tone: 'neutral', text: 'Disabled' }
        : failed
          ? process
          : update
            ? { tone: 'info', text: 'Update available · ', mono: v(update.to) }
            : process;
  return {
    key: `plugin:${t.id}`,
    kind: 'plugin',
    id: t.id,
    name: t.name,
    card: {
      type: t.id,
      variant: null,
      name: t.name,
      icon: t.icon || 'puzzle',
      kind: 'plugin',
      version: t.version,
      signed: t.signed,
      description: t.description,
      tools: t.tools,
      action: null,
      status,
    },
    connections: t.connections,
    activityQuery: connectionsQuery(names),
    state,
    update: state === 'enabled' ? update : null,
    failed,
    server: null,
    search: searchText(t.name, 'plugin', t.description, t.version),
  };
}

function serverCardView(s: McpServerView): InstalledView {
  return {
    key: `mcp:${s.name}`,
    kind: 'mcp-server',
    id: s.name,
    name: s.name,
    card: {
      type: 'mcp-server',
      variant: null,
      name: s.name,
      icon: 'plug',
      kind: 'mcp-server',
      version: s.version,
      signed: s.signed,
      description: s.description,
      tools: s.tools,
      action: null,
    },
    connections: 0,
    activityQuery: null,
    state: s.verified ? 'enabled' : 'changed-outside',
    update: null,
    failed: false,
    server: s,
    search: searchText(s.name, 'mcp server', s.description, s.version ?? ''),
  };
}

function searchText(...parts: readonly (string | null | undefined)[]): string {
  return parts.filter(Boolean).join(' ').toLowerCase();
}

/** Whether a card or row matches the search box (every word of the query, anywhere in its text). */
export function matches(search: string, query: string): boolean {
  const words = query.trim().toLowerCase().split(/\s+/).filter(Boolean);
  return words.every((w) => search.includes(w));
}

/** The count line of Installed: "10 built-in, 1 plugin, 1 MCP server", or "3 of 12 installed connectors" when searching. */
export function installedCountText(views: readonly InstalledView[], shown: number, query: string): string {
  if (query.trim()) return `${shown} of ${plural(views.length, 'installed connector')}`;
  const builtIn = views.filter((x) => x.kind === 'built-in').length;
  const plugins = views.filter((x) => x.kind === 'plugin').length;
  const servers = views.filter((x) => x.kind === 'mcp-server').length;
  const parts = [`${builtIn} built-in`];
  if (plugins) parts.push(plural(plugins, 'plugin'));
  if (servers) parts.push(plural(servers, 'MCP server'));
  return parts.join(', ');
}

// ── Catalog ─────────────────────────────────────────────────────────────────────────────────────────────────────

/** What the catalog card's action does. */
export type CatalogAction = 'install-plugin' | 'update-plugin' | 'install-template' | 'install-server' | 'open-server' | 'add-connection';

export interface CatalogView {
  readonly key: string;
  readonly entry: CatalogEntry;
  readonly card: ConnectorCardView;
  readonly action: CatalogAction;
  readonly search: string;
}

const CATALOG_ICONS: Record<CatalogEntry['kind'], string> = { plugin: 'puzzle', template: 'file-code', 'mcp-server': 'plug' };

/** Tool names in a catalog description ("`pg_slow_queries` and `pg_locks` as read-only SQL tools"): set in code. */
export function toolLiterals(text: string): string[] {
  return [...new Set(text.match(/\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\b/g) ?? [])];
}

export function catalogViews(index: CatalogIndex): CatalogView[] {
  return index.entries.map((e) => {
    const installed = e.installed;
    let action: CatalogAction;
    let status: ConnectorCardStatus | null = null;
    let runs: string | null = null;
    let updateTo: string | null = null;
    let cardAction: ConnectorCardView['action'];
    if (e.kind === 'plugin' && installed && compareVersions(e.version, installed) > 0) {
      action = 'update-plugin';
      runs = v(installed);
      updateTo = v(e.version);
      cardAction = 'update';
    } else if (e.kind === 'plugin' && installed) {
      action = 'add-connection';
      status = { tone: 'success', text: 'Installed' };
      cardAction = null;
    } else if (e.kind === 'mcp-server' && installed) {
      action = 'open-server';
      status = { tone: 'success', text: 'Installed as ', mono: installed };
      cardAction = null;
    } else {
      action = e.kind === 'plugin' ? 'install-plugin' : e.kind === 'template' ? 'install-template' : 'install-server';
      cardAction = 'install';
    }
    return {
      key: `${e.kind}:${e.id}`,
      entry: e,
      action,
      card: {
        type: e.id,
        variant: null,
        name: e.name,
        icon: e.icon || CATALOG_ICONS[e.kind],
        kind: e.kind,
        version: e.version,
        signed: e.signed,
        description: e.description,
        literals: toolLiterals(e.description),
        tools: e.tools,
        action: cardAction,
        updateTo,
        status,
        runs,
      },
      search: searchText(e.name, e.kind.replace('-', ' '), e.description, e.version),
    };
  });
}

// ── Templates ───────────────────────────────────────────────────────────────────────────────────────────────────

export const TEMPLATE_KINDS: Record<TemplateKind, { readonly label: string; readonly icon: string; readonly noun: string }> = {
  'sql-query': { label: 'SQL query', icon: 'database', noun: 'template' },
  'ssh-runbook': { label: 'SSH runbook', icon: 'terminal', noun: 'runbook' },
  openapi: { label: 'OpenAPI import', icon: 'globe', noun: 'OpenAPI import' },
};

export function templateKind(kind: string): { readonly label: string; readonly icon: string; readonly noun: string } {
  return TEMPLATE_KINDS[kind as TemplateKind] ?? { label: kind, icon: 'file-code', noun: 'template' };
}

/** A `gw-mode` chip of a template's tools: Read only (info), Approval (warning), Off (neutral). */
export interface ModeChip {
  readonly mode: 'read' | 'approval' | 'off';
  readonly icon: string;
  readonly text: string;
}

const READ: Omit<ModeChip, 'text'> = { mode: 'read', icon: 'eye' };
const APPROVAL: Omit<ModeChip, 'text'> = { mode: 'approval', icon: 'user-check' };
const OFF: Omit<ModeChip, 'text'> = { mode: 'off', icon: 'circle-off' };

function count(natures: JsonValue, key: string): number {
  if (natures && typeof natures === 'object' && !Array.isArray(natures)) {
    const n = (natures as JsonObject)[key];
    return typeof n === 'number' ? n : 0;
  }
  return 0;
}

/**
 * A template's name as the kit lists it: a SQL query with its signature ("orders_by_status(status, since)"); a runbook
 * ("compose-ps", whatever its parameters) and an import by name. The name alone, too, from a core that doesn't send the
 * parameters (before step API) and for a template that doesn't verify.
 */
export function templateSignature(t: Pick<Template, 'kind' | 'name' | 'parameters'>): string {
  if (t.kind !== 'sql-query' || !t.parameters) return t.name;
  const params = t.parameters.map((p) => p.name).filter((n): n is string => typeof n === 'string' && n !== '');
  return `${t.name}(${params.join(', ')})`;
}

/**
 * The "Tools and default mode" cell (kit): `Read only · 1 tool`, `Approval · via ssh_run, changes state`,
 * `9 read only · 5 approval · 14 operations`; Off with why when the template serves nothing.
 */
export function templateModes(t: Pick<Template, 'kind' | 'natures' | 'status'>): { readonly chips: ModeChip[]; readonly note: string } {
  if (t.status === 'invalid') return { chips: [{ ...OFF, text: 'Off' }], note: 'its connection was deleted' };
  if (t.status === 'disabled') return { chips: [{ ...OFF, text: 'Off' }], note: 'switched off' };
  const read = count(t.natures, 'read');
  const mutation = count(t.natures, 'mutation');
  const total = read + mutation;
  if (t.kind === 'openapi' || (read > 0 && mutation > 0)) {
    const chips: ModeChip[] = [];
    if (read) chips.push({ ...READ, text: `${read} read only` });
    if (mutation) chips.push({ ...APPROVAL, text: `${mutation} approval` });
    return { chips, note: plural(total, t.kind === 'openapi' ? 'operation' : 'tool') };
  }
  const chip = mutation ? { ...APPROVAL, text: 'Approval' } : { ...READ, text: 'Read only' };
  if (t.kind === 'ssh-runbook') return { chips: [chip], note: mutation ? 'via ssh_run, changes state' : 'via ssh_run' };
  return { chips: [chip], note: mutation ? `${plural(total, 'tool')}, changes state` : plural(total, 'tool') };
}

/** Where a template's tools are on (an endpoint pair not Off): `endpoint_tool.tool_key` is `tpl:<id>:<opKey>`. */
export function templateEndpoints(templateId: string, tools: ReadonlyMap<string, readonly ToolGroup[]>): string[] {
  const prefix = `tpl:${templateId}:`;
  const out: string[] = [];
  for (const [slug, groups] of tools) {
    if (groups.some((g) => g.tools.some((t) => t.toolKey.startsWith(prefix) && t.mode !== 'OFF'))) out.push(slug);
  }
  return out.sort();
}

/** The names of a template's tools on an endpoint (Try in Playground opens the first). */
export function templateToolNames(templateId: string, groups: readonly ToolGroup[] | undefined): string[] {
  const prefix = `tpl:${templateId}:`;
  return (groups ?? []).flatMap((g) => g.tools.filter((t) => t.toolKey.startsWith(prefix)).map((t) => t.name));
}

/** The endpoint a template (or a server) is best added to: one that already has its connection, else one of its env. */
export function endpointFor(
  connection: string | null,
  env: Env | null,
  endpoints: readonly EndpointSummary[],
  tools: ReadonlyMap<string, readonly ToolGroup[]>,
): string | null {
  if (connection) {
    for (const [slug, groups] of tools) if (groups.some((g) => g.connection === connection)) return slug;
  }
  return endpoints.find((e) => e.status === 'running' && e.env === env)?.slug ?? null;
}

// ── MCP servers ─────────────────────────────────────────────────────────────────────────────────────────────────

/** A variable (stdio) or header (HTTP) the server gets, and the secret its value comes from. */
export interface McpSecretView {
  readonly name: string;
  /** The secret's name in Secrets (`github-mcp/token`), or null when the core doesn't say. */
  readonly secret: string | null;
}

/** One external MCP server: a connection of the `mcp-server` type (§2.3), its config, and what its process does. */
export interface McpServerView {
  readonly name: string;
  readonly env: Env;
  readonly transport: 'stdio' | 'http';
  /** The command as it runs, argv joined (quoted where an argument has a space). */
  readonly command: string | null;
  readonly url: string | null;
  readonly secrets: McpSecretView[];
  /** Variables of this computer the process also gets (HTTPS_PROXY…). */
  readonly inherited: string[];
  readonly catalogEntry: string | null;
  /** The pinned version: the catalog entry's, else the npm package's in the command. */
  readonly version: string | null;
  /** Whether the code is pinned: a catalog entry or `pkg@1.2.3` is; an npx package without a version isn't; null when
   *  the command isn't an npm package (a binary, an HTTP server). */
  readonly pinned: boolean | null;
  /** The npm package the command runs, as written (`@scope/server@1.2.3`), or null when it isn't an npm package. */
  readonly packageName: string | null;
  /** Signed catalog entry: true; a command typed here has no signature (no trust badge). */
  readonly signed: boolean | null;
  readonly description: string;
  readonly tools: number | null;
  readonly state: string | null;
  readonly usedBy: string[];
  readonly start: 'on-first-call' | 'with-core';
  readonly idleStopMinutes: number | null;
  readonly toolPrefix: string;
  readonly verified: boolean;
}

/** `github-mcp` → `github_` (the connector's default: the name up to its first `-`, lowercase letters and digits). */
export function defaultToolPrefix(name: string): string {
  const base = name.split('-')[0].replace(/[^a-z0-9]/g, '').slice(0, 15);
  return `${base || 'mcp'}_`;
}

/** argv as one line: arguments with spaces or quotes are quoted, so the line reads back to the same argv. */
export function formatArgv(argv: readonly string[]): string {
  return argv.map((a) => (/^[\w@%+=:,./-]+$/.test(a) ? a : `"${a.replace(/(["\\])/g, '\\$1')}"`)).join(' ');
}

/**
 * The command line of Add MCP server as argv: split at whitespace, single and double quotes group (a backslash
 * escapes inside double quotes), never a shell — no variables, globs, pipes or `;` (the connector's schema: "the UI
 * line is tokenized (quotes only), never run by a shell"). An unclosed quote is an error.
 */
export function tokenizeCommand(line: string): { readonly argv: string[]; readonly error: string | null } {
  const argv: string[] = [];
  let cur = '';
  let inWord = false;
  let quote: '"' | "'" | null = null;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (quote) {
      if (c === quote) quote = null;
      else if (c === '\\' && quote === '"' && (line[i + 1] === '"' || line[i + 1] === '\\')) cur += line[++i];
      else cur += c;
    } else if (c === '"' || c === "'") {
      quote = c;
      inWord = true;
    } else if (/\s/.test(c)) {
      if (inWord) argv.push(cur);
      cur = '';
      inWord = false;
    } else {
      cur += c;
      inWord = true;
    }
  }
  if (quote) return { argv: [], error: 'A quote in the command isn’t closed.' };
  if (inWord) argv.push(cur);
  return { argv, error: null };
}

/**
 * The npm package an `npx`, `pnpx`, `bunx` or `pnpm dlx` command runs and the version it pins (kit page script):
 * `@1.2.3` pins; no version, `@latest` or a range doesn't. Null for any other command.
 */
export function npmPackage(argv: readonly string[]): { readonly name: string; readonly version: string } | null {
  const [cmd, ...rest] = argv;
  if (!cmd) return null;
  const base = cmd.split('/').at(-1) ?? cmd;
  if (!/^(npx|pnpx|bunx)$/.test(base) && !(base === 'pnpm' && rest[0] === 'dlx')) return null;
  const pkg = rest.filter((w) => !w.startsWith('-') && w !== 'dlx')[0] ?? '';
  const at = pkg.lastIndexOf('@');
  const version = at > 0 ? pkg.slice(at + 1) : '';
  return { name: pkg, version: /^\d+(\.\d+)*$/.test(version) ? version : '' };
}

function stringArray(v: JsonValue | undefined): string[] {
  return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [];
}

function bindings(config: JsonObject, key: 'env' | 'headers', detail: ConnectionDetail | null): McpSecretView[] {
  const list = config[key];
  if (!Array.isArray(list)) return [];
  return list.flatMap((item, i) => {
    if (!item || typeof item !== 'object' || Array.isArray(item)) return [];
    const name = (item as JsonObject)['name'];
    if (typeof name !== 'string') return [];
    const field = `/${key}/${i}/secretRef`;
    const stored = detail?.secrets.find((s) => s.field === field);
    return [{ name, secret: stored?.name ?? null }];
  });
}

/** Installed entries of the `mcp-server` kind that C10 adds per server: process, tools, description, signature. */
export function mcpServerView(
  row: ConnectionRow,
  detail: ConnectionDetail | null,
  installed: InstalledConnector | null,
  toolsSnapshot: number | null,
  liveState: string | null,
  catalog: CatalogIndex | null,
): McpServerView {
  const config: JsonObject = detail?.config ?? {};
  const transport = config['transport'] === 'http' ? 'http' : 'stdio';
  const argv = stringArray(config['command']);
  const url = typeof config['url'] === 'string' ? config['url'] : null;
  const catalogEntry = typeof config['catalogEntry'] === 'string' ? config['catalogEntry'] : null;
  const entryId = catalogEntry?.split('@')[0] ?? null;
  const fromCatalog = entryId ? catalog?.entries.find((e) => e.kind === 'mcp-server' && e.id === entryId) : undefined;
  const pkg = transport === 'stdio' ? npmPackage(argv) : null;
  const version = catalogEntry?.includes('@') ? catalogEntry.split('@').at(-1)! : pkg?.version || installed?.version || null;
  const description =
    installed?.description ||
    fromCatalog?.description ||
    (transport === 'http' ? `Calls ${url ?? 'its URL'} over Streamable HTTP.` : 'Runs over stdio on this computer.');
  const prefix = typeof config['toolPrefix'] === 'string' && config['toolPrefix'] ? (config['toolPrefix'] as string) : defaultToolPrefix(row.name);
  const idle = config['idleStopMinutes'];
  return {
    name: row.name,
    env: row.env,
    transport,
    command: argv.length ? formatArgv(argv) : null,
    url,
    secrets: transport === 'http' ? bindings(config, 'headers', detail) : bindings(config, 'env', detail),
    inherited: stringArray(config['inheritEnv']),
    catalogEntry,
    version,
    pinned: catalogEntry ? true : pkg ? !!pkg.version : null,
    packageName: pkg?.name || null,
    signed: catalogEntry ? (installed?.signed ?? fromCatalog?.signed ?? true) : (installed?.signed ?? null),
    description,
    tools: installed?.tools ?? toolsSnapshot,
    state: liveState ?? installed?.process?.state ?? null,
    usedBy: row.usedBy ?? [],
    start: config['start'] === 'with-core' ? 'with-core' : 'on-first-call',
    idleStopMinutes: typeof idle === 'number' ? idle : 15,
    toolPrefix: prefix,
    verified: row.verified && (detail?.verified ?? true),
  };
}

/** The Status column of MCP servers and the link on its Installed card: "Running", "Sleeping", "Not started yet". */
export function serverStatus(state: string | null): ConnectorCardStatus {
  return processStatus(state ? { state, rssMb: null } : null) ?? { tone: 'neutral', text: 'Not started yet' };
}

/** "not on an endpoint yet", "on dev", "on dev and incident-prod". */
export function onEndpointsText(endpoints: readonly string[]): string {
  return endpoints.length ? `on ${joinAnd(endpoints)}` : 'not on an endpoint yet';
}

/** The activity filter of a server's tools: `tool:github_*`. */
export function serverActivityQuery(s: Pick<McpServerView, 'toolPrefix'>): string {
  return `tool:${s.toolPrefix}*`;
}

/** The workspace and log of a server's process (§7.5): `~/.gaterook/mcp/<name>`, `logs/mcp/<name>.log`. */
export function serverPaths(name: string): { readonly workdir: string; readonly log: string } {
  return { workdir: `~/.gaterook/mcp/${name}`, log: `~/.gaterook/logs/mcp/${name}.log` };
}
