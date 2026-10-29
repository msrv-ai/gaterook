// Main's own files in the gateway home (desktop.json, master.keyset.osk): written whole or not at all, owner-only, and
// on the disk before the caller goes on. The order matters for the file key: the core rewrites the master keyset
// under the key as soon as it gets it, so the key must survive a power loss from that moment — a keyset whose key
// was lost with the page cache can't be opened again.
import { randomBytes } from 'node:crypto';
import { chmodSync, closeSync, fsyncSync, openSync, renameSync, rmSync, writeSync } from 'node:fs';
import { dirname } from 'node:path';

/**
 * Writes `data` to `file` through a new temporary file next to it (created exclusively: never one someone put there),
 * synced, then renamed over `file`; the folder is synced too, so the rename itself is on the disk.
 */
export function writeFileAtomic(file: string, data: string | Uint8Array, mode = 0o600): void {
  const tmp = `${file}.${process.pid}.${randomBytes(6).toString('hex')}.tmp`;
  const fd = openSync(tmp, 'wx', mode);
  try {
    try {
      const bytes = typeof data === 'string' ? Buffer.from(data, 'utf8') : data;
      for (let at = 0; at < bytes.length;) at += writeSync(fd, bytes, at, bytes.length - at);
      fsyncSync(fd);
    } finally {
      closeSync(fd);
    }
    if (process.platform !== 'win32') chmodSync(tmp, mode);
    renameSync(tmp, file);
  } catch (e) {
    rmSync(tmp, { force: true });
    throw e;
  }
  syncFolder(dirname(file));
}

/** The folder's entry for a renamed file reaches the disk (POSIX; Windows has no fsync of a folder). */
export function syncFolder(dir: string): void {
  if (process.platform === 'win32') return;
  let fd: number | null = null;
  try {
    fd = openSync(dir, 'r');
    fsyncSync(fd);
  } catch {
    // A file system that can't sync a folder: the rename is as durable as it gets there.
  } finally {
    if (fd !== null) closeSync(fd);
  }
}
