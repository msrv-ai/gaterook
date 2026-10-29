// Main-only orchestration: native file selection, portable encrypted data, and offline replacement.
import type { SaveDialogOptions, SaveDialogReturnValue, OpenDialogOptions, OpenDialogReturnValue, MessageBoxOptions, MessageBoxReturnValue } from 'electron';
import { mkdtempSync, realpathSync, rmSync } from 'node:fs';
import { basename, join } from 'node:path';
import { createHash } from 'node:crypto';
import { writeFileAtomic } from './atomic-file.ts';
import { callCoreOk } from './core-client.ts';
import type { ReadyCore } from './core-supervisor.ts';
import { encryptFullBackup, decryptFullBackup, validateBackupPassphrase } from './full-backup-archive.ts';
import { packBackup, privateDirectory, readBackupFile, unpackBackup, validateBackupDestination } from './full-backup-files.ts';
import { installFullRestore } from './full-backup-restore.ts';
import { readDesktopConfig, writeDesktopConfig } from './desktop-config.ts';

export interface RestoreContext {
  home: string;
  core: ReadyCore;
  current: () => ReadyCore | null;
  close: () => Promise<void>;
}
export interface FullBackupHost {
  home: string;
  version: string;
  saveDialog: (options: SaveDialogOptions) => Promise<SaveDialogReturnValue>;
  openDialog: (options: OpenDialogOptions) => Promise<OpenDialogReturnValue>;
  confirmRestore: (options: MessageBoxOptions) => Promise<MessageBoxReturnValue>;
  core: () => ReadyCore | null;
  pause: () => Promise<void>;
  resume: () => Promise<void>;
  stop: () => Promise<void>;
  restored: () => Promise<void>;
  restoreContext?: () => Promise<RestoreContext>;
}
const filters = [{ name: 'Encrypted GateRook full backup', extensions: ['grbackup'] }];

function staging(home: string): string {
  privateDirectory(home);
  privateDirectory(join(home, 'tmp'));
  return mkdtempSync(join(home, 'tmp', 'full-backup-'));
}
function ready(host: FullBackupHost, unlocked: boolean): ReadyCore {
  const core = host.core();
  if (!core || (unlocked && core.locked)) throw new Error('Wait for GateRook to start and unlock its master key, then try again.');
  return core;
}

export async function createFullBackup(host: FullBackupHost, passphrase: unknown): Promise<{ cancelled: boolean; fileName?: string }> {
  validateBackupPassphrase(passphrase);
  const core = ready(host, true);
  const options = { title: 'Create encrypted full backup', filters,
    defaultPath: `gaterook-${new Date().toISOString().slice(0, 10)}.grbackup`,
    properties: ['createDirectory' as const, 'showOverwriteConfirmation' as const] };
  const chosen = await host.saveDialog(options);
  if (chosen.canceled || !chosen.filePath) return { cancelled: true };
  const home = realpathSync(host.home);
  const destination = validateBackupDestination(home, chosen.filePath);
  const directory = staging(home);
  let payload: Buffer | null = null;
  let encrypted: Buffer | null = null;
  let paused = false;
  try {
    const snapshot = await callCoreOk<{ keyset: string; pluginFiles: { path: string; sha256: string }[] }>(core, 'POST', '/internal/full-backup/snapshot', { directory }, 120_000);
    if (host.core()?.instanceNonce !== core.instanceNonce) throw new Error('GateRook restarted during backup. Try again.');
    paused = true;
    await host.pause();
    for (const entry of snapshot.pluginFiles) {
      if (!/^plugins\/[A-Za-z0-9._-]+\/[A-Za-z0-9._+-]+\/plugin\.jar$/.test(entry.path)
          || entry.path.split('/').some((part) => part === '..' || part === '.')) throw new Error('The core returned an invalid plugin file.');
      const file = readBackupFile(join(home, entry.path));
      try {
        if (createHash('sha256').update(file).digest('hex') !== entry.sha256) {
          throw new Error('A plugin changed during backup. Retry after plugin operations finish.');
        }
      } finally { file.fill(0); }
    }
    payload = packBackup(home, directory, snapshot.keyset, host.version);
    snapshot.keyset = '';
    encrypted = await encryptFullBackup(payload, passphrase);
    writeFileAtomic(destination, encrypted);
    return { cancelled: false, fileName: basename(destination) };
  } finally {
    payload?.fill(0);
    encrypted?.fill(0);
    try { rmSync(directory, { recursive: true, force: true }); }
    finally { if (paused) await host.resume(); }
  }
}

export async function restoreFullBackup(host: FullBackupHost, passphrase: unknown, confirmation: unknown): Promise<{ cancelled: boolean }> {
  validateBackupPassphrase(passphrase);
  if (confirmation !== 'RESTORE') throw new Error('Type RESTORE to confirm replacing this gateway.');
  const options = { title: 'Restore encrypted full backup', filters, properties: ['openFile' as const] };
  const chosen = await host.openDialog(options);
  if (chosen.canceled || !chosen.filePaths[0]) return { cancelled: true };
  const home = realpathSync(host.home);
  let directory: string | null = null;
  let context: RestoreContext | null = null;
  let payload: Buffer | null = null;
  let encrypted: Buffer | null = null;
  try {
    encrypted = readBackupFile(chosen.filePaths[0]);
    payload = await decryptFullBackup(encrypted, passphrase);
    encrypted.fill(0); encrypted = null;
    context = host.restoreContext ? await host.restoreContext()
      : { home, core: ready(host, false), current: host.core, close: async () => undefined };
    const core = context.core;
    directory = staging(realpathSync(context.home));
    const metadata = unpackBackup(payload, directory);
    payload.fill(0); payload = null;
    const validated = await callCoreOk<{ keyset: string }>(core, 'POST', '/internal/full-backup/validate',
      { directory, keyset: metadata.keyset }, 120_000);
    metadata.keyset = '';
    const keyset = Buffer.from(validated.keyset, 'base64');
    validated.keyset = '';
    try { writeFileAtomic(join(directory, 'master.keyset'), keyset, 0o400); }
    finally { keyset.fill(0); }
    // Validate/sanitize desktop settings. Keep this computer's ports to avoid a restored port conflict.
    const settings = readDesktopConfig(directory);
    if (settings.problem) throw new Error('The full backup contains invalid desktop settings.');
    const local = readDesktopConfig(home).config;
    writeDesktopConfig(directory, { ...settings.config, port: local.port, publicPort: local.publicPort,
      openAtLogin: false, startHidden: false });
    const review = { type: 'warning' as const, title: 'Replace GateRook from this backup?',
      message: `Restore ${basename(chosen.filePaths[0])}?`,
      detail: `Backup created ${new Date(metadata.createdAt).toISOString()}.\n\nThis replaces local gateway data and closes GateRook. AI app access will be revoked. Review restored plugins and external files before enabling them.`,
      buttons: ['Cancel', 'Replace and close GateRook'], defaultId: 0, cancelId: 0, noLink: true };
    const answer = await host.confirmRestore(review);
    if (answer.response !== 1) return { cancelled: true };
    if (context.current()?.instanceNonce !== core.instanceNonce) throw new Error('GateRook restarted during validation. Try restoring again.');
    await host.stop();
    installFullRestore(home, directory);
    await host.restored();
    return { cancelled: false };
  } finally {
    payload?.fill(0);
    encrypted?.fill(0);
    try { if (directory) rmSync(directory, { recursive: true, force: true }); }
    finally { await context?.close(); }
  }
}
