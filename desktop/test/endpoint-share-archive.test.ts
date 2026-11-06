import assert from 'node:assert/strict';
import { test } from 'node:test';
import { encryptEndpointShare, decryptEndpointShare, MAX_SHARE_BYTES } from '../src/main/endpoint-share-archive.ts';
import { decryptFullBackup, encryptFullBackup } from '../src/main/full-backup-archive.ts';

const passphrase = '  fictional sharing passphrase  ';
test('endpoint shares encrypt every payload byte and authenticate the header with a separate file type', async () => {
  const payload = Buffer.from('{"secret":"fictional-password","endpoint":"shared"}');
  const file = await encryptEndpointShare(payload, passphrase);
  assert.deepEqual(await decryptEndpointShare(file, passphrase), payload);
  assert.equal(file.includes(Buffer.from('fictional-password')), false);
  await assert.rejects(decryptEndpointShare(file, passphrase.trim()), /incorrect.*damaged/);
  await assert.rejects(decryptFullBackup(file, passphrase), /not a supported/);
  const backup = await encryptFullBackup(payload, passphrase);
  await assert.rejects(decryptEndpointShare(backup, passphrase), /not a supported/);
  for (const offset of [24, 56, 68, file.length - 1]) {
    const corrupt = Buffer.from(file);
    corrupt[offset] = corrupt[offset]! ^ 1;
    await assert.rejects(decryptEndpointShare(corrupt, passphrase), /incorrect.*damaged/);
  }
  const second = await encryptEndpointShare(payload, passphrase);
  assert.notDeepEqual(second.subarray(24, 68), file.subarray(24, 68));
});

test('endpoint share format and size checks precede expensive key derivation', async () => {
  const invalid = Buffer.alloc(100);
  invalid.write('GTRKSHR\0');
  await assert.rejects(decryptEndpointShare(invalid, passphrase), /not a supported/);
  await assert.rejects(encryptEndpointShare(Buffer.alloc(0), passphrase), /empty or exceeds/);
  await assert.rejects(encryptEndpointShare(Buffer.alloc(MAX_SHARE_BYTES), passphrase), /8 MiB/);
  await assert.rejects(decryptEndpointShare(Buffer.alloc(MAX_SHARE_BYTES + 1), passphrase), /not a supported/);
});
