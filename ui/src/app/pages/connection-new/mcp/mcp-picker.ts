import { signal } from '@angular/core';
import { ConnectorCard, ConnectorGroup } from '../../../core/api/connector-types.api';
import { McpProfile, McpRegistryPage } from '../../../core/api/plugins.api';
import { joinAnd } from '../../connectors/connectors-view';
import { failureText } from '../../connectors/dialogs/failure';
import { FEATURED_MCP_SERVERS, FeaturedMcpServer } from '../../connectors/mcp-featured-servers';
import { PastedServer, parsePastedServer } from '../../connectors/mcp-paste';
import { profileLogo } from '../../connectors/mcp-profiles';
import { RegistryResult, completeRegistryResults, rankRegistryResults, registryResult } from '../../connectors/mcp-registry-results';

/** The connector type of external MCP servers: every card of the picker's MCP section has it. */
export const MCP_SERVER_TYPE = 'mcp-server';

/** The registry the shortcuts trust (Popular cards, `?registry=`): a similarly named entry elsewhere never stands in. */
export const OFFICIAL_REGISTRY = 'registry.modelcontextprotocol.io';

/**
 * What an MCP card of the picker sets up; Configure starts from it. `external` is the manual entry (a command or a URL),
 * `registry` an entry read from the MCP registry `registry` (a Popular card's entry is read from the Official MCP
 * Registry on Continue), `catalog` an entry of GateRook's signed catalog (Connectors › Catalog).
 */
export type McpChoice =
  | { readonly origin: 'external'; readonly transport?: 'stdio' | 'http' }
  | { readonly origin: 'profile'; readonly profile: McpProfile }
  | { readonly origin: 'registry'; readonly result: RegistryResult; readonly registry: string | null }
  | { readonly origin: 'paste'; readonly server: PastedServer }
  | { readonly origin: 'catalog'; readonly id: string; readonly version: string; readonly name: string };

/** The External MCP server card's key: the type alone, as the core's card had it (`?type=mcp-server`). */
export const EXTERNAL_KEY = MCP_SERVER_TYPE;
export const PASTE_KEY = `${MCP_SERVER_TYPE}:paste`;

export function isMcpKey(key: string | null): boolean {
  return !!key && (key === MCP_SERVER_TYPE || key.startsWith(`${MCP_SERVER_TYPE}:`));
}

export function profileKey(id: string): string {
  return `${MCP_SERVER_TYPE}:profile.${id}`;
}

export function featuredKey(f: Pick<FeaturedMcpServer, 'registryName'>): string {
  return `${MCP_SERVER_TYPE}:featured.${f.registryName.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`;
}

export function registryKey(serverName: string): string {
  return `${MCP_SERVER_TYPE}:registry.${serverName.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`;
}

function card(variant: string | null, name: string, description: string, extra: Partial<ConnectorCard>): ConnectorCard {
  return {
    type: MCP_SERVER_TYPE, variant, name, icon: 'plug', description, kind: 'mcp-server', version: null, signed: null,
    tools: null, toolsFrom: 'server', connections: 0, available: true, reason: null, presetConfig: null, catalogRef: null,
    ...extra,
  };
}

/** The manual entry: any server by its command or URL, first in the MCP section. */
export const EXTERNAL_CARD: ConnectorCard = card(
  null,
  'External MCP server',
  'Enter any server by hand: a command this computer runs, or a URL GateRook signs in to. Have its configuration from the docs? Paste it into the search above.',
  { markSrc: 'assets/integrations/mcp.svg', metaText: 'MCP server · stdio or HTTP', searchWords: 'mcp external custom own other manual command url stdio http model context protocol' },
);

/** "Sign-in in your browser", "No sign-in", "Microsoft Entra ID": a profile's sign-in in a few words, else nothing. */
function signInWords(p: McpProfile): string | null {
  if (p.transport === 'stdio') return 'Runs on this computer';
  const s = p.signIn.toLowerCase();
  if (s.startsWith('none')) return 'No sign-in';
  if (s.includes('entra')) return 'Microsoft Entra ID';
  if (s.includes('oauth')) return 'Sign-in in your browser';
  return null;
}

/**
 * Other names a developer types for an MCP server's service (its vendor's own words for it): "postgres" finds Supabase's
 * and Neon's servers next to the PostgreSQL connector, "git" GitHub's, "jira" Atlassian's.
 */
const MCP_ALIASES: Readonly<Record<string, string>> = {
  supabase: 'postgres postgresql',
  neon: 'postgres postgresql serverless branches',
  mongodb: 'mongo atlas',
  github: 'git pull requests issues actions',
  atlassian: 'jira confluence',
  'azure devops': 'ado git boards pipelines',
  firebase: 'firestore google',
  cloudflare: 'workers dns',
  'cloudflare api': 'workers dns r2 zero trust',
  'aws knowledge': 'amazon documentation',
  playwright: 'browser e2e testing',
  'chrome devtools': 'browser performance',
  notion: 'wiki docs',
  stripe: 'payments billing',
};

