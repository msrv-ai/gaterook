import { ClientCard, ClientsView } from '../../core/api/clients.api';
import { ConnectionRow } from '../../core/api/connections.api';
import { ConnectionPreview, EndpointRisk, EndpointSettings, EndpointSummary, ToolGroup, TokenRow, WiredConnection } from '../../core/api/endpoints.api';
import { Env, JsonObject, JsonValue } from '../../core/api/types';
import { formatCount } from '../../core/time/time-format';
import { SplitClient, SplitOption } from '../../kit/elements/risk-callout/split-dialog';
import { clientName } from '../../kit/elements/shared/client-mark';
import { Look, healthLook } from '../../kit/elements/shared/dictionaries';
import { WiringClient } from '../../kit/elements/setup-map/setup-map';
import { ToolPolicyGroup } from '../../kit/elements/tool-policy-list/tool-policy-view';
import { ToastTone } from '../../kit/toast.service';

/**
 * What the endpoint page says (design/pages/endpoint-detail.html; ARCHITECTURE.md §8.4 "endpoint-detail"), from the
 * admin API's answers. Pure functions: the page and its tests share them, and each states the kit wording it follows.
 */

export const ENVS: readonly Env[] = ['dev', 'stage', 'prod'];

/** The tabs of the page, in the kit's order; the id is also the `?tab=` value and the panel's id. */
export const TABS = ['tools', 'connections', 'tokens', 'clients', 'settings'] as const;
export type TabId = (typeof TABS)[number];

export function parseTab(value: string | null | undefined): TabId {
  return (TABS as readonly string[]).includes(value ?? '') ? (value as TabId) : 'tools';
}

export function plural(n: number, one: string, many = `${one}s`): string {
  return `${formatCount(n)} ${n === 1 ? one : many}`;
}

/** "dev-db", "dev-db and app-logs", "dev-db, app-logs and billing-api". */
export function andList(items: readonly string[]): string {
  return items.length <= 1 ? items.join('') : `${items.slice(0, -1).join(', ')} and ${items.at(-1)}`;
}

// ── Header ──────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The line under the title (kit: "Local development on 8 connections. Agents read freely; anything that changes data
 * waits for your approval."): the endpoint's description, connection count, access and explicit Bypass modes.
 */
export function headerDescription(e: EndpointSummary, connections: number): string {
  const what = e.description?.trim().replace(/[.\s]+$/, '') || 'An endpoint';
  const on = connections ? ` on ${plural(connections, 'connection')}` : ' with no connections yet';
  if (e.status === 'disabled') return `${what}${on}. Disabled: every call gets an error until you enable it; tokens and settings are kept.`;
  if (e.access === 'read-only') return `${what}${on}. Read only: agents read, and no tool that changes data is offered.`;
  const bypass = e.tools.bypass ?? 0;
  if (bypass) return `${what}${on}. ${plural(bypass, 'tool')} in Bypass ${bypass === 1 ? 'runs' : 'run'} without approval, including changes to data. Policy denials and limits still apply.`;
  return `${what}${on}. Agents read freely; anything that changes data waits for your approval.`;
}

/** The status in the header: Running (success) or Disabled (neutral), as the kit's `#ep-status` toggles it. */
export function statusLook(e: EndpointSummary): Look {
  return e.status === 'disabled'
    ? { tone: 'neutral', icon: 'circle-pause', text: 'Disabled' }
    : { tone: 'success', icon: 'circle-check', text: 'Running' };
}

/** The MCP revisions the endpoint answers, oldest first (kit: "MCP 2025-11-25 · 2026-07-28"). */
export function servedRevisions(settings: EndpointSettings | null): string[] {
  const revs = settings?.protocol?.['revisions'];
  return Array.isArray(revs) ? revs.filter((r): r is string => typeof r === 'string').sort() : [];
}

/** "Tokens · OAuth off" (the kit's sign-in badge; it follows the OAuth switch of Settings). */
export function signInText(oauth: boolean): string {
  return `Tokens · OAuth ${oauth ? 'on' : 'off'}`;
}

// ── Wiring (setup-map --wiring) ─────────────────────────────────────────────────────────────────────────────────

/**
 * The clients on the wiring card and the Clients tab: those with an entry for this endpoint first (in the state of
 * that entry), then the ones installed on this computer but not connected to it ("Detected"). Not installed ones
 * are left out, as the kit leaves out Antigravity.
 */
