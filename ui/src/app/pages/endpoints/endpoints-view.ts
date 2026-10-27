import { ConnectionRow } from '../../core/api/connections.api';
import { EndpointStart, EndpointSummary, TokenRow } from '../../core/api/endpoints.api';
import { OAuthGrant, TunnelState } from '../../core/api/tunnel.api';
import { Env } from '../../core/api/types';
import { duration } from '../../core/time/time-format';
import { clientName } from '../../kit/elements/shared/client-mark';
import { MODES } from '../../kit/elements/shared/dictionaries';
import { tokenView } from '../../kit/elements/token-issue/token-issue';
import { ToastTone } from '../../kit/toast.service';

/*
 * What the Endpoints page (design/pages/endpoints.html) says about the core's endpoints, as pure functions: the
 * header meta, each card's facts (connections by environment, connection health, tools by mode, clients, tokens,
 * approvals), the Disable / Undo toast, the Delete confirmation, the slug rules and the Start from meta of Create.
 * The page and its data service only wire them to the API and the DOM.
 */

export const ENVS: readonly Env[] = ['dev', 'stage', 'prod'];

/**
 * The kit's slug rule (`pattern="[a-z0-9]+(-[a-z0-9]+)*"`, `maxlength="40"`): lowercase letters and digits in words
 * joined by single hyphens. The core accepts up to 63 characters; the kit's form keeps slugs short enough to type.
 */
export const SLUG_PATTERN = /^[a-z0-9]+(-[a-z0-9]+)*$/;
export const SLUG_MAX = 40;
export const DESCRIPTION_MAX = 120;

/** What the Create dialog's "Start from" sends (`POST /api/endpoints {startFrom}`), in the kit's order. */
export type StartFrom = 'empty' | 'read-only-dev' | 'incident-response';

export function plural(n: number, one: string, many = `${one}s`): string {
  return `${n.toLocaleString('en-US')} ${n === 1 ? one : many}`;
}

/** "Claude Desktop", "Claude Desktop and Codex", "A, B and C". */
export function andList(items: readonly string[]): string {
  if (items.length <= 1) return items[0] ?? '';
  return `${items.slice(0, -1).join(', ')} and ${items.at(-1)}`;
}

/** What is wrong with a slug the person typed (shown after a submit attempt), or null. */
export function slugError(value: string): string | null {
  const v = value.trim();
  if (!v) return 'Enter a slug. It becomes the end of the endpoint URL.';
  if (v.length > SLUG_MAX) return `Use at most ${SLUG_MAX} characters.`;
  if (!SLUG_PATTERN.test(v)) return 'Use lowercase letters, digits and single hyphens between them, like payments-dev.';
  return null;
}

/** The suggested slug of a copy: `<slug>-copy`, or `-copy-2`, `-copy-3`… when that one is taken. */
export function copySlug(slug: string, taken: readonly string[]): string {
  const used = new Set(taken);
  // Cut to fit, never ending on a hyphen (the pattern wouldn't accept it).
  const fit = (s: string, max: number) => s.slice(0, max).replace(/-+$/, '');
  const base = fit(`${slug}-copy`, SLUG_MAX);
  if (!used.has(base)) return base;
  for (let i = 2; ; i++) {
    const suffix = `-${i}`;
    const next = `${fit(base, SLUG_MAX - suffix.length)}${suffix}`;
    if (!used.has(next)) return next;
  }
}

// ── Header ────────────────────────────────────────────────────────────────────────────────────────────────────────

export interface StatusPart {
  readonly tone: 'success' | 'warning' | 'danger' | 'neutral' | 'info';
  readonly text: string;
}

/** "All running", or "2 running · 1 disabled", plus the endpoints that serve nothing until reviewed. */
export function headerStatuses(rows: readonly EndpointSummary[]): StatusPart[] {
  const blocked = rows.filter((r) => r.blockedReason).length;
  const disabled = rows.filter((r) => !r.blockedReason && r.status === 'disabled').length;
  const running = rows.length - blocked - disabled;
  const parts: StatusPart[] = [];
  if (running === rows.length) parts.push({ tone: 'success', text: 'All running' });
  else {
    if (running) parts.push({ tone: 'success', text: `${running} running` });
    if (disabled) parts.push({ tone: 'neutral', text: `${disabled} disabled` });
  }
  if (blocked) parts.push({ tone: 'warning', text: `${blocked} changed outside GateRook` });
  return parts;
}