function aliases(name: string): string {
  return MCP_ALIASES[name.toLowerCase().replace(/\s*\(.*\)$/, '')] ?? '';
}

/** A light-tile variant of a mark whose own style turns it white in dark themes (DigitalOcean's site icon). */
const ON_LIGHT: Readonly<Record<string, string>> = { 'mcp-logos/digitalocean.svg': 'mcp-logos/digitalocean-on-light.svg' };

function onLight(src: string | null): string | null {
  return src ? (ON_LIGHT[src] ?? src) : null;
}

export function profileCard(p: McpProfile): ConnectorCard {
  const how = signInWords(p);
  return card(`profile.${p.id}`, p.name, p.description, {
    markSrc: onLight(profileLogo(p)) ?? 'assets/integrations/mcp.svg',
    metaText: ['MCP server', p.transport === 'http' ? 'HTTP' : 'stdio', how].filter(Boolean).join(' · '),
    badge: 'verified',
    searchWords: [p.id, p.name, p.publisher, p.category, aliases(p.name), 'mcp verified profile'].join(' '),
  });
}

export function featuredCard(f: FeaturedMcpServer): ConnectorCard {
  return card(featuredKey(f).slice(MCP_SERVER_TYPE.length + 1), f.name, f.description, {
    markSrc: onLight(f.logo),
    metaText: `MCP server · ${f.category}`,
    searchWords: [f.name, f.category, aliases(f.name), 'mcp popular'].join(' '),
  });
}

export function registryCard(r: RegistryResult): ConnectorCard {
  const transport = r.use.kind === 'remote' ? 'HTTP' : r.use.kind === 'package' ? 'stdio' : null;
  const version = r.server.version ? (r.server.version.startsWith('v') ? r.server.version : `v${r.server.version}`) : null;
  const description = r.server.description || r.server.name;
  return card(registryKey(r.server.name).slice(MCP_SERVER_TYPE.length + 1), r.title, description, {
    markSrc: 'assets/integrations/mcp.svg',
    metaText: ['MCP server', transport, version].filter(Boolean).join(' · '),
    badge: r.server.status === 'deprecated' ? 'deprecated' : r.officialPublisher ? 'official' : null,
    available: r.use.kind !== 'none',
    reason: r.use.kind === 'none' ? `${description} ${r.use.reason}` : null,
    searchWords: '',
  });
}

/**
 * The MCP section before the registry: External MCP server, then GateRook's verified profiles, then the Popular picks
 * that have no profile (a vendor with one shows once, as its verified card).
 */
export function mcpGroups(profiles: readonly McpProfile[]): ConnectorGroup[] {
  const profiled = new Set(profiles.map((p) => p.registryName).filter(Boolean));
  const popular = FEATURED_MCP_SERVERS.filter((f) => !profiled.has(f.registryName));
  return [
    { name: 'Any MCP server', cards: [EXTERNAL_CARD] },
    ...(profiles.length ? [{
      name: 'Verified MCP servers',
      description: "The vendors' own servers with GateRook's checked profile: their read-only switches are set, and tools that run code or hand work to another agent start in Approval or Off.",
      cards: profiles.map(profileCard),
    }] : []),
    {
      name: 'Popular MCP servers',
      description: 'Picks for coding, testing and shipping, set up from the Official MCP Registry.',
      cards: popular.map(featuredCard),
    },
  ];
}

// ─── A paste in the search box ───

/**
 * What the search box read from a paste: an MCP server (a configuration from the docs, a `claude mcp add` line, an
 * `npx` command, a URL), a PostgreSQL connection string, or text that looks like a configuration and isn't one.
 */
export type PasteRead =
  | { readonly kind: 'mcp'; readonly server: PastedServer }
  | { readonly kind: 'db'; readonly db: DatabaseUrl }
  | { readonly kind: 'error'; readonly message: string };

/** A PostgreSQL connection string's parts that fill Configure; its password is never kept. */
export interface DatabaseUrl {
  readonly type: 'postgres';
  readonly host: string;
  readonly port: number;
  readonly database: string | null;
  readonly user: string | null;
  readonly hadPassword: boolean;
}

