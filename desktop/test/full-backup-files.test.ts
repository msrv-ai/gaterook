import assert from 'node:assert/strict';
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, symlinkSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { after, describe, it } from 'node:test';
import { packBackup, readBackupFile, unpackBackup, validateBackupDestination } from '../src/main/full-backup-files.ts';
import { tempDir } from './helpers.ts';

const temp = tempDir('full-backup-files');
after(temp.remove);
let sequence = 0;
function directory(): string {
  const result = join(temp.dir, String(++sequence));
  mkdirSync(result, { mode: 0o700 });
  return result;
}
function payload(entries: { path: string; data?: string; size?: number }[], extra = ''): Buffer {
  const files = entries.map((entry) => ({ path: entry.path, size: entry.size ?? Buffer.byteLength(entry.data ?? '') }));
  const manifest = Buffer.from(JSON.stringify({ format: 'gaterook/full-backup/1', createdAt: '2026-10-29T12:00:00Z',
    version: '0.4.1', keyset: 'portable-test-key', files }));
  const header = Buffer.alloc(4); header.writeUInt32BE(manifest.length);
  return Buffer.concat([header, manifest, ...entries.map((entry) => Buffer.from(entry.data ?? '')), Buffer.from(extra)]);
}
const required = [{ path: 'gateway.db', data: 'database' }, { path: 'desktop.json', data: '{}' }];

describe('full-backup container', () => {
  it('round-trips application files and excludes scratch data', () => {
    const home = directory();
    const snapshot = directory();
    const target = directory();
    writeFileSync(join(snapshot, 'gateway.db'), 'consistent snapshot');
    writeFileSync(join(home, 'desktop.json'), '{"port":7465}');
    mkdirSync(join(home, 'plugins', 'example', '1.0'), { recursive: true });
    writeFileSync(join(home, 'plugins', 'example', '1.0', 'plugin.jar'), 'plugin');
    mkdirSync(join(home, 'plugins', '.staging'), { recursive: true });
    writeFileSync(join(home, 'plugins', '.staging', 'partial.jar'), 'incomplete');
    mkdirSync(join(home, 'plugin-data', 'example', 'tmp'), { recursive: true });
    writeFileSync(join(home, 'plugin-data', 'example', 'state.json'), '{}');
    writeFileSync(join(home, 'plugin-data', 'example', 'tmp', 'transient'), 'temporary');
    writeFileSync(join(home, 'master.keyset.osk'), 'machine-bound key');
    writeFileSync(join(home, 'unrelated'), 'unrelated data');
    const packed = packBackup(home, snapshot, 'portable-test-key', '0.4.1');
    const result = unpackBackup(packed, target);
    assert.equal(result.keyset, 'portable-test-key');
    assert.equal(readFileSync(join(target, 'gateway.db'), 'utf8'), 'consistent snapshot');
    assert.equal(readFileSync(join(target, 'plugins', 'example', '1.0', 'plugin.jar'), 'utf8'), 'plugin');
    assert.equal(existsSync(join(target, 'plugins', '.staging')), false);
    assert.equal(existsSync(join(target, 'plugin-data', 'example', 'tmp')), false);
    assert.equal(existsSync(join(target, 'master.keyset.osk')), false);
    assert.equal(existsSync(join(target, 'unrelated')), false);
    if (process.platform !== 'win32') {
      assert.equal(statSync(target).mode & 0o777, 0o700);
      assert.equal(statSync(join(target, 'gateway.db')).mode & 0o777, 0o600);
    }
  });

  it('rejects traversal, absolute paths, reserved names, and alternate streams before writing', () => {
    for (const path of ['../escape', '/tmp/escape', 'plugins/../../escape', 'plugins/a\\b', 'plugins/C:escape',
      'plugins/con', 'plugins/COM1.txt', 'plugins/com¹.txt', 'plugins/a.', 'plugins/a ', 'plugins/a?b', 'plugins/<a>',
      'plugins/a|b', 'plugins/a\0b', 'master.keyset', 'master.keyset.osk', 'run/broker.json', 'plugins//empty']) {
      const target = directory();
      assert.throws(() => unpackBackup(payload([...required, { path, data: 'x' }]), target), /Invalid file/);
      assert.deepEqual(readdirSync(target), []);
    }
  });

  it('rejects duplicates, portable name collisions, and conflicting parent files', () => {
    for (const paths of [['plugins/a', 'plugins/a'], ['plugins/a', 'plugins/A'], ['plugins/café', 'plugins/cafe\u0301'],
      ['plugins/a', 'plugins/a/nested']]) {
      const target = directory();
      assert.throws(() => unpackBackup(payload([...required, ...paths.map((path) => ({ path, data: 'x' }))]), target), /Duplicate|Conflicting/);
      assert.deepEqual(readdirSync(target), []);
    }
  });

  it('rejects incomplete, trailing, and invalid-length data before writing', () => {
    for (const invalid of [payload(required, 'extra'), payload(required).subarray(0, -1),
      payload([{ path: 'gateway.db', data: 'only database' }]),
      payload([...required, { path: 'plugins/a', size: -1 }]), Buffer.from([255, 255, 255, 255])]) {
      const target = directory();
      assert.throws(() => unpackBackup(invalid, target));
      assert.deepEqual(readdirSync(target), []);
    }
  });

  it('refuses existing staging files or links, including linked source data', () => {
    const source = directory();
    const target = directory();
    const outside = directory();
    writeFileSync(join(outside, 'secret'), 'outside');
    symlinkSync(outside, join(target, 'plugins'), 'dir');
    assert.throws(() => unpackBackup(payload([...required, { path: 'plugins/secret', data: 'changed' }]), target), /empty staging/);
    assert.equal(readFileSync(join(outside, 'secret'), 'utf8'), 'outside');
    writeFileSync(join(source, 'desktop.json'), '{}');
    symlinkSync(outside, join(source, 'plugins'), 'dir');
    const snapshot = directory();
    writeFileSync(join(snapshot, 'gateway.db'), 'database');
    assert.throws(() => packBackup(source, snapshot, 'key', '0.4.1'), /symbolic links/);
    symlinkSync(join(outside, 'secret'), join(source, 'linked-file'));
    assert.throws(() => readBackupFile(join(source, 'linked-file')), /regular backup file/);
    assert.throws(() => readBackupFile(join(outside, 'secret'), 2), /size limit/);
  });

  it('requires an external regular destination with the archive extension', () => {
    const home = directory();
    const outside = directory();
    assert.equal(validateBackupDestination(home, join(outside, 'backup.grbackup')), join(outside, 'backup.grbackup'));
    assert.throws(() => validateBackupDestination(home, join(home, 'backup.grbackup')), /outside/);
    symlinkSync(home, join(outside, 'alias'), 'dir');
    assert.throws(() => validateBackupDestination(home, join(outside, 'alias', 'backup.grbackup')), /outside/);
    assert.throws(() => validateBackupDestination(home, join(outside, 'backup.db')), /extension/);
    writeFileSync(join(home, 'gateway.db'), 'database');
    symlinkSync(join(home, 'gateway.db'), join(outside, 'linked.grbackup'));
    assert.throws(() => validateBackupDestination(home, join(outside, 'linked.grbackup')), /regular destination/);
    assert.equal(readFileSync(join(home, 'gateway.db'), 'utf8'), 'database');
  });
});
