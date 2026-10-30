import assert from 'node:assert/strict';
import { chmodSync, existsSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, readlinkSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import { Platform } from 'app-builder-lib';
import { resolveFunction } from 'app-builder-lib/out/util/resolve.js';
import { Arch } from 'builder-util';
import { load } from 'js-yaml';

const repository = fileURLToPath(new URL('../../', import.meta.url));
const builderConfig = load(readFileSync(new URL('../electron-builder.yml', import.meta.url), 'utf8'));
const afterPack = await resolveFunction('module', builderConfig.afterPack, 'afterPack', repository);

function fixture(t, { platform = 'darwin', config = builderConfig, platformOptions = config.mac } = {}) {
  const scratch = join(repository, 'ai');
  mkdirSync(scratch, { recursive: true });
  const directory = mkdtempSync(join(scratch, 'after-pack-test-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const resources = join(directory, 'GateRook.app', 'Contents', 'Resources');
  mkdirSync(resources, { recursive: true });
  const appInfo = { updaterCacheDirName: 'gaterook-desktop-updater', channel: null };
  const packager = {
    platform: Platform.MAC,
    config,
    platformSpecificBuildOptions: platformOptions,
    appInfo,
    info: { config, appInfo },
    expandMacro: (value) => value,
    getResourcesDir: (appOutDir) => {
      assert.equal(appOutDir, directory);
      return resources;
    },
  };
  return {
    context: { electronPlatformName: platform, packager, arch: Arch.arm64, appOutDir: directory, targets: [] },
    directory,
    resources,
    metadata: join(resources, 'app-update.yml'),
  };
}

describe('macOS update metadata packaging', () => {
  it('includes the configured feed and updater cache in directory-only packages', async (t) => {
    const f = fixture(t);
    await afterPack(f.context);
    assert.deepEqual(load(readFileSync(f.metadata, 'utf8')), {
      provider: 'github', owner: 'msrv-ai', repo: 'gaterook', updaterCacheDirName: 'gaterook-desktop-updater',
    });
  });

  it('uses the same metadata for directory-only and installer targets', async (t) => {
    const directory = fixture(t);
    const installer = fixture(t);
    installer.context.targets = [{ name: 'dmg' }, { name: 'zip' }];
    await afterPack(directory.context);
    await afterPack(installer.context);
    assert.equal(readFileSync(directory.metadata, 'utf8'), readFileSync(installer.metadata, 'utf8'));
  });

  it('resolves platform publish overrides through electron-builder', async (t) => {
    const f = fixture(t, { platformOptions: { publish: { provider: 'generic', url: 'https://updates.example.test/' } } });
    await afterPack(f.context);
    assert.deepEqual(load(readFileSync(f.metadata, 'utf8')), {
      provider: 'generic', url: 'https://updates.example.test/', updaterCacheDirName: 'gaterook-desktop-updater',
    });
  });

  it('respects explicitly disabled update sources', async (t) => {
    for (const options of [{ config: { ...builderConfig, publish: null } }, { platformOptions: { publish: null } }]) {
      const f = fixture(t, options);
      await afterPack(f.context);
      assert.equal(existsSync(f.metadata), false);
    }
  });

  it('leaves Windows, Linux and Mac App Store packaging to electron-builder', async () => {
    for (const electronPlatformName of ['win32', 'linux', 'mas']) {
      await afterPack({ electronPlatformName });
    }
  });

  it('fails packaging when the update source is invalid', async (t) => {
    const f = fixture(t, { platformOptions: { publish: { provider: 'generic' } } });
    await assert.rejects(afterPack(f.context), /Please specify "url"/);
    assert.equal(existsSync(f.metadata), false);
  });
});

describe('macOS update resource permissions', () => {
  const posixOnly = { skip: process.platform === 'win32' };

  it('makes read-only JRE resources writable while preserving contents and other permission bits', posixOnly, async (t) => {
    const f = fixture(t);
    const legal = join(f.resources, 'jre', 'legal', 'java.xml');
    const bin = join(f.resources, 'jre', 'bin');
    mkdirSync(legal, { recursive: true });
    mkdirSync(bin, { recursive: true });
    const notice = join(legal, 'bcel.md');
    const executable = join(bin, 'java');
    const restricted = join(f.resources, 'jre', 'release');
    writeFileSync(notice, 'Third-party license notice.\n');
    writeFileSync(executable, 'runtime executable');
    writeFileSync(restricted, 'JAVA_RUNTIME_VERSION="25.0.2"\n');
    chmodSync(notice, 0o444);
    chmodSync(executable, 0o555);
    chmodSync(restricted, 0o640);
    chmodSync(legal, 0o550);
    chmodSync(f.resources, 0o555);
    try {
      await afterPack(f.context);
      assert.equal(statSync(notice).mode & 0o777, 0o644);
      assert.equal(statSync(executable).mode & 0o777, 0o755);
      assert.equal(statSync(restricted).mode & 0o777, 0o640);
      assert.equal(statSync(legal).mode & 0o777, 0o750);
      assert.equal(statSync(f.resources).mode & 0o777, 0o755);
      assert.equal(readFileSync(notice, 'utf8'), 'Third-party license notice.\n');
      assert.equal(readFileSync(executable, 'utf8'), 'runtime executable');
      assert.equal(readFileSync(restricted, 'utf8'), 'JAVA_RUNTIME_VERSION="25.0.2"\n');
      assert.ok(existsSync(f.metadata), 'the read-only resource root must permit update metadata creation');
    } finally {
      chmodSync(legal, 0o750);
      chmodSync(f.resources, 0o755);
    }
  });

  it('preserves symlinks without changing or traversing external targets', posixOnly, async (t) => {
    const f = fixture(t);
    const external = join(f.directory, 'external');
    mkdirSync(external);
    const notice = join(external, 'notice.md');
    writeFileSync(notice, 'External notice.\n');
    chmodSync(notice, 0o444);
    chmodSync(external, 0o555);
    const targets = new Map([
      ['notice-link', notice], ['directory-link', external], ['broken-link', 'missing-target'],
    ]);
    for (const [name, target] of targets) symlinkSync(target, join(f.resources, name));
    try {
      await afterPack(f.context);
      for (const [name, target] of targets) {
        const link = join(f.resources, name);
        assert.equal(lstatSync(link).isSymbolicLink(), true);
        assert.equal(readlinkSync(link), target);
      }
      assert.equal(statSync(notice).mode & 0o777, 0o444);
      assert.equal(statSync(external).mode & 0o777, 0o555);
      assert.equal(readFileSync(notice, 'utf8'), 'External notice.\n');
    } finally {
      chmodSync(external, 0o755);
    }
  });

  it('normalizes resources even when update publishing is disabled', posixOnly, async (t) => {
    for (const options of [{ config: { ...builderConfig, publish: null } }, { platformOptions: { publish: null } }]) {
      const f = fixture(t, options);
      const notice = join(f.resources, 'notice.md');
      writeFileSync(notice, 'License notice.\n');
      chmodSync(notice, 0o444);
      await afterPack(f.context);
      assert.equal(statSync(notice).mode & 0o777, 0o644);
      assert.equal(existsSync(f.metadata), false);
    }
  });

  it('leaves resource permissions unchanged on other packaging platforms', posixOnly, async (t) => {
    for (const platform of ['win32', 'linux', 'mas']) {
      const f = fixture(t, { platform });
      const notice = join(f.resources, 'notice.md');
      writeFileSync(notice, 'License notice.\n');
      chmodSync(notice, 0o444);
      await afterPack(f.context);
      assert.equal(statSync(notice).mode & 0o777, 0o444);
      assert.equal(existsSync(f.metadata), false);
    }
  });
});
