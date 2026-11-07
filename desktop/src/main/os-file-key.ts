// Preserve the existing file key when moving from Electron safeStorage to the macOS unlock helper.
// Windows keeps DPAPI and verifies the person first: with Windows Hello, or with the account password where Hello
// isn't set up. A file key first made on a Windows computer with Hello is bound to a Hello key instead.
// Linux retains its existing Secret Service backend.
import { createCipheriv, createDecipheriv, hkdfSync, randomBytes, randomUUID } from 'node:crypto';
import { existsSync, lstatSync, readFileSync } from 'node:fs';
import { writeFileAtomic } from './atomic-file.ts';
import { FILE_KEY_BYTES, FileKeyAccessError, keysetPath, oskPath, planFileKey,
  type FileKeyPlan, type SafeStorageLike } from './file-key.ts';
import { NativeUnlockError, type NativeCall } from './native-unlock.ts';

const FORMAT = 'gaterook-native-key-v1';
/**
 * A Windows file key wrapped with AES-256-GCM under HKDF-SHA256 of a Windows Hello key's signature of `salt`. The Hello
 * key signs only after the person's Hello gesture, and the same way each time (RSA PKCS#1 v1.5).
 */
const HELLO_FORMAT = 'gaterook-hello-key-v1';
const ACCOUNT = /^[a-zA-Z0-9._-]{1,128}$/;
const SALT_BYTES = 32;
const NONCE_BYTES = 12;
const TAG_BYTES = 16;
/** Far shorter than any RSA signature of a Hello key: such a reply is not a signature. */
const MIN_SIGNATURE_BYTES = 128;
export type UnlockAuthentication = 'touch-id' | 'mac-password' | 'windows-hello' | 'windows-password' | 'system';
interface NativeReference { format: typeof FORMAT; account: string }
interface HelloReference { format: typeof HELLO_FORMAT; account: string; salt: string; nonce: string; wrapped: string }
type Reference = NativeReference | HelloReference;
interface Options {
  home: string; platform: NodeJS.Platform; safe: SafeStorageLike; native: NativeCall;
  windowHandle: () => string; keychainService: string;
}

/** Canonical base64 of exactly `bytes` bytes. */
function decodes(value: unknown, bytes: number): value is string {
  if (typeof value !== 'string') return false;
  const decoded = Buffer.from(value, 'base64');
  return decoded.length === bytes && decoded.toString('base64') === value;
}

/** A recognized but damaged reference must never fall through to a different storage provider. */
function reference(home: string): Reference | null {
  const path = oskPath(home);
  if (!existsSync(path)) return null;
  const bytes = readFileSync(path);
  if (bytes[0] !== 0x7b) return null; // safeStorage ciphertext is binary, native references are JSON.
  try {
    const value: unknown = JSON.parse(bytes.toString('utf8'));
    if (!value || typeof value !== 'object' || !('account' in value) || typeof value.account !== 'string'
        || !ACCOUNT.test(value.account) || !('format' in value)) throw new Error();
    if (value.format === FORMAT) return { format: FORMAT, account: value.account };
    if (value.format === HELLO_FORMAT && 'salt' in value && 'nonce' in value && 'wrapped' in value
        && decodes(value.salt, SALT_BYTES) && decodes(value.nonce, NONCE_BYTES)
        && decodes(value.wrapped, FILE_KEY_BYTES + TAG_BYTES)) {
      return { format: HELLO_FORMAT, account: value.account, salt: value.salt, nonce: value.nonce, wrapped: value.wrapped };
    }
    throw new Error();
  } catch { throw new FileKeyAccessError('The native encryption key reference is invalid.'); }
}

/** The key that wraps a file key: HKDF-SHA256 of the Hello key's signature of the salt. */
function wrappingKey(signature: Buffer, salt: Buffer): Buffer {
  return Buffer.from(hkdfSync('sha256', signature, salt, 'gaterook file key v1', 32));
}

/** Binds a wrapped key to its reference's format and Hello key. */
function associated(account: string): Buffer {
  return Buffer.from(`${HELLO_FORMAT}:${account}`, 'utf8');
}

function wrapKey(key: Buffer, wrap: Buffer, account: string, nonce: Buffer): Buffer {
  const cipher = createCipheriv('aes-256-gcm', wrap, nonce, { authTagLength: TAG_BYTES });
  cipher.setAAD(associated(account));
  return Buffer.concat([cipher.update(key), cipher.final(), cipher.getAuthTag()]);
}

