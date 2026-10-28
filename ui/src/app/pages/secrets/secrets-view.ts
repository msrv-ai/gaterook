import {
  MasterKeyRotation,
  MasterKeyStatus,
  SecretRefTest,
  SecretRow,
  SecretStore,
  SecretStoreDisconnected,
  SecretStoreKind,
  SecretStoreTest,
  ShortLivedCredential,
} from '../../core/api/secrets.api';
import { Env, JsonObject, JsonValue } from '../../core/api/types';
import { duration, relativeTime, shortDate } from '../../core/time/time-format';

/**
 * The Secrets page's wording and view models (design/pages/secrets.html), as pure functions of the core's answers
 * (ARCHITECTURE.md §6 secrets) and "now", so every sentence the page shows is tested without a browser or a clock.
 */

export type Tone = 'success' | 'warning' | 'danger' | 'info' | 'neutral';

/** A toast the page shows after an action (tone, title, body). */
export interface ToastText {
  readonly tone: 'success' | 'warning' | 'danger' | 'info';
  readonly title: string;
  readonly body: string | null;
  /** 0: stays until dismissed (the backup's location is something to act on). */
  readonly timeout?: number;
}

export function plural(n: number, word: string, many = `${word}s`): string {
  return `${n.toLocaleString('en-US')} ${n === 1 ? word : many}`;
}

/** "a", "a and b", "a, b and c". */
export function joinAnd(items: readonly string[]): string {
  if (items.length <= 1) return items[0] ?? '';
  return `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`;
}

/** An id-safe fragment of a name (`dev-db/password` → `dev-db-password`). */
export function idOf(prefix: string, name: string): string {
  return `${prefix}-${name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')}`;
}

// ── Stores: what each kind is called and looks like ──────────────────────────────────────────────────────────────

export interface StoreKindInfo {
  readonly kind: SecretStoreKind;
  /** The card's title ("HashiCorp Vault"). */
  readonly title: string;
  /** The Source column's short name ("Vault", "GCP SM"). */
  readonly short: string;
  readonly icon: string;
  /** What the dashed "not connected" card says the store does. */
  readonly pitch: string;
  /** The URI scheme of its references. */
  readonly scheme: string;
}

/** The three kinds, in the kit's order (External stores: Vault, GCP SM, AWS SM). */
export const STORE_KINDS: readonly StoreKindInfo[] = [
  {
    kind: 'vault',
    title: 'HashiCorp Vault',
    short: 'Vault',
    icon: 'vault',
    pitch: 'The gateway signs in with AppRole or a token and reads references at call time. Vault can also issue SSH certificates and database users.',
    scheme: 'vault://',
  },
  {
    kind: 'gcp-sm',
    title: 'GCP Secret Manager',
    short: 'GCP SM',
    icon: 'cloud',
    pitch: 'The gateway reads references with your gcloud credentials, optionally as a read-only service account. No key file is needed.',
    scheme: 'gcp-sm://',
  },
  {
    kind: 'aws-sm',
    title: 'AWS Secrets Manager',
    short: 'AWS SM',
    icon: 'cloud',
    pitch: 'The gateway assumes a read-only role in your account. No static AWS keys go into references.',
    scheme: 'aws-sm://',
  },
];

export function storeKind(kind: string | null | undefined): StoreKindInfo | null {
  return STORE_KINDS.find((k) => k.kind === kind) ?? null;
}

/** The store kind a reference URI points into (`vault://…` → vault), or null. */
export function kindOfRef(uri: string | null | undefined): SecretStoreKind | null {
  if (!uri) return null;
  return STORE_KINDS.find((k) => uri.startsWith(k.scheme))?.kind ?? null;
}

/**
 * The in-page anchor of each store card: `store-<kind>` for the first store of a kind (the kit's `#store-aws`
 * links), `store-<kind>-<name>` for more of the same kind, and `store-<kind>` for the dashed card of a kind that has
 * none yet.
 */
export function storeAnchors(stores: readonly SecretStore[]): Map<string, string> {
  const anchors = new Map<string, string>();
  const seen = new Set<string>();
  for (const s of stores) {
    const base = `store-${anchorKind(s.kind)}`;
    anchors.set(s.name, seen.has(s.kind) ? idOf(base, s.name) : base);
    seen.add(s.kind);
  }
  return anchors;
}

/** The kit's short anchors: `#store-vault`, `#store-gcp`, `#store-aws`. */
export function anchorKind(kind: string): string {
  return kind.replace(/-sm$/, '');
}

// ── Stored secrets: filters, sort, rows ──────────────────────────────────────────────────────────────────────────

export type SourceFilter = '' | 'local' | SecretStoreKind;

/** The Source select (kit: All sources · Encrypted here · Vault · GCP SM · AWS SM). */
export const SOURCE_OPTIONS: readonly { readonly value: SourceFilter; readonly label: string }[] = [
  { value: '', label: 'All sources' },
  { value: 'local', label: 'Encrypted here' },
  { value: 'vault', label: 'Vault' },
  { value: 'gcp-sm', label: 'GCP SM' },
  { value: 'aws-sm', label: 'AWS SM' },
];

export interface Filters {
  readonly q: string;
  readonly source: SourceFilter;
}

export const NO_FILTERS: Filters = { q: '', source: '' };

export function parseSource(value: string | null | undefined): SourceFilter {
  return SOURCE_OPTIONS.some((o) => o.value === value) ? (value as SourceFilter) : '';
}

export type SortKey = 'name' | 'rotated';
export interface Sort {
  readonly key: SortKey;
  readonly direction: 'ascending' | 'descending';
}