export function endpointClients(view: ClientsView | null, slug: string): ClientCard[] {
  if (!view) return [];
  const linked = view.clients.filter((c) => c.links.some((l) => l.endpoint === slug));
  const installed = view.clients.filter((c) => !linked.includes(c) && c.status !== 'not-installed');
  return [...linked, ...installed];
}

export function wiringClients(cards: readonly ClientCard[], slug: string): WiringClient[] {
  return cards.map((c) => {
    const link = c.links.find((l) => l.endpoint === slug);
    if (!link) return { client: c.client, status: 'detected' };
    return { client: c.client, status: link.status === 'connected' || link.status === 'needs-restart' ? link.status : 'error' };
  });
}

/**
 * How the clients of the endpoint get their tokens (the wiring card's footer): "Tokens issued on the fly, none stored"
 * when every connected client goes through the bridge or the headers helper (kit), else "Each signs in with its own
 * token".
 */
export function clientsNote(cards: readonly ClientCard[], slug: string): string {
  const methods = cards.flatMap((c) => c.links.filter((l) => l.endpoint === slug).map((l) => l.method));
  return methods.length && methods.every((m) => m === 'mcpb' || m === 'helper')
    ? 'Tokens issued on the fly, none stored'
    : 'Each signs in with its own token';
}

/**
 * The wiring in words, for screen readers (kit `#ep-map-flow`: "Claude Desktop and Codex call endpoint dev with
 * short-lived tokens issued on the fly; Cursor is installed but not connected. dev checks each call …").
 */
export function flowText(slug: string, cards: readonly ClientCard[], wired: readonly WiredConnection[]): string {
  const connected = cards.filter((c) => c.links.some((l) => l.endpoint === slug && l.status === 'connected')).map((c) => c.name);
  const detected = cards.filter((c) => !c.links.some((l) => l.endpoint === slug)).map((c) => c.name);
  const how = clientsNote(cards, slug) === 'Tokens issued on the fly, none stored' ? ' with short-lived tokens issued on the fly' : '';
  const who = connected.length ? `${andList(connected)} ${connected.length === 1 ? 'calls' : 'call'} endpoint ${slug}${how}` : `No AI app calls endpoint ${slug} yet`;
  const also = detected.length ? `; ${andList(detected)} ${detected.length === 1 ? 'is' : 'are'} installed but not connected` : '';
  const rules = andList(policyEnvs(wired, 'dev'));
  const runs = wired.length ? `, then runs the tool on one of ${plural(wired.length, 'connection')}, which injects the secret` : '';
  return `${who}${also}. ${slug} checks each call against its tool modes and the ${rules} rules${runs}.`;
}

/** The environments whose rules the endpoint's pairs follow, in the kit's order (each pair keeps its connection's). */
export function policyEnvs(connections: readonly WiredConnection[], fallback: Env): Env[] {
  const envs = ENVS.filter((e) => connections.some((c) => c.env === e));
  return envs.length ? envs : [fallback];
}

/** The section of a policy, or an empty one. */
function section(sections: JsonObject | null | undefined, name: string): JsonObject {
  const s = sections?.[name];
  return s && typeof s === 'object' && !Array.isArray(s) ? (s as JsonObject) : {};
}

function num(v: JsonValue | undefined): number | null {
  return typeof v === 'number' && Number.isFinite(v) ? v : null;
}

/** The three limits an endpoint narrows (Settings › Limits): keys of the policy's `limits` section (`x-stricter`). */
export const ENDPOINT_LIMITS = ['maxResponseKb', 'rateLimitPerToken', 'rateLimitPerTool'] as const;
export type EndpointLimit = (typeof ENDPOINT_LIMITS)[number];

export interface EffectiveLimits {
  readonly perToken: number | null;
  readonly perTool: number | null;
  readonly maxResponseKb: number | null;
}

/**
 * What applies on the endpoint: the policy's value, or the endpoint's own when it is stricter (the core merges "only
 * stricter", so a larger own value has no effect).
 */
export function effectiveLimits(policySections: JsonObject | null | undefined, own: JsonObject | null | undefined): EffectiveLimits {
  const limits = section(policySections, 'limits');
  const pick = (key: EndpointLimit): number | null => {
    const p = num(limits[key]);
    const o = num(own?.[key]);
    if (p === null) return o;
    return o === null ? p : Math.min(p, o);
  };
  return { perToken: pick('rateLimitPerToken'), perTool: pick('rateLimitPerTool'), maxResponseKb: pick('maxResponseKb') };
}

