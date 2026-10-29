import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { chmodSync, existsSync, mkdirSync, readFileSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { after, describe, it } from 'node:test';
import { installFullRestore, pendingFullRestore, recoverFullRestore } from '../src/main/full-backup-restore.ts';
import { tempDir } from './helpers.ts';

const temp = tempDir('full-backup-restore');
after(temp.remove);
let sequence = 0;
function fixture(): { home: string; staged: string } {
  const home = join(temp.dir, `home-${++sequence}`);
  const staged = join(temp.dir, `stage-${sequence}`);
  mkdirSync(home); mkdirSync(staged);
  for (const name of ['gateway.db', 'master.keyset', 'desktop.json']) {
    writeFileSync(join(home, name), `old ${name}`);
    writeFileSync(join(staged, name), `new ${name}`);
  }
  mkdirSync(join(home, 'plugins')); writeFileSync(join(home, 'plugins', 'old.jar'), 'old plugin');
  mkdirSync(join(staged, 'plugins')); writeFileSync(join(staged, 'plugins', 'new.jar'), 'new plugin');
  writeFileSync(join(home, 'gateway.db-wal'), 'old wal');
  writeFileSync(join(home, 'master.keyset.osk'), 'local OS protection');
  writeFileSync(join(home, 'unrelated'), 'unrelated');
  return { home, staged };
}
function expectVersion(home: string, version: 'old' | 'new'): void {
  for (const name of ['gateway.db', 'master.keyset', 'desktop.json']) assert.equal(readFileSync(join(home, name), 'utf8'), `${version} ${name}`);
  assert.equal(readFileSync(join(home, 'plugins', `${version}.jar`), 'utf8'), `${version} plugin`);
  assert.equal(existsSync(join(home, 'plugins', `${version === 'old' ? 'new' : 'old'}.jar`)), false);
  assert.equal(readFileSync(join(home, 'master.keyset.osk'), 'utf8'), 'local OS protection');
  assert.equal(readFileSync(join(home, 'unrelated'), 'utf8'), 'unrelated');
}
const moduleUrl = new URL('../src/main/full-backup-restore.ts', import.meta.url).href;
function child(script: string): void {
  const result = spawnSync(process.execPath, ['--disable-warning=ExperimentalWarning', '--input-type=module', '-e', script], { encoding: 'utf8' });
  assert.equal(result.status, 73, result.stderr || result.stdout);
}
function crashDuringInstall(home: string, staged: string): void {
  child(`const { installFullRestore } = await import(${JSON.stringify(moduleUrl)});
    installFullRestore(${JSON.stringify(home)}, ${JSON.stringify(staged)}, (name) => { if (name === 'master.keyset') process.exit(73); });`);
}

describe('offline full-restore transaction', () => {
  it('replaces only application recovery state and removes stale SQLite sidecars', () => {
    const { home, staged } = fixture();
    installFullRestore(home, staged);
    expectVersion(home, 'new');
    assert.equal(existsSync(join(home, 'gateway.db-wal')), false);
    assert.equal(pendingFullRestore(home), false);
  });

  it('rolls back every replacement when an install step fails', () => {
    const { home, staged } = fixture();
    assert.throws(() => installFullRestore(home, staged, (name) => { if (name === 'plugins') throw new Error('injected failure'); }), /injected failure/);
    expectVersion(home, 'old');
    assert.equal(readFileSync(join(home, 'gateway.db-wal'), 'utf8'), 'old wal');
    assert.equal(pendingFullRestore(home), false);
  });

  it('recovers an interrupted install before a new core starts', () => {
    const { home, staged } = fixture();
    crashDuringInstall(home, staged);
    assert.equal(pendingFullRestore(home), true);
    recoverFullRestore(home);
    expectVersion(home, 'old');
    assert.equal(pendingFullRestore(home), false);
  });

  it('can retry a rollback interrupted after copying one original', () => {
    const { home, staged } = fixture();
    crashDuringInstall(home, staged);
    child(`import fs from 'node:fs'; import { syncBuiltinESMExports } from 'node:module';
      const copy = fs.cpSync; fs.cpSync = (...args) => { copy(...args); process.exit(73); }; syncBuiltinESMExports();
      const { recoverFullRestore } = await import(${JSON.stringify(moduleUrl)}); recoverFullRestore(${JSON.stringify(home)});`);
    assert.equal(pendingFullRestore(home), true);
    recoverFullRestore(home);
    expectVersion(home, 'old');
    assert.equal(pendingFullRestore(home), false);
  });

  it('finishes interrupted committed cleanup without rolling back partially removed originals', () => {
    const { home, staged } = fixture();
    child(`import fs from 'node:fs'; import { syncBuiltinESMExports } from 'node:module';
      const remove = fs.rmSync; fs.rmSync = (...args) => { remove(...args); if (String(args[0]).endsWith('journal.json')) process.exit(73); }; syncBuiltinESMExports();
      const { installFullRestore } = await import(${JSON.stringify(moduleUrl)}); installFullRestore(${JSON.stringify(home)}, ${JSON.stringify(staged)});`);
    assert.equal(pendingFullRestore(home), true);
    recoverFullRestore(home);
    expectVersion(home, 'new');
    assert.equal(pendingFullRestore(home), false);
  });

  it('fails closed for missing originals or an invalid journal', () => {
    const { home, staged } = fixture();
    crashDuringInstall(home, staged);
    rmSync(join(home, '.full-restore', 'old', 'gateway.db'));
    rmSync(join(home, 'gateway.db'));
    assert.throws(() => recoverFullRestore(home), /original application file is missing/);
    assert.equal(pendingFullRestore(home), true);
    writeFileSync(join(home, '.full-restore', 'journal.json'), JSON.stringify({ version: 1, state: 'prepared', original: ['../outside'] }));
    assert.throws(() => recoverFullRestore(home), /journal is invalid/);
    assert.equal(pendingFullRestore(home), true);
  });

  it('rejects linked data and incomplete staging without changing live files', () => {
    const { home, staged } = fixture();
    rmSync(join(staged, 'master.keyset'));
    assert.throws(() => installFullRestore(home, staged), /incomplete/);
    expectVersion(home, 'old');
    writeFileSync(join(staged, 'master.keyset'), 'new master.keyset');
    symlinkSync(join(home, 'unrelated'), join(staged, 'plugins', 'linked.jar'));
    assert.throws(() => installFullRestore(home, staged), /linked or special/);
    expectVersion(home, 'old');
    assert.equal(pendingFullRestore(home), false);
  });

  it('opens writable flush handles on Windows and preserves read-only key files', () => {
    const { home, staged } = fixture();
    chmodSync(join(home, 'master.keyset'), 0o400);
    chmodSync(join(staged, 'master.keyset'), 0o400);
    child(`import fs from 'node:fs'; import { syncBuiltinESMExports } from 'node:module';
      Object.defineProperty(process, 'platform', { value: 'win32' });
      const open = fs.openSync; const sync = fs.fsyncSync; const descriptors = new Map();
      fs.openSync = (...args) => { const fd = open(...args); descriptors.set(fd, args[1]); return fd; };
      fs.fsyncSync = (fd) => { const flags = descriptors.get(fd); if (flags !== 'wx' && (flags & fs.constants.O_RDWR) === 0) throw new Error('FlushFileBuffers needs write access'); return sync(fd); };
      syncBuiltinESMExports();
      const { installFullRestore } = await import(${JSON.stringify(moduleUrl)});
      installFullRestore(${JSON.stringify(home)}, ${JSON.stringify(staged)}); process.exit(73);`);
    expectVersion(home, 'new');
    assert.equal(statSync(join(home, 'master.keyset')).mode & 0o200, 0);
  });
});