/** The file key, or null when this wrapping key doesn't open it: another Hello key, or a changed reference. */
function unwrapKey(stored: HelloReference, wrap: Buffer): Buffer | null {
  let key: Buffer | null = null;
  try {
    const wrapped = Buffer.from(stored.wrapped, 'base64');
    const decipher = createDecipheriv('aes-256-gcm', wrap, Buffer.from(stored.nonce, 'base64'),
      { authTagLength: TAG_BYTES });
    decipher.setAAD(associated(stored.account));
    decipher.setAuthTag(wrapped.subarray(FILE_KEY_BYTES));
    key = decipher.update(wrapped.subarray(0, FILE_KEY_BYTES));
    decipher.final();
    return key;
  } catch {
    key?.fill(0);
    return null;
  }
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
    if (this.o.platform === 'win32') {
      let hello = false;
      try { hello = reference(this.o.home)?.format === HELLO_FORMAT; } catch { /* load reports the error */ }
      if (hello) return { authentication: 'windows-hello' };
      if (!this.initialized) return { authentication: 'system' };
      return { authentication: this.available ? 'windows-hello' : 'windows-password' };
    }
    if (this.o.platform === 'darwin') {
      let legacy = false;
      try { legacy = existsSync(oskPath(this.o.home)) && !reference(this.o.home); } catch { /* load reports the error */ }
      if (legacy || !this.available) return { authentication: 'system', keychainService: this.o.keychainService };
      // LocalAuthentication's device-owner policy falls back to the Mac password without Touch ID.
      return { authentication: this.touchId ? 'touch-id' : 'mac-password' };
    }
    return { authentication: 'system' };
  }

  /** Remove only this home's native key. The reset caller removes local files after this succeeds. */
  async reset(): Promise<void> {
    if (this.o.platform === 'win32') return this.resetHello();
    if (this.o.platform !== 'darwin') return;
    const path = oskPath(this.o.home);
    if (existsSync(path) && !lstatSync(path).isFile()) {
      throw new FileKeyAccessError('The encryption key reference must be a regular file.');
    }
    const stored = reference(this.o.home);
    if (stored?.format === FORMAT) await this.o.native({ operation: 'delete', account: stored.account });
  }

  async load(): Promise<FileKeyPlan> {
    await this.initialize();
    const { home, platform, safe, native } = this.o;
    const stored = reference(home);
    if (stored?.format === HELLO_FORMAT) {
      if (platform !== 'win32') throw new FileKeyAccessError('This encryption key belongs to Windows Hello.');
      return this.openHello(stored);
    }
    if (stored) {
      if (platform !== 'darwin') throw new FileKeyAccessError('This encryption key belongs to the macOS Keychain.');
      // Never downgrade authentication after cancellation, denial, or a missing helper. A lost Keychain item
      // enters the existing backup recovery flow, without creating a key for an encrypted keyset.
      let reply;
      try { reply = await native({ operation: 'get', account: stored.account }); }
      catch (error) {
        if (!(error instanceof NativeUnlockError) || error.code !== 'missing') throw error;
        if (this.cleartextKeyset()) {
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
    if (platform === 'win32' && this.available && this.newVault()) {
      // The first file key on a computer with Hello is bound to a Hello key; making it takes the person's gesture.
      const created = await this.createHello();
      if (created) return created;
    }
    if (platform === 'win32') await this.verifyWindowsUser();
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
    if (!this.cleartextKeyset() && existsSync(keysetPath(home))) {
      return { fileKey: null, protection: 'off', note: 'the master key file is encrypted, but its protected file key is missing' };
    }
    return this.create();
  }

  /** No file key yet, and no keyset or a cleartext one: a key made now is the first to protect it. */
  private newVault(): boolean {
    return !existsSync(oskPath(this.o.home)) && (!existsSync(keysetPath(this.o.home)) || this.cleartextKeyset());
  }

  private cleartextKeyset(): boolean {
    const keyset = keysetPath(this.o.home);
    return existsSync(keyset) && !readFileSync(keyset, 'utf8').includes('"encryptedKeyset"');
  }

  /**
   * DPAPI opens the key for any process of the account, so main asks the person first: Windows Hello where it is
   * set up, else the account password in the system credential dialog. Hello that has stopped being available falls
   * back to the password, never to no check; a cancelled, denied or failed check leaves DPAPI unopened.
   */
  private async verifyWindowsUser(): Promise<void> {
    const windowHandle = this.o.windowHandle();
    if (this.available) {
      try {
        await this.o.native({ operation: 'verify', windowHandle });
        return;
      } catch (error) {
        if (!(error instanceof NativeUnlockError) || error.code !== 'unavailable') throw error;
        this.available = false;
      }
    }
    await this.o.native({ operation: 'verify-password', windowHandle });
  }

  /** The signature in a helper's reply, checked for shape: whether it opens anything, the file key's tag decides. */
  private static signature(reply: { signature?: string }): Buffer {
    const signature = Buffer.from(reply.signature ?? '', 'base64');
    if (signature.length < MIN_SIGNATURE_BYTES || signature.toString('base64') !== reply.signature) {
      signature.fill(0);
      throw new FileKeyAccessError('Windows Hello returned no signature.');
    }
    return signature;
  }

  /**
   * A new Hello key signs a new salt (the person's gesture), which derives the key that wraps a new file key. Null when
   * Hello can't make one: the vault then uses DPAPI after the usual check, with the account password when the person
   * chose it over Hello. A cancellation closes the app, as a cancelled check does.
   */
  private async createHello(): Promise<FileKeyPlan | null> {
    const account = `GateRook-${randomUUID()}`;
    const salt = randomBytes(SALT_BYTES);
    let signature: Buffer;
    try {
      signature = OsFileKey.signature(await this.o.native({ operation: 'hello-key-create', account,
        challenge: salt.toString('base64') }));
    } catch (error) {
      // Windows makes the key before it asks for the signature, so a failure can leave one behind.
      await this.forget(account);
      if (error instanceof NativeUnlockError && error.code === 'cancelled') throw error;
      if (error instanceof NativeUnlockError && error.code === 'denied') this.available = false;
      return null;
    }
    const key = randomBytes(FILE_KEY_BYTES);
    const wrap = wrappingKey(signature, salt);
    signature.fill(0);
    try {
      const nonce = randomBytes(NONCE_BYTES);
      const wrapped = wrapKey(key, wrap, account, nonce);
      writeFileAtomic(oskPath(this.o.home), JSON.stringify({ format: HELLO_FORMAT, account,
        salt: salt.toString('base64'), nonce: nonce.toString('base64'), wrapped: wrapped.toString('base64') }) + '\n',
      0o600);
      return { fileKey: key, protection: 'os', note: 'a Windows Hello key protects the master key' };
    } catch (error) {
      key.fill(0);
      await this.forget(account);
      throw error; // local persistence errors remain retryable by the startup gate
    } finally {
      wrap.fill(0);
    }
  }

  /**
   * The Hello key signs the salt again (the person's gesture), which derives the key that unwraps the file key. Never a
   * downgrade: a cancelled or refused gesture, or Hello being unavailable, closes the app and keeps everything for the
   * next start. Only a Hello key that is gone, or that no longer opens the file key, enters recovery.
   */
  private async openHello(stored: HelloReference): Promise<FileKeyPlan> {
    let signature: Buffer;
    try {
      signature = OsFileKey.signature(await this.o.native({ operation: 'hello-key-sign', account: stored.account,
        challenge: stored.salt }));
    } catch (error) {
      if (!(error instanceof NativeUnlockError) || error.code !== 'missing') throw error;
      return this.replaceHello('the Windows Hello key is missing');
    }
    const wrap = wrappingKey(signature, Buffer.from(stored.salt, 'base64'));
    signature.fill(0);
    const key = unwrapKey(stored, wrap);
    wrap.fill(0);
    if (key) return { fileKey: key, protection: 'os', note: null };
    return this.replaceHello('the Windows Hello key does not open the encryption key');
  }

  /**
   * A Hello reference whose key opens nothing. An encrypted keyset enters master-key backup recovery and the reference
   * stays; a cleartext one, such as a backup restored in that recovery, gets a new Hello key, or stays cleartext until
   * a later start can make one.
   */
  private async replaceHello(reason: string): Promise<FileKeyPlan> {
    if (!this.cleartextKeyset()) return { fileKey: null, protection: 'off', note: `${reason}; restore a master-key backup` };
    const created = this.available ? await this.createHello() : null;
    return created ?? { fileKey: null, protection: 'off',
      note: `${reason}; the master key file stays unprotected until Windows Hello can make a new key` };
  }

  /** A Hello key left behind opens nothing, so neither a damaged reference nor a failed deletion stops a reset. */
  private async resetHello(): Promise<void> {
    let stored: Reference | null;
    try {
      const path = oskPath(this.o.home);
      stored = existsSync(path) && lstatSync(path).isFile() ? reference(this.o.home) : null;
    } catch {
      return;
    }
    if (stored?.format === HELLO_FORMAT) await this.forget(stored.account);
  }

  /** Best effort: a Hello key that no reference names opens nothing; it only takes room. */
  private async forget(account: string): Promise<void> {
    try { await this.o.native({ operation: 'hello-key-delete', account }); } catch { /* see above */ }
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