/** The kit's table opens by name, ascending. */
export const DEFAULT_SORT: Sort = { key: 'name', direction: 'ascending' };

/** What the table needs to know about the rest of the gateway: each connection's env and the stores. */
export interface RowContext {
  readonly envOf: ReadonlyMap<string, Env>;
  readonly stores: readonly SecretStore[] | null;
}

export interface OwnerCell {
  readonly kind: 'connection' | 'mcp-server' | 'store' | 'other';
  readonly label: string;
  /** Mono label (a connection or server name) rather than words ("Vault store"). */
  readonly mono: boolean;
  readonly env: Env | null;
  /** Where the owner lives: another page, or a card on this one (`fragment` only). */
  readonly link: { readonly route: string | null; readonly fragment: string | null } | null;
  readonly caption: string | null;
}

const OTHER_OWNERS: Readonly<Record<string, { readonly caption: string; readonly route: string }>> = {
  notification: { caption: 'notifications', route: '/settings/notifications' },
  'audit-export': { caption: 'audit export', route: '/settings/audit' },
};

/** The "Used by" cell: a connection with its env, an MCP server, a store's own sign-in, a setting. */
export function ownerCell(row: SecretRow, ctx: RowContext): OwnerCell {
  const label = row.owner ?? row.name.split('/')[0] ?? row.name;
  switch (row.ownerKind) {
    case 'connection':
      return { kind: 'connection', label, mono: true, env: ctx.envOf.get(label) ?? null, link: null, caption: null };
    case 'mcp-server':
      return { kind: 'mcp-server', label, mono: true, env: null, link: { route: '/connectors', fragment: 'mcp-servers' }, caption: 'MCP server' };
    case 'store': {
      const store = ctx.stores?.find((s) => s.name === row.owner) ?? null;
      const info = storeKind(store?.kind);
      const anchor = store ? (storeAnchors(ctx.stores ?? []).get(store.name) ?? null) : null;
      return {
        kind: 'store',
        label: info ? `${info.short} store` : label,
        mono: !info,
        env: null,
        link: anchor ? { route: null, fragment: anchor } : null,
        caption: 'sign-in',
      };
    }
    default: {
      const other = OTHER_OWNERS[row.ownerKind];
      return { kind: 'other', label, mono: true, env: null, link: other ? { route: other.route, fragment: null } : null, caption: other?.caption ?? null };
    }
  }
}

/** The text the search looks at: the secret's name and its Used by cell (kit: the first two cells). */
export function searchText(row: SecretRow, ctx: RowContext): string {
  const owner = ownerCell(row, ctx);
  return [row.name, owner.label, owner.caption ?? '', owner.env ?? ''].join(' ').toLowerCase();
}

export function filterRows(rows: readonly SecretRow[], filters: Filters, ctx: RowContext): SecretRow[] {
  const q = filters.q.trim().toLowerCase();
  return rows.filter(
    (r) => (!q || searchText(r, ctx).includes(q)) && (!filters.source || sourceGroup(r) === filters.source),
  );
}

/** Which Source option a row falls under: `local` or the kind of its store (from its reference when unbound). */
export function sourceGroup(row: SecretRow): SourceFilter {
  if (row.source === 'local') return 'local';
  const kind = storeKind(row.source)?.kind ?? kindOfRef(row.ref);
  return kind ?? '';
}

export function sortRows(rows: readonly SecretRow[], sort: Sort): SecretRow[] {
  const dir = sort.direction === 'ascending' ? 1 : -1;
  const byName = (a: SecretRow, b: SecretRow) => a.name.localeCompare(b.name, 'en');
  return [...rows].sort((a, b) => {
    if (sort.key === 'rotated') {
      // Never rotated is the oldest of all: first ascending, last descending.
      const ta = a.rotatedAt ? Date.parse(a.rotatedAt) : Number.NEGATIVE_INFINITY;
      const tb = b.rotatedAt ? Date.parse(b.rotatedAt) : Number.NEGATIVE_INFINITY;
      if (ta !== tb) return ta < tb ? -dir : dir;
      return byName(a, b);
    }
    return dir * byName(a, b);
  });
}

/** The count line (kit `#secret-count`, a live region): "11 secrets", "3 of 11 secrets". */
export function countText(shown: number, total: number): string {
  return shown === total ? plural(total, 'secret') : `${shown.toLocaleString('en-US')} of ${plural(total, 'secret')}`;
}

export interface SourceCell {
  readonly label: string;
  /** A plain source with a muted icon; or a `gw-status` when the secret needs attention. */
  readonly icon: string | null;
  readonly tone: Tone | null;
}

/** The Source cell: where the value lives, and what is wrong with it (never the value). */
export function sourceCell(row: SecretRow): SourceCell {
  if (row.source === 'local') {
    if (row.state === 'needs-value') return { label: 'Encrypted here · needs a value', icon: null, tone: 'warning' };
    if (row.state === 'testing') return { label: 'Encrypted here · new value failed its test', icon: null, tone: 'warning' };
    return { label: 'Encrypted here', icon: 'lock-keyhole', tone: null };
  }
  const info = storeKind(row.source) ?? storeKind(kindOfRef(row.ref));
  const name = info?.short ?? 'Reference';
  if (row.state === 'not-connected') return { label: `${name} · not connected`, icon: null, tone: 'danger' };
  if (row.state === 'changed-outside') return { label: `${name} · changed outside GateRook`, icon: null, tone: 'warning' };
  if (row.state === 'short-lived') return { label: `${name} · short-lived`, icon: 'timer', tone: null };
  return { label: name, icon: info?.icon ?? 'link', tone: null };
}