/** "1 published for claude.ai · tunnel off since 12:10": null when nothing is published. */
export interface PublishedMeta {
  readonly text: string;
  readonly tunnel: { readonly text: string; readonly at: string | null } | null;
}

export function publishedMeta(rows: readonly EndpointSummary[], tunnel: TunnelState | null): PublishedMeta | null {
  const n = rows.filter((r) => r.published).length;
  if (!n) return null;
  if (!tunnel) return { text: `${n} published for claude.ai`, tunnel: null };
  const on = tunnel.state === 'on';
  return { text: `${n} published for claude.ai`, tunnel: { text: on ? 'tunnel on' : `tunnel ${tunnel.state}`, at: tunnel.since } };
}

// ── Card facts ────────────────────────────────────────────────────────────────────────────────────────────────────

/** "8 · 6 in dev 2 in stage": the total and the environments that have connections, in the kit's order. */
export function connectionsByEnv(e: EndpointSummary): { readonly total: number; readonly envs: { readonly env: Env; readonly count: number }[] } {
  const by = e.connections.byEnv ?? {};
  return { total: e.connections.total, envs: ENVS.map((env) => ({ env, count: by[env] ?? 0 })).filter((x) => x.count > 0) };
}

/** Beyond this many problem connections of one kind, the card counts them instead of naming each. */
export const NAMED_PROBLEMS = 3;

/**
 * "6 healthy · cache-redis slow · 1.8 s · billing-api unreachable" (kit). With the connections list, the problems are
 * named (and their latency given); without it — or when the list doesn't know the endpoint's connections yet — the
 * core's counts are used. Connections the core hasn't checked yet are counted as such.
 */
export function healthParts(e: EndpointSummary, connections: readonly ConnectionRow[] | null): StatusPart[] {
  const mine = connections?.filter((c) => c.usedBy.includes(e.slug)) ?? [];
  if (connections && mine.length === e.connections.total && mine.length > 0) {
    const state = (c: ConnectionRow) => (c.health?.state ?? 'unknown').toLowerCase();
    const parts: StatusPart[] = [];
    const healthy = mine.filter((c) => state(c) === 'healthy').length;
    if (healthy) parts.push({ tone: 'success', text: `${healthy} healthy` });
    const slow = mine.filter((c) => state(c) === 'degraded');
    if (slow.length > NAMED_PROBLEMS) parts.push({ tone: 'warning', text: `${slow.length} slow` });
    else
      slow.forEach((c) => {
        const ms = c.health?.latencyMs;
        parts.push({ tone: 'warning', text: `${c.name} slow${ms != null ? ` · ${duration(ms)}` : ''}` });
      });
    const down = mine.filter((c) => state(c) === 'unreachable');
    if (down.length > NAMED_PROBLEMS) parts.push({ tone: 'danger', text: `${down.length} unreachable` });
    else down.forEach((c) => parts.push({ tone: 'danger', text: `${c.name} unreachable` }));
    const unknown = mine.length - healthy - slow.length - down.length;
    if (unknown) parts.push({ tone: 'neutral', text: `${unknown} not checked yet` });
    return parts;
  }
  const h = e.health ?? {};
  const parts: StatusPart[] = [];
  if (h['healthy']) parts.push({ tone: 'success', text: `${h['healthy']} healthy` });
  if (h['degraded']) parts.push({ tone: 'warning', text: `${h['degraded']} slow` });
  if (h['unreachable']) parts.push({ tone: 'danger', text: `${h['unreachable']} unreachable` });
  if (h['unknown']) parts.push({ tone: 'neutral', text: `${h['unknown']} not checked yet` });
  return parts;
}

export interface ModePart {
  readonly cls: string;
  readonly icon: string;
  readonly text: string;
}

