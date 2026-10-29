// A bounded, uncompressed container. Only named application data can enter or leave it; no archive extractor runs.
import { chmodSync, constants, closeSync, fstatSync, lstatSync, mkdirSync, openSync, readSync, readdirSync, realpathSync } from 'node:fs';
import { basename, dirname, join, resolve, sep } from 'node:path';
import { writeFileAtomic } from './atomic-file.ts';
import { MAX_BACKUP_BYTES, MAX_BACKUP_PAYLOAD_BYTES } from './full-backup-archive.ts';

export const BACKUP_DIRECTORIES = ['plugins', 'plugin-data', 'connectors'] as const;
export const RESTORE_ENTRIES = ['gateway.db', 'gateway.db-wal', 'gateway.db-shm', 'gateway.db-journal',
  'master.keyset', 'desktop.json', ...BACKUP_DIRECTORIES] as const;
const MAX_FILES = 20_000;
const MAX_MANIFEST = 4 * 1024 * 1024;
interface Entry { path: string; size: number }
interface Manifest { format: 'gaterook/full-backup/1'; createdAt: string; version: string; keyset: string; files: Entry[] }
export interface BackupPayload { keyset: string; createdAt: string; version: string }

export function privateDirectory(path: string): void {
  mkdirSync(path, { recursive: true, mode: 0o700 });
  if (lstatSync(path).isSymbolicLink() || !lstatSync(path).isDirectory() || realpathSync(path) !== resolve(path)) {
    throw new Error('The backup data directory must not contain symbolic links.');
  }
  if (process.platform !== 'win32') chmodSync(path, 0o700);
}

/** Reject links, devices and oversized files before allocating or reading. */
export function readBackupFile(file: string, limit = MAX_BACKUP_BYTES): Buffer {
  if (!Number.isSafeInteger(limit) || limit < 0 || limit > MAX_BACKUP_BYTES) throw new Error('Invalid backup size limit.');
  if (!lstatSync(file).isFile() || lstatSync(file).isSymbolicLink()) throw new Error('Choose a regular backup file.');
  const fd = openSync(file, constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0));
  try {
    const stat = fstatSync(fd);
    if (!stat.isFile() || stat.size > limit) throw new Error('The backup exceeds the supported size limit.');
    const result = Buffer.allocUnsafe(stat.size);
    try {
      for (let offset = 0; offset < result.length;) {
        const count = readSync(fd, result, offset, result.length - offset, offset);
        if (count === 0) throw new Error('The backup file changed while it was being read. Try again.');
        offset += count;
      }
      if (fstatSync(fd).size !== stat.size) throw new Error('The backup file changed while it was being read. Try again.');
      return result;
    } catch (error) { result.fill(0); throw error; }
  } finally { closeSync(fd); }
}

