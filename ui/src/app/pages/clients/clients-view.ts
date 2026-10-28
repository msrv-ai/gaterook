import { isApiError } from '../../core/api/api-error';
import {
  BridgeRemoved,
  ClientCard,
  ClientLink,
  ConnectPreview,
  ConnectRequest,
  ConnectResult,
  DisconnectResult,
  ManualSetup,
  configEditProblem,
} from '../../core/api/clients.api';
import { EndpointSummary, TokenRow } from '../../core/api/endpoints.api';
import { Overview } from '../../core/api/overview.api';
import { OAuthGrant, PublishPreview, TunnelState } from '../../core/api/tunnel.api';
import { Env, Iso } from '../../core/api/types';
import { clockTime, formatCount, shortDate } from '../../core/time/time-format';
import { ClientFailure, ConfigEntry, andList, clientFailure, fileName, keeps } from '../../kit/elements/client-card/client-view';
import { TextPart, literalParts } from '../../kit/elements/shared/literals';

/**
 * What the Clients screen says (design/pages/clients.html; ARCHITECTURE.md §8.4 "clients"), as pure functions of
 * the core's answers: the header's counts, where each card goes, the toasts and announcements of Connect,
 * Disconnect and Scan, "Connect failed", the manual setup's snippets and the claude.ai card's rows. Tested against the
 * kit's wording.
 */

// ── Header and layout ───────────────────────────────────────────────────────────────────────────────────────────

export interface Count {
  readonly tone: 'success' | 'info' | 'warning' | 'danger' | 'neutral';
  readonly text: string;
}

/** "2 connected", "1 detected, not connected", "1 not installed" (+ restart and error counts when there are any). */
export function headerCounts(cards: readonly ClientCard[]): Count[] {
  const n = (s: ClientCard['status']) => cards.filter((c) => c.status === s).length;
  const out: Count[] = [];
  const add = (count: number, tone: Count['tone'], text: string) => {
    if (count) out.push({ tone, text: `${formatCount(count)} ${text}` });
  };
  add(n('connected'), 'success', 'connected');
  add(n('needs-restart'), 'warning', n('needs-restart') === 1 ? 'needs restart' : 'need restart');
  add(n('error'), 'danger', n('error') === 1 ? 'needs you' : 'need you');
  add(n('detected'), 'info', 'detected, not connected');
  add(n('not-installed'), 'neutral', 'not installed');
  return out;
}

/** Where each card goes on the page. */
export interface ClientsLayout {
  /** The first detected client: the only thing to do here, so it comes first, wide, with Connect open inline. */
  readonly inline: ClientCard | null;
  /** Clients on several endpoints or through the bridge extension: wide, one row per endpoint. */
  readonly wide: ClientCard[];
  /** Everyone else, two by two. */
  readonly grid: ClientCard[];
}

export function clientsLayout(cards: readonly ClientCard[], wide: (c: ClientCard) => boolean): ClientsLayout {
  const inline = cards.find((c) => c.status === 'detected' && c.links.length === 0) ?? null;
  const rest = cards.filter((c) => c !== inline);
  return { inline, wide: rest.filter((c) => wide(c)), grid: rest.filter((c) => !wide(c)) };
}

/** The scan found no AI app at all: "No AI apps found" stands in for the cards. */
export function noClients(cards: readonly ClientCard[]): boolean {
  return cards.every((c) => !c.detected && c.links.length === 0);
}

/** What Scan again tells a screen reader when it is done (kit: "Scan finished. Nothing changed: …"). */
export function scanSummary(before: readonly ClientCard[] | null, after: readonly ClientCard[]): string {
  const counts = headerCounts(after).map((c) => c.text).join(', ');
  const key = (cards: readonly ClientCard[]) => cards.map((c) => `${c.client}:${c.status}:${c.links.length}`).join('|');
  if (!after.some((c) => c.detected || c.links.length)) return 'Scan finished. No AI apps found on this computer.';
  return before && key(before) === key(after) ? `Scan finished. Nothing changed: ${counts}.` : `Scan finished: ${counts}.`;
}

