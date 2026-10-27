// Files the person picks for the core (ARCHITECTURE.md §6 "The core accepts no raw file paths from the UI"): the
// renderer names a purpose, main shows the native dialog, registers the chosen path with `POST /internal/files/grant`
// and hands back only the one-time token. The purposes are the core's (platform.FileGrants.Purpose); each is either
// a file to save or one to open, and the renderer can't ask for the other kind. And what main opens with the OS on
// the renderer's behalf (a client's config file, the logs and backups folders): only what can't run anything.
import { lstat, realpath } from 'node:fs/promises';
import { extname } from 'node:path';

export interface FilePurpose {
  readonly id: string;
  readonly save: boolean;
  readonly title: string;
  readonly filters: readonly { readonly name: string; readonly extensions: readonly string[] }[];
  /** The suggested name of a file to save; `{date}` becomes today's date. */
  readonly defaultName?: string;
}

export const FILE_PURPOSES: Readonly<Record<string, FilePurpose>> = {
  'master-key-backup': {
    id: 'master-key-backup', save: true, title: 'Back up master key', defaultName: 'gaterook-master-key-{date}.json',
    filters: [{ name: 'Master key backup', extensions: ['json'] }],
  },
  'master-key-restore': {
    id: 'master-key-restore', save: false, title: 'Restore master key from a backup',
    filters: [{ name: 'Master key backup', extensions: ['json'] }, { name: 'All files', extensions: ['*'] }],
  },
  'db-backup': {
    id: 'db-backup', save: true, title: 'Back up the database', defaultName: 'gaterook-{date}.db',
    filters: [{ name: 'SQLite database', extensions: ['db'] }],
  },
  'audit-export': {
    id: 'audit-export', save: true, title: 'Export activity', defaultName: 'gaterook-activity-{date}.jsonl',
    filters: [{ name: 'JSON Lines', extensions: ['jsonl'] }, { name: 'CSV', extensions: ['csv'] }],
  },
  plugin: {
    id: 'plugin', save: false, title: 'Choose a plugin',
    filters: [{ name: 'Plugin', extensions: ['jar', 'zip'] }],
  },
  openapi: {
    id: 'openapi', save: false, title: 'Choose an OpenAPI document',
    filters: [{ name: 'OpenAPI document', extensions: ['json', 'yaml', 'yml'] }],
  },
};

export function filePurpose(id: unknown, save: boolean): FilePurpose | null {
  if (typeof id !== 'string' || !Object.hasOwn(FILE_PURPOSES, id)) return null;
  const purpose = FILE_PURPOSES[id];
  return purpose && purpose.save === save ? purpose : null;
}

export function defaultFileName(p: FilePurpose, now: Date = new Date()): string | undefined {
  return p.defaultName?.replace('{date}', now.toISOString().slice(0, 10));
}

/** The kinds of folders the renderer may ask main to reveal; main knows where they are. */
export const REVEAL_KINDS = ['backups', 'logs'] as const;
export type RevealKind = (typeof REVEAL_KINDS)[number];

export function revealKind(kind: unknown): RevealKind | null {
  return (REVEAL_KINDS as readonly unknown[]).includes(kind) ? (kind as RevealKind) : null;
}

/** The MCP clients whose config file main may open (ui ClientId); the path comes from the core, never the renderer. */
export const CLIENT_IDS = ['claude-desktop', 'codex', 'cursor', 'antigravity'] as const;

export function clientId(id: unknown): string | null {
  return (CLIENT_IDS as readonly unknown[]).includes(id) ? (id as string) : null;
}

/** A client link id as the core makes them (`cl_…`, `ln_…`): word characters only, bounded. */
export function linkId(id: unknown): string | null {
  return typeof id === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(id) ? id : null;
}

/** The MCP clients' config files are JSON or TOML (core clients.internal.ClientAdapter). */
const CONFIG_EXTENSIONS: ReadonlySet<string> = new Set(['.json', '.jsonc', '.toml']);

/**
 * The config file "Open config" may hand to the OS (`shell.openPath` opens a file with its default application): the
 * real path of a regular file named .json or .toml, `missing`, or `refused` — a config replaced by a link to a
 * `.command` script or an `.app` would otherwise run it.
 */
export async function openableConfig(path: string): Promise<string | 'missing' | 'refused'> {
  const real = await realpath(path).catch(() => null);
  if (!real) return 'missing';
  const stat = await lstat(real).catch(() => null);
  return stat?.isFile() && CONFIG_EXTENSIONS.has(extname(real).toLowerCase()) ? real : 'refused';
}

/** Whether `dir` is a folder of its own to show, not a link (which could lead to an application that opening runs). */
export async function revealableFolder(dir: string): Promise<boolean> {
  const stat = await lstat(dir).catch(() => null);
  return !!stat && stat.isDirectory() && !stat.isSymbolicLink();
}
