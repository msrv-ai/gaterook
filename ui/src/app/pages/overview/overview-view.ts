import { ClientCard, ClientId } from '../../core/api/clients.api';
import { ConnectionRow, ConnectionUsedBy, StoredSecret } from '../../core/api/connections.api';
import { HealthRow, Overview, OverviewStats } from '../../core/api/overview.api';
import { TunnelState } from '../../core/api/tunnel.api';
import { Env } from '../../core/api/types';
import { duration, formatCount } from '../../core/time/time-format';
import { connectorIcon, connectorName } from '../../kit/elements/shared/connector-icons';
import { healthLook } from '../../kit/elements/shared/dictionaries';
import { TileState, TilePart } from '../../kit/elements/status-tile/status-tile';

/**
 * What Overview shows (design/pages/overview.html, BLUEPRINT §5 overview, COMPONENTS.md › status-tile), computed from
 * the admin API: pure functions of the answers, so the tests check them against the kit's wording without a clock or
 * a core. The page binds them; nothing here invents a number the core didn't give.
 */

// ── The three states ─────────────────────────────────────────────────────────────────────────────────────────────

/**
 * - `first-run`: no connection yet — the three-step guide, step 1 current;
 * - `setup`: a connection, no AI app connected yet — step 1 done, step 2 current;
 * - `in-use`: an AI app is connected (a link of the AI apps page, or a named token) — the home screen.
 */
export type OverviewState = 'first-run' | 'setup' | 'in-use';

/** How many connections the core has (its setup map counts them; external MCP servers aren't connections here). */
export function connectionCount(o: Overview): number {
  return o.setupMap.nodes.find((n) => n.id === 'connections')?.count ?? o.health.length;
}

/**
 * The state from the core's own facts: its connections, and its getting-started step "client" — done once an AI app
 * is connected through the AI apps page or a named token was issued (`clients.ClientService.progress`).
 */
export function overviewState(o: Overview): OverviewState {
  if (connectionCount(o) === 0) return 'first-run';
  return o.gettingStarted.steps.find((s) => s.id === 'client')?.state === 'done' ? 'in-use' : 'setup';
}

// ── Your connections ─────────────────────────────────────────────────────────────────────────────────────────────

/** One connection as a tile: its name and environment, type icon, one meta line, its state in words. */
export interface ConnectionTile {
  readonly name: string;
  readonly env: Env;
  readonly icon: string;
  readonly meta: readonly TilePart[];
  readonly state: TileState;
  /** Unreachable: the tile's one action is Test again. */
  readonly retest: boolean;
}

const TONE_RANK: Record<string, number> = { danger: 0, warning: 1, success: 2, info: 3, neutral: 4, brand: 5 };
const ENV_RANK: Record<string, number> = { dev: 0, stage: 1, prod: 2 };

/** The tile's state: "Healthy", "Slow · 1.8 s", "Unreachable", "Not checked yet" (kit tiles name the latency only when slow). */
export function tileHealth(row: Pick<HealthRow, 'state' | 'latencyMs'>): TileState {
  const look = healthLook(row.state, null);
  if (look.tone === 'warning' && row.latencyMs != null) return { tone: 'warning', label: `Slow · ${duration(row.latencyMs)}` };
  return { tone: look.tone === 'info' ? 'info' : look.tone, label: look.text };
}

/** A problem shows first and is never folded away: unreachable or slow. */
export function isProblem(row: Pick<HealthRow, 'state'>): boolean {
  const tone = healthLook(row.state).tone;
  return tone === 'danger' || tone === 'warning';
}

