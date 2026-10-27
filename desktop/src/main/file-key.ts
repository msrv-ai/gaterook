// OS protection of the master key file (ARCHITECTURE.md A14, §7.1 "safeStorage async"; report 12 §7.4). The master
// keyset is encrypted under a 32-byte file key; the file key is kept by Electron's safeStorage (macOS Keychain,
// Windows DPAPI, Linux Secret Service or KWallet) in `master.keyset.osk` and reaches the core only on its stdin, in
// the bootstrap line. On by default wherever safeStorage works — on Linux not with the `basic_text` backend, which is
// no protection at all; there the key file stays 0400 and the UI says "OS protection unavailable".
//
// A cleartext keyset written before protection existed (or while it was off) is switched over at the next start:
// main makes a key, keeps it in safeStorage, and hands it to the core in the bootstrap — the core, given a file key
// and a cleartext file, rewrites the file encrypted while it loads (secrets.MasterKeyService). Not at runtime through
// `POST /internal/keyset/protect`: an answer lost after the core rewrote the file would leave main unsure whether to
// keep the key, and dropping it would lose the keyset.
import { randomBytes } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { writeFileAtomic } from './atomic-file.ts';

/** The part of Electron's `safeStorage` this needs (async API; a fake in tests). */
export interface SafeStorageLike {
  isAsyncEncryptionAvailable(): Promise<boolean>;
  encryptStringAsync(plainText: string): Promise<Buffer>;
  decryptStringAsync(encrypted: Buffer): Promise<{ result: string; shouldReEncrypt: boolean }>;
  /** Linux only: the password store Chromium picked. */
  getSelectedStorageBackend?(): string;
}

export const FILE_KEY_BYTES = 32;

/** An OS provider failed before local persistence; Electron caches that provider for this process. */
export class FileKeyAccessError extends Error {}

export function oskPath(home: string): string {
  return join(home, 'master.keyset.osk');
}

export function keysetPath(home: string): string {
  return join(home, 'master.keyset');
}

/** What the next core start gets. */
export interface FileKeyPlan {
  /** The file key for the bootstrap line (the caller zeroes it after writing the line), or null. */
  readonly fileKey: Buffer | null;
  /** The bootstrap's `protection`: `os`, `off`, `unavailable`, or the Linux backend's name. */
  readonly protection: string;
  /** Something the log should say (never the key). */
  readonly note: string | null;
  /** Native key access failed: the desktop must close instead of starting without protection. */
  readonly accessFailed?: true;
}

interface Availability {
  readonly available: boolean;
  /** `protection` while a key is in use. */
  readonly on: string;
  /** `protection` without a key though protection is possible. */
  readonly off: string;
  /** `protection` when this system can't protect the file. */
  readonly unavailable: string;
  readonly accessFailed?: true;
}

async function availability(safe: SafeStorageLike | null, platform: NodeJS.Platform): Promise<Availability> {
  if (!safe) return { available: false, on: 'off', off: 'off', unavailable: 'off' };
  let available = false;
  try {
    available = await safe.isAsyncEncryptionAvailable();
  } catch {
    available = false;
  }
  if (platform === 'linux') {
    const backend = normalizeBackend(safe.getSelectedStorageBackend?.() ?? 'unknown');
    const usable = available && backend !== 'basic_text' && backend !== 'unknown';
    return { available: usable, on: backend, off: 'off', unavailable: usable ? 'off' : backend,
      ...(!available && backend !== 'basic_text' && backend !== 'unknown' ? { accessFailed: true as const } : {}) };
  }
  return { available, on: 'os', off: 'off', unavailable: 'unavailable',
    ...(!available ? { accessFailed: true as const } : {}) };
}

/** The bootstrap accepts `[a-z0-9_]{1,32}`. */
function normalizeBackend(name: string): string {
  const n = name.toLowerCase().replace(/[^a-z0-9_]/g, '_').slice(0, 32);
  return n || 'unknown';
}

/**
 * Decides the file key of the next start. `safe` null means protection is off on purpose (a development run that
 * asked for it).
 */
