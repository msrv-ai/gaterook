import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { chmodSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, readlinkSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import { assertMacUpdatePermissions } from '../scripts/verify-mac-update-permissions.mjs';

const repository = fileURLToPath(new URL('../../', import.meta.url));
const script = fileURLToPath(new URL('../scripts/verify-mac-update-permissions.mjs', import.meta.url));

function fixture(t) {
  const scratch = join(repository, 'ai');
  mkdirSync(scratch, { recursive: true });
  const directory = mkdtempSync(join(scratch, 'verify-mac-permissions-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const app = join(directory, 'GateRook.app');
  const resources = join(app, 'Contents', 'Resources');
  mkdirSync(resources, { recursive: true });
  writeFileSync(join(app, 'Contents', 'Info.plist'), '<plist><dict/></plist>');
  return { directory, app, resources };
}

describe('macOS update permission verification', { skip: process.platform === 'win32' }, () => {
  it('checks the full bundle while retaining executable bits, file contents and external symlinks', async (t) => {
    const f = fixture(t);
    const executable = join(f.app, 'Contents', 'GateRook');
    writeFileSync(executable, 'signed executable fixture');
    chmodSync(executable, 0o755);
    const external = join(f.directory, 'external');
    mkdirSync(external);
    const notice = join(external, 'notice.md');
    writeFileSync(notice, 'External license notice.\n');
    chmodSync(notice, 0o444);
    chmodSync(external, 0o555);
    const links = new Map([['external-file', notice], ['external-directory', external], ['broken', 'missing']]);
    for (const [name, target] of links) symlinkSync(target, join(f.resources, name));
    try {
      assert.deepEqual(await assertMacUpdatePermissions(f.app), { files: 2, directories: 3, symlinks: 3 });
      assert.equal(lstatSync(executable).mode & 0o777, 0o755);
      assert.equal(readFileSync(executable, 'utf8'), 'signed executable fixture');
      assert.equal(lstatSync(notice).mode & 0o777, 0o444);
      assert.equal(lstatSync(external).mode & 0o777, 0o555);
      assert.equal(readFileSync(notice, 'utf8'), 'External license notice.\n');
      for (const [name, target] of links) assert.equal(readlinkSync(join(f.resources, name)), target);
    } finally {
      chmodSync(external, 0o755);
    }
  });

  it('rejects read-only JRE notices with their bundle path and mode without repairing them', async (t) => {
    const f = fixture(t);
    const legal = join(f.resources, 'jre', 'legal', 'java.xml');
    mkdirSync(legal, { recursive: true });
    const notice = join(legal, 'bcel.md');
    writeFileSync(notice, 'Third-party license notice.\n');
    chmodSync(notice, 0o444);
    await assert.rejects(assertMacUpdatePermissions(f.app), /GateRook\.app\/Contents\/Resources\/jre\/legal\/java\.xml\/bcel\.md \(mode 0444\)/);
    assert.equal(lstatSync(notice).mode & 0o777, 0o444);
    assert.equal(readFileSync(notice, 'utf8'), 'Third-party license notice.\n');
  });

  it('rejects read-only directories and resources outside Contents/Resources', async (t) => {
    const f = fixture(t);
    chmodSync(f.resources, 0o555);
    try {
      await assert.rejects(assertMacUpdatePermissions(f.app), /Contents\/Resources \(mode 0555\)/);
      assert.equal(lstatSync(f.resources).mode & 0o777, 0o555);
    } finally {
      chmodSync(f.resources, 0o755);
    }
    const executable = join(f.app, 'Contents', 'GateRook');
    writeFileSync(executable, 'signed executable fixture');
    chmodSync(executable, 0o555);
    await assert.rejects(assertMacUpdatePermissions(f.app), /Contents\/GateRook \(mode 0555\)/);
    assert.equal(lstatSync(executable).mode & 0o777, 0o555);
  });

  it('rejects missing or malformed bundles and does not accept a symlink as the bundle root', async (t) => {
    const f = fixture(t);
    await assert.rejects(assertMacUpdatePermissions(''), /packaged \.app directory/);
    await assert.rejects(assertMacUpdatePermissions(f.directory), /packaged \.app directory/);
    await assert.rejects(assertMacUpdatePermissions(join(f.directory, 'missing.app')), /ENOENT/);
    const alias = join(f.directory, 'Alias.app');
    symlinkSync(f.app, alias);
    await assert.rejects(assertMacUpdatePermissions(alias), /Expected a real directory: Alias\.app/);
    rmSync(join(f.app, 'Contents', 'Info.plist'));
    await assert.rejects(assertMacUpdatePermissions(f.app), /ENOENT.*Info\.plist/);
  });

  it('CLI succeeds for a valid bundle and returns failure for bad permissions or arguments', (t) => {
    const f = fixture(t);
    const invoke = (...args) => spawnSync(process.execPath, [script, ...args], { encoding: 'utf8' });
    const valid = invoke(f.app);
    assert.equal(valid.status, 0, valid.stderr);
    assert.match(valid.stdout, /permissions verified/);
    const info = join(f.app, 'Contents', 'Info.plist');
    chmodSync(info, 0o444);
    const invalid = invoke(f.app);
    assert.equal(invalid.status, 1);
    assert.match(invalid.stderr, /Info\.plist \(mode 0444\)/);
    assert.equal(lstatSync(info).mode & 0o777, 0o444);
    assert.equal(invoke().status, 1);
    assert.equal(invoke(f.app, 'extra').status, 1);
    assert.match(invoke('--help').stderr, /Usage:/);
  });
});

it('cannot certify POSIX permissions from Windows metadata', { skip: process.platform !== 'win32' }, async () => {
  await assert.rejects(assertMacUpdatePermissions('GateRook.app'), /POSIX filesystem/);
});
