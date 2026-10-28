import { ApprovalItem } from '../../core/api/approvals.api';
import { ClientsView } from '../../core/api/clients.api';
import { CoreInfo } from '../../core/api/core.api';
import { TokenRow } from '../../core/api/endpoints.api';
import { NotificationChannel, NotificationsSent } from '../../core/api/notifications.api';
import { BackupCopy, AuditExportDestination } from '../../core/api/settings.api';
import { TunnelProviderId, TunnelState } from '../../core/api/tunnel.api';
import { Env, Iso, JsonObject, JsonValue } from '../../core/api/types';
import { UpdateState } from '../../core/desktop/desktop-bridge';
import { downloadPercent } from '../../core/stores/updates.store';
import { clockTime, relativeTime, shortDate } from '../../core/time/time-format';
import { clientName } from '../../kit/elements/shared/client-mark';
import { ToastOptions } from '../../kit/toast.service';

/**
 * The words and small computations of Settings (design/pages/settings.html), as pure functions of the core's answers
 * and "now": what each section says, which toast a change gives, what a form accepts. The page and its tests share
 * them, so the wording is checked once against the kit.
 */

/** A run of text, some of it a literal (`gw-mono`). */
export interface TextPart {
  readonly text: string;
  readonly mono?: boolean;
}

const t = (text: string): TextPart => ({ text });
const m = (text: string): TextPart => ({ text, mono: true });

const DAY = 24 * 60 * 60 * 1000;

export function plural(n: number, one: string, many = `${one}s`): string {
  return `${count(n)} ${n === 1 ? one : many}`;
}

/** Numbers with thousands separators: "1,284". */
export function count(n: number): string {
  return n.toLocaleString('en-US');
}

/** "A, B and C" (the kit lists things this way, without a serial comma). */
export function listText(items: readonly string[]): string {
  if (items.length <= 1) return items[0] ?? '';
  return `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`;
}

// ── Reading a section's values ──────────────────────────────────────────────────────────────────────────────────

/** A section's values as the core reads them (schema defaults overlaid with what was stored). */
export type Values = JsonObject | null | undefined;

export function boolOf(values: Values, key: string, dflt: boolean): boolean {
  const v = values?.[key];
  return typeof v === 'boolean' ? v : dflt;
}

export function numOf(values: Values, key: string, dflt: number): number {
  const v = values?.[key];
  return typeof v === 'number' && Number.isFinite(v) ? v : dflt;
}

export function strOf(values: Values, key: string, dflt: string): string {
  const v = values?.[key];
  return typeof v === 'string' ? v : dflt;
}

export function stringsOf(values: Values, key: string): string[] {
  const v = values?.[key];
  return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [];
}

function objectOf(v: JsonValue | undefined): JsonObject | null {
  return v && typeof v === 'object' && !Array.isArray(v) ? v : null;
}

// ── Applies at once: the toast names the setting and its new value (kit page script) ───────────────────────────

export const onOff = (on: boolean): string => (on ? 'on' : 'off');

export function savedToast(label: string, value: string): ToastOptions {
  return { tone: 'success', title: 'Saved', body: `${label}: ${value}.` };
}

/** A Save form: "<Topic> saved" (the kit takes the form's legend). */
export function formSavedToast(topic: string, body: string | null = null): ToastOptions {
  return { tone: 'success', title: `${topic} saved`, body };
}

// ── General › Core ──────────────────────────────────────────────────────────────────────────────────────────────

export interface KvRow {
  readonly key: string;
  readonly value: readonly TextPart[];
}

/**
 * What runs the core and its plugins (ARCHITECTURE.md §8.6.1: the kit's "Java runtime: Bundled JRE 25" became the
 * native core plus the plugin runtime, a JRE started only for Java plugins).
 */
export function runtimeRows(info: CoreInfo | null): KvRow[] {
  if (!info) return [];
  const core: TextPart[] =
    info.runtime === 'native' ? [t('Native core')] : [t('Java VM, a development build · '), m(info.vm)];
  const p = info.pluginRuntime;
  const major = p.version ? p.version.split(/[.+-]/)[0] : null;
  const plugins: TextPart[] = !p.present
    ? [t('Not found: Java plugins can’t start')]
    : p.bundledJre
      ? [t(major ? `Bundled JRE ${major}` : 'Bundled JRE'), t(', started only for Java plugins')]
      : [t(major ? `JRE ${major} at ` : 'JRE at '), m(p.home)];
  return [
    { key: 'Core', value: core },
    { key: 'Plugin runtime', value: plugins },
  ];
}

