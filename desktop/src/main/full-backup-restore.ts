// A crash during replacement rolls back before any core can open the database. Journal paths are never supplied by an archive.
import { chmodSync, closeSync, constants, cpSync, existsSync, fsyncSync, lstatSync, mkdirSync, openSync, readdirSync, renameSync, rmSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { writeFileAtomic, syncFolder } from './atomic-file.ts';
import { privateDirectory, readBackupFile, RESTORE_ENTRIES } from './full-backup-files.ts';

const TRANSACTION = '.full-restore';
interface Journal { version: 1; state: 'prepared' | 'committed' | 'rolled-back'; original: string[] }

function assertTree(path: string): void {
  const stat = lstatSync(path);
  if (stat.isSymbolicLink() || (!stat.isFile() && !stat.isDirectory())) throw new Error('Restore cannot replace linked or special application files.');
  if (stat.isDirectory()) for (const name of readdirSync(path)) assertTree(join(path, name));
}

/** A copied rollback file must reach durable storage before its only durable original is removed. */
function syncTree(path: string): void {
  const stat = lstatSync(path);
  if (stat.isSymbolicLink() || (!stat.isFile() && !stat.isDirectory())) throw new Error('Restore cannot sync linked or special application files.');
  if (stat.isDirectory()) {
    for (const name of readdirSync(path)) syncTree(join(path, name));
    syncFolder(path);
  } else {
    // Windows FlushFileBuffers requires a writable handle. Changing the read-only attribute does not alter ACLs.
    const windows = process.platform === 'win32';
    const mode = stat.mode & 0o777;
    const restoreReadOnly = windows && (mode & 0o200) === 0;
    if (restoreReadOnly) chmodSync(path, mode | 0o200);
    let fd: number | null = null;
    try {
      fd = openSync(path, (windows ? constants.O_RDWR : constants.O_RDONLY) | (constants.O_NOFOLLOW ?? 0));
      fsyncSync(fd);
    } finally {
      if (fd !== null) closeSync(fd);
      if (restoreReadOnly) chmodSync(path, mode);
    }
  }
}

export function pendingFullRestore(home: string): boolean { return existsSync(join(home, TRANSACTION)); }

/** Called after the old core has exited, before startup, and on failure. Recovery can itself be interrupted. */
export function recoverFullRestore(home: string): void {
  const root = join(home, TRANSACTION);
  if (!pendingFullRestore(home)) return;
  privateDirectory(home);
  privateDirectory(root);
  const journalFile = join(root, 'journal.json');
  // No live paths change until a complete durable journal exists.
  if (!existsSync(journalFile)) { rmSync(root, { recursive: true }); syncFolder(home); return; }
  const raw: unknown = JSON.parse(readBackupFile(journalFile, 16 * 1024).toString('utf8'));
  const journal = raw as Journal;
  if (!journal || journal.version !== 1 || !['prepared', 'committed', 'rolled-back'].includes(journal.state)
      || !Array.isArray(journal.original) || new Set(journal.original).size !== journal.original.length
      || journal.original.some((name) => !RESTORE_ENTRIES.includes(name as typeof RESTORE_ENTRIES[number]))) {
    throw new Error('The full restore recovery journal is invalid. Keep this data folder intact for recovery.');
  }
  if (journal.state === 'prepared') {
    if (!existsSync(join(root, 'old'))) throw new Error('The full restore recovery files are missing. Keep this data folder intact for recovery.');
    privateDirectory(join(root, 'old'));
    for (const name of RESTORE_ENTRIES) {
      const old = join(root, 'old', name);
      if (existsSync(old)) {
        if (!journal.original.includes(name)) throw new Error('The full restore recovery files do not match the journal.');
        assertTree(old);
      } else if (journal.original.includes(name) && !existsSync(join(home, name))) {
        throw new Error('An original application file is missing. Keep this data folder intact for recovery.');
      }
    }
    for (const name of RESTORE_ENTRIES) {
      const old = join(root, 'old', name);
      const live = join(home, name);
      if (existsSync(old)) {
        // Retain the originals until every rollback step is complete; a second crash repeats this copy safely.
        rmSync(live, { recursive: true, force: true });
        cpSync(old, live, { recursive: true, errorOnExist: true, force: false });
        syncTree(live);
      } else if (!journal.original.includes(name)) rmSync(live, { recursive: true, force: true });
    }
    syncFolder(home);
    writeFileAtomic(journalFile, JSON.stringify({ ...journal, state: 'rolled-back' }));
  }
  // Removing the journal first prevents an interrupted cleanup from interpreting incomplete originals as a rollback.
  rmSync(journalFile);
  syncFolder(root);
  rmSync(root, { recursive: true });
  syncFolder(home);
}

/** The caller has authenticated, validated, confirmed, and stopped the core and broker. */
export function installFullRestore(home: string, staged: string, afterMove?: (name: string) => void): void {
  privateDirectory(home);
  privateDirectory(staged);
  if (resolve(staged) === resolve(home)) throw new Error('Restore staging must be separate from the live application data.');
  if (pendingFullRestore(home)) throw new Error('An earlier restore needs recovery first.');
  for (const name of ['gateway.db', 'master.keyset', 'desktop.json']) {
    if (!existsSync(join(staged, name)) || !lstatSync(join(staged, name)).isFile()) throw new Error('The validated restore data is incomplete.');
  }
  for (const name of RESTORE_ENTRIES) {
    if (existsSync(join(home, name))) assertTree(join(home, name));
    if (existsSync(join(staged, name))) assertTree(join(staged, name));
  }
  // Persist both the rollback originals and the validated replacement before recording an install intent.
  for (const name of RESTORE_ENTRIES) {
    if (existsSync(join(home, name))) syncTree(join(home, name));
    if (existsSync(join(staged, name))) syncTree(join(staged, name));
  }
  syncFolder(home);
  syncFolder(staged);
  const root = join(home, TRANSACTION);
  mkdirSync(root, { mode: 0o700 });
  mkdirSync(join(root, 'old'), { mode: 0o700 });
  syncFolder(root);
  syncFolder(home);
  const journal: Journal = { version: 1, state: 'prepared', original: RESTORE_ENTRIES.filter((name) => existsSync(join(home, name))) };
  writeFileAtomic(join(root, 'journal.json'), JSON.stringify(journal));
  try {
    for (const name of RESTORE_ENTRIES) {
      if (journal.original.includes(name)) {
        renameSync(join(home, name), join(root, 'old', name));
        syncFolder(join(root, 'old'));
        syncFolder(home);
      }
      if (existsSync(join(staged, name))) {
        renameSync(join(staged, name), join(home, name));
        syncFolder(staged);
        syncFolder(home);
      }
      afterMove?.(name);
    }
    writeFileAtomic(join(root, 'journal.json'), JSON.stringify({ ...journal, state: 'committed' }));
  } catch (error) {
    recoverFullRestore(home);
    throw error;
  }
  recoverFullRestore(home);
}