/** Check again of one client: what the scan says about it. */
export function checkSummary(after: readonly ClientCard[], client: ClientCard['client'], name: string): string {
  const card = after.find((c) => c.client === client);
  if (!card || card.status === 'not-installed') return `${name} is still not installed.`;
  return `${name} found on this computer: ${card.statusText.toLowerCase()}.`;
}

// ── First run and setup (kit clients.html ?state=first-run | setup) ─────────────────────────────────────────────

const ANCHOR: Readonly<Record<string, string>> = { 'claude-desktop': 'cd', codex: 'cx', cursor: 'cu', antigravity: 'ag', 'claude-ai': 'ai' };

/** `cl-cd`: the base of an app's ids on this page (`cl-cd-t` its card's heading, `cl-cd-row` its row, `cl-cd-s` its review). */
export function anchorBase(client: string): string {
  return `cl-${ANCHOR[client] ?? client}`;
}

/** The app a fragment names (`#cl-cu-t`, from Overview's tiles and guide), or null. */
export function anchorClient(fragment: string | null): string | null {
  const m = /^cl-([a-z0-9-]+)-t$/.exec(fragment ?? '');
  if (!m) return null;
  return Object.entries(ANCHOR).find(([, short]) => short === m[1])?.[0] ?? null;
}

/** The first connection and its endpoint, as Overview's guide names them (getting started, health). */
export interface FirstConnection {
  readonly name: string;
  readonly env: Env | null;
  readonly type: string | null;
  /** "PostgreSQL 16". */
  readonly typeName: string | null;
  readonly endpoint: string | null;
}

export function firstConnection(o: Overview): FirstConnection | null {
  const detail = (id: string) => o.gettingStarted.steps.find((s) => s.id === id)?.detail ?? {};
  const name = detail('connection')['name'];
  if (typeof name !== 'string' || !name) return null;
  const row = o.health.find((h) => h.name === name) ?? null;
  const typeName = detail('connection')['typeName'];
  const endpoint = detail('endpoint')['name'];
  return {
    name,
    env: row?.env ?? null,
    type: row?.type ?? null,
    typeName: typeof typeName === 'string' ? typeName : (row?.typeLabel ?? null),
    endpoint: typeof endpoint === 'string' && endpoint ? endpoint : null,
  };
}

/**
 * The header's description outside "in use" (kit): first run names the apps already found and says what comes first;
 * setup names the connection the app will read.
 */
export function stageDescription(stage: 'first-run' | 'setup', cards: readonly ClientCard[], first: FirstConnection | null): { readonly text: string; readonly literals: readonly string[] } {
  if (stage === 'setup') {
    const reading = first ? `, and it can start reading ${first.name}` : '';
    return {
      text: `Connect the AI app you use${reading}. You see the exact change before anything is written, and Disconnect takes it back.`,
      literals: first ? [first.name] : [],
    };
  }
  const found = cards.filter((c) => c.detected).map((c) => c.name);
  const lead = found.length ? `${andList(found)} ${found.length === 1 ? 'is' : 'are'} already on this computer. ` : '';
  const them = found.length === 1 ? 'it has' : found.length ? 'they have' : 'your AI app has';
  return {
    text: `${lead}Add a connection first, so ${them} something to work with. Connect then shows the exact change before anything is written.`,
    literals: [],
  };
}

/** Setup: what goes on top as cards, and what stays a quiet row. */
export interface SetupLayout {
  /** The app whose Connect review is open (Overview's Connect, a row's Connect): detected, not connected yet. */
  readonly review: ClientCard | null;
  /** Apps with entries (connected in this session, or before): their full cards stay on top. */
  readonly kept: ClientCard[];
  /** Everyone else: quiet rows in Overview's order. */
  readonly rows: ClientCard[];
}

export function setupLayout(cards: readonly ClientCard[], review: string | null): SetupLayout {
  const open = cards.find((c) => c.client === review && c.links.length === 0 && c.status !== 'not-installed') ?? null;
  const kept = cards.filter((c) => c !== open && c.links.length > 0);
  return { review: open, kept, rows: cards.filter((c) => c !== open && !kept.includes(c)) };
}

