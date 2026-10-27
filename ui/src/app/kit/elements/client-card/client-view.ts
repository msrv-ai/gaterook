import { ApiError } from '../../../core/api/api-error';
import {
  ClientCard,
  ClientDiff,
  ClientId,
  ClientLink,
  ClientMethod,
  ClientMethodId,
  ConnectPreview,
  configEditProblem,
} from '../../../core/api/clients.api';
import { EndpointSummary } from '../../../core/api/endpoints.api';
import { Env, Iso } from '../../../core/api/types';
import { formatCount } from '../../../core/time/time-format';
import { TextPart, literalParts } from '../shared/literals';

/**
 * What the client-card element says, from the clients module's cards (ARCHITECTURE.md §6 clients, step K1). Pure
 * functions, tested against the kit's wording (design/elements/client-card.html).
 */

/** What a card needs to know about an endpoint: its name, class, access and tools (`GET /api/endpoints`). */
export type ClientEndpoint = Pick<EndpointSummary, 'slug' | 'env' | 'access' | 'tools' | 'connections' | 'url'>;

const sum = (t: ClientEndpoint['tools']): number => t.read + t.approval + t.off;

/** "dev · 8 connections, 22 tools", "stage-readonly · 3 connections, read only", "incident-prod · 3 prod connections". */
export function endpointOption(e: ClientEndpoint): string {
  const n = e.connections.total;
  const conns = `${formatCount(n)} ${e.env === 'prod' ? 'prod ' : ''}${n === 1 ? 'connection' : 'connections'}`;
  if (e.env === 'prod') return `${e.slug} · ${conns}`;
  if (e.access === 'read-only') return `${e.slug} · ${conns}, read only`;
  return `${e.slug} · ${conns}, ${formatCount(sum(e.tools))} tools`;
}

/** The Tools cell: the count, then "14 read only, 3 approval, 5 off" (zero modes left out). */
export function toolsCell(e: ClientEndpoint): { readonly total: string; readonly modes: string } {
  const parts = [
    e.tools.read ? `${e.tools.read} read only` : null,
    e.tools.approval ? `${e.tools.approval} approval` : null,
    e.tools.off ? `${e.tools.off} off` : null,
  ].filter((p): p is string => p !== null);
  return { total: formatCount(sum(e.tools)), modes: parts.join(', ') };
}

export function toolCount(e: ClientEndpoint): string {
  const n = sum(e.tools);
  return `${formatCount(n)} ${n === 1 ? 'tool' : 'tools'}`;
}

/** Claude Desktop through the `.mcpb` extension: no config file to open, one row per endpoint. */
export function usesBridgeExtension(card: ClientCard): boolean {
  if (card.links.length) return card.links.some((l) => l.method === 'mcpb');
  return card.methods[0]?.id === 'mcpb';
}

/** The card's subtitle (`__via`): "Bridge extension (.mcpb) · 2 endpoints, one of them prod", or the config path. */
export function viaText(card: ClientCard): { readonly text: string; readonly mono: boolean } {
  if (usesBridgeExtension(card)) {
    const n = card.links.length;
    const prod = card.links.filter((l) => l.env === 'prod').length;
    let text = 'Bridge extension (.mcpb)';
    if (n) text += ` · ${n} ${n === 1 ? 'endpoint' : 'endpoints'}`;
    if (n > 1 && prod === 1) text += ', one of them prod';
    else if (n > 1 && prod > 1) text += `, ${prod} of them prod`;
    else if (n === 1 && prod === 1) text += ', prod';
    return { text, mono: false };
  }
  return { text: card.configDisplay ?? card.configPath ?? '', mono: true };
}

/** One row per endpoint (a table) for the bridge extension and for any client on more than one endpoint. */
export function tableLayout(card: ClientCard): boolean {
  return card.links.length > 1 || (card.links.length === 1 && usesBridgeExtension(card));
}

/**
 * The Auth row: how the client authenticates, literals in code. `page`: in the words of the Clients screen
 * (pages/clients.html), which says it shorter than the element's demo for the bridge, the helper and a client that
 * isn't installed.
 */
