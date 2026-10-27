import assert from 'node:assert/strict';
import { existsSync, mkdirSync, readdirSync, readFileSync, symlinkSync, writeFileSync } from 'node:fs';
import { join, parse } from 'node:path';
import { describe, it } from 'node:test';
import { finishApplicationReset, hasPendingReset, requestApplicationReset, validateResetPaths,
  type ResetOperations, type ResetPaths } from '../src/main/application-reset.ts';
import { DEFAULTS, readDesktopConfig } from '../src/main/desktop-config.ts';
import { tempDir } from './helpers.ts';

function fixture() {
  const temp = tempDir('reset');
  const home = join(temp.dir, 'gateway');
  const userData = join(home, 'electron');
  mkdirSync(userData, { recursive: true });
  for (const name of ['gateway.db', 'gateway.db-wal', 'gateway.db-shm', 'master.keyset', 'master.keyset.osk', 'desktop.json',
    'master.keyset.replaced-123456', '.master.keyset.tmp-123', 'master.keyset.osk.123.012345abcdef.tmp']) {
    writeFileSync(join(home, name), name === 'desktop.json' ? '{"port":9999,"publicPort":9998,"startHidden":true}' : 'old data');
  }
  for (const name of ['backups', 'logs', 'plugins', 'plugin-data', 'clients', 'connectors', 'cache', 'run', 'bin', 'mcp']) {
    mkdirSync(join(home, name));
    writeFileSync(join(home, name, 'old'), 'old data');
  }
  writeFileSync(join(userData, 'profile-in-use'), 'Chromium owns this file');
  const paths: ResetPaths = { home, userData, protectedPaths: [temp.dir] };
  const order: string[] = [];
  const operations: ResetOperations = {
    stop: async () => { order.push('stop'); },
    waitForCore: async (pid) => { assert.ok(pid === 123 || pid === null); order.push('wait'); },
    disableLogin: () => { order.push('login'); },
    removeKey: async () => { assert.ok(existsSync(join(home, 'master.keyset.osk'))); order.push('key'); },
    clearProfile: async () => { order.push('profile'); },
  };
  return { ...temp, paths, order, operations };
}