/** The data folder (`~/.gaterook/` in the kit): the folder of the gateway's database file. */
export function folderOf(path: string | null | undefined): string | null {
  if (!path) return null;
  const i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
  return i > 0 ? path.slice(0, i + 1) : path;
}

export function fileOf(path: string): string {
  const i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
  return i >= 0 ? path.slice(i + 1) : path;
}

/** "http://127.0.0.1:7465/mcp/dev": an endpoint URL at a port. */
export function endpointUrl(port: number, slug: string): string {
  return `http://127.0.0.1:${port}/mcp/${slug}`;
}

/**
 * The Change-port dialog's "Endpoint URLs": the first in full, the rest as paths ("http://127.0.0.1:7475/mcp/dev,
 * /mcp/stage-readonly and /mcp/incident-prod"). Without a port yet, the URLs with `<port>`.
 */
export function endpointUrlsAt(port: number | null, slugs: readonly string[]): TextPart[] {
  if (!slugs.length) return [t('None yet: create an endpoint and its URL uses the new port')];
  const out: TextPart[] = [m(port ? endpointUrl(port, slugs[0]) : `http://127.0.0.1:<port>/mcp/${slugs[0]}`)];
  slugs.slice(1).forEach((s, i, rest) => {
    out.push(t(i === rest.length - 1 ? ' and ' : ', '));
    out.push(m(`/mcp/${s}`));
  });
  return out;
}

export type ParseResult<T> = { readonly value: T; readonly error: null } | { readonly value: null; readonly error: string };

/** The New port field: 1024–65535, not the one in use, not the tunnel's (the core checks again, and that it is free). */
export function parsePort(raw: string, current: number | null, publicPort: number | null): ParseResult<number> {
  const s = raw.trim();
  if (!/^[0-9]{1,5}$/.test(s)) return { value: null, error: 'Enter a port from 1024 to 65535.' };
  const n = Number(s);
  if (n < 1024 || n > 65535) return { value: null, error: 'Use a port from 1024 to 65535.' };
  if (n === current) return { value: null, error: `The core already listens on ${n}. Enter another port.` };
  if (n === publicPort) return { value: null, error: `${n} is reserved by GateRook. Choose another one.` };
  return { value: n, error: null };
}

/**
 * Who has to be connected again after a port change: the entries whose config holds the URL (Codex's headers
 * helper, a token or a browser sign-in in Cursor or Antigravity). The Claude Desktop bridge asks the app for the
 * address each time (§7.2), so it keeps working. The kit says "Claude Desktop and Codex"; the bridge made the first
 * half untrue.
 */
export function portClients(view: ClientsView | null): { readonly again: string[]; readonly keep: string[] } {
  const again = new Set<string>();
  const keep = new Set<string>();
  for (const c of view?.clients ?? []) {
    for (const l of c.links) {
      if (l.method === 'mcpb' || l.method === 'config-bridge') keep.add(c.name || clientName(c.client));
      else again.add(c.name || clientName(c.client));
    }
  }
  for (const k of again) keep.delete(k);
  return { again: [...again], keep: [...keep] };
}

// ── General › Updates (Electron's electron-updater, §7.1) ────────────────────────────────────────────────────────

export interface UpdateView {
  /** `gw-status--<tone>`; `checking` shows a spinner instead of the dot. */
  readonly tone: 'success' | 'info' | 'warning' | 'danger' | 'neutral' | 'checking';
  readonly label: string;
  /** The version after the label, mono. */
  readonly version: string | null;
  /** When it was last checked, for "Last checked 2 h ago". */
  readonly lastCheckedAt: Iso | null;
  readonly busy: boolean;
  readonly error: string | null;
}

