import assert from 'node:assert/strict';
import { before, describe, it } from 'node:test';
import { decryptFullBackup, encryptFullBackup, MAX_BACKUP_BYTES, validateBackupPassphrase } from '../src/main/full-backup-archive.ts';

const passphrase = '  fictional backup phrase  ';
const payload = Buffer.from('database bytes\0portable master key\0settings\0\u{1f512}', 'utf8');
let encrypted: Buffer;

describe('encrypted full-backup envelope', () => {
  before(async () => { encrypted = await encryptFullBackup(payload, passphrase); });

  it('round-trips arbitrary bytes, with no plaintext or passphrase in the file', async () => {
    assert.deepEqual(await decryptFullBackup(encrypted, passphrase), payload);
    assert.equal(encrypted.includes(payload), false);
    assert.equal(encrypted.includes(Buffer.from(passphrase)), false);
    assert.equal(encrypted.length, payload.length + 68 + 16);
    assert.equal(encrypted.readUInt32BE(12), 2 ** 17);
    assert.equal(encrypted.readUInt32BE(16), 8);
    assert.equal(encrypted.readUInt32BE(20), 1);
  });

  it('uses independent random salts and nonces for repeated exports', async () => {
    const second = await encryptFullBackup(payload, passphrase);
    assert.notDeepEqual(second.subarray(24, 56), encrypted.subarray(24, 56));
    assert.notDeepEqual(second.subarray(56, 68), encrypted.subarray(56, 68));
    assert.notDeepEqual(second, encrypted);
  });

  it('preserves spaces and rejects the wrong passphrase without returning plaintext', async () => {
    await assert.rejects(decryptFullBackup(encrypted, passphrase.trim()), /incorrect.*damaged/);
  });

  it('authenticates payload, tag, salt, and nonce', async () => {
    for (const offset of [68, encrypted.length - 1, 24, 56]) {
      const damaged = Buffer.from(encrypted);
      damaged[offset] = damaged[offset]! ^ 1;
      await assert.rejects(decryptFullBackup(damaged, passphrase), /incorrect.*damaged/);
    }
  });

  it('rejects unsupported formats and KDF costs without accepting attacker-selected parameters', async () => {
    for (const offset of [0, 8, 9, 10, 11, 12, 16, 20]) {
      const damaged = Buffer.from(encrypted);
      damaged[offset] = damaged[offset]! ^ 1;
      await assert.rejects(decryptFullBackup(damaged, passphrase), /not a supported/);
    }
    await assert.rejects(decryptFullBackup(Buffer.alloc(84), passphrase), /not a supported/);
    await assert.rejects(decryptFullBackup(Buffer.from('{}'), passphrase), /not a supported/);
    await assert.rejects(decryptFullBackup(encrypted.subarray(0, encrypted.length - 1), passphrase), /incorrect.*damaged/);
  });

  it('rejects invalid passphrases and empty payloads before deriving a key', async () => {
    for (const value of [undefined, null, 42, {}, '', 'short', 'a'.repeat(1025), '\ud800'.repeat(12)]) {
      assert.throws(() => validateBackupPassphrase(value), /12 to 1,024/);
      await assert.rejects(encryptFullBackup(payload, value), /12 to 1,024/);
    }
    validateBackupPassphrase('a'.repeat(12));
    validateBackupPassphrase('a'.repeat(1024));
    await assert.rejects(encryptFullBackup(Buffer.alloc(0), passphrase), /empty or exceeds/);
    assert.equal(MAX_BACKUP_BYTES, 256 * 1024 * 1024);
  });

  it('rejects oversized input before allocating ciphertext or deriving a key', async () => {
    const oversized = Buffer.allocUnsafe(MAX_BACKUP_BYTES + 1);
    await assert.rejects(encryptFullBackup(oversized, passphrase), /exceeds the 256 MiB/);
    await assert.rejects(decryptFullBackup(oversized, passphrase), /not a supported/);
  });
});
