import assert from 'node:assert/strict';
import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { after, describe, it } from 'node:test';
import { keysetPath, oskPath, planFileKey, type SafeStorageLike } from '../src/main/file-key.ts';
import { tempDir } from './helpers.ts';

const t = tempDir('filekey');
after(t.remove);

/** A safeStorage that "encrypts" by reversing and tagging (enough to see the bytes went through it). */
function fakeSafe(o: { available?: boolean; backend?: string; reEncrypt?: boolean; broken?: boolean; encryptFails?: boolean } = {}): SafeStorageLike & { calls: string[] } {
  const calls: string[] = [];
  return {
    calls,
    async isAsyncEncryptionAvailable() {
      return o.available ?? true;
    },
    async encryptStringAsync(plain: string) {
      calls.push('encrypt');
      if (o.encryptFails) throw new Error('keyring locked');
      return Buffer.from(`enc:${[...plain].reverse().join('')}`);
    },
    async decryptStringAsync(enc: Buffer) {
      calls.push('decrypt');
      if (o.broken) throw new Error('Keychain item not found');
      const text = enc.toString();
      if (!text.startsWith('enc:')) throw new Error('not ours');
      return { result: [...text.slice(4)].reverse().join(''), shouldReEncrypt: o.reEncrypt ?? false };
    },
    getSelectedStorageBackend: () => o.backend ?? 'gnome_libsecret',
  };
}

let n = 0;
const home = (): string => {
  const dir = join(t.dir, `home-${++n}`);
  mkdirSync(dir, { recursive: true });
  return dir;
};

describe('OS protection of the master key file (A14)', () => {
  it('a first start gets a new key, kept by safeStorage owner-only', async () => {
    const h = home();
    const safe = fakeSafe();
    const plan = await planFileKey(h, safe, 'darwin');
    assert.equal(plan.protection, 'os');
    assert.equal(plan.fileKey?.length, 32);
    assert.ok(existsSync(oskPath(h)));
    if (process.platform !== 'win32') assert.equal(statSync(oskPath(h)).mode & 0o777, 0o600);
    assert.ok(!readFileSync(oskPath(h)).toString().includes(plan.fileKey!.toString('base64')), 'not stored in the clear');
    const again = await planFileKey(h, safe, 'darwin');
    assert.deepEqual(again.fileKey, plan.fileKey, 'the next start reads the same key back');
  });

  it('a cleartext keyset gets a key at the next start: the core rewrites it under the key while it loads', async () => {
    const h = home();
    writeFileSync(keysetPath(h), '{"primaryKeyId":1,"key":[{"keyData":{}}]}');
    const plan = await planFileKey(h, fakeSafe(), 'win32');
    assert.equal(plan.fileKey?.length, 32);
    assert.equal(plan.protection, 'os');
    assert.match(plan.note ?? '', /from this start on/);
    assert.ok(existsSync(oskPath(h)), 'the key is kept before the core uses it');
    const again = await planFileKey(h, fakeSafe(), 'win32');
    assert.deepEqual(again.fileKey, plan.fileKey);
  });

  it('an encrypted keyset whose key is gone gets no new key (the core reports it invalid)', async () => {
    const h = home();
    writeFileSync(keysetPath(h), '{"encryptedKeyset":"AAAA","keysetInfo":{}}');
    const plan = await planFileKey(h, fakeSafe(), 'darwin');
    assert.equal(plan.fileKey, null);
    assert.match(plan.note ?? '', /missing/);
    assert.ok(!existsSync(oskPath(h)));
  });

  it('Linux basic_text is no protection: the file stays 0400 and the core says unavailable', async () => {
    const h = home();
    const plan = await planFileKey(h, fakeSafe({ backend: 'basic_text' }), 'linux');
    assert.deepEqual(plan, { fileKey: null, protection: 'basic_text', note: null });
    const kwallet = await planFileKey(home(), fakeSafe({ backend: 'kwallet5' }), 'linux');
    assert.equal(kwallet.protection, 'kwallet5');
    assert.equal(kwallet.fileKey?.length, 32);
  });

  it('without safeStorage nothing is protected and nothing is written', async () => {
    const h = home();
    assert.deepEqual(await planFileKey(h, fakeSafe({ available: false }), 'darwin'),
      { fileKey: null, protection: 'unavailable', note: null });
    assert.deepEqual(await planFileKey(h, null, 'darwin'), { fileKey: null, protection: 'off', note: null });
    assert.ok(!existsSync(oskPath(h)));
  });

  it('a key the OS store lost is not replaced: the core reports the keyset invalid', async () => {
    const h = home();
    await planFileKey(h, fakeSafe(), 'darwin');
    writeFileSync(keysetPath(h), '{"encryptedKeyset":"x"}');
    const before = readFileSync(oskPath(h));
    const plan = await planFileKey(h, fakeSafe({ broken: true }), 'darwin');
    assert.equal(plan.fileKey, null);
    assert.match(plan.note ?? '', /can't be read/);
    assert.deepEqual(readFileSync(oskPath(h)), before, 'the .osk is kept for when the store comes back');
  });

  it('a failed re-encryption keeps the key that was read', async () => {
    const h = home();
    const first = await planFileKey(h, fakeSafe(), 'darwin');
    const again = await planFileKey(h, fakeSafe({ reEncrypt: true, encryptFails: true }), 'darwin');
    assert.deepEqual(again.fileKey, first.fileKey);
    assert.match(again.note ?? '', /couldn't be re-encrypted/);
  });

  it('re-encrypts when safeStorage asks to', async () => {
    const h = home();
    const first = await planFileKey(h, fakeSafe(), 'darwin');
    const safe = fakeSafe({ reEncrypt: true });
    const again = await planFileKey(h, safe, 'darwin');
    assert.deepEqual(again.fileKey, first.fileKey);
    assert.deepEqual(safe.calls, ['decrypt', 'encrypt']);
  });
});