describe('full application reset', () => {
  it('requires exact confirmation before writing anything', () => {
    const f = fixture();
    try {
      for (const value of ['', 'reset', ' RESET', 'RESET ', true, null, {}, ['RESET']]) {
        assert.throws(() => requestApplicationReset(f.paths, value, 123), /Type RESET/);
      }
      assert.equal(hasPendingReset(f.paths.home), false);
      assert.ok(existsSync(join(f.paths.home, 'gateway.db')));
    } finally { f.remove(); }
  });

  it('stops writers, removes the key, clears storage and deletes all local data before removing its marker', async () => {
    const f = fixture();
    try {
      requestApplicationReset(f.paths, 'RESET', 123);
      assert.equal(hasPendingReset(f.paths.home), true);
      await finishApplicationReset(f.paths, f.operations);
      assert.deepEqual(f.order, ['stop', 'wait', 'login', 'key', 'profile']);
      assert.deepEqual(readdirSync(f.paths.home), ['electron']);
      assert.equal(readFileSync(join(f.paths.userData, 'profile-in-use'), 'utf8'), 'Chromium owns this file');
      assert.deepEqual(readDesktopConfig(f.paths.home).config, DEFAULTS);
      assert.equal(hasPendingReset(f.paths.home), false);
    } finally { f.remove(); }
  });

  it('also resets an installed home whose Chromium profile is outside it', async () => {
    const f = fixture();
    try {
      const paths = { ...f.paths, userData: join(f.dir, 'installed-profile') };
      mkdirSync(paths.userData);
      writeFileSync(join(paths.userData, 'profile-in-use'), 'profile');
      requestApplicationReset(paths, 'RESET', 123);
      await finishApplicationReset(paths, f.operations);
      assert.deepEqual(readdirSync(paths.home), []);
      assert.equal(readFileSync(join(paths.userData, 'profile-in-use'), 'utf8'), 'profile');
    } finally { f.remove(); }
  });

  for (const stage of ['stop', 'waitForCore', 'disableLogin', 'removeKey', 'clearProfile'] as const) {
    it(`preserves data and a retry marker if ${stage} fails`, async () => {
      const f = fixture();
      try {
        requestApplicationReset(f.paths, 'RESET', 123);
        await assert.rejects(finishApplicationReset(f.paths, { ...f.operations,
          [stage]: () => { throw new Error('unavailable'); } }), /unavailable/);
        assert.ok(hasPendingReset(f.paths.home));
        assert.equal(readFileSync(join(f.paths.home, 'gateway.db'), 'utf8'), 'old data');
        // A subsequent process can finish the same confirmed operation.
        await finishApplicationReset(f.paths, f.operations);
        assert.equal(hasPendingReset(f.paths.home), false);
        assert.deepEqual(readdirSync(f.paths.home), ['electron']);
      } finally { f.remove(); }
    });
  }

  it('refuses filesystem roots, protected directories, and overlapping profiles', () => {
    const f = fixture();
    try {
      const bad = [
        { ...f.paths, home: parse(f.paths.home).root },
        { ...f.paths, home: f.dir },
        { ...f.paths, protectedPaths: [join(f.paths.home, 'application')] },
        { ...f.paths, userData: f.paths.home },
        { ...f.paths, userData: f.dir },
        { ...f.paths, userData: join(f.paths.home, 'nested', 'profile') },
      ];
      for (const paths of bad) assert.throws(() => validateResetPaths(paths), /safely/);
      assert.ok(existsSync(join(f.paths.home, 'gateway.db')));
    } finally { f.remove(); }
  });

  it('does not follow directory links into external data', { skip: process.platform === 'win32' }, async () => {
    const f = fixture();
    try {
      const outside = join(f.dir, 'external');
      mkdirSync(outside);
      writeFileSync(join(outside, 'database'), 'keep');
      symlinkSync(outside, join(f.paths.home, 'tmp'));
      writeFileSync(join(f.paths.home, 'unrelated-notes.txt'), 'keep this too');
      symlinkSync(f.dir, join(f.dir, 'alias'));
      assert.throws(() => validateResetPaths({ ...f.paths, home: join(f.dir, 'alias') }), /safely/);
      requestApplicationReset(f.paths, 'RESET', 123);
      await finishApplicationReset(f.paths, f.operations);
      assert.equal(readFileSync(join(outside, 'database'), 'utf8'), 'keep');
      assert.deepEqual(readdirSync(f.paths.home), ['electron', 'unrelated-notes.txt']);
    } finally { f.remove(); }
  });

  it('refuses missing or damaged markers before invoking lifecycle operations', async () => {
    const f = fixture();
    try {
      await assert.rejects(finishApplicationReset(f.paths, f.operations));
      writeFileSync(join(f.paths.home, '.reset-pending'), '{"version":1,"corePid":-1}');
      await assert.rejects(finishApplicationReset(f.paths, f.operations), /Invalid application reset marker/);
      assert.deepEqual(f.order, []);
      assert.ok(existsSync(join(f.paths.home, 'gateway.db')));
    } finally { f.remove(); }
  });

  it('forgets the stopped core PID before a failed cleanup is retried', async () => {
    const f = fixture();
    try {
      requestApplicationReset(f.paths, 'RESET', 123);
      await assert.rejects(finishApplicationReset(f.paths, { ...f.operations,
        removeKey: async () => { throw new Error('Keychain unavailable'); } }), /Keychain/);
      const marker = JSON.parse(readFileSync(join(f.paths.home, '.reset-pending'), 'utf8'));
      assert.equal(marker.corePid, null);
      await finishApplicationReset(f.paths, { ...f.operations,
        waitForCore: async (pid) => { assert.equal(pid, null); } });
      assert.equal(hasPendingReset(f.paths.home), false);
    } finally { f.remove(); }
  });
});
