import { ApprovalItem } from '../../core/api/approvals.api';
import { ConnectionDetail, ConnectionRow, ConnectionDeleted, TestOutcome } from '../../core/api/connections.api';
import { EndpointSummary } from '../../core/api/endpoints.api';
import { SecretRow, ShortLivedCredential } from '../../core/api/secrets.api';
import { Env } from '../../core/api/types';
import { duration } from '../../core/time/time-format';
import { clientName } from '../../kit/elements/shared/client-mark';
import { CONNECTOR_NAMES, connectorIcon } from '../../kit/elements/shared/connector-icons';
import { Look, healthLook } from '../../kit/elements/shared/dictionaries';
import { ToastTone } from '../../kit/toast.service';

/*
 * What the Connections page (design/pages/connections.html) says about the core's rows, as pure functions: the
 * filters, the counts, the health and credentials cells, the alert under a row, the delete confirmation and the
 * toasts of Test connection and Test all. The page and its data service only wire them to the API and the DOM.
 */

/** A connection's health as the page groups it; the API's `degraded` is the kit's "Slow". */
export type HealthState = 'healthy' | 'degraded' | 'unreachable' | 'unknown';
export type HealthFilter = 'all' | HealthState;
export type EnvFilter = 'all' | Env;

export interface Filters {
  readonly q: string;
  readonly type: string;
  readonly env: EnvFilter;
  readonly health: HealthFilter;
}

export const NO_FILTERS: Filters = { q: '', type: 'all', env: 'all', health: 'all' };

export const ENVS: readonly Env[] = ['dev', 'stage', 'prod'];

export function healthState(row: Pick<ConnectionRow, 'health'>): HealthState {
  const s = (row.health?.state ?? '').toLowerCase();
  return s === 'healthy' || s === 'degraded' || s === 'unreachable' ? s : 'unknown';
}

/**
 * A filter value from a link (`/connections?health=slow&env=prod&type=postgres`): the kit's words and the API's are
 * both accepted; anything else means "all".
 */
export function parseHealth(value: string | null | undefined): HealthFilter {
  const v = (value ?? '').toLowerCase();
  if (v === 'slow' || v === 'degraded') return 'degraded';
  if (v === 'healthy' || v === 'unreachable' || v === 'unknown') return v;
  return 'all';
}

export function parseEnv(value: string | null | undefined): EnvFilter {
  return ENVS.find((e) => e === value) ?? 'all';
}

/** By name, as the kit's table ("All connections, by name"). */
export function sortRows(rows: readonly ConnectionRow[]): ConnectionRow[] {
  return [...rows].sort((a, b) => a.name.localeCompare(b.name));
}

/** The kit's search: name, host and type (with its badges), case-insensitive. */
export function matches(row: ConnectionRow, f: Filters): boolean {
  if (f.type !== 'all' && row.type !== f.type) return false;
  if (f.env !== 'all' && row.env !== f.env) return false;
  if (f.health !== 'all' && healthState(row) !== f.health) return false;
  const q = f.q.trim().toLowerCase();
  if (!q) return true;
  const text = [row.name, row.host ?? '', row.typeName, row.typeLabel ?? '', ...row.badges].join(' ').toLowerCase();
  return text.includes(q);
}

/**
 * The Type cell in the kit's words: the core's label with the server's version or the Logs backend ("PostgreSQL 16",
 * "Loki"); a core before step API names the connector ("PostgreSQL", "Logs").
 */
export function typeText(row: Pick<ConnectionRow, 'typeName' | 'typeLabel'>): string {
  return row.typeLabel || row.typeName;
}

/** External MCP servers are listed on Connectors (kit), not among the connections. */
export function isMcpServer(row: Pick<ConnectionRow, 'type'>): boolean {
  return row.type === 'mcp-server';
}

export function filtered(rows: readonly ConnectionRow[], f: Filters): ConnectionRow[] {
  return rows.filter((r) => matches(r, f));
}

