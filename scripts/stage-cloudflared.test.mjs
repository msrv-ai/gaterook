// node --test scripts/stage-cloudflared.test.mjs
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';
import { currentPlatform, fromTarGz } from './stage-cloudflared.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');

/** A ustar archive laid out like the macOS release tarballs. */
function tgz(entries) {
  const blocks = [];
  for (const { name, data, type } of entries) {
    const h = Buffer.alloc(512);
    h.write(name, 0, 'ascii');
    h.write('0000755', 100, 'ascii');
    h.write(data.length.toString(8).padStart(11, '0'), 124, 'ascii');
    h[156] = type.charCodeAt(0);
    h.write('ustar', 257, 'ascii');
    blocks.push(h, data, Buffer.alloc((512 - (data.length % 512)) % 512));
  }
  blocks.push(Buffer.alloc(1024));
  return gzipSync(Buffer.concat(blocks));
}

test('the agent is the named top-level regular file of the archive', () => {
  const agent = Buffer.from('agent bytes');
  const archive = tgz([{ name: 'README', data: Buffer.from('x'), type: '0' }, { name: './cloudflared', data: agent,
    type: '0' }]);
  assert.deepEqual(Buffer.from(fromTarGz(archive, 'cloudflared')), agent);
  assert.throws(() => fromTarGz(tgz([{ name: 'cloudflared', data: Buffer.alloc(0), type: '2' }]), 'cloudflared'),
    /no cloudflared/);
  assert.throws(() => fromTarGz(tgz([{ name: 'bin/cloudflared', data: agent, type: '0' }]), 'cloudflared'),
    /no cloudflared/);
});

test('platforms are named like the core names them', () => {
  assert.equal(currentPlatform('linux', 'x64'), 'linux-x64');
  assert.equal(currentPlatform('linux', 'arm64'), 'linux-arm64');
  assert.equal(currentPlatform('darwin', 'arm64'), 'darwin-arm64');
  assert.equal(currentPlatform('win32', 'x64'), 'windows-x64');
});

test('every platform of the installers is pinned', () => {
  const pins = JSON.parse(readFileSync(join(root, 'core/src/main/resources/ai/msrv/gaterook/core/tunnel/binaries.json'),
    'utf8'));
  for (const p of ['linux-x64', 'linux-arm64', 'darwin-x64', 'darwin-arm64', 'windows-x64']) {
    const pin = pins.cloudflared.platforms[p];
    assert.match(pin.sha256, /^[0-9a-f]{64}$/, p);
    assert.match(pin.binarySha256, /^[0-9a-f]{64}$/, p);
    assert.ok(pin.url.startsWith(`https://github.com/cloudflare/cloudflared/releases/download/${pins.cloudflared.version}/`), p);
  }
});
