// The core exports gw-bridge.exe into the gateway home for external MCP clients. Its Windows DLLs must travel
// with it: a client that launches the exported bridge does not inherit the desktop package's dependency graph.
import { lstatSync, mkdirSync, readFileSync, readdirSync, type Stats } from 'node:fs';
import { join } from 'node:path';
import { writeFileAtomic } from './atomic-file.ts';

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

/** Provisions only bundled DLLs; the core owns the exported executable and unrelated files remain untouched. */
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
  directory(home);
  const destination = join(home, 'bin');
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
  const copied: string[] = [];
  for (const { name, to, bytes } of libraries) {
    if (optionalStat(to)) {
      const stat = file(to);
      if (stat.size === bytes.length && readFileSync(to).equals(bytes)) continue;
    }
    writeFileAtomic(to, bytes, 0o600);
    copied.push(name);
  }
  return copied;
}