/** The Version cell: `v3` here, the store's version of a reference, or why there is none. */
export function versionCell(row: SecretRow): { readonly text: string; readonly mono: boolean } {
  if (row.source === 'local') return row.version === null ? { text: 'None', mono: false } : { text: `v${row.version}`, mono: true };
  const remote = row.remoteVersion?.trim();
  if (!remote) return { text: 'Unknown', mono: false };
  return { text: /^\d+$/.test(remote) ? `v${remote}` : remote, mono: true };
}

/**
 * The Last rotated cell, as the kit writes it: relative up to about six weeks ("41 days ago"), the date after
 * ("Jul 14"); "Never" for a value stored here that was never replaced, "Unknown" for a reference not read yet.
 */
export function rotatedText(row: SecretRow, now: number): string {
  if (!row.rotatedAt) return row.source === 'local' ? 'Never' : 'Unknown';
  const t = Date.parse(row.rotatedAt);
  if (Number.isNaN(t)) return 'Unknown';
  return now - t < 45 * 24 * 3600 * 1000 ? relativeTime(t, now) : shortDate(t, now);
}

export interface RowActions {
  /** Rotate (values stored here): available, or `aria-disabled` with the core's reason in a tooltip. */
  readonly rotate: { readonly available: boolean; readonly reason: string | null } | null;
  /** What Replace replaces: the value, or the reference. */
  readonly replace: 'value' | 'reference';
  /** Test reference (a reference whose store is connected). */
  readonly test: boolean;
  /** Connect store: the dashed card of the kind the reference needs. */
  readonly connectStore: { readonly kind: SecretStoreKind; readonly title: string; readonly anchor: string } | null;
  /** View usage: the Activity query, or null when calls don't name the owner. */
  readonly usage: string | null;
}

export function rowActions(row: SecretRow, ctx: RowContext): RowActions {
  const usage = usageQuery(row);
  if (row.source === 'local') {
    return { rotate: { available: row.canRotate, reason: row.canRotate ? null : row.rotateReason }, replace: 'value', test: false, connectStore: null, usage };
  }
  const kind = storeKind(row.source)?.kind ?? kindOfRef(row.ref);
  const info = storeKind(kind);
  if (row.state === 'not-connected' && info) {
    const anchor = storeAnchors(ctx.stores ?? []).get(row.store ?? '') ?? `store-${anchorKind(info.kind)}`;
    return { rotate: null, replace: 'reference', test: false, connectStore: { kind: info.kind, title: info.title, anchor }, usage };
  }
  return { rotate: null, replace: 'reference', test: row.state === 'reference' && !!row.ref, connectStore: null, usage };
}

/** Activity's query for the calls that used this secret (kit: `connection:dev-db`; a store: its connections). */
export function usageQuery(row: SecretRow): string | null {
  if (row.ownerKind === 'connection' || row.ownerKind === 'mcp-server') return row.owner ? `connection:${row.owner}` : null;
  if (row.ownerKind === 'store') return row.usedBy.length ? row.usedBy.map((c) => `connection:${c}`).join(' ') : null;
  return null;
}

// ── Replace, Rotate, references ──────────────────────────────────────────────────────────────────────────────────

/** What the row dialogs (Rotate, Replace, Replace reference) say about one secret. */
export interface SecretSubject {
  readonly id: string;
  readonly name: string;
  /** Who is tested with the new value: the connection, or a store's address. */
  readonly usedBy: string;
  /** The version in use (null: it needs a value). */
  readonly version: number | null;
  /** On prod a comment is required to change a secret (it goes to Setup changes). */
  readonly prod: boolean;
  /** Whether the owner can test a new value before switching (connections and stores can). */
  readonly testable: boolean;
  readonly ref: string | null;
  readonly store: string | null;
}

export function subjectOf(row: SecretRow, ctx: RowContext): SecretSubject {
  let usedBy = row.owner ?? row.name;
  if (row.ownerKind === 'store') {
    const store = ctx.stores?.find((s) => s.name === row.owner);
    usedBy = hostOf(stringAt(store?.config, 'address')) ?? usedBy;
  }
  return {
    id: row.id,
    name: row.name,
    usedBy,
    version: row.version,
    prod: row.ownerKind === 'connection' && ctx.envOf.get(row.owner ?? '') === 'prod',
    testable: row.ownerKind === 'connection' || row.ownerKind === 'store',
    ref: row.ref ?? null,
    store: row.store ?? null,
  };
}

/** The Replace hint: "Saving creates version 4. Version 3 stays in use until then." */
export function replaceHint(s: SecretSubject): string {
  return s.version === null
    ? 'Saving creates a new version and puts it in use.'
    : `Saving creates version ${s.version + 1}. Version ${s.version} stays in use until then.`;
}

export function replacedToast(s: SecretSubject, result: { readonly version: number; readonly tested: boolean | null; readonly testMessage: string | null }): ToastText {
  const tested = result.tested ? `${s.usedBy} works with version ${result.version}. ` : '';
  return { tone: 'success', title: 'New value saved', body: `Encrypted with the master key. ${tested}The previous version is retired.` };
}

