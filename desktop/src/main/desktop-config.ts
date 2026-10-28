// ~/.gaterook/desktop.json (ARCHITECTURE.md §7.1): the only source of the core's ports, written only by main
// ("Use another port", Settings › Local API → core.restart-required), 0600. The database never stores a port: a core
// started on another port must not disagree with the file that started it.
//
// Main also keeps here the General switches it needs while the core doesn't answer (start hidden at login; whether
// to look for updates while the core is stopped or locked): the core stores the settings, this is main's copy.
import { chmodSync, mkdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { writeFileAtomic } from './atomic-file.ts';

export interface DesktopConfig {
  readonly port: number;
  readonly publicPort: number;
  readonly openAtLogin?: boolean;
  readonly startHidden?: boolean;
  /** General › "Check for updates automatically" as the core last said it. */
  readonly autoUpdate?: boolean;
}

export const DEFAULT_PORT = 7465;
export const DEFAULT_PUBLIC_PORT = 7466;
export const DEFAULTS: DesktopConfig = { port: DEFAULT_PORT, publicPort: DEFAULT_PUBLIC_PORT };

/** The lowest port a person may choose: below it ports need privileges (and the core refuses them too). */
export const MIN_USER_PORT = 1024;

export function desktopConfigPath(home: string): string {
  return join(home, 'desktop.json');
}

/** Why `port` can't be the local API's port next to `publicPort`, or null. */
export function portProblem(port: unknown, publicPort: number): string | null {
  if (!Number.isInteger(port)) return 'Enter a port number.';
  const p = port as number;
  if (p < MIN_USER_PORT || p > 65_535) return `Use a port from ${MIN_USER_PORT} to 65535.`;
  if (p === publicPort) return `${p} is reserved by GateRook. Choose another one.`;
  return null;
}

/** Why `publicPort` can't be the tunnel's public listener next to the local API on `port`, or null. */
export function publicPortProblem(publicPort: unknown, port: number): string | null {
  if (!Number.isInteger(publicPort)) return 'Enter a port number.';
  const p = publicPort as number;
  if (p < MIN_USER_PORT || p > 65_535) return `Use a port from ${MIN_USER_PORT} to 65535.`;
  if (p === port) return `${p} is the local API's port. Choose another one.`;
  return null;
}

/** What "Use another port" asks for: the ports to start the core on, and which of them the request named. */
export interface PortRequest {
  readonly port: number;
  readonly publicPort: number;
  readonly named: readonly ('port' | 'publicPort')[];
}

/**
 * Reads `usePort`'s argument (U16, ARCHITECTURE.md §7.1): a number moves the local API; `{port?, publicPort?}` moves
 * either listener or both — the tunnel's public one too, which the core binds at start as well, so a port someone
 * else holds there stops the core just the same. Returns the request, or why it can't be used (for the field).
 */
export function portRequest(raw: unknown, current: { readonly port: number; readonly publicPort: number }):
    PortRequest | { readonly problem: string } {
  if (typeof raw === 'number') {
    const problem = portProblem(raw, current.publicPort);
    return problem ? { problem } : { port: raw, publicPort: current.publicPort, named: ['port'] };
  }
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return { problem: 'Enter a port number.' };
  const r = raw as Record<string, unknown>;
  const named = (['port', 'publicPort'] as const).filter((k) => r[k] !== undefined);
  if (named.length === 0) return { problem: 'Enter a port number.' };
  const port = r['port'] !== undefined ? r['port'] : current.port;
  const publicPort = r['publicPort'] !== undefined ? r['publicPort'] : current.publicPort;
  const problem = (r['port'] !== undefined ? portProblem(port, publicPort as number) : null)
    ?? (r['publicPort'] !== undefined ? publicPortProblem(publicPort, port as number) : null);
  return problem ? { problem } : { port: port as number, publicPort: publicPort as number, named };
}

/**
 * The file as main last wrote it; defaults when it is missing. A file someone broke (not JSON, ports out of range,
 * both the same) falls back to the defaults too, and `problem` says so for the log: starting the core beats
 * refusing to start over a file only main writes.
 */
export function readDesktopConfig(home: string): { config: DesktopConfig; problem: string | null } {
  let text: string;
  try {
    text = readFileSync(desktopConfigPath(home), 'utf8');
  } catch (e) {
    const code = (e as NodeJS.ErrnoException).code;
    return { config: DEFAULTS, problem: code === 'ENOENT' ? null : `can't read desktop.json (${code ?? 'error'})` };
  }
  let raw: unknown;
  try {
    raw = JSON.parse(text);
  } catch {
    return { config: DEFAULTS, problem: 'desktop.json is not JSON' };
  }
  if (typeof raw !== 'object' || raw === null) return { config: DEFAULTS, problem: 'desktop.json is not an object' };
  const r = raw as Record<string, unknown>;
  const publicPort = Number.isInteger(r['publicPort']) && (r['publicPort'] as number) >= MIN_USER_PORT
    && (r['publicPort'] as number) <= 65_535 ? (r['publicPort'] as number) : DEFAULT_PUBLIC_PORT;
  const port = portProblem(r['port'], publicPort) === null ? (r['port'] as number) : null;
  const config: DesktopConfig = {
    port: port ?? (publicPort === DEFAULT_PORT ? DEFAULT_PUBLIC_PORT : DEFAULT_PORT),
    publicPort,
    ...(typeof r['openAtLogin'] === 'boolean' ? { openAtLogin: r['openAtLogin'] } : {}),
    ...(typeof r['startHidden'] === 'boolean' ? { startHidden: r['startHidden'] } : {}),
    ...(typeof r['autoUpdate'] === 'boolean' ? { autoUpdate: r['autoUpdate'] } : {}),
  };
  const problem = port === null && r['port'] !== undefined ? `desktop.json has an invalid port; using ${config.port}` : null;
  return { config, problem };
}

/**
 * Writes the file atomically (temporary file, synced, then renamed), owner-only, creating the home folder 0700.
 * Refuses ports the core would refuse, the public one included.
 */
export function writeDesktopConfig(home: string, config: DesktopConfig): void {
  const problem = portProblem(config.port, config.publicPort) ?? publicPortProblem(config.publicPort, config.port);
  if (problem) throw new Error(problem);
  ensureHome(home);
  const body = {
    port: config.port,
    publicPort: config.publicPort,
    ...(config.openAtLogin !== undefined ? { openAtLogin: config.openAtLogin } : {}),
    ...(config.startHidden !== undefined ? { startHidden: config.startHidden } : {}),
    ...(config.autoUpdate !== undefined ? { autoUpdate: config.autoUpdate } : {}),
  };
  writeFileAtomic(desktopConfigPath(home), JSON.stringify(body, null, 2) + '\n', 0o600);
}

/** The gateway home, owner-only (the core makes it 0700 too; main may get there first). */
export function ensureHome(home: string): void {
  mkdirSync(home, { recursive: true, mode: 0o700 });
  if (process.platform !== 'win32') chmodSync(home, 0o700);
}
