import assert from 'node:assert/strict';
import { createHmac, randomBytes } from 'node:crypto';
import { existsSync, mkdtempSync, readFileSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, it } from 'node:test';
import { FileKeyAccessError, keysetPath, oskPath, type SafeStorageLike } from '../src/main/file-key.ts';
import { NativeUnlockError, type NativeRequest, type NativeReply } from '../src/main/native-unlock.ts';
import { OsFileKey } from '../src/main/os-file-key.ts';

const homes: string[] = [];
afterEach(() => { for (const home of homes.splice(0)) rmSync(home, { recursive: true, force: true }); });
const key = Buffer.alloc(32, 71);

function setup(platform: NodeJS.Platform = 'darwin', available = true, touchId = available && platform === 'darwin') {
  const home = mkdtempSync(join(tmpdir(), 'gaterook-native-key-'));
  homes.push(home);
  const calls: NativeRequest[] = [];
  const safeCalls: string[] = [];
  const saved = new Map<string, string>();
  let failure: { operation: NativeRequest['operation']; code: string } | null = null;
  // A Hello key signs deterministically, and only its own secret makes its signatures (RSA-2048: 256 bytes).
  const helloKeys = new Map<string, Buffer>();
  let shortSignatures = false;
  const sign = (account: string, challenge: string): string => shortSignatures ? 'AAAA' : Buffer.concat(
    Array.from({ length: 8 }, (_, i) => createHmac('sha256', helloKeys.get(account)!).update(`${i}:${challenge}`).digest()),
  ).toString('base64');
  const safe: SafeStorageLike = {
    isAsyncEncryptionAvailable: async () => { safeCalls.push('available'); return true; },
    encryptStringAsync: async (text) => { safeCalls.push('encrypt'); return Buffer.from('safe:' + text); },
    decryptStringAsync: async (bytes) => { safeCalls.push('decrypt'); return { result: bytes.toString().slice(5), shouldReEncrypt: false }; },
    getSelectedStorageBackend: () => 'gnome_libsecret',
  };
  const native = async (request: NativeRequest): Promise<NativeReply> => {
    calls.push(request);
    if (failure?.operation === request.operation) throw new NativeUnlockError(failure.code);
    switch (request.operation) {
      case 'check': return { ok: true, available, touchId };
      case 'verify': case 'verify-password': return { ok: true };
      case 'delete': saved.delete(request.account!); return { ok: true };
      case 'store': case 'create': saved.set(request.account!, request.key!); return { ok: true };
      case 'get': {
        const value = saved.get(request.account!);
        if (!value) throw new NativeUnlockError('missing');
        return { ok: true, key: value };
      }
      case 'hello-key-create':
        helloKeys.set(request.account!, randomBytes(32));
        return { ok: true, signature: sign(request.account!, request.challenge!) };
      case 'hello-key-sign':
        if (!helloKeys.has(request.account!)) throw new NativeUnlockError('missing');
        return { ok: true, signature: sign(request.account!, request.challenge!) };
      case 'hello-key-delete': helloKeys.delete(request.account!); return { ok: true };
    }
  };
  const options = { home, platform, native, safe, windowHandle: () => '1234', keychainService: 'GateRook Dev Safe Storage' };
  return { home, calls, safeCalls, saved, helloKeys, options, manager: new OsFileKey(options),
    fail: (operation: NativeRequest['operation'], code: string) => { failure = { operation, code }; },
    heal: () => { failure = null; },
    shortSignatures: () => { shortSignatures = true; } };
}