export async function planFileKey(home: string, safe: SafeStorageLike | null,
    platform: NodeJS.Platform = process.platform): Promise<FileKeyPlan> {
  const osk = oskPath(home);
  const stored = existsSync(osk);
  // Decryption initializes Electron's async provider itself. On macOS and Windows, opening an existing key
  // needs only that operation; a separate availability probe can access the native store before the read and
  // is not proof that decryption is permitted. Linux still needs its backend check to reject basic_text.
  const a: Availability = stored && safe && (platform === 'darwin' || platform === 'win32')
    ? { available: true, on: 'os', off: 'off', unavailable: 'unavailable' }
    : await availability(safe, platform);
  if (stored) {
    if (!a.available || !safe) {
      return { fileKey: null, protection: a.unavailable, note: 'the key file is OS-protected, but OS protection is unavailable now',
        ...(safe ? { accessFailed: true as const } : {}) };
    }
    let result: string;
    let shouldReEncrypt: boolean;
    let key: Buffer;
    // Local I/O errors can be retried separately; they must not be mistaken for refusal of a native prompt.
    const encrypted = readFileSync(osk);
    try {
      ({ result, shouldReEncrypt } = await safe.decryptStringAsync(encrypted));
      key = Buffer.from(result, 'base64');
      if (key.length !== FILE_KEY_BYTES) throw new Error('the protected key has the wrong length');
    } catch (e) {
      // The OS store lost the key (a new Keychain, another account): the core can't open the keyset and says
      // MASTER_KEY_INVALID; restoring a backup is the way out. The .osk stays: the store may come back.
      return { fileKey: null, protection: a.off, note: `the protected file key can't be read (${(e as Error).message})`,
        ...(accessDenied(e) ? { accessFailed: true as const } : {}) };
    }
    let note: string | null = null;
    if (shouldReEncrypt) {
      // The store wants the key under its newer key; failing that, the key read now is still the right one.
      try {
        writeOwnerOnly(osk, await safe.encryptStringAsync(result));
      } catch (e) {
        note = `the file key couldn't be re-encrypted (${(e as Error).message}); it works as it is`;
      }
    }
    return { fileKey: key, protection: a.on, note };
  }
  if (!a.available || !safe) return { fileKey: null, protection: a.unavailable, note: null,
    ...(a.accessFailed ? { accessFailed: true as const } : {}) };
  const keyset = keysetPath(home);
  if (!existsSync(keyset)) {
    // No keyset yet (first start, or it is gone): the core creates it under this key.
    return { fileKey: await newProtectionKey(home, safe), protection: a.on, note: 'a new file key protects the master key' };
  }
  if (!encryptedKeyset(keyset)) {
    // A cleartext keyset (made while protection was off or unavailable): the core rewrites it under this key.
    return { fileKey: await newProtectionKey(home, safe), protection: a.on,
      note: 'the master key file is OS-protected from this start on' };
  }
  // Encrypted, but its key is gone with the .osk: nothing here opens it; the core says MASTER_KEY_INVALID.
  return { fileKey: null, protection: a.off, note: 'the master key file is encrypted, but its protected file key is missing' };
}

/** The core's own test (secrets.internal.crypto.KeysetFile): an OS-protected file is an `encryptedKeyset`. */
function encryptedKeyset(file: string): boolean {
  try {
    return readFileSync(file, 'utf8').includes('"encryptedKeyset"');
  } catch {
    return true; // unreadable: don't guess, don't make a key for it
  }
}

/**
 * A new file key, kept by safeStorage in `master.keyset.osk` (0600) before anyone uses it — on the disk, not only in
 * the page cache: the core encrypts the keyset under it right away.
 */
export async function newProtectionKey(home: string, safe: SafeStorageLike): Promise<Buffer> {
  const key = randomBytes(FILE_KEY_BYTES);
  try {
    let encrypted: Buffer;
    try {
      encrypted = await safe.encryptStringAsync(key.toString('base64'));
    } catch (error) {
      throw new FileKeyAccessError(error instanceof Error ? error.message : String(error));
    }
    writeOwnerOnly(oskPath(home), encrypted);
    return key;
  } catch (error) {
    key.fill(0);
    throw error;
  }
}

/** Native authentication failures close the app; lost keys and malformed ciphertext still use core recovery. */
function accessDenied(error: unknown): boolean {
  // Electron 45's async API distinguishes a temporarily unavailable provider from invalid ciphertext.
  return error instanceof Error && /temporarily unavailable|(?:encryption|decryption) is not available|denied|cancel|locked|not authorized|interaction.*(?:required|allowed)|auth(?:entication)? failed|errSecAuthFailed/i.test(error.message);
}

function writeOwnerOnly(file: string, bytes: Buffer): void {
  writeFileAtomic(file, bytes, 0o600);
}