/** "22 · 14 read only 3 approval 5 off": modes with no tool are left out, as the kit does. */
export function toolParts(e: EndpointSummary): { readonly total: number; readonly modes: ModePart[] } {
  const t = e.tools;
  const modes: ModePart[] = [];
  const add = (n: number, mode: keyof typeof MODES, word: string) => {
    if (n > 0) modes.push({ cls: MODES[mode].cls, icon: MODES[mode].icon, text: `${n} ${word}` });
  };
  add(t.read, 'READ_ONLY', 'read only');
  add(t.approval, 'APPROVAL', 'approval');
  add(t.bypass ?? 0, 'BYPASS', 'bypass');
  add(t.off, 'OFF', 'off');
  return { total: t.read + t.approval + t.off + (t.bypass ?? 0), modes };
}

/** One client of an endpoint: its kit mark and name. */
export interface ClientPart {
  readonly id: string;
  readonly name: string;
}

/** The clients with live access through a grant, then claude.ai when the endpoint is published through the tunnel. */
export function clientParts(e: EndpointSummary): ClientPart[] {
  const parts = e.clients.map((id) => ({ id, name: clientName(id) }));
  if (e.published && !e.clients.includes('claude-ai')) parts.push({ id: 'claude-ai', name: 'claude.ai' });
  return parts;
}

/** The status next to claude.ai on a published endpoint: "Tunnel on", "Tunnel off since 12:10" (or just "Tunnel off"). */
export function tunnelStatus(tunnel: TunnelState | null): { readonly tone: 'warning' | 'neutral' | 'danger' | 'info'; readonly text: string; readonly at: string | null } {
  if (!tunnel) return { tone: 'neutral', text: 'Tunnel off', at: null };
  switch (tunnel.state) {
    case 'on':
      return { tone: 'warning', text: 'Tunnel on', at: tunnel.since };
    case 'starting':
      return { tone: 'info', text: 'Tunnel starting', at: null };
    case 'error':
      return { tone: 'danger', text: 'Tunnel failed', at: tunnel.since };
    default:
      return { tone: 'neutral', text: 'Tunnel off', at: tunnel.since };
  }
}

/**
 * The Tokens row. Tokens a person issued and pasted somewhere count, and so do the grants of helpers (`codex-laptop`):
 * the token table lists both. The Claude Desktop bridge is how that client gets its tokens, not a token of its own
 * (the kit doesn't count it either), so an endpoint that only has the bridge says it gets short-lived ones.
 */
export interface TokensFact {
  /** "2 active · 1 expired", or null when there are none of either. */
  readonly counts: string | null;
  /** "1 expires in 3 days": active tokens that stop working within 7 days (the token table's warning). */
  readonly expiring: string | null;
  /** "1 OAuth sign-in you allowed", when the core reports any for this endpoint. */
  readonly oauth: string | null;
  /** "None · the Claude Desktop bridge gets short-lived ones", when there is nothing to count. */
  readonly none: string | null;
}

export function tokensFact(
  e: EndpointSummary,
  tokens: readonly TokenRow[] | null,
  oauthSignIns: number,
  now: number,
  relative: (iso: string) => string,
): TokensFact {
  const oauth = oauthSignIns > 0 ? `${plural(oauthSignIns, 'OAuth sign-in')} you allowed` : null;
  if (!tokens) {
    // The token list didn't answer: the core's own counts of named tokens (expiring within 3 days there).
    const counts = e.tokens.active ? `${e.tokens.active} active` : null;
    const expiring = e.tokens.expiringSoon ? `${e.tokens.expiringSoon} ${e.tokens.expiringSoon === 1 ? 'expires' : 'expire'} within 3 days` : null;
    return { counts, expiring, oauth, none: counts || oauth ? null : 'None' };
  }
  const views = tokens.map((t) => tokenView(t, e.slug, now));
  const counted = views.filter((v) => !v.bridge);
  const active = counted.filter((v) => v.row.state === 'active').length;
  const expired = counted.filter((v) => v.row.state === 'expired').length;
  const parts: string[] = [];
  if (active) parts.push(`${active} active`);
  if (expired) parts.push(`${expired} expired`);
  const soon = counted.filter((v) => v.expiring && v.row.expiresAt).sort((a, b) => Date.parse(a.row.expiresAt!) - Date.parse(b.row.expiresAt!));
  let expiring: string | null = null;
  if (soon.length === 1) expiring = `1 expires ${relative(soon[0].row.expiresAt!)}`;
  else if (soon.length > 1) expiring = `${soon.length} expire, the first ${relative(soon[0].row.expiresAt!)}`;
  const counts = parts.length ? parts.join(' · ') : null;
  let none: string | null = null;
  if (!counts && !oauth) {
    const bridges = views.filter((v) => v.bridge && v.row.state === 'active');
    none = bridges.length ? 'None · the Claude Desktop bridge gets short-lived ones' : 'None';
  }
  return { counts, expiring, oauth, none };
}