/** "120 calls per min per token · 512 KB per result" (the wiring card's Limits row). */
export function limitsLine(l: EffectiveLimits): string {
  const parts: string[] = [];
  if (l.perToken !== null) parts.push(`${formatCount(l.perToken)} calls per min per token`);
  if (l.maxResponseKb !== null) parts.push(`${formatCount(l.maxResponseKb)} KB per result`);
  return parts.join(' · ');
}

/** Each connection's group names what it reaches (kit: "PostgreSQL 16 · db.dev.internal:5432/app · 4 tools"). */
export function withTargets(groups: readonly ToolPolicyGroup[], rows: readonly ConnectionRow[] | null): ToolPolicyGroup[] {
  return groups.map((g) => {
    const host = g.group.connection ? rows?.find((r) => r.name === g.group.connection)?.host : null;
    return host ? { ...g, detail: [g.detail, host].filter(Boolean).join(' · ') } : g;
  });
}

// ── Tabs ────────────────────────────────────────────────────────────────────────────────────────────────────────

export interface TabCounts {
  readonly tools: number;
  readonly connections: number;
  /** Active tokens a client can use, the bridge row left out (kit "2" = codex-laptop + inspector-debug). */
  readonly tokens: number;
  readonly clients: number;
}

export function tabCounts(e: EndpointSummary, connections: number, tokens: readonly TokenRow[] | null, cards: readonly ClientCard[], slug: string): TabCounts {
  return {
    tools: e.tools.read + e.tools.approval + e.tools.off + (e.tools.bypass ?? 0),
    connections,
    tokens: tokens ? activeTokens(tokens) : e.tokens.active,
    clients: cards.filter((c) => c.links.some((l) => l.endpoint === slug && l.status === 'connected')).length,
  };
}

/** Active tokens and grants, without the Claude Desktop bridge (it issues its own on the fly; the kit doesn't count it). */
export function activeTokens(tokens: readonly TokenRow[]): number {
  return tokens.filter((t) => t.state === 'active' && !(String(t.kind).toLowerCase() === 'grant' && t.client === 'claude-desktop')).length;
}

// ── Connections tab ─────────────────────────────────────────────────────────────────────────────────────────────

/** "6 in dev, 2 in stage" (the Connections card's description). */
export function envCounts(connections: readonly WiredConnection[]): { readonly env: Env; readonly count: number }[] {
  return ENVS.map((env) => ({ env, count: connections.filter((c) => c.env === env).length })).filter((x) => x.count > 0);
}

/** "4" and "· 3 read only, 1 approval" (Tools on dev). */
export function toolsCell(t: { readonly read: number; readonly approval: number; readonly off: number; readonly bypass?: number }): { readonly total: number; readonly modes: string } {
  const parts: string[] = [];
  if (t.read) parts.push(`${t.read} read only`);
  if (t.approval) parts.push(`${t.approval} approval`);
  if (t.bypass) parts.push(`${t.bypass} bypass`);
  if (t.off) parts.push(`${t.off} off`);
  return { total: t.read + t.approval + t.off + (t.bypass ?? 0), modes: parts.join(', ') };
}

/** One row of the Connections table: the endpoint's wiring with what the connections list knows (host, latency). */
export interface ConnectionView {
  readonly c: WiredConnection;
  readonly row: ConnectionRow | null;
  readonly target: string;
  readonly health: Look;
  readonly tools: { readonly total: number; readonly modes: string };
}

export function connectionViews(wired: readonly WiredConnection[], rows: readonly ConnectionRow[] | null): ConnectionView[] {
  return wired.map((c) => {
    const row = rows?.find((r) => r.name === c.name) ?? null;
    // The connections list has the latest health (events patch it); the endpoint detail has it at load.
    const state = row?.health?.state ?? c.health;
    const latency = row?.health?.latencyMs ?? null;
    return {
      c,
      row,
      target: row?.host ?? '',
      health: healthLook(state, state === 'healthy' || state === 'degraded' ? latency : null),
      tools: toolsCell(c.tools),
    };
  });
}

/**
 * The kit's alert over the table for a connection that can't be reached: what fails on this endpoint and what to do.
 * "The gateway can't reach https://billing.stage.acme.dev since 13:42, so http_request calls on dev fail."
 */
export interface UnreachableAlert {
  readonly name: string;
  readonly env: Env;
  readonly target: string;
  /** Since when the checks say it is unreachable (step API's `health.since`): "can't reach … since 13:42". */
  readonly since: string | null;
  /** When it was last checked, for a core that doesn't say since when: "couldn't reach … at 13:42". */
  readonly checkedAt: string | null;
  readonly tools: string[];
  readonly message: string | null;
}