/** A URL, a command or a configuration: never a name to search for (and never sent to the registry). */
export function looksLikePaste(text: string): boolean {
  const t = text.trim();
  return /^[a-z][a-z0-9+.-]*:\/\//i.test(t)
    || /^[{[]/.test(t)
    || /^"[^"]+"\s*:/.test(t)
    || /\n/.test(t)
    || /^(\S*\/)?(npx|bunx|pnpm|node|deno|uvx|uv|docker|python3?)\s/.test(t)
    || /^(claude|codex)\s+mcp\s/.test(t);
}

export function parseDatabaseUrl(text: string): DatabaseUrl | null {
  const t = text.trim();
  if (!/^postgres(ql)?:\/\//i.test(t)) return null;
  let url: URL;
  try {
    url = new URL(t.replace(/^postgres(ql)?:/i, 'http:'));
  } catch {
    return null;
  }
  if (!url.hostname) return null;
  const database = decodeURIComponent(url.pathname.replace(/^\//, '')) || null;
  return {
    type: 'postgres',
    host: url.hostname.replace(/^\[|\]$/g, ''),
    port: url.port ? Number(url.port) : 5432,
    database,
    user: url.username ? decodeURIComponent(url.username) : null,
    hadPassword: !!url.password,
  };
}

export function readPaste(text: string): PasteRead | null {
  if (!looksLikePaste(text)) return null;
  const db = parseDatabaseUrl(text);
  if (db) return { kind: 'db', db };
  if (/^[a-z][a-z0-9+.-]*:\/\//i.test(text.trim()) && !/^https?:\/\//i.test(text.trim())) {
    return { kind: 'error', message: 'GateRook reads PostgreSQL connection strings and MCP server URLs. Clear the search, then choose the type and fill in where it is.' };
  }
  const r = parsePastedServer(text);
  return 'error' in r ? { kind: 'error', message: r.error } : { kind: 'mcp', server: r };
}

/** The mark of a pasted server: a Popular pick's, when its name says which one ("linear", "sentry-mcp"). */
function pastedMark(name: string): string {
  const bare = name.toLowerCase().replace(/-mcp$/, '');
  const known = FEATURED_MCP_SERVERS.find((f) => f.name.toLowerCase().replace(/\s+/g, '-') === bare);
  return onLight(known?.logo ?? null) ?? 'assets/integrations/mcp.svg';
}

/** The "From your paste" group: the one card read from it, chosen; its description says what isn't kept. */
export function pasteGroup(read: PasteRead, postgres: ConnectorCard | null): ConnectorGroup | null {
  if (read.kind === 'error') return null;
  if (read.kind === 'db') {
    if (!postgres) return null;
    const d = read.db;
    const at = `${d.host}:${d.port}${d.database ? `/${d.database}` : ''}${d.user ? ` as ${d.user}` : ''}`;
    return {
      name: 'From your paste',
      description: `${joinAnd(['Host', 'port', ...(d.database ? ['database'] : []), ...(d.user ? ['user'] : [])])} fill Configure.${d.hadPassword ? ' The password in it isn’t copied: you enter it there.' : ''}`,
      cards: [{
        ...postgres,
        variant: 'paste',
        description: `${at}.`,
        // The PostgreSQL form's own places: Connection (host, port, the databases agents may use) and Credentials.
        presetConfig: {
          connection: { host: d.host, port: d.port, ...(d.database ? { databases: [d.database] } : {}) },
          ...(d.user ? { credentials: { user: d.user } } : {}),
        },
        metaText: `Built-in · ${postgres.tools ?? 4} tools · from a connection string`,
        searchWords: '',
      }],
    };
  }
  const s = read.server;
  const http = s.transport === 'http';
  const values = s.variables.length
    ? `Values of ${s.variables.join(', ')} aren’t copied: the review asks for each one, and GateRook stores it encrypted.`
    : 'Values of keys and tokens in it aren’t copied: the review asks for each one, and GateRook stores it encrypted.';
  const others = s.others ? ` It named ${s.others + 1} servers; this is the first.` : '';
  return {
    name: 'From your paste',
    description: `${values}${others}`,
    cards: [card('paste', s.name || 'MCP server', http ? `At ${s.url}. GateRook signs in to it for you after the review.` : `Runs ${s.command} on this computer, after the review.`, {
      markSrc: pastedMark(s.name),
      metaText: `MCP server · ${http ? 'HTTP' : 'stdio'} · Read from your paste`,
      searchWords: '',
    })],
  };
}

// ─── The Official MCP Registry, searched from the picker ───

/**
 * The registry part of the picker's search: from 3 characters, 350 ms after the last key; Enter searches at once. An
 * answer for an older query is dropped. `more` loads the next page.
 */
export class RegistryFinder {
  readonly results = signal<readonly RegistryResult[]>([]);
  /** The query the results are for, or null before any answer. */
  readonly searched = signal<string | null>(null);
  readonly searching = signal(false);
  readonly moreBusy = signal(false);
  readonly nextCursor = signal<string | null>(null);
  readonly registry = signal<string | null>(null);
  readonly error = signal<string | null>(null);
  private query = '';
  private timer: ReturnType<typeof setTimeout> | null = null;
  private seq = 0;

  constructor(private readonly search: (q: string, cursor?: string | null) => Promise<McpRegistryPage>) {}

  /** A query as typed: below 3 characters (or a paste) there is no registry search. */
  find(text: string, now = false): void {
    const q = text.trim();
    if (q === this.query && !now && (this.searching() || this.searched() === q)) return;
    this.query = q;
    this.stop();
    this.error.set(null);
    if (q.length < 3 || looksLikePaste(q)) {
      this.results.set([]);
      this.searched.set(null);
      this.nextCursor.set(null);
      return;
    }
    this.searching.set(true);
    if (now) {
      void this.run(q);
      return;
    }
    this.timer = setTimeout(() => {
      this.timer = null;
      void this.run(q);
    }, 350);
  }

  retry(): void {
    this.find(this.query, true);
  }

  /** Cancels a search on its way (the page goes, the query changes). */
  stop(): void {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    this.seq++;
    this.searching.set(false);
    this.moreBusy.set(false);
  }

  private async run(q: string): Promise<void> {
    const seq = ++this.seq;
    this.searching.set(true);
    try {
      const page = await this.search(q);
      if (seq !== this.seq) return;
      const results = await completeRegistryResults(page, q, (name) => this.search(name));
      if (seq !== this.seq) return;
      this.results.set(results);
      this.nextCursor.set(page.nextCursor ?? null);
      this.registry.set(page.registry);
      this.searched.set(q);
    } catch (err) {
      if (seq !== this.seq) return;
      this.results.set([]);
      this.nextCursor.set(null);
      this.searched.set(null);
      this.error.set(failureText(err, 'This build of the gateway core can’t search the MCP registry.'));
    } finally {
      if (seq === this.seq) this.searching.set(false);
    }
  }

  async more(): Promise<RegistryResult | null> {
    const cursor = this.nextCursor();
    const q = this.searched();
    if (!cursor || q === null || this.moreBusy() || this.searching() || q !== this.query) return null;
    const seq = this.seq;
    this.moreBusy.set(true);
    try {
      const page = await this.search(q, cursor);
      if (seq !== this.seq) return null;
      const shown = new Set(this.results().map((r) => r.server.name));
      const added = page.servers.filter((s) => !shown.has(s.name)).map((s) => registryResult(s, page.registry));
      this.results.update((rows) => rankRegistryResults([...rows, ...added]));
      this.nextCursor.set(page.nextCursor ?? null);
      return this.results().find((row) => added.some((a) => a.server.name === row.server.name)) ?? null;
    } catch (err) {
      if (seq === this.seq) this.error.set(failureText(err, 'This build of the gateway core can’t search the MCP registry.'));
      return null;
    } finally {
      if (seq === this.seq) this.moreBusy.set(false);
    }
  }
}

/**
 * "the Official MCP Registry", or "the MCP registry at <host>" when the core is set to another one. Before the first
 * answer names it, the registry is the core's default, the official one.
 */
export function registryLabel(registry: string | null): string {
  return registry && registry !== OFFICIAL_REGISTRY ? `the MCP registry at ${registry}` : 'the Official MCP Registry';
}

/**
 * One server of the Official MCP Registry by its exact name (a Popular card, `?registry=`): never a similarly named
 * entry, and only from the official registry.
 */
export async function officialEntry(
  search: (q: string) => Promise<McpRegistryPage>,
  name: string,
): Promise<RegistryResult> {
  const page = await search(name);
  if (page.registry !== OFFICIAL_REGISTRY) {
    throw new Error('This shortcut requires the Official MCP Registry. Search your configured registry or enter the server by hand.');
  }
  const server = page.servers.find((s) => s.name === name && s.status === 'active');
  if (!server) throw new Error('This server is no longer available in the Official MCP Registry. Search for another server or enter it by hand.');
  const result = registryResult(server, page.registry);
  if (result.use.kind === 'none') throw new Error(result.use.reason);
  return result;
}

/**
 * One server of the registry the core searches, by its exact name: a search result Connectors opened (`?registryServer=`).
 * Unlike a Popular shortcut it may come from a configured registry and be deprecated, as the result showed it.
 */
export async function registryEntry(
  search: (q: string) => Promise<McpRegistryPage>,
  name: string,
): Promise<{ readonly result: RegistryResult; readonly registry: string }> {
  const page = await search(name);
  const server = page.servers.find((s) => s.name === name);
  if (!server) throw new Error(`${registryLabel(page.registry).replace(/^the/, 'The')} no longer lists this server. Search for another server or enter it by hand.`);
  const result = registryResult(server, page.registry);
  if (result.use.kind === 'none') throw new Error(result.use.reason);
  return { result, registry: page.registry };
}