/** The names a deletion revokes: live named tokens and helper grants, by name ("codex-laptop, inspector-debug"). */
export function revocableNames(e: EndpointSummary, tokens: readonly TokenRow[] | null, now: number): string[] | null {
  if (!tokens) return null;
  return tokens
    .map((t) => tokenView(t, e.slug, now))
    .filter((v) => !v.bridge && v.row.state === 'active')
    .map((v) => v.row.name)
    .sort((a, b) => a.localeCompare(b));
}

/**
 * Why an endpoint serves nothing: the core names the first row that failed its integrity check ("endpoint row",
 * "tool sql_query", "connection wiring").
 */
export function blockedText(reason: string): string {
  return `The ${reason} was changed outside GateRook. It serves nothing until you review it.`;
}

// ── Disable, delete, create ─────────────────────────────────────────────────────────────────────────────────────

export interface ToastText {
  readonly tone: ToastTone;
  readonly title: string;
  readonly body: string;
}

/**
 * What disabling means for the people who use the endpoint (kit: "Claude Desktop and Codex get an error on their next
 * call. Tokens and settings are kept."). A single client that also reaches other endpoints keeps those: "… on its next
 * call to it".
 */
export function disableBody(e: EndpointSummary, all: readonly EndpointSummary[], tokens: readonly TokenRow[] | null, now: number): string {
  const clients = clientParts(e);
  let first: string;
  if (!clients.length) first = 'Agents get an error on their next call.';
  else {
    const one = clients.length === 1;
    const elsewhere = one && all.some((o) => o.slug !== e.slug && clientParts(o).some((c) => c.id === clients[0].id));
    const tunnel = e.published ? ', even with the tunnel on' : '';
    first = `${andList(clients.map((c) => c.name))} ${one ? 'gets' : 'get'} an error on ${one ? 'its' : 'their'} next call${elsewhere ? ' to it' : ''}${tunnel}.`;
  }
  const names = revocableNames(e, tokens, now);
  const hasTokens = names ? names.length > 0 : e.tokens.active > 0;
  return `${first} ${hasTokens ? 'Tokens and settings are kept.' : 'Settings are kept.'}`;
}

export function disabledToast(e: EndpointSummary, body: string): ToastText {
  return { tone: 'neutral', title: `Endpoint ${e.slug} disabled`, body };
}

/** The Delete confirmation: its paragraph of consequences and the facts under it. */
export interface DeleteFacts {
  readonly description: string;
  readonly clients: string;
  /** "codex-laptop, inspector-debug (revoked)", "None stored"; null when the token list didn't answer. */
  readonly tokens: string;
  /** "3 approval requests (denied)", or null when none waits. */
  readonly waiting: string | null;
}

/** Joins clauses as the kit's sentences do: "a, b and c"; two read "a, and b". */
function clauses(parts: readonly string[]): string {
  if (parts.length === 2) return `${parts[0]}, and ${parts[1]}`;
  return andList(parts);
}