export function authParts(card: ClientCard, link: ClientLink | null, restart = false, page = false): TextPart[] {
  const method = link?.method ?? null;
  const label = link?.grantLabel ?? null;
  const t = (text: string, literals: (string | null)[] = []): TextPart[] => literalParts(text, literals);
  if (page && !restart) {
    switch (method) {
      case 'mcpb':
        return t('A short-lived token per endpoint, issued on the fly and never written to the config');
      case 'helper':
        return t(
          `Short-lived token from gw-bridge headers (http_headers_helper), issued on the fly, not written to the config${label ? ` · access ${label}` : ''}`,
          ['gw-bridge headers', 'http_headers_helper', label],
        );
      case 'config-bridge':
        return t(`Short-lived token from the bridge, issued on the fly, not written to the config${label ? ` · access ${label}` : ''}`, [label]);
      case null:
        if (card.status === 'not-installed' && card.methods.some((m) => m.id === 'token')) return t('Token in headers');
    }
  }
  switch (method) {
    case 'mcpb':
      return t('A 15-minute token per endpoint, issued on the fly by the bridge and never written to a config');
    case 'helper':
      return restart
        ? t(`Access ${label ?? 'of this client'}, tokens issued on the fly through http_headers_helper`, [label, 'http_headers_helper'])
        : t(`Access ${label ?? 'of this client'}: a 15-minute token handed to http_headers_helper on the fly, never written to the config`, [label, 'http_headers_helper']);
    case 'config-bridge':
      return t(`Access ${label ?? 'of this client'}: a 15-minute token from the bridge on the fly, never written to the config`, [label]);
    case 'token':
      return link?.tokenName
        ? t(`Token ${link.tokenName}, written to the config in plain text`, [link.tokenName])
        : t('A token written to the config in plain text');
    case 'oauth':
      return t('Signed in through the browser and allowed in this app; no token in the file');
    default:
      return t(detectedAuth(card));
  }
}

/** Auth before Connect: what the client's methods mean. */
function detectedAuth(card: ClientCard): string {
  const ids = card.methods.map((m) => m.id);
  if (ids[0] === 'mcpb') return 'A 15-minute token per endpoint, issued on the fly by the bridge and never written to a config';
  if (ids[0] === 'helper') return 'A 15-minute token handed to http_headers_helper on the fly, never written to the config';
  if (ids.includes('token') && ids.includes('oauth')) {
    return card.status === 'not-installed'
      ? 'Token in headers, written to its config in plain text'
      : 'You choose on Connect: a token in the file (plain text), or sign-in through the browser';
  }
  if (ids.includes('token')) return 'Token in headers, written to its config in plain text';
  return 'Set on Connect';
}

/** A client that isn't installed: what to do, and on the Clients screen what one config file serves. */
export function notInstalledText(card: ClientCard, page = false): string {
  if (!page) return 'Not found on this machine. Install it, then check again.';
  const serves: Partial<Record<ClientId, string>> = { antigravity: ' One config file serves the Antigravity app, its IDE and its CLI.' };
  return `Not found on this computer. Install it, then check again.${serves[card.client] ?? ''}`;
}

/**
 * The entry GateRook keeps in a client's config file, exactly as it is there (the Clients screen's "Entry in
 * config.toml"): the page reads it from the core's disconnect preview, which is the part of the file that is ours.
 */
export interface ConfigEntry {
  readonly file: string;
  readonly language: 'toml' | 'json';
  readonly text: string;
}

/** Codex's `default_tools_approval_mode` in its entry (`writes`, `prompt`), or null. */
export function approvalMode(entry: ConfigEntry | null): string | null {
  return entry ? (/^\s*default_tools_approval_mode\s*=\s*"([^"]+)"/m.exec(entry.text)?.[1] ?? null) : null;
}

/** The latest call through any of the card's entries. */
export function lastCallAt(links: readonly ClientLink[]): Iso | null {
  let best: Iso | null = null;
  for (const l of links) if (l.lastCallAt && (!best || Date.parse(l.lastCallAt) > Date.parse(best))) best = l.lastCallAt;
  return best;
}

/** Where to get a client that isn't installed (opens in the browser). */
export const INSTALL_URL: Record<ClientId, string> = {
  'claude-desktop': 'https://claude.ai/download',
  codex: 'https://developers.openai.com/codex',
  cursor: 'https://cursor.com/downloads',
  antigravity: 'https://antigravity.google/',
};

/** Activity filtered to a client (and an endpoint): `client:codex endpoint:dev`. */
export function activityQuery(client: string, endpoint?: string | null): { readonly q: string } {
  return { q: endpoint ? `client:${client} endpoint:${endpoint}` : `client:${client}` };
}