describe('native startup file keys', () => {
  it('checks capabilities without authentication, then creates a protected key in one native operation', async () => {
    const s = setup();
    await s.manager.initialize();
    await s.manager.initialize();
    assert.deepEqual(s.calls, [{ operation: 'check' }]);
    assert.deepEqual(s.manager.details(), { authentication: 'touch-id' });
    const plan = await s.manager.load();
    assert.equal(plan.fileKey?.length, 32);
    assert.equal(plan.protection, 'os');
    assert.deepEqual(s.calls.map((c) => c.operation), ['check', 'create']);
    assert.deepEqual(s.safeCalls, []);
    const text = readFileSync(oskPath(s.home), 'utf8');
    const ref = JSON.parse(text);
    assert.equal(ref.format, 'gaterook-native-key-v1');
    assert.equal(s.saved.get(ref.account), plan.fileKey!.toString('base64'));
    assert.ok(!text.includes(plan.fileKey!.toString('base64')));
    if (process.platform !== 'win32') assert.equal(statSync(oskPath(s.home)).mode & 0o777, 0o600);
  });

  it('migrates the exact existing key with one legacy decrypt and no extra biometric request', async () => {
    const s = setup();
    writeFileSync(oskPath(s.home), 'safe:' + key.toString('base64'));
    writeFileSync(keysetPath(s.home), '{"encryptedKeyset":"unchanged"}');
    await s.manager.initialize();
    assert.deepEqual(s.manager.details(), { authentication: 'system', keychainService: 'GateRook Dev Safe Storage' });
    assert.deepEqual((await s.manager.load()).fileKey, key);
    assert.deepEqual(s.safeCalls, ['decrypt']);
    assert.deepEqual(s.calls.map((c) => c.operation), ['check', 'store']);
    assert.equal(readFileSync(keysetPath(s.home), 'utf8'), '{"encryptedKeyset":"unchanged"}');
    s.calls.length = 0;
    s.safeCalls.length = 0;
    const nextLaunch = new OsFileKey(s.options);
    await nextLaunch.initialize();
    assert.deepEqual((await nextLaunch.load()).fileKey, key);
    assert.deepEqual(s.calls.map((c) => c.operation), ['check', 'get']);
    assert.deepEqual(s.safeCalls, [], 'a migrated vault never opens Electron safeStorage');
  });

  it('retains the legacy ciphertext and working key when migration cannot persist the native item', async () => {
    const s = setup();
    const legacy = 'safe:' + key.toString('base64');
    writeFileSync(oskPath(s.home), legacy);
    s.fail('store', 'denied');
    await s.manager.initialize();
    const plan = await s.manager.load();
    assert.deepEqual(plan.fileKey, key);
    assert.equal(readFileSync(oskPath(s.home), 'utf8'), legacy);
    assert.match(plan.note!, /retained/);
    assert.ok(!plan.note!.includes(key.toString('base64')));
  });

  for (const failure of ['cancelled', 'denied', 'unavailable', 'failed']) {
    it(`does not fall back to legacy access after native ${failure}`, async () => {
      const s = setup();
      await s.manager.initialize();
      await s.manager.load();
      s.fail('get', failure);
      await assert.rejects(s.manager.load(), NativeUnlockError);
      assert.deepEqual(s.safeCalls, []);
    });
  }

  it('keeps missing native keys recoverable and protects a restored cleartext keyset on the next start', async () => {
    const s = setup();
    await s.manager.initialize();
    await s.manager.load();
    s.saved.clear();
    writeFileSync(keysetPath(s.home), '{"encryptedKeyset":"old"}');
    const recovery = await s.manager.load();
    assert.equal(recovery.fileKey, null);
    assert.match(recovery.note!, /backup/);
    writeFileSync(keysetPath(s.home), '{"key":["restored"]}');
    assert.equal((await s.manager.load()).fileKey?.length, 32);
    assert.equal(s.calls.at(-1)?.operation, 'create');
    assert.deepEqual(s.safeCalls, []);
  });

  it('never creates a replacement for an encrypted keyset with its file key missing', async () => {
    const s = setup();
    writeFileSync(keysetPath(s.home), '{"encryptedKeyset":"old"}');
    await s.manager.initialize();
    assert.equal((await s.manager.load()).fileKey, null);
    assert.deepEqual(s.calls.map((c) => c.operation), ['check']);
  });

  it('rejects corrupt references and invalid native keys without a storage downgrade', async () => {
    const s = setup();
    writeFileSync(oskPath(s.home), '{"format":"gaterook-native-key-v1"}');
    await assert.rejects(s.manager.load(), FileKeyAccessError);
    writeFileSync(oskPath(s.home), JSON.stringify({ format: 'gaterook-native-key-v1', account: 'test' }));
    s.saved.set('test', 'broken');
    await assert.rejects(s.manager.load(), FileKeyAccessError);
    assert.deepEqual(s.safeCalls, []);
  });

  it('does not create a local reference after cancelled enrollment', async () => {
    const s = setup();
    await s.manager.initialize();
    s.fail('create', 'cancelled');
    await assert.rejects(s.manager.load(), NativeUnlockError);
    assert.throws(() => readFileSync(oskPath(s.home)), { code: 'ENOENT' });
    assert.deepEqual(s.safeCalls, []);
  });

  it('uses Windows Hello once with the owner HWND before releasing the DPAPI key', async () => {
    const s = setup('win32');
    writeFileSync(oskPath(s.home), 'safe:' + key.toString('base64'));
    await s.manager.initialize();
    assert.deepEqual(s.manager.details(), { authentication: 'windows-hello' });
    assert.deepEqual((await s.manager.load()).fileKey, key);
    assert.deepEqual(s.calls, [{ operation: 'check' }, { operation: 'verify', windowHandle: '1234' }]);
    assert.deepEqual(s.safeCalls, ['decrypt']);
    s.safeCalls.length = 0;
    s.fail('verify', 'cancelled');
    await assert.rejects(s.manager.load(), NativeUnlockError);
    assert.deepEqual(s.safeCalls, [], 'no DPAPI access after cancelled Hello');
  });

  it('asks for the Windows password before releasing the DPAPI key when Hello is not set up', async () => {
    const s = setup('win32', false);
    writeFileSync(oskPath(s.home), 'safe:' + key.toString('base64'));
    await s.manager.initialize();
    assert.deepEqual(s.manager.details(), { authentication: 'windows-password' });
    assert.deepEqual((await s.manager.load()).fileKey, key);
    assert.deepEqual(s.calls, [{ operation: 'check' }, { operation: 'verify-password', windowHandle: '1234' }]);
    assert.deepEqual(s.safeCalls, ['decrypt']);
    for (const code of ['cancelled', 'denied', 'unavailable', 'failed']) {
      s.safeCalls.length = 0;
      s.fail('verify-password', code);
      await assert.rejects(s.manager.load(), (error: unknown) => error instanceof NativeUnlockError && error.code === code);
      assert.deepEqual(s.safeCalls, [], `no DPAPI access after a ${code} password check`);
    }
  });

  it('creates a Windows key only after the password check', async () => {
    const s = setup('win32', false);
    s.fail('verify-password', 'cancelled');
    await s.manager.initialize();
    await assert.rejects(s.manager.load(), NativeUnlockError);
    assert.deepEqual(s.safeCalls, []);
    assert.equal(existsSync(oskPath(s.home)), false);
  });

  it('falls back to the Windows password when Hello stops being available, never to no check', async () => {
    const s = setup('win32');
    writeFileSync(oskPath(s.home), 'safe:' + key.toString('base64'));
    await s.manager.initialize();
    s.fail('verify', 'unavailable');
    assert.deepEqual((await s.manager.load()).fileKey, key);
    assert.deepEqual(s.calls.map((c) => c.operation), ['check', 'verify', 'verify-password']);
    assert.deepEqual(s.manager.details(), { authentication: 'windows-password' });
  });

  it('does not offer the password after Windows Hello is cancelled or denied', async () => {
    for (const code of ['cancelled', 'denied']) {
      const s = setup('win32');
      writeFileSync(oskPath(s.home), 'safe:' + key.toString('base64'));
      s.fail('verify', code);
      await assert.rejects(s.manager.load(), NativeUnlockError);
      assert.deepEqual(s.calls.map((c) => c.operation), ['check', 'verify']);
      assert.deepEqual(s.safeCalls, []);
    }
  });

  it('names the Mac password when there is no Touch ID', async () => {
    const s = setup('darwin', true, false);
    await s.manager.initialize();
    assert.deepEqual(s.manager.details(), { authentication: 'mac-password' });
  });

  it('does not downgrade Windows authentication when the helper fails its capability check', async () => {
    const s = setup('win32');
    s.fail('check', 'failed');
    await assert.rejects(s.manager.initialize(), NativeUnlockError);
    await assert.rejects(s.manager.initialize(), NativeUnlockError);
    await assert.rejects(s.manager.load(), NativeUnlockError);
    assert.deepEqual(s.safeCalls, []);
  });

  it('does not use a native helper on Linux and retains secure backend checks', async () => {
    const s = setup('linux');
    await s.manager.initialize();
    assert.equal((await s.manager.load()).protection, 'gnome_libsecret');
    assert.deepEqual(s.calls, []);
    assert.deepEqual(s.safeCalls, ['available', 'encrypt']);
  });
});