export function unreachableAlerts(views: readonly ConnectionView[], groups: readonly ToolGroup[] | null): UnreachableAlert[] {
  return views
    .filter((v) => v.health.tone === 'danger')
    .map((v) => ({
      name: v.c.name,
      env: v.c.env,
      target: v.target,
      since: v.row?.health?.since ?? null,
      checkedAt: v.row?.health?.checkedAt ?? null,
      tools: (groups?.find((g) => g.connection === v.c.name)?.tools ?? []).filter((t) => t.mode !== 'OFF').map((t) => t.name),
      message: v.row?.health?.message ?? v.c.problem ?? null,
    }));
}

/**
 * More than two unreachable connections read as one alert (a VPN that is down, not eight separate problems): which
 * ones, and where to look. Up to two keep the kit's alert per connection.
 */
export const UNREACHABLE_ALERTS_MAX = 2;

/** The order of the Tokens tab (kit): active first (the bridge, then client accesses, then named tokens), expired, revoked. */
export function sortTokens(tokens: readonly TokenRow[]): TokenRow[] {
  const state = (t: TokenRow) => ({ active: 0, expired: 1, revoked: 2 })[t.state] ?? 3;
  const kind = (t: TokenRow) => (String(t.kind).toLowerCase() === 'grant' ? (t.client === 'claude-desktop' ? 0 : 1) : 2);
  return [...tokens].sort((a, b) => state(a) - state(b) || kind(a) - kind(b) || b.createdAt.localeCompare(a.createdAt));
}

/** How a connection's secret is held, for the Add connections dialog (kit: "PostgreSQL · secret in Vault"). */
export function secretText(source: string | null | undefined): string | null {
  switch (source) {
    case 'encrypted':
    case 'local':
      return 'secret encrypted here';
    case 'vault':
      return 'secret in Vault';
    case 'gcp-sm':
      return 'secret in GCP Secret Manager';
    case 'aws-sm':
      return 'secret in AWS Secrets Manager';
    case 'short-lived':
      return 'short-lived credentials';
    default:
      return null;
  }
}

/** A connection the endpoint doesn't have yet, with what adding it would do (the core's preview). */
export interface AddCandidate {
  readonly row: ConnectionRow;
  /** "PostgreSQL · secret in Vault"; with the core's answer: "3 read only, 1 approval". */
  readonly hint: string;
  readonly preview: ConnectionPreview | null;
  /** Why it can't be added ("goes only on a prod endpoint"): the checkbox is aria-disabled with this reason. */
  readonly refusal: string | null;
  readonly mcpServer: boolean;
}

export function addCandidates(all: readonly ConnectionRow[], wired: readonly WiredConnection[], previews: ReadonlyMap<string, ConnectionPreview | null>): AddCandidate[] {
  const on = new Set(wired.map((w) => w.name));
  return all
    .filter((r) => !on.has(r.name))
    .map((row) => {
      const preview = previews.get(row.name) ?? null;
      const what = [row.typeName, ...row.badges, secretText(row.secretSource)].filter(Boolean).join(' · ');
      const modes = preview && !preview.refusal ? toolsCell(countModes(preview.tools.map((t) => t.mode))).modes : '';
      return {
        row,
        hint: modes ? `${what} · its tools start as ${modes}` : what,
        preview,
        refusal: preview?.refusal ? sentence(preview.refusal) : null,
        mcpServer: row.type === 'mcp-server',
      };
    })
    // What can be added first, then what the core refuses; in each, as the connections list sorts (by name).
    .sort((a, b) => Number(!!a.refusal) - Number(!!b.refusal) || a.row.name.localeCompare(b.row.name));
}

/** The core's sentences sometimes end without a stop; the dialog's hints are sentences. */
export function sentence(text: string): string {
  const t = text.trim();
  return !t || /[.!?…]$/.test(t) ? t : `${t}.`;
}

function countModes(modes: readonly string[]): { read: number; approval: number; off: number; bypass: number } {
  return {
    read: modes.filter((m) => m === 'READ_ONLY').length,
    approval: modes.filter((m) => m === 'APPROVAL').length,
    bypass: modes.filter((m) => m === 'BYPASS').length,
    off: modes.filter((m) => m === 'OFF').length,
  };
}

/** Remove <connection> from <endpoint>: what disappears (kit: "Its 4 tools disappear from dev on the next call, …"). */
export function removeText(c: WiredConnection, slug: string): string {
  const n = toolsCell(c.tools).total;
  const its = n === 1 ? 'Its tool disappears' : `Its ${formatCount(n)} tools disappear`;
  return `${its} from ${slug} on the next call, with ${n === 1 ? 'its mode and limits' : 'their modes and limits'}. The connection, its secret and its audit history stay.`;
}

