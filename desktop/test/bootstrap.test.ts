import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { bootstrapLine, newLaunch } from '../src/main/bootstrap.ts';

const fields = (over: Record<string, unknown> = {}) => ({
  launchSecret: Buffer.alloc(32, 7).toString('base64url'),
  instanceNonce: 'abcdefghijklmnopqrstuvwx',
  fileKey: null,
  protection: 'off',
  port: 7465,
  publicPort: 7466,
  ...over,
});

describe('bootstrap line', () => {
  it('is one JSON line with exactly the fields the core reads', () => {
    const text = bootstrapLine(fields());
    assert.ok(text.endsWith('\n'));
    assert.equal(text.indexOf('\n'), text.length - 1);
    assert.deepEqual(JSON.parse(text), {
      v: 1, launchSecret: Buffer.alloc(32, 7).toString('base64url'), instanceNonce: 'abcdefghijklmnopqrstuvwx',
      fileKey: null, protection: 'off', port: 7465, publicPort: 7466,
    });
  });

  it('writes the file key as base64url without padding, 43 characters', () => {
    const key = Buffer.from(Array.from({ length: 32 }, (_, i) => 250 - i));
    const line = JSON.parse(bootstrapLine(fields({ fileKey: key, protection: 'os' })));
    assert.equal(line.fileKey, key.toString('base64url'));
    assert.equal(line.fileKey.length, 43);
    assert.doesNotMatch(line.fileKey, /[+/=]/);
  });

  it('refuses what the core would refuse', () => {
    assert.throws(() => bootstrapLine(fields({ launchSecret: Buffer.alloc(16).toString('base64url') })), /32 bytes/);
    assert.throws(() => bootstrapLine(fields({ instanceNonce: 'short' })), /nonce/);
    assert.throws(() => bootstrapLine(fields({ fileKey: Buffer.alloc(16) })), /32 bytes/);
    assert.throws(() => bootstrapLine(fields({ protection: 'OS protection' })), /protection/);
    assert.throws(() => bootstrapLine(fields({ port: 0 })), /port/);
    assert.throws(() => bootstrapLine(fields({ publicPort: 7465 })), /differ/);
  });

  it('makes a new secret and nonce for every launch', () => {
    const a = newLaunch();
    const b = newLaunch();
    assert.notEqual(a.launchSecret, b.launchSecret);
    assert.notEqual(a.instanceNonce, b.instanceNonce);
    assert.equal(Buffer.from(a.launchSecret, 'base64url').length, 32);
    assert.match(a.instanceNonce, /^[A-Za-z0-9_-]{16,128}$/);
  });
});
