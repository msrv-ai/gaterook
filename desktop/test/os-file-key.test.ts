import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, it } from 'node:test';
import { FileKeyAccessError, keysetPath, oskPath, type SafeStorageLike } from '../src/main/file-key.ts';
import { NativeUnlockError, type NativeRequest, type NativeReply } from '../src/main/native-unlock.ts';
import { OsFileKey } from '../src/main/os-file-key.ts';

const homes: string[] = [];
afterEach(() => { for (const home of homes.splice(0)) rmSync(home, { recursive: true, force: true }); });
const key = Buffer.alloc(32, 71);

function setup(platform: NodeJS.Platform = 'darwin', available = true) {
  const home = mkdtempSync(join(tmpdir(), 'gaterook-native-key-'));
  homes.push(home);
  const calls: NativeRequest[] = [];
  const safeCalls: string[] = [];
  const saved = new Map<string, string>();
  let failure: { operation: NativeRequest['operation']; code: string } | null = null;
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
      case 'check': return { ok: true, available, touchId: available && platform === 'darwin' };
      case 'verify': return { ok: true };
      case 'delete': saved.delete(request.account!); return { ok: true };
      case 'store': case 'create': saved.set(request.account!, request.key!); return { ok: true };
      case 'get': {
        const value = saved.get(request.account!);
        if (!value) throw new NativeUnlockError('missing');
        return { ok: true, key: value };
      }
    }
  };
  const options = { home, platform, native, safe, windowHandle: () => '1234', keychainService: 'GateRook Dev Safe Storage' };
  return { home, calls, safeCalls, saved, options, manager: new OsFileKey(options),
    fail: (operation: NativeRequest['operation'], code: string) => { failure = { operation, code }; } };
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

  it('retains OS protection when biometric authentication is not configured', async () => {
    const s = setup('win32', false);
    writeFileSync(oskPath(s.home), 'safe:' + key.toString('base64'));
    await s.manager.initialize();
    assert.deepEqual(s.manager.details(), { authentication: 'system' });
    assert.deepEqual((await s.manager.load()).fileKey, key);
    assert.deepEqual(s.calls, [{ operation: 'check' }]);
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
});