/**
 * The review's "Tools" (kit: "sql_list_schemas, sql_describe_table and sql_query run at once (read only).
 * sql_execute waits for your approval."), from the endpoint's tools of the connection.
 */
export function usesTools(rows: readonly { readonly name: string; readonly mode: string }[]): { readonly text: string; readonly literals: readonly string[] } {
  const of = (mode: string) => rows.filter((r) => r.mode === mode).map((r) => r.name);
  const reads = of('READ_ONLY');
  const approval = of('APPROVAL');
  const bypass = of('BYPASS');
  const off = of('OFF');
  const parts = [
    reads.length ? `${andList(reads)} ${reads.length === 1 ? 'runs' : 'run'} at once (read only).` : null,
    approval.length ? `${andList(approval)} ${approval.length === 1 ? 'waits' : 'wait'} for your approval.` : null,
    bypass.length ? `${andList(bypass)} ${bypass.length === 1 ? 'runs' : 'run'} without approval (Bypass); policy limits still apply.` : null,
    off.length ? `${andList(off)} ${off.length === 1 ? 'is' : 'are'} off.` : null,
  ].filter((p): p is string => !!p);
  return { text: parts.join(' ') || 'No tools on yet.', literals: [...reads, ...approval, ...bypass, ...off] };
}

// ── Connect, Disconnect ─────────────────────────────────────────────────────────────────────────────────────────

export interface Said {
  readonly title: string;
  readonly body: string;
}

/**
 * The toast after Connect wrote the entry (kit: "Cursor connected to dev" · "One server added to ~/.cursor/mcp.json
 * with token cursor-laptop, which expires in 7 days. Backup: …").
 */
export function connectToast(card: ClientCard, request: ConnectRequest | null, preview: ConnectPreview | null, result: ConnectResult, opened: boolean): Said {
  const endpoint = result.link.endpoint;
  const file = preview?.files.find((f) => f.kind === 'config') ?? null;
  const backup = file?.backupPath ? ` Backup: ${file.backupPath}.` : '';
  const where = file?.display ?? card.configDisplay ?? 'its config';
  const title = `${card.name} connected to ${endpoint}`;
  switch (result.link.method) {
    case 'mcpb':
      return {
        title,
        body: opened
          ? `${card.name} opens its install dialog for the extension: click Install. It lists the tools in the next new chat.`
          : `Open ${result.mcpbPath ?? 'the extension'} in ${card.name} to install it; it lists the tools in the next new chat.`,
      };
    case 'token': {
      const name = result.link.tokenName ?? request?.token?.name ?? 'the token';
      const days = request?.token?.ttlDays ?? null;
      return { title, body: `One server added to ${where} with token ${name}${days ? `, which expires in ${days} days` : ''}.${backup}` };
    }
    case 'oauth':
      return { title, body: `One server added to ${where}, with no token in it. ${card.name} opens your browser to sign in; allow it here.` };
    case 'helper':
      return { title, body: `One server added to ${where}, with no token in it: the helper gets one from this app when ${card.name} needs it.${backup}` };
    default:
      return { title, body: `One server added to ${where}, with no token in it: the bridge gets one from this app when ${card.name} needs it.${backup}` };
  }
}

/** The toast after Disconnect (kit: "Claude Desktop disconnected from dev" · "incident-prod still works through the bridge."). */
export function disconnectToast(card: ClientCard, link: ClientLink, result: DisconnectResult): Said {
  const kept = keeps(card, link);
  if (link.method === 'mcpb') {
    const still = kept.length ? `${andList(kept)} still ${kept.length === 1 ? 'works' : 'work'} through the bridge.` : 'Its access is revoked.';
    return { title: `${card.name} disconnected from ${link.endpoint}`, body: [still, ...result.notes].join(' ') };
  }
  const what = link.grantLabel ? `access ${link.grantLabel} is revoked` : link.tokenName ? `token ${link.tokenName} is revoked` : 'its access is revoked';
  const title = kept.length ? `${card.name} disconnected from ${link.endpoint}` : `${card.name} disconnected`;
  const entry = result.entryRemoved ? 'The entry is removed and ' : 'The entry stays in the file, and ';
  return { title, body: `${entry}${what}.` };
}