// ── Tokens, clients, delete, disable ────────────────────────────────────────────────────────────────────────────

/** What can call the endpoint now: names of active named tokens and grants (revoked by Delete). */
export function revocableNames(tokens: readonly TokenRow[]): string[] {
  // The bridge's access is Claude Desktop's (the Clients line says it loses access); the kit lists the others.
  return sortTokens(tokens)
    .filter((t) => t.state === 'active' && !(String(t.kind).toLowerCase() === 'grant' && t.client === 'claude-desktop'))
    .map((t) => t.name);
}

/** The Delete confirmation: its paragraph and the facts under it (kit `#ep-dlg-delete`). */
export interface DeleteFacts {
  readonly description: string;
  readonly clients: string;
  readonly tokens: string;
  readonly waiting: string | null;
}

export function deleteFacts(e: EndpointSummary, cards: readonly ClientCard[], tokens: readonly TokenRow[] | null): DeleteFacts {
  const clients = cards.filter((c) => c.links.some((l) => l.endpoint === e.slug)).map((c) => c.name);
  const named = tokens ? revocableNames(tokens) : [];
  const waiting = e.approvalsWaiting;
  const who = clients.length ? `${andList(clients)} lose${clients.length === 1 ? 's' : ''} access at once` : 'AI apps lose access at once';
  const toks = named.length || !tokens ? ', its tokens are revoked' : '';
  const reqs = waiting ? ` and the ${plural(waiting, 'request')} waiting for approval ${waiting === 1 ? 'is' : 'are'} denied` : '';
  return {
    description: `${who}${toks}${reqs}. Connections, their secrets and the audit log stay.`,
    clients: clients.length ? clients.join(', ') : 'None',
    tokens: !tokens ? 'Unknown' : named.length ? `${named.join(', ')} (revoked)` : 'None active',
    waiting: waiting ? `${plural(waiting, 'approval request')} (denied)` : null,
  };
}

/** What Disable means for the clients (kit: "Claude Desktop and Codex get an error on their next call. …"). */
export function disableBody(cards: readonly ClientCard[], slug: string, hasTokens: boolean): string {
  const names = cards.filter((c) => c.links.some((l) => l.endpoint === slug && l.status === 'connected')).map((c) => c.name);
  const first = names.length
    ? `${andList(names)} ${names.length === 1 ? 'gets an error on its' : 'get an error on their'} next call.`
    : 'Agents get an error on their next call.';
  return `${first} ${hasTokens ? 'Tokens and settings are kept.' : 'Settings are kept.'}`;
}

export interface ToastText {
  readonly tone: ToastTone;
  readonly title: string;
  readonly body?: string;
}

// ── Slugs ───────────────────────────────────────────────────────────────────────────────────────────────────────

/** What `endpoint.slug` allows (core `EndpointService.SLUG`); the kit's fields stop at 40 characters. */
export const SLUG = /^[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?$/;

export function slugError(value: string): string | null {
  const v = value.trim();
  if (!v) return 'Name the endpoint, for example dev-copy.';
  if (v.length > 40) return 'Use at most 40 characters.';
  if (!SLUG.test(v)) return 'Use lowercase letters, digits and hyphens; start and end with a letter or digit.';
  return null;
}

/** The first free "<slug>-copy", "<slug>-copy-2", … (kit: Duplicate suggests dev-copy). */
export function copySlug(slug: string, taken: readonly string[], suffix = 'copy'): string {
  const base = `${slug}-${suffix}`.slice(0, 40).replace(/-+$/, '');
  if (!taken.includes(base)) return base;
  for (let i = 2; i < 1000; i++) {
    const candidate = `${base.slice(0, 40 - String(i).length - 1)}-${i}`;
    if (!taken.includes(candidate)) return candidate;
  }
  return base;
}

// ── Split ───────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * What the Split dialog offers to move: each connection whose tools bring the external channel (moving it is what
 * breaks the combination; the kit pre-selects billing-api), then each connection that is the ONLY source of private
 * data or untrusted content (moving any other would leave the ingredient on the endpoint). A connection whose channel
 * is only "limited" (fixed runbooks) comes after one that can send data anywhere.
 */