describe('Windows Hello file keys', () => {
  const stored = (home: string) => JSON.parse(readFileSync(oskPath(home), 'utf8'));
  const bytes = (value: string) => Buffer.from(value, 'base64').length;
  const notNative = (error: unknown) => error instanceof FileKeyAccessError && !(error instanceof NativeUnlockError);

  it('binds a new vault\'s file key to a new Hello key, with no DPAPI access or separate check', async () => {
    const s = setup('win32');
    await s.manager.initialize();
    assert.deepEqual(s.manager.details(), { authentication: 'windows-hello' });
    const plan = await s.manager.load();
    assert.equal(plan.fileKey?.length, 32);
    assert.equal(plan.protection, 'os');
    assert.equal(plan.note, 'a Windows Hello key protects the master key');
    assert.deepEqual(s.calls.map((c) => c.operation), ['check', 'hello-key-create']);
    assert.deepEqual(s.safeCalls, []);
    const create = s.calls[1]!;
    assert.match(create.account!, /^GateRook-[0-9a-f-]{36}$/);
    assert.equal(create.windowHandle, undefined);
    const text = readFileSync(oskPath(s.home), 'utf8');
    const ref = JSON.parse(text);
    assert.deepEqual(Object.keys(ref).sort(), ['account', 'format', 'nonce', 'salt', 'wrapped']);
    assert.equal(ref.format, 'gaterook-hello-key-v1');
    assert.equal(ref.account, create.account);
    assert.equal(ref.salt, create.challenge);
    assert.deepEqual([bytes(ref.salt), bytes(ref.nonce), bytes(ref.wrapped)], [32, 12, 48]);
    assert.ok(!text.includes(plan.fileKey!.toString('base64')));
    if (process.platform !== 'win32') assert.equal(statSync(oskPath(s.home)).mode & 0o777, 0o600);

    // The next start: the same Hello key signs the same salt again, and nothing else opens the file key.
    s.calls.length = 0;
    const next = new OsFileKey(s.options);
    assert.deepEqual(next.details(), { authentication: 'windows-hello' }, 'named before the capability check');
    assert.deepEqual((await next.load()).fileKey, plan.fileKey);
    assert.deepEqual(s.calls, [{ operation: 'check' }, { operation: 'hello-key-sign', account: ref.account, challenge: ref.salt }]);
    assert.deepEqual(s.safeCalls, []);
  });

  it('binds the first file key of a cleartext keyset too, but makes none for an encrypted keyset without one', async () => {
    const s = setup('win32');
    writeFileSync(keysetPath(s.home), '{"primaryKeyId":1}');
    assert.equal((await s.manager.load()).note, 'a Windows Hello key protects the master key');
    const t = setup('win32');
    writeFileSync(keysetPath(t.home), '{"encryptedKeyset":"x"}');
    assert.equal((await t.manager.load()).fileKey, null);
    assert.deepEqual(t.calls.map((c) => c.operation), ['check', 'verify']);
    assert.equal(existsSync(oskPath(t.home)), false);
  });

  for (const code of ['unavailable', 'failed', 'invalid-request']) {
    it(`uses DPAPI after the usual check when Hello can't make a key (${code})`, async () => {
      const s = setup('win32');
      s.fail('hello-key-create', code);
      assert.equal((await s.manager.load()).fileKey?.length, 32);
      assert.deepEqual(s.calls.map((c) => c.operation), ['check', 'hello-key-create', 'hello-key-delete', 'verify']);
      assert.equal(s.calls[2]!.account, s.calls[1]!.account, 'a half-made Hello key is removed');
      assert.deepEqual(s.safeCalls, ['available', 'encrypt']);
      assert.ok(readFileSync(oskPath(s.home), 'utf8').startsWith('safe:'));
      assert.deepEqual(s.manager.details(), { authentication: 'windows-hello' });
    });
  }

  it('asks for the account password when the person chose it over Hello', async () => {
    const s = setup('win32');
    s.fail('hello-key-create', 'denied');
    assert.equal((await s.manager.load()).fileKey?.length, 32);
    assert.deepEqual(s.calls.map((c) => c.operation), ['check', 'hello-key-create', 'hello-key-delete', 'verify-password']);
    assert.deepEqual(s.manager.details(), { authentication: 'windows-password' });
  });

  it('closes after a cancelled Hello key creation and leaves no key behind', async () => {
    const s = setup('win32');
    s.fail('hello-key-create', 'cancelled');
    await assert.rejects(s.manager.load(), (error: unknown) => error instanceof NativeUnlockError && error.code === 'cancelled');
    assert.deepEqual(s.calls.map((c) => c.operation), ['check', 'hello-key-create', 'hello-key-delete']);
    assert.deepEqual(s.safeCalls, []);
    assert.equal(existsSync(oskPath(s.home)), false);
  });

  it('never falls back to DPAPI or the password when a Hello vault does not open', async () => {
    const s = setup('win32');
    await s.manager.load();
    const reference = readFileSync(oskPath(s.home), 'utf8');
    for (const code of ['cancelled', 'denied', 'unavailable', 'failed']) {
      s.calls.length = 0;
      s.fail('hello-key-sign', code);
      await assert.rejects(new OsFileKey(s.options).load(), (error: unknown) => error instanceof NativeUnlockError && error.code === code);
      assert.deepEqual(s.calls.map((c) => c.operation), ['check', 'hello-key-sign']);
    }
    s.heal();
    s.shortSignatures();
    await assert.rejects(new OsFileKey(s.options).load(), notNative, 'a reply that is no signature');
    assert.deepEqual(s.safeCalls, []);
    assert.equal(readFileSync(oskPath(s.home), 'utf8'), reference);

    // The capability check doesn't decide a Hello vault: only its Hello key opens it.
    const t = setup('win32', false);
    writeFileSync(oskPath(t.home), reference);
    const { account } = JSON.parse(reference);
    t.helloKeys.set(account, s.helloKeys.get(account)!);
    assert.equal((await t.manager.load()).fileKey?.length, 32);
    assert.deepEqual(t.calls.map((c) => c.operation), ['check', 'hello-key-sign']);
    assert.deepEqual(t.manager.details(), { authentication: 'windows-hello' });
  });

  it('enters master-key recovery when the Hello key is gone or no longer opens the file key, keeping the reference', async () => {
    const s = setup('win32');
    await s.manager.load();
    writeFileSync(keysetPath(s.home), '{"encryptedKeyset":"x"}');
    const reference = readFileSync(oskPath(s.home), 'utf8');
    const { account } = JSON.parse(reference);
    s.calls.length = 0;
    s.helloKeys.delete(account);
    assert.deepEqual(await new OsFileKey(s.options).load(),
      { fileKey: null, protection: 'off', note: 'the Windows Hello key is missing; restore a master-key backup' });
    s.helloKeys.set(account, randomBytes(32));
    assert.deepEqual(await new OsFileKey(s.options).load(), { fileKey: null, protection: 'off',
      note: 'the Windows Hello key does not open the encryption key; restore a master-key backup' });
    assert.deepEqual(s.calls.map((c) => c.operation), ['check', 'hello-key-sign', 'check', 'hello-key-sign']);
    assert.deepEqual(s.safeCalls, []);
    assert.equal(readFileSync(oskPath(s.home), 'utf8'), reference);
  });

  it('gives a backup restored after its Hello key was lost a new Hello key, or leaves it cleartext until it can', async () => {
    const s = setup('win32');
    await s.manager.load();
    const lost = stored(s.home);
    s.helloKeys.delete(lost.account);
    writeFileSync(keysetPath(s.home), '{"primaryKeyId":1}');
    s.calls.length = 0;
    const plan = await new OsFileKey(s.options).load();
    assert.equal(plan.fileKey?.length, 32);
    assert.deepEqual(s.calls.map((c) => c.operation), ['check', 'hello-key-sign', 'hello-key-create']);
    const replaced = stored(s.home);
    assert.notEqual(replaced.account, lost.account);

    s.helloKeys.delete(replaced.account);
    s.fail('hello-key-create', 'unavailable');
    assert.deepEqual(await new OsFileKey(s.options).load(), { fileKey: null, protection: 'off',
      note: 'the Windows Hello key is missing; the master key file stays unprotected until Windows Hello can make a new key' });
    assert.equal(stored(s.home).account, replaced.account);
    assert.deepEqual(s.safeCalls, []);
  });

  for (const change of [{ salt: 'AAAA' }, { nonce: Buffer.alloc(16).toString('base64') }, { wrapped: 'not base64!' },
    { salt: undefined }, { account: 'invalid/account' }]) {
    it(`refuses a damaged Hello reference instead of using another provider: ${JSON.stringify(change)}`, async () => {
      const s = setup('win32');
      await s.manager.load();
      writeFileSync(oskPath(s.home), JSON.stringify({ ...stored(s.home), ...change }));
      s.calls.length = 0;
      await assert.rejects(new OsFileKey(s.options).load(), notNative);
      assert.deepEqual(s.calls.map((c) => c.operation), ['check']);
      assert.deepEqual(s.safeCalls, []);
    });
  }

  it('refuses a Hello reference on another system', async () => {
    const s = setup('win32');
    await s.manager.load();
    const t = setup('darwin');
    writeFileSync(oskPath(t.home), readFileSync(oskPath(s.home)));
    await assert.rejects(t.manager.load(), /belongs to Windows Hello/);
    assert.deepEqual(t.calls.map((c) => c.operation), ['check']);
    assert.deepEqual(t.safeCalls, []);
  });
});

