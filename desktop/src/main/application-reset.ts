// A confirmed reset survives interruption. The marker contains no paths or secrets; main supplies every target.
import { existsSync, lstatSync, readdirSync, readFileSync, realpathSync, rmSync } from 'node:fs';
import { basename, dirname, isAbsolute, join, parse, relative, resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { writeFileAtomic } from './atomic-file.ts';

const MARKER = '.reset-pending';
// GatewayPaths, PluginPaths, McpServers and ClientEnvironment own these paths. Keep unrelated files in a
// development home intact; a configured GW_HOME is not permission to recursively erase an arbitrary folder.
const OWNED_ENTRIES = new Set(['gateway.db', 'gateway.db-wal', 'gateway.db-shm', 'gateway.db-journal', 'core.lock',
  'master.keyset', 'master.keyset.osk', 'desktop.json', 'backups', 'logs', 'tmp', 'run', 'plugins', 'plugin-data',
  'cache', 'connectors', 'clients', 'bin', 'mcp', 'demo-user-home', 'electron']);
function ownedEntry(name: string): boolean {
  return OWNED_ENTRIES.has(name) || /^master\.keyset\.replaced-\d+$/.test(name)
    || /^\.master\.keyset\.tmp-\d+$/.test(name)
    || /^(desktop\.json|master\.keyset\.osk|\.reset-pending)\.\d+\.[a-f0-9]{12}\.tmp$/.test(name);
}
interface PendingReset { version: 1; corePid: number | null }
export interface ResetPaths {
  readonly home: string;
  readonly userData: string;
  /** The OS home and application sources/resources must never be removed by a misconfigured development home. */
  readonly protectedPaths: readonly string[];
}

function contains(parent: string, child: string): boolean {
  const rel = relative(parent, child);
  return rel === '' || (!isAbsolute(rel) && rel !== '..' && !rel.startsWith(`..${process.platform === 'win32' ? '\\' : '/'}`));
}

/** Resolve existing ancestors as well, so a symlink cannot turn a harmless-looking target into the user's home. */
function canonical(path: string): string {
  const absolute = resolve(path);
  if (existsSync(absolute)) return realpathSync(absolute);
  const parent = dirname(absolute);
  return parent === absolute ? absolute : join(canonical(parent), basename(absolute));
}

export function validateResetPaths(paths: ResetPaths): void {
  const home = canonical(paths.home);
  const profile = canonical(paths.userData);
  if (!isAbsolute(paths.home) || !isAbsolute(paths.userData) || home === parse(home).root
      || lstatSync(paths.home).isSymbolicLink()
      || paths.protectedPaths.some((path) => contains(home, canonical(path)))
      || contains(profile, home)
      || (contains(home, profile) && dirname(profile) !== home)) {
    throw new Error('GateRook cannot reset this data directory safely. Choose a dedicated application data directory.');
  }
}

export function hasPendingReset(home: string): boolean { return existsSync(join(home, MARKER)); }

/** Validate in main too: a renderer cannot bypass the confirmation or select a deletion target. */
export function requestApplicationReset(paths: ResetPaths, confirmation: unknown, corePid: number | null): void {
  if (confirmation !== 'RESET') throw new Error('Type RESET to confirm deleting all GateRook data.');
  validateResetPaths(paths);
  const pending: PendingReset = { version: 1, corePid };
  writeFileAtomic(join(paths.home, MARKER), JSON.stringify(pending) + '\n');
}

function pendingReset(home: string): PendingReset {
  const marker = join(home, MARKER);
  if (!lstatSync(marker).isFile() || lstatSync(marker).isSymbolicLink()) throw new Error('Invalid application reset marker.');
  const value: unknown = JSON.parse(readFileSync(marker, 'utf8'));
  if (!value || typeof value !== 'object' || !('version' in value) || value.version !== 1 || !('corePid' in value)
      || (value.corePid !== null && (!Number.isSafeInteger(value.corePid) || (value.corePid as number) <= 0))) {
    throw new Error('Invalid application reset marker.');
  }
  return value as PendingReset;
}

/** A crashed desktop's child may still be shutting down; never unlink a database it can still write. */
export async function waitForResetCore(pid: number | null): Promise<void> {
  if (pid === null) return;
  for (let attempt = 0; attempt <= 120; attempt++) {
    try { process.kill(pid, 0); }
    catch (error) { if ((error as NodeJS.ErrnoException).code === 'ESRCH') return; }
    if (attempt < 120) await delay(250);
  }
  throw new Error('The previous GateRook core is still running. Close it before restarting GateRook.');
}

export interface ResetOperations {
  readonly stop: () => Promise<void>;
  readonly disableLogin: () => void;
  readonly removeKey: () => Promise<void>;
  /** Destroy renderer windows, then clear the Electron session through its APIs (profile files may be locked). */
  readonly clearProfile: () => Promise<void>;
  readonly waitForCore?: (pid: number | null) => Promise<void>;
}

/** Call before starting the core on a resumed reset. Any failure keeps the marker and prevents normal startup. */
export async function finishApplicationReset(paths: ResetPaths, operations: ResetOperations): Promise<void> {
  validateResetPaths(paths);
  const pending = pendingReset(paths.home);
  await operations.stop();
  await (operations.waitForCore ?? waitForResetCore)(pending.corePid);
  // Do not mistake an unrelated process that later reuses this PID for the old core on the next retry.
  writeFileAtomic(join(paths.home, MARKER), JSON.stringify({ version: 1, corePid: null }) + '\n');
  operations.disableLogin();
  // Keep the reference until deletion succeeds, so an interrupted reset can retry the exact Keychain item.
  await operations.removeKey();
  await operations.clearProfile();
  const profile = canonical(paths.userData);
  for (const entry of readdirSync(paths.home)) {
    const path = join(paths.home, entry);
    if (!ownedEntry(entry) || canonical(path) === profile) continue;
    // rm removes links themselves, never their targets.
    rmSync(path, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 });
  }
  rmSync(join(paths.home, MARKER));
}