/** The toast after Remove bridge (kit: "The extension is removed and its tokens for dev and incident-prod are revoked."). */
export function bridgeRemovedToast(card: ClientCard, result: BridgeRemoved): Said {
  const endpoints = card.links.map((l) => l.endpoint);
  const tokens = endpoints.length ? `its tokens for ${andList(endpoints)} are revoked` : 'its tokens are revoked';
  return { title: `Bridge removed from ${card.name}`, body: [`The extension is removed and ${tokens}.`, ...result.notes].join(' ') };
}

/** The toast after Add endpoint of Claude Desktop's bridge (kit: "stage-readonly added to Claude Desktop"). */
export function endpointAddedToast(card: ClientCard, endpoint: string, result: ConnectResult, opened: boolean): Said {
  return {
    title: `${endpoint} added to ${card.name}`,
    body: opened
      ? `${card.name} opens its install dialog for the extension: click Install. It lists the tools in the next new chat.`
      : `Open ${result.mcpbPath ?? 'the extension'} in ${card.name} to install it; it lists the tools in the next new chat.`,
  };
}

/** What a refused edit is, in the kit's "Can't connect Cursor: ~/.cursor/mcp.json is not valid JSON". */
const REFUSED: Record<string, string> = {
  invalid: 'is not valid',
  jsonc: 'has comments or trailing commas',
  'unexpected-shape': "isn't laid out the way it expects",
  'inline-table': 'keeps its servers in an inline table',
  'unsupported-file': "can't be edited",
  'check-failed': "couldn't be edited safely",
};

/**
 * "Connect failed" (the kit's reference state, shown in the client's card): from a refused preview or write. The
 * title says what failed, the body is the core's sentence (it says nothing was written; for a file that isn't valid,
 * the kit's "The file is unchanged. Fix line 7 in Cursor…", the parser's words going to Details), Details carries the
 * answer for a bug report, and an entry to add by hand comes along when the core gives one.
 */
export function connectFailure(error: unknown, card: ClientCard, at: number): ClientFailure {
  const file = card.configDisplay ?? fileName(card.configPath);
  const kind = /\.toml$/.test(file) ? 'TOML' : 'JSON';
  if (!isApiError(error)) {
    return {
      title: literalParts(`Can't connect ${card.name}`, []),
      body: literalParts("The core didn't answer. Nothing was written; try again.", []),
      fix: null,
      details: { title: `no answer · ${clockTime(at)}`, text: error instanceof Error ? `${error.name}: ${error.message}` : String(error) },
    };
  }
  const problem = configEditProblem(error);
  const base = clientFailure(error, card);
  if (problem) {
    const what = problem.reason === 'invalid' ? `is not valid ${kind}` : (REFUSED[problem.reason] ?? "can't be edited safely");
    const where = problem.line > 0 ? `\n  at     line ${problem.line}, column ${problem.column}` : '';
    // Not valid: the kit's sentence, where to fix it; the parser's own words are in Details.
    const body =
      problem.reason === 'invalid'
        ? literalParts(`The file is unchanged. Fix ${problem.line > 0 ? `line ${problem.line}` : 'it'} in ${card.name} or an editor, then connect again.`, [])
        : base.body;
    return {
      ...base,
      title: literalParts(`Can't connect ${card.name}: ${file} ${what}`, [file]),
      body,
      details: {
        title: `core answer · ${clockTime(at)}`,
        text: `client-config: ${problem.reason}, nothing written\n  file   ${file}${where}\n  error  ${problem.detail ?? error.detail}`,
      },
    };
  }
  // 409 "The file changed": the file is not what the preview showed; nothing was written.
  const changed = error.kind === 'conflict' && /changed/i.test(error.title);
  return {
    ...base,
    title: changed ? literalParts(`Can't connect ${card.name}: ${file} changed since you checked it`, [file]) : base.title,
    body: changed ? literalParts(`${error.detail} Nothing was written: connect again to see the new change.`, [file]) : base.body,
    details: { title: `core answer · ${clockTime(at)}`, text: `${error.status || 'no answer'} ${error.title}\n${error.detail}` },
  };
}