/** The alert of a card that waits for the client to load its new entry. */
export function restartAlert(card: ClientCard): { readonly title: string; readonly body: string } {
  const link = card.links[0] ?? null;
  switch (card.client) {
    case 'codex':
      return { title: 'Restart Codex to load the server', body: 'The config is written. Codex reads it only when it starts.' };
    case 'claude-desktop':
      return link?.method === 'mcpb'
        ? { title: 'Install the extension in Claude Desktop', body: 'Claude Desktop opened its install dialog: click Install. Nothing was written to its config.' }
        : { title: 'Restart Claude Desktop to load the server', body: 'The config is written. Quit Claude Desktop from the tray icon, then open it again.' };
    case 'cursor':
      return { title: 'Turn the server on in Cursor', body: 'The config is written. Cursor lists it in Settings › MCP; turn it on there.' };
    case 'antigravity':
      return { title: 'Refresh Antigravity to load the server', body: 'The config is written. Refresh it in Antigravity: Installed MCP Servers.' };
  }
}

/** Clients that read their config only when they start: Connect says so before it writes. */
export function restartNote(client: ClientId, method: ClientMethodId, configName: string): { readonly title: string; readonly body: TextPart[] } | null {
  if (client === 'codex') return { title: 'Restart Codex after connecting', body: literalParts(`It reads ${configName} only when it starts.`, [configName]) };
  if (client === 'claude-desktop' && method === 'config-bridge') {
    return { title: 'Restart Claude Desktop after connecting', body: literalParts('It reads claude_desktop_config.json only when it starts.', ['claude_desktop_config.json']) };
  }
  return null;
}

/** The note on how the chosen method authenticates (`gw-alert--compact`); a token in the file is a warning. */
export function authNote(
  card: ClientCard,
  method: ClientMethodId,
  endpoint: string,
  tokenName: string | null,
  grantLabel: string | null,
): { readonly tone: 'info' | 'warning'; readonly icon: string; readonly title: string; readonly body: TextPart[] } {
  const file = card.configDisplay ?? 'the config';
  switch (method) {
    case 'token':
      return {
        tone: 'warning',
        icon: 'key-round',
        title: 'The token is written in plain text',
        body: literalParts(
          `Token ${tokenName ?? ''} goes into ${file} as it is: anyone who can read the file can call endpoint ${endpoint} from this computer until it expires.${card.methods.some((m) => m.id === 'oauth') ? ' Pick sign-in through the browser to keep it out of the file.' : ''}`,
          [tokenName, file, endpoint],
        ),
      };
    case 'helper':
      return {
        tone: 'info',
        icon: 'key-round',
        title: 'Token issued on the fly, not written to the config',
        body: literalParts(
          `gw-bridge headers hands ${card.name} a 15-minute token for access ${grantLabel ?? ''} whenever it needs one. There is no token value to copy or paste.`,
          ['gw-bridge headers', grantLabel],
        ),
      };
    case 'config-bridge':
    case 'mcpb':
      return {
        tone: 'info',
        icon: 'key-round',
        title: 'Token issued on the fly, not written to the config',
        body: literalParts(`The bridge gets a 15-minute token from this app whenever ${card.name} needs one. There is no token value to copy or paste.`, []),
      };
    case 'oauth':
      return {
        tone: 'info',
        icon: 'log-in',
        title: 'Sign-in through the browser, no token in the file',
        body: literalParts(`${card.name} opens your browser and you allow it in this app, with a code to match.`, []),
      };
  }
}

/** The kit's descriptions of the sign-in choices. */
export function methodChoice(card: ClientCard, m: ClientMethod): { readonly icon: string; readonly description: string } {
  switch (m.id) {
    case 'token':
      return {
        icon: 'key-round',
        description: `${card.name} has no token helper, so a token only for ${card.name} is written into its config in plain text.`,
      };
    case 'oauth':
      return { icon: 'log-in', description: `${card.name} opens your browser and you allow it in this app. No token in the file.` };
    case 'config-bridge':
      return { icon: 'cable', description: `${card.name} starts the bridge, which gets a 15-minute token from this app. No token in the file.` };
    case 'mcpb':
      return { icon: 'package', description: 'Claude Desktop installs the bridge as an extension. Nothing is written to its config.' };
    case 'helper':
      return { icon: 'terminal', description: `${card.name} asks the bridge for a 15-minute token. No token in the file.` };
  }
}