export function connectionTile(row: HealthRow): ConnectionTile {
  const state = tileHealth(row);
  const type = row.typeLabel || connectorName(row.type);
  // Where it connects, only when it can't be reached there (kit: "HTTP API · billing.stage.acme.dev", no scheme).
  const host = row.host ? row.host.replace(/^[a-z][a-z0-9+.-]*:\/\//i, '') : null;
  const meta: TilePart[] = state.tone === 'danger' && host ? [{ text: `${type} · ` }, { text: host, mono: true }] : [{ text: type }];
  return { name: row.name, env: row.env, icon: connectorIcon(row.type), meta, state, retest: state.tone === 'danger' };
}

/** The set of tiles: problems first, one row in view, the rest behind "N more connections, all healthy". */
export interface ConnectionTiles {
  readonly shown: readonly ConnectionTile[];
  readonly more: readonly ConnectionTile[];
  /** The disclosure's words; null when nothing is folded. */
  readonly moreLabel: string | null;
  /** "12 connections: 10 healthy, 1 slow, 1 unreachable. The tag shows each one's environment: dev, stage or prod." */
  readonly description: string;
}

/** Tiles in one row of `gw-grid --3`. */
export const ROW = 3;

const STATE_WORDS: readonly { readonly tone: string; readonly word: string }[] = [
  { tone: 'success', word: 'healthy' },
  { tone: 'warning', word: 'slow' },
  { tone: 'danger', word: 'unreachable' },
  { tone: 'info', word: 'being checked' },
  { tone: 'neutral', word: 'not checked yet' },
];

function stateParts(tiles: readonly ConnectionTile[]): string[] {
  return STATE_WORDS.map((s) => ({ s, n: tiles.filter((t) => t.state.tone === s.tone).length }))
    .filter((x) => x.n > 0)
    .map((x) => `${formatCount(x.n)} ${x.s.word}`);
}

export function connectionTiles(rows: readonly HealthRow[]): ConnectionTiles {
  const tiles = rows
    .map((r, i) => ({ t: connectionTile(r), i }))
    .sort((a, b) => TONE_RANK[a.t.state.tone] - TONE_RANK[b.t.state.tone] || (ENV_RANK[a.t.env] ?? 3) - (ENV_RANK[b.t.env] ?? 3) || a.i - b.i)
    .map((x) => x.t);
  const problems = tiles.filter((t) => t.state.tone === 'danger' || t.state.tone === 'warning').length;
  const visible = Math.max(ROW, problems);
  const shown = tiles.slice(0, visible);
  const more = tiles.slice(visible);
  let moreLabel: string | null = null;
  if (more.length) {
    const parts = stateParts(more);
    const n = more.length;
    const noun = n === 1 ? 'more connection' : 'more connections';
    moreLabel = parts.length === 1 ? `${formatCount(n)} ${noun}, ${n === 1 ? '' : 'all '}${parts[0].replace(/^[\d,]+ /, '')}` : `${formatCount(n)} ${noun}: ${parts.join(', ')}`;
  }
  const total = tiles.length;
  const counted = total === 1 ? `1 connection: ${stateParts(tiles)[0]?.replace(/^1 /, '') ?? ''}` : `${formatCount(total)} connections: ${stateParts(tiles).join(', ')}`;
  return {
    shown,
    more,
    moreLabel,
    description: `${counted}. The tag shows each one's environment: dev, stage or prod.`,
  };
}

/** What one Test again found for a connection. */
export interface RetestResult {
  readonly name: string;
  readonly ok: boolean;
  readonly latencyMs: number | null;
  /** What failed and what to do (the connector's words), when it failed. */
  readonly message: string | null;
}

/** The toast after Test again (kit: "billing-api is still unreachable" + what the host didn't do, in danger). */
export function retestToast(r: RetestResult): { readonly tone: 'success' | 'danger'; readonly title: string; readonly body: string } {
  return r.ok
    ? { tone: 'success', title: `${r.name} is reachable again`, body: r.latencyMs !== null ? `It answered in ${duration(r.latencyMs)}.` : 'It answered.' }
    : { tone: 'danger', title: `${r.name} is still unreachable`, body: r.message ?? 'Check the VPN or the host, then test again.' };
}

// ── AI apps ──────────────────────────────────────────────────────────────────────────────────────────────────────

/** The id part of the kit's anchors on AI apps (`clients.html#cl-cu-t`): the card each app's link opens. */
const ANCHORS: Record<string, string> = {
  'claude-desktop': 'cd',
  codex: 'cx',
  cursor: 'cu',
  antigravity: 'ag',
  'claude-ai': 'ai',
};

/** `cl-cu-t`: the fragment of an app's card on AI apps (its heading). */
export function appAnchor(client: ClientId | 'claude-ai' | string): string {
  return `cl-${ANCHORS[client] ?? client}-t`;
}

/** One AI app as a tile. */
export interface AppTile {
  readonly client: string;
  readonly name: string;
  readonly meta: string;
  readonly state: TileState;
  /** The app has its own card to open (connected, needing a restart, failing, claude.ai): the title links there. */
  readonly linked: boolean;
  /**
   * The tile's one action: Connect (found, not connected) or How to install (not found); null for a connected app.
   * Both lead to the app's card on AI apps (`/clients#cl-<app>-t`).
   */
  readonly action: 'connect' | 'install' | null;
}

/** An app the guide or the home screen offers to connect, or one to install (a card's state in the tile's words). */
export function guideAppTile(card: ClientCard): AppTile {
  const installed = card.status !== 'not-installed';
  if (!installed) {
    return { client: card.client, name: card.name, meta: 'Not found on this computer', state: { tone: 'neutral', label: 'Not installed' }, linked: false, action: 'install' };
  }
  return { client: card.client, name: card.name, meta: 'Found on this computer', state: { tone: 'info', label: 'Not connected' }, linked: false, action: 'connect' };
}

/**
 * "Can use 11 connections, 3 of them prod": the connections on the endpoints the app's entries reach, each once.
 * Without the connections list, the endpoints themselves ("Endpoint dev", "Endpoints dev and incident-prod").
 */
export function canUse(card: ClientCard, connections: readonly Pick<ConnectionRow, 'name' | 'env' | 'usedBy'>[] | null): string {
  const endpoints = [...new Set(card.links.map((l) => l.endpoint))];
  if (!endpoints.length) return '';
  if (!connections) {
    return endpoints.length === 1 ? `Endpoint ${endpoints[0]}` : `Endpoints ${andWords(endpoints)}`;
  }
  const reached = connections.filter((c) => c.usedBy.some((e) => endpoints.includes(e)));
  if (!reached.length) return endpoints.length === 1 ? `Endpoint ${endpoints[0]}, no connections yet` : 'No connections yet';
  const prod = reached.filter((c) => c.env === 'prod').length;
  const n = reached.length;
  const can = `Can use ${formatCount(n)} ${n === 1 ? 'connection' : 'connections'}`;
  if (!prod) return can;
  return prod === n ? `${can}, ${n === 1 ? 'it is' : 'all'} prod` : `${can}, ${formatCount(prod)} of them prod`;
}

function andWords(words: readonly string[]): string {
  return words.length <= 1 ? (words[0] ?? '') : `${words.slice(0, -1).join(', ')} and ${words.at(-1)}`;
}

/**
 * The home screen's AI apps (kit "AI apps", 2 × 2): an app found and not connected first, with Connect; problems
 * next; connected apps with what they can use; claude.ai, which comes in through the tunnel, last. An app that isn't
 * installed stays on AI apps: here it would be noise.
 */
export function homeAppTiles(
  cards: readonly ClientCard[],
  connections: readonly Pick<ConnectionRow, 'name' | 'env' | 'usedBy'>[] | null,
  claudeAi: AppTile | null,
): AppTile[] {
  const rank = (c: ClientCard) => (c.status === 'detected' ? 0 : c.status === 'error' ? 1 : c.status === 'needs-restart' ? 2 : 3);
  const tiles = cards
    .filter((c) => c.status !== 'not-installed')
    .map((c, i) => ({ c, i }))
    .sort((a, b) => rank(a.c) - rank(b.c) || a.i - b.i)
    .map(({ c }): AppTile => {
      switch (c.status) {
        case 'detected':
          return guideAppTile(c);
        case 'needs-restart':
          return { client: c.client, name: c.name, meta: c.statusText || 'Restart it to load GateRook', state: { tone: 'warning', label: 'Needs restart' }, linked: true, action: null };
        case 'error':
          return { client: c.client, name: c.name, meta: c.statusText || c.warnings[0] || 'Open it to see what failed', state: { tone: 'danger', label: 'Error' }, linked: true, action: null };
        default:
          return { client: c.client, name: c.name, meta: canUse(c, connections), state: { tone: 'success', label: 'Connected' }, linked: true, action: null };
      }
    });
  return claudeAi ? [...tiles, claudeAi] : tiles;
}

/**
 * claude.ai, through the tunnel (kit: "In the browser · used until 12:10", Tunnel off): public now, last public
 * until when, or never published; null before the tunnel exists in the core (K3).
 */
export function claudeAiTile(tunnel: TunnelState | null, clock: (at: string) => string): AppTile | null {
  if (!tunnel) return null;
  const tile = (meta: string, state: TileState): AppTile => ({ client: 'claude-ai', name: 'claude.ai', meta, state, linked: true, action: null });
  if (tunnel.state === 'on' || tunnel.state === 'starting') {
    const slug = tunnel.endpoint;
    return tile(tunnel.since ? `In the browser · public since ${clock(tunnel.since)}` : 'In the browser', {
      tone: 'warning',
      label: tunnel.state === 'starting' ? 'Tunnel starting' : slug ? `Public · ${slug}` : 'Public',
    });
  }
  const last = tunnel.history
    .map((h) => h.to)
    .filter((t): t is string => !!t)
    .sort()
    .at(-1);
  return tile(last ? `In the browser · used until ${clock(last)}` : 'In the browser · never published', { tone: 'neutral', label: 'Tunnel off' });
}

// ── Activity ─────────────────────────────────────────────────────────────────────────────────────────────────────

/** The one line about Activity: "1,287 calls from your AI apps in the last 24 hours, 17 denied by policy." */
export function activityLine(stats: Pick<OverviewStats, 'calls24h' | 'denied24h'>): string {
  const calls = stats.calls24h;
  if (!calls) return 'No calls from your AI apps in the last 24 hours. When one calls a tool, it shows up in';
  const denied = stats.denied24h ? `, ${formatCount(stats.denied24h)} denied by policy` : '';
  return `${formatCount(calls)} ${calls === 1 ? 'call' : 'calls'} from your AI apps in the last 24 hours${denied}. Each one is in`;
}

// ── The guide ────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * What to ask the AI app to check that it works, for the connection's type: the kit's question for a database
 * ("Show me the tables in dev-db"), the same idea for the other types; "my database" before any connection exists.
 */
export function checkPrompt(type: string | null, name: string | null): string {
  if (!type || !name) return 'Show me the tables in my database';
  switch (type) {
    case 'postgres':
    case 'mysql':
    case 'sql':
      return `Show me the tables in ${name}`;
    case 'mongodb':
      return `Show me the collections in ${name}`;
    case 'redis':
      return `Show me a few keys in ${name}`;
    case 'kubernetes':
      return `Show me the pods in ${name}`;
    case 'logs':
      return `Show me the latest errors in ${name}`;
    default:
      return `Show me what you can see in ${name}`;
  }
}

/** Where the connection's secret is, for step 1 of setup: encrypted here, or read from a secret manager. */
export interface SecretLine {
  /** "password", "token", "private key", "secret": what the connection signs in with. */
  readonly noun: string;
  readonly where: 'here' | 'manager';
}

/** The word for a secret field (`/credentials/password` → "password"). */
export function secretNoun(field: string): string {
  const key = (field.split('/').at(-1) ?? '').toLowerCase();
  if (key.includes('password') || key === 'pass') return 'password';
  if (key.includes('token')) return 'token';
  if (key.includes('key')) return key.includes('private') || key === 'key' ? 'private key' : 'key';
  return 'secret';
}

/** The connection's first secret: encrypted here (stored, or waiting for a value) or a reference to a manager. */
export function secretLine(secrets: readonly StoredSecret[]): SecretLine | null {
  const s = secrets.find((x) => x.state !== 'reference') ?? secrets.find((x) => x.state === 'reference');
  if (!s) return null;
  return { noun: secretNoun(s.field), where: s.state === 'reference' ? 'manager' : 'here' };
}

/**
 * Step 1 of setup, what the connection does for an AI app: "changes need your approval" when a tool of it waits for
 * you on an endpoint, "read only" when only reads are on, "not on an endpoint yet" without one.
 */
export function modesWords(usedBy: readonly Pick<ConnectionUsedBy, 'mode'>[] | null): string | null {
  if (!usedBy) return null;
  if (!usedBy.length) return 'not on an endpoint yet';
  if (usedBy.some((u) => String(u.mode).toUpperCase() === 'APPROVAL')) return 'changes need your approval';
  if (usedBy.some((u) => String(u.mode).toUpperCase() === 'READ_ONLY')) return 'read only';
  return 'its tools are off';
}