/** A Replace whose test failed: the new version waits, the old one stays in use (the dialog stays open). */
export function replaceFailedText(s: SecretSubject, testMessage: string | null): { readonly title: string; readonly body: string } {
  const inUse = s.version === null ? 'Nothing changed.' : `Version ${s.version} stays in use.`;
  return {
    title: `${s.usedBy} didn't accept the new value`,
    body: `${testMessage ? `${testMessage.replace(/\.?$/, '.')} ` : ''}${inUse} Check the value and save again, or save it without the test.`,
  };
}

export function rotateLead(s: SecretSubject): string {
  if (s.version === null) return `The connector sets a new value at the source, stores it and tests ${s.usedBy}.`;
  return `The connector sets a new value at the source, stores it as version ${s.version + 1} and tests ${s.usedBy}. Version ${s.version} keeps working until the test passes.`;
}

export function rotateStartedToast(s: SecretSubject): ToastText {
  return { tone: 'success', title: `Rotating ${s.name}`, body: 'The new version goes in use once its test passes. Nobody, including you, can view it.' };
}

export function refTestToast(name: string, t: SecretRefTest): ToastText {
  if (!t.found) {
    return { tone: 'danger', title: `Reference ${name} doesn't resolve`, body: t.error ?? 'The store didn\'t find it. Check the reference, then test again.' };
  }
  const took = t.latencyMs !== null && t.latencyMs !== undefined ? ` in ${duration(t.latencyMs)}` : '';
  const version = t.resolvedVersion ? `Version ${t.resolvedVersion.replace(/^(\d+)$/, 'v$1')}. ` : '';
  return { tone: 'success', title: `Reference ${name} works`, body: `Resolved${took}. ${version}The value was neither shown nor kept.` };
}

/** The reference field's own result line in the Replace reference dialog. */
export function refTestLine(t: SecretRefTest): { readonly ok: boolean; readonly text: string } {
  if (!t.found) return { ok: false, text: t.error ?? 'The store didn\'t find it. Check the reference.' };
  const parts = ['Found'];
  if (t.resolvedVersion) parts.push(t.resolvedVersion.replace(/^(\d+)$/, 'v$1'));
  if (t.keys?.length) parts.push(`keys ${t.keys.join(', ')}`);
  return { ok: true, text: `${parts.join(' · ')}. The value was neither shown nor kept.` };
}

/** A reference typed into Replace reference: one of the three schemes (the core checks the rest). */
export function refProblem(uri: string): string | null {
  const v = uri.trim();
  if (!v) return 'Enter a reference such as vault://kv/stage/db#password.';
  if (!kindOfRef(v)) return 'A reference starts with vault://, gcp-sm:// or aws-sm://.';
  return null;
}

// ── Master key ───────────────────────────────────────────────────────────────────────────────────────────────────

/** The OS protection row: on with which keyring, or off (the file's permissions only). */
export function osProtectionText(p: string): string {
  switch (p) {
    case 'keychain':
      return 'On · the file is also encrypted with your macOS Keychain';
    case 'dpapi':
      return 'On · the file is also encrypted with Windows DPAPI for your account';
    case 'libsecret':
      return 'On · the file is also encrypted with your Secret Service keyring';
    case 'kwallet':
      return 'On · the file is also encrypted with KWallet';
    case 'unavailable':
      return 'Unavailable · the file is protected by its permissions only';
    default:
      return 'Off · the file is protected by its permissions only';
  }
}

export interface KeyAlert {
  readonly code: string;
  readonly tone: 'danger' | 'warning' | 'info';
  readonly icon: string;
  readonly title: string | null;
  readonly body: string;
  /** The alert's own way out. */
  readonly action: 'fix-permissions' | 'data-folder' | null;
}

/**
 * What is wrong with the key, in the kit's words (the "problem states" the kit keeps under "Show problem states").
 * `backup-needed` tones Last backup instead of adding an alert.
 */
export function keyAlerts(s: MasterKeyStatus): KeyAlert[] {
  const out: KeyAlert[] = [];
  const has = (p: string) => s.problems.includes(p);
  if (s.warnings.includes('new-key-created')) {
    out.push({
      code: 'new-key-created',
      tone: 'warning',
      icon: 'triangle-alert',
      title: 'GateRook created a new master key',
      body: 'No key file was found when the core started and no secret needed one, so it made a new key. Back it up now.',
      action: null,
    });
  }
  if (has('perms')) {
    out.push({
      code: 'perms',
      tone: 'danger',
      icon: 'shield-x',
      title: `Permissions are ${s.mode ?? 'too open'}: other users on this computer can read the key`,
      body: "Something changed them after the gateway set 0400. Fix them, then rotate the key if you don't trust the other accounts.",
      action: 'fix-permissions',
    });
  }
  if (has('no-os-protection')) {
    out.push(
      s.osProtection === 'unavailable'
        ? {
            code: 'no-os-protection',
            tone: 'warning',
            icon: 'lock-open',
            title: 'OS protection unavailable',
            body: 'No keyring answered (Linux without a Secret Service, for example), so the key file is protected by its permissions only. Install or unlock a keyring, then restart the core.',
            action: null,
          }
        : {
            code: 'no-os-protection',
            tone: 'warning',
            icon: 'lock-open',
            title: 'OS protection is off',
            body: 'The key file is protected by its permissions only. The desktop app turns protection on with your keyring where one is available.',
            action: null,
          },
    );
  }
  if (has('synced-folder')) {
    out.push({
      code: 'synced-folder',
      tone: 'warning',
      icon: 'folder-sync',
      title: 'The key is in a synced folder',
      body: 'The data folder syncs to other devices, so each of them gets a copy of the key and the database. Quit the app, move the folder out of the synced folder, then start it again.',
      action: 'data-folder',
    });
  }
  return out;
}