export function updateView(state: UpdateState | null, version: string | null, desktop: boolean): UpdateView {
  const base = { lastCheckedAt: state?.lastCheckedAt ?? null, busy: false, error: null };
  if (!desktop || !state) return { ...base, tone: 'neutral', label: 'Version', version };
  switch (state.state) {
    case 'checking':
      return { ...base, tone: 'checking', label: 'Checking for updates', version: null, busy: true };
    case 'up-to-date':
      return { ...base, tone: 'success', label: 'Up to date · version', version: state.version ?? version };
    case 'available':
      return { ...base, tone: 'info', label: 'Update available · version', version: state.version ?? null };
    case 'downloading': {
      const percent = downloadPercent(state.percent);
      return { ...base, tone: 'checking', label: percent === null ? 'Downloading update' : `Downloading update · ${percent}%`, version: null, busy: true };
    }
    case 'downloaded':
      return { ...base, tone: 'info', label: 'Ready to install · version', version: state.version ?? null };
    case 'installing':
      return { ...base, tone: 'checking', label: 'Restarting to install update', version: null, busy: true };
    case 'error':
      return { ...base, tone: 'danger', label: state.version ? 'Couldn’t install update' : 'Couldn’t check for updates', version: null, error: state.message };
  }
}

/** The toast once "Check now" has an answer (kit: "You're on the latest version · GateRook 0.4.0"). */
export function updateToast(state: UpdateState, version: string | null): ToastOptions | null {
  switch (state.state) {
    case 'up-to-date':
      return { tone: 'success', title: 'You’re on the latest version', body: `GateRook ${state.version ?? version ?? ''}`.trim() };
    case 'available':
      return { tone: 'info', title: `Version ${state.version ?? ''} is available`.replace('  ', ' '), body: 'Choose Update and restart in the sidebar to download and install it.' };
    case 'downloaded':
      return { tone: 'info', title: `Version ${state.version ?? ''} is ready`.replace('  ', ' '), body: 'Choose Restart to update in the sidebar to install it.' };
    case 'error':
      return { tone: 'danger', title: state.version ? 'Couldn’t install update' : 'Couldn’t check for updates', body: state.message };
    default:
      return null;
  }
}

// ── Authorization ───────────────────────────────────────────────────────────────────────────────────────────────

export { TOKEN_TTL_DAYS } from '../../core/api/token-lifetime';

export function daysText(d: number): string {
  if (d === 184) return '6 months (184 days)';
  return d === 1 ? '1 day' : `${count(d)} days`;
}

/** Client registration: the core keeps `cimd` or `cimd+dcr` (CIMD always works; DCR is added on top). */
export type Registration = 'cimd' | 'cimd+dcr';

export function registrationOf(values: Values): Registration {
  return strOf(values, 'clientRegistration', 'cimd') === 'cimd+dcr' ? 'cimd+dcr' : 'cimd';
}

export interface ExpiringToken {
  readonly endpoint: string;
  readonly name: string;
  readonly expiresAt: Iso;
}

/** Named tokens that are active and run out within 7 days (the kit's "inspector-debug … expires in 3 days"). */
export function expiringTokens(tokens: ReadonlyMap<string, readonly TokenRow[]>, now: number): ExpiringToken[] {
  const out: ExpiringToken[] = [];
  for (const [endpoint, rows] of tokens) {
    for (const r of rows) {
      if (r.kind.toLowerCase() !== 'named' || r.state !== 'active' || !r.expiresAt) continue;
      const left = Date.parse(r.expiresAt) - now;
      if (left > 0 && left <= 7 * DAY) out.push({ endpoint, name: r.name, expiresAt: r.expiresAt });
    }
  }
  return out.sort((a, b) => Date.parse(a.expiresAt) - Date.parse(b.expiresAt));
}

/** "expires in 3 days", "expires tomorrow", "expires in 5 h". */
export function expiresText(at: Iso, now: number): string {
  const rel = relativeTime(at, now);
  return rel === 'tomorrow' ? 'expires tomorrow' : `expires ${rel}`;
}

// ── Approval rules ──────────────────────────────────────────────────────────────────────────────────────────────

export type DecisionsFrom = 'app' | 'app-and-linked';

export interface EnvRuleRow {
  readonly env: Env;
  readonly timeout: string;
  readonly comment: string;
  readonly channels: readonly string[];
}

const ENVS: readonly Env[] = ['dev', 'stage', 'prod'];