/**
 * The entry GateRook keeps in a client's file, from the core's disconnect preview of the link: the lines the
 * reverse edit would remove, exactly as they are (blank lines around it trimmed). Null for the bridge extension or
 * an entry that is gone or changed outside the app.
 */
export function entryFromPreview(preview: ConnectPreview): ConfigEntry | null {
  const file = preview.files.find((f) => f.kind === 'config' && f.diff);
  if (!file?.diff) return null;
  const lines = file.diff.hunks.flatMap((h) => h.lines.filter((l) => l.type === 'removed').map((l) => l.text));
  while (lines.length && !lines[0].trim()) lines.shift();
  while (lines.length && !lines.at(-1)!.trim()) lines.pop();
  if (!lines.length) return null;
  return { file: file.display, language: /\.toml$/.test(file.display) ? 'toml' : 'json', text: lines.join('\n') };
}

// ── Manual setup ────────────────────────────────────────────────────────────────────────────────────────────────

/** The endpoint the manual setup is about: the first running one that isn't prod (kit: dev), else the first. */
export function manualEndpoint(endpoints: readonly Pick<EndpointSummary, 'slug' | 'env' | 'status'>[]): string | null {
  return (endpoints.find((e) => e.status === 'running' && e.env !== 'prod') ?? endpoints[0])?.slug ?? null;
}

export interface SnippetTab {
  readonly id: 'inspector' | 'json' | 'toml';
  readonly label: string;
  readonly icon: string;
  readonly snippets: ManualSetup['snippets'];
  readonly caption: TextPart[];
}

/**
 * The snippet tabs of the manual setup from the core's snippets (`GET /api/clients/manual`): MCP Inspector (its
 * commands), JSON (an `mcpServers` file — Inspector's config holds the token, never its command line), TOML.
 */
export function snippetTabs(manual: ManualSetup): SnippetTab[] {
  const of = (langs: string[]) => manual.snippets.filter((s) => langs.includes(s.language));
  const entry = `gaterook-${manual.endpoint}`;
  const json = of(['json']);
  const tabs: SnippetTab[] = [
    {
      id: 'inspector',
      label: 'MCP Inspector',
      icon: 'square-terminal',
      snippets: of(['sh', 'shell', 'bash']),
      caption: json.length
        ? literalParts('Inspector reads the token from gw-inspector.json on the JSON tab: paste your token there, never into the command line.', ['gw-inspector.json'])
        : literalParts('In Inspector, open Authentication, choose Bearer token and paste your token.', []),
    },
    {
      id: 'json',
      label: 'JSON',
      icon: 'file-json',
      snippets: json,
      caption: literalParts(`For apps that read an mcpServers file. Add the ${entry} entry next to the servers you already have.`, ['mcpServers', entry]),
    },
    {
      id: 'toml',
      label: 'TOML',
      icon: 'file-code',
      snippets: of(['toml']),
      caption: literalParts('For apps configured through a config.toml. Codex itself connects in one click above, with no token in the file.', ['config.toml']),
    },
  ];
  return tabs.filter((t) => t.snippets.length);
}

/** The overline of a snippet: "Shell", "JSON", "TOML". */
export function snippetLang(language: string): string {
  return ({ sh: 'Shell', shell: 'Shell', bash: 'Shell', json: 'JSON', toml: 'TOML' } as Record<string, string>)[language] ?? language.toUpperCase();
}

/** "/mcp/stage-readonly, /mcp/incident-prod": the other endpoints' paths, for the URL's hint. */
export function otherPaths(manual: ManualSetup): string[] {
  return manual.others.map((o) => {
    try {
      return new URL(o.url).pathname;
    } catch {
      return `/mcp/${o.slug}`;
    }
  });
}

/**
 * Tokens set up by hand on the manual setup's endpoint (kit footer: "Set up by hand: MCP Inspector with token
 * inspector-debug gwt_K9xP…, expires in 3 days"): named tokens in use that no Connect wrote.
 */
export function handTokens(tokens: readonly TokenRow[], cards: readonly ClientCard[]): TokenRow[] {
  const connected = new Set(cards.flatMap((c) => c.links.map((l) => l.tokenName)).filter((n): n is string => !!n));
  return tokens.filter((t) => t.kind === 'named' && t.state === 'active' && !connected.has(t.name));
}