export function deleteFacts(e: EndpointSummary, tokens: readonly TokenRow[] | null, now: number): DeleteFacts {
  const clients = e.clients.map(clientName);
  const names = revocableNames(e, tokens, now);
  const revokes = names ? names.length > 0 : e.tokens.active > 0;
  const waiting = e.approvalsWaiting;
  const parts: string[] = [];
  if (e.published) parts.push('claude.ai loses this connector', 'the tunnel stops publishing it');
  if (clients.length) {
    const prod = e.env === 'prod' ? ' to the prod connections' : '';
    parts.push(`${andList(clients)} ${clients.length === 1 ? 'loses' : 'lose'} access${prod} at once`);
  }
  if (revokes) parts.push('its tokens are revoked');
  if (waiting) parts.push(waiting === 1 ? 'the request waiting for approval is denied' : `the ${waiting} requests waiting for approval are denied`);
  if (!parts.length) parts.push('Its URL stops answering at once');
  else if (!revokes && !waiting && !e.published) parts.push('its URL stops answering');
  const lead = clauses(parts);
  // Sentence case, except a name that is written in lower case ("claude.ai loses this connector…").
  const first = lead.startsWith('claude.ai') ? lead : `${lead.charAt(0).toUpperCase()}${lead.slice(1)}`;
  const description = `${first}. Connections, their secrets and the audit log stay.`;
  // Claude Desktop always reaches an endpoint through its .mcpb bridge (ARCHITECTURE §8.6.2); alone, the kit says so.
  const alone = !e.published && e.clients.length === 1 && e.clients[0] === 'claude-desktop';
  const clientNames = alone ? ['Claude Desktop, through its bridge'] : [...clients, ...(e.published ? ['claude.ai, through the tunnel'] : [])];
  let tokenText: string;
  if (names) tokenText = names.length ? `${names.join(', ')} (revoked)` : 'None stored';
  else tokenText = e.tokens.active ? `${plural(e.tokens.active, 'active token')} (revoked)` : 'None stored';
  return {
    description,
    clients: clientNames.length ? clientNames.join(', ') : 'None',
    tokens: tokenText,
    waiting: waiting ? `${plural(waiting, 'approval request')} (denied)` : null,
  };
}

export function deletedToast(slug: string, result: { readonly tokensRevoked: readonly string[]; readonly rejectedApprovals: number }): ToastText {
  const facts: string[] = [];
  if (result.tokensRevoked.length) facts.push(`${plural(result.tokensRevoked.length, 'token')} revoked`);
  if (result.rejectedApprovals) facts.push(`${plural(result.rejectedApprovals, 'waiting request')} denied`);
  const tail = facts.length ? ` ${facts.join(', ')}.` : '';
  return { tone: 'success', title: `Endpoint ${slug} deleted`, body: `Its URL stops answering. The audit log keeps every call.${tail}` };
}

export function createdToast(slug: string, startFrom: StartFrom): ToastText {
  return {
    tone: 'success',
    title: `Endpoint ${slug} created`,
    body: startFrom === 'empty' ? 'Add its connections and pick tool modes next.' : 'Check its connections and tool modes next.',
  };
}

export function duplicatedToast(slug: string, source: string): ToastText {
  return { tone: 'success', title: `Endpoint ${slug} created`, body: `A copy of ${source} without tokens or AI app links.` };
}

/**
 * The meta line of each Start from card (kit: "6 dev connections · 12 tools"). The core's preview of each way to
 * start (`GET /api/endpoint-starts`, step API) gives the connections it would wire and the tools that would be on, as
 * the endpoint's card counts them after Create. Without it, the connections of the env are counted here and the tools
 * are left out: they depend on the core's rules for each connector.
 */
export function startFromMeta(connections: readonly ConnectionRow[] | null, starts: readonly EndpointStart[] | null = null): Record<StartFrom, string> {
  const start = (id: StartFrom) => starts?.find((s) => s.startFrom === id) ?? null;
  const count = (id: StartFrom, env: Env) =>
    start(id)?.connections.length ?? connections?.filter((c) => c.env === env && c.verified && c.type !== 'mcp-server').length ?? null;
  const dev = count('read-only-dev', 'dev');
  const prod = count('incident-response', 'prod');
  const of = (n: number | null, env: Env) =>
    n === null ? `${env[0].toUpperCase()}${env.slice(1)} connections` : n === 0 ? `No ${env} connections yet` : plural(n, `${env} connection`);
  const readOnly = start('read-only-dev');
  const on = readOnly ? readOnly.tools.read + readOnly.tools.approval : null;
  return {
    empty: 'Start from scratch',
    'read-only-dev': dev && on !== null ? `${of(dev, 'dev')} · ${plural(on, 'tool')}` : of(dev, 'dev'),
    'incident-response': `${of(prod, 'prod')} · stricter prod limits`,
  };
}

/** Where a field error of the core goes in the Create dialog (`field` is a JSON Pointer of the request). */
export function createField(pointer: string | null): 'slug' | 'description' | 'startFrom' | null {
  switch (pointer) {
    case '/slug':
      return 'slug';
    case '/description':
      return 'description';
    case '/startFrom':
      return 'startFrom';
    default:
      return null;
  }
}
