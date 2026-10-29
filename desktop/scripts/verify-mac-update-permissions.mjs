#!/usr/bin/env node
// Verify a packaged or freshly extracted macOS app without changing its signed resources.
import { lstat, readdir } from 'node:fs/promises';
import { basename, extname, join, relative, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

/**
 * Squirrel removes quarantine attributes after the app quits. Every real file and directory must have the
 * owner-write bit, including JRE notices. Access checks are insufficient: root can write to mode 0444 files.
 * Child symlinks are retained without following their targets. This check does not verify code signatures.
 */
export async function assertMacUpdatePermissions(appBundle) {
  if (process.platform === 'win32') throw new Error('Verify macOS update permissions on macOS or another POSIX filesystem.');
  if (typeof appBundle !== 'string' || !appBundle.trim()) throw new Error('Provide a packaged .app directory.');
  const root = resolve(appBundle);
  if (extname(root).toLowerCase() !== '.app') throw new Error('Provide a packaged .app directory.');
  const label = (entry) => [basename(root), relative(root, entry)].filter(Boolean).join('/');
  const requireEntry = async (entry, kind) => {
    const info = await lstat(entry);
    if (info.isSymbolicLink() || !(kind === 'directory' ? info.isDirectory() : info.isFile())) {
      throw new Error(`Expected a real ${kind}: ${label(entry)}`);
    }
  };
  await requireEntry(root, 'directory');
  await requireEntry(join(root, 'Contents'), 'directory');
  await requireEntry(join(root, 'Contents', 'Resources'), 'directory');
  await requireEntry(join(root, 'Contents', 'Info.plist'), 'file');

  const checked = { files: 0, directories: 0, symlinks: 0 };
  const visit = async (entry) => {
    const info = await lstat(entry);
    if (info.isSymbolicLink()) { checked.symlinks++; return; }
    if (!info.isFile() && !info.isDirectory()) throw new Error(`Unsupported update payload entry: ${label(entry)}`);
    if (!(info.mode & 0o200)) {
      const mode = (info.mode & 0o7777).toString(8).padStart(4, '0');
      throw new Error(`macOS update payload lacks owner-write permission: ${label(entry)} (mode ${mode}). `
        + 'Correct packaging permissions before signing; this verifier does not modify the app.');
    }
    if (info.isFile()) { checked.files++; return; }
    checked.directories++;
    for (const name of (await readdir(entry)).sort()) await visit(join(entry, name));
  };
  await visit(root);
  return checked;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    if (process.argv.length !== 3 || process.argv[2].startsWith('-')) {
      throw new Error('Usage: node desktop/scripts/verify-mac-update-permissions.mjs /path/to/GateRook.app');
    }
    const checked = await assertMacUpdatePermissions(process.argv[2]);
    console.log(`macOS update permissions verified: ${checked.files} files, ${checked.directories} directories, `
      + `${checked.symlinks} symlinks skipped.`);
  } catch (error) {
    console.error(`macOS update permissions: ${error.message}`);
    process.exitCode = 1;
  }
}