export function splitOptions(risk: EndpointRisk, groups: readonly ToolGroup[], wired: readonly WiredConnection[], slug: string): SplitOption[] {
  const sourcesOf = (ingredient: string) => [...new Set(risk.sources.filter((s) => s.ingredient === ingredient).map((s) => s.connection))];
  const toolsOf = (connection: string) => (groups.find((g) => g.connection === connection)?.tools ?? []).filter((t) => t.mode !== 'OFF').map((t) => t.name);
  const envOf = (connection: string): Env => wired.find((w) => w.name === connection)?.env ?? 'dev';
  const out: SplitOption[] = [];
  const channel = sourcesOf('externalChannel').sort((a, b) => limitedChannel(risk, a) - limitedChannel(risk, b));
  for (const connection of channel) {
    out.push({
      connection,
      env: envOf(connection),
      ingredient: 'externalChannel',
      tools: toolsOf(connection),
      effect: channel.length === 1 ? `Agents on ${slug} can no longer send data out.` : `Agents on ${slug} can no longer send data out through it.`,
      meta: '',
      recommended: false,
    });
  }
  for (const ingredient of ['untrustedContent', 'privateData'] as const) {
    const only = sourcesOf(ingredient);
    if (only.length !== 1 || out.some((o) => o.connection === only[0])) continue;
    out.push({
      connection: only[0],
      env: envOf(only[0]),
      ingredient,
      tools: toolsOf(only[0]),
      effect: ingredient === 'untrustedContent' ? `Agents on ${slug} no longer read text other people wrote.` : `Agents on ${slug} no longer read private data.`,
      meta: '',
      recommended: false,
    });
  }
  return out.map((o, i) => ({ ...o, recommended: i === 0, meta: optionMeta(o, i === 0, out) }));
}

/** 1 when the connection's channel is only fixed runbooks ("ssh_run runs fixed runbooks"), else 0. */
function limitedChannel(risk: EndpointRisk, connection: string): number {
  const s = risk.sources.find((x) => x.ingredient === 'externalChannel' && x.connection === connection);
  return s && /fixed runbooks/.test(s.text) ? 1 : 0;
}

function optionMeta(o: SplitOption, recommended: boolean, all: readonly SplitOption[]): string {
  const others = all.filter((x) => x.ingredient === 'externalChannel' && x.connection !== o.connection).map((x) => x.connection);
  if (o.ingredient === 'externalChannel') {
    if (recommended) return others.length ? `Recommended · the widest way out; ${andList(others)} stays` : 'Recommended · removes the way out';
    return 'A narrower way out';
  }
  return o.ingredient === 'untrustedContent' ? 'Leaves private data and an external channel' : 'Leaves untrusted content and an external channel';
}

/**
 * The clients of the endpoint for "Clients that need the moved tools", checked when they called a tool of the moving
 * connection today (Activity facets: `endpoint:<slug> connection:<name>` since midnight).
 */
export function splitClients(cards: readonly ClientCard[], slug: string, calledToday: ReadonlyMap<string, string>): SplitClient[] {
  return cards
    .filter((c) => c.links.some((l) => l.endpoint === slug))
    .map((c) => {
      const tool = calledToday.get(c.client) ?? null;
      return { id: c.client, name: c.name, note: tool ? `called ${tool} today` : null, checked: !!tool };
    });
}

/** "Endpoint dev-billing created" with what moved and who should connect next (kit's split toast). */
export function splitToast(newSlug: string, moved: string, slug: string, remaining: EndpointRisk | null, clients: readonly string[]): ToastText {
  const still = remaining && (remaining.state === 'present' || remaining.state === 'partial');
  const what = `${moved} and its tools moved from ${slug}.${remaining ? (still ? ` ${slug} still has a risky combination.` : ` ${slug} has no risky combination now.`) : ''}`;
  const next = clients.length ? ` Next: connect ${andList(clients.map(clientName))} to it.` : '';
  return { tone: 'success', title: `Endpoint ${newSlug} created`, body: what + next };
}

// ── Settings ────────────────────────────────────────────────────────────────────────────────────────────────────

/** "765 / 4,000" (the instructions counter). */
export function instructionsCount(chars: number, max: number): string {
  return `${formatCount(chars)} / ${formatCount(max)}`;
}

/**
 * A limit field's text → its number: empty is "follow the policy" (null); anything else must be a whole number ≥ 1
 * ("1,000" and "1 000" are read as 1000).
 */
export function parseLimit(raw: string): { readonly value: number | null; readonly error: string | null } {
  const t = raw.replace(/[,\s]/g, '');
  if (!t) return { value: null, error: null };
  if (!/^\d+$/.test(t) || Number(t) < 1) return { value: null, error: 'Enter a whole number, or leave it empty to follow the policy.' };
  return { value: Number(t), error: null };
}