function allowedPath(path: string): boolean {
  if (path === 'gateway.db' || path === 'desktop.json') return true;
  if (!BACKUP_DIRECTORIES.some((dir) => path.startsWith(`${dir}/`))) return false;
  // A portable subset avoids traversal, Windows device names, alternate streams and normalization collisions.
  return path.length <= 1024 && path.split('/').every((part) => part.length > 0 && part.length <= 255
    && part !== '.' && part !== '..' && !/[\\:<>"|?*\x00-\x1f\x7f]/.test(part) && !/[. ]$/.test(part)
    && !/^(con|prn|aux|nul|com[0-9¹²³]|lpt[0-9¹²³])(?:\.|$)/i.test(part));
}

function validateFiles(files: Entry[]): number {
  let total = 0;
  const names = new Set<string>();
  for (const entry of files) {
    if (!entry || typeof entry.path !== 'string' || !allowedPath(entry.path) || !Number.isSafeInteger(entry.size)
        || entry.size < 0 || entry.size > MAX_BACKUP_PAYLOAD_BYTES) throw new Error('Invalid file in full backup.');
    const name = entry.path.normalize('NFC').toLowerCase();
    if (names.has(name)) throw new Error('Duplicate file in full backup.');
    names.add(name);
    total += entry.size;
    if (total > MAX_BACKUP_PAYLOAD_BYTES) throw new Error('The backup exceeds the supported size limit.');
  }
  if (!names.has('gateway.db') || !names.has('desktop.json')) throw new Error('The full backup is incomplete.');
  for (const name of names) {
    const parts = name.split('/');
    for (let i = 1; i < parts.length; i++) if (names.has(parts.slice(0, i).join('/'))) throw new Error('Conflicting backup file paths.');
  }
  return total;
}

export function packBackup(home: string, snapshot: string, keyset: string, version: string): Buffer {
  const files: Entry[] = [];
  const buffers: Buffer[] = [];
  let bytes = 0;
  const add = (path: string, file: string): void => {
    if (!allowedPath(path) || files.length >= MAX_FILES) throw new Error('The backup contains an unsupported file name or too many files.');
    const data = readBackupFile(file, MAX_BACKUP_PAYLOAD_BYTES - bytes);
    bytes += data.length;
    buffers.push(data);
    files.push({ path, size: data.length });
  };
  const walk = (path: string): void => {
    if (path.length > 1024) throw new Error('The backup contains an unsupported file name.');
    const full = join(home, path);
    const stat = lstatSync(full);
    if (stat.isSymbolicLink()) throw new Error('Full backup cannot include symbolic links. Move linked data into GateRook first.');
    if (stat.isDirectory()) {
      for (const name of readdirSync(full).sort()) {
        // Plugin temporary work and incomplete installations are not recovery data.
        if (name === '.staging' || (path.startsWith('plugin-data/') && name === 'tmp')) continue;
        walk(`${path}/${name}`);
      }
    } else add(path, full);
  };
  try {
    add('gateway.db', join(snapshot, 'gateway.db'));
    add('desktop.json', join(home, 'desktop.json'));
    for (const dir of BACKUP_DIRECTORIES) {
      try { lstatSync(join(home, dir)); } catch (e) { if ((e as NodeJS.ErrnoException).code === 'ENOENT') continue; throw e; }
      walk(dir);
    }
    const manifest: Manifest = { format: 'gaterook/full-backup/1', createdAt: new Date().toISOString(), version, keyset, files };
    validateFiles(files);
    const meta = Buffer.from(JSON.stringify(manifest));
    try {
      if (meta.length > MAX_MANIFEST || bytes + meta.length + 4 > MAX_BACKUP_PAYLOAD_BYTES) throw new Error('The backup exceeds the supported size limit.');
      const length = Buffer.alloc(4); length.writeUInt32BE(meta.length);
      return Buffer.concat([length, meta, ...buffers]);
    } finally { meta.fill(0); }
  } finally { for (const buffer of buffers) buffer.fill(0); }
}

/** Authentication must have succeeded before this function is called. Validate the entire index before writing. */
export function unpackBackup(payload: Buffer, directory: string): BackupPayload {
  if (payload.length < 4 || payload.length > MAX_BACKUP_PAYLOAD_BYTES) throw new Error('Invalid full backup.');
  const length = payload.readUInt32BE();
  if (length > MAX_MANIFEST || length > payload.length - 4) throw new Error('Invalid full backup manifest.');
  const manifest = JSON.parse(payload.subarray(4, 4 + length).toString('utf8')) as Partial<Manifest>;
  if (!manifest || manifest.format !== 'gaterook/full-backup/1' || typeof manifest.keyset !== 'string'
      || manifest.keyset.length === 0 || manifest.keyset.length > 256 * 1024 || typeof manifest.createdAt !== 'string'
      || !Number.isFinite(Date.parse(manifest.createdAt)) || typeof manifest.version !== 'string'
      || !Array.isArray(manifest.files) || manifest.files.length > MAX_FILES) throw new Error('Unsupported full backup format.');
  if (4 + length + validateFiles(manifest.files) !== payload.length) throw new Error('The full backup is incomplete.');
  privateDirectory(directory);
  if (readdirSync(directory).length !== 0) throw new Error('Restore requires an empty staging directory.');
  let offset = 4 + length;
  for (const entry of manifest.files) {
    const target = join(directory, ...entry.path.split('/'));
    privateDirectory(dirname(target));
    writeFileAtomic(target, payload.subarray(offset, offset + entry.size));
    offset += entry.size;
  }
  return { keyset: manifest.keyset, createdAt: manifest.createdAt, version: manifest.version };
}

/** The save dialog cannot overwrite a live application file or place this secret-bearing archive in its home. */
export function validateBackupDestination(home: string, file: string): string {
  if (!file.endsWith('.grbackup')) throw new Error('Use the .grbackup file extension.');
  const parent = realpathSync(dirname(file));
  const target = join(parent, basename(file));
  const root = realpathSync(home);
  const fold = (path: string): string => process.platform === 'linux' ? path : path.normalize('NFC').toLowerCase();
  if (fold(target) === fold(root) || fold(target).startsWith(fold(root) + sep)) throw new Error('Save the full backup outside the GateRook data folder.');
  try { if (!lstatSync(target).isFile() || lstatSync(target).isSymbolicLink()) throw new Error('Choose a regular destination file.'); }
  catch (e) { if ((e as NodeJS.ErrnoException).code !== 'ENOENT') throw e; }
  return target;
}
