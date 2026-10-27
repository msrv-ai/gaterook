// Preserve the existing file key when moving from Electron safeStorage to the macOS unlock helper.
// Windows keeps DPAPI and adds Windows Hello verification. Linux retains its existing Secret Service backend.
import { randomBytes, randomUUID } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { writeFileAtomic } from './atomic-file.ts';
import { FILE_KEY_BYTES, FileKeyAccessError, keysetPath, oskPath, planFileKey,
  type FileKeyPlan, type SafeStorageLike } from './file-key.ts';
import { NativeUnlockError, type NativeCall } from './native-unlock.ts';

const FORMAT = 'gaterook-native-key-v1';
const ACCOUNT = /^[a-zA-Z0-9._-]{1,128}$/;
export type UnlockAuthentication = 'touch-id' | 'windows-hello' | 'system';
interface Reference { format: typeof FORMAT; account: string }
interface Options {
  home: string; platform: NodeJS.Platform; safe: SafeStorageLike; native: NativeCall;
  windowHandle: () => string; keychainService: string;
}

/** A recognized but damaged reference must never fall through to a different storage provider. */
function reference(home: string): Reference | null {
  const path = oskPath(home);
  if (!existsSync(path)) return null;
  const bytes = readFileSync(path);
  if (bytes[0] !== 0x7b) return null; // safeStorage ciphertext is binary, native references are JSON.
  try {
    const value: unknown = JSON.parse(bytes.toString('utf8'));
    if (!value || typeof value !== 'object' || !('format' in value) || value.format !== FORMAT
        || !('account' in value) || typeof value.account !== 'string' || !ACCOUNT.test(value.account)) throw new Error();
    return { format: FORMAT, account: value.account };
  } catch { throw new FileKeyAccessError('The native encryption key reference is invalid.'); }
}

export class OsFileKey {
  private available = false;
  private touchId = false;
  private initialized = false;
  private readonly o: Options;
  constructor(options: Options) { this.o = options; }

  /** This checks device capabilities only. It neither authenticates nor opens a key-store item. */
  async initialize(): Promise<void> {
    if (this.initialized) return;
    if (this.o.platform === 'darwin' || this.o.platform === 'win32') {
      const result = await this.o.native({ operation: 'check' });
      if (typeof result.available !== 'boolean') throw new NativeUnlockError('failed');
      this.available = result.available;
      this.touchId = result.touchId === true;
    }
    this.initialized = true;
  }

  details(): { authentication: UnlockAuthentication; keychainService?: string } {
    if (this.o.platform === 'win32' && this.available) return { authentication: 'windows-hello' };
    if (this.o.platform === 'darwin') {
      let legacy = false;
      try { legacy = existsSync(oskPath(this.o.home)) && !reference(this.o.home); } catch { /* load reports the error */ }
      if (legacy || !this.available) return { authentication: 'system', keychainService: this.o.keychainService };
      return { authentication: this.touchId ? 'touch-id' : 'system' };
    }
    return { authentication: 'system' };
  }

  async load(): Promise<FileKeyPlan> {
    await this.initialize();
    const { home, platform, safe, native } = this.o;
    const stored = reference(home);
    if (stored) {
      if (platform !== 'darwin') throw new FileKeyAccessError('This encryption key belongs to the macOS Keychain.');
      // Never downgrade authentication after cancellation, denial, or a missing helper. A lost Keychain item
      // enters the existing backup recovery flow, without creating a key for an encrypted keyset.
      let reply;
      try { reply = await native({ operation: 'get', account: stored.account }); }
      catch (error) {
        if (!(error instanceof NativeUnlockError) || error.code !== 'missing') throw error;
        const keyset = keysetPath(home);
        if (existsSync(keyset) && !readFileSync(keyset, 'utf8').includes('"encryptedKeyset"')) {
          // A restored backup is cleartext until its next desktop start; give it a new protected file key.
          return this.create();
        }
        return { fileKey: null, protection: 'off', note: 'the native encryption key is missing; restore a master-key backup' };
      }
      const key = Buffer.from(reply.key ?? '', 'base64');
      if (key.length !== FILE_KEY_BYTES || key.toString('base64') !== reply.key) {
        key.fill(0);
        throw new FileKeyAccessError('The native encryption key is invalid.');
      }
      return { fileKey: key, protection: 'os', note: null };
    }
    if (platform === 'win32' && this.available) {
      await native({ operation: 'verify', windowHandle: this.o.windowHandle() });
    }
    if (platform !== 'darwin' || !this.available) return planFileKey(home, safe, platform);

    if (existsSync(oskPath(home))) {
      // Authenticate once using the legacy provider, then save the same key. No extra biometric prompt during
      // migration. Keep the old ciphertext until the new Keychain entry has been stored successfully.
      const plan = await planFileKey(home, safe, platform);
      if (!plan.fileKey || plan.accessFailed) return plan;
      try {
        await this.store(plan.fileKey);
        return { ...plan, note: 'the encryption key now uses native macOS unlock' };
      } catch {
        // A failed migration cannot invalidate a working legacy key or expose the secret in an error message.
        return { ...plan, note: 'native unlock setup could not finish; the existing OS-protected key is retained' };
      }
    }
    const keyset = keysetPath(home);
    if (existsSync(keyset) && readFileSync(keyset, 'utf8').includes('"encryptedKeyset"')) {
      return { fileKey: null, protection: 'off', note: 'the master key file is encrypted, but its protected file key is missing' };
    }
    return this.create();
  }

  private async create(): Promise<FileKeyPlan> {
    const key = randomBytes(FILE_KEY_BYTES);
    try {
      await this.store(key, 'create');
      return { fileKey: key, protection: 'os', note: 'a native OS-protected file key protects the master key' };
    } catch (error) {
      key.fill(0);
      throw error; // local persistence errors remain retryable by the startup gate
    }
  }

  private async store(key: Buffer, operation: 'store' | 'create' = 'store'): Promise<void> {
    const account = randomUUID();
    await this.o.native({ operation, account, key: key.toString('base64') });
    writeFileAtomic(oskPath(this.o.home), JSON.stringify({ format: FORMAT, account }) + '\n', 0o600);
  }
}