/** The endpoint's own value of a limit, as the field shows it ("" when it follows the policy). */
export function limitText(own: JsonObject | null | undefined, key: EndpointLimit): string {
  const v = num(own?.[key]);
  return v === null ? '' : String(v);
}

/** The revision each client used on the endpoint (Settings › Protocol), from the core's per-revision clients. */
export function revisionsByClient(revisions: EndpointSettings['revisions'] | null | undefined): { readonly client: string; readonly revisions: string }[] {
  const byClient = new Map<string, string[]>();
  for (const r of revisions ?? []) {
    for (const c of r.clients) byClient.set(c, [...(byClient.get(c) ?? []), r.rev]);
  }
  return [...byClient.entries()]
    .map(([client, revs]) => ({ client: clientName(client), revisions: [...revs].sort().join(', ') }))
    .sort((a, b) => a.client.localeCompare(b.client));
}

/** The clients that used a revision recently: turning it off breaks them. */
export function clientsOfRevision(revisions: EndpointSettings['revisions'] | null | undefined, rev: string): string[] {
  return (revisions ?? []).find((r) => r.rev === rev)?.clients.map(clientName) ?? [];
}

/** The meter of Settings › Limits: the busiest token of this endpoint now against the per-token limit. */
export interface BusiestMeter {
  readonly label: string;
  readonly calls: number;
  readonly limit: number;
  /** The kit's `data-level` (`.gw-meter`): ok, warn (from 70 %), critical (from 90 %). */
  readonly level: 'ok' | 'warn' | 'critical';
  readonly ratio: number;
  /** "14 / 120 calls in the last minute" (the visible value). */
  readonly value: string;
  /** aria-valuetext: the reading and what it means. */
  readonly valueText: string;
}

/**
 * The busiest token is the gateway's (`GET /api/policies` usage): it is this endpoint's only when the endpoint has a
 * token of that name. Otherwise the meter shows the endpoint's own calls in the last minute (`settings.usage`).
 */
export function busiestMeter(
  busiest: { readonly name: string; readonly calls: number } | null | undefined,
  tokens: readonly TokenRow[] | null,
  limit: number | null,
  endpointCalls: number,
  slug: string,
): BusiestMeter | null {
  if (!limit) return null;
  const ours = busiest && tokens?.some((t) => t.name === busiest.name) ? busiest : null;
  const calls = ours?.calls ?? endpointCalls;
  const ratio = Math.min(1, calls / limit);
  const level = ratio >= 0.9 ? 'critical' : ratio >= 0.7 ? 'warn' : 'ok';
  const words = level === 'ok' ? 'well under the limit' : level === 'warn' ? 'close to the limit' : 'at the limit';
  return {
    label: ours ? `Busiest token now · ${ours.name}` : calls ? `Calls on ${slug} now` : 'Busiest token now · no calls in the last minute',
    calls,
    limit,
    level,
    ratio,
    value: `${formatCount(calls)} / ${formatCount(limit)} calls in the last minute`,
    valueText: `${formatCount(calls)} of ${formatCount(limit)} calls in the last minute, ${words}`,
  };
}

/**
 * The environment whose policy the endpoint's limits come from: where most of its connections are (a tie goes to the
 * stricter). Kit: "from the dev policy; tools on stage connections follow the stage policy".
 */
export function primaryEnv(connections: readonly WiredConnection[], fallback: Env): Env {
  let best: Env | null = null;
  let most = 0;
  for (const env of ENVS) {
    const n = connections.filter((c) => c.env === env).length;
    if (n && n >= most) {
      best = env;
      most = n;
    }
  }
  return best ?? fallback;
}

/** The hints under the limit fields (kit: "Empty: 512 KB from the dev policy. A larger value has no effect."). */
export function limitHints(policy: EffectiveLimits, env: Env, slug: string, exampleToken: string | null): { readonly size: string; readonly perToken: string; readonly perTool: string } {
  const from = (v: number | null, unit = '') => (v === null ? `Empty: no limit in the ${env} policy.` : `Empty: ${formatCount(v)}${unit} from the ${env} policy.`);
  return {
    size: `${from(policy.maxResponseKb, ' KB')} A larger value has no effect.`,
    perToken: `${from(policy.perToken)} For each token on ${slug}${exampleToken ? ` such as ${exampleToken}` : ''}.`,
    perTool: `${from(policy.perTool)} For one tool and one token. Stops an agent stuck in a loop.`,
  };
}