/**
 * The Clients screen's line under a sign-in choice: where it works (a token in a file is never offered for prod,
 * ARCHITECTURE.md §8.6.2), or what choosing it changes.
 */
export function choiceMeta(m: ClientMethod): string {
  switch (m.id) {
    case 'oauth':
      return 'Turns on OAuth sign-in for the endpoint';
    case 'mcpb':
      return 'Nothing is written to its config';
    default:
      return m.prodAllowed ? 'Works on every endpoint' : 'Not on prod endpoints';
  }
}

/** The methods offered for an endpoint (prod refuses tokens in files and browser sign-in), and why others aren't. */
export function methodsFor(card: ClientCard, env: Env | null): { readonly offered: ClientMethod[]; readonly refused: ClientMethod[] } {
  const offered = card.methods.filter((m) => env !== 'prod' || m.prodAllowed);
  return { offered, refused: card.methods.filter((m) => !offered.includes(m)) };
}

/** "7 additions, 1 deletion", "7 additions". */
export function changeSummary(d: Pick<ClientDiff, 'added' | 'removed'>): string {
  const parts: string[] = [];
  if (d.added) parts.push(`${d.added} ${d.added === 1 ? 'addition' : 'additions'}`);
  if (d.removed) parts.push(`${d.removed} ${d.removed === 1 ? 'deletion' : 'deletions'}`);
  return parts.join(', ') || 'No changes';
}

/** The file's name for a sentence ("config.toml", "mcp.json"). */
export function fileName(display: string | null): string {
  if (!display) return 'its config';
  return display.split(/[\\/]/).at(-1) ?? display;
}

/** What Disconnect removes, revokes and keeps, from the link and its preview. */
export function disconnectFacts(
  card: ClientCard,
  link: ClientLink,
  preview: ConnectPreview | null,
): { readonly removes: TextPart[] | null; readonly revokes: TextPart[] | null; readonly backup: string | null } {
  if (link.method === 'mcpb') {
    // One `.mcpb` per endpoint (kit after KIT: "The bridge extension of dev, mcp-gateway-dev.mcpb"): its file goes,
    // the grant behind it is revoked.
    const file = link.mcpbPath ? fileName(link.mcpbPath) : null;
    return {
      removes: file
        ? literalParts(`The bridge extension of ${link.endpoint}, ${file}`, [link.endpoint, file])
        : literalParts(`The bridge extension of ${link.endpoint}`, [link.endpoint]),
      revokes: literalParts(`The bridge's short-lived token for ${link.endpoint}; no new ones are issued for it`, [link.endpoint]),
      backup: null,
    };
  }
  const file = link.configDisplay ?? card.configDisplay;
  const entry = link.entryName ? (fileName(file).endsWith('.toml') ? `[mcp_servers.${link.entryName}]` : `"${link.entryName}"`) : null;
  const removes = entry && file ? literalParts(`${entry} from ${file}`, [entry, file]) : null;
  let revokes: TextPart[] | null = null;
  if (link.grantLabel) {
    const whose = link.method === 'helper' ? "The helper's grant" : link.method === 'config-bridge' ? "The bridge's grant" : 'Access';
    revokes = literalParts(`${whose} ${link.grantLabel}; no new tokens are issued to it`, [link.grantLabel]);
  } else if (link.tokenName) revokes = literalParts(`Token ${link.tokenName}; it stops working on its next call`, [link.tokenName]);
  const backup = preview?.files.find((f) => f.backupPath)?.backupPath ?? null;
  return { removes, revokes, backup };
}

/**
 * What a Disconnect confirm says (pages/clients.html, pages/endpoint-detail.html): the endpoint the client loses at
 * once and, for a client on several endpoints, the ones it keeps; the button names the object.
 */