/** The table "Per environment" from the section's `perEnvironment` (the policies' `approvals`). */
export function envRuleRows(perEnvironment: JsonValue | undefined): EnvRuleRow[] {
  const all = objectOf(perEnvironment);
  return ENVS.map((env) => {
    const row = objectOf(all?.[env]);
    const minutes = row && typeof row['timeoutMinutes'] === 'number' ? row['timeoutMinutes'] : null;
    const comment = row && typeof row['comment'] === 'string' ? row['comment'] : 'optional';
    const channels = row && Array.isArray(row['announceIn']) ? row['announceIn'].filter((c): c is string => typeof c === 'string') : [];
    return {
      env,
      timeout: minutes === null ? 'Not set' : `${count(minutes)} min`,
      comment: comment.charAt(0).toUpperCase() + comment.slice(1),
      channels,
    };
  });
}

/** One channel as the note under the table names it ("desktop notifications", "Slack #deploy-approvals"). */
function channelParts(kind: string, slackChannel: string | null): TextPart[] {
  switch (kind) {
    case 'desktop':
      return [t('desktop notifications')];
    case 'slack':
      return slackChannel ? [t('Slack '), m(slackChannel)] : [t('Slack')];
    case 'telegram':
      return [t('Telegram')];
    default:
      return [t(kind)];
  }
}

function channelsParts(kinds: readonly string[], slackChannel: string | null): TextPart[] {
  const out: TextPart[] = [];
  kinds.forEach((k, i) => {
    if (i > 0) out.push(t(i === kinds.length - 1 ? ' and ' : ', '));
    out.push(...channelParts(k, slackChannel));
  });
  return out;
}

/**
 * The note under the table: where requests are announced ("All three announce requests in desktop notifications and
 * Slack #deploy-approvals.") and how many approvals are enough.
 */
export function announceParts(rows: readonly EnvRuleRow[], slackChannel: string | null): TextPart[] {
  const key = (r: EnvRuleRow) => r.channels.join(',');
  const same = rows.every((r) => key(r) === key(rows[0]));
  const out: TextPart[] = [];
  if (same && rows.length) {
    const kinds = rows[0].channels;
    out.push(kinds.length ? t('All three announce requests in ') : t('None of them announces requests outside this window'));
    out.push(...channelsParts(kinds, slackChannel));
    out.push(t('. '));
  } else {
    rows.forEach((r, i) => {
      out.push(t(`${i === 0 ? '' : '; '}${r.env} announces requests in `));
      if (r.channels.length) out.push(...channelsParts(r.channels, slackChannel));
      else out.push(t('this window only'));
    });
    out.push(t('. '));
  }
  out.push(t('One approval is enough everywhere: on this desktop, yours. Named approvers and two-person approval are server mode only.'));
  return out;
}

// ── Local API › Allowed origins ─────────────────────────────────────────────────────────────────────────────────