describe('native file key reset', () => {
  it('deletes only the referenced account without loading, authenticating, or creating a key', async () => {
    const s = setup();
    const reference = JSON.stringify({ format: 'gaterook-native-key-v1', account: 'this-home' });
    writeFileSync(oskPath(s.home), reference);
    s.saved.set('this-home', key.toString('base64'));
    s.saved.set('another-home', key.toString('base64'));
    await s.manager.reset();
    assert.deepEqual(s.calls, [{ operation: 'delete', account: 'this-home' }]);
    assert.equal(s.saved.has('this-home'), false);
    assert.equal(s.saved.has('another-home'), true);
    assert.deepEqual(s.safeCalls, []);
    assert.equal(readFileSync(oskPath(s.home), 'utf8'), reference, 'the caller removes local files after key cleanup');
    await s.manager.reset();
    assert.deepEqual(s.calls, [
      { operation: 'delete', account: 'this-home' }, { operation: 'delete', account: 'this-home' },
    ], 'a missing native item is already reset');
  });

  it('does not open the shared legacy provider or create a key when no native reference exists', async () => {
    const s = setup();
    await s.manager.reset();
    const legacy = 'safe:' + key.toString('base64');
    writeFileSync(oskPath(s.home), legacy);
    await s.manager.reset();
    assert.deepEqual(s.calls, []);
    assert.deepEqual(s.safeCalls, []);
    assert.equal(readFileSync(oskPath(s.home), 'utf8'), legacy);
  });

  for (const platform of ['win32', 'linux'] as const) {
    it(`does not access OS key storage during reset on ${platform}`, async () => {
      const s = setup(platform);
      writeFileSync(oskPath(s.home), 'safe:' + key.toString('base64'));
      await s.manager.reset();
      assert.deepEqual(s.calls, []);
      assert.deepEqual(s.safeCalls, []);
    });
  }

  for (const reference of ['{', '{"format":"gaterook-native-key-v1"}',
    '{"format":"gaterook-native-key-v1","account":""}',
    '{"format":"gaterook-native-key-v1","account":"invalid/account"}']) {
    it(`rejects a damaged native reference without deleting any account: ${reference}`, async () => {
      const s = setup();
      writeFileSync(oskPath(s.home), reference);
      await assert.rejects(s.manager.reset(), FileKeyAccessError);
      assert.deepEqual(s.calls, []);
      assert.deepEqual(s.safeCalls, []);
      assert.equal(readFileSync(oskPath(s.home), 'utf8'), reference);
    });
  }

  for (const failure of ['cancelled', 'denied', 'unavailable', 'failed']) {
    it(`preserves the native reference and reports ${failure} when deletion fails`, async () => {
      const s = setup();
      const reference = JSON.stringify({ format: 'gaterook-native-key-v1', account: 'this-home' });
      writeFileSync(oskPath(s.home), reference);
      s.saved.set('this-home', key.toString('base64'));
      s.fail('delete', failure);
      await assert.rejects(s.manager.reset(), NativeUnlockError);
      assert.equal(readFileSync(oskPath(s.home), 'utf8'), reference);
      assert.equal(s.saved.has('this-home'), true);
      assert.deepEqual(s.calls, [{ operation: 'delete', account: 'this-home' }]);
      assert.deepEqual(s.safeCalls, []);
    });
  }

  it('refuses a linked reference instead of deleting another home\'s key', { skip: process.platform === 'win32' }, async () => {
    const s = setup();
    const other = setup();
    writeFileSync(oskPath(other.home), JSON.stringify({ format: 'gaterook-native-key-v1', account: 'another-home' }));
    symlinkSync(oskPath(other.home), oskPath(s.home));
    await assert.rejects(s.manager.reset(), FileKeyAccessError);
    assert.deepEqual(s.calls, []);
    assert.deepEqual(s.safeCalls, []);
  });

  it('deletes the Hello key of a Windows Hello vault, and nothing about that key stops a reset', async () => {
    const s = setup('win32');
    await s.manager.load();
    const reference = readFileSync(oskPath(s.home), 'utf8');
    const { account } = JSON.parse(reference);
    s.calls.length = 0;
    await s.manager.reset();
    assert.deepEqual(s.calls, [{ operation: 'hello-key-delete', account }]);
    assert.equal(s.helloKeys.has(account), false);
    assert.equal(readFileSync(oskPath(s.home), 'utf8'), reference, 'the caller removes local files after key cleanup');
    s.fail('hello-key-delete', 'failed');
    await s.manager.reset();
    writeFileSync(oskPath(s.home), '{');
    s.calls.length = 0;
    await s.manager.reset();
    assert.deepEqual(s.calls, [], 'a damaged reference names no key to delete');
    assert.deepEqual(s.safeCalls, []);
  });
});