/** "expires in 3 days", "expires today", "never expires". */
export function expiresIn(at: Iso | null, now: number): string {
  if (!at) return 'never expires';
  const days = Math.round((Date.parse(at) - now) / 86_400_000);
  if (days <= 0) return 'expires today';
  return `expires in ${days} ${days === 1 ? 'day' : 'days'}`;
}

// ── claude.ai through the tunnel ────────────────────────────────────────────────────────────────────────────────

/**
 * The endpoint the claude.ai card is about: the tunnel's (on, or chosen in Settings › Tunnel), else the one marked
 * published, else none ("choose it in Tunnel settings").
 */
export function publishedEndpoint(tunnel: TunnelState | null, endpoints: readonly Pick<EndpointSummary, 'slug' | 'published'>[]): string | null {
  return tunnel?.endpoint ?? endpoints.find((e) => e.published)?.slug ?? null;
}

/** The provider by name (Publish dialog: "Cloudflare Tunnel"). */
export function providerName(provider: TunnelState['provider']): string | null {
  switch (provider) {
    case 'cloudflare-quick':
      return 'Cloudflare Tunnel (quick)';
    case 'cloudflare-named':
      return 'Cloudflare Tunnel';
    case 'ngrok':
      return 'ngrok';
    default:
      return null;
  }
}

/**
 * The card's Tunnel row (kit: "Off since 12:10 · public today 08:40 to 12:10"; on: "Public since just now").
 * `relative` words a moment as the page's clock does.
 */
export function tunnelRow(t: TunnelState, now: number, relative: (at: Iso) => string): string {
  if (t.state === 'on') return t.since ? `Public since ${relative(t.since)}` : 'Public';
  if (t.state === 'starting') return 'Starting';
  const done = [...t.history].filter((h) => h.to).sort((a, b) => Date.parse(b.to!) - Date.parse(a.to!))[0] ?? null;
  const day = (at: Iso) => (shortDate(at, now) === 'Today' ? '' : `${shortDate(at, now)} `);
  const since = t.since ? `Off since ${day(t.since)}${clockTime(t.since, false)}` : 'Off';
  if (!done) return `${since} · never published`;
  const when = shortDate(done.from, now) === 'Today' ? 'today' : shortDate(done.from, now);
  return `${since} · public ${when} ${clockTime(done.from, false)} to ${clockTime(done.to!, false)}`;
}

/**
 * The card's Last call row: the newest call of a claude.ai sign-in and, while the tunnel is off, since when (kit:
 * "12:08 (tunnel off since 12:10) · 19 calls today").
 */
export function lastCallRow(t: TunnelState, grants: readonly OAuthGrant[], now: number): { readonly at: Iso | null; readonly note: string; readonly calls: string | null } {
  let at: Iso | null = null;
  for (const g of grants) if (g.ingress === 'TUNNEL' && g.lastUsedAt && (!at || Date.parse(g.lastUsedAt) > Date.parse(at))) at = g.lastUsedAt;
  const note = t.state !== 'on' && t.since ? ` (tunnel off since ${clockTime(t.since, false)})` : '';
  const today = t.history.filter((h) => shortDate(h.from, now) === 'Today').reduce((n, h) => n + h.calls, 0);
  const calls = t.history.length ? `${formatCount(today)} ${today === 1 ? 'call' : 'calls'} today` : null;
  return { at, note, calls };
}

/** The env of the published endpoint's connections, once each, for the markers after their names. */
export function connectionEnvs(preview: PublishPreview | null): Env[] {
  return [...new Set((preview?.connections ?? []).map((c) => c.env as Env))];
}

/** A sign-in's Allowed cell: "Today 08:52", "Oct 21 08:52". */
export function allowedAt(at: Iso, now: number): string {
  return `${shortDate(at, now)} ${clockTime(at, false)}`;
}

/** ApiError or not, a sentence for a toast. */
export function errorSentence(e: unknown): string {
  return isApiError(e) ? e.detail : "The core didn't answer. Try again.";
}