/** Scheme, host, port; no path (the core's `SettingsService.ORIGIN`, so the page refuses what the core would). */
const ORIGIN = /^https?:\/\/(\[[0-9a-f:]+\]|[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*)(:([0-9]{1,5}))?$/;
export const MAX_ORIGINS = 20;
const ORIGIN_HELP = 'Enter a scheme, host and port, for example http://localhost:3000. Paths aren’t allowed.';

export function parseOrigin(raw: string, existing: readonly string[]): ParseResult<string> {
  // Scheme and host compare without case, and a trailing slash is not a path.
  const v = raw.trim().replace(/\/$/, '').toLowerCase();
  const match = ORIGIN.exec(v);
  if (!match || v.length > 253) return { value: null, error: ORIGIN_HELP };
  const port = match[6];
  if (port !== undefined && (Number(port) < 1 || Number(port) > 65535)) return { value: null, error: ORIGIN_HELP };
  if (existing.includes(v)) return { value: null, error: `${v} is already allowed.` };
  if (existing.length >= MAX_ORIGINS) return { value: null, error: `At most ${MAX_ORIGINS} origins can be allowed. Remove one first.` };
  return { value: v, error: null };
}

export function sameList(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((x, i) => x === b[i]);
}

// ── Audit & retention ───────────────────────────────────────────────────────────────────────────────────────────

export const RETENTION_YEARS = [1, 2, 3, 5] as const;

export function yearsText(y: number): string {
  return y === 1 ? '1 year' : `${count(y)} years`;
}

/** Two significant digits: 468,660 → "470,000", 3,285 → "3,300". */
export function roughly(n: number): string {
  if (n < 100) return count(Math.round(n));
  const magnitude = 10 ** (Math.floor(Math.log10(n)) - 1);
  return count(Math.round(n / magnitude) * magnitude);
}

/** The hint under "Keep records for": the floor, and how many records the period holds at today's rate. */
export function retentionHint(calls24h: number | null, years: number): string {
  const floor = 'A year at least, so the audit always covers the last twelve months.';
  if (calls24h === null || calls24h <= 0) return floor;
  const period = years === 1 ? 'a year holds' : `${yearsText(years)} hold`;
  return `${floor} At today's rate, ${plural(calls24h, 'call')} in the last 24 hours, ${period} about ${roughly(calls24h * 365 * years)} records.`;
}

export interface ExportCard {
  readonly id: string;
  readonly title: string;
  readonly target: string;
  readonly tone: 'success' | 'danger' | 'warning' | 'neutral';
  /** "Streaming · last event", "Daily at 02:00 · last run", "Hourly · failing since 12:00 · last try". */
  readonly label: string;
  readonly at: Iso | null;
  readonly failing: boolean;
  /** Why the core won't run it (Retry, Export now), or null: changed outside GateRook, or a demo destination. */
  readonly blocked: string | null;
  readonly error: string | null;
}

/** Why the core refuses to run a destination (409), or null when it runs it. */
export function exportBlocked(d: AuditExportDestination): string | null {
  if (d.demo === true) return 'A destination of the demo world: shown, never called.';
  if (d.verified === false) return 'Changed outside GateRook: it sends nothing until it is saved again.';
  return null;
}

const EXPORT_TITLES: Record<string, string> = { siem: 'SIEM · syslog', s3: 'Amazon S3', bigquery: 'BigQuery' };

/** Where a destination writes: its host, bucket path or table, from its config. */
function exportTarget(d: AuditExportDestination): string {
  const c = d.config;
  const s = (k: string) => (typeof c[k] === 'string' ? (c[k] as string) : null);
  switch (d.kind) {
    case 'siem':
      return s('host') ? `${s('host')}${typeof c['port'] === 'number' ? `:${c['port']}` : ''}` : d.name;
    case 's3':
      return s('bucket') ? `s3://${s('bucket')}/${s('prefix') ?? ''}` : d.name;
    case 'bigquery':
      return s('table') ?? ([s('project'), s('dataset'), s('tableId')].filter(Boolean).join('.') || d.name);
  }
}

function scheduleText(schedule: string): string {
  if (schedule === 'stream') return 'Streaming';
  if (schedule === 'hourly') return 'Hourly';
  const daily = /^daily@([0-9]{2}:[0-9]{2})$/.exec(schedule);
  return daily ? `Daily at ${daily[1]}` : schedule;
}

export function exportCard(d: AuditExportDestination): ExportCard {
  const failing = d.failingSince !== null || d.state === 'failing';
  const sched = scheduleText(d.schedule);
  const last = d.schedule === 'stream' ? 'last event' : 'last run';
  const label = !d.enabled
    ? `${sched} · paused`
    : failing
      ? `${sched} · failing${d.failingSince ? ` since ${clockTime(d.failingSince, false)}` : ''} · last try`
      : `${sched} · ${last}`;
  return {
    id: d.id,
    title: EXPORT_TITLES[d.kind] ?? d.name,
    // The core's own name for the place (`host:6514` with the default port), else one read from the config.
    target: d.target || exportTarget(d),
    tone: !d.enabled ? 'neutral' : failing ? 'danger' : 'success',
    label,
    at: d.enabled ? d.lastAttemptAt : null,
    failing: d.enabled && failing,
    blocked: exportBlocked(d),
    error: d.lastError,
  };
}

// ── Backup ──────────────────────────────────────────────────────────────────────────────────────────────────────

export function dailyHint(at: string, keep: number): string {
  return `At ${at}, or at the next start if the computer was off. The last ${count(keep)} copies are kept.`;
}

/** "Today at 03:00", "Yesterday at 03:00", "Oct 20 at 03:00". */
export function dayAt(at: Iso, now: number): string {
  const day = shortDate(at, now);
  const yesterday = shortDate(now - DAY, now);
  const word = day === yesterday && day !== 'Today' ? 'Yesterday' : day;
  return `${word} at ${clockTime(at, false)}`;
}

export function dbBackedUpToast(copy: BackupCopy): ToastOptions {
  const folder = folderOf(copy.path);
  return { tone: 'success', title: 'Database backed up', body: folder ? `Saved as ${copy.file} in ${folder}.` : `Saved as ${copy.file}.` };
}

/** "8 secrets stored here · 3 secret references don't need it". */
export function protectsText(protects: number, references: number | null): string {
  const stored = protects === 0 ? 'No secrets stored here yet' : `${plural(protects, 'secret')} stored here`;
  if (!references) return stored;
  return `${stored} · ${plural(references, 'secret reference')} ${references === 1 ? 'doesn’t' : 'don’t'} need it`;
}

export function keyBackupLead(protects: number): string {
  return protects > 0
    ? `Without a copy, the ${plural(protects, 'secret')} stored here can't be recovered if this computer is lost.`
    : "Without a copy, the secrets you store here later can't be recovered if this computer is lost.";
}

/**
 * The name the save dialog suggests: Electron main's (`desktop/src/main/file-purposes.ts`,
 * `gaterook-master-key-<UTC date>.json`), so this dialog names the file the system dialog proposes.
 */
export function keyBackupFileName(now: number): string {
  return `gaterook-master-key-${new Date(now).toISOString().slice(0, 10)}.json`;
}

export function keyBackedUpToast(result: { readonly path: string; readonly passphrase: boolean; readonly warnings: readonly string[] }): ToastOptions {
  const synced = result.warnings.includes('synced-folder');
  return {
    tone: synced ? 'warning' : 'success',
    title: `Master key backed up${result.passphrase ? ', encrypted with your passphrase' : ''}`,
    body:
      `Saved to ${result.path}. ` +
      (synced
        ? 'That folder syncs to other devices: move the copy to your password manager or an encrypted drive, then delete it there.'
        : 'Move it to your password manager or an encrypted drive, then delete it from this computer.'),
    timeout: 0,
  };
}

// ── Notifications ───────────────────────────────────────────────────────────────────────────────────────────────

export function channelOf(channels: readonly NotificationChannel[] | null | undefined, kind: string): NotificationChannel | null {
  return channels?.find((c) => c.kind === kind) ?? null;
}

export function configText(c: NotificationChannel | null, key: string): string | null {
  const v = c?.config[key];
  return typeof v === 'string' && v ? v : null;
}

export function configBool(c: NotificationChannel | null, key: string, dflt: boolean): boolean {
  const v = c?.config[key];
  return typeof v === 'boolean' ? v : dflt;
}

/** A chat channel is set up once it is on and the core reports it connected (or it has a delivery). */
export function chatSetUp(c: NotificationChannel | null): boolean {
  return !!c && c.enabled && c.state !== 'not-set-up' && c.state !== null;
}

/** The Slack workspace the app was connected to (`config.workspace.team`, K2), "acme". */
export function slackWorkspace(c: NotificationChannel | null): string | null {
  const w = c?.config['workspace'];
  if (typeof w === 'string') return w || null;
  if (w && typeof w === 'object' && !Array.isArray(w)) {
    const team = (w as JsonObject)['team'];
    return typeof team === 'string' && team ? team : null;
  }
  return null;
}

/** The Telegram bot the token belongs to (`config.bot.username`, K2), "@acme_gateway_bot". */
export function telegramBot(c: NotificationChannel | null): string | null {
  const b = c?.config['bot'];
  if (!b || typeof b !== 'object' || Array.isArray(b)) return null;
  const name = (b as JsonObject)['username'];
  return typeof name === 'string' && name ? `@${name.replace(/^@/, '')}` : null;
}

/** Slack's "#deploy-approvals" (the name from its config, with the `#`). */
export function slackChannelName(c: NotificationChannel | null): string | null {
  const name = configText(c, 'channelName') ?? configText(c, 'channel');
  return name ? (name.startsWith('#') ? name : `#${name}`) : null;
}

/**
 * The toast of "Send test notification", from what the core says each channel did (`POST /api/notifications/test`):
 * where it went (kit: "Sent to the desktop and to #deploy-approvals in Slack. Telegram is not set up."), and a
 * failure with the channel's own words — never "sent" for a message that didn't go.
 */
export function testSentToast(sent: NotificationsSent, channels: readonly NotificationChannel[]): ToastOptions {
  const slack = channelOf(channels, 'slack');
  const where = (kind: string): string => {
    if (kind === 'desktop') return 'the desktop';
    if (kind === 'slack') {
      const name = configText(slack, 'deliverTo') === 'channel' ? slackChannelName(slack) : null;
      return name ? `${name} in Slack` : 'Slack';
    }
    return kind === 'telegram' ? 'Telegram' : kind;
  };
  const name = (kind: string): string =>
    kind === 'desktop' ? 'Desktop notifications' : kind === 'slack' ? 'Slack' : kind === 'telegram' ? 'Telegram' : kind;
  const ok = sent.channels.filter((c) => c.state === 'sent').map((c) => where(c.channel));
  const failed = sent.channels.filter((c) => c.state === 'failed');
  const off = sent.channels.filter((c) => c.state === 'off').map((c) => c.channel as string);
  // A chat that is off: never set up, or set up but not connected to its service right now.
  const chatsOff = off.filter((k) => k !== 'desktop');
  const notSetUp = chatsOff.filter((k) => !chatSetUp(channelOf(channels, k))).map(name);
  const notConnected = chatsOff.filter((k) => chatSetUp(channelOf(channels, k))).map(name);
  const offText = [
    off.includes('desktop') ? 'Desktop notifications are off.' : null,
    notConnected.length ? `${listText(notConnected)} ${notConnected.length === 1 ? 'isn’t' : 'aren’t'} connected right now.` : null,
    notSetUp.length ? `${listText(notSetUp)} ${notSetUp.length === 1 ? 'is' : 'are'} not set up.` : null,
  ];
  const failures = failed.map((c) => `${name(c.channel)} failed${c.detail ? `: ${c.detail.replace(/\.$/, '')}` : ''}.`);
  const body = [ok.length ? `Sent to ${listText(ok)}.` : 'Nothing was sent.', ...failures, ...offText].filter(Boolean).join(' ');
  if (failed.length) return { tone: 'danger', title: 'The test notification didn’t reach every channel', body };
  if (!ok.length) return { tone: 'warning', title: 'No test notification sent', body };
  return { tone: 'success', title: 'Test notification sent', body };
}

export interface ChannelStatus {
  readonly tone: 'success' | 'danger' | 'warning' | 'neutral';
  readonly label: string;
}

/**
 * A chat channel's status line: "Connected", a failure with the core's words, or "Not set up". The K2 contract names
 * no fixed states, so anything that is not set up, connected or failing shows as the core describes it.
 */
export function channelStatus(c: NotificationChannel | null): ChannelStatus {
  if (!c || !chatSetUp(c)) return { tone: 'neutral', label: 'Not set up' };
  const state = c.state ?? '';
  if (/^(error|failing|failed|auth-failed|unauthorized|revoked)$/.test(state)) return { tone: 'danger', label: c.stateDetail ?? 'Not delivering' };
  if (/^(connected|ok|ready|on)$/.test(state)) return { tone: 'success', label: 'Connected' };
  return { tone: 'warning', label: c.stateDetail ?? state.charAt(0).toUpperCase() + state.slice(1).replace(/-/g, ' ') };
}

/** How one channel is named in the disconnect dialog ("desktop notifications", "Telegram"). */
function channelName(kind: string): string {
  return kind === 'desktop' ? 'desktop notifications' : kind === 'telegram' ? 'Telegram' : kind === 'slack' ? 'Slack' : kind;
}

/** "Still notified by" once Slack is gone: the other channels that are on. */
export function stillNotifiedBy(desktopOn: boolean, telegramUp: boolean): string {
  const left = [desktopOn ? 'Desktop notifications' : null, telegramUp ? 'Telegram' : null].filter((x): x is string => !!x);
  return left.length ? listText(left) : 'Nothing outside this window: requests wait in Approvals';
}

/** "Policies" once Slack is gone: "dev, stage and prod announce in desktop notifications only". */
export function policiesAfterSlack(rows: readonly EnvRuleRow[]): string {
  const left = rows.map((r) => ({ env: r.env, kinds: r.channels.filter((c) => c !== 'slack') }));
  const say = (kinds: readonly string[]) => (kinds.length ? `${listText(kinds.map(channelName))}${kinds.length === 1 ? ' only' : ''}` : 'this window only');
  const key = (k: readonly string[]) => k.join(',');
  if (left.length && left.every((l) => key(l.kinds) === key(left[0].kinds))) {
    return `${listText(left.map((l) => l.env))} announce in ${say(left[0].kinds)}`;
  }
  return left.map((l) => `${l.env} announces in ${say(l.kinds)}`).join('; ');
}

/** The fact after the rule in a chat preview ("estimated rows 1"): the request's first impact line. */
export function ruleNoteOf(impact: readonly { readonly label: string; readonly text: string }[]): string | null {
  const first = impact[0];
  return first ? `${first.label.charAt(0).toLowerCase()}${first.label.slice(1)} ${first.text}` : null;
}

/**
 * Which request "Preview messages" shows: a waiting one that won't expire while the dialog is open (the kit shows the
 * pending `sql_execute`), else the first waiting one, else none.
 */
export function previewRequest(pending: readonly ApprovalItem[], now: number): ApprovalItem | null {
  const live = pending.filter((p) => p.status === 'pending');
  return live.find((p) => Date.parse(p.expiresAt) - now > 60_000) ?? live[0] ?? null;
}

// ── Tunnel ──────────────────────────────────────────────────────────────────────────────────────────────────────

/** The kit's two provider cards: Cloudflare (quick or named tunnel) and ngrok. */
export type ProviderCard = 'cloudflare' | 'ngrok';

export function providerCard(p: TunnelProviderId | null | undefined): ProviderCard {
  return p === 'ngrok' ? 'ngrok' : 'cloudflare';
}

export function providerName(p: TunnelProviderId | null | undefined): string {
  return p === 'ngrok' ? 'ngrok' : 'Cloudflare Tunnel';
}

/** The provider a card saves: Cloudflare keeps a named tunnel that is already set up. */
export function providerOf(card: ProviderCard, current: TunnelProviderId | null | undefined): TunnelProviderId {
  if (card === 'ngrok') return 'ngrok';
  return current === 'cloudflare-named' ? 'cloudflare-named' : 'cloudflare-quick';
}

export interface TunnelLook {
  readonly tone: 'neutral' | 'warning' | 'danger' | 'checking';
  readonly text: string;
}

export function tunnelLook(t: TunnelState): TunnelLook {
  switch (t.state) {
    case 'on':
      return { tone: 'warning', text: `Public${t.endpoint ? ` · ${t.endpoint}` : ''} · ${providerName(t.provider)}` };
    case 'starting':
      return { tone: 'checking', text: 'Starting the tunnel' };
    case 'error':
      return { tone: 'danger', text: 'The tunnel didn’t start' };
    default:
      return { tone: 'neutral', text: t.since ? `Off since ${clockTime(t.since, false)}` : 'Off' };
  }
}

/** "Public 08:40 to 12:10, turned on and off by you": today's periods of the tunnel. */
export function todayText(t: TunnelState, now: number): string | null {
  const today = t.history.filter((h) => shortDate(h.from, now) === 'Today');
  if (!today.length) return null;
  const spans = today.map((h) => (h.to ? `${clockTime(h.from, false)} to ${clockTime(h.to, false)}` : `since ${clockTime(h.from, false)}`));
  const ended = today.every((h) => h.to);
  return `Public ${listText(spans)}, turned on${ended ? ' and off' : ''} by you`;
}

/** Calls through the tunnel while it was public today (the history counts them per period). */
export function tunnelCalls(t: TunnelState, now: number): number | null {
  const today = t.history.filter((h) => shortDate(h.from, now) === 'Today');
  return today.length ? today.reduce((n, h) => n + h.calls, 0) : null;
}