/** A named token of the endpoint to name in the per-token hint (kit: "such as codex-laptop"). */
export function exampleToken(tokens: readonly TokenRow[] | null): string | null {
  return tokens ? (sortTokens(tokens).find((t) => t.state === 'active' && t.client !== 'claude-desktop')?.name ?? null) : null;
}

/** The MCP revisions the core can serve, newest first, with what each means for approvals (kit Settings › Protocol). */
export const KNOWN_REVISIONS: readonly { readonly rev: string; readonly hint: string }[] = [
  {
    rev: '2026-07-28',
    hint: 'Stateless: every request stands alone. Approvals come back as an input request with a link to Approvals in this app; the AI app waits and retries. Only this app or a linked Slack or Telegram approver can approve.',
  },
  { rev: '2025-11-25', hint: 'Starts with initialize. Approvals return a handle the agent polls with approval_status.' },
];

/** "CIMD" (Settings › Authorization › Client registration). */
export function registrationText(value: JsonValue | undefined): string {
  return value === 'cimd+dcr' ? 'CIMD, then dynamic registration' : 'CIMD';
}

/** `http://127.0.0.1:7465/.well-known/oauth-protected-resource/mcp/dev` (RFC 9728 path-suffixed metadata). */
export function resourceMetadataUrl(url: string): string {
  try {
    const u = new URL(url);
    return `${u.origin}/.well-known/oauth-protected-resource${u.pathname}`;
  } catch {
    return url;
  }
}

/** `127.0.0.1:7465` from the endpoint's URL (the dialogs' URL previews). */
export function addressOf(url: string): string {
  try {
    return new URL(url).host;
  } catch {
    return '127.0.0.1:7465';
  }
}

// ── Toasts ──────────────────────────────────────────────────────────────────────────────────────────────────────

/** Revoke (kit: "codex-laptop revoked" · "Codex loses access on its next call."). */
export function revokedToast(row: TokenRow, rejectedApprovals: number): ToastText {
  const grant = String(row.kind).toLowerCase() === 'grant';
  const who = row.client ? clientName(row.client) : null;
  const lead = who ? `${who} loses access on its next call.` : 'Apps using it lose access on their next call.';
  const denied = rejectedApprovals ? ` ${plural(rejectedApprovals, 'request')} waiting for approval ${rejectedApprovals === 1 ? 'was' : 'were'} denied.` : '';
  return { tone: 'success', title: grant ? `${row.name} revoked` : `Token ${row.name} revoked`, body: lead + denied };
}

/** Add connections: what went on, how its tools start, and what the core refused. */
export function addedToast(slug: string, added: readonly AddCandidate[], refused: readonly { readonly name: string; readonly reason: string }[]): ToastText {
  if (!added.length) {
    return { tone: 'danger', title: `Nothing was added to ${slug}`, body: refused.map((r) => r.reason).join(' ') };
  }
  const names = andList(added.map((a) => a.row.name));
  const modes = added.length === 1 && added[0].preview ? toolsCell(countModes(added[0].preview.tools.map((t) => t.mode))).modes : '';
  const how = modes ? `Its tools start as ${modes}; change them under Tools & policies.` : 'Their tools start as their types suggest; change them under Tools & policies.';
  const not = refused.length ? ` Not added: ${refused.map((r) => `${r.name} (${r.reason.replace(/\.$/, '')})`).join('; ')}.` : '';
  return { tone: refused.length ? 'warning' : 'success', title: `${names} added to ${slug}`, body: how + not };
}

/** Connect from the Clients tab: the core's notes, else the kit's sentence. */
export function connectedToast(clientLabel: string, slug: string, notes: readonly string[]): ToastText {
  return {
    tone: 'success',
    title: `${clientLabel} connected to ${slug}`,
    body: notes.length ? notes.join(' ') : `${clientLabel} gets the tools of ${slug} on its next start. Backup kept next to the file.`,
  };
}

/** Disconnect (kit: "Claude Desktop disconnected from dev" · "incident-prod still works through the bridge."). */
export function disconnectedToast(clientLabel: string, slug: string, others: readonly string[], bridge: boolean, rejectedApprovals: number): ToastText {
  const still = others.length ? `${andList(others)} still ${others.length === 1 ? 'works' : 'work'}${bridge ? ' through the bridge' : ''}.` : `The entry is removed and its access to ${slug} is revoked.`;
  const denied = rejectedApprovals ? ` ${plural(rejectedApprovals, 'request')} waiting for approval ${rejectedApprovals === 1 ? 'was' : 'were'} denied.` : '';
  return { tone: 'success', title: `${clientLabel} disconnected from ${slug}`, body: still + denied };
}