export function isFiltered(f: Filters): boolean {
  return f.q.trim() !== '' || f.type !== 'all' || f.env !== 'all' || f.health !== 'all';
}

export interface MenuOption {
  readonly value: string;
  readonly label: string;
  readonly icon: string | null;
  readonly count: number;
}

/** The order the kit lists connector types in (BLUEPRINT §9): databases, remote access, logs, clusters, clouds, HTTP. */
const TYPE_ORDER = Object.keys(CONNECTOR_NAMES);

/**
 * The Type filter: "All types" with every connection, then each type the connections have, in the kit's order (a
 * type the kit doesn't know follows by name). The selected type stays listed while none of its rows is left (after
 * a delete), so the trigger never names an option that isn't there.
 */
export function typeOptions(rows: readonly ConnectionRow[], selected = 'all'): MenuOption[] {
  const byType = new Map<string, { label: string; count: number }>();
  for (const r of rows) {
    const t = byType.get(r.type);
    // The connector's name (kit "PostgreSQL", "Logs"), not a row's own line, which may name a version or backend.
    byType.set(r.type, { label: t?.label ?? CONNECTOR_NAMES[r.type] ?? r.typeName ?? r.type, count: (t?.count ?? 0) + 1 });
  }
  if (selected !== 'all' && !byType.has(selected)) byType.set(selected, { label: CONNECTOR_NAMES[selected] ?? selected, count: 0 });
  const rank = (type: string) => {
    const i = TYPE_ORDER.indexOf(type);
    return i < 0 ? TYPE_ORDER.length : i;
  };
  const types = [...byType.keys()].sort((a, b) => rank(a) - rank(b) || a.localeCompare(b));
  return [
    { value: 'all', label: 'All types', icon: 'shapes', count: rows.length },
    ...types.map((t) => ({ value: t, label: byType.get(t)!.label, icon: connectorIcon(t), count: byType.get(t)!.count })),
  ];
}

export interface HealthCounts {
  readonly healthy: number;
  readonly degraded: number;
  readonly unreachable: number;
  readonly unknown: number;
}

export function healthCounts(rows: readonly ConnectionRow[]): HealthCounts {
  const c = { healthy: 0, degraded: 0, unreachable: 0, unknown: 0 };
  for (const r of rows) c[healthState(r)]++;
  return c;
}

const HEALTH_LABELS: Record<HealthState, string> = {
  healthy: 'Healthy',
  degraded: 'Slow',
  unreachable: 'Unreachable',
  unknown: 'Not checked yet',
};

/**
 * The Health filter with live counts (rows change with every `connection.health`). "Not checked yet" is offered only
 * while some connection hasn't been checked (or it is the current choice): the kit's menu has the three others.
 */
export function healthOptions(rows: readonly ConnectionRow[], selected: HealthFilter = 'all'): MenuOption[] {
  const c = healthCounts(rows);
  const states: HealthState[] = ['healthy', 'degraded', 'unreachable'];
  if (c.unknown > 0 || selected === 'unknown') states.push('unknown');
  return [
    { value: 'all', label: 'Any health', icon: null, count: rows.length },
    ...states.map((s) => ({ value: s, label: HEALTH_LABELS[s], icon: null, count: c[s] })),
  ];
}

export function optionLabel(options: readonly MenuOption[], value: string): string {
  return options.find((o) => o.value === value)?.label ?? options[0]?.label ?? '';
}

/** The page header's counters: "10 healthy · 1 slow · 1 unreachable", only the states some connection is in. */
export function headerStatuses(c: HealthCounts): { readonly tone: Look['tone']; readonly label: string }[] {
  const out: { tone: Look['tone']; label: string }[] = [];
  if (c.healthy) out.push({ tone: 'success', label: `${c.healthy} healthy` });
  if (c.degraded) out.push({ tone: 'warning', label: `${c.degraded} slow` });
  if (c.unreachable) out.push({ tone: 'danger', label: `${c.unreachable} unreachable` });
  if (c.unknown) out.push({ tone: 'neutral', label: `${c.unknown} not checked yet` });
  return out;
}

