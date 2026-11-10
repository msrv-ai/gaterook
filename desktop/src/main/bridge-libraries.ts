// The core exports gw-bridge.exe into the gateway home for external MCP clients. Its Windows DLLs must travel
// with it: a client that launches the exported bridge does not inherit the desktop package's dependency graph.
import { closeSync, lstatSync, mkdirSync, openSync, readFileSync, readdirSync, realpathSync, type Stats } from 'node:fs';
import { join } from 'node:path';
import { writeFileAtomic } from './atomic-file.ts';
import { StartupProblem } from './startup-failure.ts';

function directory(path: string): void {
  const stat = lstatSync(path);
  if (!stat.isDirectory() || stat.isSymbolicLink()) throw new Error('Bridge libraries require a regular directory.');
}

function file(path: string): Stats {
  const stat = lstatSync(path);
  if (!stat.isFile() || stat.isSymbolicLink()) throw new Error('Bridge libraries must be regular files.');
  return stat;
}

function optionalStat(path: string): Stats | null {
  try { return lstatSync(path); }
  catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') return null;
    throw error;
  }
}

/** Whether `path` can be opened for writing now; a DLL a running gw-bridge.exe has loaded can't be on Windows. */
function writable(path: string): boolean {
  try {
    closeSync(openSync(path, 'r+'));
    return true;
  } catch (error) {
    if (['EBUSY', 'EPERM', 'EACCES'].includes((error as NodeJS.ErrnoException).code ?? '')) return false;
    throw error;
  }
}

function heldBy(bin: string, cause?: unknown): StartupProblem {
  return new StartupProblem(`GateRook can't update its bridge files in ${bin} while another app uses them. Quit the `
    + 'AI apps that connect through GateRook, then start GateRook again.', cause === undefined ? undefined : { cause });
}

/**
 * Provisions only bundled DLLs; the core owns the exported executable and unrelated files remain untouched. A link at
 * the home itself is the person's own (as for the core); the `bin` folder and the files must not be links. A changed
 * DLL that another process holds (a client's running bridge) stops the start with nothing replaced, so the exported
 * bridge never runs with a mix of old and new libraries: the person is asked to quit those apps first.
 */
export function prepareBridgeLibraries(resources: string | null, home: string, platform: NodeJS.Platform): readonly string[] {
  if (platform !== 'win32' || resources === null) return [];
  const source = join(resources, 'bridge');
  if (!optionalStat(source)) return [];
  directory(source);
  const names = readdirSync(source).filter((name) => /\.dll$/i.test(name)).sort();
  if (names.length === 0) return [];
  if (new Set(names.map((name) => name.toLowerCase())).size !== names.length) {
    throw new Error('Bridge libraries contain duplicate Windows filenames.');
  }
  const root = realpathSync(home);
  directory(root);
  const destination = join(root, 'bin');
  if (optionalStat(destination)) directory(destination);
  // Validate the entire set before copying. Never follow source or destination links.
  const libraries = names.map((name) => {
    const from = join(source, name);
    const to = join(destination, name);
    file(from);
    if (optionalStat(to)) file(to);
    return { name, to, bytes: readFileSync(from) };
  });
  if (!optionalStat(destination)) mkdirSync(destination, { mode: 0o700 });
  directory(destination);
  const changed = libraries.filter(({ to, bytes }) => {
    if (!optionalStat(to)) return true;
    const stat = file(to);
    return !(stat.size === bytes.length && readFileSync(to).equals(bytes));
  });
  // All or nothing: one held library leaves every one as it was.
  if (changed.some(({ to }) => optionalStat(to) && !writable(to))) throw heldBy(destination);
  const copied: string[] = [];
  for (const { name, to, bytes } of changed) {
    try {
      writeFileAtomic(to, bytes, 0o600);
    } catch (error) {
      if (['EBUSY', 'EPERM', 'EACCES'].includes((error as NodeJS.ErrnoException).code ?? '')) {
        throw heldBy(destination, error);
      }
      throw error;
    }
    copied.push(name);
  }
  return copied;
}