export function keyFileName(path: string): string {
  const i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
  return i >= 0 ? path.slice(i + 1) : path;
}

export function primaryKeyId(s: MasterKeyStatus | null): number | null {
  return s?.keys.find((k) => k.primary)?.id ?? null;
}

/** "Protects": the local secrets with a value under this key. */
export function protectsText(n: number): string {
  return n === 0 ? 'No secrets stored here yet' : `${plural(n, 'secret')} stored here`;
}

/** The Back up dialog's lead: what is lost without a copy. */
export function backupLead(protects: number): string {
  return protects > 0
    ? `Without a copy, the ${plural(protects, 'secret')} stored here can't be recovered if this computer is lost.`
    : "Without a copy, the secrets you store here later can't be recovered if this computer is lost.";
}

/** The name the save dialog suggests for a backup (the kit's "master-keyset-2026-10-22.json"). */
export function backupFileName(now: number): string {
  const d = new Date(now);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `master-keyset-${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}.json`;
}

export function backedUpToast(result: { readonly path: string; readonly passphrase: boolean; readonly warnings: readonly string[] }): ToastText {
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

// ── Master key rotation ──────────────────────────────────────────────────────────────────────────────────────────

export type StepState = 'complete' | 'current' | 'upcoming' | 'error';

export interface RotationStep {
  readonly label: string;
  readonly state: StepState;
  /** Plain text, with the key id (if any) shown mono after it. */
  readonly description: string | null;
  readonly keyId: number | null;
  readonly keyAfter: string;
}

export interface RotationView {
  readonly state: MasterKeyRotation['state'];
  readonly running: boolean;
  readonly label: string;
  readonly done: number;
  readonly total: number;
  readonly fraction: number;
  readonly valueText: string;
  readonly tone: 'brand' | 'success' | 'danger';
  readonly steps: readonly RotationStep[];
}

/** The four steps of the rotation (kit `#dlg-rotate-key` before it starts, `#dlg-rotate-progress` while it runs). */
export function rotationView(r: MasterKeyRotation, oldKeyId: number | null, protects: number): RotationView {
  const total = r.state === 'idle' ? protects : r.total;
  const done = Math.min(r.done, Math.max(total, r.done));
  const keys = plural(total, 'data key');
  const step = (label: string, state: StepState, description: string | null, keyId: number | null = null, keyAfter = ''): RotationStep => ({
    label,
    state,
    description,
    keyId,
    keyAfter,
  });
  const target = r.targetKeyId;
  let steps: RotationStep[];
  let label: string;
  let tone: RotationView['tone'] = 'brand';
  switch (r.state) {
    case 'running':
      label = `Re-wrapping data keys · ${done} of ${total}`;
      steps = [
        step('Add a new key', 'complete', target === null ? 'The new key is primary' : 'Key ', target, target === null ? '' : ' is primary'),
        step(`Re-wrap ${keys}`, 'current', `${done} of ${total} done`),
        step('Disable the old key', 'upcoming', oldKeyId === null ? null : 'Key ', oldKeyId),
        step('Back up the new key', 'upcoming', null),
      ];
      break;
    case 'done':
      label = total === 0 ? 'Done · no data keys to re-wrap' : `Done · ${done} of ${total} data keys re-wrapped`;
      tone = 'success';
      steps = [
        step('Add a new key', 'complete', target === null ? 'The new key is primary' : 'Key ', target, target === null ? '' : ' is primary'),
        step(`Re-wrap ${keys}`, 'complete', `${done} of ${total} done`),
        step('Disable the old key', 'complete', oldKeyId === null ? 'The old key is disabled' : 'Key ', oldKeyId, oldKeyId === null ? '' : ' is disabled'),
        step('Back up the new key', 'current', "Copies made before rotation can't open the re-wrapped data keys."),
      ];
      break;
    case 'failed':
      label = `Rotation stopped · ${done} of ${total} data keys re-wrapped`;
      tone = 'danger';
      steps = [
        step('Add a new key', 'complete', target === null ? 'The new key is primary' : 'Key ', target, target === null ? '' : ' is primary'),
        step(`Re-wrap ${keys}`, 'error', `${r.error ? `${r.error.replace(/\.?$/, '.')} ` : ''}It resumes when the core starts again; calls keep working.`),
        step('Disable the old key', 'upcoming', oldKeyId === null ? null : 'Key ', oldKeyId),
        step('Back up the new key', 'upcoming', null),
      ];
      break;
    default:
      label = 'Not started';
      steps = [
        step('Add a new key', 'upcoming', 'Generated on this computer and made primary in the keyset.'),
        step(`Re-wrap ${keys}`, 'upcoming', "Each secret's data key is wrapped again with the new key. Secret values are never written out."),
        step('Disable the old key', 'upcoming', 'Only after every data key is re-wrapped, so a call in flight never loses its key.'),
        step('Back up the new key', 'upcoming', "Copies made before rotation can't open the re-wrapped data keys."),
      ];
  }
  return {
    state: r.state,
    running: r.state === 'running',
    label,
    done,
    total,
    fraction: total === 0 ? (r.state === 'done' ? 1 : 0) : Math.min(1, done / total),
    valueText: `${done} of ${total} data keys re-wrapped`,
    tone,
    steps,
  };
}

// ── External stores ──────────────────────────────────────────────────────────────────────────────────────────────

/** A string in a JSON config by path (`credentials.impersonate`), or null. */
export function stringAt(config: JsonObject | null | undefined, path: string): string | null {
  let node: JsonValue | undefined = config ?? undefined;
  for (const part of path.split('.')) {
    if (!node || typeof node !== 'object' || Array.isArray(node)) return null;
    node = (node as JsonObject)[part];
  }
  return typeof node === 'string' && node.trim() ? node : null;
}

export function hostOf(url: string | null): string | null {
  if (!url) return null;
  try {
    return new URL(url).host || null;
  } catch {
    return null;
  }
}

/** The store's status in its card header. */
export function storeStatus(s: SecretStore): { readonly tone: Tone; readonly label: string } {
  if (s.verified === false) return { tone: 'warning', label: 'Changed outside GateRook' };
  if (s.state === 'connected') return { tone: 'success', label: s.latencyMs !== null && s.latencyMs !== undefined ? `Connected · ${duration(s.latencyMs)}` : 'Connected' };
  if (s.state === 'failing') return { tone: 'danger', label: 'Failing' };
  return { tone: 'neutral', label: 'Not checked yet' };
}

/** The card's description: Vault's fork, GCP's project, AWS's region and how it reads. */
export function storeDescription(s: SecretStore): string {
  switch (s.kind) {
    case 'vault':
      return 'Or OpenBao, its open-source fork';
    case 'gcp-sm':
      return stringAt(s.config, 'project') ? `Project ${stringAt(s.config, 'project')}` : 'Google Cloud';
    default: {
      const region = stringAt(s.config, 'region');
      const how = stringAt(s.config, 'credentials.roleArn') ? 'Read with AssumeRole and an ExternalId' : 'Read with your AWS credentials';
      return region ? `${how} · ${region}` : how;
    }
  }
}

/** One sentence of how the store signs in, as parts (`mono` for literals). */
export type TextPart = { readonly text: string; readonly mono?: boolean };

export function storeSignIn(s: SecretStore): TextPart[] {
  const secretName = (field: string) => s.signIn?.find((x) => x.field === field)?.name ?? null;
  if (s.kind === 'vault') {
    const method = stringAt(s.config, 'auth.method') ?? 'approle';
    if (method === 'token') {
      const name = secretName('/auth/token');
      return name ? [{ text: 'A token stored here: ' }, { text: name, mono: true }] : [{ text: 'A token stored here' }];
    }
    if (method === 'cli-token') return [{ text: 'Your ' }, { text: 'vault login', mono: true }, { text: ' on this computer (' }, { text: stringAt(s.config, 'auth.file') ?? '~/.vault-token', mono: true }, { text: ')' }];
    const role = stringAt(s.config, 'auth.roleId');
    const parts: TextPart[] = [{ text: 'AppRole ' }];
    if (role) parts.push({ text: role, mono: true }, { text: ' ' });
    parts.push({ text: `gets a token, renewed every ${renewText(stringAt(s.config, 'renewIncrement') ?? '1h')}.` });
    const secret = secretName('/auth/secretId');
    if (secret) parts.push({ text: ' Secret ID: ' }, { text: secret, mono: true });
    return parts;
  }
  if (s.kind === 'gcp-sm') {
    if (stringAt(s.config, 'credentials.source') === 'serviceAccountKey') {
      const key = secretName('/credentials/serviceAccountKey');
      return key ? [{ text: 'A service account key stored here: ' }, { text: key, mono: true }] : [{ text: 'A service account key stored here' }];
    }
    const sa = stringAt(s.config, 'credentials.impersonate');
    return sa
      ? [{ text: 'Impersonates ' }, { text: sa, mono: true }, { text: ' from your gcloud credentials. No key file.' }]
      : [{ text: 'Your gcloud credentials on this computer. No key file.' }];
  }
  const parts: TextPart[] =
    stringAt(s.config, 'credentials.source') === 'profile'
      ? [{ text: 'AWS profile ' }, { text: stringAt(s.config, 'credentials.profile') ?? 'default', mono: true }]
      : secretName('/credentials/secretAccessKey')
        ? [{ text: 'Access key ' }, { text: secretName('/credentials/secretAccessKey')!, mono: true }]
        : [{ text: 'An access key stored here' }];
  const role = stringAt(s.config, 'credentials.roleArn');
  if (role) {
    parts.push({ text: ' assumes ' }, { text: role, mono: true });
    const ext = stringAt(s.config, 'credentials.externalId');
    if (ext) parts.push({ text: ' with ExternalId ' }, { text: ext, mono: true });
  }
  parts.push({ text: '.' });
  return parts;
}

/** Vault's renew increment as words: "1h" → "hour", "30m" → "30 min". */
export function renewText(inc: string): string {
  const m = /^(\d+)([smh])$/.exec(inc.trim());
  if (!m) return inc;
  const n = Number(m[1]);
  const unit = m[2] === 'h' ? 'hour' : m[2] === 'm' ? 'min' : 's';
  if (n === 1 && unit === 'hour') return 'hour';
  return unit === 'hour' ? `${n} hours` : `${n} ${unit}`;
}

/**
 * What a store issues (the Issues row): database users for its dynamic references; the rest of `issues` are SSH
 * certificates, the only other credential a store signs (S1 counts the connections it issued for since start).
 */
export function storeIssuesText(s: SecretStore): string {
  const dynamic = new Set((s.references ?? []).filter((r) => r.kind === 'dynamic').map((r) => r.owner)).size;
  const issues = s.issues ?? 0;
  if (dynamic > 0 && issues > dynamic) return 'SSH certificates and database users';
  if (dynamic > 0) return 'Database users';
  return issues > 0 ? 'SSH certificates' : 'Short-lived credentials';
}

/** The keyset row: how many keys, and that older ones go once the new key is backed up. */
/**
 * The kit's Created row ("Jul 14, never rotated"): when the key was made and last rotated (step API); null from a core
 * that doesn't say, which shows the Keys row instead.
 */
export function createdRow(
  s: Pick<MasterKeyStatus, 'createdAt' | 'rotatedAt'>,
  date: (at: string) => string,
): { readonly at: string; readonly date: string; readonly rotation: string } | null {
  if (!s.createdAt) return null;
  return { at: s.createdAt, date: date(s.createdAt), rotation: s.rotatedAt ? `rotated ${date(s.rotatedAt)}` : 'never rotated' };
}

export function keysText(s: MasterKeyStatus): string {
  const n = s.keys.length;
  const older = s.keys.filter((k) => !k.primary).length;
  if (!older) return `${n} in the keyset`;
  return `${n} in the keyset · ${older === 1 ? 'the older one goes' : 'older ones go'} at the next backup`;
}

/** References read through a store (dynamic credentials are counted under Issues instead). */
export function storeReferences(s: SecretStore): { readonly name: string; readonly ref: string }[] {
  return (s.references ?? []).filter((r) => r.kind !== 'dynamic').map((r) => ({ name: r.name, ref: r.ref }));
}

/** The connections a store serves: its references' owners, sorted. */
export function storeConnections(s: SecretStore): string[] {
  return [...new Set((s.references ?? []).map((r) => r.owner).filter((o): o is string => !!o))].sort();
}

/** "Reference secrets where they already live. … 2 of 3 connected." */
export function storesSummary(stores: readonly SecretStore[]): string {
  const kinds = new Set(stores.map((s) => s.kind));
  return `Reference secrets where they already live. They're read at call time and never copied here. ${kinds.size} of ${STORE_KINDS.length} connected.`;
}

/** The references that wait for a kind of store: rows of that kind no store resolves now. */
export function waitingFor(kind: SecretStoreKind, rows: readonly SecretRow[]): string[] {
  return rows.filter((r) => r.source !== 'local' && r.state === 'not-connected' && sourceGroup(r) === kind).map((r) => r.name);
}

export function waitingText(names: readonly string[]): TextPart[] {
  if (!names.length) return [];
  const parts: TextPart[] = [];
  names.forEach((n, i) => {
    if (i > 0) parts.push({ text: i === names.length - 1 ? ' and ' : ', ' });
    parts.push({ text: n, mono: true });
  });
  parts.push({ text: names.length === 1 ? " points here and can't be resolved until you connect." : " point here and can't be resolved until you connect." });
  return parts;
}

/** What Disconnect stops, said before it happens (kit `#dlg-disconnect-vault`). */
export function disconnectLead(s: SecretStore, title: string): TextPart[] {
  const conns = storeConnections(s);
  const parts: TextPart[] = [];
  if (conns.length) {
    conns.forEach((c, i) => {
      if (i > 0) parts.push({ text: i === conns.length - 1 ? ' and ' : ', ' });
      parts.push({ text: c, mono: true });
    });
    parts.push({ text: `${conns.length === 1 ? ' fails on its' : ' fail on their'} next call: ${conns.length === 1 ? 'its' : 'their'} credentials come from ${title}.` });
  } else {
    parts.push({ text: `No connection reads from ${title} now.` });
  }
  const signIn = (s.signIn ?? []).map((x) => x.name);
  if (signIn.length) {
    parts.push({ text: ` Its sign-in ${signIn.length === 1 ? 'secret' : 'secrets'} ` });
    signIn.forEach((n, i) => {
      if (i > 0) parts.push({ text: ', ' });
      parts.push({ text: n, mono: true });
    });
    parts.push({ text: signIn.length === 1 ? ' is destroyed.' : ' are destroyed.' });
  }
  return parts;
}

export function disconnectedToast(title: string, r: SecretStoreDisconnected, connections: number): ToastText {
  const body = connections
    ? `${plural(connections, 'connection')} can't get credentials until you connect it again.`
    : r.referencesNotConnected.length
      ? `${joinAnd(r.referencesNotConnected)} can't be resolved until you connect it again.`
      : 'Nothing read from it.';
  return { tone: 'warning', title: `${title} disconnected`, body };
}

export function storeTestToast(title: string, t: SecretStoreTest): ToastText {
  if (!t.ok) return { tone: 'danger', title: `${title} doesn't answer`, body: t.error ?? 'The test failed. Check the store, then test again.' };
  const warn = t.hints.find((h) => h.severity === 'warning' || h.severity === 'danger');
  const refs = t.permissions.length ? ` ${plural(t.permissions.length, 'reference')} checked.` : '';
  return {
    tone: warn ? 'warning' : 'success',
    title: `${title} works`,
    body: `Answered in ${duration(t.latencyMs)}.${refs}${warn ? ` ${warn.text}` : ''}`,
  };
}

export function connectedToast(title: string, store: SecretStore, test: SecretStoreTest | null): ToastText {
  const refs = storeReferences(store).map((r) => r.name);
  if (test && !test.ok) {
    return { tone: 'warning', title: `${title} connected, but its test failed`, body: test.error ?? 'Check its settings, then test it again.' };
  }
  return {
    tone: 'success',
    title: `${title} connected`,
    body: refs.length ? `${joinAnd(refs)} ${refs.length === 1 ? 'resolves' : 'resolve'} now.` : 'Point a connection’s secret at it from the connection’s form.',
  };
}

// ── Short-lived credentials ──────────────────────────────────────────────────────────────────────────────────────

export interface ShortLivedGroup {
  readonly key: string;
  readonly title: string;
  readonly icon: string;
  readonly description: string;
  readonly identityLabel: string;
  readonly identity: string;
  readonly lifetimeLabel: string;
  readonly lifetime: string;
  readonly connections: readonly { readonly name: string; readonly env: Env | null }[];
  readonly lastIssuedAt: string | null;
  /** AWS: the stored key it assumes the role with. */
  readonly assumedWith: string | null;
  /** AWS: the role's ExternalId (not a secret), step API; null when the core doesn't say. */
  readonly externalId: string | null;
  /** SSH CA, Vault: the principal it is issued for (`deploy`), step API; null when the source doesn't say. */
  readonly principal: string | null;
}

interface Mechanism {
  readonly title: string;
  readonly icon: string;
  readonly description: string;
  readonly identityLabel: string;
  readonly lifetimeLabel: string;
  readonly lifetimeSuffix: string;
}

/**
 * The kit's four cards (Secrets › Short-lived credentials), keyed by the core's mechanism names. Texts per decision
 * 10 (ARCHITECTURE §8.6.5): a credential is reused and renewed, not issued for every call — except SSH certificates.
 */
const MECHANISMS: readonly [string, Mechanism][] = [
  [
    'GCP impersonation',
    {
      title: 'GCP service account impersonation',
      icon: 'user-round-key',
      description: "Your Google sign-in borrows a service account's read access: a short-lived token, renewed automatically.",
      identityLabel: 'Service account',
      lifetimeLabel: 'Lifetime',
      lifetimeSuffix: '',
    },
  ],
  [
    'AWS AssumeRole',
    {
      title: 'AWS AssumeRole with ExternalId',
      icon: 'cloud',
      description: 'A stored access key that can only assume one read-only role.',
      identityLabel: 'Role',
      lifetimeLabel: 'Session',
      lifetimeSuffix: '',
    },
  ],
  [
    'SSH certificate from Vault CA',
    {
      title: 'SSH certificates from Vault CA',
      icon: 'file-key',
      description: 'Vault signs a fresh key pair for each call. No private key is kept anywhere.',
      identityLabel: 'Signer',
      lifetimeLabel: 'Lifetime',
      lifetimeSuffix: '',
    },
  ],
  [
    'Vault dynamic database user',
    {
      title: 'Vault dynamic database users',
      icon: 'database',
      description: 'Vault creates a short-lived database user, renews it automatically and revokes it when the connection goes idle.',
      identityLabel: 'Role',
      lifetimeLabel: 'Lease',
      lifetimeSuffix: ', renewed automatically',
    },
  ],
];

export function shortLivedGroups(items: readonly ShortLivedCredential[], envOf: ReadonlyMap<string, Env>, secrets: readonly SecretRow[] = []): ShortLivedGroup[] {
  const order = (m: string) => {
    const i = MECHANISMS.findIndex(([name]) => name === m);
    return i < 0 ? MECHANISMS.length : i;
  };
  const groups = new Map<string, { m: string; identity: string; items: ShortLivedCredential[] }>();
  for (const c of items) {
    const key = `${c.mechanism}\u0000${c.identity}`;
    const g = groups.get(key) ?? { m: c.mechanism, identity: c.identity, items: [] };
    g.items.push(c);
    groups.set(key, g);
  }
  return [...groups.entries()]
    .sort(([, a], [, b]) => order(a.m) - order(b.m) || a.identity.localeCompare(b.identity))
    .map(([key, g]) => {
      const m = MECHANISMS.find(([name]) => name === g.m)?.[1] ?? {
        title: g.m,
        icon: 'timer',
        description: 'A credential the gateway gets for a short time instead of a static key.',
        identityLabel: 'Identity',
        lifetimeLabel: 'Lifetime',
        lifetimeSuffix: '',
      };
      const names = [...new Set(g.items.map((c) => c.connection))].sort();
      const last = g.items.map((c) => c.lastIssuedAt).filter((t): t is string => !!t).sort().at(-1) ?? null;
      const lifetime = Math.max(...g.items.map((c) => c.lifetime));
      const assumed = g.m === 'AWS AssumeRole' ? (secrets.find((s) => s.source === 'local' && names.includes(s.owner ?? ''))?.name ?? null) : null;
      const first = (field: 'externalId' | 'principal') => g.items.map((c) => c[field]).find((x): x is string => !!x) ?? null;
      return {
        key,
        title: m.title,
        icon: m.icon,
        description: m.description,
        identityLabel: m.identityLabel,
        identity: g.identity,
        lifetimeLabel: m.lifetimeLabel,
        lifetime: `${duration(lifetime * 1000)}${m.lifetimeSuffix}`,
        connections: names.map((name) => ({ name, env: envOf.get(name) ?? null })),
        lastIssuedAt: last,
        assumedWith: assumed,
        externalId: first('externalId'),
        principal: first('principal'),
      };
    });
}

export function shortLivedSummary(groups: readonly ShortLivedGroup[]): string {
  const n = new Set(groups.flatMap((g) => g.connections.map((c) => c.name))).size;
  return `Instead of a static key, the gateway gets a credential that expires minutes after the call. ${plural(n, 'connection')} ${n === 1 ? 'works' : 'work'} this way.`;
}

/** Activity's query for a card's connections. */
export function connectionsQuery(names: readonly string[]): string {
  return names.map((n) => `connection:${n}`).join(' ');
}