/** The latest health check of any row (the header says how fresh the counters are). */
export function lastChecked(rows: readonly ConnectionRow[]): string | null {
  let best: string | null = null;
  for (const r of rows) {
    const at = r.health?.checkedAt;
    if (at && (!best || Date.parse(at) > Date.parse(best))) best = at;
  }
  return best;
}

export const plural = (n: number, one: string, many = `${one}s`): string => `${n.toLocaleString('en-US')} ${n === 1 ? one : many}`;

/** The toolbar's live count: "12 connections", "3 of 12 connections". */
export function countText(shown: number, total: number): string {
  return shown === total ? plural(total, 'connection') : `${shown.toLocaleString('en-US')} of ${plural(total, 'connection')}`;
}

/** The health cell: "Healthy · 12 ms", "Slow · 1.8 s", "Unreachable", "Not checked yet" (BLUEPRINT §7). */
export function healthCell(row: ConnectionRow): Look {
  const state = healthState(row);
  return healthLook(state, state === 'unreachable' ? null : row.health?.latencyMs ?? null);
}

// ── Credentials ─────────────────────────────────────────────────────────────────────────────────────────────────

export interface CredentialsView {
  readonly icon: string;
  readonly label: string;
  /** Under the label: how short-lived credentials are issued, or where a reference points. */
  readonly caption: string | null;
  readonly captionTitle: string | null;
  /** The caption is a literal (a reference path), set in mono. */
  readonly captionCode: boolean;
  /** A secret of the connection that can't be used now: "Webhook secret unresolved". */
  readonly problems: readonly { readonly label: string; readonly title: string }[];
  /** Nothing is stored for the connection: the cell reads muted. */
  readonly muted: boolean;
}

const STORE_NAMES: Record<string, { readonly short: string; readonly long: string; readonly icon: string }> = {
  vault: { short: 'Vault', long: 'Vault', icon: 'vault' },
  'gcp-sm': { short: 'GCP SM', long: 'GCP Secret Manager', icon: 'cloud' },
  'aws-sm': { short: 'AWS SM', long: 'AWS Secrets Manager', icon: 'cloud' },
};

export function storeName(source: string | null | undefined, long = true): string {
  const s = STORE_NAMES[source ?? ''];
  return s ? (long ? s.long : s.short) : 'an external store';
}

/** "billing-api/webhook-secret" → "Webhook secret"; "aws-stage/secret-access-key" → "Secret access key". */
export function secretLabel(secret: Pick<SecretRow, 'name' | 'field'>): string {
  const base = (secret.name || secret.field).split('/').filter(Boolean).at(-1) ?? 'secret';
  const words = base.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/[-_]+/g, ' ').toLowerCase().trim();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