export function disconnectWords(card: ClientCard, link: ClientLink, table: boolean): { readonly title: string; readonly body: TextPart[]; readonly button: string } {
  const prod = link.env === 'prod';
  const kept = keeps(card, link);
  const loses = prod
    ? `${card.name} loses access to endpoint ${link.endpoint} and its prod connections at once.`
    : `${card.name} loses access to endpoint ${link.endpoint} at once.`;
  if (table) {
    const rest = kept.length ? (link.method === 'mcpb' ? ` The bridge stays installed for ${andList(kept)}.` : ` It keeps ${andList(kept)}.`) : '';
    return { title: `Disconnect ${card.name} from ${link.endpoint}?`, body: literalParts(loses + rest, [link.endpoint, ...kept]), button: `Disconnect ${card.name} from ${link.endpoint}` };
  }
  const again = link.method === 'token' ? ' Its token stops working; connect again any time.' : ' Connect again any time; that creates a new access.';
  return { title: `Disconnect ${card.name}?`, body: literalParts(loses + again, [link.endpoint]), button: `Disconnect ${card.name}` };
}

/** The `.mcpb` files a Remove bridge takes away (one per endpoint), by file name. */
export function bridgeFiles(card: ClientCard): string[] {
  return card.links.filter((l) => l.method === 'mcpb' && l.mcpbPath).map((l) => fileName(l.mcpbPath));
}

/** The other endpoints a bridge keeps when one is disconnected. */
export function keeps(card: ClientCard, link: ClientLink): string[] {
  return card.links.filter((l) => l.id !== link.id).map((l) => l.endpoint);
}

/** "dev and incident-prod", "dev, stage-readonly and incident-prod". */
export function andList(items: readonly string[]): string {
  return items.length <= 1 ? items.join('') : `${items.slice(0, -1).join(', ')} and ${items.at(-1)}`;
}

/** What failed on Connect, as the card's danger alert says it; `fix` is a command or the entry to add by hand. */
export interface ClientFailure {
  readonly title: TextPart[];
  readonly body: TextPart[];
  readonly fix: { readonly title: string; readonly language: string; readonly text: string; readonly label: string } | null;
  /** The technical details behind Details (the Clients screen's "Connect failed"): a title and the text to copy. */
  readonly details?: { readonly title: string; readonly text: string } | null;
}

/**
 * From a failed preview or write: 422 with K1's edit problem (the file can't be edited safely: the core's sentence,
 * and the entry to add by hand when it gives one), 409 "Connect failed" ("Can't write <file>: <why>"), or anything
 * else as the core said it.
 */
export function clientFailure(error: ApiError, card: ClientCard): ClientFailure {
  const file = card.configDisplay ?? '';
  const problem = configEditProblem(error);
  if (problem) {
    // The core's sentence already says where and that nothing changed; without one, say it here.
    const body = problem.detail ?? 'Nothing was written. Add the entry by hand, then check again.';
    return {
      title: literalParts(`Can't edit ${file} safely`, [file]),
      body: literalParts(body, []),
      fix: problem.fragment
        ? { title: 'Add by hand', label: 'entry', language: fileName(file).endsWith('.toml') ? 'toml' : 'json', text: problem.fragment }
        : null,
    };
  }
  const m = /^Can't write (.+?): (.*)$/s.exec(error.detail);
  if (m) {
    const denied = /AccessDenied|Permission denied|not writable|Operation not permitted/i.test(m[2]);
    return {
      title: literalParts(`Can't write ${m[1]}`, [m[1]]),
      body: literalParts(
        denied
          ? 'Permission denied: the file is not yours to write. Give it back to your user, then try again. Nothing was written.'
          : `${m[2].trim().replace(/\.$/, '')}. Nothing was written.`,
        [],
      ),
      fix: denied ? { title: 'Fix the owner', label: 'command', language: 'shell', text: `sudo chown "$USER" ${m[1]}` } : null,
    };
  }
  return { title: literalParts(error.title, []), body: literalParts(error.detail, []), fix: null };
}

/** The overline of a fix's code block: "Shell", "JSON", "TOML" (plain text, as the kit shows commands to copy). */
export function langLabel(language: string): string {
  return ({ shell: 'Shell', json: 'JSON', toml: 'TOML' } as Record<string, string>)[language] ?? language.toUpperCase();
}

/** A token's expiry date for the hint ("On Oct 29, 2026"): `days` from now. */
export function expiresOn(now: number, days: number): Iso {
  return new Date(now + days * 86_400_000).toISOString();
}

/** A token name the core accepts (kebab case, ≤ 40). */
export const TOKEN_NAME = /^[a-z0-9]+(-[a-z0-9]+)*$/;

/** The default name of a client's token ("cursor-laptop"). */
export function defaultTokenName(client: ClientId): string {
  return `${client === 'claude-desktop' ? 'claude' : client}-laptop`;
}