function withoutScheme(ref: string): string {
  return ref.replace(/^[a-z][a-z0-9+.-]*:\/\//i, '');
}

/**
 * The Credentials cell (kit: "Encrypted here", "Vault reference" + its path, "GCP SM reference", "Short-lived" + how
 * it is issued, a warning for a secret that can't be resolved). The row says where the connection's secrets live;
 * the secrets table (`/api/secrets`, its rows of this connection) and the short-lived credentials the core issued
 * (`/api/secrets/short-lived`) add the details when they answered. No value, length or prefix of a secret ever.
 */
export function credentialsView(
  row: Pick<ConnectionRow, 'name' | 'secretSource'>,
  secrets: readonly SecretRow[] = [],
  shortLived: readonly ShortLivedCredential[] = [],
): CredentialsView {
  const problems = secrets.flatMap((s) => {
    const label = secretLabel(s);
    switch (s.state) {
      case 'not-connected':
        return [{ label: `${label} unresolved`, title: `${s.name} points to ${storeName(s.source)}, which isn't connected` }];
      case 'needs-value':
        return [{ label: `${label} needs a value`, title: `${s.name} can't be read with this master key. Replace it in Secrets.` }];
      case 'changed-outside':
        return [{ label: `${label} changed outside GateRook`, title: `${s.name} was changed outside GateRook. Review it in Secrets.` }];
      default:
        return [];
    }
  });
  const base = { problems, muted: false, caption: null, captionTitle: null, captionCode: false };
  const issued = shortLived.find((c) => c.connection === row.name);
  const dynamic = secrets.find((s) => s.state === 'short-lived');
  if (issued || dynamic || row.secretSource === 'short-lived') {
    return {
      ...base,
      icon: 'timer',
      label: 'Short-lived',
      caption: issued?.mechanism ?? (dynamic ? `${storeName(dynamic.source)} dynamic credentials` : null),
      captionTitle: issued?.identity ?? (dynamic?.ref ? dynamic.ref : null),
    };
  }
  switch (row.secretSource) {
    case 'encrypted':
    case 'local':
      return { ...base, icon: 'lock-keyhole', label: 'Encrypted here' };
    case 'vault':
    case 'gcp-sm':
    case 'aws-sm': {
      const refs = secrets.filter((s) => s.source === row.secretSource && s.ref);
      // One reference: its path under the label (kit `kv/stage/db#password`); more than one would not fit a cell.
      const ref = refs.length === 1 && row.secretSource === 'vault' ? refs[0].ref! : null;
      return {
        ...base,
        icon: STORE_NAMES[row.secretSource].icon,
        label: `${storeName(row.secretSource, false)} reference`,
        caption: ref ? withoutScheme(ref) : null,
        captionTitle: ref,
        captionCode: !!ref,
      };
    }
    case null:
    case undefined:
    case '':
      return { ...base, icon: 'circle-dashed', label: 'Nothing stored here', muted: true };
    default:
      return { ...base, icon: 'key-round', label: row.secretSource };
  }
}

// ── The alert under a row ───────────────────────────────────────────────────────────────────────────────────────

export interface RowAlert {
  readonly kind: 'unreachable' | 'integrity';
  readonly tone: 'danger' | 'warning';
  readonly icon: string;
  readonly title: string;
  readonly body: string | null;
}

/**
 * What the kit's row alert says (`billing-api`: "Connection refused since 11:42" / "Check the stage VPN or the
 * billing service, then test again."): the first sentence of the connector's message is what happened, since when the
 * checks say so (`health.since`, step API; `clock` writes the time), the rest what to do. A connection changed outside
 * GateRook gets its own alert: it stays off until you review it.
 */
export function rowAlert(row: ConnectionRow, clock: ((at: string) => string) | null = null): RowAlert | null {
  if (!row.verified) {
    return {
      kind: 'integrity',
      tone: 'warning',
      icon: 'shield-alert',
      title: 'Changed outside GateRook',
      body: "Agents can't use it until you review it. Keep it only if you made this change.",
    };
  }
  if (healthState(row) !== 'unreachable') return null;
  const { what, todo } = splitMessage(row.health?.message ?? null);
  const since = row.health?.since && clock ? clock(row.health.since) : '';
  return {
    kind: 'unreachable',
    tone: 'danger',
    icon: 'circle-alert',
    title: `${what ?? 'Unreachable'}${since ? ` since ${since}` : ''}`,
    body: todo ?? (what ? null : 'Check the host or the network, then test again.'),
  };
}

export { nextHealth } from '../../core/api/connections.api';

/** "Connection refused. Check the VPN, then test again." → what happened (no final period) and what to do. */
export function splitMessage(message: string | null): { readonly what: string | null; readonly todo: string | null } {
  const text = (message ?? '').trim();
  if (!text) return { what: null, todo: null };
  // A sentence ends at ". " before a capital letter: "db.dev.internal:5432" and "1.8 s" don't end one.
  const m = /^(.+?[.!?])\s+(?=[A-Z])(.+)$/s.exec(text);
  const what = (m ? m[1] : text).replace(/[.]$/, '');
  return { what, todo: m ? m[2].trim() : null };
}

// ── Delete ──────────────────────────────────────────────────────────────────────────────────────────────────────

/** What the delete confirmation lists: everything comes from the core, nothing is assumed. */
export interface DeleteImpact {
  /** The tools that stop working, per endpoint (Off tools aren't listed: they work nowhere already). */
  readonly endpoints: readonly { readonly slug: string; readonly tools: readonly string[]; readonly clients: string | null }[];
  /** Waiting requests on the connection: the core denies them. */
  readonly pending: readonly { readonly tool: string; readonly client: string }[];
  /** "Its encrypted password (version 3) is destroyed." or what happens to references; null when unknown. */
  readonly secrets: string | null;
}

const NUMBERS = ['no', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine'];

/** "Claude Desktop", "Claude Desktop and Codex", "A, B and C". */
export function andList(items: readonly string[]): string {
  if (items.length <= 1) return items[0] ?? '';
  return `${items.slice(0, -1).join(', ')} and ${items.at(-1)}`;
}

/**
 * The secret sentence of the confirmation, from the connection's stored secrets (`GET /api/connections/{name}`):
 * secrets encrypted here are destroyed with it; references are removed, their secrets stay in their store.
 */
export function secretsSentence(secrets: ConnectionDetail['secrets']): string {
  const local = secrets.filter((s) => s.source === 'local' || (!s.source && s.state !== 'reference'));
  const refs = secrets.filter((s) => !local.includes(s));
  const parts: string[] = [];
  if (local.length === 1) {
    const s = local[0];
    const version = s.version != null ? ` (version ${s.version})` : '';
    parts.push(`Its encrypted ${secretLabel({ name: s.name ?? '', field: s.field }).toLowerCase()}${version} is destroyed.`);
  } else if (local.length > 1) {
    parts.push(`Its encrypted ${andList(local.map((s) => secretLabel({ name: s.name ?? '', field: s.field }).toLowerCase()))} are destroyed.`);
  }
  if (refs.length) {
    const stores = andList([...new Set(refs.map((s) => storeName(s.source)))]);
    parts.push(
      refs.length === 1
        ? `The ${storeName(refs[0].source, false)} reference is removed; the secret itself stays in ${stores}.`
        : `Its ${NUMBERS[refs.length] ?? refs.length} references are removed; the secrets stay in ${stores}.`,
    );
  }
  return parts.length ? parts.join(' ') : 'Nothing is stored here.';
}

/**
 * The confirmation's facts (kit `#dlg-delete-connection`): per endpoint the tools that stop working and the clients
 * that use it, the pending requests the core denies, and what happens to the secrets. Inputs the core didn't answer
 * are null: their part is left out, never guessed.
 */
export function deleteImpact(input: {
  readonly row: ConnectionRow;
  readonly detail: ConnectionDetail | null;
  /** The connector's tools in its manifest order (`GET /api/connector-types/{type}`), to list them like the kit. */
  readonly toolOrder: readonly string[] | null;
  readonly endpoints: readonly EndpointSummary[] | null;
  readonly pending: readonly ApprovalItem[] | null;
}): DeleteImpact {
  const { row, detail, toolOrder, endpoints, pending } = input;
  const rank = (tool: string) => {
    const i = toolOrder?.indexOf(tool) ?? -1;
    return i < 0 ? Number.MAX_SAFE_INTEGER : i;
  };
  const slugs = detail ? [...new Set([...row.usedBy, ...detail.usedBy.map((u) => u.endpoint)])] : row.usedBy;
  return {
    endpoints: slugs.map((slug) => {
      const tools = (detail?.usedBy ?? [])
        .filter((u) => u.endpoint === slug && u.mode !== 'OFF')
        .map((u) => u.tool)
        .sort((a, b) => rank(a) - rank(b) || a.localeCompare(b));
      const clients = (endpoints?.find((e) => e.slug === slug)?.clients ?? []).filter((client) => client !== 'claude-ai');
      return { slug, tools: [...new Set(tools)], clients: clients.length ? andList(clients.map(clientName)) : null };
    }),
    pending: (pending ?? [])
      .filter((p) => p.connection === row.name && p.status === 'pending')
      .map((p) => ({ tool: p.tool, client: clientName(p.client ?? p.tokenLabel ?? 'an agent') })),
    secrets: detail ? secretsSentence(detail.secrets) : null,
  };
}

/** The dialog's first sentence (kit "Agents lose it on their next call."). */
export function deleteLead(row: ConnectionRow): string {
  return row.usedBy.length ? 'Agents lose it on their next call.' : 'No endpoint uses it, so no agent loses a tool.';
}

export function pendingTitle(n: number): string {
  return n === 1 ? '1 pending approval is denied' : `${n} pending approvals are denied`;
}

/** The toast after a delete (kit "Connection dev-db deleted" / "Removed from dev."), from what the core did. */
export function deletedToast(name: string, row: ConnectionRow, result: ConnectionDeleted): { title: string; body: string } {
  const parts = [row.usedBy.length ? `Removed from ${andList(row.usedBy)}.` : 'It was on no endpoint.'];
  if (result.rejectedApprovals) parts.push(`${plural(result.rejectedApprovals, 'pending approval')} denied.`);
  if (result.templates.length) parts.push(`${plural(result.templates.length, 'template')} can't run until you pick another connection.`);
  return { title: `Connection ${name} deleted`, body: parts.join(' ') };
}

// ── Toasts of Test connection and Test all ──────────────────────────────────────────────────────────────────────

export interface ToastText {
  readonly tone: ToastTone;
  readonly title: string;
  readonly body: string | null;
}

/** The result of one stored test (kit: "dev-db is healthy" / "Connected in 12 ms."). */
export function testToast(name: string, o: TestOutcome): ToastText {
  if (o.needsConfirmation) {
    return { tone: 'warning', title: `${name} needs your confirmation`, body: o.needsConfirmation.text };
  }
  if (o.ok) return { tone: 'success', title: `${name} is healthy`, body: `Connected in ${duration(o.latencyMs)}.` };
  return {
    tone: 'danger',
    title: `${name} is unreachable`,
    body: o.error?.message ?? 'Check the host or the network, then test again.',
  };
}

/** A test the core refused (409 for a row changed outside GateRook, a type that is off…): its words. */
export function testRefusedToast(name: string, detail: string): ToastText {
  return { tone: 'warning', title: `${name} wasn't tested`, body: detail };
}

/**
 * The end of Test all (kit "12 connections tested" / "10 healthy, 1 slow, 1 unreachable."). `missing` connections
 * didn't report back before the page stopped waiting: the toast says so instead of counting them.
 */
export function testAllToast(results: readonly HealthState[], total: number): ToastText {
  const n = { healthy: 0, degraded: 0, unreachable: 0, unknown: 0 };
  results.forEach((s) => n[s]++);
  const missing = Math.max(0, total - results.length);
  const body = `${n.healthy} healthy, ${n.degraded} slow, ${n.unreachable} unreachable.`;
  if (missing) {
    return {
      tone: 'warning',
      title: `${results.length} of ${plural(total, 'connection')} tested`,
      body: `${body} ${missing === 1 ? "1 didn't" : `${missing} didn't`} answer in time; ${missing === 1 ? 'its' : 'their'} health shows the last check.`,
    };
  }
  return { tone: n.unreachable ? 'warning' : 'success', title: `${plural(total, 'connection')} tested`, body };
}

/** A DOM id from a connection name (names are slugs, but an id must never break on another character). */
export function idOf(prefix: string, name: string): string {
  return `${prefix}-${name.replace(/[^A-Za-z0-9_-]/g, '_')}`;
}
